package tv.jellybeam.ui.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * docs/16-library-sort-filter.md §4.6, docs/17-mini-player.md §6: shared refresh scheduler for
 * [tv.jellybeam.ui.home.common.HomeFeed] and [tv.jellybeam.ui.library.LibraryViewModel]. A plain
 * `debounce(500)` never fires while a sync burst keeps landing events under 500ms apart, leaving
 * the screen frozen on stale data; this refreshes on the leading event instead.
 *
 * Contract:
 * - Any event on [events] sets a dirty flag; multiple events between refreshes conflate into one.
 * - A single loop owns [refresh]: wait for dirty, clear it, run [refresh], rest, repeat -- so this
 *   scheduler's own refreshes never overlap (other callers of [refresh] must guard themselves).
 * - The first event after a quiet spell refreshes immediately, not after a quiet window.
 * - Rest is [visiblePeriodMs] while [active], else [hiddenPeriodMs]; becoming active again ends
 *   the rest early so a hidden screen catches up immediately rather than waiting out its window.
 * - The loop re-checks dirty after every rest, so a trailing refresh is always guaranteed.
 *
 * [active] means the screen is top of its stack and the host Activity is resumed. This class owns
 * only the dirty flag; other callers (`refreshNow`, `onBecameTop`, `ON_RESUME`) may still call
 * [refresh] concurrently -- that dedup is [refresh]'s own problem, not this scheduler's. A caller
 * that must not overlap it asks through [requestRefresh] instead, and the one loop serves it.
 */
class ChangeRefreshScheduler<T>(
    scope: CoroutineScope,
    events: Flow<T>,
    private val active: StateFlow<Boolean>,
    private val refresh: suspend () -> Unit,
    private val visiblePeriodMs: Long = 500L,
    private val hiddenPeriodMs: Long = 3000L,
) {
    private val dirty = MutableStateFlow(false)

    /** Marks dirty as an event would: the loop runs [refresh] next, in order with every other run. */
    fun requestRefresh() {
        dirty.value = true
    }

    init {
        scope.launch { events.collect { dirty.value = true } }
        scope.launch { loop() }
    }

    private suspend fun loop() {
        while (true) {
            dirty.first { it }
            dirty.value = false
            refresh()
            if (active.value) {
                delay(visiblePeriodMs)
            } else {
                withTimeoutOrNull(hiddenPeriodMs) { active.first { it } }
            }
        }
    }
}
