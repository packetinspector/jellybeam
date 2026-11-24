package tv.jellybeam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.jellybeam.nav.NavBackStack
import tv.jellybeam.nav.Screen
import tv.jellybeam.player.RemoteKey
import tv.jellybeam.player.UiOp
import uniffi.jellybeam_core.ViewKind
import uniffi.jellybeam_core.ViewSnapshot

/** docs/31 §4.5: what MainActivity's remote port does with each browsing op. */
class RemoteTargetsTest {
    private val movies = ViewSnapshot("lib-movies", "Synthetic Movies", ViewKind.LIBRARY, "movies")
    private val music = ViewSnapshot("lib-music", "Synthetic Music", ViewKind.LIBRARY, "music")
    private val views = listOf(movies, music)

    private val pressed = mutableListOf<RemoteKey>()
    private var keyTaken = true
    private val targets = RemoteTargets()

    private val typed = mutableListOf<String>()
    private var fieldFocused = true

    private fun take(op: UiOp): Boolean = targets.take(op, { typed += it; fieldFocused }) { pressed += it; keyTaken }

    @Test
    fun `a sent string goes to the window as typing`() {
        assertTrue(take(UiOp.Text("typed")))
        assertEquals(listOf("typed"), typed)
    }

    @Test
    fun `a sent string with no focused field is untaken so the bridge shows it as a message`() {
        fieldFocused = false

        assertFalse(take(UiOp.Text("typed")))
    }

    @Test
    fun `typing drops line breaks and characters with no key`() {
        val keys = keystrokes("a\r\nb\u00e9c\n") { c -> if (c == '\u00e9') null else arrayOf("down $c", "up $c") }

        assertEquals(listOf("down a", "up a", "down b", "up b", "down c", "up c"), keys)
    }

    @Test
    fun `a key goes to the window and counts as applied even when nothing moved`() {
        keyTaken = false

        assertTrue(take(UiOp.Key(RemoteKey.Down)))
        assertEquals(listOf(RemoteKey.Down), pressed)
    }

    @Test
    fun `the context menu is the Menu key and is untaken where no menu answers it`() {
        assertTrue(take(UiOp.ContextMenu))
        keyTaken = false
        assertFalse(take(UiOp.ContextMenu))
        assertEquals(listOf(RemoteKey.Menu, RemoteKey.Menu), pressed)
    }

    @Test
    fun `go home, search and settings take the stack's navigation`() {
        val opened = mutableListOf<Screen>()
        targets.navigate.claim { opened += it }

        assertTrue(take(UiOp.Home))
        assertTrue(take(UiOp.Search))
        assertTrue(take(UiOp.Settings))
        assertEquals(listOf(Screen.Home, Screen.Search, Screen.Settings), opened)
    }

    /** docs/31 §4.3: a library open is the drawer's pick of that library's page: pushed above Home, replacing another library. */
    @Test
    fun `a library opens its own page, music its music page, by the drawer's stack transform`() {
        val home = NavBackStack.of(Screen.Home)

        assertEquals(listOf(Screen.Home, Screen.Library(movies)), libraryOpenStack(home, views, "lib-movies")?.entries)
        assertEquals(listOf(Screen.Home, Screen.MusicLibrary(music)), libraryOpenStack(home, views, "lib-music")?.entries)
        val onMovies = home.push(Screen.Library(movies))
        assertEquals(listOf(Screen.Home, Screen.MusicLibrary(music)), libraryOpenStack(onMovies, views, "lib-music")?.entries)
    }

    @Test
    fun `a library open is none for an unknown library, or a stack not rooted on Home`() {
        val home = NavBackStack.of(Screen.Home)

        assertNull(libraryOpenStack(home, views, "lib-unknown"))
        assertNull(libraryOpenStack(NavBackStack.of(Screen.SignIn), views, "lib-movies"))
    }

    @Test
    fun `navigation with no Home-rooted stack is untaken`() {
        assertFalse(take(UiOp.Home))
    }

    @Test
    fun `letters step the claimed rail and are untaken without one or at its end`() {
        assertFalse(take(UiOp.NextLetter))
        val steps = mutableListOf<Boolean>()
        targets.letters.claim { forward -> steps += forward; forward }

        assertTrue(take(UiOp.NextLetter))
        assertFalse(take(UiOp.PreviousLetter))
        assertEquals(listOf(true, false), steps)
    }
}
