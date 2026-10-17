package tv.jellybeam.data

import tv.jellybeam.AppGraph
import tv.jellybeam.perf.PerfAccumulator
import tv.jellybeam.perf.PerfLog
import tv.jellybeam.player.AuthorizationRecoveryCoordinator
import tv.jellybeam.player.authorizationRecoveryCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import uniffi.jellybeam_core.AccountEpochs
import uniffi.jellybeam_core.AccountIdentity
import uniffi.jellybeam_core.AccountInfo
import uniffi.jellybeam_core.AssOverlay
import uniffi.jellybeam_core.Card
import uniffi.jellybeam_core.ChangeEvent
import uniffi.jellybeam_core.ChangeListener
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
import uniffi.jellybeam_core.HomeLayout
import uniffi.jellybeam_core.HomeSnapshot
import uniffi.jellybeam_core.ImageKind
import uniffi.jellybeam_core.ItemDetail
import uniffi.jellybeam_core.JellybeamCoreInterface
import uniffi.jellybeam_core.LibraryGridPrefs
import uniffi.jellybeam_core.LiveSort
import uniffi.jellybeam_core.MediaSegment
import uniffi.jellybeam_core.PersonPage
import uniffi.jellybeam_core.PlaybackOsdDetail
import uniffi.jellybeam_core.PlaybackPlan
import uniffi.jellybeam_core.PlaybackRequest
import uniffi.jellybeam_core.QuickConnectSession
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
import uniffi.jellybeam_core.SeerrRequestDetail
import uniffi.jellybeam_core.SeerrRequestInput
import uniffi.jellybeam_core.SeerrRequestOptions
import uniffi.jellybeam_core.SeerrStatus
import uniffi.jellybeam_core.SeerrTvDetail
import uniffi.jellybeam_core.ServerDetails
import uniffi.jellybeam_core.ServerInfoSnapshot
import uniffi.jellybeam_core.Settings
import uniffi.jellybeam_core.SortOrder
import uniffi.jellybeam_core.StillWatchingDecision
import uniffi.jellybeam_core.SyncStatus
import uniffi.jellybeam_core.TitleTmdbRef
import uniffi.jellybeam_core.TrackDecisionFfi
import uniffi.jellybeam_core.TrackInfo
import uniffi.jellybeam_core.TrackKindFfi
import uniffi.jellybeam_core.TrackMemoryFfi
import uniffi.jellybeam_core.TrickplayMetaFfi
import uniffi.jellybeam_core.TrickplayTileFfi
import uniffi.jellybeam_core.ViewSnapshot

/**
 * Thin wrapper around [JellybeamCoreInterface], the seam ViewModels test against a fake instead
 * of a real `JellybeamCore`. Every real-work method is `suspend` on [Dispatchers.IO]; [imageUrl]
 * is the synchronous exception (pure string format, `null` on failure fails open).
 */
interface CoreGateway {
    suspend fun updater(facts: uniffi.jellybeam_core.InstalledUpdateFacts): uniffi.jellybeam_core.AppUpdater =
        error("Updater unavailable in this gateway")

    suspend fun restoreSession(): AccountInfo?

    @Throws(CoreException::class)
    suspend fun signIn(serverUrl: String, username: String, password: String): AccountInfo

    /** Refreshes one saved account's token while preserving its isolated mirror. */
    @Throws(CoreException::class)
    suspend fun reauthorizeSession(index: UInt, username: String, password: String): AccountInfo

    /** Starts Quick Connect and returns the display code plus opaque in-memory secret. */
    @Throws(CoreException::class)
    suspend fun initiateQuickConnect(serverUrl: String): QuickConnectSession

    /** Performs exactly one status poll. Android owns delay and cancellation. */
    @Throws(CoreException::class)
    suspend fun pollQuickConnect(serverUrl: String, secret: String): Boolean

    /** Completes an approved request through the same transactional sign-in path as a password. */
    @Throws(CoreException::class)
    suspend fun completeQuickConnect(serverUrl: String, secret: String): AccountInfo

    /** Completes Quick Connect only if the approved user is the selected saved account. */
    @Throws(CoreException::class)
    suspend fun completeQuickConnectReauthorization(index: UInt, secret: String): AccountInfo

    /**
     * LAN discovery (docs/feature-dev/spec-still-watching-and-lan-discovery.md Feature B):
     * broadcasts, returns responders within ~1.5s marked [DiscoveredServer.alreadySaved]. Fails
     * open to an empty list; the manual URL field stays primary.
     */
    suspend fun discoverServers(): List<DiscoveredServer>

    /** Every locally known signed-in account, core's stable order. [AccountInfo] carries no
     * token, so it's safe in Compose state; server URLs/usernames shown verbatim, never logged.
     */
    suspend fun listAccounts(): List<AccountInfo>

    /** Index into [listAccounts]'s ordering of the currently active session, or `null` if nothing
     * is signed in yet.
     */
    suspend fun activeAccountIndex(): UInt?

    /** The active server's reported version, refreshed at sign-in/restore/switch/reauthorization
     * and every socket reconnect. `null` until first fetched.
     */
    suspend fun serverVersion(): String?

    /** Version gate for server-specific features. Fails closed: unknown answers `false`, falling
     * back to the support floor. Changes arrive as [ChangeEvent.Refresh] on [changeEvents].
     */
    suspend fun serverAtLeast(major: UInt, minor: UInt): Boolean

    /**
     * Switches the active session to [index] from its stored token, no network validation.
     * Callers must re-run [restoreSession] -> [openMirror] -> [views] afterward; this alone
     * does not reopen the mirror.
     */
    @Throws(CoreException::class)
    suspend fun switchSession(index: UInt): AccountInfo

    /** Permanently removes one saved account and its isolated local mirror. */
    @Throws(CoreException::class)
    suspend fun removeSession(index: UInt): Boolean

    @Throws(CoreException::class)
    suspend fun openMirror()

    /** Pushes probed decoder capabilities ([tv.jellybeam.player.DeviceCapsProbe]) for the next
     * [preparePlayback] call; latest wins if called more than once, else a conservative default.
     */
    suspend fun setDeviceCaps(caps: DeviceCaps)

    /** docs/25 §4.3: the snapshot for [layout], the layout the caller will draw. */
    suspend fun homeSnapshot(layout: HomeLayout): HomeSnapshot

    suspend fun isSyncing(): Boolean

    /** Snapshot of the mirror's bulk-sync activity, backing cold-start status messaging. Same
     * fail-open-to-`Idle` contract as [isSyncing]; polled roughly once a second.
     */
    suspend fun syncStatus(): SyncStatus

    /** docs/13 About: server identity, mirror stats and library list for the connected account.
     * Fails open like [syncStatus], never throws.
     */
    suspend fun serverInfoSnapshot(): ServerInfoSnapshot

    /** docs/13 About: product/OS/architecture, restart-or-update status and server-side media
     * counts; requires an administrator account on the server. Persists the server's name and
     * version on success, feeding the next [serverInfoSnapshot] read.
     */
    @Throws(CoreException::class)
    suspend fun fetchServerDetails(): ServerDetails

    /**
     * A bounded slice of a parent's mirror-backed children; library grids page through this. An
     * empty list is a genuinely empty parent; a mirror that is not open yet throws
     * [BrowseNotReady] and a failed query [BrowseFailed] (docs/07 §5).
     */
    suspend fun children(parentId: String, sort: SortOrder, offset: UInt, limit: UInt): List<Card>

    /** A bounded slice of [parentId]'s children fetched live, never mirror-backed -- used for
     * plugin channel views, which never sync to the offline mirror. Throws on failure; caller
     * keeps the last good page.
     */
    @Throws(CoreException::class)
    suspend fun liveChildren(parentId: String, startIndex: UInt, limit: UInt, sort: LiveSort): List<Card>

