package tv.jellybeam.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** docs/31 §4.4: a remote's 0-100 volume and the stream's index scale map onto each other proportionally. */
class SystemVolumeTest {
    @Test
    fun `a percentage lands on the proportional stream index, rounded and clamped`() {
        assertEquals(0, volumeIndex(0, min = 0, max = 15))
        assertEquals(15, volumeIndex(100, min = 0, max = 15))
        assertEquals(6, volumeIndex(40, min = 0, max = 15))
        assertEquals(8, volumeIndex(50, min = 0, max = 15))
        assertEquals("a stream with a floor above zero", 1, volumeIndex(0, min = 1, max = 25))
        assertEquals(13, volumeIndex(50, min = 1, max = 25))
        assertEquals(15, volumeIndex(140, min = 0, max = 15))
        assertEquals(0, volumeIndex(-5, min = 0, max = 15))
    }

    @Test
    fun `a stream index reads back as a percentage`() {
        assertEquals(0, volumePercent(0, min = 0, max = 15))
        assertEquals(100, volumePercent(15, min = 0, max = 15))
        assertEquals(40, volumePercent(6, min = 0, max = 15))
        assertEquals(50, volumePercent(13, min = 1, max = 25))
        assertEquals("a degenerate scale reads full", 100, volumePercent(3, min = 3, max = 3))
    }
}
