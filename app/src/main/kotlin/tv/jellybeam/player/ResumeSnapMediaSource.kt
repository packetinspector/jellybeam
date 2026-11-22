package tv.jellybeam.player

import androidx.annotation.OptIn
import androidx.media3.common.StreamKey
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.source.WrappingMediaSource
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator

/**
 * docs/18 §5.2: a resume's first track selection starts at the keyframe at or before its saved position, so the
 * first request opens there and no later seek reopens it. One-shot per source, so a period a retry re-creates keeps
 * its exact position. Period calls arrive on the playback thread only.
 */
@OptIn(UnstableApi::class, ExperimentalApi::class)
internal class ResumeSnapMediaSource(inner: MediaSource) : WrappingMediaSource(inner) {
    private var snapPending = true

    override fun createPeriod(id: MediaSource.MediaPeriodId, allocator: Allocator, startPositionUs: Long): MediaPeriod =
        SnapPeriod(mediaSource.createPeriod(id, allocator, startPositionUs))

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        mediaSource.releasePeriod((mediaPeriod as SnapPeriod).inner)
    }

    private fun takeSnap(): Boolean = snapPending.also { snapPending = false }

    /** Forwards every call to [inner]; callbacks name this period, since ExoPlayer matches them by identity. */
    private inner class SnapPeriod(val inner: MediaPeriod) : MediaPeriod {
        override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
            inner.prepare(
                object : MediaPeriod.Callback {
                    override fun onPrepared(mediaPeriod: MediaPeriod) = callback.onPrepared(this@SnapPeriod)

                    override fun onContinueLoadingRequested(source: MediaPeriod) = callback.onContinueLoadingRequested(this@SnapPeriod)
                },
                positionUs,
            )
        }

        override fun selectTracks(
            selections: Array<out ExoTrackSelection?>,
            mayRetainStreamFlags: BooleanArray,
            streams: Array<SampleStream?>,
            streamResetFlags: BooleanArray,
            positionUs: Long,
        ): Long {
            val startUs = if (takeSnap() && positionUs > 0L) inner.getAdjustedSeekPositionUs(positionUs, SeekParameters.PREVIOUS_SYNC) else positionUs
            return inner.selectTracks(selections, mayRetainStreamFlags, streams, streamResetFlags, startUs)
        }

        override fun maybeThrowPrepareError() = inner.maybeThrowPrepareError()

        override fun getTrackGroups(): TrackGroupArray = inner.trackGroups

        override fun getStreamKeys(trackSelections: List<ExoTrackSelection>): List<StreamKey> = inner.getStreamKeys(trackSelections)

        override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) = inner.discardBuffer(positionUs, toKeyframe)

        override fun readDiscontinuity(): Long = inner.readDiscontinuity()

        override fun setUsesStreamPrerollFlags() = inner.setUsesStreamPrerollFlags()

        override fun seekToUs(positionUs: Long): Long = inner.seekToUs(positionUs)

        override fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters): Long =
            inner.getAdjustedSeekPositionUs(positionUs, seekParameters)

        override fun getBufferedPositionUs(): Long = inner.bufferedPositionUs

        override fun getNextLoadPositionUs(): Long = inner.nextLoadPositionUs

        override fun continueLoading(loadingInfo: LoadingInfo): Boolean = inner.continueLoading(loadingInfo)

        override fun isLoading(): Boolean = inner.isLoading

        override fun reevaluateBuffer(positionUs: Long) = inner.reevaluateBuffer(positionUs)

        override fun setEndPositionUs(endPositionUs: Long): Long = inner.setEndPositionUs(endPositionUs)
    }
}
