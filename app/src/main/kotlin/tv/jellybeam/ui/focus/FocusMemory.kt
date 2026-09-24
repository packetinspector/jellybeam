package tv.jellybeam.ui.focus

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.isActive
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
 * - [frozen]: while true, [noteFocused] does not write [lastKey] -- default-focus churn during a
 *   pop or in-flight restore must not corrupt the record.
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

    var frozen: Boolean = false
    var seeded: Boolean = false

    var scrollTo: (suspend (key: String) -> Boolean)? = null

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

    /** §4: captures the invoker -- the key focused right before a nested surface opens. */
    fun captureInvoker() {
        invokerKey = lastKey
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
 * The placement work §2/§5 describe: freeze, resolve §2's precedence order, place by key (falling
 * back through §2 rule 3's declared primary), clear the invoker, mark [memory] seeded, log,
 * unfreeze. Extracted from [FocusRestorer]'s becoming-top effect so a second, non-`isTop` restore
 * trigger (e.g. a drawer closing) can reuse the same logic. Callers own their own
 * freeze-around-the-trigger and `ready`-style gating; only [FocusRestorer] gates on
 * `seeded`/`wasTop`.
 *
 * @return the key that was placed, or null if every candidate missed and placement fell through to
 *         [fallback].
 */
internal suspend fun FocusMemory.restoreNow(
    focusGate: MutableState<Boolean>,
    selectedKey: () -> String? = { null },
    fallback: () -> FocusTarget?,
    tag: String = "",
): String? {
    frozen = true
    try {
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
        return placed
    } finally {
        // Only a run that finished on its own may unfreeze; a cancelled run resumes here after
        // the replacement `!isTop` run already froze the memory.
        if (coroutineContext.isActive) frozen = false
    }
}

/**
 * Pure eligibility check for a screen's second, same-composition restore trigger (alongside
 * [FocusRestorer]'s becoming-top effect) for "a live data refresh dropped the focused item and
 * left focus nowhere". A `frozen` left true by a cancelled refresh-restore job must not block its
 * own replacement, while a freeze belonging to something else still must.
 *
 * @param ownsFreeze true only while a refresh-restore coroutine that itself set `memory.frozen =
 *   true` has not yet had its own [restoreNow] call return normally.
 */
internal fun refreshGuardEligible(
    isTop: Boolean,
    seeded: Boolean,
    resumed: Boolean,
    hasFocus: Boolean,
    frozen: Boolean,
    ownsFreeze: Boolean,
): Boolean = isTop && seeded && resumed && !hasFocus && (!frozen || ownsFreeze)

/**
 * docs/15-focus-and-selection.md §2-§5's one becoming-top restore effect. Screens delete their
 * own `LaunchedEffect(isTop)` restore blocks and call this once, at the top of their composable.
 * Cancelled by navigation like any becoming-top effect, so an outgoing retained layer can't keep
 * re-opening [focusGate] or steal focus from the new top screen.
 *
 * @param ready gates the restore on data the screen needs (e.g. a list still loading); while
 *   false, nothing is placed and [memory] stays frozen only while `!isTop`.
 * @param selectedKey §2 rule 2 -- resolved lazily, once, only if reached.
 * @param fallback §2 rule 3, this surface's one declared primary action.
 * @param tag a short label for the [PerfLog] line; empty is fine.
 */
@Composable
internal fun FocusRestorer(
    memory: FocusMemory,
    isTop: Boolean,
    focusGate: MutableState<Boolean>,
    ready: Boolean = true,
    selectedKey: () -> String? = { null },
    fallback: () -> FocusTarget?,
    tag: String = "",
) {
    var wasTop by remember { mutableStateOf(isTop) }
    LaunchedEffect(isTop, ready) {
        if (!isTop) {
            memory.frozen = true
            wasTop = false
            return@LaunchedEffect
        }
        // Top but not ready: keep the freeze state, and keep wasTop as-is so the ready flip still
        // counts as a becoming-top.
        if (!ready) return@LaunchedEffect
        if (memory.seeded && wasTop) {
            // A reload finished while already top: nothing to place, but tracking must stay live.
            memory.frozen = false
            return@LaunchedEffect
        }
        memory.restoreNow(focusGate, selectedKey, fallback, tag)
        wasTop = true
    }
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
    }

    /** `hasFocus`, not `isFocused`: most keyed elements wrap an inner focusable, and only report a
     * descendant as focused.
     */
    override fun onFocusEvent(focusState: FocusState) {
        if (focusState.hasFocus) memory.noteFocused(key)
    }
}
