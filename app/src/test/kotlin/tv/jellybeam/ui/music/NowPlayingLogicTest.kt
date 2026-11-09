package tv.jellybeam.ui.music

import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import tv.jellybeam.R
import tv.jellybeam.i18n.ResourceUiStrings
import tv.jellybeam.i18n.UsLocaleRule
import tv.jellybeam.player.music.MusicFlow
import tv.jellybeam.player.music.MusicNow
import tv.jellybeam.player.music.MusicSession
import tv.jellybeam.player.music.MusicSource
import tv.jellybeam.player.music.QueueEntry
import tv.jellybeam.player.music.QueueOrigin
import tv.jellybeam.player.music.RepeatMode
import tv.jellybeam.player.music.queueGroups
import tv.jellybeam.player.music.queueReorder
import tv.jellybeam.player.music.userCount
import tv.jellybeam.ui.cards.testCard
import uniffi.jellybeam_core.AudioStream
import uniffi.jellybeam_core.AudioTranscode
import uniffi.jellybeam_core.Lyrics
import uniffi.jellybeam_core.MixSeedFfi
import uniffi.jellybeam_core.SongSource
import uniffi.jellybeam_core.SyncedLyricLine

/** docs/29 §9 F/H: Now Playing's lines, the lyric line, and the Queue screen's rows and moves. */
class NowPlayingLogicTest {
    @get:Rule val usLocale = UsLocaleRule()

    private val strings = ResourceUiStrings.default

    private fun song(id: String, seconds: Long = 200) = testCard(id = id, itemType = "Audio", name = "Song $id").copy(
        runtimeTicks = seconds * 1_000L * 10_000L,
        album = "Synthetic Album",
        albumId = "album-1",
        albumArtist = "Album Artist",
        artists = listOf("Performer A"),
        productionYear = 2022,
        parentIndexNumber = 1,
        indexNumber = 2,
    )

    private val cards = listOf("a", "b", "c", "d", "e").associateWith { song(it, seconds = 120 + it[0].code.toLong() - 'a'.code) }

    private fun now(
        items: String = "abcde",
        origins: String = "sssss",
        current: Int = 1,
        playOrder: List<Int> = items.indices.toList(),
        shuffle: Boolean = false,
        repeat: RepeatMode = RepeatMode.Off,
        paused: Boolean = false,
        positionMs: Long = 61_000L,
    ) = MusicNow(
        queue = items.mapIndexed { i, c -> QueueEntry(c.toString(), origin(origins[i])) },
        playOrder = playOrder,
        current = current,
        isPlaying = !paused,
        positionMs = positionMs,
        durationMs = 120_900L,
        shuffle = shuffle,
        repeat = repeat,
        paused = paused,
    )

    private fun view(now: MusicNow, source: MusicSource = MusicSource.Album("album-1")) = MusicQueueView(now, source, loading = false)

    private fun model(now: MusicNow) = nowPlayingModel(view(now), cards::get, strings)

    // -- F1 lines --

    @Test
    fun `F1 names the song, its place on the album and the queue`() {
        val m = model(now())
        assertEquals("Song b", m.title)
        assertEquals("Performer A", m.artistLine)
        assertEquals("Album Artist", m.artistName)
        assertEquals("Synthetic Album · 2022 · Disc 1, track 2", m.meta)
        assertEquals("QUEUE │ 2 OF 5 │ 10 MIN", m.queueLine)
        assertEquals("Song c", m.upNext?.title)
        assertEquals("2:02", m.upNext?.duration)
        assertEquals(1, m.stripCurrent)
    }

    @Test
    fun `a track without a disc reads Track n and missing parts are left out`() {
        assertEquals("Track 3", trackMetaLine(testCard(itemType = "Audio").copy(indexNumber = 3), strings))
        assertEquals("", trackMetaLine(testCard(itemType = "Audio"), strings))
    }

    @Test
    fun `F3 shuffled names SHUFFLED in place of the length and the strip follows the play order`() {
        val m = model(now(current = 3, playOrder = listOf(2, 4, 3, 0, 1), shuffle = true))
        assertEquals("QUEUE │ 3 OF 5 │ SHUFFLED", m.queueLine)
        assertEquals(2, m.stripCurrent)
        assertEquals(listOf(122_000L, 124_000L, 123_000L, 120_000L, 121_000L), m.stripDurationsMs)
        assertEquals("Song a", m.upNext?.title)
    }

    @Test
    fun `up next is the same song on repeat one, wraps on repeat all, and is absent at the end`() {
        assertEquals(1, upNextEntry(now(repeat = RepeatMode.One)))
        assertEquals(0, upNextEntry(now(current = 4, repeat = RepeatMode.All)))
        assertNull(upNextEntry(now(current = 4)))
        assertNull(model(now(current = 4)).upNext)
    }

