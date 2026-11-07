package tv.jellybeam.ui.search

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import tv.jellybeam.ui.music.BlocksAmbient
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import tv.jellybeam.i18n.uppercaseUi
import tv.jellybeam.player.music.MusicSource
import tv.jellybeam.ui.music.ArtistCircle
import tv.jellybeam.ui.music.RING_CLEAR_TOP
import tv.jellybeam.ui.music.CurrentMark
import tv.jellybeam.ui.music.MusicImageUrl
import tv.jellybeam.ui.music.SquareCard
import tv.jellybeam.ui.music.MusicActionPanel
import tv.jellybeam.ui.music.MusicMenuKind
import tv.jellybeam.ui.music.MusicMenuTarget
import tv.jellybeam.ui.music.MusicMenuViewModel
import tv.jellybeam.ui.music.MusicNav
import tv.jellybeam.ui.music.TrackRow
import tv.jellybeam.ui.music.controls
import tv.jellybeam.ui.music.rememberMusicMenu
import tv.jellybeam.ui.music.TrackRowData
import tv.jellybeam.ui.music.TrackRowLayout
import tv.jellybeam.ui.music.albumArt
import tv.jellybeam.ui.music.markFor
import tv.jellybeam.ui.music.musicArtistLine
import tv.jellybeam.ui.music.rememberMusicImageUrl
import tv.jellybeam.ui.music.rememberPlayingTrack
import tv.jellybeam.ui.music.trackArtSource
import tv.jellybeam.ui.music.trackDurationLabel
import tv.jellybeam.AppGraph
import tv.jellybeam.JellybeamTheme
import tv.jellybeam.LocalAccent
import tv.jellybeam.R
import tv.jellybeam.ui.cards.CardFormatting
import tv.jellybeam.ui.cards.PosterCard
import tv.jellybeam.ui.discover.SeerrPosterCard
import tv.jellybeam.ui.discover.seerrCardKey
import tv.jellybeam.ui.focus.FocusMemory
import tv.jellybeam.ui.focus.FocusRestorer
import tv.jellybeam.ui.focus.asFocusTarget
import tv.jellybeam.ui.focus.focusKey
import tv.jellybeam.ui.focus.rememberFocusMemory
import tv.jellybeam.ui.focus.ringClearBringIntoViewSpec
import uniffi.jellybeam_core.Card
import uniffi.jellybeam_core.SeerrMediaType

private val PAGE_MARGIN = 32.dp

/** Mirrors [tv.jellybeam.ui.library.LibraryScreen]'s density-pass constants -- same grid pattern,
 * same reasoning. */
private val CELL_GAP = 12.dp
private const val GRID_COLUMNS = 8
private val CELL_WIDTH_MIN = 100.dp
private val CELL_WIDTH_MAX = 220.dp

private const val CONTENT_TYPE_SEERR_POSTER = "seerr-poster"
private const val CONTENT_TYPE_SECTION_LINE = "section-line"
private const val CONTENT_TYPE_GROUP_HEADER = "group-header"

/** docs/29 §9 K1: song rows stop at about half the page, as the mock's 1100 px column. */
private val SONG_ROW_MAX_WIDTH = 560.dp

/** docs/29 §8.1: a two-line row's 32 dp thumb. */
private val SONG_THUMB = 32.dp

