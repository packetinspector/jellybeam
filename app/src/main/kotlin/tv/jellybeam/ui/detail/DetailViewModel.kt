package tv.jellybeam.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import tv.jellybeam.data.CoreGateway
import tv.jellybeam.data.runCatchingCancellable
import tv.jellybeam.player.PlaybackReports
import tv.jellybeam.ui.common.ChangeRefreshScheduler
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uniffi.jellybeam_core.Card
import uniffi.jellybeam_core.ChangeEvent
import uniffi.jellybeam_core.CollectionInfo
import uniffi.jellybeam_core.ItemDetail
import uniffi.jellybeam_core.SortOrder

private const val SERIES_ITEM_TYPE = "Series"
private const val EPISODE_ITEM_TYPE = "Episode"
private const val BOXSET_ITEM_TYPE = CollectionFormatting.BOXSET_ITEM_TYPE

/** docs/11-detail-ux-spec.md tier 2 item 13: "up to [limit]... from a live GetSimilar call." */
private const val SIMILAR_LIMIT = 16u

// docs/19-detail-action-menu.md §1.4: fixed toast texts.
private const val TOAST_MARKED_WATCHED = "Marked as watched"
private const val TOAST_MARKED_UNWATCHED = "Marked as unwatched"
private const val TOAST_ADDED_FAVORITE = "Added to favorites"
private const val TOAST_REMOVED_FAVORITE = "Removed from favorites"
private const val TOAST_REFRESHING_METADATA = "Refreshing metadata"
private const val TOAST_SERVER_UNREACHABLE = "Couldn't reach the server"

/** How long [DetailUiState.toast] stays before [DetailViewModel] clears it (docs/19 §1.4). */
private const val TOAST_DURATION_MS = 2_500L

/** docs/19 §3.3's `MenuUiState.level`: the panel's first level, or the collection-picker door. */
enum class MenuLevel { FIRST, COLLECTIONS }

/**
 * docs/19 §3.3: the open panel's content and level. [scopeSeasonNumber] is the scoped season's
 * number for the `ACTIONS │ SEASON N` kicker (§1.5); `null` off-Series or unscoped.
 */
data class MenuUiState(val model: MenuModel, val level: MenuLevel = MenuLevel.FIRST, val scopeSeasonNumber: Int? = null)

/** docs/19 §1.3/§3.3: a pending bulk mark; [action] is the row to re-expand on confirm/cancel. */
data class BulkMarkConfirm(val played: Boolean, val count: Int, val scopeId: String, val scopeName: String, val action: MenuAction)

/** A Play* action's target; [DetailScreen] launches it once via PlaybackActivity. */
data class PendingPlayback(val targetId: String, val startFromBeginning: Boolean)

