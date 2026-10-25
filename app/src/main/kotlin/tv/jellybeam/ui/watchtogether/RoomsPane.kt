package tv.jellybeam.ui.watchtogether

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import tv.jellybeam.JellybeamTheme
import tv.jellybeam.R
import tv.jellybeam.i18n.UiStrings
import tv.jellybeam.ui.focus.FocusMemory
import tv.jellybeam.ui.focus.focusKey
import uniffi.jellybeam_core.SyncPlayPhase
import uniffi.jellybeam_core.SyncPlayRoom

/** docs/28 §1: a first fetch quicker than this never shows the loading line. */
private const val ROOMS_LOADING_SHOWN_MS = 300L

/** Rows bleed this far past the column on both sides, so their text lines up with the heading. */
private val ROW_BLEED = 14.dp
private val ROW_HEIGHT = 60.dp

/** Whole rows only, so the list never ends on a half-cut name. */
private const val ROWS_SHOWN = 5
private val ROW_SHAPE = RoundedCornerShape(5.dp)

internal fun roomKey(groupId: String) = "room-$groupId"

/** A focused row and where it sat, so a refresh that drops it can pick the nearest row. */
private data class FocusedRoom(val groupId: String, val index: Int)

/**
 * docs/28 §1 (2a-2g): the room list. Rows are its only focus targets, so an empty list has none; while
 * [RoomsPaneView.joining] the list holds still, Up and Down do nothing and only Back acts.
 */
@Composable
internal fun RoomsPane(view: RoomsPaneView, isTop: Boolean, strings: UiStrings, memory: FocusMemory, send: (TogetherEvent) -> Unit) {
    val state = view.rooms
    val joining = view.joining
    val list = state.list
    val rooms = (list as? RoomList.Listed)?.rooms.orEmpty()
    var focused by remember { mutableStateOf<FocusedRoom?>(null) }
    var inPane by remember { mutableStateOf(false) }
    var loadingShown by remember { mutableStateOf(false) }
    val latestSend by rememberUpdatedState(send)

    LaunchedEffect(list == RoomList.Loading) {
        loadingShown = false
        if (list == RoomList.Loading) {
            delay(ROOMS_LOADING_SHOWN_MS)
            loadingShown = true
        }
    }
    // docs/28 §1: the line clears after its time unless a Select cleared it first.
    LaunchedEffect(state.line, isTop) {
        if (state.line == null || !isTop) return@LaunchedEffect
        delay(ROOMS_LINE_MS)
        latestSend(TogetherEvent.LineExpired)
    }
    // Decided in composition, before the dropped row's removal clears focus out of the pane.
    val refocus = remember(rooms) { focused?.let { roomRefocus(it.groupId, it.index, rooms, inPane, isTop) } ?: RoomRefocus.Keep }
    LaunchedEffect(rooms, refocus) {
        if (refocus == RoomRefocus.Keep) return@LaunchedEffect
        focused = null
        if (refocus !is RoomRefocus.Move) return@LaunchedEffect
        withFrameNanos { }
        memory.target(refocus.key)?.requestFocus()
    }

    Column(
        modifier = Modifier
            .onFocusChanged { inPane = it.hasFocus }
            .onPreviewKeyEvent { joining != null && (it.key == Key.DirectionUp || it.key == Key.DirectionDown) },
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (list == RoomList.NoAccess) {
            Eyebrow(stringResource(R.string.wt_role_no_access))
            Heading(stringResource(R.string.wt_rooms_no_access_title))
            MessageBlock(stringResource(R.string.wt_rooms_no_access), stringResource(R.string.wt_rooms_no_access_hint))
            return@Column
        }
        Eyebrow(if (list is RoomList.Listed) stringResource(R.string.wt_role_rooms_count, rooms.size) else stringResource(R.string.wt_role_rooms))
        Heading(stringResource(R.string.wt_rooms_title))
        val status = state.line?.let { roomsLineText(strings, it) }
            ?: LogLine(stringResource(R.string.wt_rooms_failed), LogTone.Alert).takeIf { list == RoomList.Failed }
        status?.let { Status(it) }
        when {
            list == RoomList.Loading -> if (loadingShown) RuledBlock { Mono(stringResource(R.string.wt_rooms_loading), 9.sp, JellybeamTheme.Grigio) }
            list is RoomList.Listed && rooms.isEmpty() -> MessageBlock(stringResource(R.string.wt_rooms_empty), stringResource(R.string.wt_rooms_empty_hint))
            rooms.isNotEmpty() -> {
                val focusedIndex = focused?.let { f -> rooms.indexOfFirst { it.groupId == f.groupId } }?.takeIf { it >= 0 }
                LazyColumn(modifier = Modifier.requiredWidth(650.dp + ROW_BLEED * 2).heightIn(max = ROW_HEIGHT * ROWS_SHOWN)) {
                    itemsIndexed(rooms, key = { _, room -> room.groupId }, contentType = { _, _ -> "room" }) { index, room ->
                        RoomRow(
                            room = room,
                            ruled = roomRowRuled(index, focusedIndex),
                            joining = room.groupId == joining,
                            modifier = Modifier
                                .focusKey(memory, roomKey(room.groupId))
                                .onFocusChanged { if (it.isFocused) focused = FocusedRoom(room.groupId, index) },
                        ) { if (joining == null) send(TogetherEvent.PickRoom(room.groupId, listedRoomName(room))) }
                    }
                }
            }
        }
        Body(stringResource(R.string.wt_rooms_footnote), 10.5.sp, JellybeamTheme.Grigio, Modifier.widthIn(max = 450.dp))
    }
}

