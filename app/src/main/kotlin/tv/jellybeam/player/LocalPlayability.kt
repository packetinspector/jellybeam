package tv.jellybeam.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi

/**
 * docs/18-playback-quality.md §1's local-evidence rule: Auto/Cap only transcodes when this device
 * actually cannot play the file. [unplayableReason] is one of three local signals -- the others are
 * a non-recoverable [androidx.media3.common.PlaybackException] (`onPlayerError`) and
 * [SoftwareDecoder.isSoftwareOnly].
 *
 * A [Tracks] type ([C.TRACK_TYPE_VIDEO]/[C.TRACK_TYPE_AUDIO]) is unplayable when present and every
 * track reports [C.FORMAT_UNSUPPORTED_TYPE]/`_SUBTYPE`/`_DRM` -- never
 * [C.FORMAT_EXCEEDS_CAPABILITIES],
 * which the doc reserves for the mislabeled-level case the tolerate setting handles. A file failing
 * both types reports only the video reason.
 */
@OptIn(UnstableApi::class) // C.FORMAT_* track-support constants and TrackGroup.type are @UnstableApi in Media3 1.9.0 -- same opt-in TrackMapping uses
internal object LocalPlayability {
    private val UNSUPPORTED_TRACK_SUPPORT = setOf(
        C.FORMAT_UNSUPPORTED_TYPE,
        C.FORMAT_UNSUPPORTED_SUBTYPE,
        C.FORMAT_UNSUPPORTED_DRM,
    )

    fun unplayableReason(tracks: Tracks): String? =
        unsupportedTypeReason(tracks, C.TRACK_TYPE_VIDEO, "video")
            ?: unsupportedTypeReason(tracks, C.TRACK_TYPE_AUDIO, "audio")

    /** `Tracks.Group` has no direct type accessor in Media3 1.9.0; mirrors
     * [TrackMapping.toTrackInfos]'s own `mediaTrackGroup.type` read.
     */
    private fun unsupportedTypeReason(tracks: Tracks, type: Int, label: String): String? {
        if (!tracks.containsType(type)) return null
        var firstMime: String? = null
        var sawTrack = false
        for (group in tracks.groups) {
            if (group.mediaTrackGroup.type != type) continue
            for (trackIndex in 0 until group.length) {
                sawTrack = true
                if (firstMime == null) firstMime = group.getTrackFormat(trackIndex).sampleMimeType
                if (group.getTrackSupport(trackIndex) !in UNSUPPORTED_TRACK_SUPPORT) return null
            }
        }
        if (!sawTrack) return null
        return "No decoder on this device for the $label track (${firstMime ?: "unknown"})"
    }
}
