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
import uniffi.jellybeam_core.JellybeamCoreInterface

/** docs/07 Watch state: the foreground notice reaches the core once, off the caller's thread. */
class RealCoreGatewayForegroundTest {

    private class Recording(
        delegate: JellybeamCoreInterface = Proxy.newProxyInstance(
            JellybeamCoreInterface::class.java.classLoader,
            arrayOf(JellybeamCoreInterface::class.java),
        ) { _, method, _ -> if (method.name.substringBefore('-') == "accountEpoch") 0L else null } as JellybeamCoreInterface,
    ) : JellybeamCoreInterface by delegate {
        val threads = mutableListOf<Thread>()
        override fun onForeground() {
            threads += Thread.currentThread()
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
}
