package tv.jellybeam.ui.home.classic

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import tv.jellybeam.data.CoreGateway
import tv.jellybeam.data.LaunchWarmup
import tv.jellybeam.player.PlaybackReports
import tv.jellybeam.ui.home.common.HomeFeed
import tv.jellybeam.ui.home.common.HomeFeedState
import tv.jellybeam.ui.home.common.reuseIfUnchanged
import uniffi.jellybeam_core.Card
import uniffi.jellybeam_core.ClassicHome
import uniffi.jellybeam_core.HomeLayout
import uniffi.jellybeam_core.HomeShelf
import uniffi.jellybeam_core.HomeSnapshot

/** docs/07 §1: what Classic draws, all decided in the core (docs/25 §3). */
data class ClassicContent(
    /** Continue Watching's first card, when that shelf leads. */
    val hero: Card? = null,
    /** Every shelf in display order, empty ones already dropped. */
    val shelves: List<HomeShelf> = emptyList(),
)

/**
 * Classic's ViewModel: the shared [HomeFeed] plus Classic's own mapping. Every section is diffed
 * against held state ([reuseIfUnchanged]/[mergeShelves]) so a redundant marshal still hands the UI
 * the same list instance -- `List<Card>` is Compose-unstable, so strong skipping needs referential
 * equality, not structural `equals`.
 */
class ClassicHomeViewModel(
    gateway: CoreGateway,
    /** docs/17-mini-player.md §6: injected so a test can drive it instead of [PlaybackReports]. */
    stopEpoch: Flow<Long> = PlaybackReports.stopEpoch,
    launchWarmup: LaunchWarmup? = null,
) : ViewModel() {

    private val feed = HomeFeed(
        scope = viewModelScope,
        gateway = gateway,
        layout = HomeLayout.CLASSIC,
        initialContent = ClassicContent(),
        stopEpoch = stopEpoch,
        launchWarmup = launchWarmup,
        extract = ::classic,
        reduce = ::reduceClassic,
    )

    val state: StateFlow<HomeFeedState<ClassicContent>> = feed.state

    fun setActive(active: Boolean) = feed.setActive(active)

    fun refreshNow() = feed.refreshNow()

    fun onHostResume() = feed.onHostResume()

    fun loadServerHost() = feed.loadServerHost()
}

/** docs/25 §5.4: this layout's record, or `null` for another layout's snapshot. */
private fun classic(snapshot: HomeSnapshot): ClassicHome? = (snapshot as? HomeSnapshot.Classic)?.home

private fun reduceClassic(current: ClassicContent, incoming: ClassicHome): ClassicContent =
    ClassicContent(
        hero = reuseIfUnchanged(current.hero, incoming.hero),
        shelves = mergeShelves(current.shelves, incoming.shelves),
    )

/** Per-shelf variant of [reuseIfUnchanged]: a sync burst touching one library must not force
 * every other shelf's list to be reallocated. Matches shelves by [ShelfSource.key], never list
 * position.
 */
private fun mergeShelves(current: List<HomeShelf>, incoming: List<HomeShelf>): List<HomeShelf> {
    val currentByKey = current.associateBy { it.source.key() }
    val merged = incoming.map { shelf ->
        val existing = currentByKey[shelf.source.key()]
        if (existing != null && existing == shelf) existing else shelf
    }
    return if (merged == current) current else merged
}

class ClassicHomeViewModelFactory(
    private val gateway: CoreGateway,
    private val launchWarmup: LaunchWarmup? = null,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(ClassicHomeViewModel::class.java))
        return ClassicHomeViewModel(gateway, launchWarmup = launchWarmup) as T
    }
}
