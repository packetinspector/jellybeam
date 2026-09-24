package tv.jellybeam.player

import uniffi.jellybeam_core.MediaSegment
import uniffi.jellybeam_core.MediaSegmentKind
import uniffi.jellybeam_core.SegmentAction

/**
 * Skip-intro/credits pure logic (docs/12-osd-ux-spec.md "Skip intro/credits", build order item 6),
 * free of Compose/ViewModel deps so segment-activity and label rules are JVM-testable.
 * [tv.jellybeam.player.PlaybackViewModel] reads a [SkipSegmentActions] from `Settings` once per
 * session
 * and asks [decision] what to do with the segment covering the playhead: [SegmentDecision.PILL]
 * shows
 * the pill (Enter/Select skips via [tv.jellybeam.player.PlaybackViewModel.skipSegment]/`undoSkip`);
 * [SegmentDecision.AUTO_SKIP] seeks past it immediately with the same Undo toast;
 * [SegmentDecision.NOTHING] ignores it.
 */
object SkipSegment {
    /** The first segment whose `[startTicks, endTicks)` window contains [positionTicks], via linear
     * scan (no assumed sort order).
     */
    fun activeSegment(segments: List<MediaSegment>, positionTicks: Long): MediaSegment? =
        segments.firstOrNull { positionTicks >= it.startTicks && positionTicks < it.endTicks }

    /** The skip pill's label, per type (docs/12: "Skip Intro/Credits/Recap/Preview/Commercial"). */
    fun pillLabel(kind: MediaSegmentKind): String = when (kind) {
        MediaSegmentKind.INTRO -> "Skip Intro"
        MediaSegmentKind.OUTRO -> "Skip Credits"
        MediaSegmentKind.RECAP -> "Skip Recap"
        MediaSegmentKind.PREVIEW -> "Skip Preview"
        MediaSegmentKind.COMMERCIAL -> "Skip Commercial"
        MediaSegmentKind.UNKNOWN -> "Skip"
    }

    /** The post-skip Undo toast's label, past tense -- mirrors [pillLabel]'s per-type wording. */
    fun toastLabel(kind: MediaSegmentKind): String = when (kind) {
        MediaSegmentKind.INTRO -> "Skipped intro"
        MediaSegmentKind.OUTRO -> "Skipped credits"
        MediaSegmentKind.RECAP -> "Skipped recap"
        MediaSegmentKind.PREVIEW -> "Skipped preview"
        MediaSegmentKind.COMMERCIAL -> "Skipped commercial"
        MediaSegmentKind.UNKNOWN -> "Skipped segment"
    }

    /** `SegmentAction` x `MediaSegmentKind` -> [SegmentDecision] lookup. */
    fun decision(kind: MediaSegmentKind, actions: SkipSegmentActions): SegmentDecision =
        when (actions.actionFor(kind)) {
            SegmentAction.ASK -> SegmentDecision.PILL
            SegmentAction.AUTO_SKIP -> SegmentDecision.AUTO_SKIP
            SegmentAction.OFF -> SegmentDecision.NOTHING
        }
}

/**
 * Session-scoped mirror of `core/ffi/src/settings.rs`'s five `skip_*` fields, one [SegmentAction]
 * per
 * [MediaSegmentKind] the server returns (`Unknown` has no settings row and is always
 * [SegmentAction.OFF]). Defaults mirror `Settings::default()` for the brief window before
 * [tv.jellybeam.player.PlaybackViewModel.start]'s settings read resolves.
 */
data class SkipSegmentActions(
    val intro: SegmentAction = SegmentAction.ASK,
    val outro: SegmentAction = SegmentAction.ASK,
    val recap: SegmentAction = SegmentAction.ASK,
    val preview: SegmentAction = SegmentAction.ASK,
    val commercial: SegmentAction = SegmentAction.AUTO_SKIP,
) {
    fun actionFor(kind: MediaSegmentKind): SegmentAction = when (kind) {
        MediaSegmentKind.INTRO -> intro
        MediaSegmentKind.OUTRO -> outro
        MediaSegmentKind.RECAP -> recap
        MediaSegmentKind.PREVIEW -> preview
        MediaSegmentKind.COMMERCIAL -> commercial
        MediaSegmentKind.UNKNOWN -> SegmentAction.OFF
    }
}

/** Config x segment-type -> what the OSD should do, a thin wrapper over [SegmentAction] so callers
 * read as "what to do".
 */
enum class SegmentDecision {
    /** Ask (default): show the skip pill, wait for Enter/Select. */
    PILL,
    /** Auto-skip: seek past the segment the instant playback enters it, with an Undo toast. */
    AUTO_SKIP,
    /** Off: ignore the segment entirely. */
    NOTHING,
}
