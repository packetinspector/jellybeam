package tv.jellybeam.data

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import uniffi.jellybeam_core.AccountInfo
import uniffi.jellybeam_core.HomeLayout
import uniffi.jellybeam_core.HomeSnapshot
import uniffi.jellybeam_core.Settings
import uniffi.jellybeam_core.ViewSnapshot

/**
 * Cold-start head start (docs/10 "Startup phases"): `restoreSession` -> `openMirror` -> the first
 * Home snapshot run from process start, overlapping the Activity's first frame instead of queuing
 * behind it. Each result is handed out once; every later session (account switch, add server,
 * Activity recreation) takes `null` and runs the ordinary path.
 */
class LaunchWarmup(
    private val gateway: CoreGateway,
    private val scope: CoroutineScope,
) {
    /** [account] is `null` for a signed-out launch. [settings] is the launch read, `null` when it
     * failed; [views] is the list [CoreGateway.viewsState] held once the mirror opened (empty when
     * it failed to open), `null` only for a signed-out launch; [homeLayout] seeds the session's committed Home layout (docs/25
     * §6.1).
     */
    class Session(val account: AccountInfo?, val settings: Settings?, val views: List<ViewSnapshot>?) {
        val homeLayout: HomeLayout get() = settings?.homeLayout ?: HomeLayout.CLASSIC
    }

    /** [stale]: the mirror changed after [snapshot] was read, so the taker owes one refresh.
     * [settings] is the launch read the snapshot was built beside, `null` when it failed, so the
     * taker fetches its own; [views] is [CoreGateway.viewsState] as of the snapshot.
     */
    class Home(
        val snapshot: HomeSnapshot,
        val stale: Boolean,
        val settings: Settings? = null,
        val views: List<ViewSnapshot>? = null,
    )

    private class Prefetched(val snapshot: HomeSnapshot, val settings: Settings?, val views: List<ViewSnapshot>?)

    private val changed = AtomicBoolean(false)

    /** Set once the snapshot is taken or discarded; a watcher started after that stops itself. */
    private val released = AtomicBoolean(false)

    private val session: Deferred<Session> = scope.async {
        val account = gateway.restoreSession()
        // In-memory settings read, no network and no mirror: overlaps openMirror. A failure leaves
        // the default layout.
        val settingsRead = async { runCatchingCancellable { gateway.getSettings() }.getOrNull() }
        // Fail open (docs/05): a mirror that won't open still lands on Home's empty state.
        if (account != null) runCatchingCancellable { gateway.openMirror() }
        // The library list openMirror just published (docs/07 §5); the startup-screen pick and
        // Home's chrome both need it (docs/10).
        val views = if (account != null) gateway.viewsState.value else null
        Session(account, settingsRead.await(), views)
    }

    @Volatile
    private var watcher: Job? = null

    private val home: Deferred<Prefetched?> = scope.async {
        val restored = session.await()
        if (restored.account == null) return@async null
        // Subscribed before the snapshot is read and until it is taken, so no write falls between
        // this read and the taker's own subscription. Never before openMirror: the core wires its
        // change listener to the mirror that is open when it registers. On [scope], not this
        // `async`: a child collector would keep the snapshot from ever completing.
        watcher = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            gateway.changeEvents().collect { changed.set(true) }
        }
        if (released.get()) watcher?.cancel()
        val snapshot = runCatchingCancellable { gateway.homeSnapshot(restored.homeLayout) }.getOrNull() ?: return@async null
        Prefetched(snapshot, restored.settings, gateway.viewsState.value)
    }

    private val sessionSlot = AtomicReference<Deferred<Session>?>(session)
    private val homeSlot = AtomicReference<Deferred<Prefetched?>?>(home)

    /** The restored session with its mirror already open, or `null` once taken or discarded. */
    suspend fun takeSession(): Session? = sessionSlot.getAndSet(null)?.await()

    /** The prefetched first Home snapshot, or `null` once taken, discarded, signed out or failed. */
    suspend fun takeHome(): Home? {
        val prefetched = homeSlot.getAndSet(null)?.await()
        // Read after the await: the taker subscribed to change events before calling this.
        val stale = changed.get()
        release()
        return prefetched?.let { Home(it.snapshot, stale, it.settings, it.views) }
    }

    /**
     * Non-suspending look at the prefetch for first-composition seeding: `null` unless it already
     * completed, so the caller never waits. Takes nothing; the seeded caller still owes
     * [takeHome], which settles the stale flag and releases the change watcher. [Home.stale]
     * here is only the state at this instant.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun peekHome(): Home? {
        val deferred = homeSlot.get() ?: return null
        if (!deferred.isCompleted || deferred.isCancelled) return null
        val prefetched = runCatching { deferred.getCompleted() }.getOrNull() ?: return null
        return Home(prefetched.snapshot, changed.get(), prefetched.settings, prefetched.views)
    }

    /** A session reset: whatever was prefetched belongs to the session being replaced. */
    fun discard() {
        sessionSlot.set(null)
        homeSlot.set(null)
        release()
    }

    private fun release() {
        released.set(true)
        watcher?.cancel()
    }
}
