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
import uniffi.jellybeam_core.Card

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
}
