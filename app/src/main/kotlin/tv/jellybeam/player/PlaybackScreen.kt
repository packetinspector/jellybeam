package tv.jellybeam.player

import android.graphics.Typeface
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt
import tv.jellybeam.AppGraph
import tv.jellybeam.JellybeamTheme
import tv.jellybeam.R
import tv.jellybeam.ui.cards.CardArtImage
import tv.jellybeam.ui.cards.CardFormatting
import tv.jellybeam.ui.detail.DetailFormatting
import tv.jellybeam.ui.focus.requestFocusUntilSuccess
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import uniffi.jellybeam_core.Card
import uniffi.jellybeam_core.GlideDirection
import uniffi.jellybeam_core.GlideSeek
import uniffi.jellybeam_core.MediaSegment
import uniffi.jellybeam_core.MediaSegmentKind
import uniffi.jellybeam_core.MediaStreamKind
import uniffi.jellybeam_core.OsdDetailSetting
import uniffi.jellybeam_core.PlaybackOsdDetail
import uniffi.jellybeam_core.PlayMethodFfi
import uniffi.jellybeam_core.SubtitlePositionPreset
import uniffi.jellybeam_core.TrickplayMetaFfi
import uniffi.jellybeam_core.TrickplayTileFfi

/** Every dp/sp literal here traces to docs/12; [OsdColor] covers its `surface`/`bg` tokens, which
 * have no [JellybeamTheme] equivalent. */
private object Osd {
    val SAFE_INSET = 48.dp
    val BLOCK_BOTTOM = 27.dp
    val GAP_FULL = 7.dp
    val GAP_MINIMAL = 7.dp
    val SCRIM_HEIGHT_FULL = 200.dp
    val SCRIM_HEIGHT_MINIMAL = 160.dp
    val TITLE_MAX_WIDTH = 530.dp
    val TITLE_KICKER_GAP = 3.dp
    val TITLE_MINIMAL_GAP = 10.dp
    val TIME_ROW_GAP = 14.dp
    val TIME_COL_FULL_LEFT = 120.dp
    val TIME_COL_FULL_RIGHT = 160.dp
    val TIME_COL_MINIMAL = 65.dp
    val TRACK_HEIGHT_FULL = 2.dp
    val TRACK_HEIGHT_MINIMAL = 2.dp
    val TRACK_CANVAS_HEIGHT = 13.dp
    val CHAPTER_TICK_WIDTH = 1.dp
    val BUTTON_TARGET = 44.dp
    val BUTTON_GLYPH = 22.dp
    val BUTTON_GAP = 10.dp
    val BUTTON_BREAK_EXTRA = 6.dp
    val ACTIVE_DOT = 4.dp
    val SHEET_WIDTH = 450.dp
    val SHEET_MAX_Y = 350.dp
    val SHEET_PAD_TOP = 32.dp
    val SHEET_PAD_END = 48.dp
    val SHEET_PAD_BOTTOM = 26.dp
    val SHEET_PAD_START = 52.dp
    val SHEET_FADE_HEIGHT = 40.dp
    /** docs/12 §17: 81dp (18% of [SHEET_WIDTH]) transparent-to-tint fade before the fill goes
     * constant. */
    val SHEET_LEFT_FADE_WIDTH = 81.dp
    val MENU_WIDTH = 170.dp
    val MENU_RADIUS = 5.dp
    val MENU_V_PADDING = 10.dp
}

/** docs/12 §0's `surface`/`bg` tokens -- distinct from [JellybeamTheme.Surface]/
 * [JellybeamTheme.SurfacePanel], used elsewhere in the app's chrome. */
private object OsdColor {
    val Surface = Color(0xFF0B0908)
    val ScrimBase = Color(0xFF070605)
}

/** docs/12 §2's safe inset, for overlays that only need to clear the control zone. */
private val OSD_MARGIN = Osd.SAFE_INSET

private const val IDLE_TICK_MS = 250L

/** docs/12 §9: fixed cadence, one FFI call per tick. Independent of [IDLE_TICK_MS] since the glide
 * surface advances far more often than the OSD's idle clock. */
private const val GLIDE_TICK_MS = 33L
private val SELECT_KEYS = setOf(Key.DirectionCenter, Key.Enter)

/** docs/12 "Skip intro/credits": "live 5s" -- the post-skip Undo toast's auto-hide window. */
private const val SKIP_UNDO_WINDOW_MS = 5_000L

/** docs/12 §2: how far the OSD's reserved zone extends above the bottom, keeping the next-up
 * card/skip pill clear of it. */
private val CONTROL_ZONE_HEIGHT_FULL = 140.dp
private val CONTROL_ZONE_HEIGHT_MINIMAL = 120.dp

private fun controlZoneHeight(osdDetail: OsdDetailSetting): Dp =
    if (osdDetail == OsdDetailSetting.FULL) CONTROL_ZONE_HEIGHT_FULL else CONTROL_ZONE_HEIGHT_MINIMAL

// -- Pure OSD logic (unit-tested: PlaybackScreenControlsTest, PlaybackScreenBackActionTest) --

/** docs/12 §8's buttons, in [visibleControls]'s layout order: transport cluster (around
 * [PLAY_PAUSE]), then, past a flexible spacer, the secondary cluster. */
enum class ControlButton {
    PREV_EPISODE,
    SKIP_BACK,
    PLAY_PAUSE,
    SKIP_FORWARD,
    NEXT_EPISODE,
    SPEED,
    TRACKS,
    CHAPTERS,
    LIBRARY_INFO,
    STATS,
}

/**
 * docs/12 §8's table + §3's density rows. No "disabled" state: an unavailable action omits its
 * button rather than graying it out, so the list itself changes with
 * [isEpisode]/[hasPrev]/[hasNext]/[hasChapters]/[osdDetail].
 */
fun visibleControls(
    osdDetail: OsdDetailSetting,
    isEpisode: Boolean,
    hasPrev: Boolean,
    hasNext: Boolean,
    hasChapters: Boolean,
): List<ControlButton> = buildList {
    if (isEpisode && hasPrev) add(ControlButton.PREV_EPISODE)
    add(ControlButton.SKIP_BACK)
    add(ControlButton.PLAY_PAUSE)
    add(ControlButton.SKIP_FORWARD)
    if (isEpisode && hasNext) add(ControlButton.NEXT_EPISODE)
    if (osdDetail == OsdDetailSetting.FULL) add(ControlButton.SPEED)
    add(ControlButton.TRACKS)
    if (hasChapters) add(ControlButton.CHAPTERS)
    add(ControlButton.LIBRARY_INFO)
    if (osdDetail == OsdDetailSetting.FULL) add(ControlButton.STATS)
}

/** `"1×"`, `"1.25×"`, `"0.75×"` -- trailing zeros trimmed, per docs/12 §12's option list. */
fun formatSpeedLabel(rate: Float): String = when (rate) {
    0.5f -> "0.5×"
    0.75f -> "0.75×"
    1f -> "1×"
    1.25f -> "1.25×"
    1.5f -> "1.5×"
    2f -> "2×"
    else -> "${rate.toString().trimEnd('0').trimEnd('.')}×"
}

private enum class SeekDirection { BACK, FORWARD }

/** Returns a new scroll position only when a sheet genuinely has somewhere to move. */
internal fun sheetScrollTarget(current: Int, max: Int, delta: Int): Int? {
    if (max <= 0) return null
    val target = (current + delta).coerceIn(0, max)
    return target.takeIf { it != current }
}

/**
 * docs/12 §9's Back priority chain: menu -> picker -> sheet -> next-up -> OSD -> exit, one level
 * per press. [STOP_STILL_WATCHING] outranks [DISMISS_NEXT_UP]: Back always resolves the
 * still-watching card to Stop (§13). [CANCEL_GLIDE] cancels an active glide instead of reaching
 * the exit chain, an exception to "Back is not a wake key".
 */
enum class BackAction { CLOSE_MENU, CLOSE_PICKER, CLOSE_SHEET, STOP_STILL_WATCHING, DISMISS_NEXT_UP, CANCEL_GLIDE, HIDE_OSD, EXIT }

/** Resolves a Back press per [BackAction]. Independent booleans (not shared state) keep this a
 * pure, exhaustively-testable function. */
fun resolveBackAction(
    pickerOpen: Boolean,
    nextUpShown: Boolean,
    osdVisible: Boolean,
    menuOpen: Boolean = false,
    sheetOpen: Boolean = false,
    stillWatchingShown: Boolean = false,
    glideActive: Boolean = false,
): BackAction = when {
    menuOpen -> BackAction.CLOSE_MENU
    pickerOpen -> BackAction.CLOSE_PICKER
    sheetOpen -> BackAction.CLOSE_SHEET
    stillWatchingShown -> BackAction.STOP_STILL_WATCHING
    nextUpShown -> BackAction.DISMISS_NEXT_UP
    glideActive -> BackAction.CANCEL_GLIDE
    osdVisible -> BackAction.HIDE_OSD
    else -> BackAction.EXIT
}

/**
 * docs/15-focus-and-selection.md §4: focus returns to [invoker] if still in [visible]; else
 * [ControlButton.PLAY_PAUSE] if present; else the first button; `null` if [visible] is empty.
 */
internal fun resolveInvokerReturn(invoker: ControlButton?, visible: List<ControlButton>): ControlButton? =
    invoker?.takeIf { it in visible } ?: visible.firstOrNull { it == ControlButton.PLAY_PAUSE } ?: visible.firstOrNull()

/**
 * docs/12 §5 codec summary (Full only): `"1080p · H264 · EAC3 5.1"`, each part dropped
 * independently when absent; `null` when all are missing. Server codec strings are bare
 * (`"h264"`), so this uppercases verbatim (same convention as [StatsSheetFormat]'s VIDEO row).
 */
internal fun directPlayCodecSummary(detail: PlaybackOsdDetail?): String? {
    if (detail == null) return null
    val video = detail.mediaStreams.firstOrNull { it.streamType == MediaStreamKind.VIDEO }
    val audio = detail.mediaStreams.firstOrNull { it.streamType == MediaStreamKind.AUDIO && it.isDefault }
        ?: detail.mediaStreams.firstOrNull { it.streamType == MediaStreamKind.AUDIO }
    val parts = listOfNotNull(
        chipResolutionLabel(video?.width, video?.height),
        video?.codec?.takeIf { it.isNotBlank() }?.uppercase(Locale.US),
        chipAudioLabel(audio?.codec, audio?.channels),
    )
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** `"{height}p"`, falling back to a width ladder when height is omitted. Kept in sync with
 * [StatsSheetFormat]'s own copy (see [chipChannelLabel]). */
private fun chipResolutionLabel(width: Int?, height: Int?): String? {
    val classified = DetailFormatting.resolutionLabel(width, height)
    if (classified != null) return classified.lowercase(Locale.US)
    val fallbackHeight = chipHeightFromWidth(width) ?: return null
    return "${fallbackHeight}p"
}

private fun chipHeightFromWidth(width: Int?): Int? {
    val w = width?.takeIf { it > 0 } ?: return null
    return when {
        w >= 3800 -> 2160
        w >= 2550 -> 1440
        w >= 1800 -> 1080
        w >= 1200 -> 720
        w >= 700 -> 480
        else -> 360
    }
}

private fun chipAudioLabel(codec: String?, channels: Int?): String? {
    val name = codec?.takeIf { it.isNotBlank() }?.uppercase(Locale.US) ?: return null
    val channelPart = channels?.let(::chipChannelLabel)
    return if (channelPart != null) "$name $channelPart" else name
}

/** Kept in sync with [StatsSheetFormat]'s own private copy of this exact mapping. */
private fun chipChannelLabel(channels: Int): String = when (channels) {
    1 -> "Mono"
    2 -> "Stereo"
    6 -> "5.1"
    8 -> "7.1"
    else -> "$channels ch"
}

/**
 * Subtitle bottom-padding fraction while the OSD is hidden vs. shown. 0.08 pins Media3
 * SubtitleView's own DEFAULT_BOTTOM_PADDING_FRACTION (not stably re-exported); 0.24 clears the
 * OSD's whole bottom band.
 */
private const val SUBTITLE_BOTTOM_FRACTION_DEFAULT = 0.08f
private const val SUBTITLE_BOTTOM_FRACTION_OSD = 0.24f

/** Subtitle style settings (docs/09-settings-plan.md): the "Vertical position" preset ladder,
 * expressed as bottom-padding-fraction steps. */
private const val SUBTITLE_POSITION_DEFAULT_FRACTION = 0.08f
private const val SUBTITLE_POSITION_RAISED_FRACTION = 0.16f
private const val SUBTITLE_POSITION_HIGHER_FRACTION = 0.24f
private const val SUBTITLE_POSITION_HIGHEST_FRACTION = 0.32f

private fun subtitlePositionBottomFraction(position: SubtitlePositionPreset): Float = when (position) {
    SubtitlePositionPreset.DEFAULT -> SUBTITLE_POSITION_DEFAULT_FRACTION
    SubtitlePositionPreset.RAISED -> SUBTITLE_POSITION_RAISED_FRACTION
    SubtitlePositionPreset.HIGHER -> SUBTITLE_POSITION_HIGHER_FRACTION
    SubtitlePositionPreset.HIGHEST -> SUBTITLE_POSITION_HIGHEST_FRACTION
}

// -- Glyphs (no icon pack -- Canvas-drawn, plain shapes) -----------------

/** Canvas-drawn OSD glyphs whose geometry doesn't need any external state. */
private enum class SimpleGlyph { TRACKS, CHAPTERS, LIBRARY_INFO, STATS }

private enum class TransportGlyph { PLAY, PAUSE }

/** docs/12 §0: every stroked glyph line is 0.8dp. */
private val GLYPH_STROKE_WIDTH = 0.8.dp

/** docs/12 §0: the STATS pulse glyph alone is drawn heavier, at 0.95dp. */
private val STATS_PULSE_STROKE_WIDTH = 0.95.dp

// docs/12 §0: solid marks max 2.5dp wide; at 22dp [Osd.BUTTON_GLYPH] that's
// ~0.11 (2.5/22), the cap for PAUSE_BAR_WIDTH_FRACTION and EpisodeGlyph's bar fraction.
private const val PAUSE_BAR_WIDTH_FRACTION = 0.11f
private const val PAUSE_GAP_FRACTION = 0.25f
private const val PAUSE_BAR_HEIGHT_FRACTION = 0.82f
private const val PAUSE_CORNER_FRACTION = 0.30f
private const val PLAY_HEIGHT_FRACTION = 0.82f
private const val PLAY_EQUILATERAL_RATIO = 0.866f
private const val PLAY_CORNER_FRACTION = 0.14f
private const val PLAY_OPTICAL_SHIFT_FRACTION = 0.04f

@Composable
private fun TransportIcon(glyph: TransportGlyph, tint: Color, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(size)) {
        when (glyph) {
            TransportGlyph.PAUSE -> drawPauseGlyph(tint)
            TransportGlyph.PLAY -> drawPlayGlyph(tint)
        }
    }
}

private fun DrawScope.drawPauseGlyph(tint: Color) {
    val barWidth = size.width * PAUSE_BAR_WIDTH_FRACTION
    val gap = size.width * PAUSE_GAP_FRACTION
    val barHeight = size.height * PAUSE_BAR_HEIGHT_FRACTION
    val top = (size.height - barHeight) / 2f
    val startX = (size.width - (barWidth * 2f + gap)) / 2f
    val corner = CornerRadius(barWidth * PAUSE_CORNER_FRACTION, barWidth * PAUSE_CORNER_FRACTION)

    drawRoundRect(color = tint, topLeft = Offset(startX, top), size = Size(barWidth, barHeight), cornerRadius = corner)
    drawRoundRect(
        color = tint,
        topLeft = Offset(startX + barWidth + gap, top),
        size = Size(barWidth, barHeight),
        cornerRadius = corner,
    )
}

