package tv.jellybeam.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * docs/18 §5.2: a resume and a user skip interleave through [SeekSerializer] in the order [PlayerHolder] drives them
 * (load -> transition -> input -> landing). The resume itself is never a player seek: its source starts at the keyframe.
 */
class ResumeSeekOrderingTest {
    private val gate = ResumeSeekGate()
    private val seeks = SeekSerializer()
    private val issued = mutableListOf<Long>()

    private fun userSkip(targetMs: Long, nowMs: Long) {
        if (seeks.request(targetMs, nowMs) is SeekSerializer.Decision.Issue) issued += targetMs
    }

    private fun landed(nowMs: Long) {
        seeks.landed(nowMs)?.let { issued += it }
    }

    @Test
    fun `a resume issues no player seek and its landing frees the slot`() {
        gate.arm(1L, 42_000L)
        assertEquals(42_000L, reserveResumeSeek(gate, seeks, 1L, nowMs = 0L))
        assertEquals(42_000L, seeks.inFlightTargetMs)

        landed(nowMs = 400L)

        assertEquals(emptyList<Long>(), issued)
        assertNull(seeks.inFlightTargetMs)
    }

    @Test
    fun `a skip pressed while the resume prepares is held and replays on its landing`() {
        gate.arm(1L, 42_000L)
        reserveResumeSeek(gate, seeks, 1L, nowMs = 0L)

        userSkip(52_000L, nowMs = 100L)
        assertEquals(emptyList<Long>(), issued)
        landed(nowMs = 400L) // the resume's first frame: the held skip replays
        landed(nowMs = 800L)

        assertEquals(listOf(52_000L), issued)
        assertNull(seeks.inFlightTargetMs)
    }

    @Test
    fun `a play-from-start reserves nothing and a skip issues straight away`() {
        gate.reset()
        assertNull(reserveResumeSeek(gate, seeks, 1L, nowMs = 0L))
        userSkip(10_000L, nowMs = 100L)

        assertEquals(listOf(10_000L), issued)
    }

    @Test
    fun `a stale generation reserves nothing`() {
        gate.arm(1L, 42_000L)
        assertNull(reserveResumeSeek(gate, seeks, 2L, nowMs = 0L))
        assertNull(seeks.inFlightTargetMs)
    }

    /** docs/12 §9: an unseekable file's source starts at 0 with no seek; its landing still frees the slot. */
    @Test
    fun `an unseekable resume issues nothing and frees the slot on landing`() {
        gate.arm(1L, 42_000L)
        reserveResumeSeek(gate, seeks, 1L, nowMs = 0L)
        landed(nowMs = 300L)
        userSkip(5_000L, nowMs = 400L)

        assertEquals(listOf(5_000L), issued)
    }
}
