package tv.jellybeam.player

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.jellybeam_core.ExternalSubtitleFfi
import uniffi.jellybeam_core.SubtitleActionFfi
import uniffi.jellybeam_core.TrackDecisionFfi
import uniffi.jellybeam_core.TrackKindFfi

class ExternalSubtitlesTest {
    private fun sub(index: Int, codec: String = "srt", isDefault: Boolean = false, isForced: Boolean = false, language: String = "eng") =
        ExternalSubtitleFfi(index = index, codec = codec, language = language, displayTitle = null, isDefault = isDefault, isForced = isForced)

    private fun decision(action: SubtitleActionFfi = SubtitleActionFfi.LEAVE, id: Long? = null) =
        TrackDecisionFfi(audioTrackId = 4L, subtitleAction = action, subtitleTrackId = id)

    @Test
    fun `sidecar text codecs map to a Media3 mime, bitmaps and ASS do not`() {
        assertEquals(MimeTypes.APPLICATION_SUBRIP, ExternalSubtitles.mimeFor("SubRip"))
        assertEquals(MimeTypes.TEXT_VTT, ExternalSubtitles.mimeFor("webvtt"))
        assertNull(ExternalSubtitles.mimeFor("ass"))
        assertEquals(MimeTypes.APPLICATION_TTML, ExternalSubtitles.mimeFor("ttml"))
        assertNull(ExternalSubtitles.mimeFor("pgssub"))
        assertNull(ExternalSubtitles.mimeFor(""))
    }

    @Test
    fun `sidecar rows carry the language as Media3 normalizes an embedded track's`() {
        assertEquals("en", ExternalSubtitles.trackInfos(listOf(sub(2, language = "eng")), activeIndex = null).single().lang)
    }

    @Test
    fun `synthetic ids round-trip and never decode an embedded id`() {
        assertEquals(7, ExternalSubtitles.indexOf(ExternalSubtitles.idFor(7)))
        assertNull(ExternalSubtitles.indexOf(TrackMapping.toId(groupIndex = 999, trackIndex = 999)))
        assertNull(ExternalSubtitles.indexOf(null))
        assertNull(ExternalSubtitles.indexOf(TRACK_PICKER_SUBTITLE_OFF_ID))
    }

    @Test
    fun `track infos skip unparseable codecs and mark only the active sidecar`() {
        val infos = ExternalSubtitles.trackInfos(listOf(sub(2), sub(3, codec = "pgssub"), sub(4, codec = "vtt")), activeIndex = 4)
        assertEquals(listOf(ExternalSubtitles.idFor(2), ExternalSubtitles.idFor(4)), infos.map { it.id })
        assertEquals(listOf(false, true), infos.map { it.isSelected })
        assertEquals(setOf(TrackKindFfi.SUBTITLE), infos.map { it.kind }.toSet())
    }

    @Test
    fun `a decision for a sidecar turns embedded text off and hands the sidecar over`() {
        val (player, sidecar) = ExternalSubtitles.route(decision(id = ExternalSubtitles.idFor(3)), listOf(sub(3)), embeddedTextSelected = true, audioLanguage = "eng")
        assertEquals(decision(action = SubtitleActionFfi.OFF), player)
        assertEquals(3, sidecar)
    }

    @Test
    fun `an embedded or off decision passes through untouched`() {
        val embedded = decision(id = 2_000L)
        assertEquals(embedded to null, ExternalSubtitles.route(embedded, listOf(sub(3, isDefault = true)), embeddedTextSelected = false, audioLanguage = "eng"))
        val off = decision(action = SubtitleActionFfi.OFF)
        assertEquals(off to null, ExternalSubtitles.route(off, listOf(sub(3, isDefault = true)), embeddedTextSelected = false, audioLanguage = "eng"))
    }

    @Test
    fun `left alone, a default sidecar shows only when no embedded text did`() {
        val subs = listOf(sub(2), sub(3, isDefault = true))
        assertEquals(decision() to 3, ExternalSubtitles.route(decision(), subs, embeddedTextSelected = false, audioLanguage = "eng"))
        assertEquals(decision() to null, ExternalSubtitles.route(decision(), subs, embeddedTextSelected = true, audioLanguage = "eng"))
        assertEquals(decision() to null, ExternalSubtitles.route(decision(), listOf(sub(2)), embeddedTextSelected = false, audioLanguage = "eng"))
    }

    @Test
    fun `left alone, a forced sidecar in the audio language shows, matching across code forms`() {
        val subs = listOf(sub(2), sub(3, isForced = true, language = "fra"), sub(4, isForced = true))
        assertEquals(decision() to 4, ExternalSubtitles.route(decision(), subs, embeddedTextSelected = false, audioLanguage = "en"))
        assertEquals(decision() to 3, ExternalSubtitles.route(decision(), subs, embeddedTextSelected = false, audioLanguage = "fr"))
        assertEquals(decision() to null, ExternalSubtitles.route(decision(), subs, embeddedTextSelected = false, audioLanguage = null))
        assertEquals(decision() to null, ExternalSubtitles.route(decision(), subs, embeddedTextSelected = true, audioLanguage = "eng"))
    }

    @Test
    fun `a default sidecar outranks a forced one`() {
        val subs = listOf(sub(3, isForced = true), sub(5, isDefault = true, language = "fra"))
        assertEquals(decision() to 5, ExternalSubtitles.route(decision(), subs, embeddedTextSelected = false, audioLanguage = "eng"))
    }


    @Test
    fun `languages match across code forms and regions, as Media3 matches them`() {
        assertEquals(true, ExternalSubtitles.languagesMatch("eng", "en-US"))
        assertEquals(true, ExternalSubtitles.languagesMatch("en-us", "eng"))
        assertEquals(true, ExternalSubtitles.languagesMatch("fra", "fr"))
        assertEquals(false, ExternalSubtitles.languagesMatch("fra", "en"))
        assertEquals(false, ExternalSubtitles.languagesMatch(null, "en"))
        assertEquals(false, ExternalSubtitles.languagesMatch("en", " "))
    }

    @Test
    fun `a forced sidecar tagged eng shows under en-US audio`() {
        val subs = listOf(sub(4, isForced = true))
        assertEquals(decision() to 4, ExternalSubtitles.route(decision(), subs, embeddedTextSelected = false, audioLanguage = "en-US"))
    }


    @Test
    fun `a refusal mid-swap waits for the new session, and only a real failure fails`() {
        fun outcome(loaded: Boolean = false, chosen: Boolean = true, replaced: Boolean = false, pending: Boolean = false) =
            ExternalSubtitles.fetchOutcome(loaded, chosen, replaced, pending)
        assertEquals(ExternalSubtitles.FetchOutcome.LOADED, outcome(loaded = true, pending = true))
        assertEquals(ExternalSubtitles.FetchOutcome.RETRY_UNDER_NEW_SESSION, outcome(replaced = true))
        assertEquals(ExternalSubtitles.FetchOutcome.AWAIT_NEW_SESSION, outcome(pending = true))
        assertEquals(ExternalSubtitles.FetchOutcome.FAILED, outcome())
        assertEquals("an abandoned pick never waits", ExternalSubtitles.FetchOutcome.FAILED, outcome(chosen = false, pending = true))
    }
}
