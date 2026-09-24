package tv.jellybeam.ui.focus

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * docs/15-focus-and-selection.md §3, against the real [FocusMemory]/[restoreNow]/
 * [RefreshRestoreOwner]: a refresh restore cancelled by a drawer/panel open leaves `frozen = true`,
 * and an in-place close runs no restore of its own, so unless that close releases the owner's
 * claim, every later [FocusMemory.noteFocused] is ignored and the next navigation restores a
 * stale key.
 */
class InPlaceCloseFreezeTest {

    private val neverPlaces = object : FocusTarget {
        override fun requestFocus(): Boolean = false
    }

    /** Starts a refresh-owned restore that never places, exactly as a guard would mid-refresh. */
    private fun kotlinx.coroutines.test.TestScope.startStuckRefreshRestore(
        clock: BroadcastFrameClock,
        owner: RefreshRestoreOwner,
        memory: FocusMemory,
    ) {
        launch(clock) {
            owner.onContentChanged(
                scope = this,
                frame = {},
                eligible = { true },
                restore = { memory.restoreNow(mutableStateOf(false), fallback = { neverPlaces }) },
            )
        }
        runCurrent()
        assertTrue("restore should be running and frozen", memory.frozen)
        assertTrue(owner.ownsFreeze)
    }

    @Test
    fun `in-place close releases the freeze a cancelled refresh restore left behind`() = runTest {
        val clock = BroadcastFrameClock()
        val memory = FocusMemory("old-card", null).apply { seeded = true }
        val owner = RefreshRestoreOwner()
        startStuckRefreshRestore(clock, owner, memory)

        owner.ownershipLost() // drawer (or action panel) opened
        runCurrent()
        assertTrue("restoreNow keeps frozen on cancellation (its contract)", memory.frozen)

        // Library's / Detail's in-place close contract.
        if (owner.releaseCancelledFreeze()) memory.frozen = false

        memory.noteFocused("new-card")
        assertEquals("focus recording must resume after the in-place close", "new-card", memory.lastKey)
        assertFalse(owner.ownsFreeze)
    }

    @Test
    fun `without the release the stale key would survive the close`() = runTest {
        // Pins the defect the test above guards against.
        val clock = BroadcastFrameClock()
        val memory = FocusMemory("old-card", null).apply { seeded = true }
        val owner = RefreshRestoreOwner()
        startStuckRefreshRestore(clock, owner, memory)
        owner.ownershipLost()
        runCurrent()

        memory.noteFocused("new-card")
        assertEquals("old-card", memory.lastKey)
    }

    @Test
    fun `release is refused while a restore is still running`() = runTest {
        val clock = BroadcastFrameClock()
        val memory = FocusMemory("old-card", null).apply { seeded = true }
        val owner = RefreshRestoreOwner()
        startStuckRefreshRestore(clock, owner, memory)

        assertFalse("a live restore unfreezes on its own; the claim must stay", owner.releaseCancelledFreeze())
        assertTrue(owner.ownsFreeze)
        owner.ownershipLost()
        runCurrent()
    }

    @Test
    fun `release is a no-op when nothing was cancelled`() {
        assertFalse(RefreshRestoreOwner().releaseCancelledFreeze())
    }

    @Test
    fun `a panel dropped for a navigation keeps the navigation freeze`() = runTest {
        // docs/15-focus-and-selection.md §3, effect order: panel open cancels the refresh restore
        // (claim kept), the page loses top for the pushed series page, then the dropped panel's
        // `panelOpen = false` effect runs.
        val clock = BroadcastFrameClock()
        val memory = FocusMemory("old-card", null).apply { seeded = true }
        val owner = RefreshRestoreOwner()
        startStuckRefreshRestore(clock, owner, memory)

        owner.ownershipLost() // panel opened
        runCurrent()
        memory.frozen = true  // FocusRestorer's `!isTop` branch
        owner.ownershipLost() // Detail's own `LaunchedEffect(isTop)` hook

        // Detail's panel-close hook with the page no longer top.
        assertFalse(owner.releaseCancelledFreezeInPlace(memory, ownsFocus = false))

        assertTrue("the navigation freeze must survive the dropped panel", memory.frozen)
        assertTrue("the owner's claim stays for the next in-place regain", owner.ownsFreeze)
        memory.noteFocused("incidental-card")
        assertEquals("old-card", memory.lastKey)
    }

    @Test
    fun `the shared in-place hook releases only while the screen owns focus`() = runTest {
        val clock = BroadcastFrameClock()
        val memory = FocusMemory("old-card", null).apply { seeded = true }
        val owner = RefreshRestoreOwner()
        startStuckRefreshRestore(clock, owner, memory)
        owner.ownershipLost()
        runCurrent()

        assertTrue(owner.releaseCancelledFreezeInPlace(memory, ownsFocus = true))
        assertFalse(memory.frozen)
        memory.noteFocused("new-card")
        assertEquals("new-card", memory.lastKey)
        // Nothing left to release: a second call is a no-op either way.
        assertFalse(owner.releaseCancelledFreezeInPlace(memory, ownsFocus = true))
    }
}
