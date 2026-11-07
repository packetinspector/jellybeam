package tv.jellybeam.ui.search

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.jellybeam.ui.cards.testCard
import uniffi.jellybeam_core.ViewKind
import uniffi.jellybeam_core.ViewSnapshot

/** docs/29 §9 K1-K3: Search's grouping presenter and its no-results line. */
class SearchGroupingTest {

    private fun view(id: String, collectionType: String?) =
        ViewSnapshot(id = id, name = "Library $id", kind = ViewKind.LIBRARY, collectionType = collectionType)

    @Test
    fun `groups come in the fixed order whatever the rank order`() {
        val results = listOf(
            testCard(id = "pl", itemType = "Playlist"),
            testCard(id = "song", itemType = "Audio"),
            testCard(id = "album", itemType = "MusicAlbum"),
            testCard(id = "artist", itemType = "MusicArtist"),
            testCard(id = "ep", itemType = "Episode"),
            testCard(id = "series", itemType = "Series"),
            testCard(id = "film", itemType = "Movie"),
        )

        val groups = groupSearchResults(results)

        assertEquals(
            listOf(
                SearchGroup.Films, SearchGroup.Series, SearchGroup.Episodes,
                SearchGroup.Artists, SearchGroup.Albums, SearchGroup.Songs, SearchGroup.Playlists,
            ),
            groups.map { it.group },
        )
    }

    @Test
    fun `empty groups are absent and cards keep their rank order inside a group`() {
        val results = listOf(
            testCard(id = "s2", itemType = "Audio"),
            testCard(id = "f1", itemType = "Movie"),
            testCard(id = "s1", itemType = "Audio"),
        )

        val groups = groupSearchResults(results)

        assertEquals(listOf(SearchGroup.Films, SearchGroup.Songs), groups.map { it.group })
        assertEquals(listOf("s2", "s1"), groups[1].cards.map { it.id })
    }

    @Test
    fun `a Person hit named like an artist is dropped`() {
        val results = listOf(
            testCard(id = "artist", itemType = "MusicArtist", name = "Neon Harbor"),
            testCard(id = "person", itemType = "Person", name = "Neon Harbor"),
            testCard(id = "other-person", itemType = "Person", name = "Someone Else"),
        )

        val ids = groupSearchResults(results).flatMap { group -> group.cards.map { it.id } }

        assertEquals(listOf("artist", "other-person"), ids)
    }

    @Test
    fun `types the spec does not name stay visible in a last group`() {
        val results = listOf(
            testCard(id = "box", itemType = "BoxSet"),
            testCard(id = "pl", itemType = "Playlist"),
        )

        val groups = groupSearchResults(results)

        assertEquals(listOf(SearchGroup.Playlists, SearchGroup.Other), groups.map { it.group })
        assertEquals(listOf("box"), groups[1].cards.map { it.id })
    }

    @Test
    fun `no results means no groups`() {
        assertEquals(emptyList<SearchResultGroup>(), groupSearchResults(emptyList()))
    }

    @Test
    fun `grid cells count one header per group ahead of its cards`() {
        val groups = groupSearchResults(
            listOf(
                testCard(id = "f1", itemType = "Movie"),
                testCard(id = "f2", itemType = "Movie"),
                testCard(id = "a1", itemType = "MusicAlbum"),
            ),
        )

        assertEquals(5, searchGridCellCount(groups))
        assertEquals(1, searchGridIndexOf(groups, "f1"))
        assertEquals(2, searchGridIndexOf(groups, "f2"))
        assertEquals(4, searchGridIndexOf(groups, "a1"))
        assertEquals(-1, searchGridIndexOf(groups, "missing"))
    }

    @Test
    fun `the searched line names only the kinds the libraries hold, in fixed order`() {
        val views = listOf(
            view("m", "music"),
            view("h", "homevideos"),
            view("t", "tvshows"),
            view("n", null),
        )

        assertEquals(listOf(SearchedKind.Series, SearchedKind.Music), searchedKinds(views))
    }

    @Test
    fun `two libraries of one kind name it once`() {
        val views = listOf(view("a", "movies"), view("b", "movies"), view("c", "music"))

        assertEquals(listOf(SearchedKind.Films, SearchedKind.Music), searchedKinds(views))
    }
}
