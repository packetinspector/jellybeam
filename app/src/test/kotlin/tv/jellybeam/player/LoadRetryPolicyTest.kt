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
        for (transcode in listOf(false, true)) {
            listOf(408, 425, 429).forEach { status ->
                assertEquals("status $status transcode=$transcode", transient, LoadRetryPolicy.classifyHttpResponseCode(status, transcode))
            }
            listOf(400, 401, 403, 404, 416, 501).forEach { status ->
                assertEquals(
                    "status $status transcode=$transcode",
                    LoadRetryPolicy.FailureKind.PERMANENT,
                    LoadRetryPolicy.classifyHttpResponseCode(status, transcode),
                )
            }
        }
    }

    @Test
    fun `every retryable 5xx is a server failure on a transcode, unless a 503 names its wait, and transient on direct play`() {
        for (status in listOf(500, 502, 503, 504)) {
            assertEquals("$status", LoadRetryPolicy.FailureKind.SERVER_FAILURE, LoadRetryPolicy.classifyHttpResponseCode(status, transcode = true))
            assertEquals("$status", transient, LoadRetryPolicy.classifyHttpResponseCode(status, transcode = false))
            assertEquals("$status", transient, LoadRetryPolicy.classifyHttpResponseCode(status, transcode = false, retryAfter = true))
        }
        assertEquals(transient, LoadRetryPolicy.classifyHttpResponseCode(503, transcode = true, retryAfter = true))
        assertEquals(LoadRetryPolicy.FailureKind.SERVER_FAILURE, LoadRetryPolicy.classifyHttpResponseCode(502, transcode = true, retryAfter = true))
    }

    @Test
    fun `a server failure retries once after 2s then gives up whatever the elapsed time`() {
        val serverFailure = LoadRetryPolicy.FailureKind.SERVER_FAILURE
        for (elapsedMs in listOf(0L, 1_000L, 30_000L)) {
            assertEquals("elapsed $elapsedMs", LoadRetryPolicy.Decision.Retry(2_000L), LoadRetryPolicy.decide(serverFailure, 1, elapsedMs))
            for (attempt in 2..6) {
                assertEquals(
                    "attempt $attempt elapsed $elapsedMs",
                    LoadRetryPolicy.Decision.GiveUp,
                    LoadRetryPolicy.decide(serverFailure, attempt, elapsedMs),
                )
            }
        }
    }

    @Test
    fun `a direct play 500 keeps the 90s window`() {
        val kind = LoadRetryPolicy.classifyHttpResponseCode(500, transcode = false)
        assertEquals(LoadRetryPolicy.Decision.Retry(8_000L), LoadRetryPolicy.decide(kind, attempt = 6, elapsedMs = 60_000L))
        assertEquals(LoadRetryPolicy.Decision.GiveUp, LoadRetryPolicy.decide(kind, attempt = 6, elapsedMs = 90_000L))
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
    fun `a still-retrying load never surfaces - only a give-up does`() {
        assertEquals(Int.MAX_VALUE, LoadRetryPolicy.MINIMUM_LOADABLE_RETRY_COUNT)
        assertEquals(Int.MAX_VALUE, NetworkAwareLoadErrorHandlingPolicy().getMinimumLoadableRetryCount(androidx.media3.common.C.DATA_TYPE_MEDIA))
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
