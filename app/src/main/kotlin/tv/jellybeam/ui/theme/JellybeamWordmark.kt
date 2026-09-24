package tv.jellybeam.ui.theme

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import tv.jellybeam.JellybeamTheme

/**
 * The "Jellybeam" wordmark (docs/brand.md §4): one text run in [JellybeamTheme.BagelFatOne],
 * `Jelly` in Panna and `beam` in Pistacchio, kerned only between the `y` and the `b`. [rays] draws
 * the three bars over the `m`; launch screen and marketing only, never the header.
 */
@Composable
fun JellybeamWordmark(size: TextUnit, modifier: Modifier = Modifier, rays: Boolean = false) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val emPx = with(LocalDensity.current) { size.toPx() }
    BasicText(
        text = wordmarkText(),
        style = TextStyle(fontFamily = JellybeamTheme.BagelFatOne, fontSize = size),
        onTextLayout = { layout = it },
        modifier = modifier.drawWithContent {
            drawContent()
            val l = layout
            if (rays && l != null) {
                for (bar in WordmarkRays.bars(emPx, textEndPx = l.getLineRight(0), baselinePx = l.firstBaseline)) {
                    rotate(bar.degrees, pivot = bar.center) {
                        drawRoundRect(
                            color = JellybeamTheme.Pistacchio,
                            topLeft = bar.topLeft,
                            size = bar.size,
                            cornerRadius = CornerRadius(bar.size.height / 2f),
                        )
                    }
                }
            }
        },
    )
}

private fun wordmarkText() = buildAnnotatedString {
    withStyle(SpanStyle(color = JellybeamTheme.Panna)) {
        append("Jell")
        withStyle(SpanStyle(letterSpacing = WordmarkRays.Y_TO_B_KERN_EM.em)) { append("y") }
    }
    withStyle(SpanStyle(color = JellybeamTheme.Pistacchio)) { append("beam") }
}

/** Ray geometry in em of the wordmark size (§4), resolved to pixels for one laid-out text run. */
object WordmarkRays {
    const val Y_TO_B_KERN_EM = -0.03f

    const val BAR_WIDTH_EM = 0.107f
    const val GROUP_W_EM = 0.6f
    const val GROUP_RIGHT_PAST_TEXT_EM = 0.04f
    const val GROUP_TOP_ABOVE_BASELINE_EM = 1.16f

    /** x, y of the bar's unrotated top-left inside the group box, its length, and its rotation. */
    data class Spec(val x: Float, val y: Float, val length: Float, val degrees: Float)

    val SPECS = listOf(
        Spec(x = 0.067f, y = 0.093f, length = 0.293f, degrees = -24f),
        Spec(x = 0.267f, y = 0f, length = 0.333f, degrees = 8f),
        Spec(x = 0.453f, y = 0.173f, length = 0.253f, degrees = 46f),
    )

    data class Bar(val topLeft: Offset, val size: Size, val degrees: Float) {
        val center: Offset get() = Offset(topLeft.x + size.width / 2f, topLeft.y + size.height / 2f)
    }

    fun bars(emPx: Float, textEndPx: Float, baselinePx: Float): List<Bar> {
        val groupLeft = textEndPx + GROUP_RIGHT_PAST_TEXT_EM * emPx - GROUP_W_EM * emPx
        val groupTop = baselinePx - GROUP_TOP_ABOVE_BASELINE_EM * emPx
        return SPECS.map { s ->
            Bar(
                topLeft = Offset(groupLeft + s.x * emPx, groupTop + s.y * emPx),
                size = Size(s.length * emPx, BAR_WIDTH_EM * emPx),
                degrees = s.degrees,
            )
        }
    }
}
