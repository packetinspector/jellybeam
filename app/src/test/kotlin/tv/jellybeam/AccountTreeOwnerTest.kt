package tv.jellybeam

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** docs/18 §2.1: every account change resets session state once and advances the epoch once. */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountTreeOwnerTest {

    private val events = mutableListOf<String>()
    private val coreEpoch = epochsFlow()
    private val owner = AccountTreeOwner(coreEpoch) { events += "reset" }
    private val restored = CompletableDeferred<Unit>()

    /** Starts the follower, then (unless [restore] is false) completes the restore. */
    private fun TestScope.startFollowing(restore: Boolean = true) {
        backgroundScope.launch { owner.follow(restored::await) }
        runCurrent()
        if (restore) {
            restored.complete(Unit)
            runCurrent()
        }
    }

    private fun TestScope.moveCoreEpoch() {
        coreEpoch.epoch = coreEpoch.epoch + 1uL
        runCurrent()
    }

    // -- Core-epoch following (docs/18 §2.1) -----------------------------------

    @Test
    fun `an epoch move before the restore completes never resets the tree`() = runTest {
        startFollowing(restore = false)

        moveCoreEpoch()
        restored.complete(Unit)
        runCurrent()

        assertEquals("cold-start restore is not an account change: no reset", emptyList<String>(), events)
        assertEquals(0, owner.epoch)
    }

    @Test
    fun `an epoch value written again does not reset`() = runTest {
        startFollowing()

        coreEpoch.epoch = coreEpoch.epoch
        runCurrent()

        assertEquals(emptyList<String>(), events)
        assertEquals(0, owner.epoch)
    }

    @Test
    fun `every account change is one reset and one advance`() = runTest {
        startFollowing()

        // Removing the active account installs the next one in the same core change (docs/18 §2.1).
        listOf(
            "sign-in",
            "switch",
            "add server",
            "reauthentication",
            "remove active, others left",
            "remove active, none left",
        ).forEachIndexed { index, change ->
            moveCoreEpoch()

            assertEquals("$change: resets once", index + 1, events.size)
            assertEquals("$change: advances once", index + 1, owner.epoch)
        }
    }

    @Test
    fun `a second account change resets again`() = runTest {
        startFollowing()
        moveCoreEpoch()

        moveCoreEpoch()

        assertEquals(listOf("reset", "reset"), events)
        assertEquals(2, owner.epoch)
    }
}
