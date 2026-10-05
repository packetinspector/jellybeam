package tv.jellybeam.ui.launch

import org.junit.Test
import org.junit.Assert.assertEquals
import tv.jellybeam.i18n.ResourceUiStrings

class LaunchStatusTextTest {
    private val strings = ResourceUiStrings.default

    @Test
    fun connecting_with_and_without_a_known_host() {
        assertEquals("CONNECTING · jellyfin.example.test:8096", launchStatusText(strings, "jellyfin.example.test:8096", unreachable = false))
        assertEquals("CONNECTING", launchStatusText(strings, null, unreachable = false))
    }

    @Test
    fun unreachable_swaps_the_prefix_but_keeps_the_host_rule() {
        assertEquals("SERVER UNREACHABLE · jellyfin.example.test", launchStatusText(strings, "jellyfin.example.test", unreachable = true))
        assertEquals("SERVER UNREACHABLE", launchStatusText(strings, null, unreachable = true))
    }
}
