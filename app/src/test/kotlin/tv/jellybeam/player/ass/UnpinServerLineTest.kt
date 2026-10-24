package tv.jellybeam.player.ass

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.common.util.Consumer
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import org.junit.Assert.assertEquals
import org.junit.Test

class UnpinServerLineTest {
    private fun cue(line: Float, lineType: Int) =
        Cue.Builder().setText("line").setLine(line, lineType).setLineAnchor(Cue.ANCHOR_TYPE_START).build()

    @Test
    fun `a cue on the bottom line follows the viewer's position`() {
        for (bottom in listOf(cue(-1f, Cue.LINE_TYPE_NUMBER), cue(0.9f, Cue.LINE_TYPE_FRACTION))) {
            val unpinned = unpinServerLine(bottom)
            assertEquals(Cue.DIMEN_UNSET, unpinned.line)
            assertEquals(Cue.TYPE_UNSET, unpinned.lineType)
            assertEquals(Cue.TYPE_UNSET, unpinned.lineAnchor)
            assertEquals("line", unpinned.text.toString())
        }
    }

    @Test
    fun `a cue placed anywhere else keeps its line`() {
        for (placed in listOf(cue(0.1f, Cue.LINE_TYPE_FRACTION), cue(0f, Cue.LINE_TYPE_NUMBER), cue(-2f, Cue.LINE_TYPE_NUMBER))) {
            assertEquals(placed, unpinServerLine(placed))
        }
    }

    @Test
    fun `only WebVTT is unpinned`() {
        val pinned = cue(0.9f, Cue.LINE_TYPE_FRACTION)
        val delegate = object : SubtitleParser.Factory {
            override fun supportsFormat(format: Format) = true
            override fun getCueReplacementBehavior(format: Format) = Format.CUE_REPLACEMENT_BEHAVIOR_MERGE
            override fun create(format: Format) = object : SubtitleParser {
                override fun parse(data: ByteArray, offset: Int, length: Int, outputOptions: SubtitleParser.OutputOptions, output: Consumer<CuesWithTiming>) =
                    output.accept(CuesWithTiming(listOf(pinned), 0L, 1_000L))
                override fun getCueReplacementBehavior() = Format.CUE_REPLACEMENT_BEHAVIOR_MERGE
            }
        }
        fun parsedLine(mime: String): Float {
            var line = Float.NaN
            AssAwareSubtitleParserFactory(delegate).create(Format.Builder().setSampleMimeType(mime).build())
                .parse(ByteArray(0), SubtitleParser.OutputOptions.allCues()) { line = it.cues.single().line }
            return line
        }
        assertEquals(Cue.DIMEN_UNSET, parsedLine(MimeTypes.TEXT_VTT))
        assertEquals(0.9f, parsedLine(MimeTypes.APPLICATION_SUBRIP))
    }
}
