package tv.jellybeam

import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertEquals
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

class OnActivityStartTest {
    @Test
    fun `one ON_START with an account runs the check and the foreground refresh once each`() {
        var checks = 0
        var foregrounds = 0
        onActivityStart(Lifecycle.Event.ON_START, true, { checks++ }, { foregrounds++ })
        assertEquals(1, checks)
        assertEquals(1, foregrounds)
    }

    @Test
    fun `other events and a signed-out start run neither`() {
        var calls = 0
        for (event in Lifecycle.Event.values().filter { it != Lifecycle.Event.ON_START }) {
            onActivityStart(event, true, { calls++ }, { calls++ })
        }
        onActivityStart(Lifecycle.Event.ON_START, false, { calls++ }, { calls++ })
        assertEquals(0, calls)
    }
}
