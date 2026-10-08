package tv.jellybeam.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.jellybeam_core.EmbeddedSubtitleFfi
import uniffi.jellybeam_core.SubtitleActionFfi
import uniffi.jellybeam_core.TrackDecisionFfi
import uniffi.jellybeam_core.TrackInfo
import uniffi.jellybeam_core.TrackKindFfi

/** docs/18 §3.1: what survives a reload, and how an embedded track is found again. */
class TrackReapplyTest {

    private fun sub(id: Long, lang: String?, title: String? = null, forced: Boolean = false, selected: Boolean = false) =
        TrackInfo(id = id, kind = TrackKindFfi.SUBTITLE, title = title, lang = lang, codec = "srt", isDefault = false, isSelected = selected, isForced = forced)

    private fun audio(id: Long, lang: String?, selected: Boolean = false) =
        TrackInfo(id = id, kind = TrackKindFfi.AUDIO, title = null, lang = lang, codec = "aac", isDefault = false, isSelected = selected, isForced = false)

    @Test
    fun `the choice before a reload is the recorded one unless undecided or a sidecar shows`() {
        val embedded = TextChoice.Embedded(sub(3, "en", "Full").identity())
        assertEquals(TextChoice.Auto, textChoiceBeforeReload(decided = false, sidecarActive = true, recorded = embedded))
        assertEquals(TextChoice.Sidecar, textChoiceBeforeReload(decided = true, sidecarActive = true, recorded = embedded))
        assertEquals(embedded, textChoiceBeforeReload(decided = true, sidecarActive = false, recorded = embedded))
        assertEquals(TextChoice.Off, textChoiceBeforeReload(decided = true, sidecarActive = false, recorded = TextChoice.Off))
        assertEquals(TextChoice.Leave, textChoiceBeforeReload(decided = true, sidecarActive = false, recorded = TextChoice.Leave))
    }

    @Test
    fun `language codes of either length compare equal`() {
        assertEquals(normalizedLanguage("eng"), normalizedLanguage("en"))
        assertEquals(normalizedLanguage("ger"), normalizedLanguage("de"))
        assertEquals(normalizedLanguage("fre"), normalizedLanguage("fra"))
        assertEquals(normalizedLanguage("chi"), normalizedLanguage("zh"))
        assertNull(normalizedLanguage("und"))
        assertNull(normalizedLanguage(" "))
        assertEquals("xx-unknown", normalizedLanguage("xx-unknown"))
    }

    @Test
    fun `an exact match wins over a same-language one`() {
        val wanted = sub(0, "eng", "SDH").identity()
        val tracks = listOf(sub(10, "en", "Full"), sub(11, "en", "SDH"))
        assertEquals(11L, matchTrack(wanted, TrackKindFfi.SUBTITLE, tracks)?.id)
    }

    @Test
    fun `a rewritten or dropped title still matches when the language is unique`() {
        val wanted = sub(0, "eng", "English (Full)").identity()
        assertEquals(10L, matchTrack(wanted, TrackKindFfi.SUBTITLE, listOf(sub(10, "en", null), sub(11, "fr")))?.id)
        assertEquals(10L, matchTrack(wanted, TrackKindFfi.SUBTITLE, listOf(sub(10, "en", "English"), sub(11, "fr")))?.id)
    }

    @Test
    fun `forced and full never stand in for each other`() {
        val forced = sub(0, "en", forced = true).identity()
        assertNull(matchTrack(forced, TrackKindFfi.SUBTITLE, listOf(sub(10, "en"))))
        val full = sub(0, "en").identity()
        assertNull(matchTrack(full, TrackKindFfi.SUBTITLE, listOf(sub(10, "en", forced = true))))
    }

    @Test
    fun `ambiguity is no match, never the first candidate`() {
        val wanted = sub(0, "en", "Commentary").identity()
        assertNull(matchTrack(wanted, TrackKindFfi.SUBTITLE, listOf(sub(10, "en", "Full"), sub(11, "en", "SDH"))))
        val untitled = sub(0, "en").identity()
        assertNull(matchTrack(untitled, TrackKindFfi.SUBTITLE, listOf(sub(10, "en"), sub(11, "en"))))
    }

    @Test
    fun `no track of the language is no match, and kinds never cross`() {
        val wanted = sub(0, "en").identity()
        assertNull(matchTrack(wanted, TrackKindFfi.SUBTITLE, listOf(sub(10, "fr"))))
        assertNull(matchTrack(wanted, TrackKindFfi.SUBTITLE, listOf(audio(10, "en"))))
        assertEquals(10L, matchTrack(audio(0, "eng").identity(), TrackKindFfi.AUDIO, listOf(audio(10, "en"), sub(11, "en")))?.id)
    }

    @Test
    fun `a negotiation names the chosen stream, none for Off or a sidecar, nothing when undecided`() {
        val streams = listOf(
            EmbeddedSubtitleFfi(index = 2, language = "eng", title = null, forced = false, default = false, codec = "ass"),
            EmbeddedSubtitleFfi(index = 3, language = "eng", title = "Signs", forced = true, default = false, codec = "ass"),
            EmbeddedSubtitleFfi(index = 4, language = "fre", title = null, forced = false, default = false, codec = "srt"),
        )
        assertNull(subtitleStreamIndexFor(TextChoice.Auto, streams))
        assertNull(subtitleStreamIndexFor(TextChoice.Leave, streams))
        assertEquals(NO_SUBTITLE_STREAM, subtitleStreamIndexFor(TextChoice.Off, streams))
        assertEquals(NO_SUBTITLE_STREAM, subtitleStreamIndexFor(TextChoice.Sidecar, streams))
        assertEquals(2, subtitleStreamIndexFor(TextChoice.Embedded(sub(0, "en", "English").identity()), streams))
        assertEquals(3, subtitleStreamIndexFor(TextChoice.Embedded(sub(0, "en", forced = true).identity()), streams))
        assertEquals(4, subtitleStreamIndexFor(TextChoice.Embedded(sub(0, "fr").identity()), streams))
        assertEquals(NO_SUBTITLE_STREAM, subtitleStreamIndexFor(TextChoice.Embedded(sub(0, "de").identity()), streams))
    }

    @Test
    fun `a decision naming a burn-in-only stream turns player text off and hands the stream on`() {
        val burnable = listOf(sub(burnableSubtitleId(3), "en", forced = true))
        val picks = TrackDecisionFfi(audioTrackId = 7, subtitleAction = SubtitleActionFfi.LEAVE, subtitleTrackId = burnableSubtitleId(3))
        val off = TrackDecisionFfi(audioTrackId = 7, subtitleAction = SubtitleActionFfi.OFF, subtitleTrackId = null)
        assertEquals(off to burnable.single(), routeBurnable(picks, burnable))
        val delivered = picks.copy(subtitleTrackId = 4)
        assertEquals(delivered to null, routeBurnable(delivered, burnable))
        assertEquals(off to null, routeBurnable(off, burnable))
    }

    @Test
    fun `the policy sees rows needing no renegotiation first`() {
        val delivered = sub(4, "en")
        val sidecar = sub(ExternalSubtitles.idFor(0), "en")
        val burned = sub(burnableSubtitleId(2), "eng", selected = true)
        val unburned = sub(burnableSubtitleId(3), "eng")
        assertEquals(listOf(delivered, burned, sidecar, unburned), policyTracks(listOf(delivered), listOf(sidecar), listOf(unburned, burned)))
    }
}
