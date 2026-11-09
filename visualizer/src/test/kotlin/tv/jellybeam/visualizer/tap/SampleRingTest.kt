package tv.jellybeam.visualizer.tap

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.jellybeam.visualizer.dsp.PcmFormat

/** docs/30 §9 T11, T12, T14: windows follow the output frame index; events never leak stale audio. */
class SampleRingTest {
    /** Mono float PCM whose sample at frame f is `value(f)`. */
    private fun floats(first: Long, frames: Int, value: (Long) -> Float): ByteBuffer {
        val b = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) b.putFloat(value(first + i))
        b.flip()
        return b
    }

    private fun stamp(f: Long): Float = ((f % 10_007) + 1) / 20_000f

    private fun feed(ring: SampleRing, from: Long, total: Int, bufferFrames: Int) {
        var f = from
        while (f < from + total) {
            val n = minOf(bufferFrames.toLong(), from + total - f).toInt()
            val b = floats(f, n, ::stamp)
            ring.write(b, 0, b.limit(), PcmFormat.F32, 1, f)
            f += n
        }
    }

    @Test
    fun readReturnsExactlyTheFramesAroundThePosition() {
        for (size in intArrayOf(256, 1024, 4096)) {
            val ring = SampleRing()
            ring.reconfigure(48_000)
            feed(ring, 0, 30_000, size)
            for (p in longArrayOf(5_000L, 5_001L, 12_345L, 20_000L + size / 2)) {
                val mono = FloatArray(1024)
                val l = FloatArray(512)
                val r = FloatArray(512)
                assertTrue(ring.read(p, mono, l, r))
                for (i in mono.indices) assertEquals("size=$size p=$p i=$i", stamp(p - 512 + i), mono[i], 0f)
                for (i in l.indices) {
                    assertEquals(stamp(p + i), l[i], 0f)
                    assertEquals(stamp(p + i), r[i], 0f)
                }
            }
        }
    }

    @Test
    fun clearLeavesOnlySilenceUntilNewFramesArrive() {
        val ring = SampleRing()
        ring.reconfigure(48_000)
        feed(ring, 0, 20_000, 1024)
        ring.clear()
        val mono = FloatArray(1024)
        assertFalse(ring.read(10_000, mono, FloatArray(512), FloatArray(512)))
        assertTrue(mono.all { it == 0f })
        // After a seek the output restarts at frame 0; pre-seek samples never reappear.
        feed(ring, 0, 600, 600)
        assertTrue(ring.read(300, mono, FloatArray(512), FloatArray(512)))
        for (i in 0 until 1024) {
            val f = 300 - 512 + i
            assertEquals(if (f in 0 until 600) stamp(f.toLong()) else 0f, mono[i], 0f)
        }
    }

    @Test
    fun pausedReadsReturnTheSameWindow() {
        val ring = SampleRing()
        ring.reconfigure(44_100)
        feed(ring, 0, 20_000, 2048)
        val a = FloatArray(1024)
        val b = FloatArray(1024)
        ring.read(9_000, a, FloatArray(512), FloatArray(512))
        ring.read(9_000, b, FloatArray(512), FloatArray(512))
        assertTrue(a.contentEquals(b))
    }

    @Test
    fun gaplessJoinKeepsTheStreamContinuous() {
        val ring = SampleRing()
        ring.reconfigure(48_000)
        // Track A ends at frame 15 000 and track B continues in the same output without a flush.
        feed(ring, 0, 15_000, 1024)
        feed(ring, 15_000, 15_000, 1024)
        val mono = FloatArray(1024)
        assertTrue(ring.read(15_000, mono, FloatArray(512), FloatArray(512)))
        for (i in mono.indices) assertEquals(stamp(15_000L - 512 + i), mono[i], 0f)
    }

    @Test
    fun sampleRateChangeClears() {
        val ring = SampleRing()
        ring.reconfigure(48_000)
        feed(ring, 0, 20_000, 1024)
        ring.reconfigure(96_000)
        assertEquals(96_000, ring.sampleRate)
        assertFalse(ring.read(10_000, FloatArray(1024), FloatArray(512), FloatArray(512)))
    }

    @Test
    fun underrunReadsSilencePastTheNewestFrame() {
        val ring = SampleRing()
        ring.reconfigure(48_000)
        feed(ring, 0, 10_000, 1024)
        val mono = FloatArray(1024)
        ring.read(10_000, mono, FloatArray(512), FloatArray(512))
        for (i in 512 until 1024) assertEquals(0f, mono[i], 0f)
        assertFalse(ring.read(12_000, mono, FloatArray(512), FloatArray(512)))
    }

    @Test
    fun aGapStartsANewValidSpan() {
        val ring = SampleRing()
        ring.reconfigure(48_000)
        feed(ring, 0, 5_000, 1000)
        feed(ring, 8_000, 5_000, 1000)
        val mono = FloatArray(1024)
        ring.read(8_000, mono, FloatArray(512), FloatArray(512))
        for (i in 0 until 512) assertEquals(0f, mono[i], 0f)
        for (i in 512 until 1024) assertEquals(stamp(8_000L - 512 + i), mono[i], 0f)
    }

    /**
     * T14: a writer at audio cadence and a reader at 60 Hz. Every buffer carries its own index in all
     * its samples, so a torn window shows up as a sample that disagrees with its frame.
     */
    @Test
    fun concurrentReaderNeverSeesATornWindow() {
        val ring = SampleRing()
        ring.reconfigure(48_000)
        val written = AtomicLong(0)
        val stop = AtomicBoolean(false)
        val bufferFrames = 960
        // Inside ±1, since decoding clips overs.
        fun value(f: Long): Float = (f / bufferFrames % 4096 + 1) / 8192f
        val writer = Thread {
            var f = 0L
            while (!stop.get()) {
                val b = floats(f, bufferFrames, ::value)
                ring.write(b, 0, b.limit(), PcmFormat.F32, 1, f)
                f += bufferFrames
                written.set(f)
                Thread.sleep(0, 200_000)
            }
        }
        writer.start()
        val deadline = System.nanoTime() + STRESS_NANOS
        var reads = 0
        var torn = 0
        val mono = FloatArray(1024)
        val l = FloatArray(512)
        val r = FloatArray(512)
        while (System.nanoTime() < deadline) {
            val p = written.get() - 2_000
            if (p > 1_000 && ring.read(p, mono, l, r)) {
                for (i in mono.indices) {
                    val f = p - 512 + i
                    if (mono[i] != 0f && mono[i] != value(f)) torn++
                }
                reads++
            }
            Thread.sleep(0, 500_000)
        }
        stop.set(true)
        writer.join()
        assertTrue("reads=$reads", reads > 100)
        assertEquals(0, torn)
    }

    private companion object {
        /** 30 s per the plan when run alone; the default gate run keeps it short. */
        val STRESS_NANOS = (System.getProperty("viz.stressSeconds")?.toLong() ?: 3L) * 1_000_000_000L
    }
}
