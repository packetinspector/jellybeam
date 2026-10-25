package tv.jellybeam.ui.watchtogether

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Date
import kotlinx.coroutines.delay
import tv.jellybeam.AppGraph
import tv.jellybeam.JellybeamTheme
import tv.jellybeam.R
import tv.jellybeam.i18n.UiStrings
import tv.jellybeam.i18n.rememberUiStrings
import tv.jellybeam.i18n.uppercaseUi
import tv.jellybeam.player.outcomeText
import tv.jellybeam.ui.cards.ArtSource
import tv.jellybeam.ui.cards.BACKDROP_PLACEHOLDER_DIM
import tv.jellybeam.ui.cards.CardArtImage
import tv.jellybeam.ui.cards.CardFormatting
import tv.jellybeam.ui.cards.WIDE_ART_ASPECT
import tv.jellybeam.ui.cards.focusRing
import tv.jellybeam.ui.common.ActiveWhileTop
import tv.jellybeam.ui.focus.FocusMemory
import tv.jellybeam.ui.focus.FocusRestorer
import tv.jellybeam.ui.focus.focusKey
import tv.jellybeam.ui.focus.rememberFocusMemory
import uniffi.jellybeam_core.Card

/** docs/28 §1: how long a lobby notice stays before the next one. */
private const val NOTICE_SHOWN_MS = 4_000L

/** The backdrop slot is 650 dp (1300 px) wide. */
private val BACKDROP_WIDTH_PX = CardFormatting.bucketedImageWidth(1300)
private val POSTER_WIDTH_PX = CardFormatting.bucketedImageWidth(150)

private val MONO_TRACKING = (-0.02).em
private val PILL_SHAPE = RoundedCornerShape(50)
private val PILL_RING = 1.5.dp

/** The screen's focus target for each view, so a view change re-places focus. */
internal enum class Pane(val primary: String) { Idle("none"), Rooms("none"), Entering("cancel"), Refused("stop"), Lobby("cancel"), Done("none") }

/**
 * docs/15 §2: a new key is a fresh landing on [primary]; a room-list refresh is none, except between empty and not,
 * so the first room to appear takes focus.
 */
internal fun focusReloadKey(pane: Pane, listedEmpty: Boolean, primary: String): Any =
    if (pane == Pane.Rooms) pane to listedEmpty else pane to primary

/**
 * docs/28 §1: the room list and the lobby. Drawer-less; Back goes to the view model, which leaves the group
 * before the screen pops. Nothing here runs unless the screen is on the stack.
 */
