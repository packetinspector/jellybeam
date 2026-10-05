package tv.jellybeam.ui.home.common

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.jellybeam.data.CoreGateway
import tv.jellybeam.data.LaunchWarmup
import tv.jellybeam.ui.common.ChangeRefreshScheduler
import tv.jellybeam.ui.common.countLabel
import tv.jellybeam.ui.common.serverHostLabel
import uniffi.jellybeam_core.HomeLayout
import uniffi.jellybeam_core.HomeSnapshot
import uniffi.jellybeam_core.SyncStatus
import uniffi.jellybeam_core.ViewSnapshot

/** No [uniffi.jellybeam_core.ChangeEvent] variant fires on sync start/stop, so
 * [HomeFeed.pollSyncing] polls on its own timer.
 */
private const val SYNC_POLL_INTERVAL_MS = 3_000L

/** [HomeFeed.pollSyncStatus]'s poll cadence, faster than [SYNC_POLL_INTERVAL_MS], for as long as
 * [HomeChrome.isLoading] stays true.
 */
private const val SYNC_STATUS_POLL_INTERVAL_MS = 1_000L

/** [loadingStatusText]'s escalation threshold: after this many [SYNC_STATUS_POLL_INTERVAL_MS]
 * ticks of [SyncStatus.Idle] while still loading (~2s), "Loading…" becomes "Still loading…" --
 * covers a cold start stuck before any library sync has begun.
 */
private const val STILL_LOADING_AFTER_TICKS = 2

/** docs/25 §5.4: the Home state every layout draws from and none owns. */
data class HomeChrome(
    val isLoading: Boolean = true,
    val isSyncing: Boolean = false,
    /** Server order, names shown verbatim; drives the empty-library state. */
    val views: List<ViewSnapshot> = emptyList(),
    /** Cold-start status line for the loading skeleton (see [loadingStatusText]); meaningful only
     * while [isLoading] is true.
     */
    val loadingStatusText: String = "Loading…",
    /** [syncProgressText] for a background sync after the skeleton; `null` while [isSyncing] is
     * false or no progress is known.
     */
    val syncProgressText: String? = null,
    /** Mirrors [uniffi.jellybeam_core.Settings.showClock]; refreshed with each snapshot. */
    val showClock: Boolean = true,
    /** Mirrors [uniffi.jellybeam_core.Settings.homeResumePosters]; refreshed with each snapshot. */
    val resumeAsPosters: Boolean = false,
    /** `host[:port]` of the active server for the empty-library strip; loaded only when that
     * state shows, so the hot refresh path stays untouched (docs/10).
     */
    val serverHost: String? = null,
)

/** One emission for both halves, so the skeleton and a layout's first content swap in the same
 * frame rather than flashing the empty state between them.
 */
data class HomeFeedState<U>(val chrome: HomeChrome, val content: U)

/** [HomeChrome.loadingStatusText]'s pure mapping. Library names pass through verbatim, never
 * prettified. [tickCount] is [HomeFeed.pollSyncStatus]'s poll-loop counter, not a wall-clock read.
 */
fun loadingStatusText(status: SyncStatus, tickCount: Int): String = when (status) {
    is SyncStatus.Idle -> if (tickCount >= STILL_LOADING_AFTER_TICKS) "Still loading…" else "Loading…"
    is SyncStatus.Syncing -> syncProgressText(status)
}

/** One sync pass as a status line. The library's name is the server's own, verbatim; a pass whose
 * library the mirror can't name yet reads "library", never an id.
 */
fun syncProgressText(status: SyncStatus.Syncing): String {
    val library = status.libraryName ?: "library"
    val total = status.totalItems
    return if (total != null) {
        "Syncing $library — ${status.itemsDone} of $total…"
    } else {
        "Syncing $library — ${countLabel(status.itemsDone.toULong(), "item", "items")}…"
    }
}

/** [incoming] is a fresh FFI marshal every call, so structural equality is the only way to detect
 * nothing changed; returns [current] itself when equal, so a downstream `remember{}`/strong-
 * skipping check sees referential equality across a no-op refresh.
 */
fun <T> reuseIfUnchanged(current: T, incoming: T): T =
    if (current == incoming) current else incoming

/**
 * docs/25 §5.4: the layout-agnostic half of a Home ViewModel, owned by composition. Fetches
 * [layout]'s snapshot and the shared chrome, and hands the snapshot to the layout through
 * [extract] (this layout's record, `null` for another layout's) and [reduce] (the layout's own
 * mapping, which reuses unchanged sections).
 *
 * [refreshSnapshot] and [pollSyncing] are independent loops, so a syncing-flag flip never
 * re-fetches [CoreGateway.homeSnapshot].
 *
 * docs/16-library-sort-filter.md §4.6, docs/17-mini-player.md §6: a resetting `debounce(500)`
 * never fires while a sync burst keeps events under 500ms apart. [changeRefreshScheduler] drives
 * [refreshSnapshot] instead: leading refresh, bounded periodic sampling, and a guaranteed trailing
 * refresh, gated by [setActive] so a hidden Home samples at a slower, bounded rate.
 */
