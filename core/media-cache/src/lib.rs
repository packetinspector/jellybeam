//! SQLite mirror. Disposable cache: schema_version
//! mismatch drops and rebuilds. Single-writer; readers never block on network.

use jellyfin_api::models::{BaseItemDto, UserItemDataDto};

#[cfg(test)]
mod mock_server;
mod pool;
mod query;
mod rows;
mod schema;
mod sync;
pub mod watch_grace;
mod writer;

/// Maps a view's `collection_type` to the root `item_type`(s) whose `date_created` reflects
/// "recently added" for that library, matching Jellyfin's Latest-Media behavior (a TV
/// library's Latest surfaces Episodes, not Series). Used by `query::latest` and by
/// `sync::reconcile_view` to scope reconciliation's local/server count comparison the same way.
pub(crate) fn item_types_for_collection(collection_type: &str) -> &'static [&'static str] {
    match collection_type {
        "movies" => &["Movie"],
        "tvshows" => &["Episode"],
        "boxsets" => &["BoxSet"],
        "music" => &["Audio"],
        "musicvideos" => &["MusicVideo"],
        "homevideos" => &["Video", "Photo"],
        _ => &[],
    }
}

/// Disposable cache: every bump below relies on drop-and-rebuild (schema.rs) to apply, since
/// an already-synced mirror can't be migrated in place.
///
/// - 1 -> 2: adds `collection_members` (BoxSet membership).
/// - 2 -> 3: forces a rebuild so mirrors pick up corrected Season/Episode `parent_id` values
///   (`rows::browse_parent_id`), which reconciliation alone would never notice.
/// - 3 -> 4 (docs/07 §2 art fallback chains): adds the artwork-fallback columns and
///   `idx_items_parent_order`.
/// - 4 -> 5: adds `library_id`, reshapes `idx_items_latest` to lead with it; forces a resync
///   so `latest()`'s per-library scoping doesn't misread `NULL` as unscoped.
/// - 5 -> 6: adds `last_played_date` and `idx_items_resume_by_last_played`; forces a resync
///   so `resume()` doesn't sort `NULL` as least-recently-played.
/// - 6 -> 7: adds `overview`/`CardRow::overview`; forces a resync to backfill.
/// - 7 -> 8: adds `is_virtual`/`CardRow::is_virtual`/`premiere_date`; forces a resync so
///   unaired/missing episodes aren't misreported as playable until then.
/// - 8 -> 9: adds `series_name`/`CardRow::series_name`, previously only in the FTS5 index;
///   forces a resync to backfill.
/// - 9 -> 10: widens `idx_items_resume_by_last_played` to add deterministic tiebreakers;
///   pure index change, still needs the rebuild path.
/// - 10 -> 11: adds `views.item_type`; `sync::current_views`/`reconcile_all` use it to
///   exclude Channel rows; forces a resync to backfill.
/// - 11 -> 12 (docs/16-library-sort-filter.md §1): adds `items.series_status`, the
///   `item_genres` table, and the grid sort indexes; forces a resync to backfill both.
/// - 12 -> 13: `sync::item_fields()` now requests `SortName` (previously unpopulated), and
///   the grid sort indexes trail `sort_name COLLATE NOCASE, id` for index-served ASC/DESC
///   (see `query::grid_sort_order`); forces a resync/rebuild for both.
/// - 13 -> 14: drops `items.community_rating`/`official_rating` -- written on every upsert
///   (`rows::extract_columns`) but read by no query (`ItemDetail`'s ratings come from a live
///   `BaseItemDto` fetch, not the mirror); pure column removal, drop-and-rebuild like every
///   bump above.
/// - 14 -> 15: `idx_items_browse` trails `sort_name COLLATE NOCASE` so `children()`'s
///   `Sort::NameAsc` (now also `COLLATE NOCASE`) stays index-served instead of a binary sort;
///   drops the dead `image_lru` table.
/// - 15 -> 16: the `search` FTS table drops `overview`, so search matches titles only (a
///   short prefix like "an" matched nearly every synopsis).
pub const SCHEMA_VERSION: u32 = 16;

/// docs/16 §2.7: the reserved `view_id` that scopes the library grid queries to every
/// favorite across libraries. Server ids are 32-hex, so it can never collide with one.
pub const FAVORITES_VIEW_ID: &str = "favorites";

/// Read connections held open per `Mirror`.
const READ_POOL_SIZE: usize = 4;

/// A library row mirrored from `/UserViews` into the `views` table.
#[derive(Debug, Clone)]
pub(crate) struct ViewRow {
    pub id: String,
    pub name: String,
    pub collection_type: String,
    /// The `/UserViews` entry's own `Type`, distinct from `collection_type`. See
    /// `SCHEMA_VERSION`'s 10 -> 11 note for why this exists.
    pub item_type: String,
}

/// One row of [`Mirror::views`] / `query::views` -- a browsable drawer entry. `ffi`'s
/// `ViewSnapshot` maps `item_type == "Channel"` to `ViewKind::Channel`, else `ViewKind::Library`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ViewSummary {
    pub id: String,
    pub name: String,
    pub item_type: String,
    /// docs/16-library-sort-filter.md §1.3: gates which views the library sort/filter UI
    /// applies to (`movies`/`tvshows` only; every other library keeps the plain grid).
    pub collection_type: String,
}

/// Settings-panel-driven `/Shows/NextUp` filtering. `media-cache` can't depend on `app`, so
/// the app pushes its current values down via [`Mirror::set_next_up_options`] instead of
/// this reading `AppSettings` directly, the same shape `MirrorState::playback_active` uses.
/// `sync::refresh_next_up` reads the stored value fresh on every call, so a settings change
/// takes effect on the next refresh without a dedicated "settings changed" signal.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct NextUpOptions {
    /// `Some(days)` sends `nextUpDateCutoff` on the next `/Shows/NextUp` request; `None`
    /// ("Off") omits the param.
    pub cutoff_days: Option<u32>,
    /// Mirrors `jellyfin_api::NextUpOptions::enable_rewatching`.
    pub rewatching: bool,
}

