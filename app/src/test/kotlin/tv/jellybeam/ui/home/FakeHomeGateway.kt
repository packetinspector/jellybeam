package tv.jellybeam.ui.home

import tv.jellybeam.data.CoreGateway
import tv.jellybeam.data.FakeCoreGateway
import tv.jellybeam.data.defaultTestSettings
import tv.jellybeam.ui.home.classic.ClassicContent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import uniffi.jellybeam_core.ChangeEvent
import uniffi.jellybeam_core.Card
import uniffi.jellybeam_core.ClassicHome
import uniffi.jellybeam_core.HomeLayout
import uniffi.jellybeam_core.HomeShelf
import uniffi.jellybeam_core.HomeSnapshot
import uniffi.jellybeam_core.Settings
import uniffi.jellybeam_core.ShelfSource
import uniffi.jellybeam_core.SyncStatus
import uniffi.jellybeam_core.ViewSnapshot

/**
 * A [CoreGateway] test double scoped to what [tv.jellybeam.ui.home.common.HomeFeed] reads: [homeSnapshotResult]/
 * [isSyncingResult]/[viewsResult]/[syncStatusResult] flip between emissions on [events]; matching
 * call counts assert one loop's polling never triggers another's fetch. Everything else delegates
 * to [FakeCoreGateway].
 */
class FakeHomeGateway(
    var homeSnapshotResult: HomeSnapshot = classicSnapshot(),
    var isSyncingResult: Boolean = false,
    var viewsResult: List<ViewSnapshot> = emptyList(),
    var syncStatusResult: SyncStatus = SyncStatus.Idle,
    /** [CoreGateway.getSettings] result, for the feed's `showClock` wiring test. */
    var settingsResult: Settings = defaultTestSettings(),
) : CoreGateway by FakeCoreGateway() {

    /** Drives the feed's debounced change-event collector; buffered so `emit` never needs a
     * ready collector.
     */
    val events = MutableSharedFlow<ChangeEvent>(extraBufferCapacity = 8)

    var homeSnapshotCallCount = 0
        private set

    /** Every layout [homeSnapshot] was asked for, in order. */
    val requestedLayouts = mutableListOf<HomeLayout>()

    var isSyncingCallCount = 0
        private set

    var syncStatusCallCount = 0
        private set

    override suspend fun homeSnapshot(layout: HomeLayout): HomeSnapshot {
        homeSnapshotCallCount++
        requestedLayouts += layout
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

/** A Classic snapshot shaped the way the core builds one (docs/07 §1): the hero is Continue
 * Watching's first card, and empty shelves are dropped.
 */
fun classicSnapshot(
    resume: List<Card> = emptyList(),
    nextUp: List<Card> = emptyList(),
    latest: List<HomeShelf> = emptyList(),
): HomeSnapshot = HomeSnapshot.Classic(
    ClassicHome(
        hero = resume.firstOrNull(),
        shelves = (
            listOf(HomeShelf(ShelfSource.ContinueWatching, resume), HomeShelf(ShelfSource.NextUp, nextUp)) + latest
            ).filter { it.cards.isNotEmpty() },
    ),
)

fun latestShelf(viewId: String, viewName: String, cards: List<Card>): HomeShelf =
    HomeShelf(ShelfSource.Latest(viewId = viewId, viewName = viewName), cards)

/** Continue Watching's cards in [ClassicContent.shelves], or empty when that shelf is absent. */
val ClassicContent.resume: List<Card>
    get() = shelves.firstOrNull { it.source == ShelfSource.ContinueWatching }?.cards.orEmpty()
