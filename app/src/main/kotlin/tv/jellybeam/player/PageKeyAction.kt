package tv.jellybeam.player

import androidx.compose.ui.input.key.Key

/**
 * Page Up/Down (and remote Channel Up/Down) chapter-skip transport (docs/12 §9), resolved by
 * [PageKeys.resolve]. Up starts the next chapter; Down restarts the current one, or jumps to the
 * previous within [Chapters]' 5s grace.
 */
enum class PageKeyAction { NEXT_CHAPTER, PREV_CHAPTER, CONSUME_NOOP, PASS }

/** Chapter-skip decision, kept free of Compose/ViewModel deps like [Chapters]. KeyUp unhandled. */
object PageKeys {
    val NEXT_KEYS: Set<Key> = setOf(Key.PageUp, Key.ChannelUp)

    val PREV_KEYS: Set<Key> = setOf(Key.PageDown, Key.ChannelDown)

    fun isPageKey(key: Key): Boolean = key in NEXT_KEYS || key in PREV_KEYS

    /**
     * Falls through as [PageKeyAction.PASS] outside [NEXT_KEYS]/[PREV_KEYS]. A held repeat
     * ([repeatCount] != 0), or [nestedSurfaceOpen]/[cardShowing]/[glideActive] alone, consumes the
     * press as a no-op; whether the item has chapters is left to the caller.
     */
    fun resolve(
        key: Key,
        repeatCount: Int,
        nestedSurfaceOpen: Boolean,
        cardShowing: Boolean,
        glideActive: Boolean,
    ): PageKeyAction {
        if (!isPageKey(key)) return PageKeyAction.PASS
        if (repeatCount != 0) return PageKeyAction.CONSUME_NOOP
        if (nestedSurfaceOpen || cardShowing || glideActive) return PageKeyAction.CONSUME_NOOP
        return if (key in NEXT_KEYS) PageKeyAction.NEXT_CHAPTER else PageKeyAction.PREV_CHAPTER
    }
}
