package tv.jellybeam.player

import androidx.media3.common.C
import androidx.media3.common.text.Cue
import androidx.media3.extractor.text.CuesWithTiming

/**
 * A parsed sidecar as segments of unchanging cues (docs/18 §3.2), each one shared list the feed compares by identity.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SidecarCues private constructor(
    /** Segment `i` covers `[startsUs[i], startsUs[i + 1])`; the last runs to the end. */
    private val startsUs: LongArray,
    private val segments: List<List<Cue>>,
) {
    private fun segmentAt(positionUs: Long): Int {
        var lo = 0
        var hi = startsUs.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (startsUs[mid] <= positionUs) lo = mid + 1 else hi = mid
        }
        return lo - 1
    }

    /** The cues visible at [positionUs]; the same instance for every position in one segment. */
    fun cuesAt(positionUs: Long): List<Cue> = segments.getOrNull(segmentAt(positionUs)) ?: emptyList()

    /** The next position after [positionUs] where the visible cues change, or [Long.MAX_VALUE]. */
    fun nextChangeUs(positionUs: Long): Long = startsUs.getOrElse(segmentAt(positionUs) + 1) { Long.MAX_VALUE }

    private class Span(val startUs: Long, val endUs: Long, val cues: List<Cue>)

    companion object {
        /** The feed's longest sleep, so a seek or a speed change is caught within this many ms. */
        const val MAX_POLL_MS = 120L
        private const val MIN_POLL_MS = 16L

        /** Cues drawn at once; more is unreadable, and the cap keeps segments linear in size. */
        const val MAX_VISIBLE = 8

        /** Wall-clock sleep until the next change at playback [rate], so a fast rate never skips a cue. */
        fun pollDelayMs(untilChangeUs: Long, rate: Float = 1f): Long =
            (untilChangeUs / 1000.0 / rate.coerceAtLeast(MIN_RATE)).toLong().coerceIn(MIN_POLL_MS, MAX_POLL_MS)

        private const val MIN_RATE = 0.1f

        /**
         * Builds the segments; null when nothing would ever show. An entry with no duration lasts
         * until the next entry starts, matching how Media3 replaces cues.
         */
        fun of(entries: List<CuesWithTiming>): SidecarCues? {
            // Empty entries still end the one before them, so they are dropped only after.
            val timed = entries.sortedBy { it.startTimeUs }
            val spans = timed.mapIndexed { i, entry ->
                val end = if (entry.durationUs == C.TIME_UNSET) {
                    var j = i + 1
                    while (j < timed.size && timed[j].startTimeUs <= entry.startTimeUs) j++
                    timed.getOrNull(j)?.startTimeUs ?: Long.MAX_VALUE
                } else {
                    entry.startTimeUs + entry.durationUs
                }
                Span(entry.startTimeUs, end, entry.cues)
            }.filter { it.endUs > it.startUs && it.cues.isNotEmpty() }
            if (spans.isEmpty()) return null
            val boundaries = spans.flatMap { listOf(it.startUs, it.endUs) }
                .filter { it != Long.MAX_VALUE }
                .distinct()
                .sorted()
            // One sweep, O(n log n): spans enter in start order and leave by end; each segment keeps
            // at most MAX_VISIBLE of them, so heavy overlap can't grow memory quadratically.
            val active = java.util.TreeSet<Int>()
            val byEnd = java.util.PriorityQueue<Int>(compareBy { spans[it].endUs })
            var next = 0
            var shownIds: List<Int> = emptyList()
            var shown: List<Cue> = emptyList()
            val segments = boundaries.map { at ->
                while (byEnd.isNotEmpty() && spans[byEnd.peek()].endUs <= at) active.remove(byEnd.poll())
                while (next < spans.size && spans[next].startUs <= at) {
                    if (spans[next].endUs > at) {
                        active += next
                        byEnd += next
                    }
                    next++
                }
                val ids = active.take(MAX_VISIBLE)
                if (ids != shownIds) {
                    shownIds = ids
                    shown = ids.flatMap { spans[it].cues }.take(MAX_VISIBLE)
                }
                shown
            }
            return SidecarCues(boundaries.toLongArray(), segments)
        }
    }
}
