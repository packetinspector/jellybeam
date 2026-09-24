package tv.jellybeam.nav

import tv.jellybeam.ui.cards.testCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.jellybeam_core.ViewKind
import uniffi.jellybeam_core.ViewSnapshot

class NavBackStackTest {

    // ---- construction ------------------------------------------------------

    @Test
    fun `of starts with a single entry and no back`() {
        val stack = NavBackStack.of(Screen.Home)
        assertEquals(listOf(Screen.Home), stack.entries)
        assertEquals(Screen.Home, stack.current)
        assertFalse(stack.canGoBack)
    }

    // ---- push ----------------------------------------------------------------

    @Test
    fun `push adds a new top entry and enables back`() {
        val view = ViewSnapshot(id = "v1", name = "Movies", kind = ViewKind.LIBRARY)
        val stack = NavBackStack.of(Screen.Home).push(Screen.Library(view))

        assertEquals(listOf(Screen.Home, Screen.Library(view)), stack.entries)
        assertEquals(Screen.Library(view), stack.current)
        assertTrue(stack.canGoBack)
    }

    @Test
    fun `push does not touch the entries below it`() {
        val card1 = testCard(id = "a")
        val card2 = testCard(id = "b")
        val stack = NavBackStack.of(Screen.Home)
            .push(Screen.Detail(card1))
            .push(Screen.Detail(card2))

        assertEquals(listOf(Screen.Home, Screen.Detail(card1), Screen.Detail(card2)), stack.entries)
    }

    @Test
    fun `push caps retained history while preserving root and newest entries`() {
        var stack = NavBackStack.of(Screen.Home)
        repeat(NavBackStack.MAX_RETAINED_ENTRIES + 3) { index ->
            stack = stack.push(Screen.Detail(testCard(id = "card-$index")))
        }

        assertEquals(NavBackStack.MAX_RETAINED_ENTRIES, stack.entries.size)
        assertEquals(Screen.Home, stack.entries.first())
        assertEquals(Screen.Detail(testCard(id = "card-${NavBackStack.MAX_RETAINED_ENTRIES + 2}")), stack.current)
    }

    // ---- pop -------------------------------------------------------------------

    @Test
    fun `pop removes the top entry`() {
        val card = testCard()
        val stack = NavBackStack.of(Screen.Home).push(Screen.Detail(card)).pop()

        assertEquals(NavBackStack.of(Screen.Home), stack)
    }

    @Test
    fun `pop at the root is a no-op`() {
        val stack = NavBackStack.of(Screen.Home)
        assertEquals(stack, stack.pop())
    }

    @Test
    fun `pop after a sibling replace skips the visited sibling entirely`() {
        // docs/07 §5: back collapses to below the *first* Detail entry, not each sibling in turn.
        val series = testCard(id = "series-1", itemType = "Series")
        val ep1 = testCard(id = "ep-1", itemType = "Episode")
        val ep2 = testCard(id = "ep-2", itemType = "Episode")
        val ep3 = testCard(id = "ep-3", itemType = "Episode")

        val stack = NavBackStack.of(Screen.Home)
            .push(Screen.Detail(series))
            .push(Screen.Detail(ep1))
            .replace(Screen.Detail(ep2))
            .replace(Screen.Detail(ep3))

        assertEquals(Screen.Detail(ep3), stack.current)

        val afterBack = stack.pop()
        assertEquals(Screen.Detail(series), afterBack.current)
    }

    // ---- replace -----------------------------------------------------------

    @Test
    fun `replace swaps the top entry in place, leaving the stack size unchanged`() {
        val ep1 = testCard(id = "ep-1", itemType = "Episode")
        val ep2 = testCard(id = "ep-2", itemType = "Episode")
        val stack = NavBackStack.of(Screen.Home).push(Screen.Detail(ep1))

        val replaced = stack.replace(Screen.Detail(ep2))

        assertEquals(2, replaced.entries.size)
        assertEquals(Screen.Detail(ep2), replaced.current)
    }

    // ---- reset -------------------------------------------------------------

    @Test
    fun `reset clears history down to a single fresh entry`() {
        val stack = NavBackStack.of(Screen.SignIn)
            .push(Screen.Home)
            .push(Screen.Detail(testCard()))
            .reset(Screen.SignIn)

        assertEquals(NavBackStack.of(Screen.SignIn), stack)
        assertFalse(stack.canGoBack)
    }

    // ---- isHomeRooted / isHomeVisible (flash-on-return fix) -----------------

    @Test
    fun `isHomeRooted is true for a Home-rooted stack no matter how deep`() {
        val stack = NavBackStack.of(Screen.Home)
            .push(Screen.Detail(testCard(id = "a")))
            .push(Screen.Detail(testCard(id = "b")))

        assertTrue(stack.isHomeRooted)
    }

    @Test
    fun `isHomeRooted is false for a SignIn-rooted stack`() {
        assertFalse(NavBackStack.of(Screen.SignIn).isHomeRooted)
    }

    @Test
    fun `isHomeVisible is true only when Home is current`() {
        val atHome = NavBackStack.of(Screen.Home)
        val pushedOverHome = atHome.push(Screen.Detail(testCard()))

        assertTrue(atHome.isHomeVisible)
        assertFalse(pushedOverHome.isHomeVisible)
    }

