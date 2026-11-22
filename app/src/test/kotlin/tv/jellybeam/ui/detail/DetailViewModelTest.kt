package tv.jellybeam.ui.detail

import androidx.lifecycle.ViewModelStore
import tv.jellybeam.MainDispatcherRule
import tv.jellybeam.data.BrowseFailed
import tv.jellybeam.data.BrowseNotReady
import tv.jellybeam.data.CoreGateway
import tv.jellybeam.data.FakeCoreGateway
import tv.jellybeam.data.SettingsStore
import tv.jellybeam.data.defaultTestSettings
import tv.jellybeam.i18n.ResourceUiStrings
import tv.jellybeam.ui.cards.testCard
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
import uniffi.jellybeam_core.Playability
import uniffi.jellybeam_core.SeasonStats
import uniffi.jellybeam_core.SeriesPlayKind
import uniffi.jellybeam_core.SeriesPlayTarget
import uniffi.jellybeam_core.SeriesSummary
import uniffi.jellybeam_core.SortOrder
import uniffi.jellybeam_core.ViewKind
import uniffi.jellybeam_core.ViewSnapshot

/** Bare-minimum [ItemDetail] fixture -- only [DetailViewModelTest]'s own assertions (identity, not
 * field-by-field) need it to exist at all.
 */
