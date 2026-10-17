package tv.jellybeam.ui.library

import androidx.annotation.StringRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import tv.jellybeam.JellybeamTheme
import tv.jellybeam.R
import tv.jellybeam.i18n.AppLocale
import tv.jellybeam.i18n.UiStrings
import tv.jellybeam.i18n.rememberUiStrings
import tv.jellybeam.ui.cards.CardFormatting
import tv.jellybeam.ui.common.rememberMinuteClock
import tv.jellybeam.ui.detail.endsAtLabel
import uniffi.jellybeam_core.Card

/** Tuned on device: [READOUT_TOP] is the title's top; the gaps are row-to-row, label-stack and column-to-column. */
private val READOUT_TOP = 16.dp
private val READOUT_ROW_GAP = 7.dp
private val READOUT_STACK_GAP = 2.dp
private val READOUT_COLUMN_GAP = 22.dp
private val READOUT_TITLE_GAP = 8.dp
private val READOUT_TITLE_MAX_WIDTH = 450.dp
private const val READOUT_CROSSFADE_MS = 80

private val TitleStyle = TextStyle(fontFamily = JellybeamTheme.Archivo, fontWeight = FontWeight.Bold, color = JellybeamTheme.Panna, fontSize = 16.sp)
private val MetaStyle = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Grigio, fontSize = 12.sp)
private val LabelStyle = TextStyle(fontFamily = JellybeamTheme.MartianMono, color = JellybeamTheme.Grigio, fontSize = 7.5.sp, letterSpacing = (-0.02).em)
private val ValueStyle = TextStyle(fontFamily = JellybeamTheme.Archivo, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
private val SubStyle = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Grigio, fontSize = 9.5.sp)

/**
 * docs/16 §4: the rested poster's title, year/runtime and ENDS AT / LAST PLAYED / ADDED, end-aligned
 * in the header's empty upper-right. It overdraws below [modifier]'s box instead of growing it,
 * so the title, strip and grid never move; [card] `null` shows nothing.
 */
@Composable
fun LibraryReadout(card: Card?, startInset: Dp, modifier: Modifier = Modifier) {
    // Hiding is immediate: a fade-out would show the outgoing card in a state that has none.
    if (card == null) return
    Crossfade(
        targetState = card,
        modifier = modifier,
        animationSpec = tween(READOUT_CROSSFADE_MS),
        label = "libraryReadout",
    ) { shown ->
        Box(modifier = Modifier.fillMaxWidth().padding(start = startInset), contentAlignment = Alignment.TopEnd) {
            ReadoutBody(
                card = shown,
                modifier = Modifier
                    .wrapContentHeight(align = Alignment.Top, unbounded = true)
                    .padding(top = READOUT_TOP, end = PAGE_MARGIN)
                    .onlyIfItFits(),
            )
        }
    }
}

/** Measures at its natural width and draws nothing when that exceeds the room it is given, so it never squeezes or overlaps the header text. */
private fun Modifier.onlyIfItFits(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity))
    if (placeable.width > constraints.maxWidth) {
        layout(0, 0) {}
    } else {
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
}

@Composable
private fun ReadoutBody(card: Card, modifier: Modifier) {
    val strings = rememberUiStrings()
    val context = LocalContext.current
    val timeFormat = remember(context) { android.text.format.DateFormat.getTimeFormat(context) }
    // Read here, not in the grid: a minute tick recomposes only this body.
    val nowMillis = rememberMinuteClock()
    val zone = ZoneId.systemDefault()
    val locale = AppLocale.format
    val now = Instant.ofEpochMilli(nowMillis)

    val meta = CardFormatting.detailMetaLine(strings, card.productionYear, card.runtimeTicks)
    val endsAt = endsAtLabel(card.runtimeTicks, card.positionTicks, nowMillis, zone) { timeFormat.format(Date(it)) }
    val endsSub = endsSub(strings, card.runtimeTicks, card.positionTicks)
    val lastPlayed = lastPlayedValue(strings, card.lastPlayedDate, card.played, now, zone)
    val lastPlayedSub = lastPlayedSub(strings, card.played, card.positionTicks)
    val addedRelative = relativeDay(strings, card.dateCreated, now, zone)
    val addedAbsolute = addedAbsolute(card.dateCreated, zone, locale)

    Column(modifier = modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(READOUT_ROW_GAP)) {
        Row(horizontalArrangement = Arrangement.spacedBy(READOUT_TITLE_GAP)) {
            BasicText(
                text = card.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = READOUT_TITLE_MAX_WIDTH).alignByBaseline(),
                style = TitleStyle,
            )
            if (meta != null) {
                BasicText(text = meta, maxLines = 1, modifier = Modifier.alignByBaseline(), style = MetaStyle)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(READOUT_COLUMN_GAP)) {
            if (endsAt != null) {
                ReadoutColumn(strings, R.string.library_readout_ends_at, endsAt, JellybeamTheme.Pistacchio, endsSub)
            }
            ReadoutColumn(strings, R.string.library_readout_last_played, lastPlayed, JellybeamTheme.Panna, lastPlayedSub)
            if (addedRelative != null) {
                ReadoutColumn(strings, R.string.library_readout_added, addedRelative, JellybeamTheme.Panna, addedAbsolute)
            }
        }
    }
}

@Composable
private fun ReadoutColumn(strings: UiStrings, @StringRes label: Int, value: String, valueColor: Color, sub: String?) {
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(READOUT_STACK_GAP)) {
        BasicText(text = strings.get(label), maxLines = 1, style = LabelStyle)
        BasicText(text = value, maxLines = 1, style = ValueStyle.copy(color = valueColor))
        if (sub != null) BasicText(text = sub, maxLines = 1, style = SubStyle)
    }
}