private fun DrawScope.drawPlayGlyph(tint: Color) {
    val triHeight = size.height * PLAY_HEIGHT_FRACTION
    val triWidth = triHeight * PLAY_EQUILATERAL_RATIO
    val left = (size.width - triWidth) / 2f + size.width * PLAY_OPTICAL_SHIFT_FRACTION
    val top = (size.height - triHeight) / 2f

    val vertices = listOf(
        Offset(left, top),
        Offset(left + triWidth, top + triHeight / 2f),
        Offset(left, top + triHeight),
    )
    val radius = minOf(triWidth, triHeight) * PLAY_CORNER_FRACTION
    drawPath(path = roundedPolygonPath(vertices, radius), color = tint)
}

/**
 * Closed polygon with each vertex rounded to [radius]: a quadratic curve from [radius] back along
 * the incoming edge, through the vertex, to [radius] along the outgoing edge, clamped per-vertex
 * to half the shorter adjacent edge.
 */
private fun roundedPolygonPath(points: List<Offset>, radius: Float): Path {
    val n = points.size
    fun towards(from: Offset, to: Offset, r: Float): Offset {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val length = kotlin.math.hypot(dx, dy)
        val clamped = r.coerceAtMost(length / 2f)
        return Offset(from.x + dx / length * clamped, from.y + dy / length * clamped)
    }

    val path = Path()
    val start = towards(points[0], points[1], radius)
    path.moveTo(start.x, start.y)
    for (i in 1..n) {
        val curr = points[i % n]
        val prev = points[(i - 1 + n) % n]
        val next = points[(i + 1) % n]
        val incoming = towards(curr, prev, radius)
        val outgoing = towards(curr, next, radius)
        path.lineTo(incoming.x, incoming.y)
        path.quadraticTo(curr.x, curr.y, outgoing.x, outgoing.y)
    }
    path.close()
    return path
}

/**
 * docs/12 §8's seek glyph: a ring + arrowhead + centered skip-seconds numeral. `back` mirrors the
 * ring+arrowhead across the viewBox centerline (x=28); the numeral stays unmirrored since its
 * anchor sits there. Uses the platform's bold monospace face, not MartianMono, to avoid a
 * null-typeface crash inside a [DrawScope]; untested.
 */
@Composable
private fun SeekGlyph(seconds: Long, back: Boolean, tint: Color, modifier: Modifier = Modifier) {
    val tintArgb = tint.toArgb()
    Canvas(modifier = modifier.size(Osd.BUTTON_GLYPH)) {
        val scale = size.width / 56f
        // docs/12 §0: every stroked glyph is 0.8dp (2u in this 56-unit viewBox).
        val strokeWidthPx = GLYPH_STROKE_WIDTH.toPx()
        val centerPx = Offset(28f * scale, 28f * scale)
        val radiusPx = 23f * scale
        // Starts at (28,5), angle -90, sweeping 270deg clockwise; mirroring across x=28 flips the
        // sweep counter-clockwise while leaving the start angle unmoved.
        drawArc(
            color = tint,
            startAngle = -90f,
            sweepAngle = if (back) -270f else 270f,
            useCenter = false,
            topLeft = Offset(centerPx.x - radiusPx, centerPx.y - radiusPx),
            size = Size(radiusPx * 2f, radiusPx * 2f),
            style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round),
        )
        // Arrowhead polygon: mirrored apex x = 56-37 = 19.
        val apexX = if (back) 19f else 37f
        val arrow = Path().apply {
            moveTo(28f * scale, 0f * scale)
            lineTo(28f * scale, 11f * scale)
            lineTo(apexX * scale, 5.5f * scale)
            close()
        }
        drawPath(arrow, color = tint)

        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            color = tintArgb
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            // docs/12 §0: painted size must be >=11sp -- 28 units at this 56-unit viewBox.
            textSize = 28f * scale
            textAlign = android.graphics.Paint.Align.CENTER
        }
        drawContext.canvas.nativeCanvas.drawText(seconds.toString(), 28f * scale, 37f * scale, paint)
    }
}

/** docs/12 §8 rows 1/5: "bar + left/right triangle" -- a bar on the trailing edge, a triangle
 * pointing the direction of travel on the leading edge. */
@Composable
private fun EpisodeGlyph(forward: Boolean, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(Osd.BUTTON_GLYPH)) {
        // docs/12 §0: solid bar capped at 2.5dp wide (~0.11 of the 22dp box).
        val barWidth = size.width * 0.11f
        val barHeight = size.height * 0.78f
        val triWidth = size.width * 0.46f
        val top = (size.height - barHeight) / 2f
        if (forward) {
            drawRoundRect(
                color = tint,
                topLeft = Offset(size.width - barWidth, top),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth * 0.3f),
            )
            val left = size.width - barWidth - triWidth
            val path = Path().apply {
                moveTo(left, top)
                lineTo(left + triWidth, top + barHeight / 2f)
                lineTo(left, top + barHeight)
                close()
            }
            drawPath(path, tint)
        } else {
            drawRoundRect(
                color = tint,
                topLeft = Offset(0f, top),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth * 0.3f),
            )
            val left = barWidth
            val path = Path().apply {
                moveTo(left + triWidth, top)
                lineTo(left, top + barHeight / 2f)
                lineTo(left + triWidth, top + barHeight)
                close()
            }
            drawPath(path, tint)
        }
    }
}

/** SPEED's own button content is a text label, not a Canvas glyph (docs/12 §8 row 6): live rate,
 * tinted accent whenever it isn't 1x, even unfocused. */
@Composable
private fun SpeedButtonLabel(rate: Float, focusedTint: Color, modifier: Modifier = Modifier) {
    val tint = if (rate != 1f) JellybeamTheme.Pistacchio else focusedTint
    BasicText(
        text = formatSpeedLabel(rate),
        modifier = modifier,
        style = TextStyle(fontFamily = JellybeamTheme.MartianMono, fontWeight = FontWeight.Bold, color = tint, fontSize = 14.sp),
    )
}

@Composable
private fun SimpleGlyphIcon(glyph: SimpleGlyph, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(Osd.BUTTON_GLYPH)) {
        when (glyph) {
            SimpleGlyph.TRACKS -> drawTracksGlyph(tint)
            SimpleGlyph.CHAPTERS -> drawChaptersGlyph(tint)
            SimpleGlyph.LIBRARY_INFO -> drawLibraryInfoGlyph(tint)
            SimpleGlyph.STATS -> drawStatsGlyph(tint)
        }
    }
}

/** docs/12 §8 row 7: "rounded rect + 2 rows of lines." */
private fun DrawScope.drawTracksGlyph(tint: Color) {
    // docs/12 §0: flat 0.8dp stroke, not a fraction of the glyph box.
    val stroke = GLYPH_STROKE_WIDTH.toPx()
    drawRoundRect(
        color = tint,
        topLeft = Offset(size.width * 0.12f, size.height * 0.12f),
        size = Size(size.width * 0.76f, size.height * 0.76f),
        cornerRadius = CornerRadius(size.width * 0.14f),
        style = Stroke(width = stroke),
    )
    fun row(y: Float) = drawLine(tint, Offset(size.width * 0.28f, size.height * y), Offset(size.width * 0.72f, size.height * y), stroke, StrokeCap.Round)
    row(0.40f)
    row(0.62f)
}

/** docs/12 §8 row 8: "3 rounded segments." */
private fun DrawScope.drawChaptersGlyph(tint: Color) {
    val segWidth = size.width * 0.22f
    val segHeight = size.height * 0.5f
    val y = (size.height - segHeight) / 2f
    val gap = size.width * 0.08f
    var x = size.width * 0.10f
    repeat(3) {
        drawRoundRect(color = tint, topLeft = Offset(x, y), size = Size(segWidth, segHeight), cornerRadius = CornerRadius(segWidth * 0.3f))
        x += segWidth + gap
    }
}

/** docs/12 §8 row 9: "circled i." */
private fun DrawScope.drawLibraryInfoGlyph(tint: Color) {
    // docs/12 §0: flat 0.8dp stroke.
    val stroke = GLYPH_STROKE_WIDTH.toPx()
    drawCircle(color = tint, radius = size.minDimension / 2f - stroke / 2f, style = Stroke(width = stroke))
    val dotRadius = size.minDimension * 0.06f
    drawCircle(color = tint, radius = dotRadius, center = Offset(size.width / 2f, size.height * 0.30f))
    drawLine(
        color = tint,
        start = Offset(size.width / 2f, size.height * 0.46f),
        end = Offset(size.width / 2f, size.height * 0.74f),
        strokeWidth = stroke,
        cap = StrokeCap.Round,
    )
}

