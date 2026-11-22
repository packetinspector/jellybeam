package tv.jellybeam.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.Call
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException

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
 *
 * docs/18 §5.3: small reads are served from a [READ_AHEAD_BYTES] buffer, since Media3's extractors
 * read container fields a few bytes at a time and each call through OkHttp/Okio costs far more
 * than the bytes it returns.
 */
// DataSource/DataSpec/TransferListener are @UnstableApi in Media3 1.9.0 -- same opt-in
// NetworkAwareLoadErrorHandlingPolicy takes to implement LoadErrorHandlingPolicy.
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class CancelingDataSource(
    private val delegate: DataSource,
    private val threadLocalCall: ThreadLocal<Call?>,
    private val tracker: AbandonedReadTracker = AbandonedReadTracker(),
    private val gate: OutOfMediaGate? = null,
    private val urlOf: (DataSpec) -> String = { it.uri.toString() },
) : DataSource {
    /** The open gated segment and its frame count; no count for a resumed open, whose verdict is unknown. */
    private var media: Pair<OutOfMediaGate.Segment, TsVideoPesCounter?>? = null

    /** Bytes pulled from [delegate] but not yet returned: `ahead[aheadPos until aheadEnd]`. */
    private var ahead: ByteArray? = null
    private var aheadPos = 0
    private var aheadEnd = 0

    override fun addTransferListener(transferListener: TransferListener) =
        delegate.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        media = null
        aheadPos = 0
        aheadEnd = 0
        val segment = gate?.admit(OutOfMediaGate.segmentOf(urlOf(dataSpec)))
        // A resumed load starts mid-segment, so its bytes can't say whether the segment had video.
        if (segment != null) media = segment to TsVideoPesCounter().takeIf { dataSpec.position == 0L }
        val result = onTimeout { delegate.open(dataSpec) }
        tracker.onOpened(result)
        return result
    }

    /** docs/18 §7: a gated segment request the server holds past the read timeout may be the end; the gate decides. */
    private inline fun <T> onTimeout(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        val segment = media?.first
        if (segment != null && causes(e).any { it is SocketTimeoutException }) gate?.onSegmentTimedOut(segment)
        throw e
    }

    override fun getUri(): Uri? = delegate.uri

    override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (aheadPos < aheadEnd) return takeAhead(buffer, offset, length)
        // A read as large as the buffer gains nothing from it and would pay an extra copy.
        if (length >= READ_AHEAD_BYTES) return pull(buffer, offset, length)
        val into = ahead ?: ByteArray(READ_AHEAD_BYTES).also { ahead = it }
        val pulled = pull(into, 0, READ_AHEAD_BYTES)
        if (pulled == C.RESULT_END_OF_INPUT) return pulled
        aheadPos = 0
        aheadEnd = pulled
        return takeAhead(buffer, offset, length)
    }

    private fun takeAhead(buffer: ByteArray, offset: Int, length: Int): Int {
        val n = minOf(length, aheadEnd - aheadPos)
        System.arraycopy(ahead!!, aheadPos, buffer, offset, n)
        aheadPos += n
        return n
    }

    /** One upstream read; the tracker and the segment gate see bytes as they leave the network. */
    private fun pull(buffer: ByteArray, offset: Int, length: Int): Int {
        val bytesRead = onTimeout { delegate.read(buffer, offset, length) }
        tracker.onRead(bytesRead)
        media?.let { (segment, counter) ->
            if (bytesRead == C.RESULT_END_OF_INPUT) {
                gate?.onSegmentRead(segment, counter?.hasVideo())
                media = null
            } else {
                counter?.feed(buffer, offset, bytesRead)
            }
        }
        return bytesRead
    }

    override fun close() {
        if (tracker.shouldCancel()) {
            threadLocalCall.get()?.cancel()
        }
        threadLocalCall.remove()
        aheadPos = 0
        aheadEnd = 0
        delegate.close()
    }

    companion object {
        /** Two Okio segments: enough to absorb a run of field reads, small enough to waste nothing on a seek. */
        const val READ_AHEAD_BYTES = 16 * 1024
    }
}

/** [DataSource.Factory] pairing [CancelingDataSource] with the factory it wraps -- an
 * [OkHttpDataSource.Factory] built over a [TrackingCallFactory] sharing the same [threadLocalCall]. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class CancelingDataSourceFactory(
    private val delegate: DataSource.Factory,
    private val threadLocalCall: ThreadLocal<Call?>,
    private val gate: OutOfMediaGate? = null,
) : DataSource.Factory {
    override fun createDataSource(): DataSource =
        CancelingDataSource(delegate.createDataSource(), threadLocalCall, gate = gate)
}

/** docs/18 §7: a segment at or past an [OutOfMediaGate]'s end, refused before any request reaches the server. */
internal class OutOfMediaException : IOException("the server has no media past this point")

/**
 * docs/18 §7: one transcode load's record of its Jellyfin ffmpeg segments, counted on the loader thread as each read
 * ends. [LoadRetryPolicy.EMPTY_SEGMENT_LIMIT] consecutive segments with no video frame mean the server has no media
 * from the run's first index on; from then every open at or past it fails locally, since each request would start
 * another ffmpeg. It keeps counting after a trip, so a lower run moves the end down. Each read and each new end is
 * [post]ed tagged with load [seq] for [reducePlaybackEnd]. Built per [PlayerHolder.load] of a transcode only, so
 * Direct Play never pays for it.
 */
internal class OutOfMediaGate(private val seq: Long = 0L, private val post: (EndEvent) -> Unit = {}) {
    /** A main-playlist ffmpeg segment: its index and media time span. */
    data class Segment(val index: Int, val startMs: Long, val endMs: Long)

