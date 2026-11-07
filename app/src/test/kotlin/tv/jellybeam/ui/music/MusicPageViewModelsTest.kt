package tv.jellybeam.ui.music

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import tv.jellybeam.MainDispatcherRule
import tv.jellybeam.data.FakeCoreGateway
import tv.jellybeam.ui.cards.testCard
import uniffi.jellybeam_core.AlbumSummary
import uniffi.jellybeam_core.MusicArtistRow
import uniffi.jellybeam_core.PlaylistEntry
import uniffi.jellybeam_core.SongSource

/** docs/29 §9 B–E: the album, artist, song-list and playlist pages page 100 rows at a time and keep what they show. */
@OptIn(ExperimentalCoroutinesApi::class)
class MusicPageViewModelsTest {

    @get:Rule val main = MainDispatcherRule()

    private fun tracks(n: Int, prefix: String = "t") = (1..n).map { testCard(id = "$prefix$it", itemType = "Audio", name = "Song $it") }

    private fun albumGateway(n: Int) = FakeCoreGateway().apply {
        cardsByItemId["al"] = testCard(id = "al", itemType = "MusicAlbum")
        albumTracksByAlbum = mapOf("al" to tracks(n))
        albumSummaryByAlbum = mapOf("al" to AlbumSummary(trackCount = n.toULong(), discCount = 1uL, runtimeTicks = 0L))
    }

    @Test
    fun `an album page asked for twice while it loads appends its rows once`() {
        val gateway = albumGateway(250)
        val vm = MusicAlbumViewModel(gateway, "al", specLine = { "" }).also { it.setActive(true) }
        assertEquals(100, vm.state.value.tracks.size)
        val held = CompletableDeferred<Unit>().also { gateway.albumTracksGate = it }
        vm.loadMore()
        vm.loadMore()
        held.complete(Unit)
        val ids = vm.state.value.tracks.map { it.id }
        assertEquals("no row twice", ids.distinct(), ids)
        assertEquals(200, ids.size)
    }

    @Test
    fun `an album refresh re-reads the shown rows a page at a time`() {
        val gateway = albumGateway(250)
        val vm = MusicAlbumViewModel(gateway, "al", specLine = { "" }).also { it.setActive(true) }
        vm.loadMore()
        vm.loadMore()
        assertEquals(250, vm.state.value.tracks.size)
        gateway.albumTracksCalls.clear()
        vm.retry()
        assertEquals(250, vm.state.value.tracks.size)
        assertEquals(listOf("0+100", "100+100", "200+100"), gateway.albumTracksCalls)
    }

    @Test
    fun `a performer's songs page past the first 100`() {
        val gateway = FakeCoreGateway().apply {
            artistSummaryById = mapOf("ar" to MusicArtistRow("ar", "Synthetic Band", null, null, 0uL, 0uL, 250uL, 0L))
            artistAlbumsResult = emptyList()
            musicSongsResult = tracks(250)
        }
        val vm = MusicArtistViewModel(gateway, "ar").also { it.setActive(true) }
        assertEquals(100, vm.state.value.songs.size)
        vm.loadMoreSongs()
        vm.loadMoreSongs()
        assertEquals(tracks(250).map { it.id }, vm.state.value.songs.map { it.id })
        assertFalse(vm.state.value.songsMore)
        assertTrue("each read is one page", gateway.musicSongsCalls.all { it.endsWith("+100") })
    }

    @Test
    fun `a song-list refresh re-reads the shown rows a page at a time`() {
        val source = SongSource.Favorites("music-view")
        val gateway = FakeCoreGateway().apply { musicSongsResult = tracks(250); musicSongCountResult = 250uL }
        val vm = MusicSongsViewModel(gateway, source).also { it.setActive(true) }
        vm.loadMore()
        vm.loadMore()
        gateway.musicSongsCalls.clear()
        vm.retry()
        assertEquals(250, vm.state.value.songs.size)
        assertEquals(listOf("0+100", "100+100", "200+100"), gateway.musicSongsCalls)
    }

