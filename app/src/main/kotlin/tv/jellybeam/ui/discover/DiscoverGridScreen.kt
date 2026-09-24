package tv.jellybeam.ui.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import tv.jellybeam.AppGraph
import tv.jellybeam.JellybeamTheme
import tv.jellybeam.R
import tv.jellybeam.ui.focus.FocusRestorer
import tv.jellybeam.ui.focus.asFocusTarget
import tv.jellybeam.ui.focus.focusKey
import tv.jellybeam.ui.focus.rememberFocusMemory
import tv.jellybeam.ui.settings.SettingsChip
import uniffi.jellybeam_core.SeerrBrowseKind
import uniffi.jellybeam_core.SeerrGenre
import uniffi.jellybeam_core.SeerrMediaType

private val PAGE_MARGIN = 32.dp
private val CELL_GAP = 12.dp
private const val GRID_COLUMNS = 8
private val CELL_WIDTH_MIN = 100.dp
private val CELL_WIDTH_MAX = 220.dp
private const val CONTENT_TYPE_POSTER = "poster"

/** [DiscoverSortOption]'s chip label -- verbatim strings, docs/14's own sort list. */
@Composable
private fun DiscoverSortOption.chipLabel(): String = when (this) {
    DiscoverSortOption.POPULARITY -> stringResource(R.string.discover_sort_popularity)
    DiscoverSortOption.RELEASE_DATE -> stringResource(R.string.discover_sort_release_date)
    DiscoverSortOption.RATING -> stringResource(R.string.discover_sort_rating)
    DiscoverSortOption.TITLE_ASC -> stringResource(R.string.discover_sort_az)
    DiscoverSortOption.TITLE_DESC -> stringResource(R.string.discover_sort_za)
}

/**
 * A paged Seerr grid (docs/14-seerr-discover.md): [tv.jellybeam.ui.library.LibraryScreen]'s grid
 * recipe
 * reused for [SeerrCard]s. Sort + genre-filter chips only for
 * [SeerrBrowseKind.MOVIES]/[SeerrBrowseKind.TV]
 * ([supportsSortAndFilter]); paging resets in [DiscoverGridViewModel] on sort/genre change.
 *
 * docs/15-focus-and-selection.md §2-§5: cells keyed `"card:<seerrCardKey>"`; no §2 rule-2 selected
 * item.
 * Once [tv.jellybeam.ui.focus.FocusMemory.seeded], a stale/missing key falls back to the first-visible
 * card;
 * a fresh entry lands on [initialFocusRequester], cell 0.
 */
