package tv.jellybeam.player

import uniffi.jellybeam_core.SyncPlayPhase

/** Injectable time source so [PlaybackOsdController] is deterministically unit-testable. */
fun interface Clock {
    fun nowMs(): Long

    companion object {
        val SYSTEM = Clock { System.currentTimeMillis() }
    }
}

/**
 * State machine for the player OSD's visibility (docs/12 §9: auto-hide after 5s idle while
 * playing). No Android/Compose dependency; [tv.jellybeam.player.PlaybackScreen] drives it from key
 * events and a periodic [tick].
 *
 * Enter is two-stage: the first press while hidden only reveals; a second while visible toggles
 * play/pause. A pause reveals and pins the OSD, since a TV remote has no pointer to wake the screen.
 * [setPinned] pins it visible for the track picker, next-up card, or a trickplay seek preview, so
 * the main chrome doesn't vanish mid-interaction.
 */
class PlaybackOsdController(
    private val clock: Clock = Clock.SYSTEM,
    /** docs/12 §9's idle window; [PlaybackScreen]'s own pinned-list extension covers the rest of
     * §9's "never auto-hide" cases.
     */
    private val idleTimeoutMs: Long = 5_000L,
) {
    var isVisible: Boolean = true
        private set

    private var isPaused: Boolean = false
    private var isPinned: Boolean = false
    private var lastInputAtMs: Long = clock.nowMs()

    /** What an Enter/DPAD_CENTER press should do, decided by [onEnterPressed]. */
    enum class EnterAction { REVEAL_ONLY, TOGGLE_PLAY_PAUSE }

    /** Any non-Enter key wakes/keeps the OSD visible and resets the idle clock. */
    fun onKeyEvent() {
        isVisible = true
        lastInputAtMs = clock.nowMs()
    }

    /** Hides the OSD for the Back priority chain; not an idle transition, so [tick]'s
     * paused/pinned exclusions don't apply.
     */
    fun hide() {
        isVisible = false
    }

    /** First Enter while hidden reveals only; a second while visible toggles play/pause. */
    fun onEnterPressed(): EnterAction {
        lastInputAtMs = clock.nowMs()
        return if (!isVisible) {
            isVisible = true
            EnterAction.REVEAL_ONLY
        } else {
            EnterAction.TOGGLE_PLAY_PAUSE
        }
    }

    /** Pause/resume edge from the player; resets the idle clock either way, and reveals per [pauseRevealsOsd]. */
    fun onPausedChanged(paused: Boolean, groupPhase: SyncPlayPhase? = null) {
        isPaused = paused
        if (pauseRevealsOsd(paused, groupPhase)) isVisible = true
        lastInputAtMs = clock.nowMs()
    }

    /**
     * Pins/unpins the OSD for a picker/next-up-card/seek-preview overlay; called with
     * `pickerOpen || nextUpShown || seekPreviewShown`. Resets the idle clock either way, so
     * unpinning grants a fresh window rather than letting the next [tick] hide the OSD instantly.
     */
    fun setPinned(pinned: Boolean) {
        isPinned = pinned
        isVisible = true
        lastInputAtMs = clock.nowMs()
    }

    /** Called periodically (~250ms) by the tick loop; flips [isVisible] false once idle, unless
     * paused or pinned.
     */
    fun tick() {
        if (isVisible && !isPaused && !isPinned && clock.nowMs() - lastInputAtMs >= idleTimeoutMs) {
            isVisible = false
        }
    }
}

/**
 * docs/12 §9, docs/28 §4: solo ([groupPhase] null) reveals on either edge; in a group only a member's Pause does, so a
 * seek's Waiting hold or a drift hold keeps hidden Left/Right as seeks.
 */
fun pauseRevealsOsd(paused: Boolean, groupPhase: SyncPlayPhase?): Boolean =
    groupPhase == null || (paused && groupPhase == SyncPlayPhase.PAUSED)
