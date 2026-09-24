package tv.jellybeam.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.jellybeam_core.GlideClamp

class GlideChipFormatTest {

    @Test
    fun `delta is signed positive for a forward glide`() {
        val line = GlideChipFormat.line(targetMs = 130_000L, deltaMs = 30_000L, clamp = GlideClamp.NONE, multiplier = 6)
        assertEquals("+0:30", line.delta)
    }

    @Test
    fun `delta is signed negative for a backward glide`() {
        val line = GlideChipFormat.line(targetMs = 70_000L, deltaMs = -30_000L, clamp = GlideClamp.NONE, multiplier = 6)
        assertEquals("-0:30", line.delta)
    }

    @Test
    fun `delta renders h-mm-ss once an hour is crossed`() {
        val oneHourMs = 3_600_000L
        val line = GlideChipFormat.line(targetMs = oneHourMs, deltaMs = oneHourMs, clamp = GlideClamp.NONE, multiplier = 120)
        assertEquals("+1:00:00", line.delta)
    }

    @Test
    fun `Start clamp replaces the delta slot with the word Start`() {
        val line = GlideChipFormat.line(targetMs = 0L, deltaMs = -500_000L, clamp = GlideClamp.START, multiplier = 30)
        assertEquals("Start", line.delta)
    }

    @Test
    fun `End clamp replaces the delta slot with the word End`() {
        val line = GlideChipFormat.line(targetMs = 7_199_000L, deltaMs = 500_000L, clamp = GlideClamp.END, multiplier = 30)
        assertEquals("End", line.delta)
    }

    @Test
    fun `speed is null at multiplier zero (outside a glide)`() {
        val line = GlideChipFormat.line(targetMs = 10_000L, deltaMs = 0L, clamp = GlideClamp.NONE, multiplier = 0)
        assertNull(line.speed)
    }

    @Test
    fun `speed renders the rounded multiplier with a trailing multiplication sign`() {
        val line = GlideChipFormat.line(targetMs = 10_000L, deltaMs = 5_000L, clamp = GlideClamp.NONE, multiplier = 340)
        assertEquals("340×", line.speed)
    }

    @Test
    fun `time is the plain target formatting, independent of delta or clamp`() {
        val line = GlideChipFormat.line(targetMs = 84_000L, deltaMs = -1_000L, clamp = GlideClamp.NONE, multiplier = 6)
        assertEquals(PlaybackTimeFormat.format(84_000L), line.time)
    }
}