    /** A bounded, sorted, filtered slice of one `ViewKind.LIBRARY` view's grid (docs/16 §2.2,
     * §3). `null` (docs/16 §4.6) on a failed/unopened query, kept distinct from an `emptyList()`
     * result, so the caller retains its last good grid.
     */
    suspend fun libraryGrid(viewId: String, sort: GridSort, filters: GridFilters, offset: UInt, limit: UInt): List<Card>?

    /** Filtered vs. total row counts for [viewId]'s grid (docs/16 §2.3, §3), backing the summary
     * line. Same null/last-good contract as [libraryGrid] (docs/16 §4.6).
     */
    suspend fun libraryGridCounts(viewId: String, filters: GridFilters): GridCounts?

    /** Per-[sort]-field group buckets over [viewId]'s filtered grid (docs/16 §2.4, §3), backing
     * the index rail. Same null/last-good contract as [libraryGrid].
     */
    suspend fun libraryGridGroups(viewId: String, sort: GridSort, filters: GridFilters): List<GridGroup>?

    /** Distinct genres in [viewId]'s grid, verbatim server strings (CLAUDE.md hard rule), `COLLATE
     * NOCASE` order (docs/16 §2.5, §3). Fails open to an empty list.
     */
    suspend fun libraryGenres(viewId: String): List<String>

    /** [viewId]'s persisted sort/filter choice (docs/16 §3, §5); defaults to Name-ascending/every
     * filter `Any` for an unseen view id.
     */
    suspend fun getLibraryGridPrefs(viewId: String): LibraryGridPrefs

    /** Persists [prefs] for [viewId] (docs/16 §3, §5), write-through on every sort/filter intent,
     * no save button.
     */
    suspend fun setLibraryGridPrefs(viewId: String, prefs: LibraryGridPrefs)

    /** The server-configured libraries, names shown verbatim (CLAUDE.md hard rule). */
    suspend fun views(): List<ViewSnapshot>

    /**
     * [views] as the app's one shared list (docs/07 §5): read when the mirror opens and again only
     * on [ChangeEvent.ViewsChanged] or a merged [ChangeEvent.Refresh] (never on item changes). Empty
     * until the mirror has opened, and again after an open that failed.
     */
    val viewsState: StateFlow<List<ViewSnapshot>>

    /** docs/07 §5: whether the drawer shows its Favorites entry. */
    suspend fun hasFavorites(): Boolean

    /** docs/16 §2.7: item types present among favorites, for the Favorites grid's Type panel. */
    suspend fun favoriteItemTypes(): List<String>

    /** Mirror-backed free-text search (name/series-name/overview prefix match, ranked, capped
     * at [limit]). Fails open to an empty list; never throws.
     */
    suspend fun search(query: String, limit: UInt): List<Card>

    /** The full detail record for [itemId]'s Detail UI: fields the mirror sync never carries,
     * fetched live per visit and never persisted. Throws rather than degrading to a default
     * (docs/11 tier 1 item 20).
     */
    @Throws(CoreException::class)
    suspend fun getItemDetail(itemId: String, accountEpoch: ULong? = null): ItemDetail

    /**
     * [getItemDetail]'s first paint from the mirror (docs/11 item 1): overview, genres, added date,
     * no cast/streams/counts; never networks, `null` when the mirror doesn't know [itemId].
     */
    suspend fun itemDetailLocal(itemId: String): ItemDetail? = null

    /** Mirror-only lookup of one item's full [Card] by bare id, `null` if not in the local mirror.
     * Backs Discover's "Go to library" routing (docs/14-seerr-discover.md).
     */
    @Throws(CoreException::class)
    suspend fun cardById(itemId: String): Card?

    /** [cardById] for many ids in one call: the mirror's cards in the order asked, unknown ids dropped. */
    @Throws(CoreException::class)
    suspend fun cardsByIds(itemIds: List<String>): List<Card>

    /** Playback's one post-load enrichment fetch (docs/10): stream/chapter detail plus [mediaSourceId]'s
     * trickplay manifest; never collector metadata. */
    @Throws(CoreException::class)
    suspend fun getPlaybackOsdDetail(itemId: String, mediaSourceId: String, accountEpoch: ULong?): PlaybackOsdDetail

    /** The "Similar Titles" row (docs/11 tier 2 item 13): up to [limit] [Card]s from a live,
     * non-mirror-backed `GetSimilar` call, fetched alongside the Detail paint.
     */
    @Throws(CoreException::class)
    suspend fun getSimilar(itemId: String, limit: UInt): List<Card>

    /** Skip-intro/credits markers for [itemId] (docs/12 "Skip intro/credits"). Fails open to
     * an empty list; a markers fetch must never block or error out playback.
     */
    suspend fun getMediaSegments(itemId: String, accountEpoch: ULong? = null): List<MediaSegment>

    /** Pure delegate to `outro_start_secs_from_segments`: the Outro segment's start (seconds)
     * within [segments], if any. No I/O; not `suspend`.
     */
    fun outroStartSecsFromSegments(segments: List<MediaSegment>): Double?

    /** The whole persisted [Settings] record (docs/09-settings-plan.md) -- never a per-field
     * getter.
     */
    suspend fun getSettings(): Settings

    /** Persists the whole [Settings] record and applies every side effect it controls (home
     * filtering, Next Up options). Write-through, no save button.
     */
    suspend fun setSettings(settings: Settings)

    fun imageUrl(itemId: String, kind: ImageKind, tag: String, maxWidth: UInt, accountEpoch: ULong? = null): String?

    /** docs/18 §2.1: the next playback request, minted on the caller's main thread before any
     * dispatch so the core admits requests in viewer order, under [accountEpoch] (a playback
     * continuing on its own account) or else the current one. Account-bound calls take the same
     * `accountEpoch`; `null` is the current account.
     */
    fun mintPlaybackRequest(accountEpoch: ULong? = null): PlaybackRequest

    /** docs/18 §2.1: the core's account epoch and the epoch a playback kept on its own account
     * across a change still owns (`parked`), read from the core as one pair and updated as each
     * account call returns; `parked` can clear without the epoch moving (its account removed).
     */
    val accountEpochs: StateFlow<AccountEpochs>

    /** docs/18 §2.1: the saved account to re-authorize for a token [rejected] was issued to -- while
     * the app uses it (browsed, or a playback runs or negotiates on it), or, for the player's own
     * [failedPlayback], while it is saved; `null` otherwise.
     */
    suspend fun reauthorizationAccount(rejected: AccountIdentity, failedPlayback: Boolean): AccountInfo?

    /** docs/18 §2.1: whether a request minted under [accountEpoch] still owns playback: the current
     * account's, or a playback kept on its own account across a change. Main-thread safe: no FFI.
     */
    fun playbackOwnershipOpen(accountEpoch: ULong): Boolean

    /** docs/18 §2.1: [playbackOwnershipOpen] against the core's epochs now, not [accountEpochs], which
     * lags an account call still in flight; for judging a playback result off the main thread.
     */
    suspend fun playbackOwnershipOpenNow(accountEpoch: ULong): Boolean

    /** docs/18 §2.1: suspends until the first account call (the launch restore) has returned, so the
     * first playback request carries the epoch the core will hold.
     */
    suspend fun awaitAccountRestored()

    /** Whether the launch restore has returned, without suspending. */
    fun accountRestoredNow(): Boolean

    /** Negotiates playback for [itemId] and starts a fresh reporting session. Direct Play only
     * (CLAUDE.md hard rule): throws [CoreException.WouldTranscode] instead of returning a plan.
     * [startFromBeginning] (docs/11 item 11 pill) ignores any saved resume position.
     */
    @Throws(CoreException::class)
    suspend fun preparePlayback(itemId: String, startFromBeginning: Boolean, request: PlaybackRequest): PlaybackPlan

