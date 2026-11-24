package tv.jellybeam.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.jellybeam_core.AudioStreamFfi
import uniffi.jellybeam_core.EmbeddedSubtitleFfi
import uniffi.jellybeam_core.ExternalSubtitleFfi
import uniffi.jellybeam_core.PlayMethodFfi
import uniffi.jellybeam_core.PlaybackPlan
import uniffi.jellybeam_core.SubtitleDeliveryFfi
import uniffi.jellybeam_core.TrackInfo
import uniffi.jellybeam_core.TrackKindFfi
import uniffi.jellybeam_core.TrackKindFfi.AUDIO
import uniffi.jellybeam_core.TrackKindFfi.SUBTITLE

/** docs/31 §4.3, §10 "Track mapping": a server stream index to the picker row a remote SetAudio/SetSubtitle picks. */
class RowForStreamIndexTest {
    // Audio 0 (eng) and 1 (jpn); subtitles 2 (eng Signs), 3 (eng Full), 4 (jpn Notes); sidecar 6.
    private val audio = listOf(audioStream(0, "eng", "Main"), audioStream(1, "jpn", "Original"))
    private val sidecar = ExternalSubtitleFfi(index = 6, codec = "srt", language = "fre", displayTitle = "French", isDefault = false, isForced = false)

    /** Media3's tracks of the file: group ids 0..4, as [TrackMapping.toId] numbers them. */
    private val media3 = listOf(
        info(0, AUDIO, "Main", "eng"),
        info(1_000, AUDIO, "Original", "jpn"),
        info(2_000, SUBTITLE, "Signs", "eng"),
        info(3_000, SUBTITLE, "Full", "eng"),
        info(4_000, SUBTITLE, "Notes", "jpn"),
    )

    @Test
    fun `Direct Play maps an embedded stream to the player's track in its place`() {
        val (facts, rows) = directPlay(subtitles(SubtitleDeliveryFfi.EMBED), media3)
        assertEquals(3_000L, rowForStreamIndex(facts, rows, SUBTITLE, 3))
        assertEquals(2_000L, rowForStreamIndex(facts, rows, SUBTITLE, 2))
        assertEquals(1_000L, rowForStreamIndex(facts, rows, AUDIO, 1))
        assertEquals(0L, rowForStreamIndex(facts, rows, AUDIO, 0))
    }

    /** Two untitled English tracks match each other by identity; only their place in the file tells them apart. */
    @Test
    fun `Direct Play tells twin tracks apart by their place`() {
        val twins = listOf(stream(2, "eng", null, SubtitleDeliveryFfi.EMBED), stream(3, "eng", null, SubtitleDeliveryFfi.EMBED))
        val (facts, rows) = directPlay(twins, listOf(media3[0], info(2_000, SUBTITLE, null, "eng"), info(3_000, SUBTITLE, null, "eng")))
        assertEquals(3_000L, rowForStreamIndex(facts, rows, SUBTITLE, 3))
        assertEquals(2_000L, rowForStreamIndex(facts, rows, SUBTITLE, 2))
    }

    /** A wrong track is worse than none: tracks that don't line up with the server's streams map nothing. */
    @Test
    fun `Direct Play maps nothing when the player's tracks don't line up with the server's`() {
        val (facts, rows) = directPlay(subtitles(SubtitleDeliveryFfi.EMBED), media3.filter { it.id != 3_000L })
        assertNull(rowForStreamIndex(facts, rows, SUBTITLE, 3))
        assertNull(rowForStreamIndex(facts, rows, SUBTITLE, 2))
    }

    @Test
    fun `a transcode maps a stream to its rendition, or to the stream row a renegotiation shows`() {
        val embedded = listOf(
            stream(2, "eng", "Signs", SubtitleDeliveryFfi.ENCODE),
            stream(3, "eng", "Full", SubtitleDeliveryFfi.HLS),
            stream(4, "jpn", "Notes", SubtitleDeliveryFfi.HLS),
        )
        val plan = plan(PlayMethodFfi.TRANSCODE, embedded, coreTrackRows(audio, embedded, listOf(sidecar), delivered = 0, burned = null, renditions = true))
        val rows = trackRows(plan, listOf(info(303, SUBTITLE, "Full", "eng"), info(304, SUBTITLE, "Notes", "jpn")))
        val facts = plan.trackFacts()
        assertEquals(303L, rowForStreamIndex(facts, rows, SUBTITLE, 3))
        assertEquals(304L, rowForStreamIndex(facts, rows, SUBTITLE, 4))
        assertEquals(streamRowId(2), rowForStreamIndex(facts, rows, SUBTITLE, 2))
        assertEquals(streamRowId(1), rowForStreamIndex(facts, rows, AUDIO, 1))
        assertEquals(ExternalSubtitles.idFor(6), rowForStreamIndex(facts, rows, SUBTITLE, 6))
    }