    @Test
    fun `isHomeVisible is true again after popping back to Home`() {
        val stack = NavBackStack.of(Screen.Home)
            .push(Screen.Detail(testCard()))
            .pop()

        assertTrue(stack.isHomeRooted)
        assertTrue(stack.isHomeVisible)
    }

    // ---- entryIdentity / entryKeys (retain-every-screen pass) ---------------

    @Test
    fun `entryIdentity is stable per singleton screen`() {
        assertEquals("home", Screen.Home.entryIdentity())
        assertEquals("search", Screen.Search.entryIdentity())
        assertEquals("settings", Screen.Settings.entryIdentity())
        assertEquals("signin", Screen.SignIn.entryIdentity())
    }

    @Test
    fun `entryIdentity for Library and Detail is keyed on id only`() {
        val view = ViewSnapshot(id = "v1", name = "Movies", kind = ViewKind.LIBRARY)
        val card = testCard(id = "c1")

        assertEquals("library-v1", Screen.Library(view).entryIdentity())
        assertEquals("detail-c1", Screen.Detail(card).entryIdentity())
    }

    @Test
    fun `entryKeys pairs each entry's index with its identity, in stack order`() {
        val view = ViewSnapshot(id = "v1", name = "Movies", kind = ViewKind.LIBRARY)
        val stack = NavBackStack.of(Screen.Home).push(Screen.Library(view))

        assertEquals(listOf("0:home", "1:library-v1"), stack.entryKeys())
    }

    @Test
    fun `push leaves every existing entry's key untouched, appending only a new one`() {
        val before = NavBackStack.of(Screen.Home).push(Screen.Detail(testCard(id = "a")))
        val after = before.push(Screen.Detail(testCard(id = "b")))

        assertEquals(before.entryKeys(), after.entryKeys().dropLast(1))
        assertEquals("2:detail-b", after.entryKeys().last())
    }

    @Test
    fun `pop leaves every surviving entry's key untouched`() {
        val stack = NavBackStack.of(Screen.Home)
            .push(Screen.Detail(testCard(id = "a")))
            .push(Screen.Detail(testCard(id = "b")))
        val keysBeforePop = stack.entryKeys()

        val popped = stack.pop()

        assertEquals(keysBeforePop.dropLast(1), popped.entryKeys())
    }

    @Test
    fun `replace with a different screen changes the key at that index -- Compose must dispose it`() {
        val stack = NavBackStack.of(Screen.Home).push(Screen.Detail(testCard(id = "a")))
        val keysBefore = stack.entryKeys()

        val replaced = stack.replace(Screen.Detail(testCard(id = "b")))

        // A different key at the same index is the signal Compose's key() uses to tear down
        // the old composition and its ViewModel instead of reusing it.
        assertEquals(keysBefore.size, replaced.entryKeys().size)
        assertEquals(keysBefore[0], replaced.entryKeys()[0]) // Home untouched
        org.junit.Assert.assertNotEquals(keysBefore[1], replaced.entryKeys()[1])
    }

    @Test
    fun `a chain of sibling-episode replaces changes only the top key each time`() {
        val series = testCard(id = "series-1", itemType = "Series")
        val ep1 = testCard(id = "ep-1", itemType = "Episode")
        val ep2 = testCard(id = "ep-2", itemType = "Episode")

        val afterPush = NavBackStack.of(Screen.Home)
            .push(Screen.Detail(series))
            .push(Screen.Detail(ep1))
        val afterReplace = afterPush.replace(Screen.Detail(ep2))

        assertEquals(afterPush.entryKeys().dropLast(1), afterReplace.entryKeys().dropLast(1))
        assertEquals("2:detail-ep-1", afterPush.entryKeys().last())
        assertEquals("2:detail-ep-2", afterReplace.entryKeys().last())
    }

    // ---- Seerr Discover entryIdentity (docs/14-seerr-discover.md) ---------

    @Test
    fun `entryIdentity is stable per Discover singleton screen`() {
        assertEquals("discover", Screen.Discover.entryIdentity())
        assertEquals("discover-requests", Screen.DiscoverRequests.entryIdentity())
        assertEquals("discover-search", Screen.DiscoverSearch.entryIdentity())
    }

    @Test
    fun `entryIdentity for DiscoverGrid is keyed on kind and genre`() {
        assertEquals(
            "discover-grid-MOVIES-all",
            Screen.DiscoverGrid(uniffi.jellybeam_core.SeerrBrowseKind.MOVIES, "Movies").entryIdentity(),
        )
        assertEquals(
            "discover-grid-TV-12",
            Screen.DiscoverGrid(uniffi.jellybeam_core.SeerrBrowseKind.TV, "Comedy", genreId = 12L, genreName = "Comedy").entryIdentity(),
        )
    }

    @Test
    fun `entryIdentity for DiscoverDetail and DiscoverPerson is keyed on id only`() {
        assertEquals(
            "discover-detail-MOVIE-42",
            Screen.DiscoverDetail(uniffi.jellybeam_core.SeerrMediaType.MOVIE, 42L).entryIdentity(),
        )
        assertEquals("discover-person-7", Screen.DiscoverPerson(7L).entryIdentity())
    }
}
