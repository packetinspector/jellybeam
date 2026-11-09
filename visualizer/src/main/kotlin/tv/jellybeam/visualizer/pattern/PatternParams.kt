package tv.jellybeam.visualizer.pattern

import kotlin.math.exp
import kotlin.math.ln
import tv.jellybeam.visualizer.Features

/** docs/30 §5–6: the pattern-specific uniforms; the renderer copies each array to its Frame block field. */
class FrameParams {
    /** zoom, rotate, swirl, decay | tx, ty, rippleAmp, rippleFreq | cx, cy, jitter, gShift (u_warp); read only for warping patterns. */
    val warp = FloatArray(12)

    /** u_param[0..1]. */
    val param = FloatArray(8)

    /** u_comp.y: the composite mode's parameter (kaleidoscope rotation). */
    var compParam = 0f

    /** u_time.z: a CPU-integrated distance for patterns that travel. */
    var travel = 0f
}

/**
 * docs/30 §5: per-frame CPU uniforms from audio time and features only, so a paused frame is static.
 * Warp motion is given per 60 fps frame and scaled by dt, so the look holds at 30 fps and a redraw with
 * dt 0 is the identity with decay 1.
 */
object PatternParams {
    private const val MAX_DT_S = 0.1f
    private const val FRAME_S = 1f / 60f
    private const val TAU = 6.2831855f

    private const val KICK_HEIGHT = 1.2f
    private const val KICK_RADIUS = 2.2f
    private const val SNARE_HEIGHT = 0.6f
    private const val SNARE_RADIUS = 1.5f
    private const val SNARE_SALT = 0.5f

    /** Writes [out] for [p]; zero allocation. [dtS] 0 (a redraw without new audio) moves nothing. */
    /** [kickPulse] is the flash-limited kick (docs/30 §4); motion follows it like the shaders do. */
    fun update(p: Pattern, f: Features, dtS: Float, out: FrameParams, kickPulse: Float = f.kickPulse) {
        val dt = dtS.coerceIn(0f, MAX_DT_S)
        val k = dt / FRAME_S
        val loud = f.loudShort
        val turn = if (f.phrase and 1 == 1) -1f else 1f
        out.param.fill(0f)
        if (p != Pattern.KALEIDO) out.compParam = 0f
        when (p) {
            // Trails rise and fade, shifting hue as they go.
            Pattern.BARS -> warp(out, k, decay = 0.90f, ty = 0.004f, gShift = 0.0015f)
            // A slow swirl flowing outward, kicked wider; its turn reverses each phrase.
            Pattern.RADIAL -> warp(
                out, k, decay = 0.92f, zoom = 0.004f + 0.02f * kickPulse,
                rotate = (0.003f + 0.01f * loud) * turn, swirl = 0.02f, gShift = 0.002f,
            )
            // The glow trail shrinks toward the horizon (waterfall shaders: 0.55 of the half height up).
            Pattern.WATERFALL -> warp(out, k, decay = 0.94f, zoom = -0.01f, cy = 0.775f, gShift = 0.001f)
            Pattern.KALEIDO -> {
                warp(out, k, decay = 0.93f, zoom = 0.006f + 0.02f * kickPulse, rotate = 0.004f + 0.012f * loud, swirl = 0.03f, gShift = 0.003f)
                out.compParam = (out.compParam + dt * (0.08f + 0.3f * loud)) % TAU
            }
            // Zoom-rotate feedback: bass and kicks push outward, snares twist, hats ripple; the turn reverses each phrase.
            Pattern.SWIRL -> warp(
                out, k, decay = 0.95f, zoom = 0.012f + 0.025f * f.bass + 0.04f * kickPulse,
                rotate = (0.006f + 0.02f * loud) * turn, swirl = 0.12f + 0.1f * f.snarePulse,
                rippleAmp = 0.004f * f.hatPulse, rippleFreq = 30f, gShift = 0.004f,
            )
            // Heat rises with a flicker and cools as it goes.
            Pattern.FIRE -> warp(out, k, decay = 0.94f, ty = 0.008f + 0.004f * loud, jitter = 0.0035f, gShift = 0.0005f)
            // CRT afterglow; a phrase swaps XY (0) and the oscilloscope (1).
            Pattern.PHOSPHOR -> {
                warp(out, k, decay = 0.94f, zoom = 0.0015f, gShift = 0.001f)
                out.param[0] = if (turn < 0f) 1f else 0f
            }
            // Expanding trails behind the turning shape; a phrase picks the next shape.
            Pattern.SUPERSCOPE -> {
                warp(out, k, decay = 0.90f, zoom = 0.008f, rotate = 0.002f, gShift = 0.002f)
                out.param[0] = (f.phrase % 3).toFloat()
                out.travel += dt * (0.25f + 0.9f * loud)
            }
            // The aurora rises above the farthest ridge.
            Pattern.RIDGELINE -> warp(out, k, decay = 0.93f, ty = 0.004f, gShift = 0.002f)
            Pattern.RIPPLES -> {
                if (dt > 0f && f.kick) drop(out, 0, f.timeS, KICK_HEIGHT, KICK_RADIUS)
                if (dt > 0f && f.snare) drop(out, 4, f.timeS + SNARE_SALT, SNARE_HEIGHT, SNARE_RADIUS)
            }
        }
    }

    /** u_warp: per-60-fps-frame amounts times [k] frames, decay raised to [k]; a negative [zoom] contracts toward (0.5, [cy]). */
    private fun warp(
        out: FrameParams, k: Float, decay: Float, zoom: Float = 0f, rotate: Float = 0f, swirl: Float = 0f,
        ty: Float = 0f, rippleAmp: Float = 0f, rippleFreq: Float = 0f, cy: Float = 0.5f, jitter: Float = 0f, gShift: Float = 0f,
    ) {
        val w = out.warp
        w[0] = 1f + zoom * k
        w[1] = rotate * k
        w[2] = swirl * k
        w[3] = exp(ln(decay) * k)
        w[4] = 0f
        w[5] = ty * k
        w[6] = rippleAmp * k
        w[7] = rippleFreq
        w[8] = 0.5f
        w[9] = cy
        w[10] = jitter * k
        w[11] = gShift * k
    }

    /**
     * docs/30 §5: a ripple into u_param slot [o] at a place hashed from the onset's audio time (same time,
     * same place): cell x = 10 + 100·h₁, cell y = 8 + 52·h₂ (bottom-left origin), then height and radius.
     */
    private fun drop(out: FrameParams, o: Int, timeS: Float, height: Float, radius: Float) {
        val h1 = hashU(java.lang.Float.floatToRawIntBits(timeS))
        val h2 = hashU(h1 xor 0x9e3779b9.toInt())
        out.param[o] = 10f + 100f * unit(h1)
        out.param[o + 1] = 8f + 52f * unit(h2)
        out.param[o + 2] = height
        out.param[o + 3] = radius
    }

    /** common.glsl `hashU` (lowbias32). */
    private fun hashU(x: Int): Int {
        var h = x
        h = h xor (h ushr 16)
        h *= 0x7feb352d
        h = h xor (h ushr 15)
        h *= 0x846ca68b.toInt()
        h = h xor (h ushr 16)
        return h
    }

    private fun unit(h: Int): Float = (h ushr 8) * (1f / 16777216f)
}
