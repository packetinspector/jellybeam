package tv.jellybeam

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import android.view.ViewTreeObserver
import android.view.Window
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.profileinstaller.ProfileVerifier
import tv.jellybeam.nav.ExitConfirmationGate
import tv.jellybeam.nav.NavBackStack
import tv.jellybeam.nav.Screen
import tv.jellybeam.nav.diagName
import tv.jellybeam.nav.entryIdentity
import tv.jellybeam.nav.entryKeys
import tv.jellybeam.nav.isHomeRooted
import tv.jellybeam.nav.resolveStartupView
import tv.jellybeam.nav.screenForLibraryCard
import tv.jellybeam.perf.FrameStatsHistogram
import tv.jellybeam.perf.PerfLog
import tv.jellybeam.perf.ProfileStatus
import tv.jellybeam.player.ExternalPlaybackContract
import tv.jellybeam.player.ExternalPlaybackIntentResult
import tv.jellybeam.player.PlaybackActivity
import tv.jellybeam.player.authorizationRecoveryCoordinator
import tv.jellybeam.player.pipController
import tv.jellybeam.player.playbackActivityTracker
import tv.jellybeam.ui.common.serverHostLabel
import tv.jellybeam.ui.detail.DetailScreen
import tv.jellybeam.ui.discover.DiscoverDetailScreen
import tv.jellybeam.ui.discover.DiscoverGridScreen
import tv.jellybeam.ui.discover.DiscoverPersonScreen
import tv.jellybeam.ui.discover.DiscoverRequestsScreen
import tv.jellybeam.ui.discover.DiscoverScreen
import tv.jellybeam.ui.discover.DiscoverSearchScreen
import tv.jellybeam.ui.discover.resolveLibraryCard
import tv.jellybeam.ui.home.HomeScreen
import tv.jellybeam.ui.launch.LaunchScreen
import tv.jellybeam.ui.library.LibraryScreen
import tv.jellybeam.ui.nav.NavDrawerHost
import tv.jellybeam.ui.report.ReportScreen
import tv.jellybeam.ui.search.SearchScreen
import tv.jellybeam.ui.server.ServerManagementScreen
import tv.jellybeam.ui.settings.SettingsScreen
import tv.jellybeam.ui.signin.ReauthorizationTarget
import tv.jellybeam.ui.signin.SignInScreen
import tv.jellybeam.data.displayMessage
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import uniffi.jellybeam_core.AccountInfo
import uniffi.jellybeam_core.Card
import uniffi.jellybeam_core.CoreException
import uniffi.jellybeam_core.SeerrBrowseKind
import uniffi.jellybeam_core.SeerrMediaType
import uniffi.jellybeam_core.ViewSnapshot

class MainActivity : ComponentActivity() {
    /** Non-null only while [PerfLog.enabled] and this Activity is resumed -- see
     * [startFrameMetrics]/[stopFrameMetrics].
     */
    private var frameMetricsThread: HandlerThread? = null

    /**
     * Actually a `Window.OnFrameMetricsAvailableListener` (API 24+), kept behind [Any]: this
     * app's minSdk is 23, and a field *declared* as an API-24 interface would put that type
     * into [MainActivity]'s class descriptor unconditionally. An `as?` cast at the one read
     * site ([stopFrameMetrics]) keeps this class loadable on API 23 with zero doubt.
     */
    private var frameMetricsListener: Any? = null

    /**
     * External PLAY intents are parsed only once this Activity is resumed. With `singleTask`,
     * Android first removes a currently running PlaybackActivity above it, unless it's in PiP
     * (docs/17-mini-player.md §3) -- the external-PLAY `LaunchedEffect` below skips
     * [playbackActivityTracker.awaitIdle] in that case and delivers straight to the live
     * instance. [playbackActivityTracker] is the barrier that keeps Compose from launching a
     * replacement until the old (non-PiP) player's teardown has actually completed.
     */
    private var externalIntentAwaitingResume: Intent? = null
    private var pendingExternalPlayback by mutableStateOf<ExternalPlaybackRequest?>(null)
    private var nextExternalPlaybackRequestId = 0L

    /**
     * Compose-visible lifecycle edge for retained-screen focus recovery. The navigation stack
     * doesn't change when PlaybackActivity covers this Activity, so a screen keyed only on "is
     * top of stack" would never observe the false -> true transition when playback finishes.
     */
    private var activityResumed by mutableStateOf(false)