@Composable
internal fun RoomsKeyHint(view: RoomsPaneView, modifier: Modifier) {
    val text = when {
        view.joining != null -> R.string.wt_rooms_keys_joining
        (view.rooms.list as? RoomList.Listed)?.rooms?.isNotEmpty() == true -> R.string.wt_rooms_keys_list
        else -> R.string.wt_rooms_keys_close
    }
    Mono(stringResource(text), 9.sp, JellybeamTheme.GrigioDim, modifier)
}

@Composable
private fun Status(line: LogLine) {
    val color = if (line.tone == LogTone.Alert) JellybeamTheme.Ambra else JellybeamTheme.Panna2
    Body(line.text, 13.sp, color, Modifier.offset(y = (-4).dp))
}

@Composable
private fun RuledBlock(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind { drawLine(JellybeamTheme.RuleFaint, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) }
            .padding(top = 14.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) { content() }
}

@Composable
private fun MessageBlock(title: String, hint: String) {
    RuledBlock {
        BasicText(title, style = TextStyle(fontFamily = JellybeamTheme.Archivo, fontWeight = FontWeight.SemiBold, color = JellybeamTheme.Panna, fontSize = 16.sp))
        Body(hint, 13.sp, JellybeamTheme.Panna2)
    }
}

/** docs/28 §1: one room: its name over its members, both verbatim, then the head count and the state; the focused or joining row is filled. */
@Composable
private fun RoomRow(room: SyncPlayRoom, ruled: Boolean, joining: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val filled = focused || joining
    val ink = JellybeamTheme.Notte
    val names = roomNames(room.participants)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .drawBehind { if (ruled) drawLine(JellybeamTheme.RuleFaint, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) }
            .background(if (filled) JellybeamTheme.Pistacchio else Color.Transparent, ROW_SHAPE)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = ROW_BLEED),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            listedRoomName(room)?.let { name ->
                BasicText(
                    name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = JellybeamTheme.Archivo, fontWeight = FontWeight.SemiBold, color = if (filled) ink else JellybeamTheme.Panna, fontSize = 15.sp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Server user names, verbatim; the count after them never truncates.
                val small = TextStyle(fontFamily = JellybeamTheme.Archivo, color = if (filled) ink else JellybeamTheme.Grigio, fontSize = 13.sp)
                BasicText(names.names, modifier = Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis, style = small)
                if (names.more > 0) BasicText(" " + stringResource(R.string.wt_room_more, names.more), maxLines = 1, style = small)
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.width(60.dp), horizontalAlignment = Alignment.End) {
            Mono(pluralStringResource(R.plurals.wt_room_people, room.participants.size, room.participants.size), 9.sp, if (filled) ink else JellybeamTheme.Grigio)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.width(75.dp), horizontalAlignment = Alignment.End) {
            val tag = if (joining) R.string.wt_room_joining else roomTag(room.phase)
            tag?.let { Mono(stringResource(it), 9.sp, if (filled) ink else tagColor(room.phase)) }
        }
    }
}

private fun tagColor(phase: SyncPlayPhase?): Color = when (phase) {
    SyncPlayPhase.PLAYING -> JellybeamTheme.Pistacchio
    SyncPlayPhase.WAITING -> JellybeamTheme.Grigio
    SyncPlayPhase.IDLE, SyncPlayPhase.PAUSED, null -> JellybeamTheme.Panna2
}
