package tv.jellybeam.player

import kotlin.math.ceil

/**
 * Derivation of the still-watching card's visible countdown, mirroring [NextUpCountdown]'s shape.
 * [PlaybackViewModel] still owns `timeoutSecs` and the timeout job firing
 * [PlaybackViewModel.stillWatchingStop]; this only computes remaining time from a wall-clock
 * `deadlineMs`, read per frame by [PlaybackScreen]'s own loop (docs/12 §13).
 */
object StillWatchingCountdown {
    /** Whole seconds remaining until [deadlineMs], ceiling so it reads the full timeout on the
     * first frame; clamped to `>= 0`.
     */
    fun remainingWholeSecs(deadlineMs: Long, nowMs: Long): Long {
        val remainingMs = (deadlineMs - nowMs).coerceAtLeast(0L)
        return ceil(remainingMs / 1000.0).toLong()
    }

    /** Depleting-rule fraction (design 1c): `1f` right after [deadlineMs] is set, `0f` once time
     * runs out, read straight off the wall clock every frame; `0f` for a non-positive
     * [timeoutTotalSecs].
     */
    fun remainingFraction(timeoutTotalSecs: Double, deadlineMs: Long, nowMs: Long): Float {
        if (timeoutTotalSecs <= 0.0) return 0f
        val remainingMs = (deadlineMs - nowMs).toDouble()
        return (remainingMs / (timeoutTotalSecs * 1000.0)).toFloat().coerceIn(0f, 1f)
    }
}
