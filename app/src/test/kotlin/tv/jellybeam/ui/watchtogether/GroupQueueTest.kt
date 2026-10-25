package tv.jellybeam.ui.watchtogether

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.jellybeam.ui.cards.testCard

class GroupQueueTest {
    private val series = "series-1"
    private fun ep(id: String, virtual: Boolean = false) = testCard(id = id, itemType = "Episode", seriesId = series, isVirtual = virtual)
    private val episodes = listOf(ep("e1"), ep("e2"), ep("e3-missing", virtual = true), ep("e4"))

    @Test
    fun `an episode sends it and every later real episode, as jellyfin-web builds it`() {
        assertEquals(listOf("e2", "e4"), groupQueue("e2", ep("e2"), episodes))
        assertEquals(listOf("e4"), groupQueue("e4", ep("e4"), episodes))
    }

    @Test
    fun `anything else, or an episode the list doesn't place, goes alone`() {
        assertEquals(listOf("m1"), groupQueue("m1", testCard(id = "m1"), episodes))
        assertEquals(listOf("e9"), groupQueue("e9", ep("e9"), episodes))
        assertEquals(listOf("e2"), groupQueue("e2", ep("e2"), emptyList()))
        assertEquals(listOf("e2"), groupQueue("e2", null, episodes))
        assertEquals(listOf("x"), groupQueue("x", testCard(id = "x", itemType = "Episode"), episodes))
    }
}
