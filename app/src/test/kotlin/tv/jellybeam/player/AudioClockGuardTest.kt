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
    fun `flush clears a stop so the next stretch learns again`() {
        guard.onPlay(nowUs = 0)
        guard.onStop()
        guard.onFlush()
        guard.onPlay(nowUs = 0)
        guard.filter(reportedUs = 1_930_000, nowUs = 2_000_000, speed = 1f)
        assertEquals(70_000L, guard.learnedLagUs)
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

    // One long-lived player hands each output's learned latency to the next (docs/18 §5.1).

    /**
     * Plays [guard] from 0 to [pauseAtUs] on a truthful track lagging [lagUs]; the last frame is written at [stopAtUs]
     * and plays at [audioEndUs]. After stop() Media3 reports min(written duration, position), so the clock freezes there.
     */
    private fun playEpisode(guard: AudioClockGuard, lagUs: Long, audioEndUs: Long, pauseAtUs: Long, stopAtUs: Long = audioEndUs - 2_000_000) {
        guard.onPlay(nowUs = 0)
        for (t in 100_000L..pauseAtUs step 100_000L) {
            if (t >= stopAtUs) guard.onStop()
            guard.filter(reportedUs = minOf(t - lagUs, audioEndUs), nowUs = t, speed = 1f)
        }
        guard.onPause(nowUs = pauseAtUs, speed = 1f)
    }

    @Test
    fun `an advance mid-episode hands the next output the true latency`() {
        playEpisode(guard, lagUs = 70_000, audioEndUs = Long.MAX_VALUE, pauseAtUs = 10_000_000)
        assertEquals(70_000L, guard.learnedLagUs)
    }

    @Test
    fun `the silence after the last audio frame does not teach a latency`() {
        // Audio ends at 10 s of media; the video runs 1.5 s longer before the player ends and pauses the sink.
        playEpisode(guard, lagUs = 70_000, audioEndUs = 10_000_000 - 70_000, pauseAtUs = 11_500_000)
        assertEquals(70_000L, guard.learnedLagUs)
        assertEquals(1_570_000L, guard.unstoppedLagUs) // what the release log reports the old rule would have learnt
    }

    @Test
    fun `the next episode's track that keeps counting the last stream stays in lip sync`() {
        playEpisode(guard, lagUs = 70_000, audioEndUs = 10_000_000 - 70_000, pauseAtUs = 11_500_000)
        val next = AudioClockGuard(slackUs = slack, lagUs = guard.learnedLagUs)
        next.onPlay(nowUs = 0)
        val previousStreamUs = 10_000_000L
        var reported = 0L
        for (t in 100_000L..5_000_000L step 100_000L) {
            reported = next.filter(reportedUs = previousStreamUs + t - 70_000, nowUs = t, speed = 1f)
        }
        // The sound actually playing at 5 s is 5 s - 70 ms; any difference is A/V offset for the whole episode.
        assertEquals(5_000_000L - 70_000L, reported)
    }
}
