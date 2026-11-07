package tv.jellybeam.ui.music

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import tv.jellybeam.MainDispatcherRule
import tv.jellybeam.data.FakeCoreGateway
import tv.jellybeam.ui.cards.testCard
import tv.jellybeam.ui.cards.ArtSource
import uniffi.jellybeam_core.ArtistScope
import uniffi.jellybeam_core.ChangeEvent
import uniffi.jellybeam_core.LiveMusicGenre
import uniffi.jellybeam_core.MusicGenreRow
import uniffi.jellybeam_core.GridFilters
import uniffi.jellybeam_core.GridSort
import uniffi.jellybeam_core.GridSortField
import uniffi.jellybeam_core.LibraryGridPrefs
import uniffi.jellybeam_core.MusicArtistRow
import uniffi.jellybeam_core.StatusFilter
import uniffi.jellybeam_core.ViewKind
import uniffi.jellybeam_core.ViewSnapshot
import uniffi.jellybeam_core.WatchedFilter

@OptIn(ExperimentalCoroutinesApi::class)
class MusicLibraryViewModelTest {

    @get:Rule val main = MainDispatcherRule()

    private val view = ViewSnapshot("music-view", "Synthetic Music", ViewKind.LIBRARY, "music")
    private val persist = CoroutineScope(UnconfinedTestDispatcher())

    private fun albums(n: Int) = (1..n).map { testCard(id = "al$it", itemType = "MusicAlbum", name = "Album $it") }

    private fun prefs(itemType: String?) = LibraryGridPrefs(
        GridSort(GridSortField.NAME, descending = false),
        GridFilters(WatchedFilter.ANY, null, null, StatusFilter.ANY, itemType),
    )

    private fun gateway(saved: String? = null, albumCount: Int = 3) = FakeCoreGateway(
        libraryGridByView = mapOf(view.id to albums(albumCount)),
        libraryGridPrefsByView = if (saved == null) mutableMapOf() else mutableMapOf(view.id to prefs(saved)),
    )

    @Test
    fun `nothing loads until the screen first becomes active`() {
        val gateway = gateway()
        val vm = MusicLibraryViewModel(gateway, view, persist)
        assertEquals(emptyList<Any>(), gateway.libraryGridCalls)
        vm.setActive(true)
        assertEquals(MusicTab.Albums, vm.state.value.tab)
        assertEquals("MusicAlbum", gateway.libraryGridCalls.single().filters.itemType)
        assertEquals(MusicLoad.Ready, vm.state.value.load)
        assertEquals(3, vm.state.value.items?.size)
    }

    @Test
    fun `the library opens on its persisted last-used tab`() {
        val gateway = gateway(saved = "Audio").apply { musicSongsResult = listOf(testCard(id = "s1", itemType = "Audio")) ; musicSongCountResult = 1uL }
        val vm = MusicLibraryViewModel(gateway, view, persist)
        vm.setActive(true)
        assertEquals(MusicTab.Songs, vm.state.value.tab)
        assertEquals(TabItems.Songs::class, vm.state.value.items!!::class)
    }

    @Test
    fun `choosing a tab loads it and persists it as the last-used tab`() {
        val gateway = gateway().apply {
            musicArtistsResult = listOf(MusicArtistRow("ar1", "Synthetic Band", null, null, 1uL, 0uL, 3uL, 0L))
            musicArtistCountResult = 1uL
        }
        val vm = MusicLibraryViewModel(gateway, view, persist)
        vm.setActive(true)
        vm.selectTab(MusicTab.Artists)
        assertEquals(MusicTab.Artists, vm.state.value.tab)
        assertEquals("MusicArtist", gateway.setLibraryGridPrefsCalls.last().prefs.filters.itemType)
        assertEquals(listOf(ArtistScope.ALBUM_ARTISTS), gateway.musicArtistsScopes)
        vm.selectArtistScope(ArtistScope.ALL)
        assertEquals(listOf(ArtistScope.ALBUM_ARTISTS, ArtistScope.ALL), gateway.musicArtistsScopes)
    }

