package tv.jellybeam.player

/**
 * Scheduling policy behind [NetworkAwareLoadErrorHandlingPolicy]. The progressive loader resets Media3's
 * `errorCount` after any attempt that extracted a sample, so the time-budgeted kinds give up on monotonic
 * elapsed time from [LoadRetryBudgetTracker]; [FailureKind.SERVER_FAILURE] counts on `errorCount`, which
 * an HLS chunk or playlist retry never resets.
 */
object LoadRetryPolicy {
    val BACKOFF_SCHEDULE_MS = listOf(1_000L, 2_000L, 4_000L, 8_000L)
    const val STEADY_STATE_RETRY_DELAY_MS = 8_000L

    /** Wall-clock budget from the first load error in one outage episode. */
    const val GIVE_UP_BUDGET_MS = 90_000L

    /** An error-free interval this long is treated as recovery; a later failure starts a fresh
     * episode rather than inheriting an old deadline.
     */
    const val RECOVERY_RESET_AFTER_MS = 60_000L

    /** A load still retrying never surfaces through Media3's `maybeThrowError` (which any rebuffer or seek calls):
     * only [decide]'s give-up does, so a transient error waits for its deadline and an [FailureKind.OUT_OF_MEDIA]
     * hold never surfaces at all (docs/18 §7).
     */
    const val MINIMUM_LOADABLE_RETRY_COUNT = Int.MAX_VALUE

    /** A [FailureKind.SERVER_FAILURE]'s single retry, for a blip; the second failure is the verdict. */
    const val SERVER_FAILURE_RETRY_DELAY_MS = 2_000L

    /** docs/18 §7: this many transcode segments in a row with no video frame mean the server's ffmpeg has nothing
     * left to encode (every real segment starts with a keyframe); each further request starts another one, and
     * Jellyfin 10.11 can hold the third open indefinitely. */
    const val EMPTY_SEGMENT_LIMIT = 2

    /** docs/18 §7: how often a held load re-checks its gate; each check is local, never a request. */
    const val OUT_OF_MEDIA_HOLD_MS = 5_000L

    enum class FailureKind {
        TRANSIENT_NETWORK,
        PERMANENT,
        PASSTHROUGH,

        /** A transcode's HTTP 5xx: the server's ffmpeg failed (a proxy may relay it as 502/504), and every retry
         * launches a new one. */
        SERVER_FAILURE,

        /** docs/18 §7: an [OutOfMediaException]; retried locally without end, so loading holds and never errors early. */
        OUT_OF_MEDIA,
    }

    sealed interface Decision {
        data class Retry(val delayMs: Long) : Decision
        data object GiveUp : Decision
        data object Passthrough : Decision
    }

    /** Only response codes that can plausibly clear without changing the request are retried; other
     * 4xx responses fail immediately.
     */
    fun classifyHttpResponseCode(responseCode: Int, transcode: Boolean, retryAfter: Boolean = false): FailureKind {
        val kind = classifyHttpResponseCode(responseCode)
        // docs/18 §7: on a transcode a retryable 5xx is ffmpeg failing; only a 503 naming its own wait asks for patience.
        val serverFailure = transcode && responseCode >= 500 && kind == FailureKind.TRANSIENT_NETWORK &&
            !(responseCode == 503 && retryAfter)
        return if (serverFailure) FailureKind.SERVER_FAILURE else kind
    }

    private fun classifyHttpResponseCode(responseCode: Int): FailureKind =
        when (responseCode) {
            500, // Internal Server Error
            408, // Request Timeout
            425, // Too Early
            429, // Too Many Requests
            502, // Bad Gateway
            503, // Service Unavailable
            504, // Gateway Timeout
            -> FailureKind.TRANSIENT_NETWORK
            else -> FailureKind.PERMANENT
        }

    /** Decides one retry using real elapsed episode time; [attempt] only shapes backoff and can
     * never extend [GIVE_UP_BUDGET_MS].
     */
    fun decide(failureKind: FailureKind, attempt: Int, elapsedMs: Long): Decision {
        require(attempt >= 1) { "attempt is 1-based; was $attempt" }
        require(elapsedMs >= 0L) { "elapsedMs must be non-negative; was $elapsedMs" }

        when (failureKind) {
            FailureKind.PERMANENT -> return Decision.GiveUp
            FailureKind.PASSTHROUGH -> return Decision.Passthrough
            FailureKind.SERVER_FAILURE ->
                return if (attempt == 1) Decision.Retry(SERVER_FAILURE_RETRY_DELAY_MS) else Decision.GiveUp
            FailureKind.OUT_OF_MEDIA -> return Decision.Retry(OUT_OF_MEDIA_HOLD_MS)
            FailureKind.TRANSIENT_NETWORK -> Unit
        }

        val delayMs = BACKOFF_SCHEDULE_MS.getOrElse(attempt - 1) { STEADY_STATE_RETRY_DELAY_MS }
        return if (elapsedMs >= GIVE_UP_BUDGET_MS || elapsedMs + delayMs >= GIVE_UP_BUDGET_MS) {
            Decision.GiveUp
        } else {
            Decision.Retry(delayMs)
        }
    }
}

/** Monotonic per-Media3-load-task outage accounting, kept independent of Android/Media3 types for
 * plain JVM tests.
 */
internal class LoadRetryBudgetTracker(
    private val nowMs: () -> Long,
    private val recoveryResetAfterMs: Long = LoadRetryPolicy.RECOVERY_RESET_AFTER_MS,
) {
    private data class Episode(var startedAtMs: Long, var lastErrorAtMs: Long)

    private val episodes = mutableMapOf<Long, Episode>()

    @Synchronized
    fun elapsedMsForError(loadTaskId: Long): Long {
        val now = nowMs()
        val existing = episodes[loadTaskId]
        val episode = if (existing == null || now - existing.lastErrorAtMs >= recoveryResetAfterMs) {
            Episode(startedAtMs = now, lastErrorAtMs = now).also { episodes[loadTaskId] = it }
        } else {
            existing.also { it.lastErrorAtMs = now }
        }
        return (now - episode.startedAtMs).coerceAtLeast(0L)
    }

    @Synchronized
    fun conclude(loadTaskId: Long) {
        episodes.remove(loadTaskId)
    }

    @Synchronized
    fun reset() {
        episodes.clear()
    }
}
