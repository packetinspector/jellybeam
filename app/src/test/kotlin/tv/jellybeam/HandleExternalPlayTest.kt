package tv.jellybeam

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** docs/17 §3: an external PLAY launches after the old player is idle, and only if it is still the pending request. */
@OptIn(ExperimentalCoroutinesApi::class)
class HandleExternalPlayTest {

    private val request = ExternalPlaybackRequest(requestId = 7L, itemId = "item-1", kind = ExternalPlaybackRequestKind.PLAY)

    @Test
    fun `a request still pending when the player goes idle launches and then is consumed`() = runTest {
        val idle = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()

        launch {
            handleExternalPlay(
                request = request,
                readyStackHomeRooted = true,
                awaitIdle = { idle.await() },
                stillPending = { request },
                launch = { events += "launch:$it" },
                consume = { events += "consume" },
                signInRequired = { events += "signIn" },
            )
        }
        runCurrent()
        assertEquals("nothing happens before idle", emptyList<String>(), events)
        idle.complete(Unit)
        runCurrent()

        assertEquals(listOf("launch:item-1", "consume"), events)
    }

    @Test
    fun `a request dropped while waiting for idle never launches`() = runTest {
        val idle = CompletableDeferred<Unit>()
        var pending: ExternalPlaybackRequest? = request
        val launched = mutableListOf<String>()

        launch {
            handleExternalPlay(
                request = request,
                readyStackHomeRooted = true,
                awaitIdle = { idle.await() },
                stillPending = { pending },
                launch = { launched += it },
                consume = {},
                signInRequired = {},
            )
        }
        runCurrent()
        pending = null
        idle.complete(Unit)
        runCurrent()

        assertEquals("an account change dropped the request before idle", emptyList<String>(), launched)
    }

    @Test
    fun `a signed-out launch is consumed with sign-in feedback and no player`() = runTest {
        val events = mutableListOf<String>()

        handleExternalPlay(
            request = request,
            readyStackHomeRooted = false,
            awaitIdle = { events += "awaitIdle" },
            stillPending = { request },
            launch = { events += "launch" },
            consume = { events += "consume" },
            signInRequired = { events += "signIn" },
        )

        assertEquals(listOf("consume", "signIn"), events)
    }

    /** docs/18 §2.1: the recheck reads the Activity's live holder, so an account change that cleared it in the same turn as idle wins. */
    @Test
    fun `a request cleared in the same turn the player goes idle never launches`() = runTest {
        val idle = CompletableDeferred<Unit>()
        var holder: ExternalPlaybackRequest? = request
        val launched = mutableListOf<String>()

        launch {
            handleExternalPlay(
                request = request,
                readyStackHomeRooted = true,
                awaitIdle = { idle.await() },
                stillPending = { holder },
                launch = { launched += it },
                consume = {},
                signInRequired = {},
            )
        }
        runCurrent()
        idle.complete(Unit)
        holder = pendingAfterConsume(holder, null)
        runCurrent()

        assertEquals("consume(null) and idle landed together", emptyList<String>(), launched)
    }

    @Test
    fun `the same request read back as an equal value still launches`() = runTest {
        val launched = mutableListOf<String>()

        handleExternalPlay(
            request = request,
            readyStackHomeRooted = true,
            awaitIdle = {},
            stillPending = { request.copy() },
            launch = { launched += it },
            consume = {},
            signInRequired = {},
        )

        assertEquals(listOf("item-1"), launched)
    }
}
