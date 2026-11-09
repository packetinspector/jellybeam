package tv.jellybeam.visualizer.pattern

import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.jellybeam.visualizer.Features
import tv.jellybeam.visualizer.dsp.Signals
import kotlin.math.sin

/** The four synthetic signals of docs/30 §9 U8, one frame at a time at 60 fps. */
internal class SignalFeatures(val signal: Int, seed: Long = 7L) {
    val f = Features()
    private val rnd = Random(seed)

    fun frame(n: Int, dtS: Float = FRAME_S): Features {
        f.clear()
        f.timeS = n * dtS
        when (signal) {
            SILENCE -> Unit
            TONE -> {
                val b = Signals.bandOf(1000.0)
                f.bands[b] = 0.9f
                f.peaks[b] = 0.95f
                for (i in 0 until Features.WAVE) {
                    val s = 0.6f * sin(i * 0.13f)
                    f.wave[i] = s; f.waveL[i] = s; f.waveR[i] = s
                }
                tone(0.3f)
            }
            KICK -> {
                for (i in 0 until 10) f.bands[i] = 0.8f
                f.kick = true; f.beat = true
                f.kickPulse = 1f; f.beatPulse = 1f
                f.beatAges[0] = 0f
                f.bass = 0.8f
                tone(0.5f)
            }
            else -> {
                for (i in 0 until Features.BANDS) f.bands[i] = rnd.nextFloat()
                for (i in 0 until Features.WAVE) {
                    f.wave[i] = rnd.nextFloat() * 2f - 1f
                    f.waveL[i] = rnd.nextFloat() * 2f - 1f
                    f.waveR[i] = rnd.nextFloat() * 2f - 1f
                }
                f.kick = rnd.nextInt(8) == 0
                f.snare = rnd.nextInt(8) == 0
                f.hat = rnd.nextInt(3) == 0
                f.kickPulse = rnd.nextFloat(); f.snarePulse = rnd.nextFloat(); f.hatPulse = rnd.nextFloat()
                f.flux = rnd.nextFloat()
                f.bass = rnd.nextFloat(); f.mid = rnd.nextFloat(); f.treble = rnd.nextFloat()
                f.tempoLocked = true
                f.beatPhase = (n % 28) / 28f
                f.beatCount = n / 28
                f.phrase = n / (28 * 16)
                f.phraseTick = n % (28 * 16) == 0 && n > 0
                tone(rnd.nextFloat())
            }
        }
        return f
    }

    private fun tone(level: Float) {
        f.level = level
        f.loudShort = level
        f.loudLong = level
        f.silent = false
    }

    companion object {
        const val FRAME_S = 1f / 60f
        const val SILENCE = 0
        const val TONE = 1
        const val KICK = 2
        const val NOISE = 3
        val ALL = intArrayOf(SILENCE, TONE, KICK, NOISE)
    }
}

/** docs/30 §9 U8 (E1): every pattern's warp, through a CPU mirror of warp.vert.glsl, stays bounded and moves only with audio time. */
class WarpMathsTest {
    private val src = FloatArray(2)

    /** Runs [frames] updates and folds every parameter into a checksum, checking the warp after each frame. */
    private fun run(p: Pattern, signal: Int, dtS: Float, frames: Int, check: Boolean): Long {
        val sig = SignalFeatures(signal)
        val out = FrameParams()
        var sum = 17L
        for (n in 0 until frames) {
            val f = sig.frame(n, dtS)
            PatternParams.update(p, f, dtS, out)
            for (v in out.warp) sum = sum * 31 + v.toRawBits()
            for (v in out.param) sum = sum * 31 + v.toRawBits()
            sum = sum * 31 + out.compParam.toRawBits()
            sum = sum * 31 + out.travel.toRawBits()
            if (check && PatternSpecs.spec(p).warp) {
                val d = out.warp[3]
                assertTrue("$p signal $signal frame $n decay $d", d > 0f && d < 1f)
                for (cy in 0..ShaderMirrors.GY) for (cx in 0..ShaderMirrors.GX) {
                    ShaderMirrors.warpSource(cx, cy, out.warp, f.timeS, ASPECT, src)
                    for (u in src) assertTrue("$p signal $signal frame $n vertex ($cx,$cy) src $u", u.isFinite() && u >= -0.1f && u <= 1.1f)
                }
            }
        }
        return sum
    }

