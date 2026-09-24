package tv.jellybeam.ui.discover

import tv.jellybeam.MainDispatcherRule
import tv.jellybeam.data.FakeCoreGateway
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uniffi.jellybeam_core.SeerrAvailability
import uniffi.jellybeam_core.SeerrCard
import uniffi.jellybeam_core.SeerrMediaType
import uniffi.jellybeam_core.SeerrMovieDetail
import uniffi.jellybeam_core.SeerrProfile
import uniffi.jellybeam_core.SeerrRequestOptions
import uniffi.jellybeam_core.SeerrRootFolder
import uniffi.jellybeam_core.SeerrSeasonStatus
import uniffi.jellybeam_core.SeerrServiceServer
import uniffi.jellybeam_core.SeerrTvDetail

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoverDetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun card(tmdbId: Long, mediaType: SeerrMediaType, title: String = "Title") = SeerrCard(
        mediaType = mediaType,
        tmdbId = tmdbId,
        title = title,
        year = 2020,
        overview = null,
        posterUrl = null,
        backdropUrl = null,
        availability = SeerrAvailability.NOT_REQUESTED,
        jellyfinItemId = null,
    )

    private fun movieDetail(tmdbId: Long) = SeerrMovieDetail(
        card = card(tmdbId, SeerrMediaType.MOVIE),
        runtimeMinutes = 120,
        genres = emptyList(),
        cast = emptyList(),
        similar = emptyList(),
        recommendations = emptyList(),
        trailerUrl = null,
        criticsScore = null,
        audienceScore = null,
        activeRequest = null,
        canRequest = true,
        canRequest4k = false,
    )

    private fun season(number: Int, requestable: Boolean = true) = SeerrSeasonStatus(
        seasonNumber = number,
        name = "Season $number",
        episodeCount = 10,
        availability = SeerrAvailability.NOT_REQUESTED,
        requestable = requestable,
    )

    private fun tvDetail(tmdbId: Long, seasons: List<SeerrSeasonStatus>) = SeerrTvDetail(
        card = card(tmdbId, SeerrMediaType.TV),
        genres = emptyList(),
        cast = emptyList(),
        similar = emptyList(),
        recommendations = emptyList(),
        trailerUrl = null,
        criticsScore = null,
        audienceScore = null,
        activeRequest = null,
        canRequest = true,
        canRequest4k = false,
        seasons = seasons,
    )

    @Test
    fun `movie with empty request options submits immediately, no dialog`() = runTest {
        val fake = FakeCoreGateway(
            seerrMovieResultsByTmdbId = mapOf(1L to Result.success(movieDetail(1L))),
            seerrRequestOptionsResult = Result.success(SeerrRequestOptions(servers = emptyList())),
        )
        val viewModel = DiscoverDetailViewModel(fake, SeerrMediaType.MOVIE, 1L)
        advanceUntilIdle()

        viewModel.startRequest(false)
        advanceUntilIdle()

        assertFalse("movie must never show a dialog when there is nothing to pick", viewModel.state.value.requestDialogVisible)
        assertEquals(1, fake.seerrSubmitRequestCalls.size)
        assertEquals(emptyList<Int>(), fake.seerrSubmitRequestCalls.single().seasons)
        assertEquals(DiscoverDetailTransientEvent.REQUEST_SUBMITTED, viewModel.state.value.transientEvent)
    }

    @Test
    fun `TV with empty request options opens the season dialog instead of submitting`() = runTest {
        val seasons = listOf(season(1), season(2))
        val fake = FakeCoreGateway(
            seerrTvResultsByTmdbId = mapOf(2L to Result.success(tvDetail(2L, seasons))),
            seerrRequestOptionsResult = Result.success(SeerrRequestOptions(servers = emptyList())),
        )
        val viewModel = DiscoverDetailViewModel(fake, SeerrMediaType.TV, 2L)
        advanceUntilIdle()

        viewModel.startRequest(false)
        advanceUntilIdle()

        assertTrue("TV must always open the season picker, even with no servers", viewModel.state.value.requestDialogVisible)
        assertNull("requestOptions stays null rather than a fabricated non-null value", viewModel.state.value.requestOptions)
        assertTrue("nothing is submitted just from opening the dialog", fake.seerrSubmitRequestCalls.isEmpty())
    }

    @Test
    fun `TV with a failed request options fetch still opens the season dialog`() = runTest {
        val seasons = listOf(season(1))
        val fake = FakeCoreGateway(
            seerrTvResultsByTmdbId = mapOf(3L to Result.success(tvDetail(3L, seasons))),
            seerrRequestOptionsResult = Result.failure(uniffi.jellybeam_core.CoreException.Api(detail = "unreachable")),
        )
        val viewModel = DiscoverDetailViewModel(fake, SeerrMediaType.TV, 3L)
        advanceUntilIdle()

        viewModel.startRequest(false)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.requestDialogVisible)
        assertNull(viewModel.state.value.requestOptions)
        assertTrue(fake.seerrSubmitRequestCalls.isEmpty())
    }

    @Test
    fun `confirming the TV dialog submits only the selected requestable seasons`() = runTest {
        val seasons = listOf(season(1), season(2), season(3, requestable = false))
        val fake = FakeCoreGateway(
            seerrTvResultsByTmdbId = mapOf(4L to Result.success(tvDetail(4L, seasons))),
            seerrRequestOptionsResult = Result.success(SeerrRequestOptions(servers = emptyList())),
        )
        val viewModel = DiscoverDetailViewModel(fake, SeerrMediaType.TV, 4L)
        advanceUntilIdle()

        viewModel.startRequest(false)
        advanceUntilIdle()
        assertTrue(fake.seerrSubmitRequestCalls.isEmpty())

        viewModel.toggleSeason(seasons[0])
        viewModel.confirmRequestDialog()
        advanceUntilIdle()

        assertEquals(1, fake.seerrSubmitRequestCalls.size)
        assertEquals(listOf(1), fake.seerrSubmitRequestCalls.single().seasons)
        assertFalse(viewModel.state.value.requestDialogVisible)
    }

    @Test
    fun `TV with a server present seeds server, profile, and folder defaults`() = runTest {
        val seasons = listOf(season(1))
        val server = SeerrServiceServer(
            serverId = 9L,
            name = "Sonarr",
            is4k = false,
            isDefault = true,
            profiles = listOf(SeerrProfile(id = 5L, name = "HD-1080p", isDefault = true)),
            rootFolders = listOf(SeerrRootFolder(id = 1L, path = "/tv", isDefault = true)),
        )
        val fake = FakeCoreGateway(
            seerrTvResultsByTmdbId = mapOf(5L to Result.success(tvDetail(5L, seasons))),
            seerrRequestOptionsResult = Result.success(SeerrRequestOptions(servers = listOf(server))),
        )
        val viewModel = DiscoverDetailViewModel(fake, SeerrMediaType.TV, 5L)
        advanceUntilIdle()

        viewModel.startRequest(false)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.requestDialogVisible)
        assertEquals(9L, viewModel.state.value.selectedServerId)
        assertEquals(5L, viewModel.state.value.selectedProfileId)
        assertEquals("/tv", viewModel.state.value.selectedRootFolder)
    }

    @Test
    fun `submit is disabled with no season selected and enabled once one is picked`() {
        val seasons = listOf(season(1), season(2))
        val emptySelection = initialSeasonSelection(seasons)
        assertFalse(canSubmitSeasonRequest(seasons, emptySelection))

        val withOne = toggleSeasonSelection(emptySelection, seasons[0])
        assertTrue(canSubmitSeasonRequest(seasons, withOne))
    }
}
