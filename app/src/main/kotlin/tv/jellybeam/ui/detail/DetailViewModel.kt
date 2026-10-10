package tv.jellybeam.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import tv.jellybeam.R
import tv.jellybeam.data.CoreGateway
import tv.jellybeam.data.runCatchingCancellable
import tv.jellybeam.i18n.UiStrings
import tv.jellybeam.player.PlaybackReports
import tv.jellybeam.ui.common.ChangeRefreshScheduler
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
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
     * True once the first [allEpisodes] fetch has settled (success or failure), so "no primary
     * pill" can be told apart from "primary pill not resolved yet" (docs/15 §2 rule 3).
     */
    val allEpisodesSettled: Boolean = false,
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
     * True once [loadNextEpisode] has settled (success, failure, or no next episode); separates
     * "Up Next still loading" from "no Up Next" (docs/11 §Loading state).
     */
    val nextEpisodeLoaded: Boolean = false,
    /**
     * §3 item 4 / §4 item 3's Series/Movie eyebrow, resolved against [CoreGateway.views] and
     * shown verbatim (never prettified); `null` before settling, with no library id, or no match.
     */
    val libraryName: String? = null,
    /**
     * True once [loadLibraryName] has settled, including failure and a card with no library id;
     * separates "eyebrow still loading" from "no eyebrow" (docs/11 §Loading state).
     */
    val libraryNameLoaded: Boolean = false,
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
    /** docs/27 §3: toast and menu text. */
    private val strings: UiStrings,
    /** docs/17 §6's mini-player dismissal edge; injected so a test can drive it directly. */
    private val stopEpoch: Flow<Long> = PlaybackReports.stopEpoch,
    /** docs/19 §3.3's "Play something random" -- injected so tests are deterministic. */
    private val random: Random = Random.Default,
) : ViewModel() {

    private val _state = MutableStateFlow(DetailUiState(card = card))
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    /**
     * The season the viewer last tapped (docs/19: menu scope = the viewer's pick, only while it is
     * still the selection). Plain field: read/write on [viewModelScope]'s Main dispatcher.
     */
    private var viewerPickId: String? = null

    /** Newest [allEpisodesSeq] / [seasonsSeq] whose result was published: an older read never lands over it. */
    private var publishedAllSeq = 0
    private var seasonsSeq = 0
    private var publishedSeasonsSeq = 0

    /** Bumped by every [applySelection]; [publishEpisodes] drops a reply from an older generation. */
    private var selectionGen = 0

    /** The in-flight [loadCollection]; declared above `init` so its first write isn't clobbered. */
    private var collectionJob: Job? = null

    /**
     * True once the first [publishAllEpisodes] has landed (success or failure), the per-episode
     * signal the resume pick waits for (docs/15 §0.2/§5).
     */
    private var allEpisodesResolved = false

    /**
     * `Settings::show_virtual_episodes` as of the last [allEpisodes] fetch, so a season switch can
     * apply the core's `children()` virtual filter in memory; `null` (unread) -> fall back to
     * [CoreGateway.children].
     */
    private var showVirtualEpisodes: Boolean? = null

    /** Bumped by each [DetailUiState.allEpisodes] read, so an older read never lands over a newer one. */
    private var allEpisodesSeq = 0

    private suspend fun refreshShowVirtualEpisodes() {
        showVirtualEpisodes = runCatchingCancellable { gateway.getSettings().showVirtualEpisodes }.getOrNull()
    }

    /**
     * The season's episodes derived from [DetailUiState.allEpisodes] ([DetailFormatting.episodesOfSeason]),
     * or `null` when memory can't answer faithfully and the caller must query [CoreGateway.children].
     */
    private fun episodesFromMemory(seasonId: String): List<Card>? {
        val state = _state.value
        if (!state.allEpisodesSettled) return null
        val season = state.seasons.firstOrNull { it.id == seasonId } ?: return null
        val showVirtual = showVirtualEpisodes ?: return null
        return DetailFormatting.episodesOfSeason(season, state.allEpisodes, showVirtual)
    }

    /**
     * Drives [changeRefreshScheduler]'s sampling rate, same shape as Home/Library: whether the
     * screen is top of its stack ([setActive]); `true` until told otherwise (500ms, not the 3s hidden rate).
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
        // Unlimited buffer: the suspending filter must not make this subscriber lag the DROP_OLDEST bus.
        events = gateway.changeEvents().buffer(Channel.UNLIMITED).filter(::affectsThisDetail),
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
            // The scheduler's one loop owns refreshes, so a stop report never overlaps a change refresh.
            stopEpoch.drop(1).collect { changeRefreshScheduler.requestRefresh() }
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
            val play = CollectionFormatting.resolvePlay(strings, members, previous = _state.value.collectionPlay) { series ->
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

    /**
     * docs/11 item 1: the mirror's [CoreGateway.itemDetailLocal] paints first (meta line, genres,
     * overview, ADDED eyebrow), then the live [CoreGateway.getItemDetail] replaces it -- network
     * authoritative. Fails open: a throw keeps the mirror record, or `null` if there was none.
     */
    private suspend fun loadItemDetail(itemId: String) = coroutineScope {
        val live = async { runCatchingCancellable { gateway.getItemDetail(itemId) }.getOrNull() }
        val local = runCatchingCancellable { gateway.itemDetailLocal(itemId) }.getOrNull()
        // Never over a record the live fetch already delivered.
        if (local != null) _state.update { if (it.itemDetailLoaded) it else it.copy(itemDetail = local) }
        val detail = live.await()
        _state.update { it.copy(itemDetail = detail ?: it.itemDetail, itemDetailLoaded = true) }
    }

    /** Fails open to empty; [DetailFormatting.dedupeById] guards a server-duplicated title. */
    private suspend fun loadSimilar(itemId: String) {
        val similar = runCatchingCancellable { gateway.getSimilar(itemId, SIMILAR_LIMIT) }.getOrDefault(emptyList())
        _state.update { it.copy(similar = DetailFormatting.dedupeById(similar) { it.id }, similarLoaded = true) }
    }

    /**
     * Fails open to empty so the page always settles; the result goes through [publishAllEpisodes]
     * like every other read, so an older one never lands over a newer.
     */
    private suspend fun loadAllEpisodes(seriesId: String) {
        val seq = ++allEpisodesSeq
        val allEpisodes = try {
            gateway.seriesEpisodes(seriesId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        refreshShowVirtualEpisodes()
        publishAllEpisodes(seq, allEpisodes)
    }

    /**
     * The only writer of [DetailUiState.allEpisodes], [DetailUiState.allEpisodesSettled] and
     * [allEpisodesResolved]: a [seq] older than the newest published is dropped whole (no settle, no resume pick), the winner
     * then re-derives the selection. Deduped: a repeated id is a mirror data error.
     */
    private fun publishAllEpisodes(seq: Int, list: List<Card>) {
        if (seq <= publishedAllSeq) return
        publishedAllSeq = seq
        _state.update { it.copy(allEpisodes = DetailFormatting.dedupeById(list) { e -> e.id }, allEpisodesSettled = true) }
        allEpisodesResolved = true
        syncSelection(resyncEpisodes = true)
    }

    /** Fails open to `null` on any throw; the Up Next panel simply doesn't render. */
    private suspend fun loadNextEpisode(episodeId: String) {
        val next = runCatchingCancellable { gateway.nextEpisodeAfter(episodeId) }.getOrNull()
        _state.update { it.copy(nextEpisode = next, nextEpisodeLoaded = true) }
    }

    /** [CoreGateway.views] is the nav drawer's library list; failure or no match fails open. */
    private suspend fun loadLibraryName(libraryId: String?) {
        if (libraryId == null) {
            _state.update { it.copy(libraryNameLoaded = true) }
            return
        }
        val name = runCatchingCancellable { gateway.views() }.getOrDefault(emptyList()).firstOrNull { it.id == libraryId }?.name
        _state.update { it.copy(libraryName = name, libraryNameLoaded = true) }
    }

    /**
     * Same shape as [tv.jellybeam.ui.library.LibraryViewModel.affectsThisLibrary]: a containment
     * check against known ids, plus a mirror probe for a Series' unknown ids ([DetailFormatting.collectionChange]).
     */
    private suspend fun affectsThisDetail(event: ChangeEvent): Boolean {
        if (card.itemType != SERIES_ITEM_TYPE && card.itemType != BOXSET_ITEM_TYPE) {
            return when (event) {
                is ChangeEvent.Upserted -> card.id in event.ids
                ChangeEvent.Refresh -> true
                else -> false
            }
        }
        // Hoisted out of the `any {}` lambdas so a burst of N ids doesn't rebuild this set N times.
        val knownIds = knownIds()
        return when (val change = DetailFormatting.collectionChange(event, card.id, card.itemType == SERIES_ITEM_TYPE, knownIds)) {
            DetailFormatting.CollectionChange.Affects -> true
            DetailFormatting.CollectionChange.Ignores -> false
            is DetailFormatting.CollectionChange.AffectsIfChildOf -> DetailFormatting.isChildOfSeries(
                runCatchingCancellable { gateway.cardsByIds(change.unknownIds) }.getOrDefault(emptyList()),
                card.id,
            )
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
        val seasonsSeqNow = ++seasonsSeq
        // A failed read keeps the current seasons; the scheduler's loop must not see the throw.
        val seasons = runCatchingCancellable {
            gateway.children(seriesId, SortOrder.INDEX_NUMBER, 0u, UInt.MAX_VALUE)
                .let { DetailFormatting.dedupeById(it) { season -> season.id } }
                .sortedWith(compareBy(nullsLast()) { it.indexNumber })
        }.getOrNull()
        // Seasons and the selection move in one synchronous section: no state has seasons without the selected chip.
        publishSeasons(seasonsSeqNow, seasons, initial = false)
        // docs/11 item 11: re-fetched unconditionally -- a watched-state flip anywhere in the
        // series can change [resolvePrimaryAction].
        val seq = ++allEpisodesSeq
        val allEpisodes = try {
            gateway.seriesEpisodes(seriesId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Keep what is on screen; an unsettled page still has its initial read in flight, which fails open itself.
            return
        }
        refreshShowVirtualEpisodes()
        publishAllEpisodes(seq, allEpisodes)
    }

    private suspend fun loadSeasons(seriesId: String) {
        val seq = ++seasonsSeq
        _state.update { it.copy(isLoadingSeasons = true) }
        // Fails open so the page still settles and its focus seed can fall back to the door.
        val seasons = runCatchingCancellable {
            gateway.children(seriesId, SortOrder.INDEX_NUMBER, 0u, UInt.MAX_VALUE)
                .let { DetailFormatting.dedupeById(it) { season -> season.id } }
                .sortedWith(compareBy(nullsLast()) { it.indexNumber })
        }.getOrNull()
        publishSeasons(seq, seasons, initial = true)
    }

    /**
     * The only writer of [DetailUiState.seasons], [DetailUiState.isLoadingSeasons] and
     * [DetailUiState.seasonsSettled]: a null [seasons] (failed read) or one older than the newest
     * published leaves the list alone, and every exit re-derives the selection. Only the [initial]
     * read settles the flags.
     */
    private fun publishSeasons(seq: Int, seasons: List<Card>?, initial: Boolean) {
        val take = seasons != null && seq > publishedSeasonsSeq
        if (take) publishedSeasonsSeq = seq
        _state.update {
            val next = if (take) it.copy(seasons = seasons!!) else it
            if (initial) next.copy(isLoadingSeasons = false, seasonsSettled = true) else next
        }
        syncSelection(resyncEpisodes = false)
    }

    /**
     * Re-derives the selection after seasons or allEpisodes changed (docs/15 §0.2/§5): the first
     * pick waits for both signals and never repeats; a removed pick falls over
     * ([DetailFormatting.reconcileSelectedSeason]). [resyncEpisodes] re-serves the kept selection
     * from the fresh [DetailUiState.allEpisodes] without a skeleton.
     */
    private fun syncSelection(resyncEpisodes: Boolean) {
        val state = _state.value
        if (state.isLoadingSeasons || !state.seasonsSettled) return
        val current = state.selectedSeasonId
        val target = DetailFormatting.reconcileSelectedSeason(current, state.seasons, state.allEpisodes, settled = allEpisodesResolved)
        when {
            target == null -> Unit
            target != current -> {
                // A programmatic move is not the viewer's choice (docs/19: scope follows the pick).
                viewerPickId = null
                applySelection(target)
            }
            // An empty or foreign seasons list reads children(unknown parent) as [] -- never wipe the shelf with it.
            resyncEpisodes && state.seasons.any { it.id == target } -> applySelection(target, quiet = true)
        }
    }

    /** Season tab tap: records the viewer's pick (a selection is never cleared, so the first pick is also the last resume pick). */
    fun selectSeason(seasonId: String) {
        viewerPickId = seasonId
        applySelection(seasonId)
    }

    /**
     * The only writer of [DetailUiState.selectedSeasonId]: bumps [selectionGen], serves the season
     * from memory in the same frame, else queries [CoreGateway.children]; episodes land only via
     * [publishEpisodes]. [quiet] re-serves the current season without a skeleton.
     * [DetailFormatting.dedupeById] guards a repeated id, which can't survive `LazyVerticalGrid`'s key.
     */
    private fun applySelection(seasonId: String, quiet: Boolean = false) {
        if (!quiet && _state.value.selectedSeasonId == seasonId) return
        val gen = ++selectionGen
        val local = episodesFromMemory(seasonId)
        _state.update {
            when {
                local != null -> it.copy(selectedSeasonId = seasonId, isLoadingEpisodes = false, episodes = local)
                quiet -> it.copy(selectedSeasonId = seasonId)
                else -> it.copy(selectedSeasonId = seasonId, isLoadingEpisodes = true, episodes = emptyList())
            }
        }
        viewModelScope.launch {
            if (local != null) {
                // The cached virtual-episodes flag can predate a Settings change made while this page sat
                // on the stack; re-read it and re-derive once if it moved.
                val cached = showVirtualEpisodes
                refreshShowVirtualEpisodes()
                if (showVirtualEpisodes == cached) return@launch
                episodesFromMemory(seasonId)?.let { publishEpisodes(gen, it) }
                return@launch
            }
            val fetched = runCatchingCancellable {
                gateway.children(seasonId, SortOrder.INDEX_NUMBER, 0u, UInt.MAX_VALUE)
                    .let { DetailFormatting.dedupeById(it) { episode -> episode.id } }
                    .sortedWith(compareBy(nullsLast()) { it.indexNumber })
            }.getOrNull()
            // Fails open to an empty shelf so the loading flag always clears (docs/11 §Loading state);
            // a quiet re-serve keeps what is on screen instead.
            if (fetched == null && quiet && !_state.value.isLoadingEpisodes) return@launch
            publishEpisodes(gen, fetched ?: emptyList())
        }
    }

    /** The only writer of a network-served [DetailUiState.episodes]; a stale [gen] is dropped whole. */
    private fun publishEpisodes(gen: Int, episodes: List<Card>) {
        _state.update { if (gen == selectionGen) it.copy(isLoadingEpisodes = false, episodes = episodes) else it }
    }

    // Detail action menu (docs/19-detail-action-menu.md §3.3)

    /** Cancels a pending auto-dismiss so a newer toast isn't cut short by an older timer. */
    private var toastJob: Job? = null

    /** Scope [MenuAction.PlayRandom] picks from, captured at [openMenu] (menu traps focus). */
    private var randomPool: List<Card> = emptyList()

    /**
     * docs/19: scope is the series until a season chip is tapped -- keyed off [viewerPickId] while
     * it is still the selection, not [DetailUiState.selectedSeasonId] alone. `null` for Movie/Episode and unscoped Series.
     */
    private fun currentScopeSeason(): Card? {
        val state = _state.value
        if (card.itemType != SERIES_ITEM_TYPE || viewerPickId == null || viewerPickId != state.selectedSeasonId) return null
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
            primaryAction = DetailFormatting.resolvePrimaryAction(strings, state.card, state.allEpisodes),
            isFavorite = state.card.isFavorite,
            hasCollections = state.collections.isNotEmpty(),
            isAdministrator = state.isAdministrator,
            seriesCard = seriesCard,
            highlightedSeasonNumber = state.seasons.firstOrNull { it.id == state.selectedSeasonId }?.indexNumber,
        )
        val model = buildMenu(strings, input)
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
            showToast(if (result.isSuccess) strings.get(R.string.detail_toast_added_to_collection, collection.name) else serverUnreachable())
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
            showToast(if (result.isSuccess) (if (played) markedWatched() else markedUnwatched()) else serverUnreachable())
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
            showToast(if (result.isSuccess) (if (confirm.played) markedWatched() else markedUnwatched()) else serverUnreachable())
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
            showToast(if (result.isSuccess) strings.get(if (favorite) R.string.detail_toast_added_favorite else R.string.detail_toast_removed_favorite) else serverUnreachable())
        }
    }

    private fun refreshMetadata() {
        val itemId = _state.value.card.id
        _state.update { it.copy(menu = null) }
        viewModelScope.launch {
            val result = runCatchingCancellable { gateway.refreshMetadata(itemId) }
            showToast(if (result.isSuccess) strings.get(R.string.detail_toast_refreshing_metadata) else serverUnreachable())
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

    private fun markedWatched() = strings.get(R.string.detail_toast_marked_watched)

    private fun markedUnwatched() = strings.get(R.string.detail_toast_marked_unwatched)

    private fun serverUnreachable() = strings.get(R.string.detail_toast_server_unreachable)

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
    private val strings: UiStrings,
    /** See [DetailViewModel]'s own constructor param of the same name. */
    private val stopEpoch: Flow<Long> = PlaybackReports.stopEpoch,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(DetailViewModel::class.java))
        return DetailViewModel(gateway, card, strings, stopEpoch) as T
    }
}