/// Shared state behind every `Mirror` clone: the single writer's command
/// queue, the read-only pool, the server connection (for the sync engine),
/// and the change-feed broadcaster.
pub(crate) struct MirrorState {
    pub(crate) client: jellyfin_api::JellyfinClient,
    pub(crate) writer: writer::WriterHandle,
    /// Arc'd so functions holding only `&MirrorState` can cheaply clone a handle into
    /// `spawn_blocking` (`pool.acquire()`'s blocking wait must not run inline on a tokio
    /// worker thread).
    pub(crate) read_pool: std::sync::Arc<pool::ReadPool>,
    /// `mirror.db`'s path, captured at [`Mirror::open`]; [`Mirror::db_size_bytes`] stats it
    /// (plus its `-wal`/`-shm` siblings) directly rather than re-deriving it from a directory.
    pub(crate) db_path: std::path::PathBuf,
    pub(crate) changes_tx: tokio::sync::broadcast::Sender<MirrorChange>,
    /// Set for the duration of the startup initial sync. While true, `bus_listener` buffers
    /// incoming `BusEvent`s instead of applying them, so a WS delta for an item a later sync
    /// page hasn't reached can't be clobbered by that page's now-stale snapshot.
    pub(crate) initial_sync_in_progress: std::sync::atomic::AtomicBool,
    /// True for the whole duration of `sync::spawn`'s startup pass (cold `initial_sync`, or
    /// the warm-launch refresh in its `else` branch). `apply_bus_event`'s `NeedsReconcile` arm
    /// skips its own delta+reconcile for the first post-launch connect while this holds, since
    /// the startup pass already covers the same ground.
    pub(crate) startup_pass_active: std::sync::atomic::AtomicBool,
    /// docs/07 §1: wakes `sync::next_up_refresher`, the one place Next Up is fetched from;
    /// `Notify` keeps at most one pending wake, which is what coalesces a burst of triggers.
    pub(crate) next_up_requested: std::sync::Arc<tokio::sync::Notify>,
    /// One-shot gate: only the very first post-launch `NeedsReconcile` may be skipped under
    /// `startup_pass_active`; every later (re)connect runs `apply_bus_event`'s handler in full.
    pub(crate) first_reconcile_handled: std::sync::atomic::AtomicBool,
    /// Number of bulk library passes streaming pages into the writer: `sync_library_breadth`
    /// walks and `reconcile_sweep` id sweeps, neither covered by `initial_sync_in_progress`
    /// alone. Folded into [`Mirror::is_syncing`]. Decremented by `sync::BreadthSyncGuard`
    /// before the pass's terminal `SyncActivity::Idle`, so an Idle observer already sees
    /// `is_syncing() == false`.
    pub(crate) breadth_syncs_in_flight: std::sync::atomic::AtomicUsize,
    /// Single-flight guard + deferred-rerun flag for `sync::reconcile_all` (two passes used
    /// to run concurrently at every launch, doubling resync bandwidth).
    pub(crate) reconcile_in_progress: std::sync::atomic::AtomicBool,
    pub(crate) reconcile_pending: std::sync::atomic::AtomicBool,
    /// Same single-flight + deferred-rerun pair, for `sync::delta_sync`. Kept separate from
    /// `reconcile_in_progress` because delta runs before reconcile at every trigger; sharing
    /// one flag would let the first pass's delta swallow the reconcile meant to follow it.
    pub(crate) delta_in_progress: std::sync::atomic::AtomicBool,
    pub(crate) delta_pending: std::sync::atomic::AtomicBool,
    /// Set by the app while a playback session is active; breadth syncs pause between pages
    /// while set, so bulk metadata doesn't compete with the stream mpv is buffering. Sync
    /// resumes where it left off when playback stops; WS deltas/reconcile probes are unaffected.
    pub(crate) playback_active: std::sync::atomic::AtomicBool,
    /// Notified when `initial_sync_in_progress` flips back to `false`, so `bus_listener` can
    /// replay its buffer promptly even if the event bus goes quiet right after sync finishes.
    /// `Arc`'d separately so `bus_listener` can clone just this handle and drop its
    /// `Arc<MirrorState>` upgrade before awaiting it.
    pub(crate) initial_sync_done: std::sync::Arc<tokio::sync::Notify>,
    /// Set (instead of replaying) when the WS delta buffer overflows during initial sync;
    /// `bus_listener` consumes this once sync completes and runs one `reconcile_all` in
    /// place of a partial/lossy replay.
    pub(crate) reconcile_after_sync: std::sync::atomic::AtomicBool,
    /// Self-referential weak handle so code holding only `&MirrorState` can hand out a
    /// `Weak<MirrorState>` (the startup sync task checks liveness per page via this instead
    /// of holding a strong `Arc` for its whole run).
    pub(crate) self_weak: std::sync::Weak<MirrorState>,
    /// Sync activity observable; see [`SyncActivity`] and [`Mirror::sync_activity`].
    pub(crate) sync_activity: tokio::sync::watch::Sender<SyncActivity>,
    /// Current Next Up filtering knobs; see [`NextUpOptions`]. A plain `Mutex`, not an
    /// atomic, since it's a two-field struct touched only on a rare settings change or read
    /// once per `refresh_next_up` call.
    pub(crate) next_up_options: std::sync::Mutex<NextUpOptions>,
    /// [`schema::IndexGroup::bit`]s of the deferred index groups that exist; a query needing
    /// an unbuilt group answers empty rather than scan the table (docs/25 §4.8).
    pub(crate) ready_index_groups: std::sync::atomic::AtomicU8,
}

impl MirrorState {
    /// Shared constructor for [`Mirror::open`] and the `lib.rs`/`sync.rs` test harnesses:
    /// every field below the parameters starts out identical in both (freshly opened, nothing
    /// in flight yet) -- only what's built differently per caller (the client, the writer
    /// handle, the read pool, the db path, the change/activity broadcast senders, and the
    /// self-referential weak handle `Arc::new_cyclic` hands back) is passed in.
    pub(crate) fn new(
        client: jellyfin_api::JellyfinClient,
        writer: writer::WriterHandle,
        read_pool: std::sync::Arc<pool::ReadPool>,
        db_path: std::path::PathBuf,
        changes_tx: tokio::sync::broadcast::Sender<MirrorChange>,
        sync_activity: tokio::sync::watch::Sender<SyncActivity>,
        self_weak: std::sync::Weak<MirrorState>,
    ) -> Self {
        MirrorState {
            client,
            writer,
            read_pool,
            db_path,
            changes_tx,
            initial_sync_in_progress: std::sync::atomic::AtomicBool::new(false),
            startup_pass_active: std::sync::atomic::AtomicBool::new(false),
            next_up_requested: std::sync::Arc::new(tokio::sync::Notify::new()),
            first_reconcile_handled: std::sync::atomic::AtomicBool::new(false),
            breadth_syncs_in_flight: std::sync::atomic::AtomicUsize::new(0),
            reconcile_in_progress: std::sync::atomic::AtomicBool::new(false),
            reconcile_pending: std::sync::atomic::AtomicBool::new(false),
            delta_in_progress: std::sync::atomic::AtomicBool::new(false),
            delta_pending: std::sync::atomic::AtomicBool::new(false),
            playback_active: std::sync::atomic::AtomicBool::new(false),
            initial_sync_done: std::sync::Arc::new(tokio::sync::Notify::new()),
            reconcile_after_sync: std::sync::atomic::AtomicBool::new(false),
            self_weak,
            sync_activity,
            next_up_options: std::sync::Mutex::new(NextUpOptions::default()),
            ready_index_groups: std::sync::atomic::AtomicU8::new(0),
        }
    }

    /// Asks for a Next Up re-fetch; see `sync::next_up_refresher` for the cooldown.
    pub(crate) fn request_next_up_refresh(&self) {
        self.next_up_requested.notify_one();
    }

