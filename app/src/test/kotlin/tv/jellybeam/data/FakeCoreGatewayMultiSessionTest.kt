package tv.jellybeam.data

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.jellybeam_core.AccountInfo
import uniffi.jellybeam_core.CoreException

/** [FakeCoreGateway]'s multi-session surface: seeded/mutable account state, and
 * [FakeCoreGateway.switchSession] throws for an unconfigured index (no sensible default
 * [AccountInfo]).
 */
class FakeCoreGatewayMultiSessionTest {

    private fun account(name: String) = AccountInfo(serverUrl = "https://media.example.test", userId = "u-$name", userName = name)

    @Test
    fun `lists no accounts and no active index by default`() = runTest {
        val gateway = FakeCoreGateway()
        assertTrue(gateway.listAccounts().isEmpty())
        assertEquals(null, gateway.activeAccountIndex())
    }

    @Test
    fun `lists seeded accounts and active index verbatim`() = runTest {
        val primary = account("Primary User")
        val secondary = account("Secondary User")
        val gateway = FakeCoreGateway(accountsList = listOf(primary, secondary), activeAccountIndexValue = 1u)

        assertEquals(listOf(primary, secondary), gateway.listAccounts())
        assertEquals(1u, gateway.activeAccountIndex())
    }

    @Test
    fun `switch session returns the configured account and records the call`() = runTest {
        val secondary = account("Secondary User")
        val gateway = FakeCoreGateway(switchSessionResultsByIndex = mapOf(1u to Result.success(secondary)))

        val result = gateway.switchSession(1u)

        assertEquals(secondary, result)
        assertEquals(listOf(1u), gateway.switchSessionCalls)
    }

    @Test
    fun `switch session propagates a configured failure`() {
        val failure = CoreException.NotSignedIn()
        val gateway = FakeCoreGateway(switchSessionResultsByIndex = mapOf(0u to Result.failure(failure)))

        val thrown = assertThrows(CoreException.NotSignedIn::class.java) {
            runBlocking { gateway.switchSession(0u) }
        }
        assertEquals(failure, thrown)
    }

    @Test
    fun `switch session throws for an unconfigured index rather than silently succeeding`() {
        val gateway = FakeCoreGateway()
        assertThrows(UnconfiguredFakeCall::class.java) {
            runBlocking { gateway.switchSession(0u) }
        }
    }
}
