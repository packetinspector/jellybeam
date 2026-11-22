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

/** docs/02 Foreground / background: presence follows every start and stop; the session check needs an account. */
class OnActivityStartTest {
    private val sent = mutableListOf<String>()

    private fun deliver(event: Lifecycle.Event, hasAccounts: Boolean = true) =
        onActivityStart(event, hasAccounts, { sent += "check" }, { sent += "foreground" }, { sent += "background" })

    @Test
    fun `one ON_START with an account runs the check and the foreground refresh once each`() {
        deliver(Lifecycle.Event.ON_START)
        assertEquals(listOf("check", "foreground"), sent)
    }

    @Test
    fun `ON_STOP sends the background notice once and nothing else`() {
        deliver(Lifecycle.Event.ON_STOP)
        assertEquals(listOf("background"), sent)
    }

    @Test
    fun `ON_START after ON_STOP sends the foreground notice again`() {
        deliver(Lifecycle.Event.ON_START)
        deliver(Lifecycle.Event.ON_STOP)
        deliver(Lifecycle.Event.ON_START)
        assertEquals(listOf("check", "foreground", "background", "check", "foreground"), sent)
    }

    @Test
    fun `a signed-out stop and start still send both notices but skip the session check`() {
        deliver(Lifecycle.Event.ON_STOP, hasAccounts = false)
        deliver(Lifecycle.Event.ON_START, hasAccounts = false)
        assertEquals(listOf("background", "foreground"), sent)
    }

    @Test
    fun `other events send nothing with or without an account`() {
        val others = Lifecycle.Event.values().filter { it != Lifecycle.Event.ON_START && it != Lifecycle.Event.ON_STOP }
        for (event in others) {
            deliver(event, hasAccounts = true)
            deliver(event, hasAccounts = false)
        }
        assertEquals(emptyList<String>(), sent)
    }
}
