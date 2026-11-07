package tv.jellybeam.ui.music

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.LocalDate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import tv.jellybeam.AppGraph
import tv.jellybeam.JellybeamTheme
import tv.jellybeam.LocalAccent
import tv.jellybeam.R
import tv.jellybeam.i18n.rememberUiStrings
import tv.jellybeam.player.music.MusicSource
import tv.jellybeam.ui.cards.ArtSource
import tv.jellybeam.ui.cards.focusRing
import tv.jellybeam.ui.common.ActiveWhileTop
import tv.jellybeam.ui.focus.FocusMemory
import tv.jellybeam.ui.focus.FocusRestorer
import tv.jellybeam.ui.focus.LocalFocusGate
import tv.jellybeam.ui.focus.LocalFocusSeat
import tv.jellybeam.ui.focus.asFocusTarget
import tv.jellybeam.ui.focus.focusKey
import tv.jellybeam.ui.focus.placeByKeys
import tv.jellybeam.ui.focus.refreshGuardEligible
import tv.jellybeam.ui.focus.rememberFocusMemory
import tv.jellybeam.ui.focus.rememberRefreshRestoreOwner
import tv.jellybeam.ui.focus.restoreNow
import tv.jellybeam.ui.focus.withPlacement
import tv.jellybeam.ui.library.IndexRailModel
import tv.jellybeam.ui.nav.LocalDrawerFocusCoordinator
import uniffi.jellybeam_core.ArtistScope
import uniffi.jellybeam_core.Card
import uniffi.jellybeam_core.GridSort
import uniffi.jellybeam_core.GridSortField
import uniffi.jellybeam_core.ImageKind
import uniffi.jellybeam_core.MusicArtistRow
import uniffi.jellybeam_core.SongSource
import uniffi.jellybeam_core.ViewSnapshot

private const val GRID_COLUMNS = 6
private const val GENRE_COLUMNS = 3
private val GRID_WIDTH = 848.dp

/**
 * docs/29 §9 A (A1–A5, S1–S3): one music library's landing. Tabs switch on OK; the list below is
 * the selected tab's, paged as it scrolls; the focused album or artist's facts sit top right.
 */
