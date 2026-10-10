package tv.jellybeam.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
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
import androidx.media3.common.VideoSize
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import tv.jellybeam.player.ass.AssAwareSubtitleParserFactory
import tv.jellybeam.player.ass.AssExtractorsFactory
import tv.jellybeam.player.ass.AttachmentsBlock
import tv.jellybeam.player.ass.ItemBoundMediaSourceFactory
import tv.jellybeam.player.ass.ItemScopedFontSink
import tv.jellybeam.player.ass.RangeReader
import tv.jellybeam.player.ass.assVideoColour
import tv.jellybeam.player.ass.SubtitleCues
import tv.jellybeam.player.ass.rangeHeader
import tv.jellybeam.player.ass.servedRangeLength
import tv.jellybeam.player.ass.spanningSamples
import tv.jellybeam.player.ass.fontsToFetch
import tv.jellybeam.player.ass.attachedFonts
import tv.jellybeam.player.ass.ASS_CHOICE_GRACE_MS
import tv.jellybeam.player.ass.ASS_FONT_WAIT_MS
import tv.jellybeam.player.ass.assFramesHeld
import tv.jellybeam.player.ass.AssFontFetch
import tv.jellybeam.player.ass.assFontFetchTransition
import tv.jellybeam.player.ass.assFontTimers
import tv.jellybeam.player.ass.embeddedRawSsaAfter
import tv.jellybeam.player.ass.AssOverlayView
import tv.jellybeam.player.ass.AssTextRenderer
import tv.jellybeam.player.ass.PlainSsaRenderer
import tv.jellybeam.player.ass.shouldSendPosition
import tv.jellybeam.player.ass.assLineLiftPercent
import tv.jellybeam.player.ass.isRawSsa
import uniffi.jellybeam_core.AssOverlay
import uniffi.jellybeam_core.AssSample
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
private const val ASS_PERF_PERIOD_MS = 2_000L

/** How often a styled sidecar's overlay is sent the position; [shouldSendPosition] thins it further. */
private const val ASS_SIDECAR_CLOCK_MS = 40L

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
        fullAssStyling: Boolean = false,
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

    /** docs/18 §3.2: where ASS sidecar [index] loads styled; null with full styling off, so it is read as plain cues. */
    fun assSidecarTarget(index: Int): AssSidecarTarget? = null

    /** Main thread: draws the loaded [target] on the overlay in place of any earlier one; null hides it. */
    fun showAssSidecar(target: AssSidecarTarget?) = Unit
}

