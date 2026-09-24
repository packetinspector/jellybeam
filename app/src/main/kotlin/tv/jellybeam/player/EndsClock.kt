package tv.jellybeam.player

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Helpers behind the nerdy OSD's `ENDS` readout
 * (docs/jellybeam-osd-handoff/handoff/JELLYBEAM-TV-OSD-SPEC.md §4), JVM-testable against a fixed
 * `now`/zone.
 */
object EndsClock {
    /** Epoch millis playback finishes at, scaling [remainingMs] by `1 / rate`; a non-positive
     * [rate] is treated as `1f`.
     */
    fun endsAtMillis(nowMillis: Long, remainingMs: Long, rate: Float): Long {
        val effectiveRate = if (rate <= 0f) 1f else rate
        val scaledRemainingMs = (remainingMs.coerceAtLeast(0L) / effectiveRate).toLong()
        return nowMillis + scaledRemainingMs
    }

    private val FORMAT_24H = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

    /** No leading zero on the hour (`9:14`, not `09:14`), matching the spec's short 12h example. */
    private val FORMAT_12H = DateTimeFormatter.ofPattern("h:mm", Locale.US)

    /** Short wall-clock string in [zone]: `"22:14"` (24h) or `"10:14"` with no AM/PM (12h), per
     * spec §4.
     */
    fun formatWallClock(millis: Long, is24h: Boolean, zone: ZoneId): String {
        val time = Instant.ofEpochMilli(millis).atZone(zone).toLocalTime()
        val formatter = if (is24h) FORMAT_24H else FORMAT_12H
        return formatter.format(time)
    }
}
