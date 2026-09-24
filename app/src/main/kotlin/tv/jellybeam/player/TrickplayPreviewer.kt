package tv.jellybeam.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uniffi.jellybeam_core.GlideDirection
import uniffi.jellybeam_core.SeekPreviewSize
import uniffi.jellybeam_core.TrickplayMetaFfi
import uniffi.jellybeam_core.TrickplayTileFfi

/**
 * Transport-side trickplay tunables (docs/12 §11). The pacing decisions (sample dwell, sheet
 * want-list, abandon rule) live in Rust `playback_policy::trickplay::glide_tunables`; these are
 * the Kotlin-owned budgets that bound what those decisions may cost on a weak device or a VPN.
 */
object TrickplayTransport {
    /** Fetched sheets kept as bytes in memory (a 10x10 sheet is ~0.3-0.7 MB): the current sheet
     * and its neighbours in both directions. */
    const val SHEET_MEMORY_SHEETS = 4

    /** Decoded tiles kept, by bytes (a 320x180 RGB_565 tile is ~115 KB, so ~70 tiles); the
     * budget, not an entry count, so a manifest with large thumbnails cannot grow the heap. */
    const val TILE_MEMORY_BYTES = 8 * 1024 * 1024

    /** A decoded tile is subsampled until its width is at most this, twice the largest panel
     * width in px at 1080p; anything larger is wasted pixels and heap. */
    const val TILE_MAX_DECODE_WIDTH = 640

    /** On-disk sheet budget, shared across items, trimmed oldest-first. */
    const val SHEET_DISK_BYTES = 32L * 1024 * 1024

    /** A sheet response larger than this is refused rather than buffered. */
    const val SHEET_MAX_BYTES = 8 * 1024 * 1024

    /** Whole-call deadline for one sheet fetch; a stalled server must free the single in-flight
     * slot rather than hold it for the player's unbounded streaming timeout. */
    const val SHEET_FETCH_TIMEOUT_SECS = 15L

    /** A `.part` file older than this was left by an interrupted write and is removed by trim. */
    const val STALE_PART_MS = 60_000L

    /** Panel width per [SeekPreviewSize]; height follows the manifest's aspect. Small is the
     * server's 320 px thumbnail pixel-for-pixel on a 1080p panel. */
    fun previewWidthDp(size: SeekPreviewSize): Dp = when (size) {
        SeekPreviewSize.SMALL -> 160.dp
        SeekPreviewSize.MEDIUM -> 240.dp
        SeekPreviewSize.LARGE -> 320.dp
    }
}

/** Where sheet bytes come from; production is [OkHttpSheetFetcher], tests script it. */
fun interface SheetFetcher {
    /** The sheet's bytes, or `null` on any failure. Must honour cancellation: a cancelled call
     * must stop consuming the network, not just stop being awaited. */
    suspend fun fetch(url: String): ByteArray?
}

/** Decodes one tile region out of sheet bytes; production is [RegionTileDecoder]. */
fun interface TileDecoder<T : Any> {
    fun decode(sheet: ByteArray, x: Int, y: Int, width: Int, height: Int): T?
}

/** Whether an in-flight fetch of `sheet` is still worth finishing for a glide now at `targetMs`
 * heading `direction` (Rust `trickplay_should_abandon_sheet`). */
fun interface SheetAbandonRule {
    fun shouldAbandon(sheet: Int, targetMs: Long, direction: GlideDirection): Boolean
}

/**
 * The glide-seek trickplay pipeline (docs/12 §11): sheets in, one hold-last tile out.
 *
 * - [tile] never blanks mid-gesture: a newer tile replaces it when decoded, otherwise the last
 *   one stays while the chip carries the time.
 * - At most one sheet fetch in flight and one queued. [want] replaces the queue; the sheet under
 *   the target always wins the slot (a farther prefetch is cancelled for it), and an in-flight
 *   fetch that is merely off the list is cancelled only when [SheetAbandonRule] says the glide
 *   has left it behind.
 * - Decodes are serial on [decodeDispatcher]; a decode that finishes for a tile no longer wanted
 *   is cached but not shown (latest wins). The tile cache is bounded by bytes ([tileBytes]).
 *
 * All state is touched on [scope]'s dispatcher (the main thread in production); fetch and decode
 * hop off it. Generic over the tile type so the scheduling is testable without Android bitmaps.
 * [perfLine] receives `perf ...` lines (docs/10) for fetch, decode and key-down-to-first-tile.
 */