    private fun entries(n: Int) = (1..n).map { PlaylistEntry("e$it", testCard(id = "s$it", itemType = "Audio")) }

    @Test
    fun `a playlist that fails to refresh keeps its rows`() = runTest {
        val playlist = testCard(id = "pl", itemType = "Playlist")
        val gateway = FakeCoreGateway().apply { playlistItemsByPlaylist = mapOf("pl" to entries(3)) }
        val vm = MusicPlaylistViewModel(gateway, playlist, clock = { 7L }).also { it.setActive(true) }
        gateway.playlistItemsError = IOException("synthetic")
        gateway.addToPlaylist("pl", listOf("s1"))
        assertEquals(MusicLoad.Ready, vm.state.value.load)
        assertEquals(listOf("e1", "e2", "e3"), vm.state.value.reorder.entries.map { it.entryId })
        vm.retry()
        assertEquals("Retry from the page still says it failed", 7L, (vm.state.value.load as MusicLoad.Failed).atMs)
    }

    @Test
    fun `a playlist refresh keeps every page it had loaded`() = runTest {
        val playlist = testCard(id = "pl", itemType = "Playlist")
        val gateway = FakeCoreGateway().apply { playlistItemsByPlaylist = mapOf("pl" to entries(250)) }
        val vm = MusicPlaylistViewModel(gateway, playlist).also { it.setActive(true) }
        vm.loadMore()
        vm.loadMore()
        assertEquals(250, vm.state.value.reorder.entries.size)
        gateway.addToPlaylist("pl", listOf("s1"))
        assertEquals(250, vm.state.value.reorder.entries.size)
        assertFalse(vm.state.value.hasMore)
    }

    @Test
    fun `a hidden playlist page re-reads once on return after an edit elsewhere, and never otherwise`() = runTest {
        val playlist = testCard(id = "pl", itemType = "Playlist")
        val gateway = FakeCoreGateway().apply { playlistItemsByPlaylist = mapOf("pl" to entries(2)) }
        val vm = MusicPlaylistViewModel(gateway, playlist).also { it.setActive(true) }
        vm.setActive(false)
        vm.setActive(true)
        assertEquals("a return with no edit reads nothing", 1, gateway.playlistItemsCalls.size)

        vm.setActive(false)
        gateway.playlistItemsByPlaylist = mapOf("pl" to entries(3))
        gateway.addToPlaylist("other", listOf("s9"))
        gateway.addToPlaylist("pl", listOf("s3"))
        assertEquals("nothing is read while hidden", 1, gateway.playlistItemsCalls.size)
        vm.setActive(true)
        assertEquals(2, gateway.playlistItemsCalls.size)
        assertEquals(listOf("e1", "e2", "e3"), vm.state.value.reorder.entries.map { it.entryId })
        advanceTimeBy(1_000)
        assertEquals("one re-read per return", 2, gateway.playlistItemsCalls.size)
    }

    @Test
    fun `a removal from the open page re-reads it, and the page's own move does not`() = runTest {
        val playlist = testCard(id = "pl", itemType = "Playlist")
        val gateway = FakeCoreGateway().apply { playlistItemsByPlaylist = mapOf("pl" to entries(3)) }
        val vm = MusicPlaylistViewModel(gateway, playlist).also { it.setActive(true) }
        gateway.playlistItemsByPlaylist = mapOf("pl" to entries(2))
        gateway.removeFromPlaylist("pl", listOf("e3"))
        assertEquals(listOf("e1", "e2"), vm.state.value.reorder.entries.map { it.entryId })
        advanceTimeBy(1_000)
        gateway.movePlaylistItem("pl", "e2", 0u)
        advanceTimeBy(1_000)
        assertEquals(2, gateway.playlistItemsCalls.size)
    }
}
