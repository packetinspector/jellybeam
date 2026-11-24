package tv.jellybeam.player

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
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
import tv.jellybeam.player.ass.isSeekIndexMiss
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import uniffi.jellybeam_core.SyncPlayRole
import uniffi.jellybeam_core.AccountIdentity
import uniffi.jellybeam_core.Card
import uniffi.jellybeam_core.ChapterInfoFfi
import uniffi.jellybeam_core.CoreException
import uniffi.jellybeam_core.EmbeddedSubtitleFfi
import uniffi.jellybeam_core.FailedTrackFfi
import uniffi.jellybeam_core.MediaSegment
import uniffi.jellybeam_core.MediaSegmentKind
import uniffi.jellybeam_core.OsdDetailSetting
import uniffi.jellybeam_core.PlaybackOsdDetail
import uniffi.jellybeam_core.PlaybackPlan
import uniffi.jellybeam_core.PlaybackRequest
import uniffi.jellybeam_core.PlayMethodFfi
import uniffi.jellybeam_core.ReportFactsFfi
import uniffi.jellybeam_core.SegmentAction
import uniffi.jellybeam_core.StillWatchingDecision
import uniffi.jellybeam_core.SubtitleActionFfi
import uniffi.jellybeam_core.SubtitleDeliveryFfi
import uniffi.jellybeam_core.SyncPlayInput
import uniffi.jellybeam_core.SyncPlaySeverity
import uniffi.jellybeam_core.TrackDecisionFfi
import uniffi.jellybeam_core.TrackDeliveryFfi
import uniffi.jellybeam_core.TrackInfo
import uniffi.jellybeam_core.TrackKindFfi
import uniffi.jellybeam_core.TrackMemoryFfi
import uniffi.jellybeam_core.TranscodeCauseFfi
import uniffi.jellybeam_core.TranscodeStreamsFfi
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
 * Sentinel [TrackPickerRow.id] for the track picker's synthetic subtitle "Off"
 * row (docs/09 slice 3b) -- never a real [TrackMapping] id (always `>= 0`),
 * so it resolves to `null` rather than a wrong track if mistakenly threaded
 * into [TrackMapping.resolve].
 */
const val TRACK_PICKER_SUBTITLE_OFF_ID: Long = -1L

/** docs/18 §3.1: picker ids for a transcode's streams that show only after a renegotiation, below
 * every real [TrackMapping] id and [TRACK_PICKER_SUBTITLE_OFF_ID].
 */
private const val STREAM_ROW_ID_BASE = -1_000L

internal fun streamRowId(index: Int): Long = STREAM_ROW_ID_BASE - index

private fun streamRowIndexOf(id: Long): Int? = id.takeIf { it <= STREAM_ROW_ID_BASE }?.let { (STREAM_ROW_ID_BASE - it).toInt() }

private fun EmbeddedSubtitleFfi.facts() = SubtitleFacts(hearingImpaired = hearingImpaired, forced = forced, codec = codec)

/**
 * docs/12 §16: [infos] with the server's [facts] copied onto the subtitle rows they name. Media3 has
 * no SDH flag and names a format by mime ("application/x-subrip") where the server says "subrip".
 */
internal fun withServerFacts(infos: List<TrackInfo>, facts: Map<Long, SubtitleFacts>): List<TrackInfo> =
    infos.map { info ->
        val fact = facts[info.id]?.takeIf { info.kind == TrackKindFfi.SUBTITLE } ?: return@map info
        info.copy(
            codec = fact.codec?.takeIf { it.isNotBlank() } ?: info.codec,
            isForced = info.isForced || fact.forced,
            isHearingImpaired = fact.hearingImpaired,
        )
    }

/**
 * docs/12 §16: the server's facts per picker id for a Direct Play file. Media3 calls ASS and SSA
 * alike, so its text tracks take the server's by position, only when the counts and every language
 * agree: a wrong SDH is worse than none. A transcode's and a sidecar's rows carry their own.
 */
internal fun serverSubtitleFacts(infos: List<TrackInfo>, streams: List<EmbeddedSubtitleFfi>): Map<Long, SubtitleFacts> {
    val text = infos.filter { it.kind == TrackKindFfi.SUBTITLE }
    // A manifest sidecar copy is not a track of the file Media3 reads.
    val inFile = streams.filter { it.delivery != SubtitleDeliveryFfi.HLS }
    val paired = inFile.size == text.size && inFile.zip(text).all { (stream, info) -> sameTrack(stream.identity(), info.identity()) }
    return if (paired) text.zip(inFile).associate { (info, stream) -> info.id to stream.facts() } else emptyMap()
}

/** docs/18 §3.2: loaded cues are positions in the same file, so a same-source [landed] plan keeps listing the [loaded] sidecars it no longer serves. */
internal fun keepLoadedSidecars(previous: PlaybackPlan, landed: PlaybackPlan, loaded: Set<Int>): PlaybackPlan {
    if (landed.mediaSourceId != previous.mediaSourceId) return landed
    val kept = previous.externalSubtitles.filter { old -> old.index in loaded && landed.externalSubtitles.none { it.index == old.index } }
    if (kept.isEmpty()) return landed
    val keptRows = previous.trackRows.filter { row -> row.delivery == TrackDeliveryFfi.SIDECAR && kept.any { it.index == row.streamIndex } }
    return landed.copy(externalSubtitles = landed.externalSubtitles + kept, trackRows = landed.trackRows + keptRows)
}

/** docs/21 §2.1: what started a transcode negotiation; [tag] is its diag value. */
internal enum class Renegotiation(val tag: String) {
    FALLBACK("fallback"),
    PICK("pick"),
    AUDIO_PICK("audio_pick"),
    /** The choice changed while a negotiation ran. */
    CHANGED("changed"),
}

/** Embedded subtitles off, nothing else touched. */
private val SUBTITLES_OFF = TrackDecisionFfi(audioTrackId = null, subtitleAction = SubtitleActionFfi.OFF, subtitleTrackId = null)

/**
 * docs/18 §1/§3: message shown once [PlaybackViewModel.maybeFallBackToTranscode]
 * has declined to take over. A DirectPlay [plan] carrying a [PlaybackPlan.serverVerdict]
 * means the server would have transcoded and only Direct Played because CLAUDE.md's
 * Direct Play rule forced it -- names that verdict and the Quality setting that
 * would unlock a transcode; a Transcode plan's 5xx [httpStatus] is the server's conversion failing (docs/18 §7);
 * every other case falls back to `"Playback error: <code>"`.
 */
internal fun fatalPlaybackMessage(strings: UiStrings, errorCodeName: String, plan: PlaybackPlan?, httpStatus: Int? = null): String {
    val serverVerdict = plan?.takeIf { it.playMethod == PlayMethodFfi.DIRECT_PLAY }?.serverVerdict
    return when {
        serverVerdict != null -> strings.get(R.string.player_error_direct_play_blocked, errorCodeName, serverVerdict)
        plan?.playMethod == PlayMethodFfi.TRANSCODE && httpStatus in 500..599 ->
            strings.get(R.string.player_error_transcode_failed)
        else -> strings.get(R.string.player_error_generic, errorCodeName)
    }
}

/** docs/18 §7: a track type no fallback will rescue; for video on a Transcode [plan] the likely cause is an account
 * without video transcoding, so name it as a condition. */
