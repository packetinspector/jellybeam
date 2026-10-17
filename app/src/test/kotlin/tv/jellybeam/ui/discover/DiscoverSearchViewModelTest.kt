package tv.jellybeam.ui.discover

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import tv.jellybeam.MainDispatcherRule
import tv.jellybeam.data.FakeCoreGateway
import tv.jellybeam.data.emptySeerrPage
import uniffi.jellybeam_core.CoreException
import uniffi.jellybeam_core.SeerrAvailability
import uniffi.jellybeam_core.SeerrCard
import uniffi.jellybeam_core.SeerrMediaType
import uniffi.jellybeam_core.SeerrPage

/** Discover search tells a failed search apart from an empty one (docs/14). */
@OptIn(ExperimentalCoroutinesApi::class)
class DiscoverSearchViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun card() = SeerrCard(
        mediaType = SeerrMediaType.MOVIE,
        tmdbId = 1L,
        title = "Synthetic Movie",
        year = 2020,
        overview = null,
        posterUrl = null,
        backdropUrl = null,
        availability = SeerrAvailability.NOT_REQUESTED,
        jellyfinItemId = null,
    )

    @Test
    fun `a failed search is unavailable, not an empty result`() = runTest {
        val fake = FakeCoreGateway(seerrSearchResult = Result.failure(CoreException.Api(detail = "seerr down")))
        val store = ViewModelStore()
        try {
            val vm = DiscoverSearchViewModel(fake).also { store.put("search", it) }

            vm.onQueryChange("synthetic")
            advanceUntilIdle()

            val state = vm.state.value
            assertEquals(1, fake.seerrSearchCalls.size)
            assertFalse(state.isSearching)
            assertTrue(state.results.isEmpty())
            assertTrue("a failure must not read as No results", state.unavailable)
        } finally {
            store.clear()
        }
    }

    @Test
    fun `an empty successful search is not unavailable`() = runTest {
        val fake = FakeCoreGateway(seerrSearchResult = Result.success(emptySeerrPage()))
        val store = ViewModelStore()
        try {
            val vm = DiscoverSearchViewModel(fake).also { store.put("search", it) }

            vm.onQueryChange("synthetic")
            advanceUntilIdle()

            assertFalse(vm.state.value.isSearching)
            assertFalse(vm.state.value.unavailable)
        } finally {
            store.clear()
        }
    }

    @Test
    fun `the next successful search clears unavailable`() = runTest {
        val fake = FakeCoreGateway(seerrSearchResult = Result.failure(CoreException.Api(detail = "seerr down")))
        val store = ViewModelStore()
        try {
            val vm = DiscoverSearchViewModel(fake).also { store.put("search", it) }
            vm.onQueryChange("synthetic")
            advanceUntilIdle()
            assertTrue(vm.state.value.unavailable)

            fake.seerrSearchResult = Result.success(SeerrPage(cards = listOf(card()), page = 1, totalPages = 1, totalResults = 1))
            vm.onQueryChange("synthetic two")
            advanceUntilIdle()

            assertFalse(vm.state.value.unavailable)
            assertEquals(1, vm.state.value.results.size)
        } finally {
            store.clear()
        }
    }
}
