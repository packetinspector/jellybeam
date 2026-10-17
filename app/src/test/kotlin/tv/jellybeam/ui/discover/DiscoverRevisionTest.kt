package tv.jellybeam.ui.discover

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CompletableDeferred
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
import tv.jellybeam.i18n.ResourceUiStrings
import uniffi.jellybeam_core.CoreException
import uniffi.jellybeam_core.SeerrActiveRequest
import uniffi.jellybeam_core.SeerrAuthMethod
import uniffi.jellybeam_core.SeerrAvailability
import uniffi.jellybeam_core.SeerrCard
import uniffi.jellybeam_core.SeerrHome
import uniffi.jellybeam_core.SeerrHomeRow
import uniffi.jellybeam_core.SeerrMediaType
import uniffi.jellybeam_core.SeerrMovieDetail
import uniffi.jellybeam_core.SeerrMyRequest
import uniffi.jellybeam_core.SeerrRequestStatus
import uniffi.jellybeam_core.SeerrStatus

/**
 * Discover home and My Requests reload each time their screen returns to the top, so a change made
 * on another screen (a request in Detail, a connect in Settings) shows on Back (docs/14).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DiscoverRevisionTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun card(availability: SeerrAvailability) = SeerrCard(
        mediaType = SeerrMediaType.MOVIE,
        tmdbId = 1L,
        title = "Synthetic Movie",
        year = 2020,
        overview = null,
        posterUrl = null,
        backdropUrl = null,
        availability = availability,
        jellyfinItemId = null,
    )

    private fun home(availability: SeerrAvailability, rowId: String = "trending") =
        SeerrHome(rows = listOf(SeerrHomeRow(id = rowId, title = "Row $rowId", cards = listOf(card(availability)))))

    private fun myRequest() = SeerrMyRequest(
        requestId = 7L,
        card = card(SeerrAvailability.PENDING),
        status = SeerrRequestStatus.PENDING,
        is4k = false,
        seasons = emptyList(),
        requestedBy = null,
    )

    private fun movieDetail(availability: SeerrAvailability, activeRequest: SeerrActiveRequest?) = SeerrMovieDetail(
        card = card(availability),
        runtimeMinutes = 100,
        genres = emptyList(),
        cast = emptyList(),
        similar = emptyList(),
        recommendations = emptyList(),
        trailerUrl = null,
        criticsScore = null,
        audienceScore = null,
        activeRequest = activeRequest,
        canRequest = activeRequest == null,
        canRequest4k = false,
    )

    private fun connectedStatus() = SeerrStatus(
        configured = true,
        seerrUrl = "http://seerr.test",
        method = SeerrAuthMethod.API_KEY,
        identity = null,
        appTitle = null,
    )

    @Test
    fun `a request made in Detail shows on Discover home and My Requests on return`() = runTest {
        val fake = FakeCoreGateway(
            seerrHomeResult = Result.success(home(SeerrAvailability.NOT_REQUESTED)),
            seerrMovieResultsByTmdbId = mapOf(1L to Result.success(movieDetail(SeerrAvailability.NOT_REQUESTED, null))),
        )
        val store = ViewModelStore()
        try {
            val homeVm = DiscoverViewModel(fake, ResourceUiStrings.default).also { store.put("home", it) }
            val requestsVm = DiscoverRequestsViewModel(fake, ResourceUiStrings.default).also { store.put("requests", it) }
            val detailVm = DiscoverDetailViewModel(fake, ResourceUiStrings.default, SeerrMediaType.MOVIE, 1L).also { store.put("detail", it) }
            advanceUntilIdle()
            assertEquals(SeerrAvailability.NOT_REQUESTED, homeVm.state.value.rows.single().cards.single().availability)
            assertTrue(requestsVm.state.value.requests.isEmpty())

            homeVm.setActive(false)
            requestsVm.setActive(false)
            fake.seerrHomeResult = Result.success(home(SeerrAvailability.PENDING))
            fake.seerrMyRequestsResult = Result.success(listOf(myRequest()))
            detailVm.startRequest(false)
            advanceUntilIdle()
            homeVm.setActive(true)
            requestsVm.setActive(true)
            advanceUntilIdle()

            assertEquals(SeerrAvailability.PENDING, homeVm.state.value.rows.single().cards.single().availability)
            assertEquals(listOf(7L), requestsVm.state.value.requests.map { it.requestId })
        } finally {
            store.clear()
        }
    }

    /** Back during the POST: the lists return before the change lands, so the landing itself reloads them (docs/14). */
    @Test
    fun `a request still in flight on return shows once it lands`() = runTest {
        val fake = FakeCoreGateway(
            seerrHomeResult = Result.success(home(SeerrAvailability.NOT_REQUESTED)),
            seerrMovieResultsByTmdbId = mapOf(1L to Result.success(movieDetail(SeerrAvailability.NOT_REQUESTED, null))),
        )
        val store = ViewModelStore()
        try {
            val homeVm = DiscoverViewModel(fake, ResourceUiStrings.default).also { store.put("home", it) }
            val requestsVm = DiscoverRequestsViewModel(fake, ResourceUiStrings.default).also { store.put("requests", it) }
            val detailVm = DiscoverDetailViewModel(fake, ResourceUiStrings.default, SeerrMediaType.MOVIE, 1L).also { store.put("detail", it) }
            advanceUntilIdle()
            homeVm.setActive(false)
            requestsVm.setActive(false)
            val submit = CompletableDeferred<Unit>().also { fake.seerrSubmitRequestGate = it }
            detailVm.startRequest(false)
            advanceUntilIdle()
            homeVm.setActive(true)
            requestsVm.setActive(true)
            advanceUntilIdle()

            fake.seerrHomeResult = Result.success(home(SeerrAvailability.PENDING))
            fake.seerrMyRequestsResult = Result.success(listOf(myRequest()))
            submit.complete(Unit)
            advanceUntilIdle()

            assertEquals(SeerrAvailability.PENDING, homeVm.state.value.rows.single().cards.single().availability)
            assertEquals(listOf(7L), requestsVm.state.value.requests.map { it.requestId })
        } finally {
            store.clear()
        }
    }

    @Test
    fun `a request cancelled in Detail clears from Discover home and My Requests on return`() = runTest {
        val active = SeerrActiveRequest(requestId = 7L, status = SeerrRequestStatus.PENDING, is4k = false, seasons = emptyList())
        val fake = FakeCoreGateway(
            seerrHomeResult = Result.success(home(SeerrAvailability.PENDING)),
            seerrMyRequestsResult = Result.success(listOf(myRequest())),
            seerrMovieResultsByTmdbId = mapOf(1L to Result.success(movieDetail(SeerrAvailability.PENDING, active))),
        )
        val store = ViewModelStore()
        try {
            val homeVm = DiscoverViewModel(fake, ResourceUiStrings.default).also { store.put("home", it) }
            val requestsVm = DiscoverRequestsViewModel(fake, ResourceUiStrings.default).also { store.put("requests", it) }
            val detailVm = DiscoverDetailViewModel(fake, ResourceUiStrings.default, SeerrMediaType.MOVIE, 1L).also { store.put("detail", it) }
            advanceUntilIdle()
            assertEquals(1, requestsVm.state.value.requests.size)

            homeVm.setActive(false)
            requestsVm.setActive(false)
            fake.seerrHomeResult = Result.success(home(SeerrAvailability.NOT_REQUESTED))
            fake.seerrMyRequestsResult = Result.success(emptyList())
            detailVm.confirmCancel()
            advanceUntilIdle()
            homeVm.setActive(true)
            requestsVm.setActive(true)
            advanceUntilIdle()

            assertEquals(SeerrAvailability.NOT_REQUESTED, homeVm.state.value.rows.single().cards.single().availability)
            assertTrue(requestsVm.state.value.requests.isEmpty())
        } finally {
            store.clear()
        }
    }

    @Test
    fun `reconnecting Seerr to another server reloads Discover home rows`() = runTest {
        val fake = FakeCoreGateway(
            seerrHomeResult = Result.success(home(SeerrAvailability.NOT_REQUESTED, rowId = "server-a")),
            seerrConnectResult = Result.success(connectedStatus()),
        )
        val store = ViewModelStore()
        try {
            val homeVm = DiscoverViewModel(fake, ResourceUiStrings.default).also { store.put("home", it) }
            advanceUntilIdle()
            assertEquals("server-a", homeVm.state.value.rows.single().id)

            homeVm.setActive(false)
            fake.seerrHomeResult = Result.success(home(SeerrAvailability.AVAILABLE, rowId = "server-b"))
            fake.seerrConnect("http://other-seerr.test", SeerrAuthMethod.API_KEY, "", "synthetic-key")
            homeVm.setActive(true)
            advanceUntilIdle()

            assertEquals("server-b", homeVm.state.value.rows.single().id)
        } finally {
            store.clear()
        }
    }

    @Test
    fun `disconnecting Seerr reloads Discover home into the not configured state`() = runTest {
        val fake = FakeCoreGateway(seerrHomeResult = Result.success(home(SeerrAvailability.NOT_REQUESTED)))
        val store = ViewModelStore()
        try {
            val homeVm = DiscoverViewModel(fake, ResourceUiStrings.default).also { store.put("home", it) }
            advanceUntilIdle()

            homeVm.setActive(false)
            fake.seerrHomeResult = Result.failure(CoreException.SeerrNotConfigured())
            fake.seerrDisconnect()
            homeVm.setActive(true)
            advanceUntilIdle()

            assertTrue(homeVm.state.value.notConfigured)
            assertTrue(homeVm.state.value.rows.isEmpty())
        } finally {
            store.clear()
        }
    }

    /** The first composition's `setActive(true)` is not a return: creation loads once. */
    @Test
    fun `a new list loads exactly once`() = runTest {
        val fake = FakeCoreGateway(seerrHomeResult = Result.success(home(SeerrAvailability.NOT_REQUESTED)))
        val store = ViewModelStore()
        try {
            DiscoverViewModel(fake, ResourceUiStrings.default).also { store.put("home", it) }.setActive(true)
            DiscoverRequestsViewModel(fake, ResourceUiStrings.default).also { store.put("requests", it) }.setActive(true)
            advanceUntilIdle()

            assertEquals(1, fake.seerrHomeCallCount)
            assertEquals(1, fake.seerrMyRequestsCallCount)
        } finally {
            store.clear()
        }
    }

    @Test
    fun `each return to the top reloads once and leaving reloads nothing`() = runTest {
        val fake = FakeCoreGateway(
            seerrHomeResult = Result.success(home(SeerrAvailability.NOT_REQUESTED)),
            seerrMyRequestsResult = Result.success(listOf(myRequest())),
        )
        val store = ViewModelStore()
        try {
            val homeVm = DiscoverViewModel(fake, ResourceUiStrings.default).also { store.put("home", it) }
            val requestsVm = DiscoverRequestsViewModel(fake, ResourceUiStrings.default).also { store.put("requests", it) }
            advanceUntilIdle()
            for (visit in 1..2) {
                homeVm.setActive(false)
                requestsVm.setActive(false)
                fake.seerrRevision.value += 1
                advanceUntilIdle()
                assertEquals("visit $visit: nothing reloads while away", visit, fake.seerrHomeCallCount)
                assertEquals("visit $visit", visit, fake.seerrMyRequestsCallCount)

                homeVm.setActive(true)
                requestsVm.setActive(true)
                advanceUntilIdle()
                assertEquals("visit $visit: one reload on return", visit + 1, fake.seerrHomeCallCount)
                assertEquals("visit $visit", visit + 1, fake.seerrMyRequestsCallCount)
            }
        } finally {
            store.clear()
        }
    }

    @Test
    fun `a list on the stack keeps its rows on screen while it reloads`() = runTest {
        val fake = FakeCoreGateway(
            seerrHomeResult = Result.success(home(SeerrAvailability.NOT_REQUESTED)),
            seerrMyRequestsResult = Result.success(listOf(myRequest())),
        )
        val store = ViewModelStore()
        try {
            val homeVm = DiscoverViewModel(fake, ResourceUiStrings.default).also { store.put("home", it) }
            val requestsVm = DiscoverRequestsViewModel(fake, ResourceUiStrings.default).also { store.put("requests", it) }
            advanceUntilIdle()
            val homeGate = CompletableDeferred<Unit>().also { fake.seerrHomeGate = it }
            val requestsGate = CompletableDeferred<Unit>().also { fake.seerrMyRequestsGate = it }

            homeVm.setActive(false)
            requestsVm.setActive(false)
            homeVm.setActive(true)
            requestsVm.setActive(true)
            advanceUntilIdle()

            assertEquals(2, fake.seerrHomeCallCount)
            assertFalse("rows stay up during the reload", homeVm.state.value.isLoading)
            assertTrue(homeVm.state.value.rows.isNotEmpty())
            assertFalse("rows stay up during the reload", requestsVm.state.value.isLoading)
            assertTrue(requestsVm.state.value.requests.isNotEmpty())
            homeGate.complete(Unit)
            requestsGate.complete(Unit)
        } finally {
            store.clear()
        }
    }

    @Test
    fun `an empty list shows its skeleton while it loads`() = runTest {
        val fake = FakeCoreGateway(seerrHomeResult = Result.success(SeerrHome(rows = emptyList())))
        val gate = CompletableDeferred<Unit>().also { fake.seerrHomeGate = it }
        val store = ViewModelStore()
        try {
            val homeVm = DiscoverViewModel(fake, ResourceUiStrings.default).also { store.put("home", it) }
            advanceUntilIdle()

            assertTrue(homeVm.state.value.isLoading)
            gate.complete(Unit)
        } finally {
            store.clear()
        }
    }
}