/**
 * Mirror-backed search: a house-style text field up top, live results below in
 * [tv.jellybeam.ui.library.LibraryScreen]'s grid pattern, grouped by [groupSearchResults] under one
 * header per type with art by type (docs/29 §9 K1): posters, artist circles, album and playlist
 * squares, two-line song rows that play on OK. With Seerr connected, [discoverSection] follows.
 *
 * Focus starts on the text field ([fieldFocusRequester]). D-pad Down is routed explicitly to the
 * first result ([firstResultRequester]) via `focusProperties { down = ... }`, since Compose's
 * default 2D focus search picks whichever cell is spatially nearest the field's full-width
 * bounds (empirically column 4 of an 8-column grid, not column 0). The override only applies
 * while there are results; with none, `down` stays `FocusRequester.Default`.
 *
 * docs/15-focus-and-selection.md §2-§5: [memory] records the last-focused key -- `"field"`,
 * `"result:<Card.id>"` or `"discover:<seerrCardKey>"`. A fresh entry falls through to the field; a return restores the exact
 * result cell the viewer left. No §2 rule-2 selected item, so `selectedKey` stays `null`.
 * `ready` stays `true` unconditionally since `state.results` is retained `ViewModel` state.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SearchScreen(
    onOpenDetail: (Card) -> Unit,
    /** Opens a Discover title page for a card from the Discover section (docs/14 "Unified search"). */
    onOpenDiscoverDetail: (mediaType: SeerrMediaType, tmdbId: Long) -> Unit,
    /** MainActivity's zero-network Seerr gate; the Discover section exists only while it is true. */
    discoverConfigured: Boolean,
    isTop: Boolean = true,
    /** docs/29 §9 K1: OK on a song row plays that song. */
    onPlaySong: (Card) -> Unit = { AppGraph.music.play(MusicSource.Single(it)) },
    /** docs/29 §9 K1: a song row's ··· opens the song menu, which navigates through this. */
    musicNav: MusicNav? = null,
    viewModel: SearchViewModel = viewModel(factory = SearchViewModelFactory(AppGraph.gateway)),
    menuModel: MusicMenuViewModel = rememberMusicMenu(),
) {
    val state by viewModel.state.collectAsState()
    val menu by menuModel.state.collectAsState()
    LaunchedEffect(discoverConfigured) { viewModel.setDiscoverConfigured(discoverConfigured) }
    // Keyed by focus-memory key, since the grid mixes library and Discover cells.
    var focusedKey by remember { mutableStateOf<String?>(null) }
    val fieldFocusRequester = remember { FocusRequester() }
    // Down-routing target for the query field (see kdoc above), attached only to grid index 0.
    val firstResultRequester = remember { FocusRequester() }
    val memory = rememberFocusMemory()
    val gridState = remember { LazyGridState() }
    val groups = remember(state.results) { groupSearchResults(state.results) }
    // The field's Down target and the cell that claims [firstResultRequester].
    val firstResultId = groups.firstOrNull()?.cards?.firstOrNull()?.id
    val playing = rememberPlayingTrack()

    // A new library answer starts at the top: its rows are inserted ahead of the Discover header,
    // and the grid would otherwise keep that header anchored and push them off the top.
    val shownResults = remember { arrayOf(state.results) }
    if (shownResults[0] !== state.results) {
        shownResults[0] = state.results
        gridState.requestScrollToItem(0)
    }

    // §5: scroll a not-yet-composed result cell into view before retrying.
    memory.scrollTo = scrollTo@{ key ->
        val index = when {
            key.startsWith("result:") -> searchGridIndexOf(groups, key.removePrefix("result:"))
            key.startsWith("discover:") -> (state.discover as? DiscoverSection.Results)?.cards
                ?.indexOfFirst { seerrCardKey(it.mediaType, it.tmdbId) == key.removePrefix("discover:") }
                // +1 for the section header item ahead of the Discover cells.
                ?.let { if (it >= 0) searchGridCellCount(groups) + 1 + it else -1 } ?: -1
            else -> -1
        }
        if (index >= 0) gridState.scrollToItem(index)
        index >= 0
    }

    FocusRestorer(
        memory = memory,
        isTop = isTop,
        ready = true,
        fallback = { fieldFocusRequester.asFocusTarget() },
        tag = "search",
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(JellybeamTheme.Notte),
    ) {
        val availableWidth = maxWidth - PAGE_MARGIN * 2 - CELL_GAP * (GRID_COLUMNS - 1)
        val cellWidth = (availableWidth / GRID_COLUMNS).coerceIn(CELL_WIDTH_MIN, CELL_WIDTH_MAX)
        // Request art at the cell's actual displayed width, not a flat 320 oversized for it
        // (docs/07 §1, same pattern as LibraryScreen's grid).
        val density = LocalDensity.current
        val cellImageWidth = remember(cellWidth, density) {
            CardFormatting.bucketedImageWidth(with(density) { cellWidth.roundToPx() })
        }
        val squareImageUrl = rememberMusicImageUrl(cellWidth)
        val thumbImageUrl = rememberMusicImageUrl(SONG_THUMB)
        val ringSpec = remember(density) { ringClearBringIntoViewSpec(with(density) { RING_CLEAR_TOP.toPx() }) }

        Column(modifier = Modifier.fillMaxSize()) {
            SearchField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                focusRequester = fieldFocusRequester,
                firstResultRequester = firstResultRequester,
                hasResults = groups.isNotEmpty() || state.discover is DiscoverSection.Results,
                memory = memory,
                modifier = Modifier
                    .padding(horizontal = PAGE_MARGIN, vertical = 24.dp)
                    .fillMaxWidth(),
            )

            when {
                state.query.isBlank() -> SearchHint(
                    stringResource(if (state.discoverEnabled) R.string.search_empty_hint_with_discover else R.string.search_empty_hint),
                )
                showNoMatches(state.query, state.isSearching, state.results, state.discover) ->
                    SearchNoResults(query = state.query.trim(), searched = state.searched)
                // docs/15 §5: a focused cell scrolls in with its ring, so the bottom row's ring is never cut by the bar's edge.
                else -> CompositionLocalProvider(LocalBringIntoViewSpec provides ringSpec) { LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(GRID_COLUMNS),
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = PAGE_MARGIN, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(CELL_GAP),
                    verticalArrangement = Arrangement.spacedBy(CELL_GAP),
                ) {
                    groups.forEachIndexed { groupIndex, group ->
                        item(key = "group:${group.group.name}", span = { GridItemSpan(maxLineSpan) }, contentType = CONTENT_TYPE_GROUP_HEADER) {
                            SearchGroupHeader(title = stringResource(group.group.titleRes()), count = group.cards.size, topGap = groupIndex > 0)
                        }
                        val rows = group.group == SearchGroup.Songs
                        itemsIndexed(
                            group.cards,
                            key = { _, card -> card.id },
                            span = { _, _ -> GridItemSpan(if (rows) maxLineSpan else 1) },
                            contentType = { _, _ -> group.group },
                        ) { _, card ->
                            val cellKey = "result:${card.id}"
                            // docs/07 §4: derivedStateOf, same reasoning as Library's grid.
                            val isFocused by remember(cellKey) { derivedStateOf { focusedKey == cellKey } }
                            SearchResultCell(
                                card = card,
                                group = group.group,
                                isFocused = isFocused,
                                cellWidth = cellWidth,
                                posterImageUrl = { itemId, kind, tag -> AppGraph.gateway.imageUrl(itemId, kind, tag, cellImageWidth) },
                                squareImageUrl = squareImageUrl,
                                thumbImageUrl = thumbImageUrl,
                                mark = playing.markFor(card.id),
                                onOpen = { onOpenDetail(card) },
                                onPlaySong = { onPlaySong(card) },
                                onMore = { menuModel.open(MusicMenuTarget(card, MusicMenuKind.Song), cellKey) },
                                modifier = Modifier
                                    .focusKey(memory, cellKey)
                                    .then(
                                        // Only the current first cell claims this requester;
                                        // attaching never moves focus, so a refresh can't steal it.
                                        if (card.id == firstResultId) Modifier.focusRequester(firstResultRequester) else Modifier
                                    )
                                    .onFocusChanged { focusState ->
                                        if (focusState.isFocused) {
                                            focusedKey = cellKey
                                        } else if (focusedKey == cellKey) {
                                            focusedKey = null
                                        }
                                    },
                            )
                        }
                    }

                    discoverSection(
                        section = state.discover,
                        libraryEmpty = groups.isEmpty(),
                        cellWidth = cellWidth,
                        memory = memory,
                        firstResultRequester = firstResultRequester,
                        focusedKey = { focusedKey },
                        onFocusedKey = { focusedKey = it },
                        onOpen = onOpenDiscoverDetail,
                    )
                } }
            }
        }

        val hintCount by remember(groups) {
            derivedStateOf {
                discoverHintCount(
                    section = state.discover,
                    headerIndex = searchGridCellCount(groups),
                    lastVisibleIndex = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1,
                )
            }
        }
        hintCount?.let { count ->
            // On the field label's line, the one strip no result cell ever occupies.
            DiscoverBelowHint(count = count, modifier = Modifier.align(Alignment.TopEnd).padding(top = 24.dp, end = PAGE_MARGIN))
        }
        if (musicNav != null) {
            MusicActionPanel(menu, menuModel.controls(), musicNav, isTop, memory, Modifier.align(Alignment.TopEnd))
        }
    }
}

