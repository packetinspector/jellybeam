package tv.jellybeam.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.jellybeam.data.defaultTestSettings
import uniffi.jellybeam_core.SubtitleColorPreset
import uniffi.jellybeam_core.SubtitlePositionPreset

class SubtitleStyleTest {

    @Test
    fun `default settings map to the default style`() {
        assertEquals(SubtitleStyle(), defaultTestSettings().subtitleStyle())
    }

    @Test
    fun `default style draws opaque white outlined text with no box`() {
        assertEquals(CaptionColors(foreground = 0xFFFFFFFF.toInt(), background = 0, outline = true), captionColors(SubtitleStyle()))
    }

    @Test
    fun `a background box replaces the outline and carries the opacity as alpha`() {
        val colors = captionColors(SubtitleStyle(backgroundOpacity = 0.5f))!!
        assertEquals(0x7F000000, colors.background)
        assertEquals(false, colors.outline)
    }

    @Test
    fun `colour preset drives the foreground`() {
        val colors = captionColors(SubtitleStyle(color = SubtitleColorPreset.LIGHT_GREEN))!!
        assertEquals(subtitleColorArgb(SubtitleColorPreset.LIGHT_GREEN), colors.foreground)
    }

    @Test
    fun `every colour preset is fully opaque and distinct`() {
        val argbs = SubtitleColorPreset.entries.map(::subtitleColorArgb)
        argbs.forEach { assertEquals(0xFF, it ushr 24) }
        assertEquals(argbs.size, argbs.toSet().size)
    }

    @Test
    fun `system style defers colours to Android`() {
        assertNull(captionColors(SubtitleStyle(useSystemStyle = true, color = SubtitleColorPreset.YELLOW)))
    }

    @Test
    fun `position ladder rises monotonically from Media3's default`() {
        val fractions = SubtitlePositionPreset.entries.map(::subtitlePositionBottomFraction)
        assertEquals(0.08f, fractions.first())
        assertEquals(fractions.sorted(), fractions)
    }
}
