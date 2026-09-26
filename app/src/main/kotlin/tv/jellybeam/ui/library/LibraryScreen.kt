package tv.jellybeam.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.LocalDate
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import tv.jellybeam.AppGraph
import tv.jellybeam.JellybeamTheme
import tv.jellybeam.R
import tv.jellybeam.ui.cards.CardFormatting
import tv.jellybeam.ui.cards.PosterCard
import tv.jellybeam.ui.cards.PreloadOnDwell
import tv.jellybeam.ui.focus.FocusRestorer
import tv.jellybeam.ui.focus.RefreshRestoreOwner
import tv.jellybeam.ui.focus.asFocusTarget
import tv.jellybeam.ui.focus.focusKey
import tv.jellybeam.ui.focus.placeByKeys
import tv.jellybeam.ui.focus.refreshGuardEligible
import tv.jellybeam.ui.focus.releaseCancelledFreezeInPlace
import tv.jellybeam.ui.focus.restoreNow
import tv.jellybeam.ui.focus.rememberFocusMemory
import tv.jellybeam.ui.focus.requestFocusWithRetry
import tv.jellybeam.ui.nav.LocalDrawerFocusCoordinator
import uniffi.jellybeam_core.Card
import uniffi.jellybeam_core.GridCounts
import uniffi.jellybeam_core.GridFilters
import uniffi.jellybeam_core.GridSort
import uniffi.jellybeam_core.StatusFilter
import uniffi.jellybeam_core.ViewKind
import uniffi.jellybeam_core.ViewSnapshot
import uniffi.jellybeam_core.WatchedFilter

/** Shared with [ChannelFolderList]: both title blocks use the same horizontal margin. */
internal val PAGE_MARGIN = 32.dp

/** 8 columns at a 12dp gap puts the cell width at ~101dp on the reference 896dp-wide viewport;
 * [CELL_WIDTH_MIN] is set just under that so it floors smaller windows.
 */
private val CELL_GAP = 12.dp
private const val GRID_COLUMNS = 8
private val CELL_WIDTH_MIN = 100.dp
private val CELL_WIDTH_MAX = 220.dp

/** The grid is homogeneous, so one fixed contentType covers every cell. */
private const val CONTENT_TYPE_POSTER = "poster"

/** docs/16 §4.1: title padding shrinks on a [ViewSnapshot.supportsSortFilter] library so
 * title+summary occupy about the old title-only block height plus the summary line.
 */
private val TITLE_TOP_PADDING_COMPACT = 16.dp
private val SUMMARY_TOP_PADDING = 4.dp
private val SUMMARY_BOTTOM_PADDING = 16.dp

/** docs/16 §4.1: the summary line's separator padding. */
private val SUMMARY_SEPARATOR_HPADDING = 12.dp

/** Whether any [GridFilters] departs from its default (docs/16 §4.1's "filtered" branch, §4.5's
 * empty-result gate).
 */
private fun GridFilters.isAnyActive(): Boolean =
    watched != WatchedFilter.ANY || genre != null || decade != null || status != StatusFilter.ANY

/**
 * A `ViewKind.LIBRARY` view's full poster grid (docs/07 §3): title shows the server-configured
 * name verbatim, `GridCells.Fixed(8)`, cell width clamped into [[CELL_WIDTH_MIN],
 * [CELL_WIDTH_MAX]]dp.
 *
 * docs/16-library-sort-filter.md: a [ViewSnapshot.supportsSortFilter] library (Movies/TV Shows)
 * additionally gets a summary line under the title ([LibrarySummaryLine]), a sort/filter strip
 * that Up from the top row opens ([LibrarySortStrip]), and an index rail at the grid's right edge
 * that Right from the last column opens ([IndexRail]). Every other `LIBRARY` view keeps the plain
 * grid.
 *
 * Any other [ViewKind] (both [ViewKind.isLive]) is diverted to [ChannelFolderList] instead: a
 * plugin channel's recordings have no art, no runtime, and mostly identical names.
 *
 * [isTop]/[focusGate] are [tv.jellybeam.MainActivity]'s `JellybeamRoot`/`RetainedScreenLayer` wiring,
 * same contract as every other retained screen.
 *
 * docs/15-focus-and-selection.md §2-§5: [tv.jellybeam.ui.focus.FocusMemory] keys every cell
 * `"card:<Card.id>"`; [tv.jellybeam.ui.focus.FocusRestorer] is the one becoming-top effect. No §2
 * rule-2 selected item on a library grid, so `selectedKey` stays `null`. Once
 * [tv.jellybeam.ui.focus.FocusMemory.seeded], a stale-key fallback looks up the currently
 * first-visible card's key rather than snapping to cell 0; a fresh entry lands on
 * [initialFocusRequester]. docs/16 §6: the strip and rail hold their own origin-poster keys
 * ([stripOriginKey]/[railOriginKey]) and are never a becoming-top restore target;
 * `LaunchedEffect(isTop)` below forces both closed on any return.
 *
 * The strip closes outright ([closeStrip]) whenever focus would leave it downward or on Back,
 * same as a becoming-top return. The index rail does not compress while the strip is
 * open/closing.
 */
