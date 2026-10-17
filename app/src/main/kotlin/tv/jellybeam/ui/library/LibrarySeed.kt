package tv.jellybeam.ui.library

/** Where a Library grid page puts focus on entry: nothing yet, the first poster, or the sort/filter strip. */
internal enum class LibrarySeed { NONE, GRID, STRIP }

/**
 * docs/15 §2, docs/16 §6: the grid once it holds a poster; the strip when the viewer opened it, or
 * when a loaded grid is empty under active filters, since a grid with no cell holds no focus and
 * Reset lives on the strip. An unfiltered empty library has nothing to seed.
 */
internal fun librarySeed(isLoading: Boolean, count: Int, filtersActive: Boolean, userOpen: Boolean): LibrarySeed = when {
    userOpen -> LibrarySeed.STRIP
    isLoading -> LibrarySeed.NONE
    count > 0 -> LibrarySeed.GRID
    filtersActive -> LibrarySeed.STRIP
    else -> LibrarySeed.NONE
}
