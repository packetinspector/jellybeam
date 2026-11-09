package tv.jellybeam.visualizer.color

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.jellybeam.visualizer.dsp.Signals

/** docs/30 §4, plan U7: at most three flashes in any rolling second. */
class FlashLimiterTest {
    private val fps = 60

    /** Square wave between [lo] and [hi] flipping every [framesPerHalf] frames; one flip is one transition. */
    private fun square(frames: Int, framesPerHalf: Int, lo: Float, hi: Float) =
        FloatArray(frames) { if ((it / framesPerHalf) % 2 == 0) hi else lo }

    /** Independent oracle: a flash is a monotone run whose swing first exceeds 0.10; time of that crossing. */
    private fun flashTimes(v: FloatArray): List<Float> {
        val out = ArrayList<Float>()
        var origin = v[0]
        var dir = 0
        var counted = false
        for (i in 1 until v.size) {
            val d = v[i] - v[i - 1]
            if (abs(d) < 1e-6f) continue
            val nd = if (d > 0) 1 else -1
            if (nd != dir) { dir = nd; origin = v[i - 1]; counted = false }
            if (!counted && abs(v[i] - origin) > 0.10f) { out.add(i.toFloat() / fps); counted = true }
        }
        return out
    }

    private fun maxInWindow(times: List<Float>): Int {
        var best = 0
        for (i in times.indices) {
            var n = 0
            for (j in i until times.size) if (times[j] - times[i] < 1f) n++
            best = maxOf(best, n)
        }
        return best
    }

    private fun run(limiter: FlashLimiter, target: FloatArray): FloatArray =
        FloatArray(target.size) { limiter.limit(it.toFloat() / fps, target[it], 0f, 1f) }

    @Test
    fun `a 10 Hz strobe request leaves at most 3 flashes in any second`() {
        val req = square(5 * fps, 3, 0.1f, 0.5f)
        assertTrue("the request itself is a strobe", maxInWindow(flashTimes(req)) > 6)
        val out = run(FlashLimiter(), req)
        assertTrue("output flashes ${maxInWindow(flashTimes(out))}", maxInWindow(flashTimes(out)) <= 3)
        var jumps = 0
        for (i in 1 until out.size) if (abs(out[i] - out[i - 1]) > 0.10f) jumps++
        assertTrue("hard jumps $jumps in 5 s", jumps in 1..15)
    }

    @Test
    fun `a 10 Hz strobe of admit requests is admitted at most 3 times per second`() {
        val limiter = FlashLimiter()
        val admitted = ArrayList<Float>()
        for (k in 0 until 50) {
            val now = k / 10f
            val delta = if (k % 2 == 0) 0.4f else -0.4f
            val f = limiter.admit(now, delta, false)
            assertTrue("fraction in 0..1", f in 0f..1f)
            if (abs(f * delta) > 0.10f) admitted.add(now) else assertTrue("softened change stays below 0.10", abs(f * delta) < 0.10f)
        }
        assertTrue("admitted ${admitted.size}", admitted.size in 12..16)
        assertTrue(maxInWindow(admitted) <= 3)
    }

    @Test
    fun `2 Hz requests pass untouched`() {
        val limiter = FlashLimiter()
        for (k in 0 until 40) {
            val delta = if (k % 2 == 0) 0.4f else -0.4f
            assertEquals("request $k", 1f, limiter.admit(k * 0.5f, delta, false), 0f)
        }
        val l2 = FlashLimiter()
        val req = square(10 * fps, 30, 0.1f, 0.5f)
        for (i in req.indices) assertEquals("frame $i", req[i], l2.limit(i.toFloat() / fps, req[i], 0f, 1f), 0f)
    }

    @Test
    fun `red saturated requests are counted the same, even with no luminance change`() {
        val limiter = FlashLimiter()
        var passed = ArrayList<Float>()
        var refused = 0
        for (k in 0 until 50) {
            val f = limiter.admit(k / 10f, 0.05f, true)
            if (f >= 1f) passed.add(k / 10f) else { refused++; assertEquals("a red step cannot be softened", 0f, f, 0f) }
        }
        assertTrue(maxInWindow(passed) <= 3)
        assertTrue("some refused: $refused", refused > 20)
        val slow = FlashLimiter()
        for (k in 0 until 20) assertEquals(1f, slow.admit(k * 0.5f, 0.05f, true), 0f)
        val quiet = FlashLimiter()
        for (k in 0 until 50) assertEquals(1f, quiet.admit(k / 10f, if (k % 2 == 0) 0.05f else -0.05f, false), 0f)
        assertEquals(0, quiet.flashesInWindow(5f))
    }

    @Test
    fun `red and white flashes share one budget`() {
        val limiter = FlashLimiter()
        assertEquals(1f, limiter.admit(0f, 0.5f, false), 0f)
        assertEquals(1f, limiter.admit(0.1f, 0f, true), 0f)
        assertEquals(1f, limiter.admit(0.2f, -0.5f, false), 0f)
        assertEquals(3, limiter.flashesInWindow(0.25f))
        assertEquals(0f, limiter.admit(0.3f, 0f, true), 0f)
        assertTrue(limiter.admit(0.3f, 0.5f, false) < 0.2f)
        assertEquals("a quiet change still passes", 1f, limiter.admit(0.3f, 0.05f, false), 0f)
        assertEquals("first stamp expired", 1f, limiter.admit(1.1f, 0.5f, false), 0f)
    }