@Composable
fun LibraryScreen(
    view: ViewSnapshot,
    onOpenDetail: (Card) -> Unit,
    isTop: Boolean = true,
    /** JellybeamRoot's focus gate: closed while hidden/transitioning; opened before each explicit
     * focus placement. See [tv.jellybeam.MainActivity.RetainedScreenLayer].
     */
    focusGate: MutableState<Boolean> = remember { mutableStateOf(true) },
    viewModel: LibraryViewModel = viewModel(
        key = "library-${view.id}",
        factory = LibraryViewModelFactory(AppGraph.gateway, view),
    ),
) {
    val state by viewModel.state.collectAsState()

    if (view.kind != ViewKind.LIBRARY) {
        ChannelFolderList(
            view = view,
            state = state,
            viewModel = viewModel,
            onOpenDetail = onOpenDetail,
            isTop = isTop,
            focusGate = focusGate,
        )
        return
    }

    var focusedIndex by remember { mutableStateOf<Int?>(null) }
    val initialFocusRequester = remember { FocusRequester() }
    val gridState = remember { LazyGridState() }

    // Page Up/Down's own scroll-then-focus job, serialized so a held/rapid key doesn't stack.
    val pagingScope = rememberCoroutineScope()
    var pagingJob by remember { mutableStateOf<Job?>(null) }

    // docs/16 §4.2/§4.4: the strip and rail's own open/focus state, plus a dedicated scope for
    // their (non-paging) focus asks.
    val scope = rememberCoroutineScope()
    var stripOpen by remember { mutableStateOf(false) }
    var stripOriginKey by remember { mutableStateOf<String?>(null) }
    // docs/16 §6: the sort/filters in effect when the strip opened; [closeStrip] compares these
    // against the current [state] to decide whether [stripOriginKey] is still safe to refocus.
    var stripOriginSort by remember { mutableStateOf<GridSort?>(null) }
    var stripOriginFilters by remember { mutableStateOf<GridFilters?>(null) }
    var panel by remember { mutableStateOf<LibraryStripPanel?>(null) }
    val activeSortChipRequester = remember { FocusRequester() }

    var railIndex by remember { mutableIntStateOf(0) }
    var railOriginKey by remember { mutableStateOf<String?>(null) }
    val railRequester = remember { FocusRequester() }
    val railEntries = remember(state.sort, state.groups) { IndexRailModel.build(state.sort, state.groups, LocalDate.now()) }

    // Request the next page when focus/scroll enters the last three loaded rows.
    LaunchedEffect(gridState, state.items.size, state.hasMore) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .filter { it >= state.items.size - GRID_COLUMNS * 3 }
            .collect { viewModel.loadNextPage() }
    }

    // Always a no-op here: LibraryViewModel.onBecameTop is a no-op for LIBRARY, since mirror
    // change events already cover it. Kept for symmetry with [ChannelFolderList]'s effect.
    LaunchedEffect(isTop) {
        if (isTop) viewModel.onBecameTop()
    }

    // docs/16 §4.6, docs/17-mini-player.md §6: feeds this screen's top-of-stack-and-resumed
    // signal into LibraryViewModel.changeRefreshScheduler.
    LaunchedEffect(isTop) {
        viewModel.setActive(isTop)
    }

    // docs/16 §4.2/§6: a return to top always finds the strip closed and its panel gone; neither
    // is a becoming-top restore target.
    LaunchedEffect(isTop) {
        if (!isTop) {
            stripOpen = false
            panel = null
        }
    }

    val memory = rememberFocusMemory(view.id)
    val lifecycleOwner = LocalLifecycleOwner.current
    // docs/15-focus-and-selection.md §3: whether any descendant of this screen's root Box holds
    // focus. Read by the refresh guard below (`false` after a refresh disposes the focused cell
    // with nothing re-placing it); `onFocusChanged.hasFocus` covers the strip and rail too.
    var libraryHasFocus by remember { mutableStateOf(false) }

    // [RefreshRestoreOwner] (docs/15 §3) owns the refresh guard's `restoreNow` coroutine and its
    // freeze. [refreshGuardEligible] is the shared predicate this guard and Home's read.
    val refreshOwner = remember { RefreshRestoreOwner() }
    // Live top state read via `rememberUpdatedState`, never the captured `isTop` parameter: the
    // refresh guard's eligibility and the drawer close's in-place check.
    val latestIsTop by rememberUpdatedState(isTop)

    // Mirrors HomeScreen's ON_PAUSE branch: losing RESUMED must cancel an in-flight
    // refresh-restore job the same way losing `isTop` does below.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) refreshOwner.ownershipLost()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // docs/15 §3: opening the drawer leaves this screen top+resumed with focus moved outside its
    // root and [memory] not frozen, so a live refresh while open could satisfy the guard below
    // and steal focus back into the grid. [drawerOpen] closes that gap: matching
    // [tv.jellybeam.ui.nav.DrawerFocusCoordinator]'s contract, this returns `false` from
    // [onDrawerClosed] and lets [tv.jellybeam.ui.nav.NavDrawerHost]'s generic restore keep going.
    var drawerOpen by remember { mutableStateOf(false) }
    val drawerFocusCoordinator = LocalDrawerFocusCoordinator.current
    if (drawerFocusCoordinator != null) {
        // `SideEffect`, not `remember`, so these closures see the current recomposition's state.
        SideEffect {
            drawerFocusCoordinator.onDrawerOpened = {
                drawerOpen = true
                refreshOwner.ownershipLost()
            }
            drawerFocusCoordinator.onDrawerClosed = { navigatingAway ->
                drawerOpen = false
                // In-place close only: a navigating-away close must keep the navigation freeze.
                refreshOwner.releaseCancelledFreezeInPlace(memory, ownsFocus = !navigatingAway && latestIsTop)
                false
            }
        }
    }

    // docs/15-focus-and-selection.md §5: scroll a not-yet-composed card into view by id before
    // FocusRestorer retries the lookup. A stale id short-circuits to the fallback below.
    memory.scrollTo = scrollTo@{ key ->
        val id = key.removePrefix("card:")
        if (id == key) return@scrollTo false
        val index = state.items.indexOfFirst { it.id == id }
        if (index >= 0) gridState.scrollToItem(index)
        index >= 0
    }

    // docs/15 §2 rule 3: only once [tv.jellybeam.ui.focus.FocusMemory.seeded] does a stale/missing
    // key fall back to the currently first-visible card, never a hardcoded cell 0; a fresh entry
    // lands on [initialFocusRequester]. Hoisted to a `val` so the refresh guard below shares one
    // fallback with [FocusRestorer], which also guards an empty grid indexing into an empty list.
    val libraryFallback = fallback@{
        if (memory.seeded) {
            val items = state.items
            if (items.isEmpty()) return@fallback initialFocusRequester.asFocusTarget()
            val index = gridState.firstVisibleItemIndex.coerceIn(0, items.lastIndex)
            memory.target("card:${items[index].id}") ?: initialFocusRequester.asFocusTarget()
        } else {
            initialFocusRequester.asFocusTarget()
        }
    }

    FocusRestorer(
        memory = memory,
        isTop = isTop,
        focusGate = focusGate,
        ready = state.items.isNotEmpty(),
        fallback = libraryFallback,
        tag = "library",
    )

    // docs/15 §3: "Content/library refresh -- kept if the key still resolves; otherwise rule 2,
    // then 3. Never index." A live mirror change, re-sort, or filter change can replace
    // [state.items] with a fresh list that no longer contains the focused card's id, so Compose
    // disposes the focused cell with nothing re-placing it. `frame` lets Compose apply the new
    // list before `eligible` reads [libraryHasFocus]; only a refresh that left focus nowhere
    // triggers a restore. No §2 rule 2 selected item here, so `memory.restoreNow` tries
    // [FocusMemory.lastKey] and, on a miss, falls to [libraryFallback].
    //
    // [latestIsTop]/[drawerOpen] are read live (not the captured `isTop` parameter), so a
    // navigation or drawer-open landing mid-`frame` can never be outrun by a stale read.
    LaunchedEffect(state.items, state.counts, state.groups) {
        refreshOwner.onContentChanged(
            scope = scope,
            frame = { withFrameNanos {} },
            eligible = { ownsFreeze ->
                !drawerOpen &&
                    refreshGuardEligible(
                        isTop = latestIsTop,
                        seeded = memory.seeded,
                        resumed = lifecycleOwner.lifecycle.currentState == Lifecycle.State.RESUMED,
                        hasFocus = libraryHasFocus,
                        frozen = memory.frozen,
                        ownsFreeze = ownsFreeze,
                    )
            },
            restore = {
                memory.restoreNow(focusGate, fallback = libraryFallback, tag = "library-refresh")
            },
        )
    }

    // This guard's restore work must not outlive Library's ownership of focus; reruns on every
    // `isTop` value.
    LaunchedEffect(isTop) {
        if (!isTop) refreshOwner.ownershipLost()
    }

    // docs/16 §4.2/§6: Back and Down-off-the-strip's-last-row share this one closing path.
    // [stripOriginKey] is only safe to refocus if [state]'s sort/filters still match
    // [stripOriginSort]/[stripOriginFilters]; otherwise the origin card may have moved or dropped
    // out of the filtered set, and this lands on the grid's first item instead. Either branch
    // finishes with `animateScrollToItem(0)`, since a plain bring-into-view can leave row 0
    // partially scrolled out of the space the collapsing strip just revealed.
    val closeStrip: () -> Unit = {
        stripOpen = false
        panel = null
        val originKey = stripOriginKey
        val moved = stripOriginSort != null &&
            (stripOriginSort != state.sort || stripOriginFilters != state.filters)
        scope.launch {
            if (moved || originKey == null) {
                gridState.scrollToItem(0)
                val firstId = state.items.firstOrNull()?.id
                if (firstId != null) {
                    memory.placeByKeys(listOf("card:$firstId"), focusGate)
                } else {
                    requestFocusWithRetry(focusGate) { initialFocusRequester.requestFocus() }
                }
            } else {
                memory.placeByKeys(listOf(originKey), focusGate)
            }
            gridState.animateScrollToItem(0)
        }
    }

    // `panel == null` leaves an open panel's own Back handling to [LibrarySortStrip] (offered
    // first, LIFO).
    BackHandler(enabled = isTop && stripOpen && panel == null) { closeStrip() }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(JellybeamTheme.Notte)
            // Tracks [libraryHasFocus] for the refresh guard above (no `focusGroup()` needed).
            .onFocusChanged { libraryHasFocus = it.hasFocus }
            .onPreviewKeyEvent { event ->
                if (view.kind == ViewKind.LIBRARY && GridPaging.isPagingKey(event.key)) {
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    // A held-key repeat is a no-op rather than re-triggering a scroll per frame.
                    if (event.nativeKeyEvent.repeatCount != 0) return@onPreviewKeyEvent true

                    val layoutInfo = gridState.layoutInfo
                    val visible = layoutInfo.visibleItemsInfo.map {
                        GridPaging.VisibleCell(index = it.index, top = it.offset.y, height = it.size.height)
                    }
                    val rows = GridPaging.fullyVisibleRows(
                        visible,
                        GRID_COLUMNS,
                        layoutInfo.viewportStartOffset,
                        layoutInfo.viewportEndOffset,
                    )
                    val current = focusedIndex ?: gridState.firstVisibleItemIndex
                    val target = GridPaging.targetIndex(
                        current = current,
                        itemCount = state.items.size,
                        columns = GRID_COLUMNS,
                        rows = rows,
                        forward = GridPaging.isForward(event.key),
                    )
                    if (target != null) {
                        val card = state.items[target]
                        pagingJob?.cancel()
                        pagingJob = pagingScope.launch {
                            gridState.scrollToItem(GridPaging.rowStart(target, GRID_COLUMNS))
                            // The target row may have just entered composition -- retry across a
                            // few frames.
                            requestFocusWithRetry(focusGate) {
                                memory.target("card:${card.id}")?.requestFocus() ?: false
                            }
                        }
                    }
                    return@onPreviewKeyEvent true
                }
                // docs/16 §4.2/§6: Up from the top row opens the strip fresh, recording the
                // origin poster and sort/filters so [closeStrip] can tell if the grid moved.
                if (view.supportsSortFilter && event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp && !stripOpen) {
                    val idx = focusedIndex
                    if (idx != null && idx < GRID_COLUMNS) {
                        stripOriginKey = "card:${state.items[idx].id}"
                        stripOriginSort = state.sort
                        stripOriginFilters = state.filters
                        stripOpen = true
                        scope.launch {
                            requestFocusWithRetry(focusGate) {
                                activeSortChipRequester.requestFocus()
                                true
                            }
                        }
                        return@onPreviewKeyEvent true
                    }
                }
                // docs/16 §4.4: Right from the last column (or item) opens the rail at the
                // entry containing the focused poster.
                if (view.supportsSortFilter && event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight) {
                    val idx = focusedIndex
                    if (idx != null && (idx % GRID_COLUMNS == GRID_COLUMNS - 1 || idx == state.items.lastIndex)) {
                        railOriginKey = "card:${state.items[idx].id}"
                        railIndex = IndexRailModel.entryIndexForOffset(railEntries, idx)
                        railRequester.requestFocus()
                        return@onPreviewKeyEvent true
                    }
                }
                false
            },
    ) {
        val availableWidth = maxWidth - PAGE_MARGIN * 2 - CELL_GAP * (GRID_COLUMNS - 1) -
            (if (view.supportsSortFilter) RAIL_WIDTH else 0.dp)
        val cellWidth = (availableWidth / GRID_COLUMNS).coerceIn(CELL_WIDTH_MIN, CELL_WIDTH_MAX)
        // Request art at the cell's actual displayed width, not a flat 320 oversized for it.
        val density = LocalDensity.current
        val cellImageWidth = remember(cellWidth, density) {
            CardFormatting.bucketedImageWidth(with(density) { cellWidth.roundToPx() })
        }
        // docs/16 §4.4: the rail's top padding, so its labels stay level with the grid's first
        // row regardless of strip state; must live outside the strip-bearing Column.
        var headerHeight by remember { mutableStateOf(0.dp) }

        // docs/16 §4/§4.5: identical cell contents/behavior with or without sort/filter chrome,
        // so the Row-plus-rail and plain-grid layouts share one body.
        val posterGrid: @Composable () -> Unit = {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(GRID_COLUMNS),
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = PAGE_MARGIN, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(CELL_GAP),
                verticalArrangement = Arrangement.spacedBy(CELL_GAP),
            ) {
                itemsIndexed(
                    state.items,
                    key = { _, card -> card.id },
                    contentType = { _, _ -> CONTENT_TYPE_POSTER },
                ) { index, card ->
                    // docs/07 §4: derivedStateOf, keyed per-index, so a D-pad move only recomposes
                    // cells whose isFocused actually flips.
                    val isFocused by remember(index) { derivedStateOf { focusedIndex == index } }

                    var cellModifier = Modifier
                        .focusKey(memory, "card:${card.id}")
                        .onFocusChanged { focusState ->
                            if (focusState.isFocused) {
                                focusedIndex = index
                                // docs/18 preload: a grid cell dwells like a shelf card (docs/13);
                                // Rust no-ops for a non-playable item type.
                                PreloadOnDwell.default.onCardFocused(card.id, card.itemType)
                            } else {
                                if (focusedIndex == index) {
                                    focusedIndex = null
                                }
                                PreloadOnDwell.default.onCardUnfocused(card.id)
                            }
                        }
                    if (index == 0) {
                        cellModifier = cellModifier.focusRequester(initialFocusRequester)
                    }

                    PosterCard(
                        card = card,
                        isFocused = isFocused,
                        imageUrl = { itemId, kind, tag -> AppGraph.gateway.imageUrl(itemId, kind, tag, cellImageWidth) },
                        onClick = { onOpenDetail(card) },
                        modifier = cellModifier,
                        width = cellWidth,
                    )
                }
            }
        }

        val titleStyle = TextStyle(
            fontFamily = JellybeamTheme.Archivo,
            fontWeight = FontWeight.Bold,
            color = JellybeamTheme.Panna,
            fontSize = 28.sp,
        )

        if (view.supportsSortFilter) {
            // docs/16 §4.4: a top-level Row, not a Column -- [IndexRail] sits beside the
            // title/summary/strip/grid Column rather than below the strip, so opening/closing it
            // never touches the rail's height. [headerHeight] is the rail's top padding, keeping
            // its labels level with the grid's first row on every strip state.
            Row(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.weight(1f)) {
                    Column(modifier = Modifier.onSizeChanged { size -> headerHeight = with(density) { size.height.toDp() } }) {
                        BasicText(
                            text = view.name,
                            modifier = Modifier.padding(start = PAGE_MARGIN, end = PAGE_MARGIN, top = TITLE_TOP_PADDING_COMPACT),
                            style = titleStyle,
                        )
                        LibrarySummaryLine(
                            counts = state.counts,
                            sort = state.sort,
                            filters = state.filters,
                            isTv = view.isTvLibrary,
                            modifier = Modifier.padding(start = PAGE_MARGIN, end = PAGE_MARGIN, top = SUMMARY_TOP_PADDING, bottom = SUMMARY_BOTTOM_PADDING),
                        )
                    }

                    // docs/16 §4.2: both directions at 150ms.
                    AnimatedVisibility(
                        visible = stripOpen,
                        enter = expandVertically(animationSpec = tween(150)),
                        exit = shrinkVertically(animationSpec = tween(150)),
                    ) {
                        LibrarySortStrip(
                            view = view,
                            state = state,
                            viewModel = viewModel,
                            isTop = isTop,
                            focusGate = focusGate,
                            activeSortChipRequester = activeSortChipRequester,
                            panel = panel,
                            onPanelChange = { panel = it },
                            onCloseStrip = closeStrip,
                        )
                    }

                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        if (!state.isLoading && state.items.isEmpty() && state.filters.isAnyActive()) {
                            Box(modifier = Modifier.fillMaxWidth().fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                                BasicText(
                                    text = stringResource(R.string.library_no_titles_match),
                                    modifier = Modifier.padding(start = PAGE_MARGIN),
                                    style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Grigio, fontSize = 16.sp),
                                )
                            }
                        } else {
                            posterGrid()
                        }
                    }
                }
                IndexRail(
                    entries = railEntries,
                    railIndex = railIndex,
                    onRailIndexChange = { railIndex = it },
                    focusedIndex = focusedIndex,
                    gridState = gridState,
                    viewModel = viewModel,
                    memory = memory,
                    focusGate = focusGate,
                    railRequester = railRequester,
                    isTop = isTop,
                    originKey = railOriginKey,
                    columns = GRID_COLUMNS,
                    modifier = Modifier.fillMaxHeight().padding(top = headerHeight),
                )
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                BasicText(
                    text = view.name,
                    modifier = Modifier.padding(horizontal = PAGE_MARGIN, vertical = 24.dp),
                    style = titleStyle,
                )
                posterGrid()
            }
        }
    }
}

/** docs/16 §4.1: the always-present summary line under the title -- count segment `Panna2`,
 * every other segment `Panna`, `│` separators in `Grigio`; see [GridSummaryFormat.segments].
 */
@Composable
private fun LibrarySummaryLine(counts: GridCounts, sort: GridSort, filters: GridFilters, isTv: Boolean, modifier: Modifier = Modifier) {
    val segments = remember(counts, sort, filters, isTv) { GridSummaryFormat.segments(counts, sort, filters, isTv) }
    Row(modifier = modifier) {
        segments.forEachIndexed { index, segment ->
            if (index > 0) {
                BasicText(
                    text = "│",
                    modifier = Modifier.padding(horizontal = SUMMARY_SEPARATOR_HPADDING),
                    style = TextStyle(fontFamily = JellybeamTheme.MartianMono, color = JellybeamTheme.Grigio, fontSize = 13.sp),
                )
            }
            BasicText(
                text = segment,
                style = TextStyle(
                    fontFamily = JellybeamTheme.MartianMono,
                    color = if (index == 0) JellybeamTheme.Panna2 else JellybeamTheme.Panna,
                    fontSize = 13.sp,
                ),
            )
        }
    }
}
