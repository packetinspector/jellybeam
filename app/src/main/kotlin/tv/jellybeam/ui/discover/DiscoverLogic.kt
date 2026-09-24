package tv.jellybeam.ui.discover

import uniffi.jellybeam_core.SeerrAvailability
import uniffi.jellybeam_core.SeerrCard
import uniffi.jellybeam_core.SeerrMediaType
import uniffi.jellybeam_core.SeerrSeasonStatus
import uniffi.jellybeam_core.SeerrStatus

/** Pure decision functions behind Seerr Discover's Kotlin UI (docs/14-seerr-discover.md);
 * server-owned decisions (availability mapping, requestability, POST-vs-PUT, URL probing) live
 * in Rust. */

// -- Availability badge (Discover home/grid poster corner) -------------------

/** [tv.jellybeam.ui.cards.WatchBadge]'s three-way shape mapped from [SeerrAvailability] (docs/14):
 * check = available, dot = pending/processing, plus a half badge for partially-available. */
sealed interface SeerrAvailabilityBadge {
    data object None : SeerrAvailabilityBadge
    data object Check : SeerrAvailabilityBadge
    data object Dot : SeerrAvailabilityBadge
    data object Half : SeerrAvailabilityBadge
}

// -- Detail meta line ------------------------------------------------------

/** Detail meta line's runtime figure, or `null` to omit -- zero/negative treated the same as
 * `null`. */
fun displayRuntimeMinutes(minutes: Int?): Int? = minutes?.takeIf { it > 0 }

fun availabilityBadge(availability: SeerrAvailability): SeerrAvailabilityBadge = when (availability) {
    SeerrAvailability.NOT_REQUESTED -> SeerrAvailabilityBadge.None
    SeerrAvailability.PENDING, SeerrAvailability.PROCESSING -> SeerrAvailabilityBadge.Dot
    SeerrAvailability.PARTIALLY_AVAILABLE -> SeerrAvailabilityBadge.Half
    SeerrAvailability.AVAILABLE -> SeerrAvailabilityBadge.Check
}

// -- Grid sort chips -----------------------------------------------------

/** The Movies/TV grid's sort ladder (docs/14); Trending/Upcoming have no sort row. [POPULARITY]
 * is the default/first chip. */
enum class DiscoverSortOption { POPULARITY, RELEASE_DATE, RATING, TITLE_ASC, TITLE_DESC }

/** Display order for the sort chip row -- Popularity first (the default), per docs/14. */
val DISCOVER_SORT_OPTIONS: List<DiscoverSortOption> = listOf(
    DiscoverSortOption.POPULARITY,
    DiscoverSortOption.RELEASE_DATE,
    DiscoverSortOption.RATING,
    DiscoverSortOption.TITLE_ASC,
    DiscoverSortOption.TITLE_DESC,
)

/** The `sortBy` query-param value Seerr expects (docs/14); movie vs. TV pick a different field
 * name for date and title sorts. */
fun DiscoverSortOption.sortByValue(mediaType: SeerrMediaType): String {
    val isMovie = mediaType == SeerrMediaType.MOVIE
    return when (this) {
        DiscoverSortOption.POPULARITY -> "popularity.desc"
        DiscoverSortOption.RELEASE_DATE -> if (isMovie) "primary_release_date.desc" else "first_air_date.desc"
        DiscoverSortOption.RATING -> "vote_average.desc"
        DiscoverSortOption.TITLE_ASC -> if (isMovie) "original_title.asc" else "name.asc"
        DiscoverSortOption.TITLE_DESC -> if (isMovie) "original_title.desc" else "name.desc"
    }
}

// -- Grid/shelf paging -----------------------------------------------------

/** [tv.jellybeam.ui.library.LibraryScreen]'s "next page once scroll enters the last three rows"
 * rule, shared by Discover's grid. `hasMore = false` always answers `false`. */
fun shouldFetchNextDiscoverPage(lastVisibleIndex: Int, loadedCount: Int, columns: Int, hasMore: Boolean): Boolean {
    if (!hasMore || loadedCount == 0 || columns <= 0) return false
    return lastVisibleIndex >= loadedCount - columns * 3
}

// -- Drawer gating ---------------------------------------------------------

/** [tv.jellybeam.ui.nav.NavDrawerHost]'s "Discover" row gate -- present only when
 * `seerr_status().configured` (docs/14). */
fun shouldShowDiscoverDrawerEntry(status: SeerrStatus): Boolean = status.configured

// -- TV season request picker ----------------------------------------------

/** Request dialog's per-season checkbox state, keyed by season number; a non-requestable
 * season's entry is never consulted by [selectedRequestableSeasons]/[canSubmitSeasonRequest]. */
typealias SeasonSelection = Map<Int, Boolean>

/** Every requestable season starts unselected; a non-requestable season shows checked+inert
 * regardless of this map (the UI decides that off [SeerrSeasonStatus.requestable] directly). */
fun initialSeasonSelection(seasons: List<SeerrSeasonStatus>): SeasonSelection =
    seasons.filter { it.requestable }.associate { it.seasonNumber to false }

/** Flips one requestable season's checkbox; a non-requestable season's number is a no-op. */
fun toggleSeasonSelection(current: SeasonSelection, season: SeerrSeasonStatus): SeasonSelection {
    if (!season.requestable) return current
    val isSelected = current[season.seasonNumber] ?: false
    return current + (season.seasonNumber to !isSelected)
}

/** "Select all" over requestable seasons only: selects all unless every requestable season is
 * already selected, in which case it clears them all. */
fun toggleSelectAllRequestableSeasons(seasons: List<SeerrSeasonStatus>, current: SeasonSelection): SeasonSelection {
    val requestable = seasons.filter { it.requestable }
    if (requestable.isEmpty()) return current
    val allSelected = requestable.all { current[it.seasonNumber] == true }
    val target = !allSelected
    return current + requestable.associate { it.seasonNumber to target }
}

/** The season numbers an actual submit should send -- requestable and currently checked only. */
fun selectedRequestableSeasons(seasons: List<SeerrSeasonStatus>, selection: SeasonSelection): List<Int> =
    seasons.filter { it.requestable && selection[it.seasonNumber] == true }.map { it.seasonNumber }

/** Submit is disabled until at least one requestable season is selected (docs/14). */
fun canSubmitSeasonRequest(seasons: List<SeerrSeasonStatus>, selection: SeasonSelection): Boolean =
    selectedRequestableSeasons(seasons, selection).isNotEmpty()

// -- Becoming-top focus restore ---------------------------------------------

/** The stable key every Discover poster grid/shelf keys its cells by, kept in one place. */
fun seerrCardKey(mediaType: SeerrMediaType, tmdbId: Long): String = "$mediaType-$tmdbId"

/** Drops every later card whose [seerrCardKey] already appeared, keeping order (docs/14): a
 * LazyGrid/LazyRow throws on a repeated key, and Seerr repeats items across popularity-sorted
 * pages and in a person's cast-plus-crew credits. */
fun distinctSeerrCards(cards: List<SeerrCard>): List<SeerrCard> =
    cards.distinctBy { seerrCardKey(it.mediaType, it.tmdbId) }
