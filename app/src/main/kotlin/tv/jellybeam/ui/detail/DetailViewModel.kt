package tv.jellybeam.ui.detail

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import tv.jellybeam.AppGraph
import tv.jellybeam.R
import tv.jellybeam.data.BrowseNotReady
import tv.jellybeam.data.CoreGateway
import tv.jellybeam.data.SettingsStore
import tv.jellybeam.data.runCatchingCancellable
import tv.jellybeam.i18n.UiStrings
import tv.jellybeam.ui.common.ChangeRefreshScheduler
import tv.jellybeam.ui.common.canOpenTrailer
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import uniffi.jellybeam_core.Card
import uniffi.jellybeam_core.ChangeEvent
import uniffi.jellybeam_core.CollectionInfo
import uniffi.jellybeam_core.ExtraItem
import uniffi.jellybeam_core.ItemDetail
import uniffi.jellybeam_core.SeasonStats
import uniffi.jellybeam_core.SeriesPlayTarget
import uniffi.jellybeam_core.SeriesSummary
import uniffi.jellybeam_core.SortOrder

private const val SERIES_ITEM_TYPE = "Series"
private const val EPISODE_ITEM_TYPE = "Episode"
private const val BOXSET_ITEM_TYPE = CollectionFormatting.BOXSET_ITEM_TYPE

/** docs/11-detail-ux-spec.md tier 2 item 13: "up to [limit]... from a live GetSimilar call." */
private const val SIMILAR_LIMIT = 16u

/** How long [DetailViewModel.browse] waits before asking again while the mirror is not open. */
private const val BROWSE_NOT_READY_RETRY_MS = 1_000L

/** How long a browse read waits for the mirror to open before it counts as failed (docs/07 §5). */
private const val BROWSE_NOT_READY_BUDGET_MS = 10_000L

/** How long [DetailUiState.toast] stays before [DetailViewModel] clears it (docs/19 §1.4). */
private const val TOAST_DURATION_MS = 2_500L

