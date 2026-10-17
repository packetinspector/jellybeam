package tv.jellybeam.ui.common

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * docs/16 §4.6, with a virtual clock: a hidden screen only marks dirty, becoming active refreshes
 * once if dirty, and an active screen refreshes on the leading event plus one trailing run after the rest.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChangeRefreshSchedulerTest {

    /** The scheduler's coroutines never complete; a child [Job], cancelled explicitly, keeps them from outliving `runTest`. */
    private fun CoroutineScope.newSchedulerScope(): Pair<CoroutineScope, Job> {
        val job = Job(coroutineContext[Job])
        return CoroutineScope(coroutineContext + job) to job
    }

    private fun TestScope.settle(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    /** A scheduler over [events] and [active] counting its refreshes; [block] runs before the scope is cancelled. */
    private fun TestScope.withScheduler(
        active: Boolean,
        refresh: suspend () -> Unit = {},
        block: TestScope.(events: MutableSharedFlow<Unit>, active: MutableStateFlow<Boolean>, scheduler: ChangeRefreshScheduler<Unit>, count: () -> Int) -> Unit,
    ) {
        val events = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
        val activeFlow = MutableStateFlow(active)
        var refreshCount = 0
        val (scope, job) = newSchedulerScope()
        val scheduler = ChangeRefreshScheduler(scope, events, activeFlow, refresh = { refreshCount++; refresh() })
        try {
            runCurrent()
            block(events, activeFlow, scheduler) { refreshCount }
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `a single quiet event triggers exactly one refresh`() = runTest {
        withScheduler(active = true) { events, _, _, count ->
            assertEquals("nothing has happened yet", 0, count())
            events.tryEmit(Unit)
            runCurrent()
            assertEquals("the leading event refreshes immediately, no debounce wait", 1, count())
            settle(5_000)
            assertEquals("no further event landed -- no further refresh", 1, count())
        }
    }

    @Test
    fun `events while hidden refresh nothing`() = runTest {
        withScheduler(active = false) { events, _, _, count ->
            repeat(40) {
                events.tryEmit(Unit)
                settle(100)
            }
            settle(10_000)
            assertEquals("a hidden screen only marks itself dirty", 0, count())
        }
    }

    @Test
    fun `becoming active after hidden events refreshes exactly once`() = runTest {
        withScheduler(active = false) { events, active, _, count ->
            repeat(10) {
                events.tryEmit(Unit)
                settle(100)
            }
            active.value = true
            runCurrent()
            assertEquals("activation catches up at once", 1, count())
            settle(5_000)
            assertEquals("and only once", 1, count())
        }
    }

    @Test
    fun `becoming active with nothing dirty refreshes nothing`() = runTest {
        withScheduler(active = false) { _, active, _, count ->
            active.value = true
            settle(5_000)
            active.value = false
            settle(100)
            active.value = true
            settle(5_000)
            assertEquals(0, count())
        }
    }

    @Test
    fun `an active burst costs the leading refresh plus one trailing`() = runTest {
        withScheduler(active = true) { events, _, _, count ->
            repeat(5) {
                events.tryEmit(Unit)
                settle(50)
            }
            assertEquals("the leading event refreshed, the rest wait out the rest period", 1, count())
            settle(5_000)
            assertEquals("one trailing refresh covers every event inside the rest", 2, count())
        }
    }

    @Test
    fun `events during a refresh cost exactly one trailing refresh`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var running = 0
        var maxRunning = 0
        withScheduler(
            active = true,
            refresh = {
                running++
                maxRunning = maxOf(maxRunning, running)
                if (!gate.isCompleted) gate.await()
                running--
            },
        ) { events, _, _, count ->
            events.tryEmit(Unit)
            runCurrent()
            assertEquals(1, count())
            repeat(3) { events.tryEmit(Unit) }
            runCurrent()
            assertEquals("events wait behind the running refresh", 1, count())

            gate.complete(Unit)
            settle(5_000)
            assertEquals(2, count())
            assertEquals("one at a time", 1, maxRunning)
        }
    }

    @Test
    fun `leaving during the rest holds the trailing refresh until the screen is back`() = runTest {
        withScheduler(active = true) { events, active, _, count ->
            events.tryEmit(Unit)
            runCurrent()
            events.tryEmit(Unit)
            active.value = false
            settle(5_000)
            assertEquals("no trailing refresh while hidden", 1, count())

            active.value = true
            runCurrent()
            assertEquals("the owed refresh runs on return", 2, count())
        }
    }

    @Test
    fun `markDirty refreshes an active screen and waits for a hidden one`() = runTest {
        withScheduler(active = false) { _, active, scheduler, count ->
            scheduler.markDirty()
            settle(5_000)
            assertEquals(0, count())
            active.value = true
            runCurrent()
            assertEquals(1, count())

            settle(1_000)
            scheduler.markDirty()
            runCurrent()
            assertEquals(2, count())
        }
    }

    @Test
    fun `a refresh that throws is reported and the next dirty still refreshes`() = runTest {
        val events = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
        val active = MutableStateFlow(true)
        var refreshCount = 0
        val errors = mutableListOf<Throwable>()
        val (scope, job) = newSchedulerScope()
        ChangeRefreshScheduler(
            scope, events, active,
            refresh = { if (++refreshCount == 1) throw IllegalStateException("boom") },
            onError = { errors += it },
        )
        try {
            runCurrent()
            events.tryEmit(Unit)
            runCurrent()
            assertEquals(1, errors.size)

            settle(1_000)
            events.tryEmit(Unit)
            runCurrent()
            assertEquals("the loop survived", 2, refreshCount)
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `cancelling the scope cancels a running refresh and ends the loop`() = runTest {
        val events = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
        val active = MutableStateFlow(true)
        var refreshCount = 0
        var cancelled = false
        val errors = mutableListOf<Throwable>()
        val (scope, job) = newSchedulerScope()
        ChangeRefreshScheduler(
            scope, events, active,
            refresh = {
                refreshCount++
                try {
                    CompletableDeferred<Unit>().await()
                } catch (e: CancellationException) {
                    cancelled = true
                    throw e
                }
            },
            onError = { errors += it },
        )
        runCurrent()
        events.tryEmit(Unit)
        runCurrent()
        job.cancel()
        runCurrent()
        events.tryEmit(Unit)
        settle(5_000)

        assertTrue("the running refresh saw the cancellation", cancelled)
        assertTrue("a cancellation is not a refresh error", errors.isEmpty())
        assertEquals("nothing runs after the scope ends", 1, refreshCount)
    }

    @Test
    fun `a continuous stream advances state during the burst, not only once it goes quiet`() = runTest {
        val events = MutableSharedFlow<Unit>(extraBufferCapacity = 32)
        val active = MutableStateFlow(true)
        // `latestValue` advances independently; each refresh records what it saw.
        var latestValue = 0
        val seenByRefresh = mutableListOf<Int>()
        val (scope, job) = newSchedulerScope()
        ChangeRefreshScheduler(scope, events, active, refresh = { seenByRefresh.add(latestValue) })
        try {
            runCurrent()
            repeat(12) { // 12 * 250ms == 3s of a sustained sync burst, well under the 500ms rest
                latestValue++
                events.tryEmit(Unit)
                settle(250)
            }
            assertTrue(
                "a resetting debounce(500) would emit nothing while events keep arriving under 500ms apart -- " +
                    "this scheduler must have refreshed several times during the stream itself, not just at the end",
                seenByRefresh.size > 1,
            )

            settle(600)
            assertEquals(
                "the final refresh must reflect the very last event once the stream settles",
                latestValue,
                seenByRefresh.last(),
            )
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `a suspended relevance check does not make the scheduler miss later events`() = runTest {
        // The gateway's bus: replay 0, 8 extra slots, DROP_OLDEST -- a subscriber held in its
        // relevance check loses everything but the newest 8 unless the scheduler buffers itself.
        val bus = MutableSharedFlow<Int>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        val active = MutableStateFlow(true)
        val gate = CompletableDeferred<Unit>()
        val checked = mutableSetOf<Int>()
        var refreshCount = 0
        val job = Job(coroutineContext[Job])
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + job)
        ChangeRefreshScheduler(scope, bus, active, refresh = { refreshCount++ }, affects = { id -> checked += id; gate.await(); true })
        try {
            runCurrent()
            repeat(20) { bus.tryEmit(it) }
            gate.complete(Unit)
            runCurrent()

            assertEquals("every one of the 20 ids reached the relevance check", (0 until 20).toSet(), checked)
            assertTrue("and the burst refreshed", refreshCount >= 1)
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `an event the relevance check rejects refreshes nothing`() = runTest {
        val events = MutableSharedFlow<Int>(extraBufferCapacity = 4)
        val active = MutableStateFlow(true)
        var refreshCount = 0
        val (scope, job) = newSchedulerScope()
        ChangeRefreshScheduler(scope, events, active, refresh = { refreshCount++ }, affects = { it % 2 == 0 })
        try {
            runCurrent()
            events.tryEmit(1)
            runCurrent()
            assertEquals(0, refreshCount)

            events.tryEmit(2)
            runCurrent()
            assertEquals(1, refreshCount)
        } finally {
            job.cancel()
        }
    }
}
