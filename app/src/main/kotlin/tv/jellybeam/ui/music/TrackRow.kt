package tv.jellybeam.ui.music

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tv.jellybeam.JellybeamTheme
import tv.jellybeam.LocalAccent
import tv.jellybeam.R
import tv.jellybeam.ui.cards.ArtSource
import tv.jellybeam.ui.cards.focusRing

/** docs/29 §8.1's row variants: Compact (S4) drops the LRC and heart columns. */
internal enum class TrackRowLayout { OneLine, TwoLine, Compact }

/** Whether a row is the playing track; [Playing] pulses, so a caller off screen passes [Paused]. */
internal enum class CurrentMark { None, Playing, Paused }

/**
 * One row's text, all verbatim from the server except [number] ([trackNumberLabel]) and [duration]
 * ([formatTrackTime]). A `null` column is absent: [number], [thumb], [artist] and [album] (one-line only),
 * [line2] (two-line only), [extra], [plays].
 */
internal data class TrackRowData(
    val title: String,
    val duration: String,
    val number: String? = null,
    val thumb: ArtSource? = null,
    val artist: String? = null,
    val album: String? = null,
    val line2: String? = null,
    val extra: String? = null,
    val plays: String? = null,
    val hasLyrics: Boolean = false,
    val isFavorite: Boolean = false,
)

private val ONE_LINE_HEIGHT = 44.dp
private val ONE_LINE_GROWN = 62.dp
private val TWO_LINE_HEIGHT = 52.dp
private val PILL = RoundedCornerShape(50)

/**
 * OK plays on release; OK held (or the Menu key) calls [onMore] instead, and the release that ends the hold is
 * swallowed so it never also plays. No-op without [onMore].
 */
@Composable
private fun rememberHoldForMore(onClick: () -> Unit, onMore: (() -> Unit)?): Modifier {
    var down by remember { mutableStateOf(false) }
    var held by remember { mutableStateOf(false) }
    val latestClick by rememberUpdatedState(onClick)
    val latestMore by rememberUpdatedState(onMore)
    if (onMore == null) return Modifier
    return Modifier.onPreviewKeyEvent { event ->
        val more = latestMore ?: return@onPreviewKeyEvent false
        when (event.key) {
            Key.Menu -> {
                if (event.type == KeyEventType.KeyUp) more()
                true
            }
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                when {
                    event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0 -> {
                        down = true
                        held = false
                    }
                    event.type == KeyEventType.KeyDown -> if (down && !held) {
                        held = true
                        more()
                    }
                    event.type == KeyEventType.KeyUp -> {
                        if (down && !held) latestClick()
                        down = false
                        held = false
                    }
                }
                true
            }
            else -> false
        }
    }
}

/** docs/29 §9 A3/D1: the PLAYS column, wide enough for "12,408". */
internal val PLAYS_WIDTH = 44.dp

/**
 * docs/29 §8.1: a pill row that fills with accent on focus, every glyph turning onAccent. A one-line
 * row whose title or artist is cut grows to 62 dp with two lines while focused; two-line rows never grow.
 * [modifier] goes before the focus target, so a caller's `focusKey` lands on it. A [moving] row (docs/29 §9 H2)
 * shows ↕ in its gutter. With [onMore], OK held or the Menu key is the row's ··· (docs/29 §9 K, §H).
 */
@Composable
internal fun TrackRow(
    data: TrackRowData,
    layout: TrackRowLayout,
    mark: CurrentMark,
    imageUrl: MusicImageUrl,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    moving: Boolean = false,
    onMore: (() -> Unit)? = null,
) {
    val hold = rememberHoldForMore(onClick, onMore)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val accent = LocalAccent.current
    var clipped by remember(data.title, data.artist, data.album) { mutableStateOf(false) }
    val twoLine = layout == TrackRowLayout.TwoLine
    val grown = focused && clipped && !twoLine
    val ink = if (focused) accent.onAccent else JellybeamTheme.Panna
    val dim = if (focused) accent.onAccent else PannaB3
    val mono = if (focused) accent.onAccent else JellybeamTheme.Grigio
    val clipLines = if (grown) 2 else 1
    val height = if (twoLine) TWO_LINE_HEIGHT else if (grown) ONE_LINE_GROWN else ONE_LINE_HEIGHT

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .focusRing(focused, cornerRadius = height / 2, color = accent.accentSheen)
            .background(if (focused) accent.accent else Color.Transparent, PILL)
            .then(hold)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(start = 10.dp, end = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(20.dp), contentAlignment = Alignment.Center) {
            if (moving) {
                Canvas(Modifier.size(12.dp)) { MoveGlyph(if (focused) accent.onAccent else accent.accentInk) }
            } else if (mark != CurrentMark.None) {
                CurrentDot(color = if (focused) accent.onAccent else accent.accentInk, pulsing = mark == CurrentMark.Playing)
            }
        }
        data.number?.let { BasicText(it, Modifier.width(32.dp), maxLines = 1, style = monoStyle(mono, 11)) }
        data.thumb?.let {
            SquareArt(it, imageUrl, data.title, Modifier.size(32.dp).clip(RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            BasicText(
                text = data.title,
                maxLines = clipLines,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (!grown && !twoLine && it.hasVisualOverflow) clipped = true },
                style = TextStyle(fontFamily = JellybeamTheme.Archivo, fontWeight = FontWeight.Medium, color = ink, fontSize = 14.sp),
            )
            if (twoLine && data.line2 != null) {
                BasicText(
                    text = data.line2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = dim, fontSize = 12.sp),
                )
            }
        }
        if (!twoLine && data.artist != null) {
            BasicText(
                text = data.artist,
                modifier = Modifier.padding(start = 16.dp).widthIn(min = 250.dp, max = 320.dp),
                maxLines = clipLines,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (!grown && it.hasVisualOverflow) clipped = true },
                style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = dim, fontSize = 13.sp),
            )
        }
        if (!twoLine && data.album != null) {
            BasicText(
                text = data.album,
                modifier = Modifier.padding(start = 16.dp).width(180.dp),
                maxLines = clipLines,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (!grown && it.hasVisualOverflow) clipped = true },
                style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = dim, fontSize = 13.sp),
            )
        }
        data.extra?.let {
            BasicText(
                it,
                Modifier.padding(start = 12.dp).widthIn(min = 40.dp, max = 110.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = monoStyle(mono, 10).copy(textAlign = TextAlign.End),
            )
        }
        data.plays?.let {
            BasicText(it, Modifier.padding(start = 12.dp).width(PLAYS_WIDTH), maxLines = 1, style = monoStyle(mono, 10).copy(textAlign = TextAlign.End))
        }
        if (layout != TrackRowLayout.Compact) {
            Box(Modifier.width(38.dp), contentAlignment = Alignment.Center) {
                if (data.hasLyrics) BasicText(stringResource(R.string.music_row_lyrics_tag), maxLines = 1, style = monoStyle(mono, 10))
            }
            Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                if (data.isFavorite) {
                    val heart = if (focused) accent.onAccent else accent.accentInk
                    Canvas(Modifier.size(12.dp)) { HeartGlyph(heart) }
                }
            }
        }
        BasicText(
            text = data.duration,
            modifier = Modifier.width(55.dp),
            maxLines = 1,
            style = monoStyle(if (focused) accent.onAccent else JellybeamTheme.Panna2, 11).copy(textAlign = TextAlign.End),
        )
    }
}
