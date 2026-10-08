package tv.jellybeam.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.ui.PlayerView
import tv.jellybeam.AppGraph
import tv.jellybeam.i18n.AndroidUiStrings
import tv.jellybeam.perf.PerfLog
import java.util.concurrent.atomic.AtomicLong
import okhttp3.Call
import okhttp3.OkHttpClient
import uniffi.jellybeam_core.PlaybackPlan
import uniffi.jellybeam_core.PlayMethodFfi
import uniffi.jellybeam_core.SubtitleActionFfi
import uniffi.jellybeam_core.TrackDecisionFfi

/** [SeekSerializer]'s validation logging on device (docs/18-playback-quality.md §5) -- numbers
 * only, never titles. */
private const val SEEK_LOG_TAG = "JellybeamSeek"

/** `player.state`'s `state` tag (docs/21 §2.1) -- [Player]'s four playback states by name. */
private fun playerStateName(playbackState: Int): String = when (playbackState) {
    Player.STATE_IDLE -> "IDLE"
    Player.STATE_BUFFERING -> "BUFFERING"
    Player.STATE_READY -> "READY"
    Player.STATE_ENDED -> "ENDED"
    else -> "UNKNOWN"
}

/**
 * The player operations [PlaybackViewModel] needs, factored out of [PlayerHolder] so the
 * ViewModel's FFI-orchestration/reporting logic is unit-testable against a fake -- a real
 * [PlayerHolder] needs an Android [Context] and a real [ExoPlayer]. [attach]/[detach] aren't part
 * of this interface: only [tv.jellybeam.player.PlaybackScreen]'s `AndroidView` wiring needs them.
 */
interface PlaybackPlayer {
    /**
     * Loads [plan] and starts playback from its resume position, if any. [tolerateMislabeledLevels]
     * /[audioDecoderPreferences] are that session's settings snapshots, fetched once per session by
     * [PlaybackViewModel.start]. Per docs/18-playback-quality.md §3.1, since this is a process-wide
     * singleton, every implementation must reset the per-item track-selection baseline before any
     * [applyTrackDecision] for [plan] runs, or a previous item's state silently survives.
     */
    fun load(
        plan: PlaybackPlan,
        tolerateMislabeledLevels: Boolean = true,
        audioDecoderPreferences: AudioDecoderPreferences = AudioDecoderPreferences(),
    )

    fun addListener(listener: Player.Listener)
    fun removeListener(listener: Player.Listener)

    fun togglePlayPause()

    /** Sets `playWhenReady = false`; a no-op if already paused. Never stops/clears the item -- the
     * hold-to-seek end clamp pauses on commit without tearing playback down. */
    fun pause()

    /**
     * Sets the live playback rate (docs/12 §12 speed menu), pass-through to
     * `Player.setPlaybackSpeed`; pitch stays at Media3's default. [PlaybackViewModel.start] resets
     * this to `1f` at the top of every session, since the shared singleton would otherwise carry a
     * previous session's rate into a freshly loaded item.
     */
    fun setPlaybackRate(rate: Float)

    /** Seeks by [deltaMs] from the current position, clamped to `[0, duration]`. */
    fun seekBy(deltaMs: Long)

    /** Media3's current window seekability; UNSEEKABLE for e.g. an MKV with no Cues index, where
     * every seekTo restarts from 0:00, and UNKNOWN while the window is still a placeholder (docs/12 §9). */
    val seekability: Seekability get() = Seekability.SEEKABLE

    /** Stops playback and clears the loaded item; must not release/tear down the player. */
    fun stopAndClear()

    val isPlaying: Boolean
    val playbackState: Int

    fun currentPositionTicks(): Long

    /**
     * How far into the item Media3 has actually buffered, in Jellyfin ticks -- an absolute position
     * (like [currentPositionTicks]), matching `ExoPlayer.getBufferedPosition`'s contract. Feeds the
     * OSD progress bar's buffered fill.
     */
    fun bufferedPositionTicks(): Long

    /**
     * `true` when the player intends to play as soon as able (`getPlayWhenReady`). Distinct from
     * [isPlaying] (also `false` during `STATE_BUFFERING`): lets [PlaybackViewModel] tell "stalled
     * while trying to play" apart from "stalled because paused".
     */
    val playWhenReady: Boolean

    /**
     * The most recent bandwidth estimate from Media3's `DefaultBandwidthMeter`, in bytes/sec
     * (converted from `onBandwidthEstimate`'s bits/sec). `0L` before this session's first sample
     * lands. Feeds the buffering pill's live throughput readout.
     */
    fun bandwidthBytesPerSecond(): Long