    @Test
    fun `every music time reads the server length, not the player's`() {
        // The player reports 120.9 s for a 121 s song: rows, the bar and Now Playing all show 2:01.
        val m = model(now())
        assertEquals(121_000L, m.durationMs)
        assertEquals("2:01", trackDurationLabel(cards.getValue("b")))
        val session = MusicSession(1, 1uL, MusicSource.Album("album-1"), now().queue, start = 1)
        val bar = musicBarModel(MusicFlow(MusicFlow.Phase.Active(session, g = 1, n = 0, net = MusicFlow.Net.Online)), now(), cards::get)!!
        assertEquals(121_000L, bar.durationMs)
        assertEquals(120_900L, trackDurationMs(null, playerMs = 120_900L))
    }

    // -- time row, seek, paused --

    @Test
    fun `the time row counts down and names when the track ends`() {
        val row = nowPlayingTimeRow(positionMs = 61_000L, durationMs = 145_000L, paused = false, nowMs = 1_000_000L)
        assertEquals("1:01", row.elapsed)
        assertEquals("−1:24", row.remaining)
        assertEquals(1_084_000L, row.endsAtMs)
    }

    @Test
    fun `F3 paused drops ENDS AT`() {
        val row = nowPlayingTimeRow(positionMs = 55_000L, durationMs = 303_000L, paused = true, nowMs = 1_000_000L)
        assertTrue(row.paused)
        assertNull(row.endsAtMs)
        assertEquals("−4:08", row.remaining)
        assertTrue(model(now(paused = true)).paused)
    }

    @Test
    fun `the strip seeks by the skip settings within the track`() {
        assertEquals(71_000L, stripSeekTarget(61_000L, 120_000L, forward = true, skipBackMs = 5_000L, skipForwardMs = 10_000L))
        assertEquals(56_000L, stripSeekTarget(61_000L, 120_000L, forward = false, skipBackMs = 5_000L, skipForwardMs = 10_000L))
        assertEquals(120_000L, stripSeekTarget(115_000L, 120_000L, forward = true, skipBackMs = 5_000L, skipForwardMs = 10_000L))
        assertEquals(0L, stripSeekTarget(2_000L, 120_000L, forward = false, skipBackMs = 5_000L, skipForwardMs = 10_000L))
    }

    // -- spec line, source --

    @Test
    fun `Direct Play reads the facts line and a transcode the caution line`() {
        assertEquals(SpecLine.Direct("DIRECT PLAY │ MP3"), specLineFor(AudioStream.DirectPlay("http://example.test/a", null), { "DIRECT PLAY │ MP3" }, { "x" }))
        val target = AudioTranscode(sourceCodec = "FLAC", targetCodec = "AAC", bitrateKbps = 128u, reason = "BITRATE LIMIT")
        assertEquals(SpecLine.Transcode("TRANSCODE"), specLineFor(AudioStream.Transcode("http://example.test/a", null, target), { "unused" }, { "TRANSCODE" }))
        assertNull("no facts, no line", specLineFor(AudioStream.DirectPlay("http://example.test/a", null), { "" }, { "x" }))
        assertNull(specLineFor(AudioStream.WouldTranscode("container"), { "x" }, { "x" }))
    }

    @Test
    fun `PLAYING FROM cases the label and keeps the name verbatim`() {
        assertEquals("PLAYING FROM │ Paper Satellites", playingFromLine(sourceName(MusicSource.Album("a"), "Paper Satellites"), strings))
        assertEquals("PLAYING FROM │ INSTANT MIX · Tidal Lights", playingFromLine(sourceName(MusicSource.Mix(MixSeedFfi.Album("a")), "Tidal Lights"), strings))
        assertEquals("PLAYING FROM │ INSTANT MIX", playingFromLine(sourceName(MusicSource.Mix(MixSeedFfi.Album("a")), null), strings))
        assertEquals("PLAYING FROM │ FAVORITE SONGS", playingFromLine(SourceName.Fixed(R.string.music_songs_favorites), strings))
        assertNull(playingFromLine(sourceName(MusicSource.Playlist("p"), null), strings))
        assertEquals(SourceLookup.Fixed(R.string.music_songs_favorites), sourceLookup(MusicSource.Songs(SongSource.Favorites("v"))))
        assertEquals(SourceLookup.Verbatim("Synthwave;Ambient"), sourceLookup(MusicSource.Songs(SongSource.Genre("v", "Synthwave;Ambient"))))
    }

    // -- lyrics --

    private val lines = listOf(1_000uL, 5_000uL, 9_000uL).mapIndexed { i, ms -> SyncedLyricLine(ms, "Line $i") }

