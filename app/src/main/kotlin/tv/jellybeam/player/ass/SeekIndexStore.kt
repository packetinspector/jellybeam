package tv.jellybeam.player.ass

import java.io.File
import java.net.URI
import java.net.URISyntaxException
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import tv.jellybeam.data.runCatchingCancellable
import tv.jellybeam.player.LruFileDir

/**
 * docs/18 §5.4: seek indexes on disk, one small file per media file named by [seekIndexKey]. Nothing runs
 * until a load asks: [dir] is resolved on first use, reads happen on the loader thread, and writes,
 * deletes and the LRU trim run in order on one IO lane.
 */
class SeekIndexStore(
    dir: () -> File,
    private val scope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    now: () -> Long = System::currentTimeMillis,
) {
    private val files = LruFileDir(dir, INDEX_SUFFIX, MAX_INDEX_DIR_BYTES, MAX_INDEX_FILES, now = now)
    private val io = ioDispatcher.limitedParallelism(1)

    /** Keys deleted but maybe not yet gone from disk; [read] skips them, so a cold retry can't hit what it dropped. */
    private val dropping: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Loader thread: the index stored under [key], or null; a damaged or other-version file is deleted. */
    fun read(key: String): SeekIndex? {
        if (key in dropping) return null
        val bytes = files.read(key, MAX_INDEX_FILE_BYTES) ?: return null
        return decodeSeekIndex(bytes) ?: null.also { files.delete(key) }
    }

    /** Stores what [build] returns (on the IO lane, so it may do the checking work); null stores nothing. */
    fun save(key: String, build: () -> SeekIndex?) {
        scope.launch(io) {
            runCatchingCancellable { build()?.let { files.write(key, encodeSeekIndex(it)) } }
        }
    }

    /** Takes effect for [read] at once, on disk on the IO lane. */
    fun delete(key: String) {
        dropping += key
        scope.launch(io) {
            files.delete(key)
            dropping -= key
        }
    }

    /** Drops the index of the stream at [url], keyed exactly as a load keys it. */
    fun forget(url: String) {
        seekIndexKey(url)?.let(::delete)
    }
}

/**
 * docs/18 §5.4: a static stream's cache key: host, port, path and mediaSourceId, hashed, so no token or
 * per-play parameter takes part and the URL never reaches disk. Null for anything but a static stream.
 */
internal fun seekIndexKey(url: String): String? {
    val uri = try {
        URI(url)
    } catch (e: URISyntaxException) {
        return null
    }
    val params = uri.rawQuery?.split('&')?.map { it.substringBefore('=') to it.substringAfter('=', "") } ?: return null
    if (params.none { (k, v) -> k.equals("static", ignoreCase = true) && v.equals("true", ignoreCase = true) }) return null
    val source = params.firstOrNull { (k, _) -> k.equals("mediaSourceId", ignoreCase = true) }?.second.orEmpty()
    return LruFileDir.nameFor("${uri.scheme}://${uri.host}:${uri.port}${uri.rawPath}?mediaSourceId=$source", KEY_HEX_CHARS)
}

/** The file's total size from a response: Content-Range's total, else Content-Length; null when neither says. */
internal fun contentLengthOf(headers: Map<String, List<String>>): Long? {
    header(headers, "Content-Range")?.let { return it.substringAfterLast('/', "").trim().toLongOrNull()?.takeIf { n -> n > 0 } }
    return header(headers, "Content-Length")?.trim()?.toLongOrNull()?.takeIf { it > 0 }
}

/** The response's Last-Modified and ETag, hashed; 0 when it carries neither. */
internal fun validatorOf(headers: Map<String, List<String>>): Long {
    val lastModified = header(headers, "Last-Modified")
    val etag = header(headers, "ETag")
    if (lastModified == null && etag == null) return 0L
    val hash = LruFileDir.nameFor("${etag.orEmpty()}\n${lastModified.orEmpty()}", 16).toULong(16).toLong()
    return if (hash == 0L) 1L else hash
}

/** OkHttp lowercases header names, the platform client doesn't; either is matched. */
private fun header(headers: Map<String, List<String>>, name: String): String? =
    headers.entries.firstOrNull { name.equals(it.key, ignoreCase = true) }?.value?.firstOrNull()

internal fun encodeSeekIndex(index: SeekIndex): ByteArray {
    val n = index.timesUs.size
    val m = index.subtitleCues.size
    val out = ByteBuffer.allocate(HEADER_BYTES + n * SEEK_ENTRY_BYTES + m * SUBTITLE_ENTRY_BYTES)
    out.putInt(MAGIC).putInt(FORMAT_VERSION)
    out.putLong(index.contentLength).putLong(index.validator).putLong(index.segmentStart)
    out.putLong(index.firstClusterPos).putLong(index.durationUs).putLong(index.timecodeScaleNs)
    out.putInt(n).putInt(m)
    index.timesUs.forEach { out.putLong(it) }
    index.positions.forEach { out.putLong(it) }
    for (cue in index.subtitleCues) {
        out.putInt(cue.track).putLong(cue.timeUs).putLong(cue.durationUs).putLong(cue.clusterOffset).putLong(cue.relativePosition)
    }
    return out.array()
}

/** Null for anything this version didn't write whole: wrong magic or version, a bad count or length, or times out of order. */
internal fun decodeSeekIndex(bytes: ByteArray): SeekIndex? = try {
    val buf = ByteBuffer.wrap(bytes)
    if (buf.int != MAGIC || buf.int != FORMAT_VERSION) {
        null
    } else {
        val contentLength = buf.long
        val validator = buf.long
        val segmentStart = buf.long
        val firstClusterPos = buf.long
        val durationUs = buf.long
        val timecodeScaleNs = buf.long
        val n = buf.int
        val m = buf.int
        if (n !in 2..MAX_SEEK_INDEX_CUES || m !in 0..MAX_STORED_SUBTITLE_CUES ||
            bytes.size != HEADER_BYTES + n * SEEK_ENTRY_BYTES + m * SUBTITLE_ENTRY_BYTES
        ) {
            null
        } else {
            val times = LongArray(n) { buf.long }
            val positions = LongArray(n) { buf.long }
            val subtitles = List(m) { SubtitleCue(buf.int, buf.long, buf.long, buf.long, buf.long) }
            val ordered = (1 until n).all { times[it] >= times[it - 1] }
            if (ordered) SeekIndex(contentLength, validator, segmentStart, firstClusterPos, durationUs, timecodeScaleNs, times, positions, subtitles) else null
        }
    }
} catch (e: BufferUnderflowException) {
    null
}

const val MAX_INDEX_FILES = 32
const val MAX_INDEX_DIR_BYTES = 2L * 1024 * 1024
const val INDEX_SUFFIX = ".idx"

/** docs/18 §5.4: a file with more SSA Cues entries than this stores no index, keeping each file small. */
const val MAX_STORED_SUBTITLE_CUES = 1 shl 14

private const val KEY_HEX_CHARS = 32
private const val MAGIC = 0x4A425349 // "JBSI"
private const val FORMAT_VERSION = 2
private const val HEADER_BYTES = 4 + 4 + 6 * 8 + 4 + 4
private const val SEEK_ENTRY_BYTES = 16
private const val SUBTITLE_ENTRY_BYTES = 4 + 4 * 8
private const val MAX_INDEX_FILE_BYTES = HEADER_BYTES + MAX_SEEK_INDEX_CUES.toLong() * SEEK_ENTRY_BYTES + MAX_STORED_SUBTITLE_CUES * SUBTITLE_ENTRY_BYTES
