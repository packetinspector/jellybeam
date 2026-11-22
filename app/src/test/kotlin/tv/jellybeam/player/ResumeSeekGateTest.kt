package tv.jellybeam.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** docs/18 §5.2: [ResumeSeekGate] hands a resume load's target to its own media-item transition, once. */
class ResumeSeekGateTest {

    @Test
    fun `an armed resume reserves its target exactly once`() {
        val gate = ResumeSeekGate()
        gate.arm(generation = 1L, targetMs = 42_000L)

        assertEquals(42_000L, gate.reserve(1L))
        // Media3 can deliver the transition again (stop's clearMediaItems): nothing more to reserve.
        assertNull(gate.reserve(1L))
    }

    @Test
    fun `a play-from-start load reserves nothing`() {
        val gate = ResumeSeekGate()
        gate.arm(generation = 1L, targetMs = 42_000L)
        gate.reset() // load() for generation 2, play-from-start

        assertNull(gate.reserve(1L))
        assertNull(gate.reserve(2L))
    }

    @Test
    fun `a transition for a replaced load reserves nothing and the newer load keeps its own`() {
        val gate = ResumeSeekGate()
        gate.arm(generation = 1L, targetMs = 10_000L)
        gate.arm(generation = 2L, targetMs = 20_000L)

        assertNull(gate.reserve(1L))
        assertEquals(20_000L, gate.reserve(2L))
    }
}
