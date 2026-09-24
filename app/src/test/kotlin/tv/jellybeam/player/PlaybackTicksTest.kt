package tv.jellybeam.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackTicksTest {

    @Test
    fun `ticksToMs converts Jellyfin 100ns ticks to milliseconds`() {
        assertEquals(1_000L, PlaybackTicks.ticksToMs(10_000_000L))
        assertEquals(1_500L, PlaybackTicks.ticksToMs(15_000_000L))
        assertEquals(0L, PlaybackTicks.ticksToMs(0L))
    }

    @Test
    fun `ticksToMs truncates a sub-millisecond remainder`() {
        // 10_000 ticks per ms -- 19_999 ticks is 1.9999ms, truncates to 1ms.
        assertEquals(1L, PlaybackTicks.ticksToMs(19_999L))
    }

    @Test
    fun `msToTicks converts milliseconds to Jellyfin ticks`() {
        assertEquals(10_000_000L, PlaybackTicks.msToTicks(1_000L))
        assertEquals(0L, PlaybackTicks.msToTicks(0L))
    }

    @Test
    fun `ticksToMs and msToTicks round-trip for whole-millisecond values`() {
        val ms = 123_456L
        assertEquals(ms, PlaybackTicks.ticksToMs(PlaybackTicks.msToTicks(ms)))
    }
}
