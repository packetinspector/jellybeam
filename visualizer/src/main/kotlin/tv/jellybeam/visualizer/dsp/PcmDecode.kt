package tv.jellybeam.visualizer.dsp

import java.nio.ByteBuffer

/** docs/30 §2: the PCM encodings the tap decodes; byte order is always little-endian. */
enum class PcmFormat(val bytesPerSample: Int) {
    I16(2),
    I24(3),
    I32(4),
    F32(4);

    companion object {
        /** Android `AudioFormat` ints: 2 = 16 bit, 21 = 24 bit packed, 22 = 32 bit, 4 = float. */
        fun fromAndroidEncoding(e: Int): PcmFormat? = when (e) {
            2 -> I16
            21 -> I24
            22 -> I32
            4 -> F32
            else -> null
        }
    }
}

object PcmDecode {
    /**
     * docs/30 §2: decodes whole frames of [src] bytes [start, end) with absolute gets, so the buffer's
     * position, limit and contents are untouched. Returns frames written at [outOffset]; the caller
     * guarantees capacity. Mono is the mean of all channels, right repeats left for mono input.
     */
    fun decode(
        src: ByteBuffer,
        start: Int,
        end: Int,
        format: PcmFormat,
        channels: Int,
        mono: FloatArray,
        left: FloatArray,
        right: FloatArray,
        outOffset: Int,
    ): Int {
        val bps = format.bytesPerSample
        val frames = (end - start) / (bps * channels)
        val inv = 1f / channels
        var p = start
        for (f in 0 until frames) {
            var sum = 0f
            var first = 0f
            var second = 0f
            for (c in 0 until channels) {
                val s = sample(src, p, format)
                p += bps
                sum += s
                if (c == 0) first = s else if (c == 1) second = s
            }
            val o = outOffset + f
            mono[o] = sum * inv
            left[o] = first
            right[o] = if (channels > 1) second else first
        }
        return frames
    }

    private fun sample(src: ByteBuffer, p: Int, format: PcmFormat): Float = when (format) {
        PcmFormat.I16 -> ((src.get(p + 1).toInt() shl 8) or (src.get(p).toInt() and 0xFF)) / 32768f
        PcmFormat.I24 -> (
            (src.get(p + 2).toInt() shl 16) or ((src.get(p + 1).toInt() and 0xFF) shl 8) or (src.get(p).toInt() and 0xFF)
            ) / 8388608f
        PcmFormat.I32 -> bits(src, p) / 2147483648f
        PcmFormat.F32 -> {
            val v = Float.fromBits(bits(src, p))
            if (v - v == 0f) v.coerceIn(-1f, 1f) else 0f // NaN and Inf decode to silence; overs clip like the DAC
        }
    }

    private fun bits(src: ByteBuffer, p: Int): Int =
        (src.get(p + 3).toInt() shl 24) or ((src.get(p + 2).toInt() and 0xFF) shl 16) or
            ((src.get(p + 1).toInt() and 0xFF) shl 8) or (src.get(p).toInt() and 0xFF)
}
