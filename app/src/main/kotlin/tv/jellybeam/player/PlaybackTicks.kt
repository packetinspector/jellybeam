package tv.jellybeam.player

/** Jellyfin ticks (100ns units, 10_000_000/sec) <-> Media3 milliseconds, pinned by a JVM test. */
object PlaybackTicks {
    private const val TICKS_PER_MS = 10_000L

    /** Jellyfin ticks -> Media3 milliseconds, truncating (matches Jellyfin server-side integer
     * division).
     */
    fun ticksToMs(ticks: Long): Long = ticks / TICKS_PER_MS

    /** Media3 milliseconds -> Jellyfin ticks. */
    fun msToTicks(ms: Long): Long = ms * TICKS_PER_MS
}