    /** A cheap, synchronous snapshot for the info overlay; no network or FFI work is performed. */
    fun livePlaybackStats(): PlaybackLiveStats

    /** The most recent [Tracks] snapshot announced via `onTracksChanged`, or `null` before the
     * first. [PlaybackViewModel] reads this once per session for [TrackMapping.toTrackInfos]. */
    val currentTracks: Tracks?

    /**
     * Applies track selection (docs/09-settings-plan.md step 3): [decision] must be resolved
     * against this exact [tracks] snapshot, since track ids are only meaningful against the
     * snapshot they came from. A `null` [TrackMapping.resolve] result for either half is "nothing
     * to apply", never an error.
     */
    fun applyTrackDecision(decision: TrackDecisionFfi, tracks: Tracks)

    /**
     * Retries playback after a [ReconnectPolicy.isRecoverable] player error: seeks to
     * [positionTicks] (the position [PlaybackViewModel] remembered when the error occurred, since
     * Media3's own reads aren't trustworthy again until retry succeeds), then calls `prepare()` to
     * retry from [Player.STATE_IDLE]. [playing] restores the play/pause intent from before the
     * error explicitly, rather than assuming Media3 preserved it.
     */
    fun retryAfterError(positionTicks: Long, playing: Boolean)

    /** `true` when Media3's load-level network policy already spent its recovery window; the
     * ViewModel must not stack a second reconnect budget on top. */
    fun hasExhaustedLoadRetryBudget(): Boolean = false

    /**
     * Called with the Media3 decoder name every time the shared player initializes a *video*
     * decoder, forwarded verbatim -- docs/18 §1's third local-evidence signal: [PlaybackViewModel]
     * runs [SoftwareDecoder.isSoftwareOnly] on it. Settable so [PlaybackViewModel] can wire this in
     * `init` and clear it in `onCleared`; `null` is the valid "nobody is listening" state.
     */
    var onVideoDecoderInitialized: ((String) -> Unit)?
}

/**
 * docs/18 §3.1: the per-item track-selection reset [PlayerHolder.load] applies to [current] before
 * this item's Rust decision is applied via [PlayerHolder.applyTrackDecision] -- top-level, not a
 * class member, so it's unit-testable against a plain [TrackSelectionParameters].
 *
 * Clears both audio and text overrides (a group from the previous item's [Tracks] snapshot is
 * meaningless against this item's groups) and re-enables [C.TRACK_TYPE_TEXT] (undoes a previous
 * [SubtitleActionFfi.OFF]); audio isn't re-enabled the same way since nothing ever disables it.
 * Nothing else on [current] is touched.
 */
internal fun trackSelectionBaseline(current: TrackSelectionParameters): TrackSelectionParameters =
    current.buildUpon()
        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
        .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        .build()

/**
 * [decision] applied onto [current] against the [tracks] snapshot its ids came from -- top-level so
 * the test player applies decisions exactly as [PlayerHolder] does. Audio and subtitle halves are
 * independent; an out-of-range id on one never prevents the other.
 */
internal fun withTrackDecision(current: TrackSelectionParameters, decision: TrackDecisionFfi, tracks: Tracks): TrackSelectionParameters {
    var params = current.buildUpon()

    decision.audioTrackId?.let { id ->
        TrackMapping.resolve(tracks, id)?.let { resolved ->
            val group = tracks.groups[resolved.groupIndex].mediaTrackGroup
            params = params.setOverrideForType(TrackSelectionOverride(group, resolved.trackIndex))
        }
    }

    val subtitleTrackId = decision.subtitleTrackId
    if (subtitleTrackId != null) {
        // A specific track wins outright, regardless of subtitleAction.
        TrackMapping.resolve(tracks, subtitleTrackId)?.let { resolved ->
            val group = tracks.groups[resolved.groupIndex].mediaTrackGroup
            params = params
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(group, resolved.trackIndex))
        }
    } else if (decision.subtitleAction == SubtitleActionFfi.OFF) {
        params = params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
    }
    // SubtitleActionFfi.LEAVE with no subtitleTrackId does nothing here, since load() already
    // reset trackSelectionParameters to a clean per-item baseline.

    return params.build()
}

/**
 * docs/18-playback-quality.md §5.2: sequences the resume-seek [PlayerHolder.load] arms for a
 * `startPositionMs > 0` load. `setMediaItem(item, startPositionMs)` applies that position exactly
 * (decoding through every frame back to the last keyframe); one extra `seekTo` issued once the
 * period is prepared lands on the keyframe at/before it instead. Pure, single-threaded (main
 * thread only, same contract as [SeekSerializer]); [generation] is [PlayerHolder]'s own load
 * counter, so a callback from a load a newer one has already replaced finds nothing armed.
 */
