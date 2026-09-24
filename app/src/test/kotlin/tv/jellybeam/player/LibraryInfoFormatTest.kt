package tv.jellybeam.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.jellybeam_core.ItemDetail
import uniffi.jellybeam_core.PersonInfo

class LibraryInfoFormatTest {
    private fun detail() = ItemDetail(
        id = "episode-1",
        name = "The One With Metadata",
        itemType = "Episode",
        seriesName = "Collector Show",
        parentIndexNumber = 2,
        indexNumber = 7,
        premiereDate = "2024-03-06T00:00:00Z",
        playCount = 3,
        lastPlayedDate = "2025-04-07T00:00:00Z",
        genres = listOf("Drama", "Mystery"),
        officialRating = "TV-14",
        communityRating = 8.35f,
        criticRating = 92.0f,
        productionYear = 2024,
        endYear = null,
        status = null,
        studios = listOf("Archive Pictures"),
        overview = "A plot worth cataloging.",
        runTimeTicks = 2_700_000_000L,
        container = "mkv",
        people = listOf(
            PersonInfo(id = "actor", name = "Ada Actor", role = "The Curator", personType = "Actor", primaryImageTag = null),
        ),
        mediaStreams = emptyList(),
        chapters = emptyList(),
        dateCreated = "2024-03-08T00:00:00Z",
        sizeBytes = 7_730_941_132L,
        recursiveItemCount = null,
        childCount = null,
        directors = listOf("Dina Director"),
        writers = listOf("Will Writer"),
    )

    // -- buildSheet (docs/jellybeam-osd-handoff §8a) --------------------------

    @Test
    fun `buildSheet for a full episode joins every meta segment and includes all four fields`() {
        val sheet = LibraryInfoFormat.buildSheet(detail())

        assertEquals(
            listOf(
                StatsSpan("S2 E7"),
                StatsSpan(" · "),
                StatsSpan("Mar 6, 2024"),
                StatsSpan(" · "),
                StatsSpan("5m"),
                StatsSpan(" · "),
                StatsSpan("TV-14"),
                StatsSpan(" · "),
                StatsSpan("8.4", accent = true),
                StatsSpan("/10"),
                StatsSpan(" · "),
                StatsSpan("Drama, Mystery"),
            ),
            sheet.metaSegments,
        )
        assertEquals("A plot worth cataloging.", sheet.synopsis)
        assertEquals("Dina Director", sheet.fields.single { it.label == "Director" }.value)
        assertEquals("Will Writer", sheet.fields.single { it.label == "Writers" }.value)
        assertEquals("3 times · last Apr 7, 2025", sheet.fields.single { it.label == "Watched" }.value)
        assertEquals("Mar 8, 2024", sheet.fields.single { it.label == "Added" }.value)
    }

    @Test
    fun `buildSheet for a movie uses the production year instead of a season-episode segment`() {
        val movie = detail().copy(itemType = "Movie", parentIndexNumber = null, indexNumber = null)
        val sheet = LibraryInfoFormat.buildSheet(movie)
        assertEquals(StatsSpan("2024"), sheet.metaSegments.first())
    }

    @Test
    fun `buildSheet drops every absent meta segment and field with no placeholder`() {
        val sparse = ItemDetail(
            id = "item-1",
            name = "Sparse",
            itemType = "Movie",
            seriesName = null,
            parentIndexNumber = null,
            indexNumber = null,
            premiereDate = null,
            playCount = 0,
            lastPlayedDate = null,
            genres = emptyList(),
            officialRating = null,
            communityRating = null,
            criticRating = null,
            productionYear = null,
            endYear = null,
            status = null,
            studios = emptyList(),
            overview = null,
            runTimeTicks = null,
            container = null,
            people = emptyList(),
            mediaStreams = emptyList(),
            chapters = emptyList(),
            dateCreated = null,
            sizeBytes = null,
            recursiveItemCount = null,
            childCount = null,
            directors = emptyList(),
            writers = emptyList(),
        )
        val sheet = LibraryInfoFormat.buildSheet(sparse)

        assertTrue("no metadata at all resolves to an empty segment list", sheet.metaSegments.isEmpty())
        assertNull(sheet.synopsis)
        assertTrue(sheet.fields.none { it.label == "Director" })
        assertTrue(sheet.fields.none { it.label == "Writers" })
        assertTrue(sheet.fields.none { it.label == "Added" })
        assertEquals("Never", sheet.fields.single { it.label == "Watched" }.value)
    }

    @Test
    fun `buildSheet writers collapses more than two into first-two-plus-count`() {
        val many = detail().copy(writers = listOf("A", "B", "C", "D", "E", "F"))
        val sheet = LibraryInfoFormat.buildSheet(many)
        assertEquals("A, B + 4", sheet.fields.single { it.label == "Writers" }.value)
    }

    @Test
    fun `buildSheet writers joins exactly two with no plus-count`() {
        val two = detail().copy(writers = listOf("A", "B"))
        val sheet = LibraryInfoFormat.buildSheet(two)
        assertEquals("A, B", sheet.fields.single { it.label == "Writers" }.value)
    }

    @Test
    fun `buildSheet Watched reads Never for zero and singular for exactly one`() {
        val never = LibraryInfoFormat.buildSheet(detail().copy(playCount = 0))
        assertEquals("Never", never.fields.single { it.label == "Watched" }.value)

        val once = LibraryInfoFormat.buildSheet(detail().copy(playCount = 1))
        assertEquals("1 time · last Apr 7, 2025", once.fields.single { it.label == "Watched" }.value)
    }
}
