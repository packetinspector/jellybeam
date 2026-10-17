package tv.jellybeam.ui.focus

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusRequesterModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.requestFocus
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import tv.jellybeam.perf.PerfLog

/**
 * A placeable, stable-keyed focus target: a live [FocusKeyNode] in [FocusMemory]'s registry, or a
 * plain [FocusRequester] wrapped by [asFocusTarget]. [requestFocus] returns `false`, never throws.
 */
internal interface FocusTarget {
    fun requestFocus(): Boolean
}

/**
 * docs/15-focus-and-selection.md §5's shared holder of "where the cursor was" for a retained
 * screen or nested surface, so no screen writes its own becoming-top restore effect.
 *
 * [lastKey]/[invokerKey] are `mutableStateOf`-backed since [Saver] must persist them across a
 * configuration change; every other field is plain, written far more often than read.
 * - [frozen]: derived, never written -- true while any placer holds a [PlacementToken] or the
 *   screen is [offTop]; [noteFocused] does not write [lastKey] then, so default-focus churn during
 *   a pop or an in-flight placement cannot corrupt the record.
 * - [seeded]: true once focus has been placed at least once via [FocusRestorer]; tells a first
 *   mount apart from a later becoming-top restore.
 * - [scrollTo]: brings a not-yet-composed [key] into a lazy list's window before [placeByKeys]
 * retries; returns whether [key] is a member at all, so a `false` skips straight to the next
 * candidate.
 */
@Stable
internal class FocusMemory(lastKey: String?, invokerKey: String?) {

    var lastKey: String? by mutableStateOf(lastKey)
    var invokerKey: String? by mutableStateOf(invokerKey)

    /** Set by [FocusRestorer] when the screen loses top, cleared once it has placed for the new top period. */
    var offTop: Boolean = false
    var seeded: Boolean = false

    private val placers = HashSet<PlacementToken>()

    /** docs/15 §5: one source for "do not record focus now"; see [beginPlacement]. */
    val frozen: Boolean get() = placers.isNotEmpty() || offTop

    /** True while any placer (restore, re-entry, drawer, key placement) holds a token. */
    val isPlacing: Boolean get() = placers.isNotEmpty()

    /** Frozen by something other than [own] tokens the caller itself holds. */
    fun frozenBesides(own: Int): Boolean = offTop || placers.size > own

    /** Every programmatic placement holds one token for its whole run; [endPlacement] always runs. */
    fun beginPlacement(): PlacementToken = PlacementToken().also(placers::add)

    /** Idempotent per token. */
    fun endPlacement(token: PlacementToken) {
        placers.remove(token)
    }

    var scrollTo: (suspend (key: String) -> Boolean)? = null

    /** The key whose element holds focus right now, frozen or not; null once it loses focus. */
    var focusedKey: String? = null
        private set

    /** Registered outside composition; read only by [placeByKeys] (not snapshot state). */
    private val registry: MutableMap<String, FocusTarget> = HashMap()

    fun register(key: String, target: FocusTarget) {
        registry[key] = target
    }

    /** Removes the mapping only if it still points at [target] (a recycled item may re-add). */
    fun unregister(key: String, target: FocusTarget) {
        if (registry[key] === target) registry.remove(key)
    }

    fun target(key: String): FocusTarget? = registry[key]

    fun hasKey(key: String): Boolean = registry.containsKey(key)

    /** Records the currently focused element's key, unless [frozen]. */
    fun noteFocused(key: String) {
        if (!frozen) lastKey = key
    }

    /** A keyed element's focus event: tracks [focusedKey] always, [lastKey] only when not [frozen]. */
    fun noteFocusEvent(key: String, hasFocus: Boolean) {
        if (hasFocus) {
            focusedKey = key
            noteFocused(key)
        } else if (focusedKey == key) {
            focusedKey = null
        }
    }

    /** §4: captures the invoker -- the key focused right before a nested surface opens ([invokerFor]). */
    fun captureInvoker() {
        invokerKey = invokerFor(focusedKey, lastKey)
    }

