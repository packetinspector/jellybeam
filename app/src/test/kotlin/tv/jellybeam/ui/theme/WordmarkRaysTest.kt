package tv.jellybeam.ui.theme

import org.junit.Test
import org.junit.Assert.assertEquals

class WordmarkRaysTest {
    @Test
    fun group_box_sits_past_the_text_end_and_above_the_baseline() {
        val bars = WordmarkRays.bars(emPx = 100f, textEndPx = 500f, baselinePx = 200f)
        assertEquals(3, bars.size)
        // Group left = textEnd + 0.04em - 0.6em; bar B starts 0.267em in, at the group top (y 0).
        val b = bars[1]
        assertEquals(500f + 4f - 60f + 26.7f, b.topLeft.x, 0.01f)
        assertEquals(200f - 116f, b.topLeft.y, 0.01f)
        assertEquals(33.3f, b.size.width, 0.01f)
        assertEquals(10.7f, b.size.height, 0.01f)
        assertEquals(8f, b.degrees)
    }

    @Test
    fun rotation_pivots_are_bar_centres() {
        val a = WordmarkRays.bars(emPx = 10f, textEndPx = 0f, baselinePx = 0f)[0]
        assertEquals(a.topLeft.x + a.size.width / 2f, a.center.x, 0.001f)
        assertEquals(a.topLeft.y + a.size.height / 2f, a.center.y, 0.001f)
    }
}
