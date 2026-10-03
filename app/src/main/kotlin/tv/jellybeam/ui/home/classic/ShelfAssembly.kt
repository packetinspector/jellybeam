package tv.jellybeam.ui.home.classic

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import uniffi.jellybeam_core.Card
import uniffi.jellybeam_core.HomeShelf
import uniffi.jellybeam_core.ShelfSource

enum class ShelfKind { RESUME, POSTER }

data class ShelfSpec(val id: String, val title: String, val items: List<Card>, val kind: ShelfKind)

/**
 * docs/25 §3: the core already decided which shelves show and in what order; this only gives each
 * one its localised title and card treatment. Pulled out of the Composable file so it's
 * plain-JVM-testable.
 */
fun buildShelves(
    shelves: List<HomeShelf>,
    continueWatchingTitle: String,
    nextUpTitle: String,
    favoritesTitle: String,
    latestInTitle: (viewName: String) -> String,
): List<ShelfSpec> = shelves.map { shelf ->
    when (val source = shelf.source) {
        ShelfSource.ContinueWatching -> ShelfSpec(source.key(), continueWatchingTitle, shelf.cards, ShelfKind.RESUME)
        ShelfSource.NextUp -> ShelfSpec(source.key(), nextUpTitle, shelf.cards, ShelfKind.RESUME)
        ShelfSource.Favorites -> ShelfSpec(source.key(), favoritesTitle, shelf.cards, ShelfKind.POSTER)
        is ShelfSource.Latest -> ShelfSpec(source.key(), latestInTitle(source.viewName), shelf.cards, ShelfKind.POSTER)
    }
}

/** docs/25 §10.1: a shelf's identity for focus memory (`shelf:<key>/card:<id>`), its scroll state
 * and list reuse; these strings predate the core deciding shelves and must not change.
 */
fun ShelfSource.key(): String = when (this) {
    ShelfSource.ContinueWatching -> "resume"
    ShelfSource.NextUp -> "next-up"
    ShelfSource.Favorites -> "favorites"
    is ShelfSource.Latest -> "latest:$viewId"
}

/** Height of Home's floating wordmark band and its top wash. */
val MASTHEAD_HEIGHT = 100.dp

/** docs/07 §1: without a hero the shelves start below [MASTHEAD_HEIGHT], since the masthead floats over them. */
fun contentTopInset(hero: Card?): Dp = if (hero == null) MASTHEAD_HEIGHT else 0.dp

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
