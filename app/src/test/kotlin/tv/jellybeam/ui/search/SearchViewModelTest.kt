package tv.jellybeam.ui.search

import tv.jellybeam.MainDispatcherRule
import tv.jellybeam.data.CoreGateway
import tv.jellybeam.data.FakeCoreGateway
import tv.jellybeam.ui.cards.testCard
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlinx.coroutines.test.advanceTimeBy
import tv.jellybeam.data.emptySeerrPage
import uniffi.jellybeam_core.Card
import uniffi.jellybeam_core.SeerrAvailability
import uniffi.jellybeam_core.SeerrCard
import uniffi.jellybeam_core.SeerrMediaType

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `initial state is empty with no gateway call`() = runTest {
        val fake = FakeCoreGateway()
        val viewModel = SearchViewModel(fake)

        assertEquals("", viewModel.state.value.query)
        assertEquals(emptyList<Card>(), viewModel.state.value.results)
        assertTrue(fake.searchCalls.isEmpty())
    }

    @Test
    fun `a query change debounces and then populates results`() = runTest {
        val results = listOf(testCard(id = "q1", name = "Quantum Static"))
        val fake = FakeCoreGateway(searchResultsByQuery = mapOf("quantum" to results))
        val viewModel = SearchViewModel(fake)

        viewModel.onQueryChange("quantum")
        assertTrue(fake.searchCalls.isEmpty())

        advanceUntilIdle()

        assertEquals(listOf(FakeCoreGateway.SearchCall("quantum", 60u)), fake.searchCalls)
        assertEquals(results, viewModel.state.value.results)
        assertEquals(false, viewModel.state.value.isSearching)
    }

    @Test
    fun `rapid successive query changes collapse into a single debounced search call`() = runTest {
        val results = listOf(testCard(id = "q1", name = "Quantum Static"))
        val fake = FakeCoreGateway(searchResultsByQuery = mapOf("quantum" to results))
        val viewModel = SearchViewModel(fake)

        viewModel.onQueryChange("q")
        viewModel.onQueryChange("qu")
        viewModel.onQueryChange("quan")
        viewModel.onQueryChange("quantum")
        advanceUntilIdle()

        assertEquals(listOf(FakeCoreGateway.SearchCall("quantum", 60u)), fake.searchCalls)
        assertEquals(results, viewModel.state.value.results)
    }

    @Test
    fun `a blank query clears results without ever calling the gateway`() = runTest {
        val results = listOf(testCard(id = "q1"))
        val fake = FakeCoreGateway(searchResultsByQuery = mapOf("quantum" to results))
        val viewModel = SearchViewModel(fake)

        viewModel.onQueryChange("quantum")
        advanceUntilIdle()
        assertEquals(1, fake.searchCalls.size)
        assertEquals(results, viewModel.state.value.results)

        viewModel.onQueryChange("")
        advanceUntilIdle()

        assertEquals(
            "a blank query must never reach CoreGateway.search",
            1,
            fake.searchCalls.size,
        )
        assertEquals(emptyList<Card>(), viewModel.state.value.results)
        assertEquals(false, viewModel.state.value.isSearching)
    }

    /**
     * Two searches can be in flight at once with no guarantee the first
     * dispatched returns first; the query-generation counter must discard
     * a late-resolving earlier result rather than clobber the newer one.
     */
    @Test
    fun `a slower earlier query does not clobber a newer one`() = runTest {
        val quantumResults = listOf(testCard(id = "quantum-result", name = "Quantum Static"))
        val hevcResults = listOf(testCard(id = "hevc-result", name = "06-hevc8"))
        val fake = FakeCoreGateway(
            searchResultsByQuery = mapOf(
                "quantum" to quantumResults,
                "hevc" to hevcResults,
            ),
        )
        val slowQueryGate = CompletableDeferred<Unit>()
        val gateway = object : CoreGateway by fake {
            override suspend fun search(query: String, limit: UInt): List<Card> {
                if (query == "quantum") slowQueryGate.await()
                return fake.search(query, limit)
            }
        }
        val viewModel = SearchViewModel(gateway)

        viewModel.onQueryChange("quantum")
        advanceUntilIdle()
        assertEquals(emptyList<Card>(), viewModel.state.value.results)

        viewModel.onQueryChange("hevc")
        advanceUntilIdle()
        assertEquals(hevcResults, viewModel.state.value.results)

        slowQueryGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(
            hevcResults,
            viewModel.state.value.results,
        )
    }

    // -- Discover section (docs/14 "Unified search") --------------------------

    private fun seerrCard(tmdbId: Long, jellyfinItemId: String? = null) = SeerrCard(
        mediaType = SeerrMediaType.MOVIE,
        tmdbId = tmdbId,
        title = "t$tmdbId",
        year = null,
        overview = null,
        posterUrl = null,
        backdropUrl = null,
        availability = SeerrAvailability.NOT_REQUESTED,
        jellyfinItemId = jellyfinItemId,
    )

    private fun configuredFake(library: List<Card>, seerr: Result<List<SeerrCard>>) = FakeCoreGateway(
        searchResultsByQuery = mapOf("quantum" to library),
        seerrSearchResult = seerr.map { emptySeerrPage().copy(cards = it) },
    )

    private fun connectedViewModel(fake: FakeCoreGateway) = SearchViewModel(fake).apply { setDiscoverConfigured(true) }

    @Test
    fun `without Seerr the section stays hidden and Seerr is never searched`() = runTest {
        val fake = FakeCoreGateway(searchResultsByQuery = mapOf("quantum" to listOf(testCard(id = "q1"))))
        val viewModel = SearchViewModel(fake)

        viewModel.onQueryChange("quantum")
        advanceUntilIdle()

        assertEquals(DiscoverSection.Hidden, viewModel.state.value.discover)
        assertTrue(fake.seerrSearchCalls.isEmpty())
    }

    @Test
    fun `library results land before Seerr is asked`() = runTest {
        val library = listOf(testCard(id = "q1"))
        val fake = configuredFake(library, Result.success(listOf(seerrCard(7))))
        val viewModel = connectedViewModel(fake)

        viewModel.onQueryChange("quantum")
        advanceTimeBy(400)

        assertEquals(library, viewModel.state.value.results)
        assertEquals(DiscoverSection.Searching, viewModel.state.value.discover)
        assertTrue(fake.seerrSearchCalls.isEmpty())

        advanceUntilIdle()

        assertEquals(listOf(FakeCoreGateway.SeerrSearchCall("quantum", 1)), fake.seerrSearchCalls)
        assertEquals(DiscoverSection.Results(listOf(seerrCard(7))), viewModel.state.value.discover)
    }

    @Test
    fun `Seerr results already in the library results are dropped`() = runTest {
        val fake = configuredFake(listOf(testCard(id = "q1")), Result.success(listOf(seerrCard(7, "q1"), seerrCard(8))))
        val viewModel = connectedViewModel(fake)

        viewModel.onQueryChange("quantum")
        advanceUntilIdle()

        assertEquals(DiscoverSection.Results(listOf(seerrCard(8))), viewModel.state.value.discover)
    }

    @Test
    fun `a Seerr failure marks only the section unavailable`() = runTest {
        val library = listOf(testCard(id = "q1"))
        val fake = configuredFake(library, Result.failure(RuntimeException("down")))
        val viewModel = connectedViewModel(fake)

        viewModel.onQueryChange("quantum")
        advanceUntilIdle()

        assertEquals(library, viewModel.state.value.results)
        assertEquals(DiscoverSection.Unavailable, viewModel.state.value.discover)
    }

    @Test
    fun `clearing the query hides the section`() = runTest {
        val fake = configuredFake(emptyList(), Result.success(listOf(seerrCard(7))))
        val viewModel = connectedViewModel(fake)

        viewModel.onQueryChange("quantum")
        advanceUntilIdle()
        viewModel.onQueryChange("")
        advanceUntilIdle()

        assertEquals(DiscoverSection.Hidden, viewModel.state.value.discover)
        assertEquals(1, fake.seerrSearchCalls.size)
    }

    @Test
    fun `returning to the answered query shows its answer at once`() = runTest {
        val fake = configuredFake(emptyList(), Result.success(listOf(seerrCard(7))))
        val viewModel = connectedViewModel(fake)

        viewModel.onQueryChange("quantum")
        advanceUntilIdle()
        viewModel.onQueryChange("quantumx")
        assertEquals(DiscoverSection.Searching, viewModel.state.value.discover)
        viewModel.onQueryChange("quantum")

        assertEquals(DiscoverSection.Results(listOf(seerrCard(7))), viewModel.state.value.discover)
        advanceUntilIdle()
        assertEquals(DiscoverSection.Results(listOf(seerrCard(7))), viewModel.state.value.discover)
    }

    @Test
    fun `a Seerr failure is retried when the same query is searched again`() = runTest {
        val fake = configuredFake(emptyList(), Result.failure(RuntimeException("down")))
        val viewModel = connectedViewModel(fake)
        viewModel.onQueryChange("quantum")
        advanceUntilIdle()
        assertEquals(DiscoverSection.Unavailable, viewModel.state.value.discover)

        fake.seerrSearchResult = Result.success(emptySeerrPage().copy(cards = listOf(seerrCard(7))))
        viewModel.onQueryChange("")
        advanceUntilIdle()
        viewModel.onQueryChange("quantum")
        advanceUntilIdle()

        assertEquals(DiscoverSection.Results(listOf(seerrCard(7))), viewModel.state.value.discover)
        assertEquals(2, fake.seerrSearchCalls.size)
    }

    @Test
    fun `disconnecting Discover hides the section and the hint stops naming it`() = runTest {
        val fake = configuredFake(emptyList(), Result.success(listOf(seerrCard(7))))
        val viewModel = connectedViewModel(fake)
        viewModel.onQueryChange("quantum")
        advanceUntilIdle()

        viewModel.setDiscoverConfigured(false)

        assertEquals(DiscoverSection.Hidden, viewModel.state.value.discover)
        assertEquals(false, viewModel.state.value.discoverEnabled)
    }

    @Test
    fun `a one-letter query searches the library but never Seerr`() = runTest {
        val fake = FakeCoreGateway(searchResultsByQuery = mapOf("q" to listOf(testCard(id = "q1"))))
        val viewModel = connectedViewModel(fake)

        viewModel.onQueryChange("q")
        assertEquals(DiscoverSection.Hidden, viewModel.state.value.discover)
        advanceUntilIdle()

        assertEquals(listOf(testCard(id = "q1")), viewModel.state.value.results)
        assertEquals(DiscoverSection.Hidden, viewModel.state.value.discover)
        assertTrue(fake.seerrSearchCalls.isEmpty())
    }

    @Test
    fun `a trailing space from a keyboard suggestion reaches Seerr trimmed`() = runTest {
        val fake = FakeCoreGateway(seerrSearchResult = Result.success(emptySeerrPage().copy(cards = listOf(seerrCard(7)))))
        val viewModel = connectedViewModel(fake)

        viewModel.onQueryChange("quantum ")
        advanceUntilIdle()

        assertEquals(listOf(FakeCoreGateway.SeerrSearchCall("quantum", 1)), fake.seerrSearchCalls)
        assertEquals(DiscoverSection.Results(listOf(seerrCard(7))), viewModel.state.value.discover)
    }
}
