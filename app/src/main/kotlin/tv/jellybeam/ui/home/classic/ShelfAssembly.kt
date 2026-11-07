package tv.jellybeam.ui.home.classic

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import uniffi.jellybeam_core.Card
import uniffi.jellybeam_core.HomeShelf
import uniffi.jellybeam_core.ResumeArt
import uniffi.jellybeam_core.ShelfSource

/** [SQUARE] is a music library's Latest: one 1:1 card per album (docs/29 §9 J1). */
enum class ShelfKind { RESUME, POSTER, SQUARE }

/** [art] only matters on a [ShelfKind.RESUME] shelf: which 16:9 chain its cards draw (docs/07 §1). */
data class ShelfSpec(
    val id: String,
    val title: String,
    val items: List<Card>,
    val kind: ShelfKind,
    val art: ResumeArt = ResumeArt.EPISODE,
)

/**
 * docs/25 §3: the core already decided which shelves show and in what order; this only gives each
 * one its localised title and card treatment. Pulled out of the Composable file so it's
 * plain-JVM-testable. [resumeArt] mirrors `Settings.homeResumeArt` (docs/07 §1): Poster draws the Latest poster cells,
 * the others a 16:9 rail. [musicViewIds] are the views with collection type `music`, whose Latest is square.
 */
fun buildShelves(
    shelves: List<HomeShelf>,
    continueWatchingTitle: String,
    nextUpTitle: String,
    favoritesTitle: String,
    resumeArt: ResumeArt,
    musicViewIds: Set<String> = emptySet(),
    latestInTitle: (viewName: String) -> String,
): List<ShelfSpec> = shelves.map { shelf ->
    val resumeKind = if (resumeArt == ResumeArt.POSTER) ShelfKind.POSTER else ShelfKind.RESUME
    when (val source = shelf.source) {
        ShelfSource.ContinueWatching -> ShelfSpec(source.key(), continueWatchingTitle, shelf.cards, resumeKind, resumeArt)
        ShelfSource.NextUp -> ShelfSpec(source.key(), nextUpTitle, shelf.cards, resumeKind, resumeArt)
        ShelfSource.Favorites -> ShelfSpec(source.key(), favoritesTitle, shelf.cards, ShelfKind.POSTER)
        is ShelfSource.Latest -> {
            val kind = if (source.viewId in musicViewIds) ShelfKind.SQUARE else ShelfKind.POSTER
            ShelfSpec(source.key(), latestInTitle(source.viewName), shelf.cards, kind)
        }
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

/** What a key does on a focused [ShelfKind.SQUARE] album (docs/29 §9 J1). */
enum class AlbumKey { PLAY, CONSUME, PASS }

/**
 * docs/29 §9 J1: the remote's Play or Play/Pause plays the focused album, once per press; its repeats and
 * release are consumed so they never reach the media session as a toggle.
 */
fun albumPlayKey(key: Key, type: KeyEventType, repeatCount: Int): AlbumKey = when {
    key != Key.MediaPlay && key != Key.MediaPlayPause -> AlbumKey.PASS
    type == KeyEventType.KeyDown && repeatCount == 0 -> AlbumKey.PLAY
    else -> AlbumKey.CONSUME
}
