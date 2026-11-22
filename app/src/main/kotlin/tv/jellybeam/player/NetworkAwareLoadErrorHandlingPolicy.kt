package tv.jellybeam.player

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Media3 adapter for [LoadRetryPolicy]. A per-load-task monotonic tracker owns the deadline;
 * Media3's resettable `errorCount` only shapes backoff. HTTP wrapper types are inspected here
 * because OkHttp wraps every open/read `IOException` in [HttpDataSource.HttpDataSourceException]
 * including permanent failures, so only a concrete transport cause or transient response code
 * gets the extended retry window.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NetworkAwareLoadErrorHandlingPolicy(
    private val fallback: LoadErrorHandlingPolicy = DefaultLoadErrorHandlingPolicy(),
    nowMs: () -> Long = { SystemClock.elapsedRealtime() },
) : LoadErrorHandlingPolicy {
    private val budgetTracker = LoadRetryBudgetTracker(nowMs)
    private val exhausted = AtomicBoolean(false)
    private val transcode = AtomicBoolean(false)

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val loadTaskId = loadErrorInfo.loadEventInfo.loadTaskId
        val failureKind = classifyFailure(loadErrorInfo.exception)

        if (failureKind == LoadRetryPolicy.FailureKind.TRANSIENT_NETWORK && exhausted.get()) {
            return C.TIME_UNSET
        }

        val elapsedMs = if (failureKind == LoadRetryPolicy.FailureKind.TRANSIENT_NETWORK) {
            budgetTracker.elapsedMsForError(loadTaskId)
        } else {
            budgetTracker.conclude(loadTaskId)
            0L
        }

        return when (val decision = LoadRetryPolicy.decide(failureKind, loadErrorInfo.errorCount, elapsedMs)) {
            is LoadRetryPolicy.Decision.Retry -> decision.delayMs
            LoadRetryPolicy.Decision.GiveUp -> {
                if (
                    failureKind == LoadRetryPolicy.FailureKind.TRANSIENT_NETWORK &&
                    loadErrorInfo.mediaLoadData.dataType.isPrimaryPlaybackMedia()
                ) {
                    exhausted.set(true)
                }
                budgetTracker.conclude(loadTaskId)
                C.TIME_UNSET
            }
            LoadRetryPolicy.Decision.Passthrough -> fallbackDelayWithoutWidening(loadErrorInfo)
        }
    }

    /** Media3 asks for this before it has an exception to classify, so the extended value applies
     * globally; [fallbackDelayWithoutWidening] compensates for passthrough failures.
     */
    override fun getMinimumLoadableRetryCount(dataType: Int): Int = LoadRetryPolicy.MINIMUM_LOADABLE_RETRY_COUNT

    override fun getFallbackSelectionFor(
        fallbackOptions: LoadErrorHandlingPolicy.FallbackOptions,
        loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo,
    ): LoadErrorHandlingPolicy.FallbackSelection? =
        // Excluding a transcode's variant only asks the same failing server for another ffmpeg.
        if (classifyFailure(loadErrorInfo.exception) == LoadRetryPolicy.FailureKind.SERVER_FAILURE) {
            null
        } else {
            fallback.getFallbackSelectionFor(fallbackOptions, loadErrorInfo)
        }

    override fun onLoadTaskConcluded(loadTaskId: Long) {
        budgetTracker.conclude(loadTaskId)
        fallback.onLoadTaskConcluded(loadTaskId)
    }

    /** Starts a new playback item with a fresh extended-recovery allowance; on a [transcode] a 500 is a server failure. */
    fun resetForNewPlayback(transcode: Boolean = false) {
        budgetTracker.reset()
        exhausted.set(false)
        this.transcode.set(transcode)
    }

    /** True after the load-level policy spent its complete recovery window; avoids stacking another
     * reconnect budget on top.
     */
    fun hasExhaustedRetryBudget(): Boolean = exhausted.get()

    private fun fallbackDelayWithoutWidening(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val fallbackDelayMs = fallback.getRetryDelayMsFor(loadErrorInfo)
        if (fallbackDelayMs == C.TIME_UNSET) return C.TIME_UNSET

        val fallbackRetryCount = fallback.getMinimumLoadableRetryCount(loadErrorInfo.mediaLoadData.dataType)
        return if (loadErrorInfo.errorCount > fallbackRetryCount) C.TIME_UNSET else fallbackDelayMs
    }

    private fun classifyFailure(error: Throwable): LoadRetryPolicy.FailureKind {
        if (causes(error).any { it is OutOfMediaException }) return LoadRetryPolicy.FailureKind.OUT_OF_MEDIA
        // Response status takes precedence over any incidental nested cause.
        httpResponseCode(error)?.let { return LoadRetryPolicy.classifyHttpResponseCode(it, transcode.get(), hasRetryAfter(error)) }
        if (ReconnectPolicy.isRecoverable(error)) return LoadRetryPolicy.FailureKind.TRANSIENT_NETWORK
        val sawHttpWrapper = causes(error).any { it is HttpDataSource.HttpDataSourceException }
        return if (sawHttpWrapper) LoadRetryPolicy.FailureKind.PERMANENT else LoadRetryPolicy.FailureKind.PASSTHROUGH
    }

    private companion object {

        fun Int.isPrimaryPlaybackMedia(): Boolean = this == C.DATA_TYPE_MEDIA || this == C.DATA_TYPE_MEDIA_PROGRESSIVE_LIVE
    }
}

private const val MAX_CAUSE_CHAIN_DEPTH = 16

/** [error] and its causes, bounded so a pathological cycle ends. */
internal fun causes(error: Throwable?): Sequence<Throwable> =
    generateSequence(error) { cause -> cause.cause?.takeIf { it !== cause } }.take(MAX_CAUSE_CHAIN_DEPTH)

/** The HTTP status a load failed with, if any; shared by the retry policy and the player's fatal message. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun httpResponseCode(error: Throwable?): Int? = invalidResponse(error)?.responseCode

private fun invalidResponse(error: Throwable?): HttpDataSource.InvalidResponseCodeException? =
    causes(error).filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()

/** Whether the failed response named its own wait (a Retry-After header). */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun hasRetryAfter(error: Throwable?): Boolean =
    invalidResponse(error)?.headerFields?.keys?.any { "Retry-After".equals(it, ignoreCase = true) } == true
