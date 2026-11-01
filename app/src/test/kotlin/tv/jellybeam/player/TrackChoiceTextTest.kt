package tv.jellybeam.player

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.jellybeam.i18n.ResourceUiStrings
import uniffi.jellybeam_core.TrackInfo
import uniffi.jellybeam_core.TrackKindFfi

class TrackChoiceTextTest {
    private val strings = ResourceUiStrings.default
    private val us = Locale.US

    private fun info(
        title: String?,
        lang: String?,
        codec: String?,
        kind: TrackKindFfi = TrackKindFfi.SUBTITLE,
        forced: Boolean = false,
    ) = TrackInfo(
        id = 1L, kind = kind, title = title, lang = lang, codec = codec,
        isDefault = false, isSelected = false, isForced = forced,
    )

    private fun label(info: TrackInfo, index: Int = 0) = TrackChoiceText.label(strings, info, index, us)

    private fun meta(
        info: TrackInfo,
        external: Boolean = false,
        status: SidecarStatus? = null,
        facts: SubtitleFacts? = null,
    ) = TrackChoiceText.meta(strings, info, external, status, facts, us)

    @Test
    fun `a title shows verbatim, else the language name, else Track N`() {
        assertEquals("Commentary", label(info("Commentary", "en", null)))
        assertEquals("English", label(info("", "en", null)))
        assertEquals("English", label(info("  ", "en", null)))
        assertEquals("Track 3", label(info("", "", null), 2))
    }

    @Test
    fun `three-letter and bibliographic codes name their language`() {
        assertEquals("English", label(info(null, "eng", null)))
        assertEquals("Chinese", label(info(null, "zho", null)))
        assertEquals("Chinese", label(info(null, "chi", null)))
        assertEquals("Portuguese", label(info(null, "por", null)))
        assertEquals("French", label(info(null, "fre", null)))
        assertEquals("German", label(info(null, "ger", null)))
    }

    @Test
    fun `an undetermined or unresolvable language never shows as a name`() {
        assertEquals("Track 1", label(info(null, "und", null)))
        assertEquals("Track 1", label(info(null, " ", null)))
        assertEquals("Track 1", label(info(null, null, null)))
        assertEquals("xx", label(info(null, "xx", null)))
        assertEquals("qaa", label(info(null, "qaa", null)))
    }

    @Test
    fun `a titled row adds the language to its meta and an untitled row does not repeat it`() {
        assertEquals("English · SRT", meta(info("Commentary", "en", "application/x-subrip")))
        assertEquals("SRT", meta(info(null, "en", "application/x-subrip")))
        assertEquals("SRT", meta(info("", "en", "srt")))
        assertEquals("Dolby Digital", meta(info(null, "eng", "audio/ac3", TrackKindFfi.AUDIO)))
    }

    @Test
    fun `meta runs language, format, Forced, SDH in that order`() {
        val facts = SubtitleFacts(hearingImpaired = true, forced = false, codec = null)
        assertEquals(
            "Portuguese · SRT · Forced · SDH",
            meta(info("Brasileiro", "por", "application/x-subrip", forced = true), facts = facts),
        )
        assertEquals("SDH", meta(info(null, "por", null), facts = facts))
        assertEquals("Forced", meta(info(null, "por", null, forced = true)))
        assertEquals("Forced", meta(info(null, "por", null), facts = SubtitleFacts(hearingImpaired = false, forced = true, codec = null)))
    }

    @Test
    fun `a sidecar row ends with External then its fetch state`() {
        val srt = info(null, "en", "srt")
        assertEquals("SRT · External", meta(srt, external = true))
        assertEquals("SRT · External · Loading…", meta(srt, external = true, status = SidecarStatus.LOADING))
        assertEquals("External", meta(info(null, null, null), external = true))
        assertEquals("External · Unavailable", meta(info(null, null, null), external = true, status = SidecarStatus.UNAVAILABLE))
        val facts = SubtitleFacts(hearingImpaired = true, forced = false, codec = null)
        assertEquals(
            "English · SRT · Forced · SDH · External · Loading…",
            meta(info("Full", "en", "srt", forced = true), external = true, status = SidecarStatus.LOADING, facts = facts),
        )
    }

    @Test
    fun `a row with nothing to say has no meta`() {
        assertNull(meta(info(null, null, null)))
        assertNull(meta(info(null, "und", " ")))
    }

    @Test
    fun `an unknown format shows as the server or player wrote it`() {
        assertEquals("application/x-media3-cues", meta(info(null, null, "application/x-media3-cues")))
        assertEquals("English · eia_608", meta(info("Hi", "en", "eia_608")))
        assertEquals("Atmosphere", meta(info(null, null, "Atmosphere", TrackKindFfi.AUDIO)))
    }

