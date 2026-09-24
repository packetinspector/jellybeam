package tv.jellybeam.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import tv.jellybeam.data.CoreGateway
import tv.jellybeam.data.LaunchWarmup
import tv.jellybeam.player.PlaybackReports
import tv.jellybeam.ui.common.ChangeRefreshScheduler
import tv.jellybeam.ui.common.serverHostLabel
import uniffi.jellybeam_core.Card
import uniffi.jellybeam_core.LatestShelf
import uniffi.jellybeam_core.SyncStatus
import uniffi.jellybeam_core.ViewSnapshot

/** How many "latest" items to ask the mirror for per library shelf; [LaunchWarmup] prefetches
 * the first snapshot with the same value.
 */
const val HOME_LATEST_PER_VIEW = 30u

/** No [uniffi.jellybeam_core.ChangeEvent] variant fires on sync start/stop, so
 * [HomeViewModel.pollSyncing] polls on its own timer.
 */
private const val SYNC_POLL_INTERVAL_MS = 3_000L

/** [HomeViewModel.pollSyncStatus]'s poll cadence, faster than [SYNC_POLL_INTERVAL_MS], for as
 * long as [HomeUiState.isLoading] stays true.
 */
private const val SYNC_STATUS_POLL_INTERVAL_MS = 1_000L

/** [loadingStatusText]'s escalation threshold: after this many [SYNC_STATUS_POLL_INTERVAL_MS]
 * ticks of [SyncStatus.Idle] while still loading (~2s), "Loading…" becomes "Still loading…" --
 * covers a cold start stuck before any library sync has begun.
 */
private const val STILL_LOADING_AFTER_TICKS = 2

data class HomeUiState(
    val isLoading: Boolean = true,
    val isSyncing: Boolean = false,
    val resume: List<Card> = emptyList(),
    val nextUp: List<Card> = emptyList(),
    val latest: List<LatestShelf> = emptyList(),
    /** The library/tab row above the shelves -- server order, names shown verbatim. */
    val views: List<ViewSnapshot> = emptyList(),
    /** Cold-start status line for [HomeScreen]'s loading skeleton (see [loadingStatusText]);
     * meaningful only while [isLoading] is true.
     */
    val loadingStatusText: String = "Loading…",
    /** [syncProgressText] for a background sync after the skeleton, refreshed by
     * [HomeViewModel.pollSyncing]; `null` while [isSyncing] is false or no progress is known.
     */
    val syncProgressText: String? = null,
    /** Home top bar's clock, mirroring [uniffi.jellybeam_core.Settings.showClock]; refreshed in
     * [refreshSnapshotOnce].
     */
    val showClock: Boolean = true,
    /** `host[:port]` of the active server for the empty-library spec strip (§6.2); loaded only
     * when that state shows, so the hot refresh path stays untouched (docs/10).
     */
    val serverHost: String? = null,
)

/** [HomeUiState.loadingStatusText]'s pure mapping, pulled out of [HomeViewModel] so it's
 * JVM-testable. Library names pass through verbatim, never prettified. [tickCount] is
 * [HomeViewModel.pollSyncStatus]'s poll-loop counter, not a wall-clock read.
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
        "Syncing $library — ${status.itemsDone} items…"
    }
}

/**
 * Assembles and keeps the Home screen's shelves in sync with the mirror. Shelf composition per
 * docs/07-home-browse-behavior.md §1: Continue Watching, then Next Up (de-duplicated by id against
 * it, since the Rust side doesn't), then one "Latest in {view}" shelf per library with anything to
 * show; empty-shelf hiding is left to the Composable.
 *
 * [refreshSnapshot] and [pollSyncing] are independent loops, so a syncing-flag flip never
 * re-fetches [CoreGateway.homeSnapshot]. Every section [refreshSnapshot] emits is diffed against
 * held state ([reuseIfUnchanged]/[mergeLatestShelves]) so a redundant marshal still hands the UI
 * the same list instance -- `List<Card>` is Compose-unstable, so strong skipping needs referential
 * equality, not structural `equals`.
 *
 * docs/16-library-sort-filter.md §4.6, docs/17-mini-player.md §6: a resetting `debounce(500)`
 * never fires while a sync burst keeps events under 500ms apart. [changeRefreshScheduler] drives
 * [refreshSnapshot] instead: leading refresh, bounded periodic sampling, and a guaranteed trailing
 * refresh, gated by [setActive] so a hidden Home samples at a slower, bounded rate.
 */
