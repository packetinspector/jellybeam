package tv.jellybeam.visualizer.color

import kotlin.math.PI
import kotlin.math.sin

/** The five palette families (docs/30 §4); order is the LUT texture's row-block order. */
enum class PaletteFamily(val key: String) {
    ANALOGOUS("analogous"),
    TRIAD("triad"),
    SPLIT("split"),
    FIRE("fire"),
    ICE("ice"),
}

/**
 * What a family is built from. The anchor hue and chroma come from the accent, except for a deep accent
 * (OKLab L under [DEEP_L]) which is too dark to carry a ramp that climbs to near-white: it borrows the sheen's.
 */
internal class Anchors(accent: Int, sheen: Int) {
    val hue: Double; val chroma: Double
    val sheenH: Double; val sheenL: Double

    init {
        val a = lch(accent)
        val s = lch(sheen)
        sheenH = s[2]; sheenL = s[0]
        val deep = a[0] < DEEP_L
        hue = if (deep) s[2] else a[2]
        chroma = (if (deep) s[1] else a[1]).coerceIn(MIN_CHROMA, MAX_CHROMA)
    }

    private fun lch(argb: Int): DoubleArray {
        val out = DoubleArray(3)
        Oklch.fromSrgb(
            ((argb shr 16) and 0xFF) / 255f,
            ((argb shr 8) and 0xFF) / 255f,
            (argb and 0xFF) / 255f,
            out,
        )
        return out
    }

    companion object {
        const val DEEP_L = 0.62
        const val MIN_CHROMA = 0.08
        const val MAX_CHROMA = 0.15
    }
}

/**
 * docs/30 §4 (E3): per family a LUT of [LEVELS] intensity levels by [POSITIONS] palette positions.
 * Level 0 is true black (#000000) at every position, so idle pixels emit no light on OLED and projector panels;
 * OKLab L (hence luminance) rises with level; positions are a closed loop,
 * so a cycle offset wraps seamlessly. Every entry is gamut mapped by chroma reduction at constant L and hue.
 */
object Palettes {
    const val POSITIONS = 128
    const val LEVELS = 32

    private const val TOP_L = 0.92
    private const val CHROMA_PEAK_T = 0.55
    private const val WOBBLE_DEG = 12.0
    private const val FIRE_WARM_HUE = 78.0

    /** sRGB 0..1, index (level * POSITIONS + pos) * 3. */
    fun build(f: PaletteFamily, accent: Int, sheen: Int): FloatArray {
        val anchors = Anchors(accent, sheen)
        val out = FloatArray(POSITIONS * LEVELS * 3)
        val lch = DoubleArray(3)
        val rgb = DoubleArray(3)
        for (level in 0 until LEVELS) for (pos in 0 until POSITIONS) {
            val i = (level * POSITIONS + pos) * 3
            // Level 0 stays exactly 0 (the array's initial value): it is the engine's clear colour.
            if (level == 0) continue
            target(f, level, pos, anchors, lch)
            Oklch.mapToGamut(lch[0], lch[1], lch[2], rgb)
            for (ch in 0 until 3) out[i + ch] = rgb[ch].toFloat()
        }
        return out
    }

