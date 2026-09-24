package tv.jellybeam.perf

import android.os.SystemClock
import android.util.Log
import java.lang.ref.WeakReference
import java.util.Collections

/**
 * Runtime gate for every perf log line in this app, plus the hot-path-safe helpers built on
 * it: FFI timing ([timed]), startup marks ([markStartup]), and
 * [PerfAccumulator]/[FrameStatsHistogram].
 *
 * Gate: `Log.isLoggable(TAG, Log.DEBUG)`, not `BuildConfig.DEBUG` -- this must gate a measured
 * *release* build. Enable with `adb shell setprop log.tag.JellybeamTV DEBUG`, disable with `INFO`
 * (docs/10-perf-logging.md has the full guide).
 *
 * Cost when disabled: [enabled] is a cached `@Volatile` read; every hot-path call site is
 * `if (!enabled) return` before any timing or allocation. [refresh] does the real
 * `Log.isLoggable` call, only from `MainActivity.onResume`.
 */
object PerfLog {
    const val TAG = "JellybeamTV"

    @Volatile
    private var cachedEnabled: Boolean = Log.isLoggable(TAG, Log.DEBUG)

    /** docs/21 §2 mirror rule: when set, every perf line also reaches [tv.jellybeam.diag.DiagLog]'s
     * ring so a report from a measured session includes it.
     */
    @Volatile
    var sink: ((String) -> Unit)? = null

    /** Cached gate value -- safe to read from any thread, any frequency; never itself does the
     * [Log.isLoggable] call.
     */
    val enabled: Boolean
        get() = cachedEnabled

    /** One free-form `perf ...` line when [enabled]; for call sites that time across
     * callbacks and cannot use [timed]. */
    fun line(text: String) {
        if (enabled) Log.d(TAG, text)
    }

    /** Re-checks `Log.isLoggable` and updates [enabled]. Not called from any hot path. */
    fun refresh() {
        cachedEnabled = Log.isLoggable(TAG, Log.DEBUG)
    }

    /**
     * Times [block] and logs `perf section=$section ms=...` when [enabled]; otherwise runs
     * [block] with no measurement. `suspend inline` so FFI-wrapper callers already inside
     * `withContext(Dispatchers.IO) { ... }` can make further suspend calls with no extra
     * allocation. Never logs call arguments -- only a fixed `section` name, never a
     * title/URL/token.
     */
    suspend inline fun <T> timed(section: String, block: suspend () -> T): T {
        if (!enabled) return block()
        val startNs = System.nanoTime()
        val result = block()
        val ms = (System.nanoTime() - startNs) / 1_000_000.0
        val line = "perf section=$section ms=${"%.2f".format(ms)}"
        Log.d(TAG, line)
        sink?.invoke(line)
        return result
    }

    /** One startup-phase timestamp mark (docs/10-perf-logging.md has the phase list); uses
     * [SystemClock.elapsedRealtime] since only deltas matter.
     */
    fun markStartup(phase: String) {
        if (!enabled) return
        val line = "perf startup phase=$phase atMs=${SystemClock.elapsedRealtime()}"
        Log.d(TAG, line)
        sink?.invoke(line)
    }

    /** Same line shape as [markStartup] but with an explicit [atMs] instead of "now" --
     * [tv.jellybeam.JellybeamApp]'s `process.start` mark uses
     * [android.os.Process.getStartElapsedRealtime]'s own timestamp, not the time this call
     * happens to run.
     */
    fun markStartupAt(phase: String, atMs: Long) {
        if (!enabled) return
        val line = "perf startup phase=$phase atMs=$atMs"
        Log.d(TAG, line)
        sink?.invoke(line)
    }

    /** One playback-start timeline point, like [markStartup]. Kept separate since playback can
     * start repeatedly per process.
     */
    fun markPlayback(phase: String) {
        if (!enabled) return
        val line = "perf playback phase=$phase atMs=${SystemClock.elapsedRealtime()}"
        Log.d(TAG, line)
        sink?.invoke(line)
    }

    /** [SystemClock.elapsedRealtime] at the most recent [markDetailPush] -- the zero point
     * [markDetailPhase] measures its `ms=` against. */
    @Volatile
    private var lastDetailPushAtMs: Long = 0L

    /** The `detail.push` mark (docs/10-perf-logging.md) -- logs the plain `atMs=` startup line
     * (via [markStartup]) and records this moment as the reference every later `detail.<phase>`
     * mark for the same push measures its own `ms=` from -- a second push always measures from
     * ITS OWN call here, never a stale one. */
    fun markDetailPush() {
        if (!enabled) return
        lastDetailPushAtMs = SystemClock.elapsedRealtime()
        markStartup("detail.push")
    }