/** docs/12 §8 row 10: "pulse polyline." */
private fun DrawScope.drawStatsGlyph(tint: Color) {
    // docs/12 §0: pulse glyph alone at 0.95dp, others 0.8dp.
    val stroke = STATS_PULSE_STROKE_WIDTH.toPx()
    val path = Path().apply {
        moveTo(size.width * 0.08f, size.height * 0.55f)
        lineTo(size.width * 0.32f, size.height * 0.55f)
        lineTo(size.width * 0.44f, size.height * 0.20f)
        lineTo(size.width * 0.58f, size.height * 0.80f)
        lineTo(size.width * 0.70f, size.height * 0.45f)
        lineTo(size.width * 0.92f, size.height * 0.45f)
    }
    drawPath(path, color = tint, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

// -- Center transient flash (docs/12 "Center flash") ---------------------
private const val FLASH_FADE_IN_MS = 80
private const val FLASH_HOLD_UNTIL_MS = 400
private const val FLASH_FADE_OUT_UNTIL_MS = 720
private val CENTER_FLASH_DIAMETER = 112.dp
private val CENTER_FLASH_ICON_SIZE = 48.dp

private data class CenterFlashContent(val text: String? = null, val icon: TransportGlyph? = null, val numeral: String? = null)

private data class SkipUndoState(val preSkipPositionTicks: Long, val segmentType: MediaSegmentKind)

private val TRACK_PICKER_WIDTH = 320.dp
private val TRACK_PICKER_MAX_HEIGHT = 480.dp
private val TRACK_PICKER_RADIUS = 8.dp
private val TRACK_ROW_RADIUS = 6.dp

/**
 * The playback surface + OSD, per docs/12. Full-bleed [PlayerView] (`useController = false`);
 * this Compose overlay is the only OSD.
 *
 * Focus model (docs/12 §9): `focusedButton` tracks a button identity, not an index into
 * [visibleControls] -- that list can reshuffle mid-session, so a position is re-derived via
 * `indexOf` only at press time. Reveal and a fresh session always land on
 * [ControlButton.PLAY_PAUSE]. Media play/pause keys fall through unconsumed whether or not the
 * OSD is visible (see [MediaSessionHolder]); every other key wakes a hidden OSD. Sheets/menus/the
 * picker pin [PlaybackOsdController] visible (see [pinned]) and render independently of
 * `osdVisible`, same as the next-up card.
 */
// OptIn: PlayerView.subtitleView / SubtitleView.setBottomPaddingFraction are @UnstableApi.
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun PlaybackScreen(
    viewModel: PlaybackViewModel,
    onFinish: () -> Unit,
    onReauthorizationRequired: () -> Unit,
    focusRestoreEpoch: Long = 0L,
    /**
     * docs/12 §13: fired for [PlaybackEvent.FinishToDetail] instead of [onFinish] -- routes to the
     * given episode's detail page rather than exiting playback.
     */
    onFinishToDetail: (itemId: String) -> Unit = { onFinish() },
    /** docs/17 §2/§3: true for the span between PiP enter/exit; gates everything below the player
     * surface so the PiP window shows only bare video. */
    inPictureInPicture: Boolean = false,
    /** [BackAction.EXIT]'s handler. Defaults to [onFinish]; [PlaybackActivity] overrides it to try
     * PiP first, falling back to [onFinish] (docs/17 §3). */
    onExitRequested: () -> Unit = onFinish,
) {
    val state by viewModel.state.collectAsState()
    val previewTile by viewModel.previewTile.collectAsState()
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val osd = remember { PlaybackOsdController() }
    var osdVisible by remember { mutableStateOf(osd.isVisible) }
    val trickplayPreview = remember { TrickplaySeekPreviewController() }
    var trickplayPreviewState by remember { mutableStateOf(trickplayPreview.preview) }
    var centerFlash by remember { mutableStateOf<CenterFlashContent?>(null) }
    val flashAlpha = remember { Animatable(0f) }
    var flashJob by remember { mutableStateOf<Job?>(null) }
    val focusRequester = remember { FocusRequester() }
    var pickerFocusIndex by remember { mutableStateOf(0) }
    /** The button row's focused identity; null only while another overlay owns interaction. */
    var focusedButton by remember { mutableStateOf<ControlButton?>(null) }
    /** docs/15 §4: the button that opened the current nested surface, captured before
     * [focusedButton] is cleared, consumed by `restoreInvokerFocus` on close. */
    var menuInvoker by remember { mutableStateOf<ControlButton?>(null) }
    var activeSegment by remember { mutableStateOf<MediaSegment?>(null) }
    var skipUndo by remember { mutableStateOf<SkipUndoState?>(null) }
    val sheetScrollState = rememberScrollState()
    val sheetScrollStepPx = remember(density) { with(density) { 120.dp.roundToPx() } }
    var speedMenuFocusIndex by remember { mutableStateOf(0) }
    var chaptersMenuFocusIndex by remember { mutableStateOf(0) }

    /**
     * docs/12 §9: one [GlideSeekController] per READY session, rebuilt only on item/duration
     * change (`null` until duration is known). Chapters must not rebuild it (would lose hold
     * state); forwarded via the `LaunchedEffect(glide, state.chapters)` below instead.
     */
    val glide = remember(state.itemName, state.durationTicks) {
        val durationTicks = state.durationTicks
        if (durationTicks == null || durationTicks <= 0L) {
            null
        } else {
            val durationMs = PlaybackTicks.ticksToMs(durationTicks)
            val host = object : GlideHost {
                override fun positionMs(): Long = PlaybackTicks.ticksToMs(viewModel.positionTicks.value)

                override fun tapSeek(direction: GlideDirection): Long {
                    val current = viewModel.state.value
                    val deltaMs = if (direction == GlideDirection.BACK) -current.skipBackMs else current.skipForwardMs
                    return viewModel.tapSeek(deltaMs)
                }

                override fun commitSeek(targetMs: Long, endClamped: Boolean) {
                    viewModel.commitGlide(targetMs, endClamped)
                }

                override fun noteInput() = viewModel.noteUserInput()

                override fun sampleTile(targetMs: Long, nowMs: Long, lastSampleMs: Long?, direction: GlideDirection): TrickplayTileFfi? =
                    viewModel.glideSampleTile(targetMs, nowMs, lastSampleMs, direction)

                override fun wantSheets(targetMs: Long, rate: Int, direction: GlideDirection) =
                    viewModel.glideWantSheets(targetMs, rate, direction)

                override fun previewStart(tapTargetMs: Long, direction: GlideDirection) = viewModel.glidePreviewStart(tapTargetMs, direction)

                override fun releaseTarget(targetMs: Long, direction: GlideDirection) = viewModel.glideReleaseTarget(targetMs, direction)
            }
            val seek = GlideSeek(durationMs.toULong(), viewModel.glideChapterStartsMs().map { it.toULong() })
            GlideSeekController(UniffiGlideMachine(seek), host)
        }
    }
    /** [State], not `by remember`, so this composable's body never reads `.value` -- a per-tick
     * write would otherwise recompose the whole composable. */
    val glideSurfaceState = remember { mutableStateOf<GlideSurfaceState?>(null) }
    /** Bumped on every `glide.onKeyDown` that returns `true`, to (re)start the tick loop below. */
    var glideEpoch by remember { mutableIntStateOf(0) }

    LaunchedEffect(glide, glideEpoch) {
        if (glide == null) return@LaunchedEffect
        while (glide.needsTicks) {
            glide.tick()
            glideSurfaceState.value = glide.surface
            delay(GLIDE_TICK_MS)
        }
        glideSurfaceState.value = null
    }

    // Forwards a chapters change to the live machine without rebuilding it.
    LaunchedEffect(glide, state.chapters) {
        glide?.setChapterStarts(viewModel.glideChapterStartsMs())
    }

    // docs/12 §9: revealing the OSD ends any active glide; glide never reveals/pins the OSD.
    LaunchedEffect(osdVisible) {
        val g = glide
        if (osdVisible && g != null && g.resetIfActive()) {
            glideSurfaceState.value = g.surface
        }
    }

    // Root-relative x centers per button (keyed by identity, not index, since visibleControls can
    // reorder), feeding the speed/chapters menu anchor.
    val buttonCentersPx = remember { mutableStateOf(emptyMap<ControlButton, Float>()) }

    val visibleButtons = remember(state.osdDetail, state.itemType, state.hasPreviousEpisode, state.hasNextEpisode, state.chapters) {
        visibleControls(
            osdDetail = state.osdDetail,
            isEpisode = state.itemType == "Episode",
            hasPrev = state.hasPreviousEpisode,
            hasNext = state.hasNextEpisode,
            hasChapters = state.chapters.isNotEmpty(),
        )
    }

    fun flash(text: String, numeral: String? = null) {
        centerFlash = CenterFlashContent(text = text, numeral = numeral)
        flashJob?.cancel()
        flashJob = scope.launch {
            flashAlpha.snapTo(0f)
            flashAlpha.animateTo(1f, tween(FLASH_FADE_IN_MS))
            delay((FLASH_HOLD_UNTIL_MS - FLASH_FADE_IN_MS).toLong())
            flashAlpha.animateTo(0f, tween(FLASH_FADE_OUT_UNTIL_MS - FLASH_HOLD_UNTIL_MS))
            centerFlash = null
        }
    }

    fun flashTransport(icon: TransportGlyph) {
        centerFlash = CenterFlashContent(icon = icon)
        flashJob?.cancel()
        flashJob = scope.launch {
            flashAlpha.snapTo(0f)
            flashAlpha.animateTo(1f, tween(FLASH_FADE_IN_MS))
            delay((FLASH_HOLD_UNTIL_MS - FLASH_FADE_IN_MS).toLong())
            flashAlpha.animateTo(0f, tween(FLASH_FADE_OUT_UNTIL_MS - FLASH_HOLD_UNTIL_MS))
            centerFlash = null
        }
    }

    fun openPickerAndFocusSelected() {
        viewModel.openTrackPicker()
        val picker = viewModel.state.value.trackPicker ?: return
        val combined = picker.audioTracks + picker.subtitleTracks
        pickerFocusIndex = combined.indexOfFirst { it.selected }.let { if (it >= 0) it else 0 }
    }

    /** [visibleControls] against [viewModel]'s current state, not this composition's stale
     * [visibleButtons] snapshot -- used wherever a fresh read matters (e.g. the key handler). */
    fun currentVisibleButtons(state: PlaybackUiState = viewModel.state.value): List<ControlButton> = visibleControls(
        osdDetail = state.osdDetail,
        isEpisode = state.itemType == "Episode",
        hasPrev = state.hasPreviousEpisode,
        hasNext = state.hasNextEpisode,
        hasChapters = state.chapters.isNotEmpty(),
    )

    /** docs/15 §4: every nested-surface close routes through here. Resolves [menuInvoker] against
     * the current button set via [resolveInvokerReturn], then consumes it. */
    fun restoreInvokerFocus(current: PlaybackUiState) {
        focusedButton = resolveInvokerReturn(menuInvoker, currentVisibleButtons(current))
        menuInvoker = null
    }

    /** docs/12 §9: OSD reveal always sets focus to Play/Pause, never restores secondary focus. */
    fun revealOsdToPlayPause() {
        osd.onKeyEvent()
        osdVisible = true
        val buttons = currentVisibleButtons()
        focusedButton = if (buttons.contains(ControlButton.PLAY_PAUSE)) ControlButton.PLAY_PAUSE else buttons.firstOrNull()
    }

    BackHandler(onBack = {
        val current = viewModel.state.value
        when (
            resolveBackAction(
                pickerOpen = current.trackPicker != null,
                nextUpShown = current.nextUp != null,
                osdVisible = osdVisible,
                menuOpen = current.speedMenuOpen || current.chaptersMenuOpen,
                sheetOpen = current.statsSheetLive != null || current.libraryInfoOverlay != null,
                stillWatchingShown = current.stillWatching != null,
                glideActive = glide?.isGliding == true,
            )
        ) {
            BackAction.CLOSE_MENU -> {
                if (current.speedMenuOpen) viewModel.closeSpeedMenu() else viewModel.closeChaptersMenu()
                restoreInvokerFocus(current)
            }
            BackAction.CLOSE_PICKER -> {
                viewModel.closeTrackPicker()
                restoreInvokerFocus(current)
            }
            BackAction.CLOSE_SHEET -> {
                if (current.statsSheetLive != null) viewModel.closeInfoOverlay() else viewModel.closeLibraryInfoOverlay()
                restoreInvokerFocus(current)
            }
            BackAction.STOP_STILL_WATCHING -> viewModel.stillWatchingStop()
            BackAction.DISMISS_NEXT_UP -> viewModel.dismissNextUp()
            BackAction.CANCEL_GLIDE -> {
                glide?.cancel()
                glideSurfaceState.value = glide?.surface
            }
            BackAction.HIDE_OSD -> {
                osd.hide()
                osdVisible = false
            }
            BackAction.EXIT -> onExitRequested()
        }
    })

    LaunchedEffect(focusRestoreEpoch) {
        requestFocusUntilSuccess { focusRequester.requestFocus() }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(IDLE_TICK_MS)
            osd.tick()
            osdVisible = osd.isVisible
            trickplayPreview.tick()
            trickplayPreviewState = trickplayPreview.preview
            activeSegment = if (skipUndo == null) {
                SkipSegment.activeSegment(state.mediaSegments, viewModel.positionTicks.value)
                    ?.takeIf { SkipSegment.decision(it.segmentType, state.skipSegmentActions) == SegmentDecision.PILL }
            } else {
                null
            }
        }
    }

    LaunchedEffect(state.isPaused) {
        osd.onPausedChanged(state.isPaused)
        osdVisible = osd.isVisible
    }

    // A fresh session lands focus on PLAY_PAUSE, like a reveal-from-hidden.
    LaunchedEffect(state.phase, state.itemName) {
        if (state.phase == PlaybackUiState.Phase.READY) {
            val buttons = currentVisibleButtons(state)
            focusedButton = if (buttons.contains(ControlButton.PLAY_PAUSE)) ControlButton.PLAY_PAUSE else buttons.firstOrNull()
        }
    }

    // docs/12 §9's "never auto-hide while..." list, minus paused (handled above).
    val pinned = state.trackPicker != null ||
        state.statsSheetLive != null ||
        state.libraryInfoOverlay != null ||
        state.speedMenuOpen ||
        state.chaptersMenuOpen ||
        state.nextUp != null ||
        state.stillWatching != null ||
        trickplayPreviewState != null ||
        state.reconnecting != null
    LaunchedEffect(pinned) {
        osd.setPinned(pinned)
        osdVisible = osd.isVisible
    }

    LaunchedEffect(state.nextUp != null, state.stillWatching != null) {
        if (state.nextUp != null || state.stillWatching != null) {
            focusedButton = null
        } else if (state.phase == PlaybackUiState.Phase.READY) {
            val buttons = currentVisibleButtons(state)
            focusedButton = if (buttons.contains(ControlButton.PLAY_PAUSE)) ControlButton.PLAY_PAUSE else buttons.firstOrNull()
        }
    }

    // Safety net (docs/15 §4): restores a real button identity if some close path left
    // focusedButton null without already routing through restoreInvokerFocus.
    LaunchedEffect(state.trackPicker != null) {
        if (state.trackPicker == null && state.nextUp == null && state.stillWatching == null && state.phase == PlaybackUiState.Phase.READY && focusedButton == null) {
            restoreInvokerFocus(state)
        }
    }

    LaunchedEffect(state.libraryInfoOverlay, state.statsSheetLive != null) {
        if (state.libraryInfoOverlay != null || state.statsSheetLive != null) sheetScrollState.scrollTo(0)
    }

    LaunchedEffect(state.speedMenuOpen) {
        if (state.speedMenuOpen) {
            speedMenuFocusIndex = SPEED_OPTIONS.indexOf(state.playbackRate).let { if (it >= 0) it else 0 }
        }
    }
    LaunchedEffect(state.chaptersMenuOpen) {
        if (state.chaptersMenuOpen) chaptersMenuFocusIndex = 0
    }

    LaunchedEffect(skipUndo) {
        if (skipUndo != null) {
            delay(SKIP_UNDO_WINDOW_MS)
            skipUndo = null
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                PlaybackEvent.Finish -> onFinish()
                is PlaybackEvent.FinishToDetail -> onFinishToDetail(event.itemId)
                PlaybackEvent.ReauthorizationRequired -> onReauthorizationRequired()
                is PlaybackEvent.FinishWithMessage -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_LONG).show()
                    onFinish()
                }
                is PlaybackEvent.AutoSkipped -> {
                    skipUndo = SkipUndoState(event.preSkipPositionTicks, event.segmentType)
                    activeSegment = null
                    osd.onKeyEvent()
                    osdVisible = true
                }
            }
        }
    }

    val handleKeyEvent = remember(viewModel, sheetScrollState, sheetScrollStepPx, glide) {
        keyHandler@{ event: KeyEvent ->
            // docs/12 §9: every Left/Right KeyUp reaches the glide machine regardless of OSD
            // visibility; an unstarted glide reports unconsumed. A release whose direction didn't
            // start the current cycle is ignored (reversal).
            if (event.type != KeyEventType.KeyDown) {
                if (event.type == KeyEventType.KeyUp && (event.key == Key.DirectionLeft || event.key == Key.DirectionRight)) {
                    val g = glide
                    val direction = if (event.key == Key.DirectionLeft) GlideDirection.BACK else GlideDirection.FORWARD
                    return@keyHandler if (g != null && g.onKeyUp(direction)) {
                        glideSurfaceState.value = g.surface
                        true
                    } else {
                        false
                    }
                }
                return@keyHandler false
            }

            // `repeatCount == 0` avoids spamming the FFI call on a held key.
            if (event.nativeKeyEvent.repeatCount == 0) viewModel.noteUserInput()

            val current = viewModel.state.value
            val currentButtons = currentVisibleButtons(current)

            // docs/12 §9: Page/Channel Up=next chapter, Down=chapter
            // start/previous. Resolved before the OSD-visibility branches
            // below so it works identically hidden or shown. [PageKeys.resolve]
            // doesn't know whether the item has chapters -- that's resolved
            // via [PlaybackViewModel.jumpToChapter]'s null return.
            if (PageKeys.isPageKey(event.key)) {
                val nestedSurfaceOpen = current.speedMenuOpen || current.chaptersMenuOpen ||
                    current.trackPicker != null || current.statsSheetLive != null || current.libraryInfoOverlay != null
                val cardShowing = current.nextUp != null || current.stillWatching != null
                val glideActive = glide?.isActive == true
                val action = PageKeys.resolve(
                    key = event.key,
                    repeatCount = event.nativeKeyEvent.repeatCount,
                    nestedSurfaceOpen = nestedSurfaceOpen,
                    cardShowing = cardShowing,
                    glideActive = glideActive,
                )
                when (action) {
                    PageKeyAction.CONSUME_NOOP -> return@keyHandler true
                    PageKeyAction.NEXT_CHAPTER, PageKeyAction.PREV_CHAPTER -> {
                        // Visible-OSD resets the idle clock without touching focusedButton;
                        // hidden-OSD doesn't reveal it, same as the silent seek above.
                        if (osdVisible) osd.onKeyEvent()
                        val forward = action == PageKeyAction.NEXT_CHAPTER
                        val targetTicks = viewModel.jumpToChapter(forward)
                        if (targetTicks != null) {
                            // centerFlash renders independent of osdVisible, so this flashes
                            // whether the OSD is hidden or shown.
                            flash(if (forward) "⏭" else "⏮", Chapters.nameAt(current.chapters, targetTicks))
                        }
                        return@keyHandler true
                    }
                    PageKeyAction.PASS -> Unit // unreachable here -- this branch only ever sees PageKeys.isPageKey keys
                }
            }

            fun doSeek(direction: SeekDirection, showFeedback: Boolean = true) {
                val baseMs = if (direction == SeekDirection.BACK) current.skipBackMs else current.skipForwardMs
                val signedMs = baseMs * if (direction == SeekDirection.BACK) -1L else 1L
                // Hidden seeks have no preview UI, so skip the trickplay tile lookup too -- no
                // unused FFI work on the D-pad hot path.
                val result = viewModel.seek(signedMs, resolveTrickplay = showFeedback)
                if (showFeedback) {
                    val glyph = if (direction == SeekDirection.BACK) "◀◀" else "▶▶"
                    flash(glyph, "${baseMs / 1000L}s")
                    if (result != null) trickplayPreview.show(result.targetPositionMs, result.tile) else trickplayPreview.clear()
                    trickplayPreviewState = trickplayPreview.preview
                }
            }

            fun focusPlayPauseOrFirst() {
                focusedButton = if (currentButtons.contains(ControlButton.PLAY_PAUSE)) ControlButton.PLAY_PAUSE else currentButtons.firstOrNull()
            }

            // Most keys wake a hidden OSD; Left/Right are silent seek
            // shortcuts. Media play/pause keys are the one exception (docs/12 §9)
            // -- they fall through unconsumed to [MediaSessionHolder]'s own
            // callback instead of waking the OSD.
            if (!osdVisible) {
                if (event.key == Key.MediaPlayPause || event.key == Key.MediaPlay || event.key == Key.MediaPause) {
                    return@keyHandler false
                }
                // Back is a navigation level, not an OSD-wake key -- must reach BackHandler when
                // hidden.
                if (event.key == Key.Back || event.key == Key.Escape) return@keyHandler false
                when (event.key) {
                    // docs/12 §9: first press fires the tap seek immediately via
                    // `glide.onKeyDown` -> `GlideHost.tapSeek`, then starts the hold timer.
                    // `glide == null` falls back to plain tap seek.
                    Key.DirectionLeft -> {
                        if (event.nativeKeyEvent.repeatCount == 0) {
                            if (glide?.onKeyDown(GlideDirection.BACK, osdVisible = false) == true) {
                                glideEpoch++
                            } else {
                                doSeek(SeekDirection.BACK, showFeedback = false)
                            }
                        }
                    }
                    Key.DirectionRight -> {
                        if (event.nativeKeyEvent.repeatCount == 0) {
                            if (glide?.onKeyDown(GlideDirection.FORWARD, osdVisible = false) == true) {
                                glideEpoch++
                            } else {
                                doSeek(SeekDirection.FORWARD, showFeedback = false)
                            }
                        }
                    }
                    else -> revealOsdToPlayPause()
                }
                return@keyHandler true
            }

            // Android's Back dispatcher owns the real Back key; handling
            // KeyDown here closes one layer, then KeyUp closes a second
            // (or exits). Escape has no platform dispatch, so panels still
            // consume it explicitly below.
            if (event.key == Key.Back) return@keyHandler false

            if (current.speedMenuOpen) {
                when (event.key) {
                    Key.Escape, Key.Menu -> {
                        viewModel.closeSpeedMenu()
                        restoreInvokerFocus(current)
                    }
                    Key.DirectionUp -> speedMenuFocusIndex = (speedMenuFocusIndex - 1).coerceAtLeast(0)
                    Key.DirectionDown -> speedMenuFocusIndex = (speedMenuFocusIndex + 1).coerceAtMost(SPEED_OPTIONS.lastIndex)
                    in SELECT_KEYS -> viewModel.setPlaybackRate(SPEED_OPTIONS[speedMenuFocusIndex])
                    else -> Unit
                }
                return@keyHandler true
            }

            if (current.chaptersMenuOpen) {
                val rowCount = current.chapters.size
                when (event.key) {
                    Key.Escape, Key.Menu -> {
                        viewModel.closeChaptersMenu()
                        restoreInvokerFocus(current)
                    }
                    Key.DirectionUp -> chaptersMenuFocusIndex = (chaptersMenuFocusIndex - 1).coerceAtLeast(0)
                    Key.DirectionDown -> chaptersMenuFocusIndex = (chaptersMenuFocusIndex + 1).coerceAtMost((rowCount - 1).coerceAtLeast(0))
                    // jumpToChapterStart closes the menu itself (unlike the
                    // speed menu) -- docs/15-focus-and-selection.md §4's
                    // "closes itself after a choice" path, routed through
                    // the same restore.
                    in SELECT_KEYS -> current.chapters.getOrNull(chaptersMenuFocusIndex)?.let {
                        viewModel.jumpToChapterStart(it.startPositionTicks)
                        restoreInvokerFocus(current)
                    }
                    else -> Unit
                }
                return@keyHandler true
            }

            val picker = current.trackPicker
            if (picker != null) {
                val rowCount = picker.audioTracks.size + picker.subtitleTracks.size
                when (event.key) {
                    Key.Escape, Key.Menu -> {
                        viewModel.closeTrackPicker()
                        restoreInvokerFocus(current)
                    }
                    Key.DirectionUp -> pickerFocusIndex = (pickerFocusIndex - 1).coerceAtLeast(0)
                    Key.DirectionDown -> pickerFocusIndex = (pickerFocusIndex + 1).coerceAtMost((rowCount - 1).coerceAtLeast(0))
                    in SELECT_KEYS -> {
                        val audioCount = picker.audioTracks.size
                        if (pickerFocusIndex < audioCount) {
                            picker.audioTracks.getOrNull(pickerFocusIndex)?.let { viewModel.chooseAudio(it.id) }
                        } else {
                            picker.subtitleTracks.getOrNull(pickerFocusIndex - audioCount)?.let { viewModel.chooseSubtitle(it.id) }
                        }
                    }
                    else -> Unit
                }
                return@keyHandler true
            }

            if (current.statsSheetLive != null || current.libraryInfoOverlay != null) {
                when (event.key) {
                    Key.Escape, Key.Menu -> {
                        if (current.statsSheetLive != null) viewModel.closeInfoOverlay() else viewModel.closeLibraryInfoOverlay()
                        restoreInvokerFocus(current)
                    }
                    Key.DirectionUp -> scope.launch {
                        sheetScrollTarget(sheetScrollState.value, sheetScrollState.maxValue, -sheetScrollStepPx)
                            ?.let { sheetScrollState.animateScrollTo(it) }
                    }
                    Key.DirectionDown -> scope.launch {
                        sheetScrollTarget(sheetScrollState.value, sheetScrollState.maxValue, sheetScrollStepPx)
                            ?.let { sheetScrollState.animateScrollTo(it) }
                    }
                    else -> Unit
                }
                return@keyHandler true
            }

            // docs/12 §13: nothing on the still-watching card is focusable. Select always keeps
            // watching; Left/Right are swallowed so OSD focus can't move underneath the card. Back
            // is handled separately via BackAction.STOP_STILL_WATCHING.
            if (current.stillWatching != null) {
                when (event.key) {
                    Key.DirectionLeft, Key.DirectionRight -> return@keyHandler true
                    in SELECT_KEYS -> {
                        viewModel.stillWatchingContinue()
                        return@keyHandler true
                    }
                    else -> Unit
                }
            }

            // Moves focus [delta] away in [currentButtons], clamped with no
            // wrap. [focusedButton] stores the resulting identity, not a
            // position, so a later press against a changed list re-derives
            // fresh (see [PlaybackScreen]'s class doc).
            fun moveFocus(delta: Int) {
                val idx = focusedButton?.let { currentButtons.indexOf(it) }?.takeIf { it >= 0 } ?: 0
                focusedButton = currentButtons.getOrNull((idx + delta).coerceIn(0, currentButtons.lastIndex.coerceAtLeast(0)))
            }

            when (event.key) {
                Key.Menu -> {
                    // docs/15 §4: capture the invoker before clearing focusedButton -- Menu opens
                    // the picker from any focused button.
                    menuInvoker = focusedButton
                    focusedButton = null
                    openPickerAndFocusSelected()
                    true
                }
                in SELECT_KEYS -> {
                    val undo = skipUndo
                    val segment = activeSegment
                    if (undo != null) {
                        viewModel.undoSkip(undo.preSkipPositionTicks)
                        skipUndo = null
                        osd.onKeyEvent()
                        osdVisible = true
                    } else if (current.nextUp != null) {
                        viewModel.playNext()
                    } else if (segment != null) {
                        val before = viewModel.skipSegment(segment)
                        skipUndo = SkipUndoState(before, segment.segmentType)
                        activeSegment = null
                        osd.onKeyEvent()
                        osdVisible = true
                    } else {
                        val focused = focusedButton
                        if (focused != null) {
                            // docs/12 §8/§9: acts on `focused` directly, not
                            // re-derived from [currentButtons] -- a vanished
                            // target is already a safe no-op at the
                            // ViewModel layer. Branches opening a nested
                            // surface capture `focused` as [menuInvoker]
                            // first (docs/15-focus-and-selection.md §4).
                            when (focused) {
                                ControlButton.PREV_EPISODE -> viewModel.playPrevious()
                                ControlButton.SKIP_BACK -> doSeek(SeekDirection.BACK)
                                ControlButton.PLAY_PAUSE -> {
                                    val wasPlaying = current.isPlaying
                                    viewModel.togglePlayPause()
                                    flashTransport(if (wasPlaying) TransportGlyph.PAUSE else TransportGlyph.PLAY)
                                }
                                ControlButton.SKIP_FORWARD -> doSeek(SeekDirection.FORWARD)
                                ControlButton.NEXT_EPISODE -> viewModel.playNextEpisode()
                                ControlButton.SPEED -> {
                                    menuInvoker = focused
                                    viewModel.openSpeedMenu()
                                }
                                ControlButton.TRACKS -> {
                                    menuInvoker = focused
                                    openPickerAndFocusSelected()
                                    focusedButton = null
                                }
                                ControlButton.CHAPTERS -> {
                                    menuInvoker = focused
                                    viewModel.openChaptersMenu()
                                }
                                ControlButton.LIBRARY_INFO -> {
                                    menuInvoker = focused
                                    viewModel.openLibraryInfoOverlay()
                                }
                                ControlButton.STATS -> {
                                    menuInvoker = focused
                                    viewModel.openInfoOverlay()
                                }
                            }
                            osd.onKeyEvent()
                            osdVisible = true
                        }
                    }
                    true
                }
                Key.DirectionLeft -> {
                    osd.onKeyEvent()
                    osdVisible = true
                    moveFocus(-1)
                    true
                }
                Key.DirectionRight -> {
                    osd.onKeyEvent()
                    osdVisible = true
                    moveFocus(1)
                    true
                }
                Key.DirectionUp -> {
                    osd.onKeyEvent()
                    osdVisible = true
                    true
                }
                Key.DirectionDown -> {
                    osd.onKeyEvent()
                    osdVisible = true
                    if (focusedButton == null && current.nextUp == null && current.stillWatching == null) {
                        focusPlayPauseOrFirst()
                    }
                    true
                }
                else -> false
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent(handleKeyEvent),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    isFocusable = false
                    isFocusableInTouchMode = false
                    descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    setBackgroundColor(android.graphics.Color.BLACK)
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    AppGraph.playerHolder.attach(this)
                }
            },
            update = { playerView ->
                val subtitleView = playerView.subtitleView
                subtitleView?.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * state.subtitleScale)
                val backgroundAlpha = (state.subtitleBackgroundOpacity.coerceIn(0f, 1f) * 255).toInt()
                val hasBackground = backgroundAlpha > 0
                subtitleView?.setStyle(
                    CaptionStyleCompat(
                        /* foregroundColor = */ android.graphics.Color.WHITE,
                        /* backgroundColor = */ android.graphics.Color.argb(backgroundAlpha, 0, 0, 0),
                        /* windowColor = */ android.graphics.Color.TRANSPARENT,
                        /* edgeType = */ if (hasBackground) CaptionStyleCompat.EDGE_TYPE_NONE else CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                        /* edgeColor = */ android.graphics.Color.BLACK,
                        /* typeface = */ if (state.subtitleBold) Typeface.DEFAULT_BOLD else null,
                    ),
                )
                // docs/12 §16: SubtitleView lays out cues within its own measured bounds, ignoring
                // the sheet drawn over it. Shrinking the view's right margin by the sheet width
                // reflows cues into the remaining left column; reset to 0 when no sheet is open.
                val sheetOpen = state.statsSheetLive != null || state.libraryInfoOverlay != null
                val sheetMarginPx = if (sheetOpen) with(density) { Osd.SHEET_WIDTH.roundToPx() } else 0
                subtitleView?.let { view ->
                    (view.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let { params ->
                        if (params.rightMargin != sheetMarginPx) {
                            params.rightMargin = sheetMarginPx
                            view.layoutParams = params
                        }
                    }
                }
                val osdFraction = if (osdVisible) SUBTITLE_BOTTOM_FRACTION_OSD else SUBTITLE_BOTTOM_FRACTION_DEFAULT
                val positionFraction = subtitlePositionBottomFraction(state.subtitlePosition)
                subtitleView?.setBottomPaddingFraction(maxOf(osdFraction, positionFraction))
            },
            onRelease = { playerView -> AppGraph.playerHolder.detach(playerView) },
        )

        // docs/17-mini-player.md §2/§3: everything below the player surface
        // draws only outside PiP -- while `inPictureInPicture` is true, the
        // window shows bare video.
        if (!inPictureInPicture) {
        centerFlash?.let { content ->
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(CENTER_FLASH_DIAMETER)
                    .graphicsLayer { alpha = flashAlpha.value }
                    .clip(CircleShape)
                    .background(JellybeamTheme.Notte.copy(alpha = 0xaa / 255f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (content.icon != null) {
                        TransportIcon(glyph = content.icon, tint = JellybeamTheme.Panna, size = CENTER_FLASH_ICON_SIZE)
                    } else if (content.text != null) {
                        BasicText(
                            text = content.text,
                            style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Panna, fontSize = 48.sp),
                        )
                    }
                    content.numeral?.let {
                        BasicText(
                            text = it,
                            style = TextStyle(fontFamily = JellybeamTheme.MartianMono, color = JellybeamTheme.Panna, fontSize = 22.sp),
                        )
                    }
                }
            }
        }

        if (osdVisible && state.phase == PlaybackUiState.Phase.READY) {
            OsdBottomScrim(osdDetail = state.osdDetail, modifier = Modifier.align(Alignment.BottomStart))

            trickplayPreviewState?.let { preview ->
                viewModel.trickplayMeta?.let { meta ->
                    BoxWithConstraints(modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(bottom = controlZoneHeight(state.osdDetail) + 16.dp)) {
                        val panelWidth = TrickplayTransport.previewWidthDp(state.seekPreviewSize)
                        val durationMs = state.durationTicks?.let { PlaybackTicks.ticksToMs(it) }
                        val offsetX = if (durationMs != null && durationMs > 0L) {
                            val fraction = (preview.targetPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                            val raw = maxWidth * fraction - panelWidth / 2f
                            raw.coerceIn(0.dp, (maxWidth - panelWidth).coerceAtLeast(0.dp))
                        } else {
                            OSD_MARGIN
                        }
                        TrickplaySeekPreviewPanel(
                            meta = meta,
                            tileBitmap = previewTile,
                            widthDp = panelWidth,
                            targetPositionMs = preview.targetPositionMs,
                            chapterName = Chapters.nameAt(state.chapters, PlaybackTicks.msToTicks(preview.targetPositionMs)),
                            modifier = Modifier.offset(x = offsetX),
                        )
                    }
                }
            }

            OsdBlock(
                state = state,
                directPlayDetail = state.playbackStatsDetail,
                visibleButtons = visibleButtons,
                focusedButton = focusedButton,
                positionTicks = viewModel.positionTicks,
                bufferedPositionTicks = viewModel.bufferedPositionTicks,
                onButtonCentersMeasured = { buttonCentersPx.value = it },
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
            )

            if (state.speedMenuOpen) {
                val anchorX = buttonCentersPx.value[ControlButton.SPEED] ?: 0f
                OsdAnchoredMenu(
                    anchorXPx = anchorX,
                    bottomPx = with(density) { (controlZoneHeight(state.osdDetail)).toPx() },
                    modifier = Modifier.align(Alignment.TopStart),
                ) {
                    SPEED_OPTIONS.forEachIndexed { index, rate ->
                        OsdMenuRow(
                            label = formatSpeedLabel(rate),
                            leading = if (rate == state.playbackRate) OsdRowLeading.CHECK else OsdRowLeading.NONE,
                            focused = index == speedMenuFocusIndex,
                        )
                    }
                }
            }

            if (state.chaptersMenuOpen) {
                val anchorX = buttonCentersPx.value[ControlButton.CHAPTERS] ?: 0f
                // The currently-playing chapter, not menu focus -- derived once per chapter-index
                // change, not per tick.
                val position by viewModel.positionTicks.collectAsState()
                val currentIndex by remember(state.chapters) {
                    derivedStateOf { Chapters.currentChapterIndex(state.chapters, position) }
                }
                OsdAnchoredMenu(
                    anchorXPx = anchorX,
                    bottomPx = with(density) { (controlZoneHeight(state.osdDetail)).toPx() },
                    modifier = Modifier.align(Alignment.TopStart),
                ) {
                    state.chapters.forEachIndexed { index, chapter ->
                        OsdMenuRow(
                            label = chapter.name?.takeIf { it.isNotBlank() } ?: "Chapter ${index + 1}",
                            trailing = PlaybackTimeFormat.format(PlaybackTicks.ticksToMs(chapter.startPositionTicks)),
                            trailingMuted = true,
                            leading = if (index == currentIndex) OsdRowLeading.CURRENT_DOT else OsdRowLeading.NONE,
                            focused = index == chaptersMenuFocusIndex,
                        )
                    }
                }
            }

            state.statsSheetLive?.let { live ->
                val detail = state.playbackStatsDetail
                if (detail != null) {
                    val subtitleSelection = remember(detail, state.nonDefaultTrackActive) {
                        viewModel.currentSubtitleSelection()
                    }
                    val content = remember(detail, subtitleSelection, state.statsServerName, state.playMethod, state.transcodeReason) {
                        StatsSheetFormat.build(
                            detail,
                            subtitleSelection.first,
                            subtitleSelection.second,
                            state.statsServerName,
                            live = null,
                            playMethod = state.playMethod,
                            transcodeReason = state.transcodeReason,
                        )
                    }
                    OsdSheetContainer(kicker = "PLAYBACK STATS", scrollState = sheetScrollState, modifier = Modifier.align(Alignment.TopEnd)) {
                        StatsSpansText(
                            spans = content.headline,
                            style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Panna2, fontSize = 15.sp),
                            accentColor = JellybeamTheme.Panna,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        content.rows.forEach { row ->
                            StatsGridRow(label = row.label, value = row.value)
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                        StatsSheetFormat.liveRows(live).forEach { row ->
                            StatsGridRow(label = row.label, value = row.value)
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                        content.fileName?.let { fileName ->
                            Spacer(modifier = Modifier.height(14.dp))
                            BasicText(
                                text = "FILE $fileName",
                                style = TextStyle(
                                    fontFamily = JellybeamTheme.MartianMono,
                                    color = Color(0xFFA69C8E),
                                    fontSize = 10.5.sp,
                                ),
                            )
                        }
                    }
                }
            }

            state.libraryInfoOverlay?.let { overlay ->
                OsdSheetContainer(kicker = "IN YOUR LIBRARY", scrollState = sheetScrollState, modifier = Modifier.align(Alignment.TopEnd)) {
                    when (overlay) {
                        LibraryInfoOverlayState.Loading -> BasicText(
                            text = "Loading…",
                            style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Panna, fontSize = 15.sp),
                        )
                        LibraryInfoOverlayState.Unavailable -> BasicText(
                            text = "Library info unavailable",
                            style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Panna, fontSize = 15.sp),
                        )
                        is LibraryInfoOverlayState.Content -> LibrarySheetBody(overlay.value)
                    }
                }
            }
        }

        // docs/12 §9: renders only while the OSD is hidden --
        // a glide never coexists with OSD chrome (see
        // `LaunchedEffect(osdVisible)` above).
        if (!osdVisible && state.phase == PlaybackUiState.Phase.READY) {
            GlideSurfaceHost(
                surfaceState = glideSurfaceState,
                state = state,
                positionTicks = viewModel.positionTicks,
                meta = viewModel.trickplayMeta,
                tileBitmap = previewTile,
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }

        if (state.phase == PlaybackUiState.Phase.READY) {
            EndOfEpisodeOverlay(
                nextUp = state.nextUp,
                stillWatching = state.stillWatching,
                osdLift = if (osdVisible) controlZoneHeight(state.osdDetail) else 0.dp,
                livePositionTicks = viewModel::livePositionTicks,
                onCountdownElapsed = viewModel::nextUpCountdownElapsed,
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (state.phase == PlaybackUiState.Phase.READY) {
            state.trackPicker?.let { picker ->
                TrackPickerPanel(
                    picker = picker,
                    focusedIndex = pickerFocusIndex,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(OSD_MARGIN),
                )
            }
        }

        if (state.phase == PlaybackUiState.Phase.READY) {
            activeSegment?.let { segment ->
                if (state.nextUp == null && state.stillWatching == null) {
                    SkipPill(
                        label = SkipSegment.pillLabel(segment.segmentType),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = OSD_MARGIN, bottom = controlZoneHeight(state.osdDetail) + 16.dp),
                    )
                }
            }
            skipUndo?.let { undo ->
                SkipUndoToast(
                    label = SkipSegment.toastLabel(undo.segmentType),
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        }

        if (state.phase == PlaybackUiState.Phase.READY) {
            state.bufferingInfo?.let { info ->
                BufferingPill(info = info, modifier = Modifier.align(Alignment.TopEnd).padding(OSD_MARGIN))
            }
        }

        if (state.phase == PlaybackUiState.Phase.READY) {
            state.reconnecting?.let { reconnecting ->
                ReconnectingPill(info = reconnecting, modifier = Modifier.align(Alignment.TopEnd).padding(OSD_MARGIN))
            }
        }
        }
    }
}

/** docs/12 §12's fixed rate ladder, shared by the speed menu rows and the pill's CURRENT lookup. */
private val SPEED_OPTIONS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

// -- OSD block: scrim, title, time+scrubber, button row (docs/12 §2-§8) ---

/**
 * docs/12 §2's bottom scrim. Gradient stops convert the spec's "percent from bottom" convention
 * (CSS `linear-gradient(0deg,...)`) into [Brush.verticalGradient]'s "0=top,1=bottom" fractions: a
 * stop at `p` from bottom becomes `1-p` from top.
 */
@Composable
private fun OsdBottomScrim(osdDetail: OsdDetailSetting, modifier: Modifier = Modifier) {
    val height = if (osdDetail == OsdDetailSetting.FULL) Osd.SCRIM_HEIGHT_FULL else Osd.SCRIM_HEIGHT_MINIMAL
    val brush = remember(osdDetail) {
        val stops = if (osdDetail == OsdDetailSetting.FULL) {
            arrayOf(
                0.00f to OsdColor.ScrimBase.copy(alpha = 0f),
                0.34f to OsdColor.ScrimBase.copy(alpha = 0.56f),
                0.64f to OsdColor.ScrimBase.copy(alpha = 0.83f),
                1.00f to OsdColor.ScrimBase.copy(alpha = 0.90f),
            )
        } else {
            arrayOf(
                0.00f to OsdColor.ScrimBase.copy(alpha = 0f),
                0.32f to OsdColor.ScrimBase.copy(alpha = 0.50f),
                0.62f to OsdColor.ScrimBase.copy(alpha = 0.80f),
                1.00f to OsdColor.ScrimBase.copy(alpha = 0.88f),
            )
        }
        Brush.verticalGradient(colorStops = stops)
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(brush),
    )
}

/** Title line(s), time+scrubber row, button row -- bottom-anchored at [Osd.BLOCK_BOTTOM] with
 * [Osd.GAP_FULL]/[Osd.GAP_MINIMAL] between them (§2).
 */
@Composable
private fun OsdBlock(
    state: PlaybackUiState,
    directPlayDetail: PlaybackOsdDetail?,
    visibleButtons: List<ControlButton>,
    focusedButton: ControlButton?,
    positionTicks: StateFlow<Long>,
    bufferedPositionTicks: StateFlow<Long>,
    onButtonCentersMeasured: (Map<ControlButton, Float>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gap = if (state.osdDetail == OsdDetailSetting.FULL) Osd.GAP_FULL else Osd.GAP_MINIMAL

    Column(
        modifier = modifier
            .padding(horizontal = Osd.SAFE_INSET)
            .padding(bottom = Osd.BLOCK_BOTTOM),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        OsdTitleLine(state = state, directPlayDetail = directPlayDetail)
        LiveOsdTimeAndScrubber(
            state = state,
            positionTicks = positionTicks,
            bufferedPositionTicks = bufferedPositionTicks,
        )
        OsdButtonRow(
            state = state,
            visibleButtons = visibleButtons,
            focusedButton = focusedButton,
            onButtonCentersMeasured = onButtonCentersMeasured,
        )
    }
}

/** Keeps the 1Hz position/buffer subscriptions scoped to the time row, so the title/Direct Play
 * line/button row don't recompose on every tick.
 */
@Composable
private fun LiveOsdTimeAndScrubber(
    state: PlaybackUiState,
    positionTicks: StateFlow<Long>,
    bufferedPositionTicks: StateFlow<Long>,
) {
    val position by positionTicks.collectAsState()
    val buffered by bufferedPositionTicks.collectAsState()
    OsdTimeAndScrubber(
        state = state,
        positionTicks = position,
        bufferedTicks = buffered,
        durationTicks = state.durationTicks?.takeIf { it > 0L },
    )
}

/**
 * docs/12 §4's title line(s) + §5's Direct Play line. Item/series names render verbatim per
 * CLAUDE.md's naming rule. A movie's release year has no source at OSD-render time --
 * [PlaybackOsdDetail] carries no `productionYear`, and the library sheet's full fetch is
 * deliberately deferred until opened -- so the title renders alone, no year segment.
 */
@Composable
private fun OsdTitleLine(state: PlaybackUiState, directPlayDetail: PlaybackOsdDetail?, modifier: Modifier = Modifier) {
    val isEpisode = state.itemType == "Episode"
    val title = OsdTitleFormat.displayTitle(state.itemName)
    val seasonEpisode = if (isEpisode) CardFormatting.seasonEpisodeLabel(state.parentIndexNumber, state.indexNumber) else null

    if (state.osdDetail == OsdDetailSetting.FULL) {
        Column(modifier = modifier) {
            val kicker = if (isEpisode) {
                listOfNotNull(state.seriesName?.let(OsdTitleFormat::displayTitle), seasonEpisode)
                    .joinToString(" · ")
                    .takeIf { it.isNotBlank() }
            } else {
                null
            }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (kicker != null) {
                    BasicText(
                        text = kicker,
                        modifier = Modifier.widthIn(max = Osd.TITLE_MAX_WIDTH),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = JellybeamTheme.MartianMono, color = JellybeamTheme.Pistacchio, fontSize = 11.sp),
                    )
                }
                // A plain weighted Spacer (not weight() on the kicker text, which would only
                // shrink-wrap it) is what pushes the Direct Play line flush right in both cases.
                Spacer(modifier = Modifier.width(12.dp))
                Spacer(modifier = Modifier.weight(1f))
                DirectPlayLine(playMethod = state.playMethod, detail = directPlayDetail)
            }
            if (kicker != null) Spacer(modifier = Modifier.height(Osd.TITLE_KICKER_GAP))
            BasicText(
                text = title,
                modifier = Modifier.widthIn(max = Osd.TITLE_MAX_WIDTH),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = JellybeamTheme.Archivo, fontWeight = FontWeight.Medium, color = JellybeamTheme.Panna, fontSize = 20.sp),
            )
        }
    } else {
        Row(modifier = modifier, verticalAlignment = Alignment.Bottom) {
            BasicText(
                text = title,
                modifier = Modifier.weight(1f, fill = false),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = JellybeamTheme.Archivo, fontWeight = FontWeight.Medium, color = JellybeamTheme.Panna, fontSize = 20.sp),
            )
            if (seasonEpisode != null) {
                Spacer(modifier = Modifier.width(Osd.TITLE_MINIMAL_GAP))
                BasicText(
                    text = seasonEpisode,
                    // docs/12 §0: no color in the OSD band goes darker than Panna2 -- Grigio
                    // ("muted") is sheet-interior-only.
                    style = TextStyle(fontFamily = JellybeamTheme.MartianMono, color = JellybeamTheme.Panna2, fontSize = 11.sp),
                )
            }
        }
    }
}

