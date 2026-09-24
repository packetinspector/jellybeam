package tv.jellybeam.ui.cards

import androidx.compose.runtime.snapshotFlow
import tv.jellybeam.AppForeground
import tv.jellybeam.AppGraph
import tv.jellybeam.data.CoreGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** [PreloadOnDwell]'s sustained-focus threshold before it fires a preload. */
const val PRELOAD_DWELL_MILLIS: Long = 400L

/** Mirrors `core/ffi/src/object.rs`'s `PRELOAD_CACHE_TTL` (docs/18 §2); kept in sync by hand since
 * the value isn't exposed over FFI. [PreloadOnDwell] re-fires at this interval so a card held in
 * focus past the cache's lifetime stays warm.
 */
const val PRELOAD_REARM_MILLIS: Long = 30_000L

/** Item types worth firing [CoreGateway.preloadPlayback] for; a Series/Season/BoxSet card opens a
 * browse page on click, never `preparePlayback`.
 */
private val PRELOAD_PLAYABLE_ITEM_TYPES = setOf("Movie", "Episode")

/**
 * docs/13 focus-dwell preload: after a card holds focus for [PRELOAD_DWELL_MILLIS] and names a
 * playable item, fires one best-effort [CoreGateway.preloadPlayback] call. The Rust side
 * (`JellybeamCore::preload_playback`, `Settings::preload_on_focus`) is the sole authority on whether
 * that call does anything; this class unconditionally schedules it once dwell elapses.
 *
 * Dedup rules (pure, testable with a virtual-clock [CoroutineScope]):
 * - [onCardFocused] cancels a pending timer for a *different* item id and starts a fresh one for
 *   the newly focused item, only if its `itemType` is in [PRELOAD_PLAYABLE_ITEM_TYPES].
 * - [onCardUnfocused] cancels the pending timer only if it's still the one for that item id --
 *   Compose doesn't guarantee unfocus/focus ordering on a fast hop, so an outgoing unfocus must
 *   never cancel the incoming card's just-scheduled timer.
 * - A card already fired for re-fires every [PRELOAD_REARM_MILLIS] while it stays focused, so a
 *   long dwell (reading a synopsis) never plays against a stale cache entry.
 *
 * One instance ([default]) is shared across every dwell-preload call site (Home shelves, the
 * Home hero, library grid cells, the Detail episode grid).
 */
class PreloadOnDwell(
    private val scope: CoroutineScope,
    private val gateway: CoreGateway,
    private val dwellMillis: Long = PRELOAD_DWELL_MILLIS,
    private val reArmMillis: Long = PRELOAD_REARM_MILLIS,
) {
    private var pendingJob: Job? = null
    private var pendingItemId: String? = null
    private var firedForItemId: String? = null

    companion object {
        /**
         * Process-wide shared instance, on [Dispatchers.Main.immediate] -- deliberately the same
         * thread [onCardFocused]/[onCardUnfocused] are always called from, since
         * [pendingJob]/[pendingItemId]/[firedForItemId] are unsynchronized `var`s. [SupervisorJob]
         * keeps one card's preload failing from cancelling a sibling's timer. Lazy so it never
         * builds before [AppGraph.gateway] is set.
         */
        val default: PreloadOnDwell by lazy {
            PreloadOnDwell(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
                gateway = AppGraph.gateway,
            )
        }
    }

    fun onCardFocused(itemId: String, itemType: String) {
        if (itemType !in PRELOAD_PLAYABLE_ITEM_TYPES) {
            cancelPending()
            firedForItemId = null
            return
        }
        onPlayableFocused(itemId)
    }

    /** Schedules a known-playable target such as a Detail Play/Resume action. */
    fun onPlayableFocused(itemId: String) {
        if (itemId == firedForItemId || itemId == pendingItemId) {
            // Already fired or already pending: a duplicate onFocusChanged callback must not
            // restart the clock.
            return
        }
        cancelPending()
        firedForItemId = null

        pendingItemId = itemId
        pendingJob = scope.launch {
            delay(dwellMillis)
            pendingItemId = null
            firedForItemId = itemId
            reArmLoop(itemId)
        }
    }

    /** Fires immediately for the final Play/Resume control -- a stronger intent signal than
     * browsing, so dwell would waste handshake time.
     */
    fun onPlayableActionFocused(itemId: String) {
        if (itemId == firedForItemId) return
        cancelPending()
        firedForItemId = itemId
        pendingJob = scope.launch { reArmLoop(itemId) }
    }

    /** Runs forever, re-firing every [reArmMillis]: the caller has already fired once and set
     * [firedForItemId]; cancelled (by [cancelPending]) on unfocus or a switch to a different item.
     * Each fire waits for [AppForeground.browsing], so a card left focused behind the launcher or
     * the player does not keep negotiating.
     */
    private suspend fun reArmLoop(itemId: String) {
        while (true) {
            snapshotFlow { AppForeground.browsing.value }.first { it }
            gateway.preloadPlayback(itemId)
            delay(reArmMillis)
        }
    }

    fun onCardUnfocused(itemId: String) {
        if (pendingItemId == itemId || firedForItemId == itemId) {
            cancelPending()
        }
        if (firedForItemId == itemId) {
            firedForItemId = null
        }
    }

    private fun cancelPending() {
        pendingJob?.cancel()
        pendingJob = null
        pendingItemId = null
    }
}
