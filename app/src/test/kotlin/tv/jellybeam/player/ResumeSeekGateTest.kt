package tv.jellybeam.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * docs/18-playback-quality.md §5.2: [ResumeSeekGate] sequences the one extra `seekTo` a resume
 * load needs to land on a keyframe instead of decoding through every frame back to it, and gates
 * restoring normal [androidx.media3.exoplayer.SeekParameters] once it lands. Mirrors
 * [SeekSerializerTest]'s pure, hand-threaded-clock style; [PlayerHolder] itself needs a real
 * `ExoPlayer` so these three PlayerHolder task cases are exercised here against the gate directly.
 */
class ResumeSeekGateTest {

    @Test
    fun `resume issues exactly one seek after tracks change and restores on the first landing signal`() {
        val gate = ResumeSeekGate()
        gate.arm(generation = 1L, targetMs = 42_000L)

        assertEquals(42_000L, gate.onTracksChanged(1L))
        // A second onTracksChanged for the same item (Media3 can fire it more than once) must not
        // issue a second seek.
        assertNull(gate.onTracksChanged(1L))

        assertTrue("first landing signal should ask the caller to restore", gate.onLandingSignal(1L))
        // Whichever of firstFrame/STATE_READY fires second must not restore again.
        assertFalse(gate.onLandingSignal(1L))
    }

    @Test
    fun `play-from-start never arms, so nothing is issued or restored`() {
        val gate = ResumeSeekGate()
        gate.reset()

        assertNull(gate.onTracksChanged(1L))
        assertFalse(gate.onLandingSignal(1L))
    }

    @Test
    fun `a generation change before tracks-changed issues nothing for the stale generation`() {
        val gate = ResumeSeekGate()
        gate.arm(generation = 1L, targetMs = 10_000L)

        // A second load() landed before the first item's tracks-changed callback arrived: it either
        // rearms for the new generation or disarms outright, exactly as PlayerHolder.load() does.
        gate.arm(generation = 2L, targetMs = 20_000L)

        // The stale callback, still carrying the old generation, must not fire.
        assertNull(gate.onTracksChanged(1L))
        assertFalse(gate.onLandingSignal(1L))

        // The new generation's own callback still works normally.
        assertEquals(20_000L, gate.onTracksChanged(2L))
    }

    @Test
    fun `a generation change to a non-resume load disarms, so a late stale callback issues nothing`() {
        val gate = ResumeSeekGate()
        gate.arm(generation = 1L, targetMs = 10_000L)

        gate.reset() // PlayerHolder.load() for generation 2, play-from-start

        assertNull(gate.onTracksChanged(1L))
        assertNull(gate.onTracksChanged(2L))
        assertFalse(gate.onLandingSignal(2L))
    }
}
