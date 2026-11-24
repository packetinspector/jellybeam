package tv.jellybeam

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.jellybeam.data.FakeCoreGateway
import tv.jellybeam.nav.NavBackStack
import tv.jellybeam.nav.Screen
import tv.jellybeam.player.Req
import tv.jellybeam.ui.cards.testCard
import uniffi.jellybeam_core.ViewKind
import uniffi.jellybeam_core.ViewSnapshot

@OptIn(ExperimentalCoroutinesApi::class)
class HandleOpenDetailTest {

    private fun detailRequest(itemId: String) = Req(1L, Req.Kind.OpenDetail(itemId), Req.Origin.External)

    @Test
    fun `opening a detail pushes it on a home-rooted stack and consumes the request after navigating`() = runTest {
        val card = testCard(id = "movie-1", itemType = "Movie")
        val gateway = FakeCoreGateway().apply { cardsByItemId["movie-1"] = card }
        val events = mutableListOf<String>()
        val navigated = mutableListOf<NavBackStack>()

        handleOpenDetail(
            request = detailRequest("movie-1"),
            stillPending = { detailRequest("movie-1") },
            readyStack = NavBackStack.of(Screen.Home),
            gateway = gateway,
            dropped = { error("no drop expected") },
            stackCovered = { false },
            endAmbient = { error("no ambient end expected") },
            consume = { events += "consume" },
            navigate = {
                events += "navigate"
                navigated += it
            },
        )

        assertEquals(listOf(NavBackStack(listOf(Screen.Home, Screen.Detail(card)))), navigated)
        assertEquals("the request is consumed only once navigation has happened", listOf("navigate", "consume"), events)
    }

    @Test
    fun `opening a detail over an episode detail replaces it instead of stacking`() = runTest {
        val episode = testCard(id = "ep-1", itemType = "Episode")
        val series = testCard(id = "series-1", itemType = "Series")
        val gateway = FakeCoreGateway().apply { cardsByItemId["series-1"] = series }
        val navigated = mutableListOf<NavBackStack>()

        handleOpenDetail(
            request = detailRequest("series-1"),
            stillPending = { detailRequest("series-1") },
            readyStack = NavBackStack(listOf(Screen.Home, Screen.Detail(episode))),
            gateway = gateway,
            dropped = { error("no drop expected") },
            stackCovered = { false },
            endAmbient = { error("no ambient end expected") },
            consume = {},
            navigate = { navigated += it },
        )

        assertEquals(listOf(NavBackStack(listOf(Screen.Home, Screen.Detail(series)))), navigated)
    }

    @Test
    fun `a request on a stack that is not home-rooted is consumed without navigating`() = runTest {
        val gateway = FakeCoreGateway().apply { cardsByItemId["movie-1"] = testCard(id = "movie-1") }
        val events = mutableListOf<String>()

        handleOpenDetail(
            request = detailRequest("movie-1"),
            stillPending = { detailRequest("movie-1") },
            readyStack = NavBackStack.of(Screen.SignIn),
            gateway = gateway,
            dropped = { error("no drop expected") },
            stackCovered = { false },
            endAmbient = { error("no ambient end expected") },
            consume = { events += "consume" },
            navigate = { events += "navigate" },
        )

        assertEquals(listOf("consume"), events)
    }

    @Test
    fun `a lookup cancelled before it completes leaves the request pending`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val gateway = FakeCoreGateway().apply {
            cardsByItemId["movie-1"] = testCard(id = "movie-1", itemType = "Movie")
            cardByIdGate = gate
        }
        val events = mutableListOf<String>()
        val caller = launch {
            handleOpenDetail(
                request = detailRequest("movie-1"),
                stillPending = { detailRequest("movie-1") },
                readyStack = NavBackStack.of(Screen.Home),
                gateway = gateway,
                dropped = { error("no drop expected") },
                stackCovered = { false },
                endAmbient = { error("no ambient end expected") },
                consume = { events += "consume" },
                navigate = { events += "navigate" },
            )
        }
        runCurrent()
        caller.cancel()
        runCurrent()

