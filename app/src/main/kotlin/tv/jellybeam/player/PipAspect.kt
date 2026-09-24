package tv.jellybeam.player

/**
 * Clamps a video's `(width, height)` into the aspect-ratio range Android's picture-in-picture
 * window accepts (docs/17-mini-player.md §2). No Android import, so it's plain-JVM-testable;
 * [PipController] is the one caller.
 */
internal object PipAspect {
    /** Android's documented PiP aspect-ratio ceiling; its reciprocal is the floor. */
    const val MAX_RATIO = 2.39f

    /**
     * Clamps [width]/[height] to Android's PiP aspect-ratio range. A non-positive dimension
     * (not yet known) falls back to 16:9; outside [MAX_RATIO] clamps to the 2.39:1/1:2.39 bounds.
     */
    fun clamp(width: Int, height: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 16 to 9

        val ratio = width.toFloat() / height.toFloat()
        return when {
            ratio > MAX_RATIO -> 239 to 100
            ratio < 1f / MAX_RATIO -> 100 to 239
            else -> width to height
        }
    }
}