    private var runStart: Segment? = null
    private var runLength = 0

    /** First index with no media (the lowest tripping run), or -1 while every segment had media. */
    @Volatile var endIndex: Int = -1
        private set

    val tripped: Boolean get() = endIndex >= 0

    /** Passes [segment] through, or throws [OutOfMediaException] when it lies at or past the end. */
    fun admit(segment: Segment?): Segment? {
        val end = endIndex
        if (segment != null && end >= 0 && segment.index >= end) throw OutOfMediaException()
        return segment
    }

    /** A held-open request past the read timeout counts as video-less only when it continues a run that already is:
     * Jellyfin holds a post-end request open instead of answering, while a slow real segment follows real ones. */
    @Synchronized
    fun onSegmentTimedOut(segment: Segment) {
        val start = runStart
        if (runLength > 0 && start != null && segment.index == start.index + runLength) {
            onSegmentRead(segment, hasVideo = false)
        }
    }

    /** [hasVideo] null (a resumed read, or bytes that weren't plain TS) proves nothing about media, so it breaks any
     * run, and counts as media read. */
    @Synchronized
    fun onSegmentRead(segment: Segment, hasVideo: Boolean?) {
        val start = runStart
        when {
            hasVideo != false -> {
                runLength = 0
                post(EndEvent.MediaArrived(seq, segment.index, segment.endMs))
            }
            runLength > 0 && start != null && segment.index == start.index + runLength -> runLength++
            else -> {
                runStart = segment
                runLength = 1
            }
        }
        val first = runStart
        if (runLength >= LoadRetryPolicy.EMPTY_SEGMENT_LIMIT && first != null && (endIndex < 0 || first.index < endIndex)) {
            endIndex = first.index
            post(EndEvent.Tripped(seq, first.index, first.startMs))
        }
    }

    companion object {
        /** The segment length the device profile asks for (core device_profile.rs HLS_SEGMENT_LENGTH_SECS), used for a
         * segment's times only when its URL omits runtimeTicks/actualSegmentLengthTicks. */
        const val SEGMENT_MS = 6_000L

        // The tail of Jellyfin's DynamicHlsController route, .../hls1/{playlistId}/{segmentId}.ts; proxies may prefix it.
        private val SEGMENT = Regex("""/hls1/[^/?]+/(\d+)\.ts(?:\?|$)""", RegexOption.IGNORE_CASE)
        private val START = Regex("""[?&]runtimeTicks=(\d+)""", RegexOption.IGNORE_CASE)
        private val LENGTH = Regex("""[?&]actualSegmentLengthTicks=(\d+)""", RegexOption.IGNORE_CASE)

        /** Null for anything but a media segment of a Jellyfin ffmpeg job (playlists, subtitles, other files). */
        fun segmentOf(url: String): Segment? {
            val index = SEGMENT.find(url)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            val start = START.find(url)?.groupValues?.get(1)?.toLongOrNull()
            val length = LENGTH.find(url)?.groupValues?.get(1)?.toLongOrNull()
            return if (start != null && length != null) {
                Segment(index, start / 10_000L, (start + length) / 10_000L)
            } else {
                Segment(index, index * SEGMENT_MS, (index + 1) * SEGMENT_MS)
            }
        }
    }
}

/**
 * docs/18 §7: counts video PES starts (stream_id 0xE0-0xEF) in an MPEG-TS byte stream fed in arbitrary chunks. A
 * Jellyfin past-the-end stub carries tables and at most an audio PES, never a video frame, while any real segment
 * starts with a keyframe; byte size can't tell them apart for slideshows or black, silent stretches.
 */
internal class TsVideoPesCounter {
    private val packet = ByteArray(TS_PACKET)
    private var filled = 0
    private var packets = 0
    private var unsynced = false

    var videoPesStarts = 0
        private set

    /** True with a video frame, false for whole 188-byte TS packets with none, null when the bytes weren't that
     * (an error page, 192-byte M2TS, leading junk), so a parse failure never reads as "no media". */
    fun hasVideo(): Boolean? = when {
        videoPesStarts > 0 -> true
        unsynced || packets == 0 || filled != 0 -> null
        else -> false
    }

    fun feed(buffer: ByteArray, offset: Int, length: Int) {
        var i = offset
        val end = offset + length
        while (i < end && videoPesStarts == 0 && !unsynced) {
            if (filled == 0 && end - i >= TS_PACKET) {
                inspect(buffer, i)
                i += TS_PACKET
                continue
            }
            val n = minOf(TS_PACKET - filled, end - i)
            System.arraycopy(buffer, i, packet, filled, n)
            filled += n
            i += n
            if (filled == TS_PACKET) {
                inspect(packet, 0)
                filled = 0
            }
        }
    }

    private fun inspect(p: ByteArray, o: Int) {
        packets++
        if (p[o] != SYNC) {
            unsynced = true
            return
        }
        if (p[o + 1].toInt() and PAYLOAD_UNIT_START == 0) return
        val adaptation = (p[o + 3].toInt() shr 4) and 3
        if (adaptation and 1 == 0) return // no payload
        val start = o + 4 + if (adaptation and 2 != 0) 1 + (p[o + 4].toInt() and 0xFF) else 0
        if (start + 3 >= o + TS_PACKET) return
        if (p[start].toInt() == 0 && p[start + 1].toInt() == 0 && p[start + 2].toInt() == 1 &&
            p[start + 3].toInt() and 0xF0 == 0xE0
        ) {
            videoPesStarts++
        }
    }

    private companion object {
        const val TS_PACKET = 188
        const val SYNC = 0x47.toByte()
        const val PAYLOAD_UNIT_START = 0x40
    }
}