        assertEquals("a cancelled lookup neither navigates nor consumes", emptyList<String>(), events)
        assertTrue(caller.isCancelled)
    }

    /** docs/18 §2.1: an account change dropped the request while the card lookup ran, so it must not navigate or consume. */
    @Test
    fun `a request cleared during the lookup neither navigates nor consumes`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val gateway = FakeCoreGateway().apply {
            cardsByItemId["movie-1"] = testCard(id = "movie-1", itemType = "Movie")
            cardByIdGate = gate
        }
        val request = detailRequest("movie-1")
        var pending: Req<Req.Kind.OpenDetail>? = request
        val events = mutableListOf<String>()
        val caller = launch {
            handleOpenDetail(
                request = request,
                readyStack = NavBackStack.of(Screen.Home),
                gateway = gateway,
                stillPending = { pending },
                dropped = { error("no drop expected") },
                stackCovered = { false },
                endAmbient = { error("no ambient end expected") },
                consume = { events += "consume" },
                navigate = { events += "navigate" },
            )
        }
        runCurrent()

        pending = null
        gate.complete(Unit)
        runCurrent()

        assertEquals(emptyList<String>(), events)
        assertTrue(caller.isCompleted)
    }

    private fun libraryRequest(id: String) = Req(1L, Req.Kind.OpenDetail(id, library = true), Req.Origin.Remote)

    /** Runs [request] on [stack] and records every effect in order: `endAmbient`, `navigate <top>`, `dropped`, `consume`. */
    private suspend fun open(
        request: Req<Req.Kind.OpenDetail>,
        stack: NavBackStack,
        views: List<ViewSnapshot> = listOf(movies),
        covered: Boolean = false,
        events: MutableList<String>,
    ) = handleOpenDetail(
        request = request,
        stillPending = { request },
        readyStack = stack,
        gateway = FakeCoreGateway().apply {
            publishViews(views)
            cardsByItemId["movie-1"] = movie
        },
        dropped = { events += "dropped" },
        stackCovered = { covered },
        endAmbient = { events += "endAmbient" },
        consume = { events += "consume" },
        navigate = { events += "navigate ${it.entries.last()}" },
    )

    private suspend fun openLibrary(id: String, stack: NavBackStack, views: List<ViewSnapshot>, covered: Boolean = false, events: MutableList<String>) =
        open(libraryRequest(id), stack, views, covered, events)

    private val movies = ViewSnapshot("lib-movies", "Synthetic Movies", ViewKind.LIBRARY, "movies")
    private val movie = testCard(id = "movie-1", itemType = "Movie")

    /** docs/31 §4.3, §4.5: a remote library open that lands ends a drawing ambient, lands as the drawer's pick, then consumes; it never reads a card. */
    @Test
    fun `a library open ends ambient, navigates to the library's page and consumes`() = runTest {
        val events = mutableListOf<String>()

        openLibrary("lib-movies", NavBackStack.of(Screen.Home), listOf(movies), events = events)

        assertEquals(listOf("endAmbient", "navigate ${Screen.Library(movies)}", "consume"), events)
    }

    /** docs/31 §4.5: a remote item open that lands ends a drawing ambient before it navigates. */
    @Test
    fun `a remote item open ends ambient, then navigates`() = runTest {
        val events = mutableListOf<String>()

        open(Req(1L, Req.Kind.OpenDetail("movie-1"), Req.Origin.Remote), NavBackStack.of(Screen.Home), events = events)

        assertEquals(listOf("endAmbient", "navigate ${Screen.Detail(movie)}", "consume"), events)
    }

    /** docs/31 §4.3: a library open that cannot land leaves exactly one drop, still frees the waiting request, and leaves ambient alone. */
    @Test
    fun `a library open that cannot land is dropped once and consumed, never silently`() = runTest {
        val miss = mutableListOf<String>()
        openLibrary("lib-gone", NavBackStack.of(Screen.Home), listOf(movies), events = miss)
        val signedOut = mutableListOf<String>()
        openLibrary("lib-movies", NavBackStack.of(Screen.SignIn), listOf(movies), events = signedOut)
        val prompted = mutableListOf<String>()
        openLibrary("lib-movies", NavBackStack.of(Screen.Home), listOf(movies), covered = true, events = prompted)

        for (events in listOf(miss, signedOut, prompted)) assertEquals(listOf("dropped", "consume"), events)
    }

    /** docs/31 §4.3: a remote item open under a prompt or account surface never moves the hidden stack. */
    @Test
    fun `a remote item open while the stack is covered is dropped once and consumed`() = runTest {
        val events = mutableListOf<String>()

        open(Req(1L, Req.Kind.OpenDetail("movie-1"), Req.Origin.Remote), NavBackStack.of(Screen.Home), covered = true, events = events)

        assertEquals(listOf("dropped", "consume"), events)
    }

    /** docs/05 "external": only a remote open drops under a cover or ends ambient; an external open lands as before. */
    @Test
    fun `an external item open while covered navigates without touching ambient`() = runTest {
        val events = mutableListOf<String>()

        open(detailRequest("movie-1"), NavBackStack.of(Screen.Home), covered = true, events = events)

        assertEquals(listOf("navigate ${Screen.Detail(movie)}", "consume"), events)
    }

    private fun remoteRequest(id: String) = Req(1L, Req.Kind.OpenDetail(id), Req.Origin.Remote)

    /** docs/31 §4.3: the cover is re-read when the open lands, so a prompt raised during the card lookup still drops it. */
    @Test
    fun `a remote item open drops when a cover opens during its card lookup`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val gateway = FakeCoreGateway().apply {
            cardsByItemId["movie-1"] = movie
            cardByIdGate = gate
        }
        val request = remoteRequest("movie-1")
        var covered = false
        val events = mutableListOf<String>()
        launch {
            handleOpenDetail(
                request = request,
                stillPending = { request },
                readyStack = NavBackStack.of(Screen.Home),
                gateway = gateway,
                dropped = { events += "dropped" },
                stackCovered = { covered },
                endAmbient = { events += "endAmbient" },
                consume = { events += "consume" },
                navigate = { events += "navigate" },
            )
        }
        runCurrent()
        covered = true
        gate.complete(Unit)
        runCurrent()

        assertEquals(listOf("dropped", "consume"), events)
    }

    /** docs/31 §4.3: a remote item open that finds no card leaves one drop line, never a silent consume. */
    @Test
    fun `a remote item open with no card drops once`() = runTest {
        val events = mutableListOf<String>()

        open(remoteRequest("movie-gone"), NavBackStack.of(Screen.Home), events = events)

        assertEquals(listOf("dropped", "consume"), events)
    }

    /** docs/31 §4.3: a remote item open with no Home-rooted stack to land on leaves one drop line. */
    @Test
    fun `a remote item open off a Home-rooted stack drops once`() = runTest {
        val events = mutableListOf<String>()

        open(remoteRequest("movie-1"), NavBackStack.of(Screen.SignIn), events = events)

        assertEquals(listOf("dropped", "consume"), events)
    }
}