/** docs/29 §9 K1: a group's header, Archivo 20/600 with its count beside it in Grigio. */
@Composable
private fun SearchGroupHeader(title: String, count: Int, topGap: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = if (topGap) 16.dp else 0.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText(
            text = title,
            modifier = Modifier.alignByBaseline(),
            style = TextStyle(fontFamily = JellybeamTheme.Archivo, fontWeight = FontWeight.SemiBold, color = JellybeamTheme.Panna, fontSize = 20.sp),
        )
        BasicText(
            text = count.toString(),
            modifier = Modifier.alignByBaseline(),
            style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Grigio, fontSize = 13.sp),
        )
    }
}

private fun SearchGroup.titleRes(): Int = when (this) {
    SearchGroup.Films -> R.string.search_group_films
    SearchGroup.Series -> R.string.search_group_series
    SearchGroup.Episodes -> R.string.search_group_episodes
    SearchGroup.Artists -> R.string.music_tab_artists
    SearchGroup.Albums -> R.string.music_tab_albums
    SearchGroup.Songs -> R.string.music_tab_songs
    SearchGroup.Playlists -> R.string.music_tab_playlists
    SearchGroup.Other -> R.string.search_group_other
}

private fun SearchedKind.labelRes(): Int = when (this) {
    SearchedKind.Films -> R.string.search_group_films
    SearchedKind.Series -> R.string.search_group_series
    SearchedKind.Music -> R.string.music_title
}

