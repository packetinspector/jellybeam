package tv.jellybeam.ui.music

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tv.jellybeam.JellybeamTheme
import tv.jellybeam.LocalAccent
import tv.jellybeam.ui.cards.focusRing

/**
 * docs/29 §8.5: a round transport control. Off has no fill and a PANNA B3 glyph; [on] (shuffle, repeat)
 * is a SurfacePanel fill with a hairline and a PANNA glyph, never accent, so it can't read as focus;
 * [primary] (Play/Pause) is always accent-filled; focused is accent with the sheen ring. [loading] swaps
 * the glyph for [LoadingDots] at the same footprint and makes the button inert, still focusable.
 * [badge] is repeat-one's "1".
 */
@Composable
internal fun TransportButton(
    glyph: MusicGlyph,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    glyphSize: Dp = 18.dp,
    primary: Boolean = false,
    on: Boolean = false,
    loading: Boolean = false,
    badge: String? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val accent = LocalAccent.current
    val filled = focused || primary
    val tint = when {
        filled -> accent.onAccent
        on -> JellybeamTheme.Panna
        else -> PannaB3
    }
    val base = modifier
        .size(size)
        .focusRing(focused, cornerRadius = size / 2, color = accent.accentSheen)
    val fill = when {
        filled -> base.background(accent.accent, CircleShape)
        on -> base.background(JellybeamTheme.SurfacePanel, CircleShape).border(Dp.Hairline, JellybeamTheme.Hairline, CircleShape)
        else -> base
    }
    Box(
        modifier = fill.clickable(interactionSource = interaction, indication = null) { if (!loading) onClick() },
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            LoadingDots(color = tint)
        } else {
            Canvas(Modifier.size(glyphSize)) { glyph(tint) }
        }
        if (badge != null) {
            BasicText(
                text = badge,
                modifier = Modifier.align(Alignment.TopEnd).offset(x = (-7).dp, y = 4.dp),
                style = monoStyle(tint, 8, FontWeight.Bold),
            )
        }
    }
}
