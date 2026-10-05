package tv.jellybeam.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import tv.jellybeam.JellybeamTheme
import tv.jellybeam.R
import tv.jellybeam.i18n.rememberUiStrings
import tv.jellybeam.ui.cards.focusRing
import tv.jellybeam.ui.focus.requestFocusWithRetry
import uniffi.jellybeam_core.Decade
import uniffi.jellybeam_core.GridSortField
import uniffi.jellybeam_core.StatusFilter
import uniffi.jellybeam_core.ViewSnapshot
import uniffi.jellybeam_core.WatchedFilter

/** docs/16-library-sort-filter.md §4.3: which panel, if any, the strip has open below FILTER;
 * hoisted to [LibraryScreen] so becoming-top can force it closed.
 */
internal enum class LibraryStripPanel { GENRE, YEARS, TYPE }

/** Which strip row owns focus, for the strip's key edges (§4.2): Left/Right stop at row ends,
 * Up is a no-op on SORT, Down leaves from FILTER or the panel's last wrapped line.
 */
private enum class StripRow { SORT, FILTER, PANEL }

private val STRIP_LABEL_WIDTH = 132.dp
private val STRIP_ROW_GAP = 12.dp
private val STRIP_CHIP_GAP = 8.dp
private val STRIP_VERTICAL_PADDING = 16.dp
private val STRIP_CHIP_HEIGHT = 36.dp
private val STRIP_CHIP_HPADDING = 16.dp

private val SORT_FIELDS = listOf(GridSortField.NAME, GridSortField.DATE_ADDED, GridSortField.YEAR, GridSortField.RUNTIME)
private val DECADES = listOf(Decade.D2020S, Decade.D2010S, Decade.D2000S, Decade.D1990S, Decade.D1980S, Decade.OLDER)

private fun stripLabelStyle() = TextStyle(fontFamily = JellybeamTheme.MartianMono, color = JellybeamTheme.Grigio, fontSize = 12.sp)

/**
 * docs/16-library-sort-filter.md §4.2/§4.3's sort/filter strip: a SurfacePanel band with a SORT
 * row and a FILTER row, plus a panel row when [panel] is non-null. Down from the strip's last row
 * closes it via [onCloseStrip], like Back (see [LibraryScreen] for the origin-focus rules).
 * [activeSortChipRequester] opens the strip on the active sort chip (§4.2). [panel]/
 * [onPanelChange] are hoisted to [LibraryScreen] so a becoming-top return from Detail forces
 * the panel closed.
 */