    /// Whether deferred index `group` exists (docs/25 §4.8).
    pub(crate) fn index_group_ready(&self, group: schema::IndexGroup) -> bool {
        self.ready_index_groups
            .load(std::sync::atomic::Ordering::Acquire)
            & group.bit()
            != 0
    }
}

/// Sync activity observable for a UI status affordance. Broader than [`Mirror::is_syncing`]'s
/// initial-sync-only flag: also covers the reconciliation id sweep and any breadth sync after
/// startup. Updated by `sync::sync_library_breadth` and `sync::reconcile_sweep`, both via
/// `watch::Sender::send_replace` (synchronous, non-blocking, infallible with zero receivers,
/// so a UI that never subscribes can't stall a sync). Granularity is per page.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SyncActivity {
    /// No bulk library pass currently running.
    Idle,
    /// A bulk library pass (initial sync's breadth walk, or a reconciliation id sweep --
    /// indistinguishable here, deliberately) is in progress.
    Syncing {
        /// The view/library's id, not a resolved display name; callers resolve one via
        /// [`Mirror::views`] if needed.
        library_name_or_id: String,
        /// Pages already fetched in the current pass (`0` while the first page is in flight).
        pages_done: u32,
        /// Items already accounted for in the current pass; with `total_items` drives a
        /// determinate progress bar. See `sync::reconcile_sweep_inner`.
        items_done: u32,
        /// Denominator for `items_done` (the server's `TotalRecordCount`, or the repair set
        /// size during a sweep's by-ids phase); `None` while the first page is in flight.
        total_items: Option<u32>,
    },
}

/// Handle to one server's mirror. Clone-cheap. All queries are indexed —
/// EXPLAIN QUERY PLAN asserted in tests (no full scans on browse paths).
#[derive(Clone)]
pub struct Mirror {
    inner: std::sync::Arc<MirrorState>,
}

/// Rows the UI binds to: extracted columns only — the DTO blob is NOT parsed on
/// the browse path.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CardRow {
    pub id: String,
    pub item_type: String,
    pub name: String,
    pub primary_tag: Option<String>,
    /// The item's own first `BackdropImageTags` entry; the hero/detail header chain needs
    /// the item's real backdrop (falling back to `parent_backdrop_*`), not Primary-as-backdrop.
    pub backdrop_tag: Option<String>,
    /// `ImageTags["Thumb"]`, rare (mostly library folders).
    pub thumb_tag: Option<String>,
    pub blurhash: Option<String>,
    pub played: bool,
    pub position_ticks: i64,
    pub runtime_ticks: Option<i64>,
    pub unplayed_count: Option<i64>,
    pub production_year: Option<i32>,
    /// Season/Episode's own `IndexNumber`, for `episode_card`'s `"{n}. {name}"` title.
    pub index_number: Option<i32>,
    /// `PremiereDate` (RFC3339), so a virtual (unaired/missing) episode's card can show
    /// "Airs <date>" without a live/blob fetch. `None` for most non-Episode types.
    pub premiere_date: Option<String>,
    /// The season's own number, for an Episode row.
    pub parent_index_number: Option<i32>,
    /// The owning series' id (`SeriesId`), for a Season/Episode row. Used for the
    /// poster-fallback chain (`series_primary_tag`) and episode-context navigation.
    pub series_id: Option<String>,
    /// `SeriesPrimaryImageTag`, so a poster-shaped slot on a Season/Episode row can fall
    /// back to the series' poster. `None` for Movie/Series/BoxSet.
    pub series_primary_tag: Option<String>,
    /// `ParentBackdropItemId`/`ParentBackdropImageTags[0]`, the nearest ancestor backdrop
    /// for a Season/Episode row lacking its own (docs/07 §2 art fallback chains).
    pub parent_backdrop_item_id: Option<String>,
    pub parent_backdrop_tag: Option<String>,
    /// `SeriesName`, for a Season/Episode row. `None` for Movie/Series/BoxSet or a pre-v9
    /// row. Distinct from `series_primary_tag`/`series_id` (artwork/navigation only): this
    /// is what a Continue Watching/Next Up card prints as its second line.
    pub series_name: Option<String>,
    /// Server-authoritative `UserData.LastPlayedDate` (RFC3339); what `resume()` orders by
    /// (see `schema.rs`'s `last_played_date` column). Not shown, only carried through.
    pub last_played_date: Option<String>,
    /// The episode grid's synopsis text on the browse path, previously only available via a
    /// live enrichment fetch or a `dto` blob parse. `None` for a pre-v7 row or an item with
    /// no overview.
    pub overview: Option<String>,
    /// `BaseItemDto.LocationType == "Virtual"` (unaired/missing episode, no `MediaSources`).
    /// `cards.rs`/`detail.rs` dim artwork and disable Play when true; `root.rs::play_item`
    /// refuses playback as a defensive backstop. `false` for a pre-v8 row (SQLite's column
    /// default).
    pub is_virtual: bool,
    /// Per-library home visibility: the owning library's `views.id` (see `schema.rs`'s
    /// `library_id` column). `None` for a row whose library isn't attributed yet (rare in
    /// practice). `home.rs` uses this to drop a Continue Watching/Next Up card whose library
    /// is hidden from Home.
    pub library_id: Option<String>,
    /// docs/19-detail-action-menu.md §2.3: `UserData.IsFavorite`, exposed for the detail
    /// action menu's favorite toggle without a live-DTO fetch.
    pub is_favorite: bool,
}

#[derive(Debug, Clone)]
pub enum MirrorChange {
    Upserted {
        ids: Vec<String>,
        library_id: Option<String>,
    },
    Removed {
        ids: Vec<String>,
        library_id: Option<String>,
    },
    ViewsChanged,
    /// Synthetic signal (never sent by the writer) meaning "you missed some commits, throw
    /// away incremental state and re-query." Produced by [`recv_changes`] on `Lagged`.
    Refresh,
}

impl Mirror {
    /// Opens (or drop-and-recreates on version mismatch). Spawns the writer task and the
    /// sync engine (initial sync if empty; subscribes to the EventBus for live
    /// LibraryChanged/UserDataChanged deltas + NeedsReconcile).
    ///
    /// `dir` is a directory, not a filename; per-server scoping is assumed already baked
    /// into it by the caller (one directory per server), and this just creates `mirror.db`
    /// inside it.
    pub async fn open(
        dir: std::path::PathBuf,
        client: jellyfin_api::JellyfinClient,
        bus: tokio::sync::broadcast::Receiver<jellyfin_core::BusEvent>,
    ) -> Result<Self, CacheError> {
        tokio::fs::create_dir_all(&dir)
            .await
            .map_err(|e| CacheError::Db(e.to_string()))?;
        let db_path = dir.join("mirror.db");

        let open_path = db_path.clone();
        let (conn, is_empty, built_index_groups) = tokio::task::spawn_blocking(move || {
            schema::open_and_prepare(&open_path).map(|(conn, is_empty)| {
                let built = schema::built_index_groups(&conn);
                (conn, is_empty, built)
            })
        })
        .await
        .map_err(|e| CacheError::Db(e.to_string()))??;

        let pool_path = db_path.clone();
        let read_pool = tokio::task::spawn_blocking(move || {
            pool::ReadPool::open(&pool_path, READ_POOL_SIZE).map(std::sync::Arc::new)
        })
        .await
        .map_err(|e| CacheError::Db(e.to_string()))??;

        let (write_tx, write_rx) = tokio::sync::mpsc::channel(256);
        let (changes_tx, _) = tokio::sync::broadcast::channel(256);
        let writer_changes_tx = changes_tx.clone();
        tokio::task::spawn_blocking(move || writer::run(conn, write_rx, writer_changes_tx));
        let (sync_activity_tx, _) = tokio::sync::watch::channel(SyncActivity::Idle);

        let state = std::sync::Arc::new_cyclic(|weak| {
            MirrorState::new(
                client,
                writer::WriterHandle::new(write_tx),
                read_pool,
                db_path,
                changes_tx,
                sync_activity_tx,
                weak.clone(),
            )
        });

        state
            .ready_index_groups
            .store(built_index_groups, std::sync::atomic::Ordering::Release);
        sync::spawn(state.clone(), bus, is_empty);

        Ok(Mirror { inner: state })
    }

