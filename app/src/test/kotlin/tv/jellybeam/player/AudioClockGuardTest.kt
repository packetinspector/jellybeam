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
        // Audio ends at 4 s of media, inside the learning window; the video runs on and the sink is paused at 11.5 s.
        playEpisode(guard, lagUs = 70_000, audioEndUs = 4_000_000 - 70_000, pauseAtUs = 11_500_000)
        assertEquals(70_000L, guard.learnedLagUs)
        assertEquals(6_070_000L, guard.unstoppedLagUs) // what the release log reports the rule without the cut-off would have learnt
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

    @Test
    fun `an audio clock running slightly fast is followed, not clamped`() {
        // Hardware audio clocks drift against the system clock by tens to hundreds of ppm; a smooth
        // position that slowly gains on play time is the truth, not a lie (#32).
        val failures = mutableListOf<String>()
        for (ppm in listOf(50, 100, 300)) {
            val g = AudioClockGuard(slackUs = slack)
            val latencyUs = 70_000L
            g.onPlay(nowUs = 0)
            var firstClampAtS = -1L
            var worstErrorUs = 0L
            var nowUs = 0L
            while (nowUs <= 2 * 3_600_000_000L) { // a two-hour movie, sampled every 100 ms
                val trueUs = maxOf(0L, ((nowUs - latencyUs) * (1.0 + ppm / 1e6)).toLong())
                val out = g.filter(reportedUs = trueUs, nowUs = nowUs, speed = 1f)
                if (g.clampCount > 0 && firstClampAtS < 0) firstClampAtS = nowUs / 1_000_000
                worstErrorUs = maxOf(worstErrorUs, trueUs - out)
                nowUs += 100_000
            }
            println("ppm=$ppm firstClampAt=${firstClampAtS}s clamps=${g.clampCount} worstAvError=${worstErrorUs / 1000}ms")
            if (g.clampCount > 0) failures += "ppm=$ppm first clamp ${firstClampAtS}s, worst A/V error ${worstErrorUs / 1000} ms"
        }
        assertEquals(emptyList<String>(), failures)
    }

    @Test
    fun `drift meter reads the rate of the raw position against play time`() {
        for (ppm in listOf(-300L, 0L, 50L, 1000L)) {
            val m = ClockDriftMeter()
            var nowUs = 0L
            while (nowUs <= 120_000_000L) {
                m.sample(reportedUs = ((nowUs - 70_000L) * (1.0 + ppm / 1e6)).toLong(), playedUs = nowUs)
                nowUs += 100_000
            }
            assertEquals(ppm.toDouble(), m.ppm!!.toDouble(), 2.0)
        }
    }

    @Test
    fun `drift meter stays silent until ten seconds are covered and after a reset`() {
        val m = ClockDriftMeter()
        m.sample(reportedUs = 2_000_000, playedUs = 2_000_000)
        m.sample(reportedUs = 6_000_000, playedUs = 6_000_000)
        assertEquals(null, m.ppm)
        m.sample(reportedUs = 20_000_000, playedUs = 20_000_000)
        assertEquals(0L, m.ppm)
        m.reset()
        assertEquals(null, m.ppm.takeIf { m.spanS > 0 })
    }

    // Trace tests (#32): the same trace through the previous guard and this one.

    /** The guard before #32, kept as the oracle for "clamps only what it clamped" (docs/18 §5.1). */
    private class LegacyGuard(private val slackUs: Long, private val lagUs: Long = 0L) {
        private var playingSinceUs = Long.MIN_VALUE
        private var last = Long.MIN_VALUE
        var clamped = false
            private set

        fun onPlay(nowUs: Long) {
            if (playingSinceUs == Long.MIN_VALUE) playingSinceUs = nowUs
        }

        fun filter(reportedUs: Long, nowUs: Long): Long {
            val played = if (playingSinceUs != Long.MIN_VALUE) nowUs - playingSinceUs else 0L
            var p = reportedUs
            clamped = p > played + slackUs
            if (clamped) p = maxOf(0L, played - lagUs)
            if (last != Long.MIN_VALUE) p = maxOf(p, last)
            last = p
            return p
        }
    }

    private class Trace(val name: String, val points: List<Pair<Long, Long>>)

    /** A truthful track lagging [lagUs] whose clock runs [ppm] fast, sampled every 20 ms for [durationUs]. */
    private fun driftTrace(ppm: Long, durationUs: Long, lagUs: Long = 70_000L, stepUs: Long = 20_000L): Trace =
        Trace("drift $ppm ppm", (0L..durationUs step stepUs).map { now -> now to maxOf(0L, Math.round((now - lagUs) * (1.0 + ppm / 1e6))) })

    /** A healthy track until [atUs], then the position gains [jumpUs] at once (and keeps that offset). */
    private fun jumpTrace(atUs: Long, jumpUs: Long, durationUs: Long = atUs + 20_000_000L): Trace =
        Trace("jump $jumpUs at $atUs", (0L..durationUs step 20_000L).map { now ->
            now to maxOf(0L, now - 70_000L) + if (now >= atUs) jumpUs else 0L
        })

    /** The 6ab40ce9 fault: a new track whose position starts at the previous stream's counter. */
    private fun counterTrace(counterUs: Long): Trace =
        Trace("counter $counterUs", (0L..30_000_000L step 20_000L).map { now -> now to counterUs + maxOf(0L, now - 70_000L) })

    private fun run(trace: Trace, lagUs: Long = 0L): Pair<AudioClockGuard, List<Long>> {
        val g = AudioClockGuard(slackUs = slack, lagUs = lagUs)
        g.onPlay(nowUs = 0)
        return g to trace.points.map { (now, raw) -> g.filter(reportedUs = raw, nowUs = now, speed = 1f) }
    }

    @Test
    fun `the 6ab40ce9 seek trace is still caught at the start and followed at the right rate`() {
        for (counter in listOf(2_500_000L, 44_739_000_000L)) {
            val (g, out) = run(counterTrace(counter), lagUs = 70_000)
            assertEquals(ClockRule.START, g.lastRule)
            assertEquals(1, g.ruleCounts.sum())
            // The sound playing at 30 s is 30 s - 70 ms, whatever the old stream counted.
            assertEquals(30_000_000L - 70_000L, out.last())
        }
    }

    @Test
    fun `a jump of 2_5 s is caught at any time`() {
        for (atUs in listOf(2_500_000L, 3_000_000L, 60_000_000L, 1_800_000_000L, 5_400_000_000L)) {
            val tr = jumpTrace(atUs, 2_500_000L, durationUs = atUs + 5_000_000L)
            val (g, out) = run(tr, lagUs = 70_000)
            assertEquals("${tr.name} caught", 1, g.ruleCounts.sum())
            // Never more than the slack ahead of the sound that really plays.
            assertEquals(tr.name, 0L, tr.points.indices.maxOf { i -> out[i] - maxOf(0L, tr.points[i].first - 70_000L) - slack }.coerceAtLeast(0))
        }
    }

    @Test
    fun `a smooth drift of up to 1000 ppm either way is never clamped`() {
        for (ppm in listOf(-1000L, -300L, -50L, 0L, 50L, 300L, 1000L)) {
            val tr = driftTrace(ppm, 3 * 3_600_000_000L, stepUs = 100_000L)
            val (g, out) = run(tr, lagUs = 70_000)
            assertEquals("${tr.name} clamps", 0, g.clampCount)
            // Identity: the output is the raw position (monotonic already).
            assertEquals(tr.name, tr.points.last().second, out.last())
        }
    }

    @Test
    fun `a clock gaining faster than the ceiling is still a lie`() {
        val (g, _) = run(driftTrace(ppm = 20_000, durationUs = 600_000_000L), lagUs = 70_000)
        assertEquals(ClockRule.CEILING, g.lastRule)
    }

    @Test
    fun `the new guard clamps only what the old one clamps and agrees until then`() {
        val rng = java.util.Random(32)
        val traces = mutableListOf<Trace>()
        for (ppm in listOf(-3000L, -500L, 0L, 400L, 1000L, 2000L, 20_000L)) traces += driftTrace(ppm, 1_200_000_000L, stepUs = 50_000L)
        for (atUs in listOf(500_000L, 2_000_000L, 90_000_000L)) for (jump in listOf(-1_000_000L, 200_000L, 300_000L, 2_500_000L)) traces += jumpTrace(atUs, jump)
        for (c in listOf(1_000_000L, 2_500_000L, 44_739_000_000L)) traces += counterTrace(c)
        repeat(20) { // noisy healthy tracks with random catch-ups that stay behind play time
            val pts = (0L..200_000_000L step 20_000L).map { now ->
                val base = maxOf(0L, now - 70_000L - 400_000L + minOf(now / 4, 400_000L))
                now to maxOf(0L, base + rng.nextInt(20_000) - 10_000)
            }
            traces += Trace("noisy $it", pts)
        }
        for (tr in traces) {
            val old = LegacyGuard(slack, lagUs = 70_000)
            old.onPlay(0)
            val g = AudioClockGuard(slackUs = slack, lagUs = 70_000)
            g.onPlay(nowUs = 0)
            var oldClampedYet = false
            for ((now, raw) in tr.points) {
                val before = g.clampCount
                val o = old.filter(raw, now)
                val n = g.filter(reportedUs = raw, nowUs = now, speed = 1f)
                val newClamped = g.clampCount > before
                if (newClamped) assertEquals("${tr.name} @$now: new clamp where old did not", true, old.clamped)
                oldClampedYet = oldClampedYet || old.clamped
                if (!oldClampedYet) assertEquals("${tr.name} @$now: differs before any old clamp", o, n)
            }
        }
    }

    @Test
    fun `a lie that ends hands back the truth and clears the rebase`() {
        val g = AudioClockGuard(slackUs = slack, lagUs = 70_000)
        g.onPlay(nowUs = 0)
        g.filter(reportedUs = 3_000_000, nowUs = 100_000, speed = 1f) // start rule, rebased
        assertEquals(1_000_000L - 70_000L, g.filter(reportedUs = 3_000_000 + 900_000 - 70_000, nowUs = 1_000_000, speed = 1f))
        // The HAL corrects itself: raw is truthful again, behind play time.
        assertEquals(3_000_000L - 70_000L, g.filter(reportedUs = 3_000_000L - 70_000L, nowUs = 3_000_000, speed = 1f))
        val clampsAfter = g.clampCount
        g.filter(reportedUs = 3_500_000L - 70_000L, nowUs = 3_500_000, speed = 1f)
        assertEquals(clampsAfter, g.clampCount)
    }

    @Test
    fun `a latency is learnt inside the first 10 s only, so drift never carries over`() {
        val g = AudioClockGuard(slackUs = slack)
        g.onPlay(nowUs = 0)
        for ((now, raw) in driftTrace(ppm = 900, durationUs = 3_600_000_000L, stepUs = 100_000L).points) g.filter(raw, now, 1f)
        // 900 ppm for an hour would read -3.2 s here if the latency kept updating.
        assertEquals(true, g.learnedLagUs in 60_000L..80_000L)
    }
}
