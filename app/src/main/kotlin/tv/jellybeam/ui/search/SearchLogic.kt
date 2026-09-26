package tv.jellybeam.ui.search

import tv.jellybeam.ui.discover.distinctSeerrCards
import uniffi.jellybeam_core.Card
import uniffi.jellybeam_core.SeerrCard
import uniffi.jellybeam_core.SeerrMediaType

/** The Discover section under Search's library results (docs/14 "Unified search"). */
sealed interface DiscoverSection {
    /** Not configured, blank query, or nothing new to request: no header, no row. */
    data object Hidden : DiscoverSection

    /** A Seerr query is pending for the current text; header plus a muted status line. */
    data object Searching : DiscoverSection

    /** Seerr failed or was unreachable; header plus a muted line, library results unaffected. */
    data object Unavailable : DiscoverSection

    data class Results(val cards: List<SeerrCard>) : DiscoverSection
}

/**
 * The Seerr cards worth showing under [library]: distinct, minus any title the library results
 * already show, because a duplicate would open the Discover page for something already playable.
 * Matches on Seerr's Jellyfin id, else on kind + title + year, since a Seerr linked to a different
 * server than the one browsed (or behind a multi-server proxy) reports ids that never match.
 */
fun discoverCardsBesideLibrary(library: List<Card>, seerr: List<SeerrCard>): List<SeerrCard> {
    val shownIds = library.flatMapTo(HashSet()) { listOfNotNull(it.id, it.seriesId) }
    val shownTitles = library.mapNotNullTo(HashSet()) { card ->
        val kind = when (card.itemType) {
            "Movie" -> SeerrMediaType.MOVIE
            "Series" -> SeerrMediaType.TV
            else -> null
        }
        titleKey(kind ?: return@mapNotNullTo null, card.name, card.productionYear ?: return@mapNotNullTo null)
    }
    return distinctSeerrCards(seerr).filter { card ->
        val sameId = card.jellyfinItemId != null && card.jellyfinItemId in shownIds
        val sameTitle = card.year?.let { titleKey(card.mediaType, card.title, it) in shownTitles } ?: false
        !sameId && !sameTitle
    }
}

private fun titleKey(kind: SeerrMediaType, title: String, year: Int) = "$kind|${title.trim().lowercase()}|$year"

/** A settled Seerr answer as a section: an empty list hides the section rather than saying so. */
fun discoverSectionFor(library: List<Card>, seerr: List<SeerrCard>): DiscoverSection =
    discoverCardsBesideLibrary(library, seerr).let { if (it.isEmpty()) DiscoverSection.Hidden else DiscoverSection.Results(it) }

/** How many Discover titles the below-the-fold hint announces, or null for no hint: only while
 * there are results and the section's header (grid index [headerIndex]) is past the last
 * visible item, since a visible header already says the same thing. */
fun discoverHintCount(section: DiscoverSection, headerIndex: Int, lastVisibleIndex: Int): Int? =
    (section as? DiscoverSection.Results)?.cards?.size?.takeIf { headerIndex > lastVisibleIndex }

/** Seerr waits for two characters: one letter matches half its catalogue and costs a network call. */
private const val DISCOVER_MIN_QUERY_LENGTH = 2

fun discoverQueryReady(query: String): Boolean = query.trim().length >= DISCOVER_MIN_QUERY_LENGTH

/** "No matches" only once both sides have settled empty; a Seerr failure shows its own line instead. */
fun showNoMatches(query: String, librarySearching: Boolean, library: List<Card>, discover: DiscoverSection): Boolean =
    query.isNotBlank() && !librarySearching && library.isEmpty() && discover == DiscoverSection.Hidden
