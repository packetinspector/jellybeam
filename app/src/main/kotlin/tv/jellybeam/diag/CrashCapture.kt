package tv.jellybeam.diag

import java.io.File
import java.io.FileOutputStream

/** One pending crash capture: the class and first app frame for display, [atMs] for ordering,
 * and the full capture [text] for a report (docs/21 §1.3, §3.1).
 */
data class CrashInfo(val exceptionClass: String, val firstAppFrame: String, val atMs: Long, val text: String)

/**
 * docs/21 §1.3: installs itself as the process's uncaught-exception handler, writes a capture
 * to [dir] synchronously (the process is dying; no executor), then always delegates to whatever
 * handler was previously installed. Never throws past [uncaughtException].
 */
class CrashCapture(
    private val dir: File,
    private val diag: DiagLog,
    private val summary: () -> List<Pair<String, String>>,
    persistedSettings: File? = null,
) : Thread.UncaughtExceptionHandler {

    private val offMarker = File(dir, OFF_MARKER_NAME)

    /** docs/21 §1.3: a pre-marker opt-out (`crash_reports_enabled: false` already persisted, no
     * [offMarker] yet) must start disabled on the first launch after upgrade, so this runs before
     * [enabled]'s initializer reads [offMarker].
     */
    init {
        if (!offMarker.exists() && persistedSettings != null && wasPersistedOptOut(persistedSettings)) {
            try {
                dir.mkdirs()
                offMarker.createNewFile()
            } catch (_: Exception) {
                // Best-effort; a failed write just re-checks the same persisted file next launch.
            }
        }
    }

    /** docs/21 §1.3: mirrors [offMarker] from construction, so a startup crash before Settings
     * loads honors the last persisted opt-out; the setter keeps the marker file in sync.
     */
    @Volatile
    var enabled: Boolean = !offMarker.exists()
        set(value) {
            field = value
            try {
                if (value) offMarker.delete() else { dir.mkdirs(); offMarker.createNewFile() }
            } catch (_: Exception) {
                // Best-effort; a failed write just leaves the previous persisted choice in place.
            }
        }

    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    /** docs/21 §1.3: regex-matches serde's `crash_reports_enabled` output (key order and
     * compact-vs-pretty both unknown) instead of parsing JSON, since org.json returns defaults
     * under unit tests; any I/O failure or oversized file reads as "no opinion", never opt-out.
     */
    private fun wasPersistedOptOut(file: File): Boolean = try {
        if (!file.exists()) {
            false
        } else {
            val text = file.inputStream().use { input ->
                val buffer = ByteArray(MAX_SETTINGS_BYTES)
                var total = 0
                while (total < buffer.size) {
                    val read = input.read(buffer, total, buffer.size - total)
                    if (read < 0) break
                    total += read
                }
                String(buffer, 0, total, Charsets.UTF_8)
            }
            PERSISTED_OPT_OUT_PATTERN.containsMatchIn(text)
        }
    } catch (_: Exception) {
        false
    }

    fun install() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(t: Thread, e: Throwable) {
        try {
            if (enabled) writeCaptureFiles(buildCrashText(e))
        } catch (_: Throwable) {
            // A failing capture must never mask the original crash or block the previous handler.
        }
        previousHandler?.uncaughtException(t, e)
    }

    fun pending(): CrashInfo? {
        val file = File(dir, CRASH_FILE_NAME)
        if (!file.exists()) return null
        return try {
            val text = file.readText(Charsets.UTF_8)
            val exceptionLine = text.lineSequence().firstOrNull { it.startsWith("exception: ") }
            val frameLine = text.lineSequence().firstOrNull { it.startsWith("first app frame: ") }
            val exceptionClass = exceptionLine?.removePrefix("exception: ")?.trim() ?: "unknown"
            val firstAppFrame = frameLine?.removePrefix("first app frame: ")?.trim() ?: "unknown"
            CrashInfo(exceptionClass, firstAppFrame, file.lastModified(), text)
        } catch (_: Exception) {
            null
        }
    }

    fun shouldPrompt(): Boolean = pending() != null && readShownCount() < 3

    fun markShown() {
        writeShownCount(readShownCount() + 1)
    }

    fun discard() {
        File(dir, CRASH_FILE_NAME).delete()
        File(dir, STATE_FILE_NAME).delete()
    }

    private fun buildCrashText(throwable: Throwable): String {
        val lines = mutableListOf<String>()
        summary().forEach { (k, v) -> lines += "# $k: $v" }
        lines += ""
        lines += "--- crash ---"
        lines += "exception: ${throwable.javaClass.name}"
        lines += "heap: ${heapSummary()}"
        lines += "first app frame: ${firstAppFrame(throwable)}"
        lines += "stack:"
        lines += renderChain(throwable)
        if (diag.enabled) {
            lines += ""
            lines += "--- log ---"
            lines += diag.linesSnapshot()
        }
        return lines.joinToString("\n")
    }

    /** docs/21 §1.3: [Throwable.message] never appears here -- frames and each cause's class
     * name only, capped to [MAX_FRAMES_PER_THROWABLE] frames and [MAX_CAUSES] causes.
     */
    private fun renderChain(throwable: Throwable): List<String> {
        val lines = mutableListOf<String>()
        lines += renderFrames(throwable.stackTrace)
        var cause = throwable.cause
        var depth = 0
        while (cause != null && depth < MAX_CAUSES) {
            lines += "caused by: ${cause.javaClass.name}"
            lines += renderFrames(cause.stackTrace)
            cause = cause.cause
            depth++
        }
        return lines
    }

    private fun renderFrames(frames: Array<StackTraceElement>): List<String> =
        if (frames.isEmpty()) {
            listOf("  (no frames recorded)")
        } else {
            frames.take(MAX_FRAMES_PER_THROWABLE).map { "  at ${formatFrame(it)}" }
        }

    /** Java heap at capture time -- the one number an OutOfMemoryError needs, since ART's
     * preallocated OOM carries no frames. */
    private fun heapSummary(): String {
        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val maxMb = runtime.maxMemory() / (1024 * 1024)
        return "${usedMb}MB used of ${maxMb}MB"
    }

    /** [StackTraceElement] fields only -- never `toString()` on a [Throwable], which would
     * pull its message back in.
     */
    private fun formatFrame(frame: StackTraceElement): String =
        "${frame.className}.${frame.methodName}(${frame.fileName ?: "Unknown"}:${frame.lineNumber})"

    private fun firstAppFrame(throwable: Throwable): String =
        throwable.stackTrace.firstOrNull { it.className.startsWith("tv.jellybeam") }?.let(::formatFrame) ?: "none"

    private fun writeCaptureFiles(text: String) {
        try {
            dir.mkdirs()
            FileOutputStream(File(dir, CRASH_FILE_NAME)).use { it.write(text.toByteArray(Charsets.UTF_8)) }
            writeShownCount(0)
        } catch (_: Exception) {
            // Best-effort: a write failure here must not stop delegation to the previous handler.
        }
    }

    private fun readShownCount(): Int {
        val file = File(dir, STATE_FILE_NAME)
        if (!file.exists()) return 0
        return try {
            SHOWN_COUNT_PATTERN.find(file.readText(Charsets.UTF_8))?.groupValues?.get(1)?.toIntOrNull() ?: 0
        } catch (_: Exception) {
            0
        }
    }

    private fun writeShownCount(n: Int) {
        try {
            dir.mkdirs()
            File(dir, STATE_FILE_NAME).writeText("{\"shownCount\":$n}", Charsets.UTF_8)
        } catch (_: Exception) {
            // Best-effort; a missing/stale count just re-prompts, which is the safe direction.
        }
    }

    companion object {
        const val CRASH_FILE_NAME = "crash.txt"
        const val STATE_FILE_NAME = "crash-state.json"
        const val OFF_MARKER_NAME = "crash-off"
        private const val MAX_FRAMES_PER_THROWABLE = 40
        private const val MAX_CAUSES = 4
        private const val MAX_SETTINGS_BYTES = 64 * 1024
        private val SHOWN_COUNT_PATTERN = Regex(""""shownCount"\s*:\s*(\d+)""")
        private val PERSISTED_OPT_OUT_PATTERN = Regex(""""crash_reports_enabled"\s*:\s*false""")
    }
}