    @Test
    fun `the server codec decides the format when it is known`() {
        val ssa = SubtitleFacts(hearingImpaired = false, forced = false, codec = "ssa")
        assertEquals("SSA", meta(info(null, "en", "text/x-ssa"), facts = ssa))
        assertEquals("ASS", meta(info(null, "en", "text/x-ssa")))
        assertEquals("ASS", meta(info(null, "en", "text/x-ssa"), facts = SubtitleFacts(false, false, codec = null)))
        assertEquals("ASS", meta(info(null, "en", "text/x-ssa"), facts = SubtitleFacts(false, false, codec = " ")))
    }

    @Test
    fun `every subtitle format row maps and any other is unknown`() {
        val rows = mapOf(
            "SRT" to listOf("application/x-subrip", "subrip", "srt"),
                        "SSA" to listOf("application/x-ssa", "ssa"),
            "ASS" to listOf("text/x-ssa", "application/x-ass", "ass"),
            "PGS" to listOf("application/pgs", "application/x-pgs", "hdmv_pgs_subtitle", "pgssub"),
            "VobSub" to listOf("application/vobsub", "application/x-vobsub", "dvd_subtitle", "dvdsub"),
            "VTT" to listOf("text/vtt", "webvtt", "vtt", "application/x-mp4-vtt", "wvtt"),
            "TTML" to listOf("application/ttml+xml", "ttml"),
            "MOV Text" to listOf("application/x-quicktime-tx3g", "tx3g", "mov_text"),
            "DVB" to listOf("application/dvbsubs", "dvbsub", "dvb_subtitle"),
            "CC 608" to listOf("application/cea-608", "application/x-mp4-cea-608"),
            "CC 708" to listOf("application/cea-708"),
        )
        for ((name, codecs) in rows) for (codec in codecs) {
            assertEquals(codec, name, TrackChoiceText.subtitleFormat(codec))
            assertEquals("$codec in upper case", name, TrackChoiceText.subtitleFormat(codec.uppercase()))
        }
        assertNull(TrackChoiceText.subtitleFormat("application/x-media3-cues"))
        assertNull(TrackChoiceText.subtitleFormat("audio/ac3"))
        assertNull(TrackChoiceText.subtitleFormat(""))
        assertNull(TrackChoiceText.subtitleFormat(null))
    }

    @Test
    fun `every audio format row maps, with the AAC and PCM families by prefix`() {
        val rows = mapOf(
            "Dolby Digital" to listOf("audio/ac3", "ac-3", "ac3"),
            "Dolby Digital Plus" to listOf("audio/eac3", "ec-3", "eac3"),
            "Dolby Atmos" to listOf("audio/eac3-joc", "ec+3"),
            "Dolby TrueHD" to listOf("audio/true-hd", "truehd"),
            "DTS" to listOf("audio/vnd.dts", "dts", "dca", "dtsc"),
            "DTS-HD" to listOf("audio/vnd.dts.hd", "dtsh", "dtsl"),
            "DTS Express" to listOf("audio/vnd.dts.hd;profile=lbr", "dtse"),
            "DTS:X" to listOf("audio/vnd.dts.uhd;profile=p2", "dtsx"),
            "AAC" to listOf("audio/mp4a-latm", "aac", "mp4a.40.2", "mp4a.40.5", "MP4A.40.29"),
            "FLAC" to listOf("audio/flac", "flac"),
            "Opus" to listOf("audio/opus", "opus"),
            "Vorbis" to listOf("audio/vorbis", "vorbis"),
            "MP3" to listOf("audio/mpeg", "mp3"),
            "ALAC" to listOf("audio/alac", "alac"),
            "PCM" to listOf("audio/raw", "audio/wav", "pcm_s16le", "pcm_s24be", "PCM_F32LE"),
        )
        for ((name, codecs) in rows) for (codec in codecs) {
            assertEquals(codec, name, meta(info("T", null, codec, TrackKindFfi.AUDIO)))
        }
        assertEquals("mp4a.20.1", meta(info("T", null, "mp4a.20.1", TrackKindFfi.AUDIO)))
    }

    @Test
    fun `the delay line names the label and the friendly format`() {
        assertEquals("English · SRT", TrackChoiceText.delayTrack(strings, info(null, "en", "application/x-subrip"), 0, locale = us))
        assertEquals("English", TrackChoiceText.delayTrack(strings, info(null, "en", "application/x-media3-cues"), 0, locale = us))
        assertEquals("Signs · ASS", TrackChoiceText.delayTrack(strings, info("Signs", "en", "ass"), 0, locale = us))
        val ssa = SubtitleFacts(hearingImpaired = false, forced = false, codec = "ssa")
        assertEquals("Signs · SSA", TrackChoiceText.delayTrack(strings, info("Signs", "en", "text/x-ssa"), 0, ssa, us))
    }
}
