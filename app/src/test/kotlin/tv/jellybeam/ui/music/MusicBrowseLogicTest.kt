package tv.jellybeam.ui.music

import java.time.Instant
import java.time.ZoneOffset
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import tv.jellybeam.R
import tv.jellybeam.i18n.ResourceUiStrings
import tv.jellybeam.i18n.UsLocaleRule
import tv.jellybeam.nav.Screen
import tv.jellybeam.player.music.MusicFlow
import tv.jellybeam.player.music.MusicSession
import tv.jellybeam.player.music.MusicSource
import tv.jellybeam.player.music.QueueEntry
import tv.jellybeam.player.music.QueueOrigin
import tv.jellybeam.ui.cards.testCard
import uniffi.jellybeam_core.AlbumSummary
import uniffi.jellybeam_core.GridSort
import uniffi.jellybeam_core.GridSortField
import uniffi.jellybeam_core.MusicArtistRow
import uniffi.jellybeam_core.SongSource
import uniffi.jellybeam_core.ViewKind
import uniffi.jellybeam_core.ViewSnapshot

class MusicBrowseLogicTest {

    @get:Rule val usLocale = UsLocaleRule()

    private val strings = ResourceUiStrings.default
    private val utc = ZoneOffset.UTC

    private fun track(id: String, disc: Int?, number: Int? = 1) =
        testCard(id = id, itemType = "Audio", name = "Track $id").copy(parentIndexNumber = disc, indexNumber = number)

    private fun artist(albums: ULong, appearsOn: ULong, songs: ULong = 10uL, ticks: Long = 43L * 60_000L * 10_000L) =
        MusicArtistRow(id = "a", name = "Synthetic Band", sortName = null, primaryTag = null, albumCount = albums, appearsOnCount = appearsOn, songCount = songs, runtimeTicks = ticks)

    @Test
    fun `the Playlists tab shows only when a playlist or the Playlists view exists`() {
        val withoutPlaylists = listOf(MusicTab.Albums, MusicTab.Artists, MusicTab.Songs, MusicTab.Genres)
        assertEquals(withoutPlaylists, visibleMusicTabs(hasPlaylists = false, playlistsViewExists = false))
        assertEquals(MusicTab.entries.toList(), visibleMusicTabs(hasPlaylists = true, playlistsViewExists = false))
        assertEquals(MusicTab.entries.toList(), visibleMusicTabs(hasPlaylists = false, playlistsViewExists = true))
    }

    @Test
    fun `the Playlists view is found by its collection type`() {
        val music = ViewSnapshot("m", "Synthetic Music", ViewKind.LIBRARY, "music")
        val lists = ViewSnapshot("p", "Synthetic Lists", ViewKind.LIBRARY, "playlists")
        assertEquals(false, playlistsViewExists(listOf(music)))
        assertEquals(true, playlistsViewExists(listOf(music, lists)))
    }

    @Test
    fun `the landing opens on the last-used tab while it shows, and Albums the first time`() {
        val visible = visibleMusicTabs(hasPlaylists = false, playlistsViewExists = false)
        assertEquals(MusicTab.Albums, openingMusicTab(null, visible))
        assertEquals(MusicTab.Songs, openingMusicTab(MusicTab.Songs, visible))
        assertEquals(MusicTab.Albums, openingMusicTab(MusicTab.Playlists, visible))
        assertEquals(MusicTab.Genres, musicTabForItemType("MusicGenre"))
        assertNull(musicTabForItemType("Movie"))
    }

    @Test
    fun `a single-disc album has no disc headers`() {
        val entries = albumListEntries(listOf(track("1", 1), track("2", 1), track("3", null, null)))
        assertEquals(listOf(0, 1, 2), entries.map { (it as AlbumListEntry.Track).index })
    }

    @Test
    fun `a multi-disc album gets one header per disc and keeps every track's queue index`() {
        val entries = albumListEntries(listOf(track("1", 1), track("2", 1), track("3", 2), track("4", null, null)))
        assertEquals(
            listOf("disc 1", "track 0", "track 1", "disc 2", "track 2", "track 3"),
            entries.map {
                when (it) {
                    is AlbumListEntry.Disc -> "disc ${it.number}"
                    is AlbumListEntry.Track -> "track ${it.index}"
                }
            },
        )
    }