    /** [all] holds one LUT per family in enum order; [out] is POSITIONS x (LEVELS * families) RGBA8, opaque. */
    fun toRgba8(all: List<FloatArray>, out: ByteArray) {
        var o = 0
        for (fi in all.indices) {
            val lut = all[fi]
            for (i in 0 until POSITIONS * LEVELS) {
                for (ch in 0 until 3) out[o++] = (lut[i * 3 + ch] * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
                out[o++] = 0xFF.toByte()
            }
        }
    }

    /**
     * Whether shifting every palette position by [step] moves any colour into or out of saturated red.
     * Each pixel carries its own position, so every position is checked; [offset] does not change the answer.
     */
    @Suppress("UNUSED_PARAMETER")
    fun stepIsRed(lut: FloatArray, offset: Float, step: Float): Boolean {
        val shift = Math.floorMod(Math.round(step * POSITIONS), POSITIONS)
        for (level in 1 until LEVELS) for (p0 in 0 until POSITIONS) {
            if (redAt(lut, level, p0) != redAt(lut, level, (p0 + shift) % POSITIONS)) return true
        }
        return false
    }

    private fun redAt(lut: FloatArray, level: Int, pos: Int): Boolean {
        val i = (level * POSITIONS + pos) * 3
        return FlashLimiter.isSaturatedRed(lut[i], lut[i + 1], lut[i + 2])
    }

    /** The designed (L, C, hue) of one entry before gamut mapping. */
    internal fun target(f: PaletteFamily, level: Int, pos: Int, a: Anchors, out: DoubleArray) {
        if (level == 0) {
            out[0] = 0.0; out[1] = 0.0; out[2] = a.hue
            return
        }
        val t = level.toDouble() / (LEVELS - 1)
        val wobble = WOBBLE_DEG * sin(2.0 * PI * pos / POSITIONS)
        when (f) {
            PaletteFamily.ANALOGOUS, PaletteFamily.TRIAD, PaletteFamily.SPLIT -> {
                out[0] = TOP_L * t
                out[1] = a.chroma * chromaShape(t)
                out[2] = loopHue(f, pos, a.hue)
            }
            PaletteFamily.FIRE -> fire(t, a, wobble, out)
            PaletteFamily.ICE -> ice(t, a, wobble, out)
        }
    }

    // Black -> deep hue (low L, high C) -> accent -> warm near-white; L is linear between stops so it only rises.
    private fun fire(t: Double, a: Anchors, wobble: Double, out: DoubleArray) {
        val deepH = a.hue - 30.0
        val seg: Int
        val u: Double
        when {
            t <= 0.35 -> { seg = 0; u = t / 0.35 }
            t <= 0.68 -> { seg = 1; u = (t - 0.35) / 0.33 }
            else -> { seg = 2; u = (t - 0.68) / 0.32 }
        }
        val s = smooth(u)
        when (seg) {
            0 -> {
                out[0] = 0.42 * u
                out[1] = 0.17 * s
                out[2] = deepH
            }
            1 -> {
                out[0] = lerp(0.42, 0.72, u)
                out[1] = lerp(0.17, a.chroma, s)
                out[2] = deepH + shortDelta(deepH, a.hue) * s
            }
            else -> {
                out[0] = lerp(0.72, 0.95, u)
                out[1] = lerp(a.chroma, 0.045, s)
                out[2] = a.hue + shortDelta(a.hue, FIRE_WARM_HUE) * s
            }
        }
        out[2] = wrapHue(out[2] + wobble)
    }

    // Black -> hue accent+150 at the dark end, turning to the sheen's hue and L at the top.
    private fun ice(t: Double, a: Anchors, wobble: Double, out: DoubleArray) {
        val startH = a.hue + 150.0
        val topL = a.sheenL.coerceIn(0.86, 0.93)
        out[0] = topL * t
        out[1] = a.chroma * chromaShape(t)
        out[2] = wrapHue(startH + shortDelta(startH, a.sheenH) * smooth(((t - 0.1) / 0.8).coerceIn(0.0, 1.0)) + wobble)
    }

    // Rises fast to the peak, then eases down so the near-white top stays tinted but not garish.
    private fun chromaShape(t: Double): Double =
        if (t <= CHROMA_PEAK_T) {
            sin(t / CHROMA_PEAK_T * PI / 2.0)
        } else {
            val u = (t - CHROMA_PEAK_T) / (1.0 - CHROMA_PEAK_T)
            1.0 - 0.4 * u * u
        }

    /** The family's three hues in order; the position loop walks them and back to the first. */
    private fun keyHue(f: PaletteFamily, i: Int, accent: Double): Double = when (f) {
        PaletteFamily.ANALOGOUS -> accent + (i - 1) * 40.0
        PaletteFamily.TRIAD -> accent + i * 120.0
        else -> accent + when (i) { 0 -> 0.0; 1 -> 150.0; else -> 210.0 }
    }

    private fun loopHue(f: PaletteFamily, pos: Int, accent: Double): Double {
        val u = pos * 3.0 / POSITIONS
        val seg = u.toInt()
        val from = keyHue(f, seg, accent)
        val to = keyHue(f, (seg + 1) % 3, accent)
        return wrapHue(from + shortDelta(from, to) * smooth(u - seg))
    }

    private fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t

    private fun smooth(t: Double) = t * t * (3.0 - 2.0 * t)

    private fun wrapHue(h: Double): Double = ((h % 360.0) + 360.0) % 360.0

    /** Signed hue difference from [from] to [to], the short way round (-180..180). */
    private fun shortDelta(from: Double, to: Double): Double {
        var d = (to - from) % 360.0
        if (d > 180.0) d -= 360.0
        if (d < -180.0) d += 360.0
        return d
    }
}
