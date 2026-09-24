package tv.jellybeam.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.jellybeam_core.ViewKind
import uniffi.jellybeam_core.ViewSnapshot

class StartupScreenResolverTest {

    private val movies = ViewSnapshot(id = "view-movies", name = "Movies", kind = ViewKind.LIBRARY)
    private val shows = ViewSnapshot(id = "view-shows", name = "TV Shows", kind = ViewKind.LIBRARY)

    @Test
    fun `a matching id resolves to that view`() {
        val resolved = resolveStartupView("view-shows", listOf(movies, shows))
        assertEquals(shows, resolved)
    }

    @Test
    fun `a stale id -- no longer among the current views -- resolves to null (Home)`() {
        val resolved = resolveStartupView("view-deleted", listOf(movies, shows))
        assertNull(resolved)
    }

    @Test
    fun `no persisted id resolves to null (Home)`() {
        val resolved = resolveStartupView(null, listOf(movies, shows))
        assertNull(resolved)
    }

    @Test
    fun `a stale id against an empty view list also resolves to null`() {
        assertNull(resolveStartupView("view-movies", emptyList()))
    }
}
