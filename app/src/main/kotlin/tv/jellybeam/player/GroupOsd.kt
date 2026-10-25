package tv.jellybeam.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import java.util.Date
import kotlinx.coroutines.delay
import tv.jellybeam.JellybeamTheme
import tv.jellybeam.R
import tv.jellybeam.i18n.rememberUiStrings

/** docs/28 §1: how long an event shows alone while the OSD is hidden, and its fade. */
private const val EVENT_ALONE_MS = 4_000L
private const val EVENT_FADE_MS = 200

private val MONO_TRACKING = (-0.02).em
private val MEMBERS_MAX_WIDTH = 420.dp

/**
 * docs/28 §1 (1f): the group readout, top right. It reads each live notice once through [onNoticeRead], keeping
 * the latest as its event line; with the OSD hidden a fresh event shows alone for [EVENT_ALONE_MS].
 */
@Composable
internal fun GroupReadout(group: GroupOsd, osdVisible: Boolean, sheetOpen: Boolean, onNoticeRead: () -> Unit, modifier: Modifier = Modifier) {
    val strings = rememberUiStrings()
    val context = LocalContext.current
    val timeFormat = remember(context) { android.text.format.DateFormat.getTimeFormat(context) }
    val noticeRead by rememberUpdatedState(onNoticeRead)
    val readAt = remember { mutableStateOf<Int?>(null) }
    var latest by remember { mutableStateOf<ReadNotice?>(null) }
    var eventToken by remember { mutableIntStateOf(0) }
    var eventFresh by remember { mutableStateOf(false) }

    // Keyed on the drop count too: a drop and an equal arrival in one snapshot leave the list unchanged.
    LaunchedEffect(group.live, group.dropped) {
        val turn = noticeTurn(group.live, group.dropped, readAt.value)
        readAt.value = turn.readAt
        turn.fresh?.let {
            latest = ReadNotice(it, System.currentTimeMillis())
            eventToken++
            noticeRead()
        }
    }
    LaunchedEffect(eventToken) {
        if (eventToken > 0) {
            eventFresh = true
            delay(EVENT_ALONE_MS)
            eventFresh = false
        }
    }

    val line = groupEventLine(strings, group, latest) { timeFormat.format(Date(it)) }
    val shows = readoutShows(osdVisible, sheetOpen, eventFresh, group.reconnecting)
    Column(modifier = modifier, horizontalAlignment = Alignment.End) {
        if (shows == ReadoutShows.All) {
            Column(modifier = Modifier.readoutPlate(), horizontalAlignment = Alignment.End) {
                Row(modifier = Modifier.widthIn(max = MEMBERS_MAX_WIDTH), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Mono(stringResource(R.string.wt_eyebrow), 10.sp, JellybeamTheme.Pistacchio)
                    // docs/28 §1: the room's name, verbatim; it ellipsizes before the eyebrow does.
                    group.name?.let {
                        Mono("│", 10.sp, JellybeamTheme.Hairline)
                        Mono(it, 10.sp, JellybeamTheme.Panna2, Modifier.weight(1f, fill = false))
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                val hostMark = stringResource(R.string.wt_osd_host)
                BasicText(
                    text = buildAnnotatedString {
                        group.members.forEachIndexed { i, member ->
                            if (i > 0) append(", ")
                            append(member.name)
                            if (member.host) withStyle(SpanStyle(color = JellybeamTheme.Panna2, fontWeight = FontWeight.Normal)) { append(" $hostMark") }
                        }
                    },
                    modifier = Modifier.widthIn(max = MEMBERS_MAX_WIDTH),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = JellybeamTheme.Archivo, fontWeight = FontWeight.SemiBold, color = JellybeamTheme.Panna, fontSize = 13.sp),
                )
                line?.let {
                    Spacer(modifier = Modifier.height(4.dp))
                    Mono(it, 9.sp, JellybeamTheme.Panna2)
                }
            }
        } else {
            AnimatedVisibility(visible = shows == ReadoutShows.EventOnly && line != null, enter = EnterTransition.None, exit = fadeOut(tween(EVENT_FADE_MS))) {
                Box(modifier = Modifier.readoutPlate()) { Mono(line.orEmpty(), 9.sp, JellybeamTheme.Panna2) }
            }
        }
    }
}

/** The readout's solid plate, so it reads over any frame; no shadow or glow. */
private fun Modifier.readoutPlate(): Modifier = this
    .background(JellybeamTheme.Notte, RoundedCornerShape(5.dp))
    .padding(horizontal = 12.dp, vertical = 10.dp)

/** docs/28 §1, §6: the waiting pill; [prompt] swaps in the stall prompt, and Leave holds the ring when [leaveFocused]. */
@Composable
internal fun GroupWaitingPill(prompt: Boolean, leaveFocused: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(JellybeamTheme.SurfaceRaised, RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = stringResource(if (prompt) R.string.wt_osd_prompt else R.string.wt_osd_waiting),
            style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Panna, fontSize = 14.sp),
        )
        Spacer(modifier = Modifier.width(16.dp))
        BasicText(
            text = stringResource(R.string.wt_leave),
            style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = if (leaveFocused) JellybeamTheme.Pistacchio else JellybeamTheme.Panna2, fontSize = 14.sp),
        )
    }
}

/** docs/28 §1: the one-time line under the scrubber saying the transport is the group's. */
@Composable
internal fun GroupHelperLine(modifier: Modifier = Modifier) {
    BasicText(
        text = stringResource(R.string.wt_osd_helper),
        modifier = modifier,
        style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Grigio, fontSize = 11.sp),
    )
}

@Composable
private fun Mono(text: String, size: TextUnit, color: Color, modifier: Modifier = Modifier) {
    BasicText(
        text,
        modifier = modifier,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = TextStyle(fontFamily = JellybeamTheme.MartianMono, color = color, fontSize = size, letterSpacing = MONO_TRACKING),
    )
}