    @Test
    fun theMirrorMatchesWarpVert() {
        val vert = ShaderMirrors.shaderText("viz2/warp.vert.glsl")
        for (line in listOf(
            "a -= u_warp[0].y + u_warp[0].z * max(0.0, 1.0 - r * 1.6);",
            "r = r / max(u_warp[0].x, 0.01);",
            "r += u_warp[1].z * sin(r * u_warp[1].w - u_time.x * 3.0);",
            "vec2 src = centre + vec2(cos(a), sin(a)) * r / vec2(aspect, 1.0) - u_warp[1].xy;",
            "uint seed = uint(cx + cy * 97) + uint(u_time.x * 60.0) * 7919u;",
            "src += (vec2(hash01(seed), hash01(seed ^ 0x9e3779b9u)) - 0.5) * u_warp[2].z;",
        )) assertTrue(line, vert.contains(line))
    }

    @Test
    fun everyWarpStaysInsideTheLayerWithADecayBelowOne() {
        for (p in Pattern.entries) for (s in SignalFeatures.ALL) for (dt in floatArrayOf(1f / 60f, 1f / 30f)) run(p, s, dt, FRAMES, check = true)
    }

    @Test
    fun theParameterSequenceIsDeterministic() {
        for (p in Pattern.entries) for (s in SignalFeatures.ALL) {
            assertEquals("$p signal $s", run(p, s, 1f / 60f, FRAMES, false), run(p, s, 1f / 60f, FRAMES, false))
        }
    }

    @Test
    fun aRedrawWithoutNewAudioMovesNothing() {
        for (p in Pattern.entries) for (s in SignalFeatures.ALL) {
            val sig = SignalFeatures(s)
            val out = FrameParams()
            for (n in 0 until 30) PatternParams.update(p, sig.frame(n), 1f / 60f, out)
            val travel = out.travel
            val comp = out.compParam
            val f = sig.frame(30)
            PatternParams.update(p, f, 0f, out)
            assertEquals("$p travel", travel, out.travel, 0f)
            assertEquals("$p kaleidoscope turn", comp, out.compParam, 0f)
            assertEquals("$p no ripple drop", 0f, out.param[2] + out.param[6], 0f)
            if (!PatternSpecs.spec(p).warp) continue
            assertEquals("$p decay", 1f, out.warp[3], 0f)
            for (cy in 0..ShaderMirrors.GY) for (cx in 0..ShaderMirrors.GX) {
                ShaderMirrors.warpSource(cx, cy, out.warp, f.timeS, ASPECT, src)
                assertArrayEquals("$p ($cx,$cy)", floatArrayOf(cx.toFloat() / ShaderMirrors.GX, cy.toFloat() / ShaderMirrors.GY), src, 1e-5f)
            }
        }
    }

    @Test
    fun barsTrailsRise() {
        val sig = SignalFeatures(SignalFeatures.TONE)
        val out = FrameParams()
        val f = sig.frame(1)
        PatternParams.update(Pattern.BARS, f, 1f / 60f, out)
        for (cy in 0..ShaderMirrors.GY) for (cx in 0..ShaderMirrors.GX) {
            ShaderMirrors.warpSource(cx, cy, out.warp, f.timeS, ASPECT, src)
            assertTrue("($cx,$cy) takes its content from below", src[1] < cy.toFloat() / ShaderMirrors.GY)
        }
    }

    @Test
    fun phosphorAndSuperscopeChangeModeOnPhrases() {
        val f = Features()
        val out = FrameParams()
        val modes = (0 until 6).map { ph ->
            f.phrase = ph
            PatternParams.update(Pattern.PHOSPHOR, f, 1f / 60f, out)
            out.param[0]
        }
        assertEquals(listOf(0f, 1f, 0f, 1f, 0f, 1f), modes)
        val shapes = (0 until 6).map { ph ->
            f.phrase = ph
            PatternParams.update(Pattern.SUPERSCOPE, f, 1f / 60f, out)
            out.param[0]
        }
        assertEquals(listOf(0f, 1f, 2f, 0f, 1f, 2f), shapes)
    }

    @Test
    fun anUpdateAllocatesNothing() {
        val sig = SignalFeatures(SignalFeatures.NOISE)
        val out = FrameParams()
        val patterns = Pattern.entries.toTypedArray()
        for (n in 0 until 200) PatternParams.update(patterns[n % patterns.size], sig.frame(n), 1f / 60f, out)
        val f = sig.frame(0)
        f.kick = true
        val overhead = Signals.allocationOverhead()
        val start = Signals.allocatedBytes()
        var i = 0
        while (i < 1200) {
            PatternParams.update(patterns[i % patterns.size], f, 1f / 60f, out)
            i++
        }
        assertEquals(0L, Signals.allocatedBytes() - start - overhead)
    }

    private companion object {
        const val FRAMES = 600
        const val ASPECT = 480f / 270f
    }
}
