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
}
