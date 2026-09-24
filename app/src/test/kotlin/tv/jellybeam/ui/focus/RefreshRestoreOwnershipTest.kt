package tv.jellybeam.ui.focus

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins two [RefreshRestoreOwner] contracts (docs/15-focus-and-selection.md §3):
 * `restoreNow` leaves `memory.frozen = true` when cancelled mid-flight (navigation
 * contract), and `refreshGuardEligible` must clear a freeze it owns but still
 * respect one it doesn't.
 */
class RefreshRestoreOwnershipTest {

    @Test
    fun `cancelled restoreNow leaves frozen true`() = runTest {
        val clock = BroadcastFrameClock()
        val memory = FocusMemory("removed-card", null).apply { seeded = true }
        // Never resolves, so restoreNow stays stuck in its retry loop like a
        // real target that never arrives.
        val neverPlaces = object : FocusTarget {
            override fun requestFocus(): Boolean = false
        }
        val focusGate = mutableStateOf(false)

        val job = launch(clock) {
            memory.restoreNow(focusGate, fallback = { neverPlaces })
        }
        runCurrent()
        assertTrue("restoreNow should freeze memory while running", memory.frozen)

        job.cancelAndJoin()
        assertTrue("cancellation must leave frozen true (navigation contract)", memory.frozen)
    }

    @Test
    fun `guard is eligible when it owns the leftover freeze`() {
        assertTrue(
            refreshGuardEligible(
                isTop = true,
                seeded = true,
                resumed = true,
                hasFocus = false,
                frozen = true,
                ownsFreeze = true,
            ),
        )
    }

    @Test
    fun `guard stays blocked by a freeze it does not own`() {
        assertFalse(
            refreshGuardEligible(
                isTop = true,
                seeded = true,
                resumed = true,
                hasFocus = false,
                frozen = true,
                ownsFreeze = false,
            ),
        )
    }

    @Test
    fun `an unfrozen memory is eligible regardless of ownsFreeze`() {
        assertTrue(
            refreshGuardEligible(
                isTop = true,
                seeded = true,
                resumed = true,
                hasFocus = false,
                frozen = false,
                ownsFreeze = false,
            ),
        )
    }

    @Test
    fun `not top, not seeded, not resumed, or holding focus each block eligibility`() {
        // All-true baseline with one flag flipped false at a time.
        assertFalse(refreshGuardEligible(isTop = false, seeded = true, resumed = true, hasFocus = false, frozen = false, ownsFreeze = false))
        assertFalse(refreshGuardEligible(isTop = true, seeded = false, resumed = true, hasFocus = false, frozen = false, ownsFreeze = false))
        assertFalse(refreshGuardEligible(isTop = true, seeded = true, resumed = false, hasFocus = false, frozen = false, ownsFreeze = false))
        assertFalse(refreshGuardEligible(isTop = true, seeded = true, resumed = true, hasFocus = true, frozen = false, ownsFreeze = false))
    }
}