    /**
     * The Auto/Cap quality modes' one fallback per item (docs/18 §1/§2): renegotiates [itemId]
     * as a server transcode from [positionTicks] after direct play proves unplayable. [reason]
     * is free text surfaced via [tv.jellybeam.player.PlaybackUiState.transcodeReason]. Refuses with
     * [CoreException.WouldTranscode] when `Settings.playbackQuality` is `DirectPlay`.
     * Also renegotiates a playing transcode for a new subtitle choice (§3.1): [failed] is the
     * session's fallback cause, if any, and [subtitleStreamIndex] the stream to name (`-1` none).
     * [playSessionId] and [request] guard staleness (§2.1): throws
     * [CoreException.StalePlaybackSession] if superseded.
     */
    @Throws(CoreException::class)
    suspend fun prepareTranscodeFallback(
        itemId: String,
        positionTicks: Long,
        reason: String,
        playSessionId: String,
        failed: FailedTrackFfi?,
        subtitleStreamIndex: Int?,
        request: PlaybackRequest,
    ): PlaybackPlan

    /**
     * Focus-dwell preload (`Settings.preloadOnFocus`, default on): best-effort playback-handshake
     * prefetch for [itemId], fired by [tv.jellybeam.ui.cards.PreloadOnDwell] once a card has held
     * focus long enough. Metadata/negotiation only, never media bytes or a reporting session, and
     * never throws -- a no-op when the setting is off or the negotiation fails. Any fresh result
     * is silently consumed by the next [preparePlayback] call within the core's cache TTL.
     */
    suspend fun preloadPlayback(itemId: String, accountEpoch: ULong? = null)

    /**
     * The next episode within [itemId]'s series (season/episode order, skipping virtual
     * entries, crossing season boundaries), or `null` if [itemId] isn't a real `Episode`, has no
     * known series, or is already last. Called once per loaded Episode, not on every tick.
     */
    suspend fun nextEpisodeAfter(itemId: String): Card?

    /**
     * All of [seriesId]'s episodes across every season, season-then-episode order (Specials
     * first), in one call (docs/11 item 11 Series Play/Resume) -- lets
     * [tv.jellybeam.ui.detail.DetailViewModel] resolve the primary-action button without fetching
     * every season individually. Empty means the series has none; a mirror that is not open yet
     * throws [BrowseNotReady] and a failed query [BrowseFailed] (docs/07 §5).
     */
    suspend fun seriesEpisodes(seriesId: String): List<Card>

    /** Both episode-edge controls from one lookup; the Rust side prefers the mirror, falling
     * back to one live series request only when the episode can't be placed locally.
     */
    suspend fun episodeNeighbors(itemId: String, seriesId: String, accountEpoch: ULong? = null): EpisodeNeighbors?

    /** The playback stats sheet's SOURCE row (docs/12 §8b): derived from the active session's
     * server URL, no network round trip. `null` if signed out or the URL has no parseable host.
     */
    suspend fun serverDisplayName(accountEpoch: ULong? = null): String?

    /** Pure delegate to `next_episode_trigger_remaining_secs`: the threshold at which the next-up
     * card should appear (~15%-of-runtime default, or the Outro start when known, brought forward
     * by [countdownSecs] when the outro is auto-skipped). No I/O; not `suspend`.
     */
    fun nextEpisodeTriggerRemainingSecs(
        durationSecs: Double,
        outroStartSecs: Double?,
        outroAutoSkip: Boolean,
        countdownSecs: Double,
    ): Double

    /** Pure delegate to `next_episode_countdown_total`: the next-up card's countdown-fill total
     * (`min(remainingSecs, delaySecs)`). No I/O; not `suspend`.
     */
    fun nextEpisodeCountdownTotal(remainingSecs: Double, delaySecs: Double): Double

    /**
     * "Still watching?" inactivity guard (docs/feature-dev/spec-still-watching-and-lan-discovery.md
     * Feature A): records a user key press (not an autoplay transition) and resets its counters.
     * Also called once at session construction so a new session is never asked immediately.
     */
    suspend fun notePlayerInput(nowMs: ULong)

    /** Records an autoplay transition with no intervening input, and returns whether next-up should
     * show its ordinary countdown or ask "still watching?". Called once per autoplay transition.
     */
    suspend fun noteEpisodeFinished(nowMs: ULong): StillWatchingDecision

    /** Unconditionally resets the "still watching?" guard's counters, unlike [notePlayerInput]
     * which is gated by `Settings.stillWatching.resetOnInput`.
     */
    suspend fun resetStillWatching(nowMs: ULong)

    /** Forwards a position update (ticks) to the installed session [playSessionId] names; a no-op
     * for any other (docs/18 §2.1).
     */
    suspend fun reportPosition(playSessionId: String, ticks: Long)

    /** Forwards a pause/resume edge, as [reportPosition]. */
    suspend fun reportPaused(playSessionId: String, paused: Boolean)

    /** Ends the active reporting session if it's still the one named by [playSessionId]: a final
     * Stopped report plus a mirror writeback fired over [changeEvents]. Fire-and-forget (docs/18
     * §2); a mismatch from a delayed call is a silently ignored lost race, not an error.
     */
    suspend fun stopPlayback(playSessionId: String, positionTicks: Long)

    /** Discards the active reporting session, if still named by [playSessionId] -- error paths with
     * no position worth persisting. Same lost-race contract as [stopPlayback].
     */
    suspend fun abandonPlayback(playSessionId: String)

    /** Automatic track selection (docs/09 step 3): resolves an audio/subtitle decision for
     * [tracks] against `Settings.language` and [seriesId]'s remembered choice (`null` for a
     * non-episodic item means no per-series memory).
     */
    suspend fun resolveTracks(seriesId: String?, tracks: List<TrackInfo>): TrackDecisionFfi

    /** Persists [seriesId]'s remembered choice for [kind], consumed by the next [resolveTracks]; subtitle
     * `Off` is a choice of its own and `Unset` forgets it (docs/18 §3.1). A `Key` must be [trackPrefKeyOf]'s result.
     */
    suspend fun rememberTrackMemory(seriesId: String, kind: TrackKindFfi, memory: TrackMemoryFfi)

    /** Pure delegate to `track_pref_key_of`: the persistence key a [TrackInfo] is remembered under
     * (language, else title). No I/O; not `suspend`.
     */
    fun trackPrefKeyOf(track: TrackInfo): String?

    /** Trickplay tile-sheet image URL for [tv.jellybeam.player.TrickplayPreviewer], or `null` with no
     * signed-in client. `suspend` (unlike [imageUrl]): called once per debounced seek gesture.
     */
    suspend fun trickplayTileUrl(itemId: String, width: UInt, imageIndex: UInt, accountEpoch: ULong? = null): String?

    /** docs/18 §3.2: sidecar [index]'s text for session [playSessionId], fetched on pick; null on any failure. */
    suspend fun fetchExternalSubtitle(playSessionId: String, index: Int): String?

    /**
     * docs/18 §3.2: ASS sidecar [index] fetched by the core straight into [overlay] as track [key]
     * of overlay item [item], never through the JVM heap; false on any failure.
     */
    suspend fun loadAssSidecar(playSessionId: String, index: Int, overlay: AssOverlay, key: String, item: ULong): Boolean

    /** Pure delegate to `trickplay_locate`: the sprite-sheet tile (if any) covering [positionMs]
     * for [meta]. No I/O; not `suspend`. Callers must not re-derive the tile-grid math themselves.
     */
    fun trickplayLocate(meta: TrickplayMetaFfi, positionMs: ULong): TrickplayTileFfi?

    /** Pure delegate to `trickplay_glide_sample` (docs/12 §11): the dwell-gated tile for a glide
     * target, `null` while the dwell since [lastSampleMs] is still running. */
    fun trickplayGlideSample(meta: TrickplayMetaFfi, targetMs: ULong, nowMs: ULong, lastSampleMs: ULong?, direction: GlideDirection): TrickplayTileFfi?

    /** Pure delegate to `trickplay_locate_biased` (docs/12 §11): the tile for a seek target,
     * biased so the previewed frame is never behind the landing point in [direction]. */
    fun trickplayLocateBiased(meta: TrickplayMetaFfi, positionMs: ULong, direction: GlideDirection): TrickplayTileFfi?

