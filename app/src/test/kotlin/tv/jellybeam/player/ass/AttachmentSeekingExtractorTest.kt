package tv.jellybeam.player.ass

import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Runs the real MatroskaExtractor over a synthetic MKV (attachments before the first cluster, like
 * fansub muxes) with the seek threshold lowered to 1 KB, so a Media3 change that breaks resuming an
 * interrupted skip fails here instead of in playback.
 */
class AttachmentSeekingExtractorTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun giveBuildAFingerprint() = fakeBuildFingerprint()
    }

    private val file = requireNotNull(javaClass.getResource("/ass/attachments.mkv")).readBytes()
    private val flags = matroskaFlags(transcoding = false, parseHagc = true)

    private class Sink(private val wants: Boolean) : AssFontSink {
        val blocks = mutableListOf<AttachmentsBlock>()
        override fun wantsFonts() = wants
        override fun attachments(block: AttachmentsBlock) {
            blocks += block
        }
    }

    /** Sample counts per track, as Media3 would hand them to renderers. */
    private class Samples : ExtractorOutput {
        val counts = sortedMapOf<Int, Int>()
        override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput {
            private val scratch = ByteArray(64 * 1024)
            override fun format(format: Format) = Unit
            override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int =
                input.read(scratch, 0, minOf(length, scratch.size))
            override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
                data.skipBytes(length)
            }
            override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                counts.merge(id, 1, Int::plus)
            }
        }
        override fun endTracks() = Unit
        override fun seekMap(seekMap: SeekMap) = Unit
    }

    private class Result(val samples: Map<Int, Int>, val bytesRead: Long)

    private fun extract(extractor: Extractor): Result {
        val output = Samples()
        val bytesRead = extractAll(file, extractor, output)
        return Result(output.counts, bytesRead)
    }

    private fun plain() = extract(MatroskaExtractor(DefaultSubtitleParserFactory(), flags))

    private fun wrapped(sink: Sink, seekPastBytes: Int) =
        extract(AttachmentSeekingExtractor(AssMatroskaExtractor(DefaultSubtitleParserFactory(), flags, sink, seekPastBytes)))

    @Test
    fun `styling off jumps the whole attachments block and keeps every sample`() {
        val baseline = plain()
        val sink = Sink(wants = false)
        val skipped = wrapped(sink, seekPastBytes = 1024)
        assertEquals("video and subtitle samples unchanged", baseline.samples, skipped.samples)
        assertTrue("the 12 KB of attachments were not read: ${skipped.bytesRead}", skipped.bytesRead < baseline.bytesRead - 10_000)
        assertTrue(sink.blocks.isEmpty())
    }

    @Test
    fun `styling on reads exactly what styling off does, and its fonts are found from the block afterwards`() {
        val off = wrapped(Sink(wants = false), seekPastBytes = 1024)
        val sink = Sink(wants = true)
        val styled = wrapped(sink, seekPastBytes = 1024)
        assertEquals(plain().samples, styled.samples)
        assertEquals("no font byte before the first frame", off.bytesRead, styled.bytesRead)
        val block = sink.blocks.single()
        var reads = 0
        val reader = file.rangeReader { reads++ }
        val fonts = attachedFonts(block, reader, maxFontBytes = 1L shl 20)
        assertEquals(listOf("one.otf", "two.otf", "three.otf"), fonts.map { it.name })
        assertEquals(listOf(3004, 3504, 4004), fonts.map { it.size })
        for (font in fonts) {
            assertEquals("${font.name} starts at its data", "OTTO", String(file, font.offset.toInt(), 4, Charsets.US_ASCII))
        }
        assertEquals("one short read per element: a CRC-32, three fonts and a cover", 5, reads)
    }

    @Test
    fun `below the threshold the block reads through and is still recorded`() {
        val baseline = plain()
        val sink = Sink(wants = true)
        val through = wrapped(sink, seekPastBytes = SEEK_PAST_ATTACHMENTS_BYTES)
        assertEquals(baseline.samples, through.samples)
        assertEquals("nothing jumped", baseline.bytesRead, through.bytesRead)
        val block = sink.blocks.single()
        assertEquals(listOf(3004, 3504, 4004), attachedFonts(block, file.rangeReader(), maxFontBytes = 1L shl 20).map { it.size })
    }
}
