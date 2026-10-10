package tv.jellybeam.player.ass

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.common.util.Consumer
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.jellybeam.player.SidecarCues
import uniffi.jellybeam_core.AssOverlay
import uniffi.jellybeam_core.NoHandle

class PlainSsaTest {
    private val rawSsa = Format.Builder().setSampleMimeType(MimeTypes.TEXT_SSA).build()

    private fun cue(text: String) = Cue.Builder().setText(text).build()

    private fun support(capabilities: Int) = RendererCapabilities.getFormatSupport(capabilities)

    @Test
    fun `no ssa track is parsed while the file is read, styled or not, other text still is`() {
        val factory = AssAwareSubtitleParserFactory()
        assertFalse(factory.supportsFormat(rawSsa))
        assertTrue(factory.supportsFormat(Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_SUBRIP).build()))
    }

    @Test
    fun `the styled renderer takes raw ssa only with full styling on, else the plain one does`() {
        val overlay = AssOverlay(NoHandle)
        assertEquals(C.FORMAT_HANDLED, support(AssTextRenderer({ overlay }, { true }).supportsFormat(rawSsa)))
        assertEquals(C.FORMAT_UNSUPPORTED_SUBTYPE, support(AssTextRenderer({ overlay }, { false }).supportsFormat(rawSsa)))
        assertEquals(C.FORMAT_UNSUPPORTED_SUBTYPE, support(AssTextRenderer({ null }, { true }).supportsFormat(rawSsa)))
        val plain = PlainSsaRenderer(TextOutput { }) { it.run() }
        assertEquals(C.FORMAT_HANDLED, support(plain.supportsFormat(rawSsa)))
    }

    @Test
    fun `overlapping lines all show until each ends`() {
        val lines = PlainSsaLines()
        val a = cue("a")
        val b = cue("b")
        lines.add(0, 2_000_000, listOf(a))
        lines.add(1_000_000, 3_000_000, listOf(b))
        assertEquals(listOf(a), lines.at(500_000))
        assertEquals(listOf(a, b), lines.at(1_500_000))
        assertEquals(listOf(b), lines.at(2_000_000))
        assertTrue(lines.at(3_000_000).isEmpty())
    }

    @Test
    fun `the same list is returned until what shows changes`() {
        val lines = PlainSsaLines()
        lines.add(0, 2_000_000, listOf(cue("a")))
        val first = lines.at(100_000)
        assertSame(first, lines.at(900_000))
        lines.add(1_000_000, 2_000_000, listOf(cue("b")))
        assertNotSame(first, lines.at(1_000_000))
    }

    @Test
    fun `at most the cap shows, and a line past it is never held`() {
        val lines = PlainSsaLines(cap = 2)
        repeat(2) { lines.add(0, 5_000_000, listOf(cue("$it"))) }
        assertFalse(lines.hasRoom(1_000_000))
        assertTrue("ended lines leave room", lines.hasRoom(5_000_000))
        assertEquals(2, lines.at(1_000_000).size)
        assertEquals(SidecarCues.MAX_VISIBLE, PlainSsaLines().let { l ->
            repeat(20) { l.add(0, 1_000_000, listOf(cue("$it"))) }
            l.at(0).size
        })
    }

    /** A cue where SsaParser puts a line without `\pos`: [line] with [anchor], centred unless [position] says otherwise. */
    private fun placed(text: String, line: Float, anchor: Int, position: Float = 0.5f, positionAnchor: Int = Cue.ANCHOR_TYPE_MIDDLE) =
        Cue.Builder().setText(text).setLine(line, Cue.LINE_TYPE_FRACTION).setLineAnchor(anchor)
            .setPosition(position).setPositionAnchor(positionAnchor).build()

    private fun bottom(text: String) = placed(text, 0.95f, Cue.ANCHOR_TYPE_END)

    private fun top(text: String) = placed(text, 0.05f, Cue.ANCHOR_TYPE_START)

    @Test
    fun `lines sharing a spot stack as libass stacks them, positioned lines keep their own place`() {
        val sign = placed("sign", 0.3f, Cue.ANCHOR_TYPE_END)
        val left = placed("left", 0.95f, Cue.ANCHOR_TYPE_END, position = 0.05f, positionAnchor = Cue.ANCHOR_TYPE_START)
        val cues = listOf(bottom("first"), top("title"), sign, bottom("second"), left, top("subtitle"), bottom("third"))
        val stacks = plainSsaStacks(cues).map { (spot, group) -> spot to group.map { it.text.toString() } }
        assertEquals(
            listOf(
                SsaSpot.BOTTOM to listOf("third", "second", "first"),
                SsaSpot.TOP to listOf("title", "subtitle"),
                null to listOf("sign"),
                SsaSpot.BOTTOM to listOf("left"),
            ),
            stacks,
        )
    }

    @Test
    fun `a bottom line loses its fixed spot so the view's padding lifts it over the OSD, others are untouched`() {
        val sign = placed("sign", 0.95f, Cue.ANCHOR_TYPE_MIDDLE)
        val title = top("title")
        val arranged = arrangePlainSsa(listOf(bottom("line"), title, sign))
        assertEquals(Cue.DIMEN_UNSET, arranged[0].line)
        assertEquals("line", arranged[0].text.toString())
        assertSame(title, arranged[1])
        assertSame("a line SsaParser didn't place by alignment", sign, arranged[2])
        val plain = listOf(cue("srt"))
        assertSame("no SSA spots: the same list", plain, arrangePlainSsa(plain))
    }

    /** Stands in for SsaParser, which needs Android's TextUtils: a sample's cue starts at 0 for its duration. */
    private class SampleParser(private val durationUs: Long) : SubtitleParser {
        override fun parse(
            data: ByteArray,
            offset: Int,
            length: Int,
            outputOptions: SubtitleParser.OutputOptions,
            output: Consumer<CuesWithTiming>,
        ) {
            val text = String(data, offset, length)
            require(text.startsWith("Dialogue:")) { "not a dialogue line" }
            output.accept(CuesWithTiming(listOf(Cue.Builder().setText(text.substringAfterLast(',')).build()), 0, durationUs))
        }

        override fun getCueReplacementBehavior(): Int = Format.CUE_REPLACEMENT_BEHAVIOR_MERGE
    }

    @Test
    fun `a sample's line starts at its media time and runs its duration, a malformed one is dropped`() {
        val parser = SampleParser(durationUs = 2_500_000)
        val lines = PlainSsaLines()
        lines.addSample(parser, 10_000_000, ssaSample("1,0,Default,,0,0,0,,Hello".toByteArray(), 2_500_000))
        lines.addSample(parser, 11_000_000, "not a dialogue line".toByteArray())
        assertTrue(lines.at(9_999_999).isEmpty())
        assertEquals("Hello", lines.at(10_000_000).single().text.toString())
        assertEquals(1, lines.at(12_499_999).size)
        assertTrue(lines.at(12_500_000).isEmpty())
    }

    @Test
    fun `a sample is not parsed while the cap's worth of lines still runs`() {
        var parsed = 0
        val parser = object : SubtitleParser by SampleParser(1_000_000) {
            override fun parse(
                data: ByteArray,
                offset: Int,
                length: Int,
                outputOptions: SubtitleParser.OutputOptions,
                output: Consumer<CuesWithTiming>,
            ) {
                parsed++
                output.accept(CuesWithTiming(listOf(cue("x")), 0, 1_000_000))
            }
        }
        val lines = PlainSsaLines(cap = 2)
        repeat(5) { lines.addSample(parser, 0, "Dialogue:".toByteArray()) }
        assertEquals(2, parsed)
    }

    @Test
    fun `a drain hands over at most its cap, and the rest come on the next call`() {
        val times = ArrayDeque((0 until 5).map { it * 100_000L })
        val samples = LookaheadSamples { _: FormatHolder, buffer: DecoderInputBuffer ->
            if (times.isEmpty()) {
                buffer.addFlag(C.BUFFER_FLAG_END_OF_STREAM)
            } else {
                buffer.timeUs = times.removeFirst()
                buffer.ensureSpaceForWrite(1)
                buffer.data!!.put(1.toByte())
            }
            C.RESULT_BUFFER_READ
        }
        val got = mutableListOf<Long>()
        samples.drain(mediaUs = 0, offsetUs = 0, onFormat = {}, maxSamples = 2) { t, _ -> got += t }
        assertEquals(listOf(0L, 100_000L), got)
        samples.drain(mediaUs = 0, offsetUs = 0, onFormat = {}, maxSamples = 2) { t, _ -> got += t }
        samples.drain(mediaUs = 0, offsetUs = 0, onFormat = {}, maxSamples = 2) { t, _ -> got += t }
        assertEquals((0 until 5).map { it * 100_000L }, got)
        assertTrue(samples.ended)
    }

    @Test
    fun `a render pass drains at most the per-render cap, across several passes`() {
        val total = MAX_SAMPLES_PER_RENDER * 2 + 5
        var next = 0
        val samples = LookaheadSamples { _: FormatHolder, buffer: DecoderInputBuffer ->
            if (next == total) {
                buffer.addFlag(C.BUFFER_FLAG_END_OF_STREAM)
            } else {
                buffer.timeUs = (next++).toLong()
                buffer.ensureSpaceForWrite(1)
                buffer.data!!.put(1.toByte())
            }
            C.RESULT_BUFFER_READ
        }
        var got = 0
        samples.drain(mediaUs = 0, offsetUs = 0, onFormat = {}, maxSamples = MAX_SAMPLES_PER_RENDER) { _, _ -> got++ }
        assertEquals(MAX_SAMPLES_PER_RENDER, got)
        samples.drain(mediaUs = 0, offsetUs = 0, onFormat = {}, maxSamples = MAX_SAMPLES_PER_RENDER) { _, _ -> got++ }
        samples.drain(mediaUs = 0, offsetUs = 0, onFormat = {}, maxSamples = MAX_SAMPLES_PER_RENDER) { _, _ -> got++ }
        assertEquals(total, got)
        assertTrue(samples.ended)
    }

    @Test
    fun `samples past the lookahead wait in the stream, and the end of input is kept`() {
        val times = ArrayDeque(listOf(0L, 1_000_000L, 5_000_000L))
        var formatsRead = 0
        val samples = LookaheadSamples { holder: FormatHolder, buffer: DecoderInputBuffer ->
            when {
                formatsRead == 0 -> {
                    formatsRead++
                    holder.format = rawSsa
                    C.RESULT_FORMAT_READ
                }
                times.isEmpty() -> {
                    buffer.addFlag(C.BUFFER_FLAG_END_OF_STREAM)
                    C.RESULT_BUFFER_READ
                }
                else -> {
                    buffer.timeUs = times.removeFirst()
                    buffer.ensureSpaceForWrite(1)
                    buffer.data!!.put(1.toByte())
                    C.RESULT_BUFFER_READ
                }
            }
        }
        val got = mutableListOf<Long>()
        val formats = mutableListOf<Format>()
        samples.drain(mediaUs = 0, offsetUs = 0, onFormat = { formats += it }) { t, _ -> got += t }
        assertEquals(listOf(rawSsa), formats)
        assertEquals(listOf(0L, 1_000_000L), got)
        samples.drain(mediaUs = 1_000_000, offsetUs = 0, onFormat = {}) { t, _ -> got += t }
        assertEquals("still past the lookahead", 2, got.size)
        samples.drain(mediaUs = 2_000_000, offsetUs = 0, onFormat = {}) { t, _ -> got += t }
        assertEquals(listOf(0L, 1_000_000L, 5_000_000L), got)
        assertTrue(samples.ended)
    }
}
