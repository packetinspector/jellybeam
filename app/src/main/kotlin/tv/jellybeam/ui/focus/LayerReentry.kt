package tv.jellybeam.ui.focus

import androidx.compose.runtime.MutableState

/** docs/15 §4: only the top layer admits focus, and only around one of its own explicit placements. */
internal fun gateEnter(isTop: Boolean, gateOpen: Boolean): Boolean = isTop && gateOpen

/** How a screen answers the root's re-entry. */
internal enum class Reentry { Restore, Swallow, Default }

/**
 * docs/15 §4: a seeded screen re-places its own remembered focus; while any placement is pending
 * or running the key is swallowed (that placement lands it); a screen not ready to place (e.g. a
 * card-less load error) hands the key to its layer's fallback.
 */
internal fun reentryAction(ready: Boolean, seeded: Boolean, placing: Boolean): Reentry = when {
    !ready -> Reentry.Default
    !seeded || placing -> Reentry.Swallow
    else -> Reentry.Restore
}

/**
 * [restoreNow]'s order with bounded retries, for a D-pad key that found the page holding no
 * focus: returns whether anything took focus. A miss leaves the gate closed (docs/15 §4).
 */
internal suspend fun FocusMemory.reenterNow(
    focusGate: MutableState<Boolean>,
    selectedKey: () -> String?,
    fallback: () -> FocusTarget?,
): Boolean = withPlacement {
    placeByKeys(resolveRestoreOrder(lastKey, invokerKey, selectedKey()), focusGate) != null ||
        fallback()?.let { target -> requestFocusWithRetry(focusGate) { target.requestFocus() } } == true
}
