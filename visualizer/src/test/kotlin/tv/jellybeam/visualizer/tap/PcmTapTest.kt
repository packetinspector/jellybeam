package tv.jellybeam.visualizer.tap

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.jellybeam.visualizer.dsp.Signals

/** docs/30 §9 T9, T10, T13: the tap is invisible to the output and free without a listener. */
class PcmTapTest {
    private fun pcm16(frames: Int, channels: Int, valueOf: (Int, Int) -> Short): ByteBuffer {
        val b = ByteBuffer.allocateDirect(frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (f in 0 until frames) for (c in 0 until channels) b.putShort(valueOf(f, c))
        b.flip()
        return b
    }

    private fun crc(b: ByteBuffer): Long {
        val c = CRC32()
        for (i in 0 until b.capacity()) c.update(b.get(i).toInt())
        return c.value
    }

    private fun allocated(): Long = Signals.allocatedBytes()

    @Test
    fun withoutListenerOfferLeavesBufferUntouchedAndAllocatesNothing() {
        val tap = PcmTap()
        tap.configure(ENCODING_PCM_16BIT, 48_000, 2, offload = false)
        val buf = pcm16(1024, 2) { f, c -> (f * 7 + c).toShort() }
        buf.position(256)
        val sum = crc(buf)
        repeat(1000) { tap.offer(buf, buf.position(), buf.limit()) }
        allocated()
        val before = allocated()
        repeat(10_000) { tap.offer(buf, buf.position(), buf.limit()) }
        val after = allocated()
        assertEquals(0L, after - before - Signals.allocationOverhead())
        assertEquals(256, buf.position())
        assertEquals(buf.capacity(), buf.limit())
        assertEquals(sum, crc(buf))
        assertEquals(0L, tap.offeredToRing)
        assertFalse(tap.isAttached)
    }

    @Test
    fun withListenerOfferStillLeavesBufferUntouched() {
        val tap = PcmTap()
        tap.configure(ENCODING_PCM_16BIT, 48_000, 2, offload = false)
        val ring = SampleRing()
        tap.attach(ring)
        val buf = pcm16(1024, 2) { f, c -> (f * 13 - c).toShort() }
        val sameBuffer = buf
        val sum = crc(buf)
        repeat(50) { tap.offer(buf, 0, buf.limit()) }
        assertSame(sameBuffer, buf)
        assertEquals(0, buf.position())
        assertEquals(buf.capacity(), buf.limit())
        assertEquals(sum, crc(buf))
        assertEquals(50L, tap.offeredToRing)
    }

    @Test
    fun encodedOutputIsReportedAndNeverWritten() {
        val tap = PcmTap()
        assertEquals(TapState.IDLE, tap.state)
        tap.configure(ENCODING_AC3, 48_000, 6, offload = false)
        assertEquals(TapState.ENCODED, tap.state)
        val ring = SampleRing()
        tap.attach(ring)
        repeat(10) { tap.offer(pcm16(256, 2) { _, _ -> 1000 }, 0, 1024) }
        assertEquals(0L, tap.offeredToRing)
        tap.configure(ENCODING_PCM_16BIT, 48_000, 2, offload = true)
        assertEquals(TapState.ENCODED, tap.state)
        tap.configure(ENCODING_PCM_FLOAT, 48_000, 2, offload = false)
        assertEquals(TapState.PCM, tap.state)
    }

    @Test
    fun lateAttachIndexesFramesFromTheOutputStart() {
        val tap = PcmTap()
        tap.configure(ENCODING_PCM_16BIT, 48_000, 1, offload = false)
        // 10 000 frames go by unrecorded, then the listener attaches.
        tap.offer(pcm16(10_000, 1) { _, _ -> 0 }, 0, 20_000)
        val ring = SampleRing()
        tap.attach(ring)
        tap.offer(pcm16(4096, 1) { f, _ -> (f + 1).toShort() }, 0, 8192)
        val mono = FloatArray(2)
        val l = FloatArray(1)
        val r = FloatArray(1)
        assertTrue(ring.read(10_001, mono, l, r))
        // mono covers [center-1, center+1): frames 10 000 and 10 001 are the 1st and 2nd new samples.
        assertArrayEquals(floatArrayOf(1f / 32768f, 2f / 32768f), mono, 1e-7f)
        // Frames before the attach read as silence, never as stale audio.
        assertFalse(ring.read(9_000, FloatArray(4), FloatArray(1), FloatArray(1)))
    }

    @Test
    fun positionExtrapolatesOnlyWhileRunning() {
        val tap = PcmTap()
        tap.configure(ENCODING_PCM_16BIT, 48_000, 1, offload = false)
        val ring = SampleRing()
        tap.attach(ring)
        tap.offer(pcm16(256, 1) { _, _ -> 0 }, 0, 512)
        tap.onPosition(1_000_000, 0L, playing = true, speed = 1f)
        assertEquals(48_000.0, tap.position(0L), 1e-6)
        assertEquals(48_000.0 + 4_800.0, tap.position(100_000_000L), 1e-6)
        tap.onPosition(2_000_000, 0L, playing = false, speed = 1f)
        assertEquals(96_000.0, tap.position(500_000_000L), 1e-6)
    }

    @Test
    fun pauseFreezesThePositionAndAudioAfterAPausedAttachWakesTheReader() {
        val tap = PcmTap()
        tap.configure(ENCODING_PCM_16BIT, 48_000, 1, offload = false)
        val first = SampleRing()
        tap.attach(first)
        tap.offer(pcm16(256, 1) { _, _ -> 0 }, 0, 512)
        tap.onPosition(1_000_000, 0L, playing = true, speed = 1f)
        tap.pause(100_000_000L)
        assertFalse(tap.isRunning)
        assertEquals(48_000.0 + 4_800.0, tap.position(900_000_000L), 1e-6)
        // Detached while the clock changes, then re-attached while paused: the new ring has no audio, so
        // the reader idles; the first buffer after play must wake it.
        tap.detach(first)
        val second = SampleRing()
        var wakes = 0
        tap.onResume = Runnable { wakes++ }
        tap.attach(second)
        assertEquals(0, second.sampleRate)
        tap.offer(pcm16(256, 1) { _, _ -> 0 }, 0, 512)
        assertTrue(wakes >= 1)
    }

    @Test
    fun anOlderReadersDetachLeavesANewerAttachInPlace() {
        val tap = PcmTap()
        val old = SampleRing()
        val newer = SampleRing()
        tap.attach(old)
        tap.attach(newer)
        tap.detach(old)
        assertTrue(tap.isAttached)
        tap.detach(newer)
        assertFalse(tap.isAttached)
    }

    companion object {
        // android.media.AudioFormat values, inlined so the JVM test needs no Android stubs.
        const val ENCODING_PCM_16BIT = 2
        const val ENCODING_PCM_FLOAT = 4
        const val ENCODING_AC3 = 5
    }
}
