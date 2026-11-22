package tv.jellybeam.player

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.jellybeam.i18n.ResourceUiStrings
import uniffi.jellybeam_core.AudioStreamFfi
import uniffi.jellybeam_core.ExternalSubtitleFfi
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
        sdh: Boolean = false,
    ) = TrackInfo(
        id = 1L, kind = kind, title = title, lang = lang, codec = codec,
        isDefault = false, isSelected = false, isForced = forced, isHearingImpaired = sdh,
    )

    private fun label(info: TrackInfo, index: Int = 0) = TrackChoiceText.label(strings, info, index, us)

    private fun meta(
        info: TrackInfo,
        external: Boolean = false,
        status: SidecarStatus? = null,
    ) = TrackChoiceText.meta(strings, info, external, status, us)

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
    fun `a language name starts with a capital in every locale, and an unknown code stays as written`() {
        assertEquals("Anglais", TrackChoiceText.languageName("eng", Locale.FRENCH))
        assertEquals("Français", TrackChoiceText.languageName("fre", Locale.FRENCH))
        assertEquals("English", TrackChoiceText.languageName("eng", us))
        assertEquals("xyz", TrackChoiceText.languageName("xyz", us))
        assertNull(TrackChoiceText.languageName("und", us))
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
        assertEquals(
            "Portuguese · SRT · Forced · SDH",
            meta(info("Brasileiro", "por", "application/x-subrip", forced = true, sdh = true)),
        )
        assertEquals("SDH", meta(info(null, "por", null, sdh = true)))
        assertEquals("Forced", meta(info(null, "por", null, forced = true)))
    }

    @Test
    fun `a sidecar row ends with External then its fetch state`() {
        val srt = info(null, "en", "srt")
        assertEquals("SRT · External", meta(srt, external = true))
        assertEquals("SRT · External · Loading…", meta(srt, external = true, status = SidecarStatus.LOADING))
        assertEquals("External", meta(info(null, null, null), external = true))
        assertEquals("External · Unavailable", meta(info(null, null, null), external = true, status = SidecarStatus.UNAVAILABLE))
        assertEquals(
            "English · SRT · Forced · SDH · External · Loading…",
            meta(info("Full", "en", "srt", forced = true, sdh = true), external = true, status = SidecarStatus.LOADING),
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
    fun `the server's own codec name tells SSA from the ASS Media3 reports for both`() {
        assertEquals("SSA", meta(info(null, "en", "ssa")))
        assertEquals("ASS", meta(info(null, "en", "text/x-ssa")))
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
        assertEquals("Signs · SSA", TrackChoiceText.delayTrack(strings, info("Signs", "en", "ssa"), 0, locale = us))
    }

    /** docs/12 §16: a transcode's audio rows read like Direct Play's, not as the server's joined label. */
    @Test
    fun `a transcode audio row has the Direct Play label and meta`() {
        val (main, commentary) = coreTrackRows(
            listOf(
                AudioStreamFfi(index = 1, displayTitle = "English - Dolby Digital+ - 5.1 - Default", language = "eng", codec = "eac3", channels = 6, isDefault = true),
                AudioStreamFfi(index = 2, displayTitle = "Commentary - English - AAC - Stereo", title = "Commentary", language = "eng", codec = "aac", channels = 2, isDefault = false),
            ),
            embedded = emptyList(),
            sidecars = emptyList(),
            delivered = 1,
            burned = null,
            renditions = false,
        ).map { it.info() }
        assertEquals("English" to "Dolby Digital Plus", label(main) to meta(main))
        assertEquals("Commentary" to "English · AAC", label(commentary, 1) to meta(commentary))
    }

    /** docs/18 §3.2: a sidecar's picker row reads the plan's sidecar row: the server's label verbatim, then language, format, flags. */
    @Test
    fun `a sidecar picker row reads the server's label, language, format and flags`() {
        val rows = sidecarRows(
            coreSidecarRows(
                listOf(
                    ExternalSubtitleFfi(index = 5, codec = "srt", language = "eng", displayTitle = "English SDH", isDefault = false, isForced = false, hearingImpaired = true),
                    ExternalSubtitleFfi(index = 6, codec = "ass", language = "fre", displayTitle = null, isDefault = false, isForced = true),
                    ExternalSubtitleFfi(index = 7, codec = "vtt", language = null, displayTitle = null, isDefault = true, isForced = false),
                ),
            ),
        )
        assertEquals(listOf(5, 6, 7).map(ExternalSubtitles::idFor), rows.map { it.id })
        assertEquals(
            listOf(
                "English SDH" to "English · SRT · SDH · External",
                "French" to "ASS · Forced · External",
                "Track 3" to "VTT · External",
            ),
            rows.mapIndexed { index, row -> label(row, index) to meta(row, external = true) },
        )
    }
}
