package tv.jellybeam.ui.nav

import androidx.compose.ui.unit.dp

/**
 * docs/07 §5: the nav drawer's permanent closed edge. A non-focusable full-height strip at x = 0
 * with a centred chevron, drawn by [NavDrawerHost]'s panel so an open widens the strip rather
 * than replacing it. It lives inside the page gutter (every page margin is 32 or 40dp), so no
 * layout value moves between closed and open. The strip never reacts to focus: a widen, a
 * brighter chevron, a lighter fill or a tinted wash beside it all read as flicker or shadow on the
 * TV and were removed. px values are the 1080p design at 2x.
 */
internal object MenuSpine {
    val WIDTH = 19.dp
    val CHEVRON_SIZE = 13.dp
    val CHEVRON_STROKE = 1.3.dp

    /** The divider: 1px at 1080p, never a 1dp bar. */
    val HAIRLINE = 0.5.dp

    /** The closed strip's fill opacity over the page (the open panel is solid); a solid strip dominated the TV. */
    const val CLOSED_FILL_ALPHA = 0.40f

    const val OPEN_MS = 220
    const val CLOSE_MS = 160

    /** The chevron is gone in the first 80ms of a 220ms open, and back in the same last slice of a close. */
    const val CHEVRON_FADE_FRACTION = 80f / 220f

    /** Drawer rows fade in over the last 120ms of a 220ms open, and out over the same first slice of a close. */
    const val CONTENT_FADE_FRACTION = 120f / 220f
}

/** Chevron opacity for an open [progress] in 0..1; the close is the exact reverse. */
internal fun spineChevronAlpha(progress: Float): Float =
    (1f - progress / MenuSpine.CHEVRON_FADE_FRACTION).coerceIn(0f, 1f)

/** Drawer-row opacity for an open [progress] in 0..1; the close is the exact reverse. */
internal fun drawerContentAlpha(progress: Float): Float =
    ((progress - (1f - MenuSpine.CONTENT_FADE_FRACTION)) / MenuSpine.CONTENT_FADE_FRACTION).coerceIn(0f, 1f)
