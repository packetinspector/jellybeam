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
}
