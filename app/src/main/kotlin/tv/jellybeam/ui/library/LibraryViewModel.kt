package tv.jellybeam.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import tv.jellybeam.data.CoreGateway
import tv.jellybeam.data.runCatchingCancellable
import tv.jellybeam.ui.common.ChangeRefreshScheduler
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uniffi.jellybeam_core.Card
import uniffi.jellybeam_core.ChangeEvent
import uniffi.jellybeam_core.Decade
import uniffi.jellybeam_core.GridCounts
import uniffi.jellybeam_core.GridFilters
import uniffi.jellybeam_core.GridGroup
import uniffi.jellybeam_core.GridSort
import uniffi.jellybeam_core.GridSortField
import uniffi.jellybeam_core.LibraryGridPrefs
import uniffi.jellybeam_core.LiveSort
import uniffi.jellybeam_core.SortOrder
import uniffi.jellybeam_core.StatusFilter
import uniffi.jellybeam_core.ViewKind
import uniffi.jellybeam_core.ViewSnapshot
import uniffi.jellybeam_core.WatchedFilter

/** The "no filter" [GridFilters]: default state and [LibraryViewModel.resetSortAndFilters]'s
 * target (docs/16 §3, §4.2).
 */
private fun defaultGridFilters(): GridFilters = GridFilters(
    watched = WatchedFilter.ANY,
    genre = null,
    decade = null,
    status = StatusFilter.ANY,
)

/** Name-ascending: the default/reset [GridSort] for both library types (docs/16 §3). */
private fun defaultGridSort(): GridSort = GridSort(GridSortField.NAME, descending = false)

data class LibraryUiState(
    val isLoading: Boolean = true,
    val items: List<Card> = emptyList(),
    val hasMore: Boolean = true,
    val isLoadingMore: Boolean = false,
    /** Sticky per-library sort (docs/16 §3, §5); Name ascending until a saved value loads. */
    val sort: GridSort = defaultGridSort(),
    /** Sticky per-library filters (docs/16 §3, §5); every filter at its default until loaded. */
    val filters: GridFilters = defaultGridFilters(),
    /** Filtered/total row counts for the summary line (docs/16 §4.1); `0`/`0` until first query.
     */
    val counts: GridCounts = GridCounts(filtered = 0uL, total = 0uL),
    /** Per-[sort]-field group buckets backing the index rail (docs/16 §4.4). */
    val groups: List<GridGroup> = emptyList(),
    /** This library's distinct genres, verbatim (docs/16 §4.3); refreshed on first load and on
     * mirror change events only.
     */
    val genres: List<String> = emptyList(),
    /** docs/16 §2.7: the Favorites page's Type panel options (item types present), refreshed
     * alongside [genres]; always empty for a library view.
     */
    val itemTypes: List<String> = emptyList(),
    /** True once [CoreGateway.getLibraryGridPrefs] resolved and [sort]/[filters] reflect the saved
     * value; the first [CoreGateway.libraryGrid] query never fires before this flips.
     */
    val prefsLoaded: Boolean = false,
)

/**
 * Loads one library's poster grid in bounded 200-item pages. Mirror events refresh the loaded
 * prefix from offset zero, so an insertion ahead of an offset boundary can't leave it stale.
 *
 * Sort/filter (docs/16-library-sort-filter.md): every `ViewKind.LIBRARY` view pages through
 * [CoreGateway.libraryGrid] (only a [tv.jellybeam.ui.library.supportsSortFilter] library shows the
 * summary/strip/rail UI, but the query is uniform). [LibraryUiState.sort]/[filters] load from
 * [CoreGateway.getLibraryGridPrefs] once before the first query ([LibraryUiState.prefsLoaded]),
 * and persist back through [CoreGateway.setLibraryGridPrefs] on every intent (docs/16 §5).
 *
 * Re-queries on mirror change events, same as Home, so a watched checkmark earned during playback
 * appears without reopening the grid; also refreshes [LibraryUiState.counts]/[groups] (docs/16
 * §4.6). [LibraryUiState.genres] only reloads on the first query and a change event, gated by
 * [affectsThisLibrary].
 *
 * docs/16 §4.6, docs/17-mini-player.md §6: a resetting `debounce(500)` never fires while a sync
 * burst keeps events under 500ms apart. [changeRefreshScheduler] drives the re-query instead:
 * leading refresh, bounded periodic sampling, and a guaranteed trailing refresh, gated by
 * [setActive] so a hidden-but-retained screen samples at a slower rate.
 *
 * A live view ([ViewKind.isLive]: `CHANNEL` or a `CHANNEL_FOLDER` inside one) never syncs to the
 * offline mirror, so every page comes from [CoreGateway.liveChildren] instead, with no
 * [ChangeEvent] stream and no sort/filter/prefs concept; [onBecameTop] re-queries it when this
 * screen becomes visible again (see [liveSortFor] for its per-kind sort).
 */
