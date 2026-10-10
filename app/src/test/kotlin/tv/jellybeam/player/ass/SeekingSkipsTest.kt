package tv.jellybeam.player.ass

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.ExtractorInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SeekingSkipsTest {
    private val bytes = ByteArray(3 * SEEK_PAST_ATTACHMENTS_BYTES) { it.toByte() }

    /** The file from [position], as Media3 hands it to an extractor after (re)opening the stream there. */
    private fun inputAt(position: Long): ExtractorInput {
        var next = position.toInt()
        val reader = DataReader { buffer, offset, length ->
            if (next >= bytes.size) return@DataReader C.RESULT_END_OF_INPUT
            val n = minOf(length, bytes.size - next)
            bytes.copyInto(buffer, offset, next, next + n)
            next += n
            n
        }
        return DefaultExtractorInput(reader, position, bytes.size.toLong())
    }

    @Test
    fun `only a large unread attachments block is worth a seek`() {
        assertTrue(seeksPastAttachments(unread = true, bytes = SEEK_PAST_ATTACHMENTS_BYTES))
        assertFalse(seeksPastAttachments(unread = true, bytes = SEEK_PAST_ATTACHMENTS_BYTES - 1))
        assertFalse(seeksPastAttachments(unread = false, bytes = 60 * SEEK_PAST_ATTACHMENTS_BYTES))
    }

    @Test
    fun `an armed large skip becomes a seek and resumes as a no-op at the target`() {
        val skips = SeekingSkips(SEEK_PAST_ATTACHMENTS_BYTES) { true }
        val size = 2 * SEEK_PAST_ATTACHMENTS_BYTES
        val first = skips.wrap(inputAt(100))
        try {
            first.skipFully(size)
            fail("expected a seek")
        } catch (seek: SeekPast) {
            assertEquals(100L + size, seek.target)
        }
        assertEquals("nothing was read", 100L, first.position)

        val resumed = skips.wrap(inputAt(100L + size))
        resumed.skipFully(size)
        assertEquals(100L + size, resumed.position)
        assertEquals((100 + size).toByte(), ByteArray(1).also { resumed.readFully(it, 0, 1) }[0])
    }

    @Test
    fun `small or unarmed skips read through`() {
        val unarmed = SeekingSkips(SEEK_PAST_ATTACHMENTS_BYTES) { false }.wrap(inputAt(0))
        unarmed.skipFully(2 * SEEK_PAST_ATTACHMENTS_BYTES)
        assertEquals(2L * SEEK_PAST_ATTACHMENTS_BYTES, unarmed.position)

        val small = SeekingSkips(SEEK_PAST_ATTACHMENTS_BYTES) { true }.wrap(inputAt(0))
        small.skipFully(10)
        assertEquals(10L, small.position)
    }

    @Test
    fun `a player seek drops a pending resume`() {
        var armed = true
        val skips = SeekingSkips(SEEK_PAST_ATTACHMENTS_BYTES) { armed }
        val size = SEEK_PAST_ATTACHMENTS_BYTES
        assertTrue(runCatching { skips.wrap(inputAt(0)).skipFully(size) }.exceptionOrNull() is SeekPast)
        skips.reset()
        armed = false
        val input = skips.wrap(inputAt(size.toLong()))
        input.skipFully(size)
        assertEquals("read through, not resumed", 2L * size, input.position)
    }
}
