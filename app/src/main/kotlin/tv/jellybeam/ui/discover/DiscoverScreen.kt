package tv.jellybeam.ui.discover

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import tv.jellybeam.AppGraph
import tv.jellybeam.JellybeamTheme
import tv.jellybeam.R
import tv.jellybeam.ui.cards.POSTER_CELL_WIDTH
import tv.jellybeam.ui.cards.rememberShelfBringIntoViewSpec
import tv.jellybeam.ui.cards.rememberSkeletonPulseAlpha
import tv.jellybeam.ui.focus.FocusMemory
import tv.jellybeam.ui.focus.FocusRestorer
import tv.jellybeam.ui.focus.asFocusTarget
import tv.jellybeam.ui.focus.focusKey
import tv.jellybeam.ui.focus.rememberFocusMemory
import tv.jellybeam.ui.settings.SettingsChip
import uniffi.jellybeam_core.SeerrBrowseKind
import uniffi.jellybeam_core.SeerrHomeRow
import uniffi.jellybeam_core.SeerrMediaType

private val PAGE_MARGIN = 32.dp
private val CELL_GAP = 12.dp

/** Focus-key prefix for the top action-chip row -- never collides with a real shelf's own
 * `"shelf:<rowId>/card:<key>"` key (row ids come from Rust: `"trending"`, `"movies"`, etc). */
private const val CHIP_ID_SEARCH = "chip:search"
private const val CHIP_ID_MY_REQUESTS = "chip:my-requests"
private const val CHIP_ID_MOVIES = "chip:movies"
private const val CHIP_ID_TV = "chip:tv"

/**
 * Discover home (docs/14-seerr-discover.md UI section): a top action-chip row (Search / My
 * Requests / Movies / TV) plus an eager [Column] of [SeerrHomeRow] shelves, not a `LazyColumn`.
 * Row titles render verbatim (server-string rule).
 *
 * docs/15-focus-and-selection.md §2-§5: every focusable is keyed directly, group baked into the
 * key so shelves can't collide (`"chip:search"`/etc, `"shelf:<row.id>/card:<seerrCardKey>"`). No
 * §2 rule-2 selected item, so `selectedKey` stays `null`. First open lands on [CHIP_ID_SEARCH]; a
 * becoming-top return restores the last-focused chip/cell (scrolling its [LazyListState] via
 * [tv.jellybeam.ui.focus.FocusMemory.scrollTo]), falling back to the first action chip if gone.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DiscoverScreen(
    onOpenGrid: (kind: SeerrBrowseKind, title: String, genreId: Long?, genreName: String?) -> Unit,
    onOpenDetail: (mediaType: SeerrMediaType, tmdbId: Long) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenMyRequests: () -> Unit,
    isTop: Boolean = true,
    /** JellybeamRoot's focus gate for this entry -- see [tv.jellybeam.ui.search.SearchScreen]'s own
     * param doc. */
    focusGate: MutableState<Boolean> = remember { mutableStateOf(true) },
    viewModel: DiscoverViewModel = viewModel(factory = DiscoverViewModelFactory(AppGraph.gateway, AppGraph.strings)),
) {
    val state by viewModel.state.collectAsState()
    val moviesLabel = stringResource(R.string.discover_chip_movies)
    val tvLabel = stringResource(R.string.discover_chip_tv)

    val memory = rememberFocusMemory()

    // Search chip keeps a plain requester only for the fresh-entry fallback (§2 rule 3).
    val chipSearchRequester = remember { FocusRequester() }

    // One LazyListState per shelf, keyed by row id -- same "shelfListStates" primitive as
    // HomeScreen.
    val shelfListStates = remember { mutableStateMapOf<String, LazyListState>() }

    // §5: scroll a not-yet-composed shelf cell into view by key before FocusRestorer retries.
    // Chip keys are never scrolled, so they never reach this lambda.
    memory.scrollTo = scrollTo@{ key ->
        if (!key.startsWith("shelf:")) return@scrollTo false
        val rest = key.removePrefix("shelf:")
        val cardMarker = "/card:"
        val markerIndex = rest.indexOf(cardMarker)
        if (markerIndex < 0) return@scrollTo false
        val rowId = rest.substring(0, markerIndex)
        val cardKey = rest.substring(markerIndex + cardMarker.length)
        val row = state.rows.firstOrNull { it.id == rowId } ?: return@scrollTo false
        val index = row.cards.indexOfFirst { seerrCardKey(it.mediaType, it.tmdbId) == cardKey }
        if (index < 0) return@scrollTo false
        val listState = shelfListStates[rowId] ?: return@scrollTo false
        listState.scrollToItem(index)
        true
    }

    // §2 rule 3: a stale key whose shelf still has cards restores to that shelf's nearest
    // visible cell; a gone shelf, non-shelf key, or fresh entry falls to the first action chip.
    FocusRestorer(
        memory = memory,
        isTop = isTop,
        focusGate = focusGate,
        ready = !state.isLoading,
        fallback = {
            val staleKey = memory.lastKey
            val rowId = staleKey?.takeIf { it.startsWith("shelf:") }?.removePrefix("shelf:")?.substringBefore("/card:")
            val row = rowId?.let { id -> state.rows.firstOrNull { it.id == id } }
            if (row != null && row.cards.isNotEmpty()) {
                val listState = shelfListStates[row.id]
                val index = (listState?.firstVisibleItemIndex ?: 0).coerceIn(0, row.cards.lastIndex)
                val nearbyCard = row.cards[index]
                val nearbyKey = "shelf:${row.id}/card:${seerrCardKey(nearbyCard.mediaType, nearbyCard.tmdbId)}"
                memory.target(nearbyKey) ?: chipSearchRequester.asFocusTarget()
            } else {
                chipSearchRequester.asFocusTarget()
            }
        },
        tag = "discover-home",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(JellybeamTheme.Notte)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = PAGE_MARGIN, vertical = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsChip(
                label = stringResource(R.string.discover_chip_search),
                selected = false,
                onSelect = onOpenSearch,
                modifier = Modifier
                    .focusKey(memory, CHIP_ID_SEARCH)
                    .focusRequester(chipSearchRequester),
            )
            ChipGap()
            SettingsChip(
                label = stringResource(R.string.discover_chip_my_requests),
                selected = false,
                onSelect = onOpenMyRequests,
                modifier = Modifier.focusKey(memory, CHIP_ID_MY_REQUESTS),
            )
            ChipGap()
            SettingsChip(
                label = moviesLabel,
                selected = false,
                onSelect = { onOpenGrid(SeerrBrowseKind.MOVIES, moviesLabel, null, null) },
                modifier = Modifier.focusKey(memory, CHIP_ID_MOVIES),
            )
            ChipGap()
            SettingsChip(
                label = tvLabel,
                selected = false,
                onSelect = { onOpenGrid(SeerrBrowseKind.TV, tvLabel, null, null) },
                modifier = Modifier.focusKey(memory, CHIP_ID_TV),
            )
        }

        when {
            state.isLoading -> DiscoverHomeSkeleton()
            state.notConfigured -> DiscoverMessage(stringResource(R.string.discover_not_configured))
            state.error != null -> DiscoverErrorMessage(state.error!!, onRetry = viewModel::retry)
            state.rows.isEmpty() -> DiscoverMessage(stringResource(R.string.discover_empty))
            else -> Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                state.rows.forEach { row ->
                    if (row.cards.isNotEmpty()) {
                        val listState = shelfListStates.getOrPut(row.id) { LazyListState() }
                        DiscoverShelf(
                            row = row,
                            listState = listState,
                            onOpenDetail = onOpenDetail,
                            memory = memory,
                        )
                    }
                }
            }
        }
    }
}

