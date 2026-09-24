package tv.jellybeam.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-logic coverage for [MenuSpine]'s open/close fade mapping. */
class MenuSpineTest {

    // ---- spineChevronAlpha ----------------------------------------------------

    @Test
    fun `chevron is opaque at rest and gone after the first 80ms of a 220ms open`() {
        assertEquals(1f, spineChevronAlpha(0f), 0f)
        assertEquals(0f, spineChevronAlpha(80f / 220f), 1e-6f)
        assertEquals(0f, spineChevronAlpha(1f), 0f)
    }

    @Test
    fun `chevron fades linearly through its slice`() {
        assertEquals(0.5f, spineChevronAlpha(40f / 220f), 1e-6f)
    }

    // ---- drawerContentAlpha ---------------------------------------------------

    @Test
    fun `rows stay hidden until the last 120ms of a 220ms open`() {
        assertEquals(0f, drawerContentAlpha(0f), 0f)
        assertEquals(0f, drawerContentAlpha(100f / 220f), 1e-6f)
        assertEquals(1f, drawerContentAlpha(1f), 0f)
    }

    @Test
    fun `rows fade linearly through their slice`() {
        assertEquals(0.5f, drawerContentAlpha(160f / 220f), 1e-6f)
    }

    @Test
    fun `chevron and rows never overlap`() {
        // Every progress value has at most one of the two visible.
        for (i in 0..220) {
            val p = i / 220f
            assertTrue("p=$p", spineChevronAlpha(p) == 0f || drawerContentAlpha(p) == 0f)
        }
    }
}
