package tv.jellybeam.ui.music

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import tv.jellybeam.player.drawPauseGlyph
import tv.jellybeam.player.drawPlayGlyph
import tv.jellybeam.player.drawSkipGlyph

/** A transport glyph drawn into its box in [tint]; the player's own marks, so music and video read the same. */
internal typealias MusicGlyph = DrawScope.(tint: Color) -> Unit

internal val PlayGlyph: MusicGlyph = { drawPlayGlyph(it) }
internal val PauseGlyph: MusicGlyph = { drawPauseGlyph(it) }
internal val PreviousGlyph: MusicGlyph = { drawSkipGlyph(forward = false, tint = it) }
internal val NextGlyph: MusicGlyph = { drawSkipGlyph(forward = true, tint = it) }

/** A single eighth note: the no-art tile's mark and the drawer's Now playing entry. */
internal val NoteGlyph: MusicGlyph = { tint ->
    val w = size.width
    val h = size.height
    val stemX = w * 0.62f
    val stem = w * 0.09f
    val headW = w * 0.42f
    val headH = h * 0.30f
    drawOval(tint, topLeft = Offset(stemX + stem - headW, h * 0.94f - headH), size = Size(headW, headH))
    drawRect(tint, topLeft = Offset(stemX, h * 0.08f), size = Size(stem, h * 0.74f))
    val flag = Path().apply {
        moveTo(stemX + stem, h * 0.08f)
        cubicTo(stemX + w * 0.30f, h * 0.18f, stemX + w * 0.36f, h * 0.34f, stemX + w * 0.26f, h * 0.48f)
        cubicTo(stemX + w * 0.28f, h * 0.34f, stemX + w * 0.18f, h * 0.27f, stemX + stem, h * 0.26f)
        close()
    }
    drawPath(flag, tint)
}

/** docs/29 §8.1: the filled favourite heart. */
internal val HeartGlyph: MusicGlyph = { tint ->
    val w = size.width
    val h = size.height
    val heart = Path().apply {
        moveTo(w * 0.5f, h * 0.9f)
        cubicTo(w * 0.1f, h * 0.62f, -w * 0.02f, h * 0.36f, w * 0.12f, h * 0.2f)
        cubicTo(w * 0.24f, h * 0.06f, w * 0.44f, h * 0.1f, w * 0.5f, h * 0.27f)
        cubicTo(w * 0.56f, h * 0.1f, w * 0.76f, h * 0.06f, w * 0.88f, h * 0.2f)
        cubicTo(w * 1.02f, h * 0.36f, w * 0.9f, h * 0.62f, w * 0.5f, h * 0.9f)
        close()
    }
    drawPath(heart, tint)
}

/** A ✓, the selected mark of a toggle pill and a menu row. */
internal val CheckGlyph: MusicGlyph = { tint ->
    val w = size.width
    val h = size.height
    val stroke = w * 0.12f
    drawLine(tint, Offset(w * 0.18f, h * 0.52f), Offset(w * 0.42f, h * 0.76f), stroke, StrokeCap.Round)
    drawLine(tint, Offset(w * 0.42f, h * 0.76f), Offset(w * 0.84f, h * 0.28f), stroke, StrokeCap.Round)
}

/** Repeat: two arrows chasing round a loop. */
internal val RepeatGlyph: MusicGlyph = { tint ->
    val w = size.width
    val h = size.height
    val stroke = Stroke(width = w * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val top = Path().apply {
        moveTo(w * 0.17f, h * 0.5f)
        lineTo(w * 0.17f, h * 0.4f)
        cubicTo(w * 0.17f, h * 0.32f, w * 0.23f, h * 0.25f, w * 0.31f, h * 0.25f)
        lineTo(w * 0.79f, h * 0.25f)
        moveTo(w * 0.67f, h * 0.13f)
        lineTo(w * 0.79f, h * 0.25f)
        lineTo(w * 0.67f, h * 0.37f)
    }
    val bottom = Path().apply {
        moveTo(w * 0.83f, h * 0.5f)
        lineTo(w * 0.83f, h * 0.6f)
        cubicTo(w * 0.83f, h * 0.68f, w * 0.77f, h * 0.75f, w * 0.69f, h * 0.75f)
        lineTo(w * 0.21f, h * 0.75f)
        moveTo(w * 0.33f, h * 0.87f)
        lineTo(w * 0.21f, h * 0.75f)
        lineTo(w * 0.33f, h * 0.63f)
    }
    drawPath(top, tint, style = stroke)
    drawPath(bottom, tint, style = stroke)
}

/** Queue: three list lines and a play mark. */
internal val QueueGlyph: MusicGlyph = { tint ->
    val w = size.width
    val h = size.height
    val stroke = w * 0.09f
    drawLine(tint, Offset(w * 0.17f, h * 0.25f), Offset(w * 0.71f, h * 0.25f), stroke, StrokeCap.Round)
    drawLine(tint, Offset(w * 0.17f, h * 0.46f), Offset(w * 0.71f, h * 0.46f), stroke, StrokeCap.Round)
    drawLine(tint, Offset(w * 0.17f, h * 0.67f), Offset(w * 0.5f, h * 0.67f), stroke, StrokeCap.Round)
    val play = Path().apply {
        moveTo(w * 0.62f, h * 0.58f)
        lineTo(w * 0.88f, h * 0.73f)
        lineTo(w * 0.62f, h * 0.88f)
        close()
    }
    drawPath(play, tint)
}

/** Move: an up and a down arrow (docs/29 §9 H2's ↕ on a picked-up row). */
internal val MoveGlyph: MusicGlyph = { tint ->
    val w = size.width
    val h = size.height
    val stroke = w * 0.1f
    drawLine(tint, Offset(w * 0.5f, h * 0.12f), Offset(w * 0.5f, h * 0.88f), stroke, StrokeCap.Round)
    drawLine(tint, Offset(w * 0.3f, h * 0.32f), Offset(w * 0.5f, h * 0.12f), stroke, StrokeCap.Round)
    drawLine(tint, Offset(w * 0.7f, h * 0.32f), Offset(w * 0.5f, h * 0.12f), stroke, StrokeCap.Round)
    drawLine(tint, Offset(w * 0.3f, h * 0.68f), Offset(w * 0.5f, h * 0.88f), stroke, StrokeCap.Round)
    drawLine(tint, Offset(w * 0.7f, h * 0.68f), Offset(w * 0.5f, h * 0.88f), stroke, StrokeCap.Round)
}
