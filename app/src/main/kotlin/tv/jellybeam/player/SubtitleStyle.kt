package tv.jellybeam.player

import uniffi.jellybeam_core.Settings
import uniffi.jellybeam_core.SubtitleColorPreset
import uniffi.jellybeam_core.SubtitlePositionPreset

/** docs/09 subtitle style, read once per session; defaults mirror `Settings::default()`. */
data class SubtitleStyle(
    val scale: Float = 1.0f,
    val position: SubtitlePositionPreset = SubtitlePositionPreset.DEFAULT,
    val bold: Boolean = false,
    val backgroundOpacity: Float = 0.0f,
    val color: SubtitleColorPreset = SubtitleColorPreset.WHITE,
    val useSystemStyle: Boolean = false,
)

fun Settings.subtitleStyle(): SubtitleStyle = SubtitleStyle(
    scale = subtitleScale,
    position = subtitlePosition,
    bold = subtitleBold,
    backgroundOpacity = subtitleBackgroundOpacity,
    color = subtitleColor,
    useSystemStyle = subtitleUseSystemStyle,
)

/** Opaque ARGB for [color]; shared by the player and the Settings swatches so they never drift. */
fun subtitleColorArgb(color: SubtitleColorPreset): Int = when (color) {
    SubtitleColorPreset.WHITE -> 0xFFFFFFFF
    SubtitleColorPreset.SOFT_WHITE -> 0xFFC0C0C0
    SubtitleColorPreset.YELLOW -> 0xFFFFFF00
    SubtitleColorPreset.LIGHT_GREEN -> 0xFF90EE90
}.toInt()

/** The preset caption colours; [outline] stands in for the edge whenever no background box is drawn. */
data class CaptionColors(val foreground: Int, val background: Int, val outline: Boolean)

/** `null` when [SubtitleStyle.useSystemStyle] hands colours, edge and typeface to Android's caption style. */
fun captionColors(style: SubtitleStyle): CaptionColors? {
    if (style.useSystemStyle) return null
    val alpha = (style.backgroundOpacity.coerceIn(0f, 1f) * 255).toInt()
    return CaptionColors(foreground = subtitleColorArgb(style.color), background = alpha shl 24, outline = alpha == 0)
}

/** The "Vertical position" ladder as SubtitleView bottom-padding fractions; Default pins Media3's own 0.08. */
fun subtitlePositionBottomFraction(position: SubtitlePositionPreset): Float = when (position) {
    SubtitlePositionPreset.DEFAULT -> 0.08f
    SubtitlePositionPreset.RAISED -> 0.16f
    SubtitlePositionPreset.HIGHER -> 0.24f
    SubtitlePositionPreset.HIGHEST -> 0.32f
}
