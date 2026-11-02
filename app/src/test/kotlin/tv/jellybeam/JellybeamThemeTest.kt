package tv.jellybeam

import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins JellybeamTheme's palette tokens to their expected hex values. */
class JellybeamThemeTest {

    @Test
    fun `palette tokens match the design spec`() {
        assertEquals(Color(0xFF14100D), JellybeamTheme.Notte)
        assertEquals(Color(0xFF1D1814), JellybeamTheme.Surface)
        assertEquals(Color(0xFF322A22), JellybeamTheme.Hairline)
        assertEquals(Color(0xFFF7E9CE), JellybeamTheme.Panna)
        assertEquals(Color(0xFFC9C0B2), JellybeamTheme.Panna2)
        assertEquals(Color(0xFF8C8478), JellybeamTheme.Grigio)
        assertEquals(Color(0xFFA8CB6B), JellybeamTheme.Pistacchio)
        assertEquals(Color(0xFFC6DE9B), JellybeamTheme.Sheen)
        assertEquals(Color(0xFFE0B45C), JellybeamTheme.Ambra)
        assertEquals(Color(0xFF261F19), JellybeamTheme.SurfacePanel)
        assertEquals(JellybeamTheme.Surface, JellybeamTheme.SurfaceRaised)
        assertEquals(JellybeamTheme.Panna.copy(alpha = 0.45f), JellybeamTheme.PannaTertiary)
    }

    @Test
    fun `every flavour has its own mascot`() {
        assertEquals(AccentSchemes.size, AccentSchemes.map { it.mascot }.toSet().size)
    }

    /** A decoded mascot costs width x height x 4 on every screen showing it, whatever the file format. */
    @Test
    fun `mascot assets are one WebP per flavour within the memory cap`() {
        for (scheme in AccentSchemes) {
            val bytes = java.io.File("src/main/res/drawable-nodpi/jb_mascot_base_${scheme.key}.webp").readBytes()
            assertEquals("VP8X", String(bytes, 12, 4))
            fun u24(at: Int) = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8) or ((bytes[at + 2].toInt() and 0xFF) shl 16)
            val width = u24(24) + 1
            val height = u24(27) + 1
            assertTrue("${scheme.key} is ${width}x$height", width <= 240 && height <= 240)
        }
    }

    @Test
    fun `accent table has twelve unique keys in picker order`() {
        assertEquals(
            listOf(
                "pistacchio", "menta", "puffo", "mirtillo", "lavanda", "fragola",
                "bosco", "pino", "oceano", "mirtillo_scuro", "prugna", "amarena",
            ),
            AccentSchemes.map { it.key },
        )
        assertEquals(12, AccentSchemes.map { it.name }.toSet().size)
        assertEquals("Mirtillo scuro", accentSchemeFor("mirtillo_scuro").name)
    }

    @Test
    fun `an unknown null or empty accent key resolves to pistacchio`() {
        val default = AccentSchemes.first()
        assertEquals("pistacchio", default.key)
        assertSame(default, accentSchemeFor(null))
        assertSame(default, accentSchemeFor(""))
        assertSame(default, accentSchemeFor("not-a-flavour"))
        assertSame(AccentSchemes[7], accentSchemeFor("pino"))
    }

    @Test
    fun `accent ink is the sheen for deep flavours and the accent for light ones`() {
        AccentSchemes.forEach {
            assertEquals(it.key, if (it.deep) it.accentSheen else it.accent, it.accentInk)
            assertEquals(it.key, it.accentInk.copy(alpha = 0.3f), it.progressTrack)
            assertEquals(it.key, if (it.deep) JellybeamTheme.Panna else JellybeamTheme.Notte, it.onAccent)
        }
        assertEquals(6, AccentSchemes.count { it.deep })
    }

    @Test
    fun `every flavour keeps readable contrast for its fills and ink`() {
        AccentSchemes.forEach {
            assertTrue("${it.key} onAccent on accent", contrast(it.onAccent, it.accent) >= 4.5)
            assertTrue("${it.key} onAccentMuted on accent", contrast(it.onAccentMuted, it.accent) >= 4.5)
            assertTrue("${it.key} onAccent on empty tank", contrast(it.onAccent, it.accentTankEmpty) >= 4.5)
            assertTrue("${it.key} ink on Notte", contrast(it.accentInk, JellybeamTheme.Notte) >= 4.5)
            assertTrue("${it.key} ink on LobbyGround", contrast(it.accentInk, JellybeamTheme.LobbyGround) >= 4.5)
        }
    }

    @Test
    fun `pistacchio derived values keep today's exact pixels`() {
        val p = accentSchemeFor("pistacchio")
        assertEquals(JellybeamTheme.Pistacchio, p.accent)
        assertEquals(JellybeamTheme.Pistacchio, p.accentInk)
        assertEquals(JellybeamTheme.Sheen, p.accentSheen)
        assertEquals(JellybeamTheme.Notte, p.onAccent)
        assertEquals(Color(0xFFE4EDD0), p.accentTankEmpty)
        assertEquals(Color(0xFF2E3A18), p.onAccentMuted)
        assertEquals(JellybeamTheme.Pistacchio.copy(alpha = 0.3f), p.progressTrack)
    }

    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun luminance(c: Color): Double {
        fun lin(v: Float) = v.toDouble().let { if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
    }
}
