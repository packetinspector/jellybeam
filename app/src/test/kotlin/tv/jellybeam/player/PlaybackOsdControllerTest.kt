package tv.jellybeam.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.jellybeam_core.SyncPlayPhase

/** A [Clock] a test can advance deterministically, instead of racing [System.currentTimeMillis].
 * Internal (not private) -- [PlaybackViewModelTest] reuses it too.
 */
internal class FakeClock(var nowMs: Long = 0L) : Clock {
    override fun nowMs(): Long = nowMs
}

class PlaybackOsdControllerTest {

    private val clock = FakeClock()

    // Uses the production default (docs/jellybeam-osd-handoff §10: "Auto-hide after 5s") rather than
    // an explicit idleTimeoutMs.
    private val controller = PlaybackOsdController(clock = clock)

    @Test
    fun `starts visible`() {
        assertTrue(controller.isVisible)
    }

    @Test
    fun `does not hide before the idle timeout elapses`() {
        clock.nowMs += 4_999L
        controller.tick()
        assertTrue(controller.isVisible)
    }

    @Test
    fun `hides once the idle timeout elapses with no input`() {
        clock.nowMs += 5_000L
        controller.tick()
        assertFalse(controller.isVisible)
    }

    @Test
    fun `any key event re-reveals and resets the idle clock`() {
        clock.nowMs += 5_000L
        controller.tick()
        assertFalse(controller.isVisible)

        controller.onKeyEvent()
        assertTrue(controller.isVisible)

        clock.nowMs += 4_999L
        controller.tick()
        assertTrue("should not re-hide until another full 5s of idle time passes", controller.isVisible)

        clock.nowMs += 1L
        controller.tick()
        assertFalse(controller.isVisible)
    }

    @Test
    fun `explicit hide takes effect immediately and a key can reveal again`() {
        controller.hide()
        assertFalse(controller.isVisible)

        controller.onKeyEvent()
        assertTrue(controller.isVisible)
    }

    @Test
    fun `first Enter while hidden only reveals, does not toggle`() {
        clock.nowMs += 5_000L
        controller.tick()
        assertFalse(controller.isVisible)

        val action = controller.onEnterPressed()

        assertEquals(PlaybackOsdController.EnterAction.REVEAL_ONLY, action)
        assertTrue(controller.isVisible)
    }

    @Test
    fun `Enter while already visible toggles play-pause`() {
        assertTrue(controller.isVisible)

        val action = controller.onEnterPressed()

        assertEquals(PlaybackOsdController.EnterAction.TOGGLE_PLAY_PAUSE, action)
        assertTrue(controller.isVisible)
    }

    @Test
    fun `paused pins the OSD visible past what would otherwise be the idle timeout`() {
        controller.onPausedChanged(true)

        clock.nowMs += 10_000L
        controller.tick()

        assertTrue(controller.isVisible)
    }

    @Test
    fun `pinned prevents hiding past what would otherwise be the idle timeout`() {
        controller.setPinned(true)

        clock.nowMs += 10_000L
        controller.tick()

        assertTrue(controller.isVisible)
    }

    @Test
    fun `unpinning re-arms idle auto-hide with a fresh window`() {
        controller.setPinned(true)
        clock.nowMs += 10_000L
        controller.tick()
        assertTrue(controller.isVisible)

        controller.setPinned(false)

        clock.nowMs += 4_999L
        controller.tick()
        assertTrue("unpinning should grant a fresh idle window, not reuse the already-overdue one", controller.isVisible)

        clock.nowMs += 1L
        controller.tick()
        assertFalse(controller.isVisible)
    }

    @Test
    fun `pinned while also paused still counts as never-hide once unpaused but still pinned`() {
        controller.onPausedChanged(true)
        controller.setPinned(true)
        controller.onPausedChanged(false)

        clock.nowMs += 10_000L
        controller.tick()

        assertTrue("pinned alone must keep the OSD visible even once no longer paused", controller.isVisible)
    }

    @Test
    fun `resuming from pause re-arms idle auto-hide with a fresh window`() {
        controller.onPausedChanged(true)
        clock.nowMs += 10_000L
        controller.tick()
        assertTrue(controller.isVisible)

        controller.onPausedChanged(false)

        clock.nowMs += 4_999L
        controller.tick()
        assertTrue("resume should grant a fresh idle window, not reuse the already-overdue one", controller.isVisible)

        clock.nowMs += 1L
        controller.tick()
        assertFalse(controller.isVisible)
    }

