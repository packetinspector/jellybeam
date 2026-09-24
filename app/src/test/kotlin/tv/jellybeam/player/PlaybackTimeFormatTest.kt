package tv.jellybeam.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackTimeFormatTest {

    @Test
    fun `under an hour formats as M-SS`() {
        assertEquals("0:00", PlaybackTimeFormat.format(0L))
        assertEquals("0:09", PlaybackTimeFormat.format(9_000L))
        assertEquals("5:03", PlaybackTimeFormat.format(303_000L))
        assertEquals("59:59", PlaybackTimeFormat.format(3_599_000L))
    }

    @Test
    fun `an hour or more formats as H-MM-SS`() {
        assertEquals("1:00:00", PlaybackTimeFormat.format(3_600_000L))
        assertEquals("2:05:09", PlaybackTimeFormat.format((2 * 3_600 + 5 * 60 + 9) * 1_000L))
    }

    @Test
    fun `a negative input clamps to zero rather than showing a sign`() {
        assertEquals("0:00", PlaybackTimeFormat.format(-5_000L))
    }

    @Test
    fun `formatRemaining renders a leading minus sign under an hour`() {
        assertEquals("-5:03", PlaybackTimeFormat.formatRemaining(positionMs = 0L, durationMs = 303_000L))
        assertEquals("-4:33", PlaybackTimeFormat.formatRemaining(positionMs = 30_000L, durationMs = 303_000L))
    }

    @Test
    fun `formatRemaining renders H-MM-SS once an hour or more remains`() {
        assertEquals("-1:00:00", PlaybackTimeFormat.formatRemaining(positionMs = 0L, durationMs = 3_600_000L))
    }

    @Test
    fun `formatRemaining clamps to zero rather than going negative once position reaches or passes duration`() {
        assertEquals("-0:00", PlaybackTimeFormat.formatRemaining(positionMs = 303_000L, durationMs = 303_000L))
        assertEquals("-0:00", PlaybackTimeFormat.formatRemaining(positionMs = 400_000L, durationMs = 303_000L))
    }
}
