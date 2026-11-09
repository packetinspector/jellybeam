package tv.jellybeam.visualizer.pattern

import tv.jellybeam.visualizer.color.PaletteFamily

/**
 * One `glDrawArraysInstanced(GL_TRIANGLES, 0, vertsPerInstance, instances)` with [vertexShader] (an asset
 * path); the counts must match the constants in that shader, which derives geometry from the vertex ids.
 */
class DrawSpec(val vertexShader: String, val vertsPerInstance: Int, val instances: Int)

/** docs/30 §6: how the composite reads the layer. */
enum class CompositeMode { PLAIN, KALEIDO }

/**
 * docs/30 §5–6 (E1): one pattern's frame. The layer pass warps last frame ([warp]) or paints a per-pixel
 * [field] (or both), then [inject] draws shapes into it; [sim] steps the ripple grid first. The composite
 * colours the layer through [family]; [overlay] draws crisp shapes at screen resolution on top.
 */
class PatternSpec(
    val family: PaletteFamily,
    val warp: Boolean,
    val field: String?,
    val sim: String?,
    val inject: List<DrawSpec>,
    val overlay: List<DrawSpec>,
    val composite: CompositeMode = CompositeMode.PLAIN,
) {
    /** E2: render passes into the low-res layer or the sim grid this frame. */
    val lowResPasses: Int = listOf(warp, field != null, sim != null, inject.isNotEmpty()).count { it }

    /** E2: draw calls, composite included. */
    val draws: Int = listOf(warp, field != null, sim != null).count { it } + inject.size + 1 + overlay.size

    /** E2: pixels shaded per frame, full-target passes counted at their size (geometry passes counted as a full layer). */
    val fill: Long = lowResPasses.toLong() * LAYER_PIXELS + SCREEN_PIXELS

    companion object {
        const val LAYER_W = 480
        const val LAYER_H = 270
        const val LAYER_PIXELS = LAYER_W * LAYER_H
        const val SCREEN_PIXELS = 1920 * 1080
        const val SIM_W = 120
        const val SIM_H = 68

        /** E1: everything the visualizer allocates on the GPU, in bytes. */
        const val GPU_BYTES_LIMIT = 1 shl 20
    }
}

/** docs/30 §5–6 (E2): a pattern's ceiling; raising a row is a deliberate cost decision. */
class Budget(val lowResPasses: Int, val draws: Int, val fill: Long)

/** docs/30 §5–6: each pattern's spec and the one budget table U9 holds them to. */
object PatternSpecs {
    /** E2: hard limits for any pattern; a crossfade may use twice the passes and draws (E6). */
    const val MAX_LOW_RES_PASSES = 3
    const val MAX_DRAWS = 8
    const val MAX_FILL: Long = 3L * PatternSpec.LAYER_PIXELS + PatternSpec.SCREEN_PIXELS

    private val specs: List<PatternSpec> = Pattern.entries.map(::specFor)

    fun spec(p: Pattern): PatternSpec = specs[p.ordinal]

    /** E2: the cost table; U9 fails when a spec's real usage differs from its row. */
    val budget: Map<Pattern, Budget> = mapOf(
        Pattern.BARS to Budget(2, 4, TWO_PASS_FILL),
        Pattern.RADIAL to Budget(2, 4, TWO_PASS_FILL),
        Pattern.WATERFALL to Budget(2, 4, TWO_PASS_FILL),
        Pattern.KALEIDO to Budget(2, 3, TWO_PASS_FILL),
        Pattern.SWIRL to Budget(2, 5, TWO_PASS_FILL),
        Pattern.FIRE to Budget(2, 4, TWO_PASS_FILL),
        Pattern.PHOSPHOR to Budget(2, 4, TWO_PASS_FILL),
        Pattern.SUPERSCOPE to Budget(2, 4, TWO_PASS_FILL),
        Pattern.RIDGELINE to Budget(2, 4, TWO_PASS_FILL),
        Pattern.RIPPLES to Budget(2, 3, TWO_PASS_FILL),
    )

