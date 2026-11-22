package tv.jellybeam.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.jellybeam_core.AudioStreamFfi
import uniffi.jellybeam_core.EmbeddedSubtitleFfi
import uniffi.jellybeam_core.TrackInfo
import uniffi.jellybeam_core.TrackKindFfi

/** docs/18 §3.1: what survives a reload, and how an embedded track is found again. */
class TrackReapplyTest {

    private fun sub(id: Long, lang: String?, title: String? = null, forced: Boolean = false, selected: Boolean = false, sdh: Boolean = false) =
        TrackInfo(id = id, kind = TrackKindFfi.SUBTITLE, title = title, lang = lang, codec = "srt", isDefault = false, isSelected = selected, isForced = forced, isHearingImpaired = sdh)

    private fun audio(id: Long, lang: String?, selected: Boolean = false) =
        TrackInfo(id = id, kind = TrackKindFfi.AUDIO, title = null, lang = lang, codec = "aac", isDefault = false, isSelected = selected, isForced = false, isHearingImpaired = false)

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
    fun `matching tries title and SDH, then title, then SDH, then language and forced only`() {
        val twinsSdh = listOf(sub(10, "en", "English"), sub(11, "en", "English", sdh = true))
        assertEquals("exact title and SDH", 11L, matchTrack(sub(0, "en", "English", sdh = true).identity(), TrackKindFfi.SUBTITLE, twinsSdh)?.id)
        assertEquals(10L, matchTrack(sub(0, "en", "English").identity(), TrackKindFfi.SUBTITLE, twinsSdh)?.id)
        val retitled = listOf(sub(10, "en", "Full"), sub(11, "en", "Hearing", sdh = true))
        assertEquals("title gone, SDH tells them apart", 11L, matchTrack(sub(0, "en", "English", sdh = true).identity(), TrackKindFfi.SUBTITLE, retitled)?.id)
        assertEquals(10L, matchTrack(sub(0, "en", "English").identity(), TrackKindFfi.SUBTITLE, retitled)?.id)
        assertEquals("SDH flag lost, the unique language still matches", 10L, matchTrack(sub(0, "en", "English", sdh = true).identity(), TrackKindFfi.SUBTITLE, listOf(sub(10, "en", "English"), sub(11, "fr")))?.id)
        assertEquals("an unpaired Direct Play pick knows no SDH, its title still finds it", 11L, matchTrack(sub(0, "en", "English SDH").identity(), TrackKindFfi.SUBTITLE, listOf(sub(10, "en", "English"), sub(11, "en", "English SDH", sdh = true)))?.id)
        assertNull("true duplicates stay ambiguous", matchTrack(sub(0, "en", "English", sdh = true).identity(), TrackKindFfi.SUBTITLE, listOf(sub(10, "en", "English", sdh = true), sub(11, "en", "English", sdh = true))))
        assertNull("same title, no SDH flag wanted, two plain twins", matchTrack(sub(0, "en", "English").identity(), TrackKindFfi.SUBTITLE, listOf(sub(10, "en", "English"), sub(11, "en", "English"), sub(12, "en", "English", sdh = true))))
    }

    @Test
    fun `a negotiation keeps SDH and plain apart when their titles match`() {
        val streams = listOf(
            EmbeddedSubtitleFfi(index = 2, language = "eng", title = "English", forced = false, default = false, codec = "subrip"),
            EmbeddedSubtitleFfi(index = 3, language = "eng", title = "English", forced = false, default = false, codec = "subrip", hearingImpaired = true),
        )
        assertEquals(3, subtitleStreamIndexFor(TextChoice.Embedded(sub(0, "en", "English", sdh = true).identity()), streams))
        assertEquals(2, subtitleStreamIndexFor(TextChoice.Embedded(sub(0, "en", "English").identity()), streams))
        val duplicates = streams.map { it.copy(hearingImpaired = false) }
        assertEquals("true duplicates name none", NO_SUBTITLE_STREAM, subtitleStreamIndexFor(TextChoice.Embedded(sub(0, "en", "English").identity()), duplicates))
    }

    @Test
    fun `a negotiation names the chosen stream, none for Off or a sidecar, nothing for the default`() {
        val streams = listOf(
            EmbeddedSubtitleFfi(index = 2, language = "eng", title = null, forced = false, default = false, codec = "ass"),
            EmbeddedSubtitleFfi(index = 3, language = "eng", title = "Signs", forced = true, default = false, codec = "ass"),
            EmbeddedSubtitleFfi(index = 4, language = "fre", title = null, forced = false, default = false, codec = "srt"),
        )
        assertNull(subtitleStreamIndexFor(TextChoice.Leave, streams))
        assertEquals(NO_SUBTITLE_STREAM, subtitleStreamIndexFor(TextChoice.Off, streams))
        assertEquals(NO_SUBTITLE_STREAM, subtitleStreamIndexFor(TextChoice.Sidecar, streams))
        assertEquals(2, subtitleStreamIndexFor(TextChoice.Embedded(sub(0, "en", "English").identity()), streams))
        assertEquals(3, subtitleStreamIndexFor(TextChoice.Embedded(sub(0, "en", forced = true).identity()), streams))
        assertEquals(4, subtitleStreamIndexFor(TextChoice.Embedded(sub(0, "fr").identity()), streams))
        assertEquals(NO_SUBTITLE_STREAM, subtitleStreamIndexFor(TextChoice.Embedded(sub(0, "de").identity()), streams))
    }

