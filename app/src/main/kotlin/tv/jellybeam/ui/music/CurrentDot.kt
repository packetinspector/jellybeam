package tv.jellybeam.ui.music

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/** docs/29 §8's focus curve, `cubic-bezier(0.2,0,0,1)`. */
internal val JellybeamEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

private const val PULSE_HALF_MS = 800
private const val PULSE_LOW_SCALE = 0.55f

/**
 * The 6 dp "you are here" mark shared by the drawer and music rows; [color] transparent keeps its slot.
 * docs/29 §8.1: [pulsing] (scale 1 to 0.55 to 1 over 1.6 s) only while the track plays and is on
 * screen; a paused track's dot holds still, and no animation runs while it doesn't pulse.
 */
@Composable
internal fun CurrentDot(color: Color, modifier: Modifier = Modifier, pulsing: Boolean = false) {
    val scaled = if (pulsing) {
        val pulse = rememberInfiniteTransition(label = "currentDot").animateFloat(
            initialValue = 1f,
            targetValue = PULSE_LOW_SCALE,
            animationSpec = infiniteRepeatable(tween(PULSE_HALF_MS, easing = JellybeamEasing), RepeatMode.Reverse),
            label = "currentDotScale",
        )
        Modifier.graphicsLayer {
            scaleX = pulse.value
            scaleY = pulse.value
        }
    } else {
        Modifier
    }
    Box(modifier = modifier.size(6.dp).then(scaled).background(color, CircleShape))
}