    /** Pure delegate to `trickplay_glide_sheets`: the sheets (at most two, fetch order) a glide
     * at [rate] media-seconds per real second should have ready. */
    fun trickplayGlideSheets(meta: TrickplayMetaFfi, targetMs: ULong, rate: UInt, direction: GlideDirection): List<UInt>

    /** Pure delegate to `trickplay_should_abandon_sheet`: whether an in-flight fetch of [sheet]
     * is no longer worth finishing for a glide now at [targetMs]. */
    fun trickplayShouldAbandonSheet(meta: TrickplayMetaFfi, sheet: UInt, targetMs: ULong, direction: GlideDirection): Boolean

    /** Pure delegate to `glide_end_clamp_ms`: the furthest position a hold-to-seek glide may land
     * at (docs/feature-dev/PRD-hold-to-seek.md §6.2). No I/O; not `suspend`.
     */
    fun glideEndClampMs(durationMs: ULong): ULong

    /** Mirror-change notifications from the Rust `ChangeListener`, via one shared, replay-0
     * [kotlinx.coroutines.flow.SharedFlow] -- so Home/Library/Detail can all collect concurrently
     * without one registration silently killing another.
     */
    fun changeEvents(): Flow<ChangeEvent>

    // -- Detail action menu (docs/19-detail-action-menu.md §2.3) --------------

    /** `POST`/`DELETE /UserPlayedItems/{id}` for a single Movie/Episode. The mirror writeback
     * follows over [changeEvents], same as playback's own stop report.
     */
    @Throws(CoreException::class)
    suspend fun setPlayed(itemId: String, played: Boolean)

    /** The same call on a Series or Season id, applied to every non-virtual episode in scope
     * (docs/19 §2.3's `set_played_recursive`).
     */
    @Throws(CoreException::class)
    suspend fun setPlayedRecursive(scopeId: String, played: Boolean)

    /** `POST`/`DELETE /UserFavoriteItems/{id}`. In season scope the caller passes the SERIES id
     * (docs/19 §1.1: the row reads/writes the series flag there).
     */
    @Throws(CoreException::class)
    suspend fun setFavorite(itemId: String, favorite: Boolean)

    /** The signed-in account's BoxSets, server sort-name order, names shown verbatim,
     * session-cached for ten minutes (docs/19 §2.3). Fails open to an empty list (docs/19 §1.1).
     */
    @Throws(CoreException::class)
    suspend fun listCollections(): List<CollectionInfo>

    /** `POST /Collections/{id}/Items?ids={itemId}` -- no local mirror write; the next
     * `LibraryChanged` resyncs membership (docs/19 §2.3).
     */
    @Throws(CoreException::class)
    suspend fun addToCollection(collectionId: String, itemId: String)

    /** Mirror-only membership read backing the Add to collection list's `ALREADY IN` rows
     * (docs/19 §1.3, §2.3). Same fail-open-to-empty shape as [listCollections].
     */
    suspend fun collectionIdsContaining(itemId: String): List<String>

    /** Fire-and-forget `POST /Items/{id}/Refresh` with fixed parameters (docs/19 §1.4) -- the
     * mirror reconciles over [changeEvents] when the server finishes.
     */
    @Throws(CoreException::class)
    suspend fun refreshMetadata(itemId: String)

    /** One authenticated round trip. Browse reads the mirror, so a revoked token is otherwise
     * unnoticed until the first Play; a 401 here reaches re-authorization through
     * [RealCoreGateway]'s seam like any other call's.
     */
    @Throws(CoreException::class)
    suspend fun validateSession()

    /** docs/07 Watch state: the app came to the foreground; the core refreshes played state in
     * the background. Runs on [Dispatchers.IO]; the core never does network work inline. */
    suspend fun onForeground() {}

    /** `current_user().policy.is_administrator`, cached per session on the Rust side -- gates the
     * Refresh metadata row (docs/19 §1.1).
     */
    @Throws(CoreException::class)
    suspend fun isAdministrator(): Boolean

    // -- Seerr Discover (docs/14-seerr-discover.md) --------------------------
    //
    // Every method below is a thin, blocking-dispatched wrapper, same convention as the rest of
    // this file. `seerrStatus` is local-file-only (zero network), which is why it's safe to call
    // near startup without violating docs/14's "zero startup cost" rule.

    /** Local-file-only read (zero network): whether Discover is configured for the active account.
     * Safe near startup.
     */
    suspend fun seerrStatus(): SeerrStatus

    /** Expands [url] into candidates, tries a real login on each, and saves the connection for the
     * active account on first success.
     */
    @Throws(CoreException::class)
    suspend fun seerrConnect(url: String, method: SeerrAuthMethod, identity: String, secret: String): SeerrStatus

    /** Removes the active account's saved Seerr connection and drops the live handle, if any.
     * Best-effort, never throws.
     */
    suspend fun seerrDisconnect()

    /** Trending/Movies/TV/Upcoming Movies/Upcoming TV shelves, fetched concurrently and cached
     * in-core for 60s; a row whose fetch fails is simply omitted.
     */
    @Throws(CoreException::class)
    suspend fun seerrHome(): SeerrHome

    /** One page of a single browse kind, with optional sort/genre/vote/network/status filters
     * (Movies/TV kinds only; Trending/Upcoming ignore [filters]).
     */
    @Throws(CoreException::class)
    suspend fun seerrBrowse(kind: SeerrBrowseKind, page: Int, filters: SeerrBrowseFilters): SeerrPage

    /** Genre list for [mediaType], cached for the process lifetime on the Rust side. */
    @Throws(CoreException::class)
    suspend fun seerrGenres(mediaType: SeerrMediaType): List<SeerrGenre>

    /** Mixed movie/TV search against the connected Seerr server (not the local Jellyfin mirror --
     * see [search] for that).
     */
    @Throws(CoreException::class)
    suspend fun seerrSearch(query: String, page: Int): SeerrPage

    /** Full movie detail: genres/cast/similar/recommendations/scores/request state, by TMDB id. */
    @Throws(CoreException::class)
    suspend fun seerrMovie(tmdbId: Long): SeerrMovieDetail

    /** Full TV detail, including the per-season SD availability/requestability list -- see
     * [SeerrTvDetail.seasons]'s own doc comment on the Rust side.
     */
    @Throws(CoreException::class)
    suspend fun seerrTv(tmdbId: Long): SeerrTvDetail

    /** A person's combined cast+crew credits (each capped at 25), by TMDB person id. */
    @Throws(CoreException::class)
    suspend fun seerrPerson(personId: Long): SeerrPersonCredits

    /** A person's record and library titles, live (docs/11 §Person page). [personId] is a Jellyfin id. */
    @Throws(CoreException::class)
    suspend fun getPersonPage(personId: String): PersonPage

    /** Seerr credits for a TMDB person minus [inLibrary] (null: the core lists ownership); callers fail open on any error. */
    @Throws(CoreException::class)
    suspend fun personDiscoverCredits(personId: String, tmdbPersonId: Long, inLibrary: List<TitleTmdbRef>?): List<SeerrCard>

    /** Radarr/Sonarr instances (with profiles/root folders) available for a request at [is4k];
     * empty means the UI shows a plain Request button.
     */
    @Throws(CoreException::class)
    suspend fun seerrRequestOptions(mediaType: SeerrMediaType, is4k: Boolean): SeerrRequestOptions

    /** Creates or updates (POST-vs-PUT decided in Rust) a request and returns the title's post-mutation
     * request state; Kotlin sends only seasons it marked requestable (docs/14 Request semantics).
     */
    @Throws(CoreException::class)
    suspend fun seerrSubmitRequestDetail(input: SeerrRequestInput): SeerrRequestDetail

    /** Cancels (deletes) the request [requestId] of the title and returns its post-mutation request state (docs/14). */
    @Throws(CoreException::class)
    suspend fun seerrCancelRequestDetail(mediaType: SeerrMediaType, tmdbId: Long, requestId: Long): SeerrRequestDetail

