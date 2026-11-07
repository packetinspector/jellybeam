package tv.jellybeam.ui.discover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.jellybeam.R
import uniffi.jellybeam_core.SeerrAvailability
import uniffi.jellybeam_core.SeerrActiveRequest
import uniffi.jellybeam_core.SeerrCard
import uniffi.jellybeam_core.SeerrHomeRow
import uniffi.jellybeam_core.SeerrMediaType
import uniffi.jellybeam_core.SeerrRequestStatus
import uniffi.jellybeam_core.SeerrSeasonStatus

/** docs/14-seerr-discover.md's own house "one tested rule, not N inline checks" convention -- see
 * DiscoverLogic.kt's class doc comment for what each function decides.
 */
class DiscoverLogicTest {

    // -- displayRuntimeMinutes -------------------------------------------------

    @Test
    fun `a null runtime is omitted`() {
        assertEquals(null, displayRuntimeMinutes(null))
    }

    @Test
    fun `a zero runtime is omitted, not shown as 0m`() {
        // Zero means "no runtime data" from seerr_movie, not a real 0m runtime.
        assertEquals(null, displayRuntimeMinutes(0))
    }

    @Test
    fun `a negative runtime is omitted`() {
        assertEquals(null, displayRuntimeMinutes(-5))
    }

    @Test
    fun `a positive runtime passes through unchanged`() {
        assertEquals(118, displayRuntimeMinutes(118))
    }

    // -- availabilityBadge ---------------------------------------------------

    @Test
    fun `not requested has no badge`() {
        assertEquals(SeerrAvailabilityBadge.None, availabilityBadge(SeerrAvailability.NOT_REQUESTED))
    }

    @Test
    fun `pending and processing both show a dot`() {
        assertEquals(SeerrAvailabilityBadge.Dot, availabilityBadge(SeerrAvailability.PENDING))
        assertEquals(SeerrAvailabilityBadge.Dot, availabilityBadge(SeerrAvailability.PROCESSING))
    }

    @Test
    fun `partially available shows the half badge`() {
        assertEquals(SeerrAvailabilityBadge.Half, availabilityBadge(SeerrAvailability.PARTIALLY_AVAILABLE))
    }

    @Test
    fun `available shows a check`() {
        assertEquals(SeerrAvailabilityBadge.Check, availabilityBadge(SeerrAvailability.AVAILABLE))
    }

    // -- sortByValue ----------------------------------------------------------

    @Test
    fun `popularity sort is the same value for movies and tv`() {
        assertEquals("popularity.desc", DiscoverSortOption.POPULARITY.sortByValue(SeerrMediaType.MOVIE))
        assertEquals("popularity.desc", DiscoverSortOption.POPULARITY.sortByValue(SeerrMediaType.TV))
    }

    @Test
    fun `release date sort picks the movie vs tv field name`() {
        assertEquals("primary_release_date.desc", DiscoverSortOption.RELEASE_DATE.sortByValue(SeerrMediaType.MOVIE))
        assertEquals("first_air_date.desc", DiscoverSortOption.RELEASE_DATE.sortByValue(SeerrMediaType.TV))
    }

    @Test
    fun `rating sort is the same value for movies and tv`() {
        assertEquals("vote_average.desc", DiscoverSortOption.RATING.sortByValue(SeerrMediaType.MOVIE))
        assertEquals("vote_average.desc", DiscoverSortOption.RATING.sortByValue(SeerrMediaType.TV))
    }

    @Test
    fun `title ascending sort picks the movie vs tv field name`() {
        assertEquals("original_title.asc", DiscoverSortOption.TITLE_ASC.sortByValue(SeerrMediaType.MOVIE))
        assertEquals("name.asc", DiscoverSortOption.TITLE_ASC.sortByValue(SeerrMediaType.TV))
    }

    @Test
    fun `title descending sort picks the movie vs tv field name`() {
        assertEquals("original_title.desc", DiscoverSortOption.TITLE_DESC.sortByValue(SeerrMediaType.MOVIE))
        assertEquals("name.desc", DiscoverSortOption.TITLE_DESC.sortByValue(SeerrMediaType.TV))
    }

    // -- shouldFetchNextDiscoverPage -------------------------------------------

    @Test
    fun `does not fetch when there is no more to load`() {
        assertFalse(shouldFetchNextDiscoverPage(lastVisibleIndex = 19, loadedCount = 20, columns = 8, hasMore = false))
    }