    /// Change feed for live UI updates (commit-ordered). Consumers MUST read via
    /// [`recv_changes`], not `.recv()` directly, since a raw `Lagged` can't just be ignored.
    pub fn changes(&self) -> tokio::sync::broadcast::Receiver<MirrorChange> {
        self.inner.changes_tx.subscribe()
    }

    /// True while bulk writes are streaming into the mirror (initial/rebuild sync, any
    /// breadth walk, or a reconcile sweep), so the UI can ride out the "tiles reordering"
    /// churn. Drives Home's shelf order-freeze and the active library's refresh gating.
    pub fn is_syncing(&self) -> bool {
        self.inner
            .initial_sync_in_progress
            .load(std::sync::atomic::Ordering::Acquire)
            || self
                .inner
                .breadth_syncs_in_flight
                .load(std::sync::atomic::Ordering::Acquire)
                > 0
    }

    /// Sync activity observable; see [`SyncActivity`]. Every `Mirror` clone shares the same
    /// `watch` channel, so subscribing repeatedly is cheap.
    pub fn sync_activity(&self) -> tokio::sync::watch::Receiver<SyncActivity> {
        self.inner.sync_activity.subscribe()
    }

    /// App-reported playback state: while `true`, breadth syncs pause between pages so bulk
    /// metadata doesn't compete with the stream mpv is buffering. Idempotent; the app calls
    /// it on every playback start/stop transition.
    pub fn set_playback_active(&self, active: bool) {
        self.inner
            .playback_active
            .store(active, std::sync::atomic::Ordering::Release);
    }

    /// Pushes the app's current Next Up filtering preferences into the sync engine; see
    /// [`NextUpOptions`] for why this is a push rather than reading `AppSettings` directly.
    /// Does not itself trigger a refresh -- the next opportunistic `sync::refresh_next_up`
    /// call picks up the new value; a caller wanting it immediately should also poke a
    /// refresh (`Root::refresh_next_up_now`).
    pub fn set_next_up_options(&self, options: NextUpOptions) {
        *self
            .inner
            .next_up_options
            .lock()
            .unwrap_or_else(|e| e.into_inner()) = options;
    }

    /// Total item count for the sidebar's sync progress text. A plain `COUNT(*)`, cheap and
    /// only polled at UI refresh cadence.
    pub fn item_count(&self) -> i64 {
        query::item_count(&self.inner.read_pool.acquire())
    }

    /// Mirrored, non-virtual items per `item_type` -- the library as this client holds it,
    /// which a proxy's `/Items/Counts` can't be trusted to describe.
    pub fn item_type_counts(&self) -> Vec<(String, i64)> {
        query::item_type_counts(&self.inner.read_pool.acquire())
    }

    /// Reads one `meta` row (docs/13 About > server info: `last_full_sync`/`last_delta_sync`
    /// timestamps). `None` when the key was never written.
    pub fn meta_value(&self, key: &str) -> Option<String> {
        schema::read_meta(&self.inner.read_pool.acquire(), key)
    }

    /// On-disk mirror size in bytes: `mirror.db` plus its `-wal`/`-shm` siblings when present
    /// (docs/13 About > server info). A missing file (no WAL activity yet, or `-shm` already
    /// checkpointed away) contributes 0 rather than erroring.
    pub fn db_size_bytes(&self) -> u64 {
        let db_path = &self.inner.db_path;
        let mut wal_path = db_path.clone();
        wal_path.as_mut_os_string().push("-wal");
        let mut shm_path = db_path.clone();
        shm_path.as_mut_os_string().push("-shm");
        [db_path.as_path(), wal_path.as_path(), shm_path.as_path()]
            .iter()
            .map(|p| std::fs::metadata(p).map(|m| m.len()).unwrap_or(0))
            .sum()
    }

    // Browse queries (all served from indexes, sync-fast, called from UI thread pool)
    pub fn views(&self) -> Vec<ViewSummary> {
        query::views(&self.inner.read_pool.acquire())
    }

    /// If `parent_id` names a `BoxSet`, resolves through `collection_members` (server
    /// curation order) instead of plain `parent_id` equality; see `query::children`.
    pub fn children(&self, parent_id: &str, sort: Sort, offset: u32, limit: u32) -> Vec<CardRow> {
        query::children(
            &self.inner.read_pool.acquire(),
            parent_id,
            sort,
            offset,
            limit,
        )
    }

    /// [`Mirror::children`] with query failure surfaced as `None` (logged here) instead of
    /// coerced to an empty list, so the library refresh path keeps the last good grid rather
    /// than blanking it; `Some(vec![])` still means a genuinely empty parent.
    pub fn children_checked(
        &self,
        parent_id: &str,
        sort: Sort,
        offset: u32,
        limit: u32,
    ) -> Option<Vec<CardRow>> {
        query::children_checked(
            &self.inner.read_pool.acquire(),
            parent_id,
            sort,
            offset,
            limit,
        )
        .map_err(|e| tracing::error!(error = %e, "children query failed"))
        .ok()
    }

    /// Per-season episode counts under `series_id`: `(season_id, total_episodes,
    /// non_virtual_episodes)`; see `query::season_episode_counts`. Feeds
    /// `JellybeamCore::children`'s virtual-season filtering; `None` on a query failure, logged
    /// here.
    pub fn season_episode_counts(&self, series_id: &str) -> Option<Vec<(String, i64, i64)>> {
        query::season_episode_counts(&self.inner.read_pool.acquire(), series_id)
            .map_err(|e| tracing::error!(error = %e, "season_episode_counts query failed"))
            .ok()
    }

    /// docs/16-library-sort-filter.md §2.2: the sorted/filtered library grid for `view_id`
    /// (same population as `children(view_id, ..)`, with the strip's sort/filter state
    /// applied). Fails open to an empty page.
    pub fn library_grid(
        &self,
        view_id: &str,
        sort: GridSort,
        filters: &GridFilters,
        offset: u32,
        limit: u32,
    ) -> Vec<CardRow> {
        query::library_grid(
            &self.inner.read_pool.acquire(),
            view_id,
            sort,
            filters,
            offset,
            limit,
        )
    }

