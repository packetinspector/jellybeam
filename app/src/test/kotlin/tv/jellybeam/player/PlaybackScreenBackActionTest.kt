package tv.jellybeam.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** [resolveBackAction] backs [tv.jellybeam.player.PlaybackScreen]'s `BackHandler` chain
 * (docs/jellybeam-osd-handoff/handoff/JELLYBEAM-TV-OSD-SPEC.md §10: menu → picker → sheet → next-up → OSD
 * → exit); pure Kotlin, exercised directly.
 */
class PlaybackScreenBackActionTest {

    @Test
    fun `an open menu takes priority over everything else`() {
        assertEquals(BackAction.CLOSE_MENU, resolveBackAction(pickerOpen = false, nextUpShown = false, osdVisible = true, menuOpen = true))
        assertEquals(BackAction.CLOSE_MENU, resolveBackAction(pickerOpen = true, nextUpShown = false, osdVisible = true, menuOpen = true))
        assertEquals(BackAction.CLOSE_MENU, resolveBackAction(pickerOpen = true, nextUpShown = true, osdVisible = false, menuOpen = true, sheetOpen = true))
    }

    @Test
    fun `picker closes before a sheet, next-up, OSD-wake, or exit once no menu is open`() {
        assertEquals(BackAction.CLOSE_PICKER, resolveBackAction(pickerOpen = true, nextUpShown = false, osdVisible = true))
        assertEquals(BackAction.CLOSE_PICKER, resolveBackAction(pickerOpen = true, nextUpShown = true, osdVisible = true))
        assertEquals(BackAction.CLOSE_PICKER, resolveBackAction(pickerOpen = true, nextUpShown = true, osdVisible = false))
        assertEquals(BackAction.CLOSE_PICKER, resolveBackAction(pickerOpen = true, nextUpShown = false, osdVisible = false))
        // Picker still wins even if a sheet were somehow also open (mutually exclusive in
        // practice).
        assertEquals(BackAction.CLOSE_PICKER, resolveBackAction(pickerOpen = true, nextUpShown = false, osdVisible = true, sheetOpen = true))
    }

    @Test
    fun `a sheet closes before still-watching, next-up, OSD-wake, or exit once menu and picker are closed`() {
        assertEquals(BackAction.CLOSE_SHEET, resolveBackAction(pickerOpen = false, nextUpShown = false, osdVisible = true, sheetOpen = true))
        assertEquals(BackAction.CLOSE_SHEET, resolveBackAction(pickerOpen = false, nextUpShown = true, osdVisible = true, sheetOpen = true))
        assertEquals(BackAction.CLOSE_SHEET, resolveBackAction(pickerOpen = false, nextUpShown = true, osdVisible = false, sheetOpen = true))
        assertEquals(
            BackAction.CLOSE_SHEET,
            resolveBackAction(pickerOpen = false, nextUpShown = false, osdVisible = true, sheetOpen = true, stillWatchingShown = true),
        )
    }

    /** docs/feature-dev/spec-still-watching-and-lan-discovery.md Feature A: still-watching outranks
     * next-up (mutually exclusive in practice).
     */
    @Test
    fun `still-watching Stop wins over next-up dismissal, OSD-wake, and exit once menu, picker, and sheet are closed`() {
        assertEquals(
            BackAction.STOP_STILL_WATCHING,
            resolveBackAction(pickerOpen = false, nextUpShown = false, osdVisible = true, stillWatchingShown = true),
        )
        assertEquals(
            BackAction.STOP_STILL_WATCHING,
            resolveBackAction(pickerOpen = false, nextUpShown = true, osdVisible = true, stillWatchingShown = true),
        )
        assertEquals(
            BackAction.STOP_STILL_WATCHING,
            resolveBackAction(pickerOpen = false, nextUpShown = false, osdVisible = false, stillWatchingShown = true),
        )
    }

    @Test
    fun `menuOpen, sheetOpen, and stillWatchingShown default to false`() {
        assertEquals(BackAction.HIDE_OSD, resolveBackAction(pickerOpen = false, nextUpShown = false, osdVisible = true))
    }

    @Test
    fun `next-up card dismissal wins over OSD-wake and exit once menu, picker, sheet, and still-watching are all closed`() {
        assertEquals(BackAction.DISMISS_NEXT_UP, resolveBackAction(pickerOpen = false, nextUpShown = true, osdVisible = true))
        assertEquals(BackAction.DISMISS_NEXT_UP, resolveBackAction(pickerOpen = false, nextUpShown = true, osdVisible = false))
    }

    /** `docs/feature-dev/PRD-hold-to-seek.md` §4.6: glide-cancel ranks between next-up dismissal
     * and OSD-hide, and wins even while the OSD is hidden.
     */
    @Test
    fun `an active glide cancels before OSD-wake or exit, and before next-up dismissal loses to it`() {
        assertEquals(BackAction.CANCEL_GLIDE, resolveBackAction(pickerOpen = false, nextUpShown = false, osdVisible = true, glideActive = true))
        assertEquals(BackAction.CANCEL_GLIDE, resolveBackAction(pickerOpen = false, nextUpShown = false, osdVisible = false, glideActive = true))
        assertEquals(
            BackAction.DISMISS_NEXT_UP,
            resolveBackAction(pickerOpen = false, nextUpShown = true, osdVisible = false, glideActive = true),
        )
        assertEquals(
            BackAction.CLOSE_MENU,
            resolveBackAction(pickerOpen = false, nextUpShown = false, osdVisible = false, menuOpen = true, glideActive = true),
        )
    }

    @Test
    fun `glideActive defaults to false`() {
        assertEquals(BackAction.HIDE_OSD, resolveBackAction(pickerOpen = false, nextUpShown = false, osdVisible = true))
    }

    @Test
    fun `a visible OSD hides before playback can exit`() {
        assertEquals(BackAction.HIDE_OSD, resolveBackAction(pickerOpen = false, nextUpShown = false, osdVisible = true))
    }

    @Test
    fun `exit is the last resort -- nothing else showing and the OSD already hidden`() {
        assertEquals(BackAction.EXIT, resolveBackAction(pickerOpen = false, nextUpShown = false, osdVisible = false))
    }
}
