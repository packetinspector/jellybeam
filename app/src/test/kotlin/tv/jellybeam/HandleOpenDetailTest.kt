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
import tv.jellybeam.ui.cards.testCard

@OptIn(ExperimentalCoroutinesApi::class)
class HandleOpenDetailTest {

    private fun detailRequest(itemId: String) = ExternalPlaybackRequest(1L, itemId, ExternalPlaybackRequestKind.OPEN_DETAIL)

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
        var pending: ExternalPlaybackRequest? = request
        val events = mutableListOf<String>()
        val caller = launch {
            handleOpenDetail(
                request = request,
                readyStack = NavBackStack.of(Screen.Home),
                gateway = gateway,
                stillPending = { pending },
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
}