    /** One-shot `detail.<phase>` mark: `ms=` elapsed since the most recent [markDetailPush],
     * matching [tv.jellybeam.MainActivity]'s own `activity.firstFrame` shape rather than
     * [markStartup]'s `atMs=`. */
    fun markDetailPhase(phase: String) {
        if (!enabled) return
        val ms = (SystemClock.elapsedRealtime() - lastDetailPushAtMs).toDouble()
        val line = "perf startup phase=$phase ms=${"%.2f".format(ms)}"
        Log.d(TAG, line)
        sink?.invoke(line)
    }

    /** One-shot "the next Coil success for this exact request URL fires this detail mark"
     * registrations (docs/10 `detail.poster`/`detail.backdrop`) -- matched by
     * [notifyImageUrlSucceeded] against the URL [tv.jellybeam.PerfImageEventListener] reports
     * succeeding. Compared only, never logged: this app's standing rule is to never echo a
     * server URL, and nothing here writes one to Logcat or the diag sink.
     */
    private val pendingDetailImageMarks = Collections.synchronizedMap(mutableMapOf<String, String>())

    /** Arms [mark] to fire (via [markDetailPhase]) the next time [notifyImageUrlSucceeded]
     * reports [url] as loaded. A blank/null [url] arms nothing; re-arming the same [mark] with a
     * different [url] (a fallback-art swap before the first request lands) drops the stale entry
     * first so it can't fire twice.
     */
    fun armDetailImageMark(mark: String, url: String?) {
        if (!enabled || url.isNullOrBlank()) return
        // Collections.synchronizedMap only makes single calls atomic; this remove-then-put pair
        // needs its own lock on the map itself, per that wrapper's own contract.
        synchronized(pendingDetailImageMarks) {
            pendingDetailImageMarks.entries.removeAll { it.value == mark }
            pendingDetailImageMarks[url] = mark
        }
    }

    /** [tv.jellybeam.PerfImageEventListener]'s per-success hook: fires and disarms a matching
     * [armDetailImageMark] entry, if any. */
    fun notifyImageUrlSucceeded(url: String?) {
        if (url == null) return
        val mark = pendingDetailImageMarks.remove(url) ?: return
        markDetailPhase(mark)
    }

    /** Every live [PerfAccumulator], weakly held so one that's fallen out of scope (a test's, or
     * a future call site's) is silently dropped rather than pinned for the process's lifetime.
     * [flushAllIfDue] is this registry's only reader.
     */
    private val accumulators = Collections.synchronizedList(mutableListOf<WeakReference<PerfAccumulator>>())

    /** Registers [accumulator] so [flushAllIfDue] can flush its pending window even if no further
     * [PerfAccumulator.record] call ever arrives to supply the trigger -- see that function's own
     * doc. Called once from every [PerfAccumulator]'s own construction, never from a hot path.
     */
    internal fun registerAccumulator(accumulator: PerfAccumulator) {
        accumulators.add(WeakReference(accumulator))
    }

    /** [tv.jellybeam.MainActivity]'s 10s frame-metrics tick calls this so a burst that ends before an
     * accumulator's own flush interval elapses (docs/10: Home's `image.load`/`ffi.imageUrl`
     * summaries during a grid scroll that stops short of 10s) still reaches Logcat instead of
     * sitting in [PerfAccumulator]'s pending totals forever. Each accumulator still enforces its
     * own flush interval ([PerfAccumulator.flushIfDue]) -- this only supplies the periodic
     * trigger `record()` alone can't when calls stop mid-window.
     */
    fun flushAllIfDue() {
        if (!enabled) return
        synchronized(accumulators) {
            val iterator = accumulators.iterator()
            while (iterator.hasNext()) {
                val accumulator = iterator.next().get()
                if (accumulator == null) iterator.remove() else accumulator.flushIfDue()
            }
        }
    }
}

/**
 * Count/duration accumulator for a hot, synchronous call site where per-call logging would
 * itself be the overhead ([tv.jellybeam.data.CoreGateway.imageUrl], the Coil `EventListener` in
 * [tv.jellybeam.JellybeamApp]). Two paths to Logcat:
 *
 * - Immediately, when a single call exceeds [outlierMs] (`null` disables this path).
 * - As a rolled-up summary (count, success, avg/max ms, bytes) once [flushIntervalMs] has
 *   elapsed, checked on every [record] rather than via a background ticker.
 *
 * [now]/[emit] default to real clock/Logcat, overridden with fakes in `PerfAccumulatorTest`.
 */
