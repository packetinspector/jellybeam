package tv.jellybeam.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.jellybeam_core.TrickplayTileFfi

/** A [Clock] a test can advance deterministically instead of racing [System.currentTimeMillis];
 * named distinctly from [PlaybackOsdControllerTest]'s `FakeClock` to avoid a same-package
 * redeclaration.
 */
private class FakeSeekClock(var nowMs: Long = 0L) : Clock {
    override fun nowMs(): Long = nowMs
}

private fun sampleTile(imageIndex: UInt = 0u, x: UInt = 0u, y: UInt = 0u) =
    TrickplayTileFfi(imageIndex = imageIndex, x = x, y = y)

class TrickplaySeekPreviewControllerTest {

    private val clock = FakeSeekClock()
    private val controller = TrickplaySeekPreviewController(clock = clock, visibleForMs = 1_500L)

    @Test
    fun `starts with no preview shown`() {
        assertNull(controller.preview)
    }

    @Test
    fun `show sets the preview to the given target and tile`() {
        val tile = sampleTile(imageIndex = 2u, x = 640u, y = 0u)

        controller.show(targetPositionMs = 45_000L, tile = tile)

        assertEquals(TrickplaySeekPreviewController.Preview(45_000L, tile), controller.preview)
    }

    @Test
    fun `does not hide before the visibility window elapses`() {
        controller.show(30_000L, sampleTile())
        clock.nowMs += 1_499L
        controller.tick()
        assertEquals(sampleTile(), controller.preview?.tile)
    }

    @Test
    fun `hides once the visibility window elapses`() {
        controller.show(30_000L, sampleTile())
        clock.nowMs += 1_500L
        controller.tick()
        assertNull(controller.preview)
    }

    @Test
    fun `a second show before expiry restarts the visibility window against the new tile`() {
        controller.show(10_000L, sampleTile(imageIndex = 0u))
        clock.nowMs += 1_000L

        controller.show(20_000L, sampleTile(imageIndex = 1u))
        clock.nowMs += 1_000L
        controller.tick()
        assertEquals(
            "the second show's own 1500ms window has not elapsed yet (only 1000ms since it fired)",
            TrickplaySeekPreviewController.Preview(20_000L, sampleTile(imageIndex = 1u)),
            controller.preview,
        )

        clock.nowMs += 500L
        controller.tick()
        assertNull(controller.preview)
    }

    @Test
    fun `clear hides the preview immediately regardless of the visibility window`() {
        controller.show(30_000L, sampleTile())
        controller.clear()
        assertNull(controller.preview)
    }

    @Test
    fun `tick is a no-op when nothing is shown`() {
        clock.nowMs += 10_000L
        controller.tick()
        assertNull(controller.preview)
    }
}
