package tv.jellybeam.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.jellybeam.ui.cards.testCard

class AppendPageTest {
    private fun cards(vararg ids: String) = ids.map { testCard(id = it) }

    @Test
    fun `new ids append after the loaded rows in page order`() {
        val state = LibraryUiState(items = cards("a", "b"))

        val next = appendPage(state, cards("c", "d"), requested = 2)

        assertEquals(listOf("a", "b", "c", "d"), next.items.map { it.id })
    }

    @Test
    fun `ids already loaded are dropped, keeping the loaded row`() {
        val loaded = cards("a", "b")
        val state = LibraryUiState(items = loaded)

        val next = appendPage(state, cards("b", "c"), requested = 2)

        assertEquals(listOf("a", "b", "c"), next.items.map { it.id })
        assertSame(loaded[1], next.items[1])
    }

    @Test
    fun `an id repeated inside the page is appended once`() {
        val next = appendPage(LibraryUiState(items = cards("a")), cards("b", "b", "c"), requested = 3)

        assertEquals(listOf("a", "b", "c"), next.items.map { it.id })
    }

    @Test
    fun `hasMore follows the page size, not what survived the dedupe`() {
        val state = LibraryUiState(items = cards("a", "b"))

        // A full page whose rows were all already loaded: more may still exist.
        assertTrue(appendPage(state, cards("a", "b"), requested = 2).hasMore)
        // A short page ends the list even though every row was new.
        assertFalse(appendPage(state, cards("c"), requested = 2).hasMore)
    }

    @Test
    fun `an empty page ends the list and leaves the rows alone`() {
        val state = LibraryUiState(items = cards("a"), hasMore = true)

        val next = appendPage(state, emptyList(), requested = 2)

        assertEquals(listOf("a"), next.items.map { it.id })
        assertFalse(next.hasMore)
    }
}
