package tv.jellybeam.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import tv.jellybeam.AccentSchemes
import tv.jellybeam.accentSchemeFor

/** docs/09 Appearance: the swatch grid's navigation, entry and preview rules as pure functions. */
class AppearanceSectionTest {
    private val last = AccentSchemes.size - 1

    @Test
    fun `grid is two rows of six with each deep under its light partner`() {
        assertEquals(12, AccentSchemes.size)
        AccentSchemes.take(ACCENT_COLUMNS).forEach { assertEquals(false, it.deep) }
        AccentSchemes.drop(ACCENT_COLUMNS).forEach { assertEquals(true, it.deep) }
    }

    @Test
    fun `right and left move one flavour and stop at the right edge`() {
        assertEquals(AccentStep.To(3), accentStep(2, AccentDir.Right))
        assertEquals(AccentStep.To(1), accentStep(2, AccentDir.Left))
        assertEquals(AccentStep.Stay, accentStep(5, AccentDir.Right))
        assertEquals(AccentStep.Stay, accentStep(last, AccentDir.Right))
    }

    @Test
    fun `left from the first column of either row returns to the rail`() {
        assertEquals(AccentStep.Rail, accentStep(0, AccentDir.Left))
        assertEquals(AccentStep.Rail, accentStep(ACCENT_COLUMNS, AccentDir.Left))
    }

    @Test
    fun `down switches light to deep in the same column and up switches back`() {
        for (col in 0 until ACCENT_COLUMNS) {
            assertEquals(AccentStep.To(col + ACCENT_COLUMNS), accentStep(col, AccentDir.Down))
            assertEquals(AccentStep.To(col), accentStep(col + ACCENT_COLUMNS, AccentDir.Up))
        }
    }

    @Test
    fun `up from light and down from deep stay`() {
        for (col in 0 until ACCENT_COLUMNS) {
            assertEquals(AccentStep.Stay, accentStep(col, AccentDir.Up))
            assertEquals(AccentStep.Stay, accentStep(col + ACCENT_COLUMNS, AccentDir.Down))
        }
    }

    @Test
    fun `a walk across a row keeps its shade and a walk down keeps its column`() {
        var index = 8
        repeat(3) { index = (accentStep(index, AccentDir.Right) as? AccentStep.To)?.index ?: index }
        assertEquals(11, index)
        assertEquals(AccentStep.To(5), accentStep(index, AccentDir.Up))
    }

    @Test
    fun `preview follows the focused swatch and falls back to the one in use`() {
        val inUse = accentSchemeFor("bosco")
        val focused = accentSchemeFor("menta")
        assertSame(focused, accentPreviewScheme(focused, inUse))
        assertSame(inUse, accentPreviewScheme(null, inUse))
    }
}
