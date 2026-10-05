package tv.jellybeam.ui.library

import tv.jellybeam.R
import tv.jellybeam.i18n.UiStrings
import tv.jellybeam.i18n.uppercaseUi
import uniffi.jellybeam_core.Decade
import uniffi.jellybeam_core.GridCounts
import uniffi.jellybeam_core.GridFilters
import uniffi.jellybeam_core.GridSort
import uniffi.jellybeam_core.GridSortField
import uniffi.jellybeam_core.StatusFilter
import uniffi.jellybeam_core.WatchedFilter

/** What a grid's count segment counts (docs/16 §4.1, §2.7). */
enum class GridNoun { MOVIES, SHOWS, FAVORITES }

/** Pure Kotlin formatting for the library summary line and strip chips
 * (docs/16-library-sort-filter.md §4.1, §4.2), plain-JVM-testable like [RecordingFormatting].
 */
object GridSummaryFormat {

    /**
     * The summary line's segments, in strip order: the count segment (unfiltered `"<total>
     * MOVIES"`/`"<total> SHOWS"`, or filtered `"<filtered> OF <total>"`), the current sort (`"NAME
     * ↑"`
     * etc.), then any active filters -- watched, genre (verbatim, upper-cased), decade, status. A
     * filter
     * segment is present only when it departs from its default (`Any`/`null`).
     */
    fun segments(strings: UiStrings, counts: GridCounts, sort: GridSort, filters: GridFilters, noun: GridNoun): List<String> {
        val segments = mutableListOf<String>()

        val isFiltered = filters.watched != WatchedFilter.ANY ||
            filters.genre != null ||
            filters.decade != null ||
            filters.status != StatusFilter.ANY ||
            filters.itemType != null
        segments += if (isFiltered) {
            strings.get(R.string.library_summary_filtered, counts.filtered.toLong(), counts.total.toLong())
        } else {
            val total = counts.total
            val plural = when (noun) {
                GridNoun.MOVIES -> R.plurals.library_summary_movies
                GridNoun.SHOWS -> R.plurals.library_summary_shows
                GridNoun.FAVORITES -> R.plurals.library_summary_favorites
            }
            strings.plural(plural, total.coerceAtMost(Int.MAX_VALUE.toULong()).toInt(), total.toLong())
        }

        segments += "${sortFieldSummaryLabel(strings, sort.field)} ${arrow(sort.descending)}"

        when (filters.watched) {
            WatchedFilter.UNWATCHED -> segments += strings.get(R.string.library_watched_unwatched).uppercaseUi()
            WatchedFilter.HAS_UNWATCHED -> segments += strings.get(R.string.library_has_unwatched).uppercaseUi()
            WatchedFilter.WATCHED -> segments += strings.get(R.string.library_watched_watched).uppercaseUi()
            WatchedFilter.ANY -> Unit
        }
        filters.genre?.let { segments += it.uppercaseUi() }
        filters.decade?.let { segments += decadeSummarySegment(strings, it) }
        when (filters.status) {
            StatusFilter.CONTINUING -> segments += strings.get(R.string.library_status_continuing).uppercaseUi()
            StatusFilter.ENDED -> segments += strings.get(R.string.library_status_ended).uppercaseUi()
            StatusFilter.ANY -> Unit
        }
        filters.itemType?.let { segments += itemTypeLabel(strings, it).uppercaseUi() }

        return segments
    }

    /** docs/16 §2.7: a Type panel chip's label, plural; an unknown type shows verbatim. */
    fun itemTypeLabel(strings: UiStrings, itemType: String): String = when (itemType) {
        "Movie" -> strings.get(R.string.library_type_movies)
        "Series" -> strings.get(R.string.library_type_shows)
        "Season" -> strings.get(R.string.library_type_seasons)
        "Episode" -> strings.get(R.string.library_type_episodes)
        "BoxSet" -> strings.get(R.string.library_type_collections)
        else -> itemType
    }

    /** A sort chip's label: the field name, with the direction arrow appended only when [field] is
     * the currently active [sort]'s field.
     */
    fun sortChipLabel(strings: UiStrings, field: GridSortField, sort: GridSort): String {
        val base = sortFieldChipLabel(strings, field)
        return if (field == sort.field) "$base ${arrow(sort.descending)}" else base
    }

    /** A decade's chip/panel label -- `"2020s"` … `"1980s"`, `"Older"`. */
    fun decadeLabel(strings: UiStrings, decade: Decade): String = strings.get(
        when (decade) {
            Decade.D2020S -> R.string.library_decade_2020s
            Decade.D2010S -> R.string.library_decade_2010s
            Decade.D2000S -> R.string.library_decade_2000s
            Decade.D1990S -> R.string.library_decade_1990s
            Decade.D1980S -> R.string.library_decade_1980s
            Decade.OLDER -> R.string.library_decade_older
        },
    )

    /** Whether [field]'s natural (reset) direction is descending -- only Name is naturally
     * ascending.
     */
    fun naturalDescending(field: GridSortField): Boolean = field != GridSortField.NAME

    private fun sortFieldSummaryLabel(strings: UiStrings, field: GridSortField): String =
        sortFieldChipLabel(strings, field).uppercaseUi()

    private fun sortFieldChipLabel(strings: UiStrings, field: GridSortField): String = strings.get(
        when (field) {
            GridSortField.NAME -> R.string.library_sort_name
            GridSortField.DATE_ADDED -> R.string.library_sort_date_added
            GridSortField.YEAR -> R.string.library_sort_year
            GridSortField.RUNTIME -> R.string.library_sort_runtime
        },
    )

    /** The summary line's decade segment -- same as [decadeLabel] except `Older` reads `OLDER`. */
    private fun decadeSummarySegment(strings: UiStrings, decade: Decade): String {
        val label = decadeLabel(strings, decade)
        return if (decade == Decade.OLDER) label.uppercaseUi() else label
    }

    private fun arrow(descending: Boolean): String = if (descending) "↓" else "↑"
}
