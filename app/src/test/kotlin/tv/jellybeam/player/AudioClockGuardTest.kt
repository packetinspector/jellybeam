package tv.jellybeam.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** docs/18 §5.1: an audio output's position can never lead the time it has spent playing. */
class AudioClockGuardTest {

    private val slack = 100_000L
    private val guard = AudioClockGuard(slackUs = slack)

    @Test
    fun `a truthful position lagging play time passes through`() {
        guard.onPlay(nowUs = 0)
        assertEquals(950_000L, guard.filter(reportedUs = 950_000, nowUs = 1_000_000, speed = 1f))
        assertEquals(0, guard.clampCount)
    }

    @Test
    fun `a position ahead of play time plus slack is replaced by play time`() {
        // On a real TV: 2.5 s reported 50 ms after the new track started playing.
        guard.onPlay(nowUs = 0)
        assertEquals(50_000L, guard.filter(reportedUs = 2_500_000, nowUs = 50_000, speed = 1f))
        assertEquals(1, guard.clampCount)
        assertEquals(2_450_000L, guard.maxExcessUs)
    }

    @Test
    fun `a lead inside the slack is not a lie`() {
        guard.onPlay(nowUs = 0)
        assertEquals(1_050_000L, guard.filter(reportedUs = 1_050_000, nowUs = 1_000_000, speed = 1f))
        assertEquals(0, guard.clampCount)
    }

    @Test
    fun `a lying track is reported at play time minus the learned latency`() {
        val lagged = AudioClockGuard(slackUs = slack, lagUs = 60_000)
        lagged.onPlay(nowUs = 0)
        assertEquals(2_000_000L - 60_000L, lagged.filter(reportedUs = 4_500_000, nowUs = 2_000_000, speed = 1f))
        // Never below zero at the very start.
        val fresh = AudioClockGuard(slackUs = slack, lagUs = 60_000)
        fresh.onPlay(nowUs = 0)
        assertEquals(0L, fresh.filter(reportedUs = 2_500_000, nowUs = 10_000, speed = 1f))
    }

    @Test
    fun `latency is learned from a healthy track after a second of play`() {
        guard.onPlay(nowUs = 0)
        guard.filter(reportedUs = 450_000, nowUs = 500_000, speed = 1f)
        assertEquals(-9223372036854775807L, guard.learnedLagUs) // C.TIME_UNSET: too early
        guard.filter(reportedUs = 1_930_000, nowUs = 2_000_000, speed = 1f)
        assertEquals(70_000L, guard.learnedLagUs)
    }

    @Test
    fun `a track that lied never teaches a latency`() {
        guard.onPlay(nowUs = 0)
        guard.filter(reportedUs = 2_500_000, nowUs = 50_000, speed = 1f)
        guard.filter(reportedUs = 1_930_000, nowUs = 2_000_000, speed = 1f)
        assertEquals(-9223372036854775807L, guard.learnedLagUs)
    }

    @Test
    fun `before play the position is zero`() {
        assertEquals(0L, guard.filter(reportedUs = 2_000_000, nowUs = 5_000_000, speed = 1f))
    }

    @Test
    fun `the position never goes backwards after a clamp`() {
        guard.onPlay(nowUs = 0)
        guard.filter(reportedUs = 2_500_000, nowUs = 50_000, speed = 1f) // -> 50_000
        // The output later tells the truth, which is below what was already reported: hold.
        assertEquals(50_000L, guard.filter(reportedUs = 40_000, nowUs = 100_000, speed = 1f))
        // Once the truth passes the held value it is reported again.
        assertEquals(260_000L, guard.filter(reportedUs = 260_000, nowUs = 350_000, speed = 1f))
    }

    @Test
    fun `pause stops play time from growing`() {
        guard.onPlay(nowUs = 0)
        guard.onPause(nowUs = 1_000_000, speed = 1f)
        assertEquals(1_000_000L, guard.filter(reportedUs = 3_000_000, nowUs = 9_000_000, speed = 1f))
        guard.onPlay(nowUs = 9_000_000)
        assertEquals(1_500_000L, guard.filter(reportedUs = 3_000_000, nowUs = 9_500_000, speed = 1f))
    }

    @Test
    fun `play time is scaled by speed`() {
        guard.onPlay(nowUs = 0)
        assertEquals(2_000_000L, guard.filter(reportedUs = 5_000_000, nowUs = 1_000_000, speed = 2f))
    }

    @Test
    fun `flush forgets everything`() {
        guard.onPlay(nowUs = 0)
        guard.filter(reportedUs = 2_500_000, nowUs = 50_000, speed = 1f)
        guard.onFlush()
        assertEquals(0, guard.clampCount)
        assertEquals(0L, guard.maxExcessUs)
        assertEquals(0L, guard.filter(reportedUs = 1_000_000, nowUs = 10_000_000, speed = 1f))
    }
}
