package tv.jellybeam.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [resolveInvokerReturn] backs [tv.jellybeam.player.PlaybackScreen]'s
 * `restoreInvokerFocus` (docs/15-focus-and-selection.md §4).
 */
class PlaybackScreenInvokerFocusTest {

    private val buttons = listOf(ControlButton.SKIP_BACK, ControlButton.PLAY_PAUSE, ControlButton.SKIP_FORWARD, ControlButton.TRACKS)

    @Test
    fun `an invoker still in the visible set wins`() {
        assertEquals(ControlButton.TRACKS, resolveInvokerReturn(ControlButton.TRACKS, buttons))
        assertEquals(ControlButton.SKIP_BACK, resolveInvokerReturn(ControlButton.SKIP_BACK, buttons))
    }

    @Test
    fun `an invoker no longer in the visible set falls back to PLAY_PAUSE`() {
        assertEquals(ControlButton.PLAY_PAUSE, resolveInvokerReturn(ControlButton.CHAPTERS, buttons))
    }

    @Test
    fun `a null invoker falls back to PLAY_PAUSE too`() {
        assertEquals(ControlButton.PLAY_PAUSE, resolveInvokerReturn(null, buttons))
    }

    @Test
    fun `PLAY_PAUSE absent from the visible set falls back to the first button`() {
        val noPlayPause = listOf(ControlButton.TRACKS, ControlButton.LIBRARY_INFO)
        assertEquals(ControlButton.TRACKS, resolveInvokerReturn(null, noPlayPause))
        assertEquals(ControlButton.TRACKS, resolveInvokerReturn(ControlButton.CHAPTERS, noPlayPause))
    }

    @Test
    fun `an empty visible set resolves to null regardless of invoker`() {
        assertNull(resolveInvokerReturn(ControlButton.PLAY_PAUSE, emptyList()))
        assertNull(resolveInvokerReturn(null, emptyList()))
    }

    @Test
    fun `a focused button that left the row moves the ring to PLAY_PAUSE, else the first button`() {
        assertEquals(ControlButton.PLAY_PAUSE, reconcileFocusedButton(ControlButton.CHAPTERS, buttons))
        assertEquals(ControlButton.TRACKS, reconcileFocusedButton(ControlButton.CHAPTERS, listOf(ControlButton.TRACKS)))
        assertNull(reconcileFocusedButton(ControlButton.CHAPTERS, emptyList()))
    }

    @Test
    fun `reconcile keeps a present button and a cleared ring`() {
        assertEquals(ControlButton.TRACKS, reconcileFocusedButton(ControlButton.TRACKS, buttons))
        assertNull(reconcileFocusedButton(null, buttons))
    }
}
