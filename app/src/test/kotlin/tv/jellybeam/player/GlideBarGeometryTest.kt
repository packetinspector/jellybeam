package tv.jellybeam.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlideBarGeometryTest {

    private val durationMs = 7_200_000L // 2h

    @Test
    fun `null duration yields no fractions`() {
        assertNull(GlideBarGeometry.fractions(positionMs = 10_000L, targetMs = 20_000L, durationMs = 0L))
        assertNull(GlideBarGeometry.fractions(positionMs = 10_000L, targetMs = 20_000L, durationMs = -1L))
    }

    @Test
    fun `band spans true position to target when gliding forward`() {
        val fractions = GlideBarGeometry.fractions(positionMs = 1_800_000L, targetMs = 3_600_000L, durationMs = durationMs)!!
        assertEquals(0.25f, fractions.played, 1e-6f)
        assertEquals(0.5f, fractions.target, 1e-6f)
        assertEquals(0.25f, fractions.bandStart, 1e-6f)
        assertEquals(0.5f, fractions.bandEnd, 1e-6f)
    }

    @Test
    fun `band spans target to true position when gliding backward`() {
        val fractions = GlideBarGeometry.fractions(positionMs = 3_600_000L, targetMs = 1_800_000L, durationMs = durationMs)!!
        assertEquals(0.5f, fractions.played, 1e-6f)
        assertEquals(0.25f, fractions.target, 1e-6f)
        // Ordering never flips: bandStart <= bandEnd regardless of direction.
        assertEquals(0.25f, fractions.bandStart, 1e-6f)
        assertEquals(0.5f, fractions.bandEnd, 1e-6f)
    }

    @Test
    fun `fractions clamp into 0 to 1 at either traversal boundary`() {
        val start = GlideBarGeometry.fractions(positionMs = 100_000L, targetMs = 0L, durationMs = durationMs)!!
        assertEquals(0f, start.target, 1e-6f)
        assertEquals(0f, start.bandStart, 1e-6f)

        val end = GlideBarGeometry.fractions(positionMs = durationMs - 100_000L, targetMs = durationMs, durationMs = durationMs)!!
        assertEquals(1f, end.target, 1e-6f)
        assertEquals(1f, end.bandEnd, 1e-6f)

        // Defensive: an out-of-range input (beyond duration) never produces a fraction outside [0, 1].
        val overshoot = GlideBarGeometry.fractions(positionMs = durationMs, targetMs = durationMs + 1_000_000L, durationMs = durationMs)!!
        assertTrue(overshoot.target in 0f..1f)
    }
}
