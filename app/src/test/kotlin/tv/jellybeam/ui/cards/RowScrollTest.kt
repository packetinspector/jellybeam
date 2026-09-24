package tv.jellybeam.ui.cards

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure-logic coverage for [shelfBringIntoViewScrollDistance] (bugs 1/2). The Compose/LazyRow
 * wiring itself needs a real D-pad pass -- no Compose UI test harness exists in this module.
 */
class RowScrollTest {

    @Test
    fun `item already resting at the margin needs no scroll`() {
        assertEquals(0f, shelfBringIntoViewScrollDistance(offset = 40f, startMarginPx = 40f), 0f)
    }

    @Test
    fun `mid-row item further right than the margin pins to the margin`() {
        assertEquals(280f, shelfBringIntoViewScrollDistance(offset = 320f, startMarginPx = 40f), 0f)
    }

    @Test
    fun `row-start item left of the margin requests a negative distance`() {
        // LazyRow's own start-of-content clamp turns this negative request into "stay put".
        assertEquals(-40f, shelfBringIntoViewScrollDistance(offset = 0f, startMarginPx = 40f), 0f)
    }

    @Test
    fun `zero margin degenerates to pinning flush at the viewport edge`() {
        assertEquals(150f, shelfBringIntoViewScrollDistance(offset = 150f, startMarginPx = 0f), 0f)
    }

    // -- headerPageScrollToTopDistance -----------------------------------------

    @Test
    fun `an action row scrolled above the viewport brings the page back to its top`() {
        // Page scrolled 900px; the row sits at content y 60 (offset -840), header ends at 600.
        assertEquals(-900f, headerPageScrollToTopDistance(offset = -840f, size = 71f, containerSize = 1080f, scrolled = 900f, headerBottomPx = 600f))
    }

    @Test
    fun `the rule keeps holding once the row has entered the viewport mid-animation`() {
        // Same row at content y 60 with 50px of scroll left: still the distance to the top.
        assertEquals(-50f, headerPageScrollToTopDistance(offset = 10f, size = 71f, containerSize = 1080f, scrolled = 50f, headerBottomPx = 600f))
    }

    @Test
    fun `a cast card above the viewport but below the header uses the default rule`() {
        assertEquals(null, headerPageScrollToTopDistance(offset = -150f, size = 236f, containerSize = 1080f, scrolled = 1250f, headerBottomPx = 600f))
    }

    @Test
    fun `an unscrolled page never triggers the rule`() {
        assertEquals(null, headerPageScrollToTopDistance(offset = 400f, size = 71f, containerSize = 1080f, scrolled = 0f, headerBottomPx = 600f))
    }

    @Test
    fun `a header item that does not fit the first screenful uses the default rule`() {
        // A long header pushes its action row past the first viewport: scrolling to 0 would hide it.
        assertEquals(null, headerPageScrollToTopDistance(offset = 1100f, size = 71f, containerSize = 1080f, scrolled = 300f, headerBottomPx = 1500f))
    }

    @Test
    fun `an unmeasured header defers to the default rule`() {
        assertEquals(null, headerPageScrollToTopDistance(offset = -840f, size = 71f, containerSize = 1080f, scrolled = 900f, headerBottomPx = Float.NaN))
    }
}
