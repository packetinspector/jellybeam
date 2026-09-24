package tv.jellybeam.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.Call
import okhttp3.Request

/**
 * Records the [Call] each [newCall] creates in [threadLocalCall] -- Media3 opens and closes an
 * [OkHttpDataSource] on the same loader thread, so [CancelingDataSource.close] can find and
 * cancel the right one later with no other plumbing between them.
 */
internal class TrackingCallFactory(
    private val delegate: Call.Factory,
    private val threadLocalCall: ThreadLocal<Call?>,
) : Call.Factory {
    override fun newCall(request: Request): Call {
        val call = delegate.newCall(request)
        threadLocalCall.set(call)
        return call
    }
}

/**
 * Pure decision for [CancelingDataSource.close]: whether the read behind this [DataSource] was
 * abandoned mid-stream and its OkHttp [Call] should be cancelled, or left alone so keep-alive can
 * reuse the connection. Extracted so it's directly unit-tested with no fake [DataSpec]/[Uri]
 * needed -- mirrors [SeekSerializer]'s "thread the decision through by hand" shape.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class AbandonedReadTracker(private val cancelThresholdBytes: Long = CANCEL_THRESHOLD_BYTES) {
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()
    private var reachedEof: Boolean = false

    /** Call from [DataSource.open] with its return value. */
    fun onOpened(openedBytesRemaining: Long) {
        bytesRemaining = openedBytesRemaining
        reachedEof = false
    }

    /** Call from [DataSource.read] with its return value. */
    fun onRead(bytesRead: Int) {
        if (bytesRead == C.RESULT_END_OF_INPUT) {
            reachedEof = true
        } else if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            bytesRemaining -= bytesRead
        }
    }

    /** `true` when [DataSource.close] should cancel the underlying call: the remaining length is
     * unknown or still substantial, and [onRead] never reported end-of-stream. A clean close at
     * (or effectively at) end-of-stream -- `0` remaining, or an observed EOF read -- returns
     * `false` so OkHttp's connection pool can reuse it. */
    fun shouldCancel(): Boolean =
        !reachedEof && (bytesRemaining == C.LENGTH_UNSET.toLong() || bytesRemaining > cancelThresholdBytes)

    companion object {
        /** Below this, draining the response is cheaper than paying for a fresh TCP+TLS
         * handshake on the next open; docs/18 §5.2's keyframe-resume reopens and an out-of-buffer
         * skip are typically full reloads well past it. */
        const val CANCEL_THRESHOLD_BYTES = 256 * 1024L
    }
}

/**
 * Wraps an [OkHttpDataSource] so [close] cancels a still-abandoned read's [Call] instead of
 * letting Media3 1.9.0's `closeConnectionQuietly()` -- which only closes the response body --
 * leave OkHttp to drain up to `DISCARD_STREAM_TIMEOUT` (100ms) on this loader thread before it
 * drops the connection. Every reopen (an MKV/MP4 trailer seek, the docs/18 §5.2 resume seek, any
 * out-of-buffer skip) paid that cost. The cancel-or-not decision is [AbandonedReadTracker]'s;
 * this class is just the [DataSource] plumbing around it, so no `IOException` from a cancelled
 * close is expected to surface here -- OkHttp's own discard path fails fast without throwing.
 */
// DataSource/DataSpec/TransferListener are @UnstableApi in Media3 1.9.0 -- same opt-in
// NetworkAwareLoadErrorHandlingPolicy takes to implement LoadErrorHandlingPolicy.
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class CancelingDataSource(
    private val delegate: DataSource,
    private val threadLocalCall: ThreadLocal<Call?>,
    private val tracker: AbandonedReadTracker = AbandonedReadTracker(),
) : DataSource {

    override fun addTransferListener(transferListener: TransferListener) =
        delegate.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val result = delegate.open(dataSpec)
        tracker.onOpened(result)
        return result
    }

    override fun getUri(): Uri? = delegate.uri

    override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val bytesRead = delegate.read(buffer, offset, length)
        tracker.onRead(bytesRead)
        return bytesRead
    }

    override fun close() {
        if (tracker.shouldCancel()) {
            threadLocalCall.get()?.cancel()
        }
        threadLocalCall.remove()
        delegate.close()
    }
}

/** [DataSource.Factory] pairing [CancelingDataSource] with the factory it wraps -- an
 * [OkHttpDataSource.Factory] built over a [TrackingCallFactory] sharing the same [threadLocalCall]. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class CancelingDataSourceFactory(
    private val delegate: DataSource.Factory,
    private val threadLocalCall: ThreadLocal<Call?>,
) : DataSource.Factory {
    override fun createDataSource(): DataSource = CancelingDataSource(delegate.createDataSource(), threadLocalCall)
}