@Composable
internal fun LibrarySortStrip(
    view: ViewSnapshot,
    state: LibraryUiState,
    viewModel: LibraryViewModel,
    isTop: Boolean,
    focusGate: MutableState<Boolean>,
    activeSortChipRequester: FocusRequester,
    panel: LibraryStripPanel?,
    onPanelChange: (LibraryStripPanel?) -> Unit,
    onCloseStrip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    var focusRow by remember { mutableStateOf(StripRow.SORT) }
    var focusIndex by remember { mutableIntStateOf(0) }

    // Which chip a panel Select/Back returns focus to (§4.3); stays composed while open.
    val genreChipRequester = remember { FocusRequester() }
    val yearsChipRequester = remember { FocusRequester() }
    val typeChipRequester = remember { FocusRequester() }
    var openerRequester by remember { mutableStateOf<FocusRequester?>(null) }
    val panelCurrentRequester = remember { FocusRequester() }

    // Panel-chip index -> its FlowRow top offset (§4.3: only the last line closes the strip).
    // Remembered per [panel] so it's cleared before the chips relay out.
    val panelChipTops = remember(panel) { mutableStateMapOf<Int, Float>() }

    val filterChipCount = 4 + (if (view.isTvLibrary) 1 else 0) + (if (view.isFavorites) 1 else 0)
    val panelChipCount = when (panel) {
        LibraryStripPanel.GENRE -> state.genres.size + 1
        LibraryStripPanel.YEARS -> DECADES.size + 1
        LibraryStripPanel.TYPE -> state.itemTypes.size + 1
        null -> 0
    }

    fun closePanel(commitFocus: Boolean) {
        onPanelChange(null)
        if (commitFocus) {
            val opener = openerRequester
            if (opener != null) {
                scope.launch { requestFocusWithRetry(focusGate) { opener.requestFocus() } }
            }
        }
    }

    // §4.3 "On open, focus the current value's chip".
    LaunchedEffect(panel) {
        if (panel != null) {
            requestFocusWithRetry(focusGate) { panelCurrentRequester.requestFocus() }
        }
    }

    // §4.3 "Back closes unchanged": registers later than LibraryScreen's, so it wins (LIFO).
    BackHandler(enabled = isTop && panel != null) { closePanel(commitFocus = true) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(JellybeamTheme.SurfacePanel),
    ) {
        HairlineRule()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = STRIP_VERTICAL_PADDING)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val rowSize = when (focusRow) {
                        StripRow.SORT -> SORT_FIELDS.size
                        StripRow.FILTER -> filterChipCount
                        StripRow.PANEL -> panelChipCount
                    }
                    when (event.key) {
                        Key.DirectionLeft -> focusIndex <= 0
                        Key.DirectionRight -> focusIndex >= rowSize - 1
                        Key.DirectionUp -> focusRow == StripRow.SORT
                        Key.DirectionDown -> when {
                            // SORT -> FILTER and FILTER -> an open panel are plain focus traversal.
                            focusRow == StripRow.SORT -> false
                            focusRow == StripRow.FILTER && panel != null -> false
                            focusRow == StripRow.PANEL -> {
                                // The panel wraps (§4.3): only the FlowRow's last line closes it.
                                val top = panelChipTops[focusIndex]
                                if (top != null && panelChipTops.values.any { it > top + 1f }) {
                                    false
                                } else {
                                    onCloseStrip()
                                    true
                                }
                            }
                            else -> {
                                onCloseStrip()
                                true
                            }
                        }
                        else -> false
                    }
                },
            verticalArrangement = Arrangement.spacedBy(STRIP_ROW_GAP),
        ) {
            val strings = rememberUiStrings()
            // -- SORT row ---------------------------------------------------
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = PAGE_MARGIN), verticalAlignment = Alignment.CenterVertically) {
                BasicText(text = stringResource(R.string.library_sort_label), modifier = Modifier.width(STRIP_LABEL_WIDTH), style = stripLabelStyle())
                Row(horizontalArrangement = Arrangement.spacedBy(STRIP_CHIP_GAP)) {
                    SORT_FIELDS.forEachIndexed { index, field ->
                        val active = field == state.sort.field
                        StripChip(
                            label = GridSummaryFormat.sortChipLabel(strings, field, state.sort),
                            active = active,
                            onSelect = { viewModel.setSort(field) },
                            focusRequester = if (active) activeSortChipRequester else null,
                            onFocusChange = { focused -> if (focused) { focusRow = StripRow.SORT; focusIndex = index } },
                        )
                    }
                }
            }

            // -- FILTER row ---------------------------------------------------
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = PAGE_MARGIN), verticalAlignment = Alignment.CenterVertically) {
                BasicText(text = stringResource(R.string.library_filter_label), modifier = Modifier.width(STRIP_LABEL_WIDTH), style = stripLabelStyle())
                Row(horizontalArrangement = Arrangement.spacedBy(STRIP_CHIP_GAP)) {
                    var index = 0

                    val watchedLabel = when (state.filters.watched) {
                        WatchedFilter.ANY -> stringResource(R.string.library_watched_any)
                        WatchedFilter.UNWATCHED -> stringResource(R.string.library_watched_unwatched)
                        WatchedFilter.HAS_UNWATCHED -> stringResource(R.string.library_has_unwatched)
                        WatchedFilter.WATCHED -> stringResource(R.string.library_watched_watched)
                    }
                    val watchedIndex = index++
                    StripChip(
                        label = watchedLabel,
                        active = state.filters.watched != WatchedFilter.ANY,
                        onSelect = { viewModel.cycleWatched() },
                        onFocusChange = { focused -> if (focused) { focusRow = StripRow.FILTER; focusIndex = watchedIndex } },
                    )

                    val genreIndex = index++
                    StripChip(
                        label = state.filters.genre ?: stringResource(R.string.library_genre),
                        active = state.filters.genre != null,
                        onSelect = { openerRequester = genreChipRequester; onPanelChange(LibraryStripPanel.GENRE) },
                        focusRequester = genreChipRequester,
                        onFocusChange = { focused -> if (focused) { focusRow = StripRow.FILTER; focusIndex = genreIndex } },
                    )

                    val yearsIndex = index++
                    StripChip(
                        label = state.filters.decade?.let { GridSummaryFormat.decadeLabel(strings, it) } ?: stringResource(R.string.library_years),
                        active = state.filters.decade != null,
                        onSelect = { openerRequester = yearsChipRequester; onPanelChange(LibraryStripPanel.YEARS) },
                        focusRequester = yearsChipRequester,
                        onFocusChange = { focused -> if (focused) { focusRow = StripRow.FILTER; focusIndex = yearsIndex } },
                    )

                    if (view.isFavorites) {
                        val typeIndex = index++
                        StripChip(
                            label = state.filters.itemType?.let { GridSummaryFormat.itemTypeLabel(strings, it) } ?: stringResource(R.string.library_type),
                            active = state.filters.itemType != null,
                            onSelect = { openerRequester = typeChipRequester; onPanelChange(LibraryStripPanel.TYPE) },
                            focusRequester = typeChipRequester,
                            onFocusChange = { focused -> if (focused) { focusRow = StripRow.FILTER; focusIndex = typeIndex } },
                        )
                    }

                    if (view.isTvLibrary) {
                        val statusLabel = when (state.filters.status) {
                            StatusFilter.ANY -> stringResource(R.string.library_status_any)
                            StatusFilter.CONTINUING -> stringResource(R.string.library_status_continuing)
                            StatusFilter.ENDED -> stringResource(R.string.library_status_ended)
                        }
                        val statusIndex = index++
                        StripChip(
                            label = statusLabel,
                            active = state.filters.status != StatusFilter.ANY,
                            onSelect = { viewModel.cycleStatus() },
                            onFocusChange = { focused -> if (focused) { focusRow = StripRow.FILTER; focusIndex = statusIndex } },
                        )
                    }

                    val resetIndex = index
                    StripChip(
                        label = stringResource(R.string.library_reset),
                        active = false,
                        onSelect = { viewModel.resetSortAndFilters() },
                        onFocusChange = { focused -> if (focused) { focusRow = StripRow.FILTER; focusIndex = resetIndex } },
                    )
                }
            }

            // -- Genre/Years panel ---------------------------------------------
            if (panel != null) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = PAGE_MARGIN)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(STRIP_CHIP_GAP), verticalArrangement = Arrangement.spacedBy(STRIP_CHIP_GAP)) {
                        when (panel) {
                            LibraryStripPanel.GENRE -> {
                                val anyActive = state.filters.genre == null
                                StripChip(
                                    label = stringResource(R.string.library_any),
                                    active = anyActive,
                                    onSelect = { viewModel.setGenre(null); closePanel(commitFocus = true) },
                                    focusRequester = if (anyActive) panelCurrentRequester else null,
                                    onFocusChange = { focused -> if (focused) { focusRow = StripRow.PANEL; focusIndex = 0 } },
                                    onTopPositioned = { panelChipTops[0] = it },
                                )
                                state.genres.forEachIndexed { genreIndex, genre ->
                                    val active = state.filters.genre == genre
                                    StripChip(
                                        label = genre,
                                        active = active,
                                        onSelect = { viewModel.setGenre(genre); closePanel(commitFocus = true) },
                                        focusRequester = if (active) panelCurrentRequester else null,
                                        onFocusChange = { focused -> if (focused) { focusRow = StripRow.PANEL; focusIndex = genreIndex + 1 } },
                                        onTopPositioned = { panelChipTops[genreIndex + 1] = it },
                                    )
                                }
                            }
                            LibraryStripPanel.YEARS -> {
                                val anyActive = state.filters.decade == null
                                StripChip(
                                    label = stringResource(R.string.library_any),
                                    active = anyActive,
                                    onSelect = { viewModel.setDecade(null); closePanel(commitFocus = true) },
                                    focusRequester = if (anyActive) panelCurrentRequester else null,
                                    onFocusChange = { focused -> if (focused) { focusRow = StripRow.PANEL; focusIndex = 0 } },
                                    onTopPositioned = { panelChipTops[0] = it },
                                )
                                DECADES.forEachIndexed { decadeIndex, decade ->
                                    val active = state.filters.decade == decade
                                    StripChip(
                                        label = GridSummaryFormat.decadeLabel(strings, decade),
                                        active = active,
                                        onSelect = { viewModel.setDecade(decade); closePanel(commitFocus = true) },
                                        focusRequester = if (active) panelCurrentRequester else null,
                                        onFocusChange = { focused -> if (focused) { focusRow = StripRow.PANEL; focusIndex = decadeIndex + 1 } },
                                        onTopPositioned = { panelChipTops[decadeIndex + 1] = it },
                                    )
                                }
                            }
                            LibraryStripPanel.TYPE -> {
                                val anyActive = state.filters.itemType == null
                                StripChip(
                                    label = stringResource(R.string.library_any),
                                    active = anyActive,
                                    onSelect = { viewModel.setItemType(null); closePanel(commitFocus = true) },
                                    focusRequester = if (anyActive) panelCurrentRequester else null,
                                    onFocusChange = { focused -> if (focused) { focusRow = StripRow.PANEL; focusIndex = 0 } },
                                    onTopPositioned = { panelChipTops[0] = it },
                                )
                                state.itemTypes.forEachIndexed { typeIndex, itemType ->
                                    val active = state.filters.itemType == itemType
                                    StripChip(
                                        label = GridSummaryFormat.itemTypeLabel(strings, itemType),
                                        active = active,
                                        onSelect = { viewModel.setItemType(itemType); closePanel(commitFocus = true) },
                                        focusRequester = if (active) panelCurrentRequester else null,
                                        onFocusChange = { focused -> if (focused) { focusRow = StripRow.PANEL; focusIndex = typeIndex + 1 } },
                                        onTopPositioned = { panelChipTops[typeIndex + 1] = it },
                                    )
                                }
                            }
                            null -> Unit
                        }
                    }
                }
            }
        }
        HairlineRule()
    }
}