class TrickplayPreviewer<T : Any>(
    private val fetcher: SheetFetcher,
    private val decoder: TileDecoder<T>,
    private val scope: CoroutineScope,
    private val tileBytes: (T) -> Int,
    private val fetchDispatcher: CoroutineDispatcher = Dispatchers.IO,
    @Suppress("OPT_IN_USAGE")
    private val decodeDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    private val perfLine: ((String) -> Unit)? = null,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val _tile = MutableStateFlow<T?>(null)

    /** The tile to draw, hold-last; `null` until the first decode of a gesture lands. */
    val tile: StateFlow<T?> = _tile

    private var meta: TrickplayMetaFfi? = null
    private var urlFor: (suspend (UInt) -> String?)? = null
    private var abandonRule: SheetAbandonRule? = null

    private val sheets = LruMap<Int, ByteArray>(maxEntries = TrickplayTransport.SHEET_MEMORY_SHEETS, weigh = { 1 })
    private val tiles = LruMap<TileKey, T>(maxEntries = TrickplayTransport.TILE_MEMORY_BYTES, weigh = tileBytes)

    private var inflightSheet: Int? = null
    private var inflightJob: Job? = null
    private var queuedSheet: Int? = null
    private var wanted: TrickplayTileFfi? = null
    private var decodeJob: Job? = null

    /** Set by [reset]; the first tile published afterwards logs key-down-to-first-tile. */
    private var blankSinceMs: Long? = null

    /** Test seam: a sheet's bytes as if fetched. */
    internal fun holdSheetBytes(sheet: Int, bytes: ByteArray) {
        sheets[sheet] = bytes
    }

    /** Test/diagnostic counters. */
    var fetchesStarted: Int = 0
        private set
    var fetchesCancelled: Int = 0
        private set

    /** Binds this previewer to one playback session's manifest and URL builder. */
    fun startSession(meta: TrickplayMetaFfi, urlFor: suspend (UInt) -> String?, abandonRule: SheetAbandonRule) {
        endSession()
        this.meta = meta
        this.urlFor = urlFor
        this.abandonRule = abandonRule
    }

    /** Drops every cache, fetch and the shown tile; safe to call repeatedly. */
    fun endSession() {
        cancelInflight()
        queuedSheet = null
        decodeJob?.cancel()
        decodeJob = null
        sheets.clear()
        tiles.clear()
        wanted = null
        meta = null
        urlFor = null
        abandonRule = null
        blankSinceMs = null
        _tile.value = null
    }

    /** Gesture start: a frame from the previous gesture would mislead, so start blank. */
    fun reset() {
        wanted = null
        _tile.value = null
        blankSinceMs = nowMs()
    }

    /** Fetches [sheet] into memory without changing what is shown: the sheet under the play
     * position at session start, so the first glide has no cold fetch. */
    fun warm(sheet: Int) {
        if (meta == null || sheets[sheet] != null || inflightSheet == sheet) return
        if (inflightSheet == null) {
            queuedSheet = sheet
            startNextFetch()
        } else if (queuedSheet == null) {
            queuedSheet = sheet
        }
    }

    /** Shows [tile] as soon as it is decodable: immediately from the tile cache, after a decode
     * when its sheet is in memory, after a fetch otherwise. */
    fun show(tile: TrickplayTileFfi) {
        if (meta == null) return
        wanted = tile
        val key = TileKey(tile)
        tiles[key]?.let {
            publish(it)
            return
        }
        val sheet = tile.imageIndex.toInt()
        if (sheets[sheet] != null) {
            decodeWanted()
        } else if (inflightSheet != sheet) {
            queuedSheet = sheet
            startNextFetch()
        }
    }

    /**
     * Replaces the fetch plan with [sheetsInOrder] (Rust `trickplay_glide_sheets`). The first
     * listed sheet is the one under the target: if it is not held and something else is in
     * flight, that fetch is cancelled for it. Otherwise an in-flight fetch survives unless it is
     * off the list and [SheetAbandonRule] drops it. The first listed sheet not already held or in
     * flight becomes the queue.
     */
    fun want(sheetsInOrder: List<Int>, targetMs: Long, direction: GlideDirection) {
        if (meta == null) return
        val inflight = inflightSheet
        val current = sheetsInOrder.firstOrNull()
        if (inflight != null && inflight != current) {
            val currentMissing = current != null && sheets[current] == null
            val abandoned = inflight !in sheetsInOrder &&
                abandonRule?.shouldAbandon(inflight, targetMs, direction) == true
            if (currentMissing || abandoned) cancelInflight()
        }
        val missing = sheetsInOrder.filter { sheets[it] == null }
        queuedSheet = missing.firstOrNull { it != inflightSheet }
        startNextFetch()
        // The slot took the first missing sheet; the next one is the queue.
        if (queuedSheet == null) queuedSheet = missing.firstOrNull { it != inflightSheet }
    }

    /** Release: the committed target's tile is what the viewer reads, so it jumps every queue. */
    fun prioritize(tile: TrickplayTileFfi) {
        if (meta == null) return
        val sheet = tile.imageIndex.toInt()
        if (inflightSheet != null && inflightSheet != sheet) cancelInflight()
        queuedSheet = null
        show(tile)
    }

    private fun publish(tile: T) {
        _tile.value = tile
        blankSinceMs?.let { since ->
            blankSinceMs = null
            perfLine?.invoke("perf section=trickplay.firstTile ms=${nowMs() - since}")
        }
    }

    private fun startNextFetch() {
        if (inflightSheet != null) return
        val sheet = queuedSheet ?: return
        queuedSheet = null
        if (sheets[sheet] != null) {
            decodeWantedIfOn(sheet)
            startNextFetch()
            return
        }
        val urlFor = urlFor ?: return
        inflightSheet = sheet
        fetchesStarted++
        inflightJob = scope.launch {
            val startedMs = nowMs()
            val bytes = try {
                val url = urlFor(sheet.toUInt())
                if (url == null) null else withContext(fetchDispatcher) { fetcher.fetch(url) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            // Only this job's own slot is released: a cancel-and-restart of the same sheet index
            // would otherwise have the dying job clear the new one's slot.
            if (inflightJob === coroutineContext[Job]) {
                inflightSheet = null
                inflightJob = null
            }
            if (bytes != null) {
                perfLine?.invoke("perf section=trickplay.sheetFetch ms=${nowMs() - startedMs} bytes=${bytes.size}")
                sheets[sheet] = bytes
                decodeWantedIfOn(sheet)
            }
            startNextFetch()
        }
    }

    private fun cancelInflight() {
        if (inflightJob != null) fetchesCancelled++
        inflightJob?.cancel()
        inflightJob = null
        inflightSheet = null
    }

    private fun decodeWantedIfOn(sheet: Int) {
        if (wanted?.imageIndex?.toInt() == sheet) decodeWanted()
    }

    private fun decodeWanted() {
        val meta = meta ?: return
        val target = wanted ?: return
        val bytes = sheets[target.imageIndex.toInt()] ?: return
        val key = TileKey(target)
        decodeJob?.cancel()
        decodeJob = scope.launch {
            val startedMs = nowMs()
            val decoded = withContext(decodeDispatcher) {
                decoder.decode(bytes, target.x.toInt(), target.y.toInt(), meta.width.toInt(), meta.height.toInt())
            } ?: return@launch
            perfLine?.invoke("perf section=trickplay.tileDecode ms=${nowMs() - startedMs}")
            tiles[key] = decoded
            if (wanted == target) publish(decoded)
        }
    }

    private data class TileKey(val sheet: UInt, val x: UInt, val y: UInt) {
        constructor(tile: TrickplayTileFfi) : this(tile.imageIndex, tile.x, tile.y)
    }
}

/** Access-ordered, weight-bounded LRU on a plain [LinkedHashMap]; JVM-only so the previewer
 * stays unit-testable. [weigh] is the cost of one value against [maxEntries]. */
private class LruMap<K, V>(private val maxEntries: Int, private val weigh: (V) -> Int) {
    private val map = LinkedHashMap<K, V>(16, 0.75f, true)
    private var total = 0

    operator fun get(key: K): V? = map[key]

    operator fun set(key: K, value: V) {
        map.remove(key)?.let { total -= weigh(it) }
        map[key] = value
        total += weigh(value)
        val it = map.entries.iterator()
        while (total > maxEntries && it.hasNext()) {
            val eldest = it.next()
            if (eldest.key == key) continue
            total -= weigh(eldest.value)
            it.remove()
        }
    }

    fun clear() {
        map.clear()
        total = 0
    }
}

/**
 * Sheet bytes over the app's [OkHttpClient] (shared pool, own whole-call timeout) with a small
 * file cache under [dir], keyed by a hash of the URL so the access token in the query never
 * lands on disk in the clear. Cancelling the coroutine cancels the OkHttp call.
 */
class OkHttpSheetFetcher(client: OkHttpClient, private val dir: File) : SheetFetcher {
    private val client = client.newBuilder()
        .callTimeout(TrickplayTransport.SHEET_FETCH_TIMEOUT_SECS, TimeUnit.SECONDS)
        .build()

    override suspend fun fetch(url: String): ByteArray? {
        val file = File(dir, hash(url) + ".jpg")
        if (file.isFile) {
            file.setLastModified(System.currentTimeMillis())
            return runCatching { file.readBytes() }.getOrNull()
        }
        val bytes = client.newCall(Request.Builder().url(url).build()).awaitBytes() ?: return null
        runCatching {
            dir.mkdirs()
            val tmp = File.createTempFile("sheet-", ".part", dir)
            try {
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(file)) tmp.delete()
            } catch (e: IOException) {
                tmp.delete()
                throw e
            }
            trim()
        }
        return bytes
    }

    /** Suspends on [Call.enqueue]; coroutine cancellation calls [Call.cancel], which aborts the
     * socket read, so an abandoned sheet stops consuming the link. */
    private suspend fun Call.awaitBytes(): ByteArray? = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resume(null)
                }

                override fun onResponse(call: Call, response: Response) {
                    val bytes = response.use { r ->
                        if (!r.isSuccessful) return@use null
                        val body = r.body ?: return@use null
                        if (body.contentLength() > TrickplayTransport.SHEET_MAX_BYTES) return@use null
                        runCatching { body.byteStream().readNBytesCapped(TrickplayTransport.SHEET_MAX_BYTES) }.getOrNull()
                    }
                    if (cont.isActive) cont.resume(bytes)
                }
            },
        )
    }

    private fun trim() {
        val now = System.currentTimeMillis()
        val all = dir.listFiles { f -> f.isFile } ?: return
        all.filter { it.name.endsWith(".part") && now - it.lastModified() > TrickplayTransport.STALE_PART_MS }
            .forEach { it.delete() }
        val sheets = all.filter { it.name.endsWith(".jpg") }
        var total = sheets.sumOf { it.length() }
        if (total <= TrickplayTransport.SHEET_DISK_BYTES) return
        for (f in sheets.sortedBy { it.lastModified() }) {
            total -= f.length()
            f.delete()
            if (total <= TrickplayTransport.SHEET_DISK_BYTES) break
        }
    }

    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun java.io.InputStream.readNBytesCapped(cap: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = read(buf)
            if (n < 0) break
            if (out.size() + n > cap) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}

