package tv.jellybeam.ui.search

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import tv.jellybeam.AppGraph
import tv.jellybeam.JellybeamTheme
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
import uniffi.jellybeam_core.Card
import uniffi.jellybeam_core.SeerrMediaType

private val PAGE_MARGIN = 32.dp

/** Mirrors [tv.jellybeam.ui.library.LibraryScreen]'s density-pass constants -- same grid pattern,
 * same reasoning. */
private val CELL_GAP = 12.dp
private const val GRID_COLUMNS = 8
private val CELL_WIDTH_MIN = 100.dp
private val CELL_WIDTH_MAX = 220.dp

private const val CONTENT_TYPE_POSTER = "poster"
private const val CONTENT_TYPE_SEERR_POSTER = "seerr-poster"
private const val CONTENT_TYPE_SECTION_LINE = "section-line"

/**
 * Mirror-backed search: a house-style text field up top, live results below in
 * [tv.jellybeam.ui.library.LibraryScreen]'s poster-grid pattern -- [PosterCard] renders whatever
 * item type comes back (Movie/Series/Episode all matched by `JellybeamCore::search`), fail open
 * like every other grid. With Seerr connected, [discoverSection] follows the library cells.
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
@Composable
fun SearchScreen(
    onOpenDetail: (Card) -> Unit,
    /** Opens a Discover title page for a card from the Discover section (docs/14 "Unified search"). */
    onOpenDiscoverDetail: (mediaType: SeerrMediaType, tmdbId: Long) -> Unit,
    /** MainActivity's zero-network Seerr gate; the Discover section exists only while it is true. */
    discoverConfigured: Boolean,
    isTop: Boolean = true,
    /** JellybeamRoot's focus gate for this entry -- closed while hidden/transitioning; opened
     * before each explicit focus placement. See [tv.jellybeam.MainActivity.RetainedScreenLayer]. */
    focusGate: MutableState<Boolean> = remember { mutableStateOf(true) },
    viewModel: SearchViewModel = viewModel(factory = SearchViewModelFactory(AppGraph.gateway)),
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(discoverConfigured) { viewModel.setDiscoverConfigured(discoverConfigured) }
    // Keyed by focus-memory key, since the grid mixes library and Discover cells.
    var focusedKey by remember { mutableStateOf<String?>(null) }
    val fieldFocusRequester = remember { FocusRequester() }
    // Down-routing target for the query field (see kdoc above), attached only to grid index 0.
    val firstResultRequester = remember { FocusRequester() }
    val memory = rememberFocusMemory()
    val gridState = remember { LazyGridState() }

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
            key.startsWith("result:") -> state.results.indexOfFirst { it.id == key.removePrefix("result:") }
            key.startsWith("discover:") -> (state.discover as? DiscoverSection.Results)?.cards
                ?.indexOfFirst { seerrCardKey(it.mediaType, it.tmdbId) == key.removePrefix("discover:") }
                // +1 for the section header item ahead of the Discover cells.
                ?.let { if (it >= 0) state.results.size + 1 + it else -1 } ?: -1
            else -> -1
        }
        if (index >= 0) gridState.scrollToItem(index)
        index >= 0
    }

    FocusRestorer(
        memory = memory,
        isTop = isTop,
        focusGate = focusGate,
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

        Column(modifier = Modifier.fillMaxSize()) {
            SearchField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                focusRequester = fieldFocusRequester,
                firstResultRequester = firstResultRequester,
                hasResults = state.results.isNotEmpty() || state.discover is DiscoverSection.Results,
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
                    SearchNoResults(stringResource(R.string.search_no_results))
                else -> LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(GRID_COLUMNS),
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = PAGE_MARGIN, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(CELL_GAP),
                    verticalArrangement = Arrangement.spacedBy(CELL_GAP),
                ) {
                    itemsIndexed(
                        state.results,
                        key = { _, card -> card.id },
                        contentType = { _, _ -> CONTENT_TYPE_POSTER },
                    ) { index, card ->
                        val cellKey = "result:${card.id}"
                        // docs/07 §4: derivedStateOf, same reasoning as Library's grid.
                        val isFocused by remember(cellKey) { derivedStateOf { focusedKey == cellKey } }

                        PosterCard(
                            card = card,
                            isFocused = isFocused,
                            imageUrl = { itemId, kind, tag -> AppGraph.gateway.imageUrl(itemId, kind, tag, cellImageWidth) },
                            onClick = { onOpenDetail(card) },
                            modifier = Modifier
                                .focusKey(memory, cellKey)
                                .then(
                                    // Only the current index-0 item claims this requester;
                                    // attaching never moves focus, so a refresh can't steal it.
                                    if (index == 0) Modifier.focusRequester(firstResultRequester) else Modifier
                                )
                                .onFocusChanged { focusState ->
                                    if (focusState.isFocused) {
                                        focusedKey = cellKey
                                    } else if (focusedKey == cellKey) {
                                        focusedKey = null
                                    }
                                },
                            width = cellWidth,
                        )
                    }

                    discoverSection(
                        section = state.discover,
                        libraryEmpty = state.results.isEmpty(),
                        cellWidth = cellWidth,
                        memory = memory,
                        firstResultRequester = firstResultRequester,
                        focusedKey = { focusedKey },
                        onFocusedKey = { focusedKey = it },
                        onOpen = onOpenDiscoverDetail,
                    )
                }
            }
        }

        val hintCount by remember {
            derivedStateOf {
                discoverHintCount(
                    section = state.discover,
                    headerIndex = state.results.size,
                    lastVisibleIndex = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1,
                )
            }
        }
        hintCount?.let { count ->
            // On the field label's line, the one strip no result cell ever occupies.
            DiscoverBelowHint(count = count, modifier = Modifier.align(Alignment.TopEnd).padding(top = 24.dp, end = PAGE_MARGIN))
        }
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
 * Grigio label, a Surface box with a Hairline border that turns Pistacchio on focus, Archivo
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
    val borderColor = if (isFocused) JellybeamTheme.Pistacchio else JellybeamTheme.Hairline
    val keyboardController = LocalSoftwareKeyboardController.current

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
                cursorBrush = SolidColor(JellybeamTheme.Pistacchio),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }),
                interactionSource = interactionSource,
            )
        }
    }
}

/** Search with no matches (docs/brand.md §5): the searching mascot on Notte above the one-line copy. */
@Composable
private fun SearchNoResults(text: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = PAGE_MARGIN, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.jb_mascot_searching),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.height(STATE_MASCOT_HEIGHT),
        )
        BasicText(
            text = text,
            modifier = Modifier.padding(top = 15.dp),
            style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Panna2, fontSize = 16.sp),
        )
    }
}

/** §5: UI-state mascots render at 96dp or larger. */
private val STATE_MASCOT_HEIGHT = 120.dp

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
