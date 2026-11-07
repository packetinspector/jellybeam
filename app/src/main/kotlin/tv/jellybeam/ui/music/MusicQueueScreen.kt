package tv.jellybeam.ui.music

import androidx.compose.foundation.ExperimentalFoundationApi
import tv.jellybeam.ui.focus.ringClearBringIntoViewSpec
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import tv.jellybeam.AppGraph
import tv.jellybeam.JellybeamTheme
import tv.jellybeam.LocalAccent
import tv.jellybeam.R
import tv.jellybeam.i18n.rememberUiStrings
import tv.jellybeam.i18n.uppercaseUi
import tv.jellybeam.player.music.MusicEvent
import tv.jellybeam.player.music.MusicNow
import tv.jellybeam.player.music.MusicPlaybackOwner
import tv.jellybeam.player.music.RepeatMode
import tv.jellybeam.player.music.TransportOp
import tv.jellybeam.player.music.queueReorder
import tv.jellybeam.ui.cards.focusRing
import tv.jellybeam.ui.common.ActiveWhileTop
import tv.jellybeam.ui.focus.FocusMemory
import tv.jellybeam.ui.focus.FocusRestorer
import tv.jellybeam.ui.focus.asFocusTarget
import tv.jellybeam.ui.focus.focusKey
import tv.jellybeam.ui.focus.rememberFocusMemory

private val QUEUE_START = 40.dp
private val QUEUE_ART = 160.dp
private val QUEUE_STRIP = 280.dp
private val LIST_START = 380.dp
private val MOVE_INDENT = 16.dp

private const val KEY_SHUFFLE = "queue-shuffle"
private const val KEY_REPEAT = "queue-repeat"
private const val KEY_CLEAR = "queue-clear"

/** A row picked up for Move (docs/29 §9 H2): its play position when picked, and where it would drop now. */
private data class Picked(val from: Int, val at: Int)

/**
 * docs/29 §9 H (H1/H2): full screen, no spine and no music bar. Left: counts, art, strip, the shuffle and
 * repeat toggles and Clear queue; right: Now playing, what the user queued, then the rest of the source.
 * OK on a row plays from there; long-press OK (or Menu) opens Play next · Move · Remove · Go to album.
 * Move picks the row up, Up/Down move it live, OK drops it and Back puts it back; the playing song never moves.
 */
@Composable
internal fun MusicQueueScreen(isTop: Boolean, nav: MusicNav, onExit: () -> Unit) {
    val owner by AppGraph.musicStarted
    val playing = owner
    if (playing == null) LeaveWhenTop(isTop, onExit) else QueueFor(playing, isTop, nav, onExit)
}

@Composable
private fun QueueFor(
    owner: MusicPlaybackOwner,
    isTop: Boolean,
    nav: MusicNav,
    onExit: () -> Unit,
    viewModel: MusicQueueViewModel = viewModel(factory = MusicQueueViewModel.Factory(AppGraph.gateway, owner)),
    menuModel: MusicMenuViewModel = rememberMusicMenu(),
) {
    ActiveWhileTop(isTop, viewModel::setActive)
    val named by viewModel.state.collectAsState()
    val flow by owner.state.collectAsState()
    val nowState by owner.now.collectAsState()
    val view = remember(flow, nowState) { musicQueueView(flow, nowState) }
    if (view == null) {
        LeaveWhenTop(isTop, onExit)
        return
    }
    val strings = rememberUiStrings()
    val memory = rememberFocusMemory("music-queue")
    var picked by remember { mutableStateOf<Picked?>(null) }
    val menu by menuModel.state.collectAsState()
    val now = view.now
    // The live preview while a row is picked up is the queue as the player will hold it after the drop.
    val shown = remember(now, picked) { picked?.let { p -> previewMove(now, p) } ?: now }
    val name = named?.second ?: SourceName.Unknown
    val model = remember(shown, name, strings) { queueScreenModel(shown, name, owner::card, strings) }
    val firstUpcoming = model.rows.filterIsInstance<QueueRow.Track>().firstOrNull { !it.isCurrent }?.key
    val currentKey = model.rows.filterIsInstance<QueueRow.Track>().firstOrNull { it.isCurrent }?.key
    // docs/29 §9 H: the open menu follows its row in the live queue; a row that left it closes the menu.
    val accounts by AppGraph.gateway.accountEpochs.collectAsState()
    val onCurrentAccount = flow.session?.epoch == accounts.epoch
    val liveTarget = menu.target?.let { liveQueueTarget(it, menu.returnKey, model.rows, onCurrentAccount) }
    val menuGone = menu.target != null && liveTarget == null
    LaunchedEffect(menuGone) { if (menuGone) menuModel.close() }
    FocusRestorer(
        memory,
        isTop,
        selectedKey = { firstUpcoming ?: currentKey },
        fallback = { memory.target(KEY_SHUFFLE) },
        tag = "music-queue",
    )

    val send: (TransportOp) -> Unit = remember(owner) { { op -> owner.send(MusicEvent.Transport(op)) } }
    BackHandler(enabled = picked != null) { picked = null }

    Box(Modifier.fillMaxSize().background(JellybeamTheme.Notte)) {
        QueueLeftColumn(view, model, owner, flow.session?.epoch, memory, send)
        QueueList(
            model = model,
            accountEpoch = flow.session?.epoch,
            picked = picked,
            owner = owner,
            memory = memory,
            onPlay = { row -> send(TransportOp.PlayAt(row.index)) },
            onMenu = { row ->
                owner.card(row.entry.item)?.let { song ->
                    queueMenuTarget(song, row, onCurrentAccount)?.let { menuModel.open(it, row.key) }
                }
            },
            onPickedMove = { delta ->
                picked = picked?.let { p -> p.copy(at = (p.at + delta).coerceIn(now.playOrder.indexOf(now.current) + 1, now.playOrder.lastIndex)) }
            },
            onDrop = {
                picked?.let { p -> if (p.at != p.from) send(TransportOp.Move(p.from, p.at)) }
                picked = null
            },
        )
        MusicActionPanel(
            state = if (liveTarget == menu.target) menu else menu.copy(target = liveTarget),
            controls = menuModel.controls(),
            nav = nav,
            isTop = isTop,
            memory = memory,
            modifier = Modifier.align(Alignment.TopEnd),
            queue = QueueRowActions(
                onPlayNext = { index, _ -> send(TransportOp.PlayNext(index)) },
                onMove = { position -> picked = Picked(position, position) },
                onRemove = { index -> send(TransportOp.Remove(index)) },
            ),
        )
    }
}

