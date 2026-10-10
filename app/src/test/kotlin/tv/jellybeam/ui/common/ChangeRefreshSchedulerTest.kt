package tv.jellybeam.ui.common

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * docs/16-library-sort-filter.md §4.6, docs/17-mini-player.md §6, with a
 * virtual clock: refresh immediately, keep refreshing during a burst,
 * bound work while hidden, catch up on activation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChangeRefreshSchedulerTest {

    /** Two never-completing coroutines; a child [Job], cancelled explicitly, keeps them from
     * outliving `runTest`.
     */
    private fun CoroutineScope.newSchedulerScope(): Pair<CoroutineScope, Job> {
        val job = Job(coroutineContext[Job])
        return CoroutineScope(coroutineContext + job) to job
    }

    @Test
    fun `a single quiet event triggers exactly one refresh`() = runTest {
        val events = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
        val active = MutableStateFlow(true)
        var refreshCount = 0
        val (scope, job) = newSchedulerScope()
        ChangeRefreshScheduler(scope, events, active, refresh = { refreshCount++ })
        try {
            runCurrent()
            assertEquals("nothing has happened yet", 0, refreshCount)

            events.tryEmit(Unit)
            runCurrent()
            assertEquals("the leading event refreshes immediately, no debounce wait", 1, refreshCount)

            advanceTimeBy(5_000)
            runCurrent()
            assertEquals("no further event landed -- no further refresh", 1, refreshCount)
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `requestRefresh is served by the same loop, never overlapping an event refresh`() = runTest {
        val events = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
        val active = MutableStateFlow(true)
        val gate = CompletableDeferred<Unit>()
        var running = 0
        var maxRunning = 0
        var refreshCount = 0
        val (scope, job) = newSchedulerScope()
        val scheduler = ChangeRefreshScheduler(
            scope, events, active,
            refresh = {
                running++
                maxRunning = maxOf(maxRunning, running)
                refreshCount++
                if (refreshCount == 1) gate.await()
                running--
            },
        )
        try {
            scheduler.requestRefresh()
            runCurrent()
            assertEquals("the request runs like an event", 1, refreshCount)

            events.tryEmit(Unit)
            runCurrent()
            assertEquals("the event waits behind the held request", 1, refreshCount)

            gate.complete(Unit)
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals("then the event's own refresh runs", 2, refreshCount)
            assertEquals("one at a time", 1, maxRunning)
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `an explicit request ends the rest early but an event keeps it`() = runTest {
        val events = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
        val active = MutableStateFlow(true)
        var refreshCount = 0
        val (scope, job) = newSchedulerScope()
        val scheduler = ChangeRefreshScheduler(scope, events, active, refresh = { refreshCount++ })
        try {
            runCurrent()
            events.tryEmit(Unit)
            runCurrent()
            assertEquals(1, refreshCount)

            events.tryEmit(Unit)
            advanceTimeBy(100)
            runCurrent()
            assertEquals("an event during the rest waits it out", 1, refreshCount)

            scheduler.requestRefresh()
            runCurrent()
            assertEquals("an explicit ask cuts the rest", 2, refreshCount)
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `an ask during a refresh skips the rest entirely`() = runTest {
        val events = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
        val active = MutableStateFlow(true)
        val gate = CompletableDeferred<Unit>()
        var refreshCount = 0
        val (scope, job) = newSchedulerScope()
        val scheduler = ChangeRefreshScheduler(scope, events, active, refresh = { if (++refreshCount == 1) gate.await() })
        try {
            runCurrent()
            events.tryEmit(Unit)
            runCurrent()
            scheduler.requestRefresh()
            gate.complete(Unit)
            runCurrent()
            assertEquals("the ask made mid-refresh runs with no rest", 2, refreshCount)
        } finally {
            job.cancel()
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

            advanceTimeBy(1_000)
            events.tryEmit(Unit)
            runCurrent()
            assertEquals("the loop survived", 2, refreshCount)
        } finally {
            job.cancel()
        }
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
            repeat(12) { // 12 * 250ms == 3s of a sustained sync burst, well under the 500ms period
                latestValue++
                events.tryEmit(Unit)
                advanceTimeBy(250)
                runCurrent()
            }
            assertTrue(
                "a resetting debounce(500) would emit nothing while events keep arriving under 500ms apart -- " +
                    "this scheduler must have refreshed several times during the stream itself, not just at the end",
                seenByRefresh.size > 1,
            )

            // Let the trailing rest elapse so the last event's refresh lands.
            advanceTimeBy(600)
            runCurrent()
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
    fun `hidden events are bounded to about one refresh per hidden period, and activation catches up immediately`() = runTest {
        val events = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
        val active = MutableStateFlow(false)
        var refreshCount = 0
        val (scope, job) = newSchedulerScope()
        ChangeRefreshScheduler(scope, events, active, refresh = { refreshCount++ }, hiddenPeriodMs = 3_000L)
        try {
            runCurrent()
            repeat(40) { // a continuous burst while hidden, far more often than once per 3s
                events.tryEmit(Unit)
                advanceTimeBy(100)
                runCurrent()
            }
            // ~4s elapsed hidden: a leading refresh plus at most one more per 3s.
            assertTrue("hidden work must stay bounded to about one refresh per hiddenPeriodMs", refreshCount <= 2)

            val countBeforeActivation = refreshCount
            active.value = true
            runCurrent()
            assertEquals(
                "becoming active with a dirty flag must refresh right away, not wait out the rest of the hidden period",
                countBeforeActivation + 1,
                refreshCount,
            )
        } finally {
            job.cancel()
        }
    }
}