/**
 * docs/29 §9 K1: one result in its group's art; [modifier] carries the cell's focus key and lands
 * before each card's own focus target. Names are verbatim; a song's line 2 is its artist and album.
 */
@Composable
private fun SearchResultCell(
    card: Card,
    group: SearchGroup,
    isFocused: Boolean,
    cellWidth: Dp,
    posterImageUrl: MusicImageUrl,
    squareImageUrl: MusicImageUrl,
    thumbImageUrl: MusicImageUrl,
    mark: CurrentMark,
    onOpen: () -> Unit,
    onPlaySong: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier,
) {
    when (group) {
        SearchGroup.Artists -> ArtistCircle(
            name = card.name,
            sortName = null,
            art = albumArt(card),
            subline = null,
            imageUrl = squareImageUrl,
            onClick = onOpen,
            modifier = modifier,
            size = cellWidth,
        )
        SearchGroup.Albums, SearchGroup.Playlists -> SquareCard(
            title = card.name,
            subline = if (group == SearchGroup.Albums) card.albumArtist else null,
            art = albumArt(card),
            imageUrl = squareImageUrl,
            onClick = onOpen,
            modifier = modifier,
            size = cellWidth,
        )
        SearchGroup.Songs -> {
            val artist = musicArtistLine(card).ifEmpty { null }
            val album = card.album
            TrackRow(
                data = TrackRowData(
                    title = card.name,
                    duration = trackDurationLabel(card),
                    thumb = trackArtSource(card),
                    line2 = if (artist != null && album != null) stringResource(R.string.music_pair, artist, album) else artist ?: album,
                    hasLyrics = card.hasLyrics,
                    isFavorite = card.isFavorite,
                ),
                layout = TrackRowLayout.TwoLine,
                mark = mark,
                imageUrl = thumbImageUrl,
                onClick = onPlaySong,
                onMore = onMore,
                modifier = modifier.widthIn(max = SONG_ROW_MAX_WIDTH),
            )
        }
        SearchGroup.Films, SearchGroup.Series, SearchGroup.Episodes, SearchGroup.Other -> PosterCard(
            card = card,
            isFocused = isFocused,
            imageUrl = posterImageUrl,
            onClick = onOpen,
            modifier = modifier,
            width = cellWidth,
        )
    }
}