/**
 * docs/12 §5: one plain mono line -- `"DIRECT PLAY · 1080p · HEVC · AAC 5.1"` -- Full only (caller
 * gates that). Per docs/18 §1/§3, once transcoding, [playMethod] flips the leading word to
 * `TRANSCODE` in [JellybeamTheme.Ambra]; the codec summary is unchanged.
 */
@Composable
private fun DirectPlayLine(playMethod: PlayMethodFfi, detail: PlaybackOsdDetail?, modifier: Modifier = Modifier) {
    val transcoding = playMethod == PlayMethodFfi.TRANSCODE
    val text = buildString {
        append(if (transcoding) "TRANSCODE" else "DIRECT PLAY")
        directPlayCodecSummary(detail)?.let { summary -> append(" · "); append(summary) }
    }
    BasicText(
        text = text,
        modifier = modifier,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        style = TextStyle(
            fontFamily = JellybeamTheme.MartianMono,
            color = if (transcoding) JellybeamTheme.Ambra else JellybeamTheme.Panna2,
            fontSize = 11.sp,
        ),
    )
}

/** docs/12 §6: one row -- elapsed, scrubber, remaining/ENDS -- with fixed-width side columns so
 * the bar's edges stay stationary as digits change width. */
@Composable
private fun OsdTimeAndScrubber(
    state: PlaybackUiState,
    positionTicks: Long,
    bufferedTicks: Long,
    durationTicks: Long?,
    modifier: Modifier = Modifier,
) {
    val isFull = state.osdDetail == OsdDetailSetting.FULL
    val positionMs = PlaybackTicks.ticksToMs(positionTicks)
    val durationMs = durationTicks?.let(PlaybackTicks::ticksToMs)
    val leftColumn = if (isFull) Osd.TIME_COL_FULL_LEFT else Osd.TIME_COL_MINIMAL
    val rightColumn = if (isFull) Osd.TIME_COL_FULL_RIGHT else Osd.TIME_COL_MINIMAL

    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.width(leftColumn)) {
            if (isFull && durationMs != null) {
                BasicText(
                    text = buildAnnotatedString {
                        append(PlaybackTimeFormat.format(positionMs))
                        // docs/12 §0: Panna2 is the OSD band's floor -- Grigio ("muted") is
                        // sheet-interior-only.
                        withStyle(SpanStyle(color = JellybeamTheme.Panna2)) { append(" / " + PlaybackTimeFormat.format(durationMs)) }
                    },
                    style = TextStyle(fontFamily = JellybeamTheme.MartianMono, color = JellybeamTheme.Panna, fontSize = 13.sp),
                )
            } else {
                BasicText(
                    text = PlaybackTimeFormat.format(positionMs),
                    style = TextStyle(fontFamily = JellybeamTheme.MartianMono, color = JellybeamTheme.Panna, fontSize = 13.sp),
                )
            }
        }
        Spacer(modifier = Modifier.width(Osd.TIME_ROW_GAP))
        OsdScrubber(
            playedFraction = if (durationMs != null && durationMs > 0L) (positionMs.toDouble() / durationMs.toDouble()).coerceIn(0.0, 1.0).toFloat() else 0f,
            bufferedFraction = if (durationMs != null && durationMs > 0L) (PlaybackTicks.ticksToMs(bufferedTicks).toDouble() / durationMs.toDouble()).coerceIn(0.0, 1.0).toFloat() else 0f,
            isFull = isFull,
            indeterminate = durationMs == null,
            chapterFractions = if (isFull) remember(state.chapters, durationTicks) { Chapters.tickFractions(state.chapters, durationTicks) } else emptyList(),
            modifier = Modifier.weight(1f),
        )
        if (durationMs != null) {
            Spacer(modifier = Modifier.width(Osd.TIME_ROW_GAP))
            // Min- not fixed-width (deviates from docs/12 §6's 160dp): the
            // longest readouts need more than 160dp and would wrap. Growth
            // eats scrubber length rather than shifting the readouts, and is
            // stable within one title. contentAlignment right-aligns within
            // the min width -- fillMaxWidth here would starve the weighted
            // scrubber to zero.
            Box(modifier = Modifier.widthIn(min = rightColumn), contentAlignment = Alignment.CenterEnd) {
                if (isFull) {
                    val context = LocalContext.current
                    val ends = remember(positionMs, durationMs, state.playbackRate) {
                        val remainingMs = (durationMs - positionMs).coerceAtLeast(0L)
                        EndsClock.endsAtMillis(System.currentTimeMillis(), remainingMs, state.playbackRate)
                    }
                    val is24h = remember(context) { DateFormat.is24HourFormat(context) }
                    val endsLabel = EndsClock.formatWallClock(ends, is24h, java.time.ZoneId.systemDefault())
                    // docs/12 §0: whole right cell is Panna2 -- both readouts are secondary, unlike
                    // the left column's Panna elapsed digit.
                    BasicText(
                        text = "${PlaybackTimeFormat.formatRemaining(positionMs, durationMs)} · ENDS $endsLabel",
                        maxLines = 1,
                        softWrap = false,
                        style = TextStyle(fontFamily = JellybeamTheme.MartianMono, color = JellybeamTheme.Panna2, fontSize = 13.sp),
                    )
                } else {
                    BasicText(
                        text = PlaybackTimeFormat.formatRemaining(positionMs, durationMs),
                        maxLines = 1,
                        softWrap = false,
                        style = TextStyle(fontFamily = JellybeamTheme.MartianMono, color = JellybeamTheme.Panna2, fontSize = 13.sp),
                    )
                }
            }
        }
    }
}

