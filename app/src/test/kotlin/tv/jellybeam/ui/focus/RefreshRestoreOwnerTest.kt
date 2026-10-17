package tv.jellybeam.ui.focus

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
    fun `a second trigger supersedes a running restore and sees its own token`() = runTest {
        val owner = RefreshRestoreOwner()
        val firstRestoreGate = CompletableDeferred<Unit>()
        val secondRestoreGate = CompletableDeferred<Unit>()
        var firstCancelled = false
        var secondSawOwnTokens = -1
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
        assertEquals("restore running -> one own token", 1, owner.ownTokens)

        launch {
            owner.onContentChanged(
                scope = this,
                frame = {},
                eligible = { own -> secondSawOwnTokens = own; true },
                restore = {
                    secondRestoreStarted = true
                    secondRestoreGate.await()
                },
            )
        }
        runCurrent()

        assertTrue("the first restore's coroutine must actually be cancelled", firstCancelled)
        assertEquals("the second trigger's eligible must discount the running restore", 1, secondSawOwnTokens)
        assertTrue(secondRestoreStarted)

        secondRestoreGate.complete(Unit)
        runCurrent()
        assertEquals("a finished run holds nothing", 0, owner.ownTokens)
    }

    @Test
    fun `ownershipLost cancels the running restore and drops its own token count`() = runTest {
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
        assertEquals(1, owner.ownTokens)

        owner.ownershipLost()
        assertEquals(0, owner.ownTokens)
    }

    @Test
    fun `drawer-style eligible blocks a trigger the drawer opened across, and a later one restores`() = runTest {
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

        // The drawer opens mid-wait, as onDrawerOpened writes the flag and calls ownershipLost.
        drawerOpen = true
        owner.ownershipLost()
        frameGate.complete(Unit)
        trigger.join()

        assertEquals("the live flag decides", 0, restoreStarts)
        drawerOpen = false

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
