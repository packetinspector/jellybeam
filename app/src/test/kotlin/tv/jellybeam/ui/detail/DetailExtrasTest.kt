package tv.jellybeam.ui.detail

import androidx.lifecycle.ViewModelStore
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import tv.jellybeam.MainDispatcherRule
import tv.jellybeam.data.CoreGateway
import tv.jellybeam.data.FakeCoreGateway
import tv.jellybeam.data.SettingsStore
import tv.jellybeam.i18n.ResourceUiStrings
import tv.jellybeam.ui.cards.testCard
import uniffi.jellybeam_core.Card
import uniffi.jellybeam_core.ExtraItem
import uniffi.jellybeam_core.ItemDetail
import uniffi.jellybeam_core.RemoteTrailer

private val strings = ResourceUiStrings.default

private const val YOUTUBE_URL = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
private const val OTHER_URL = "https://trailers.example.test/v/1"

/** An [ItemDetail] that only says how many extras it counted and which links it carries. */
private fun extrasDetail(
    id: String = "movie-1",
    localTrailerCount: Int = 0,
    specialFeatureCount: Int = 0,
    remoteTrailers: List<RemoteTrailer> = emptyList(),
): ItemDetail = ItemDetail(
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
    localTrailerCount = localTrailerCount,
    specialFeatureCount = specialFeatureCount,
    remoteTrailers = remoteTrailers,
)

private fun extra(id: String, name: String = id, type: String? = "Trailer") = ExtraItem(id = id, name = name, extraType = type, runTimeTicks = null)

private fun link(url: String, name: String? = null) = RemoteTrailer(name = name, url = url)

private val openAll: (String) -> Boolean = { true }
private val openNone: (String) -> Boolean = { false }

/** docs/19 §1.3: the pure rules behind the Trailer pill and the Trailers and extras level. */
class DetailExtrasModelTest {

    // -- pillTrailer ----------------------------------------------------------

    @Test
    fun `a movie with a server trailer pills the local one even beside links`() {
        val detail = extrasDetail(localTrailerCount = 1, remoteTrailers = listOf(link(YOUTUBE_URL)))
        assertEquals(PillTrailer.Local, pillTrailer("Movie", detail, openAll))
    }

    @Test
    fun `a movie without one pills the first link an app can open`() {
        val detail = extrasDetail(remoteTrailers = listOf(link(OTHER_URL), link(YOUTUBE_URL)))
        assertEquals(PillTrailer.Remote(YOUTUBE_URL), pillTrailer("Movie", detail) { it == YOUTUBE_URL })
    }

    @Test
    fun `a movie whose links nothing can open has no pill`() {
        assertNull(pillTrailer("Movie", extrasDetail(remoteTrailers = listOf(link(YOUTUBE_URL))), openNone))
    }

    @Test
    fun `no detail and no trailers both mean no pill`() {
        assertNull(pillTrailer("Movie", null, openAll))
        assertNull(pillTrailer("Movie", extrasDetail(), openAll))
    }

    @Test
    fun `only a movie gets the pill`() {
        val detail = extrasDetail(localTrailerCount = 1, remoteTrailers = listOf(link(YOUTUBE_URL)))
        listOf("Series", "Episode", "BoxSet").forEach { assertNull(it, pillTrailer(it, detail, openAll)) }
    }

    // -- extrasAvailable --------------------------------------------------------

    @Test
    fun `a movie shows the row for a second trailer or any feature, never for the pill's trailer alone`() {
        assertFalse(extrasAvailable("Movie", extrasDetail(), openAll))
        assertFalse(extrasAvailable("Movie", extrasDetail(localTrailerCount = 1), openAll))
        assertFalse(extrasAvailable("Movie", extrasDetail(remoteTrailers = listOf(link(YOUTUBE_URL))), openAll))
        assertTrue(extrasAvailable("Movie", extrasDetail(localTrailerCount = 2), openAll))
        assertTrue(extrasAvailable("Movie", extrasDetail(localTrailerCount = 1, remoteTrailers = listOf(link(YOUTUBE_URL))), openAll))
        assertTrue(extrasAvailable("Movie", extrasDetail(specialFeatureCount = 1), openAll))
    }

