package tv.jellybeam.ui.common

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * docs/16-library-sort-filter.md §4.6, docs/17-mini-player.md §6: shared refresh scheduler for
 * Home, Library, Detail and Person screens. A plain
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
 * - An explicit [requestRefresh] ends the current rest early (the caller is a user-visible edge,
 *   e.g. a PiP dismissal, docs/17 §6); an ask made during [refresh] skips the rest entirely.
 *   Event-driven dirty keeps the rest.
 * - A [refresh] that throws is reported to [onError] and the loop keeps going.
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
    private val onError: (Throwable) -> Unit = { Log.w(TAG, "refresh failed", it) },
) {
    private val dirty = MutableStateFlow(false)

    /** Bumped by each [requestRefresh]; a rest ends when it moves. */
    private val explicitAsks = MutableStateFlow(0)

    /** Marks dirty as an event would: the loop runs [refresh] next, in order with every other run. */
    fun requestRefresh() {
        explicitAsks.value++
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
            val asksBefore = explicitAsks.value
            try {
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
            }
            val wasActive = active.value
            val period = if (wasActive) visiblePeriodMs else hiddenPeriodMs
            withTimeoutOrNull(period) {
                combine(active, explicitAsks) { isActive, asks -> asks != asksBefore || (!wasActive && isActive) }.first { it }
            }
        }
    }

    private companion object {
        const val TAG = "ChangeRefresh"
    }
}