/**
 * docs/12 §7's scrubber track. The played fill is the only position indicator -- no buffered
 * fill, no thumb. Full also shows chapter ticks; Minimal omits them. Unknown duration renders an
 * empty static groove. [bufferedFraction] is unused -- still threaded down from
 * [PlaybackViewModel.bufferedPositionTicks], but no longer drawn.
 */
@Composable
private fun OsdScrubber(
    playedFraction: Float,
    bufferedFraction: Float,
    isFull: Boolean,
    indeterminate: Boolean,
    chapterFractions: List<Float>,
    modifier: Modifier = Modifier,
) {
    val trackHeight = if (isFull) Osd.TRACK_HEIGHT_FULL else Osd.TRACK_HEIGHT_MINIMAL
    val grooveColor = JellybeamTheme.Panna.copy(alpha = 0.14f)
    val tickColor = OsdColor.Surface.copy(alpha = 0.75f)

    Canvas(modifier = modifier.fillMaxWidth().height(Osd.TRACK_CANVAS_HEIGHT)) {
        val trackHeightPx = trackHeight.toPx()
        val centerY = size.height / 2f
        val cornerRadius = CornerRadius(trackHeightPx / 2f, trackHeightPx / 2f)
        val trackTop = centerY - trackHeightPx / 2f

        drawRoundRect(color = grooveColor, topLeft = Offset(0f, trackTop), size = Size(size.width, trackHeightPx), cornerRadius = cornerRadius)

        if (indeterminate) return@Canvas

        val playedWidth = size.width * playedFraction
        if (playedWidth > 0f) {
            drawRoundRect(color = JellybeamTheme.Pistacchio, topLeft = Offset(0f, trackTop), size = Size(playedWidth, trackHeightPx), cornerRadius = cornerRadius)
        }

        if (isFull) {
            val tickWidthPx = Osd.CHAPTER_TICK_WIDTH.toPx()
            chapterFractions.forEach { fraction ->
                val x = (size.width * fraction).coerceIn(0f, size.width)
                drawRect(color = tickColor, topLeft = Offset(x - tickWidthPx / 2f, trackTop), size = Size(tickWidthPx, trackHeightPx))
            }
        }
    }
}

