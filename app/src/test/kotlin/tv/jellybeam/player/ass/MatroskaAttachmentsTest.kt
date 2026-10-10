package tv.jellybeam.player.ass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MatroskaAttachmentsTest {
    /** An EBML element: [id] as written (marker kept), an 8-byte size, then [content]. */
    private fun element(id: Long, content: ByteArray): ByteArray {
        val idLength = (71 - java.lang.Long.numberOfLeadingZeros(id)) / 8
        val idBytes = ByteArray(idLength) { i -> (id shr (8 * (idLength - 1 - i))).toByte() }
        val size = ByteArray(8) { i -> if (i == 0) 0x01 else (content.size.toLong() shr (8 * (7 - i))).toByte() }
        return idBytes + size + content
    }

    private fun attachedFile(name: String, mime: String, data: ByteArray, uid: Long = 7) = element(
        0x61A7,
        element(0x466E, name.toByteArray()) + element(0x4660, mime.toByteArray()) + element(0x465C, data) +
            element(0x46AE, byteArrayOf(uid.toByte())),
    )

    private fun data(size: Int, tag: Char) = ByteArray(size) { tag.code.toByte() }

    /** [content] placed at [BLOCK_AT] in a file with bytes on either side, and a reader over it that counts reads. */
    private class File(content: ByteArray) {
        val bytes = data(BLOCK_AT) + content + data(64)
        val block = AttachmentsBlock(BLOCK_AT.toLong(), content.size.toLong())
        var reads = 0
        val reader = bytes.rangeReader { reads++ }

        private fun data(size: Int) = ByteArray(size) { 0x55 }
    }

    @Test
    fun `each font's data range in file order, other files and elements passed over, one read per element`() {
        val file = File(
            attachedFile("a.ttf", "font/ttf", data(100, 'a')) +
                element(0xEC, ByteArray(10)) +
                attachedFile("cover.jpg", "image/jpeg", data(5000, 'c')) +
                attachedFile("b.otf", "application/vnd.ms-opentype", data(200, 'b')),
        )
        val fonts = attachedFonts(file.block, file.reader, maxFontBytes = 1L shl 20)
        assertEquals(listOf("a.ttf", "b.otf"), fonts.map { it.name })
        assertEquals(listOf(100, 200), fonts.map { it.size })
        for (font in fonts) {
            val tag = font.name.first().code.toByte()
            val start = font.offset.toInt()
            assertTrue("${font.name} is its data", (start until start + font.size).all { file.bytes[it] == tag })
            assertTrue("${font.name} is all of its data", file.bytes[start - 1] != tag && file.bytes[start + font.size] != tag)
        }
        assertEquals(4, file.reads)
    }

    @Test
    fun `a font past the per-font cap is passed over and the rest still found`() {
        val file = File(attachedFile("big.ttf", "font/ttf", data(3000, 'x')) + attachedFile("small.ttf", "font/ttf", data(100, 's')))
        assertEquals(listOf("small.ttf"), attachedFonts(file.block, file.reader, maxFontBytes = 1000).map { it.name })
    }

    @Test
    fun `an element overrunning the block ends the walk with what was found`() {
        val good = attachedFile("a.ttf", "font/ttf", data(100, 'a'))
        val bad = attachedFile("b.ttf", "font/ttf", data(100, 'b'))
        // The second file claims more than the block holds.
        val file = File(good + bad.copyOf(bad.size - 20))
        assertEquals(listOf("a.ttf"), attachedFonts(file.block, file.reader, maxFontBytes = 1L shl 20).map { it.name })
    }

    @Test
    fun `nothing is read once the item no longer wants it`() {
        val file = File(attachedFile("a.ttf", "font/ttf", data(100, 'a')))
        assertTrue(attachedFonts(file.block, file.reader, maxFontBytes = 1L shl 20, stillWanted = { false }).isEmpty())
        assertEquals(0, file.reads)
    }

    @Test
    fun `a failed read ends the walk`() {
        val fonts = attachedFonts(AttachmentsBlock(0, 10_000), { _, _ -> null }, maxFontBytes = 1L shl 20)
        assertTrue(fonts.isEmpty())
    }

    private companion object {
        const val BLOCK_AT = 1000
    }
}
