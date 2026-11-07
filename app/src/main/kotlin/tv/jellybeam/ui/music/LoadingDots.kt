package tv.jellybeam.ui.music

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import tv.jellybeam.JellybeamTheme

private const val DOT_COUNT = 3
private const val CYCLE_MS = 1_200
private const val STAGGER_MS = 150
private const val LOW_SCALE = 0.5f
private const val LOW_ALPHA = 0.45f

/**
 * docs/29 §8.6: the app's one loading motif, three dots in a glyph-sized box so a control never
 * changes size; each pulses 0.5 to 1 in scale and 0.45 to 1 in opacity over 1.2 s, staggered 150 ms.
 * Runs only while composed, and the window's frame clock stops it while the Activity is stopped.
 */
@Composable
internal fun LoadingDots(modifier: Modifier = Modifier, color: Color = JellybeamTheme.Panna) {
    val transition = rememberInfiniteTransition(label = "loadingDots")
    Row(
        modifier = modifier.width(15.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(DOT_COUNT) { index ->
            // 0%, 80% and 100% low, 40% high, as the motif's keyframes.
            val pulse = transition.animateFloat(
                initialValue = 0f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = CYCLE_MS
                        0f at 0 using FastOutSlowInEasing
                        1f at CYCLE_MS * 2 / 5 using FastOutSlowInEasing
                        0f at CYCLE_MS * 4 / 5
                    },
                    initialStartOffset = StartOffset(index * STAGGER_MS),
                ),
                label = "loadingDot",
            )
            Box(
                modifier = Modifier
                    .size(3.5.dp)
                    .graphicsLayer {
                        val p = pulse.value
                        val scale = LOW_SCALE + (1f - LOW_SCALE) * p
                        scaleX = scale
                        scaleY = scale
                        alpha = LOW_ALPHA + (1f - LOW_ALPHA) * p
                    }
                    .background(color, CircleShape),
            )
        }
    }
}