    @Test
    fun `rows show performers only on a compilation`() {
        fun by(vararg names: String) = testCard(id = names.joinToString(), itemType = "Audio").copy(artists = names.toList())
        assertEquals(false, isCompilation("Synthetic Band", listOf(by("Synthetic Band"), by("Synthetic Band"))))
        assertEquals(true, isCompilation("Various Artists", listOf(by("Performer A"), by("Performer B"))))
    }

    @Test
    fun `the album page opens on the playing track when it is on the album, else on Play`() {
        val tracks = listOf(track("1", 1), track("2", 1))
        assertEquals(AlbumOpenFocus.Track("2"), albumOpenFocus("2", tracks))
        assertEquals(AlbumOpenFocus.Play, albumOpenFocus("elsewhere", tracks))
        assertEquals(AlbumOpenFocus.Play, albumOpenFocus(null, tracks))
    }

    @Test
    fun `each song source has its own column set`() {
        assertEquals(SongColumns(SongLine2.ArtistAlbum, null, rail = true, plays = true), songColumns(SongSource.All("v")))
        assertEquals(SongColumns(SongLine2.ArtistAlbum, SongExtra.Added, rail = false, plays = false), songColumns(SongSource.Favorites("v")))
        assertEquals(SongColumns(SongLine2.ArtistAlbum, SongExtra.Played, rail = false, plays = true), songColumns(SongSource.RecentlyPlayed("v")))
        val byYear = SongColumns(SongLine2.AlbumOnly, SongExtra.Year, rail = false, plays = true)
        assertEquals(byYear, songColumns(SongSource.Genre("v", "Synthwave;Ambient")))
        assertEquals(byYear, songColumns(SongSource.Artist("a", GridSort(GridSortField.YEAR, descending = false))))
    }

    @Test
    fun `an artist or genre row shows the album alone and the year`() {
        val card = testCard(id = "s", itemType = "Audio", name = "Song").copy(album = "Synthetic Album", artists = listOf("Synthetic Band"), productionYear = 2019)
        val row = songRowData(card, songColumns(SongSource.Genre("v", "Pop")), strings, nowMs = 0L, zone = utc)
        assertEquals("Synthetic Album", row.line2)
        assertEquals("2019", row.extra)
    }

    @Test
    fun `a plays column shows the count, a dash when never played, and is absent for favourites`() {
        val played = testCard(id = "s", itemType = "Audio", name = "Song").copy(playCount = 1_204u)
        val never = testCard(id = "t", itemType = "Audio", name = "Song")
        assertEquals("1,204", songRowData(played, songColumns(SongSource.All("v")), strings, 0L, utc).plays)
        assertEquals("—", songRowData(never, songColumns(SongSource.RecentlyPlayed("v")), strings, 0L, utc).plays)
        assertNull(songRowData(played, songColumns(SongSource.Favorites("v")), strings, 0L, utc).plays)
    }

    @Test
    fun `Play on the playing album pauses or resumes it, any other album plays`() {
        val album = MusicSource.Album("al1")
        val active = MusicFlow(MusicFlow.Phase.Active(MusicSession(1, 1uL, album, listOf(QueueEntry("x", QueueOrigin.Source))), g = 1, n = 0, MusicFlow.Net.Online), lastSid = 1)
        assertEquals(AlbumPlayKey.Pause, albumPlayKey(active, isPlaying = true, "al1"))
        assertEquals(AlbumPlayKey.Resume, albumPlayKey(active, isPlaying = false, "al1"))
        assertEquals(AlbumPlayKey.Play, albumPlayKey(active, isPlaying = true, "al2"))
        assertEquals(AlbumPlayKey.Play, albumPlayKey(null, isPlaying = false, "al1"))
        assertEquals(AlbumPlayKey.Play, albumPlayKey(MusicFlow(), isPlaying = false, "al1"))
    }

    @Test
    fun `most played names its count in plays`() {
        assertEquals("14 PLAYS", mostPlayedCount(testCard(itemType = "Audio").copy(playCount = 14u), strings))
        assertEquals("1 PLAY", mostPlayedCount(testCard(itemType = "Audio").copy(playCount = 1u), strings))
    }

