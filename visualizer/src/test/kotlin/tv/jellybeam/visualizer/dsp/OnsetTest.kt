package tv.jellybeam.visualizer.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.jellybeam.visualizer.Features

/** docs/30 §3 (E5) U1 and U2: per-class onsets on synthetic drums in pink noise. */
class OnsetTest {
    private class Mix(val name: String, val noise: Double, val kick: Double, val snare: Double, val hat: Double)

    private val mixes = listOf(Mix("moderate", 0.05, 0.6, 0.3, 0.15), Mix("loud", 0.2, 0.9, 0.5, 0.3))

    private fun classScores(sr: Int, bpm: Double, mix: Mix, fps: Int = Signals.FPS): List<Pair<String, OnsetScore>> {
        val d = Signals.drums(sr, 30.0, bpm, mix.noise, mix.kick, mix.snare, mix.hat)
        val log = FeatureLog(sr, d.signal, fps)
        return listOf(
            "kick" to OnsetScore(log.kicks, d.kicks, d.snares + d.hatsOnly, log.hop, fps),
            "snare" to OnsetScore(log.snares, d.snares, d.kicks + d.hatsOnly, log.hop, fps),
            "hat" to OnsetScore(log.hats, d.hats, IntArray(0), log.hop, fps),
            "kickOnHatOnly" to OnsetScore(log.kicks, d.kicks, d.hatsOnly, log.hop, fps),
        )
    }

