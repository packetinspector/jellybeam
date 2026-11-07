package tv.jellybeam.ui.music

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import tv.jellybeam.AppGraph
import tv.jellybeam.JellybeamTheme
import tv.jellybeam.LocalAccent
import tv.jellybeam.R
import tv.jellybeam.i18n.rememberUiStrings
import tv.jellybeam.i18n.uppercaseUi
import tv.jellybeam.player.music.MusicSource
import tv.jellybeam.ui.cards.focusRing
import tv.jellybeam.ui.common.ActiveWhileTop
import tv.jellybeam.ui.focus.FocusMemory
import tv.jellybeam.ui.focus.FocusRestorer
import tv.jellybeam.ui.focus.LocalFocusGate
import tv.jellybeam.ui.focus.LocalFocusSeat
import tv.jellybeam.ui.focus.asFocusTarget
import tv.jellybeam.ui.focus.focusKey
import tv.jellybeam.ui.focus.placeByKeys
import tv.jellybeam.ui.focus.rememberFocusMemory
import tv.jellybeam.ui.focus.requestFocusWithRetry
import tv.jellybeam.ui.focus.withPlacement
import uniffi.jellybeam_core.Card
import uniffi.jellybeam_core.MixSeedFfi
import uniffi.jellybeam_core.PlaylistEntry
import uniffi.jellybeam_core.SpecLineContext
import uniffi.jellybeam_core.musicSpecLine

private val COVER = 232.dp
private val RIGHT_START = 304.dp
private val RIGHT_WIDTH = 628.dp

/**
 * docs/29 §9 C (C1–C3, S1, S3): the cover, spec capsule and genre stay fixed on the left; only the
 * track list scrolls. Focus opens on the playing track's row when it is on this album, else on Play.
 */
