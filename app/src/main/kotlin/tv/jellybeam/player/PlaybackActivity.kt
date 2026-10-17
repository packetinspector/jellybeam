package tv.jellybeam.player

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import uniffi.jellybeam_core.AccountIdentity

/**
 * Barrier for externally replacing an active player Activity: Android can resume the singleTask
 * MainActivity before the outgoing PlaybackActivity finishes onStop/onDestroy, so [awaitIdle] lets
 * the old teardown clear the singleton player before a replacement starts.
 */
internal class PlaybackActivityTracker {
    private val activeCount = MutableStateFlow(0)

    val isActive: Boolean get() = activeCount.value > 0

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
 * [QUIET_AFTER_REAUTHORIZATION_MS] after [noteReauthorized] (docs/18 §2.1).
 */
internal class AuthorizationRecoveryCoordinator(private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val pending = MutableStateFlow<AuthorizationRequest?>(null)

    /** The one pending request; another account's 401 while it is held is dropped and comes back on its next call. */
    val requests = pending.asStateFlow()

    /** When the quiet window after the last re-authorization ends; guarded by `this`. */
    private var quietUntilMs = Long.MIN_VALUE
    private var nextId = 0L

    /** A call's 401: [account] is the one whose token was rejected (docs/18 §2.1). */
    fun request(account: AccountIdentity?) = post(account, failedPlayback = false)

    /** The player's own 401, sent after its teardown ended the playback on [account] (docs/18 §2.1). */
    fun requestForFailedPlayback(account: AccountIdentity?) = post(account, failedPlayback = true)

    /** A request naming no account prompts nobody, so it is dropped; a repeat keeps the failed-playback flag of what it replaces (docs/18 §2.1). */
    @Synchronized
    private fun post(account: AccountIdentity?, failedPlayback: Boolean) {
        if (account == null || nowMs() < quietUntilMs) return
        val held = pending.value
        if (held != null && held.account != account) return
        pending.value = AuthorizationRequest(++nextId, account, failedPlayback || held?.failedPlayback == true)
    }

    /** Answers request [id] only. */
    @Synchronized
    fun consume(id: Long) {
        if (pending.value?.id == id) pending.value = null
    }

    /** [account] was just re-authorized: stragglers are dropped for the quiet window and its pending request is answered. */
    @Synchronized
    fun noteReauthorized(account: AccountIdentity) {
        quietUntilMs = nowMs() + QUIET_AFTER_REAUTHORIZATION_MS
        if (pending.value?.account == account) pending.value = null
    }

    companion object {
        const val QUIET_AFTER_REAUTHORIZATION_MS = 5_000L
    }
}

/** One pending [AuthorizationRecoveryCoordinator] request: the account whose token was rejected,
 * and whether the player's own failed playback sent it.
 */
internal data class AuthorizationRequest(val id: Long, val account: AccountIdentity, val failedPlayback: Boolean)

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
     * not a playback prerequisite), released by [onStop] unless it survives a PiP stop, and nulled so [onDestroy]'s fallback can
     * never double-release it.
     */
    private var mediaSessionHolder: MediaSessionHolder? = null

    /** Cancelled in [onStop] so a no-first-frame/error exit can't create a late session after the
     * Activity leaves the foreground. */
    private var mediaSessionInitJob: Job? = null

    /** Waits until ExoPlayer has already been invoked; see [installPlaybackUiAfterPlayerLoad]. */
    private var playbackUiInitJob: Job? = null

    /** Re-requests the one real Compose focus target when this window regains focus, or D-pad
     * keys go dead until recreation. */
    private var windowFocusEpoch by mutableLongStateOf(0L)

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
        AppGraph.updateOwnerIfStarted()?.playbackChanged(true)
        // Isolates the cold-Activity-launch phase from ffi.preparePlayback that follows (docs/10).
        PerfLog.markStartup("playback.activityCreate")
        PerfLog.markPlayback("activity.create")

        itemId = intent.getStringExtra(EXTRA_ITEM_ID)
            ?: error("PlaybackActivity requires $EXTRA_ITEM_ID")
        val startFromBeginning = intent.getBooleanExtra(EXTRA_START_FROM_BEGINNING, false)

        // Obtained here, not in the Composable, so onStop/onDestroy reach the same instance.
        viewModel = ViewModelProvider(
            this,
            PlaybackViewModelFactory(AppGraph.gateway, AppGraph.playerHolder, itemId, startFromBeginning, AppGraph.strings, AppGraph.trickplayFetcher),
        )[PlaybackViewModel::class.java]

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // docs/17 §2/§3: teardown callback for MainActivity's app-exit cleanup; singleInstance
        // makes finishAndRemoveTask always safe, and it removes the task record too.
        pipController.finishPlayback = { finishAndRemoveTask() }

        // Feeds PipController's clamped aspect from every reported video size; removed in onDestroy
        AppGraph.playerHolder.addListener(videoSizeListener)

        installPlaybackUiAfterPlayerLoad()
    }

    /**
     * Creates [mediaSessionHolder] once the first frame has rendered; called from [onStart]'s wait
     * and directly when the frame is already up, since [onStop] releases the session even for a
     * transient stop like screen-off (docs/17 §3). No-op once a session exists, before the first frame, or once
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
            router = MediaKeyRouter(
                stop = viewModel::stopFromRemote,
                seekBy = { viewModel.seek(it, resolveTrickplay = false) },
                seekToMs = viewModel::seekToPositionMs,
                skipForwardMs = { viewModel.state.value.skipForwardMs },
                skipBackMs = { viewModel.state.value.skipBackMs },
                playWhenReady = { viewModel.playWhenReady },
                togglePlayPause = viewModel::togglePlayPause,
            ),
            sessionActivity = PendingIntent.getActivity(
                this,
                0,
                intent(this, itemId).putExtra(EXTRA_RESUME_SESSION, true),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        PerfLog.markPlayback("mediaSession.ready")
    }

    /**
     * docs/17 §3: (re)creates the MediaSession [onStop] released, now or at the first rendered frame;
     * waits rather than posting, so the session constructor can't win the main-loop race against a
     * fast server response.
     */
    override fun onStart() {
        super.onStart()
        if (viewModel.state.value.hasRenderedFirstFrame) return ensureMediaSession()
        mediaSessionInitJob?.cancel()
        mediaSessionInitJob = lifecycleScope.launch {
            viewModel.state.first { it.hasRenderedFirstFrame }
            ensureMediaSession()
        }
    }

    /** docs/12 §9, docs/17 §2: while no session exists, transport keys (DOWN and UP) must not reach another app's. */
    @SuppressLint("RestrictedApi") // lint flags every ComponentActivity override of this framework method
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        MediaKeys.dispatchConsumed(event.keyCode, sessionLive = mediaSessionHolder != null) || super.dispatchKeyEvent(event)

    override fun onStop() {
        super.onStop()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        playbackUiInitJob?.cancel()
        playbackUiInitJob = null
        mediaSessionInitJob?.cancel()
        mediaSessionInitJob = null
        // docs/17 §3: a PiP stop keeps playing, so its session stays for the remote keys and a dismissal tears down via
        // onPictureInPictureModeChanged(false) or onDestroy; every other stop releases it (nulled so onDestroy's release is a no-op).
        if (sessionShouldSurviveStop(inPip = inPip(), finishing = isFinishing)) return
        mediaSessionHolder?.release()
        mediaSessionHolder = null
        // Any other stop ends playback (a session left running past it is the ghost-task class); the exactly-once guard
        // makes a duplicate call a no-op.
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
     * behind it. Terminal events (those that close the player) are collected here for the whole
     * Activity lifetime, so one emitted before the screen installs is never lost and none is handled
     * twice; [PlaybackScreen] handles only the rest.
     */
    private fun installPlaybackUiAfterPlayerLoad() {
        lifecycleScope.launch {
            viewModel.events.collect { event ->
                when (event) {
                    PlaybackEvent.Finish -> closePlayer()
                    is PlaybackEvent.FinishToDetail -> finishToDetail(event.itemId)
                    is PlaybackEvent.ReauthorizationRequired -> {
                        authorizationRecoveryCoordinator.requestForFailedPlayback(event.account)
                        closePlayer()
                    }
                    is PlaybackEvent.FinishWithMessage -> {
                        Toast.makeText(this@PlaybackActivity, event.message, Toast.LENGTH_LONG).show()
                        closePlayer()
                    }
                    is PlaybackEvent.AutoSkipped, PlaybackEvent.SeekUnavailable, PlaybackEvent.SubtitleUnavailable -> Unit // PlaybackScreen's
                }
            }
        }

        playbackUiInitJob = lifecycleScope.launch {
            viewModel.state.first { it.phase == PlaybackUiState.Phase.READY }
            if (isFinishing || isDestroyed) return@launch
            PerfLog.markPlayback("ui.install")
            setContent {
                PlaybackScreen(
                    viewModel = viewModel,
                    focusRestoreEpoch = windowFocusEpoch,
                    inPictureInPicture = inPictureInPicture,
                    onExitRequested = { if (!tryEnterPip()) finish() },
                )
            }
        }
    }

    /** docs/12 §13: "Still watching?" Stop/timeout routes to the next episode's detail page, unless in
     * PiP (docs/17 §3), which has no browsing UI to route into.
     */
    private fun finishToDetail(nextItemId: String) {
        if (inPip()) return finishAndRemoveTask()
        leavingForDetail = true
        // NO_USER_ACTION: a synthetic routing Intent, not a user action.
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(ExternalPlaybackContract.ACTION_OPEN_DETAIL)
                .putExtra(ExternalPlaybackContract.EXTRA_ITEM_ID, nextItemId)
                .addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION),
        )
        finish()
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
        viewModel.refreshSubtitleStyle()
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
        AppGraph.updateOwnerIfStarted()?.playbackChanged(playbackActivityTracker.isActive)
    }

    companion object {
        private const val EXTRA_ITEM_ID = "tv.jellybeam.player.EXTRA_ITEM_ID"

        /** docs/11's "Start from beginning" override; absent/`false` preserves resume behavior. */
        private const val EXTRA_START_FROM_BEGINNING = "tv.jellybeam.player.EXTRA_START_FROM_BEGINNING"

        /** docs/17 §2: marks an Intent as "bring this Activity forward"; [onNewIntent] never
         * restarts the item when `true`. */
        private const val EXTRA_RESUME_SESSION = "tv.jellybeam.player.EXTRA_RESUME_SESSION"

        fun launch(context: Context, itemId: String, startFromBeginning: Boolean = false) {
            AppGraph.beforePlayback { context.startActivity(intent(context, itemId, startFromBeginning)) }
        }

        fun intent(context: Context, itemId: String, startFromBeginning: Boolean = false): Intent =
            Intent(context, PlaybackActivity::class.java)
                .putExtra(EXTRA_ITEM_ID, itemId)
                .putExtra(EXTRA_START_FROM_BEGINNING, startFromBeginning)
    }
}