internal class ResumeSeekGate {
    private var armedGeneration: Long? = null
    private var targetMs: Long = 0L
    private var reserved = false
    private var seekIssued = false

    /** Arms the gate for [generation]'s resume to [targetMs]. */
    fun arm(generation: Long, targetMs: Long) {
        armedGeneration = generation
        this.targetMs = targetMs
        reserved = false
        seekIssued = false
    }

    /** Disarms -- a play-from-start or transcode load, which never touches [SeekParameters]. */
    fun reset() {
        armedGeneration = null
        reserved = false
        seekIssued = false
    }

    /** Returns the target to reserve in the serializer exactly once for the armed [generation],
     * at the media-item transition, before any user input can be accepted. */
    fun reserve(generation: Long): Long? {
        if (armedGeneration != generation || reserved) return null
        reserved = true
        return targetMs
    }

    /** Returns the target to seek to exactly once for the armed [generation] -- `null` for a
     * stale/mismatched generation, an unarmed gate, or a repeat call. */
    fun onTracksChanged(generation: Long): Long? {
        if (armedGeneration != generation || seekIssued) return null
        seekIssued = true
        return targetMs
    }

    /** Returns `true` exactly once, on the first landing signal (firstFrame or STATE_READY, per
     * [PlayerHolder]) for the armed [generation] -- the caller restores normal seek semantics
     * then. Consumes the arm, so a later landing signal for the same load is a no-op. */
    fun onLandingSignal(generation: Long): Boolean {
        if (armedGeneration != generation) return false
        armedGeneration = null
        reserved = false
        seekIssued = false
        return true
    }
}

/**
 * docs/18 §5.2 ordering, pure so the interleaving with user skips is unit-tested: the resume
 * target takes [seeks]' in-flight slot at the media-item transition (before playback input can
 * be accepted), so a skip pressed while the player is still preparing is held behind it and
 * replayed on top once it lands -- never overwritten by a resume seek that arrives later.
 */
internal fun reserveResumeSeek(gate: ResumeSeekGate, seeks: SeekSerializer, generation: Long, nowMs: Long): Long? {
    val target = gate.reserve(generation) ?: return null
    return when (seeks.request(target, nowMs)) {
        is SeekSerializer.Decision.Issue -> target
        is SeekSerializer.Decision.Hold -> null
    }
}

/** The resume target to hand to the player once tracks are known, or `null` when a user seek
 * already owns the slot: their exact position wins and the keyframe snap is dropped. An
 * unseekable file drops it and frees the slot, since that seekTo would only restart from 0:00
 * (docs/12 §9). */
internal fun resumeSeekToIssue(gate: ResumeSeekGate, seeks: SeekSerializer, generation: Long, seekability: Seekability): Long? {
    val target = gate.onTracksChanged(generation) ?: return null
    if (seekability == Seekability.UNSEEKABLE) {
        seeks.reset()
        return null
    }
    return target.takeIf { seeks.inFlightTargetMs == it }
}

/**
 * Application-scoped holder for the single long-lived [ExoPlayer] instance (one player per
 * process, avoiding leaked codecs from a per-Activity player). [PlaybackActivity] is just
 * a surface + OSD: it [attach]es/[detach]es its [PlayerView] here but never owns the player's
 * lifetime. Constructed with the application [Context], never an Activity context, so it can never
 * leak one.
 *
 * `release()` is deliberately never called: the instance lives for the process's lifetime,
 * reclaimed by the OS on process death. Every session must instead [stopAndClear] on exit.
 */