@Composable
internal fun MusicAlbumScreen(
    albumId: String,
    isTop: Boolean,
    nav: MusicNav,
    viewModel: MusicAlbumViewModel = viewModel(
        factory = MusicAlbumViewModel.Factory(AppGraph.gateway, albumId) { musicSpecLine(it, SpecLineContext.ALBUM_CAPSULE) },
    ),
    menuModel: MusicMenuViewModel = rememberMusicMenu(),
) {
    val state by viewModel.state.collectAsState()
    val menu by menuModel.state.collectAsState()
    val mix = rememberInstantMix(MixSeedFfi.Album(albumId), isTop)
    ActiveWhileTop(isTop, viewModel::setActive)
    val strings = rememberUiStrings()
    val playing = rememberPlayingTrack()
    val memory = rememberFocusMemory(albumId)
    val playRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val thumbUrl = rememberMusicImageUrl(32.dp)
    val album = state.album
    val compilation = remember(album?.albumArtist, state.tracks) { isCompilation(album?.albumArtist, state.tracks) }
    val entries = remember(state.tracks) { albumListEntries(state.tracks) }
    memory.scrollTo = { key -> scrollToTrack(key, entries, listState) }
    FocusRestorer(
        memory = memory,
        isTop = isTop,
        ready = state.load != MusicLoad.Loading,
        selectedKey = { (albumOpenFocus(playing?.itemId, state.tracks) as? AlbumOpenFocus.Track)?.let { itemKey(it.itemId) } },
        fallback = { memory.target(KEY_RETRY_PAGE) ?: playRequester.asFocusTarget() },
        tag = "music-album",
    )
    PagedList(listState, state.tracks.size, viewModel::loadMore)

    MusicPageFrame(
        load = state.load,
        onRetry = viewModel::retry,
        memory = memory,
        cover = { if (album != null) SquareArt(albumArt(album), rememberMusicImageUrl(COVER), album.name, Modifier.size(COVER).clip(RoundedCornerShape(2.dp))) },
        underCover = {
            state.specLine?.let { HairlineChip(it, mono = true) }
            state.genre?.let { HairlineChip(it, mono = false) }
        },
        eyebrow = album?.let { a ->
            mediumDate(a.dateCreated, deviceZone())?.let { stringResource(R.string.music_eyebrow_added, stringResource(R.string.music_eyebrow_album), it) }
                ?: stringResource(R.string.music_eyebrow_album)
        },
        title = album?.name,
        link = album?.albumArtist?.ifEmpty { null }?.let { name -> PageLink(name, album?.albumArtistId?.let { id -> { nav.openArtist(id) } }) },
        headerLine = albumHeaderLine(album?.productionYear, state.summary, strings),
        actions = PageActions(
            playLabel = stringResource(R.string.music_play),
            isFavorite = state.isFavorite,
            onPlay = { nav.play(MusicSource.Album(albumId), 0) },
            onShuffle = { nav.shuffle(MusicSource.Album(albumId)) },
            onInstantMix = { nav.instantMix(MixSeedFfi.Album(albumId)) },
            onFavorite = viewModel::toggleFavorite,
            onMore = { album?.let { menuModel.open(MusicMenuTarget(it, MusicMenuKind.Album), KEY_PAGE_MORE) } },
            mix = mix,
        ),
        playRequester = playRequester,
        panel = {
            MusicActionPanel(
                state = menu,
                controls = menuModel.controls(),
                nav = nav,
                isTop = isTop,
                memory = memory,
                modifier = Modifier.align(Alignment.TopEnd),
                pageFavorite = PageFavorite(albumId, state.isFavorite, viewModel::toggleFavorite),
            )
        },
        rows = {
            LazyColumn(state = listState, contentPadding = PaddingValues(top = RING_CLEAR_TOP, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                itemsIndexed(entries, key = { _, e -> entryKey(e) }, contentType = { _, e -> if (e is AlbumListEntry.Disc) "disc" else "track" }) { _, entry ->
                    when (entry) {
                        is AlbumListEntry.Disc -> DiscHeader(entry.number)
                        is AlbumListEntry.Track -> TrackRow(
                            data = TrackRowData(
                                title = entry.card.name,
                                duration = trackDurationLabel(entry.card),
                                number = trackNumberLabel(entry.card.indexNumber),
                                artist = if (compilation) musicArtistLine(entry.card) else null,
                                hasLyrics = entry.card.hasLyrics,
                                isFavorite = entry.card.isFavorite,
                            ),
                            layout = TrackRowLayout.OneLine,
                            mark = playing.markFor(entry.card.id),
                            imageUrl = thumbUrl,
                            onClick = { nav.play(MusicSource.Album(albumId), entry.index) },
                            onMore = { menuModel.open(MusicMenuTarget(entry.card, MusicMenuKind.Song), itemKey(entry.card.id)) },
                            // docs/29 §9 C1: Left from a row does nothing.
                            modifier = Modifier.focusKey(memory, itemKey(entry.card.id)).focusProperties { left = FocusRequester.Cancel },
                        )
                    }
                }
            }
        },
    )
}

/**
 * docs/29 §9 E1/E4: the album frame with the server collage as the cover; rows numbered by playlist
 * position, two-line with their own art. Reordering swaps the action row for the REORDERING banner.
 */
@Composable
internal fun MusicPlaylistScreen(
    playlist: Card,
    isTop: Boolean,
    nav: MusicNav,
    viewModel: MusicPlaylistViewModel = viewModel(factory = MusicPlaylistViewModel.Factory(AppGraph.gateway, playlist)),
    menuModel: MusicMenuViewModel = rememberMusicMenu(),
) {
    val state by viewModel.state.collectAsState()
    val menu by menuModel.state.collectAsState()
    val mix = rememberInstantMix(MixSeedFfi.Playlist(playlist.id), isTop)
    val reorder = state.reorder
    val entries = remember(reorder) { reorder.shown() }
    val rowKeys = remember(entries) { playlistRowKeys(entries.map { it.entryId }) }
    val held = reorder.heldEntry()
    val reordering = reorder.mode != ReorderMode.Off
    val songs = remember(entries) { entries.map { it.card } }
    ActiveWhileTop(isTop, viewModel::setActive)
    val strings = rememberUiStrings()
    val playing = rememberPlayingTrack()
    val memory = rememberFocusMemory(playlist.id)
    val playRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val thumbUrl = rememberMusicImageUrl(32.dp)
    val columns = SongColumns(SongLine2.ArtistAlbum, extra = null, rail = false, plays = false)
    memory.scrollTo = { key ->
        val index = rowKeys.indexOf(key)
        if (index >= 0) listState.scrollToItem(index)
        index >= 0
    }
    FocusRestorer(memory, isTop, ready = state.load != MusicLoad.Loading, fallback = { memory.target(KEY_RETRY_PAGE) ?: playRequester.asFocusTarget() }, tag = "music-playlist")
    PagedList(listState, entries.size, viewModel::loadMore)
    BackHandler(enabled = isTop && reordering) { viewModel.reorder(ReorderEvent.Back) }
    // docs/29 §9 E4: the action row (and the ··· that opened the menu) is gone while reordering, so focus starts on the first row.
    // docs/15 §4: the focused ··· left with the action row, so only a gated placement re-enters the layer.
    val latestEntries by rememberUpdatedState(entries)
    val latestRowKeys by rememberUpdatedState(rowKeys)
    val focusGate = LocalFocusGate.current
    LaunchedEffect(reordering) {
        if (!reordering) return@LaunchedEffect
        latestRowKeys.firstOrNull()?.let { memory.withPlacement { memory.placeByKeys(listOf(it), focusGate) } }
    }
    // docs/15 §3: a removed row takes focus with it, so the row that took its place (else the one before, else Play) takes it once the re-read lands.
    var removal by remember { mutableStateOf<Pair<List<String>, String>?>(null) }
    val seat = LocalFocusSeat.current
    LaunchedEffect(entries) {
        val (before, removed) = removal ?: return@LaunchedEffect
        val now = rowKeys
        if (removed in now) return@LaunchedEffect
        removal = null
        withFrameNanos { }
        if (memory.focusedKey != null || seat?.windowHasFocus?.invoke() == true) return@LaunchedEffect
        memory.withPlacement {
            val placed = memory.placeByKeys(listOfNotNull(keyAfterRemoval(before, removed, now)), focusGate)
            if (placed == null) requestFocusWithRetry(focusGate) { playRequester.asFocusTarget().requestFocus() }
        }
    }
    FollowHeldRow(listState, entries.indexOfFirst { it.entryId == held }.takeIf { held != null })
    LaunchedEffect(reorder.failed) {
        if (reorder.failed == null) return@LaunchedEffect
        delay(REORDER_NOTICE_MS)
        viewModel.reorder(ReorderEvent.NoticeShown)
    }

    MusicPageFrame(
        load = state.load,
        onRetry = viewModel::retry,
        memory = memory,
        cover = { SquareArt(albumArt(playlist), rememberMusicImageUrl(COVER), playlist.name, Modifier.size(COVER).clip(RoundedCornerShape(2.dp))) },
        underCover = {},
        eyebrow = stringResource(R.string.music_eyebrow_playlist),
        title = playlist.name,
        link = null,
        headerLine = playlistHeaderLine(songs, strings),
        actions = PageActions(
            playLabel = stringResource(R.string.music_play),
            isFavorite = state.isFavorite,
            onPlay = { nav.play(MusicSource.Playlist(playlist.id), 0) },
            onShuffle = { nav.shuffle(MusicSource.Playlist(playlist.id)) },
            onInstantMix = { nav.instantMix(MixSeedFfi.Playlist(playlist.id)) },
            onFavorite = viewModel::toggleFavorite,
            onMore = { menuModel.open(MusicMenuTarget(playlist, MusicMenuKind.Playlist), KEY_PAGE_MORE) },
            mix = mix,
        ),
        playRequester = playRequester,
        banner = if (reordering || reorder.failed != null) {
            { ReorderBanner(reorder.failed) }
        } else {
            null
        },
        panel = {
            MusicActionPanel(
                state = menu,
                controls = menuModel.controls { action ->
                    val keys = playlistRowKeys(latestEntries.map { it.entryId })
                    val gone = latestEntries.indexOfFirst { it.entryId == action.entryId }
                    if (gone >= 0) removal = keys to keys[gone]
                },
                nav = nav,
                isTop = isTop,
                memory = memory,
                modifier = Modifier.align(Alignment.TopEnd),
                pageFavorite = PageFavorite(playlist.id, state.isFavorite, viewModel::toggleFavorite),
                onReorder = { viewModel.reorder(ReorderEvent.Enter) }.takeIf { reorder.entries.size > 1 },
            )
        },
        rows = {
            LazyColumn(
                state = listState,
                modifier = Modifier.onPreviewKeyEvent { event -> reorderKey(reorder.mode, event, viewModel::reorder) },
                contentPadding = PaddingValues(top = RING_CLEAR_TOP, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                // Playlists may hold a song twice, so the row key is its entry, numbered when the server repeats one.
                itemsIndexed(entries, key = { i, _ -> rowKeys[i] }, contentType = { _, _ -> "song" }) { index, entry ->
                    val card = entry.card
                    val moving = entry.entryId == held
                    TrackRow(
                        data = songRowData(card, columns, strings, System.currentTimeMillis(), deviceZone(), number = (index + 1).toString()),
                        layout = TrackRowLayout.TwoLine,
                        mark = playing.markFor(card.id),
                        imageUrl = thumbUrl,
                        moving = moving,
                        onClick = {
                            if (reordering) viewModel.reorder(ReorderEvent.PickUp(index)) else nav.play(MusicSource.Playlist(playlist.id), index)
                        },
                        onMore = if (reordering) {
                            null
                        } else {
                            {
                                val context = MusicMenuContext.InPlaylist(playlist.id, entry.entryId)
                                menuModel.open(MusicMenuTarget(card, MusicMenuKind.Song, context), rowKeys[index])
                            }
                        },
                        modifier = Modifier
                            .padding(start = if (moving) REORDER_INDENT else 0.dp)
                            .focusKey(memory, rowKeys[index])
                            .focusProperties { left = FocusRequester.Cancel },
                    )
                }
            }
        },
    )
}

private val REORDER_INDENT = 16.dp
private const val REORDER_NOTICE_MS = 4_000L

/**
 * docs/29 §9 E4 keys while reordering: a held row takes Up/Down and drops on OK's release; a drop at the server
 * holds every key off; Left/Right never leave the list. Browsing rows pick up through their own OK.
 */
private fun reorderKey(mode: ReorderMode, event: KeyEvent, send: (ReorderEvent) -> Unit): Boolean {
    if (mode == ReorderMode.Off || mode == ReorderMode.Browsing && event.key != Key.DirectionLeft && event.key != Key.DirectionRight) return false
    val down = event.type == KeyEventType.KeyDown
    when (event.key) {
        Key.DirectionUp, Key.DirectionDown -> if (down && mode is ReorderMode.Holding) send(ReorderEvent.Step(if (event.key == Key.DirectionDown) 1 else -1))
        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> if (event.type == KeyEventType.KeyUp && mode is ReorderMode.Holding) send(ReorderEvent.Drop)
        Key.DirectionLeft, Key.DirectionRight -> Unit
        else -> return false
    }
    return true
}

/** A held row moves under focus, so the list keeps it in view. */
@Composable
private fun FollowHeldRow(listState: LazyListState, index: Int?) {
    LaunchedEffect(index) {
        if (index == null || index < 0) return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.none { it.index == index } || visible.first().index == index || visible.last().index == index) {
            listState.animateScrollToItem((index - 2).coerceAtLeast(0))
        }
    }
}

/** docs/29 §9 E4: mono `REORDERING │ ▲▼ MOVE │ OK DROP │ BACK CANCEL` where the action row was; a failed move names its song verbatim. */
@Composable
private fun ReorderBanner(failed: String?) {
    val accent = LocalAccent.current
    Row(Modifier.height(36.dp), verticalAlignment = Alignment.CenterVertically) {
        if (failed != null) {
            BasicText(stringResource(R.string.music_reorder_failed).uppercaseUi(), style = monoStyle(accent.accentInk, 11))
            BasicText(" $failed", maxLines = 1, overflow = TextOverflow.Ellipsis, style = archivo(JellybeamTheme.Panna2, 13.sp, FontWeight.Medium))
            return@Row
        }
        BasicText(stringResource(R.string.music_reordering).uppercaseUi(), style = monoStyle(accent.accentInk, 11))
        BasicText("│", Modifier.padding(horizontal = 14.dp), style = monoStyle(JellybeamTheme.Hairline, 11))
        BasicText(stringResource(R.string.music_reorder_keys).uppercaseUi(), maxLines = 1, style = monoStyle(JellybeamTheme.Panna2, 11))
    }
}

/** The album artist link: focusable only when its page is known. */
internal class PageLink(val name: String, val onOpen: (() -> Unit)?)

internal const val KEY_RETRY_PAGE = "retry"

/**
 * docs/29 §9 C/E1's two-column frame: the fixed left column ([cover], [underCover]) and the right
 * column's header, actions and independently scrolling [rows]. Loading draws the final geometry;
 * a failed first load is the unreachable state with Retry. A [banner] stands in for the action row.
 */
@Composable
internal fun MusicPageFrame(
    load: MusicLoad,
    onRetry: () -> Unit,
    memory: FocusMemory,
    cover: @Composable () -> Unit,
    underCover: @Composable () -> Unit,
    eyebrow: String?,
    title: String?,
    link: PageLink?,
    headerLine: String,
    actions: PageActions,
    playRequester: FocusRequester,
    banner: (@Composable () -> Unit)? = null,
    panel: @Composable BoxScope.() -> Unit = {},
    rows: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(JellybeamTheme.Notte)) {
        when (load) {
            MusicLoad.Loading -> PageSkeleton()
            is MusicLoad.Failed -> MusicUnreachable(
                host = load.host,
                detail = lastTriedLine(load.atMs),
                onRetry = onRetry,
                retryModifier = Modifier.focusKey(memory, KEY_RETRY_PAGE),
                modifier = Modifier.padding(top = 180.dp),
            )
            MusicLoad.Ready -> FadeInBlock {
                Box(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(start = MUSIC_PAGE_START, top = 40.dp).width(COVER), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        cover()
                        Spacer(Modifier.height(8.dp))
                        underCover()
                    }
                    Column(Modifier.padding(start = RIGHT_START, top = 42.dp).width(RIGHT_WIDTH)) {
                        eyebrow?.let { MonoLine(it, LocalAccent.current.accentInk) }
                        Spacer(Modifier.height(6.dp))
                        title?.let { PageTitle(it, 28) }
                        if (link != null) ArtistLink(link)
                        Spacer(Modifier.height(6.dp))
                        if (headerLine.isNotEmpty()) BasicText(headerLine, maxLines = 1, style = monoStyle(JellybeamTheme.Panna2, 11))
                        Spacer(Modifier.height(14.dp))
                        if (banner != null) banner() else MusicActionRow(actions, playModifier = Modifier.focusRequester(playRequester), moreModifier = Modifier.focusKey(memory, KEY_PAGE_MORE))
                        // The list's own top padding makes up the 16 dp, so its first row's ring is inside the clip.
                        Spacer(Modifier.height(16.dp - RING_CLEAR_TOP))
                        rows()
                    }
                }
            }
        }
        panel()
    }
}

