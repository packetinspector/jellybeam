package tv.jellybeam.player

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import kotlinx.coroutines.Dispatchers
import tv.jellybeam.AppGraph
import tv.jellybeam.R
import tv.jellybeam.data.runCatchingCancellable
import tv.jellybeam.i18n.UiStrings
import tv.jellybeam.data.CoreGateway
import tv.jellybeam.data.SettingsStore
import tv.jellybeam.data.displayMessage
import tv.jellybeam.perf.PerfLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.jellybeam_core.AccountIdentity
import uniffi.jellybeam_core.Card
import uniffi.jellybeam_core.ChapterInfoFfi
import uniffi.jellybeam_core.CoreException
import uniffi.jellybeam_core.ExternalSubtitleFfi
import uniffi.jellybeam_core.FailedTrackFfi
import uniffi.jellybeam_core.MediaSegment
import uniffi.jellybeam_core.MediaSegmentKind
import uniffi.jellybeam_core.OsdDetailSetting
import uniffi.jellybeam_core.PlaybackOsdDetail
import uniffi.jellybeam_core.PlaybackPlan
import uniffi.jellybeam_core.PlaybackRequest
import uniffi.jellybeam_core.PlayMethodFfi
import uniffi.jellybeam_core.SegmentAction
import uniffi.jellybeam_core.StillWatchingDecision
import uniffi.jellybeam_core.SubtitleActionFfi
import uniffi.jellybeam_core.TrackDecisionFfi
import uniffi.jellybeam_core.TrackInfo
import uniffi.jellybeam_core.TrackKindFfi
import uniffi.jellybeam_core.TrackMemoryFfi
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

/** docs/18 §3.1: picker ids for a transcode's embedded streams that only burn-in can show, below
 * every real [TrackMapping] id and [TRACK_PICKER_SUBTITLE_OFF_ID].
 */
private const val BURNABLE_ID_BASE = -1_000L

internal fun burnableSubtitleId(index: Int): Long = BURNABLE_ID_BASE - index

private fun burnableIndexOf(id: Long): Int? = id.takeIf { it <= BURNABLE_ID_BASE }?.let { (BURNABLE_ID_BASE - it).toInt() }

/** docs/18 §3.1: the policy takes the first match, so rows that need no renegotiation come first:
 * delivered tracks, the one burned in, sidecars, then streams only a new burn-in can show.
 */
internal fun policyTracks(embedded: List<TrackInfo>, sidecars: List<TrackInfo>, burnable: List<TrackInfo>): List<TrackInfo> {
    val (burned, unburned) = burnable.partition { it.isSelected }
    return embedded + burned + sidecars + unburned
}

/** docs/18 §3.1: a decision naming a [burnable] stream keeps player text off and returns that
 * stream for the renegotiation to burn in; any other decision passes through.
 */
internal fun routeBurnable(decision: TrackDecisionFfi, burnable: List<TrackInfo>): Pair<TrackDecisionFfi, TrackInfo?> {
    val pick = decision.subtitleTrackId?.let { id -> burnable.firstOrNull { it.id == id } } ?: return decision to null
    return decision.copy(subtitleAction = SubtitleActionFfi.OFF, subtitleTrackId = null) to pick
}

/** Embedded subtitles off, nothing else touched. */
private val SUBTITLES_OFF = TrackDecisionFfi(audioTrackId = null, subtitleAction = SubtitleActionFfi.OFF, subtitleTrackId = null)

/** A pick with no stable key forgets the series' choice rather than remembering a blank one. */
private fun keyedMemory(key: String?): TrackMemoryFfi = key?.let { TrackMemoryFfi.Key(it) } ?: TrackMemoryFfi.Unset

/**
 * docs/18 §1/§3: message shown once [PlaybackViewModel.maybeFallBackToTranscode]
 * has declined to take over. A DirectPlay [plan] carrying a [PlaybackPlan.serverVerdict]
 * means the server would have transcoded and only Direct Played because CLAUDE.md's
 * Direct Play rule forced it -- names that verdict and the Quality setting that
 * would unlock a transcode; every other case falls back to `"Playback error: <code>"`.
 */
internal fun fatalPlaybackMessage(strings: UiStrings, errorCodeName: String, plan: PlaybackPlan?): String {
    val serverVerdict = plan?.takeIf { it.playMethod == PlayMethodFfi.DIRECT_PLAY }?.serverVerdict
    return if (serverVerdict != null) {
        strings.get(R.string.player_error_direct_play_blocked, errorCodeName, serverVerdict)
    } else {
        strings.get(R.string.player_error_generic, errorCodeName)
    }
}

/** docs/12 §14: what the Undo toast needs to seek back and to label the skipped segment. */
data class SkipUndoState(val preSkipPositionTicks: Long, val segmentType: MediaSegmentKind)

/**
 * docs/17 §2, docs/18 §2.1: what one playback session owns -- its [generation], item and account.
 * Everything the session launches captures it, names [accountEpoch] in account-bound calls, and
 * publishes only while [PlaybackViewModel] still holds this generation.
 */
internal data class SessionScope(val generation: Long, val itemId: String, val accountEpoch: ULong)

/**
 * docs/18 §2.1: whether the player holds a live reporting session. [Playing] owns one [scope];
 * [Ended] ran its single stop or abandon, naming [sessionId] (`null`: none was ever installed), so
 * that call, not a later one, ends it. [generation] only ever grows, so no two scopes compare equal.
 */
internal sealed interface SessionOwner {
    val generation: Long

    data class Playing(val scope: SessionScope) : SessionOwner {
        override val generation: Long get() = scope.generation
    }

    data class Ended(override val generation: Long, val sessionId: String?) : SessionOwner
}

/**
 * docs/12 §8: the episode-edge lookup. [Failed] is told apart from a series edge ([Known] with a
 * `null` side) so entering the next-up trigger zone can retry it once ([Failed.retried]).
 */
internal sealed interface NeighborLookup {
    data object Unknown : NeighborLookup

    data class Known(val previous: Card?, val next: Card?) : NeighborLookup

    data class Failed(val retried: Boolean) : NeighborLookup
}

/** docs/12 §14: the post-skip Undo toast's auto-hide window. */
internal const val SKIP_UNDO_WINDOW_MS = 5_000L

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
    /** Media3's seekability for the loaded file (docs/12 §9, [SeekGuard]): UNKNOWN until its timeline
     * is real, during which seeks are dropped; UNSEEKABLE seeks show a notice instead. */
    val seekability: Seekability = Seekability.UNKNOWN,
    /** Credits-aware next-up: non-null while the card should be shown over the OSD. */
    val nextUp: NextUpState? = null,
    /** docs/12 §13's "Still watching?" card -- mutually exclusive with [nextUp] by construction:
     * [PlaybackViewModel.evaluateNextUp] only ever sets one, per
     * [uniffi.jellybeam_core.StillWatchingDecision]. */
    val stillWatching: StillWatchingState? = null,
    /** docs/12 §14: the post-skip Undo toast; set by [PlaybackViewModel.skipSegment], cleared by undo,
     * dismissal or its [SKIP_UNDO_WINDOW_MS] expiry. */
    val skipUndo: SkipUndoState? = null,
    /** dpad-left seek magnitude, from `Settings.skipBackSecs`, read once per session in [start].
     * Defaults to [SeekMath.SKIP_MS] until that read resolves.
     */
    val skipBackMs: Long = SeekMath.SKIP_MS,
    /** dpad-right seek magnitude, from `Settings.skipForwardSecs` -- see [skipBackMs]. */
    val skipForwardMs: Long = SeekMath.SKIP_MS,
    /** Read once per session in [start]; PlaybackScreen's `AndroidView` update block applies it. */
    val subtitleStyle: SubtitleStyle = SubtitleStyle(),
    /** docs/12 §12: the one menu or sheet open over the OSD, if any -- opening one replaces the
     * last, so no two are ever up together. The per-surface reads below ([speedMenuOpen],
     * [trackPicker], ...) all derive from it.
     */
    val openSurface: OpenSurface = OpenSurface.None,
    /** Narrow, non-blocking OSD detail fetch and server label, kept in observable state so the
     * Direct Play chip and a newly opened stats sheet see the completed fetch immediately.
     */
    val playbackStatsDetail: PlaybackOsdDetail? = null,
    val statsServerName: String? = null,
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
    /** docs/18 §3.2: the showing sidecar's cues, drawn by the screen's own feed; null when none is. */
    val sidecarCues: SidecarCues? = null,
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

    /** The speed menu (docs/12 §12). */
    val speedMenuOpen: Boolean get() = openSurface is OpenSurface.Speed

    /** The chapters menu (docs/12 §8/§12, shown only when the item has markers). */
    val chaptersMenuOpen: Boolean get() = openSurface is OpenSurface.Chapters

    /** The in-player track picker (docs/09 slice 3b): `null` closed, else [TrackPickerState]'s
     * rows. Independent of [PlaybackOsdController]'s `isVisible` -- see [TrackPickerState]'s own
     * doc comment.
     */
    val trackPicker: TrackPickerState? get() = (openSurface as? OpenSurface.Tracks)?.picker

    /** The stats sheet's live snapshot (docs/12 §17): non-null exactly while open, refreshed at 1Hz by [PlaybackViewModel.refreshStatsSheetLive]. */
    val statsSheetLive: PlaybackLiveStats? get() = (openSurface as? OpenSurface.Stats)?.live

    /** Library metadata (docs/12 §17), loaded only when its OSD button is activated so it stays off the time-to-play path. */
    val libraryInfoOverlay: LibraryInfoOverlayState? get() = (openSurface as? OpenSurface.LibraryInfo)?.overlay

    /** Rewrites the open track picker in place; any other surface (or none) is left as it is. */
    internal fun withTrackPicker(transform: (TrackPickerState) -> TrackPickerState): PlaybackUiState =
        (openSurface as? OpenSurface.Tracks)?.let { copy(openSurface = OpenSurface.Tracks(transform(it.picker))) } ?: this
}

/** docs/12 §12: what [PlaybackUiState.openSurface] can hold -- the player's menus and sheets. */
sealed interface OpenSurface {
    data object None : OpenSurface

    data object Speed : OpenSurface

    data object Chapters : OpenSurface

    data class Tracks(val picker: TrackPickerState) : OpenSurface

    data class Stats(val live: PlaybackLiveStats) : OpenSurface

    data class LibraryInfo(val overlay: LibraryInfoOverlayState) : OpenSurface
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
    /** [accountEpoch]: the failing request's account (docs/18 §2.1), so the right one re-signs in. */
    data class ReauthorizationRequired(val account: AccountIdentity?) : PlaybackEvent

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

    /** A seek was refused because the file is unseekable -- the screen shows the notice
     * (docs/12 §9, [SeekGuard]). */
    data object SeekUnavailable : PlaybackEvent

    /** The chosen sidecar subtitle failed to load; subtitles went back to Off (docs/18 §3.2). */
    data object SubtitleUnavailable : PlaybackEvent
}

