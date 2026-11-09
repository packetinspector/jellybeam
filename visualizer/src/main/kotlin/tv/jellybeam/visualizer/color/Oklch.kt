package tv.jellybeam.visualizer.color

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * docs/30 §4: sRGB <-> OKLab/OKLCH (Ottosson's matrices) in doubles; palette build is one-off, so no float tricks.
 * Hue is in degrees, 0..360. Outputs go into caller arrays so nothing allocates.
 */
object Oklch {
    /** sRGB-encoded 0..1 to linear light; sign-preserving so out-of-gamut values survive a round trip. */
    fun srgbToLinear(c: Double): Double {
        val a = abs(c)
        val l = if (a <= 0.04045) a / 12.92 else ((a + 0.055) / 1.055).pow(2.4)
        return if (c < 0.0) -l else l
    }

    fun linearToSrgb(l: Double): Double {
        val a = abs(l)
        val c = if (a <= 0.0031308) a * 12.92 else 1.055 * a.pow(1.0 / 2.4) - 0.055
        return if (l < 0.0) -c else c
    }

    /** [out] = L, C, hue (degrees). */
    fun fromSrgb(r: Float, g: Float, b: Float, out: DoubleArray) {
        val lr = srgbToLinear(r.toDouble())
        val lg = srgbToLinear(g.toDouble())
        val lb = srgbToLinear(b.toDouble())
        val l = cbrt(0.4122214708 * lr + 0.5363325363 * lg + 0.0514459929 * lb)
        val m = cbrt(0.2119034982 * lr + 0.6806995451 * lg + 0.1073969566 * lb)
        val s = cbrt(0.0883024619 * lr + 0.2817188376 * lg + 0.6299787005 * lb)
        val ll = 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s
        val a = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s
        val bb = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s
        out[0] = ll
        out[1] = sqrt(a * a + bb * bb)
        var h = Math.toDegrees(atan2(bb, a))
        if (h < 0.0) h += 360.0
        out[2] = h
    }

    /** [out] = sRGB-encoded r, g, b, NOT clamped: a value outside 0..1 means the colour is out of gamut. */
    fun toSrgb(l: Double, c: Double, hDeg: Double, out: DoubleArray) {
        val hr = Math.toRadians(hDeg)
        val a = c * cos(hr)
        val b = c * sin(hr)
        val l1 = l + 0.3963377774 * a + 0.2158037573 * b
        val m1 = l - 0.1055613458 * a - 0.0638541728 * b
        val s1 = l - 0.0894841775 * a - 1.2914855480 * b
        val l3 = l1 * l1 * l1
        val m3 = m1 * m1 * m1
        val s3 = s1 * s1 * s1
        out[0] = linearToSrgb(4.0767416621 * l3 - 3.3077115913 * m3 + 0.2309699292 * s3)
        out[1] = linearToSrgb(-1.2684380046 * l3 + 2.6097574011 * m3 - 0.3413193965 * s3)
        out[2] = linearToSrgb(-0.0041960863 * l3 - 0.7034186147 * m3 + 1.7076147010 * s3)
    }

    /**
     * Gamut map by shrinking chroma at constant [l] and [hDeg] (binary search): [out] gets the in-gamut sRGB
     * clamped to 0..1 (the clamp only removes search residue); returns the chroma used.
     */
    fun mapToGamut(l: Double, c: Double, hDeg: Double, out: DoubleArray): Double {
        if (l >= 1.0 || l <= 0.0) {
            val v = if (l >= 1.0) 1.0 else 0.0
            out[0] = v; out[1] = v; out[2] = v
            return 0.0
        }
        var used = c
        toSrgb(l, used, hDeg, out)
        if (!inCube(out)) {
            var lo = 0.0
            var hi = c
            repeat(SEARCH_STEPS) {
                val mid = (lo + hi) * 0.5
                toSrgb(l, mid, hDeg, out)
                if (inCube(out)) lo = mid else hi = mid
            }
            used = lo
            toSrgb(l, used, hDeg, out)
        }
        for (i in 0 until 3) out[i] = out[i].coerceIn(0.0, 1.0)
        return used
    }

    /** WCAG relative luminance of an sRGB-encoded colour. */
    fun relativeLuminance(r: Float, g: Float, b: Float): Float =
        (0.2126 * srgbToLinear(r.toDouble()) + 0.7152 * srgbToLinear(g.toDouble()) + 0.0722 * srgbToLinear(b.toDouble())).toFloat()

    /** WCAG contrast ratio of two relative luminances, either order. */
    fun contrast(y1: Float, y2: Float): Float = (maxOf(y1, y2) + 0.05f) / (minOf(y1, y2) + 0.05f)

    private const val SEARCH_STEPS = 30
    private const val CUBE_EPS = 1e-7

    private fun inCube(rgb: DoubleArray) = rgb.all { it >= -CUBE_EPS && it <= 1.0 + CUBE_EPS }
}