    @Test
    fun `a next page of artists reads rows only, keeping the count and rail`() {
        val gateway = gateway(saved = "MusicArtist").apply {
            musicArtistsResult = (1..250).map { MusicArtistRow("ar$it", "Artist $it", null, null, 1uL, 0uL, 1uL, 0L) }
            musicArtistCountResult = 250uL
        }
        val vm = MusicLibraryViewModel(gateway, view, persist).also { it.setActive(true) }
        assertEquals(2, gateway.musicArtistTotalsCalls)
        vm.loadMore()
        assertEquals(200, vm.state.value.items?.size)
        assertEquals(250L, vm.state.value.count)
        assertEquals(2, gateway.musicArtistTotalsCalls)
    }

    @Test
    fun `the Playlists tab appears once a playlist exists`() {
        val gateway = gateway().apply { hasPlaylistsResult = true }
        val vm = MusicLibraryViewModel(gateway, view, persist)
        vm.setActive(true)
        assertTrue(MusicTab.Playlists in vm.state.value.tabs)
        assertTrue(MusicTab.Playlists !in MusicLibraryViewModel(gateway(), view, persist).also { it.setActive(true) }.state.value.tabs)
    }

    @Test
    fun `a failed tab read is the unreachable state, and Retry reloads it`() {
        val gateway = gateway().apply { libraryGridFails = true }
        val vm = MusicLibraryViewModel(gateway, view, persist, clock = { 42L })
        vm.setActive(true)
        assertEquals(42L, (vm.state.value.load as MusicLoad.Failed).atMs)
        gateway.libraryGridFails = false
        vm.retry()
        assertEquals(MusicLoad.Ready, vm.state.value.load)
    }

    @Test
    fun `the list pages 100 rows at a time`() {
        val gateway = gateway(albumCount = 250)
        val vm = MusicLibraryViewModel(gateway, view, persist)
        vm.setActive(true)
        assertEquals(100, vm.state.value.items?.size)
        vm.loadMore()
        assertEquals(200, vm.state.value.items?.size)
        assertEquals(listOf(0u, 100u), gateway.libraryGridCalls.map { it.offset })
        assertTrue(gateway.libraryGridCalls.all { it.limit == 100u })
    }

    @Test
    fun `New playlist creates an empty playlist under the typed name and opens it`() {
        val made = testCard(id = "new-playlist", itemType = "Playlist", name = "Night drive")
        val gateway = gateway(saved = "Playlist").apply {
            hasPlaylistsResult = true
            playlistsResult = listOf(testCard(id = "p1", itemType = "Playlist", name = "Old"))
        }
        val vm = MusicLibraryViewModel(gateway, view, persist).also { it.setActive(true) }
        vm.openNewPlaylist()
        assertEquals(NewPlaylistStep.Naming(), vm.state.value.newPlaylist)
        vm.createPlaylist("   ")
        assertEquals("a blank name creates nothing", emptyList<String>(), gateway.playlistWrites)
        gateway.playlistsResult = listOf(made) + gateway.playlistsResult
        vm.createPlaylist(" Night drive ")
        assertEquals(listOf("create:Night drive:"), gateway.playlistWrites)
        assertEquals(NewPlaylistStep.Created(made), vm.state.value.newPlaylist)
        assertEquals("the tab shows it first", made, (vm.state.value.items as TabItems.Playlists).cards.first())
        vm.createdShown()
        assertEquals(NewPlaylistStep.Closed, vm.state.value.newPlaylist)
    }

    @Test
    fun `a create whose reply spells the id with dashes still opens the listed playlist`() {
        val made = testCard(id = "0a1b2c3d-4e5f-4071-8293-a4b5c6d7e8f9".replace("-", ""), itemType = "Playlist", name = "Night drive")
        val gateway = gateway(saved = "Playlist").apply {
            hasPlaylistsResult = true
            createPlaylistResult = "0A1B2C3D-4E5F-4071-8293-A4B5C6D7E8F9"
        }
        val vm = MusicLibraryViewModel(gateway, view, persist).also { it.setActive(true) }
        vm.openNewPlaylist()
        gateway.playlistsResult = listOf(made)
        vm.createPlaylist("Night drive")
        assertEquals(NewPlaylistStep.Created(made), vm.state.value.newPlaylist)
    }

    @Test
    fun `a failed create keeps the name panel open and says so`() {
        val gateway = gateway(saved = "Playlist").apply { hasPlaylistsResult = true; playlistWriteError = IllegalStateException("synthetic") }
        val vm = MusicLibraryViewModel(gateway, view, persist).also { it.setActive(true) }
        vm.openNewPlaylist()
        vm.createPlaylist("Night drive")
        assertEquals(NewPlaylistStep.Naming(failed = true), vm.state.value.newPlaylist)
        vm.closeNewPlaylist()
        assertEquals(NewPlaylistStep.Closed, vm.state.value.newPlaylist)
    }

