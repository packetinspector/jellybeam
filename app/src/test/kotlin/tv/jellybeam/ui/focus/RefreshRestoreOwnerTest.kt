package tv.jellybeam.ui.focus

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RefreshRestoreOwner] exercised directly, no Compose host or [FocusMemory]
 * (docs/15-focus-and-selection.md §3). Each test uses a [CompletableDeferred]
 * as the `frame` gate so an ownership transition's timing relative to the
 * one-frame wait is explicit, not timing-dependent.
 */
class RefreshRestoreOwnerTest {

    @Test
    fun `ownership lost during the frame wait cancels the trigger before eligible runs`() = runTest {
        val owner = RefreshRestoreOwner()
        val frameGate = CompletableDeferred<Unit>()
        var eligibleCalls = 0
        var restoreStarts = 0

        val trigger = launch {
            owner.onContentChanged(
                scope = this,
                frame = { frameGate.await() },
                eligible = { eligibleCalls++; true },
                restore = { restoreStarts++ },
            )
        }
        runCurrent() // trigger reaches `frame()` and suspends on frameGate

        owner.ownershipLost()
        frameGate.complete(Unit)
        trigger.join()

        assertEquals("epoch invalidation must short-circuit before eligible is even consulted", 0, eligibleCalls)
        assertEquals(0, restoreStarts)
    }

    @Test
    fun `eligible reading a live flag catches a transition that lands during the frame wait`() = runTest {
        // `isTop` is captured by reference, as a composable's `rememberUpdatedState`
        // would be, so `eligible` sees a mid-wait flip when it finally runs.
        val owner = RefreshRestoreOwner()
        val frameGate = CompletableDeferred<Unit>()
        var isTop = true
        var restoreStarts = 0

        val trigger = launch {
            owner.onContentChanged(
                scope = this,
                frame = { frameGate.await() },
                eligible = { isTop },
                restore = { restoreStarts++ },
            )
        }
        runCurrent()

        isTop = false // the transition a screen's own `LaunchedEffect(isTop)` hook reacts to
        frameGate.complete(Unit)
        trigger.join()

        assertEquals(0, restoreStarts)
    }

    @Test
    fun `a second trigger supersedes a running restore and inherits ownsFreeze`() = runTest {
        val owner = RefreshRestoreOwner()
        val firstRestoreGate = CompletableDeferred<Unit>()
        val secondRestoreGate = CompletableDeferred<Unit>()
        var firstCancelled = false
        var secondSawOwnsFreeze = false
        var secondRestoreStarted = false

        launch {
            owner.onContentChanged(
                scope = this,
                frame = {},
                eligible = { true },
                restore = {
                    try {
                        firstRestoreGate.await()
                    } catch (e: CancellationException) {
                        firstCancelled = true
                        throw e
                    }
                },
            )
        }
        runCurrent()
        assertTrue("restore started -> ownsFreeze true", owner.ownsFreeze)

        launch {
            owner.onContentChanged(
                scope = this,
                frame = {},
                eligible = { ownsFreeze -> secondSawOwnsFreeze = ownsFreeze; true },
                restore = {
                    secondRestoreStarted = true
                    secondRestoreGate.await()
                },
            )
        }
        runCurrent()

        assertTrue("the first restore's coroutine must actually be cancelled", firstCancelled)
        assertTrue("the second trigger's eligible must see the freeze as refresh-owned", secondSawOwnsFreeze)
        assertTrue(secondRestoreStarted)
        assertTrue(owner.ownsFreeze)

        secondRestoreGate.complete(Unit)
        runCurrent()
        assertFalse("a run that finishes on its own releases ownsFreeze", owner.ownsFreeze)
    }

    @Test
    fun `ownershipLost releaseFreeze controls whether ownsFreeze survives the cancellation`() = runTest {
        val owner = RefreshRestoreOwner()
        val restoreGate = CompletableDeferred<Unit>()

        launch {
            owner.onContentChanged(
                scope = this,
                frame = {},
                eligible = { true },
                restore = { restoreGate.await() },
            )
        }
        runCurrent()
        assertTrue(owner.ownsFreeze)

        owner.ownershipLost() // plain loss of top/resumed -- no new owner of the freeze
        assertTrue("a plain ownership loss leaves ownsFreeze true for a possible refresh successor", owner.ownsFreeze)

        launch {
            owner.onContentChanged(
                scope = this,
                frame = {},
                eligible = { true },
                restore = { restoreGate.await() },
            )
        }
        runCurrent()
        assertTrue(owner.ownsFreeze)

        owner.ownershipLost(releaseFreeze = true)
        assertFalse("releaseFreeze = true hands the freeze to the new owner", owner.ownsFreeze)
    }

    @Test
    fun `drawer-style eligible blocks while open and invalidates a trigger pending across the open`() = runTest {
        // Same shape as Library's own guard: `eligible = { !drawerOpen &&
        // refreshGuardEligible(...) }`.
        val owner = RefreshRestoreOwner()
        val frameGate = CompletableDeferred<Unit>()
        var drawerOpen = false
        var restoreStarts = 0

        val trigger = launch {
            owner.onContentChanged(
                scope = this,
                frame = { frameGate.await() },
                eligible = { !drawerOpen },
                restore = { restoreStarts++ },
            )
        }
        runCurrent()

        // Drawer opens then closes mid-wait; the flag alone would read false
        // again by the time eligible runs, but the epoch bump must still invalidate.
        drawerOpen = true
        owner.ownershipLost()
        drawerOpen = false
        frameGate.complete(Unit)
        trigger.join()

        assertEquals("epoch invalidation, not the flag's final value, must decide this", 0, restoreStarts)

        var secondEligible = false
        launch {
            owner.onContentChanged(
                scope = this,
                frame = {},
                eligible = { secondEligible = !drawerOpen; secondEligible },
                restore = { restoreStarts++ },
            )
        }
        runCurrent()

        assertTrue(secondEligible)
        assertEquals(1, restoreStarts)
    }
}
