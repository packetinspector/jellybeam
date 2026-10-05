package tv.jellybeam.ui.detail

import androidx.lifecycle.ViewModelStore
import tv.jellybeam.MainDispatcherRule
import tv.jellybeam.data.CoreGateway
import tv.jellybeam.data.FakeCoreGateway
import tv.jellybeam.ui.cards.testCard
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uniffi.jellybeam_core.Card
import uniffi.jellybeam_core.ChangeEvent
import uniffi.jellybeam_core.CollectionInfo
import uniffi.jellybeam_core.ItemDetail
import uniffi.jellybeam_core.SortOrder
import uniffi.jellybeam_core.ViewKind
import uniffi.jellybeam_core.ViewSnapshot

/** Bare-minimum [ItemDetail] fixture -- only [DetailViewModelTest]'s own assertions (identity, not
 * field-by-field) need it to exist at all.
 */
private fun testItemDetail(id: String = "item-1"): ItemDetail = ItemDetail(
    id = id,
    name = "Item",
    itemType = "Movie",
    seriesName = null,
    parentIndexNumber = null,
    indexNumber = null,
    premiereDate = null,
    playCount = 0,
    lastPlayedDate = null,
    genres = emptyList(),
    officialRating = null,
    communityRating = null,
    criticRating = null,
    productionYear = null,
    endYear = null,
    status = null,
    studios = emptyList(),
    overview = null,
    runTimeTicks = null,
    container = null,
    people = emptyList(),
    mediaStreams = emptyList(),
    chapters = emptyList(),
    dateCreated = null,
    sizeBytes = null,
    recursiveItemCount = null,
    childCount = null,
    directors = emptyList(),
    writers = emptyList(),
)

class DetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /**
     * Cancels [DetailViewModel.changeRefreshScheduler]'s `while(true)` loop after each test;
     * left running, it hangs `runTest`'s cleanup phase. Tests use `runCurrent()`, never
     * `advanceUntilIdle()`, mid-test for the same reason.
     */
    private inline fun withDetailViewModel(
        viewModel: DetailViewModel,
        block: (DetailViewModel) -> Unit,
    ) {
        try {
            block(viewModel)
        } finally {
            val store = ViewModelStore()
            store.put("detail", viewModel)
            store.clear()
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `a mirror change re-queries the selected season's episodes without touching selection`() = runTest {
        val series = testCard(id = "series-1", itemType = "Series")
        val season1 = testCard(id = "season-1", itemType = "Season", indexNumber = 1, name = "Season 1")
        val fake = FakeCoreGateway(
            childrenByParent = mapOf(
                "series-1" to listOf(season1),
                "season-1" to listOf(testCard(id = "ep-1", itemType = "Episode", indexNumber = 1)),
            ),
        )
        val changes = MutableSharedFlow<ChangeEvent>(extraBufferCapacity = 4)
        val gateway = object : CoreGateway by fake {
            override fun changeEvents(): Flow<ChangeEvent> = changes
        }

        withDetailViewModel(DetailViewModel(gateway, series)) { viewModel ->
            val callsBefore = fake.childrenCalls.size
            assertEquals("season-1", viewModel.state.value.selectedSeasonId)

            changes.tryEmit(ChangeEvent.Upserted(ids = listOf("ep-1"), libraryId = null))
            runCurrent() // scheduler's leading refresh runs synchronously off this event

            assertEquals(callsBefore + 2, fake.childrenCalls.size)
            assertEquals("season-1", viewModel.state.value.selectedSeasonId)
            assertEquals(listOf("ep-1"), viewModel.state.value.episodes.map { it.id })
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `a change event for an unrelated id does not trigger a refetch`() = runTest {
        // "Unrelated": neither the series id nor an already-loaded season/episode id.
        val series = testCard(id = "series-1", itemType = "Series")
        val season1 = testCard(id = "season-1", itemType = "Season", indexNumber = 1, name = "Season 1")
        val fake = FakeCoreGateway(
            childrenByParent = mapOf(
                "series-1" to listOf(season1),
                "season-1" to listOf(testCard(id = "ep-1", itemType = "Episode", indexNumber = 1)),
            ),
        )
        val changes = MutableSharedFlow<ChangeEvent>(extraBufferCapacity = 4)
        val gateway = object : CoreGateway by fake {
            override fun changeEvents(): Flow<ChangeEvent> = changes
        }

        withDetailViewModel(DetailViewModel(gateway, series)) { viewModel ->
            val callsBefore = fake.childrenCalls.size

            changes.tryEmit(ChangeEvent.Upserted(ids = listOf("some-other-series-item"), libraryId = null))
            runCurrent()

            assertEquals(callsBefore, fake.childrenCalls.size)
        }
    }

    @Test
    fun `a movie never touches children -- seasons and episodes stay empty`() = runTest {
        val movie = testCard(id = "movie-1", itemType = "Movie")
        val gateway = FakeCoreGateway()

        withDetailViewModel(DetailViewModel(gateway, movie)) { viewModel ->

            assertTrue(viewModel.state.value.seasons.isEmpty())
            assertTrue(viewModel.state.value.episodes.isEmpty())
            assertTrue(gateway.childrenCalls.isEmpty())
        }
    }

    // -- docs/11 item 11's cross-season fix: allEpisodes -------------------

    @Test
    fun `a series loads ALL of its episodes across every season, not just the selected one`() = runTest {
        val series = testCard(id = "series-1", itemType = "Series")
        val season1 = testCard(id = "season-1", itemType = "Season", indexNumber = 1)
        val season2 = testCard(id = "season-2", itemType = "Season", indexNumber = 2)
        val s1Ep = testCard(id = "s1-ep", itemType = "Episode", parentIndexNumber = 1)
        val s2Ep = testCard(id = "s2-ep", itemType = "Episode", parentIndexNumber = 2, positionTicks = 100)
        val gateway = FakeCoreGateway(
            childrenByParent = mapOf(
                "series-1" to listOf(season1, season2),
                "season-1" to listOf(s1Ep),
                "season-2" to listOf(s2Ep),
            ),
            seriesEpisodesBySeriesId = mapOf("series-1" to listOf(s1Ep, s2Ep)),
        )

        withDetailViewModel(DetailViewModel(gateway, series)) { viewModel ->

            // [allEpisodes] spans both seasons regardless of season tab.
            assertEquals(listOf("s1-ep", "s2-ep"), viewModel.state.value.allEpisodes.map { it.id })
            assertEquals(listOf("series-1"), gateway.seriesEpisodesCalls)
            // Resume-season preselection: season-2's in-progress episode wins over the default.
            assertEquals("season-2", viewModel.state.value.selectedSeasonId)
            assertEquals(listOf("s2-ep"), viewModel.state.value.episodes.map { it.id })
        }
    }

    // -- Resume-season preselection: single-pass contract ------------------
    // docs/15-focus-and-selection.md §0.2/§5: resolves once both seasons and allEpisodes settle;
    // never provisionally from the fallback first.

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `seasons landing before episodes still resolves exactly once, matching the per-episode answer`() = runTest {
        val series = testCard(id = "series-1", itemType = "Series")
        // Season fallback (unplayedCount) would pick season-2; only the per-episode signal
        // may surface.
        val season1 = testCard(id = "season-1", itemType = "Season", indexNumber = 1, unplayedCount = 0)
        val season2 = testCard(id = "season-2", itemType = "Season", indexNumber = 2, unplayedCount = 5)
        val s1Resuming = testCard(id = "s1-ep", itemType = "Episode", parentIndexNumber = 1, positionTicks = 100)
        val fake = FakeCoreGateway(
            childrenByParent = mapOf(
                "series-1" to listOf(season1, season2),
                "season-1" to listOf(s1Resuming),
                "season-2" to listOf(testCard(id = "s2-ep", itemType = "Episode", parentIndexNumber = 2)),
            ),
            seriesEpisodesBySeriesId = mapOf("series-1" to listOf(s1Resuming)),
        )
        // seriesEpisodes held open so seasons resolve first; allEpisodes lands once the gate
        // completes.
        val allEpisodesGate = CompletableDeferred<List<Card>>()
        val gateway = object : CoreGateway by fake {
            override suspend fun seriesEpisodes(seriesId: String): List<Card> = allEpisodesGate.await()
        }

        withDetailViewModel(DetailViewModel(gateway, series)) { viewModel ->
            // Seasons loaded but allEpisodes hasn't settled: unresolved, not a provisional guess.
            assertEquals(null, viewModel.state.value.selectedSeasonId)

            allEpisodesGate.complete(listOf(s1Resuming))
            runCurrent()

            assertEquals("season-1", viewModel.state.value.selectedSeasonId)
            assertEquals(listOf("s1-ep"), viewModel.state.value.episodes.map { it.id })
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `episodes landing before seasons still resolves exactly once, matching the per-episode answer`() = runTest {
        val series = testCard(id = "series-1", itemType = "Series")
        val season1 = testCard(id = "season-1", itemType = "Season", indexNumber = 1)
        val season2 = testCard(id = "season-2", itemType = "Season", indexNumber = 2)
        val s1Ep = testCard(id = "s1-ep", itemType = "Episode", parentIndexNumber = 1)
        val s2Resuming = testCard(id = "s2-ep", itemType = "Episode", parentIndexNumber = 2, positionTicks = 50)
        val fake = FakeCoreGateway(
            childrenByParent = mapOf(
                "series-1" to listOf(season1, season2),
                "season-1" to listOf(s1Ep),
                "season-2" to listOf(s2Resuming),
            ),
            seriesEpisodesBySeriesId = mapOf("series-1" to listOf(s1Ep, s2Resuming)),
        )
        // The seasons fetch held open this time; allEpisodes resolves first.
        val seasonsGate = CompletableDeferred<List<Card>>()
        val gateway = object : CoreGateway by fake {
            override suspend fun children(parentId: String, sort: SortOrder, startIndex: UInt, limit: UInt): List<Card> =
                if (parentId == "series-1") seasonsGate.await() else fake.children(parentId, sort, startIndex, limit)
        }

        withDetailViewModel(DetailViewModel(gateway, series)) { viewModel ->
            // allEpisodes has settled but seasons haven't loaded -- unresolved.
            assertEquals(null, viewModel.state.value.selectedSeasonId)

            seasonsGate.complete(listOf(season1, season2))
            runCurrent()

            assertEquals("season-2", viewModel.state.value.selectedSeasonId)
            assertEquals(listOf("s2-ep"), viewModel.state.value.episodes.map { it.id })
        }
    }

    @Test
    fun `a failed episode load resolves the season-count fallback instead of hanging unresolved`() = runTest {
        val series = testCard(id = "series-1", itemType = "Series")
        val season1 = testCard(id = "season-1", itemType = "Season", indexNumber = 1, unplayedCount = 0)
        val season2 = testCard(id = "season-2", itemType = "Season", indexNumber = 2, unplayedCount = 5)
        val fake = FakeCoreGateway(
            childrenByParent = mapOf(
                "series-1" to listOf(season1, season2),
                "season-1" to listOf(testCard(id = "s1-ep", itemType = "Episode", parentIndexNumber = 1)),
                "season-2" to listOf(testCard(id = "s2-ep", itemType = "Episode", parentIndexNumber = 2)),
            ),
        )
        val gateway = object : CoreGateway by fake {
            override suspend fun seriesEpisodes(seriesId: String): List<Card> = throw IllegalStateException("boom")
        }

        withDetailViewModel(DetailViewModel(gateway, series)) { viewModel ->

            // allEpisodes fails but still resolves the flag, falling back to unplayedCount.
            assertTrue(viewModel.state.value.allEpisodes.isEmpty())
            assertEquals("season-2", viewModel.state.value.selectedSeasonId)
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `a viewer's season tap before resolution wins over the later resume-season answer`() = runTest {
        val series = testCard(id = "series-1", itemType = "Series")
        val season1 = testCard(id = "season-1", itemType = "Season", indexNumber = 1)
        val season2 = testCard(id = "season-2", itemType = "Season", indexNumber = 2)
        val s2Resuming = testCard(id = "s2-ep", itemType = "Episode", parentIndexNumber = 2, positionTicks = 100)
        val fake = FakeCoreGateway(
            childrenByParent = mapOf(
                "series-1" to listOf(season1, season2),
                "season-1" to listOf(testCard(id = "s1-ep", itemType = "Episode", parentIndexNumber = 1)),
                "season-2" to listOf(s2Resuming),
            ),
        )
        // seriesEpisodes held open so selectSeason can be called before allEpisodes resolves.
        val allEpisodesGate = CompletableDeferred<List<Card>>()
        val gateway = object : CoreGateway by fake {
            override suspend fun seriesEpisodes(seriesId: String): List<Card> = allEpisodesGate.await()
        }

        withDetailViewModel(DetailViewModel(gateway, series)) { viewModel ->
            // Unresolved: seasons loaded, allEpisodes hasn't -- no provisional guess to tap over.
            assertEquals(null, viewModel.state.value.selectedSeasonId)

            viewModel.selectSeason("season-1")
            assertEquals("season-1", viewModel.state.value.selectedSeasonId)

            // allEpisodes resolving with season-2 in progress must not yank the viewer's own pick.
            allEpisodesGate.complete(listOf(s2Resuming))
            runCurrent()

            assertEquals("season-1", viewModel.state.value.selectedSeasonId)
            assertEquals(listOf("s1-ep"), viewModel.state.value.episodes.map { it.id })
        }
    }

    @Test
    fun `a movie never calls seriesEpisodes -- allEpisodes stays empty`() = runTest {
        val movie = testCard(id = "movie-1", itemType = "Movie")
        val gateway = FakeCoreGateway()

        withDetailViewModel(DetailViewModel(gateway, movie)) { viewModel ->

            assertTrue(viewModel.state.value.allEpisodes.isEmpty())
            assertTrue(gateway.seriesEpisodesCalls.isEmpty())
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `a mirror change also re-queries allEpisodes, not just the selected season`() = runTest {
        val series = testCard(id = "series-1", itemType = "Series")
        val season1 = testCard(id = "season-1", itemType = "Season", indexNumber = 1)
        val originalEp = testCard(id = "s1-ep", itemType = "Episode", parentIndexNumber = 1, played = false)
        val updatedEp = originalEp.copy(played = true)
        val fake = FakeCoreGateway(
            childrenByParent = mapOf(
                "series-1" to listOf(season1),
                "season-1" to listOf(originalEp),
            ),
            seriesEpisodesBySeriesId = mapOf("series-1" to listOf(originalEp)),
        )
        val changes = MutableSharedFlow<ChangeEvent>(extraBufferCapacity = 4)
        // serveUpdated flips only after initial load, so the first read sees the original episode.
        var serveUpdated = false
        val gateway = object : CoreGateway by fake {
            override fun changeEvents(): Flow<ChangeEvent> = changes
            override suspend fun seriesEpisodes(seriesId: String): List<Card> =
                fake.seriesEpisodes(seriesId).map { if (serveUpdated && it.id == "s1-ep") updatedEp else it }
        }

        withDetailViewModel(DetailViewModel(gateway, series)) { viewModel ->
            assertEquals(false, viewModel.state.value.allEpisodes.single().played)

            serveUpdated = true
            changes.tryEmit(ChangeEvent.Upserted(ids = listOf("s1-ep"), libraryId = null))
            runCurrent()

            assertEquals(true, viewModel.state.value.allEpisodes.single().played)
        }
    }

    @Test
    fun `a series loads its seasons sorted by index number, out-of-order server response included`() = runTest {
        val series = testCard(id = "series-1", itemType = "Series")
        val season2 = testCard(id = "season-2", itemType = "Season", indexNumber = 2, name = "Season 2")
        val season1 = testCard(id = "season-1", itemType = "Season", indexNumber = 1, name = "Season 1")
        val gateway = FakeCoreGateway(
            childrenByParent = mapOf(
                "series-1" to listOf(season2, season1), // deliberately out of order
                "season-1" to emptyList(),
            ),
        )

        withDetailViewModel(DetailViewModel(gateway, series)) { viewModel ->

            assertEquals(listOf("season-1", "season-2"), viewModel.state.value.seasons.map { it.id })

            val seasonsCall = gateway.childrenCalls.first { it.parentId == "series-1" }
            assertEquals(SortOrder.INDEX_NUMBER, seasonsCall.sort)
        }
    }

    @Test
    fun `a series auto-selects and loads its first season's episode grid`() = runTest {
        val series = testCard(id = "series-1", itemType = "Series")
        val season1 = testCard(id = "season-1", itemType = "Season", indexNumber = 1)
        val ep2 = testCard(id = "ep-2", itemType = "Episode", indexNumber = 2, name = "Second")
        val ep1 = testCard(id = "ep-1", itemType = "Episode", indexNumber = 1, name = "First")
        val gateway = FakeCoreGateway(
            childrenByParent = mapOf(
                "series-1" to listOf(season1),
                "season-1" to listOf(ep2, ep1), // deliberately out of order
            ),
        )

        withDetailViewModel(DetailViewModel(gateway, series)) { viewModel ->

            assertEquals("season-1", viewModel.state.value.selectedSeasonId)
            assertEquals(listOf("ep-1", "ep-2"), viewModel.state.value.episodes.map { it.id })
        }
    }

    @Test
    fun `selectSeason switches the episode grid to the newly selected season`() = runTest {
        val series = testCard(id = "series-1", itemType = "Series")
        val season1 = testCard(id = "season-1", itemType = "Season", indexNumber = 1)
        val season2 = testCard(id = "season-2", itemType = "Season", indexNumber = 2)
        val gateway = FakeCoreGateway(
            childrenByParent = mapOf(
                "series-1" to listOf(season1, season2),
                "season-1" to listOf(testCard(id = "s1-ep", itemType = "Episode")),
                "season-2" to listOf(testCard(id = "s2-ep", itemType = "Episode")),
            ),
        )
        withDetailViewModel(DetailViewModel(gateway, series)) { viewModel ->
            assertEquals(listOf("s1-ep"), viewModel.state.value.episodes.map { it.id })

            viewModel.selectSeason("season-2")

            assertEquals("season-2", viewModel.state.value.selectedSeasonId)
            assertEquals(listOf("s2-ep"), viewModel.state.value.episodes.map { it.id })
        }
    }

    @Test
    fun `a series with a Specials season defaults to the first non-special season, not Specials`() = runTest {
        // detail-ux-spec item 3: a Specials season (index 0) must never win the default.
        val series = testCard(id = "series-1", itemType = "Series")
        val specials = testCard(id = "season-0", itemType = "Season", indexNumber = 0, name = "Specials")
        val season1 = testCard(id = "season-1", itemType = "Season", indexNumber = 1, name = "Season 1")
        val gateway = FakeCoreGateway(
            childrenByParent = mapOf(
                "series-1" to listOf(specials, season1), // server order: Specials first
                "season-1" to listOf(testCard(id = "ep-1", itemType = "Episode")),
            ),
        )

        withDetailViewModel(DetailViewModel(gateway, series)) { viewModel ->

            assertEquals("season-1", viewModel.state.value.selectedSeasonId)
        }
    }

    @Test
    fun `a series with ONLY a Specials season falls back to selecting it`() = runTest {
        val series = testCard(id = "series-1", itemType = "Series")
        val specials = testCard(id = "season-0", itemType = "Season", indexNumber = 0, name = "Specials")
        val gateway = FakeCoreGateway(
            childrenByParent = mapOf(
                "series-1" to listOf(specials),
                "season-0" to emptyList(),
            ),
        )

        withDetailViewModel(DetailViewModel(gateway, series)) { viewModel ->

            assertEquals("season-0", viewModel.state.value.selectedSeasonId)
        }
    }

    @Test
    fun `a series with no seasons yet renders an empty tab strip, not a crash`() = runTest {
        val series = testCard(id = "series-1", itemType = "Series")
        val gateway = FakeCoreGateway()

        withDetailViewModel(DetailViewModel(gateway, series)) { viewModel ->

            assertTrue(viewModel.state.value.seasons.isEmpty())
            assertEquals(null, viewModel.state.value.selectedSeasonId)
            assertTrue(viewModel.state.value.episodes.isEmpty())
        }
    }

    // -- itemDetail / similar (docs/11 tier 1 item 1, tier 2 items 8-9-13) --

    @Test
    fun `a Movie loads its ItemDetail on open`() = runTest {
        val movie = testCard(id = "movie-1", itemType = "Movie")
        val detail = testItemDetail(id = "movie-1")
        val gateway = FakeCoreGateway(itemDetailResultsByItemId = mapOf("movie-1" to Result.success(detail)))

        withDetailViewModel(DetailViewModel(gateway, movie)) { viewModel ->

            assertEquals(detail, viewModel.state.value.itemDetail)
        }
    }

    @Test
    fun `a Series also loads its ItemDetail -- the fetch isn't gated on item type`() = runTest {
        val series = testCard(id = "series-1", itemType = "Series")
        val detail = testItemDetail(id = "series-1")
        val gateway = FakeCoreGateway(itemDetailResultsByItemId = mapOf("series-1" to Result.success(detail)))

        withDetailViewModel(DetailViewModel(gateway, series)) { viewModel ->

            assertEquals(detail, viewModel.state.value.itemDetail)
        }
    }

    // -- itemDetailLoaded / similarLoaded: fetch-settled flags --------------

    @Test
    fun `itemDetailLoaded flips true once the fetch succeeds`() = runTest {
        val movie = testCard(id = "movie-1", itemType = "Movie")
        val detail = testItemDetail(id = "movie-1")
        val gateway = FakeCoreGateway(itemDetailResultsByItemId = mapOf("movie-1" to Result.success(detail)))

        withDetailViewModel(DetailViewModel(gateway, movie)) { viewModel ->

            assertTrue(viewModel.state.value.itemDetailLoaded)
        }
    }

    @Test
    fun `itemDetailLoaded flips true even when the fetch fails -- a failure still settles`() = runTest {
        // Lets [DetailScreen] stop waiting once the fetch settles, even on failure, instead of
        // reserving a gap forever.
        val movie = testCard(id = "movie-1", itemType = "Movie")
        val gateway = FakeCoreGateway() // no entry configured -- fetch throws

        withDetailViewModel(DetailViewModel(gateway, movie)) { viewModel ->

            assertNull(viewModel.state.value.itemDetail)
            assertTrue(viewModel.state.value.itemDetailLoaded)
        }
    }

    @Test
    fun `similarLoaded flips true once the fetch settles, success or empty`() = runTest {
        val movie = testCard(id = "movie-1", itemType = "Movie")
        val gateway = FakeCoreGateway(similarResultsByItemId = mapOf("movie-1" to emptyList()))

        withDetailViewModel(DetailViewModel(gateway, movie)) { viewModel ->

            assertTrue(viewModel.state.value.similar.isEmpty())
            assertTrue(viewModel.state.value.similarLoaded)
        }
    }

    @Test
    fun `an Episode page never fetches Similar Titles -- similarLoaded stays false`() = runTest {
        val episode = testCard(id = "ep-1", itemType = "Episode")
        val gateway = FakeCoreGateway()

        withDetailViewModel(DetailViewModel(gateway, episode)) { viewModel ->

            assertEquals(false, viewModel.state.value.similarLoaded)
        }
    }

    @Test
    fun `a failed ItemDetail fetch leaves itemDetail null instead of crashing the page`() = runTest {
        // FakeCoreGateway throws UnconfiguredFakeCall here, standing in for a real CoreException;
        // the fail-open contract means it must not propagate.
        val movie = testCard(id = "movie-1", itemType = "Movie")
        val gateway = FakeCoreGateway()

        withDetailViewModel(DetailViewModel(gateway, movie)) { viewModel ->

            assertNull(viewModel.state.value.itemDetail)
        }
    }

    @Test
    fun `a Movie loads Similar Titles, capped at the spec's limit of 16`() = runTest {
        val movie = testCard(id = "movie-1", itemType = "Movie")
        val similar = listOf(testCard(id = "similar-1"))
        val gateway = FakeCoreGateway(similarResultsByItemId = mapOf("movie-1" to similar))

        withDetailViewModel(DetailViewModel(gateway, movie)) { viewModel ->

            assertEquals(similar, viewModel.state.value.similar)
            assertEquals(listOf(FakeCoreGateway.GetSimilarCall("movie-1", 16u)), gateway.getSimilarCalls)
        }
    }

    @Test
    fun `a Series loads Similar Titles too`() = runTest {
        val series = testCard(id = "series-1", itemType = "Series")
        val similar = listOf(testCard(id = "similar-1"))
        val gateway = FakeCoreGateway(similarResultsByItemId = mapOf("series-1" to similar))

        withDetailViewModel(DetailViewModel(gateway, series)) { viewModel ->

            assertEquals(similar, viewModel.state.value.similar)
        }
    }

    @Test
    fun `an Episode page never fetches Similar Titles at all -- skipped, not just hidden`() = runTest {
        val episode = testCard(id = "ep-1", itemType = "Episode")
        // Configured on purpose: state alone can't prove the fetch was skipped; the call log does.
        val gateway = FakeCoreGateway(similarResultsByItemId = mapOf("ep-1" to listOf(testCard(id = "similar-1"))))

        withDetailViewModel(DetailViewModel(gateway, episode)) { viewModel ->

            assertTrue(viewModel.state.value.similar.isEmpty())
            assertTrue(gateway.getSimilarCalls.isEmpty())
        }
    }

    // -- nextEpisode / libraryName -------------------------------------------

    @Test
    fun `an Episode page fetches its Up Next target via nextEpisodeAfter`() = runTest {
        val episode = testCard(id = "ep-1", itemType = "Episode")
        val next = testCard(id = "ep-2", itemType = "Episode", indexNumber = 2)
        val gateway = FakeCoreGateway(nextEpisodeByItemId = mapOf("ep-1" to next))

        withDetailViewModel(DetailViewModel(gateway, episode)) { viewModel ->

            assertEquals(next, viewModel.state.value.nextEpisode)
            assertEquals(listOf("ep-1"), gateway.nextEpisodeAfterCalls)
        }
    }

    @Test
    fun `a Series or Movie page never fetches nextEpisode -- Episode only`() = runTest {
        val movie = testCard(id = "movie-1", itemType = "Movie")
        val gateway = FakeCoreGateway()

        withDetailViewModel(DetailViewModel(gateway, movie)) { viewModel ->

            assertNull(viewModel.state.value.nextEpisode)
            assertTrue(gateway.nextEpisodeAfterCalls.isEmpty())
        }
    }

    @Test
    fun `a Movie page resolves its library name from the server-configured view list`() = runTest {
        val movie = testCard(id = "movie-1", itemType = "Movie", libraryId = "lib-1")
        val gateway = FakeCoreGateway(viewsList = listOf(ViewSnapshot(id = "lib-1", name = "My Movies", kind = ViewKind.LIBRARY), ViewSnapshot(id = "lib-2", name = "TV Shows", kind = ViewKind.LIBRARY)))

        withDetailViewModel(DetailViewModel(gateway, movie)) { viewModel ->

            assertEquals("My Movies", viewModel.state.value.libraryName)
        }
    }

    @Test
    fun `a card with no libraryId never resolves a library name`() = runTest {
        val movie = testCard(id = "movie-1", itemType = "Movie", libraryId = null)
        val gateway = FakeCoreGateway(viewsList = listOf(ViewSnapshot(id = "lib-1", name = "My Movies", kind = ViewKind.LIBRARY)))

        withDetailViewModel(DetailViewModel(gateway, movie)) { viewModel ->

            assertNull(viewModel.state.value.libraryName)
        }
    }

    @Test
    fun `an Episode page never resolves a library name`() = runTest {
        val episode = testCard(id = "ep-1", itemType = "Episode", libraryId = "lib-1")
        val gateway = FakeCoreGateway(viewsList = listOf(ViewSnapshot(id = "lib-1", name = "My Movies", kind = ViewKind.LIBRARY)))

        withDetailViewModel(DetailViewModel(gateway, episode)) { viewModel ->

            assertNull(viewModel.state.value.libraryName)
        }
    }

    // -- Dedupe at the VM boundary: crash-safe lazy-container keys ----------

    @Test
    fun `a season's episode list with a duplicate id is deduped instead of crashing the grid`() = runTest {
        val series = testCard(id = "series-1", itemType = "Series")
        val season1 = testCard(id = "season-1", itemType = "Season", indexNumber = 1)
        val dupe = testCard(id = "ep-1", itemType = "Episode", indexNumber = 1)
        val gateway = FakeCoreGateway(
            childrenByParent = mapOf(
                "series-1" to listOf(season1),
                // Simulates the server/mirror returning the same episode id twice;
                // LazyVerticalGrid's `key = { episode.id }` can't survive that.
                "season-1" to listOf(dupe, dupe.copy(name = "Duplicate")),
            ),
        )

        withDetailViewModel(DetailViewModel(gateway, series)) { viewModel ->

            assertEquals(listOf("ep-1"), viewModel.state.value.episodes.map { it.id })
        }
    }

    @Test
    fun `selectSeason also dedupes a season's episode list by id`() = runTest {
        val series = testCard(id = "series-1", itemType = "Series")
        val season1 = testCard(id = "season-1", itemType = "Season", indexNumber = 1)
        val season2 = testCard(id = "season-2", itemType = "Season", indexNumber = 2)
        val dupe = testCard(id = "s2-ep", itemType = "Episode", parentIndexNumber = 2)
        val gateway = FakeCoreGateway(
            childrenByParent = mapOf(
                "series-1" to listOf(season1, season2),
                "season-1" to listOf(testCard(id = "s1-ep", itemType = "Episode")),
                "season-2" to listOf(dupe, dupe.copy(name = "Duplicate")),
            ),
        )
        withDetailViewModel(DetailViewModel(gateway, series)) { viewModel ->

            viewModel.selectSeason("season-2")

            assertEquals(listOf("s2-ep"), viewModel.state.value.episodes.map { it.id })
        }
    }

    @Test
    fun `allEpisodes is deduped by id -- a cross-season crash candidate`() = runTest {
        val series = testCard(id = "series-1", itemType = "Series")
        val dupe = testCard(id = "s1-ep", itemType = "Episode", parentIndexNumber = 1)
        val gateway = FakeCoreGateway(
            childrenByParent = mapOf("series-1" to emptyList()),
            seriesEpisodesBySeriesId = mapOf("series-1" to listOf(dupe, dupe.copy(played = true))),
        )

        withDetailViewModel(DetailViewModel(gateway, series)) { viewModel ->

            assertEquals(listOf("s1-ep"), viewModel.state.value.allEpisodes.map { it.id })
        }
    }

    @Test
    fun `seasons list is deduped by id`() = runTest {
        val series = testCard(id = "series-1", itemType = "Series")
        val dupe = testCard(id = "season-1", itemType = "Season", indexNumber = 1)
        val gateway = FakeCoreGateway(
            childrenByParent = mapOf("series-1" to listOf(dupe, dupe.copy(name = "Duplicate"))),
        )

        withDetailViewModel(DetailViewModel(gateway, series)) { viewModel ->

            assertEquals(listOf("season-1"), viewModel.state.value.seasons.map { it.id })
        }
    }

    @Test
    fun `Similar Titles is deduped by id -- a server returning the same title twice must not crash`() = runTest {
        val movie = testCard(id = "movie-1", itemType = "Movie")
        val dupe = testCard(id = "similar-1")
        val gateway = FakeCoreGateway(similarResultsByItemId = mapOf("movie-1" to listOf(dupe, dupe.copy(name = "Duplicate"))))

        withDetailViewModel(DetailViewModel(gateway, movie)) { viewModel ->

            assertEquals(listOf("similar-1"), viewModel.state.value.similar.map { it.id })
        }
    }

    // -- docs/17-mini-player.md §6: the header card follows playback -------

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `an upserted change for an episode refreshes the header card`() = runTest {
        val episode = testCard(id = "ep-1", itemType = "Episode", positionTicks = 1_000L)
        val fake = FakeCoreGateway().apply {
            cardsByItemId["ep-1"] = episode.copy(positionTicks = 5_000L, played = true)
        }
        val changes = MutableSharedFlow<ChangeEvent>(extraBufferCapacity = 4)
        val gateway = object : CoreGateway by fake {
            override fun changeEvents(): Flow<ChangeEvent> = changes
        }

        withDetailViewModel(DetailViewModel(gateway, episode)) { viewModel ->
            assertEquals(1_000L, viewModel.state.value.card.positionTicks)

            changes.tryEmit(ChangeEvent.Upserted(ids = listOf("ep-1"), libraryId = null))
            runCurrent()

            assertEquals(5_000L, viewModel.state.value.card.positionTicks)
            assertTrue(viewModel.state.value.card.played)
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `a stop report epoch refreshes the header card`() = runTest {
        val movie = testCard(id = "movie-1", itemType = "Movie", positionTicks = 1_000L)
        val fake = FakeCoreGateway().apply {
            cardsByItemId["movie-1"] = movie.copy(positionTicks = 9_000L, played = true)
        }
        val stopEpoch = MutableStateFlow(0L)

        withDetailViewModel(DetailViewModel(fake, movie, stopEpoch)) { viewModel ->
            assertEquals(1_000L, viewModel.state.value.card.positionTicks)

            // docs/17-mini-player.md §6: a PiP dismissal gives this screen only the stop-epoch
            // edge, no mirror-change event.
            stopEpoch.value = 1L
            runCurrent()

            assertEquals(9_000L, viewModel.state.value.card.positionTicks)
            assertTrue(viewModel.state.value.card.played)
        }
    }

    // -- Detail action menu (docs/19-detail-action-menu.md §3.3) -----------

    @Test
    fun `collections, admin status, and membership are loaded once in init`() = runTest {
        val movie = testCard(id = "movie-1", itemType = "Movie")
        val gateway = FakeCoreGateway(
            collectionsResult = listOf(CollectionInfo(id = "c1", name = "Alpha"), CollectionInfo(id = "c2", name = "Beta")),
            isAdministratorResult = true,
            collectionIdsByItemId = mutableMapOf("movie-1" to listOf("c2")),
        )

        withDetailViewModel(DetailViewModel(gateway, movie)) { viewModel ->

            assertEquals(listOf("c1", "c2"), viewModel.state.value.collections.map { it.id })
            assertTrue(viewModel.state.value.isAdministrator)
            assertEquals(setOf("c2"), viewModel.state.value.memberOfCollections)
        }
    }

    @Test
    fun `a failed collections, admin, or membership fetch fails open -- empty, ordinary user`() = runTest {
        val movie = testCard(id = "movie-1", itemType = "Movie")
        withDetailViewModel(DetailViewModel(FakeCoreGateway(), movie)) { viewModel ->

            assertTrue(viewModel.state.value.collections.isEmpty())
            assertFalse(viewModel.state.value.isAdministrator)
            assertTrue(viewModel.state.value.memberOfCollections.isEmpty())
        }
    }

    @Test
    fun `openMenu builds the panel from current state, predicting the never-played focus row`() = runTest {
        val movie = testCard(id = "movie-1", itemType = "Movie", played = false)
        val gateway = FakeCoreGateway(
            collectionsResult = listOf(CollectionInfo(id = "c1", name = "Alpha")),
            isAdministratorResult = true,
        )
        withDetailViewModel(DetailViewModel(gateway, movie)) { viewModel ->

            viewModel.openMenu()

            val menu = viewModel.state.value.menu
            assertEquals(MenuAction.MarkWatched, menu?.model?.focusOn)
            assertEquals(MenuLevel.FIRST, menu?.level)
            assertNull(menu?.scopeSeasonNumber)
            assertTrue(menu?.model?.groups.orEmpty().any { it.group == MenuGroup.LIBRARY })
        }
    }

    @Test
    fun `openMenu in season scope carries the scoped season's number for the panel header`() = runTest {
        val series = testCard(id = "series-1", itemType = "Series")
        val season2 = testCard(id = "season-2", itemType = "Season", indexNumber = 2, name = "Season 2")
        val gateway = FakeCoreGateway(
            childrenByParent = mapOf("series-1" to listOf(season2), "season-2" to emptyList()),
        )
        withDetailViewModel(DetailViewModel(gateway, series)) { viewModel ->
            viewModel.selectSeason("season-2")

            viewModel.openMenu()

            assertEquals(2, viewModel.state.value.menu?.scopeSeasonNumber)
        }
    }

    @Test
    fun `mark watched calls setPlayed, closes the menu, and toasts Marked as watched`() = runTest {
        val movie = testCard(id = "movie-1", itemType = "Movie", played = false)
        val gateway = FakeCoreGateway()
        withDetailViewModel(DetailViewModel(gateway, movie)) { viewModel ->
            viewModel.openMenu()

            viewModel.runAction(MenuAction.MarkWatched)

            assertEquals(listOf(FakeCoreGateway.SetPlayedCall("movie-1", true)), gateway.setPlayedCalls)
            assertNull(viewModel.state.value.menu)
            assertEquals("Marked as watched", viewModel.state.value.toast)
        }
    }

    @Test
    fun `mark unwatched calls setPlayed with false and toasts the unwatched text`() = runTest {
        val movie = testCard(id = "movie-1", itemType = "Movie", played = true)
        val gateway = FakeCoreGateway()
        withDetailViewModel(DetailViewModel(gateway, movie)) { viewModel ->
            viewModel.openMenu()

            viewModel.runAction(MenuAction.MarkUnwatched)

            assertEquals(listOf(FakeCoreGateway.SetPlayedCall("movie-1", false)), gateway.setPlayedCalls)
            assertEquals("Marked as unwatched", viewModel.state.value.toast)
        }
    }

    @Test
    fun `clearing during an in-flight mark publishes no toast`() = runTest {
        val movie = testCard(id = "movie-1", itemType = "Movie", played = false)
        val gate = CompletableDeferred<Unit>()
        val gateway = object : CoreGateway by FakeCoreGateway() {
            override suspend fun setPlayed(itemId: String, played: Boolean) = gate.await()
        }
        val viewModel = DetailViewModel(gateway, movie)
        val store = ViewModelStore().apply { put("detail", viewModel) }
        try {
            viewModel.openMenu()
            viewModel.runAction(MenuAction.MarkWatched)
            assertNull(viewModel.state.value.toast)

            store.clear()

            assertNull("cancellation is not a server failure", viewModel.state.value.toast)
        } finally {
            store.clear()
            gate.cancel()
        }
    }

    @Test
    fun `a failed mark toasts the server-unreachable text and changes nothing else`() = runTest {
        val movie = testCard(id = "movie-1", itemType = "Movie", played = false)
        val gateway = FakeCoreGateway().apply { setPlayedError = RuntimeException("boom") }
        withDetailViewModel(DetailViewModel(gateway, movie)) { viewModel ->
            val cardBefore = viewModel.state.value.card
            viewModel.openMenu()

            viewModel.runAction(MenuAction.MarkWatched)

            assertEquals("Couldn't reach the server", viewModel.state.value.toast)
            assertEquals(cardBefore, viewModel.state.value.card)
        }
    }

    @Test
    fun `a bulk mark opens the confirm dialog restating the count, then confirming calls setPlayedRecursive with the series id`() = runTest {
        val series = testCard(id = "series-1", itemType = "Series", name = "Sample Series Title")
        val unplayed = testCard(id = "e1", itemType = "Episode", played = false)
        val gateway = FakeCoreGateway(seriesEpisodesBySeriesId = mapOf("series-1" to listOf(unplayed)))
        withDetailViewModel(DetailViewModel(gateway, series)) { viewModel ->
            viewModel.openMenu()
            val row = viewModel.state.value.menu!!.model.groups
                .flatMap { it.rows }
                .first { it.action is MenuAction.MarkScopeWatched }

            viewModel.runAction(row.action)

            val confirm = viewModel.state.value.confirm
            assertEquals(1, confirm?.count)
            assertTrue(confirm?.played == true)
            assertEquals("series-1", confirm?.scopeId)
            assertEquals("Sample Series Title", confirm?.scopeName)
            assertEquals(row.action, confirm?.action)
            // The panel itself stays open behind the confirm block.
            assertTrue(viewModel.state.value.menu != null)

            viewModel.confirmBulkMark()

            assertEquals(listOf(FakeCoreGateway.SetPlayedRecursiveCall("series-1", true)), gateway.setPlayedRecursiveCalls)
            assertNull(viewModel.state.value.confirm)
            assertNull(viewModel.state.value.menu)
            assertEquals("Marked as watched", viewModel.state.value.toast)
        }
    }

    @Test
    fun `a bulk mark in season scope confirms against the season's own id and name`() = runTest {
        val series = testCard(id = "series-1", itemType = "Series")
        val season1 = testCard(id = "season-1", itemType = "Season", indexNumber = 1, name = "Season 1")
        val ep = testCard(id = "s1e1", itemType = "Episode", parentIndexNumber = 1, played = false)
        val gateway = FakeCoreGateway(
            childrenByParent = mapOf("series-1" to listOf(season1), "season-1" to listOf(ep)),
            seriesEpisodesBySeriesId = mapOf("series-1" to listOf(ep)),
        )
        withDetailViewModel(DetailViewModel(gateway, series)) { viewModel ->
            // A real viewer tap (not the resume-season auto-pick) is what latches menu scope to
            // "season".
            viewModel.selectSeason("season-1")
            viewModel.openMenu()
            val row = viewModel.state.value.menu!!.model.groups
                .flatMap { it.rows }
                .first { it.action is MenuAction.MarkScopeWatched }

            viewModel.runAction(row.action)

            assertEquals("season-1", viewModel.state.value.confirm?.scopeId)
            assertEquals("Season 1", viewModel.state.value.confirm?.scopeName)

            viewModel.confirmBulkMark()

            assertEquals(listOf(FakeCoreGateway.SetPlayedRecursiveCall("season-1", true)), gateway.setPlayedRecursiveCalls)
        }
    }

    @Test
    fun `dismissConfirm returns to the still-open menu without calling the gateway`() = runTest {
        val series = testCard(id = "series-1", itemType = "Series")
        val gateway = FakeCoreGateway(seriesEpisodesBySeriesId = mapOf("series-1" to listOf(testCard(id = "e1", itemType = "Episode", played = false))))
        withDetailViewModel(DetailViewModel(gateway, series)) { viewModel ->
            viewModel.openMenu()
            val row = viewModel.state.value.menu!!.model.groups.flatMap { it.rows }.first { it.action is MenuAction.MarkScopeWatched }
            viewModel.runAction(row.action)

            viewModel.dismissConfirm()

            assertNull(viewModel.state.value.confirm)
            assertTrue(viewModel.state.value.menu != null)
            assertTrue(gateway.setPlayedRecursiveCalls.isEmpty())
        }
    }

    @Test
    fun `play something random calls nothing on the gateway and hands back a pending playback target`() = runTest {
        val series = testCard(id = "series-1", itemType = "Series")
        val ep1 = testCard(id = "e1", itemType = "Episode", played = false)
        val ep2 = testCard(id = "e2", itemType = "Episode", played = true)
        val gateway = FakeCoreGateway(seriesEpisodesBySeriesId = mapOf("series-1" to listOf(ep1, ep2)))
        withDetailViewModel(DetailViewModel(gateway, series, random = Random(42))) { viewModel ->
            viewModel.openMenu()
            val row = viewModel.state.value.menu!!.model.groups.flatMap { it.rows }.first { it.action is MenuAction.PlayRandom }

            viewModel.runAction(row.action)

            assertTrue(gateway.setPlayedCalls.isEmpty())
            assertTrue(gateway.setPlayedRecursiveCalls.isEmpty())
            assertTrue(gateway.setFavoriteCalls.isEmpty())
            assertTrue(gateway.addToCollectionCalls.isEmpty())
            assertTrue(gateway.refreshMetadataCalls.isEmpty())
            val pending = viewModel.state.value.pendingPlayback
            assertTrue(pending != null && (pending.targetId == "e1" || pending.targetId == "e2"))
            assertFalse(pending!!.startFromBeginning)
            assertNull(viewModel.state.value.menu)
        }
    }

    @Test
    fun `selecting a collection adds the item, closes the menu, toasts Added to Name, and re-reads membership`() = runTest {
        val movie = testCard(id = "movie-1", itemType = "Movie")
        val gateway = FakeCoreGateway(
            collectionsResult = listOf(CollectionInfo(id = "c1", name = "Alpha")),
            // The mirror hasn't caught up yet; the optimistic id must survive the re-read.
            collectionIdsByItemId = mutableMapOf("movie-1" to emptyList()),
        )
        withDetailViewModel(DetailViewModel(gateway, movie)) { viewModel ->
            viewModel.openMenu()

            viewModel.selectCollection(CollectionInfo(id = "c1", name = "Alpha"))

            assertEquals(listOf(FakeCoreGateway.AddToCollectionCall("c1", "movie-1")), gateway.addToCollectionCalls)
            assertNull(viewModel.state.value.menu)
            assertEquals("Added to Alpha", viewModel.state.value.toast)
            assertEquals(setOf("c1"), viewModel.state.value.memberOfCollections)
        }
    }

    @Test
    fun `selecting a collection the item is already in is a no-op`() = runTest {
        val movie = testCard(id = "movie-1", itemType = "Movie")
        val gateway = FakeCoreGateway(
            collectionsResult = listOf(CollectionInfo(id = "c1", name = "Alpha")),
            collectionIdsByItemId = mutableMapOf("movie-1" to listOf("c1")),
        )
        withDetailViewModel(DetailViewModel(gateway, movie)) { viewModel ->
            viewModel.openMenu()

            viewModel.selectCollection(CollectionInfo(id = "c1", name = "Alpha"))

            assertTrue(gateway.addToCollectionCalls.isEmpty())
            assertNull(viewModel.state.value.toast)
            // Ignoring the tap does not itself close an already-open panel.
            assertTrue(viewModel.state.value.menu != null)
        }
    }

    @Test
    fun `a failed addToCollection toasts server-unreachable and leaves membership untouched`() = runTest {
        val movie = testCard(id = "movie-1", itemType = "Movie")
        val gateway = FakeCoreGateway(collectionsResult = listOf(CollectionInfo(id = "c1", name = "Alpha"))).apply {
            addToCollectionError = RuntimeException("boom")
        }
        withDetailViewModel(DetailViewModel(gateway, movie)) { viewModel ->
            viewModel.openMenu()

            viewModel.selectCollection(CollectionInfo(id = "c1", name = "Alpha"))

            assertEquals("Couldn't reach the server", viewModel.state.value.toast)
            assertTrue(viewModel.state.value.memberOfCollections.isEmpty())
        }
    }

    @Test
    fun `Go to series closes the menu with no toast and hands back the series card`() = runTest {
        val episode = testCard(id = "ep-1", itemType = "Episode", seriesId = "series-1", seriesName = "Sample Series Title")
        withDetailViewModel(DetailViewModel(FakeCoreGateway(), episode)) { viewModel ->
            viewModel.openMenu()
            val row = viewModel.state.value.menu!!.model.groups.flatMap { it.rows }.first { it.action is MenuAction.GoToSeries }

            viewModel.runAction(row.action)

            assertNull(viewModel.state.value.menu)
            assertNull(viewModel.state.value.toast)
            assertEquals("series-1", viewModel.state.value.pendingSeriesNavigation?.id)
        }
    }

    // -- docs/11 §Collection: BoxSet members, play target ------------------

    @Test
    fun `a collection loads its members in order with series season counts and a play target`() = runTest {
        val box = testCard(id = "box", itemType = "BoxSet")
        val show = testCard(id = "show", itemType = "Series", name = "Show", unplayedCount = 2)
        val film = testCard(id = "film", itemType = "Movie")
        val gateway = FakeCoreGateway(
            childrenByParent = mapOf(
                "box" to listOf(show, film),
                "show" to listOf(
                    testCard(id = "s1", itemType = "Season", indexNumber = 1),
                    testCard(id = "s2", itemType = "Season", indexNumber = 2),
                ),
            ),
            seriesEpisodesBySeriesId = mapOf(
                "show" to listOf(
                    testCard(id = "e1", itemType = "Episode", parentIndexNumber = 1, indexNumber = 1, played = true),
                    testCard(id = "e2", itemType = "Episode", parentIndexNumber = 1, indexNumber = 2),
                ),
            ),
        )

        withDetailViewModel(DetailViewModel(gateway, box)) { viewModel ->
            val state = viewModel.state.value
            assertEquals(listOf("show", "film"), state.members.map { it.id })
            assertTrue(state.membersSettled)
            assertEquals(mapOf("show" to 2), state.memberSeasonCounts)
            val play = state.collectionPlay!!
            assertEquals("show", play.member.id)
            assertEquals("e2", play.targetId)
        }
    }

    @Test
    fun `playing a collection hands the target to the shared playback hand-off`() = runTest {
        val box = testCard(id = "box", itemType = "BoxSet")
        val gateway = FakeCoreGateway(
            childrenByParent = mapOf("box" to listOf(testCard(id = "a", played = true), testCard(id = "b", played = true))),
        )

        withDetailViewModel(DetailViewModel(gateway, box)) { viewModel ->
            viewModel.playCollection()
            assertEquals(PendingPlayback("a", startFromBeginning = true), viewModel.state.value.pendingPlayback)
        }
    }

    @Test
    fun `an empty collection settles with no play target`() = runTest {
        val box = testCard(id = "box", itemType = "BoxSet")

        withDetailViewModel(DetailViewModel(FakeCoreGateway(), box)) { viewModel ->
            assertTrue(viewModel.state.value.membersSettled)
            assertNull(viewModel.state.value.collectionPlay)
        }
    }
}