    @Test
    fun `the current lyric line follows the position`() {
        assertEquals("before the first line", LyricCue(-1, 1L), lyricCue(lines, 999L))
        assertEquals(LyricCue(0, 4_000L), lyricCue(lines, 1_000L))
        assertEquals(LyricCue(0, 1L), lyricCue(lines, 4_999L))
        assertEquals(LyricCue(1, 4_000L), lyricCue(lines, 5_000L))
        assertEquals("after the last line nothing is due", LyricCue(2, null), lyricCue(lines, 600_000L))
        assertEquals(LyricCue(-1, null), lyricCue(emptyList(), 5_000L))
        assertEquals("a negative position reads as zero", LyricCue(-1, 1_000L), lyricCue(lines, -50L))
    }

    @Test
    fun `lines sharing a start time wake once, on the last of them`() {
        val doubled = listOf(1_000uL, 1_000uL, 3_000uL).mapIndexed { i, ms -> SyncedLyricLine(ms, "Line $i") }
        assertEquals(LyricCue(1, 2_000L), lyricCue(doubled, 1_000L))
    }

    @Test
    fun `no lyrics or an empty list hides the pill`() {
        assertEquals(LyricsState.Absent, lyricsStateOf(Lyrics.None))
        assertEquals(LyricsState.Absent, lyricsStateOf(Lyrics.Synced(emptyList())))
        assertEquals(LyricsState.Synced(lines), lyricsStateOf(Lyrics.Synced(lines)))
        assertEquals(LyricsState.Unsynced(listOf("plain")), lyricsStateOf(Lyrics.Unsynced(listOf("plain"))))
    }

    // -- Queue screen --

    private fun tracks(model: QueueScreenModel) = model.rows.filterIsInstance<QueueRow.Track>()

    private fun headers(model: QueueScreenModel) = model.rows.filterIsInstance<QueueRow.Header>().map { it.text }

    @Test
    fun `H1 lists Now playing, the user group with its tags, then the rest of the source`() {
        val q = queueScreenModel(now(origins = "ssnas", positionMs = 21_000L), SourceName.Named(SourceKind.Album, "Paper Satellites"), cards::get, strings)
        assertEquals(
            listOf("NOW PLAYING", "NEXT IN QUEUE · 2 · PLAYS BEFORE THE REST OF THE ALBUM", "NEXT FROM Paper Satellites · 1"),
            headers(q),
        )
        assertEquals(listOf(1, 2, 3, 4), tracks(q).map { it.index })
        assertEquals(listOf(null, QueueTag.PlayNext, QueueTag.Added, null), tracks(q).map { it.tag })
        assertEquals(listOf(true, false, false, false), tracks(q).map { it.isCurrent })
        assertEquals("4 SONGS │ 8 MIN LEFT", q.countLine)
        assertEquals("SHUFFLE │ OFF", q.shuffleLine)
        assertEquals("REPEAT │ OFF", q.repeatLine)
        assertTrue(q.hasFollowing)
    }

    @Test
    fun `H2 shuffled names the order, and Clear queue is absent when nothing follows`() {
        val shuffled = queueScreenModel(now(current = 0, playOrder = listOf(0, 3, 1, 4, 2), shuffle = true, repeat = RepeatMode.All), SourceName.Named(SourceKind.Album, "Paper Satellites"), cards::get, strings)
        assertEquals("NEXT FROM Paper Satellites · 4 · SHUFFLED ORDER", headers(shuffled).last())
        assertEquals(listOf(0, 3, 1, 4, 2), tracks(shuffled).map { it.index })
        assertEquals(listOf(0, 1, 2, 3, 4), tracks(shuffled).map { it.position })
        assertEquals("REPEAT │ ALL", shuffled.repeatLine)
        val last = queueScreenModel(now(current = 4), SourceName.Unknown, cards::get, strings)
        assertFalse(last.hasFollowing)
        assertEquals(listOf("NOW PLAYING"), headers(last))
    }

    private fun queueMenu(row: QueueRow.Track, onCurrentAccount: Boolean = true) = queueMenuTarget(cards.getValue(row.entry.item), row, onCurrentAccount)!!

    @Test
    fun `an open queue menu whose row starts playing offers only Go to album`() {
        val opened = tracks(queueScreenModel(now(current = 1), SourceName.Unknown, cards::get, strings)).first { it.index == 2 }
        val playing = queueScreenModel(now(current = 2), SourceName.Unknown, cards::get, strings)
        val live = liveQueueTarget(queueMenu(opened), opened.key, playing.rows, onCurrentAccount = true)
        assertEquals(MusicMenuContext.InQueue(2, 2, isCurrent = true, onCurrentAccount = true), live?.context)
        val actions = buildMusicMenu(strings, MusicMenuInput(live!!, isFavorite = false, isAdministrator = false)).rows.map { it.action }
        assertEquals(listOf(MusicMenuAction.GoToAlbum("album-1")), actions)
    }

