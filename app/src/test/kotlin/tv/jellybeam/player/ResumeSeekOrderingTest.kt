package tv.jellybeam.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * docs/18 §5.2: the resume seek and a user skip interleave through [SeekSerializer] in the order
 * [PlayerHolder] drives them (load -> transition -> input -> tracks -> landing), so a skip pressed
 * while the player is still preparing lands on top of the resume instead of being overwritten.
 */
class ResumeSeekOrderingTest {
    private val gate = ResumeSeekGate()
    private val seeks = SeekSerializer()
    private val issued = mutableListOf<Long>()

    private fun userSkip(targetMs: Long, nowMs: Long) {
        if (seeks.request(targetMs, nowMs) is SeekSerializer.Decision.Issue) issued += targetMs
    }

    private fun tracksChanged() {
        resumeSeekToIssue(gate, seeks, 1L)?.let { issued += it }
    }

    private fun landed(nowMs: Long) {
        seeks.landed(nowMs)?.let { issued += it }
    }

    @Test
    fun `a skip pressed before tracks arrive replays on top of the resume, never the reverse`() {
        gate.arm(1L, 42_000L)
        assertEquals(42_000L, reserveResumeSeek(gate, seeks, 1L, nowMs = 0L))

        userSkip(52_000L, nowMs = 100L) // held: the reservation owns the slot
        tracksChanged() // the resume seek goes to the player
        landed(nowMs = 400L) // the held skip replays
        landed(nowMs = 800L)

        assertEquals(listOf(42_000L, 52_000L), issued)
        assertNull(seeks.inFlightTargetMs)
    }

    @Test
    fun `a skip pressed after the resume seek is issued is held until it lands`() {
        gate.arm(1L, 42_000L)
        reserveResumeSeek(gate, seeks, 1L, nowMs = 0L)
        tracksChanged()
        userSkip(52_000L, nowMs = 200L)
        landed(nowMs = 400L)

        assertEquals(listOf(42_000L, 52_000L), issued)
    }

    @Test
    fun `without a reservation a user seek already in flight wins and the resume snap is dropped`() {
        gate.arm(1L, 42_000L)
        userSkip(52_000L, nowMs = 100L)
        tracksChanged()
        landed(nowMs = 400L)

        assertEquals(listOf(52_000L), issued)
        assertNull(seeks.inFlightTargetMs)
    }

    @Test
    fun `a play-from-start reserves nothing and a skip issues straight away`() {
        gate.reset()
        assertNull(reserveResumeSeek(gate, seeks, 1L, nowMs = 0L))
        userSkip(10_000L, nowMs = 100L)
        tracksChanged()

        assertEquals(listOf(10_000L), issued)
    }

    @Test
    fun `a stale generation reserves and issues nothing`() {
        gate.arm(1L, 42_000L)
        assertNull(reserveResumeSeek(gate, seeks, 2L, nowMs = 0L))
        assertNull(resumeSeekToIssue(gate, seeks, 2L))
    }
}
