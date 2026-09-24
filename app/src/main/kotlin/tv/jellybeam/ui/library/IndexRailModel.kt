package tv.jellybeam.ui.library

import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale
import uniffi.jellybeam_core.GridGroup
import uniffi.jellybeam_core.GridSort
import uniffi.jellybeam_core.GridSortField

/** One index-rail entry: a jump label plus the grid offset range it covers. */
data class RailEntry(val label: String, val offset: Int, val count: Int)

/** Pure Kotlin model for the library index rail (docs/16-library-sort-filter.md §4.4),
 * plain-JVM-testable like [RecordingFormatting]. [GridGroup]s arrive from the mirror already in
 * grid order; this model buckets each group's key into a fixed, per-field label and accumulates
 * offsets by walking the groups in that order.
 */
object IndexRailModel {

    private val RUNTIME_BANDS_ASCENDING = listOf("<30m", "30m", "1h", "1h30", "2h", "2h30", "3h+")
    private val RUNTIME_KEYS = listOf("0", "30", "60", "90", "120", "150", "180")
    private val YEAR_LABELS_NATURAL = listOf("2020s", "2010s", "2000s", "1990s", "1980s", "OLD")

    /** The rail's entries, in grid order, for the current [sort] and (already-filtered) [groups].
     * Every label in the field's set is always present, `count == 0` when empty; Year additionally
     * trims to the decades between the newest and oldest present group (§4.4), and an empty
     * [groups] returns the full natural Year set at zero count.
     */
    fun build(sort: GridSort, groups: List<GridGroup>, today: LocalDate): List<RailEntry> {
        val naturalLabels = naturalLabels(sort.field, today)

        // First pass: accumulate each label's first-member offset and total count -- labels can
        // recur non-contiguously.
        val offsets = LinkedHashMap<String, Int>()
        val counts = LinkedHashMap<String, Int>()
        var running = 0
        for (group in groups) {
            val label = keyToLabel(sort.field, group.key, today)
            if (label !in offsets) offsets[label] = running
            val count = group.count.toInt()
            counts[label] = (counts[label] ?: 0) + count
            running += count
        }

        val displayLabels = when (sort.field) {
            GridSortField.YEAR -> trimmedYearLabels(naturalLabels, counts, groups.isEmpty())
            else -> naturalLabels
        }
        val gridLabels = toGridOrder(displayLabels, sort)

        // RUNTIME's "" key always sorts last (§2.2), folding into whichever label is last in this
        // grid arrangement.
        if (sort.field == GridSortField.RUNTIME) {
            foldRuntimeEmptyKey(groups, gridLabels, offsets, counts)
        }

        // Second pass: labels with no member group get the running end of whichever label precedes
        // them.
        var lastEnd = 0
        val result = mutableListOf<RailEntry>()
        for (label in gridLabels) {
            val offset = offsets[label] ?: lastEnd
            val count = counts[label] ?: 0
            result += RailEntry(label, offset, count)
            lastEnd = offset + count
        }
        return result
    }

    /** The entry whose `[offset, offset + count)` contains [itemOffset]; the nearest preceding
     * non-empty entry inside a zero-count gap; `0` for an empty list.
     */
    fun entryIndexForOffset(entries: List<RailEntry>, itemOffset: Int): Int {
        if (entries.isEmpty()) return 0
        var candidate = 0
        for (i in entries.indices) {
            if (entries[i].offset <= itemOffset) candidate = i else break
        }
        val entry = entries[candidate]
        if (entry.count > 0 && itemOffset < entry.offset + entry.count) return candidate
        var i = candidate
        while (i > 0 && entries[i].count == 0) i--
        return i
    }

    // ---- label sets, in each field's *natural* order ----------------------

    private fun naturalLabels(field: GridSortField, today: LocalDate): List<String> = when (field) {
        GridSortField.NAME -> listOf("#") + ('A'..'Z').map { it.toString() }
        GridSortField.DATE_ADDED -> dateAddedNaturalLabels(today)
        GridSortField.YEAR -> YEAR_LABELS_NATURAL
        GridSortField.RUNTIME -> RUNTIME_BANDS_ASCENDING.reversed()
    }

    private fun dateAddedNaturalLabels(today: LocalDate): List<String> {
        val months = (today.monthValue - 1) downTo 1
        val monthLabels = months.map { monthLabel(it) }
        val years = (today.year - 1) downTo (today.year - 5)
        val yearLabels = years.map { "'" + "%02d".format(it % 100) }
        return listOf("NOW") + monthLabels + yearLabels + listOf("OLD")
    }

    private fun monthLabel(month: Int): String =
        Month.of(month).getDisplayName(TextStyle.SHORT, Locale.US).uppercase(Locale.US)

    // ---- key -> label ------------------------------------------------------

    private fun keyToLabel(field: GridSortField, key: String, today: LocalDate): String = when (field) {
        GridSortField.NAME -> key
        GridSortField.DATE_ADDED -> dateAddedLabel(key, today)
        GridSortField.YEAR -> yearLabel(key)
        GridSortField.RUNTIME -> RUNTIME_KEYS.indexOf(key).let { if (it >= 0) RUNTIME_BANDS_ASCENDING[it] else "" }
    }

    private fun dateAddedLabel(key: String, today: LocalDate): String {
        if (key.length != 7) return "OLD" // "" (NULL) or malformed
        val year = key.substring(0, 4).toIntOrNull() ?: return "OLD"
        val month = key.substring(5, 7).toIntOrNull() ?: return "OLD"
        val todayYm = "%04d-%02d".format(today.year, today.monthValue)
        return when {
            key == todayYm -> "NOW"
            year == today.year -> monthLabel(month)
            year in (today.year - 5)..(today.year - 1) -> "'" + "%02d".format(year % 100)
            else -> "OLD"
        }
    }

    private fun yearLabel(key: String): String {
        val year = key.toIntOrNull() ?: return "OLD"
        return when {
            year >= 2020 -> "2020s"
            year >= 2010 -> "2010s"
            year >= 2000 -> "2000s"
            year >= 1990 -> "1990s"
            year >= 1980 -> "1980s"
            else -> "OLD"
        }
    }

    // ---- Year trimming and grid-order reversal -----------------------------

    private fun trimmedYearLabels(natural: List<String>, counts: Map<String, Int>, groupsEmpty: Boolean): List<String> {
        if (groupsEmpty) return natural
        val presentIndices = natural.indices.filter { natural[it] in counts }
        if (presentIndices.isEmpty()) return natural
        val min = presentIndices.min()
        val max = presentIndices.max()
        return natural.subList(min, max + 1)
    }

    private fun toGridOrder(natural: List<String>, sort: GridSort): List<String> =
        if (sort.descending == GridSummaryFormat.naturalDescending(sort.field)) natural else natural.reversed()

    // ---- Runtime's dynamic "" (no-data) fold-in ----------------------------

    private fun foldRuntimeEmptyKey(
        groups: List<GridGroup>,
        gridLabels: List<String>,
        offsets: MutableMap<String, Int>,
        counts: MutableMap<String, Int>,
    ) {
        val lastLabel = gridLabels.lastOrNull() ?: return
        val emptyCount = groups.filter { it.key.isEmpty() }.sumOf { it.count.toInt() }
        if (emptyCount == 0) return
        // Remove the placeholder "" bucket and fold its count into lastLabel (offset unaffected,
        // §2.2).
        offsets.remove("")
        counts.remove("")
        counts[lastLabel] = (counts[lastLabel] ?: 0) + emptyCount
    }
}
