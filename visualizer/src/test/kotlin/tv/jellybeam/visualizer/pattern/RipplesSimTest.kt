package tv.jellybeam.visualizer.pattern

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.jellybeam.visualizer.Features

/** docs/30 §9 U10 (E7): the ripple grid through a CPU mirror of ripples.sim.frag.glsl, fed by PatternParams' drops. */
class RipplesSimTest {
    private val dt = 1f / 60f

    private fun kickFrame(f: Features, n: Int, kick: Boolean, snare: Boolean) {
        f.clear()
        f.timeS = n * dt
        f.kick = kick
        f.kickPulse = if (kick) 1f else 0f
        f.snare = snare
        f.snarePulse = if (snare) 1f else 0f
        f.loudShort = 0.5f
        f.silent = false
    }

    @Test
    fun theMirrorMatchesTheShaderConstants() {
        val sim = ShaderMirrors.shaderText("viz2/ripples.sim.frag.glsl")
        assertTrue(sim.contains("const float SCALE = 2048.0;"))
        assertTrue(sim.contains("const float C2 = 0.45;"))
        assertTrue(sim.contains("const float DAMP = 0.97;"))
        assertTrue(sim.contains("const int SIM_W = ${PatternSpec.SIM_W};"))
        assertTrue(sim.contains("const int SIM_H = ${PatternSpec.SIM_H};"))
        assertTrue(sim.contains("float hn = (2.0 * h - hp + C2 * lap) * DAMP + drop(u_param[0], c) + drop(u_param[1], c);"))
        assertTrue(sim.contains("vec2 q = (vec2(c) + 0.5 - d.xy) / d.w;"))
        assertEquals("ripples.sim.frag.glsl", PatternSpecs.spec(Pattern.RIPPLES).sim?.substringAfterLast('/'))
        // CFL for the 5-point scheme in 2D, and a damping that drains every mode.
        assertTrue(ShaderMirrors.SIM_C2 <= 0.5f)
        assertTrue(ShaderMirrors.SIM_DAMP > 0f && ShaderMirrors.SIM_DAMP < 1f)
    }

    @Test
    fun aKickDropsOneRippleAtAHashedPositionInsideTheGrid() {
        val f = Features()
        val out = FrameParams()
        kickFrame(f, 30, kick = true, snare = false)
        PatternParams.update(Pattern.RIPPLES, f, dt, out)
        val x = out.param[0]
        val y = out.param[1]
        assertTrue("height ${out.param[2]}", out.param[2] > 0f)
        assertTrue("radius ${out.param[3]}", out.param[3] > 0f)
        assertTrue("x $x", x >= 8f && x <= PatternSpec.SIM_W - 8f)
        assertTrue("y $y", y >= 6f && y <= PatternSpec.SIM_H - 6f)
        assertEquals("no snare drop", 0f, out.param[6], 0f)

        PatternParams.update(Pattern.RIPPLES, f, dt, out)
        assertEquals("same audio time, same place", x, out.param[0], 0f)

        kickFrame(f, 61, kick = true, snare = false)
        PatternParams.update(Pattern.RIPPLES, f, dt, out)
        assertNotEquals("another kick lands elsewhere", x, out.param[0])

        kickFrame(f, 62, kick = false, snare = false)
        PatternParams.update(Pattern.RIPPLES, f, dt, out)
        assertEquals("no onset, no drop", 0f, out.param[2] + out.param[6], 0f)
    }

    @Test
    fun aKickRingMovesOutward() {
        val grid = ShaderMirrors.Ripples()
        val f = Features()
        val out = FrameParams()
        kickFrame(f, 30, kick = true, snare = false)
        PatternParams.update(Pattern.RIPPLES, f, dt, out)
        val cx = out.param[0].toInt()
        val cy = out.param[1].toInt()
        grid.step(out.param, dt)
        // The ring's front: the farthest cell along the row still moved by a tenth of the largest motion.
        fun ringRadius(): Int {
            var top = 0f
            for (d in 0 until 40) top = maxOf(top, kotlin.math.abs(grid.at(cx + d, cy)), kotlin.math.abs(grid.at(cx - d, cy)))
            var front = 0
            for (d in 0 until 40) if (maxOf(kotlin.math.abs(grid.at(cx + d, cy)), kotlin.math.abs(grid.at(cx - d, cy))) > 0.1f * top) front = d
            return front
        }
        kickFrame(f, 31, kick = false, snare = false)
        val radii = IntArray(4)
        for (k in 0 until 4) {
            repeat(6) {
                PatternParams.update(Pattern.RIPPLES, f, dt, out)
                grid.step(out.param, dt)
            }
            radii[k] = ringRadius()
        }
        for (k in 1 until 4) assertTrue("radii ${radii.toList()}", radii[k] > radii[k - 1])
    }

    @Test
    fun repeatedKicksStayBoundedAndSilenceDrainsTheGrid() {
        val grid = ShaderMirrors.Ripples()
        val f = Features()
        val out = FrameParams()
        var peak = 0f
        for (n in 0 until 10_000) {
            kickFrame(f, n, kick = n % 20 == 0, snare = n % 30 == 15)
            PatternParams.update(Pattern.RIPPLES, f, dt, out)
            grid.step(out.param, dt)
            val m = grid.maxAbs()
            assertTrue("step $n max $m", m.isFinite())
            peak = maxOf(peak, m)
        }
        assertTrue("never near the 16-bit range (±16): $peak", peak < 8f)
        val loud = grid.energy()
        assertTrue("the kicks excite the grid: $loud", loud > 1.0)
        f.clear()
        for (n in 0 until 300) {
            f.timeS = (10_000 + n) * dt
            PatternParams.update(Pattern.RIPPLES, f, dt, out)
            grid.step(out.param, dt)
        }
        val quiet = grid.energy()
        assertTrue("5 s of silence leaves ${quiet / loud} of the energy", quiet < 0.01 * loud)
    }
}
