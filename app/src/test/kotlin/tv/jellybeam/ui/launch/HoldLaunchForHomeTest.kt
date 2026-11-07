package tv.jellybeam.ui.launch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HoldLaunchForHomeTest {
    @Test
    fun a_cold_start_landing_on_home_holds() {
        assertTrue(holdLaunchForHome(coldStart = true, externalLaunch = false, startupLibrary = false))
    }

    @Test
    fun a_later_session_never_holds() {
        assertFalse(holdLaunchForHome(coldStart = false, externalLaunch = false, startupLibrary = false))
    }

    @Test
    fun an_external_launch_never_waits() {
        assertFalse(holdLaunchForHome(coldStart = true, externalLaunch = true, startupLibrary = false))
    }

    @Test
    fun a_startup_library_never_holds() {
        assertFalse(holdLaunchForHome(coldStart = true, externalLaunch = false, startupLibrary = true))
    }
}