    /** [SystemClock.elapsedRealtime] at first frame, `null` until then -- lets [JellybeamRoot] gate
     * the cold-start `validateSession` call until slightly after the startup window instead of
     * racing it (docs/10-perf-logging.md). Set from [markFirstFrame], unconditionally. */
    private var firstFrameElapsedMs by mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // System splash (docs/brand.md §6.3): no keep-on-screen condition, so this hands
        // off to the Compose launch screen at the next frame rather than blocking on core init.
        installSplashScreen()
        val createStartElapsedMs = SystemClock.elapsedRealtime()
        super.onCreate(savedInstanceState)
        PerfLog.markStartup("activity.onCreate")
        externalIntentAwaitingResume = intent
        // docs/21 §1.3: the crash dialog never appears on a launch headed straight into
        // playback/detail -- computed once from this Activity's own launch intent (not the
        // live, soon-consumed pendingExternalPlayback state) so it can't race consumption.
        val launchedForExternalPlayback = isExternalPlaybackIntent(intent)
        setContent {
            JellybeamRoot(
                onExitApp = ::finish,
                externalPlaybackRequest = pendingExternalPlayback,
                onExternalPlaybackConsumed = { consumed ->
                    if (pendingExternalPlayback == consumed) pendingExternalPlayback = null
                },
                activityResumed = activityResumed,
                launchedForExternalPlayback = launchedForExternalPlayback,
                firstFrameAtMs = firstFrameElapsedMs,
            )
        }
        markFirstFrame(createStartElapsedMs)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        externalIntentAwaitingResume = intent
        // Already-resumed singleTask Activity: consume immediately (no onResume() follows).
        // Otherwise retain until onResume so the old player's onStop/report wins first.
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            acceptExternalPlaybackIntent(intent)
            externalIntentAwaitingResume = null
        }
    }

    /**
     * `onResume` is a rare lifecycle callback, so [PerfLog.refresh] here is cheap and is what
     * lets `adb shell setprop log.tag.JellybeamTV DEBUG` take effect against an already-running
     * process. The frame-metrics listener is re-armed on the same schedule since its
     * registration can't be cheap to poll continuously; [AppForeground.resumeCount] rides the
     * same schedule to give composables a cheap foreground-return signal.
     */
    override fun onResume() {
        super.onResume()
        activityResumed = true
        externalIntentAwaitingResume?.let(::acceptExternalPlaybackIntent)
        externalIntentAwaitingResume = null
        PerfLog.refresh()
        if (PerfLog.enabled) startFrameMetrics() else stopFrameMetrics()
        if (PerfLog.enabled) logProfileStatusOnce()
        AppForeground.resumeCount.intValue++
        AppForeground.browsing.value = true
    }

    /**
     * Verifies the embedded Baseline Profile actually compiled into this install
     * (docs/10-perf-logging.md "Profile status"). Once per process ([profileStatusLogged] is a
     * companion field, so a second [MainActivity] instance never re-logs), only reached from
     * [onResume]. `runCatching` since this is diagnostic-only and must never crash a resume.
     */
    private fun logProfileStatusOnce() {
        if (profileStatusLogged) return
        profileStatusLogged = true
        runCatching {
            val future = ProfileVerifier.getCompilationStatusAsync()
            future.addListener(
                {
                    runCatching {
                        val r = future.get()
                        Log.d(
                            PerfLog.TAG,
                            ProfileStatus.line(
                                resultCode = r.profileInstallResultCode,
                                compiledWithProfile = r.isCompiledWithProfile,
                                enqueued = r.hasProfileEnqueuedForCompilation(),
                            ),
                        )
                    }
                },
                Runnable::run,
            )
        }
    }

    /** docs/21 §1.3: whether [intent] is a play-deep-link/OPEN_DETAIL launch, computed with the
     * same parse [acceptExternalPlaybackIntent] uses -- the crash dialog's gate. */
    private fun isExternalPlaybackIntent(intent: Intent): Boolean =
        when (ExternalPlaybackContract.parse(intent.action, intent.getStringExtra(ExternalPlaybackContract.EXTRA_ITEM_ID), intent.dataString)) {
            is ExternalPlaybackIntentResult.Play, is ExternalPlaybackIntentResult.OpenDetail -> true
            ExternalPlaybackIntentResult.NotExternal, ExternalPlaybackIntentResult.Invalid -> false
        }

    private fun acceptExternalPlaybackIntent(intent: Intent) {
        when (val parsed = ExternalPlaybackContract.parse(intent.action, intent.getStringExtra(ExternalPlaybackContract.EXTRA_ITEM_ID), intent.dataString)) {
            ExternalPlaybackIntentResult.NotExternal -> Unit
            ExternalPlaybackIntentResult.Invalid -> Toast.makeText(this, R.string.external_playback_invalid, Toast.LENGTH_LONG).show()
            is ExternalPlaybackIntentResult.Play -> {
                pendingExternalPlayback =
                    ExternalPlaybackRequest(++nextExternalPlaybackRequestId, parsed.itemId, ExternalPlaybackRequestKind.PLAY)
            }
            is ExternalPlaybackIntentResult.OpenDetail -> {
                pendingExternalPlayback =
                    ExternalPlaybackRequest(++nextExternalPlaybackRequestId, parsed.itemId, ExternalPlaybackRequestKind.OPEN_DETAIL)
            }
        }
    }

    override fun onPause() {
        AppForeground.browsing.value = false
        activityResumed = false
        stopFrameMetrics()
        super.onPause()
    }

    /**
     * docs/17-mini-player.md §3, app-exit orphan cleanup: double-Back at
     * the Home root (or any other path that finishes this Activity) must
     * not leave an active PiP window playing headless once nothing else
     * in the app remains -- stop it first, via [pipController]'s
     * callback into the live [tv.jellybeam.player.PlaybackActivity], before
     * this Activity itself actually finishes.
     */
    override fun finish() {
        if (pipController.isInPip.value) pipController.stopPlayback()
        super.finish()
    }

    /**
     * One-shot "time to first frame" mark (docs/10's startup worked example). Registered
     * unconditionally, cheap, since `onCreate` runs before [PerfLog.refresh] has a chance to
     * run; the [PerfLog.enabled] check inside the callback is what actually gates the log line.
     */
    private fun markFirstFrame(createStartElapsedMs: Long) {
        val decorView = window.decorView
        decorView.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                decorView.viewTreeObserver.removeOnPreDrawListener(this)
                val nowMs = SystemClock.elapsedRealtime()
                // Unconditional (not gated on PerfLog.enabled): AppGraph's device-caps probe and
                // JellybeamRoot's validateSession gate both key off this real moment, not a log line.
                AppGraph.notifyFirstFrame()
                firstFrameElapsedMs = nowMs
                if (PerfLog.enabled) {
                    val ms = (nowMs - createStartElapsedMs).toDouble()
                    Log.d(PerfLog.TAG, "perf startup phase=activity.firstFrame ms=${"%.2f".format(ms)}")
                }
                return true
            }
        })
    }

    /**
     * Frame-jank sampling via the platform [FrameMetrics] API (API 24+, hence the
     * [Build.VERSION] guard rather than a new dependency). Idempotent: a second call while
     * already running is a no-op, so [onResume] can call this unconditionally.
     */
    private fun startFrameMetrics() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        if (frameMetricsThread != null) return
        val thread = HandlerThread("JellybeamFrameMetrics").apply { start() }
        val handler = Handler(thread.looper)
        val listener = FrameMetricsReporter(handler)
        window.addOnFrameMetricsAvailableListener(listener, handler)
        frameMetricsThread = thread
        frameMetricsListener = listener
    }

    private fun stopFrameMetrics() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            (frameMetricsListener as? Window.OnFrameMetricsAvailableListener)?.let {
                window.removeOnFrameMetricsAvailableListener(it)
            }
        }
        frameMetricsListener = null
        // quitSafely (not quit): lets an already-posted flush finish rather than discarding it.
        frameMetricsThread?.quitSafely()
        frameMetricsThread = null
    }

    companion object {
        /** Process-wide so a second [MainActivity] instance doesn't re-log the profile-status line.
         * [Volatile] since [onResume] can run on a fresh instance.
         */
        @Volatile
        private var profileStatusLogged = false

        /** docs/21 §1.3: "once per process" for the post-crash Home dialog -- a fresh
         * [MainActivity] instance (e.g. after a Servers-section switch) must not re-show it.
         * `internal` so [JellybeamRoot], a sibling top-level declaration in this file, can read it.
         */
        @Volatile
        internal var crashDialogShownThisProcess = false
    }
}

private data class ExternalPlaybackRequest(val requestId: Long, val itemId: String, val kind: ExternalPlaybackRequestKind)

/**
 * [ExternalPlaybackRequest.kind]: [PLAY] is the "start PlaybackActivity" request (external PLAY
 * intent / `jellybeam://play` deep link); [OPEN_DETAIL] is Feature A's "still watching? -> Stop"
 * routing (docs/feature-dev/spec-still-watching-and-lan-discovery.md), never exported/deep-linked.
 */
private enum class ExternalPlaybackRequestKind { PLAY, OPEN_DETAIL }

/** [MainActivity.startFrameMetrics]'s periodic-summary tick -- never per-frame. */
private const val FRAME_SUMMARY_INTERVAL_MS = 10_000L

/**
 * [Window.OnFrameMetricsAvailableListener] that feeds every frame into a [FrameStatsHistogram]
 * and logs one rolled-up summary every [FRAME_SUMMARY_INTERVAL_MS]; must never run on the main
 * thread's hot path. [onFrameMetricsAvailable] is guaranteed to run on [handler]'s thread, and
 * the flush is scheduled there via [Handler.postDelayed] rather than a coroutine ticker.
 */
@RequiresApi(Build.VERSION_CODES.N)
private class FrameMetricsReporter(private val handler: Handler) : Window.OnFrameMetricsAvailableListener {
    private val histogram = FrameStatsHistogram()
    private var flushScheduled = false

    override fun onFrameMetricsAvailable(window: Window, frameMetrics: FrameMetrics, dropCountSinceLastInvocation: Int) {
        val totalNs = frameMetrics.getMetric(FrameMetrics.TOTAL_DURATION)
        histogram.add(totalNs / 1_000_000.0)
        scheduleFlushIfNeeded()
    }

    private fun scheduleFlushIfNeeded() {
        if (flushScheduled) return
        flushScheduled = true
        handler.postDelayed({
            flushScheduled = false
            val summary = histogram.summary()
            histogram.reset()
            if (summary.frames > 0) {
                Log.d(
                    PerfLog.TAG,
                    "perf frames count=${summary.frames} janky=${summary.janky} " +
                        "p50Ms=${"%.1f".format(summary.p50Ms)} p90Ms=${"%.1f".format(summary.p90Ms)}",
                )
            }
            // docs/10: this tick is also the periodic trigger a hot-path PerfAccumulator
            // (image.load, ffi.imageUrl) needs once its own record() calls stop mid-window.
            if (PerfLog.enabled) PerfLog.flushAllIfDue()
        }, FRAME_SUMMARY_INTERVAL_MS)
    }
}