    @Test
    fun `does not fetch while scrolled well above the last three rows`() {
        assertFalse(shouldFetchNextDiscoverPage(lastVisibleIndex = 5, loadedCount = 40, columns = 8, hasMore = true))
    }

    @Test
    fun `fetches once scroll enters the last three loaded rows`() {
        // 40 loaded, 8 columns -> last three rows start at index 40 - 24 = 16.
        assertTrue(shouldFetchNextDiscoverPage(lastVisibleIndex = 16, loadedCount = 40, columns = 8, hasMore = true))
        assertTrue(shouldFetchNextDiscoverPage(lastVisibleIndex = 39, loadedCount = 40, columns = 8, hasMore = true))
    }

    @Test
    fun `never fetches with nothing loaded yet`() {
        assertFalse(shouldFetchNextDiscoverPage(lastVisibleIndex = 0, loadedCount = 0, columns = 8, hasMore = true))
    }

    // -- distinctSeerrCards --------------------------------------------------

    private fun card(mediaType: SeerrMediaType, tmdbId: Long, title: String = "t$tmdbId") = SeerrCard(
        mediaType = mediaType,
        tmdbId = tmdbId,
        title = title,
        year = null,
        overview = null,
        posterUrl = null,
        backdropUrl = null,
        availability = SeerrAvailability.NOT_REQUESTED,
        jellyfinItemId = null,
    )

    @Test
    fun `a card repeated later in the list is dropped, keeping the first and the order`() {
        val cards = listOf(
            card(SeerrMediaType.MOVIE, 1, "first"),
            card(SeerrMediaType.MOVIE, 2),
            card(SeerrMediaType.MOVIE, 1, "repeat"),
            card(SeerrMediaType.MOVIE, 3),
        )
        assertEquals(listOf("first", "t2", "t3"), distinctSeerrCards(cards).map { it.title })
    }

    @Test
    fun `a movie and a series sharing a TMDB id are distinct cards`() {
        val cards = listOf(card(SeerrMediaType.MOVIE, 7), card(SeerrMediaType.TV, 7))
        assertEquals(2, distinctSeerrCards(cards).size)
    }

    @Test
    fun `an already distinct list is returned unchanged`() {
        val cards = listOf(card(SeerrMediaType.MOVIE, 1), card(SeerrMediaType.TV, 2))
        assertEquals(cards, distinctSeerrCards(cards))
    }

    // -- Season request picker --------------------------------------------------

    private fun season(number: Int, requestable: Boolean, availability: SeerrAvailability = SeerrAvailability.NOT_REQUESTED) =
        SeerrSeasonStatus(seasonNumber = number, name = "Season $number", episodeCount = 10, availability = availability, requestable = requestable)

    @Test
    fun `initial season selection starts every requestable season unselected`() {
        val seasons = listOf(season(1, requestable = true), season(2, requestable = false))
        val selection = initialSeasonSelection(seasons)

        assertEquals(mapOf(1 to false), selection)
    }

    @Test
    fun `toggling a requestable season flips only that season`() {
        val seasons = listOf(season(1, requestable = true), season(2, requestable = true))
        val selection = toggleSeasonSelection(initialSeasonSelection(seasons), seasons[0])

        assertEquals(mapOf(1 to true, 2 to false), selection)
    }

    @Test
    fun `toggling a non-requestable season is a no-op`() {
        val seasons = listOf(season(1, requestable = false))
        val selection = toggleSeasonSelection(initialSeasonSelection(seasons), seasons[0])

        assertEquals(emptyMap<Int, Boolean>(), selection)
    }

    @Test
    fun `select-all selects every requestable season, never a non-requestable one`() {
        val seasons = listOf(season(1, requestable = true), season(2, requestable = true), season(3, requestable = false))
        val selection = toggleSelectAllRequestableSeasons(seasons, initialSeasonSelection(seasons))

        assertEquals(mapOf(1 to true, 2 to true), selection)
    }

    @Test
    fun `select-all toggles back off once every requestable season is already selected`() {
        val seasons = listOf(season(1, requestable = true), season(2, requestable = true))
        val allSelected = seasons.associate { it.seasonNumber to true }

        val selection = toggleSelectAllRequestableSeasons(seasons, allSelected)

        assertEquals(mapOf(1 to false, 2 to false), selection)
    }

    @Test
    fun `selected requestable seasons excludes non-requestable ones even if present in the map`() {
        val seasons = listOf(season(1, requestable = true), season(2, requestable = false))
        // Non-requestable seasons are excluded even if the map marks them true.
        val selection = mapOf(1 to true, 2 to true)

        assertEquals(listOf(1), selectedRequestableSeasons(seasons, selection))
    }

