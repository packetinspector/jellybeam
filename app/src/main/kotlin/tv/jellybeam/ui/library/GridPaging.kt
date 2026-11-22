package tv.jellybeam.ui.library

import androidx.compose.ui.input.key.Key

/** The poster grid's column count, shared by [LibraryScreen] and [LibraryViewModel]'s read sizes. */
internal const val GRID_COLUMNS = 8

/** docs/16 §4.6: rows the first read covers, about three screens of the grid's ~2.5 visible rows. */
private const val FIRST_PAGE_ROWS = 8

/** Pure Page Up/Down (Channel Up/Down on the remote) decision and read sizes for [LibraryScreen]'s
 * poster grid, kept apart from Compose state so the paging math is plain-JVM-testable.
 */
object GridPaging {

    /** docs/16 §4.6: every read after the first stays large, so a long scroll costs few FFI calls. */
    const val PAGE_SIZE = 200

    /** docs/16 §4.6: the first read is a few screens of rows, so first paint stays cheap. */
    fun firstPageSize(columns: Int): Int = columns * FIRST_PAGE_ROWS

    /** docs/16 §4.6: a refresh re-reads the whole loaded prefix, never less than a first page. */
    fun refreshSize(loaded: Int, columns: Int): Int = maxOf(firstPageSize(columns), loaded)

    /** docs/16 §4.6: a rail jump to [offset] reads from [loaded] through one page past [offset]. */
    fun tailSize(loaded: Int, offset: Int): Int = maxOf(offset + PAGE_SIZE - loaded, PAGE_SIZE)

    /** Keys that page the grid toward the top: Page Up and the remote's Channel Up rocker. */
    val PAGE_UP_KEYS: Set<Key> = setOf(Key.PageUp, Key.ChannelUp)

    /** Keys that page the grid toward the bottom: Page Down and the remote's Channel Down rocker.
     */
    val PAGE_DOWN_KEYS: Set<Key> = setOf(Key.PageDown, Key.ChannelDown)

    /** Whether [key] pages the grid at all. */
    fun isPagingKey(key: Key): Boolean = key in PAGE_UP_KEYS || key in PAGE_DOWN_KEYS

    /** `true` when [key] pages toward the bottom (the [targetIndex] `forward` sense); only
     * meaningful for an [isPagingKey] key.
     */
    fun isForward(key: Key): Boolean = key in PAGE_DOWN_KEYS

    /** One visible grid cell's vertical extent, as read from `LazyGridItemInfo`. */
    data class VisibleCell(val index: Int, val top: Int, val height: Int)

    /** How many grid rows are fully inside `[viewportStart, viewportEnd]` -- a row half in view
     * doesn't count. Always at least 1.
     */
    fun fullyVisibleRows(items: List<VisibleCell>, columns: Int, viewportStart: Int, viewportEnd: Int): Int {
        if (columns <= 0) return 1
        val rows = items.asSequence()
            .filter { it.top >= viewportStart && it.top + it.height <= viewportEnd }
            .map { it.index / columns }
            .toSet()
        return maxOf(rows.size, 1)
    }

    /**
     * The cell [rows] screenfuls away from [current] in the same column, clamped to the grid's
     * bounds.
     * `null` means nothing to do: an empty grid, degenerate [columns]/[rows], or a target that
     * lands back
     * on [current]. Forward (Page Down) clamps to the last row; backward (Page Up) clamps to row 0.
     */
    fun targetIndex(current: Int, itemCount: Int, columns: Int, rows: Int, forward: Boolean): Int? {
        if (itemCount <= 0 || columns <= 0 || rows <= 0) return null
        val col = current % columns
        val currentRow = current / columns
        val lastRow = (itemCount - 1) / columns
        val targetRow = if (forward) minOf(currentRow + rows, lastRow) else maxOf(currentRow - rows, 0)
        val target = minOf(targetRow * columns + col, itemCount - 1)
        return if (target == current) null else target
    }

    /** The first index in [index]'s row -- where a page lands the viewport. */
    fun rowStart(index: Int, columns: Int): Int = index - index % columns
}
