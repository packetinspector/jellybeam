package tv.jellybeam.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.jellybeam.i18n.ResourceUiStrings
import uniffi.jellybeam_core.Decade
import uniffi.jellybeam_core.GridCounts
import uniffi.jellybeam_core.GridFilters
import uniffi.jellybeam_core.GridSort
import uniffi.jellybeam_core.GridSortField
import uniffi.jellybeam_core.StatusFilter
import uniffi.jellybeam_core.WatchedFilter

class GridSummaryFormatTest {
    private val strings = ResourceUiStrings.default


    private val noFilters = GridFilters(
        watched = WatchedFilter.ANY,
        genre = null,
        decade = null,
        status = StatusFilter.ANY,
    )

    // ---- segments: favorites (docs/16 §2.7) ------------------------------

    @Test
    fun `favorites count favorites, and a type filter reads as filtered with its plural`() {
        val counts = GridCounts(filtered = 2uL, total = 7uL)
        val sort = GridSort(GridSortField.NAME, descending = false)

        assertEquals("7 FAVORITES", GridSummaryFormat.segments(strings, counts, sort, noFilters, GridNoun.FAVORITES).first())
        val byType = GridSummaryFormat.segments(strings, counts, sort, noFilters.copy(itemType = "Series"), GridNoun.FAVORITES)
        assertEquals("2 OF 7", byType.first())
        assertEquals("SHOWS", byType.last())
    }

    @Test
    fun `type labels are plural, and an unknown type shows verbatim`() {
        assertEquals("Collections", GridSummaryFormat.itemTypeLabel(strings, "BoxSet"))
        assertEquals("Trailer", GridSummaryFormat.itemTypeLabel(strings, "Trailer"))
    }

    @Test
    fun `a type filter is stale only when set and no longer present`() {
        assertTrue(isStaleItemTypeFilter("Episode", listOf("Movie")))
        assertTrue(isStaleItemTypeFilter("Episode", emptyList()))
        assertFalse(isStaleItemTypeFilter("Movie", listOf("Movie")))
        assertFalse(isStaleItemTypeFilter(null, emptyList()))
    }

    // ---- segments: rest state -------------------------------------------

    @Test
    fun `rest state movies shows total count and NAME ascending, no filter segments`() {
        val segments = GridSummaryFormat.segments(
            strings,
            counts = GridCounts(filtered = 342uL, total = 342uL),
            sort = GridSort(GridSortField.NAME, descending = false),
            filters = noFilters,
            noun = GridNoun.MOVIES,
        )
        assertEquals(listOf("342 MOVIES", "NAME ↑"), segments)
    }

    @Test
    fun `rest state tv shows SHOWS noun instead of MOVIES`() {
        val segments = GridSummaryFormat.segments(
            strings,
            counts = GridCounts(filtered = 120uL, total = 120uL),
            sort = GridSort(GridSortField.NAME, descending = false),
            filters = noFilters,
            noun = GridNoun.SHOWS,
        )
        assertEquals(listOf("120 SHOWS", "NAME ↑"), segments)
    }

    @Test
    fun `a single title takes the singular noun`() {
        val one = GridCounts(filtered = 1uL, total = 1uL)
        val sort = GridSort(GridSortField.NAME, descending = false)
        assertEquals("1 MOVIE", GridSummaryFormat.segments(strings, one, sort, noFilters, noun = GridNoun.MOVIES).first())
        assertEquals("1 SHOW", GridSummaryFormat.segments(strings, one, sort, noFilters, noun = GridNoun.SHOWS).first())
    }

    // ---- segments: filtered state -----------------------------------------

    @Test
    fun `filtered state with every filter active lists all filter segments in strip order`() {
        val filters = GridFilters(
            watched = WatchedFilter.HAS_UNWATCHED,
            genre = "Action",
            decade = Decade.D2010S,
            status = StatusFilter.CONTINUING,
        )
        val segments = GridSummaryFormat.segments(
            strings,
            counts = GridCounts(filtered = 23uL, total = 342uL),
            sort = GridSort(GridSortField.DATE_ADDED, descending = true),
            filters = filters,
            noun = GridNoun.SHOWS,
        )
        assertEquals(
            listOf("23 OF 342", "DATE ADDED ↓", "HAS UNWATCHED", "ACTION", "2010s", "CONTINUING"),
            segments,
        )
    }

    @Test
    fun `has-unwatched watched value renders HAS UNWATCHED`() {
        val filters = noFilters.copy(watched = WatchedFilter.HAS_UNWATCHED)
        val segments = GridSummaryFormat.segments(
            strings,
            counts = GridCounts(filtered = 7uL, total = 342uL),
            sort = GridSort(GridSortField.NAME, descending = false),
            filters = filters,
            noun = GridNoun.SHOWS,
        )
        assertEquals(listOf("7 OF 342", "NAME ↑", "HAS UNWATCHED"), segments)
    }