    @Test
    fun `submit is disabled until at least one requestable season is selected`() {
        val seasons = listOf(season(1, requestable = true), season(2, requestable = true))
        assertFalse(canSubmitSeasonRequest(seasons, initialSeasonSelection(seasons)))

        val withOneSelected = toggleSeasonSelection(initialSeasonSelection(seasons), seasons[0])
        assertTrue(canSubmitSeasonRequest(seasons, withOneSelected))
    }

    @Test
    fun `submit stays disabled when every season is already covered (nothing requestable)`() {
        val seasons = listOf(season(1, requestable = false), season(2, requestable = false))
        assertFalse(canSubmitSeasonRequest(seasons, initialSeasonSelection(seasons)))
    }

    // -- SeerrBrowseKind helpers (DiscoverGridViewModel.kt) --------------------

    @Test
    fun `only movies and tv browse kinds support sort and filter`() {
        assertTrue(uniffi.jellybeam_core.SeerrBrowseKind.MOVIES.supportsSortAndFilter())
        assertTrue(uniffi.jellybeam_core.SeerrBrowseKind.TV.supportsSortAndFilter())
        assertFalse(uniffi.jellybeam_core.SeerrBrowseKind.TRENDING.supportsSortAndFilter())
        assertFalse(uniffi.jellybeam_core.SeerrBrowseKind.UPCOMING_MOVIES.supportsSortAndFilter())
        assertFalse(uniffi.jellybeam_core.SeerrBrowseKind.UPCOMING_TV.supportsSortAndFilter())
    }

    @Test
    fun `browse kind maps onto its media type only for movies and tv`() {
        assertEquals(SeerrMediaType.MOVIE, uniffi.jellybeam_core.SeerrBrowseKind.MOVIES.toMediaType())
        assertEquals(SeerrMediaType.TV, uniffi.jellybeam_core.SeerrBrowseKind.TV.toMediaType())
        assertEquals(null, uniffi.jellybeam_core.SeerrBrowseKind.TRENDING.toMediaType())
    }

    @Test
    fun `seerrCardKey combines media type and tmdb id`() {
        assertEquals("MOVIE-42", seerrCardKey(SeerrMediaType.MOVIE, 42L))
        assertEquals("TV-7", seerrCardKey(SeerrMediaType.TV, 7L))
    }

    // -- Watchlist shelf composition (DiscoverViewModel.kt) -----------------------

    private fun homeRow(id: String, vararg cards: SeerrCard) = SeerrHomeRow(id = id, title = "Row $id", cards = cards.toList())

    @Test
    fun `a non-empty watchlist is the first shelf, with its own id and title, ahead of the home rows`() {
        val rows = discoverRows(
            watchlist = listOf(card(SeerrMediaType.MOVIE, 1L)),
            watchlistTitle = "Watchlist",
            home = listOf(homeRow("trending"), homeRow("movies")),
        )

        assertEquals(listOf(WATCHLIST_ROW_ID, "trending", "movies"), rows.map { it.id })
        assertEquals("watchlist", rows.first().id)
        assertEquals("Watchlist", rows.first().title)
    }

    @Test
    fun `an empty watchlist leaves no shelf and the home rows untouched`() {
        val home = listOf(homeRow("trending"), homeRow("movies"))

        assertEquals(home, discoverRows(emptyList(), "Watchlist", home))
    }

    @Test
    fun `watchlist and home cards are de-duplicated within their own shelf`() {
        val dup = card(SeerrMediaType.MOVIE, 1L)
        val rows = discoverRows(listOf(dup, dup), "Watchlist", listOf(homeRow("trending", dup, dup)))

        assertEquals(listOf(dup), rows[0].cards)
        assertEquals(listOf(dup), rows[1].cards)
    }

    // -- Refresh refocus (discoverRefocusKey) -----------------------------------------

    private fun key(rowId: String, id: Long) = discoverCardFocusKey(rowId, card(SeerrMediaType.MOVIE, id))

    private val shelves = listOf(
        homeRow("watchlist", card(SeerrMediaType.MOVIE, 1L), card(SeerrMediaType.MOVIE, 2L), card(SeerrMediaType.MOVIE, 3L)),
        homeRow("trending", card(SeerrMediaType.MOVIE, 10L), card(SeerrMediaType.MOVIE, 11L)),
    )

