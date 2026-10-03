package tv.jellybeam.data

import tv.jellybeam.AppGraph
import tv.jellybeam.perf.PerfAccumulator
import tv.jellybeam.perf.PerfLog
import tv.jellybeam.player.authorizationRecoveryCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.jellybeam_core.AccountInfo
import uniffi.jellybeam_core.Card
import uniffi.jellybeam_core.ChangeEvent
import uniffi.jellybeam_core.ChangeListener
import uniffi.jellybeam_core.CollectionInfo
import uniffi.jellybeam_core.CoreException
import uniffi.jellybeam_core.DeviceCaps
import uniffi.jellybeam_core.DiscoveredServer
import uniffi.jellybeam_core.EpisodeNeighbors
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
import uniffi.jellybeam_core.PlaybackOsdDetail
import uniffi.jellybeam_core.PlaybackPlan
import uniffi.jellybeam_core.QuickConnectSession
import uniffi.jellybeam_core.SeerrAuthMethod
import uniffi.jellybeam_core.SeerrBrowseFilters
import uniffi.jellybeam_core.SeerrBrowseKind
import uniffi.jellybeam_core.SeerrGenre
import uniffi.jellybeam_core.SeerrHome
import uniffi.jellybeam_core.SeerrMediaType
import uniffi.jellybeam_core.SeerrMovieDetail
import uniffi.jellybeam_core.SeerrMyRequest
import uniffi.jellybeam_core.SeerrPage
import uniffi.jellybeam_core.SeerrPersonCredits
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
import uniffi.jellybeam_core.TrackDecisionFfi
import uniffi.jellybeam_core.TrackInfo
import uniffi.jellybeam_core.TrackKindFfi
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

    /** One bounded capability request; no polling or retained native work. */
    @Throws(CoreException::class)
    suspend fun quickConnectEnabled(serverUrl: String): Boolean

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

    suspend fun signOut()

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

    /** A bounded slice of a parent's mirror-backed children; library grids page through this. */
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
    suspend fun getItemDetail(itemId: String): ItemDetail

    /** Mirror-only lookup of one item's full [Card] by bare id, `null` if not in the local mirror.
     * Backs Discover's "Go to library" routing (docs/14-seerr-discover.md).
     */
    @Throws(CoreException::class)
    suspend fun cardById(itemId: String): Card?

    /** Narrow post-load stream/chapter enrichment; never collector metadata. */
    @Throws(CoreException::class)
    suspend fun getPlaybackOsdDetail(itemId: String): PlaybackOsdDetail

    /** The "Similar Titles" row (docs/11 tier 2 item 13): up to [limit] [Card]s from a live,
     * non-mirror-backed `GetSimilar` call, fetched alongside the Detail paint.
     */
    @Throws(CoreException::class)
    suspend fun getSimilar(itemId: String, limit: UInt): List<Card>

    /** Skip-intro/credits markers for [itemId] (docs/12 "Skip intro/credits"). Fails open to
     * an empty list; a markers fetch must never block or error out playback.
     */
    suspend fun getMediaSegments(itemId: String): List<MediaSegment>

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

    fun imageUrl(itemId: String, kind: ImageKind, tag: String, maxWidth: UInt): String?

    /** Negotiates playback for [itemId] and starts a fresh reporting session. Direct Play only
     * (CLAUDE.md hard rule): throws [CoreException.WouldTranscode] instead of returning a plan.
     * [startFromBeginning] (docs/11 item 11 pill) ignores any saved resume position.
     */
    @Throws(CoreException::class)
    suspend fun preparePlayback(itemId: String, startFromBeginning: Boolean): PlaybackPlan

    /**
     * The Auto/Cap quality modes' one fallback per item (docs/18 §1/§2): renegotiates [itemId]
     * as a server transcode from [positionTicks] after direct play proves unplayable. [reason]
     * is free text surfaced via [tv.jellybeam.player.PlaybackUiState.transcodeReason]. Refuses with
     * [CoreException.WouldTranscode] when `Settings.playbackQuality` is `DirectPlay`.
     * [playSessionId] guards staleness (§2): throws [CoreException.StalePlaybackSession] if
     * superseded.
     */
    @Throws(CoreException::class)
    suspend fun prepareTranscodeFallback(itemId: String, positionTicks: Long, reason: String, playSessionId: String): PlaybackPlan

    /**
     * Focus-dwell preload (`Settings.preloadOnFocus`, default on): best-effort playback-handshake
     * prefetch for [itemId], fired by [tv.jellybeam.ui.cards.PreloadOnDwell] once a card has held
     * focus long enough. Metadata/negotiation only, never media bytes or a reporting session, and
     * never throws -- a no-op when the setting is off or the negotiation fails. Any fresh result
     * is silently consumed by the next [preparePlayback] call within the core's cache TTL.
     */
    suspend fun preloadPlayback(itemId: String)

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
     * every season individually. Empty, never a throw, if the mirror isn't open or has none yet.
     */
    suspend fun seriesEpisodes(seriesId: String): List<Card>

    /**
     * Mirror-local previous-episode lookup for the OSD's "Previous episode" button (docs/12 §6
     * row 1), like [nextEpisodeAfter] but walking backward. `null` if [itemId] isn't a real
     * `Episode`, has no known series, or is already first.
     */
    suspend fun previousEpisodeBefore(itemId: String): Card?

    /** Both episode-edge controls from one lookup; the Rust side prefers the mirror, falling
     * back to one live series request only when the episode can't be placed locally.
     */
    suspend fun episodeNeighbors(itemId: String, seriesId: String): EpisodeNeighbors?

    /** The playback stats sheet's SOURCE row (docs/12 §8b): derived from the active session's
     * server URL, no network round trip. `null` if signed out or the URL has no parseable host.
     */
    suspend fun serverDisplayName(): String?

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

    /** Forwards a position update (ticks) to the active reporting session; a no-op if there isn't
     * one.
     */
    suspend fun reportPosition(ticks: Long)

    /** Forwards a pause/resume edge to the active reporting session; a no-op if there isn't one. */
    suspend fun reportPaused(paused: Boolean)

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

    /** Persists (or clears, when [trackKey] is `null`) [seriesId]'s remembered track choice,
     * consumed by the next [resolveTracks] call. [trackKey] must already be [trackPrefKeyOf]'s
     * result.
     */
    suspend fun rememberTrackChoice(seriesId: String, kind: TrackKindFfi, trackKey: String?)

    /** Pure delegate to `track_pref_key_of`: the persistence key a [TrackInfo] is remembered under
     * (language, else title). No I/O; not `suspend`.
     */
    fun trackPrefKeyOf(track: TrackInfo): String?

    /** Trickplay tile-sheet image URL for [tv.jellybeam.player.TrickplayPreviewer], or `null` with no
     * signed-in client. `suspend` (unlike [imageUrl]): called once per debounced seek gesture.
     */
    suspend fun trickplayTileUrl(itemId: String, width: UInt, imageIndex: UInt): String?

    /** Trickplay scrub-preview manifest for [itemId]/[mediaSourceId], fetched fire-and-forget
     * right after `load()`. A seek before it resolves fails open to no preview tile.
     */
    suspend fun getTrickplay(itemId: String, mediaSourceId: String): TrickplayMetaFfi?

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

    /** Radarr/Sonarr instances (with profiles/root folders) available for a request at [is4k];
     * empty means the UI shows a plain Request button.
     */
    @Throws(CoreException::class)
    suspend fun seerrRequestOptions(mediaType: SeerrMediaType, is4k: Boolean): SeerrRequestOptions

    /** Creates or updates (POST-vs-PUT decided in Rust) a request. Kotlin is responsible for only
     * sending seasons it marked requestable.
     */
    @Throws(CoreException::class)
    suspend fun seerrSubmitRequest(input: SeerrRequestInput)

    /** Cancels (deletes) an existing request by its Seerr request id. */
    @Throws(CoreException::class)
    suspend fun seerrCancelRequest(requestId: Long)

    /** The signed-in Seerr account's own requests, each resolved to a full card -- a request this
     * app can't resolve a title for is omitted (fail open).
     */
    @Throws(CoreException::class)
    suspend fun seerrMyRequests(): List<SeerrMyRequest>
}

