package tv.jellybeam.ui.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import tv.jellybeam.i18n.ResourceUiStrings
import tv.jellybeam.i18n.UsLocaleRule
import tv.jellybeam.player.music.EnqueueMode
import tv.jellybeam.player.music.MusicFlow
import tv.jellybeam.player.music.MusicFlow.Pending
import tv.jellybeam.player.music.MusicFlow.Refusal
import tv.jellybeam.player.music.MusicNotice
import tv.jellybeam.player.music.MusicSession
import tv.jellybeam.player.music.MusicSource
import tv.jellybeam.player.music.QueueEntry
import tv.jellybeam.player.music.QueueOrigin
import tv.jellybeam.ui.cards.testCard
import uniffi.jellybeam_core.MixSeedFfi

/** docs/29 §9 L1, E2, E3, I1/I2 and §3.4: the menu rows, the playlist level, the mix's presentation and the bar's confirmations. */
class MusicMenuModelTest {

    @get:Rule val usLocale = UsLocaleRule()

    private val strings = ResourceUiStrings.default

    private val song = testCard(id = "s1", itemType = "Audio", name = "Song A").copy(albumId = "al1", albumArtistId = "ar1")
    private val album = testCard(id = "al1", itemType = "MusicAlbum", name = "Album A").copy(albumArtistId = "ar1")
    private val artist = testCard(id = "ar1", itemType = "MusicArtist", name = "Artist A")
    private val playlist = testCard(id = "p1", itemType = "Playlist", name = "List A")

    private fun menu(target: MusicMenuTarget, favorite: Boolean = false, admin: Boolean = true, reorder: Boolean = true) =
        buildMusicMenu(strings, MusicMenuInput(target, favorite, admin, reorder))

    private fun MusicMenuModel.table(): List<Pair<String, List<String>>> = groups.map { g -> g.heading to g.rows.map { it.label } }

    @Test
    fun `a song menu is this song, playback and library, seven rows`() {
        val model = menu(musicMenuTarget(song)!!)
        assertEquals(
            listOf(
                "THIS SONG" to listOf("Add to favorites", "Go to album", "Go to artist"),
                "PLAYBACK" to listOf("Play next", "Add to queue", "Instant Mix"),
                "LIBRARY" to listOf("Add to playlist"),
            ),
            model.table(),
        )
        assertEquals(listOf(MusicMenuAction.GoToAlbum("al1"), MusicMenuAction.GoToArtist("ar1")), model.rows.map { it.action }.subList(1, 3))
        assertTrue("Add to playlist drills in", model.rows.last().drillIn)
    }

    @Test
    fun `a song opened inside a playlist adds Remove from playlist as the eighth row`() {
        val model = menu(MusicMenuTarget(song, MusicMenuKind.Song, MusicMenuContext.InPlaylist("p1", "e7")))
        assertEquals(MUSIC_MENU_MAX_ROWS, model.rows.size)
        assertEquals(MusicMenuRow(MusicMenuAction.RemoveFromPlaylist("p1", "e7"), "Remove from playlist"), model.rows.last())
    }

    @Test
    fun `album, artist and playlist menus follow the L1 table`() {
        assertEquals(
            listOf(
                "THIS ALBUM" to listOf("Remove from favorites", "Go to artist"),
                "PLAYBACK" to listOf("Play next", "Add to queue", "Instant Mix"),
                "LIBRARY" to listOf("Add to playlist", "Refresh metadata"),
            ),
            menu(musicMenuTarget(album)!!, favorite = true).table(),
        )
        assertEquals(
            listOf("THIS ARTIST" to listOf("Add to favorites"), "PLAYBACK" to listOf("Play next", "Add to queue", "Instant Mix"), "LIBRARY" to listOf("Refresh metadata")),
            menu(musicMenuTarget(artist)!!).table(),
        )
        assertEquals(
            listOf("THIS PLAYLIST" to listOf("Add to favorites", "Reorder songs"), "PLAYBACK" to listOf("Play next", "Add to queue", "Instant Mix")),
            menu(musicMenuTarget(playlist)!!).table(),
        )
    }

