package tv.jellybeam.visualizer.color

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/30 §4: the colour space the palette families are built in. */
class OklchTest {
    private val lch = DoubleArray(3)
    private val rgb = DoubleArray(3)

    @Test
    fun `srgb to oklch to srgb round trips within 1e-4`() {
        val steps = floatArrayOf(0f, 0.02f, 0.078f, 0.25f, 0.5f, 0.73f, 1f)
        for (r in steps) for (g in steps) for (b in steps) {
            Oklch.fromSrgb(r, g, b, lch)
            Oklch.toSrgb(lch[0], lch[1], lch[2], rgb)
            assertEquals("r of $r $g $b", r.toDouble(), rgb[0], 1e-4)
            assertEquals("g of $r $g $b", g.toDouble(), rgb[1], 1e-4)
            assertEquals("b of $r $g $b", b.toDouble(), rgb[2], 1e-4)
        }
    }

    @Test
    fun `known colours land where Ottosson puts them`() {
        Oklch.fromSrgb(1f, 1f, 1f, lch)
        assertEquals(1.0, lch[0], 1e-3)
        assertEquals(0.0, lch[1], 1e-3)
        Oklch.fromSrgb(0f, 0f, 0f, lch)
        assertEquals(0.0, lch[0], 1e-6)
        Oklch.fromSrgb(1f, 0f, 0f, lch)
        assertEquals(0.628, lch[0], 2e-3)
        assertEquals(0.2577, lch[1], 2e-3)
        assertEquals(29.23, lch[2], 0.2)
    }

    @Test
    fun `gamut mapping keeps L and hue and lands every result inside the cube`() {
        var h = 0.0
        while (h < 360.0) {
            var l = 0.05
            while (l < 0.99) {
                for (c in doubleArrayOf(0.02, 0.1, 0.2, 0.37)) {
                    val mapped = Oklch.mapToGamut(l, c, h, rgb)
                    assertTrue("chroma only shrinks", mapped <= c + 1e-12)
                    Oklch.toSrgb(l, mapped, h, rgb)
                    for (ch in 0 until 3) assertTrue("L=$l C=$c h=$h ch$ch=${rgb[ch]}", rgb[ch] >= -1e-4 && rgb[ch] <= 1 + 1e-4)
                    if (mapped > 0.01) {
                        val out = floatArrayOf(rgb[0].toFloat().coerceIn(0f, 1f), rgb[1].toFloat().coerceIn(0f, 1f), rgb[2].toFloat().coerceIn(0f, 1f))
                        Oklch.fromSrgb(out[0], out[1], out[2], lch)
                        assertEquals(l, lch[0], 2e-3)
                        assertEquals(mapped * cos(Math.toRadians(h)), lch[1] * cos(Math.toRadians(lch[2])), 2e-3)
                        assertEquals(mapped * sin(Math.toRadians(h)), lch[1] * sin(Math.toRadians(lch[2])), 2e-3)
                    }
                }
                l += 0.07
            }
            h += 23.0
        }
    }

    @Test
    fun `an in gamut colour is returned untouched and an impossible one collapses to grey`() {
        assertEquals(0.05, Oklch.mapToGamut(0.6, 0.05, 140.0, rgb), 1e-9)
        assertEquals(0.0, Oklch.mapToGamut(1.0, 0.2, 140.0, rgb), 1e-9)
        assertEquals(1.0, rgb[0], 1e-6)
        assertEquals(0.0, Oklch.mapToGamut(0.0, 0.2, 140.0, rgb), 1e-9)
        assertEquals(0.0, rgb[1], 1e-6)
    }

    @Test
    fun `relative luminance and contrast follow WCAG`() {
        assertEquals(1f, Oklch.relativeLuminance(1f, 1f, 1f), 1e-6f)
        assertEquals(0f, Oklch.relativeLuminance(0f, 0f, 0f), 1e-6f)
        assertEquals(0.2126f, Oklch.relativeLuminance(1f, 0f, 0f), 1e-4f)
        assertEquals(21f, Oklch.contrast(1f, 0f), 1e-3f)
        assertTrue(abs(Oklch.contrast(0.3f, 0.1f) - Oklch.contrast(0.1f, 0.3f)) < 1e-6f)
    }
}
