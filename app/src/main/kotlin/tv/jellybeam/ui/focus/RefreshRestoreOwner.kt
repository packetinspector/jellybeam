package tv.jellybeam.ui.focus

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * docs/15-focus-and-selection.md §3's one owner of a screen's refresh-restore work, replacing
 * each screen's own `refreshRestoreJob`/`refreshOwnsFreeze` var pair. [epoch] guards a trigger
 * still waiting in [onContentChanged]'s [frame] when ownership is lost mid-wait: every loss bumps
 * it, and a trigger whose epoch moved returns without consulting [eligible]; `eligible` must
 * itself read the *latest* ownership inputs (`rememberUpdatedState` for composable parameters) to
 * catch a loss reaching it only through a live read. A screen under
 * [tv.jellybeam.ui.nav.NavDrawerHost]
 * must wire [tv.jellybeam.ui.nav.LocalDrawerFocusCoordinator] to call [ownershipLost], or a live
 * refresh can steal focus from a drawer holding it outside the root. Thread confinement: plain
 * vars, touched only from the composition/main thread.
 */
internal class RefreshRestoreOwner {
    private var job: Job? = null
    private var epoch = 0L

    /** True from just before a restore coroutine starts until its [restoreNow] call returns (or
     * forever if cancelled, mirroring `frozen`); tells [refreshGuardEligible] a refresh-owned
     * freeze from a drawer/navigation one.
     */
    var ownsFreeze: Boolean = false
        private set

    /**
     * An ownership transition (lost top, ON_PAUSE, drawer/panel opened): cancels the running
     * restore and invalidates every waiting [onContentChanged] trigger via [epoch]. [releaseFreeze]
     * clears [ownsFreeze] when the new owner sets its own blocking freeze right after; leave it
     * `false` for a plain loss of top/resumed, where `ownsFreeze` must survive.
     */
    fun ownershipLost(releaseFreeze: Boolean = false) {
        epoch++
        job?.cancel()
        job = null
        if (releaseFreeze) ownsFreeze = false
    }

    /**
     * The screen regained ownership in place (docs/15 §3), via a path that runs no restore of its
     * own. Without this, a freeze left by a refresh restore cancelled on the matching open would
     * never lift. Returns true when this owner held such a freeze and dropped its claim (caller
     * then clears `memory.frozen`); never true while a restore is still running.
     */
    fun releaseCancelledFreeze(): Boolean {
        if (!ownsFreeze || job?.isActive == true) return false
        ownsFreeze = false
        return true
    }

    /**
     * Body of a screen's content-change refresh-restore trigger effect. [frame] is a one-frame
     * settle (`{ withFrameNanos {} }` in production) letting Compose apply new content -- and
     * clear focus, if it's going to -- before [eligible] reads whether anything still holds focus.
     */
    suspend fun onContentChanged(
        scope: CoroutineScope,
        frame: suspend () -> Unit,
        eligible: (ownsFreeze: Boolean) -> Boolean,
        restore: suspend () -> Unit,
    ) {
        val startEpoch = epoch
        frame()
        if (epoch != startEpoch) return
        if (!eligible(ownsFreeze)) return
        job?.cancel() // latest refresh wins, same as a second drawer close superseding the first
        job = scope.launch {
            ownsFreeze = true
            try {
                restore()
            } finally {
                // Only a run that finished on its own releases the freeze, mirroring `restoreNow`'s
                // `finally` contract.
                if (isActive) ownsFreeze = false
            }
        }
    }
}

/**
 * The in-place-regain hook every screen's drawer/panel close runs: releases a freeze a cancelled
 * refresh restore left behind and clears [FocusMemory.frozen], but only while the screen still
 * owns focus ([ownsFocus]) -- a navigating-away close instead leaves [FocusRestorer]'s own
 * navigation freeze alone (docs/15 §3). Returns true when a freeze was released.
 */
internal fun RefreshRestoreOwner.releaseCancelledFreezeInPlace(memory: FocusMemory, ownsFocus: Boolean): Boolean {
    if (!ownsFocus) return false
    if (!releaseCancelledFreeze()) return false
    memory.frozen = false
    return true
}
