package tv.jellybeam.ui.detail

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import tv.jellybeam.data.CoreGateway
import tv.jellybeam.data.FakeCoreGateway
import tv.jellybeam.data.notConfiguredSeerrStatus
import tv.jellybeam.i18n.ResourceUiStrings
import tv.jellybeam.ui.cards.testCard
import uniffi.jellybeam_core.Card
import uniffi.jellybeam_core.ChangeEvent
import uniffi.jellybeam_core.CoreException
import uniffi.jellybeam_core.PersonPage
import uniffi.jellybeam_core.SeerrAvailability
import uniffi.jellybeam_core.SeerrCard
import uniffi.jellybeam_core.SeerrMediaType
import uniffi.jellybeam_core.TitleTmdbRef
import uniffi.jellybeam_core.UnreachableReason

@OptIn(ExperimentalCoroutinesApi::class)
class PersonViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val ref = TitleTmdbRef(mediaType = SeerrMediaType.MOVIE, tmdbId = 11L)
    private val seerrConfigured = notConfiguredSeerrStatus().copy(configured = true)

    private fun page(tmdbPersonId: Long?, libraryTmdb: List<TitleTmdbRef>? = listOf(ref)) = PersonPage(
        id = "person-1",
        name = "Sample Person",
        overview = null,
        primaryImageTag = null,
        birthDate = null,
        deathDate = null,
        birthPlace = null,
        tmdbPersonId = tmdbPersonId,
        library = emptyList(),
        libraryTmdb = libraryTmdb,
    )

    private fun card(tmdbId: Long) = SeerrCard(
        mediaType = SeerrMediaType.MOVIE,
        tmdbId = tmdbId,
        title = "Title $tmdbId",
        year = 2020,
        overview = null,
        posterUrl = null,
        backdropUrl = null,
        availability = SeerrAvailability.NOT_REQUESTED,
        jellyfinItemId = null,
    )

    @Test
    fun `loads the page, then the Seerr row built from the page's library ids`() = runTest {
        val gateway = FakeCoreGateway(
            personPageResultsById = mapOf("person-1" to Result.success(page(tmdbPersonId = 4242L))),
            personDiscoverResultsByTmdbId = mapOf(4242L to Result.success(listOf(card(1), card(2), card(1)))),
            seerrStatusValue = seerrConfigured,
        )
        val viewModel = PersonViewModel(gateway, ResourceUiStrings(), "person-1")
        advanceUntilIdle()

        val state = viewModel.state.value
        assertFalse(state.isLoading)
        assertEquals("Sample Person", state.page?.name)
        assertEquals(listOf(1L, 2L), state.discover.map { it.tmdbId })
        assertFalse(state.discoverPending)
        assertEquals(listOf(FakeCoreGateway.PersonDiscoverCall("person-1", 4242L, listOf(ref))), gateway.personDiscoverCalls)
    }

    @Test
    fun `a capped library leaves ownership to the optional row, so its failure keeps the page`() = runTest {
        val gateway = FakeCoreGateway(
            personPageResultsById = mapOf("person-1" to Result.success(page(tmdbPersonId = 4242L, libraryTmdb = null))),
            seerrStatusValue = seerrConfigured,
            personDiscoverResultsByTmdbId = mapOf(4242L to Result.failure(CoreException.ServerUnreachable("media.example.test:8096", UnreachableReason.TIMED_OUT, ""))),
        )
        val viewModel = PersonViewModel(gateway, ResourceUiStrings(), "person-1")
        advanceUntilIdle()

        val state = viewModel.state.value
        assertNotNull(state.page)
        assertNull(state.error)
        assertTrue(state.discover.isEmpty())
        assertEquals(listOf(FakeCoreGateway.PersonDiscoverCall("person-1", 4242L, null)), gateway.personDiscoverCalls)
    }

    @Test
    fun `without Seerr configured the row is never asked for or waited on`() = runTest {
        val gateway = FakeCoreGateway(personPageResultsById = mapOf("person-1" to Result.success(page(tmdbPersonId = 4242L))))
        val viewModel = PersonViewModel(gateway, ResourceUiStrings(), "person-1")
        advanceUntilIdle()

        assertFalse(viewModel.state.value.discoverPending)
        assertTrue(gateway.personDiscoverCalls.isEmpty())
    }

    @Test
    fun `a return refresh re-reads shown cards from the mirror even while the Seerr row is still loading`() = runTest {
        val credits = CompletableDeferred<List<SeerrCard>>()
        val fake = FakeCoreGateway(
            seerrStatusValue = seerrConfigured,
            personPageResultsById = mapOf(
                "person-1" to Result.success(page(tmdbPersonId = 4242L).copy(library = listOf(testCard(id = "a", name = "A"), testCard(id = "b", name = "B")))),
            ),
        )
        val gateway = object : CoreGateway by fake {
            override suspend fun personDiscoverCredits(personId: String, tmdbPersonId: Long, inLibrary: List<TitleTmdbRef>?): List<SeerrCard> =
                credits.await()
        }
        val viewModel = PersonViewModel(gateway, ResourceUiStrings(), "person-1")
        advanceUntilIdle()
        assertTrue(viewModel.state.value.discoverPending)
        val generation = viewModel.state.value.loadGeneration

        // Only "a" is in the mirror, played now; "b" keeps its card and the row keeps its order.
        fake.cardsByItemId["a"] = testCard(id = "a", name = "A", played = true)
        viewModel.refreshLibrary()
        advanceUntilIdle()
        assertEquals(listOf("a" to true, "b" to false), viewModel.state.value.page?.library?.map { it.id to it.played })
        assertEquals("no fresh focus landing", generation, viewModel.state.value.loadGeneration)

        credits.complete(listOf(card(1)))
        advanceUntilIdle()
        assertEquals(listOf(true, false), viewModel.state.value.page?.library?.map { it.played })
        assertEquals(listOf(1L), viewModel.state.value.discover.map { it.tmdbId })
    }

    @Test
    fun `a mirror change that commits after the return refresh still updates the badge`() = runTest {
        val gateway = FakeCoreGateway(
            personPageResultsById = mapOf("person-1" to Result.success(page(tmdbPersonId = null).copy(library = listOf(testCard(id = "a"), testCard(id = "b"))))),
        )
        val viewModel = PersonViewModel(gateway, ResourceUiStrings(), "person-1")
        advanceUntilIdle()

        // Back on top before the played count committed: the return refresh reads the old card.
        viewModel.refreshLibrary()
        advanceUntilIdle()
        assertEquals(listOf(false, false), viewModel.state.value.page?.library?.map { it.played })

        // The commit lands as a mirror event for a shown card.
        gateway.cardsByItemId["a"] = testCard(id = "a", played = true)
        gateway.emitChange(ChangeEvent.Upserted(ids = listOf("a"), libraryId = null))
        advanceUntilIdle()
        assertEquals(listOf("a" to true, "b" to false), viewModel.state.value.page?.library?.map { it.id to it.played })

        // An event for a card this page doesn't show changes nothing.
        gateway.cardsByItemId["b"] = testCard(id = "b", played = true)
        gateway.emitChange(ChangeEvent.Upserted(ids = listOf("zzz"), libraryId = null))
        advanceUntilIdle()
        assertEquals(listOf(true, false), viewModel.state.value.page?.library?.map { it.played })
    }

    @Test
    fun `a return read that finishes after a later event read can't restore the old badge`() = runTest {
        val fake = FakeCoreGateway(
            personPageResultsById = mapOf("person-1" to Result.success(page(tmdbPersonId = null).copy(library = listOf(testCard(id = "a"))))),
        )
        val holdFirstRead = CompletableDeferred<Unit>()
        var reads = 0
        val gateway = object : CoreGateway by fake {
            override suspend fun cardsByIds(itemIds: List<String>): List<Card> {
                if (reads++ == 0) holdFirstRead.await()
                return fake.cardsByIds(itemIds)
            }
        }
        val viewModel = PersonViewModel(gateway, ResourceUiStrings(), "person-1")
        advanceUntilIdle()

        // Return read A starts and is held with the old card in reach.
        viewModel.refreshLibrary()
        advanceUntilIdle()
        assertEquals(1, reads)
        // The count commits and its event B arrives while A is still out.
        fake.cardsByItemId["a"] = testCard(id = "a", played = true)
        fake.emitChange(ChangeEvent.Upserted(ids = listOf("a"), libraryId = null))
        advanceUntilIdle()
        assertEquals("B waits behind A", 1, reads)

        holdFirstRead.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, reads)
        assertEquals(listOf(true), viewModel.state.value.page?.library?.map { it.played })
    }

    @Test
    fun `a person without a TMDB id never asks Seerr`() = runTest {
        val gateway = FakeCoreGateway(personPageResultsById = mapOf("person-1" to Result.success(page(tmdbPersonId = null))))
        val viewModel = PersonViewModel(gateway, ResourceUiStrings(), "person-1")
        advanceUntilIdle()

        assertTrue(viewModel.state.value.discover.isEmpty())
        assertFalse(viewModel.state.value.discoverPending)
        assertTrue(gateway.personDiscoverCalls.isEmpty())
    }

    @Test
    fun `a Seerr failure omits the row and leaves the page and no error`() = runTest {
        val gateway = FakeCoreGateway(
            personPageResultsById = mapOf("person-1" to Result.success(page(tmdbPersonId = 4242L))),
            personDiscoverResultsByTmdbId = mapOf(4242L to Result.failure(CoreException.SeerrNotConfigured())),
            seerrStatusValue = seerrConfigured,
        )
        val viewModel = PersonViewModel(gateway, ResourceUiStrings(), "person-1")
        advanceUntilIdle()

        val state = viewModel.state.value
        assertNotNull(state.page)
        assertNull(state.error)
        assertTrue(state.discover.isEmpty())
        assertFalse(state.discoverPending)
    }

    @Test
    fun `a failed page load shows the error and retry reloads it`() = runTest {
        var failing = true
        val gateway = object : CoreGateway by FakeCoreGateway() {
            override suspend fun getPersonPage(personId: String): PersonPage {
                if (failing) throw CoreException.NotSignedIn()
                return page(tmdbPersonId = null)
            }
        }
        val viewModel = PersonViewModel(gateway, ResourceUiStrings(), "person-1")
        advanceUntilIdle()
        assertNotNull(viewModel.state.value.error)
        assertNull(viewModel.state.value.page)

        val firstLoad = viewModel.state.value.loadGeneration
        failing = false
        viewModel.retry()
        advanceUntilIdle()
        assertEquals("each load is a new focus landing", firstLoad + 1, viewModel.state.value.loadGeneration)
        assertNull(viewModel.state.value.error)
        assertEquals("person-1", viewModel.state.value.page?.id)
    }
}