class LibraryViewModel(private val gateway: CoreGateway, private val view: ViewSnapshot) : ViewModel() {

    private companion object {
        const val PAGE_SIZE = 200
    }

    /**
     * Members per BoxSet id, loaded lazily per visible card (docs/07 §Collection card): the stack
     * draws the first three, the badge counts all of them as the collection page does.
     */
    private val collectionPreviews = MutableStateFlow<Map<String, List<Card>>>(emptyMap())

    /** Ids already asked for, so a recycled cell never re-queries; main-thread only. */
    private val previewRequested = mutableSetOf<String>()

    /** Previews a change event touched since the last refresh; main-thread only. */
    private val dirtyPreviews = mutableSetOf<String>()
    private var allPreviewsDirty = false

    /** `null` until [loadCollectionPreview] lands for [collectionId]. */
    fun collectionPreview(collectionId: String): Flow<List<Card>?> =
        collectionPreviews.map { it[collectionId] }.distinctUntilChanged()

    /** Fire-and-forget mirror read of a collection's members, in server display order. */
    fun loadCollectionPreview(collectionId: String) {
        if (!previewRequested.add(collectionId)) return
        viewModelScope.launch { readCollectionPreview(collectionId) }
    }

    private suspend fun readCollectionPreview(collectionId: String) {
        val members = runCatchingCancellable {
            gateway.children(collectionId, SortOrder.INDEX_NUMBER, 0u, UInt.MAX_VALUE)
        }.getOrNull()
        if (members == null) {
            previewRequested.remove(collectionId)
        } else {
            collectionPreviews.update { it + (collectionId to members) }
        }
    }

    /** Marks the previews [event] can change: its ids name the collection or one of its members. */
    private fun markPreviewsDirty(event: ChangeEvent) {
        val ids = when (event) {
            is ChangeEvent.Upserted -> event.ids
            is ChangeEvent.Removed -> event.ids
            ChangeEvent.Refresh, ChangeEvent.ViewsChanged -> {
                allPreviewsDirty = true
                return
            }
        }.toSet()
        collectionPreviews.value.forEach { (collectionId, members) ->
            if (collectionId in ids || members.any { it.id in ids }) dirtyPreviews += collectionId
        }
    }

    /**
     * Re-reads the previews a change touched, and drops those whose collection left the grid so
     * the cache never outgrows what the grid holds.
     */
    private suspend fun refreshCollectionPreviews() {
        val onGrid = _state.value.items.mapTo(mutableSetOf()) { it.id }
        val held = collectionPreviews.value.keys
        val gone = held - onGrid
        if (gone.isNotEmpty()) {
            collectionPreviews.update { it - gone }
            previewRequested -= gone
        }
        val stale = (if (allPreviewsDirty) held else dirtyPreviews.toSet()) - gone
        allPreviewsDirty = false
        dirtyPreviews.clear()
        stale.forEach { readCollectionPreview(it) }
    }

    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    /**
     * Bumped synchronously in [applyIntent]: [refresh]/[loadNextPage]/[ensureLoadedThrough]
     * snapshot this before their first suspension and discard a stale result if it moved on, so
     * an older query can't overwrite a newer [requery]'s results (docs/16 §4.6). [requery] needs
     * no such check since [applyIntent] cancels the previous [intentJob] first.
     */
    private var generation = 0