/**
 * [core] arrives as a not-yet-necessarily-ready [Deferred] (docs/10 `core.ready` phase):
 * construction happens off `Application.onCreate`, so every real call here waits for it.
 */
class RealCoreGateway(
    private val core: Deferred<JellybeamCoreInterface>,
    /** Only used for the one-time [changeEvents] listener registration; must be a
     * process-scoped, never-cancelled scope ([tv.jellybeam.AppGraph.processScope]).
     */
    private val backgroundScope: CoroutineScope,
    /** Completed after the one-time decoder probe has either landed or failed open. */
    private val deviceCapsReady: Deferred<Unit>? = null,
) : CoreGateway {
    override suspend fun updater(facts: uniffi.jellybeam_core.InstalledUpdateFacts): uniffi.jellybeam_core.AppUpdater =
        ffi("updater") { updater(facts) }

    /**
     * The one seam every ordinary FFI wrapper goes through: hop to [Dispatchers.IO], await
     * [core], and time the call under [section] (`ffi.<methodName>`, never derived from an
     * argument -- privacy rule). [imageUrl], [preparePlayback]/[preloadPlayback], [changeEvents]
     * and the pure delegates don't go through this; they're written out explicitly.
     */
    private suspend inline fun <T> ffi(section: String, crossinline op: suspend JellybeamCoreInterface.() -> T): T =
        withContext(Dispatchers.IO) {
            try {
                PerfLog.timed(section) { core.await().op() }
            } catch (e: CoreException) {
                noteCoreFailure(section, e)
                throw e
            }
        }

    /** Callers mostly fail open, so this is the one place a failed call is always recorded
     * (docs/21: section and variant only) and a dead token always reaches re-authorization.
     */
    private fun noteCoreFailure(section: String, error: CoreException) {
        AppGraph.diag.event("ffi.error") {
            tag("section", section)
            tag("kind", error.diagLabel())
        }
        if (routesToReauthorization(section, error)) authorizationRecoveryCoordinator.request()
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

    override suspend fun restoreSession(): AccountInfo? =
        authTimed("auth.restore", "ffi.restoreSession", result = { if (it != null) "ok" else "none" }) {
            restoreSession()
        }

    override suspend fun signIn(serverUrl: String, username: String, password: String): AccountInfo =
        authTimed("auth.signin", "ffi.signIn") { signIn(serverUrl, username, password) }

    override suspend fun reauthorizeSession(index: UInt, username: String, password: String): AccountInfo =
        authTimed("auth.reauth", "ffi.reauthorizeSession") { reauthorizeSession(index, username, password) }

    override suspend fun quickConnectEnabled(serverUrl: String): Boolean =
        ffi("ffi.quickConnectEnabled") { quickConnectEnabled(serverUrl) }

    override suspend fun initiateQuickConnect(serverUrl: String): QuickConnectSession =
        ffi("ffi.initiateQuickConnect") { initiateQuickConnect(serverUrl) }

    override suspend fun pollQuickConnect(serverUrl: String, secret: String): Boolean =
        ffi("ffi.pollQuickConnect") { pollQuickConnect(serverUrl, secret) }

    override suspend fun completeQuickConnect(serverUrl: String, secret: String): AccountInfo =
        ffi("ffi.completeQuickConnect") { completeQuickConnect(serverUrl, secret) }

    override suspend fun completeQuickConnectReauthorization(index: UInt, secret: String): AccountInfo =
        ffi("ffi.completeQuickConnectReauthorization") { completeQuickConnectReauthorization(index, secret) }

    override suspend fun discoverServers(): List<DiscoveredServer> =
        ffi("ffi.discoverServers") { discoverServers() }

    override suspend fun signOut() = withContext(Dispatchers.IO) {
        PerfLog.timed("ffi.signOut") { core.await().signOut() }
        AppGraph.diag.event("auth.signout")
    }

    override suspend fun listAccounts(): List<AccountInfo> =
        ffi("ffi.listAccounts") { listAccounts() }

    override suspend fun activeAccountIndex(): UInt? =
        ffi("ffi.activeAccountIndex") { activeAccountIndex() }

    override suspend fun serverVersion(): String? =
        ffi("ffi.serverVersion") { serverVersion() }

    override suspend fun serverAtLeast(major: UInt, minor: UInt): Boolean =
        ffi("ffi.serverAtLeast") { serverAtLeast(major, minor) }

    override suspend fun switchSession(index: UInt): AccountInfo =
        ffi("ffi.switchSession") { switchSession(index) }

    override suspend fun removeSession(index: UInt): Boolean =
        ffi("ffi.removeSession") { removeSession(index) }

    override suspend fun openMirror() =
        ffi("ffi.openMirror") { openMirror() }

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
        ffi("ffi.children") { children(parentId, sort, offset, limit) }

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

    override suspend fun getItemDetail(itemId: String): ItemDetail =
        ffi("ffi.getItemDetail") { getItemDetail(itemId) }

    override suspend fun cardById(itemId: String): Card? =
        ffi("ffi.cardById") { cardById(itemId) }

    override suspend fun getPlaybackOsdDetail(itemId: String): PlaybackOsdDetail =
        ffi("ffi.getPlaybackOsdDetail") { getPlaybackOsdDetail(itemId) }

    override suspend fun getSimilar(itemId: String, limit: UInt): List<Card> =
        ffi("ffi.getSimilar") { getSimilar(itemId, limit) }

    override suspend fun getMediaSegments(itemId: String): List<MediaSegment> =
        ffi("ffi.getMediaSegments") { getMediaSegments(itemId) }

    override fun outroStartSecsFromSegments(segments: List<MediaSegment>): Double? =
        uniffi.jellybeam_core.outroStartSecsFromSegments(segments)

    override suspend fun getSettings(): Settings =
        ffi("ffi.getSettings") { getSettings() }

    override suspend fun setSettings(settings: Settings) =
        ffi("ffi.setSettings") { setSettings(settings) }

    /** Count-only accumulator for [imageUrl], called dozens of times a second while scrolling,
     * so a per-call [PerfLog.timed] line would itself be the overhead being measured.
     */
    private val imageUrlPerf = PerfAccumulator(label = "ffi.imageUrl", outlierMs = 5.0)

    /** Not `suspend`, so can't `await()` [core]; the [isCompleted] check fails open to the
     * placeholder tile instead of [Deferred.getCompleted]'s `IllegalStateException`.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun imageUrl(itemId: String, kind: ImageKind, tag: String, maxWidth: UInt): String? {
        if (!core.isCompleted) return null
        if (!PerfLog.enabled) {
            return try {
                core.getCompleted().imageUrl(itemId, kind, tag, maxWidth)
            } catch (_: CoreException) {
                null
            }
        }
        val startNs = System.nanoTime()
        val result = try {
            core.getCompleted().imageUrl(itemId, kind, tag, maxWidth)
        } catch (_: CoreException) {
            null
        }
        val ms = (System.nanoTime() - startNs) / 1_000_000.0
        imageUrlPerf.record(durationMs = ms, success = result != null, detail = itemId)
        return result
    }

    override suspend fun preparePlayback(itemId: String, startFromBeginning: Boolean): PlaybackPlan =
        withContext(Dispatchers.IO) {
            PerfLog.timed("gateway.preparePlayback.total") {
                deviceCapsReady?.await()
                PerfLog.timed("ffi.preparePlayback") { core.await().preparePlayback(itemId, startFromBeginning) }
            }
        }

    override suspend fun prepareTranscodeFallback(itemId: String, positionTicks: Long, reason: String, playSessionId: String): PlaybackPlan =
        ffi("ffi.prepareTranscodeFallback") { prepareTranscodeFallback(itemId, positionTicks, reason, playSessionId) }

    override suspend fun preloadPlayback(itemId: String) =
        withContext(Dispatchers.IO) {
            deviceCapsReady?.await()
            PerfLog.timed("ffi.preloadPlayback") { core.await().preloadPlayback(itemId) }
        }

    override suspend fun nextEpisodeAfter(itemId: String): Card? =
        ffi("ffi.nextEpisodeAfter") { nextEpisodeAfter(itemId) }

    override suspend fun seriesEpisodes(seriesId: String): List<Card> =
        ffi("ffi.seriesEpisodes") { seriesEpisodes(seriesId) }

    override suspend fun previousEpisodeBefore(itemId: String): Card? =
        ffi("ffi.previousEpisodeBefore") { previousEpisodeBefore(itemId) }

    override suspend fun episodeNeighbors(itemId: String, seriesId: String): EpisodeNeighbors? =
        ffi("ffi.episodeNeighbors") { episodeNeighbors(itemId, seriesId) }

    override suspend fun serverDisplayName(): String? =
        ffi("ffi.serverDisplayName") { serverDisplayName() }

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

    override suspend fun reportPosition(ticks: Long) =
        ffi("ffi.reportPosition") { reportPosition(ticks) }

    override suspend fun reportPaused(paused: Boolean) =
        ffi("ffi.reportPaused") { reportPaused(paused) }

    override suspend fun stopPlayback(playSessionId: String, positionTicks: Long) =
        ffi("ffi.stopPlayback") { stopPlayback(playSessionId, positionTicks) }

    override suspend fun abandonPlayback(playSessionId: String) =
        ffi("ffi.abandonPlayback") { abandonPlayback(playSessionId) }

    override suspend fun resolveTracks(seriesId: String?, tracks: List<TrackInfo>): TrackDecisionFfi =
        ffi("ffi.resolveTracks") { resolveTracks(seriesId, tracks) }

    override suspend fun rememberTrackChoice(seriesId: String, kind: TrackKindFfi, trackKey: String?) =
        ffi("ffi.rememberTrackChoice") { rememberTrackChoice(seriesId, kind, trackKey) }

    override fun trackPrefKeyOf(track: TrackInfo): String? = uniffi.jellybeam_core.trackPrefKeyOf(track)

    override suspend fun trickplayTileUrl(itemId: String, width: UInt, imageIndex: UInt): String? =
        ffi("ffi.trickplayTileUrl") { trickplayTileUrl(itemId, width, imageIndex) }

    override suspend fun getTrickplay(itemId: String, mediaSourceId: String): TrickplayMetaFfi? =
        ffi("ffi.getTrickplay") { getTrickplay(itemId, mediaSourceId) }

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
                    // docs/21 §2.1: one line per event, never the ids themselves.
                    AppGraph.diag.event("sync.change") {
                        tag("kind", changeEventKind(event))
                        num("n", changeEventCount(event).toLong())
                    }
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

    override suspend fun isAdministrator(): Boolean =
        ffi("ffi.isAdministrator") { isAdministrator() }

    // -- Seerr Discover (docs/14-seerr-discover.md) --------------------------

    override suspend fun seerrStatus(): SeerrStatus =
        ffi("ffi.seerrStatus") { seerrStatus() }

    override suspend fun seerrConnect(url: String, method: SeerrAuthMethod, identity: String, secret: String): SeerrStatus =
        ffi("ffi.seerrConnect") { seerrConnect(url, method, identity, secret) }

    override suspend fun seerrDisconnect() =
        ffi("ffi.seerrDisconnect") { seerrDisconnect() }

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

    override suspend fun seerrRequestOptions(mediaType: SeerrMediaType, is4k: Boolean): SeerrRequestOptions =
        ffi("ffi.seerrRequestOptions") { seerrRequestOptions(mediaType, is4k) }

    override suspend fun seerrSubmitRequest(input: SeerrRequestInput) =
        ffi("ffi.seerrSubmitRequest") { seerrSubmitRequest(input) }

    override suspend fun seerrCancelRequest(requestId: Long) =
        ffi("ffi.seerrCancelRequest") { seerrCancelRequest(requestId) }

    override suspend fun seerrMyRequests(): List<SeerrMyRequest> =
        ffi("ffi.seerrMyRequests") { seerrMyRequests() }
}

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
