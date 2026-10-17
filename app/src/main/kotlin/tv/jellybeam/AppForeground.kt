package tv.jellybeam

import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf

/**
 * Process-wide "app returned to foreground" signal, bumped from [MainActivity.onResume].
 * Composables key a `LaunchedEffect` on [resumeCount] to retry on foreground return
 * (e.g. [tv.jellybeam.ui.cards.CardArtImage] image-load retry). Not persisted; a trigger only.
 */
object AppForeground {
    val resumeCount: MutableIntState = mutableIntStateOf(0)

    /** `true` while [tv.jellybeam.MainActivity] is resumed: a retained screen is top only then (the
     * stack doesn't change when the player covers it), and browsing-only work waits on it. */
    val browsing: MutableState<Boolean> = mutableStateOf(false)
}
