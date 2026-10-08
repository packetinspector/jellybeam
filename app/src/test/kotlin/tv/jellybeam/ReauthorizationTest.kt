package tv.jellybeam

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.jellybeam_core.AccountInfo

/** docs/18 §2.1: a 401 re-authorizes the account the core named for it, or nobody; docs/21 §1.3:
 * it never opens under the crash prompt, nor the prompt over it.
 */
class ReauthorizationTest {

    private val a = AccountInfo(serverUrl = "http://a.test", userId = "u-a", userName = "a")
    private val b = AccountInfo(serverUrl = "http://b.test", userId = "u-b", userName = "b")

    @Test
    fun `the named account is re-authorized wherever it sits, active or not`() {
        assertEquals(0u, reauthorizationIndex(listOf(a, b), target = a))
        assertEquals(1u, reauthorizationIndex(listOf(a, b), target = b))
    }

    @Test
    fun `a 401 the core names nobody for prompts nobody, never the active account`() {
        assertNull("not in use, or naming nobody", reauthorizationIndex(listOf(a, b), target = null))
        assertNull("removed after the core named it", reauthorizationIndex(listOf(b), target = a))
    }

    /** The lookup whose number is [n], so a test can tell which one was applied. */
    private fun lookup(n: Int) = ReauthorizationLookup(listOf(a), activeIndex = 0u, targetIndex = n.toUInt())

    @Test
    fun `a crash prompt that opens during the lookups defers reauthorization until it closes`() = runTest {
        val prompt = MutableStateFlow(false)
        val firstLookupHeld = CompletableDeferred<Unit>()
        var lookups = 0
        val applied = mutableListOf<UInt?>()
        val resolving = launch {
            resolveReauthorization(
                promptVisible = { prompt.value },
                awaitPromptClosed = { prompt.first { !it } },
                lookUp = {
                    lookups += 1
                    if (lookups == 1) firstLookupHeld.await()
                    lookup(lookups)
                },
                apply = { applied += it.targetIndex },
            )
        }
        runCurrent()
        prompt.value = true
        firstLookupHeld.complete(Unit)
        runCurrent()
        assertEquals("deferred while the prompt is up", emptyList<UInt?>(), applied)

        prompt.value = false
        runCurrent()
        assertEquals("looked up again once it closed, then applied", listOf<UInt?>(2u), applied)
        resolving.join()
    }

    @Test
    fun `with no crash prompt the first lookup applies`() = runTest {
        val applied = mutableListOf<UInt?>()
        resolveReauthorization(
            promptVisible = { false },
            awaitPromptClosed = {},
            lookUp = { lookup(1) },
            apply = { applied += it.targetIndex },
        )
        assertEquals(listOf<UInt?>(1u), applied)
    }

    @Test
    fun `the crash prompt never opens over the sign-in surface`() {
        assertTrue(crashPromptMayOpen(externalPlayback = false, homeRooted = true, shownThisProcess = false, reauthorizing = false))
        assertFalse(crashPromptMayOpen(externalPlayback = false, homeRooted = true, shownThisProcess = false, reauthorizing = true))
        assertFalse(crashPromptMayOpen(externalPlayback = true, homeRooted = true, shownThisProcess = false, reauthorizing = false))
        assertFalse(crashPromptMayOpen(externalPlayback = false, homeRooted = false, shownThisProcess = false, reauthorizing = false))
        assertFalse(crashPromptMayOpen(externalPlayback = false, homeRooted = true, shownThisProcess = true, reauthorizing = false))
    }
}
