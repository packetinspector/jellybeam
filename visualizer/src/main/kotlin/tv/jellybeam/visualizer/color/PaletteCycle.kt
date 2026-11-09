package tv.jellybeam.visualizer.color

/**
 * docs/30 §4 (E3): the LUT position offset, drifting at one cycle per 60 s (quiet) to one per 20 s (loud) and
 * stepping a quarter cycle on a phrase. Per frame the drift never exceeds 1/64 cycle; only a step jumps.
 */
class PaletteCycle {
    /** 0..1; add it to a texel's palette position and wrap. */
    var offset: Float = 0f
        private set

    /**
     * Advances [dtS] at [energy] (0..1). On [phraseTick] the offset steps by [STEP] when [limiter] admits it.
     * A step changes hue only, so its luminance request is 0; it costs a flash only when [stepIsRed]
     * (the engine asks `Palettes.stepIsRed` for the current family) and is skipped when none is left.
     */
    fun update(
        dtS: Float,
        energy: Float,
        phraseTick: Boolean,
        limiter: FlashLimiter,
        nowS: Float,
        stepIsRed: Boolean = false,
    ) {
        val speed = MIN_SPEED + (MAX_SPEED - MIN_SPEED) * energy.coerceIn(0f, 1f)
        var o = offset + (maxOf(dtS, 0f) * speed).coerceAtMost(MAX_FRAME_DRIFT)
        if (phraseTick && limiter.admit(nowS, 0f, stepIsRed) >= 1f) o += STEP
        if (o >= 1f) o -= 1f
        offset = o
    }

    companion object {
        const val STEP = 0.25f
        private const val MIN_SPEED = 1f / 60f
        private const val MAX_SPEED = 1f / 20f
        private const val MAX_FRAME_DRIFT = 1f / 64f
    }
}