@Composable
internal fun MusicLibraryScreen(
    view: ViewSnapshot,
    isTop: Boolean,
    nav: MusicNav,
    viewModel: MusicLibraryViewModel = viewModel(factory = MusicLibraryViewModel.Factory(AppGraph.gateway, view, AppGraph.processScope)),
    menuModel: MusicMenuViewModel = rememberMusicMenu(),
) {
    val state by viewModel.state.collectAsState()
    val menu by menuModel.state.collectAsState()
    ActiveWhileTop(isTop, viewModel::setActive)
    val strings = rememberUiStrings()
    val imageUrl = rememberMusicImageUrl(SQUARE_ART)
    val playing = rememberPlayingTrack()
    val memory = rememberFocusMemory(view.id)
    val focusGate = LocalFocusGate.current
    val seat = LocalFocusSeat.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()
    val tabRequester = remember { FocusRequester() }
    var focusedId by remember { mutableStateOf<String?>(null) }
    var screenHasFocus by remember { mutableStateOf(false) }
    val latestIsTop by rememberUpdatedState(isTop)
    val refreshOwner = rememberRefreshRestoreOwner(isTop)
    val drawerFocusCoordinator = LocalDrawerFocusCoordinator.current
    if (drawerFocusCoordinator != null) {
        SideEffect {
            drawerFocusCoordinator.onDrawerOpened = { refreshOwner.ownershipLost() }
            drawerFocusCoordinator.onDrawerClosed = { false }
        }
    }

    val firstKey = state.items?.let { firstItemKey(it) }
    // docs/15 §2 rule 3: a fresh landing and a stale key land on the first row, else the selected tab.
    val fallback = { (firstKey?.let(memory::target) ?: tabRequester.asFocusTarget()) }
    memory.scrollTo = { key -> scrollToKey(key, state.items, gridState, listState) }
    FocusRestorer(memory, isTop, ready = state.load != MusicLoad.Loading && state.tab != null, fallback = fallback, tag = "music-library", reloadKey = state.tab)

    // docs/15 §3: a refresh that dropped the focused row re-places focus, never while focus sits elsewhere in the window (drawer, music bar).
    LaunchedEffect(state.items) {
        refreshOwner.onContentChanged(
            scope = scope,
            frame = { withFrameNanos {} },
            eligible = { own ->
                refreshGuardEligible(
                    isTop = latestIsTop,
                    seeded = memory.seeded,
                    resumed = lifecycleOwner.lifecycle.currentState == Lifecycle.State.RESUMED,
                    hasFocus = screenHasFocus,
                    focusElsewhere = seat?.focusElsewhere(screenHasFocus) == true,
                    frozenByOthers = memory.frozenBesides(own),
                )
            },
            restore = { memory.restoreNow(focusGate, fallback = fallback, tag = "music-library-refresh") },
        )
    }

    // docs/16 §4.6-style paging: the next page loads as the last rows come into view.
    LaunchedEffect(gridState, listState) {
        snapshotFlow { maxOf(gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1, listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) }
            .distinctUntilChanged()
            .filter { it >= 0 && it >= (viewModel.state.value.items?.size ?: 0) - GRID_COLUMNS * 3 }
            .collect { viewModel.loadMore() }
    }

    val tab = state.tab
    Box(
        Modifier
            .fillMaxSize()
            .background(JellybeamTheme.Notte)
            .onFocusChanged { screenHasFocus = it.hasFocus },
    ) {
        Column(Modifier.fillMaxSize().padding(start = MUSIC_PAGE_START, top = 20.dp)) {
            BasicText(stringResource(R.string.music_title), style = archivo(JellybeamTheme.Panna, 28.sp))
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                state.tabs.forEach { t ->
                    MusicTabPill(
                        label = stringResource(tabLabel(t)),
                        selected = t == tab,
                        onClick = { viewModel.selectTab(t) },
                        modifier = Modifier.focusKey(memory, "tab:${t.name}").then(if (t == tab) Modifier.focusRequester(tabRequester) else Modifier),
                    )
                }
                Box(Modifier.padding(start = 9.dp, end = 11.dp).size(Dp.Hairline, 18.dp).background(JellybeamTheme.Hairline))
                if (tab != null && state.load == MusicLoad.Ready) {
                    BasicText(musicSortStrip(tab, state.count, strings), maxLines = 1, style = monoStyle(JellybeamTheme.Panna2, 13))
                }
            }
            if (tab == MusicTab.Artists) {
                Spacer(Modifier.height(12.dp))
                ArtistScopeToggle(state.artistScope, viewModel::selectArtistScope, memory)
            }
            Spacer(Modifier.height(if (tab == MusicTab.Artists) 26.dp else 28.dp))
            when (val load = state.load) {
                MusicLoad.Loading -> TabSkeleton(tab)
                is MusicLoad.Failed -> MusicUnreachable(
                    host = load.host,
                    detail = lastTriedLine(load.atMs),
                    onRetry = viewModel::retry,
                    retryModifier = Modifier.focusKey(memory, KEY_RETRY),
                    modifier = Modifier.width(GRID_WIDTH).padding(top = 60.dp),
                )
                MusicLoad.Ready -> {
                    val items = state.items
                    if (items == null || items.size == 0 && tab != MusicTab.Playlists) {
                        MusicEmptyState(
                            line = stringResource(musicEmptyLine(tab ?: MusicTab.Albums)),
                            detail = tab?.let { musicSortStrip(it, 0L, strings) },
                            modifier = Modifier.width(GRID_WIDTH).padding(top = 40.dp),
                        )
                    } else {
                        FadeInBlock {
                            TabContent(
                                items = items,
                                view = view,
                                memory = memory,
                                gridState = gridState,
                                listState = listState,
                                imageUrl = imageUrl,
                                playing = playing,
                                strings = strings,
                                nav = nav,
                                onNewPlaylist = viewModel::openNewPlaylist,
                                onMore = { card -> menuModel.open(MusicMenuTarget(card, MusicMenuKind.Song), itemKey(card.id)) },
                                onFocused = { id ->
                                    focusedId = id
                                    if (items is TabItems.Albums) viewModel.albumFocused(id)
                                },
                            )
                        }
                    }
                }
            }
        }
        val facts = focusedFacts(state, focusedId, strings)
        if (facts != null) FactsBlock(facts, Modifier.align(Alignment.TopEnd).padding(top = 18.dp, end = MUSIC_PAGE_START))
        val rail = remember(state.groups, strings) {
            IndexRailModel.build(strings, GridSort(GridSortField.NAME, descending = false), state.groups, LocalDate.now())
        }
        val longerThanScreen by remember(state.count) {
            derivedStateOf {
                val shown = maxOf(gridState.layoutInfo.visibleItemsInfo.size, listState.layoutInfo.visibleItemsInfo.size)
                shown > 0 && (state.count ?: Long.MAX_VALUE) > shown
            }
        }
        if (state.load == MusicLoad.Ready && state.groups.isNotEmpty() && longerThanScreen) {
            MusicIndexRail(
                entries = rail,
                currentOffset = (focusedId?.let { id -> indexOfId(state.items, id) } ?: maxOf(gridState.firstVisibleItemIndex, listState.firstVisibleItemIndex)),
                onJump = { offset ->
                    viewModel.loadThrough(offset)
                    val key = keyAt(viewModel.state.value.items, offset) ?: return@MusicIndexRail
                    memory.placeByKeys(listOf(key), focusGate)
                },
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 118.dp, end = 15.dp),
            )
        }
        MusicActionPanel(menu, menuModel.controls(), nav, isTop, memory, Modifier.align(Alignment.TopEnd))
        val step = state.newPlaylist
        if (step is NewPlaylistStep.Naming || step == NewPlaylistStep.Creating) {
            NewPlaylistPanel(
                isTop = isTop,
                busy = step == NewPlaylistStep.Creating,
                failed = (step as? NewPlaylistStep.Naming)?.failed == true,
                onCreate = viewModel::createPlaylist,
                onClose = viewModel::closeNewPlaylist,
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
    }
    NewPlaylistOutcome(state.newPlaylist, memory, focusGate, isTop, nav, viewModel::createdShown)
}

