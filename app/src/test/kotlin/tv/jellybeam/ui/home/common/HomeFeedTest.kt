package tv.jellybeam.ui.home.common

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import tv.jellybeam.MainDispatcherRule
import tv.jellybeam.data.CoreGateway
import tv.jellybeam.data.LaunchWarmup
import tv.jellybeam.player.PlaybackReports
import tv.jellybeam.ui.cards.testCard
import tv.jellybeam.ui.home.FakeHomeGateway
import tv.jellybeam.ui.home.classicSnapshot
import uniffi.jellybeam_core.AccountInfo
import uniffi.jellybeam_core.Card
import uniffi.jellybeam_core.ChangeEvent
import uniffi.jellybeam_core.ClassicHome
import uniffi.jellybeam_core.HomeLayout
import uniffi.jellybeam_core.HomeSnapshot
import uniffi.jellybeam_core.ShelfSource
import uniffi.jellybeam_core.SyncStatus

/** A [HomeFeed] on its own main-thread scope, as a ViewModel would run it, holding the snapshot's
 * record as-is so these tests exercise only the feed.
 */
private class TestFeed(gateway: CoreGateway, stopEpoch: Flow<Long>, launchWarmup: LaunchWarmup?) {
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val feed = HomeFeed<ClassicHome, ClassicHome?>(
        scope = scope,
        gateway = gateway,
        layout = HomeLayout.CLASSIC,
        initialContent = null,
        stopEpoch = stopEpoch,
        launchWarmup = launchWarmup,
        extract = { (it as? HomeSnapshot.Classic)?.home },
        reduce = { _, incoming -> incoming },
    )
    val state get() = feed.state
    val resume: List<Card>
        get() = feed.state.value.content?.shelves
            ?.firstOrNull { it.source == ShelfSource.ContinueWatching }?.cards.orEmpty()

    fun setActive(active: Boolean) = feed.setActive(active)
    fun refreshNow() = feed.refreshNow()
    fun onHostResume() = feed.onHostResume()

    /** [HomeFeed.pollSyncing] loops forever; left alive it hangs `runTest`'s cleanup. */
    fun close() = scope.cancel()
}

