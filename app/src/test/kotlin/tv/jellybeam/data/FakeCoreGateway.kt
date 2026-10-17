package tv.jellybeam.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import uniffi.jellybeam_core.AccountEpochs
import uniffi.jellybeam_core.AccountIdentity
import uniffi.jellybeam_core.AccountInfo
import uniffi.jellybeam_core.AssOverlay
import uniffi.jellybeam_core.Card
import uniffi.jellybeam_core.ChangeEvent
import uniffi.jellybeam_core.ClassicHome
import uniffi.jellybeam_core.CollectionInfo
import uniffi.jellybeam_core.CoreException
import uniffi.jellybeam_core.DeviceCaps
import uniffi.jellybeam_core.DiscoveredServer
import uniffi.jellybeam_core.EpisodeNeighbors
import uniffi.jellybeam_core.FailedTrackFfi
import uniffi.jellybeam_core.GlideDirection
import uniffi.jellybeam_core.GridCounts
import uniffi.jellybeam_core.GridFilters
import uniffi.jellybeam_core.GridGroup
import uniffi.jellybeam_core.GridSort
import uniffi.jellybeam_core.GridSortField
import uniffi.jellybeam_core.HomeLayout
import uniffi.jellybeam_core.HomeSnapshot
import uniffi.jellybeam_core.ImageKind
import uniffi.jellybeam_core.ItemDetail
import uniffi.jellybeam_core.LanguageSettings
import uniffi.jellybeam_core.LibraryGridPrefs
import uniffi.jellybeam_core.LiveSort
import uniffi.jellybeam_core.MediaSegment
import uniffi.jellybeam_core.MediaSegmentKind
import uniffi.jellybeam_core.OsdDetailSetting
import uniffi.jellybeam_core.PersonPage
import uniffi.jellybeam_core.PlaybackOsdDetail
import uniffi.jellybeam_core.PlaybackPlan
import uniffi.jellybeam_core.PlaybackRequest
import uniffi.jellybeam_core.PlaybackQuality
import uniffi.jellybeam_core.QuickConnectSession
import uniffi.jellybeam_core.SeekPreviewSize
import uniffi.jellybeam_core.SeerrAuthMethod
import uniffi.jellybeam_core.SeerrBrowseFilters
import uniffi.jellybeam_core.SeerrBrowseKind
import uniffi.jellybeam_core.SeerrCard
import uniffi.jellybeam_core.SeerrGenre
import uniffi.jellybeam_core.SeerrHome
import uniffi.jellybeam_core.SeerrMediaType
import uniffi.jellybeam_core.SeerrMovieDetail
import uniffi.jellybeam_core.SeerrMyRequest
import uniffi.jellybeam_core.SeerrPage
import uniffi.jellybeam_core.SeerrPersonCredits
import uniffi.jellybeam_core.SeerrActiveRequest
import uniffi.jellybeam_core.SeerrRequestDetail
import uniffi.jellybeam_core.SeerrRequestInput
import uniffi.jellybeam_core.SeerrRequestStatus
import uniffi.jellybeam_core.SeerrRequestOptions
import uniffi.jellybeam_core.SeerrStatus
import uniffi.jellybeam_core.SeerrTvDetail
import uniffi.jellybeam_core.SegmentAction
import uniffi.jellybeam_core.ServerDetails
import uniffi.jellybeam_core.ServerInfoSnapshot
import uniffi.jellybeam_core.Settings
import uniffi.jellybeam_core.SortOrder
import uniffi.jellybeam_core.StatusFilter
import uniffi.jellybeam_core.StillWatchingDecision
import uniffi.jellybeam_core.StillWatchingMode
import uniffi.jellybeam_core.StillWatchingSettings
import uniffi.jellybeam_core.SubtitleActionFfi
import uniffi.jellybeam_core.SubtitleModeSetting
import uniffi.jellybeam_core.SubtitleColorPreset
import uniffi.jellybeam_core.ResumeArt
import uniffi.jellybeam_core.SubtitlePositionPreset
import uniffi.jellybeam_core.SyncStatus
import uniffi.jellybeam_core.TitleTmdbRef
import uniffi.jellybeam_core.TrackDecisionFfi
import uniffi.jellybeam_core.TrackInfo
import uniffi.jellybeam_core.TrackKindFfi
import uniffi.jellybeam_core.TrackMemoryFfi
import uniffi.jellybeam_core.TrickplayMetaFfi
import uniffi.jellybeam_core.TrickplayTileFfi
import uniffi.jellybeam_core.ViewSnapshot
import uniffi.jellybeam_core.WatchedFilter

/** Matches `Settings::default()`; every [FakeCoreGateway] starts here unless overridden. */
fun defaultTestSettings(): Settings = Settings(
    nextUpCutoffDays = null,
    nextUpRewatching = false,
    hiddenLibraryIds = emptyList(),
    hideWatchedInLatest = false,
    startupScreenViewId = null,
    homeShelfSize = 20u,
    homeResumeArt = ResumeArt.EPISODE,
    skipBackSecs = 10u,
    skipForwardSecs = 10u,
    language = LanguageSettings(audio = null, subtitle = null, subtitleMode = SubtitleModeSetting.DEFAULT),
    autoplayEnabled = true,
    autoplayDelaySecs = 10u,
    subtitleScale = 1.0f,
    subtitlePosition = SubtitlePositionPreset.DEFAULT,
    subtitleBold = false,
    subtitleBackgroundOpacity = 0.0f,
    subtitleColor = SubtitleColorPreset.WHITE,
    subtitleUseSystemStyle = false,
    subtitleFullAssStyling = false,
    skipIntro = SegmentAction.ASK,
    skipOutro = SegmentAction.ASK,
    skipRecap = SegmentAction.ASK,
    skipPreview = SegmentAction.ASK,
    skipCommercial = SegmentAction.AUTO_SKIP,
    showClock = true,
    tolerateMislabeledLevels = true,
    preferFfmpegTrueHd = false,
    preferFfmpegDts = false,
    preferFfmpegDtsHd = false,
    showVirtualEpisodes = false,
    preloadOnFocus = true,
    miniPlayerEnabled = false,
    osdDetail = OsdDetailSetting.FULL,
    seekPreviewSize = SeekPreviewSize.MEDIUM,
    stillWatching = StillWatchingSettings(
        mode = StillWatchingMode.AFTER_EPISODES,
        episodes = 3u,
        hours = 3f,
        timeoutSecs = 120u,
        resetOnInput = true,
    ),
    // docs/18 §1 / CLAUDE.md: Direct Play is the default, never transcodes.
    playbackQuality = PlaybackQuality.DirectPlay,
    // docs/21 §6: mirrors Settings::default() -- logging off, crash capture on.
    diagnosticLoggingEnabled = false,
    crashReportsEnabled = true,
    homeLayout = HomeLayout.CLASSIC,
)

/**
 * [CoreGateway] test double: [children] keyed by `parentId`, everything else empty/no-op, calls
 * recorded in `*Calls` lists. [preparePlaybackResultsByItemId] takes priority over
 * [preparePlaybackResult] for `playNext()` tests. [nextEpisodeTriggerRemainingSecs]/
 * [nextEpisodeCountdownTotal] reimplement `playback_policy`'s formulas in Kotlin; keep in sync
 * with `playback-policy/src/segments.rs`.
 */
/** A fake call with no configured result stands in for a failed server call: an [Exception]
 * like the real gateway's CoreException, so fail-open code treats it as recoverable.
 */
class UnconfiguredFakeCall(message: String) : IllegalStateException(message)

