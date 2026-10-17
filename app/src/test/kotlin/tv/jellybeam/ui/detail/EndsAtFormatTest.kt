package tv.jellybeam.ui.detail

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EndsAtFormatTest {
    private val utc = ZoneId.of("UTC")
    private val h24 = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
    private val h12 = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

    private fun ticks(minutes: Long) = minutes * 60L * 10_000_000L

    private fun at(zone: ZoneId, local: String): Long =
        LocalDateTime.parse(local).atZone(zone).toInstant().toEpochMilli()

    private fun fmt(f: DateTimeFormatter, zone: ZoneId): (Long) -> String =
        { f.format(Instant.ofEpochMilli(it).atZone(zone)) }

    @Test
    fun `a fresh title ends a full runtime from now`() {
        val now = at(utc, "2026-08-29T21:32:00")
        assertEquals("23:24", endsAtLabel(ticks(112), 0, now, utc, fmt(h24, utc)))
    }

    @Test
    fun `a title in progress ends after only what remains`() {
        val now = at(utc, "2026-08-29T21:32:00")
        assertEquals("22:36", endsAtLabel(ticks(112), ticks(48), now, utc, fmt(h24, utc)))
    }

    @Test
    fun `a played title with position zero still reads the full runtime`() {
        val now = at(utc, "2026-08-29T20:00:00")
        assertEquals("21:52", endsAtLabel(ticks(112), 0, now, utc, fmt(h24, utc)))
    }

    @Test
    fun `a position past the runtime clamps to now`() {
        val now = at(utc, "2026-08-29T20:00:00")
        assertEquals("20:00", endsAtLabel(ticks(100), ticks(130), now, utc, fmt(h24, utc)))
    }

    @Test
    fun `no runtime or a zero runtime has no label`() {
        val now = at(utc, "2026-08-29T21:32:00")
        assertNull(endsAtLabel(null, 0, now, utc, fmt(h24, utc)))
        assertNull(endsAtLabel(0L, 0, now, utc, fmt(h24, utc)))
    }

    @Test
    fun `an end past local midnight carries the day offset`() {
        val now = at(utc, "2026-08-29T23:30:00")
        assertEquals("00:40 +1", endsAtLabel(ticks(70), 0, now, utc, fmt(h24, utc)))
        assertEquals("12:40 AM +1", endsAtLabel(ticks(70), 0, now, utc, fmt(h12, utc)))
    }

    @Test
    fun `a runtime spanning two midnights reads plus two`() {
        val now = at(utc, "2026-08-29T23:30:00")
        assertEquals("01:00 +2", endsAtLabel(ticks(25 * 60 + 30), 0, now, utc, fmt(h24, utc)))
    }

    @Test
    fun `the injected formatter decides 12h or 24h and AM or PM`() {
        val now = at(utc, "2026-08-29T21:32:00")
        assertEquals("11:24 PM", endsAtLabel(ticks(112), 0, now, utc, fmt(h12, utc)))
        assertEquals("23:24", endsAtLabel(ticks(112), 0, now, utc, fmt(h24, utc)))
    }

    @Test
    fun `the day offset follows the local zone, not UTC`() {
        val zone = ZoneId.of("America/New_York")
        val now = at(zone, "2026-08-29T21:00:00")
        assertEquals("23:30", endsAtLabel(ticks(150), 0, now, zone, fmt(h24, zone)))
        assertEquals("00:30 +1", endsAtLabel(ticks(210), 0, now, zone, fmt(h24, zone)))
    }

    @Test
    fun `a DST change inside the runtime shifts the wall clock but not the elapsed time`() {
        val zone = ZoneId.of("America/New_York")
        val now = at(zone, "2026-03-07T23:00:00")
        // The clocks jump 02:00 -> 03:00 on 2026-03-08, so 3 elapsed hours land on 03:00 local.
        assertEquals("03:00 +1", endsAtLabel(ticks(180), 0, now, zone, fmt(h24, zone)))
    }
}