/** [now] with the row at play position [Picked.from] dropped at [Picked.at], as [queueReorder] will apply it. */
private fun previewMove(now: MusicNow, picked: Picked): MusicNow =
    queueReorder(now.queue.map { it.origin }, now.playOrder, now.current, now.shuffle, picked.from, picked.at)?.let { applyReorder(now, it) } ?: now

/** docs/29 §9 H1 left column. */
@Composable
private fun QueueLeftColumn(view: MusicQueueView, model: QueueScreenModel, owner: MusicPlaybackOwner, accountEpoch: ULong?, memory: FocusMemory, send: (TransportOp) -> Unit) {
    val accent = LocalAccent.current
    val now = view.now
    val song = owner.card(now.queue[now.current].item)
    val strings = rememberUiStrings()
    val np = remember(view, strings) { nowPlayingModel(view, owner::card, strings) }
    val position = rememberShownPosition(np.itemId, np.positionMs, np.durationMs, np.isPlaying)
    val latestNow by rememberUpdatedState(now)
    Column(Modifier.padding(start = QUEUE_START, top = 42.dp).width(QUEUE_STRIP)) {
        BasicText(stringResource(R.string.music_queue).uppercaseUi(), style = monoStyle(accent.accentInk, 11))
        BasicText(stringResource(R.string.music_up_next), Modifier.padding(top = 4.dp), style = archivo(JellybeamTheme.Panna, 28.sp))
        BasicText(model.countLine, Modifier.padding(top = 8.dp), maxLines = 1, style = monoStyle(JellybeamTheme.Panna2, 11))
        SquareArt(
            song?.let(::trackArtSource) ?: tv.jellybeam.ui.cards.ArtSource.None,
            rememberMusicImageUrl(QUEUE_ART, accountEpoch),
            song?.name.orEmpty(),
            Modifier.padding(top = 22.dp).size(QUEUE_ART).clip(RoundedCornerShape(2.dp)),
        )
        QueueStrip(
            durationsMs = np.stripDurationsMs,
            current = np.stripCurrent,
            progress = { if (np.durationMs > 0L) position.value.toFloat() / np.durationMs else 0f },
            modifier = Modifier.padding(top = 14.dp),
        )
        Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ToggleLine(ShuffleGlyph, now.shuffle, model.shuffleLine, null, Modifier.focusKey(memory, KEY_SHUFFLE)) {
                send(TransportOp.Shuffle(!latestNow.shuffle))
            }
            ToggleLine(RepeatGlyph, now.repeat != RepeatMode.Off, model.repeatLine, "1".takeIf { now.repeat == RepeatMode.One }, Modifier.focusKey(memory, KEY_REPEAT)) {
                send(TransportOp.Repeat(nextRepeat(latestNow.repeat)))
            }
            if (model.hasFollowing) {
                MusicPill(
                    stringResource(R.string.music_clear_queue),
                    glyph = null,
                    primary = false,
                    onClick = { send(TransportOp.Clear) },
                    modifier = Modifier.padding(top = 6.dp).focusKey(memory, KEY_CLEAR),
                )
            }
        }
    }
}

