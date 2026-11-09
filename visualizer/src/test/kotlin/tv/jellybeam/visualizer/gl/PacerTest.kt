package tv.jellybeam.visualizer.gl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/30 §9 T22 (pure half): 60 fps, halving after three misses for the session, idle when settled. */
class PacerTest {
    private val v = Pacer.DEFAULT_VSYNC_NANOS

    @Test
    fun rendersEveryVsyncWhileOnTime() {
        val p = Pacer()
        var t = 0L
        repeat(600) { t += v; assertTrue(p.onVsync(t)) }
        assertFalse(p.halved)
    }

    @Test
    fun twoMissesDoNotHalveButThreeDo() {
        val p = Pacer()
        var t = v
        p.onVsync(t)
        repeat(2) { t += 2 * v; p.onVsync(t) }
        t += v
        p.onVsync(t)
        assertFalse(p.halved)
        repeat(3) { t += 2 * v; p.onVsync(t) }
        assertTrue(p.halved)
        val rendered = (1..60).count { t += v; p.onVsync(t) }
        assertEquals(30, rendered)
    }

    @Test
    fun slowHookCountsAsAMissAndHalvingLastsTheSession() {
        val p = Pacer()
        var t = 0L
        repeat(3) { t += v; p.onVsync(t, slowHook = true) }
        assertTrue(p.halved)
        p.onStopped()
        repeat(100) { t += v; p.onVsync(t) }
        assertTrue(p.halved)
    }

    @Test
    fun aTwentyFourHertzDisplayIsNotAStreamOfMisses() {
        val v24 = 1_000_000_000L / 24
        val p = Pacer(v24)
        var t = 0L
        val rendered = (1..240).count { t += v24; p.onVsync(t) }
        assertFalse(p.halved)
        assertEquals(240, rendered)
    }

    @Test
    fun aStopIsNotAMiss() {
        val p = Pacer()
        var t = v
        p.onVsync(t)
        repeat(3) {
            p.onStopped()
            t += 100 * v
            p.onVsync(t)
        }
        assertFalse(p.halved)
    }

    @Test
    fun pausedWantsOneFrozenFrameThenNothing() {
        val p = Pacer()
        assertTrue(p.wantsFrames(running = true, frozenFrameDrawn = true))
        assertTrue(p.wantsFrames(running = false, frozenFrameDrawn = false))
        assertFalse(p.wantsFrames(running = false, frozenFrameDrawn = true))
    }
}