    /// [`Self::library_grid`] with query failure surfaced as `None` instead of an empty list
    /// (§4.6: keep the last good grid, don't blank it).
    pub fn library_grid_checked(
        &self,
        view_id: &str,
        sort: GridSort,
        filters: &GridFilters,
        offset: u32,
        limit: u32,
    ) -> Option<Vec<CardRow>> {
        query::library_grid_checked(
            &self.inner.read_pool.acquire(),
            view_id,
            sort,
            filters,
            offset,
            limit,
        )
        .map_err(|e| tracing::error!(error = %e, "library_grid query failed"))
        .ok()
    }

    /// docs/16-library-sort-filter.md §2.3: filtered/total row counts for the summary line.
    /// Fails open to zeroed counts; use [`Self::library_grid_counts_checked`] to tell a
    /// failure apart from a genuinely empty view.
    pub fn library_grid_counts(&self, view_id: &str, filters: &GridFilters) -> GridCounts {
        query::library_grid_counts(&self.inner.read_pool.acquire(), view_id, filters)
    }

    /// [`Self::library_grid_counts`] with query failure surfaced as `None` instead of
    /// zeroed counts (§4.6: keep the last good counts, don't zero them).
    pub fn library_grid_counts_checked(
        &self,
        view_id: &str,
        filters: &GridFilters,
    ) -> Option<GridCounts> {
        query::library_grid_counts_checked(&self.inner.read_pool.acquire(), view_id, filters)
            .map_err(|e| tracing::error!(error = %e, "library_grid_counts query failed"))
            .ok()
    }

    /// docs/16-library-sort-filter.md §2.4: the index rail's group buckets for the current
    /// sort/filter state. Fails open to an empty list; use
    /// [`Self::library_grid_groups_checked`] to tell a failure apart from a genuinely empty view.
    pub fn library_grid_groups(
        &self,
        view_id: &str,
        sort: GridSort,
        filters: &GridFilters,
    ) -> Vec<GridGroup> {
        query::library_grid_groups(&self.inner.read_pool.acquire(), view_id, sort, filters)
    }

    /// [`Self::library_grid_groups`] with query failure surfaced as `None` instead of an
    /// empty list (§4.6: keep the last good rail, don't blank it).
    pub fn library_grid_groups_checked(
        &self,
        view_id: &str,
        sort: GridSort,
        filters: &GridFilters,
    ) -> Option<Vec<GridGroup>> {
        query::library_grid_groups_checked(&self.inner.read_pool.acquire(), view_id, sort, filters)
            .map_err(|e| tracing::error!(error = %e, "library_grid_groups query failed"))
            .ok()
    }

    /// docs/16-library-sort-filter.md §2.5: the view's distinct genres, `COLLATE NOCASE`
    /// order, for the Genre panel.
    pub fn library_genres(&self, view_id: &str) -> Vec<String> {
        query::library_genres(&self.inner.read_pool.acquire(), view_id)
    }

    /// docs/07 §5: see `query::has_favorites`.
    pub fn has_favorites(&self) -> bool {
        self.inner.index_group_ready(schema::IndexGroup::Favorites)
            && query::has_favorites(&self.inner.read_pool.acquire())
    }

    /// docs/16 §2.7: see `query::favorite_item_types`.
    pub fn favorite_item_types(&self) -> Vec<String> {
        query::favorite_item_types(&self.inner.read_pool.acquire())
    }

    /// docs/07 §1: the Home Favorites shelf; see `query::favorites`.
    pub fn favorites(&self, limit: u32) -> Vec<CardRow> {
        if !self.inner.index_group_ready(schema::IndexGroup::Favorites) {
            return Vec::new();
        }
        query::favorites(&self.inner.read_pool.acquire(), limit)
    }

    pub fn resume(&self, limit: u32) -> Vec<CardRow> {
        query::resume(&self.inner.read_pool.acquire(), limit)
    }

    pub fn next_up(&self, limit: u32) -> Vec<CardRow> {
        query::next_up(&self.inner.read_pool.acquire(), limit)
    }

    /// Re-fetches `/Shows/NextUp` against the current [`NextUpOptions`] immediately, rather
    /// than waiting for the next opportunistic trigger. The Settings sheet calls this right
    /// after changing the cutoff/rewatching knobs.
    pub async fn refresh_next_up(&self) {
        sync::refresh_next_up(&self.inner).await;
    }

    /// `hide_watched`: excludes already-played items (see `query::latest`). Does not affect
    /// [`Self::resume`]/[`Self::next_up`], both inherently unwatched/in-progress already.
    pub fn latest(&self, view_id: &str, limit: u32, hide_watched: bool) -> Vec<CardRow> {
        query::latest(
            &self.inner.read_pool.acquire(),
            view_id,
            limit,
            hide_watched,
        )
    }

    pub fn search(&self, query_str: &str, limit: u32) -> Vec<CardRow> {
        query::search(&self.inner.read_pool.acquire(), query_str, limit)
    }

    /// The same `CardRow` shape every other browse query returns, for one id; see
    /// `query::card_by_id`.
    pub fn card_by_id(&self, id: &str) -> Option<CardRow> {
        query::card_by_id(&self.inner.read_pool.acquire(), id)
    }

    /// Full DTO for Detail view (blob parse allowed here).
    pub fn item(&self, id: &str) -> Option<BaseItemDto> {
        query::item(&self.inner.read_pool.acquire(), id)
    }

    /// Batched detail metadata for a browse result, avoiding one read-pool checkout and SQL
    /// statement per card.
    pub fn items(&self, ids: &[String]) -> std::collections::HashMap<String, BaseItemDto> {
        query::items(&self.inner.read_pool.acquire(), ids)
    }

    /// Optimistically applies a watch-state update this client just reported to the server,
    /// without waiting for the `UserDataChanged` WS event (Jellyfin doesn't push it back to
    /// its own originating session, which otherwise leaves Resume/Continue Watching stale
    /// until a full resync).
    ///
    /// `played`: `None` is an ordinary tick/stop, where the played flag only flips past the
    /// ~90% runtime threshold (see `writer::resolve_played_position`); `Some(true)` is a real
    /// EOF, always marking played regardless of the threshold.
    ///
    /// Routed through the single writer, emitting
    /// `MirrorChange::Upserted` on success like a server-pushed update.
    pub async fn apply_local_user_data(
        &self,
        item_id: &str,
        position_ticks: i64,
        played: Option<bool>,
    ) {
        self.inner
            .writer
            .apply_local_user_data(item_id.to_string(), position_ticks, played)
            .await;
    }

