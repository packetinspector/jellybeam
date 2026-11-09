package tv.jellybeam.visualizer.dsp

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmDecodeTest {
    private class Case(val buf: ByteBuffer, val expect: Array<DoubleArray>)

    private fun putLe(b: ByteBuffer, at: Int, bits: Int, n: Int) {
        for (i in 0 until n) b.put(at + i, ((bits shr (8 * i)) and 0xFF).toByte())
    }

    /** [frames] frames of [ch] channels after [pad] junk bytes; expect[c][f] is the exact float value. */
    private fun build(fmt: PcmFormat, ch: Int, frames: Int, pad: Int, order: ByteOrder, direct: Boolean): Case {
        val size = pad + frames * ch * fmt.bytesPerSample + 5
        val b = if (direct) ByteBuffer.allocateDirect(size) else ByteBuffer.allocate(size)
        b.order(order)
        val rnd = Random(3L + ch + fmt.ordinal)
        for (i in 0 until size) b.put(i, rnd.nextInt().toByte())
        val expect = Array(ch) { DoubleArray(frames) }
        for (f in 0 until frames) for (c in 0 until ch) {
            val at = pad + (f * ch + c) * fmt.bytesPerSample
            expect[c][f] = when (fmt) {
                PcmFormat.I16 -> { val v = rnd.nextInt(65536) - 32768; putLe(b, at, v, 2); v / 32768.0 }
                PcmFormat.I24 -> { val v = rnd.nextInt(1 shl 24) - (1 shl 23); putLe(b, at, v, 3); v / 8388608.0 }
                PcmFormat.I32 -> { val v = rnd.nextInt(); putLe(b, at, v, 4); v / 2147483648.0 }
                PcmFormat.F32 -> { val v = rnd.nextFloat() * 2f - 1f; putLe(b, at, java.lang.Float.floatToRawIntBits(v), 4); v.toDouble() }
            }
        }
        return Case(b, expect)
    }

    private fun snapshot(b: ByteBuffer): ByteArray = ByteArray(b.limit()) { b.get(it) }

    @Test
    fun `every format and channel count decodes to the mean and the first two channels`() {
        for (fmt in PcmFormat.values()) for (ch in intArrayOf(1, 2, 6)) for (order in arrayOf(ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN)) {
            val frames = 37
            val pad = 7
            val c = build(fmt, ch, frames, pad, order, direct = order == ByteOrder.LITTLE_ENDIAN)
            val mono = FloatArray(frames + 4)
            val left = FloatArray(frames + 4)
            val right = FloatArray(frames + 4)
            val before = snapshot(c.buf)
            c.buf.position(3).limit(c.buf.limit() - 1)
            val n = PcmDecode.decode(c.buf, pad, pad + frames * ch * fmt.bytesPerSample, fmt, ch, mono, left, right, 4)
            assertEquals("$fmt $ch frames", frames, n)
            assertEquals("position", 3, c.buf.position())
            assertEquals("limit", before.size - 1, c.buf.limit())
            c.buf.limit(c.buf.capacity())
            assertTrue("contents $fmt $ch", before.contentEquals(snapshot(c.buf)))
            for (f in 0 until frames) {
                val mean = (0 until ch).sumOf { c.expect[it][f] } / ch
                assertEquals("$fmt $ch mono $f", mean, mono[4 + f].toDouble(), 1e-6)
                assertEquals("$fmt $ch left $f", c.expect[0][f], left[4 + f].toDouble(), 1e-6)
                assertEquals("$fmt $ch right $f", c.expect[if (ch > 1) 1 else 0][f], right[4 + f].toDouble(), 1e-6)
            }
            for (i in 0 until 4) assertEquals(0f, mono[i], 0f)
        }
    }

    @Test
    fun `a trailing partial frame is not decoded`() {
        val c = build(PcmFormat.I16, 2, 10, 0, ByteOrder.LITTLE_ENDIAN, false)
        val m = FloatArray(16)
        assertEquals(9, PcmDecode.decode(c.buf, 0, 39, PcmFormat.I16, 2, m, FloatArray(16), FloatArray(16), 0))
    }

    @Test
    fun `non finite float samples decode to silence`() {
        val b = ByteBuffer.allocate(8)
        putLe(b, 0, java.lang.Float.floatToRawIntBits(Float.NaN), 4)
        putLe(b, 4, java.lang.Float.floatToRawIntBits(Float.POSITIVE_INFINITY), 4)
        val m = FloatArray(2)
        PcmDecode.decode(b, 0, 8, PcmFormat.F32, 1, m, FloatArray(2), FloatArray(2), 0)
        assertTrue(abs(m[0]) == 0f && abs(m[1]) == 0f)
    }

    @Test
    fun `android encodings map to formats`() {
        assertEquals(PcmFormat.I16, PcmFormat.fromAndroidEncoding(2))
        assertEquals(PcmFormat.I24, PcmFormat.fromAndroidEncoding(21))
        assertEquals(PcmFormat.I32, PcmFormat.fromAndroidEncoding(22))
        assertEquals(PcmFormat.F32, PcmFormat.fromAndroidEncoding(4))
        for (e in intArrayOf(0, 1, 3, 5, 6, 20, 23)) assertNull("encoding $e", PcmFormat.fromAndroidEncoding(e))
    }
}
