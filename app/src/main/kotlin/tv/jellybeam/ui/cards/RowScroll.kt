package tv.jellybeam.ui.cards

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp

/**
 * The default [BringIntoViewSpec] slides a focused item flush to the viewport edge, ignoring
 * `contentPadding` -- this clips the focus ring at the bezel on scroll, and leaves no "peek" of
 * the next card when the rightmost visible card is focused. Pins the focused item at a fixed
 * cursor, [startMargin] from the viewport's leading edge, instead. Provide via
 * `CompositionLocalProvider(LocalBringIntoViewSpec provides ...)` wrapped directly around a
 * shelf's own `LazyRow` (an inner provider nested inside the outer vertical spec).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberShelfBringIntoViewSpec(startMargin: Dp): BringIntoViewSpec {
    val startMarginPx = with(LocalDensity.current) { startMargin.toPx() }
    return remember(startMarginPx) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
                shelfBringIntoViewScrollDistance(offset, startMarginPx)
        }
    }
}

/**
 * Pure arithmetic behind [rememberShelfBringIntoViewSpec], pulled out as a plain top-level
 * function so it's JVM-testable. Returns the distance to scroll so [offset] (the focused item's
 * leading edge, relative to the viewport start) lands at [startMarginPx]; `LazyRow` clamps this
 * at both content ends, which is what keeps row-start cards at their natural position and lets
 * the last card be focused without being pulled fully to the cursor.
 */
internal fun shelfBringIntoViewScrollDistance(offset: Float, startMarginPx: Float): Float =
    offset - startMarginPx

/**
 * docs/15 §2: a detail page's vertical spec. Scrolls only as far as needed (never the TV pivot,
 * which would park the first-open seed at 30% of the viewport), except that an item above the
 * viewport that lies inside the header (art, title, action row) brings the page back to its top,
 * so a return to the action row shows the whole header rather than just the pill.
 * [headerBottomPx] is the header's bottom in content coordinates, NaN until measured.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberHeaderPageBringIntoViewSpec(scrollState: ScrollState, headerBottomPx: () -> Float): BringIntoViewSpec =
    remember(scrollState) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
                headerPageScrollToTopDistance(offset, size, containerSize, scrollState.value.toFloat(), headerBottomPx())
                    ?: super.calculateScrollDistance(offset, size, containerSize)
        }
    }

/**
 * Pure arithmetic behind [rememberHeaderPageBringIntoViewSpec]: the distance back to the page
 * top while the page is scrolled and the item (leading edge [offset] relative to the viewport,
 * content position `offset + scrolled`) lies inside the header and fits in the first screenful,
 * else `null` for the default rule. Holding for the whole animation, not just while the item is
 * above the viewport, is what carries the scroll all the way to 0 instead of stopping at the pill.
 */
internal fun headerPageScrollToTopDistance(offset: Float, size: Float, containerSize: Float, scrolled: Float, headerBottomPx: Float): Float? {
    val contentTop = offset + scrolled
    val fitsFirstScreen = contentTop + size <= containerSize
    return if (scrolled > 0f && contentTop < headerBottomPx && fitsFirstScreen) -scrolled else null
}