    private fun playlists(n: Int) = (1..n).map { testCard(id = "p$it", itemType = "Playlist", name = "List $it") }

    @Test
    fun `the Playlists tab pages to its end and counts once the end is known`() {
        val gateway = gateway(saved = "Playlist").apply { hasPlaylistsResult = true; playlistsResult = playlists(250) }
        val vm = MusicLibraryViewModel(gateway, view, persist).also { it.setActive(true) }
        assertEquals(100, vm.state.value.items?.size)
        assertEquals("no total while more pages may follow", null, vm.state.value.count)
        vm.loadMore()
        vm.loadMore()
        assertEquals(250, vm.state.value.items?.size)
        assertEquals(250L, vm.state.value.count)
        assertEquals(false, vm.state.value.hasMore)
    }

    @Test
    fun `a refresh re-reads the shown rows a page at a time`() {
        val gateway = gateway(albumCount = 250)
        val vm = MusicLibraryViewModel(gateway, view, persist).also { it.setActive(true) }
        vm.loadMore()
        vm.loadMore()
        val before = gateway.libraryGridCalls.size
        gateway.emitChange(ChangeEvent.Upserted(ids = listOf("al1"), libraryId = null))
        val refresh = gateway.libraryGridCalls.drop(before)
        assertEquals(listOf(0u to 100u, 100u to 100u, 200u to 100u), refresh.map { it.offset to it.limit })
        assertEquals(250, vm.state.value.items?.size)
    }

    @Test
    fun `genre art is read a page at a time for every genre`() {
        val names = (1..520).map { "Genre $it" }
        val gateway = gateway(saved = "MusicGenre").apply {
            musicGenresResult = names.map { MusicGenreRow(it, 1uL, 1uL) }
            musicGenresLiveResult = names.mapIndexed { i, n -> LiveMusicGenre("g$i", n, "tag$i") }
        }
        val vm = MusicLibraryViewModel(gateway, view, persist).also { it.setActive(true) }
        val tiles = (vm.state.value.items as TabItems.Genres).tiles
        assertEquals(520, tiles.count { it.art != ArtSource.None })
        assertTrue(gateway.musicGenresLiveCalls.all { it.endsWith("+100") })
    }

    @Test
    fun `genres show from the mirror while the live art is still on its way`() {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val gateway = gateway(saved = "MusicGenre").apply {
            musicGenresResult = listOf(MusicGenreRow("Rock", 2uL, 9uL), MusicGenreRow("Jazz", 1uL, 4uL))
            musicGenresLiveResult = listOf(LiveMusicGenre("g1", "Jazz", "tag1"))
            musicGenresLiveGate = gate
        }
        val vm = MusicLibraryViewModel(gateway, view, persist).also { it.setActive(true) }
        assertEquals(MusicLoad.Ready, vm.state.value.load)
        assertEquals(listOf(ArtSource.None, ArtSource.None), (vm.state.value.items as TabItems.Genres).tiles.map { it.art })
        gate.complete(Unit)
        val tiles = (vm.state.value.items as TabItems.Genres).tiles
        assertEquals(listOf("Rock" to false, "Jazz" to true), tiles.map { it.name to (it.art != ArtSource.None) })
    }
}

/** docs/29 §9 E3: the New playlist write outlives the screen that asked for it. */
@OptIn(ExperimentalCoroutinesApi::class)
class MusicLibraryCreateTest {
    private val scheduler = TestCoroutineScheduler()

    @get:Rule val main = MainDispatcherRule(StandardTestDispatcher(scheduler))

    @Test
    fun `a playlist create still lands when the screen goes before it runs`() {
        val view = ViewSnapshot("music-view", "Synthetic Music", ViewKind.LIBRARY, "music")
        val gateway = FakeCoreGateway()
        val vm = MusicLibraryViewModel(gateway, view, CoroutineScope(UnconfinedTestDispatcher(scheduler)))
        val store = ViewModelStore().apply { put("library", vm) }
        vm.setActive(true)
        scheduler.advanceUntilIdle()
        vm.openNewPlaylist()
        vm.createPlaylist("Night drive")
        store.clear()
        scheduler.advanceUntilIdle()
        assertEquals(listOf("create:Night drive:"), gateway.playlistWrites)
    }
}