@OptIn(ExperimentalCoroutinesApi::class)
class HomeFeedTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun newFeed(gateway: CoreGateway, launchWarmup: LaunchWarmup? = null) =
        TestFeed(gateway, PlaybackReports.stopEpoch, launchWarmup)

    private inline fun withFeed(
        gateway: CoreGateway,
        stopEpoch: Flow<Long> = PlaybackReports.stopEpoch,
        block: (TestFeed) -> Unit,
    ) {
        val feed = TestFeed(gateway, stopEpoch, launchWarmup = null)
        try {
            block(feed)
        } finally {
            feed.close()
        }
    }

    /** Must match [tv.jellybeam.ui.common.ChangeRefreshScheduler]'s default `visiblePeriodMs`, wired
     * in unmodified. */
    private val testVisiblePeriodMs = 500L

    /** Must match [HomeFeed]'s private `SYNC_POLL_INTERVAL_MS`, which can't be referenced
     * directly (file-private). */
    private val testSyncPollIntervalMs = 3_000L

    /** Must match [HomeFeed]'s private `SYNC_STATUS_POLL_INTERVAL_MS` (file-private). */
    private val testSyncStatusPollIntervalMs = 1_000L

    @Test
    fun `the first change event refreshes immediately, with no debounce wait`() = runTest {
        // changeRefreshScheduler's leading edge fires on the first event, not after a quiet window.
        val gateway = FakeHomeGateway()
        withFeed(gateway) {
            assertEquals("the initial load", 1, gateway.homeSnapshotCallCount)

            gateway.events.emit(ChangeEvent.Refresh)
            runCurrent()
            assertEquals("the leading event must refresh immediately", 2, gateway.homeSnapshotCallCount)
        }
    }

    @Test
    fun `a sustained burst keeps refreshing during the stream, not just once at the end`() = runTest {
        // Rust's recv_changes delivers on a ~250ms window, under the scheduler's 500ms sampling
        // period.
        val gateway = FakeHomeGateway()
        withFeed(gateway) { feed ->
            feed.setActive(true)
            assertEquals("the initial load", 1, gateway.homeSnapshotCallCount)

            repeat(8) {
                gateway.events.emit(ChangeEvent.Refresh)
                advanceTimeBy(250)
                runCurrent()
            }

            assertTrue(
                "the burst must make progress DURING the stream (roughly one refresh " +
                    "per $testVisiblePeriodMs ms), not collapse into a single refresh at the end",
                gateway.homeSnapshotCallCount > 2,
            )
        }
    }

    @Test
    fun `initial host resume reuses init but later resumes refresh`() = runTest {
        val gateway = FakeHomeGateway()
        withFeed(gateway) { feed ->
            feed.onHostResume()
            runCurrent()
            assertEquals(1, gateway.homeSnapshotCallCount)

            gateway.homeSnapshotResult = classicSnapshot(resume = listOf(testCard(id = "updated")))
            feed.onHostResume()
            runCurrent()
            assertEquals(2, gateway.homeSnapshotCallCount)
            assertEquals("updated", feed.resume.single().id)
        }
    }

    @Test
    fun `initial resume does not queue a duplicate behind a slow initial load`() = runTest {
        val fake = FakeHomeGateway()
        val gate = CompletableDeferred<Unit>()
        val gateway = object : CoreGateway by fake {
            override suspend fun homeSnapshot(layout: HomeLayout): HomeSnapshot {
                gate.await()
                return fake.homeSnapshot(layout)
            }
        }
        withFeed(gateway) { feed ->
            feed.onHostResume()
            gate.complete(Unit)
            runCurrent()
            assertEquals(1, fake.homeSnapshotCallCount)
        }
    }

    @Test
    fun `real refresh during initial load still queues a follow-up`() = runTest {
        val fake = FakeHomeGateway()
        val gate = CompletableDeferred<Unit>()
        val gateway = object : CoreGateway by fake {
            override suspend fun homeSnapshot(layout: HomeLayout): HomeSnapshot {
                gate.await()
                return fake.homeSnapshot(layout)
            }
        }
        withFeed(gateway) { feed ->
            feed.onHostResume()
            feed.refreshNow()
            runCurrent()
            gate.complete(Unit)
            runCurrent()
            assertEquals(2, fake.homeSnapshotCallCount)
        }
    }

    /** [LaunchWarmup] prefetches only for a restored session; [FakeHomeGateway] restores none. */
    private fun signedIn(fake: FakeHomeGateway): CoreGateway = object : CoreGateway by fake {
        override suspend fun restoreSession() = AccountInfo(
            serverUrl = "https://example.test",
            userId = "00000000-0000-4000-8000-000000000001",
            userName = "synthetic-user",
        )
    }

    @Test
    fun `the first load takes the launch prefetch instead of marshalling its own`() = runTest {
        val resume = listOf(testCard("prefetched"))
        val gateway = FakeHomeGateway(homeSnapshotResult = classicSnapshot(resume))
        val warmup = LaunchWarmup(signedIn(gateway), backgroundScope)
        runCurrent()
        assertEquals("the prefetch itself", 1, gateway.homeSnapshotCallCount)

        val feed = newFeed(gateway, launchWarmup = warmup)
        try {
            runCurrent()
            assertEquals("no second marshal for the first paint", 1, gateway.homeSnapshotCallCount)
            assertEquals(resume, feed.resume)
            assertEquals(false, feed.state.value.chrome.isLoading)

            feed.refreshNow()
            runCurrent()
            assertEquals("later refreshes read the mirror again", 2, gateway.homeSnapshotCallCount)
        } finally {
            feed.close()
        }
    }

    @Test
    fun `a prefetch the mirror has since changed under is followed by one real refresh`() = runTest {
        val gateway = FakeHomeGateway()
        val warmup = LaunchWarmup(signedIn(gateway), backgroundScope)
        runCurrent()
        gateway.events.emit(ChangeEvent.Refresh)
        runCurrent()
        val fresh = listOf(testCard("fresh"))
        gateway.homeSnapshotResult = classicSnapshot(fresh)

        val feed = newFeed(gateway, launchWarmup = warmup)
        try {
            runCurrent()
            assertEquals(2, gateway.homeSnapshotCallCount)
            assertEquals(fresh, feed.resume)
        } finally {
            feed.close()
        }
    }

    @Test
    fun `refreshNow re-fetches immediately without waiting for the change-event scheduler`() = runTest {
        val gateway = FakeHomeGateway(
            homeSnapshotResult = classicSnapshot(resume = listOf(testCard(id = "r1"))),
        )
        withFeed(gateway) { feed ->
            assertEquals("the initial load", 1, gateway.homeSnapshotCallCount)

            gateway.homeSnapshotResult = classicSnapshot(resume = listOf(testCard(id = "r2")))
            feed.refreshNow()
            runCurrent()

            assertEquals(
                "refreshNow must not sit behind changeRefreshScheduler's own sampling",
                2,
                gateway.homeSnapshotCallCount,
            )
            assertEquals(listOf("r2"), feed.resume.map { it.id })
        }
    }

    @Test
    fun `isSyncing polling never re-fetches the snapshot, but still reaches the state`() = runTest {
        // docs/17-mini-player.md §6: pollSyncing only calls the FFI while active.
        val gateway = FakeHomeGateway()
        withFeed(gateway) { feed ->
            feed.setActive(true)
            val snapshotCallsAfterLoad = gateway.homeSnapshotCallCount
            assertEquals(false, feed.state.value.chrome.isSyncing)

            gateway.isSyncingResult = true
            // The first tick ran at construction and skipped its FFI call (docs/17 §6); two
            // intervals here give two active ticks.
            advanceTimeBy(2 * testSyncPollIntervalMs + 1)
            runCurrent()

            assertTrue("isSyncing must be polled independently of the snapshot", gateway.isSyncingCallCount > 1)
            assertEquals(
                "a syncing-flag flip alone must never re-fetch the snapshot",
                snapshotCallsAfterLoad,
                gateway.homeSnapshotCallCount,
            )
            assertEquals("the polled value must still reach the state", true, feed.state.value.chrome.isSyncing)
        }
    }

    @Test
    fun `a background sync after load carries its named progress line`() = runTest {
        val gateway = FakeHomeGateway()
        withFeed(gateway) { feed ->
            feed.setActive(true)
            gateway.isSyncingResult = true
            gateway.syncStatusResult = SyncStatus.Syncing(libraryName = "Movies (4K)", pagesDone = 1u, itemsDone = 40u, totalItems = 120u)
            advanceTimeBy(2 * testSyncPollIntervalMs + 1)
            runCurrent()

            assertEquals("Syncing Movies (4K) — 40 of 120…", feed.state.value.chrome.syncProgressText)

            gateway.isSyncingResult = false
            advanceTimeBy(testSyncPollIntervalMs + 1)
            runCurrent()

            assertEquals(null, feed.state.value.chrome.syncProgressText)
        }
    }

    @Test
    fun `isSyncing polling is skipped while Home is inactive`() = runTest {
        // docs/17 §6: an inactive Home (the default) must not poll isSyncing on its own timer.
        val gateway = FakeHomeGateway()
        withFeed(gateway) {
            val callsAfterLoad = gateway.isSyncingCallCount

            advanceTimeBy(testSyncPollIntervalMs * 3 + 1)
            runCurrent()

            assertEquals("no isSyncing call while inactive", callsAfterLoad, gateway.isSyncingCallCount)
        }
    }

    @Test
    fun `home state defaults show-clock to true from settings`() = runTest {
        val gateway = FakeHomeGateway()
        withFeed(gateway) { feed ->
            assertTrue(feed.state.value.chrome.showClock)
        }
    }

    @Test
    fun `home state reflects show-clock turned off in settings`() = runTest {
        val gateway = FakeHomeGateway(settingsResult = tv.jellybeam.data.defaultTestSettings().copy(showClock = false))
        withFeed(gateway) { feed ->
            assertEquals(false, feed.state.value.chrome.showClock)
        }
    }

    @Test
    fun `home state mirrors the resume posters setting`() = runTest {
        val gateway = FakeHomeGateway(settingsResult = tv.jellybeam.data.defaultTestSettings().copy(homeResumePosters = true))
        withFeed(gateway) { feed ->
            assertTrue(feed.state.value.chrome.resumeAsPosters)
        }
    }

    @Test
    fun `idle status reads Loading before the still-loading escalation`() {
        assertEquals("Loading…", loadingStatusText(SyncStatus.Idle, tickCount = 0))
        assertEquals("Loading…", loadingStatusText(SyncStatus.Idle, tickCount = 1))
    }

    @Test
    fun `idle status escalates to Still loading after the threshold`() {
        assertEquals("Still loading…", loadingStatusText(SyncStatus.Idle, tickCount = 2))
        assertEquals("Still loading…", loadingStatusText(SyncStatus.Idle, tickCount = 5))
    }

    @Test
    fun `syncing status with a known total shows progress out of that total`() {
        val status = SyncStatus.Syncing(libraryName = "Movies (4K)", pagesDone = 1u, itemsDone = 40u, totalItems = 120u)

        assertEquals("Syncing Movies (4K) — 40 of 120…", loadingStatusText(status, tickCount = 0))
    }

    @Test
    fun `syncing status without a known total shows a plain item count`() {
        val status = SyncStatus.Syncing(libraryName = "TV Shows", pagesDone = 1u, itemsDone = 7u, totalItems = null)

        assertEquals("Syncing TV Shows — 7 items…", loadingStatusText(status, tickCount = 0))
    }

    @Test
    fun `syncing status counts a single item in the singular`() {
        val status = SyncStatus.Syncing(libraryName = "TV Shows", pagesDone = 1u, itemsDone = 1u, totalItems = null)

        assertEquals("Syncing TV Shows — 1 item…", loadingStatusText(status, tickCount = 0))
    }

    @Test
    fun `syncing status shows the server library name verbatim, never prettified`() {
        // Server-configured names render verbatim; deliberately not normal-looking, to prove
        // nothing reformats it.
        val status = SyncStatus.Syncing(libraryName = "weird_library-ID 42", pagesDone = 0u, itemsDone = 0u, totalItems = null)

        assertEquals("Syncing weird_library-ID 42 — 0 items…", loadingStatusText(status, tickCount = 0))
    }

    @Test
    fun `a sync whose library has no name yet never shows an id`() {
        val status = SyncStatus.Syncing(libraryName = null, pagesDone = 0u, itemsDone = 12u, totalItems = 300u)

        assertEquals("Syncing library — 12 of 300…", loadingStatusText(status, tickCount = 0))
    }

    /** [FakeHomeGateway]'s `homeSnapshot` resolves synchronously, flipping `isLoading` false
     * before `pollSyncStatus` can observe it; gate it behind a [CompletableDeferred]. */
    @Test
    fun `the loading status poll reflects a syncing status and stops once loading finishes`() = runTest {
        val fake = FakeHomeGateway(syncStatusResult = SyncStatus.Idle)
        val homeSnapshotGate = CompletableDeferred<Unit>()
        val gateway = object : CoreGateway by fake {
            override suspend fun homeSnapshot(layout: HomeLayout): HomeSnapshot {
                homeSnapshotGate.await()
                return fake.homeSnapshot(layout)
            }
        }
        withFeed(gateway) { feed ->
            // docs/17 §6: pollSyncStatus only polls while active; this test covers the
            // escalation logic.
            feed.setActive(true)
            assertEquals("still loading, nothing synced yet", "Loading…", feed.state.value.chrome.loadingStatusText)
            assertTrue(feed.state.value.chrome.isLoading)

            fake.syncStatusResult = SyncStatus.Syncing(
                libraryName = "Movies (4K)",
                pagesDone = 1u,
                itemsDone = 40u,
                totalItems = 120u,
            )
            advanceTimeBy(testSyncStatusPollIntervalMs + 1)
            runCurrent()
            assertEquals("Syncing Movies (4K) — 40 of 120…", feed.state.value.chrome.loadingStatusText)

            val pollsWhileLoading = fake.syncStatusCallCount
            homeSnapshotGate.complete(Unit)
            runCurrent()
            assertEquals("loading must finish once homeSnapshot resolves", false, feed.state.value.chrome.isLoading)

            advanceTimeBy(testSyncStatusPollIntervalMs * 3)
            runCurrent()
            assertEquals(
                "the poll must stop once the skeleton is done loading",
                pollsWhileLoading,
                fake.syncStatusCallCount,
            )
        }
    }

    @Test
    fun `a stop report epoch triggers a snapshot refresh`() = runTest {
        // docs/17 §6: dismissing PiP has no resume/foreground callback; the stop-epoch bump is
        // the only refresh signal.
        val gateway = FakeHomeGateway()
        val stopEpoch = MutableStateFlow(0L)
        withFeed(gateway, stopEpoch) {
            assertEquals("the initial load", 1, gateway.homeSnapshotCallCount)

            stopEpoch.value = 1L
            runCurrent()

            assertEquals("a landed stop report refreshes the snapshot", 2, gateway.homeSnapshotCallCount)
        }
    }
}
