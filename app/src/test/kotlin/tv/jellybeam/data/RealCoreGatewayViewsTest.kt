package tv.jellybeam.data

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import uniffi.jellybeam_core.AccountEpochs
import uniffi.jellybeam_core.AccountInfo
import uniffi.jellybeam_core.ChangeEvent
import uniffi.jellybeam_core.ChangeListener
import uniffi.jellybeam_core.CoreException
import uniffi.jellybeam_core.JellybeamCoreInterface
import uniffi.jellybeam_core.ViewKind
import uniffi.jellybeam_core.ViewSnapshot

/** docs/07 §5: the views list refreshes on a views change or a merged refresh, never on item changes. */
class RealCoreGatewayViewsTest {

    private class Core : InvocationHandler {
        val calls = CopyOnWriteArrayList<String>()
        val viewReads = AtomicInteger()

        @Volatile
        var epoch = 0uL

        @Volatile
        var viewsList: List<ViewSnapshot> = emptyList()

        /** Runs inside the core's openMirror; may block or throw. */
        @Volatile
        var onOpenMirror: () -> Unit = {}

        @Volatile
        var listener: ChangeListener? = null

        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            val name = method.name.substringBefore('-')
            calls += name
            return when (name) {
                "accountEpochs" -> AccountEpochs(epoch = epoch, parked = null)
                "switchSession" -> {
                    epoch += 1uL
                    AccountInfo(serverUrl = "http://example.test", userId = "u1", userName = "user")
                }
                "setChangeListener" -> {
                    listener = args!![0] as ChangeListener
                    null
                }
                "views" -> {
                    viewReads.incrementAndGet()
                    viewsList
                }
                else -> null
            }
        }
    }

    /** Answers openMirror from Kotlin so a [CoreException] is not wrapped by the proxy; the rest goes to [core]. */
    private class MirrorOpening(private val core: Core, delegate: JellybeamCoreInterface) : JellybeamCoreInterface by delegate {
        override fun openMirror() {
            core.calls += "openMirror"
            core.onOpenMirror()
        }
    }

    private fun gateway(core: Core) = RealCoreGateway(
        core = CompletableDeferred(
            MirrorOpening(
                core,
                Proxy.newProxyInstance(javaClass.classLoader, arrayOf(JellybeamCoreInterface::class.java), core)
                    as JellybeamCoreInterface,
            ),
        ),
        backgroundScope = CoroutineScope(Job()),
    )

    private suspend fun awaitUntil(what: String, condition: () -> Boolean) = withTimeout(5_000) {
        while (!condition()) kotlinx.coroutines.delay(5)
    }

    @Test
    fun `which change events refresh the views list`() = runBlocking {
        val table = listOf(
            ChangeEvent.ViewsChanged to true,
            ChangeEvent.Refresh to true,
            ChangeEvent.Upserted(ids = listOf("a"), libraryId = null) to false,
            ChangeEvent.Removed(ids = listOf("a"), libraryId = null) to false,
        )
        for ((event, refreshes) in table) {
            val core = Core()
            val gateway = gateway(core)
            gateway.changeEvents()
            awaitUntil("listener registered") { core.listener != null }

            core.listener!!.onChange(event)
            // Sentinel: one views change always reads, so the event under test is the extra read.
            core.listener!!.onChange(ChangeEvent.ViewsChanged)
            val expected = if (refreshes) 2 else 1
            awaitUntil("$event reads") { core.viewReads.get() >= expected }
            kotlinx.coroutines.delay(150)

            assertEquals("$event", expected, core.viewReads.get())
        }
    }

    @Test
    fun `openMirror registers the change listener before the core opens its mirror`() = runBlocking {
        val core = Core()
        val gateway = gateway(core)

        gateway.openMirror()

        val order = core.calls.filter { it == "setChangeListener" || it == "openMirror" }
        assertEquals(listOf("setChangeListener", "openMirror"), order)
    }

    private fun view(id: String) = ViewSnapshot(id = id, name = id, kind = ViewKind.LIBRARY)

    /** docs/05: a failed open under the current account clears the previous account's libraries, and rethrows. */
    @Test
    fun `a failed openMirror under the same epoch blanks the views and rethrows`() = runBlocking {
        val core = Core()
        core.viewsList = listOf(view("v1"))
        val gateway = gateway(core)
        gateway.openMirror()
        assertEquals(listOf("v1"), gateway.viewsState.value.map { it.id })
        core.onOpenMirror = { throw CoreException.Api("synthetic failure") }

        assertThrows(CoreException.Api::class.java) { runBlocking { gateway.openMirror() } }

        assertEquals(emptyList<ViewSnapshot>(), gateway.viewsState.value)
    }

    /** docs/18 §2.1: an open that failed because the account moved must not blank the newer account's libraries. */
    @Test
    fun `an openMirror that fails after the epoch moved leaves the newer views`() = runBlocking {
        val core = Core()
        core.viewsList = listOf(view("older"))
        val gateway = gateway(core)
        gateway.openMirror()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        core.onOpenMirror = {
            entered.countDown()
            release.await()
            throw CoreException.AccountChanged()
        }
        val stale = async(Dispatchers.Default) {
            try {
                gateway.openMirror()
                null
            } catch (e: CoreException) {
                e
            }
        }
        entered.await()
        core.onOpenMirror = {}
        core.viewsList = listOf(view("newer"))
        gateway.switchSession(0u)
        gateway.openMirror()

        release.countDown()

        assertEquals(CoreException.AccountChanged::class.java, stale.await()?.javaClass)
        assertEquals(listOf("newer"), gateway.viewsState.value.map { it.id })
    }

    /** Cancellation is not a failure: the caller leaving must not clear the account's libraries. */
    @Test
    fun `a cancelled openMirror leaves the views`() = runBlocking {
        val core = Core()
        core.viewsList = listOf(view("v1"))
        val gateway = gateway(core)
        gateway.openMirror()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        core.onOpenMirror = {
            entered.countDown()
            release.await()
        }
        val caller = CoroutineScope(Job())
        val call = caller.launch { gateway.openMirror() }
        entered.await()
        call.cancel()
        release.countDown()
        call.join()

        assertEquals(listOf("v1"), gateway.viewsState.value.map { it.id })
    }
}
