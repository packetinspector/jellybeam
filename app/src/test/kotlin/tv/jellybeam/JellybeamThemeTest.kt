package tv.jellybeam

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
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
}
