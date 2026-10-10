package tv.jellybeam.player.ass

/**
 * The subtitle entries of a Matroska file's Cues index. A seek, a resume or a track switch starts
 * reading after lines that began earlier and still show; these say where those lines' blocks sit, so
 * the player fetches just them (docs/13). Positions are absolute file offsets of each block's cluster.
 */
class SubtitleCues(val tracks: Set<Int>) {
    private var size = 0
    private var track = IntArray(64)
    private var timeUs = LongArray(64)
    private var durationUs = LongArray(64)
    private var cluster = LongArray(64)
    private var relative = LongArray(64)

    val count: Int get() = size

    /** Loader thread, in file order; one past [MAX_SUBTITLE_CUES] is dropped, so a hostile index can't grow memory. */
    fun add(cue: SubtitleCue) {
        if (cue.track !in tracks || size >= MAX_SUBTITLE_CUES) return
        if (size == timeUs.size) grow()
        track[size] = cue.track
        timeUs[size] = cue.timeUs
        durationUs[size] = cue.durationUs
        cluster[size] = cue.clusterOffset
        relative[size] = cue.relativePosition
        size++
    }

    private fun grow() {
        val n = size * 2
        track = track.copyOf(n)
        timeUs = timeUs.copyOf(n)
        durationUs = durationUs.copyOf(n)
        cluster = cluster.copyOf(n)
        relative = relative.copyOf(n)
    }

    /**
     * Lines of [track] that began before [positionUs] and still show there, at most [limit]. A cue without
     * a duration counts when it began within [UNKNOWN_SPAN_US], as mpv's preroll does. This only selects
     * candidates: [spanningSamples] rebuilds one only if its block carries its own BlockDuration (> 0),
     * and takes the sample's duration from the block, never from the cue.
     */
    fun spanning(track: Int, positionUs: Long, limit: Int = MAX_SPANNING_LINES): List<SubtitleCue> {
        val out = ArrayList<SubtitleCue>()
        for (i in 0 until size) {
            if (this.track[i] != track || timeUs[i] >= positionUs) continue
            val showing = if (durationUs[i] > 0) timeUs[i] + durationUs[i] > positionUs else positionUs - timeUs[i] < UNKNOWN_SPAN_US
            if (showing) out += SubtitleCue(track, timeUs[i], durationUs[i], cluster[i], relative[i])
        }
        return out.takeLast(limit)
    }
}

/** One Cues entry for a subtitle block: its track, start, duration (0 when unknown) and place in the file. */
data class SubtitleCue(
    val track: Int,
    val timeUs: Long,
    val durationUs: Long,
    val clusterOffset: Long,
    val relativePosition: Long,
)

/** A subtitle block read back from the file: its payload and BlockDuration (null when the block has none). */
class SubtitleBlock(val payload: ByteArray, val durationUs: Long?)

/** An EBML element header: its ID, where its content starts relative to the header, and its content size. */
data class EbmlHeader(val id: Long, val headerLength: Int, val size: Long)

/** The element header at [pos], or null when [bytes] ends first or a length marker is invalid. */
fun ebmlHeader(bytes: ByteArray, pos: Int): EbmlHeader? {
    val id = ebmlVint(bytes, pos, keepMarker = true) ?: return null
    val size = ebmlVint(bytes, pos + id.second, keepMarker = false) ?: return null
    return EbmlHeader(id.first, id.second + size.second, size.first)
}

/** An EBML variable-length integer at [pos] and its length in bytes; IDs keep their length marker. */
fun ebmlVint(bytes: ByteArray, pos: Int, keepMarker: Boolean): Pair<Long, Int>? {
    if (pos !in bytes.indices) return null
    val first = bytes[pos].toInt() and 0xFF
    val length = Integer.numberOfLeadingZeros(first) - 23
    if (length !in 1..8 || pos + length > bytes.size) return null
    var value = (if (keepMarker) first else first and (0xFF shr length)).toLong()
    for (i in 1 until length) value = (value shl 8) or (bytes[pos + i].toLong() and 0xFF)
    return value to length
}

/**
 * The SimpleBlock or BlockGroup at [pos] in [bytes]: its payload with the track number, timecode and
 * flags stripped, and its BlockDuration scaled to microseconds. Null for a laced or truncated block,
 * which Media3 itself never emits as one subtitle sample.
 */
fun subtitleBlock(bytes: ByteArray, pos: Int, timecodeScaleNs: Long): SubtitleBlock? {
    val element = ebmlHeader(bytes, pos) ?: return null
    val start = pos + element.headerLength
    val end = start + element.size
    if (end > bytes.size) return null
    return when (element.id) {
        ID_SIMPLE_BLOCK -> blockPayload(bytes, start, end.toInt())?.let { SubtitleBlock(it, null) }
        ID_BLOCK_GROUP -> {
            var payload: ByteArray? = null
            var duration: Long? = null
            var child = start
            while (child < end) {
                val h = ebmlHeader(bytes, child) ?: return null
                val content = child + h.headerLength
                if (content + h.size > end) return null
                when (h.id) {
                    ID_BLOCK -> payload = blockPayload(bytes, content, (content + h.size).toInt())
                    ID_BLOCK_DURATION -> duration = readUnsigned(bytes, content, h.size.toInt()) * timecodeScaleNs / 1000
                }
                child = (content + h.size).toInt()
            }
            payload?.let { SubtitleBlock(it, duration) }
        }
        else -> null
    }
}

