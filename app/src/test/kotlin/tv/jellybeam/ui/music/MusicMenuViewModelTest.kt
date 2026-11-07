package tv.jellybeam.ui.music

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import tv.jellybeam.MainDispatcherRule
import tv.jellybeam.data.FakeCoreGateway
import tv.jellybeam.ui.cards.testCard

/** docs/29 §9 E2/E3 and L1: the menu's playlist level, its writes and the bar confirmations they leave. */
@OptIn(ExperimentalCoroutinesApi::class)
class MusicMenuViewModelTest {

    @get:Rule val main = MainDispatcherRule()

    private val song = testCard(id = "s1", itemType = "Audio", name = "Song A")
    private val lists = (1..8).map { testCard(id = "p$it", itemType = "Playlist", name = "List $it") }
    private val outcomes = mutableListOf<PlaylistOutcome>()
    private val favorited = mutableListOf<Pair<String, Boolean>>()

    private fun vm(gateway: FakeCoreGateway) =
        MusicMenuViewModel(gateway, CoroutineScope(UnconfinedTestDispatcher()), { id, on -> favorited += id to on }, { outcomes += it }, main = UnconfinedTestDispatcher())

    @Test
    fun `the playlist level marks the recent playlists that already hold the song`() {
        val gateway = FakeCoreGateway().apply {
            playlistsResult = lists
            playlistEntryIdsResult = mapOf("p3" to listOf("e9"), "p8" to listOf("e1"))
        }
        val vm = vm(gateway)
        vm.open(musicMenuTarget(song)!!, "row")
        vm.enterPlaylists()
        assertEquals(MusicMenuLevel.Playlists, vm.state.value.level)
        val ready = vm.state.value.recent as PlaylistsLoad.Ready
        assertEquals("one past the shown seven, so All playlists knows there are more", 8, ready.playlists.size)
        assertEquals("only the shown seven are scanned", setOf("p3"), ready.holding)
    }

    @Test
    fun `choosing a playlist that holds the song still adds it and confirms it went in again`() {
        val gateway = FakeCoreGateway().apply { playlistsResult = lists }
        val vm = vm(gateway)
        vm.open(musicMenuTarget(song)!!, "row")
        vm.enterPlaylists()
        vm.addTo(lists[2], again = true)
        assertNull("the panel closes", vm.state.value.target)
        assertEquals(listOf("add:p3:s1"), gateway.playlistWrites)
        assertEquals(listOf<PlaylistOutcome>(PlaylistOutcome.Added("List 3", again = true)), outcomes)
    }

    @Test
    fun `Create and add makes the named playlist holding the song, and a blank name does nothing`() {
        val gateway = FakeCoreGateway()
        val vm = vm(gateway)
        vm.open(musicMenuTarget(song)!!, "row")
        vm.enterPlaylists()
        vm.enterNewPlaylist()
        vm.createAndAdd("   ")
        assertEquals(emptyList<String>(), gateway.playlistWrites)
        assertEquals(MusicMenuLevel.NewPlaylist, vm.state.value.level)
        vm.createAndAdd(" Night drive ")
        assertEquals(listOf("create:Night drive:s1"), gateway.playlistWrites)
        assertEquals(listOf<PlaylistOutcome>(PlaylistOutcome.Added("Night drive", again = false)), outcomes)
    }

    @Test
    fun `a failed write confirms the failure`() {
        val gateway = FakeCoreGateway().apply { playlistWriteError = IllegalStateException("synthetic") }
        val vm = vm(gateway)
        vm.open(musicMenuTarget(song)!!, "row")
        vm.addTo(lists[0], again = false)
        assertEquals(listOf<PlaylistOutcome>(PlaylistOutcome.Failed("List 1")), outcomes)
    }

    @Test
    fun `Back walks up one level and closes from the first`() {
        val vm = vm(FakeCoreGateway())
        vm.open(musicMenuTarget(song)!!, "row")
        vm.enterPlaylists()
        vm.enterNewPlaylist()
        vm.back()
        assertEquals(MusicMenuLevel.Playlists, vm.state.value.level)
        vm.back()
        assertEquals(MusicMenuLevel.First, vm.state.value.level)
        vm.back()
        assertNull(vm.state.value.target)
    }

    @Test
    fun `the song's heart flips in place and a failed write puts it back`() {
        val gateway = FakeCoreGateway()
        val vm = vm(gateway)
        vm.open(musicMenuTarget(song)!!, "row")
        vm.toggleFavorite()
        assertEquals(true, vm.state.value.isFavorite)
        assertEquals("only the write that landed reaches the playing session's card", listOf("s1" to true), favorited)
        gateway.setFavoriteError = IllegalStateException("synthetic")
        vm.toggleFavorite()
        assertEquals(true, vm.state.value.isFavorite)
        assertEquals(listOf("s1" to true), favorited)
    }

    @Test
    fun `All playlists lists every playlist, read a page at a time`() {
        val many = (1..250).map { testCard(id = "q$it", itemType = "Playlist", name = "List $it") }
        val gateway = FakeCoreGateway().apply { playlistsResult = many }
        val vm = vm(gateway)
        vm.open(musicMenuTarget(song)!!, "row")
        gateway.playlistsCalls.clear()
        vm.enterAllPlaylists()
        assertEquals(many, (vm.state.value.all as PlaylistsLoad.Ready).playlists)
        assertEquals(listOf("0+100", "100+100", "200+100"), gateway.playlistsCalls)
    }
}
