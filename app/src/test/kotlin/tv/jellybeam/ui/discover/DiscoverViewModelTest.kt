package tv.jellybeam.ui.discover

import tv.jellybeam.MainDispatcherRule
import tv.jellybeam.data.FakeCoreGateway
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uniffi.jellybeam_core.CoreException
import uniffi.jellybeam_core.SeerrHome
import uniffi.jellybeam_core.SeerrHomeRow

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
        val viewModel = DiscoverViewModel(fake)

        advanceUntilIdle()

        assertEquals(false, viewModel.state.value.isLoading)
        assertEquals(listOf(row), viewModel.state.value.rows)
    }

    @Test
    fun `SeerrNotConfigured routes to the notConfigured flag, not a generic error`() = runTest {
        val fake = FakeCoreGateway(seerrHomeResult = Result.failure(CoreException.SeerrNotConfigured()))

        val viewModel = DiscoverViewModel(fake)
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

        val viewModel = DiscoverViewModel(gateway)
        advanceUntilIdle()
        assertEquals("server unreachable", viewModel.state.value.error)

        viewModel.retry()
        advanceUntilIdle()

        assertEquals(null, viewModel.state.value.error)
        assertEquals(listOf(row), viewModel.state.value.rows)
    }
}
