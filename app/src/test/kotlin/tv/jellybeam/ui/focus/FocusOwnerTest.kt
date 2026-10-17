package tv.jellybeam.ui.focus

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

/** docs/15 §4: the root key funnel hands a D-pad press with nothing focused to the top layer's seat. */
class FocusOwnerTest {

    @Test
    fun `a D-pad press with nothing focused re-enters the top layer`() {
        listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER).forEach { key ->
            assertEquals(RootKeyAction.Reenter, rootKeyAction(KeyEvent.ACTION_DOWN, key, hasFocus = false, hasSeat = true, swallowingUp = null))
        }
    }

    @Test
    fun `the release of a re-entered press is swallowed, other releases pass`() {
        assertEquals(RootKeyAction.Swallow, rootKeyAction(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN, hasFocus = true, hasSeat = true, swallowingUp = KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(RootKeyAction.Pass, rootKeyAction(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP, hasFocus = true, hasSeat = true, swallowingUp = KeyEvent.KEYCODE_DPAD_DOWN))
    }

    @Test
    fun `focused windows, no top layer, and non D-pad keys pass to the framework`() {
        assertEquals(RootKeyAction.Pass, rootKeyAction(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, hasFocus = true, hasSeat = true, swallowingUp = null))
        assertEquals(RootKeyAction.Pass, rootKeyAction(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, hasFocus = false, hasSeat = false, swallowingUp = null))
        assertEquals(RootKeyAction.Pass, rootKeyAction(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, hasFocus = false, hasSeat = true, swallowingUp = null))
        assertEquals(RootKeyAction.Pass, rootKeyAction(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, hasFocus = false, hasSeat = true, swallowingUp = null))
    }
}
