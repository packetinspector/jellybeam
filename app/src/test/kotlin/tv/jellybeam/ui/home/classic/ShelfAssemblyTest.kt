package tv.jellybeam.ui.home.classic

import org.junit.Assert.assertEquals
import org.junit.Test

/** docs/07 §1: progressive shelf mounting's pure helpers, plain-JVM-testable per [ShelfAssembly.kt]. */
class ShelfAssemblyTest {

    @Test
    fun `initial mount count is two, or fewer if the list is shorter`() {
        assertEquals(0, initialMountCount(0))
        assertEquals(1, initialMountCount(1))
        assertEquals(2, initialMountCount(2))
        assertEquals(2, initialMountCount(6))
    }

    @Test
    fun `mounted count starts at the initial seed with no growth yet`() {
        assertEquals(2, mountedShelfCount(grown = 0, shelfCount = 6))
        assertEquals(1, mountedShelfCount(grown = 0, shelfCount = 1))
    }

    @Test
    fun `mounted count grows by one per call`() {
        assertEquals(3, mountedShelfCount(grown = 3, shelfCount = 6))
        assertEquals(4, mountedShelfCount(grown = 4, shelfCount = 6))
    }

    @Test
    fun `mounted count jumps to all shelves on demand`() {
        assertEquals(6, mountedShelfCount(grown = 6, shelfCount = 6))
    }

    @Test
    fun `mounted count never exceeds what the list currently holds`() {
        assertEquals(3, mountedShelfCount(grown = 6, shelfCount = 3))
    }

    @Test
    fun `mounted count resumes growth once a shrunk list grows back`() {
        // A shorter list clamps the visible count without discarding `grown`'s own progress.
        val grown = 5
        assertEquals(3, mountedShelfCount(grown = grown, shelfCount = 3))
        assertEquals(5, mountedShelfCount(grown = grown, shelfCount = 6))
    }
}
