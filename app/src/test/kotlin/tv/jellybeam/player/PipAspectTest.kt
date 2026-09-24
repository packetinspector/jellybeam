package tv.jellybeam.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PipAspectTest {

    @Test
    fun `16 by 9 passes through unchanged`() {
        assertEquals(1920 to 1080, PipAspect.clamp(1920, 1080))
    }

    @Test
    fun `4 by 3 passes through unchanged`() {
        assertEquals(1024 to 768, PipAspect.clamp(1024, 768))
    }

    @Test
    fun `21 by 9 is within range and passes through unchanged`() {
        assertEquals(21 to 9, PipAspect.clamp(21, 9))
    }

    @Test
    fun `a ratio wider than 2 point 39 clamps to the 239 by 100 ceiling`() {
        assertEquals(239 to 100, PipAspect.clamp(2400, 1000))
    }

    @Test
    fun `a ratio of exactly 2 point 39 passes through unchanged`() {
        assertEquals(2_390_000 to 1_000_000, PipAspect.clamp(2_390_000, 1_000_000))
    }

    @Test
    fun `a ratio narrower than 1 over 2 point 39 clamps to the 100 by 239 floor`() {
        assertEquals(100 to 239, PipAspect.clamp(100, 300))
    }

    @Test
    fun `a ratio of exactly 1 over 2 point 39 passes through unchanged`() {
        assertEquals(1_000_000 to 2_390_000, PipAspect.clamp(1_000_000, 2_390_000))
    }

    @Test
    fun `zero width falls back to 16 by 9`() {
        assertEquals(16 to 9, PipAspect.clamp(0, 1080))
    }

    @Test
    fun `zero height falls back to 16 by 9`() {
        assertEquals(16 to 9, PipAspect.clamp(1920, 0))
    }

    @Test
    fun `negative width falls back to 16 by 9`() {
        assertEquals(16 to 9, PipAspect.clamp(-1920, 1080))
    }

    @Test
    fun `negative height falls back to 16 by 9`() {
        assertEquals(16 to 9, PipAspect.clamp(1920, -1080))
    }
}