    @Test
    fun `an external subtitle maps to its sidecar row`() {
        val (facts, rows) = directPlay(subtitles(SubtitleDeliveryFfi.EMBED), media3)
        assertEquals(ExternalSubtitles.idFor(6), rowForStreamIndex(facts, rows, SUBTITLE, 6))
    }

    @Test
    fun `subtitle index -1 is the Off row and means nothing for audio`() {
        val (facts, rows) = directPlay(subtitles(SubtitleDeliveryFfi.EMBED), media3)
        assertEquals(TRACK_PICKER_SUBTITLE_OFF_ID, rowForStreamIndex(facts, rows, SUBTITLE, -1))
        assertNull(rowForStreamIndex(facts, rows, AUDIO, -1))
    }

    @Test
    fun `a stream no row of that kind shows maps to nothing`() {
        val (facts, rows) = directPlay(subtitles(SubtitleDeliveryFfi.EMBED), media3)
        assertNull(rowForStreamIndex(facts, rows, SUBTITLE, 9))
        assertNull(rowForStreamIndex(facts, rows, AUDIO, 9))
        assertNull("a sidecar is no audio", rowForStreamIndex(facts, rows, AUDIO, 6))
        assertNull("an audio stream is no subtitle", rowForStreamIndex(facts, rows, SUBTITLE, 0))
    }

    /** A Direct Play plan of [embedded] and the rows on screen over Media3's [tracks]. */
    private fun directPlay(embedded: List<EmbeddedSubtitleFfi>, tracks: List<TrackInfo>): Pair<PlanFacts, List<TrackInfo>> {
        val plan = plan(PlayMethodFfi.DIRECT_PLAY, embedded, coreSidecarRows(listOf(sidecar)))
        return plan.trackFacts() to trackRows(plan, tracks)
    }

    private fun subtitles(delivery: SubtitleDeliveryFfi) = listOf(
        stream(2, "eng", "Signs", delivery),
        stream(3, "eng", "Full", delivery),
        stream(4, "jpn", "Notes", delivery),
    )

    private fun plan(method: PlayMethodFfi, embedded: List<EmbeddedSubtitleFfi>, rows: List<uniffi.jellybeam_core.TrackRowFfi>) = PlaybackPlan(
        itemId = "item-1",
        itemName = "Sample",
        url = "http://example.test/videos/1",
        mediaSourceId = "src-1",
        playSessionId = "session-1",
        playMethod = method,
        transcodeReason = null,
        serverVerdict = null,
        transcodeFallbackAllowed = true,
        startPositionTicks = 0L,
        runtimeTicks = null,
        container = "mkv",
        itemType = "Movie",
        seriesName = null,
        parentIndexNumber = null,
        indexNumber = null,
        seriesId = null,
        trackMemorySubject = "item-1",
        externalSubtitles = listOf(sidecar),
        embeddedSubtitles = embedded,
        burnedSubtitleIndex = null,
        audioStreams = audio,
        deliveredAudioIndex = if (method == PlayMethodFfi.TRANSCODE) 0 else null,
        policyDecision = null,
        trackRows = rows,
    )

    private companion object {
        fun audioStream(index: Int, lang: String, title: String) =
            AudioStreamFfi(index = index, displayTitle = title, title = title, language = lang, codec = "aac", channels = 2, isDefault = index == 0)

        fun stream(index: Int, lang: String, title: String?, delivery: SubtitleDeliveryFfi) =
            EmbeddedSubtitleFfi(index = index, language = lang, title = title, forced = false, default = false, codec = "subrip", displayTitle = title ?: lang, delivery = delivery)

        fun info(id: Long, kind: TrackKindFfi, title: String?, lang: String) =
            TrackInfo(id = id, kind = kind, title = title, lang = lang, codec = null, isDefault = false, isSelected = false, isForced = false, isHearingImpaired = false)
    }
}
