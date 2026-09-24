package tv.jellybeam.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.jellybeam_core.MediaSegment
import uniffi.jellybeam_core.MediaSegmentKind
import uniffi.jellybeam_core.SegmentAction

/** [SkipSegment] is pure Kotlin (docs/12 "Skip intro/credits") -- exercised directly, no Compose/ViewModel needed. */
class SkipSegmentTest {

    private fun segment(kind: MediaSegmentKind, startTicks: Long, endTicks: Long) =
        MediaSegment(segmentType = kind, startTicks = startTicks, endTicks = endTicks)

    @Test
    fun `activeSegment finds the segment covering the position, half-open at the end`() {
        val intro = segment(MediaSegmentKind.INTRO, 0L, 100L)
        val outro = segment(MediaSegmentKind.OUTRO, 500L, 600L)
        val segments = listOf(intro, outro)

        assertEquals(intro, SkipSegment.activeSegment(segments, 0L))
        assertEquals(intro, SkipSegment.activeSegment(segments, 99L))
        assertNull("endTicks itself is outside the segment (half-open window)", SkipSegment.activeSegment(segments, 100L))
        assertNull(SkipSegment.activeSegment(segments, 300L))
        assertEquals(outro, SkipSegment.activeSegment(segments, 550L))
    }

    @Test
    fun `activeSegment is null for an empty list`() {
        assertNull(SkipSegment.activeSegment(emptyList(), 0L))
    }

    @Test
    fun `pillLabel and toastLabel are per-type, per docs-12`() {
        assertEquals("Skip Intro", SkipSegment.pillLabel(MediaSegmentKind.INTRO))
        assertEquals("Skip Credits", SkipSegment.pillLabel(MediaSegmentKind.OUTRO))
        assertEquals("Skip Recap", SkipSegment.pillLabel(MediaSegmentKind.RECAP))
        assertEquals("Skip Preview", SkipSegment.pillLabel(MediaSegmentKind.PREVIEW))
        assertEquals("Skip Commercial", SkipSegment.pillLabel(MediaSegmentKind.COMMERCIAL))
        assertEquals("Skip", SkipSegment.pillLabel(MediaSegmentKind.UNKNOWN))

        assertEquals("Skipped intro", SkipSegment.toastLabel(MediaSegmentKind.INTRO))
        assertEquals("Skipped credits", SkipSegment.toastLabel(MediaSegmentKind.OUTRO))
        assertEquals("Skipped recap", SkipSegment.toastLabel(MediaSegmentKind.RECAP))
        assertEquals("Skipped preview", SkipSegment.toastLabel(MediaSegmentKind.PREVIEW))
        assertEquals("Skipped commercial", SkipSegment.toastLabel(MediaSegmentKind.COMMERCIAL))
        assertEquals("Skipped segment", SkipSegment.toastLabel(MediaSegmentKind.UNKNOWN))
    }

    // -- docs/09-settings-plan.md skip-segment settings: SkipSegmentActions/decision --

    @Test
    fun `actionFor reads the matching field per kind, and folds Unknown to Off`() {
        val actions = SkipSegmentActions(
            intro = SegmentAction.OFF,
            outro = SegmentAction.AUTO_SKIP,
            recap = SegmentAction.ASK,
            preview = SegmentAction.OFF,
            commercial = SegmentAction.AUTO_SKIP,
        )

        assertEquals(SegmentAction.OFF, actions.actionFor(MediaSegmentKind.INTRO))
        assertEquals(SegmentAction.AUTO_SKIP, actions.actionFor(MediaSegmentKind.OUTRO))
        assertEquals(SegmentAction.ASK, actions.actionFor(MediaSegmentKind.RECAP))
        assertEquals(SegmentAction.OFF, actions.actionFor(MediaSegmentKind.PREVIEW))
        assertEquals(SegmentAction.AUTO_SKIP, actions.actionFor(MediaSegmentKind.COMMERCIAL))
        assertEquals(
            "Unknown has no settings row -- always Off",
            SegmentAction.OFF,
            actions.actionFor(MediaSegmentKind.UNKNOWN),
        )
    }

    @Test
    fun `SkipSegmentActions defaults match Settings default() -- everything Ask except commercial`() {
        val actions = SkipSegmentActions()

        assertEquals(SegmentAction.ASK, actions.intro)
        assertEquals(SegmentAction.ASK, actions.outro)
        assertEquals(SegmentAction.ASK, actions.recap)
        assertEquals(SegmentAction.ASK, actions.preview)
        assertEquals(SegmentAction.AUTO_SKIP, actions.commercial)
    }

    @Test
    fun `decision maps Ask to Pill, AutoSkip to AutoSkip, and Off to Nothing`() {
        val actions = SkipSegmentActions(
            intro = SegmentAction.ASK,
            outro = SegmentAction.AUTO_SKIP,
            recap = SegmentAction.OFF,
            preview = SegmentAction.ASK,
            commercial = SegmentAction.AUTO_SKIP,
        )

        assertEquals(SegmentDecision.PILL, SkipSegment.decision(MediaSegmentKind.INTRO, actions))
        assertEquals(SegmentDecision.AUTO_SKIP, SkipSegment.decision(MediaSegmentKind.OUTRO, actions))
        assertEquals(SegmentDecision.NOTHING, SkipSegment.decision(MediaSegmentKind.RECAP, actions))
        assertEquals(SegmentDecision.PILL, SkipSegment.decision(MediaSegmentKind.PREVIEW, actions))
        assertEquals(SegmentDecision.AUTO_SKIP, SkipSegment.decision(MediaSegmentKind.COMMERCIAL, actions))
        assertEquals(
            "Unknown folds to Off in actionFor, so decision is Nothing",
            SegmentDecision.NOTHING,
            SkipSegment.decision(MediaSegmentKind.UNKNOWN, actions),
        )
    }

    @Test
    fun `decision against the default SkipSegmentActions is Pill everywhere except commercial`() {
        val defaults = SkipSegmentActions()

        assertEquals(SegmentDecision.PILL, SkipSegment.decision(MediaSegmentKind.INTRO, defaults))
        assertEquals(SegmentDecision.PILL, SkipSegment.decision(MediaSegmentKind.OUTRO, defaults))
        assertEquals(SegmentDecision.PILL, SkipSegment.decision(MediaSegmentKind.RECAP, defaults))
        assertEquals(SegmentDecision.PILL, SkipSegment.decision(MediaSegmentKind.PREVIEW, defaults))
        assertEquals(SegmentDecision.AUTO_SKIP, SkipSegment.decision(MediaSegmentKind.COMMERCIAL, defaults))
    }
}