@Composable
fun DiscoverGridScreen(
    kind: SeerrBrowseKind,
    title: String,
    genreId: Long?,
    genreName: String?,
    onOpenDetail: (mediaType: SeerrMediaType, tmdbId: Long) -> Unit,
    isTop: Boolean = true,
    /** JellybeamRoot's focus gate for this entry -- see [tv.jellybeam.ui.library.LibraryScreen]'s own
     * param doc.
     */
    focusGate: MutableState<Boolean> = remember { mutableStateOf(true) },
    viewModel: DiscoverGridViewModel = viewModel(
        factory = DiscoverGridViewModelFactory(
            gateway = AppGraph.gateway,
            kind = kind,
            initialGenre = genreId?.let { SeerrGenre(id = it, name = genreName.orEmpty()) },
        ),
    ),
) {
    val state by viewModel.state.collectAsState()
    var genrePickerOpen by remember { mutableStateOf(false) }
    var focusedIndex by remember { mutableStateOf<Int?>(null) }
    val gridState = remember { LazyGridState() }
    val initialFocusRequester = remember { FocusRequester() }

    // Keyed on the page cursor too: a page that added nothing leaves the count unchanged, and
    // re-collecting here is what carries paging on to the next page (docs/14).
    LaunchedEffect(gridState, state.cards.size, state.hasMore, state.page) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collect { lastVisible -> viewModel.loadNextPageIfNeeded(lastVisible, GRID_COLUMNS) }
    }

    val memory = rememberFocusMemory()

    // docs/15-focus-and-selection.md §5: scroll a not-yet-composed card into view by key before
    // FocusRestorer retries the lookup. Returns whether the key is a current card, so a stale key
    // short-circuits to the fallback below.
    memory.scrollTo = scrollTo@{ key ->
        val cardKey = key.removePrefix("card:")
        if (cardKey == key) return@scrollTo false
        val index = state.cards.indexOfFirst { seerrCardKey(it.mediaType, it.tmdbId) == cardKey }
        if (index >= 0) gridState.scrollToItem(index)
        index >= 0
    }

    // docs/15-focus-and-selection.md §2 rule 3: only once seeded does a stale/missing key fall back
    // to
    // the first-visible card, never a hardcoded cell 0.
    FocusRestorer(
        memory = memory,
        isTop = isTop,
        focusGate = focusGate,
        ready = state.cards.isNotEmpty(),
        fallback = {
            if (memory.seeded) {
                val cards = state.cards
                val index = gridState.firstVisibleItemIndex.coerceIn(0, cards.lastIndex)
                val nearbyKey = "card:${seerrCardKey(cards[index].mediaType, cards[index].tmdbId)}"
                memory.target(nearbyKey) ?: initialFocusRequester.asFocusTarget()
            } else {
                initialFocusRequester.asFocusTarget()
            }
        },
        tag = "discover-grid",
    )

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(JellybeamTheme.Notte)) {
        val availableWidth = maxWidth - PAGE_MARGIN * 2 - CELL_GAP * (GRID_COLUMNS - 1)
        val cellWidth = (availableWidth / GRID_COLUMNS).coerceIn(CELL_WIDTH_MIN, CELL_WIDTH_MAX)

        Column(modifier = Modifier.fillMaxSize()) {
            BasicText(
                text = title,
                modifier = Modifier.padding(horizontal = PAGE_MARGIN, vertical = 24.dp),
                style = TextStyle(fontFamily = JellybeamTheme.Archivo, fontWeight = FontWeight.Bold, color = JellybeamTheme.Panna, fontSize = 28.sp),
            )

            if (kind.supportsSortAndFilter()) {
                Column(
                    modifier = Modifier.padding(horizontal = PAGE_MARGIN, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DISCOVER_SORT_OPTIONS.forEach { option ->
                            SettingsChip(label = option.chipLabel(), selected = option == state.sort, onSelect = { viewModel.selectSort(option) })
                        }
                    }
                    if (state.genres.isNotEmpty()) {
                        SettingsChip(
                            label = state.selectedGenre?.name ?: stringResource(R.string.discover_genre_all),
                            selected = state.selectedGenre != null,
                            onSelect = { genrePickerOpen = !genrePickerOpen },
                        )
                        if (genrePickerOpen) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SettingsChip(
                                    label = stringResource(R.string.discover_genre_all),
                                    selected = state.selectedGenre == null,
                                    onSelect = { viewModel.selectGenre(null); genrePickerOpen = false },
                                )
                                state.genres.forEach { genre ->
                                    SettingsChip(
                                        label = genre.name,
                                        selected = state.selectedGenre?.id == genre.id,
                                        onSelect = { viewModel.selectGenre(genre); genrePickerOpen = false },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            when {
                state.isLoading -> DiscoverPosterSkeletonRow(count = GRID_COLUMNS, modifier = Modifier.padding(top = 8.dp))
                state.notConfigured -> DiscoverMessage(stringResource(R.string.discover_not_configured))
                state.error != null -> DiscoverErrorMessage(state.error!!, onRetry = viewModel::retry)
                state.cards.isEmpty() -> DiscoverMessage(stringResource(R.string.discover_empty))
                else -> LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(GRID_COLUMNS),
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = PAGE_MARGIN, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(CELL_GAP),
                    verticalArrangement = Arrangement.spacedBy(CELL_GAP),
                ) {
                    itemsIndexed(
                        state.cards,
                        key = { _, card -> seerrCardKey(card.mediaType, card.tmdbId) },
                        contentType = { _, _ -> CONTENT_TYPE_POSTER },
                    ) { index, card ->
                        val cardKey = remember(card.mediaType, card.tmdbId) { seerrCardKey(card.mediaType, card.tmdbId) }
                        val isFocused by remember(index) { derivedStateOf { focusedIndex == index } }
                        var cellModifier = Modifier
                            .focusKey(memory, "card:$cardKey")
                            .onFocusChanged { focusState ->
                                if (focusState.isFocused) {
                                    focusedIndex = index
                                } else if (focusedIndex == index) {
                                    focusedIndex = null
                                }
                            }
                        if (index == 0) cellModifier = cellModifier.focusRequester(initialFocusRequester)

                        SeerrPosterCard(
                            posterUrl = card.posterUrl,
                            title = card.title,
                            year = card.year,
                            availability = card.availability,
                            isFocused = isFocused,
                            onClick = { onOpenDetail(card.mediaType, card.tmdbId) },
                            modifier = cellModifier,
                            width = cellWidth,
                        )
                    }
                }
            }
        }
    }
}

