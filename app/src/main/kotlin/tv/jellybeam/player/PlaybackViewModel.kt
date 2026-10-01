package tv.jellybeam.player

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import tv.jellybeam.AppGraph
import tv.jellybeam.data.CoreGateway
import tv.jellybeam.data.displayMessage
import tv.jellybeam.perf.PerfLog
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import uniffi.jellybeam_core.Card
import uniffi.jellybeam_core.ChapterInfoFfi
import uniffi.jellybeam_core.CoreException
import uniffi.jellybeam_core.MediaSegment
import uniffi.jellybeam_core.MediaSegmentKind
import uniffi.jellybeam_core.OsdDetailSetting
import uniffi.jellybeam_core.PlaybackOsdDetail
import uniffi.jellybeam_core.PlaybackPlan
import uniffi.jellybeam_core.PlayMethodFfi
import uniffi.jellybeam_core.SegmentAction
import uniffi.jellybeam_core.StillWatchingDecision
import uniffi.jellybeam_core.SubtitleActionFfi
import uniffi.jellybeam_core.TrackDecisionFfi
import uniffi.jellybeam_core.TrackInfo
import uniffi.jellybeam_core.TrackKindFfi
import uniffi.jellybeam_core.GlideDirection
import uniffi.jellybeam_core.SeekPreviewSize
import uniffi.jellybeam_core.TrickplayMetaFfi
import uniffi.jellybeam_core.TrickplayTileFfi

/** How often the position-report ticker runs while playing. */
private const val REPORT_INTERVAL_MS = 1_000L

/** How often the buffering pill refreshes while stalled -- shorter than [REPORT_INTERVAL_MS] since
 * the percent/throughput climbing is the point of watching it.
 */
private const val BUFFERING_TICK_MS = 250L

/**
 * Grace period before a seek-triggered `STATE_BUFFERING` shows the buffering
 * pill, so Media3's routine keyframe-reposition transition (~560ms measured
 * on the reference device for a buffered 10s seek) doesn't read as network
 * slowness. Cold starts and spontaneous stalls remain immediate.
 */
internal const val USER_SEEK_BUFFERING_GRACE_MS = 750L

/** [PlaybackViewModel.enrichmentGate]'s fallback delay, so a stream that never renders a frame
 * (audio-only, an error path) still gets its gated enrichment fetches.
 */
private const val ENRICHMENT_GATE_FALLBACK_MS = 1_500L

/** `BaseItemKind::Episode.to_string()`, per `Card.itemType`/`PlaybackPlan.itemType`'s convention.
 */
private const val ITEM_TYPE_EPISODE = "Episode"

/**
 * Sentinel [TrackChoice.id] for the track picker's synthetic subtitle "Off"
 * row (docs/09 slice 3b) -- never a real [TrackMapping] id (always `>= 0`),
 * so it resolves to `null` rather than a wrong track if mistakenly threaded
 * into [TrackMapping.resolve].
 */
const val TRACK_PICKER_SUBTITLE_OFF_ID: Long = -1L

/**
 * docs/18 §1/§3: message shown once [PlaybackViewModel.maybeFallBackToTranscode]
 * has declined to take over. A DirectPlay [plan] carrying a [PlaybackPlan.serverVerdict]
 * means the server would have transcoded and only Direct Played because CLAUDE.md's
 * Direct Play rule forced it -- names that verdict and the Quality setting that
 * would unlock a transcode; every other case falls back to `"Playback error: <code>"`.
 */
internal fun fatalPlaybackMessage(errorCodeName: String, plan: PlaybackPlan?): String {
    val serverVerdict = plan?.takeIf { it.playMethod == PlayMethodFfi.DIRECT_PLAY }?.serverVerdict
    return if (serverVerdict != null) {
        "This file can't be Direct Played on this TV ($errorCodeName). Server: $serverVerdict. " +
            "Set Quality to Auto in Settings › Playback to let the server transcode it."
    } else {
        "Playback error: $errorCodeName"
    }
}

/**
 * Deliberately excludes position: [PlaybackViewModel.positionTicks] lives on its own `StateFlow`
 * so a per-second tick doesn't invalidate the whole playback screen (video surface included),
 * which collects this state at its composable root. Composables needing live position (the time
 * row, [tv.jellybeam.player.NextUpCard]'s countdown) collect [PlaybackViewModel.positionTicks]
 * directly instead.
 */
data class PlaybackUiState(
    val phase: Phase = Phase.LOADING,
    val itemName: String = "",
    /** The playing item's `BaseItemKind` string (e.g. `"Movie"`, `"Episode"`), from
     * [uniffi.jellybeam_core.PlaybackPlan.itemType] -- feeds [PlaybackBreadcrumb.format]'s
     * movie/episode branch.
     */
    val itemType: String = "",
    /**
     * Top-region breadcrumb fields for [PlaybackBreadcrumb.format] (docs/12)
     * -- always `null` today: `PlaybackPlan` only carries a bare `itemName`,
     * and the full `Card` fields exist only for [nextEpisode], never the
     * loaded item. Wired end-to-end so a future [start] change lights up the
     * full breadcrumb; until then this degrades to [itemName].
     */
    val seriesName: String? = null,
    val parentIndexNumber: Int? = null,
    val indexNumber: Int? = null,
    val durationTicks: Long? = null,
    val isPlaying: Boolean = false,
    val isPaused: Boolean = false,
    /** Credits-aware next-up: non-null while the card should be shown over the OSD. */
    val nextUp: NextUpState? = null,
    /** docs/12 §13's "Still watching?" card -- mutually exclusive with [nextUp] by construction:
     * [PlaybackViewModel.evaluateNextUp] only ever sets one, per
     * [uniffi.jellybeam_core.StillWatchingDecision]. */
    val stillWatching: StillWatchingState? = null,
    /** dpad-left seek magnitude, from `Settings.skipBackSecs`, read once per session in [start].
     * Defaults to [SeekMath.SKIP_MS] until that read resolves.
     */
    val skipBackMs: Long = SeekMath.SKIP_MS,
    /** dpad-right seek magnitude, from `Settings.skipForwardSecs` -- see [skipBackMs]. */
    val skipForwardMs: Long = SeekMath.SKIP_MS,
    /** Read once per session in [start]; PlaybackScreen's `AndroidView` update block applies it. */
    val subtitleStyle: SubtitleStyle = SubtitleStyle(),
    /** The in-player track picker (docs/09 slice 3b): `null` closed, else [TrackPickerState]'s
     * rows. Independent of [PlaybackOsdController]'s `isVisible` -- see [TrackPickerState]'s own
     * doc comment.
     */
    val trackPicker: TrackPickerState? = null,
    /**
     * The playback stats sheet (docs/12 §17): non-null exactly while open,
     * holding the live [PlaybackLiveStats] snapshot its HEALTH row needs,
     * refreshed at 1Hz by [PlaybackViewModel.refreshStatsSheetLive] only
     * while open. The rest of the sheet is stateless per-item data built
     * directly by [tv.jellybeam.player.PlaybackScreen] via [StatsSheetFormat.build].
     */
    val statsSheetLive: PlaybackLiveStats? = null,
    /** Narrow, non-blocking OSD detail fetch and server label, kept in observable state so the
     * Direct Play chip and a newly opened stats sheet see the completed fetch immediately.
     */
    val playbackStatsDetail: PlaybackOsdDetail? = null,
    val statsServerName: String? = null,
    /**
     * Collector-oriented library metadata, loaded only when its OSD button
     * is activated -- unlike [statsSheetLive] this stays outside the
     * time-to-play path. [LibraryInfoOverlayState.Content] carries a
     * [LibrarySheetContent] built via [LibraryInfoFormat.buildSheet] (docs/12 §17).
     */
    val libraryInfoOverlay: LibraryInfoOverlayState? = null,
    /** This session's chapter markers, from [uniffi.jellybeam_core.ItemDetail.chapters] -- empty
     * (never a throw) when unresolved, failed, or genuinely absent (see [Chapters]'s own doc
     * comment).
     */
    val chapters: List<ChapterInfoFfi> = emptyList(),
    /** This session's skip-intro/credits markers (docs/12 §14), from
     * [tv.jellybeam.data.CoreGateway.getMediaSegments] -- empty (never a throw) when unresolved,
     * unsupported by an older server, or genuinely absent.
     */
    val mediaSegments: List<MediaSegment> = emptyList(),
    /**
     * Per-type Ask/AutoSkip/Off behavior (`core/ffi/src/settings.rs`'s five `skip_*` fields), read
     * once per session in [start]. [PlaybackScreen] filters [mediaSegments] through
     * [SkipSegment.decision] against this: [SegmentDecision.PILL] shows the pill,
     * [SegmentDecision.AUTO_SKIP] is handled by [PlaybackViewModel.evaluateAutoSkip] instead,
     * [SegmentDecision.NOTHING] is ignored.
     */
    val skipSegmentActions: SkipSegmentActions = SkipSegmentActions(),
    /**
     * `true` while Media3 reports [androidx.media3.common.Player.STATE_BUFFERING].
     * A sibling of [isPaused], not a replacement: [isPaused] derives from
     * `playWhenReady`, so a rebuffer while the player still intends to play
     * is buffering but not paused. Combined with `playWhenReady` to gate
     * [bufferingInfo].
     */
    val isBuffering: Boolean = false,
    /**
     * `true` once this session's player has rendered at least one frame --
     * picks which of [BufferingInfo.INITIAL_RESUME_THRESHOLD_MS]/
     * [BufferingInfo.REBUFFER_RESUME_THRESHOLD_MS] a live stall is measured
     * against. Reset to `false` by every [start] call (including
     * [PlaybackViewModel.playNext]'s) even though the shared [PlayerHolder]
     * itself never tears down between items.
     */
    val hasRenderedFirstFrame: Boolean = false,
    /**
     * The OSD's buffering pill (docs/12 §15), non-null exactly while stalled
     * on the network/server while intending to play (`isBuffering && playWhenReady`),
     * covering initial start, post-seek, and mid-play rebuffer alike.
     * Refreshed by [PlaybackViewModel.updateBufferingTicker] only while open;
     * reset to `null` by every fresh [start].
     */
    val bufferingInfo: BufferingInfo? = null,
    /**
     * Non-null exactly while waiting out a [ReconnectPolicy]-recoverable
     * player error. Media3 sits in `Player.STATE_IDLE` for this whole span,
     * so neither [isBuffering] nor [isPaused] read `true` -- this is the
     * only signal the screen has to avoid showing a silently frozen video
     * surface. Cleared on reaching `STATE_READY`, or when the retry budget
     * is exhausted.
     */
    val reconnecting: ReconnectingInfo? = null,
    /** OSD density preference (`Settings.osdDetail`, docs/12 §3/§18), read once per session in
     * [start]. Defaults to [OsdDetailSetting.FULL], matching `Settings::default()`.
     */
    val osdDetail: OsdDetailSetting = OsdDetailSetting.FULL,
    /** Seek-preview panel size (`Settings.seekPreviewSize`, docs/12 §11/§18), read once per
     * session in [start]. */
    val seekPreviewSize: SeekPreviewSize = SeekPreviewSize.MEDIUM,
    /** Live playback speed (docs/12 §12 speed menu: `0.5x`..`2x`). Reset to `1f` at the top of
     * every session (including [PlaybackViewModel.playNext]'s) so the shared [PlaybackPlayer]
     * singleton never carries a previous session's rate forward.
     */
    val playbackRate: Float = 1f,
    /** The speed menu (docs/12 §12). Opening it closes [chaptersMenuOpen], [trackPicker], and both
     * info sheets -- the one-thing-at-a-time convention [PlaybackViewModel.openLibraryInfoOverlay]
     * also follows.
     */
    val speedMenuOpen: Boolean = false,
    /** The chapters menu (docs/12 §8/§12, shown only when the item has markers) -- same
     * mutual-exclusion convention as [speedMenuOpen].
     */
    val chaptersMenuOpen: Boolean = false,
    /** Credits-aware previous/next-episode availability (docs/12 §8, episodic only) -- booleans
     * rather than exposing the fetched [Card]s, populated once per session in [start].
     */
    val hasPreviousEpisode: Boolean = false,
    val hasNextEpisode: Boolean = false,
    /**
     * Feeds the audio & subtitles OSD button's "active-but-not-default"
     * accent dot (docs/12 §8): non-default audio selected (only when some
     * audio track actually is flagged default -- see
     * [PlaybackViewModel.computeNonDefaultTrackActive]), OR non-default
     * subtitle selected, OR the default subtitle was turned off. Recomputed
     * wherever track selection is resolved or changed.
     */
    val nonDefaultTrackActive: Boolean = false,
    /** Mini player / picture-in-picture (docs/17 §2, §4): `Settings.miniPlayerEnabled` snapshot,
     * combined with [tv.jellybeam.player.PipController.isSupported] by
     * [tv.jellybeam.player.PlaybackActivity] to decide whether Back/Home enter PiP.
     */
    val miniPlayerEnabled: Boolean = false,
    /**
     * docs/18 §1/§3: whether this session is Direct Play or server
     * transcode -- set from `PlaybackPlan.playMethod` in [start] and again
     * by [PlaybackViewModel.maybeFallBackToTranscode] on a successful
     * fallback; no manual "switch to transcode" control exists. Drives
     * [DirectPlayLine] and the stats sheet's headline.
     */
    val playMethod: PlayMethodFfi = PlayMethodFfi.DIRECT_PLAY,
    /** [uniffi.jellybeam_core.PlaybackPlan.transcodeReason] -- `null` for Direct Play, else the
     * fallback failure text or `"bitrate above cap"`. Feeds the stats sheet headline only;
     * [DirectPlayLine] shows just the leading word/color.
     */
    val transcodeReason: String? = null,
) {
    enum class Phase { LOADING, READY }
}

/** [PlaybackUiState.reconnecting]'s payload -- just the 1-based retry [attempt]: the
 * "Reconnecting…" pill has no live percent/throughput to show while `Player.STATE_IDLE` waits on a
 * timer.
 */
data class ReconnectingInfo(val attempt: Int)

