package tv.jellybeam.ui.search

import tv.jellybeam.ui.discover.distinctSeerrCards
import uniffi.jellybeam_core.Card
import uniffi.jellybeam_core.SeerrCard
import uniffi.jellybeam_core.SeerrMediaType
import uniffi.jellybeam_core.ViewSnapshot

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

/** docs/29 §9 K1: Search's result groups in their fixed order, so D-pad paths never move with the query;
 * [Other] keeps every type the spec doesn't name last rather than hiding it. */
enum class SearchGroup(val itemType: String?) {
    Films("Movie"),
    Series("Series"),
    Episodes("Episode"),
    Artists("MusicArtist"),
    Albums("MusicAlbum"),
    Songs("Audio"),
    Playlists("Playlist"),
    Other(null),
}

/** One non-empty group, its cards in the index's rank order. */
data class SearchResultGroup(val group: SearchGroup, val cards: List<Card>)

/**
 * docs/29 §9 K1/K2: [results] split into [SearchGroup]s, empty ones absent; a `Person` hit named exactly
 * like an artist in the same results is dropped, since the artist card already leads to it.
 */
fun groupSearchResults(results: List<Card>): List<SearchResultGroup> {
    val artistNames = results.mapNotNullTo(HashSet()) { card -> card.name.takeIf { card.itemType == SearchGroup.Artists.itemType } }
    val byGroup = results
        .filterNot { it.itemType == PERSON_ITEM_TYPE && it.name in artistNames }
        .groupBy { card -> SearchGroup.entries.firstOrNull { it.itemType == card.itemType } ?: SearchGroup.Other }
    return SearchGroup.entries.mapNotNull { group -> byGroup[group]?.let { SearchResultGroup(group, it) } }
}

private const val PERSON_ITEM_TYPE = "Person"

/** The grid holds one header per group ahead of its cards; this is how many cells that is. */
fun searchGridCellCount(groups: List<SearchResultGroup>): Int = groups.sumOf { it.cards.size + 1 }

/** The grid index of [cardId]'s cell, or -1 when no group holds it. */
fun searchGridIndexOf(groups: List<SearchResultGroup>, cardId: String): Int {
    var groupStart = 0
    for (group in groups) {
        val index = group.cards.indexOfFirst { it.id == cardId }
        if (index >= 0) return groupStart + 1 + index
        groupStart += group.cards.size + 1
    }
    return -1
}

/** docs/29 §9 K3: what the no-results line says was searched, in this fixed order. */
enum class SearchedKind(val collectionType: String) { Films("movies"), Series("tvshows"), Music("music") }

/** The kinds the viewer's libraries hold, by collection type; libraries of other types add nothing. */
fun searchedKinds(views: List<ViewSnapshot>): List<SearchedKind> =
    SearchedKind.entries.filter { kind -> views.any { it.collectionType == kind.collectionType } }
