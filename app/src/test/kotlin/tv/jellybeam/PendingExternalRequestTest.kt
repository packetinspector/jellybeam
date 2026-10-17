package tv.jellybeam

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** docs/18 §2.1: an account change drops a pending external request, so it never navigates on the new account. */
@OptIn(ExperimentalCoroutinesApi::class)
class PendingExternalRequestTest {

    private val play = ExternalPlaybackRequest(1L, "item-1", ExternalPlaybackRequestKind.PLAY)
    private val openDetail = ExternalPlaybackRequest(2L, "item-2", ExternalPlaybackRequestKind.OPEN_DETAIL)

    private var pending: ExternalPlaybackRequest? = null

    /** Only the request handler appends here, so a non-empty list means a stale request navigated. */
    private val navigated = mutableListOf<ExternalPlaybackRequest>()

    private val coreEpoch = epochsFlow()
    private val owner = AccountTreeOwner(coreEpoch) { pending = pendingAfterConsume(pending, null) }

    /** The request handler: navigates for whatever is pending once the new account tree runs. */
    private fun runRequestHandler() {
        pending?.let { navigated += it }
    }

    private fun TestScope.startFollowing() {
        backgroundScope.launch { owner.follow { } }
        runCurrent()
    }

    @Test
    fun `every account change drops a pending request of either kind`() = runTest {
        startFollowing()
        val changes: List<Pair<String, TestScope.() -> Unit>> = listOf(
            "switch, remove active, reauthentication or add server" to { coreEpoch.epoch = coreEpoch.epoch + 1uL },
        )

        for ((event, change) in changes) {
            for (request in listOf(play, openDetail)) {
                pending = request
                navigated.clear()

                change()
                runCurrent()
                runRequestHandler()

                assertNull("$event drops a pending ${request.kind} request", pending)
                assertEquals("$event: a ${request.kind} request never navigates", emptyList<ExternalPlaybackRequest>(), navigated)
            }
        }
    }

    @Test
    fun `consuming leaves only a newer pending request`() {
        assertNull("null drops a play request", pendingAfterConsume(play, null))
        assertNull("null drops a detail request", pendingAfterConsume(openDetail, null))
        assertNull("consuming the pending request clears it", pendingAfterConsume(play, play))
        assertSame("a different request keeps the pending one", openDetail, pendingAfterConsume(openDetail, play))
        assertNull("nothing pending stays nothing", pendingAfterConsume(null, play))
    }
}