/**
 * One row in the track picker (docs/09 slice 3b) -- [id] is a [TrackMapping]
 * id, or [TRACK_PICKER_SUBTITLE_OFF_ID] for the synthetic "Off" row.
 * [label] follows the display rule: title, else language code, else
 * "Track N" (N = 1-based position within its kind's list).
 */
data class TrackChoice(
    val id: Long,
    val label: String,
    val selected: Boolean,
    /** Trailing `"lang codec"` metadata (docs/12-osd-ux-spec.md "Track picker"), e.g. `"eng aac"`
     * -- `null` if [TrackInfo] has neither.
     */
    val meta: String? = null,
    /** From [TrackInfo.isDefault]; the row's own default-dot, distinct from
     * [PlaybackUiState.nonDefaultTrackActive]. `false` for the synthetic "Off" row.
     */
    val isDefault: Boolean = false,
)

/**
 * The track picker's state while open (docs/09 slice 3b), built by
 * [PlaybackViewModel.openTrackPicker] via [TrackMapping.toTrackInfos].
 * [subtitleTracks] always carries a synthetic "Off" entry first -- "no
 * subtitles" is itself a real choosable state.
 * Independent of [PlaybackOsdController]: [tv.jellybeam.player.PlaybackScreen]
 * renders it whenever non-null, same as the next-up card's own pinning.
 */
data class TrackPickerState(
    val audioTracks: List<TrackChoice>,
    val subtitleTracks: List<TrackChoice>,
)

/**
 * The next-up card's state. [countdownStartPositionTicks] lets [NextUpCountdown] derive live
 * elapsed/remaining from [PlaybackViewModel.positionTicks] advancing, with no separate timer.
 * [autoAdvance] snapshots `Settings.autoplayEnabled` when the card appeared: `false` means the
 * card still shows and Enter still plays it, but [PlaybackViewModel.evaluateNextUp] never
 * auto-advances it on countdown completion.
 */
data class NextUpState(
    val card: Card,
    val countdownTotalSecs: Double,
    val countdownStartPositionTicks: Long,
    val autoAdvance: Boolean,
)

/**
 * The "still watching?" card's state (docs/12 §13) -- [card] is the next episode. [deadlineMs] is
 * the wall-clock instant (injected [Clock]) [PlaybackViewModel.stillWatchingStop] fires at if
 * unanswered; replaced (not extended) on every [PlaybackViewModel.startStillWatchingTimeout]
 * restart so a key press visibly resets the Stop button's fill and "Stops in" numeral. Not a
 * per-second ticking field -- see [PlaybackUiState]'s doc on `positionTicks`; the card's own
 * `LaunchedEffect` ticks this locally.
 */
data class StillWatchingState(val card: Card, val timeoutTotalSecs: Double, val deadlineMs: Long)

/** A seek's trickplay scrub-preview resolution, from [PlaybackViewModel.seek] -- `null` when
 * there's nothing to preview (see [seek]'s own doc comment).
 */
data class TrickplaySeekResult(val targetPositionMs: Long, val tile: TrickplayTileFfi)

/** One-shot events the Compose screen can't derive from [PlaybackUiState] alone. */
sealed interface PlaybackEvent {
    /** The current item reached its natural end -- close playback without presenting an error. */
    data object Finish : PlaybackEvent

    /**
     * docs/12 §13's "Still watching?" Stop or timeout: the finished episode's
     * final report already landed (same exit as [Finish]), but this routes
     * [tv.jellybeam.player.PlaybackActivity] to [itemId]'s (the next,
     * never-started episode's) detail page instead, so the viewer resumes
     * there rather than mid-autoplay-chain.
     */
    data class FinishToDetail(val itemId: String) : PlaybackEvent

    /** The active saved token was rejected; MainActivity must open in-place reauthorization. */
    data object ReauthorizationRequired : PlaybackEvent

    /** WouldTranscode / any other [CoreException], or a fatal player error -- show [message], then
     * finish.
     */
    data class FinishWithMessage(val message: String) : PlaybackEvent

    /**
     * [PlaybackViewModel.evaluateAutoSkip] just seeked past an
     * [SegmentDecision.AUTO_SKIP] segment -- the screen reacts like a manual
     * [PlaybackViewModel.skipSegment] call, showing the same undo toast. A
     * one-shot event so a fresh toast fires even if nobody was collecting
     * at the exact tick (same reason `replay = 1` on [PlaybackViewModel.events]).
     */
    data class AutoSkipped(val preSkipPositionTicks: Long, val segmentType: MediaSegmentKind) : PlaybackEvent
}

/**
 * Orchestrates one playback session: negotiates the plan via [CoreGateway.preparePlayback], loads
 * it into the shared [PlayerHolder], ticks position reports while playing, and guarantees the
 * reporting session is closed out **exactly once** ([stopPlaybackOnce], or [abandonPlaybackOnce]
 * when playback never became active) no matter which exit path fires first --
 * [tv.jellybeam.player.PlaybackActivity]'s `onStop`-while-finishing, its `onDestroy` fallback, and
 * this ViewModel's own [onCleared] all funnel through the same [sessionEnded]-guarded path.
 * Autoplay ([playNext]) reuses this same instance for a second session rather than recreating the
 * Activity/screen: [start] resets every one-shot-per-session field so the guarantee holds per
 * session, not once per instance lifetime.
 *
 * The final report runs on [reportScope] (default [AppGraph.processScope]), not [viewModelScope]:
 * `onCleared()` cancels `viewModelScope` as part of the same teardown that triggers the report, so
 * the process-lifetime scope avoids the report being cancelled before its `Dispatchers.IO`
 * dispatch starts. Tests inject a deterministic scope.
 */
