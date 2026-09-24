package tv.jellybeam.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [PerfAccumulator] takes `now`/`emit` as constructor parameters so tests can fake both without
 * Robolectric or a periodic ticker.
 */
class PerfAccumulatorTest {

    private class FakeClock(private var nowMs: Long = 0L) {
        fun advanceTo(ms: Long) {
            nowMs = ms
        }

        fun get(): Long = nowMs
    }

    @Test
    fun `a call under the outlier threshold never emits until the flush interval elapses`() {
        val clock = FakeClock()
        val emitted = mutableListOf<String>()
        val accumulator = PerfAccumulator(
            label = "test",
            outlierMs = 5.0,
            flushIntervalMs = 10_000,
            now = clock::get,
            emit = emitted::add,
        )

        accumulator.record(durationMs = 1.0)
        assertTrue("no outlier, no flush interval elapsed yet -- nothing should be emitted", emitted.isEmpty())
    }

    @Test
    fun `a call above the outlier threshold emits immediately with the detail id, never the periodic average`() {
        val clock = FakeClock()
        val emitted = mutableListOf<String>()
        val accumulator = PerfAccumulator(
            label = "ffi.imageUrl",
            outlierMs = 5.0,
            flushIntervalMs = 10_000,
            now = clock::get,
            emit = emitted::add,
        )

        accumulator.record(durationMs = 42.0, detail = "item-123")

        assertEquals(1, emitted.size)
        assertTrue(emitted[0].contains("outlier"))
        assertTrue(emitted[0].contains("ms=42.00"))
        assertTrue(emitted[0].contains("id=item-123"))
    }

    @Test
    fun `outlierMs null never emits an immediate line, however slow a single call is`() {
        val clock = FakeClock()
        val emitted = mutableListOf<String>()
        val accumulator = PerfAccumulator(
            label = "image.load",
            outlierMs = null,
            flushIntervalMs = 10_000,
            now = clock::get,
            emit = emitted::add,
        )

        accumulator.record(durationMs = 300.0)
        assertTrue(emitted.isEmpty())
    }

    @Test
    fun `once the flush interval elapses, record emits one summary including the triggering call, then starts a fresh window`() {
        val clock = FakeClock()
        val emitted = mutableListOf<String>()
        val accumulator = PerfAccumulator(
            label = "image.load",
            flushIntervalMs = 1_000,
            now = clock::get,
            emit = emitted::add,
        )

        accumulator.record(durationMs = 10.0, success = true)
        accumulator.record(durationMs = 20.0, success = true)
        accumulator.record(durationMs = 30.0, success = false)
        assertTrue("still inside the flush window", emitted.isEmpty())

        clock.advanceTo(1_000)
        // The triggering call's own duration/success is folded into the flush it causes.
        accumulator.record(durationMs = 5.0, success = true)

        assertEquals(1, emitted.size)
        val line = emitted[0]
        assertTrue(line.contains("count=4"))
        assertTrue(line.contains("success=3"))
        assertTrue(line.contains("avgMs=16.25"))
        assertTrue(line.contains("maxMs=30.00"))

        // A flush resets the window's counters.
        clock.advanceTo(1_999)
        accumulator.record(durationMs = 7.0, success = true)
        accumulator.flush()

        assertEquals(2, emitted.size)
        assertTrue(emitted[1].contains("count=1"))
        assertTrue(emitted[1].contains("avgMs=7.00"))
    }

    @Test
    fun `flush with nothing recorded since the last one emits no line`() {
        val clock = FakeClock()
        val emitted = mutableListOf<String>()
        val accumulator = PerfAccumulator(label = "test", now = clock::get, emit = emitted::add)

        accumulator.flush()
        assertTrue(emitted.isEmpty())
    }

    @Test
    fun `byte totals only appear in the summary line when something was actually recorded`() {
        val clock = FakeClock()
        val emitted = mutableListOf<String>()
        val accumulator = PerfAccumulator(label = "image.load", now = clock::get, emit = emitted::add)

        accumulator.record(durationMs = 10.0, sizeBytes = 4_096)
        accumulator.flush()

        assertEquals(1, emitted.size)
        assertTrue(emitted[0].contains("bytes=4096"))
    }

    // docs/10-perf-logging.md: a burst that ends before the flush interval never re-enters
    // record(), so flushIfDue() is the only remaining trigger -- PerfLog.flushAllIfDue() calls
    // this from MainActivity's 10s frame-metrics tick.

    @Test
    fun `flushIfDue does nothing before the flush interval has elapsed`() {
        val clock = FakeClock()
        val emitted = mutableListOf<String>()
        val accumulator = PerfAccumulator(label = "image.load", flushIntervalMs = 10_000, now = clock::get, emit = emitted::add)

        accumulator.record(durationMs = 10.0)
        clock.advanceTo(9_999)
        accumulator.flushIfDue()

        assertTrue("interval not yet elapsed -- nothing should be emitted", emitted.isEmpty())
    }

    @Test
    fun `flushIfDue flushes a burst that never triggers another record once its interval elapses`() {
        val clock = FakeClock()
        val emitted = mutableListOf<String>()
        val accumulator = PerfAccumulator(label = "ffi.imageUrl", flushIntervalMs = 10_000, now = clock::get, emit = emitted::add)

        // A short burst (e.g. a grid scroll) that stops well inside the window -- record() alone
        // never flushes this, since nothing calls it again.
        accumulator.record(durationMs = 1.0)
        accumulator.record(durationMs = 2.0)
        clock.advanceTo(10_000)

        accumulator.flushIfDue()

        assertEquals(1, emitted.size)
        assertTrue(emitted[0].contains("count=2"))
    }

    @Test
    fun `flushIfDue with nothing recorded emits no line and does not disturb the next window`() {
        val clock = FakeClock()
        val emitted = mutableListOf<String>()
        val accumulator = PerfAccumulator(label = "test", flushIntervalMs = 1_000, now = clock::get, emit = emitted::add)

        clock.advanceTo(1_000)
        accumulator.flushIfDue()
        assertTrue(emitted.isEmpty())

        accumulator.record(durationMs = 5.0)
        clock.advanceTo(1_999)
        accumulator.flushIfDue()
        assertTrue("interval restarted at the no-op flush, so this hasn't elapsed yet", emitted.isEmpty())

        clock.advanceTo(2_000)
        accumulator.flushIfDue()
        assertEquals(1, emitted.size)
        assertTrue(emitted[0].contains("count=1"))
    }
}