    /// [`Self::apply_local_user_data`], but resolves only once the write is committed, not
    /// merely enqueued (docs/17 §6's commit barrier). Needed on the app-quit path, which
    /// awaits inside a short shutdown grace window and then dies. Costs one extra round trip
    /// through the writer's FIFO queue (`WriterHandle::barrier`).
    pub async fn apply_local_user_data_and_wait(
        &self,
        item_id: &str,
        position_ticks: i64,
        played: Option<bool>,
    ) -> bool {
        self.apply_local_user_data(item_id, position_ticks, played)
            .await;
        self.inner.writer.barrier().await
    }

    /// docs/19-detail-action-menu.md §2.2: applies a `UserItemDataDto` a server mutation
    /// just returned (FFI's `set_played`/`set_favorite`) as the authoritative result, same
    /// patch semantics as a `UserDataChanged` application (see `writer::apply_user_data`),
    /// reachable without waiting on the WS event (which never echoes back to the originating
    /// session).
    pub async fn apply_user_data(&self, updates: Vec<(String, UserItemDataDto)>) {
        self.inner.writer.apply_user_data(updates).await;
    }

    /// docs/07 §1: Next Up after a watch-state change this client made (a stop, a mark); the
    /// refresher fetches at once unless one just ran, so returning Home shows the next episode.
    pub fn request_next_up_refresh(&self) {
        self.inner.request_next_up_refresh();
    }

    /// docs/19-detail-action-menu.md §2.2: authoritative re-read of items by id, in batches
    /// of 100, to refresh parent rows (`unplayed_item_count`) only the server can recompute.
    /// Thin wrapper over [`sync::fetch_and_upsert_ids`].
    pub async fn refresh_items(&self, ids: Vec<String>) {
        for batch in ids.chunks(100) {
            sync::fetch_and_upsert_ids(&self.inner, batch.to_vec()).await;
        }
    }

    /// docs/19-detail-action-menu.md §2.2: re-warms one collection's `collection_members` row
    /// right after a successful add ([`sync::sync_one_boxset_membership`]), instead of
    /// waiting on the server's `LibraryChanged` push.
    pub async fn refresh_collection_membership(&self, collection_id: &str) {
        sync::sync_one_boxset_membership(&self.inner, collection_id).await;
    }

    /// docs/19-detail-action-menu.md §2.2/§2.3: which collections (BoxSet ids) `item_id` is
    /// currently a member of, per `collection_members`. Empty when the mirror has no
    /// membership rows for this item, or the query failed (logged in
    /// `query::collection_ids_containing`).
    pub fn collection_ids_containing(&self, item_id: &str) -> Vec<String> {
        query::collection_ids_containing(&self.inner.read_pool.acquire(), item_id)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Sort {
    NameAsc,
    DateCreatedDesc,
    PremiereDateDesc,
    /// `ORDER BY parent_index_number, index_number`, serving both Series -> Seasons and
    /// Season -> Episodes. Backed by `idx_items_parent_order` so `children()` stays
    /// index-served.
    IndexNumber,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum ImageKind {
    Primary,
    Backdrop,
    Thumb,
    Trickplay,
}

// ---- Library sort/filter (docs/16-library-sort-filter.md §2.1) ----------

/// The library grid's sortable fields; see `query::library_grid`'s ORDER BY table (§2.2).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GridSortField {
    Name,
    DateAdded,
    Year,
    Runtime,
}

/// One field plus direction. `descending` only flips the first ORDER BY term's `ASC`/`DESC`,
/// never the tiebreak.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct GridSort {
    pub field: GridSortField,
    pub descending: bool,
}

/// §2.2's Watched filter: `Unwatched` ("never started") is distinct from `HasUnwatched`
/// ("not finished") -- a Movie is never `HasUnwatched`, while a Series is when
/// `unplayed_item_count > 0`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum WatchedFilter {
    Any,
    Unwatched,
    HasUnwatched,
    Watched,
}

/// TV Shows' Continuing/Ended chip (§2.2); `series_status` is `NULL` for every non-Series
/// row, so this is a no-op outside a `tvshows` library.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum StatusFilter {
    Any,
    Continuing,
    Ended,
}

/// The Years panel's decade buckets (§2.2/§4.3). `Older` is everything before the 1980s; a
/// `NULL` `production_year` matches no variant, including `Older`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Decade {
    D2020s,
    D2010s,
    D2000s,
    D1990s,
    D1980s,
    Older,
}

/// The strip's full filter state (§2.2), ANDed together. Not `Copy`: `genre` owns its `String`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GridFilters {
    pub watched: WatchedFilter,
    pub genre: Option<String>,
    pub decade: Option<Decade>,
    pub status: StatusFilter,
    /// Exact `item_type` match; the Favorites grid's type chips (§2.7).
    pub item_type: Option<String>,
}

/// [`Mirror::library_grid_counts`]'s result (§2.3): `filtered` matches the current
/// [`GridFilters`], `total` is the library's unfiltered row count.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct GridCounts {
    pub filtered: u64,
    pub total: u64,
}

/// One contiguous run of [`Mirror::library_grid_groups`]'s result (§2.4), the index rail's
/// raw material. No offset field: callers sum `count` across groups in the returned order.
/// A run-length encoding of the ordered grid, not a `GROUP BY` -- the same key can repeat
/// non-contiguously (e.g. `#` under Name), and each run stays its own entry.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GridGroup {
    pub key: String,
    pub count: u64,
}

