package tv.jellybeam.ui.common

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

/** docs/15 §4.1: a text field's remote keys behave the same whatever device sent them. */
class JellybeamTextFieldTest {

    private val selectKeys = listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)

    @Test
    fun `every OK key code opens the keyboard on press and is swallowed on release`() {
        selectKeys.forEach { key ->
            assertEquals(FieldKey.OpenKeyboard, fieldKeyAction(KeyEvent.ACTION_DOWN, key))
            assertEquals(FieldKey.Swallow, fieldKeyAction(KeyEvent.ACTION_UP, key))
        }
    }

    @Test
    fun `Up and Down leave the field on press only`() {
        assertEquals(FieldKey.FocusUp, fieldKeyAction(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(FieldKey.Pass, fieldKeyAction(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(FieldKey.FocusDown, fieldKeyAction(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(FieldKey.Pass, fieldKeyAction(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN))
    }

    @Test
    fun `other keys reach the field untouched`() {
        listOf(
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_DEL,
        ).forEach { key ->
            assertEquals(FieldKey.Pass, fieldKeyAction(KeyEvent.ACTION_DOWN, key))
            assertEquals(FieldKey.Pass, fieldKeyAction(KeyEvent.ACTION_UP, key))
        }
    }
}