private const val EPISODE_ITEM_TYPE = "Episode"

/** Foreground returns re-validate the saved token at most this often; see `checkSession`. */
private const val SESSION_CHECK_MIN_INTERVAL_MS = 60_000L

/** docs/10: the cold-start `validateSession` call waits this long past `activity.firstFrame`
 * before firing, so it no longer competes with core init/first frame/Home composition. */
private const val SESSION_CHECK_STARTUP_DELAY_MS = 1_000L

/**
 * [DetailScreen]/[LibraryScreen]'s `viewModel(key = ...)` calls are scoped to whatever
 * `LocalViewModelStoreOwner.current` resolves to -- by default the Activity's own store, which
 * nothing ever clears, so browsing N series would retain N ViewModels for the process lifetime.
 * [JellybeamRoot] provides one of these per distinct Library/Detail screen on [NavBackStack]
 * (the per-entry store androidx.navigation gives for free), and [pruneViewModelStores] clears +
 * drops one the moment its key leaves the stack.
 */
private class ScreenViewModelStoreOwner : ViewModelStoreOwner {
    override val viewModelStore: ViewModelStore = ViewModelStore()
}

/**
 * [Screen.Detail]'s fresh-entry perf mark (docs/10-perf-logging.md `detail.push`): every push or
 * sibling-replace that lands a NEW Detail screen. Never call these from a Back pop -- popping
 * reveals a Detail entry already on the stack, not a fresh one, and goes through
 * [NavBackStack.pop] directly, which never touches these helpers.
 */
private fun NavBackStack.pushDetail(card: Card): NavBackStack {
    PerfLog.markDetailPush()
    return push(Screen.Detail(card))
}

private fun NavBackStack.replaceDetail(card: Card): NavBackStack {
    PerfLog.markDetailPush()
    return replace(Screen.Detail(card))
}

/**
 * The identity a [ScreenViewModelStoreOwner] is tracked under -- `null` for screens that don't
 * need per-entry scoping. Home/SignIn/Search/Settings are singletons on this stack, so they
 * keep the ordinary Activity-level store; Library/Detail get one retained ViewModel per item.
 */
private fun Screen.viewModelStoreKey(): String? = when (this) {
    // Reuses Screen.entryIdentity's id-only string rather than re-deriving it.
    is Screen.Library, is Screen.Detail -> entryIdentity()
    // docs/14-seerr-discover.md: same per-entry reasoning as Library/Detail above.
    is Screen.DiscoverGrid, is Screen.DiscoverDetail, is Screen.DiscoverPerson -> entryIdentity()
    Screen.SignIn, Screen.Home, Screen.Search, Screen.Settings,
    Screen.Discover, Screen.DiscoverRequests, Screen.DiscoverSearch, Screen.Report -> null
}

/**
 * Clears and drops every tracked [ScreenViewModelStoreOwner] whose key is no longer present in
 * [keep]'s entries. Called from a [SideEffect] in [JellybeamRoot], after Compose has applied the
 * recomposition that made [keep] current -- never from the `navigate()` callback that sets it,
 * since that runs before the next recomposition and would clear a screen's own owner (running
 * its ViewModels' `onCleared()`) while it's still the one mounted. A [SideEffect] runs only
 * after the old screen's nodes are already disposed, the earliest point this is provably safe.
 */
private fun pruneViewModelStores(owners: MutableMap<String, ScreenViewModelStoreOwner>, keep: NavBackStack) {
    val keepKeys = keep.entries.mapNotNull { it.viewModelStoreKey() }.toSet()
    val iterator = owners.entries.iterator()
    while (iterator.hasNext()) {
        val entry = iterator.next()
        if (entry.key !in keepKeys) {
            entry.value.viewModelStore.clear()
            iterator.remove()
        }
    }
}

