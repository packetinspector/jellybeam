package tv.jellybeam.diag

import android.util.Log
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tv.jellybeam.perf.PerfLog

/** docs/21 §3.1 Logging row: on/off, since-when, and how many lines are on hand for a report. */
data class DiagStatus(val enabled: Boolean, val onSinceMs: Long?, val lines: Int)

/**
 * docs/21 §2: event-tier recorder and shared ring for the perf tier and the Rust `tracing`
 * forwarder. [enabled] reflects [enabledMarker] from construction, before Settings loads, so
 * early events are never lost; writers hand records to [executor], which formats and appends.
 */
class DiagLog(
    private val dir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val executor: Executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "diag").apply { isDaemon = true }
    },
    private val mirror: (String) -> Unit = { line -> Log.i(PerfLog.TAG, line) },
) {
    private val enabledMarker = File(dir, ENABLED_MARKER_NAME)

    @Volatile
    var enabled: Boolean = enabledMarker.exists()
        private set

    private val stateLock = Any()
    private val ring = ArrayDeque<String>()
    private var currentBytes = 0
    private val previous = ArrayDeque(readBack())
    private var onSinceMs: Long? = if (enabled) enabledMarker.lastModified() else null

    private val aliasMap = ConcurrentHashMap<String, String>()
    private val aliasCounters = ConcurrentHashMap<String, AtomicInteger>()

    private val _status =
        MutableStateFlow(DiagStatus(enabled = enabled, onSinceMs = onSinceMs, lines = previous.size))
    val status: StateFlow<DiagStatus> = _status.asStateFlow()

    /** docs/21 §2.1: builds one event's fields on the caller thread; entries render on
     * [executor], never here, so a hot call site pays no formatting or alias-lookup cost.
     */
    class Fields {
        @PublishedApi
        internal val entries = mutableListOf<FieldEntry>()

        fun num(k: String, v: Long) {
            entries += FieldEntry(k, FieldKind.NUM, v.toString())
        }

        fun ms(k: String, v: Long) {
            entries += FieldEntry(k, FieldKind.NUM, v.toString())
        }

        fun bool(k: String, v: Boolean) {
            entries += FieldEntry(k, FieldKind.BOOL, v.toString())
        }

        /** docs/21 §2.3: charset-checked at render time on [executor]; raw value kept here. */
        fun tag(k: String, v: String) {
            entries += FieldEntry(k, FieldKind.TAG, v)
        }

        /** docs/21 §2.3: resolved through [alias] at render time, never the raw id. */
        fun item(k: String, id: String) {
            entries += FieldEntry(k, FieldKind.ITEM, id)
        }

        fun server(k: String, id: String) {
            entries += FieldEntry(k, FieldKind.SERVER, id)
        }

        @PublishedApi
        internal data class FieldEntry(val key: String, val kind: FieldKind, val raw: String)

        @PublishedApi
        internal enum class FieldKind { NUM, BOOL, TAG, ITEM, SERVER }
    }

    /** docs/21 §2.2: returns before touching [fields] when disabled -- one volatile read, no
     * allocation.
     */
    inline fun event(name: String, fields: Fields.() -> Unit = {}) {
        if (!enabled) return
        val f = Fields()
        f.fields()
        enqueueEvent(name, f.entries)
    }

    @PublishedApi
    internal fun enqueueEvent(name: String, entries: List<Fields.FieldEntry>) {
        val atMs = clock()
        executor.execute { appendLine(atMs, renderEventBody(name, entries)) }
    }

    /** docs/21 §2.1: Rust `tracing` WARN/ERROR forwarded through the uniffi sink; message and
     * fields are already redacted in Rust, so this only formats and appends.
     */
    fun coreRecord(level: String, target: String, message: String, fields: String) {
        if (!enabled) return
        val atMs = clock()
        executor.execute {
            val suffix = if (fields.isNotBlank()) " $fields" else ""
            appendLine(atMs, "core.$target level=$level msg=$message$suffix")
        }
    }

    /** docs/21 §2: mirrors a docs/10 [PerfLog] line into the same ring when the perf gate and
     * diagnostic logging are both on; no-op otherwise.
     */
    fun perf(line: String) {
        if (!enabled) return
        val atMs = clock()
        executor.execute { appendLine(atMs, "perf $line") }
    }

    /** docs/21 §2.3: per-process, first-seen numbering; thread-safe so it can also be called
     * directly, not only from [Fields] rendering.
     */
    fun alias(kind: String, id: String): String {
        val key = "$kind\u0000$id"
        return aliasMap.getOrPut(key) {
            val n = aliasCounters.getOrPut(kind) { AtomicInteger(0) }.incrementAndGet()
            "$kind#$n"
        }
    }

    /** docs/21(fixup) 1: no-op when [on] matches [enabled] so a redundant call from Settings'
     * read-once never clears an already-running ring or restarts the on-since clock.
     */
    fun setEnabled(on: Boolean) {
        if (on == enabled) return
        enabled = on
        val atMs = if (on) clock() else null
        executor.execute {
            if (on) {
                onSinceMs = atMs
                dir.mkdirs()
                enabledMarker.createNewFile()
                enabledMarker.setLastModified(atMs!!)
            } else {
                clearRingLocked()
                resetAliases()
                File(dir, RING_FILE_NAME).delete()
                enabledMarker.delete()
                onSinceMs = null
            }
            publishStatus()
        }
    }

    /** Deletes the ring, flushed file and aliases without touching [enabled] or [onSinceMs]
     * (docs/21 §6 "Clear log").
     */
    fun clear() {
        executor.execute {
            clearRingLocked()
            resetAliases()
            File(dir, RING_FILE_NAME).delete()
            publishStatus()
        }
    }

    /** docs/21 §2.2: writes only the current ring (never `previous + ring`) via temp file +
     * rename so a partial write can never corrupt the on-disk file.
     */
    fun flush() {
        executor.execute {
            writeRingFile()
            publishStatus()
        }
    }

    /** Previous-process lines (from the last [flush]) followed by this process's ring, both
     * oldest first.
     */
    fun linesSnapshot(): List<String> = synchronized(stateLock) { previous + ring }

    private fun clearRingLocked() {
        synchronized(stateLock) {
            ring.clear()
            currentBytes = 0
            previous.clear()
        }
    }

    private fun resetAliases() {
        aliasMap.clear()
        aliasCounters.clear()
    }

    private fun appendLine(atMs: Long, body: String) {
        val full = "${ISO_FORMATTER.format(Instant.ofEpochMilli(atMs))} $body"
        synchronized(stateLock) {
            ring.addLast(full)
            currentBytes += byteLen(full)
            while (ring.size > MAX_LINES || currentBytes > MAX_BYTES) {
                currentBytes -= byteLen(ring.removeFirst())
            }
        }
        mirror(full)
        publishStatus()
    }

    private fun renderEventBody(name: String, entries: List<Fields.FieldEntry>): String {
        if (entries.isEmpty()) return name
        return "$name " + entries.joinToString(" ") { "${it.key}=${renderField(it)}" }
    }

    private fun renderField(entry: Fields.FieldEntry): String = when (entry.kind) {
        Fields.FieldKind.TAG -> if (TAG_PATTERN.matches(entry.raw)) entry.raw else "<invalid>"
        Fields.FieldKind.ITEM -> alias("item", entry.raw)
        Fields.FieldKind.SERVER -> alias("server", entry.raw)
        Fields.FieldKind.NUM, Fields.FieldKind.BOOL -> entry.raw
    }

    private fun writeRingFile() {
        try {
            dir.mkdirs()
            val snapshot = synchronized(stateLock) { ring.toList() }
            val tmp = File(dir, "$RING_FILE_NAME.tmp")
            val target = File(dir, RING_FILE_NAME)
            val text = if (snapshot.isEmpty()) "" else snapshot.joinToString("\n") + "\n"
            tmp.writeText(text, Charsets.UTF_8)
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
        } catch (_: Exception) {
            // Best-effort persistence; a failed flush must not crash the recorder.
        }
    }

    private fun readBack(): List<String> {
        val file = File(dir, RING_FILE_NAME)
        if (!file.exists()) return emptyList()
        return try {
            file.readLines(Charsets.UTF_8).takeLast(MAX_LINES)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun publishStatus() {
        val lines = synchronized(stateLock) { ring.size + previous.size }
        _status.value = DiagStatus(enabled = enabled, onSinceMs = onSinceMs, lines = lines)
    }

    private fun byteLen(line: String) = line.toByteArray(Charsets.UTF_8).size + 1

    companion object {
        const val RING_FILE_NAME = "ring.txt"
        private const val ENABLED_MARKER_NAME = "enabled"
        private const val MAX_LINES = 2000
        private const val MAX_BYTES = 262_144
        private val TAG_PATTERN = Regex("[A-Za-z0-9_.-]{1,64}")
        private val ISO_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
    }
}
