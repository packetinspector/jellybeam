package tv.jellybeam.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekGuardTest {
    @Test
    fun `a seekable file seeks and an unseekable one raises the notice`() {
        assertEquals(SeekRoute.SEEK, SeekGuard.route(Seekability.SEEKABLE))
        assertEquals(SeekRoute.NOTICE, SeekGuard.route(Seekability.UNSEEKABLE))
        assertEquals(SeekRoute.DROP, SeekGuard.route(Seekability.UNKNOWN))
    }

    @Test
    fun `skip pills and auto-skip are suppressed on an unseekable file`() {
        for (decision in SegmentDecision.entries) {
            assertEquals(decision, SeekGuard.segmentDecision(decision, Seekability.SEEKABLE))
            assertEquals(SegmentDecision.NOTHING, SeekGuard.segmentDecision(decision, Seekability.UNSEEKABLE))
            assertEquals(SegmentDecision.NOTHING, SeekGuard.segmentDecision(decision, Seekability.UNKNOWN))
        }
    }

    @Test
    fun `the chapters button needs chapters and a seekable file`() {
        assertTrue(SeekGuard.chaptersButtonVisible(hasChapters = true, Seekability.SEEKABLE))
        assertTrue(SeekGuard.chaptersButtonVisible(hasChapters = true, Seekability.UNKNOWN))
        assertFalse(SeekGuard.chaptersButtonVisible(hasChapters = true, Seekability.UNSEEKABLE))
        assertFalse(SeekGuard.chaptersButtonVisible(hasChapters = false, Seekability.SEEKABLE))
        assertTrue(SeekGuard.skipButtonsDimmed(Seekability.UNSEEKABLE))
        assertFalse(SeekGuard.skipButtonsDimmed(Seekability.UNKNOWN))
    }

    @Test
    fun `the notice lives three seconds`() {
        assertEquals(3_000L, SeekGuard.NOTICE_MS)
    }

    @Test
    fun `a reconnect on an unseekable file restarts instead of seeking`() {
        assertEquals(0L, SeekGuard.recoveryPositionTicks(Seekability.UNSEEKABLE, 42L))
        assertEquals(42L, SeekGuard.recoveryPositionTicks(Seekability.SEEKABLE, 42L))
        assertEquals(42L, SeekGuard.recoveryPositionTicks(Seekability.UNKNOWN, 42L))
    }

}
