package tv.jellybeam.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Tracks
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
 * via [resolve] before the next announcement, never persisted (per-series memory instead keys on
 * [uniffi.jellybeam_core.trackPrefKeyOf]'s stable language/title string).
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
