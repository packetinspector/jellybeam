package tv.jellybeam.player

/**
 * Scheduling policy behind [NetworkAwareLoadErrorHandlingPolicy]. Media3's `errorCount` is only a
 * backoff hint (the progressive loader resets it after any attempt that extracted a sample), so the
 * give-up budget instead uses monotonic elapsed time from [LoadRetryBudgetTracker], which survives
 * those resets.
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

    /** Prevents Media3's `maybeThrowError` threshold from surfacing a transient error before
     * [decide] reaches its deadline.
     */
    const val MINIMUM_LOADABLE_RETRY_COUNT = 13

    enum class FailureKind {
        TRANSIENT_NETWORK,
        PERMANENT,
        PASSTHROUGH,
    }

    sealed interface Decision {
        data class Retry(val delayMs: Long) : Decision
        data object GiveUp : Decision
        data object Passthrough : Decision
    }

    /** Only response codes that can plausibly clear without changing the request are retried; other
     * 4xx responses fail immediately.
     */
    fun classifyHttpResponseCode(responseCode: Int): FailureKind =
        when (responseCode) {
            408, // Request Timeout
            425, // Too Early
            429, // Too Many Requests
            500, // Internal Server Error
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