/** What the shelf shows, by the one rule the screen uses. */
private val DetailUiState.episodes: List<Card> get() = DetailFormatting.visibleEpisodes(seasonEpisodes, showVirtualEpisodes)

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
    playability = Playability.Unknown,
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

    /** A [DetailViewModel] whose docs/09 [SettingsStore] reads [gateway] on the test's background scope. */
    private fun TestScope.detailViewModel(
        gateway: CoreGateway,
        card: Card,
        random: Random = Random.Default,
        settingsStore: SettingsStore = SettingsStore(gateway, backgroundScope),
    ) = DetailViewModel(gateway, card, ResourceUiStrings.default, random, settingsStore)

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

        withDetailViewModel(detailViewModel(gateway, series)) { viewModel ->
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
        // "Unrelated": neither the series id nor a loaded id, and from another library.
        val series = testCard(id = "series-1", itemType = "Series", libraryId = "lib-a")
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

        withDetailViewModel(detailViewModel(gateway, series)) { viewModel ->
            val callsBefore = fake.childrenCalls.size

            changes.tryEmit(ChangeEvent.Upserted(ids = listOf("some-other-series-item"), libraryId = "lib-b"))
            runCurrent()

            assertEquals(callsBefore, fake.childrenCalls.size)
        }
    }

    @Test
    fun `a movie never touches children -- seasons and episodes stay empty`() = runTest {
        val movie = testCard(id = "movie-1", itemType = "Movie")
        val gateway = FakeCoreGateway()

        withDetailViewModel(detailViewModel(gateway, movie)) { viewModel ->

            assertTrue(viewModel.state.value.seasons.isEmpty())
            assertTrue(viewModel.state.value.episodes.isEmpty())
            assertTrue(gateway.childrenCalls.isEmpty())
        }
    }

    // -- docs/11 item 11 / §12: the series summary ---------------------------

    @Test
    fun `a series reads one summary for the pill, resume season and chip counts, never the whole episode list`() = runTest {
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
            seriesPlayBySeriesId = mapOf("series-1" to testSeriesPlay(s2Ep)),
            seasonStatsBySeriesId = mapOf("series-1" to listOf(testStats("season-1", have = 1), testStats("season-2", have = 1))),
        )

        withDetailViewModel(detailViewModel(gateway, series)) { viewModel ->

            assertEquals(listOf("season-1", "season-2"), viewModel.state.value.seasonStats.map { it.seasonId })
            assertEquals(listOf("series-1" to false), gateway.seriesSummaryCalls)
            assertTrue(gateway.seriesEpisodesCalls.isEmpty())
            // Only the shown season's episodes are read.
            assertEquals(listOf("season-2"), gateway.childrenCalls.map { it.parentId }.filter { it.startsWith("season-") })
            assertTrue(viewModel.state.value.summarySettled)
            // Resume-season preselection: season-2's in-progress episode wins over the default.
            assertEquals("season-2", viewModel.state.value.selectedSeasonId)
            assertEquals(listOf("s2-ep"), viewModel.state.value.episodes.map { it.id })
        }
    }

    // -- Resume-season preselection: single-pass contract ------------------
    // docs/15-focus-and-selection.md §0.2/§5: resolves once both seasons and the summary settle;
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
            seriesPlayBySeriesId = mapOf("series-1" to testSeriesPlay(s1Resuming)),
        )
        // The summary held open so seasons resolve first; it lands once the gate completes.
        val summaryGate = CompletableDeferred<Unit>()
        val gateway = object : CoreGateway by fake {
            override suspend fun seriesSummary(seriesId: String, replay: Boolean) = fake.seriesSummary(seriesId, replay).also { summaryGate.await() }
        }

        withDetailViewModel(detailViewModel(gateway, series)) { viewModel ->
            // Seasons loaded but the summary hasn't settled: unresolved, not a provisional guess.
            assertEquals(null, viewModel.state.value.selectedSeasonId)

            summaryGate.complete(Unit)
            runCurrent()

            assertEquals("season-1", viewModel.state.value.selectedSeasonId)
            assertEquals(listOf("s1-ep"), viewModel.state.value.episodes.map { it.id })
        }
    }

    @Test
    fun `seasons never publish before the missing-episodes flag is known, so chips paint their markers from first paint`() = runTest {
        val series = testCard(id = "series-1", itemType = "Series")
        val season1 = testCard(id = "season-1", itemType = "Season", indexNumber = 1)
        val fake = FakeCoreGateway(
            childrenByParent = mapOf("series-1" to listOf(season1), "season-1" to emptyList()),
            settings = defaultTestSettings().copy(showVirtualEpisodes = true),
        )
        // The summary never completes, and the settings owner's first read is held open.
        val settingsGate = CompletableDeferred<Unit>()
        val gateway = object : CoreGateway by fake {
            override suspend fun seriesSummary(seriesId: String, replay: Boolean): SeriesSummary = CompletableDeferred<SeriesSummary>().await()
            override suspend fun getSettings() = fake.getSettings().also { settingsGate.await() }
        }

        withDetailViewModel(detailViewModel(gateway, series)) { viewModel ->
            assertTrue(viewModel.state.value.seasons.isEmpty())

            settingsGate.complete(Unit)
            runCurrent()

            assertEquals(listOf("season-1"), viewModel.state.value.seasons.map { it.id })
            assertEquals(true, viewModel.state.value.showVirtualEpisodes)
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
            seriesPlayBySeriesId = mapOf("series-1" to testSeriesPlay(s2Resuming)),
        )
        // The seasons fetch held open this time; the summary resolves first.
        val seasonsGate = CompletableDeferred<List<Card>>()
        val gateway = object : CoreGateway by fake {
            override suspend fun children(parentId: String, sort: SortOrder, startIndex: UInt, limit: UInt): List<Card> =
                if (parentId == "series-1") seasonsGate.await() else fake.children(parentId, sort, startIndex, limit)
        }

        withDetailViewModel(detailViewModel(gateway, series)) { viewModel ->
            // The summary has settled but seasons haven't loaded -- unresolved.
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
            override suspend fun seriesSummary(seriesId: String, replay: Boolean): SeriesSummary = throw IllegalStateException("boom")
        }

        withDetailViewModel(detailViewModel(gateway, series)) { viewModel ->

            // The summary fails but still resolves the flag, falling back to unplayedCount.
            assertTrue(viewModel.state.value.seasonStats.isEmpty())
            assertTrue(viewModel.state.value.summarySettled)
            assertEquals("season-2", viewModel.state.value.selectedSeasonId)
        }
    }

    @Test
    fun `a failed season query settles the shelf empty instead of loading forever`() = runTest {
        val series = testCard(id = "series-1", itemType = "Series")
        val season1 = testCard(id = "season-1", itemType = "Season", indexNumber = 1)
        val season2 = testCard(id = "season-2", itemType = "Season", indexNumber = 2)
        val fake = FakeCoreGateway(
            childrenByParent = mapOf(
                "series-1" to listOf(season1, season2),
                "season-1" to listOf(testCard(id = "s1-ep", itemType = "Episode")),
            ),
        )
        val gateway = object : CoreGateway by fake {
            // The season-2 read fails: the switch still lands, empty, and says so.
            override suspend fun getSettings() = throw IllegalStateException("boom")
            override suspend fun children(parentId: String, sort: SortOrder, offset: UInt, limit: UInt): List<Card> =
                if (parentId == "season-2") throw IllegalStateException("boom") else fake.children(parentId, sort, offset, limit)
            override suspend fun seasonEpisodes(seasonId: String): List<Card> = children(seasonId, SortOrder.INDEX_NUMBER, 0u, UInt.MAX_VALUE)
        }
        withDetailViewModel(detailViewModel(gateway, series)) { viewModel ->
            viewModel.selectSeason("season-2")

            assertEquals("season-2", viewModel.state.value.selectedSeasonId)
            assertTrue(viewModel.state.value.episodes.isEmpty())
        }
    }

    @Test
    fun `a series with seasons selects one once seasons and the summary settle, so the shelf cannot pulse forever`() = runTest {
        val series = testCard(id = "series-1", itemType = "Series")
        val season1 = testCard(id = "season-1", itemType = "Season", indexNumber = 1)
        val gateway = FakeCoreGateway(
            childrenByParent = mapOf("series-1" to listOf(season1), "season-1" to emptyList()),
        )
        withDetailViewModel(detailViewModel(gateway, series)) { viewModel ->
            val state = viewModel.state.value
            assertTrue(state.seasonsSettled)
            assertTrue(state.summarySettled)
            assertTrue(state.seasons.isNotEmpty())
            assertEquals("season-1", state.selectedSeasonId)
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `a viewer's season tap before resolution wins over the later resume-season answer`() = runTest {
        val series = testCard(id = "series-1", itemType = "Series")
        val season1 = testCard(id = "season-1", itemType = "Season", indexNumber = 1)
        val season2 = testCard(id = "season-2", itemType = "Season", indexNumber = 2)
        val s2Resuming = testCard(id = "s2-ep", itemType = "Episode", parentIndexNumber = 2, positionTicks = 100)
        val s1Ep = testCard(id = "s1-ep", itemType = "Episode", parentIndexNumber = 1)
        val fake = FakeCoreGateway(
            childrenByParent = mapOf(
                "series-1" to listOf(season1, season2),
                "season-1" to listOf(s1Ep),
                "season-2" to listOf(s2Resuming),
            ),
            seriesPlayBySeriesId = mapOf("series-1" to testSeriesPlay(s2Resuming)),
        )
        // The summary held open so selectSeason can be called before it resolves.
        val summaryGate = CompletableDeferred<Unit>()
        val gateway = object : CoreGateway by fake {
            override suspend fun seriesSummary(seriesId: String, replay: Boolean) = fake.seriesSummary(seriesId, replay).also { summaryGate.await() }
        }

        withDetailViewModel(detailViewModel(gateway, series)) { viewModel ->
            // Unresolved: seasons loaded, the summary hasn't -- no provisional guess to tap over.
            assertEquals(null, viewModel.state.value.selectedSeasonId)

            viewModel.selectSeason("season-1")
            runCurrent()
            assertEquals("season-1", viewModel.state.value.selectedSeasonId)

            // The summary resolving with season-2 in progress must not yank the viewer's own pick.
            summaryGate.complete(Unit)
            runCurrent()

            assertEquals("season-1", viewModel.state.value.selectedSeasonId)
            assertEquals(listOf("s1-ep"), viewModel.state.value.episodes.map { it.id })
        }
    }

    @Test
    fun `a movie never reads a series summary or episode list`() = runTest {
        val movie = testCard(id = "movie-1", itemType = "Movie")
        val gateway = FakeCoreGateway()

        withDetailViewModel(detailViewModel(gateway, movie)) { viewModel ->

            assertTrue(viewModel.state.value.seasonStats.isEmpty())
            assertTrue(gateway.seriesSummaryCalls.isEmpty())
            assertTrue(gateway.seriesEpisodesCalls.isEmpty())
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `a mirror change also re-reads the summary, not just the selected season`() = runTest {
        val series = testCard(id = "series-1", itemType = "Series")
        val season1 = testCard(id = "season-1", itemType = "Season", indexNumber = 1)
        val originalEp = testCard(id = "s1-ep", itemType = "Episode", parentIndexNumber = 1, played = false)
        val fake = FakeCoreGateway(
            childrenByParent = mapOf(
                "series-1" to listOf(season1),
                "season-1" to listOf(originalEp),
            ),
        )
        val changes = MutableSharedFlow<ChangeEvent>(extraBufferCapacity = 4)
        // serveUpdated flips only after initial load, so the first read sees the unwatched season.
        var serveUpdated = false
        val gateway = object : CoreGateway by fake {
            override fun changeEvents(): Flow<ChangeEvent> = changes
            override suspend fun seriesSummary(seriesId: String, replay: Boolean) =
                SeriesSummary(null, listOf(testStats("season-1", have = 1, watched = if (serveUpdated) 1 else 0)))
        }

        withDetailViewModel(detailViewModel(gateway, series)) { viewModel ->
            assertEquals(0u, viewModel.state.value.seasonStats.single().watched)

            serveUpdated = true
            changes.tryEmit(ChangeEvent.Upserted(ids = listOf("s1-ep"), libraryId = null))
            runCurrent()

            assertEquals(1u, viewModel.state.value.seasonStats.single().watched)
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

        withDetailViewModel(detailViewModel(gateway, series)) { viewModel ->

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

        withDetailViewModel(detailViewModel(gateway, series)) { viewModel ->

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
        withDetailViewModel(detailViewModel(gateway, series)) { viewModel ->
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

        withDetailViewModel(detailViewModel(gateway, series)) { viewModel ->

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

        withDetailViewModel(detailViewModel(gateway, series)) { viewModel ->

            assertEquals("season-0", viewModel.state.value.selectedSeasonId)
        }
    }

    @Test
    fun `a series with no seasons yet renders an empty tab strip, not a crash`() = runTest {
        val series = testCard(id = "series-1", itemType = "Series")
        val gateway = FakeCoreGateway()

        withDetailViewModel(detailViewModel(gateway, series)) { viewModel ->

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

        withDetailViewModel(detailViewModel(gateway, movie)) { viewModel ->

            assertEquals(detail, viewModel.state.value.itemDetail)
        }
    }

    @Test
    fun `a Series also loads its ItemDetail -- the fetch isn't gated on item type`() = runTest {
        val series = testCard(id = "series-1", itemType = "Series")
        val detail = testItemDetail(id = "series-1")
        val gateway = FakeCoreGateway(itemDetailResultsByItemId = mapOf("series-1" to Result.success(detail)))

        withDetailViewModel(detailViewModel(gateway, series)) { viewModel ->

            assertEquals(detail, viewModel.state.value.itemDetail)
        }
    }

    // -- itemDetailLoaded / similarLoaded: fetch-settled flags --------------

    @Test
    fun `itemDetailLoaded flips true once the fetch succeeds`() = runTest {
        val movie = testCard(id = "movie-1", itemType = "Movie")
        val detail = testItemDetail(id = "movie-1")
        val gateway = FakeCoreGateway(itemDetailResultsByItemId = mapOf("movie-1" to Result.success(detail)))

        withDetailViewModel(detailViewModel(gateway, movie)) { viewModel ->

            assertTrue(viewModel.state.value.itemDetailLoaded)
        }
    }

    @Test
    fun `itemDetailLoaded flips true even when the fetch fails -- a failure still settles`() = runTest {
        // Lets [DetailScreen] stop waiting once the fetch settles, even on failure, instead of
        // reserving a gap forever.
        val movie = testCard(id = "movie-1", itemType = "Movie")
        val gateway = FakeCoreGateway() // no entry configured -- fetch throws

        withDetailViewModel(detailViewModel(gateway, movie)) { viewModel ->

            assertNull(viewModel.state.value.itemDetail)
            assertTrue(viewModel.state.value.itemDetailLoaded)
        }
    }

    @Test
    fun `similarLoaded flips true once the fetch settles, success or empty`() = runTest {
        val movie = testCard(id = "movie-1", itemType = "Movie")
        val gateway = FakeCoreGateway(similarResultsByItemId = mapOf("movie-1" to emptyList()))

        withDetailViewModel(detailViewModel(gateway, movie)) { viewModel ->

            assertTrue(viewModel.state.value.similar.isEmpty())
            assertTrue(viewModel.state.value.similarLoaded)
        }
    }

    @Test
    fun `an Episode page never fetches Similar Titles -- similarLoaded stays false`() = runTest {
        val episode = testCard(id = "ep-1", itemType = "Episode")
        val gateway = FakeCoreGateway()

        withDetailViewModel(detailViewModel(gateway, episode)) { viewModel ->

            assertEquals(false, viewModel.state.value.similarLoaded)
        }
    }

    @Test
    fun `a failed ItemDetail fetch leaves itemDetail null instead of crashing the page`() = runTest {
        // FakeCoreGateway throws UnconfiguredFakeCall here, standing in for a real CoreException;
        // the fail-open contract means it must not propagate.
        val movie = testCard(id = "movie-1", itemType = "Movie")
        val gateway = FakeCoreGateway()

        withDetailViewModel(detailViewModel(gateway, movie)) { viewModel ->

            assertNull(viewModel.state.value.itemDetail)
        }
    }

    @Test
    fun `a Movie loads Similar Titles, capped at the spec's limit of 16`() = runTest {
        val movie = testCard(id = "movie-1", itemType = "Movie")
        val similar = listOf(testCard(id = "similar-1"))
        val gateway = FakeCoreGateway(similarResultsByItemId = mapOf("movie-1" to similar))

        withDetailViewModel(detailViewModel(gateway, movie)) { viewModel ->

            assertEquals(similar, viewModel.state.value.similar)
            assertEquals(listOf(FakeCoreGateway.GetSimilarCall("movie-1", 16u)), gateway.getSimilarCalls)
        }
    }

    @Test
    fun `a Series loads Similar Titles too`() = runTest {
        val series = testCard(id = "series-1", itemType = "Series")
        val similar = listOf(testCard(id = "similar-1"))
        val gateway = FakeCoreGateway(similarResultsByItemId = mapOf("series-1" to similar))

        withDetailViewModel(detailViewModel(gateway, series)) { viewModel ->

            assertEquals(similar, viewModel.state.value.similar)
        }
    }

    @Test
    fun `an Episode page never fetches Similar Titles at all -- skipped, not just hidden`() = runTest {
        val episode = testCard(id = "ep-1", itemType = "Episode")
        // Configured on purpose: state alone can't prove the fetch was skipped; the call log does.
        val gateway = FakeCoreGateway(similarResultsByItemId = mapOf("ep-1" to listOf(testCard(id = "similar-1"))))

        withDetailViewModel(detailViewModel(gateway, episode)) { viewModel ->

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

        withDetailViewModel(detailViewModel(gateway, episode)) { viewModel ->

            assertEquals(next, viewModel.state.value.nextEpisode)
            assertEquals(listOf("ep-1"), gateway.nextEpisodeAfterCalls)
        }
    }

    @Test
    fun `a Series or Movie page never fetches nextEpisode -- Episode only`() = runTest {
        val movie = testCard(id = "movie-1", itemType = "Movie")
        val gateway = FakeCoreGateway()

        withDetailViewModel(detailViewModel(gateway, movie)) { viewModel ->

            assertNull(viewModel.state.value.nextEpisode)
            assertTrue(gateway.nextEpisodeAfterCalls.isEmpty())
        }
    }

    @Test
    fun `a Movie page resolves its library name from the server-configured view list`() = runTest {
        val movie = testCard(id = "movie-1", itemType = "Movie", libraryId = "lib-1")
        val gateway = FakeCoreGateway(viewsList = listOf(ViewSnapshot(id = "lib-1", name = "My Movies", kind = ViewKind.LIBRARY), ViewSnapshot(id = "lib-2", name = "TV Shows", kind = ViewKind.LIBRARY)))

        withDetailViewModel(detailViewModel(gateway, movie)) { viewModel ->

            assertEquals("My Movies", viewModel.state.value.libraryName)
        }
    }

    @Test
    fun `a card with no libraryId never resolves a library name`() = runTest {
        val movie = testCard(id = "movie-1", itemType = "Movie", libraryId = null)
        val gateway = FakeCoreGateway(viewsList = listOf(ViewSnapshot(id = "lib-1", name = "My Movies", kind = ViewKind.LIBRARY)))

        withDetailViewModel(detailViewModel(gateway, movie)) { viewModel ->

            assertNull(viewModel.state.value.libraryName)
        }
    }

    @Test
    fun `an Episode page never resolves a library name`() = runTest {
        val episode = testCard(id = "ep-1", itemType = "Episode", libraryId = "lib-1")
        val gateway = FakeCoreGateway(viewsList = listOf(ViewSnapshot(id = "lib-1", name = "My Movies", kind = ViewKind.LIBRARY)))

        withDetailViewModel(detailViewModel(gateway, episode)) { viewModel ->

            assertNull(viewModel.state.value.libraryName)
        }
    }

    // -- nextEpisodeLoaded / libraryNameLoaded: settled flags (docs/11 §Loading state) ----

    @Test
    fun `nextEpisodeLoaded flips true when an Episode page finds a next episode`() = runTest {
        val episode = testCard(id = "ep-1", itemType = "Episode")
        val next = testCard(id = "ep-2", itemType = "Episode", indexNumber = 2)
        val gateway = FakeCoreGateway(nextEpisodeByItemId = mapOf("ep-1" to next))

        withDetailViewModel(detailViewModel(gateway, episode)) { viewModel ->

            assertTrue(viewModel.state.value.nextEpisodeLoaded)
        }
    }

    @Test
    fun `nextEpisodeLoaded flips true when there is no next episode -- settled empty`() = runTest {
        val episode = testCard(id = "ep-1", itemType = "Episode")
        val gateway = FakeCoreGateway()

        withDetailViewModel(detailViewModel(gateway, episode)) { viewModel ->

            assertNull(viewModel.state.value.nextEpisode)
            assertTrue(viewModel.state.value.nextEpisodeLoaded)
        }
    }

    @Test
    fun `nextEpisodeLoaded flips true when the lookup fails`() = runTest {
        val episode = testCard(id = "ep-1", itemType = "Episode")
        val gateway = FakeCoreGateway(nextEpisodeError = IllegalStateException("offline"))

        withDetailViewModel(detailViewModel(gateway, episode)) { viewModel ->

            assertNull(viewModel.state.value.nextEpisode)
            assertTrue(viewModel.state.value.nextEpisodeLoaded)
        }
    }

    @Test
    fun `a Movie page never looks up Up Next -- nextEpisodeLoaded stays false`() = runTest {
        val movie = testCard(id = "movie-1", itemType = "Movie")
        val gateway = FakeCoreGateway()

        withDetailViewModel(detailViewModel(gateway, movie)) { viewModel ->

            assertFalse(viewModel.state.value.nextEpisodeLoaded)
        }
    }

    @Test
    fun `libraryNameLoaded flips true once the view list resolves the name`() = runTest {
        val movie = testCard(id = "movie-1", itemType = "Movie", libraryId = "lib-1")
        val gateway = FakeCoreGateway(viewsList = listOf(ViewSnapshot(id = "lib-1", name = "My Movies", kind = ViewKind.LIBRARY)))

        withDetailViewModel(detailViewModel(gateway, movie)) { viewModel ->

            assertEquals("My Movies", viewModel.state.value.libraryName)
            assertTrue(viewModel.state.value.libraryNameLoaded)
        }
    }

    @Test
    fun `libraryNameLoaded flips true for a card with no library id -- the early return settles`() = runTest {
        val movie = testCard(id = "movie-1", itemType = "Movie", libraryId = null)
        val gateway = FakeCoreGateway(viewsList = listOf(ViewSnapshot(id = "lib-1", name = "My Movies", kind = ViewKind.LIBRARY)))

        withDetailViewModel(detailViewModel(gateway, movie)) { viewModel ->

            assertNull(viewModel.state.value.libraryName)
            assertTrue(viewModel.state.value.libraryNameLoaded)
        }
    }

    @Test
    fun `libraryNameLoaded flips true when no view matches the library id`() = runTest {
        val movie = testCard(id = "movie-1", itemType = "Movie", libraryId = "lib-9")
        val gateway = FakeCoreGateway(viewsList = listOf(ViewSnapshot(id = "lib-1", name = "My Movies", kind = ViewKind.LIBRARY)))

        withDetailViewModel(detailViewModel(gateway, movie)) { viewModel ->

            assertNull(viewModel.state.value.libraryName)
            assertTrue(viewModel.state.value.libraryNameLoaded)
        }
    }

    @Test
    fun `libraryNameLoaded flips true when the view list fails to load`() = runTest {
        val series = testCard(id = "series-1", itemType = "Series", libraryId = "lib-1")
        val gateway = FakeCoreGateway(viewsError = IllegalStateException("offline"))

        withDetailViewModel(detailViewModel(gateway, series)) { viewModel ->

            assertNull(viewModel.state.value.libraryName)
            assertTrue(viewModel.state.value.libraryNameLoaded)
        }
    }

    @Test
    fun `an Episode page never loads a library name -- libraryNameLoaded stays false`() = runTest {
        val episode = testCard(id = "ep-1", itemType = "Episode", libraryId = "lib-1")
        val gateway = FakeCoreGateway(viewsList = listOf(ViewSnapshot(id = "lib-1", name = "My Movies", kind = ViewKind.LIBRARY)))

        withDetailViewModel(detailViewModel(gateway, episode)) { viewModel ->

            assertFalse(viewModel.state.value.libraryNameLoaded)
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

        withDetailViewModel(detailViewModel(gateway, series)) { viewModel ->

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
        withDetailViewModel(detailViewModel(gateway, series)) { viewModel ->

            viewModel.selectSeason("season-2")

            assertEquals(listOf("s2-ep"), viewModel.state.value.episodes.map { it.id })
        }
    }

    @Test
    fun `seasons list is deduped by id`() = runTest {
        val series = testCard(id = "series-1", itemType = "Series")
        val dupe = testCard(id = "season-1", itemType = "Season", indexNumber = 1)
        val gateway = FakeCoreGateway(
            childrenByParent = mapOf("series-1" to listOf(dupe, dupe.copy(name = "Duplicate"))),
        )

        withDetailViewModel(detailViewModel(gateway, series)) { viewModel ->

            assertEquals(listOf("season-1"), viewModel.state.value.seasons.map { it.id })
        }
    }

    @Test
    fun `Similar Titles is deduped by id -- a server returning the same title twice must not crash`() = runTest {
        val movie = testCard(id = "movie-1", itemType = "Movie")
        val dupe = testCard(id = "similar-1")
        val gateway = FakeCoreGateway(similarResultsByItemId = mapOf("movie-1" to listOf(dupe, dupe.copy(name = "Duplicate"))))

        withDetailViewModel(detailViewModel(gateway, movie)) { viewModel ->

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

        withDetailViewModel(detailViewModel(gateway, episode)) { viewModel ->
            assertEquals(1_000L, viewModel.state.value.card.positionTicks)

            changes.tryEmit(ChangeEvent.Upserted(ids = listOf("ep-1"), libraryId = null))
            runCurrent()

            assertEquals(5_000L, viewModel.state.value.card.positionTicks)
            assertTrue(viewModel.state.value.card.played)
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `a stop's mirror write for another item leaves a retained movie page alone`() = runTest {
        // docs/17 §6: every Detail in the back stack hears every stop as its item's Upserted; only that item's page re-reads.
        for ((stoppedId, refreshes) in listOf("movie-2" to false, "movie-1" to true)) {
            val movie = testCard(id = "movie-1", itemType = "Movie")
            val fake = FakeCoreGateway().apply { cardsByItemId["movie-1"] = movie }

            withDetailViewModel(detailViewModel(fake, movie)) {
                runCurrent()
                val before = fake.cardByIdCalls.size

                fake.emitChange(ChangeEvent.Upserted(ids = listOf(stoppedId), libraryId = null))
                runCurrent()

                assertEquals("stopped item $stoppedId", before + if (refreshes) 1 else 0, fake.cardByIdCalls.size)
            }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `a stop landing while the page is hidden refreshes it once on return`() = runTest {
        // docs/17 §6: a PiP dismissal stops playback with this page hidden under the player.
        val movie = testCard(id = "movie-1", itemType = "Movie", positionTicks = 1_000L)
        val fake = FakeCoreGateway().apply { cardsByItemId["movie-1"] = movie.copy(positionTicks = 9_000L) }

        withDetailViewModel(detailViewModel(fake, movie)) { viewModel ->
            viewModel.setActive(false)
            runCurrent()
            val before = fake.cardByIdCalls.size

            fake.emitChange(ChangeEvent.Upserted(ids = listOf("movie-1"), libraryId = null))
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals("hidden: no read", before, fake.cardByIdCalls.size)

            viewModel.setActive(true)
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals("one read on return", before + 1, fake.cardByIdCalls.size)
            assertEquals(9_000L, viewModel.state.value.card.positionTicks)
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `a stop's mirror write for another item leaves a retained series page alone, one of its episodes does not`() = runTest {
        for ((stoppedId, refreshes) in listOf("other-item" to false, "ep-1" to true)) {
            val series = testCard(id = "series-1", itemType = "Series")
            val fake = FakeCoreGateway(
                childrenByParent = mapOf(
                    "series-1" to listOf(testCard(id = "season-1", itemType = "Season", indexNumber = 1, name = "Season 1")),
                    "season-1" to listOf(testCard(id = "ep-1", itemType = "Episode", indexNumber = 1)),
                ),
            )

            withDetailViewModel(detailViewModel(fake, series)) {
                runCurrent()
                val before = fake.childrenCalls.size

                fake.emitChange(ChangeEvent.Upserted(ids = listOf(stoppedId), libraryId = null))
                runCurrent()

                assertEquals("stopped item $stoppedId refreshes=$refreshes", refreshes, fake.childrenCalls.size > before)
            }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `a movie or episode page closes when the mirror drops its item`() = runTest {
        // docs/07 §5: a removed item's page must not stay up on stale data.
        for (itemType in listOf("Movie", "Episode")) {
            val card = testCard(id = "item-1", itemType = itemType)
            val fake = FakeCoreGateway().apply { cardsByItemId["item-1"] = card }
            val changes = MutableSharedFlow<ChangeEvent>(extraBufferCapacity = 4)
            val gateway = object : CoreGateway by fake {
                override fun changeEvents(): Flow<ChangeEvent> = changes
            }

            withDetailViewModel(detailViewModel(gateway, card)) { viewModel ->
                runCurrent()
                assertFalse(viewModel.state.value.itemRemoved)

                changes.tryEmit(ChangeEvent.Removed(ids = listOf("item-2"), libraryId = null))
                runCurrent()
                assertFalse("$itemType: another item's removal is not this page's", viewModel.state.value.itemRemoved)

                changes.tryEmit(ChangeEvent.Removed(ids = listOf("item-1"), libraryId = null))
                runCurrent()
                assertTrue("$itemType: its own removal closes the page", viewModel.state.value.itemRemoved)
            }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `a merged refresh closes the page only when the item is gone from the mirror`() = runTest {
        // docs/07 §5: a Removed merged into a Refresh still removes the page; a failed probe never does.
        val table = listOf("gone" to true, "present" to false, "failed" to false)
        for ((probe, closes) in table) {
            val card = testCard(id = "item-1", itemType = "Movie")
            val fake = FakeCoreGateway().apply { if (probe == "present") cardsByItemId["item-1"] = card }
            val changes = MutableSharedFlow<ChangeEvent>(extraBufferCapacity = 4)
            val gateway = object : CoreGateway by fake {
                override fun changeEvents(): Flow<ChangeEvent> = changes
                override suspend fun cardById(itemId: String): Card? =
                    if (probe == "failed") throw IllegalStateException("mirror read failed") else fake.cardById(itemId)
            }

            withDetailViewModel(detailViewModel(gateway, card)) { viewModel ->
                runCurrent()

                changes.tryEmit(ChangeEvent.Refresh)
                runCurrent()

                assertEquals("probe $probe", closes, viewModel.state.value.itemRemoved)
            }
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

        withDetailViewModel(detailViewModel(gateway, movie)) { viewModel ->

            assertEquals(listOf("c1", "c2"), viewModel.state.value.collections.map { it.id })
            assertTrue(viewModel.state.value.isAdministrator)
            assertEquals(setOf("c2"), viewModel.state.value.memberOfCollections)
        }
    }

    @Test
    fun `a failed collections, admin, or membership fetch fails open -- empty, ordinary user`() = runTest {
        val movie = testCard(id = "movie-1", itemType = "Movie")
        withDetailViewModel(detailViewModel(FakeCoreGateway(), movie)) { viewModel ->

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
        withDetailViewModel(detailViewModel(gateway, movie)) { viewModel ->

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
        withDetailViewModel(detailViewModel(gateway, series)) { viewModel ->
            viewModel.selectSeason("season-2")

            viewModel.openMenu()

            assertEquals(2, viewModel.state.value.menu?.scopeSeasonNumber)
        }
    }

    @Test
    fun `mark watched calls setPlayed, closes the menu, and toasts Marked as watched`() = runTest {
        val movie = testCard(id = "movie-1", itemType = "Movie", played = false)
        val gateway = FakeCoreGateway()
        withDetailViewModel(detailViewModel(gateway, movie)) { viewModel ->
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
        withDetailViewModel(detailViewModel(gateway, movie)) { viewModel ->
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
        val viewModel = detailViewModel(gateway, movie)
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
        withDetailViewModel(detailViewModel(gateway, movie)) { viewModel ->
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
        val gateway = FakeCoreGateway(seasonStatsBySeriesId = mapOf("series-1" to listOf(testStats(have = 1))))
        withDetailViewModel(detailViewModel(gateway, series)) { viewModel ->
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
            seasonStatsBySeriesId = mapOf("series-1" to listOf(testStats("season-1", have = 1))),
        )
        withDetailViewModel(detailViewModel(gateway, series)) { viewModel ->
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
        val gateway = FakeCoreGateway(seasonStatsBySeriesId = mapOf("series-1" to listOf(testStats(have = 1))))
        withDetailViewModel(detailViewModel(gateway, series)) { viewModel ->
            viewModel.openMenu()
            val row = viewModel.state.value.menu!!.model.groups.flatMap { it.rows }.first { it.action is MenuAction.MarkScopeWatched }
            viewModel.runAction(row.action)

            viewModel.dismissConfirm()

            assertNull(viewModel.state.value.confirm)
            assertTrue(viewModel.state.value.menu != null)
            assertTrue(gateway.setPlayedRecursiveCalls.isEmpty())
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `play something random at series scope reads the episodes only when it runs, and hands back a pending playback target`() = runTest {
        val series = testCard(id = "series-1", itemType = "Series")
        val ep1 = testCard(id = "e1", itemType = "Episode", played = false)
        val ep2 = testCard(id = "e2", itemType = "Episode", played = true)
        val gateway = FakeCoreGateway(
            seriesEpisodesBySeriesId = mapOf("series-1" to listOf(ep1, ep2)),
            seasonStatsBySeriesId = mapOf("series-1" to listOf(testStats(have = 2, watched = 1, anyProgress = true))),
        )
        withDetailViewModel(detailViewModel(gateway, series, random = Random(42))) { viewModel ->
            viewModel.openMenu()
            val row = viewModel.state.value.menu!!.model.groups.flatMap { it.rows }.first { it.action is MenuAction.PlayRandom }
            assertTrue("opening the page and the menu reads no episode list", gateway.seriesEpisodesCalls.isEmpty())

            viewModel.runAction(row.action)
            runCurrent()

            assertEquals(listOf("series-1"), gateway.seriesEpisodesCalls)

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

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `a series-scope random pick runs once at a time and dies with the menu`() = runTest {
        val series = testCard(id = "series-1", itemType = "Series")
        val fake = FakeCoreGateway(
            seriesEpisodesBySeriesId = mapOf("series-1" to listOf(testCard(id = "e1", itemType = "Episode"))),
            seasonStatsBySeriesId = mapOf("series-1" to listOf(testStats(have = 1))),
        )
        val gate = CompletableDeferred<Unit>()
        var reads = 0
        val gateway = object : CoreGateway by fake {
            override suspend fun seriesEpisodes(seriesId: String): List<Card> {
                reads++
                gate.await()
                return fake.seriesEpisodes(seriesId)
            }
        }
        withDetailViewModel(detailViewModel(gateway, series, random = Random(1))) { viewModel ->
            viewModel.openMenu()
            val row = viewModel.state.value.menu!!.model.groups.flatMap { it.rows }.first { it.action is MenuAction.PlayRandom }

            viewModel.runAction(row.action)
            viewModel.runAction(row.action) // a second Select while the read runs
            runCurrent()
            assertEquals(1, reads)

            viewModel.closeMenu() // Back while reading
            gate.complete(Unit)
            runCurrent()

            assertNull("no playback after the menu was dismissed", viewModel.state.value.pendingPlayback)
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `a random pick still reading dies when another menu row closes the menu`() = runTest {
        val series = testCard(id = "series-1", itemType = "Series")
        val fake = FakeCoreGateway(
            seriesEpisodesBySeriesId = mapOf("series-1" to listOf(testCard(id = "e1", itemType = "Episode"))),
            seasonStatsBySeriesId = mapOf("series-1" to listOf(testStats(have = 1))),
        )
        val gate = CompletableDeferred<Unit>()
        val gateway = object : CoreGateway by fake {
            override suspend fun seriesEpisodes(seriesId: String): List<Card> {
                gate.await()
                return fake.seriesEpisodes(seriesId)
            }
        }
        withDetailViewModel(detailViewModel(gateway, series, random = Random(1))) { viewModel ->
            viewModel.openMenu()
            val random = viewModel.state.value.menu!!.model.groups.flatMap { it.rows }.first { it.action is MenuAction.PlayRandom }
            viewModel.runAction(random.action)
            runCurrent()

            viewModel.runAction(MenuAction.PlayFromBeginning("e-other")) // another row while the read runs
            assertEquals(PendingPlayback("e-other", startFromBeginning = true), viewModel.state.value.pendingPlayback)
            viewModel.consumePendingPlayback()
            gate.complete(Unit)
            runCurrent()

            assertNull("the closed menu's random pick never plays", viewModel.state.value.pendingPlayback)
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
        withDetailViewModel(detailViewModel(gateway, movie)) { viewModel ->
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
        withDetailViewModel(detailViewModel(gateway, movie)) { viewModel ->
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
        withDetailViewModel(detailViewModel(gateway, movie)) { viewModel ->
            viewModel.openMenu()

            viewModel.selectCollection(CollectionInfo(id = "c1", name = "Alpha"))

            assertEquals("Couldn't reach the server", viewModel.state.value.toast)
            assertTrue(viewModel.state.value.memberOfCollections.isEmpty())
        }
    }

    @Test
    fun `Go to series closes the menu with no toast and hands back the series card`() = runTest {
        val episode = testCard(id = "ep-1", itemType = "Episode", seriesId = "series-1", seriesName = "Sample Series Title")
        withDetailViewModel(detailViewModel(FakeCoreGateway(), episode)) { viewModel ->
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
            seriesPlayBySeriesId = mapOf(
                "show" to testSeriesPlay(
                    testCard(id = "e2", itemType = "Episode", parentIndexNumber = 1, indexNumber = 2),
                    seasonId = "s1",
                    kind = SeriesPlayKind.NEXT_UP,
                ),
            ),
        )

        withDetailViewModel(detailViewModel(gateway, box)) { viewModel ->
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

        withDetailViewModel(detailViewModel(gateway, box)) { viewModel ->
            viewModel.playCollection()
            assertEquals(PendingPlayback("a", startFromBeginning = true), viewModel.state.value.pendingPlayback)
        }
    }

    @Test
    fun `an empty collection settles with no play target`() = runTest {
        val box = testCard(id = "box", itemType = "BoxSet")

        withDetailViewModel(detailViewModel(FakeCoreGateway(), box)) { viewModel ->
            assertTrue(viewModel.state.value.membersSettled)
            assertNull(viewModel.state.value.collectionPlay)
        }
    }

    // -- mirror-first loadItemDetail (docs/11 item 1) ------------------------

    @Test
    fun `the mirror record paints before the live ItemDetail arrives`() = runTest {
        val movie = testCard(id = "movie-1", itemType = "Movie")
        val local = testItemDetail(id = "movie-1").copy(name = "Local")
        val liveGate = CompletableDeferred<ItemDetail>()
        val fake = FakeCoreGateway()
        fake.itemDetailLocalByItemId["movie-1"] = local
        val gateway = object : CoreGateway by fake {
            override suspend fun getItemDetail(itemId: String, accountEpoch: ULong?): ItemDetail = liveGate.await()
        }

        withDetailViewModel(detailViewModel(gateway, movie)) { viewModel ->
            assertEquals(local, viewModel.state.value.itemDetail)
            assertFalse(viewModel.state.value.itemDetailLoaded)
        }
    }

    @Test
    fun `the live ItemDetail replaces the mirror record and settles`() = runTest {
        val movie = testCard(id = "movie-1", itemType = "Movie")
        val local = testItemDetail(id = "movie-1").copy(name = "Local")
        val live = testItemDetail(id = "movie-1").copy(name = "Live")
        val liveGate = CompletableDeferred<ItemDetail>()
        val fake = FakeCoreGateway()
        fake.itemDetailLocalByItemId["movie-1"] = local
        val gateway = object : CoreGateway by fake {
            override suspend fun getItemDetail(itemId: String, accountEpoch: ULong?): ItemDetail = liveGate.await()
        }

        withDetailViewModel(detailViewModel(gateway, movie)) { viewModel ->
            liveGate.complete(live)
            runCurrent()

            assertEquals(live, viewModel.state.value.itemDetail)
            assertTrue(viewModel.state.value.itemDetailLoaded)
        }
    }

    @Test
    fun `a failing live fetch keeps the mirror record and still settles`() = runTest {
        val movie = testCard(id = "movie-1", itemType = "Movie")
        val local = testItemDetail(id = "movie-1").copy(name = "Local")
        val liveGate = CompletableDeferred<ItemDetail>()
        val fake = FakeCoreGateway()
        fake.itemDetailLocalByItemId["movie-1"] = local
        val gateway = object : CoreGateway by fake {
            override suspend fun getItemDetail(itemId: String, accountEpoch: ULong?): ItemDetail = liveGate.await()
        }

        withDetailViewModel(detailViewModel(gateway, movie)) { viewModel ->
            liveGate.completeExceptionally(IllegalStateException("synthetic"))
            runCurrent()

            assertEquals(local, viewModel.state.value.itemDetail)
            assertTrue(viewModel.state.value.itemDetailLoaded)
        }
    }

    @Test
    fun `no mirror record and a live success shows the live ItemDetail`() = runTest {
        val movie = testCard(id = "movie-1", itemType = "Movie")
        val live = testItemDetail(id = "movie-1").copy(name = "Live")
        val fake = FakeCoreGateway(itemDetailResultsByItemId = mapOf("movie-1" to Result.success(live)))

        withDetailViewModel(detailViewModel(fake, movie)) { viewModel ->
            assertEquals(listOf("movie-1"), fake.itemDetailLocalCalls)
            assertEquals(live, viewModel.state.value.itemDetail)
            assertTrue(viewModel.state.value.itemDetailLoaded)
        }
    }

    @Test
    fun `no mirror record and a failing live fetch leaves itemDetail null but settled`() = runTest {
        val movie = testCard(id = "movie-1", itemType = "Movie")

        withDetailViewModel(detailViewModel(FakeCoreGateway(), movie)) { viewModel ->
            assertNull(viewModel.state.value.itemDetail)
            assertTrue(viewModel.state.value.itemDetailLoaded)
        }
    }

    // -- season switch: only the shown season crosses FFI ----------------------

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `a season switch keeps the shown chip and shelf until the new season lands, then swaps both in one emission`() = runTest {
        val series = testCard(id = "series-1", itemType = "Series")
        val season1 = testCard(id = "season-1", itemType = "Season", indexNumber = 1)
        val season2 = testCard(id = "season-2", itemType = "Season", indexNumber = 2)
        val fake = FakeCoreGateway(
            childrenByParent = mapOf(
                "series-1" to listOf(season1, season2),
                "season-1" to listOf(testCard(id = "s1-ep", itemType = "Episode", indexNumber = 1, parentIndexNumber = 1)),
                "season-2" to listOf(testCard(id = "s2-ep", itemType = "Episode", indexNumber = 1, parentIndexNumber = 2)),
            ),
        )
        val gate = CompletableDeferred<Unit>()
        val gateway = object : CoreGateway by fake {
            override suspend fun seasonEpisodes(seasonId: String): List<Card> =
                fake.seasonEpisodes(seasonId).also { if (seasonId == "season-2") gate.await() }
        }

        withDetailViewModel(detailViewModel(gateway, series)) { viewModel ->
            assertEquals("season-1", viewModel.state.value.selectedSeasonId)
            val states = record(viewModel)

            assertTrue(viewModel.state.value.let { episodeShelfEnterable(it.requestedSeasonId, it.selectedSeasonId) })
            viewModel.selectSeason("season-2")
            runCurrent()
            assertEquals("season-1", viewModel.state.value.selectedSeasonId)
            assertEquals(listOf("s1-ep"), viewModel.state.value.episodes.map { it.id })
            assertFalse(
                "docs/15 §5: the old season's shelf takes no focus while the switch reads",
                viewModel.state.value.let { episodeShelfEnterable(it.requestedSeasonId, it.selectedSeasonId) },
            )

            gate.complete(Unit)
            runCurrent()

            assertEquals("season-2", viewModel.state.value.selectedSeasonId)
            assertEquals(listOf("s2-ep"), viewModel.state.value.episodes.map { it.id })
            assertTrue(viewModel.state.value.let { episodeShelfEnterable(it.requestedSeasonId, it.selectedSeasonId) })
            val pairs = states.map { it.selectedSeasonId to it.episodes.map { e -> e.id } }.distinct()
            assertEquals(listOf("season-1" to listOf("s1-ep"), "season-2" to listOf("s2-ep")), pairs)
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `a season switch reads only that season's episodes, in index order`() = runTest {
        val series = testCard(id = "series-1", itemType = "Series")
        val season1 = testCard(id = "season-1", itemType = "Season", indexNumber = 1)
        val season2 = testCard(id = "season-2", itemType = "Season", indexNumber = 2)
        val s1Ep = testCard(id = "s1-ep", itemType = "Episode", indexNumber = 1, parentIndexNumber = 1)
        val s2Ep2 = testCard(id = "s2-ep-2", itemType = "Episode", indexNumber = 2, parentIndexNumber = 2)
        val s2Ep1 = testCard(id = "s2-ep-1", itemType = "Episode", indexNumber = 1, parentIndexNumber = 2)
        val gateway = FakeCoreGateway(
            childrenByParent = mapOf(
                "series-1" to listOf(season1, season2),
                "season-1" to listOf(s1Ep),
                "season-2" to listOf(s2Ep2, s2Ep1),
            ),
        )

        withDetailViewModel(detailViewModel(gateway, series)) { viewModel ->
            assertTrue(viewModel.state.value.summarySettled)

            viewModel.selectSeason("season-2")
            runCurrent()

            val state = viewModel.state.value
            assertEquals("season-2", state.selectedSeasonId)
            assertEquals(listOf("s2-ep-1", "s2-ep-2"), state.episodes.map { it.id })
            assertEquals(listOf("season-1", "season-2"), gateway.childrenCalls.map { it.parentId }.filter { it.startsWith("season-") })
            assertTrue(gateway.seriesEpisodesCalls.isEmpty())
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `an episode added while the page is open shows when its season is selected`() = runTest {
        val series = testCard(id = "series-1", itemType = "Series")
        val season1 = testCard(id = "season-1", itemType = "Season", indexNumber = 1)
        val season2 = testCard(id = "season-2", itemType = "Season", indexNumber = 2)
        val s1Ep = testCard(id = "s1-ep", itemType = "Episode", indexNumber = 1, parentIndexNumber = 1)
        val s2Ep = testCard(id = "s2-ep-1", itemType = "Episode", indexNumber = 1, parentIndexNumber = 2)
        val newEp = testCard(id = "s2-ep-2", itemType = "Episode", indexNumber = 2, parentIndexNumber = 2, seriesId = "series-1")
        val fake = FakeCoreGateway(
            childrenByParent = mapOf(
                "series-1" to listOf(season1, season2),
                "season-1" to listOf(s1Ep),
                "season-2" to listOf(s2Ep),
            ),
        )
        fake.cardsByItemId["s2-ep-2"] = newEp
        val changes = MutableSharedFlow<ChangeEvent>(extraBufferCapacity = 4)
        var added = false
        val gateway = object : CoreGateway by fake {
            override fun changeEvents(): Flow<ChangeEvent> = changes
            override suspend fun children(parentId: String, sort: SortOrder, offset: UInt, limit: UInt): List<Card> =
                fake.children(parentId, sort, offset, limit) + if (added && parentId == "season-2") listOf(newEp) else emptyList()
            override suspend fun seasonEpisodes(seasonId: String): List<Card> = children(seasonId, SortOrder.INDEX_NUMBER, 0u, UInt.MAX_VALUE)
        }

        withDetailViewModel(detailViewModel(gateway, series)) { viewModel ->
            assertTrue(viewModel.state.value.summarySettled)

            added = true
            changes.tryEmit(ChangeEvent.Upserted(ids = listOf("s2-ep-2"), libraryId = null))
            runCurrent()
            viewModel.selectSeason("season-2")
            runCurrent()

            assertEquals(listOf("s2-ep-1", "s2-ep-2"), viewModel.state.value.episodes.map { it.id })
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `an unknown id that belongs to another series does not trigger a refetch`() = runTest {
        val series = testCard(id = "series-1", itemType = "Series")
        val season1 = testCard(id = "season-1", itemType = "Season", indexNumber = 1)
        val s1Ep = testCard(id = "s1-ep", itemType = "Episode", indexNumber = 1, parentIndexNumber = 1)
        val foreign = testCard(id = "foreign-ep", itemType = "Episode", seriesId = "series-2")
        val fake = FakeCoreGateway(
            childrenByParent = mapOf("series-1" to listOf(season1), "season-1" to listOf(s1Ep)),
        )
        fake.cardsByItemId["foreign-ep"] = foreign
        val changes = MutableSharedFlow<ChangeEvent>(extraBufferCapacity = 4)
        val gateway = object : CoreGateway by fake {
            override fun changeEvents(): Flow<ChangeEvent> = changes
        }

        withDetailViewModel(detailViewModel(gateway, series)) {
            val childrenBefore = fake.childrenCalls.size
            val summariesBefore = fake.seriesSummaryCalls.size

            changes.tryEmit(ChangeEvent.Upserted(ids = listOf("foreign-ep"), libraryId = null))
            runCurrent()

            assertEquals(childrenBefore, fake.childrenCalls.size)
            assertEquals(summariesBefore, fake.seriesSummaryCalls.size)
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `a removed selected season hands selection to a surviving season and its episodes`() = runTest {
        val series = testCard(id = "series-1", itemType = "Series")
        val season1 = testCard(id = "season-1", itemType = "Season", indexNumber = 1)
        val season2 = testCard(id = "season-2", itemType = "Season", indexNumber = 2)
        val s1Ep = testCard(id = "s1-ep", itemType = "Episode", indexNumber = 1, parentIndexNumber = 1)
        val s2Ep = testCard(id = "s2-ep", itemType = "Episode", indexNumber = 1, parentIndexNumber = 2)
        val fake = FakeCoreGateway(
            childrenByParent = mapOf(
                "series-1" to listOf(season1, season2),
                "season-1" to listOf(s1Ep),
                "season-2" to listOf(s2Ep),
            ),
        )
        val changes = MutableSharedFlow<ChangeEvent>(extraBufferCapacity = 4)
        var removed = false
        val gateway = object : CoreGateway by fake {
            override fun changeEvents(): Flow<ChangeEvent> = changes
            override suspend fun children(parentId: String, sort: SortOrder, offset: UInt, limit: UInt): List<Card> =
                fake.children(parentId, sort, offset, limit).filter { !removed || (it.id != "season-2" && it.id != "s2-ep") }
            override suspend fun seasonEpisodes(seasonId: String): List<Card> = children(seasonId, SortOrder.INDEX_NUMBER, 0u, UInt.MAX_VALUE)
        }

        withDetailViewModel(detailViewModel(gateway, series)) { viewModel ->
            viewModel.selectSeason("season-2")
            runCurrent()
            assertEquals("season-2", viewModel.state.value.selectedSeasonId)

            removed = true
            changes.tryEmit(ChangeEvent.Removed(ids = listOf("season-2"), libraryId = null))
            runCurrent()

            assertEquals("season-1", viewModel.state.value.selectedSeasonId)
            assertEquals(listOf("s1-ep"), viewModel.state.value.episodes.map { it.id })
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `a relevant event is not dropped while an earlier probe is suspended`() = runTest {
        val series = testCard(id = "series-1", itemType = "Series", libraryId = "lib-a")
        val season1 = testCard(id = "season-1", itemType = "Season", indexNumber = 1)
        val s1Ep = testCard(id = "s1-ep", itemType = "Episode", indexNumber = 1, parentIndexNumber = 1)
        val fake = FakeCoreGateway(
            childrenByParent = mapOf("series-1" to listOf(season1), "season-1" to listOf(s1Ep)),
        )
        // Mirrors CoreGateway's production bus: extraBufferCapacity 8, DROP_OLDEST.
        val bus = MutableSharedFlow<ChangeEvent>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        val gate = CompletableDeferred<Unit>()
        val gateway = object : CoreGateway by fake {
            override fun changeEvents(): Flow<ChangeEvent> = bus
            override suspend fun cardsByIds(itemIds: List<String>): List<Card> {
                gate.await()
                return emptyList()
            }
        }

        withDetailViewModel(detailViewModel(gateway, series)) {
            val before = fake.childrenCalls.size

            bus.tryEmit(ChangeEvent.Upserted(ids = listOf("unknown"), libraryId = null)) // probe suspends
            bus.tryEmit(ChangeEvent.Refresh)
            repeat(10) { bus.tryEmit(ChangeEvent.Removed(ids = listOf("other-$it"), libraryId = "lib-b")) }
            gate.complete(Unit)
            advanceTimeBy(1_000)
            runCurrent()

            assertTrue(fake.childrenCalls.size > before)
        }
    }

    // State x event matrix for the Series page's selection/episodes lifecycle (docs/15 §0.2/§5, docs/19).

    private fun seasonCard(n: Int) = testCard(id = "season-$n", itemType = "Season", indexNumber = n)

    private fun episodeCard(season: Int, played: Boolean = false) =
        testCard(id = "s$season-ep", itemType = "Episode", indexNumber = 1, parentIndexNumber = season, played = played, seriesId = "series-1")

    /** The core's NextUp pick for [episodeCard] of [season]. */
    private fun nextUp(season: Int) = testSeriesPlay(episodeCard(season), seasonId = "season-$season", kind = SeriesPlayKind.NEXT_UP)

    private fun mirrorGateway(seasons: List<Card>, episodes: List<Card>, play: SeriesPlayTarget? = null): MirrorGateway {
        val gateway = MirrorGateway(FakeCoreGateway(), MutableSharedFlow(extraBufferCapacity = 8))
        gateway.play = play
        gateway.children["series-1"] = seasons
        seasons.forEach { season -> gateway.children[season.id] = episodes.filter { it.parentIndexNumber == season.indexNumber } }
        gateway.all = episodes
        return gateway
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun TestScope.record(viewModel: DetailViewModel): MutableList<DetailUiState> {
        val states = mutableListOf<DetailUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect { states += it } }
        return states
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `a refresh that drops the shown season during a pending switch clears its episodes with its chip`() = runTest {
        val series = testCard(id = "series-1", itemType = "Series")
        val season1 = testCard(id = "season-1", itemType = "Season", indexNumber = 1)
        val season2 = testCard(id = "season-2", itemType = "Season", indexNumber = 2)
        val fake = FakeCoreGateway(
            childrenByParent = mapOf(
                "season-1" to listOf(testCard(id = "s1-ep", itemType = "Episode", indexNumber = 1, parentIndexNumber = 1)),
                "season-2" to listOf(testCard(id = "s2-ep", itemType = "Episode", indexNumber = 1, parentIndexNumber = 2)),
            ),
        )
        var seasons = listOf(season1, season2)
        val gate = CompletableDeferred<Unit>()
        val changes = MutableSharedFlow<ChangeEvent>(extraBufferCapacity = 4)
        val gateway = object : CoreGateway by fake {
            override fun changeEvents(): Flow<ChangeEvent> = changes
            override suspend fun children(parentId: String, sort: SortOrder, offset: UInt, limit: UInt): List<Card> =
                if (parentId == "series-1") seasons else fake.children(parentId, sort, offset, limit)
            override suspend fun seasonEpisodes(seasonId: String): List<Card> =
                fake.seasonEpisodes(seasonId).also { if (seasonId == "season-2") gate.await() }
        }

        withDetailViewModel(detailViewModel(gateway, series)) { viewModel ->
            assertEquals("season-1", viewModel.state.value.selectedSeasonId)
            val states = record(viewModel)

            viewModel.selectSeason("season-2")
            runCurrent()
            seasons = listOf(season2) // the server dropped the shown season
            changes.tryEmit(ChangeEvent.Upserted(ids = listOf("s1-ep"), libraryId = null))
            runCurrent()

            assertTrue(
                "no state shows season 1's episodes without its chip",
                states.none { state -> state.seasonEpisodes.any { it.id == "s1-ep" } && state.seasons.none { it.id == "season-1" } },
            )

            gate.complete(Unit)
            runCurrent()
            assertEquals("season-2", viewModel.state.value.selectedSeasonId)
            assertEquals(listOf("s2-ep"), viewModel.state.value.episodes.map { it.id })
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `an older episodes read landing in either order never settles empty or picks the wrong season`() = runTest {
        for (order in listOf(listOf(0, 1), listOf(1, 0))) {
            val seasons = listOf(seasonCard(1), seasonCard(2), seasonCard(3))
            val gateway = mirrorGateway(seasons, listOf(episodeCard(1, true), episodeCard(2, true), episodeCard(3)), nextUp(3))
            val gates = mutableListOf<CompletableDeferred<Unit>>()
            gateway.summaryGate = { CompletableDeferred<Unit>().also { gates += it }.await() }

            withDetailViewModel(detailViewModel(gateway, testCard(id = "series-1", itemType = "Series"))) { viewModel ->
                val states = record(viewModel)
                runCurrent()
                gateway.bus.tryEmit(ChangeEvent.Refresh)
                runCurrent()
                assertEquals(2, gates.size)

                order.forEach { gates[it].complete(Unit); runCurrent() }

                assertEquals("season-3", viewModel.state.value.selectedSeasonId)
                assertTrue(states.none { it.summarySettled && it.seasonStats.isEmpty() })
            }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `two change events never run overlapping series refreshes`() = runTest {
        val v1 = listOf(seasonCard(1), seasonCard(2))
        val v2 = v1 + seasonCard(3)
        val gateway = mirrorGateway(v1, listOf(episodeCard(1), episodeCard(2)))
        val gates = mutableListOf<CompletableDeferred<Unit>>()
        var gating = false
        gateway.childrenGate = { id -> if (gating && id == "series-1") CompletableDeferred<Unit>().also { gates += it }.await() }
        withDetailViewModel(detailViewModel(gateway, testCard(id = "series-1", itemType = "Series"))) { viewModel ->
            runCurrent()
            gating = true
            gateway.bus.tryEmit(ChangeEvent.Refresh)
            runCurrent()
            gateway.children["series-1"] = v2
            gateway.bus.tryEmit(ChangeEvent.Upserted(ids = listOf("series-1"), libraryId = null))
            runCurrent()
            repeat(8) {
                gates.reversed().forEach { it.complete(Unit) }
                advanceTimeBy(600)
                runCurrent()
            }

            assertEquals(1, gateway.maxInFlightSeries)
            assertEquals(listOf("season-1", "season-2", "season-3"), viewModel.state.value.seasons.map { it.id })
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `an empty seasons read never clears the viewer's pick`() = runTest {
        val seasons = listOf(seasonCard(1), seasonCard(2), seasonCard(3))
        val gateway = mirrorGateway(seasons, listOf(episodeCard(1), episodeCard(2), episodeCard(3)))

        withDetailViewModel(detailViewModel(gateway, testCard(id = "series-1", itemType = "Series"))) { viewModel ->
            runCurrent()
            viewModel.selectSeason("season-3")
            val states = record(viewModel)

            gateway.children["series-1"] = emptyList()
            gateway.bus.tryEmit(ChangeEvent.Refresh)
            runCurrent()
            gateway.children["series-1"] = seasons
            advanceTimeBy(600)
            gateway.bus.tryEmit(ChangeEvent.Refresh)
            runCurrent()

            assertTrue(states.all { it.selectedSeasonId == "season-3" })
            assertEquals("season-3", viewModel.state.value.selectedSeasonId)
            assertEquals(seasons.map { it.id }, viewModel.state.value.seasons.map { it.id })
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `no state shows seasons without the selected one while a removal refresh is in flight`() = runTest {
        val seasons = listOf(seasonCard(1), seasonCard(2))
        val gateway = mirrorGateway(seasons, listOf(episodeCard(1), episodeCard(2)))

        withDetailViewModel(detailViewModel(gateway, testCard(id = "series-1", itemType = "Series"))) { viewModel ->
            runCurrent()
            viewModel.selectSeason("season-2")
            val states = record(viewModel)
            val gate = CompletableDeferred<Unit>()
            gateway.summaryGate = { gate.await() }

            gateway.children["series-1"] = listOf(seasons[0])
            gateway.all = listOf(episodeCard(1))
            gateway.bus.tryEmit(ChangeEvent.Removed(ids = listOf("season-2"), libraryId = null))
            runCurrent()
            gate.complete(Unit)
            runCurrent()

            val torn = states.filter { st -> st.seasons.isNotEmpty() && st.selectedSeasonId != null && st.seasons.none { it.id == st.selectedSeasonId } }
            assertTrue("torn states: ${torn.size}", torn.isEmpty())
            assertEquals("season-1", viewModel.state.value.selectedSeasonId)
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `an older season reply never lands over a newer read of the same season`() = runTest {
        val seasons = listOf(seasonCard(1), seasonCard(2))
        val s1 = episodeCard(1)
        val gateway = mirrorGateway(seasons, listOf(s1, episodeCard(2)))
        val staleS1 = testCard(id = "stale-s1", itemType = "Episode", parentIndexNumber = 1)
        val summaryGate = CompletableDeferred<Unit>()
        val s1Gate = CompletableDeferred<Unit>()
        gateway.summaryGate = { summaryGate.await() }
        gateway.childrenGate = { id -> if (id == "season-1") s1Gate.await() }
        gateway.children["season-1"] = listOf(staleS1)

        withDetailViewModel(detailViewModel(gateway, testCard(id = "series-1", itemType = "Series"))) { viewModel ->
            runCurrent()
            viewModel.selectSeason("season-1") // the older read, gated with a stale snapshot
            viewModel.selectSeason("season-2")
            summaryGate.complete(Unit)
            runCurrent()
            gateway.children["season-1"] = listOf(s1)
            viewModel.selectSeason("season-1") // the newer read

            s1Gate.complete(Unit)
            runCurrent()

            assertEquals(listOf("s1-ep"), viewModel.state.value.episodes.map { it.id })
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `the menu scope drops to the series once the viewer's picked season is removed`() = runTest {
        val seasons = listOf(seasonCard(1), seasonCard(2))
        val gateway = mirrorGateway(seasons, listOf(episodeCard(1), episodeCard(2)))

        withDetailViewModel(detailViewModel(gateway, testCard(id = "series-1", itemType = "Series"))) { viewModel ->
            runCurrent()
            viewModel.selectSeason("season-2")
            viewModel.openMenu()
            assertEquals(2, viewModel.state.value.menu?.scopeSeasonNumber)
            viewModel.closeMenu()

            gateway.children["series-1"] = listOf(seasons[0])
            gateway.all = listOf(episodeCard(1))
            gateway.bus.tryEmit(ChangeEvent.Removed(ids = listOf("season-2"), libraryId = null))
            runCurrent()
            viewModel.openMenu()

            assertEquals("season-1", viewModel.state.value.selectedSeasonId)
            assertNull(viewModel.state.value.menu?.scopeSeasonNumber)
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `a failed or late refresh read never settles empty while a good read is pending`() = runTest {
        for (initPending in listOf(true, false)) for (refreshThrows in listOf(false, true)) for (order in listOf(listOf(0, 1), listOf(1, 0))) {
            if (!initPending && order != listOf(0, 1)) continue
            val seasons = listOf(seasonCard(1), seasonCard(2), seasonCard(3))
            val gateway = mirrorGateway(seasons, listOf(episodeCard(1, true), episodeCard(2, true), episodeCard(3)), nextUp(3))
            val gates = mutableListOf<CompletableDeferred<Unit>>()
            gateway.summaryGate = { CompletableDeferred<Unit>().also { gates += it }.await() }

            withDetailViewModel(detailViewModel(gateway, testCard(id = "series-1", itemType = "Series"))) { viewModel ->
                val states = record(viewModel)
                runCurrent()
                if (!initPending) {
                    gates[0].complete(Unit)
                    runCurrent()
                }
                gateway.bus.tryEmit(ChangeEvent.Refresh)
                runCurrent()
                val refreshGate = gates.last()
                val initGate = gates.first()
                fun release(which: Int) {
                    val gate = if (which == 0) initGate else refreshGate
                    if (gate.isCompleted) return
                    if (which == 1 && refreshThrows) gate.completeExceptionally(IllegalStateException("boom")) else gate.complete(Unit)
                    runCurrent()
                }
                order.forEach(::release)
                release(0)
                release(1)

                val label = "initPending=$initPending refreshThrows=$refreshThrows order=$order"
                assertTrue(label, states.none { it.summarySettled && it.seasonStats.isEmpty() })
                assertEquals(label, "season-3", viewModel.state.value.selectedSeasonId)
                assertEquals(label, 3, viewModel.state.value.seasonStats.size)
            }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `an older seasons read never lands over a newer one, and a failed initial read still settles the selection`() = runTest {
        for (initThrows in listOf(false, true)) {
            val older = listOf(seasonCard(1))
            val newer = listOf(seasonCard(1), seasonCard(2), seasonCard(3))
            val gateway = mirrorGateway(older, listOf(episodeCard(1, true), episodeCard(2, true), episodeCard(3)), nextUp(3))
            val gates = mutableListOf<CompletableDeferred<Unit>>()
            gateway.childrenGate = { id -> if (id == "series-1") CompletableDeferred<Unit>().also { gates += it }.await() }

            withDetailViewModel(detailViewModel(gateway, testCard(id = "series-1", itemType = "Series"))) { viewModel ->
                runCurrent()
                gateway.children["series-1"] = newer
                gateway.bus.tryEmit(ChangeEvent.Refresh)
                runCurrent()
                assertEquals(2, gates.size)

                gates[1].complete(Unit) // the refresh lands first
                runCurrent()
                if (initThrows) gates[0].completeExceptionally(IllegalStateException("boom")) else gates[0].complete(Unit)
                runCurrent()

                val label = "initThrows=$initThrows"
                assertEquals(label, newer.map { it.id }, viewModel.state.value.seasons.map { it.id })
                assertEquals(label, "season-3", viewModel.state.value.selectedSeasonId)
                assertTrue(label, viewModel.state.value.seasonsSettled)
            }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `an empty seasons read leaves the shelf alone and the selection and shelf agree when seasons return`() = runTest {
        val seasons = listOf(seasonCard(1), seasonCard(2))
        val gateway = mirrorGateway(seasons, listOf(episodeCard(1), episodeCard(2)))

        withDetailViewModel(detailViewModel(gateway, testCard(id = "series-1", itemType = "Series"))) { viewModel ->
            runCurrent()
            viewModel.selectSeason("season-2")
            runCurrent()
            assertEquals(listOf("s2-ep"), viewModel.state.value.episodes.map { it.id })

            gateway.children["series-1"] = emptyList()
            gateway.children["season-2"] = emptyList() // unknown parent reads empty
            gateway.all = emptyList()
            gateway.bus.tryEmit(ChangeEvent.Refresh)
            runCurrent()
            assertEquals(listOf("s2-ep"), viewModel.state.value.episodes.map { it.id })

            gateway.children["series-1"] = seasons
            gateway.children["season-2"] = listOf(episodeCard(2))
            gateway.all = listOf(episodeCard(1), episodeCard(2))
            advanceTimeBy(600)
            gateway.bus.tryEmit(ChangeEvent.Refresh)
            runCurrent()

            assertEquals("season-2", viewModel.state.value.selectedSeasonId)
            assertEquals(listOf("s2-ep"), viewModel.state.value.episodes.map { it.id })
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `the menu scope stays on the series when removals bring the selection back to the old pick`() = runTest {
        val seasons = listOf(seasonCard(1), seasonCard(2))
        val gateway = mirrorGateway(seasons, listOf(episodeCard(1), episodeCard(2)))

        withDetailViewModel(detailViewModel(gateway, testCard(id = "series-1", itemType = "Series"))) { viewModel ->
            runCurrent()
            viewModel.selectSeason("season-2")

            gateway.children["series-1"] = listOf(seasons[0])
            gateway.all = listOf(episodeCard(1))
            gateway.bus.tryEmit(ChangeEvent.Removed(ids = listOf("season-2"), libraryId = null))
            runCurrent()
            assertEquals("season-1", viewModel.state.value.selectedSeasonId)

            gateway.children["series-1"] = listOf(seasons[1])
            gateway.all = listOf(episodeCard(2))
            advanceTimeBy(600)
            gateway.bus.tryEmit(ChangeEvent.Removed(ids = listOf("season-1"), libraryId = null))
            runCurrent()
            assertEquals("season-2", viewModel.state.value.selectedSeasonId)

            viewModel.openMenu()
            assertNull(viewModel.state.value.menu?.scopeSeasonNumber)
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `a pick removed before the per-episode answer resolves to the resume season, not the first`() = runTest {
        val seasons = listOf(seasonCard(1), seasonCard(2), seasonCard(3))
        val gateway = mirrorGateway(seasons, listOf(episodeCard(1, true), episodeCard(2), episodeCard(3)), nextUp(2))
        val gate = CompletableDeferred<Unit>()
        gateway.summaryGate = { gate.await() }

        withDetailViewModel(detailViewModel(gateway, testCard(id = "series-1", itemType = "Series"))) { viewModel ->
            runCurrent()
            viewModel.selectSeason("season-3")
            gateway.children["series-1"] = seasons.take(2)
            gateway.bus.tryEmit(ChangeEvent.Removed(ids = listOf("season-3"), libraryId = null))
            runCurrent()
            assertTrue("no provisional fallback", viewModel.state.value.selectedSeasonId != "season-1")

            gate.complete(Unit)
            runCurrent()

            assertEquals("season-2", viewModel.state.value.selectedSeasonId)
        }
    }

    // -- summary read ordering + virtual-flag refresh ---------------------

    @Test
    fun `an older summary read finishing late is dropped whole, the newer one having settled`() = runTest {
        val series = testCard(id = "series-1", itemType = "Series")
        val season1 = testCard(id = "season-1", itemType = "Season", indexNumber = 1)
        val stale = SeriesSummary(null, listOf(testStats("season-1", have = 9)))
        val fresh = SeriesSummary(null, listOf(testStats("season-1", have = 1)))
        val fake = FakeCoreGateway(childrenByParent = mapOf("series-1" to listOf(season1)))
        val changes = MutableSharedFlow<ChangeEvent>(extraBufferCapacity = 4)
        val firstReadGate = CompletableDeferred<SeriesSummary>()
        var reads = 0
        val gateway = object : CoreGateway by fake {
            override fun changeEvents(): Flow<ChangeEvent> = changes

            // The init read is held; the quiet refresh's read answers at once.
            override suspend fun seriesSummary(seriesId: String, replay: Boolean): SeriesSummary =
                if (++reads == 1) firstReadGate.await() else fresh
        }

        withDetailViewModel(detailViewModel(gateway, series)) { viewModel ->
            assertFalse(viewModel.state.value.summarySettled)

            changes.tryEmit(ChangeEvent.Refresh)
            runCurrent()
            assertEquals(fresh.seasons, viewModel.state.value.seasonStats)
            assertTrue(viewModel.state.value.summarySettled)

            firstReadGate.complete(stale)
            runCurrent()

            assertEquals(fresh.seasons, viewModel.state.value.seasonStats)
            assertTrue(viewModel.state.value.summarySettled)
        }
    }

    /** Season 2 holds one real and one missing (virtual) episode; [shown] seeds the settings record. */
    private fun missingEpisodesFake(shown: Boolean) = FakeCoreGateway(
        childrenByParent = mapOf(
            "series-1" to listOf(
                testCard(id = "season-1", itemType = "Season", indexNumber = 1),
                testCard(id = "season-2", itemType = "Season", indexNumber = 2),
            ),
            "season-1" to listOf(testCard(id = "s1-ep", itemType = "Episode", indexNumber = 1, parentIndexNumber = 1)),
            "season-2" to listOf(
                testCard(id = "s2-real", itemType = "Episode", indexNumber = 1, parentIndexNumber = 2),
                testCard(id = "s2-virtual", itemType = "Episode", indexNumber = 2, parentIndexNumber = 2, isVirtual = true),
            ),
        ),
        settings = defaultTestSettings().copy(showVirtualEpisodes = shown),
    )

    @Test
    fun `turning Show missing episodes off while the page sits on the stack drops the missing episode without a chip tap`() = runTest {
        val fake = missingEpisodesFake(shown = true)
        val store = SettingsStore(fake, backgroundScope)

        withDetailViewModel(detailViewModel(fake, testCard(id = "series-1", itemType = "Series"), settingsStore = store)) { viewModel ->
            viewModel.selectSeason("season-2")
            runCurrent()
            assertEquals(listOf("s2-real", "s2-virtual"), viewModel.state.value.episodes.map { it.id })
            val settingsReads = fake.getSettingsCallCount
            val reads = fake.childrenCalls.size
            viewModel.setActive(false)

            store.update { it.copy(showVirtualEpisodes = false) }
            runCurrent()

            assertEquals(false, viewModel.state.value.showVirtualEpisodes)
            assertEquals(listOf("s2-real"), viewModel.state.value.episodes.map { it.id })
            assertEquals("season-2", viewModel.state.value.selectedSeasonId)
            assertEquals(settingsReads, fake.getSettingsCallCount)
            assertEquals("the flip re-filters in place, no read racing the settings write", reads, fake.childrenCalls.size)
        }
    }

    @Test
    fun `turning Show missing episodes on while the page sits on the stack adds the missing episode without a chip tap`() = runTest {
        val fake = missingEpisodesFake(shown = false)
        val store = SettingsStore(fake, backgroundScope)

        withDetailViewModel(detailViewModel(fake, testCard(id = "series-1", itemType = "Series"), settingsStore = store)) { viewModel ->
            viewModel.selectSeason("season-2")
            runCurrent()
            assertEquals(listOf("s2-real"), viewModel.state.value.episodes.map { it.id })
            viewModel.setActive(false)

            store.update { it.copy(showVirtualEpisodes = true) }
            runCurrent()

            assertEquals(true, viewModel.state.value.showVirtualEpisodes)
            assertEquals(listOf("s2-real", "s2-virtual"), viewModel.state.value.episodes.map { it.id })
            assertEquals("season-2", viewModel.state.value.selectedSeasonId)
        }
    }

    /** Like the core, the series' season list drops an all-missing season while the persisted flag hides missing episodes. */
    private class VirtualSeasonGateway(private val fake: FakeCoreGateway) : CoreGateway by fake {
        override suspend fun children(parentId: String, sort: SortOrder, offset: UInt, limit: UInt): List<Card> {
            val listed = fake.children(parentId, sort, offset, limit)
            if (parentId != "series-1" || !fake.settings.showVirtualEpisodes) return listed
            return listed + testCard(id = "season-3", itemType = "Season", indexNumber = 3)
        }
    }

    @Test
    fun `turning Show missing episodes on while the page is hidden re-reads the season list once the page is active again`() = runTest {
        val fake = missingEpisodesFake(shown = false)
        val gateway = VirtualSeasonGateway(fake)
        val store = SettingsStore(gateway, backgroundScope)

        withDetailViewModel(detailViewModel(gateway, testCard(id = "series-1", itemType = "Series"), settingsStore = store)) { viewModel ->
            assertEquals(listOf("season-1", "season-2"), viewModel.state.value.seasons.map { it.id })
            viewModel.setActive(false)

            store.update { it.copy(showVirtualEpisodes = true) }
            runCurrent()
            assertEquals(listOf("season-1", "season-2"), viewModel.state.value.seasons.map { it.id })

            viewModel.setActive(true)
            runCurrent()

            assertEquals(listOf("season-1", "season-2", "season-3"), viewModel.state.value.seasons.map { it.id })
        }
    }

    @Test
    fun `the first settings value does not refresh the page`() = runTest {
        val fake = missingEpisodesFake(shown = true)

        withDetailViewModel(detailViewModel(fake, testCard(id = "series-1", itemType = "Series"))) { viewModel ->
            runCurrent()

            assertEquals(true, viewModel.state.value.showVirtualEpisodes)
            assertEquals(1, fake.childrenCalls.count { it.parentId == "series-1" })
        }
    }

    // -- docs/07 §5: browse read outcomes on a series page ----------------------------------

    /** A series gateway whose season and summary reads fail ([BrowseFailed]) while [failing]. */
    private class FlakySeriesGateway(private val fake: FakeCoreGateway, var failing: Boolean) : CoreGateway by fake {
        override suspend fun children(parentId: String, sort: SortOrder, offset: UInt, limit: UInt): List<Card> {
            if (failing && parentId == "series-1") throw BrowseFailed()
            return fake.children(parentId, sort, offset, limit)
        }

        override suspend fun seriesSummary(seriesId: String, replay: Boolean): SeriesSummary {
            if (failing) throw BrowseFailed()
            return fake.seriesSummary(seriesId, replay)
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `a failed series read shows the list error and a Retry on a good read clears it`() = runTest {
        val series = testCard(id = "series-1", itemType = "Series")
        val season1 = testCard(id = "season-1", itemType = "Season", indexNumber = 1, name = "Season 1")
        val episode = testCard(id = "ep-1", itemType = "Episode", indexNumber = 1, parentIndexNumber = 1)
        val fake = FakeCoreGateway(
            childrenByParent = mapOf("series-1" to listOf(season1), "season-1" to listOf(episode)),
        )
        val gateway = FlakySeriesGateway(fake, failing = true)

        withDetailViewModel(detailViewModel(gateway, series)) { viewModel ->
            runCurrent()
            val failed = viewModel.state.value
            assertTrue("a failed read is not an empty series", failed.listError)
            assertTrue("a failed read still settles the page", failed.seasonsSettled)
            assertTrue(failed.seasons.isEmpty())

            gateway.failing = false
            viewModel.retryLists()
            runCurrent()

            val retried = viewModel.state.value
            assertFalse(retried.listError)
            assertEquals(listOf("season-1"), retried.seasons.map { it.id })
            assertEquals("season-1", retried.selectedSeasonId)
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `a mirror that is not open yet keeps the seasons unsettled until the read succeeds`() = runTest {
        val series = testCard(id = "series-1", itemType = "Series")
        val season1 = testCard(id = "season-1", itemType = "Season", indexNumber = 1, name = "Season 1")
        val fake = FakeCoreGateway(
            childrenByParent = mapOf("series-1" to listOf(season1), "season-1" to emptyList()),
        )
        var notReady = true
        val gateway = object : CoreGateway by fake {
            override suspend fun children(parentId: String, sort: SortOrder, offset: UInt, limit: UInt): List<Card> {
                if (notReady) throw BrowseNotReady()
                return fake.children(parentId, sort, offset, limit)
            }
        }

        withDetailViewModel(detailViewModel(gateway, series)) { viewModel ->
            runCurrent()
            assertFalse("not ready is neither empty nor failed", viewModel.state.value.seasonsSettled)
            assertFalse(viewModel.state.value.listError)

            advanceTimeBy(5_000)
            runCurrent()
            assertFalse("still waiting while the mirror stays closed", viewModel.state.value.seasonsSettled)

            notReady = false
            advanceTimeBy(1_000)
            runCurrent()
            val settled = viewModel.state.value
            assertTrue(settled.seasonsSettled)
            assertFalse(settled.listError)
            assertEquals(listOf("season-1"), settled.seasons.map { it.id })
        }
    }

    // -- docs/07 §5: the list error follows the newest read of each list ---------------------

    /** Every browse read parks on its own deferred, so a test completes them in any order. */
    private class ScriptedBrowseGateway(private val fake: FakeCoreGateway) : CoreGateway by fake {
        val seasonReads = mutableListOf<CompletableDeferred<List<Card>>>()
        val summaryReads = mutableListOf<CompletableDeferred<SeriesSummary>>()
        val episodeReads = mutableListOf<CompletableDeferred<List<Card>>>()

        override suspend fun children(parentId: String, sort: SortOrder, offset: UInt, limit: UInt): List<Card> {
            val read = CompletableDeferred<List<Card>>()
            (if (parentId == "series-1") seasonReads else episodeReads) += read
            return read.await()
        }

        override suspend fun seasonEpisodes(seasonId: String): List<Card> = children(seasonId, SortOrder.INDEX_NUMBER, 0u, UInt.MAX_VALUE)

        override suspend fun seriesSummary(seriesId: String, replay: Boolean): SeriesSummary {
            val read = CompletableDeferred<SeriesSummary>()
            summaryReads += read
            return read.await()
        }
    }

    private val listSeries = testCard(id = "series-1", itemType = "Series")
    private val listSeasons = listOf(
        testCard(id = "season-1", itemType = "Season", indexNumber = 1),
        testCard(id = "season-2", itemType = "Season", indexNumber = 2),
    )
    private val listEpisode = testCard(id = "ep-1", itemType = "Episode", indexNumber = 1, parentIndexNumber = 1)
    private val emptySummary = SeriesSummary(null, emptyList())
    private val listSummary = SeriesSummary(null, listOf(testStats("season-1", have = 1)))

    private fun <T> CompletableDeferred<T>.fail() = completeExceptionally(BrowseFailed())

    private inline fun TestScope.withScriptedSeries(block: (ScriptedBrowseGateway, DetailViewModel) -> Unit) {
        val gateway = ScriptedBrowseGateway(FakeCoreGateway())
        withDetailViewModel(detailViewModel(gateway, listSeries)) { block(gateway, it) }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `the seasons read decides the list error, a stale failure after a newer success does not`() = runTest {
        for ((name, outcome) in listOf("ok" to false, "failed" to true)) {
            withScriptedSeries { gateway, viewModel ->
                runCurrent()
                gateway.summaryReads[0].complete(emptySummary)
                if (outcome) gateway.seasonReads[0].fail() else gateway.seasonReads[0].complete(listSeasons)
                runCurrent()
                assertEquals("seasons $name", outcome, viewModel.state.value.listError)
            }
        }
        withScriptedSeries { gateway, viewModel ->
            runCurrent()
            gateway.summaryReads[0].complete(emptySummary)
            viewModel.retryLists()
            runCurrent()
            gateway.seasonReads[1].complete(listSeasons)
            gateway.summaryReads[1].complete(emptySummary)
            runCurrent()
            gateway.seasonReads[0].fail()
            runCurrent()
            assertFalse("seasons stale failure", viewModel.state.value.listError)
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `the summary read decides the list error, a stale failure after a newer success does not`() = runTest {
        for ((name, outcome) in listOf("ok" to false, "failed" to true)) {
            withScriptedSeries { gateway, viewModel ->
                runCurrent()
                gateway.seasonReads[0].complete(listSeasons)
                if (outcome) gateway.summaryReads[0].fail() else gateway.summaryReads[0].complete(listSummary)
                runCurrent()
                assertEquals("summary $name", outcome, viewModel.state.value.listError)
            }
        }
        withScriptedSeries { gateway, viewModel ->
            runCurrent()
            gateway.seasonReads[0].complete(listSeasons)
            viewModel.retryLists()
            runCurrent()
            gateway.seasonReads[1].complete(listSeasons)
            gateway.summaryReads[1].complete(listSummary)
            runCurrent()
            gateway.summaryReads[0].fail()
            runCurrent()
            assertFalse("summary stale failure", viewModel.state.value.listError)
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `a network-served season read decides the list error, a stale one does not`() = runTest {
        for ((name, outcome) in listOf("ok" to false, "failed" to true)) {
            withScriptedSeries { gateway, viewModel ->
                runCurrent()
                gateway.seasonReads[0].complete(listSeasons) // the summary stays parked; the viewer's tap picks the season
                runCurrent()
                viewModel.selectSeason("season-1")
                runCurrent()
                if (outcome) gateway.episodeReads.last().fail() else gateway.episodeReads.last().complete(listOf(listEpisode))
                runCurrent()
                assertEquals("episodes $name", outcome, viewModel.state.value.listError)
            }
        }
        withScriptedSeries { gateway, viewModel ->
            runCurrent()
            gateway.seasonReads[0].complete(listSeasons)
            runCurrent()
            viewModel.selectSeason("season-1")
            runCurrent()
            val older = gateway.episodeReads.last()
            viewModel.selectSeason("season-2")
            runCurrent()
            gateway.episodeReads.last().complete(listOf(listEpisode))
            older.fail()
            runCurrent()
            assertFalse("episodes stale failure", viewModel.state.value.listError)
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `a mirror that never opens settles the page as failed within the wait budget`() = runTest {
        val series = testCard(id = "series-1", itemType = "Series")
        val fake = FakeCoreGateway()
        val gateway = object : CoreGateway by fake {
            override suspend fun children(parentId: String, sort: SortOrder, offset: UInt, limit: UInt): List<Card> =
                throw BrowseNotReady()

            override suspend fun seriesSummary(seriesId: String, replay: Boolean): SeriesSummary = throw BrowseNotReady()
        }

        withDetailViewModel(detailViewModel(gateway, series)) { viewModel ->
            runCurrent()
            advanceTimeBy(9_000)
            runCurrent()
            assertFalse("still waiting inside the budget", viewModel.state.value.seasonsSettled)

            advanceTimeBy(2_000)
            runCurrent()

            assertTrue("the wait is bounded", viewModel.state.value.seasonsSettled)
            assertTrue("a read that never became ready reads as failed", viewModel.state.value.listError)
        }
    }
}

/** Snapshot-at-call-time gateway with per-call hooks, so a test can hold a read open while the mirror moves on. */
private class MirrorGateway(
    private val fake: FakeCoreGateway,
    val bus: MutableSharedFlow<ChangeEvent>,
) : CoreGateway by fake {
    val children = mutableMapOf<String, List<Card>>()
    /** The episodes [seriesSummary] counts. */
    var all: List<Card> = emptyList()
    var childrenGate: (suspend (String) -> Unit)? = null
    /** Held before [seriesSummary] answers with the snapshot taken when it was called. */
    var summaryGate: (suspend () -> Unit)? = null
    var play: SeriesPlayTarget? = null
    private var inFlightSeries = 0
    var maxInFlightSeries = 0
        private set

    override fun changeEvents(): Flow<ChangeEvent> = bus

    override suspend fun children(parentId: String, sort: SortOrder, offset: UInt, limit: UInt): List<Card> {
        val snapshot = children[parentId].orEmpty()
        if (parentId == "series-1") maxInFlightSeries = maxOf(maxInFlightSeries, ++inFlightSeries)
        try {
            childrenGate?.invoke(parentId)
        } finally {
            if (parentId == "series-1") inFlightSeries--
        }
        return snapshot
    }

    override suspend fun seasonEpisodes(seasonId: String): List<Card> = children(seasonId, SortOrder.INDEX_NUMBER, 0u, UInt.MAX_VALUE)

    override suspend fun seriesSummary(seriesId: String, replay: Boolean): SeriesSummary {
        val snapshot = SeriesSummary(play, seasonStatsOf(children[seriesId].orEmpty(), all))
        summaryGate?.invoke()
        return snapshot
    }

    /** Fixture counts for [all] (no virtual episodes here); the real rules are pinned in next_episode.rs. */
    private fun seasonStatsOf(seasons: List<Card>, episodes: List<Card>): List<SeasonStats> = seasons.map { season ->
        val of = episodes.filter { it.parentIndexNumber == season.indexNumber }
        testStats(season.id, have = of.size, watched = of.count { it.played }, anyProgress = of.any { it.played || it.positionTicks > 0 })
    }
}