/** docs/29 §9 C: the album artist as a focusable link with a chevron, ringed on focus. */
@Composable
internal fun ArtistLink(link: PageLink, modifier: Modifier = Modifier.padding(top = 4.dp), textSp: Int = 15, height: androidx.compose.ui.unit.Dp = 24.dp) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val ink = PannaB3
    val base = modifier
        .height(height)
        .focusRing(focused, cornerRadius = 12.dp, color = LocalAccent.current.accentSheen)
    val target = link.onOpen?.let { open -> base.clickable(interactionSource = interaction, indication = null, onClick = open) } ?: base
    Row(target.padding(start = 0.dp, end = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        BasicText(link.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = archivo(ink, textSp.sp, FontWeight.Medium))
        if (link.onOpen != null) {
            Canvas(Modifier.size((textSp * 2 / 3).dp)) {
                val w = size.width
                drawLine(ink, Offset(w * 0.35f, w * 0.2f), Offset(w * 0.7f, w * 0.5f), w * 0.14f, StrokeCap.Round)
                drawLine(ink, Offset(w * 0.7f, w * 0.5f), Offset(w * 0.35f, w * 0.8f), w * 0.14f, StrokeCap.Round)
            }
        }
    }
}

/** docs/29 §10 S1: the album frame's final geometry in SURFACE blocks; rows reserve six. */
@Composable
private fun PageSkeleton() {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = MUSIC_PAGE_START, top = 40.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MusicSkeleton(COVER, COVER)
            Spacer(Modifier.height(8.dp))
            MusicSkeleton(150.dp, 26.dp, radius = 13.dp)
            MusicSkeleton(100.dp, 24.dp, radius = 12.dp)
        }
        Column(Modifier.padding(start = RIGHT_START, top = 44.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            MusicSkeleton(180.dp, 11.dp)
            MusicSkeleton(320.dp, 28.dp, radius = 3.dp)
            MusicSkeleton(130.dp, 15.dp)
            MusicSkeleton(170.dp, 11.dp)
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MusicSkeleton(96.dp, 36.dp, radius = 18.dp)
                MusicSkeleton(110.dp, 36.dp, radius = 18.dp)
                MusicSkeleton(139.dp, 36.dp, radius = 18.dp)
                MusicSkeleton(36.dp, 36.dp, radius = 18.dp)
                MusicSkeleton(36.dp, 36.dp, radius = 18.dp)
            }
            Spacer(Modifier.height(16.dp))
            repeat(skeletonRowCount(null)) {
                Row(Modifier.height(44.dp).padding(start = 26.dp), verticalAlignment = Alignment.CenterVertically) {
                    MusicSkeleton(14.dp, 11.dp)
                    Spacer(Modifier.width(18.dp))
                    MusicSkeleton(190.dp, 14.dp)
                }
            }
        }
    }
}

/** Loads the next page as the last rows come into view. */
@Composable
internal fun PagedList(listState: LazyListState, size: Int, loadMore: () -> Unit) {
    LaunchedEffect(listState, size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .filter { it >= 0 && it >= size - 12 }
            .collect { loadMore() }
    }
}

private fun entryKey(entry: AlbumListEntry): String = when (entry) {
    is AlbumListEntry.Disc -> "disc:${entry.number}"
    is AlbumListEntry.Track -> itemKey(entry.card.id)
}

private suspend fun scrollToTrack(key: String, entries: List<AlbumListEntry>, listState: LazyListState): Boolean {
    val index = entries.indexOfFirst { it is AlbumListEntry.Track && itemKey(it.card.id) == key }
    if (index >= 0) listState.scrollToItem(index)
    return index >= 0
}
