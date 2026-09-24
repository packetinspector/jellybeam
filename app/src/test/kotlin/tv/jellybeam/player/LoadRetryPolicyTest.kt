package tv.jellybeam.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LoadRetryPolicyTest {
    private val transient = LoadRetryPolicy.FailureKind.TRANSIENT_NETWORK

    @Test
    fun `opening backoff remains 1s 2s 4s 8s`() {
        val delays = (1..4).map { attempt ->
            val decision = LoadRetryPolicy.decide(transient, attempt, elapsedMs = 0L)
            (decision as LoadRetryPolicy.Decision.Retry).delayMs
        }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L), delays)
    }

    @Test
    fun `attempt count reset cannot reset the wall clock budget`() {
        assertEquals(
            LoadRetryPolicy.Decision.Retry(2_000L),
            LoadRetryPolicy.decide(transient, attempt = 2, elapsedMs = 40_000L),
        )
        assertEquals(
            LoadRetryPolicy.Decision.GiveUp,
            LoadRetryPolicy.decide(transient, attempt = 2, elapsedMs = 90_000L),
        )
    }

    @Test
    fun `a retry that would reach the deadline is not started`() {
        assertEquals(
            LoadRetryPolicy.Decision.GiveUp,
            LoadRetryPolicy.decide(transient, attempt = 4, elapsedMs = 82_000L),
        )
    }

    @Test
    fun `permanent failure gives up immediately`() {
        assertEquals(
            LoadRetryPolicy.Decision.GiveUp,
            LoadRetryPolicy.decide(LoadRetryPolicy.FailureKind.PERMANENT, attempt = 1, elapsedMs = 0L),
        )
    }

    @Test
    fun `non-network failure passes through`() {
        assertEquals(
            LoadRetryPolicy.Decision.Passthrough,
            LoadRetryPolicy.decide(LoadRetryPolicy.FailureKind.PASSTHROUGH, attempt = 1, elapsedMs = 0L),
        )
    }

    @Test
    fun `temporary HTTP statuses retry and permanent client statuses do not`() {
        listOf(408, 425, 429, 500, 502, 503, 504).forEach { status ->
            assertEquals("status $status", transient, LoadRetryPolicy.classifyHttpResponseCode(status))
        }
        listOf(400, 401, 403, 404, 416, 501).forEach { status ->
            assertEquals(
                "status $status",
                LoadRetryPolicy.FailureKind.PERMANENT,
                LoadRetryPolicy.classifyHttpResponseCode(status),
            )
        }
    }

    @Test
    fun `tracker uses monotonic task time independently of Media3 error count`() {
        var nowMs = 1_000L
        val tracker = LoadRetryBudgetTracker(nowMs = { nowMs })

        assertEquals(0L, tracker.elapsedMsForError(loadTaskId = 7L))
        repeat(4) { cycle ->
            nowMs += 22_000L // 20s read timeout + the reset attempt's 2s delay
            assertEquals((cycle + 1L) * 22_000L, tracker.elapsedMsForError(loadTaskId = 7L))
        }
        nowMs += 2_000L
        assertEquals(90_000L, tracker.elapsedMsForError(loadTaskId = 7L))
    }

    @Test
    fun `tracker keeps independent deadlines for concurrent load tasks`() {
        var nowMs = 0L
        val tracker = LoadRetryBudgetTracker(nowMs = { nowMs })

        assertEquals(0L, tracker.elapsedMsForError(loadTaskId = 1L))
        nowMs = 10_000L
        assertEquals(0L, tracker.elapsedMsForError(loadTaskId = 2L))
        nowMs = 20_000L
        assertEquals(20_000L, tracker.elapsedMsForError(loadTaskId = 1L))
        assertEquals(10_000L, tracker.elapsedMsForError(loadTaskId = 2L))
    }

    @Test
    fun `concluding a task clears its old deadline`() {
        var nowMs = 0L
        val tracker = LoadRetryBudgetTracker(nowMs = { nowMs })
        tracker.elapsedMsForError(loadTaskId = 3L)
        nowMs = 30_000L
        tracker.conclude(loadTaskId = 3L)

        assertEquals(0L, tracker.elapsedMsForError(loadTaskId = 3L))
    }

    @Test
    fun `sustained error-free interval starts a fresh outage episode`() {
        var nowMs = 0L
        val tracker = LoadRetryBudgetTracker(nowMs = { nowMs })
        tracker.elapsedMsForError(loadTaskId = 9L)
        nowMs = LoadRetryPolicy.RECOVERY_RESET_AFTER_MS - 1L
        assertEquals(nowMs, tracker.elapsedMsForError(loadTaskId = 9L))

        nowMs += LoadRetryPolicy.RECOVERY_RESET_AFTER_MS
        assertEquals(0L, tracker.elapsedMsForError(loadTaskId = 9L))
    }

    @Test
    fun `minimum retry threshold still covers the no-progress opening schedule`() {
        assertEquals(13, LoadRetryPolicy.MINIMUM_LOADABLE_RETRY_COUNT)
        assertTrue(LoadRetryPolicy.MINIMUM_LOADABLE_RETRY_COUNT > LoadRetryPolicy.BACKOFF_SCHEDULE_MS.size)
    }

    @Test
    fun `invalid inputs throw`() {
        try {
            LoadRetryPolicy.decide(transient, attempt = 0, elapsedMs = 0L)
            fail("expected attempt guard")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
        try {
            LoadRetryPolicy.decide(transient, attempt = 1, elapsedMs = -1L)
            fail("expected elapsed guard")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }
}
