package tv.jellybeam.ui.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.jellybeam.player.music.MusicFlow
import tv.jellybeam.player.music.MusicNow
import tv.jellybeam.player.music.MusicSession
import tv.jellybeam.player.music.MusicSource
import tv.jellybeam.player.music.QueueEntry
import tv.jellybeam.player.music.QueueOrigin
import tv.jellybeam.player.music.RepeatMode
import tv.jellybeam.ui.cards.ArtSource
import tv.jellybeam.ui.cards.testCard
import uniffi.jellybeam_core.ImageKind

class MusicFormatTest {

    private fun song(id: String, runtimeMs: Long? = 200_000L) = testCard(id = id, itemType = "Audio", name = "Song $id").copy(
        runtimeTicks = runtimeMs?.let { it * 10_000L },
        albumArtist = "Album Artist",
        artists = listOf("Performer A", "Performer B"),
    )

    private val cards = listOf(song("a"), song("b"), song("c")).associateBy { it.id }

    private fun session(start: Int = 0) =
        MusicSession(sid = 1, epoch = 1uL, source = MusicSource.Album("album"), items = listOf("a", "b", "c").map { QueueEntry(it, QueueOrigin.Source) }, start = start)

    private fun now(current: Int, playing: Boolean = true, positionMs: Long = 61_000L) = MusicNow(
        queue = listOf("a", "b", "c").map { QueueEntry(it, QueueOrigin.Source) },
        playOrder = listOf(0, 1, 2),
        current = current,
        isPlaying = playing,
        positionMs = positionMs,
        durationMs = 228_000L,
        shuffle = false,
        repeat = RepeatMode.Off,
    )

    // -- Bar presence (§3.4: only while music is playing or paused) --

    @Test
    fun `the bar is absent before any music owner exists`() {
        assertNull(musicBarModel(flow = null, now = null, card = cards::get))
    }

    @Test
    fun `the bar is absent while the owner is idle`() {
        assertNull(musicBarModel(MusicFlow(), now = null, card = cards::get))
    }

    @Test
    fun `a starting session shows its start track loading`() {
        val model = musicBarModel(MusicFlow(phase = MusicFlow.Phase.Starting(session(start = 1), ticket = 1)), now = null, card = cards::get)!!
        assertEquals("b", model.itemId)
        assertEquals("Song b", model.title)
        assertTrue(model.loading)
        assertEquals(false, model.isPlaying)
    }

    @Test
    fun `an active session shows the player's current track and position`() {
        val flow = MusicFlow(phase = MusicFlow.Phase.Active(session(), g = 1, n = 0, net = MusicFlow.Net.Online))
        val model = musicBarModel(flow, now(current = 2), cards::get)!!
        assertEquals("c", model.itemId)
        assertEquals("Performer A, Performer B", model.artist)
        assertEquals(61_000L, model.positionMs)
        assertEquals("the server length, as the rows show it", 200_000L, model.durationMs)
        assertEquals(false, model.loading)
        assertEquals(false, model.connectionLost)
    }

    @Test
    fun `a lost connection is carried from the session phase`() {
        val flow = MusicFlow(phase = MusicFlow.Phase.Active(session(), g = 1, n = 0, net = MusicFlow.Net.Lost))
        assertTrue(musicBarModel(flow, now(current = 0, playing = false), cards::get)!!.connectionLost)
    }

    // -- Line 2 (§3.4: connection lost stays until resume and beats a notice) --

    @Test
    fun `line two is the artist unless a notice or lost connection replaces it`() {
        val flow = MusicFlow(phase = MusicFlow.Phase.Active(session(), g = 1, n = 0, net = MusicFlow.Net.Online))
        val model = musicBarModel(flow, now(current = 0), cards::get)!!
        assertEquals(BarLine2.Artist("Performer A, Performer B"), musicBarLine2(model, notice = null))
        assertEquals(BarLine2.Notice("ADDED TO QUEUE │ Song b"), musicBarLine2(model, notice = "ADDED TO QUEUE │ Song b"))
        assertEquals(BarLine2.ConnectionLost, musicBarLine2(model.copy(connectionLost = true), notice = "ADDED TO QUEUE │ Song b"))
    }