@Composable
internal fun WatchTogetherScreen(
    itemId: String?,
    resumeTicks: Long?,
    isTop: Boolean,
    onExit: () -> Unit,
    onChooseTitle: () -> Unit,
) {
    val context = LocalContext.current
    val strings = rememberUiStrings()
    val held = itemId?.let { HeldItem(it, resumeTicks ?: 0L) }
    val factory = remember { WatchTogetherViewModel.factory(held, context) }
    val viewModel: WatchTogetherViewModel = viewModel(factory = factory)
    val state by viewModel.state.collectAsState()
    val latestExit by rememberUpdatedState(onExit)

    ActiveWhileTop(isTop, viewModel::setActive)
    // docs/28 §1 (Q6): a title chosen above the lobby arrives as this entry's new item.
    LaunchedEffect(held) { held?.let { viewModel.send(TogetherEvent.Hold(it)) } }
    BackHandler(enabled = isTop) { viewModel.send(TogetherEvent.Back) }

    val done = state as? TogetherState.Done
    LaunchedEffect(done, isTop) {
        if (done == null || !isTop) return@LaunchedEffect
        // LineShown re-keys this effect with lineShown set, so only that rerun exits: once.
        if (!done.lineShown) {
            outcomeText(strings, done.line)?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
            viewModel.send(TogetherEvent.LineShown)
            return@LaunchedEffect
        }
        latestExit()
    }

    val member = state as? TogetherState.Member
    // docs/28 §1: the account name marks our own row, so only a member screen reads it, once.
    val inGroup = member != null
    val account by produceState<String?>(null, inGroup) { if (inGroup) value = viewModel.ownName() }
    // docs/28 §1: only a member screen reads its executor, and only while composed.
    val lobby = member?.let { key(it.exec) { lobbyView(it.exec.state.collectAsState().value, it.origin, it.memory, account, it.leaving) } }
    // A count change restarts the wait, so each live notice gets its own turn.
    LaunchedEffect(member?.exec, lobby?.pendingNotices, isTop) {
        if ((lobby?.pendingNotices ?: 0) == 0 || !isTop) return@LaunchedEffect
        delay(NOTICE_SHOWN_MS)
        viewModel.send(TogetherEvent.NoticeShown)
    }

    // docs/28 §1 (R3): one mirror lookup per title the card shows, and the account name only for a guest.
    val cardItem = lobby?.cardItem?.takeIf { lobby.phase == LobbyPhase.Members }
    val loaded by produceState<Card?>(null, cardItem) { value = cardItem?.let { viewModel.card(it) } }
    val card = loaded?.takeIf { it.id == cardItem }
    val timeFormat = remember(context) { android.text.format.DateFormat.getTimeFormat(context) }
    val time: (Long) -> String = { timeFormat.format(Date(it)) }

    // docs/28 §1: a room being joined keeps the list up until its lobby or title arrives.
    val roomsView = roomsPane(state, lobby?.let { it.phase == LobbyPhase.Starting || it.transit } ?: false)
    val pane = if (roomsView != null) Pane.Rooms else when (state) {
        is TogetherState.Idle -> Pane.Idle
        is TogetherState.Rooms -> Pane.Rooms
        is TogetherState.Entering -> Pane.Entering
        is TogetherState.CreateRefused -> Pane.Refused
        is TogetherState.Member -> Pane.Lobby
        is TogetherState.Done -> Pane.Done
    }
    // docs/15 §2: each pane is a fresh landing on its primary, the lobby's first button once it appears.
    val memory = rememberFocusMemory("watch-together", pane)
    val listed = (roomsView?.rooms?.list as? RoomList.Listed)?.rooms.orEmpty()
    val primary = when {
        pane == Pane.Lobby && lobby != null -> lobbyButtons(lobby).first().key
        listed.isNotEmpty() -> roomKey(listed.first().groupId)
        else -> pane.primary
    }
    FocusRestorer(
        memory = memory,
        isTop = isTop,
        // docs/15 §2: the room list lands once it has an answer, on its first room; an empty list has no target.
        ready = roomsView?.rooms?.list != RoomList.Loading,
        selectedKey = { primary },
        fallback = { memory.target(primary) },
        tag = "watchTogether",
        reloadKey = focusReloadKey(pane, listed.isEmpty(), primary),
    )
    val send: (TogetherEvent) -> Unit = viewModel::send

    Box(modifier = Modifier.fillMaxSize().background(JellybeamTheme.LobbyGround)) {
        card?.let { Backdrop(it) }
        Column(
            modifier = Modifier.padding(start = 64.dp, top = 64.dp).width(if (roomsView != null) 650.dp else 500.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (roomsView != null) {
                RoomsPane(roomsView, isTop, strings, memory, send)
            } else when (val s = state) {
                is TogetherState.Idle, is TogetherState.Done, is TogetherState.Rooms -> Unit
                // docs/28 §1: a join keeps its room list up, so only a create reaches this pane.
                is TogetherState.Entering -> Entering(memory, send)
                is TogetherState.CreateRefused -> Refused(memory, send)
                is TogetherState.Member -> if (lobby != null) Lobby(lobby, strings, time, memory, send, onChooseTitle)
            }
        }
        roomsView?.let { RoomsKeyHint(it, Modifier.align(Alignment.BottomEnd).padding(end = 64.dp, bottom = 36.dp)) }
        if (card != null && lobby != null && roomsView == null) {
            TitleCard(card, stringResource(cardCaption(lobby)), strings, Modifier.align(Alignment.BottomEnd).padding(end = 64.dp, bottom = 48.dp))
        }
    }
}

@Composable
private fun Entering(memory: FocusMemory, send: (TogetherEvent) -> Unit) {
    Eyebrow(R.string.wt_role_hosting)
    Heading(stringResource(R.string.wt_host_title))
    Log(LogLine(stringResource(R.string.wt_starting).uppercaseUi(), LogTone.Waiting))
    Pill(stringResource(R.string.wt_cancel), { send(TogetherEvent.Back) }, Modifier.focusKey(memory, "cancel"))
}

@Composable
private fun Refused(memory: FocusMemory, send: (TogetherEvent) -> Unit) {
    Eyebrow(R.string.wt_role_hosting)
    Heading(stringResource(R.string.wt_notice_create_refused))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Pill(stringResource(R.string.wt_stop), { send(TogetherEvent.Stop) }, Modifier.focusKey(memory, "stop"), primary = true)
        Pill(stringResource(R.string.wt_cancel), { send(TogetherEvent.Cancel) }, Modifier.focusKey(memory, "cancel"))
    }
}

