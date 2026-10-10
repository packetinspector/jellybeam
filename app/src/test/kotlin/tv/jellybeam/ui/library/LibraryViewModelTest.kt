package tv.jellybeam.ui.library

import androidx.lifecycle.ViewModelStore
import tv.jellybeam.MainDispatcherRule
import tv.jellybeam.data.CoreGateway
import tv.jellybeam.data.FakeCoreGateway
import tv.jellybeam.ui.cards.testCard
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uniffi.jellybeam_core.ChangeEvent
import uniffi.jellybeam_core.Decade
import uniffi.jellybeam_core.GridCounts
import uniffi.jellybeam_core.GridFilters
import uniffi.jellybeam_core.GridGroup
import uniffi.jellybeam_core.GridSort
import uniffi.jellybeam_core.GridSortField
import uniffi.jellybeam_core.LibraryGridPrefs
import uniffi.jellybeam_core.LiveSort
import uniffi.jellybeam_core.StatusFilter
import uniffi.jellybeam_core.ViewKind
import uniffi.jellybeam_core.ViewSnapshot
import uniffi.jellybeam_core.WatchedFilter

private fun noFilters(): GridFilters = GridFilters(
    watched = WatchedFilter.ANY,
    genre = null,
    decade = null,
    status = StatusFilter.ANY,
)

private fun nameAsc(): GridSort = GridSort(GridSortField.NAME, descending = false)

class LibraryViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /**
     * docs/16 §4.6, docs/17 §6: [LibraryViewModel.changeRefreshScheduler]'s `while(true)` loop
     * (non-live views only) must be cancelled before `runTest` ends or its cleanup phase hangs
     * advancing virtual time forever; use `runCurrent()`, never `advanceUntilIdle()`, mid-test.
     * CHANNEL/CHANNEL_FOLDER views never create the scheduler, so those tests build the
     * ViewModel directly, unwrapped.
     */
    private inline fun withLibraryViewModel(
        gateway: CoreGateway,
        view: ViewSnapshot,
        block: (LibraryViewModel) -> Unit,
    ) {
        val viewModel = LibraryViewModel(gateway, view)
        try {
            block(viewModel)
        } finally {
            val store = ViewModelStore()
            store.put("library", viewModel)
            store.clear()
        }
    }

    @Test
    fun `loads the first bounded page with default sort and filters`() = runTest {
        val view = ViewSnapshot(id = "view-1", name = "Movies", kind = ViewKind.LIBRARY)
        val items = listOf(testCard(id = "b", name = "Beta"), testCard(id = "a", name = "Alpha"))
        val gateway = FakeCoreGateway(libraryGridByView = mapOf("view-1" to items))

        withLibraryViewModel(gateway, view) { viewModel ->
            assertEquals(items, viewModel.state.value.items)
            assertFalse(viewModel.state.value.isLoading)

            val call = gateway.libraryGridCalls.single()
            assertEquals("view-1", call.viewId)
            assertEquals(nameAsc(), call.sort)
            assertEquals(noFilters(), call.filters)
            assertEquals(0u, call.offset)
            assertEquals(200u, call.limit)
        }
    }

    @Test
    fun `prefs are loaded before the first query and used in it`() = runTest {
        val view = ViewSnapshot(id = "view-1", name = "Movies", kind = ViewKind.LIBRARY)
        val savedSort = GridSort(GridSortField.YEAR, descending = true)
        val savedFilters = noFilters().copy(watched = WatchedFilter.UNWATCHED)
        val gateway = FakeCoreGateway(
            libraryGridByView = mapOf("view-1" to emptyList()),
            libraryGridPrefsByView = mutableMapOf("view-1" to LibraryGridPrefs(savedSort, savedFilters)),
        )

        withLibraryViewModel(gateway, view) { viewModel ->
            assertTrue(viewModel.state.value.prefsLoaded)
            assertEquals(savedSort, viewModel.state.value.sort)
            assertEquals(savedFilters, viewModel.state.value.filters)

            val call = gateway.libraryGridCalls.single()
            assertEquals(savedSort, call.sort)
            assertEquals(savedFilters, call.filters)
        }
    }

    @Test
    fun `the favorites page loads its type options and filters by type`() = runTest {
        val view = ViewSnapshot(id = "favorites", name = "Favorites", kind = ViewKind.LIBRARY, collectionType = "favorites")
        val gateway = FakeCoreGateway(libraryGridByView = mapOf("favorites" to listOf(testCard(id = "a"))))
        gateway.favoriteItemTypesResult = listOf("Movie", "Episode")

        withLibraryViewModel(gateway, view) { viewModel ->
            assertEquals(listOf("Movie", "Episode"), viewModel.state.value.itemTypes)

            viewModel.setItemType("Episode")
            runCurrent()

            assertEquals("Episode", viewModel.state.value.filters.itemType)
        }
    }

    @Test
    fun `a saved type filter no favorite has any more is cleared and saved`() = runTest {
        val view = ViewSnapshot(id = "favorites", name = "Favorites", kind = ViewKind.LIBRARY, collectionType = "favorites")
        val gateway = FakeCoreGateway(
            libraryGridByView = mapOf("favorites" to listOf(testCard(id = "a"))),
            libraryGridPrefsByView = mutableMapOf("favorites" to LibraryGridPrefs(nameAsc(), noFilters().copy(itemType = "Episode"))),
        )
        gateway.favoriteItemTypesResult = listOf("Movie")

        withLibraryViewModel(gateway, view) { viewModel ->
            runCurrent()

            assertEquals(null, viewModel.state.value.filters.itemType)
            assertEquals(null, gateway.getLibraryGridPrefs("favorites").filters.itemType)
        }
    }

    @Test
    fun `a library view never asks for favorite types`() = runTest {
        val view = ViewSnapshot(id = "view-1", name = "Movies", kind = ViewKind.LIBRARY, collectionType = "movies")
        val gateway = FakeCoreGateway(libraryGridByView = mapOf("view-1" to listOf(testCard(id = "a"))))
        gateway.favoriteItemTypesResult = listOf("Movie")

        withLibraryViewModel(gateway, view) { viewModel ->
            assertEquals(emptyList<String>(), viewModel.state.value.itemTypes)
        }
    }

    @Test
    fun `the first load also fetches counts groups and genres`() = runTest {
        val view = ViewSnapshot(id = "view-1", name = "Movies", kind = ViewKind.LIBRARY)
        val counts = GridCounts(filtered = 3uL, total = 5uL)
        val groups = listOf(GridGroup(key = "A", count = 3uL))
        val genres = listOf("Action", "Comedy")
        val gateway = FakeCoreGateway(
            libraryGridByView = mapOf("view-1" to listOf(testCard(id = "a"))),
            libraryGridCountsByView = mapOf("view-1" to counts),
            libraryGridGroupsByView = mapOf("view-1" to groups),
            libraryGenresByView = mapOf("view-1" to genres),
        )

        withLibraryViewModel(gateway, view) { viewModel ->
            assertEquals(counts, viewModel.state.value.counts)
            assertEquals(groups, viewModel.state.value.groups)
            assertEquals(genres, viewModel.state.value.genres)
            assertEquals(1, gateway.libraryGenresCalls.size)
        }
    }

    @Test
    fun `counts default to filtered equal to total equal to the list size`() = runTest {
        val view = ViewSnapshot(id = "view-1", name = "Movies", kind = ViewKind.LIBRARY)
        val items = listOf(testCard(id = "a"), testCard(id = "b"))
        val gateway = FakeCoreGateway(libraryGridByView = mapOf("view-1" to items))

        withLibraryViewModel(gateway, view) { viewModel ->
            assertEquals(GridCounts(filtered = 2uL, total = 2uL), viewModel.state.value.counts)
        }
    }

    // ---- docs/16 §4.6: a null query result fails soft --------------------

    @Test
    fun `a refresh that returns null keeps the last good items counts groups and hasMore`() = runTest {
        val view = ViewSnapshot(id = "view-1", name = "Movies", kind = ViewKind.LIBRARY)
        val items = listOf(testCard(id = "a", name = "Alpha"))
        val counts = GridCounts(filtered = 1uL, total = 1uL)
        val groups = listOf(GridGroup(key = "A", count = 1uL))
        val fake = FakeCoreGateway(
            libraryGridByView = mapOf("view-1" to items),
            libraryGridCountsByView = mapOf("view-1" to counts),
            libraryGridGroupsByView = mapOf("view-1" to groups),
        )
        val changes = MutableSharedFlow<ChangeEvent>(extraBufferCapacity = 4)
        val gateway = object : CoreGateway by fake {
            override fun changeEvents(): Flow<ChangeEvent> = changes
        }

        withLibraryViewModel(gateway, view) { viewModel ->
            assertEquals(items, viewModel.state.value.items)
            assertEquals(counts, viewModel.state.value.counts)
            assertEquals(groups, viewModel.state.value.groups)
            val hasMoreBefore = viewModel.state.value.hasMore

            fake.libraryGridFails = true
            changes.tryEmit(ChangeEvent.Refresh)
            runCurrent()

            assertEquals(items, viewModel.state.value.items)
            assertEquals(counts, viewModel.state.value.counts)
            assertEquals(groups, viewModel.state.value.groups)
            assertEquals(hasMoreBefore, viewModel.state.value.hasMore)
            assertFalse(viewModel.state.value.isLoading)
        }
    }

    @Test
    fun `a refresh that returns a genuinely empty list still clears the grid`() = runTest {
        // docs/16 §4.6: emptyList() (Some(vec![])) is a real result, not a failure -- replaces the
        // prior page.
        val view = ViewSnapshot(id = "view-1", name = "Movies", kind = ViewKind.LIBRARY)
        val items = listOf(testCard(id = "a", name = "Alpha"))
        val fake = FakeCoreGateway(libraryGridByView = mapOf("view-1" to items))
        val changes = MutableSharedFlow<ChangeEvent>(extraBufferCapacity = 4)
        val gateway = object : CoreGateway by fake {
            override fun changeEvents(): Flow<ChangeEvent> = changes
        }

        withLibraryViewModel(gateway, view) { viewModel ->
            assertEquals(items, viewModel.state.value.items)

            fake.libraryGridByView = mapOf("view-1" to emptyList())
            changes.tryEmit(ChangeEvent.Refresh)
            runCurrent()

            assertEquals(emptyList<Any>(), viewModel.state.value.items)
            assertFalse(viewModel.state.value.hasMore)
        }
    }

    @Test
    fun `a sort intent that fails at the gateway keeps prior items counts and groups but still applies the new sort`() = runTest {
        // Pins: requery() (sort/filter path) shares refresh()'s null-retention behavior (docs/16
        // §4.6).
        val view = ViewSnapshot(id = "view-1", name = "Movies", kind = ViewKind.LIBRARY)
        val items = listOf(testCard(id = "a", name = "Alpha"))
        val counts = GridCounts(filtered = 1uL, total = 1uL)
        val groups = listOf(GridGroup(key = "A", count = 1uL))
        val gateway = FakeCoreGateway(
            libraryGridByView = mapOf("view-1" to items),
            libraryGridCountsByView = mapOf("view-1" to counts),
            libraryGridGroupsByView = mapOf("view-1" to groups),
        )

        withLibraryViewModel(gateway, view) { viewModel ->
            assertEquals(items, viewModel.state.value.items)

            gateway.libraryGridFails = true
            viewModel.setSort(GridSortField.YEAR)
            runCurrent()

            // Sort applies synchronously in applyIntent, before the failed requery() runs.
            assertEquals(GridSort(GridSortField.YEAR, descending = true), viewModel.state.value.sort)
            assertEquals(items, viewModel.state.value.items)
            assertEquals(counts, viewModel.state.value.counts)
            assertEquals(groups, viewModel.state.value.groups)
        }
    }

    // ---- setSort ------------------------------------------------------------

    @Test
    fun `setSort on an inactive field activates it in its natural direction`() = runTest {
        val view = ViewSnapshot(id = "view-1", name = "Movies", kind = ViewKind.LIBRARY)
        val gateway = FakeCoreGateway(libraryGridByView = mapOf("view-1" to emptyList()))

        withLibraryViewModel(gateway, view) { viewModel ->
            viewModel.setSort(GridSortField.YEAR)
            runCurrent()

            // Year's natural direction is descending (GridSummaryFormat.naturalDescending).
            assertEquals(GridSort(GridSortField.YEAR, descending = true), viewModel.state.value.sort)
        }
    }

    @Test
    fun `setSort on the already-active field flips its direction`() = runTest {
        val view = ViewSnapshot(id = "view-1", name = "Movies", kind = ViewKind.LIBRARY)
        val gateway = FakeCoreGateway(libraryGridByView = mapOf("view-1" to emptyList()))

        withLibraryViewModel(gateway, view) { viewModel ->
            viewModel.setSort(GridSortField.NAME) // already active (Name asc is the default)
            runCurrent()
            assertEquals(GridSort(GridSortField.NAME, descending = true), viewModel.state.value.sort)

            viewModel.setSort(GridSortField.NAME)
            runCurrent()
            assertEquals(GridSort(GridSortField.NAME, descending = false), viewModel.state.value.sort)
        }
    }

    // ---- cycleWatched ---------------------------------------------------------

    @Test
    fun `cycleWatched on a Movies library wraps Any to Unwatched to Watched to Any, skipping Has unwatched`() = runTest {
        val view = ViewSnapshot(id = "view-1", name = "Movies", kind = ViewKind.LIBRARY, collectionType = "movies")
        val gateway = FakeCoreGateway(libraryGridByView = mapOf("view-1" to emptyList()))

        withLibraryViewModel(gateway, view) { viewModel ->
            viewModel.cycleWatched()
            runCurrent()
            assertEquals(WatchedFilter.UNWATCHED, viewModel.state.value.filters.watched)

            viewModel.cycleWatched()
            runCurrent()
            assertEquals(WatchedFilter.WATCHED, viewModel.state.value.filters.watched)

            viewModel.cycleWatched()
            runCurrent()
            assertEquals(WatchedFilter.ANY, viewModel.state.value.filters.watched)
        }
    }

    @Test
    fun `cycleWatched on a TV library wraps Any to Unwatched to Has unwatched to Watched to Any`() = runTest {
        val view = ViewSnapshot(id = "view-1", name = "Shows", kind = ViewKind.LIBRARY, collectionType = "tvshows")
        val gateway = FakeCoreGateway(libraryGridByView = mapOf("view-1" to emptyList()))

        withLibraryViewModel(gateway, view) { viewModel ->
            viewModel.cycleWatched()
            runCurrent()
            assertEquals(WatchedFilter.UNWATCHED, viewModel.state.value.filters.watched)

            viewModel.cycleWatched()
            runCurrent()
            assertEquals(WatchedFilter.HAS_UNWATCHED, viewModel.state.value.filters.watched)

            viewModel.cycleWatched()
            runCurrent()
            assertEquals(WatchedFilter.WATCHED, viewModel.state.value.filters.watched)

            viewModel.cycleWatched()
            runCurrent()
            assertEquals(WatchedFilter.ANY, viewModel.state.value.filters.watched)
        }
    }

    // ---- setGenre / setDecade / cycleStatus ---------------------------------

    @Test
    fun `setGenre sets and clears the genre filter`() = runTest {
        val view = ViewSnapshot(id = "view-1", name = "Movies", kind = ViewKind.LIBRARY)
        val gateway = FakeCoreGateway(libraryGridByView = mapOf("view-1" to emptyList()))

        withLibraryViewModel(gateway, view) { viewModel ->
            viewModel.setGenre("Comedy")
            runCurrent()
            assertEquals("Comedy", viewModel.state.value.filters.genre)

            viewModel.setGenre(null)
            runCurrent()
            assertNull(viewModel.state.value.filters.genre)
        }
    }

    @Test
    fun `setDecade sets and clears the decade filter`() = runTest {
        val view = ViewSnapshot(id = "view-1", name = "Movies", kind = ViewKind.LIBRARY)
        val gateway = FakeCoreGateway(libraryGridByView = mapOf("view-1" to emptyList()))

        withLibraryViewModel(gateway, view) { viewModel ->
            viewModel.setDecade(Decade.D1990S)
            runCurrent()
            assertEquals(Decade.D1990S, viewModel.state.value.filters.decade)

            viewModel.setDecade(null)
            runCurrent()
            assertNull(viewModel.state.value.filters.decade)
        }
    }

    @Test
    fun `cycleStatus wraps Any to Continuing to Ended to Any`() = runTest {
        val view = ViewSnapshot(id = "view-1", name = "Shows", kind = ViewKind.LIBRARY)
        val gateway = FakeCoreGateway(libraryGridByView = mapOf("view-1" to emptyList()))

        withLibraryViewModel(gateway, view) { viewModel ->
            viewModel.cycleStatus()
            runCurrent()
            assertEquals(StatusFilter.CONTINUING, viewModel.state.value.filters.status)

            viewModel.cycleStatus()
            runCurrent()
            assertEquals(StatusFilter.ENDED, viewModel.state.value.filters.status)

            viewModel.cycleStatus()
            runCurrent()
            assertEquals(StatusFilter.ANY, viewModel.state.value.filters.status)
        }
    }

    // ---- resetSortAndFilters -------------------------------------------------

    @Test
    fun `resetSortAndFilters restores defaults and persists them`() = runTest {
        val view = ViewSnapshot(id = "view-1", name = "Movies", kind = ViewKind.LIBRARY)
        val gateway = FakeCoreGateway(libraryGridByView = mapOf("view-1" to emptyList()))

        withLibraryViewModel(gateway, view) { viewModel ->
            viewModel.setSort(GridSortField.YEAR)
            runCurrent()
            viewModel.cycleWatched()
            runCurrent()
            viewModel.setGenre("Comedy")
            runCurrent()

            viewModel.resetSortAndFilters()
            runCurrent()

            assertEquals(nameAsc(), viewModel.state.value.sort)
            assertEquals(noFilters(), viewModel.state.value.filters)

            val lastPersisted = gateway.setLibraryGridPrefsCalls.last()
            assertEquals("view-1", lastPersisted.viewId)
            assertEquals(nameAsc(), lastPersisted.prefs.sort)
            assertEquals(noFilters(), lastPersisted.prefs.filters)
        }
    }

    // ---- every intent persists exactly once and re-queries from offset 0 -----

    @Test
    fun `every intent persists exactly once per call and re-queries from offset 0`() = runTest {
        val view = ViewSnapshot(id = "view-1", name = "Shows", kind = ViewKind.LIBRARY)
        val gateway = FakeCoreGateway(libraryGridByView = mapOf("view-1" to emptyList()))

        withLibraryViewModel(gateway, view) { viewModel ->
            val persistedBefore = gateway.setLibraryGridPrefsCalls.size
            val queriesBefore = gateway.libraryGridCalls.size

            val intents: List<() -> Unit> = listOf(
                { viewModel.setSort(GridSortField.DATE_ADDED) },
                { viewModel.cycleWatched() },
                { viewModel.cycleWatched() },
                { viewModel.setGenre("Drama") },
                { viewModel.setDecade(Decade.D2010S) },
                { viewModel.cycleStatus() },
                { viewModel.resetSortAndFilters() },
            )

            intents.forEachIndexed { index, intent ->
                intent()
                runCurrent()
                assertEquals("persist count after intent $index", persistedBefore + index + 1, gateway.setLibraryGridPrefsCalls.size)
                val lastQuery = gateway.libraryGridCalls.last()
                assertEquals(0u, lastQuery.offset)
                assertEquals(200u, lastQuery.limit)
            }
            assertEquals(queriesBefore + intents.size, gateway.libraryGridCalls.size)
        }
    }

    @Test
    fun `an intent also refreshes counts and groups but not genres`() = runTest {
        val view = ViewSnapshot(id = "view-1", name = "Movies", kind = ViewKind.LIBRARY)
        val gateway = FakeCoreGateway(libraryGridByView = mapOf("view-1" to emptyList()))

        withLibraryViewModel(gateway, view) { viewModel ->
            val genreCallsBefore = gateway.libraryGenresCalls.size
            val countsCallsBefore = gateway.libraryGridCountsCalls.size
            val groupsCallsBefore = gateway.libraryGridGroupsCalls.size

            viewModel.cycleWatched()
            runCurrent()

            assertEquals(countsCallsBefore + 1, gateway.libraryGridCountsCalls.size)
            assertEquals(groupsCallsBefore + 1, gateway.libraryGridGroupsCalls.size)
            assertEquals(genreCallsBefore, gateway.libraryGenresCalls.size)
        }
    }

    // ---- stale query discard (K1: a generation bump inside applyIntent) -----

    @Test
    fun `a refresh blocked in the gateway does not overwrite a newer sort's results`() = runTest {
        val view = ViewSnapshot(id = "view-1", name = "Movies", kind = ViewKind.LIBRARY)
        val nameItems = listOf(testCard(id = "a", name = "Alpha"))
        val fake = FakeCoreGateway(libraryGridByView = mapOf("view-1" to nameItems))
        val changes = MutableSharedFlow<ChangeEvent>(extraBufferCapacity = 4)
        val gateway = object : CoreGateway by fake {
            override fun changeEvents(): Flow<ChangeEvent> = changes
        }

        withLibraryViewModel(gateway, view) { viewModel ->
            assertEquals(nameItems, viewModel.state.value.items)
            assertEquals(1, fake.libraryGridCalls.size)

            fake.libraryGridGate = CompletableDeferred()
            changes.tryEmit(ChangeEvent.Refresh)
            runCurrent()
            assertEquals(2, fake.libraryGridCalls.size)

            val yearItems = listOf(testCard(id = "b", name = "Beta"))
            fake.libraryGridByView = mapOf("view-1" to yearItems)
            viewModel.setSort(GridSortField.YEAR)
            runCurrent()
            assertEquals(3, fake.libraryGridCalls.size)
            assertEquals(GridSortField.YEAR, viewModel.state.value.sort.field)

            fake.libraryGridGate!!.complete(Unit)
            runCurrent()

            assertEquals(yearItems, viewModel.state.value.items)
            assertEquals(GridSortField.YEAR, viewModel.state.value.sort.field)
        }
    }

    @Test
    fun `a page append started under the old sort is discarded after an intent`() = runTest {
        val view = ViewSnapshot(id = "view-1", name = "Movies", kind = ViewKind.LIBRARY)
        val nameItems = (0 until 250).map { testCard(id = "name-$it", name = "Item $it") }
        val fake = FakeCoreGateway(libraryGridByView = mapOf("view-1" to nameItems))

        withLibraryViewModel(fake, view) { viewModel ->
            assertEquals(200, viewModel.state.value.items.size)

            fake.libraryGridGate = CompletableDeferred()
            viewModel.loadNextPage()
            runCurrent()
            assertEquals(2, fake.libraryGridCalls.size)
            assertTrue(viewModel.state.value.isLoadingMore)

            val yearItems = listOf(testCard(id = "y0", name = "Y0"))
            fake.libraryGridByView = mapOf("view-1" to yearItems)
            viewModel.setSort(GridSortField.YEAR)
            runCurrent()
            assertEquals(3, fake.libraryGridCalls.size)

            fake.libraryGridGate!!.complete(Unit)
            runCurrent()

            assertEquals(yearItems, viewModel.state.value.items)
            assertFalse(viewModel.state.value.isLoadingMore)
        }
    }

    // ---- ensureLoadedThrough --------------------------------------------------

    @Test
    fun `ensureLoadedThrough is a no-op when the offset is already covered`() = runTest {
        val view = ViewSnapshot(id = "view-1", name = "Movies", kind = ViewKind.LIBRARY)
        val items = (0 until 200).map { testCard(id = "item-$it", name = "Item $it") }
        val gateway = FakeCoreGateway(libraryGridByView = mapOf("view-1" to items))

        withLibraryViewModel(gateway, view) { viewModel ->
            val callsBefore = gateway.libraryGridCalls.size

            viewModel.ensureLoadedThrough(50)

            assertEquals(callsBefore, gateway.libraryGridCalls.size)
        }
    }

    @Test
    fun `ensureLoadedThrough reads only the missing tail`() = runTest {
        val view = ViewSnapshot(id = "view-1", name = "Movies", kind = ViewKind.LIBRARY)
        val items = (0 until 500).map { testCard(id = "item-$it", name = "Item $it") }
        val gateway = FakeCoreGateway(libraryGridByView = mapOf("view-1" to items))

        withLibraryViewModel(gateway, view) { viewModel ->
            assertEquals(200, viewModel.state.value.items.size)

            viewModel.ensureLoadedThrough(350)

            // offset 350 needs rows up to 350 + PAGE_SIZE(200) = 550: the 350 after the loaded 200,
            // clamped to the 500 items available.
            assertEquals(500, viewModel.state.value.items.size)
            assertEquals(items.map { it.id }, viewModel.state.value.items.map { it.id })
            assertFalse(viewModel.state.value.hasMore)
            val call = gateway.libraryGridCalls.last()
            assertEquals(200u, call.offset)
            assertEquals(350u, call.limit)
        }
    }

    @Test
    fun `ensureLoadedThrough is a no-op when nothing more exists`() = runTest {
        val view = ViewSnapshot(id = "view-1", name = "Movies", kind = ViewKind.LIBRARY)
        val items = (0 until 50).map { testCard(id = "item-$it", name = "Item $it") }
        val gateway = FakeCoreGateway(libraryGridByView = mapOf("view-1" to items))

        withLibraryViewModel(gateway, view) { viewModel ->
            assertFalse(viewModel.state.value.hasMore) // only 50 items, less than PAGE_SIZE
            val callsBefore = gateway.libraryGridCalls.size

            val result = viewModel.ensureLoadedThrough(1000)

            assertEquals(callsBefore, gateway.libraryGridCalls.size)
            assertFalse(result)
        }
    }

    // ---- ensureLoadedThrough / loadNextPage serialization (K2) ---------------

    @Test
    fun `ensureLoadedThrough waits for a blocked page load, then returns true covering the offset`() = runTest {
        val view = ViewSnapshot(id = "view-1", name = "Movies", kind = ViewKind.LIBRARY)
        val items = (0 until 500).map { testCard(id = "item-$it", name = "Item $it") }
        val fake = FakeCoreGateway(libraryGridByView = mapOf("view-1" to items))

        withLibraryViewModel(fake, view) { viewModel ->
            assertEquals(200, viewModel.state.value.items.size)

            // Block the next-page load in flight.
            fake.libraryGridGate = CompletableDeferred()
            viewModel.loadNextPage()
            runCurrent()
            assertTrue(viewModel.state.value.isLoadingMore)

            var result: Boolean? = null
            val job = launch { result = viewModel.ensureLoadedThrough(250) }
            runCurrent()
            // Waits on loadNextPage's mutex hold, not an isLoadingMore early return.
            assertNull(result)

            fake.libraryGridGate!!.complete(Unit)
            runCurrent()
            job.join()

            assertEquals(true, result)
            assertTrue(viewModel.state.value.items.size > 250)
            assertFalse(viewModel.state.value.isLoadingMore)
        }
    }

    // ---- loadNextPage passes sort/filters -------------------------------------

    @Test
    fun `loadNextPage appends a bounded second page`() = runTest {
        val view = ViewSnapshot(id = "view-1", name = "Movies", kind = ViewKind.LIBRARY)
        val items = (0 until 250).map { testCard(id = "item-$it", name = "Item $it") }
        val gateway = FakeCoreGateway(libraryGridByView = mapOf(view.id to items))

        withLibraryViewModel(gateway, view) { viewModel ->
            assertEquals(200, viewModel.state.value.items.size)
            viewModel.loadNextPage()
            runCurrent()

            assertEquals(250, viewModel.state.value.items.size)
            assertFalse(viewModel.state.value.hasMore)
            assertEquals(200u, gateway.libraryGridCalls.last().offset)
            assertEquals(200u, gateway.libraryGridCalls.last().limit)
        }
    }

    @Test
    fun `loadNextPage passes the current sort and filters`() = runTest {
        val view = ViewSnapshot(id = "view-1", name = "Movies", kind = ViewKind.LIBRARY)
        val items = (0 until 250).map { testCard(id = "item-$it", name = "Item $it") }
        val gateway = FakeCoreGateway(libraryGridByView = mapOf(view.id to items))

        withLibraryViewModel(gateway, view) { viewModel ->
            viewModel.setSort(GridSortField.YEAR)
            runCurrent()
            viewModel.cycleWatched()
            runCurrent()

            viewModel.loadNextPage()
            runCurrent()

            val call = gateway.libraryGridCalls.last()
            assertEquals(GridSort(GridSortField.YEAR, descending = true), call.sort)
            assertEquals(WatchedFilter.UNWATCHED, call.filters.watched)
        }
    }

    @Test
    fun `loadNextPage discards a null page, leaving items and hasMore unchanged for a later retry`() = runTest {
        // docs/16 §4.6: a null page (failure) leaves hasMore alone, unlike a short page
        // (page.size < PAGE_SIZE) which legitimately sets hasMore = false.
        val view = ViewSnapshot(id = "view-1", name = "Movies", kind = ViewKind.LIBRARY)
        val items = (0 until 250).map { testCard(id = "item-$it", name = "Item $it") }
        val gateway = FakeCoreGateway(libraryGridByView = mapOf(view.id to items))

        withLibraryViewModel(gateway, view) { viewModel ->
            assertEquals(200, viewModel.state.value.items.size)
            assertTrue(viewModel.state.value.hasMore)

            gateway.libraryGridFails = true
            viewModel.loadNextPage()
            runCurrent()

            assertEquals(200, viewModel.state.value.items.size)
            assertTrue(viewModel.state.value.hasMore)
            assertFalse(viewModel.state.value.isLoadingMore)
        }
    }

    // ---- mirror change events --------------------------------------------------

    @Test
    fun `a mirror change event refreshes the grid immediately, no debounce wait`() = runTest {
        // changeRefreshScheduler's leading edge fires on the first event, not after a debounce
        // window.
        val view = ViewSnapshot(id = "view-1", name = "Movies", kind = ViewKind.LIBRARY)
        val fake = FakeCoreGateway(libraryGridByView = mapOf("view-1" to listOf(testCard(id = "a", name = "Alpha"))))
        val changes = MutableSharedFlow<ChangeEvent>(extraBufferCapacity = 4)
        val gateway = object : CoreGateway by fake {
            override fun changeEvents(): Flow<ChangeEvent> = changes
        }

        withLibraryViewModel(gateway, view) {
            assertEquals(1, fake.libraryGridCalls.size)

            changes.tryEmit(ChangeEvent.Upserted(ids = listOf("a"), libraryId = null))
            runCurrent()

            assertEquals(2, fake.libraryGridCalls.size)
        }
    }

    @Test
    fun `a change event re-queries with the current sort and filters and refreshes counts groups and genres`() = runTest {
        val view = ViewSnapshot(id = "view-1", name = "Movies", kind = ViewKind.LIBRARY)
        val fake = FakeCoreGateway(libraryGridByView = mapOf("view-1" to listOf(testCard(id = "a", name = "Alpha"))))
        val changes = MutableSharedFlow<ChangeEvent>(extraBufferCapacity = 4)
        val gateway = object : CoreGateway by fake {
            override fun changeEvents(): Flow<ChangeEvent> = changes
        }

        withLibraryViewModel(gateway, view) { viewModel ->
            viewModel.setSort(GridSortField.RUNTIME)
            runCurrent()
            viewModel.cycleStatus()
            runCurrent()

            val countsCallsBefore = fake.libraryGridCountsCalls.size
            val groupsCallsBefore = fake.libraryGridGroupsCalls.size
            val genresCallsBefore = fake.libraryGenresCalls.size

            changes.tryEmit(ChangeEvent.Refresh)
            runCurrent()

            val lastQuery = fake.libraryGridCalls.last()
            assertEquals(GridSort(GridSortField.RUNTIME, descending = true), lastQuery.sort)
            assertEquals(StatusFilter.CONTINUING, lastQuery.filters.status)
            assertEquals(countsCallsBefore + 1, fake.libraryGridCountsCalls.size)
            assertEquals(groupsCallsBefore + 1, fake.libraryGridGroupsCalls.size)
            assertEquals(genresCallsBefore + 1, fake.libraryGenresCalls.size)
        }
    }

    @Test
    fun `a change event for an unrelated id does not trigger a refetch`() = runTest {
        val view = ViewSnapshot(id = "view-1", name = "Movies", kind = ViewKind.LIBRARY)
        val fake = FakeCoreGateway(libraryGridByView = mapOf("view-1" to listOf(testCard(id = "a", name = "Alpha"))))
        val changes = MutableSharedFlow<ChangeEvent>(extraBufferCapacity = 4)
        val gateway = object : CoreGateway by fake {
            override fun changeEvents(): Flow<ChangeEvent> = changes
        }

        withLibraryViewModel(gateway, view) {
            assertEquals(1, fake.libraryGridCalls.size)

            changes.tryEmit(ChangeEvent.Upserted(ids = listOf("some-other-library-item"), libraryId = null))
            runCurrent()

            assertEquals(1, fake.libraryGridCalls.size)
        }
    }

    @Test
    fun `a Refresh event always re-queries, even with no id to check`() = runTest {
        val view = ViewSnapshot(id = "view-1", name = "Movies", kind = ViewKind.LIBRARY)
        val fake = FakeCoreGateway(libraryGridByView = mapOf("view-1" to listOf(testCard(id = "a", name = "Alpha"))))
        val changes = MutableSharedFlow<ChangeEvent>(extraBufferCapacity = 4)
        val gateway = object : CoreGateway by fake {
            override fun changeEvents(): Flow<ChangeEvent> = changes
        }

        withLibraryViewModel(gateway, view) {
            assertEquals(1, fake.libraryGridCalls.size)

            changes.tryEmit(ChangeEvent.Refresh)
            runCurrent()

            assertEquals(2, fake.libraryGridCalls.size)
        }
    }

    @Test
    fun `a Removed event for an item already on screen triggers a refetch`() = runTest {
        val view = ViewSnapshot(id = "view-1", name = "Movies", kind = ViewKind.LIBRARY)
        val fake = FakeCoreGateway(libraryGridByView = mapOf("view-1" to listOf(testCard(id = "a", name = "Alpha"))))
        val changes = MutableSharedFlow<ChangeEvent>(extraBufferCapacity = 4)
        val gateway = object : CoreGateway by fake {
            override fun changeEvents(): Flow<ChangeEvent> = changes
        }

        withLibraryViewModel(gateway, view) {
            assertEquals(1, fake.libraryGridCalls.size)

            changes.tryEmit(ChangeEvent.Removed(ids = listOf("a"), libraryId = null))
            runCurrent()

            assertEquals(2, fake.libraryGridCalls.size)
        }
    }

    @Test
    fun `ViewsChanged refetches so a revoked open library clears`() = runTest {
        val view = ViewSnapshot(id = "view-1", name = "Movies", kind = ViewKind.LIBRARY)
        val fake = FakeCoreGateway(libraryGridByView = mapOf("view-1" to listOf(testCard(id = "a", name = "Alpha"))))
        val changes = MutableSharedFlow<ChangeEvent>(extraBufferCapacity = 4)
        val gateway = object : CoreGateway by fake {
            override fun changeEvents(): Flow<ChangeEvent> = changes
        }

        withLibraryViewModel(gateway, view) {
            assertEquals(1, fake.libraryGridCalls.size)

            changes.tryEmit(ChangeEvent.ViewsChanged)
            runCurrent()

            assertEquals(2, fake.libraryGridCalls.size)
        }
    }

    @Test
    fun `an Upserted event always refetches an empty grid, even for an unrelated id`() = runTest {
        // No items loaded yet, so no cheap id-containment check to skip the refetch with.
        val view = ViewSnapshot(id = "view-1", name = "Movies", kind = ViewKind.LIBRARY)
        val fake = FakeCoreGateway()
        val changes = MutableSharedFlow<ChangeEvent>(extraBufferCapacity = 4)
        val gateway = object : CoreGateway by fake {
            override fun changeEvents(): Flow<ChangeEvent> = changes
        }

        withLibraryViewModel(gateway, view) {
            assertEquals(1, fake.libraryGridCalls.size)

            changes.tryEmit(ChangeEvent.Upserted(ids = listOf("brand-new-item"), libraryId = "view-1"))
            runCurrent()

            assertEquals(2, fake.libraryGridCalls.size)
        }
    }

    @Test
    fun `an empty library renders as an empty grid, not a crash`() = runTest {
        val view = ViewSnapshot(id = "view-empty", name = "Home Videos", kind = ViewKind.LIBRARY)
        val gateway = FakeCoreGateway()

        withLibraryViewModel(gateway, view) { viewModel ->
            assertEquals(emptyList<Any>(), viewModel.state.value.items)
            assertFalse(viewModel.state.value.isLoading)
        }
    }

    // ---- ViewKind.CHANNEL (plugin channel views, e.g. TVHeadend recordings) ----
    // changeRefreshScheduler is null for a live view (no mirror ChangeEvent stream), so no
    // withLibraryViewModel wrapper or advanceUntilIdle() is needed below.

    @Test
    fun `a CHANNEL view loads its first page via liveChildren, not libraryGrid`() = runTest {
        val view = ViewSnapshot(id = "channel-1", name = "Recordings", kind = ViewKind.CHANNEL)
        val items = listOf(testCard(id = "rec-1", name = "Recording 1"))
        val gateway = FakeCoreGateway(liveChildrenByParent = mapOf("channel-1" to items))

        val viewModel = LibraryViewModel(gateway, view)

        assertEquals(items, viewModel.state.value.items)
        assertFalse(viewModel.state.value.isLoading)
        assertEquals(0, gateway.childrenCalls.size)
        assertEquals(0, gateway.libraryGridCalls.size)

        val call = gateway.liveChildrenCalls.single()
        assertEquals("channel-1", call.parentId)
        assertEquals(0u, call.startIndex)
        assertEquals(200u, call.limit)
    }

    @Test
    fun `a CHANNEL view requests NAME_ASC`() = runTest {
        val view = ViewSnapshot(id = "channel-1", name = "Recordings", kind = ViewKind.CHANNEL)
        val gateway = FakeCoreGateway(liveChildrenByParent = mapOf("channel-1" to listOf(testCard(id = "rec-1"))))

        LibraryViewModel(gateway, view)

        assertEquals(LiveSort.NAME_ASC, gateway.liveChildrenCalls.single().sort)
    }

    @Test
    fun `a CHANNEL_FOLDER view requests NEWEST_FIRST and pages with it`() = runTest {
        val view = ViewSnapshot(id = "folder-1", name = "Aug 30", kind = ViewKind.CHANNEL_FOLDER)
        val items = (0 until 250).map { testCard(id = "rec-$it", name = "Recording $it") }
        val gateway = FakeCoreGateway(liveChildrenByParent = mapOf("folder-1" to items))
        val viewModel = LibraryViewModel(gateway, view)

        assertEquals(LiveSort.NEWEST_FIRST, gateway.liveChildrenCalls.single().sort)

        viewModel.loadNextPage()

        assertEquals(LiveSort.NEWEST_FIRST, gateway.liveChildrenCalls.last().sort)
        assertEquals(200u, gateway.liveChildrenCalls.last().startIndex)
        assertEquals(200u, gateway.liveChildrenCalls.last().limit)
    }

    @Test
    fun `loadNextPage on a CHANNEL view pages liveChildren by offset`() = runTest {
        val view = ViewSnapshot(id = "channel-1", name = "Recordings", kind = ViewKind.CHANNEL)
        val items = (0 until 250).map { testCard(id = "rec-$it", name = "Recording $it") }
        val gateway = FakeCoreGateway(liveChildrenByParent = mapOf("channel-1" to items))
        val viewModel = LibraryViewModel(gateway, view)

        assertEquals(200, viewModel.state.value.items.size)
        viewModel.loadNextPage()

        assertEquals(250, viewModel.state.value.items.size)
        assertFalse(viewModel.state.value.hasMore)
        assertEquals(200u, gateway.liveChildrenCalls.last().startIndex)
        assertEquals(200u, gateway.liveChildrenCalls.last().limit)
    }

    @Test
    fun `a failed live fetch keeps the last good items and clears loading`() = runTest {
        val view = ViewSnapshot(id = "channel-1", name = "Recordings", kind = ViewKind.CHANNEL)
        val items = listOf(testCard(id = "rec-1", name = "Recording 1"))
        val gateway = FakeCoreGateway(liveChildrenByParent = mapOf("channel-1" to items))
        val viewModel = LibraryViewModel(gateway, view)
        assertEquals(items, viewModel.state.value.items)

        gateway.liveChildrenError = RuntimeException("server unreachable")
        viewModel.onBecameTop()

        assertEquals(items, viewModel.state.value.items)
        assertFalse(viewModel.state.value.isLoading)
        assertFalse(viewModel.state.value.isLoadingMore)
    }

    @Test
    fun `onBecameTop refreshes a CHANNEL view once the initial load has completed`() = runTest {
        val view = ViewSnapshot(id = "channel-1", name = "Recordings", kind = ViewKind.CHANNEL)
        val items = listOf(testCard(id = "rec-1", name = "Recording 1"))
        val gateway = FakeCoreGateway(liveChildrenByParent = mapOf("channel-1" to items))
        val viewModel = LibraryViewModel(gateway, view)
        assertEquals(1, gateway.liveChildrenCalls.size)

        viewModel.onBecameTop()

        assertEquals(2, gateway.liveChildrenCalls.size)
    }

    @Test
    fun `onBecameTop refreshes a CHANNEL_FOLDER view too`() = runTest {
        val view = ViewSnapshot(id = "folder-1", name = "Aug 30", kind = ViewKind.CHANNEL_FOLDER)
        val items = listOf(testCard(id = "rec-1", name = "Recording 1"))
        val gateway = FakeCoreGateway(liveChildrenByParent = mapOf("folder-1" to items))
        val viewModel = LibraryViewModel(gateway, view)
        assertEquals(1, gateway.liveChildrenCalls.size)

        viewModel.onBecameTop()

        assertEquals(2, gateway.liveChildrenCalls.size)
    }

    @Test
    fun `onBecameTop is a no-op for a LIBRARY view`() = runTest {
        val view = ViewSnapshot(id = "view-1", name = "Movies", kind = ViewKind.LIBRARY)
        val gateway = FakeCoreGateway(libraryGridByView = mapOf("view-1" to listOf(testCard(id = "a", name = "Alpha"))))

        withLibraryViewModel(gateway, view) { viewModel ->
            assertEquals(1, gateway.libraryGridCalls.size)

            viewModel.onBecameTop()

            assertEquals(1, gateway.libraryGridCalls.size)
            assertEquals(0, gateway.liveChildrenCalls.size)
        }
    }

    @Test
    fun `a CHANNEL view never subscribes to mirror change events`() = runTest {
        val view = ViewSnapshot(id = "channel-1", name = "Recordings", kind = ViewKind.CHANNEL)
        val fake = FakeCoreGateway(liveChildrenByParent = mapOf("channel-1" to listOf(testCard(id = "rec-1", name = "Recording 1"))))
        var subscribed = false
        val changes = MutableSharedFlow<ChangeEvent>(extraBufferCapacity = 4)
        val gateway = object : CoreGateway by fake {
            override fun changeEvents(): Flow<ChangeEvent> {
                subscribed = true
                return changes
            }
        }

        LibraryViewModel(gateway, view)

        assertFalse(subscribed)
    }

    @Test
    fun `a CHANNEL view never calls getLibraryGridPrefs`() = runTest {
        val view = ViewSnapshot(id = "channel-1", name = "Recordings", kind = ViewKind.CHANNEL)
        val gateway = FakeCoreGateway(liveChildrenByParent = mapOf("channel-1" to listOf(testCard(id = "rec-1"))))

        val viewModel = LibraryViewModel(gateway, view)

        assertFalse(viewModel.state.value.prefsLoaded)
    }

    // -- docs/07 §Collection card: lazy member previews --------------------

    @Test
    fun `a collection preview loads every member once and caches them by id`() = runTest {
        val view = ViewSnapshot(id = "boxsets", name = "Collections", kind = ViewKind.LIBRARY)
        val members = listOf("a", "b", "c", "d").map { testCard(id = it) }
        val gateway = FakeCoreGateway(libraryGridByView = mapOf("boxsets" to emptyList()), childrenByParent = mapOf("box" to members))

        withLibraryViewModel(gateway, view) { viewModel ->
            viewModel.loadCollectionPreview("box")
            viewModel.loadCollectionPreview("box")
            runCurrent()

            assertEquals(listOf("a", "b", "c", "d"), viewModel.collectionPreview("box").first()?.map { it.id })
            assertNull(viewModel.collectionPreview("other").first())
            assertEquals(1, gateway.childrenCalls.count { it.parentId == "box" })
        }
    }

    @Test
    fun `a change to a held member re-reads that collection's preview`() = runTest {
        val view = ViewSnapshot(id = "boxsets", name = "Collections", kind = ViewKind.LIBRARY)
        val fake = FakeCoreGateway(
            libraryGridByView = mapOf("boxsets" to listOf(testCard(id = "box", itemType = "BoxSet"))),
            childrenByParent = mapOf("box" to listOf(testCard(id = "member"))),
        )
        val changes = MutableSharedFlow<ChangeEvent>(extraBufferCapacity = 4)
        val gateway = object : CoreGateway by fake {
            override fun changeEvents(): Flow<ChangeEvent> = changes
        }

        withLibraryViewModel(gateway, view) { viewModel ->
            viewModel.loadCollectionPreview("box")
            runCurrent()
            assertEquals(1, fake.childrenCalls.count { it.parentId == "box" })

            // The member lives in another library, so only its preview membership ties it here.
            changes.tryEmit(ChangeEvent.Upserted(ids = listOf("member"), libraryId = "films"))
            runCurrent()

            assertEquals(2, fake.childrenCalls.count { it.parentId == "box" })
        }
    }

    @Test
    fun `a change re-reads only the stacks it touches and drops stacks that left the grid`() = runTest {
        val view = ViewSnapshot(id = "boxsets", name = "Collections", kind = ViewKind.LIBRARY)
        val grid = mutableListOf(testCard(id = "boxA", itemType = "BoxSet"), testCard(id = "boxB", itemType = "BoxSet"))
        val fake = FakeCoreGateway(
            libraryGridByView = mapOf("boxsets" to grid),
            childrenByParent = mapOf("boxA" to listOf(testCard(id = "a1")), "boxB" to listOf(testCard(id = "b1"))),
        )
        val changes = MutableSharedFlow<ChangeEvent>(extraBufferCapacity = 4)
        val gateway = object : CoreGateway by fake {
            override fun changeEvents(): Flow<ChangeEvent> = changes
        }

        withLibraryViewModel(gateway, view) { viewModel ->
            viewModel.loadCollectionPreview("boxA")
            viewModel.loadCollectionPreview("boxB")
            runCurrent()

            changes.tryEmit(ChangeEvent.Upserted(ids = listOf("a1"), libraryId = "films"))
            runCurrent()
            assertEquals(2, fake.childrenCalls.count { it.parentId == "boxA" })
            assertEquals(1, fake.childrenCalls.count { it.parentId == "boxB" })

            // boxB leaves the grid: a full refresh re-reads boxA and evicts boxB's members.
            fake.libraryGridByView = mapOf("boxsets" to grid.take(1))
            advanceTimeBy(1_000)
            changes.tryEmit(ChangeEvent.Refresh)
            advanceUntilIdle()
            assertEquals(3, fake.childrenCalls.count { it.parentId == "boxA" })
            assertEquals(1, fake.childrenCalls.count { it.parentId == "boxB" })
            assertNull(viewModel.collectionPreview("boxB").first())
        }
    }

    // ---- tail reads dropped when a requery replaced the items (identity guard) ----

    @Test
    fun `a next-page tail in flight across a requery is dropped, leaving a prefix of the new order`() = runTest {
        val view = ViewSnapshot(id = "view-1", name = "Movies", kind = ViewKind.LIBRARY)
        val nameOrder = (0 until 500).map { testCard(id = "name-$it", name = "Item $it") }
        val fake = FakeCoreGateway(libraryGridByView = mapOf("view-1" to nameOrder))

        withLibraryViewModel(fake, view) { viewModel ->
            assertEquals(200, viewModel.state.value.items.size)

            val tailGate = CompletableDeferred<Unit>()
            fake.libraryGridGate = tailGate
            viewModel.loadNextPage()
            runCurrent()
            assertTrue(viewModel.state.value.isLoadingMore)

            // The requery runs ungated and lands first, under the new order.
            val yearOrder = (0 until 500).map { testCard(id = "year-$it", name = "Item $it") }
            fake.libraryGridGate = null
            fake.libraryGridByView = mapOf("view-1" to yearOrder)
            viewModel.setSort(GridSortField.YEAR)
            runCurrent()
            assertEquals(yearOrder.take(200).map { it.id }, viewModel.state.value.items.map { it.id })

            tailGate.complete(Unit)
            runCurrent()

            assertEquals(yearOrder.take(200).map { it.id }, viewModel.state.value.items.map { it.id })
            assertTrue(viewModel.state.value.hasMore)
            assertFalse(viewModel.state.value.isLoadingMore)
        }
    }

    @Test
    fun `a rail-jump tail in flight across a requery is dropped, leaving a prefix of the new order`() = runTest {
        val view = ViewSnapshot(id = "view-1", name = "Movies", kind = ViewKind.LIBRARY)
        val nameOrder = (0 until 500).map { testCard(id = "name-$it", name = "Item $it") }
        val fake = FakeCoreGateway(libraryGridByView = mapOf("view-1" to nameOrder))

        withLibraryViewModel(fake, view) { viewModel ->
            assertEquals(200, viewModel.state.value.items.size)

            val tailGate = CompletableDeferred<Unit>()
            fake.libraryGridGate = tailGate
            var covered: Boolean? = null
            val jump = launch { covered = viewModel.ensureLoadedThrough(250) }
            runCurrent()
            assertNull(covered)

            val yearOrder = (0 until 500).map { testCard(id = "year-$it", name = "Item $it") }
            fake.libraryGridGate = null
            fake.libraryGridByView = mapOf("view-1" to yearOrder)
            viewModel.setSort(GridSortField.YEAR)
            runCurrent()

            tailGate.complete(Unit)
            runCurrent()
            jump.join()

            assertEquals(yearOrder.take(200).map { it.id }, viewModel.state.value.items.map { it.id })
            // Coverage is reported against the new result, which stops short of the offset.
            assertEquals(false, covered)
            assertFalse(viewModel.state.value.isLoadingMore)
        }
    }

    // ---- refresh summary ordering (refreshSeq) ---------------------------------

    @Test
    fun `an older refresh's counts and groups never land after a newer refresh's`() = runTest {
        val view = ViewSnapshot(id = "view-1", name = "Movies", kind = ViewKind.LIBRARY)
        val fake = FakeCoreGateway(libraryGridByView = mapOf("view-1" to listOf(testCard(id = "a"))))
        val changes = MutableSharedFlow<ChangeEvent>(extraBufferCapacity = 4)
        val firstSummaryGate = CompletableDeferred<Unit>()
        var countsCalls = 0
        val oldCounts = GridCounts(filtered = 1uL, total = 1uL)
        val newCounts = GridCounts(filtered = 2uL, total = 2uL)
        val oldGroups = listOf(GridGroup(key = "OLD", count = 1uL))
        val newGroups = listOf(GridGroup(key = "NEW", count = 2uL))
        val gateway = object : CoreGateway by fake {
            override fun changeEvents(): Flow<ChangeEvent> = changes

            // The first refresh's summary is held; the second's answers at once with newer data.
            override suspend fun libraryGridCounts(viewId: String, filters: GridFilters): GridCounts? {
                if (++countsCalls == 1) {
                    firstSummaryGate.await()
                    return oldCounts
                }
                return newCounts
            }

            // The first refresh only reaches its groups read after the gate opens.
            override suspend fun libraryGridGroups(viewId: String, sort: GridSort, filters: GridFilters): List<GridGroup>? =
                if (firstSummaryGate.isCompleted) oldGroups else newGroups
        }

        withLibraryViewModel(gateway, view) { viewModel ->
            // Init refresh published its items and is parked on its summary read.
            assertEquals(1, countsCalls)
            assertEquals(listOf("a"), viewModel.state.value.items.map { it.id })

            changes.tryEmit(ChangeEvent.Refresh)
            runCurrent()
            assertEquals(newCounts, viewModel.state.value.counts)
            assertEquals(newGroups, viewModel.state.value.groups)

            firstSummaryGate.complete(Unit)
            runCurrent()

            assertEquals(newCounts, viewModel.state.value.counts)
            assertEquals(newGroups, viewModel.state.value.groups)
        }
    }
}
