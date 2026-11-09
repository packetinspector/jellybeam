package tv.jellybeam.visualizer.color

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/30 §4, plan U5: the five palette families over the twelve accents. */
class PalettesTest {
    private class Accent(val key: String, val accent: Int, val sheen: Int)

    // Copied from AccentSchemes in app/src/main/kotlin/tv/jellybeam/JellybeamTheme.kt (accent, sheen).
    private val accents = listOf(
        Accent("pistacchio", 0xFFA8CB6B.toInt(), 0xFFC6DE9B.toInt()),
        Accent("menta", 0xFF7FCFAE.toInt(), 0xFFAEE3CB.toInt()),
        Accent("puffo", 0xFF86C3E3.toInt(), 0xFFB5DBEF.toInt()),
        Accent("mirtillo", 0xFFA6B4EE.toInt(), 0xFFC9D1F6.toInt()),
        Accent("lavanda", 0xFFC6A8E6.toInt(), 0xFFDDCAF1.toInt()),
        Accent("fragola", 0xFFEEA0B4.toInt(), 0xFFF5C5D1.toInt()),
        Accent("bosco", 0xFF4E6A2C.toInt(), 0xFFA8CB6B.toInt()),
        Accent("pino", 0xFF2C6B58.toInt(), 0xFF7FCFAE.toInt()),
        Accent("oceano", 0xFF2B5F82.toInt(), 0xFF86C3E3.toInt()),
        Accent("mirtillo_scuro", 0xFF4C4F94.toInt(), 0xFFA6B4EE.toInt()),
        Accent("prugna", 0xFF6E3F7C.toInt(), 0xFFC6A8E6.toInt()),
        Accent("amarena", 0xFF8C2F48.toInt(), 0xFFEEA0B4.toInt()),
    )
    private val black = 0xFF000000.toInt()
    private val families = PaletteFamily.entries

    private val luts = HashMap<String, FloatArray>()

    private fun lut(a: Accent, f: PaletteFamily): FloatArray =
        luts.getOrPut("${a.key}/${f.key}") { Palettes.build(f, a.accent, a.sheen) }

    private fun at(lut: FloatArray, level: Int, pos: Int, ch: Int) = lut[(level * Palettes.POSITIONS + pos) * 3 + ch]

    private fun lum(lut: FloatArray, level: Int, pos: Int) =
        Oklch.relativeLuminance(at(lut, level, pos, 0), at(lut, level, pos, 1), at(lut, level, pos, 2))

    private fun channel(argb: Int, ch: Int) = ((argb shr (16 - 8 * ch)) and 0xFF) / 255f

    private inline fun forEachCase(body: (Accent, PaletteFamily, FloatArray) -> Unit) {
        for (a in accents) for (f in families) body(a, f, lut(a, f))
    }

    @Test
    fun `lut has the contract size and finite values`() {
        forEachCase { a, f, lut ->
            assertEquals("${a.key} ${f.key}", Palettes.POSITIONS * Palettes.LEVELS * 3, lut.size)
            assertTrue(lut.all { it.isFinite() })
        }
        assertEquals(128, Palettes.POSITIONS)
        assertEquals(32, Palettes.LEVELS)
    }

    @Test
    fun `level 0 is true black at every position`() {
        forEachCase { a, f, lut ->
            for (pos in 0 until Palettes.POSITIONS) for (ch in 0 until 3) {
                assertEquals("${a.key} ${f.key} pos$pos ch$ch", 0f, at(lut, 0, pos, ch), 0f)
            }
        }
    }

    @Test
    fun `luminance rises strictly with level at every position`() {
        forEachCase { a, f, lut ->
            for (pos in 0 until Palettes.POSITIONS) for (level in 1 until Palettes.LEVELS) {
                assertTrue(
                    "${a.key} ${f.key} pos$pos level$level: ${lum(lut, level, pos)} vs ${lum(lut, level - 1, pos)}",
                    lum(lut, level, pos) > lum(lut, level - 1, pos),
                )
            }
        }
    }

    @Test
    fun `quantised rgba8 luminance also never falls with level`() {
        for (a in accents) {
            val all = families.map { lut(a, it) }
            val bytes = ByteArray(Palettes.POSITIONS * Palettes.LEVELS * families.size * 4)
            Palettes.toRgba8(all, bytes)
            for (fi in families.indices) for (pos in 0 until Palettes.POSITIONS) {
                var prev = -1f
                for (level in 0 until Palettes.LEVELS) {
                    val i = (((fi * Palettes.LEVELS + level) * Palettes.POSITIONS) + pos) * 4
                    val y = Oklch.relativeLuminance((bytes[i].toInt() and 0xFF) / 255f, (bytes[i + 1].toInt() and 0xFF) / 255f, (bytes[i + 2].toInt() and 0xFF) / 255f)
                    assertTrue("${a.key} ${families[fi].key} pos$pos level$level", y >= prev)
                    prev = y
                }
            }
        }
    }