class PlayerHolder(
    private val appContext: Context,
    /** [tv.jellybeam.AppGraph]'s single shared client, used by [buildPlayer]. */
    private val httpClient: OkHttpClient,
) : PlaybackPlayer {

    private val loadErrorHandlingPolicy = NetworkAwareLoadErrorHandlingPolicy()

    private val player: ExoPlayer by lazy { buildPlayer() }

    /** Touches [player] to force the lazy `ExoPlayer` construction ahead of first playback. */
    fun prewarm() {
        player
    }

    /** Exposes the shared [ExoPlayer] as a plain [Player] for [MediaSessionHolder] to wrap;
     * read-only, no caller may swap or dispose it. */
    val mediaSessionPlayer: Player get() = player

    /** Backing field for [currentTracks], updated by a dedicated listener living for the shared
     * player's whole lifetime, kept separate from any per-session listener. */
    @Volatile
    private var trackSnapshot: Tracks? = null

    override val currentTracks: Tracks? get() = trackSnapshot

    /** Backing field for [bandwidthBytesPerSecond]; same listener shape as [trackSnapshot]. */
    @Volatile
    private var lastBandwidthBitsPerSecond: Long = 0L

    private val droppedVideoFrames = AtomicLong(0L)

    override var onVideoDecoderInitialized: ((String) -> Unit)? = null

    /** Serializes [seekBy]'s `player.seekTo` calls; main-thread only like every field here that
     * Media3's callbacks touch without synchronization. */
    private val seeks = SeekSerializer()

    /** docs/18 §5.2: bumped once per [load] call; the one identity a stale listener callback from
     * a replaced item can be checked against. Main-thread only, like every field here. */
    private var loadGeneration = 0L

    private val resumeSeekGate = ResumeSeekGate()

    /** Set by [load] when this generation is a resume with a real seek to sequence; independent
     * of [resumeSeekGate]'s one-shot state so the `firstFrame.resume` perf mark still fires
     * whichever of firstFrame/STATE_READY consumes the gate first. Cleared once logged. */
    private var currentLoadIsResume = false

    // docs/10 "Playback-start timeline": one-shot per-load marks.
    private var tracksMarked = false
    private var readyMarked = false
    private var surfaceReadyMarked = false
    private var loadsMarked = false
    private var perLoadRequestCount = 0
    private var perLoadBytes = 0L

    private val seekTimeoutHandler = Handler(Looper.getMainLooper())

    /** One timeout at a time by construction: every post first cancels any pending one. */
    private val seekTimeoutRunnable = Runnable { checkSeekTimeout() }

    /** Media3's normal allocator config, retained so the info overlay can read allocation. */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private val playbackAllocator = DefaultAllocator(
        /* trimOnReset = */ true,
        C.DEFAULT_BUFFER_SEGMENT_SIZE,
    )

    /**
     * Backing value for [JellybeamRenderersFactory]'s `tolerateMislabeledLevels` supplier. [player] is
     * built once for the process lifetime, so [buildPlayer] hands a supplier lambda reading this
     * field; [load] updates it every call, so a toggle change takes effect on the next playback
     * without rebuilding the shared player.
     */
    @Volatile
    private var tolerateMislabeledLevels: Boolean = true

    @Volatile
    private var audioDecoderPreferences: AudioDecoderPreferences = AudioDecoderPreferences()

    // DefaultRenderersFactory is still @UnstableApi in 1.9.0; scoped to just this builder.
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun buildPlayer(): ExoPlayer {
        // EXTENSION_RENDERER_MODE_ON lets the ffmpeg audio decoders load via Media3's extension
        // convention; platform MediaCodec decoders are tried first except where a Jellybeam advanced
        // switch makes PreferenceAwareMediaCodecAudioRenderer stand aside. JellybeamRenderersFactory
        // swaps in LevelTolerantMediaCodecVideoRenderer so an over-declared HEVC/AVC level doesn't
        // push decoder selection to software and lose HDR signaling.
        val renderersFactory = JellybeamRenderersFactory(
            appContext,
            tolerateMislabeledLevels = { tolerateMislabeledLevels },
            audioDecoderPreferences = { audioDecoderPreferences },
        ).apply {
            setEnableDecoderFallback(true)
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        }

        // OkHttp (connection pooling/keep-alive) in place of Media3's default HttpURLConnection
        // source; [httpClient] owns the timeout tuning. [pendingCall] lets [CancelingDataSource]
        // find and cancel an abandoned read's Call on close instead of paying OkHttp's ~100ms
        // drain -- every reopen (trailer seek, resume seek, out-of-buffer skip) paid it.
        val pendingCall = ThreadLocal<Call?>()
        val httpDataSourceFactory = OkHttpDataSource.Factory(TrackingCallFactory(httpClient, pendingCall))
        val mediaSourceFactory = DefaultMediaSourceFactory(appContext)
            .setDataSourceFactory(CancelingDataSourceFactory(httpDataSourceFactory, pendingCall))
            // Media3's DefaultLoadErrorHandlingPolicy gives up after ~3 fast retries, not enough to
            // ride out a 10-20s Wi-Fi stall; LoadRetryPolicy's longer runway replaces it
            .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)

        // Media3's default bufferForPlaybackMs=2500 makes every Direct Play wait 2.5s regardless of
        // LAN speed; lowering it (and bufferForPlaybackAfterRebufferMs) cuts that. minBufferMs/
        // maxBufferMs are widened to ride out an observed Wi-Fi stall (15s floor, 60s ceiling).
        // setTargetBufferBytes is a soft heap target: time is prioritized over it.
        val loadControl = DefaultLoadControl.Builder()
            .setAllocator(playbackAllocator)
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 60_000,
                // Sourced from BufferingInfo's constants so the load control and the OSD's percent
                // toward resume can never silently drift apart.
                /* bufferForPlaybackMs = */ BufferingInfo.INITIAL_RESUME_THRESHOLD_MS.toInt(),
                /* bufferForPlaybackAfterRebufferMs = */ BufferingInfo.REBUFFER_RESUME_THRESHOLD_MS.toInt(),
            )
            .setTargetBufferBytes(64 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        return ExoPlayer.Builder(appContext)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setPauseAtEndOfMediaItems(true)
            .build()
            .apply {
                addListener(object : Player.Listener {
                    override fun onTracksChanged(tracks: Tracks) {
                        trackSnapshot = tracks
                        if (!tracksMarked) {
                            tracksMarked = true
                            PerfLog.markPlayback("exo.tracks")
                        }
                        // docs/18 §5.2: the period is prepared once tracks are known -- issue the
                        // resume seek reserved at the transition; a skip held behind it replays on
                        // landing (see reserveResumeSeek).
                        if (tracks.groups.isNotEmpty()) {
                            resumeSeekToIssue(resumeSeekGate, seeks, loadGeneration, seekability)?.let { targetMs ->
                                issue(targetMs)
                                PerfLog.markPlayback("resume.seek")
                            }
                        }
                    }

                    // Seek-landing signals (docs/18 §5): the first of these two to fire is what
                    // "landed" means; both no-op via [SeekSerializer.landed] if nothing's in flight
                    override fun onRenderedFirstFrame() {
                        onSeekLanded("firstFrame")
                        maybeRestoreSeekParameters()
                        if (currentLoadIsResume) {
                            currentLoadIsResume = false
                            PerfLog.markPlayback("firstFrame.resume")
                        }
                        maybeLogLoadCounts()
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            onSeekLanded("ready")
                            maybeRestoreSeekParameters()
                            if (!readyMarked) {
                                readyMarked = true
                                PerfLog.markPlayback("exo.ready")
                            }
                        }
                        // docs/21 §2.1: one line per state change, never per frame.
                        AppGraph.diag.event("player.state") {
                            tag("state", playerStateName(playbackState))
                            ms("posMs", player.currentPosition)
                        }
                    }

                    override fun onSurfaceSizeChanged(width: Int, height: Int) {
                        if (!surfaceReadyMarked && width > 0 && height > 0) {
                            surfaceReadyMarked = true
                            PerfLog.markPlayback("surface.ready")
                        }
                    }

                    // A new item means any seek tracked against the previous one is meaningless.
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        resetSeeks("transition")
                        reserveResumeSeek(resumeSeekGate, seeks, loadGeneration, SystemClock.uptimeMillis())
                            ?.let { Log.i(SEEK_LOG_TAG, "reserve target=$it") }
                    }
                })
                // Buffering pill's live throughput readout; only the rolling estimate is needed.
                addAnalyticsListener(object : AnalyticsListener {
                    override fun onBandwidthEstimate(
                        eventTime: AnalyticsListener.EventTime,
                        totalLoadTimeMs: Int,
                        totalBytesLoaded: Long,
                        bitrateEstimate: Long,
                    ) {
                        lastBandwidthBitsPerSecond = bitrateEstimate
                        perLoadBytes += totalBytesLoaded
                    }

                    override fun onDroppedVideoFrames(
                        eventTime: AnalyticsListener.EventTime,
                        droppedFrames: Int,
                        elapsedMs: Long,
                    ) {
                        droppedVideoFrames.addAndGet(droppedFrames.toLong())
                    }

                    // docs/18 §1's 3rd local-evidence signal. `this@PlayerHolder.` is load-bearing:
                    // unqualified would resolve against this override's own name first
                    override fun onVideoDecoderInitialized(
                        eventTime: AnalyticsListener.EventTime,
                        decoderName: String,
                        initializedTimestampMs: Long,
                        initializationDurationMs: Long,
                    ) {
                        this@PlayerHolder.onVideoDecoderInitialized?.invoke(decoderName)
                        // docs/21 §2.1: the Media3 decoder name is a platform string (e.g.
                        // "c2.qti.hevc.decoder") that already fits tag()'s charset.
                        AppGraph.diag.event("player.decoder") { tag("video", decoderName) }
                        // docs/10 "Playback-start timeline": phase carries initMs since markPlayback
                        // takes no separate field; gated here since the string is built eagerly.
                        if (PerfLog.enabled) PerfLog.markPlayback("decoder.video initMs=$initializationDurationMs")
                    }

                    override fun onAudioDecoderInitialized(
                        eventTime: AnalyticsListener.EventTime,
                        decoderName: String,
                        initializedTimestampMs: Long,
                        initializationDurationMs: Long,
                    ) {
                        AppGraph.diag.event("player.decoder") { tag("audio", decoderName) }
                        if (PerfLog.enabled) PerfLog.markPlayback("decoder.audio initMs=$initializationDurationMs")
                    }

                    // docs/10: one line at first frame counts every load this item started. Bytes
                    // come from onBandwidthEstimate: a progressive load completes only at end of
                    // file, so onLoadCompleted has nothing to tally by the first frame.
                    override fun onLoadStarted(
                        eventTime: AnalyticsListener.EventTime,
                        loadEventInfo: LoadEventInfo,
                        mediaLoadData: MediaLoadData,
                    ) {
                        perLoadRequestCount++
                    }
                })
            }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun load(
        plan: PlaybackPlan,
        tolerateMislabeledLevels: Boolean,
        audioDecoderPreferences: AudioDecoderPreferences,
    ) {
        loadErrorHandlingPolicy.resetForNewPlayback()
        // docs/18 §3.1: this is a process-wide singleton, so a previous item's applyTrackDecision
        // state would otherwise silently carry over; this is what makes SubtitleActionFfi.LEAVE
        // mean "this item's own default", not "whatever the previous item left".
        player.trackSelectionParameters = trackSelectionBaseline(player.trackSelectionParameters)
        this.tolerateMislabeledLevels = tolerateMislabeledLevels
        this.audioDecoderPreferences = audioDecoderPreferences
        // A new item means whatever Tracks the previous one announced is stale.
        trackSnapshot = null
        lastBandwidthBitsPerSecond = 0L
        droppedVideoFrames.set(0L)
        loadGeneration++
        val generation = loadGeneration
        tracksMarked = false
        readyMarked = false
        surfaceReadyMarked = false
        loadsMarked = false
        perLoadRequestCount = 0
        perLoadBytes = 0L
        // MediaSession title: the same breadcrumb formatter the OSD's top bar uses. No artworkUri:
        // PlaybackPlan carries no image URL.
        val breadcrumbTitle = PlaybackBreadcrumb.format(
            strings = AndroidUiStrings(appContext.resources),
            itemType = plan.itemType,
            itemName = plan.itemName,
            seriesName = plan.seriesName,
            parentIndexNumber = plan.parentIndexNumber,
            indexNumber = plan.indexNumber,
        )
        val mediaItemBuilder = MediaItem.Builder()
            .setMediaId(plan.itemId)
            .setUri(plan.url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(breadcrumbTitle).build())
        // docs/18 §3: a Transcode plan's `url` is the server's HLS manifest, not a static file, and
        // Jellyfin's query-string-only URLs defeat Media3's extension-sniffing, so the mime type is
        // set explicitly. A DirectPlay plan is untouched.
        if (plan.playMethod == PlayMethodFfi.TRANSCODE) {
            mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
        }
        val mediaItem = mediaItemBuilder.build()
        val startPositionMs = PlaybackTicks.ticksToMs(plan.startPositionTicks).coerceAtLeast(0L)
        // docs/18 §5.2: a resume's saved position is applied exactly by setMediaItem, decoding
        // through every frame back to the last keyframe; land there instead by biasing the one
        // extra seekTo() this arms in onTracksChanged toward the preceding sync sample. A
        // Transcode (HLS) plan is left untouched -- its own segment boundaries already govern this.
        val isResume = startPositionMs > 0L && plan.playMethod != PlayMethodFfi.TRANSCODE
        currentLoadIsResume = isResume
        if (isResume) {
            player.setSeekParameters(SeekParameters.PREVIOUS_SYNC)
            resumeSeekGate.arm(generation, startPositionMs)
        } else {
            disarmResumeSeek()
        }
        PerfLog.markPlayback("exo.setMediaItem")
        if (startPositionMs > 0L) {
            player.setMediaItem(mediaItem, startPositionMs)
        } else {
            player.setMediaItem(mediaItem)
        }
        PerfLog.markPlayback("exo.prepare")
        player.prepare()
        player.playWhenReady = true
    }

    /** docs/18 §5.2: restores normal seek semantics once the resume seek has landed (or
     * immediately, for a non-resume load); at most once per [loadGeneration]. */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun maybeRestoreSeekParameters() {
        if (resumeSeekGate.onLandingSignal(loadGeneration)) {
            player.setSeekParameters(SeekParameters.DEFAULT)
        }
    }

    /** docs/18 §5.2: every path that ends a resume load's special seek semantics goes through here. */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun disarmResumeSeek() {
        resumeSeekGate.reset()
        player.setSeekParameters(SeekParameters.DEFAULT)
    }

    /** docs/10: one summary line per item, logged once at first frame. */
    private fun maybeLogLoadCounts() {
        if (loadsMarked) return
        loadsMarked = true
        if (PerfLog.enabled) PerfLog.line("perf playback loads count=$perLoadRequestCount bytes=$perLoadBytes")
    }

    override fun hasExhaustedLoadRetryBudget(): Boolean = loadErrorHandlingPolicy.hasExhaustedRetryBudget()

    /** Attaches the shared player to [playerView] -- call from e.g. `AndroidView`'s `factory`. */
    fun attach(playerView: PlayerView) {
        playerView.player = player
    }

    /**
     * Detaches [playerView] from the shared player before it's destroyed, or [PlayerView]'s
     * internal listener leaks a dead View reference. Identity-guarded so detaching a non-active
     * view can't null out someone else's attachment.
     */
    fun detach(playerView: PlayerView) {
        if (playerView.player === player) {
            playerView.player = null
        }
    }

    override fun addListener(listener: Player.Listener) = player.addListener(listener)

    override fun removeListener(listener: Player.Listener) = player.removeListener(listener)

    override fun togglePlayPause() {
        // isPlaying is false during a seek/rebuffer even though the player still intends to play.
        if (player.playWhenReady) player.pause() else player.play()
    }

    override fun pause() {
        player.pause()
    }

    override fun setPlaybackRate(rate: Float) {
        player.setPlaybackSpeed(rate)
    }

    override val isPlaying: Boolean get() = player.isPlaying
    override val playbackState: Int get() = player.playbackState

    // Masked with any held seek target so the OSD never snaps back to the pre-seek position while a
    // request is held -- Media3's currentPosition is still mid-flight toward the previous target.
    override fun currentPositionTicks(): Long =
        PlaybackTicks.msToTicks((seeks.pendingTargetMs ?: player.currentPosition).coerceAtLeast(0L))

    override fun bufferedPositionTicks(): Long = PlaybackTicks.msToTicks(player.bufferedPosition.coerceAtLeast(0L))

    override val playWhenReady: Boolean get() = player.playWhenReady

    /** Reused by [seekability]: Player is read on the main looper only, so one scratch window suffices. */
    private val seekabilityWindow = androidx.media3.common.Timeline.Window()

    override val seekability: Seekability
        get() {
            val timeline = player.currentTimeline
            if (timeline.isEmpty) return Seekability.UNKNOWN
            val window = timeline.getWindow(player.currentMediaItemIndex, seekabilityWindow)
            return when {
                window.isPlaceholder -> Seekability.UNKNOWN
                window.isSeekable -> Seekability.SEEKABLE
                else -> Seekability.UNSEEKABLE
            }
        }

    override fun bandwidthBytesPerSecond(): Long = lastBandwidthBitsPerSecond.coerceAtLeast(0L) / 8L

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun livePlaybackStats(): PlaybackLiveStats {
        val state = when {
            player.playbackState == Player.STATE_ENDED -> PlaybackLiveState.ENDED
            player.playbackState == Player.STATE_IDLE -> PlaybackLiveState.IDLE
            !player.playWhenReady -> PlaybackLiveState.PAUSED
            player.playbackState == Player.STATE_BUFFERING -> PlaybackLiveState.BUFFERING
            player.isPlaying -> PlaybackLiveState.PLAYING
            else -> PlaybackLiveState.PREPARING
        }
        return PlaybackLiveStats(
            bufferedAheadMs = player.totalBufferedDuration.coerceAtLeast(0L),
            allocatedBufferBytes = playbackAllocator.totalBytesAllocated.toLong().coerceAtLeast(0L),
            bandwidthBytesPerSecond = bandwidthBytesPerSecond(),
            state = state,
            droppedFrames = droppedVideoFrames.get(),
        )
    }

    /** `null` before Media3 knows the duration, matching [SeekMath.clampSeekTarget]'s contract. */
    fun durationTicks(): Long? =
        player.duration.takeIf { it != C.TIME_UNSET }?.let { PlaybackTicks.msToTicks(it) }

    /**
     * Routed through [SeekSerializer] (docs/18 §5) so a fast run of D-pad presses never fires two
     * overlapping `seekTo` calls. [fromMs] bases the clamp on any already-held target rather than
     * the stale [Player.currentPosition], or a burst's middle press would be lost.
     */
    override fun seekBy(deltaMs: Long) {
        val fromMs = seeks.pendingTargetMs ?: player.currentPosition
        val target = SeekMath.clampSeekTarget(
            currentMs = fromMs,
            deltaMs = deltaMs,
            durationMs = player.duration.takeIf { it != C.TIME_UNSET },
        )
        val now = SystemClock.uptimeMillis()
        when (val decision = seeks.request(target, now)) {
            is SeekSerializer.Decision.Issue -> issue(decision.targetMs)
            is SeekSerializer.Decision.Hold -> {
                val since = seeks.inFlightSinceMs ?: now
                Log.i(SEEK_LOG_TAG, "hold target=${decision.targetMs} inflight=${seeks.inFlightTargetMs} age=${now - since}")
            }
        }
    }

    /** Issues [target] against the real player -- the only place [ExoPlayer.seekTo] is called for a
     * dpad skip. */
    private fun issue(target: Long, held: Boolean = false) {
        val from = player.currentPosition
        val edge = player.bufferedPosition
        player.seekTo(target)
        scheduleSeekTimeout()
        Log.i(SEEK_LOG_TAG, "issue target=$target from=$from edge=$edge ahead=${edge - from} held=$held")
    }

    /** Common body for both landing signals. */
    private fun onSeekLanded(via: String) {
        val now = SystemClock.uptimeMillis()
        val since = seeks.inFlightSinceMs
        val pending = seeks.pendingTargetMs
        val held = seeks.landed(now)
        if (since != null) {
            Log.i(
                SEEK_LOG_TAG,
                "land via=$via after=${now - since} pos=${player.currentPosition} edge=${player.bufferedPosition} pending=${pending ?: "none"}",
            )
        }
        if (held != null) {
            issue(held, held = true)
        } else {
            cancelSeekTimeout()
        }
    }

    /** [seekTimeoutRunnable]'s body -- the landing timeout doubles as recovery. */
    private fun checkSeekTimeout() {
        val now = SystemClock.uptimeMillis()
        if (!seeks.timeoutDue(now)) return
        val inFlight = seeks.inFlightTargetMs
        val since = seeks.inFlightSinceMs ?: now
        val pending = seeks.pendingTargetMs
        Log.w(SEEK_LOG_TAG, "stall inflight=$inFlight after=${now - since} pending=${pending ?: "none"}")
        // A held target fires as the recovery seek; otherwise the in-flight state clears.
        seeks.landed(now)?.let { issue(it, held = true) }
    }

    private fun scheduleSeekTimeout() {
        seekTimeoutHandler.removeCallbacks(seekTimeoutRunnable)
        seekTimeoutHandler.postDelayed(seekTimeoutRunnable, SeekSerializer.LANDING_TIMEOUT_MS)
    }

    private fun cancelSeekTimeout() {
        seekTimeoutHandler.removeCallbacks(seekTimeoutRunnable)
    }

    private fun resetSeeks(reason: String) {
        seeks.reset()
        cancelSeekTimeout()
        Log.i(SEEK_LOG_TAG, "reset reason=$reason")
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun stopAndClear() {
        resetSeeks("stop")
        // docs/18 §5.2: an armed-but-never-landed resume must not seek/restore a future load.
        disarmResumeSeek()
        player.stop()
        player.clearMediaItems()
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun retryAfterError(positionTicks: Long, playing: Boolean) {
        resetSeeks("retry")
        // docs/18 §5.2: this is its own recovery seek; an armed resume from before the error must
        // not also fire once tracks change again.
        disarmResumeSeek()
        // Direct, not routed through [seekBy]: this is itself the recovery path, not a dpad skip.
        val positionMs = PlaybackTicks.ticksToMs(positionTicks).coerceAtLeast(0L)
        player.seekTo(positionMs)
        player.playWhenReady = playing
        player.prepare()
    }

    /** Applies [decision] against [tracks]. Audio and subtitle halves are independent; an
     * out-of-range/missing override on one never prevents the other from applying.
     */
    override fun applyTrackDecision(decision: TrackDecisionFfi, tracks: Tracks) {
        player.trackSelectionParameters = withTrackDecision(player.trackSelectionParameters, decision, tracks)
    }
}