/** docs/29 §9 A/E3: a created playlist opens once; a closed panel hands focus back to the New playlist tile. */
@Composable
private fun NewPlaylistOutcome(step: NewPlaylistStep, memory: FocusMemory, focusGate: MutableState<Boolean>, isTop: Boolean, nav: MusicNav, createdShown: () -> Unit) {
    val open = step is NewPlaylistStep.Naming || step == NewPlaylistStep.Creating
    var wasOpen by remember { mutableStateOf(false) }
    val latestIsTop by rememberUpdatedState(isTop)
    LaunchedEffect(open) {
        if (open) {
            wasOpen = true
            return@LaunchedEffect
        }
        if (!wasOpen) return@LaunchedEffect
        wasOpen = false
        // docs/15 §4: the panel's focused row left with it, so only a gated placement re-enters the layer.
        if (latestIsTop) memory.withPlacement { memory.placeByKeys(listOf(itemKey(NEW_PLAYLIST_ID)), focusGate) }
    }
    LaunchedEffect(step) {
        if (step !is NewPlaylistStep.Created) return@LaunchedEffect
        nav.openPlaylist(step.playlist)
        createdShown()
    }
}

private const val KEY_RETRY = "retry"

@Composable
private fun TabContent(
    items: TabItems,
    view: ViewSnapshot,
    memory: FocusMemory,
    gridState: LazyGridState,
    listState: LazyListState,
    imageUrl: MusicImageUrl,
    playing: PlayingTrack?,
    strings: tv.jellybeam.i18n.UiStrings,
    nav: MusicNav,
    onNewPlaylist: () -> Unit,
    onMore: (Card) -> Unit,
    onFocused: (String) -> Unit,
) {
    when (items) {
        is TabItems.Albums -> SquareGrid(gridState) {
            itemsIndexed(items.cards, key = { _, c -> c.id }, contentType = { _, _ -> "album" }) { _, card ->
                SquareCard(
                    title = card.name,
                    subline = albumSubline(card, strings),
                    art = albumArt(card),
                    imageUrl = imageUrl,
                    onClick = { nav.openAlbum(card.id) },
                    modifier = Modifier.focusKey(memory, itemKey(card.id)).onFocusChanged { if (it.hasFocus) onFocused(card.id) },
                )
            }
        }
        is TabItems.Playlists -> SquareGrid(gridState) {
            item(key = "new-playlist", contentType = "new") {
                SquareCard(
                    title = stringResource(R.string.music_new_playlist),
                    subline = stringResource(R.string.music_new_playlist_hint),
                    art = ArtSource.None,
                    imageUrl = imageUrl,
                    onClick = onNewPlaylist,
                    modifier = Modifier.focusKey(memory, itemKey(NEW_PLAYLIST_ID)),
                )
            }
            itemsIndexed(items.cards, key = { _, c -> c.id }, contentType = { _, _ -> "playlist" }) { _, card ->
                SquareCard(
                    title = card.name,
                    subline = musicLength(card.runtimeTicks, strings),
                    art = albumArt(card),
                    imageUrl = imageUrl,
                    onClick = { nav.openPlaylist(card) },
                    modifier = Modifier.focusKey(memory, itemKey(card.id)).onFocusChanged { if (it.hasFocus) onFocused(card.id) },
                )
            }
        }
        is TabItems.Artists -> LazyVerticalGrid(
            columns = GridCells.Fixed(GRID_COLUMNS),
            state = gridState,
            modifier = Modifier.width(GRID_WIDTH).fillMaxHeight(),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            itemsIndexed(items.rows, key = { _, r -> r.id }, contentType = { _, _ -> "artist" }) { _, row ->
                ArtistCircle(
                    name = row.name,
                    sortName = row.sortName,
                    art = artistArt(row),
                    subline = artistCircleSubline(row, strings),
                    imageUrl = imageUrl,
                    onClick = { nav.openArtist(row.id) },
                    modifier = Modifier.focusKey(memory, itemKey(row.id)).onFocusChanged { if (it.hasFocus) onFocused(row.id) },
                )
            }
        }
        is TabItems.Songs -> Column(Modifier.width(GRID_WIDTH)) {
            SongColumnHeader()
            LazyColumn(state = listState, contentPadding = PaddingValues(top = 10.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                itemsIndexed(items.cards, key = { _, c -> c.id }, contentType = { _, _ -> "song" }) { index, card ->
                    TrackRow(
                        data = TrackRowData(
                            title = card.name,
                            duration = trackDurationLabel(card),
                            artist = musicArtistLine(card),
                            album = card.album.orEmpty(),
                            plays = playsLabel(card),
                            hasLyrics = card.hasLyrics,
                            isFavorite = card.isFavorite,
                        ),
                        layout = TrackRowLayout.OneLine,
                        mark = playing.markFor(card.id),
                        imageUrl = imageUrl,
                        onClick = { nav.play(MusicSource.Songs(SongSource.All(view.id)), index) },
                        onMore = { onMore(card) },
                        modifier = Modifier.focusKey(memory, itemKey(card.id)).onFocusChanged { if (it.hasFocus) onFocused(card.id) },
                    )
                }
            }
        }
        is TabItems.Genres -> LazyVerticalGrid(
            columns = GridCells.Fixed(GENRE_COLUMNS),
            state = gridState,
            modifier = Modifier.width(GRID_WIDTH).fillMaxHeight(),
            contentPadding = PaddingValues(top = RING_CLEAR_TOP, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(19.dp),
            verticalArrangement = Arrangement.spacedBy(19.dp),
        ) {
            itemsIndexed(items.tiles, key = { _, t -> t.name }, contentType = { _, _ -> "genre" }) { _, tile ->
                GenreTileView(
                    tile = tile,
                    strings = strings,
                    imageUrl = imageUrl,
                    onClick = { nav.openSongs(SongSource.Genre(view.id, tile.name), tile.name) },
                    modifier = Modifier.focusKey(memory, itemKey(tile.name)),
                )
            }
        }
    }
}

@Composable
private fun SquareGrid(state: LazyGridState, content: androidx.compose.foundation.lazy.grid.LazyGridScope.() -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(GRID_COLUMNS),
        state = state,
        modifier = Modifier.width(GRID_WIDTH).fillMaxHeight(),
        contentPadding = PaddingValues(top = RING_CLEAR_TOP, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        content = content,
    )
}

/** docs/29 §9 A: tab pills switch on OK; selected is SurfacePanel with a hairline, focus is the ring alone. */
@Composable
internal fun MusicTabPill(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, small: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(50)
    val height = if (small) 22.dp else 28.dp
    val base = modifier
        .height(height)
        .focusRing(focused, cornerRadius = height / 2, color = LocalAccent.current.accentSheen)
    val look = if (selected) base.background(JellybeamTheme.SurfacePanel, shape).border(Dp.Hairline, JellybeamTheme.Hairline, shape) else base
    Box(look.clickable(interactionSource = interaction, indication = null, onClick = onClick).padding(horizontal = if (small) 10.dp else 14.dp), contentAlignment = Alignment.Center) {
        BasicText(label, maxLines = 1, style = archivo(if (selected) JellybeamTheme.Panna else PannaB3, if (small) 11.sp else 13.sp, FontWeight.SemiBold))
    }
}

@Composable
private fun ArtistScopeToggle(scope: ArtistScope, onSelect: (ArtistScope) -> Unit, memory: FocusMemory) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        MonoLine(stringResource(R.string.music_show), JellybeamTheme.Grigio, sizeSp = 9, modifier = Modifier.padding(end = 3.dp))
        MusicTabPill(
            stringResource(R.string.music_album_artists),
            selected = scope == ArtistScope.ALBUM_ARTISTS,
            onClick = { onSelect(ArtistScope.ALBUM_ARTISTS) },
            modifier = Modifier.focusKey(memory, "scope:album"),
            small = true,
        )
        MusicTabPill(
            stringResource(R.string.music_all_artists),
            selected = scope == ArtistScope.ALL,
            onClick = { onSelect(ArtistScope.ALL) },
            modifier = Modifier.focusKey(memory, "scope:all"),
            small = true,
        )
    }
}