    fun clearInvoker() {
        invokerKey = null
    }

    companion object {
        val Saver: Saver<FocusMemory, *> = listSaver(
            save = { listOf(it.lastKey, it.invokerKey) },
            restore = { FocusMemory(it[0], it[1]) },
        )
    }
}

/** One placer's claim on [FocusMemory.frozen] (docs/15 §5). */
internal class PlacementToken internal constructor()

/** Runs [block] holding a placement token, released however it ends (cancellation included). */
internal inline fun <T> FocusMemory.withPlacement(block: () -> T): T {
    val token = beginPlacement()
    try {
        return block()
    } finally {
        endPlacement(token)
    }
}

/**
 * docs/15 §4: the invoker is the element focused when the surface opens; [FocusMemory.lastKey] is
 * only the fallback, since a placement made while frozen (a seed or restore) never writes it.
 */
internal fun invokerFor(focusedKey: String?, lastKey: String?): String? = focusedKey ?: lastKey

/** A saveable [FocusMemory], scoped like any other `rememberSaveable` by [inputs]. */
@Composable
internal fun rememberFocusMemory(vararg inputs: Any?): FocusMemory =
    rememberSaveable(*inputs, saver = FocusMemory.Saver) { FocusMemory(null, null) }

/**
 * §2/§5's precedence order, pure and JVM-testable: invoker (the control that opened a
 * just-closed nested surface) outranks last-focused, which outranks the selected item (§2 rule
 * 2). Distinct, non-null only; empty leaves [FocusRestorer] to fall back to its primary (rule 3).
 */
internal fun resolveRestoreOrder(lastKey: String?, invokerKey: String?, selectedKey: String?): List<String> =
    listOfNotNull(invokerKey, lastKey, selectedKey).distinct()

/** Tries each candidate key in order, asking a lazy list to scroll a not-yet-composed key into view
 * before giving up. Returns the key that took focus, or null.
 */
internal suspend fun FocusMemory.placeByKeys(
    keys: List<String>,
    focusGate: MutableState<Boolean>?,
    attemptsPerKey: Int = 3,
): String? {
    for (key in keys) {
        val direct = target(key)
        if (direct != null) {
            if (requestFocusWithRetry(focusGate, attemptsPerKey) { direct.requestFocus() }) return key
            continue
        }
        val scroll = scrollTo ?: continue
        if (!scroll(key)) continue
        repeat(attemptsPerKey) {
            withFrameNanos { }
            val scrolledInto = target(key)
            if (scrolledInto != null && requestFocusWithRetry(focusGate, attemptsPerKey) { scrolledInto.requestFocus() }) {
                return key
            }
        }
    }
    return null
}

/** Wraps a plain [FocusRequester] as a [FocusTarget] for [FocusRestorer]'s `fallback`;
 * [tryRequestFocus] makes an unattached requester a miss, not a crash.
 */
internal fun FocusRequester.asFocusTarget(): FocusTarget = object : FocusTarget {
    override fun requestFocus(): Boolean = tryRequestFocus { this@asFocusTarget.requestFocus() }
}

/**
 * The placement work §2/§5 describe: resolve §2's precedence order, place by key (falling back
 * through §2 rule 3's declared primary), clear the invoker, mark [memory] seeded, log -- all under
 * one [PlacementToken]. Shared by [FocusRestorer]'s becoming-top effect, drawer-close and refresh
 * restores; callers own their own `ready`-style gating.
 *
 * @return the key that was placed, or null if every candidate missed and placement fell through to
 *         [fallback].
 */
