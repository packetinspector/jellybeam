package tv.jellybeam.data

import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import tv.jellybeam.player.authorizationRecoveryCoordinator
import uniffi.jellybeam_core.AccountEpochs
import uniffi.jellybeam_core.AccountIdentity
import uniffi.jellybeam_core.ChangeListener
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
        ) { _, method, _ -> if (method.name.substringBefore('-') == "accountEpochs") AccountEpochs(epoch = 0uL, parked = null) else null } as JellybeamCoreInterface,
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

    @Test
    fun `a live-events 401 asks to re-authorize the account it names`() = runBlocking<Unit> {
        val listener = CompletableDeferred<ChangeListener>()
        val core = Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(JellybeamCoreInterface::class.java),
        ) { _, method, args ->
            when (method.name.substringBefore('-')) {
                "accountEpochs" -> AccountEpochs(epoch = 0uL, parked = null)
                "setChangeListener" -> { listener.complete(args!![0] as ChangeListener); null }
                else -> null
            }
        } as JellybeamCoreInterface
        val scope = CoroutineScope(Job())
        val gateway = RealCoreGateway(core = CompletableDeferred(core), backgroundScope = scope)
        val collecting = gateway.changeEvents().onEach { }.launchIn(scope)

        withTimeout(5_000) { listener.await() }.onUnauthorized(rejected)
        collecting.cancel()

        val request = authorizationRecoveryCoordinator.requests.value
        assertEquals(rejected, request?.account)
        assertEquals("a socket's 401, not a failed playback", false, request?.failedPlayback)
    }
}
