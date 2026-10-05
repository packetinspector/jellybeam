package tv.jellybeam.ui.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import tv.jellybeam.AppGraph
import tv.jellybeam.JellybeamTheme
import tv.jellybeam.R
import tv.jellybeam.ui.focus.FocusRestorer
import tv.jellybeam.ui.focus.asFocusTarget
import tv.jellybeam.ui.focus.focusKey
import tv.jellybeam.ui.focus.rememberFocusMemory
import uniffi.jellybeam_core.SeerrMediaType

private val PAGE_MARGIN = 32.dp
private val CELL_GAP = 12.dp
private const val GRID_COLUMNS = 8
private val CELL_WIDTH_MIN = 100.dp
private val CELL_WIDTH_MAX = 220.dp
private const val CONTENT_TYPE_POSTER = "poster"

/**
 * Person header + cast/crew credits grid (docs/14). Focus restore as [DiscoverGridScreen]
 * (docs/15 §2-§5): cells keyed `"card:<seerrCardKey>"`, no selected item.
 */
@Composable
fun DiscoverPersonScreen(
    personId: Long,
    onOpenDetail: (mediaType: SeerrMediaType, tmdbId: Long) -> Unit,
    isTop: Boolean = true,
    /** JellybeamRoot's focus gate for this entry (see [tv.jellybeam.ui.library.LibraryScreen]). */
    focusGate: MutableState<Boolean> = remember { mutableStateOf(true) },
    viewModel: DiscoverPersonViewModel = viewModel(factory = DiscoverPersonViewModelFactory(AppGraph.gateway, AppGraph.strings, personId)),
) {
    val state by viewModel.state.collectAsState()
    var focusedIndex by remember { mutableStateOf<Int?>(null) }
    val gridState = remember { LazyGridState() }
    val initialFocusRequester = remember { FocusRequester() }

    val memory = rememberFocusMemory()
    memory.scrollTo = scrollTo@{ key ->
        val cardKey = key.removePrefix("card:")
        if (cardKey == key) return@scrollTo false
        val index = state.credits.indexOfFirst { seerrCardKey(it.mediaType, it.tmdbId) == cardKey }
        if (index >= 0) gridState.scrollToItem(index)
        index >= 0
    }

    FocusRestorer(
        memory = memory,
        isTop = isTop,
        focusGate = focusGate,
        ready = state.credits.isNotEmpty(),
        fallback = {
            if (memory.seeded) {
                val credits = state.credits
                val index = gridState.firstVisibleItemIndex.coerceIn(0, credits.lastIndex)
                val nearbyKey = "card:${seerrCardKey(credits[index].mediaType, credits[index].tmdbId)}"
                memory.target(nearbyKey) ?: initialFocusRequester.asFocusTarget()
            } else {
                initialFocusRequester.asFocusTarget()
            }
        },
        tag = "discover-person",
    )

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(JellybeamTheme.Notte)) {
        val availableWidth = maxWidth - PAGE_MARGIN * 2 - CELL_GAP * (GRID_COLUMNS - 1)
        val cellWidth = (availableWidth / GRID_COLUMNS).coerceIn(CELL_WIDTH_MIN, CELL_WIDTH_MAX)

        Column(modifier = Modifier.fillMaxSize()) {
            if (!state.isLoading && state.name.isNotEmpty()) {
                BasicText(
                    text = state.name,
                    modifier = Modifier.padding(horizontal = PAGE_MARGIN, vertical = 24.dp),
                    style = TextStyle(fontFamily = JellybeamTheme.Archivo, fontWeight = FontWeight.Bold, color = JellybeamTheme.Panna, fontSize = 28.sp),
                )
            }

            when {
                state.isLoading -> DiscoverPosterSkeletonRow(count = GRID_COLUMNS, modifier = Modifier.padding(top = 32.dp))
                state.notConfigured -> DiscoverMessage(stringResource(R.string.discover_not_configured))
                state.error != null -> DiscoverErrorMessage(state.error!!, onRetry = viewModel::retry)
                state.credits.isEmpty() -> DiscoverMessage(stringResource(R.string.discover_empty))
                else -> LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(GRID_COLUMNS),
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = PAGE_MARGIN, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(CELL_GAP),
                    verticalArrangement = Arrangement.spacedBy(CELL_GAP),
                ) {
                    itemsIndexed(
                        state.credits,
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