    @Test
    fun `an open queue menu acts on its row where it is now, and closes once the row is gone`() {
        val opened = tracks(queueScreenModel(now(current = 0), SourceName.Unknown, cards::get, strings)).first { it.entry.item == "c" }
        val shifted = queueScreenModel(now(items = "abdce", current = 0), SourceName.Unknown, cards::get, strings)
        assertEquals(MusicMenuContext.InQueue(3, 3, isCurrent = false, onCurrentAccount = true), liveQueueTarget(queueMenu(opened), opened.key, shifted.rows, onCurrentAccount = true)?.context)
        val removed = queueScreenModel(now(items = "abde", current = 0), SourceName.Unknown, cards::get, strings)
        assertNull(liveQueueTarget(queueMenu(opened), opened.key, removed.rows, onCurrentAccount = true))
    }

    @Test
    fun `a parked session's playing row has no menu, and an open menu follows the account it is read on`() {
        val model = queueScreenModel(now(current = 1), SourceName.Unknown, cards::get, strings)
        val rows = model.rows
        val playing = tracks(model).first { it.isCurrent }
        val upcoming = tracks(model).first { it.index == 2 }
        assertNull(queueMenuTarget(cards.getValue(playing.entry.item), playing, onCurrentAccount = false))
        assertEquals(false, (queueMenuTarget(cards.getValue(upcoming.entry.item), upcoming, onCurrentAccount = false)?.context as MusicMenuContext.InQueue).onCurrentAccount)
        val opened = queueMenu(upcoming)
        assertEquals(false, (liveQueueTarget(opened, upcoming.key, rows, onCurrentAccount = false)?.context as MusicMenuContext.InQueue).onCurrentAccount)
    }

    @Test
    fun `a song queued twice keeps two rows`() {
        assertEquals(listOf("a#1", "b#1", "a#2"), entryKeys(listOf(QueueEntry("a", QueueOrigin.Source), QueueEntry("b", QueueOrigin.Source), QueueEntry("a", QueueOrigin.UserAdded))))
    }

    @Test
    fun `Repeat steps Off, All, One`() {
        assertEquals(listOf(RepeatMode.All, RepeatMode.One, RepeatMode.Off), RepeatMode.entries.map(::nextRepeat))
    }

    /**
     * Every drop of every upcoming row, shuffle on and off: the row lands at its drop position in play order,
     * the playing song stays current, and the user group stays a timeline run right after it that plays first.
     */
    @Test
    fun `every Queue move lands where it was dropped and keeps the user group next`() {
        val cases = listOf(
            now(items = "abcdefg", origins = "ssnasss", current = 1, playOrder = listOf(5, 1, 2, 3, 6, 0, 4), shuffle = true),
            now(items = "abcdefg", origins = "snnasss", current = 0, playOrder = listOf(0, 1, 2, 3, 5, 6, 4), shuffle = true),
            now(items = "abcdefg", origins = "ssnasss", current = 1),
        )
        var moves = 0
        cases.forEach { before ->
            val pc = before.playOrder.indexOf(before.current)
            for (from in pc + 1..before.playOrder.lastIndex) for (to in pc + 1..before.playOrder.lastIndex) {
                if (from == to) continue
                val move = queueReorder(before.queue.map { it.origin }, before.playOrder, before.current, before.shuffle, from, to)!!
                val after = applyReorder(before, move)
                val label = "move $from to $to in $before"
                assertEquals(label, before.queue[before.current].item, after.queue[after.current].item)
                assertEquals(label, before.queue[before.playOrder[from]].item, after.queue[after.playOrder[to]].item)
                assertEquals(label, pc, after.playOrder.indexOf(after.current))
                val origins = after.queue.map { it.origin }
                val users = userCount(after.current, origins)
                assertEquals(label, origins.count { it.isUser }, users)
                assertEquals(label, (after.current + 1..after.current + users).toList(), after.playOrder.drop(pc + 1).take(users))
                assertEquals(label, after.playOrder.drop(pc + 1), queueGroups(after.playOrder, after.current, origins).let { it.nextInQueue + it.nextFromSource })
                moves++
            }
        }
        assertEquals(5 * 4 + 6 * 5 + 5 * 4, moves)
    }

    @Test
    fun `the album eyebrow reads the core's dates`() {
        // The core writes RFC 3339 with an offset, not Z.
        assertEquals("Oct 1, 2026", mediumDate("2026-10-01T10:00:00.123456700+00:00", ZoneOffset.UTC))
        assertEquals("Oct 1, 2026", mediumDate("2026-10-01T10:00:00Z", ZoneOffset.UTC))
        assertNull(mediumDate("not a date", ZoneOffset.UTC))
    }

    private fun origin(c: Char) = when (c) {
        'n' -> QueueOrigin.UserNext
        'a' -> QueueOrigin.UserAdded
        else -> QueueOrigin.Source
    }
}