/** docs/14 "Unified search": a quiet, unfocusable note that Discover results wait below a full
 * page of library results. */
@Composable
private fun DiscoverBelowHint(count: Int, modifier: Modifier = Modifier) {
    BasicText(
        text = pluralStringResource(R.plurals.search_discover_below, count, count),
        modifier = modifier,
        style = TextStyle(fontFamily = JellybeamTheme.MartianMono, color = JellybeamTheme.Grigio, fontSize = 11.sp, letterSpacing = 1.sp),
    )
}

/**
 * docs/14 "Unified search": Seerr results as their own labelled block after the library cells, in
 * the same grid so D-pad Down walks from the last library row into it. The header and a status
 * line span the full row; the cards keep Discover's own [SeerrPosterCard] with its availability
 * badge so they never read as library items.
 */
private fun LazyGridScope.discoverSection(
    section: DiscoverSection,
    libraryEmpty: Boolean,
    cellWidth: Dp,
    memory: FocusMemory,
    firstResultRequester: FocusRequester,
    focusedKey: () -> String?,
    onFocusedKey: (String?) -> Unit,
    onOpen: (SeerrMediaType, Long) -> Unit,
) {
    if (section == DiscoverSection.Hidden) return
    item(key = "discover-header", span = { GridItemSpan(maxLineSpan) }, contentType = CONTENT_TYPE_SECTION_LINE) {
        DiscoverSectionHeader(
            status = when (section) {
                DiscoverSection.Searching -> stringResource(R.string.search_discover_searching)
                DiscoverSection.Unavailable -> stringResource(R.string.search_discover_unavailable)
                else -> null
            },
            topGap = !libraryEmpty,
        )
    }
    if (section !is DiscoverSection.Results) return
    itemsIndexed(
        section.cards,
        key = { _, card -> "discover:" + seerrCardKey(card.mediaType, card.tmdbId) },
        contentType = { _, _ -> CONTENT_TYPE_SEERR_POSTER },
    ) { index, card ->
        val cellKey = "discover:" + seerrCardKey(card.mediaType, card.tmdbId)
        val isFocused by remember(cellKey) { derivedStateOf { focusedKey() == cellKey } }
        SeerrPosterCard(
            posterUrl = card.posterUrl,
            title = card.title,
            year = card.year,
            availability = card.availability,
            isFocused = isFocused,
            onClick = { onOpen(card.mediaType, card.tmdbId) },
            width = cellWidth,
            modifier = Modifier
                .focusKey(memory, cellKey)
                // The field's Down target when the library side has nothing to offer.
                .then(if (libraryEmpty && index == 0) Modifier.focusRequester(firstResultRequester) else Modifier)
                .onFocusChanged { focusState ->
                    if (focusState.isFocused) {
                        onFocusedKey(cellKey)
                    } else if (focusedKey() == cellKey) {
                        onFocusedKey(null)
                    }
                },
        )
    }
}

/** Mono muted kicker over a 1px divider (docs/brand.md: dividers, no washes), plus an optional
 * one-line status while Seerr is pending or unreachable. */
