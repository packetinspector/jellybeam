package tv.jellybeam.visualizer.lab

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tv.jellybeam.visualizer.Features
import tv.jellybeam.visualizer.color.Palettes
import tv.jellybeam.visualizer.pattern.Pattern
import tv.jellybeam.visualizer.pattern.PatternSpecs
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** docs/30 §9 L2, L4, L6: properties every frame must keep, without goldens. */
@RunWith(AndroidJUnit4::class)
class InvariantTest {
    // Level 0 is black (docs/30 §4): anything within LIT of it is unlit.
    private fun lit(f: Frame, x: Int, y: Int) = f.r(x, y) > LIT || f.g(x, y) > LIT || f.b(x, y) > LIT

    private fun litFraction(f: Frame): Double {
        var n = 0
        for (y in 0 until f.height) for (x in 0 until f.width) if (lit(f, x, y)) n++
        return n.toDouble() / (f.width * f.height)
    }

    /** L2: loud for 120 frames, then silence: every pattern lights under 2% within 3 s. */
    @Test
    fun feedbackDecaysToDarkWithinThreeSecondsOfSilence() {
        // One continuous signal, so the analyser's own release and silence handling are part of the test.
        val loudThenSilent = LabSignals.loudThenSilent(120, 180)
        val failures = mutableListOf<String>()
        for (p in Pattern.entries) Lab(p).use { lab ->
            val frame = lab.captureAfter(loudThenSilent)
            val frac = litFraction(frame)
            Log.i("VizInv", "decay ${p.key} lit=${"%.4f".format(frac)}")
            if (frac >= 0.02) failures += "${p.key} lit ${"%.2f".format(frac * 100)}% after 3 s of silence"
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    /**
     * L4: every lit pixel is a colour of the pattern's palette family surface (±6/255), or an overlay
     * edge: a coverage blend of two on-palette neighbours within 2 px. Up to 1% may be neither (logged
     * decision, docs/30 §6), and none is brighter than the palette's top.
     */
    @Test
    fun litPixelsAreThePalettesColours() {
        val failures = mutableListOf<String>()
        val sequences = listOf(Preset.TONE, Preset.KICK, Preset.NOISE).associateWith { LabSignals.sequence(it, 120) }
        // One 16 MB grid at a time: patterns grouped by family.
        var gridFamily = -1
        var current: PaletteGrid? = null
        for (p in Pattern.entries.sortedBy { PatternSpecs.spec(it).family.ordinal }) {
            val family = PatternSpecs.spec(p).family.ordinal
            if (family != gridFamily) {
                current = null
                current = PaletteGrid(LabActivity.Harness.defaultPalettes[family])
                gridFamily = family
            }
            val grid = current!!
            for ((preset, seq) in sequences) Lab(p).use { lab ->
                val f = lab.captureAfter(seq)
                var litCount = 0
                var off = 0
                var brighter = 0
                for (y in 0 until f.height) for (x in 0 until f.width) {
                    if (!lit(f, x, y)) continue
                    litCount++
                    if (!grid.contains(f.r(x, y), f.g(x, y), f.b(x, y)) && !isEdgeBlend(f, grid, x, y)) off++
                    if (luma(f.r(x, y), f.g(x, y), f.b(x, y)) > grid.topLuma + 1.0) brighter++
                }
                val offFrac = if (litCount == 0) 0.0 else off.toDouble() / litCount
                Log.i("VizInv", "palette ${p.key}/$preset lit=$litCount off=${"%.4f".format(offFrac)} brighter=$brighter")
                if (offFrac > 0.01) failures += "${p.key}/$preset ${"%.2f".format(offFrac * 100)}% of lit pixels off the palette"
                if (brighter > 0) failures += "${p.key}/$preset $brighter pixels brighter than the palette top"
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    /** True when [x],[y] lies (±[TOL] per channel) on the segment between two on-palette pixels within 2 px. */
    private fun isEdgeBlend(f: Frame, grid: PaletteGrid, x: Int, y: Int): Boolean {
        val px = intArrayOf(f.r(x, y), f.g(x, y), f.b(x, y))
        val near = ArrayList<IntArray>(24)
        for (dy in -2..2) for (dx in -2..2) {
            val nx = x + dx
            val ny = y + dy
            if ((dx == 0 && dy == 0) || nx !in 0 until f.width || ny !in 0 until f.height) continue
            val c = intArrayOf(f.r(nx, ny), f.g(nx, ny), f.b(nx, ny))
            if (grid.contains(c[0], c[1], c[2])) near += c
        }
        for (i in near.indices) for (j in i + 1 until near.size) {
            val a = near[i]
            val b = near[j]
            var num = 0.0
            var den = 0.0
            for (c in 0..2) {
                num += (px[c] - a[c]).toDouble() * (b[c] - a[c])
                den += (b[c] - a[c]).toDouble() * (b[c] - a[c])
            }
            val t = if (den == 0.0) 0.0 else (num / den).coerceIn(0.0, 1.0)
            if ((0..2).all { c -> abs(px[c] - (a[c] + t * (b[c] - a[c]))) <= TOL }) return true
        }
        return false
    }

    /** L6: the kaleidoscope matches its own 60° rotation. */
    @Test
    fun kaleidoscopeMatchesItsSixtyDegreeRotation() {
        Lab(Pattern.KALEIDO).use { lab ->
            val f = lab.captureAfter(LabSignals.sequence(Preset.NOISE, 120))
            val cx = f.width / 2.0
            val cy = f.height / 2.0
            val c = cos(PI / 3)
            val s = sin(PI / 3)
            var differ = 0
            var litCount = 0
            for (y in 0 until f.height) for (x in 0 until f.width) {
                val dx = x + 0.5 - cx
                val dy = y + 0.5 - cy
                val rx = (cx + dx * c - dy * s - 0.5).roundToInt()
                val ry = (cy + dx * s + dy * c - 0.5).roundToInt()
                val a = lit(f, x, y)
                if (a) litCount++
                // Only compare where the rotated point is on screen (the frame is wider than tall).
                if (rx !in 0 until f.width || ry !in 0 until f.height) continue
                if (a != lit(f, rx, ry)) differ++
            }
            assertTrue("kaleidoscope drew nothing", litCount > 1000)
            assertTrue("kaleidoscope lit everything", litCount < f.width * f.height * 0.9)
            val frac = differ.toDouble() / (f.width * f.height)
            assertTrue("rotation differs on ${frac * 100}%", frac <= 0.02)
        }
    }

    /** L6: fire's column for a 1 kHz tone is hotter than the columns ±10% of the width away. */
    @Test
    fun fireBurnsHottestAboveTheTone() {
        Lab(Pattern.FIRE).use { lab ->
            val f = lab.captureAfter(LabSignals.sequence(Preset.TONE, 120))
            val col = DoubleArray(f.width) { x -> (f.height / 2 until f.height).sumOf { y -> luma(f.r(x, y), f.g(x, y), f.b(x, y)) } }
            val hot = col.indices.maxByOrNull { col[it] }!!
            val off = f.width / 10
            val left = col[(hot - off).coerceAtLeast(0)]
            val right = col[(hot + off).coerceAtMost(f.width - 1)]
            Log.i("VizInv", "fire hot=$hot ${col[hot]} left=$left right=$right")
            assertTrue("fire column $hot not hotter: ${col[hot]} vs $left / $right", col[hot] > 1.5 * left && col[hot] > 1.5 * right)
        }
    }

    /** L6: one kick drops a ripple whose ring moves outward from its centre across frames. */
    @Test
    fun aKickRippleMovesOutward() {
        Lab(Pattern.RIPPLES).use { lab ->
            val kick = LabSignals.sequence(Preset.KICK, 40, kickHz = 0.25)
            val a = lab.captureAfter(kick.take(12))
            repeat(10) { lab.stepAndCapture() }
            val b = lab.stepAndCapture()
            // Kick and snare drop rings at different points: follow the brighter (kick) ring from its centre.
            val (cx, cy) = brightCentre(a)
            val ra = edgeRadius(a, cx, cy)
            val rb = edgeRadius(b, cx, cy)
            Log.i("VizInv", "ripple radius $ra -> $rb")
            assertTrue("ripple did not grow: $ra -> $rb", rb > ra + 4)
        }
    }

    /** L6: phosphor in XY mode draws a left-only signal as a vertical line. */
    @Test
    fun phosphorDrawsLeftOnlyAsAVerticalLine() {
        val seq = LabSignals.sequence(Preset.TONE, 60).map { src ->
            Features().also { it.copyFrom(src); it.waveR.fill(0f); it.phrase = 0 }
        }
        Lab(Pattern.PHOSPHOR).use { lab ->
            val f = lab.captureAfter(seq)
            var xs = 0.0
            var ys = 0.0
            var n = 0
            var minX = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE
            var minY = Int.MAX_VALUE
            var maxY = Int.MIN_VALUE
            for (y in 0 until f.height) for (x in 0 until f.width) if (lit(f, x, y)) {
                xs += x
                ys += y
                n++
                minX = minOf(minX, x); maxX = maxOf(maxX, x); minY = minOf(minY, y); maxY = maxOf(maxY, y)
            }
            assertTrue("phosphor drew nothing", n > 200)
            val w = maxX - minX
            val h = maxY - minY
            Log.i("VizInv", "phosphor bbox ${w}x$h")
            assertTrue("not a vertical line: ${w}x$h", h > 4 * w)
        }
    }

    /** Centroid weighted by luminance^4, so the brightest ring decides; a ring's centroid is its centre. */
    private fun brightCentre(f: Frame): Pair<Double, Double> {
        var sx = 0.0
        var sy = 0.0
        var sw = 0.0
        for (y in 0 until f.height step 2) for (x in 0 until f.width step 2) {
            val l = luma(f.r(x, y), f.g(x, y), f.b(x, y)) / 255.0
            val w = l * l * l * l
            sx += w * x; sy += w * y; sw += w
        }
        return if (sw == 0.0) Pair(f.width / 2.0, f.height / 2.0) else Pair(sx / sw, sy / sw)
    }

    /** The radius of the brightest ring around (cx, cy), within [RING_MAX_R] so another drop can't win. */
    private fun edgeRadius(f: Frame, cx: Double, cy: Double): Double {
        val maxR = RING_MAX_R
        val sum = DoubleArray(maxR)
        val cnt = IntArray(maxR)
        for (y in 0 until f.height step 2) for (x in 0 until f.width step 2) {
            val r = hypot(x - cx, y - cy).toInt()
            if (r < maxR) { sum[r] += luma(f.r(x, y), f.g(x, y), f.b(x, y)); cnt[r]++ }
        }
        var best = 0
        var bestV = -1.0
        for (r in 20 until maxR) {
            if (cnt[r] == 0) continue
            val v = sum[r] / cnt[r]
            if (v > bestV) { bestV = v; best = r }
        }
        return best.toDouble()
    }

    /**
     * Every 8-bit colour within ±[TOL]/255 (per channel) of one family's palette surface: the composite
     * samples the LUT with linear filtering, so a lit pixel may sit anywhere between neighbouring entries
     * in position and level; an overlay edge over the background mixes an entry with the background by
     * coverage. Sampled at eighth steps, then dilated one axis at a time.
     */
    private class PaletteGrid(lut: FloatArray) {
        private val near = ByteArray(1 shl 24)
        var topLuma = 0.0
            private set

        init {
            val p = Palettes.POSITIONS
            fun c(pos: Int, level: Int, ch: Int) = lut[(level * p + pos % p) * 3 + ch]
            for (level in 0 until Palettes.LEVELS) for (pos in 0 until p) {
                topLuma = maxOf(topLuma, luma(round(c(pos, level, 0)), round(c(pos, level, 1)), round(c(pos, level, 2))))
                val nextLevel = minOf(level + 1, Palettes.LEVELS - 1)
                for (a in 0..STEPS) for (b in 0..STEPS) {
                    val u = a.toFloat() / STEPS
                    val v = b.toFloat() / STEPS
                    val rgb = IntArray(3) { ch ->
                        val lo = c(pos, level, ch) * (1 - u) + c(pos + 1, level, ch) * u
                        val hi = c(pos, nextLevel, ch) * (1 - u) + c(pos + 1, nextLevel, ch) * u
                        round(lo * (1 - v) + hi * v)
                    }
                    near[key(rgb[0], rgb[1], rgb[2])] = 1
                }
                for (a in 1 until STEPS) {
                    val t = a.toFloat() / STEPS
                    near[key(
                        round(c(pos, level, 0) * t + c(0, 0, 0) * (1 - t)),
                        round(c(pos, level, 1) * t + c(0, 0, 1) * (1 - t)),
                        round(c(pos, level, 2) * t + c(0, 0, 2) * (1 - t)),
                    )] = 1
                }
            }
            val line = ByteArray(256)
            for (axis in 0..2) {
                val stride = 1 shl (8 * axis)
                for (base in 0 until (1 shl 24)) {
                    if ((base shr (8 * axis)) and 0xFF != 0) continue
                    for (i in 0 until 256) line[i] = near[base + i * stride]
                    var count = 0
                    for (i in 0 until TOL) count += line[i]
                    for (i in 0 until 256) {
                        if (i + TOL < 256) count += line[i + TOL]
                        if (i - TOL - 1 >= 0) count -= line[i - TOL - 1]
                        near[base + i * stride] = if (count > 0) 1 else 0
                    }
                }
            }
        }

        fun contains(r: Int, g: Int, b: Int) = near[key(r, g, b)] != 0.toByte()

        private fun round(v: Float) = (v * 255f).roundToInt().coerceIn(0, 255)

        private fun key(r: Int, g: Int, b: Int) = (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
    }

    private companion object {
        const val LIT = 8
        const val RING_MAX_R = 300
        const val TOL = 6
        const val STEPS = 8

        fun luma(r: Int, g: Int, b: Int) = 0.2126 * r + 0.7152 * g + 0.0722 * b
    }
}