    @Test
    fun `solo reveals on either edge and a group only on a member's pause`() {
        val rows = listOf(
            Triple(true, null, true),
            Triple(false, null, true),
            Triple(true, SyncPlayPhase.PAUSED, true),
            Triple(true, SyncPlayPhase.WAITING, false),
            Triple(true, SyncPlayPhase.PLAYING, false),
            Triple(true, SyncPlayPhase.IDLE, false),
        ) + SyncPlayPhase.entries.map { Triple(false, it, false) }
        for ((paused, phase, reveals) in rows) {
            assertEquals("paused=$paused phase=$phase", reveals, pauseRevealsOsd(paused, phase))
        }
    }

    @Test
    fun `hidden through a group seek's Waiting pause and its resume, then the stall prompt's pin reveals`() {
        controller.hide()

        controller.onPausedChanged(true, SyncPlayPhase.WAITING)
        assertFalse("a group seek's Waiting hold must not reveal the OSD", controller.isVisible)

        controller.onPausedChanged(false, SyncPlayPhase.PLAYING)
        assertFalse("the group's resume after the seek must not reveal the OSD", controller.isVisible)

        controller.setPinned(true)
        assertTrue("the stall prompt's appearance must reveal the OSD", controller.isVisible)
    }

    @Test
    fun `twenty-two hidden group seeks leave the OSD hidden throughout`() {
        controller.hide()
        repeat(22) { tap ->
            controller.onPausedChanged(true, SyncPlayPhase.WAITING)
            clock.nowMs += 250L
            controller.tick()
            controller.onPausedChanged(false, SyncPlayPhase.PLAYING)
            clock.nowMs += 250L
            controller.tick()
            assertFalse("tap ${tap + 1}", controller.isVisible)
        }
    }

    @Test
    fun `a solo pause and a group member's pause still reveal a hidden OSD`() {
        controller.hide()
        controller.onPausedChanged(true)
        assertTrue(controller.isVisible)

        controller.hide()
        controller.onPausedChanged(true, SyncPlayPhase.PAUSED)
        assertTrue(controller.isVisible)
    }

    // ---- remote toggles (docs/31 §4.5) --------------------------------------

    @Test
    fun `a remote's OSD toggle hides a shown OSD and reveals a hidden one`() {
        assertEquals(OsdToggleStep.HIDE, osdToggleStep(osdVisible = true, OpenSurface.None))
        assertEquals(OsdToggleStep.REVEAL, osdToggleStep(osdVisible = false, OpenSurface.None))
    }

    @Test
    fun `a remote's OSD toggle leaves an open menu, sheet or delay strip alone`() {
        assertEquals(OsdToggleStep.UNAVAILABLE, osdToggleStep(osdVisible = true, OpenSurface.Speed))
        assertEquals(OsdToggleStep.UNAVAILABLE, osdToggleStep(osdVisible = false, OpenSurface.Chapters))
    }

    private val fullRow = listOf(ControlButton.PLAY_PAUSE, ControlButton.SPEED, ControlButton.TRACKS, ControlButton.CHAPTERS)

    @Test
    fun `a remote's menu toggle opens speed first, else chapters, else nothing`() {
        assertEquals(OsdMenuStep.OPEN_SPEED, osdMenuStep(OpenSurface.None, cardShown = false, fullRow))
        assertEquals(OsdMenuStep.OPEN_CHAPTERS, osdMenuStep(OpenSurface.None, cardShown = false, fullRow - ControlButton.SPEED))
        assertEquals(OsdMenuStep.UNAVAILABLE, osdMenuStep(OpenSurface.None, cardShown = false, listOf(ControlButton.PLAY_PAUSE, ControlButton.TRACKS)))
    }

    @Test
    fun `a remote's menu toggle closes whatever surface is open`() {
        assertEquals(OsdMenuStep.CLOSE, osdMenuStep(OpenSurface.Speed, cardShown = false, fullRow))
        assertEquals(OsdMenuStep.CLOSE, osdMenuStep(OpenSurface.Chapters, cardShown = true, fullRow))
    }

    @Test
    fun `a remote's menu toggle opens nothing over a next-up or still-watching card`() {
        assertEquals(OsdMenuStep.UNAVAILABLE, osdMenuStep(OpenSurface.None, cardShown = true, fullRow))
    }
}
