package tv.jellybeam.player.ass

import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.BeforeClass
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * docs/09: lines that began before a start, seek or track switch are read back from the Cues. Runs the
 * real MatroskaExtractor over a synthetic MKV (two long lines spanning later times, three short ones)
 * and checks the rebuilt samples are byte for byte what Media3 itself emits.
 */
class MatroskaCuesTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun giveBuildAFingerprint() = fakeBuildFingerprint()
    }

    private val file = requireNotNull(javaClass.getResource("/ass/spans.mkv")).readBytes()
    private val reader = file.rangeReader()

    private class Sink : AssFontSink {
        var cues: SubtitleCues? = null
        var scaleNs = 0L
        override fun wantsFonts() = true
        override fun attachments(block: AttachmentsBlock) = Unit
        override fun subtitleCues(cues: SubtitleCues, timecodeScaleNs: Long) {
            this.cues = cues
            scaleNs = timecodeScaleNs
        }
    }

    /** Every SSA sample Media3 emits: (time µs, bytes), plus the track's Format id. */
    private class SsaSamples : ExtractorOutput {
        val samples = mutableListOf<Pair<Long, ByteArray>>()
        var trackId: String? = null
        override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput {
            private val pending = ByteArrayOutputStream()
            private var ssa = false
            override fun format(format: Format) {
                ssa = isRawSsa(format)
                if (ssa) trackId = format.id
            }
            override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
                val buffer = ByteArray(length)
                val n = input.read(buffer, 0, length)
                if (n > 0) pending.write(buffer, 0, n)
                return n
            }
            override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
                val buffer = ByteArray(length)
                data.readBytes(buffer, 0, length)
                pending.write(buffer)
            }
            override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                val all = pending.toByteArray()
                val end = all.size - offset
                if (ssa) samples += timeUs to all.copyOfRange(end - size, end)
                pending.reset()
                pending.write(all, end, offset)
            }
        }
        override fun endTracks() = Unit
        override fun seekMap(seekMap: SeekMap) = Unit
    }

    /** Media3's loader loop over the fixture, through the app's own extractor stack with styling on. */
    private fun extract(sink: Sink): SsaSamples {
        val output = SsaSamples()
        val extractor = AttachmentSeekingExtractor(
            AssMatroskaExtractor(AssAwareSubtitleParserFactory(), matroskaFlags(transcoding = true, parseHagc = true), sink),
        )
        extractAll(file, extractor, output)
        return output
    }

    @Test
    fun `the extractor records one cue per subtitle block, with its duration`() {
        val sink = Sink()
        val media3 = extract(sink)
        val cues = requireNotNull(sink.cues) { "no subtitle cues recorded" }
        assertEquals(1_000_000L, sink.scaleNs)
        assertEquals(5, cues.count)
        assertEquals("the renderer's track key is the cue track number", "2", media3.trackId?.let { assTrackKey(Format.Builder().setId(it).build()) })
        assertEquals(5, media3.samples.size)
    }

    @Test
    fun `lines read back from the cues are byte for byte what Media3 emits`() {
        val sink = Sink()
        val media3 = extract(sink).samples.associate { it.first to it.second }
        val cues = requireNotNull(sink.cues)
        for (start in media3.keys.sorted()) {
            val wanted = cues.spanning(track = 2, positionUs = start + 1).filter { it.timeUs == start }
            val rebuilt = spanningSamples(wanted, sink.scaleNs, reader)
            assertEquals("one sample for the line at $start", 1, rebuilt.size)
            assertEquals(start, rebuilt.single().first)
            assertArrayEquals("line at $start", media3.getValue(start), rebuilt.single().second)
        }
    }

    /** docs/09 matrix: which lines a start, seek or switch at a time must read back. */
    @Test
    fun `only lines that began earlier and still show are read back`() {
        val sink = Sink()
        extract(sink)
        val cues = requireNotNull(sink.cues)
        fun at(seconds: Double) = cues.spanning(track = 2, positionUs = (seconds * 1_000_000).toLong()).map { it.timeUs / 1000 }
        assertEquals("before anything", emptyList<Long>(), at(0.5))
        assertEquals("inside long one only", listOf(1_000L), at(3.5))
        assertEquals("long one, short at 2 still showing", listOf(1_000L, 2_000L), at(2.5))
        assertEquals("both long lines and the short at 5", listOf(1_000L, 4_000L, 5_000L), at(5.5))
        assertEquals("long one has ended at 10", listOf(4_000L), at(10.5))
        assertEquals("a line starting exactly now is Media3's to read", listOf(1_000L), at(4.0))
        assertEquals("nothing after the last line ends", emptyList<Long>(), at(11.6))
        assertEquals("another track has none", emptyList<SubtitleCue>(), cues.spanning(track = 1, positionUs = 5_500_000))
    }

    @Test
    fun `cues without a duration count for mpv's preroll window`() {
        val cues = SubtitleCues(setOf(3))
        cues.add(SubtitleCue(3, timeUs = 1_000_000, durationUs = 0, clusterOffset = 0, relativePosition = 0))
        cues.add(SubtitleCue(3, timeUs = 20_000_000, durationUs = 0, clusterOffset = 0, relativePosition = 0))
        assertEquals(listOf(1_000_000L), cues.spanning(3, positionUs = 1_000_000 + UNKNOWN_SPAN_US - 1).map { it.timeUs })
        assertEquals(emptyList<Long>(), cues.spanning(3, positionUs = 1_000_000 + UNKNOWN_SPAN_US).map { it.timeUs })
    }

    @Test
    fun `a block is read back only with its own BlockDuration, whatever the cue says`() {
        // Cluster holding one block, track 1, timecode 0, flags 0, payload "ab"; BlockGroup durations are in 1 ms ticks.
        val simple = byteArrayOf(0xA3.toByte(), 0x86.toByte(), 0x81.toByte(), 0, 0, 0, 'a'.code.toByte(), 'b'.code.toByte())
        fun group(durationTicks: Int?): ByteArray {
            val block = byteArrayOf(0xA1.toByte(), 0x86.toByte(), 0x81.toByte(), 0, 0, 0, 'a'.code.toByte(), 'b'.code.toByte())
            val duration = durationTicks?.let { byteArrayOf(0x9B.toByte(), 0x81.toByte(), it.toByte()) } ?: byteArrayOf()
            val content = block + duration
            return byteArrayOf(0xA0.toByte(), (0x80 or content.size).toByte()) + content
        }
        fun cluster(element: ByteArray) =
            byteArrayOf(0x1F, 0x43, 0xB6.toByte(), 0x75, (0x80 or element.size).toByte()) + element
        // (block, own duration in µs or null) rows; each is tried with a cue duration of 0 and of 2 s.
        val rows = listOf(
            "SimpleBlock" to (simple to null),
            "BlockGroup without BlockDuration" to (group(null) to null),
            "BlockGroup with BlockDuration 0" to (group(0) to 0L),
            "BlockGroup with BlockDuration 3 ms" to (group(3) to 3_000L),
        )
        for ((name, row) in rows) {
            val (element, own) = row
            for (cueDuration in listOf(0L, 2_000_000L)) {
                val cue = SubtitleCue(1, timeUs = 0, durationUs = cueDuration, clusterOffset = 0, relativePosition = 0)
                val out = spanningSamples(listOf(cue), 1_000_000, cluster(element).rangeReader())
                val label = "$name, cue duration $cueDuration"
                if (own != null && own > 0) {
                    assertArrayEquals(label, ssaSample("ab".toByteArray(), own), out.single().second)
                } else {
                    assertEquals(label, emptyList<Pair<Long, ByteArray>>(), out)
                }
            }
        }
    }

    @Test
    fun `untracked tracks are not kept and the list is bounded`() {
        val cues = SubtitleCues(setOf(3))
        cues.add(SubtitleCue(4, 0, 1_000, 0, 0))
        assertEquals(0, cues.count)
        repeat(MAX_SPANNING_LINES + 5) { cues.add(SubtitleCue(3, it.toLong(), 1_000_000_000, 0, 0)) }
        assertEquals("at most MAX_SPANNING_LINES, the latest ones", MAX_SPANNING_LINES, cues.spanning(3, 1_000_000).size)
        assertEquals(MAX_SPANNING_LINES + 4L, cues.spanning(3, 1_000_000).last().timeUs)
    }

    @Test
    fun `a laced or truncated block is not read back`() {
        // SimpleBlock: ID A3, size 6, track 0x81, timecode 0000, flags 0x02 (Xiph lacing), payload "ab".
        val laced = byteArrayOf(0xA3.toByte(), 0x86.toByte(), 0x81.toByte(), 0, 0, 0x02, 'a'.code.toByte(), 'b'.code.toByte())
        assertNull(subtitleBlock(laced, 0, 1_000_000))
        val plain = laced.copyOf().also { it[5] = 0 }
        assertArrayEquals("ab".toByteArray(), subtitleBlock(plain, 0, 1_000_000)?.payload)
        assertNull("size runs past the bytes", subtitleBlock(plain.copyOf(6), 0, 1_000_000))
    }

    @Test
    fun `the sample prefix ends at the duration in h mm ss cc`() {
        assertEquals("Dialogue: 0:00:00:00,1:02:03:45,x", String(ssaSample("x".toByteArray(), 3_723_456_789)))
    }
}
