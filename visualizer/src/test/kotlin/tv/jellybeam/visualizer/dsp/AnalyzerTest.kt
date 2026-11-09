package tv.jellybeam.visualizer.dsp

import java.util.Random
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.jellybeam.visualizer.Features

class AnalyzerTest {
    private val db24 = 24f / 70f

    private fun steadyTone(freq: Double, sr: Int): Features {
        val feed = Feed(sr, Signals.sine(freq, sr, sr))
        for (f in 0 until 12) feed.step(f)
        return feed.out
    }

    @Test
    fun `a sine peaks in its band and the far bands are 24 dB lower`() {
        val sr = 48000
        val binHz = sr / 1024.0
        for (freq in doubleArrayOf(100.0, 1000.0, 10000.0)) {
            val out = steadyTone(freq, sr)
            val own = Signals.bandOf(freq)
            val peak = Signals.argMax(out.bands)
            // At 100 Hz one 46.9 Hz bin spans several bands, so the peak may land one band off.
            val slack = if (freq < 500.0) 1 else 0
            assertTrue("freq $freq peak band $peak own $own", abs(peak - own) <= slack)
            // Full scale reads 0 dB; Hann scalloping costs at most 1.42 dB, and bands under a bin interpolate.
            val floor = if (freq < 500.0) 0.9f else 0.97f
            assertTrue("freq $freq peak value ${out.bands[peak]}", out.bands[peak] >= floor)
            for (i in 0 until Features.BANDS) {
                val farBin = abs(Signals.bandCentre(i) - freq) >= 2 * binHz
                val farBand = freq >= 1000.0 && abs(i - own) >= 2
                if (i != own && i != peak && (farBin || farBand)) {
                    assertTrue(
                        "freq $freq band $i ${out.bands[i]} vs peak ${out.bands[peak]}",
                        out.bands[i] <= out.bands[peak] - db24 + 1e-6f,
                    )
                }
            }
        }
    }

    @Test
    fun `a full scale sine exactly on a bin reads zero dB`() {
        val out = steadyTone(3000.0, 48000) // bin 64
        val top = out.bands[Signals.argMax(out.bands)]
        assertEquals("on-bin level $top", 1f, top, 0.005f)
    }

    @Test
    fun `a half scale one kilohertz sine leaves the low bands quiet`() {
        val sr = 48000
        val sig = Signals.sine(1000.0, sr, 1024 + 30 * 800, 0.5)
        val a = Analyzer(sr)
        val out = Features()
        val mono = FloatArray(1024)
        val l = FloatArray(512)
        val r = FloatArray(512)
        for (f in 0 until 30) {
            sig.copyInto(mono, 0, f * 800, f * 800 + 1024)
            a.analyze(mono, l, r, 1f / 60f, out)
        }
        for (i in 0 until Features.BANDS) {
            if (Signals.bandCentre(i) < 300.0) assertTrue("band $i = ${out.bands[i]}", out.bands[i] <= 0.1f)
        }
    }

    @Test
    fun `a log sweep moves the peak band up and never back`() {
        val sr = 48000
        val feed = Feed(sr, Signals.logSweep(30.0, 16000.0, sr, sr * 4))
        var last = -1
        var first = -1
        val total = 4.0
        for (f in 2 until feed.frames) {
            feed.step(f)
            val p = Signals.argMax(feed.out.bands)
            val centreS = ((f + 1) * feed.hop - 512).toDouble() / sr
            val freq = 30.0 * Math.pow(16000.0 / 30.0, centreS / total)
            if (first < 0) first = p
            // Below about three bins (150 Hz) one 1024 window cannot place a chirp, so order is only held above.
            if (freq >= 150.0) {
                assertTrue("frame $f ($freq Hz) peak $p after $last", p >= last)
                last = p
            }
        }
        assertTrue("sweep starts low (first $first) and ends high (last $last)", first <= 14 && last >= 58)
    }

    @Test
    fun `silence gives zero bands and every value finite`() {
        val feed = Feed(48000, FloatArray(48000))
        for (f in 0 until 30) feed.step(f)
        for (v in feed.out.bands) assertEquals(0f, v, 0f)
        assertEquals(0f, feed.out.level, 0f)
        assertTrue(!feed.out.beat)
        Signals.assertFinite(feed.out)
    }

