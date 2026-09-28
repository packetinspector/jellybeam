package tv.jellybeam.ui.home

import androidx.lifecycle.ViewModelStore
import tv.jellybeam.MainDispatcherRule
import tv.jellybeam.data.CoreGateway
import tv.jellybeam.data.LaunchWarmup
import tv.jellybeam.player.PlaybackReports
import tv.jellybeam.ui.cards.testCard
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uniffi.jellybeam_core.AccountInfo
import uniffi.jellybeam_core.ChangeEvent
import uniffi.jellybeam_core.HomeSnapshot
import uniffi.jellybeam_core.LatestShelf
import uniffi.jellybeam_core.SyncStatus

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /** [HomeViewModel.pollSyncing] loops forever on `viewModelScope`; left alive it hangs
     * `runTest`'s cleanup. Clear it in a try/finally here -- `@After` runs too late.
     */
    private inline fun withViewModel(
        gateway: CoreGateway,
        stopEpoch: Flow<Long> = PlaybackReports.stopEpoch,
        block: (HomeViewModel) -> Unit,
    ) {
        val viewModel = HomeViewModel(gateway, stopEpoch)
        try {
            block(viewModel)
        } finally {
            val store = ViewModelStore()
            store.put("home", viewModel)
            store.clear()
        }
    }

    // ---- dedupAgainst (home.rs's `dedup_against`, ported to Kotlin) ------

    @Test
    fun `dedup drops next-up items already shown in continue watching`() {
        val resumeIds = setOf("a", "b")
        val nextUp = listOf(testCard(id = "b"), testCard(id = "c"))

        val result = HomeViewModel.dedupAgainst(resumeIds, nextUp)

        assertEquals(listOf("c"), result.map { it.id })
    }

    @Test
    fun `dedup is a no-op with nothing to exclude`() {
        val nextUp = listOf(testCard(id = "a"), testCard(id = "b"))

        val result = HomeViewModel.dedupAgainst(emptySet(), nextUp)

        assertEquals(nextUp, result)
    }

    @Test
    fun `dedup can drop every item`() {
        val nextUp = listOf(testCard(id = "a"), testCard(id = "b"))

        val result = HomeViewModel.dedupAgainst(setOf("a", "b"), nextUp)

        assertTrue(result.isEmpty())
    }

    // ---- buildShelves (shelf order + empty-shelf hiding, docs/07 §1) -----

    @Test
    fun `all shelves are hidden when the snapshot is empty`() {
        val shelves = buildShelves(HomeUiState(isLoading = false), "Continue Watching", "Next Up") { "Latest in $it" }
        assertTrue(shelves.isEmpty())
    }

    @Test
    fun `shelf order is continue watching then next up then latest per view`() {
        val state = HomeUiState(
            isLoading = false,
            resume = listOf(testCard(id = "r1")),
            nextUp = listOf(testCard(id = "n1")),
            latest = listOf(
                LatestShelf(viewId = "v1", viewName = "Movies", cards = listOf(testCard(id = "m1"))),
                LatestShelf(viewId = "v2", viewName = "TV Shows", cards = listOf(testCard(id = "t1"))),
            ),
        )

        val shelves = buildShelves(state, "Continue Watching", "Next Up") { "Latest in $it" }

        assertEquals(
            listOf("Continue Watching", "Next Up", "Latest in Movies", "Latest in TV Shows"),
            shelves.map { it.title },
        )
    }

    @Test
    fun `a latest shelf with an empty card list is hidden, others still show`() {
        val state = HomeUiState(
            isLoading = false,
            latest = listOf(
                LatestShelf(viewId = "v1", viewName = "Movies", cards = emptyList()),
                LatestShelf(viewId = "v2", viewName = "TV Shows", cards = listOf(testCard(id = "t1"))),
            ),
        )

        val shelves = buildShelves(state, "Continue Watching", "Next Up") { "Latest in $it" }

        assertEquals(listOf("Latest in TV Shows"), shelves.map { it.title })
    }

    @Test
    fun `continue watching and next up are hidden individually when empty`() {
        val state = HomeUiState(isLoading = false, nextUp = listOf(testCard(id = "n1")))

        val shelves = buildShelves(state, "Continue Watching", "Next Up") { "Latest in $it" }

        assertEquals(listOf("Next Up"), shelves.map { it.title })
    }

    // ---- heroCard (docs/07 §1: hero only when shelf 0 is Continue Watching) --

    @Test
    fun `hero card is continue watching's first card`() {
        val state = HomeUiState(
            isLoading = false,
            resume = listOf(testCard(id = "r1"), testCard(id = "r2")),
        )

        assertEquals("r1", heroCard(state)?.id)
    }

    @Test
    fun `hero card is null with no continue watching`() {
        val state = HomeUiState(
            isLoading = false,
            nextUp = listOf(testCard(id = "n1")),
            latest = listOf(LatestShelf(viewId = "v1", viewName = "Movies", cards = listOf(testCard(id = "m1")))),
        )

        assertEquals(null, heroCard(state))
    }

    // ---- refresh diffing + change-event scheduler (docs/16 §4.6, docs/17 §6) ----------

    /** Must match [tv.jellybeam.ui.common.ChangeRefreshScheduler]'s default `visiblePeriodMs`, wired
     * in unmodified. */
    private val testVisiblePeriodMs = 500L

    /** Must match [HomeViewModel]'s private `SYNC_POLL_INTERVAL_MS`, which can't be referenced
     * directly (file-private). */
    private val testSyncPollIntervalMs = 3_000L

    /** Must match [HomeViewModel]'s private `SYNC_STATUS_POLL_INTERVAL_MS` (file-private). */
    private val testSyncStatusPollIntervalMs = 1_000L

    @Test
    fun `the first change event refreshes immediately, with no debounce wait`() = runTest {
        // changeRefreshScheduler's leading edge fires on the first event, not after a quiet window.
        val gateway = FakeHomeGateway()
        withViewModel(gateway) {
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
        withViewModel(gateway) { viewModel ->
            viewModel.setActive(true)
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
    fun `an unchanged section keeps the same list instance after a debounced refresh`() = runTest {
        val gateway = FakeHomeGateway(
            homeSnapshotResult = HomeSnapshot(resume = listOf(testCard(id = "r1")), nextUp = emptyList(), latest = emptyList()),
        )
        withViewModel(gateway) { viewModel ->
            val resumeBefore = viewModel.state.value.resume

            // Structurally equal but a new instance, matching what a real FFI marshal produces.
            gateway.homeSnapshotResult = HomeSnapshot(resume = listOf(testCard(id = "r1")), nextUp = emptyList(), latest = emptyList())
            gateway.events.emit(ChangeEvent.Refresh)
            advanceTimeBy(testVisiblePeriodMs + 1)
            runCurrent()

            assertSame(
                "an unchanged section must reuse the existing list instance, not the fresh FFI one",
                resumeBefore,
                viewModel.state.value.resume,
            )
        }
    }

    @Test
    fun `a changed section is replaced with the new content`() = runTest {
        val gateway = FakeHomeGateway(
            homeSnapshotResult = HomeSnapshot(resume = listOf(testCard(id = "r1")), nextUp = emptyList(), latest = emptyList()),
        )
        withViewModel(gateway) { viewModel ->
            val resumeBefore = viewModel.state.value.resume

            gateway.homeSnapshotResult = HomeSnapshot(resume = listOf(testCard(id = "r2")), nextUp = emptyList(), latest = emptyList())
            gateway.events.emit(ChangeEvent.Refresh)
            advanceTimeBy(testVisiblePeriodMs + 1)
            runCurrent()

            assertNotSame(resumeBefore, viewModel.state.value.resume)
            assertEquals(listOf("r2"), viewModel.state.value.resume.map { it.id })
        }
    }

    @Test
    fun `one changed latest shelf does not reallocate its unchanged sibling`() = runTest {
        val shelfA = LatestShelf(viewId = "a", viewName = "Movies", cards = listOf(testCard(id = "m1")))
        val shelfB = LatestShelf(viewId = "b", viewName = "TV Shows", cards = listOf(testCard(id = "t1")))
        val gateway = FakeHomeGateway(
            homeSnapshotResult = HomeSnapshot(resume = emptyList(), nextUp = emptyList(), latest = listOf(shelfA, shelfB)),
        )
        withViewModel(gateway) { viewModel ->
            val shelfABefore = viewModel.state.value.latest.first { it.viewId == "a" }

            val shelfBChanged = LatestShelf(viewId = "b", viewName = "TV Shows", cards = listOf(testCard(id = "t1"), testCard(id = "t2")))
            gateway.homeSnapshotResult = HomeSnapshot(
                resume = emptyList(),
                nextUp = emptyList(),
                // A fresh LatestShelf("a", ...) instance with shelfA's same content, as a real
                // refresh would marshal.
                latest = listOf(
                    LatestShelf(viewId = "a", viewName = "Movies", cards = listOf(testCard(id = "m1"))),
                    shelfBChanged,
                ),
            )
            gateway.events.emit(ChangeEvent.Refresh)
            advanceTimeBy(testVisiblePeriodMs + 1)
            runCurrent()

            val latestAfter = viewModel.state.value.latest
            assertSame(
                "shelf a's content didn't change, so its LatestShelf instance must be reused",
                shelfABefore,
                latestAfter.first { it.viewId == "a" },
            )
            assertEquals(listOf("t1", "t2"), latestAfter.first { it.viewId == "b" }.cards.map { it.id })
        }
    }

    // ---- refreshNow ----

    @Test
    fun `initial host resume reuses init but later resumes refresh`() = runTest {
        val gateway = FakeHomeGateway()
        withViewModel(gateway) { viewModel ->
            viewModel.onHostResume()
            runCurrent()
            assertEquals(1, gateway.homeSnapshotCallCount)

            gateway.homeSnapshotResult = HomeSnapshot(
                resume = listOf(testCard(id = "updated")), nextUp = emptyList(), latest = emptyList(),
            )
            viewModel.onHostResume()
            runCurrent()
            assertEquals(2, gateway.homeSnapshotCallCount)
            assertEquals("updated", viewModel.state.value.resume.single().id)
        }
    }

    @Test
    fun `initial resume does not queue a duplicate behind a slow initial load`() = runTest {
        val fake = FakeHomeGateway()
        val gate = CompletableDeferred<Unit>()
        val gateway = object : CoreGateway by fake {
            override suspend fun homeSnapshot(): HomeSnapshot {
                gate.await()
                return fake.homeSnapshot()
            }
        }
        withViewModel(gateway) { viewModel ->
            viewModel.onHostResume()
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
            override suspend fun homeSnapshot(): HomeSnapshot {
                gate.await()
                return fake.homeSnapshot()
            }
        }
        withViewModel(gateway) { viewModel ->
            viewModel.onHostResume()
            viewModel.refreshNow()
            runCurrent()
            gate.complete(Unit)
            runCurrent()
            assertEquals(2, fake.homeSnapshotCallCount)
        }
    }

    // ---- cold-start prefetch (docs/10 "Startup phases") ----------------------

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
        val gateway = FakeHomeGateway(homeSnapshotResult = HomeSnapshot(resume, emptyList(), emptyList()))
        val warmup = LaunchWarmup(signedIn(gateway), backgroundScope)
        runCurrent()
        assertEquals("the prefetch itself", 1, gateway.homeSnapshotCallCount)

        val viewModel = HomeViewModel(gateway, launchWarmup = warmup)
        try {
            runCurrent()
            assertEquals("no second marshal for the first paint", 1, gateway.homeSnapshotCallCount)
            assertEquals(resume, viewModel.state.value.resume)
            assertEquals(false, viewModel.state.value.isLoading)

            viewModel.refreshNow()
            runCurrent()
            assertEquals("later refreshes read the mirror again", 2, gateway.homeSnapshotCallCount)
        } finally {
            ViewModelStore().apply { put("home", viewModel) }.clear()
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
        gateway.homeSnapshotResult = HomeSnapshot(fresh, emptyList(), emptyList())

        val viewModel = HomeViewModel(gateway, launchWarmup = warmup)
        try {
            runCurrent()
            assertEquals(2, gateway.homeSnapshotCallCount)
            assertEquals(fresh, viewModel.state.value.resume)
        } finally {
            ViewModelStore().apply { put("home", viewModel) }.clear()
        }
    }

    @Test
    fun `refreshNow re-fetches immediately without waiting for the change-event scheduler`() = runTest {
        val gateway = FakeHomeGateway(
            homeSnapshotResult = HomeSnapshot(resume = listOf(testCard(id = "r1")), nextUp = emptyList(), latest = emptyList()),
        )
        withViewModel(gateway) { viewModel ->
            assertEquals("the initial load", 1, gateway.homeSnapshotCallCount)

            gateway.homeSnapshotResult = HomeSnapshot(resume = listOf(testCard(id = "r2")), nextUp = emptyList(), latest = emptyList())
            viewModel.refreshNow()
            runCurrent()

            assertEquals(
                "refreshNow must not sit behind changeRefreshScheduler's own sampling",
                2,
                gateway.homeSnapshotCallCount,
            )
            assertEquals(listOf("r2"), viewModel.state.value.resume.map { it.id })
        }
    }

    @Test
    fun `isSyncing polling never re-fetches the snapshot, but still reaches the state`() = runTest {
        // docs/17-mini-player.md §6: pollSyncing only calls the FFI while active.
        val gateway = FakeHomeGateway()
        withViewModel(gateway) { viewModel ->
            viewModel.setActive(true)
            val snapshotCallsAfterLoad = gateway.homeSnapshotCallCount
            assertEquals(false, viewModel.state.value.isSyncing)

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
            assertEquals("the polled value must still reach the state", true, viewModel.state.value.isSyncing)
        }
    }

    @Test
    fun `a background sync after load carries its named progress line`() = runTest {
        val gateway = FakeHomeGateway()
        withViewModel(gateway) { viewModel ->
            viewModel.setActive(true)
            gateway.isSyncingResult = true
            gateway.syncStatusResult = SyncStatus.Syncing(libraryName = "Movies (4K)", pagesDone = 1u, itemsDone = 40u, totalItems = 120u)
            advanceTimeBy(2 * testSyncPollIntervalMs + 1)
            runCurrent()

            assertEquals("Syncing Movies (4K) — 40 of 120…", viewModel.state.value.syncProgressText)

            gateway.isSyncingResult = false
            advanceTimeBy(testSyncPollIntervalMs + 1)
            runCurrent()

            assertEquals(null, viewModel.state.value.syncProgressText)
        }
    }

    @Test
    fun `isSyncing polling is skipped while Home is inactive`() = runTest {
        // docs/17 §6: an inactive Home (the default) must not poll isSyncing on its own timer.
        val gateway = FakeHomeGateway()
        withViewModel(gateway) {
            val callsAfterLoad = gateway.isSyncingCallCount

            advanceTimeBy(testSyncPollIntervalMs * 3 + 1)
            runCurrent()

            assertEquals("no isSyncing call while inactive", callsAfterLoad, gateway.isSyncingCallCount)
        }
    }

    // ---- showClock ----

    @Test
    fun `home state defaults show-clock to true from settings`() = runTest {
        val gateway = FakeHomeGateway()
        withViewModel(gateway) { viewModel ->
            assertTrue(viewModel.state.value.showClock)
        }
    }

    @Test
    fun `home state reflects show-clock turned off in settings`() = runTest {
        val gateway = FakeHomeGateway(settingsResult = tv.jellybeam.data.defaultTestSettings().copy(showClock = false))
        withViewModel(gateway) { viewModel ->
            assertEquals(false, viewModel.state.value.showClock)
        }
    }

    // ---- loadingStatusText (cold-start status messaging) ----

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

    // ---- pollSyncStatus (cold-start status messaging) ----

    /** [FakeHomeGateway]'s `homeSnapshot` resolves synchronously, flipping `isLoading` false
     * before `pollSyncStatus` can observe it; gate it behind a [CompletableDeferred]. */
    @Test
    fun `the loading status poll reflects a syncing status and stops once loading finishes`() = runTest {
        val fake = FakeHomeGateway(syncStatusResult = SyncStatus.Idle)
        val homeSnapshotGate = CompletableDeferred<Unit>()
        val gateway = object : CoreGateway by fake {
            override suspend fun homeSnapshot(): HomeSnapshot {
                homeSnapshotGate.await()
                return fake.homeSnapshot()
            }
        }
        withViewModel(gateway) { viewModel ->
            // docs/17 §6: pollSyncStatus only polls while active; this test covers the
            // escalation logic.
            viewModel.setActive(true)
            assertEquals("still loading, nothing synced yet", "Loading…", viewModel.state.value.loadingStatusText)
            assertTrue(viewModel.state.value.isLoading)

            fake.syncStatusResult = SyncStatus.Syncing(
                libraryName = "Movies (4K)",
                pagesDone = 1u,
                itemsDone = 40u,
                totalItems = 120u,
            )
            advanceTimeBy(testSyncStatusPollIntervalMs + 1)
            runCurrent()
            assertEquals("Syncing Movies (4K) — 40 of 120…", viewModel.state.value.loadingStatusText)

            val pollsWhileLoading = fake.syncStatusCallCount
            homeSnapshotGate.complete(Unit)
            runCurrent()
            assertEquals("loading must finish once homeSnapshot resolves", false, viewModel.state.value.isLoading)

            advanceTimeBy(testSyncStatusPollIntervalMs * 3)
            runCurrent()
            assertEquals(
                "the poll must stop once the skeleton is done loading",
                pollsWhileLoading,
                fake.syncStatusCallCount,
            )
        }
    }

    // ---- docs/17 §6: the PiP-dismissal stop-report edge ---

    @Test
    fun `a stop report epoch triggers a snapshot refresh`() = runTest {
        // docs/17 §6: dismissing PiP has no resume/foreground callback; the stop-epoch bump is
        // the only refresh signal.
        val gateway = FakeHomeGateway()
        val stopEpoch = MutableStateFlow(0L)
        withViewModel(gateway, stopEpoch) {
            assertEquals("the initial load", 1, gateway.homeSnapshotCallCount)

            stopEpoch.value = 1L
            runCurrent()

            assertEquals("a landed stop report refreshes the snapshot", 2, gateway.homeSnapshotCallCount)
        }
    }
}
