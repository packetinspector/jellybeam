package tv.jellybeam.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import coil.Coil
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import tv.jellybeam.AppGraph
import tv.jellybeam.MainActivity
import tv.jellybeam.perf.PerfLog

/**
 * Barrier for externally replacing an active player Activity: Android can resume the singleTask
 * MainActivity before the outgoing PlaybackActivity finishes onStop/onDestroy, so [awaitIdle] lets
 * the old teardown clear the singleton player before a replacement starts.
 */
internal class PlaybackActivityTracker {
    private val activeCount = MutableStateFlow(0)

    fun onCreated() = activeCount.update { it + 1 }
    fun onDestroyed() = activeCount.update { (it - 1).coerceAtLeast(0) }
    suspend fun awaitIdle() = activeCount.first { it == 0 }
}

internal val playbackActivityTracker = PlaybackActivityTracker()

/**
 * Handoff from any core call rejected with HTTP 401 (playback, or
 * [tv.jellybeam.data.RealCoreGateway]'s seam) to the retained MainActivity. A StateFlow, not a
 * fire-and-forget event, keeps the request pending until Main consumes it. A call that left with
 * the old token can still fail after the user re-authorized, so requests are dropped for
 * [QUIET_AFTER_REAUTHORIZATION_MS] after [noteReauthorized].
 */
internal class AuthorizationRecoveryCoordinator(private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val pendingRequest = MutableStateFlow<Long?>(null)
    val requests = pendingRequest.asStateFlow()
    private var nextId = 0L
    private var quietUntilMs = Long.MIN_VALUE

    @Synchronized
    fun request() {
        if (nowMs() < quietUntilMs) return
        pendingRequest.value = ++nextId
    }

    fun consume(id: Long) {
        pendingRequest.compareAndSet(id, null)
    }

    @Synchronized
    fun noteReauthorized() {
        quietUntilMs = nowMs() + QUIET_AFTER_REAUTHORIZATION_MS
        pendingRequest.value = null
    }

    companion object {
        const val QUIET_AFTER_REAUTHORIZATION_MS = 5_000L
    }
}

internal val authorizationRecoveryCoordinator = AuthorizationRecoveryCoordinator()

/**
 * The playback surface: hosts [PlaybackScreen] but owns none of the actual playback state, which
 * lives in [PlaybackViewModel] and the process-wide [AppGraph.playerHolder]. Its real
 * responsibilities are keeping the screen on while playing and
 * guaranteeing the exactly-once stop-report path runs before this Activity is gone for good.
 *
 * docs/17 §2/§3 is the spec of record for this Activity's picture-in-picture behaviour; read it
 * before touching [onStop], [onDestroy], [onNewIntent], [onUserLeaveHint], or
 * [onPictureInPictureModeChanged].
 */
class PlaybackActivity : ComponentActivity() {

    private lateinit var viewModel: PlaybackViewModel

    /** Kept as a field so [ensureMediaSession] and a later [onNewIntent] swap reach the item. */
    private lateinit var itemId: String

    /**
     * Scoped to this Activity instance, not [AppGraph.playerHolder]'s process-wide lifetime.
     * Created via [ensureMediaSession] after the first rendered frame (system-integration chrome,
     * not a playback prerequisite), released in [onStop] and nulled so [onDestroy]'s fallback can
     * never double-release it.
     */
    private var mediaSessionHolder: MediaSessionHolder? = null

    /** Cancelled in [onStop] so a no-first-frame/error exit can't create a late session after the
     * Activity leaves the foreground. */
    private var mediaSessionInitJob: Job? = null

    /** Waits until ExoPlayer has already been invoked; see [installPlaybackUiAfterPlayerLoad]. */
    private var playbackUiInitJob: Job? = null

    /** Handles negotiation failures while no Compose event collector exists yet. */
    private var startupEventJob: Job? = null

    /** Re-requests the one real Compose focus target when this window regains focus, or D-pad
     * keys go dead until recreation. */
    private var windowFocusEpoch by mutableStateOf(0L)

