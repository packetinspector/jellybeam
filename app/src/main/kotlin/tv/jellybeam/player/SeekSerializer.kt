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

    /**
     * docs/28 §5: the owed landing report (position ms, ms since issue); a newer request's report replaces it, and a
     * request without one keeps it for its own landing, since an owed report that never fires leaves the member silent.
     */
    private var onLanded: ((Long, Long) -> Unit)? = null

    /** Idle -> [Decision.Issue]; in flight -> [Decision.Hold], replacing any held target. */
    fun request(targetMs: Long, nowMs: Long, onLanded: ((landedMs: Long, latencyMs: Long) -> Unit)? = null): Decision {
        if (onLanded != null) this.onLanded = onLanded
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
     * landed, since that repeat is the deliberate recovery re-seek. With nothing held, the report
     * hears [positionMs], the player position; null (no position known) reports nothing.
     */
    fun landed(nowMs: Long, positionMs: Long? = null): Long? {
        val since = inFlightSinceMs ?: return null
        val pending = pendingTargetMs
        inFlightTargetMs = null
        inFlightSinceMs = null
        pendingTargetMs = null
        if (pending == null) {
            // Taken before it runs, so a report that requests a new seek keeps that seek's report.
            val report = onLanded.also { onLanded = null }
            positionMs?.let { report?.invoke(it, nowMs - since) }
            return null
        }
        inFlightTargetMs = pending
        inFlightSinceMs = nowMs
        return pending
    }

    /** The landing timeout's recovery: once [timeoutDue], lands as [landed] does, report included, since
     * an owed report that never fires stalls the group and one off target is re-sent a Seek (docs/28 §5). */
    fun stalled(nowMs: Long, positionMs: Long): Long? {
        if (!timeoutDue(nowMs)) return null
        return landed(nowMs, positionMs)
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
        onLanded = null
    }

    companion object {
        const val LANDING_TIMEOUT_MS = 2_500L
    }
}
