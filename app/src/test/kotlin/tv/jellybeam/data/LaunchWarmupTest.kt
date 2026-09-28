package tv.jellybeam.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.jellybeam_core.AccountInfo
import uniffi.jellybeam_core.ChangeEvent
import uniffi.jellybeam_core.HomeSnapshot

@OptIn(ExperimentalCoroutinesApi::class)
class LaunchWarmupTest {
    private val account = AccountInfo(
        serverUrl = "https://example.test",
        userId = "00000000-0000-4000-8000-000000000001",
        userName = "synthetic-user",
    )
    private val snapshot = HomeSnapshot(resume = emptyList(), nextUp = emptyList(), latest = emptyList())

    /** Records the launch calls in order; everything else is [FakeCoreGateway]. */
    private inner class Gateway(
        private val restored: AccountInfo? = account,
        private val openMirrorError: Throwable? = null,
    ) : CoreGateway by FakeCoreGateway() {
        val calls = mutableListOf<String>()
        val events = MutableSharedFlow<ChangeEvent>(extraBufferCapacity = 8)

        override suspend fun restoreSession(): AccountInfo? {
            calls += "restoreSession"
            return restored
        }

        override suspend fun openMirror() {
            calls += "openMirror"
            openMirrorError?.let { throw it }
        }

        override suspend fun homeSnapshot(): HomeSnapshot {
            calls += "homeSnapshot"
            return snapshot
        }

        override fun changeEvents(): Flow<ChangeEvent> {
            calls += "changeEvents"
            return events
        }
    }

    @Test
    fun `a signed-in launch restores, opens the mirror, then reads Home, each exactly once`() = runTest {
        val gateway = Gateway()
        val warmup = LaunchWarmup(gateway, backgroundScope)
        runCurrent()

        // The change subscription never precedes openMirror: the core binds its listener to the
        // mirror that is open when it registers.
        assertEquals(listOf("restoreSession", "openMirror", "changeEvents", "homeSnapshot"), gateway.calls)
        assertSame(account, warmup.takeSession()?.account)
        assertSame(snapshot, warmup.takeHome()?.snapshot)
        assertEquals(4, gateway.calls.size)
    }

    @Test
    fun `each result is handed out once`() = runTest {
        val warmup = LaunchWarmup(Gateway(), backgroundScope)

        assertNotNull(warmup.takeSession())
        assertNull(warmup.takeSession())
        assertNotNull(warmup.takeHome())
        assertNull(warmup.takeHome())
    }

    @Test
    fun `a signed-out launch opens nothing and prefetches nothing`() = runTest {
        val gateway = Gateway(restored = null)
        val warmup = LaunchWarmup(gateway, backgroundScope)

        val session = warmup.takeSession()
        assertNotNull(session)
        assertNull(session?.account)
        assertNull(warmup.takeHome())
        assertEquals(listOf("restoreSession"), gateway.calls)
    }

    @Test
    fun `a mirror that fails to open still yields the session`() = runTest {
        val gateway = Gateway(openMirrorError = IllegalStateException("synthetic"))
        val warmup = LaunchWarmup(gateway, backgroundScope)

        assertSame(account, warmup.takeSession()?.account)
        assertNotNull(warmup.takeHome())
    }

    @Test
    fun `a change after the prefetch marks it stale`() = runTest {
        val gateway = Gateway()
        val warmup = LaunchWarmup(gateway, backgroundScope)
        runCurrent()

        gateway.events.emit(ChangeEvent.Refresh)
        runCurrent()

        assertTrue(warmup.takeHome()!!.stale)
    }

    @Test
    fun `an untouched prefetch is not stale, and taking it ends the subscription`() = runTest {
        val gateway = Gateway()
        val warmup = LaunchWarmup(gateway, backgroundScope)
        runCurrent()

        assertFalse(warmup.takeHome()!!.stale)
        runCurrent()
        assertEquals(0, gateway.events.subscriptionCount.value)
    }

    @Test
    fun `discard drops both results and the subscription`() = runTest {
        val gateway = Gateway()
        val warmup = LaunchWarmup(gateway, backgroundScope)
        runCurrent()

        warmup.discard()
        runCurrent()

        assertNull(warmup.takeSession())
        assertNull(warmup.takeHome())
        assertEquals(0, gateway.events.subscriptionCount.value)
    }
}
