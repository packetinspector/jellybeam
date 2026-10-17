package tv.jellybeam.ui.discover

import uniffi.jellybeam_core.SeerrActiveRequest
import uniffi.jellybeam_core.SeerrAvailability
import uniffi.jellybeam_core.SeerrCard
import uniffi.jellybeam_core.SeerrMediaType
import uniffi.jellybeam_core.SeerrRequestDetail
import uniffi.jellybeam_core.SeerrSeasonStatus

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

// -- Post-mutation detail merge ----------------------------------------------

/** The request fields a submit/cancel returns for either kind of title; `seasons` is the current list for a movie. */
private class RequestFields(
    val card: SeerrCard,
    val activeRequest: SeerrActiveRequest?,
    val canRequest: Boolean,
    val canRequest4k: Boolean,
    val seasons: List<SeerrSeasonStatus>,
)

/**
 * Takes a submit/cancel's returned request fields (card, active request, requestability and, for
 * TV, seasons) into the detail on screen; the returned detail has no similar/recommendations/scores,
 * so everything else stays (docs/14 Request semantics).
 */
internal fun DiscoverDetailUiState.withMutation(detail: SeerrRequestDetail): DiscoverDetailUiState {
    val fields = when (detail) {
        is SeerrRequestDetail.Movie -> detail.detail.let { RequestFields(it.card, it.activeRequest, it.canRequest, it.canRequest4k, seasons) }
        is SeerrRequestDetail.Tv -> detail.detail.let { RequestFields(it.card, it.activeRequest, it.canRequest, it.canRequest4k, it.seasons) }
    }
    return copy(
        card = fields.card,
        activeRequest = fields.activeRequest,
        canRequest = fields.canRequest,
        canRequest4k = fields.canRequest4k,
        seasons = fields.seasons,
        seasonSelection = seasonSelectionFor(seasons, fields.seasons, seasonSelection),
    )
}

/** The picker state after the season list changes from [old] to [new]: picks survive an unchanged list, else start over. */
internal fun seasonSelectionFor(old: List<SeerrSeasonStatus>, new: List<SeerrSeasonStatus>, selection: SeasonSelection): SeasonSelection =
    if (old == new) selection else initialSeasonSelection(new)

/** Both request dialogs closed; what a landed submit or cancel leaves behind (docs/14). */
internal fun DiscoverDetailUiState.dialogsClosed(): DiscoverDetailUiState =
    copy(requestDialogVisible = false, requestOptions = null, cancelDialogVisible = false)

// -- Detail action row focus ----------------------------------------------

/** Stable per-action ids, keyed `"action:<id>"` in the page's focus memory; independent of display
 * order so a remembered action survives a row recomposition. */
internal const val ACTION_GO_TO_LIBRARY = "go-to-library"
internal const val ACTION_CANCEL_REQUEST = "cancel-request"
internal const val ACTION_REQUEST = "request"
internal const val ACTION_REQUEST_4K = "request-4k"
internal const val ACTION_TRAILER = "trailer"

/** One action-row pill as the state decides it; a non-[actionable] pill stays focusable but ignores Select (docs/15 §4). */
internal data class ActionSlot(val id: String, val actionable: Boolean, val primary: Boolean = false)

/** The action row, in display order; the one source for both the pills and their focus targets. */
internal fun DiscoverDetailUiState.actionSlots(): List<ActionSlot> = buildList {
    val libraryItem = card?.jellyfinItemId
    if (libraryItem != null) add(ActionSlot(ACTION_GO_TO_LIBRARY, actionable = true, primary = true))
    if (activeRequest != null) add(ActionSlot(ACTION_CANCEL_REQUEST, actionable = !busy))
    if (canRequest) add(ActionSlot(ACTION_REQUEST, actionable = !busy, primary = libraryItem == null))
    if (canRequest4k) add(ActionSlot(ACTION_REQUEST_4K, actionable = !busy))
    if (trailerUrl != null) add(ActionSlot(ACTION_TRAILER, actionable = true))
}

/** Focus keys (`"action:<id>"`) of every pill on the row, busy or not: none ever drops out of focus (docs/15 §4). */
internal fun DiscoverDetailUiState.actionKeys(): List<String> = actionSlots().map { "action:${it.id}" }

private val REQUEST_SLOT_KEYS = listOf("action:$ACTION_REQUEST", "action:$ACTION_REQUEST_4K", "action:$ACTION_CANCEL_REQUEST")

/**
 * Where focus lands once a dialog or a vanished pill took it (docs/15 §4): nowhere while the page
 * still holds focus, else the invoker if it is still there, else the request-family pill that
 * replaced it (Request, Request 4K and Cancel request swap in one slot), else the first pill.
 */
internal fun actionFocusKeys(invokerKey: String?, visibleActionKeys: List<String>, pageHasFocus: Boolean = false): List<String> {
    if (pageHasFocus) return emptyList()
    val slotMates = if (invokerKey in REQUEST_SLOT_KEYS) visibleActionKeys.filter { it in REQUEST_SLOT_KEYS } else emptyList()
    return (listOfNotNull(invokerKey).filter { it in visibleActionKeys } + slotMates + visibleActionKeys).distinct()
}
