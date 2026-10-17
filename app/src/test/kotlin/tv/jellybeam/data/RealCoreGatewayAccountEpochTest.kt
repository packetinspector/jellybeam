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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.jellybeam.player.AuthorizationRecoveryCoordinator
import uniffi.jellybeam_core.AccountEpochs
import uniffi.jellybeam_core.AccountIdentity
import uniffi.jellybeam_core.AccountInfo
import uniffi.jellybeam_core.CoreException
import uniffi.jellybeam_core.ImageKind
import uniffi.jellybeam_core.ImageUrlPrefix
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
            if (name == "accountEpochs") return AccountEpochs(epoch = epoch.toULong(), parked = parked)
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

    private fun gateway(core: InvocationHandler) = RealCoreGateway(
        core = CompletableDeferred(
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(JellybeamCoreInterface::class.java), core)
                as JellybeamCoreInterface,
        ),
        backgroundScope = CoroutineScope(Job()),
        // Its own coordinator: a re-authorization here must not open the shared quiet window.
        authorizationRecovery = AuthorizationRecoveryCoordinator(nowMs = { 0L }),
    )

    private val accountCalls: List<Pair<String, suspend CoreGateway.() -> Unit>> = listOf(
        "restoreSession" to { restoreSession() },
        "signIn" to { signIn("http://example.test", "user", "pw") },
        "reauthorizeSession" to { reauthorizeSession(0u, "user", "pw") },
        "completeQuickConnect" to { completeQuickConnect("http://example.test", "secret") },
        "completeQuickConnectReauthorization" to { completeQuickConnectReauthorization(0u, "secret") },
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

    /** A caller cancelled mid-call still publishes the epoch the core moved to, or the next request carries a stale one. */
    @Test
    fun `a cancelled account call still publishes the core's epoch`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val core = object : InvocationHandler {
            override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? = when (method.name.substringBefore('-')) {
                "accountEpochs" -> AccountEpochs(epoch = 1uL, parked = null)
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

        withTimeout(5_000) { gateway.awaitAccountRestored() }
        assertEquals(1uL, gateway.mintPlaybackRequest().accountEpoch)
    }

    /** docs/18 §2.1: a re-authorization that landed in the core opens its quiet window even when the
     * sign-in surface was closed (caller cancelled) while the call ran.
     */
    @Test
    fun `a re-authorization lands its quiet window though the caller was cancelled`() = runBlocking {
        val calls: List<Pair<String, suspend CoreGateway.() -> Unit>> = listOf(
            "reauthorizeSession" to { reauthorizeSession(0u, "user", "pw") },
            "completeQuickConnectReauthorization" to { completeQuickConnectReauthorization(0u, "secret") },
        )
        val account = AccountIdentity(serverUrl = "http://example.test", userId = "u1")
        for ((name, call) in calls) {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val core = object : InvocationHandler {
                override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? = when (method.name.substringBefore('-')) {
                    "accountEpochs" -> AccountEpochs(epoch = 1uL, parked = null)
                    else -> {
                        entered.countDown()
                        release.await()
                        AccountInfo(serverUrl = "http://example.test", userId = "u1", userName = "user")
                    }
                }
            }
            val recovery = AuthorizationRecoveryCoordinator(nowMs = { 0L })
            val gateway = RealCoreGateway(
                core = CompletableDeferred(Proxy.newProxyInstance(javaClass.classLoader, arrayOf(JellybeamCoreInterface::class.java), core) as JellybeamCoreInterface),
                backgroundScope = CoroutineScope(Job()),
                authorizationRecovery = recovery,
            )
            recovery.request(account)
            val caller = CoroutineScope(Job())
            val running = caller.launch { gateway.call() }
            entered.await()
            running.cancel()
            release.countDown()
            running.join()
            withTimeout(5_000) { gateway.awaitAccountRestored() }

            assertNull("$name: the pending request for the account is answered", recovery.requests.value)
            recovery.request(account)
            assertNull("$name: a straggler for the account is dropped", recovery.requests.value)
        }
    }

    /** docs/18 §2.1: the core installs the next account before the call publishes its epoch; a card
     * of the published account composed in that gap gets the placeholder, never the new account's URL.
     */
    @Test
    fun `an image url mid account call is the placeholder until the new epoch publishes`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val coreEpoch = java.util.concurrent.atomic.AtomicLong()
        val core = object : InvocationHandler {
            override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? = when (method.name.substringBefore('-')) {
                "accountEpochs" -> AccountEpochs(epoch = coreEpoch.get().toULong(), parked = null)
                "imageUrlPrefix" -> ImageUrlPrefix(epoch = coreEpoch.get().toULong(), baseUrl = "http://b${coreEpoch.get()}.example.test", token = "synthetic")
                "switchSession" -> {
                    coreEpoch.incrementAndGet()
                    entered.countDown()
                    release.await()
                    null
                }
                else -> error("unexpected core call ${method.name}")
            }
        }
        val gateway = gateway(core)
        val call = CoroutineScope(Job()).launch { gateway.switchSession(0u) }
        check(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))

        assertNull(gateway.imageUrl("item", ImageKind.PRIMARY, "tag", 300u, null))

        release.countDown()
        withTimeout(5_000) { call.join() }
        val url = gateway.imageUrl("item", ImageKind.PRIMARY, "tag", 300u, null)
        assertNotNull(url)
        assertTrue(url, url!!.startsWith("http://b1.example.test"))
    }

    private companion object {
        val ACCOUNT_CALLS = setOf(
            "restoreSession",
            "signIn",
            "reauthorizeSession",
            "completeQuickConnect",
            "completeQuickConnectReauthorization",
            "switchSession",
            "removeSession",
        )
    }
}
