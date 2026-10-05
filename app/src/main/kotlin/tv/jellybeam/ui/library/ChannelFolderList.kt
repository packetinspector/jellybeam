package tv.jellybeam.ui.library

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import tv.jellybeam.JellybeamTheme
import tv.jellybeam.R
import tv.jellybeam.nav.CHANNEL_FOLDER_ITEM_TYPE
import tv.jellybeam.ui.cards.focusRing
import tv.jellybeam.ui.focus.FocusRestorer
import tv.jellybeam.ui.focus.asFocusTarget
import tv.jellybeam.ui.focus.focusKey
import tv.jellybeam.ui.focus.rememberFocusMemory
import uniffi.jellybeam_core.Card
import uniffi.jellybeam_core.ViewSnapshot

private val ROW_HEIGHT = 64.dp
private val ROW_GAP = 4.dp
private val ROW_CORNER = 10.dp
private val LEADING_WIDTH = 132.dp
private val TRAILING_WIDTH = 48.dp
private val ROW_HPADDING = 16.dp

/** Load-more threshold in rows; row-granular analog of the grid's `GRID_COLUMNS * 3` window. */
private const val LOAD_MORE_THRESHOLD_ROWS = 12

private const val CONTENT_TYPE_FOLDER = "folder"
private const val CONTENT_TYPE_RECORDING = "recording"

/**
 * Full-width row list for a live channel/folder view, replacing [LibraryScreen]'s poster grid
 * since folder/recording items have no art or runtime. Reuses [LibraryViewModel]/[LibraryUiState],
 * rendering [state] as rows over a [LazyColumn] with the grid's own focus-restore contract
 * (docs/15-focus-and-selection.md §1.2).
 */
@Composable
fun ChannelFolderList(
    view: ViewSnapshot,
    state: LibraryUiState,
    viewModel: LibraryViewModel,
    onOpenDetail: (Card) -> Unit,
    isTop: Boolean,
    focusGate: MutableState<Boolean>,
) {
    var focusedIndex by remember { mutableStateOf<Int?>(null) }
    val initialFocusRequester = remember { FocusRequester() }
    val listState = remember { LazyListState() }

    // Computed once at the list level, not per row.
    val zone = remember { ZoneId.systemDefault() }
    val today = remember(zone) { LocalDate.now(zone) }
    val context = LocalContext.current
    val use24h = remember(context) { DateFormat.is24HourFormat(context) }

    // Mirrors LibraryScreen's load-more shape against a LazyListState instead of LazyGridState.
    LaunchedEffect(listState, state.items.size, state.hasMore) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .filter { it >= state.items.size - LOAD_MORE_THRESHOLD_ROWS }
            .collect { viewModel.loadNextPage() }
    }

    // Stands in for the ChangeEvent stream this live view lacks (see onBecameTop).
    LaunchedEffect(isTop) {
        if (isTop) viewModel.onBecameTop()
    }

    val memory = rememberFocusMemory(view.id)

    memory.scrollTo = scrollTo@{ key ->
        val id = key.removePrefix("card:")
        if (id == key) return@scrollTo false
        val index = state.items.indexOfFirst { it.id == id }
        if (index >= 0) listState.scrollToItem(index)
        index >= 0
    }

    FocusRestorer(
        memory = memory,
        isTop = isTop,
        focusGate = focusGate,
        ready = state.items.isNotEmpty(),
        fallback = {
            if (memory.seeded) {
                val items = state.items
                val index = listState.firstVisibleItemIndex.coerceIn(0, items.lastIndex)
                memory.target("card:${items[index].id}") ?: initialFocusRequester.asFocusTarget()
            } else {
                initialFocusRequester.asFocusTarget()
            }
        },
        tag = "channel-list",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(JellybeamTheme.Notte),
    ) {
        BasicText(
            text = view.name,
            modifier = Modifier.padding(horizontal = PAGE_MARGIN, vertical = 24.dp),
            style = TextStyle(
                fontFamily = JellybeamTheme.Archivo,
                fontWeight = FontWeight.Bold,
                color = JellybeamTheme.Panna,
                fontSize = 28.sp,
            ),
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = PAGE_MARGIN, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(ROW_GAP),
        ) {
            itemsIndexed(
                state.items,
                key = { _, card -> card.id },
                contentType = { _, card -> if (card.itemType == CHANNEL_FOLDER_ITEM_TYPE) CONTENT_TYPE_FOLDER else CONTENT_TYPE_RECORDING },
            ) { index, card ->
                val isFocused by remember(index) { derivedStateOf { focusedIndex == index } }

                var rowModifier = Modifier
                    .fillMaxWidth()
                    .height(ROW_HEIGHT)
                    .background(if (isFocused) JellybeamTheme.Surface else Color.Transparent, RoundedCornerShape(ROW_CORNER))
                    .focusRing(isFocused, cornerRadius = ROW_CORNER)
                    .focusKey(memory, "card:${card.id}")
                    .onFocusChanged { focusState ->
                        if (focusState.isFocused) {
                            focusedIndex = index
                        } else if (focusedIndex == index) {
                            focusedIndex = null
                        }
                    }
                if (index == 0) {
                    rowModifier = rowModifier.focusRequester(initialFocusRequester)
                }
                rowModifier = rowModifier
                    .clickable { onOpenDetail(card) }
                    .padding(horizontal = ROW_HPADDING)

                ChannelFolderRow(
                    card = card,
                    zone = zone,
                    today = today,
                    use24h = use24h,
                    modifier = rowModifier,
                )
            }
        }
    }
}

