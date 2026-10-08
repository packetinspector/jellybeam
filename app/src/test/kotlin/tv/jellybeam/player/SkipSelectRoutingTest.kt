package tv.jellybeam.player

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkipSelectRoutingTest {
    private fun route(
        osdVisible: Boolean = false,
        pillFocused: Boolean = false,
        segmentShown: Boolean = false,
        undoShown: Boolean = false,
        nextUpShown: Boolean = false,
        stillWatchingShown: Boolean = false,
    ) = SkipSelectRouting.resolveSelect(osdVisible, pillFocused, segmentShown, undoShown, nextUpShown, stillWatchingShown)

    @Test
    fun `a card outranks Undo in both branches, so Select never undoes under a card`() {
        assertEquals(SelectRoute.REVEAL_OSD, route(undoShown = true, nextUpShown = true))
        assertEquals(SelectRoute.REVEAL_OSD, route(undoShown = true, stillWatchingShown = true))
        assertEquals(SelectRoute.PLAY_NEXT, route(osdVisible = true, undoShown = true, nextUpShown = true))
        assertEquals(SelectRoute.ACTIVATE_FOCUSED, route(osdVisible = true, undoShown = true, stillWatchingShown = true))
        for (osd in listOf(true, false)) {
            for (card in listOf(true to false, false to true, true to true)) {
                val r = route(osdVisible = osd, undoShown = true, segmentShown = true, nextUpShown = card.first, stillWatchingShown = card.second)
                assertFalse(r == SelectRoute.UNDO)
            }
        }
    }

    @Test
    fun `a skip leaves the OSD alone over a card, a player menu, or a pause`() {
        assertTrue(SkipSelectRouting.skipHidesOsd(osdVisible = true, menuOpen = false, cardShown = false, paused = false))
        assertFalse(SkipSelectRouting.skipHidesOsd(osdVisible = true, menuOpen = false, cardShown = true, paused = false))
        assertFalse(SkipSelectRouting.skipHidesOsd(osdVisible = true, menuOpen = true, cardShown = false, paused = false))
        assertFalse("paused pins the OSD; hiding it would strand a paused player behind Undo", SkipSelectRouting.skipHidesOsd(osdVisible = true, menuOpen = false, cardShown = false, paused = true))
        assertFalse(SkipSelectRouting.skipHidesOsd(osdVisible = false, menuOpen = false, cardShown = false, paused = false))
    }

    @Test
    fun `the Undo toast is drawn only with a hidden OSD and no card`() {
        assertTrue(SkipSelectRouting.undoToastVisible(undoShown = true, osdVisible = false, cardShown = false))
        assertFalse(SkipSelectRouting.undoToastVisible(undoShown = true, osdVisible = false, cardShown = true))
        assertFalse(SkipSelectRouting.undoToastVisible(undoShown = true, osdVisible = true, cardShown = false))
        assertFalse(SkipSelectRouting.undoToastVisible(undoShown = false, osdVisible = false, cardShown = false))
    }

    @Test
    fun `pill with hidden OSD skips on one Select`() {
        assertEquals(SelectRoute.SKIP_SEGMENT, route(segmentShown = true))
    }

    @Test
    fun `visible OSD activates the focused button even with the pill showing`() {
        assertEquals(SelectRoute.ACTIVATE_FOCUSED, route(osdVisible = true, segmentShown = true))
    }

    @Test
    fun `visible OSD activates the focused button even with the toast showing`() {
        assertEquals(SelectRoute.ACTIVATE_FOCUSED, route(osdVisible = true, undoShown = true))
    }

    @Test
    fun `focused pill under a visible OSD skips`() {
        assertEquals(SelectRoute.SKIP_SEGMENT, route(osdVisible = true, pillFocused = true, segmentShown = true))
    }

    @Test
    fun `pillFocused without a shown pill falls back to the focused button`() {
        assertEquals(SelectRoute.ACTIVATE_FOCUSED, route(osdVisible = true, pillFocused = true))
    }

    @Test
    fun `toast with hidden OSD undoes, ahead of a pill`() {
        assertEquals(SelectRoute.UNDO, route(undoShown = true))
        assertEquals(SelectRoute.UNDO, route(undoShown = true, segmentShown = true))
    }

    @Test
    fun `after a focused-pill skip hides the OSD the next Select undoes`() {
        assertEquals(SelectRoute.SKIP_SEGMENT, route(osdVisible = true, pillFocused = true, segmentShown = true))
        assertEquals(SelectRoute.UNDO, route(osdVisible = false, undoShown = true))
    }

    @Test
    fun `after an undo leaves the OSD hidden a returning pill skips on one Select`() {
        assertEquals(SelectRoute.UNDO, route(osdVisible = false, undoShown = true))
        assertEquals(SelectRoute.SKIP_SEGMENT, route(osdVisible = false, segmentShown = true))
    }

    @Test
    fun `hidden OSD with nothing to act on only reveals`() {
        assertEquals(SelectRoute.REVEAL_OSD, route())
    }

    @Test
    fun `next-up card keeps precedence over pill and focused button`() {
        assertEquals(SelectRoute.PLAY_NEXT, route(osdVisible = true, nextUpShown = true))
        assertEquals(SelectRoute.PLAY_NEXT, route(osdVisible = true, pillFocused = true, segmentShown = true, nextUpShown = true))
        assertEquals(SelectRoute.REVEAL_OSD, route(nextUpShown = true, segmentShown = true))
    }

    @Test
    fun `only arrow keys dismiss the toast`() {
        for (k in listOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight)) {
            assertTrue(SkipSelectRouting.dismissesUndoToast(k))
        }
        for (k in listOf(Key.DirectionCenter, Key.Enter, Key.Menu, Key.Back, Key.PageUp)) {
            assertFalse(SkipSelectRouting.dismissesUndoToast(k))
        }
    }

    @Test
    fun `Up on a visible OSD with a pill focuses the pill`() {
        assertEquals(PillFocusMove.FOCUS_PILL, SkipSelectRouting.pillFocusMove(Key.DirectionUp, true, false, true))
    }

    @Test
    fun `Up does not focus the pill when hidden, absent or already focused`() {
        assertEquals(PillFocusMove.NONE, SkipSelectRouting.pillFocusMove(Key.DirectionUp, false, false, true))
        assertEquals(PillFocusMove.NONE, SkipSelectRouting.pillFocusMove(Key.DirectionUp, true, false, false))
        assertEquals(PillFocusMove.NONE, SkipSelectRouting.pillFocusMove(Key.DirectionUp, true, true, true))
    }

    @Test
    fun `Down Left Right from the focused pill return to the buttons`() {
        for (k in listOf(Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight)) {
            assertEquals(PillFocusMove.RETURN_TO_BUTTONS, SkipSelectRouting.pillFocusMove(k, true, true, true))
            assertEquals(PillFocusMove.NONE, SkipSelectRouting.pillFocusMove(k, true, false, true))
        }
    }

    @Test
    fun `pill focus drops when the pill vanishes or the OSD hides`() {
        assertTrue(SkipSelectRouting.pillStaysFocused(true, true, true))
        assertFalse(SkipSelectRouting.pillStaysFocused(true, true, false))
        assertFalse(SkipSelectRouting.pillStaysFocused(true, false, true))
        assertFalse(SkipSelectRouting.pillStaysFocused(false, true, true))
    }

    @Test
    fun `the pill only exists with no card and no menu over it, so Select and Up ignore a hidden one`() {
        assertTrue(SkipSelectRouting.pillVisible(segmentActive = true, cardShown = false, menuOpen = false))
        assertFalse(SkipSelectRouting.pillVisible(segmentActive = true, cardShown = true, menuOpen = false))
        assertFalse(SkipSelectRouting.pillVisible(segmentActive = true, cardShown = false, menuOpen = true))
        assertFalse(SkipSelectRouting.pillVisible(segmentActive = false, cardShown = false, menuOpen = false))
        // A still-watching card hides the pill: hidden-OSD Select reveals instead of skipping.
        val hiddenPill = SkipSelectRouting.pillVisible(segmentActive = true, cardShown = true, menuOpen = false)
        assertEquals(SelectRoute.REVEAL_OSD, route(segmentShown = hiddenPill))
        assertEquals(PillFocusMove.NONE, SkipSelectRouting.pillFocusMove(Key.DirectionUp, true, false, hiddenPill))
    }
}
