package tv.jellybeam.visualizer.gl

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** docs/30 §9: counters and hooks the lab's instrumented tests read; plain field reads, no work when unused. */
object VizDebug {
    /** EGL contexts alive across every engine (T23 leak check). */
    val liveContexts = AtomicInteger()

    /** GL threads alive across every engine (T23, T27). */
    val liveThreads = AtomicInteger()

    /** GPU bytes allocated by live renderers (L3: ≤ 1 MB per engine). */
    val gpuBytes = AtomicLong()

    /** Automatic transitions started, and the timing index at the latest (docs/30 §9). */
    val autoStarts = AtomicLong()

    @Volatile
    var autoStartTiming = 0L

    /** Frames rendered across every engine (T22). */
    val framesRendered = AtomicLong()

    /** Nanoseconds from the last wake (a paused source running again) to the vsync callback that served it (T22). */
    @Volatile
    var wakeLatencyNanos = -1L

    /** Disables every flash guard (limiter, strobe admit) while set: the control run that shows the flash test can fail (docs/30 §9). */
    @Volatile
    var limiterOff = false

    /** Counts every vsync as missed while set (T22's forced slow frame). */
    @Volatile
    var slowFrameHook = false

    /** Non-empty compile/link logs from the last engine start, by shader path (T19). */
    @Volatile
    var shaderLogs: Map<String, String> = emptyMap()

    /**
     * Per-frame CPU time of analysis and of GL submission, and the wait for the flash limiter's stats read
     * (GPU time, not in submission), nanoseconds, for the last [TIMING_FRAMES] frames (T24).
     */
    const val TIMING_FRAMES = 2048
    val analyzeNanos = LongArray(TIMING_FRAMES)
    val submitNanos = LongArray(TIMING_FRAMES)
    val statsWaitNanos = LongArray(TIMING_FRAMES)
    val timingCount = AtomicLong()
}
