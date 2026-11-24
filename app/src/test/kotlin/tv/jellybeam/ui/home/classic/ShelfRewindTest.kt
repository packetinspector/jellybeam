package tv.jellybeam.ui.home.classic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** docs/07 §1: which shelves a refresh rewinds to their start. */
class ShelfRewindTest {

    @Test
    fun a_new_head_rewinds_an_unfocused_shelf() {
        assertEquals(listOf("latest:v1"), shelvesToRewind(mapOf("latest:v1" to "a"), mapOf("latest:v1" to "n"), keptShelfId = null))
    }

    @Test
    fun the_shelf_holding_focus_is_never_moved() {
        assertEquals(emptyList<String>(), shelvesToRewind(mapOf("latest:v1" to "a"), mapOf("latest:v1" to "n"), keptShelfId = "latest:v1"))
    }

    @Test
    fun an_unchanged_head_leaves_the_shelf_where_it_is() {
        assertEquals(emptyList<String>(), shelvesToRewind(mapOf("resume" to "a"), mapOf("resume" to "a"), keptShelfId = null))
    }

    @Test
    fun a_reorder_or_removed_head_rewinds_too() {
        val seen = mapOf("resume" to "a", "next-up" to "x")
        val now = mapOf("resume" to "b", "next-up" to null)
        assertEquals(listOf("resume", "next-up"), shelvesToRewind(seen, now, keptShelfId = "latest:v1"))
    }

    @Test
    fun a_first_seen_shelf_is_left_alone() {
        assertEquals(emptyList<String>(), shelvesToRewind(emptyMap(), mapOf("favorites" to "a"), keptShelfId = null))
    }

    @Test
    fun shelf_id_comes_out_of_a_card_key_only() {
        assertEquals("latest:v1", shelfIdOf("shelf:latest:v1/card:c9"))
        assertNull(shelfIdOf("hero:resume"))
        assertNull(shelfIdOf(null))
    }
}
