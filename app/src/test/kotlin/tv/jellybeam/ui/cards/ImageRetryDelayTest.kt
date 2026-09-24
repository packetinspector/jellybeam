package tv.jellybeam.ui.cards

import org.junit.Assert.assertEquals
import org.junit.Test

/** [CardArtImage]'s bounded-retry schedule (2s, 4s, 8s for attempts 0, 1, 2) -- see
 * [imageRetryDelayMs]'s doc.
 */
class ImageRetryDelayTest {

    @Test
    fun `first attempt waits the base delay`() {
        assertEquals(2000L, imageRetryDelayMs(0))
    }

    @Test
    fun `second attempt doubles the base delay`() {
        assertEquals(4000L, imageRetryDelayMs(1))
    }

    @Test
    fun `third attempt quadruples the base delay`() {
        assertEquals(8000L, imageRetryDelayMs(2))
    }

    @Test
    fun `delay keeps doubling past the configured retry ceiling`() {
        // No ceiling inside the function -- IMAGE_LOAD_MAX_RETRIES is what stops the caller.
        assertEquals(16000L, imageRetryDelayMs(3))
    }
}
