package tv.jellybeam.ui.common

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tv.jellybeam.JellybeamTheme
import tv.jellybeam.LocalAccent
import tv.jellybeam.ui.focus.isSelectKey

/** What a remote key does on a focused text field (docs/15 §4.1). */
internal enum class FieldKey { OpenKeyboard, FocusUp, FocusDown, Swallow, Pass }

/**
 * docs/15 §4.1: OK opens the keyboard and Up/Down leave the field for every remote, since Compose does
 * this only for a non-virtual D-pad device; an OK release is swallowed so ENTER never runs the IME action.
 */
internal fun fieldKeyAction(action: Int, keyCode: Int): FieldKey = when {
    isSelectKey(keyCode) -> if (action == KeyEvent.ACTION_DOWN) FieldKey.OpenKeyboard else FieldKey.Swallow
    action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP -> FieldKey.FocusUp
    action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> FieldKey.FocusDown
    else -> FieldKey.Pass
}

/**
 * The house single-line text field: optional label, Hairline border at rest and accent while focused.
 * [fieldModifier] goes on the text input itself (focus keys, requesters, focus properties); [compact]
 * is the action-panel size.
 */
@Composable
internal fun JellybeamTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    fieldModifier: Modifier = Modifier,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    compact: Boolean = false,
) {
    val isFocused by interactionSource.collectIsFocusedAsState()
    val accent = LocalAccent.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (label != null) {
            BasicText(
                text = label,
                style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Grigio, fontSize = 12.sp),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(JellybeamTheme.Surface, RoundedCornerShape(8.dp))
                .border(2.dp, if (isFocused) accent.accentInk else JellybeamTheme.Hairline, RoundedCornerShape(8.dp))
                .padding(horizontal = if (compact) 14.dp else 16.dp, vertical = if (compact) 10.dp else 12.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(fieldModifier)
                    .onPreviewKeyEvent { key ->
                        val event = key.nativeKeyEvent
                        when (fieldKeyAction(event.action, event.keyCode)) {
                            FieldKey.OpenKeyboard -> {
                                keyboard?.show()
                                true
                            }
                            FieldKey.FocusUp -> focusManager.moveFocus(FocusDirection.Up)
                            FieldKey.FocusDown -> focusManager.moveFocus(FocusDirection.Down)
                            FieldKey.Swallow -> true
                            FieldKey.Pass -> false
                        }
                    },
                textStyle = TextStyle(
                    fontFamily = JellybeamTheme.Archivo,
                    color = JellybeamTheme.Panna,
                    fontSize = if (compact) 15.sp else 16.sp,
                ),
                singleLine = true,
                cursorBrush = SolidColor(accent.accentInk),
                visualTransformation = visualTransformation,
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
                interactionSource = interactionSource,
            )
        }
    }
}
