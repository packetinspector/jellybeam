package tv.jellybeam.visualizer.pattern

import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * CPU mirrors of the viz2 shaders the JVM tests hold to docs/30 §5–6 (U8, U10, U11). Each function copies
 * its GLSL formula term for term in Float maths; [shaderText] lets a test check the shared constants.
 */
internal object ShaderMirrors {
    fun shaderText(path: String): String = File("src/main/assets/$path").readText()

    /** common.glsl `hashU` (lowbias32); uint arithmetic wraps exactly as Int arithmetic does. */
    fun hashU(x: Int): Int {
        var h = x
        h = h xor (h ushr 16)
        h *= 0x7feb352d
        h = h xor (h ushr 15)
        h *= 0x846ca68b.toInt()
        h = h xor (h ushr 16)
        return h
    }

    fun hash01(x: Int): Float = (hashU(x) ushr 8) * (1f / 16777216f)

    // -- warp.vert.glsl ------------------------------------------------------------------------

    const val GX = 32
    const val GY = 24

    /** The source uv of grid vertex ([cx], [cy]) for u_warp = [w], written to [out]. */
    fun warpSource(cx: Int, cy: Int, w: FloatArray, timeS: Float, aspect: Float, out: FloatArray) {
        val ux = cx.toFloat() / GX
        val uy = cy.toFloat() / GY
        val px = (ux - w[8]) * aspect
        val py = uy - w[9]
        var r = sqrt(px * px + py * py)
        var a = if (r > 0f) atan2(py, px) else 0f
        a -= w[1] + w[2] * max(0f, 1f - r * 1.6f)
        r /= max(w[0], 0.01f)
        r += w[6] * sin(r * w[7] - timeS * 3f)
        var sx = w[8] + cos(a) * r / aspect - w[4]
        var sy = w[9] + sin(a) * r - w[5]
        val seed = (cx + cy * 97) + (timeS * 60f).toInt() * 7919
        sx += (hash01(seed) - 0.5f) * w[10]
        sy += (hash01(seed xor 0x9e3779b9.toInt()) - 0.5f) * w[10]
        out[0] = sx
        out[1] = sy
    }

    // -- ripples.sim.frag.glsl -----------------------------------------------------------------

    const val SIM_W = 120
    const val SIM_H = 68
    const val SIM_SCALE = 2048f
    const val SIM_C2 = 0.45f
    const val SIM_DAMP = 0.97f

    /** The ripple grid as the shader stores it: quantised 16-bit h and last step's h per cell. */
    class Ripples {
        val h = IntArray(SIM_W * SIM_H) { 32768 }
        val hp = IntArray(SIM_W * SIM_H) { 32768 }
        private val nh = IntArray(SIM_W * SIM_H)

        fun value(q: Int): Float = (q - 32768) / SIM_SCALE

        fun at(x: Int, y: Int): Float = value(h[y.coerceIn(0, SIM_H - 1) * SIM_W + x.coerceIn(0, SIM_W - 1)])

        private fun drop(p: FloatArray, o: Int, x: Int, y: Int): Float {
            if (p[o + 2] <= 0f) return 0f
            val qx = (x + 0.5f - p[o]) / p[o + 3]
            val qy = (y + 0.5f - p[o + 1]) / p[o + 3]
            return p[o + 2] * exp(-(qx * qx + qy * qy))
        }

        private fun enc(v: Float): Int = (floor(v * SIM_SCALE + 0.5f) + 32768f).coerceIn(0f, 65535f).toInt()

        /** One shader pass with u_param = [param] and u_time.w = [dtS]. */
        fun step(param: FloatArray, dtS: Float) {
            if (dtS <= 0f) return
            for (y in 0 until SIM_H) for (x in 0 until SIM_W) {
                val c = value(h[y * SIM_W + x])
                val p = value(hp[y * SIM_W + x])
                val lap = at(x + 1, y) + at(x - 1, y) + at(x, y + 1) + at(x, y - 1) - 4f * c
                val n = (2f * c - p + SIM_C2 * lap) * SIM_DAMP + drop(param, 0, x, y) + drop(param, 4, x, y)
                nh[y * SIM_W + x] = enc(n)
            }
            h.copyInto(hp)
            nh.copyInto(h)
        }

