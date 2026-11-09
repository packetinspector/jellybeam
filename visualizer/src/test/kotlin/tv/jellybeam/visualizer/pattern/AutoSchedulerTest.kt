package tv.jellybeam.visualizer.pattern

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.jellybeam.visualizer.Features

/** docs/30 §9 U13 (E6): when patterns change by themselves, and the crossfade's length and cancel. */
class AutoSchedulerTest {
    private val dt = 1f / 60f

    /** Locked at 120 BPM: a beat tick every 30 frames. */
    private fun locked(frame: Int) = Features().apply {
        silent = false
        tempoLocked = true
        tempoBpm = 120f
        beatTick = frame % 30 == 0
    }

    private fun framesUntilFire(s: AutoScheduler, maxFrames: Int, features: (Int) -> Features): Int? {
        for (i in 1..maxFrames) if (s.update(features(i), dt)) return i
        return null
    }

    @Test
    fun firesOnTheSixtyFourthBeatWhileLocked() {
        val s = AutoScheduler()
        val at = framesUntilFire(s, 10_000) { locked(it) }
        assertEquals(64 * 30, at)
    }

    @Test
    fun firesAfterFortyFiveSecondsWithoutALock() {
        val s = AutoScheduler()
        val at = framesUntilFire(s, 10_000) { Features().apply { silent = false } }!!
        // The first frame at or past 45 s: within one frame, allowing float accumulation.
        assertEquals(45f, at * dt, dt * 1.5f)
    }

    @Test
    fun neverFiresDuringSilence() {
        val s = AutoScheduler()
        assertEquals(null, framesUntilFire(s, 60 * 120) { locked(it).apply { silent = true } })
        assertEquals(null, framesUntilFire(s, 60 * 120) { Features() })
    }

    @Test
    fun aManualSwitchRestartsTheCount() {
        val s = AutoScheduler()
        repeat(60 * 30) { s.update(locked(it + 1), dt) }
        s.reset()
        val at = framesUntilFire(s, 10_000) { locked(it) }
        assertEquals(64 * 30, at)
    }

    @Test
    fun theSettingOffNeverFires() {
        val s = AutoScheduler().apply { enabled = false }
        assertEquals(null, framesUntilFire(s, 60 * 600) { locked(it) })
        assertEquals(null, framesUntilFire(s, 60 * 600) { Features().apply { silent = false } })
    }

    @Test
    fun theCrossfadeLastsOnePointFiveSecondsAndACancelEndsItCleanly() {
        val c = Crossfade()
        assertFalse(c.active)
        c.start(Pattern.BARS, Pattern.RADIAL)
        var frames = 0
        while (!c.advance(dt)) frames++
        // 1.5 s at 60 fps is 90 frames; the completing frame is the 90th (±1).
        assertTrue("frames $frames", frames + 1 in 89..91)
        assertFalse(c.active)
        c.start(Pattern.RADIAL, Pattern.WATERFALL)
        c.advance(0.5f)
        assertTrue(c.active)
        c.cancel()
        assertFalse(c.active)
        assertEquals(1f, c.t, 0f)
        assertFalse(c.advance(dt))
    }

    @Test
    fun aPausedCrossfadeHolds() {
        val c = Crossfade()
        c.start(Pattern.BARS, Pattern.RADIAL)
        c.advance(0.3f)
        val t = c.t
        repeat(100) { c.advance(0f) }
        assertEquals(t, c.t, 0f)
    }
}
