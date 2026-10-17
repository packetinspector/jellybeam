package tv.jellybeam.ui.focus

import org.junit.Assert.assertEquals
import org.junit.Test

class RingClearanceTest {

    @Test
    fun `an item well inside the viewport does not scroll`() {
        assertEquals(0f, ringClearScrollDistance(offset = 100f, size = 50f, containerSize = 1000f, margin = 12f), 0f)
    }

    @Test
    fun `an item flush with the bottom edge scrolls its ring into view`() {
        // Row bottom at 1000 = the viewport bottom: the ring below it needs 12 more.
        assertEquals(12f, ringClearScrollDistance(offset = 950f, size = 50f, containerSize = 1000f, margin = 12f), 0f)
    }

    @Test
    fun `an item whose ring alone crosses the bottom edge scrolls by the overhang`() {
        assertEquals(7f, ringClearScrollDistance(offset = 945f, size = 50f, containerSize = 1000f, margin = 12f), 0f)
    }

    @Test
    fun `an item below the viewport scrolls up to show its ring`() {
        assertEquals(162f, ringClearScrollDistance(offset = 1100f, size = 50f, containerSize = 1000f, margin = 12f), 0f)
    }

    @Test
    fun `an item flush with the top edge scrolls its ring into view`() {
        assertEquals(-12f, ringClearScrollDistance(offset = 0f, size = 50f, containerSize = 1000f, margin = 12f), 0f)
    }

    @Test
    fun `an item taller than the viewport keeps the plain rule`() {
        // Spans both edges: no scroll, same as the framework default.
        assertEquals(0f, ringClearScrollDistance(offset = -10f, size = 1020f, containerSize = 1000f, margin = 12f), 0f)
    }
}
