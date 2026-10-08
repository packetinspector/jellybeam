package tv.jellybeam.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.jellybeam_core.FailedTrackFfi

/** [LocalPlayability] is pure media3-common data-class plumbing; no Android
 * runtime/decoder/ExoPlayer needed, so fixtures build [Format]/[TrackGroup]/[Tracks] directly via
 * their public constructors.
 */
class LocalPlayabilityTest {

    private fun format(sampleMimeType: String?) = Format.Builder().setSampleMimeType(sampleMimeType).build()

    private fun group(vararg support: Int, sampleMimeType: String? = "video/avc"): Tracks.Group {
        val formats = Array(support.size) { format(sampleMimeType) }
        val trackGroup = TrackGroup(*formats)
        val selected = BooleanArray(support.size)
        return Tracks.Group(trackGroup, /* adaptiveSupported= */ false, support, selected)
    }

    // -- No tracks of the type at all -> playable ----------------------

    @Test
    fun `no video or audio tracks at all is playable`() {
        assertNull(LocalPlayability.unplayableReason(Tracks(emptyList())))
    }

    // -- Every track unsupported -> unplayable, per type ----------------

    @Test
    fun `every video track unsupported-type returns the video reason with its mime`() {
        val tracks = Tracks(listOf(group(C.FORMAT_UNSUPPORTED_TYPE, sampleMimeType = "video/hevc")))
        assertEquals(
            "No decoder on this device for the video track (video/hevc)",
            LocalPlayability.unplayableReason(tracks),
        )
    }

    @Test
    fun `every audio track unsupported-subtype returns the audio reason when video is fine`() {
        val videoGroup = group(C.FORMAT_HANDLED, sampleMimeType = "video/avc")
        val audioGroup = Tracks.Group(
            TrackGroup(format("audio/eac3")),
            /* adaptiveSupported= */ false,
            intArrayOf(C.FORMAT_UNSUPPORTED_SUBTYPE),
            booleanArrayOf(false),
        )
        val tracks = Tracks(listOf(videoGroup, audioGroup))
        assertEquals(
            "No decoder on this device for the audio track (audio/eac3)",
            LocalPlayability.unplayableReason(tracks),
        )
    }

    @Test
    fun `unsupported-drm counts the same as unsupported-type or -subtype`() {
        val tracks = Tracks(listOf(group(C.FORMAT_UNSUPPORTED_DRM)))
        assertTrue(LocalPlayability.unplayableReason(tracks)!!.contains("video track"))
    }

    @Test
    fun `every track unsupported across two separate groups of the same type still reports that type`() {
        val groupOne = group(C.FORMAT_UNSUPPORTED_TYPE, sampleMimeType = "video/hevc")
        val groupTwo = group(C.FORMAT_UNSUPPORTED_TYPE, sampleMimeType = "video/hevc")
        val tracks = Tracks(listOf(groupOne, groupTwo))
        assertEquals(
            "No decoder on this device for the video track (video/hevc)",
            LocalPlayability.unplayableReason(tracks),
        )
    }

    // -- At least one supported track of the type -> playable -----------

    @Test
    fun `one handled track among unsupported ones keeps the type playable`() {
        val tracks = Tracks(listOf(group(C.FORMAT_UNSUPPORTED_TYPE, C.FORMAT_HANDLED)))
        assertNull(LocalPlayability.unplayableReason(tracks))
    }

    @Test
    fun `format-exceeds-capabilities is never a trigger -- the tolerate-setting case, not a real failure`() {
        val tracks = Tracks(listOf(group(C.FORMAT_EXCEEDS_CAPABILITIES)))
        assertNull(LocalPlayability.unplayableReason(tracks))
    }

    @Test
    fun `a mix of exceeds-capabilities and unsupported still counts as unplayable`() {
        val tracks = Tracks(listOf(group(C.FORMAT_EXCEEDS_CAPABILITIES, C.FORMAT_UNSUPPORTED_TYPE)))
        assertNull(LocalPlayability.unplayableReason(tracks))
    }

    // -- Video checked first ---------------------------------------------

    @Test
    fun `a file unplayable in both video and audio reports the video reason only`() {
        val videoGroup = group(C.FORMAT_UNSUPPORTED_TYPE, sampleMimeType = "video/hevc")
        val audioGroup = Tracks.Group(
            TrackGroup(format("audio/eac3")),
            /* adaptiveSupported= */ false,
            intArrayOf(C.FORMAT_UNSUPPORTED_TYPE),
            booleanArrayOf(false),
        )
        val tracks = Tracks(listOf(videoGroup, audioGroup))
        assertEquals(
            "No decoder on this device for the video track (video/hevc)",
            LocalPlayability.unplayableReason(tracks),
        )
    }

    // -- docs/18 §2: which track a failure is attributed to -----------

    @Test
    fun `an unplayable type is reported with its kind, video first`() {
        val audioOnly = Tracks(listOf(group(C.FORMAT_HANDLED), group(C.FORMAT_UNSUPPORTED_TYPE, sampleMimeType = "audio/eac3")))
        assertEquals(FailedTrackFfi.AUDIO, LocalPlayability.unplayable(audioOnly)?.first)
        val both = Tracks(listOf(group(C.FORMAT_UNSUPPORTED_TYPE), group(C.FORMAT_UNSUPPORTED_TYPE, sampleMimeType = "audio/eac3")))
        assertEquals(FailedTrackFfi.VIDEO, LocalPlayability.unplayable(both)?.first)
    }

    @Test
    fun `a renderer error is attributed to its format's type, anything else is unknown`() {
        fun rendererError(mime: String?) = ExoPlaybackException.createForRenderer(
            IllegalStateException("decode"),
            "renderer",
            0,
            format(mime),
            C.FORMAT_HANDLED,
            /* mediaPeriodId= */ null,
            false,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
        )
        assertEquals(FailedTrackFfi.VIDEO, LocalPlayability.failedTrack(rendererError("video/hevc")))
        assertEquals(FailedTrackFfi.AUDIO, LocalPlayability.failedTrack(rendererError("audio/eac3")))
        assertEquals(FailedTrackFfi.UNKNOWN, LocalPlayability.failedTrack(rendererError(null)))
        assertEquals(
            FailedTrackFfi.UNKNOWN,
            LocalPlayability.failedTrack(PlaybackException("boom", null, PlaybackException.ERROR_CODE_DECODING_FAILED)),
        )
    }
}
