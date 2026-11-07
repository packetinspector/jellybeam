package tv.jellybeam.ui.music

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import tv.jellybeam.JellybeamTheme
import tv.jellybeam.LocalAccent
import tv.jellybeam.R

private val STRIP_HEIGHT = 6.dp
private val STRIP_GAP = 2.dp
private val STRIP_MIN = 5.dp
private val SEGMENT_RADIUS = 1.dp
private val PastInk = JellybeamTheme.Panna.copy(alpha = 0x40 / 255f)

/**
 * docs/29 §9 F's queue strip: one segment per track in play order ([durationsMs] already in that order,
 * `null` unknown), [current] filled with accent to [progress] (0..1). Laid out by [queueStripLayout];
 * a long queue draws its 40-track window with mono `… +n` at the ends that hide tracks.
 */
@Composable
internal fun QueueStrip(durationsMs: List<Long?>, current: Int, progress: () -> Float, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val style = monoStyle(JellybeamTheme.Grigio, 11)
    val more = stringResource(R.string.music_strip_more)
    val accent = LocalAccent.current.accent
    val labelHeight = remember(measurer, style) { measurer.measure("0", style).size.height }
    Canvas(modifier.fillMaxWidth().height(maxOf(STRIP_HEIGHT, 14.dp))) {
        val gap = STRIP_GAP.toPx()
        fun label(n: Int) = measurer.measure(more.format(n), style)
        val layout = queueStripLayout(durationsMs, current, size.width, gap, STRIP_MIN.toPx(), labelPx = { label(it).size.width.toFloat() })
        var left = 0f
        val top = (size.height - STRIP_HEIGHT.toPx()) / 2f
        val textTop = (size.height - labelHeight) / 2f
        if (layout.hiddenBefore > 0) {
            val text = label(layout.hiddenBefore)
            drawText(text, topLeft = Offset(0f, textTop))
            left = text.size.width + gap
        }
        if (layout.hiddenAfter > 0) {
            val text = label(layout.hiddenAfter)
            drawText(text, topLeft = Offset(size.width - text.size.width, textTop))
        }
        val radius = CornerRadius(SEGMENT_RADIUS.toPx())
        val h = STRIP_HEIGHT.toPx()
        layout.segments.forEach { s ->
            val at = Offset(left + s.x, top)
            drawRoundRect(if (s.kind == SegmentKind.Past) PastInk else JellybeamTheme.Hairline, at, Size(s.width, h), radius)
            if (s.kind == SegmentKind.Current) {
                drawRoundRect(accent, at, Size(s.width * progress().coerceIn(0f, 1f), h), radius)
            }
        }
    }
}
