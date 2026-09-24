package tv.jellybeam.ui.library

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import tv.jellybeam.ui.library.GridPaging.VisibleCell

class GridPagingTest {

    private val columns = 8

    // ---- targetIndex ----

    @Test
    fun `page down moves by rows times columns, same column`() {
        val target = GridPaging.targetIndex(current = 11, itemCount = 200, columns = columns, rows = 5, forward = true)
        assertEquals(6 * columns + 3, target)
    }

    @Test
    fun `page up moves by rows times columns, same column`() {
        val target = GridPaging.targetIndex(current = 83, itemCount = 200, columns = columns, rows = 5, forward = false)
        assertEquals(5 * columns + 3, target)
    }

    @Test
    fun `page down clamps to the last row, same column, when that row is full`() {
        val itemCount = columns * 3
        val target = GridPaging.targetIndex(current = 2, itemCount = itemCount, columns = columns, rows = 10, forward = true)
        val lastRow = (itemCount - 1) / columns
        assertEquals(lastRow * columns + 2, target)
    }

    @Test
    fun `page down clamps to lastIndex when the last row is partial`() {
        val itemCount = 19
        val target = GridPaging.targetIndex(current = 5, itemCount = itemCount, columns = columns, rows = 10, forward = true)
        assertEquals(itemCount - 1, target)
    }

    @Test
    fun `page up clamps to row 0, same column`() {
        val target = GridPaging.targetIndex(current = 20, itemCount = 200, columns = columns, rows = 10, forward = false)
        assertEquals(4, target)
    }

    @Test
    fun `same target as current index yields null`() {
        val target = GridPaging.targetIndex(current = 3, itemCount = 200, columns = columns, rows = 5, forward = false)
        assertNull(target)
    }

    @Test
    fun `empty grid yields null`() {
        assertNull(GridPaging.targetIndex(current = 0, itemCount = 0, columns = columns, rows = 5, forward = true))
    }

    @Test
    fun `degenerate columns or rows yields null`() {
        assertNull(GridPaging.targetIndex(current = 0, itemCount = 200, columns = 0, rows = 5, forward = true))
        assertNull(GridPaging.targetIndex(current = 0, itemCount = 200, columns = columns, rows = 0, forward = true))
    }

    // ---- fullyVisibleRows ----

    @Test
    fun `fullyVisibleRows counts only rows entirely inside the viewport`() {
        val cells = listOf(
            VisibleCell(index = 0, top = 0, height = 100),
            VisibleCell(index = 1, top = 0, height = 100),
            VisibleCell(index = 8, top = 100, height = 100),
            VisibleCell(index = 9, top = 100, height = 100),
            VisibleCell(index = 16, top = 200, height = 100),
        )
        val rows = GridPaging.fullyVisibleRows(cells, columns = columns, viewportStart = 0, viewportEnd = 250)
        assertEquals(2, rows)
    }

    @Test
    fun `fullyVisibleRows excludes a row clipped at the top edge`() {
        val cells = listOf(
            VisibleCell(index = 0, top = -20, height = 100),
            VisibleCell(index = 8, top = 80, height = 100),
        )
        val rows = GridPaging.fullyVisibleRows(cells, columns = columns, viewportStart = 0, viewportEnd = 300)
        assertEquals(1, rows)
    }

    @Test
    fun `fullyVisibleRows is at least 1 even when nothing fits`() {
        val cells = listOf(VisibleCell(index = 0, top = -50, height = 400))
        val rows = GridPaging.fullyVisibleRows(cells, columns = columns, viewportStart = 0, viewportEnd = 300)
        assertEquals(1, rows)
    }

    @Test
    fun `fullyVisibleRows on an empty visible list is 1`() {
        assertEquals(1, GridPaging.fullyVisibleRows(emptyList(), columns = columns, viewportStart = 0, viewportEnd = 300))
    }

    // ---- key aliases ----

    @Test
    fun `PageUp and ChannelUp page toward the top, PageDown and ChannelDown toward the bottom`() {
        for (key in listOf(Key.PageUp, Key.ChannelUp)) {
            assertTrue(GridPaging.isPagingKey(key))
            assertFalse(GridPaging.isForward(key))
        }
        for (key in listOf(Key.PageDown, Key.ChannelDown)) {
            assertTrue(GridPaging.isPagingKey(key))
            assertTrue(GridPaging.isForward(key))
        }
        for (key in listOf(Key.DirectionUp, Key.DirectionDown, Key.Back, Key.MediaNext)) assertFalse(GridPaging.isPagingKey(key))
    }

    // ---- rowStart ----

    @Test
    fun `rowStart floors to the row's first column`() {
        assertEquals(16, GridPaging.rowStart(19, columns))
        assertEquals(0, GridPaging.rowStart(5, columns))
        assertEquals(24, GridPaging.rowStart(24, columns))
    }
}
