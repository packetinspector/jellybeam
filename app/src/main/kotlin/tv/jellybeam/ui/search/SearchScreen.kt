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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import tv.jellybeam.AppGraph
import tv.jellybeam.JellybeamTheme
import tv.jellybeam.R
import tv.jellybeam.ui.cards.CardFormatting
import tv.jellybeam.ui.cards.PosterCard
import tv.jellybeam.ui.focus.FocusMemory
import tv.jellybeam.ui.focus.FocusRestorer
import tv.jellybeam.ui.focus.asFocusTarget
import tv.jellybeam.ui.focus.focusKey
import tv.jellybeam.ui.focus.rememberFocusMemory
import uniffi.jellybeam_core.Card

private val PAGE_MARGIN = 32.dp

/** Mirrors [tv.jellybeam.ui.library.LibraryScreen]'s density-pass constants -- same grid pattern,
 * same reasoning. */
private val CELL_GAP = 12.dp
private const val GRID_COLUMNS = 8
private val CELL_WIDTH_MIN = 100.dp
private val CELL_WIDTH_MAX = 220.dp

/** The grid is homogeneous -- every cell is a [PosterCard] -- one fixed contentType covers it. */
private const val CONTENT_TYPE_POSTER = "poster"

/**
 * Mirror-backed search: a house-style text field up top, live results below in
 * [tv.jellybeam.ui.library.LibraryScreen]'s poster-grid pattern -- [PosterCard] renders whatever
 * item type comes back (Movie/Series/Episode all matched by `JellybeamCore::search`), fail open
 * like every other grid.
 *
 * Focus starts on the text field ([fieldFocusRequester]). D-pad Down is routed explicitly to the
 * first result ([firstResultRequester]) via `focusProperties { down = ... }`, since Compose's
 * default 2D focus search picks whichever cell is spatially nearest the field's full-width
 * bounds (empirically column 4 of an 8-column grid, not column 0). The override only applies
 * while there are results; with none, `down` stays `FocusRequester.Default`.
 *
 * docs/15-focus-and-selection.md §2-§5: [memory] records the last-focused key -- `"field"` or
 * `"result:<Card.id>"`. A fresh entry falls through to the field; a return restores the exact
 * result cell the viewer left. No §2 rule-2 selected item, so `selectedKey` stays `null`.
 * `ready` stays `true` unconditionally since `state.results` is retained `ViewModel` state.
 */
@Composable
fun SearchScreen(
    onOpenDetail: (Card) -> Unit,
    isTop: Boolean = true,
    /** JellybeamRoot's focus gate for this entry -- closed while hidden/transitioning; opened
     * before each explicit focus placement. See [tv.jellybeam.MainActivity.RetainedScreenLayer]. */
    focusGate: MutableState<Boolean> = remember { mutableStateOf(true) },
    viewModel: SearchViewModel = viewModel(factory = SearchViewModelFactory(AppGraph.gateway)),
) {
    val state by viewModel.state.collectAsState()
    var focusedIndex by remember { mutableStateOf<Int?>(null) }
    val fieldFocusRequester = remember { FocusRequester() }
    // Down-routing target for the query field (see kdoc above), attached only to grid index 0.
    val firstResultRequester = remember { FocusRequester() }
    val memory = rememberFocusMemory()
    val gridState = remember { LazyGridState() }

    // §5: scroll a not-yet-composed result cell into view before retrying.
    memory.scrollTo = scrollTo@{ key ->
        val id = key.removePrefix("result:")
        if (id == key) return@scrollTo false
        val index = state.results.indexOfFirst { it.id == id }
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
                hasResults = state.results.isNotEmpty(),
                memory = memory,
                modifier = Modifier
                    .padding(horizontal = PAGE_MARGIN, vertical = 24.dp)
                    .fillMaxWidth(),
            )

            when {
                state.query.isBlank() -> SearchHint(stringResource(R.string.search_empty_hint))
                !state.isSearching && state.results.isEmpty() -> SearchNoResults(stringResource(R.string.search_no_results))
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
                        // docs/07 §4: derivedStateOf, keyed per-index, same reasoning as Library's
                        // grid.
                        val isFocused by remember(index) { derivedStateOf { focusedIndex == index } }

                        PosterCard(
                            card = card,
                            isFocused = isFocused,
                            imageUrl = { itemId, kind, tag -> AppGraph.gateway.imageUrl(itemId, kind, tag, cellImageWidth) },
                            onClick = { onOpenDetail(card) },
                            modifier = Modifier
                                .focusKey(memory, "result:${card.id}")
                                .then(
                                    // Only the current index-0 item claims this requester;
                                    // attaching never moves focus, so a refresh can't steal it.
                                    if (index == 0) Modifier.focusRequester(firstResultRequester) else Modifier
                                )
                                .onFocusChanged { focusState ->
                                    if (focusState.isFocused) {
                                        focusedIndex = index
                                    } else if (focusedIndex == index) {
                                        focusedIndex = null
                                    }
                                },
                            width = cellWidth,
                        )
                    }
                }
            }
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
