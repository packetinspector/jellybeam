package tv.jellybeam.ui.discover

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import tv.jellybeam.AppGraph
import tv.jellybeam.JellybeamTheme
import tv.jellybeam.R
import tv.jellybeam.ui.focus.FocusMemory
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
 * [tv.jellybeam.ui.search.SearchScreen]'s recipe copied verbatim (field atop a poster grid, 300ms
 * debounce,
 * generation-guarded, Down-from-field routed to the first result), querying `seerr_search` instead
 * of
 * the local mirror (docs/14). `SearchField` below is a private copy of that file's field, same
 * convention.
 *
 * docs/15-focus-and-selection.md §2-§5, same shape as [tv.jellybeam.ui.search.SearchScreen]: [memory]
 * keys
 * `"field"` or `"result:<seerrCardKey>"`. No §2 rule-2 selected item, so `selectedKey` stays
 * `null`.
 * `ready` stays `true` since `state.results` is retained `ViewModel` state.
 */
@Composable
fun DiscoverSearchScreen(
    onOpenDetail: (mediaType: SeerrMediaType, tmdbId: Long) -> Unit,
    isTop: Boolean = true,
    /** JellybeamRoot's focus gate for this entry -- see [tv.jellybeam.ui.search.SearchScreen]'s own param
     * doc.
     */
    focusGate: MutableState<Boolean> = remember { mutableStateOf(true) },
    viewModel: DiscoverSearchViewModel = viewModel(factory = DiscoverSearchViewModelFactory(AppGraph.gateway)),
) {
    val state by viewModel.state.collectAsState()
    var focusedIndex by remember { mutableStateOf<Int?>(null) }
    val fieldFocusRequester = remember { FocusRequester() }
    val firstResultRequester = remember { FocusRequester() }
    val memory = rememberFocusMemory()
    val gridState = remember { LazyGridState() }

    // docs/15-focus-and-selection.md §5: scroll a not-yet-composed result cell into view by key
    // before
    // FocusRestorer retries the lookup. A key from a stale query short-circuits to the fallback.
    memory.scrollTo = scrollTo@{ key ->
        val cardKey = key.removePrefix("result:")
        if (cardKey == key) return@scrollTo false
        val index = state.results.indexOfFirst { seerrCardKey(it.mediaType, it.tmdbId) == cardKey }
        if (index >= 0) gridState.scrollToItem(index)
        index >= 0
    }

    FocusRestorer(
        memory = memory,
        isTop = isTop,
        focusGate = focusGate,
        ready = true,
        fallback = { fieldFocusRequester.asFocusTarget() },
        tag = "discover-search",
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(JellybeamTheme.Notte),
    ) {
        val availableWidth = maxWidth - PAGE_MARGIN * 2 - CELL_GAP * (GRID_COLUMNS - 1)
        val cellWidth = (availableWidth / GRID_COLUMNS).coerceIn(CELL_WIDTH_MIN, CELL_WIDTH_MAX)

        Column(modifier = Modifier.fillMaxSize()) {
            DiscoverSearchField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                focusRequester = fieldFocusRequester,
                firstResultRequester = firstResultRequester,
                hasResults = state.results.isNotEmpty(),
                memory = memory,
                modifier = Modifier
                    .padding(horizontal = PAGE_MARGIN, vertical = 24.dp)
                    .fillMaxWidth(),
            )

            when {
                state.query.isBlank() -> DiscoverSearchHint(stringResource(R.string.discover_search_empty_hint))
                !state.isSearching && state.results.isEmpty() -> DiscoverSearchHint(stringResource(R.string.discover_search_no_results))
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
                        key = { _, card -> seerrCardKey(card.mediaType, card.tmdbId) },
                        contentType = { _, _ -> CONTENT_TYPE_POSTER },
                    ) { index, card ->
                        val isFocused by remember(index) { derivedStateOf { focusedIndex == index } }

                        SeerrPosterCard(
                            posterUrl = card.posterUrl,
                            title = card.title,
                            year = card.year,
                            availability = card.availability,
                            isFocused = isFocused,
                            onClick = { onOpenDetail(card.mediaType, card.tmdbId) },
                            width = cellWidth,
                            modifier = Modifier
                                .focusKey(memory, "result:${seerrCardKey(card.mediaType, card.tmdbId)}")
                                .then(if (index == 0) Modifier.focusRequester(firstResultRequester) else Modifier)
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
}

/** [tv.jellybeam.ui.search.SearchScreen]'s private `SearchField`, copied verbatim (Grigio label,
 * Surface box, Hairline/Pistacchio-on-focus border, Archivo text).
 */
@Composable
private fun DiscoverSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    focusRequester: FocusRequester,
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
            text = stringResource(R.string.discover_search_field_label),
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
                        down = if (hasResults) firstResultRequester else FocusRequester.Default
                    },
                textStyle = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Panna, fontSize = 16.sp),
                singleLine = true,
                cursorBrush = SolidColor(JellybeamTheme.Pistacchio),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }),
                interactionSource = interactionSource,
            )
        }
    }
}

@Composable
private fun DiscoverSearchHint(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(horizontal = PAGE_MARGIN)) {
        BasicText(text = text, style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Grigio, fontSize = 16.sp))
    }
}
