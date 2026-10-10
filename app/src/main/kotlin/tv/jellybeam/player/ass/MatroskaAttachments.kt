package tv.jellybeam.player.ass

/** An MKV Attachments element's content: [size] bytes at absolute file offset [offset]. */
data class AttachmentsBlock(val offset: Long, val size: Long)

/**
 * docs/13: the font attachments in [block] at most [maxFontBytes] each, in file order, from one short
 * range read per element instead of the whole block. A read that fails, an element that can't be
 * parsed or overruns the block, or [stillWanted] going false ends the walk with what was found.
 */
fun attachedFonts(
    block: AttachmentsBlock,
    reader: RangeReader,
    maxFontBytes: Long,
    stillWanted: () -> Boolean = { true },
): List<AssFontLocation> {
    val end = block.offset + block.size
    val out = ArrayList<AssFontLocation>()
    var pos = block.offset
    var reads = 0
    while (pos < end && out.size < MAX_ATTACHED_FONTS && reads < MAX_ATTACHMENT_READS && stillWanted()) {
        reads++
        val bytes = reader.read(pos, minOf(ATTACHMENT_HEADER_READ_BYTES.toLong(), end - pos).toInt()) ?: break
        val element = ebmlHeader(bytes, 0) ?: break
        val next = pos + element.headerLength + element.size
        if (next > end) break
        if (element.id == ID_ATTACHED_FILE) {
            attachedFile(bytes, element, pos, next)
                ?.takeIf { it.size <= maxFontBytes }
                ?.let { out += it }
        }
        pos = next
    }
    return out
}

/**
 * The font in the AttachedFile at [pos] whose first bytes are [bytes], or null when it isn't a font or
 * its name, type and data header aren't within those bytes (mkvmerge and FFmpeg both write them first).
 */
private fun attachedFile(bytes: ByteArray, file: EbmlHeader, pos: Long, end: Long): AssFontLocation? {
    var name: String? = null
    var mime: String? = null
    var child = file.headerLength
    while (pos + child < end) {
        val h = ebmlHeader(bytes, child) ?: return null
        val content = child + h.headerLength
        when (h.id) {
            ID_FILE_DATA -> {
                val dataAt = pos + content
                if (h.size > Int.MAX_VALUE || dataAt + h.size > end || !isFontAttachment(mime, name)) return null
                return AssFontLocation(name ?: "attachment", dataAt, h.size.toInt())
            }
            ID_FILE_NAME, ID_FILE_MIME_TYPE -> {
                if (content + h.size > bytes.size) return null
                val value = String(bytes, content, h.size.toInt(), Charsets.UTF_8).trimEnd('\u0000')
                if (h.id == ID_FILE_NAME) name = value else mime = value
            }
        }
        if (content + h.size > bytes.size) return null
        child = (content + h.size).toInt()
    }
    return null
}

/** One AttachedFile's name, type and data header fit well inside this. */
private const val ATTACHMENT_HEADER_READ_BYTES = 4 * 1024

/** Fonts recorded per item at most, as the renderer's font count limit (limits.rs). */
private const val MAX_ATTACHED_FONTS = 128

/** Reads per block at most, so a block of tiny or non-font elements can't cost unbounded requests. */
private const val MAX_ATTACHMENT_READS = 1024

private const val ID_ATTACHED_FILE = 0x61A7L
private const val ID_FILE_NAME = 0x466EL
private const val ID_FILE_MIME_TYPE = 0x4660L
private const val ID_FILE_DATA = 0x465CL