    @Test
    fun `a stream row's choice names its own stream where identity alone is ambiguous`() {
        val streams = listOf(
            EmbeddedSubtitleFfi(index = 2, language = "eng", title = null, forced = false, default = true, codec = "ass"),
            EmbeddedSubtitleFfi(index = 3, language = "eng", title = null, forced = false, default = false, codec = "subrip"),
        )
        val untitled = sub(0, "eng").identity()
        assertEquals("identity alone names none", NO_SUBTITLE_STREAM, subtitleStreamIndexFor(TextChoice.Embedded(untitled), streams))
        assertEquals(3, subtitleStreamIndexFor(TextChoice.Embedded(untitled, streamIndex = 3), streams))
        val renamed = TextChoice.Embedded(sub(0, "fr").identity(), streamIndex = 3)
        assertEquals("a stream that no longer carries the identity is not named", NO_SUBTITLE_STREAM, subtitleStreamIndexFor(renamed, streams))
    }

    // -- docs/18 §3.1: the negotiation carries the audio choice ----------------------------------

    private val mainAudio = AudioStreamFfi(index = 1, displayTitle = "English - AAC - Stereo - Default", language = "eng", codec = "aac", channels = 2, isDefault = true)
    private val commentary = AudioStreamFfi(index = 3, displayTitle = "Commentary - English - AAC - Stereo", title = "Commentary", language = "eng", codec = "aac", channels = 2, isDefault = false)
    private val japanese = AudioStreamFfi(index = 4, displayTitle = null, language = "jpn", codec = "ac3", channels = 6, isDefault = false)
    private val audioStreams = listOf(mainAudio, commentary, japanese)

    @Test
    fun `an audio negotiation names the picked stream while it keeps its identity, else the unique match, else the server's default`() {
        val picked = streamAudioChoice(commentary)
        assertEquals("a row's stream, where language alone is ambiguous", 3, audioStreamIndexFor(picked, audioStreams))
        assertNull("undecided leaves the server's default", audioStreamIndexFor(null, audioStreams))
        assertEquals("a player track's language when it is unique", 4, audioStreamIndexFor(AudioChoice(audio(0, "ja").identity()), audioStreams))
        assertEquals("an untitled player track names its language's untitled stream", 1, audioStreamIndexFor(AudioChoice(audio(0, "en").identity()), audioStreams))
        assertNull("a missing language names none", audioStreamIndexFor(AudioChoice(audio(0, "de").identity()), audioStreams))
        val twins = listOf(5, 6).map { AudioStreamFfi(index = it, displayTitle = null, language = "eng", codec = "aac", channels = 2, isDefault = false) }
        assertNull("an ambiguous language names none", audioStreamIndexFor(AudioChoice(audio(0, "en").identity()), twins))
        val second = streamAudioChoice(twins[1])
        assertEquals("only the row's own index tells unlabelled twins apart", 6, audioStreamIndexFor(second, twins))
        val moved = AudioChoice(TrackIdentity("fra", null, forced = false, sdh = false), streamIndex = 3)
        assertNull("a stream that no longer carries the identity is not named", audioStreamIndexFor(moved, audioStreams))
    }

    @Test
    fun `a Direct Play pick of a titled twin names that stream when a fallback transcodes`() {
        val playerCommentary = audio(0, "eng").copy(title = "Commentary")
        assertEquals(3, audioStreamIndexFor(AudioChoice(playerCommentary.identity()), audioStreams))
    }

    @Test
    fun `a transcode's player audio records the stream it delivers, Direct Play's the track itself`() {
        val track = audio(0, "eng")
        assertEquals(AudioChoice(track.identity()), audioChoiceOf(track, delivered = null))
        val delivered = audioChoiceOf(track, delivered = commentary)
        assertEquals(3, delivered.streamIndex)
        assertEquals("it names the delivered stream again", 3, audioStreamIndexFor(delivered, audioStreams))
    }

    @Test
    fun `picking the delivered audio stream changes nothing, a group refuses, any other pick renegotiates`() {
        assertEquals(AudioStreamPick.UNCHANGED, audioStreamPick(picked = 1, delivered = 1, negotiating = false, inGroup = false))
        assertEquals(AudioStreamPick.UNCHANGED, audioStreamPick(picked = 1, delivered = 1, negotiating = false, inGroup = true))
        assertEquals(AudioStreamPick.RENEGOTIATE, audioStreamPick(picked = 3, delivered = 1, negotiating = false, inGroup = false))
        assertEquals(AudioStreamPick.RENEGOTIATE, audioStreamPick(picked = 3, delivered = null, negotiating = false, inGroup = false))
        assertEquals("the landing in flight re-checks it", AudioStreamPick.RENEGOTIATE, audioStreamPick(picked = 1, delivered = 1, negotiating = true, inGroup = false))
        assertEquals(AudioStreamPick.REFUSED, audioStreamPick(picked = 3, delivered = 1, negotiating = false, inGroup = true))
    }
}
