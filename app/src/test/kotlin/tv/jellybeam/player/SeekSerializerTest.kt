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
}
