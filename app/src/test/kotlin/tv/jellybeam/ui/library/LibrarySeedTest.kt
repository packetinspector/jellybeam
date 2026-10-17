package tv.jellybeam.ui.library

import org.junit.Assert.assertEquals
import org.junit.Test

/** docs/16 §6, docs/15 §2: where a Library page seeds focus, over every input combination. */
class LibrarySeedTest {

    @Test
    fun `the strip opens itself on a loaded, empty, filtered grid so Reset is reachable`() {
        assertEquals(LibrarySeed.STRIP, librarySeed(isLoading = false, count = 0, filtersActive = true, userOpen = false))
    }

    @Test
    fun `a loaded grid with posters seeds the grid, filtered or not`() {
        assertEquals(LibrarySeed.GRID, librarySeed(isLoading = false, count = 5, filtersActive = false, userOpen = false))
        assertEquals(LibrarySeed.GRID, librarySeed(isLoading = false, count = 5, filtersActive = true, userOpen = false))
    }

    @Test
    fun `an empty unfiltered library has nothing to seed`() {
        assertEquals(LibrarySeed.NONE, librarySeed(isLoading = false, count = 0, filtersActive = false, userOpen = false))
    }

    @Test
    fun `while loading nothing seeds, however the filters read`() {
        for (count in listOf(0, 5)) {
            for (filtersActive in listOf(false, true)) {
                assertEquals(
                    "count=$count filtersActive=$filtersActive",
                    LibrarySeed.NONE,
                    librarySeed(isLoading = true, count = count, filtersActive = filtersActive, userOpen = false),
                )
            }
        }
    }

    @Test
    fun `a strip the viewer opened stays the seed`() {
        for (isLoading in listOf(false, true)) {
            for (count in listOf(0, 5)) {
                for (filtersActive in listOf(false, true)) {
                    assertEquals(
                        "isLoading=$isLoading count=$count filtersActive=$filtersActive",
                        LibrarySeed.STRIP,
                        librarySeed(isLoading, count, filtersActive, userOpen = true),
                    )
                }
            }
        }
    }

    @Test
    fun `once loaded with no posters and filters active the seed is never NONE`() {
        for (userOpen in listOf(false, true)) {
            assertEquals(LibrarySeed.STRIP, librarySeed(isLoading = false, count = 0, filtersActive = true, userOpen = userOpen))
        }
    }
}