#[derive(Debug, thiserror::Error)]
pub enum CacheError {
    #[error("db: {0}")]
    Db(String),
    #[error(transparent)]
    Api(#[from] jellyfin_api::ApiError),
    #[error("cancelled")]
    Cancelled,
    /// `item_id`/`tag` build on-disk cache paths; a value containing `/` or `..` is rejected
    /// at the `get()` boundary rather than allowed near a `Path::join`.
    #[error("invalid image cache key component: {0:?}")]
    InvalidKey(String),
}

/// How long [`recv_changes`] coalesces a burst of [`MirrorChange`]s into one signal, so the
/// many small `Upserted` batches of an initial sync collapse into one UI refresh instead of
/// each triggering its own re-query, while single-event latency stays imperceptible.
const CHANGE_DEBOUNCE: std::time::Duration = std::time::Duration::from_millis(250);

/// Reads the next change from a [`Mirror::changes`] receiver, collapsing a `Lagged` gap into
/// a synthetic [`MirrorChange::Refresh`] instead of surfacing the raw broadcast error. UI
/// consumers MUST call this instead of `rx.recv()` directly: a raw `Lagged` means the
/// receiver missed commits, so `Upserted`/`Removed` deltas alone can no longer be trusted to
/// reconstruct state. Returns `None` once the channel is closed.
///
/// Also debounces (see [`CHANGE_DEBOUNCE`]) to bound re-query frequency during sync bursts.
/// Compatible scoped changes are merged and deduplicated; a window with different change
/// kinds or library scopes returns [`MirrorChange::Refresh`] instead.
pub async fn recv_changes(
    rx: &mut tokio::sync::broadcast::Receiver<MirrorChange>,
) -> Option<MirrorChange> {
    let mut combined = match rx.recv().await {
        Ok(change) => change,
        Err(tokio::sync::broadcast::error::RecvError::Lagged(skipped)) => {
            tracing::warn!(
                skipped,
                "MirrorChange receiver lagged; issuing a Refresh signal"
            );
            MirrorChange::Refresh
        }
        Err(tokio::sync::broadcast::error::RecvError::Closed) => return None,
    };

    // Fixed window from the first event, not a reset-on-every-event quiet timer, so a
    // sustained stream still surfaces a refresh at a bounded ~4Hz rather than being starved.
    let deadline = tokio::time::Instant::now() + CHANGE_DEBOUNCE;
    loop {
        tokio::select! {
            biased;
            _ = tokio::time::sleep_until(deadline) => break,
            res = rx.recv() => match res {
                Ok(change) => {
                    combined = merge_changes(combined, change);
                    continue;
                },
                Err(tokio::sync::broadcast::error::RecvError::Lagged(skipped)) => {
                    tracing::warn!(
                        skipped,
                        "MirrorChange receiver lagged while debouncing; issuing a Refresh signal"
                    );
                    return Some(MirrorChange::Refresh);
                }
                // Channel closed mid-drain: hand back the change we already have; the
                // caller's next call sees the close and returns `None`.
                Err(tokio::sync::broadcast::error::RecvError::Closed) => break,
            },
        }
    }

    Some(combined)
}

fn merge_changes(left: MirrorChange, right: MirrorChange) -> MirrorChange {
    match (left, right) {
        (MirrorChange::Refresh, _) | (_, MirrorChange::Refresh) => MirrorChange::Refresh,
        (
            MirrorChange::Upserted {
                ids: mut a,
                library_id: a_library,
            },
            MirrorChange::Upserted {
                ids: b,
                library_id: b_library,
            },
        ) if a_library == b_library => {
            a.extend(b);
            a.sort();
            a.dedup();
            MirrorChange::Upserted {
                ids: a,
                library_id: a_library,
            }
        }
        (
            MirrorChange::Removed {
                ids: mut a,
                library_id: a_library,
            },
            MirrorChange::Removed {
                ids: b,
                library_id: b_library,
            },
        ) if a_library == b_library => {
            a.extend(b);
            a.sort();
            a.dedup();
            MirrorChange::Removed {
                ids: a,
                library_id: a_library,
            }
        }
        (MirrorChange::ViewsChanged, MirrorChange::ViewsChanged) => MirrorChange::ViewsChanged,
        // Different scopes/types can't be represented losslessly by one event; force a re-query.
        _ => MirrorChange::Refresh,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn schema_version_is_stable_constant() {
        assert_eq!(SCHEMA_VERSION, 16);
    }

    // `start_paused = true`: `recv_changes` always waits out `CHANGE_DEBOUNCE` before
    // returning; tokio's paused-clock auto-advance lets these tests resolve instantly.
    #[tokio::test(start_paused = true)]
    async fn recv_changes_passes_through_ok_values() {
        let (tx, mut rx) = tokio::sync::broadcast::channel(4);
        tx.send(MirrorChange::ViewsChanged).expect("send");
        let change = recv_changes(&mut rx).await;
        assert!(matches!(change, Some(MirrorChange::ViewsChanged)));
    }

    #[tokio::test(start_paused = true)]
    async fn recv_changes_maps_lagged_to_refresh() {
        let (tx, mut rx) = tokio::sync::broadcast::channel(2);
        // Overflow the small buffer so the receiver's next recv() sees Lagged.
        tx.send(MirrorChange::ViewsChanged).expect("send");
        tx.send(MirrorChange::ViewsChanged).expect("send");
        tx.send(MirrorChange::ViewsChanged).expect("send");
        let change = recv_changes(&mut rx).await;
        assert!(matches!(change, Some(MirrorChange::Refresh)));
    }

    #[tokio::test(start_paused = true)]
    async fn recv_changes_returns_none_when_closed() {
        let (tx, mut rx) = tokio::sync::broadcast::channel::<MirrorChange>(4);
        drop(tx);
        assert!(recv_changes(&mut rx).await.is_none());
    }

    /// A burst of rapid `Upserted` events must collapse into exactly one returned signal per
    /// `CHANGE_DEBOUNCE` window, not one wakeup per event.
    #[tokio::test(start_paused = true)]
    async fn recv_changes_coalesces_a_burst_into_one_signal() {
        let (tx, mut rx) = tokio::sync::broadcast::channel(16);
        for i in 0..5 {
            tx.send(MirrorChange::Upserted {
                ids: vec![format!("item-{i}")],
                library_id: None,
            })
            .expect("send");
        }

        let change = recv_changes(&mut rx).await;
        assert!(matches!(change, Some(MirrorChange::Upserted { .. })));
        assert_eq!(
            rx.len(),
            0,
            "all 5 sends must be drained into the single returned signal, \
             not left queued for one-by-one delivery"
        );
    }

    /// A quiet channel after a debounced burst must still deliver later, separate changes.
    #[tokio::test(start_paused = true)]
    async fn recv_changes_still_delivers_a_later_event_after_debouncing() {
        let (tx, mut rx) = tokio::sync::broadcast::channel(16);
        tx.send(MirrorChange::ViewsChanged).expect("send");
        let first = recv_changes(&mut rx).await;
        assert!(matches!(first, Some(MirrorChange::ViewsChanged)));

        tx.send(MirrorChange::Removed {
            ids: vec!["gone".to_string()],
            library_id: None,
        })
        .expect("send");
        let second = recv_changes(&mut rx).await;
        assert!(matches!(second, Some(MirrorChange::Removed { .. })));
    }

    /// Shared harness for tests needing a fully wired `Mirror` (real writer task + read
    /// pool), so they don't re-paste the `MirrorState` field list.
    fn test_mirror(client: jellyfin_api::JellyfinClient, path: &std::path::Path) -> Mirror {
        use crate::pool::ReadPool;
        use crate::writer::WriterHandle;

        let (conn, _empty) = schema::open_and_prepare(path).expect("open");
        let (tx, rx) = tokio::sync::mpsc::channel(64);
        let (changes_tx, _) = tokio::sync::broadcast::channel(64);
        let writer_changes_tx = changes_tx.clone();
        let _writer_task =
            tokio::task::spawn_blocking(move || writer::run(conn, rx, writer_changes_tx));
        let read_pool = std::sync::Arc::new(ReadPool::open(path, 2).expect("read pool"));
        let (sync_activity_tx, _) = tokio::sync::watch::channel(SyncActivity::Idle);
        let state = std::sync::Arc::new_cyclic(|weak| {
            MirrorState::new(
                client,
                WriterHandle::new(tx),
                read_pool,
                path.to_path_buf(),
                changes_tx,
                sync_activity_tx,
                weak.clone(),
            )
        });
        Mirror { inner: state }
    }

    /// docs/19-detail-action-menu.md §2.2: [`Mirror::refresh_items`] must split its id list
    /// into batches of at most 100 before handing each to `sync::fetch_and_upsert_ids`.
    #[tokio::test]
    async fn refresh_items_batches_ids_into_groups_of_100() {
        use crate::mock_server::MockServer;
        use jellyfin_api::{ClientIdentity, JellyfinClient};

        let server = MockServer::start().await;
        let client = JellyfinClient::from_token(
            &server.base_url,
            ClientIdentity {
                client: "Jellybeam Test".to_string(),
                device: "test".to_string(),
                device_id: "test-device".to_string(),
                version: "0.1.0".to_string(),
            },
            "tok",
        );
        // This test only cares about request count/batch size, not the response.
        server.route(
            "/Items",
            &[],
            serde_json::json!({ "Items": [], "TotalRecordCount": 0 }),
        );

        let dir = tempfile::tempdir().expect("tempdir");
        let path = dir.path().join("mirror.db");
        let mirror = test_mirror(client, &path);

        let ids: Vec<String> = (0..250).map(|i| format!("item-{i}")).collect();
        mirror.refresh_items(ids).await;

        assert_eq!(
            server.request_count("/Items"),
            3,
            "250 ids should batch into 3 requests (100 + 100 + 50)"
        );
        assert_eq!(server.request_count_matching("limit=100"), 2);
        assert_eq!(server.request_count_matching("limit=50"), 1);
    }

    /// docs/19-detail-action-menu.md §2.2: seeds `collection_members` via the writer's
    /// `set_collection_members` command (not a direct connection poke), then confirms the
    /// read sees it and stays empty for an item with no rows.
    #[tokio::test]
    async fn collection_ids_containing_reads_what_the_writer_seeded() {
        use jellyfin_api::{ClientIdentity, JellyfinClient};

        let client = JellyfinClient::from_token(
            "http://core.test.invalid",
            ClientIdentity {
                client: "Jellybeam Test".to_string(),
                device: "test".to_string(),
                device_id: "test-device".to_string(),
                version: "0.1.0".to_string(),
            },
            "tok",
        );

        let dir = tempfile::tempdir().expect("tempdir");
        let path = dir.path().join("mirror.db");
        let mirror = test_mirror(client, &path);

        let collection_id = "collection-1".to_string();
        let member_id = "item-1".to_string();
        mirror
            .inner
            .writer
            .set_collection_members(collection_id.clone(), vec![(member_id.clone(), 0)])
            .await;
        assert!(
            mirror.inner.writer.barrier().await,
            "seed write must commit before the read below"
        );

        assert_eq!(
            mirror.collection_ids_containing(&member_id),
            vec![collection_id],
        );
        assert!(
            mirror.collection_ids_containing("no-such-item").is_empty(),
            "an item with no membership rows must read back empty, not error"
        );
    }

    /// docs/19-detail-action-menu.md §2.2: [`Mirror::refresh_collection_membership`] must
    /// keep paging until membership is complete before replacing `collection_members`, or a
    /// genuine member reads back as absent.
    #[tokio::test]
    async fn refresh_collection_membership_pages_past_the_first_page() {
        use crate::mock_server::MockServer;
        use jellyfin_api::{ClientIdentity, JellyfinClient};

        let server = MockServer::start().await;
        let client = JellyfinClient::from_token(
            &server.base_url,
            ClientIdentity {
                client: "Jellybeam Test".to_string(),
                device: "test".to_string(),
                device_id: "test-device".to_string(),
                version: "0.1.0".to_string(),
            },
            "tok",
        );

        // `BaseItemDto::id` is `Option<Uuid>`, so synthetic member ids must parse as UUIDs.
        fn member_uuid(i: u32) -> String {
            format!("00000000-0000-0000-0000-{i:012x}")
        }

        let first_page: Vec<serde_json::Value> = (0..500)
            .map(|i| serde_json::json!({ "Id": member_uuid(i), "Name": "Member", "Type": "Movie" }))
            .collect();
        let second_page: Vec<serde_json::Value> = (500..502)
            .map(|i| serde_json::json!({ "Id": member_uuid(i), "Name": "Member", "Type": "Movie" }))
            .collect();
        server.route(
            "/Items",
            &[("startIndex", "0")],
            serde_json::json!({ "Items": first_page, "TotalRecordCount": 502 }),
        );
        server.route(
            "/Items",
            &[("startIndex", "500")],
            serde_json::json!({ "Items": second_page, "TotalRecordCount": 502 }),
        );

        let dir = tempfile::tempdir().expect("tempdir");
        let path = dir.path().join("mirror.db");
        let mirror = test_mirror(client, &path);

        mirror.refresh_collection_membership("collection-1").await;
        assert!(
            mirror.inner.writer.barrier().await,
            "membership write must commit"
        );

        assert_eq!(
            server.request_count("/Items"),
            2,
            "502 members at PAGE_SIZE 500 must fetch exactly two pages"
        );
        assert!(
            mirror
                .collection_ids_containing(&member_uuid(501))
                .contains(&"collection-1".to_string()),
            "a member from the second page must not be lost when membership is replaced"
        );
    }

    /// docs/13 About > server info: [`Mirror::meta_value`] must read back a key written
    /// through the writer, and report `None` for a key never written.
    #[tokio::test]
    async fn meta_value_round_trips_a_key_written_through_the_writer() {
        use jellyfin_api::{ClientIdentity, JellyfinClient};

        let client = JellyfinClient::from_token(
            "http://core.test.invalid",
            ClientIdentity {
                client: "Jellybeam Test".to_string(),
                device: "test".to_string(),
                device_id: "test-device".to_string(),
                version: "0.1.0".to_string(),
            },
            "tok",
        );

        let dir = tempfile::tempdir().expect("tempdir");
        let path = dir.path().join("mirror.db");
        let mirror = test_mirror(client, &path);

        assert_eq!(mirror.meta_value("last_full_sync"), None);

        mirror
            .inner
            .writer
            .set_meta("last_full_sync", "2026-01-02T03:04:05Z".to_string())
            .await;
        assert!(
            mirror.inner.writer.barrier().await,
            "seed write must commit before the read below"
        );

        assert_eq!(
            mirror.meta_value("last_full_sync").as_deref(),
            Some("2026-01-02T03:04:05Z")
        );
    }

    /// docs/13 About > server info: [`Mirror::db_size_bytes`] must reflect the real
    /// `mirror.db` file the schema was written to, not report 0 once the writer has run.
    #[tokio::test]
    async fn db_size_bytes_is_nonzero_after_open() {
        use jellyfin_api::{ClientIdentity, JellyfinClient};

        let client = JellyfinClient::from_token(
            "http://core.test.invalid",
            ClientIdentity {
                client: "Jellybeam Test".to_string(),
                device: "test".to_string(),
                device_id: "test-device".to_string(),
                version: "0.1.0".to_string(),
            },
            "tok",
        );

        let dir = tempfile::tempdir().expect("tempdir");
        let path = dir.path().join("mirror.db");
        let mirror = test_mirror(client, &path);

        assert!(
            mirror.db_size_bytes() > 0,
            "mirror.db should already have the schema written to it after open"
        );
    }
}