    @Test
    fun `silence makes no flashes and shows what it asks`() {
        val limiter = FlashLimiter()
        assertEquals(0, limiter.flashesInWindow(10f))
        for (i in 0 until 600) {
            assertEquals(0.02f, limiter.limit(i.toFloat() / fps, 0.02f, 0f, 1f), 0f)
        }
        assertEquals(0, limiter.flashesInWindow(10f))
    }

    @Test
    fun `a quiet change passes and a loud jump only while budget lasts`() {
        val limiter = FlashLimiter()
        limiter.limit(0f, 0.2f, 0f, 1f)
        assertEquals(0.25f, limiter.limit(0.05f, 0.25f, 0f, 1f), 0f)
        assertEquals("no flash for a 0.05 change", 0, limiter.flashesInWindow(0.05f))
        assertEquals(0.9f, limiter.limit(0.1f, 0.9f, 0f, 1f), 0f)
        assertEquals(1, limiter.flashesInWindow(0.1f))
        val spent = FlashLimiter()
        spent.admit(0f, 0.5f, false); spent.admit(0.01f, 0.5f, false); spent.admit(0.02f, 0.5f, false)
        spent.limit(0.05f, 0.2f, 0f, 1f)
        assertEquals("held below a 0.10 change", 0.2f + 0.07f, spent.limit(0.1f, 0.9f, 0f, 1f), 1e-5f)
        // The same run keeps creeping up no further than the held bound until the budget frees.
        assertEquals(0.2f + 0.07f, spent.limit(0.2f, 0.9f, 0f, 1f), 1e-5f)
        assertTrue("after the window the run lands", spent.limit(1.2f, 0.9f, 0f, 1f) > 0.89f)
    }

    @Test
    fun `the output stays within reach, and a swing it can't hold is still counted`() {
        val l = FlashLimiter()
        l.limit(0f, 0.6f, 0f, 1f)
        l.admit(0.01f, 0.5f, false); l.admit(0.02f, 0.5f, false); l.admit(0.03f, 0.5f, false)
        // Falling from 0.6 to 0.1 with the budget spent wants a hold near 0.53, but the brightest reachable is 0.2.
        assertEquals(0.2f, l.limit(0.1f, 0.1f, 0.05f, 0.2f), 0f)
        assertEquals("counted against the newest stamps", 3, l.flashesInWindow(0.1f))
        assertEquals("the oldest stamp was replaced, so the budget frees later", 3, l.flashesInWindow(1.015f))
        // The next run starts from the 0.2 actually shown, so a rebound to 0.6 is a fresh swing, held again.
        val back = l.limit(0.12f, 0.6f, 0f, 1f)
        assertTrue("rebound held near 0.2 + 0.07, got $back", back < 0.2f + 0.071f)
    }

    @Test
    fun `reset forgets every flash and the previous frame`() {
        val limiter = FlashLimiter()
        repeat(3) { limiter.admit(it * 0.01f, 0.5f, false) }
        limiter.reset()
        assertEquals(0, limiter.flashesInWindow(0.05f))
        assertEquals(1f, limiter.admit(0.05f, 0.5f, false), 0f)
    }

    @Test
    fun `saturated red follows R over the sum, in linear light`() {
        assertTrue(FlashLimiter.isSaturatedRed(1f, 0f, 0f))
        assertTrue(FlashLimiter.isSaturatedRed(0.8f, 0.2f, 0.2f))
        assertFalse(FlashLimiter.isSaturatedRed(0.9f, 0.5f, 0.5f))
        assertFalse(FlashLimiter.isSaturatedRed(0f, 0f, 0f))
        assertFalse(FlashLimiter.isSaturatedRed(0.1f, 0.9f, 0.1f))
    }

    @Test
    fun `the same inputs give the same outputs`() {
        val req = square(300, 4, 0.05f, 0.6f)
        val a = run(FlashLimiter(), req)
        val b = run(FlashLimiter(), req)
        for (i in a.indices) assertEquals(a[i], b[i], 0f)
    }

    @Test
    fun `calls allocate nothing`() {
        val limiter = FlashLimiter()
        val overhead = Signals.allocationOverhead()
        var sink = 0f
        repeat(2000) { limiter.limit(it / 60f, 0.2f + 0.1f * (it % 7), 0f, 1f) }
        val start = Signals.allocatedBytes()
        for (i in 0 until 20000) {
            val now = i / 60f
            sink += limiter.limit(now, 0.1f + 0.4f * ((i / 3) % 2), 0f, 1f)
            sink += limiter.admit(now, 0.5f, i % 5 == 0)
            sink += limiter.flashesInWindow(now)
        }
        val delta = Signals.allocatedBytes() - start - overhead
        assertEquals("bytes allocated (sink $sink)", 0L, delta)
    }
}