internal suspend fun FocusMemory.restoreNow(
    focusGate: MutableState<Boolean>,
    selectedKey: () -> String? = { null },
    fallback: () -> FocusTarget?,
    tag: String = "",
): String? = withPlacement {
    val keys = resolveRestoreOrder(lastKey, invokerKey, selectedKey())
    val placed = placeByKeys(keys, focusGate)
    if (placed == null) {
        fallback()?.let { target ->
            requestFocusUntilSuccess(focusGate) { target.requestFocus() }
        }
    }
    invokerKey = null
    seeded = true
    if (PerfLog.enabled) {
        Log.d(PerfLog.TAG, "focus restore tag=$tag placed=${placed ?: "fallback"}")
    }
    placed
}

/**
 * Pure eligibility check for a screen's second, same-composition restore trigger (alongside
 * [FocusRestorer]'s becoming-top effect) for "a live data refresh dropped the focused item and
 * left focus nowhere". [frozenByOthers] is [FocusMemory.frozenBesides] the refresh owner's own
 * running restore, so a refresh supersedes its predecessor but never a drawer or navigation.
 */
internal fun refreshGuardEligible(
    isTop: Boolean,
    seeded: Boolean,
    resumed: Boolean,
    hasFocus: Boolean,
    frozenByOthers: Boolean,
): Boolean = isTop && seeded && resumed && !hasFocus && !frozenByOthers

/**
 * docs/15-focus-and-selection.md §2-§5's one becoming-top restore effect. Screens delete their
 * own `LaunchedEffect(isTop)` restore blocks and call this once, at the top of their composable.
 * Cancelled by navigation like any becoming-top effect, so an outgoing retained layer can't keep
 * placing focus into a layer that no longer owns it. Also the layer's [FocusSeat.place] for the
 * root's D-pad re-entry (docs/15 §4); placements open the layer's [LocalFocusGate].
 *
 * @param ready gates the restore on data the screen needs (e.g. a list still loading); while
 *   false, nothing is placed and [memory] stays [FocusMemory.offTop] if it was.
 * @param selectedKey §2 rule 2 -- resolved lazily, once, only if reached.
 * @param fallback §2 rule 3, this surface's one declared primary action.
 * @param tag a short label for the [PerfLog] line; empty is fine.
 * @param modal a dialog inside the page that owns placement while open: becoming top and re-entry
 *   land on it, and the invoker stays for its close.
 */
@Composable
internal fun FocusRestorer(
    memory: FocusMemory,
    isTop: Boolean,
    ready: Boolean = true,
    selectedKey: () -> String? = { null },
    fallback: () -> FocusTarget?,
    tag: String = "",
    /** A new value is a fresh landing (e.g. Retry): placement runs again even if [ready] never flips. */
    reloadKey: Any? = null,
    modal: () -> FocusTarget? = { null },
) {
    val focusGate = LocalFocusGate.current
    val reentry = rememberReentryHandler(memory, focusGate, ready, selectedKey, fallback, modal)
    // docs/15 §4: a new reloadKey is a fresh landing; applied at commit, before the effect below restarts.
    DisposableEffect(reloadKey) {
        memory.seeded = false
        onDispose {}
    }
    LaunchedEffect(isTop, ready, reloadKey) {
        reentry.cancel()
        if (!isTop) {
            memory.offTop = true
            return@LaunchedEffect
        }
        // Top but not ready: stay offTop if hidden before, so the ready flip still counts as becoming top.
        if (!ready) return@LaunchedEffect
        // Placed and still top (a reload finished while top): nothing to place, tracking is live.
        if (memory.seeded && !memory.offTop) return@LaunchedEffect
        val dialog = modal()
        if (dialog != null) {
            memory.withPlacement { requestFocusUntilSuccess(focusGate) { dialog.requestFocus() } }
        } else {
            memory.restoreNow(focusGate, selectedKey, fallback, tag)
        }
        // Only a placement that finished lifts offTop; a cancelled one leaves the screen frozen.
        memory.offTop = false
    }
}

/** [FocusRestorer]'s re-entry job, cancelled on every isTop/ready change. */
private class ReentryState(private val scope: CoroutineScope) {
    private var job: Job? = null

