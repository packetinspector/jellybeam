package tv.jellybeam.ui.home

import uniffi.jellybeam_core.Card

enum class ShelfKind { RESUME, POSTER }

data class ShelfSpec(val id: String, val title: String, val items: List<Card>, val kind: ShelfKind)

/**
 * Home's shelf order and empty-shelf hiding (docs/07-home-browse-behavior.md §1): Continue
 * Watching,
 * then Next Up, then one "Latest in {view}" shelf per library in server order; an empty shelf is
 * omitted.
 * Pulled out of the Composable file so it's plain-JVM-testable.
 */
fun buildShelves(
    state: HomeUiState,
    continueWatchingTitle: String,
    nextUpTitle: String,
    latestInTitle: (viewName: String) -> String,
): List<ShelfSpec> = buildList {
    if (state.resume.isNotEmpty()) {
        add(ShelfSpec("resume", continueWatchingTitle, state.resume, ShelfKind.RESUME))
    }
    if (state.nextUp.isNotEmpty()) {
        add(ShelfSpec("next-up", nextUpTitle, state.nextUp, ShelfKind.RESUME))
    }
    for (shelf in state.latest) {
        if (shelf.cards.isNotEmpty()) {
            add(ShelfSpec("latest:${shelf.viewId}", latestInTitle(shelf.viewName), shelf.cards, ShelfKind.POSTER))
        }
    }
}

/** docs/07 §1's hero banner: shown only when shelf 0 is Continue Watching, using its first card --
 * [buildShelves] only ever places it at index 0, so this collapses to "resume has a first card".
 */
fun heroCard(state: HomeUiState): Card? = state.resume.firstOrNull()

/** docs/07 §1: progressive shelf mounting's initial seed -- the hero plus
 * this many shelves compose in Home's first content frame regardless of mounting progress; shelf 0
 * carries the initial focus target.
 */
fun initialMountCount(shelfCount: Int): Int = minOf(2, shelfCount)

/** The shelf count actually composed this frame: never fewer than [initialMountCount], never fewer
 * than progressive mounting's own progress ([grown]), never more than [shelfCount] exists to mount.
 * [grown] only ever grows across recompositions, so a `shelves` list that temporarily loses entries
 * clamps down here without discarding it -- a library regaining entries resumes mounting instead of
 * restarting from two.
 */
fun mountedShelfCount(grown: Int, shelfCount: Int): Int =
    minOf(shelfCount, maxOf(initialMountCount(shelfCount), grown))
