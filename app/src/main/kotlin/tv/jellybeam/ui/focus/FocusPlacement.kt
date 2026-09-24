package tv.jellybeam.ui.focus

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay

private const val ATTEMPTS_PER_BURST = 3
private const val RETRY_DELAY_MS = 100L

/** `false` from `requestFocus()` means the target isn't eligible yet, not an exception. */
internal fun tryRequestFocus(requestFocus: () -> Boolean): Boolean =
    try {
        requestFocus()
    } catch (_: IllegalStateException) {
        // Modifier not yet attached; also retryable.
        false
    }

/** Opens [focusGate] only for the synchronous request so default focus can't race the explicit
 * target.
 */
internal suspend fun requestFocusWithRetry(
    focusGate: MutableState<Boolean>? = null,
    attempts: Int = ATTEMPTS_PER_BURST,
    requestFocus: () -> Boolean,
): Boolean {
    require(attempts > 0)
    repeat(attempts) {
        withFrameNanos { }
        focusGate?.value = true
        val placed = tryRequestFocus(requestFocus)
        focusGate?.value = false
        if (placed) return true
    }
    return false
}

/** Retries until success or the caller's `LaunchedEffect` is cancelled by navigation/disposal. */
internal suspend fun requestFocusUntilSuccess(
    focusGate: MutableState<Boolean>? = null,
    requestFocus: () -> Boolean,
) {
    while (true) {
        if (requestFocusWithRetry(focusGate = focusGate, requestFocus = requestFocus)) return
        delay(RETRY_DELAY_MS)
    }
}
