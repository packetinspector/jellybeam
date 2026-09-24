package tv.jellybeam

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityFocusLifecycleTest {
    @Test
    fun `top retained layer is inactive while activity is covered`() {
        assertFalse(retainedLayerIsActive(index = 2, topIndex = 2, activityResumed = false))
    }

    @Test
    fun `only the top retained layer becomes active on resume`() {
        assertTrue(retainedLayerIsActive(index = 2, topIndex = 2, activityResumed = true))
        assertFalse(retainedLayerIsActive(index = 1, topIndex = 2, activityResumed = true))
    }
}
