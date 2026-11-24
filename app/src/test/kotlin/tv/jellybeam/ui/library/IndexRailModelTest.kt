package tv.jellybeam.ui.library

import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.jellybeam.i18n.ResourceUiStrings
import uniffi.jellybeam_core.GridGroup
import uniffi.jellybeam_core.GridSort
import uniffi.jellybeam_core.GridSortField

class IndexRailModelTest {
    private val strings = ResourceUiStrings.default


    private val today: LocalDate = LocalDate.of(2026, 9, 6)

    private fun group(key: String, count: Long) = GridGroup(key, count.toULong())

    // ---- Name ---------------------------------------------------------------

    @Test
    fun `name ascending accumulates offsets and folds a non-contiguous second hash into the first`() {
        val groups = listOf(
            group("#", 2),
            group("A", 5),
            group("B", 3),
            group("Z", 1),
            group("#", 4),
        )
        val entries = IndexRailModel.build(strings, GridSort(GridSortField.NAME, descending = false), groups, today, Locale.US)

        assertEquals(27, entries.size)
        assertEquals(RailEntry("#", 0, 6), entries[0])
        assertEquals(RailEntry("A", 2, 5), entries[1])
        assertEquals(RailEntry("B", 7, 3), entries[2])
        assertEquals(RailEntry("C", 10, 0), entries[3])
        assertEquals(RailEntry("Y", 10, 0), entries[25])
        assertEquals(RailEntry("Z", 10, 1), entries[26])
    }

    @Test
    fun `name descending reverses the label order, hash last`() {
        val groups = listOf(
            group("Z", 3),
            group("Y", 2),
            group("A", 1),
            group("#", 4),
        )
        val entries = IndexRailModel.build(strings, GridSort(GridSortField.NAME, descending = true), groups, today, Locale.US)

        assertEquals(27, entries.size)
        assertEquals(RailEntry("Z", 0, 3), entries[0])
        assertEquals(RailEntry("Y", 3, 2), entries[1])
        assertEquals(RailEntry("X", 5, 0), entries[2])
        assertEquals(RailEntry("A", 5, 1), entries[25])
        assertEquals(RailEntry("#", 6, 4), entries[26])
    }

    // ---- Date added -----------------------------------------------------------

    @Test
    fun `date added folds current-year months, prior-year buckets, and OLD plus the empty key`() {
        val groups = listOf(
            group("2026-09", 5),
            group("2026-07", 3),
            group("2024-01", 2),
            group("2015-05", 1),
            group("", 4),
        )
        val entries = IndexRailModel.build(strings, GridSort(GridSortField.DATE_ADDED, descending = true), groups, today, Locale.US)

        val byLabel = entries.associateBy { it.label }
        assertEquals(15, entries.size)
        assertEquals(RailEntry("NOW", 0, 5), byLabel.getValue("NOW"))
        assertEquals(RailEntry("AUG", 5, 0), byLabel.getValue("AUG"))
        assertEquals(RailEntry("JUL", 5, 3), byLabel.getValue("JUL"))
        assertEquals(RailEntry("'25", 8, 0), byLabel.getValue("'25"))
        assertEquals(RailEntry("'24", 8, 2), byLabel.getValue("'24"))
        assertEquals(RailEntry("OLD", 10, 5), byLabel.getValue("OLD"))
        assertEquals("NOW", entries.first().label)
        assertEquals("OLD", entries.last().label)
    }

    // ---- Year -----------------------------------------------------------------

    @Test
    fun `year trims to the range between newest and oldest present decade`() {
        val groups = listOf(group("2020", 5), group("1990", 3))
        val entries = IndexRailModel.build(strings, GridSort(GridSortField.YEAR, descending = true), groups, today, Locale.US)

        assertEquals(listOf("2020s", "2010s", "2000s", "1990s"), entries.map { it.label })
        assertEquals(RailEntry("2020s", 0, 5), entries[0])
        assertEquals(RailEntry("2010s", 5, 0), entries[1])
        assertEquals(RailEntry("1990s", 5, 3), entries[3])
    }

    @Test
    fun `year includes OLD when the oldest present group is older than 1980, and reverses for ascending`() {
        val groups = listOf(group("1970", 2), group("2000", 1))
        val entries = IndexRailModel.build(strings, GridSort(GridSortField.YEAR, descending = false), groups, today, Locale.US)

        assertEquals(listOf("OLD", "1980s", "1990s", "2000s"), entries.map { it.label })
        assertEquals(RailEntry("OLD", 0, 2), entries[0])
        assertEquals(RailEntry("2000s", 2, 1), entries[3])
    }