    @Test
    fun `watched filter alone renders WATCHED and marks the state filtered`() {
        val filters = noFilters.copy(watched = WatchedFilter.WATCHED)
        val segments = GridSummaryFormat.segments(
            strings,
            counts = GridCounts(filtered = 5uL, total = 342uL),
            sort = GridSort(GridSortField.NAME, descending = false),
            filters = filters,
            noun = GridNoun.MOVIES,
        )
        assertEquals(listOf("5 OF 342", "NAME ↑", "WATCHED"), segments)
    }

    @Test
    fun `decade OLDER renders upper-cased, unlike the chip label`() {
        val filters = noFilters.copy(decade = Decade.OLDER)
        val segments = GridSummaryFormat.segments(
            strings,
            counts = GridCounts(filtered = 8uL, total = 342uL),
            sort = GridSort(GridSortField.YEAR, descending = true),
            filters = filters,
            noun = GridNoun.MOVIES,
        )
        assertEquals(listOf("8 OF 342", "YEAR ↓", "OLDER"), segments)
    }

    @Test
    fun `status filter alone renders ENDED`() {
        val filters = noFilters.copy(status = StatusFilter.ENDED)
        val segments = GridSummaryFormat.segments(
            strings,
            counts = GridCounts(filtered = 2uL, total = 342uL),
            sort = GridSort(GridSortField.RUNTIME, descending = true),
            filters = filters,
            noun = GridNoun.SHOWS,
        )
        assertEquals(listOf("2 OF 342", "RUNTIME ↓", "ENDED"), segments)
    }

    @Test
    fun `genre segment is upper-cased verbatim, not re-titled`() {
        val filters = noFilters.copy(genre = "sci-fi & fantasy")
        val segments = GridSummaryFormat.segments(
            strings,
            counts = GridCounts(filtered = 9uL, total = 342uL),
            sort = GridSort(GridSortField.NAME, descending = false),
            filters = filters,
            noun = GridNoun.MOVIES,
        )
        assertTrue(segments.contains("SCI-FI & FANTASY"))
    }

    // ---- sort segment arrows, both directions ------------------------------

    @Test
    fun `sort segment arrow points up when ascending and down when descending`() {
        val ascending = GridSummaryFormat.segments(
            strings,GridCounts(1uL, 1uL), GridSort(GridSortField.YEAR, descending = false), noFilters, noun = GridNoun.MOVIES,
        )
        val descending = GridSummaryFormat.segments(
            strings,GridCounts(1uL, 1uL), GridSort(GridSortField.YEAR, descending = true), noFilters, noun = GridNoun.MOVIES,
        )
        assertEquals("YEAR ↑", ascending[1])
        assertEquals("YEAR ↓", descending[1])
    }

    // ---- sortChipLabel ------------------------------------------------------

    @Test
    fun `sortChipLabel shows the field name with no arrow when it is not the active field`() {
        val sort = GridSort(GridSortField.NAME, descending = false)
        assertEquals("Date added", GridSummaryFormat.sortChipLabel(strings, GridSortField.DATE_ADDED, sort))
        assertEquals("Year", GridSummaryFormat.sortChipLabel(strings, GridSortField.YEAR, sort))
        assertEquals("Runtime", GridSummaryFormat.sortChipLabel(strings, GridSortField.RUNTIME, sort))
    }

    @Test
    fun `sortChipLabel appends the arrow only for the active field, in its current direction`() {
        assertEquals("Name ↑", GridSummaryFormat.sortChipLabel(strings, GridSortField.NAME, GridSort(GridSortField.NAME, false)))
        assertEquals("Name ↓", GridSummaryFormat.sortChipLabel(strings, GridSortField.NAME, GridSort(GridSortField.NAME, true)))
    }

    // ---- decadeLabel (chips) ------------------------------------------------

    @Test
    fun `decadeLabel renders lower-case decade names and Older for chips`() {
        assertEquals("2020s", GridSummaryFormat.decadeLabel(strings, Decade.D2020S))
        assertEquals("2010s", GridSummaryFormat.decadeLabel(strings, Decade.D2010S))
        assertEquals("2000s", GridSummaryFormat.decadeLabel(strings, Decade.D2000S))
        assertEquals("1990s", GridSummaryFormat.decadeLabel(strings, Decade.D1990S))
        assertEquals("1980s", GridSummaryFormat.decadeLabel(strings, Decade.D1980S))
        assertEquals("Older", GridSummaryFormat.decadeLabel(strings, Decade.OLDER))
    }

    // ---- naturalDescending ---------------------------------------------------

    @Test
    fun `naturalDescending is false only for Name`() {
        assertFalse(GridSummaryFormat.naturalDescending(GridSortField.NAME))
        assertTrue(GridSummaryFormat.naturalDescending(GridSortField.DATE_ADDED))
        assertTrue(GridSummaryFormat.naturalDescending(GridSortField.YEAR))
        assertTrue(GridSummaryFormat.naturalDescending(GridSortField.RUNTIME))
    }
}