    @Test
    fun `each drum class is found at 90 120 and 174 bpm in moderate and loud mixes at 60 and 30 fps`() {
        val failures = ArrayList<String>()
        val cases = listOf(48000 to 60, 48000 to 30, 44100 to 60)
        for ((sr, fps) in cases) for (bpm in doubleArrayOf(90.0, 120.0, 174.0)) for (mix in mixes) {
            val scores = classScores(sr, bpm, mix, fps)
            val line = "$sr ${fps}fps ${mix.name} $bpm " + scores.joinToString(" ") { "${it.first} ${it.second}" }
            println(line)
            for ((name, s) in scores.take(3)) {
                if (s.recall < 0.95 || s.precision < 0.90 || s.crossTalk > 0.05) failures.add("$name: $line")
            }
            if (scores[3].second.crossHits != 0) failures.add("kick on a hat-only frame: $line")
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    /** The hop is shorter than the window, so a short hit near a window edge is split between two frames. */
    @Test
    fun `snares and hats are found wherever they fall inside the hop`() {
        for (start in 7200 until 8000 step 100) {
            val d = Signals.drums(48000, 30.0, 120.0, 0.2, 0.9, 0.5, 0.3, start = start)
            val log = FeatureLog(48000, d.signal)
            val hat = OnsetScore(log.hats, d.hats, IntArray(0), log.hop)
            val snare = OnsetScore(log.snares, d.snares, IntArray(0), log.hop)
            println("offset ${start - 7200} hat $hat snare $snare")
            assertTrue("offset $start hat $hat", hat.recall >= 0.95 && hat.precision >= 0.90)
            assertTrue("offset $start snare $snare", snare.recall >= 0.95 && snare.precision >= 0.90)
        }
    }

    @Test
    fun `a dense master at 0 3 rms keeps 90 percent of its kicks at 60 and 30 fps`() {
        for (fps in intArrayOf(60, 30)) {
            val sig = Signals.pink(48000 * 30, 11L, 0.3)
            val onsets = ArrayList<Int>()
            var t = 1300
            while (t < 48000 * 30 - 20000) {
                onsets.add(t)
                Signals.addKick(sig, t, 48000, 0.9)
                t += 24000
            }
            val log = FeatureLog(48000, sig, fps)
            val k = OnsetScore(log.kicks, onsets.toIntArray(), IntArray(0), log.hop, fps)
            println("dense master ${fps}fps kick $k")
            assertTrue("${fps}fps $k", k.recall >= 0.90)
        }
    }

    @Test
    fun `steady pink noise fires at most six onsets a minute per class at 60 and 30 fps`() {
        for (fps in intArrayOf(60, 30)) for ((seed, rms) in listOf(11L to 0.3, 12L to 0.3, 11L to 0.05)) {
            val log = FeatureLog(48000, Signals.pink(48000 * 60, seed, rms), fps)
            // The first frame of sound out of silence is a real onset; the minute is counted after it settles.
            val counts = listOf(log.kicks, log.snares, log.hats).map { l -> l.count { it >= fps } }
            println("noise ${fps}fps seed $seed rms $rms onsets kick/snare/hat $counts")
            for (c in counts) assertTrue("seed $seed rms $rms onsets $counts", c <= 6)
        }
    }

    @Test
    fun `at 32 and 22 05 kHz each class keeps 90 percent recall and the guard holds`() {
        for (sr in intArrayOf(32000, 22050)) for (mix in mixes) {
            val scores = classScores(sr, 120.0, mix)
            val line = "$sr ${mix.name} 120 " + scores.joinToString(" ") { "${it.first} ${it.second}" }
            println(line)
            for ((name, s) in scores.take(3)) assertTrue("$name: $line", s.recall >= 0.90 && s.precision >= 0.85)
            assertEquals(line, 0, scores[3].second.crossHits)
        }
    }

    @Test
    fun `a repeated frame with no time passing changes no onset or tick`() {
        val d = Signals.drums(48000, 20.0, 120.0, 0.05, 0.6, 0.3, 0.15)
        val plain = FeatureLog(48000, d.signal)
        val repeated = FeatureLog(48000, d.signal, repeatEvery = 7)
        assertEquals(plain.kicks, repeated.kicks)
        assertEquals(plain.snares, repeated.snares)
        assertEquals(plain.hats, repeated.hats)
        assertEquals(plain.ticks, repeated.ticks)
    }

    @Test
    fun `analyze without feed never reports an onset`() {
        val d = Signals.drums(48000, 10.0, 120.0, 0.05, 0.6, 0.3, 0.15)
        val a = Analyzer(48000)
        val out = Features()
        val mono = FloatArray(1024)
        val side = FloatArray(512)
        for (f in 1 until d.signal.size / 800) {
            d.signal.copyInto(mono, 0, maxOf(0, f * 800 - 1024), f * 800)
            a.analyze(mono, side, side, 1f / 60f, out)
            assertTrue("frame $f", !out.kick && !out.snare && !out.hat && !out.beat && !out.beatTick && !out.tempoLocked)
        }
    }

    @Test
    fun `a steady tone fires no onsets after it starts`() {
        for (freq in doubleArrayOf(440.0, 1000.0, 1234.5, 3000.0, 7000.0, 9876.5)) for (amp in doubleArrayOf(0.5, 0.01)) {
            val log = FeatureLog(48000, Signals.sine(freq, 48000, 48000 * 30, amp))
            // The first frames of sound out of silence are onsets by design.
            val after = listOf(log.kicks, log.snares, log.hats).map { l -> l.count { it >= 3 } }
            assertEquals("$freq Hz amp $amp onsets kick/snare/hat $after", listOf(0, 0, 0), after)
        }
    }

    @Test
    fun `the second and third kicks after a silent gap are found`() {
        for (fps in intArrayOf(60, 30)) for (start in intArrayOf(1300, 2400)) for (noise in doubleArrayOf(0.0, 0.05)) {
            val gap = 2 * 48000
            val kicks = Signals.drums(48000, 10.0, 120.0, noise, 0.9, 0.0, 0.0, start = start)
            val sig = FloatArray(gap + kicks.signal.size)
            kicks.signal.copyInto(sig, gap)
            val log = FeatureLog(48000, sig, fps)
            val wanted = kicks.kicks.take(3).map { Signals.frameOf(it + gap, log.hop) }
            println("fps $fps start $start noise $noise kicks after gap $wanted fired ${log.kicks.take(4)}")
            for (k in wanted.drop(1)) assertTrue("fps $fps start $start noise $noise kick $k: ${log.kicks.take(4)}", log.kicks.any { it - k in -1..2 })
        }
    }

    @Test
    fun `a one second stall keeps the lock and the tempo`() {
        val d = Signals.drums(48000, 20.0, 120.0, 0.05, 0.6, 0.3, 0.15)
        val feed = Feed(48000, d.signal)
        for (f in 0 until 600) feed.step(f)
        assertTrue(feed.out.tempoLocked)
        // A one-second stall: 94 hops arrive at once, and only the newest are analysed.
        val t0 = System.nanoTime()
        feed.jump(659, 60)
        println("one-second backlog call %.1f us".format((System.nanoTime() - t0) / 1000.0))
        for (f in 660 until feed.frames) feed.step(f)
        Signals.assertFinite(feed.out)
        assertTrue(feed.out.tempoLocked)
        assertEquals(120.0, feed.out.tempoBpm.toDouble(), 2.0)
    }

    @Test
    fun `sample rates outside 8 to 96 kHz are refused`() {
        for (bad in intArrayOf(7999, 96001, 192000)) {
            assertTrue("$bad", runCatching { Analyzer(bad) }.isFailure)
            assertTrue("$bad", runCatching { Analyzer(48000).reconfigure(bad) }.isFailure)
        }
        Analyzer(8000).reconfigure(96000)
    }

    @Test
    fun `per frame analysis stays inside a loose host time bound`() {
        val d = Signals.drums(48000, 30.0, 120.0, 0.2, 0.9, 0.5, 0.3)
        for (fps in intArrayOf(60, 30)) {
            val feed = Feed(48000, d.signal, fps)
            for (rep in 0 until 3) for (f in 0 until feed.frames) feed.step(f)
            val times = LongArray(feed.frames)
            for (f in 0 until feed.frames) {
                val t0 = System.nanoTime()
                feed.step(f)
                times[f] = System.nanoTime() - t0
            }
            times.sort()
            val p50 = times[times.size / 2] / 1000.0
            val p95 = times[times.size * 95 / 100] / 1000.0
            println("feed+analyze ${fps}fps us/frame p50 %.1f p95 %.1f max %.1f".format(p50, p95, times.last() / 1000.0))
            // The aim is 50 us p95 at 60 fps on the host; this bound allows for a busy build machine.
            assertTrue("${fps}fps p95 $p95 us", p95 < 150.0 * 60 / fps)
        }
    }
}