    @Test
    fun `rows without their target are absent, never disabled`() {
        val bare = testCard(id = "s2", itemType = "Audio", name = "Song B")
        assertEquals(listOf("Add to favorites"), menu(musicMenuTarget(bare)!!).groups.first().rows.map { it.label })
        assertEquals(listOf("THIS ARTIST", "PLAYBACK"), menu(musicMenuTarget(artist)!!, admin = false).groups.map { it.heading })
        assertEquals(listOf("Add to favorites"), menu(musicMenuTarget(playlist)!!, reorder = false).groups.first().rows.map { it.label })
    }

    @Test
    fun `no music menu holds Watch together or more than eight rows`() {
        val targets = listOf(song, album, artist, playlist).map { musicMenuTarget(it)!! } +
            MusicMenuTarget(song, MusicMenuKind.Song, MusicMenuContext.InPlaylist("p1", "e1"))
        for (target in targets) for (favorite in listOf(false, true)) for (admin in listOf(false, true)) {
            val model = menu(target, favorite, admin)
            assertTrue("$target", model.rows.size <= MUSIC_MENU_MAX_ROWS)
            assertFalse("$target", model.rows.any { it.label == "Watch together" })
        }
    }

    @Test
    fun `a queue row offers play next, move, remove and go to album, the playing row only go to album`() {
        val upcoming = menu(MusicMenuTarget(song, MusicMenuKind.Song, MusicMenuContext.InQueue(3, 2, isCurrent = false, onCurrentAccount = true)))
        assertEquals(listOf("THIS SONG" to listOf("Go to album"), "PLAYBACK" to listOf("Play next", "Move", "Remove")), upcoming.table())
        val current = menu(MusicMenuTarget(song, MusicMenuKind.Song, MusicMenuContext.InQueue(0, 0, isCurrent = true, onCurrentAccount = true)))
        assertEquals(listOf("THIS SONG" to listOf("Go to album")), current.table())
    }

    @Test
    fun `a queue row of a parked session keeps the queue rows and drops Go to album`() {
        val upcoming = menu(MusicMenuTarget(song, MusicMenuKind.Song, MusicMenuContext.InQueue(3, 2, isCurrent = false, onCurrentAccount = false)))
        assertEquals(listOf("PLAYBACK" to listOf("Play next", "Move", "Remove")), upcoming.table())
    }

    @Test
    fun `the Now Playing menu has no Play next or Add to queue`() {
        val here = menu(nowPlayingMenuTarget(song, sessionEpoch = 2uL, currentEpoch = 2uL)!!)
        assertEquals(
            listOf(
                "THIS SONG" to listOf("Add to favorites", "Go to album", "Go to artist"),
                "PLAYBACK" to listOf("Instant Mix"),
                "LIBRARY" to listOf("Add to playlist"),
            ),
            here.table(),
        )
    }

    @Test
    fun `a song playing on a parked account has no Now Playing menu or heart, since every row acts on the current account`() {
        assertEquals(null, nowPlayingMenuTarget(song, sessionEpoch = 1uL, currentEpoch = 2uL))
        assertEquals("no song card yet, nothing to act on", null, nowPlayingMenuTarget(null, sessionEpoch = 2uL, currentEpoch = 2uL))
        assertEquals("no session yet", null, nowPlayingMenuTarget(song, sessionEpoch = null, currentEpoch = 2uL))
    }

    @Test
    fun `only music items open a music menu`() {
        assertEquals(null, musicMenuTarget(testCard(itemType = "Movie")))
        assertEquals(MusicMenuKind.Playlist, musicMenuTarget(playlist)?.kind)
    }

    @Test
    fun `Add to playlist lists New playlist, seven recent playlists, then All playlists when there are more`() {
        val lists = (1..8).map { testCard(id = "p$it", itemType = "Playlist", name = "List $it") }
        val picks = playlistPicks(lists, holding = setOf("p2"))
        assertEquals(PlaylistPick.New, picks.first())
        assertEquals(PlaylistPick.All, picks.last())
        assertEquals((1..7).map { "p$it" }, picks.filterIsInstance<PlaylistPick.Existing>().map { it.playlist.id })
        assertEquals(listOf("p2"), picks.filterIsInstance<PlaylistPick.Existing>().filter { it.inPlaylist }.map { it.playlist.id })
        assertEquals(listOf(PlaylistPick.New, PlaylistPick.Existing(lists[0], false)), playlistPicks(lists.take(1), emptySet()))
        assertEquals(RECENT_PLAYLISTS + 1, playlistPicks(lists.take(7), emptySet()).size)
    }

