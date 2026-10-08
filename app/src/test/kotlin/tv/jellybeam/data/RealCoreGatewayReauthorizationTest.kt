package tv.jellybeam.data

import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import tv.jellybeam.player.authorizationRecoveryCoordinator
import uniffi.jellybeam_core.AccountIdentity
import uniffi.jellybeam_core.CoreException
import uniffi.jellybeam_core.ItemDetail
import uniffi.jellybeam_core.JellybeamCoreInterface
import uniffi.jellybeam_core.SeerrHome

/** docs/18 §2.1: a core call's 401 asks to re-authorize the account it names; Seerr's never asks. */
class RealCoreGatewayReauthorizationTest {

    private val rejected = AccountIdentity(serverUrl = "http://a.test", userId = "u-a")

    /** Throws [error] from the calls under test, from Kotlin so no proxy wraps it; the rest answer null. */
    private class Rejecting(
        private val error: CoreException,
        delegate: JellybeamCoreInterface = Proxy.newProxyInstance(
            JellybeamCoreInterface::class.java.classLoader,
            arrayOf(JellybeamCoreInterface::class.java),
        ) { _, method, _ -> if (method.name.substringBefore('-') == "accountEpoch") 0L else null } as JellybeamCoreInterface,
    ) : JellybeamCoreInterface by delegate {
        override fun getItemDetail(itemId: String, accountEpoch: ULong?): ItemDetail = throw error
        override fun seerrHome(): SeerrHome = throw error
    }

    private fun gateway(error: CoreException) = RealCoreGateway(
        core = CompletableDeferred<JellybeamCoreInterface>(Rejecting(error)),
        backgroundScope = CoroutineScope(Job()),
    )

    private fun clearPending() {
        authorizationRecoveryCoordinator.requests.value?.let { authorizationRecoveryCoordinator.consume(it.id) }
    }

    @Before fun before() = clearPending()

    @After fun after() = clearPending()

    @Test
    fun `a browse call's 401 asks to re-authorize the account it names`() = runBlocking<Unit> {
        val gateway = gateway(CoreException.Unauthorized(account = rejected))

        runCatching { gateway.getItemDetail("item-1", accountEpoch = null) }

        val request = authorizationRecoveryCoordinator.requests.value
        assertEquals(rejected, request?.account)
        assertEquals("a call's 401, not a failed playback", false, request?.failedPlayback)
    }

    @Test
    fun `Seerr's own 401 never asks`() = runBlocking<Unit> {
        val gateway = gateway(CoreException.Unauthorized(account = null))

        runCatching { gateway.seerrHome() }

        assertNull(authorizationRecoveryCoordinator.requests.value)
    }
}