    @Test
    fun `an unopenable link counts for nothing`() {
        val twoLinks = extrasDetail(remoteTrailers = listOf(link(OTHER_URL), link(YOUTUBE_URL)))
        assertTrue(extrasAvailable("Movie", twoLinks, openAll))
        assertFalse(extrasAvailable("Movie", twoLinks, openNone))
        assertFalse(extrasAvailable("Series", twoLinks, openNone))
    }

    @Test
    fun `a series or episode shows the row for any single trailer or feature`() {
        listOf("Series", "Episode").forEach { type ->
            assertFalse(type, extrasAvailable(type, extrasDetail(), openAll))
            assertTrue(type, extrasAvailable(type, extrasDetail(localTrailerCount = 1), openAll))
            assertTrue(type, extrasAvailable(type, extrasDetail(remoteTrailers = listOf(link(YOUTUBE_URL))), openAll))
            assertTrue(type, extrasAvailable(type, extrasDetail(specialFeatureCount = 1), openAll))
        }
    }

    @Test
    fun `a collection never shows the row and an unloaded detail neither`() {
        assertFalse(extrasAvailable("BoxSet", extrasDetail(localTrailerCount = 3, specialFeatureCount = 3), openAll))
        assertFalse(extrasAvailable("Movie", null, openAll))
    }

    // -- buildExtrasList --------------------------------------------------------

    @Test
    fun `trailers come first, server ones before links, then features by type and name`() {
        val list = buildExtrasList(
            itemType = "Series",
            localTrailers = listOf(extra("t1"), extra("t2")),
            remote = listOf(link(YOUTUBE_URL, "Teaser")),
            features = listOf(
                extra("f-clip", "Zed", "Clip"),
                extra("f-other", "Any", "Unknown"),
                extra("f-none", "Nothing", null),
                extra("f-deleted", "Gone", "DeletedScene"),
                extra("f-bts-b", "b roll", "BehindTheScenes"),
                extra("f-bts-a", "A roll", "BehindTheScenes"),
                extra("f-interview", "Talk", "Interview"),
                extra("f-feat", "Making of", "Featurette"),
                extra("f-short", "Tiny", "Short"),
                extra("f-sample", "Taste", "Sample"),
                extra("f-scene", "Opening", "Scene"),
            ),
            canOpenRemote = openAll,
        )
        val ids = list.map { entry ->
            when (entry) {
                is ExtraEntry.Local -> entry.item.id
                is ExtraEntry.Remote -> "remote:${entry.name}"
            }
        }
        assertEquals(
            listOf(
                "t1", "t2", "remote:Teaser",
                "f-bts-a", "f-bts-b", "f-feat", "f-interview", "f-scene", "f-deleted", "f-short", "f-clip", "f-sample",
                "f-other", "f-none",
            ),
            ids,
        )
    }

    @Test
    fun `unknown and untyped features keep name order after the known types`() {
        val list = buildExtrasList(
            "Series",
            emptyList(),
            emptyList(),
            listOf(extra("b", "banana", null), extra("a", "Apple", "Mystery"), extra("c", "cherry", "Clip")),
            openAll,
        )
        assertEquals(listOf("c", "a", "b"), list.map { (it as ExtraEntry.Local).item.id })
    }

    @Test
    fun `a movie drops the server trailer its pill plays`() {
        val list = buildExtrasList("Movie", listOf(extra("t1"), extra("t2")), listOf(link(YOUTUBE_URL)), emptyList(), openAll)
        assertEquals(listOf(ExtraEntry.Local(extra("t2")), ExtraEntry.Remote(null, YOUTUBE_URL)), list)
    }

    @Test
    fun `a movie with no server trailer drops the first openable link`() {
        val list = buildExtrasList("Movie", emptyList(), listOf(link(OTHER_URL, "Dead"), link(YOUTUBE_URL, "One"), link("$YOUTUBE_URL&x=1", "Two")), emptyList()) { it != OTHER_URL }
        assertEquals(listOf(ExtraEntry.Remote("Two", "$YOUTUBE_URL&x=1")), list)
    }

    @Test
    fun `a series keeps every trailer`() {
        val list = buildExtrasList("Series", listOf(extra("t1")), listOf(link(YOUTUBE_URL)), emptyList(), openAll)
        assertEquals(2, list.size)
    }