internal fun unplayableTrackMessage(strings: UiStrings, failed: FailedTrackFfi, mime: String, plan: PlaybackPlan?): String {
    val audio = failed == FailedTrackFfi.AUDIO
    val track = strings.get(if (audio) R.string.player_error_track_audio else R.string.player_error_track_video)
    val unconverted = !audio && plan?.playMethod == PlayMethodFfi.TRANSCODE
    return strings.get(if (unconverted) R.string.player_error_unplayable_track_unconverted else R.string.player_error_unplayable_track, track, mime)
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
@ConsistentCopyVisibility
data class PlaybackUiState internal constructor(
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
     * and the full `Card` fields exist only for the episode neighbours, never the
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
    /** docs/12 §8: the audio & subtitles button's accent dot, derived from the track choice by [selection]. */
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
    /** docs/28 §1: the membership this player plays for, `null` while solo; the screen reads the group from its state. */
    internal val syncPlay: SyncPlayExecutor? = null,
    /** docs/28 §1: a subtitle or audio pick the group refused, shown once as a notice ([PlaybackViewModel.trackPickRefusedShown]). */
    val trackPickRefused: Boolean = false,
    /** docs/18 §3.1: a subtitle or audio change the server refused while the stream plays on, shown once ([PlaybackViewModel.trackChangeFailedShown]). */
    val trackChangeFailed: Boolean = false,
) {
    enum class Phase { LOADING, READY }

    /** The speed menu (docs/12 §12). */
    val speedMenuOpen: Boolean get() = openSurface is OpenSurface.Speed

    /** The subtitle delay control (docs/12 §16): non-null exactly while open. */
    val subtitleDelay: DelayState? get() = (openSurface as? OpenSurface.DelayControl)?.state

    /** What Back closes first: the speed or chapters menu, or the delay control (docs/12 §9). */
    val menuOpen: Boolean get() = speedMenuOpen || chaptersMenuOpen || subtitleDelay != null

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

    /** docs/12 §16: the subtitle delay chip, opened from the track picker's Delay row. */
    data class DelayControl(val state: DelayState) : OpenSurface
}

/** docs/12 §9, §16: whether this surface pins the OSD visible; the delay control instead hides it so subtitles sit at normal padding. */
val OpenSurface.pinsOsd: Boolean get() = this !is OpenSurface.None && this !is OpenSurface.DelayControl

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
data class TrackPickerRow(
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
    val audioTracks: List<TrackPickerRow>,
    val subtitleTracks: List<TrackPickerRow>,
    /** docs/12 §16: the delay, already worded, trailing the final "Delay" row. */
    val delayText: String = "",
) {
    /** docs/12 §16: the "Delay" row ends the list exactly while a real subtitle (embedded or sidecar) is selected. */
    val delayRowShown: Boolean get() = subtitleTracks.any { it.selected && it.id >= 0 }

    /** Rows in the one focus order: audio, subtitles, then the Delay row. */
    val rowCount: Int get() = audioTracks.size + subtitleTracks.size + if (delayRowShown) 1 else 0
}

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
class PlaybackViewModel internal constructor(
    private val gateway: CoreGateway,
    internal val playerHolder: PlaybackPlayer,
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
    /** docs/18 §7: the playback end's clock, monotonic so a wall-clock jump never stretches or cuts its window. */
    private val monotonicClock: Clock = Clock { android.os.SystemClock.elapsedRealtime() },
    /** docs/18 §3.2: sidecar parsing, off the main thread; tests inject a fake. */
    private val parseSidecar: suspend (codec: String, text: String) -> SidecarCues? = { codec, text ->
        withContext(Dispatchers.Default) { ExternalSubtitles.parse(codec, text) }
    },
    /** docs/09: the one settings reader (single writer); tests inject a store over their fake. */
    private val settingsStore: SettingsStore = AppGraph.settingsStore,
    /** docs/28 §4: a player launched for a group plays nothing until the group's Load. */
    private val forGroup: Boolean = false,
    /** docs/05 external: START_MS for the constructor's initial [start] only; implies [startFromBeginning]. */
    private val startMs: Long? = null,
    /** docs/31 §4.1: a remote Play's list and stream picks, for the constructor's initial [start] only. */
    private val remoteQueue: RemoteQueue? = null,
    private val trackRequest: TrackRequest? = null,
    /** docs/31 Seam 1 R3: the bridge's one drop line, for a remote Play this player refuses after it was routed. */
    private val remoteDropped: (cmd: String, why: DropWhy) -> Unit = { cmd, why -> AppGraph.remoteControl.dropped(cmd, why) },
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

    /** The item the live session plays on the browsed account, else `null`: the Activity's own id goes stale after autoplay,
     * and a parked account's session must be replaced, not expanded (docs/05 external, docs/17 §2). */
    internal val liveItemId: String? get() = liveScope?.takeIf { it.accountEpoch == gateway.accountEpochs.value.epoch }?.itemId

    /** The live session's scope for the remote router (docs/31 §5): `null` while none plays. */
    internal val liveSession: SessionScope? get() = liveScope

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

    /** docs/18 §7: how the current load ends; main thread, written only by [sendEnd]. */
    private var playbackEnd: PlaybackEnd = PlaybackEnd.NoLoad

    /**
     * docs/18 §1/§3: this session's current plan, needed by
     * [maybeFallBackToTranscode]'s gate and [fatalPlaybackMessage]'s
     * `serverVerdict`. Reassigned by [start] on every session (including a
     * successful fallback's replacement plan); unlike
     * [reconnectPositionTicks] it's replaced, never cleared to `null`
     * mid-session.
     */
    private var currentPlan: PlaybackPlan? = null

    /** docs/18 §5.4: the play session that spent its one cold retry after a seek-index miss. */
    private var coldRetriedSession: String? = null

    /** docs/21 §2.1's `playback.prepare` `type` tag -- the previous session's [PlaybackPlan
     * .itemType], best-effort only: the constructor's very first [start] call has no prior plan
     * to read a type from, so that one line's `type` reads "unknown". */
    private var lastKnownItemType: String? = null

    /** One fallback per item (docs/18 §1): `true` once [maybeFallBackToTranscode] has attempted a
     * fallback this session, so any later local-unplayability signal falls straight to the fatal
     * path. Reset by every [start].
     */
    private var transcodeFallbackAttempted: Boolean = false

    /** docs/18 §2: this session's fallback reason and the track type it blamed, resent on every
     * renegotiation so a subtitle change never lets the failed stream be copied back. Reset by every [start].
     */
    private var sessionFallback: Pair<String, FailedTrackFfi>? = null

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

    /** docs/31 §4.1.1: the live session's remote list; [sendList] is its only writer. */
    private var remoteList: RemoteList = RemoteList.None

    /** docs/31 §4.1.1: what a [step] plays now, the list's neighbour before the episode's. */
    private fun successorFor(step: ListStep): Successor = successor(remoteList, neighbors, step)

    /** docs/31 §7: position, pause, facts and immediate reports, in call order; each names its session on Main as it is sent. */
    private val reports = ReportQueue(viewModelScope)

    /** docs/31 §7: the facts last sent and the session they went to, since a session a transcode installs starts with none; written by [sendTrack] and [reportRemote]. */
    private var factsSent: Pair<String, ReportFactsFfi>? = null

    /** docs/31 §4.1: a remote Play's stream picks, tried once on the first track announcement; reset by [start]. */
    private var trackHint: TrackRequest? = null

    /** Back-dismiss: once dismissed, the next-up card stays hidden for the rest of this session,
     * reset by [start]. */
    private var nextUpDismissed = false

    /** Guards [showCountdownCard]'s [CoreGateway.preloadPlayback] call to once per next item
     * (docs/18 preload); reset by [start]. */
    private var preloadedNextUpItemId: String? = null

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

    /** docs/18 §3.1: the one track choice; [sendTrack] is its only writer. */
    private var trackChoice: TrackChoice = TrackChoice.Idle
    private val trackInbox = ArrayDeque<TrackEvent>()
    private var trackDispatching = false

    /** docs/18 §3.1: the plan's resolve and sidecar fetches, cancelled when its plan or session ends. */
    private var planScope: CoroutineScope? = null

    /** docs/18 §3.2: picker status and parsed cues per sidecar index, for the plan's media source. */
    private val sidecarStatus = mutableMapOf<Int, SidecarStatus>()
    private val sidecarCache = mutableMapOf<Int, SidecarCues>()

    /** The one sidecar fetch in flight; hiding the sidecar cancels it, so an abandoned file is never parsed. */
    private var sidecarJob: Job? = null

    /** docs/28 §5: read once per entry point; only [executePort] enters a group and [leaveGroupMode] leaves it. */
    internal var mode: PlayerMode = PlayerMode.Solo
        set(value) {
            val entering = value is PlayerMode.Group && field !is PlayerMode.Group
            field = value
            // docs/31 §4.1.1: the group's queue decides what follows, so a remote list ends.
            if (entering) sendList(RemoteListEvent.Clear)
            // docs/28 §1: the one writer of [PlaybackUiState.syncPlay], so it always names the mode's membership.
            _state.update { it.copy(syncPlay = (value as? PlayerMode.Group)?.exec) }
        }

    /** The one port that may act on this player, cleared by [leaveGroupMode]. */
    internal var attached: AttachedPort? = null

    /** docs/28 §4: the port a group-launched Activity attaches to [exec]; the same membership gets the same port, so a recreated Activity re-attaching changes nothing. */
    internal fun portFor(exec: SyncPlayExecutor): GroupPort =
        attached?.takeIf { it.exec === exec } ?: AttachedPort(exec) { port, call -> executePort(port, call) }.also { attached = it }

    /** docs/28 §1: the screen showed the refused-pick notice. */
    fun trackPickRefusedShown() = _state.update { it.copy(trackPickRefused = false) }

    /** docs/18 §3.1: the screen showed the failed-change notice. */
    fun trackChangeFailedShown() = _state.update { it.copy(trackChangeFailed = false) }

    /** docs/28 §1: the player read the oldest live notice, so the executor drops it. */
    fun syncPlayNoticesShown() {
        (mode as? PlayerMode.Group)?.exec?.send(GroupEvent.NoticesShown(NoticeSurface.Player))
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = updatePlayState()
        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) = refreshSeekable()
        override fun onPlaybackStateChanged(playbackState: Int) {
            updatePlayState()
            // The group hears this state before any end it decides (docs/28 §5).
            (mode as? PlayerMode.Group)?.reportState(playbackState, playerHolder)
            observeEnd()
        }

        // playWhenReady has its own callback, independent of onPlaybackStateChanged
        // (e.g. pausing mid-stall never changes playbackState) -- see
        // updateBufferingTicker's gate. `reason` is never inspected.
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            updatePlayState()
            observeEnd()
        }

        override fun onIsLoadingChanged(isLoading: Boolean) = observeEnd()

        override fun onTracksChanged(tracks: Tracks) {
            // Stamped with the plan on screen at receipt, so a later plan never reads them as its own; a prepared stream lists its video or text.
            val announced = tracks.groups.any { it.type == C.TRACK_TYPE_VIDEO || it.type == C.TRACK_TYPE_TEXT }
            currentPlan?.let { sendTrack(TrackEvent.Tracks(it.playSessionId, rowsOnScreen(tracks), announced)) }
            applyTrackHint(tracks)
            // docs/18 §1's second local-evidence signal (the other is onPlayerError below): every track of a
            // present type rejected by this device's decoders. Declined with nothing negotiating, the live owner
            // ends rather than play black or silent.
            LocalPlayability.unplayable(tracks)?.let { (failed, reason, mime) ->
                if (!maybeFallBackToTranscode(reason, failed) && trackChoice.asked == null && liveScope != null) {
                    finishAfterFatalPlaybackError("UNPLAYABLE_TRACK")
                    _events.tryEmit(PlaybackEvent.FinishWithMessage(unplayableTrackMessage(strings, failed, mime, currentPlan)))
                }
            }
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
            if (trackChoice.asked != null) return sendTrack(TrackEvent.PlayerDied(error.errorCodeName))
            val session = currentPlan?.playSessionId
            if (ReconnectPolicy.retriesCold(isSeekIndexMiss(error), session, coldRetriedSession)) {
                coldRetriedSession = session
                return playerHolder.retryAfterError(_positionTicks.value, playerHolder.playWhenReady)
            }
            // docs/18 §7: a failure where the recovery seek left playback stuck ends as that stall, not as this error.
            if (sendEnd(EndEvent.Failed(endFacts()))) return
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
                (mode as? PlayerMode.Group)?.send { GroupEvent.PlayerError(it, SyncPlaySeverity.RECOVERABLE) }
                beginOrContinueReconnect()
            } else if (!maybeFallBackToTranscode("Playback error: ${error.errorCodeName}", LocalPlayability.failedTrack(error))) {
                // docs/18 §1's first local-evidence signal (the other is
                // onTracksChanged above) -- ordinary fatal path once the fallback
                // gate declines.
                failPlayback(error.errorCodeName, httpResponseCode(error))
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

    /** docs/21 §2.1: why a transcode restarts and what it names, so a slow start shows whether the viewer or the policy asked. */
    private fun logRenegotiation(itemId: String, cause: Renegotiation, sent: TranscodeStreamsFfi) {
        val named = sent as? TranscodeStreamsFfi.Named
        AppGraph.diag.event("playback.renegotiate") {
            item("item", itemId)
            tag("cause", cause.tag)
            tag("sub", named?.let { it.subtitle?.toString() ?: "none" } ?: "policy")
            tag("audio", named?.let { it.audio?.toString() ?: "none" } ?: "policy")
        }
    }

    private fun logIgnoredBurn(itemId: String, effect: TrackEffect.BurnIgnored) {
        AppGraph.diag.event("playback.burn_ignored") {
            item("item", itemId)
            tag("sub", effect.asked?.toString() ?: "none")
            tag("burned", effect.burned.toString())
        }
    }

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
        leaveGroup() // docs/28 §1: a transcode needs a stream of its own
        transcodeFallbackAttempted = true
        sessionFallback = reason to failed
        clearReconnectState()
        sendTrack(TrackEvent.Fallback)
        return true
    }

    /**
     * docs/18 §3.1: the one writer of [trackChoice]: each event is reduced and its effects run in
     * order; an event an effect raises waits its turn, and the picker and dot publish once at the end.
     */
    private fun sendTrack(event: TrackEvent) {
        trackInbox.addLast(event)
        if (trackDispatching) return
        trackDispatching = true
        try {
            while (true) {
                val next = trackInbox.removeFirstOrNull() ?: break
                val step = reduceTracks(trackChoice, next)
                trackChoice = step.state
                step.effects.forEach(::runTrackEffect)
            }
        } finally {
            // An effect that threw leaves no queued event to run against a state it never reached.
            trackInbox.clear()
            trackDispatching = false
        }
        publishTracks()
        // docs/31 §7: a changed stream rides the next report; a remote command reports it at once.
        val facts = remoteFacts(trackChoice, remoteList)
        val session = sessionOnScreen()
        if (liveScope != null && session != null && factsSent != session to facts) {
            // docs/31 §4.1.1 (I8): a session new to the server under a playing list hears its list at once, not on the next tick.
            if (reportsAtOnce(factsSent, session, facts)) return reportRemote()
            factsSent = session to facts
            reports.send { gateway.reportFacts(session, facts) }
        }
    }

    /** docs/31 §4.2, §7: an immediate Progress for the live session, from where the player stands, naming the streams shown and the list. */
    internal fun reportRemote() {
        if (liveScope == null) return
        val session = sessionOnScreen() ?: return
        val ticks = playerHolder.currentPositionTicks()
        val facts = remoteFacts(trackChoice, remoteList)
        factsSent = session to facts
        reports.send { gateway.reportNow(session, ticks, facts) }
    }

    /** docs/31 §4.1.1: the one writer of [remoteList]; returns the step's effects for the caller to run. */
    private fun stepList(event: RemoteListEvent): List<RemoteListEffect> {
        val (next, effects) = reduceRemoteList(remoteList, event, grouped = mode is PlayerMode.Group)
        remoteList = next
        publishSuccessor()
        return effects
    }

    /** docs/31 §4.1.1: a list change on the live session, its effects run; returns [DropWhy.NotApplicable] when the list refuses [event]. */
    internal fun sendList(event: RemoteListEvent): DropWhy? {
        var dropped: DropWhy? = null
        stepList(event).forEach {
            when (it) {
                RemoteListEffect.Report -> reportRemote()
                RemoteListEffect.Refused -> dropped = DropWhy.NotApplicable
            }
        }
        return dropped
    }

    /** docs/31 §4.1.1 (I8): the edge buttons show exactly when a step has a successor, and a list's next replaces the next-up card. */
    private fun publishSuccessor() {
        val next = successorFor(ListStep.Next)
        _state.update {
            it.copy(
                hasPreviousEpisode = successorFor(ListStep.Previous) != Successor.None,
                hasNextEpisode = next != Successor.None,
                nextUp = it.nextUp.takeUnless { next is Successor.ListItem },
            )
        }
    }

    /**
     * docs/31 §4.1: a same-item remote Play's list and stream picks over the live session, without a
     * reload (docs/05 "Same item"), so its version stays; reported at once.
     */
    internal fun remoteExtras(queue: RemoteQueue?, tracks: TrackRequest?) {
        if (tracks != null) {
            trackHint = tracks
            playerHolder.currentTracks?.let(::applyTrackHint)
        }
        val before = remoteList
        // docs/31 §4.1.1: the Play was routed before this player refused its list, so the drop is logged here.
        sendList(listOpening(queue))?.let { remoteDropped(PLAY_VIDEO_DIAG, it) }
        if (tracks != null && remoteList == before) reportRemote()
    }

    /**
     * docs/31 §4.1: the hint's streams become the first picks once Media3 announces [tracks], through
     * the picker's own gates, so a hint never forces a transcode the viewer's pick would not.
     */
    private fun applyTrackHint(tracks: Tracks) {
        val hint = trackHint ?: return
        if (tracks.groups.none { it.type == C.TRACK_TYPE_AUDIO || it.type == C.TRACK_TYPE_TEXT }) return
        trackHint = null
        hint.audioStreamIndex?.let { remoteRowFor(TrackKindFfi.AUDIO, it) }?.let { chooseAudio(it, byUser = false) }
        hint.subtitleStreamIndex?.let { remoteRowFor(TrackKindFfi.SUBTITLE, it) }?.let { chooseSubtitle(it, byUser = false) }
    }

    /** docs/31 §4.3: the picker row on screen showing server stream [index] of [kind], `null` when none does. */
    internal fun remoteRowFor(kind: TrackKindFfi, index: Int): Long? {
        val plan = currentPlan ?: return null
        val tracks = playerHolder.currentTracks ?: return null
        return rowForStreamIndex(plan.trackFacts(), rowsOnScreen(tracks), kind, index)
    }

    private fun runTrackEffect(effect: TrackEffect) {
        when (effect) {
            is TrackEffect.Apply -> playerHolder.applyTrackDecision(effect.decision, playerHolder.currentTracks ?: Tracks.EMPTY)
            // OFF carries no track id, so no snapshot is needed to apply it.
            TrackEffect.TextOff -> playerHolder.applyTrackDecision(SUBTITLES_OFF, Tracks.EMPTY)
            is TrackEffect.Resolve -> resolveTracks(effect)
            is TrackEffect.Negotiate -> negotiateTranscode(effect)
            is TrackEffect.Commit -> commitTranscode(effect)
            is TrackEffect.Load -> loadCommitted(effect.plan)
            is TrackEffect.FetchSidecar -> fetchSidecar(effect.session, effect.index)
            is TrackEffect.ShowSidecar -> showSidecar(effect.index)
            TrackEffect.HideSidecar -> {
                sidecarJob?.cancel()
                playerHolder.showAssSidecar(null)
                // A load no longer chosen stops reading Loading; a refusal mid-swap keeps it.
                sidecarStatus.entries.removeAll { it.value == SidecarStatus.LOADING }
            }
            is TrackEffect.SidecarUnavailable -> {
                sidecarStatus[effect.index] = SidecarStatus.UNAVAILABLE
                _events.tryEmit(PlaybackEvent.SubtitleUnavailable)
            }
            TrackEffect.ResetSidecarCache -> {
                sidecarStatus.clear()
                sidecarCache.clear()
            }
            TrackEffect.ClosePicker -> replaceSurface { if (it is OpenSurface.Tracks) OpenSurface.None else it }
            is TrackEffect.BurnIgnored -> currentPlan?.let { logIgnoredBurn(it.itemId, effect) }
            is TrackEffect.Remember -> rememberTrackMemory(effect.kind, effect.row?.let(gateway::trackMemoryOf) ?: TrackMemoryFfi.Off)
            TrackEffect.ResetDelay -> if (playerHolder.subtitleDelayMs != 0L) playerHolder.setSubtitleDelayMs(0L)
            TrackEffect.Refused -> _state.update { it.copy(trackPickRefused = true) }
            TrackEffect.Notice -> _state.update { it.copy(trackChangeFailed = true) }
            is TrackEffect.Fatal -> finishFor(effect.outcome, effect.error)
            is TrackEffect.PlayerLost -> failPlayback(effect.code)
            TrackEffect.NewPlanScope -> {
                planScope?.cancel()
                planScope = CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext[Job]))
            }
            TrackEffect.CancelPlanScope -> {
                planScope?.cancel()
                planScope = null
            }
        }
    }

    /** docs/18 §3.1: the plan's one Direct Play resolve, in its scope so an ended plan hears nothing. */
    private fun resolveTracks(effect: TrackEffect.Resolve) {
        val subject = currentPlan?.trackMemorySubject
        planScope?.launch {
            val decision = runCatchingCancellable { gateway.resolveTracks(subject, effect.rows) }.getOrNull()
            sendTrack(decision?.let { TrackEvent.Resolved(effect.session, it, effect.rows) } ?: TrackEvent.ResolveFailed(effect.session))
        }
    }

    /**
     * docs/18 §2/§3.1: the one transcode negotiation, for a fallback or a changed choice. It keeps the
     * session's copy policy ([sessionFallback]) and asks for [effect]'s streams; its result is tagged
     * with what it asked, so [trackChoice] judges it. Only the live owner's choice emits one.
     */
    private fun negotiateTranscode(effect: TrackEffect.Negotiate) {
        val plan = currentPlan ?: return sendTrack(TrackEvent.Gone(effect.from, effect.sent))
        val fallback = sessionFallback
        val reason = if (effect.cause == TranscodeCauseFfi.FALLBACK) fallback?.first.orEmpty() else plan.transcodeReason.orEmpty()
        val ticks = _positionTicks.value
        val request = claimRequest()
        logRenegotiation(plan.itemId, effect.why, effect.sent)
        clearReconnectState()
        viewModelScope.launch {
            // The native call can't be cancelled, so its result is always seen and disposed of.
            val negotiated = try {
                withContext(NonCancellable) {
                    gateway.prepareTranscodeFallback(plan.itemId, ticks, reason, effect.from, fallback?.second, effect.sent, effect.cause, request)
                }
            } catch (e: CoreException) {
                val outcome = requestFailure(request, e, liveSession = owner is SessionOwner.Playing && !trackChoice.oldDied)
                return@launch sendTrack(TrackEvent.Failed(effect.from, effect.sent, outcome, e))
            }
            val adopted = adoptPlan(request, negotiated, retires = effect.from)
            val landed = keepLoadedSidecars(plan, negotiated, sidecarCache.keys)
            sendTrack(if (adopted) TrackEvent.Landed(effect.from, effect.sent, landed) else TrackEvent.Gone(effect.from, effect.sent))
        }
    }

    /** docs/18 §2.1: starts the landed plan in the core; any refusal (a stop or newer claim took it) leaves the stream on screen playing. */
    private fun commitTranscode(effect: TrackEffect.Commit) {
        val gone = TrackEvent.Gone(effect.from, effect.sent)
        val request = currentRequest ?: return sendTrack(gone)
        val session = effect.plan.playSessionId
        viewModelScope.launch {
            val event = try {
                // The native commit can't be cancelled, so its answer is always heard.
                withContext(NonCancellable) { gateway.commitTranscode(session, request) }
                TrackEvent.Committed(session)
            } catch (_: CoreException) {
                gone
            }
            sendTrack(event)
        }
    }

    /**
     * docs/18 §2.1: loads a committed plan in place of the one on screen; a paused viewer stays paused.
     * Only a live session commits: its end idles [trackChoice], so a later Committed is stale.
     */
    private fun loadCommitted(plan: PlaybackPlan) {
        val paused = !playerHolder.playWhenReady // load() resumes
        playerHolder.load(
            plan,
            tolerateMislabeledLevels = sessionTolerateMislabeledLevels,
            audioDecoderPreferences = sessionAudioDecoderPreferences,
            fullAssStyling = sessionFullAssStyling,
        )
        if (paused) playerHolder.pause()
        playerHolder.setPlaybackRate(1f)
        currentPlan = plan
        logPlan(plan.itemId, plan)
        lastReportedPaused = null // the new session starts unpaused; it hears the real state
        clearReconnectState()
        _positionTicks.value = plan.startPositionTicks
        _state.update { it.copy(playMethod = plan.playMethod, transcodeReason = plan.transcodeReason, playbackRate = 1f) }
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

    /** docs/18 §7: the holder's and gate's end inputs, kept so [onCleared] removes only its own hook from the shared holder. */
    private val endInput: (EndEvent) -> Unit = { sendEnd(it) }

    /** docs/18 §7: the one writer of [playbackEnd]; the state lands before its effect runs, since a stop re-enters the listeners; true when it ran an effect. */
    private fun sendEnd(event: EndEvent): Boolean {
        val step = reducePlaybackEnd(playbackEnd, event)
        playbackEnd = step.state
        step.effect?.let(::applyEnd)
        return step.effect != null
    }

    private fun observeEnd() {
        sendEnd(EndEvent.Observed(endFacts(), monotonicClock.nowMs()))
    }

    /** docs/18 §7: one live read of the player; a seek in flight is read live because `seekTo` reports BUFFERING inside the call. */
    private fun endFacts() = EndFacts(
        phase = playerHolder.playbackState,
        playWhenReady = playerHolder.playWhenReady,
        loading = playerHolder.isLoading,
        seekInFlight = playerHolder.seekInFlight,
        positionMs = PlaybackTicks.ticksToMs(playerHolder.currentPositionTicks()),
        bufferedMs = PlaybackTicks.ticksToMs(playerHolder.bufferedPositionTicks()),
        bufferedToListedEnd = playerHolder.bufferedToEnd(),
        audience = when {
            trackChoice.asked != null || liveScope == null -> Audience.HELD
            mode is PlayerMode.Group -> Audience.GROUP
            else -> Audience.SOLO
        },
    )

    private fun applyEnd(effect: EndEffect) {
        when (effect) {
            EndEffect.PlayedThrough -> {
                playerHolder.forgetSeekIndex()
                val group = mode as? PlayerMode.Group
                if (group != null) group.send(GroupEvent::PlayerEnded) else handlePlaybackEnded()
            }
            EndEffect.PastEnd -> finishAfterStop(reason = "past_end")
            EndEffect.NoMedia -> {
                finishAfterFatalPlaybackError("SERVER_NO_MEDIA")
                _events.tryEmit(PlaybackEvent.FinishWithMessage(strings.get(R.string.player_error_transcode_no_media)))
            }
            is EndEffect.RecoverSeek -> {
                AppGraph.diag.event("player.stallRecover") { ms("posMs", effect.positionMs) }
                // Media3 ignores a seek to the very position it holds; a millisecond on makes it reset the renderers.
                playerHolder.seekGroup(effect.positionMs + 1, null)
            }
            is EndEffect.StopStuck -> {
                AppGraph.diag.event("player.stalledForGood") {
                    ms("posMs", effect.positionMs)
                    ms("aheadMs", effect.aheadMs)
                    tag("renderers", playerHolder.rendererStates())
                }
                finishAfterFatalPlaybackError("STALLED_FOR_GOOD")
                val message = if (effect.toListedEnd) R.string.player_error_stuck_at_end else R.string.player_error_stalled_mid
                _events.tryEmit(PlaybackEvent.FinishWithMessage(strings.get(message)))
            }
        }
    }

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
        playerHolder.onEndInput = endInput
    }

    init {
        // "Still watching?" (docs/12 §13): construction-only reset (not in
        // [start], which playNext/playPrevious/playNextEpisode reuse for later
        // sessions, and autoplay isn't interaction) -- unconditional, unlike
        // notePlayerInput's resetOnInput gating.
        viewModelScope.launch { runCatchingCancellable { gateway.resetStillWatching(clock.nowMs().toULong()) } }
        // Minted at once, so a replacement minted later outranks it; if the launch restore was still
        // running, it is re-minted under the restored epoch -- unless stopped or replaced meanwhile.
        val initialRequest = if (forGroup) null else claimRequest(fresh = true)
        viewModelScope.launch {
            gateway.awaitAccountRestored()
            // docs/18 §2.1: from here, leaving the account this owner plays on ends its playback; the
            // core already stopped it. Not before: the launch restore itself moves the epoch.
            viewModelScope.launch {
                gateway.accountEpochs.collect { epochs ->
                    // docs/28 §4: another account leaves the group without closing the player.
                    val group = mode as? PlayerMode.Group
                    if (group != null && group.exec.accountEpoch != epochs.epoch) leaveGroupMode()
                    if (currentRequest?.let { request -> !ownershipOpen(request) } == true) closeForAccountChange()
                }
            }
            if (initialRequest == null || currentRequest != initialRequest) return@launch
            start(itemId, remintIfRestored(initialRequest), startFromBeginning, startAt(startMs), listOpening(remoteQueue), trackRequest)
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

    /** docs/18 §2.1: the session whose stream is on screen, which reports, stops and abandons name;
     * a negotiation's plan replaces it only once committed and loaded.
     */
    private fun sessionOnScreen(): String? = currentPlan?.playSessionId

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

    /** docs/18 §2.1: what [request]'s failure means for this owner; [liveSession] plays on if it is stale. */
    private suspend fun requestFailure(request: PlaybackRequest, error: CoreException, liveSession: Boolean): FailureOutcome {
        val open = withContext(NonCancellable) { gateway.playbackOwnershipOpenNow(request.accountEpoch) }
        return failureOutcome(latest = request == currentRequest, open = open, liveSession = liveSession, error = error)
    }

    /** docs/18 §2.1: the one place a request's failure reaches the viewer, and only from its owner.
     * A live session (a failed fallback) ends first; a failed prepare has none.
     */
    private fun finishFor(outcome: FailureOutcome, error: CoreException) {
        val event = when (outcome) {
            FailureOutcome.IGNORE -> return
            FailureOutcome.CLOSE -> return closeForAccountChange()
            FailureOutcome.QUIET_FINISH -> PlaybackEvent.Finish
            FailureOutcome.REAUTHORIZE -> PlaybackEvent.ReauthorizationRequired((error as? CoreException.Unauthorized)?.account)
            FailureOutcome.FINISH_WITH_MESSAGE -> PlaybackEvent.FinishWithMessage(error.displayMessage(strings))
        }
        failGroupMode()
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

    /** [list] is how this session's request changes the remote list; [tracks], a remote Play's version and picks (docs/31 §4.1). */
    private suspend fun start(
        itemId: String,
        request: PlaybackRequest,
        startFromBeginning: Boolean,
        opening: LoadStart,
        list: RemoteListEvent,
        tracks: TrackRequest?,
    ) {
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
                // docs/05 external: an explicit start position is never overruled by the server's saved one.
                val plan = gateway.preparePlayback(
                    itemId,
                    startFromBeginning || opening is LoadStart.At,
                    request,
                    tracks?.mediaSourceId,
                    tracks?.audioStreamIndex,
                    tracks?.subtitleStreamIndex,
                )
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
            finishFor(requestFailure(request, e, liveSession = owner is SessionOwner.Playing), e)
            return
        }
        val (plan, settings) = startup
        if (!adoptPlan(request, plan)) return
        val loadStart = if (opening is LoadStart.At) LoadStart.At(externalStartTicks(opening.ticks, plan.runtimeTicks)) else opening
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
        sessionFallback = null
        owner = SessionOwner.Playing(scope)
        lastReportedPaused = null
        playbackWasActive = false
        outroStartSecs = null // populated by fetchMediaSegments below, not read off the plan
        neighbors = NeighborLookup.Unknown
        // docs/31 §4.1.1: the list steps before the load, so no track callback reports this session under the ended list; its report rides the plan's adoption.
        stepList(list)
        nextUpDismissed = false
        preloadedNextUpItemId = null
        lastAutoSkipSegmentKey = null
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
        trackHint = tracks

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
            start = loadStart,
        )
        // The shared PlayerHolder singleton outlives any one session --
        // a previous session's rate must never leak into a freshly loaded item.
        playerHolder.setPlaybackRate(1f)
        // docs/18 §3.1: after the load, so its text-off lands on this plan; the last session's end hid its sidecar.
        sendTrack(TrackEvent.PlanAdopted(plan))
        // docs/28 §5: from here a READY is this group load's landing, not the replaced item's.
        if (opening is LoadStart.GroupExactPaused) (mode as? PlayerMode.Group)?.let { mode = it.copy(loadIssued = true) }
        // docs/28 §5: a group load opens on the group's position and never resumes a saved one.
        val startTicks = when (loadStart) {
            is LoadStart.GroupExactPaused -> loadStart.ticks
            is LoadStart.At -> loadStart.ticks
            LoadStart.Resume -> plan.startPositionTicks
        }
        _positionTicks.value = startTicks
        resumeNoticePending = loadStart == LoadStart.Resume && startTicks > 0L
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
                publishSuccessor()
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
                    // Awaited, so the tick evaluates nothing until its report has landed.
                    val landed = CompletableDeferred<Unit>()
                    reports.send {
                        try {
                            gateway.reportPosition(reportTo, ticks)
                        } finally {
                            landed.complete(Unit)
                        }
                    }
                    landed.await()
                }
                if (!scope.isCurrent()) return@launch // docs/18 §2.1: a report held across a newer start evaluates nothing
                when (val group = mode) {
                    // docs/28 §1: in a group the position feeds drift correction; next-up is off and only the host auto-skips.
                    is PlayerMode.Group -> {
                        group.send { GroupEvent.Position(it, ticks) }
                        if (groupRole(group) == SyncPlayRole.HOST) evaluateAutoSkip(ticks)
                    }
                    // docs/12 §9: an end-clamped glide commit pauses without
                    // triggering autoplay/up-next/watched-state -- position
                    // reporting and stats above are unaffected.
                    PlayerMode.Solo -> if (!endClampHold) {
                        evaluateNextUp(scope, ticks)
                        evaluateAutoSkip(ticks)
                    }
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
        // docs/31 §4.1.1: the list's next item plays at the natural end instead (handlePlaybackEnded).
        if (successorFor(ListStep.Next) is Successor.ListItem) return
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
                    // docs/31 §4.1.1: a list that gained a next while deciding moots the answer; an end met meanwhile plays the list's item.
                    val listNext = successorFor(ListStep.Next)
                    if (listNext is Successor.ListItem) {
                        if (endedWhileDeciding) transitionTo(listNext)
                        endedWhileDeciding = false
                        return@launch
                    }
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
                                CountdownOutcome.ADVANCE_NOW -> transitionTo(Successor.Episode(next))
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
        val seriesId = currentPlan?.seriesId ?: return
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
        // docs/31 §4.1.1 (approved Q1 a): a list's next enqueued under the card plays before the card's episode.
        transitionTo(successorFor(ListStep.Next).takeIf { it is Successor.ListItem } ?: Successor.Episode(card))
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
    /** docs/28 §1: this player's role in its group, `null` solo; the skip pill reads it. */
    internal fun groupRole(): SyncPlayRole? = groupRole(mode)

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
     * [playNext]/[playPrevious]/[playNextEpisode]/[stillWatchingContinue] all funnel through this;
     * a list item plays by id and moves the list to its index, an episode ends the list (docs/31 §4.1.1).
     */
    private fun transitionTo(to: Successor) {
        if (owner !is SessionOwner.Playing) return
        val (itemId, list) = when (to) {
            is Successor.ListItem -> to.id to RemoteListEvent.Adopted(to.id, to.at)
            is Successor.Episode -> to.card.id to RemoteListEvent.Adopted(to.card.id, null)
            Successor.None -> return
        }
        val request = claimRequest()
        // Undispatched: the stop names the session at once, so no landing fallback sees the new
        // request before it (see adoptPlan).
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { replaceWith(itemId, request, false, LoadStart.Resume, list, null) }
    }

    /** The end-then-start body [transitionTo] and [replaceItem] share, keyed by a raw item id since
     * JellybeamCore owns a single reporting session: the final stop must finish before
     * `preparePlayback` replaces it, and only the newest [request] gets that far (docs/18 §2.1).
     */
    private suspend fun replaceWith(
        itemId: String,
        request: PlaybackRequest,
        startFromBeginning: Boolean,
        opening: LoadStart,
        list: RemoteListEvent,
        tracks: TrackRequest?,
    ) {
        stopPlaybackAndAwait(keep = request)
        gateway.awaitAccountRestored()
        if (currentRequest != request) return
        start(itemId, remintIfRestored(request), startFromBeginning, opening, list, tracks)
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
    internal fun replaceItem(itemId: String, startFromBeginning: Boolean = false, startMs: Long? = null, queue: RemoteQueue? = null, tracks: TrackRequest? = null) {
        leaveGroup() // docs/28 §4: picking other content leaves the group first
        replace(itemId, startFromBeginning, startAt(startMs), queue, tracks)
    }

    /** The newest request's stop-then-start, for an intent and for the group's Load; a request without [queue] ends the remote list. */
    internal fun replace(itemId: String, startFromBeginning: Boolean, opening: LoadStart, queue: RemoteQueue? = null, tracks: TrackRequest? = null) {
        // Minted now, so an initial prepare still negotiating is already obsolete (docs/18 §2.1).
        val request = claimRequest(fresh = true)
        val list = listOpening(queue)
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { replaceWith(itemId, request, startFromBeginning, opening, list, tracks) } // as transitionTo
    }

    /** Autoplay/"Play now": advances to the next-up card's episode via [transitionTo]. A no-op if
     * no next-up card is currently shown.
     */
    fun playNext() {
        val next = _state.value.nextUp?.card ?: return
        transitionTo(Successor.Episode(next))
    }

    /** OSD "Previous episode" transport button (docs/12 §8) -- mirrors [playNextEpisode] one step back. */
    internal fun playPrevious(): DropWhy? = walk(ListStep.Previous, SyncPlayInput.Prev)

    /** OSD "Next episode" transport button (docs/12 §8) -- against the successor directly rather
     * than [playNext]'s countdown-gated card, so it works before the threshold is crossed.
     */
    internal fun playNextEpisode(): DropWhy? = walk(ListStep.Next, SyncPlayInput.Next)

    /** docs/31 §4.1.1: a step plays its successor; with none it is [DropWhy.NotApplicable] for a remote. In a group the group's queue steps (docs/28 §4). */
    private fun walk(step: ListStep, group: SyncPlayInput): DropWhy? {
        (mode as? PlayerMode.Group)?.let { it.request(group); return null }
        val to = successorFor(step)
        if (to == Successor.None) return DropWhy.NotApplicable
        transitionTo(to)
        return null
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

    /** docs/18 §3.1: [trackRows] over the tracks [tracks] announce, for the plan on screen. */
    private fun rowsOnScreen(tracks: Tracks): List<TrackInfo> = currentPlan?.let { trackRows(it, TrackMapping.toTrackInfos(tracks)) }.orEmpty()

    /** docs/12 §16: subtitle rows the player or a sidecar shows, never a stream only a renegotiation shows. */
    private fun shownTextRows(rows: List<TrackInfo>) = rows.filter { it.kind == TrackKindFfi.SUBTITLE && streamRowIndexOf(it.id) == null }

    /** docs/12 §16: the picker and the dot, derived from the choice over the rows on screen. */
    private fun publishTracks() {
        // A plan just loaded has announced nothing yet; its own rows still show in an open picker.
        val tracks = playerHolder.currentTracks ?: Tracks.EMPTY
        val rows = rowsOnScreen(tracks)
        val selected = selection(trackChoice, rows)
        _state.update { state ->
            // A retry's empty announcement says nothing about what plays.
            val dot = if (tracks.isEmpty) state.nonDefaultTrackActive else selected.nonDefault
            // docs/18 §3.2: derived once per dispatch, so a load's clear and the cached file's return publish as one frame.
            val cues = trackChoice.shownSidecar?.let(sidecarCache::get)
            state.copy(nonDefaultTrackActive = dot, sidecarCues = cues).withTrackPicker { pickerState(rows, selected) }
        }
        // docs/12 §16: a delay means something only while a real subtitle (embedded or sidecar, never burn-in) is selected.
        if (!tracks.isEmpty && selected.text < 0) closeSubtitleDelay()
    }

    // -- In-player track picker (docs/09 slice 3b) ------------------------

    /** Opens the track picker over the rows on screen -- a no-op if no [Tracks] announced yet. */
    fun openTrackPicker() {
        val tracks = playerHolder.currentTracks ?: return
        val rows = rowsOnScreen(tracks)
        open(OpenSurface.Tracks(pickerState(rows, selection(trackChoice, rows))))
    }

    private fun pickerState(rows: List<TrackInfo>, selected: TrackSelection): TrackPickerState {
        fun row(info: TrackInfo, index: Int, selectedId: Long?): TrackPickerRow {
            val sidecar = ExternalSubtitles.indexOf(info.id)
            return TrackPickerRow(
                id = info.id,
                label = TrackChoiceText.label(strings, info, index),
                selected = info.id == selectedId,
                meta = TrackChoiceText.meta(strings, info, external = sidecar != null, status = sidecar?.let(sidecarStatus::get)),
                isDefault = info.isDefault,
            )
        }
        // docs/18 §3.2: "Off" first, then streams only a renegotiation shows, then the player's text and the sidecars.
        val off = TrackPickerRow(id = TRACK_PICKER_SUBTITLE_OFF_ID, label = strings.get(R.string.player_track_off), selected = selected.text == TRACK_PICKER_SUBTITLE_OFF_ID)
        val streams = rows.filter { it.kind == TrackKindFfi.SUBTITLE && streamRowIndexOf(it.id) != null }
        return TrackPickerState(
            audioTracks = rows.filter { it.kind == TrackKindFfi.AUDIO }.mapIndexed { index, info -> row(info, index, selected.audio) },
            subtitleTracks = listOf(off) + streams.mapIndexed { index, info -> row(info, index, selected.text) } +
                shownTextRows(rows).mapIndexed { index, info -> row(info, index, selected.text) },
            delayText = SubtitleDelay.format(strings, playerHolder.subtitleDelayMs),
        )
    }

    /** docs/18 §3.2: fetches sidecar [index] under [session] in the plan's scope; any failure, a core panic included, costs this track only. */
    private fun fetchSidecar(session: String, index: Int) {
        if (index in sidecarCache) return sendTrack(TrackEvent.SidecarFetched(session, index, ok = true))
        val sub = currentPlan?.externalSubtitles?.firstOrNull { it.index == index }
        val scope = planScope
        if (sub == null || scope == null) return sendTrack(TrackEvent.SidecarFetched(session, index, ok = false))
        // With full styling on, an ASS sidecar loads whole into the overlay rather than as cues.
        val styled = if (ExternalSubtitles.isSsa(sub.codec)) playerHolder.assSidecarTarget(index) else null
        sidecarStatus[index] = SidecarStatus.LOADING
        sidecarJob?.cancel()
        sidecarJob = scope.launch {
            val loaded = try {
                if (styled != null) {
                    gateway.loadAssSidecar(session, index, styled.overlay, styled.key, styled.item)
                } else {
                    val cues = gateway.fetchExternalSubtitle(session, index)?.let { parseSidecar(sub.codec, it) }
                    if (cues != null) sidecarCache[index] = cues
                    cues != null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            if (loaded) sidecarStatus.remove(index)
            sendTrack(TrackEvent.SidecarFetched(session, index, loaded))
        }
    }

    /** docs/18 §3.2: a styled sidecar [index] loads whole into the overlay; plain cues publish with the choice. */
    private fun showSidecar(index: Int) = playerHolder.showAssSidecar(if (index in sidecarCache) null else playerHolder.assSidecarTarget(index))

    /** docs/12 §16: the Delay row's Select -- replaces the picker with the delay strip; refused without a selected subtitle. */
    fun openSubtitleDelay() {
        val tracks = playerHolder.currentTracks ?: return
        val rows = rowsOnScreen(tracks)
        val subtitles = shownTextRows(rows)
        val shownId = selection(trackChoice, rows).text
        val shown = subtitles.indexOfFirst { it.id == shownId }
        if (shown < 0) return
        val track = TrackChoiceText.delayTrack(strings, subtitles[shown], shown)
        open(OpenSurface.DelayControl(DelayState(playerHolder.subtitleDelayMs, track = track)))
    }

    /** docs/12 §16: one key for the open delay control; each changed value reaches the player at once. */
    fun subtitleDelayKey(key: DelayKey) {
        val before = _state.value.subtitleDelay ?: return
        val step = SubtitleDelay.reduce(before, key)
        if (step.state.delayMs != before.delayMs) playerHolder.setSubtitleDelayMs(step.state.delayMs)
        replaceSurface { if (it is OpenSurface.DelayControl) (if (step.open) OpenSurface.DelayControl(step.state) else OpenSurface.None) else it }
    }

    /** Closes the delay control only; any other open surface stays (a card or PiP ends it, docs/12 §16). */
    fun closeSubtitleDelay() = replaceSurface { if (it is OpenSurface.DelayControl) OpenSurface.None else it }

    /** The live subtitle delay for the stats sheet's SUBTITLES row (docs/12 §17). */
    val subtitleDelayMs: Long get() = playerHolder.subtitleDelayMs

    /** docs/28 §5, docs/18 §2.1: who a pick answers to: the group, this live player, or no playback of this player's. */
    private fun pickGate(): TrackEvent.Gate = when {
        mode is PlayerMode.Group -> TrackEvent.Gate.GROUP
        liveScope == null || currentRequest?.let(::ownershipOpen) != true -> TrackEvent.Gate.NOT_LIVE
        else -> TrackEvent.Gate.SOLO
    }

    /** Chooses [id] as the audio track (docs/09 slice 3b); a no-op for an id no row on screen carries. [byUser] false: a hint, not remembered. */
    fun chooseAudio(id: Long, byUser: Boolean = true) {
        val tracks = playerHolder.currentTracks ?: return
        val input = streamRowIndexOf(id)?.let { TrackEvent.AudioInput.Stream(it) }
            ?: rowsOnScreen(tracks).firstOrNull { it.kind == TrackKindFfi.AUDIO && it.id == id }?.let { TrackEvent.AudioInput.Player(it) }
            ?: return
        sendTrack(TrackEvent.PickAudio(input, pickGate(), byUser))
    }

    /** Chooses [id] as the subtitle track, or [TRACK_PICKER_SUBTITLE_OFF_ID] to disable subtitles;
     * "Off" is itself a real remembered choice (docs/18 §3.1); [byUser] as [chooseAudio].
     */
    fun chooseSubtitle(id: Long, byUser: Boolean = true) {
        val tracks = playerHolder.currentTracks ?: return
        val input = if (id == TRACK_PICKER_SUBTITLE_OFF_ID) {
            TrackEvent.TextInput.Off
        } else {
            streamRowIndexOf(id)?.let { TrackEvent.TextInput.StreamRow(it) }
                ?: ExternalSubtitles.indexOf(id)?.let { TrackEvent.TextInput.Sidecar(it) }
                ?: rowsOnScreen(tracks).firstOrNull { it.kind == TrackKindFfi.SUBTITLE && it.id == id }?.let { TrackEvent.TextInput.Player(it) }
                ?: return
        }
        sendTrack(TrackEvent.PickText(input, pickGate(), byUser))
    }

    /** Shared by every pick: remembers [memory] under [memorySubject] (docs/12 §16). */
    private fun rememberTrackMemory(kind: TrackKindFfi, memory: TrackMemoryFfi) {
        val subject = memorySubject() ?: return
        viewModelScope.launch { runCatchingCancellable { gateway.rememberTrackMemory(subject, kind, memory) } }
    }

    private fun memorySubject() = currentPlan?.trackMemorySubject

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

    /** The sidecar cue feed's clock (docs/18 §3.2): the live position behind the subtitle delay (docs/12 §16). */
    fun sidecarClockUs(): Long = subtitleClockUs(PlaybackTicks.ticksToUs(playerHolder.currentPositionTicks()), playerHolder.subtitleDelayMs)

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
                reports.send { gateway.reportPaused(reportTo, isPaused) }
            }
        }

        // docs/12 §15: a stall only gets the pill while intending to play.
        updateBufferingTicker(isBuffering && playWhenReady)
    }

    /**
     * Solo [EndEffect.PlayedThrough], its only caller (docs/18 §7) -- the progress ticker normally starts autoplay before
     * EOF, but ENDED can beat its next tick. Preserves autoplay when its card is eligible;
     * otherwise closes the reporting session and tells the Activity to leave playback.
     * A [transitionTo] ends the session synchronously, making natural EOF and a simultaneous
     * countdown/Enter mutually exclusive. A no-op while a "still watching?" card is
     * shown: it must stay up until answered or timed out.
     */
    private fun handlePlaybackEnded() {
        if (_state.value.stillWatching != null) return
        // docs/31 §4.1.1 (approved Q2): a remote list plays on at a natural end, before any card or decision.
        val listNext = successorFor(ListStep.Next)
        if (listNext is Successor.ListItem) return transitionTo(listNext)
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
                    observeEnd() // docs/18 §7: decides a stall the loader never asks past, and times the safety net
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

    fun togglePlayPause() {
        val group = mode as? PlayerMode.Group ?: return playerHolder.togglePlayPause()
        group.request(if (playerHolder.playWhenReady) SyncPlayInput.Pause else SyncPlayInput.Unpause)
    }

    /** docs/31 §4.2: an idempotent pause; in a group it asks the group, as [togglePlayPause] does. */
    fun pause() {
        if (!playerHolder.playWhenReady) return
        (mode as? PlayerMode.Group)?.let { return it.request(SyncPlayInput.Pause) }
        playerHolder.pause()
    }

    /** docs/31 §4.2: an idempotent unpause, group-aware as [pause]. */
    fun unpause() {
        if (playerHolder.playWhenReady) return
        (mode as? PlayerMode.Group)?.let { return it.request(SyncPlayInput.Unpause) }
        playerHolder.play()
    }

    /** docs/17 §2: a remote's Stop ends playback exactly like Back, whatever the session is doing --
     * a transition still preparing its title is retired with it, so that title never plays.
     */
    fun stopFromRemote() = finishAfterStop(reason = "remote")

    /** docs/17 §2: the one end the viewer asked for. [stopPlaybackOnce] is the exactly-once guard and
     * retires any pending request; with the still-watching card up it is that card's Stop, as Back.
     */
    internal fun finishAfterStop(reason: String) {
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
        if (mode is PlayerMode.Group) return // docs/28 §1: a group plays at 1x
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
        val group = mode as? PlayerMode.Group
        // docs/28 §4: a group tap adds to its newest ask still on its way, else to the player, which a group seek moves at once.
        val currentMs = PlaybackTicks.ticksToMs(group?.let { it.pendingSeeks.lastOrNull() ?: playerHolder.currentPositionTicks() } ?: _positionTicks.value)
        val durationMs = _state.value.durationTicks?.let { PlaybackTicks.ticksToMs(it) }
        var targetMs = SeekMath.clampSeekTarget(currentMs, deltaMs, durationMs)
        val boundByExtraClamp = extraClampMs != null && targetMs > extraClampMs
        if (boundByExtraClamp) targetMs = extraClampMs
        // docs/28 §4: the target goes to the group, and glide still tracks it while the player waits.
        if (group != null) {
            val targetTicks = PlaybackTicks.msToTicks(targetMs)
            group.request(SyncPlayInput.Seek(targetTicks))
            mode = group.copy(pendingSeeks = group.pendingSeeks + targetTicks)
            return targetMs
        }

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
        // docs/28 §4: the group owns play state, so an end-clamped commit is a plain seek request.
        if (mode is PlayerMode.Group) {
            seekToAbsoluteTicks(PlaybackTicks.msToTicks(targetMs))
            return
        }
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
        (mode as? PlayerMode.Group)?.let { group ->
            group.request(SyncPlayInput.Seek(PlaybackTicks.msToTicks(targetMs)))
            return true
        }
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
        if (mode is PlayerMode.Group) return // docs/28 §1: a group plays at 1x
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
     * plus track count, from the rows the picker reads. `null to 0` before any [Tracks] announced.
     */
    fun currentSubtitleSelection(): Pair<String?, Int> {
        val tracks = playerHolder.currentTracks ?: return null to 0
        val rows = rowsOnScreen(tracks)
        val subtitles = shownTextRows(rows)
        val shownId = selection(trackChoice, rows).text
        val selected = subtitles.firstOrNull { it.id == shownId }
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
        // docs/28 §4: before the once-guard, so a player closed with no session left still leaves its group.
        (mode as? PlayerMode.Group)?.let {
            it.send(GroupEvent::PlayerClosed)
            leaveGroupMode()
        }
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
        // docs/28 §3.3: counted before the launch, so a create never misses a stop not yet dispatched.
        playbackActivityTracker.onFinalStopLaunched()
        // Survives teardown: once captured, this stop is the only thing that ends the session it
        // names, a landed fallback included (see adoptPlan).
        reportScope.launch {
            try {
                withContext(NonCancellable) {
                    runCatchingCancellable { gateway.stopPlayback(snapshot.playSessionId, snapshot.ticks) }
                }
            } finally {
                playbackActivityTracker.onFinalStopHandedOff()
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
     * Reconnecting must cancel any pending retry, not race it), [libraryInfoJob], the track choice's
     * plan scope, and the still-watching card with its timeout (which must not fire a stop for
     * a session that has ended). Also closes whichever surface is open, which must not survive it.
     */
    private fun cancelSessionJobsAndCloseMenus() {
        reportTickerJob?.cancel()
        bufferingTickerJob?.cancel()
        clearSeekBufferingGrace()
        reconnectJob?.cancel()
        libraryInfoJob?.cancel()
        // A stopped player reads 0:00, so a still-fed sidecar would flash its first cue; the end hides it.
        sendTrack(TrackEvent.SessionEnded)
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
        // docs/31 §7: reset after the ending session's last facts check, so its end sends nothing; the next session starts with none in the core.
        factsSent = null
        return report(playSessionId).also {
            playerHolder.stopAndClear()
            sendEnd(EndEvent.Released)
        }
    }

    /** Player-side, exactly-once half shared by normal exits and play-next transitions. */
    private fun endSessionForStop(keep: PlaybackRequest? = null): StopSnapshot? = endSessionOnce(keep) { playSessionId ->
        // A reconnect episode leaves the player idle after the error, so it reports the remembered position.
        val lastKnownTicks = _positionTicks.value
        val currentTicks = reconnectPositionTicks
            ?: playerHolder.currentPositionTicks().takeIf { it > 0L || lastKnownTicks <= 0L }
            ?: lastKnownTicks
        // docs/12 §9: exiting an active end-clamp hold reports where the viewer reached, never the clamp point.
        // docs/18 §7: whichever position wins, it never lands in the server's stubs.
        val reachedTicks = if (endClampHold) endHoldReportTicks ?: currentTicks else currentTicks
        playSessionId?.let { StopSnapshot(it, endStopTicks(playbackEnd, reachedTicks, currentPlan?.runtimeTicks)) }
    }

    /** Error path: discards the session without a final report; a session prepare never opened has nothing to abandon. */
    private fun abandonPlaybackOnce() {
        endSessionOnce(keep = null) { id -> id?.let { reportScope.launch { runCatchingCancellable { gateway.abandonPlayback(it) } } } }
    }

    /** A failure before playback ever became active has no trustworthy position, so it abandons;
     * once active, the position is real and a final Stopped report preserves it like Back/HOME
     * does.
     */
    /** docs/18 §1: the player's own fatal error, [code] being Media3's name for it. */
    private fun failPlayback(code: String, httpStatus: Int? = null) {
        finishAfterFatalPlaybackError(code)
        _events.tryEmit(PlaybackEvent.FinishWithMessage(fatalPlaybackMessage(strings, code, currentPlan, httpStatus)))
    }

    private fun finishAfterFatalPlaybackError(errorCode: String) {
        // docs/21 §2.1: one line per fatal error, before deciding stop vs. abandon.
        AppGraph.diag.event("player.error") {
            tag("code", errorCode)
            ms("posMs", PlaybackTicks.ticksToMs(_positionTicks.value))
        }
        failGroupMode()
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
            if (playerHolder.onEndInput === endInput) playerHolder.onEndInput = null
        }
        stopPlaybackOnce()
    }
}

class PlaybackViewModelFactory internal constructor(
    private val gateway: CoreGateway,
    private val playerHolder: PlaybackPlayer,
    private val itemId: String,
    private val startFromBeginning: Boolean = false,
    private val strings: UiStrings,
    private val sheetFetcher: SheetFetcher? = null,
    private val forGroup: Boolean = false,
    private val startMs: Long? = null,
    private val remoteQueue: RemoteQueue? = null,
    private val trackRequest: TrackRequest? = null,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(PlaybackViewModel::class.java))
        return PlaybackViewModel(gateway, playerHolder, itemId, strings, startFromBeginning = startFromBeginning, sheetFetcher = sheetFetcher, forGroup = forGroup, startMs = startMs,
            remoteQueue = remoteQueue, trackRequest = trackRequest,
        ) as T
    }
}
