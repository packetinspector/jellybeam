package tv.jellybeam.ui.home.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import tv.jellybeam.data.LaunchWarmup
import tv.jellybeam.data.defaultTestSettings
import tv.jellybeam.ui.home.classicSnapshot
import uniffi.jellybeam_core.ClassicHome
import uniffi.jellybeam_core.HomeSnapshot
import uniffi.jellybeam_core.ResumeArt
import uniffi.jellybeam_core.Settings
import uniffi.jellybeam_core.ViewKind
import uniffi.jellybeam_core.ViewSnapshot

class SeedFromWarmupTest {
    private val views = listOf(ViewSnapshot(id = "view-1", name = "Movies", kind = ViewKind.LIBRARY))
    private val snapshot = classicSnapshot()

    private fun warm(
        settings: Settings? = defaultTestSettings(),
        views: List<ViewSnapshot>? = this.views,
    ) = LaunchWarmup.Home(snapshot = snapshot, stale = false, settings = settings, views = views)

    private fun seed(
        warm: LaunchWarmup.Home?,
        extract: (HomeSnapshot) -> ClassicHome? = { (it as? HomeSnapshot.Classic)?.home },
    ): HomeFeedState<String>? = seedFromWarmup(
        warm = warm,
        initialContent = "initial",
        loadingStatusText = "Loading",
        extract = extract,
        reduce = { current, incoming -> "$current+${incoming.shelves.size}" },
    )

    @Test
    fun `no warm-up seeds nothing`() {
        assertNull(seed(null))
    }

    @Test
    fun `a warm-up without settings seeds nothing`() {
        assertNull(seed(warm(settings = null)))
    }

    @Test
    fun `a warm-up without views seeds nothing`() {
        assertNull(seed(warm(views = null)))
    }

    @Test
    fun `a snapshot another layout owns seeds nothing`() {
        assertNull(seed(warm(), extract = { null }))
    }

    @Test
    fun `a complete warm-up seeds a loaded state from the launch reads`() {
        val settings = defaultTestSettings().copy(showClock = false, homeResumeArt = ResumeArt.SERIES_THUMB)

        val seeded = seed(warm(settings = settings))

        assertNotNull(seeded)
        val chrome = seeded!!.chrome
        assertFalse(chrome.isLoading)
        assertSame(views, chrome.views)
        assertFalse(chrome.showClock)
        assertEquals(ResumeArt.SERIES_THUMB, chrome.resumeArt)
        assertEquals("Loading", chrome.loadingStatusText)
    }

    @Test
    fun `the seeded content is the reduce of the initial content and the extracted record`() {
        val seeded = seed(warm())

        assertEquals("initial+0", seeded?.content)
    }
}