    fun launch(block: suspend () -> Unit) {
        job = scope.launch { block() }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }
}

/**
 * Registers this screen's answer to the root's D-pad re-entry (docs/15 §4, [reentryAction]) as its
 * layer's [FocusSeat.place]; a restore runs in a job [FocusRestorer] cancels on every isTop/ready
 * change, so it never outlives the screen's ownership.
 */
@Composable
private fun rememberReentryHandler(
    memory: FocusMemory,
    focusGate: MutableState<Boolean>,
    ready: Boolean,
    selectedKey: () -> String?,
    fallback: () -> FocusTarget?,
    modal: () -> FocusTarget?,
): ReentryState {
    val scope = rememberCoroutineScope()
    val state = remember(memory) { ReentryState(scope) }
    val latestReady by rememberUpdatedState(ready)
    val latestSelectedKey by rememberUpdatedState(selectedKey)
    val latestFallback by rememberUpdatedState(fallback)
    val latestModal by rememberUpdatedState(modal)
    val seat = LocalFocusSeat.current
    DisposableEffect(seat, state) {
        val place: (() -> Unit) -> Boolean = place@{ onMiss ->
            latestModal()?.let { dialog ->
                state.launch { memory.withPlacement { if (!requestFocusWithRetry(focusGate) { dialog.requestFocus() }) onMiss() } }
                return@place true
            }
            val action = reentryAction(latestReady, memory.seeded, memory.isPlacing)
            when (action) {
                Reentry.Default -> false
                Reentry.Swallow -> true
                Reentry.Restore -> {
                    state.launch {
                        if (!memory.reenterNow(focusGate, latestSelectedKey, latestFallback)) onMiss()
                    }
                    true
                }
            }
        }
        seat?.place = place
        onDispose {
            state.cancel()
            if (seat?.place === place) seat.place = null
        }
    }
    return state
}

/**
 * Registers [key] in [memory]'s live registry while this element stays composed, and records
 * focus-gained events via [FocusMemory.noteFocused]. A [ModifierNodeElement] (not
 * `Modifier.composed`) so registration lives in node attach/detach, outside composition.
 * Apply *before* the modifier that creates the actual focus target (`.focusable()`, etc.) --
 * [FocusRequesterModifierNode.requestFocus] resolves against the nearest target below this node.
 */
internal fun Modifier.focusKey(memory: FocusMemory, key: String): Modifier = this then FocusKeyElement(memory, key)

private data class FocusKeyElement(
    val memory: FocusMemory,
    val key: String,
) : ModifierNodeElement<FocusKeyNode>() {

    override fun create(): FocusKeyNode = FocusKeyNode(memory, key)

    override fun update(node: FocusKeyNode) {
        if (node.memory !== memory || node.key != key) {
            node.memory.unregister(node.key, node.target)
            node.memory.noteFocusEvent(node.key, hasFocus = false)
            node.memory = memory
            node.key = key
            node.memory.register(node.key, node.target)
        }
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "focusKey"
        properties["key"] = key
    }
}

private class FocusKeyNode(
    var memory: FocusMemory,
    var key: String,
) : DelegatingNode(), FocusRequesterModifierNode, FocusEventModifierNode {

    /** A separate object, not this node implementing [FocusTarget] directly: a member
     * `requestFocus()` here would shadow the `FocusRequesterModifierNode` extension it needs to
     * call.
     */
    val target: FocusTarget = object : FocusTarget {
        override fun requestFocus(): Boolean = this@FocusKeyNode.requestFocus()
    }

    override fun onAttach() {
        memory.register(key, target)
    }

    override fun onDetach() {
        memory.unregister(key, target)
        memory.noteFocusEvent(key, hasFocus = false)
    }

    /** `hasFocus`, not `isFocused`: most keyed elements wrap an inner focusable, and only report a
     * descendant as focused.
     */
    override fun onFocusEvent(focusState: FocusState) {
        memory.noteFocusEvent(key, focusState.hasFocus)
    }
}
