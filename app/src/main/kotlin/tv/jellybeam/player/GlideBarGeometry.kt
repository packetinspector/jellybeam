package tv.jellybeam.player

/** The glide traversal bar's four fractions (PRD §5.1), each coerced into `[0, 1]`. */
data class GlideBarFractions(val played: Float, val bandStart: Float, val bandEnd: Float, val target: Float)

/**
 * Fraction math for the glide traversal bar (PRD §5.1): played fill stays at true
 * position, the band spans true position <-> target. Converts already-resolved
 * [positionMs]/[targetMs] into fractions of [durationMs]; no decision logic.
 */
object GlideBarGeometry {
    fun fractions(positionMs: Long, targetMs: Long, durationMs: Long): GlideBarFractions? {
        if (durationMs <= 0L) return null
        val played = (positionMs.toDouble() / durationMs.toDouble()).toFloat().coerceIn(0f, 1f)
        val target = (targetMs.toDouble() / durationMs.toDouble()).toFloat().coerceIn(0f, 1f)
        return GlideBarFractions(
            played = played,
            bandStart = minOf(played, target),
            bandEnd = maxOf(played, target),
            target = target,
        )
    }
}