    private const val TWO_PASS_FILL: Long = 2L * PatternSpec.LAYER_PIXELS + PatternSpec.SCREEN_PIXELS
    private const val QUAD = 6
    private const val BANDS = 64

    private fun draw(name: String, instances: Int) = DrawSpec("viz2/$name.vert.glsl", QUAD, instances)

    private fun frag(name: String) = "viz2/$name.frag.glsl"

    // docs/30 §5: what each pattern draws; instance counts match the constants in its shaders.
    private fun specFor(p: Pattern): PatternSpec = when (p) {
        Pattern.BARS -> PatternSpec(
            family = PaletteFamily.ANALOGOUS, warp = true, field = null, sim = null,
            inject = listOf(draw("bars.inject", BANDS)),
            overlay = listOf(draw("bars.overlay", 2 * BANDS)),
        )
        Pattern.RADIAL -> PatternSpec(
            family = PaletteFamily.TRIAD, warp = true, field = null, sim = null,
            inject = listOf(draw("radial.inject", 2 * BANDS)),
            overlay = listOf(draw("radial.overlay", 2 * BANDS)),
        )
        Pattern.WATERFALL -> PatternSpec(
            family = PaletteFamily.ICE, warp = true, field = null, sim = null,
            inject = listOf(draw("waterfall.inject", BANDS - 1)),
            overlay = listOf(draw("waterfall.overlay", WATERFALL_ROWS * (BANDS - 1))),
        )
        Pattern.KALEIDO -> PatternSpec(
            family = PaletteFamily.SPLIT, warp = true, field = null, sim = null,
            inject = listOf(draw("kaleido.inject", RING + BANDS)),
            overlay = emptyList(),
            composite = CompositeMode.KALEIDO,
        )
        Pattern.SWIRL -> PatternSpec(
            family = PaletteFamily.TRIAD, warp = true, field = null, sim = null,
            inject = listOf(draw("swirl.ring", RING), draw("swirl.sparks", KICK_SLOTS * 8)),
            overlay = listOf(draw("swirl.ring", RING)),
        )
        Pattern.FIRE -> PatternSpec(
            family = PaletteFamily.FIRE, warp = true, field = null, sim = null,
            inject = listOf(draw("fire.spectrum", BANDS), draw("fire.sparks", KICK_SLOTS * 6)),
            overlay = emptyList(),
        )
        Pattern.PHOSPHOR -> PatternSpec(
            family = PaletteFamily.ICE, warp = true, field = null, sim = null,
            inject = listOf(draw("phosphor.beam", BEAM_SEGS)),
            overlay = listOf(draw("phosphor.beam", BEAM_SEGS)),
        )
        Pattern.SUPERSCOPE -> PatternSpec(
            family = PaletteFamily.SPLIT, warp = true, field = null, sim = null,
            inject = listOf(draw("superscope.points", SCOPE_POINTS)),
            overlay = listOf(draw("superscope.points", SCOPE_POINTS)),
        )
        Pattern.RIDGELINE -> PatternSpec(
            family = PaletteFamily.ANALOGOUS, warp = true, field = null, sim = null,
            inject = listOf(draw("ridgeline.sky", BANDS - 1)),
            overlay = listOf(draw("ridgeline.overlay", RIDGE_ROWS * (BANDS - 1) * 2)),
        )
        Pattern.RIPPLES -> PatternSpec(
            family = PaletteFamily.ICE, warp = false, field = frag("ripples.field"), sim = frag("ripples.sim"),
            inject = emptyList(), overlay = emptyList(),
        )
    }

    private const val RING = 128
    private const val KICK_SLOTS = 8
    private const val BEAM_SEGS = 511
    private const val SCOPE_POINTS = 1024
    private const val WATERFALL_ROWS = 40
    private const val RIDGE_ROWS = 64
}