/** A styled ASS sidecar's overlay track: [key] within overlay item [item] (docs/18 §3.2). */
data class AssSidecarTarget(val overlay: AssOverlay, val key: String, val item: ULong)

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

    /** Settings > Subtitles "Full styling for styled subtitles": read by the extractor and renderers at load. */
    @Volatile
    private var fullAssStyling: Boolean = false

    /** The Rust ASS overlay (one render thread, idle until a styled track shows); null if it failed to start. */
    private var assOverlay: AssOverlay? = null

    private var assOverlayView: AssOverlayView? = null

    /** Guards the item generation bump with the writes that depend on it; declared before [assFonts], which captures it. */
    private val assItemLock = Any()

    /** Delivers an extractor's findings only into the item whose media source it came from ([ItemScopedFontSink]). */
    private val assFonts = ItemScopedFontSink(
        generation = { assItemGeneration.get() },
        lock = assItemLock,
        wants = { fullAssStyling && assOverlay != null },
        onAttachments = { block ->
            synchronized(assAttachments) {
                if (assAttachments.none { it.offset == block.offset }) assAttachments += block
            }
            seekTimeoutHandler.post { maybeFetchAssFonts() }
        },
        onCues = { cues, timecodeScaleNs ->
            assCues = cues to timecodeScaleNs
            seekTimeoutHandler.post { maybeFetchSpanningLines() }
        },
    )

    /** This item's SSA Cues and time scale, from the extractor's loader thread (docs/13). */
    @Volatile
    private var assCues: Pair<SubtitleCues, Long>? = null

    /** Main thread: the newest (track, media time) whose earlier-starting lines are still to fetch. */
    private var assLinesWanted: Pair<String, Long>? = null

    /** Each spanning-lines fetch takes a number; a newer seek or item stops the older one between reads. */
    private val assLinesRequest = AtomicLong(0L)

    /** This item's attachments blocks, recorded by the extractor's loader thread. */
    private val assAttachments = mutableListOf<AttachmentsBlock>()

    /** Main thread: this item's font fetch; written only by [setAssFontFetch], which also holds the overlay's frames (docs/13). */
    private var assFontFetch = AssFontFetch.IDLE

    /** Each font fetch takes a number; one stopped because its track stopped showing ends between reads. */
    private val assFontsRequest = AtomicLong(0L)

    /** This item's fonts already handed to the overlay, by offset, so a restarted fetch skips them. */
    private val assFontsFetched: MutableSet<Long> = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    /** Main thread: [ASS_FONT_WAIT_MS] passed, so the overlay shows with whatever fonts arrived; per item. */
    private var assFontWaitExpired = false

    private val assFontWaitRunnable = Runnable {
        assFontWaitExpired = true
        applyAssOverlayState()
    }

    /** Main thread: this item's subtitle choice is known, so fonts may be fetched; Media3's default pick before it isn't one. */
    private var assChoiceSettled = false

    /** Main thread: [assChoiceGraceRunnable] has been armed for this item. */
    private var assGraceArmed = false

    /** A choice that never arrives (failed resolve, left default, sidecar pick) settles after [ASS_CHOICE_GRACE_MS]. */
    private val assChoiceGraceRunnable = Runnable {
        assChoiceSettled = true
        maybeFetchAssFonts()
    }

    /**
     * Bumps the item generation and resets the overlay under [assItemLock], which the font fetch
     * also holds across its check-then-send, so an old item's font can't be queued after the reset.
     * Every per-item ASS field resets here, after the bump, and extractor sinks bound at an earlier setMediaItem drop writes from before it ([ItemScopedFontSink]), so no stale thread can refill one.
     */
    private fun beginAssItem() {
        // Main thread: the item's sidecar script goes with it, so nothing feeds it a clock.
        assSidecar = null
        seekTimeoutHandler.removeCallbacks(assSidecarClock)
        synchronized(assItemLock) {
            assItemGeneration.incrementAndGet()
            assFontCall?.cancel()
            assLinesRequest.incrementAndGet()
            assLinesCall?.cancel()
            assOverlay?.beginItem()
        }
        synchronized(assAttachments) { assAttachments.clear() }
        assFontsFetched.clear()
        assCues = null
        assLinesWanted = null
        assTrackShowing = false
        assChoiceSettled = false
        assGraceArmed = false
        assFontWaitExpired = false
        seekTimeoutHandler.removeCallbacks(assChoiceGraceRunnable)
        setAssFontFetch(AssFontFetch.IDLE)
    }

    /** The font range request in flight, cancelled with its item so zapping doesn't keep downloading. */
    @Volatile
    private var assFontCall: okhttp3.Call? = null

    /** The spanning-lines range request in flight, cancelled with its item or by a newer seek. */
    @Volatile
    private var assLinesCall: okhttp3.Call? = null

    /** [load]'s generation and URL, for a font fetch that must not land on a later item. */
    private val assItemGeneration = AtomicLong(0L)
    @Volatile
    private var assItemUrl: String? = null

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
            ssaRenderers = { output, looper ->
                val outputHandler = Handler(looper)
                listOf(
                    AssTextRenderer({ assOverlay }, { fullAssStyling }) { key, mediaUs ->
                        seekTimeoutHandler.post {
                            assLinesWanted = key to mediaUs
                            maybeFetchSpanningLines()
                        }
                    },
                    PlainSsaRenderer(output) { outputHandler.post(it) },
                )
            },
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
        assOverlay = runCatching { AssOverlay() }
            .onFailure { Log.w(SEEK_LOG_TAG, "ASS overlay unavailable", it) }
            .getOrNull()
        val cancelingDataSourceFactory = CancelingDataSourceFactory(httpDataSourceFactory, pendingCall)
        // One factory per item, so each item's extractors bind its generation at setMediaItem (docs/13).
        val mediaSourceFactory = ItemBoundMediaSourceFactory(assFonts) { sink ->
            DefaultMediaSourceFactory(appContext, AssExtractorsFactory(sink))
                .setSubtitleParserFactory(AssAwareSubtitleParserFactory())
                .setDataSourceFactory(cancelingDataSourceFactory)
                // Media3's DefaultLoadErrorHandlingPolicy gives up after ~3 fast retries, not enough to
                // ride out a 10-20s Wi-Fi stall; LoadRetryPolicy's longer runway replaces it
                .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        }

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
                        onAssTracks(tracks)
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

                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        if (videoSize.width > 0 && videoSize.height > 0) {
                            assOverlay?.setVideoSize(videoSize.width, videoSize.height)
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
                        val total = droppedVideoFrames.addAndGet(droppedFrames.toLong())
                        // docs/10: dropped frames for every playback, styled overlay or not.
                        if (PerfLog.enabled) PerfLog.line("perf playback dropped=$droppedFrames total=$total")
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
        fullAssStyling: Boolean,
    ) {
        loadErrorHandlingPolicy.resetForNewPlayback()
        this.fullAssStyling = fullAssStyling
        // A new item starts with no styled track, fonts or overlay frame from the last one.
        beginAssItem()
        assItemUrl = plan.url
        assOverlayView?.visibility = assOverlayVisibility()
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
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun attach(playerView: PlayerView) {
        playerView.player = player
        val overlay = assOverlay ?: return
        val frame = playerView.findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame) ?: return
        if (assOverlayView?.parent === frame) return
        (assOverlayView?.parent as? ViewGroup)?.removeView(assOverlayView)
        assOverlayView = AssOverlayView(playerView.context, overlay).also { view ->
            view.visibility = assOverlayVisibility()
            // Directly above the video, below Media3's SubtitleView: a SurfaceView clears the window
            // under it, so anywhere later in the frame it erases PGS and every other Media3 cue.
            val index = frame.indexOfChild(playerView.videoSurfaceView) + 1
            frame.addView(view, index, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    /**
     * Detaches [playerView] from the shared player before it's destroyed, or [PlayerView]'s
     * internal listener leaks a dead View reference. Identity-guarded so detaching a non-active
     * view can't null out someone else's attachment.
     */
    fun detach(playerView: PlayerView) {
        val frame = playerView.findViewById<ViewGroup>(androidx.media3.ui.R.id.exo_content_frame)
        assOverlayView?.takeIf { it.parent === frame }?.let { view ->
            frame.removeView(view)
            assOverlayView = null
        }
        if (playerView.player === player) {
            playerView.player = null
        }
    }

    /** Whether the Rust overlay draws a chosen track: raw SSA with full styling on (else PlainSsaRenderer has it), or a sidecar. */
    private var assTrackShowing = false

    /** Main thread: the styled sidecar on the overlay (docs/18 §3.2); [assSidecarClock] feeds it the position. */
    private var assSidecar: AssSidecarTarget? = null
    private var assSidecarSentUs = Long.MIN_VALUE
    private var assSidecarSentPlaying = false

    /** No renderer reads a sidecar, so its clock comes from the player, as [AssTextRenderer] sends it. */
    private val assSidecarClock: Runnable = object : Runnable {
        override fun run() {
            val overlay = assOverlay ?: return
            if (assSidecar == null) return
            val us = player.currentPosition * 1000
            val playing = player.isPlaying
            if (shouldSendPosition(assSidecarSentUs, assSidecarSentPlaying, us, playing)) {
                overlay.setPosition(us, playing)
                assSidecarSentUs = us
                assSidecarSentPlaying = playing
            }
            seekTimeoutHandler.postDelayed(this, ASS_SIDECAR_CLOCK_MS)
        }
    }

    override fun assSidecarTarget(index: Int): AssSidecarTarget? {
        val overlay = assOverlay?.takeIf { fullAssStyling } ?: return null
        return AssSidecarTarget(overlay, "sidecar:$index", overlay.item())
    }

    override fun showAssSidecar(target: AssSidecarTarget?) {
        val overlay = assOverlay ?: return
        // A target from an earlier item names a script the overlay already freed.
        val next = target?.takeIf { it.overlay === overlay && it.item == overlay.item() }
        if (next == assSidecar) return
        assSidecar?.let { overlay.unselect(it.key) }
        assSidecar = next
        seekTimeoutHandler.removeCallbacks(assSidecarClock)
        if (next != null) {
            overlay.select(next.key)
            assSidecarSentUs = Long.MIN_VALUE
            assSidecarClock.run()
        }
        onAssTracks(player.currentTracks)
    }

    /** Main thread: fetches fonts once a styled track is chosen, and shows the overlay only while one is. */
    private fun onAssTracks(tracks: Tracks) {
        if (assOverlay == null) return
        tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }
            ?.let { group -> (0 until group.length).firstOrNull(group::isTrackSelected)?.let(group::getTrackFormat) }
            ?.let(::assVideoColour)
            ?.let { assOverlay?.setVideoColour(it.matrix, it.fullRange, it.hdr, it.height) }
        refreshAssState(embeddedRawSsaSelected(tracks))
    }

    private fun embeddedRawSsaSelected(tracks: Tracks): Boolean = tracks.groups.any { group ->
        group.type == C.TRACK_TYPE_TEXT &&
            (0 until group.length).any { i -> group.isTrackSelected(i) && isRawSsa(group.getTrackFormat(i)) }
    }

    /** Main thread: [embedded] says whether a raw SSA track is the embedded one playing, now or once a decision applies. */
    private fun refreshAssState(embedded: Boolean) {
        if (assOverlay == null) return
        val showing = fullAssStyling && (embedded || assSidecar != null)
        assTrackShowing = showing
        maybeFetchAssFonts()
        maybeFetchSpanningLines()
        seekTimeoutHandler.removeCallbacks(assPerfRunnable)
        if (showing && PerfLog.enabled) seekTimeoutHandler.postDelayed(assPerfRunnable, ASS_PERF_PERIOD_MS)
    }

    /**
     * The overlay view is shown for the whole item whenever full styling is on: a SurfaceView turned
     * visible mid-playback gets no surface until something else redraws, so choosing or leaving a
     * styled track, and the font wait, blank it in Rust instead (`select`, `holdFrames`).
     */
    private fun assOverlayVisibility(): Int = if (fullAssStyling) View.VISIBLE else View.GONE

    /** Applies the font wait to the overlay's frames (docs/09). */
    private fun applyAssOverlayState() {
        assOverlay?.holdFrames(assFramesHeld(assFontFetch, assFontWaitExpired))
    }

    /**
     * Main thread: once a styled track shows, its choice is settled and the extractor has jumped over
     * the file's attachments, finds their fonts with a short read per attachment and range-reads them
     * on a background thread while playback runs, so no font byte is read before the first frame
     * (docs/13). It stops once no styled track shows, and starts again when one does
     * ([assFontFetchTransition]).
     */
    private fun maybeFetchAssFonts() {
        val overlay = assOverlay ?: return
        val blocks = synchronized(assAttachments) { assAttachments.toList() }
        val url = assItemUrl
        val transition = assFontFetchTransition(assFontFetch, assTrackShowing, blocks.isNotEmpty(), assChoiceSettled, hasUrl = url != null)
        // Every real transition changes the state, so an unchanged one is a no-op.
        if (transition.next == assFontFetch) return
        if (transition.endRequest) {
            // Between reads, its request in flight cancelled; a stop from the wait has neither.
            assFontsRequest.incrementAndGet()
            assFontCall?.cancel()
        }
        setAssFontFetch(transition.next)
        if (!transition.startThread || url == null) return
        val generation = assItemGeneration.get()
        val request = assFontsRequest.incrementAndGet()
        val current = { assItemGeneration.get() == generation && assFontsRequest.get() == request }
        kotlin.concurrent.thread(name = "ass-font-fetch", isDaemon = true) {
            try {
                val limits = overlay.fontLimits()
                val reader = RangeReader { offset, length ->
                    fetchRange(url, offset, length, exact = false, { registerFontCall(it, current) }, current)
                }
                val locations = blocks
                    .flatMap { attachedFonts(it, reader, limits.maxFontBytes.toLong(), current) }
                    .distinctBy { it.offset }
                for (location in fontsToFetch(locations, limits.totalBytes.toLong())) {
                    if (!current()) return@thread
                    if (location.offset in assFontsFetched) continue
                    val bytes = fetchRange(url, location.offset, location.size, exact = true, { registerFontCall(it, current) }, current)
                    if (!current()) break
                    if (bytes == null) {
                        // Attachments are independent ranges: one bad or failed font doesn't cost the rest.
                        Log.w(SEEK_LOG_TAG, "ASS font fetch failed for ${location.name}")
                        continue
                    }
                    synchronized(assItemLock) {
                        if (current() && location.offset !in assFontsFetched) {
                            overlay.addFont(location.name, bytes)
                            assFontsFetched += location.offset
                        }
                    }
                }
            } catch (t: Throwable) {
                // Class only: a message can carry the server's URL.
                Log.w(SEEK_LOG_TAG, "ASS font fetch ended: ${t.javaClass.simpleName}")
            } finally {
                // Every exit ends the fetch, so the hold can't outlive a thread that died.
                seekTimeoutHandler.post { if (current()) setAssFontFetch(AssFontFetch.DONE) }
            }
        }
    }

    /**
     * The only writer of [assFontFetch]: sets the font wait and choice grace timers and applies the
     * overlay's hold, so every transition holds or releases the frames the same way (docs/13).
     */
    private fun setAssFontFetch(next: AssFontFetch) {
        val timers = assFontTimers(assFontFetch, next, assFontWaitExpired, assGraceArmed)
        assFontFetch = next
        if (timers.cancelWait) seekTimeoutHandler.removeCallbacks(assFontWaitRunnable)
        if (timers.armWait) seekTimeoutHandler.postDelayed(assFontWaitRunnable, ASS_FONT_WAIT_MS)
        if (timers.armGrace) {
            assGraceArmed = true
            seekTimeoutHandler.postDelayed(assChoiceGraceRunnable, ASS_CHOICE_GRACE_MS)
        }
        applyAssOverlayState()
    }

    /**
     * Records the font request's call for whoever cancels it, unless the fetch is no longer current. Under
     * [assItemLock] so a stale thread can't overwrite a newer fetch's call; [fetchRange]'s recheck cancels
     * a call this skips.
     */
    private fun registerFontCall(call: okhttp3.Call, current: () -> Boolean) {
        synchronized(assItemLock) { if (current()) assFontCall = call }
    }

    /**
     * Up to [size] bytes of the item at [offset] (exactly [size] when [exact]), or null. It checks the
     * range served and reads at most one byte past it, so a 206 with a larger body is never buffered.
     * The call is handed to [register] so whoever ends its use (the next item, a newer seek) can cancel it.
     */
    private fun fetchRange(
        url: String,
        offset: Long,
        size: Int,
        exact: Boolean,
        register: (okhttp3.Call) -> Unit,
        stillWanted: () -> Boolean,
    ): ByteArray? = runCatching {
        val request = okhttp3.Request.Builder().url(url).header("Range", rangeHeader(offset, size)).build()
        val call = httpClient.newCall(request).also(register)
        // The canceller moves the state on, then cancels the registered call; checking after
        // registering means one of the two always sees the other, and a call [register] skipped as stale dies here.
        if (!stillWanted()) call.cancel()
        call.execute().use { r ->
            val body = r.body
            val served = servedRangeLength(r.header("Content-Range"), offset, size)
            if (r.code != 206 || body == null || served == null || exact && served != size) return@use null
            val source = body.source()
            if (source.request(served + 1L)) return@use null
            source.readByteArray().takeIf { it.size == served }
        }
    }.getOrNull()

    /**
     * Main thread: once a styled track shows, reads the lines that began before the newest start, seek
     * or track switch and still show (docs/13), from the Cues; a newer one supersedes it.
     */
    private fun maybeFetchSpanningLines() {
        val overlay = assOverlay ?: return
        if (!assTrackShowing) return
        val (key, mediaUs) = assLinesWanted ?: return
        val (cues, scaleNs) = assCues ?: return
        val url = assItemUrl ?: return
        val track = key.toIntOrNull() ?: return
        assLinesWanted = null
        val generation = assItemGeneration.get()
        val request = assLinesRequest.incrementAndGet()
        assLinesCall?.cancel()
        val current = { assItemGeneration.get() == generation && assLinesRequest.get() == request }
        kotlin.concurrent.thread(name = "ass-lines-fetch", isDaemon = true) {
            // The scan walks every cue, up to MAX_SUBTITLE_CUES, so it stays off the main thread.
            val wanted = cues.spanning(track, mediaUs)
            val reader = RangeReader { offset, length ->
                fetchRange(url, offset, length, exact = false, { assLinesCall = it }, current)
            }
            val samples = spanningSamples(wanted, scaleNs, reader, current)
            synchronized(assItemLock) {
                if (current()) overlay.addSamples(key, samples.map { (timeUs, bytes) -> AssSample(timeUs, bytes) })
            }
        }
    }

    /** docs/10: one gated line every [ASS_PERF_PERIOD_MS] while a styled track shows. */
    private val assPerfRunnable: Runnable = object : Runnable {
        override fun run() {
            val s = assOverlay?.stats() ?: return
            PerfLog.line(
                "perf ass frames=${s.framesDrawn} lastMs=${"%.1f".format(s.lastFrameMs)} " +
                    "p90Ms=${"%.1f".format(s.windowP90Ms)} quality=${s.quality} " +
                    "size=${s.renderWidth}x${s.renderHeight} fontBytes=${s.fontBytes} " +
                    "eventsSkipped=${s.eventsSkipped} refused=${s.framesRefused} " +
                    "inputRefused=${s.inputRefusals} renderBytes=${s.renderMemoryBytes} " +
                    "renderPeak=${s.renderPeakBytes} maxWork=${s.maxFrameWork} " +
                    "droppedVideo=${droppedVideoFrames.get()}",
            )
            if (assTrackShowing) seekTimeoutHandler.postDelayed(this, ASS_PERF_PERIOD_MS)
        }
    }

    private var assLineLift = 0.0

    /** docs/12 §16: raises bottom ASS dialogue over the OSD, as plain subtitles are; sent only on change. */
    fun setAssOsdVisible(osdVisible: Boolean) {
        val lift = assLineLiftPercent(osdVisible)
        if (lift == assLineLift) return
        assLineLift = lift
        assOverlay?.setLineLift(lift)
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
        // A font fetch or wait still running must not land fonts or re-show the overlay after stop.
        // The overlay view stays visible but blank: hiding it would make a retry in this activity
        // show it again mid-session, which leaves it without a surface; detach() removes it on exit.
        beginAssItem()
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
        val params = withTrackDecision(player.trackSelectionParameters, decision, tracks)
        player.trackSelectionParameters = params
        // Every decision path lands here, so the item's subtitle choice is known: fonts may fetch for the
        // track it leaves showing. Media3 reports that track later, and not at all when it equals the default.
        assChoiceSettled = true
        refreshAssState(embeddedRawSsaAfter(params, embeddedRawSsaSelected(player.currentTracks)))
    }
}
