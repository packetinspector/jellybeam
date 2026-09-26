package tv.jellybeam.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class CountLabelTest {
    @Test
    fun `one takes the singular noun`() {
        assertEquals("1 EPISODE", countLabel(1, "EPISODE", "EPISODES"))
    }

    @Test
    fun `zero and many take the plural noun`() {
        assertEquals("0 EPISODES", countLabel(0, "EPISODE", "EPISODES"))
        assertEquals("12 EPISODES", countLabel(12, "EPISODE", "EPISODES"))
    }
}