/**
 * One row: leading glyph or recording date/time ([RecordingFormatting]), name (+ overview for
 * recordings), and a played/resume/disclosure glyph; [modifier] carries height/background/focus.
 */
@Composable
private fun ChannelFolderRow(
    card: Card,
    zone: ZoneId,
    today: LocalDate,
    use24h: Boolean,
    modifier: Modifier = Modifier,
) {
    val isFolder = card.itemType == CHANNEL_FOLDER_ITEM_TYPE

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(LEADING_WIDTH)) {
            if (isFolder) {
                BasicText(
                    text = stringResource(R.string.library_folder_tag),
                    style = TextStyle(
                        fontFamily = JellybeamTheme.MartianMono,
                        color = JellybeamTheme.Grigio,
                        fontSize = 13.sp,
                    ),
                )
            } else {
                val dateLine = remember(card.id, card.premiereDate, zone, today) {
                    RecordingFormatting.dateLine(card.premiereDate, zone, today)
                }
                val timeLine = remember(card.id, card.premiereDate, zone, use24h) {
                    RecordingFormatting.timeLine(card.premiereDate, zone, use24h)
                }
                Column {
                    dateLine?.let {
                        BasicText(
                            text = it,
                            style = TextStyle(fontFamily = JellybeamTheme.MartianMono, color = JellybeamTheme.Panna, fontSize = 15.sp),
                        )
                    }
                    timeLine?.let {
                        BasicText(
                            text = it,
                            style = TextStyle(fontFamily = JellybeamTheme.MartianMono, color = JellybeamTheme.Panna2, fontSize = 13.sp),
                        )
                    }
                }
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = card.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    fontFamily = JellybeamTheme.Archivo,
                    fontWeight = FontWeight.SemiBold,
                    color = JellybeamTheme.Panna,
                    fontSize = 20.sp,
                ),
            )
            if (!isFolder) {
                card.overview?.takeIf { it.isNotBlank() }?.let { overview ->
                    BasicText(
                        text = overview,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Grigio, fontSize = 14.sp),
                    )
                }
            }
        }

        Box(modifier = Modifier.width(TRAILING_WIDTH), contentAlignment = Alignment.Center) {
            when {
                isFolder -> BasicText(
                    text = "›",
                    style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Grigio, fontSize = 22.sp),
                )
                card.played -> BasicText(
                    text = "✓",
                    style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Pistacchio, fontSize = 18.sp),
                )
                card.positionTicks > 0 -> BasicText(
                    text = stringResource(R.string.library_resume_tag),
                    style = TextStyle(fontFamily = JellybeamTheme.MartianMono, color = JellybeamTheme.Pistacchio, fontSize = 11.sp),
                )
            }
        }
    }
}
