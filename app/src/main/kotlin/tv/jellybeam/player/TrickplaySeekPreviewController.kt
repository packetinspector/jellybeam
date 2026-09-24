package tv.jellybeam.player

import uniffi.jellybeam_core.TrickplayTileFfi

/**
 * State machine for the trickplay scrub-preview panel (mission GOAL item 4/5), showing a thumbnail
 * for ~1.5s. Same injectable-[Clock] shape as [PlaybackOsdController]. [show] takes an
 * already-resolved [TrickplayTileFfi] (via [tv.jellybeam.data.CoreGateway.trickplayLocate]) --
 * which tile to show stays in Rust; this only decides when the panel is visible.
 */
class TrickplaySeekPreviewController(
    private val clock: Clock = Clock.SYSTEM,
    private val visibleForMs: Long = 1_500L,
) {
    /** One resolved preview: the position a seek press landed on, and the sprite-sheet tile
     * covering it.
     */
    data class Preview(val targetPositionMs: Long, val tile: TrickplayTileFfi)

    var preview: Preview? = null
        private set

    private var shownAtMs: Long = 0L

    /** Shows [tile] at [targetPositionMs] and restarts the visibility window; a call before the
     * previous window elapsed just restarts it against the new tile.
     */
    fun show(targetPositionMs: Long, tile: TrickplayTileFfi) {
        preview = Preview(targetPositionMs, tile)
        shownAtMs = clock.nowMs()
    }

    /** A seek press that resolved to no tile; hides immediately rather than leaving a stale
     * thumbnail from a previous seek.
     */
    fun clear() {
        preview = null
    }

    /** Called periodically; hides the panel once its visibility window has elapsed, a no-op
     * otherwise.
     */
    fun tick() {
        if (preview != null && clock.nowMs() - shownAtMs >= visibleForMs) {
            preview = null
        }
    }
}