class PlaybackViewModel(
    private val gateway: CoreGateway,
    private val playerHolder: PlaybackPlayer,
    itemId: String,
    private val reportScope: CoroutineScope = AppGraph.processScope,
    /** docs/17 §6: called after every landed final stop report
     * ([stopPlaybackOnce]/[stopPlaybackAndAwait], never [abandonPlaybackOnce]'s) so Detail/Home can
     * refresh promptly. Tests inject a counting lambda.
     */
    private val onStopReported: () -> Unit = PlaybackReports::onStopLanded,
    /** Sheet transport for the trickplay preview (docs/12 §11); `null` (tests) disables the
     * pipeline while [seek]/[glideSampleTile] still resolve tiles. */
    sheetFetcher: SheetFetcher? = null,
    /** docs/11 "Start from beginning" override -- applies only to the constructor's initial [start]
     * call; [playNext] always uses the default `false` since autoplay is never a restart.
     */
    private val startFromBeginning: Boolean = false,
    /**
     * Injectable time source for the "still watching?" inactivity guard
     * (docs/12 §13),
     * passed to the relevant `CoreGateway` calls as their monotonic `nowMs`.
     * Defaults to real wall-clock time in production.
     */
    private val clock: Clock = Clock.SYSTEM,
) : ViewModel() {

    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    /**
     * Live playback position, on its own `StateFlow` rather than a
     * [PlaybackUiState] field -- see that class's doc comment for why.
     * [startProgressTicker] writes this every [REPORT_INTERVAL_MS]
     * regardless of who's collecting: server progress reporting doesn't
     * care whether the OSD happens to be on screen.
     */
    private val _positionTicks = MutableStateFlow(0L)
    val positionTicks: StateFlow<Long> = _positionTicks.asStateFlow()

    /** How far Media3 has buffered, mirrored every tick alongside [_positionTicks] -- its own
     * `StateFlow` for the same perf reason (only the progress bar's narrow scope collects it).
     */
    private val _bufferedPositionTicks = MutableStateFlow(0L)
    val bufferedPositionTicks: StateFlow<Long> = _bufferedPositionTicks.asStateFlow()

    // replay = 1 so an error event fired before the Compose screen's collector
    // attaches (e.g. a preparePlayback failure resolving pre-composition) is
    // never silently dropped.
    private val _events = MutableSharedFlow<PlaybackEvent>(replay = 1, extraBufferCapacity = 1)
    val events: SharedFlow<PlaybackEvent> = _events.asSharedFlow()

    /** The item currently loaded/loading -- reassigned by [start] on every session, including
     * [playNext]'s.
     */
    private var currentItemId: String = itemId

    /** `true` until a `preparePlayback` call actually succeeds -- see [stopPlaybackOnce]'s doc. */
    private val sessionEnded = AtomicBoolean(true)
    /** Serializes the old session's stop/report with the next session's prepare/load. */
    private val sessionTransitionInFlight = AtomicBoolean(false)
    private var lastReportedPaused: Boolean? = null
    private var reportTickerJob: Job? = null
    /** Sticky per-session proof that real playback began; fatal errors after this point must
     * preserve resume position.
     */
    private var playbackWasActive = false

    /**
     * Non-null exactly while [PlaybackUiState.bufferingInfo] is refreshing
     * live, started/stopped only by [updateBufferingTicker]. A
     * `while (isActive) { delay(...) }` loop on [viewModelScope], same
     * leftover-job hygiene as every other per-session ticker/job field in
     * this class: cancelled on a fresh [start] and by
     * [cancelSessionJobsAndCloseMenus] on exit, or it would run forever.
     */
    private var bufferingTickerJob: Job? = null

    /** Short, resettable presentation grace for an explicit seek, so the buffering pill doesn't
     * flash for Media3's normal keyframe transition. Repeated seeks restart it.
     */
    private var seekBufferingGraceJob: Job? = null
    private var suppressSeekBuffering = false

    /**
     * Non-null exactly while a [ReconnectPolicy] retry is pending ([PlaybackUiState.reconnecting]
     * non-null) -- one `delay` then one [PlaybackPlayer.retryAfterError] call, not a ticker loop,
     * so a pending `delay` in a `runTest` simply runs to completion. Cancelled with the other
     * per-session jobs, and by [clearReconnectState] once a retry reaches READY.
     */
    private var reconnectJob: Job? = null

    /** `0` when no reconnect episode is in progress, else the 1-based attempt number passed to
     * [ReconnectPolicy.decide] -- see [beginOrContinueReconnect] for the episode lifecycle.
     */
    private var reconnectAttempt: Int = 0

    /** Sum of every [ReconnectPolicy.Decision.Retry.delayMs] this reconnect episode has waited out
     * -- [ReconnectPolicy.decide]'s `elapsedMsSoFar`, threaded through [beginOrContinueReconnect].
     */
    private var reconnectElapsedMs: Long = 0L

    /**
     * The position remembered at the start of the current reconnect episode
     * -- [_positionTicks]'s last-good value, never a live read taken during
     * the error: `Player.STATE_IDLE` after an error can read a bogus
     * position-0. `null` when no episode is in progress; both
     * [startProgressTicker] and [stopPlaybackOnce] fall back to a live read
     * in that case.
     */
    private var reconnectPositionTicks: Long? = null

    /**
     * docs/18 §1/§3: this session's current plan, needed by
     * [maybeFallBackToTranscode]'s gate and [fatalPlaybackMessage]'s
     * `serverVerdict`. Reassigned by [start] on every session (including a
     * successful fallback's replacement plan); unlike
     * [reconnectPositionTicks] it's replaced, never cleared to `null`
     * mid-session.
     */
    private var currentPlan: PlaybackPlan? = null

    /** docs/21 §2.1's `playback.prepare` `type` tag -- the previous session's [PlaybackPlan
     * .itemType], best-effort only: the constructor's very first [start] call has no prior plan
     * to read a type from, so that one line's `type` reads "unknown". */
    private var lastKnownItemType: String? = null

    /** One fallback per item (docs/18 §1): `true` once [maybeFallBackToTranscode] has attempted a
     * fallback this session, so any later local-unplayability signal falls straight to the fatal
     * path. Reset by every [start].
     */
    private var transcodeFallbackAttempted: Boolean = false

    /**
     * docs/18 §3: incremented once per session by [start] -- see [start]'s
     * NonCancellable comment for the generation-guard rule this snapshot
     * feeds. A plain counter, never reset, so no two sessions can compare
     * equal.
     */
    private var sessionGeneration = 0

    /** This session's `Settings.tolerateMislabeledLevels`/decoder-preference snapshot, remembered
     * so [maybeFallBackToTranscode] can reload the fallback plan with the same preferences [start]
     * used.
     */
    private var sessionTolerateMislabeledLevels: Boolean = true
    private var sessionAudioDecoderPreferences: AudioDecoderPreferences = AudioDecoderPreferences()

    /** The play/pause intent ([PlaybackPlayer.playWhenReady]) in effect when the current reconnect
     * episode started, captured once per episode so a successful retry resumes exactly how the
     * viewer left it.
     */
    private var reconnectPlayWhenReady: Boolean = true

    /**
     * The current session's Outro segment start (seconds), derived by
     * [fetchMediaSegments] via [CoreGateway.outroStartSecsFromSegments] from
     * the same fetch that populates [PlaybackUiState.mediaSegments] -- no
     * second request. `null` until resolved, absent, or failed; fed to
     * [CoreGateway.nextEpisodeTriggerRemainingSecs] each tick.
     */
    private var outroStartSecs: Double? = null

    /** `Settings.autoplayEnabled`/`autoplayDelaySecs`, fetched once via [CoreGateway.getSettings]
     * in [start]. [autoplayEnabled] snapshots into [NextUpState.autoAdvance]; [autoplayDelaySecs]
     * feeds [CoreGateway.nextEpisodeCountdownTotal].
     */
    private var autoplayEnabled: Boolean = true
    private var autoplayDelaySecs: Double = 10.0

    /** `Settings.stillWatching.timeoutSecs`, snapshotted once per session -- the delay
     * [startStillWatchingTimeout] waits before treating silence as Stop. Defaults to 120 (matching
     * `StillWatchingPrefs::default()`).
     */
    private var stillWatchingTimeoutSecs: Double = 120.0

    /** Non-null exactly while a "still watching?" card's visible timeout is pending --
     * started/restarted by [startStillWatchingTimeout], cancelled by
     * [stillWatchingContinue]/[stillWatchingStop] and by every fresh [start].
     */
    private var stillWatchingTimeoutJob: Job? = null

    /**
     * Guards [evaluateNextUp]'s `noteEpisodeFinished` round trip against
     * double-counting: [evaluateNextUp] runs once per second on Main, so
     * this flag (set the instant the threshold is crossed, cleared once the
     * resulting card is applied) stops a slow round trip from letting a
     * second tick fire a second call before the first answer is rendered.
     */
    private var stillWatchingEvaluationPending = false

    /**
     * Set by [handlePlaybackEnded] when `STATE_ENDED` arrives while
     * [stillWatchingEvaluationPending] is still true, since outro auto-skip
     * defers to the still-watching decision and the file can legitimately
     * reach EOF first. [evaluateNextUp]'s decision `launch` reads it to
     * skip straight to [CountdownOutcome.ADVANCE_NOW] rather than starting a
     * card frozen at 0s. Cleared once consumed, and by every [start].
     */
    private var endedWhileDeciding = false

    /**
     * [evaluateAutoSkip]'s idempotency guard -- the `(startTicks, endTicks)`
     * key of the last segment this session auto-skipped, or `null`.
     * Without it the same segment would refire every tick until the seek
     * lands. Deliberately never cleared by [undoSkip], so seeking back into
     * the segment doesn't immediately re-trigger it. Reset by every [start].
     */
    private var lastAutoSkipSegmentKey: Pair<Long, Long>? = null

    /** The current session's next episode, fetched via [CoreGateway.nextEpisodeAfter] when an
     * Episode's plan loads -- `null` if unresolved, not an Episode, or already the series finale.
     */
    private var nextEpisode: Card? = null

    /** The current session's previous episode, fetched via [CoreGateway.previousEpisodeBefore]
     * alongside [nextEpisode] (docs/12 §8) -- `null` for the same three reasons. Private; only
     * [PlaybackUiState.hasPreviousEpisode] is exposed.
     */
    private var previousEpisode: Card? = null

    /** Back-dismiss: once dismissed, the next-up card stays hidden for the rest of this session,
     * reset by [start]. */
    private var nextUpDismissed = false

    /** Guards [showCountdownCard]'s [CoreGateway.preloadPlayback] call to once per next item
     * (docs/18 preload); reset by [start]. */
    private var preloadedNextUpItemId: String? = null

    /** The current session's `PlaybackPlan.seriesId`, captured in [start] -- fed to
     * [CoreGateway.resolveTracks] by [resolveTrackSelectionOnce]. */
    private var currentSeriesId: String? = null

    /**
     * The current session's trickplay manifest, fetched fire-and-forget by
     * [fetchTrickplay] via [CoreGateway.getTrickplay] (not read off the
     * plan directly, since `prepare_playback` must not await it). `null`
     * until resolved, absent, or failed -- [seek] fails open on `null` the
     * same way a manifest-less item always has. Exposed publicly (unlike
     * [outroStartSecs]); [TrickplayPreviewer] turns its tiles into bitmaps.
     */
    var trickplayMeta: TrickplayMetaFfi? = null
        private set

    /**
     * Hold-to-seek's end-clamp hold (docs/12 §9): `true` only right after [commitGlide] commits
     * at the end clamp. While `true`, [startProgressTicker] skips [evaluateNextUp] and
     * [evaluateAutoSkip] so landing at the end via a deliberate glide never fires autoplay,
     * up-next, or a watched-state write. Cleared only by [start] and once [updatePlayState] sees
     * `playWhenReady` go back to `true` -- not by a seek, since a Left glide's initial tap from the
     * clamp can still land inside the up-next trigger window while paused.
     */
    private var endClampHold = false

    /**
     * While [endClampHold] is `true`, the position [endSessionForStop] must report on exit --
     * never the clamp point itself, or a racing stop/mirror-write would mark the item watched.
     * Set by [commitGlide]'s `endClamped = true` branch to the true pre-seek position, then kept
     * current by every later seek taken while still paused. Cleared to `null` with [endClampHold].
     */
    private var endHoldReportTicks: Long? = null

    /** Monotonic timestamp right after playback negotiation (`SystemClock.elapsedRealtime()`) --
     * feeds the `playback.firstFrame` perf-log line (docs/10).
     */
    var loadStartedAtMs: Long = 0L
        private set

    /** Completes on the first rendered frame or [ENRICHMENT_GATE_FALLBACK_MS] after [start],
     * whichever comes first -- gates [fetchPlaybackOsdDetail], [fetchTrickplay], and
     * [fetchServerDisplayName] so they don't compete with the media loads for time-to-first-frame
     * (docs/12 §11, docs/18). Replaced fresh by every [start]; [fetchMediaSegments] and
     * [fetchEpisodeNeighbors] stay immediate and never await it.
     */
    private var enrichmentGate = CompletableDeferred<Unit>()

    /** This session's already-fetched [PlaybackOsdDetail], kept unformatted so the stats sheet
     * (docs/12 §17) can build its content from the same fetch. `null` until
     * [fetchPlaybackOsdDetail] resolves or fails.
     */
    val playbackOsdDetail: PlaybackOsdDetail? get() = _state.value.playbackStatsDetail

    /** This session's server display name ([CoreGateway.serverDisplayName]) for the stats sheet's
     * SOURCE row (docs/12 §17). `null` until resolved, or if nobody is signed in / the server URL
     * has no parseable host.
     */
    val serverDisplayName: String? get() = _state.value.statsServerName

    /** The one in-flight, explicitly requested library-info fetch. */
    private var libraryInfoJob: Job? = null

    /**
     * Automatic track selection (docs/09 step 3): `true` once
     * [resolveTrackSelectionOnce] has resolved and applied a decision this
     * session, so a later `onTracksChanged` (ExoPlayer can announce twice
     * per load) doesn't re-resolve. Reset by [start]. Deliberately NOT
     * cleared when [trackSelectionJob] is cancelled -- cancellation must
     * not reopen the "resolve once" window.
     */
    private var trackSelectionApplied = false

    /**
     * docs/18 §3.1: the job for [resolveTrackSelectionOnce]'s one `resolveTracks` round trip, so a
     * session change or manual [chooseAudio]/[chooseSubtitle] pick can cancel it outright rather
     * than relying only on the generation/manual-choice checks inside it (cancellation is
     * cooperative, same caveat as [maybeFallBackToTranscode]'s generation guard). Cancelled in
     * [start]'s reset, in [cancelSessionJobsAndCloseMenus], and before applying a manual pick.
     */
    private var trackSelectionJob: Job? = null

    /** `true` once [chooseAudio]/[chooseSubtitle] has applied a manual pick this session, checked
     * by [resolveTrackSelectionOnce]'s pending coroutine so a slow automatic resolution can't
     * overwrite it. Reset by every [start].
     */
    private var manualTrackChoiceMade = false

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = updatePlayState()
        override fun onPlaybackStateChanged(playbackState: Int) {
            updatePlayState()
            if (playbackState == Player.STATE_ENDED && playerHolder.playbackState == Player.STATE_ENDED) {
                handlePlaybackEnded()
            }
        }

        // playWhenReady has its own callback, independent of onPlaybackStateChanged
        // (e.g. pausing mid-stall never changes playbackState) -- see
        // updateBufferingTicker's gate. `reason` is never inspected.
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = updatePlayState()

        override fun onTracksChanged(tracks: Tracks) {
            resolveTrackSelectionOnce(tracks)
            // docs/18 §1's second local-evidence signal (the other is onPlayerError
            // below): every track of a present type rejected by this device's
            // decoders. maybeFallBackToTranscode's own gate gets it right
            // regardless of playMethod, so this stays unconditional.
            LocalPlayability.unplayableReason(tracks)?.let { reason -> maybeFallBackToTranscode(reason) }
        }

        // Initial-load/mid-play-stall disambiguator -- see
        // PlaybackUiState.hasRenderedFirstFrame. Never reset to `false` here
        // (fires once per loaded item); `start` resets the state field instead.
        override fun onRenderedFirstFrame() {
            playbackWasActive = true
            _state.update { it.copy(hasRenderedFirstFrame = true) }
            enrichmentGate.complete(Unit) // docs/12 §11: unblocks the OSD/trickplay/server-name fetches
            // Isolates "prepare_playback returned -> first frame" from the
            // click-to-plan phase already covered by playback.click/ffi.preparePlayback
            // (docs/10).
            if (PerfLog.enabled) {
                val ms = android.os.SystemClock.elapsedRealtime() - loadStartedAtMs
                Log.d(PerfLog.TAG, "perf startup phase=playback.firstFrame ms=$ms")
            }
            PerfLog.markPlayback("firstFrame")
        }

        override fun onPlayerError(error: PlaybackException) {
            // The source-level policy already spent a real recovery window before
            // surfacing this; starting ReconnectPolicy too would stack two
            // independent budgets and could hold a dead stream for minutes.
            if (playerHolder.hasExhaustedLoadRetryBudget()) {
                finishAfterFatalPlaybackError("LOAD_RETRY_EXHAUSTED")
                _events.tryEmit(PlaybackEvent.FinishWithMessage("Playback error: lost connection to the server"))
                return
            }

            // A network-ish SOURCE/IO error gets a reconnect episode; a
            // codec/renderer/parsing error falls straight to the fatal path.
            if (ReconnectPolicy.isRecoverable(error)) {
                beginOrContinueReconnect()
            } else if (!maybeFallBackToTranscode("Playback error: ${error.errorCodeName}")) {
                // docs/18 §1's first local-evidence signal (the other is
                // onTracksChanged above) -- ordinary fatal path once the fallback
                // gate declines.
                finishAfterFatalPlaybackError(error.errorCodeName)
                _events.tryEmit(PlaybackEvent.FinishWithMessage(fatalPlaybackMessage(error.errorCodeName, currentPlan)))
            }
        }
    }

    /**
     * Drives one [ReconnectPolicy] reconnect episode across however many recoverable errors it
     * takes to recover or give up -- called fresh by `onPlayerError` for each new recoverable
     * error, including a retry attempt's own failure. [reconnectAttempt] `== 0` signals a fresh
     * episode, snapshotting [reconnectPositionTicks]/[reconnectPlayWhenReady] before anything the
     * error changed; later calls only advance the attempt and ask [ReconnectPolicy.decide]. A
     * give-up is a plain fatal error -- no partial position report.
     */
    private fun beginOrContinueReconnect() {
        if (reconnectAttempt == 0) {
            reconnectPositionTicks = _positionTicks.value
            reconnectPlayWhenReady = playerHolder.playWhenReady
            reconnectElapsedMs = 0L
        }
        reconnectAttempt += 1

        when (val decision = ReconnectPolicy.decide(reconnectAttempt, reconnectElapsedMs)) {
            is ReconnectPolicy.Decision.GiveUp -> {
                finishAfterFatalPlaybackError("RECONNECT_EXHAUSTED")
                // Preserve reconnectPositionTicks until the final stop has
                // consumed it -- an idle-after-error player may read zero.
                clearReconnectState()
                _events.tryEmit(PlaybackEvent.FinishWithMessage("Playback error: lost connection to the server"))
            }
            is ReconnectPolicy.Decision.Retry -> {
                _state.update { it.copy(reconnecting = ReconnectingInfo(attempt = reconnectAttempt)) }
                val delayMs = decision.delayMs
                val positionTicks = reconnectPositionTicks ?: 0L
                val playing = reconnectPlayWhenReady
                reconnectJob?.cancel()
                reconnectJob = viewModelScope.launch {
                    delay(delayMs)
                    reconnectElapsedMs += delayMs
                    playerHolder.retryAfterError(positionTicks, playing)
                }
            }
        }
    }

    /** Ends the current reconnect episode without touching the rest of the session -- called on
     * recovery ([updatePlayState]'s `STATE_READY` branch) and before the give-up fallback,
     * resetting the fields [beginOrContinueReconnect] initializes for a fresh episode.
     */
    private fun clearReconnectState() {
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempt = 0
        reconnectElapsedMs = 0L
        reconnectPositionTicks = null
        _state.update { it.copy(reconnecting = null) }
    }

    /**
     * docs/18 §1's "one fallback per item" gate: `true` only while
     * [currentPlan] is DirectPlay, its `transcodeFallbackAllowed` permits a
     * fallback, and none has been attempted yet this session. Plain field
     * reads only, so the decoder hook can check this before asking
     * [SoftwareDecoder.isSoftwareOnly] about the decoder name (docs/18 §3).
     */
    private fun fallbackPossible(): Boolean {
        val plan = currentPlan ?: return false
        return plan.playMethod == PlayMethodFfi.DIRECT_PLAY && plan.transcodeFallbackAllowed && !transcodeFallbackAttempted
    }

    /**
     * docs/18 §1's Auto/Cap fallback -- called from all three local-evidence signals
     * (`onPlayerError`, `onTracksChanged`, the decoder-init hook). A no-op unless
     * [fallbackPossible]; a `true` return tells the caller to skip its own fatal-error handling
     * since this takes over the session's fate. On success: loads the new plan with this session's
     * remembered tolerate/decoder preferences, resets playback rate, re-seeds position, and
     * updates [PlaybackUiState.playMethod]/[PlaybackUiState.transcodeReason] -- [sessionEnded]
     * stays `false` since Rust already swapped reporting sessions internally. A failed fallback is
     * a plain fatal error, same handling as [start]'s own `CoreException` catch.
     *
     * **Generation guard** (canonical statement, cited elsewhere in this class): the negotiation
     * is a real round trip, so a newer [start] call may move the session on before it resolves. A
     * local `generation` snapshot of [sessionGeneration], re-checked when the call returns,
     * discards the result on a mismatch -- no load, no state update, no ending a session that
     * isn't this one's. [CoreException.StalePlaybackSession] is ignored the same way regardless.
     */
    private fun maybeFallBackToTranscode(reason: String): Boolean {
        if (!fallbackPossible()) return false
        val plan = currentPlan!! // fallbackPossible() just confirmed this is non-null
        transcodeFallbackAttempted = true
        clearReconnectState()
        val itemId = currentItemId
        val ticksAtFailure = _positionTicks.value
        val generation = sessionGeneration
        val playSessionId = plan.playSessionId
        viewModelScope.launch {
            try {
                val fallbackPlan = gateway.prepareTranscodeFallback(itemId, ticksAtFailure, reason, playSessionId)
                if (generation != sessionGeneration) return@launch // a newer session started meanwhile -- see this method's own doc comment
                playerHolder.load(
                    fallbackPlan,
                    tolerateMislabeledLevels = sessionTolerateMislabeledLevels,
                    audioDecoderPreferences = sessionAudioDecoderPreferences,
                )
                playerHolder.setPlaybackRate(1f)
                currentPlan = fallbackPlan
                _positionTicks.value = fallbackPlan.startPositionTicks
                _state.update {
                    it.copy(
                        playMethod = fallbackPlan.playMethod,
                        transcodeReason = fallbackPlan.transcodeReason,
                        playbackRate = 1f,
                    )
                }
            } catch (_: CoreException.StalePlaybackSession) {
                // Rust's own staleness guard fired (docs/18 §2); never surfaced to the viewer.
                Log.d(PerfLog.TAG, "maybeFallBackToTranscode: ignoring a stale play session result")
            } catch (e: CoreException) {
                if (generation != sessionGeneration) return@launch // see this method's own doc comment
                finishAfterFatalPlaybackError(e::class.simpleName ?: "CoreException")
                _events.tryEmit(PlaybackEvent.FinishWithMessage(e.displayMessage()))
            }
        }
        return true
    }

    init {
        playerHolder.addListener(playerListener)
        // docs/18 §1's third local-evidence signal. Wired for this ViewModel's
        // whole lifetime (not per-session) since the shared PlayerHolder may
        // init a new decoder for any session, including playNext's; cleared in
        // onCleared so a dead ViewModel's closure can't outlive it.
        playerHolder.onVideoDecoderInitialized = { decoderName ->
            // Cheap guard first (docs/18 §3): most decoder inits are a default
            // Direct Play session fallbackPossible() rejects outright, so
            // isSoftwareOnly's MediaCodecList enumeration is skipped for those.
            if (fallbackPossible() && SoftwareDecoder.isSoftwareOnly(decoderName)) {
                maybeFallBackToTranscode("Software video decoder $decoderName -- no hardware decoder on this TV")
            }
        }
        // "Still watching?" (docs/12 §13): construction-only reset (not in
        // [start], which playNext/playPrevious/playNextEpisode reuse for later
        // sessions, and autoplay isn't interaction) -- unconditional, unlike
        // notePlayerInput's resetOnInput gating.
        viewModelScope.launch { runCatching { gateway.resetStillWatching(clock.nowMs().toULong()) } }
        viewModelScope.launch { start(currentItemId, startFromBeginning = startFromBeginning) }
    }

    private suspend fun start(itemId: String, startFromBeginning: Boolean = false) {
        PerfLog.markPlayback("viewModel.start")
        // docs/21 §2.1: one line per negotiation attempt, before the network round trip.
        AppGraph.diag.event("playback.prepare") {
            item("item", itemId)
            tag("type", lastKnownItemType ?: "unknown")
        }
        // docs/18-playback-quality.md §3: preparePlayback is a synchronous native call that
        // can't itself be cancelled -- Rust may already have installed a real
        // reporting session by the time this coroutine's cancellation is
        // noticed, so it must run inside NonCancellable rather than discarding
        // `plan`. `job` is captured before entering NonCancellable so its
        // `isActive` reflects this coroutine, not the non-cancellable scope's.
        val job = coroutineContext[Job]
        val startup = try {
            withContext(NonCancellable) {
                // The settings read is independent of playback negotiation;
                // starting both before either is awaited collapses two
                // dispatcher hops into one. Not a coroutineScope (would defeat
                // NonCancellable), so runCatching replaces structured-failure
                // propagation for this child.
                val settingsDeferred = async {
                    try {
                        gateway.getSettings()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                }
                val plan = gateway.preparePlayback(itemId, startFromBeginning)
                PerfLog.markPlayback("plan.ready")
                plan to runCatching { settingsDeferred.await() }.getOrNull()
            }
        } catch (e: CoreException) {
            PerfLog.markPlayback(
                when (e) {
                    is CoreException.WouldTranscode -> "plan.refusedTranscode"
                    is CoreException.Unauthorized -> "plan.unauthorized"
                    is CoreException.StalePlaybackSession -> "plan.stale"
                    else -> "plan.failed"
                },
            )
            if (e is CoreException.StalePlaybackSession) {
                // docs/18 §2: lost the install race to a newer session; Rust
                // already abandoned its own negotiated session, so there's
                // nothing here to give up. Quiet finish (no error toast) only
                // if the owner is still active -- a cancelled owner doesn't care.
                if (job?.isActive == true) {
                    Log.i(PerfLog.TAG, "start($itemId): prepare_playback reported StalePlaybackSession; quiet finish")
                    _events.emit(PlaybackEvent.Finish)
                }
                return
            }
            // Per CLAUDE.md's Direct Play rule, prepare_playback never starts a
            // reporting session for a WouldTranscode refusal or any other
            // failure -- sessionEnded stays true, so exit-path stop/abandon
            // calls below are correctly no-ops.
            _events.emit(
                if (e is CoreException.Unauthorized) {
                    PlaybackEvent.ReauthorizationRequired
                } else {
                    PlaybackEvent.FinishWithMessage(e.displayMessage())
                },
            )
            return
        }
        val (plan, settings) = startup

        if (job?.isActive == false) {
            // The owner was cancelled while preparePlayback was on the network,
            // but NonCancellable guaranteed it ran to completion -- Rust already
            // installed a real session for `plan`. Nothing below has touched
            // any ViewModel field yet, so just abandon the installed session,
            // fire-and-forget on reportScope since this ViewModel's own scope
            // is already on its way out.
            reportScope.launch { runCatching { gateway.abandonPlayback(plan.playSessionId) } }
            return
        }

        // Per-session reset (see the class doc's autoplay paragraph): every
        // field below is one-shot-per-session, put back to its fresh value on
        // every `start` call exactly as the constructor's own first call does.
        currentItemId = itemId
        sessionGeneration++ // docs/18 §3: fresh generation guard value for this session
        currentPlan = plan
        lastKnownItemType = plan.itemType
        // docs/21 §2.1: one line per negotiated plan. `transcodeReason` is free text (docs/18's
        // own doc on PlaybackPlan), so a reason with a space maps to the fixed tag "text" -- the
        // line still says a reason existed, without the sentence itself reaching the ring.
        AppGraph.diag.event("playback.plan") {
            item("item", itemId)
            tag("mode", plan.playMethod.name)
            plan.container?.let { tag("container", it) }
            tag("reason", plan.transcodeReason?.let { if (' ' in it) "text" else it } ?: "none")
        }
        transcodeFallbackAttempted = false // docs/18 §1: one fallback per item
        sessionEnded.set(false)
        lastReportedPaused = null
        playbackWasActive = false
        outroStartSecs = null // populated by fetchMediaSegments below, not read off the plan
        nextEpisode = null
        previousEpisode = null
        nextUpDismissed = false
        preloadedNextUpItemId = null
        lastAutoSkipSegmentKey = null
        currentSeriesId = plan.seriesId
        trackSelectionJob?.cancel() // docs/18-playback-quality.md §3.1: leftover-job hygiene, see trackSelectionJob's own doc comment
        trackSelectionJob = null
        trackSelectionApplied = false
        manualTrackChoiceMade = false
        trickplayMeta = null
        enrichmentGate = CompletableDeferred() // fresh gate per session -- see its own doc comment
        previewer?.endSession()
        endClampHold = false
        endHoldReportTicks = null
        loadStartedAtMs = android.os.SystemClock.elapsedRealtime()
        libraryInfoJob?.cancel()
        libraryInfoJob = null
        bufferingTickerJob?.cancel() // leftover-job hygiene, see that field's own doc comment
        bufferingTickerJob = null
        clearSeekBufferingGrace()
        reconnectJob?.cancel() // leftover-job hygiene, see that field's own doc comment
        reconnectJob = null
        reconnectAttempt = 0
        reconnectElapsedMs = 0L
        reconnectPositionTicks = null
        stillWatchingTimeoutJob?.cancel() // leftover-job hygiene, same reasoning as the tickers above
        stillWatchingTimeoutJob = null
        stillWatchingEvaluationPending = false
        endedWhileDeciding = false

        // Settings: a local disk read, negligible latency before `load` below.
        // Failure (shouldn't happen) falls back to this class's own defaults.
        PerfLog.markPlayback("settings.ready")
        autoplayEnabled = settings?.autoplayEnabled ?: true
        autoplayDelaySecs = settings?.autoplayDelaySecs?.toDouble() ?: 10.0
        stillWatchingTimeoutSecs = settings?.stillWatching?.timeoutSecs?.toDouble() ?: 120.0
        val skipBackMs = (settings?.skipBackSecs?.toLong() ?: (SeekMath.SKIP_MS / 1000L)) * 1000L
        val skipForwardMs = (settings?.skipForwardSecs?.toLong() ?: (SeekMath.SKIP_MS / 1000L)) * 1000L
        val subtitleStyle = settings?.subtitleStyle() ?: SubtitleStyle()
        val skipSegmentActions = SkipSegmentActions(
            intro = settings?.skipIntro ?: SegmentAction.ASK,
            outro = settings?.skipOutro ?: SegmentAction.ASK,
            recap = settings?.skipRecap ?: SegmentAction.ASK,
            preview = settings?.skipPreview ?: SegmentAction.ASK,
            commercial = settings?.skipCommercial ?: SegmentAction.AUTO_SKIP,
        )

        // Remembered on the instance (docs/18 §3) so a later maybeFallBackToTranscode
        // call can re-load with the exact same preferences without re-reading Settings.
        sessionTolerateMislabeledLevels = settings?.tolerateMislabeledLevels ?: true
        sessionAudioDecoderPreferences = AudioDecoderPreferences(
            preferFfmpegTrueHd = settings?.preferFfmpegTrueHd ?: false,
            preferFfmpegDts = settings?.preferFfmpegDts ?: false,
            preferFfmpegDtsHd = settings?.preferFfmpegDtsHd ?: false,
        )
        PerfLog.markPlayback("player.load")
        playerHolder.load(
            plan,
            tolerateMislabeledLevels = sessionTolerateMislabeledLevels,
            audioDecoderPreferences = sessionAudioDecoderPreferences,
        )
        // The shared PlayerHolder singleton outlives any one session --
        // a previous session's rate must never leak into a freshly loaded item.
        playerHolder.setPlaybackRate(1f)
        _positionTicks.value = plan.startPositionTicks
        _state.update {
            it.copy(
                phase = PlaybackUiState.Phase.READY,
                itemName = plan.itemName,
                itemType = plan.itemType,
                seriesName = plan.seriesName,
                parentIndexNumber = plan.parentIndexNumber,
                indexNumber = plan.indexNumber,
                durationTicks = plan.runtimeTicks,
                nextUp = null,
                stillWatching = null,
                skipBackMs = skipBackMs,
                skipForwardMs = skipForwardMs,
                subtitleStyle = subtitleStyle,
                skipSegmentActions = skipSegmentActions,
                playMethod = plan.playMethod, // docs/18 §1/§3: reset per session from the fresh plan, never carried over
                transcodeReason = plan.transcodeReason,
                statsSheetLive = null,
                playbackStatsDetail = null,
                statsServerName = null,
                libraryInfoOverlay = null,
                chapters = emptyList(),
                mediaSegments = emptyList(),
                isBuffering = false,
                hasRenderedFirstFrame = false,
                bufferingInfo = null,
                reconnecting = null,
                osdDetail = settings?.osdDetail ?: OsdDetailSetting.FULL,
                seekPreviewSize = settings?.seekPreviewSize ?: SeekPreviewSize.MEDIUM,
                miniPlayerEnabled = settings?.miniPlayerEnabled ?: false,
                playbackRate = 1f,
                speedMenuOpen = false,
                chaptersMenuOpen = false,
                hasPreviousEpisode = false,
                hasNextEpisode = false,
                nonDefaultTrackActive = false,
            )
        }

        // Session heartbeat starts before every optional enrichment; episode-neighbor
        // recovery may need one live request and must never block time-to-first-frame.
        startProgressTicker()
        val episodeSeriesId = plan.seriesId
        if (plan.itemType == ITEM_TYPE_EPISODE && episodeSeriesId != null) {
            fetchEpisodeNeighbors(itemId, episodeSeriesId)
        }
        // Segments feed auto-skip, which can fire at 0s, so this one stays immediate.
        fetchMediaSegments(itemId)
        // The rest are gated on enrichmentGate (first frame, or the fallback below) so they
        // don't compete with the media loads for time-to-first-frame (docs/12 §11, docs/18).
        val gate = enrichmentGate
        viewModelScope.launch {
            delay(ENRICHMENT_GATE_FALLBACK_MS)
            gate.complete(Unit)
        }
        fetchPlaybackOsdDetail(itemId)
        fetchTrickplay(itemId, plan.mediaSourceId)
        fetchServerDisplayName(itemId)
    }

    /**
     * Populates both episode-edge controls from one coherent lookup after
     * player load. The FFI prefers the mirror and performs at most one live
     * series request if the current item cannot be placed there. The item-id
     * guard prevents a late answer from an old autoplay session from adding
     * controls that target the wrong episode.
     */
    private fun fetchEpisodeNeighbors(itemId: String, seriesId: String) {
        viewModelScope.launch {
            val neighbors = runCatching { gateway.episodeNeighbors(itemId, seriesId) }.getOrNull()
            if (currentItemId != itemId) return@launch
            previousEpisode = neighbors?.previous
            nextEpisode = neighbors?.next
            _state.update {
                it.copy(
                    hasPreviousEpisode = previousEpisode != null,
                    hasNextEpisode = nextEpisode != null,
                )
            }
        }
    }

    /**
     * Chapters + the info overlay's content (docs/12), from one narrow live
     * [uniffi.jellybeam_core.PlaybackOsdDetail] fetch. Fire-and-forget, not awaited by [start]; fails
     * open since an older/unreachable server just means no tick marks/empty overlay, never a
     * playback interruption. Gated on [enrichmentGate], along with [fetchTrickplay] and
     * [fetchServerDisplayName].
     *
     * **Stale-session guard, shared by every `fetch*` method in this class**
     * ([fetchServerDisplayName]/[fetchMediaSegments]/[fetchTrickplay]): [viewModelScope] outlives
     * one session, so a slow fetch from a *previous* item may still be in flight after
     * [currentItemId] moved on. Each re-checks `currentItemId != itemId` after its suspend point
     * and bails if a newer session started; each is independent, one failing never blocks another.
     */
    private fun fetchPlaybackOsdDetail(itemId: String) {
        val gate = enrichmentGate
        viewModelScope.launch {
            gate.await() // docs/12 §11: first frame, or the fallback timer -- see enrichmentGate's doc comment
            val detail = runCatching { gateway.getPlaybackOsdDetail(itemId) }.getOrNull()
            if (currentItemId != itemId) return@launch // a newer session started meanwhile -- see this method's own doc comment
            _state.update {
                it.copy(
                    playbackStatsDetail = detail,
                    chapters = detail?.chapters.orEmpty(),
                )
            }
        }
    }

    /** Playback stats sheet's SOURCE row (docs/12 §17), via [CoreGateway.serverDisplayName] -- see
     * [fetchPlaybackOsdDetail]'s doc comment for the shared shape. `null` if unresolved or nobody's
     * signed in; the sheet omits the row.
     */
    private fun fetchServerDisplayName(itemId: String) {
        val gate = enrichmentGate
        viewModelScope.launch {
            gate.await() // docs/12 §11: first frame, or the fallback timer -- see enrichmentGate's doc comment
            val name = runCatching { gateway.serverDisplayName() }.getOrNull()
            if (currentItemId != itemId) return@launch // a newer session started meanwhile -- see fetchPlaybackOsdDetail's own doc comment
            _state.update { it.copy(statsServerName = name) }
        }
    }

    /** See [fetchPlaybackOsdDetail]'s doc comment for the shared shape. Also derives
     * [outroStartSecs] via [CoreGateway.outroStartSecsFromSegments] from this same fetch -- no
     * second request.
     */
    private fun fetchMediaSegments(itemId: String) {
        viewModelScope.launch {
            val segments = runCatching { gateway.getMediaSegments(itemId) }.getOrDefault(emptyList())
            if (currentItemId != itemId) return@launch // a newer session started meanwhile -- see fetchPlaybackOsdDetail's own doc comment
            outroStartSecs = gateway.outroStartSecsFromSegments(segments)
            _state.update { it.copy(mediaSegments = segments) }
        }
    }

    /** Trickplay scrub-preview manifest, via [CoreGateway.getTrickplay] -- see
     * [fetchPlaybackOsdDetail]'s doc comment for the shared shape. [seek] already fails open on a
     * `null` [trickplayMeta], so a race needs no special handling.
     */
    private fun fetchTrickplay(itemId: String, mediaSourceId: String) {
        val gate = enrichmentGate
        viewModelScope.launch {
            gate.await() // docs/12 §11: first frame, or the fallback timer -- see enrichmentGate's doc comment
            val meta = runCatching { gateway.getTrickplay(itemId, mediaSourceId) }.getOrNull()
            if (currentItemId != itemId) return@launch // a newer session started meanwhile -- see fetchPlaybackOsdDetail's own doc comment
            trickplayMeta = meta
            val previewer = previewer
            if (meta != null && previewer != null) {
                previewer.startSession(
                    meta,
                    urlFor = { index -> gateway.trickplayTileUrl(itemId, meta.width, index) },
                    abandonRule = { sheet, targetMs, direction ->
                        gateway.trickplayShouldAbandonSheet(meta, sheet.toUInt(), targetMs.toULong(), direction)
                    },
                )
                // docs/12 §11: the sheet under the play position is warmed now so the session's
                // first glide never starts with a cold fetch.
                val positionMs = PlaybackTicks.ticksToMs(positionTicks.value).coerceAtLeast(0L)
                gateway.trickplayLocate(meta, positionMs.toULong())?.let { previewer.warm(it.imageIndex.toInt()) }
            }
        }
    }

    /** docs/12 §11 pipeline; `null` without a [SheetFetcher] (tests). */
    private val previewer: TrickplayPreviewer<ImageBitmap>? =
        sheetFetcher?.let {
            TrickplayPreviewer(it, RegionTileDecoder, viewModelScope, tileBytes = RegionTileDecoder::bytesOf, perfLine = PerfLog::line)
        }

    /** The hold-last trickplay tile for the seek-preview panel and the glide surface. */
    val previewTile: StateFlow<ImageBitmap?> = previewer?.tile ?: MutableStateFlow(null)

    /** Runs unconditionally while a session is active, independent of OSD visibility or Compose
     * collection -- see [_positionTicks]'s doc comment. The position write, server report, and
     * [evaluateNextUp] are independent concerns sharing one tick, none skippable just because
     * nobody's collecting.
     */
    private fun startProgressTicker() {
        reportTickerJob?.cancel()
        reportTickerJob = viewModelScope.launch {
            while (isActive) {
                delay(REPORT_INTERVAL_MS)

                // Reconnect episode in progress: player is in STATE_IDLE, so
                // position/buffered reads aren't trustworthy yet (see
                // reconnectPositionTicks's doc comment) -- hold the last-known-good
                // position and skip reporting/next-up/auto-skip entirely.
                val frozenTicks = reconnectPositionTicks
                if (frozenTicks != null) {
                    _positionTicks.value = frozenTicks
                    refreshStatsSheetLive()
                    continue
                }

                val ticks = playerHolder.currentPositionTicks()
                _positionTicks.value = ticks
                _bufferedPositionTicks.value = playerHolder.bufferedPositionTicks()
                refreshStatsSheetLive()
                if (ProgressReportGate.shouldReportPosition(playerHolder.isPlaying)) {
                    runCatching { gateway.reportPosition(ticks) }
                }
                // docs/12 §9: an end-clamped glide commit pauses without
                // triggering autoplay/up-next/watched-state -- position
                // reporting and stats above are unaffected.
                if (!endClampHold) {
                    evaluateNextUp(ticks)
                    evaluateAutoSkip(ticks)
                }
            }
        }
    }

    /** Per-frame position read for [tv.jellybeam.player.NextUpCard]'s countdown rule (docs/12 §13) --
     * [_positionTicks] only advances once per [REPORT_INTERVAL_MS], too coarse for a smooth
     * per-frame fill. Mirrors [startProgressTicker]'s reconnect hold so the rule freezes, not
     * jumps, during a reconnect. Main thread only.
     */
    fun livePositionTicks(): Long = reconnectPositionTicks ?: playerHolder.currentPositionTicks()

    /** The card's per-frame loop saw the countdown run out (docs/12 §13): hand over now instead
     * of on [startProgressTicker]'s next 1 Hz pass, so the empty rule, the `0S` numeral and the
     * transition land on the same frame. Re-checked against the live position, and [transitionTo]
     * is guarded, so a stale or duplicate call is a no-op.
     */
    fun nextUpCountdownElapsed() {
        val shown = _state.value.nextUp ?: return
        if (!shown.autoAdvance) return
        val elapsed = NextUpCountdown.elapsedSecs(livePositionTicks(), shown.countdownStartPositionTicks)
        if (NextUpCountdown.isComplete(shown.countdownTotalSecs, elapsed)) playNext()
    }

    /**
     * Credits-aware next-up's per-tick state machine (docs/12 §13): with neither card shown,
     * checks whether remaining time crossed [CoreGateway.nextEpisodeTriggerRemainingSecs]'s
     * threshold; with credits set to Auto-skip that is the autoplay delay before the outro, and
     * the countdown is sized to run out where the skip lands, so the card is seen in full and the
     * next episode starts as the credits begin. Autoplay OFF shows the static countdown card with
     * no Rust round trip; autoplay ON asks [CoreGateway.noteEpisodeFinished], whose [StillWatchingDecision.COUNTDOWN] populates
     * [PlaybackUiState.nextUp] the same way and whose `ASK_STILL_WATCHING` populates
     * [PlaybackUiState.stillWatching] instead (mutually exclusive by construction). With a card
     * already shown, advances its countdown and calls [playNext] on completion only if
     * [NextUpState.autoAdvance] is true; a shown still-watching card is left to its own timeout
     * job. A no-op with no next episode, no known duration, or after [nextUpDismissed].
     */
    private fun evaluateNextUp(positionTicks: Long) {
        val next = nextEpisode ?: return
        if (nextUpDismissed) return
        if (_state.value.stillWatching != null) return
        if (stillWatchingEvaluationPending) return
        val durationTicks = _state.value.durationTicks?.takeIf { it > 0L } ?: return

        val shown = _state.value.nextUp
        if (shown == null) {
            val durationSecs = PlaybackTicks.ticksToMs(durationTicks) / 1000.0
            val positionSecs = PlaybackTicks.ticksToMs(positionTicks.coerceAtLeast(0L)) / 1000.0
            val remainingSecs = (durationSecs - positionSecs).coerceAtLeast(0.0)
            val outroStart = outroStartSecs
            val outroAutoSkipActive = outroStart != null &&
                SkipSegment.decision(MediaSegmentKind.OUTRO, _state.value.skipSegmentActions) == SegmentDecision.AUTO_SKIP
            val triggerSecs = gateway.nextEpisodeTriggerRemainingSecs(durationSecs, outroStart, outroAutoSkipActive, autoplayDelaySecs)
            if (remainingSecs <= triggerSecs) {
                // The countdown runs against what will actually play: to the outro when the
                // credits are auto-skipped, otherwise to the end of the file.
                val playableSecs =
                    if (outroAutoSkipActive && outroStart != null) (outroStart - positionSecs).coerceAtLeast(0.0) else remainingSecs
                if (!autoplayEnabled) {
                    // Autoplay off: guard is inert, show the static card, no round trip.
                    showCountdownCard(next, playableSecs, positionTicks)
                    return
                }
                val requestedItemId = currentItemId
                stillWatchingEvaluationPending = true
                viewModelScope.launch {
                    val decision = runCatching { gateway.noteEpisodeFinished(clock.nowMs().toULong()) }
                        .getOrDefault(StillWatchingDecision.COUNTDOWN)
                    if (currentItemId != requestedItemId) return@launch // a newer session started meanwhile
                    // docs/12 §9: a hold established during this await means the
                    // viewer already glided to the end clamp -- honour that over
                    // a decision computed before the hold existed.
                    if (endClampHold) {
                        stillWatchingEvaluationPending = false
                        return@launch
                    }
                    stillWatchingEvaluationPending = false
                    when (decision) {
                        StillWatchingDecision.ASK_STILL_WATCHING -> {
                            endedWhileDeciding = false
                            _state.update {
                                it.copy(
                                    stillWatching = StillWatchingState(
                                        card = next,
                                        timeoutTotalSecs = stillWatchingTimeoutSecs,
                                        deadlineMs = clock.nowMs() + (stillWatchingTimeoutSecs * 1000).toLong(),
                                    ),
                                )
                            }
                            startStillWatchingTimeout()
                        }
                        StillWatchingDecision.COUNTDOWN -> {
                            // Already inside auto-skipped credits (a seek landed there):
                            // go straight to the next episode instead of a
                            // doomed-to-flash countdown card. endedWhileDeciding forces
                            // the same outcome if the file already hit EOF while this
                            // decision was in flight.
                            val outcome = NextUpCountdown.countdownOutcome(
                                outroAutoSkipActive = outroAutoSkipActive,
                                hasOutroSegment = outroStart != null,
                                insideOutro = playableSecs <= 0.0,
                                endedWhileDeciding = endedWhileDeciding,
                            )
                            endedWhileDeciding = false
                            when (outcome) {
                                CountdownOutcome.ADVANCE_NOW -> transitionTo(next)
                                CountdownOutcome.SHOW_CARD -> showCountdownCard(next, playableSecs, positionTicks)
                            }
                        }
                    }
                }
            }
        } else if (shown.autoAdvance) {
            val elapsed = NextUpCountdown.elapsedSecs(positionTicks, shown.countdownStartPositionTicks)
            if (NextUpCountdown.isComplete(shown.countdownTotalSecs, elapsed)) {
                playNext()
            }
        }
    }

    /** Populates the ordinary autoplay countdown card -- factored out of [evaluateNextUp] since
     * it's reached from both the autoplay-off path and a [StillWatchingDecision.COUNTDOWN] answer.
     * [playableSecs] is what is left to play before the file ends or the credits are skipped.
     */
    private fun showCountdownCard(next: Card, playableSecs: Double, positionTicks: Long) {
        val countdownTotal = gateway.nextEpisodeCountdownTotal(playableSecs, autoplayDelaySecs)
        _state.update {
            it.copy(
                nextUp = NextUpState(
                    card = next,
                    countdownTotalSecs = countdownTotal,
                    countdownStartPositionTicks = positionTicks,
                    autoAdvance = autoplayEnabled,
                ),
            )
        }
        // docs/18 preload: warm the next episode's handshake once, so autoplay's own prepare hits a
        // fresh cache instead of negotiating cold.
        if (preloadedNextUpItemId != next.id) {
            preloadedNextUpItemId = next.id
            viewModelScope.launch { runCatching { gateway.preloadPlayback(next.id) } }
        }
    }

    /** Starts (or restarts) the still-watching card's visible timeout -- called when the card
     * appears and by [noteUserInput] on every key press while shown. Replaces
     * [StillWatchingState.deadlineMs] with a fresh deadline each time (see that field's doc
     * comment).
     */
    private fun startStillWatchingTimeout() {
        stillWatchingTimeoutJob?.cancel()
        val timeoutMs = (stillWatchingTimeoutSecs * 1000).toLong()
        _state.update { state ->
            val shown = state.stillWatching ?: return@update state
            state.copy(stillWatching = shown.copy(deadlineMs = clock.nowMs() + timeoutMs))
        }
        stillWatchingTimeoutJob = viewModelScope.launch {
            delay(timeoutMs)
            stillWatchingStop()
        }
    }

    /** docs/12 §13's "Still watching?" input signal: call on every qualifying key event
     * (repeatCount == 0) so the Rust-side counters never trip while the viewer is present. Also
     * restarts the shown card's timeout, resetting its fill/numeral.
     */
    fun noteUserInput() {
        viewModelScope.launch { runCatching { gateway.notePlayerInput(clock.nowMs().toULong()) } }
        if (_state.value.stillWatching != null) {
            startStillWatchingTimeout()
        }
    }

    /** "Continue" on the still-watching card: resets the counters unconditionally via
     * [CoreGateway.resetStillWatching] (the only reset a Continue gets) and proceeds straight to
     * the next episode via [transitionTo], skipping the countdown.
     */
    fun stillWatchingContinue() {
        val card = _state.value.stillWatching?.card ?: return
        stillWatchingTimeoutJob?.cancel()
        stillWatchingTimeoutJob = null
        _state.update { it.copy(stillWatching = null) }
        viewModelScope.launch { runCatching { gateway.resetStillWatching(clock.nowMs().toULong()) } }
        transitionTo(card)
    }

    /** "Stop" on the still-watching card, or its timeout elapsing. Ends the finished
     * episode normally via [stopPlaybackOnce] but emits [PlaybackEvent.FinishToDetail] (not
     * [PlaybackEvent.Finish]) so the Activity routes to the next episode's detail page instead of
     * starting it.
     */
    fun stillWatchingStop() {
        val card = _state.value.stillWatching?.card ?: return
        stillWatchingTimeoutJob?.cancel()
        stillWatchingTimeoutJob = null
        _state.update { it.copy(stillWatching = null) }
        stopPlaybackOnce(reason = "still_watching")
        _events.tryEmit(PlaybackEvent.FinishToDetail(itemId = card.id))
    }

    /**
     * AutoSkip driver (docs/12 §14): runs every tick alongside [evaluateNextUp]. When the
     * playhead is inside a segment whose [SkipSegment.decision] resolves to
     * [SegmentDecision.AUTO_SKIP], seeks past it via [skipSegment] and emits
     * [PlaybackEvent.AutoSkipped] for the same Undo toast a manual skip shows. A no-op for
     * `PILL`/`NOTHING`, and, via [lastAutoSkipSegmentKey], for a segment already auto-skipped.
     */
    private fun evaluateAutoSkip(positionTicks: Long) {
        // A transition this tick already started (the next-up countdown ran out at the
        // credits): nothing left to skip in a session being torn down.
        if (sessionTransitionInFlight.get()) return
        val segment = SkipSegment.activeSegment(_state.value.mediaSegments, positionTicks) ?: return
        val decision = SkipSegment.decision(segment.segmentType, _state.value.skipSegmentActions)
        if (decision != SegmentDecision.AUTO_SKIP) return
        // Outro auto-skip defers to the still-watching decision instead of seeking
        // to EOF out from under it; deliberately doesn't mark lastAutoSkipSegmentKey,
        // so this retries every tick until the decision/card resolves.
        if (segment.segmentType == MediaSegmentKind.OUTRO &&
            (stillWatchingEvaluationPending || _state.value.stillWatching != null)
        ) {
            return
        }
        val key = segment.startTicks to segment.endTicks
        if (lastAutoSkipSegmentKey == key) return
        lastAutoSkipSegmentKey = key
        val before = skipSegment(segment)
        _events.tryEmit(PlaybackEvent.AutoSkipped(before, segment.segmentType))
    }

    /** Shared end-then-start transition: ends the current session (serialized via
     * [sessionTransitionInFlight]) then starts a fresh one for [card]'s item.
     * [playNext]/[playPrevious]/[playNextEpisode]/[stillWatchingContinue] all funnel through this.
     */
    private fun transitionTo(card: Card) {
        if (!sessionTransitionInFlight.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                replaceWith(card.id)
            } finally {
                sessionTransitionInFlight.set(false)
            }
        }
    }

    /** The end-then-start body [transitionTo] and [replaceItem] both guard with
     * [sessionTransitionInFlight], keyed by a raw item id since JellybeamCore owns a single reporting
     * session: the final stop must finish before `preparePlayback` replaces it.
     */
    private suspend fun replaceWith(itemId: String, startFromBeginning: Boolean = false) {
        stopPlaybackAndAwait()
        start(itemId, startFromBeginning)
    }

    /** `onNewIntent` path (docs/17 §2): a Play/Resume/deep-link intent to an already-alive
     * [tv.jellybeam.player.PlaybackActivity] swaps the item via [transitionTo]'s stop-then-start,
     * keyed by raw item id. A no-op while another transition is in flight.
     */
    fun replaceItem(itemId: String, startFromBeginning: Boolean = false) {
        if (!sessionTransitionInFlight.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                replaceWith(itemId, startFromBeginning)
            } finally {
                sessionTransitionInFlight.set(false)
            }
        }
    }

    /** Autoplay/"Play now": advances to the next-up card's episode via [transitionTo]. A no-op if
     * no next-up card is currently shown.
     */
    fun playNext() {
        val next = _state.value.nextUp?.card ?: return
        transitionTo(next)
    }

    /** OSD "Previous episode" transport button (docs/12 §8) -- mirrors [playNext], against
     * [previousEpisode]. A no-op when none is known.
     */
    fun playPrevious() {
        val previous = previousEpisode ?: return
        transitionTo(previous)
    }

    /** OSD "Next episode" transport button (docs/12 §8) -- against [nextEpisode] directly rather
     * than [playNext]'s countdown-gated card, so it works before the threshold is crossed. A no-op
     * when none is known.
     */
    fun playNextEpisode() {
        val next = nextEpisode ?: return
        transitionTo(next)
    }

    /**
     * Back while the next-up card is shown dismisses it for the rest of
     * this session rather than exiting playback --
     * [tv.jellybeam.player.PlaybackScreen]'s `BackHandler` calls this instead
     * of finishing when [PlaybackUiState.nextUp] is non-null.
     */
    fun dismissNextUp() {
        nextUpDismissed = true
        _state.update { it.copy(nextUp = null) }
    }

    /**
     * Automatic track selection (docs/09 step 3): the first time this session's player announces
     * [tracks], maps them via [TrackMapping.toTrackInfos], resolves a decision via
     * [CoreGateway.resolveTracks], and applies it (see [trackSelectionApplied]'s doc for the
     * once-per-session rule). A no-op when [tracks] carries nothing to decide on, without setting
     * [trackSelectionApplied] so a later, more complete announcement still gets a chance. Same
     * generation-guard shape as [maybeFallBackToTranscode] (re-checked alongside
     * [manualTrackChoiceMade]) since a session change or manual pick can land mid-flight.
     */
    private fun resolveTrackSelectionOnce(tracks: Tracks) {
        if (trackSelectionApplied) return
        val trackInfos = TrackMapping.toTrackInfos(tracks)
        if (trackInfos.isEmpty()) return
        trackSelectionApplied = true
        val seriesId = currentSeriesId
        val generation = sessionGeneration
        trackSelectionJob = viewModelScope.launch {
            val decision = runCatching { gateway.resolveTracks(seriesId, trackInfos) }.getOrNull() ?: return@launch
            if (generation != sessionGeneration || manualTrackChoiceMade) return@launch // see this method's own doc comment
            playerHolder.applyTrackDecision(decision, tracks)
            _state.update {
                it.copy(nonDefaultTrackActive = computeNonDefaultTrackActive(applyDecisionToTrackInfos(trackInfos, decision)))
            }
        }
    }

    /** Applies [decision] onto [tracks]' `isSelected` flags without touching the player -- an
     * "effective" snapshot feeding [computeNonDefaultTrackActive] the result of a decision rather
     * than Media3's pre-decision snapshot. Shared by
     * [resolveTrackSelectionOnce]/[chooseAudio]/[chooseSubtitle].
     */
    private fun applyDecisionToTrackInfos(tracks: List<TrackInfo>, decision: TrackDecisionFfi): List<TrackInfo> =
        tracks.map { info ->
            when (info.kind) {
                TrackKindFfi.AUDIO -> if (decision.audioTrackId != null) info.copy(isSelected = info.id == decision.audioTrackId) else info
                TrackKindFfi.SUBTITLE -> when {
                    decision.subtitleTrackId != null -> info.copy(isSelected = info.id == decision.subtitleTrackId)
                    decision.subtitleAction == SubtitleActionFfi.OFF -> info.copy(isSelected = false)
                    else -> info
                }
                else -> info
            }
        }

    /**
     * [PlaybackUiState.nonDefaultTrackActive]'s definition: `true` iff a selected audio track
     * isn't the default AND some audio track is actually flagged default (many files flag none,
     * so without this guard ordinary Direct Play would light the dot spuriously), OR a selected
     * subtitle isn't the default, OR no subtitle is selected despite one being marked default.
     * Subtitles need no guard: they start OFF by default, so any selection is a real deviation.
     */
    private fun computeNonDefaultTrackActive(tracks: List<TrackInfo>): Boolean {
        val audioTracks = tracks.filter { it.kind == TrackKindFfi.AUDIO }
        val subtitles = tracks.filter { it.kind == TrackKindFfi.SUBTITLE }
        val selectedAudio = audioTracks.firstOrNull { it.isSelected }
        val selectedSubtitle = subtitles.firstOrNull { it.isSelected }
        val audioNonDefault = selectedAudio != null && !selectedAudio.isDefault && audioTracks.any { it.isDefault }
        val subtitleNonDefault = selectedSubtitle != null && !selectedSubtitle.isDefault
        val subtitleDefaultTurnedOff = selectedSubtitle == null && subtitles.any { it.isDefault }
        return audioNonDefault || subtitleNonDefault || subtitleDefaultTurnedOff
    }

    // -- In-player track picker (docs/09 slice 3b) ------------------------

    /** Opens the track picker via [buildTrackPickerState] -- a no-op if no [Tracks] announced yet.
     * Safe to call again while open: rebuilds fresh each time, which is also how a stale optimistic
     * flip from [chooseAudio]/[chooseSubtitle] self-corrects.
     */
    fun openTrackPicker() {
        val tracks = playerHolder.currentTracks ?: return
        _state.update {
            it.copy(trackPicker = buildTrackPickerState(tracks), speedMenuOpen = false, chaptersMenuOpen = false)
        }
    }

    /** Closes the picker -- the Back priority chain calls this first, ahead of next-up dismissal
     * and exit, whenever the picker is open.
     */
    fun closeTrackPicker() {
        _state.update { it.copy(trackPicker = null) }
    }

    private fun buildTrackPickerState(tracks: Tracks): TrackPickerState {
        val infos = TrackMapping.toTrackInfos(tracks)
        val audio = infos.filter { it.kind == TrackKindFfi.AUDIO }
        val subtitle = infos.filter { it.kind == TrackKindFfi.SUBTITLE }

        val audioChoices = audio.mapIndexed { index, info ->
            TrackChoice(
                id = info.id,
                label = trackChoiceLabel(info, index),
                selected = info.isSelected,
                meta = trackChoiceMeta(info),
                isDefault = info.isDefault,
            )
        }

        val subtitleChoices = buildList {
            // "Off" is selected exactly when no real subtitle is selected -- matches
            // applyTrackDecision's OFF branch, which disables the whole text type.
            add(TrackChoice(id = TRACK_PICKER_SUBTITLE_OFF_ID, label = "Off", selected = subtitle.none { it.isSelected }))
            subtitle.forEachIndexed { index, info ->
                add(
                    TrackChoice(
                        id = info.id,
                        label = trackChoiceLabel(info, index),
                        selected = info.isSelected,
                        meta = trackChoiceMeta(info),
                        isDefault = info.isDefault,
                    ),
                )
            }
        }

        return TrackPickerState(audioTracks = audioChoices, subtitleTracks = subtitleChoices)
    }

    private fun trackChoiceLabel(info: TrackInfo, indexInKind: Int): String =
        info.title ?: info.lang ?: "Track ${indexInKind + 1}"

    /** [TrackChoice.meta] -- `"lang codec"`, dropping whichever half [info] doesn't have; `null` if
     * neither.
     */
    private fun trackChoiceMeta(info: TrackInfo): String? =
        listOfNotNull(info.lang, info.codec).joinToString(" ").ifBlank { null }

    /**
     * Chooses [id] as the audio track (docs/09 slice 3b): applies it via
     * [PlaybackPlayer.applyTrackDecision] (subtitles untouched) and, only when this session has a
     * series, remembers the choice via [CoreGateway.rememberTrackChoice]. A Movie applies the
     * override but skips remembering (nothing to key on). A no-op if [id] doesn't resolve to a
     * currently-known audio track. Flips [TrackChoice.selected] optimistically in the held
     * [TrackPickerState] rather than waiting for a real `onTracksChanged`.
     */
    fun chooseAudio(id: Long) {
        val tracks = playerHolder.currentTracks ?: return
        val trackInfos = TrackMapping.toTrackInfos(tracks)
        val info = trackInfos.firstOrNull { it.kind == TrackKindFfi.AUDIO && it.id == id } ?: return

        // A manual pick always wins -- cancel any pending automatic resolution
        // outright rather than relying solely on its own after-the-fact recheck.
        trackSelectionJob?.cancel()
        trackSelectionJob = null
        manualTrackChoiceMade = true
        val decision = TrackDecisionFfi(audioTrackId = id, subtitleAction = SubtitleActionFfi.LEAVE, subtitleTrackId = null)
        playerHolder.applyTrackDecision(decision, tracks)
        rememberTrackChoiceIfSeries(TrackKindFfi.AUDIO, gateway.trackPrefKeyOf(info))
        _state.update { state ->
            state.copy(
                trackPicker = state.trackPicker?.copy(
                    audioTracks = state.trackPicker.audioTracks.map { it.copy(selected = it.id == id) },
                ),
                nonDefaultTrackActive = computeNonDefaultTrackActive(applyDecisionToTrackInfos(trackInfos, decision)),
            )
        }
    }

    /** Chooses [id] as the subtitle track, or [TRACK_PICKER_SUBTITLE_OFF_ID] to disable subtitles
     * -- see [chooseAudio]'s doc comment for the shared design. "Off" is itself a real remembered
     * choice (docs/09), so it still calls [CoreGateway.rememberTrackChoice] with `trackKey = null`.
     */
    fun chooseSubtitle(id: Long) {
        val tracks = playerHolder.currentTracks ?: return
        val trackInfos = TrackMapping.toTrackInfos(tracks)

        // See chooseAudio's comment on why this cancels the pending resolution outright.
        trackSelectionJob?.cancel()
        trackSelectionJob = null
        manualTrackChoiceMade = true
        val decision: TrackDecisionFfi
        if (id == TRACK_PICKER_SUBTITLE_OFF_ID) {
            decision = TrackDecisionFfi(audioTrackId = null, subtitleAction = SubtitleActionFfi.OFF, subtitleTrackId = null)
            playerHolder.applyTrackDecision(decision, tracks)
            rememberTrackChoiceIfSeries(TrackKindFfi.SUBTITLE, null)
        } else {
            val info = trackInfos.firstOrNull { it.kind == TrackKindFfi.SUBTITLE && it.id == id } ?: return
            decision = TrackDecisionFfi(audioTrackId = null, subtitleAction = SubtitleActionFfi.LEAVE, subtitleTrackId = id)
            playerHolder.applyTrackDecision(decision, tracks)
            rememberTrackChoiceIfSeries(TrackKindFfi.SUBTITLE, gateway.trackPrefKeyOf(info))
        }

        _state.update { state ->
            state.copy(
                trackPicker = state.trackPicker?.copy(
                    subtitleTracks = state.trackPicker.subtitleTracks.map { it.copy(selected = it.id == id) },
                ),
                nonDefaultTrackActive = computeNonDefaultTrackActive(applyDecisionToTrackInfos(trackInfos, decision)),
            )
        }
    }

    /** Shared by [chooseAudio]/[chooseSubtitle] -- see [chooseAudio]'s own doc comment for the
     * Movie skip-remember rule.
     */
    private fun rememberTrackChoiceIfSeries(kind: TrackKindFfi, trackKey: String?) {
        val seriesId = currentSeriesId ?: return
        viewModelScope.launch { runCatching { gateway.rememberTrackChoice(seriesId, kind, trackKey) } }
    }

    private fun updatePlayState() {
        val isActuallyPlaying = playerHolder.isPlaying
        val playbackState = playerHolder.playbackState
        val playWhenReady = playerHolder.playWhenReady
        if (isActuallyPlaying) playbackWasActive = true

        // prepare() transitions IDLE -> BUFFERING before its network read succeeds,
        // so BUFFERING is not recovery (clearing there caused an infinite retry
        // loop); READY is the first state proving the source was prepared.
        if (_state.value.reconnecting != null && playbackState == Player.STATE_READY) {
            clearReconnectState()
        }

        // isPlaying is false for both a deliberate pause and a buffering stall;
        // playWhenReady is the user-intent signal -- only false means paused.
        val isPaused = !playWhenReady &&
            playbackState != Player.STATE_IDLE &&
            playbackState != Player.STATE_ENDED
        val isBuffering = playbackState == Player.STATE_BUFFERING
        val showsPauseAction = playWhenReady && playbackState != Player.STATE_ENDED
        _state.update { it.copy(isPlaying = showsPauseAction, isPaused = isPaused, isBuffering = isBuffering) }

        // docs/12 §9: the viewer resuming on their own ends the hold explicitly,
        // rather than waiting for a seek that may never come.
        if (playWhenReady) {
            endClampHold = false
            endHoldReportTicks = null
        }

        if (ProgressReportGate.shouldReportPaused(lastReportedPaused, isPaused)) {
            lastReportedPaused = isPaused
            viewModelScope.launch { runCatching { gateway.reportPaused(isPaused) } }
        }

        // docs/12 §15: a stall only gets the pill while intending to play.
        updateBufferingTicker(isBuffering && playWhenReady)
    }

    /**
     * Resolves Media3's terminal state -- the progress ticker normally starts autoplay before
     * EOF, but ENDED can beat its next tick. Preserves autoplay when its card is eligible;
     * otherwise closes the reporting session and tells the Activity to leave playback.
     * [sessionTransitionInFlight] doubles as the play-next lock, making natural EOF and a
     * simultaneous countdown/Enter mutually exclusive. A no-op while a "still watching?" card is
     * shown: it must stay up until answered or timed out.
     */
    private fun handlePlaybackEnded() {
        if (_state.value.stillWatching != null) return
        // The still-watching decision may still be in flight when outro auto-skip's
        // deferral lets the file reach genuine EOF under it -- wait for that decision;
        // endedWhileDeciding tells evaluateNextUp's launch there's no live position left.
        if (stillWatchingEvaluationPending) {
            endedWhileDeciding = true
            return
        }
        if (_state.value.nextUp?.autoAdvance == true) {
            playNext()
            return
        }
        if (!sessionTransitionInFlight.compareAndSet(false, true)) return
        if (sessionEnded.get()) return

        stopPlaybackOnce(reason = "ended")
        _events.tryEmit(PlaybackEvent.Finish)
    }

    /**
     * Starts/stops [bufferingTickerJob] to match [shouldShowBuffering], feeding
     * [PlaybackUiState.bufferingInfo]. A no-op if the ticker's running state already matches
     * (Media3's three related callbacks can each call this for the same real transition). The
     * first value writes synchronously so the pill shows a real number immediately.
     */
    private fun updateBufferingTicker(shouldShowBuffering: Boolean) {
        if (shouldShowBuffering && suppressSeekBuffering) return

        val alreadyRunning = bufferingTickerJob?.isActive == true
        if (shouldShowBuffering == alreadyRunning) return

        if (shouldShowBuffering) {
            _state.update { it.copy(bufferingInfo = currentBufferingInfo()) }
            bufferingTickerJob = viewModelScope.launch {
                while (isActive) {
                    delay(BUFFERING_TICK_MS)
                    _state.update { it.copy(bufferingInfo = currentBufferingInfo()) }
                }
            }
        } else {
            bufferingTickerJob?.cancel()
            bufferingTickerJob = null
            _state.update { it.copy(bufferingInfo = null) }
        }
    }

    /** Starts/restarts the UI-only grace for a viewer-initiated seek. */
    private fun beginSeekBufferingGrace() {
        // A seek during an already-visible genuine stall must not hide that diagnosis.
        if (_state.value.bufferingInfo != null) return

        seekBufferingGraceJob?.cancel()
        suppressSeekBuffering = true
        seekBufferingGraceJob = viewModelScope.launch {
            delay(USER_SEEK_BUFFERING_GRACE_MS)
            suppressSeekBuffering = false
            seekBufferingGraceJob = null
            if (playerHolder.playbackState == Player.STATE_BUFFERING && playerHolder.playWhenReady) {
                updateBufferingTicker(true)
            }
        }
    }

    private fun clearSeekBufferingGrace() {
        seekBufferingGraceJob?.cancel()
        seekBufferingGraceJob = null
        suppressSeekBuffering = false
    }

    /** Builds one live [BufferingInfo] reading against whichever resume threshold
     * [PlaybackUiState.hasRenderedFirstFrame] picks, plus the player's live bandwidth estimate
     * verbatim.
     */
    private fun currentBufferingInfo(): BufferingInfo {
        val bufferAheadMs = PlaybackTicks.ticksToMs(
            (playerHolder.bufferedPositionTicks() - playerHolder.currentPositionTicks()).coerceAtLeast(0L),
        )
        val resumeThresholdMs = if (_state.value.hasRenderedFirstFrame) {
            BufferingInfo.REBUFFER_RESUME_THRESHOLD_MS
        } else {
            BufferingInfo.INITIAL_RESUME_THRESHOLD_MS
        }
        return BufferingInfo(
            percent = BufferingInfo.percentTowardResume(bufferAheadMs, resumeThresholdMs),
            bytesPerSec = playerHolder.bandwidthBytesPerSecond(),
        )
    }

    fun togglePlayPause() = playerHolder.togglePlayPause()

    /** Sets the live playback rate (docs/12 §12) on both the player and
     * [PlaybackUiState.playbackRate] so the pill's label never drifts. Doesn't close
     * [PlaybackUiState.speedMenuOpen] -- it stays open after a selection per spec.
     */
    fun setPlaybackRate(rate: Float) {
        playerHolder.setPlaybackRate(rate)
        _state.update { it.copy(playbackRate = rate) }
    }

    /**
     * Performs the seek and resolves this session's trickplay scrub-preview tile for the same
     * target, if any. Clamps against [positionTicks]/[PlaybackUiState.durationTicks] rather than a
     * post-seek player read, keeping this deterministically testable against fakes.
     * [resolveTrickplay] is false for a hidden-OSD D-pad seek. Returns `null` (clear any preview
     * showing) whenever [trickplayMeta] is absent or [CoreGateway.trickplayLocate] can't resolve.
     */
    fun seek(deltaMs: Long, resolveTrickplay: Boolean = true): TrickplaySeekResult? {
        val targetMs = performSeek(deltaMs)

        if (!resolveTrickplay) return null
        val meta = trickplayMeta ?: return null
        // docs/12 §11: biased by travel direction, so the frame shown is never behind where
        // this seek lands.
        val direction = if (deltaMs >= 0L) GlideDirection.FORWARD else GlideDirection.BACK
        val tile = gateway.trickplayLocateBiased(meta, targetMs.toULong(), direction) ?: return null
        previewer?.show(tile) // hold-last: the previous tile stays until this one decodes
        return TrickplaySeekResult(targetMs, tile)
    }

    /**
     * Hold-to-seek's tap primitive (docs/12 §9): like [seek] with `resolveTrickplay = false`, but
     * returns the clamped target ms synchronously for `GlideSeek.keyDown`'s tap-latency
     * requirement. Caps at [glideEndClampMs] rather than raw duration, so this initial tap alone
     * can't reach true EOF -- only a committed end-clamp seek should. [seek] is unaffected.
     */
    fun tapSeek(deltaMs: Long): Long {
        val durationMs = _state.value.durationTicks?.let { PlaybackTicks.ticksToMs(it) }
        val endClampMs = durationMs?.let { gateway.glideEndClampMs(it.toULong()).toLong() }
        return performSeek(deltaMs, extraClampMs = endClampMs)
    }

    /**
     * Shared body of [seek]/[tapSeek]: clamps, issues the seek, returns the target ms.
     * [extraClampMs] (only [tapSeek]'s end clamp) caps the target below the ordinary duration
     * clamp when set. Leaves [endClampHold] alone, but while active keeps [endHoldReportTicks]
     * current, same as [seekToAbsoluteTicks].
     */
    private fun performSeek(deltaMs: Long, extraClampMs: Long? = null): Long {
        val currentMs = PlaybackTicks.ticksToMs(_positionTicks.value)
        val durationMs = _state.value.durationTicks?.let { PlaybackTicks.ticksToMs(it) }
        var targetMs = SeekMath.clampSeekTarget(currentMs, deltaMs, durationMs)
        val boundByExtraClamp = extraClampMs != null && targetMs > extraClampMs
        if (boundByExtraClamp) targetMs = extraClampMs!!

        beginSeekBufferingGrace()
        // The player clamps a raw delta against its own exact live position; only re-derive the
        // delta here when the extra clamp binds, the one case the target needs a bound it lacks.
        playerHolder.seekBy(if (boundByExtraClamp) targetMs - currentMs else deltaMs)
        _positionTicks.value = PlaybackTicks.msToTicks(targetMs)
        noteHoldSeekTarget(targetMs)
        return targetMs
    }

    /** While the end-clamp hold is active, a seek landing strictly below the clamp becomes the
     * exit-report position; one landing at the clamp itself leaves the protected position alone. */
    private fun noteHoldSeekTarget(targetMs: Long) {
        if (!endClampHold) return
        val durationMs = _state.value.durationTicks?.let { PlaybackTicks.ticksToMs(it) } ?: return
        if (targetMs < gateway.glideEndClampMs(durationMs.toULong()).toLong()) {
            endHoldReportTicks = PlaybackTicks.msToTicks(targetMs)
        }
    }

    /**
     * Hold-to-seek's commit (docs/12 §9). [endClamped] mirrors `GlideOutcome.Commit.endClamped` --
     * when set, the target is the true end-of-file clamp, and this pauses before seeking (rather
     * than running past it), holding off next-up/autoplay/watched writes via [endClampHold] until
     * the viewer resumes. A non-end commit never clears the hold. The true pre-seek position is
     * captured into [endHoldReportTicks] before [seekToAbsoluteTicks] moves position to the clamp,
     * so exiting later reports that instead of the clamp itself.
     */
    fun commitGlide(targetMs: Long, endClamped: Boolean) {
        if (endClamped) {
            // First entry captures where the viewer really was; a repeat end-clamp commit while
            // already holding keeps that.
            if (!endClampHold) endHoldReportTicks = _positionTicks.value
            playerHolder.pause()
        }
        seekToAbsoluteTicks(PlaybackTicks.msToTicks(targetMs))
        if (endClamped) endClampHold = true
    }

    /** Hold-to-seek's chapter input (docs/12 §9): every chapter start in ms, for the `GlideSeek`
     * this session's [GlideSeekController] constructs. */
    fun glideChapterStartsMs(): List<Long> = _state.value.chapters.map { PlaybackTicks.ticksToMs(it.startPositionTicks) }

    /** [GlideHost.sampleTile] (docs/12 §11): the dwell-gated Rust sample, shown as soon as it
     * decodes. */
    fun glideSampleTile(targetMs: Long, nowMs: Long, lastSampleMs: Long?, direction: GlideDirection): TrickplayTileFfi? {
        val meta = trickplayMeta ?: return null
        val tile = gateway.trickplayGlideSample(meta, targetMs.toULong(), nowMs.toULong(), lastSampleMs?.toULong(), direction) ?: return null
        previewer?.show(tile)
        return tile
    }

    /** [GlideHost.wantSheets]: Rust's sheet want-list handed to the transport. */
    fun glideWantSheets(targetMs: Long, rate: Int, direction: GlideDirection) {
        val meta = trickplayMeta ?: return
        val previewer = previewer ?: return
        val sheets = gateway.trickplayGlideSheets(meta, targetMs.toULong(), rate.toUInt(), direction)
        previewer.want(sheets.map { it.toInt() }, targetMs, direction)
    }

    /** [GlideHost.previewStart]: blank, then prime the tap target's tile so glide entry has it. */
    fun glidePreviewStart(tapTargetMs: Long, direction: GlideDirection) {
        val previewer = previewer ?: return
        previewer.reset()
        val meta = trickplayMeta ?: return
        gateway.trickplayLocateBiased(meta, tapTargetMs.coerceAtLeast(0L).toULong(), direction)?.let(previewer::show)
    }

    /** [GlideHost.releaseTarget]: the committed target's tile jumps every queue. */
    fun glideReleaseTarget(targetMs: Long, direction: GlideDirection) {
        val meta = trickplayMeta ?: return
        val tile = gateway.trickplayLocateBiased(meta, targetMs.toULong(), direction) ?: return
        previewer?.prioritize(tile)
    }


    /**
     * Seeks straight to [targetTicks] -- the shared primitive behind
     * [jumpToChapter]/[skipSegment]/[undoSkip]. Computes the equivalent delta against
     * [_positionTicks] and clamps it, since [PlaybackPlayer] has no absolute-seek entry point; the
     * clamp is defensive since every target already comes from server data inside this item's
     * duration. Updates [_positionTicks] optimistically so the OSD moves immediately, and keeps
     * [endHoldReportTicks] current while [endClampHold] is active.
     */
    private fun seekToAbsoluteTicks(targetTicks: Long) {
        val currentMs = PlaybackTicks.ticksToMs(_positionTicks.value)
        val durationMs = _state.value.durationTicks?.let { PlaybackTicks.ticksToMs(it) }
        val targetMs = SeekMath.clampSeekTarget(currentMs, PlaybackTicks.ticksToMs(targetTicks) - currentMs, durationMs)
        beginSeekBufferingGrace()
        playerHolder.seekBy(targetMs - currentMs)
        _positionTicks.value = PlaybackTicks.msToTicks(targetMs)
        noteHoldSeekTarget(targetMs)
    }

    /** Prev/next-chapter transport (docs/12; also Page Up/Page Down, [tv.jellybeam.player.PageKeys])
     * -- a no-op with no markers or nothing left to jump to. Returns the seeked-to ticks, or `null`
     * if nothing happened.
     */
    fun jumpToChapter(forward: Boolean): Long? {
        val target = Chapters.jumpTargetTicks(_state.value.chapters, _positionTicks.value, forward) ?: return null
        seekToAbsoluteTicks(target)
        return target
    }

    /** Chapters menu row activation (docs/12 §12): seeks to [startPositionTicks] and closes the
     * menu, same "pick and dismiss" shape as a normal menu row.
     */
    fun jumpToChapterStart(startPositionTicks: Long) {
        seekToAbsoluteTicks(startPositionTicks)
        _state.update { it.copy(chaptersMenuOpen = false) }
    }

    // -- Speed menu / chapters menu (docs/12 §8, §12) --------------------

    /** Opens the speed menu -- mutually exclusive with the chapters menu/track picker/both info
     * sheets, same one-thing-at-a-time convention as [openLibraryInfoOverlay]. Cancels an in-flight
     * library-info request too, since its result would populate a panel nobody can see.
     */
    fun openSpeedMenu() {
        libraryInfoJob?.cancel()
        libraryInfoJob = null
        _state.update {
            it.copy(
                speedMenuOpen = true,
                chaptersMenuOpen = false,
                trackPicker = null,
                statsSheetLive = null,
                libraryInfoOverlay = null,
            )
        }
    }

    fun closeSpeedMenu() {
        _state.update { it.copy(speedMenuOpen = false) }
    }

    /** See [openSpeedMenu]'s own doc comment -- identical mutual-exclusion shape, the other
     * direction.
     */
    fun openChaptersMenu() {
        libraryInfoJob?.cancel()
        libraryInfoJob = null
        _state.update {
            it.copy(
                chaptersMenuOpen = true,
                speedMenuOpen = false,
                trackPicker = null,
                statsSheetLive = null,
                libraryInfoOverlay = null,
            )
        }
    }

    fun closeChaptersMenu() {
        _state.update { it.copy(chaptersMenuOpen = false) }
    }

    /** Skip-intro/credits (docs/12 §14) -- seeks to [segment]'s end and returns the pre-skip
     * position for the Undo toast ([undoSkip]). Never re-validates [segment] is still active; a
     * backwards/no-op seek is harmless. Shared by the manual pill and [evaluateAutoSkip].
     */
    fun skipSegment(segment: MediaSegment): Long {
        val before = _positionTicks.value
        seekToAbsoluteTicks(segment.endTicks)
        return before
    }

    /** Undoes a [skipSegment] call -- seeks back to [preSkipPositionTicks] (docs/12 §14's Undo
     * toast).
     */
    fun undoSkip(preSkipPositionTicks: Long) {
        seekToAbsoluteTicks(preSkipPositionTicks)
    }

    /** Opens the playback stats sheet (docs/12 §17): a no-op if [playbackOsdDetail] hasn't resolved
     * yet. Static content is built directly by [tv.jellybeam.player.PlaybackScreen] via
     * [StatsSheetFormat.build]; this only seeds the live half, refreshed by
     * [refreshStatsSheetLive].
     */
    fun openInfoOverlay() {
        if (playbackOsdDetail == null) return
        _state.update {
            it.copy(
                statsSheetLive = playerHolder.livePlaybackStats(),
                trackPicker = null,
                libraryInfoOverlay = null,
                speedMenuOpen = false,
                chaptersMenuOpen = false,
            )
        }
    }

    /** docs/09: re-reads the subtitle style on resume, so a Settings change made from PiP or the
     * background reaches the running session; a failed read keeps the current style. */
    fun refreshSubtitleStyle() {
        viewModelScope.launch {
            val settings = try {
                gateway.getSettings()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@launch
            }
            _state.update { it.copy(subtitleStyle = settings.subtitleStyle()) }
        }
    }

    /** See [PlaybackUiState.statsSheetLive]'s own doc comment -- a no-op while the sheet is closed.
     */
    private fun refreshStatsSheetLive() {
        _state.update { state ->
            if (state.statsSheetLive == null) return@update state
            state.copy(statsSheetLive = playerHolder.livePlaybackStats())
        }
    }

    /** Closes the playback stats sheet -- [tv.jellybeam.player.PlaybackScreen]'s Back priority chain
     * calls this first, same tier as [closeTrackPicker].
     */
    fun closeInfoOverlay() {
        _state.update { it.copy(statsSheetLive = null) }
    }

    /** The stats sheet's SUBTITLES row (docs/12 §17): selected subtitle's title (`null` for Off)
     * plus track count, derived from the same [PlaybackPlayer.currentTracks] snapshot
     * [buildTrackPickerState] reads. `null to 0` before any [Tracks] announced.
     */
    fun currentSubtitleSelection(): Pair<String?, Int> {
        val tracks = playerHolder.currentTracks ?: return null to 0
        val subtitles = TrackMapping.toTrackInfos(tracks).filter { it.kind == TrackKindFfi.SUBTITLE }
        val selected = subtitles.firstOrNull { it.isSelected }
        val title = selected?.let { trackChoiceLabel(it, subtitles.indexOf(it)) }
        return title to subtitles.size
    }

    /** Opens the library-info panel and starts its live detail request; the synchronous Loading
     * state gives Select immediate feedback even on a slow server. Idempotent; an old session's
     * result can never populate a new one. Builds via [LibraryInfoFormat.buildSheet] (docs/12 §17).
     */
    fun openLibraryInfoOverlay() {
        if (_state.value.libraryInfoOverlay != null) return
        val requestedItemId = currentItemId
        _state.update {
            it.copy(
                trackPicker = null,
                statsSheetLive = null,
                libraryInfoOverlay = LibraryInfoOverlayState.Loading,
                speedMenuOpen = false,
                chaptersMenuOpen = false,
            )
        }
        libraryInfoJob?.cancel()
        libraryInfoJob = viewModelScope.launch {
            val result = try {
                LibraryInfoOverlayState.Content(LibraryInfoFormat.buildSheet(gateway.getItemDetail(requestedItemId)))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                LibraryInfoOverlayState.Unavailable
            }
            if (currentItemId != requestedItemId || _state.value.libraryInfoOverlay == null) return@launch
            _state.update { it.copy(libraryInfoOverlay = result) }
            libraryInfoJob = null
        }
    }

    /** Cancels an in-flight on-demand request as well as closing its panel. */
    fun closeLibraryInfoOverlay() {
        libraryInfoJob?.cancel()
        libraryInfoJob = null
        _state.update { it.copy(libraryInfoOverlay = null) }
    }

    /**
     * Normal exit path (activity finish/back/`onStop`, [onCleared] as the guaranteed fallback) --
     * reports the final position for Home/Detail's watched-state writeback. Exactly once: a no-op
     * if [abandonPlaybackOnce] or a prior call already closed it out. [reason] is docs/21 §2.1's
     * `playback.stop` tag -- a fixed allow-list name describing why this call happened, never
     * free text.
     */
    fun stopPlaybackOnce(reason: String = "normal") {
        val snapshot = endSessionForStop() ?: return
        recordPlaybackStop(snapshot, reason)
        reportScope.launch {
            runCatching { gateway.stopPlayback(snapshot.playSessionId, snapshot.ticks) }
            onStopReported()
        }
    }

    /** Ends the current session and waits for its core reporting state to close. */
    private suspend fun stopPlaybackAndAwait(reason: String = "transition") {
        val snapshot = endSessionForStop() ?: return
        recordPlaybackStop(snapshot, reason)
        runCatching { gateway.stopPlayback(snapshot.playSessionId, snapshot.ticks) }
        onStopReported()
    }

    /** docs/21 §2.1's `playback.stop` event, shared by [stopPlaybackOnce]/[stopPlaybackAndAwait].
     */
    private fun recordPlaybackStop(snapshot: StopSnapshot, reason: String) {
        AppGraph.diag.event("playback.stop") {
            ms("posMs", PlaybackTicks.ticksToMs(snapshot.ticks))
            tag("reason", reason)
        }
    }

    /**
     * The session identity and final position a stop/abandon call is for, captured synchronously
     * at the exit decision: [stopPlaybackOnce]/[abandonPlaybackOnce] dispatch onto [reportScope],
     * which can run after a newer session started ([currentPlan]/position reassigned), so reading
     * either lazily inside the dispatched lambda would race that.
     */
    private data class StopSnapshot(val playSessionId: String, val ticks: Long)

    /**
     * Shared job-cancellation/menu-closing half of exit cleanup, run by both
     * [endSessionForStop] and [abandonPlaybackOnce] right after their exactly-once guard. Cancels
     * [reportTickerJob], [bufferingTickerJob], the seek grace, [reconnectJob] (a Back press during
     * Reconnecting must cancel any pending retry, not race it), [libraryInfoJob], and
     * [trackSelectionJob]. Also closes the speed/chapters menus, which must not survive it.
     */
    private fun cancelSessionJobsAndCloseMenus() {
        reportTickerJob?.cancel()
        bufferingTickerJob?.cancel()
        clearSeekBufferingGrace()
        reconnectJob?.cancel()
        libraryInfoJob?.cancel()
        trackSelectionJob?.cancel()
        _state.update { it.copy(speedMenuOpen = false, chaptersMenuOpen = false) }
    }

    /** Player-side, exactly-once half shared by normal exits and play-next transitions. */
    private fun endSessionForStop(): StopSnapshot? {
        if (!sessionEnded.compareAndSet(false, true)) return null
        // Read here synchronously -- see StopSnapshot's doc comment. `null` means
        // start() never installed a plan, so the native call is skipped entirely.
        val playSessionId = currentPlan?.playSessionId
        cancelSessionJobsAndCloseMenus()
        // During a reconnect episode the player is idle after the error, so a live
        // position read is untrustworthy -- report the remembered position instead.
        val liveTicks = playerHolder.currentPositionTicks()
        val lastKnownTicks = _positionTicks.value
        val currentTicks = reconnectPositionTicks
            ?: liveTicks.takeIf { it > 0L || lastKnownTicks <= 0L }
            ?: lastKnownTicks
        // docs/12 §9: exiting an active end-clamp hold must report where the viewer
        // actually reached, never the clamp point itself.
        val ticks = if (endClampHold) endHoldReportTicks ?: currentTicks else currentTicks
        playerHolder.stopAndClear()
        return playSessionId?.let { StopSnapshot(it, ticks) }
    }

    /** Error path -- discards the session without a final report. Exactly once, same guard as
     * [stopPlaybackOnce].
     */
    private fun abandonPlaybackOnce() {
        if (!sessionEnded.compareAndSet(false, true)) return
        // Captured synchronously before dispatching -- see StopSnapshot's doc comment.
        val playSessionId = currentPlan?.playSessionId
        cancelSessionJobsAndCloseMenus()
        playerHolder.stopAndClear()
        // No plan means prepare never completed -- nothing to abandon.
        if (playSessionId != null) {
            reportScope.launch { runCatching { gateway.abandonPlayback(playSessionId) } }
        }
    }

    /** A failure before playback ever became active has no trustworthy position, so it abandons;
     * once active, the position is real and a final Stopped report preserves it like Back/HOME
     * does.
     */
    private fun finishAfterFatalPlaybackError(errorCode: String) {
        // docs/21 §2.1: one line per fatal error, before deciding stop vs. abandon.
        AppGraph.diag.event("player.error") {
            tag("code", errorCode)
            ms("posMs", PlaybackTicks.ticksToMs(_positionTicks.value))
        }
        if (playbackWasActive) stopPlaybackOnce(reason = "error") else abandonPlaybackOnce()
    }

    /** Guaranteed backstop: fires when the owning Activity is finishing, independent of whether
     * `onStop`/`onDestroy` already ran [stopPlaybackOnce]. Listener removal is unconditional so the
     * shared [PlayerHolder] never keeps a dead reference.
     */
    override fun onCleared() {
        super.onCleared()
        previewer?.endSession()
        playerHolder.removeListener(playerListener)
        playerHolder.onVideoDecoderInitialized = null
        stopPlaybackOnce()
    }
}

class PlaybackViewModelFactory(
    private val gateway: CoreGateway,
    private val playerHolder: PlaybackPlayer,
    private val itemId: String,
    private val startFromBeginning: Boolean = false,
    private val sheetFetcher: SheetFetcher? = null,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(PlaybackViewModel::class.java))
        return PlaybackViewModel(gateway, playerHolder, itemId, startFromBeginning = startFromBeginning, sheetFetcher = sheetFetcher) as T
    }
}