    /**
     * Serializes [loadNextPage] against [ensureLoadedThrough]: a rail jump waits for an in-flight
     * next-page load rather than bailing early, so [IndexRail]'s post-jump focus lookup always
     * finds a card.
     */
    private val pagingMutex = Mutex()

    /**
     * docs/16 §4.6, docs/17-mini-player.md §6: true while top of stack and the host Activity is
     * resumed (fed by [LibraryScreen]'s `setActive(isTop)`). Drives [changeRefreshScheduler]'s
     * sampling rate; meaningless for a live view, which has no scheduler.
     */
    private val _active = MutableStateFlow(false)

    fun setActive(active: Boolean) {
        _active.value = active
    }

    /**
     * `null` for a live view; [onBecameTop] is its only refresh trigger. Filtered before reaching
     * the scheduler, since checking relevance after conflating risks swallowing a relevant event
     * that landed earlier in a burst.
     */
    private val changeRefreshScheduler: ChangeRefreshScheduler<ChangeEvent>? =
        if (view.kind.isLive) {
            null
        } else {
            ChangeRefreshScheduler(
                scope = viewModelScope,
                events = gateway.changeEvents().onEach(::markPreviewsDirty).filter(::affectsThisLibrary),
                active = _active.asStateFlow(),
                refresh = {
                    refresh(reloadGenres = true)
                    refreshCollectionPreviews()
                },
            )
        }

    init {
        viewModelScope.launch {
            if (!view.kind.isLive) {
                // Prefs load before the first query (docs/16 §5): it must use the saved
                // sort/filters, not the in-memory default.
                val prefs = gateway.getLibraryGridPrefs(view.id)
                _state.update { it.copy(sort = prefs.sort, filters = prefs.filters, prefsLoaded = true) }
            }
            refresh(reloadGenres = true)
        }
    }

    /**
     * [ChangeEvent.Upserted]/[ChangeEvent.Removed] carry the ids that changed; checking those
     * against ids already on screen (or [view]'s id) catches a watched-state flip, but not a
     * brand-new item, so an empty grid always passes an `Upserted` through too.
     * [ChangeEvent.Refresh] always refetches; [ChangeEvent.ViewsChanged] is never relevant here.
     */
    private fun affectsThisLibrary(event: ChangeEvent): Boolean {
        // Hoisted out of the `any {}` lambdas below so a burst of N ids isn't O(N*M) per id.
        val currentItemIds = currentItemIds()
        return when (event) {
            // Favorites spans libraries and a newly favorited item isn't loaded yet.
            is ChangeEvent.Upserted -> view.isFavorites || event.libraryId == view.id || currentItemIds.isEmpty() || event.ids.any { it == view.id || it in currentItemIds }
            is ChangeEvent.Removed -> view.isFavorites || event.libraryId == view.id || event.ids.any { it == view.id || it in currentItemIds }
            ChangeEvent.Refresh -> true
            ChangeEvent.ViewsChanged -> true
        }
    }

    /** A collection grid also watches its cards' members, whose watched state the stacks show. */
    private fun currentItemIds(): Set<String> = _state.value.items.mapTo(mutableSetOf()) { it.id }.apply {
        collectionPreviews.value.values.forEach { members -> members.mapTo(this) { it.id } }
    }

    /**
     * Result of one three-query library read ([CoreGateway.libraryGrid], [libraryGridCounts],
     * [libraryGridGroups]) via [readGrid]. [requested] is the prefix length asked for, so
     * [applying] can recompute [LibraryUiState.hasMore].
     *
     * §4.6 null-vs-empty contract: `null` on one field means the mirror isn't open or that query
     * failed -- [applying] fails soft, keeping what was on screen for it independently of the
     * other two. A non-null `items`, including an empty list, always replaces the prior page.
     *
     * Bundles three independent, non-transactional reads, not one SQLite snapshot -- a commit
     * landing mid-read can skew it for one cycle, self-healing on the next refresh. A
     * transactional snapshot needs a new FFI surface and is deliberately deferred (docs/16 §4.6).
     */
    private data class GridRead(
        val requested: Int,
        val items: List<Card>?,
        val counts: GridCounts?,
        val groups: List<GridGroup>?,
    )

