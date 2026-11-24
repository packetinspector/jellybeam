package tv.jellybeam.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import uniffi.jellybeam_core.SubtitleDeliveryFfi
import uniffi.jellybeam_core.TrackDeliveryFfi
import uniffi.jellybeam_core.TrackInfo
import uniffi.jellybeam_core.TrackKindFfi

/**
 * Media3 [Tracks] <-> Rust [TrackInfo] mapping (docs/09-settings-plan.md step 3): [toTrackInfos]
 * feeds [tv.jellybeam.data.CoreGateway.resolveTracks]; [resolve] reverses a
 * [uniffi.jellybeam_core.TrackDecisionFfi]'s chosen id back to `(groupIndex, trackIndex)`
 * (see [PlayerHolder.applyTrackDecision]).
 *
 * **Id scheme**: `id = groupIndex * 1000 + trackIndex`, only meaningful against the same [Tracks]
 * snapshot since Media3 renumbers groups/tracks on every `onTracksChanged`; an id must be consumed
 * via [resolve] before the next announcement, never persisted (memory instead keeps
 * [uniffi.jellybeam_core.trackMemoryOf]'s stable identity).
 *
 * **Forced-flag source**: [Format.selectionFlags] `and` [C.SELECTION_FLAG_FORCED] -- Media3 1.9.0
 * has no `ROLE_FLAG_FORCED_SUBTITLE` constant.
 */
object TrackMapping {
    /** Decimal digits of `id` reserved for `trackIndex`, larger than any real group's tracks. */
    private const val GROUP_ID_MULTIPLIER = 1000L

    /** The `(groupIndex, trackIndex)` pair one [TrackInfo.id] decodes to. */
    data class ResolvedTrack(val groupIndex: Int, val trackIndex: Int)

    /** Maps [tracks]'s audio and text groups only; video carries nothing
     * [tv.jellybeam.data.CoreGateway.resolveTracks] needs.
     */
    // TrackGroup.type is @UnstableApi in Media3 1.9.0 -- same opt-in PlayerHolder.buildPlayer takes
    // for DefaultRenderersFactory.
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun toTrackInfos(tracks: Tracks): List<TrackInfo> {
        val result = mutableListOf<TrackInfo>()
        tracks.groups.forEachIndexed { groupIndex, group ->
            val kind = when (group.mediaTrackGroup.type) {
                C.TRACK_TYPE_AUDIO -> TrackKindFfi.AUDIO
                C.TRACK_TYPE_TEXT -> TrackKindFfi.SUBTITLE
                else -> return@forEachIndexed
            }
            for (trackIndex in 0 until group.length) {
                val format = group.getTrackFormat(trackIndex)
                result += TrackInfo(
                    id = toId(groupIndex, trackIndex),
                    kind = kind,
                    title = format.label,
                    lang = format.language,
                    codec = format.codecs ?: format.sampleMimeType,
                    isDefault = format.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0,
                    isSelected = group.isTrackSelected(trackIndex),
                    isForced = format.selectionFlags and C.SELECTION_FLAG_FORCED != 0,
                    isHearingImpaired = false, // Media3 has no SDH flag; the server's facts fill it (docs/12 §16)
                )
            }
        }
        return result
    }

    /** The id one `(groupIndex, trackIndex)` pair maps to, per this object's id scheme. */
    fun toId(groupIndex: Int, trackIndex: Int): Long =
        groupIndex.toLong() * GROUP_ID_MULTIPLIER + trackIndex.toLong()

    /** Reverses [toId] against [tracks]: `null` for a negative id or an out-of-range group/track
     * (e.g. stale); [PlayerHolder.applyTrackDecision] treats `null` as nothing to apply.
     */
    fun resolve(tracks: Tracks, id: Long): ResolvedTrack? {
        if (id < 0) return null
        val groupIndex = (id / GROUP_ID_MULTIPLIER).toInt()
        val trackIndex = (id % GROUP_ID_MULTIPLIER).toInt()
        val group = tracks.groups.getOrNull(groupIndex) ?: return null
        if (trackIndex !in 0 until group.length) return null
        return ResolvedTrack(groupIndex, trackIndex)
    }
}

/** docs/31 §4.3: the picker row among [rows] (the rows on screen) showing server stream [index] of [kind]; `null` when none does. */
internal fun rowForStreamIndex(p: PlanFacts, rows: List<TrackInfo>, kind: TrackKindFfi, index: Int): Long? {
    if (kind == TrackKindFfi.SUBTITLE) {
        if (index == NO_SUBTITLE_STREAM) return TRACK_PICKER_SUBTITLE_OFF_ID
        ExternalSubtitles.idFor(index).takeIf { id -> rows.any { it.id == id } }?.let { return it }
    }
    // docs/18 §3.1: a transcode's picker lists one row per non-sidecar plan row, in the plan's order.
    if (p.transcode) {
        return p.rows.filter { it.delivery != TrackDeliveryFfi.SIDECAR }.zip(rows)
            .firstOrNull { (row, info) -> row.kind == kind && row.streamIndex == index && info.kind == kind }?.second?.id
    }
    // Direct Play: Media3 lists the file's tracks in the server's stream order, trusted only when every pair agrees, since a wrong track is worse than none.
    val streams = when (kind) {
        TrackKindFfi.SUBTITLE -> p.embedded.filter { it.delivery != SubtitleDeliveryFfi.HLS }.map { it.index to it.identity() }
        TrackKindFfi.AUDIO -> p.audioStreams.map { it.index to streamAudioChoice(it).identity }
        TrackKindFfi.VIDEO -> return null
    }
    val tracks = rows.filter { it.kind == kind && it.id >= 0 && ExternalSubtitles.indexOf(it.id) == null }
    val paired = streams.size == tracks.size && streams.zip(tracks).all { (stream, track) -> sameTrack(stream.second, track.identity()) }
    val at = streams.indexOfFirst { it.first == index }
    return if (paired && at >= 0) tracks[at].id else null
}

/** Same language, and the same title where both carry one, since two tracks of one language differ only by title. */
internal fun sameTrack(stream: TrackIdentity, track: TrackIdentity) =
    stream.lang == track.lang && (stream.title == null || track.title == null || stream.title == track.title)