/**
 * docs/12 §8's button row: transport cluster, flexible spacer, secondary cluster -- 44dp invisible
 * touch/focus targets (docs/12 §0; glyph itself is 22dp), [Osd.BUTTON_GAP] within a cluster plus
 * [Osd.BUTTON_BREAK_EXTRA] around the episode-skip buttons.
 * [onButtonCentersMeasured] reports each button's root-space x-center per layout pass, feeding the
 * speed/chapters menu anchor.
 */
@Composable
private fun OsdButtonRow(
    state: PlaybackUiState,
    visibleButtons: List<ControlButton>,
    focusedButton: ControlButton?,
    onButtonCentersMeasured: (Map<ControlButton, Float>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val centers = remember(visibleButtons) { mutableMapOf<ControlButton, Float>() }
    val clusters = remember(visibleButtons) {
        val transport = visibleButtons.filter {
            it == ControlButton.PREV_EPISODE || it == ControlButton.SKIP_BACK || it == ControlButton.PLAY_PAUSE ||
                it == ControlButton.SKIP_FORWARD || it == ControlButton.NEXT_EPISODE
        }
        transport to (visibleButtons - transport.toSet())
    }
    val transport = clusters.first
    val secondary = clusters.second

    fun report(button: ControlButton, centerX: Float) {
        if (centers[button] == centerX) return
        centers[button] = centerX
        if (visibleButtons.all(centers::containsKey)) {
            onButtonCentersMeasured(centers.toMap())
        }
    }

    Box(modifier = modifier.fillMaxWidth().height(Osd.BUTTON_TARGET)) {
        Row(modifier = Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
            transport.forEachIndexed { i, button ->
                if (i > 0) {
                    val extraBreak = button == ControlButton.PLAY_PAUSE && transport.getOrNull(i - 1) == ControlButton.PREV_EPISODE ||
                        button == ControlButton.NEXT_EPISODE
                    Spacer(modifier = Modifier.width(Osd.BUTTON_GAP + if (extraBreak) Osd.BUTTON_BREAK_EXTRA else 0.dp))
                }
                OsdControlButton(
                    button = button,
                    state = state,
                    focused = button == focusedButton,
                    onCenterMeasured = { report(button, it) },
                )
            }
        }
        Row(modifier = Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
            secondary.forEachIndexed { i, button ->
                if (i > 0) Spacer(modifier = Modifier.width(Osd.BUTTON_GAP))
                OsdControlButton(
                    button = button,
                    state = state,
                    focused = button == focusedButton,
                    onCenterMeasured = { report(button, it) },
                )
            }
        }
    }
}

/**
 * One docs/12 §8 button (docs/12 §0: flat -- no circle/fill/ring/shadow). Rest is a
 * plain [JellybeamTheme.Panna] glyph; focused retints it
 * [JellybeamTheme.Pistacchio]. The 44dp [Box] only serves as the invisible
 * touch/focus target and [onCenterMeasured] anchor.
 */
@Composable
private fun OsdControlButton(
    button: ControlButton,
    state: PlaybackUiState,
    focused: Boolean,
    onCenterMeasured: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = if (focused) JellybeamTheme.Pistacchio else JellybeamTheme.Panna

    Box(
        modifier = modifier
            .size(Osd.BUTTON_TARGET)
            .onGloballyPositioned { coords -> onCenterMeasured(coords.positionInRoot().x + coords.size.width / 2f) },
        contentAlignment = Alignment.Center,
    ) {
        when (button) {
            ControlButton.PREV_EPISODE -> EpisodeGlyph(forward = false, tint = tint)
            ControlButton.SKIP_BACK -> SeekGlyph(seconds = state.skipBackMs / 1000L, back = true, tint = tint)
            ControlButton.PLAY_PAUSE -> TransportIcon(glyph = if (state.isPlaying) TransportGlyph.PAUSE else TransportGlyph.PLAY, tint = tint, size = Osd.BUTTON_GLYPH)
            ControlButton.SKIP_FORWARD -> SeekGlyph(seconds = state.skipForwardMs / 1000L, back = false, tint = tint)
            ControlButton.NEXT_EPISODE -> EpisodeGlyph(forward = true, tint = tint)
            ControlButton.SPEED -> SpeedButtonLabel(rate = state.playbackRate, focusedTint = tint)
            ControlButton.TRACKS -> SimpleGlyphIcon(glyph = SimpleGlyph.TRACKS, tint = tint)
            ControlButton.CHAPTERS -> SimpleGlyphIcon(glyph = SimpleGlyph.CHAPTERS, tint = tint)
            ControlButton.LIBRARY_INFO -> SimpleGlyphIcon(glyph = SimpleGlyph.LIBRARY_INFO, tint = tint)
            ControlButton.STATS -> SimpleGlyphIcon(glyph = SimpleGlyph.STATS, tint = tint)
        }
        if (button == ControlButton.TRACKS && state.nonDefaultTrackActive) {
            // Hug the 22dp glyph's corner, not the 44dp target's -- the glyph is inset
            // (44-22)/2=11dp, so an un-inset dot floats off.
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-7).dp, y = 7.dp)
                    .size(Osd.ACTIVE_DOT)
                    .clip(CircleShape)
                    .background(JellybeamTheme.Pistacchio),
            )
        }
    }
}

// -- Shared sheet container (docs/12 §17) ----------------------------------

/**
 * docs/12 §17's shared sheet container. Right-anchored, top 0, fixed width, height = content up
 * to [Osd.SHEET_MAX_Y] with internal scroll past that. No blur, border, radius or shadow -- a
 * scrim, not a card, with a soft left edge fade over [Osd.SHEET_LEFT_FADE_WIDTH].
 */
@Composable
private fun OsdSheetContainer(
    kicker: String,
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val background = remember {
        val leftFadeFraction = (Osd.SHEET_LEFT_FADE_WIDTH.value / Osd.SHEET_WIDTH.value).coerceIn(0f, 1f)
        Brush.horizontalGradient(
            colorStops = arrayOf(
                0.00f to OsdColor.ScrimBase.copy(alpha = 0f),
                leftFadeFraction to OsdColor.ScrimBase.copy(alpha = 0.92f),
                1.00f to OsdColor.ScrimBase.copy(alpha = 0.92f),
            ),
        )
    }
    Box(
        modifier = modifier
            .width(Osd.SHEET_WIDTH)
            .heightIn(max = Osd.SHEET_MAX_Y)
            .background(background)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithCache {
                val fadeStart = ((size.height - Osd.SHEET_FADE_HEIGHT.toPx()) / size.height).coerceIn(0f, 1f)
                val fadeBrush = Brush.verticalGradient(colorStops = arrayOf(fadeStart to Color.Black, 1f to Color.Transparent))
                onDrawWithContent {
                    drawContent()
                    drawRect(brush = fadeBrush, blendMode = BlendMode.DstIn)
                }
            }
            .verticalScroll(scrollState)
            .padding(top = Osd.SHEET_PAD_TOP, end = Osd.SHEET_PAD_END, bottom = Osd.SHEET_PAD_BOTTOM, start = Osd.SHEET_PAD_START),
    ) {
        Column {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                // docs/12 §17: sheet kickers are muted, not accent -- the library rating figure is
                // the sheet's one accent.
                BasicText(text = kicker, style = TextStyle(fontFamily = JellybeamTheme.MartianMono, color = JellybeamTheme.Grigio, fontSize = 11.sp))
                BasicText(text = "BACK TO CLOSE", style = TextStyle(fontFamily = JellybeamTheme.MartianMono, color = JellybeamTheme.Grigio, fontSize = 10.sp))
            }
            Spacer(modifier = Modifier.height(12.dp))
            // docs/12 §17: divider at 6% alpha, exactly one per sheet.
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(JellybeamTheme.Panna.copy(alpha = 0.06f)))
            Spacer(modifier = Modifier.height(16.dp))
            content()
        }
    }
}

/**
 * Renders a flat [StatsSpan] run (docs/12 §17: accent spans, no separators) as one [BasicText].
 * [accentColor] defaults to [JellybeamTheme.Pistacchio] (the library rating figure, §17's one
 * accent); the stats sheet's headline overrides it to plain [JellybeamTheme.Panna] bold.
 */
@Composable
private fun StatsSpansText(
    spans: List<StatsSpan>,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Ellipsis,
    accentColor: Color = JellybeamTheme.Pistacchio,
) {
    val annotated = remember(spans, accentColor) {
        buildAnnotatedString {
            spans.forEach { span ->
                if (span.accent) {
                    withStyle(SpanStyle(color = accentColor, fontWeight = FontWeight.Bold)) { append(span.text) }
                } else {
                    append(span.text)
                }
            }
        }
    }
    BasicText(text = annotated, modifier = modifier, style = style, maxLines = maxLines, overflow = overflow)
}

/** docs/12 §17's grid row: label col 75dp, gap 11dp, mono 11sp muted / mono 12sp cream. */
@Composable
private fun StatsGridRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth()) {
        BasicText(
            text = label,
            modifier = Modifier.width(75.dp),
            style = TextStyle(fontFamily = JellybeamTheme.MartianMono, color = JellybeamTheme.Grigio, fontSize = 11.sp),
        )
        Spacer(modifier = Modifier.width(11.dp))
        BasicText(
            text = value,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = JellybeamTheme.MartianMono, color = JellybeamTheme.Panna, fontSize = 12.sp),
        )
    }
}

/** docs/12 §17's library sheet body: meta line, synopsis (max 4 lines), fields grid. */
@Composable
private fun LibrarySheetBody(content: LibrarySheetContent, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        if (content.metaSegments.isNotEmpty()) {
            // docs/12 §17: wrap to a second line rather than truncating -- genres are never
            // ellipsized.
            StatsSpansText(
                spans = content.metaSegments,
                style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Panna2, fontSize = 15.sp),
                maxLines = 2,
                overflow = TextOverflow.Clip,
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
        content.synopsis?.let {
            BasicText(
                text = it,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Panna, fontSize = 15.sp, lineHeight = 21.75.sp),
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
        content.fields.forEach { field ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.5.dp)) {
                BasicText(
                    text = field.label,
                    modifier = Modifier.width(95.dp),
                    style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Grigio, fontSize = 13.sp),
                )
                BasicText(
                    text = field.value,
                    modifier = Modifier.weight(1f),
                    style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Panna, fontSize = 14.sp),
                )
            }
        }
    }
}

// -- Speed & chapters menus (docs/12 §12) -------------------------------------------

/**
 * Shared container for the speed and chapters menus (docs/12 §12). Centered on
 * [anchorXPx], clamped to [Osd.SAFE_INSET] margins, bottom [bottomPx] above
 * the screen edge -- an approximation of "just above the button row".
 */
@Composable
private fun OsdAnchoredMenu(
    anchorXPx: Float,
    bottomPx: Float,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    var heightPx by remember { mutableStateOf(0f) }
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val screenWidthPx = with(density) { maxWidth.toPx() }
        val menuWidthPx = with(density) { Osd.MENU_WIDTH.toPx() }
        val insetPx = with(density) { Osd.SAFE_INSET.toPx() }
        val rawX = anchorXPx - menuWidthPx / 2f
        val x = rawX.coerceIn(insetPx, (screenWidthPx - insetPx - menuWidthPx).coerceAtLeast(insetPx))
        val y = maxHeight - with(density) { (bottomPx + heightPx + 8f).toDp() }
        Column(
            modifier = Modifier
                .offset { IntOffset(x.roundToInt(), with(density) { y.roundToPx() }) }
                .width(Osd.MENU_WIDTH)
                .onGloballyPositioned { heightPx = it.size.height.toFloat() }
                .background(OsdColor.Surface.copy(alpha = 0.92f), RoundedCornerShape(Osd.MENU_RADIUS))
                .border(1.dp, JellybeamTheme.Panna.copy(alpha = 0.12f), RoundedCornerShape(Osd.MENU_RADIUS))
                // Clips each row's focused-fill to the container's rounded corners, else a
                // first/last focused row overflows the radius.
                .clip(RoundedCornerShape(Osd.MENU_RADIUS))
                .padding(vertical = Osd.MENU_V_PADDING),
            content = content,
        )
    }
}

/**
 * [OsdMenuRow]'s leading-gutter marker (docs/15-focus-and-selection.md
 * §1.2): [NONE] no marker; [CHECK] "current value" (speed menu's rate);
 * [CURRENT_DOT] "playing now" (chapters menu's current chapter).
 */
private enum class OsdRowLeading { NONE, CHECK, CURRENT_DOT }

/** The leading gutter's fixed width (docs/15-focus-and-selection.md §1.2: "always reserved so rows
 * never shift"), mirroring [TrackChoiceRow]'s own gutter.
 */
private val OSD_MENU_ROW_LEADING_WIDTH = 20.dp

/** One row shared by both menus (docs/12 §12): mono 14sp label, optional mono 10sp trailing
 * marker, focused = accent fill + on-accent label. */
