package tv.jellybeam.ui.detail

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import tv.jellybeam.player.EndsClock
import tv.jellybeam.player.PlaybackTicks

/**
 * Wall-clock time a title would finish if started now, from [positionTicks] on: `"11:24 PM"`, or
 * `"12:40 AM +1"` when it ends on a later local day. `null` without a positive runtime. The
 * remaining time is the literal runtime minus position, ignoring `played` (EndsClock clamps a
 * position past the end to now); [timeFormat] owns 12h/24h.
 */
fun endsAtLabel(
    runtimeTicks: Long?,
    positionTicks: Long,
    nowMs: Long,
    zone: ZoneId,
    timeFormat: (Long) -> String,
): String? {
    val runtime = runtimeTicks?.takeIf { it > 0L } ?: return null
    val remainingMs = PlaybackTicks.ticksToMs(runtime - positionTicks)
    val endMs = EndsClock.endsAtMillis(nowMs, remainingMs, 1f)
    val days = ChronoUnit.DAYS.between(
        Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate(),
        Instant.ofEpochMilli(endMs).atZone(zone).toLocalDate(),
    )
    val time = timeFormat(endMs)
    return if (days > 0L) "$time +$days" else time
}
