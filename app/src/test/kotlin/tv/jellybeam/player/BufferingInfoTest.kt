package tv.jellybeam.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins [BufferingInfo]'s clamp/rounding edges directly: the buffering pill must
 * report stalls honestly (owner directive), so these aren't left to indirect
 * coverage through [PlaybackViewModelTest].
 */
class BufferingInfoTest {

    @Test
    fun `zero buffered ahead is zero percent`() {
        assertEquals(0, BufferingInfo.percentTowardResume(bufferAheadMs = 0L, resumeThresholdMs = 1_000L))
    }

    @Test
    fun `half the threshold banked is fifty percent`() {
        assertEquals(50, BufferingInfo.percentTowardResume(bufferAheadMs = 500L, resumeThresholdMs = 1_000L))
    }

    @Test
    fun `exactly the threshold banked is one hundred percent`() {
        assertEquals(100, BufferingInfo.percentTowardResume(bufferAheadMs = 1_000L, resumeThresholdMs = 1_000L))
    }

    @Test
    fun `banked past the threshold clamps to one hundred rather than overshooting`() {
        assertEquals(100, BufferingInfo.percentTowardResume(bufferAheadMs = 5_000L, resumeThresholdMs = 1_000L))
    }

    @Test
    fun `a negative buffer-ahead reading clamps to zero rather than going negative`() {
        assertEquals(0, BufferingInfo.percentTowardResume(bufferAheadMs = -100L, resumeThresholdMs = 1_000L))
    }

    @Test
    fun `a non-positive resume threshold reads as fully resumable rather than dividing by zero`() {
        assertEquals(100, BufferingInfo.percentTowardResume(bufferAheadMs = 0L, resumeThresholdMs = 0L))
        assertEquals(100, BufferingInfo.percentTowardResume(bufferAheadMs = 500L, resumeThresholdMs = -1_000L))
    }

    @Test
    fun `the rebuffer threshold is higher than the initial threshold`() {
        // A mid-play rebuffer needs more banked before Media3 resumes than a cold start does.
        assertEquals(1_000L, BufferingInfo.INITIAL_RESUME_THRESHOLD_MS)
        assertEquals(2_000L, BufferingInfo.REBUFFER_RESUME_THRESHOLD_MS)
    }

    @Test
    fun `formats a whole-number megabyte rate with one decimal`() {
        assertEquals("12.0 MB/s", BufferingInfo.formatThroughput(12_000_000L))
    }

    @Test
    fun `formats a fractional megabyte rate rounded to one decimal`() {
        assertEquals("12.4 MB/s", BufferingInfo.formatThroughput(12_400_000L))
    }

    @Test
    fun `zero bytes per second formats as zero point zero, not n slash a`() {
        // 0 is a real, measured reading; "nothing measured yet" is a separate
        // case the caller handles by omitting this segment entirely.
        assertEquals("0.0 MB/s", BufferingInfo.formatThroughput(0L))
    }

    @Test
    fun `a negative rate clamps to zero rather than showing a sign`() {
        assertEquals("0.0 MB/s", BufferingInfo.formatThroughput(-5_000_000L))
    }

    @Test
    fun `rounds up at the decimal boundary`() {
        assertEquals("1.0 MB/s", BufferingInfo.formatThroughput(999_950L))
    }
}
