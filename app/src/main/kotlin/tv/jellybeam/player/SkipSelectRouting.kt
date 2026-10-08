package tv.jellybeam.player

import androidx.compose.ui.input.key.Key

/** What a Select press does while the skip pill / undo toast may be on screen (docs/12 §14). */
enum class SelectRoute {
    /** Seek back to the pre-skip position and drop the toast. */
    UNDO,
    PLAY_NEXT,
    SKIP_SEGMENT,
    /** The OSD button holding the focus ring. */
    ACTIVATE_FOCUSED,
    /** Hidden OSD with nothing to act on: Select only reveals (docs/12 §9). */
    REVEAL_OSD,
}

/** Where an arrow key moves focus relative to the skip pill (docs/12 §14). */
enum class PillFocusMove { NONE, FOCUS_PILL, RETURN_TO_BUTTONS }

object SkipSelectRouting {
    private val DPAD_KEYS = setOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight)

    /**
     * Select acts on what holds the focus ring (docs/12 §14): hidden OSD gives the one-press
     * Undo/Skip, a visible OSD gives its focused control, and only a pill the user focused skips.
     * A next-up / still-watching card outranks Undo, the pill and the OSD's focused button, so Select
     * never undoes a skip under a card.
     */
    fun resolveSelect(
        osdVisible: Boolean,
        pillFocused: Boolean,
        segmentShown: Boolean,
        undoShown: Boolean,
        nextUpShown: Boolean,
        stillWatchingShown: Boolean = false,
    ): SelectRoute = if (!osdVisible) {
        when {
            nextUpShown || stillWatchingShown -> SelectRoute.REVEAL_OSD
            undoShown -> SelectRoute.UNDO
            segmentShown -> SelectRoute.SKIP_SEGMENT
            else -> SelectRoute.REVEAL_OSD
        }
    } else {
        when {
            nextUpShown -> SelectRoute.PLAY_NEXT
            pillFocused && segmentShown -> SelectRoute.SKIP_SEGMENT
            else -> SelectRoute.ACTIVATE_FOCUSED
        }
    }

    /**
     * A skip closes a bare OSD so Select can undo, but not over a card or an open player menu, and
     * not while paused, which pins the OSD (docs/12 §9, §13, §14).
     */
    fun skipHidesOsd(osdVisible: Boolean, menuOpen: Boolean, cardShown: Boolean, paused: Boolean): Boolean =
        osdVisible && !menuOpen && !cardShown && !paused

    /** The Undo toast is drawn only while Select would undo: hidden OSD, no card (docs/12 §14). */
    fun undoToastVisible(undoShown: Boolean, osdVisible: Boolean, cardShown: Boolean): Boolean =
        undoShown && !osdVisible && !cardShown

    /** Any arrow key leaves the Undo toast stale, so it is dismissed (docs/12 §14). */
    fun dismissesUndoToast(key: Key): Boolean = key in DPAD_KEYS

    /** Up from a visible OSD focuses a shown pill; Down/Left/Right from the pill hand focus back. */
    fun pillFocusMove(key: Key, osdVisible: Boolean, pillFocused: Boolean, segmentShown: Boolean): PillFocusMove = when {
        pillFocused && (key == Key.DirectionDown || key == Key.DirectionLeft || key == Key.DirectionRight) ->
            PillFocusMove.RETURN_TO_BUTTONS
        key == Key.DirectionUp && osdVisible && segmentShown && !pillFocused -> PillFocusMove.FOCUS_PILL
        else -> PillFocusMove.NONE
    }

    /** The pill is on screen only with no next-up / still-watching card and no player menu over it. */
    fun pillVisible(segmentActive: Boolean, cardShown: Boolean, menuOpen: Boolean): Boolean =
        segmentActive && !cardShown && !menuOpen

    /** The pill keeps focus only while it is on screen under a visible OSD; else the ring returns. */
    fun pillStaysFocused(pillFocused: Boolean, osdVisible: Boolean, segmentShown: Boolean): Boolean =
        pillFocused && osdVisible && segmentShown
}
