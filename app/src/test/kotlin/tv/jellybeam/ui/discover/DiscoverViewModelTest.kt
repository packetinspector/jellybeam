package tv.jellybeam.ui.discover

import tv.jellybeam.MainDispatcherRule
import tv.jellybeam.i18n.ResourceUiStrings
import tv.jellybeam.data.FakeCoreGateway
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uniffi.jellybeam_core.CoreException
import uniffi.jellybeam_core.SeerrAvailability
import uniffi.jellybeam_core.SeerrCard
import uniffi.jellybeam_core.SeerrHome
import uniffi.jellybeam_core.SeerrHomeRow
import uniffi.jellybeam_core.SeerrMediaType

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoverViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `fetches seerr_home on init, not before`() = runTest {
        val row = SeerrHomeRow(id = "trending", title = "Trending", cards = emptyList())
        val fake = FakeCoreGateway(seerrHomeResult = Result.success(SeerrHome(rows = listOf(row))))

        // UnconfinedTestDispatcher completes the init-launched fetch before the constructor
        // returns.
        val viewModel = DiscoverViewModel(fake, ResourceUiStrings.default)

        advanceUntilIdle()

        assertEquals(false, viewModel.state.value.isLoading)
        assertEquals(listOf(row), viewModel.state.value.rows)
    }

    @Test
    fun `SeerrNotConfigured routes to the notConfigured flag, not a generic error`() = runTest {
        val fake = FakeCoreGateway(seerrHomeResult = Result.failure(CoreException.SeerrNotConfigured()))

        val viewModel = DiscoverViewModel(fake, ResourceUiStrings.default)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.notConfigured)
        assertEquals(emptyList<SeerrHomeRow>(), viewModel.state.value.rows)
    }

    @Test
    fun `retry re-fetches after a failure`() = runTest {
        val row = SeerrHomeRow(id = "movies", title = "Movies", cards = emptyList())
        var attempt = 0
        val fake = FakeCoreGateway()
        val gateway = object : tv.jellybeam.data.CoreGateway by fake {
            override suspend fun seerrHome(): SeerrHome {
                attempt++
                if (attempt == 1) throw CoreException.Api(detail = "server unreachable")
                return SeerrHome(rows = listOf(row))
            }
        }

        val viewModel = DiscoverViewModel(gateway, ResourceUiStrings.default)
        advanceUntilIdle()
        assertEquals("server unreachable", viewModel.state.value.error)

        viewModel.retry()
        advanceUntilIdle()

        assertEquals(null, viewModel.state.value.error)
        assertEquals(listOf(row), viewModel.state.value.rows)
    }

    // -- Watchlist shelf (docs/14 Watchlist) -------------------------------------------

    private fun card(tmdbId: Long) = SeerrCard(
        mediaType = SeerrMediaType.MOVIE,
        tmdbId = tmdbId,
        title = "Synthetic $tmdbId",
        year = null,
        overview = null,
        posterUrl = null,
        backdropUrl = null,
        availability = SeerrAvailability.NOT_REQUESTED,
        jellyfinItemId = null,
    )

    private val trending = SeerrHomeRow(id = "trending", title = "Trending", cards = listOf(card(100L)))

    private fun fakeWith(watchlist: Result<List<SeerrCard>>) =
        FakeCoreGateway(seerrHomeResult = Result.success(SeerrHome(rows = listOf(trending))), seerrWatchlistResult = watchlist)

    @Test
    fun `a watchlist shelf comes first, titled from the string resource, ahead of the home rows`() = runTest {
        val viewModel = DiscoverViewModel(fakeWith(Result.success(listOf(card(1L), card(1L), card(2L)))), ResourceUiStrings.default)
        advanceUntilIdle()

        val rows = viewModel.state.value.rows
        assertEquals(listOf("watchlist", "trending"), rows.map { it.id })
        assertEquals("Watchlist", rows.first().title)
        assertEquals("duplicates are dropped", listOf(1L, 2L), rows.first().cards.map { it.tmdbId })
    }

    @Test
    fun `an empty watchlist leaves no shelf`() = runTest {
        val viewModel = DiscoverViewModel(fakeWith(Result.success(emptyList())), ResourceUiStrings.default)
        advanceUntilIdle()

        assertEquals(listOf(trending), viewModel.state.value.rows)
    }

    @Test
    fun `a watchlist failure leaves the shelf out silently while the home rows show`() = runTest {
        val viewModel = DiscoverViewModel(fakeWith(Result.failure(CoreException.Api(detail = "watchlist down"))), ResourceUiStrings.default)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(listOf(trending), state.rows)
        assertNull(state.error)
        assertFalse(state.notConfigured)
        assertFalse(state.isLoading)
    }

    @Test
    fun `home rows do not wait for a slow watchlist, which joins when it lands`() = runTest {
        val fake = fakeWith(Result.success(listOf(card(1L))))
        val gate = CompletableDeferred<Unit>().also { fake.seerrWatchlistGate = it }
        val store = ViewModelStore()
        try {
            val viewModel = DiscoverViewModel(fake, ResourceUiStrings.default).also { store.put("home", it) }
            advanceUntilIdle()

            assertFalse(viewModel.state.value.isLoading)
            assertEquals(listOf("trending"), viewModel.state.value.rows.map { it.id })

            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf("watchlist", "trending"), viewModel.state.value.rows.map { it.id })
        } finally {
            store.clear()
        }
    }

    @Test
    fun `rows on screen, the watchlist shelf included, stay up during a reload`() = runTest {
        val fake = fakeWith(Result.success(listOf(card(1L))))
        val store = ViewModelStore()
        try {
            val viewModel = DiscoverViewModel(fake, ResourceUiStrings.default).also { store.put("home", it) }
            advanceUntilIdle()
            val homeGate = CompletableDeferred<Unit>().also { fake.seerrHomeGate = it }
            val watchlistGate = CompletableDeferred<Unit>().also { fake.seerrWatchlistGate = it }

            viewModel.retry()
            advanceUntilIdle()

            assertFalse(viewModel.state.value.isLoading)
            assertEquals(listOf("watchlist", "trending"), viewModel.state.value.rows.map { it.id })
            homeGate.complete(Unit)
            watchlistGate.complete(Unit)
        } finally {
            store.clear()
        }
    }

    @Test
    fun `a reload publishes once both land, so the previous watchlist never pairs with the new home rows`() = runTest {
        val fake = fakeWith(Result.success(listOf(card(1L), card(2L))))
        val store = ViewModelStore()
        try {
            val viewModel = DiscoverViewModel(fake, ResourceUiStrings.default).also { store.put("home", it) }
            advanceUntilIdle()
            val before = viewModel.state.value.rows
            assertEquals(listOf(1L, 2L), before.first().cards.map { it.tmdbId })

            val newTrending = SeerrHomeRow(id = "trending", title = "Trending", cards = listOf(card(101L)))
            fake.seerrHomeResult = Result.success(SeerrHome(rows = listOf(newTrending)))
            fake.seerrWatchlistResult = Result.success(listOf(card(1L)))
            val gate = CompletableDeferred<Unit>().also { fake.seerrWatchlistGate = it }
            viewModel.retry()
            advanceUntilIdle()

            assertEquals("new home held back while the watchlist is in flight", before, viewModel.state.value.rows)

            gate.complete(Unit)
            advanceUntilIdle()

            val rows = viewModel.state.value.rows
            assertEquals(listOf(1L), rows.first { it.id == "watchlist" }.cards.map { it.tmdbId })
            assertEquals(listOf(101L), rows.first { it.id == "trending" }.cards.map { it.tmdbId })
        } finally {
            store.clear()
        }
    }

    @Test
    fun `a stale watchlist result cannot land after a newer refresh`() = runTest {
        val fake = fakeWith(Result.success(listOf(card(1L))))
        val staleGate = CompletableDeferred<Unit>().also { fake.seerrWatchlistGate = it }
        val store = ViewModelStore()
        try {
            val viewModel = DiscoverViewModel(fake, ResourceUiStrings.default).also { store.put("home", it) }
            advanceUntilIdle()

            fake.seerrWatchlistResult = Result.success(listOf(card(2L)))
            fake.seerrWatchlistGate = null
            viewModel.retry()
            advanceUntilIdle()
            assertEquals(listOf(2L), viewModel.state.value.rows.first().cards.map { it.tmdbId })

            fake.seerrWatchlistResult = Result.success(listOf(card(1L)))
            staleGate.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf(2L), viewModel.state.value.rows.first().cards.map { it.tmdbId })
        } finally {
            store.clear()
        }
    }

    @Test
    fun `a home failure is still the error state even when the watchlist loads`() = runTest {
        val fake = FakeCoreGateway(
            seerrHomeResult = Result.failure(CoreException.Api(detail = "home down")),
            seerrWatchlistResult = Result.success(listOf(card(1L))),
        )
        val viewModel = DiscoverViewModel(fake, ResourceUiStrings.default)
        advanceUntilIdle()

        assertEquals("home down", viewModel.state.value.error)
        assertEquals(emptyList<SeerrHomeRow>(), viewModel.state.value.rows)
    }
}