    @Test
    fun `the artist line falls back to the album artist and keeps names verbatim`() {
        assertEquals("Various Artists", musicArtistLine(song("x").copy(artists = emptyList(), albumArtist = "Various Artists")))
        assertEquals("Synthwave;Ambient feat. Someone", musicArtistLine(song("x").copy(artists = listOf("Synthwave;Ambient feat. Someone"))))
        assertEquals("", musicArtistLine(song("x").copy(artists = emptyList(), albumArtist = null)))
    }

    // -- Times --

    @Test
    fun `track times are m colon ss, with hours only past an hour`() {
        assertEquals("0:00", formatTrackTime(0))
        assertEquals("0:09", formatTrackTime(9_999))
        assertEquals("3:48", formatTrackTime(228_000))
        assertEquals("59:59", formatTrackTime(3_599_000))
        assertEquals("1:02:05", formatTrackTime(3_725_000))
        assertEquals("0:00", formatTrackTime(-5_000))
    }

    @Test
    fun `the shown position runs on from the snapshot only while playing, capped at the duration`() {
        assertEquals(63_500L, musicPositionMs(positionMs = 61_000, durationMs = 228_000, isPlaying = true, elapsedMs = 2_500))
        assertEquals(61_000L, musicPositionMs(positionMs = 61_000, durationMs = 228_000, isPlaying = false, elapsedMs = 2_500))
        assertEquals(228_000L, musicPositionMs(positionMs = 227_000, durationMs = 228_000, isPlaying = true, elapsedMs = 5_000))
        // An unknown duration doesn't cap.
        assertEquals(66_000L, musicPositionMs(positionMs = 61_000, durationMs = 0, isPlaying = true, elapsedMs = 5_000))
    }

    @Test
    fun `a track ends at now plus what is left of it`() {
        assertEquals(1_000_000L + 167_000L, trackEndsAtMs(nowMs = 1_000_000, positionMs = 61_000, durationMs = 228_000))
    }

    @Test
    fun `a missing track number shows a dash`() {
        assertEquals("—", trackNumberLabel(null))
        assertEquals("7", trackNumberLabel(7))
    }

    // -- Art and initials (§3.3, S4) --

    @Test
    fun `an artist initial is the first letter of the sort name, else the name`() {
        assertEquals("Q", artistInitial(sortName = "Quiet Engines", name = "The Quiet Engines"))
        assertEquals("T", artistInitial(sortName = null, name = "The Quiet Engines"))
        assertEquals("T", artistInitial(sortName = "  ", name = "The Quiet Engines"))
        assertEquals("4", artistInitial(sortName = "(4) Lanterns", name = "Four Lanterns"))
        assertEquals("", artistInitial(sortName = null, name = "…"))
    }

    @Test
    fun `a track's art is its own, then its album's, then none`() {
        assertEquals(ArtSource.Own("t", "own", ImageKind.PRIMARY), trackArtSource(song("t").copy(primaryTag = "own", albumId = "al", albumPrimaryTag = "alt")))
        assertEquals(ArtSource.Fallback("al", "alt", ImageKind.PRIMARY), trackArtSource(song("t").copy(albumId = "al", albumPrimaryTag = "alt")))
        assertEquals(ArtSource.None, trackArtSource(song("t").copy(albumId = "al")))
    }

    // -- Queue strip (§F: widths by duration, min 10px, 40-track window) --

    private val noLabel: (Int) -> Float = { 0f }

    @Test
    fun `segments fill the strip in proportion to duration`() {
        val layout = queueStripLayout(listOf(100L, 300L), current = 0, widthPx = 404f, gapPx = 4f, minPx = 10f, labelPx = noLabel)
        assertEquals(listOf(100f, 300f), layout.segments.map { it.width })
        assertEquals(listOf(0f, 104f), layout.segments.map { it.x })
        assertEquals(listOf(SegmentKind.Current, SegmentKind.Future), layout.segments.map { it.kind })
        assertEquals(0, layout.hiddenBefore)
        assertEquals(0, layout.hiddenAfter)
    }

