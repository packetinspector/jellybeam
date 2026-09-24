package tv.jellybeam.perf

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure math, no Android [android.view.FrameMetrics] involved. */
class FrameStatsHistogramTest {

    @Test
    fun `an empty histogram summarizes as all zeros`() {
        val histogram = FrameStatsHistogram()
        val summary = histogram.summary()
        assertEquals(0, summary.frames)
        assertEquals(0, summary.janky)
        assertEquals(0.0, summary.p50Ms, 0.0)
        assertEquals(0.0, summary.p90Ms, 0.0)
    }

    @Test
    fun `frames at or under the janky threshold are not counted as janky`() {
        val histogram = FrameStatsHistogram(jankyThresholdMs = 16.7)
        histogram.add(16.7)
        histogram.add(10.0)
        val summary = histogram.summary()
        assertEquals(2, summary.frames)
        assertEquals(0, summary.janky)
    }

    @Test
    fun `frames past the janky threshold are counted as janky`() {
        val histogram = FrameStatsHistogram(jankyThresholdMs = 16.7)
        histogram.add(16.8)
        histogram.add(33.0)
        histogram.add(5.0)
        val summary = histogram.summary()
        assertEquals(3, summary.frames)
        assertEquals(2, summary.janky)
    }

    @Test
    fun `p50 and p90 land on the expected bucket for a simple ramp`() {
        val histogram = FrameStatsHistogram()
        // percentile() returns the smallest bucket whose cumulative count exceeds
        // floor(frameCount * fraction), one past the naive 50th/90th value for this ramp.
        for (ms in 1..100) histogram.add(ms.toDouble())

        val summary = histogram.summary()
        assertEquals(100, summary.frames)
        assertEquals(51.0, summary.p50Ms, 0.0)
        assertEquals(91.0, summary.p90Ms, 0.0)
    }

    @Test
    fun `a single slow frame among many fast ones only shows up at a high percentile`() {
        val histogram = FrameStatsHistogram()
        repeat(99) { histogram.add(4.0) }
        histogram.add(200.0)

        val summary = histogram.summary()
        assertEquals(100, summary.frames)
        assertEquals(4.0, summary.p50Ms, 0.0)
        assertEquals(4.0, summary.p90Ms, 0.0)
    }

    @Test
    fun `reset clears counts so the next window starts from zero`() {
        val histogram = FrameStatsHistogram()
        histogram.add(50.0)
        histogram.add(60.0)
        histogram.reset()

        val summary = histogram.summary()
        assertEquals(0, summary.frames)
        assertEquals(0, summary.janky)
    }

    @Test
    fun `a duration past the bucket ceiling is clamped rather than throwing`() {
        val histogram = FrameStatsHistogram()
        histogram.add(10_000.0)
        val summary = histogram.summary()
        assertEquals(1, summary.frames)
        assertEquals(1, summary.janky)
    }
}
