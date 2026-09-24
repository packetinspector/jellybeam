package tv.jellybeam.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SheetScrollTargetTest {
    @Test
    fun `a sheet whose content fits cannot manufacture a scroll action`() {
        assertNull(sheetScrollTarget(current = 0, max = 0, delta = 120))
        assertNull(sheetScrollTarget(current = 0, max = 0, delta = -120))
    }

    @Test
    fun `a press at either scroll boundary is a no-op`() {
        assertNull(sheetScrollTarget(current = 0, max = 400, delta = -120))
        assertNull(sheetScrollTarget(current = 400, max = 400, delta = 120))
    }

    @Test
    fun `scroll targets clamp without overshooting`() {
        assertEquals(400, sheetScrollTarget(current = 350, max = 400, delta = 120))
        assertEquals(0, sheetScrollTarget(current = 50, max = 400, delta = -120))
    }
}
