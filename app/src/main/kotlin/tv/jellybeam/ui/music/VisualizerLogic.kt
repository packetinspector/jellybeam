package tv.jellybeam.ui.music

import android.view.KeyEvent
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.toArgb
import tv.jellybeam.AccentScheme
import tv.jellybeam.R
import tv.jellybeam.visualizer.color.PaletteFamily
import tv.jellybeam.visualizer.color.Palettes
import tv.jellybeam.visualizer.pattern.Pattern

/** docs/30 §8: what the ambient screen draws; stored as [key], an unknown key is [COVER]. */
internal enum class AmbientStyle(val key: String) {
    COVER("cover"),
    VISUALIZER("visualizer"),
}

internal fun ambientStyleFor(key: String?): AmbientStyle = AmbientStyle.entries.firstOrNull { it.key == key } ?: AmbientStyle.COVER

/** docs/30 §7 (D10): GLES 3.0 (`ConfigurationInfo.reqGlEsVersion`) or no Visualizer button and no ambient option. */
internal fun visualizerSupported(reqGlEsVersion: Int): Boolean = reqGlEsVersion >= GLES_3_0

/** docs/30 §7: ambient draws the visualizer only when chosen and the device can draw it; otherwise the cover art. */
internal fun ambientDrawsVisualizer(style: AmbientStyle, supported: Boolean): Boolean = supported && style == AmbientStyle.VISUALIZER

/** docs/30 §7: what a key does on the full-screen visualizer. */
internal enum class VisualizerKey { PREVIOUS, NEXT, NEXT_TRACK, PREVIOUS_TRACK, PLAY_PAUSE, CLOSE, PASS }

/**
 * docs/30 §7 (D9): Left/Right switch patterns, Up/Down go to the next/previous track, Centre plays or pauses,
 * Escape closes (Back goes through the dispatcher); media and page keys pass through to the music layer.
 */
internal fun visualizerKey(keyCode: Int): VisualizerKey = when (keyCode) {
    KeyEvent.KEYCODE_DPAD_LEFT -> VisualizerKey.PREVIOUS
    KeyEvent.KEYCODE_DPAD_RIGHT -> VisualizerKey.NEXT
    KeyEvent.KEYCODE_DPAD_UP -> VisualizerKey.NEXT_TRACK
    KeyEvent.KEYCODE_DPAD_DOWN -> VisualizerKey.PREVIOUS_TRACK
    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> VisualizerKey.PLAY_PAUSE
    KeyEvent.KEYCODE_ESCAPE -> VisualizerKey.CLOSE
    else -> VisualizerKey.PASS
}

/** docs/30 §4 (E3): the five palette families for an accent, in [PaletteFamily] order. */
internal fun visualizerPalettes(scheme: AccentScheme): List<FloatArray> {
    return PaletteFamily.entries.map { Palettes.build(it, scheme.accent.toArgb(), scheme.accentSheen.toArgb()) }
}

@StringRes
internal fun patternName(p: Pattern): Int = when (p) {
    Pattern.BARS -> R.string.visualizer_pattern_bars
    Pattern.RADIAL -> R.string.visualizer_pattern_radial
    Pattern.WATERFALL -> R.string.visualizer_pattern_waterfall
    Pattern.KALEIDO -> R.string.visualizer_pattern_kaleido
    Pattern.SWIRL -> R.string.visualizer_pattern_swirl
    Pattern.FIRE -> R.string.visualizer_pattern_fire
    Pattern.PHOSPHOR -> R.string.visualizer_pattern_phosphor
    Pattern.SUPERSCOPE -> R.string.visualizer_pattern_superscope
    Pattern.RIDGELINE -> R.string.visualizer_pattern_ridgeline
    Pattern.RIPPLES -> R.string.visualizer_pattern_ripples
}

private const val GLES_3_0 = 0x30000