        /** Displacement plus motion energy. */
        fun energy(): Double {
            var e = 0.0
            for (i in h.indices) {
                val c = value(h[i]).toDouble()
                val d = c - value(hp[i])
                e += c * c + d * d
            }
            return e
        }

        fun maxAbs(): Float {
            var m = 0f
            for (q in h) m = max(m, abs(value(q)))
            return m
        }
    }

    // -- superscope.points.vert.glsl -----------------------------------------------------------

    const val SCOPE_POINTS = 1024
    const val SCOPE_PERSP = 0.22f
    const val SCOPE_SCALE = 0.32f
    private const val TAU = (2 * PI).toFloat()
    private const val PIF = PI.toFloat()

    fun shapePoint(i: Int, shape: Int, out: FloatArray) {
        val fi = i.toFloat()
        when (shape) {
            0 -> {
                val y = 1f - 2f * (fi + 0.5f) / SCOPE_POINTS
                val rr = sqrt(max(0f, 1f - y * y))
                val ph = fi * 2.39996323f
                out[0] = rr * cos(ph); out[1] = y; out[2] = rr * sin(ph)
            }
            1 -> {
                val al = TAU * (i % 32) / 32f
                val be = TAU * (i / 32) / 32f
                val ring = 0.68f + 0.32f * cos(al)
                out[0] = ring * cos(be); out[1] = 0.32f * sin(al); out[2] = ring * sin(be)
            }
            else -> {
                val tt = (i / 2).toFloat() / (SCOPE_POINTS / 2 - 1)
                val a = tt * TAU * 3f + (i % 2) * PIF
                out[0] = 0.55f * cos(a); out[1] = tt * 1.8f - 0.9f; out[2] = 0.55f * sin(a)
            }
        }
    }

    /** Clip-space position of point [i] at a [w]×[h] target, written to [out]. */
    fun scopeClip(i: Int, shape: Int, band: Float, kick: Float, travel: Float, w: Float, h: Float, out: FloatArray) {
        val q = FloatArray(3)
        shapePoint(i, shape, q)
        val s = (0.7f + 0.3f * band) * (1f + 0.1f * kick)
        val qx = q[0] * s
        val qy = q[1] * s
        val qz = q[2] * s
        val yaw = travel
        val pitch = 0.35f + 0.45f * sin(travel * 0.37f)
        val x1 = qx * cos(yaw) - qz * sin(yaw)
        val z1 = qx * sin(yaw) + qz * cos(yaw)
        val y2 = qy * cos(pitch) - z1 * sin(pitch)
        val z2 = qy * sin(pitch) + z1 * cos(pitch)
        val k = (1f / (1f - z2 * SCOPE_PERSP)) * min(w, h) * SCOPE_SCALE
        out[0] = x1 * k * 2f / w
        out[1] = y2 * k * 2f / h
    }

    // -- ridgeline.overlay.vert.glsl -----------------------------------------------------------

    const val RIDGE_ROWS = 64
    const val RIDGE_SEGS = 63
    const val RIDGE_STEP = 2
    const val RIDGE_FILL_DROP = 2f
    const val RIDGE_HW = 1.1f

    fun rowZ(k: Int): Float = 1f + k * 0.06f

    fun baseY(k: Int, h: Float): Float {
        val hy = h * 0.5f
        val yh = hy * 0.55f
        return yh + (-0.9f * hy - yh) / rowZ(k)
    }

    /** Point [i] of row [k] for band value [b], origin at the target's centre, y up. */
    fun ridgePoint(k: Int, i: Int, b: Float, w: Float, h: Float, out: FloatArray) {
        val z = rowZ(k)
        val xn = i.toFloat() / RIDGE_SEGS
        out[0] = (xn * 2f - 1f) * w * 0.5f * 0.95f * (0.55f + 0.45f / z)
        val taper = 0.35f + 0.65f * sin(PIF * xn)
        out[1] = baseY(k, h) + b * h * 0.5f * 0.55f * taper / z
    }

    /** The shader's instance mapping in draw order: [out] = row, 1 for a fill (0 for a line), segment. */
    fun ridgeInstance(j: Int, out: IntArray) {
        val k = RIDGE_ROWS - 1 - j / (2 * RIDGE_SEGS)
        val rem = j % (2 * RIDGE_SEGS)
        out[0] = k
        out[1] = if (rem < RIDGE_SEGS) 1 else 0
        out[2] = rem % RIDGE_SEGS
    }
}
