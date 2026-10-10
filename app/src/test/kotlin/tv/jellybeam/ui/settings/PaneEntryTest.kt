package tv.jellybeam.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** docs/15-focus-and-selection.md §3: which row/chip carries a section's pane entry. */
class PaneEntryTest {
    private val rows = listOf(
        PaneEntryRow("playback/a", canClaim = true),
        PaneEntryRow("playback/chip/x", canClaim = false),
        PaneEntryRow("playback/chip/y", canClaim = true),
    )

    @Test
    fun `a recorded key that is composed wins, claimable or not`() {
        assertEquals("playback/chip/x", resolvePaneEntryKey("playback/chip/x", rows))
    }

    @Test
    fun `a stale recorded key resolves to nothing, not the first row`() {
        assertNull(resolvePaneEntryKey("playback/gone", rows))
    }

    @Test
    fun `nothing recorded picks the first claimable row`() {
        assertEquals("playback/a", resolvePaneEntryKey(null, rows))
        assertEquals("playback/chip/y", resolvePaneEntryKey(null, rows.drop(1)))
    }

    @Test
    fun `nothing recorded and nothing claimable resolves to nothing`() {
        assertNull(resolvePaneEntryKey(null, listOf(PaneEntryRow("a", canClaim = false))))
        assertNull(resolvePaneEntryKey(null, emptyList()))
    }
}
