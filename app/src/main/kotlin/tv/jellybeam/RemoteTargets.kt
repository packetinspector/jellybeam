package tv.jellybeam

import androidx.compose.runtime.staticCompositionLocalOf
import tv.jellybeam.nav.NavBackStack
import tv.jellybeam.nav.Screen
import tv.jellybeam.nav.forDrawerPick
import tv.jellybeam.nav.isHomeRooted
import tv.jellybeam.nav.screenForView
import tv.jellybeam.player.RemoteKey
import tv.jellybeam.player.UiOp
import uniffi.jellybeam_core.ViewSnapshot

/** docs/31 §4.5: the one live target for a remote op, claimed by its owner while live; Main only. */
internal class RemoteSlot<T : Any> {
    var current: T? = null
        private set

    fun claim(target: T) {
        current = target
    }

    /** Only the claimant clears the slot, so an older owner's late release never drops a newer one. */
    fun release(target: T) {
        if (current === target) current = null
    }
}

/**
 * docs/31 §4.5: what MainActivity's browsing ops reach: the top library's index rail (one step, forward
 * when true; false at an end) and the Home-rooted stack's navigation.
 */
internal class RemoteTargets {
    val letters = RemoteSlot<(Boolean) -> Boolean>()
    val navigate = RemoteSlot<(Screen) -> Unit>()
}

/** MainActivity's [RemoteTargets]; `null` outside it, where no field or screen is remote-reachable. */
internal val LocalRemoteTargets = staticCompositionLocalOf<RemoteTargets?> { null }

/**
 * docs/31 §4.5: runs [op] on our own window ([type], [press]) or a claimed target; false when nothing took it.
 * A key counts once dispatched, so focus rules (docs/15) decide what it moved; the context menu is the
 * Menu key, taken only where a menu answers it (Detail's `···`, a focused track).
 */
internal fun RemoteTargets.take(
    op: UiOp,
    type: (String) -> Boolean,
    press: (RemoteKey) -> Boolean,
): Boolean {
    fun go(screen: Screen): Boolean {
        val navigate = navigate.current ?: return false
        navigate(screen)
        return true
    }
    return when (op) {
        is UiOp.Key -> true.also { press(op.key) }
        UiOp.ContextMenu -> press(RemoteKey.Menu)
        is UiOp.Text -> type(op.text)
        UiOp.PreviousLetter -> letters.current?.invoke(false) ?: false
        UiOp.NextLetter -> letters.current?.invoke(true) ?: false
        UiOp.Home -> go(Screen.Home)
        UiOp.Search -> go(Screen.Search)
        UiOp.Settings -> go(Screen.Settings)
    }
}

/**
 * docs/31 §4.3: the stack a remote's library open produces, the drawer's pick of that library's page; `null` off a
 * Home-rooted stack or for a library the views no longer list.
 */
internal fun libraryOpenStack(stack: NavBackStack, views: List<ViewSnapshot>, id: String): NavBackStack? =
    if (!stack.isHomeRooted) null else views.firstOrNull { it.id == id }?.let { stack.forDrawerPick(screenForView(it)) }

/** docs/31 §4.5: a sent string as the key events that type it; line breaks and characters with no key ([keys] null) are dropped. */
internal fun <E> keystrokes(text: String, keys: (Char) -> Array<E>?): List<E> =
    text.filterNot { it == '\n' || it == '\r' }.flatMap { keys(it)?.asList().orEmpty() }