    @Test
    fun `a removed card refocuses the card nearest the viewport start in its own shelf`() {
        val removed = key("watchlist", 2L)
        val rowsAfter = listOf(homeRow("watchlist", card(SeerrMediaType.MOVIE, 1L), card(SeerrMediaType.MOVIE, 3L))) + shelves.drop(1)

        assertEquals(key("watchlist", 3L), discoverRefocusKey(rowsAfter, removed) { 1 })
        assertEquals(key("watchlist", 1L), discoverRefocusKey(rowsAfter, removed) { 0 })
    }

    @Test
    fun `a first visible index past the shorter shelf clamps to its last card`() {
        assertEquals(key("trending", 11L), discoverRefocusKey(shelves, key("trending", 99L)) { 7 })
    }

    @Test
    fun `a vanished shelf refocuses the first shelf that still has cards`() {
        val rowsAfter = listOf(homeRow("watchlist"), homeRow("trending", card(SeerrMediaType.MOVIE, 10L), card(SeerrMediaType.MOVIE, 11L)))

        assertEquals(key("trending", 10L), discoverRefocusKey(rowsAfter, key("watchlist", 1L)) { 0 })
        assertEquals(key("trending", 10L), discoverRefocusKey(shelves.drop(1), key("watchlist", 1L)) { 0 })
    }

    @Test
    fun `no cards anywhere, a chip key or a fresh entry has no card target so focus goes to the first chip`() {
        assertEquals(null, discoverRefocusKey(emptyList(), key("watchlist", 1L)) { 0 })
        assertEquals(null, discoverRefocusKey(shelves, "chip:tv") { 0 })
        assertEquals(null, discoverRefocusKey(shelves, null) { 0 })
    }

    // -- Watchlist pill (actionSlots) -------------------------------------------------

    private val activeRequest = SeerrActiveRequest(requestId = 7L, status = SeerrRequestStatus.PENDING, is4k = false, seasons = emptyList())

    @Test
    fun `the watchlist pill is absent when the watchlist is unavailable`() {
        val state = DiscoverDetailUiState(isLoading = false, canRequest = true, onWatchlist = null)

        assertFalse(state.actionSlots().any { it.id == ACTION_WATCHLIST })
    }

    @Test
    fun `the watchlist pill is present for either known state and labelled by it`() {
        for (on in listOf(false, true)) {
            val state = DiscoverDetailUiState(isLoading = false, onWatchlist = on)

            assertTrue("on=$on", state.actionSlots().any { it.id == ACTION_WATCHLIST })
        }
        assertEquals(R.string.discover_action_add_to_watchlist, watchlistActionLabel(false))
        assertEquals(R.string.discover_action_on_watchlist, watchlistActionLabel(true))
    }

    @Test
    fun `the watchlist pill stays focusable but inert while a mutation is in flight`() {
        val idle = DiscoverDetailUiState(isLoading = false, onWatchlist = false)
        val held = idle.copy(busy = true)

        assertTrue(idle.actionSlots().single { it.id == ACTION_WATCHLIST }.actionable)
        assertFalse(held.actionSlots().single { it.id == ACTION_WATCHLIST }.actionable)
        assertEquals(idle.actionKeys(), held.actionKeys())
    }

    @Test
    fun `the watchlist pill follows the request family and precedes the trailer`() {
        val state = DiscoverDetailUiState(
            isLoading = false,
            card = card(SeerrMediaType.MOVIE, 1L).copy(jellyfinItemId = "item"),
            activeRequest = activeRequest,
            canRequest = true,
            canRequest4k = true,
            trailerUrl = "http://trailer.test",
            onWatchlist = true,
        )

        assertEquals(
            listOf(ACTION_GO_TO_LIBRARY, ACTION_CANCEL_REQUEST, ACTION_REQUEST, ACTION_REQUEST_4K, ACTION_WATCHLIST, ACTION_TRAILER),
            state.actionSlots().map { it.id },
        )
    }

    @Test
    fun `the watchlist is never the primary pill`() {
        val state = DiscoverDetailUiState(isLoading = false, onWatchlist = false)

        assertFalse(state.actionSlots().single().primary)
    }

    @Test
    fun `a toggle targets the opposite state, and nothing when the watchlist is unavailable`() {
        assertEquals(true, DiscoverDetailUiState(onWatchlist = false).watchlistToggleTarget())
        assertEquals(false, DiscoverDetailUiState(onWatchlist = true).watchlistToggleTarget())
        assertEquals(null, DiscoverDetailUiState(onWatchlist = null).watchlistToggleTarget())
    }
}
