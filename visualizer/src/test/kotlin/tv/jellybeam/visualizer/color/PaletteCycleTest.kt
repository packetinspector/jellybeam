package tv.jellybeam.visualizer.color

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.jellybeam.visualizer.dsp.Signals

/** docs/30 §4, plan U6: the palette cycle drifts continuously and steps on phrases only through the limiter. */
class PaletteCycleTest {
    private val dt = 1f / 60f
    private val frameCap = 1f / 64f

    private fun circular(from: Float, to: Float): Float {
        val d = to - from
        return when {
            d < -0.5f -> d + 1f
            d > 0.5f -> d - 1f
            else -> d
        }
    }

    @Test
    fun `the offset is continuous at every energy and wraps at one`() {
        for (energy in floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val cycle = PaletteCycle()
            val limiter = FlashLimiter()
            var prev = cycle.offset
            var travelled = 0f
            for (i in 1..3000) {
                cycle.update(dt, energy, false, limiter, i * dt)
                val step = circular(prev, cycle.offset)
                assertTrue("energy $energy frame $i step $step", step in 0f..frameCap)
                assertTrue("offset ${cycle.offset}", cycle.offset >= 0f && cycle.offset < 1f)
                travelled += step
                prev = cycle.offset
            }
            val wantSpeed = 1f / 60f + (1f / 20f - 1f / 60f) * energy
            assertEquals("energy $energy cycles in 50 s", wantSpeed * 50f, travelled, 0.01f)
        }
    }

    @Test
    fun `speed runs from one cycle per 60 s to one per 20 s and follows energy`() {
        fun speedAt(energy: Float): Float {
            val c = PaletteCycle()
            c.update(0.25f, energy, false, FlashLimiter(), 0f)
            return c.offset / 0.25f
        }
        assertEquals(1f / 60f, speedAt(0f), 1e-5f)
        assertEquals(1f / 20f, speedAt(1f), 1e-5f)
        assertTrue(speedAt(0.3f) < speedAt(0.6f))
        assertEquals("out of range energy clamps", speedAt(1f), speedAt(5f), 0f)
        assertEquals(speedAt(0f), speedAt(-2f), 0f)
    }

    @Test
    fun `a long frame never moves the drift by more than 1 over 64 of a cycle`() {
        val c = PaletteCycle()
        c.update(2f, 1f, false, FlashLimiter(), 0f)
        assertEquals(frameCap, c.offset, 1e-6f)
        val before = c.offset
        c.update(-1f, 1f, false, FlashLimiter(), 0f)
        assertEquals("negative dt does not run backwards", before, c.offset, 0f)
    }

    @Test
    fun `an admitted phrase tick steps a quarter cycle on top of the drift`() {
        val cycle = PaletteCycle()
        val limiter = FlashLimiter()
        cycle.update(dt, 0.5f, false, limiter, 0f)
        val before = cycle.offset
        cycle.update(dt, 0.5f, true, limiter, dt)
        assertEquals(PaletteCycle.STEP, circular(before, cycle.offset), frameCap)
        assertEquals(0.25f, PaletteCycle.STEP, 0f)
        var o = cycle.offset
        repeat(4) { cycle.update(dt, 0f, true, limiter, 10f + it) }
        assertTrue("four steps wrap round: $o to ${cycle.offset}", cycle.offset >= 0f && cycle.offset < 1f)
    }

    @Test
    fun `no phrase tick means no step even with the budget free`() {
        val cycle = PaletteCycle()
        val limiter = FlashLimiter()
        var prev = cycle.offset
        for (i in 1..600) {
            cycle.update(dt, 1f, false, limiter, i * dt)
            assertTrue(abs(circular(prev, cycle.offset)) <= frameCap)
            prev = cycle.offset
        }
    }

    @Test
    fun `a red phrase step is refused while the budget is spent and a quiet one is not`() {
        val limiter = FlashLimiter()
        limiter.admit(0f, 0.5f, false); limiter.admit(0.1f, -0.5f, false); limiter.admit(0.2f, 0.5f, false)
        val cycle = PaletteCycle()
        cycle.update(dt, 0f, true, limiter, 0.3f, stepIsRed = true)
        assertTrue("red step refused, offset ${cycle.offset}", cycle.offset <= frameCap)
        assertEquals(3, limiter.flashesInWindow(0.3f))
        val before = cycle.offset
        cycle.update(dt, 0f, true, limiter, 0.35f, stepIsRed = false)
        assertEquals("hue only step carries no luminance change", PaletteCycle.STEP, circular(before, cycle.offset), frameCap)
        assertEquals("and uses no flash budget", 3, limiter.flashesInWindow(0.35f))
        val b2 = cycle.offset
        cycle.update(dt, 0f, true, limiter, 1.25f, stepIsRed = true)
        assertEquals("budget frees, red step lands", PaletteCycle.STEP, circular(b2, cycle.offset), frameCap)
        assertEquals("and costs one flash, the earlier three having expired", 1, limiter.flashesInWindow(1.25f))
    }

    @Test
    fun `phrase ticks arriving every tenth of a second step red at most 3 times a second`() {
        val cycle = PaletteCycle()
        val limiter = FlashLimiter()
        val stepTimes = ArrayList<Float>()
        var prev = cycle.offset
        for (k in 1..60) {
            val now = k / 10f
            cycle.update(0.1f, 0f, true, limiter, now, stepIsRed = true)
            if (abs(circular(prev, cycle.offset)) > 0.2f) stepTimes.add(now)
            prev = cycle.offset
        }
        for (t0 in stepTimes) assertTrue("steps in $t0..", stepTimes.count { it >= t0 && it < t0 + 1f } <= 3)
        assertTrue("some steps land: ${stepTimes.size}", stepTimes.size in 15..20)
    }

    @Test
    fun `calls allocate nothing`() {
        val cycle = PaletteCycle()
        val limiter = FlashLimiter()
        val overhead = Signals.allocationOverhead()
        repeat(2000) { cycle.update(dt, 0.5f, it % 90 == 0, limiter, it * dt, it % 180 == 0) }
        val start = Signals.allocatedBytes()
        for (i in 0 until 20000) cycle.update(dt, (i % 100) / 100f, i % 90 == 0, limiter, i * dt, i % 180 == 0)
        assertEquals(0L, Signals.allocatedBytes() - start - overhead)
    }
}