/** docs/29 §9 A3: the Songs tab's mono column header over a hairline. */
@Composable
private fun SongColumnHeader() {
    Row(
        Modifier.fillMaxWidth().height(18.dp).padding(start = 30.dp, end = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MonoLine(stringResource(R.string.music_col_title), JellybeamTheme.Grigio, 9, Modifier.weight(1f))
        MonoLine(stringResource(R.string.music_col_artist), JellybeamTheme.Grigio, 9, Modifier.padding(start = 16.dp).width(250.dp))
        MonoLine(stringResource(R.string.music_col_album), JellybeamTheme.Grigio, 9, Modifier.padding(start = 16.dp).width(180.dp))
        MonoLine(stringResource(R.string.music_col_plays), JellybeamTheme.Grigio, 9, Modifier.padding(start = 12.dp).width(PLAYS_WIDTH))
        Spacer(Modifier.width(66.dp))
        MonoLine(stringResource(R.string.music_col_time), JellybeamTheme.Grigio, 9, Modifier.width(55.dp))
    }
    Box(Modifier.fillMaxWidth().height(Dp.Hairline).background(JellybeamTheme.Hairline))
}

/** docs/29 §9 A5: a wide genre tile, name and counts beside its art. */
@Composable
private fun GenreTileView(tile: GenreTile, strings: tv.jellybeam.i18n.UiStrings, imageUrl: MusicImageUrl, onClick: () -> Unit, modifier: Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier
            .height(96.dp)
            .focusRing(focused, cornerRadius = 8.dp, color = LocalAccent.current.accentSheen)
            .background(JellybeamTheme.Surface, shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            BasicText(tile.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = archivo(JellybeamTheme.Panna, 16.sp))
            val counts = listOf(
                strings.plural(R.plurals.music_count_albums, tile.albumCount.toInt(), tile.albumCount.toString()),
                strings.plural(R.plurals.music_count_songs, tile.songCount.toInt(), tile.songCount.toString()),
            ).joinToString(" │ ")
            MonoLine(counts, JellybeamTheme.Grigio, 10)
        }
        SquareArt(tile.art, imageUrl, tile.name, Modifier.size(80.dp))
    }
}