    @Test
    fun `the top level holds 7 to 1 against black at every position`() {
        val yBlack = Oklch.relativeLuminance(channel(black, 0), channel(black, 1), channel(black, 2))
        forEachCase { a, f, lut ->
            for (pos in 0 until Palettes.POSITIONS) {
                val c = Oklch.contrast(lum(lut, Palettes.LEVELS - 1, pos), yBlack)
                assertTrue("${a.key} ${f.key} pos$pos contrast $c", c >= 7f)
            }
        }
    }

    @Test
    fun `every family shows three hues at least 25 degrees apart`() {
        forEachCase { a, f, lut ->
            val sep = bestTripleSeparation(lut)
            assertTrue("${a.key} ${f.key} best triple separation $sep", sep >= 25.0)
        }
    }

    @Test
    fun `positions form a closed loop and neighbours differ smoothly`() {
        forEachCase { a, f, lut ->
            for (level in 0 until Palettes.LEVELS) for (pos in 0 until Palettes.POSITIONS) {
                val next = (pos + 1) % Palettes.POSITIONS
                val de = deltaE(lut, level, pos, level, next)
                assertTrue("${a.key} ${f.key} level$level pos$pos to $next dE=$de", de <= 0.02)
            }
        }
    }

    @Test
    fun `entries are gamut mapped at constant L and hue, never hard clipped`() {
        val lch = DoubleArray(3)
        val want = DoubleArray(3)
        forEachCase { a, f, lut ->
            val anchors = Anchors(a.accent, a.sheen)
            for (level in 1 until Palettes.LEVELS) for (pos in 0 until Palettes.POSITIONS step 3) {
                Palettes.target(f, level, pos, anchors, want)
                Oklch.fromSrgb(at(lut, level, pos, 0), at(lut, level, pos, 1), at(lut, level, pos, 2), lch)
                val tag = "${a.key} ${f.key} level$level pos$pos"
                assertEquals("$tag L", want[0], lch[0], 2e-3)
                assertTrue("$tag C ${lch[1]} vs ${want[1]}", lch[1] <= want[1] + 1e-3)
                if (lch[1] >= 0.03) assertTrue("$tag hue", hueDist(want[2], lch[2]) <= 0.5)
            }
        }
    }

    @Test
    fun `deep accents anchor their hue on the sheen and light ones on the accent`() {
        val want = DoubleArray(3)
        val lch = DoubleArray(3)
        for (a in accents) {
            val deep = a.key in setOf("bosco", "pino", "oceano", "mirtillo_scuro", "prugna", "amarena")
            val source = if (deep) a.sheen else a.accent
            Oklch.fromSrgb(channel(source, 0), channel(source, 1), channel(source, 2), lch)
            // Position one third round the loop sits exactly on the family's middle hue (ANALOGOUS: the anchor).
            Palettes.target(PaletteFamily.ANALOGOUS, 15, Palettes.POSITIONS / 3, Anchors(a.accent, a.sheen), want)
            assertTrue("${a.key} hue ${want[2]} vs ${lch[2]}", hueDist(want[2], lch[2]) <= 0.5)
        }
    }

    @Test
    fun `fire and ice end on their stated top colours`() {
        val want = DoubleArray(3)
        val sheen = DoubleArray(3)
        for (a in accents) {
            val anchors = Anchors(a.accent, a.sheen)
            Oklch.fromSrgb(channel(a.sheen, 0), channel(a.sheen, 1), channel(a.sheen, 2), sheen)
            Palettes.target(PaletteFamily.ICE, Palettes.LEVELS - 1, 0, anchors, want)
            assertTrue("${a.key} ice top hue", hueDist(want[2], sheen[2]) <= 12.5)
            Palettes.target(PaletteFamily.FIRE, Palettes.LEVELS - 1, 0, anchors, want)
            assertTrue("${a.key} fire top L ${want[0]}", want[0] >= 0.93)
            assertTrue("${a.key} fire top hue ${want[2]}", hueDist(want[2], 78.0) <= 13.0)
            assertTrue("${a.key} fire top C ${want[1]}", want[1] <= 0.06)
        }
    }

