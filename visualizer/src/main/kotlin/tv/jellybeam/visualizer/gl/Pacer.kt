package tv.jellybeam.visualizer.gl

/**
 * docs/30 §6 (D7): which vsyncs render. 60 fps on every vsync; after [MISSES_TO_HALVE] consecutive
 * missed vsyncs it renders every other one for the rest of the session. Paused with a settled frame
 * on screen it asks for no vsync at all.
 */
class Pacer(private val vsyncNanos: Long = DEFAULT_VSYNC_NANOS) {
    var halved = false
        private set

    private var lastFrameNanos = 0L
    private var consecutiveMisses = 0
    private var skipNext = false

    /** Vsync at [frameNanos]: true when this one renders. [slowHook] counts it as missed (T22's forced slow frame). */
    fun onVsync(frameNanos: Long, slowHook: Boolean = false): Boolean {
        val gap = if (lastFrameNanos == 0L) vsyncNanos else frameNanos - lastFrameNanos
        lastFrameNanos = frameNanos
        if (!halved) {
            val missed = slowHook || gap > vsyncNanos * 3 / 2
            consecutiveMisses = if (missed) consecutiveMisses + 1 else 0
            if (consecutiveMisses >= MISSES_TO_HALVE) {
                halved = true
                skipNext = true
            }
            return true
        }
        if (skipNext) {
            skipNext = false
            return false
        }
        skipNext = true
        return true
    }

    /** True while another vsync is wanted: playing, or paused with the last frame not yet drawn. */
    fun wantsFrames(running: Boolean, frozenFrameDrawn: Boolean): Boolean = running || !frozenFrameDrawn

    /** A stop (pause or idle) breaks the vsync chain; the next gap is not a miss. */
    fun onStopped() {
        lastFrameNanos = 0L
        consecutiveMisses = 0
        skipNext = false
    }

    companion object {
        const val DEFAULT_VSYNC_NANOS = 16_666_667L
        const val MISSES_TO_HALVE = 3
    }
}