class FakeCoreGateway(
    private val childrenByParent: Map<String, List<Card>> = emptyMap(),
    /** [libraryGrid] results keyed by view id, full sorted list (this fake applies only
     * `offset`/`limit`); mutable so a test can swap it mid-flight (docs/16 §4.6). */
    var libraryGridByView: Map<String, List<Card>> = emptyMap(),
    /** When non-null, [libraryGrid] awaits this after snapshotting, letting a test hold a query
     * open while a newer one runs ahead. */
    var libraryGridGate: CompletableDeferred<Unit>? = null,
    /** docs/16 §4.6: when `true`, library-grid reads return `null` (simulated failed query). */
    var libraryGridFails: Boolean = false,
    /** [libraryGenres] results keyed by view id; unmapped ids fall back to empty. */
    private val libraryGenresByView: Map<String, List<String>> = emptyMap(),
    /** [libraryGridGroups] results keyed by view id; unmapped ids fall back to empty. */
    private val libraryGridGroupsByView: Map<String, List<GridGroup>> = emptyMap(),
    /** [libraryGridCounts] override; unmapped falls back to `filtered = total =
     * libraryGridByView[viewId].size`. */
    private val libraryGridCountsByView: Map<String, GridCounts> = emptyMap(),
    /** [getLibraryGridPrefs]/[setLibraryGridPrefs]'s live store; unmapped ids read as
     * [defaultLibraryGridPrefs]. */
    private val libraryGridPrefsByView: MutableMap<String, LibraryGridPrefs> = mutableMapOf(),
    /** [liveChildren] results keyed by parent id; unmapped ids fall back to empty. */
    private val liveChildrenByParent: Map<String, List<Card>> = emptyMap(),
    /** When non-null, every [liveChildren] call throws this instead of returning a page. */
    var liveChildrenError: Throwable? = null,
    private val viewsList: List<ViewSnapshot> = emptyList(),
    /** When non-null, [views] throws this instead of returning [viewsList]. */
    var viewsError: Throwable? = null,
    /** [discoverServers] result; defaults to none found. */
    private val discoveredServers: List<DiscoveredServer> = emptyList(),
    /** [search] results keyed by exact query string; unmapped queries fall back to empty. */
    private val searchResultsByQuery: Map<String, List<Card>> = emptyMap(),
    private val preparePlaybackResult: Result<PlaybackPlan>? = null,
    private val preparePlaybackResultsByItemId: Map<String, Result<PlaybackPlan>> = emptyMap(),
    /** [prepareTranscodeFallback] result; an unconfigured call throws [UnconfiguredFakeCall]. */
    var prepareTranscodeFallbackResult: Result<PlaybackPlan>? = null,
    private val nextEpisodeByItemId: Map<String, Card?> = emptyMap(),
    /** When non-null, [nextEpisodeAfter] throws this instead of looking up [nextEpisodeByItemId]. */
    var nextEpisodeError: Throwable? = null,
    private val seriesEpisodesBySeriesId: Map<String, List<Card>> = emptyMap(),
    /** [episodeNeighbors] previous results keyed by item id; unmapped ids fall back to `null`. */
    private val previousEpisodeByItemId: Map<String, Card?> = emptyMap(),
    /** [serverDisplayName] result; defaults to `null` ("not signed in"/no parseable host). */
    var serverDisplayNameValue: String? = null,
    var isAdministratorResult: Boolean = false,
    var collectionsResult: List<CollectionInfo> = emptyList(),
    /** [collectionIdsContaining] results keyed by item id; unmapped ids fall back to empty. */
    var collectionIdsByItemId: MutableMap<String, List<String>> = mutableMapOf(),
    /** [getItemDetail] results keyed by item id; unmapped throws [UnconfiguredFakeCall] (no
     * harmless default). */
    private val itemDetailResultsByItemId: Map<String, Result<ItemDetail>> = emptyMap(),
    /** [getSimilar] results keyed by item id; unmapped ids fall back to empty. */
    private val similarResultsByItemId: Map<String, List<Card>> = emptyMap(),
    /** [getMediaSegments] results keyed by item id; unmapped ids fall back to empty. */
    private val mediaSegmentsByItemId: Map<String, List<MediaSegment>> = emptyMap(),
    /** [listAccounts] result; defaults to no known accounts. */
    private val accountsList: List<AccountInfo> = emptyList(),
    /** [activeAccountIndex] result; mutable so a test can simulate [switchSession] moving it. */
    var activeAccountIndexValue: UInt? = null,
    /** Reported server version for [serverVersion]/[serverAtLeast]; `null` = unknown (gate
     * fails closed). */
    var serverVersionValue: String? = null,
    /** [serverInfoSnapshot] result; defaults to "not signed in" (every field null/false/empty). */
    var serverInfoSnapshotValue: ServerInfoSnapshot = emptyServerInfoSnapshot(),
    /** Runs after each [serverInfoSnapshot] call, before the value is returned -- lets a test
     * mutate [serverInfoSnapshotValue] between a refresh's two reads (docs/13 About). */
    var onServerInfoSnapshotCall: (() -> Unit)? = null,
    /** [fetchServerDetails] result; `null` throws [CoreException.NotSignedIn], matching
     * [switchSession]'s unmapped-throws style. */
    var serverDetailsValue: ServerDetails? = null,
    /** [switchSession] results keyed by index; unmapped throws [UnconfiguredFakeCall]. */
    private val switchSessionResultsByIndex: Map<UInt, Result<AccountInfo>> = emptyMap(),
    /** Seed value [getSettings] returns until a [setSettings] call replaces it. */
    settings: Settings = defaultTestSettings(),
    /** The decision every [resolveTracks] returns; defaults to [leaveEverythingAloneDecision]. */
    var resolveTracksResult: TrackDecisionFfi = leaveEverythingAloneDecision(),
    /** The URL every [trickplayTileUrl] call returns; `null` simulates "not signed in". */
    var trickplayTileUrlResult: String? = "https://fake.test/trickplay-tile.jpg",
    /** The trickplay manifest every [getPlaybackOsdDetail] answer carries; `null` (default) is "none". */
    var osdTrickplay: TrickplayMetaFfi? = null,
    /** Sidecar text by index for [fetchExternalSubtitle]; a missing index fails. */
    var externalSubtitleText: Map<Int, String> = emptyMap(),
    // -- Seerr Discover (docs/14-seerr-discover.md) --------------------------
    /** [seerrStatus] result; defaults to "not configured" (`SeerrStatus.configured = false`). */
    var seerrStatusValue: SeerrStatus = notConfiguredSeerrStatus(),
    /** [seerrConnect] result; an unconfigured call throws [UnconfiguredFakeCall]. */
    var seerrConnectResult: Result<SeerrStatus>? = null,
    /** [seerrHome] result; defaults to an empty successful home. */
    var seerrHomeResult: Result<SeerrHome> = Result.success(SeerrHome(rows = emptyList())),
    /** [seerrBrowse] result; one fake value reused regardless of kind/page/filters. */
    var seerrBrowseResult: Result<SeerrPage> = Result.success(emptySeerrPage()),
    /** [seerrGenres] result; defaults to an empty successful list. */
    var seerrGenresResult: Result<List<SeerrGenre>> = Result.success(emptyList()),
    /** [seerrSearch] result; defaults to an empty successful page. */
    var seerrSearchResult: Result<SeerrPage> = Result.success(emptySeerrPage()),
    /** [seerrMovie] results keyed by tmdb id; an unmapped id throws [UnconfiguredFakeCall]. */
    private val seerrMovieResultsByTmdbId: Map<Long, Result<SeerrMovieDetail>> = emptyMap(),
    /** [seerrTv] results keyed by tmdb id; an unmapped id throws [UnconfiguredFakeCall]. */
    private val seerrTvResultsByTmdbId: Map<Long, Result<SeerrTvDetail>> = emptyMap(),
    /** [seerrPerson] results keyed by person id; an unmapped id throws [UnconfiguredFakeCall]. */
    private val seerrPersonResultsByPersonId: Map<Long, Result<SeerrPersonCredits>> = emptyMap(),
    /** [getPersonPage] results keyed by Jellyfin person id; an unmapped id throws [UnconfiguredFakeCall]. */
    private val personPageResultsById: Map<String, Result<PersonPage>> = emptyMap(),
    /** [personDiscoverCredits] results keyed by TMDB person id; an unmapped id throws [UnconfiguredFakeCall]. */
    private val personDiscoverResultsByTmdbId: Map<Long, Result<List<SeerrCard>>> = emptyMap(),
    /** [seerrRequestOptions] result; defaults to an empty server list (plain-Request-button
     * case). */
    var seerrRequestOptionsResult: Result<SeerrRequestOptions> = Result.success(SeerrRequestOptions(servers = emptyList())),
    /** [seerrSubmitRequestDetail] result; `null` answers the title's configured detail with a pending request. */
    var seerrSubmitRequestResult: Result<SeerrRequestDetail>? = null,
    /** [seerrCancelRequestDetail] result; `null` answers the title's configured detail with no request. */
    var seerrCancelRequestResult: Result<SeerrRequestDetail>? = null,
    /** [seerrMyRequests] result; defaults to an empty successful list. */
    var seerrMyRequestsResult: Result<List<SeerrMyRequest>> = Result.success(emptyList()),
    /** Queue of decisions successive [noteEpisodeFinished] calls return; empty falls back to
     * [StillWatchingDecision.COUNTDOWN]. */
    val noteEpisodeFinishedDecisions: ArrayDeque<StillWatchingDecision> = ArrayDeque(),
    /** When set, [signIn] throws this instead of [UnconfiguredFakeCall] (docs/13 "sign-in"). */
    var signInError: CoreException? = null,
) : CoreGateway {

    /** The current settings record; starts at the constructor's [settings], updated by
     * [setSettings]. */
    var settings: Settings = settings
        private set

    private val _setSettingsCalls = mutableListOf<Settings>()
    val setSettingsCalls: List<Settings> get() = _setSettingsCalls

    data class ChildrenCall(val parentId: String, val sort: SortOrder, val offset: UInt, val limit: UInt)

    private val _childrenCalls = mutableListOf<ChildrenCall>()
    val childrenCalls: List<ChildrenCall> get() = _childrenCalls

    data class LibraryGridCall(val viewId: String, val sort: GridSort, val filters: GridFilters, val offset: UInt, val limit: UInt)

    private val _libraryGridCalls = mutableListOf<LibraryGridCall>()
    val libraryGridCalls: List<LibraryGridCall> get() = _libraryGridCalls

    data class LibraryGridCountsCall(val viewId: String, val filters: GridFilters)

    private val _libraryGridCountsCalls = mutableListOf<LibraryGridCountsCall>()
    val libraryGridCountsCalls: List<LibraryGridCountsCall> get() = _libraryGridCountsCalls

    data class LibraryGridGroupsCall(val viewId: String, val sort: GridSort, val filters: GridFilters)

    private val _libraryGridGroupsCalls = mutableListOf<LibraryGridGroupsCall>()
    val libraryGridGroupsCalls: List<LibraryGridGroupsCall> get() = _libraryGridGroupsCalls

    private val _libraryGenresCalls = mutableListOf<String>()
    val libraryGenresCalls: List<String> get() = _libraryGenresCalls

    data class SetLibraryGridPrefsCall(val viewId: String, val prefs: LibraryGridPrefs)

    private val _setLibraryGridPrefsCalls = mutableListOf<SetLibraryGridPrefsCall>()
    val setLibraryGridPrefsCalls: List<SetLibraryGridPrefsCall> get() = _setLibraryGridPrefsCalls

    data class LiveChildrenCall(val parentId: String, val startIndex: UInt, val limit: UInt, val sort: LiveSort)

    private val _liveChildrenCalls = mutableListOf<LiveChildrenCall>()
    val liveChildrenCalls: List<LiveChildrenCall> get() = _liveChildrenCalls

    data class SearchCall(val query: String, val limit: UInt)

    private val _searchCalls = mutableListOf<SearchCall>()
    val searchCalls: List<SearchCall> get() = _searchCalls

    private val _reportPositionCalls = mutableListOf<Long>()
    val reportPositionCalls: List<Long> get() = _reportPositionCalls

    private val _reportPausedCalls = mutableListOf<Boolean>()
    val reportPausedCalls: List<Boolean> get() = _reportPausedCalls

    /** `playSessionId` of every [reportPaused] call, index-aligned with [reportPausedCalls]. */
    private val _reportPausedSessionIds = mutableListOf<String>()
    val reportPausedSessionIds: List<String> get() = _reportPausedSessionIds

    private val _stopPlaybackCalls = mutableListOf<Long>()
    val stopPlaybackCalls: List<Long> get() = _stopPlaybackCalls

    /** `playSessionId` argument of every [stopPlayback] call, index-aligned with
     * [stopPlaybackCalls]. */
    private val _stopPlaybackSessionIds = mutableListOf<String>()
    val stopPlaybackSessionIds: List<String> get() = _stopPlaybackSessionIds

    var abandonPlaybackCallCount: Int = 0
        private set

    /** `playSessionId` of every [abandonPlayback] call. */
    private val _abandonPlaybackSessionIds = mutableListOf<String>()
    val abandonPlaybackSessionIds: List<String> get() = _abandonPlaybackSessionIds

    private val _setDeviceCapsCalls = mutableListOf<DeviceCaps>()
    val setDeviceCapsCalls: List<DeviceCaps> get() = _setDeviceCapsCalls

    private val _preparePlaybackCalls = mutableListOf<String>()
    val preparePlaybackCalls: List<String> get() = _preparePlaybackCalls

    /** [startFromBeginning] of every [preparePlayback] call, index-aligned with
     * [preparePlaybackCalls]. */
    private val _preparePlaybackStartFromBeginningCalls = mutableListOf<Boolean>()
    val preparePlaybackStartFromBeginningCalls: List<Boolean> get() = _preparePlaybackStartFromBeginningCalls

    data class PrepareTranscodeFallbackCall(
        val itemId: String,
        val positionTicks: Long,
        val reason: String,
        val playSessionId: String,
        val failed: FailedTrackFfi?,
        val subtitleStreamIndex: Int? = null,
    )

    private val _prepareTranscodeFallbackCalls = mutableListOf<PrepareTranscodeFallbackCall>()
    val prepareTranscodeFallbackCalls: List<PrepareTranscodeFallbackCall> get() = _prepareTranscodeFallbackCalls

    /** When non-null, [prepareTranscodeFallback] awaits this after snapshotting, holding one
     * fallback negotiation open while a second session runs ahead (docs/18 §3). */
    var prepareTranscodeFallbackGate: CompletableDeferred<Unit>? = null

    /** When non-null, [stopPlayback] awaits this before it reaches the core (is recorded). */
    var stopPlaybackGate: CompletableDeferred<Unit>? = null

    /** [stopPlayback] calls begun, held at [stopPlaybackGate] or not. */
    var stopPlaybackEntered: Int = 0
        private set

    /** The n-th (zero-based) [prepareTranscodeFallback] call's result, over [prepareTranscodeFallbackResult]. */
    val prepareTranscodeFallbackResultsByCall = mutableMapOf<Int, Result<PlaybackPlan>>()

    /** Holds the n-th (zero-based) [prepareTranscodeFallback] call until completed. */
    val prepareTranscodeFallbackGatesByCall = mutableMapOf<Int, CompletableDeferred<Unit>>()

    private val _preloadPlaybackCalls = mutableListOf<String>()
    val preloadPlaybackCalls: List<String> get() = _preloadPlaybackCalls

    private val _nextEpisodeAfterCalls = mutableListOf<String>()
    val nextEpisodeAfterCalls: List<String> get() = _nextEpisodeAfterCalls

    private val _seriesEpisodesCalls = mutableListOf<String>()
    val seriesEpisodesCalls: List<String> get() = _seriesEpisodesCalls

    private val _episodeNeighborsItemIds = mutableListOf<String>()
    val episodeNeighborsItemIds: List<String> get() = _episodeNeighborsItemIds

    var serverDisplayNameCallCount: Int = 0
        private set

    private val _getItemDetailCalls = mutableListOf<String>()
    val getItemDetailCalls: List<String> get() = _getItemDetailCalls

    private val _getPlaybackOsdDetailCalls = mutableListOf<String>()
    val getPlaybackOsdDetailCalls: List<String> get() = _getPlaybackOsdDetailCalls

    /** The media source id of every [getPlaybackOsdDetail] call, in order. */
    private val _getPlaybackOsdDetailMediaSources = mutableListOf<String>()
    val getPlaybackOsdDetailMediaSources: List<String> get() = _getPlaybackOsdDetailMediaSources

    data class GetSimilarCall(val itemId: String, val limit: UInt)

    private val _getSimilarCalls = mutableListOf<GetSimilarCall>()
    val getSimilarCalls: List<GetSimilarCall> get() = _getSimilarCalls

    private val _getMediaSegmentsCalls = mutableListOf<String>()
    val getMediaSegmentsCalls: List<String> get() = _getMediaSegmentsCalls

    data class ResolveTracksCall(val seriesId: String?, val tracks: List<TrackInfo>)

    private val _resolveTracksCalls = mutableListOf<ResolveTracksCall>()
    val resolveTracksCalls: List<ResolveTracksCall> get() = _resolveTracksCalls

    /** When non-null, [resolveTracks] awaits this before returning [resolveTracksResult], holding
     * one round trip open while a session change or manual pick runs ahead (docs/18 §3.1). */
    var resolveTracksGate: CompletableDeferred<Unit>? = null

    data class RememberTrackMemoryCall(val seriesId: String, val kind: TrackKindFfi, val memory: TrackMemoryFfi)

    private val _rememberTrackMemoryCalls = mutableListOf<RememberTrackMemoryCall>()
    val rememberTrackMemoryCalls: List<RememberTrackMemoryCall> get() = _rememberTrackMemoryCalls

    data class TrickplayTileUrlCall(val itemId: String, val width: UInt, val imageIndex: UInt)

    private val _trickplayTileUrlCalls = mutableListOf<TrickplayTileUrlCall>()
    val trickplayTileUrlCalls: List<TrickplayTileUrlCall> get() = _trickplayTileUrlCalls

    // -- Seerr Discover call logs ---------------------------------------------

    data class SeerrConnectCall(val url: String, val method: SeerrAuthMethod, val identity: String, val secret: String)

    private val _seerrConnectCalls = mutableListOf<SeerrConnectCall>()
    val seerrConnectCalls: List<SeerrConnectCall> get() = _seerrConnectCalls

    var seerrDisconnectCallCount: Int = 0
        private set

    data class SeerrBrowseCall(val kind: SeerrBrowseKind, val page: Int, val filters: SeerrBrowseFilters)

    private val _seerrBrowseCalls = mutableListOf<SeerrBrowseCall>()
    val seerrBrowseCalls: List<SeerrBrowseCall> get() = _seerrBrowseCalls

    data class SeerrSearchCall(val query: String, val page: Int)

    private val _seerrSearchCalls = mutableListOf<SeerrSearchCall>()
    val seerrSearchCalls: List<SeerrSearchCall> get() = _seerrSearchCalls

    private val _seerrMovieCalls = mutableListOf<Long>()
    val seerrMovieCalls: List<Long> get() = _seerrMovieCalls

    private val _seerrTvCalls = mutableListOf<Long>()
    val seerrTvCalls: List<Long> get() = _seerrTvCalls

    private val _seerrPersonCalls = mutableListOf<Long>()
    val seerrPersonCalls: List<Long> get() = _seerrPersonCalls

    private val _getPersonPageCalls = mutableListOf<String>()
    val getPersonPageCalls: List<String> get() = _getPersonPageCalls

    data class PersonDiscoverCall(val personId: String, val tmdbPersonId: Long, val inLibrary: List<TitleTmdbRef>?)

    private val _personDiscoverCalls = mutableListOf<PersonDiscoverCall>()
    val personDiscoverCalls: List<PersonDiscoverCall> get() = _personDiscoverCalls

    data class SeerrRequestOptionsCall(val mediaType: SeerrMediaType, val is4k: Boolean)

    private val _seerrRequestOptionsCalls = mutableListOf<SeerrRequestOptionsCall>()
    val seerrRequestOptionsCalls: List<SeerrRequestOptionsCall> get() = _seerrRequestOptionsCalls

    private val _seerrSubmitRequestCalls = mutableListOf<SeerrRequestInput>()
    val seerrSubmitRequestCalls: List<SeerrRequestInput> get() = _seerrSubmitRequestCalls

    /** Held open at the top of [seerrMovie]/[seerrTv]/[seerrRequestOptions]/[seerrSubmitRequestDetail]/[seerrCancelRequestDetail] when set. */
    var seerrMovieGate: CompletableDeferred<Unit>? = null
    var seerrTvGate: CompletableDeferred<Unit>? = null
    var seerrRequestOptionsGate: CompletableDeferred<Unit>? = null
    var seerrSubmitRequestGate: CompletableDeferred<Unit>? = null
    var seerrCancelRequestGate: CompletableDeferred<Unit>? = null

    var seerrHomeGate: CompletableDeferred<Unit>? = null
    var seerrMyRequestsGate: CompletableDeferred<Unit>? = null
    var seerrHomeCallCount: Int = 0
    var seerrMyRequestsCallCount: Int = 0

    /** Bumped like the real gateway: after each successful Seerr request, cancel, connect and disconnect. */
    override val seerrRevision = MutableStateFlow(0L)

    private val _seerrCancelRequestCalls = mutableListOf<Long>()
    val seerrCancelRequestCalls: List<Long> get() = _seerrCancelRequestCalls

    override suspend fun restoreSession(): AccountInfo? = null

    /** When non-null, [signIn] awaits this (after counting) before it answers. */
    var signInGate: CompletableDeferred<Unit>? = null

    /** [signIn] calls begun, held at [signInGate] or not. */
    var signInCallCount: Int = 0
        private set

    override suspend fun signIn(serverUrl: String, username: String, password: String): AccountInfo {
        signInCallCount++
        signInGate?.await()
        throw signInError ?: UnconfiguredFakeCall("FakeCoreGateway.signIn is not used by Library/Detail ViewModel tests")
    }

    /** When non-null, [reauthorizeSession] awaits this (after counting) before it answers. */
    var reauthorizeSessionGate: CompletableDeferred<Unit>? = null

    /** [reauthorizeSession] calls begun, held at [reauthorizeSessionGate] or not. */
    var reauthorizeSessionCallCount: Int = 0
        private set

    override suspend fun reauthorizeSession(index: UInt, username: String, password: String): AccountInfo {
        reauthorizeSessionCallCount++
        reauthorizeSessionGate?.await()
        throw UnconfiguredFakeCall("FakeCoreGateway.reauthorizeSession is not configured")
    }

    /** [initiateQuickConnect] answer; `null` throws [UnconfiguredFakeCall]. */
    var initiateQuickConnectResult: QuickConnectSession? = null

    /** [pollQuickConnect] answer after the gate; `null` throws [UnconfiguredFakeCall]. */
    var pollQuickConnectResult: Boolean? = null

    override suspend fun initiateQuickConnect(serverUrl: String): QuickConnectSession =
        initiateQuickConnectResult ?: throw UnconfiguredFakeCall("FakeCoreGateway.initiateQuickConnect is not configured")

    /** When non-null, [pollQuickConnect] awaits this (after counting) before it answers. */
    var pollQuickConnectGate: CompletableDeferred<Unit>? = null

    /** [pollQuickConnect] calls begun, held at [pollQuickConnectGate] or not. */
    var pollQuickConnectCallCount: Int = 0
        private set

    override suspend fun pollQuickConnect(serverUrl: String, secret: String): Boolean {
        pollQuickConnectCallCount++
        pollQuickConnectGate?.await()
        return pollQuickConnectResult ?: throw UnconfiguredFakeCall("FakeCoreGateway.pollQuickConnect is not configured")
    }

    override suspend fun completeQuickConnect(serverUrl: String, secret: String): AccountInfo =
        throw UnconfiguredFakeCall("FakeCoreGateway.completeQuickConnect is not configured")

    override suspend fun completeQuickConnectReauthorization(index: UInt, secret: String): AccountInfo =
        throw UnconfiguredFakeCall("FakeCoreGateway.completeQuickConnectReauthorization is not configured")

    var discoverServersCalls: Int = 0
        private set

    /** Per-call replies for [discoverServers], consumed first to last; empty falls back to [discoveredServers]. */
    val discoverServersGates = ArrayDeque<CompletableDeferred<List<DiscoveredServer>>>()

    override suspend fun discoverServers(): List<DiscoveredServer> {
        discoverServersCalls++
        val gate = discoverServersGates.removeFirstOrNull() ?: return discoveredServers
        return gate.await()
    }

    override suspend fun listAccounts(): List<AccountInfo> = accountsList

    override suspend fun activeAccountIndex(): UInt? = activeAccountIndexValue

    override suspend fun serverVersion(): String? = serverVersionValue

    override suspend fun serverAtLeast(major: UInt, minor: UInt): Boolean {
        val parts = serverVersionValue?.split('.') ?: return false
        val maj = parts.getOrNull(0)?.toUIntOrNull() ?: return false
        val min = parts.getOrNull(1)?.takeWhile { it.isDigit() }?.toUIntOrNull() ?: return false
        return maj > major || (maj == major && min >= minor)
    }

    private val _switchSessionCalls = mutableListOf<UInt>()
    val switchSessionCalls: List<UInt> get() = _switchSessionCalls

    override suspend fun switchSession(index: UInt): AccountInfo {
        _switchSessionCalls.add(index)
        val result = switchSessionResultsByIndex[index]
            ?: throw UnconfiguredFakeCall("FakeCoreGateway.switchSession($index): no result configured")
        return result.getOrElse { throw it }
    }

    override suspend fun removeSession(index: UInt): Boolean = false

    override suspend fun openMirror() = Unit

    override suspend fun setDeviceCaps(caps: DeviceCaps) {
        _setDeviceCapsCalls.add(caps)
    }

    override suspend fun homeSnapshot(layout: HomeLayout): HomeSnapshot =
        HomeSnapshot.Classic(ClassicHome(hero = null, shelves = emptyList()))

    override suspend fun isSyncing(): Boolean = false

    override suspend fun syncStatus(): SyncStatus = SyncStatus.Idle

    var serverInfoSnapshotCalls: Int = 0
        private set

    override suspend fun serverInfoSnapshot(): ServerInfoSnapshot {
        serverInfoSnapshotCalls++
        onServerInfoSnapshotCall?.invoke()
        return serverInfoSnapshotValue
    }

    var fetchServerDetailsCalls: Int = 0
        private set

    override suspend fun fetchServerDetails(): ServerDetails {
        fetchServerDetailsCalls++
        return serverDetailsValue ?: throw CoreException.NotSignedIn()
    }

    override suspend fun children(parentId: String, sort: SortOrder, offset: UInt, limit: UInt): List<Card> {
        _childrenCalls.add(ChildrenCall(parentId, sort, offset, limit))
        val items = childrenByParent[parentId].orEmpty()
        return items.drop(offset.toInt()).take(limit.coerceAtMost(Int.MAX_VALUE.toUInt()).toInt())
    }

    override suspend fun libraryGrid(viewId: String, sort: GridSort, filters: GridFilters, offset: UInt, limit: UInt): List<Card>? {
        _libraryGridCalls.add(LibraryGridCall(viewId, sort, filters, offset, limit))
        // Snapshot before awaiting the gate: an older call must return what was configured at its
        // own call time.
        val items = libraryGridByView[viewId].orEmpty()
        libraryGridGate?.await()
        if (libraryGridFails) return null
        return items.drop(offset.toInt()).take(limit.coerceAtMost(Int.MAX_VALUE.toUInt()).toInt())
    }

    override suspend fun libraryGridCounts(viewId: String, filters: GridFilters): GridCounts? {
        _libraryGridCountsCalls.add(LibraryGridCountsCall(viewId, filters))
        if (libraryGridFails) return null
        libraryGridCountsByView[viewId]?.let { return it }
        val size = libraryGridByView[viewId].orEmpty().size.toULong()
        return GridCounts(filtered = size, total = size)
    }

    override suspend fun libraryGridGroups(viewId: String, sort: GridSort, filters: GridFilters): List<GridGroup>? {
        _libraryGridGroupsCalls.add(LibraryGridGroupsCall(viewId, sort, filters))
        if (libraryGridFails) return null
        return libraryGridGroupsByView[viewId].orEmpty()
    }

    override suspend fun libraryGenres(viewId: String): List<String> {
        _libraryGenresCalls.add(viewId)
        return libraryGenresByView[viewId].orEmpty()
    }

    override suspend fun getLibraryGridPrefs(viewId: String): LibraryGridPrefs =
        libraryGridPrefsByView[viewId] ?: defaultLibraryGridPrefs()

    /** When non-null, [setLibraryGridPrefs] awaits this after recording the call (a slow prefs write). */
    var setLibraryGridPrefsGate: CompletableDeferred<Unit>? = null

    override suspend fun setLibraryGridPrefs(viewId: String, prefs: LibraryGridPrefs) {
        _setLibraryGridPrefsCalls.add(SetLibraryGridPrefsCall(viewId, prefs))
        setLibraryGridPrefsGate?.await()
        libraryGridPrefsByView[viewId] = prefs
    }

    override suspend fun liveChildren(parentId: String, startIndex: UInt, limit: UInt, sort: LiveSort): List<Card> {
        _liveChildrenCalls.add(LiveChildrenCall(parentId, startIndex, limit, sort))
        liveChildrenError?.let { throw it }
        val items = liveChildrenByParent[parentId].orEmpty()
        return items.drop(startIndex.toInt()).take(limit.coerceAtMost(Int.MAX_VALUE.toUInt()).toInt())
    }

    override suspend fun views(): List<ViewSnapshot> = viewsError?.let { throw it } ?: viewsList

    private val _viewsState = MutableStateFlow(viewsList)
    override val viewsState: StateFlow<List<ViewSnapshot>> = _viewsState

    /** Test hook: publishes a new library list to [viewsState], as a refreshed [RealCoreGateway] does. */
    fun publishViews(views: List<ViewSnapshot>) {
        _viewsState.value = views
    }

    var hasFavoritesResult: Boolean = false
    override suspend fun hasFavorites(): Boolean = hasFavoritesResult

    var favoriteItemTypesResult: List<String> = emptyList()
    override suspend fun favoriteItemTypes(): List<String> = favoriteItemTypesResult

    override suspend fun search(query: String, limit: UInt): List<Card> {
        _searchCalls.add(SearchCall(query, limit))
        return searchResultsByQuery[query].orEmpty()
    }

    override suspend fun getItemDetail(itemId: String, accountEpoch: ULong?): ItemDetail {
        accountBoundCalls.add("getItemDetail" to accountEpoch)
        _getItemDetailCalls.add(itemId)
        val result = itemDetailResultsByItemId[itemId]
            ?: throw UnconfiguredFakeCall("FakeCoreGateway.getItemDetail($itemId): no result configured")
        return result.getOrElse { throw it }
    }

    /** [itemDetailLocal] mirror records keyed by item id; unmapped resolves `null` ("not mirrored"). */
    val itemDetailLocalByItemId = mutableMapOf<String, ItemDetail>()

    private val _itemDetailLocalCalls = mutableListOf<String>()
    val itemDetailLocalCalls: List<String> get() = _itemDetailLocalCalls

    override suspend fun itemDetailLocal(itemId: String): ItemDetail? {
        _itemDetailLocalCalls.add(itemId)
        return itemDetailLocalByItemId[itemId]
    }

    /** [cardById] results keyed by item id; unmapped resolves `null` ("not in the mirror"). */
    val cardsByItemId = mutableMapOf<String, Card>()

    private val _cardByIdCalls = mutableListOf<String>()
    val cardByIdCalls: List<String> get() = _cardByIdCalls

    /** When non-null, [cardById] awaits this (after recording the call) before it answers. */
    var cardByIdGate: CompletableDeferred<Unit>? = null

    override suspend fun cardById(itemId: String): Card? {
        _cardByIdCalls.add(itemId)
        cardByIdGate?.await()
        return cardsByItemId[itemId]
    }

    override suspend fun cardsByIds(itemIds: List<String>): List<Card> = itemIds.mapNotNull { cardsByItemId[it] }

    /** One gate per [getPlaybackOsdDetail] call, popped in call order; its value is that call's
     * answer. An empty queue answers at once from [itemDetailResultsByItemId].
     */
    val playbackOsdDetailGates = ArrayDeque<CompletableDeferred<PlaybackOsdDetail>>()

    override suspend fun getPlaybackOsdDetail(itemId: String, mediaSourceId: String, accountEpoch: ULong?): PlaybackOsdDetail {
        accountBoundCalls.add("getPlaybackOsdDetail" to accountEpoch)
        _getPlaybackOsdDetailCalls.add(itemId)
        _getPlaybackOsdDetailMediaSources.add(mediaSourceId)
        playbackOsdDetailGates.removeFirstOrNull()?.let { return it.await() }
        val result = itemDetailResultsByItemId[itemId]
            ?: throw UnconfiguredFakeCall("FakeCoreGateway.getPlaybackOsdDetail($itemId): no result configured")
        val detail = result.getOrElse { throw it }
        return PlaybackOsdDetail(
            container = detail.container,
            mediaStreams = detail.mediaStreams,
            chapters = detail.chapters,
            sizeBytes = detail.sizeBytes,
            // ItemDetail carries no file path; a test needing one builds PlaybackOsdDetail
            // directly.
            path = null,
            trickplay = osdTrickplay,
        )
    }

    override suspend fun getSimilar(itemId: String, limit: UInt): List<Card> {
        _getSimilarCalls.add(GetSimilarCall(itemId, limit))
        return similarResultsByItemId[itemId].orEmpty()
    }

    /** One gate per [getMediaSegments] call, popped in call order; its value is that call's
     * answer. An empty queue answers at once from [mediaSegmentsByItemId].
     */
    val mediaSegmentsGates = ArrayDeque<CompletableDeferred<List<MediaSegment>>>()

    override suspend fun getMediaSegments(itemId: String, accountEpoch: ULong?): List<MediaSegment> {
        accountBoundCalls.add("getMediaSegments" to accountEpoch)
        _getMediaSegmentsCalls.add(itemId)
        mediaSegmentsGates.removeFirstOrNull()?.let { return it.await() }
        return mediaSegmentsByItemId[itemId].orEmpty()
    }

    /** Test-only reimplementation of `outro_start_secs_from_segments` (Outro entry ticks->secs). */
    override fun outroStartSecsFromSegments(segments: List<MediaSegment>): Double? =
        segments.firstOrNull { it.segmentType == MediaSegmentKind.OUTRO }?.let { it.startTicks / 10_000_000.0 }

    /** How many times [getSettings] was called. */
    var getSettingsCallCount = 0
        private set

    override suspend fun getSettings(): Settings {
        getSettingsCallCount++
        return settings
    }

    /** When non-null, [setSettings] awaits this (after counting) before it is recorded. */
    var setSettingsGate: CompletableDeferred<Unit>? = null

    /** [setSettings] calls begun, held at [setSettingsGate] or not. */
    var setSettingsEntered: Int = 0
        private set

    /** Thrown by [setSettings] while non-null, after the gate and before the record is kept. */
    var setSettingsError: Throwable? = null

    override suspend fun setSettings(settings: Settings) {
        setSettingsEntered++
        setSettingsGate?.await()
        setSettingsError?.let { throw it }
        _setSettingsCalls.add(settings)
        this.settings = settings
    }

    override fun imageUrl(itemId: String, kind: ImageKind, tag: String, maxWidth: UInt, accountEpoch: ULong?): String? = null

    private var lastPlaybackSeq = 0uL

    /** Requests passed to [preparePlayback]/[prepareTranscodeFallback], in call order. */
    val playbackRequests = mutableListOf<PlaybackRequest>()

    /** The pair the fake publishes; tests move it through [accountEpoch] and [parkedEpoch]. */
    override val accountEpochs = MutableStateFlow(AccountEpochs(epoch = 0uL, parked = null))

    /** Tests move the epoch to model an account change. */
    var accountEpoch: ULong
        get() = accountEpochs.value.epoch
        set(next) {
            accountEpochs.update { it.copy(epoch = next) }
        }

    /** Moves the pair in one write, as the core publishes it (an account change that parks a playback). */
    fun setEpochs(epoch: ULong, parked: ULong?) {
        accountEpochs.value = AccountEpochs(epoch = epoch, parked = parked)
    }

    /** Completed by default; a test replaces it to hold the first request until the restore. */
    var accountRestored = CompletableDeferred(Unit)

    override fun mintPlaybackRequest(accountEpoch: ULong?): PlaybackRequest =
        PlaybackRequest(seq = ++lastPlaybackSeq, accountEpoch = accountEpoch ?: this.accountEpoch)

    /** The epoch a playback kept across an account change owns, as the core reports it. */
    var parkedEpoch: ULong?
        get() = accountEpochs.value.parked
        set(next) {
            accountEpochs.update { it.copy(parked = next) }
        }

    override fun playbackOwnershipOpen(accountEpoch: ULong): Boolean =
        accountEpochs.value.let { accountEpoch == it.epoch || accountEpoch == it.parked }

    /** [reauthorizationAccount]'s answers: the in-use accounts by identity. */
    val reauthorizationAccounts = mutableMapOf<AccountIdentity, AccountInfo>()

    override suspend fun reauthorizationAccount(rejected: AccountIdentity, failedPlayback: Boolean): AccountInfo? =
        reauthorizationAccounts[rejected]

    /** Every account-bound call by name with the epoch it named (null: the current account). */
    val accountBoundCalls = java.util.concurrent.CopyOnWriteArrayList<Pair<String, ULong?>>()

    override suspend fun playbackOwnershipOpenNow(accountEpoch: ULong): Boolean = playbackOwnershipOpen(accountEpoch)

    override suspend fun awaitAccountRestored() = accountRestored.await()

    override fun accountRestoredNow(): Boolean = accountRestored.isCompleted

    /** Holds [preparePlayback] for an item id (after recording the call) until completed. */
    val preparePlaybackGates = mutableMapOf<String, CompletableDeferred<Unit>>()

    override suspend fun preparePlayback(itemId: String, startFromBeginning: Boolean, request: PlaybackRequest): PlaybackPlan {
        playbackRequests.add(request)
        _preparePlaybackCalls.add(itemId)
        _preparePlaybackStartFromBeginningCalls.add(startFromBeginning)
        val result = preparePlaybackResultsByItemId[itemId]
            ?: preparePlaybackResult
            ?: throw UnconfiguredFakeCall("FakeCoreGateway.preparePlayback($itemId): no result configured")
        preparePlaybackGates[itemId]?.await()
        return result.getOrElse { throw it }
    }

    override suspend fun prepareTranscodeFallback(
        itemId: String,
        positionTicks: Long,
        reason: String,
        playSessionId: String,
        failed: FailedTrackFfi?,
        subtitleStreamIndex: Int?,
        request: PlaybackRequest,
    ): PlaybackPlan {
        playbackRequests.add(request)
        _prepareTranscodeFallbackCalls.add(PrepareTranscodeFallbackCall(itemId, positionTicks, reason, playSessionId, failed, subtitleStreamIndex))
        val result = prepareTranscodeFallbackResultsByCall[_prepareTranscodeFallbackCalls.size - 1]
            ?: prepareTranscodeFallbackResult
            ?: throw UnconfiguredFakeCall("FakeCoreGateway.prepareTranscodeFallback($itemId): no result configured")
        // Snapshot before awaiting the gate, same as libraryGrid's gate above.
        prepareTranscodeFallbackGate?.await()
        prepareTranscodeFallbackGatesByCall[_prepareTranscodeFallbackCalls.size - 1]?.await()
        return result.getOrElse { throw it }
    }

    override suspend fun preloadPlayback(itemId: String, accountEpoch: ULong?) {
        accountBoundCalls.add("preloadPlayback" to accountEpoch)
        _preloadPlaybackCalls.add(itemId)
    }

    override suspend fun nextEpisodeAfter(itemId: String): Card? {
        _nextEpisodeAfterCalls.add(itemId)
        nextEpisodeError?.let { throw it }
        return nextEpisodeByItemId[itemId]
    }

    override suspend fun seriesEpisodes(seriesId: String): List<Card> {
        _seriesEpisodesCalls.add(seriesId)
        return seriesEpisodesBySeriesId[seriesId].orEmpty()
    }

    /** One gate per [episodeNeighbors] call, popped in call order; its value is that call's
     * answer. An empty queue answers at once from the next/previous-episode maps.
     */
    val episodeNeighborsGates = ArrayDeque<CompletableDeferred<EpisodeNeighbors>>()

    override suspend fun episodeNeighbors(itemId: String, seriesId: String, accountEpoch: ULong?): EpisodeNeighbors {
        accountBoundCalls.add("episodeNeighbors" to accountEpoch)
        _episodeNeighborsItemIds.add(itemId)
        episodeNeighborsGates.removeFirstOrNull()?.let { return it.await() }
        return EpisodeNeighbors(
            previous = previousEpisodeByItemId[itemId],
            next = nextEpisodeAfter(itemId),
        )
    }

    override suspend fun serverDisplayName(accountEpoch: ULong?): String? {
        accountBoundCalls.add("serverDisplayName" to accountEpoch)
        serverDisplayNameCallCount++
        return serverDisplayNameValue
    }

    override fun nextEpisodeTriggerRemainingSecs(
        durationSecs: Double,
        outroStartSecs: Double?,
        outroAutoSkip: Boolean,
        countdownSecs: Double,
    ): Double {
        val default = (durationSecs * 0.15).coerceIn(3.0, 30.0)
        return if (outroStartSecs != null && outroStartSecs >= 0.0 && outroStartSecs < durationSecs) {
            val credits = durationSecs - outroStartSecs
            if (outroAutoSkip) credits + countdownSecs.coerceAtLeast(0.0) else credits
        } else {
            default
        }
    }

    override fun nextEpisodeCountdownTotal(remainingSecs: Double, delaySecs: Double): Double =
        remainingSecs.coerceAtLeast(0.0).coerceAtMost(delaySecs.coerceAtLeast(0.0))

    private val _notePlayerInputCalls = mutableListOf<ULong>()
    val notePlayerInputCalls: List<ULong> get() = _notePlayerInputCalls

    private val _noteEpisodeFinishedCalls = mutableListOf<ULong>()
    val noteEpisodeFinishedCalls: List<ULong> get() = _noteEpisodeFinishedCalls

    override suspend fun notePlayerInput(nowMs: ULong) {
        _notePlayerInputCalls.add(nowMs)
    }

    override suspend fun noteEpisodeFinished(nowMs: ULong): StillWatchingDecision {
        _noteEpisodeFinishedCalls.add(nowMs)
        return if (noteEpisodeFinishedDecisions.isEmpty()) {
            StillWatchingDecision.COUNTDOWN
        } else {
            noteEpisodeFinishedDecisions.removeFirst()
        }
    }

    private val _resetStillWatchingCalls = mutableListOf<ULong>()
    val resetStillWatchingCalls: List<ULong> get() = _resetStillWatchingCalls

    override suspend fun resetStillWatching(nowMs: ULong) {
        _resetStillWatchingCalls.add(nowMs)
    }

    /** When non-null, [reportPosition] awaits this (after counting) before it returns. */
    var reportPositionGate: CompletableDeferred<Unit>? = null

    /** [reportPosition] calls begun, held at [reportPositionGate] or not. */
    var reportPositionEntered: Int = 0
        private set

    override suspend fun reportPosition(playSessionId: String, ticks: Long) {
        reportPositionEntered++
        reportPositionGate?.await()
        _reportPositionCalls.add(ticks)
    }

    override suspend fun reportPaused(playSessionId: String, paused: Boolean) {
        _reportPausedSessionIds.add(playSessionId)
        _reportPausedCalls.add(paused)
    }

    override suspend fun stopPlayback(playSessionId: String, positionTicks: Long) {
        stopPlaybackEntered++
        stopPlaybackGate?.await()
        _stopPlaybackCalls.add(positionTicks)
        _stopPlaybackSessionIds.add(playSessionId)
    }

    override suspend fun abandonPlayback(playSessionId: String) {
        abandonPlaybackCallCount++
        _abandonPlaybackSessionIds.add(playSessionId)
    }

    override suspend fun resolveTracks(seriesId: String?, tracks: List<TrackInfo>): TrackDecisionFfi {
        _resolveTracksCalls.add(ResolveTracksCall(seriesId, tracks))
        resolveTracksGate?.await()
        return resolveTracksResult
    }

    override suspend fun rememberTrackMemory(seriesId: String, kind: TrackKindFfi, memory: TrackMemoryFfi) {
        _rememberTrackMemoryCalls.add(RememberTrackMemoryCall(seriesId, kind, memory))
    }

    /** Test-only reimplementation of `track_pref_key_of` (lang, else title). */
    override fun trackPrefKeyOf(track: TrackInfo): String? = track.lang ?: track.title

    override suspend fun trickplayTileUrl(itemId: String, width: UInt, imageIndex: UInt, accountEpoch: ULong?): String? {
        accountBoundCalls.add("trickplayTileUrl" to accountEpoch)
        _trickplayTileUrlCalls.add(TrickplayTileUrlCall(itemId, width, imageIndex))
        return trickplayTileUrlResult
    }

    val fetchExternalSubtitleCalls = mutableListOf<Pair<String, Int>>()

    /** Holds the fetch for `(playSessionId, index)` open until completed, so a pick, a session
     * change or a fallback can run while it is in flight (docs/18 §3.2). */
    val externalSubtitleGates = mutableMapOf<Pair<String, Int>, CompletableDeferred<Unit>>()

    /** Per-session text, over [externalSubtitleText]; an absent key there means the core refused. */
    var externalSubtitleTextBySession: Map<Pair<String, Int>, String?> = emptyMap()

    override suspend fun fetchExternalSubtitle(playSessionId: String, index: Int): String? {
        val key = playSessionId to index
        fetchExternalSubtitleCalls += key
        externalSubtitleGates[key]?.await()
        return if (key in externalSubtitleTextBySession) externalSubtitleTextBySession[key] else externalSubtitleText[index]
    }

    /** (session, index, key) per styled ASS sidecar load; [assSidecarLoads] answers it, true by default. */
    val loadAssSidecarCalls = mutableListOf<Triple<String, Int, String>>()
    var assSidecarLoads = true

    override suspend fun loadAssSidecar(playSessionId: String, index: Int, overlay: AssOverlay, key: String, item: ULong): Boolean {
        loadAssSidecarCalls += Triple(playSessionId, index, key)
        return assSidecarLoads
    }

    /** Test-only reimplementation of `trickplay::locate`'s grid math; keep in sync with Rust. */
    override fun trickplayLocate(meta: TrickplayMetaFfi, positionMs: ULong): TrickplayTileFfi? =
        fakeTrickplayLocate(meta, positionMs)

    /** Test-only reimplementation of `trickplay::glide_sample` (150 ms dwell); keep in sync. */
    override fun trickplayGlideSample(meta: TrickplayMetaFfi, targetMs: ULong, nowMs: ULong, lastSampleMs: ULong?, direction: GlideDirection): TrickplayTileFfi? {
        if (lastSampleMs != null && nowMs - lastSampleMs < 150uL) return null
        return trickplayLocateBiased(meta, targetMs, direction)
    }

    /** Test-only reimplementation of `trickplay::locate_biased`; keep in sync with Rust. */
    override fun trickplayLocateBiased(meta: TrickplayMetaFfi, positionMs: ULong, direction: GlideDirection): TrickplayTileFfi? {
        if (meta.intervalMs == 0u) return null
        // Forward rounds to the nearest thumbnail (half up), backward keeps the earlier one.
        val interval = meta.intervalMs.toULong()
        val biased = if (direction == GlideDirection.FORWARD && (positionMs % interval) * 2uL >= interval) {
            (positionMs / interval + 1uL) * interval
        } else {
            positionMs
        }
        return fakeTrickplayLocate(meta, biased)
    }

    /** Test-only reimplementation of `trickplay::glide_want_list` (1.5 s lookahead); keep in sync. */
    override fun trickplayGlideSheets(meta: TrickplayMetaFfi, targetMs: ULong, rate: UInt, direction: GlideDirection): List<UInt> {
        val current = fakeTrickplayLocate(meta, targetMs)?.imageIndex ?: return emptyList()
        val reach = rate.toULong() * 1500uL
        val ahead = if (direction == GlideDirection.FORWARD) targetMs + reach else if (targetMs > reach) targetMs - reach else 0uL
        val next = fakeTrickplayLocate(meta, ahead)?.imageIndex
        return if (next != null && next != current) listOf(current, next) else listOf(current)
    }

    /** Test-only reimplementation of `trickplay::should_abandon_sheet` (1 sheet slack); keep in sync. */
    override fun trickplayShouldAbandonSheet(meta: TrickplayMetaFfi, sheet: UInt, targetMs: ULong, direction: GlideDirection): Boolean {
        val current = fakeTrickplayLocate(meta, targetMs)?.imageIndex ?: return true
        return if (direction == GlideDirection.FORWARD) current > sheet + 1u else current + 1u < sheet
    }

    /** Mirrors `playback_policy::glide::END_CLAMP_MARGIN_MS` (1s) without the native library. */
    override fun glideEndClampMs(durationMs: ULong): ULong = if (durationMs > 1_000uL) durationMs - 1_000uL else 0uL

    // -- Seerr Discover (docs/14-seerr-discover.md) --------------------------

    override suspend fun seerrStatus(): SeerrStatus = seerrStatusValue

    override suspend fun seerrConnect(url: String, method: SeerrAuthMethod, identity: String, secret: String): SeerrStatus {
        _seerrConnectCalls.add(SeerrConnectCall(url, method, identity, secret))
        val result = seerrConnectResult
            ?: throw UnconfiguredFakeCall("FakeCoreGateway.seerrConnect: no result configured")
        val status = result.getOrElse { throw it }
        seerrRevision.update { it + 1 }
        return status
    }

    override suspend fun seerrDisconnect() {
        seerrDisconnectCallCount++
        seerrStatusValue = notConfiguredSeerrStatus()
        seerrRevision.update { it + 1 }
    }

    override suspend fun seerrHome(): SeerrHome {
        seerrHomeCallCount++
        seerrHomeGate?.await()
        return seerrHomeResult.getOrElse { throw it }
    }

    override suspend fun seerrBrowse(kind: SeerrBrowseKind, page: Int, filters: SeerrBrowseFilters): SeerrPage {
        _seerrBrowseCalls.add(SeerrBrowseCall(kind, page, filters))
        return seerrBrowseResult.getOrElse { throw it }
    }

    override suspend fun seerrGenres(mediaType: SeerrMediaType): List<SeerrGenre> = seerrGenresResult.getOrElse { throw it }

    override suspend fun seerrSearch(query: String, page: Int): SeerrPage {
        _seerrSearchCalls.add(SeerrSearchCall(query, page))
        return seerrSearchResult.getOrElse { throw it }
    }

    override suspend fun seerrMovie(tmdbId: Long): SeerrMovieDetail {
        _seerrMovieCalls.add(tmdbId)
        seerrMovieGate?.await()
        val result = seerrMovieResultsByTmdbId[tmdbId]
            ?: throw UnconfiguredFakeCall("FakeCoreGateway.seerrMovie($tmdbId): no result configured")
        return result.getOrElse { throw it }
    }

    override suspend fun seerrTv(tmdbId: Long): SeerrTvDetail {
        _seerrTvCalls.add(tmdbId)
        seerrTvGate?.await()
        val result = seerrTvResultsByTmdbId[tmdbId]
            ?: throw UnconfiguredFakeCall("FakeCoreGateway.seerrTv($tmdbId): no result configured")
        return result.getOrElse { throw it }
    }

    override suspend fun seerrPerson(personId: Long): SeerrPersonCredits {
        _seerrPersonCalls.add(personId)
        val result = seerrPersonResultsByPersonId[personId]
            ?: throw UnconfiguredFakeCall("FakeCoreGateway.seerrPerson($personId): no result configured")
        return result.getOrElse { throw it }
    }

    override suspend fun getPersonPage(personId: String): PersonPage {
        _getPersonPageCalls.add(personId)
        val result = personPageResultsById[personId]
            ?: throw UnconfiguredFakeCall("FakeCoreGateway.getPersonPage($personId): no result configured")
        return result.getOrElse { throw it }
    }

    override suspend fun personDiscoverCredits(personId: String, tmdbPersonId: Long, inLibrary: List<TitleTmdbRef>?): List<SeerrCard> {
        _personDiscoverCalls.add(PersonDiscoverCall(personId, tmdbPersonId, inLibrary))
        val result = personDiscoverResultsByTmdbId[tmdbPersonId]
            ?: throw UnconfiguredFakeCall("FakeCoreGateway.personDiscoverCredits($tmdbPersonId): no result configured")
        return result.getOrElse { throw it }
    }

    override suspend fun seerrRequestOptions(mediaType: SeerrMediaType, is4k: Boolean): SeerrRequestOptions {
        _seerrRequestOptionsCalls.add(SeerrRequestOptionsCall(mediaType, is4k))
        seerrRequestOptionsGate?.await()
        return seerrRequestOptionsResult.getOrElse { throw it }
    }

    override suspend fun seerrSubmitRequestDetail(input: SeerrRequestInput): SeerrRequestDetail {
        _seerrSubmitRequestCalls.add(input)
        seerrSubmitRequestGate?.await()
        return announcingChange { seerrSubmitRequestResult?.getOrThrow() ?: configuredRequestDetail(input.mediaType, input.tmdbId, requested = true) }
    }

    override suspend fun seerrCancelRequestDetail(mediaType: SeerrMediaType, tmdbId: Long, requestId: Long): SeerrRequestDetail {
        _seerrCancelRequestCalls.add(requestId)
        seerrCancelRequestGate?.await()
        return announcingChange { seerrCancelRequestResult?.getOrThrow() ?: configuredRequestDetail(mediaType, tmdbId, requested = false) }
    }

    /** Bumps [seerrRevision] like [RealCoreGateway]: on success, and when the change landed but its detail could not be read. */
    private inline fun announcingChange(call: () -> SeerrRequestDetail): SeerrRequestDetail =
        try {
            call().also { seerrRevision.update { it + 1 } }
        } catch (e: CoreException.SeerrChangeUnconfirmed) {
            seerrRevision.update { it + 1 }
            throw e
        }

    /** The title's configured detail as the post-mutation read the core returns: sibling lists and scores empty. */
    private fun configuredRequestDetail(mediaType: SeerrMediaType, tmdbId: Long, requested: Boolean): SeerrRequestDetail {
        val active = if (requested) SeerrActiveRequest(requestId = 7L, status = SeerrRequestStatus.PENDING, is4k = false, seasons = emptyList()) else null
        return if (mediaType == SeerrMediaType.MOVIE) {
            val movie = seerrMovieResultsByTmdbId[tmdbId]?.getOrThrow()
                ?: throw UnconfiguredFakeCall("FakeCoreGateway.seerrSubmit/CancelRequestDetail($tmdbId): no movie configured")
            SeerrRequestDetail.Movie(movie.copy(activeRequest = active, canRequest = active == null, similar = emptyList(), recommendations = emptyList(), criticsScore = null, audienceScore = null))
        } else {
            val tv = seerrTvResultsByTmdbId[tmdbId]?.getOrThrow()
                ?: throw UnconfiguredFakeCall("FakeCoreGateway.seerrSubmit/CancelRequestDetail($tmdbId): no tv configured")
            SeerrRequestDetail.Tv(tv.copy(activeRequest = active, canRequest = active == null, similar = emptyList(), recommendations = emptyList(), criticsScore = null, audienceScore = null))
        }
    }

    override suspend fun seerrMyRequests(): List<SeerrMyRequest> {
        seerrMyRequestsCallCount++
        seerrMyRequestsGate?.await()
        return seerrMyRequestsResult.getOrElse { throw it }
    }

    /** Matches [RealCoreGateway]'s contract: one shared, replay-0 flow, fed by [emitChange]. */
    private val _changeEvents = MutableSharedFlow<ChangeEvent>(replay = 0, extraBufferCapacity = 8)

    override fun changeEvents(): Flow<ChangeEvent> = _changeEvents.asSharedFlow()

    /** Test hook: pushes [event] to every current [changeEvents] collector. */
    fun emitChange(event: ChangeEvent) {
        _changeEvents.tryEmit(event)
    }

    // -- Detail action menu (docs/19-detail-action-menu.md §2.3) --------------

    data class SetPlayedCall(val itemId: String, val played: Boolean)

    private val _setPlayedCalls = mutableListOf<SetPlayedCall>()
    val setPlayedCalls: List<SetPlayedCall> get() = _setPlayedCalls

    /** When non-null, [setPlayed] throws this instead of succeeding (a failed server call). */
    var setPlayedError: Throwable? = null

    override suspend fun setPlayed(itemId: String, played: Boolean) {
        _setPlayedCalls.add(SetPlayedCall(itemId, played))
        setPlayedError?.let { throw it }
    }

    data class SetPlayedRecursiveCall(val scopeId: String, val played: Boolean)

    private val _setPlayedRecursiveCalls = mutableListOf<SetPlayedRecursiveCall>()
    val setPlayedRecursiveCalls: List<SetPlayedRecursiveCall> get() = _setPlayedRecursiveCalls

    var setPlayedRecursiveError: Throwable? = null

    override suspend fun setPlayedRecursive(scopeId: String, played: Boolean) {
        _setPlayedRecursiveCalls.add(SetPlayedRecursiveCall(scopeId, played))
        setPlayedRecursiveError?.let { throw it }
    }

    data class SetFavoriteCall(val itemId: String, val favorite: Boolean)

    private val _setFavoriteCalls = mutableListOf<SetFavoriteCall>()
    val setFavoriteCalls: List<SetFavoriteCall> get() = _setFavoriteCalls

    var setFavoriteError: Throwable? = null

    override suspend fun setFavorite(itemId: String, favorite: Boolean) {
        _setFavoriteCalls.add(SetFavoriteCall(itemId, favorite))
        setFavoriteError?.let { throw it }
    }

    var listCollectionsCallCount: Int = 0
        private set

    /** When non-null, [listCollections] awaits this (after counting) before it answers. */
    var listCollectionsGate: CompletableDeferred<Unit>? = null

    override suspend fun listCollections(): List<CollectionInfo> {
        listCollectionsCallCount++
        listCollectionsGate?.await()
        return collectionsResult
    }

    data class AddToCollectionCall(val collectionId: String, val itemId: String)

    private val _addToCollectionCalls = mutableListOf<AddToCollectionCall>()
    val addToCollectionCalls: List<AddToCollectionCall> get() = _addToCollectionCalls

    var addToCollectionError: Throwable? = null

    override suspend fun addToCollection(collectionId: String, itemId: String) {
        _addToCollectionCalls.add(AddToCollectionCall(collectionId, itemId))
        addToCollectionError?.let { throw it }
    }

    private val _refreshMetadataCalls = mutableListOf<String>()
    val refreshMetadataCalls: List<String> get() = _refreshMetadataCalls

    var refreshMetadataError: Throwable? = null

    override suspend fun refreshMetadata(itemId: String) {
        _refreshMetadataCalls.add(itemId)
        refreshMetadataError?.let { throw it }
    }

    var validateSessionCallCount: Int = 0
        private set

    override suspend fun validateSession() {
        validateSessionCallCount++
    }

    var isAdministratorCallCount: Int = 0
        private set

    /** When non-null, [isAdministrator] awaits this (after counting) before it answers. */
    var isAdministratorGate: CompletableDeferred<Unit>? = null

    override suspend fun isAdministrator(): Boolean {
        isAdministratorCallCount++
        isAdministratorGate?.await()
        return isAdministratorResult
    }

    private val _collectionIdsContainingCalls = mutableListOf<String>()
    val collectionIdsContainingCalls: List<String> get() = _collectionIdsContainingCalls

    override suspend fun collectionIdsContaining(itemId: String): List<String> {
        _collectionIdsContainingCalls.add(itemId)
        return collectionIdsByItemId[itemId].orEmpty()
    }
}

