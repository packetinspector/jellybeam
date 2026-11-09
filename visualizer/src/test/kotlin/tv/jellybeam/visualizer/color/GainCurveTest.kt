package tv.jellybeam.visualizer.color

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/** docs/30 §4: the composite gain from the measured shown-luminance curve. */
class GainCurveTest {
    // Shown luminance at GAINS 0, .25, .5, .75, 1, 1.5, 2.5, 4.
    private val rising = floatArrayOf(0.01f, 0.05f, 0.1f, 0.15f, 0.2f, 0.3f, 0.45f, 0.6f)

    @Test
    fun `the stats shader measures at the same gains`() {
        val src = File("src/main/assets/viz2/lum.frag.glsl").readText()
        val list = Regex("""GAINS\[8\] = float\[8\]\(([^)]*)\)""").find(src)!!.groupValues[1]
        assertEquals(GainCurve.GAINS.toList(), list.split(',').map { it.trim().toFloat() })
    }

    @Test
    fun `an unchanged frame keeps gain one`() {
        assertEquals(1f, GainCurve.solve(rising, 0.2f), 0f)
    }

    @Test
    fun `dimming and brightening land on the target, linear between knots`() {
        assertEquals(0.5f, GainCurve.solve(rising, 0.1f), 1e-6f)
        assertEquals(0.625f, GainCurve.solve(rising, 0.125f), 1e-6f)
        assertEquals(2f, GainCurve.solve(rising, 0.375f), 1e-6f)
    }

    @Test
    fun `out of reach takes the extreme gain`() {
        assertEquals(0f, GainCurve.solve(rising, 0.001f), 0f)
        assertEquals(4f, GainCurve.solve(rising, 0.9f), 0f)
    }

    @Test
    fun `a flat curve never pulls the gain away from one`() {
        // A thin line on a dark field: every gain shows the same luminance.
        val flat = FloatArray(8) { 0.0056f }
        assertEquals(1f, GainCurve.solve(flat, 0.0056f), 0f)
        // Asked a hair dimmer than anything reachable, it takes the darkest gain: still never brighter than asked.
        assertEquals(0f, GainCurve.solve(flat, 0.0050f), 0f)
    }

    @Test
    fun `a flat stretch takes the knot nearest one`() {
        // Flat from 0 to 0.5: asking for that floor dims no further than 0.5.
        val floor = floatArrayOf(0.01f, 0.01f, 0.01f, 0.05f, 0.2f, 0.3f, 0.45f, 0.6f)
        assertEquals(0.5f, GainCurve.solve(floor, 0.01f), 0f)
    }

    @Test
    fun `a dip in the curve is crossed from one outward`() {
        // Non-monotone: the nearest crossing below gain 1 wins, not a farther one.
        val dip = floatArrayOf(0.01f, 0.12f, 0.08f, 0.15f, 0.2f, 0.3f, 0.45f, 0.6f)
        assertEquals(0.5f + 0.25f * (0.1f - 0.08f) / (0.15f - 0.08f), GainCurve.solve(dip, 0.1f), 1e-6f)
    }
}
