package tv.jellybeam.player

import java.util.Locale
import tv.jellybeam.R
import tv.jellybeam.i18n.AppLocale
import tv.jellybeam.i18n.UiStrings
import uniffi.jellybeam_core.TrackInfo
import uniffi.jellybeam_core.TrackKindFfi

/** docs/12 §16: what the server says about one subtitle track that Media3 cannot (SDH), plus its own flags and codec. */
internal data class SubtitleFacts(val hearingImpaired: Boolean, val forced: Boolean, val codec: String?)

/** Track picker row text (docs/12 "Track picker"): pure so the display rules stay JVM-testable. */
object TrackChoiceText {
    /** docs/12 §16: the server's title verbatim, else the language's name, else "Track N" (1-based within its kind); blank counts as absent. */
    fun label(strings: UiStrings, info: TrackInfo, indexInKind: Int, locale: Locale = AppLocale.text): String =
        info.title?.takeIf { it.isNotBlank() }
            ?: languageName(info.lang, locale)
            ?: strings.get(R.string.player_track_default_name, indexInKind + 1)

    /**
     * docs/12 §16: ` · `-joined language (only when titled, else the label already says it), format,
     * Forced, SDH, then External and its fetch [status] (docs/18 §3.2); `null` if empty.
     */
    internal fun meta(
        strings: UiStrings,
        info: TrackInfo,
        external: Boolean,
        status: SidecarStatus? = null,
        facts: SubtitleFacts? = null,
        locale: Locale = AppLocale.text,
    ): String? {
        val titled = !info.title.isNullOrBlank()
        val parts = listOfNotNull(
            languageName(info.lang, locale).takeIf { titled },
            formatOf(info.kind, facts?.codec?.takeIf { it.isNotBlank() } ?: info.codec),
            strings.get(R.string.player_track_forced).takeIf { info.isForced || facts?.forced == true },
            strings.get(R.string.player_track_sdh).takeIf { facts?.hearingImpaired == true },
            strings.get(R.string.player_track_external).takeIf { external },
            when (status) {
                null -> null
                SidecarStatus.LOADING -> strings.get(R.string.player_track_loading)
                SidecarStatus.UNAVAILABLE -> strings.get(R.string.player_track_unavailable)
            },
        )
        return parts.reduceOrNull { joined, part -> strings.get(R.string.player_track_meta_join, joined, part) }
    }

    /** docs/12 §16: the friendly name for a subtitle codec or mime (case-insensitive); `null` when unknown. */
    fun subtitleFormat(codec: String?): String? = codec?.let { SUBTITLE_FORMATS[it.trim().lowercase(Locale.ROOT)] }

    /** docs/12 §16: as [subtitleFormat] for an audio codec or mime, the AAC and PCM families by prefix. */
    fun audioFormat(codec: String?): String? {
        val key = codec?.trim()?.lowercase(Locale.ROOT) ?: return null
        return AUDIO_FORMATS[key] ?: when {
            key.startsWith("mp4a.40.") -> "AAC"
            key.startsWith("pcm_") -> "PCM"
            else -> null
        }
    }

    /** docs/12 §16: the friendly format, else what the server or player wrote, never dropped. */
    private fun formatOf(kind: TrackKindFfi, codec: String?): String? {
        val raw = codec?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return (if (kind == TrackKindFfi.AUDIO) audioFormat(raw) else subtitleFormat(raw)) ?: raw
    }

    /** docs/12 §16: the language's name in [locale]; an unresolvable code shows as written, `und` or blank as nothing. */
    private fun languageName(lang: String?, locale: Locale): String? {
        val code = lang?.trim()?.takeIf { it.isNotEmpty() && !it.equals("und", ignoreCase = true) } ?: return null
        // The JVM names some three-letter codes only by their two-letter form ("zho" -> "zh").
        val alpha2 by lazy {
            val target = normalizedLanguage(code)
            Locale.getISOLanguages().firstOrNull { target != null && normalizedLanguage(it) == target }
        }
        val name = Locale.forLanguageTag(code).getDisplayLanguage(locale).takeIf { it.isNotBlank() && !it.equals(code, ignoreCase = true) }
        return name ?: alpha2?.let { Locale.forLanguageTag(it).getDisplayLanguage(locale) }?.takeIf { it.isNotBlank() } ?: code
    }

    /** docs/12 §16: the delay control's track line: the picker's [label] and the subtitle format, `"English · SRT"`. */
    internal fun delayTrack(strings: UiStrings, info: TrackInfo, indexInKind: Int, facts: SubtitleFacts? = null, locale: Locale = AppLocale.text): String {
        val label = label(strings, info, indexInKind, locale)
        return subtitleFormat(facts?.codec?.takeIf { it.isNotBlank() } ?: info.codec)?.let { strings.get(R.string.player_subtitle_delay_track, label, it) } ?: label
    }

    private val SUBTITLE_FORMATS: Map<String, String> = formats(
        "SRT" to listOf("application/x-subrip", "subrip", "srt"),
        "SSA" to listOf("application/x-ssa", "ssa"),
        // Media3 calls both ASS and SSA text/x-ssa; ASS is what nearly every such file holds.
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

    private val AUDIO_FORMATS: Map<String, String> = formats(
        "Dolby Digital" to listOf("audio/ac3", "ac-3", "ac3"),
        "Dolby Digital Plus" to listOf("audio/eac3", "ec-3", "eac3"),
        "Dolby Atmos" to listOf("audio/eac3-joc", "ec+3"),
        "Dolby TrueHD" to listOf("audio/true-hd", "truehd"),
        "DTS" to listOf("audio/vnd.dts", "dts", "dca", "dtsc"),
        "DTS-HD" to listOf("audio/vnd.dts.hd", "dtsh", "dtsl"),
        "DTS Express" to listOf("audio/vnd.dts.hd;profile=lbr", "dtse"),
        "DTS:X" to listOf("audio/vnd.dts.uhd;profile=p2", "dtsx"),
        "AAC" to listOf("audio/mp4a-latm", "aac"),
        "FLAC" to listOf("audio/flac", "flac"),
        "Opus" to listOf("audio/opus", "opus"),
        "Vorbis" to listOf("audio/vorbis", "vorbis"),
        "MP3" to listOf("audio/mpeg", "mp3"),
        "ALAC" to listOf("audio/alac", "alac"),
        "PCM" to listOf("audio/raw", "audio/wav"),
    )

    private fun formats(vararg rows: Pair<String, List<String>>): Map<String, String> =
        rows.flatMap { (name, codecs) -> codecs.map { it to name } }.toMap()
}
