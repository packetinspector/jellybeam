package tv.jellybeam.player

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

/** [EndsClock] is pure Kotlin (docs/jellybeam-osd-handoff §4) -- exercised directly against fixed
 * instants/zones.
 */
class EndsClockTest {

    @Test
    fun `endsAtMillis at 1x adds the remaining time unscaled`() {
        val now = 1_000_000L
        assertEquals(1_060_000L, EndsClock.endsAtMillis(now, remainingMs = 60_000L, rate = 1f))
    }

    @Test
    fun `endsAtMillis at 1_5x finishes sooner than the unscaled remaining time`() {
        val now = 0L
        assertEquals(40_000L, EndsClock.endsAtMillis(now, remainingMs = 60_000L, rate = 1.5f))
    }

    @Test
    fun `endsAtMillis at 0_5x finishes later than the unscaled remaining time`() {
        val now = 0L
        assertEquals(120_000L, EndsClock.endsAtMillis(now, remainingMs = 60_000L, rate = 0.5f))
    }

    @Test
    fun `endsAtMillis treats a non-positive rate as 1x`() {
        val now = 0L
        assertEquals(60_000L, EndsClock.endsAtMillis(now, remainingMs = 60_000L, rate = 0f))
        assertEquals(60_000L, EndsClock.endsAtMillis(now, remainingMs = 60_000L, rate = -2f))
    }

    @Test
    fun `formatWallClock renders 24h with a leading zero`() {
        val millis = Instant.parse("2024-01-01T22:14:00Z").toEpochMilli()
        assertEquals("22:14", EndsClock.formatWallClock(millis, is24h = true, zone = ZoneOffset.UTC))
    }

    @Test
    fun `formatWallClock renders 12h with no AM-PM and no leading zero`() {
        val millis = Instant.parse("2024-01-01T10:14:00Z").toEpochMilli()
        assertEquals("10:14", EndsClock.formatWallClock(millis, is24h = false, zone = ZoneOffset.UTC))
    }

    @Test
    fun `formatWallClock 12h drops the leading zero for a single-digit hour`() {
        val millis = Instant.parse("2024-01-01T09:05:00Z").toEpochMilli()
        assertEquals("9:05", EndsClock.formatWallClock(millis, is24h = false, zone = ZoneOffset.UTC))
    }

    @Test
    fun `formatWallClock respects the supplied zone, not the system default`() {
        val millis = Instant.parse("2024-01-01T22:14:00Z").toEpochMilli()
        assertEquals("07:14", EndsClock.formatWallClock(millis, is24h = true, zone = ZoneId.of("+09:00")))
    }
}
