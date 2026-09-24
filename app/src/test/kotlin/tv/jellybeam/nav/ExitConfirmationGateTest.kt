package tv.jellybeam.nav

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExitConfirmationGateTest {
    @Test
    fun `second press inside the window exits`() {
        val gate = ExitConfirmationGate(windowMs = 2_000L)

        assertFalse(gate.press(10_000L))
        assertTrue(gate.press(11_999L))
    }

    @Test
    fun `expired press rearms instead of exiting`() {
        val gate = ExitConfirmationGate(windowMs = 2_000L)

        assertFalse(gate.press(10_000L))
        assertFalse(gate.press(12_001L))
        assertTrue(gate.press(13_000L))
    }

    @Test
    fun `navigation reset cancels an armed exit`() {
        val gate = ExitConfirmationGate()

        assertFalse(gate.press(10_000L))
        gate.reset()
        assertFalse(gate.press(10_100L))
    }
}