/** docs/29 §9 A facts: title 17sp bold and subline, then mono label over value pairs. */
@Composable
private fun FactsBlock(facts: FocusedFacts, modifier: Modifier) {
    Column(modifier.widthIn(max = 380.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicText(facts.title, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis, style = archivo(JellybeamTheme.Panna, 17.sp))
            facts.subline?.let { BasicText(it, Modifier.widthIn(max = 180.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = archivo(PannaB3, 13.sp, FontWeight.Normal)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            facts.facts.forEach { fact ->
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    BasicText(fact.label, maxLines = 1, style = monoStyle(JellybeamTheme.Grigio, 9))
                    BasicText(fact.value, maxLines = 1, style = archivo(JellybeamTheme.Panna, 13.sp, FontWeight.SemiBold))
                }
            }
        }
    }
}

/** docs/29 §10 S1: the selected tab's final geometry in SURFACE blocks. */
@Composable
private fun TabSkeleton(tab: MusicTab?) {
    when (tab) {
        MusicTab.Songs -> Column(Modifier.width(GRID_WIDTH).padding(top = 28.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(skeletonRowCount(null)) { Row(Modifier.height(44.dp).padding(start = 30.dp), verticalAlignment = Alignment.CenterVertically) { MusicSkeleton(260.dp, 14.dp) } }
        }
        MusicTab.Genres -> Row(horizontalArrangement = Arrangement.spacedBy(19.dp)) {
            repeat(GENRE_COLUMNS) { MusicSkeleton(270.dp, 96.dp, radius = 8.dp) }
        }
        MusicTab.Artists -> Row(Modifier.width(GRID_WIDTH), horizontalArrangement = Arrangement.SpaceBetween) {
            repeat(GRID_COLUMNS) { MusicSkeleton(ARTIST_CIRCLE, ARTIST_CIRCLE, radius = ARTIST_CIRCLE / 2) }
        }
        else -> Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            repeat(GRID_COLUMNS) { MusicSkeleton(SQUARE_ART, SQUARE_ART) }
        }
    }
}

private fun tabLabel(tab: MusicTab): Int = when (tab) {
    MusicTab.Albums -> R.string.music_tab_albums
    MusicTab.Artists -> R.string.music_tab_artists
    MusicTab.Songs -> R.string.music_tab_songs
    MusicTab.Playlists -> R.string.music_tab_playlists
    MusicTab.Genres -> R.string.music_tab_genres
}

private const val NEW_PLAYLIST_ID = "new-playlist"

internal fun itemKey(id: String) = "item:$id"

private fun firstItemKey(items: TabItems): String? = when (items) {
    is TabItems.Albums -> items.cards.firstOrNull()?.id
    is TabItems.Artists -> items.rows.firstOrNull()?.id
    is TabItems.Songs -> items.cards.firstOrNull()?.id
    is TabItems.Playlists -> NEW_PLAYLIST_ID
    is TabItems.Genres -> items.tiles.firstOrNull()?.name
}?.let(::itemKey)

private fun idAt(items: TabItems?, index: Int): String? = when (items) {
    null -> null
    is TabItems.Albums -> items.cards.getOrNull(index)?.id
    is TabItems.Artists -> items.rows.getOrNull(index)?.id
    is TabItems.Songs -> items.cards.getOrNull(index)?.id
    is TabItems.Playlists -> items.cards.getOrNull(index)?.id
    is TabItems.Genres -> items.tiles.getOrNull(index)?.name
}

private fun keyAt(items: TabItems?, index: Int): String? = idAt(items, index)?.let(::itemKey)

private fun indexOfId(items: TabItems?, id: String): Int? {
    val size = items?.size ?: return null
    return (0 until size).firstOrNull { idAt(items, it) == id }
}

/** The lazy index of [key]'s row (Playlists has New playlist ahead of the rows); `false` when it isn't loaded. */
private suspend fun scrollToKey(key: String, items: TabItems?, grid: LazyGridState, list: LazyListState): Boolean {
    val id = key.removePrefix("item:")
    if (id == key) return false
    val index = indexOfId(items, id) ?: return false
    val lazyIndex = if (items is TabItems.Playlists) index + 1 else index
    if (items is TabItems.Songs) list.scrollToItem(lazyIndex) else grid.scrollToItem(lazyIndex)
    return true
}

private fun focusedFacts(state: MusicLibraryState, focusedId: String?, strings: tv.jellybeam.i18n.UiStrings): FocusedFacts? {
    val id = focusedId ?: return null
    return when (val items = state.items) {
        is TabItems.Albums -> items.cards.firstOrNull { it.id == id }?.let { albumFacts(it, state.summaries[id], strings, deviceZone()) }
        is TabItems.Artists -> items.rows.firstOrNull { it.id == id }?.let { artistFacts(it, strings) }
        else -> null
    }
}

/** An album or playlist's own Primary, else the no-art tile (docs/29 §10 S4). */
internal fun albumArt(card: Card): ArtSource = card.primaryTag?.let { ArtSource.Own(card.id, it, ImageKind.PRIMARY) } ?: ArtSource.None

internal fun artistArt(row: MusicArtistRow): ArtSource = row.primaryTag?.let { ArtSource.Own(row.id, it, ImageKind.PRIMARY) } ?: ArtSource.None

/** Server art at [slot]'s pixel width, read on account [accountEpoch]; `null` is the current account, as browse pages show. */
@Composable
internal fun rememberMusicImageUrl(slot: Dp, accountEpoch: ULong? = null): MusicImageUrl {
    val px = with(LocalDensity.current) { slot.roundToPx() }.toUInt()
    return remember(px, accountEpoch) { { itemId, kind, tag -> AppGraph.gateway.imageUrl(itemId, kind, tag, px, accountEpoch) } }
}

/** docs/29 §10 S3: `LAST TRIED 9:41 PM`, in the device's time format. */
@Composable
internal fun lastTriedLine(atMs: Long): String {
    val context = androidx.compose.ui.platform.LocalContext.current
    val format = remember(context) { android.text.format.DateFormat.getTimeFormat(context) }
    return stringResource(R.string.music_last_tried, format.format(java.util.Date(atMs)))
}
