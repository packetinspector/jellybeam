package tv.jellybeam.ui.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.jellybeam.ui.cards.testCard
import uniffi.jellybeam_core.SeerrAvailability
import uniffi.jellybeam_core.SeerrCard
import uniffi.jellybeam_core.SeerrMediaType

class SearchLogicTest {

    private fun seerr(
        tmdbId: Long,
        jellyfinItemId: String? = null,
        mediaType: SeerrMediaType = SeerrMediaType.MOVIE,
        title: String = "t$tmdbId",
        year: Int? = null,
    ) = SeerrCard(
        mediaType = mediaType,
        tmdbId = tmdbId,
        title = title,
        year = year,
        overview = null,
        posterUrl = null,
        backdropUrl = null,
        availability = if (jellyfinItemId == null) SeerrAvailability.NOT_REQUESTED else SeerrAvailability.AVAILABLE,
        jellyfinItemId = jellyfinItemId,
    )

    @Test
    fun `a Seerr title already among the library results is dropped`() {
        val library = listOf(testCard(id = "lib-1"))
        val cards = discoverCardsBesideLibrary(library, listOf(seerr(1, "lib-1"), seerr(2)))
        assertEquals(listOf(2L), cards.map { it.tmdbId })
    }

    @Test
    fun `a Seerr series matching a library episode's series is dropped`() {
        val library = listOf(testCard(id = "ep-1", itemType = "Episode", seriesId = "series-1"))
        val cards = discoverCardsBesideLibrary(library, listOf(seerr(1, "series-1", SeerrMediaType.TV)))
        assertTrue(cards.isEmpty())
    }

    @Test
    fun `an owned title the library results do not show stays, since its page offers Go to library`() {
        val cards = discoverCardsBesideLibrary(emptyList(), listOf(seerr(1, "elsewhere")))
        assertEquals(listOf(1L), cards.map { it.tmdbId })
    }

    @Test
    fun `without a matching id, the same kind, title and year still counts as shown`() {
        val library = listOf(testCard(id = "lib-1", itemType = "Movie", name = "Stub Film", productionYear = 2010))
        val cards = discoverCardsBesideLibrary(library, listOf(seerr(1, "other-server-id", title = " stub film ", year = 2010)))
        assertTrue(cards.isEmpty())
    }

    @Test
    fun `a title match needs the same kind and year`() {
        val library = listOf(testCard(id = "lib-1", itemType = "Movie", name = "Stub Film", productionYear = 2010))
        val cards = discoverCardsBesideLibrary(
            library,
            listOf(
                seerr(1, title = "Stub Film", year = 1987),
                seerr(2, title = "Stub Film", year = 2010, mediaType = SeerrMediaType.TV),
                seerr(3, title = "Stub Film", year = null),
            ),
        )
        assertEquals(listOf(1L, 2L, 3L), cards.map { it.tmdbId })
    }

    @Test
    fun `repeated Seerr cards collapse to the first`() {
        val cards = discoverCardsBesideLibrary(emptyList(), listOf(seerr(1), seerr(2), seerr(1)))
        assertEquals(listOf(1L, 2L), cards.map { it.tmdbId })
    }

    @Test
    fun `nothing left after filtering hides the section`() {
        val library = listOf(testCard(id = "lib-1"))
        assertEquals(DiscoverSection.Hidden, discoverSectionFor(library, listOf(seerr(1, "lib-1"))))
    }

    @Test
    fun `no matches waits for a pending Discover answer`() {
        assertFalse(showNoMatches("q", librarySearching = false, library = emptyList(), discover = DiscoverSection.Searching))
        assertTrue(showNoMatches("q", librarySearching = false, library = emptyList(), discover = DiscoverSection.Hidden))
    }

    @Test
    fun `an unreachable Seerr shows its own line, not no matches`() {
        assertFalse(showNoMatches("q", librarySearching = false, library = emptyList(), discover = DiscoverSection.Unavailable))
    }

    @Test
    fun `library results or a blank query never show no matches`() {
        assertFalse(showNoMatches("q", librarySearching = false, library = listOf(testCard()), discover = DiscoverSection.Hidden))
        assertFalse(showNoMatches("", librarySearching = false, library = emptyList(), discover = DiscoverSection.Hidden))
        assertFalse(showNoMatches("q", librarySearching = true, library = emptyList(), discover = DiscoverSection.Hidden))
    }

    @Test
    fun `Discover waits for two characters, ignoring surrounding spaces`() {
        assertFalse(discoverQueryReady(""))
        assertFalse(discoverQueryReady("a"))
        assertFalse(discoverQueryReady(" a "))
        assertTrue(discoverQueryReady("ab"))
    }

    @Test
    fun `the Discover hint shows only while results sit below the last visible cell`() {
        val results = DiscoverSection.Results(listOf(seerr(1), seerr(2)))
        assertEquals(2, discoverHintCount(results, headerIndex = 40, lastVisibleIndex = 23))
        assertEquals(null, discoverHintCount(results, headerIndex = 40, lastVisibleIndex = 40))
        assertEquals(null, discoverHintCount(DiscoverSection.Searching, headerIndex = 40, lastVisibleIndex = 23))
        assertEquals(null, discoverHintCount(DiscoverSection.Unavailable, headerIndex = 40, lastVisibleIndex = 23))
    }
}