    @Test
    fun `theme songs are never listed and unopenable links are left out`() {
        val list = buildExtrasList(
            "Series",
            emptyList(),
            listOf(link(OTHER_URL), link(YOUTUBE_URL)),
            listOf(extra("song", "Theme", "ThemeSong"), extra("clip", "Clip", "Clip")),
        ) { it == YOUTUBE_URL }
        assertEquals(listOf(ExtraEntry.Remote(null, YOUTUBE_URL), ExtraEntry.Local(extra("clip", "Clip", "Clip"))), list)
    }

    // -- buildExtraRows ---------------------------------------------------------

    @Test
    fun `rows show the server name verbatim with the kind in the state column`() {
        val rows = buildExtraRows(
            strings,
            listOf(
                ExtraEntry.Local(extra("t", "tHe TeAsEr!", "Trailer")),
                ExtraEntry.Remote("Official", YOUTUBE_URL),
                ExtraEntry.Remote("Official", OTHER_URL),
                ExtraEntry.Local(extra("f1", "a", "BehindTheScenes")),
                ExtraEntry.Local(extra("f2", "b", "Featurette")),
                ExtraEntry.Local(extra("f3", "c", "Interview")),
                ExtraEntry.Local(extra("f4", "d", "Scene")),
                ExtraEntry.Local(extra("f5", "e", "DeletedScene")),
                ExtraEntry.Local(extra("f6", "f", "Short")),
                ExtraEntry.Local(extra("f7", "g", "Clip")),
                ExtraEntry.Local(extra("f8", "h", "Sample")),
                ExtraEntry.Local(extra("f9", "i", "Unknown")),
                ExtraEntry.Local(extra("f10", "j", null)),
            ),
        )
        assertEquals(
            listOf(
                "tHe TeAsEr!" to "TRAILER",
                "Official" to "YOUTUBE",
                "Official" to "TRAILER",
                "a" to "BEHIND THE SCENES",
                "b" to "FEATURETTE",
                "c" to "INTERVIEW",
                "d" to "SCENE",
                "e" to "DELETED SCENE",
                "f" to "SHORT",
                "g" to "CLIP",
                "h" to "SAMPLE",
                "i" to "EXTRA",
                "j" to "EXTRA",
            ),
            rows.map { it.label to it.subtext },
        )
    }

    @Test
    fun `a blank trailer name falls back to Trailer, a blank feature name to Extra`() {
        val rows = buildExtraRows(
            strings,
            listOf(
                ExtraEntry.Local(extra("t", " ", "Trailer")),
                ExtraEntry.Remote(null, YOUTUBE_URL),
                ExtraEntry.Remote("  ", YOUTUBE_URL),
                ExtraEntry.Local(extra("f", "", "Clip")),
            ),
        )
        assertEquals(listOf("Trailer", "Trailer", "Trailer", "Extra"), rows.map { it.label })
    }

    // -- the menu row -----------------------------------------------------------

    private fun input(itemType: String, scopeSeason: Card? = null, extrasAvailable: Boolean = true): MenuInput {
        val card = testCard(id = "x-1", itemType = itemType)
        return MenuInput(
            card = card,
            itemType = itemType,
            scopeSeason = scopeSeason,
            scopeEpisodes = emptyList(),
            allEpisodes = emptyList(),
            primaryAction = DetailFormatting.resolvePrimaryAction(strings, card, emptyList()),
            isFavorite = false,
            hasCollections = true,
            isAdministrator = true,
            seriesCard = null,
            extrasAvailable = extrasAvailable,
        )
    }

    private fun MenuModel.playbackRows(): List<MenuRow> = groups.firstOrNull { it.group == MenuGroup.PLAYBACK }?.rows.orEmpty()

    @Test
    fun `the Extras row ends the Playback group on Movie Series and Episode`() {
        listOf("Movie", "Series", "Episode").forEach { type ->
            val playback = buildMenu(strings, input(type)).playbackRows()
            assertEquals(type, MenuAction.Extras, playback.last().action)
            assertEquals(type, "Trailers & extras", playback.last().label)
            assertNull(type, playback.last().subtext)
        }
    }