/**
 * One tile out of a sheet via [BitmapRegionDecoder]: only the tile's rows and columns are
 * decoded, so a 3200x1800 sheet never exists as a bitmap and the result fits any GPU texture.
 * A region wider than [TrickplayTransport.TILE_MAX_DECODE_WIDTH] is subsampled by powers of two
 * on decode, bounding every tile's heap cost whatever the manifest advertises.
 */
object RegionTileDecoder : TileDecoder<ImageBitmap> {
    override fun decode(sheet: ByteArray, x: Int, y: Int, width: Int, height: Int): ImageBitmap? {
        @Suppress("DEPRECATION")
        val decoder = runCatching { BitmapRegionDecoder.newInstance(sheet, 0, sheet.size, false) }.getOrNull() ?: return null
        try {
            if (x < 0 || y < 0 || width <= 0 || height <= 0 || x + width > decoder.width || y + height > decoder.height) return null
            var sample = 1
            while (width / sample > TrickplayTransport.TILE_MAX_DECODE_WIDTH) sample *= 2
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
                inSampleSize = sample
            }
            return runCatching { decoder.decodeRegion(Rect(x, y, x + width, y + height), options) }.getOrNull()?.asImageBitmap()
        } finally {
            decoder.recycle()
        }
    }

    /** Heap cost of a decoded tile for the byte-weighted cache. */
    fun bytesOf(tile: ImageBitmap): Int = tile.width * tile.height * 2
}