    private suspend fun readGrid(sort: GridSort, filters: GridFilters, requested: Int): GridRead {
        val items = gateway.libraryGrid(view.id, sort, filters, 0u, requested.toUInt())
        val counts = gateway.libraryGridCounts(view.id, filters)
        val groups = gateway.libraryGridGroups(view.id, sort, filters)
        return GridRead(requested, items, counts, groups)
    }

    /** Per-field null-retaining merge of [read] into this state (see [GridRead]); callers compose
     * loading flags/genres ([refresh]) or nothing else ([requery]) on top.
     */
    private fun LibraryUiState.applying(read: GridRead): LibraryUiState = copy(
        items = read.items ?: items,
        hasMore = read.items?.let { it.size == read.requested } ?: hasMore,
        counts = read.counts ?: counts,
        groups = read.groups ?: groups,
    )

    /**
     * Growing-prefix refresh: re-fetches items `0..max(PAGE_SIZE, current size)` under the current
     * [LibraryUiState.sort]/[filters], plus [counts]/[groups], plus [genres] when [reloadGenres]
     * (docs/16 §4.6). Used by [init]'s first load and [changeRefreshScheduler]; never by a
     * sort/filter intent, which drops the old prefix instead (see [requery]).
     *
     * Takes [pagingMutex] for its whole body, same as [loadNextPage]/[ensureLoadedThrough], to
     * close both the refresh-vs-page and refresh-vs-refresh races. [requery] deliberately does not
     * take it, since a sort/filter intent must not wait behind a slow refresh -- [generation] lets
     * its result win instead.
     *
     * Not fixed here: items/counts/groups/genres remain separate, non-transactional reads that can
     * straddle an intervening commit -- self-heals on the next change event/refresh.
     */
    private suspend fun refresh(reloadGenres: Boolean = false) {
        pagingMutex.withLock {
            if (view.kind.isLive) {
                // Live listing has no mirror behind it: always re-pulled from the top.
                val page = runCatching {
                    gateway.liveChildren(view.id, 0u, PAGE_SIZE.toUInt(), liveSortFor(view.kind))
                }.getOrElse {
                    // Fail soft: keep whatever was already on screen rather than blanking the grid.
                    _state.update { it.copy(isLoading = false, isLoadingMore = false) }
                    return@withLock
                }
                _state.update { it.copy(isLoading = false, items = page, hasMore = page.size == PAGE_SIZE, isLoadingMore = false) }
                return@withLock
            }
            val myGeneration = generation
            val current = _state.value
            val requested = maxOf(PAGE_SIZE, current.items.size)
            val read = readGrid(current.sort, current.filters, requested)
            val genres = if (reloadGenres) gateway.libraryGenres(view.id) else current.genres
            val itemTypes = if (reloadGenres && view.isFavorites) gateway.favoriteItemTypes() else current.itemTypes
            if (myGeneration != generation) {
                // A newer intent's requery() already holds the correct results; touch nothing but
                // this stale fetch's own loading flags.
                _state.update { it.copy(isLoading = false, isLoadingMore = false) }
                return@withLock
            }
            // Same fail-soft reasoning as the live branch, via a null result instead of a thrown
            // exception (see [GridRead]'s null-vs-empty contract).
            _state.update { it.applying(read).copy(isLoading = false, isLoadingMore = false, genres = genres, itemTypes = itemTypes) }
            if (reloadGenres && isStaleItemTypeFilter(current.filters.itemType, itemTypes)) setItemType(null)
        }
    }

    /**
     * A sort/filter intent's re-query (docs/16 §4.2: results change on Select, never on focus
     * move): drops the loaded prefix and re-fetches one [PAGE_SIZE] page from offset 0 under the
     * just-updated sort/filters, plus counts/groups. Never reloads genres. Null-vs-empty
     * coalescing: see [GridRead]/[applying] -- a stale-but-populated grid beats an empty one.
     */
    private suspend fun requery() {
        val current = _state.value
        val read = readGrid(current.sort, current.filters, PAGE_SIZE)
        _state.update { it.applying(read) }
    }