    @Test
    fun `no Extras row without availability, and never on a collection`() {
        listOf("Movie", "Series", "Episode").forEach { type ->
            assertFalse(type, buildMenu(strings, input(type, extrasAvailable = false)).groups.flatMap { it.rows }.any { it.action == MenuAction.Extras })
        }
        assertFalse(buildMenu(strings, input("BoxSet")).groups.flatMap { it.rows }.any { it.action == MenuAction.Extras })
    }

    @Test
    fun `season scope keeps the Extras row with no subtext`() {
        val season = testCard(id = "season-1", itemType = "Season", indexNumber = 1)
        val row = buildMenu(strings, input("Series", scopeSeason = season)).playbackRows().last()
        assertEquals(MenuAction.Extras, row.action)
        assertEquals(null, row.subtext)
    }

    @Test
    fun `the Extras row leaves the focus prediction where it was`() {
        val with = buildMenu(strings, input("Movie", extrasAvailable = true)).focusOn
        val without = buildMenu(strings, input("Movie", extrasAvailable = false)).focusOn
        assertEquals(without, with)
    }
}

/** docs/19 §1.3: the view model's extras state, pill and plays. */
class DetailExtrasViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val movie = testCard(id = "movie-1", itemType = "Movie")

    private inline fun withDetailViewModel(viewModel: DetailViewModel, block: (DetailViewModel) -> Unit) {
        try {
            block(viewModel)
        } finally {
            val store = ViewModelStore()
            store.put("detail", viewModel)
            store.clear()
        }
    }

    private fun TestScope.detailViewModel(
        gateway: CoreGateway,
        card: Card = movie,
        canOpenTrailer: (String) -> Boolean = openAll,
    ) = DetailViewModel(gateway, card, strings, Random.Default, SettingsStore(gateway, backgroundScope), canOpenTrailer)

    private fun gatewayWith(detail: ItemDetail, id: String = "movie-1") =
        FakeCoreGateway(itemDetailResultsByItemId = mapOf(id to Result.success(detail)))

    private fun DetailViewModel.extrasState() = state.value.extras

    private fun DetailViewModel.loadedIds(): List<String> =
        (extrasState() as ExtrasState.Loaded).entries.map { (it as ExtraEntry.Local).item.id }

    // -- idle cost ----------------------------------------------------------------

    @Test
    fun `loading the detail fetches no extras and asks no app when a server trailer exists`() = runTest {
        val gateway = gatewayWith(extrasDetail(localTrailerCount = 2, specialFeatureCount = 2))
        var asked = 0
        withDetailViewModel(detailViewModel(gateway, canOpenTrailer = { asked++; true })) { viewModel ->
            assertTrue(gateway.localTrailersCalls.isEmpty())
            assertTrue(gateway.specialFeaturesCalls.isEmpty())
            assertEquals(0, asked)
            assertEquals(PillTrailer.Local, viewModel.state.value.pillTrailer)
            assertEquals(ExtrasState.Idle, viewModel.extrasState())
        }
    }

    @Test
    fun `opening the menu with zero counts fetches nothing and shows no Extras row`() = runTest {
        val gateway = gatewayWith(extrasDetail())
        withDetailViewModel(detailViewModel(gateway)) { viewModel ->
            viewModel.openMenu()

            assertTrue(gateway.localTrailersCalls.isEmpty())
            assertTrue(gateway.specialFeaturesCalls.isEmpty())
            assertEquals(ExtrasState.Idle, viewModel.extrasState())
            assertFalse(viewModel.state.value.menu!!.model.groups.flatMap { it.rows }.any { it.action == MenuAction.Extras })
        }
    }

    @Test
    fun `opening the menu fetches only the endpoints that counted something`() = runTest {
        val trailersOnly = gatewayWith(extrasDetail(localTrailerCount = 2)).also {
            it.localTrailersByItemId["movie-1"] = listOf(extra("t1"), extra("t2"))
        }
        withDetailViewModel(detailViewModel(trailersOnly)) { viewModel ->
            viewModel.openMenu()
            assertEquals(listOf("movie-1"), trailersOnly.localTrailersCalls)
            assertTrue(trailersOnly.specialFeaturesCalls.isEmpty())
            assertEquals(listOf("t2"), viewModel.loadedIds())
        }

        val featuresOnly = gatewayWith(extrasDetail(specialFeatureCount = 1)).also {
            it.specialFeaturesByItemId["movie-1"] = listOf(extra("f1", "Making of", "Featurette"))
        }
        withDetailViewModel(detailViewModel(featuresOnly)) { viewModel ->
            viewModel.openMenu()
            assertTrue(featuresOnly.localTrailersCalls.isEmpty())
            assertEquals(listOf("movie-1"), featuresOnly.specialFeaturesCalls)
            assertEquals(listOf("f1"), viewModel.loadedIds())
        }
    }

    @Test
    fun `the menu lists the Extras row and the level builds from both endpoints`() = runTest {
        val gateway = gatewayWith(extrasDetail(localTrailerCount = 1, specialFeatureCount = 1, remoteTrailers = listOf(link(YOUTUBE_URL, "Teaser")))).also {
            it.localTrailersByItemId["movie-1"] = listOf(extra("t1"))
            it.specialFeaturesByItemId["movie-1"] = listOf(extra("f1", "Making of", "Featurette"))
        }
        withDetailViewModel(detailViewModel(gateway)) { viewModel ->
            viewModel.openMenu()

            val playback = viewModel.state.value.menu!!.model.groups.first { it.group == MenuGroup.PLAYBACK }.rows
            assertEquals(MenuAction.Extras, playback.last().action)
            assertEquals(
                listOf(ExtraEntry.Remote("Teaser", YOUTUBE_URL), ExtraEntry.Local(extra("f1", "Making of", "Featurette"))),
                (viewModel.extrasState() as ExtrasState.Loaded).entries,
            )
        }
    }

    // -- the extras state machine ---------------------------------------------------

    @Test
    fun `sequence Idle to Loading to Loaded, and reopening never refetches`() = runTest {
        val gateway = gatewayWith(extrasDetail(localTrailerCount = 2)).also {
            it.localTrailersByItemId["movie-1"] = listOf(extra("t1"), extra("t2"))
            it.localTrailersGate = CompletableDeferred()
        }
        withDetailViewModel(detailViewModel(gateway)) { viewModel ->
            assertEquals(ExtrasState.Idle, viewModel.extrasState())

            viewModel.openMenu()
            assertEquals(ExtrasState.Loading, viewModel.extrasState())

            viewModel.openMenu() // Loading: no second fetch
            assertEquals(1, gateway.localTrailersCalls.size)

            gateway.localTrailersGate!!.complete(Unit)
            runCurrent()
            assertEquals(listOf("t2"), viewModel.loadedIds())

            viewModel.closeMenu()
            viewModel.openMenu() // Loaded: no refetch
            assertEquals(1, gateway.localTrailersCalls.size)
            assertEquals(listOf("t2"), viewModel.loadedIds())
        }
    }

    @Test
    fun `sequence Failed then retry on the next open`() = runTest {
        val gateway = gatewayWith(extrasDetail(specialFeatureCount = 1)).also {
            it.specialFeaturesError = RuntimeException("boom")
            it.specialFeaturesByItemId["movie-1"] = listOf(extra("f1", "Clip", "Clip"))
        }
        withDetailViewModel(detailViewModel(gateway)) { viewModel ->
            viewModel.openMenu()
            assertEquals(ExtrasState.Failed, viewModel.extrasState())

            gateway.specialFeaturesError = null
            viewModel.openMenu()
            runCurrent()

            assertEquals(2, gateway.specialFeaturesCalls.size)
            assertEquals(listOf("f1"), viewModel.loadedIds())
        }
    }

    @Test
    fun `one failing endpoint fails the whole level`() = runTest {
        val gateway = gatewayWith(extrasDetail(localTrailerCount = 2, specialFeatureCount = 1)).also {
            it.localTrailersByItemId["movie-1"] = listOf(extra("t1"), extra("t2"))
            it.specialFeaturesError = RuntimeException("boom")
        }
        withDetailViewModel(detailViewModel(gateway)) { viewModel ->
            viewModel.openMenu()
            assertEquals(ExtrasState.Failed, viewModel.extrasState())
        }
    }

    @Test
    fun `closing the menu during Loading lets the result land`() = runTest {
        val gateway = gatewayWith(extrasDetail(localTrailerCount = 2)).also {
            it.localTrailersByItemId["movie-1"] = listOf(extra("t1"), extra("t2"))
            it.localTrailersGate = CompletableDeferred()
        }
        withDetailViewModel(detailViewModel(gateway)) { viewModel ->
            viewModel.openMenu()
            viewModel.runAction(MenuAction.Extras)
            viewModel.backFromExtras()
            viewModel.closeMenu()
            assertEquals(ExtrasState.Loading, viewModel.extrasState())

            gateway.localTrailersGate!!.complete(Unit)
            runCurrent()

            assertEquals(listOf("t2"), viewModel.loadedIds())
            assertNull(viewModel.state.value.menu)
        }
    }

    @Test
    fun `the mirror record never derives the pill or the Extras row, the settled one does`() = runTest {
        val fake = gatewayWith(extrasDetail(localTrailerCount = 2)).also {
            it.itemDetailLocalByItemId["movie-1"] = extrasDetail(localTrailerCount = 2)
            it.localTrailersByItemId["movie-1"] = listOf(extra("t1"), extra("t2"))
        }
        val liveGate = CompletableDeferred<Unit>()
        val gateway = object : CoreGateway by fake {
            override suspend fun getItemDetail(itemId: String, accountEpoch: ULong?): ItemDetail {
                liveGate.await()
                return fake.getItemDetail(itemId, accountEpoch)
            }
        }
        withDetailViewModel(detailViewModel(gateway)) { viewModel ->
            assertEquals(2, viewModel.state.value.itemDetail?.localTrailerCount)
            assertNull(viewModel.state.value.pillTrailer)
            assertFalse(viewModel.state.value.extrasAvailable)
            viewModel.openMenu()
            assertFalse(viewModel.state.value.menu!!.model.groups.flatMap { it.rows }.any { it.action == MenuAction.Extras })
            assertTrue(fake.localTrailersCalls.isEmpty())

            liveGate.complete(Unit)
            runCurrent()

            assertEquals(PillTrailer.Local, viewModel.state.value.pillTrailer)
            assertTrue(viewModel.state.value.extrasAvailable)
        }
    }

    @Test
    fun `each link is asked about once per page however often the menu opens`() = runTest {
        val gateway = gatewayWith(extrasDetail(remoteTrailers = listOf(link(OTHER_URL), link(YOUTUBE_URL))))
        val asked = mutableListOf<String>()
        withDetailViewModel(detailViewModel(gateway, canOpenTrailer = { asked += it; true })) { viewModel ->
            viewModel.openMenu()
            viewModel.runAction(MenuAction.Extras)
            viewModel.backFromExtras()
            viewModel.closeMenu()
            viewModel.openMenu()

            assertEquals(setOf(OTHER_URL, YOUTUBE_URL), asked.toSet())
            assertEquals(asked.toSet().size, asked.size)
        }
    }

    @Test
    fun `selecting the Extras row retries a failed fetch`() = runTest {
        val gateway = gatewayWith(extrasDetail(specialFeatureCount = 1)).also {
            it.specialFeaturesError = RuntimeException("boom")
            it.specialFeaturesByItemId["movie-1"] = listOf(extra("f1", "Clip", "Clip"))
        }
        withDetailViewModel(detailViewModel(gateway)) { viewModel ->
            viewModel.openMenu()
            assertEquals(ExtrasState.Failed, viewModel.extrasState())

            gateway.specialFeaturesError = null
            viewModel.runAction(MenuAction.Extras)
            runCurrent()

            assertEquals(MenuLevel.EXTRAS, viewModel.state.value.menu?.level)
            assertEquals(2, gateway.specialFeaturesCalls.size)
            assertEquals(listOf("f1"), viewModel.loadedIds())
        }
    }

    // -- levels and selection ---------------------------------------------------------

    @Test
    fun `the Extras row enters the level and back returns to the first`() = runTest {
        val gateway = gatewayWith(extrasDetail(specialFeatureCount = 1))
        withDetailViewModel(detailViewModel(gateway)) { viewModel ->
            viewModel.openMenu()

            viewModel.runAction(MenuAction.Extras)
            assertEquals(MenuLevel.EXTRAS, viewModel.state.value.menu?.level)

            viewModel.backFromExtras()
            assertEquals(MenuLevel.FIRST, viewModel.state.value.menu?.level)
        }
    }

    @Test
    fun `selecting a server extra plays it from the start and closes the menu`() = runTest {
        val gateway = gatewayWith(extrasDetail(specialFeatureCount = 1))
        withDetailViewModel(detailViewModel(gateway)) { viewModel ->
            viewModel.openMenu()
            viewModel.runAction(MenuAction.Extras)

            viewModel.selectExtra(ExtraEntry.Local(extra("f1", "Making of", "Featurette")))

            assertEquals(PendingPlayback("f1", startFromBeginning = true), viewModel.state.value.pendingPlayback)
            assertNull(viewModel.state.value.menu)
            viewModel.openMenu()
            assertEquals(MenuLevel.FIRST, viewModel.state.value.menu?.level)
        }
    }

    @Test
    fun `selecting a link closes the menu and hands the url over once`() = runTest {
        val gateway = gatewayWith(extrasDetail(specialFeatureCount = 1))
        withDetailViewModel(detailViewModel(gateway)) { viewModel ->
            viewModel.openMenu()

            viewModel.selectExtra(ExtraEntry.Remote("Teaser", YOUTUBE_URL))

            assertEquals(YOUTUBE_URL, viewModel.state.value.pendingTrailerUrl)
            assertNull(viewModel.state.value.menu)
            assertNull(viewModel.state.value.pendingPlayback)

            viewModel.consumePendingTrailer()
            assertNull(viewModel.state.value.pendingTrailerUrl)
        }
    }

    // -- the pill -------------------------------------------------------------------------

    @Test
    fun `the pill derives from the links when no server trailer exists`() = runTest {
        val detail = extrasDetail(remoteTrailers = listOf(link(OTHER_URL), link(YOUTUBE_URL)))
        withDetailViewModel(detailViewModel(gatewayWith(detail), canOpenTrailer = { it == YOUTUBE_URL })) { viewModel ->
            assertEquals(PillTrailer.Remote(YOUTUBE_URL), viewModel.state.value.pillTrailer)
        }
        withDetailViewModel(detailViewModel(gatewayWith(detail), canOpenTrailer = openNone)) { viewModel ->
            assertNull(viewModel.state.value.pillTrailer)
        }
    }

    @Test
    fun `a series gets no pill`() = runTest {
        val series = testCard(id = "series-1", itemType = "Series")
        val gateway = gatewayWith(extrasDetail(id = "series-1", localTrailerCount = 1), id = "series-1")
        withDetailViewModel(detailViewModel(gateway, card = series)) { viewModel ->
            assertNull(viewModel.state.value.pillTrailer)
        }
    }

    @Test
    fun `pressing a link pill hands the url over without any fetch`() = runTest {
        val gateway = gatewayWith(extrasDetail(remoteTrailers = listOf(link(YOUTUBE_URL))))
        withDetailViewModel(detailViewModel(gateway)) { viewModel ->
            viewModel.playPillTrailer()

            assertEquals(YOUTUBE_URL, viewModel.state.value.pendingTrailerUrl)
            assertTrue(gateway.localTrailersCalls.isEmpty())
        }
    }

    @Test
    fun `pressing the server pill plays the first trailer from the start`() = runTest {
        val gateway = gatewayWith(extrasDetail(localTrailerCount = 2)).also {
            it.localTrailersByItemId["movie-1"] = listOf(extra("t1"), extra("t2"))
        }
        withDetailViewModel(detailViewModel(gateway)) { viewModel ->
            viewModel.playPillTrailer()

            assertEquals(listOf("movie-1"), gateway.localTrailersCalls)
            assertEquals(PendingPlayback("t1", startFromBeginning = true), viewModel.state.value.pendingPlayback)
        }
    }

    @Test
    fun `a second press while the lookup runs is ignored, a press after it settles is not`() = runTest {
        val gateway = gatewayWith(extrasDetail(localTrailerCount = 1)).also {
            it.localTrailersByItemId["movie-1"] = listOf(extra("t1"))
            it.localTrailersGate = CompletableDeferred()
        }
        withDetailViewModel(detailViewModel(gateway)) { viewModel ->
            viewModel.playPillTrailer()
            viewModel.playPillTrailer()
            assertEquals(1, gateway.localTrailersCalls.size)

            gateway.localTrailersGate!!.complete(Unit)
            runCurrent()
            assertEquals(PendingPlayback("t1", startFromBeginning = true), viewModel.state.value.pendingPlayback)

            viewModel.consumePendingPlayback()
            viewModel.playPillTrailer()
            runCurrent()
            assertEquals(2, gateway.localTrailersCalls.size)
        }
    }

    @Test
    fun `a failed pill lookup toasts server unreachable and starts nothing`() = runTest {
        val gateway = gatewayWith(extrasDetail(localTrailerCount = 1)).also { it.localTrailersError = RuntimeException("boom") }
        withDetailViewModel(detailViewModel(gateway)) { viewModel ->
            viewModel.playPillTrailer()

            assertEquals("Couldn't reach the server", viewModel.state.value.toast)
            assertNull(viewModel.state.value.pendingPlayback)
        }
    }

    @Test
    fun `an empty pill lookup toasts that no trailer can play`() = runTest {
        val gateway = gatewayWith(extrasDetail(localTrailerCount = 1))
        withDetailViewModel(detailViewModel(gateway)) { viewModel ->
            viewModel.playPillTrailer()

            assertEquals("No app can play this trailer", viewModel.state.value.toast)
            assertNull(viewModel.state.value.pendingPlayback)
        }
    }

    @Test
    fun `the pill lookup and the menu fetch run independently`() = runTest {
        val gateway = gatewayWith(extrasDetail(localTrailerCount = 2)).also {
            it.localTrailersByItemId["movie-1"] = listOf(extra("t1"), extra("t2"))
            it.localTrailersGate = CompletableDeferred()
        }
        withDetailViewModel(detailViewModel(gateway)) { viewModel ->
            viewModel.playPillTrailer()
            viewModel.openMenu()
            assertEquals(2, gateway.localTrailersCalls.size)
            assertEquals(ExtrasState.Loading, viewModel.extrasState())

            gateway.localTrailersGate!!.complete(Unit)
            runCurrent()

            assertEquals(listOf("t2"), viewModel.loadedIds())
            assertEquals(PendingPlayback("t1", startFromBeginning = true), viewModel.state.value.pendingPlayback)
        }
    }

    // -- a lookup the viewer has walked away from ---------------------------------------------

    private fun pillLookupThen(leave: (DetailViewModel) -> Unit): PendingPlayback? {
        var pending: PendingPlayback? = null
        runTest {
            val gateway = gatewayWith(extrasDetail(localTrailerCount = 1, specialFeatureCount = 1)).also {
                it.localTrailersByItemId["movie-1"] = listOf(extra("t1"))
                it.localTrailersGate = CompletableDeferred()
            }
            withDetailViewModel(detailViewModel(gateway)) { viewModel ->
                viewModel.playPillTrailer()
                leave(viewModel)
                gateway.localTrailersGate!!.complete(Unit)
                runCurrent()
                pending = viewModel.state.value.pendingPlayback
            }
        }
        return pending
    }

    @Test
    fun `a slow pill lookup never plays after the viewer left`() {
        val leaves = mapOf<String, (DetailViewModel) -> Unit>(
            "page went inactive" to { it.setActive(false) },
            "the Play pill pressed" to { it.cancelTrailerLookup() },
            "link extra" to { it.selectExtra(ExtraEntry.Remote("Teaser", YOUTUBE_URL)) },
            "watch together" to { it.runAction(MenuAction.WatchTogether("movie-1", 0L)) },
        )
        assertEquals(emptyList<String>(), leaves.filter { (_, leave) -> pillLookupThen(leave) != null }.keys.toList())
    }

    @Test
    fun `another playback start wins over a slow pill lookup`() {
        val starts = mapOf<String, Pair<(DetailViewModel) -> Unit, String>>(
            "play row" to ({ it: DetailViewModel -> it.runAction(MenuAction.PlayFromBeginning("other-1")) } to "other-1"),
            "server extra" to ({ it: DetailViewModel -> it.selectExtra(ExtraEntry.Local(extra("f1"))) } to "f1"),
        )
        assertEquals(
            emptyList<String>(),
            starts.filter { (_, start) -> pillLookupThen(start.first) != PendingPlayback(start.second, startFromBeginning = true) }.keys.toList(),
        )
    }
}
