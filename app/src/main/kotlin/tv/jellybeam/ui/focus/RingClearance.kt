package tv.jellybeam.ui.focus

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import kotlin.math.abs

/**
 * Compose's default bring-into-view distance with the item grown by [margin] on both sides, so a
 * focus ring drawn outside the item scrolls fully into view instead of being cut at the viewport
 * edge (docs/15 §0.3). An item that cannot fit with its margin keeps the plain rule.
 */
internal fun ringClearScrollDistance(offset: Float, size: Float, containerSize: Float, margin: Float): Float {
    val m = if (size + 2 * margin <= containerSize) margin else 0f
    val leading = offset - m
    val trailing = offset + size + m
    return when {
        leading >= 0f && trailing <= containerSize -> 0f
        leading < 0f && trailing > containerSize -> 0f
        abs(leading) < abs(trailing - containerSize) -> leading
        else -> trailing - containerSize
    }
}

/** A [BringIntoViewSpec] applying [ringClearScrollDistance] with [marginPx]. */
@OptIn(ExperimentalFoundationApi::class)
internal fun ringClearBringIntoViewSpec(marginPx: Float): BringIntoViewSpec = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        ringClearScrollDistance(offset, size, containerSize, marginPx)
}