@Composable
private fun OsdMenuRow(
    label: String,
    focused: Boolean,
    trailing: String? = null,
    trailingMuted: Boolean = false,
    leading: OsdRowLeading = OsdRowLeading.NONE,
    modifier: Modifier = Modifier,
) {
    val labelColor = if (focused) JellybeamTheme.Notte else JellybeamTheme.Panna
    val trailingColor = if (focused) JellybeamTheme.Notte else if (trailingMuted) JellybeamTheme.Grigio else JellybeamTheme.Pistacchio
    // Same contrast swap as [trailingColor] -- a focused row's solid fill would swallow a
    // Pistacchio marker.
    val leadingColor = if (focused) JellybeamTheme.Notte else JellybeamTheme.Pistacchio
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(if (focused) JellybeamTheme.Pistacchio else Color.Transparent)
            .padding(horizontal = 13.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(modifier = Modifier.weight(1f, fill = false), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.width(OSD_MENU_ROW_LEADING_WIDTH), contentAlignment = Alignment.Center) {
                when (leading) {
                    OsdRowLeading.CHECK -> BasicText(
                        text = "✓",
                        style = TextStyle(fontFamily = JellybeamTheme.MartianMono, color = leadingColor, fontSize = 14.sp),
                    )
                    OsdRowLeading.CURRENT_DOT -> Box(
                        modifier = Modifier.size(6.dp).background(leadingColor, CircleShape),
                    )
                    OsdRowLeading.NONE -> Unit
                }
            }
            BasicText(
                text = label,
                modifier = Modifier.weight(1f, fill = false),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = JellybeamTheme.MartianMono, color = labelColor, fontSize = 14.sp),
            )
        }
        trailing?.let {
            Spacer(modifier = Modifier.width(8.dp))
            BasicText(text = it, style = TextStyle(fontFamily = JellybeamTheme.MartianMono, color = trailingColor, fontSize = 10.sp))
        }
    }
}

// -- Hold-to-seek surface (docs/12 §9) --

/** docs/12 §9's target tick: `2dp wide x 10dp tall`, overhanging the 2dp track so it's findable
 * against any content. */
private val GLIDE_TARGET_TICK_WIDTH = 2.dp
private val GLIDE_TARGET_TICK_HEIGHT = 10.dp

/** Reads [surfaceState] so a per-tick write recomposes only this host, never [PlaybackScreen] --
 * it passes the [State] object down without reading `.value` itself. */
@Composable
private fun GlideSurfaceHost(
    surfaceState: State<GlideSurfaceState?>,
    state: PlaybackUiState,
    positionTicks: StateFlow<Long>,
    meta: TrickplayMetaFfi?,
    tileBitmap: ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    surfaceState.value?.let { surface ->
        GlideSurface(
            surface = surface,
            state = state,
            positionTicks = positionTicks,
            meta = meta,
            tileBitmap = tileBitmap,
            modifier = modifier,
        )
    }
}

/**
 * The glide traversal bar + tile/chip (docs/12 §9), rendered only while [GlideSurfaceHost]'s
 * surface state is non-null. Reuses [TrickplaySeekPreviewPanel]; a null `tile` renders the chip
 * alone, clamped to the safe inset via a measure-then-clamp [Modifier.layout].
 */
@Composable
private fun GlideSurface(
    surface: GlideSurfaceState,
    state: PlaybackUiState,
    positionTicks: StateFlow<Long>,
    meta: TrickplayMetaFfi?,
    tileBitmap: ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    val position by positionTicks.collectAsState()
    val positionMs = PlaybackTicks.ticksToMs(position)
    val durationMs = state.durationTicks?.let { PlaybackTicks.ticksToMs(it) } ?: 0L
    val fractions = remember(positionMs, surface.targetMs, durationMs) {
        GlideBarGeometry.fractions(positionMs, surface.targetMs, durationMs)
    }
    val chapterFractions = remember(state.chapters, state.durationTicks) {
        Chapters.tickFractions(state.chapters, state.durationTicks)
    }
    val chip = remember(surface.targetMs, surface.deltaMs, surface.clamp, surface.multiplier) {
        GlideChipFormat.line(surface.targetMs, surface.deltaMs, surface.clamp, surface.multiplier)
    }
    val chapterName = remember(state.chapters, surface.chapterIndex) {
        surface.chapterIndex?.let { state.chapters.getOrNull(it)?.name }
    }
    val targetFraction = fractions?.target ?: 0f

    Box(modifier = modifier.fillMaxWidth()) {
        if (fractions != null) {
            Canvas(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = Osd.SAFE_INSET)
                    .padding(bottom = controlZoneHeight(state.osdDetail))
                    .height(Osd.TRACK_CANVAS_HEIGHT),
            ) {
                val trackHeightPx = Osd.TRACK_HEIGHT_FULL.toPx()
                val centerY = size.height / 2f
                val cornerRadius = CornerRadius(trackHeightPx / 2f, trackHeightPx / 2f)
                val trackTop = centerY - trackHeightPx / 2f

                drawRoundRect(
                    color = JellybeamTheme.Panna.copy(alpha = 0.14f),
                    topLeft = Offset(0f, trackTop),
                    size = Size(size.width, trackHeightPx),
                    cornerRadius = cornerRadius,
                )

                val playedWidth = size.width * fractions.played
                if (playedWidth > 0f) {
                    drawRoundRect(
                        color = JellybeamTheme.Pistacchio,
                        topLeft = Offset(0f, trackTop),
                        size = Size(playedWidth, trackHeightPx),
                        cornerRadius = cornerRadius,
                    )
                }

                val bandStartX = size.width * fractions.bandStart
                val bandEndX = size.width * fractions.bandEnd
                if (bandEndX > bandStartX) {
                    drawRect(
                        color = JellybeamTheme.Panna.copy(alpha = 0.45f),
                        topLeft = Offset(bandStartX, trackTop),
                        size = Size(bandEndX - bandStartX, trackHeightPx),
                    )
                }

                val tickWidthPx = Osd.CHAPTER_TICK_WIDTH.toPx()
                chapterFractions.forEach { fraction ->
                    val x = (size.width * fraction).coerceIn(0f, size.width)
                    drawRect(
                        color = OsdColor.Surface.copy(alpha = 0.75f),
                        topLeft = Offset(x - tickWidthPx / 2f, trackTop),
                        size = Size(tickWidthPx, trackHeightPx),
                    )
                }

                val targetTickWidthPx = GLIDE_TARGET_TICK_WIDTH.toPx()
                val targetTickHeightPx = GLIDE_TARGET_TICK_HEIGHT.toPx()
                val targetX = (size.width * fractions.target).coerceIn(0f, size.width)
                drawRect(
                    color = JellybeamTheme.Panna,
                    topLeft = Offset(targetX - targetTickWidthPx / 2f, centerY - targetTickHeightPx / 2f),
                    size = Size(targetTickWidthPx, targetTickHeightPx),
                )
            }
        }

        BoxWithConstraints(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(bottom = controlZoneHeight(state.osdDetail) + 16.dp),
        ) {
            if (tileBitmap != null && meta != null) {
                val panelWidth = TrickplayTransport.previewWidthDp(state.seekPreviewSize)
                val raw = maxWidth * targetFraction - panelWidth / 2f
                val offsetX = raw.coerceIn(0.dp, (maxWidth - panelWidth).coerceAtLeast(0.dp))
                TrickplaySeekPreviewPanel(
                    meta = meta,
                    tileBitmap = tileBitmap,
                    widthDp = panelWidth,
                    targetPositionMs = surface.targetMs,
                    chapterName = chapterName,
                    chip = chip,
                    modifier = Modifier.offset(x = offsetX),
                )
            } else {
                TrickplaySeekPreviewPanel(
                    meta = null,
                    tileBitmap = null,
                    widthDp = 0.dp,
                    targetPositionMs = surface.targetMs,
                    chapterName = chapterName,
                    chip = chip,
                    modifier = Modifier.layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        val marginPx = OSD_MARGIN.roundToPx()
                        val targetXPx = constraints.maxWidth * targetFraction
                        val rawX = targetXPx - placeable.width / 2f
                        val maxX = (constraints.maxWidth - marginPx - placeable.width).coerceAtLeast(marginPx).toFloat()
                        val clampedX = rawX.coerceIn(marginPx.toFloat(), maxX)
                        layout(placeable.width, placeable.height) {
                            placeable.placeRelative(clampedX.roundToInt(), 0)
                        }
                    },
                )
            }
        }
    }
}

// -- Trickplay scrub-preview panel (docs/12 §9 no-tile override) --

private val TRICKPLAY_PREVIEW_BORDER = 2.dp
private val TRICKPLAY_PREVIEW_RADIUS = 6.dp
private val TRICKPLAY_CHIP_RADIUS = 4.dp

/**
 * Shared by the on-screen seek preview (docs/12 §11) and the glide surface's tile+chip (§9).
 * `tile`/`meta` null renders the chip alone; `chip` null keeps the plain time-only line, non-null
 * shows the glide chip's time/delta/speed row.
 */
@Composable
private fun TrickplaySeekPreviewPanel(
    meta: TrickplayMetaFfi?,
    tileBitmap: ImageBitmap?,
    widthDp: Dp,
    targetPositionMs: Long,
    chapterName: String? = null,
    chip: GlideChipLine? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (tileBitmap != null && meta != null && meta.width > 0u) {
            // docs/12 §11: width from the Seek preview setting, height from the manifest's aspect.
            val heightDp = widthDp * (meta.height.toFloat() / meta.width.toFloat())
            Image(
                bitmap = tileBitmap,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .size(widthDp, heightDp)
                    .border(TRICKPLAY_PREVIEW_BORDER, JellybeamTheme.Panna, RoundedCornerShape(TRICKPLAY_PREVIEW_RADIUS)),
            )
            Spacer(modifier = Modifier.height(6.dp))
        }
        Column(
            modifier = Modifier
                .background(JellybeamTheme.Notte.copy(alpha = 0xcc / 255f), RoundedCornerShape(TRICKPLAY_CHIP_RADIUS))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (chip != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicText(
                        text = chip.time,
                        style = TextStyle(fontFamily = JellybeamTheme.MartianMono, color = JellybeamTheme.Panna, fontSize = 11.sp),
                    )
                    BasicText(
                        text = " · ${chip.delta}",
                        style = TextStyle(fontFamily = JellybeamTheme.MartianMono, color = JellybeamTheme.Panna2, fontSize = 11.sp),
                    )
                    chip.speed?.let { speed ->
                        Spacer(modifier = Modifier.width(12.dp))
                        BasicText(
                            text = speed,
                            style = TextStyle(fontFamily = JellybeamTheme.MartianMono, color = JellybeamTheme.Panna2, fontSize = 11.sp),
                        )
                    }
                }
            } else {
                BasicText(
                    text = PlaybackTimeFormat.format(targetPositionMs),
                    style = TextStyle(fontFamily = JellybeamTheme.MartianMono, color = JellybeamTheme.Panna, fontSize = 11.sp),
                )
            }
            chapterName?.let {
                BasicText(
                    text = it,
                    style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Grigio, fontSize = 11.sp),
                )
            }
        }
    }
}

// -- Next-up / still-watching cards (docs/12 §13, design 1c: no container) -----

private val END_OF_EPISODE_CARD_WIDTH = 360.dp
private val END_OF_EPISODE_THUMB_WIDTH = 128.dp
private val END_OF_EPISODE_THUMB_HEIGHT = 72.dp
private val END_OF_EPISODE_THUMB_RADIUS = 2.dp
private val END_OF_EPISODE_RULE_HEIGHT = 2.dp
private val END_OF_EPISODE_SCRIM_WIDTH = 550.dp
private val END_OF_EPISODE_SCRIM_HEIGHT = 310.dp
private val END_OF_EPISODE_SCRIM_EDGE_FADE = 64.dp
private val END_OF_EPISODE_CARD_BOTTOM = 96.dp
private val SQRT2 = sqrt(2f)

/** Renders whichever of [nextUp]/[stillWatching] is non-null (Rust guarantees never both) plus
 * the shared corner scrim beneath it; a no-op with neither. docs/12 §13: the card sits 96dp above
 * the frame's bottom and rides up by [osdLift] (the control zone) while the OSD shows, hard-cut
 * like the OSD itself; the scrim rides with it so the card never leaves its cover.
 */
@Composable
private fun EndOfEpisodeOverlay(
    nextUp: NextUpState?,
    stillWatching: StillWatchingState?,
    osdLift: Dp,
    livePositionTicks: () -> Long,
    onCountdownElapsed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (nextUp == null && stillWatching == null) return
    Box(modifier = modifier) {
        EndOfEpisodeScrim(modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = osdLift))
        val cardModifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(end = OSD_MARGIN, bottom = osdLift + END_OF_EPISODE_CARD_BOTTOM)
        nextUp?.let {
            NextUpCard(nextUp = it, livePositionTicks = livePositionTicks, onCountdownElapsed = onCountdownElapsed, modifier = cardModifier)
        }
        stillWatching?.let { StillWatchingCard(stillWatching = it, modifier = cardModifier) }
    }
}

/** Precomputed geometry for [EndOfEpisodeScrim] so the brush is built once per size, not per
 * frame.
 */
private data class EndOfEpisodeScrimGeometry(val brush: Brush, val centre: Offset, val rx: Float, val ry: Float)

/**
 * docs/12 §13's card scrim: pinned to the frame's own bottom-right corner (no [OSD_MARGIN] inset,
 * unlike the card content drawn over it), present beneath either card and gone the instant neither
 * shows -- never animated on its own. CSS reference: `radial-gradient(ellipse at 88% 88%, Notte
 * 94% at 0, Notte 82% at 38%, Notte 42% at 66%, Notte 0% at 100%)`, reproduced as a circle of
 * radius `rx = sqrt(2) * 0.88 * w` (the CSS farthest-corner ellipse) scaled by `ry/rx` to the
 * box's aspect ratio.
 */
@Composable
private fun EndOfEpisodeScrim(modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val geometry = remember(density) {
        val w = with(density) { END_OF_EPISODE_SCRIM_WIDTH.toPx() }
        val h = with(density) { END_OF_EPISODE_SCRIM_HEIGHT.toPx() }
        val centre = Offset(0.88f * w, 0.88f * h)
        val rx = SQRT2 * 0.88f * w
        val ry = SQRT2 * 0.88f * h
        val brush = Brush.radialGradient(
            0f to JellybeamTheme.Notte.copy(alpha = 0.94f),
            0.38f to JellybeamTheme.Notte.copy(alpha = 0.82f),
            0.66f to JellybeamTheme.Notte.copy(alpha = 0.42f),
            1f to JellybeamTheme.Notte.copy(alpha = 0f),
            center = centre,
            radius = rx,
        )
        EndOfEpisodeScrimGeometry(brush, centre, rx, ry)
    }
    Canvas(
        modifier = modifier
            .size(END_OF_EPISODE_SCRIM_WIDTH, END_OF_EPISODE_SCRIM_HEIGHT)
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen),
    ) {
        withTransform({ scale(1f, geometry.ry / geometry.rx, pivot = geometry.centre) }) {
            drawCircle(brush = geometry.brush, radius = geometry.rx, center = geometry.centre)
        }
        // The ellipse still carries up to ~35% alpha where the box's top and left edges cut it,
        // a visible seam over video; multiply those edges down to nothing over the fade width.
        val fade = END_OF_EPISODE_SCRIM_EDGE_FADE.toPx()
        drawRect(
            brush = Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black, startY = 0f, endY = fade),
            size = Size(size.width, fade),
            blendMode = BlendMode.DstIn,
        )
        drawRect(
            brush = Brush.horizontalGradient(0f to Color.Transparent, 1f to Color.Black, startX = 0f, endX = fade),
            size = Size(fade, size.height),
            blendMode = BlendMode.DstIn,
        )
    }
}

