package tv.jellybeam.player.ass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/13: an old item's extractor thread must not write into the next item's attachments or cues. */
class ItemScopedFontSinkTest {
    private var generation = 1L
    private val attachments = mutableListOf<AttachmentsBlock>()
    private val cues = mutableListOf<SubtitleCues>()
    private val root = ItemScopedFontSink(
        generation = { generation },
        lock = Any(),
        wants = { true },
        onAttachments = { attachments += it },
        onCues = { c, _ -> cues += c },
    )

    @Test
    fun `a sink made for an older generation drops its calls, the current one is recorded`() {
        val old = root.forItem()
        generation++
        val current = root.forItem()
        val staleBlock = AttachmentsBlock(1, 10)
        val freshBlock = AttachmentsBlock(2, 20)
        val staleCues = SubtitleCues(setOf(1))
        val freshCues = SubtitleCues(setOf(2))
        old.attachments(staleBlock)
        old.subtitleCues(staleCues, 1_000_000)
        current.attachments(freshBlock)
        current.subtitleCues(freshCues, 1_000_000)
        assertEquals(listOf(freshBlock), attachments)
        assertEquals(1, cues.size)
        assertSame(freshCues, cues.single())
    }

    @Test
    fun `a sink delivers until the generation moves on`() {
        val sink = root.forItem()
        sink.attachments(AttachmentsBlock(1, 10))
        generation++
        sink.attachments(AttachmentsBlock(2, 10))
        assertEquals(listOf(1L), attachments.map { it.offset })
    }

    @Test
    fun `fonts are wanted whatever the generation, and the root delivers nothing`() {
        val old = root.forItem()
        generation++
        assertTrue(old.wantsFonts())
        assertTrue(root.wantsFonts())
        root.attachments(AttachmentsBlock(3, 10))
        assertTrue(attachments.isEmpty())
    }
}
