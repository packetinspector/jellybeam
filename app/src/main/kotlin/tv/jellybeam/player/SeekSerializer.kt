package tv.jellybeam.player

/**
 * Serializes D-pad skip seeks against the shared `ExoPlayer` ([PlayerHolder.seekBy]) per
 * docs/18-playback-quality.md §5: overlapping `seekTo` resets left a session stuck in
 * `STATE_BUFFERING` for 90s, so at most one seek is in flight at a time, from issue until Media3
 * reports it landed (`onRenderedFirstFrame()`/`STATE_READY`). A request arriving mid-flight is
 * held, only the latest target kept, and issued once the in-flight seek lands; [landingTimeoutMs]
 * fires the held target anyway if a seek never lands, mirroring manual re-seek recovery.
 *
 * Pure Kotlin, single-threaded by contract (main thread only, no locking); [nowMs] is threaded
 * through each call so tests can drive time without a real clock.
 */
internal class SeekSerializer(private val landingTimeoutMs: Long = LANDING_TIMEOUT_MS) {

    sealed interface Decision {
        /** Nothing was in flight -- the caller must issue [targetMs] immediately. */
        data class Issue(val targetMs: Long) : Decision

        /** A seek is already in flight -- the caller must not issue [targetMs] yet. */
        data class Hold(val targetMs: Long) : Decision
    }

    var inFlightTargetMs: Long? = null
        private set

    var pendingTargetMs: Long? = null
        private set

    var inFlightSinceMs: Long? = null
        private set

    /** Idle -> [Decision.Issue]; in flight -> [Decision.Hold], replacing any held target. */
    fun request(targetMs: Long, nowMs: Long): Decision {
        if (inFlightTargetMs == null) {
            inFlightTargetMs = targetMs
            inFlightSinceMs = nowMs
            return Decision.Issue(targetMs)
        }
        pendingTargetMs = targetMs
        return Decision.Hold(targetMs)
    }

    /**
     * Returns `null` when nothing was in flight. Otherwise clears in-flight state and, if a target
     * was held, promotes it to in-flight and returns it -- even when equal to the one that just
     * landed, since that repeat is the deliberate recovery re-seek.
     */
    fun landed(nowMs: Long): Long? {
        if (inFlightTargetMs == null) return null
        val pending = pendingTargetMs
        inFlightTargetMs = null
        inFlightSinceMs = null
        pendingTargetMs = null
        if (pending == null) return null
        inFlightTargetMs = pending
        inFlightSinceMs = nowMs
        return pending
    }

    /** `true` once the in-flight seek has been in flight for at least [landingTimeoutMs]. */
    fun timeoutDue(nowMs: Long): Boolean {
        val since = inFlightSinceMs ?: return false
        return nowMs - since >= landingTimeoutMs
    }

    fun reset() {
        inFlightTargetMs = null
        pendingTargetMs = null
        inFlightSinceMs = null
    }

    companion object {
        const val LANDING_TIMEOUT_MS = 2_500L
    }
}
