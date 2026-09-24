package tv.jellybeam.ui.launch

import org.junit.Test
import org.junit.Assert.assertEquals

class LaunchStatusTextTest {
    @Test
    fun connecting_with_and_without_a_known_host() {
        assertEquals("CONNECTING · jellyfin.example.test:8096", launchStatusText("jellyfin.example.test:8096", unreachable = false))
        assertEquals("CONNECTING", launchStatusText(null, unreachable = false))
    }

    @Test
    fun unreachable_swaps_the_prefix_but_keeps_the_host_rule() {
        assertEquals("SERVER UNREACHABLE · jellyfin.example.test", launchStatusText("jellyfin.example.test", unreachable = true))
        assertEquals("SERVER UNREACHABLE", launchStatusText(null, unreachable = true))
    }
}