    @Test
    fun `Create and add exists only once the name has a character`() {
        assertFalse(canCreatePlaylist(""))
        assertFalse(canCreatePlaylist("   "))
        assertTrue(canCreatePlaylist("Night drive"))
    }

    @Test
    fun `the mix control shows dots only after 400 ms, and a refusal of its own seed says so`() {
        val seed = MixSeedFfi.Artist("ar1")
        val fetch = Pending.Fetch(4, 1uL, MusicSource.Mix(seed))
        assertEquals(MixShown.Waiting, mixShown(fetch, seed, waitedMs = 399))
        assertEquals(MixShown.Building, mixShown(fetch, seed, waitedMs = 400))
        assertEquals(MixShown.Idle, mixShown(fetch, MixSeedFfi.Artist("other"), waitedMs = 900))
        assertEquals(MixShown.Idle, mixShown(Pending.Fetch(4, 1uL, MusicSource.Artist("ar1")), seed, waitedMs = 900))
        val empty = mixShown(Pending.Refused(4, Refusal.Empty, MusicSource.Mix(seed)), seed, waitedMs = 0)
        val failed = mixShown(Pending.Refused(4, Refusal.Failed, MusicSource.Mix(seed)), seed, waitedMs = 0)
        assertEquals("one line for empty and failed", empty, failed)
        assertEquals("No mix for this artist yet", strings.get((empty as MixShown.NoMix).line))
        assertEquals(MixShown.Idle, mixShown(Pending.Refused(4, Refusal.Empty, MusicSource.Album("al1")), seed, 0))
        assertEquals(MixShown.Idle, mixShown(Pending.None, seed, 0))
        assertEquals(4L, mixRequest(fetch, seed))
        assertEquals(null, mixRequest(fetch, MixSeedFfi.Song("s1")))
    }

    @Test
    fun `Now Playing opens only on a session newly minted from a mix`() {
        val mix = MusicSource.Mix(MixSeedFfi.Album("al1"))
        fun starting(sid: Long, source: MusicSource) =
            MusicFlow(MusicFlow.Phase.Starting(MusicSession(sid, 1uL, source, listOf(QueueEntry("x", QueueOrigin.Source))), ticket = sid), lastSid = sid, lastTicket = sid)
        val idle = MusicFlow(pending = Pending.Fetch(1, 1uL, mix))
        assertTrue(mixStarted(idle, starting(1, mix)))
        assertFalse("a plain album play", mixStarted(MusicFlow(), starting(1, MusicSource.Album("al1"))))
        assertFalse("the same session again", mixStarted(starting(1, mix), starting(1, mix)))
        assertFalse("a refusal mints nothing", mixStarted(idle, MusicFlow(pending = Pending.Refused(1, Refusal.Empty, mix))))
    }

    @Test
    fun `a menu target seeds its mix and its queue from its own kind`() {
        assertEquals(MixSeedFfi.Song("s1"), mixSeed(musicMenuTarget(song)!!))
        assertEquals(MixSeedFfi.Playlist("p1"), mixSeed(musicMenuTarget(playlist)!!))
        assertEquals(MusicSource.Single(song), enqueueSource(musicMenuTarget(song)!!))
        assertEquals(MusicSource.Album("al1"), enqueueSource(musicMenuTarget(album)!!))
    }

    @Test
    fun `the bar confirms in mono with names verbatim`() {
        assertEquals("ADDED TO QUEUE │ Song a", musicNoticeText(MusicNotice.Queued(1, EnqueueMode.AddToQueue, "Song a"), strings))
        assertEquals("PLAYING NEXT │ Song a", musicNoticeText(MusicNotice.Queued(1, EnqueueMode.PlayNext, "Song a"), strings))
        assertEquals("ADDED TO Night drive", musicNoticeText(MusicNotice.AddedToPlaylist(2, "Night drive", again = false), strings))
        assertEquals("ALREADY IN · ADDED AGAIN", musicNoticeText(MusicNotice.AddedToPlaylist(2, "Night drive", again = true), strings))
    }
}
