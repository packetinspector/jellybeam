package tv.jellybeam.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import uniffi.jellybeam_core.PlayMethodFfi
import uniffi.jellybeam_core.PlaybackPlan
import uniffi.jellybeam_core.TrackDeliveryFfi
import uniffi.jellybeam_core.TrackInfo
import uniffi.jellybeam_core.TrackKindFfi
import uniffi.jellybeam_core.TrackRowFfi

/**
 * docs/18 §3.1: the rows the choice, the picker and the dot read over the player's [infos]. A
 * transcode lists its plan's rows, each rendition under the id of its Media3 track; a Direct Play
 * file lists Media3's with the server's facts. The plan's sidecar rows end both.
 */
internal fun trackRows(plan: PlaybackPlan, infos: List<TrackInfo>): List<TrackInfo> {
    val sidecars = sidecarRows(plan.trackRows)
    if (plan.playMethod != PlayMethodFfi.TRANSCODE) return withServerFacts(infos, serverSubtitleFacts(infos, plan.embeddedSubtitles)) + sidecars
    val rows = plan.trackRows.filter { it.delivery != TrackDeliveryFfi.SIDECAR }
    val shown = renditionTracks(rows.filter { it.delivery == TrackDeliveryFfi.RENDITION }, infos.filter { it.kind == TrackKindFfi.SUBTITLE })
    // A rendition Media3 lacks is a stream row, as one a renegotiation shows.
    return rows.map { row -> shown[row.streamIndex]?.let { row.info(it.id, it.isSelected) } ?: row.info() } + sidecars
}

/**
 * docs/18 §3.1: each rendition's Media3 track by its label, k renditions to k tracks in order (Media3 lists
 * a manifest's subtitle renditions in its order, the plan's row order), else the one unmatched track of its
 * language when it is also the one unmatched rendition of that language; ambiguity matches nothing.
 */
private fun renditionTracks(renditions: List<TrackRowFfi>, texts: List<TrackInfo>): Map<Int, TrackInfo> {
    val byLabel = renditions.groupBy { it.displayTitle }.flatMap { (label, named) ->
        named.zip(texts.filter { it.title == label }.takeIf { it.size == named.size }.orEmpty())
    }.filter { (row, info) -> row.labels(info) }.associate { (row, info) -> row.streamIndex to info }
    val rows = renditions.filter { it.streamIndex !in byLabel }
    val tracks = texts.filter { info -> byLabel.values.none { it.id == info.id } }
    val byLanguage = rows.mapNotNull { row ->
        val lang = normalizedLanguage(row.lang) ?: return@mapNotNull null
        val track = tracks.singleOrNull { normalizedLanguage(it.lang) == lang } ?: return@mapNotNull null
        (row.streamIndex to track).takeIf { rows.count { normalizedLanguage(it.lang) == lang } == 1 }
    }
    return byLabel + byLanguage
}

/** docs/18 §3.1: the manifest names a rendition by its stream's DisplayTitle verbatim, so only that matches, a missing one never. */
private fun TrackRowFfi.labels(info: TrackInfo) =
    info.title != null && info.title == displayTitle && normalizedLanguage(lang) == normalizedLanguage(info.lang)

/**
 * docs/18 §3.1: [rows]' delivery as the picker rows [shown] (from [trackRows]) show it: a rendition
 * listed as a stream row is one Media3 lacks or can't match, so only a negotiation shows it.
 */
internal fun overlaidRows(rows: List<TrackRowFfi>, shown: List<TrackInfo>): List<TrackRowFfi> = rows.map { row ->
    val lacking = row.delivery == TrackDeliveryFfi.RENDITION && shown.any { it.kind == row.kind && it.id == streamRowId(row.streamIndex) }
    if (lacking) row.copy(delivery = TrackDeliveryFfi.NEEDS_NEGOTIATION) else row
}

/**
 * docs/18 §3.1: the one stream-to-rendition map of a transcode: each subtitle stream's Media3 track as
 * [shown] (from [trackRows], one row per plan row in order) lists it; one only a negotiation shows has none.
 */
internal fun shownRenditions(rows: List<TrackRowFfi>, shown: List<TrackInfo>): Map<Int, TrackInfo> =
    rows.filter { it.delivery != TrackDeliveryFfi.SIDECAR }.zip(shown)
        .filter { (row, info) -> row.kind == TrackKindFfi.SUBTITLE && info.id != streamRowId(row.streamIndex) }
        .associate { (row, info) -> row.streamIndex to info }

/** docs/18 §3.1: [this] row as a picker row; a stream row's id names its server stream. */
internal fun TrackRowFfi.info(id: Long = streamRowId(streamIndex), selected: Boolean = isSelected) = TrackInfo(
    id = id,
    kind = kind,
    title = title,
    lang = lang,
    codec = codec,
    isDefault = isDefault,
    isSelected = selected,
    isForced = isForced,
    isHearingImpaired = isHearingImpaired,
)

/**
 * docs/18 §3.2: [rows]' sidecars as picker rows, the language normalized as Media3 normalizes an
 * embedded track's, so a series' remembered choice matches whichever way its subtitles ship.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun sidecarRows(rows: List<TrackRowFfi>): List<TrackInfo> = rows.filter { it.delivery == TrackDeliveryFfi.SIDECAR }.map {
    it.info(ExternalSubtitles.idFor(it.streamIndex), selected = false).copy(lang = it.lang?.let(Util::normalizeLanguageCode))
}