@Composable
private fun ToggleLine(glyph: MusicGlyph, on: Boolean, label: String, badge: String?, modifier: Modifier, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TransportButton(glyph, onClick, modifier, size = 36.dp, glyphSize = 16.dp, on = on, badge = badge)
        BasicText(label, style = monoStyle(if (on) JellybeamTheme.Panna else JellybeamTheme.Grigio, 10))
    }
}

/** Handlers for the list; [onPickedMove] steps a picked-up row by one. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QueueList(
    model: QueueScreenModel,
    /** The session's account, which the row art is read on. */
    accountEpoch: ULong?,
    picked: Picked?,
    owner: MusicPlaybackOwner,
    memory: FocusMemory,
    onPlay: (QueueRow.Track) -> Unit,
    onMenu: (QueueRow.Track) -> Unit,
    onPickedMove: (Int) -> Unit,
    onDrop: () -> Unit,
) {
    val listState = rememberLazyListState()
    val thumbUrl = rememberMusicImageUrl(32.dp, accountEpoch)
    val playing = rememberPlayingTrack()
    val latestPicked by rememberUpdatedState(picked)
    val latestMove by rememberUpdatedState(onPickedMove)
    val latestDrop by rememberUpdatedState(onDrop)
    val pickedIndex = picked?.let { p -> model.rows.indexOfFirst { it is QueueRow.Track && it.position == p.at } }
    // A picked-up row moves under focus, so the list follows it.
    LaunchedEffect(pickedIndex) {
        val index = pickedIndex ?: return@LaunchedEffect
        if (index < 0) return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.none { it.index == index } || visible.first().index == index || visible.last().index == index) {
            listState.animateScrollToItem((index - 2).coerceAtLeast(0))
        }
    }
    // Android TV title-safe: a focused row scrolls in with its ring and the 27 dp bottom strip clear (docs/15 §0.3).
    val clearPx = with(LocalDensity.current) { (RING_CLEAR_TOP + TV_SAFE_BOTTOM).toPx() }
    val ringSpec = remember(clearPx) { ringClearBringIntoViewSpec(clearPx) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides ringSpec) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .padding(start = LIST_START, top = 42.dp, end = 32.dp)
                .fillMaxSize()
                .onPreviewKeyEvent { event ->
                    if (latestPicked == null) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionUp, Key.DirectionDown -> {
                            if (event.type == KeyEventType.KeyDown) latestMove(if (event.key == Key.DirectionDown) 1 else -1)
                            true
                        }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            if (event.type == KeyEventType.KeyUp) latestDrop()
                            true
                        }
                        Key.DirectionLeft, Key.DirectionRight -> true
                        else -> false
                    }
                }
                .focusGroup(),
            // Android TV title-safe: a focused row's ring, drawn outside the row, still ends 27 dp above the edge.
            contentPadding = PaddingValues(bottom = TV_SAFE_BOTTOM + RING_CLEAR_TOP),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            items(model.rows, key = { row -> rowKey(row) }, contentType = { if (it is QueueRow.Header) "header" else "row" }) { row ->
                when (row) {
                    is QueueRow.Header -> BasicText(
                        row.text,
                        Modifier.padding(start = 10.dp, top = if (row === model.rows.first()) 0.dp else 13.dp, bottom = 5.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = monoStyle(JellybeamTheme.Grigio, 10),
                    )
                    is QueueRow.Track -> {
                        val song = owner.card(row.entry.item)
                        val moving = picked != null && row.position == picked.at
                        TrackRow(
                            data = TrackRowData(
                                title = song?.name.orEmpty(),
                                duration = song?.let(::trackDurationLabel).orEmpty(),
                                thumb = song?.let(::trackArtSource) ?: tv.jellybeam.ui.cards.ArtSource.None,
                                line2 = song?.let { listOfNotNull(musicArtistLine(it).ifEmpty { null }, it.album?.ifEmpty { null }).joinToString(" · ") },
                                extra = row.tag?.let { stringResource(if (it == QueueTag.PlayNext) R.string.music_play_next else R.string.music_tag_added).uppercaseUi() },
                            ),
                            layout = TrackRowLayout.TwoLine,
                            mark = if (row.isCurrent) playing.markFor(row.entry.item) else CurrentMark.None,
                            imageUrl = thumbUrl,
                            moving = moving,
                            onClick = { onPlay(row) },
                            onMore = { if (picked == null) onMenu(row) },
                            modifier = Modifier.padding(start = if (moving) MOVE_INDENT else 0.dp).focusKey(memory, row.key),
                        )
                    }
                }
            }
        }
    }
}

private fun rowKey(row: QueueRow): String = when (row) {
    is QueueRow.Header -> "h:${row.text}"
    is QueueRow.Track -> row.key
}

