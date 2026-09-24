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
import uniffi.jellybeam_core.SeerrRequestStatus

private val PAGE_MARGIN = 32.dp
private val CELL_GAP = 12.dp
private const val GRID_COLUMNS = 8
private val CELL_WIDTH_MIN = 100.dp
private val CELL_WIDTH_MAX = 220.dp
private const val CONTENT_TYPE_REQUEST = "request"

/** [SeerrRequestStatus]'s small mono status label (docs/14). */
@Composable
private fun SeerrRequestStatus.label(): String = when (this) {
    SeerrRequestStatus.PENDING -> stringResource(R.string.discover_status_pending)
    SeerrRequestStatus.APPROVED -> stringResource(R.string.discover_status_approved)
    SeerrRequestStatus.DECLINED -> stringResource(R.string.discover_status_declined)
}

/**
 * "My Requests" grid (docs/14); Select opens [DiscoverDetailScreen]. Focus restore as
 * [DiscoverGridScreen] (docs/15 §2-§5): cells keyed `"request:<request_id>"`, no selected item.
 */
@Composable
fun DiscoverRequestsScreen(
    onOpenDetail: (mediaType: SeerrMediaType, tmdbId: Long) -> Unit,
    isTop: Boolean = true,
    /** JellybeamRoot's focus gate for this entry (see [tv.jellybeam.ui.library.LibraryScreen]). */
    focusGate: MutableState<Boolean> = remember { mutableStateOf(true) },
    viewModel: DiscoverRequestsViewModel = viewModel(factory = DiscoverRequestsViewModelFactory(AppGraph.gateway)),
) {
    val state by viewModel.state.collectAsState()
    var focusedIndex by remember { mutableStateOf<Int?>(null) }
    val gridState = remember { LazyGridState() }
    val initialFocusRequester = remember { FocusRequester() }

    val memory = rememberFocusMemory()
    memory.scrollTo = scrollTo@{ key ->
        val requestId = key.removePrefix("request:").toLongOrNull() ?: return@scrollTo false
        val index = state.requests.indexOfFirst { it.requestId == requestId }
        if (index >= 0) gridState.scrollToItem(index)
        index >= 0
    }

    FocusRestorer(
        memory = memory,
        isTop = isTop,
        focusGate = focusGate,
        ready = state.requests.isNotEmpty(),
        fallback = {
            if (memory.seeded) {
                val requests = state.requests
                val index = gridState.firstVisibleItemIndex.coerceIn(0, requests.lastIndex)
                val nearbyKey = "request:${requests[index].requestId}"
                memory.target(nearbyKey) ?: initialFocusRequester.asFocusTarget()
            } else {
                initialFocusRequester.asFocusTarget()
            }
        },
        tag = "discover-requests",
    )

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(JellybeamTheme.Notte)) {
        val availableWidth = maxWidth - PAGE_MARGIN * 2 - CELL_GAP * (GRID_COLUMNS - 1)
        val cellWidth = (availableWidth / GRID_COLUMNS).coerceIn(CELL_WIDTH_MIN, CELL_WIDTH_MAX)

        Column(modifier = Modifier.fillMaxSize()) {
            BasicText(
                text = stringResource(R.string.discover_requests_title),
                modifier = Modifier.padding(horizontal = PAGE_MARGIN, vertical = 24.dp),
                style = TextStyle(fontFamily = JellybeamTheme.Archivo, fontWeight = FontWeight.Bold, color = JellybeamTheme.Panna, fontSize = 28.sp),
            )

            when {
                state.isLoading -> DiscoverPosterSkeletonRow(count = GRID_COLUMNS, modifier = Modifier.padding(top = 8.dp))
                state.notConfigured -> DiscoverMessage(stringResource(R.string.discover_not_configured))
                state.error != null -> DiscoverErrorMessage(state.error!!, onRetry = viewModel::retry)
                state.requests.isEmpty() -> DiscoverMessage(stringResource(R.string.discover_requests_empty))
                else -> LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(GRID_COLUMNS),
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = PAGE_MARGIN, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(CELL_GAP),
                    verticalArrangement = Arrangement.spacedBy(CELL_GAP),
                ) {
                    itemsIndexed(
                        state.requests,
                        // Keyed by request, not card: one title can carry several requests
                        // (SD + 4K, season batches), and LazyGrid throws on a repeated key.
                        key = { _, request -> request.requestId },
                        contentType = { _, _ -> CONTENT_TYPE_REQUEST },
                    ) { index, request ->
                        val isFocused by remember(index) { derivedStateOf { focusedIndex == index } }
                        var cellModifier = Modifier
                            .focusKey(memory, "request:${request.requestId}")
                            .onFocusChanged { focusState ->
                                if (focusState.isFocused) {
                                    focusedIndex = index
                                } else if (focusedIndex == index) {
                                    focusedIndex = null
                                }
                            }
                        if (index == 0) cellModifier = cellModifier.focusRequester(initialFocusRequester)

                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            SeerrPosterCard(
                                posterUrl = request.card.posterUrl,
                                title = request.card.title,
                                year = request.card.year,
                                availability = request.card.availability,
                                isFocused = isFocused,
                                onClick = { onOpenDetail(request.card.mediaType, request.card.tmdbId) },
                                modifier = cellModifier,
                                width = cellWidth,
                            )
                            BasicText(
                                text = request.status.label(),
                                style = TextStyle(fontFamily = JellybeamTheme.MartianMono, color = JellybeamTheme.Panna2, fontSize = 10.sp),
                            )
                        }
                    }
                }
            }
        }
    }
}
