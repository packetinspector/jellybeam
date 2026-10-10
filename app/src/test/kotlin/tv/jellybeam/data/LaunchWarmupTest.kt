package tv.jellybeam.data

import kotlinx.coroutines.CompletableDeferred
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
import uniffi.jellybeam_core.ClassicHome
import uniffi.jellybeam_core.HomeLayout
import uniffi.jellybeam_core.HomeSnapshot

@OptIn(ExperimentalCoroutinesApi::class)
class LaunchWarmupTest {
    private val account = AccountInfo(
        serverUrl = "https://example.test",
        userId = "00000000-0000-4000-8000-000000000001",
        userName = "synthetic-user",
    )
    private val snapshot = HomeSnapshot.Classic(ClassicHome(hero = null, shelves = emptyList()))

    /** Records the launch calls in order; everything else is [FakeCoreGateway]. */
    private inner class Gateway(
        private val restored: AccountInfo? = account,
        private val openMirrorError: Throwable? = null,
        /** Holds [homeSnapshot] open until completed, so a test can look at the in-flight prefetch. */
        private val snapshotGate: CompletableDeferred<Unit>? = null,
    ) : CoreGateway by FakeCoreGateway() {
        val calls = mutableListOf<String>()
        val requestedLayouts = mutableListOf<HomeLayout>()
        val events = MutableSharedFlow<ChangeEvent>(extraBufferCapacity = 8)

        override suspend fun restoreSession(): AccountInfo? {
            calls += "restoreSession"
            return restored
        }

        override suspend fun openMirror() {
            calls += "openMirror"
            openMirrorError?.let { throw it }
        }

        override suspend fun homeSnapshot(layout: HomeLayout): HomeSnapshot {
            calls += "homeSnapshot"
            requestedLayouts += layout
            snapshotGate?.await()
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
    fun `the prefetch builds the persisted layout, and the session carries it`() = runTest {
        val gateway = Gateway()
        val warmup = LaunchWarmup(gateway, backgroundScope)
        runCurrent()

        assertEquals(listOf(HomeLayout.CLASSIC), gateway.requestedLayouts)
        assertEquals(HomeLayout.CLASSIC, warmup.takeSession()?.homeLayout)
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

    @Test
    fun `peekHome is null while the prefetch is still running`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val warmup = LaunchWarmup(Gateway(snapshotGate = gate), backgroundScope)
        runCurrent()

        assertNull(warmup.peekHome())
    }

    @Test
    fun `peekHome returns the snapshot once the prefetch completed`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val warmup = LaunchWarmup(Gateway(snapshotGate = gate), backgroundScope)
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        val peeked = warmup.peekHome()
        assertSame(snapshot, peeked?.snapshot)
        assertFalse(peeked!!.stale)
    }

    @Test
    fun `peekHome takes nothing, so takeHome still returns the prefetch`() = runTest {
        val warmup = LaunchWarmup(Gateway(), backgroundScope)
        runCurrent()

        assertNotNull(warmup.peekHome())
        assertNotNull(warmup.peekHome())
        assertSame(snapshot, warmup.takeHome()?.snapshot)
    }

    @Test
    fun `peekHome is null after takeHome`() = runTest {
        val warmup = LaunchWarmup(Gateway(), backgroundScope)
        runCurrent()

        warmup.takeHome()

        assertNull(warmup.peekHome())
    }

    @Test
    fun `peekHome is null after discard and for a signed-out launch`() = runTest {
        val discarded = LaunchWarmup(Gateway(), backgroundScope)
        runCurrent()
        discarded.discard()
        assertNull(discarded.peekHome())

        val signedOut = LaunchWarmup(Gateway(restored = null), backgroundScope)
        runCurrent()
        assertNull(signedOut.peekHome())
    }

    @Test
    fun `peekHome reports a change seen so far as stale without releasing the watcher`() = runTest {
        val gateway = Gateway()
        val warmup = LaunchWarmup(gateway, backgroundScope)
        runCurrent()
        gateway.events.emit(ChangeEvent.Refresh)
        runCurrent()

        assertTrue(warmup.peekHome()!!.stale)
        assertEquals(1, gateway.events.subscriptionCount.value)
    }
}
