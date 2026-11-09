package tv.jellybeam.visualizer.color

import kotlin.math.abs

/**
 * docs/30 §4 (E4): the composite gain from this frame's measured curve of shown mean luminance at [GAINS].
 * Pure and allocation-free.
 */
object GainCurve {
    /** The gains the stats pass measures at (lum.frag.glsl's GAINS); index [ONE] is gain 1. */
    val GAINS = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f, 1.5f, 2.5f, 4f)
    const val ONE = 4

    /** Luminance differences below this are the same picture. */
    private const val SAME = 1e-5f

    /**
     * The gain nearest 1 whose shown luminance meets [want], linear between knots: walking down from 1 for a
     * dimmer frame, up for a brighter one; past the last knot the extreme gain.
     */
    fun solve(shown: FloatArray, want: Float): Float {
        val now = shown[ONE]
        if (abs(now - want) <= SAME) return 1f
        if (want < now) {
            for (k in ONE - 1 downTo 0) {
                if (shown[k] <= want) return between(k, k + 1, k + 1, shown, want)
            }
            return GAINS[0]
        }
        for (k in ONE + 1 until GAINS.size) {
            if (shown[k] >= want) return between(k - 1, k, k - 1, shown, want)
        }
        return GAINS[GAINS.size - 1]
    }

    /** Linear between knots [lo] and [hi]; a flat segment takes [nearOne], the knot closer to gain 1. */
    private fun between(lo: Int, hi: Int, nearOne: Int, shown: FloatArray, want: Float): Float {
        val span = shown[hi] - shown[lo]
        if (abs(span) <= SAME) return GAINS[nearOne]
        val t = ((want - shown[lo]) / span).coerceIn(0f, 1f)
        return GAINS[lo] + (GAINS[hi] - GAINS[lo]) * t
    }
}
