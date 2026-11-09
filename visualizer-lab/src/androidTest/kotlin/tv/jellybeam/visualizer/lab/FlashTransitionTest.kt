package tv.jellybeam.visualizer.lab

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tv.jellybeam.visualizer.gl.VizDebug
import tv.jellybeam.visualizer.pattern.Pattern
import kotlin.math.abs

/** docs/30 §9 L7, L8: flash safety on real frames, and crossfades that never dip. */
@RunWith(AndroidJUnit4::class)
class FlashTransitionTest {
    /**
     * L7 (E4, WCAG 2.3.1): a 3 Hz strobe of kicks and loud noise against silence through every pattern; in
     * the captured frames, transitions (a same-direction swing of mean luminance beyond 0.10, over any
     * number of frames) happen at most 3 times in any 1 s (60-frame) window. A control run with every
     * flash guard off is logged: no pattern in the roster flashes past 3/s even unguarded, so the
     * limiter's own behaviour is pinned by the JVM tests (U7, GainCurveTest), not here.
     */
    @Test
    fun aThreeHertzStrobeFlashesAtMostThreeTimesASecond() {
        val failures = mutableListOf<String>()
        val strobe = LabSignals.strobe(FRAMES, hz = 3.0)
        for (p in Pattern.entries) {
            val limited = worstTransitionsPerSecond(p, strobe)
            VizDebug.limiterOff = true
            val unlimited = try { worstTransitionsPerSecond(p, strobe) } finally { VizDebug.limiterOff = false }
            Log.i("VizFlash", "${p.key} limited=$limited unlimited=$unlimited")
            if (limited > 3) failures += "${p.key}: $limited transitions in one second"
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun worstTransitionsPerSecond(p: Pattern, seq: List<tv.jellybeam.visualizer.Features>): Int = Lab(p).use { lab ->
        lab.captureAfter(seq.take(30))
        val n = FRAMES - 30
        val lum = DoubleArray(n)
        lab.source.playSequence(seq.drop(30), 0)
        for (i in 0 until n) lum[i] = lab.stepAndCapture().meanLuminance()
        val at = transitionFrames(lum)
        var worst = 0
        for (start in 0 until n) worst = maxOf(worst, at.count { it in start until start + 60 })
        worst
    }

    /** Frames where a run of same-direction movement first swings beyond 0.10 from where it began. */
    private fun transitionFrames(lum: DoubleArray): List<Int> {
        val out = mutableListOf<Int>()
        var origin = lum[0]
        var dir = 0
        var counted = false
        for (i in 1 until lum.size) {
            val d = lum[i] - lum[i - 1]
            if (d == 0.0) continue
            val nd = if (d > 0) 1 else -1
            if (nd != dir) {
                dir = nd
                origin = lum[i - 1]
                counted = false
            }
            if (!counted && abs(lum[i] - origin) > 0.10) {
                out += i
                counted = true
            }
        }
        return out
    }

    /**
     * L8 (E6): across an automatic crossfade, frames blend the two patterns (the middle differs from
     * both ends), no frame goes black, and none dips under half the darker end's mean brightness.
     */
    @Test
    fun aCrossfadeBlendsWithoutADip() {
        Lab(Pattern.BARS, autoChange = true).use { lab ->
            val noise = LabSignals.sequence(Preset.NOISE, 240)
            lab.captureAfter(noise.take(60))
            lab.source.playSequence(noise.drop(60), 0)
            val before = lab.stepAndCapture()
            val starts = VizDebug.autoStarts.get()
            lab.engine()!!.forceTransition()
            // Keep only luminance per frame plus the middle and last frames (a frame is 8 MB).
            val lum = DoubleArray(100)
            var mid: Frame? = null
            var after: Frame? = null
            for (i in 0 until 100) {
                val f = lab.stepAndCapture()
                lum[i] = f.meanLuminance()
                if (i == 45) mid = f
                if (i == 99) after = f
            }
            val lumBefore = before.meanLuminance()
            val lumAfter = lum[99]
            val floor = 0.5 * minOf(lumBefore, lumAfter)
            val dips = lum.indices.filter { lum[it] < floor }
            Log.i("VizFade", "before=$lumBefore after=$lumAfter mid=${lum[45]} dips=$dips")
            assertEquals("the transition did not start", starts + 1, VizDebug.autoStarts.get())
            assertTrue("dark frames during the crossfade: $dips", dips.isEmpty())
            assertTrue("the middle frame equals the start", diffFraction(mid!!, before) > 0.02)
            assertTrue("the middle frame equals the end", diffFraction(mid, after!!) > 0.02)
        }
    }

    private fun diffFraction(a: Frame, b: Frame): Double {
        var bad = 0
        for (y in 0 until a.height step 2) for (x in 0 until a.width step 2) {
            if (abs(a.r(x, y) - b.r(x, y)) > 8 || abs(a.g(x, y) - b.g(x, y)) > 8 || abs(a.b(x, y) - b.b(x, y)) > 8) bad++
        }
        return bad.toDouble() / ((a.width / 2) * (a.height / 2))
    }

    private companion object {
        const val FRAMES = 180
    }
}
