package tv.jellybeam.ui.library

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import tv.jellybeam.i18n.ResourceUiStrings
import tv.jellybeam.ui.cards.testCard

class LibraryReadoutFormatTest {
    private val strings = ResourceUiStrings.default
    private val utc = ZoneId.of("UTC")
    private val us = Locale.US
    private val now = Instant.parse("2026-10-14T12:00:00Z")

    private fun ticks(minutes: Long) = minutes * 60L * 10_000_000L

    private fun addedDaysAgo(days: Long): String = relativeDay(strings, now.minusSeconds(days * 86_400).toString(), now, utc)!!

    // ---- lastPlayed ----------------------------------------------------------

    @Test
    fun `last played reads the relative day of the last start, Never without one`() {
        assertEquals("Yesterday", lastPlayedValue(strings, "2026-10-13T12:00:00Z", played = true, now = now, zone = utc))
        assertEquals("3 weeks ago", lastPlayedValue(strings, "2026-09-20T12:00:00Z", played = false, now = now, zone = utc))
        assertEquals("Never", lastPlayedValue(strings, null, played = false, now = now, zone = utc))
    }

    @Test
    fun `a title marked played with no date reads a dash, not Never`() {
        assertEquals("—", lastPlayedValue(strings, null, played = true, now = now, zone = utc))
    }

    @Test
    fun `last played sub is the state - watched, in progress, or none`() {
        assertEquals("watched", lastPlayedSub(strings, played = true, positionTicks = 0L))
        assertEquals("in progress", lastPlayedSub(strings, played = false, positionTicks = 1L))
        assertNull(lastPlayedSub(strings, played = false, positionTicks = 0L))
    }

    // ---- relativeDay -------------------------------------------------------

    @Test
    fun `relative day steps through days, weeks, months and years`() {
        assertEquals("Today", addedDaysAgo(0))
        assertEquals("Yesterday", addedDaysAgo(1))
        assertEquals("2 days ago", addedDaysAgo(2))
        assertEquals("13 days ago", addedDaysAgo(13))
        assertEquals("2 weeks ago", addedDaysAgo(14))
        assertEquals("8 weeks ago", addedDaysAgo(62))
        assertEquals("2 months ago", addedDaysAgo(63))
    }

    @Test
    fun `relative day counts calendar months below two years, then years`() {
        assertEquals("23 months ago", relativeDay(strings, "2024-11-14T12:00:00Z", now, utc))
        assertEquals("2 years ago", relativeDay(strings, "2024-10-14T12:00:00Z", now, utc))
        assertEquals("5 years ago", relativeDay(strings, "2021-01-01T00:00:00Z", now, utc))
    }

    @Test
    fun `relative day is absent when unparseable and a future date reads Today`() {
        assertNull(relativeDay(strings, null, now, utc))
        assertNull(relativeDay(strings, "garbage", now, utc))
        assertEquals("Today", relativeDay(strings, "2026-10-20T00:00:00Z", now, utc))
    }

    @Test
    fun `relative day compares local dates in the injected zone`() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        // 2026-10-13T20:00Z is Oct 14 05:00 in Tokyo, the same local day as now (Oct 14 21:00).
        assertEquals("Today", relativeDay(strings, "2026-10-13T20:00:00Z", now, tokyo))
        assertEquals("Yesterday", relativeDay(strings, "2026-10-13T20:00:00Z", now, utc))
    }

    // ---- addedAbsolute -----------------------------------------------------

    @Test
    fun `added absolute is the full local date, absent when unparseable`() {
        assertEquals("Mar 6, 2014", addedAbsolute("2014-03-06T23:30:00Z", utc, us))
        assertEquals("Mar 7, 2014", addedAbsolute("2014-03-06T23:30:00Z", ZoneId.of("Asia/Tokyo"), us))
        assertNull(addedAbsolute(null, utc, us))
        assertNull(addedAbsolute("garbage", utc, us))
    }

    // ---- endsSub -----------------------------------------------------------

    @Test
    fun `ends sub says if started now when fresh and what is left when in progress`() {
        assertEquals("if started now", endsSub(strings, ticks(112), 0))
        assertEquals("1h 4m left", endsSub(strings, ticks(112), ticks(48)))
    }

    @Test
    fun `ends sub is absent without a runtime`() {
        assertNull(endsSub(strings, null, 0))
        assertNull(endsSub(strings, 0L, 0))
    }

    // ---- readoutCard -------------------------------------------------------

    @Test
    fun `readout card is the rested movie as it now stands in the grid`() {
        val fresh = testCard(id = "a").copy(positionTicks = 2L)
        assertSame(fresh, readoutCard("a", listOf(testCard(id = "b"), fresh), isLoading = false))
    }

    @Test
    fun `readout card hides for nothing rested, loading, a vanished id, or a non-movie`() {
        val items = listOf(testCard(id = "a"), testCard(id = "s", itemType = "Series"))
        assertNull(readoutCard(null, items, isLoading = false))
        assertNull(readoutCard("a", items, isLoading = true))
        assertNull(readoutCard("gone", items, isLoading = false))
        assertNull(readoutCard("s", items, isLoading = false))
        assertNull(readoutCard("a", emptyList(), isLoading = false))
    }
}