class HomeViewModel(
    private val gateway: CoreGateway,
    /** docs/17-mini-player.md §6: the mini-player dismissal edge, injected so a test can drive
     * it directly instead of the real [PlaybackReports] singleton.
     */
    stopEpoch: Flow<Long> = PlaybackReports.stopEpoch,
    /** The cold-start prefetch; the first load takes it instead of marshalling its own. */
    private val launchWarmup: LaunchWarmup? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    /** docs/17-mini-player.md §6: true while Home is top of its stack and the host Activity is
     * resumed, fed by [HomeScreen]'s `setActive(isTop)`. Drives [changeRefreshScheduler]'s
     * sampling rate and gates the FFI call in [pollSyncing]/[pollSyncStatus].
     */
    private val _active = MutableStateFlow(false)

    fun setActive(active: Boolean) {
        _active.value = active
    }

    /** See [changeRefreshScheduler] for the sampling contract. */
    private val changeRefreshScheduler = ChangeRefreshScheduler(
        scope = viewModelScope,
        events = gateway.changeEvents(),
        active = _active.asStateFlow(),
        refresh = ::refreshSnapshot,
    )

    init {
        viewModelScope.launch { refreshSnapshot() }
        viewModelScope.launch { pollSyncing() }
        viewModelScope.launch { pollSyncStatus() }
        // docs/17-mini-player.md §6: dismissing the PiP window has no other edge back into Home.
        // `drop(1)`: the flow's seed value at collection time isn't itself a stop that just landed.
        viewModelScope.launch {
            stopEpoch.drop(1).collect { refreshSnapshot() }
        }
    }

    /**
     * Immediate refresh for returns to Home and explicit invalidations, bypassing the event
     * scheduler. [onHostResume] skips only the initial lifecycle replay; init owns that load.
     */
    fun refreshNow() {
        viewModelScope.launch { refreshSnapshot() }
    }

    private var receivedHostResume = false

    /** Init owns the first load; later host resumes must catch up even without a mirror event. */
    fun onHostResume() {
        if (receivedHostResume) refreshNow()
        receivedHostResume = true
    }

    /**
     * Conflation guard: init, a later host resume, a return to Home, and a change-event can all
     * fire within the same window. Both flags are only touched from [viewModelScope]'s
     * main-confined coroutines, so plain vars suffice. A refresh requested while one is in flight
     * isn't dropped -- it runs once more after.
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

    /** Fills [HomeUiState.serverHost] for the empty-library state; a no-op once known. */
    fun loadServerHost() {
        if (_state.value.serverHost != null) return
        viewModelScope.launch {
            val host = runCatching { serverHostLabel(gateway.serverInfoSnapshot().serverUrl) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull() ?: return@launch
            _state.update { it.copy(serverHost = host) }
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
        val snapshot = prefetched?.snapshot ?: gateway.homeSnapshot(HOME_LATEST_PER_VIEW)
        val nextUpDeduped = dedupAgainst(snapshot.resume.map(Card::id).toSet(), snapshot.nextUp)
        val views = viewsDeferred.await()
        val settings = settingsDeferred.await()
        _state.update { current ->
            current.copy(
                isLoading = false,
                resume = reuseIfUnchanged(current.resume, snapshot.resume),
                nextUp = reuseIfUnchanged(current.nextUp, nextUpDeduped),
                latest = mergeLatestShelves(current.latest, snapshot.latest),
                views = reuseIfUnchanged(current.views, views),
                showClock = settings.showClock,
            )
        }
    }

    /**
     * Runs for the ViewModel's whole lifetime, independently of [refreshSnapshot] (see
     * [SYNC_POLL_INTERVAL_MS]). A JVM test constructing a [HomeViewModel] directly must clear it
     * (`ViewModelStore.clear()`) before its `runTest` body ends, or this loop keeps re-queuing
     * against the virtual clock during cleanup.
     *
     * docs/17-mini-player.md §6: the timer keeps running while hidden, but the FFI call is skipped
     * while [_active] is false, since nobody can see the syncing indicator on a screen that isn't
     * top.
     */
    private suspend fun pollSyncing() {
        while (true) {
            if (_active.value) {
                val syncing = gateway.isSyncing()
                val progress = if (syncing) (gateway.syncStatus() as? SyncStatus.Syncing)?.let(::syncProgressText) else null
                _state.update { it.copy(isSyncing = syncing, syncProgressText = progress) }
            }
            delay(SYNC_POLL_INTERVAL_MS)
        }
    }

    /**
     * Polls [CoreGateway.syncStatus] roughly once a second while [HomeUiState.isLoading] stays
     * true -- the window [HomeScreen]'s cold-start skeleton is on screen. Unlike [pollSyncing],
     * bounded rather than lifetime-long: [refreshSnapshotOnce] sets `isLoading = false` exactly
     * once and never back, so the loop simply stops.
     *
     * docs/17-mini-player.md §6: same [_active] gating as [pollSyncing], so a reactivation may see
     * text up to [SYNC_STATUS_POLL_INTERVAL_MS] stale; [refreshSnapshotOnce] ends the skeleton.
     */
    private suspend fun pollSyncStatus() {
        var tick = 0
        while (_state.value.isLoading) {
            if (_active.value) {
                val status = gateway.syncStatus()
                _state.update { it.copy(loadingStatusText = loadingStatusText(status, tick)) }
                tick++
            }
            delay(SYNC_STATUS_POLL_INTERVAL_MS)
        }
    }

    companion object {
        /** `dedup_against` (home.rs:827-836): drops any item whose id is already shown. */
        fun dedupAgainst(excludeIds: Set<String>, items: List<Card>): List<Card> =
            items.filter { it.id !in excludeIds }
    }
}

/** [incoming] is a fresh FFI marshal every call, so structural equality is the only way to detect
 * nothing changed; returns [current] itself when equal, so a downstream `remember{}`/strong-
 * skipping check sees referential equality across a no-op refresh.
 */
private fun <T> reuseIfUnchanged(current: T, incoming: T): T =
    if (current == incoming) current else incoming

/** Per-shelf variant of [reuseIfUnchanged]: a sync burst touching one library must not force
 * every other "Latest in {view}" shelf's list to be reallocated. Matches shelves by
 * [LatestShelf.viewId], never list position.
 */
private fun mergeLatestShelves(current: List<LatestShelf>, incoming: List<LatestShelf>): List<LatestShelf> {
    val currentByViewId = current.associateBy { it.viewId }
    val merged = incoming.map { shelf ->
        val existing = currentByViewId[shelf.viewId]
        if (existing != null && existing == shelf) existing else shelf
    }
    return if (merged == current) current else merged
}

class HomeViewModelFactory(
    private val gateway: CoreGateway,
    /** See [HomeViewModel]'s own constructor param of the same name. */
    private val stopEpoch: Flow<Long> = PlaybackReports.stopEpoch,
    private val launchWarmup: LaunchWarmup? = null,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(HomeViewModel::class.java))
        return HomeViewModel(gateway, stopEpoch, launchWarmup) as T
    }
}
