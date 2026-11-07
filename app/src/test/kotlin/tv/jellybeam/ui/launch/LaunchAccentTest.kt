package tv.jellybeam.ui.launch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import tv.jellybeam.accentSchemeFor

class LaunchAccentTest {
    @Test
    fun `the launch screen is always the default flavour`() {
        assertEquals("pistacchio", launchAccent().key)
        assertSame(accentSchemeFor(null), launchAccent())
    }
}