@Composable
private fun Lobby(
    lobby: LobbyView,
    strings: UiStrings,
    time: (Long) -> String,
    memory: FocusMemory,
    send: (TogetherEvent) -> Unit,
    onChooseTitle: () -> Unit,
) {
    Eyebrow(lobbyRole(lobby))
    Heading(lobbyHeading(lobby).text(strings))
    lobby.name?.let { RoomName(it) }
    // docs/28 §1 (late join): a joiner waiting for the playing title shows no members yet.
    if (lobby.phase == LobbyPhase.Members && !lobby.transit) MemberList(memberRows(lobby.members, lobby.hostName, lobby.self))
    logLine(strings, lobby, time)?.let { Log(it) }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        lobbyButtons(lobby).forEachIndexed { index, button ->
            val modifier = Modifier.focusKey(memory, button.key)
            when (button) {
                is LobbyButton.Start ->
                    Pill(button.label.text(strings), { send(TogetherEvent.Start(button.ticks)) }, modifier, inert = !lobby.startActive, primary = index == 0)
                LobbyButton.Choose -> Pill(stringResource(R.string.wt_choose_title), onChooseTitle, modifier, primary = index == 0)
                is LobbyButton.Exit -> Pill(button.label.text(strings), { send(TogetherEvent.Back) }, modifier)
            }
        }
    }
}

/** The title's backdrop on the right, under a left and a bottom scrim; no backdrop leaves the plain ground. */
@Composable
private fun Backdrop(card: Card) {
    val source = CardFormatting.backdropArtSource(card)
    if (source == ArtSource.None) return
    val ground = JellybeamTheme.LobbyGround
    Box(modifier = Modifier.fillMaxSize()) {
        CardArtImage(
            source = source,
            imageUrl = { itemId, kind, tag -> AppGraph.gateway.imageUrl(itemId, kind, tag, BACKDROP_WIDTH_PX) },
            itemName = card.name,
            contentAlpha = 1f,
            modifier = Modifier.align(Alignment.CenterEnd).width(650.dp).fillMaxHeight(),
            blurhash = card.blurhash,
            placeholderDimAlpha = BACKDROP_PLACEHOLDER_DIM,
            aspect = WIDE_ART_ASPECT,
            fadeFromDisk = true,
        )
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to ground, 0.42f to ground, 0.7f to ground.copy(alpha = 0.75f), 1f to ground.copy(alpha = 0.35f)),
            ),
        )
        Box(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(150.dp).background(Brush.verticalGradient(listOf(Color.Transparent, ground))))
    }
}

@Composable
private fun TitleCard(card: Card, caption: String, strings: UiStrings, modifier: Modifier) {
    val poster = CardFormatting.posterArtSource(card)
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Mono(caption, 8.5.sp, JellybeamTheme.Grigio)
            // Server-configured names, verbatim.
            BasicText(
                card.name,
                modifier = Modifier.widthIn(max = 360.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = JellybeamTheme.Archivo, fontWeight = FontWeight.ExtraBold, color = JellybeamTheme.Panna, fontSize = 20.sp),
            )
            CardFormatting.detailMetaLine(strings, card.productionYear, card.runtimeTicks)?.let { Body(it, 12.sp, JellybeamTheme.Panna2) }
        }
        if (poster != ArtSource.None) {
            Box(modifier = Modifier.size(75.dp, 112.dp).clip(RoundedCornerShape(3.dp))) {
                CardArtImage(
                    source = poster,
                    imageUrl = { itemId, kind, tag -> AppGraph.gateway.imageUrl(itemId, kind, tag, POSTER_WIDTH_PX) },
                    itemName = card.name,
                    contentAlpha = 1f,
                    modifier = Modifier.fillMaxSize(),
                    blurhash = card.blurhash,
                )
            }
        }
    }
}