private fun blockPayload(bytes: ByteArray, start: Int, end: Int): ByteArray? {
    val trackNumber = ebmlVint(bytes, start, keepMarker = false) ?: return null
    val flags = start + trackNumber.second + 2
    if (flags >= end || bytes[flags].toInt() and LACING_BITS != 0) return null
    return bytes.copyOfRange(flags + 1, end)
}

private fun readUnsigned(bytes: ByteArray, pos: Int, length: Int): Long {
    var v = 0L
    for (i in 0 until length.coerceAtMost(8)) v = (v shl 8) or (bytes[pos + i].toLong() and 0xFF)
    return v
}

/**
 * The sample Media3's MatroskaExtractor emits for an SSA block: its fixed `Dialogue:` prefix with the
 * duration as `h:mm:ss:cc` end time, then the block payload, so the renderer can't tell the two apart.
 */
fun ssaSample(payload: ByteArray, durationUs: Long): ByteArray {
    val cs = durationUs / 10_000
    val end = "%01d:%02d:%02d:%02d".format(cs / 360_000, cs / 6_000 % 60, cs / 100 % 60, cs % 100)
    return "Dialogue: 0:00:00:00,$end,".toByteArray(Charsets.US_ASCII) + payload
}

/** Reads up to [length] bytes of the media file at [offset] (fewer at its end), or null on failure. */
fun interface RangeReader {
    fun read(offset: Long, length: Int): ByteArray?
}

/**
 * The samples for [cues] as Media3 would have emitted them, (start µs, bytes): one short read finds each
 * cluster's header length, one more reads the block. A block that can't be read or parsed is skipped, and
 * so is one without its own BlockDuration > 0 (the cue's duration is not used), because Media3 never emits it;
 * [stillWanted] going false (a newer seek, the next item) stops between reads.
 */
fun spanningSamples(
    cues: List<SubtitleCue>,
    timecodeScaleNs: Long,
    reader: RangeReader,
    stillWanted: () -> Boolean = { true },
): List<Pair<Long, ByteArray>> {
    val headerLengths = HashMap<Long, Int?>()
    val out = ArrayList<Pair<Long, ByteArray>>()
    for (cue in cues) {
        if (!stillWanted()) break
        val headerLength = headerLengths.getOrPut(cue.clusterOffset) {
            reader.read(cue.clusterOffset, CLUSTER_HEADER_BYTES)
                ?.let { ebmlHeader(it, 0) }
                ?.takeIf { it.id == ID_CLUSTER }
                ?.headerLength
        } ?: continue
        val blockAt = cue.clusterOffset + headerLength + cue.relativePosition
        var bytes = reader.read(blockAt, SUBTITLE_BLOCK_READ_BYTES) ?: continue
        val element = ebmlHeader(bytes, 0) ?: continue
        val total = element.headerLength + element.size
        if (total > bytes.size) {
            if (total > MAX_SUBTITLE_BLOCK_BYTES) continue
            bytes = reader.read(blockAt, total.toInt()) ?: continue
        }
        val block = subtitleBlock(bytes, 0, timecodeScaleNs) ?: continue
        // Media3 skips a block whose own BlockDuration is unset (it ignores the Cues duration), so playback never shows it.
        val durationUs = block.durationUs ?: continue
        if (durationUs <= 0) continue
        out += cue.timeUs to ssaSample(block.payload, durationUs)
    }
    return out
}

/** Matroska codec IDs of tracks [AssTextRenderer] draws. */
fun isSsaCodec(codecId: String?): Boolean = codecId == "S_TEXT/ASS" || codecId == "S_TEXT/SSA"

/** A Cues index holding more subtitle entries than this keeps only the first ones (S5-class files carry 66k per track). */
const val MAX_SUBTITLE_CUES = 1 shl 18

/** At most this many lines are fetched for one position; signs past it show from their next line on. */
const val MAX_SPANNING_LINES = 64

/** mpv's preroll window: a cue without a duration is assumed to show this long. */
const val UNKNOWN_SPAN_US = 10_000_000L

/** First read of one block; a longer one is read again at its exact size. */
const val SUBTITLE_BLOCK_READ_BYTES = 64 * 1024

/** A block larger than one event may be (the engine's MAX_EVENT_BYTES) plus its headers is not read. */
const val MAX_SUBTITLE_BLOCK_BYTES = (256 + 1) * 1024L

/** A Cluster ID (4 bytes) and the longest size field (8). */
private const val CLUSTER_HEADER_BYTES = 12
private const val ID_CLUSTER = 0x1F43B675L

private const val ID_SIMPLE_BLOCK = 0xA3L
private const val ID_BLOCK_GROUP = 0xA0L
private const val ID_BLOCK = 0xA1L
private const val ID_BLOCK_DURATION = 0x9BL
private const val LACING_BITS = 0x06
