package tv.jellybeam.player

import tv.jellybeam.R
import tv.jellybeam.i18n.UiStrings

/**
 * The player OSD's one buffering pill (docs/12-osd-ux-spec.md "Transients"). [PlaybackViewModel]
 * sets [PlaybackUiState.bufferingInfo] to non-null whenever stalled while it should be playing
 * (`Player.STATE_BUFFERING && PlaybackPlayer.playWhenReady`), covering initial start, post-seek,
 * and
 * a mid-play rebuffer with one readout; a short post-seek transition is debounced by
 * [USER_SEEK_BUFFERING_GRACE_MS]. One subtle always-the-same pill, deliberately not a
 * two-treatment split -- only its percent-plus-network-speed numbers are used.
 */
data class BufferingInfo(val percent: Int, val bytesPerSec: Long) {
    companion object {
        /**
         * Media3's `DefaultLoadControl.bufferForPlaybackMs` ([PlayerHolder.buildPlayer]'s
         * `LoadControl`):
         * ms banked ahead of the playhead before a cold start resumes. Single source of truth --
         * [percentTowardResume] is computed against this same constant so the two can't drift
         * apart.
         */
        const val INITIAL_RESUME_THRESHOLD_MS = 1_000L

        /** `DefaultLoadControl.bufferForPlaybackAfterRebufferMs`: resume threshold once the first
         * frame has already rendered (seek or mid-play stall), higher than
         * [INITIAL_RESUME_THRESHOLD_MS].
         */
        const val REBUFFER_RESUME_THRESHOLD_MS = 2_000L

        /**
         * Progress toward resuming: [bufferAheadMs] as a percent of [resumeThresholdMs].
         * Deliberately
         * not [androidx.media3.common.Player.getBufferedPercentage] (percent of the whole item),
         * which
         * stays near-zero early into a long movie even once enough is banked to resume. Clamped to
         * `[0, 100]`; a non-positive [resumeThresholdMs] reads as 100.
         */
        fun percentTowardResume(bufferAheadMs: Long, resumeThresholdMs: Long): Int {
            if (resumeThresholdMs <= 0L) return 100
            val raw = (bufferAheadMs.coerceAtLeast(0L) * 100L) / resumeThresholdMs
            return raw.coerceIn(0L, 100L).toInt()
        }

        /** `"12.4 MB/s"` -- decimal MB (1,000,000 bytes/sec), one decimal, clamped to `>= 0`. */
        fun formatThroughput(strings: UiStrings, bytesPerSec: Long): String =
            strings.get(R.string.player_throughput_mbps, bytesPerSec.coerceAtLeast(0L) / 1_000_000.0)
    }
}