@Composable
private fun Eyebrow(role: Int) = Eyebrow(stringResource(role))

/** `WATCH TOGETHER │ [role]`, the mono line every pane opens with. */
@Composable
internal fun Eyebrow(role: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Mono(stringResource(R.string.wt_eyebrow), 11.sp, JellybeamTheme.Pistacchio)
        Mono("│", 11.sp, JellybeamTheme.Hairline)
        Mono(role, 11.sp, JellybeamTheme.Panna2)
    }
}

@Composable
internal fun Heading(text: String) {
    BasicText(
        text,
        style = TextStyle(fontFamily = JellybeamTheme.Archivo, fontWeight = FontWeight.ExtraBold, color = JellybeamTheme.Panna, fontSize = 28.sp, lineHeight = 1.08.em),
    )
}

@Composable
internal fun Body(text: String, size: TextUnit, color: Color, modifier: Modifier = Modifier) {
    BasicText(text, modifier = modifier, style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = color, fontSize = size))
}

@Composable
internal fun Mono(text: String, size: TextUnit, color: Color, modifier: Modifier = Modifier) {
    BasicText(text, modifier = modifier, maxLines = 1, style = TextStyle(fontFamily = JellybeamTheme.MartianMono, color = color, fontSize = size, letterSpacing = MONO_TRACKING))
}

@Composable
private fun Log(line: LogLine) {
    val color = when (line.tone) {
        LogTone.Waiting -> JellybeamTheme.Grigio
        LogTone.Event -> JellybeamTheme.Panna2
        LogTone.Alert -> JellybeamTheme.Ambra
    }
    Mono(line.text, 9.5.sp, color)
}

@Composable
private fun MemberList(rows: List<MemberRow>) {
    Column(modifier = Modifier.width(280.dp)) {
        Mono(stringResource(R.string.wt_members, rows.size), 8.5.sp, JellybeamTheme.Grigio, Modifier.padding(bottom = 6.dp))
        rows.forEach { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind { drawLine(JellybeamTheme.RuleFaint, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Server user names, verbatim.
                BasicText(
                    row.name,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = JellybeamTheme.Archivo, fontWeight = FontWeight.SemiBold, color = JellybeamTheme.Panna, fontSize = 14.sp),
                )
                Mono(stringResource(row.tag.label), 8.5.sp, if (row.own) JellybeamTheme.Pistacchio else JellybeamTheme.Grigio)
            }
        }
    }
}

/** docs/28 §1: the room's name, verbatim, in one line. */
@Composable
private fun RoomName(name: String) {
    BasicText(
        name,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = TextStyle(fontFamily = JellybeamTheme.Archivo, fontWeight = FontWeight.SemiBold, color = JellybeamTheme.Panna, fontSize = 16.sp),
    )
}

/**
 * docs/15 §4: [inert] keeps the pill focusable but ignores Select, since a disabled focused node drops focus.
 * Focus draws a Sheen [focusRing] outside the pill; a primary pill adds a ground-coloured gap inside it.
 */
@Composable
private fun Pill(label: String, onClick: () -> Unit, modifier: Modifier, inert: Boolean = false, primary: Boolean = false) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val fill = if (primary) JellybeamTheme.Pistacchio else Color.Transparent
    Box(
        modifier = modifier
            .focusRing(focused, 50.dp, JellybeamTheme.Sheen, width = PILL_RING, gap = if (primary) PILL_RING else 0.dp)
            .background(fill, PILL_SHAPE)
            .then(if (primary) Modifier else Modifier.border(0.5.dp, JellybeamTheme.HairlineStrong, PILL_SHAPE))
            .clickable(interactionSource = interactionSource, indication = null, onClick = { if (!inert) onClick() })
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        BasicText(
            label,
            style = TextStyle(
                fontFamily = JellybeamTheme.Archivo,
                fontWeight = FontWeight.SemiBold,
                color = when {
                    primary -> JellybeamTheme.Notte
                    inert -> JellybeamTheme.Grigio
                    else -> JellybeamTheme.Panna
                },
                fontSize = 14.sp,
            ),
        )
    }
}
