package tv.jellybeam.visualizer.pattern

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.floor

/** docs/30 §9 U11 (E7): superscope's equations and ridgeline's hidden lines, through CPU mirrors of their shaders. */
class SuperscopeRidgelineTest {
    @Test
    fun theMirrorsMatchTheShaderConstants() {
        val scope = ShaderMirrors.shaderText("viz2/superscope.points.vert.glsl")
        assertTrue(scope.contains("const int POINTS = ${ShaderMirrors.SCOPE_POINTS};"))
        assertTrue(scope.contains("const float PERSP = 0.22;"))
        assertTrue(scope.contains("const float SCALE = 0.32;"))
        val ridge = ShaderMirrors.shaderText("viz2/ridgeline.overlay.vert.glsl")
        assertTrue(ridge.contains("const int ROWS = ${ShaderMirrors.RIDGE_ROWS};"))
        assertTrue(ridge.contains("const int SEGS = ${ShaderMirrors.RIDGE_SEGS};"))
        assertTrue(ridge.contains("const int ROW_STEP = ${ShaderMirrors.RIDGE_STEP};"))
        assertTrue(ridge.contains("const float FILL_DROP = 2.0;"))
        assertTrue(ridge.contains("return 1.0 + float(k) * 0.06;"))
        assertTrue(ridge.contains("lineQuad(p0, p1, 1.1, 0.6, c)"))
        // The draw order and fill shape the occlusion test mirrors.
        assertTrue(ridge.contains("int k = ROWS - 1 - j / (2 * SEGS);"))
        assertTrue(ridge.contains("if (rem < SEGS) {"))
        assertTrue(ridge.contains("float yb = baseY(k) - FILL_DROP;"))
        assertTrue(ridge.contains("emit(f, 0.0, 0.0);"))
        assertTrue(scope.contains("* ((0.7 + 0.3 * b) * (1.0 + 0.1 * u_drum.x));"))
        assertTrue(scope.contains("vec2 pos = vec2(x1, y2) * (1.0 / (1.0 - z2 * PERSP)) * minDim() * SCALE;"))
        for (p in listOf(Pattern.SUPERSCOPE, Pattern.RIDGELINE)) {
            val draws = PatternSpecs.spec(p).inject + PatternSpecs.spec(p).overlay
            assertTrue(draws.any { it.vertexShader.endsWith(if (p == Pattern.SUPERSCOPE) "superscope.points.vert.glsl" else "ridgeline.overlay.vert.glsl") })
        }
        val ridgeDraw = PatternSpecs.spec(Pattern.RIDGELINE).overlay.single { it.vertexShader.endsWith("ridgeline.overlay.vert.glsl") }
        assertEquals(ShaderMirrors.RIDGE_ROWS * ShaderMirrors.RIDGE_SEGS * 2, ridgeDraw.instances)
        val scopeDraws = (PatternSpecs.spec(Pattern.SUPERSCOPE).inject + PatternSpecs.spec(Pattern.SUPERSCOPE).overlay)
        for (d in scopeDraws) assertEquals(ShaderMirrors.SCOPE_POINTS, d.instances)
    }

    @Test
    fun superscopePointsAreFiniteAndOnScreenForEveryShapeAndPhase() {
        val clip = FloatArray(2)
        val q = FloatArray(3)
        for (shape in 0..2) {
            for (i in 0 until ShaderMirrors.SCOPE_POINTS) {
                ShaderMirrors.shapePoint(i, shape, q)
                for (v in q) assertTrue("shape $shape point $i", v.isFinite() && abs(v) <= 1f)
            }
            for (step in 0 until 400) {
                val travel = step * 0.5f
                for (band in floatArrayOf(0f, 1f)) for (kick in floatArrayOf(0f, 1f)) for (res in RESOLUTIONS) {
                    for (i in 0 until ShaderMirrors.SCOPE_POINTS) {
                        ShaderMirrors.scopeClip(i, shape, band, kick, travel, res[0], res[1], clip)
                        for (v in clip) assertTrue("shape $shape travel $travel point $i clip $v", v.isFinite() && abs(v) <= 1.05f)
                    }
                }
            }
        }
    }

    @Test
    fun theThreeShapesDiffer() {
        val a = FloatArray(3)
        val b = FloatArray(3)
        for (s in 0..1) {
            var diff = 0f
            for (i in 0 until ShaderMirrors.SCOPE_POINTS) {
                ShaderMirrors.shapePoint(i, s, a)
                ShaderMirrors.shapePoint(i, s + 1, b)
                diff += abs(a[0] - b[0]) + abs(a[1] - b[1]) + abs(a[2] - b[2])
            }
            assertTrue("shape $s vs ${s + 1}", diff > ShaderMirrors.SCOPE_POINTS * 0.1f)
        }
    }