class HomeFeed<T : Any, U>(
    private val scope: CoroutineScope,
    private val gateway: CoreGateway,
    private val layout: HomeLayout,
    initialContent: U,
    /** docs/17-mini-player.md §6: the mini-player dismissal edge. */
    stopEpoch: Flow<Long>,
    /** The cold-start prefetch; the first load takes it instead of marshalling its own. */
    private val launchWarmup: LaunchWarmup?,
    private val extract: (HomeSnapshot) -> T?,
    private val reduce: (current: U, incoming: T) -> U,
) {
    private val _state = MutableStateFlow(HomeFeedState(HomeChrome(), initialContent))
    val state: StateFlow<HomeFeedState<U>> = _state.asStateFlow()

    /** docs/17-mini-player.md §6: true while Home is top of its stack and the host Activity is
     * resumed. Drives [changeRefreshScheduler]'s sampling rate and gates the FFI call in
     * [pollSyncing]/[pollSyncStatus].
     */
    private val active = MutableStateFlow(false)

    fun setActive(isActive: Boolean) {
        active.value = isActive
    }

    /** See [ChangeRefreshScheduler] for the sampling contract. */
    private val changeRefreshScheduler = ChangeRefreshScheduler(
        scope = scope,
        events = gateway.changeEvents(),
        active = active.asStateFlow(),
        refresh = ::refreshSnapshot,
    )

    init {
        scope.launch { refreshSnapshot() }
        scope.launch { pollSyncing() }
        scope.launch { pollSyncStatus() }
        // docs/17-mini-player.md §6: dismissing the PiP window has no other edge back into Home.
        // `drop(1)`: the flow's seed value at collection time isn't itself a stop that just landed.
        scope.launch {
            stopEpoch.drop(1).collect { refreshSnapshot() }
        }
    }

    /**
     * Immediate refresh for returns to Home and explicit invalidations, bypassing the event
     * scheduler. [onHostResume] skips only the initial lifecycle replay; init owns that load.
     */
    fun refreshNow() {
        scope.launch { refreshSnapshot() }
    }

    private var receivedHostResume = false

    /** Init owns the first load; later host resumes must catch up even without a mirror event. */
    fun onHostResume() {
        if (receivedHostResume) refreshNow()
        receivedHostResume = true
    }

    /**
     * Conflation guard: init, a later host resume, a return to Home, and a change-event can all
     * fire within the same window. Both flags are only touched from [scope]'s main-confined
     * coroutines, so plain vars suffice. A refresh requested while one is in flight isn't dropped
     * -- it runs once more after.
     */
    private var refreshInFlight = false
    private var refreshQueued = false

    private suspend fun refreshSnapshot() {
        if (refreshInFlight) {
            refreshQueued = true
            return
        }
        refreshInFlight = true
        try {
            do {
                refreshQueued = false
                refreshSnapshotOnce()
            } while (refreshQueued)
        } finally {
            refreshInFlight = false
        }
    }

    /** Fills [HomeChrome.serverHost] for the empty-library state; a no-op once known. */
    fun loadServerHost() {
        if (_state.value.chrome.serverHost != null) return
        scope.launch {
            val host = runCatching { serverHostLabel(gateway.serverInfoSnapshot().serverUrl) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull() ?: return@launch
            _state.update { it.copy(chrome = it.chrome.copy(serverHost = host)) }
        }
    }

    private suspend fun refreshSnapshotOnce() = coroutineScope {
        // views()/getSettings() are independent of homeSnapshot(); fetched concurrently so these
        // cheap reads don't queue behind the expensive ~300-Card marshal on the cold-start path.
        val viewsDeferred = async { gateway.views() }
        val settingsDeferred = async { gateway.getSettings() }
        // [changeRefreshScheduler] is already subscribed, so a stale prefetch owes exactly the
        // one trailing pass queued here.
        val prefetched = launchWarmup?.takeHome()
        if (prefetched?.stale == true) refreshQueued = true
        // docs/25 §6.3: a prefetch built for another layout is dropped for this layout's own.
        val incoming = prefetched?.snapshot?.let(extract) ?: extract(gateway.homeSnapshot(layout))
        val views = viewsDeferred.await()
        val settings = settingsDeferred.await()
        _state.update { current ->
            HomeFeedState(
                chrome = current.chrome.copy(
                    isLoading = false,
                    views = reuseIfUnchanged(current.chrome.views, views),
                    showClock = settings.showClock,
                    resumeAsPosters = settings.homeResumePosters,
                ),
                content = incoming?.let { reduce(current.content, it) } ?: current.content,
            )
        }
    }

    /**
     * Runs for the feed's whole lifetime, independently of [refreshSnapshot] (see
     * [SYNC_POLL_INTERVAL_MS]); a JVM test must cancel [scope] before its `runTest` body ends.
     *
     * docs/17-mini-player.md §6: the timer keeps running while hidden, but the FFI call is skipped
     * while [active] is false, since nobody can see the syncing indicator on a screen that isn't
     * top.
     */
    private suspend fun pollSyncing() {
        while (true) {
            if (active.value) {
                val syncing = gateway.isSyncing()
                val progress = if (syncing) (gateway.syncStatus() as? SyncStatus.Syncing)?.let(::syncProgressText) else null
                _state.update { it.copy(chrome = it.chrome.copy(isSyncing = syncing, syncProgressText = progress)) }
            }
            delay(SYNC_POLL_INTERVAL_MS)
        }
    }

    /**
     * Polls [CoreGateway.syncStatus] roughly once a second while [HomeChrome.isLoading] stays true
     * -- the window the cold-start skeleton is on screen. Bounded rather than lifetime-long:
     * [refreshSnapshotOnce] sets `isLoading = false` exactly once and never back.
     *
     * docs/17-mini-player.md §6: same [active] gating as [pollSyncing], so a reactivation may see
     * text up to [SYNC_STATUS_POLL_INTERVAL_MS] stale; [refreshSnapshotOnce] ends the skeleton.
     */
    private suspend fun pollSyncStatus() {
        var tick = 0
        while (_state.value.chrome.isLoading) {
            if (active.value) {
                val status = gateway.syncStatus()
                _state.update { it.copy(chrome = it.chrome.copy(loadingStatusText = loadingStatusText(status, tick))) }
                tick++
            }
            delay(SYNC_STATUS_POLL_INTERVAL_MS)
        }
    }
}
