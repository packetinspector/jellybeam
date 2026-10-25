package tv.jellybeam.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekSerializerTest {

    @Test
    fun `an idle request issues immediately`() {
        val seeks = SeekSerializer()
        val decision = seeks.request(targetMs = 10_000L, nowMs = 0L)
        assertEquals(SeekSerializer.Decision.Issue(10_000L), decision)
        assertEquals(10_000L, seeks.inFlightTargetMs)
        assertEquals(0L, seeks.inFlightSinceMs)
        assertNull(seeks.pendingTargetMs)
    }

    @Test
    fun `a request while one is in flight holds instead of issuing`() {
        val seeks = SeekSerializer()
        seeks.request(targetMs = 10_000L, nowMs = 0L)
        val decision = seeks.request(targetMs = 20_000L, nowMs = 100L)
        assertEquals(SeekSerializer.Decision.Hold(20_000L), decision)
        assertEquals(10_000L, seeks.inFlightTargetMs)
        assertEquals(20_000L, seeks.pendingTargetMs)
    }

    @Test
    fun `a later held request replaces an earlier one, not stacks`() {
        val seeks = SeekSerializer()
        seeks.request(targetMs = 10_000L, nowMs = 0L)
        seeks.request(targetMs = 20_000L, nowMs = 100L)
        seeks.request(targetMs = 30_000L, nowMs = 200L)
        assertEquals(30_000L, seeks.pendingTargetMs)
    }

    @Test
    fun `landed with a held target promotes it to in flight and returns it once`() {
        val seeks = SeekSerializer()
        seeks.request(targetMs = 10_000L, nowMs = 0L)
        seeks.request(targetMs = 20_000L, nowMs = 100L)

        val promoted = seeks.landed(nowMs = 300L)
        assertEquals(20_000L, promoted)
        assertEquals(20_000L, seeks.inFlightTargetMs)
        assertEquals(300L, seeks.inFlightSinceMs)
        assertNull(seeks.pendingTargetMs)

        // Landing the now-promoted target again returns null; the earlier promotion isn't repeated.
        assertNull(seeks.landed(nowMs = 600L))
    }

    @Test
    fun `landed with nothing held clears in flight and returns null`() {
        val seeks = SeekSerializer()
        seeks.request(targetMs = 10_000L, nowMs = 0L)
        val result = seeks.landed(nowMs = 300L)
        assertNull(result)
        assertNull(seeks.inFlightTargetMs)
        assertNull(seeks.inFlightSinceMs)
    }

    @Test
    fun `landed with nothing in flight is a no-op, such as the session's initial first frame`() {
        val seeks = SeekSerializer()
        assertNull(seeks.landed(nowMs = 0L))
        assertNull(seeks.inFlightTargetMs)
    }

    @Test
    fun `a held target equal to the in-flight target still lands as the recovery re-seek`() {
        val seeks = SeekSerializer()
        seeks.request(targetMs = 10_000L, nowMs = 0L)
        seeks.request(targetMs = 10_000L, nowMs = 100L) // same target, e.g. a repeated press
        val promoted = seeks.landed(nowMs = 300L)
        assertEquals(10_000L, promoted)
    }

    @Test
    fun `timeoutDue is false before the threshold and true at it`() {
        val seeks = SeekSerializer(landingTimeoutMs = 2_500L)
        seeks.request(targetMs = 10_000L, nowMs = 0L)
        assertFalse(seeks.timeoutDue(nowMs = 2_499L))
        assertTrue(seeks.timeoutDue(nowMs = 2_500L))
    }

    @Test
    fun `timeoutDue is false with nothing in flight`() {
        val seeks = SeekSerializer()
        assertFalse(seeks.timeoutDue(nowMs = 1_000_000L))
    }

    @Test
    fun `reset clears in-flight, pending, and since`() {
        val seeks = SeekSerializer()
        seeks.request(targetMs = 10_000L, nowMs = 0L)
        seeks.request(targetMs = 20_000L, nowMs = 100L)
        seeks.reset()
        assertNull(seeks.inFlightTargetMs)
        assertNull(seeks.pendingTargetMs)
        assertNull(seeks.inFlightSinceMs)
        assertFalse(seeks.timeoutDue(nowMs = 1_000_000L))
        // Idle again -- the next request issues rather than holds.
        assertEquals(SeekSerializer.Decision.Issue(30_000L), seeks.request(targetMs = 30_000L, nowMs = 500L))
    }

    @Test
    fun `a group seek reports its landing once with position and latency`() {
        val seeks = SeekSerializer()
        val reports = mutableListOf<Pair<Long, Long>>()
        seeks.request(targetMs = 90_000L, nowMs = 1_000L) { landedMs, latencyMs -> reports += landedMs to latencyMs }

        seeks.landed(nowMs = 1_350L, positionMs = 90_002L) // firstFrame
        seeks.landed(nowMs = 1_400L, positionMs = 90_040L) // STATE_READY after it

        assertEquals(listOf(90_002L to 350L), reports)
    }

    @Test
    fun `a group seek superseded by a held seek reports only the last landing`() {
        val seeks = SeekSerializer()
        val reports = mutableListOf<Triple<String, Long, Long>>()
        seeks.request(targetMs = 90_000L, nowMs = 0L) { landedMs, latencyMs -> reports += Triple("first", landedMs, latencyMs) }
        seeks.request(targetMs = 95_000L, nowMs = 100L) { landedMs, latencyMs -> reports += Triple("second", landedMs, latencyMs) }

        assertEquals(95_000L, seeks.landed(nowMs = 300L, positionMs = 90_000L))
        seeks.landed(nowMs = 700L, positionMs = 95_001L)

        assertEquals(listOf(Triple("second", 95_001L, 400L)), reports)
    }

    @Test
    fun `a seek without a report keeps the owed one and it fires at the newer landing`() {
        // docs/28 §5: an owed landing report that never fires leaves the member silent.
        val seeks = SeekSerializer()
        val reports = mutableListOf<Pair<Long, Long>>()
        seeks.request(targetMs = 90_000L, nowMs = 0L) { landedMs, latencyMs -> reports += landedMs to latencyMs }
        seeks.request(targetMs = 70_000L, nowMs = 100L) // a silent align, no report of its own

        assertEquals(70_000L, seeks.landed(nowMs = 300L, positionMs = 90_000L))
        seeks.landed(nowMs = 650L, positionMs = 70_001L)

        assertEquals(listOf(70_001L to 350L), reports)
    }

    @Test
    fun `a group seek that never lands still reports at the stall timeout`() {
        // docs/28 §5: an owed Ready that never goes out stalls the group; one off target is re-sent a Seek.
        val seeks = SeekSerializer()
        val reports = mutableListOf<Pair<Long, Long>>()
        seeks.request(targetMs = 90_000L, nowMs = 0L) { landedMs, latencyMs -> reports += landedMs to latencyMs }

        assertNull(seeks.stalled(nowMs = 2_000L, positionMs = 88_000L)) // not due yet
        assertNull(seeks.stalled(nowMs = 2_500L, positionMs = 88_500L))
        seeks.landed(nowMs = 2_600L, positionMs = 90_000L) // a late landing signal reports nothing more

        assertEquals(listOf(88_500L to 2_500L), reports)
    }

    @Test
    fun `a reset drops a group report without firing it`() {
        val seeks = SeekSerializer()
        val reports = mutableListOf<Long>()
        seeks.request(targetMs = 91_000L, nowMs = 0L) { landedMs, _ -> reports += landedMs }
        seeks.reset()
        seeks.request(targetMs = 92_000L, nowMs = 1_000L)

        seeks.landed(nowMs = 1_300L, positionMs = 92_000L)

        assertTrue(reports.isEmpty())
    }

    @Test
    fun `a group seek requested from a landing report keeps its own report`() {
        val seeks = SeekSerializer()
        val reports = mutableListOf<Long>()
        seeks.request(targetMs = 90_000L, nowMs = 0L) { _, _ ->
            seeks.request(targetMs = 96_000L, nowMs = 300L) { landedMs, _ -> reports += landedMs }
        }

        seeks.landed(nowMs = 300L, positionMs = 90_000L)
        seeks.landed(nowMs = 600L, positionMs = 96_000L)

        assertEquals(listOf(96_000L), reports)
    }
}
