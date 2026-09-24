package tv.jellybeam.player

/** OSD position/duration text formatting (GOAL item 3: "H:MM:SS / M:SS", Martian Mono 14sp). */
object PlaybackTimeFormat {
    /** `"H:MM:SS"` once an hour is reached, else `"M:SS"`. Negative input clamps to 0 rather than
     * showing a sign.
     */
    fun format(ms: Long): String {
        val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }

    /**
     * The OSD's right-hand time label (docs/12-osd-ux-spec.md scrub row, build order item 11):
     * `"-M:SS"`/`"-H:MM:SS"` via [format]. Select-to-toggle into `"pos / total"` is deferred (see
     * [tv.jellybeam.player.PlaybackScreen]). Clamps to `"-0:00"` once [positionMs] reaches
     * [durationMs].
     */
    fun formatRemaining(positionMs: Long, durationMs: Long): String {
        val remainingMs = (durationMs - positionMs).coerceAtLeast(0L)
        return "-" + format(remainingMs)
    }
}
