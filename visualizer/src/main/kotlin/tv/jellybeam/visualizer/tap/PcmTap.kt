package tv.jellybeam.visualizer.tap

import java.nio.ByteBuffer
import tv.jellybeam.visualizer.dsp.PcmFormat

/** docs/30 §2: what the music output is carrying, as the visualizer sees it. */
enum class TapState {
    /** No output configured yet. */
    IDLE,

    /** Decoded PCM: the visualizer can draw. */
    PCM,

    /** Passthrough/offload: the data is encoded and can't be analysed (D10's still fallback). */
    ENCODED,
}

/**
 * docs/30 §2 (D3): the music audio output hands every buffer it consumed to [offer], plus its config,
 * flushes and positions. With no listener attached a call costs one volatile read and, for [offer],
 * one counter add (so a later attach knows which output frame comes next): no copy, no allocation,
 * no thread. The buffer is only ever read with absolute gets, never moved or written.
 *
 * Writer calls come from the playback thread; [attach]/[detach]/[position] from the reader (GL) thread.
 */
class PcmTap {
    @Volatile
    private var ring: SampleRing? = null

    // Config: plain fields written on the playback thread, published by the volatile [configSeq].
    private var format: PcmFormat? = null
    private var channels = 0
    private var sampleRate = 0
    private var encoded = false

    /** Bytes the output consumed since it was configured or flushed; playback thread only. */
    private var consumedBytes = 0L

    @Volatile
    private var configSeq = 0

    // Position snapshot, seqlocked: odd [posSeq] while the writer updates it.
    @Volatile
    private var posSeq = 0
    @Volatile
    private var posFrames = 0.0

    @Volatile
    private var posNanos = 0L

    @Volatile
    private var posRate = 0.0

    /** Buffers that reached the ring; a test hook for the zero-idle rule (docs/30 §9 T27). */
    @Volatile
    var offeredToRing = 0L
        private set

    val state: TapState
        get() = when {
            configSeq == 0 || sampleRate == 0 -> TapState.IDLE
            encoded || format == null -> TapState.ENCODED
            else -> TapState.PCM
        }

    val isAttached: Boolean get() = ring != null

    /** Reader-set: run on the playback thread when an attached tap's clock starts running again. */
    @Volatile
    var onResume: Runnable? = null

    /** Playback thread: a new output was created. [androidEncoding] is the AudioFormat encoding; [offload] outputs are encoded. */
    fun configure(androidEncoding: Int, sampleRate: Int, channelCount: Int, offload: Boolean) {
        val f = if (offload) null else PcmFormat.fromAndroidEncoding(androidEncoding)
        format = f
        encoded = f == null
        channels = channelCount
        this.sampleRate = sampleRate
        consumedBytes = 0L
        configSeq++
        val r = ring ?: return
        r.reconfigure(sampleRate)
        // The reader re-reads [state] (PCM vs encoded) on its next vsync.
        onResume?.run()
    }

    /** Playback thread: the output consumed `[start, end)` of [buffer]. */
    fun offer(buffer: ByteBuffer, start: Int, end: Int) {
        val before = consumedBytes
        consumedBytes = before + (end - start)
        val r = ring ?: return
        val f = format ?: return
        if (end <= start || channels <= 0) return
        // The writer is the ring's only mutator once attached, so an attach is applied here.
        if (r.resetRequested || r.sampleRate != sampleRate) {
            r.reconfigure(sampleRate)
            // A reader that attached while paused idles on its empty ring; audio arriving must wake it.
            onResume?.run()
        }
        r.write(buffer, start, end, f, channels, before / (f.bytesPerSample * channels))
        offeredToRing++
    }

    /** Playback thread: the output was flushed (seek, stop); earlier frames must never be drawn. */
    fun flush() {
        consumedBytes = 0L
        val r = ring ?: return
        r.clear()
        publishPosition(0.0, System.nanoTime(), 0.0)
    }

    /** Playback thread: the output's (guarded) position, and whether its clock is running at [speed]. */
    fun onPosition(positionUs: Long, nowNanos: Long, playing: Boolean, speed: Float) {
        if (ring == null) return
        // The output's own rate, so a report lands even before the first buffer reaches a fresh ring.
        val rate = sampleRate
        if (rate == 0) return
        val wasRunning = posRate > 0.0
        publishPosition(positionUs * rate / 1e6, nowNanos, if (playing) speed.toDouble() * rate / 1e9 else 0.0)
        if (playing && !wasRunning) onResume?.run()
    }

    /** Playback thread: the output paused; the window freezes where it is (docs/30 §2). */
    fun pause(nowNanos: Long) {
        if (ring == null) return
        publishPosition(position(nowNanos), nowNanos, 0.0)
    }

    private fun publishPosition(frames: Double, nanos: Long, framesPerNano: Double) {
        posSeq++
        posFrames = frames
        posNanos = nanos
        posRate = framesPerNano
        posSeq++
    }

    /** Reader: starts filling [target]; the playback thread sizes it on the next buffer. */
    fun attach(target: SampleRing) {
        target.requestReset()
        ring = target
    }

    /** Reader: stops filling [target]; the tap returns to its one-volatile-read cost. A newer attach is left alone. */
    fun detach(target: SampleRing) {
        if (ring === target) ring = null
    }

    /** Reader: the output frame playing at [nowNanos], extrapolated from the last position report. */
    fun position(nowNanos: Long): Double {
        while (true) {
            val s = posSeq
            if (s and 1 != 0) continue
            val frames = posFrames
            val nanos = posNanos
            val rate = posRate
            if (posSeq == s) return frames + rate * (nowNanos - nanos).coerceAtLeast(0L)
        }
    }

    /** Reader: true while the last report said the clock runs. */
    val isRunning: Boolean get() = posRate > 0.0
}
