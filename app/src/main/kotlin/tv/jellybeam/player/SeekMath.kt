package tv.jellybeam.player

/** Seek-target arithmetic for the OSD's dpad left/right skip (GOAL item 3: "-10s"/"+10s"),
 * JVM-testable.
 */
object SeekMath {
    /** The default skip magnitude (10s) for a single dpad left/right press. */
    const val SKIP_MS: Long = 10_000L

    /** `currentMs + deltaMs` clamped to `[0, durationMs]`; a `null` durationMs (no report yet)
     * enforces only the lower bound.
     */
    fun clampSeekTarget(currentMs: Long, deltaMs: Long, durationMs: Long?): Long {
        val target = currentMs + deltaMs
        val upperBound = durationMs?.coerceAtLeast(0) ?: Long.MAX_VALUE
        return target.coerceIn(0L, upperBound)
    }
}
