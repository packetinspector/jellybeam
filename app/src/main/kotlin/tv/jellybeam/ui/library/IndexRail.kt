package tv.jellybeam.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import tv.jellybeam.JellybeamTheme
import tv.jellybeam.ui.nav.LocalDrawerLeftEdgeSuppressed
import tv.jellybeam.ui.focus.FocusMemory
import tv.jellybeam.ui.focus.placeByKeys
import tv.jellybeam.ui.focus.requestFocusWithRetry

/** docs/16-library-sort-filter.md §4.4: rail column width, wide enough that `1h30` never clips. */
internal val RAIL_WIDTH = 64.dp

/** Focus pill size, drawn inside the entry (`.border`) rather than the outset
 * [tv.jellybeam.ui.cards.focusRing] so it can't overlap neighbors.
 */
private val RAIL_RING_WIDTH = 48.dp
private val RAIL_RING_HEIGHT = 22.dp

/** Same Enter/D-pad-center pair every Select-style key check in this app uses. */
private val RAIL_SELECT_KEYS = setOf(Key.DirectionCenter, Key.Enter)

/** Next entry index in [entries] with a non-zero count, walking from [current]; `null` at the
 * list end (§4.4 "stop at ends").
 */
private fun nextNonEmptyIndex(entries: List<RailEntry>, current: Int, forward: Boolean): Int? {
    val step = if (forward) 1 else -1
    var i = current + step
    while (i in entries.indices) {
        if (entries[i].count > 0) return i
        i += step
    }
    return null
}

/**
 * docs/16-library-sort-filter.md §4.4's index rail: a focusable column at the grid's right edge
 * that jumps the grid to a sort-bucket boundary. [entries] comes from [IndexRailModel.build].
 * D-pad Up/Down walk [railIndex] across non-empty entries; Left/Select move focus into the grid
 * at the entry's first item; Back restores the poster the rail was entered from ([originKey]).
 */
@Composable
internal fun IndexRail(
    entries: List<RailEntry>,
    railIndex: Int,
    onRailIndexChange: (Int) -> Unit,
    /** The grid's focused cell, or `null` when focus is elsewhere; the unfocused rail's bright
     * entry follows it, falling back to the first visible row with no focused cell.
     */
    focusedIndex: Int?,
    gridState: LazyGridState,
    viewModel: LibraryViewModel,
    memory: FocusMemory,
    focusGate: MutableState<Boolean>,
    railRequester: FocusRequester,
    isTop: Boolean,
    originKey: String?,
    columns: Int,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var railFocused by remember { mutableStateOf(false) }
    // docs/16 §4.4: a repeat retargets the in-flight load+scroll job; it loops until its last read
    // matches the newest target, and only that target scrolls the grid.
    var jumpJob by remember { mutableStateOf<Job?>(null) }
    var jumpOffset by remember { mutableIntStateOf(0) }

    // §4.4: the entry containing the first visible grid item is Panna when unfocused.
    val current by remember(entries, focusedIndex) {
        derivedStateOf { IndexRailModel.entryIndexForOffset(entries, focusedIndex ?: gridState.firstVisibleItemIndex) }
    }

    // docs/16 §4.4: Left from the rail focuses the entry's first poster, so the drawer's
    // "move focus Left" gesture stands down while the rail holds focus.
    val drawerLeftEdgeSuppressed = LocalDrawerLeftEdgeSuppressed.current
    DisposableEffect(drawerLeftEdgeSuppressed, railFocused) {
        drawerLeftEdgeSuppressed?.value = railFocused
        onDispose { drawerLeftEdgeSuppressed?.value = false }
    }

    BackHandler(enabled = isTop && railFocused) {
        val key = originKey
        if (key != null) {
            scope.launch { memory.placeByKeys(listOf(key), focusGate) }
        }
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(RAIL_WIDTH)
            .focusRequester(railRequester)
            .onFocusChanged { railFocused = it.hasFocus }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionUp, Key.DirectionDown -> {
                        val next = nextNonEmptyIndex(entries, railIndex, forward = event.key == Key.DirectionDown)
                        if (next != null) {
                            onRailIndexChange(next)
                            val entry = entries[next]
                            jumpOffset = entry.offset
                            if (jumpJob?.isActive != true) {
                                jumpJob = scope.launch {
                                    var done = -1
                                    while (done != jumpOffset) {
                                        val target = jumpOffset
                                        viewModel.ensureLoadedThrough(target)
                                        if (target == jumpOffset) {
                                            gridState.scrollToItem(GridPaging.rowStart(target, columns))
                                        }
                                        done = target
                                    }
                                }
                            }
                        }
                        true
                    }
                    // A jump still in flight would scroll to its own target after focus lands.
                    Key.DirectionLeft -> {
                        jumpJob?.cancel()
                        focusGridEntry(entries.getOrNull(railIndex), scope, viewModel, gridState, memory, focusGate, columns)
                        true
                    }
                    in RAIL_SELECT_KEYS -> {
                        jumpJob?.cancel()
                        focusGridEntry(entries.getOrNull(railIndex), scope, viewModel, gridState, memory, focusGate, columns)
                        true
                    }
                    Key.DirectionRight -> true
                    else -> false
                }
            }
            .focusable(),
    ) {
        Column(
            modifier = Modifier.fillMaxHeight(),
            verticalArrangement = Arrangement.SpaceEvenly,
        ) {
            entries.forEachIndexed { index, entry ->
                val focusedHere = railFocused && index == railIndex
                // docs/16 §4.4: an empty entry reads as disabled (HairlineStrong).
                val color = when {
                    entry.count == 0 -> JellybeamTheme.HairlineStrong
                    focusedHere -> JellybeamTheme.Pistacchio
                    !railFocused && index == current -> JellybeamTheme.Panna
                    else -> JellybeamTheme.Grigio
                }
                // weight(1f), not a fixed height: the Name rail has 27 entries sharing it equally.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    // Ring drawn inside the entry (`.border`), not the outset `focusRing`, so it
                    // can't overlap neighbors on this 64dp-wide stacked column.
                    Box(
                        modifier = Modifier
                            .width(RAIL_RING_WIDTH)
                            .heightIn(max = RAIL_RING_HEIGHT)
                            .fillMaxHeight()
                            .let { if (focusedHere) it.border(2.dp, JellybeamTheme.Pistacchio, RoundedCornerShape(50)) else it },
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText(
                            text = entry.label,
                            maxLines = 1,
                            softWrap = false,
                            style = TextStyle(fontFamily = JellybeamTheme.MartianMono, color = color, fontSize = 12.sp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Left/Select's shared body: load+scroll to [entry], then focus its first poster (docs/16 §4.4).
 * Falls back to the last loaded card when [LibraryViewModel.ensureLoadedThrough] doesn't cover
 * [entry]'s offset, so focus always lands in the grid rather than stranding on the rail.
 */
private fun focusGridEntry(
    entry: RailEntry?,
    scope: CoroutineScope,
    viewModel: LibraryViewModel,
    gridState: LazyGridState,
    memory: FocusMemory,
    focusGate: MutableState<Boolean>,
    columns: Int,
) {
    if (entry == null) return
    scope.launch {
        val loaded = viewModel.ensureLoadedThrough(entry.offset)
        gridState.scrollToItem(GridPaging.rowStart(entry.offset, columns))
        val items = viewModel.state.value.items
        val card = (if (loaded) items.getOrNull(entry.offset) else null) ?: items.lastOrNull()
        if (card != null) {
            requestFocusWithRetry(focusGate) { memory.target("card:${card.id}")?.requestFocus() ?: false }
        }
    }
}
