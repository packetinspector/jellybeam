package tv.jellybeam.player

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [PageKeys.resolve] is the plain-Kotlin decision behind the Page Up/Down chapter-skip shortcut.
 */
class PageKeyActionTest {

    private fun resolve(
        key: Key,
        repeatCount: Int = 0,
        nestedSurfaceOpen: Boolean = false,
        cardShowing: Boolean = false,
        glideActive: Boolean = false,
    ) = PageKeys.resolve(
        key = key,
        repeatCount = repeatCount,
        nestedSurfaceOpen = nestedSurfaceOpen,
        cardShowing = cardShowing,
        glideActive = glideActive,
    )

    @Test
    fun `PageUp maps to next-chapter and PageDown to chapter-start-or-previous with nothing blocking`() {
        assertEquals(PageKeyAction.NEXT_CHAPTER, resolve(key = Key.PageUp))
        assertEquals(PageKeyAction.PREV_CHAPTER, resolve(key = Key.PageDown))
    }

    @Test
    fun `the remote's Channel Up and Channel Down rocker are aliases of PageUp and PageDown`() {
        assertEquals(PageKeyAction.NEXT_CHAPTER, resolve(key = Key.ChannelUp))
        assertEquals(PageKeyAction.PREV_CHAPTER, resolve(key = Key.ChannelDown))
        assertEquals(PageKeyAction.CONSUME_NOOP, resolve(key = Key.ChannelUp, repeatCount = 1))
        assertEquals(PageKeyAction.CONSUME_NOOP, resolve(key = Key.ChannelDown, glideActive = true))
    }

    @Test
    fun `isPageKey recognises exactly the four keys`() {
        for (key in listOf(Key.PageUp, Key.PageDown, Key.ChannelUp, Key.ChannelDown)) assertTrue(PageKeys.isPageKey(key))
        for (key in listOf(Key.DirectionUp, Key.DirectionDown, Key.MediaNext, Key.MediaPrevious, Key.Back)) assertFalse(PageKeys.isPageKey(key))
    }

    @Test
    fun `a key repeat is consumed and does nothing, for either key`() {
        assertEquals(PageKeyAction.CONSUME_NOOP, resolve(key = Key.PageDown, repeatCount = 1))
        assertEquals(PageKeyAction.CONSUME_NOOP, resolve(key = Key.PageUp, repeatCount = 1))
        assertEquals(PageKeyAction.CONSUME_NOOP, resolve(key = Key.PageDown, repeatCount = 5))
    }

    @Test
    fun `an open nested surface consumes the press and does nothing`() {
        assertEquals(PageKeyAction.CONSUME_NOOP, resolve(key = Key.PageDown, nestedSurfaceOpen = true))
        assertEquals(PageKeyAction.CONSUME_NOOP, resolve(key = Key.PageUp, nestedSurfaceOpen = true))
    }

    @Test
    fun `the next-up or still-watching card consumes the press and does nothing`() {
        assertEquals(PageKeyAction.CONSUME_NOOP, resolve(key = Key.PageDown, cardShowing = true))
        assertEquals(PageKeyAction.CONSUME_NOOP, resolve(key = Key.PageUp, cardShowing = true))
    }

    @Test
    fun `an active hold-to-seek glide consumes the press and does nothing`() {
        assertEquals(PageKeyAction.CONSUME_NOOP, resolve(key = Key.PageDown, glideActive = true))
        assertEquals(PageKeyAction.CONSUME_NOOP, resolve(key = Key.PageUp, glideActive = true))
    }

    @Test
    fun `blocking flags combine without changing the outcome`() {
        assertEquals(
            PageKeyAction.CONSUME_NOOP,
            resolve(key = Key.PageDown, nestedSurfaceOpen = true, cardShowing = true, glideActive = true),
        )
    }

    @Test
    fun `any other key passes through unresolved`() {
        assertEquals(PageKeyAction.PASS, resolve(key = Key.DirectionLeft))
        assertEquals(PageKeyAction.PASS, resolve(key = Key.DirectionRight))
        assertEquals(PageKeyAction.PASS, resolve(key = Key.Menu))
        assertEquals(PageKeyAction.PASS, resolve(key = Key.Back))
        assertEquals(
            PageKeyAction.PASS,
            resolve(key = Key.DirectionUp, repeatCount = 1, nestedSurfaceOpen = true, cardShowing = true, glideActive = true),
        )
    }
}
