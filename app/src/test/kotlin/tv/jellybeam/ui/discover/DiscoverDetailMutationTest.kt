package tv.jellybeam.ui.discover

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import tv.jellybeam.MainDispatcherRule
import tv.jellybeam.data.FakeCoreGateway
import tv.jellybeam.data.displayMessage
import tv.jellybeam.i18n.ResourceUiStrings
import uniffi.jellybeam_core.CoreException
import uniffi.jellybeam_core.SeerrActiveRequest
import uniffi.jellybeam_core.SeerrAvailability
import uniffi.jellybeam_core.SeerrCard
import uniffi.jellybeam_core.SeerrMediaType
import uniffi.jellybeam_core.SeerrMovieDetail
import uniffi.jellybeam_core.SeerrRequestDetail
import uniffi.jellybeam_core.SeerrRequestStatus
import uniffi.jellybeam_core.SeerrSeasonStatus
import uniffi.jellybeam_core.SeerrTvDetail

/**
 * Detail's one mutation funnel (docs/14): busy holds until the returned detail is applied, one
 * predicate guards every entry, a failed mutation shows its error beside the card with the actions live.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DiscoverDetailMutationTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun card(mediaType: SeerrMediaType) = SeerrCard(
        mediaType = mediaType,
        tmdbId = 1L,
        title = "Synthetic Title",
        year = 2020,
        overview = null,
        posterUrl = null,
        backdropUrl = null,
        availability = SeerrAvailability.NOT_REQUESTED,
        jellyfinItemId = null,
    )

    private fun movieDetail(activeRequest: SeerrActiveRequest? = null, similar: List<SeerrCard> = emptyList()) = SeerrMovieDetail(
        card = card(SeerrMediaType.MOVIE),
        runtimeMinutes = 100,
        genres = emptyList(),
        cast = emptyList(),
        similar = similar,
        recommendations = emptyList(),
        trailerUrl = null,
        criticsScore = null,
        audienceScore = null,
        activeRequest = activeRequest,
        canRequest = activeRequest == null,
        canRequest4k = false,
    )

    private fun tvDetail(activeRequest: SeerrActiveRequest? = null) = SeerrTvDetail(
        card = card(SeerrMediaType.TV),
        genres = emptyList(),
        cast = emptyList(),
        similar = emptyList(),
        recommendations = emptyList(),
        trailerUrl = null,
        criticsScore = null,
        audienceScore = null,
        activeRequest = activeRequest,
        canRequest = true,
        canRequest4k = false,
        seasons = listOf(
            SeerrSeasonStatus(seasonNumber = 1, name = "Season 1", episodeCount = 10, availability = SeerrAvailability.NOT_REQUESTED, requestable = true),
        ),
    )

    private val active = SeerrActiveRequest(requestId = 7L, status = SeerrRequestStatus.PENDING, is4k = false, seasons = emptyList())

    private val refusal = CoreException.Api(detail = "refused")

    /** A started view model plus the store that clears it; [close] runs in the test's `finally`. */
    private class Harness(val fake: FakeCoreGateway, val vm: DiscoverDetailViewModel, private val store: ViewModelStore) {
        fun close() = store.clear()
    }

    private fun harness(fake: FakeCoreGateway, mediaType: SeerrMediaType = SeerrMediaType.MOVIE): Harness {
        val store = ViewModelStore()
        val vm = DiscoverDetailViewModel(fake, ResourceUiStrings.default, mediaType, 1L).also { store.put("detail", it) }
        return Harness(fake, vm, store)
    }

    @Test
    fun `a second Request while the first submit is in flight posts exactly once`() = runTest {
        val fake = FakeCoreGateway(seerrMovieResultsByTmdbId = mapOf(1L to Result.success(movieDetail())))
        val h = harness(fake)
        try {
            advanceUntilIdle()
            val submit = CompletableDeferred<Unit>().also { fake.seerrSubmitRequestGate = it }

            h.vm.startRequest(false)
            h.vm.startRequest(false)
            advanceUntilIdle()
            assertEquals(1, fake.seerrSubmitRequestCalls.size)
            assertTrue("busy holds until the returned detail is applied", h.vm.state.value.busy)

            submit.complete(Unit)
            advanceUntilIdle()

            assertEquals(1, fake.seerrSubmitRequestCalls.size)
            assertFalse(h.vm.state.value.busy)
            assertEquals(DiscoverDetailTransientEvent.REQUEST_SUBMITTED, h.vm.state.value.transientEvent)
        } finally {
            h.close()
        }
    }

    @Test
    fun `a second confirm while the first cancel is in flight deletes exactly once`() = runTest {
        val fake = FakeCoreGateway(seerrMovieResultsByTmdbId = mapOf(1L to Result.success(movieDetail(activeRequest = active))))
        val h = harness(fake)
        try {
            advanceUntilIdle()
            val cancel = CompletableDeferred<Unit>().also { fake.seerrCancelRequestGate = it }

            h.vm.confirmCancel()
            h.vm.confirmCancel()
            advanceUntilIdle()
            assertEquals(listOf(7L), fake.seerrCancelRequestCalls)
            assertTrue("busy holds until the returned detail is applied", h.vm.state.value.busy)

            cancel.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf(7L), fake.seerrCancelRequestCalls)
            assertFalse(h.vm.state.value.busy)
            assertEquals(DiscoverDetailTransientEvent.REQUEST_CANCELLED, h.vm.state.value.transientEvent)
        } finally {
            h.close()
        }
    }

    @Test
    fun `a successful submit merges the request fields and keeps similar titles and no refetch is made`() = runTest {
        val similar = listOf(card(SeerrMediaType.MOVIE).copy(tmdbId = 2L))
        val fake = FakeCoreGateway(seerrMovieResultsByTmdbId = mapOf(1L to Result.success(movieDetail(similar = similar))))
        val h = harness(fake)
        try {
            advanceUntilIdle()
            assertNull(h.vm.state.value.activeRequest)

            h.vm.startRequest(false)
            advanceUntilIdle()

            val state = h.vm.state.value
            assertNotNull("the submitted request is on the detail", state.activeRequest)
            assertFalse(state.canRequest)
            assertEquals("the returned detail has no similar list; the one on screen stays", similar, state.similar)
            assertEquals("the title is read once, on open", listOf(1L), fake.seerrMovieCalls)
        } finally {
            h.close()
        }
    }

    @Test
    fun `a successful TV submit takes the returned seasons`() = runTest {
        val returned = tvDetail(activeRequest = active).copy(
            seasons = listOf(SeerrSeasonStatus(seasonNumber = 1, name = "Season 1", episodeCount = 10, availability = SeerrAvailability.PENDING, requestable = false)),
        )
        val fake = FakeCoreGateway(
            seerrTvResultsByTmdbId = mapOf(1L to Result.success(tvDetail())),
            seerrSubmitRequestResult = Result.success(SeerrRequestDetail.Tv(returned)),
        )
        val h = harness(fake, SeerrMediaType.TV)
        try {
            advanceUntilIdle()
            h.vm.startRequest(false)
            advanceUntilIdle()
            h.vm.confirmRequestDialog()
            advanceUntilIdle()

            assertEquals(returned.seasons, h.vm.state.value.seasons)
            assertNotNull(h.vm.state.value.activeRequest)
        } finally {
            h.close()
        }
    }

    @Test
    fun `a failed cancel shows a visible error while the card stays on screen`() = runTest {
        val fake = FakeCoreGateway(
            seerrMovieResultsByTmdbId = mapOf(1L to Result.success(movieDetail(activeRequest = active))),
            seerrCancelRequestResult = Result.failure(CoreException.Api(detail = "cancel refused")),
        )
        val h = harness(fake)
        try {
            advanceUntilIdle()

            h.vm.confirmCancel()
            advanceUntilIdle()

            val state = h.vm.state.value
            assertNotNull("the card stays so the error is not on the card-less error screen", state.card)
            assertEquals("cancel refused", state.cancelError)
            assertFalse(state.busy)
            assertNotNull("the request is still active after a failed cancel", state.activeRequest)
            assertNull(state.transientEvent)
            assertTrue("Cancel stays live", state.actionSlots().single { it.id == ACTION_CANCEL_REQUEST }.actionable)
        } finally {
            h.close()
        }
    }

    @Test
    fun `Back during a submit is held so a failure shows in the dialog`() = runTest {
        // Contract: Back matches the disabled Cancel pill while submitting (DiscoverDetailScreen).
        val fake = FakeCoreGateway(
            seerrTvResultsByTmdbId = mapOf(1L to Result.success(tvDetail())),
            seerrSubmitRequestResult = Result.failure(CoreException.Api(detail = "submit refused")),
        )
        val h = harness(fake, SeerrMediaType.TV)
        try {
            advanceUntilIdle()
            h.vm.startRequest(false)
            advanceUntilIdle()
            assertTrue(h.vm.state.value.requestDialogVisible)
            h.vm.toggleSeason(h.vm.state.value.seasons.single())
            val submit = CompletableDeferred<Unit>().also { fake.seerrSubmitRequestGate = it }

            h.vm.confirmRequestDialog()
            assertTrue(h.vm.state.value.busy)
            h.vm.dismissRequestDialog()
            assertTrue("Back is held while the submit is in flight", h.vm.state.value.requestDialogVisible)

            submit.complete(Unit)
            advanceUntilIdle()

            val state = h.vm.state.value
            assertEquals("submit refused", state.requestError)
            assertTrue("the error is shown where the user is", state.requestDialogVisible)
            assertFalse(state.busy)
            assertEquals("the failed submit keeps the picked seasons", mapOf(1 to true), state.seasonSelection)

            h.vm.dismissRequestDialog()
            assertFalse("Back closes the dialog once nothing is in flight", h.vm.state.value.requestDialogVisible)
        } finally {
            h.close()
        }
    }

    /** Both dialogs open, then one mutation or options fetch in flight: no entry may close a dialog or start a second call. */
    @Test
    fun `no entry closes a dialog or starts a call while anything is in flight`() = runTest {
        for (busy in listOf("starting", "submitting", "cancelling")) {
            val fake = FakeCoreGateway(seerrTvResultsByTmdbId = mapOf(1L to Result.success(tvDetail(activeRequest = active))))
            val h = harness(fake, SeerrMediaType.TV)
            try {
                advanceUntilIdle()
                h.vm.startRequest(false)
                advanceUntilIdle()
                h.vm.toggleSeason(h.vm.state.value.seasons.single())
                h.vm.openCancelDialog()
                assertTrue(h.vm.state.value.requestDialogVisible && h.vm.state.value.cancelDialogVisible)
                val gate = CompletableDeferred<Unit>()
                when (busy) {
                    "starting" -> { fake.seerrRequestOptionsGate = gate; h.vm.startRequest(false) }
                    "submitting" -> { fake.seerrSubmitRequestGate = gate; h.vm.confirmRequestDialog() }
                    else -> { fake.seerrCancelRequestGate = gate; h.vm.confirmCancel() }
                }
                advanceUntilIdle()
                assertTrue(busy, h.vm.state.value.busy)
                val before = h.vm.state.value
                val options = fake.seerrRequestOptionsCalls.size
                val submits = fake.seerrSubmitRequestCalls.size
                val cancels = fake.seerrCancelRequestCalls.size

                h.vm.dismissRequestDialog()
                h.vm.dismissCancelDialog()
                h.vm.openCancelDialog()
                h.vm.startRequest(false)
                h.vm.startRequest(true)
                h.vm.confirmRequestDialog()
                h.vm.confirmCancel()
                advanceUntilIdle()

                assertEquals("busy=$busy", before, h.vm.state.value)
                assertEquals("busy=$busy", options, fake.seerrRequestOptionsCalls.size)
                assertEquals("busy=$busy", submits, fake.seerrSubmitRequestCalls.size)
                assertEquals("busy=$busy", cancels, fake.seerrCancelRequestCalls.size)
                gate.complete(Unit)
            } finally {
                h.close()
            }
        }
    }

    /** docs/14 Errors: a failed Request shows its error beside the card, Request stays live, and the next press sends one POST. */
    @Test
    fun `a failed Request shows its error, keeps Request live, and a second press posts once`() = runTest {
        val fake = FakeCoreGateway(
            seerrMovieResultsByTmdbId = mapOf(1L to Result.success(movieDetail())),
            seerrSubmitRequestResult = Result.failure(refusal),
        )
        val h = harness(fake)
        try {
            advanceUntilIdle()
            h.vm.startRequest(false)
            advanceUntilIdle()

            val state = h.vm.state.value
            assertEquals("refused", state.requestError)
            assertNotNull("the card stays, so the error renders beside it", state.card)
            assertFalse(state.busy)
            assertTrue("Request stays live", state.actionSlots().single { it.id == ACTION_REQUEST }.actionable)
            assertEquals(1, fake.seerrSubmitRequestCalls.size)

            fake.seerrSubmitRequestResult = null
            h.vm.startRequest(false)
            advanceUntilIdle()

            assertEquals("the second press sends one POST", 2, fake.seerrSubmitRequestCalls.size)
            assertNull(h.vm.state.value.requestError)
            assertNotNull(h.vm.state.value.activeRequest)
        } finally {
            h.close()
        }
    }

    /** Failure x which action, with the re-read failing too: both messages are on the page and the actions stay live. */
    @Test
    fun `a failed mutation whose re-read also fails keeps its error and the actions live`() = runTest {
        for (cancel in listOf(false, true)) {
            val results = mutableMapOf(1L to Result.success(movieDetail(activeRequest = if (cancel) active else null)))
            val fake = FakeCoreGateway(
                seerrMovieResultsByTmdbId = results,
                seerrSubmitRequestResult = Result.failure(refusal),
                seerrCancelRequestResult = Result.failure(refusal),
            )
            val h = harness(fake)
            try {
                advanceUntilIdle()
                results[1L] = Result.failure(CoreException.Api(detail = "unreadable"))

                if (cancel) h.vm.confirmCancel() else h.vm.startRequest(false)
                advanceUntilIdle()

                val state = h.vm.state.value
                assertEquals("cancel=$cancel", "refused", if (cancel) state.cancelError else state.requestError)
                assertEquals("cancel=$cancel", "unreadable", state.error)
                assertNotNull("the card stays", state.card)
                assertNull(state.transientEvent)
                assertFalse("cancel=$cancel: busy is released", state.busy)
                assertTrue("cancel=$cancel: the actions stay live", state.actionSlots().all { it.actionable })
            } finally {
                h.close()
            }
        }
    }

    @Test
    fun `clearing the view model mid-mutation publishes nothing`() = runTest {
        val fake = FakeCoreGateway(seerrMovieResultsByTmdbId = mapOf(1L to Result.success(movieDetail())))
        val h = harness(fake)
        advanceUntilIdle()
        val submit = CompletableDeferred<Unit>().also { fake.seerrSubmitRequestGate = it }
        h.vm.startRequest(false)
        advanceUntilIdle()
        val before = h.vm.state.value
        assertTrue(before.busy)

        h.close()
        submit.complete(Unit)
        advanceUntilIdle()

        assertEquals(before, h.vm.state.value)
    }

    /**
     * docs/14 Errors: the server changed but the title cannot be read back. Both dialogs close and
     * the error shows beside the card with the actions live.
     */
    @Test
    fun `a change that landed but could not be read back shows its error with the actions live`() = runTest {
        for (cancel in listOf(false, true)) {
            val unconfirmed = Result.failure<SeerrRequestDetail>(CoreException.SeerrChangeUnconfirmed())
            val results = mutableMapOf(1L to Result.success(movieDetail(activeRequest = if (cancel) active else null)))
            val fake = FakeCoreGateway(
                seerrMovieResultsByTmdbId = results,
                seerrSubmitRequestResult = unconfirmed,
                seerrCancelRequestResult = unconfirmed,
            )
            val h = harness(fake)
            try {
                advanceUntilIdle()
                results[1L] = Result.failure(CoreException.Api(detail = "unreadable"))

                if (cancel) {
                    h.vm.openCancelDialog()
                    h.vm.confirmCancel()
                } else {
                    h.vm.startRequest(false)
                }
                advanceUntilIdle()

                val state = h.vm.state.value
                val label = "cancel=$cancel"
                assertEquals(label, 1L, fake.seerrRevision.value)
                assertNotNull("$label: the card stays", state.card)
                assertEquals(
                    "$label: the error says the change landed",
                    CoreException.SeerrChangeUnconfirmed().displayMessage(ResourceUiStrings.default),
                    state.error,
                )
                assertTrue("$label: the actions stay live", state.actionSlots().all { it.actionable })
                assertFalse(label, state.requestDialogVisible)
                assertFalse(label, state.cancelDialogVisible)
                assertNull(label, state.transientEvent)
                assertFalse(label, state.busy)
            } finally {
                h.close()
            }
        }
    }

    /**
     * docs/15 §2: Retry replaces the page, so focus lands afresh; the landing count moves in the
     * same state as the loading flag, so the screen never sees one without the other.
     */
    @Test
    fun `Retry starts a new focus landing in the same state that starts the load, a quiet re-read does not`() = runTest {
        val results = mutableMapOf(1L to Result.success(movieDetail()))
        val fake = FakeCoreGateway(
            seerrMovieResultsByTmdbId = results,
            seerrSubmitRequestResult = Result.failure(CoreException.Api(detail = "refused")),
        )
        val h = harness(fake)
        try {
            advanceUntilIdle()
            val first = h.vm.state.value.loadGeneration

            // A failed mutation re-reads quietly: the page stays, no new landing.
            h.vm.startRequest(false)
            advanceUntilIdle()
            assertEquals(first, h.vm.state.value.loadGeneration)

            val seen = mutableListOf<DiscoverDetailUiState>()
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { h.vm.state.collect { seen += it } }
            h.vm.retry()
            advanceUntilIdle()
            collector.cancel()

            val loading = seen.filter { it.isLoading }
            assertTrue("Retry shows the loading page", loading.isNotEmpty())
            assertTrue("every loading state carries the new landing", loading.all { it.loadGeneration == first + 1 })
            assertFalse(h.vm.state.value.isLoading)
            assertEquals(first + 1, h.vm.state.value.loadGeneration)
        } finally {
            h.close()
        }
    }

    /** The change landed and the immediate re-read worked: it is a success, not an error. */
    @Test
    fun `a change that landed and is read back by the re-read confirms like a success`() = runTest {
        val fake = FakeCoreGateway(
            seerrMovieResultsByTmdbId = mapOf(1L to Result.success(movieDetail(activeRequest = active))),
            seerrCancelRequestResult = Result.failure(CoreException.SeerrChangeUnconfirmed()),
        )
        val h = harness(fake)
        try {
            advanceUntilIdle()

            h.vm.openCancelDialog()
            h.vm.confirmCancel()
            advanceUntilIdle()

            val state = h.vm.state.value
            assertEquals(DiscoverDetailTransientEvent.REQUEST_CANCELLED, state.transientEvent)
            assertNull(state.cancelError)
            assertNull(state.error)
            assertFalse(state.cancelDialogVisible)
            assertFalse(state.busy)
            assertEquals(1L, fake.seerrRevision.value)
        } finally {
            h.close()
        }
    }
}