    @Test
    fun `year with no groups returns the full natural set at zero count`() {
        val entries = IndexRailModel.build(strings, GridSort(GridSortField.YEAR, descending = false), emptyList(), today, Locale.US)

        assertEquals(listOf("OLD", "1980s", "1990s", "2000s", "2010s", "2020s"), entries.map { it.label })
        assertTrue(entries.all { it.count == 0 })
    }

    // ---- Runtime ----------------------------------------------------------------

    @Test
    fun `runtime bands fold the empty key into the last label in grid order`() {
        val groups = listOf(
            group("180", 2),
            group("90", 5),
            group("", 3),
        )
        val entries = IndexRailModel.build(strings, GridSort(GridSortField.RUNTIME, descending = true), groups, today, Locale.US)

        assertEquals(listOf("3h+", "2h30", "2h", "1h30", "1h", "30m", "<30m"), entries.map { it.label })
        assertEquals(RailEntry("3h+", 0, 2), entries[0])
        assertEquals(RailEntry("2h30", 2, 0), entries[1])
        assertEquals(RailEntry("1h30", 2, 5), entries[3])
        assertEquals(RailEntry("1h", 7, 0), entries[4])
        assertEquals(RailEntry("<30m", 7, 3), entries[6])
    }

    // ---- entryIndexForOffset ------------------------------------------------------

    @Test
    fun `entryIndexForOffset finds the entry containing the offset`() {
        val entries = listOf(
            RailEntry("A", 0, 5),
            RailEntry("B", 5, 0),
            RailEntry("C", 5, 3),
            RailEntry("D", 8, 0),
        )
        assertEquals(0, IndexRailModel.entryIndexForOffset(entries, 0))
        assertEquals(0, IndexRailModel.entryIndexForOffset(entries, 4))
        assertEquals(2, IndexRailModel.entryIndexForOffset(entries, 5))
        assertEquals(2, IndexRailModel.entryIndexForOffset(entries, 7))
    }

    @Test
    fun `entryIndexForOffset falls back to the nearest preceding non-empty entry in a trailing gap`() {
        val entries = listOf(
            RailEntry("A", 0, 5),
            RailEntry("B", 5, 0),
            RailEntry("C", 5, 3),
            RailEntry("D", 8, 0),
        )
        assertEquals(2, IndexRailModel.entryIndexForOffset(entries, 8))
    }

    @Test
    fun `entryIndexForOffset is zero for an empty list`() {
        assertEquals(0, IndexRailModel.entryIndexForOffset(emptyList(), 42))
    }

    // ---- one-letter steps (docs/16 §4.4, docs/31 §4.5) ----------------------

    private val stepEntries = listOf(
        RailEntry("#", 0, 0),
        RailEntry("A", 0, 4),
        RailEntry("B", 4, 0),
        RailEntry("C", 4, 2),
        RailEntry("D", 6, 0),
    )

    @Test
    fun `a letter step skips empty letters both ways`() {
        assertEquals(3, IndexRailModel.stepIndex(stepEntries, 1, forward = true))
        assertEquals(1, IndexRailModel.stepIndex(stepEntries, 3, forward = false))
    }

    @Test
    fun `a letter step stops at either end and past only empty letters`() {
        assertEquals(null, IndexRailModel.stepIndex(stepEntries, 1, forward = false))
        assertEquals(null, IndexRailModel.stepIndex(stepEntries, 3, forward = true))
        assertEquals(null, IndexRailModel.stepIndex(emptyList(), 0, forward = true))
    }

    // ---- remote letter steps (docs/31 §4.5) --------------------------------

    private val letters = listOf(RailEntry("A", 0, 2), RailEntry("B", 2, 0), RailEntry("C", 2, 3), RailEntry("D", 5, 1))

    @Test
    fun `a step sent while the last one is still landing walks on from that letter`() {
        assertEquals(3, IndexRailModel.remoteStep(letters, railIndex = 0, gridEntry = 0, railFocused = false, landingAt = 2, forward = true))
    }

    @Test
    fun `with no step landing a step walks from the grid's poster, not a stale rail entry`() {
        assertEquals(2, IndexRailModel.remoteStep(letters, railIndex = 3, gridEntry = 0, railFocused = false, landingAt = null, forward = true))
    }

    @Test
    fun `while the rail holds focus a step walks from the rail's entry`() {
        assertEquals(0, IndexRailModel.remoteStep(letters, railIndex = 2, gridEntry = 3, railFocused = true, landingAt = 3, forward = false))
    }
}
