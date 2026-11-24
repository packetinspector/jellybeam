package tv.jellybeam.player

import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** docs/31 §7: one owner's reports run one at a time in send order. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReportQueueTest {
    @Test
    fun `a drain returns only once every report sent before it has run`() = runTest(timeout = 5.seconds) {
        val queue = ReportQueue(backgroundScope)
        val gate = CompletableDeferred<Unit>()
        val log = mutableListOf<String>()
        queue.send {
            gate.await()
            log += "report"
        }
        val drained = async {
            queue.drain()
            log += "drained"
        }
        runCurrent()
        assertEquals("the drain waits behind the held report", emptyList<String>(), log)

        gate.complete(Unit)
        runCurrent()

        assertEquals(listOf("report", "drained"), log)
        drained.await()
    }

    /** A queue whose scope ended runs nothing more, so a drain has nothing to wait for. */
    @Test
    fun `a drain on a queue whose scope ended returns at once`() = runTest(timeout = 5.seconds) {
        val scope = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        val queue = ReportQueue(scope)
        queue.send { awaitCancellation() }
        runCurrent()
        scope.cancel()

        queue.drain()
    }

    @Test
    fun `a report's failure or own cancellation never stops the reports behind it`() = runTest(timeout = 5.seconds) {
        val queue = ReportQueue(backgroundScope)
        val log = mutableListOf<String>()
        queue.send { throw CancellationException("the report's own timeout") }
        queue.send { error("synthetic failure") }
        queue.send { log += "after" }

        runCurrent()

        assertEquals(listOf("after"), log)
    }
}
