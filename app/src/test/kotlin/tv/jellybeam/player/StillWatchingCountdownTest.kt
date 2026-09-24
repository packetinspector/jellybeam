package tv.jellybeam.player

import org.junit.Assert.assertEquals
import org.junit.Test

class StillWatchingCountdownTest {

    // -- remainingWholeSecs: ceiling + clamp -------------------------------

    @Test
    fun `remainingWholeSecs ceils partial seconds`() {
        assertEquals(5L, StillWatchingCountdown.remainingWholeSecs(deadlineMs = 4_001L, nowMs = 0L))
        assertEquals(4L, StillWatchingCountdown.remainingWholeSecs(deadlineMs = 4_000L, nowMs = 0L))
    }

    @Test
    fun `remainingWholeSecs clamps a past deadline to zero`() {
        assertEquals(0L, StillWatchingCountdown.remainingWholeSecs(deadlineMs = 1_000L, nowMs = 5_000L))
    }

    @Test
    fun `remainingWholeSecs at exactly the deadline is zero`() {
        assertEquals(0L, StillWatchingCountdown.remainingWholeSecs(deadlineMs = 5_000L, nowMs = 5_000L))
    }

    // -- remainingFraction: wall-clock depleting rule, clamped ---------------

    @Test
    fun `remainingFraction is a full rule right after the deadline is set`() {
        assertEquals(1f, StillWatchingCountdown.remainingFraction(timeoutTotalSecs = 120.0, deadlineMs = 120_000L, nowMs = 0L), 0.0001f)
    }

    @Test
    fun `remainingFraction is halfway at half the timeout`() {
        assertEquals(0.5f, StillWatchingCountdown.remainingFraction(timeoutTotalSecs = 120.0, deadlineMs = 120_000L, nowMs = 60_000L), 0.0001f)
    }

    @Test
    fun `remainingFraction is empty once the deadline passes`() {
        assertEquals(0f, StillWatchingCountdown.remainingFraction(timeoutTotalSecs = 120.0, deadlineMs = 120_000L, nowMs = 120_000L), 0.0001f)
    }

    @Test
    fun `remainingFraction never goes negative past the deadline`() {
        assertEquals(0f, StillWatchingCountdown.remainingFraction(timeoutTotalSecs = 120.0, deadlineMs = 120_000L, nowMs = 125_000L), 0.0001f)
    }

    @Test
    fun `remainingFraction is empty for a non-positive total rather than dividing by zero`() {
        assertEquals(0f, StillWatchingCountdown.remainingFraction(timeoutTotalSecs = 0.0, deadlineMs = 0L, nowMs = 0L), 0.0001f)
        assertEquals(0f, StillWatchingCountdown.remainingFraction(timeoutTotalSecs = -1.0, deadlineMs = 0L, nowMs = 0L), 0.0001f)
    }
}
