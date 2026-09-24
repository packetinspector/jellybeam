package tv.jellybeam.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import tv.jellybeam.JellybeamTheme

/**
 * Cross-screen backdrop scrim (design spec §0.1): the two-layer wash every full-bleed backdrop
 * (Home's hero, the detail screens) draws over its art, replacing a flat/uniform dim.
 *
 * Two stacked linear gradients: [horizontalStops] across the backdrop's short axis, near-opaque
 * on [textColumnSide] and clearing toward the opposite edge; [verticalStops] a hand-off gradient
 * darkening the bottom edge so the backdrop blends into whatever sits below it.
 *
 * Both stop lists are `(fraction, alpha)` pairs against [JellybeamTheme.Notte]. [horizontalStops]'
 * fraction is measured from [textColumnSide] so a caller never has to flip the list when the text
 * column moves sides; [verticalStops]' fraction is measured from the bottom edge. Defaults are
 * Home hero's numbers; a taller/shorter backdrop can pass its own tuned lists.
 *
 * Usage: draw as the last child inside the same [Box] as the backdrop image, sized to match it.
 */
@Composable
fun BackdropScrim(
    modifier: Modifier = Modifier,
    textColumnSide: Alignment.Horizontal = Alignment.Start,
    horizontalStops: List<Pair<Float, Float>> = DEFAULT_HORIZONTAL_SCRIM_STOPS,
    verticalStops: List<Pair<Float, Float>> = DEFAULT_VERTICAL_SCRIM_STOPS,
) {
    val horizontalBrush = remember(textColumnSide, horizontalStops) {
        buildScrimBrush(horizontal = true, textColumnSide = textColumnSide, stops = horizontalStops)
    }
    val verticalBrush = remember(verticalStops) {
        buildScrimBrush(horizontal = false, textColumnSide = textColumnSide, stops = verticalStops)
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(brush = horizontalBrush)
                drawRect(brush = verticalBrush)
            },
    )
}

/**
 * §0.1's default text-column layer: near-opaque at the text edge (fraction 0), clearing to 15%
 * by the far edge -- the design spec's `90deg`/0%/34%/60%/100% stops. Home's hero is the only
 * caller left on this default; the three Detail screens pass [DETAIL_HORIZONTAL_SCRIM_STOPS].
 */
val DEFAULT_HORIZONTAL_SCRIM_STOPS: List<Pair<Float, Float>> = listOf(
    0f to 1.00f,
    0.34f to 0.94f,
    0.60f to 0.60f,
    1.00f to 0.15f,
)

/** §0.1's default hand-off layer: opaque at the bottom edge, clear by 46% of the way up -- the
 * design spec's `0deg`/0%/12%/46% stops. Home keeps this default; Detail has its own shared curve.
 */
val DEFAULT_VERTICAL_SCRIM_STOPS: List<Pair<Float, Float>> = listOf(
    0.00f to 1.00f,
    0.12f to 0.80f,
    0.46f to 0.00f,
)

/**
 * §D.2: one horizontal recipe shared by all three Detail screens, per the design spec's
 * `90deg`/0%/34%/60%/100% stops. A separate constant since [DEFAULT_HORIZONTAL_SCRIM_STOPS] is
 * also Home hero's default.
 */
val DETAIL_HORIZONTAL_SCRIM_STOPS: List<Pair<Float, Float>> = listOf(
    0f to 1.00f,
    0.34f to 0.92f,
    0.60f to 0.55f,
    1.00f to 0.12f,
)

/**
 * §D.2's shared Detail hand-off layer: opaque at the bottom edge, clear by 46% of the way up --
 * the design spec's `0deg`/0%/13%/46% stops. The Episode screen overrides this with its own
 * deeper [tv.jellybeam.ui.detail.EPISODE_VERTICAL_SCRIM_STOPS] (§C.2).
 */
val DETAIL_VERTICAL_SCRIM_STOPS: List<Pair<Float, Float>> = listOf(
    0.00f to 1.00f,
    0.13f to 0.82f,
    0.46f to 0.00f,
)

/** Builds one of [BackdropScrim]'s two gradients. [horizontal] picks the axis; for the
 * horizontal layer, [textColumnSide] flips which edge fraction 0 refers to. */
private fun buildScrimBrush(
    horizontal: Boolean,
    textColumnSide: Alignment.Horizontal,
    stops: List<Pair<Float, Float>>,
): Brush {
    val orderedStops = stops
        .map { (fraction, alpha) ->
            val canvasFraction = if (horizontal) {
                if (textColumnSide == Alignment.Start) fraction else 1f - fraction
            } else {
                // Vertical: fraction is measured from the bottom; Compose's is measured from the
                // top.
                1f - fraction
            }
            canvasFraction to alpha
        }
        .sortedBy { it.first }
        .map { (fraction, alpha) -> fraction to JellybeamTheme.Notte.copy(alpha = alpha) }
        .toTypedArray()
    return if (horizontal) {
        Brush.horizontalGradient(*orderedStops)
    } else {
        Brush.verticalGradient(*orderedStops)
    }
}
