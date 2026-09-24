package tv.jellybeam.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SeekMathTest {

    @Test
    fun `positive delta within bounds seeks forward`() {
        assertEquals(20_000L, SeekMath.clampSeekTarget(currentMs = 10_000L, deltaMs = 10_000L, durationMs = 60_000L))
    }

    @Test
    fun `negative delta within bounds seeks backward`() {
        assertEquals(0L, SeekMath.clampSeekTarget(currentMs = 10_000L, deltaMs = -10_000L, durationMs = 60_000L))
    }

    @Test
    fun `seeking past zero clamps to zero, never negative`() {
        assertEquals(0L, SeekMath.clampSeekTarget(currentMs = 5_000L, deltaMs = -10_000L, durationMs = 60_000L))
    }

    @Test
    fun `seeking past the known duration clamps to duration`() {
        assertEquals(60_000L, SeekMath.clampSeekTarget(currentMs = 55_000L, deltaMs = 10_000L, durationMs = 60_000L))
    }

    @Test
    fun `an unknown duration only enforces the lower bound`() {
        assertEquals(70_000L, SeekMath.clampSeekTarget(currentMs = 60_000L, deltaMs = 10_000L, durationMs = null))
        assertEquals(0L, SeekMath.clampSeekTarget(currentMs = 5_000L, deltaMs = -10_000L, durationMs = null))
    }

    @Test
    fun `a negative duration is treated as zero, not as no upper bound`() {
        assertEquals(0L, SeekMath.clampSeekTarget(currentMs = 5_000L, deltaMs = 10_000L, durationMs = -1L))
    }
}