    /**
     * One in-flight intent at a time: a second Select landing while the previous re-query is
     * still running cancels it, so a stale page can never overwrite the newer results. The state
     * update is applied synchronously, so the prefs written below are always the latest.
     */
    private var intentJob: Job? = null

    /** Shared body for every public sort/filter intent below: applies [transform], persists via
     * [CoreGateway.setLibraryGridPrefs], then [requery]s.
     */
    private fun applyIntent(transform: (LibraryUiState) -> LibraryUiState) {
        generation++
        _state.update(transform)
        intentJob?.cancel()
        intentJob = viewModelScope.launch {
            val current = _state.value
            gateway.setLibraryGridPrefs(view.id, LibraryGridPrefs(current.sort, current.filters))
            requery()
        }
    }

    /** Sort chip Select (docs/16 §4.2): an inactive field becomes active in its natural direction
     * ([GridSummaryFormat.naturalDescending]); the active field flips direction instead.
     */
    fun setSort(field: GridSortField) = applyIntent { state ->
        val newSort = if (state.sort.field == field) {
            state.sort.copy(descending = !state.sort.descending)
        } else {
            GridSort(field = field, descending = GridSummaryFormat.naturalDescending(field))
        }
        state.copy(sort = newSort)
    }

    /** Watched chip Select (docs/16 §4.2): `Any -> Unwatched -> Watched -> Any` on Movies, `Any ->
     * Unwatched -> Has unwatched -> Watched -> Any` on TV ([ViewSnapshot.isTvLibrary]).
     */
    fun cycleWatched() = applyIntent { state ->
        val next = when (state.filters.watched) {
            WatchedFilter.ANY -> WatchedFilter.UNWATCHED
            WatchedFilter.UNWATCHED -> if (view.isTvLibrary) WatchedFilter.HAS_UNWATCHED else WatchedFilter.WATCHED
            WatchedFilter.HAS_UNWATCHED -> WatchedFilter.WATCHED
            WatchedFilter.WATCHED -> WatchedFilter.ANY
        }
        state.copy(filters = state.filters.copy(watched = next))
    }

    /** Genre panel Select (docs/16 §4.3) -- `null` commits "Any". */
    fun setGenre(genre: String?) = applyIntent { state -> state.copy(filters = state.filters.copy(genre = genre)) }

    /** Favorites' Type panel Select (docs/16 §2.7) -- `null` commits "Any". */
    fun setItemType(itemType: String?) = applyIntent { state -> state.copy(filters = state.filters.copy(itemType = itemType)) }

    /** Years panel Select (docs/16 §4.3) -- `null` commits "Any". */
    fun setDecade(decade: Decade?) = applyIntent { state -> state.copy(filters = state.filters.copy(decade = decade)) }

    /** Status chip Select: `Any -> Continuing -> Ended -> Any` (docs/16 §4.2, TV Shows chip only).
     */
    fun cycleStatus() = applyIntent { state ->
        val next = when (state.filters.status) {
            StatusFilter.ANY -> StatusFilter.CONTINUING
            StatusFilter.CONTINUING -> StatusFilter.ENDED
            StatusFilter.ENDED -> StatusFilter.ANY
        }
        state.copy(filters = state.filters.copy(status = next))
    }

    /** Reset chip Select: sort back to Name ascending, every filter back to its default (docs/16
     * §4.2).
     */
    fun resetSortAndFilters() = applyIntent { state ->
        state.copy(sort = defaultGridSort(), filters = defaultGridFilters())
    }

