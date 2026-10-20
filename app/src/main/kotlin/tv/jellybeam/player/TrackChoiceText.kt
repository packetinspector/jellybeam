package tv.jellybeam.player

import tv.jellybeam.R
import tv.jellybeam.i18n.UiStrings
import uniffi.jellybeam_core.TrackInfo

/** Track picker row text (docs/12 "Track picker"): pure so the display rules stay JVM-testable. */
object TrackChoiceText {
    /** Title, else language code, else "Track N" (1-based within its kind); blank counts as absent. */
    fun label(strings: UiStrings, info: TrackInfo, indexInKind: Int): String =
        info.title?.takeIf { it.isNotBlank() }
            ?: info.lang?.takeIf { it.isNotBlank() }
            ?: strings.get(R.string.player_track_default_name, indexInKind + 1)

    /** `"lang codec"`, tagged External for a sidecar (docs/18 §3.2) plus its fetch [status]; `null` if empty. */
    fun meta(strings: UiStrings, info: TrackInfo, external: Boolean, status: SidecarStatus? = null): String? {
        val base = listOfNotNull(info.lang, info.codec).filter { it.isNotBlank() }.joinToString(" ").ifBlank { null }
        if (!external) return base
        val tagged = base?.let { strings.get(R.string.player_track_external_meta, it) } ?: strings.get(R.string.player_track_external)
        val statusText = when (status) {
            null -> return tagged
            SidecarStatus.LOADING -> strings.get(R.string.player_track_loading)
            SidecarStatus.UNAVAILABLE -> strings.get(R.string.player_track_unavailable)
        }
        return strings.get(R.string.player_track_status_meta, tagged, statusText)
    }

    /** docs/12 §16: `SRT`, `ASS`, `PGS` or `VTT` for the codec or mime a subtitle carries; `null` for any other. */
    fun subtitleFormat(codec: String?): String? {
        val tokens = codec?.lowercase()?.split(Regex("[^a-z0-9]+")).orEmpty()
        return when {
            tokens.any { it == "srt" || it == "subrip" } -> "SRT"
            tokens.any { it == "ass" || it == "ssa" } -> "ASS"
            tokens.any { it == "pgs" || it == "pgssub" || it == "hdmv_pgs_subtitle" } -> "PGS"
            tokens.any { it == "vtt" || it == "webvtt" } -> "VTT"
            else -> null
        }
    }

    /** docs/12 §16: the delay control's track line: the picker's [label] and the subtitle format, `"English · SRT"`. */
    fun delayTrack(strings: UiStrings, info: TrackInfo, indexInKind: Int): String {
        val label = label(strings, info, indexInKind)
        return subtitleFormat(info.codec)?.let { strings.get(R.string.player_subtitle_delay_track, label, it) } ?: label
    }
}
