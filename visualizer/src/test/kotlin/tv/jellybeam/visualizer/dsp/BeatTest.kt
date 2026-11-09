package tv.jellybeam.visualizer.dsp

import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.jellybeam.visualizer.Features

class BeatTest {
    private val sr = 48000
    // Pink noise at 0.05 rms puts the bass bands near 0.55 on the 0..1 scale; loud material saturates the ratio.
    private val noiseRms = 0.05
    private val kickAmp = 0.6

    /** Frames on which `beat` was true, plus the pulse and ages seen on every frame. */
    private class Run(val beats: List<Int>, val pulse: FloatArray, val ages0: FloatArray, val ages1: FloatArray)

    private fun run(sig: FloatArray): Run {
        val feed = Feed(sr, sig)
        val beats = ArrayList<Int>()
        val pulse = FloatArray(feed.frames)
        val a0 = FloatArray(feed.frames)
        val a1 = FloatArray(feed.frames)
        for (f in 0 until feed.frames) {
            feed.step(f)
            if (feed.out.beat) beats.add(f)
            pulse[f] = feed.out.beatPulse
            a0[f] = feed.out.beatAges[0]
            a1[f] = feed.out.beatAges[1]
        }
        return Run(beats, pulse, a0, a1)
    }

    /** The frame whose window is centred on sample [t0] (the sync model of docs/30 §2). */
    private fun frameOf(t0: Int, hop: Int): Int = ((t0 + 512).toDouble() / hop).roundToInt() - 1

    private fun train(seconds: Int, onsets: List<Int>): FloatArray {
        val sig = Signals.pink(sr * seconds, 11L, noiseRms)
        for (t in onsets) Signals.addKick(sig, t, sr, kickAmp)
        return sig
    }

    private fun onsetsAt(bpm: Int, from: Int, to: Int): List<Int> {
        val step = sr * 60.0 / bpm
        val out = ArrayList<Int>()
        var t = from.toDouble()
        while (t < to) { out.add(t.roundToInt()); t += step }
        return out
    }

    /** A beat frame matches an onset heard from one frame before it until 45 ms after (see [OnsetScore]). */
    private fun hit(beats: List<Int>, frame: Int) = beats.any { it - frame in -1..2 }

    @Test
    fun `a 120 bpm kick train in pink noise is found within one frame`() {
        val onsets = onsetsAt(120, 1300, sr * 30 - 20000)
        val r = run(train(30, onsets))
        val hop = sr / Signals.FPS
        val counted = onsets.filter { it >= sr } // after the first second of warm up
        val found = counted.count { hit(r.beats, frameOf(it, hop)) }
        assertTrue("recall $found of ${counted.size}", found >= counted.size * 0.95)
        val extra = r.beats.count { b -> onsets.none { b - frameOf(it, hop) in -1..2 } }
        assertTrue("extra beats $extra of ${r.beats.size}", extra <= 2)
    }

    @Test
    fun `steady pink noise for a minute fires at most six beats`() {
        val r = run(Signals.pink(sr * 60, 11L, noiseRms))
        assertTrue("false beats ${r.beats.size}", r.beats.size <= 6)
    }

    @Test
    fun `a tempo change from 90 to 140 is tracked within two beats`() {
        val change = sr * 12
        val slow = onsetsAt(90, 1300, change)
        val fast = onsetsAt(140, change + 1300, sr * 24)
        val onsets = slow + fast
        val r = run(train(24, onsets))
        val hop = sr / Signals.FPS
        val settled = onsets.filter { it >= sr && (it < change || onsets.indexOf(it) >= slow.size + 2) }
        val missed = settled.filter { !hit(r.beats, frameOf(it, hop)) }
        assertTrue("missed ${missed.size} of ${settled.size} at ${missed.map { it / 48000.0 }} beats ${r.beats}", missed.size <= settled.size * 0.05)
        val firstFast = frameOf(fast[2], hop)
        assertTrue("two beats after the change", hit(r.beats, firstFast))
    }

    @Test
    fun `beat pulse decays linearly in 0 4 s and ages count up then shift`() {
        val onsets = onsetsAt(120, 1300, sr * 10)
        val r = run(train(10, onsets))
        val b = r.beats.first { it > 120 }
        val next = r.beats.first { it > b }
        assertEquals(1f, r.pulse[b], 0f)
        assertEquals(0f, r.ages0[b], 0f)
        assertEquals(0.5f, r.pulse[b + 12], 0.02f)
        assertEquals(0f, r.pulse[b + 25], 0f)
        assertEquals(12f / 60f, r.ages0[b + 12], 1e-4f)
        assertEquals((next - b) / 60f, r.ages1[next], 1e-3f)
        assertEquals(0f, r.ages0[next], 0f)
        assertEquals(Features.NO_BEAT, run(FloatArray(sr)).ages0[0], 0f)
    }

    private fun recallAt(rms: Double, kickAmp: Double): Pair<Int, Int> {
        val onsets = onsetsAt(120, 1300, sr * 30 - 20000)
        val sig = Signals.pink(sr * 30, 11L, rms)
        for (t in onsets) Signals.addKick(sig, t, sr, kickAmp)
        val beats = run(sig).beats
        val counted = onsets.filter { it >= sr }
        return counted.count { hit(beats, frameOf(it, sr / Signals.FPS)) } to counted.size
    }

    @Test
    fun `a loud master at 0 2 rms with 0 9 kicks keeps at least 90 percent recall`() {
        val (found, total) = recallAt(0.2, 0.9)
        assertTrue("recall $found of $total", found >= total * 0.9)
    }

    @Test
    fun `a very loud master at 0 3 rms with 0 9 kicks keeps at least 90 percent recall`() {
        val (found, total) = recallAt(0.3, 0.9)
        assertTrue("recall $found of $total", found >= total * 0.9)
    }

    @Test
    fun `steady pink noise at 0 3 rms for a minute fires at most six beats`() {
        for (seed in longArrayOf(11L, 12L)) {
            val r = run(Signals.pink(sr * 60, seed, 0.3))
            assertTrue("seed $seed false beats ${r.beats.size}", r.beats.size <= 6)
        }
    }

    /** A track's silent lead-in must not leave the baseline stuck at zero (then every refractory end would beat). */
    @Test
    fun `steady noise after a silent lead in fires at most two beats`() {
        val noise = Signals.pink(sr * 10, 13L, 0.1)
        val sig = FloatArray(sr + noise.size)
        noise.copyInto(sig, sr)
        val r = run(sig)
        assertTrue("false beats ${r.beats.size}", r.beats.size <= 2)
    }

    @Test
    fun `a kick below the silence floor is no beat and a normal one is`() {
        for ((amp, expected) in listOf(0.001 to false, 0.5 to true)) {
            val sig = FloatArray(sr * 3)
            Signals.addKick(sig, sr + 1300, sr, amp)
            assertEquals("amp $amp", expected, run(sig).beats.isNotEmpty())
        }
    }

    @Test
    fun `a second kick 100 ms after the first is held back by the refractory period`() {
        val pairs = onsetsAt(120, 1300, sr * 30 - 20000).flatMap { listOf(it, it + 4800) }
        val r = run(train(30, pairs))
        assertTrue("beats ${r.beats.size}", r.beats.size >= 40)
        for (i in 1 until r.beats.size) assertTrue("gap ${r.beats[i] - r.beats[i - 1]}", r.beats[i] - r.beats[i - 1] >= 15)
    }
}