    fun loadNextPage() {
        val current = _state.value
        if (current.isLoading || current.isLoadingMore || !current.hasMore) return
        _state.update { it.copy(isLoadingMore = true) }
        viewModelScope.launch {
            pagingMutex.withLock {
                val myGeneration = generation
                try {
                    val state = _state.value
                    val offset = state.items.size
                    // §4.6: a null page leaves `items`/`hasMore` untouched (not `hasMore = false`)
                    // so a later scroll retries instead of being permanently stranded.
                    val page = runCatching {
                        if (view.kind.isLive) {
                            gateway.liveChildren(view.id, offset.toUInt(), PAGE_SIZE.toUInt(), liveSortFor(view.kind))
                        } else {
                            gateway.libraryGrid(view.id, state.sort, state.filters, offset.toUInt(), PAGE_SIZE.toUInt())
                        }
                    }.getOrElse { return@withLock } ?: return@withLock
                    if (myGeneration != generation) {
                        // A newer intent's requery() already replaced items from offset 0, so
                        // this stale page must not be appended on top.
                        return@withLock
                    }
                    _state.update { s ->
                        val known = s.items.asSequence().map { it.id }.toMutableSet()
                        val additions = page.filter { known.add(it.id) }
                        s.copy(
                            items = s.items + additions,
                            hasMore = page.size == PAGE_SIZE,
                        )
                    }
                } finally {
                    _state.update { it.copy(isLoadingMore = false) }
                }
            }
        }
    }

    /**
     * Rail-jump prefix loader (docs/16 §4.4/§4.6): if the grid doesn't cover [offset] and more
     * exists, fetches one page `0..max(offset + PAGE_SIZE, current size)` and replaces
     * [LibraryUiState.items] (same growing-prefix shape as [refresh]). Returns whether it covers
     * [offset] -- `false` when beyond what the gateway has or the initial load hasn't landed.
     *
     * Takes [pagingMutex] for its whole body so a rail jump waits for an in-flight [loadNextPage]
     * rather than leaving the grid short; coverage is re-checked once acquired. `finally` clears
     * `isLoadingMore` unconditionally so a cancelled jump ([IndexRail]'s `jumpJob`) never sticks.
     */
    suspend fun ensureLoadedThrough(offset: Int): Boolean = pagingMutex.withLock {
        val current = _state.value
        if (current.items.size > offset) return@withLock true
        if (current.isLoading || !current.hasMore) return@withLock false
        val myGeneration = generation
        _state.update { it.copy(isLoadingMore = true) }
        try {
            val requested = maxOf(offset + PAGE_SIZE, current.items.size)
            val items = gateway.libraryGrid(view.id, current.sort, current.filters, 0u, requested.toUInt())
            if (myGeneration != generation) {
                // A newer intent's requery() already replaced items from offset 0 under the new
                // sort/filters, so this fetch is discarded; report coverage against its result.
                return@withLock _state.value.items.size > offset
            }
            if (items == null) {
                // §4.6: a null page leaves `items`/`hasMore` untouched so a later jump can retry.
                return@withLock _state.value.items.size > offset
            }
            _state.update { it.copy(items = items, hasMore = items.size == requested) }
            items.size > offset
        } finally {
            _state.update { it.copy(isLoadingMore = false) }
        }
    }

    /**
     * Re-runs [refresh] when a live screen becomes top of the back stack, standing in for the
     * mirror [ChangeEvent] stream this view never gets. A no-op for `ViewKind.LIBRARY` (already
     * covered by [changeRefreshScheduler]) and while the initial [refresh] is still in flight.
     */
    fun onBecameTop() {
        if (!view.kind.isLive || _state.value.isLoading) return
        viewModelScope.launch { refresh() }
    }
}

/** The [LiveSort] a live view requests: `CHANNEL` alphabetically, `CHANNEL_FOLDER` newest first. */
private fun liveSortFor(kind: ViewKind): LiveSort = when (kind) {
    ViewKind.CHANNEL -> LiveSort.NAME_ASC
    ViewKind.CHANNEL_FOLDER -> LiveSort.NEWEST_FIRST
    ViewKind.LIBRARY -> error("ViewKind.LIBRARY never calls liveChildren")
}

class LibraryViewModelFactory(
    private val gateway: CoreGateway,
    private val view: ViewSnapshot,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(LibraryViewModel::class.java))
        return LibraryViewModel(gateway, view) as T
    }
}