/** Test-only reimplementation of `playback_policy::trickplay::locate`; keep in sync with Rust. */
fun fakeTrickplayLocate(meta: TrickplayMetaFfi, positionMs: ULong): TrickplayTileFfi? {
    val tileWidth = meta.tileWidth.toULong()
    val tileHeight = meta.tileHeight.toULong()
    val tilesPerSheet = tileWidth * tileHeight
    val maxTilesPerSheet = 128UL * 128UL
    if (meta.width == 0u || meta.height == 0u || meta.intervalMs == 0u || tilesPerSheet == 0UL || tilesPerSheet > maxTilesPerSheet) {
        return null
    }
    val maxIndex = if (meta.thumbnailCount == 0u) 0UL else meta.thumbnailCount.toULong() - 1UL
    val localIndex = (positionMs / meta.intervalMs.toULong()).coerceAtMost(maxIndex)
    val imageIndex = localIndex / tilesPerSheet
    val pos = localIndex % tilesPerSheet
    val row = pos / tileWidth
    val col = pos % tileWidth
    return TrickplayTileFfi(
        imageIndex = imageIndex.toUInt(),
        x = (col * meta.width.toULong()).toUInt(),
        y = (row * meta.height.toULong()).toUInt(),
    )
}

/** Convenience for tests that need a [CoreException] thrown from `preparePlayback`'s `Result`. */
fun CoreException.asFailure(): Result<PlaybackPlan> = Result.failure(this)

