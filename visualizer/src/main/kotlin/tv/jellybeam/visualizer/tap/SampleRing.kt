package tv.jellybeam.visualizer.tap

import java.nio.ByteBuffer
import tv.jellybeam.visualizer.dsp.PcmDecode
import tv.jellybeam.visualizer.dsp.PcmFormat

/**
 * docs/30 §2: about two seconds of decoded output, indexed by output frame (frames handed to the
 * audio output since it was created or flushed), so the output's own position is the read cursor and
 * seeks, gapless joins and speed changes need no timestamp mapping.
 *
 * One writer (the playback thread) and one reader (the GL thread). The writer fills samples, then
 * publishes [written]; the reader reads only the older half of the ring and checks [epoch] and
 * [written] after copying, so a window flushed or overwritten mid-read is never returned.
 */
class SampleRing {
    private class Buffers(val capacity: Int) {
        val mask = capacity - 1
        val mono = FloatArray(capacity)
        val left = FloatArray(capacity)
        val right = FloatArray(capacity)
    }

    @Volatile
    private var buffers = Buffers(0)

    @Volatile
    private var written = 0L

    /** First frame written since the last clear or jump; older slots hold stale audio. */
    @Volatile
    private var validFrom = 0L

    /** Set by the reader on attach; the writer reconfigures before its next write. */
    @Volatile
    var resetRequested = true
        private set

    /** Odd while the writer is clearing; bumped twice by every clear. */
    @Volatile
    private var epoch = 0

    /** Sample rate of the frames in the ring; 0 until [reconfigure]. */
    @Volatile
    var sampleRate = 0
        private set

    /** Writer side: a new output format. Clears the ring; reallocates only when the rate needs more room. */
    fun reconfigure(sampleRate: Int) {
        epoch++
        val needed = Integer.highestOneBit(maxOf(sampleRate, MIN_RATE) * 2 - 1) shl 1
        if (needed > buffers.capacity) buffers = Buffers(needed)
        this.sampleRate = sampleRate
        written = 0L
        validFrom = 0L
        resetRequested = false
        epoch++
    }

    /** Reader side: a fresh attach; the ring may hold audio from before a flush it never saw. */
    fun requestReset() {
        resetRequested = true
    }

    /** Writer side: flush or seek; everything written before reads as silence. */
    fun clear() {
        epoch++
        written = 0L
        validFrom = 0L
        epoch++
    }

    /**
     * Writer side: decodes `[start, end)` of [src] (never moved or written) as frames from [firstFrame]
     * on, publishing per chunk. A [firstFrame] past the newest frame (frames went by unrecorded) starts
     * a new valid span there.
     */
    fun write(src: ByteBuffer, start: Int, end: Int, format: PcmFormat, channels: Int, firstFrame: Long) {
        val b = buffers
        if (b.capacity == 0 || channels <= 0) return
        if (firstFrame != written) {
            epoch++
            written = firstFrame
            validFrom = firstFrame
            epoch++
        }
        val frameBytes = format.bytesPerSample * channels
        val chunk = b.capacity / 4
        var pos = start
        var w = written
        while (end - pos >= frameBytes) {
            val offset = (w and b.mask.toLong()).toInt()
            val frames = minOf((end - pos) / frameBytes, b.capacity - offset, chunk)
            val n = PcmDecode.decode(src, pos, pos + frames * frameBytes, format, channels, b.mono, b.left, b.right, offset)
            if (n <= 0) break
            pos += n * frameBytes
            w += n
            written = w
        }
    }

    /**
     * Reader side: [outMono] gets frames `[center - size/2, center + size/2)` and [outLeft]/[outRight]
     * get `[center, center + size)`; a frame never written, overwritten or flushed reads as silence.
     * Returns false when no audio frame was in the windows. [monoSize] and [waveSize] use only a prefix of
     * the arrays (the decimating reader keeps arrays sized for its largest factor).
     */
    fun read(
        center: Long,
        outMono: FloatArray,
        outLeft: FloatArray,
        outRight: FloatArray,
        monoSize: Int = outMono.size,
        waveSize: Int = outLeft.size,
    ): Boolean {
        repeat(READ_ATTEMPTS) {
            val e = epoch
            val b = buffers
            if (e and 1 == 0 && b.capacity > 0) {
                val w = written
                val oldest = maxOf(validFrom, w - b.capacity / 2)
                var any = copy(b, b.mono, center - monoSize / 2, outMono, monoSize, oldest, w)
                any = copy(b, b.left, center, outLeft, waveSize, oldest, w) or any
                any = copy(b, b.right, center, outRight, waveSize, oldest, w) or any
                // Frames copied are at least capacity/2 behind w; writes since then are under capacity/4 per publish.
                if (epoch == e && buffers === b && written - w < b.capacity / 4) return any
            }
        }
        outMono.fill(0f)
        outLeft.fill(0f)
        outRight.fill(0f)
        return false
    }

    /**
     * Reader side: mono frames `[from, from + count)` into [out]; frames never written, overwritten or
     * flushed read as silence. Feeds the fixed-hop onset analysis (docs/30 §3) everything played.
     */
    fun readMono(from: Long, out: FloatArray, count: Int): Boolean {
        repeat(READ_ATTEMPTS) {
            val e = epoch
            val b = buffers
            if (e and 1 == 0 && b.capacity > 0) {
                val w = written
                val oldest = maxOf(validFrom, w - b.capacity / 2)
                val any = copy(b, b.mono, from, out, count, oldest, w)
                if (epoch == e && buffers === b && written - w < b.capacity / 4) return any
            }
        }
        out.fill(0f, 0, count)
        return false
    }

    private fun copy(b: Buffers, src: FloatArray, from: Long, out: FloatArray, n: Int, oldest: Long, w: Long): Boolean {
        var any = false
        for (i in 0 until n) {
            val f = from + i
            if (f < oldest || f >= w) {
                out[i] = 0f
            } else {
                out[i] = src[(f and b.mask.toLong()).toInt()]
                any = true
            }
        }
        return any
    }

    private companion object {
        const val MIN_RATE = 8_000
        const val READ_ATTEMPTS = 3
    }
}
