package tv.jellybeam.data

import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.jellybeam_core.AccountEpochs
import uniffi.jellybeam_core.JellybeamCoreInterface

/** docs/07 Watch state: the foreground and background notices reach the core once each, off the caller's thread. */
class RealCoreGatewayForegroundTest {

    private class Recording(
        delegate: JellybeamCoreInterface = Proxy.newProxyInstance(
            JellybeamCoreInterface::class.java.classLoader,
            arrayOf(JellybeamCoreInterface::class.java),
        ) { _, method, _ -> if (method.name.substringBefore('-') == "accountEpochs") AccountEpochs(epoch = 0uL, parked = null) else null } as JellybeamCoreInterface,
    ) : JellybeamCoreInterface by delegate {
        val threads = mutableListOf<Thread>()
        val backgroundThreads = mutableListOf<Thread>()
        override fun onForeground() {
            threads += Thread.currentThread()
        }
        override fun onBackground() {
            backgroundThreads += Thread.currentThread()
        }
    }

    @Test
    fun `onForeground calls the core once on an IO thread`() = runBlocking<Unit> {
        val core = Recording()
        val gateway = RealCoreGateway(
            core = CompletableDeferred<JellybeamCoreInterface>(core),
            backgroundScope = CoroutineScope(Job()),
        )

        gateway.onForeground()

        assertEquals(1, core.threads.size)
        assertNotSame(Thread.currentThread(), core.threads.single())
        assertTrue(core.threads.single().name.startsWith("DefaultDispatcher-worker"))
    }

    @Test
    fun `onBackground calls the core once on an IO thread`() = runBlocking<Unit> {
        val core = Recording()
        val gateway = RealCoreGateway(
            core = CompletableDeferred<JellybeamCoreInterface>(core),
            backgroundScope = CoroutineScope(Job()),
        )

        gateway.onBackground()

        assertEquals(1, core.backgroundThreads.size)
        assertEquals(0, core.threads.size)
        assertTrue(core.backgroundThreads.single().name.startsWith("DefaultDispatcher-worker"))
    }
}
