package tv.jellybeam.ui.focus

import org.junit.Assert.assertEquals
import org.junit.Test

class LayerReentryTest {

    // -- gateEnter: only the top layer admits, and only around its own placement ----------

    @Test
    fun `a hidden layer is denied whatever its gate says`() {
        assertEquals(false, gateEnter(isTop = false, gateOpen = true))
        assertEquals(false, gateEnter(isTop = false, gateOpen = false))
    }

    @Test
    fun `the top layer admits only while its gate is held open`() {
        assertEquals(true, gateEnter(isTop = true, gateOpen = true))
        assertEquals(false, gateEnter(isTop = true, gateOpen = false))
    }

    // -- reentryAction --------------------------------------------------------

    @Test
    fun `a seeded ready screen restores its own remembered focus`() {
        assertEquals(Reentry.Restore, reentryAction(ready = true, seeded = true, placing = false))
    }

    @Test
    fun `a placement already in flight swallows the key`() {
        assertEquals(Reentry.Swallow, reentryAction(ready = true, seeded = true, placing = true))
        assertEquals(Reentry.Swallow, reentryAction(ready = true, seeded = false, placing = false))
        assertEquals(Reentry.Swallow, reentryAction(ready = true, seeded = false, placing = true))
    }

    @Test
    fun `a screen not ready to place hands the key to its layer`() {
        assertEquals(Reentry.Default, reentryAction(ready = false, seeded = false, placing = false))
        assertEquals(Reentry.Default, reentryAction(ready = false, seeded = true, placing = false))
    }

    // -- FocusSeat ------------------------------------------------------------

    @Test
    fun `a seat whose surface places nothing falls back, then opens the drawer on Left`() {
        val calls = mutableListOf<String>()
        val seat = FocusSeat().apply {
            place = { false }
            fallback = { calls += "fallback"; false }
            leftEdge = { calls += "drawer" }
        }
        seat.reenter(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(listOf("fallback"), calls)
        seat.reenter(android.view.KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(listOf("fallback", "fallback", "drawer"), calls)
    }

    @Test
    fun `an asynchronous miss reaches the same fallback chain`() {
        val calls = mutableListOf<String>()
        var miss: (() -> Unit)? = null
        val seat = FocusSeat().apply {
            place = { onMiss -> miss = onMiss; true }
            fallback = { calls += "fallback"; true }
            leftEdge = { calls += "drawer" }
        }
        seat.reenter(android.view.KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(emptyList<String>(), calls)
        miss!!()
        assertEquals(listOf("fallback"), calls)
    }
}