data class DetailUiState(
    /**
     * Live mirror card, refreshed via [CoreGateway.cardById] on mirror-change/stop-report edges
     * ([DetailViewModel.refreshCard]) so header state follows playback without re-pushing the page.
     */
    val card: Card,
    /** Only ever populated for a Series [Card] -- empty for Movie/Episode. */
    val isLoadingSeasons: Boolean = false,
    /**
     * True once seasons have settled (success or failure); the focus seed (docs/15 §2 rules 2-3)
     * falls back to the `···` door only after this flips.
     */
    val seasonsSettled: Boolean = false,
    val seasons: List<Card> = emptyList(),
    val selectedSeasonId: String? = null,
    val isLoadingEpisodes: Boolean = false,
    val episodes: List<Card> = emptyList(),
    /**
     * docs/11 item 11: all of the series' episodes across every season, fetched via
     * [CoreGateway.seriesEpisodes] so [DetailFormatting.resolvePrimaryAction] finds the
     * in-progress/next-unplayed episode regardless of the selected season tab. Series-only.
     */
    val allEpisodes: List<Card> = emptyList(),
    /**
     * Spec strip/cast row/details footer data source (docs/11 tier 1 item 1, tier 2 items 8-9);
     * `null` until [CoreGateway.getItemDetail] resolves, or forever on failure.
     */
    val itemDetail: ItemDetail? = null,
    /**
     * True once [loadItemDetail] has settled (success or failure), distinct from [itemDetail]
     * being non-null; gates the spec strip/details/cast row so failure collapses cleanly.
     */
    val itemDetailLoaded: Boolean = false,
    /** docs/11 tier 2 item 13's Similar Titles row; empty pre-fetch and on failure alike. */
    val similar: List<Card> = emptyList(),
    /** Same settled-vs-empty signal as [itemDetailLoaded], gating Similar Titles' own reveal. */
    val similarLoaded: Boolean = false,
    /**
     * §2 item 5's "UP NEXT" panel (Episode only), via [CoreGateway.nextEpisodeAfter]; `null`
     * before the fetch settles or when this is the last episode.
     */
    val nextEpisode: Card? = null,
    /**
     * §3 item 4 / §4 item 3's Series/Movie eyebrow, resolved against [CoreGateway.views] and
     * shown verbatim (never prettified); `null` before settling, with no library id, or no match.
     */
    val libraryName: String? = null,
    // docs/19-detail-action-menu.md §3.3: detail action menu.

    /** `isAdministrator()` loaded once in init -- gates the Refresh metadata row. */
    val isAdministrator: Boolean = false,
    /** `listCollections()` loaded once in init -- gates the Add to collection row. */
    val collections: List<CollectionInfo> = emptyList(),
    /**
     * Collection ids [DetailUiState.card] belongs to (docs/19 §2.3/§3.3); backs the `ALREADY IN`
     * rows ([buildCollectionRows]). Fails open to an empty set.
     */
    val memberOfCollections: Set<String> = emptySet(),
    /**
     * BoxSet only: members in server order, via [CoreGateway.children] (docs/11 §Collection);
     * empty until [membersSettled].
     */
    val members: List<Card> = emptyList(),
    /** True once the first member load has settled (success or failure); gates the focus seed. */
    val membersSettled: Boolean = false,
    /** BoxSet only: seasons per Series member id, for the poster caption. */
    val memberSeasonCounts: Map<String, Int> = emptyMap(),
    /** BoxSet only: what the Play pill does; `null` for an empty collection. */
    val collectionPlay: CollectionFormatting.CollectionPlay? = null,
    /** Non-null while the action panel is open. */
    val menu: MenuUiState? = null,
    /** Non-null while the bulk-mark confirm block is expanded over [menu] (which stays open). */
    val confirm: BulkMarkConfirm? = null,
    /** In-app toast (docs/19 §1.4); cleared after [TOAST_DURATION_MS]. */
    val toast: String? = null,
    /** A Play* row's target; consumed once by [DetailScreen] to start PlaybackActivity. */
    val pendingPlayback: PendingPlayback? = null,
    /** A "Go to series" row's target, consumed once by [DetailScreen] to navigate. */
    val pendingSeriesNavigation: Card? = null,
)

/**
 * Header renders from the caller's [Card]; this ViewModel's job is a Series' seasons plus the
 * selected season's episodes, sorted client-side by `indexNumber` (nulls last). Quiet re-query
 * gated by [affectsThisDetail], same shape as [tv.jellybeam.ui.library.LibraryViewModel].
 */
