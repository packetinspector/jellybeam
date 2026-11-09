package tv.jellybeam.visualizer.color

import kotlin.math.abs

/**
 * docs/30 §4 (E4, WCAG 2.3.1): at most [MAX_FLASHES] flashes in any rolling second, always on, no setting.
 * A flash is one transition: a luminance change above [THRESHOLD], or any change into or out of saturated red.
 * Every transition counts, so an opposing pair costs two; this is stricter than WCAG's one-per-pair.
 * Pure and deterministic: time is passed in, nothing allocates.
 */
class FlashLimiter {
    // Times of the last MAX_FLASHES counted flashes; NEGATIVE_INFINITY marks an unused slot.
    private val stamps = FloatArray(MAX_FLASHES) { Float.NEGATIVE_INFINITY }
    private var hasPrev = false
    private var prevOut = 0f
    private var runOrigin = 0f
    private var runDir = 0
    private var runCounted = false

    /**
     * Fraction (0..1) of a requested change of [lumDelta] the caller may apply now. Under [THRESHOLD] it passes
     * unless [redSaturated]; a red change costs a flash whatever its size. With the budget spent a luminance change
     * is cut to stay below [THRESHOLD], and a red change is refused (0) because it cannot be softened.
     */
    fun admit(nowS: Float, lumDelta: Float, redSaturated: Boolean): Float {
        val mag = abs(lumDelta)
        if (!redSaturated && mag <= THRESHOLD) return 1f
        if (budgetLeft(nowS)) {
            record(nowS)
            return 1f
        }
        return if (redSaturated) 0f else SOFT_LIMIT / mag
    }

    /**
     * The mean luminance to show for a frame that would show [target] untouched, within the reachable
     * [lowest]..[highest]. Output moves monotonically from the last output; a run of same-direction movement is
     * one transition, counted when its swing would pass [THRESHOLD]. With no budget the output holds
     * [SOFT_LIMIT] from where the run began, and lands once a flash frees up. A swing the range can't hold is
     * still counted, so the budget stays true to the screen.
     */
    fun limit(nowS: Float, target: Float, lowest: Float, highest: Float): Float {
        val t = target.coerceIn(0f, 1f)
        if (!hasPrev) {
            hasPrev = true
            prevOut = t
            runOrigin = t
        }
        val d = t - prevOut
        var out = t
        if (abs(d) > EPS) {
            val dir = if (d > 0f) 1 else -1
            if (dir != runDir) {
                runDir = dir
                runOrigin = prevOut
                runCounted = false
            }
            if (!runCounted && abs(t - runOrigin) > THRESHOLD) {
                if (budgetLeft(nowS)) {
                    record(nowS)
                    runCounted = true
                } else {
                    out = runOrigin + dir * SOFT_LIMIT
                }
            }
        }
        out = out.coerceIn(minOf(lowest, t), maxOf(highest, t))
        if (!runCounted && abs(out - runOrigin) > THRESHOLD) {
            record(nowS)
            runCounted = true
        }
        prevOut = out
        return out
    }

    /** Counted flashes still inside the rolling second ending at [nowS]. */
    fun flashesInWindow(nowS: Float): Int {
        var n = 0
        for (i in 0 until MAX_FLASHES) if (nowS - stamps[i] < WINDOW_S) n++
        return n
    }

    fun reset() {
        for (i in 0 until MAX_FLASHES) stamps[i] = Float.NEGATIVE_INFINITY
        hasPrev = false
        runDir = 0
        runCounted = false
    }

    private fun budgetLeft(nowS: Float) = flashesInWindow(nowS) < MAX_FLASHES

    // The oldest stamp is expired whenever the budget is left, so it is the slot to reuse.
    private fun record(nowS: Float) {
        var oldest = 0
        for (i in 1 until MAX_FLASHES) if (stamps[i] < stamps[oldest]) oldest = i
        stamps[oldest] = nowS
    }

    companion object {
        /** A mean relative-luminance change above this is a flash. */
        const val THRESHOLD = 0.10f
        const val MAX_FLASHES = 3

        // A little over one second so float time rounding can never let a fourth flash into a 1 s window.
        private const val WINDOW_S = 1.002f
        // Under the threshold with room for the gain curve's linear steps between measured knots.
        private const val SOFT_LIMIT = 0.07f
        private const val EPS = 1e-6f

        /** WCAG saturated red: R / (R + G + B) >= 0.8, taken in linear light (the stricter reading). */
        fun isSaturatedRed(r: Float, g: Float, b: Float): Boolean {
            val lr = Oklch.srgbToLinear(r.toDouble())
            val sum = lr + Oklch.srgbToLinear(g.toDouble()) + Oklch.srgbToLinear(b.toDouble())
            return sum > 0.0 && lr / sum >= 0.8
        }
    }
}
