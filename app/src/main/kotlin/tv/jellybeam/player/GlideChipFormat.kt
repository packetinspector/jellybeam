package tv.jellybeam.player

import uniffi.jellybeam_core.GlideClamp

/** The glide readout chip's target/delta/speed row (PRD §5.3), pure. */
data class GlideChipLine(val time: String, val delta: String, val speed: String?)

/**
 * Formats the glide chip's first line (PRD §5.3: `"1:24:30 · +18:20   30×"`); pure,
 * no decision logic. Values come from Rust's [uniffi.jellybeam_core.GlidePreview].
 */
object GlideChipFormat {
    fun line(targetMs: Long, deltaMs: Long, clamp: GlideClamp, multiplier: Int): GlideChipLine {
        val time = PlaybackTimeFormat.format(targetMs)
        val delta = when (clamp) {
            GlideClamp.START -> "Start"
            GlideClamp.END -> "End"
            GlideClamp.NONE -> {
                val sign = if (deltaMs < 0) "-" else "+"
                sign + PlaybackTimeFormat.format(kotlin.math.abs(deltaMs))
            }
        }
        val speed = if (multiplier > 0) "${multiplier}×" else null
        return GlideChipLine(time = time, delta = delta, speed = speed)
    }
}