    @Test
    fun `toRgba8 lays the five families out as stacked rows of opaque texels`() {
        val all = families.map { lut(accents[1], it) }
        val bytes = ByteArray(Palettes.POSITIONS * Palettes.LEVELS * 5 * 4)
        Palettes.toRgba8(all, bytes)
        for (fi in 0 until 5) for (level in 0 until Palettes.LEVELS) for (pos in 0 until Palettes.POSITIONS) {
            val i = (((fi * Palettes.LEVELS + level) * Palettes.POSITIONS) + pos) * 4
            for (ch in 0 until 3) {
                val want = (at(all[fi], level, pos, ch) * 255f + 0.5f).toInt().coerceIn(0, 255)
                assertEquals("f$fi level$level pos$pos ch$ch", want, bytes[i + ch].toInt() and 0xFF)
            }
            assertEquals(0xFF, bytes[i + 3].toInt() and 0xFF)
        }
        // Row 0 of every family is black, so the engine's level-0 sample is the clear colour.
        for (fi in 0 until 5) {
            val i = fi * Palettes.LEVELS * Palettes.POSITIONS * 4
            for (ch in 0 until 3) assertEquals(0, bytes[i + ch].toInt() and 0xFF)
        }
    }

    @Test
    fun `stepIsRed sees a step into or out of saturated red and nothing else`() {
        val grey = FloatArray(Palettes.POSITIONS * Palettes.LEVELS * 3) { 0.4f }
        assertFalse(Palettes.stepIsRed(grey, 0f, 0.25f))
        val idx = (10 * Palettes.POSITIONS + 32) * 3
        grey[idx] = 0.9f; grey[idx + 1] = 0.05f; grey[idx + 2] = 0.05f
        // Every pixel has its own position, so a red entry anywhere makes any non-zero step a red step.
        for (offset in floatArrayOf(0f, 0.25f, 0.5f, 0.75f)) assertTrue("offset $offset", Palettes.stepIsRed(grey, offset, 0.25f))
        assertFalse("a whole turn changes nothing", Palettes.stepIsRed(grey, 0f, 1f))
    }

    @Test
    fun `summary table of top contrast and hue separation per family`() {
        val yBlack = Oklch.relativeLuminance(channel(black, 0), channel(black, 1), channel(black, 2))
        val sb = StringBuilder("family | min top contrast | worst accent | min hue separation | worst accent\n")
        for (f in families) {
            var minC = Float.MAX_VALUE; var minCa = ""
            var minS = Double.MAX_VALUE; var minSa = ""
            for (a in accents) {
                val lut = lut(a, f)
                for (pos in 0 until Palettes.POSITIONS) {
                    val c = Oklch.contrast(lum(lut, Palettes.LEVELS - 1, pos), yBlack)
                    if (c < minC) { minC = c; minCa = a.key }
                }
                val s = bestTripleSeparation(lut)
                if (s < minS) { minS = s; minSa = a.key }
            }
            sb.append("${f.key} | ${"%.2f".format(minC)} | $minCa | ${"%.0f".format(minS)} | $minSa\n")
        }
        println(sb)
    }

    /** Largest min-pairwise hue gap over triples of 5-degree hue bins among entries with C >= 0.03. */
    private fun bestTripleSeparation(lut: FloatArray): Double {
        val lch = DoubleArray(3)
        val bins = BooleanArray(72)
        for (level in 1 until Palettes.LEVELS) for (pos in 0 until Palettes.POSITIONS) {
            Oklch.fromSrgb(at(lut, level, pos, 0), at(lut, level, pos, 1), at(lut, level, pos, 2), lch)
            if (lch[1] >= 0.03) bins[(lch[2] / 5.0).toInt() % 72] = true
        }
        var best = 0.0
        for (i in 0 until 72) if (bins[i]) for (j in i + 1 until 72) if (bins[j]) for (k in j + 1 until 72) if (bins[k]) {
            best = maxOf(best, min(hueDist(i * 5.0, j * 5.0), min(hueDist(j * 5.0, k * 5.0), hueDist(i * 5.0, k * 5.0))))
        }
        return best
    }

    private fun hueDist(a: Double, b: Double): Double {
        val d = abs(a - b) % 360.0
        return if (d > 180.0) 360.0 - d else d
    }

    private fun deltaE(lut: FloatArray, l1: Int, p1: Int, l2: Int, p2: Int): Double {
        val x = DoubleArray(3)
        val y = DoubleArray(3)
        Oklch.fromSrgb(at(lut, l1, p1, 0), at(lut, l1, p1, 1), at(lut, l1, p1, 2), x)
        Oklch.fromSrgb(at(lut, l2, p2, 0), at(lut, l2, p2, 1), at(lut, l2, p2, 2), y)
        val a1 = x[1] * cos(Math.toRadians(x[2])); val b1 = x[1] * sin(Math.toRadians(x[2]))
        val a2 = y[1] * cos(Math.toRadians(y[2])); val b2 = y[1] * sin(Math.toRadians(y[2]))
        return sqrt((x[0] - y[0]) * (x[0] - y[0]) + (a1 - a2) * (a1 - a2) + (b1 - b2) * (b1 - b2))
    }
}