@Composable
private fun HairlineRule() {
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(JellybeamTheme.Hairline))
}

/** Strip chip vocabulary (§4.2): fully rounded, Archivo SemiBold 16sp, Pistacchio/Notte fill when
 * [active]; [focusRing] uses the Sheen-on-Pistacchio rule other filled controls use.
 */
@Composable
private fun StripChip(
    label: String,
    active: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onFocusChange: (Boolean) -> Unit = {},
    onTopPositioned: ((Float) -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = modifier
            .focusRing(isFocused, cornerRadius = STRIP_CHIP_HEIGHT / 2, color = if (active) JellybeamTheme.Sheen else JellybeamTheme.Pistacchio)
            .let { if (onTopPositioned != null) it.onGloballyPositioned { coords -> onTopPositioned(coords.positionInParent().y) } else it },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .height(STRIP_CHIP_HEIGHT)
                .let { if (active) it.background(JellybeamTheme.Pistacchio, RoundedCornerShape(50)) else it }
                .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
                .onFocusChanged { onFocusChange(it.isFocused) }
                .clickable(interactionSource = interactionSource, indication = null, onClick = onSelect)
                .padding(horizontal = STRIP_CHIP_HPADDING),
        ) {
            BasicText(
                text = label,
                style = TextStyle(
                    fontFamily = JellybeamTheme.Archivo,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = if (active) JellybeamTheme.Notte else JellybeamTheme.Panna2,
                ),
            )
        }
    }
}
