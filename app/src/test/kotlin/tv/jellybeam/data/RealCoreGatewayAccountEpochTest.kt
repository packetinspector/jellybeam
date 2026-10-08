package tv.jellybeam.data

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.jellybeam_core.AccountInfo
import uniffi.jellybeam_core.CoreException
import uniffi.jellybeam_core.JellybeamCoreInterface

/**
 * docs/18 §2.1: every account call re-reads the core's account epoch, failed ones included, so a
 * playback request minted afterwards carries the epoch the core will admit.
 */
class RealCoreGatewayAccountEpochTest {

    /** Each account call advances the epoch, as the core's account reset does, then may fail. */
    private class Core(private val failAccountCalls: Boolean) : InvocationHandler {
        var epoch = 0L

        /** The epoch a playback kept across an account change owns, if any. */
        var parked: ULong? = null

        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            // Kotlin mangles names of functions taking or returning unsigned types.
            val name = method.name.substringBefore('-')
            if (name == "accountEpoch") return epoch
            if (name == "parkedAccountEpoch") return parked
            check(name in ACCOUNT_CALLS) { "unexpected core call $name" }
            epoch += 1
            if (failAccountCalls) throw CoreException.Api("synthetic failure")
            return when (method.returnType) {
                AccountInfo::class.java -> AccountInfo(serverUrl = "http://example.test", userId = "u1", userName = "user")
                java.lang.Boolean.TYPE -> true
                else -> null
            }
        }
    }

    private fun gateway(core: Core) = RealCoreGateway(
        core = CompletableDeferred(
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(JellybeamCoreInterface::class.java), core)
                as JellybeamCoreInterface,
        ),
        backgroundScope = CoroutineScope(Job()),
    )

    private val accountCalls: List<Pair<String, suspend CoreGateway.() -> Unit>> = listOf(
        "restoreSession" to { restoreSession() },
        "signIn" to { signIn("http://example.test", "user", "pw") },
        "reauthorizeSession" to { reauthorizeSession(0u, "user", "pw") },
        "completeQuickConnect" to { completeQuickConnect("http://example.test", "secret") },
        "completeQuickConnectReauthorization" to { completeQuickConnectReauthorization(0u, "secret") },
        "signOut" to { signOut() },
        "switchSession" to { switchSession(0u) },
        "removeSession" to { removeSession(0u) },
    )

    @Test
    fun `every account call refreshes the epoch a playback request carries`() = runBlocking {
        for (failing in listOf(false, true)) {
            for ((name, call) in accountCalls) {
                val core = Core(failAccountCalls = failing)
                val gateway = gateway(core)

                runCatching { gateway.call() }

                assertEquals("$name (failing=$failing)", core.epoch.toULong(), gateway.mintPlaybackRequest().accountEpoch)
            }
        }
    }

    /** docs/18 §2.1: a playback the core kept on its own account across a switch still owns its epoch;
     * one it ended does not. A request can name that epoch to stay on its account.
     */
    @Test
    fun `a parked epoch keeps playback ownership open across a switch`() = runBlocking {
        for (parked in listOf(true, false)) {
            val core = Core(failAccountCalls = false)
            val gateway = gateway(core)
            val playing = gateway.mintPlaybackRequest().accountEpoch
            if (parked) core.parked = playing

            gateway.switchSession(0u)

            assertEquals("parked=$parked", parked, gateway.playbackOwnershipOpen(playing))
            assertEquals(playing, gateway.mintPlaybackRequest(accountEpoch = playing).accountEpoch)
        }
    }

    @Test
    fun `requests are minted in increasing order`() {
        val gateway = gateway(Core(failAccountCalls = false))

        val seqs = List(3) { gateway.mintPlaybackRequest().seq }

        assertEquals(listOf(1uL, 2uL, 3uL), seqs)
    }

    /** A caller cancelled mid-call must still reopen playback ownership, or every later plan waits forever. */
    @Test
    fun `a cancelled account call still reopens playback ownership`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val core = object : InvocationHandler {
            override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? = when (method.name.substringBefore('-')) {
                "accountEpoch" -> 1L
                "parkedAccountEpoch" -> null
                else -> {
                    entered.countDown()
                    release.await()
                    AccountInfo(serverUrl = "http://example.test", userId = "u1", userName = "user")
                }
            }
        }
        val gateway = RealCoreGateway(
            core = CompletableDeferred(Proxy.newProxyInstance(javaClass.classLoader, arrayOf(JellybeamCoreInterface::class.java), core) as JellybeamCoreInterface),
            backgroundScope = CoroutineScope(Job()),
        )
        val caller = CoroutineScope(Job())
        val call = caller.launch { gateway.signIn("http://example.test", "user", "pw") }
        entered.await()
        call.cancel()
        release.countDown()
        call.join()

        withTimeout(5_000) { gateway.awaitAccountCalls() }
        assertEquals(1uL, gateway.mintPlaybackRequest().accountEpoch)
    }

    private companion object {
        val ACCOUNT_CALLS = setOf(
            "restoreSession",
            "signIn",
            "reauthorizeSession",
            "completeQuickConnect",
            "completeQuickConnectReauthorization",
            "signOut",
            "switchSession",
            "removeSession",
        )
    }
}
