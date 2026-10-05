package tv.jellybeam.ui.discover

import tv.jellybeam.MainDispatcherRule
import tv.jellybeam.i18n.ResourceUiStrings
import tv.jellybeam.data.CoreGateway
import tv.jellybeam.data.FakeCoreGateway
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import uniffi.jellybeam_core.SeerrAvailability
import uniffi.jellybeam_core.SeerrBrowseFilters
import uniffi.jellybeam_core.SeerrBrowseKind
import uniffi.jellybeam_core.SeerrCard
import uniffi.jellybeam_core.SeerrMediaType
import uniffi.jellybeam_core.SeerrPage

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoverGridViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun card(tmdbId: Long, title: String) = SeerrCard(
        mediaType = SeerrMediaType.MOVIE,
        tmdbId = tmdbId,
        title = title,
        year = 2020,
        overview = null,
        posterUrl = null,
        backdropUrl = null,
        availability = SeerrAvailability.NOT_REQUESTED,
        jellyfinItemId = null,
    )

    private fun page(cards: List<SeerrCard>, page: Long, totalPages: Long) =
        SeerrPage(cards = cards, page = page, totalPages = totalPages, totalResults = cards.size.toLong())

    /** A gateway whose browse answers come from [pages]; records every request as sortBy to page. */
    private class BrowseGateway(
        private val pages: suspend (page: Int, filters: SeerrBrowseFilters) -> SeerrPage,
    ) : CoreGateway by FakeCoreGateway() {
        val fetched = mutableListOf<Pair<String?, Int>>()
        val fetchedPages: List<Int> get() = fetched.map { it.second }

        override suspend fun seerrBrowse(kind: SeerrBrowseKind, page: Int, filters: SeerrBrowseFilters): SeerrPage {
            fetched += filters.sortBy to page
            return pages(page, filters)
        }
    }

    private val twoCards = listOf(card(1, "One"), card(2, "Two"))

    /** What DiscoverGridScreen's loader effect does: it is keyed on card count, hasMore and the
     * page cursor, and on every restart re-reports the (unchanged) last visible index. Calls
     * back in until a pass changes none of those. */
    private fun TestScope.driveLikeScreen(viewModel: DiscoverGridViewModel, lastVisibleIndex: Int) {
        while (true) {
            val before = viewModel.state.value
            viewModel.loadNextPageIfNeeded(lastVisibleIndex, columns = 8)
            advanceUntilIdle()
            val after = viewModel.state.value
            if (after.cards.size == before.cards.size && after.hasMore == before.hasMore && after.page == before.page) return
        }
    }

    @Test
    fun `trending never shows sort or filter chips`() {
        assertFalse(SeerrBrowseKind.TRENDING.supportsSortAndFilter())
    }

    @Test
    fun `loadNextPageIfNeeded appends page 2 once the threshold is crossed`() = runTest {
        val gateway = BrowseGateway { p, _ ->
            if (p == 1) page(twoCards, page = 1, totalPages = 2) else page(listOf(card(3, "Three")), page = 2, totalPages = 2)
        }

        val viewModel = DiscoverGridViewModel(gateway, ResourceUiStrings.default, SeerrBrowseKind.MOVIES)
        advanceUntilIdle()
        assertEquals(2, viewModel.state.value.cards.size)
        assertEquals(true, viewModel.state.value.hasMore)

        // threshold = loadedCount - columns*3 is deeply negative here, so any visible index
        // triggers the next page.
        viewModel.loadNextPageIfNeeded(lastVisibleIndex = 1, columns = 8)
        advanceUntilIdle()

        assertEquals(3, viewModel.state.value.cards.size)
        assertEquals(listOf("One", "Two", "Three"), viewModel.state.value.cards.map { it.title })
        assertEquals(false, viewModel.state.value.hasMore)
        assertEquals(2, gateway.fetched.size)
    }

    @Test
    fun `a card page 2 repeats from page 1 is appended once`() = runTest {
        // Seerr's popularity-sorted browse shifts between requests, so page 2 can carry an item
        // page 1 already showed; a repeated grid key would crash the LazyVerticalGrid.
        val gateway = BrowseGateway { p, _ ->
            if (p == 1) page(twoCards, page = 1, totalPages = 2)
            else page(listOf(card(2, "Two again"), card(3, "Three")), page = 2, totalPages = 2)
        }

        val viewModel = DiscoverGridViewModel(gateway, ResourceUiStrings.default, SeerrBrowseKind.MOVIES)
        advanceUntilIdle()
        viewModel.loadNextPageIfNeeded(lastVisibleIndex = 1, columns = 8)
        advanceUntilIdle()

        assertEquals(listOf("One", "Two", "Three"), viewModel.state.value.cards.map { it.title })
    }

    @Test
    fun `the first page's response page number never seeds the cursor`() = runTest {
        // A server mis-echoing page 1 as page 3 must not make the next request page 4.
        val gateway = BrowseGateway { p, _ ->
            if (p == 1) page(twoCards, page = 3, totalPages = 3) else page(listOf(card(3, "Three")), page = 2, totalPages = 3)
        }

        val viewModel = DiscoverGridViewModel(gateway, ResourceUiStrings.default, SeerrBrowseKind.MOVIES)
        advanceUntilIdle()
        assertEquals(1, viewModel.state.value.page)
        assertEquals(true, viewModel.state.value.hasMore)

        viewModel.loadNextPageIfNeeded(lastVisibleIndex = 1, columns = 8)
        advanceUntilIdle()

        assertEquals(listOf(1, 2), gateway.fetchedPages)
        assertEquals(listOf("One", "Two", "Three"), viewModel.state.value.cards.map { it.title })
    }

    @Test
    fun `a page made only of already-shown cards keeps paging to the next unique one`() = runTest {
        // Once de-duplicated, page 2 adds nothing; the page cursor still advances, and the
        // screen's loader effect (keyed on it) calls back in for page 3.
        val gateway = BrowseGateway { p, _ ->
            when (p) {
                1 -> page(twoCards, page = 1, totalPages = 3)
                2 -> page(listOf(card(1, "One again"), card(2, "Two again")), page = 2, totalPages = 3)
                else -> page(listOf(card(3, "Three")), page = 3, totalPages = 3)
            }
        }

        val viewModel = DiscoverGridViewModel(gateway, ResourceUiStrings.default, SeerrBrowseKind.MOVIES)
        advanceUntilIdle()
        driveLikeScreen(viewModel, lastVisibleIndex = 1)

        assertEquals(listOf(1, 2, 3), gateway.fetchedPages)
        assertEquals(listOf("One", "Two", "Three"), viewModel.state.value.cards.map { it.title })
        assertEquals(3, viewModel.state.value.page)
        assertEquals(false, viewModel.state.value.hasMore)
        assertEquals(false, viewModel.state.value.isLoadingMore)
    }

    @Test
    fun `a unique card after four duplicate-only pages still loads with no user scroll`() = runTest {
        // Pages 2-5 repeat page 1; page 6 carries the next unique card. The automatic chain
        // (screen effect keyed on the page cursor) reaches it; hasMore stays the server's word.
        val gateway = BrowseGateway { p, _ ->
            page(if (p == 6) listOf(card(6, "Six")) else twoCards, page = p.toLong(), totalPages = 6)
        }

        val viewModel = DiscoverGridViewModel(gateway, ResourceUiStrings.default, SeerrBrowseKind.MOVIES)
        advanceUntilIdle()
        driveLikeScreen(viewModel, lastVisibleIndex = 1)

        assertEquals(listOf(1, 2, 3, 4, 5, 6), gateway.fetchedPages)
        assertEquals(listOf("One", "Two", "Six"), viewModel.state.value.cards.map { it.title })
        assertEquals(6, viewModel.state.value.page)
        assertEquals(false, viewModel.state.value.hasMore)
    }

    @Test
    fun `eight consecutive duplicate-only pages pause automatic paging until a user scroll`() = runTest {
        // A degraded server echoes page 1 with an inflated totalPages on every request: the
        // cursor still advances, the automatic chain stops after eight no-growth pages with
        // hasMore left as the server said (no false exhaustion), the same trigger index requests
        // nothing more, and a trigger from a different index (a real scroll) re-arms one more
        // bounded run.
        val gateway = BrowseGateway { _, _ -> page(twoCards, page = 1, totalPages = 500) }

        val viewModel = DiscoverGridViewModel(gateway, ResourceUiStrings.default, SeerrBrowseKind.MOVIES)
        advanceUntilIdle()
        driveLikeScreen(viewModel, lastVisibleIndex = 1)

        assertEquals((1..9).toList(), gateway.fetchedPages)
        assertEquals(listOf("One", "Two"), viewModel.state.value.cards.map { it.title })
        assertEquals(9, viewModel.state.value.page)
        assertEquals(true, viewModel.state.value.hasMore)
        assertEquals(false, viewModel.state.value.isLoadingMore)

        driveLikeScreen(viewModel, lastVisibleIndex = 1)
        assertEquals((1..9).toList(), gateway.fetchedPages)

        driveLikeScreen(viewModel, lastVisibleIndex = 0)
        assertEquals((1..17).toList(), gateway.fetchedPages)
        assertEquals(false, viewModel.state.value.isLoadingMore)
    }

    @Test
    fun `a sort change while a next-page request is in flight leaves the new listing able to page`() = runTest {
        // The superseded page-2 request completes after the new page 1; its stale generation
        // must not strand isLoadingMore, or page 2 of the new sort would never load.
        val page2Gate = CompletableDeferred<SeerrPage>()
        val gateway = BrowseGateway { p, filters ->
            when {
                filters.sortBy == "popularity.desc" && p == 2 -> page2Gate.await()
                p == 1 -> page(twoCards, page = 1, totalPages = 2)
                else -> page(listOf(card(3, "Three")), page = 2, totalPages = 2)
            }
        }

        val viewModel = DiscoverGridViewModel(gateway, ResourceUiStrings.default, SeerrBrowseKind.MOVIES)
        advanceUntilIdle()
        viewModel.loadNextPageIfNeeded(lastVisibleIndex = 1, columns = 8)
        advanceUntilIdle()
        assertEquals(true, viewModel.state.value.isLoadingMore)

        viewModel.selectSort(DiscoverSortOption.RATING)
        advanceUntilIdle()
        assertEquals(false, viewModel.state.value.isLoadingMore)

        page2Gate.complete(page(listOf(card(9, "Stale")), page = 2, totalPages = 2))
        advanceUntilIdle()
        assertEquals(false, viewModel.state.value.isLoadingMore)
        assertEquals(listOf("One", "Two"), viewModel.state.value.cards.map { it.title })

        driveLikeScreen(viewModel, lastVisibleIndex = 1)
        assertEquals(listOf("One", "Two", "Three"), viewModel.state.value.cards.map { it.title })
        assertEquals("vote_average.desc" to 2, gateway.fetched.last())
    }

    @Test
    fun `a sort change clears the paused paging budget`() = runTest {
        val gateway = BrowseGateway { _, _ -> page(twoCards, page = 1, totalPages = 500) }

        val viewModel = DiscoverGridViewModel(gateway, ResourceUiStrings.default, SeerrBrowseKind.MOVIES)
        advanceUntilIdle()
        driveLikeScreen(viewModel, lastVisibleIndex = 1)
        val pausedCount = gateway.fetched.size

        viewModel.selectSort(DiscoverSortOption.RATING)
        advanceUntilIdle()
        driveLikeScreen(viewModel, lastVisibleIndex = 1)

        // Page 1 plus a fresh eight-page budget under the new sort.
        assertEquals(pausedCount + 9, gateway.fetched.size)
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9), gateway.fetchedPages.drop(pausedCount))
    }

    @Test
    fun `a backward page number in the response never rewinds the cursor`() = runTest {
        // Every response claims to be page 1; the third distinct request carries the new card.
        val gateway = BrowseGateway { p, _ ->
            page(if (p == 3) listOf(card(3, "Three")) else twoCards, page = 1, totalPages = 3)
        }

        val viewModel = DiscoverGridViewModel(gateway, ResourceUiStrings.default, SeerrBrowseKind.MOVIES)
        advanceUntilIdle()
        driveLikeScreen(viewModel, lastVisibleIndex = 1)

        assertEquals(listOf(1, 2, 3), gateway.fetchedPages)
        assertEquals(listOf("One", "Two", "Three"), viewModel.state.value.cards.map { it.title })
        assertEquals(3, viewModel.state.value.page)
        assertEquals(false, viewModel.state.value.hasMore)
    }

    @Test
    fun `changing sort resets paging back to page 1`() = runTest {
        val gateway = BrowseGateway { _, filters ->
            if (filters.sortBy == DiscoverSortOption.RATING.sortByValue(SeerrMediaType.MOVIE)) {
                page(listOf(card(2, "Top Rated")), page = 1, totalPages = 1)
            } else {
                page(listOf(card(1, "Popular")), page = 1, totalPages = 5)
            }
        }

        val viewModel = DiscoverGridViewModel(gateway, ResourceUiStrings.default, SeerrBrowseKind.MOVIES)
        advanceUntilIdle()
        assertEquals(listOf("Popular"), viewModel.state.value.cards.map { it.title })
        assertEquals(true, viewModel.state.value.hasMore)

        viewModel.selectSort(DiscoverSortOption.RATING)
        advanceUntilIdle()

        assertEquals(listOf("Top Rated"), viewModel.state.value.cards.map { it.title })
        assertEquals(false, viewModel.state.value.hasMore)
        assertEquals(DiscoverSortOption.RATING, viewModel.state.value.sort)
    }

    @Test
    fun `SeerrNotConfigured routes to the notConfigured flag`() = runTest {
        val fake = FakeCoreGateway(seerrBrowseResult = Result.failure(uniffi.jellybeam_core.CoreException.SeerrNotConfigured()))
        val viewModel = DiscoverGridViewModel(fake, ResourceUiStrings.default, SeerrBrowseKind.MOVIES)
        advanceUntilIdle()

        assertEquals(true, viewModel.state.value.notConfigured)
    }
}