    /**
     * Rasterises the ridgeline overlay in the shader's instance order at 480×270 (fills to the row's baseline
     * less FILL_DROP, lines ±HW about their centre) and compares it with the horizon rule: in each column a
     * row's line shows exactly where it rises above every nearer row's line.
     */
    @Test
    fun aNearerRidgeHidesEveryFartherLineBelowIt() {
        for (seed in 1L..4L) {
            val hist = history(seed)
            val owner = IntArray(W * H) { NONE }
            val m = IntArray(3)
            val p0 = FloatArray(2)
            val p1 = FloatArray(2)
            for (j in 0 until ShaderMirrors.RIDGE_ROWS * ShaderMirrors.RIDGE_SEGS * 2) {
                ShaderMirrors.ridgeInstance(j, m)
                val (k, fill, s) = Triple(m[0], m[1] == 1, m[2])
                ShaderMirrors.ridgePoint(k, s, hist[k][s], W.toFloat(), H.toFloat(), p0)
                ShaderMirrors.ridgePoint(k, s + 1, hist[k][s + 1], W.toFloat(), H.toFloat(), p1)
                for (px in 0 until W) {
                    val x = px + 0.5f - W / 2f
                    if (x < p0[0] || x >= p1[0]) continue
                    val yl = p0[1] + (p1[1] - p0[1]) * (x - p0[0]) / (p1[0] - p0[0])
                    for (py in 0 until H) {
                        val y = py + 0.5f - H / 2f
                        if (fill && y >= ShaderMirrors.baseY(k, H.toFloat()) - ShaderMirrors.RIDGE_FILL_DROP && y <= yl) owner[py * W + px] = fillOf(k)
                        if (!fill && abs(y - yl) <= ShaderMirrors.RIDGE_HW) owner[py * W + px] = lineOf(k)
                    }
                }
            }
            var hidden = 0
            var shown = 0
            for (px in 0 until W) {
                val x = px + 0.5f - W / 2f
                var nearerTop = Float.NEGATIVE_INFINITY
                for (k in 0 until ShaderMirrors.RIDGE_ROWS) {
                    val yl = lineAt(hist, k, x) ?: continue
                    val py = floor(yl + H / 2f).toInt()
                    if (py in 0 until H) {
                        val visible = owner[py * W + px] == lineOf(k)
                        if (visible) {
                            shown++
                            assertTrue("seed $seed col $px row $k at $yl drawn in front of a nearer line at $nearerTop", yl > nearerTop - ShaderMirrors.RIDGE_HW - 1f)
                        } else {
                            hidden++
                        }
                        if (yl > nearerTop + ShaderMirrors.RIDGE_HW + 1.5f) {
                            assertTrue("seed $seed col $px row $k at $yl clear of nearer lines at $nearerTop is hidden", visible)
                        }
                    }
                    nearerTop = maxOf(nearerTop, yl)
                }
            }
            assertTrue("seed $seed: some lines hide ($hidden) and some show ($shown)", hidden > 1000 && shown > 1000)
        }
    }

    private fun lineAt(hist: Array<FloatArray>, k: Int, x: Float): Float? {
        val p0 = FloatArray(2)
        val p1 = FloatArray(2)
        for (s in 0 until ShaderMirrors.RIDGE_SEGS) {
            ShaderMirrors.ridgePoint(k, s, hist[k][s], W.toFloat(), H.toFloat(), p0)
            ShaderMirrors.ridgePoint(k, s + 1, hist[k][s + 1], W.toFloat(), H.toFloat(), p1)
            if (x >= p0[0] && x < p1[0]) return p0[1] + (p1[1] - p0[1]) * (x - p0[0]) / (p1[0] - p0[0])
        }
        return null
    }

    /** Band values per row: spiky mountains over a low floor, so ridges cross and hide each other. */
    private fun history(seed: Long): Array<FloatArray> {
        val rnd = Random(seed)
        return Array(ShaderMirrors.RIDGE_ROWS) {
            val f = FloatArray(64)
            for (i in 0 until 64) f[i] = 0.05f + 0.1f * rnd.nextFloat()
            repeat(3) {
                val c = rnd.nextInt(64)
                val hgt = rnd.nextFloat()
                for (i in 0 until 64) f[i] = maxOf(f[i], hgt * (1f - abs(i - c) / 6f))
            }
            f
        }
    }

    private companion object {
        const val W = 480
        const val H = 270
        const val NONE = -1
        val RESOLUTIONS = arrayOf(floatArrayOf(480f, 270f), floatArrayOf(1920f, 1080f))

        fun fillOf(k: Int) = 2 * k + 1
        fun lineOf(k: Int) = 2 * k
    }
}
