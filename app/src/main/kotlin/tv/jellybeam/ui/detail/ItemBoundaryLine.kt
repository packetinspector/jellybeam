package tv.jellybeam.ui.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints

/**
 * docs/19-detail-action-menu.md §1.5: draws the longest prefix of [items] (joined by
 * [separator]) that fits one line at the box's width, dropping trailing items whole
 * instead of ellipsizing mid-item; falls back to one item with a plain ellipsis only
 * if even that doesn't fit. Widths are measured once via [rememberTextMeasurer]; the
 * prefix pick reruns in [drawWithCache] on a size change only, via the pure and
 * JVM-testable [DetailFormatting.fittingItemCount].
 */
@Composable
internal fun ItemBoundaryLine(
    items: List<String>,
    separator: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    /** Aligns the fitted text to the box's end edge (used for the right-aligned season summary). */
    alignEnd: Boolean = false,
) {
    if (items.isEmpty()) return
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val (itemWidths, separatorWidth, lineHeightPx) = remember(items, separator, style, measurer) {
        val widths = items.map { measurer.measure(text = it, style = style).size.width.toFloat() }
        val sepWidth = measurer.measure(text = separator, style = style).size.width.toFloat()
        val lineHeight = measurer.measure(text = items.first(), style = style).size.height
        Triple(widths, sepWidth, lineHeight)
    }
    val lineHeightDp = with(density) { lineHeightPx.toDp() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(lineHeightDp)
            .drawWithCache {
                val maxWidthPx = size.width
                if (maxWidthPx <= 0f) {
                    onDrawBehind {}
                } else {
                    val fitCount = DetailFormatting.fittingItemCount(itemWidths, separatorWidth, maxWidthPx)
                    val text = items.take(fitCount).joinToString(separator)
                    val layout = measurer.measure(
                        text = text,
                        style = style,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        constraints = Constraints(maxWidth = maxWidthPx.toInt()),
                    )
                    // Ink width is line 0's right edge, not [layout.size] (the constraint width).
                    val inkWidth = layout.getLineRight(0)
                    val x = if (alignEnd) (maxWidthPx - inkWidth).coerceAtLeast(0f) else 0f
                    onDrawBehind { drawText(layout, topLeft = Offset(x, 0f)) }
                }
            },
    )
}