/**
 * The wrapper every retained per-entry layer on a Home-rooted [NavBackStack] is composed
 * inside. [index] drives `zIndex` (belt-and-suspenders; declaration order already paints later
 * entries over earlier ones); [isShown] decides draw-skip, [isTop] the focus gate.
 *
 * Every hidden-but-retained layer needs:
 * - Its draw skipped while hidden (`drawWithContent`; free GPU savings on the target 2GB box).
 * - To be unreachable by D-pad focus search while hidden -- otherwise a search could reach a
 *   card still sitting invisibly in a layer underneath.
 *
 * [focusGate] is a GATE, not a plain `isTop` fence: with `enter` keyed on `isTop` alone, a pop's
 * framework default-focus grab could land on the newly-top layer before that screen's own
 * becoming-top restore effect placed focus correctly (a scroll-position yo-yo). A permanent
 * `Cancel` doesn't work either -- it denies even a direct [FocusRequester.requestFocus] at a
 * descendant, so an initial focus seed could never land. The gate is closed (via [SideEffect])
 * whenever an entry isn't top, and opened by the screen itself immediately before each of its
 * own explicit focus placements (see [tv.jellybeam.ui.home.HomeScreen]'s `focusGate` param doc).
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun RetainedScreenLayer(
    index: Int,
    isTop: Boolean,
    isShown: Boolean,
    focusGate: MutableState<Boolean>,
    content: @Composable () -> Unit,
) {
    if (!isTop) {
        SideEffect { focusGate.value = false }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(index.toFloat())
            .drawWithContent { if (isShown) drawContent() }
            .focusProperties {
                enter = { if (focusGate.value) FocusRequester.Default else FocusRequester.Cancel }
            }
            .focusGroup(),
    ) {
        content()
    }
}

/**
 * Decides where to land on launch: `restoreSession` succeeds -> reopen the mirror and go
 * straight to Home; otherwise Sign In. `null` while that decision is in flight.
 *
 * Navigation (GOAL item 1, docs/07 §5): one [NavBackStack] in plain Compose state, no
 * navigation library. Enter opens Detail, never plays directly; Back pops one entry via
 * [BackHandler]; a sibling-episode switch replaces the top entry so Back skips visited siblings.
 *
 * While [NavBackStack.isHomeRooted], every entry in [NavBackStack.entries] is composed
 * bottom-to-top as its own retained layer, keyed on [NavBackStack.entryKeys]'s index+identity
 * scheme: a push/pop leaves every surviving key unchanged (Compose keeps the composition),
 * while a `replace` changes the key at that slot (Compose disposes the old subtree/ViewModel
 * and mounts fresh) -- so a pop just reveals whatever's already live underneath, zero rebuild,
 * zero flash. When the stack is SignIn-rooted, none of this applies.
 *
 * Each retained-but-hidden layer ([RetainedScreenLayer], plus each screen's own `isTop`-driven
 * restore logic) must be unreachable by D-pad focus search while hidden, and skip its draw.
 *
 * Memory: a deep stack is rare in practice; this assumes it stays cheap enough for the target
 * 2GB box without eviction/depth-cap logic -- revisit with a real memory profile if that changes.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun JellybeamRoot(
    onExitApp: () -> Unit,
    externalPlaybackRequest: ExternalPlaybackRequest?,
    onExternalPlaybackConsumed: (ExternalPlaybackRequest) -> Unit,
    activityResumed: Boolean,
    /** docs/21 §1.3: this Activity's own launch intent, not the live (soon-consumed)
     * [externalPlaybackRequest] state -- see [MainActivity.isExternalPlaybackIntent]. */
    launchedForExternalPlayback: Boolean = false,
    /** [MainActivity.firstFrameElapsedMs] -- `null` until first frame, then gates the cold-start
     * `validateSession` call below (docs/10). */
    firstFrameAtMs: Long? = null,
) {
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val gateway = AppGraph.gateway
    var backStack by remember { mutableStateOf<NavBackStack?>(null) }
    var libraryViews by remember { mutableStateOf<List<ViewSnapshot>>(emptyList()) }

    // ui/launch/LaunchScreen.kt state (docs/brand.md §6.3): the saved server's host, known
    // as soon as restoreSession resolves (local-only, no network round trip), and whether this
    // attempt's openMirror call threw -- both read only while `stack == null` below.
    var launchHost by remember { mutableStateOf<String?>(null) }
    var launchUnreachable by remember { mutableStateOf(false) }

    // Servers section (multi-session support): every locally known account plus which is
    // active, hoisted here since NavDrawerHost is instantiated from two branches (Home and
    // Library) that share this one fetch. `null` for "not known yet" (see
    // [tv.jellybeam.ui.nav.NavDrawerHost]'s `activeAccountIndex` param doc).
    var accounts by remember { mutableStateOf<List<AccountInfo>>(emptyList()) }
    var activeAccountIndex by remember { mutableStateOf<UInt?>(null) }

    // docs/14-seerr-discover.md: whether the drawer's "Discover" entry shows at all -- a
    // local-file-only read, re-checked whenever [seerrEpoch] bumps or the session changes.
    var discoverConfigured by remember { mutableStateOf(false) }
    var seerrEpoch by remember { mutableStateOf(0) }

    // Full in-app session reset (switching/adding a server): bumping this re-keys the startup
    // LaunchedEffect below to rerun restoreSession -> openMirror -> views. Plain Int, not a
    // Boolean, so a second switch requested before the first finishes is still a new key.
    var sessionEpoch by remember { mutableStateOf(0) }

    // The ambient (Activity-level) ViewModelStore, resolved before any per-entry
    // CompositionLocalProvider could shadow it -- where Home/Search/Settings/SignIn
    // ViewModels actually live, since viewModelStoreKey() resolves null for those four.
    // resetSessionState clears it alongside screenStoreOwners for that reason.
    val activityViewModelStoreOwner = LocalViewModelStoreOwner.current
    val composableScope = rememberCoroutineScope()
    val exitConfirmationGate = remember { ExitConfirmationGate() }
    val exitPrompt = androidx.compose.ui.res.stringResource(R.string.press_back_again_to_exit)

    // Per-entry ViewModelStore bookkeeping: a plain mutable map, not Compose state, since
    // mutating it is a side effect of navigating, not something that should recompose.
    val screenStoreOwners = remember { mutableMapOf<String, ScreenViewModelStoreOwner>() }

    // JellybeamRoot leaving composition (Activity finishing) is the one transition navigate()'s
    // per-navigation pruning never sees; without this every still-on-stack ViewModel would
    // leak (viewModelScope never cancelled, changeEvents() collector still reachable).
    DisposableEffect(Unit) {
        onDispose {
            screenStoreOwners.values.forEach { it.viewModelStore.clear() }
            screenStoreOwners.clear()
        }
    }

    /** Every `backStack = ...` reassignment past the first goes through here; pruning happens
     * later, from a [SideEffect], once this is composed (see [pruneViewModelStores]).
     */
    fun navigate(newStack: NavBackStack) {
        // Focus is global to the Compose owner, not scoped to a retained layer. Evict the old
        // top synchronously so no key event routes to an invisible descendant before the new
        // top's restore runs.
        focusManager.clearFocus(force = true)
        exitConfirmationGate.reset()
        backStack = newStack
        // docs/21 §2.1: one line per navigation, screen name only.
        AppGraph.diag.event("nav.screen") { tag("screen", newStack.current.diagName()) }
    }

    /**
     * The shared half of the Servers-section full-reset flow -- [switchServer] and the
     * "Add server" screen's `onSignedIn` both call this, then bump [sessionEpoch] themselves.
     * Blanking [backStack] first shows [tv.jellybeam.ui.launch.LaunchScreen] immediately, which is the point
     * here (a warm restart): there's deliberately nothing left on screen to race a cleared
     * ViewModel's `onCleared()`.
     */
    fun resetSessionState() {
        focusManager.clearFocus(force = true)
        exitConfirmationGate.reset()
        backStack = null
        // Cleared rather than left stale: the sessionEpoch bump below re-runs the LaunchedEffect
        // that recomputes both, but not before this recomposition briefly shows the launch screen.
        launchHost = null
        launchUnreachable = false
        AppGraph.launchWarmup?.discard()
        screenStoreOwners.values.forEach { it.viewModelStore.clear() }
        screenStoreOwners.clear()
        activityViewModelStoreOwner?.viewModelStore?.clear()
    }

    /**
     * Servers-section "switch to this account": the full reset above, then
     * [CoreGateway.switchSession], then [sessionEpoch] bumps regardless of outcome -- a failure
     * is not special-cased, since `switch_session` only throws before touching the persisted
     * active index, so a failed switch restores the same account that was active before,
     * landing back on Home rather than stranded on [tv.jellybeam.ui.launch.LaunchScreen].
     */
    fun switchServer(index: UInt) {
        resetSessionState()
        composableScope.launch {
            runCatching { gateway.switchSession(index) }
            sessionEpoch++
        }
    }

    // Servers-section "Add server": true while the credentials form stands in for the whole
    // Home-rooted tree, not modeled as a NavBackStack push of Screen.SignIn since that screen
    // doesn't participate in the retained-layer isTop/focusGate scheme. A plain flag sidesteps
    // that: [backStack] stays untouched underneath, so Back just flips this to `false`.
    var addServerActive by remember { mutableStateOf(false) }
    var manageServersActive by remember { mutableStateOf(false) }
    var reauthorizationTarget by remember { mutableStateOf<ReauthorizationTarget?>(null) }
    var removingServerIndex by remember { mutableStateOf<UInt?>(null) }
    var serverManagementError by remember { mutableStateOf<String?>(null) }

    // A 401 from any core call resolves the active saved identity here and replaces the
    // whole retained browse tree with the same reauthorization surface the server manager uses.
    // The coordinator retains the request until this block consumes it, so the Activity
    // transition cannot drop the event.
    LaunchedEffect(gateway) {
        authorizationRecoveryCoordinator.requests.filterNotNull().collect { requestId ->
            val refreshedAccounts = runCatching { gateway.listAccounts() }.getOrElse { accounts }
            val refreshedActiveIndex = runCatching { gateway.activeAccountIndex() }
                .getOrElse { activeAccountIndex }
            val activeAccount = refreshedActiveIndex
                ?.toInt()
                ?.let(refreshedAccounts::getOrNull)

            // Every failing call re-requests; one already on screen must keep its focus and text.
            val alreadyShowing = reauthorizationTarget?.let {
                it.serverUrl == activeAccount?.serverUrl && it.userId == activeAccount?.userId
            } == true
            if (alreadyShowing) {
                authorizationRecoveryCoordinator.consume(requestId)
                return@collect
            }

            if (activeAccount != null) {
                focusManager.clearFocus(force = true)
                accounts = refreshedAccounts
                activeAccountIndex = refreshedActiveIndex
                addServerActive = false
                manageServersActive = false
                serverManagementError = null
                reauthorizationTarget = ReauthorizationTarget(
                    index = refreshedActiveIndex,
                    serverUrl = activeAccount.serverUrl,
                    userId = activeAccount.userId,
                    userName = activeAccount.userName,
                )
            } else {
                Toast.makeText(
                    context,
                    R.string.authorization_expired_manage_servers,
                    Toast.LENGTH_LONG,
                ).show()
            }
            authorizationRecoveryCoordinator.consume(requestId)
        }
    }

    fun openServerManagement() {
        serverManagementError = null
        manageServersActive = true
    }

    fun reauthorizeServer(index: UInt) {
        val account = accounts.getOrNull(index.toInt()) ?: return
        focusManager.clearFocus(force = true)
        manageServersActive = false
        serverManagementError = null
        reauthorizationTarget = ReauthorizationTarget(
            index = index,
            serverUrl = account.serverUrl,
            userId = account.userId,
            userName = account.userName,
        )
    }

    fun removeServer(index: UInt) {
        if (removingServerIndex != null) return
        removingServerIndex = index
        serverManagementError = null
        composableScope.launch {
            try {
                val removedActive = gateway.removeSession(index)
                if (removedActive) {
                    manageServersActive = false
                    removingServerIndex = null
                    resetSessionState()
                    sessionEpoch++
                } else {
                    accounts = gateway.listAccounts()
                    activeAccountIndex = gateway.activeAccountIndex()
                    removingServerIndex = null
                }
            } catch (error: CoreException) {
                serverManagementError = error.displayMessage()
                removingServerIndex = null
            }
        }
    }

    // Home renders from the mirror, so a revoked token would otherwise wait for the first Play.
    // Launch, account switch and re-authorization check at once ([sessionEpoch]); a return to the
    // foreground checks at most once a minute. A 401 reaches re-authorization through the
    // gateway's seam; every other outcome fails open.
    var sessionCheckedAtMs by remember { mutableStateOf(Long.MIN_VALUE) }
    fun checkSession(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - sessionCheckedAtMs < SESSION_CHECK_MIN_INTERVAL_MS) return
        sessionCheckedAtMs = now
        composableScope.launch { runCatching { gateway.validateSession() } }
    }

    // docs/10: the forced cold-start check below used to fire the instant restoreSession
    // succeeded, racing core init/first frame/Home composition for a network call whose only
    // effect is 401 -> reauthorization routing (authorizationRecoveryCoordinator) -- Home already
    // renders from the mirror and fails open until it lands, so nothing else waits on its timing.
    // A later sessionEpoch (server switch/add) runs well after first frame, so the window is
    // already open and this fires immediately for those, same as before.
    var sessionCheckWindowOpen by remember { mutableStateOf(false) }
    LaunchedEffect(firstFrameAtMs) {
        val atMs = firstFrameAtMs ?: return@LaunchedEffect
        val remainingMs = atMs + SESSION_CHECK_STARTUP_DELAY_MS - SystemClock.elapsedRealtime()
        if (remainingMs > 0) delay(remainingMs)
        sessionCheckWindowOpen = true
    }
    var pendingInitialSessionCheck by remember { mutableStateOf(false) }
    LaunchedEffect(pendingInitialSessionCheck, sessionCheckWindowOpen) {
        if (pendingInitialSessionCheck && sessionCheckWindowOpen) {
            pendingInitialSessionCheck = false
            checkSession(force = true)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START && accounts.isNotEmpty()) checkSession(force = false)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(sessionEpoch) {
        // Cold start: restore and openMirror have been running since process start (LaunchWarmup).
        // Every later epoch, and an Activity recreated in a live process, runs them here.
        launchUnreachable = false
        val warm = AppGraph.launchWarmup?.takeSession()
        val restored = if (warm != null) warm.account else gateway.restoreSession()
        // Local-only (no network round trip): known the instant restoreSession resolves, so the
        // launch screen can name the host well before openMirror's sync reaches the server.
        launchHost = serverHostLabel(restored?.serverUrl)
        if (restored != null) pendingInitialSessionCheck = true
        backStack = if (restored != null) {
            coroutineScope {
                // getSettings reads only in-memory JellybeamCore state -- no
                // dependency on the mirror being open -- so it runs
                // concurrently with openMirror instead of queuing a third
                // sequential IO-dispatch hop behind it. views() DOES need
                // the mirror, so it stays after the openMirror await.
                val settingsDeferred = async { runCatching { gateway.getSettings() } }

                // Fail open (docs/05): a stale/revoked token only surfaces once
                // openMirror's own sync actually hits the server -- if it
                // fails here we still land on Home rather than bouncing the
                // user back to Sign In on every launch; Home's own empty-state
                // rendering handles "mirror never opened" already (CoreGateway
                // calls return their safe defaults). launchUnreachable only
                // reflects this attempt's own call -- LaunchWarmup's prior
                // background attempt (the `warm != null` cold-start case)
                // isn't observable here, same fail-open shape either way.
                if (warm == null) {
                    launchUnreachable = runCatching { gateway.openMirror() }
                        .onFailure { if (it is CancellationException) throw it }
                        .isFailure
                }

                // Startup screen (docs/07 §5 / docs/09 GOAL item 4): pushed
                // onto Home rather than replacing it, so Back always returns
                // to Home regardless of whether a startup library was
                // resolved. Any failure fetching settings/views (e.g. the
                // mirror never opened) falls back to Home-only, same fail-open
                // shape as the openMirror call above.
                val startupView = runCatching {
                    val settings = settingsDeferred.await().getOrThrow()
                    val views = gateway.views()
                    resolveStartupView(settings.startupScreenViewId, views)
                }.getOrNull()

                val homeStack = NavBackStack.of(Screen.Home)
                if (startupView != null) homeStack.push(Screen.Library(startupView)) else homeStack
            }
        } else {
            NavBackStack.of(Screen.SignIn)
        }
        // docs/10-perf-logging.md: the back stack's first resolution -- cold start, a later
        // session epoch (server switch/add), or an Activity recreated in a live process.
        PerfLog.markStartup("root.stackReady")
    }

    val stack = backStack

    // A cold external launch waits here until restoreSession/openMirror completes, so
    // preparePlayback can resolve the item. A signed-out launch is consumed with explicit
    // feedback instead of opening a player guaranteed to fail.
    LaunchedEffect(externalPlaybackRequest, stack) {
        val request = externalPlaybackRequest ?: return@LaunchedEffect
        val readyStack = stack ?: return@LaunchedEffect
        when (request.kind) {
            ExternalPlaybackRequestKind.PLAY -> {
                if (readyStack.isHomeRooted) {
                    // docs/17 §3: a PiP'd player never reaches idle, so startActivity below
                    // must instead be delivered straight to it via onNewIntent.
                    if (!pipController.isInPip.value) playbackActivityTracker.awaitIdle()
                    context.startActivity(PlaybackActivity.intent(context, request.itemId))
                    // Keep the request as this effect's key while awaitIdle is suspended;
                    // consuming it earlier would cancel the coroutine before it can launch.
                    onExternalPlaybackConsumed(request)
                } else {
                    onExternalPlaybackConsumed(request)
                    Toast.makeText(context, R.string.external_playback_sign_in_required, Toast.LENGTH_LONG).show()
                }
            }
            ExternalPlaybackRequestKind.OPEN_DETAIL -> {
                // Feature A "still watching? -> Stop" routing (docs/feature-dev/
                // spec-still-watching-and-lan-discovery.md). Fail-open (silently do nothing)
                // rather than a toast, since there's no sign-in gesture to prompt here.
                onExternalPlaybackConsumed(request)
                if (readyStack.isHomeRooted) {
                    val card = resolveLibraryCard(gateway, request.itemId)
                    if (card != null) {
                        val current = readyStack.current
                        val newStack = if (current is Screen.Detail && current.card.itemType == EPISODE_ITEM_TYPE) {
                            readyStack.replaceDetail(card)
                        } else {
                            readyStack.pushDetail(card)
                        }
                        navigate(newStack)
                    }
                }
            }
        }
    }

    BackHandler(enabled = stack?.canGoBack == true) {
        stack?.let { navigate(it.pop()) }
    }

    // docs/21 §1.3: once per process, only for a launch that lands on Home rather than heading
    // straight into playback/detail -- launchedForExternalPlayback is this Activity's own launch
    // intent, checked once so a later external intent (onNewIntent) can't re-arm this.
    var crashPromptVisible by remember { mutableStateOf(false) }
    LaunchedEffect(stack) {
        val readyStack = stack ?: return@LaunchedEffect
        if (!launchedForExternalPlayback && readyStack.isHomeRooted &&
            !MainActivity.crashDialogShownThisProcess && AppGraph.crash.shouldPrompt()
        ) {
            MainActivity.crashDialogShownThisProcess = true
            crashPromptVisible = true
        }
    }

    // At the stack root Back would otherwise finish the Activity immediately; require a
    // deliberate second press. navigate() resets the gate, so returning to root never
    // inherits an old armed press.
    BackHandler(
        enabled = stack != null && !stack.canGoBack && !addServerActive &&
            !manageServersActive && reauthorizationTarget == null,
    ) {
        if (exitConfirmationGate.press(SystemClock.elapsedRealtime())) {
            onExitApp()
        } else {
            Toast.makeText(context, exitPrompt, Toast.LENGTH_SHORT).show()
        }
    }

    // Composed later -> registered later on the OnBackPressedDispatcher -> called first (LIFO):
    // Back cancels out of the form instead of popping whatever's underneath on [stack].
    BackHandler(enabled = addServerActive) { addServerActive = false }
    BackHandler(enabled = reauthorizationTarget != null) { reauthorizationTarget = null }
    BackHandler(enabled = manageServersActive && removingServerIndex == null) {
        manageServersActive = false
        serverManagementError = null
    }

    // Side-drawer's library list and the Servers-section's account list share one fetch/refresh
    // loop, its own rather than reusing HomeViewModel's state (which would construct
    // HomeViewModel and fire its expensive homeSnapshot() call prematurely). Gated on
    // `stack != null` so the first fetch can't race openMirror() above; keyed on [sessionEpoch]
    // too, so a server switch doesn't leave this showing the old server's data.
    if (stack != null) {
        LaunchedEffect(gateway, sessionEpoch) {
            launch { runCatching { libraryViews = gateway.views() } }
            launch { runCatching { accounts = gateway.listAccounts() } }
            launch { activeAccountIndex = runCatching { gateway.activeAccountIndex() }.getOrNull() }
            // conflate() + collect gives leading-edge refresh, collapsed bursts, and a
            // guaranteed trailing refresh -- a plain debounce would never fire during a sync
            // burst (events keep arriving under 500ms apart). ChangeRefreshScheduler
            // (docs/16 §4.6, docs/17 §6) offers the same shape but needs machinery this tiny
            // drawer list doesn't warrant.
            launch {
                gateway.changeEvents().conflate().collect {
                    runCatching { libraryViews = gateway.views() }
                    delay(500L)
                }
            }
        }

        // docs/14-seerr-discover.md drawer section: its own effect so a Discover connect/
        // disconnect never re-subscribes the collector above. seerrStatus is zero-network.
        LaunchedEffect(gateway, sessionEpoch, seerrEpoch) {
            discoverConfigured = runCatching { gateway.seerrStatus().configured }.getOrDefault(false)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(JellybeamTheme.Notte),
    ) {
        // `stack`, a stable local `val`, gives every branch below a smart-cast, non-null
        // instance -- `null` only during the first frame and during a Servers-section
        // switch/add (resetSessionState sets it back to `null` on purpose).
        when {
            reauthorizationTarget != null -> SignInScreen(
                reauthorizationTarget = reauthorizationTarget,
                onSignedIn = {
                    authorizationRecoveryCoordinator.noteReauthorized()
                    reauthorizationTarget = null
                    resetSessionState()
                    sessionEpoch++
                },
            )

            manageServersActive -> ServerManagementScreen(
                accounts = accounts,
                activeAccountIndex = activeAccountIndex,
                removingIndex = removingServerIndex,
                error = serverManagementError,
                onReauthorize = ::reauthorizeServer,
                onRemove = ::removeServer,
                onClose = {
                    if (removingServerIndex == null) {
                        manageServersActive = false
                        serverManagementError = null
                    }
                },
            )

            // Servers-section "Add server": replaces the entire tree, `stack` included, so
            // SignInScreen needs none of the isTop/focusGate machinery. `onSignedIn` reuses
            // resetSessionState (sign_in already made this account active and called
            // openMirror()) then bumps [sessionEpoch] directly -- no coroutine needed.
            addServerActive -> SignInScreen(
                onSignedIn = {
                    addServerActive = false
                    resetSessionState()
                    sessionEpoch++
                },
            )

            stack == null -> {
                // Launch screen (docs/brand.md §6.3): without this the first frame(s) show
                // nothing but a bare Notte background, indistinguishable from a hang. Also shown
                // for the whole duration of a Servers-section switch (resetSessionState's
                // `backStack = null`) -- a warm re-run of the same cold-start sequence, reused
                // deliberately.
                LaunchScreen(
                    host = launchHost,
                    unreachable = launchUnreachable,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            else -> {
            // Deliberately after this recomposition has committed to `stack`, not from
            // navigate() itself (see pruneViewModelStores).
            SideEffect { pruneViewModelStores(screenStoreOwners, stack) }

            val current = stack.current

            // Drawer-driven navigation: "Home" always resets to the root; anything else pushes
            // one level deeper from Home, but replaces the top entry when already on a
            // Library/Search, so hopping between libraries doesn't pile up a back-stack.
            fun navigateFromDrawer(screen: Screen) {
                val newStack = when {
                    screen == Screen.Home -> NavBackStack.of(Screen.Home)
                    current == Screen.Home -> stack.push(screen)
                    else -> stack.replace(screen)
                }
                navigate(newStack)
            }

            // docs/14-seerr-discover.md, "Go to library" action: [itemId] is a real Jellyfin id
            // already in the local mirror. Fails open rather than navigating to a broken Detail
            // page: no matching item is indistinguishable here from a transient network failure.
            fun openDiscoverLibraryItem(itemId: String) {
                composableScope.launch {
                    val card = resolveLibraryCard(gateway, itemId)
                    if (card != null) navigate(stack.pushDetail(card))
                }
            }

            if (stack.isHomeRooted) {
                val entries = stack.entries
                val topIndex = entries.lastIndex
                val entryKeys = stack.entryKeys()

                // Every entry on the stack, bottom-to-top, as a retained layer (see JellybeamRoot's
                // doc). `key(...)` on entryKeys tells Compose push/pop (reuse) from replace
                // (dispose+remount) for each slot.
                entries.forEachIndexed { index, entry ->
                    // A retained screen must also become non-top while a different Activity
                    // owns the window; restoring on resume re-runs its becoming-top focus
                    // placement.
                    val isShown = retainedLayerIsActive(index, topIndex, activityResumed)
                    // docs/21 §1.3: while the crash prompt is up it owns focus -- the top layer
                    // stays drawn under the scrim but is non-top for focus and lifecycle, as when
                    // another Activity owns the window, so dismissal re-runs its own placement.
                    val isTop = isShown && !crashPromptVisible
                    key(entryKeys[index]) {
                        // One gate per entry, living exactly as long as this entry's composition.
                        val focusGate = remember { mutableStateOf(false) }

                        RetainedScreenLayer(index = index, isTop = isTop, isShown = isShown, focusGate = focusGate) {
                            when (entry) {
                                Screen.Home -> NavDrawerHost(
                                    currentScreen = Screen.Home,
                                    libraries = libraryViews,
                                    onNavigate = ::navigateFromDrawer,
                                    isTop = isTop,
                                    focusGate = focusGate,
                                    accounts = accounts,
                                    activeAccountIndex = activeAccountIndex,
                                    onSwitchServer = ::switchServer,
                                    onAddServer = { addServerActive = true },
                                    onManageServers = ::openServerManagement,
                                    discoverConfigured = discoverConfigured,
                                    onOpenDiscover = { navigateFromDrawer(Screen.Discover) },
                                ) {
                                    HomeScreen(
                                        onOpenDetail = { card -> navigate(stack.pushDetail(card)) },
                                        isTop = isTop,
                                        focusGate = focusGate,
                                    )
                                }

                                is Screen.Library -> CompositionLocalProvider(
                                    LocalViewModelStoreOwner provides screenStoreOwners.getOrPut(entry.viewModelStoreKey()!!) { ScreenViewModelStoreOwner() },
                                ) {
                                    NavDrawerHost(
                                        currentScreen = entry,
                                        libraries = libraryViews,
                                        onNavigate = ::navigateFromDrawer,
                                        isTop = isTop,
                                        focusGate = focusGate,
                                        accounts = accounts,
                                        activeAccountIndex = activeAccountIndex,
                                        onSwitchServer = ::switchServer,
                                        onAddServer = { addServerActive = true },
                                        onManageServers = ::openServerManagement,
                                        discoverConfigured = discoverConfigured,
                                        onOpenDiscover = { navigateFromDrawer(Screen.Discover) },
                                    ) {
                                        LibraryScreen(
                                            view = entry.view,
                                            onOpenDetail = { card ->
                                                // screenForLibraryCard resolves a channel-folder item
                                                // to another Library level instead of Detail -- only
                                                // mark the genuine Detail case.
                                                val screen = screenForLibraryCard(card)
                                                if (screen is Screen.Detail) PerfLog.markDetailPush()
                                                navigate(stack.push(screen))
                                            },
                                            isTop = isTop,
                                            focusGate = focusGate,
                                        )
                                    }
                                }

                                Screen.Search -> SearchScreen(
                                    onOpenDetail = { card -> navigate(stack.pushDetail(card)) },
                                    onOpenDiscoverDetail = { mediaType, tmdbId ->
                                        navigate(stack.push(Screen.DiscoverDetail(mediaType, tmdbId)))
                                    },
                                    discoverConfigured = discoverConfigured,
                                    isTop = isTop,
                                    focusGate = focusGate,
                                )

                                // Settings/Detail get the drawer too, same shape as Home/Library
                                // above. Search stays drawer-less: its query field owns D-pad Left.
                                Screen.Settings -> NavDrawerHost(
                                    currentScreen = Screen.Settings,
                                    libraries = libraryViews,
                                    onNavigate = ::navigateFromDrawer,
                                    isTop = isTop,
                                    focusGate = focusGate,
                                    accounts = accounts,
                                    activeAccountIndex = activeAccountIndex,
                                    onSwitchServer = ::switchServer,
                                    onAddServer = { addServerActive = true },
                                    onManageServers = ::openServerManagement,
                                    discoverConfigured = discoverConfigured,
                                    onOpenDiscover = { navigateFromDrawer(Screen.Discover) },
                                ) {
                                    SettingsScreen(
                                        isTop = isTop,
                                        focusGate = focusGate,
                                        onSeerrConfigChanged = { seerrEpoch++ },
                                        onReportProblem = { navigate(stack.push(Screen.Report)) },
                                    )
                                }

                                is Screen.Detail -> CompositionLocalProvider(
                                    LocalViewModelStoreOwner provides screenStoreOwners.getOrPut(entry.viewModelStoreKey()!!) { ScreenViewModelStoreOwner() },
                                ) {
                                    NavDrawerHost(
                                        currentScreen = entry,
                                        libraries = libraryViews,
                                        onNavigate = ::navigateFromDrawer,
                                        isTop = isTop,
                                        focusGate = focusGate,
                                        accounts = accounts,
                                        activeAccountIndex = activeAccountIndex,
                                        onSwitchServer = ::switchServer,
                                        onAddServer = { addServerActive = true },
                                        onManageServers = ::openServerManagement,
                                        discoverConfigured = discoverConfigured,
                                        onOpenDiscover = { navigateFromDrawer(Screen.Discover) },
                                    ) {
                                        DetailScreen(
                                            card = entry.card,
                                            isTop = isTop,
                                            focusGate = focusGate,
                                            onOpenDetail = { card ->
                                                // docs/07 §5: a sibling-episode switch replaces the
                                                // top entry so Back skips visited
                                                // siblings; opening a first episode or a
                                                // non-episode card still pushes.
                                                val stayingOnSiblingEpisode = entry.card.itemType == EPISODE_ITEM_TYPE &&
                                                    card.itemType == EPISODE_ITEM_TYPE
                                                val newStack = if (stayingOnSiblingEpisode) {
                                                    stack.replaceDetail(card)
                                                } else {
                                                    stack.pushDetail(card)
                                                }
                                                navigate(newStack)
                                            },
                                        )
                                    }
                                }

                                // docs/14-seerr-discover.md: Discover home, reached only via the
                                // drawer entry gated on [discoverConfigured].
                                Screen.Discover -> NavDrawerHost(
                                    currentScreen = Screen.Discover,
                                    libraries = libraryViews,
                                    onNavigate = ::navigateFromDrawer,
                                    isTop = isTop,
                                    focusGate = focusGate,
                                    accounts = accounts,
                                    activeAccountIndex = activeAccountIndex,
                                    onSwitchServer = ::switchServer,
                                    onAddServer = { addServerActive = true },
                                    onManageServers = ::openServerManagement,
                                    discoverConfigured = discoverConfigured,
                                    onOpenDiscover = { navigateFromDrawer(Screen.Discover) },
                                ) {
                                    DiscoverScreen(
                                        isTop = isTop,
                                        focusGate = focusGate,
                                        onOpenGrid = { kind, title, genreId, genreName ->
                                            navigate(stack.push(Screen.DiscoverGrid(kind, title, genreId, genreName)))
                                        },
                                        onOpenDetail = { mediaType, tmdbId ->
                                            navigate(stack.push(Screen.DiscoverDetail(mediaType, tmdbId)))
                                        },
                                        onOpenSearch = { navigate(stack.push(Screen.DiscoverSearch)) },
                                        onOpenMyRequests = { navigate(stack.push(Screen.DiscoverRequests)) },
                                    )
                                }

                                is Screen.DiscoverGrid -> CompositionLocalProvider(
                                    LocalViewModelStoreOwner provides screenStoreOwners.getOrPut(entry.viewModelStoreKey()!!) { ScreenViewModelStoreOwner() },
                                ) {
                                    NavDrawerHost(
                                        currentScreen = entry,
                                        libraries = libraryViews,
                                        onNavigate = ::navigateFromDrawer,
                                        isTop = isTop,
                                        focusGate = focusGate,
                                        accounts = accounts,
                                        activeAccountIndex = activeAccountIndex,
                                        onSwitchServer = ::switchServer,
                                        onAddServer = { addServerActive = true },
                                        onManageServers = ::openServerManagement,
                                        discoverConfigured = discoverConfigured,
                                        onOpenDiscover = { navigateFromDrawer(Screen.Discover) },
                                    ) {
                                        DiscoverGridScreen(
                                            kind = entry.kind,
                                            title = entry.title,
                                            genreId = entry.genreId,
                                            genreName = entry.genreName,
                                            isTop = isTop,
                                            focusGate = focusGate,
                                            onOpenDetail = { mediaType, tmdbId ->
                                                navigate(stack.push(Screen.DiscoverDetail(mediaType, tmdbId)))
                                            },
                                        )
                                    }
                                }

                                is Screen.DiscoverDetail -> CompositionLocalProvider(
                                    LocalViewModelStoreOwner provides screenStoreOwners.getOrPut(entry.viewModelStoreKey()!!) { ScreenViewModelStoreOwner() },
                                ) {
                                    NavDrawerHost(
                                        currentScreen = entry,
                                        libraries = libraryViews,
                                        onNavigate = ::navigateFromDrawer,
                                        isTop = isTop,
                                        focusGate = focusGate,
                                        accounts = accounts,
                                        activeAccountIndex = activeAccountIndex,
                                        onSwitchServer = ::switchServer,
                                        onAddServer = { addServerActive = true },
                                        onManageServers = ::openServerManagement,
                                        discoverConfigured = discoverConfigured,
                                        onOpenDiscover = { navigateFromDrawer(Screen.Discover) },
                                    ) {
                                        DiscoverDetailScreen(
                                            mediaType = entry.mediaType,
                                            tmdbId = entry.tmdbId,
                                            isTop = isTop,
                                            focusGate = focusGate,
                                            onOpenDetail = { mediaType, tmdbId ->
                                                navigate(stack.push(Screen.DiscoverDetail(mediaType, tmdbId)))
                                            },
                                            onOpenPerson = { personId -> navigate(stack.push(Screen.DiscoverPerson(personId))) },
                                            onGoToLibrary = ::openDiscoverLibraryItem,
                                        )
                                    }
                                }

                                is Screen.DiscoverPerson -> CompositionLocalProvider(
                                    LocalViewModelStoreOwner provides screenStoreOwners.getOrPut(entry.viewModelStoreKey()!!) { ScreenViewModelStoreOwner() },
                                ) {
                                    NavDrawerHost(
                                        currentScreen = entry,
                                        libraries = libraryViews,
                                        onNavigate = ::navigateFromDrawer,
                                        isTop = isTop,
                                        focusGate = focusGate,
                                        accounts = accounts,
                                        activeAccountIndex = activeAccountIndex,
                                        onSwitchServer = ::switchServer,
                                        onAddServer = { addServerActive = true },
                                        onManageServers = ::openServerManagement,
                                        discoverConfigured = discoverConfigured,
                                        onOpenDiscover = { navigateFromDrawer(Screen.Discover) },
                                    ) {
                                        DiscoverPersonScreen(
                                            personId = entry.personId,
                                            isTop = isTop,
                                            focusGate = focusGate,
                                            onOpenDetail = { mediaType, tmdbId ->
                                                navigate(stack.push(Screen.DiscoverDetail(mediaType, tmdbId)))
                                            },
                                        )
                                    }
                                }

                                Screen.DiscoverRequests -> NavDrawerHost(
                                    currentScreen = Screen.DiscoverRequests,
                                    libraries = libraryViews,
                                    onNavigate = ::navigateFromDrawer,
                                    isTop = isTop,
                                    focusGate = focusGate,
                                    accounts = accounts,
                                    activeAccountIndex = activeAccountIndex,
                                    onSwitchServer = ::switchServer,
                                    onAddServer = { addServerActive = true },
                                    onManageServers = ::openServerManagement,
                                    discoverConfigured = discoverConfigured,
                                    onOpenDiscover = { navigateFromDrawer(Screen.Discover) },
                                ) {
                                    DiscoverRequestsScreen(
                                        isTop = isTop,
                                        focusGate = focusGate,
                                        onOpenDetail = { mediaType, tmdbId ->
                                            navigate(stack.push(Screen.DiscoverDetail(mediaType, tmdbId)))
                                        },
                                    )
                                }

                                // Drawer-less, like Screen.Search: its query field owns D-pad Left.
                                Screen.DiscoverSearch -> DiscoverSearchScreen(
                                    isTop = isTop,
                                    focusGate = focusGate,
                                    onOpenDetail = { mediaType, tmdbId ->
                                        navigate(stack.push(Screen.DiscoverDetail(mediaType, tmdbId)))
                                    },
                                )

                                // docs/21 §1.2: drawer-less like Search/DiscoverSearch (Back
                                // pops, no library rail); pushed from Settings › Troubleshooting
                                // or the post-crash dialog.
                                Screen.Report -> ReportScreen(onExit = { navigate(stack.pop()) }, isTop = isTop, focusGate = focusGate)

                                // Unreachable: SignIn never appears on a Home-rooted stack.
                                Screen.SignIn -> Unit
                            }
                        }
                    }
                }
            } else {
                // SignIn-rooted stack: a successful sign-in takes the same epoch reset as "Add
                // server", since the drawer's account/library fetch already ran signed out.
                when (current) {
                    Screen.SignIn -> SignInScreen(
                        onSignedIn = {
                            resetSessionState()
                            sessionEpoch++
                        },
                    )
                    else -> Unit // unreachable: see NavBackStack's own construction sites
                }
            }
            }
        }

        // docs/21 §1.3: drawn above every retained layer, outside the RetainedScreenLayer/
        // focusGate scheme -- this is the only thing on screen worth focusing while it's up.
        if (crashPromptVisible && stack != null) {
            CrashPromptDialog(
                onReportNow = {
                    AppGraph.crash.markShown()
                    crashPromptVisible = false
                    navigate(stack.push(Screen.Report))
                },
                onLater = {
                    AppGraph.crash.markShown()
                    crashPromptVisible = false
                },
                onDiscard = {
                    AppGraph.crash.discard()
                    crashPromptVisible = false
                },
            )
        }
    }
}

/** docs/21 §1.3: the post-crash Home dialog -- "Report now" / "Later" / "Discard", initial focus
 * on "Report now". */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun CrashPromptDialog(onReportNow: () -> Unit, onLater: () -> Unit, onDiscard: () -> Unit) {
    val reportNowRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { reportNowRequester.requestFocus() }
    BackHandler(onBack = onLater)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(JellybeamTheme.Notte.copy(alpha = 0.85f))
            .focusProperties { exit = { FocusRequester.Cancel } }
            .focusGroup(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.width(480.dp).background(JellybeamTheme.Surface, RoundedCornerShape(12.dp)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BasicText(
                text = androidx.compose.ui.res.stringResource(R.string.crash_prompt_title),
                style = TextStyle(fontFamily = JellybeamTheme.Archivo, fontWeight = FontWeight.Bold, color = JellybeamTheme.Panna, fontSize = 20.sp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CrashPromptButton(
                    label = androidx.compose.ui.res.stringResource(R.string.crash_prompt_report_now),
                    onClick = onReportNow,
                    modifier = Modifier.focusRequester(reportNowRequester),
                    primary = true,
                )
                CrashPromptButton(label = androidx.compose.ui.res.stringResource(R.string.crash_prompt_later), onClick = onLater)
                CrashPromptButton(label = androidx.compose.ui.res.stringResource(R.string.crash_prompt_discard), onClick = onDiscard)
            }
        }
    }
}

/** [CrashPromptDialog]'s pill button -- same fill-on-focus recipe as
 * [tv.jellybeam.ui.discover.DiscoverDetailScreen]'s private `ActionPill`. */
@Composable
private fun CrashPromptButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    Box(
        modifier = modifier
            .background(if (isFocused) JellybeamTheme.Pistacchio else Color.Transparent, RoundedCornerShape(50))
            .let {
                if (!isFocused) {
                    it.border(1.dp, if (primary) JellybeamTheme.Pistacchio else JellybeamTheme.Hairline, RoundedCornerShape(50))
                } else {
                    it
                }
            }
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        BasicText(
            text = label,
            style = TextStyle(
                fontFamily = JellybeamTheme.Archivo,
                fontWeight = FontWeight.SemiBold,
                color = if (isFocused) JellybeamTheme.Notte else JellybeamTheme.Panna,
                fontSize = 14.sp,
            ),
        )
    }
}

/** Pure seam for the retained-layer lifecycle/focus contract. */
internal fun retainedLayerIsActive(index: Int, topIndex: Int, activityResumed: Boolean): Boolean =
    activityResumed && index == topIndex
