package tv.jellybeam.ui.settings

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AboutFormattingTest {

    // ---- formatBytes -------------------------------------------------------

    @Test
    fun `formatBytes stays plain bytes below 1024`() {
        assertEquals("0 B", AboutFormatting.formatBytes(0))
        assertEquals("512 B", AboutFormatting.formatBytes(512))
        assertEquals("1023 B", AboutFormatting.formatBytes(1023))
    }

    @Test
    fun `formatBytes switches to one decimal at 1024`() {
        assertEquals("1.0 KB", AboutFormatting.formatBytes(1024))
        assertEquals("1.5 KB", AboutFormatting.formatBytes(1536))
    }

    @Test
    fun `formatBytes steps through MB and GB`() {
        assertEquals("1.0 MB", AboutFormatting.formatBytes(1024L * 1024))
        assertEquals("1.2 MB", AboutFormatting.formatBytes((1024L * 1024 * 1.2).toLong()))
        assertEquals("1.0 GB", AboutFormatting.formatBytes(1024L * 1024 * 1024))
    }

    // ---- formatSyncInstant ---------------------------------------------------

    private val now = 10_000_000L

    @Test
    fun `formatSyncInstant is Just now under one minute`() {
        assertEquals("Just now", AboutFormatting.formatSyncInstant(now - 5_000, now))
        assertEquals("Just now", AboutFormatting.formatSyncInstant(now - 59_000, now))
    }

    @Test
    fun `formatSyncInstant counts minutes under one hour`() {
        assertEquals("1 min ago", AboutFormatting.formatSyncInstant(now - 60_000, now))
        assertEquals("59 min ago", AboutFormatting.formatSyncInstant(now - 3_599_000, now))
    }

    @Test
    fun `formatSyncInstant counts hours under one day`() {
        assertEquals("1 h ago", AboutFormatting.formatSyncInstant(now - 3_600_000, now))
        assertEquals("23 h ago", AboutFormatting.formatSyncInstant(now - 86_399_000, now))
    }

    @Test
    fun `formatSyncInstant falls back to a date at one day`() {
        val epochMs = now - 86_400_000
        val expected = Instant.ofEpochMilli(epochMs)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.US))
        assertEquals(expected, AboutFormatting.formatSyncInstant(epochMs, now))
    }

    // ---- parseRfc3339Millis --------------------------------------------------

    @Test
    fun `parseRfc3339Millis parses a valid timestamp`() {
        val expected = OffsetDateTime.parse("2024-01-15T10:30:00Z").toInstant().toEpochMilli()
        assertEquals(expected, AboutFormatting.parseRfc3339Millis("2024-01-15T10:30:00Z"))
    }

    @Test
    fun `parseRfc3339Millis returns null on a bad timestamp`() {
        assertNull(AboutFormatting.parseRfc3339Millis("not-a-date"))
    }

    // ---- hostOf ----------------------------------------------------------------

    @Test
    fun `hostOf strips the scheme and path`() {
        assertEquals("jellyfin.example.test", AboutFormatting.hostOf("https://jellyfin.example.test/web/index.html"))
    }

    @Test
    fun `hostOf keeps a non-default port`() {
        assertEquals("jellyfin.example.test:8096", AboutFormatting.hostOf("http://jellyfin.example.test:8096"))
    }

    @Test
    fun `hostOf drops the scheme's default port`() {
        assertEquals("jellyfin.example.test", AboutFormatting.hostOf("https://jellyfin.example.test:443"))
        assertEquals("jellyfin.example.test", AboutFormatting.hostOf("http://jellyfin.example.test:80"))
    }

    @Test
    fun `hostOf returns null on unparseable or hostless input`() {
        assertNull(AboutFormatting.hostOf("not a url"))
        assertNull(AboutFormatting.hostOf("jellyfin.example.test"))
    }

    @Test
    fun `tile numbers keep full size until they outgrow the tile, then shrink to fit`() {
        assertEquals(23f, AboutFormatting.fitMonoFontSp(chars = 5, availableSp = 82f, maxSp = 23f, minSp = 11f), 0.001f)

        val millions = AboutFormatting.fitMonoFontSp(chars = "1,234,567".length, availableSp = 82f, maxSp = 23f, minSp = 11f)
        assertTrue(millions < 23f)
        assertTrue("1,234,567".length * 0.69f * millions <= 82f + 0.001f)

        assertEquals(11f, AboutFormatting.fitMonoFontSp(chars = 40, availableSp = 82f, maxSp = 23f, minSp = 11f), 0.001f)
    }
}