@Composable
private fun DiscoverSectionHeader(status: String?, topGap: Boolean) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = if (topGap) 20.dp else 0.dp)) {
        BasicText(
            text = stringResource(R.string.search_discover_kicker),
            style = TextStyle(fontFamily = JellybeamTheme.MartianMono, color = JellybeamTheme.Grigio, fontSize = 11.sp, letterSpacing = 1.sp),
        )
        Box(
            modifier = Modifier
                .padding(top = 8.dp)
                .fillMaxWidth()
                .height(1.dp)
                .background(JellybeamTheme.Panna.copy(alpha = 0.08f)),
        )
        status?.let {
            BasicText(
                text = it,
                modifier = Modifier.padding(top = 12.dp),
                style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Grigio, fontSize = 14.sp),
            )
        }
    }
}

/**
 * The house text-field style ([tv.jellybeam.ui.signin.SignInScreen]'s `JellybeamTextField` recipe): a
 * Grigio label, a Surface box with a Hairline border that turns accent on focus, Archivo
 * text. `imeAction = Done` just dismisses the keyboard -- results are already live via
 * [SearchViewModel]'s debounce.
 */
@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    focusRequester: FocusRequester,
    /** Down-navigation target once there are results -- see [SearchScreen]'s kdoc. */
    firstResultRequester: FocusRequester,
    hasResults: Boolean,
    memory: FocusMemory,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    // docs/29 §6: a focused text field holds ambient off, since the keyboard's insets don't reach Compose before API 30.
    if (isFocused) BlocksAmbient()
    val accent = LocalAccent.current
    val borderColor = if (isFocused) accent.accentInk else JellybeamTheme.Hairline
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText(
            text = stringResource(R.string.search_field_label),
            style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Grigio, fontSize = 12.sp),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(JellybeamTheme.Surface, RoundedCornerShape(8.dp))
                .border(2.dp, borderColor, RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusKey(memory, "field")
                    .focusRequester(focusRequester)
                    // The field moves focus on Down itself only for a hardware D-pad; injected and CEC keys stop in the text input.
                    .onPreviewKeyEvent { it.type == KeyEventType.KeyDown && it.key == Key.DirectionDown && focusManager.moveFocus(FocusDirection.Down) }
                    .focusProperties {
                        // Only points `down` at the first-result requester once it's attached.
                        down = if (hasResults) firstResultRequester else FocusRequester.Default
                    },
                textStyle = TextStyle(
                    fontFamily = JellybeamTheme.Archivo,
                    color = JellybeamTheme.Panna,
                    fontSize = 16.sp,
                ),
                singleLine = true,
                cursorBrush = SolidColor(accent.accentInk),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }),
                interactionSource = interactionSource,
            )
        }
    }
}

/** docs/29 §9 K3: the searching mascot over `No results for "{q}".` and a mono line naming what was searched. */
@Composable
private fun SearchNoResults(query: String, searched: List<SearchedKind>) {
    val kinds = searched.map { stringResource(it.labelRes()) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = PAGE_MARGIN, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.jb_mascot_searching),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(width = 220.dp, height = 210.dp),
        )
        BasicText(
            text = stringResource(R.string.search_no_results_for, query),
            modifier = Modifier.padding(top = 15.dp),
            style = TextStyle(
                fontFamily = JellybeamTheme.Archivo,
                fontWeight = FontWeight.Bold,
                color = JellybeamTheme.Panna,
                fontSize = 22.sp,
                letterSpacing = (-0.02).em,
            ),
        )
        if (kinds.isNotEmpty()) {
            BasicText(
                text = stringResource(R.string.search_searched, kinds.joinToString(SEARCHED_SEPARATOR)).uppercaseUi(),
                modifier = Modifier.padding(top = 12.dp),
                style = TextStyle(fontFamily = JellybeamTheme.MartianMono, color = JellybeamTheme.Grigio, fontSize = 11.sp, letterSpacing = (-0.02).em),
            )
        }
    }
}

private const val SEARCHED_SEPARATOR = " │ "

@Composable
private fun SearchHint(text: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = PAGE_MARGIN),
    ) {
        BasicText(
            text = text,
            style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Grigio, fontSize = 16.sp),
        )
    }
}
