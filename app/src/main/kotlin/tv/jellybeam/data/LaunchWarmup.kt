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
    /** [account] is `null` for a signed-out launch. [settings] and [views] are the launch reads,
     * `null` when they failed; [homeLayout] seeds the session's committed Home layout (docs/25
     * §6.1).
     */
    class Session(val account: AccountInfo?, val settings: Settings?, val views: List<ViewSnapshot>?) {
        val homeLayout: HomeLayout get() = settings?.homeLayout ?: HomeLayout.CLASSIC
    }

    /** [stale]: the mirror changed after [snapshot] was read, so the taker owes one refresh.
     * [settings]/[views] are the launch reads the snapshot was built beside; `null` when either
     * failed, so the taker fetches its own.
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
        // Fail open (docs/05): a mirror that won't open still lands on Home's empty state.
        if (account != null) runCatching { gateway.openMirror() }
        // In-memory settings read, no network; a failure leaves the default layout.
        val settings = runCatching { gateway.getSettings() }.getOrNull()
        // Local mirror read, so only after openMirror; the startup-screen pick and Home's chrome
        // both need it (docs/10).
        val views = if (account != null) runCatching { gateway.views() }.getOrNull() else null
        Session(account, settings, views)
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
        val snapshot = runCatching { gateway.homeSnapshot(restored.homeLayout) }.getOrNull() ?: return@async null
        // Re-read under the watcher, so a views write between the session's read and the
        // subscription can't leave the seeded chrome behind the snapshot.
        val views = runCatching { gateway.views() }.getOrNull()
        Prefetched(snapshot, restored.settings, views)
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
