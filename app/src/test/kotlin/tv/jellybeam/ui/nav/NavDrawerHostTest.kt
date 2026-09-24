package tv.jellybeam.ui.nav

import tv.jellybeam.nav.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.jellybeam_core.AccountInfo
import uniffi.jellybeam_core.ViewKind
import uniffi.jellybeam_core.ViewSnapshot

/**
 * Pure-logic coverage for the three helpers [NavDrawerHost] extracted from its
 * @Composable body for JVM testability. Drawer animation, D-pad focus and row
 * clicks need a real device; this module has no Compose UI test harness.
 */
class NavDrawerHostTest {

    // ---- accountDrawerLabel (CLAUDE.md: server-configured names/URLs are shown verbatim) ----

    @Test
    fun `account label joins user name and server url verbatim`() {
        val account = AccountInfo(serverUrl = "https://media.example.test", userId = "user-1", userName = "Example User")
        assertEquals("Example User — https://media.example.test", accountDrawerLabel(account))
    }

    @Test
    fun `account label does not trim or reformat unusual values`() {
        // Locks accountDrawerLabel against adding trim/normalize behavior.
        val account = AccountInfo(serverUrl = "https://mixed-case.example.test:8920/ ", userId = "user-2", userName = " Ünïcode User ")
        assertEquals(" Ünïcode User  — https://mixed-case.example.test:8920/ ", accountDrawerLabel(account))
    }

    // ---- shouldSwitchServer ------------------------------------------------

    @Test
    fun `should switch server is false for the already active row`() {
        assertFalse(shouldSwitchServer(index = 1, activeAccountIndex = 1u))
    }

    @Test
    fun `should switch server is true for a non active row`() {
        assertTrue(shouldSwitchServer(index = 0, activeAccountIndex = 1u))
    }

    @Test
    fun `should switch server is true when nothing is known active yet`() {
        assertTrue(shouldSwitchServer(index = 0, activeAccountIndex = null))
    }

    // ---- matchesCurrentScreen -----------------------------------------------

    @Test
    fun `matches current screen is true for identical singleton screens`() {
        assertTrue(matchesCurrentScreen(Screen.Home, Screen.Home))
    }

    @Test
    fun `matches current screen is false for different singleton screens`() {
        assertFalse(matchesCurrentScreen(Screen.Home, Screen.Search))
    }

    @Test
    fun `matches current screen compares libraries by id not full snapshot`() {
        val moviesV1 = ViewSnapshot(id = "movies", name = "Movies", kind = ViewKind.LIBRARY)
        val moviesV2 = ViewSnapshot(id = "movies", name = "Movies (renamed on server)", kind = ViewKind.LIBRARY)
        val shows = ViewSnapshot(id = "shows", name = "Shows", kind = ViewKind.LIBRARY)

        assertTrue(matchesCurrentScreen(Screen.Library(moviesV1), Screen.Library(moviesV2)))
        assertFalse(matchesCurrentScreen(Screen.Library(moviesV1), Screen.Library(shows)))
        assertFalse(matchesCurrentScreen(Screen.Library(moviesV1), Screen.Home))
    }

    @Test
    fun `matches current screen is true only when Discover home itself is current`() {
        // docs/14-seerr-discover.md: the Discover row's current-dot shows only on the Discover home
        // screen, not its sub-screens.
        assertTrue(matchesCurrentScreen(Screen.Discover, Screen.Discover))
        assertFalse(matchesCurrentScreen(Screen.Discover, Screen.DiscoverRequests))
        assertFalse(matchesCurrentScreen(Screen.Discover, Screen.Home))
    }
}
