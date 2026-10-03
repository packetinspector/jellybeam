package tv.jellybeam.updates

import org.junit.Assert.*
import org.junit.Test

class ReleaseNotesTest {
    @Test fun `headings bullets and paragraphs retain readable hierarchy`() {
        val blocks = noteBlocks("## Changes\n- **Faster** startup\n\nLong paragraph\ncontinues here")
        assertTrue(blocks[0].heading)
        assertEquals("Changes", blocks[0].text.text)
        assertEquals("• Faster startup", blocks[1].text.text)
        assertEquals("Long paragraph continues here", blocks[2].text.text)
        assertTrue(blocks[1].text.spanStyles.isNotEmpty())
    }
    @Test fun `links keep labels without loading HTML images or URLs`() {
        val value = noteInline("<b>Local</b> [Details](https://example.invalid) ![image](https://example.invalid/p.png) `code` *easy*")
        assertEquals("Local Details  code easy", value.text)
        assertFalse(value.text.contains("https:"))
    }
    @Test fun `long words and unicode are not truncated`() {
        val text = "🎬 " + "word".repeat(100)
        assertEquals(text, noteInline(text).text)
    }
    @Test fun `identifiers and arithmetic stay literal`() {
        assertEquals("max_value_ms and snake_case_name", noteInline("max_value_ms and snake_case_name").text)
        assertEquals("2 * 3 = 6 and a * b * c", noteInline("2 * 3 = 6 and a * b * c").text)
        assertEquals("2*3*4", noteInline("2*3*4").text)
    }
    @Test fun `bounded emphasis still renders`() {
        val star = noteInline("so *easy* now")
        assertEquals("so easy now", star.text)
        assertEquals(1, star.spanStyles.size)
        val underscore = noteInline("so _easy_ now (_fine_)")
        assertEquals("so easy now (fine)", underscore.text)
        assertEquals(2, underscore.spanStyles.size)
    }
    @Test fun `headings need one to six hashes and a space`() {
        assertTrue(noteBlocks("# One")[0].heading)
        assertTrue(noteBlocks("###### Six")[0].heading)
        assertEquals("Six", noteBlocks("###### Six")[0].text.text)
        assertFalse(noteBlocks("#hashtag")[0].heading)
        assertEquals("#hashtag", noteBlocks("#hashtag")[0].text.text)
        assertFalse(noteBlocks("####### Seven")[0].heading)
    }
}