    @Test
    fun `a short track keeps the minimum width and the rest share what is left`() {
        val durations = listOf(1L, 1_000L, 1_000L)
        val layout = queueStripLayout(durations, current = 1, widthPx = 408f, gapPx = 4f, minPx = 10f, labelPx = noLabel)
        val widths = layout.segments.map { it.width }
        assertEquals(10f, widths[0], 0.001f)
        assertEquals(195f, widths[1], 0.001f)
        assertEquals(195f, widths[2], 0.001f)
        assertEquals(listOf(SegmentKind.Past, SegmentKind.Current, SegmentKind.Future), layout.segments.map { it.kind })
    }

    @Test
    fun `segment widths and gaps always sum to the strip and none is under the minimum`() {
        for (n in 1..80) {
            val durations = List(n) { i -> if (i % 7 == 0) null else (i * 37L % 400L) + 1L }
            for (current in listOf(0, n / 2, n - 1)) {
                val label: (Int) -> Float = { 30f }
                val layout = queueStripLayout(durations, current, widthPx = 920f, gapPx = 4f, minPx = 10f, labelPx = label)
                val segments = layout.segments
                val labels = (if (layout.hiddenBefore > 0) 30f + 4f else 0f) + (if (layout.hiddenAfter > 0) 30f + 4f else 0f)
                val total = segments.sumOf { it.width.toDouble() } + 4.0 * (segments.size - 1) + labels
                assertEquals("n=$n current=$current", 920.0, total, 0.01)
                assertTrue("n=$n current=$current", segments.all { it.width >= 10f - 0.001f })
                assertEquals("n=$n", n, segments.size + layout.hiddenBefore + layout.hiddenAfter)
                assertEquals("the current track is always drawn", 1, segments.count { it.kind == SegmentKind.Current })
                assertEquals(current, segments.single { it.kind == SegmentKind.Current }.index)
            }
        }
    }

    @Test
    fun `a queue too long for the minimum draws 40 tracks around the current one`() {
        val layout = queueStripLayout(List(100) { 200_000L }, current = 50, widthPx = 920f, gapPx = 4f, minPx = 10f, labelPx = { 30f })
        assertEquals(40, layout.segments.size)
        assertEquals(30, layout.hiddenBefore)
        assertEquals(30, layout.hiddenAfter)
        assertEquals(30, layout.segments.first().index)
    }

    @Test
    fun `the window stays inside the queue at its ends`() {
        val first = queueStripLayout(List(100) { 200_000L }, current = 2, widthPx = 920f, gapPx = 4f, minPx = 10f, labelPx = { 30f })
        assertEquals(0, first.hiddenBefore)
        assertEquals(60, first.hiddenAfter)
        val last = queueStripLayout(List(100) { 200_000L }, current = 99, widthPx = 920f, gapPx = 4f, minPx = 10f, labelPx = { 30f })
        assertEquals(60, last.hiddenBefore)
        assertEquals(0, last.hiddenAfter)
    }

    @Test
    fun `a narrow strip shrinks the window until the minimum fits`() {
        val layout = queueStripLayout(List(100) { 200_000L }, current = 50, widthPx = 300f, gapPx = 4f, minPx = 10f, labelPx = { 30f })
        // 300 less two labels and their gaps leaves 232: 17 segments of 10 with 16 gaps of 4 is 234, so 16.
        assertEquals(16, layout.segments.size)
        assertTrue(layout.segments.all { it.width >= 10f - 0.001f })
    }

    @Test
    fun `an empty queue draws nothing`() {
        val layout = queueStripLayout(emptyList(), current = 0, widthPx = 920f, gapPx = 4f, minPx = 10f, labelPx = noLabel)
        assertTrue(layout.segments.isEmpty())
    }
}