    @Test
    fun `a dc offset leaves the bands at the floor`() {
        val feed = Feed(48000, FloatArray(48000) { 0.5f })
        for (f in 0 until 100) feed.step(f)
        for ((i, v) in feed.out.bands.withIndex()) assertTrue("band $i = $v", v <= 0.05f)
    }

    @Test
    fun `a full scale square stays inside zero to one`() {
        val sr = 48000
        val sig = FloatArray(sr) { if (sin(2.0 * Math.PI * 1000.0 * it / sr) >= 0.0) 1f else -1f }
        val feed = Feed(sr, sig)
        for (f in 0 until 30) {
            feed.step(f)
            val o = feed.out
            for (v in o.bands) assertTrue("band $v", v in 0f..1f)
            for (v in o.peaks) assertTrue("peak $v", v in 0f..1f)
            for (v in floatArrayOf(o.level, o.bass, o.mid, o.treble, o.beatPulse)) assertTrue("scalar $v", v in 0f..1f)
            Signals.assertFinite(o)
        }
    }

    @Test
    fun `one kilohertz maps to the same band at 44100 48000 and 96000`() {
        val bands = intArrayOf(44100, 48000, 96000).map { Signals.argMax(steadyTone(1000.0, it).bands) }
        assertEquals(bands.toString(), 1, bands.toSet().size)
    }

    @Test
    fun `a step up lands in one frame and a step down releases at the set rate`() {
        val sr = 48000
        val tone = Signals.sine(1000.0, sr, 1024)
        val silent = FloatArray(1024)
        val a = Analyzer(sr)
        val out = Features()
        val l = FloatArray(512)
        val r = FloatArray(512)
        val dt = 1f / 60f
        a.analyze(tone, l, r, dt, out)
        val b = Signals.argMax(out.bands)
        val v0 = out.bands[b]
        assertTrue("one frame reaches $v0", v0 >= 0.9f)
        val frames = 15
        for (k in 1..frames) a.analyze(silent, l, r, dt, out)
        val slope = (v0 - out.bands[b]) / (frames * dt)
        assertEquals("release slope $slope", 1.2f, slope, 1.2f * 0.02f)
    }

    @Test
    fun `a peak cap holds then falls and never sits below its band`() {
        val sr = 48000
        val tone = Signals.sine(1000.0, sr, 1024)
        val silent = FloatArray(1024)
        val a = Analyzer(sr)
        val out = Features()
        val l = FloatArray(512)
        val r = FloatArray(512)
        val dt = 1f / 60f
        a.analyze(tone, l, r, dt, out)
        val b = Signals.argMax(out.bands)
        val v0 = out.bands[b]
        assertEquals(v0, out.peaks[b], 0f)
        for (k in 1..60) {
            a.analyze(silent, l, r, dt, out)
            for (i in 0 until Features.BANDS) assertTrue("peak under band at $k/$i", out.peaks[i] >= out.bands[i])
            if (k <= 29) assertEquals("held at frame $k", v0, out.peaks[b], 0f)
            if (k >= 33) assertTrue("falling at frame $k", out.peaks[b] < v0)
        }
        // 60 frames in, the fall has run 28 to 30 frames at 1.5 per second.
        val fall = v0 - out.peaks[b]
        assertTrue("fall $fall", fall in (1.5f * 28 * dt)..(1.5f * 30 * dt + 1e-4f))
    }

    @Test
    fun `ten thousand frames allocate nothing after warm up`() {
        val sr = 48000
        val sig = Signals.pink(sr * 2, 7L, 0.1)
        for (t0 in 0 until sig.size step 24000) Signals.addKick(sig, t0 + 500, sr, 0.8)
        val feed = Feed(sr, sig)
        for (f in 0 until 3000) feed.step(f % feed.frames)
        Signals.allocatedBytes()
        Signals.allocatedBytes()
        val overhead = Signals.allocationOverhead()
        val deltas = LongArray(4)
        for (block in 0 until 4) {
            val before = Signals.allocatedBytes()
            for (f in 0 until 2_500) feed.step(f % feed.frames)
            deltas[block] = Signals.allocatedBytes() - before - overhead
        }
        // The probe's own overhead varies by a few dozen bytes between reads, so a block may read below zero.
        assertTrue("allocated bytes per 2500 frames ${deltas.toList()}", deltas.all { it <= 0L })
    }
}