class PerfAccumulator(
    private val label: String,
    private val outlierMs: Double? = null,
    private val flushIntervalMs: Long = DEFAULT_FLUSH_INTERVAL_MS,
    private val now: () -> Long = SystemClock::elapsedRealtime,
    private val emit: (String) -> Unit = { Log.d(PerfLog.TAG, it) },
) {
    private var count = 0
    private var successCount = 0
    private var totalMs = 0.0
    private var maxMs = 0.0
    private var totalBytes = 0L
    private var lastFlushAtMs = now()

    init {
        // docs/10: this is the only trigger for a burst that ends mid-window, once no further
        // [record] call ever arrives to supply [flushIfDue]'s own periodic check.
        PerfLog.registerAccumulator(this)
    }

    /** Records one call's outcome. [detail] (an item id, never title/URL/token) appears only on an
     * outlier line, never in the summary.
     */
    @Synchronized
    fun record(durationMs: Double, success: Boolean = true, sizeBytes: Long = 0L, detail: String? = null) {
        count++
        if (success) successCount++
        totalMs += durationMs
        if (durationMs > maxMs) maxMs = durationMs
        totalBytes += sizeBytes

        if (outlierMs != null && durationMs > outlierMs) {
            val idSuffix = if (detail != null) " id=$detail" else ""
            emit("perf $label outlier ms=${"%.2f".format(durationMs)}$idSuffix")
        }

        val nowMs = now()
        if (nowMs - lastFlushAtMs >= flushIntervalMs) {
            flushLocked(nowMs)
        }
    }

    /** Forces a summary line (if anything was recorded since the last flush) regardless of
     * [flushIntervalMs].
     */
    @Synchronized
    fun flush() {
        flushLocked(now())
    }

    /** [PerfLog.flushAllIfDue]'s per-accumulator half: the exact same interval check [record]'s
     * own tail runs, so a periodic caller with no new data to supply never flushes early and
     * never fights a `record()` that's about to trigger the same flush itself.
     */
    @Synchronized
    fun flushIfDue() {
        val nowMs = now()
        if (nowMs - lastFlushAtMs >= flushIntervalMs) {
            flushLocked(nowMs)
        }
    }

    private fun flushLocked(nowMs: Long) {
        if (count > 0) {
            val avgMs = totalMs / count
            val bytesSuffix = if (totalBytes > 0) " bytes=$totalBytes" else ""
            emit(
                "perf $label count=$count success=$successCount " +
                    "avgMs=${"%.2f".format(avgMs)} maxMs=${"%.2f".format(maxMs)}$bytesSuffix",
            )
        }
        count = 0
        successCount = 0
        totalMs = 0.0
        maxMs = 0.0
        totalBytes = 0L
        lastFlushAtMs = nowMs
    }

    companion object {
        private const val DEFAULT_FLUSH_INTERVAL_MS = 10_000L
    }
}

/**
 * Pure bucketed-histogram math for [tv.jellybeam.MainActivity]'s periodic frame-metrics summary,
 * free of any `android.view.FrameMetrics`/`Window` dependency so it's JVM-testable. One-ms
 * buckets up to [BUCKET_COUNT_MS]; frames worse than that collapse into the top bucket.
 */
class FrameStatsHistogram(private val jankyThresholdMs: Double = DEFAULT_JANKY_THRESHOLD_MS) {
    private val buckets = IntArray(BUCKET_COUNT_MS)
    private var frameCount = 0
    private var jankyCount = 0

    /** Records one frame's total duration in milliseconds. */
    fun add(durationMs: Double) {
        frameCount++
        if (durationMs > jankyThresholdMs) jankyCount++
        val bucket = durationMs.toInt().coerceIn(0, BUCKET_COUNT_MS - 1)
        buckets[bucket]++
    }

    /** Rolls up everything recorded since the last [reset] into one summary. */
    fun summary(): Summary = Summary(
        frames = frameCount,
        janky = jankyCount,
        p50Ms = percentile(P50_FRACTION),
        p90Ms = percentile(P90_FRACTION),
    )

    /** Clears all counts -- called after every emitted summary so the next window starts fresh. */
    fun reset() {
        buckets.fill(0)
        frameCount = 0
        jankyCount = 0
    }

    /** Bucket-resolution estimate (±1ms): the smallest duration at or above which [fraction] of
     * frames fall.
     */
    private fun percentile(fraction: Double): Double {
        if (frameCount == 0) return 0.0
        val target = (frameCount * fraction).toInt().coerceIn(0, frameCount - 1)
        var running = 0
        for (ms in buckets.indices) {
            running += buckets[ms]
            if (running > target) return ms.toDouble()
        }
        return (BUCKET_COUNT_MS - 1).toDouble()
    }

    data class Summary(val frames: Int, val janky: Int, val p50Ms: Double, val p90Ms: Double)

    companion object {
        // Android's jank heuristic: a frame missing one 60Hz vsync (~16.7ms).
        private const val DEFAULT_JANKY_THRESHOLD_MS = 16.7
        private const val BUCKET_COUNT_MS = 500
        private const val P50_FRACTION = 0.50
        private const val P90_FRACTION = 0.90
    }
}