/**
 * Orchestrates one playback session: negotiates the plan via [CoreGateway.preparePlayback], loads
 * it into the shared [PlayerHolder], ticks position reports while playing, and guarantees the
 * reporting session is closed out **exactly once** ([stopPlaybackOnce], or [abandonPlaybackOnce]
 * when playback never became active) no matter which exit path fires first --
 * [tv.jellybeam.player.PlaybackActivity]'s `onStop`-while-finishing, its `onDestroy` fallback, and
 * this ViewModel's own [onCleared] all funnel through the same [owner]-guarded path.
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
    private val strings: UiStrings,
    private val reportScope: CoroutineScope = AppGraph.processScope,
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
    /** docs/18 §3.2: sidecar parsing, off the main thread; tests inject a fake. */
    private val parseSidecar: suspend (codec: String, text: String) -> SidecarCues? = { codec, text ->
        withContext(Dispatchers.Default) { ExternalSubtitles.parse(codec, text) }
    },
    /** docs/09: the one settings reader (single writer); tests inject a store over their fake. */
    private val settingsStore: SettingsStore = AppGraph.settingsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(PlaybackUiState())
    private var skipUndoExpiry: Job? = null
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

    /** docs/18 §2.1: [SessionOwner.Ended] until a `preparePlayback` call actually succeeds -- see
     * [stopPlaybackOnce]'s doc. Only the main thread reads or moves it, so a check-then-set is exactly once.
     */
    private var owner: SessionOwner = SessionOwner.Ended(generation = 0L, sessionId = null)

    /** docs/17 §2: the scope [start] adopted, `null` once it ended; every launch it makes publishes
     * only while its scope is still this one.
     */
    private val liveScope: SessionScope? get() = (owner as? SessionOwner.Playing)?.scope

    private fun SessionScope.isCurrent(): Boolean = liveScope?.generation == generation

    private inline fun <T> SessionScope.ifCurrent(block: () -> T): T? = if (isCurrent()) block() else null

    /** docs/18 §2.1: the old session's final stop, open from the moment its snapshot is taken until
     * the core has closed it; a newer request's prepare waits for it so it cannot replace a session
     * mid-report.
     */
    private var stopLanding: CompletableDeferred<Unit>? = null
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

    /** docs/18 §2: the track type this session's fallback blamed, resent on every renegotiation so
     * a subtitle change never lets the failed stream be copied back. Reset by every [start].
     */
    private var sessionFailedTrack: FailedTrackFfi? = null

    /** docs/18 §2.1: the newest playback request this view model minted, the only one whose plan,
     * error or Finish may publish; `null` once stopped, abandoned or closed for an account change.
     */
    private var currentRequest: PlaybackRequest? = null

    /** This session's `Settings.tolerateMislabeledLevels`/decoder-preference snapshot, remembered
     * so [maybeFallBackToTranscode] can reload the fallback plan with the same preferences [start]
     * used.
     */
    private var sessionTolerateMislabeledLevels: Boolean = true
    private var sessionFullAssStyling: Boolean = false
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

    /** docs/12 §8: this session's episode-edge lookup, reset to [NeighborLookup.Unknown] by [start]. */
    private var neighbors: NeighborLookup = NeighborLookup.Unknown

    /** The next episode, `null` while unknown or failed, or at the series finale. */
    private val nextEpisode: Card? get() = (neighbors as? NeighborLookup.Known)?.next

    /** The previous episode, `null` for the same three reasons as [nextEpisode]. Only
     * [PlaybackUiState.hasPreviousEpisode] is exposed.
     */
    private val previousEpisode: Card? get() = (neighbors as? NeighborLookup.Known)?.previous

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
     * The current session's trickplay manifest, carried by [fetchPlaybackOsdDetail]'s fetch (not
     * read off the plan directly, since `prepare_playback` must not await it). `null`
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
     * whichever comes first -- gates [fetchPlaybackOsdDetail] and
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

    /** docs/18 §3.1: [resolveTrackSelectionOnce]'s round trip; every session change and manual pick
     * cancels it via [cancelTrackSelection], so a result landing past the await is still current.
     */
    private var trackSelectionJob: Job? = null

    /** docs/18 §3.1: an automatic resolution is in flight; cleared by its own completion or by [cancelTrackSelection]. */
    private var resolutionPending = false

    /** Drops any in-flight automatic resolution, so a cancelled one finishing late can't clear a newer one's flag. */
    private fun cancelTrackSelection() {
        trackSelectionJob?.cancel()
        trackSelectionJob = null
        resolutionPending = false
    }

    /** docs/18 §3.1: what a same-item reload still has to restore on its first usable tracks;
     * cleared once restored, by a manual pick, and by [start].
     */
    private var pendingReapply: PendingReapply? = null

    private data class PendingReapply(val text: TrackIdentity?, val audio: TrackIdentity?)

    /** A manual pick replaces only its own kind's restoration; the other kind's still applies. */
    private fun dropPendingReapply(kind: TrackKindFfi) {
        pendingReapply = pendingReapply
            ?.let { if (kind == TrackKindFfi.AUDIO) it.copy(audio = null) else it.copy(text = null) }
            ?.takeIf { it.text != null || it.audio != null }
    }

    /** docs/18 §3.1: the subtitle and audio choice last applied this item, manual or automatic. */
    private var recordedText: TextChoice = TextChoice.Leave
    private var recordedAudio: TrackIdentity? = null

    /** `true` once [chooseAudio]/[chooseSubtitle] has applied a manual pick this session, so a reload
     * keeps it (a pick also cancels any pending automatic resolution). Reset by every [start].
     */
    private var manualTrackChoiceMade = false

    /** docs/18 §3.2: this session's sidecar subtitles, from the plan. */
    private var sidecars: List<ExternalSubtitleFfi> = emptyList()

    /** The sidecar showing or loading to show, by server index; embedded text is off meanwhile. */
    private var activeSidecar: Int? = null
    private val sidecarStatus = mutableMapOf<Int, SidecarStatus>()
    private val sidecarCache = mutableMapOf<Int, SidecarCues>()
    private var sidecarJob: Job? = null
    /** A pick whose fetch the old session refused mid-fallback, asked again once the new one exists;
     * owned like [sidecarJob], so every pick change clears it. */
    private var sidecarAwaitingSession: Pair<Int, () -> Unit>? = null
    /** docs/18 §2.1: the transcode negotiation in flight, if any, and the session it retires in the
     * core -- what a stop or abandon must name meanwhile, since that session was already taken.
     */
    private var negotiation: Negotiation? = null

    private data class Negotiation(val request: PlaybackRequest, val retires: String, val subtitleIndex: Int?)

    /** The old stream failed while a negotiation was in flight; if that negotiation then loads
     * nothing, the session has nothing left playing.
     */
    private var errorDuringNegotiation = false

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = updatePlayState()
        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) = refreshSeekable()
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
            val reapplied = reapplyChoice(tracks)
            resolveTrackSelectionOnce(tracks)
            // The dot follows what plays once nothing is deciding (a landed burn-in included); a
            // decision applied just now already set it from its effect, which these tracks predate,
            // and a retry's empty announcement says nothing.
            if (!reapplied && !resolutionPending && negotiation == null && !tracks.isEmpty) {
                _state.update { it.copy(nonDefaultTrackActive = computeNonDefaultTrackActive(allTrackInfos(tracks))) }
            }
            // An open picker follows the player's real tracks, never a stale snapshot.
            _state.update { it.withTrackPicker { _ -> buildTrackPickerState(tracks) } }
            // docs/18 §1's second local-evidence signal (the other is onPlayerError
            // below): every track of a present type rejected by this device's
            // decoders. maybeFallBackToTranscode's own gate gets it right
            // regardless of playMethod, so this stays unconditional.
            LocalPlayability.unplayable(tracks)?.let { (failed, reason) -> maybeFallBackToTranscode(reason, failed) }
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
            // docs/18 §2: the old stream may die while its replacement negotiates; the landing
            // reloads the player, so its errors are no longer this session's.
            if (negotiation != null) {
                errorDuringNegotiation = true
                return
            }
            // The source-level policy already spent a real recovery window before
            // surfacing this; starting ReconnectPolicy too would stack two
            // independent budgets and could hold a dead stream for minutes.
            if (playerHolder.hasExhaustedLoadRetryBudget()) {
                finishAfterFatalPlaybackError("LOAD_RETRY_EXHAUSTED")
                _events.tryEmit(PlaybackEvent.FinishWithMessage(strings.get(R.string.player_error_lost_connection)))
                return
            }

            // A network-ish SOURCE/IO error gets a reconnect episode; a
            // codec/renderer/parsing error falls straight to the fatal path.
            if (ReconnectPolicy.isRecoverable(error)) {
                beginOrContinueReconnect()
            } else if (!maybeFallBackToTranscode("Playback error: ${error.errorCodeName}", LocalPlayability.failedTrack(error))) {
                // docs/18 §1's first local-evidence signal (the other is
                // onTracksChanged above) -- ordinary fatal path once the fallback
                // gate declines.
                finishAfterFatalPlaybackError(error.errorCodeName)
                _events.tryEmit(PlaybackEvent.FinishWithMessage(fatalPlaybackMessage(strings, error.errorCodeName, currentPlan)))
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
                _events.tryEmit(PlaybackEvent.FinishWithMessage(strings.get(R.string.player_error_lost_connection)))
            }
            is ReconnectPolicy.Decision.Retry -> {
                _state.update { it.copy(reconnecting = ReconnectingInfo(attempt = reconnectAttempt)) }
                val delayMs = decision.delayMs
                val remembered = reconnectPositionTicks ?: 0L
                val positionTicks = SeekGuard.recoveryPositionTicks(_state.value.seekability, remembered)
                // docs/12 §9: say why the file starts over rather than jumping back silently.
                if (positionTicks != remembered && reconnectAttempt == 1) notifySeekUnavailable()
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
     * docs/21 §2.1: one line per negotiated plan, the fallback's included. `transcodeReason` is free
     * text (docs/18's own doc on PlaybackPlan), so a reason with a space maps to the fixed tag "text".
     */
    private fun logPlan(itemId: String, plan: PlaybackPlan) {
        AppGraph.diag.event("playback.plan") {
            item("item", itemId)
            tag("mode", plan.playMethod.name)
            plan.container?.let { tag("container", it) }
            tag("reason", plan.transcodeReason?.let { if (' ' in it) "text" else it } ?: "none")
        }
    }

    /**
     * docs/18 §1's Auto/Cap fallback -- called from all three local-evidence signals
     * (`onPlayerError`, `onTracksChanged`, the decoder-init hook). A no-op unless
     * [fallbackPossible]; a `true` return tells the caller to skip its own fatal-error handling
     * since this takes over the session's fate. On success: loads the new plan with this session's
     * remembered tolerate/decoder preferences, resets playback rate, re-seeds position, and
     * updates [PlaybackUiState.playMethod]/[PlaybackUiState.transcodeReason] -- [owner]
     * stays [SessionOwner.Playing] since Rust already swapped reporting sessions internally. A failed fallback is
     * a plain fatal error, same handling as [start]'s own `CoreException` catch.
     *
     * **Generation guard** (canonical statement, cited elsewhere in this class): the negotiation
     * is a real round trip, so a newer [start] call may move the session on before it resolves. Its
     * request is re-checked through [adoptPlan] when the call returns, which discards the result
     * of a retired one -- no load, no state update, no ending a session that isn't this one's.
     * [CoreException.StalePlaybackSession] is ignored the same way regardless.
     */
    private fun maybeFallBackToTranscode(reason: String, failed: FailedTrackFfi): Boolean {
        if (!fallbackPossible()) return false
        // docs/18 §2.1: only the live owner falls back; a retired one has nothing left to save.
        val request = currentRequest
        if (request == null || liveScope == null) return true
        if (!ownershipOpen(request)) return false
        val plan = currentPlan!! // fallbackPossible() just confirmed this is non-null
        transcodeFallbackAttempted = true
        sessionFailedTrack = failed
        clearReconnectState()
        negotiateTranscode(reason, from = plan)
        return true
    }

    /** docs/18 §3.1: a transcode renegotiates when the choice needs a stream burned in that isn't,
     * or moves off the one that is; no Media3 selection can do either. A no-op unless this owner is
     * live.
     */
    private fun renegotiateIfBurnedIn() {
        val plan = currentPlan?.takeIf { it.playMethod == PlayMethodFfi.TRANSCODE } ?: return
        val request = currentRequest ?: return
        if (liveScope == null || !ownershipOpen(request)) return
        // An undecided or default choice accepts whatever the server chose.
        val wanted = subtitleStreamIndexFor(choiceBeforeReload().text, plan.embeddedSubtitles) ?: return
        val burned = plan.burnedSubtitleIndex
        if (wanted == burned) return
        // One in flight already re-checks the choice when it lands.
        if (negotiation != null) return
        val tracks = playerHolder.currentTracks ?: Tracks.EMPTY
        val needsBurnIn = burnableSubtitles(tracks).any { it.id == burnableSubtitleId(wanted) }
        if (burned == null && !needsBurnIn) return
        negotiateTranscode(plan.transcodeReason.orEmpty(), from = plan)
    }

    /**
     * docs/18 §2/§3.1: the one transcode negotiation, shared by the fallback and a subtitle
     * renegotiation. It keeps the session's copy policy ([sessionFailedTrack]) and sends the
     * subtitle stream the viewer's choice names. A choice changed while it negotiated is
     * renegotiated from the landed session before anything loads, so what loads never contradicts
     * the choice (burned-in pixels can't be turned off); it ends once the viewer stops changing it.
     * Callers negotiate only for the live session.
     */
    private fun negotiateTranscode(reason: String, from: PlaybackPlan) {
        val itemId = from.itemId
        val ticks = _positionTicks.value
        val failed = sessionFailedTrack
        val sent = subtitleStreamIndexFor(choiceBeforeReload().text, from.embeddedSubtitles)
        val request = claimRequest()
        negotiation = Negotiation(request, retires = from.playSessionId, subtitleIndex = sent)
        clearReconnectState()
        viewModelScope.launch {
            try {
                // The native call can't be cancelled, so its result is always seen and disposed of.
                val negotiated = try {
                    withContext(NonCancellable) {
                        gateway.prepareTranscodeFallback(itemId, ticks, reason, from.playSessionId, failed, sent, request)
                    }
                } catch (e: CoreException) {
                    publishFailure(request, e)
                    return@launch
                }
                if (!adoptPlan(request, negotiated, retires = from.playSessionId)) return@launch
                val wanted = subtitleStreamIndexFor(choiceBeforeReload().text, negotiated.embeddedSubtitles)
                if (wanted != null && wanted != sent) {
                    negotiateTranscode(reason, from = negotiated)
                    return@launch
                }
                val previous = currentPlan ?: return@launch
                val choice = choiceBeforeReload() // read before load() clears the tracks
                val paused = !playerHolder.playWhenReady // load() resumes; a paused viewer stays paused
                playerHolder.load(
                    negotiated,
                    tolerateMislabeledLevels = sessionTolerateMislabeledLevels,
                    audioDecoderPreferences = sessionAudioDecoderPreferences,
                    fullAssStyling = sessionFullAssStyling,
                )
                if (paused) playerHolder.pause()
                playerHolder.setPlaybackRate(1f)
                currentPlan = negotiated
                logPlan(itemId, negotiated)
                lastReportedPaused = null // the new session starts unpaused; it hears the real state
                negotiation = null // before the sidecar adoption, which may ask again under this session
                errorDuringNegotiation = false
                clearReconnectState()
                adoptFallbackSidecars(previous, negotiated)
                armReapply(choice)
                _positionTicks.value = negotiated.startPositionTicks
                _state.update {
                    it.copy(
                        playMethod = negotiated.playMethod,
                        transcodeReason = negotiated.transcodeReason,
                        playbackRate = 1f,
                    )
                }
            } finally {
                // A chained attempt owns its own; only this attempt's is cleared, and an open picker
                // stops showing what it asked for.
                if (negotiation?.request == request) {
                    negotiation = null
                    refreshSubtitleChoices()
                }
            }
        }
    }

    // Declared above `init`, which mints the first request: initializers run in source order.
    /** docs/18 §2.1: the account epoch this player's playback runs on, which its account-bound
     * calls name; it outlives an account switch made while it plays.
     */
    var accountEpoch: ULong? = null
        private set

    /** The newest request minted before the launch restore landed; only those may be re-minted. */
    private var lastSeqBeforeRestore = 0uL

    /** Whether [playerListener] and [decoderHook] are on [playerHolder]; the first call builds its lazy ExoPlayer. */
    private var playerWired = false

    /** docs/18 §1's third local-evidence signal, kept so [onCleared] removes only its own hook from the shared holder. */
    private val decoderHook: (String) -> Unit = { decoderName ->
        // Cheap guard first (docs/18 §3): most decoder inits are a default Direct Play session
        // fallbackPossible() rejects outright, so isSoftwareOnly's MediaCodecList enumeration is skipped.
        if (fallbackPossible() && SoftwareDecoder.isSoftwareOnly(decoderName)) {
            maybeFallBackToTranscode("Software video decoder $decoderName -- no hardware decoder on this TV", FailedTrackFfi.VIDEO)
        }
    }

    /** docs/10: called only by [start], once its plan is adopted (before the first [PlaybackPlayer.load]),
     * so the main thread never builds the player ahead of Play's first request.
     */
    private fun wirePlayer() {
        if (playerWired) return
        playerWired = true
        playerHolder.addListener(playerListener)
        playerHolder.onVideoDecoderInitialized = decoderHook
    }

    init {
        // "Still watching?" (docs/12 §13): construction-only reset (not in
        // [start], which playNext/playPrevious/playNextEpisode reuse for later
        // sessions, and autoplay isn't interaction) -- unconditional, unlike
        // notePlayerInput's resetOnInput gating.
        viewModelScope.launch { runCatchingCancellable { gateway.resetStillWatching(clock.nowMs().toULong()) } }
        // Minted at once, so a replacement minted later outranks it; if the launch restore was still
        // running, it is re-minted under the restored epoch -- unless stopped or replaced meanwhile.
        val initialRequest = claimRequest(fresh = true)
        viewModelScope.launch {
            gateway.awaitAccountRestored()
            // docs/18 §2.1: from here, leaving the account this owner plays on ends its playback; the
            // core already stopped it. Not before: the launch restore itself moves the epoch.
            viewModelScope.launch {
                gateway.accountEpochs.collect {
                    if (currentRequest?.let { request -> !ownershipOpen(request) } == true) closeForAccountChange()
                }
            }
            if (currentRequest != initialRequest) return@launch
            start(itemId, remintIfRestored(initialRequest), startFromBeginning = startFromBeginning)
        }
    }

    /** docs/18 §2.1: mints the next request on the main thread and makes it this owner's newest.
     * Only a [fresh] start (launch, a new Play intent) takes the current account; everything else
     * the player asks for stays on the account its playback is on.
     */
    private fun claimRequest(fresh: Boolean = false): PlaybackRequest =
        gateway.mintPlaybackRequest(accountEpoch.takeUnless { fresh }).also {
            currentRequest = it
            accountEpoch = it.accountEpoch
            if (!gateway.accountRestoredNow()) lastSeqBeforeRestore = it.seq
        }

    private fun ownershipOpen(request: PlaybackRequest): Boolean = gateway.playbackOwnershipOpen(request.accountEpoch)

    /** docs/18 §2.1: retires every request but [keep], a transition's own, ahead of its teardown. */
    private fun retireRequests(keep: PlaybackRequest? = null) {
        currentRequest = currentRequest?.takeIf { it == keep }
    }

    /** docs/18 §2.1: the session whose stream is on screen, which reports, stops and abandons name:
     * while a negotiation runs it is the one that negotiation took over, not yet the plan it lands.
     */
    private fun sessionOnScreen(): String? = negotiation?.retires ?: currentPlan?.playSessionId

    /** docs/18 §2.1: whether [request]'s [plan] may load; otherwise it is abandoned by its own id, and
     * the player closes if the account changed under it. */
    private suspend fun adoptPlan(request: PlaybackRequest, plan: PlaybackPlan, retires: String? = null): Boolean {
        val open = withContext(NonCancellable) { gateway.playbackOwnershipOpenNow(request.accountEpoch) }
        val outcome = planOutcome(latest = request == currentRequest, open = open)
        if (outcome == PlanOutcome.APPLY) return true
        // A stop naming the session this fallback replaced ends it where the viewer left; an abandon
        // racing that stop could get there first and drop the position.
        if (retires == null || retires != (owner as? SessionOwner.Ended)?.sessionId) {
            reportScope.launch { runCatchingCancellable { gateway.abandonPlayback(plan.playSessionId) } }
        }
        if (outcome == PlanOutcome.CLOSE) closeForAccountChange()
        return false
    }

    /** docs/18 §2.1: the one place a request's failure reaches the viewer, and only from its owner.
     * A live session (a failed fallback) ends first; a failed prepare has none.
     */
    private suspend fun publishFailure(request: PlaybackRequest, error: CoreException) {
        val open = withContext(NonCancellable) { gateway.playbackOwnershipOpenNow(request.accountEpoch) }
        val outcome = failureOutcome(
            latest = request == currentRequest,
            open = open,
            liveSession = owner is SessionOwner.Playing && !errorDuringNegotiation,
            error = error,
        )
        val event = when (outcome) {
            FailureOutcome.IGNORE -> return
            FailureOutcome.CLOSE -> return closeForAccountChange()
            FailureOutcome.QUIET_FINISH -> PlaybackEvent.Finish
            FailureOutcome.REAUTHORIZE -> PlaybackEvent.ReauthorizationRequired((error as? CoreException.Unauthorized)?.account)
            FailureOutcome.FINISH_WITH_MESSAGE -> PlaybackEvent.FinishWithMessage(error.displayMessage(strings))
        }
        if (owner is SessionOwner.Playing) finishAfterFatalPlaybackError(error::class.simpleName ?: "CoreException")
        _events.tryEmit(event)
    }

    /** docs/18 §2.1: the core already stopped this account's session at the boundary, so this only
     * ends the player and closes it.
     */
    private fun closeForAccountChange() {
        abandonPlaybackOnce()
        _events.tryEmit(PlaybackEvent.Finish)
    }

    private suspend fun start(itemId: String, request: PlaybackRequest, startFromBeginning: Boolean = false) {
        PerfLog.markPlayback("viewModel.start")
        // docs/21 §2.1: one line per negotiation attempt, before the network round trip.
        AppGraph.diag.event("playback.prepare") {
            item("item", itemId)
            tag("type", lastKnownItemType ?: "unknown")
        }
        // docs/18-playback-quality.md §3: preparePlayback is a synchronous native call that
        // can't itself be cancelled -- Rust may already have installed a real
        // reporting session by the time this coroutine's cancellation is
        // noticed, so it must run inside NonCancellable and its plan go through
        // adoptPlan (an owner cleared meanwhile has already retired the request).
        val startup = try {
            withContext(NonCancellable) {
                // The settings read is independent of playback negotiation;
                // starting both before either is awaited collapses two
                // dispatcher hops into one. Not a coroutineScope (would defeat
                // NonCancellable), so runCatching replaces structured-failure
                // propagation for this child.
                val settingsDeferred = async {
                    try {
                        settingsStore.load()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                }
                val plan = gateway.preparePlayback(itemId, startFromBeginning, request)
                PerfLog.markPlayback("plan.ready")
                plan to runCatching { settingsDeferred.await() }.getOrNull()
            }
        } catch (e: CoreException) {
            PerfLog.markPlayback(
                when (e) {
                    is CoreException.WouldTranscode -> "plan.refusedTranscode"
                    is CoreException.Unauthorized -> "plan.unauthorized"
                    is CoreException.StalePlaybackSession -> "plan.stale"
                    is CoreException.AccountChanged -> "plan.accountChanged"
                    else -> "plan.failed"
                },
            )
            // A failed prepare never installs a reporting session, so there is nothing to end.
            publishFailure(request, e)
            return
        }
        val (plan, settings) = startup
        if (!adoptPlan(request, plan)) return
        wirePlayer()

        // Per-session reset (see the class doc's autoplay paragraph): every
        // field below is one-shot-per-session, put back to its fresh value on
        // every `start` call exactly as the constructor's own first call does.
        val scope = SessionScope(owner.generation + 1, itemId, request.accountEpoch)
        dismissSkipUndo() // docs/12 §14: the toast belongs to the session that skipped
        currentPlan = plan
        lastKnownItemType = plan.itemType
        logPlan(itemId, plan)
        transcodeFallbackAttempted = false // docs/18 §1: one fallback per item
        sessionFailedTrack = null
        negotiation = null // a previous item's, whose stop already revoked it
        errorDuringNegotiation = false
        owner = SessionOwner.Playing(scope)
        lastReportedPaused = null
        playbackWasActive = false
        outroStartSecs = null // populated by fetchMediaSegments below, not read off the plan
        neighbors = NeighborLookup.Unknown
        nextUpDismissed = false
        preloadedNextUpItemId = null
        lastAutoSkipSegmentKey = null
        currentSeriesId = plan.seriesId
        cancelTrackSelection() // docs/18 §3.1: leftover-job hygiene
        trackSelectionApplied = false
        manualTrackChoiceMade = false
        pendingReapply = null
        recordedText = TextChoice.Leave
        recordedAudio = null
        resetSidecars()
        sidecars = plan.externalSubtitles
        trickplayMeta = null
        enrichmentGate = CompletableDeferred() // fresh gate per session -- see its own doc comment
        previewer?.endSession()
        endClampHold = false
        endHoldReportTicks = null
        loadStartedAtMs = android.os.SystemClock.elapsedRealtime()
        closeSurface()
        bufferingTickerJob?.cancel() // leftover-job hygiene, see that field's own doc comment
        bufferingTickerJob = null
        clearSeekBufferingGrace()
        reconnectJob?.cancel() // leftover-job hygiene, see that field's own doc comment
        reconnectJob = null
        reconnectAttempt = 0
        reconnectElapsedMs = 0L
        reconnectPositionTicks = null
        clearStillWatching()
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
        sessionFullAssStyling = settings?.subtitleFullAssStyling ?: false
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
            fullAssStyling = sessionFullAssStyling,
        )
        // The shared PlayerHolder singleton outlives any one session --
        // a previous session's rate must never leak into a freshly loaded item.
        playerHolder.setPlaybackRate(1f)
        _positionTicks.value = plan.startPositionTicks
        resumeNoticePending = plan.startPositionTicks > 0L
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
                seekability = Seekability.UNKNOWN,
                sidecarCues = null,
                skipBackMs = skipBackMs,
                skipForwardMs = skipForwardMs,
                subtitleStyle = subtitleStyle,
                skipSegmentActions = skipSegmentActions,
                playMethod = plan.playMethod, // docs/18 §1/§3: reset per session from the fresh plan, never carried over
                transcodeReason = plan.transcodeReason,
                playbackStatsDetail = null,
                statsServerName = null,
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
                hasPreviousEpisode = false,
                hasNextEpisode = false,
                nonDefaultTrackActive = false,
            )
        }

        // Session heartbeat starts before every optional enrichment; episode-neighbor
        // recovery may need one live request and must never block time-to-first-frame.
        startProgressTicker(scope)
        val episodeSeriesId = plan.seriesId
        if (plan.itemType == ITEM_TYPE_EPISODE && episodeSeriesId != null) {
            fetchEpisodeNeighbors(scope, episodeSeriesId)
        }
        // Segments feed auto-skip, which can fire at 0s, so this one stays immediate.
        fetchMediaSegments(scope)
        // The rest are gated on enrichmentGate (first frame, or the fallback below) so they
        // don't compete with the media loads for time-to-first-frame (docs/12 §11, docs/18).
        val gate = enrichmentGate
        viewModelScope.launch {
            delay(ENRICHMENT_GATE_FALLBACK_MS)
            gate.complete(Unit)
        }
        fetchPlaybackOsdDetail(scope, plan.mediaSourceId)
        fetchServerDisplayName(scope)
    }

    /**
     * Populates both episode-edge controls from one coherent lookup after
     * player load. The FFI prefers the mirror and performs at most one live
     * series request if the current item cannot be placed there. The scope
     * guard keeps a late answer from an older session, even of the same item, from adding
     * controls that target the wrong episode. An unanswered lookup is [NeighborLookup.Failed],
     * which [retryFailedNeighbors] repeats once.
     */
    private fun fetchEpisodeNeighbors(scope: SessionScope, seriesId: String, retried: Boolean = false) {
        viewModelScope.launch {
            val found = runCatchingCancellable { gateway.episodeNeighbors(scope.itemId, seriesId, scope.accountEpoch) }.getOrNull()
            scope.ifCurrent {
                neighbors = found?.let { NeighborLookup.Known(it.previous, it.next) } ?: NeighborLookup.Failed(retried)
                _state.update {
                    it.copy(
                        hasPreviousEpisode = previousEpisode != null,
                        hasNextEpisode = nextEpisode != null,
                    )
                }
            }
        }
    }

    /**
     * Chapters + the info overlay's content (docs/12), from one narrow live
     * [uniffi.jellybeam_core.PlaybackOsdDetail] fetch. Fire-and-forget, not awaited by [start]; fails
     * open since an older/unreachable server just means no tick marks/empty overlay, never a
     * playback interruption. Gated on [enrichmentGate], along with [fetchServerDisplayName].
     *
     * **Stale-session guard, shared by every `fetch*` method in this class**
     * ([fetchServerDisplayName]/[fetchMediaSegments]): [viewModelScope] outlives
     * one session, so a slow fetch from an earlier one may still be in flight after a newer
     * [start]. Each names its own [SessionScope.accountEpoch] and publishes only through
     * [ifCurrent] (docs/18 §2.1); each is independent, one failing never blocks another.
     */
    private fun fetchPlaybackOsdDetail(scope: SessionScope, mediaSourceId: String) {
        val gate = enrichmentGate
        viewModelScope.launch {
            gate.await() // docs/12 §11: first frame, or the fallback timer -- see enrichmentGate's doc comment
            if (!scope.isCurrent()) return@launch // a replaced session asks for nothing
            val detail = runCatchingCancellable { gateway.getPlaybackOsdDetail(scope.itemId, mediaSourceId, scope.accountEpoch) }.getOrNull()
            scope.ifCurrent {
                _state.update {
                    it.copy(
                        playbackStatsDetail = detail,
                        chapters = detail?.chapters.orEmpty(),
                    )
                }
                startTrickplay(scope, detail?.trickplay)
            }
        }
    }

    /** Playback stats sheet's SOURCE row (docs/12 §17), via [CoreGateway.serverDisplayName] -- see
     * [fetchPlaybackOsdDetail]'s doc comment for the shared shape. `null` if unresolved or nobody's
     * signed in; the sheet omits the row.
     */
    private fun fetchServerDisplayName(scope: SessionScope) {
        val gate = enrichmentGate
        viewModelScope.launch {
            gate.await() // docs/12 §11: first frame, or the fallback timer -- see enrichmentGate's doc comment
            if (!scope.isCurrent()) return@launch
            val name = runCatchingCancellable { gateway.serverDisplayName(scope.accountEpoch) }.getOrNull()
            scope.ifCurrent { _state.update { it.copy(statsServerName = name) } }
        }
    }

    /** See [fetchPlaybackOsdDetail]'s doc comment for the shared shape. Also derives
     * [outroStartSecs] via [CoreGateway.outroStartSecsFromSegments] from this same fetch -- no
     * second request.
     */
    private fun fetchMediaSegments(scope: SessionScope) {
        viewModelScope.launch {
            val segments = runCatchingCancellable { gateway.getMediaSegments(scope.itemId, scope.accountEpoch) }.getOrDefault(emptyList())
            scope.ifCurrent {
                outroStartSecs = gateway.outroStartSecsFromSegments(segments)
                _state.update { it.copy(mediaSegments = segments) }
            }
        }
    }

    /** Starts the scrub-preview pipeline from the manifest the one enrichment fetch carried (docs/10).
     * [seek] already fails open on a `null` [trickplayMeta], so a race needs no special handling.
     */
    private fun startTrickplay(scope: SessionScope, meta: TrickplayMetaFfi?) {
        trickplayMeta = meta
        val previewer = previewer
        if (meta != null && previewer != null) {
            previewer.startSession(
                meta,
                urlFor = { index -> gateway.trickplayTileUrl(scope.itemId, meta.width, index, scope.accountEpoch) },
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
    private fun startProgressTicker(scope: SessionScope) {
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
                // Named on main: a report queued behind a stop never lands on the next session.
                val reportTo = sessionOnScreen()
                if (reportTo != null && ProgressReportGate.shouldReportPosition(playerHolder.isPlaying)) {
                    runCatchingCancellable { gateway.reportPosition(reportTo, ticks) }
                }
                if (!scope.isCurrent()) return@launch // docs/18 §2.1: a report held across a newer start evaluates nothing
                // docs/12 §9: an end-clamped glide commit pauses without
                // triggering autoplay/up-next/watched-state -- position
                // reporting and stats above are unaffected.
                if (!endClampHold) {
                    evaluateNextUp(scope, ticks)
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
     * job. A no-op with no next episode, no known duration, or after [nextUpDismissed]; a failed
     * lookup is retried once on entering the trigger zone.
     */
    private fun evaluateNextUp(scope: SessionScope, positionTicks: Long) {
        val lookup = neighbors
        // Only a known next episode, or a failure not yet retried, has anything to evaluate.
        if (lookup is NeighborLookup.Unknown || lookup is NeighborLookup.Failed && lookup.retried) return
        if (lookup is NeighborLookup.Known && lookup.next == null) return
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
                // Entering the zone is the moment a failed lookup matters (docs/12 §8): one more try.
                val next = when (lookup) {
                    is NeighborLookup.Known -> lookup.next ?: return
                    is NeighborLookup.Failed -> return retryFailedNeighbors(scope, lookup)
                    NeighborLookup.Unknown -> return
                }
                // The countdown runs against what will actually play: to the outro when the
                // credits are auto-skipped, otherwise to the end of the file.
                val playableSecs =
                    if (outroAutoSkipActive) (outroStart - positionSecs).coerceAtLeast(0.0) else remainingSecs
                if (!autoplayEnabled) {
                    // Autoplay off: guard is inert, show the static card, no round trip.
                    showCountdownCard(scope, next, playableSecs, positionTicks)
                    return
                }
                stillWatchingEvaluationPending = true
                viewModelScope.launch {
                    val decision = runCatchingCancellable { gateway.noteEpisodeFinished(clock.nowMs().toULong()) }
                        .getOrDefault(StillWatchingDecision.COUNTDOWN)
                    if (!scope.isCurrent()) return@launch // a newer session started meanwhile
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
                                CountdownOutcome.SHOW_CARD -> showCountdownCard(scope, next, playableSecs, positionTicks)
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

    /** docs/12 §8: a [NeighborLookup.Failed] lookup is repeated once, when the viewer reaches the
     * next-up trigger zone; the retry is marked before it runs so no later tick repeats it.
     */
    private fun retryFailedNeighbors(scope: SessionScope, lookup: NeighborLookup.Failed) {
        if (lookup.retried) return
        val seriesId = currentSeriesId ?: return
        neighbors = NeighborLookup.Failed(retried = true)
        fetchEpisodeNeighbors(scope, seriesId, retried = true)
    }

    /** Populates the ordinary autoplay countdown card -- factored out of [evaluateNextUp] since
     * it's reached from both the autoplay-off path and a [StillWatchingDecision.COUNTDOWN] answer.
     * [playableSecs] is what is left to play before the file ends or the credits are skipped.
     */
    private fun showCountdownCard(scope: SessionScope, next: Card, playableSecs: Double, positionTicks: Long) {
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
            viewModelScope.launch { runCatchingCancellable { gateway.preloadPlayback(next.id, scope.accountEpoch) } }
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
        viewModelScope.launch { runCatchingCancellable { gateway.notePlayerInput(clock.nowMs().toULong()) } }
        if (_state.value.stillWatching != null) {
            startStillWatchingTimeout()
        }
    }

    /** The one place the still-watching card goes away: its timeout dies with it, so a stale deadline can never stop a later session. */
    private fun clearStillWatching() {
        stillWatchingTimeoutJob?.cancel()
        stillWatchingTimeoutJob = null
        _state.update { it.copy(stillWatching = null) }
    }

    /** "Continue" on the still-watching card: resets the counters unconditionally via
     * [CoreGateway.resetStillWatching] (the only reset a Continue gets) and proceeds straight to
     * the next episode via [transitionTo], skipping the countdown.
     */
    fun stillWatchingContinue() {
        val card = _state.value.stillWatching?.card ?: return
        clearStillWatching()
        viewModelScope.launch { runCatchingCancellable { gateway.resetStillWatching(clock.nowMs().toULong()) } }
        transitionTo(card)
    }

    /** "Stop" on the still-watching card, or its timeout elapsing. Ends the finished
     * episode normally via [stopPlaybackOnce] but emits [PlaybackEvent.FinishToDetail] (not
     * [PlaybackEvent.Finish]) so the Activity routes to the next episode's detail page instead of
     * starting it.
     */
    fun stillWatchingStop() {
        val card = _state.value.stillWatching?.card ?: return
        clearStillWatching()
        stopPlaybackOnce(reason = "still_watching")
        // Detail resolves ids on the browsed account; a playback kept on another just closes (docs/18 §2.1).
        val onBrowsedAccount = accountEpoch == null || accountEpoch == gateway.accountEpochs.value.epoch
        _events.tryEmit(if (onBrowsedAccount) PlaybackEvent.FinishToDetail(itemId = card.id) else PlaybackEvent.Finish)
    }

    /** [SkipSegment.decision] with unseekable files forced to NOTHING (docs/12 §14, [SeekGuard]). */
    private fun guardedSegmentDecision(kind: MediaSegmentKind): SegmentDecision {
        refreshSeekable()
        return SeekGuard.segmentDecision(SkipSegment.decision(kind, _state.value.skipSegmentActions), _state.value.seekability)
    }

    /**
     * AutoSkip driver (docs/12 §14): runs every tick alongside [evaluateNextUp]. When the
     * playhead is inside a segment whose [SkipSegment.decision] resolves to
     * [SegmentDecision.AUTO_SKIP], seeks past it via [skipSegment] and emits
     * [PlaybackEvent.AutoSkipped] for the same Undo toast a manual skip shows. A no-op for
     * `PILL`/`NOTHING`, and, via [lastAutoSkipSegmentKey], for a segment already auto-skipped, and on an unseekable file.
     */
    private fun evaluateAutoSkip(positionTicks: Long) {
        // A transition this tick already started (the next-up countdown ran out at the
        // credits): nothing left to skip in a session being torn down.
        if (owner !is SessionOwner.Playing) return
        val segment = SkipSegment.activeSegment(_state.value.mediaSegments, positionTicks) ?: return
        val decision = guardedSegmentDecision(segment.segmentType)
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
        val before = skipSegment(segment) ?: return
        _events.tryEmit(PlaybackEvent.AutoSkipped(before, segment.segmentType))
    }

    /** Shared end-then-start transition: ends the current session then starts a fresh one for
     * [card]'s item. A no-op unless a session is [SessionOwner.Playing], so a double press or a
     * simultaneous EOF and countdown moves on once.
     * [playNext]/[playPrevious]/[playNextEpisode]/[stillWatchingContinue] all funnel through this.
     */
    private fun transitionTo(card: Card) {
        if (owner !is SessionOwner.Playing) return
        val request = claimRequest()
        // Undispatched: the stop names the session at once, so no landing fallback sees the new
        // request before it (see adoptPlan).
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { replaceWith(card.id, request) }
    }

    /** The end-then-start body [transitionTo] and [replaceItem] share, keyed by a raw item id since
     * JellybeamCore owns a single reporting session: the final stop must finish before
     * `preparePlayback` replaces it, and only the newest [request] gets that far (docs/18 §2.1).
     */
    private suspend fun replaceWith(itemId: String, request: PlaybackRequest, startFromBeginning: Boolean = false) {
        stopPlaybackAndAwait(keep = request)
        gateway.awaitAccountRestored()
        if (currentRequest != request) return
        start(itemId, remintIfRestored(request), startFromBeginning)
    }

    /** docs/18 §2.1: a request minted before the launch restore moved the epoch is re-minted; any
     * later epoch change is a real account change, refused by the core and closed by the watch.
     */
    private fun remintIfRestored(request: PlaybackRequest): PlaybackRequest =
        if (remintAfterRestore(request.seq, lastSeqBeforeRestore, ownershipOpen(request))) claimRequest(fresh = true) else request

    /** `onNewIntent` path (docs/17 §2): a Play/Resume/deep-link intent to an already-alive
     * [tv.jellybeam.player.PlaybackActivity] swaps the item via [transitionTo]'s stop-then-start,
     * keyed by raw item id. Always the newest request: one still negotiating, an autoplay's
     * included, is obsolete and its plan abandoned (docs/18 §2.1).
     */
    fun replaceItem(itemId: String, startFromBeginning: Boolean = false) {
        // Minted now, so an initial prepare still negotiating is already obsolete (docs/18 §2.1).
        val request = claimRequest(fresh = true)
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { replaceWith(itemId, request, startFromBeginning) } // as transitionTo
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
     * [trackSelectionApplied] so a later, more complete announcement still gets a chance.
     */
    private fun resolveTrackSelectionOnce(tracks: Tracks) {
        if (trackSelectionApplied) return
        val embedded = TrackMapping.toTrackInfos(tracks)
        if (embedded.isEmpty()) return
        trackSelectionApplied = true
        val sidecarInfos = ExternalSubtitles.trackInfos(sidecars, activeSidecar)
        val trackInfos = embedded + sidecarInfos
        // docs/18 §3.1: a transcode's burn-in-only streams are choices too, and one burned in shows.
        val burnable = burnableSubtitles(tracks)
        val embeddedTextSelected = (embedded + burnable).any { it.kind == TrackKindFfi.SUBTITLE && it.isSelected }
        val seriesId = currentSeriesId
        if (liveScope == null) return
        resolutionPending = true
        trackSelectionJob = viewModelScope.launch {
            try {
                resolveAndApply(seriesId, trackInfos, policyTracks(embedded, sidecarInfos, burnable), embedded, burnable, embeddedTextSelected, tracks)
            } finally {
                if (isActive) resolutionPending = false // a cancelled job's canceller already cleared it
            }
        }
    }

    private suspend fun resolveAndApply(
        seriesId: String?,
        trackInfos: List<TrackInfo>,
        policyInput: List<TrackInfo>,
        embedded: List<TrackInfo>,
        burnable: List<TrackInfo>,
        embeddedTextSelected: Boolean,
        tracks: Tracks,
    ) {
        val resolved = runCatchingCancellable { gateway.resolveTracks(seriesId, policyInput) }.getOrNull() ?: return
        val (decision, burnPick) = routeBurnable(resolved, burnable)
        val audio = decision.audioTrackId?.let { id -> embedded.firstOrNull { it.id == id } }
            ?: embedded.firstOrNull { it.kind == TrackKindFfi.AUDIO && it.isSelected }
        val (playerDecision, sidecar) = ExternalSubtitles.route(decision, sidecars, embeddedTextSelected, audio?.lang)
        playerHolder.applyTrackDecision(playerDecision, tracks)
        recordDecision(playerDecision, embedded)
        burnPick?.let { recordedText = TextChoice.Embedded(it.identity()) }
        resolutionPending = false // decided from here on, though this job is still running
        renegotiateIfBurnedIn()
        sidecar?.let { showSidecar(it) }
        // A routed sidecar is read back live: it may already have failed back to Off.
        val effective = if (sidecar != null) allTrackInfos(tracks) else applyDecisionToTrackInfos(trackInfos, decision)
        _state.update { it.copy(nonDefaultTrackActive = computeNonDefaultTrackActive(effective)) }
    }

    /** docs/18 §3.1: the semantic choice a same-item reload must restore, as last recorded. A
     * resolution still in flight counts as undecided, so the new load resolves afresh.
     */
    private fun choiceBeforeReload(): ReloadChoice {
        val decided = manualTrackChoiceMade || (trackSelectionApplied && !resolutionPending)
        val sidecar = activeSidecar != null || sidecarAwaitingSession != null
        return ReloadChoice(textChoiceBeforeReload(decided, sidecar, recordedText), recordedAudio.takeIf { decided })
    }

    /** Records what [decision] chose among [infos], for [choiceBeforeReload]. */
    private fun recordDecision(decision: TrackDecisionFfi, infos: List<TrackInfo>) {
        decision.audioTrackId?.let { id -> recordedAudio = infos.firstOrNull { it.id == id }?.identity() }
        val subtitle = decision.subtitleTrackId?.let { id -> infos.firstOrNull { it.id == id } }
        recordedText = when {
            subtitle != null -> TextChoice.Embedded(subtitle.identity())
            decision.subtitleAction == SubtitleActionFfi.OFF -> TextChoice.Off
            else -> recordedText
        }
    }

    /** docs/18 §3.1: arms [choice] for the load just made. Text stays off until an embedded choice
     * is matched (Off and sidecars need no track); Auto resolves again on the new tracks.
     */
    private fun armReapply(choice: ReloadChoice) {
        cancelTrackSelection()
        if (choice.text == TextChoice.Auto) {
            trackSelectionApplied = false
            pendingReapply = null
            return
        }
        trackSelectionApplied = true
        if (choice.text != TextChoice.Leave) playerHolder.applyTrackDecision(SUBTITLES_OFF, Tracks.EMPTY)
        val text = (choice.text as? TextChoice.Embedded)?.identity
        pendingReapply = PendingReapply(text, choice.audio).takeIf { text != null || choice.audio != null }
    }

    /** docs/18 §3.1: restores the armed choice on the first announcement carrying each kind; a kind
     * not announced yet stays pending, and once announced a missing match is final (text stays
     * off, audio keeps the player's default). Idempotent under its own selection callback. Returns
     * whether it decided anything.
     */
    private fun reapplyChoice(tracks: Tracks): Boolean {
        val pending = pendingReapply ?: return false
        val infos = TrackMapping.toTrackInfos(tracks)
        val textAnnounced = pending.text != null && infos.any { it.kind == TrackKindFfi.SUBTITLE }
        val audioAnnounced = pending.audio != null && infos.any { it.kind == TrackKindFfi.AUDIO }
        if (!textAnnounced && !audioAnnounced) return false
        val decision = TrackDecisionFfi(
            audioTrackId = pending.audio?.takeIf { audioAnnounced }?.let { matchTrack(it, TrackKindFfi.AUDIO, infos)?.id },
            // Text is touched only by the announcement that resolves it; it is already off till then.
            subtitleAction = if (textAnnounced) SubtitleActionFfi.OFF else SubtitleActionFfi.LEAVE,
            subtitleTrackId = pending.text?.takeIf { textAnnounced }?.let { matchTrack(it, TrackKindFfi.SUBTITLE, infos)?.id },
        )
        val remaining = PendingReapply(
            text = pending.text.takeUnless { textAnnounced },
            audio = pending.audio.takeUnless { audioAnnounced },
        )
        pendingReapply = remaining.takeIf { it.text != null || it.audio != null }
        if (decision.audioTrackId != null || decision.subtitleTrackId != null) playerHolder.applyTrackDecision(decision, tracks)
        // A no-match leaves text off but keeps the viewer's choice for any later renegotiation.
        recordDecision(decision.copy(subtitleAction = SubtitleActionFfi.LEAVE), infos)
        _state.update { it.copy(nonDefaultTrackActive = computeNonDefaultTrackActive(applyDecisionToTrackInfos(allTrackInfos(tracks), decision))) }
        return true
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
     * A transcode's burn-in rows count as subtitles (docs/18 §3.1) and come first: what is burned
     * in is what shows.
     */
    private fun computeNonDefaultTrackActive(tracks: List<TrackInfo>): Boolean {
        val audioTracks = tracks.filter { it.kind == TrackKindFfi.AUDIO }
        val subtitles = burnableSubtitles(playerHolder.currentTracks ?: Tracks.EMPTY) + tracks.filter { it.kind == TrackKindFfi.SUBTITLE }
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
        open(OpenSurface.Tracks(buildTrackPickerState(tracks)))
    }

    private fun closeTrackPicker() {
        replaceSurface { if (it is OpenSurface.Tracks) OpenSurface.None else it }
    }

    private fun buildTrackPickerState(tracks: Tracks): TrackPickerState {
        val audio = allTrackInfos(tracks).filter { it.kind == TrackKindFfi.AUDIO }
        val audioChoices = audio.mapIndexed { index, info ->
            TrackChoice(
                id = info.id,
                label = TrackChoiceText.label(strings, info, index),
                selected = info.isSelected,
                meta = TrackChoiceText.meta(strings, info, external = false),
                isDefault = info.isDefault,
            )
        }
        return TrackPickerState(audioTracks = audioChoices, subtitleTracks = subtitleChoices(tracks))
    }

    /** docs/18 §3.2: subtitle rows, "Off" first and selected exactly when no real subtitle is. */
    private fun subtitleChoices(tracks: Tracks): List<TrackChoice> {
        val subtitle = allTrackInfos(tracks).filter { it.kind == TrackKindFfi.SUBTITLE }
        return buildList {
            val burnable = burnableSubtitles(tracks)
            add(TrackChoice(id = TRACK_PICKER_SUBTITLE_OFF_ID, label = strings.get(R.string.player_track_off), selected = (subtitle + burnable).none { it.isSelected }))
            burnable.forEachIndexed { index, info ->
                add(TrackChoice(id = info.id, label = TrackChoiceText.label(strings, info, index), selected = info.isSelected, meta = TrackChoiceText.meta(strings, info, external = false), isDefault = info.isDefault))
            }
            subtitle.forEachIndexed { index, info ->
                val sidecar = ExternalSubtitles.indexOf(info.id)
                add(
                    TrackChoice(
                        id = info.id,
                        label = TrackChoiceText.label(strings, info, index),
                        selected = info.isSelected,
                        meta = TrackChoiceText.meta(strings, info, external = sidecar != null, status = sidecar?.let(sidecarStatus::get)),
                        isDefault = info.isDefault,
                    ),
                )
            }
        }
    }

    /** docs/18 §3.1: a transcode's embedded streams that no Media3 track carries (only burn-in can
     * show them), as picker rows; the one burned in now reads selected.
     */
    private fun burnableSubtitles(tracks: Tracks): List<TrackInfo> {
        val plan = currentPlan?.takeIf { it.playMethod == PlayMethodFfi.TRANSCODE } ?: return emptyList()
        val delivered = TrackMapping.toTrackInfos(tracks).filter { it.kind == TrackKindFfi.SUBTITLE }
        // While a renegotiation runs, the stream it asked for reads selected, not the outgoing one.
        val burned = negotiation?.let { it.subtitleIndex ?: plan.burnedSubtitleIndex } ?: plan.burnedSubtitleIndex
        return plan.embeddedSubtitles.map { stream ->
            TrackInfo(
                id = burnableSubtitleId(stream.index),
                kind = TrackKindFfi.SUBTITLE,
                title = stream.title,
                lang = stream.language,
                codec = stream.codec,
                isDefault = stream.default,
                isSelected = stream.index == burned,
                isForced = stream.forced,
            )
        }.filter { burnableIndexOf(it.id) == plan.burnedSubtitleIndex || matchTrack(it.identity(), TrackKindFfi.SUBTITLE, delivered) == null }
    }

    /** docs/18 §3.2: [tracks]' rows plus the sidecars; a showing sidecar is the only selected subtitle. */
    private fun allTrackInfos(tracks: Tracks): List<TrackInfo> {
        val active = activeSidecar
        val embedded = TrackMapping.toTrackInfos(tracks).map {
            if (active != null && it.kind == TrackKindFfi.SUBTITLE) it.copy(isSelected = false) else it
        }
        return embedded + ExternalSubtitles.trackInfos(sidecars, active)
    }

    /** docs/18 §3.2: shows sidecar [index] instead of embedded text, never touching the video;
     * [onShown] runs only once its cues are in. */
    private fun showSidecar(index: Int, onShown: () -> Unit = {}) {
        // OFF carries no track id, so no snapshot is needed to apply it.
        playerHolder.applyTrackDecision(SUBTITLES_OFF, Tracks.EMPTY)
        dropPendingSidecar()
        playerHolder.showAssSidecar(null)
        activeSidecar = index
        val cached = sidecarCache[index]
        if (cached != null) {
            _state.update { it.copy(sidecarCues = cached) }
            onShown()
            return
        }
        _state.update { it.copy(sidecarCues = null) }
        val sub = sidecars.firstOrNull { it.index == index }
        val plan = currentPlan
        if (sub == null || plan == null) {
            sidecarFailed(index)
            return
        }
        // With full styling on, an ASS sidecar loads whole into the overlay rather than as cues.
        val styled = if (ExternalSubtitles.isSsa(sub.codec)) playerHolder.assSidecarTarget(index) else null
        sidecarStatus[index] = SidecarStatus.LOADING
        // start() and a fallback to another media source cancel this job, so a result never lands
        // in another session or on another source's video.
        sidecarJob = viewModelScope.launch {
            var cues: SidecarCues? = null
            // Any failure, a core panic included, costs this track only, never playback.
            val loaded = try {
                if (styled != null) {
                    gateway.loadAssSidecar(plan.playSessionId, index, styled.overlay, styled.key, styled.item)
                } else {
                    cues = gateway.fetchExternalSubtitle(plan.playSessionId, index)?.let { parseSidecar(sub.codec, it) }
                    cues != null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            sidecarJob = null
            val outcome = ExternalSubtitles.fetchOutcome(
                loaded = loaded,
                stillChosen = activeSidecar == index,
                sessionReplaced = plan !== currentPlan,
                swapPending = negotiation != null,
            )
            when (outcome) {
                ExternalSubtitles.FetchOutcome.LOADED -> {
                    sidecarStatus.remove(index)
                    cues?.let { sidecarCache[index] = it }
                    if (activeSidecar == index) {
                        playerHolder.showAssSidecar(styled)
                        _state.update { it.copy(sidecarCues = cues) }
                        onShown()
                    }
                }
                ExternalSubtitles.FetchOutcome.RETRY_UNDER_NEW_SESSION -> showSidecar(index, onShown)
                // Stays chosen and Loading; adoptFallbackSidecars asks the new session.
                ExternalSubtitles.FetchOutcome.AWAIT_NEW_SESSION -> sidecarAwaitingSession = index to onShown
                ExternalSubtitles.FetchOutcome.FAILED -> sidecarFailed(index)
            }
            refreshSubtitleChoices()
        }
    }

    private fun sidecarFailed(index: Int) {
        sidecarStatus[index] = SidecarStatus.UNAVAILABLE
        if (activeSidecar != index) return
        activeSidecar = null
        _state.update { it.copy(sidecarCues = null) }
        playerHolder.currentTracks?.let { tracks ->
            _state.update { it.copy(nonDefaultTrackActive = computeNonDefaultTrackActive(allTrackInfos(tracks))) }
        }
        _events.tryEmit(PlaybackEvent.SubtitleUnavailable)
    }

    /** Forgets every sidecar result; indices only mean something within one media source. */
    private fun resetSidecars() {
        hideSidecar()
        sidecarStatus.clear()
        sidecarCache.clear()
    }

    /**
     * docs/18 §3.2: a fallback that kept the media source keeps the showing sidecar; one that moved
     * to another source drops every result and re-picks the same language and title there, if any.
     */
    private fun adoptFallbackSidecars(previous: PlaybackPlan, fallback: PlaybackPlan) {
        val showing = sidecars.firstOrNull { it.index == activeSidecar }
        sidecars = fallback.externalSubtitles
        if (fallback.mediaSourceId == previous.mediaSourceId) {
            // A new session may serve what the old one refused mid-swap.
            sidecarStatus.entries.removeAll { it.value == SidecarStatus.UNAVAILABLE }
            // Loaded cues are positions in the same file, so they outlive a session that no longer lists them.
            if (showing != null && sidecars.none { it.index == showing.index }) {
                if (showing.index in sidecarCache) sidecars = sidecars + showing else hideSidecar()
            }
            // Any pick change since the refusal cleared this (dropPendingSidecar), so it is current.
            sidecarAwaitingSession?.let { (index, onShown) -> showSidecar(index, onShown) }
            return
        }
        val awaitingOnShown = sidecarAwaitingSession?.second
        resetSidecars()
        // Its rows name the old source's files; the same index may now be another language.
        closeTrackPicker()
        showing?.let { old ->
            sidecars.firstOrNull { it.language == old.language && it.displayTitle == old.displayTitle }
                ?.let { showSidecar(it.index, awaitingOnShown ?: {}) }
        }
    }

    /** Stops showing any sidecar; embedded text stays as the caller's decision leaves it. */
    private fun hideSidecar() {
        dropPendingSidecar()
        playerHolder.showAssSidecar(null)
        activeSidecar = null
        _state.update { it.copy(sidecarCues = null) }
    }

    /** Cancels an in-flight load so its row stops reading Loading. */
    private fun dropPendingSidecar() {
        sidecarJob?.cancel()
        sidecarJob = null
        sidecarAwaitingSession = null
        sidecarStatus.entries.removeAll { it.value == SidecarStatus.LOADING }
    }

    private fun refreshSubtitleChoices() {
        val tracks = playerHolder.currentTracks ?: return
        _state.update { state ->
            state.copy(
                nonDefaultTrackActive = computeNonDefaultTrackActive(allTrackInfos(tracks)),
            ).withTrackPicker { it.copy(subtitleTracks = subtitleChoices(tracks)) }
        }
    }

    /**
     * Chooses [id] as the audio track (docs/09 slice 3b): applies it via
     * [PlaybackPlayer.applyTrackDecision] (subtitles untouched) and, only when this session has a
     * series, remembers the choice via [CoreGateway.rememberTrackMemory]. A Movie applies the
     * override but skips remembering (nothing to key on). A no-op if [id] doesn't resolve to a
     * currently-known audio track. Flips [TrackChoice.selected] optimistically in the held
     * [TrackPickerState] rather than waiting for a real `onTracksChanged`.
     */
    fun chooseAudio(id: Long) {
        val tracks = playerHolder.currentTracks ?: return
        val trackInfos = allTrackInfos(tracks)
        val info = trackInfos.firstOrNull { it.kind == TrackKindFfi.AUDIO && it.id == id } ?: return

        // A manual pick always wins -- cancel any pending automatic resolution
        // outright rather than relying solely on its own after-the-fact recheck.
        cancelTrackSelection()
        dropPendingReapply(TrackKindFfi.AUDIO)
        manualTrackChoiceMade = true
        val decision = TrackDecisionFfi(audioTrackId = id, subtitleAction = SubtitleActionFfi.LEAVE, subtitleTrackId = null)
        playerHolder.applyTrackDecision(decision, tracks)
        recordDecision(decision, trackInfos)
        rememberTrackMemoryIfSeries(TrackKindFfi.AUDIO, keyedMemory(gateway.trackPrefKeyOf(info)))
        _state.update { state ->
            state.copy(
                nonDefaultTrackActive = computeNonDefaultTrackActive(applyDecisionToTrackInfos(trackInfos, decision)),
            ).withTrackPicker { picker -> picker.copy(audioTracks = picker.audioTracks.map { it.copy(selected = it.id == id) }) }
        }
    }

    /** Chooses [id] as the subtitle track, or [TRACK_PICKER_SUBTITLE_OFF_ID] to disable subtitles
     * -- see [chooseAudio]'s doc comment for the shared design. "Off" is itself a real remembered
     * choice (docs/18 §3.1), so it remembers [TrackMemoryFfi.Off].
     */
    fun chooseSubtitle(id: Long) {
        val tracks = playerHolder.currentTracks ?: return
        burnableIndexOf(id)?.let { return chooseBurnable(id, tracks) }
        val trackInfos = allTrackInfos(tracks)
        val info = trackInfos.firstOrNull { it.kind == TrackKindFfi.SUBTITLE && it.id == id }
        if (id != TRACK_PICKER_SUBTITLE_OFF_ID && info == null) return

        // See chooseAudio's comment on why this cancels the pending resolution outright.
        cancelTrackSelection()
        dropPendingReapply(TrackKindFfi.SUBTITLE)
        manualTrackChoiceMade = true
        val sidecar = ExternalSubtitles.indexOf(id)
        val memory = if (info == null) TrackMemoryFfi.Off else keyedMemory(gateway.trackPrefKeyOf(info))
        var decision: TrackDecisionFfi? = null
        if (sidecar != null) {
            // Embedded text is off under a sidecar, and stays off if the file fails.
            recordedText = TextChoice.Off
            // Remembered only once it loads, so a broken file never follows the series.
            showSidecar(sidecar) { rememberTrackMemoryIfSeries(TrackKindFfi.SUBTITLE, memory) }
        } else {
            hideSidecar()
            decision = if (info == null) SUBTITLES_OFF else TrackDecisionFfi(audioTrackId = null, subtitleAction = SubtitleActionFfi.LEAVE, subtitleTrackId = id)
            playerHolder.applyTrackDecision(decision, tracks)
            recordDecision(decision, trackInfos)
            rememberTrackMemoryIfSeries(TrackKindFfi.SUBTITLE, memory)
        }

        renegotiateIfBurnedIn() // first, so the burn-in rows below read what it asked for
        // A sidecar pick reads live state (it may already have failed); an embedded one flips optimistically.
        val choices = subtitleChoices(tracks)
        val effective = decision?.let { applyDecisionToTrackInfos(trackInfos, it) } ?: allTrackInfos(tracks)
        _state.update { state ->
            state.copy(
                nonDefaultTrackActive = computeNonDefaultTrackActive(effective),
            ).withTrackPicker { it.copy(subtitleTracks = if (sidecar != null) choices else choices.map { choice -> choice.copy(selected = choice.id == id) }) }
        }
    }

    /** docs/18 §3.1: picks an embedded stream only burn-in can show: text stays off on the player and
     * the transcode is renegotiated with it. Picking the one already burned in changes nothing.
     */
    private fun chooseBurnable(id: Long, tracks: Tracks) {
        val info = burnableSubtitles(tracks).firstOrNull { it.id == id } ?: return
        // Already burned in and nothing negotiating; mid-negotiation the landing re-checks the choice.
        if (info.isSelected && negotiation == null) return
        cancelTrackSelection()
        dropPendingReapply(TrackKindFfi.SUBTITLE)
        manualTrackChoiceMade = true
        hideSidecar()
        playerHolder.applyTrackDecision(SUBTITLES_OFF, tracks)
        recordedText = TextChoice.Embedded(info.identity())
        rememberTrackMemoryIfSeries(TrackKindFfi.SUBTITLE, keyedMemory(gateway.trackPrefKeyOf(info)))
        renegotiateIfBurnedIn()
        _state.update { state ->
            state.copy(
                nonDefaultTrackActive = computeNonDefaultTrackActive(applyDecisionToTrackInfos(allTrackInfos(tracks), SUBTITLES_OFF)),
            ).withTrackPicker { picker -> picker.copy(subtitleTracks = subtitleChoices(tracks).map { it.copy(selected = it.id == id) }) }
        }
    }

    /** Shared by [chooseAudio]/[chooseSubtitle] -- see [chooseAudio]'s own doc comment for the
     * Movie skip-remember rule.
     */
    private fun rememberTrackMemoryIfSeries(kind: TrackKindFfi, memory: TrackMemoryFfi) {
        val seriesId = currentSeriesId ?: return
        viewModelScope.launch { runCatchingCancellable { gateway.rememberTrackMemory(seriesId, kind, memory) } }
    }

    /** A saved position this session asked to resume from; an unseekable file can't honor it. */
    private var resumeNoticePending = false

    /** Re-reads the player's seekability (docs/12 §9) on every state or timeline change; a resume
     * the file can't seek to plays from the start and says so once. */
    private fun refreshSeekable() {
        val seekability = playerHolder.seekability
        if (_state.value.seekability != seekability) _state.update { it.copy(seekability = seekability) }
        if (seekability != Seekability.UNKNOWN && resumeNoticePending) {
            resumeNoticePending = false
            if (seekability == Seekability.UNSEEKABLE) notifySeekUnavailable()
        }
    }

    /** The screen showed the notice: drop it from the replay cache so a re-attached collector
     * doesn't show it again (docs/12 §9). */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun noticeShown() {
        val last = _events.replayCache.lastOrNull()
        if (last == PlaybackEvent.SeekUnavailable || last == PlaybackEvent.SubtitleUnavailable) _events.resetReplayCache()
    }

    /** The live playback position in microseconds, for the sidecar cue feed (docs/18 §3.2). */
    fun positionUs(): Long = PlaybackTicks.ticksToUs(playerHolder.currentPositionTicks())

    /** Raises the one-shot "can't seek" notice for a UI-side seek attempt (docs/12 §9). */
    fun notifySeekUnavailable() {
        _events.tryEmit(PlaybackEvent.SeekUnavailable)
    }

    /** `true` when a seek may proceed; otherwise emits the notice (docs/12 §9, [SeekGuard]). */
    private fun seekAllowed(): Boolean {
        // The live value, so a seek racing the state update can't slip through on a stale one.
        refreshSeekable()
        return when (SeekGuard.route(_state.value.seekability)) {
            SeekRoute.SEEK -> true
            SeekRoute.NOTICE -> {
                notifySeekUnavailable()
                false
            }
            SeekRoute.DROP -> false
        }
    }

    private fun updatePlayState() {
        val isActuallyPlaying = playerHolder.isPlaying
        val playbackState = playerHolder.playbackState
        refreshSeekable()
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
            sessionOnScreen()?.let { reportTo ->
                viewModelScope.launch { runCatchingCancellable { gateway.reportPaused(reportTo, isPaused) } }
            }
        }

        // docs/12 §15: a stall only gets the pill while intending to play.
        updateBufferingTicker(isBuffering && playWhenReady)
    }

    /**
     * Resolves Media3's terminal state -- the progress ticker normally starts autoplay before
     * EOF, but ENDED can beat its next tick. Preserves autoplay when its card is eligible;
     * otherwise closes the reporting session and tells the Activity to leave playback.
     * A [transitionTo] ends the session synchronously, making natural EOF and a simultaneous
     * countdown/Enter mutually exclusive. A no-op while a "still watching?" card is
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
        if (owner !is SessionOwner.Playing) return
        finishAfterStop(reason = "ended")
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

    /** The play/pause intent, which [MediaKeyRouter] reads so a repeated Play or Pause never toggles. */
    val playWhenReady: Boolean get() = playerHolder.playWhenReady

    /** docs/17 §2: a remote's Stop ends playback exactly like Back, whatever the session is doing --
     * a transition still preparing its title is retired with it, so that title never plays.
     */
    fun stopFromRemote() = finishAfterStop(reason = "remote")

    /** docs/17 §2: the one end the viewer asked for. [stopPlaybackOnce] is the exactly-once guard and
     * retires any pending request; with the still-watching card up it is that card's Stop, as Back.
     */
    private fun finishAfterStop(reason: String) {
        if (_state.value.stillWatching != null) return stillWatchingStop()
        stopPlaybackOnce(reason = reason)
        _events.tryEmit(PlaybackEvent.Finish)
    }

    /** docs/17 §2: an absolute seek asked for by the MediaSession (Assistant, system UI), through the same guarded path as the other seeks. */
    fun seekToPositionMs(positionMs: Long) {
        seekToAbsoluteTicks(PlaybackTicks.msToTicks(positionMs))
    }

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
        val targetMs = performSeek(deltaMs) ?: return null

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
        return performSeek(deltaMs, extraClampMs = endClampMs) ?: PlaybackTicks.ticksToMs(_positionTicks.value)
    }

    /**
     * Shared body of [seek]/[tapSeek]: clamps, issues the seek, returns the target ms.
     * [extraClampMs] (only [tapSeek]'s end clamp) caps the target below the ordinary duration
     * clamp when set. Leaves [endClampHold] alone, but while active keeps [endHoldReportTicks]
     * current, same as [seekToAbsoluteTicks]. `null` when the file is unseekable (docs/12 §9).
     */
    private fun performSeek(deltaMs: Long, extraClampMs: Long? = null): Long? {
        if (!seekAllowed()) return null
        val currentMs = PlaybackTicks.ticksToMs(_positionTicks.value)
        val durationMs = _state.value.durationTicks?.let { PlaybackTicks.ticksToMs(it) }
        var targetMs = SeekMath.clampSeekTarget(currentMs, deltaMs, durationMs)
        val boundByExtraClamp = extraClampMs != null && targetMs > extraClampMs
        if (boundByExtraClamp) targetMs = extraClampMs

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
        if (!seekAllowed()) return
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
     * [endHoldReportTicks] current while [endClampHold] is active. `false` (nothing moved) when the
     * file is unseekable (docs/12 §9).
     */
    private fun seekToAbsoluteTicks(targetTicks: Long): Boolean {
        if (!seekAllowed()) return false
        val currentMs = PlaybackTicks.ticksToMs(_positionTicks.value)
        val durationMs = _state.value.durationTicks?.let { PlaybackTicks.ticksToMs(it) }
        val targetMs = SeekMath.clampSeekTarget(currentMs, PlaybackTicks.ticksToMs(targetTicks) - currentMs, durationMs)
        beginSeekBufferingGrace()
        playerHolder.seekBy(targetMs - currentMs)
        _positionTicks.value = PlaybackTicks.msToTicks(targetMs)
        noteHoldSeekTarget(targetMs)
        return true
    }

    /** Prev/next-chapter transport (docs/12; also Page Up/Page Down, [tv.jellybeam.player.PageKeys])
     * -- a no-op with no markers or nothing left to jump to. Returns the seeked-to ticks, or `null`
     * if nothing happened.
     */
    fun jumpToChapter(forward: Boolean): Long? {
        val target = Chapters.jumpTargetTicks(_state.value.chapters, _positionTicks.value, forward) ?: return null
        if (!seekToAbsoluteTicks(target)) return null
        return target
    }

    /** Chapters menu row activation (docs/12 §12): seeks to [startPositionTicks] and closes the
     * menu, same "pick and dismiss" shape as a normal menu row.
     */
    fun jumpToChapterStart(startPositionTicks: Long) {
        seekToAbsoluteTicks(startPositionTicks)
        replaceSurface { if (it is OpenSurface.Chapters) OpenSurface.None else it }
    }

    // -- Speed menu / chapters menu (docs/12 §8, §12) --------------------

    /**
     * docs/12 §12: the one writer of [PlaybackUiState.openSurface]; an in-flight library-info request dies with any
     * result that is not the library-info panel. Kind-preserving content updates of an open picker stay in
     * [PlaybackUiState.withTrackPicker].
     */
    private fun replaceSurface(transform: (OpenSurface) -> OpenSurface) {
        val after = _state.updateAndGet { it.copy(openSurface = transform(it.openSurface)) }.openSurface
        if (after !is OpenSurface.LibraryInfo) {
            libraryInfoJob?.cancel()
            libraryInfoJob = null
        }
    }

    /** docs/12 §12: opens [surface], replacing whichever was open. */
    private fun open(surface: OpenSurface) {
        // replaceSurface cancels for every other surface; reopening library info keeps the panel, so it cancels here.
        if (surface is OpenSurface.LibraryInfo) {
            libraryInfoJob?.cancel()
            libraryInfoJob = null
        }
        replaceSurface { surface }
    }

    /** docs/12 §12: closes whichever surface is open (Back, Escape / Menu, a session end). */
    fun closeSurface() = replaceSurface { OpenSurface.None }

    /** Opens the speed menu, replacing any other [PlaybackUiState.openSurface]. Cancels an
     * in-flight library-info request too, since its result would populate a panel nobody can see.
     */
    fun openSpeedMenu() {
        open(OpenSurface.Speed)
    }

    /** See [openSpeedMenu]'s own doc comment -- the same replace-the-open-surface shape. */
    fun openChaptersMenu() {
        // docs/12 §8: chapters only seek, so an unseekable file refuses the menu with the notice.
        refreshSeekable()
        if (_state.value.seekability == Seekability.UNSEEKABLE) {
            notifySeekUnavailable()
            return
        }
        open(OpenSurface.Chapters)
    }

    /** Skip-intro/credits (docs/12 §14) -- seeks to [segment]'s end and returns the pre-skip
     * position for the Undo toast ([undoSkip]), or `null` on an unseekable file. Never re-validates [segment]; a
     * backwards/no-op seek is harmless. Shared by the manual pill and [evaluateAutoSkip].
     */
    fun skipSegment(segment: MediaSegment): Long? {
        val before = _positionTicks.value
        if (!seekToAbsoluteTicks(segment.endTicks)) return null
        offerSkipUndo(SkipUndoState(before, segment.segmentType))
        return before
    }

    /** Undoes a [skipSegment] call -- seeks back to [preSkipPositionTicks] (docs/12 §14's Undo
     * toast) and drops the toast.
     */
    fun undoSkip(preSkipPositionTicks: Long) {
        seekToAbsoluteTicks(preSkipPositionTicks)
        dismissSkipUndo()
    }

    /** Undoes the skip the toast is showing; a no-op once it has expired or been dismissed. */
    fun undoSkip() {
        val undo = _state.value.skipUndo ?: return
        // docs/12 §14: `start` dismisses the toast, so a live scope is the one that offered it.
        if (liveScope == null) return dismissSkipUndo()
        undoSkip(undo.preSkipPositionTicks)
    }

    /** Drops the Undo toast and its expiry timer (an arrow key makes it stale, docs/12 §14). */
    fun dismissSkipUndo() {
        skipUndoExpiry?.cancel()
        skipUndoExpiry = null
        _state.update { it.copy(skipUndo = null) }
    }

    /** docs/12 §14: an equal token keeps the running timer, so only a changed toast restarts the window. */
    private fun offerSkipUndo(undo: SkipUndoState) {
        if (_state.value.skipUndo == undo) return
        skipUndoExpiry?.cancel()
        _state.update { it.copy(skipUndo = undo) }
        skipUndoExpiry = viewModelScope.launch {
            delay(SKIP_UNDO_WINDOW_MS)
            _state.update { it.copy(skipUndo = null) }
            skipUndoExpiry = null
        }
    }

    /** Opens the playback stats sheet (docs/12 §17): a no-op if [playbackOsdDetail] hasn't resolved
     * yet. Static content is built directly by [tv.jellybeam.player.PlaybackScreen] via
     * [StatsSheetFormat.build]; this only seeds the live half, refreshed by
     * [refreshStatsSheetLive].
     */
    fun openInfoOverlay() {
        if (playbackOsdDetail == null) return
        open(OpenSurface.Stats(playerHolder.livePlaybackStats()))
    }

    /** docs/09: re-reads the subtitle style on resume, so a Settings change made from PiP or the
     * background reaches the running session; a failed read keeps the current style. */
    fun refreshSubtitleStyle() {
        viewModelScope.launch {
            val settings = try {
                settingsStore.load()
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
        replaceSurface { if (it is OpenSurface.Stats) OpenSurface.Stats(playerHolder.livePlaybackStats()) else it }
    }

    /** The stats sheet's SUBTITLES row (docs/12 §17): selected subtitle's title (`null` for Off)
     * plus track count, derived from the same [PlaybackPlayer.currentTracks] snapshot
     * [buildTrackPickerState] reads. `null to 0` before any [Tracks] announced.
     */
    fun currentSubtitleSelection(): Pair<String?, Int> {
        val tracks = playerHolder.currentTracks ?: return null to 0
        val subtitles = allTrackInfos(tracks).filter { it.kind == TrackKindFfi.SUBTITLE }
        val selected = subtitles.firstOrNull { it.isSelected }
        val title = selected?.let { TrackChoiceText.label(strings, it, subtitles.indexOf(it)) }
        return title to subtitles.size
    }

    /** Opens the library-info panel and starts its live detail request; the synchronous Loading
     * state gives Select immediate feedback even on a slow server. Idempotent; an old session's
     * result can never populate a new one. Builds via [LibraryInfoFormat.buildSheet] (docs/12 §17).
     */
    fun openLibraryInfoOverlay() {
        if (_state.value.libraryInfoOverlay != null) return
        val scope = liveScope ?: return
        open(OpenSurface.LibraryInfo(LibraryInfoOverlayState.Loading))
        libraryInfoJob = viewModelScope.launch {
            val result = try {
                LibraryInfoOverlayState.Content(LibraryInfoFormat.buildSheet(strings, gateway.getItemDetail(scope.itemId, scope.accountEpoch)))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                LibraryInfoOverlayState.Unavailable
            }
            ensureActive()
            if (!scope.isCurrent()) return@launch
            replaceSurface { if (it is OpenSurface.LibraryInfo) OpenSurface.LibraryInfo(result) else it }
            libraryInfoJob = null
        }
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
        launchFinalStop(snapshot)
    }

    /** Ends the current session and waits for its core reporting state to close; [keep] is the
     * transition's own request, which survives the teardown.
     */
    private suspend fun stopPlaybackAndAwait(keep: PlaybackRequest, reason: String = "transition") {
        val snapshot = endSessionForStop(keep) ?: return run { stopLanding?.await() }
        recordPlaybackStop(snapshot, reason)
        launchFinalStop(snapshot).await()
    }

    /** docs/18 §2.1: the one launch of a session's final stop, whichever path ended it. The returned
     * landing is [stopLanding] until the core has closed the session, so a newer request's prepare
     * ([replaceWith]) never replaces it mid-report and loses the final position.
     */
    private fun launchFinalStop(snapshot: StopSnapshot): CompletableDeferred<Unit> {
        val landing = CompletableDeferred<Unit>().also { stopLanding = it }
        // Survives teardown: once captured, this stop is the only thing that ends the session it
        // names, a landed fallback included (see adoptPlan).
        reportScope.launch {
            try {
                withContext(NonCancellable) { runCatchingCancellable { gateway.stopPlayback(snapshot.playSessionId, snapshot.ticks) } }
            } finally {
                landing.complete(Unit)
            }
        }
        return landing
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
     * Shared job-cancellation/menu-closing half of exit cleanup, run by [endSessionOnce] right after
     * its exactly-once guard. Cancels
     * [reportTickerJob], [bufferingTickerJob], the seek grace, [reconnectJob] (a Back press during
     * Reconnecting must cancel any pending retry, not race it), [libraryInfoJob], and
     * [trackSelectionJob], and the still-watching card with its timeout (which must not fire a stop for
     * a session that has ended). Also closes whichever surface is open, which must not survive it.
     */
    private fun cancelSessionJobsAndCloseMenus() {
        reportTickerJob?.cancel()
        bufferingTickerJob?.cancel()
        clearSeekBufferingGrace()
        reconnectJob?.cancel()
        libraryInfoJob?.cancel()
        cancelTrackSelection()
        // A stopped player reads 0:00, so a still-fed sidecar would flash its first cue.
        hideSidecar()
        clearStillWatching()
        closeSurface()
    }

    /** docs/18 §2.1: ends the playing session exactly once (`null` if none was); [report] reads the
     * on-screen id (`null` if start() never installed a plan) after its jobs stop, before the player clears.
     */
    private inline fun <T> endSessionOnce(keep: PlaybackRequest?, report: (playSessionId: String?) -> T?): T? {
        // Before the exactly-once guard: a stop while the first prepare negotiates must retire it too.
        retireRequests(keep)
        val ending = owner as? SessionOwner.Playing ?: return null
        // Read here synchronously -- see StopSnapshot's doc comment.
        val playSessionId = sessionOnScreen()
        owner = SessionOwner.Ended(ending.generation, playSessionId)
        cancelSessionJobsAndCloseMenus()
        return report(playSessionId).also { playerHolder.stopAndClear() }
    }

    /** Player-side, exactly-once half shared by normal exits and play-next transitions. */
    private fun endSessionForStop(keep: PlaybackRequest? = null): StopSnapshot? = endSessionOnce(keep) { playSessionId ->
        // A reconnect episode leaves the player idle after the error, so it reports the remembered position.
        val lastKnownTicks = _positionTicks.value
        val currentTicks = reconnectPositionTicks
            ?: playerHolder.currentPositionTicks().takeIf { it > 0L || lastKnownTicks <= 0L }
            ?: lastKnownTicks
        // docs/12 §9: exiting an active end-clamp hold reports where the viewer reached, never the clamp point.
        playSessionId?.let { StopSnapshot(it, if (endClampHold) endHoldReportTicks ?: currentTicks else currentTicks) }
    }

    /** Error path: discards the session without a final report; a session prepare never opened has nothing to abandon. */
    private fun abandonPlaybackOnce() {
        endSessionOnce(keep = null) { id -> id?.let { reportScope.launch { runCatchingCancellable { gateway.abandonPlayback(it) } } } }
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
     * `onStop`/`onDestroy` already ran [stopPlaybackOnce]. A wired player's listener and its own
     * decoder hook are removed so the shared [PlayerHolder] never keeps a dead reference, and a newer
     * view model's hook is left alone.
     */
    override fun onCleared() {
        super.onCleared()
        previewer?.endSession()
        if (playerWired) {
            playerHolder.removeListener(playerListener)
            if (playerHolder.onVideoDecoderInitialized === decoderHook) playerHolder.onVideoDecoderInitialized = null
        }
        stopPlaybackOnce()
    }
}

class PlaybackViewModelFactory(
    private val gateway: CoreGateway,
    private val playerHolder: PlaybackPlayer,
    private val itemId: String,
    private val startFromBeginning: Boolean = false,
    private val strings: UiStrings,
    private val sheetFetcher: SheetFetcher? = null,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(PlaybackViewModel::class.java))
        return PlaybackViewModel(gateway, playerHolder, itemId, strings, startFromBeginning = startFromBeginning, sheetFetcher = sheetFetcher) as T
    }
}
