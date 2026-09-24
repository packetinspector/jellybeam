package tv.jellybeam.ui.library

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingFormattingTest {

    // Fixed zone, not the JVM default, for deterministic results.
    private val zone: ZoneId = ZoneId.of("America/New_York")

    // ---- dateLine -----------------------------------------------------

    @Test
    fun `dateLine converts UTC to local time, crossing midnight into the previous day`() {
        // 02:00 UTC on Aug 31 is 22:00 EDT on Aug 30.
        val dateLine = RecordingFormatting.dateLine(
            premiereDate = "2026-08-31T02:00:00+00:00",
            zone = zone,
            today = LocalDate.of(2026, 1, 1),
        )
        assertEquals("Sun, Aug 30", dateLine)
    }

    @Test
    fun `dateLine uses the short weekday form within today's year`() {
        val dateLine = RecordingFormatting.dateLine(
            premiereDate = "2026-06-15T12:00:00+00:00",
            zone = zone,
            today = LocalDate.of(2026, 1, 1),
        )
        assertEquals("Mon, Jun 15", dateLine)
    }

    @Test
    fun `dateLine falls back to a year-qualified form outside today's year`() {
        val dateLine = RecordingFormatting.dateLine(
            premiereDate = "2025-06-15T12:00:00+00:00",
            zone = zone,
            today = LocalDate.of(2026, 1, 1),
        )
        assertEquals("Jun 15, 2025", dateLine)
    }

    @Test
    fun `dateLine is null for a null premiereDate`() {
        assertNull(RecordingFormatting.dateLine(null, zone, LocalDate.of(2026, 1, 1)))
    }

    @Test
    fun `dateLine is null for unparseable input, never throws`() {
        assertNull(RecordingFormatting.dateLine("not-a-date", zone, LocalDate.of(2026, 1, 1)))
    }

    // ---- timeLine -------------------------------------------------------

    @Test
    fun `timeLine renders 12-hour by default`() {
        // 02:00 UTC on Aug 31 is 22:00 EDT on Aug 30.
        val timeLine = RecordingFormatting.timeLine(
            premiereDate = "2026-08-31T02:00:00+00:00",
            zone = zone,
            use24h = false,
        )
        assertEquals("10:00 PM", timeLine)
    }

    @Test
    fun `timeLine renders 24-hour when requested`() {
        val timeLine = RecordingFormatting.timeLine(
            premiereDate = "2026-08-31T02:00:00+00:00",
            zone = zone,
            use24h = true,
        )
        assertEquals("22:00", timeLine)
    }

    @Test
    fun `timeLine converts noon UTC into the local morning`() {
        // 12:00 UTC is 08:00 EDT (UTC-4).
        assertEquals("8:00 AM", RecordingFormatting.timeLine("2026-06-15T12:00:00+00:00", zone, use24h = false))
        assertEquals("08:00", RecordingFormatting.timeLine("2026-06-15T12:00:00+00:00", zone, use24h = true))
    }

    @Test
    fun `timeLine is null for a null premiereDate`() {
        assertNull(RecordingFormatting.timeLine(null, zone, use24h = false))
    }

    @Test
    fun `timeLine is null for unparseable input, never throws`() {
        assertNull(RecordingFormatting.timeLine("garbage", zone, use24h = true))
    }
}
