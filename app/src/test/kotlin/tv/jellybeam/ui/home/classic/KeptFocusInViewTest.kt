package tv.jellybeam.ui.home.classic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** docs/15 §3: a refresh that keeps focus on a shelf card but moves it out of its row's window. */
class KeptFocusInViewTest {

    @Test
    fun a_card_moved_off_the_row_is_out_of_view() {
        assertEquals("shelf:resume/card:a", keptFocusOutOfView("shelf:resume/card:a") { setOf("b", "c") })
    }

    @Test
    fun a_card_still_laid_out_in_the_row_is_in_view() {
        assertNull(keptFocusOutOfView("shelf:resume/card:a") { setOf("a", "b") })
    }

    @Test
    fun no_focus_a_hero_key_or_an_unknown_shelf_needs_nothing() {
        assertNull(keptFocusOutOfView(null) { emptySet() })
        assertNull(keptFocusOutOfView("hero:resume") { emptySet() })
        assertNull(keptFocusOutOfView("shelf:gone/card:a") { null })
    }
}
