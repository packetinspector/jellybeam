package tv.jellybeam.player

/** Decisions on which player state transitions warrant an FFI round-trip
 * (`reportPosition`/`reportPaused`), JVM-testable apart from [PlaybackViewModel].
 */
object ProgressReportGate {
    /** Reports only on an actual pause/resume change; `null` (no report yet) always reports the
     * first state.
     */
    fun shouldReportPaused(previousPaused: Boolean?, newPaused: Boolean): Boolean =
        previousPaused != newPaused

    /** The 1s position-report ticker (GOAL item 4) reports only while playing; pause is
     * [shouldReportPaused]'s job.
     */
    fun shouldReportPosition(isPlaying: Boolean): Boolean = isPlaying
}
