package tv.jellybeam.ui.library

import java.util.Locale
import tv.jellybeam.ui.common.countLabel
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
    fun segments(counts: GridCounts, sort: GridSort, filters: GridFilters, noun: GridNoun): List<String> {
        val segments = mutableListOf<String>()

        val isFiltered = filters.watched != WatchedFilter.ANY ||
            filters.genre != null ||
            filters.decade != null ||
            filters.status != StatusFilter.ANY ||
            filters.itemType != null
        segments += if (isFiltered) {
            "${counts.filtered} OF ${counts.total}"
        } else {
            when (noun) {
                GridNoun.MOVIES -> countLabel(counts.total, "MOVIE", "MOVIES")
                GridNoun.SHOWS -> countLabel(counts.total, "SHOW", "SHOWS")
                GridNoun.FAVORITES -> countLabel(counts.total, "FAVORITE", "FAVORITES")
            }
        }

        segments += "${sortFieldSummaryLabel(sort.field)} ${arrow(sort.descending)}"

        when (filters.watched) {
            WatchedFilter.UNWATCHED -> segments += "UNWATCHED"
            WatchedFilter.HAS_UNWATCHED -> segments += "HAS UNWATCHED"
            WatchedFilter.WATCHED -> segments += "WATCHED"
            WatchedFilter.ANY -> Unit
        }
        filters.genre?.let { segments += it.uppercase(Locale.US) }
        filters.decade?.let { segments += decadeSummarySegment(it) }
        when (filters.status) {
            StatusFilter.CONTINUING -> segments += "CONTINUING"
            StatusFilter.ENDED -> segments += "ENDED"
            StatusFilter.ANY -> Unit
        }
        filters.itemType?.let { segments += itemTypeLabel(it).uppercase(Locale.US) }

        return segments
    }

    /** docs/16 §2.7: a Type panel chip's label, plural; an unknown type shows verbatim. */
    fun itemTypeLabel(itemType: String): String = when (itemType) {
        "Movie" -> "Movies"
        "Series" -> "Shows"
        "Season" -> "Seasons"
        "Episode" -> "Episodes"
        "BoxSet" -> "Collections"
        else -> itemType
    }

    /** A sort chip's label: the field name, with the direction arrow appended only when [field] is
     * the currently active [sort]'s field.
     */
    fun sortChipLabel(field: GridSortField, sort: GridSort): String {
        val base = sortFieldChipLabel(field)
        return if (field == sort.field) "$base ${arrow(sort.descending)}" else base
    }

    /** A decade's chip/panel label -- `"2020s"` … `"1980s"`, `"Older"`. */
    fun decadeLabel(decade: Decade): String = when (decade) {
        Decade.D2020S -> "2020s"
        Decade.D2010S -> "2010s"
        Decade.D2000S -> "2000s"
        Decade.D1990S -> "1990s"
        Decade.D1980S -> "1980s"
        Decade.OLDER -> "Older"
    }

    /** Whether [field]'s natural (reset) direction is descending -- only Name is naturally
     * ascending.
     */
    fun naturalDescending(field: GridSortField): Boolean = field != GridSortField.NAME

    private fun sortFieldSummaryLabel(field: GridSortField): String = when (field) {
        GridSortField.NAME -> "NAME"
        GridSortField.DATE_ADDED -> "DATE ADDED"
        GridSortField.YEAR -> "YEAR"
        GridSortField.RUNTIME -> "RUNTIME"
    }

    private fun sortFieldChipLabel(field: GridSortField): String = when (field) {
        GridSortField.NAME -> "Name"
        GridSortField.DATE_ADDED -> "Date added"
        GridSortField.YEAR -> "Year"
        GridSortField.RUNTIME -> "Runtime"
    }

    /** The summary line's decade segment -- same as [decadeLabel] except `Older` reads `OLDER`. */
    private fun decadeSummarySegment(decade: Decade): String {
        val label = decadeLabel(decade)
        return if (decade == Decade.OLDER) label.uppercase(Locale.US) else label
    }

    private fun arrow(descending: Boolean): String = if (descending) "↓" else "↑"
}