    @Test
    fun `an all-songs row joins performers and album verbatim`() {
        val card = testCard(id = "s", itemType = "Audio", name = "Song").copy(album = "Synthetic Album", artists = listOf("A;B", "C"))
        assertEquals("A;B, C · Synthetic Album", songRowData(card, songColumns(SongSource.All("v")), strings, 0L, utc).line2)
    }

    @Test
    fun `played reads minutes, hours, yesterday, days, then the date`() {
        val now = Instant.parse("2026-11-05T21:00:00Z")
        fun ago(iso: String) = playedAgo(Instant.parse(iso), now.toEpochMilli(), utc, strings)
        assertEquals("12 min ago", ago("2026-11-05T20:48:00Z"))
        assertEquals("1 h ago", ago("2026-11-05T19:30:00Z"))
        assertEquals("Yesterday", ago("2026-11-04T18:00:00Z"))
        assertEquals("2 days ago", ago("2026-11-03T18:00:00Z"))
        assertEquals("Oct 27", ago("2026-10-27T18:00:00Z"))
    }

    @Test
    fun `the sort strip counts with grouping and drops the order when empty`() {
        assertEquals("1,186 ALBUMS │ NAME", musicSortStrip(MusicTab.Albums, 1_186L, strings))
        assertEquals("4 PLAYLISTS │ RECENTLY CHANGED", musicSortStrip(MusicTab.Playlists, 4L, strings))
        assertEquals("1 GENRE │ NAME", musicSortStrip(MusicTab.Genres, 1L, strings))
        assertEquals("0 ALBUMS", musicSortStrip(MusicTab.Albums, 0L, strings))
        assertEquals("an unknown total shows the order alone", "RECENTLY CHANGED", musicSortStrip(MusicTab.Playlists, null, strings))
    }

    @Test
    fun `album facts list tracks, length and the added date`() {
        val card = testCard(id = "al", itemType = "MusicAlbum", name = "Synthetic Album").copy(
            albumArtist = "Synthetic Band",
            productionYear = 2019,
            dateCreated = "2026-10-01T10:00:00Z",
            playCount = 14u,
        )
        val facts = albumFacts(card, AlbumSummary(trackCount = 4uL, discCount = 1uL, runtimeTicks = 19L * 60_000L * 10_000L), strings, utc)
        assertEquals("Synthetic Album", facts.title)
        assertEquals("Synthetic Band · 2019", facts.subline)
        assertEquals(
            listOf(MusicFact("TRACKS", "4"), MusicFact("LENGTH", "19 min"), MusicFact("ADDED", "Oct 1"), MusicFact("PLAYED", "14 times")),
            facts.facts,
        )
    }

    @Test
    fun `album facts omit what is not known yet`() {
        val card = testCard(id = "al", itemType = "MusicAlbum", name = "Synthetic Album")
        val facts = albumFacts(card, summary = null, strings, utc)
        assertNull(facts.subline)
        assertEquals(emptyList<MusicFact>(), facts.facts)
    }

    @Test
    fun `artist facts count albums, or the albums a performer appears on`() {
        assertEquals(
            listOf(MusicFact("ALBUMS", "2"), MusicFact("SONGS", "10"), MusicFact("LENGTH", "43 min")),
            artistFacts(artist(albums = 2uL, appearsOn = 0uL), strings).facts,
        )
        assertEquals(MusicFact("APPEARS ON", "1"), artistFacts(artist(albums = 0uL, appearsOn = 1uL), strings).facts.first())
    }

    @Test
    fun `artist header and circle sublines`() {
        assertEquals("2 ALBUMS │ 10 SONGS │ 43 MIN", artistHeaderLine(artist(2uL, 0uL), strings))
        assertEquals("APPEARS ON 1 ALBUM │ 2 SONGS │ 10 MIN", artistHeaderLine(artist(0uL, 1uL, songs = 2uL, ticks = 10L * 60_000L * 10_000L), strings))
        assertEquals("1 album", artistCircleSubline(artist(1uL, 0uL), strings))
        assertEquals("Appears on 1", artistCircleSubline(artist(0uL, 1uL), strings))
    }