/** docs/19 §3.3's `MenuUiState.level`: the panel's first level, or the collections / extras second level. */
enum class MenuLevel { FIRST, COLLECTIONS, EXTRAS }

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
    /**
     * The season the newest selection asked for; [selectedSeasonId] stays on the shown one until
     * that read lands, and the shelf takes no focus in between ([episodeShelfEnterable]).
     */
    val requestedSeasonId: String? = null,
    /**
     * [selectedSeasonId]'s episodes as read, virtual ones included (`season_episodes_read`); both
     * land in one write, so the chip and the shelf never disagree. [DetailFormatting.visibleEpisodes] is what is shown.
     */
    val seasonEpisodes: List<Card> = emptyList(),
    /**
     * docs/11 item 11: the core's Play pick ([CoreGateway.seriesSummary]), the source of the primary
     * pill and the resume season. Series-only, published with [seasonStats] in one write.
     */
    val seriesPlay: SeriesPlayTarget? = null,
    /** docs/11 §12 / docs/19: the core's per-season counts for the chip markers and the series-scope menu. */
    val seasonStats: List<SeasonStats> = emptyList(),
    /**
     * True once the first [CoreGateway.seriesSummary] read has settled (success or failure), so "no
     * primary pill" can be told apart from "primary pill not resolved yet" (docs/15 §2 rule 3).
     */
    val summarySettled: Boolean = false,
    /**
     * docs/09 `Settings::show_virtual_episodes`, derived from [SettingsStore.settings]: the season
     * chips show availability markers only while it is on (docs/11 §12), and a flip re-serves the
     * selected season; `null` means not loaded yet.
     */
    val showVirtualEpisodes: Boolean? = null,
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
    /** docs/28 §1: a Watch together row's title, consumed once by [DetailScreen] to open the lobby. */
    val pendingWatchTogether: MenuAction.WatchTogether? = null,
    /** docs/19 §1.3: a remote trailer link, consumed once by [DetailScreen] to open it. */
    val pendingTrailerUrl: String? = null,
    /** docs/19 §1.3: what the Movie Trailer pill plays; derived once, from the settled [itemDetail]. */
    val pillTrailer: PillTrailer? = null,
    /** docs/19 §1.3: whether the menu lists the Trailers and extras row; derived once, from the settled [itemDetail]. */
    val extrasAvailable: Boolean = false,
    /** docs/19 §1.3: the Trailers and extras level's content, fetched on demand. */
    val extras: ExtrasState = ExtrasState.Idle,
    /** True once the mirror dropped this Movie/Episode; [DetailScreen] then pops (docs/07 §5). */
    val itemRemoved: Boolean = false,
    /**
     * Series only: the seasons, summary or selected-season read failed (docs/07 §5), as opposed to
     * returning nothing; [DetailScreen] shows an error with Retry ([DetailViewModel.retryLists]).
     */
    val listError: Boolean = false,
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
    /** docs/19 §3.3's "Play something random" -- injected so tests are deterministic. */
    private val random: Random = Random.Default,
    /** docs/09: the settings owner [DetailUiState.showVirtualEpisodes] is derived from. */
    private val settingsStore: SettingsStore = AppGraph.settingsStore,
    /** docs/19 §1.3: whether an installed app can open a remote trailer link; tests inject it, Android answers via [canOpenTrailer]. */
    private val canOpenTrailer: (String) -> Boolean = { false },
) : ViewModel() {

    private val _state = MutableStateFlow(DetailUiState(card = card))
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    /**
     * The season the viewer last tapped (docs/19: menu scope = the viewer's pick, only while it is
     * still the selection). Plain field: read/write on [viewModelScope]'s Main dispatcher.
     */
    private var viewerPickId: String? = null

    /** Newest [summarySeq] / [seasonsSeq] whose result was published: an older read never lands over it. */
    private var publishedSummarySeq = 0
    private var seasonsSeq = 0
    private var publishedSeasonsSeq = 0

    /** Bumped by every [applySelection]; [publishEpisodes] drops a reply from an older generation. */
    private var selectionGen = 0

    /** The in-flight [loadCollection]; declared above `init` so its first write isn't clobbered. */
    private var collectionJob: Job? = null

    /** docs/19 §1.3: the pill's trailer lookup; a press while it runs is ignored, and leaving the page or starting another play cancels it. */
    private var pillJob: Job? = null

    /** docs/19 §1.3: [canOpenTrailer] answers per link, so each costs one package query per page. Main-thread only. */
    private val openableLinks = HashMap<String, Boolean>()

    private fun canOpen(url: String): Boolean = openableLinks.getOrPut(url) { canOpenTrailer(url) }

    /**
     * True once the first [publishSummary] has landed (success or failure), the per-episode signal
     * the resume pick waits for (docs/15 §0.2/§5).
     */
    private var summaryResolved = false

    /** Bumped by each [CoreGateway.seriesSummary] read, so an older read never lands over a newer one. */
    private var summarySeq = 0

    /** The newest recorded outcome of each series list; [DetailUiState.listError] is "any failed". Main-thread only. */
    private val listOutcomes = ListOutcomes()

    /** Whether the screen is top of its stack ([setActive]); [changeRefreshScheduler] refreshes only while true. */
    private val _active = MutableStateFlow(true)

    fun setActive(active: Boolean) {
        _active.value = active
        if (!active) {
            pillJob?.cancel()
            randomJob?.cancel()
        }
    }

    /**
     * The mirror-relevant refresh (header card; for a Series, the season/episode quiet re-query); a
     * playback stop arrives as its item's Upserted (docs/17 §6). [affectsThisDetail] decides per event, ahead of conflation.
     */
    private val changeRefreshScheduler: ChangeRefreshScheduler<ChangeEvent> = ChangeRefreshScheduler(
        scope = viewModelScope,
        events = gateway.changeEvents(),
        affects = ::affectsThisDetail,
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
            // docs/09: the flag follows its owner, so a page on the stack re-filters its season in place when Settings
            // flips it (no read racing the settings write); a later flip also re-reads the lists on return, since the
            // core's season list filters all-missing seasons.
            viewModelScope.launch {
                settingsStore.settings.filterNotNull().map { it.showVirtualEpisodes }.distinctUntilChanged().collect { shown ->
                    val previous = _state.value.showVirtualEpisodes
                    _state.update { it.copy(showVirtualEpisodes = shown) }
                    if (previous != null) changeRefreshScheduler.markDirty()
                }
            }
            viewModelScope.launch { loadSeasons(card.id) }
            // docs/11 item 11's cross-season fix, independent of loadSeasons/selectSeason.
            viewModelScope.launch { loadSummary(card.id) }
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
        // docs/07 §5: a Movie/Episode the mirror dropped has nothing left to show; a merged Refresh
        // may hide its Removed, so it probes the mirror (a failed probe never closes the page).
        if (card.itemType != SERIES_ITEM_TYPE && card.itemType != BOXSET_ITEM_TYPE) {
            viewModelScope.launch {
                gateway.changeEvents().buffer(Channel.UNLIMITED).first { event ->
                    when (event) {
                        is ChangeEvent.Removed -> card.id in event.ids
                        ChangeEvent.Refresh -> probeCardPresence { gateway.cardById(card.id) } == CardPresence.Gone
                        else -> false
                    }
                }
                _state.update { it.copy(itemRemoved = true) }
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
            val members = browse { gateway.children(card.id, SortOrder.INDEX_NUMBER, 0u, UInt.MAX_VALUE) }
                ?.let { DetailFormatting.dedupeById(it) { member -> member.id } }
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
            val play = CollectionFormatting.resolvePlay(members, previous = _state.value.collectionPlay) { series, replay ->
                runCatchingCancellable { gateway.seriesSummary(series.id, replay) }.getOrNull()
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
        if (local != null && !_state.value.itemDetailLoaded) publishItemDetail(local, settled = false)
        val detail = live.await()
        publishItemDetail(detail ?: _state.value.itemDetail, settled = true)
    }

    /**
     * The only writer of [DetailUiState.itemDetail]. docs/19 §1.3: the pill and the Extras row derive
     * once, from the settled record; the mirror record never carries extras counts or links.
     */
    private fun publishItemDetail(detail: ItemDetail?, settled: Boolean) {
        val pill = if (settled) pillTrailer(card.itemType, detail, ::canOpen) else null
        val available = settled && extrasAvailable(card.itemType, detail, ::canOpen)
        _state.update {
            it.copy(
                itemDetail = detail,
                itemDetailLoaded = it.itemDetailLoaded || settled,
                pillTrailer = pill,
                extrasAvailable = available,
            )
        }
    }

    /** Fails open to empty; [DetailFormatting.dedupeById] guards a server-duplicated title. */
    private suspend fun loadSimilar(itemId: String) {
        val similar = runCatchingCancellable { gateway.getSimilar(itemId, SIMILAR_LIMIT) }.getOrDefault(emptyList())
        _state.update { it.copy(similar = DetailFormatting.dedupeById(similar) { it.id }, similarLoaded = true) }
    }

    /**
     * Fails open to an empty summary so the page always settles; the result goes through
     * [publishSummary] like every other read, so an older one never lands over a newer.
     */
    private suspend fun loadSummary(seriesId: String) {
        val seq = ++summarySeq
        val summary = browse { gateway.seriesSummary(seriesId, replay = false) }
        publishSummary(seq, summary, failed = summary == null)
    }

    /**
     * Records the outcome of read [seq] of [source] and re-derives [DetailUiState.listError]; an
     * outcome older than the newest recorded for [source] is dropped (docs/07 §5).
     */
    private fun recordRead(source: ReadSource, seq: Int, ok: Boolean) {
        listOutcomes.record(source, seq, ok)
        _state.update { it.copy(listError = listOutcomes.anyFailed) }
    }

    /**
     * One mirror browse read for a screen that must tell its outcomes apart (docs/07 §5): rows
     * (possibly none), or `null` when the read failed. A mirror that is not open yet is waited
     * out for [BROWSE_NOT_READY_BUDGET_MS], so the page keeps loading rather than settling on
     * nothing; past the budget it reads as failed, so the error and Retry show.
     */
    private suspend fun <T> browse(read: suspend () -> T): T? = withTimeoutOrNull(BROWSE_NOT_READY_BUDGET_MS) {
        var result: T? = null
        var settled = false
        while (!settled) {
            try {
                result = read()
                settled = true
            } catch (_: BrowseNotReady) {
                delay(BROWSE_NOT_READY_RETRY_MS)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                settled = true
            }
        }
        result
    }

    /**
     * The only writer of [DetailUiState.seriesPlay], [DetailUiState.seasonStats], [DetailUiState.summarySettled]
     * and [summaryResolved]: a [seq] older than the newest published is dropped whole (no settle, no resume pick,
     * no outcome), the winner then re-derives the selection. [failed] records the read as failed.
     */
    private fun publishSummary(seq: Int, summary: SeriesSummary?, failed: Boolean = false) {
        if (seq <= publishedSummarySeq) return
        publishedSummarySeq = seq
        recordRead(ReadSource.SUMMARY, seq, ok = !failed)
        _state.update { it.copy(seriesPlay = summary?.target, seasonStats = summary?.seasons.orEmpty(), summarySettled = true) }
        summaryResolved = true
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
        return when (val change = DetailFormatting.collectionChange(event, card.id, card.itemType == SERIES_ITEM_TYPE, knownIds, card.libraryId)) {
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
        DetailFormatting.visibleEpisodes(state.seasonEpisodes, state.showVirtualEpisodes).mapTo(ids) { it.id }
        state.seriesPlay?.let { ids += it.episode.id }
        state.members.mapTo(ids) { it.id }
        // A Series member's target episode: watched elsewhere, it must move the pill on.
        state.collectionPlay?.episode?.let { ids += it.id }
        return ids
    }

    private suspend fun refreshQuietly(seriesId: String) {
        val seasonsSeqNow = ++seasonsSeq
        // A failed read keeps the current seasons; the scheduler's loop must not see the throw.
        val seasons = runCatchingCancellable { readSeasons(seriesId) }.getOrNull()
        // Seasons and the selection move in one synchronous section: no state has seasons without the selected chip.
        publishSeasons(seasonsSeqNow, seasons, initial = false)
        // docs/11 item 11: re-fetched unconditionally -- a watched-state flip anywhere in the
        // series can move the Play pick and the counts.
        val seq = ++summarySeq
        val summary = try {
            gateway.seriesSummary(seriesId, replay = false)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Keep what is on screen; an unsettled page still has its initial read in flight, which fails open itself.
            return
        }
        publishSummary(seq, summary)
    }

    /** The series' seasons as the chip row shows them ([inIndexOrder]). */
    private suspend fun readSeasons(seriesId: String): List<Card> =
        inIndexOrder(gateway.children(seriesId, SortOrder.INDEX_NUMBER, 0u, UInt.MAX_VALUE))

    /**
     * Deduped (a repeated id can't survive a row's keys) and by index with unnumbered rows last; the
     * core's `IndexNumber` order puts them first (SQLite NULLs sort low), so this is not a duplicate.
     */
    private fun inIndexOrder(cards: List<Card>): List<Card> =
        DetailFormatting.dedupeById(cards) { it.id }.sortedWith(compareBy(nullsLast()) { it.indexNumber })

    private suspend fun loadSeasons(seriesId: String) {
        val seq = ++seasonsSeq
        _state.update { it.copy(isLoadingSeasons = true) }
        // A failed read settles the page (so its focus seed can fall back to the door) and says so.
        val seasons = browse { readSeasons(seriesId) }
        // Chips paint their markers from first paint (docs/11 §12): the store publishes before it releases load().
        runCatchingCancellable { settingsStore.load() }
        publishSeasons(seq, seasons, initial = true)
    }

    /** Retry on the lists' error block: re-reads what a failed series read left empty; the error stays until a read lands. */
    fun retryLists() {
        viewModelScope.launch { loadSeasons(card.id) }
        viewModelScope.launch { loadSummary(card.id) }
    }

    /**
     * The only writer of [DetailUiState.seasons], [DetailUiState.isLoadingSeasons] and
     * [DetailUiState.seasonsSettled]: a null [seasons] (failed read) or one older than the newest
     * published leaves the list alone, and every exit re-derives the selection. Only the [initial]
     * read settles the flags and can record a failure; a failed quiet re-query keeps what is shown.
     */
    private fun publishSeasons(seq: Int, seasons: List<Card>?, initial: Boolean) {
        val take = seasons != null && seq > publishedSeasonsSeq
        if (take) {
            publishedSeasonsSeq = seq
            recordRead(ReadSource.SEASONS, seq, ok = true)
        } else if (seasons == null && initial) {
            recordRead(ReadSource.SEASONS, seq, ok = false)
        }
        _state.update {
            val next = if (take) it.copy(seasons = seasons!!).withoutGoneShownSeason() else it
            if (initial) next.copy(isLoadingSeasons = false, seasonsSettled = true) else next
        }
        syncSelection(resyncEpisodes = false)
    }

    /**
     * Re-derives the selection after seasons or the summary changed (docs/15 §0.2/§5): the first
     * pick waits for both signals and never repeats; a removed pick falls over
     * ([DetailFormatting.reconcileSelectedSeason]). [resyncEpisodes] re-reads the kept selection
     * without a skeleton.
     */
    private fun syncSelection(resyncEpisodes: Boolean) {
        val state = _state.value
        if (state.isLoadingSeasons || !state.seasonsSettled) return
        val current = state.requestedSeasonId ?: state.selectedSeasonId
        val target = DetailFormatting.reconcileSelectedSeason(current, state.seasons, state.seriesPlay, settled = summaryResolved)
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
     * Reads [seasonId] from [CoreGateway.seasonEpisodes] (only the shown season crosses FFI, docs/11
     * §12); the shown chip and shelf stay until [publishEpisodes] swaps both ([publishSeasons]
     * clears a shown season the list dropped). [quiet] re-reads the requested season.
     */
    private fun applySelection(seasonId: String, quiet: Boolean = false) {
        if (!quiet && _state.value.requestedSeasonId == seasonId) return
        val gen = ++selectionGen
        _state.update { it.copy(requestedSeasonId = seasonId) }
        viewModelScope.launch {
            val fetched = browse { inIndexOrder(gateway.seasonEpisodes(seasonId)) }
            // A failed re-read of the shown season keeps it; a failed switch lands the season empty and says so.
            if (fetched == null && quiet && _state.value.selectedSeasonId == seasonId) return@launch
            publishEpisodes(gen, seasonId, fetched ?: emptyList(), failed = fetched == null)
        }
    }

    /**
     * docs/15 §5: a shown season the season list dropped clears with its episodes in the same write,
     * so no state shows episodes whose chip is gone. An empty list is a read gap, not a removal.
     */
    private fun DetailUiState.withoutGoneShownSeason(): DetailUiState {
        val shown = selectedSeasonId ?: return this
        val gone = seasons.isNotEmpty() && seasons.none { it.id == shown }
        return if (gone) copy(selectedSeasonId = null, seasonEpisodes = emptyList()) else this
    }

    /**
     * The only writer of a landed [DetailUiState.selectedSeasonId] and [DetailUiState.seasonEpisodes],
     * in one update; a stale [gen] is dropped whole.
     */
    private fun publishEpisodes(gen: Int, seasonId: String, episodes: List<Card>, failed: Boolean = false) {
        if (gen != selectionGen) return
        _state.update { it.copy(selectedSeasonId = seasonId, seasonEpisodes = episodes) }
        recordRead(ReadSource.EPISODES, gen, ok = !failed)
    }

    // Detail action menu (docs/19-detail-action-menu.md §3.3)

    /** Cancels a pending auto-dismiss so a newer toast isn't cut short by an older timer. */
    private var toastJob: Job? = null

    /**
     * Season-scope pool [MenuAction.PlayRandom] picks from, captured at [openMenu] (menu traps
     * focus); `null` at series scope, which reads the series' episodes when the row runs (docs/19 §3.3).
     */
    private var randomPool: List<Card>? = null

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
        val scopeEpisodes = if (scopeSeason != null) DetailFormatting.visibleEpisodes(state.seasonEpisodes, state.showVirtualEpisodes) else emptyList()
        val seriesCard = if (card.itemType == EPISODE_ITEM_TYPE) DetailFormatting.seriesCardFrom(state.card) else null
        val input = MenuInput(
            card = state.card,
            itemType = card.itemType,
            scopeSeason = scopeSeason,
            scopeEpisodes = scopeEpisodes,
            seasonStats = state.seasonStats,
            seriesTarget = state.seriesPlay?.episode,
            primaryAction = DetailFormatting.resolvePrimaryAction(strings, state.card, state.seriesPlay),
            isFavorite = state.card.isFavorite,
            hasCollections = state.collections.isNotEmpty(),
            isAdministrator = state.isAdministrator,
            seriesCard = seriesCard,
            highlightedSeasonNumber = state.seasons.firstOrNull { it.id == state.selectedSeasonId }?.indexNumber,
            extrasAvailable = state.extrasAvailable,
        )
        val model = buildMenu(strings, input)
        randomPool = scopeSeason?.let { scopeEpisodes }
        _state.update { it.copy(menu = MenuUiState(model = model, level = MenuLevel.FIRST, scopeSeasonNumber = scopeSeason?.indexNumber)) }
        if (state.extrasAvailable) fetchExtrasIfNeeded()
    }

    /** docs/19 §1.3: fetched on the first open with the row present, again only after a failure. */
    private fun fetchExtrasIfNeeded() {
        if (_state.value.extras.let { it is ExtrasState.Idle || it is ExtrasState.Failed }) fetchExtras()
    }

    /** Loads only the endpoints [DetailUiState.itemDetail] counted, both at once; any failure fails the level. */
    private fun fetchExtras() {
        val detail = _state.value.itemDetail ?: return
        _state.update { it.copy(extras = ExtrasState.Loading) }
        viewModelScope.launch {
            val result = runCatchingCancellable {
                coroutineScope {
                    val trailers = async { if (detail.localTrailerCount > 0) gateway.localTrailers(card.id) else emptyList<ExtraItem>() }
                    val features = async { if (detail.specialFeatureCount > 0) gateway.specialFeatures(card.id) else emptyList<ExtraItem>() }
                    buildExtrasList(card.itemType, trailers.await(), detail.remoteTrailers, features.await(), ::canOpen)
                }
            }
            _state.update { it.copy(extras = result.fold(onSuccess = { entries -> ExtrasState.Loaded(entries) }, onFailure = { ExtrasState.Failed })) }
        }
    }

    /** Back/Left/Select on a row, or an action completing -- closes the panel and confirm block. */
    fun closeMenu() = dismissMenu { it.copy(confirm = null) }

    /**
     * docs/19 §1.4: the one way the panel closes, applying [change] in the same write; the menu's own
     * work ([randomJob]) is cancelled here so nothing a closed menu started still plays.
     */
    private fun dismissMenu(change: (DetailUiState) -> DetailUiState = { it }) {
        randomJob?.cancel()
        _state.update { change(it).copy(menu = null) }
    }

    /** "Add to collection" select -- swaps panel content for the collections list (§1.3). */
    fun enterCollections() {
        _state.update { it.copy(menu = it.menu?.copy(level = MenuLevel.COLLECTIONS)) }
    }

    /** Back from collections -- returns to first level (docs/19 §1.3); panel remembers refocus. */
    fun backFromCollections() {
        _state.update { it.copy(menu = it.menu?.copy(level = MenuLevel.FIRST)) }
    }

    /** Back from the extras level -- returns to the first level, where the panel refocuses the Extras row (docs/19 §1.3). */
    fun backFromExtras() {
        _state.update { it.copy(menu = it.menu?.copy(level = MenuLevel.FIRST)) }
    }

    /**
     * docs/19 §1.3: a server item plays from the start through the shared hand-off; a link closes the
     * menu and hands [DetailScreen] its url. Either way the next open starts at the first level.
     */
    fun selectExtra(entry: ExtraEntry) {
        when (entry) {
            is ExtraEntry.Local -> startPlayback(entry.item.id, startFromBeginning = true)
            is ExtraEntry.Remote -> {
                pillJob?.cancel()
                dismissMenu { it.copy(pendingTrailerUrl = entry.url) }
            }
        }
    }

    /**
     * The Movie Trailer pill: a link is handed to [DetailScreen]; a server trailer is looked up fresh
     * and played from the start. A press while the lookup runs is ignored; an empty answer or a failure toasts.
     */
    fun playPillTrailer() {
        when (val pill = _state.value.pillTrailer) {
            null -> Unit
            is PillTrailer.Remote -> dismissMenu { it.copy(pendingTrailerUrl = pill.url) }
            PillTrailer.Local -> {
                if (pillJob?.isActive == true) return
                pillJob = viewModelScope.launch {
                    runCatchingCancellable { gateway.localTrailers(card.id) }.fold(
                        onSuccess = { trailers ->
                            val first = trailers.firstOrNull()
                            if (first != null) handOffPlayback(first.id, startFromBeginning = true) else showToast(strings.get(R.string.trailer_unavailable))
                        },
                        onFailure = { showToast(serverUnreachable()) },
                    )
                }
            }
        }
    }

    /** The primary Play pill starts its own playback outside this view model; a slow trailer lookup must not follow it. */
    fun cancelTrailerLookup() {
        pillJob?.cancel()
    }

    /** [DetailScreen] calls this once it has opened [DetailUiState.pendingTrailerUrl]. */
    fun consumePendingTrailer() {
        _state.update { it.copy(pendingTrailerUrl = null) }
    }

    /**
     * Adds the item, closes the panel, toasts `Added to {Name}` (docs/19 §1.3/§1.4); no-op for
     * a collection already in [DetailUiState.memberOfCollections] (an `ALREADY IN` row).
     */
    fun selectCollection(collection: CollectionInfo) {
        val state = _state.value
        if (collection.id in state.memberOfCollections) return
        val itemId = state.card.id
        dismissMenu()
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
            is MenuAction.PlayRandom -> playRandom()
            is MenuAction.GoToSeries -> dismissMenu { it.copy(pendingSeriesNavigation = action.series) }
            is MenuAction.WatchTogether -> {
                pillJob?.cancel()
                dismissMenu { it.copy(pendingWatchTogether = action) }
            }
            MenuAction.AddToCollection -> enterCollections()
            MenuAction.Extras -> {
                _state.update { it.copy(menu = it.menu?.copy(level = MenuLevel.EXTRAS)) }
                fetchExtrasIfNeeded()
            }
            MenuAction.RefreshMetadata -> refreshMetadata()
        }
    }

    /** The one in-flight [playRandom]; closing the menu or leaving the page cancels it, so nothing plays after. */
    private var randomJob: Job? = null

    /**
     * docs/19 §3.3: a season scope picks from the pool captured at menu open; the series scope reads
     * its episodes only now, so a page open never marshals the whole series. A Select while one runs
     * is ignored; nothing to pick closes the menu.
     */
    private fun playRandom() {
        if (randomJob?.isActive == true) return
        val pool = randomPool
        randomJob = viewModelScope.launch {
            val candidates = pool ?: runCatchingCancellable { gateway.seriesEpisodes(card.id) }.getOrNull().orEmpty()
            val target = pickRandom(candidates, random)
            if (target != null) startPlayback(target.id, startFromBeginning = false) else closeMenu()
        }
    }

    /** Single-item mark: never confirms (docs/19 §1.3); header follows the mirror change event. */
    private fun markSingle(played: Boolean) {
        val itemId = _state.value.card.id
        dismissMenu()
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
        dismissMenu { it.copy(confirm = null) }
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
        dismissMenu()
        viewModelScope.launch {
            val result = runCatchingCancellable { gateway.setFavorite(itemId, favorite) }
            showToast(if (result.isSuccess) strings.get(if (favorite) R.string.detail_toast_added_favorite else R.string.detail_toast_removed_favorite) else serverUnreachable())
        }
    }

    private fun refreshMetadata() {
        val itemId = _state.value.card.id
        dismissMenu()
        viewModelScope.launch {
            val result = runCatchingCancellable { gateway.refreshMetadata(itemId) }
            showToast(if (result.isSuccess) strings.get(R.string.detail_toast_refreshing_metadata) else serverUnreachable())
        }
    }

    /** Closes menu, hands [DetailScreen] a playback target (§1.4: Play navigates, no toast). */
    private fun startPlayback(targetId: String, startFromBeginning: Boolean) {
        pillJob?.cancel()
        handOffPlayback(targetId, startFromBeginning)
    }

    /** [startPlayback] without cancelling [pillJob], for the pill's own success path. */
    private fun handOffPlayback(targetId: String, startFromBeginning: Boolean) {
        dismissMenu { it.copy(pendingPlayback = PendingPlayback(targetId, startFromBeginning)) }
    }

    /** [DetailScreen] calls this once PlaybackActivity has started for [pendingPlayback]. */
    fun consumePendingPlayback() {
        _state.update { it.copy(pendingPlayback = null) }
    }

    /** [DetailScreen] calls this once it has navigated for [pendingSeriesNavigation]. */
    fun consumePendingSeriesNavigation() {
        _state.update { it.copy(pendingSeriesNavigation = null) }
    }

    /** [DetailScreen] calls this once it has navigated for [pendingWatchTogether]. */
    fun consumePendingWatchTogether() {
        _state.update { it.copy(pendingWatchTogether = null) }
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
    /** The application context [canOpenTrailer] resolves handlers against. */
    private val appContext: Context,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(DetailViewModel::class.java))
        return DetailViewModel(gateway, card, strings, canOpenTrailer = { url -> canOpenTrailer(appContext, url) }) as T
    }
}

/** The series lists whose read outcome [DetailUiState.listError] follows (docs/07 §5). */
internal enum class ReadSource { SEASONS, SUMMARY, EPISODES }

/**
 * The newest recorded outcome of each [ReadSource], ordered by the read's own sequence: a result
 * older than the newest recorded for its list is dropped, so a stale failure never raises the
 * error over a newer success (docs/07 §5).
 */
internal class ListOutcomes {
    private val newestSeq = mutableMapOf<ReadSource, Int>()
    private val failed = mutableSetOf<ReadSource>()

    val anyFailed: Boolean get() = failed.isNotEmpty()

    fun record(source: ReadSource, seq: Int, ok: Boolean) {
        if (seq < (newestSeq[source] ?: Int.MIN_VALUE)) return
        newestSeq[source] = seq
        if (ok) failed -= source else failed += source
    }
}

/** What a mirror read of one card says about its item (docs/07 §5). */
internal enum class CardPresence { Gone, Present, Failed }

/** [read] as a [CardPresence]: `null` is Gone, any non-cancellation failure is Failed, never Gone. */
internal suspend fun probeCardPresence(read: suspend () -> Card?): CardPresence =
    try {
        if (read() == null) CardPresence.Gone else CardPresence.Present
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        CardPresence.Failed
    }