    /** docs/17 §2/§3: Compose-visible mirror of `isInPictureInPictureMode`; [PlaybackScreen] gates
     * every overlay but the bare video surface on this. */
    private var inPictureInPicture by mutableStateOf(false)

    /**
     * Set just before an [onNewIntent]-driven item swap while already in PiP: Android's fullscreen
     * expand fires [onPictureInPictureModeChanged] with `false`, indistinguishable from a genuine
     * dismissal without this flag. Cleared there and defensively in [onResume].
     */
    private var expandViaIntent = false

    /** Set for the "Still watching?" Stop/timeout detail-page routing so [tryEnterPip] never fires
     * while routing to [MainActivity]'s detail page instead of finishing. */
    private var leavingForDetail = false

    /**
     * Feeds [PipController.updateVideoSize] from Media3's reported video size (docs/17 §2) and,
     * while already in PiP, republishes [android.app.PictureInPictureParams] so an item swap
     * resizes the live window. Registered in [onCreate], removed in [onDestroy].
     */
    private val videoSizeListener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            pipController.updateVideoSize(videoSize.width, videoSize.height)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && inPip()) {
                runCatching { this@PlaybackActivity.setPictureInPictureParams(pipController.params()) }
            }
        }
    }

    /** `Activity.isInPictureInPictureMode` is API 24+; this app's minSdk is 23. */
    private fun inPip(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode

    /** docs/17 §2: "Enabled = Settings.mini_player_enabled ... and isSupported". */
    private fun miniPlayerEnabled(): Boolean =
        viewModel.state.value.miniPlayerEnabled && pipController.isSupported(this)

    /** docs/17 §3: mini player on and supported, not already exiting, and the first frame already
     * rendered (else PiP would shrink a black window). */
    private fun tryEnterPip(): Boolean =
        miniPlayerEnabled() &&
            !isFinishing &&
            !leavingForDetail &&
            viewModel.state.value.hasRenderedFirstFrame &&
            pipController.enter(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playbackActivityTracker.onCreated()
        // Isolates the cold-Activity-launch phase from ffi.preparePlayback that follows (docs/10).
        PerfLog.markStartup("playback.activityCreate")
        PerfLog.markPlayback("activity.create")

        itemId = intent.getStringExtra(EXTRA_ITEM_ID)
            ?: error("PlaybackActivity requires $EXTRA_ITEM_ID")
        val startFromBeginning = intent.getBooleanExtra(EXTRA_START_FROM_BEGINNING, false)

        // Obtained here, not in the Composable, so onStop/onDestroy reach the same instance.
        viewModel = ViewModelProvider(
            this,
            PlaybackViewModelFactory(AppGraph.gateway, AppGraph.playerHolder, itemId, startFromBeginning, AppGraph.trickplayFetcher),
        )[PlaybackViewModel::class.java]

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // docs/17 §2/§3: teardown callback for MainActivity's app-exit cleanup; singleInstance
        // makes finishAndRemoveTask always safe, and it removes the task record too.
        pipController.finishPlayback = { finishAndRemoveTask() }

        // Feeds PipController's clamped aspect from every reported video size; removed in onDestroy
        AppGraph.playerHolder.addListener(videoSizeListener)

        installPlaybackUiAfterPlayerLoad()

        // Waits for the first frame rather than posting, so a posted MediaSession constructor can't
        // win the main-loop race against a fast server response and recreate the stall this removes
        mediaSessionInitJob = lifecycleScope.launch {
            viewModel.state.first { it.hasRenderedFirstFrame }
            ensureMediaSession()
        }
    }

    /**
     * Creates [mediaSessionHolder] once the first frame has rendered; called from [onCreate]'s wait
     * and again from [onStart], since [onStop] releases the session even for a transient stop like
     * screen-off (docs/17 §3). No-op once a session exists, before the first frame, or once
     * finishing/destroyed. `setSessionActivity`'s [PendingIntent] carries [EXTRA_RESUME_SESSION] so
     * a system tap resumes this Activity in place rather than restarting the item (docs/17 §2).
     */
    private fun ensureMediaSession() {
        if (mediaSessionHolder != null) return
        if (!viewModel.state.value.hasRenderedFirstFrame) return
        if (isFinishing || isDestroyed) return
        mediaSessionHolder = MediaSessionHolder(
            context = this,
            player = AppGraph.playerHolder.mediaSessionPlayer,
            sessionActivity = PendingIntent.getActivity(
                this,
                0,
                intent(this, itemId).putExtra(EXTRA_RESUME_SESSION, true),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        PerfLog.markPlayback("mediaSession.ready")
    }

    /** docs/17 §3: recreates the MediaSession [onStop] released, once STARTED again. */
    override fun onStart() {
        super.onStart()
        if (viewModel.state.value.hasRenderedFirstFrame) ensureMediaSession()
    }

    override fun onStop() {
        super.onStop()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        playbackUiInitJob?.cancel()
        playbackUiInitJob = null
        startupEventJob?.cancel()
        startupEventJob = null
        mediaSessionInitJob?.cancel()
        mediaSessionInitJob = null
        // A session must never outlive this Activity in the foreground, PiP included. Nulled so
        // onDestroy's fallback release is a no-op; onStart's ensureMediaSession recreates it.
        mediaSessionHolder?.release()
        mediaSessionHolder = null
        // docs/17 §3: in PiP the window stays visible and playback must keep running, so a
        // transient onStop must never stop it. A dismissal reaches teardown either way, via
        // onPictureInPictureModeChanged(false) or inPip() already being false here.
        if (inPip() && !isFinishing) return
        // Otherwise end the session and finish if merely backgrounded with mini player off --
        // a session left running past onStop outside PiP is the leaked-player/ghost-task class
        // of bug. The exactly-once guard makes a duplicate call a no-op.
        viewModel.stopPlaybackOnce()
        if (!isFinishing) {
            finishAndRemoveTask()
        }
    }

    /**
     * Keeps Compose's initial player-screen composition off the latency path to
     * `ExoPlayer.prepare()`: [PlaybackUiState.phase] reaches READY only after [PlaybackPlayer.load]
     * calls through, so the theme-provided black window is the loading surface until then, letting
     * decoder/network startup overlap [PlaybackScreen]'s first composition instead of queuing
     * behind it. The separate event waiter owns terminal startup outcomes while no Compose
     * collector exists yet, and is cancelled before the real screen installs.
     */
    private fun installPlaybackUiAfterPlayerLoad() {
        startupEventJob = lifecycleScope.launch {
            when (
                val event = viewModel.events.first {
                    it is PlaybackEvent.Finish ||
                        it is PlaybackEvent.ReauthorizationRequired ||
                        it is PlaybackEvent.FinishWithMessage
                }
            ) {
                PlaybackEvent.Finish -> closePlayer()
                PlaybackEvent.ReauthorizationRequired -> {
                    authorizationRecoveryCoordinator.request()
                    closePlayer()
                }
                is PlaybackEvent.FinishWithMessage -> {
                    Toast.makeText(this@PlaybackActivity, event.message, Toast.LENGTH_LONG).show()
                    closePlayer()
                }
                is PlaybackEvent.AutoSkipped -> Unit // excluded by the predicate above
                is PlaybackEvent.FinishToDetail -> Unit // excluded above; PlaybackScreen-owned once running
            }
        }

        playbackUiInitJob = lifecycleScope.launch {
            viewModel.state.first { it.phase == PlaybackUiState.Phase.READY }
            startupEventJob?.cancel()
            startupEventJob = null
            if (isFinishing || isDestroyed) return@launch
            PerfLog.markPlayback("ui.install")
            setContent {
                PlaybackScreen(
                    viewModel = viewModel,
                    onFinish = ::closePlayer,
                    onReauthorizationRequired = {
                        authorizationRecoveryCoordinator.request()
                        closePlayer()
                    },
                    focusRestoreEpoch = windowFocusEpoch,
                    onFinishToDetail = { nextItemId ->
                        // "Still watching?" Stop/timeout: route to the next episode's detail page,
                        // unless already in PiP (docs/17 §3), with no browsing UI to route into.
                        if (inPip()) {
                            finishAndRemoveTask()
                        } else {
                            leavingForDetail = true
                            // NO_USER_ACTION: a synthetic routing Intent, not a user action.
                            startActivity(
                                Intent(this@PlaybackActivity, MainActivity::class.java)
                                    .setAction(ExternalPlaybackContract.ACTION_OPEN_DETAIL)
                                    .putExtra(ExternalPlaybackContract.EXTRA_ITEM_ID, nextItemId)
                                    .addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION),
                            )
                            finish()
                        }
                    },
                    inPictureInPicture = inPictureInPicture,
                    onExitRequested = { if (!tryEnterPip()) finish() },
                )
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) windowFocusEpoch += 1L
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // docs/17 §2: this extra means a system tap brought this forward; never restart the item
        if (intent.getBooleanExtra(EXTRA_RESUME_SESSION, false)) return
        val newItemId = intent.getStringExtra(EXTRA_ITEM_ID) ?: return
        // An expected PiP exit (fullscreen expand), not a user dismissal.
        if (inPip()) expandViaIntent = true
        itemId = newItemId
        viewModel.replaceItem(newItemId, intent.getBooleanExtra(EXTRA_START_FROM_BEGINNING, false))
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // docs/17 §3: Home during fullscreen READY playback enters PiP; already-PiP'd never tries.
        if (!inPip() && viewModel.state.value.phase == PlaybackUiState.Phase.READY) tryEnterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPictureInPicture = isInPictureInPictureMode
        pipController.setInPip(isInPictureInPictureMode)

        if (isInPictureInPictureMode) {
            // Entering PiP (docs/17 §3): free memory for the browsing UI by clearing Coil's cache.
            Coil.imageLoader(applicationContext).memoryCache?.clear()
        } else {
            // Exiting PiP (docs/17 §3): an expand (intent-driven or lifecycle reaching STARTED)
            // does nothing; a dismissal (lifecycle stays below STARTED) finishes.
            if (expandViaIntent) {
                expandViaIntent = false
            } else if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                finishAndRemoveTask()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Any pending intent-driven PiP exit has fully completed once we're resumed.
        expandViaIntent = false
    }

    /**
     * docs/17 §3: every ordinary exit funnels through here so a session ending in PiP always uses
     * [finishAndRemoveTask] -- the only way to close from PiP without risking a ghost task.
     */
    private fun closePlayer() {
        if (inPip()) finishAndRemoveTask() else finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Guaranteed fallback: a no-op if onStop already closed the session (exactly-once guard).
        viewModel.stopPlaybackOnce()
        // Same fallback shape for the MediaSession -- a no-op on the ordinary path.
        mediaSessionHolder?.release()
        mediaSessionHolder = null
        // A Player.Listener on the process-wide playerHolder must never outlive this Activity.
        AppGraph.playerHolder.removeListener(videoSizeListener)
        // docs/17 §3: clear PiP state before the tracker barrier below opens, so a replacement
        // Activity's onCreate never sees this instance's stale state.
        pipController.onActivityDestroyed()
        // Last: a replacement waiting in MainActivity may launch the instant this reaches zero.
        playbackActivityTracker.onDestroyed()
    }

    companion object {
        private const val EXTRA_ITEM_ID = "tv.jellybeam.player.EXTRA_ITEM_ID"

        /** docs/11's "Start from beginning" override; absent/`false` preserves resume behavior. */
        private const val EXTRA_START_FROM_BEGINNING = "tv.jellybeam.player.EXTRA_START_FROM_BEGINNING"

        /** docs/17 §2: marks an Intent as "bring this Activity forward"; [onNewIntent] never
         * restarts the item when `true`. */
        private const val EXTRA_RESUME_SESSION = "tv.jellybeam.player.EXTRA_RESUME_SESSION"

        fun intent(context: Context, itemId: String, startFromBeginning: Boolean = false): Intent =
            Intent(context, PlaybackActivity::class.java)
                .putExtra(EXTRA_ITEM_ID, itemId)
                .putExtra(EXTRA_START_FROM_BEGINNING, startFromBeginning)
    }
}
