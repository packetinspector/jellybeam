package tv.jellybeam

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.annotation.SuppressLint
import android.view.FrameMetrics
import android.view.KeyEvent
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.profileinstaller.ProfileVerifier
import tv.jellybeam.data.CoreGateway
import tv.jellybeam.nav.ExitConfirmationGate
import tv.jellybeam.nav.NavBackStack
import tv.jellybeam.nav.Screen
import tv.jellybeam.nav.diagName
import tv.jellybeam.nav.entryKey
import tv.jellybeam.nav.entryKeys
import tv.jellybeam.nav.forDrawerPick
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
import tv.jellybeam.ui.detail.PersonScreen
import tv.jellybeam.ui.discover.DiscoverDetailScreen
import tv.jellybeam.ui.discover.DiscoverGridScreen
import tv.jellybeam.ui.discover.DiscoverPersonScreen
import tv.jellybeam.ui.discover.DiscoverRequestsScreen
import tv.jellybeam.ui.discover.DiscoverScreen
import tv.jellybeam.ui.discover.DiscoverSearchScreen
import tv.jellybeam.ui.discover.resolveLibraryCard
import tv.jellybeam.ui.focus.FocusSeat
import tv.jellybeam.ui.focus.LocalFocusGate
import tv.jellybeam.ui.focus.LocalFocusSeat
import tv.jellybeam.ui.focus.RootFocus
import tv.jellybeam.ui.focus.gateEnter
import tv.jellybeam.ui.focus.tryRequestFocus
import tv.jellybeam.ui.home.HomeHost
import tv.jellybeam.ui.home.HomeHostArgs
import tv.jellybeam.ui.launch.LaunchScreen
import tv.jellybeam.ui.library.LibraryScreen
import tv.jellybeam.ui.nav.NavDrawerHost
import tv.jellybeam.ui.report.ReportScreen
import tv.jellybeam.ui.search.SearchScreen
import tv.jellybeam.ui.server.ServerManagementScreen
import tv.jellybeam.ui.server.indexOfAccount
import tv.jellybeam.ui.settings.SettingsScreen
import tv.jellybeam.ui.signin.ReauthorizationTarget
import tv.jellybeam.ui.signin.SignInScreen
import tv.jellybeam.data.displayMessage
import tv.jellybeam.data.runCatchingCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import uniffi.jellybeam_core.AccountIdentity
import uniffi.jellybeam_core.AccountInfo
import uniffi.jellybeam_core.Card
import uniffi.jellybeam_core.CoreException
import uniffi.jellybeam_core.HomeLayout
import uniffi.jellybeam_core.SeerrBrowseKind
import uniffi.jellybeam_core.SeerrMediaType
import uniffi.jellybeam_core.favoritesView

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

    /** [SystemClock.elapsedRealtime] at first frame, `null` until then -- lets [JellybeamRoot] gate
     * the cold-start `validateSession` call until slightly after the startup window instead of
     * racing it (docs/10-perf-logging.md). Set from [markFirstFrame], unconditionally. */
    private var firstFrameElapsedMs by mutableStateOf<Long?>(null)

    /** docs/15 §4: the top layer's re-entry seat and whether anything holds focus, filled by [JellybeamRoot]. */
    private val rootFocus = RootFocus()

    /** docs/15 §4: a D-pad key while nothing is focused re-enters the top layer instead of a framework search. */
    @SuppressLint("RestrictedApi") // lint flags every ComponentActivity override of this framework method
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        rootFocus.dispatch(event) || super.dispatchKeyEvent(event)

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
                currentExternalPlayback = { pendingExternalPlayback },
                onExternalPlaybackConsumed = { consumed ->
                    pendingExternalPlayback = pendingAfterConsume(pendingExternalPlayback, consumed)
                },
                launchedForExternalPlayback = launchedForExternalPlayback,
                firstFrameAtMs = firstFrameElapsedMs,
                rootFocus = rootFocus,
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
        AppGraph.updateOwnerIfStarted()?.resume(this)
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
        AppGraph.updateOwnerIfStarted()?.pause()
        AppForeground.browsing.value = false
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

internal data class ExternalPlaybackRequest(val requestId: Long, val itemId: String, val kind: ExternalPlaybackRequestKind)

/**
 * [ExternalPlaybackRequest.kind]: [PLAY] is the "start PlaybackActivity" request (external PLAY
 * intent / `jellybeam://play` deep link); [OPEN_DETAIL] is Feature A's "still watching? -> Stop"
 * routing (docs/feature-dev/spec-still-watching-and-lan-discovery.md), never exported/deep-linked.
 */
internal enum class ExternalPlaybackRequestKind { PLAY, OPEN_DETAIL }

/** What stays pending after [consumed] is handled: null clears all; a stale id keeps the newer request (docs/18 §2.1). */
internal fun pendingAfterConsume(pending: ExternalPlaybackRequest?, consumed: ExternalPlaybackRequest?): ExternalPlaybackRequest? =
    if (consumed == null || pending == consumed) null else pending

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

/**
 * The OPEN_DETAIL branch of the external-request effect: consuming clears the effect key and
 * cancels this coroutine, so [consume] runs last on every path that completes normally.
 * [stillPending] says whether [request] survived the card lookup; an account change that dropped it
 * while the lookup ran must neither navigate nor consume (docs/18 §2.1).
 */
