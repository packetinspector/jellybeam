package tv.jellybeam.ui.music

import android.view.KeyEvent
import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.jellybeam.AccentSchemes
import tv.jellybeam.data.defaultTestSettings
import tv.jellybeam.visualizer.color.PaletteFamily
import tv.jellybeam.visualizer.color.Palettes
import tv.jellybeam.visualizer.pattern.Pattern
import tv.jellybeam.visualizer.pattern.PatternCycle

/** docs/30 §9 T15, T29, T30: the ramp per accent, the stored keys and the device gates. */
class VisualizerLogicTest {

    private fun linear(c: Float): Double = if (c <= 0.04045f) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)

    private fun luminance(r: Float, g: Float, b: Float) = 0.2126 * linear(r) + 0.7152 * linear(g) + 0.0722 * linear(b)

    /** docs/30 §9 U5 against the real accents: five families each, true black at level 0, luminance rising with level. */
    @Test
    fun everyAccentGetsFiveFamiliesFromBlackUpward() {
        assertEquals(12, AccentSchemes.size)
        for (scheme in AccentSchemes) {
            val families = visualizerPalettes(scheme)
            assertEquals(PaletteFamily.entries.size, families.size)
            for ((f, lut) in families.withIndex()) {
                assertEquals(Palettes.POSITIONS * Palettes.LEVELS * 3, lut.size)
                for (pos in 0 until Palettes.POSITIONS step 8) {
                    for (i in 0 until 3) assertEquals("${scheme.key}/$f", 0f, lut[pos * 3 + i], 0f)
                    var last = -1.0
                    for (level in 0 until Palettes.LEVELS) {
                        val k = (level * Palettes.POSITIONS + pos) * 3
                        val y = luminance(lut[k], lut[k + 1], lut[k + 2])
                        assertTrue("${scheme.key}/$f pos $pos level $level", y > last)
                        last = y
                    }
                }
            }
        }
    }

    @Test
    fun settingsDefaultToCoverSwirlAndAutoChange() {
        val defaults = defaultTestSettings()
        assertEquals(AmbientStyle.COVER, ambientStyleFor(defaults.ambientStyle))
        assertEquals(Pattern.SWIRL, PatternCycle.fromKey(defaults.visualizerPattern))
        assertTrue(defaults.visualizerAutoChange)
    }

    @Test
    fun storedKeysRoundTripAndUnknownKeysFallBack() {
        for (s in AmbientStyle.entries) assertEquals(s, ambientStyleFor(s.key))
        assertEquals(AmbientStyle.COVER, ambientStyleFor("aurora"))
        assertEquals(AmbientStyle.COVER, ambientStyleFor(null))
        for (p in Pattern.entries) assertEquals(p, PatternCycle.fromKey(p.key))
        assertEquals(Pattern.BARS, PatternCycle.fromKey("aurora"))
    }

    @Test
    fun glesGateAndAmbientDecision() {
        assertFalse(visualizerSupported(0x20000))
        assertTrue(visualizerSupported(0x30000))
        assertTrue(visualizerSupported(0x30002))
        assertTrue(ambientDrawsVisualizer(AmbientStyle.VISUALIZER, supported = true))
        assertFalse(ambientDrawsVisualizer(AmbientStyle.VISUALIZER, supported = false))
        assertFalse(ambientDrawsVisualizer(AmbientStyle.COVER, supported = true))
    }

    @Test
    fun keysFollowD9AndMediaKeysPassThrough() {
        assertEquals(VisualizerKey.PREVIOUS, visualizerKey(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(VisualizerKey.NEXT, visualizerKey(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(VisualizerKey.PLAY_PAUSE, visualizerKey(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(VisualizerKey.CLOSE, visualizerKey(KeyEvent.KEYCODE_ESCAPE))
        // Back reaches the overlay's BackHandler through the dispatcher, never the key path.
        assertEquals(VisualizerKey.PASS, visualizerKey(KeyEvent.KEYCODE_BACK))
        assertEquals(VisualizerKey.NEXT_TRACK, visualizerKey(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(VisualizerKey.PREVIOUS_TRACK, visualizerKey(KeyEvent.KEYCODE_DPAD_DOWN))
        // Page and channel keys belong to the music layer's one track-key owner.
        for (k in intArrayOf(
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN,
        )) {
            assertEquals(VisualizerKey.PASS, visualizerKey(k))
        }
    }

    @Test
    fun everyPatternHasAName() {
        assertEquals(Pattern.entries.size, Pattern.entries.map(::patternName).toSet().size)
    }

    @Test
    fun pageAndChannelKeysChangeTrackOncePerPress() {
        assertEquals(TrackKey.NEXT, musicTrackKey(Key.PageUp, down = true, repeatCount = 0, hasSession = true))
        assertEquals(TrackKey.NEXT, musicTrackKey(Key.ChannelUp, down = true, repeatCount = 0, hasSession = true))
        assertEquals(TrackKey.PREVIOUS, musicTrackKey(Key.PageDown, down = true, repeatCount = 0, hasSession = true))
        assertEquals(TrackKey.PREVIOUS, musicTrackKey(Key.ChannelDown, down = true, repeatCount = 0, hasSession = true))
        assertEquals("held", TrackKey.CONSUME, musicTrackKey(Key.PageUp, down = true, repeatCount = 3, hasSession = true))
        assertEquals("release", TrackKey.CONSUME, musicTrackKey(Key.ChannelDown, down = false, repeatCount = 0, hasSession = true))
        assertEquals(TrackKey.PASS, musicTrackKey(Key.DirectionUp, down = true, repeatCount = 0, hasSession = true))
        assertEquals(TrackKey.PASS, musicTrackKey(Key.MediaNext, down = true, repeatCount = 0, hasSession = true))
    }

    @Test
    fun pageKeysPassWithoutASession() {
        assertEquals(TrackKey.PASS, musicTrackKey(Key.PageDown, down = true, repeatCount = 0, hasSession = false))
        assertEquals(TrackKey.PASS, musicTrackKey(Key.ChannelUp, down = true, repeatCount = 3, hasSession = false))
        assertEquals(TrackKey.PASS, musicTrackKey(Key.PageUp, down = false, repeatCount = 0, hasSession = false))
    }
}