/** The `TrackDecisionFfi` equivalent of "no preference set at all" ([FakeCoreGateway]'s
 * default). */
fun leaveEverythingAloneDecision(): TrackDecisionFfi = TrackDecisionFfi(
    audioTrackId = null,
    subtitleAction = SubtitleActionFfi.LEAVE,
    subtitleTrackId = null,
)

/** [FakeCoreGateway]'s default [FakeCoreGateway.seerrStatusValue]: a fresh install's state. */
fun notConfiguredSeerrStatus(): SeerrStatus = SeerrStatus(
    configured = false,
    seerrUrl = null,
    method = null,
    identity = null,
    appTitle = null,
)

/** An empty (but successful) [SeerrPage], [FakeCoreGateway]'s default for
 * [FakeCoreGateway.seerrBrowseResult]/[FakeCoreGateway.seerrSearchResult]. */
fun emptySeerrPage(): SeerrPage = SeerrPage(cards = emptyList(), page = 1, totalPages = 1, totalResults = 0)

/** [FakeCoreGateway]'s default [FakeCoreGateway.serverInfoSnapshotValue]: "not signed in", every
 * field null/false/empty (docs/13 About). */
fun emptyServerInfoSnapshot(): ServerInfoSnapshot = ServerInfoSnapshot(
    serverUrl = null,
    serverName = null,
    serverVersion = null,
    userName = null,
    deviceId = null,
    liveEventsConnected = false,
    mirror = null,
    libraries = emptyList(),
)

/** Matches `JellybeamCore::get_library_grid_prefs`'s default (docs/16 §3): Name ascending, every
 * filter `Any`/`false`/`null` -- the answer for a view id never seen before. */
fun defaultLibraryGridPrefs(): LibraryGridPrefs = LibraryGridPrefs(
    sort = GridSort(GridSortField.NAME, descending = false),
    filters = GridFilters(
        watched = WatchedFilter.ANY,
        genre = null,
        decade = null,
        status = StatusFilter.ANY,
    ),
)