/** The countdown rule shared by both cards: a 2dp Panna@16% track, Pistacchio fill left-anchored
 * at [remainingFraction] (1f full -> 0f empty). `null` draws the bare track -- up-next with
 * autoplay off. Reads [State.value] inside [drawBehind] so a per-frame update invalidates only the
 * draw phase, never layout or composition.
 */
@Composable
private fun EndOfEpisodeCountdownRule(remainingFraction: State<Float>?, modifier: Modifier = Modifier) {
    val track = JellybeamTheme.Panna.copy(alpha = 0.16f)
    val fill = JellybeamTheme.Pistacchio
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(END_OF_EPISODE_RULE_HEIGHT)
            .drawBehind {
                drawRect(color = track)
                val fraction = remainingFraction?.value ?: return@drawBehind
                if (fraction > 0f) {
                    drawRect(color = fill, size = Size(size.width * fraction, size.height))
                }
            },
    )
}

/**
 * Shared geometry for the next-up and still-watching cards (design 1c: no container, no
 * background/border/radius/shadow) -- only [eyebrow]/[title]/[detail]/[backLabel]/[actionLabel]/
 * [remainingFraction]/[numeral] differ between them. Nothing here is focusable; both cards handle
 * Select/Back at the OSD key-handler level.
 */
@Composable
private fun EndOfEpisodeCard(
    card: Card,
    eyebrow: String,
    title: String,
    detail: String?,
    backLabel: String,
    actionLabel: String,
    remainingFraction: State<Float>?,
    numeral: String?,
    modifier: Modifier = Modifier,
) {
    val artSource = remember(card.id) { CardFormatting.railArtSource(card) }
    val density = LocalDensity.current
    val thumbImageWidth = remember(density) {
        CardFormatting.bucketedImageWidth(with(density) { END_OF_EPISODE_THUMB_WIDTH.roundToPx() })
    }
    Column(modifier = modifier.width(END_OF_EPISODE_CARD_WIDTH)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .size(END_OF_EPISODE_THUMB_WIDTH, END_OF_EPISODE_THUMB_HEIGHT)
                    .clip(RoundedCornerShape(END_OF_EPISODE_THUMB_RADIUS)),
            ) {
                CardArtImage(
                    source = artSource,
                    imageUrl = { itemId, kind, tag -> AppGraph.gateway.imageUrl(itemId, kind, tag, thumbImageWidth) },
                    itemName = card.name,
                    contentAlpha = 1f,
                    blurhash = card.blurhash,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                BasicText(
                    text = eyebrow,
                    style = TextStyle(
                        fontFamily = JellybeamTheme.MartianMono,
                        color = JellybeamTheme.Grigio,
                        fontSize = 12.sp,
                        letterSpacing = (-0.02).em,
                    ),
                )
                BasicText(
                    text = title,
                    style = TextStyle(
                        fontFamily = JellybeamTheme.Archivo,
                        fontWeight = FontWeight.Medium,
                        color = JellybeamTheme.Panna,
                        fontSize = 15.sp,
                        lineHeight = 17.4.sp,
                    ),
                )
                detail?.let {
                    BasicText(
                        text = it,
                        style = TextStyle(
                            fontFamily = JellybeamTheme.MartianMono,
                            color = JellybeamTheme.Grigio,
                            fontSize = 11.sp,
                            letterSpacing = (-0.02).em,
                        ),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        EndOfEpisodeCountdownRule(remainingFraction = remainingFraction)
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            BasicText(
                text = backLabel,
                modifier = Modifier.alignByBaseline(),
                style = TextStyle(fontFamily = JellybeamTheme.MartianMono, color = JellybeamTheme.Grigio, fontSize = 11.sp),
            )
            Row(modifier = Modifier.alignByBaseline(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BasicText(
                    text = stringResource(R.string.card_select),
                    modifier = Modifier.alignByBaseline(),
                    style = TextStyle(fontFamily = JellybeamTheme.MartianMono, color = JellybeamTheme.Grigio, fontSize = 12.sp),
                )
                BasicText(
                    text = actionLabel,
                    modifier = Modifier.alignByBaseline(),
                    style = TextStyle(fontFamily = JellybeamTheme.Archivo, fontWeight = FontWeight.Bold, color = JellybeamTheme.Pistacchio, fontSize = 14.sp),
                )
                numeral?.let {
                    BasicText(
                        text = stringResource(R.string.card_countdown_in, it),
                        modifier = Modifier.alignByBaseline(),
                        style = TextStyle(fontFamily = JellybeamTheme.MartianMono, fontWeight = FontWeight.Bold, color = JellybeamTheme.Panna, fontSize = 14.sp),
                    )
                }
            }
        }
    }
}

/**
 * The up-next card's countdown (docs/12 §13, GOAL item 6): ends exactly where
 * [NextUpState.countdownStartPositionTicks] + [NextUpState.countdownTotalSecs] lands.
 * [PlaybackViewModel.positionTicks] only advances once per `REPORT_INTERVAL_MS`, too coarse for a
 * smooth rule, so this polls [livePositionTicks] every frame instead; the numeral state only
 * recomposes on a whole-second change. A no-op loop (rule stays full, no numeral) when
 * [NextUpState.autoAdvance] is false -- Select still plays now, but nothing counts down.
 */
@Composable
private fun NextUpCard(
    nextUp: NextUpState,
    livePositionTicks: () -> Long,
    onCountdownElapsed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val card = nextUp.card
    val seasonEpisode = remember(card.id) { CardFormatting.seasonEpisodeLabel(card.parentIndexNumber, card.indexNumber) }
    val title = if (seasonEpisode != null) "$seasonEpisode · ${card.name}" else card.name
    val detail = card.runtimeTicks?.let { ticks ->
        val minutes = ceil(ticks / 10_000_000.0 / 60.0).toLong()
        stringResource(R.string.next_up_runtime_min, minutes)
    }

    // Keyed on the countdown's identity and seeded from the live position so a fresh card never
    // shows a stale frame from the previous one.
    val startTicks = nextUp.countdownStartPositionTicks
    val totalSecs = nextUp.countdownTotalSecs
    val remainingFraction = remember(startTicks, totalSecs) {
        mutableFloatStateOf(NextUpCountdown.remainingFraction(totalSecs, NextUpCountdown.elapsedSecs(livePositionTicks(), startTicks)))
    }
    var remainingSecs by remember(startTicks, totalSecs) {
        mutableLongStateOf(NextUpCountdown.remainingWholeSecs(totalSecs, NextUpCountdown.elapsedSecs(livePositionTicks(), startTicks)))
    }
    LaunchedEffect(startTicks, totalSecs, nextUp.autoAdvance) {
        if (!nextUp.autoAdvance) return@LaunchedEffect
        while (true) {
            withFrameMillis { }
            val elapsed = NextUpCountdown.elapsedSecs(livePositionTicks(), startTicks)
            remainingFraction.floatValue = NextUpCountdown.remainingFraction(totalSecs, elapsed)
            val whole = NextUpCountdown.remainingWholeSecs(totalSecs, elapsed)
            if (whole != remainingSecs) remainingSecs = whole
            if (NextUpCountdown.isComplete(totalSecs, elapsed)) {
                // Hand over on this frame, not the ticker's next 1 Hz pass, so zero on the rule,
                // the numeral and the transition are one instant.
                onCountdownElapsed()
                break
            }
        }
    }

    EndOfEpisodeCard(
        card = card,
        eyebrow = stringResource(R.string.next_up_eyebrow),
        title = title,
        detail = detail,
        backLabel = stringResource(R.string.next_up_back_to_dismiss),
        actionLabel = stringResource(R.string.next_up_play_next),
        remainingFraction = if (nextUp.autoAdvance) remainingFraction else null,
        numeral = if (nextUp.autoAdvance) NextUpCountdown.numeral(remainingSecs) else null,
        modifier = modifier,
    )
}

/**
 * The still-watching card's countdown (docs/12 §13, GOAL item 6), same shape as [NextUpCard]'s but
 * off a wall-clock [nowMs] against [StillWatchingState.deadlineMs] rather than position ticks.
 * Keyed on [StillWatchingState.deadlineMs] alone so a [PlaybackViewModel.noteUserInput] restart
 * (a fresh deadline) resets both the rule and the numeral.
 */
@Composable
private fun StillWatchingCard(
    stillWatching: StillWatchingState,
    modifier: Modifier = Modifier,
    nowMs: () -> Long = { System.currentTimeMillis() },
) {
    val card = stillWatching.card
    val seasonEpisode = remember(card.id) { CardFormatting.seasonEpisodeLabel(card.parentIndexNumber, card.indexNumber) }
    val detail = if (seasonEpisode != null) "$seasonEpisode · ${card.name}" else card.name

    // Keyed on the deadline so a noteUserInput restart reseeds both before the first frame -- no
    // one-frame flash of the old near-zero values.
    val remainingFraction = remember(stillWatching.deadlineMs) {
        mutableFloatStateOf(StillWatchingCountdown.remainingFraction(stillWatching.timeoutTotalSecs, stillWatching.deadlineMs, nowMs()))
    }
    var remainingSecs by remember(stillWatching.deadlineMs) {
        mutableLongStateOf(StillWatchingCountdown.remainingWholeSecs(stillWatching.deadlineMs, nowMs()))
    }
    LaunchedEffect(stillWatching.deadlineMs) {
        while (true) {
            withFrameMillis { }
            val now = nowMs()
            remainingFraction.floatValue = StillWatchingCountdown.remainingFraction(stillWatching.timeoutTotalSecs, stillWatching.deadlineMs, now)
            val whole = StillWatchingCountdown.remainingWholeSecs(stillWatching.deadlineMs, now)
            if (whole != remainingSecs) remainingSecs = whole
            if (now >= stillWatching.deadlineMs) break
        }
    }

    EndOfEpisodeCard(
        card = card,
        eyebrow = stringResource(R.string.still_watching_eyebrow),
        title = stringResource(R.string.still_watching_title),
        detail = detail,
        backLabel = stringResource(R.string.still_watching_back_to_stop),
        actionLabel = stringResource(R.string.still_watching_keep_watching),
        remainingFraction = remainingFraction,
        numeral = NextUpCountdown.numeral(remainingSecs),
        modifier = modifier,
    )
}

// -- Track picker panel (A&S menu internals not yet designed -- docs/12 §16) -------

@Composable
private fun TrackPickerPanel(picker: TrackPickerState, focusedIndex: Int, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .width(TRACK_PICKER_WIDTH)
            .heightIn(max = TRACK_PICKER_MAX_HEIGHT)
            .background(JellybeamTheme.SurfacePanel, RoundedCornerShape(TRACK_PICKER_RADIUS))
            .border(1.dp, JellybeamTheme.Hairline, RoundedCornerShape(TRACK_PICKER_RADIUS))
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        TrackPickerSectionHeader(text = "Audio")
        Spacer(modifier = Modifier.height(8.dp))
        picker.audioTracks.forEachIndexed { index, choice ->
            TrackChoiceRow(choice = choice, isFocused = index == focusedIndex)
            Spacer(modifier = Modifier.height(4.dp))
        }

        Spacer(modifier = Modifier.height(16.dp))

        TrackPickerSectionHeader(text = "Subtitles")
        Spacer(modifier = Modifier.height(8.dp))
        val audioCount = picker.audioTracks.size
        picker.subtitleTracks.forEachIndexed { index, choice ->
            TrackChoiceRow(choice = choice, isFocused = audioCount + index == focusedIndex)
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@Composable
private fun TrackPickerSectionHeader(text: String) {
    BasicText(
        text = text,
        style = TextStyle(
            fontFamily = JellybeamTheme.Archivo,
            fontWeight = FontWeight.SemiBold,
            color = JellybeamTheme.Grigio,
            fontSize = 13.sp,
            letterSpacing = 1.sp,
        ),
    )
}

@Composable
private fun TrackChoiceRow(choice: TrackChoice, isFocused: Boolean, modifier: Modifier = Modifier) {
    val focusFill = remember { JellybeamTheme.Panna.copy(alpha = 0x22 / 255f) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TRACK_ROW_RADIUS))
            .background(if (isFocused) focusFill else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(20.dp)) {
            if (choice.selected) {
                BasicText(
                    text = "✓",
                    style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Panna, fontSize = 14.sp),
                )
            }
        }
        BasicText(
            text = choice.label,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Panna, fontSize = 16.sp),
        )
        choice.meta?.let {
            BasicText(
                text = it,
                style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Grigio, fontSize = 11.sp),
            )
        }
    }
}

// -- Skip intro/credits pill + Undo toast -----------------------------------

private val SKIP_PILL_RADIUS = 6.dp
private val SKIP_UNDO_TOAST_RADIUS = 8.dp

@Composable
private fun SkipPill(label: String, modifier: Modifier = Modifier) {
    BasicText(
        text = label,
        modifier = modifier
            .background(JellybeamTheme.SurfaceRaised.copy(alpha = 0xee / 255f), RoundedCornerShape(SKIP_PILL_RADIUS))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Panna, fontSize = 14.sp),
    )
}

@Composable
private fun SkipUndoToast(label: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(top = 24.dp)
            .background(JellybeamTheme.SurfacePanel, RoundedCornerShape(SKIP_UNDO_TOAST_RADIUS))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        BasicText(
            text = "$label · Select to undo",
            style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Panna, fontSize = 13.sp),
        )
    }
}

// -- Buffering / reconnecting pills ------------------------------------------

private const val PULSING_DOT_COUNT = 3
private const val PULSING_DOT_CYCLE_MS = 1200
private val PULSING_DOT_SIZE = 6.dp

@Composable
internal fun PulsingDots(modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(PULSING_DOT_COUNT) { index ->
            val transition = rememberInfiniteTransition(label = "pulsingDots")
            val dotAlpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(PULSING_DOT_CYCLE_MS / 2, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset((PULSING_DOT_CYCLE_MS / PULSING_DOT_COUNT) * index),
                ),
                label = "pulsingDotAlpha",
            )
            Box(
                modifier = Modifier
                    .size(PULSING_DOT_SIZE)
                    .graphicsLayer { alpha = dotAlpha }
                    .clip(CircleShape)
                    .background(JellybeamTheme.Panna),
            )
        }
    }
}

@Composable
private fun BufferingPill(info: BufferingInfo, modifier: Modifier = Modifier) {
    val throughput = BufferingInfo.formatThroughput(info.bytesPerSec)
    val text = if (info.bytesPerSec > 0L) {
        "${stringResource(R.string.player_buffering_label)} · ${info.percent}% · $throughput"
    } else {
        "${stringResource(R.string.player_buffering_label)} · ${info.percent}%"
    }
    Row(
        modifier = modifier
            .background(JellybeamTheme.SurfaceRaised.copy(alpha = 0xee / 255f), RoundedCornerShape(SKIP_PILL_RADIUS))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PulsingDots()
        BasicText(text = text, style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Panna, fontSize = 13.sp))
    }
}

@Composable
private fun ReconnectingPill(info: ReconnectingInfo, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(JellybeamTheme.SurfaceRaised.copy(alpha = 0xee / 255f), RoundedCornerShape(SKIP_PILL_RADIUS))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PulsingDots()
        BasicText(
            text = stringResource(R.string.player_reconnecting_label, info.attempt),
            style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Panna, fontSize = 13.sp),
        )
    }
}
