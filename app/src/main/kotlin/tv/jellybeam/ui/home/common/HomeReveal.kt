package tv.jellybeam.ui.home.common

/** What Home's first composition shows before any effect runs. */
internal data class HomeRevealStart(val contentRevealed: Boolean, val showLoadingSkeleton: Boolean)

/**
 * docs/10 "Startup phases": data already in hand at first composition (a finished warm-up seed)
 * draws content at once, with no skeleton and no 220 ms fade; still loading keeps the skeleton.
 * Decided at first composition, because a LaunchedEffect would cost a frame on the seeded path.
 */
internal fun homeRevealStart(isLoading: Boolean): HomeRevealStart =
    HomeRevealStart(contentRevealed = !isLoading, showLoadingSkeleton = isLoading)