    @Test
    fun `lengths read in minutes, then hours and minutes`() {
        assertEquals("13 min", musicLength(13L * 60_000L * 10_000L, strings))
        assertEquals("2 h 51 min", musicLength((2L * 60 + 51) * 60_000L * 10_000L, strings))
        assertEquals("1 h 06 min", musicLength(66L * 60_000L * 10_000L, strings))
        assertNull(musicLength(null, strings))
    }

    @Test
    fun `album header omits a missing year`() {
        val summary = AlbumSummary(trackCount = 6uL, discCount = 2uL, runtimeTicks = 24L * 60_000L * 10_000L)
        assertEquals("2022 │ 6 TRACKS │ 24 MIN", albumHeaderLine(2022, summary, strings))
        assertEquals("6 TRACKS │ 24 MIN", albumHeaderLine(null, summary, strings))
    }

    @Test
    fun `empty copy per tab and per song list`() {
        assertEquals(R.string.music_empty_library, musicEmptyLine(MusicTab.Albums))
        assertEquals(R.string.music_empty_library, musicEmptyLine(MusicTab.Genres))
        assertEquals(R.string.music_empty_playlists, musicEmptyLine(MusicTab.Playlists))
        assertEquals(R.string.music_empty_favorites, songListEmptyLine(SongSource.Favorites("v")))
        assertEquals(R.string.music_empty_played, songListEmptyLine(SongSource.RecentlyPlayed("v")))
        assertEquals("No playlists yet.", strings.get(musicEmptyLine(MusicTab.Playlists)))
    }

    @Test
    fun `track lists reserve the known row count while loading, else six`() {
        assertEquals(6, skeletonRowCount(null))
        assertEquals(4, skeletonRowCount(4uL))
    }

    @Test
    fun `every layer reserves the bar's room while music plays, except Now Playing and the Queue`() {
        for (screen in listOf(Screen.Home, Screen.Search, Screen.Discover, Screen.Report, Screen.Settings, Screen.MusicAlbum("album-1"))) {
            assertEquals("$screen", MUSIC_BAR_HEIGHT, musicBarInset(hasSession = true, screen))
        }
        assertEquals(0.dp, musicBarInset(hasSession = true, Screen.MusicNowPlaying))
        assertEquals(0.dp, musicBarInset(hasSession = true, Screen.MusicQueue))
        assertEquals("no bar, no room", 0.dp, musicBarInset(hasSession = false, Screen.Home))
        // Android TV title-safe: the reserved room is the 56 dp controls row plus the 27 dp strip under it.
        assertEquals(27.dp, TV_SAFE_BOTTOM)
        assertEquals(56.dp + 27.dp, MUSIC_BAR_HEIGHT)
    }

    @Test
    fun `ids match whatever their dashes and case`() {
        assertEquals(true, sameItemId("0a1b2c3d-4e5f-4071-8293-a4b5c6d7e8f9", "0a1b2c3d-4e5f-4071-8293-a4b5c6d7e8f9".replace("-", "").uppercase()))
        assertEquals(false, sameItemId("0a1b", "0a1c"))
    }

    @Test
    fun `a removed row hands focus to the row that took its place, else the one before, else nothing`() {
        val before = listOf("a", "b", "c")
        assertEquals("b", keyAfterRemoval(before, "a", listOf("b", "c")))
        assertEquals("c", keyAfterRemoval(before, "b", listOf("a", "c")))
        assertEquals("the last row falls back to the one before", "b", keyAfterRemoval(before, "c", listOf("a", "b")))
        assertNull("an emptied list leaves the page's own fallback", keyAfterRemoval(listOf("a"), "a", emptyList()))
        assertEquals("a row the re-read also dropped is skipped", "a", keyAfterRemoval(before, "b", listOf("a")))
    }

    @Test
    fun `a song the server lists twice under one entry id still gets unique row keys`() {
        assertEquals(listOf("entry:a", "entry:b", "entry:a#2", "entry:a#3"), playlistRowKeys(listOf("a", "b", "a", "a")))
    }
}