class DetailViewModel(
    private val gateway: CoreGateway,
    private val card: Card,
    /** docs/17 §6's mini-player dismissal edge; injected so a test can drive it directly. */
    private val stopEpoch: Flow<Long> = PlaybackReports.stopEpoch,
    /** docs/19 §3.3's "Play something random" -- injected so tests are deterministic. */
    private val random: Random = Random.Default,
) : ViewModel() {

    private val _state = MutableStateFlow(DetailUiState(card = card))
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    /**
     * Latches once [selectSeason] runs from an actual tap, so [updateResumeSeasonIfNeeded] can
     * never yank the season back. Plain field: read/write on [viewModelScope]'s Main dispatcher.
     */
    private var userSelectedSeason = false

    /** The in-flight [loadCollection]; declared above `init` so its first write isn't clobbered. */
    private var collectionJob: Job? = null

    /**
     * docs/15 §0.2/§5: the resume season resolves once, after the per-episode signal settles.
     * Set `true` in [loadAllEpisodes]; [updateResumeSeasonIfNeeded] no-ops until then.
     */
    private var allEpisodesResolved = false

    /**
     * Drives [changeRefreshScheduler]'s sampling rate, same shape as Home/Library. Not yet wired
     * to a real `isTop` signal; defaults `true` (500ms visible rate, not the 3s hidden rate).
     */
    private val _active = MutableStateFlow(true)

    fun setActive(active: Boolean) {
        _active.value = active
    }

    /**
     * [ChangeRefreshScheduler] drives the mirror-relevant refresh (header card; for a Series, the
     * season/episode quiet re-query). Filtered before the scheduler so conflation can't swallow a
     * relevant event landing earlier in a burst than an irrelevant one.
     */
    private val changeRefreshScheduler: ChangeRefreshScheduler<ChangeEvent> = ChangeRefreshScheduler(
        scope = viewModelScope,
        events = gateway.changeEvents().filter(::affectsThisDetail),
        active = _active.asStateFlow(),
        refresh = {
            refreshCard()
            refreshChildren()
        },
    )

    init {
        // docs/11 tier 1 item 1 / tier 2 items 8-9: once-per-visit fetch for every item type;
        // never re-queried on a mirror change.
        viewModelScope.launch { loadItemDetail(card.id) }
        // docs/11 tier 2 item 13: skipped entirely for an Episode page.
        if (card.itemType != EPISODE_ITEM_TYPE && card.itemType != BOXSET_ITEM_TYPE) {
            viewModelScope.launch { loadSimilar(card.id) }
        }
        // §2 item 5: Episode page's UP NEXT panel, from OSD's credits-aware next-up picker.
        if (card.itemType == EPISODE_ITEM_TYPE) {
            viewModelScope.launch { loadNextEpisode(card.id) }
        }
        // §3 item 4 / §4 item 3: Series/Movie eyebrow needs the library NAME, not just the id.
        if (card.itemType != EPISODE_ITEM_TYPE && card.itemType != BOXSET_ITEM_TYPE) {
            viewModelScope.launch { loadLibraryName(card.libraryId) }
        }
        if (card.itemType == BOXSET_ITEM_TYPE) loadCollection()
        if (card.itemType == SERIES_ITEM_TYPE) {
            viewModelScope.launch { loadSeasons(card.id) }
            // docs/11 item 11's cross-season fix, independent of loadSeasons/selectSeason.
            viewModelScope.launch { loadAllEpisodes(card.id) }
        }
        // docs/19 §3.3: cached in the core after first page; menu rows read state, not re-fetch.
        viewModelScope.launch {
            val isAdmin = runCatchingCancellable { gateway.isAdministrator() }.getOrDefault(false)
            _state.update { it.copy(isAdministrator = isAdmin) }
        }
        viewModelScope.launch {
            val collections = runCatchingCancellable { gateway.listCollections() }.getOrDefault(emptyList())
            _state.update { it.copy(collections = collections) }
        }
        viewModelScope.launch { refreshMemberOfCollections() }
        // Re-query on mirror changes for every item type, including [DetailUiState.card] itself,
        // quietly (no isLoading flips, selection preserved). docs/17 §6: a PiP dismissal's stop
        // report has no live Detail screen in its call stack; `drop(1)` skips the flow's seed.
        viewModelScope.launch {
            stopEpoch.drop(1).collect {
                refreshCard()
                refreshChildren()
            }
        }
    }

    /** Re-fetches the live card so header UI tracks playback; fails open, card left as-is. */
    private suspend fun refreshCard() {
        runCatchingCancellable { gateway.cardById(card.id) }.getOrNull()?.let { fresh ->
            _state.update { it.copy(card = fresh) }
        }
    }

    /** The Series season/episode re-query or the BoxSet member re-load; nothing for other types. */
    private suspend fun refreshChildren() {
        when (card.itemType) {
            SERIES_ITEM_TYPE -> refreshQuietly(card.id)
            // Awaited, as the Series re-query is: the scheduler's next run must not cancel this one.
            BOXSET_ITEM_TYPE -> loadCollection().join()
        }
    }

    /**
     * docs/11 §Collection: loads members, Series season counts and the Play target in one state
     * write so the pill never shows before its subtext resolves; a newer load cancels an older.
     * Fails open: on a throw [DetailUiState.membersSettled] flips with whatever was loaded before.
     */
    private fun loadCollection(): Job {
        collectionJob?.cancel()
        return viewModelScope.launch {
            val members = runCatchingCancellable { gateway.children(card.id, SortOrder.INDEX_NUMBER, 0u, UInt.MAX_VALUE) }
                .getOrNull()?.let { DetailFormatting.dedupeById(it) { member -> member.id } }
            if (members == null) {
                _state.update { it.copy(membersSettled = true) }
                return@launch
            }
            val seasonCounts = coroutineScope {
                members.filter { it.itemType == SERIES_ITEM_TYPE }.map { series ->
                    async {
                        val seasons = runCatchingCancellable { gateway.children(series.id, SortOrder.INDEX_NUMBER, 0u, UInt.MAX_VALUE) }
                            .getOrNull()
                        seasons?.let { series.id to CollectionFormatting.seasonCount(it) }
                    }
                }.awaitAll().filterNotNull().toMap()
            }
            val play = CollectionFormatting.resolvePlay(members, previous = _state.value.collectionPlay) { series ->
                runCatchingCancellable { gateway.seriesEpisodes(series.id) }.getOrNull()
            }
            _state.update { it.copy(members = members, membersSettled = true, memberSeasonCounts = seasonCounts, collectionPlay = play) }
        }.also { collectionJob = it }
    }

    /** The Play pill: starts [DetailUiState.collectionPlay] through the shared playback hand-off. */
    fun playCollection() {
        val play = _state.value.collectionPlay ?: return
        startPlayback(play.targetId, play.fromStart)
    }

    /** Fails open (docs/11 item 1): a throw leaves [DetailUiState.itemDetail] `null` forever. */
    private suspend fun loadItemDetail(itemId: String) {
        val detail = runCatchingCancellable { gateway.getItemDetail(itemId) }.getOrNull()
        _state.update { it.copy(itemDetail = detail, itemDetailLoaded = true) }
    }

    /** Fails open to empty; [DetailFormatting.dedupeById] guards a server-duplicated title. */
    private suspend fun loadSimilar(itemId: String) {
        val similar = runCatchingCancellable { gateway.getSimilar(itemId, SIMILAR_LIMIT) }.getOrDefault(emptyList())
        _state.update { it.copy(similar = DetailFormatting.dedupeById(similar) { it.id }, similarLoaded = true) }
    }

    /**
     * [allEpisodesResolved] flips regardless of success, guaranteeing one final
     * [updateResumeSeasonIfNeeded] pass. Deduped: a repeated id is a mirror data error.
     */
    private suspend fun loadAllEpisodes(seriesId: String) {
        val allEpisodes = try {
            gateway.seriesEpisodes(seriesId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        _state.update { it.copy(allEpisodes = DetailFormatting.dedupeById(allEpisodes) { it.id }) }
        allEpisodesResolved = true
        updateResumeSeasonIfNeeded()
    }

    /** Fails open to `null` on any throw; the Up Next panel simply doesn't render. */
    private suspend fun loadNextEpisode(episodeId: String) {
        val next = runCatchingCancellable { gateway.nextEpisodeAfter(episodeId) }.getOrNull()
        _state.update { it.copy(nextEpisode = next) }
    }

    /** [CoreGateway.views] is the nav drawer's library list; failure or no match fails open. */
    private suspend fun loadLibraryName(libraryId: String?) {
        if (libraryId == null) return
        val name = runCatchingCancellable { gateway.views() }.getOrDefault(emptyList()).firstOrNull { it.id == libraryId }?.name
        _state.update { it.copy(libraryName = name) }
    }

    /**
     * Same shape as [tv.jellybeam.ui.library.LibraryViewModel.affectsThisLibrary]: a cheap
     * containment check against known ids, falling open (refetches) when nothing is known yet.
     */
    private fun affectsThisDetail(event: ChangeEvent): Boolean {
        if (card.itemType != SERIES_ITEM_TYPE && card.itemType != BOXSET_ITEM_TYPE) {
            return when (event) {
                is ChangeEvent.Upserted -> card.id in event.ids
                ChangeEvent.Refresh -> true
                else -> false
            }
        }
        // Hoisted out of the `any {}` lambdas so a burst of N ids doesn't rebuild this set N times.
        val knownIds = knownIds()
        return when (event) {
            is ChangeEvent.Upserted -> knownIds.isEmpty() || event.ids.any { it == card.id || it in knownIds }
            is ChangeEvent.Removed -> event.ids.any { it == card.id || it in knownIds }
            ChangeEvent.Refresh -> true
            ChangeEvent.ViewsChanged -> false
        }
    }

    private fun knownIds(): Set<String> {
        val state = _state.value
        val ids = mutableSetOf<String>()
        state.seasons.mapTo(ids) { it.id }
        state.episodes.mapTo(ids) { it.id }
        state.allEpisodes.mapTo(ids) { it.id }
        state.members.mapTo(ids) { it.id }
        // A Series member's target episode: watched elsewhere, it must move the pill on.
        state.collectionPlay?.episode?.let { ids += it.id }
        return ids
    }

    private suspend fun refreshQuietly(seriesId: String) {
        val seasons = gateway.children(seriesId, SortOrder.INDEX_NUMBER, 0u, UInt.MAX_VALUE)
            .let { DetailFormatting.dedupeById(it) { season -> season.id } }
            .sortedWith(compareBy(nullsLast()) { it.indexNumber })
        _state.update { it.copy(seasons = seasons) }
        // docs/11 item 11: re-fetched unconditionally, ahead of the early-return below -- a
        // watched-state flip anywhere in the series can change [resolvePrimaryAction].
        _state.update { it.copy(allEpisodes = DetailFormatting.dedupeById(gateway.seriesEpisodes(seriesId)) { it.id }) }
        val seasonId = _state.value.selectedSeasonId ?: return
        val episodes = gateway.children(seasonId, SortOrder.INDEX_NUMBER, 0u, UInt.MAX_VALUE)
            .let { DetailFormatting.dedupeById(it) { episode -> episode.id } }
            .sortedWith(compareBy(nullsLast()) { it.indexNumber })
        _state.update {
            if (it.selectedSeasonId == seasonId) it.copy(episodes = episodes) else it
        }
    }

    private suspend fun loadSeasons(seriesId: String) {
        _state.update { it.copy(isLoadingSeasons = true) }
        val seasons = try {
            gateway.children(seriesId, SortOrder.INDEX_NUMBER, 0u, UInt.MAX_VALUE)
                .let { DetailFormatting.dedupeById(it) { season -> season.id } }
                .sortedWith(compareBy(nullsLast()) { it.indexNumber })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Fails open so the page still settles and its focus seed can fall back to the door.
            _state.update { it.copy(isLoadingSeasons = false, seasonsSettled = true) }
            return
        }
        _state.update { it.copy(isLoadingSeasons = false, seasonsSettled = true, seasons = seasons) }
        updateResumeSeasonIfNeeded()
    }

    /**
     * docs/15 §0.2/§5: applies a selection only once both seasons and [loadAllEpisodes] have
     * settled ([allEpisodesResolved]), via [selectSeasonInternal] (not [selectSeason], which sets
     * [userSelectedSeason]). No-ops once [userSelectedSeason] is set.
     */
    private fun updateResumeSeasonIfNeeded() {
        if (userSelectedSeason) return
        val state = _state.value
        if (state.isLoadingSeasons || !allEpisodesResolved) return
        val resolved = DetailFormatting.resolveResumeSeason(state.seasons, state.allEpisodes) ?: return
        if (state.selectedSeasonId != resolved.id) selectSeasonInternal(resolved.id)
    }

    /**
     * Season tab tap: latches [userSelectedSeason] unconditionally (even if [seasonId] is
     * already selected) so [updateResumeSeasonIfNeeded] can never yank it back later.
     */
    fun selectSeason(seasonId: String) {
        userSelectedSeason = true
        selectSeasonInternal(seasonId)
    }

    /**
     * Season-switch logic shared by [selectSeason] (viewer tap) and [updateResumeSeasonIfNeeded]
     * (programmatic); only [selectSeason] sets the latch. [DetailFormatting.dedupeById] guards a
     * repeated id, which can't survive `LazyVerticalGrid`'s `key = { episode.id }`.
     */
    private fun selectSeasonInternal(seasonId: String) {
        if (_state.value.selectedSeasonId == seasonId) return
        _state.update { it.copy(selectedSeasonId = seasonId, isLoadingEpisodes = true, episodes = emptyList()) }
        viewModelScope.launch {
            val episodes = gateway.children(seasonId, SortOrder.INDEX_NUMBER, 0u, UInt.MAX_VALUE)
                .let { DetailFormatting.dedupeById(it) { episode -> episode.id } }
                .sortedWith(compareBy(nullsLast()) { it.indexNumber })
            // A later selectSeason call may have moved on -- don't clobber it with a stale reply.
            _state.update {
                if (it.selectedSeasonId == seasonId) it.copy(isLoadingEpisodes = false, episodes = episodes) else it
            }
        }
    }

    // Detail action menu (docs/19-detail-action-menu.md §3.3)

    /** Cancels a pending auto-dismiss so a newer toast isn't cut short by an older timer. */
    private var toastJob: Job? = null

    /** Scope [MenuAction.PlayRandom] picks from, captured at [openMenu] (menu traps focus). */
    private var randomPool: List<Card> = emptyList()

    /**
     * docs/19: scope is the series until a season chip is tapped -- keyed off [userSelectedSeason],
     * not [DetailUiState.selectedSeasonId] alone. `null` for Movie/Episode and unscoped Series.
     */
    private fun currentScopeSeason(): Card? {
        if (card.itemType != SERIES_ITEM_TYPE || !userSelectedSeason) return null
        val state = _state.value
        return state.seasons.firstOrNull { it.id == state.selectedSeasonId }
    }

    /** Opens the panel; focus per docs/19 §1.2, seeded from state with no new fetch (§3.1). */
    fun openMenu() {
        val state = _state.value
        val scopeSeason = currentScopeSeason()
        val scopeEpisodes = if (scopeSeason != null) state.episodes else emptyList()
        val seriesCard = if (card.itemType == EPISODE_ITEM_TYPE) DetailFormatting.seriesCardFrom(state.card) else null
        val input = MenuInput(
            card = state.card,
            itemType = card.itemType,
            scopeSeason = scopeSeason,
            scopeEpisodes = scopeEpisodes,
            allEpisodes = state.allEpisodes,
            primaryAction = DetailFormatting.resolvePrimaryAction(state.card, state.allEpisodes),
            isFavorite = state.card.isFavorite,
            hasCollections = state.collections.isNotEmpty(),
            isAdministrator = state.isAdministrator,
            seriesCard = seriesCard,
            highlightedSeasonNumber = state.seasons.firstOrNull { it.id == state.selectedSeasonId }?.indexNumber,
        )
        val model = buildMenu(input)
        randomPool = scopeSeason?.let { scopeEpisodes } ?: state.allEpisodes
        _state.update { it.copy(menu = MenuUiState(model = model, level = MenuLevel.FIRST, scopeSeasonNumber = scopeSeason?.indexNumber)) }
    }

    /** Back/Left/Select on a row, or an action completing -- closes the panel and confirm block. */
    fun closeMenu() {
        _state.update { it.copy(menu = null, confirm = null) }
    }

    /** "Add to collection" select -- swaps panel content for the collections list (§1.3). */
    fun enterCollections() {
        _state.update { it.copy(menu = it.menu?.copy(level = MenuLevel.COLLECTIONS)) }
    }

    /** Back from collections -- returns to first level (docs/19 §1.3); panel remembers refocus. */
    fun backFromCollections() {
        _state.update { it.copy(menu = it.menu?.copy(level = MenuLevel.FIRST)) }
    }

    /**
     * Adds the item, closes the panel, toasts `Added to {Name}` (docs/19 §1.3/§1.4); no-op for
     * a collection already in [DetailUiState.memberOfCollections] (an `ALREADY IN` row).
     */
    fun selectCollection(collection: CollectionInfo) {
        val state = _state.value
        if (collection.id in state.memberOfCollections) return
        val itemId = state.card.id
        _state.update { it.copy(menu = null) }
        viewModelScope.launch {
            val result = runCatchingCancellable { gateway.addToCollection(collection.id, itemId) }
            if (result.isSuccess) {
                // Optimistic local add: the core's membership re-fetch runs after the POST, so
                // a mirror read now could still predate it.
                refreshMemberOfCollections(keep = setOf(collection.id))
            }
            showToast(if (result.isSuccess) "Added to ${collection.name}" else TOAST_SERVER_UNREACHABLE)
        }
    }

    /** Loads [DetailUiState.memberOfCollections]; fails open to an empty set. */
    private suspend fun refreshMemberOfCollections(keep: Set<String> = emptySet()) {
        val ids = runCatchingCancellable { gateway.collectionIdsContaining(_state.value.card.id) }.getOrDefault(emptyList())
        _state.update { it.copy(memberOfCollections = ids.toSet() + keep) }
    }

    /** Dispatches a row's action -- the menu's single entry point from the Compose layer. */
    fun runAction(action: MenuAction) {
        when (action) {
            MenuAction.MarkWatched -> markSingle(played = true)
            MenuAction.MarkUnwatched -> markSingle(played = false)
            is MenuAction.MarkScopeWatched -> beginBulkMark(action, played = true, count = action.count)
            is MenuAction.MarkScopeUnwatched -> beginBulkMark(action, played = false, count = action.count)
            MenuAction.AddFavorite -> setFavorite(favorite = true)
            MenuAction.RemoveFavorite -> setFavorite(favorite = false)
            is MenuAction.PlayNextUnwatched -> startPlayback(action.targetId, startFromBeginning = false)
            is MenuAction.PlayFromBeginning -> startPlayback(action.targetId, startFromBeginning = true)
            is MenuAction.PlayRandom -> {
                // docs/19 §3.3: reads only the pool captured at menu-open time, no gateway state.
                val target = pickRandom(randomPool, random)
                if (target != null) startPlayback(target.id, startFromBeginning = false) else closeMenu()
            }
            is MenuAction.GoToSeries -> _state.update { it.copy(menu = null, pendingSeriesNavigation = action.series) }
            MenuAction.AddToCollection -> enterCollections()
            MenuAction.RefreshMetadata -> refreshMetadata()
        }
    }

    /** Single-item mark: never confirms (docs/19 §1.3); header follows the mirror change event. */
    private fun markSingle(played: Boolean) {
        val itemId = _state.value.card.id
        _state.update { it.copy(menu = null) }
        viewModelScope.launch {
            val result = runCatchingCancellable { gateway.setPlayed(itemId, played) }
            showToast(if (result.isSuccess) (if (played) TOAST_MARKED_WATCHED else TOAST_MARKED_UNWATCHED) else TOAST_SERVER_UNREACHABLE)
        }
    }

    /** Series/season mark: expands confirm over the triggering row; panel stays open (§1.3). */
    private fun beginBulkMark(action: MenuAction, played: Boolean, count: Int) {
        val scopeSeason = currentScopeSeason()
        val state = _state.value
        val scopeId = scopeSeason?.id ?: state.card.id
        val scopeName = scopeSeason?.name ?: state.card.name
        _state.update { it.copy(confirm = BulkMarkConfirm(played, count, scopeId, scopeName, action)) }
    }

    /** Confirm: closes confirm+panel, calls the recursive mark, toasts (docs/19 §1.3/§1.4). */
    fun confirmBulkMark() {
        val confirm = _state.value.confirm ?: return
        _state.update { it.copy(confirm = null, menu = null) }
        viewModelScope.launch {
            val result = runCatchingCancellable { gateway.setPlayedRecursive(confirm.scopeId, confirm.played) }
            showToast(if (result.isSuccess) (if (confirm.played) TOAST_MARKED_WATCHED else TOAST_MARKED_UNWATCHED) else TOAST_SERVER_UNREACHABLE)
        }
    }

    /** Cancel/Back on confirm -- collapses it, focus returns to the opening row (docs/19 §4). */
    fun dismissConfirm() {
        _state.update { it.copy(confirm = null) }
    }

    private fun setFavorite(favorite: Boolean) {
        val itemId = _state.value.card.id
        _state.update { it.copy(menu = null) }
        viewModelScope.launch {
            val result = runCatchingCancellable { gateway.setFavorite(itemId, favorite) }
            showToast(if (result.isSuccess) (if (favorite) TOAST_ADDED_FAVORITE else TOAST_REMOVED_FAVORITE) else TOAST_SERVER_UNREACHABLE)
        }
    }

    private fun refreshMetadata() {
        val itemId = _state.value.card.id
        _state.update { it.copy(menu = null) }
        viewModelScope.launch {
            val result = runCatchingCancellable { gateway.refreshMetadata(itemId) }
            showToast(if (result.isSuccess) TOAST_REFRESHING_METADATA else TOAST_SERVER_UNREACHABLE)
        }
    }

    /** Closes menu, hands [DetailScreen] a playback target (§1.4: Play navigates, no toast). */
    private fun startPlayback(targetId: String, startFromBeginning: Boolean) {
        _state.update { it.copy(menu = null, pendingPlayback = PendingPlayback(targetId, startFromBeginning)) }
    }

    /** [DetailScreen] calls this once PlaybackActivity has started for [pendingPlayback]. */
    fun consumePendingPlayback() {
        _state.update { it.copy(pendingPlayback = null) }
    }

    /** [DetailScreen] calls this once it has navigated for [pendingSeriesNavigation]. */
    fun consumePendingSeriesNavigation() {
        _state.update { it.copy(pendingSeriesNavigation = null) }
    }

    private fun showToast(text: String) {
        _state.update { it.copy(toast = text) }
        toastJob?.cancel()
        toastJob = viewModelScope.launch {
            delay(TOAST_DURATION_MS)
            _state.update { it.copy(toast = null) }
        }
    }

    /** Dismisses the toast immediately, e.g. if a screen wants to clear it early. */
    fun dismissToast() {
        toastJob?.cancel()
        _state.update { it.copy(toast = null) }
    }
}

class DetailViewModelFactory(
    private val gateway: CoreGateway,
    private val card: Card,
    /** See [DetailViewModel]'s own constructor param of the same name. */
    private val stopEpoch: Flow<Long> = PlaybackReports.stopEpoch,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(DetailViewModel::class.java))
        return DetailViewModel(gateway, card, stopEpoch) as T
    }
}
