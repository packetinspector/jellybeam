package tv.jellybeam.player

import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NetworkAwareLoadErrorHandlingPolicyTest {
    private var nowMs = 0L
    private val policy = NetworkAwareLoadErrorHandlingPolicy(nowMs = { nowMs })
    /** android.jar stubs build no [android.net.Uri], so no DataSpec can exist here; a platform-typed null
     * (and Uri) passes Media3's parameters, and the policy never reads it. */
    private val noSpec = DataSpec::class.java.cast(null)
    private val noUri = android.net.Uri::class.java.cast(null)

    private fun httpError(
        status: Int,
        errorCount: Int,
        loadTaskId: Long = 1L,
        headers: Map<String, List<String>> = emptyMap(),
    ) = LoadErrorHandlingPolicy.LoadErrorInfo(
        LoadEventInfo(loadTaskId, noSpec, noUri, emptyMap(), 0L, 0L, 0L),
        MediaLoadData(C.DATA_TYPE_MEDIA),
        HttpDataSource.InvalidResponseCodeException(status, null, null, headers, noSpec, ByteArray(0)),
        errorCount,
    )

    /** Two variants left, so Media3's default would exclude one. */
    private val twoTracks = LoadErrorHandlingPolicy.FallbackOptions(1, 0, 2, 0)

    @Test
    fun `a transcode 500 retries once then gives up whatever the elapsed time and is not exhaustion`() {
        policy.resetForNewPlayback(transcode = true)
        assertEquals(2_000L, policy.getRetryDelayMsFor(httpError(500, errorCount = 1)))
        nowMs = 2_000L
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(httpError(500, errorCount = 2)))
        assertFalse(policy.hasExhaustedRetryBudget())
    }

    @Test
    fun `a transcode 502, 503 or 504 retries once then gives up, as a proxy relays ffmpeg's failure`() {
        policy.resetForNewPlayback(transcode = true)
        for (status in listOf(502, 503, 504)) {
            assertEquals("$status", 2_000L, policy.getRetryDelayMsFor(httpError(status, errorCount = 1, loadTaskId = status.toLong())))
            assertEquals("$status", C.TIME_UNSET, policy.getRetryDelayMsFor(httpError(status, errorCount = 2, loadTaskId = status.toLong())))
        }
        assertFalse(policy.hasExhaustedRetryBudget())
    }

    @Test
    fun `a direct play 500 keeps retrying inside the 90s window`() {
        policy.resetForNewPlayback(transcode = false)
        (1..6).forEach { attempt ->
            assertTrue("attempt $attempt", policy.getRetryDelayMsFor(httpError(500, attempt)) > 0L)
            nowMs += 10_000L
        }
    }

    @Test
    fun `a transcode 503 with Retry-After keeps the time budget and exhausts it`() {
        policy.resetForNewPlayback(transcode = true)
        val wait = mapOf("retry-after" to listOf("5"))
        assertEquals(1_000L, policy.getRetryDelayMsFor(httpError(503, errorCount = 1, headers = wait)))
        var attempt = 2
        var delayMs = policy.getRetryDelayMsFor(httpError(503, errorCount = attempt, headers = wait))
        assertEquals(2_000L, delayMs)
        while (delayMs != C.TIME_UNSET) {
            nowMs += delayMs
            delayMs = policy.getRetryDelayMsFor(httpError(503, errorCount = ++attempt, headers = wait))
        }
        assertTrue("gave up at ${nowMs}ms", nowMs >= 80_000L)
        assertTrue(policy.hasExhaustedRetryBudget())
    }

    @Test
    fun `a transcode 500 excludes no variant and a direct play 500 keeps the default fallback`() {
        policy.resetForNewPlayback(transcode = true)
        assertNull(policy.getFallbackSelectionFor(twoTracks, httpError(500, errorCount = 1)))
        policy.resetForNewPlayback(transcode = false)
        assertNotNull(policy.getFallbackSelectionFor(twoTracks, httpError(500, errorCount = 1)))
    }

    @Test
    fun `a held out-of-media load retries locally without end, never exhausts and never excludes a variant`() {
        policy.resetForNewPlayback(transcode = true)
        fun held(errorCount: Int) = LoadErrorHandlingPolicy.LoadErrorInfo(
            LoadEventInfo(1L, noSpec, noUri, emptyMap(), 0L, 0L, 0L),
            MediaLoadData(C.DATA_TYPE_MEDIA),
            OutOfMediaException(),
            errorCount,
        )
        for (attempt in 1..40) {
            nowMs += LoadRetryPolicy.OUT_OF_MEDIA_HOLD_MS
            assertEquals(LoadRetryPolicy.OUT_OF_MEDIA_HOLD_MS, policy.getRetryDelayMsFor(held(attempt)))
        }
        assertFalse(policy.hasExhaustedRetryBudget())
        assertNull(policy.getFallbackSelectionFor(twoTracks, held(1)))
    }
}