/** A plain horizontal gap between action chips -- this row is short and fixed, not worth a
 * `FlowRow`/`Arrangement.spacedBy` retrofit. */
@Composable
private fun ChipGap() {
    Box(modifier = Modifier.size(12.dp))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DiscoverShelf(
    row: SeerrHomeRow,
    listState: LazyListState,
    onOpenDetail: (SeerrMediaType, Long) -> Unit,
    memory: FocusMemory,
) {
    var focusedIndex by remember(row.id) { mutableStateOf<Int?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicText(
            text = row.title,
            modifier = Modifier.padding(horizontal = PAGE_MARGIN),
            style = TextStyle(fontFamily = JellybeamTheme.Archivo, fontWeight = FontWeight.Bold, color = JellybeamTheme.Panna, fontSize = 17.sp),
        )
        // Pins the focused item at PAGE_MARGIN when scrolled, matching this row's unscrolled
        // contentPadding.
        CompositionLocalProvider(LocalBringIntoViewSpec provides rememberShelfBringIntoViewSpec(startMargin = PAGE_MARGIN)) {
            LazyRow(
                state = listState,
                horizontalArrangement = Arrangement.spacedBy(CELL_GAP),
                contentPadding = PaddingValues(horizontal = PAGE_MARGIN),
            ) {
                itemsIndexed(
                    row.cards,
                    key = { _, card -> seerrCardKey(card.mediaType, card.tmdbId) },
                    contentType = { _, _ -> "poster" },
                ) { index, card ->
                    val cardKey = remember(card.mediaType, card.tmdbId) { seerrCardKey(card.mediaType, card.tmdbId) }
                    val isFocused by remember(index) { derivedStateOf { focusedIndex == index } }
                    SeerrPosterCard(
                        posterUrl = card.posterUrl,
                        title = card.title,
                        year = card.year,
                        availability = card.availability,
                        isFocused = isFocused,
                        onClick = { onOpenDetail(card.mediaType, card.tmdbId) },
                        width = POSTER_CELL_WIDTH,
                        modifier = Modifier
                            .focusKey(memory, "shelf:${row.id}/card:$cardKey")
                            .onFocusChanged { focusState ->
                                if (focusState.isFocused) {
                                    focusedIndex = index
                                } else if (focusedIndex == index) {
                                    focusedIndex = null
                                }
                            },
                    )
                }
            }
        }
    }
}

/** [tv.jellybeam.ui.home.HomeScreen]'s loading-skeleton philosophy (shaped, pulsing placeholder)
 * reduced to a few pulsing shelf-sized blocks, no hero. */
@Composable
private fun DiscoverHomeSkeleton() {
    val pulseAlpha = rememberSkeletonPulseAlpha()
    Column(
        modifier = Modifier.padding(horizontal = PAGE_MARGIN),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        repeat(3) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(POSTER_CELL_WIDTH * 1.5f)
                    .graphicsLayer { alpha = pulseAlpha.value }
                    .background(JellybeamTheme.SurfaceRaised),
            )
        }
    }
}
