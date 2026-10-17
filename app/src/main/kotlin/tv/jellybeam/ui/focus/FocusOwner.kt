package tv.jellybeam.ui.focus

import android.view.KeyEvent
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf

/** What the root does with a key event before Compose sees it (docs/15 §4). */
internal enum class RootKeyAction { Pass, Reenter, Swallow }

/**
 * docs/15 §4: a D-pad press while nothing holds focus goes to the top layer's re-entry, never to a
 * framework search; its release is swallowed with it. [swallowingUp] is the key code whose press
 * was taken.
 */
internal fun rootKeyAction(action: Int, keyCode: Int, hasFocus: Boolean, hasSeat: Boolean, swallowingUp: Int?): RootKeyAction = when {
    action == KeyEvent.ACTION_UP && keyCode == swallowingUp -> RootKeyAction.Swallow
    action != KeyEvent.ACTION_DOWN || hasFocus || !hasSeat || !isDpadKey(keyCode) -> RootKeyAction.Pass
    else -> RootKeyAction.Reenter
}

internal fun isDpadKey(keyCode: Int): Boolean = when (keyCode) {
    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> true
    else -> false
}

/**
 * A retained layer's re-entry seat (docs/15 §4): [place] is its screen's [FocusRestorer] (false = nothing to
 * place; a landing-nothing placement calls `onMiss`), [fallback] the gated entry, [leftEdge] a drawer opened on Left.
 */
internal class FocusSeat {
    var place: ((onMiss: () -> Unit) -> Boolean)? = null
    var fallback: ((keyCode: Int) -> Boolean)? = null
    var leftEdge: (() -> Unit)? = null

    fun reenter(keyCode: Int) {
        val miss = {
            if (fallback?.invoke(keyCode) != true && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) leftEdge?.invoke()
        }
        if (place?.invoke(miss) != true) miss()
    }
}

/** The seat of the retained layer composing below it; `null` outside one. */
internal val LocalFocusSeat = staticCompositionLocalOf<FocusSeat?> { null }

/** docs/15 §4: the retained layer's focus gate, held open only around its screen's own explicit placements. */
internal val LocalFocusGate = staticCompositionLocalOf<MutableState<Boolean>> { mutableStateOf(true) }

/**
 * Held by `MainActivity`, whose `dispatchKeyEvent` calls [dispatch]: [seat] is the top layer's
 * while it owns focus (resumed, no crash prompt), else null, and [hasFocus] whether anything in
 * the window holds focus.
 */
internal class RootFocus {
    var hasFocus: Boolean = false
    var seat: FocusSeat? = null
    private var swallowingUp: Int? = null

    /** True when the root took [event]; the caller then skips the framework dispatch. */
    fun dispatch(event: KeyEvent): Boolean = when (rootKeyAction(event.action, event.keyCode, hasFocus, seat != null, swallowingUp)) {
        RootKeyAction.Pass -> false
        RootKeyAction.Swallow -> {
            swallowingUp = null
            true
        }
        RootKeyAction.Reenter -> {
            swallowingUp = event.keyCode
            seat?.reenter(event.keyCode)
            true
        }
    }
}