    /** The signed-in Seerr account's own requests, each resolved to a full card -- a request this
     * app can't resolve a title for is omitted (fail open).
     */
    @Throws(CoreException::class)
    suspend fun seerrMyRequests(): List<SeerrMyRequest>

    /** Bumps after every Seerr request, cancel, connect and disconnect that changed the server (a landed change with an unreadable detail included); the drawer's Discover entry follows it (docs/14). */
    val seerrRevision: StateFlow<Long>
}

/**
 * [core] arrives as a not-yet-necessarily-ready [Deferred] (docs/10 `core.ready` phase):
 * construction happens off `Application.onCreate`, so every real call here waits for it.
 */
internal class RealCoreGateway(
    private val core: Deferred<JellybeamCoreInterface>,
    /** Only used for the one-time [changeEvents] listener registration; must be a
     * process-scoped, never-cancelled scope ([tv.jellybeam.AppGraph.processScope]).
     */
    private val backgroundScope: CoroutineScope,
    /** Completed after the one-time decoder probe has either landed or failed open. */
    private val deviceCapsReady: Deferred<Unit>? = null,
    /** Where a call's 401 goes and where a landed re-authorization is noted (docs/18 §2.1). */
    private val authorizationRecovery: AuthorizationRecoveryCoordinator = authorizationRecoveryCoordinator,
) : CoreGateway {
    override suspend fun updater(facts: uniffi.jellybeam_core.InstalledUpdateFacts): uniffi.jellybeam_core.AppUpdater =
        ffi("updater") { updater(facts) }

    /**
     * The one seam every ordinary FFI wrapper goes through: hop to [Dispatchers.IO], await
     * [core], and time the call under [section] (`ffi.<methodName>`, never derived from an
     * argument -- privacy rule). [imageUrl], [preparePlayback]/[preloadPlayback], [changeEvents]
     * and the pure delegates don't go through this; they're written out explicitly.
     */
    private suspend inline fun <T> ffi(
        section: String,
        reauthorize: Boolean = true,
        crossinline op: suspend JellybeamCoreInterface.() -> T,
    ): T =
        withContext(Dispatchers.IO) {
            try {
                PerfLog.timed(section) { core.await().op() }
            } catch (e: CoreException) {
                noteCoreFailure(section, e, reauthorize)
                throw e
            }
        }

    /** Callers mostly fail open, so this is the one place a failed call is always recorded
     * (docs/21: section and variant only) and a dead token always reaches re-authorization, unless
     * the caller owns that decision ([reauthorize] false: playback requests, docs/18 §2.1).
     */
    private fun noteCoreFailure(section: String, error: CoreException, reauthorize: Boolean) {
        AppGraph.diag.event("ffi.error") {
            tag("section", section)
            tag("kind", error.diagLabel())
        }
        if (reauthorize && routesToReauthorization(section, error)) {
            authorizationRecovery.request((error as? CoreException.Unauthorized)?.account)
        }
    }

    /** docs/21 §2.1: shared timing+outcome recording for the three auth calls -- [result] maps a
     * success value to its `result` tag; a [CoreException] always records `result=error`.
     */
    private suspend inline fun <T> authTimed(
        event: String,
        section: String,
        crossinline result: (T) -> String = { "ok" },
        crossinline block: suspend JellybeamCoreInterface.() -> T,
    ): T = withContext(Dispatchers.IO) {
        val startNs = System.nanoTime()
        try {
            val value = PerfLog.timed(section) { core.await().block() }
            AppGraph.diag.event(event) {
                ms("ms", (System.nanoTime() - startNs) / 1_000_000)
                tag("result", result(value))
            }
            value
        } catch (e: CoreException) {
            AppGraph.diag.event(event) {
                ms("ms", (System.nanoTime() - startNs) / 1_000_000)
                tag("result", "error")
            }
            throw e
        }
    }

    override suspend fun restoreSession(): AccountInfo? = accountChange {
        authTimed("auth.restore", "ffi.restoreSession", result = { if (it != null) "ok" else "none" }) {
            restoreSession()
        }
    }

    override suspend fun signIn(serverUrl: String, username: String, password: String): AccountInfo =
        accountChange { authTimed("auth.signin", "ffi.signIn") { signIn(serverUrl, username, password) } }

    override suspend fun reauthorizeSession(index: UInt, username: String, password: String): AccountInfo =
        accountChange {
            authTimed("auth.reauth", "ffi.reauthorizeSession") {
                reauthorizeSession(index, username, password).also(::noteReauthorized)
            }
        }

    override suspend fun initiateQuickConnect(serverUrl: String): QuickConnectSession =
        ffi("ffi.initiateQuickConnect") { initiateQuickConnect(serverUrl) }

    override suspend fun pollQuickConnect(serverUrl: String, secret: String): Boolean =
        ffi("ffi.pollQuickConnect") { pollQuickConnect(serverUrl, secret) }

    override suspend fun completeQuickConnect(serverUrl: String, secret: String): AccountInfo =
        accountChange { ffi("ffi.completeQuickConnect") { completeQuickConnect(serverUrl, secret) } }

    override suspend fun completeQuickConnectReauthorization(index: UInt, secret: String): AccountInfo =
        accountChange {
            ffi("ffi.completeQuickConnectReauthorization") {
                completeQuickConnectReauthorization(index, secret).also(::noteReauthorized)
            }
        }

    /** docs/18 §2.1: runs inside the IO block as the FFI returns, so a caller cancelled meanwhile still opens the quiet window. */
    private fun noteReauthorized(account: AccountInfo) =
        authorizationRecovery.noteReauthorized(AccountIdentity(serverUrl = account.serverUrl, userId = account.userId))

    override suspend fun discoverServers(): List<DiscoveredServer> =
        ffi("ffi.discoverServers") { discoverServers() }

    override suspend fun listAccounts(): List<AccountInfo> =
        ffi("ffi.listAccounts") { listAccounts() }

    override suspend fun activeAccountIndex(): UInt? =
        ffi("ffi.activeAccountIndex") { activeAccountIndex() }

    override suspend fun serverVersion(): String? =
        ffi("ffi.serverVersion") { serverVersion() }

    override suspend fun serverAtLeast(major: UInt, minor: UInt): Boolean =
        ffi("ffi.serverAtLeast") { serverAtLeast(major, minor) }

    override suspend fun switchSession(index: UInt): AccountInfo =
        accountChange { ffi("ffi.switchSession") { switchSession(index) } }

    override suspend fun removeSession(index: UInt): Boolean =
        accountChange { ffi("ffi.removeSession") { removeSession(index) } }

    override suspend fun openMirror() {
        // docs/07 §5: the listener must be live before the mirror opens, or the first views events are lost.
        changeListenerRegistration.join()
        val epoch = epochs.value.epoch
        try {
            ffi("ffi.openMirror") { openMirror() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Fail open (docs/05), but never keep the previous account's libraries on screen; a newer account's stay (docs/18 §2.1).
            if (epochs.value.epoch == epoch) _viewsState.value = emptyList()
            throw e
        }
        refreshViews()
    }

    private val _viewsState = MutableStateFlow<List<ViewSnapshot>>(emptyList())
    override val viewsState: StateFlow<List<ViewSnapshot>> = _viewsState.asStateFlow()

    /** Serialises the read-then-publish, so a slower read never publishes an older list over a newer. */
    private val viewsRefresh = Mutex()

    /** One list read published to [viewsState]; dropped when the account moved while it was in flight. */
    private suspend fun refreshViews() = viewsRefresh.withLock {
        val epoch = epochs.value.epoch
        val fresh = runCatchingCancellable { views() }.getOrNull() ?: return@withLock
        if (epochs.value.epoch == epoch) _viewsState.value = fresh
    }

    override suspend fun setDeviceCaps(caps: DeviceCaps) =
        ffi("ffi.setDeviceCaps") { setDeviceCaps(caps) }

    override suspend fun homeSnapshot(layout: HomeLayout): HomeSnapshot =
        ffi("ffi.homeSnapshot") { homeSnapshot(layout) }

    override suspend fun isSyncing(): Boolean =
        ffi("ffi.isSyncing") { isSyncing() }

    override suspend fun syncStatus(): SyncStatus =
        ffi("ffi.syncStatus") { syncStatus() }

    override suspend fun serverInfoSnapshot(): ServerInfoSnapshot =
        ffi("ffi.serverInfoSnapshot") { serverInfoSnapshot() }

    override suspend fun fetchServerDetails(): ServerDetails =
        ffi("ffi.fetchServerDetails") { fetchServerDetails() }

    override suspend fun children(parentId: String, sort: SortOrder, offset: UInt, limit: UInt): List<Card> =
        ffi("ffi.children") { childrenRead(parentId, sort, offset, limit) }.rowsOrThrow()

    override suspend fun liveChildren(parentId: String, startIndex: UInt, limit: UInt, sort: LiveSort): List<Card> =
        ffi("ffi.liveChildren") { liveChildren(parentId, startIndex, limit, sort) }

    override suspend fun libraryGrid(viewId: String, sort: GridSort, filters: GridFilters, offset: UInt, limit: UInt): List<Card>? =
        ffi("ffi.libraryGrid") { libraryGrid(viewId, sort, filters, offset, limit) }

    override suspend fun libraryGridCounts(viewId: String, filters: GridFilters): GridCounts? =
        ffi("ffi.libraryGridCounts") { libraryGridCounts(viewId, filters) }

    override suspend fun libraryGridGroups(viewId: String, sort: GridSort, filters: GridFilters): List<GridGroup>? =
        ffi("ffi.libraryGridGroups") { libraryGridGroups(viewId, sort, filters) }

    override suspend fun libraryGenres(viewId: String): List<String> =
        ffi("ffi.libraryGenres") { libraryGenres(viewId) }

    override suspend fun getLibraryGridPrefs(viewId: String): LibraryGridPrefs =
        ffi("ffi.getLibraryGridPrefs") { getLibraryGridPrefs(viewId) }

    override suspend fun setLibraryGridPrefs(viewId: String, prefs: LibraryGridPrefs) =
        ffi("ffi.setLibraryGridPrefs") { setLibraryGridPrefs(viewId, prefs) }

    override suspend fun views(): List<ViewSnapshot> =
        ffi("ffi.views") { views() }

    override suspend fun hasFavorites(): Boolean =
        ffi("ffi.hasFavorites") { hasFavorites() }

    override suspend fun favoriteItemTypes(): List<String> =
        ffi("ffi.favoriteItemTypes") { favoriteItemTypes() }

    override suspend fun search(query: String, limit: UInt): List<Card> =
        ffi("ffi.search") { search(query, limit) }

    override suspend fun getItemDetail(itemId: String, accountEpoch: ULong?): ItemDetail =
        ffi("ffi.getItemDetail") { getItemDetail(itemId, accountEpoch) }

    override suspend fun itemDetailLocal(itemId: String): ItemDetail? =
        ffi("ffi.itemDetailLocal") { itemDetailLocal(itemId) }

    override suspend fun cardById(itemId: String): Card? =
        ffi("ffi.cardById") { cardById(itemId) }

    override suspend fun cardsByIds(itemIds: List<String>): List<Card> =
        ffi("ffi.cardsByIds") { cardsByIds(itemIds) }

    override suspend fun getPlaybackOsdDetail(itemId: String, mediaSourceId: String, accountEpoch: ULong?): PlaybackOsdDetail =
        ffi("ffi.getPlaybackOsdDetail") { getPlaybackOsdDetail(itemId, mediaSourceId, accountEpoch) }

    override suspend fun getSimilar(itemId: String, limit: UInt): List<Card> =
        ffi("ffi.getSimilar") { getSimilar(itemId, limit) }

    override suspend fun getMediaSegments(itemId: String, accountEpoch: ULong?): List<MediaSegment> =
        ffi("ffi.getMediaSegments") { getMediaSegments(itemId, accountEpoch) }

    override fun outroStartSecsFromSegments(segments: List<MediaSegment>): Double? =
        uniffi.jellybeam_core.outroStartSecsFromSegments(segments)

    override suspend fun getSettings(): Settings =
        ffi("ffi.getSettings") { getSettings() }

    override suspend fun setSettings(settings: Settings) =
        ffi("ffi.setSettings") { setSettings(settings) }

    /** Count-only accumulator for [imageUrl]'s FFI path (another account's epoch), which can still
     * run per card, so a per-call [PerfLog.timed] line would itself be the overhead measured.
     */
    private val imageUrlPerf = PerfAccumulator(label = "ffi.imageUrl", outlierMs = 5.0)

    /** The published account's URL base (docs/18 §2.1): a base whose epoch isn't the published one is
     * never returned or cached, because the on-screen tree belongs to the published account until the
     * next epoch publishes. Dropped when an account call ends; written under [urlBaseLock] so a fetch
     * racing that drop can't keep a stale token.
     */
    @Volatile
    private var urlBase: ImageUrlBase? = null
    private val urlBaseLock = Any()

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun currentUrlBase(): ImageUrlBase? {
        val published = accountEpochs.value.epoch
        urlBase?.let { if (it.epoch == published) return it }
        synchronized(urlBaseLock) {
            urlBase?.let { if (it.epoch == published) return it }
            return freshUrlBase()?.takeIf { it.epoch == published }?.also { urlBase = it }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun freshUrlBase(): ImageUrlBase? =
        core.getCompleted().imageUrlPrefix()?.let { ImageUrlBase(it.epoch, it.baseUrl, it.token) }

    /** Not `suspend`, so can't `await()` [core]; the [isCompleted] check fails open to the
     * placeholder tile instead of [Deferred.getCompleted]'s `IllegalStateException`. The current
     * account's URLs are formatted in Kotlin ([ImageUrls]); a player's other epoch crosses FFI.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun imageUrl(itemId: String, kind: ImageKind, tag: String, maxWidth: UInt, accountEpoch: ULong?): String? {
        if (!core.isCompleted) return null
        if (accountEpoch == null) return currentUrlBase()?.url(itemId, kind, tag, maxWidth)
        return imageUrlViaCore(itemId, kind, tag, maxWidth, accountEpoch)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun imageUrlViaCore(itemId: String, kind: ImageKind, tag: String, maxWidth: UInt, accountEpoch: ULong?): String? {
        if (!PerfLog.enabled) {
            return try {
                core.getCompleted().imageUrl(itemId, kind, tag, maxWidth, accountEpoch)
            } catch (_: CoreException) {
                null
            }
        }
        val startNs = System.nanoTime()
        val result = try {
            core.getCompleted().imageUrl(itemId, kind, tag, maxWidth, accountEpoch)
        } catch (_: CoreException) {
            null
        }
        val ms = (System.nanoTime() - startNs) / 1_000_000.0
        imageUrlPerf.record(durationMs = ms, success = result != null, detail = itemId)
        return result
    }

    private val lastPlaybackSeq = AtomicLong(0)

    private val accountRestored = CompletableDeferred<Unit>()

    /** Serialises the read-both-then-write-both in [accountChange], so a slower call tail never publishes an older pair over a newer one (docs/18 §2.1). */
    private val epochLock = Any()

    /** The pair as the core reported it, written only under [epochLock]: readers see one value, never a torn pair. */
    private val epochs = MutableStateFlow(AccountEpochs(epoch = 0uL, parked = null))
    override val accountEpochs: StateFlow<AccountEpochs> = epochs.asStateFlow()

    override fun mintPlaybackRequest(accountEpoch: ULong?): PlaybackRequest =
        PlaybackRequest(seq = lastPlaybackSeq.incrementAndGet().toULong(), accountEpoch = accountEpoch ?: epochs.value.epoch)

    override suspend fun reauthorizationAccount(rejected: AccountIdentity, failedPlayback: Boolean): AccountInfo? =
        ffi("ffi.reauthorizationAccount") { reauthorizationAccount(rejected, failedPlayback) }

    override fun playbackOwnershipOpen(accountEpoch: ULong): Boolean =
        epochs.value.let { accountEpoch == it.epoch || accountEpoch == it.parked }

    override suspend fun playbackOwnershipOpenNow(accountEpoch: ULong): Boolean =
        ffi("ffi.accountEpochs") { accountEpochs() }.let { accountEpoch == it.epoch || accountEpoch == it.parked }

    override suspend fun awaitAccountRestored() = accountRestored.await()

    override fun accountRestoredNow(): Boolean = accountRestored.isCompleted

    /** Re-reads and publishes the core's epochs after the call, even if it failed part-way (the core
     * may already have reset the account) or its caller was cancelled.
     */
    private suspend inline fun <T> accountChange(crossinline call: suspend () -> T): T {
        try {
            return call()
        } finally {
            // Inside NonCancellable: a cancelled caller still reopens ownership (a resume after the
            // dispatcher switch would throw before a statement placed after this block).
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    runCatching {
                        val core = core.await()
                        synchronized(epochLock) {
                            // One core read, one write: the pair is never torn by an account change in between.
                            epochs.value = core.accountEpochs()
                        }
                        synchronized(urlBaseLock) { urlBase = null }
                    }
                } finally {
                    accountRestored.complete(Unit)
                }
            }
        }
    }

    override suspend fun preparePlayback(itemId: String, startFromBeginning: Boolean, request: PlaybackRequest): PlaybackPlan =
        withContext(Dispatchers.IO) {
            PerfLog.timed("gateway.preparePlayback.total") {
                deviceCapsReady?.await()
                PerfLog.timed("ffi.preparePlayback") { core.await().preparePlayback(itemId, startFromBeginning, request) }
            }
        }

    override suspend fun prepareTranscodeFallback(
        itemId: String,
        positionTicks: Long,
        reason: String,
        playSessionId: String,
        failed: FailedTrackFfi?,
        subtitleStreamIndex: Int?,
        request: PlaybackRequest,
    ): PlaybackPlan =
        ffi("ffi.prepareTranscodeFallback", reauthorize = false) {
            prepareTranscodeFallback(itemId, positionTicks, reason, playSessionId, failed, subtitleStreamIndex, request)
        }

    override suspend fun preloadPlayback(itemId: String, accountEpoch: ULong?) =
        withContext(Dispatchers.IO) {
            deviceCapsReady?.await()
            PerfLog.timed("ffi.preloadPlayback") { core.await().preloadPlayback(itemId, accountEpoch) }
        }

    override suspend fun nextEpisodeAfter(itemId: String): Card? =
        ffi("ffi.nextEpisodeAfter") { nextEpisodeAfter(itemId) }

    override suspend fun seriesEpisodes(seriesId: String): List<Card> =
        ffi("ffi.seriesEpisodes") { seriesEpisodesRead(seriesId) }.rowsOrThrow()

    override suspend fun episodeNeighbors(itemId: String, seriesId: String, accountEpoch: ULong?): EpisodeNeighbors? =
        ffi("ffi.episodeNeighbors") { episodeNeighbors(itemId, seriesId, accountEpoch) }

    override suspend fun serverDisplayName(accountEpoch: ULong?): String? =
        ffi("ffi.serverDisplayName") { serverDisplayName(accountEpoch) }

    override fun nextEpisodeTriggerRemainingSecs(
        durationSecs: Double,
        outroStartSecs: Double?,
        outroAutoSkip: Boolean,
        countdownSecs: Double,
    ): Double =
        uniffi.jellybeam_core.nextEpisodeTriggerRemainingSecs(durationSecs, outroStartSecs, outroAutoSkip, countdownSecs)

    override fun nextEpisodeCountdownTotal(remainingSecs: Double, delaySecs: Double): Double =
        uniffi.jellybeam_core.nextEpisodeCountdownTotal(remainingSecs, delaySecs)

    override suspend fun notePlayerInput(nowMs: ULong) =
        ffi("ffi.notePlayerInput") { notePlayerInput(nowMs) }

    override suspend fun noteEpisodeFinished(nowMs: ULong): StillWatchingDecision =
        ffi("ffi.noteEpisodeFinished") { noteEpisodeFinished(nowMs) }

    override suspend fun resetStillWatching(nowMs: ULong) =
        ffi("ffi.resetStillWatching") { resetStillWatching(nowMs) }

    override suspend fun reportPosition(playSessionId: String, ticks: Long) =
        ffi("ffi.reportPosition") { reportPosition(playSessionId, ticks) }

    override suspend fun reportPaused(playSessionId: String, paused: Boolean) =
        ffi("ffi.reportPaused") { reportPaused(playSessionId, paused) }

    override suspend fun stopPlayback(playSessionId: String, positionTicks: Long) =
        ffi("ffi.stopPlayback") { stopPlayback(playSessionId, positionTicks) }

    override suspend fun abandonPlayback(playSessionId: String) =
        ffi("ffi.abandonPlayback") { abandonPlayback(playSessionId) }

    override suspend fun resolveTracks(seriesId: String?, tracks: List<TrackInfo>): TrackDecisionFfi =
        ffi("ffi.resolveTracks") { resolveTracks(seriesId, tracks) }

    override suspend fun rememberTrackMemory(seriesId: String, kind: TrackKindFfi, memory: TrackMemoryFfi) =
        ffi("ffi.rememberTrackMemory") { rememberTrackMemory(seriesId, kind, memory) }

    override fun trackPrefKeyOf(track: TrackInfo): String? = uniffi.jellybeam_core.trackPrefKeyOf(track)

    override suspend fun trickplayTileUrl(itemId: String, width: UInt, imageIndex: UInt, accountEpoch: ULong?): String? =
        ffi("ffi.trickplayTileUrl") { trickplayTileUrl(itemId, width, imageIndex, accountEpoch) }

    override suspend fun fetchExternalSubtitle(playSessionId: String, index: Int): String? =
        ffi("ffi.fetchExternalSubtitle") { fetchExternalSubtitle(playSessionId, index) }

    override suspend fun loadAssSidecar(playSessionId: String, index: Int, overlay: AssOverlay, key: String, item: ULong): Boolean =
        ffi("ffi.loadAssSidecar") { loadAssSidecar(playSessionId, index, overlay, key, item) }

    override fun trickplayLocate(meta: TrickplayMetaFfi, positionMs: ULong): TrickplayTileFfi? =
        uniffi.jellybeam_core.trickplayLocate(meta, positionMs)

    override fun trickplayGlideSample(meta: TrickplayMetaFfi, targetMs: ULong, nowMs: ULong, lastSampleMs: ULong?, direction: GlideDirection): TrickplayTileFfi? =
        uniffi.jellybeam_core.trickplayGlideSample(meta, targetMs, nowMs, lastSampleMs, direction)

    override fun trickplayLocateBiased(meta: TrickplayMetaFfi, positionMs: ULong, direction: GlideDirection): TrickplayTileFfi? =
        uniffi.jellybeam_core.trickplayLocateBiased(meta, positionMs, direction)

    override fun trickplayGlideSheets(meta: TrickplayMetaFfi, targetMs: ULong, rate: UInt, direction: GlideDirection): List<UInt> =
        uniffi.jellybeam_core.trickplayGlideSheets(meta, targetMs, rate, direction)

    override fun trickplayShouldAbandonSheet(meta: TrickplayMetaFfi, sheet: UInt, targetMs: ULong, direction: GlideDirection): Boolean =
        uniffi.jellybeam_core.trickplayShouldAbandonSheet(meta, sheet, targetMs, direction)

    override fun glideEndClampMs(durationMs: ULong): ULong = uniffi.jellybeam_core.glideEndClampMs(durationMs)

    /** The one shared broadcast [changeEvents] reads from. `replay = 0`: a late collector gets
     * fresh state from its own initial fetch. [BufferOverflow.DROP_OLDEST]: no data is lost since
     * every collector re-reads current state rather than applying an incremental patch.
     */
    private val _changeEvents = MutableSharedFlow<ChangeEvent>(
        replay = 0,
        extraBufferCapacity = CHANGE_EVENTS_EXTRA_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Registers the single core-side [ChangeListener] exactly once, on first [changeEvents]
     * collection (`by lazy`), waiting for [core] on [backgroundScope] rather than blocking.
     */
    private val changeListenerRegistration: Job by lazy {
        backgroundScope.launch(Dispatchers.IO) {
            val readyCore = core.await()
            readyCore.setChangeListener(object : ChangeListener {
                override fun onChange(event: ChangeEvent) {
                    // Called from a JellybeamCore-owned background thread; tryEmit is thread-safe.
                    _changeEvents.tryEmit(event)
                    if (refreshesViews(event)) backgroundScope.launch { refreshViews() }
                    // docs/21 §2.1: one line per event, never the ids themselves.
                    AppGraph.diag.event("sync.change") {
                        tag("kind", changeEventKind(event))
                        num("n", changeEventCount(event).toLong())
                    }
                }

                // docs/18 §2.1: the socket's 401 asks to re-authorize; it must not call the core, whose teardown may be joining this thread.
                override fun onUnauthorized(account: AccountIdentity) {
                    AppGraph.diag.event("auth.socket_rejected")
                    authorizationRecovery.request(account)
                }
            })
        }
    }

    override fun changeEvents(): Flow<ChangeEvent> {
        changeListenerRegistration
        return _changeEvents.asSharedFlow()
    }

    // -- Detail action menu (docs/19-detail-action-menu.md §2.3) --------------

    override suspend fun setPlayed(itemId: String, played: Boolean) =
        ffi("ffi.setPlayed") { setPlayed(itemId, played) }

    override suspend fun setPlayedRecursive(scopeId: String, played: Boolean) =
        ffi("ffi.setPlayedRecursive") { setPlayedRecursive(scopeId, played) }

    override suspend fun setFavorite(itemId: String, favorite: Boolean) =
        ffi("ffi.setFavorite") { setFavorite(itemId, favorite) }

    override suspend fun listCollections(): List<CollectionInfo> =
        ffi("ffi.listCollections") { listCollections() }

    override suspend fun addToCollection(collectionId: String, itemId: String) =
        ffi("ffi.addToCollection") { addToCollection(collectionId, itemId) }

    override suspend fun collectionIdsContaining(itemId: String): List<String> =
        ffi("ffi.collectionIdsContaining") { collectionIdsContaining(itemId) }

    override suspend fun refreshMetadata(itemId: String) =
        ffi("ffi.refreshMetadata") { refreshMetadata(itemId) }

    override suspend fun validateSession() =
        ffi("ffi.validateSession") { validateSession() }

    override suspend fun onForeground() = ffi("ffi.onForeground") { onForeground() }

    override suspend fun isAdministrator(): Boolean =
        ffi("ffi.isAdministrator") { isAdministrator() }

    // -- Seerr Discover (docs/14-seerr-discover.md) --------------------------

    override suspend fun seerrStatus(): SeerrStatus =
        ffi("ffi.seerrStatus") { seerrStatus() }

    override suspend fun seerrConnect(url: String, method: SeerrAuthMethod, identity: String, secret: String): SeerrStatus =
        ffi("ffi.seerrConnect") { seerrConnect(url, method, identity, secret).also { bumpSeerrRevision() } }

    override suspend fun seerrDisconnect() =
        ffi("ffi.seerrDisconnect") { seerrDisconnect(); bumpSeerrRevision() }

    override suspend fun seerrHome(): SeerrHome =
        ffi("ffi.seerrHome") { seerrHome() }

    override suspend fun seerrBrowse(kind: SeerrBrowseKind, page: Int, filters: SeerrBrowseFilters): SeerrPage =
        ffi("ffi.seerrBrowse") { seerrBrowse(kind, page, filters) }

    override suspend fun seerrGenres(mediaType: SeerrMediaType): List<SeerrGenre> =
        ffi("ffi.seerrGenres") { seerrGenres(mediaType) }

    override suspend fun seerrSearch(query: String, page: Int): SeerrPage =
        ffi("ffi.seerrSearch") { seerrSearch(query, page) }

    override suspend fun seerrMovie(tmdbId: Long): SeerrMovieDetail =
        ffi("ffi.seerrMovie") { seerrMovie(tmdbId) }

    override suspend fun seerrTv(tmdbId: Long): SeerrTvDetail =
        ffi("ffi.seerrTv") { seerrTv(tmdbId) }

    override suspend fun seerrPerson(personId: Long): SeerrPersonCredits =
        ffi("ffi.seerrPerson") { seerrPerson(personId) }

    override suspend fun getPersonPage(personId: String): PersonPage =
        ffi("ffi.getPersonPage") { getPersonPage(personId) }

    override suspend fun personDiscoverCredits(personId: String, tmdbPersonId: Long, inLibrary: List<TitleTmdbRef>?): List<SeerrCard> =
        ffi("ffi.seerrPersonDiscoverCredits") { personDiscoverCredits(personId, tmdbPersonId, inLibrary) }

    override suspend fun seerrRequestOptions(mediaType: SeerrMediaType, is4k: Boolean): SeerrRequestOptions =
        ffi("ffi.seerrRequestOptions") { seerrRequestOptions(mediaType, is4k) }

    // The bump runs inside the IO block, so a caller cancelled mid-call still announces the change (docs/14).
    override suspend fun seerrSubmitRequestDetail(input: SeerrRequestInput): SeerrRequestDetail =
        ffi("ffi.seerrSubmitRequestDetail") { announcingChange { seerrSubmitRequestDetail(input) } }

    override suspend fun seerrCancelRequestDetail(mediaType: SeerrMediaType, tmdbId: Long, requestId: Long): SeerrRequestDetail =
        ffi("ffi.seerrCancelRequestDetail") { announcingChange { seerrCancelRequestDetail(mediaType, tmdbId, requestId) } }

    override suspend fun seerrMyRequests(): List<SeerrMyRequest> =
        ffi("ffi.seerrMyRequests") { seerrMyRequests() }

    private val _seerrRevision = MutableStateFlow(0L)
    override val seerrRevision: StateFlow<Long> = _seerrRevision.asStateFlow()

    /** The one bump site: every Seerr connect/disconnect/request/cancel that changed the server moves it (docs/14). */
    private fun bumpSeerrRevision() = _seerrRevision.update { it + 1 }

    /** Runs a submit/cancel and bumps when it succeeded or landed with its detail unreadable (docs/14 Errors). */
    private inline fun <T> announcingChange(call: () -> T): T =
        try {
            call().also { bumpSeerrRevision() }
        } catch (e: CoreException.SeerrChangeUnconfirmed) {
            bumpSeerrRevision()
            throw e
        }
}

/** docs/07 §5: a merged [ChangeEvent.Refresh] may carry a views change, so it re-reads the list too. */
internal fun refreshesViews(event: ChangeEvent): Boolean =
    event is ChangeEvent.ViewsChanged || event is ChangeEvent.Refresh

/** Extra buffer slots for [RealCoreGateway]'s shared change-event flow. */
private const val CHANGE_EVENTS_EXTRA_BUFFER = 8

/** [ChangeEvent]'s `sync.change` `kind` tag (docs/21 §2.1) -- a fixed name per variant, never the
 * event's own ids. */
private fun changeEventKind(event: ChangeEvent): String = when (event) {
    is ChangeEvent.Upserted -> "upserted"
    is ChangeEvent.Removed -> "removed"
    ChangeEvent.ViewsChanged -> "views_changed"
    ChangeEvent.Refresh -> "refresh"
}

/** [ChangeEvent]'s `sync.change` `n` field: the id count for the two list-carrying variants, `0`
 * for the two that carry none. */
private fun changeEventCount(event: ChangeEvent): Int = when (event) {
    is ChangeEvent.Upserted -> event.ids.size
    is ChangeEvent.Removed -> event.ids.size
    ChangeEvent.ViewsChanged, ChangeEvent.Refresh -> 0
}