internal suspend fun handleOpenDetail(
    request: ExternalPlaybackRequest,
    readyStack: NavBackStack,
    gateway: CoreGateway,
    stillPending: () -> ExternalPlaybackRequest?,
    consume: () -> Unit,
    navigate: (NavBackStack) -> Unit,
) {
    if (readyStack.isHomeRooted) {
        val card = resolveLibraryCard(gateway, request.itemId)
        if (stillPending() != request) return
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
    consume()
}

/**
 * The PLAY branch of the external-request effect: [consume] runs after the launch, because consuming
 * clears the effect key and cancels this coroutine; a signed-out launch is consumed with [signInRequired]
 * feedback instead. [stillPending] says whether [request] survived the wait for [awaitIdle].
 */
internal suspend fun handleExternalPlay(
    request: ExternalPlaybackRequest,
    readyStackHomeRooted: Boolean,
    awaitIdle: suspend () -> Unit,
    stillPending: () -> ExternalPlaybackRequest?,
    launch: (String) -> Unit,
    consume: () -> Unit,
    signInRequired: () -> Unit,
) {
    if (readyStackHomeRooted) {
        awaitIdle()
        // An account change consumed the request while waiting; launching it now would play on the next account.
        if (stillPending() != request) return
        launch(request.itemId)
        consume()
    } else {
        consume()
        signInRequired()
    }
}

private const val EPISODE_ITEM_TYPE = "Episode"

/** docs/07 Watch state: one ON_START with an account runs the session check and the core's
 * foreground refresh, once each; `foreground` must hop off Main itself (the gateway does). */
internal fun onActivityStart(
    event: Lifecycle.Event,
    hasAccounts: Boolean,
    checkSession: () -> Unit,
    foreground: () -> Unit,
) {
    if (event != Lifecycle.Event.ON_START || !hasAccounts) return
    checkSession()
    foreground()
}

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
 * A sign-in surface owns its ViewModelStore, so leaving it (Back, or the account-change reset) cancels its
 * work such as Quick Connect polling (docs/18 §2.1); the account-tree reset never touches this store.
 */
@Composable
private fun SignInSurface(content: @Composable () -> Unit) {
    val owner = remember { ScreenViewModelStoreOwner() }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner, content = content)
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
 * need per-entry scoping. Home/Search/Settings are singletons on this stack, so they
 * keep the ordinary Activity-level store, and SignIn has its own in [SignInSurface]; Library/Detail get one retained ViewModel per entry.
 * Keyed like [entryKeys] (the entry's id plus identity): the same title reached twice (Detail,
 * Person, the same Detail) is two retained layers, and a shared ViewModel would fire its one-shot
 * navigation and playback in both.
 */
private fun Screen.viewModelStoreKey(id: Int): String? = when (this) {
    is Screen.Library, is Screen.Detail, is Screen.Person -> entryKey(id)
    // docs/14-seerr-discover.md: same per-entry reasoning as Library/Detail above.
    is Screen.DiscoverGrid, is Screen.DiscoverDetail, is Screen.DiscoverPerson -> entryKey(id)
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
    val keepKeys = keep.entries.mapIndexedNotNull { index, screen -> screen.viewModelStoreKey(keep.ids[index]) }.toSet()
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
 * docs/15 §4: one retained back-stack entry. Hidden layers skip their draw and deny focus; the
 * top layer admits focus only around its screen's own placements ([LocalFocusGate], so a pop's
 * default-focus grab can't beat the restore) and, while top, is the root's re-entry [FocusSeat].
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun RetainedScreenLayer(
    isTop: Boolean,
    isShown: Boolean,
    rootFocus: RootFocus,
    content: @Composable () -> Unit,
) {
    val focusGate = remember { mutableStateOf(false) }
    if (!isTop) {
        SideEffect { focusGate.value = false }
    }
    val layerRequester = remember { FocusRequester() }
    val seat = remember { FocusSeat() }
    // The layer's last resort for the root's re-entry: one explicit placement under its own gate.
    seat.fallback = {
        focusGate.value = true
        val placed = tryRequestFocus { layerRequester.requestFocus() }
        focusGate.value = false
        placed
    }
    DisposableEffect(isTop) {
        if (isTop) rootFocus.seat = seat
        onDispose { if (rootFocus.seat === seat) rootFocus.seat = null }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawWithContent { if (isShown) drawContent() }
            .focusRequester(layerRequester)
            .focusProperties { enter = { if (gateEnter(isTop, focusGate.value)) FocusRequester.Default else FocusRequester.Cancel } }
            .focusGroup(),
    ) {
        CompositionLocalProvider(LocalFocusSeat provides seat, LocalFocusGate provides focusGate, content = content)
    }
}

/**
 * Launch decision (`restoreSession` -> Home, else Sign In; `null` while in flight) and navigation (docs/07 §5):
 * one [NavBackStack] in Compose state. docs/15 §3: a Home-rooted stack composes every entry as a
 * [RetainedScreenLayer], so a pop reveals a live layer.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun JellybeamRoot(
    onExitApp: () -> Unit,
    externalPlaybackRequest: ExternalPlaybackRequest?,
    /** Reads the pending request live, so a coroutine sees a reset's drop before the next recomposition. */
    currentExternalPlayback: () -> ExternalPlaybackRequest?,
    /** Consumes that request; null drops whichever one is pending. */
    onExternalPlaybackConsumed: (ExternalPlaybackRequest?) -> Unit,
    /** docs/21 §1.3: this Activity's own launch intent, not the live (soon-consumed)
     * [externalPlaybackRequest] state -- see [MainActivity.isExternalPlaybackIntent]. */
    launchedForExternalPlayback: Boolean = false,
    /** [MainActivity.firstFrameElapsedMs] -- `null` until first frame, then gates the cold-start
     * `validateSession` call below (docs/10). */
    firstFrameAtMs: Long? = null,
    /** docs/15 §4: [MainActivity]'s key funnel; the top layer fills its seat, this its focus flag. */
    rootFocus: RootFocus = remember { RootFocus() },
) {
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val gateway = AppGraph.gateway
    var backStack by remember { mutableStateOf<NavBackStack?>(null) }
    // docs/07 §5: the app's one shared library list, moved only by a views change.
    val libraryViews by gateway.viewsState.collectAsState()
    // docs/07 §5: the drawer's Favorites entry sits first among the views, only while any exist.
    var hasFavorites by remember { mutableStateOf(false) }
    // docs/25 §6.1: the session's committed Home layout, seeded once per session from settings.
    var homeLayout by remember { mutableStateOf(HomeLayout.CLASSIC) }
    val favoritesLabel = androidx.compose.ui.res.stringResource(R.string.drawer_favorites)
    val drawerViews = remember(libraryViews, hasFavorites, favoritesLabel) {
        if (hasFavorites) listOf(favoritesView(favoritesLabel)) + libraryViews else libraryViews
    }

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
    // local-file-only read, re-checked whenever the gateway's `seerrRevision` moves or the session changes.
    // Hidden until the current account's read lands; an account reset returns it there.
    var discoverConfigured by remember { mutableStateOf(false) }

    // The ambient (Activity-level) ViewModelStore, resolved before any per-entry
    // CompositionLocalProvider could shadow it -- where Home/Search/Settings
    // ViewModels actually live, since viewModelStoreKey resolves null for those.
    // resetSessionState clears it alongside screenStoreOwners for that reason; a sign-in
    // surface owns its own store ([SignInSurface]).
    val activityViewModelStoreOwner = LocalViewModelStoreOwner.current
    val composableScope = rememberCoroutineScope()
    val exitConfirmationGate = remember { ExitConfirmationGate() }
    val exitPrompt = androidx.compose.ui.res.stringResource(R.string.press_back_again_to_exit)

    // Per-entry ViewModelStore bookkeeping: a plain mutable map, not Compose state, since
    // mutating it is a side effect of navigating, not something that should recompose.
    val screenStoreOwners = remember { mutableMapOf<String, ScreenViewModelStoreOwner>() }
    // docs/15 §3: each entry's saveable UI state, kept while an overlay replaces the stack.
    val entryStates = rememberSaveableStateHolder()

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
        backStack?.let { old -> (old.entryKeys() - newStack.entryKeys().toSet()).forEach(entryStates::removeState) }
        backStack = newStack
        // docs/21 §2.1: one line per navigation, screen name only.
        AppGraph.diag.event("nav.screen") { tag("screen", newStack.current.diagName()) }
    }

    // Servers-section "Add server": the credentials form replaces the stack (docs/15 §3) without
    // touching [backStack], so Back just flips this to `false`.
    var addServerActive by remember { mutableStateOf(false) }

    /** docs/18 §2.1: the account tree's one reset per account change; blanks [backStack] first so nothing on screen races a cleared ViewModel. */
    fun resetSessionState() {
        // An account change drops a pending external request, even one not yet composed, so it never runs against the next account (docs/18 §2.1).
        onExternalPlaybackConsumed(null)
        focusManager.clearFocus(force = true)
        exitConfirmationGate.reset()
        backStack?.entryKeys()?.forEach(entryStates::removeState)
        backStack = null
        // The add-server form belongs to the account the reset leaves (docs/18 §2.1).
        addServerActive = false
        // Cleared rather than left stale: the epoch bump below re-runs the LaunchedEffect
        // that recomputes both, but not before this recomposition briefly shows the launch screen.
        launchHost = null
        launchUnreachable = false
        // The previous account's Discover answer never shows for the next one (docs/14).
        discoverConfigured = false
        AppGraph.launchWarmup?.discard()
        screenStoreOwners.values.forEach { it.viewModelStore.clear() }
        screenStoreOwners.clear()
        activityViewModelStoreOwner?.viewModelStore?.clear()
    }

    // docs/18 §2.1: the owner calls the latest [resetSessionState], never a stale closure.
    val currentReset by rememberUpdatedState(::resetSessionState)
    val accountTree = remember(gateway) { AccountTreeOwner(gateway.accountEpochs) { currentReset() } }

    // docs/18 §2.1: every account change resets the tree here, derived from the core epoch once restore completed.
    LaunchedEffect(gateway) {
        accountTree.follow { gateway.awaitAccountRestored() }
    }

    /** docs/18 §2.1: a landed switch moves the core epoch and the follower resets the tree; a failed one changes nothing. */
    fun switchServer(index: UInt) {
        composableScope.launch { runCatchingCancellable { gateway.switchSession(index) } }
    }

    var manageServersActive by remember { mutableStateOf(false) }
    var reauthorizationTarget by remember { mutableStateOf<ReauthorizationTarget?>(null) }
    var crashPromptVisible by remember { mutableStateOf(false) }
    var removingServerIndex by remember { mutableStateOf<UInt?>(null) }
    var serverManagementError by remember { mutableStateOf<String?>(null) }

    fun showReauthorization(target: ReauthorizationTarget) {
        focusManager.clearFocus(force = true)
        addServerActive = false
        manageServersActive = false
        serverManagementError = null
        reauthorizationTarget = target
    }

    fun closeReauthorization() {
        reauthorizationTarget = null
    }

    // docs/18 §2.1: a 401 shows the sign-in surface for the account the core named; the coordinator keeps the request until it is consumed here.
    LaunchedEffect(gateway) {
        authorizationRecoveryCoordinator.requests.filterNotNull().collect { request ->
            fun decision() = reauthorizationDecision(
                requestAccount = request.account,
                currentRequestId = authorizationRecoveryCoordinator.requests.value?.id,
                requestId = request.id,
                showing = reauthorizationTarget,
            )
            if (decision() == ReauthorizationDecision.Drop) {
                authorizationRecoveryCoordinator.consume(request.id)
                return@collect
            }
            // One surface at a time (docs/18 §2.1): another account's request waits, unconsumed, for the crash prompt and the sign-in surface to close, one opened during the lookups included.
            val surfaceBlocks = { crashPromptVisible || decision() == ReauthorizationDecision.Wait }
            resolveReauthorization(
                promptVisible = surfaceBlocks,
                awaitPromptClosed = { snapshotFlow(surfaceBlocks).first { !it } },
                lookUp = {
                    val refreshedAccounts = runCatchingCancellable { gateway.listAccounts() }.getOrElse { accounts }
                    val refreshedActiveIndex = runCatchingCancellable { gateway.activeAccountIndex() }
                        .getOrElse { activeAccountIndex }
                    val target = runCatchingCancellable { gateway.reauthorizationAccount(request.account, request.failedPlayback) }.getOrNull()
                    ReauthorizationLookup(refreshedAccounts, refreshedActiveIndex, reauthorizationIndex(refreshedAccounts, target))
                },
                apply = apply@{ lookup ->
                    val targetIndex = lookup.targetIndex ?: return@apply
                    if (decision() != ReauthorizationDecision.Show) return@apply
                    val account = lookup.accounts[targetIndex.toInt()]
                    accounts = lookup.accounts
                    activeAccountIndex = lookup.activeIndex
                    showReauthorization(
                        ReauthorizationTarget(
                            index = targetIndex,
                            serverUrl = account.serverUrl,
                            userId = account.userId,
                            userName = account.userName,
                        ),
                    )
                },
            )
            authorizationRecoveryCoordinator.consume(request.id)
        }
    }

    fun openServerManagement() {
        serverManagementError = null
        manageServersActive = true
    }

    fun reauthorizeServer(index: UInt) {
        val account = accounts.getOrNull(index.toInt()) ?: return
        showReauthorization(
            ReauthorizationTarget(
                index = index,
                serverUrl = account.serverUrl,
                userId = account.userId,
                userName = account.userName,
            ),
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
                    // The core epoch moved: the account tree resets from it (docs/18 §2.1).
                    manageServersActive = false
                    removingServerIndex = null
                } else {
                    accounts = gateway.listAccounts()
                    activeAccountIndex = gateway.activeAccountIndex()
                    removingServerIndex = null
                }
            } catch (error: CoreException) {
                serverManagementError = error.displayMessage(AppGraph.strings)
                removingServerIndex = null
            }
        }
    }

    // Home renders from the mirror, so a revoked token would otherwise wait for the first Play.
    // Launch, account switch and re-authorization check at once ([AccountTreeOwner.epoch]); a return to the
    // foreground checks at most once a minute. A 401 reaches re-authorization through the
    // gateway's seam; every other outcome fails open.
    var sessionCheckedAtMs by remember { mutableLongStateOf(Long.MIN_VALUE) }
    fun checkSession(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - sessionCheckedAtMs < SESSION_CHECK_MIN_INTERVAL_MS) return
        sessionCheckedAtMs = now
        composableScope.launch { runCatchingCancellable { gateway.validateSession() } }
    }

    val latestFirstFrameAtMs by rememberUpdatedState(firstFrameAtMs)

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            onActivityStart(
                event,
                hasAccounts = accounts.isNotEmpty(),
                checkSession = { checkSession(force = false) },
                foreground = { composableScope.launch { runCatchingCancellable { gateway.onForeground() } } },
            )
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(accountTree.epoch) {
        // Cold start: restore and openMirror have been running since process start (LaunchWarmup).
        // Every later epoch, and an Activity recreated in a live process, runs them here.
        launchUnreachable = false
        val warm = AppGraph.launchWarmup?.takeSession()
        val restored = if (warm != null) warm.account else gateway.restoreSession()
        // Local-only (no network round trip): known the instant restoreSession resolves, so the
        // launch screen can name the host well before openMirror's sync reaches the server.
        launchHost = serverHostLabel(restored?.serverUrl)
        // docs/10: the forced check waits until SESSION_CHECK_STARTUP_DELAY_MS past the first frame so it
        // doesn't compete with startup; a later epoch is past that window and checks at once.
        if (restored != null) launch {
            val atMs = snapshotFlow { latestFirstFrameAtMs }.filterNotNull().first()
            delay(atMs + SESSION_CHECK_STARTUP_DELAY_MS - SystemClock.elapsedRealtime())
            checkSession(force = true)
        }
        backStack = if (restored != null) {
            coroutineScope {
                // getSettings reads only in-memory JellybeamCore state -- no
                // dependency on the mirror being open -- so it runs
                // concurrently with openMirror instead of queuing a third
                // sequential IO-dispatch hop behind it. The library list needs the
                // mirror: openMirror publishes it to viewsState.
                // A cold start already read it with the prefetch.
                val settingsDeferred = async {
                    warm?.settings?.let { Result.success(it) } ?: runCatchingCancellable { gateway.getSettings() }
                }

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
                    launchUnreachable = runCatchingCancellable { gateway.openMirror() }
                        .isFailure
                }

                // docs/25 §6.1: a cold start already read it alongside the prefetch.
                homeLayout = warm?.homeLayout
                    ?: settingsDeferred.await().getOrNull()?.homeLayout
                    ?: HomeLayout.CLASSIC

                // Startup screen (docs/07 §5 / docs/09 GOAL item 4): pushed
                // onto Home rather than replacing it, so Back always returns
                // to Home regardless of whether a startup library was
                // resolved. Any failure fetching settings/views (e.g. the
                // mirror never opened) falls back to Home-only, same fail-open
                // shape as the openMirror call above.
                val startupView = runCatchingCancellable {
                    val settings = settingsDeferred.await().getOrThrow()
                    val views = warm?.views ?: gateway.viewsState.value
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
            ExternalPlaybackRequestKind.PLAY ->
                handleExternalPlay(
                    request = request,
                    readyStackHomeRooted = readyStack.isHomeRooted,
                    // docs/17 §3: a PiP'd player never reaches idle, so the launch must instead be
                    // delivered straight to it via onNewIntent.
                    awaitIdle = { if (!pipController.isInPip.value) playbackActivityTracker.awaitIdle() },
                    stillPending = currentExternalPlayback,
                    launch = { PlaybackActivity.launch(context, it) },
                    consume = { onExternalPlaybackConsumed(request) },
                    signInRequired = {
                        Toast.makeText(context, R.string.external_playback_sign_in_required, Toast.LENGTH_LONG).show()
                    },
                )
            ExternalPlaybackRequestKind.OPEN_DETAIL ->
                handleOpenDetail(
                    request = request,
                    readyStack = readyStack,
                    gateway = gateway,
                    stillPending = currentExternalPlayback,
                    consume = { onExternalPlaybackConsumed(request) },
                    navigate = { navigate(it) },
                )
        }
    }

    BackHandler(enabled = stack?.canGoBack == true) {
        stack?.let { navigate(it.pop()) }
    }

    // docs/21 §1.3: once per process, only for a launch that lands on Home rather than heading
    // straight into playback/detail -- launchedForExternalPlayback is this Activity's own launch
    // intent, checked once so a later external intent (onNewIntent) can't re-arm this.
    // The sign-in surface is modal too: the prompt asks once it closes (keyed on it for that).
    LaunchedEffect(stack, reauthorizationTarget == null) {
        val readyStack = stack ?: return@LaunchedEffect
        if (crashPromptMayOpen(
                externalPlayback = launchedForExternalPlayback,
                homeRooted = readyStack.isHomeRooted,
                shownThisProcess = MainActivity.crashDialogShownThisProcess,
                reauthorizing = reauthorizationTarget != null,
            ) && AppGraph.crash.shouldPrompt()
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


    // Side-drawer's Favorites entry and the Servers-section's account list share one fetch/refresh
    // loop, its own rather than reusing Home's feed state (which would construct Home's ViewModel
    // and fire its expensive homeSnapshot() call prematurely). The library list itself is
    // [CoreGateway.viewsState]. Gated on `stack != null` so the first fetch can't race
    // openMirror() above; keyed on [AccountTreeOwner.epoch] too, so a server switch doesn't leave
    // this showing the old server's data.
    suspend fun refreshDrawerFavorites() {
        hasFavorites = gateway.hasFavorites()
    }
    if (stack != null) {
        LaunchedEffect(gateway, accountTree.epoch) {
            launch { runCatchingCancellable { refreshDrawerFavorites() } }
            launch { runCatchingCancellable { accounts = gateway.listAccounts() } }
            launch { activeAccountIndex = runCatchingCancellable { gateway.activeAccountIndex() }.getOrNull() }
            // conflate() + collect gives leading-edge refresh, collapsed bursts, and a
            // guaranteed trailing refresh -- a plain debounce would never fire during a sync
            // burst (events keep arriving under 500ms apart). ChangeRefreshScheduler
            // (docs/16 §4.6, docs/17 §6) offers the same shape but needs machinery this tiny
            // probe doesn't warrant.
            launch {
                gateway.changeEvents().conflate().collect {
                    runCatchingCancellable { refreshDrawerFavorites() }
                    delay(500L)
                }
            }
        }

        // docs/14-seerr-discover.md drawer section: its own effect so a Discover connect/
        // disconnect never re-subscribes the collector above. seerrStatus is zero-network.
        LaunchedEffect(gateway, accountTree.epoch) {
            gateway.seerrRevision.collect {
                discoverConfigured = runCatchingCancellable { gateway.seerrStatus() }.getOrNull()?.configured == true
            }
        }
    }

    val crashPromptShown = crashPromptVisible && stack != null
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(JellybeamTheme.Notte)
            .onFocusChanged { rootFocus.hasFocus = it.hasFocus },
    ) {
        // `stack`, a stable local `val`, gives every branch below a smart-cast, non-null
        // instance -- `null` only during the first frame and during a Servers-section
        // switch/add (resetSessionState sets it back to `null` on purpose).
        when {
            // docs/15 §3: these replace the stack; each entry keeps its saveable state for the
            // return, and each surface's Back handler, composed after the root's, wins.
            reauthorizationTarget != null -> key(reauthorizationTarget?.index, reauthorizationTarget?.userId) {
                BackHandler(onBack = ::closeReauthorization)
                SignInSurface {
                    SignInScreen(
                        reauthorizationTarget = reauthorizationTarget,
                        onSignedIn = {
                            // The gateway opened the quiet window; the core epoch move resets the account tree (docs/18 §2.1).
                            closeReauthorization()
                        },
                    )
                }
            }

            manageServersActive -> {
                BackHandler(enabled = removingServerIndex == null) {
                    manageServersActive = false
                    serverManagementError = null
                }
                ServerManagementScreen(
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
            }

            // Servers-section "Add server". `onSignedIn` only closes the surface: the core epoch
            // move (sign_in made this account active) resets the tree.
            addServerActive -> {
                BackHandler { addServerActive = false }
                SignInSurface {
                    SignInScreen(onSignedIn = { addServerActive = false })
                }
            }

            stack == null -> {
                // docs/brand.md §6.3: the launch screen, also shown while a Servers-section switch resets the stack.
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

            // Stable navigation callbacks: each reads the live back stack when invoked, so a push
            // never hands the retained layers new lambdas (and HomeHostArgs stays equal).
            val latestNavigate = rememberUpdatedState(::navigate)
            val go: ((NavBackStack) -> NavBackStack) -> Unit = remember {
                { change ->
                    val live = backStack
                    if (live != null) latestNavigate.value(change(live))
                }
            }
            val openDetail: (Card) -> Unit = remember { { card -> go { it.pushDetail(card) } } }

            // Drawer-driven navigation: "Home" always resets to the root; anything else pushes
            // one level deeper from Home, but replaces the top entry when already on a
            // Library/Search, so hopping between libraries doesn't pile up a back-stack.
            val navigateFromDrawer: (Screen) -> Unit = remember {
                { screen -> go { it.forDrawerPick(screen) } }
            }

            // docs/14-seerr-discover.md, "Go to library" action: [itemId] is a real Jellyfin id
            // already in the local mirror. Fails open rather than navigating to a broken Detail
            // page: no matching item is indistinguishable here from a transient network failure.
            val openDiscoverLibraryItem: (String) -> Unit = remember {
                { itemId ->
                    // Only onto the stack it was asked from: a Back, drawer pick or account reset
                    // during the lookup drops it.
                    val origin = backStack
                    composableScope.launch {
                        val card = resolveLibraryCard(gateway, itemId)
                        if (card != null && backStack === origin) openDetail(card)
                    }
                }
            }

            if (stack.isHomeRooted) {
                val entries = stack.entries
                val topIndex = entries.lastIndex
                val entryKeys = stack.entryKeys()

                // Every entry, bottom-to-top: `key(...)` on entryKeys tells push/pop (reuse) from
                // replace (dispose+remount) for each slot.
                entries.forEachIndexed { index, entry ->
                    val isShown = retainedLayerIsActive(index, topIndex, AppForeground.browsing.value)
                    // docs/21 §1.3: under the crash prompt the top layer stays drawn but non-top,
                    // so dismissal re-runs its placement.
                    val isTop = isShown && !crashPromptShown
                    key(entryKeys[index]) {
                        entryStates.SaveableStateProvider(entryKeys[index]) {
                        RetainedScreenLayer(isTop = isTop, isShown = isShown, rootFocus = rootFocus) {
                            when (entry) {
                                Screen.Home -> NavDrawerHost(
                                    currentScreen = Screen.Home,
                                    libraries = drawerViews,
                                    onNavigate = navigateFromDrawer,
                                    isTop = isTop,
                                    accounts = accounts,
                                    activeAccountIndex = activeAccountIndex,
                                    onSwitchServer = ::switchServer,
                                    onAddServer = { addServerActive = true },
                                    onManageServers = ::openServerManagement,
                                    discoverConfigured = discoverConfigured,
                                    onOpenDiscover = { navigateFromDrawer(Screen.Discover) },
                                ) {
                                    HomeHost(
                                        layout = homeLayout,
                                        args = HomeHostArgs(
                                            onOpenDetail = openDetail,
                                            isTop = isTop,
                                        ),
                                    )
                                }

                                is Screen.Library -> CompositionLocalProvider(
                                    LocalViewModelStoreOwner provides screenStoreOwners.getOrPut(entry.viewModelStoreKey(stack.ids[index])!!) { ScreenViewModelStoreOwner() },
                                ) {
                                    NavDrawerHost(
                                        currentScreen = entry,
                                        libraries = drawerViews,
                                        onNavigate = navigateFromDrawer,
                                        isTop = isTop,
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
                                                go { it.push(screen) }
                                            },
                                            isTop = isTop,
                                        )
                                    }
                                }

                                Screen.Search -> SearchScreen(
                                    onOpenDetail = openDetail,
                                    onOpenDiscoverDetail = { mediaType, tmdbId ->
                                        go { it.push(Screen.DiscoverDetail(mediaType, tmdbId)) }
                                    },
                                    discoverConfigured = discoverConfigured,
                                    isTop = isTop,
                                )

                                // Settings/Detail get the drawer too, same shape as Home/Library
                                // above. Search stays drawer-less: its query field owns D-pad Left.
                                Screen.Settings -> NavDrawerHost(
                                    currentScreen = Screen.Settings,
                                    libraries = drawerViews,
                                    onNavigate = navigateFromDrawer,
                                    isTop = isTop,
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
                                        onReportProblem = { go { it.push(Screen.Report) } },
                                    )
                                }

                                is Screen.Detail -> CompositionLocalProvider(
                                    LocalViewModelStoreOwner provides screenStoreOwners.getOrPut(entry.viewModelStoreKey(stack.ids[index])!!) { ScreenViewModelStoreOwner() },
                                ) {
                                    NavDrawerHost(
                                        currentScreen = entry,
                                        libraries = drawerViews,
                                        onNavigate = navigateFromDrawer,
                                        isTop = isTop,
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
                                            // docs/07 §5: the mirror dropped this title; pops only this entry, and only while it is the top.
                                            onRemoved = { go { it.popIfTop(stack.ids[index]) } },
                                            onOpenDetail = { card ->
                                                // docs/07 §5: a sibling-episode switch replaces the
                                                // top entry so Back skips visited
                                                // siblings; opening a first episode or a
                                                // non-episode card still pushes.
                                                val stayingOnSiblingEpisode = entry.card.itemType == EPISODE_ITEM_TYPE &&
                                                    card.itemType == EPISODE_ITEM_TYPE
                                                go { live ->
                                                    if (stayingOnSiblingEpisode) live.replaceDetail(card) else live.pushDetail(card)
                                                }
                                            },
                                            onOpenPerson = { person ->
                                                person.id?.let { personId -> go { it.push(Screen.Person(personId, person.name.orEmpty())) } }
                                            },
                                        )
                                    }
                                }

                                // docs/11 §Person page: a cast member's library titles, reached from a Detail cast row.
                                is Screen.Person -> CompositionLocalProvider(
                                    LocalViewModelStoreOwner provides screenStoreOwners.getOrPut(entry.viewModelStoreKey(stack.ids[index])!!) { ScreenViewModelStoreOwner() },
                                ) {
                                    NavDrawerHost(
                                        currentScreen = entry,
                                        libraries = drawerViews,
                                        onNavigate = navigateFromDrawer,
                                        isTop = isTop,
                                        accounts = accounts,
                                        activeAccountIndex = activeAccountIndex,
                                        onSwitchServer = ::switchServer,
                                        onAddServer = { addServerActive = true },
                                        onManageServers = ::openServerManagement,
                                        discoverConfigured = discoverConfigured,
                                        onOpenDiscover = { navigateFromDrawer(Screen.Discover) },
                                    ) {
                                        PersonScreen(
                                            personId = entry.personId,
                                            personName = entry.name,
                                            isTop = isTop,
                                            onOpenDetail = openDetail,
                                            onOpenDiscoverDetail = { mediaType, tmdbId ->
                                                go { it.push(Screen.DiscoverDetail(mediaType, tmdbId)) }
                                            },
                                        )
                                    }
                                }

                                // docs/14-seerr-discover.md: Discover home, reached only via the
                                // drawer entry gated on [discoverConfigured].
                                Screen.Discover -> NavDrawerHost(
                                    currentScreen = Screen.Discover,
                                    libraries = drawerViews,
                                    onNavigate = navigateFromDrawer,
                                    isTop = isTop,
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
                                        onOpenGrid = { kind, title, genreId, genreName ->
                                            go { it.push(Screen.DiscoverGrid(kind, title, genreId, genreName)) }
                                        },
                                        onOpenDetail = { mediaType, tmdbId ->
                                            go { it.push(Screen.DiscoverDetail(mediaType, tmdbId)) }
                                        },
                                        onOpenSearch = { go { it.push(Screen.DiscoverSearch) } },
                                        onOpenMyRequests = { go { it.push(Screen.DiscoverRequests) } },
                                    )
                                }

                                is Screen.DiscoverGrid -> CompositionLocalProvider(
                                    LocalViewModelStoreOwner provides screenStoreOwners.getOrPut(entry.viewModelStoreKey(stack.ids[index])!!) { ScreenViewModelStoreOwner() },
                                ) {
                                    NavDrawerHost(
                                        currentScreen = entry,
                                        libraries = drawerViews,
                                        onNavigate = navigateFromDrawer,
                                        isTop = isTop,
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
                                            onOpenDetail = { mediaType, tmdbId ->
                                                go { it.push(Screen.DiscoverDetail(mediaType, tmdbId)) }
                                            },
                                        )
                                    }
                                }

                                is Screen.DiscoverDetail -> CompositionLocalProvider(
                                    LocalViewModelStoreOwner provides screenStoreOwners.getOrPut(entry.viewModelStoreKey(stack.ids[index])!!) { ScreenViewModelStoreOwner() },
                                ) {
                                    NavDrawerHost(
                                        currentScreen = entry,
                                        libraries = drawerViews,
                                        onNavigate = navigateFromDrawer,
                                        isTop = isTop,
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
                                            onOpenDetail = { mediaType, tmdbId ->
                                                go { it.push(Screen.DiscoverDetail(mediaType, tmdbId)) }
                                            },
                                            onOpenPerson = { personId -> go { it.push(Screen.DiscoverPerson(personId)) } },
                                            onGoToLibrary = openDiscoverLibraryItem,
                                        )
                                    }
                                }

                                is Screen.DiscoverPerson -> CompositionLocalProvider(
                                    LocalViewModelStoreOwner provides screenStoreOwners.getOrPut(entry.viewModelStoreKey(stack.ids[index])!!) { ScreenViewModelStoreOwner() },
                                ) {
                                    NavDrawerHost(
                                        currentScreen = entry,
                                        libraries = drawerViews,
                                        onNavigate = navigateFromDrawer,
                                        isTop = isTop,
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
                                            onOpenDetail = { mediaType, tmdbId ->
                                                go { it.push(Screen.DiscoverDetail(mediaType, tmdbId)) }
                                            },
                                        )
                                    }
                                }

                                Screen.DiscoverRequests -> NavDrawerHost(
                                    currentScreen = Screen.DiscoverRequests,
                                    libraries = drawerViews,
                                    onNavigate = navigateFromDrawer,
                                    isTop = isTop,
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
                                        onOpenDetail = { mediaType, tmdbId ->
                                            go { it.push(Screen.DiscoverDetail(mediaType, tmdbId)) }
                                        },
                                    )
                                }

                                // Drawer-less, like Screen.Search: its query field owns D-pad Left.
                                Screen.DiscoverSearch -> DiscoverSearchScreen(
                                    isTop = isTop,
                                    onOpenDetail = { mediaType, tmdbId ->
                                        go { it.push(Screen.DiscoverDetail(mediaType, tmdbId)) }
                                    },
                                )

                                // docs/21 §1.2: drawer-less like Search/DiscoverSearch (Back
                                // pops, no library rail); pushed from Settings › Troubleshooting
                                // or the post-crash dialog.
                                Screen.Report -> ReportScreen(onExit = { go { it.pop() } }, isTop = isTop)

                                // Unreachable: SignIn never appears on a Home-rooted stack.
                                Screen.SignIn -> Unit
                            }
                        }
                        }
                    }
                }
            } else {
                // SignIn-rooted stack: a successful sign-in moves the core epoch, which takes the same
                // reset as "Add server", since the drawer's account/library fetch already ran signed out.
                when (current) {
                    Screen.SignIn -> SignInSurface {
                        SignInScreen(onSignedIn = {})
                    }
                    else -> Unit // unreachable: see NavBackStack's own construction sites
                }
            }
            }
        }

        LaunchedEffect(stack != null, AppForeground.browsing.value) {
            if (stack != null && AppForeground.browsing.value) {
                androidx.compose.runtime.withFrameNanos { }
                kotlinx.coroutines.delay(10_000)
                val host = context as MainActivity
                if (AppForeground.browsing.value && host.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    AppGraph.updates.resume(host)
                    AppGraph.updates.browsingReady(0)
                }
            }
        }

        // docs/21 §1.3: drawn above every retained layer, which denies focus while it's up.
        if (crashPromptShown && stack != null) {
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

/** Pure seam for whether a retained layer draws: only the top one, while this Activity is resumed. */
internal fun retainedLayerIsActive(index: Int, topIndex: Int, activityResumed: Boolean): Boolean =
    activityResumed && index == topIndex

/** docs/18 §2.1: where the account the core named for a 401 ([target]: the rejected one, while the
 * app uses it) sits in [accounts]; `null` re-authorizes nobody -- never the active one in its place.
 */
internal fun reauthorizationIndex(accounts: List<AccountInfo>, target: AccountInfo?): UInt? =
    target?.let(accounts::indexOfAccount)?.takeIf { it >= 0 }?.toUInt()

/** What the 401 collector does with a request: put it on screen, hold it until the surface closes, or answer and let it go. */
internal enum class ReauthorizationDecision { Show, Wait, Drop }

/** docs/18 §2.1: one surface at a time. A request already answered ([currentRequestId] no longer [requestId]) or for the account already [showing] is dropped; another's waits, unconsumed, for the surface to close. */
internal fun reauthorizationDecision(
    requestAccount: AccountIdentity,
    currentRequestId: Long?,
    requestId: Long,
    showing: ReauthorizationTarget?,
): ReauthorizationDecision = when {
    currentRequestId != requestId -> ReauthorizationDecision.Drop
    showing == null -> ReauthorizationDecision.Show
    showing.serverUrl == requestAccount.serverUrl && showing.userId == requestAccount.userId -> ReauthorizationDecision.Drop
    else -> ReauthorizationDecision.Wait
}

/** What a 401's lookups found: the saved accounts and active index then, and the target's index. */
internal data class ReauthorizationLookup(val accounts: List<AccountInfo>, val activeIndex: UInt?, val targetIndex: UInt?)

/** docs/18 §2.1, docs/21 §1.3: looks a 401's account up and hands the result to [apply] only while
 * no modal surface (the crash prompt, another account's sign-in) is open. One that opened during
 * the lookups sends it back to wait and look up again; nothing suspends between that last check and [apply].
 */
internal suspend fun resolveReauthorization(
    promptVisible: () -> Boolean,
    awaitPromptClosed: suspend () -> Unit,
    lookUp: suspend () -> ReauthorizationLookup,
    apply: (ReauthorizationLookup) -> Unit,
) {
    while (true) {
        awaitPromptClosed()
        val lookup = lookUp()
        if (!promptVisible()) return apply(lookup)
    }
}

/** docs/21 §1.3: the post-crash prompt opens once per process, on a launch that lands on Home, and
 * never over the sign-in surface, which is modal too and holds focus.
 */
internal fun crashPromptMayOpen(externalPlayback: Boolean, homeRooted: Boolean, shownThisProcess: Boolean, reauthorizing: Boolean): Boolean =
    !externalPlayback && homeRooted && !shownThisProcess && !reauthorizing
