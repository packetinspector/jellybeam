package tv.jellybeam.ui.home

import tv.jellybeam.data.CoreGateway
import tv.jellybeam.data.FakeCoreGateway
import tv.jellybeam.data.defaultTestSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import uniffi.jellybeam_core.ChangeEvent
import uniffi.jellybeam_core.HomeSnapshot
import uniffi.jellybeam_core.Settings
import uniffi.jellybeam_core.SyncStatus
import uniffi.jellybeam_core.ViewSnapshot

/**
 * A [CoreGateway] test double scoped to what [HomeViewModel] reads: [homeSnapshotResult]/
 * [isSyncingResult]/[viewsResult]/[syncStatusResult] flip between emissions on [events]; matching
 * call counts assert one loop's polling never triggers another's fetch. Everything else delegates
 * to [FakeCoreGateway].
 */
class FakeHomeGateway(
    var homeSnapshotResult: HomeSnapshot = HomeSnapshot(resume = emptyList(), nextUp = emptyList(), latest = emptyList()),
    var isSyncingResult: Boolean = false,
    var viewsResult: List<ViewSnapshot> = emptyList(),
    var syncStatusResult: SyncStatus = SyncStatus.Idle,
    /** [CoreGateway.getSettings] result, for [HomeViewModel]'s `showClock` wiring test. */
    var settingsResult: Settings = defaultTestSettings(),
) : CoreGateway by FakeCoreGateway() {

    /** Drives [HomeViewModel]'s debounced change-event collector; buffered so `emit` never needs a
     * ready collector.
     */
    val events = MutableSharedFlow<ChangeEvent>(extraBufferCapacity = 8)

    var homeSnapshotCallCount = 0
        private set

    var isSyncingCallCount = 0
        private set

    var syncStatusCallCount = 0
        private set

    override suspend fun homeSnapshot(): HomeSnapshot {
        homeSnapshotCallCount++
        return homeSnapshotResult
    }

    override suspend fun isSyncing(): Boolean {
        isSyncingCallCount++
        return isSyncingResult
    }

    override suspend fun syncStatus(): SyncStatus {
        syncStatusCallCount++
        return syncStatusResult
    }

    override suspend fun views(): List<ViewSnapshot> = viewsResult

    override suspend fun getSettings(): Settings = settingsResult

    override fun changeEvents(): Flow<ChangeEvent> = events
}
