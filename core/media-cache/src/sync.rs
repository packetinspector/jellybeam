//! Sync engine: paged initial sync (Resume/NextUp/Latest first, then breadth),
//! WebSocket deltas, and reconciliation. Every mutation flows through `WriterHandle` (the
//! single writer), so ordering is exactly the order these `async fn`s issue writes in.

use std::sync::atomic::Ordering;
use std::sync::{Arc, Weak};

use futures_util::stream::{self, StreamExt};
use jellyfin_api::models::{BaseItemDto, BaseItemKind};
use jellyfin_api::{ItemQuery, ServerEvent};
use jellyfin_core::BusEvent;
use tokio::sync::broadcast;

use crate::schema::IndexGroup;
use crate::{MirrorChange, MirrorState, SyncActivity};

const PAGE_SIZE: u32 = 500;
const WS_BATCH_SIZE: usize = 100;
/// Page size for [`reconcile_sweep`]'s id enumeration, much larger than [`PAGE_SIZE`]: a
/// sweep page has no fields/images/user data (see [`id_sweep_query`]), so fewer, fatter
/// pages mean fewer round trips over a high-latency link.
const ID_SWEEP_PAGE_SIZE: u32 = 1000;
const MAX_SYNC_PAGES: u32 = 2000;

/// Upgrades `$weak`, binding the live `Arc<MirrorState>` to `$s` for `$body` and returning
/// from the caller if the `Mirror` is already gone; drops the upgraded `Arc` before yielding
/// `$body`'s value -- the bail-out idiom `spawn`'s startup pass and `initial_sync` repeat
/// between every step.
macro_rules! with_upgraded {
    ($weak:expr, |$s:ident| $body:expr) => {{
        let Some($s) = $weak.upgrade() else {
            return;
        };
        let result = $body;
        drop($s);
        result
    }};
}

/// Shared completeness check before pruning a snapshot or advancing a delta cursor.
struct PageProgress {
    max_pages: u32,
    pages: u32,
    total: Option<u32>,
    ids: std::collections::HashSet<uuid::Uuid>,
}

impl PageProgress {
    fn new(max_pages: u32) -> Self {
        Self {
            max_pages,
            pages: 0,
            total: None,
            ids: Default::default(),
        }
    }

    fn observe(&mut self, items: &[BaseItemDto], total: Option<i32>) -> Result<bool, &'static str> {
        self.pages += 1;
        if let Some(total) = total {
            let total = u32::try_from(total).map_err(|_| "negative page total")?;
            if self.total.is_some_and(|previous| previous != total) {
                return Err("page total changed during enumeration");
            }
            self.total = Some(total);
        }
        for item in items {
            let id = item.id.ok_or("page item has no id")?;
            if !self.ids.insert(id) {
                return Err("page repeats an already enumerated id");
            }
        }
        let count = self.ids.len() as u64;
        let complete = match self.total {
            Some(total) if count > u64::from(total) => return Err("page exceeds reported total"),
            Some(total) if count == u64::from(total) => true,
            Some(_) if items.is_empty() => return Err("empty page before reported total"),
            Some(_) => false,
            None => items.is_empty(),
        };
        if !complete && self.pages >= self.max_pages {
            return Err("page limit reached before enumeration completed");
        }
        Ok(complete)
    }
}
/// Lowered from 30 minutes to 5: each tick is a cheap `TotalRecordCount`-only probe per
/// library, not a full resync, so tightening this doesn't multiply sync cost. Near-instant
/// updates for a live session remain the WS `LibraryChanged`/`NeedsReconcile` paths' job;
/// this is the backstop for whatever those miss.
const RECONCILE_INTERVAL: std::time::Duration = std::time::Duration::from_secs(5 * 60);
/// How often a paused breadth walk re-checks `playback_active`; cheap, so it can be short.
const PLAYBACK_YIELD_POLL: std::time::Duration = std::time::Duration::from_millis(250);
/// Cap on WS deltas `bus_listener` buffers during initial sync; past this, a full reconcile
/// serves better than replaying a huge, possibly-stale backlog.
const WS_DELTA_BUFFER_CAP: usize = 10_000;
/// Mirror `meta` key holding the incremental delta cursor; see [`delta_sync`]. RFC 3339,
/// the shape `minDateLastSaved` wants, unlike the debugging-only Unix-millis `last_full_sync`.
const LAST_DELTA_SYNC_KEY: &str = "last_delta_sync";
/// How far back of the stored cursor each delta query reaches, absorbing client-ahead-of-
/// server clock skew up to 5 minutes (the cursor is a client clock reading compared against
/// server clock readings). Re-fetching the overlap is free: every write here is an
/// idempotent upsert keyed on item id.
const DELTA_OVERLAP_SLACK: chrono::TimeDelta = chrono::TimeDelta::minutes(5);

/// Fields not returned by default that the mirror's columns/search index need.
fn item_fields() -> Vec<String> {
    [
        "Overview",
        "OriginalTitle",
        "SeriesName",
        "DateCreated",
        "PremiereDate",
        "ImageBlurHashes",
        // The server only returns `ParentId` when explicitly requested; see
        // `rows::browse_parent_id` for how it's mapped onto the browse-time `parent_id`.
        "ParentId",
        // Fields-gated, unlike `ParentBackdropImageTags`/`ParentBackdropItemId`.
        "SeriesPrimaryImageTag",
        // Fields-gated; §1.2 (docs/16-library-sort-filter.md).
        "Genres",
        // Fields-gated: without this, `sort_name` always falls back to the display name.
        "SortName",
    ]
    .into_iter()
    .map(String::from)
    .collect()
}

/// The item type that should appear as a *direct child* of a view when browsing into it.
/// Jellyfin's own `/Items?ParentId=<view>` flattens through intermediate physical-folder
/// nodes server-side, but the mirror's `children()` is plain `parent_id` equality with no
/// server-side flattening available, so the sync engine reproduces it at write time: this
/// one item type per view gets `parent_id` forced to the view's id. Genuinely nested content
/// (Season under Series, Episode under Season) is untouched.
fn browse_root_item_type(collection_type: &str) -> Option<BaseItemKind> {
    match collection_type {
        "movies" => Some(BaseItemKind::Movie),
        "tvshows" => Some(BaseItemKind::Series),
        "boxsets" => Some(BaseItemKind::BoxSet),
        "music" => Some(BaseItemKind::MusicAlbum),
        "musicvideos" => Some(BaseItemKind::MusicVideo),
        "homevideos" => Some(BaseItemKind::Video),
        _ => None,
    }
}

/// Forces `parent_id` to `view_uuid` on every item in `items` whose type is `root_type` --
/// the browse-flattening [`browse_root_item_type`]'s doc comment describes, applied in place
/// to one already-fetched batch. A `None` `root_type`/`view_uuid` (collection type has no
/// root browse type, or `view_id` isn't a valid uuid) is a no-op. Shared by the breadth walk
/// (`sync_library_breadth`), cross-library delta upserts (`upsert_cross_library_items`), and
/// the reconcile sweep's missing-id fetch (`reconcile_sweep_inner`) so the three can't drift
/// apart.
fn flatten_root_parents_in_place(
    items: &mut [BaseItemDto],
    root_type: Option<BaseItemKind>,
    view_uuid: Option<uuid::Uuid>,
) {
    let (Some(root_type), Some(view_uuid)) = (root_type, view_uuid) else {
        return;
    };
    for item in items {
        if item.type_ == Some(root_type) {
            item.parent_id = Some(view_uuid);
        }
    }
}

/// The "same universe" `ItemQuery` shape shared by `sync_library_breadth`'s breadth walk,
/// `reconcile_view`'s server-count/newest-ids probes, and `id_sweep_query`'s enumeration:
/// `parent_id` + `recursive`, no `isMissing`, no `minDateLastSaved`. This unrestricted,
/// un-type-filtered recursive query defines "the sync universe" for a library -- every call
/// site built from it sees whatever the server's actual filtering rule is identically,
/// regardless of which default is in play. Each caller layers its own paging/sort/fields
/// extras on top; only what defines the universe lives here.
fn library_universe_query(view_id: &str) -> ItemQuery {
    ItemQuery {
        parent_id: Some(view_id.to_string()),
        recursive: true,
        is_missing: None,
        min_date_last_saved: None,
        ..ItemQuery::new()
    }
}

/// True for the whole duration of `spawn`'s startup pass (cold or warm); its `Drop` clears
/// `MirrorState::startup_pass_active` when that task's async block returns by any path,
/// including cancellation -- same shape as `InitialSyncGuard` below.
struct StartupPassGuard(Weak<MirrorState>);

impl Drop for StartupPassGuard {
    fn drop(&mut self) {
        if let Some(state) = self.0.upgrade() {
            state.startup_pass_active.store(false, Ordering::Release);
        }
    }
}

/// Spawns the sync engine: the startup pass (initial sync if the mirror was empty,
/// otherwise a light refresh + reconcile), the WebSocket delta listener, and the idle
/// reconciliation timer. Background tasks hold only a `Weak`, so a dropped `Mirror` lets
/// them exit rather than forcing a full initial sync to completion first.
pub(crate) fn spawn(
    state: Arc<MirrorState>,
    bus: broadcast::Receiver<BusEvent>,
    needs_initial_sync: bool,
) {
    if needs_initial_sync {
        // Set before `bus_listener` is spawned, so a WS delta can't reach it and be applied
        // directly before the startup task below flips this flag itself.
        state
            .initial_sync_in_progress
            .store(true, Ordering::Release);
    }
    // Covers both branches below; see `MirrorState::startup_pass_active`.
    state.startup_pass_active.store(true, Ordering::Release);

    let startup_weak = Arc::downgrade(&state);
    tokio::spawn(async move {
        let _startup_pass = StartupPassGuard(startup_weak.clone());
        if needs_initial_sync {
            initial_sync(&startup_weak).await;
        } else {
            with_upgraded!(startup_weak, |s| sync_views(&s).await);

            // Warm-launch resume refresh: nothing else in this pass can observe a
            // UserData-only change since the last launch (see `sync_resume`). Run early,
            // mirroring `initial_sync`'s ordering, so a stale top-row is corrected first.
            with_upgraded!(startup_weak, |s| sync_resume(&s).await);

            with_upgraded!(startup_weak, |s| {
                s.request_next_up_refresh();
                // One-time-per-launch heal for rows an older delta sync left under their
                // physical folder id (see `WriteCmd::FlattenRootParents`); idempotent,
                // index-served.
                for (view_id, collection_type) in current_views(&s).await {
                    if let Some(root_type) = browse_root_item_type(&collection_type) {
                        s.writer
                            .flatten_root_parents(view_id, root_type.to_string())
                            .await;
                    }
                }
            });

            // After everything Home's first frames need, before the long delta/reconcile work.
            with_upgraded!(startup_weak, |s| {
                ensure_index_group(&s, IndexGroup::Favorites).await;
                ensure_index_group(&s, IndexGroup::Resume).await;
            });

            // Delta before reconcile, at every trigger: delta is the fast path (new/updated
            // items visible in seconds); reconcile is the backstop for what delta can't see
            // (deletions), and usually finds the library already in agreement after delta runs.
            with_upgraded!(startup_weak, |s| delta_sync(&s).await);

            with_upgraded!(startup_weak, |s| reconcile_all(&s).await);

            // Last, so launch never waits on it: favorites are one shelf below the fold.
            with_upgraded!(startup_weak, |s| sync_favorites(&s).await);
        }
    });

    tokio::spawn(next_up_refresher(
        Arc::downgrade(&state),
        state.next_up_requested.clone(),
    ));
    tokio::spawn(bus_listener(Arc::downgrade(&state), bus));
    tokio::spawn(reconcile_timer(Arc::downgrade(&state)));
}

/// docs/07 §1: after a Next Up fetch, further requests wait this long and collapse into one,
/// so a stop's own echo, launch's overlapping passes or a bulk mark cost one extra fetch at most.
const NEXT_UP_COOLDOWN: std::time::Duration = std::time::Duration::from_millis(1500);

/// The only caller of [`refresh_next_up`] outside tests: the first request fetches at once
/// (returning Home shows the change), later ones within [`NEXT_UP_COOLDOWN`] fetch once at its
/// end. Wakes each minute to notice a dropped `Mirror`.
async fn next_up_refresher(state: Weak<MirrorState>, requested: Arc<tokio::sync::Notify>) {
    loop {
        if tokio::time::timeout(std::time::Duration::from_secs(60), requested.notified())
            .await
            .is_err()
        {
            if state.strong_count() == 0 {
                break;
            }
            continue;
        }
        let Some(s) = state.upgrade() else { break };
        refresh_next_up(&s).await;
        drop(s);
        tokio::time::sleep(NEXT_UP_COOLDOWN).await;
    }
}

/// Whether any of `ids` is an Episode row, the only kind whose watch state moves Next Up.
async fn any_episode(state: &MirrorState, ids: Vec<String>) -> bool {
    let pool = state.read_pool.clone();
    tokio::task::spawn_blocking(move || crate::query::any_episode(&pool.acquire(), &ids))
        .await
        .unwrap_or(false)
}

/// "Resume + NextUp + Latest first, then breadth" -- Home is renderable as soon
/// as the first pages land. Takes a `Weak` so a dropped `Mirror` lets this abandon cleanly
/// between checkpoints instead of forcing the full sync to finish first.
///
/// RAII accounting for [`MirrorState::breadth_syncs_in_flight`]: `enter` increments, `Drop`
/// decrements on every exit path. Terminal-`Idle` sites drop it explicitly first so the
/// decrement orders before the emission.
struct BreadthSyncGuard(Weak<MirrorState>);

impl BreadthSyncGuard {
    fn enter(state: &Weak<MirrorState>) -> Option<Self> {
        let s = state.upgrade()?;
        s.breadth_syncs_in_flight.fetch_add(1, Ordering::AcqRel);
        Some(Self(Weak::clone(state)))
    }
}

impl Drop for BreadthSyncGuard {
    fn drop(&mut self) {
        if let Some(s) = self.0.upgrade() {
            s.breadth_syncs_in_flight.fetch_sub(1, Ordering::AcqRel);
        }
    }
}

/// Releases initial-sync ownership on success, fetch failure, and task cancellation.
struct InitialSyncGuard(Weak<MirrorState>);

impl Drop for InitialSyncGuard {
    fn drop(&mut self) {
        if let Some(state) = self.0.upgrade() {
            state
                .initial_sync_in_progress
                .store(false, Ordering::Release);
            state.initial_sync_done.notify_waiters();
            state.sync_activity.send_replace(SyncActivity::Idle);
        }
    }
}

pub(crate) async fn initial_sync(state: &Weak<MirrorState>) {
    // Captured before the first fetch, stamped as the delta cursor at the end (see
    // `delta_sync`): the start instant means the first delta pass re-covers whatever the
    // server saved during this walk's minutes-long run, instead of leaving a hole.
    let started_at = now_rfc3339();

    let _completion = with_upgraded!(state, |s| {
        s.initial_sync_in_progress.store(true, Ordering::Release);
        let completion = InitialSyncGuard(state.clone());
        sync_views(&s).await;
        completion
    });

    with_upgraded!(state, |s| sync_resume(&s).await);

    with_upgraded!(state, |s| s.request_next_up_refresh());

    // `sync_views`'s replace was fire-and-forget; wait for it to commit before reading views
    // back, or the read pool can observe zero views and skip the breadth phase.
    let views = with_upgraded!(state, |s| {
        s.writer.barrier().await;
        current_views(&s).await
    });

    for (view_id, collection_type) in views {
        if !sync_library_breadth(state, &view_id, &collection_type).await {
            // Commit earlier pages before releasing buffered events; keep cursors unchanged.
            if let Some(s) = state.upgrade() {
                s.writer.barrier().await;
            }
            return;
        }
        if collection_type == "boxsets" {
            with_upgraded!(state, |s| sync_boxsets_membership(&s, &view_id).await);
        }
    }

    with_upgraded!(state, |s| {
        if s.writer.barrier().await {
            s.writer.set_meta("last_full_sync", now_iso()).await;
            s.writer.set_meta(LAST_DELTA_SYNC_KEY, started_at).await;
        } else {
            // Never bank a cursor over a failed transaction: the next launch must reconcile
            // again.
            tracing::error!(
                "initial sync had a failed mirror write; leaving sync cursors unchanged"
            );
        }
    });
}

async fn sync_views(state: &MirrorState) {
    match state.client.get_user_views().await {
        Ok(items) => {
            let rows: Vec<crate::ViewRow> = items
                .into_iter()
                .filter_map(|v| {
                    Some(crate::ViewRow {
                        id: v.id?.to_string(),
                        name: v.name.unwrap_or_default(),
                        collection_type: v
                            .collection_type
                            .map(|c| c.to_string())
                            .unwrap_or_default(),
                        item_type: v.type_.map(|t| t.to_string()).unwrap_or_default(),
                    })
                })
                .collect();
            // `/UserViews` is a complete snapshot; replacing it also removes cache rows
            // belonging to revoked/deleted views in the same transaction.
            state.writer.replace_views(rows).await;
        }
        Err(e) => tracing::error!(error = %e, "failed to fetch user views"),
    }
}

/// Refreshes `/UserItems/Resume` into the mirror. The only mirror write that can observe a
/// UserData-only change (a play/stop with no metadata edit) for an item outside this
/// session: `delta_sync` filters on metadata save time, not `UserData` save time, and the WS
/// `UserDataChanged` event never echoes back to the originating session either. Called from
/// `initial_sync`, the warm-launch startup pass, and reconciliation's `NeedsReconcile` arm.
/// Full `item_fields()`, matching every other whole-persisting fetch (a field-less Resume
/// response used to upsert-blank `Overview`/`SeriesName`/etc.).
async fn sync_resume(state: &MirrorState) {
    match state.client.get_resume_items(&item_fields()).await {
        Ok(result) => state.writer.upsert_items(result.items).await,
        Err(e) => tracing::error!(error = %e, "failed to fetch resume items"),
    }
}

/// Builds a deferred [`IndexGroup`] once (docs/25 §4.8), then emits a `Refresh` so screens
/// re-query what it gates. A no-op once built.
pub(crate) async fn ensure_index_group(state: &MirrorState, group: IndexGroup) {
    if state.index_group_ready(group) {
        return;
    }
    if state.writer.build_index_group(group).await {
        state
            .ready_index_groups
            .fetch_or(group.bit(), Ordering::AcqRel);
        let _ = state.changes_tx.send(MirrorChange::Refresh);
    }
}

/// Makes the mirror's favorite flags match the server's per-user favorites list, since
/// `delta_sync` can't see a UserData-only change: a favorite toggled on another client while
/// this one was closed would otherwise never land. Always runs after reconcile, never ahead of
/// anything Home's first frame needs; initial sync skips it (breadth DTOs carry `IsFavorite`).
/// Id-only pages; any page error or incomplete enumeration leaves the flags untouched, since
/// a partial list would clear real favorites.
pub(crate) async fn sync_favorites(state: &MirrorState) {
    const MAX_PAGES: u32 = 100;
    let mut start_index: u32 = 0;
    let mut ids: Vec<String> = Vec::new();
    let mut progress = PageProgress::new(MAX_PAGES);
    let since = crate::writer::now_millis();
    loop {
        let query = ItemQuery {
            recursive: true,
            is_favorite: Some(true),
            start_index,
            limit: ID_SWEEP_PAGE_SIZE,
            enable_images: Some(false),
            enable_user_data: Some(false),
            ..ItemQuery::new()
        };
        let result = match state.client.get_items(&query).await {
            Ok(r) => r,
            Err(e) => {
                tracing::error!(error = %e, start_index, "failed to fetch favorites page");
                return;
            }
        };
        let complete = match progress.observe(&result.items, result.total_record_count) {
            Ok(complete) => complete,
            Err(reason) => {
                tracing::warn!(
                    reason,
                    "favorites enumeration incomplete; keeping prior flags"
                );
                return;
            }
        };
        start_index += u32::try_from(result.items.len()).unwrap_or(u32::MAX);
        ids.extend(
            result
                .items
                .iter()
                .filter_map(|i| i.id.map(|id| id.to_string())),
        );
        if complete {
            break;
        }
    }
    state.writer.set_favorite_ids(ids, since).await;
}

/// Converts [`crate::NextUpOptions::cutoff_days`] into the RFC3339 UTC
/// instant `nextUpDateCutoff` expects: "now minus `days` days". Pure (no
/// clock dependency beyond `Utc::now()`) so the day-arithmetic itself is
/// unit-testable without a live clock.
fn next_up_date_cutoff(days: u32) -> String {
    (chrono::Utc::now() - chrono::Duration::days(i64::from(days)))
        .to_rfc3339_opts(chrono::SecondsFormat::Secs, true)
}

pub(crate) async fn refresh_next_up(state: &MirrorState) {
    let options = *state
        .next_up_options
        .lock()
        .unwrap_or_else(|e| e.into_inner());
    let api_options = jellyfin_api::NextUpOptions {
        date_cutoff: options.cutoff_days.map(next_up_date_cutoff),
        enable_rewatching: options.rewatching,
    };
    // Full `item_fields()`, same as every other fetch this module persists:
    // these DTOs are upserted whole over the mirror's rows below, and a
    // field-less NextUp response omits `Overview` etc. -- which is how the
    // currently-watched episode kept losing its synopsis (round-3 item 2).
    match state.client.get_next_up(&item_fields(), &api_options).await {
        Ok(result) => {
            let ids: Vec<String> = result
                .items
                .iter()
                .filter_map(|i| i.id.map(|u| u.to_string()))
                .collect();
            state.writer.upsert_items(result.items).await;
            // Diffed against the stored list and only broadcast on an actual move (see
            // `WriteCmd::SetNextUpIds`): membership/order can change with no item DTO
            // changing, which the per-item upsert change event above can't catch.
            state.writer.set_next_up_ids(ids).await;
        }
        Err(e) => tracing::error!(error = %e, "failed to fetch next up"),
    }
}

/// Full recursive paged sync of one library (view), page size 500, one writer command per
/// page (bounded transactions, incremental change-feed emission). See `browse_root_item_type`
/// for why `collection_type` is needed.
///
/// Takes a `Weak`, re-upgraded once per page, so a dropped `Mirror` is noticed between pages.
/// Returns `false` if it had to abandon this way (or on a fetch error), `true` on normal
/// completion.
///
/// Convergence (see `writer::apply_prune_library`): the complete `seen_ids` set is handed to
/// the writer as the library's new membership only on normal completion; an early `false`
/// return skips pruning since `seen_ids` would be an incomplete snapshot.
async fn sync_library_breadth(
    state: &Weak<MirrorState>,
    view_id: &str,
    collection_type: &str,
) -> bool {
    let Some(guard) = BreadthSyncGuard::enter(state) else {
        tracing::debug!(
            view_id,
            "mirror dropped before breadth sync began; abandoning"
        );
        return false;
    };
    // Marks the prune's `since_rowid` guard (docs/12; see `apply_prune_library`): snapshotted
    // before enumeration starts, so a `LibraryChanged` insert landing mid-walk always gets a
    // higher `rowid` and survives even if it missed `seen_ids`.
    let Some(s) = state.upgrade() else {
        tracing::debug!(
            view_id,
            "mirror dropped before breadth sync began; abandoning"
        );
        return false;
    };
    let breadth_started_at = max_item_rowid(&s).await;
    drop(s);
    let root_type = browse_root_item_type(collection_type);
    let view_uuid = uuid::Uuid::parse_str(view_id).ok();

    let mut start_index = 0u32;
    let mut pages_done = 0u32;
    // `TotalRecordCount` from the first page; the progress bar's denominator (None before that).
    let mut total_items: Option<u32> = None;
    let mut progress = PageProgress::new(MAX_SYNC_PAGES);
    loop {
        let Some(s) = state.upgrade() else {
            tracing::debug!(view_id, "mirror dropped mid-breadth-sync; abandoning");
            return false;
        };
        // Yield to playback: hold this walk between pages rather than competing for the
        // link. `continue` re-upgrades the Weak each poll, so a torn-down mirror still ends
        // the wait promptly.
        if s.playback_active.load(Ordering::Acquire) {
            drop(s);
            tokio::time::sleep(PLAYBACK_YIELD_POLL).await;
            continue;
        }

        // Both initial_sync's per-view loop and reconcile_view's triggered resync go
        // through here, covering all three activity sources in one place. Sent before the
        // page fetch so a poller sees Syncing for the page in flight, not the last-completed one.
        s.sync_activity.send_replace(SyncActivity::Syncing {
            library_name_or_id: view_id.to_string(),
            pages_done,
            items_done: start_index,
            total_items,
        });

        // Same "sync universe" shape as `reconcile_view`'s probes and `id_sweep_query`'s
        // enumeration -- see `library_universe_query`.
        let query = ItemQuery {
            sort_by: Some("SortName".to_string()),
            fields: item_fields(),
            start_index,
            limit: PAGE_SIZE,
            // Images and user data are what this walk exists to fetch; only
            // `id_sweep_query` turns them off.
            ..library_universe_query(view_id)
        };
        let result = match s.client.get_items(&query).await {
            Ok(r) => r,
            Err(e) => {
                tracing::error!(error = %e, view_id, start_index, "library page fetch failed");
                // Decrement before Idle: an observer reacting to Idle must already see
                // is_syncing() == false.
                drop(guard);
                s.sync_activity.send_replace(SyncActivity::Idle);
                return false;
            }
        };
        let page_len = result.items.len() as u32;
        let complete = match progress.observe(&result.items, result.total_record_count) {
            Ok(complete) => complete,
            Err(reason) => {
                tracing::warn!(
                    reason,
                    "library enumeration incomplete; keeping prior membership"
                );
                drop(guard);
                s.sync_activity.send_replace(SyncActivity::Idle);
                return false;
            }
        };
        total_items = progress.total;

        let mut items = result.items;
        flatten_root_parents_in_place(&mut items, root_type, view_uuid);
        // This recursive fetch returns every item under this library, so the whole page can
        // be stamped `library_id` in one authoritative batch (see schema.rs).
        s.writer
            .upsert_items_scoped(items, Some(view_id.to_string()))
            .await;

        pages_done += 1;
        start_index += page_len;
        if complete {
            let seen_ids = progress.ids.into_iter().map(|id| id.to_string()).collect();
            // Same-writer FIFO order (`WriteCmd::PruneLibrary`) means every id in seen_ids
            // is already committed by the time this runs.
            s.writer
                .prune_library(view_id.to_string(), seen_ids, breadth_started_at)
                .await;
            drop(guard);
            s.sync_activity.send_replace(SyncActivity::Idle);
            return true;
        }
        // `s` drops here, before the next iteration re-upgrades: the per-page liveness check.
    }
}

/// BoxSet membership isn't part of the breadth sync above (its own row is synced there, but
/// `/Items?ParentId=<view>` doesn't expand collection membership). Called once a `boxsets`
/// view's breadth sync has landed the BoxSet rows themselves.
async fn sync_boxsets_membership(state: &MirrorState, view_id: &str) {
    // The caller's breadth sync upsert was fire-and-forget; wait for it to commit before
    // querying BoxSet ids, or this races the write and finds nothing.
    state.writer.barrier().await;
    for boxset_id in current_boxset_ids(state, view_id).await {
        sync_one_boxset_membership(state, &boxset_id).await;
    }
}

/// Non-recursive `/Items?ParentId=<boxset_id>`, paged until membership is complete before
/// replacing `collection_members` once at the end. A partial replace would make
/// [`crate::query::collection_ids_containing`] report a real member as absent
/// (docs/19-detail-action-menu.md §1.1/§2.2), so any page error returns without touching
/// `collection_members`, leaving a stale-but-complete table. Also upserts every page's items,
/// since a BoxSet can reference an item not otherwise in any synced view.
pub(crate) async fn sync_one_boxset_membership(state: &MirrorState, boxset_id: &str) {
    const MAX_PAGES: u32 = 100;

    let mut start_index: u32 = 0;
    // Position counts across pages (server curation order), so members above 500 don't
    // collapse onto positions 0..PAGE_SIZE.
    let mut members: Vec<(String, i64)> = Vec::new();
    let mut progress = PageProgress::new(MAX_PAGES);

    loop {
        let query = ItemQuery {
            sort_order: None,
            parent_id: Some(boxset_id.to_string()),
            include_item_types: Vec::new(),
            recursive: false,
            sort_by: None,
            fields: item_fields(),
            start_index,
            limit: PAGE_SIZE,
            ids: Vec::new(),
            is_missing: None,
            min_date_last_saved: None,
            ..ItemQuery::new()
        };
        let result = match state.client.get_items(&query).await {
            Ok(r) => r,
            Err(e) => {
                tracing::error!(
                    error = %e,
                    boxset_id,
                    start_index,
                    "failed to fetch boxset membership page"
                );
                return;
            }
        };

        let page_len = result.items.len();
        let complete = match progress.observe(&result.items, result.total_record_count) {
            Ok(complete) => complete,
            Err(reason) => {
                tracing::warn!(
                    reason,
                    "collection enumeration incomplete; keeping prior membership"
                );
                return;
            }
        };
        for item in &result.items {
            if let Some(id) = item.id {
                members.push((id.to_string(), members.len() as i64));
            }
        }
        state.writer.upsert_items(result.items).await;
        start_index += page_len as u32;

        if complete {
            break;
        }
    }

    drop(progress);
    state
        .writer
        .set_collection_members(boxset_id.to_string(), members)
        .await;
}

/// Ids of the `BoxSet` items synced as direct children of `view_id`. Wrapped in
/// `spawn_blocking`: `pool.acquire()` blocks and must not run inline on a tokio worker.
async fn current_boxset_ids(state: &MirrorState, view_id: &str) -> Vec<String> {
    let pool = state.read_pool.clone();
    let view_id = view_id.to_string();
    tokio::task::spawn_blocking(move || {
        let conn = pool.acquire();
        let result: rusqlite::Result<Vec<String>> = (|| {
            let mut stmt =
                conn.prepare("SELECT id FROM items WHERE parent_id = ?1 AND item_type = 'BoxSet'")?;
            let rows = stmt.query_map([&view_id], |row| row.get(0))?;
            rows.collect()
        })();
        result.unwrap_or_else(|e| {
            tracing::error!(error = %e, "failed to read boxset ids for membership sync");
            Vec::new()
        })
    })
    .await
    .unwrap_or_else(|e| {
        tracing::error!(error = %e, "current_boxset_ids task panicked");
        Vec::new()
    })
}

/// `pool.acquire()` blocks, so wrapped in `spawn_blocking` via a cloned `Arc<ReadPool>`.
/// Excludes `item_type = 'Channel'` rows: every caller is a per-library sync walk, and a
/// channel's content is browsed live, never mirrored.
async fn current_views(state: &MirrorState) -> Vec<(String, String)> {
    let pool = state.read_pool.clone();
    tokio::task::spawn_blocking(move || {
        let conn = pool.acquire();
        let result: rusqlite::Result<Vec<(String, String)>> = (|| {
            let mut stmt = conn.prepare(
                "SELECT id, collection_type FROM views WHERE item_type IS NOT 'Channel'",
            )?;
            let rows = stmt.query_map([], |row| {
                Ok((
                    row.get(0)?,
                    row.get::<_, Option<String>>(1)?.unwrap_or_default(),
                ))
            })?;
            rows.collect()
        })();
        drop(conn);
        result.unwrap_or_else(|e| {
            tracing::error!(error = %e, "failed to read views for sync");
            Vec::new()
        })
    })
    .await
    .unwrap_or_else(|e| {
        tracing::error!(error = %e, "current_views task panicked");
        Vec::new()
    })
}

fn now_iso() -> String {
    // Debugging-only metadata; Unix millis is enough, no chrono needed.
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis().to_string())
        .unwrap_or_else(|_| "0".to_string())
}

// --- WebSocket deltas ------------------------------------------------------

/// While `initial_sync_in_progress` is set, incoming `BusEvent`s are buffered here instead of
/// applied immediately, so a WS delta for an item a later breadth-sync page hasn't reached
/// yet can't be silently clobbered by that page's now-stale snapshot.
///
/// Once sync completes, the buffer replays in order, except when it overflowed
/// `WS_DELTA_BUFFER_CAP` mid-sync: then it's dropped and `reconcile_after_sync` is set
/// instead, so one `reconcile_all` cleans up rather than replaying a gap-y backlog.
async fn bus_listener(state: Weak<MirrorState>, mut bus: broadcast::Receiver<BusEvent>) {
    let mut buffer: Vec<BusEvent> = Vec::new();

    loop {
        let Some(s) = state.upgrade() else { return };

        if !s.initial_sync_in_progress.load(Ordering::Acquire) {
            if s.reconcile_after_sync.swap(false, Ordering::AcqRel) {
                tracing::info!(
                    "running a post-initial-sync reconciliation after the WS delta buffer overflowed"
                );
                reconcile_all(&s).await;
            } else if !buffer.is_empty() {
                tracing::debug!(
                    count = buffer.len(),
                    "replaying WS deltas buffered during initial sync"
                );
                for event in buffer.drain(..) {
                    apply_bus_event(&s, event).await;
                }
            }
        }

        // Clone the small `Arc<Notify>` handle (not the whole `MirrorState`)
        // and drop `s` before awaiting it below -- otherwise the `Notified`
        // future would keep the entire `MirrorState` alive for as long as
        // this task is waiting on the next bus event, which is exactly the
        // strong-reference-for-the-whole-wait problem `Weak` is here to
        // avoid. Registered *before* `drop(s)` (rather than checked only via
        // the atomic above) so a sync that completes in the gap between
        // this point and the `select!` below still wakes this task promptly
        // instead of waiting for the next bus event.
        let done = s.initial_sync_done.clone();
        let synced = done.notified();
        tokio::pin!(synced);
        drop(s);

        tokio::select! {
            biased;
            recv = bus.recv() => {
                let Some(s) = state.upgrade() else { return };
                match recv {
                    Ok(event) => {
                        if s.initial_sync_in_progress.load(Ordering::Acquire) {
                            if buffer.len() >= WS_DELTA_BUFFER_CAP {
                                tracing::warn!(
                                    buffered = buffer.len(),
                                    "WS delta buffer overflowed during initial sync; dropping it and scheduling a reconcile once sync completes"
                                );
                                buffer.clear();
                                s.reconcile_after_sync.store(true, Ordering::Release);
                            } else {
                                buffer.push(event);
                            }
                        } else {
                            apply_bus_event(&s, event).await;
                        }
                    }
                    Err(broadcast::error::RecvError::Lagged(skipped)) => {
                        tracing::warn!(
                            skipped,
                            "mirror lagged behind the event bus; forcing reconciliation"
                        );
                        if s.initial_sync_in_progress.load(Ordering::Acquire) {
                            // The buffer itself may now have gaps too (we
                            // don't know what was skipped); dropping it and
                            // reconciling after sync is the same safe
                            // fallback as an outright overflow.
                            buffer.clear();
                            s.reconcile_after_sync.store(true, Ordering::Release);
                        } else {
                            reconcile_all(&s).await;
                        }
                    }
                    Err(broadcast::error::RecvError::Closed) => return,
                }
            }
            _ = &mut synced => {
                // Initial sync just completed; loop back around to drain
                // the buffer (or run the scheduled reconcile) at the top.
            }
        }
    }
}

async fn apply_bus_event(state: &MirrorState, event: BusEvent) {
    match event {
        BusEvent::Server(server_event) => apply_server_event(state, server_event).await,
        BusEvent::Connected | BusEvent::Disconnected => {}
        // Resume, then delta, then reconcile, same order as `spawn`'s startup path: this is
        // the reconnect signal, so delta covers the disconnected window and sync_resume
        // covers what delta can't (a UserData-only change this session's own WS never saw).
        BusEvent::NeedsReconcile => {
            sync_resume(state).await;
            // A `UserDataChanged` sent while disconnected is lost, and delta/reconcile only
            // see library items, so Next Up is asked for on every (re)connect.
            state.request_next_up_refresh();
            // The first post-launch connect's delta+reconcile would duplicate the startup
            // pass already covering the same ground while it's still running; every later
            // (re)connect always runs in full. See `MirrorState::startup_pass_active`.
            let is_first_connect = !state.first_reconcile_handled.swap(true, Ordering::AcqRel);
            if is_first_connect && state.startup_pass_active.load(Ordering::Acquire) {
                tracing::debug!(
                    "first connect's reconcile is covered by the in-progress startup pass; skipping delta+reconcile"
                );
                return;
            }
            delta_sync(state).await;
            reconcile_all(state).await;
            sync_favorites(state).await;
        }
    }
}

pub(crate) async fn apply_server_event(state: &MirrorState, event: ServerEvent) {
    match event {
        ServerEvent::LibraryChanged {
            added,
            updated,
            removed,
        } => {
            let mut changed: Vec<String> = added.into_iter().chain(updated).collect();
            changed.sort();
            changed.dedup();

            // A LibraryChanged touching a BoxSet needs its membership re-fetched; the item
            // upsert above only refreshes the BoxSet's own row.
            let mut changed_boxset_ids: Vec<String> = Vec::new();
            for chunk in changed.chunks(WS_BATCH_SIZE) {
                let items = fetch_and_upsert_ids(state, chunk.to_vec()).await;
                changed_boxset_ids.extend(items.iter().filter_map(|item| {
                    (item.type_ == Some(BaseItemKind::BoxSet))
                        .then(|| item.id.map(|id| id.to_string()))
                        .flatten()
                }));
            }

            if !removed.is_empty() {
                state.writer.remove_items(removed).await;
            }

            if !changed_boxset_ids.is_empty() {
                // Wait for the BoxSet rows to commit first, so the row always lands before
                // its membership does.
                state.writer.barrier().await;
                for boxset_id in changed_boxset_ids {
                    sync_one_boxset_membership(state, &boxset_id).await;
                }
            }

            if !changed.is_empty() {
                // Next Up shifts whenever library contents change; cheap to refresh
                // opportunistically. Wait for the upserts above to commit first.
                state.writer.barrier().await;
                state.request_next_up_refresh();
            }
        }
        ServerEvent::UserDataChanged { item_userdata } => {
            let ids: Vec<String> = item_userdata.iter().map(|(id, _)| id.clone()).collect();
            state.writer.apply_user_data(item_userdata).await;
            // A watch on another device moves Next Up; a movie's never does.
            if any_episode(state, ids).await {
                state.request_next_up_refresh();
            }
        }
        ServerEvent::ForceKeepAlive | ServerEvent::Ignored(_) => {}
    }
}

/// Fetches and upserts a batch of items by id, returning what was fetched (the caller
/// notices any BoxSets). Unlike a breadth-sync page, a `LibraryChanged` batch can span
/// several libraries, so each item's `library_id` is resolved individually (see
/// `resolve_library_ids_for_changed_items`).
pub(crate) async fn fetch_and_upsert_ids(
    state: &MirrorState,
    ids: Vec<String>,
) -> Vec<BaseItemDto> {
    if ids.is_empty() {
        return Vec::new();
    }
    let query = ItemQuery {
        sort_order: None,
        parent_id: None,
        include_item_types: Vec::new(),
        recursive: true,
        sort_by: None,
        fields: item_fields(),
        start_index: 0,
        limit: ids.len() as u32,
        ids,
        is_missing: None,
        min_date_last_saved: None,
        ..ItemQuery::new()
    };
    match state.client.get_items(&query).await {
        Ok(result) => {
            upsert_cross_library_items(state, &result.items).await;
            result.items
        }
        Err(e) => {
            tracing::error!(error = %e, "failed to fetch changed items by id");
            Vec::new()
        }
    }
}

/// Upserts a batch of items that may span several libraries: resolves each item's owning
/// view id individually, groups by it (including a "still unresolved" `None` group), and
/// sends each same-library subgroup as one `upsert_items_scoped` call. Extracted so
/// [`delta_sync`] can reuse it: a `minDateLastSaved` page is keyed on a timestamp, not a
/// parent, so `sync_library_breadth`'s "stamp the whole page with one view id" shortcut is
/// wrong for it.
///
/// Returns whether any subgroup actually changed a row -- see `delta_pass`'s use of this to
/// decide whether re-fetching Next Up is worth it, instead of the server's raw
/// `minDateLastSaved` match count, which a metadata-only re-save can satisfy with nothing the
/// no-op comparison lets through.
async fn upsert_cross_library_items(state: &MirrorState, items: &[BaseItemDto]) -> bool {
    if items.is_empty() {
        return false;
    }
    let library_ids = resolve_library_ids_for_changed_items(state, items).await;
    let mut groups: std::collections::HashMap<Option<String>, Vec<BaseItemDto>> =
        std::collections::HashMap::new();
    for (item, library_id) in items.iter().cloned().zip(library_ids) {
        groups.entry(library_id).or_default().push(item);
    }
    // Same root-type parent flattening as `sync_library_breadth` (see `browse_root_item_type`):
    // without forcing a root-level item's `parent_id` onto the view id, `children(view_id)`
    // never lists it (root-level series vanish from their library grid).
    let views = current_views(state).await;
    for (library_id, group_items) in groups.iter_mut() {
        let Some(library_id) = library_id else {
            continue;
        };
        let Some((_, collection_type)) = views.iter().find(|(id, _)| id == library_id) else {
            continue;
        };
        let root_type = browse_root_item_type(collection_type);
        let view_uuid = uuid::Uuid::parse_str(library_id).ok();
        flatten_root_parents_in_place(group_items, root_type, view_uuid);
    }
    let mut any_changed = false;
    for (library_id, group_items) in groups {
        let changed = state
            .writer
            .upsert_items_scoped_reporting(group_items, library_id)
            .await;
        any_changed |= !changed.is_empty();
    }
    any_changed
}

/// Resolves each item's owning library id purely from already-synced ancestor rows, no
/// network calls. Tries `series_id`, then `season_id`, then `parent_id`, in that order.
/// `None` for a brand new root-level item with nothing local to walk to.
async fn resolve_library_ids_via_ancestors(
    state: &MirrorState,
    items: &[BaseItemDto],
) -> Vec<Option<String>> {
    let pool = state.read_pool.clone();
    let candidates: Vec<Vec<String>> = items
        .iter()
        .map(|item| {
            [
                item.series_id.map(|u| u.to_string()),
                item.season_id.map(|u| u.to_string()),
                item.parent_id.map(|u| u.to_string()),
            ]
            .into_iter()
            .flatten()
            .collect()
        })
        .collect();

    tokio::task::spawn_blocking(move || {
        let conn = pool.acquire();
        candidates
            .into_iter()
            .map(|ancestor_ids| {
                ancestor_ids.into_iter().find_map(|ancestor_id| {
                    conn.query_row(
                        "SELECT library_id FROM items WHERE id = ?1",
                        [&ancestor_id],
                        |row| row.get::<_, Option<String>>(0),
                    )
                    .ok()
                    .flatten()
                })
            })
            .collect()
    })
    .await
    .unwrap_or_else(|e| {
        tracing::error!(error = %e, "resolve_library_ids_via_ancestors task panicked");
        items.iter().map(|_| None).collect()
    })
}

/// After the ancestor-chain lookup above, anything still unresolved is probed against each
/// known library's recursive membership directly. Bounded by the number of libraries, not
/// the number of changed items, and stops once everything's resolved.
async fn resolve_library_ids_for_changed_items(
    state: &MirrorState,
    items: &[BaseItemDto],
) -> Vec<Option<String>> {
    let mut resolved = resolve_library_ids_via_ancestors(state, items).await;
    if !resolved.iter().any(Option::is_none) {
        return resolved;
    }

    let ids: Vec<Option<String>> = items.iter().map(|i| i.id.map(|u| u.to_string())).collect();

    for (view_id, _) in current_views(state).await {
        let unresolved_ids: Vec<String> = ids
            .iter()
            .zip(&resolved)
            .filter_map(|(id, lib)| lib.is_none().then(|| id.clone()).flatten())
            .collect();
        if unresolved_ids.is_empty() {
            break;
        }

        let query = ItemQuery {
            parent_id: Some(view_id.clone()),
            recursive: true,
            ids: unresolved_ids.clone(),
            limit: unresolved_ids.len() as u32,
            ..ItemQuery::new()
        };
        match state.client.get_items(&query).await {
            Ok(result) => {
                for found in &result.items {
                    let Some(found_id) = found.id.map(|u| u.to_string()) else {
                        continue;
                    };
                    if let Some(pos) = ids
                        .iter()
                        .position(|id| id.as_deref() == Some(found_id.as_str()))
                    {
                        if resolved[pos].is_none() {
                            resolved[pos] = Some(view_id.clone());
                        }
                    }
                }
            }
            Err(e) => tracing::error!(
                error = %e,
                view_id,
                "failed to probe view membership for unresolved changed items"
            ),
        }
    }
    resolved
}

// --- Incremental delta sync -------------------------------------------------

/// Current UTC instant in the RFC 3339 shape `minDateLastSaved` accepts. Second granularity
/// is deliberate: the cursor is only ever compared with a 5-minute overlap slack applied.
fn now_rfc3339() -> String {
    chrono::Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Secs, true)
}

/// Pure cursor arithmetic, unit-testable without the network/DB glue: the stored cursor
/// minus [`DELTA_OVERLAP_SLACK`] is what the next query asks the server for. `None` if the
/// stored cursor isn't a timestamp we wrote.
pub(crate) fn delta_query_since(cursor: &str) -> Option<String> {
    let parsed = chrono::DateTime::parse_from_rfc3339(cursor).ok()?;
    Some(
        (parsed.with_timezone(&chrono::Utc) - DELTA_OVERLAP_SLACK)
            .to_rfc3339_opts(chrono::SecondsFormat::Secs, true),
    )
}

/// Incremental delta sync: one small recursive `/Items` query for everything the server has
/// added or updated since the stored cursor, upserted straight into the mirror. Covers what
/// WS `LibraryChanged` misses while disconnected, and what `reconcile_view`'s count/newest-id
/// probes miss entirely (an in-place update, e.g. a resolution upgrade, changes neither).
/// `minDateLastSaved` answers both in one request (see `ItemQuery::min_date_last_saved`).
///
/// Cursor rule: never move the cursor past a change not yet stored. The server never
/// returns `DateLastSaved` on an item, so the cursor can't be derived from the response --
/// instead, capture the client's clock once before the first request, and only on full
/// success store that captured instant (never "now at the end", which would skip changes
/// made during the pass). Any page error leaves the cursor unwritten, so a partial pass is
/// redone in full. The captured instant is a client reading, so [`DELTA_OVERLAP_SLACK`]
/// absorbs client-ahead-of-server clock skew; every write here is an idempotent upsert, so
/// re-fetching the overlap is free. An empty response still advances the cursor like any
/// other success.
///
/// Does not cover deletions (no `DateLastSaved` moves when an item is removed) -- that stays
/// [`reconcile_view`]'s job, run after delta at every trigger.
///
/// Single-flight, same pattern as [`reconcile_all`]: a trigger arriving mid-pass sets
/// `delta_pending` and the running pass reruns once, deferred rather than lost.
pub(crate) async fn delta_sync(state: &MirrorState) {
    // Initial sync owns first population and the first cursor stamp; running a delta
    // alongside it would duplicate work and race that stamp.
    if state.initial_sync_in_progress.load(Ordering::Acquire) {
        tracing::debug!("initial sync in progress; skipping delta sync");
        return;
    }
    if state.delta_in_progress.swap(true, Ordering::AcqRel) {
        tracing::debug!("delta sync already in progress; deferring this trigger");
        state.delta_pending.store(true, Ordering::Release);
        return;
    }
    loop {
        delta_pass(state).await;
        if !state.delta_pending.swap(false, Ordering::AcqRel) {
            break;
        }
        tracing::debug!("rerunning delta sync for a trigger deferred mid-pass");
    }
    state.delta_in_progress.store(false, Ordering::Release);
}

/// One delta pass. See [`delta_sync`] for the cursor rule; the single-flight guard lives in
/// the caller.
async fn delta_pass(state: &MirrorState) {
    let cursor = read_mirror_meta(state, LAST_DELTA_SYNC_KEY).await;
    let Some(cursor) = cursor else {
        // No cursor (a pre-delta-sync mirror, or a meta row lost to a schema reset):
        // adopt "now" rather than the epoch, which would return the entire library.
        // Older changes are covered by whatever populated the mirror plus reconcile's
        // backstop; everything from here on is covered by delta.
        let now = now_rfc3339();
        tracing::info!(cursor = %now, "no delta cursor yet; adopting the current instant");
        state.writer.set_meta(LAST_DELTA_SYNC_KEY, now).await;
        return;
    };
    let Some(since) = delta_query_since(&cursor) else {
        // Corrupted/foreign value; re-stamp "now" (same recovery as the missing-cursor
        // arm) rather than wedging delta forever on an unparseable value.
        tracing::warn!(cursor = %cursor, "delta cursor is not a timestamp; resetting it to now");
        state
            .writer
            .set_meta(LAST_DELTA_SYNC_KEY, now_rfc3339())
            .await;
        return;
    };

    // Captured before the first request goes out -- see the cursor rule.
    let pass_started_at = now_rfc3339();

    let mut start_index = 0u32;
    let mut total_seen = 0usize;
    let mut any_changed = false;
    let mut progress = PageProgress::new(MAX_SYNC_PAGES);
    loop {
        // Yield to playback like `sync_library_breadth`: a delta is normally tiny, but one
        // after a bulk server-side metadata refresh can be many pages.
        while state.playback_active.load(Ordering::Acquire) {
            tokio::time::sleep(PLAYBACK_YIELD_POLL).await;
        }

        let query = ItemQuery {
            // No parent_id: one query covers every library; upsert_cross_library_items
            // resolves each item back to its own library.
            parent_id: None,
            recursive: true,
            // Stable page ordering: an unspecified default order can skip rows when paged.
            sort_by: Some("SortName".to_string()),
            fields: item_fields(),
            start_index,
            limit: PAGE_SIZE,
            min_date_last_saved: Some(since.clone()),
            ..ItemQuery::new()
        };
        let result = match state.client.get_items(&query).await {
            Ok(r) => r,
            Err(e) => {
                // Returns without writing the cursor: partial progress is never banked.
                tracing::error!(error = %e, since = %since, start_index, "delta sync page failed");
                return;
            }
        };
        let page_len = result.items.len() as u32;
        let complete = match progress.observe(&result.items, result.total_record_count) {
            Ok(complete) => complete,
            Err(reason) => {
                tracing::warn!(reason, "delta enumeration incomplete; cursor unchanged");
                return;
            }
        };

        total_seen += result.items.len();
        any_changed |= upsert_cross_library_items(state, &result.items).await;

        start_index += page_len;
        if complete {
            break;
        }
    }

    drop(progress);
    if total_seen > 0 {
        tracing::info!(
            changed = total_seen,
            since = %since,
            "delta sync applied server-side changes"
        );
    }
    if any_changed {
        // Same rationale as the WS LibraryChanged path: Next Up shifts whenever library
        // contents actually move. Gated on `any_changed`, not `total_seen`: a
        // `minDateLastSaved` match the no-op comparison then discarded (a metadata-only
        // re-save, nothing a user would see move) must not re-fetch and re-broadcast Next Up
        // for no visible reason. Barrier first so its upsert can't race a still-in-flight page.
        state.writer.barrier().await;
        state.request_next_up_refresh();
    }

    if !state.writer.barrier().await {
        tracing::error!(since = %since, "delta sync had a failed mirror write; cursor not advanced");
        return;
    }
    state
        .writer
        .set_meta(LAST_DELTA_SYNC_KEY, pass_started_at)
        .await;
}

/// Reads one `meta` value off the read pool. `spawn_blocking` since `ReadPool::acquire`
/// blocks. Barriers first so a caller's own recent `set_meta` is visible.
async fn read_mirror_meta(state: &MirrorState, key: &'static str) -> Option<String> {
    state.writer.barrier().await;
    let pool = state.read_pool.clone();
    tokio::task::spawn_blocking(move || {
        let conn = pool.acquire();
        crate::schema::read_meta(&conn, key)
    })
    .await
    .unwrap_or_else(|e| {
        tracing::error!(error = %e, key, "read_mirror_meta task panicked");
        None
    })
}

// --- Reconciliation ---------------------------------------------------------

/// Pure decision: does this library need a resync? Split out so it's directly unit-testable.
pub(crate) fn needs_resync(
    local_count: i64,
    server_count: i32,
    local_newest_date_created: Option<&str>,
    server_date_last_media_added: Option<&str>,
) -> bool {
    if i64::from(server_count) != local_count {
        return true;
    }
    match (local_newest_date_created, server_date_last_media_added) {
        (Some(local), Some(server)) => local < server,
        (None, Some(_)) => true,
        _ => false,
    }
}

/// Cap on concurrent `reconcile_view` calls within one pass. Strictly sequential used to
/// turn a many-library server's startup reconcile into seconds of Home cold-start dead time;
/// unbounded concurrency would be rude to a small server, so this bounds the fan-out instead.
const RECONCILE_CONCURRENCY: usize = 4;

/// Two concurrent reconcile passes (startup racing `reconcile_timer`'s first tick) could
/// each independently decide "resync" for the same library, streaming its metadata twice.
/// Single-flight guard: a pass that finds another running sets a pending flag and returns;
/// the running pass reruns once on completion, so a mid-pass trigger is deferred, not lost.
async fn reconcile_all(state: &MirrorState) {
    if state.reconcile_in_progress.swap(true, Ordering::AcqRel) {
        tracing::debug!("reconcile already in progress; deferring this trigger");
        state.reconcile_pending.store(true, Ordering::Release);
        return;
    }
    loop {
        let views = match state.client.get_user_views().await {
            Ok(v) => v,
            Err(e) => {
                tracing::error!(error = %e, "reconciliation: failed to fetch views");
                break;
            }
        };
        // `reconcile_view` swallows/logs its own errors, so a slow or failing view can't
        // abort the others; each runs independently up to RECONCILE_CONCURRENCY at a time.
        // A `Channel` view is browsed live, never mirrored, so skip it explicitly here
        // rather than relying on `item_types_for_collection`'s `collection_type`-keyed
        // backstop, which every Channel view leaves empty same as other unsupported kinds.
        stream::iter(views)
            .filter(|view| {
                let is_channel = view.type_ == Some(BaseItemKind::Channel);
                async move { !is_channel }
            })
            .for_each_concurrent(RECONCILE_CONCURRENCY, |view| async move {
                reconcile_view(state, &view).await;
            })
            .await;
        if !state.reconcile_pending.swap(false, Ordering::AcqRel) {
            break;
        }
        tracing::debug!("rerunning reconcile for a trigger deferred mid-pass");
    }
    state.reconcile_in_progress.store(false, Ordering::Release);
}

async fn reconcile_view(state: &MirrorState, view: &BaseItemDto) {
    let Some(view_id) = view.id.map(|u| u.to_string()) else {
        return;
    };
    let collection_type = view
        .collection_type
        .map(|c| c.to_string())
        .unwrap_or_default();
    let item_types = crate::item_types_for_collection(&collection_type);
    if item_types.is_empty() {
        return;
    }

    // A recursive query restricted to `includeItemTypes=Episode` excludes virtual
    // (unaired/missing) placeholder episodes on a live server, while `sync_library_breadth`'s
    // unrestricted fetch includes them -- an unconfirmed server-side default. Rather than pin
    // this to that behavior, the probe below uses the same "sync universe" shape as
    // `sync_library_breadth`'s own query -- see `library_universe_query`. `local_summary`
    // mirrors this by counting every item type under the library, matching what
    // `sync_library_breadth` actually persists.
    let query = ItemQuery {
        limit: 1,
        ..library_universe_query(&view_id)
    };
    let server_count = match state.client.get_items(&query).await {
        Ok(r) => r.total_record_count.unwrap_or(0),
        Err(e) => {
            tracing::error!(error = %e, view_id, "reconciliation: failed to fetch item count");
            return;
        }
    };
    // Some servers return `DateLastMediaAdded` as the DateTime.MinValue sentinel
    // ("0001-01-01T00:00:00") when the field isn't populated; map it to `None` rather than
    // let the date branch compare against year 1 forever.
    let server_newest = view
        .date_last_media_added
        .map(|d| d.to_rfc3339())
        .filter(|d| !d.starts_with("0001-"));

    let (local_count, local_newest) = local_summary(state, &view_id, item_types).await;

    let mut resync = needs_resync(
        local_count,
        server_count,
        local_newest.as_deref(),
        server_newest.as_deref(),
    );

    // Third probe -- newest-ids presence. The two probes above are blind to a real item
    // silently replacing a virtual placeholder (count-neutral, and the date branch may be
    // unavailable too). When the cheap probes say "in sync", fetch the server's 20 newest
    // items and check each id exists locally; any absent id is drift by definition. 20 deep
    // means a missed swap stays detectable until 20 newer items arrive, which are themselves
    // count/WS-detectable and trigger the resync that repairs everything.
    let mut newest_ids_missing = false;
    if !resync {
        // Same "sync universe" shape as the count probe above -- see `library_universe_query`.
        let newest_query = ItemQuery {
            sort_by: Some("DateCreated".to_string()),
            sort_order: Some("Descending".to_string()),
            limit: 20,
            ..library_universe_query(&view_id)
        };
        match state.client.get_items(&newest_query).await {
            Ok(r) => {
                let ids: Vec<String> = r
                    .items
                    .iter()
                    .filter_map(|i| i.id.map(|u| u.to_string()))
                    .collect();
                newest_ids_missing = !local_ids_all_present(state, &ids).await;
                if newest_ids_missing {
                    resync = true;
                }
            }
            Err(e) => {
                tracing::warn!(error = %e, view_id, "reconciliation: newest-ids probe failed");
            }
        }
    }
    // Logged every pass, not only on mismatch, so "reconcile never ran" and "reconcile ran
    // and found nothing" stay distinguishable.
    tracing::info!(
        view_id,
        server_count,
        local_count,
        local_newest = local_newest.as_deref().unwrap_or("-"),
        server_newest = server_newest.as_deref().unwrap_or("-"),
        newest_ids_missing,
        resync,
        "reconcile decision"
    );
    if resync {
        match reconcile_sweep(state, &view_id, &collection_type).await {
            Some(outcome) => {
                tracing::info!(
                    view_id,
                    server_count,
                    local_count,
                    server_ids = outcome.server_ids,
                    orphans_removed = outcome.orphans_removed,
                    missing_fetched = outcome.missing_fetched,
                    "reconciliation mismatch; id sweep converged the library"
                );
                // Same post-resync side effect the full breadth walk had: a boxsets
                // library's membership needs its own re-fetch per BoxSet regardless of how
                // the row set itself converged.
                if collection_type == "boxsets" {
                    sync_boxsets_membership(state, &view_id).await;
                }
            }
            None => {
                // No fallback to the full breadth walk (see reconcile_sweep): the mirror
                // keeps its last-good state and the mismatch stands until the next tick.
                tracing::warn!(
                    view_id,
                    server_count,
                    local_count,
                    "reconciliation mismatch; id sweep failed, keeping mirror state for the \
                     next reconcile pass"
                );
            }
        }
    }
}

/// Outcome of one successful [`reconcile_sweep`], for the log line that
/// replaced the old "resyncing library" one.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct SweepOutcome {
    /// Ids the server's enumeration returned for this library.
    server_ids: usize,
    /// Local rows the sweep pruned (present locally, gone server-side).
    orphans_removed: usize,
    /// Full DTOs fetched and upserted (present server-side, absent locally).
    missing_fetched: usize,
}

/// ID-level reconciliation sweep: what a `reconcile_view` mismatch runs instead of a full
/// breadth resync, to shed a handful of stale rows without re-downloading the whole library.
///
///   1. Enumerate the library's ids with a byte-stripped version of the breadth walk's
///      query ([`id_sweep_query`]).
///   2. Diff against the mirror's own id set for that library ([`local_library_ids`]).
///   3. Fetch full DTOs for only the missing ids, in [`WS_BATCH_SIZE`] chunks.
///   4. Prune the orphans through the same
///      [`WriterHandle::prune_library`](crate::writer::WriterHandle::prune_library) call the
///      breadth walk uses, with the enumerated server id set as `keep_ids`.
///
/// Step 4 is `prune_library`, not `remove_items`, so it only touches rows stamped
/// `library_id = <this view>` -- an item merely referenced by this library's collections but
/// owned elsewhere is out of reach, and a genuine orphan's `collection_members` rows drop
/// with it in the same transaction, inheriting the breadth walk's deletion semantics
/// exactly. `keep_ids` is diffed against committed state inside the writer's own
/// transaction, not the possibly-stale read this function uses for its counters.
///
/// Every network step is fail-closed with all reads before any deletion: a failed page or
/// chunk returns `None` having pruned nothing, leaving the mismatch standing for the next
/// tick. No fallback to the full breadth walk -- that would reintroduce the exact resync
/// this exists to avoid, on the flaky-link path where it's least affordable. Upserts from an
/// earlier, successful chunk are left in place (idempotent, id-keyed, additive), same
/// posture as [`delta_pass`].
///
/// Held to the same concurrency contracts as the breadth walk it replaces: a
/// [`BreadthSyncGuard`] covers the whole sweep, `playback_active` is honored between every
/// page/chunk, and `SyncActivity::Syncing` is emitted per page/chunk. The single-flight
/// `reconcile_in_progress`/`reconcile_pending` pair lives one level up in [`reconcile_all`].
async fn reconcile_sweep(
    state: &MirrorState,
    view_id: &str,
    collection_type: &str,
) -> Option<SweepOutcome> {
    let Some(guard) = BreadthSyncGuard::enter(&state.self_weak) else {
        tracing::debug!(view_id, "mirror dropped before the reconcile sweep began");
        return None;
    };
    let outcome = reconcile_sweep_inner(state, view_id, collection_type).await;
    // Decrement before Idle on every exit path, same pair as sync_library_breadth's.
    drop(guard);
    state.sync_activity.send_replace(SyncActivity::Idle);
    outcome
}

/// The sweep proper, split out so the guard-drop/terminal-`Idle` pair wraps every `return`
/// in one place rather than being repeated at each early exit.
async fn reconcile_sweep_inner(
    state: &MirrorState,
    view_id: &str,
    collection_type: &str,
) -> Option<SweepOutcome> {
    // Marks the prune's `since_rowid` guard (docs/12; see `apply_prune_library`): snapshotted
    // before enumeration starts, so a `LibraryChanged` insert landing mid-sweep always gets a
    // higher `rowid` and survives even if it missed `server_ids`.
    let sweep_started_at = max_item_rowid(state).await;

    // --- 1. Enumerate the server's ids for this library ------------------
    let mut start_index = 0u32;
    let mut pages_done = 0u32;
    let mut total_items: Option<u32> = None;
    let mut progress = PageProgress::new(MAX_SYNC_PAGES);
    loop {
        // Yield to playback like `sync_library_breadth`; an ids page is small, but "small"
        // is relative to a stream buffering on the same constrained link.
        while state.playback_active.load(Ordering::Acquire) {
            tokio::time::sleep(PLAYBACK_YIELD_POLL).await;
        }
        // Sent before the fetch, so a poller sees the page currently in flight.
        state.sync_activity.send_replace(SyncActivity::Syncing {
            library_name_or_id: view_id.to_string(),
            pages_done,
            items_done: start_index,
            total_items,
        });

        let result = match state
            .client
            .get_items(&id_sweep_query(view_id, start_index))
            .await
        {
            Ok(r) => r,
            Err(e) => {
                tracing::error!(
                    error = %e, view_id, start_index,
                    "reconcile sweep: id enumeration page failed"
                );
                return None;
            }
        };
        let page_len = result.items.len() as u32;
        let complete = match progress.observe(&result.items, result.total_record_count) {
            Ok(complete) => complete,
            Err(reason) => {
                tracing::warn!(
                    reason,
                    "reconcile enumeration incomplete; keeping prior membership"
                );
                return None;
            }
        };
        total_items = progress.total;

        pages_done += 1;
        start_index += page_len;
        if complete {
            break;
        }
    }
    let server_ids: Vec<String> = progress.ids.into_iter().map(|id| id.to_string()).collect();

    // --- 2. Diff against the mirror's id set for this library -------------
    // `None` = the read failed; treat as a failed sweep, not "the mirror is empty"
    // (which would prune the entire library).
    let local_ids = local_library_ids(state, view_id).await?;
    let server_set: std::collections::HashSet<&str> =
        server_ids.iter().map(String::as_str).collect();
    let orphans = local_ids
        .iter()
        .filter(|id| !server_set.contains(id.as_str()))
        .count();
    let missing: Vec<String> = server_ids
        .iter()
        .filter(|id| !local_ids.contains(*id))
        .cloned()
        .collect();
    drop(server_set);

    // --- 3. Fetch full DTOs for JUST the missing ids ----------------------
    let root_type = browse_root_item_type(collection_type);
    let view_uuid = uuid::Uuid::parse_str(view_id).ok();
    let mut missing_fetched = 0usize;
    for (chunk_index, chunk) in missing.chunks(WS_BATCH_SIZE).enumerate() {
        while state.playback_active.load(Ordering::Acquire) {
            tokio::time::sleep(PLAYBACK_YIELD_POLL).await;
        }
        // The denominator switches to the missing set here, so the progress bar stays
        // meaningful through the phase that actually moves bytes.
        state.sync_activity.send_replace(SyncActivity::Syncing {
            library_name_or_id: view_id.to_string(),
            pages_done: pages_done + chunk_index as u32,
            items_done: missing_fetched as u32,
            total_items: Some(missing.len() as u32),
        });

        let query = ItemQuery {
            recursive: true,
            fields: item_fields(),
            limit: chunk.len() as u32,
            ids: chunk.to_vec(),
            ..ItemQuery::new()
        };
        let result = match state.client.get_items(&query).await {
            Ok(r) => r,
            Err(e) => {
                tracing::error!(
                    error = %e, view_id, chunk = chunk.len(),
                    "reconcile sweep: by-ids fetch of missing items failed"
                );
                return None;
            }
        };
        let mut items = result.items;
        // Same browse-flattening as the breadth walk (see `flatten_root_parents_in_place`).
        flatten_root_parents_in_place(&mut items, root_type, view_uuid);
        missing_fetched += items.len();
        // Scoped to this view: these ids came from this library's own recursive
        // enumeration, attributable the same way a breadth-sync page is.
        state
            .writer
            .upsert_items_scoped(items, Some(view_id.to_string()))
            .await;
    }

    // --- 4. Prune the orphans (last: nothing is deleted until every fetch
    // above has succeeded) -------------------------------------------------
    let server_id_count = server_ids.len();
    if orphans > 0 {
        // Same-writer FIFO order means every id in server_ids is already committed. Skipped
        // outright when nothing drifted: apply_prune_library reads every row's blob otherwise.
        state
            .writer
            .prune_library(view_id.to_string(), server_ids, sweep_started_at)
            .await;
    }

    Some(SweepOutcome {
        server_ids: server_id_count,
        orphans_removed: orphans,
        missing_fetched,
    })
}

/// One enumeration page of [`reconcile_sweep`]: same "sync universe" shape as
/// `sync_library_breadth`'s query -- see `library_universe_query` -- stripped of every byte
/// that isn't needed to learn an id.
///
/// Used only as a set of ids, never written to the mirror: `enableUserData=false` would make
/// every item look unplayed to `rows::extract_columns`. The sweep re-fetches full DTOs for
/// the ids it actually stores.
fn id_sweep_query(view_id: &str, start_index: u32) -> ItemQuery {
    ItemQuery {
        sort_by: Some("SortName".to_string()),
        start_index,
        limit: ID_SWEEP_PAGE_SIZE,
        enable_images: Some(false),
        enable_user_data: Some(false),
        ..library_universe_query(view_id)
    }
}

/// Highest `rowid` currently in `items`, or 0 if empty -- snapshotted before a breadth walk
/// or sweep starts enumerating, so `apply_prune_library`'s `since_rowid` guard can tell a
/// concurrent `LibraryChanged` insert (a strictly higher `rowid`, SQLite's own monotonic
/// counter) from a pre-existing orphan (docs/12). Barriered like `local_library_ids`, and
/// unscoped by `library_id`: a mark that's too high (from another library's concurrent
/// insert) only ever makes the guard more conservative, never less.
async fn max_item_rowid(state: &MirrorState) -> i64 {
    state.writer.barrier().await;
    let pool = state.read_pool.clone();
    tokio::task::spawn_blocking(move || {
        let conn = pool.acquire();
        conn.query_row("SELECT COALESCE(MAX(rowid), 0) FROM items", [], |row| {
            row.get(0)
        })
        .unwrap_or_else(|e| {
            tracing::error!(error = %e, "failed to read max item rowid; guard disabled for this pass");
            i64::MAX
        })
    })
    .await
    .unwrap_or_else(|e| {
        tracing::error!(error = %e, "max_item_rowid task panicked; guard disabled for this pass");
        i64::MAX
    })
}

/// Every id the mirror currently holds for one library, the same `library_id` scoping
/// `local_summary`/`prune_library` use. `None` on a read failure, treated as "sweep failed,
/// keep state" rather than an empty mirror (which would prune everything).
///
/// Barriers first so a write this reconcile pass already enqueued is visible, not read
/// stale. `spawn_blocking`; `idx_items_latest_virtual`'s leading `library_id` makes this an
/// index range scan.
async fn local_library_ids(
    state: &MirrorState,
    view_id: &str,
) -> Option<std::collections::HashSet<String>> {
    state.writer.barrier().await;
    let pool = state.read_pool.clone();
    let view_id = view_id.to_string();
    tokio::task::spawn_blocking(move || {
        let conn = pool.acquire();
        let result: rusqlite::Result<std::collections::HashSet<String>> = (|| {
            let mut stmt = conn.prepare("SELECT id FROM items WHERE library_id = ?1")?;
            let rows = stmt.query_map([&view_id], |row| row.get::<_, String>(0))?;
            rows.collect()
        })();
        drop(conn);
        match result {
            Ok(ids) => Some(ids),
            Err(e) => {
                tracing::error!(error = %e, "reconcile sweep: failed to read local library ids");
                None
            }
        }
    })
    .await
    .unwrap_or_else(|e| {
        tracing::error!(error = %e, "local_library_ids task panicked");
        None
    })
}

/// Newest-ids presence probe helper: true iff EVERY id is already a row in
/// `items`. Indexed primary-key lookups (one `IN` list of at most 20 ids),
/// `spawn_blocking` for the same reason as `local_summary` below. An empty
/// id list is trivially "all present" (a server returning zero items for
/// the newest-20 query has nothing to be missing).
async fn local_ids_all_present(state: &MirrorState, ids: &[String]) -> bool {
    if ids.is_empty() {
        return true;
    }
    let pool = state.read_pool.clone();
    let ids: Vec<String> = ids.to_vec();
    tokio::task::spawn_blocking(move || {
        let conn = pool.acquire();
        let placeholders = std::iter::repeat_n("?", ids.len())
            .collect::<Vec<_>>()
            .join(",");
        let sql = format!("SELECT COUNT(*) FROM items WHERE id IN ({placeholders})");
        let params: Vec<&dyn rusqlite::ToSql> =
            ids.iter().map(|s| s as &dyn rusqlite::ToSql).collect();
        let found: rusqlite::Result<i64> =
            conn.query_row(&sql, params.as_slice(), |row| row.get(0));
        drop(conn);
        match found {
            Ok(n) => n as usize == ids.len(),
            Err(e) => {
                tracing::error!(error = %e, "newest-ids presence query failed");
                true // fail open: a broken read must not force resync loops
            }
        }
    })
    .await
    .unwrap_or(true)
}

/// `pool.acquire()` blocks, wrapped in `spawn_blocking`.
///
/// Scoped by `library_id = view_id`, not just `item_type`, or two libraries sharing a
/// `collection_type` would compare the server's per-library count against a local count
/// summed across both, permanently mismatching one and masking drift in the other.
///
/// Two aggregates, scoped differently within the same `WHERE library_id = ?`
/// (`idx_items_latest_virtual` covers every column referenced, keeping this index-only):
/// - `COUNT(*)` covers every item type under this library, matching `reconcile_view`'s
///   unrestricted server-count probe -- neither side filters by type, so the two counts are
///   provably the same universe.
/// - `MAX(date_created)` stays scoped to `item_types` and excludes `is_virtual` rows: a
///   virtual placeholder's `DateCreated` (its local metadata-refresh time, unrelated to real
///   media) can exceed the server's `DateLastMediaAdded` (real media only), permanently
///   poisoning the date check into a false "up to date".
async fn local_summary(
    state: &MirrorState,
    view_id: &str,
    item_types: &[&str],
) -> (i64, Option<String>) {
    let pool = state.read_pool.clone();
    let view_id = view_id.to_string();
    let item_types: Vec<String> = item_types.iter().map(|s| s.to_string()).collect();
    tokio::task::spawn_blocking(move || {
        let conn = pool.acquire();
        let placeholders = std::iter::repeat_n("?", item_types.len())
            .collect::<Vec<_>>()
            .join(",");
        let sql = format!(
            "SELECT COUNT(*), \
                    MAX(CASE WHEN item_type IN ({placeholders}) AND is_virtual = 0 \
                             THEN date_created END) \
             FROM items WHERE library_id = ?"
        );
        let mut params: Vec<&dyn rusqlite::ToSql> = item_types
            .iter()
            .map(|s| s as &dyn rusqlite::ToSql)
            .collect();
        params.push(&view_id as &dyn rusqlite::ToSql);
        let result: rusqlite::Result<(i64, Option<String>)> =
            conn.query_row(&sql, params.as_slice(), |row| {
                Ok((row.get(0)?, row.get(1)?))
            });
        drop(conn);
        result.unwrap_or_else(|e| {
            tracing::error!(error = %e, "failed to read local summary for reconciliation");
            (0, None)
        })
    })
    .await
    .unwrap_or_else(|e| {
        tracing::error!(error = %e, "local_summary task panicked");
        (0, None)
    })
}

/// `spawn`'s `needs_initial_sync = false` branch already calls `reconcile_all` once before
/// this timer's first tick fires; this timer is purely the backstop for whatever the
/// startup call, WS `LibraryChanged`, and the reconnect `NeedsReconcile` signal all miss.
async fn reconcile_timer(state: Weak<MirrorState>) {
    let mut interval = tokio::time::interval(RECONCILE_INTERVAL);
    interval.tick().await; // first tick is immediate; startup already reconciles/syncs
    loop {
        interval.tick().await;
        let Some(state) = state.upgrade() else { break };
        // Resume, then delta, then reconcile, same order as spawn's startup path: the
        // backstop for a long-lived session that never restarts or drops its WS connection.
        sync_resume(&state).await;
        delta_sync(&state).await;
        reconcile_all(&state).await;
        // Retries a startup build that failed.
        ensure_index_group(&state, IndexGroup::Favorites).await;
        ensure_index_group(&state, IndexGroup::Resume).await;
        sync_favorites(&state).await;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::mock_server::MockServer;
    use crate::pool::ReadPool;
    use crate::writer::WriterHandle;
    use jellyfin_api::{ClientIdentity, JellyfinClient};
    use serde_json::json;
    use tokio::sync::mpsc;

    fn identity() -> ClientIdentity {
        ClientIdentity {
            client: "Jellybeam Test".to_string(),
            device: "test".to_string(),
            device_id: "test-device".to_string(),
            version: "0.1.0".to_string(),
        }
    }

    /// Pins the two server-gated fields library sort/filter depends on: `Genres` (§1.2) and
    /// `SortName`.
    #[test]
    fn item_fields_requests_genres_and_sort_name() {
        let fields = item_fields();
        assert!(fields.iter().any(|f| f == "Genres"), "{fields:?}");
        assert!(fields.iter().any(|f| f == "SortName"), "{fields:?}");
    }

    /// Wires a real writer task + read pool against a fresh temp mirror.
    struct TestMirror {
        _dir: tempfile::TempDir,
        state: Arc<MirrorState>,
        _writer_task: tokio::task::JoinHandle<()>,
    }

    impl TestMirror {
        fn new(client: JellyfinClient) -> Self {
            let dir = tempfile::tempdir().expect("tempdir");
            let path = dir.path().join("mirror.db");
            let (conn, _empty) = crate::schema::open_and_prepare(&path).expect("open");
            let (tx, rx) = mpsc::channel(64);
            let (changes_tx, _) = tokio::sync::broadcast::channel(64);
            let writer_changes_tx = changes_tx.clone();
            let writer_task = tokio::task::spawn_blocking(move || {
                crate::writer::run(conn, rx, writer_changes_tx)
            });
            let read_pool = Arc::new(ReadPool::open(&path, 2).expect("read pool"));
            let (sync_activity_tx, _) = tokio::sync::watch::channel(SyncActivity::Idle);
            let state = Arc::new_cyclic(|weak| {
                MirrorState::new(
                    client,
                    WriterHandle::new(tx),
                    read_pool,
                    path.clone(),
                    changes_tx,
                    sync_activity_tx,
                    weak.clone(),
                )
            });
            Self {
                _dir: dir,
                state,
                _writer_task: writer_task,
            }
        }

        /// Convenience for call sites (like `initial_sync`) that take a `Weak<MirrorState>`.
        fn weak(&self) -> Weak<MirrorState> {
            Arc::downgrade(&self.state)
        }

        async fn item_count(&self) -> i64 {
            self.state.writer.barrier().await;
            let conn = self.state.read_pool.acquire();
            conn.query_row("SELECT COUNT(*) FROM items", [], |r| r.get(0))
                .expect("count")
        }

        /// Delta sync tests assert on `name`: the cheapest stand-in for "the row carries
        /// current server metadata".
        async fn item_name(&self, id: &str) -> Option<String> {
            use rusqlite::OptionalExtension;
            self.state.writer.barrier().await;
            let conn = self.state.read_pool.acquire();
            conn.query_row("SELECT name FROM items WHERE id = ?1", [id], |r| r.get(0))
                .optional()
                .expect("name query")
        }

        async fn meta(&self, key: &str) -> Option<String> {
            self.state.writer.barrier().await;
            let conn = self.state.read_pool.acquire();
            crate::schema::read_meta(&conn, key)
        }
    }

    fn movie_json(id: &str, name: &str) -> serde_json::Value {
        json!({ "Id": id, "Name": name, "Type": "Movie" })
    }

    fn page_items(count: u32) -> Vec<BaseItemDto> {
        (1..=count)
            .map(|id| BaseItemDto {
                id: Some(uuid::Uuid::from_u128(u128::from(id))),
                name: Some("Synthetic item".to_string()),
                ..Default::default()
            })
            .collect()
    }

    #[test]
    fn page_progress_preserves_uncertainty_and_bounds_enumeration() {
        let items = page_items(3);
        let mut unknown = PageProgress::new(3);
        assert_eq!(unknown.observe(&items[..2], None), Ok(false));
        assert_eq!(unknown.observe(&items[2..], None), Ok(false));
        assert_eq!(unknown.observe(&[], None), Ok(true));

        let mut exact = PageProgress::new(3);
        assert_eq!(exact.observe(&items[..2], Some(2)), Ok(true));
        let mut early_empty = PageProgress::new(3);
        assert_eq!(early_empty.observe(&items[..1], Some(3)), Ok(false));
        assert!(early_empty.observe(&[], Some(3)).is_err());
        let mut repeated = PageProgress::new(3);
        assert_eq!(repeated.observe(&items[..2], None), Ok(false));
        assert!(repeated.observe(&items[..2], None).is_err());
        assert!(PageProgress::new(1).observe(&items[..2], None).is_err());
        assert!(PageProgress::new(1).observe(&[], Some(-1)).is_err());
        assert!(PageProgress::new(1).observe(&items[..2], Some(1)).is_err());
        assert!(PageProgress::new(1)
            .observe(&[BaseItemDto::default()], None)
            .is_err());
        let mut changing = PageProgress::new(3);
        assert_eq!(changing.observe(&items[..1], Some(3)), Ok(false));
        assert!(changing.observe(&items[1..2], Some(2)).is_err());
    }

    #[tokio::test]
    async fn breadth_without_total_fetches_the_next_page_before_pruning() {
        let server = MockServer::start().await;
        let mirror = TestMirror::new(JellyfinClient::from_token(
            &server.base_url,
            identity(),
            "tok",
        ));
        let view = "11111111-1111-1111-1111-111111111111";
        let items = page_items(PAGE_SIZE + 2);
        mirror
            .state
            .writer
            .upsert_items_scoped(items.clone(), Some(view.to_string()))
            .await;
        server.route(
            "/Items",
            &[("parentId", view), ("startIndex", "0")],
            json!({"Items": &items[..PAGE_SIZE as usize]}),
        );
        server.route(
            "/Items",
            &[("parentId", view), ("startIndex", &PAGE_SIZE.to_string())],
            json!({"Items": &items[PAGE_SIZE as usize..PAGE_SIZE as usize + 1]}),
        );
        server.route(
            "/Items",
            &[
                ("parentId", view),
                ("startIndex", &(PAGE_SIZE + 1).to_string()),
            ],
            json!({"Items": &items[PAGE_SIZE as usize + 1..]}),
        );
        server.route(
            "/Items",
            &[
                ("parentId", view),
                ("startIndex", &(PAGE_SIZE + 2).to_string()),
            ],
            json!({"Items": []}),
        );
        assert!(sync_library_breadth(&mirror.weak(), view, "movies").await);
        assert_eq!(mirror.item_count().await, i64::from(PAGE_SIZE + 2));
        assert_eq!(server.request_count("/Items?"), 4);
    }

    #[tokio::test]
    async fn reconcile_without_total_retains_members_beyond_the_first_page() {
        let server = MockServer::start().await;
        let mirror = TestMirror::new(JellyfinClient::from_token(
            &server.base_url,
            identity(),
            "tok",
        ));
        let view = "11111111-1111-1111-1111-111111111111";
        let items = page_items(ID_SWEEP_PAGE_SIZE + 1);
        mirror
            .state
            .writer
            .upsert_items_scoped(items.clone(), Some(view.to_string()))
            .await;
        assert!(mirror.state.writer.barrier().await);
        server.route(
            "/Items",
            &[("parentId", view), ("startIndex", "0")],
            json!({"Items": &items[..ID_SWEEP_PAGE_SIZE as usize]}),
        );
        server.route(
            "/Items",
            &[
                ("parentId", view),
                ("startIndex", &ID_SWEEP_PAGE_SIZE.to_string()),
            ],
            json!({"Items": &items[ID_SWEEP_PAGE_SIZE as usize..]}),
        );
        server.route(
            "/Items",
            &[
                ("parentId", view),
                ("startIndex", &(ID_SWEEP_PAGE_SIZE + 1).to_string()),
            ],
            json!({"Items": []}),
        );
        assert!(reconcile_sweep(&mirror.state, view, "movies")
            .await
            .is_some());
        assert_eq!(mirror.item_count().await, i64::from(ID_SWEEP_PAGE_SIZE + 1));
        assert_eq!(server.request_count("/Items?"), 3);
    }

    #[tokio::test]
    async fn repeated_reconcile_page_never_prunes_the_prior_snapshot() {
        let server = MockServer::start().await;
        let mirror = TestMirror::new(JellyfinClient::from_token(
            &server.base_url,
            identity(),
            "tok",
        ));
        let view = "11111111-1111-1111-1111-111111111111";
        let items = page_items(2);
        mirror
            .state
            .writer
            .upsert_items_scoped(items.clone(), Some(view.to_string()))
            .await;
        assert!(mirror.state.writer.barrier().await);
        server.route(
            "/Items",
            &[("parentId", view)],
            json!({"Items": &items[..1], "TotalRecordCount": 2}),
        );
        assert!(reconcile_sweep(&mirror.state, view, "movies")
            .await
            .is_none());
        assert_eq!(mirror.item_count().await, 2);
        assert_eq!(server.request_count("/Items?"), 2);
    }

    #[tokio::test]
    async fn incomplete_delta_page_keeps_the_prior_cursor() {
        let server = MockServer::start().await;
        let mirror = TestMirror::new(JellyfinClient::from_token(
            &server.base_url,
            identity(),
            "tok",
        ));
        let cursor = "2020-01-01T00:00:00Z";
        mirror
            .state
            .writer
            .set_meta(LAST_DELTA_SYNC_KEY, cursor.to_string())
            .await;
        server.route(
            "/Items",
            &[("startIndex", "0")],
            json!({"Items": page_items(1), "TotalRecordCount": 2}),
        );
        server.route(
            "/Items",
            &[("startIndex", "1")],
            json!({"Items": [], "TotalRecordCount": 2}),
        );
        delta_sync(&mirror.state).await;
        assert_eq!(
            mirror.meta(LAST_DELTA_SYNC_KEY).await.as_deref(),
            Some(cursor)
        );
        assert_eq!(server.request_count("/Items?"), 2);
    }

    #[tokio::test]
    async fn invalid_first_page_preserves_all_authoritative_snapshots() {
        for total in [-1, 2] {
            let server = MockServer::start().await;
            let mirror = TestMirror::new(JellyfinClient::from_token(
                &server.base_url,
                identity(),
                "tok",
            ));
            let view = "11111111-1111-1111-1111-111111111111";
            let collection = "22222222-2222-2222-2222-222222222222";
            let items = page_items(2);
            let old_ids: Vec<String> = items
                .iter()
                .map(|item| item.id.expect("id").to_string())
                .collect();
            mirror
                .state
                .writer
                .upsert_items_scoped(items, Some(view.to_string()))
                .await;
            mirror
                .state
                .writer
                .set_collection_members(
                    collection.to_string(),
                    old_ids
                        .iter()
                        .enumerate()
                        .map(|(index, id)| (id.clone(), index as i64))
                        .collect(),
                )
                .await;
            let cursor = "2020-01-01T00:00:00Z";
            mirror
                .state
                .writer
                .set_meta(LAST_DELTA_SYNC_KEY, cursor.to_string())
                .await;
            assert!(mirror.state.writer.barrier().await);
            server.route(
                "/Items",
                &[],
                json!({"Items": [], "TotalRecordCount": total}),
            );

            assert!(!sync_library_breadth(&mirror.weak(), view, "movies").await);
            assert!(reconcile_sweep(&mirror.state, view, "movies")
                .await
                .is_none());
            sync_one_boxset_membership(&mirror.state, collection).await;
            delta_sync(&mirror.state).await;

            assert_eq!(mirror.item_count().await, 2);
            assert_eq!(collection_member_ids(&mirror, collection), old_ids);
            assert_eq!(
                mirror.meta(LAST_DELTA_SYNC_KEY).await.as_deref(),
                Some(cursor)
            );
            assert_eq!(server.request_count("/Items?"), 4);
        }
    }

    #[tokio::test]
    async fn repeated_collection_page_keeps_the_prior_membership() {
        let server = MockServer::start().await;
        let mirror = TestMirror::new(JellyfinClient::from_token(
            &server.base_url,
            identity(),
            "tok",
        ));
        let collection = "11111111-1111-1111-1111-111111111111";
        let items = page_items(2);
        let old_ids: Vec<String> = items
            .iter()
            .map(|i| i.id.expect("id").to_string())
            .collect();
        mirror.state.writer.upsert_items(items.clone()).await;
        mirror
            .state
            .writer
            .set_collection_members(
                collection.to_string(),
                old_ids
                    .iter()
                    .enumerate()
                    .map(|(i, id)| (id.clone(), i as i64))
                    .collect(),
            )
            .await;
        assert!(mirror.state.writer.barrier().await);
        server.route(
            "/Items",
            &[("parentId", collection)],
            json!({"Items": &items[..1], "TotalRecordCount": 2}),
        );
        sync_one_boxset_membership(&mirror.state, collection).await;
        assert!(mirror.state.writer.barrier().await);
        assert_eq!(collection_member_ids(&mirror, collection), old_ids);
        assert_eq!(server.request_count("/Items?"), 2);
    }

    #[tokio::test]
    async fn failed_initial_library_fetch_releases_sync_waiters_without_advancing_cursor() {
        use std::time::Duration;
        let server = MockServer::start().await;
        let client = JellyfinClient::from_token(&server.base_url, identity(), "tok");
        server.route(
            "/UserViews",
            &[],
            json!({ "Items": [{
            "Id": "11111111-1111-1111-1111-111111111111",
            "Name": "Synthetic Movies", "CollectionType": "movies"
        }] }),
        );
        server.route("/UserItems/Resume", &[], json!({ "Items": [] }));
        server.route("/Shows/NextUp", &[], json!({ "Items": [] }));
        // The absent /Items route returns HTTP 404 during the first library page.
        let mirror = TestMirror::new(client);
        let notified = mirror.state.initial_sync_done.notified();
        tokio::pin!(notified);
        notified.as_mut().enable();

        initial_sync(&mirror.weak()).await;

        assert!(!mirror
            .state
            .initial_sync_in_progress
            .load(Ordering::Acquire));
        assert_eq!(*mirror.state.sync_activity.borrow(), SyncActivity::Idle);
        tokio::time::timeout(Duration::from_millis(100), notified)
            .await
            .expect("failed initial sync must release buffered-event waiters");
        assert!(mirror.meta("last_full_sync").await.is_none());
        assert!(mirror.meta(LAST_DELTA_SYNC_KEY).await.is_none());
    }

    #[tokio::test]
    async fn cancelled_initial_sync_releases_its_in_progress_flag() {
        use std::time::Duration;
        let server = MockServer::start().await;
        let client = JellyfinClient::from_token(&server.base_url, identity(), "tok");
        server.route_delayed(
            "/UserViews",
            &[],
            json!({ "Items": [] }),
            Duration::from_secs(2),
        );
        let mirror = TestMirror::new(client);
        let weak = mirror.weak();
        let task = tokio::spawn(async move { initial_sync(&weak).await });
        tokio::time::timeout(Duration::from_secs(1), async {
            while server.request_count("/UserViews") == 0 {
                tokio::time::sleep(Duration::from_millis(5)).await;
            }
        })
        .await
        .expect("initial request starts");
        task.abort();
        assert!(task.await.expect_err("aborted sync task").is_cancelled());
        assert!(!mirror
            .state
            .initial_sync_in_progress
            .load(Ordering::Acquire));
        assert_eq!(*mirror.state.sync_activity.borrow(), SyncActivity::Idle);
    }

    /// Stages one library's reconcile-sweep enumeration page (`id_sweep_query`). Returns bare
    /// `{"Id": ...}` objects, the way a real `enableImages=false&enableUserData=false`
    /// response with no `fields` looks.
    fn route_sweep_enumeration(server: &MockServer, view_id: &str, ids: &[&str]) {
        let limit = ID_SWEEP_PAGE_SIZE.to_string();
        server.route(
            "/Items",
            &[("parentId", view_id), ("limit", &limit)],
            json!({
                "Items": ids.iter().map(|id| json!({ "Id": id })).collect::<Vec<_>>(),
                "TotalRecordCount": ids.len(),
            }),
        );
    }

    /// Stages the sweep's by-ids repair fetch for exactly `items` (keyed on the joined `ids`
    /// param, so any other id set 404s instead of silently matching).
    fn route_by_ids(server: &MockServer, items: &[serde_json::Value]) {
        let ids = items
            .iter()
            .filter_map(|i| i.get("Id").and_then(|v| v.as_str()))
            .collect::<Vec<_>>()
            .join(",");
        server.route(
            "/Items",
            &[("ids", &ids)],
            json!({ "Items": items, "TotalRecordCount": items.len() }),
        );
    }

    /// Request-shape assertions the sweep tests share. A sweep enumeration
    /// page is the only request carrying `enableImages=false`; any full-DTO
    /// request (breadth page or by-ids repair fetch) carries
    /// `fields=Overview`; a by-ids repair fetch carries `ids=`.
    const SWEEP_PAGE_MARKER: &str = "enableImages=false";
    const FULL_DTO_MARKER: &str = "fields=Overview";
    const BY_IDS_MARKER: &str = "ids=";

    #[tokio::test]
    async fn initial_sync_populates_views_resume_next_up_and_breadth() {
        let server = MockServer::start().await;
        let client = JellyfinClient::from_token(&server.base_url, identity(), "tok");

        server.route(
            "/UserViews",
            &[],
            json!({ "Items": [{ "Id": "11111111-1111-1111-1111-111111111111", "Name": "Movies", "CollectionType": "movies" }] }),
        );
        server.route("/UserItems/Resume", &[], json!({ "Items": [] }));
        server.route("/Shows/NextUp", &[], json!({ "Items": [] }));
        server.route(
            "/Items",
            &[
                ("parentId", "11111111-1111-1111-1111-111111111111"),
                ("startIndex", "0"),
            ],
            json!({
                "Items": [
                    movie_json("22222222-2222-2222-2222-222222222222", "Movie A"),
                    movie_json("33333333-3333-3333-3333-333333333333", "Movie B"),
                ],
                "TotalRecordCount": 2
            }),
        );

        let mirror = TestMirror::new(client);
        initial_sync(&mirror.weak()).await;

        assert_eq!(mirror.item_count().await, 2);
        let conn = mirror.state.read_pool.acquire();
        let view_count: i64 = conn
            .query_row("SELECT COUNT(*) FROM views", [], |r| r.get(0))
            .expect("views");
        assert_eq!(view_count, 1);
    }

    fn view_item_type(mirror: &TestMirror, view_id: &str) -> Option<String> {
        use rusqlite::OptionalExtension;
        let conn = mirror.state.read_pool.acquire();
        conn.query_row(
            "SELECT item_type FROM views WHERE id = ?1",
            [view_id],
            |r| r.get(0),
        )
        .optional()
        .expect("view item_type query")
    }

    /// `sync_views` must persist the `/UserViews` entry's own `Type` (e.g. `"Channel"`,
    /// `"CollectionFolder"`) into `views.item_type`, distinct from `collection_type`
    /// (`CollectionType`), which a `Channel` view never sets.
    #[tokio::test]
    async fn sync_views_stores_item_type() {
        let server = MockServer::start().await;
        let client = JellyfinClient::from_token(&server.base_url, identity(), "tok");
        let movies_view = "11111111-1111-1111-1111-111111111111";
        let channel_view = "22222222-2222-2222-2222-222222222222";
        server.route(
            "/UserViews",
            &[],
            json!({ "Items": [
                { "Id": movies_view, "Name": "Movies", "CollectionType": "movies", "Type": "CollectionFolder" },
                { "Id": channel_view, "Name": "Recordings", "Type": "Channel" },
            ] }),
        );

        let mirror = TestMirror::new(client);
        sync_views(&mirror.state).await;
        mirror.state.writer.barrier().await;

        assert_eq!(
            view_item_type(&mirror, movies_view),
            Some("CollectionFolder".to_string())
        );
        assert_eq!(
            view_item_type(&mirror, channel_view),
            Some("Channel".to_string())
        );
    }

    /// `current_views` (every per-library sync walk's source of truth) must not return a
    /// `Channel` row, while other view kinds still come through.
    #[tokio::test]
    async fn current_views_excludes_channel_rows() {
        let server = MockServer::start().await;
        let client = JellyfinClient::from_token(&server.base_url, identity(), "tok");
        let movies_view = "11111111-1111-1111-1111-111111111111".to_string();
        let channel_view = "22222222-2222-2222-2222-222222222222".to_string();

        let mirror = TestMirror::new(client);
        mirror
            .state
            .writer
            .upsert_views(vec![
                crate::ViewRow {
                    id: movies_view.clone(),
                    name: "Movies".to_string(),
                    collection_type: "movies".to_string(),
                    item_type: "CollectionFolder".to_string(),
                },
                crate::ViewRow {
                    id: channel_view.clone(),
                    name: "Recordings".to_string(),
                    collection_type: String::new(),
                    item_type: "Channel".to_string(),
                },
            ])
            .await;
        mirror.state.writer.barrier().await;

        let views = current_views(&mirror.state).await;
        let ids: Vec<&String> = views.iter().map(|(id, _)| id).collect();
        assert!(
            ids.contains(&&movies_view),
            "non-channel views must still come through: {views:?}"
        );
        assert!(
            !ids.contains(&&channel_view),
            "a Channel view must never enter a per-library sync walk: {views:?}"
        );
    }

    /// `reconcile_all` must skip a `Channel` view entirely (no probe at all), while a normal
    /// library still gets its usual count probe.
    #[tokio::test]
    async fn reconcile_all_never_probes_a_channel_view() {
        let server = MockServer::start().await;
        let client = JellyfinClient::from_token(&server.base_url, identity(), "tok");
        let movies_view = "11111111-1111-1111-1111-111111111111";
        let channel_view = "22222222-2222-2222-2222-222222222222";
        server.route(
            "/UserViews",
            &[],
            json!({ "Items": [
                { "Id": movies_view, "Name": "Movies", "CollectionType": "movies", "Type": "CollectionFolder" },
                { "Id": channel_view, "Name": "Recordings", "Type": "Channel" },
            ] }),
        );
        // Matches local (empty mirror -> 0) so reconcile_view stops after this one probe.
        server.route(
            "/Items",
            &[
                ("parentId", movies_view),
                ("recursive", "true"),
                ("limit", "1"),
            ],
            json!({ "Items": [], "TotalRecordCount": 0 }),
        );

        let mirror = TestMirror::new(client);
        reconcile_all(&mirror.state).await;

        assert!(
            server.request_count_matching(&format!("parentId={movies_view}")) > 0,
            "a normal library must still be probed"
        );
        assert_eq!(
            server.request_count_matching(&format!("parentId={channel_view}")),
            0,
            "a Channel view must never be issued an /Items request by reconciliation"
        );
    }

    #[tokio::test]
    async fn initial_sync_pages_when_total_exceeds_page_size() {
        let server = MockServer::start().await;
        let client = JellyfinClient::from_token(&server.base_url, identity(), "tok");
        let view_id = "11111111-1111-1111-1111-111111111111";

        server.route(
            "/UserViews",
            &[],
            json!({ "Items": [{ "Id": view_id, "Name": "Movies", "CollectionType": "movies" }] }),
        );
        server.route("/UserItems/Resume", &[], json!({ "Items": [] }));
        server.route("/Shows/NextUp", &[], json!({ "Items": [] }));
        server.route(
            "/Items",
            &[("parentId", view_id), ("startIndex", "0")],
            json!({
                "Items": (0..PAGE_SIZE).map(|i| movie_json(&format!("00000000-0000-0000-0000-{i:012}"), &format!("Movie {i}"))).collect::<Vec<_>>(),
                "TotalRecordCount": PAGE_SIZE + 1
            }),
        );
        server.route(
            "/Items",
            &[
                ("parentId", view_id),
                ("startIndex", &PAGE_SIZE.to_string()),
            ],
            json!({
                "Items": [movie_json("99999999-9999-9999-9999-999999999999", "Last Movie")],
                "TotalRecordCount": PAGE_SIZE + 1
            }),
        );

        let mirror = TestMirror::new(client);
        initial_sync(&mirror.weak()).await;

        assert_eq!(mirror.item_count().await, (PAGE_SIZE + 1) as i64);
        assert_eq!(
            server.request_count("/Items?"),
            2,
            "must issue exactly two pages"
        );
    }

    #[tokio::test]
    async fn library_changed_added_fetches_and_upserts_ids() {
        let server = MockServer::start().await;
        let client = JellyfinClient::from_token(&server.base_url, identity(), "tok");
        let id = "22222222-2222-2222-2222-222222222222";
        server.route(
            "/Items",
            &[("ids", id)],
            json!({ "Items": [movie_json(id, "New Arrival")] }),
        );
        server.route("/Shows/NextUp", &[], json!({ "Items": [] }));

        let mirror = TestMirror::new(client);
        apply_server_event(
            &mirror.state,
            ServerEvent::LibraryChanged {
                added: vec![id.to_string()],
                updated: vec![],
                removed: vec![],
            },
        )
        .await;

        assert_eq!(mirror.item_count().await, 1);
    }

    fn collection_member_ids(mirror: &TestMirror, collection_id: &str) -> Vec<String> {
        let conn = mirror.state.read_pool.acquire();
        let mut stmt = conn
            .prepare(
                "SELECT item_id FROM collection_members WHERE collection_id = ?1 ORDER BY sort_index",
            )
            .expect("prepare");
        stmt.query_map([collection_id], |r| r.get(0))
            .expect("query")
            .collect::<rusqlite::Result<Vec<_>>>()
            .expect("rows")
    }

    /// Syncing a `boxsets` view's breadth must also trigger a per-BoxSet membership fetch.
    #[tokio::test]
    async fn initial_sync_of_a_boxsets_view_populates_membership() {
        let server = MockServer::start().await;
        let client = JellyfinClient::from_token(&server.base_url, identity(), "tok");
        let view_id = "11111111-1111-1111-1111-111111111111";
        let boxset_id = "22222222-2222-2222-2222-222222222222";
        let member_1 = "33333333-3333-3333-3333-333333333333";
        let member_2 = "44444444-4444-4444-4444-444444444444";

        server.route(
            "/UserViews",
            &[],
            json!({ "Items": [{ "Id": view_id, "Name": "Collections", "CollectionType": "boxsets" }] }),
        );
        server.route("/UserItems/Resume", &[], json!({ "Items": [] }));
        server.route("/Shows/NextUp", &[], json!({ "Items": [] }));
        // Breadth sync of the boxsets view: lands the BoxSet's own row.
        server.route(
            "/Items",
            &[("parentId", view_id), ("startIndex", "0")],
            json!({
                "Items": [{ "Id": boxset_id, "Name": "Set A", "Type": "BoxSet" }],
                "TotalRecordCount": 1
            }),
        );
        // Per-BoxSet membership fetch: non-recursive, distinguishable from the breadth-sync
        // route above by recursive=false.
        server.route(
            "/Items",
            &[("parentId", boxset_id), ("recursive", "false")],
            json!({
                "Items": [
                    { "Id": member_1, "Name": "Member One", "Type": "Movie" },
                    { "Id": member_2, "Name": "Member Two", "Type": "Movie" },
                ],
                "TotalRecordCount": 2
            }),
        );

        let mirror = TestMirror::new(client);
        initial_sync(&mirror.weak()).await;
        mirror.state.writer.barrier().await;

        assert_eq!(
            collection_member_ids(&mirror, boxset_id),
            vec![member_1.to_string(), member_2.to_string()],
            "membership must be populated in server order"
        );
        // The membership fetch must also upsert the member items themselves.
        assert_eq!(
            mirror.item_count().await,
            3,
            "boxset row + 2 members must all be present"
        );
    }

    /// A `LibraryChanged` touching a BoxSet must re-fetch its membership, not just its own
    /// row.
    #[tokio::test]
    async fn library_changed_touching_a_boxset_refreshes_its_membership() {
        let server = MockServer::start().await;
        let client = JellyfinClient::from_token(&server.base_url, identity(), "tok");
        let boxset_id = "22222222-2222-2222-2222-222222222222";
        let member_id = "55555555-5555-5555-5555-555555555555";

        server.route(
            "/Items",
            &[("ids", boxset_id)],
            json!({ "Items": [{ "Id": boxset_id, "Name": "Set A", "Type": "BoxSet" }] }),
        );
        server.route(
            "/Items",
            &[("parentId", boxset_id), ("recursive", "false")],
            json!({ "Items": [{ "Id": member_id, "Name": "New Member", "Type": "Movie" }], "TotalRecordCount": 1 }),
        );
        server.route("/Shows/NextUp", &[], json!({ "Items": [] }));

        let mirror = TestMirror::new(client);
        apply_server_event(
            &mirror.state,
            ServerEvent::LibraryChanged {
                added: vec![],
                updated: vec![boxset_id.to_string()],
                removed: vec![],
            },
        )
        .await;
        mirror.state.writer.barrier().await;

        assert_eq!(
            collection_member_ids(&mirror, boxset_id),
            vec![member_id.to_string()]
        );
    }

    #[tokio::test]
    async fn library_changed_removed_deletes_rows() {
        let server = MockServer::start().await;
        let client = JellyfinClient::from_token(&server.base_url, identity(), "tok");

        let mirror = TestMirror::new(client);
        // Seed directly via the writer so we don't need a route for it.
        let dto: BaseItemDto =
            serde_json::from_value(movie_json("22222222-2222-2222-2222-222222222222", "Doomed"))
                .expect("dto");
        mirror.state.writer.upsert_items(vec![dto]).await;
        assert_eq!(mirror.item_count().await, 1);

        apply_server_event(
            &mirror.state,
            ServerEvent::LibraryChanged {
                added: vec![],
                updated: vec![],
                removed: vec!["22222222-2222-2222-2222-222222222222".to_string()],
            },
        )
        .await;

        assert_eq!(mirror.item_count().await, 0);
    }

    /// docs/07 §1: a watch-state change on an episode, from any device, asks for Next Up;
    /// one on a movie never does.
    #[tokio::test]
    async fn user_data_changed_on_an_episode_requests_next_up_and_a_movie_does_not() {
        let server = MockServer::start().await;
        let client = JellyfinClient::from_token(&server.base_url, identity(), "tok");
        let mirror = TestMirror::new(client);
        let movie = "22222222-2222-2222-2222-222222222222";
        let episode = "33333333-3333-3333-3333-333333333333";
        let dtos: Vec<BaseItemDto> = [
            movie_json(movie, "Movie"),
            json!({ "Id": episode, "Name": "Episode", "Type": "Episode" }),
        ]
        .into_iter()
        .map(|v| serde_json::from_value(v).expect("dto"))
        .collect();
        mirror.state.writer.upsert_items(dtos).await;
        mirror.state.writer.barrier().await;

        for (id, expected) in [(movie, false), (episode, true)] {
            let user_data: jellyfin_api::models::UserItemDataDto =
                serde_json::from_value(json!({ "Key": "k", "Played": true })).expect("user data");
            apply_server_event(
                &mirror.state,
                ServerEvent::UserDataChanged {
                    item_userdata: vec![(id.to_string(), user_data)],
                },
            )
            .await;
            assert_eq!(next_up_was_requested(&mirror.state).await, expected, "{id}");
        }
    }

    /// docs/07 §1: the first Next Up request fetches at once; a burst right after waits
    /// out the cooldown and costs exactly one more fetch.
    #[tokio::test]
    async fn next_up_refresher_fetches_at_once_then_coalesces_a_burst_into_one() {
        let server = MockServer::start().await;
        let client = JellyfinClient::from_token(&server.base_url, identity(), "tok");
        server.route("/Shows/NextUp", &[], json!({ "Items": [] }));
        let mirror = TestMirror::new(client);
        tokio::spawn(next_up_refresher(
            Arc::downgrade(&mirror.state),
            mirror.state.next_up_requested.clone(),
        ));
        let fetches = || server.request_count("/Shows/NextUp");
        let wait_for = |n: usize| async move {
            for _ in 0..100 {
                if fetches() >= n {
                    return;
                }
                tokio::time::sleep(std::time::Duration::from_millis(20)).await;
            }
        };

        mirror.state.request_next_up_refresh();
        wait_for(1).await;
        assert_eq!(fetches(), 1, "the first request fetches without waiting");

        for _ in 0..5 {
            mirror.state.request_next_up_refresh();
        }
        tokio::time::sleep(NEXT_UP_COOLDOWN / 2).await;
        assert_eq!(fetches(), 1, "a burst inside the cooldown waits");
        tokio::time::sleep(NEXT_UP_COOLDOWN).await;
        wait_for(2).await;
        tokio::time::sleep(NEXT_UP_COOLDOWN + std::time::Duration::from_millis(300)).await;
        assert_eq!(fetches(), 2, "the whole burst cost one fetch");
    }

    #[tokio::test]
    async fn user_data_changed_updates_columns() {
        let server = MockServer::start().await;
        let client = JellyfinClient::from_token(&server.base_url, identity(), "tok");
        let mirror = TestMirror::new(client);

        let dto: BaseItemDto =
            serde_json::from_value(movie_json("22222222-2222-2222-2222-222222222222", "Movie"))
                .expect("dto");
        mirror.state.writer.upsert_items(vec![dto]).await;

        let user_data: jellyfin_api::models::UserItemDataDto = serde_json::from_value(json!({
            "Key": "k", "Played": true, "PlaybackPositionTicks": 12345
        }))
        .expect("user data");
        apply_server_event(
            &mirror.state,
            ServerEvent::UserDataChanged {
                item_userdata: vec![(
                    "22222222-2222-2222-2222-222222222222".to_string(),
                    user_data,
                )],
            },
        )
        .await;

        mirror.state.writer.barrier().await;
        let conn = mirror.state.read_pool.acquire();
        let (played, pos): (bool, i64) = conn
            .query_row(
                "SELECT played, playback_position_ticks FROM items WHERE id = '22222222-2222-2222-2222-222222222222'",
                [],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )
            .expect("row");
        assert!(played);
        assert_eq!(pos, 12345);
    }

    /// A WS delta for an item not yet reached by the breadth-sync page must not be clobbered
    /// once that page's now-stale snapshot lands. Simulated with a delayed page fetch:
    /// deliver a `UserDataChanged` while it's in flight and assert the WS value wins.
    #[tokio::test]
    async fn ws_delta_buffered_during_initial_sync_replays_and_wins_over_stale_snapshot() {
        use rusqlite::OptionalExtension;
        use std::time::Duration;

        let server = MockServer::start().await;
        let client = JellyfinClient::from_token(&server.base_url, identity(), "tok");
        let view_id = "11111111-1111-1111-1111-111111111111";
        let item_id = "22222222-2222-2222-2222-222222222222";

        server.route(
            "/UserViews",
            &[],
            json!({ "Items": [{ "Id": view_id, "Name": "Movies", "CollectionType": "movies" }] }),
        );
        server.route("/UserItems/Resume", &[], json!({ "Items": [] }));
        server.route("/Shows/NextUp", &[], json!({ "Items": [] }));
        // The breadth-sync page is slow; its snapshot is already stale by the time it lands.
        server.route_delayed(
            "/Items",
            &[("parentId", view_id), ("startIndex", "0")],
            json!({
                "Items": [{
                    "Id": item_id, "Name": "Old Snapshot", "Type": "Movie",
                    "UserData": { "Key": "k", "Played": false, "PlaybackPositionTicks": 0 }
                }],
                "TotalRecordCount": 1
            }),
            Duration::from_millis(250),
        );

        let mirror = TestMirror::new(client);
        let (bus_tx, bus_rx) = broadcast::channel::<BusEvent>(16);
        tokio::spawn(bus_listener(mirror.weak(), bus_rx));
        tokio::spawn({
            let weak = mirror.weak();
            async move { initial_sync(&weak).await }
        });

        // Give initial_sync a moment to start the delayed fetch, then confirm it's still in
        // flight, or this test would pass vacuously.
        tokio::time::sleep(Duration::from_millis(50)).await;
        assert!(
            mirror
                .state
                .initial_sync_in_progress
                .load(Ordering::Acquire),
            "sanity: initial sync should still be running (page fetch delayed 250ms)"
        );

        let user_data: jellyfin_api::models::UserItemDataDto = serde_json::from_value(json!({
            "Key": "k", "Played": true, "PlaybackPositionTicks": 99999
        }))
        .expect("user data");
        bus_tx
            .send(BusEvent::Server(ServerEvent::UserDataChanged {
                item_userdata: vec![(item_id.to_string(), user_data)],
            }))
            .expect("send bus event");

        // The delayed page snapshot commits first (played=false); the buffered delta must
        // replay on top of it once initial sync completes (played=true).
        let deadline = tokio::time::Instant::now() + Duration::from_secs(5);
        loop {
            mirror.state.writer.barrier().await;
            let conn = mirror.state.read_pool.acquire();
            let row: Option<(bool, i64)> = conn
                .query_row(
                    "SELECT played, playback_position_ticks FROM items WHERE id = ?1",
                    [item_id],
                    |r| Ok((r.get(0)?, r.get(1)?)),
                )
                .optional()
                .expect("query");
            drop(conn);
            if row == Some((true, 99999)) {
                break;
            }
            assert!(
                tokio::time::Instant::now() < deadline,
                "timed out waiting for the buffered WS delta to replay; last row: {row:?}"
            );
            tokio::time::sleep(Duration::from_millis(20)).await;
        }

        assert!(
            !mirror
                .state
                .initial_sync_in_progress
                .load(Ordering::Acquire),
            "initial sync should have completed by the time the replay landed"
        );
    }

    #[test]
    fn needs_resync_on_count_mismatch() {
        assert!(needs_resync(1, 2, None, None));
        assert!(!needs_resync(2, 2, None, None));
    }

    #[test]
    fn needs_resync_on_stale_newest_date() {
        assert!(needs_resync(
            2,
            2,
            Some("2024-01-01T00:00:00Z"),
            Some("2024-06-01T00:00:00Z")
        ));
        assert!(!needs_resync(
            2,
            2,
            Some("2024-06-01T00:00:00Z"),
            Some("2024-06-01T00:00:00Z")
        ));
    }

    #[test]
    fn needs_resync_when_local_has_no_items_but_server_reports_a_date() {
        assert!(needs_resync(0, 0, None, Some("2024-06-01T00:00:00Z")));
    }

    /// Missing-only mismatch: the sweep must enumerate ids, notice exactly one is absent
    /// locally, and fetch a full DTO for only that id, never re-downloading what it already has.
    #[tokio::test]
    async fn reconciliation_mismatch_sweep_fetches_only_the_missing_ids() {
        let server = MockServer::start().await;
        let client = JellyfinClient::from_token(&server.base_url, identity(), "tok");
        let view_id = "11111111-1111-1111-1111-111111111111";
        let have = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
        let missing = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

        server.route(
            "/UserViews",
            &[],
            json!({ "Items": [{ "Id": view_id, "Name": "Movies", "CollectionType": "movies" }] }),
        );
        // Reconciliation's count probe (limit=1).
        server.route(
            "/Items",
            &[("parentId", view_id), ("limit", "1")],
            json!({ "Items": [movie_json(have, "A")], "TotalRecordCount": 2 }),
        );
        // ID sweep: ids-only enumeration of both items, then a by-ids fetch of just the
        // missing one. Keyed on ID_SWEEP_PAGE_SIZE so it can't be confused with the count probe.
        route_sweep_enumeration(&server, view_id, &[have, missing]);
        route_by_ids(&server, &[movie_json(missing, "B")]);

        let mirror = TestMirror::new(client);
        // Seed local state to start from a realistic "only one of two items synced" mismatch.
        mirror
            .state
            .writer
            .upsert_views(vec![crate::ViewRow {
                id: view_id.to_string(),
                name: "Movies".to_string(),
                collection_type: "movies".to_string(),
                item_type: "CollectionFolder".to_string(),
            }])
            .await;
        let seed: BaseItemDto = serde_json::from_value(movie_json(have, "A")).expect("dto");
        mirror
            .state
            .writer
            .upsert_items_scoped(vec![seed], Some(view_id.to_string()))
            .await;
        assert_eq!(mirror.item_count().await, 1);

        reconcile_all(&mirror.state).await;

        assert_eq!(
            mirror.item_count().await,
            2,
            "the sweep must fill in the missing item"
        );
        assert_eq!(
            mirror.item_name(missing).await.as_deref(),
            Some("B"),
            "the missing id must land as a real, fully-fetched row"
        );
        assert_eq!(
            server.request_count_matching(BY_IDS_MARKER),
            1,
            "exactly one by-ids repair fetch, covering only the missing id"
        );
        assert_eq!(
            server.request_count_matching(&format!("{BY_IDS_MARKER}{missing}")),
            1,
            "the repair fetch must ask for the missing id and nothing else"
        );
        assert_eq!(
            server.request_count_matching(&format!("limit={PAGE_SIZE}")),
            0,
            "no full breadth page may be requested any more"
        );
    }

    /// A virtual placeholder episode deleted server-side and replaced with a new id: the
    /// mismatch must trigger a resync that both lands the new item and prunes the old one.
    #[tokio::test]
    async fn reconcile_converges_a_virtual_to_real_episode_id_swap() {
        let server = MockServer::start().await;
        let client = JellyfinClient::from_token(&server.base_url, identity(), "tok");
        let view_id = "11111111-1111-1111-1111-111111111111";
        let old_virtual_id = "22222222-2222-2222-2222-222222222222";
        let new_real_id = "33333333-3333-3333-3333-333333333333";

        server.route(
            "/UserViews",
            &[],
            // The locally-seeded virtual placeholder has no date_created, so
            // needs_resync's (None, Some(_)) => true branch triggers the resync -- the
            // swap itself is count-neutral and wouldn't trip the count check alone.
            json!({ "Items": [{
                "Id": view_id, "Name": "Shows", "CollectionType": "tvshows",
                "DateLastMediaAdded": "2024-06-01T00:00:00Z"
            }] }),
        );
        // The server now reports exactly 1 item under this library (the real episode); the
        // virtual placeholder is gone, replaced by a new id.
        server.route(
            "/Items",
            &[("parentId", view_id), ("limit", "1")],
            json!({ "Items": [], "TotalRecordCount": 1 }),
        );
        // ID sweep: enumeration returns only the new real id, then a by-ids repair fetch.
        route_sweep_enumeration(&server, view_id, &[new_real_id]);
        route_by_ids(
            &server,
            &[json!({
                "Id": new_real_id, "Name": "S01E01", "Type": "Episode",
                "LocationType": "FileSystem"
            })],
        );

        let mirror = TestMirror::new(client);
        mirror
            .state
            .writer
            .upsert_views(vec![crate::ViewRow {
                id: view_id.to_string(),
                name: "Shows".to_string(),
                collection_type: "tvshows".to_string(),
                item_type: "CollectionFolder".to_string(),
            }])
            .await;
        // Seed the mirror with the now-dead virtual placeholder, stamped like a real breadth
        // sync would.
        let virtual_dto: BaseItemDto = serde_json::from_value(json!({
            "Id": old_virtual_id, "Name": "S01E01", "Type": "Episode",
            "LocationType": "Virtual"
        }))
        .expect("dto");
        mirror
            .state
            .writer
            .upsert_items_scoped(vec![virtual_dto], Some(view_id.to_string()))
            .await;
        assert_eq!(mirror.item_count().await, 1);

        reconcile_all(&mirror.state).await;
        mirror.state.writer.barrier().await;

        let conn = mirror.state.read_pool.acquire();
        let rows: Vec<(String, i64)> = {
            let mut stmt = conn
                .prepare("SELECT id, is_virtual FROM items ORDER BY id")
                .expect("prepare");
            stmt.query_map([], |r| Ok((r.get(0)?, r.get(1)?)))
                .expect("query")
                .collect::<rusqlite::Result<_>>()
                .expect("rows")
        };
        assert_eq!(
            rows,
            vec![(new_real_id.to_string(), 0)],
            "old virtual row must be pruned; only the new real row (is_virtual=0) must remain"
        );
    }

    /// The exact live-server shape (observed on a live server) that the count
    /// and date probes are BOTH structurally blind to: a real episode
    /// silently replaced a virtual placeholder (count-neutral -- one row
    /// out, one row in), the server returns `DateLastMediaAdded` as the
    /// DateTime.MinValue sentinel ("0001-01-01...", i.e. never populated),
    /// and the local placeholder has a real `date_created` so the
    /// `(None, Some(_))` date branch can't fire either. Only the
    /// newest-ids presence probe can see this: the server's newest items
    /// include an id the mirror doesn't have.
    #[tokio::test]
    async fn reconcile_newest_ids_probe_catches_count_neutral_swap_with_sentinel_date() {
        let server = MockServer::start().await;
        let client = JellyfinClient::from_token(&server.base_url, identity(), "tok");
        let view_id = "11111111-1111-1111-1111-111111111111";
        let old_virtual_id = "22222222-2222-2222-2222-222222222222";
        let new_real_id = "33333333-3333-3333-3333-333333333333";

        server.route(
            "/UserViews",
            &[],
            json!({ "Items": [{
                "Id": view_id, "Name": "Shows", "CollectionType": "tvshows",
                "DateLastMediaAdded": "0001-01-01T00:00:00Z"
            }] }),
        );
        // Count probe: server total == local total (count-neutral swap).
        server.route(
            "/Items",
            &[("parentId", view_id), ("limit", "1")],
            json!({ "Items": [], "TotalRecordCount": 1 }),
        );
        // Newest-ids probe: the server's newest item is the new real id, absent locally.
        server.route(
            "/Items",
            &[("parentId", view_id), ("limit", "20")],
            json!({
                "Items": [{
                    "Id": new_real_id, "Name": "S01E01", "Type": "Episode",
                    "LocationType": "FileSystem"
                }],
                "TotalRecordCount": 1
            }),
        );
        // The triggered ID sweep: enumeration (new real id only) + by-ids repair fetch.
        route_sweep_enumeration(&server, view_id, &[new_real_id]);
        route_by_ids(
            &server,
            &[json!({
                "Id": new_real_id, "Name": "S01E01", "Type": "Episode",
                "LocationType": "FileSystem"
            })],
        );

        let mirror = TestMirror::new(client);
        mirror
            .state
            .writer
            .upsert_views(vec![crate::ViewRow {
                id: view_id.to_string(),
                name: "Shows".to_string(),
                collection_type: "tvshows".to_string(),
                item_type: "CollectionFolder".to_string(),
            }])
            .await;
        // Seed the dead placeholder with a date_created, making the date probe's
        // (Some, None) combination inert.
        let virtual_dto: BaseItemDto = serde_json::from_value(json!({
            "Id": old_virtual_id, "Name": "S01E01", "Type": "Episode",
            "LocationType": "Virtual", "DateCreated": "2026-08-12T07:37:25Z"
        }))
        .expect("dto");
        mirror
            .state
            .writer
            .upsert_items_scoped(vec![virtual_dto], Some(view_id.to_string()))
            .await;
        assert_eq!(mirror.item_count().await, 1);

        reconcile_all(&mirror.state).await;
        mirror.state.writer.barrier().await;

        let conn = mirror.state.read_pool.acquire();
        let rows: Vec<(String, i64)> = {
            let mut stmt = conn
                .prepare("SELECT id, is_virtual FROM items ORDER BY id")
                .expect("prepare");
            stmt.query_map([], |r| Ok((r.get(0)?, r.get(1)?)))
                .expect("query")
                .collect::<rusqlite::Result<_>>()
                .expect("rows")
        };
        assert_eq!(
            rows,
            vec![(new_real_id.to_string(), 0)],
            "newest-ids probe must trigger the resync that lands the real \
             row and prunes the placeholder, with both cheap probes blind"
        );
    }

    /// A resync-triggered prune must be scoped strictly to the library being resynced; a
    /// second, unrelated library's rows must survive untouched.
    #[tokio::test]
    async fn reconcile_resync_prunes_only_the_mismatched_library() {
        let server = MockServer::start().await;
        let client = JellyfinClient::from_token(&server.base_url, identity(), "tok");
        let movies_view = "11111111-1111-1111-1111-111111111111";
        let shows_view = "44444444-4444-4444-4444-444444444444";
        let orphan_movie = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
        let other_library_movie = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

        server.route(
            "/UserViews",
            &[],
            json!({ "Items": [
                { "Id": movies_view, "Name": "Movies", "CollectionType": "movies" },
                { "Id": shows_view, "Name": "Shows", "CollectionType": "tvshows" },
            ] }),
        );
        // Movies: server now reports 0 items (the locally-seeded movie is an orphan).
        server.route(
            "/Items",
            &[("parentId", movies_view), ("limit", "1")],
            json!({ "Items": [], "TotalRecordCount": 0 }),
        );
        route_sweep_enumeration(&server, movies_view, &[]);
        // Shows: in sync, so it must never sweep -- no enumeration route registered for it.
        server.route(
            "/Items",
            &[("parentId", shows_view), ("limit", "1")],
            json!({ "Items": [], "TotalRecordCount": 1 }),
        );
        server.route(
            "/Items",
            &[("parentId", shows_view), ("limit", "20")],
            json!({ "Items": [{ "Id": other_library_movie }], "TotalRecordCount": 1 }),
        );

        let mirror = TestMirror::new(client);
        mirror
            .state
            .writer
            .upsert_views(vec![
                crate::ViewRow {
                    id: movies_view.to_string(),
                    name: "Movies".to_string(),
                    collection_type: "movies".to_string(),
                    item_type: "CollectionFolder".to_string(),
                },
                crate::ViewRow {
                    id: shows_view.to_string(),
                    name: "Shows".to_string(),
                    collection_type: "tvshows".to_string(),
                    item_type: "CollectionFolder".to_string(),
                },
            ])
            .await;
        let orphan: BaseItemDto =
            serde_json::from_value(movie_json(orphan_movie, "Orphan")).expect("dto");
        mirror
            .state
            .writer
            .upsert_items_scoped(vec![orphan], Some(movies_view.to_string()))
            .await;
        // A different library's row; if pruning ever escaped its own library scope this
        // would get deleted too.
        let other: BaseItemDto =
            serde_json::from_value(movie_json(other_library_movie, "Untouched")).expect("dto");
        mirror
            .state
            .writer
            .upsert_items_scoped(vec![other], Some(shows_view.to_string()))
            .await;
        assert_eq!(mirror.item_count().await, 2);

        reconcile_all(&mirror.state).await;
        mirror.state.writer.barrier().await;

        let conn = mirror.state.read_pool.acquire();
        let ids: Vec<String> = {
            let mut stmt = conn.prepare("SELECT id FROM items").expect("prepare");
            stmt.query_map([], |r| r.get(0))
                .expect("query")
                .collect::<rusqlite::Result<_>>()
                .expect("rows")
        };
        assert_eq!(
            ids,
            vec![other_library_movie.to_string()],
            "the Movies orphan must be pruned but Shows' row must survive untouched"
        );
        // A deletion-only mismatch must cost ids-pages and nothing else.
        assert_eq!(
            server.request_count_matching(FULL_DTO_MARKER),
            0,
            "a deletion-only mismatch must not download a single full DTO"
        );
        assert_eq!(
            server.request_count_matching(BY_IDS_MARKER),
            0,
            "nothing is missing locally, so there is nothing to repair-fetch"
        );
        assert_eq!(
            server.request_count_matching(SWEEP_PAGE_MARKER),
            1,
            "exactly one ids-only enumeration page, for the drifted library only"
        );
    }

    /// Deletion-only mismatch: the server dropped K of N items. The sweep must remove
    /// exactly those K rows and issue no full-DTO request at all.
    #[tokio::test]
    async fn reconcile_sweep_removes_only_the_deleted_ids_without_fetching_any_dtos() {
        let server = MockServer::start().await;
        let client = JellyfinClient::from_token(&server.base_url, identity(), "tok");
        let view_id = "11111111-1111-1111-1111-111111111111";

        let all: Vec<String> = (0..12)
            .map(|i| format!("00000000-0000-0000-0000-{i:012}"))
            .collect();
        // The server dropped the last 3 (here just the delete half of a file upgrade; the
        // add half already landed via delta sync).
        let survivors: Vec<&str> = all[..9].iter().map(String::as_str).collect();

        server.route(
            "/UserViews",
            &[],
            json!({ "Items": [{ "Id": view_id, "Name": "Shows", "CollectionType": "tvshows" }] }),
        );
        server.route(
            "/Items",
            &[("parentId", view_id), ("limit", "1")],
            json!({ "Items": [], "TotalRecordCount": survivors.len() }),
        );
        route_sweep_enumeration(&server, view_id, &survivors);

        let mirror = TestMirror::new(client);
        mirror
            .state
            .writer
            .upsert_views(vec![crate::ViewRow {
                id: view_id.to_string(),
                name: "Shows".to_string(),
                collection_type: "tvshows".to_string(),
                item_type: "CollectionFolder".to_string(),
            }])
            .await;
        let seeded: Vec<BaseItemDto> = all
            .iter()
            .map(|id| {
                serde_json::from_value(json!({
                    "Id": id, "Name": "Episode", "Type": "Episode",
                    "LocationType": "FileSystem"
                }))
                .expect("dto")
            })
            .collect();
        mirror
            .state
            .writer
            .upsert_items_scoped(seeded, Some(view_id.to_string()))
            .await;
        assert_eq!(mirror.item_count().await, 12);

        reconcile_all(&mirror.state).await;
        mirror.state.writer.barrier().await;

        let conn = mirror.state.read_pool.acquire();
        let remaining: Vec<String> = {
            let mut stmt = conn
                .prepare("SELECT id FROM items ORDER BY id")
                .expect("prepare");
            stmt.query_map([], |r| r.get(0))
                .expect("query")
                .collect::<rusqlite::Result<_>>()
                .expect("rows")
        };
        drop(conn);
        assert_eq!(
            remaining,
            survivors.iter().map(|s| s.to_string()).collect::<Vec<_>>(),
            "exactly the 3 server-side deletions must be shed, nothing else"
        );
        assert_eq!(
            server.request_count_matching(FULL_DTO_MARKER),
            0,
            "a deletion-only mismatch must cost zero full DTOs"
        );
        assert_eq!(
            server.request_count_matching(BY_IDS_MARKER),
            0,
            "and zero by-ids repair fetches"
        );
        assert_eq!(
            server.request_count_matching(SWEEP_PAGE_MARKER),
            1,
            "one ids-only page covers a library this size"
        );

        // Convergence: a second pass must find the library in agreement and sweep nothing.
        let sweep_pages_after_first = server.request_count_matching(SWEEP_PAGE_MARKER);
        server.route(
            "/Items",
            &[("parentId", view_id), ("limit", "20")],
            json!({
                "Items": survivors.iter().rev().map(|id| json!({ "Id": id })).collect::<Vec<_>>(),
                "TotalRecordCount": survivors.len(),
            }),
        );
        reconcile_all(&mirror.state).await;
        assert_eq!(
            server.request_count_matching(SWEEP_PAGE_MARKER),
            sweep_pages_after_first,
            "the counts converged, so the next pass must not sweep again"
        );
    }

    /// Both directions in one pass: the sweep must prune a dropped id and fetch a gained one.
    #[tokio::test]
    async fn reconcile_sweep_handles_orphans_and_missing_ids_in_one_pass() {
        let server = MockServer::start().await;
        let client = JellyfinClient::from_token(&server.base_url, identity(), "tok");
        let view_id = "11111111-1111-1111-1111-111111111111";
        let kept = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
        let orphan = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
        let arrival = "cccccccc-cccc-cccc-cccc-cccccccccccc";

        server.route(
            "/UserViews",
            &[],
            json!({ "Items": [{ "Id": view_id, "Name": "Movies", "CollectionType": "movies" }] }),
        );
        // Count-neutral drift (one out, one in), caught by the newest-ids probe.
        server.route(
            "/Items",
            &[("parentId", view_id), ("limit", "1")],
            json!({ "Items": [], "TotalRecordCount": 2 }),
        );
        server.route(
            "/Items",
            &[("parentId", view_id), ("limit", "20")],
            json!({ "Items": [{ "Id": arrival }, { "Id": kept }], "TotalRecordCount": 2 }),
        );
        route_sweep_enumeration(&server, view_id, &[kept, arrival]);
        route_by_ids(&server, &[movie_json(arrival, "Brand New")]);

        let mirror = TestMirror::new(client);
        mirror
            .state
            .writer
            .upsert_views(vec![crate::ViewRow {
                id: view_id.to_string(),
                name: "Movies".to_string(),
                collection_type: "movies".to_string(),
                item_type: "CollectionFolder".to_string(),
            }])
            .await;
        let seeded: Vec<BaseItemDto> = [kept, orphan]
            .iter()
            .map(|id| serde_json::from_value(movie_json(id, "Seeded")).expect("dto"))
            .collect();
        mirror
            .state
            .writer
            .upsert_items_scoped(seeded, Some(view_id.to_string()))
            .await;
        assert_eq!(mirror.item_count().await, 2);

        reconcile_all(&mirror.state).await;
        mirror.state.writer.barrier().await;

        let conn = mirror.state.read_pool.acquire();
        let remaining: Vec<String> = {
            let mut stmt = conn
                .prepare("SELECT id FROM items ORDER BY id")
                .expect("prepare");
            stmt.query_map([], |r| r.get(0))
                .expect("query")
                .collect::<rusqlite::Result<_>>()
                .expect("rows")
        };
        drop(conn);
        assert_eq!(
            remaining,
            vec![kept.to_string(), arrival.to_string()],
            "the orphan must go, the arrival must land, the untouched row must stay"
        );
        assert_eq!(
            server.request_count_matching(BY_IDS_MARKER),
            1,
            "one repair fetch, covering only the arrival"
        );
        assert_eq!(
            mirror.item_name(kept).await.as_deref(),
            Some("Seeded"),
            "an id present on both sides must not be re-fetched or rewritten"
        );
    }

    /// A sweep that fails partway (by-ids repair 404s after enumeration succeeds) must leave
    /// the mirror exactly as it was and not fall back to a full breadth walk; the mismatch
    /// stands so the next pass retries and converges.
    #[tokio::test]
    async fn reconcile_sweep_failure_keeps_prior_state_and_stays_retryable() {
        let server = MockServer::start().await;
        let client = JellyfinClient::from_token(&server.base_url, identity(), "tok");
        let view_id = "11111111-1111-1111-1111-111111111111";
        let orphan = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
        let arrival = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

        server.route(
            "/UserViews",
            &[],
            json!({ "Items": [{ "Id": view_id, "Name": "Movies", "CollectionType": "movies" }] }),
        );
        server.route(
            "/Items",
            &[("parentId", view_id), ("limit", "1")],
            json!({ "Items": [], "TotalRecordCount": 1 }),
        );
        server.route(
            "/Items",
            &[("parentId", view_id), ("limit", "20")],
            json!({ "Items": [{ "Id": arrival }], "TotalRecordCount": 1 }),
        );
        route_sweep_enumeration(&server, view_id, &[arrival]);
        // No by-ids route yet: the repair fetch 404s.

        let mirror = TestMirror::new(client);
        mirror
            .state
            .writer
            .upsert_views(vec![crate::ViewRow {
                id: view_id.to_string(),
                name: "Movies".to_string(),
                collection_type: "movies".to_string(),
                item_type: "CollectionFolder".to_string(),
            }])
            .await;
        let seed: BaseItemDto = serde_json::from_value(movie_json(orphan, "Doomed")).expect("dto");
        mirror
            .state
            .writer
            .upsert_items_scoped(vec![seed], Some(view_id.to_string()))
            .await;

        reconcile_all(&mirror.state).await;
        mirror.state.writer.barrier().await;

        let conn = mirror.state.read_pool.acquire();
        let remaining: Vec<String> = {
            let mut stmt = conn
                .prepare("SELECT id FROM items ORDER BY id")
                .expect("prepare");
            stmt.query_map([], |r| r.get(0))
                .expect("query")
                .collect::<rusqlite::Result<_>>()
                .expect("rows")
        };
        drop(conn);
        assert_eq!(
            remaining,
            vec![orphan.to_string()],
            "a failed sweep must delete nothing -- the last-good mirror state stands"
        );
        assert_eq!(
            server.request_count_matching(&format!("limit={PAGE_SIZE}")),
            0,
            "a failed sweep must NOT fall back to the full breadth walk"
        );

        // The mismatch still stands, so the next tick retries and, with the repair fetch
        // now answerable, converges.
        route_by_ids(&server, &[movie_json(arrival, "Arrived")]);
        reconcile_all(&mirror.state).await;
        mirror.state.writer.barrier().await;

        let conn = mirror.state.read_pool.acquire();
        let remaining: Vec<String> = {
            let mut stmt = conn
                .prepare("SELECT id FROM items ORDER BY id")
                .expect("prepare");
            stmt.query_map([], |r| r.get(0))
                .expect("query")
                .collect::<rusqlite::Result<_>>()
                .expect("rows")
        };
        drop(conn);
        assert_eq!(
            remaining,
            vec![arrival.to_string()],
            "the retry must converge: orphan pruned, arrival fetched"
        );
    }

    /// `prune_library` is scoped by `library_id`, so a BoxSet member living in another
    /// library must survive a sweep of the collections library, along with its
    /// `collection_members` row.
    #[tokio::test]
    async fn reconcile_sweep_does_not_delete_items_that_live_in_another_library() {
        let server = MockServer::start().await;
        let client = JellyfinClient::from_token(&server.base_url, identity(), "tok");
        let boxsets_view = "11111111-1111-1111-1111-111111111111";
        let movies_view = "22222222-2222-2222-2222-222222222222";
        let live_boxset = "33333333-3333-3333-3333-333333333333";
        let dead_boxset = "44444444-4444-4444-4444-444444444444";
        let shared_member = "55555555-5555-5555-5555-555555555555";

        server.route(
            "/UserViews",
            &[],
            json!({ "Items": [
                { "Id": boxsets_view, "Name": "Collections", "CollectionType": "boxsets" },
            ] }),
        );
        // One of the two locally-known BoxSets is gone server-side.
        server.route(
            "/Items",
            &[("parentId", boxsets_view), ("limit", "1")],
            json!({ "Items": [], "TotalRecordCount": 1 }),
        );
        route_sweep_enumeration(&server, boxsets_view, &[live_boxset]);
        // Post-sweep membership refresh for the surviving BoxSet.
        server.route(
            "/Items",
            &[("parentId", live_boxset), ("recursive", "false")],
            json!({ "Items": [{ "Id": shared_member, "Name": "Shared", "Type": "Movie" }] }),
        );

        let mirror = TestMirror::new(client);
        mirror
            .state
            .writer
            .upsert_views(vec![crate::ViewRow {
                id: boxsets_view.to_string(),
                name: "Collections".to_string(),
                collection_type: "boxsets".to_string(),
                item_type: "CollectionFolder".to_string(),
            }])
            .await;
        let boxsets: Vec<BaseItemDto> = [live_boxset, dead_boxset]
            .iter()
            .map(|id| {
                serde_json::from_value(json!({ "Id": id, "Name": "Set", "Type": "BoxSet" }))
                    .expect("dto")
            })
            .collect();
        mirror
            .state
            .writer
            .upsert_items_scoped(boxsets, Some(boxsets_view.to_string()))
            .await;
        // The member lives in the Movies library, not stamped with the boxsets library.
        let member: BaseItemDto =
            serde_json::from_value(movie_json(shared_member, "Shared")).expect("dto");
        mirror
            .state
            .writer
            .upsert_items_scoped(vec![member], Some(movies_view.to_string()))
            .await;
        mirror
            .state
            .writer
            .set_collection_members(
                live_boxset.to_string(),
                vec![(shared_member.to_string(), 0)],
            )
            .await;
        mirror
            .state
            .writer
            .set_collection_members(
                dead_boxset.to_string(),
                vec![(shared_member.to_string(), 0)],
            )
            .await;
        assert_eq!(mirror.item_count().await, 3);

        reconcile_all(&mirror.state).await;
        mirror.state.writer.barrier().await;

        let conn = mirror.state.read_pool.acquire();
        let remaining: Vec<String> = {
            let mut stmt = conn
                .prepare("SELECT id FROM items ORDER BY id")
                .expect("prepare");
            stmt.query_map([], |r| r.get(0))
                .expect("query")
                .collect::<rusqlite::Result<_>>()
                .expect("rows")
        };
        drop(conn);
        assert_eq!(
            remaining,
            vec![live_boxset.to_string(), shared_member.to_string()],
            "only the dead BoxSet may be pruned -- its member lives in another library and \
             must survive"
        );
        assert_eq!(
            collection_member_ids(&mirror, live_boxset),
            vec![shared_member.to_string()],
            "the surviving collection keeps its membership (and the sweep still runs the \
             post-resync membership refresh a boxsets library needs)"
        );
        assert!(
            collection_member_ids(&mirror, dead_boxset).is_empty(),
            "the pruned BoxSet's own membership rows must go with it"
        );
    }

    /// The sweep must keep the breadth walk's observability contracts: `is_syncing()` stays
    /// true throughout, `Syncing` is emitted with the drifted library's id, and terminal
    /// `Idle` lands with the in-flight counter already back to zero.
    #[tokio::test]
    async fn reconcile_sweep_reports_syncing_then_idle_and_counts_as_syncing() {
        use std::time::Duration;

        let server = MockServer::start().await;
        let client = JellyfinClient::from_token(&server.base_url, identity(), "tok");
        let view_id = "11111111-1111-1111-1111-111111111111";
        let orphan = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

        server.route(
            "/UserViews",
            &[],
            json!({ "Items": [{ "Id": view_id, "Name": "Movies", "CollectionType": "movies" }] }),
        );
        server.route(
            "/Items",
            &[("parentId", view_id), ("limit", "1")],
            json!({ "Items": [], "TotalRecordCount": 0 }),
        );
        // Slow enumeration page, so the Syncing state is observable rather than raced.
        server.route_delayed(
            "/Items",
            &[
                ("parentId", view_id),
                ("limit", &ID_SWEEP_PAGE_SIZE.to_string()),
            ],
            json!({ "Items": [], "TotalRecordCount": 0 }),
            Duration::from_millis(150),
        );

        let mirror = TestMirror::new(client);
        mirror
            .state
            .writer
            .upsert_views(vec![crate::ViewRow {
                id: view_id.to_string(),
                name: "Movies".to_string(),
                collection_type: "movies".to_string(),
                item_type: "CollectionFolder".to_string(),
            }])
            .await;
        let seed: BaseItemDto = serde_json::from_value(movie_json(orphan, "Doomed")).expect("dto");
        mirror
            .state
            .writer
            .upsert_items_scoped(vec![seed], Some(view_id.to_string()))
            .await;
        mirror.state.writer.barrier().await;

        let mut activity_rx = mirror.state.sync_activity.subscribe();
        assert_eq!(*activity_rx.borrow(), SyncActivity::Idle);

        let state = mirror.state.clone();
        let handle = tokio::spawn(async move { reconcile_all(&state).await });

        activity_rx
            .changed()
            .await
            .expect("activity changed to Syncing");
        assert_eq!(
            *activity_rx.borrow(),
            SyncActivity::Syncing {
                library_name_or_id: view_id.to_string(),
                pages_done: 0,
                items_done: 0,
                total_items: None,
            },
            "the sweep must report progress under the drifted library's id"
        );
        assert!(
            mirror.state.breadth_syncs_in_flight.load(Ordering::Acquire) > 0,
            "is_syncing() must cover the sweep while it runs"
        );

        handle.await.expect("reconcile task");
        activity_rx
            .changed()
            .await
            .expect("activity changed back to Idle");
        assert_eq!(*activity_rx.borrow(), SyncActivity::Idle);
        assert_eq!(
            mirror.state.breadth_syncs_in_flight.load(Ordering::Acquire),
            0,
            "the in-flight counter must be back to zero before the terminal Idle is observable"
        );
    }

    /// Same contract as `breadth_sync_yields_while_playback_is_active`, for
    /// the sweep: no enumeration page may go out while a stream is running,
    /// and the sweep must resume and converge once playback stops. Real time
    /// (not `start_paused`), matching the breadth-yield test's rationale --
    /// the sweep does live loopback HTTP and auto-advance races reqwest's own
    /// timers.
    #[tokio::test]
    async fn reconcile_sweep_yields_while_playback_is_active() {
        let server = MockServer::start().await;
        let client = JellyfinClient::from_token(&server.base_url, identity(), "tok");
        let view_id = "11111111-1111-1111-1111-111111111111";
        let orphan = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

        route_sweep_enumeration(&server, view_id, &[]);

        let mirror = TestMirror::new(client);
        let seed: BaseItemDto = serde_json::from_value(movie_json(orphan, "Doomed")).expect("dto");
        mirror
            .state
            .writer
            .upsert_items_scoped(vec![seed], Some(view_id.to_string()))
            .await;
        mirror.state.writer.barrier().await;
        mirror.state.playback_active.store(true, Ordering::Release);

        let state = mirror.state.clone();
        let view_id_owned = view_id.to_string();
        let handle =
            tokio::spawn(async move { reconcile_sweep(&state, &view_id_owned, "movies").await });

        // Several yield-poll cycles: with playback active the sweep must issue no page.
        tokio::time::sleep(PLAYBACK_YIELD_POLL * 4).await;
        assert_eq!(
            server.request_count("/Items"),
            0,
            "no sweep page may be fetched while playback is active"
        );

        mirror.state.playback_active.store(false, Ordering::Release);
        let outcome = handle.await.expect("join").expect("sweep must succeed");
        assert_eq!(outcome.orphans_removed, 1);
        assert_eq!(mirror.item_count().await, 0, "the resumed sweep must prune");
    }

    /// `local_summary`'s count must cover every item type under the library, virtual
    /// placeholders included, matching `reconcile_view`'s unrestricted server-count probe --
    /// otherwise a healthy mirror with an unaired episode would resync forever.
    #[tokio::test]
    async fn local_summary_counts_virtual_items_same_as_the_server_side_probe() {
        let client = JellyfinClient::from_token("http://127.0.0.1:0", identity(), "tok");
        let mirror = TestMirror::new(client);
        let view_id = "11111111-1111-1111-1111-111111111111";

        let virtual_dto: BaseItemDto = serde_json::from_value(json!({
            "Id": "22222222-2222-2222-2222-222222222222", "Name": "Unaired",
            "Type": "Episode", "LocationType": "Virtual"
        }))
        .expect("dto");
        mirror
            .state
            .writer
            .upsert_items_scoped(vec![virtual_dto], Some(view_id.to_string()))
            .await;
        mirror.state.writer.barrier().await;

        let (local_count, _) = local_summary(&mirror.state, view_id, &["Episode"]).await;
        assert_eq!(
            local_count, 1,
            "a virtual episode must count toward local_summary's total, matching the server's \
             own unfiltered recursive count"
        );
    }

    /// A virtual placeholder's `date_created` can exceed the server's real
    /// `DateLastMediaAdded`; `local_summary`'s date probe must exclude `is_virtual` rows or
    /// it would permanently mask a real virtual-to-real episode swap.
    #[tokio::test]
    async fn local_summary_date_probe_excludes_virtual_rows() {
        let client = JellyfinClient::from_token("http://127.0.0.1:0", identity(), "tok");
        let mirror = TestMirror::new(client);
        let view_id = "11111111-1111-1111-1111-111111111111";

        let real_dto: BaseItemDto = serde_json::from_value(json!({
            "Id": "22222222-2222-2222-2222-222222222222", "Name": "S01E01",
            "Type": "Episode", "LocationType": "FileSystem",
            "DateCreated": "2024-01-01T00:00:00Z"
        }))
        .expect("dto");
        // A virtual placeholder whose DateCreated is far in the future relative to the real
        // episode above.
        let virtual_dto: BaseItemDto = serde_json::from_value(json!({
            "Id": "33333333-3333-3333-3333-333333333333", "Name": "S01E02",
            "Type": "Episode", "LocationType": "Virtual",
            "DateCreated": "2099-01-01T00:00:00Z"
        }))
        .expect("dto");
        mirror
            .state
            .writer
            .upsert_items_scoped(vec![real_dto, virtual_dto], Some(view_id.to_string()))
            .await;
        mirror.state.writer.barrier().await;

        let (local_count, local_newest) = local_summary(&mirror.state, view_id, &["Episode"]).await;
        assert_eq!(local_count, 2, "both rows count toward the total");
        assert_eq!(
            // `to_rfc3339()` normalizes a Z-suffixed input to an explicit +00:00 offset.
            local_newest.as_deref(),
            Some("2024-01-01T00:00:00+00:00"),
            "the virtual row's future date must not win the MAX() -- only the real episode's \
             date_created should surface"
        );
    }

    /// (c): `local_summary`'s reconcile probe must be answerable from `idx_items_latest_virtual`
    /// alone -- no per-row lookup into `items`.
    #[test]
    fn local_summary_query_is_covering_index_only() {
        let (_dir, conn) = crate::schema::open_test_db();
        // Mirrors `local_summary`'s generated SQL exactly for a one-type `item_types` list.
        let plan_sql = "EXPLAIN QUERY PLAN SELECT COUNT(*), \
             MAX(CASE WHEN item_type IN (?) AND is_virtual = 0 THEN date_created END) \
             FROM items WHERE library_id = ?";
        let mut stmt = conn.prepare(plan_sql).expect("prepare explain");
        let plan: Vec<String> = stmt
            .query_map(rusqlite::params!["Episode", "lib1"], |row| {
                row.get::<_, String>(3)
            })
            .expect("query")
            .collect::<rusqlite::Result<_>>()
            .expect("rows");
        assert!(
            plan.iter()
                .any(|l| l.contains("COVERING INDEX idx_items_latest_virtual")),
            "local_summary's reconcile count must be index-only, plan: {plan:?}"
        );
    }

    /// A virtual placeholder's inflated `date_created` (realistic, unlike
    /// `reconcile_converges_a_virtual_to_real_episode_id_swap`'s no-date seed) must not
    /// prevent the count-neutral virtual-to-real swap from being caught.
    #[tokio::test]
    async fn reconcile_catches_virtual_to_real_swap_even_with_a_future_virtual_date() {
        let server = MockServer::start().await;
        let client = JellyfinClient::from_token(&server.base_url, identity(), "tok");
        let view_id = "11111111-1111-1111-1111-111111111111";
        let old_virtual_id = "22222222-2222-2222-2222-222222222222";
        let new_real_id = "33333333-3333-3333-3333-333333333333";

        server.route(
            "/UserViews",
            &[],
            json!({ "Items": [{
                "Id": view_id, "Name": "Shows", "CollectionType": "tvshows",
                "DateLastMediaAdded": "2026-08-13T00:00:00Z"
            }] }),
        );
        // Count-neutral swap: the date probe must ignore the future-dated virtual row.
        server.route(
            "/Items",
            &[("parentId", view_id), ("limit", "1")],
            json!({ "Items": [], "TotalRecordCount": 1 }),
        );
        route_sweep_enumeration(&server, view_id, &[new_real_id]);
        route_by_ids(
            &server,
            &[json!({
                "Id": new_real_id, "Name": "S01E01", "Type": "Episode",
                "LocationType": "FileSystem", "DateCreated": "2026-08-13T00:00:00Z"
            })],
        );

        let mirror = TestMirror::new(client);
        mirror
            .state
            .writer
            .upsert_views(vec![crate::ViewRow {
                id: view_id.to_string(),
                name: "Shows".to_string(),
                collection_type: "tvshows".to_string(),
                item_type: "CollectionFolder".to_string(),
            }])
            .await;
        // The dead virtual placeholder, dated after the server's real DateLastMediaAdded.
        let virtual_dto: BaseItemDto = serde_json::from_value(json!({
            "Id": old_virtual_id, "Name": "S01E01", "Type": "Episode",
            "LocationType": "Virtual", "DateCreated": "2026-08-14T00:00:00Z"
        }))
        .expect("dto");
        mirror
            .state
            .writer
            .upsert_items_scoped(vec![virtual_dto], Some(view_id.to_string()))
            .await;
        assert_eq!(mirror.item_count().await, 1);

        reconcile_all(&mirror.state).await;
        mirror.state.writer.barrier().await;

        let conn = mirror.state.read_pool.acquire();
        let rows: Vec<(String, i64)> = {
            let mut stmt = conn
                .prepare("SELECT id, is_virtual FROM items ORDER BY id")
                .expect("prepare");
            stmt.query_map([], |r| Ok((r.get(0)?, r.get(1)?)))
                .expect("query")
                .collect::<rusqlite::Result<_>>()
                .expect("rows")
        };
        assert_eq!(
            rows,
            vec![(new_real_id.to_string(), 0)],
            "the virtual-to-real swap must still be caught (and converged) even though the \
             virtual row's own date_created is in the future relative to the server's real \
             DateLastMediaAdded"
        );
    }

    // --- Coordinator scope additions: startup/reconnect reconcile, WS
    // nested-item library_id stamping, sync_activity observable -----------

    /// A populated-mirror launch (`needs_initial_sync = false`) must run `reconcile_all`
    /// promptly at startup, not only wait for `reconcile_timer`'s first tick.
    #[tokio::test]
    async fn populated_mirror_startup_runs_reconcile_at_t0_not_only_on_the_timer() {
        let server = MockServer::start().await;
        let client = JellyfinClient::from_token(&server.base_url, identity(), "tok");
        let view_id = "11111111-1111-1111-1111-111111111111";

        server.route(
            "/UserViews",
            &[],
            json!({ "Items": [{ "Id": view_id, "Name": "Movies", "CollectionType": "movies" }] }),
        );
        server.route("/Shows/NextUp", &[], json!({ "Items": [] }));
        // The server reports 1 item under this library; the mirror is seeded with 0.
        server.route(
            "/Items",
            &[("parentId", view_id), ("limit", "1")],
            json!({ "Items": [], "TotalRecordCount": 1 }),
        );
        route_sweep_enumeration(&server, view_id, &["aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"]);
        route_by_ids(
            &server,
            &[movie_json("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "A")],
        );

        let mirror = TestMirror::new(client);
        // A "populated, but this one library drifted" mirror: the view is known locally,
        // just missing the item the server now reports.
        mirror
            .state
            .writer
            .upsert_views(vec![crate::ViewRow {
                id: view_id.to_string(),
                name: "Movies".to_string(),
                collection_type: "movies".to_string(),
                item_type: "CollectionFolder".to_string(),
            }])
            .await;
        assert_eq!(mirror.item_count().await, 0);

        let (_bus_tx, bus_rx) = broadcast::channel(16);
        // needs_initial_sync = false: the branch Mirror::open takes for an already-populated
        // database.
        spawn(mirror.state.clone(), bus_rx, false);

        let deadline = tokio::time::Instant::now() + std::time::Duration::from_secs(5);
        loop {
            if mirror.item_count().await == 1 {
                break;
            }
            assert!(
                tokio::time::Instant::now() < deadline,
                "populated-mirror startup must run reconcile_all promptly -- the mismatched \
                 item never arrived within the timeout, so it isn't running at t=0"
            );
            tokio::time::sleep(std::time::Duration::from_millis(20)).await;
        }
    }

    /// The warm-launch startup pass must call `sync_resume` itself, not only `initial_sync`,
    /// or a UserData-only change is invisible to `delta_sync` on every warm launch.
    #[tokio::test]
    async fn warm_launch_startup_refreshes_resume() {
        let server = MockServer::start().await;
        let client = JellyfinClient::from_token(&server.base_url, identity(), "tok");
        let resume_id = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

        // No views, so the flatten/breadth-walk parts of the warm-launch pass are no-ops.
        server.route("/UserViews", &[], json!({ "Items": [] }));
        server.route("/Shows/NextUp", &[], json!({ "Items": [] }));
        server.route(
            "/UserItems/Resume",
            &[],
            json!({ "Items": [movie_json(resume_id, "Resumable Movie")] }),
        );

        let mirror = TestMirror::new(client);
        assert_eq!(mirror.item_count().await, 0);

        let (_bus_tx, bus_rx) = broadcast::channel(16);
        spawn(mirror.state.clone(), bus_rx, false);

        let deadline = tokio::time::Instant::now() + std::time::Duration::from_secs(5);
        loop {
            if mirror.item_name(resume_id).await.as_deref() == Some("Resumable Movie") {
                break;
            }
            assert!(
                tokio::time::Instant::now() < deadline,
                "warm-launch startup must call sync_resume -- the Resume item never \
                 landed in the mirror within the timeout"
            );
            tokio::time::sleep(std::time::Duration::from_millis(20)).await;
        }
    }

    /// A failed `sync_resume` fetch (404, unstaged route) must not abort the rest of the
    /// warm-launch pass; `refresh_next_up`'s item must still land.
    #[tokio::test]
    async fn warm_launch_resume_failure_does_not_abort_the_pass() {
        let server = MockServer::start().await;
        let client = JellyfinClient::from_token(&server.base_url, identity(), "tok");
        let next_up_id = "cccccccc-cccc-cccc-cccc-cccccccccccc";

        server.route("/UserViews", &[], json!({ "Items": [] }));
        server.route(
            "/Shows/NextUp",
            &[],
            json!({ "Items": [movie_json(next_up_id, "Next Up Episode")] }),
        );
        // `/UserItems/Resume` is deliberately left unrouted -- the mock
        // server 404s it, which `sync_resume` must log and swallow.

        let mirror = TestMirror::new(client);
        let (_bus_tx, bus_rx) = broadcast::channel(16);
        spawn(mirror.state.clone(), bus_rx, false);

        let deadline = tokio::time::Instant::now() + std::time::Duration::from_secs(5);
        loop {
            if mirror.item_name(next_up_id).await.as_deref() == Some("Next Up Episode") {
                break;
            }
            assert!(
                tokio::time::Instant::now() < deadline,
                "a failed sync_resume must not abort the rest of the warm-launch pass -- \
                 refresh_next_up's item never landed within the timeout"
            );
            tokio::time::sleep(std::time::Duration::from_millis(20)).await;
        }
    }

    /// The deferred favorites indexes build once, flip the gate, and signal one `Refresh` so
    /// Home and the drawer re-query; a second call is a silent no-op.
    #[tokio::test]
    async fn ensure_index_group_builds_once_and_signals_a_refresh() {
        let server = MockServer::start().await;
        let client = JellyfinClient::from_token(&server.base_url, identity(), "tok");
        let mirror = TestMirror::new(client);
        let mut changes = mirror.state.changes_tx.subscribe();
        assert!(!mirror.state.index_group_ready(IndexGroup::Favorites));

        ensure_index_group(&mirror.state, IndexGroup::Favorites).await;
        assert!(mirror.state.index_group_ready(IndexGroup::Favorites));
        assert!(matches!(changes.try_recv(), Ok(MirrorChange::Refresh)));

        ensure_index_group(&mirror.state, IndexGroup::Favorites).await;
        assert!(
            changes.try_recv().is_err(),
            "an already-built mirror signals nothing"
        );
    }

    /// A favorite toggled on another client lands only through `sync_favorites`: the server's
    /// list wins in both directions, and a failed fetch leaves the flags alone.
    #[tokio::test]
    async fn sync_favorites_mirrors_the_server_list_and_keeps_flags_on_failure() {
        let server = MockServer::start().await;
        let client = JellyfinClient::from_token(&server.base_url, identity(), "tok");
        let was_favorite = "22222222-2222-2222-2222-222222222222";
        let now_favorite = "33333333-3333-3333-3333-333333333333";
        let mut old = movie_json(was_favorite, "Old Favorite");
        old["UserData"] = json!({ "IsFavorite": true });
        server.route(
            "/UserItems/Resume",
            &[],
            json!({ "Items": [old, movie_json(now_favorite, "New Favorite")] }),
        );
        let mirror = TestMirror::new(client);
        sync_resume(&mirror.state).await;
        mirror.state.writer.barrier().await;
        let favorites = |state: &MirrorState| -> Vec<String> {
            state
                .read_pool
                .acquire()
                .prepare("SELECT id FROM items WHERE is_favorite = 1")
                .expect("prepare")
                .query_map([], |r| r.get(0))
                .expect("query")
                .collect::<rusqlite::Result<_>>()
                .expect("rows")
        };

        // No `/Items` route yet: the fetch fails and the old flag must survive.
        sync_favorites(&mirror.state).await;
        mirror.state.writer.barrier().await;
        assert_eq!(favorites(&mirror.state), vec![was_favorite.to_string()]);

        server.route(
            "/Items",
            &[("isFavorite", "true")],
            json!({ "Items": [{ "Id": now_favorite }], "TotalRecordCount": 1 }),
        );
        sync_favorites(&mirror.state).await;
        mirror.state.writer.barrier().await;
        assert_eq!(favorites(&mirror.state), vec![now_favorite.to_string()]);
    }

    /// A warm-launch startup pass run twice against byte-identical server fixtures must emit
    /// zero change events the second time: `sync_views`'s `ViewsChanged` gate
    /// (`writer::apply_replace_views`), the DTO no-op skip (`writer::apply_upsert_items_scoped`),
    /// and `refresh_next_up`'s `next_up_ids` gate (`writer::apply_set_next_up_ids`) must all hold
    /// together, not just individually.
    #[tokio::test]
    async fn identical_warm_launch_pass_emits_no_changes_the_second_time() {
        let server = MockServer::start().await;
        let client = JellyfinClient::from_token(&server.base_url, identity(), "tok");
        let view_id = "11111111-1111-1111-1111-111111111111";
        let movie_id = "22222222-2222-2222-2222-222222222222";
        let episode_id = "33333333-3333-3333-3333-333333333333";

        server.route(
            "/UserViews",
            &[],
            json!({ "Items": [{
                "Id": view_id, "Name": "Movies", "CollectionType": "movies",
                "Type": "CollectionFolder"
            }] }),
        );
        server.route(
            "/UserItems/Resume",
            &[],
            json!({ "Items": [movie_json(movie_id, "A Movie")] }),
        );
        server.route(
            "/Shows/NextUp",
            &[],
            json!({ "Items": [movie_json(episode_id, "An Episode")] }),
        );

        let mirror = TestMirror::new(client);
        let mut changes = mirror.state.changes_tx.subscribe();

        sync_views(&mirror.state).await;
        sync_resume(&mirror.state).await;
        refresh_next_up(&mirror.state).await;
        mirror.state.writer.barrier().await;
        assert!(
            changes.try_recv().is_ok(),
            "the first pass against a fresh mirror must report real changes"
        );
        while changes.try_recv().is_ok() {}

        // Second pass: same routes, same bytes, nothing changed server-side.
        sync_views(&mirror.state).await;
        sync_resume(&mirror.state).await;
        refresh_next_up(&mirror.state).await;
        mirror.state.writer.barrier().await;

        assert!(
            changes.try_recv().is_err(),
            "an identical warm-launch pass must emit no change events the second time"
        );
    }

    /// `delta_pass`'s Next-Up refresh used to be gated on `total_seen`, the server's raw
    /// `minDateLastSaved` match count -- not on whether anything the no-op comparison lets
    /// through actually changed. A metadata-only re-save (or a delta tick's cursor boundary
    /// re-matching an already-stored, byte-identical item) satisfies that match count with
    /// nothing a user would see move, yet used to re-fetch (and risk re-broadcasting) Next Up
    /// for no visible reason -- the residual source of a startup pass's spurious empty
    /// `Refresh`. `delta_pass` now gates on `upsert_cross_library_items`'s own report of real
    /// change (see `WriterHandle::upsert_items_scoped_reporting`).
    #[tokio::test]
    async fn delta_sync_no_op_match_does_not_re_fetch_next_up() {
        let server = MockServer::start().await;
        let client = JellyfinClient::from_token(&server.base_url, identity(), "tok");
        let movie_id = "88888888-8888-8888-8888-888888888888";

        // No views: reconcile/breadth are no-ops, so delta_pass is the only `/Items` caller.
        server.route("/UserViews", &[], json!({ "Items": [] }));
        server.route("/Shows/NextUp", &[], json!({ "Items": [] }));
        server.route(
            "/UserItems/Resume",
            &[],
            json!({ "Items": [movie_json(movie_id, "A Movie")] }),
        );

        let mirror = TestMirror::new(client);
        sync_views(&mirror.state).await;
        sync_resume(&mirror.state).await;
        refresh_next_up(&mirror.state).await;
        mirror.state.writer.barrier().await;
        // No cursor yet: adopts "now" and returns without an `/Items` call.
        delta_sync(&mirror.state).await;
        mirror.state.writer.barrier().await;
        assert_eq!(
            server.request_count("/Shows/NextUp"),
            1,
            "sanity: only the startup pass's own refresh_next_up call so far"
        );

        // A later delta tick's `minDateLastSaved` query re-matches the same, byte-identical
        // item -- the no-op comparison discards it, so nothing actually changed.
        server.route(
            "/Items",
            &[],
            json!({ "Items": [movie_json(movie_id, "A Movie")], "TotalRecordCount": 1 }),
        );
        let mut changes = mirror.state.changes_tx.subscribe();
        delta_sync(&mirror.state).await;
        mirror.state.writer.barrier().await;

        assert_eq!(
            server.request_count("/Shows/NextUp"),
            1,
            "a delta match the no-op comparison discarded must not re-fetch Next Up"
        );
        assert!(
            changes.try_recv().is_err(),
            "a delta pass that changed nothing must emit no change events"
        );
    }

    /// A changed `played`/position in an otherwise-identical Resume fixture must still be
    /// reported -- the no-op skip must never mask a genuine watch-state change. The "before"
    /// row is seeded directly through the writer (the mock server only answers one query
    /// shape per path -- see `mock_server::MockServer::route`), so `sync_resume`'s one real
    /// HTTP fetch is exactly the moved-position response.
    #[tokio::test]
    async fn resume_position_change_still_emits_a_change() {
        let server = MockServer::start().await;
        let client = JellyfinClient::from_token(&server.base_url, identity(), "tok");
        let movie_id = "77777777-7777-7777-7777-777777777777";

        let mirror = TestMirror::new(client);
        let old_item: BaseItemDto = serde_json::from_value(json!({
            "Id": movie_id, "Name": "A Movie", "Type": "Movie",
            "UserData": { "PlaybackPositionTicks": 1000, "Played": false }
        }))
        .expect("deserialize seed item");
        mirror.state.writer.upsert_items(vec![old_item]).await;
        mirror.state.writer.barrier().await;

        let mut changes = mirror.state.changes_tx.subscribe();

        server.route(
            "/UserItems/Resume",
            &[],
            json!({ "Items": [{
                "Id": movie_id, "Name": "A Movie", "Type": "Movie",
                "UserData": { "PlaybackPositionTicks": 5000, "Played": false }
            }] }),
        );
        sync_resume(&mirror.state).await;
        mirror.state.writer.barrier().await;

        match changes.try_recv() {
            Ok(MirrorChange::Upserted { ids, .. }) => assert_eq!(ids, vec![movie_id.to_string()]),
            other => panic!("expected an Upserted change for the moved position, got {other:?}"),
        }
    }

    /// An item aging out of Next Up (no longer returned, with no other item's DTO changing)
    /// must still surface as a change: `query::next_up` is driven entirely by `meta.next_up_ids`,
    /// which no per-item upsert event would otherwise reflect. `resume()` has no equivalent gap
    /// -- unlike Next Up it's derived straight from indexed `played`/position columns, so an
    /// item leaving Resume is inherently a DTO change the no-op comparison already catches.
    /// The "before" list is seeded directly through the writer (the mock server only answers
    /// one query shape per path), so `refresh_next_up`'s one real HTTP fetch is exactly the
    /// shrunk response.
    #[tokio::test]
    async fn next_up_item_aging_out_still_emits_a_change() {
        let server = MockServer::start().await;
        let client = JellyfinClient::from_token(&server.base_url, identity(), "tok");
        let staying_id = "55555555-5555-5555-5555-555555555555";
        let aging_out_id = "66666666-6666-6666-6666-666666666666";

        let mirror = TestMirror::new(client);
        mirror
            .state
            .writer
            .set_next_up_ids(vec![staying_id.to_string(), aging_out_id.to_string()])
            .await;
        mirror.state.writer.barrier().await;

        let mut changes = mirror.state.changes_tx.subscribe();

        // `aging_out_id` simply no longer qualifies server-side (e.g. past
        // `nextUpDateCutoff`) -- neither item's own data changed.
        server.route(
            "/Shows/NextUp",
            &[],
            json!({ "Items": [movie_json(staying_id, "Staying Episode")] }),
        );
        refresh_next_up(&mirror.state).await;
        mirror.state.writer.barrier().await;

        let mut saw_refresh = false;
        while let Ok(change) = changes.try_recv() {
            if matches!(change, MirrorChange::Refresh) {
                saw_refresh = true;
            }
        }
        assert!(
            saw_refresh,
            "an item aging out of Next Up must still surface as a change even though no \
             item's own DTO differed"
        );
    }

    /// Root cause of a phantom warm-launch "change": a movie the breadth walk already
    /// flattened onto its view, then re-fetched by `sync_resume` with the server's raw,
    /// un-flattened folder `ParentId` (same fixture shape as
    /// `writer::tests::resume_refresh_does_not_evict_a_movie_from_its_library_grid`), must not
    /// be reported as changed: the stored DTO blob has to resolve the same authoritative-parent
    /// rule `UPSERT_ITEM_SQL`'s CASE applies to the column (see `writer::resolve_parent_id`),
    /// not just compare raw bytes.
    #[tokio::test]
    async fn resume_raw_parent_id_after_breadth_flatten_is_not_a_change() {
        let server = MockServer::start().await;
        let client = JellyfinClient::from_token(&server.base_url, identity(), "tok");
        let view_id = "11111111-1111-1111-1111-111111111111";
        let folder_id = "44444444-4444-4444-4444-444444444444";
        let movie_id = "22222222-2222-2222-2222-222222222222";

        let mirror = TestMirror::new(client);
        mirror
            .state
            .writer
            .replace_views(vec![crate::ViewRow {
                id: view_id.to_string(),
                name: "Movies".to_string(),
                collection_type: "movies".to_string(),
                item_type: "CollectionFolder".to_string(),
            }])
            .await;

        server.route(
            "/Items",
            &[("parentId", view_id), ("startIndex", "0")],
            json!({ "Items": [movie_json(movie_id, "A Movie")], "TotalRecordCount": 1 }),
        );
        assert!(sync_library_breadth(&mirror.weak(), view_id, "movies").await);
        mirror.state.writer.barrier().await;

        let mut changes = mirror.state.changes_tx.subscribe();

        server.route(
            "/UserItems/Resume",
            &[],
            json!({ "Items": [{
                "Id": movie_id, "Name": "A Movie", "Type": "Movie", "ParentId": folder_id
            }] }),
        );
        sync_resume(&mirror.state).await;
        mirror.state.writer.barrier().await;

        assert!(
            changes.try_recv().is_err(),
            "the breadth walk's flattened parent id must survive an un-flattened Resume \
             upsert with no reported change"
        );
    }

    /// Long-lived-session convergence: the `NeedsReconcile` bus event (WS
    /// reconnect) must also refresh Resume, not just delta/reconcile --
    /// otherwise a session that never restarts (so `initial_sync`/the
    /// warm-launch pass never run again) could carry a stale Resume shelf
    /// for its entire lifetime.
    #[tokio::test]
    async fn needs_reconcile_also_refreshes_resume() {
        let server = MockServer::start().await;
        let client = JellyfinClient::from_token(&server.base_url, identity(), "tok");
        let resume_id = "dddddddd-dddd-dddd-dddd-dddddddddddd";

        server.route("/UserViews", &[], json!({ "Items": [] }));
        server.route(
            "/UserItems/Resume",
            &[],
            json!({ "Items": [movie_json(resume_id, "Reconciled Resume Item")] }),
        );

        let mirror = TestMirror::new(client);
        assert_eq!(mirror.item_count().await, 0);

        apply_bus_event(&mirror.state, BusEvent::NeedsReconcile).await;

        assert_eq!(
            mirror.item_name(resume_id).await.as_deref(),
            Some("Reconciled Resume Item"),
            "NeedsReconcile must call sync_resume so a long-lived session's Resume shelf \
             converges without waiting for a full restart"
        );
    }

    /// (a): the first post-launch `NeedsReconcile` must not duplicate an in-progress startup
    /// pass's own delta+reconcile -- only `sync_resume` runs.
    #[tokio::test]
    async fn first_connect_during_startup_runs_one_reconcile_total() {
        let server = MockServer::start().await;
        let client = JellyfinClient::from_token(&server.base_url, identity(), "tok");
        server.route("/UserItems/Resume", &[], json!({ "Items": [] }));
        // No /UserViews route staged: reconcile_all's fetch would 404 (and be logged, not
        // panic), but a hit still proves the skip failed via the assertion below.

        let mirror = TestMirror::new(client);
        mirror
            .state
            .startup_pass_active
            .store(true, Ordering::Release);

        apply_bus_event(&mirror.state, BusEvent::NeedsReconcile).await;

        assert_eq!(
            server.request_count("/UserItems/Resume"),
            1,
            "sync_resume must still run on the first connect"
        );
        assert_eq!(
            server.request_count("/UserViews"),
            0,
            "delta+reconcile must be skipped: the startup pass already covers the first connect"
        );
        assert!(mirror.state.first_reconcile_handled.load(Ordering::Acquire));
        assert!(
            next_up_was_requested(&mirror.state).await,
            "Next Up is still asked for: an offline window's UserDataChanged is lost"
        );
    }

    /// Whether a Next Up request is pending, consuming it.
    async fn next_up_was_requested(state: &MirrorState) -> bool {
        tokio::time::timeout(
            std::time::Duration::from_millis(50),
            state.next_up_requested.notified(),
        )
        .await
        .is_ok()
    }

    /// (a): a second connect arriving while the startup pass is still active is not exempt --
    /// only the very first one is.
    #[tokio::test]
    async fn second_connect_during_startup_runs_the_handler_fully() {
        let server = MockServer::start().await;
        let client = JellyfinClient::from_token(&server.base_url, identity(), "tok");
        server.route("/UserItems/Resume", &[], json!({ "Items": [] }));
        server.route("/UserViews", &[], json!({ "Items": [] }));

        let mirror = TestMirror::new(client);
        mirror
            .state
            .startup_pass_active
            .store(true, Ordering::Release);

        apply_bus_event(&mirror.state, BusEvent::NeedsReconcile).await; // first: skipped
        assert!(next_up_was_requested(&mirror.state).await);
        apply_bus_event(&mirror.state, BusEvent::NeedsReconcile).await; // second: full
        assert!(
            next_up_was_requested(&mirror.state).await,
            "a full reconnect asks for Next Up too"
        );

        assert_eq!(
            server.request_count("/UserViews"),
            1,
            "the second connect's reconcile must run in full even while the startup pass is \
             still active"
        );
    }

    /// (a): once the startup pass has completed, even the first `NeedsReconcile` a mirror
    /// sees must run the handler fully -- the exemption only covers an active pass.
    #[tokio::test]
    async fn needs_reconcile_runs_fully_once_startup_pass_has_completed() {
        let server = MockServer::start().await;
        let client = JellyfinClient::from_token(&server.base_url, identity(), "tok");
        server.route("/UserItems/Resume", &[], json!({ "Items": [] }));
        server.route("/UserViews", &[], json!({ "Items": [] }));

        let mirror = TestMirror::new(client);
        // Simulates a startup pass that ran and finished before this connect landed.
        mirror
            .state
            .startup_pass_active
            .store(true, Ordering::Release);
        mirror
            .state
            .startup_pass_active
            .store(false, Ordering::Release);

        apply_bus_event(&mirror.state, BusEvent::NeedsReconcile).await;

        assert_eq!(
            server.request_count("/UserViews"),
            1,
            "delta+reconcile must run in full once the startup pass is no longer active"
        );
    }

    /// Drives `event_bus::supervise`'s reconnect sequence (`Connected` then `NeedsReconcile`)
    /// through `bus_listener` end-to-end, asserting `reconcile_all` heals a mismatch
    /// immediately without waiting on `reconcile_timer`.
    #[tokio::test]
    async fn bus_listener_reconciles_immediately_on_reconnect_signal() {
        let server = MockServer::start().await;
        let client = JellyfinClient::from_token(&server.base_url, identity(), "tok");
        let view_id = "11111111-1111-1111-1111-111111111111";

        server.route(
            "/UserViews",
            &[],
            json!({ "Items": [{ "Id": view_id, "Name": "Movies", "CollectionType": "movies" }] }),
        );
        server.route(
            "/Items",
            &[("parentId", view_id), ("limit", "1")],
            json!({ "Items": [], "TotalRecordCount": 1 }),
        );
        route_sweep_enumeration(&server, view_id, &["aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"]);
        route_by_ids(
            &server,
            &[movie_json("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "A")],
        );

        let mirror = TestMirror::new(client);
        mirror
            .state
            .writer
            .upsert_views(vec![crate::ViewRow {
                id: view_id.to_string(),
                name: "Movies".to_string(),
                collection_type: "movies".to_string(),
                item_type: "CollectionFolder".to_string(),
            }])
            .await;
        assert_eq!(mirror.item_count().await, 0);

        let (bus_tx, bus_rx) = broadcast::channel::<BusEvent>(16);
        tokio::spawn(bus_listener(mirror.weak(), bus_rx));

        bus_tx.send(BusEvent::Connected).expect("send Connected");
        bus_tx
            .send(BusEvent::NeedsReconcile)
            .expect("send NeedsReconcile");

        let deadline = tokio::time::Instant::now() + std::time::Duration::from_secs(5);
        loop {
            if mirror.item_count().await == 1 {
                break;
            }
            assert!(
                tokio::time::Instant::now() < deadline,
                "the reconnect's NeedsReconcile signal must trigger reconcile_all immediately"
            );
            tokio::time::sleep(std::time::Duration::from_millis(20)).await;
        }
    }

    /// `resolve_library_ids_via_ancestors` must correctly stamp a brand-new nested item (an
    /// Episode under an already-synced Series) with its series ancestor's own `library_id`,
    /// end-to-end through the WS `LibraryChanged` path.
    #[tokio::test]
    async fn library_changed_stamps_correct_library_id_for_a_nested_new_episode() {
        let server = MockServer::start().await;
        let client = JellyfinClient::from_token(&server.base_url, identity(), "tok");
        let view_id = "11111111-1111-1111-1111-111111111111";
        let series_id = "22222222-2222-2222-2222-222222222222";
        let new_episode_id = "33333333-3333-3333-3333-333333333333";

        server.route(
            "/Items",
            &[("ids", new_episode_id)],
            json!({ "Items": [{
                "Id": new_episode_id, "Name": "S01E01", "Type": "Episode",
                "SeriesId": series_id
            }] }),
        );
        server.route("/Shows/NextUp", &[], json!({ "Items": [] }));

        let mirror = TestMirror::new(client);
        // Seed the series row already scoped to view_id, as a real breadth sync would.
        let series_dto: BaseItemDto =
            serde_json::from_value(json!({ "Id": series_id, "Name": "Show", "Type": "Series" }))
                .expect("dto");
        mirror
            .state
            .writer
            .upsert_items_scoped(vec![series_dto], Some(view_id.to_string()))
            .await;
        // The ancestor-chain lookup reads straight from the read pool, so the series row
        // must be committed first or this races.
        mirror.state.writer.barrier().await;

        apply_server_event(
            &mirror.state,
            ServerEvent::LibraryChanged {
                added: vec![new_episode_id.to_string()],
                updated: vec![],
                removed: vec![],
            },
        )
        .await;
        mirror.state.writer.barrier().await;

        let conn = mirror.state.read_pool.acquire();
        let library_id: Option<String> = conn
            .query_row(
                "SELECT library_id FROM items WHERE id = ?1",
                [new_episode_id],
                |r| r.get(0),
            )
            .expect("row");
        assert_eq!(
            library_id.as_deref(),
            Some(view_id),
            "a new nested episode resolved via its series ancestor must be stamped with the \
             series' own library_id, not left NULL/unresolved"
        );
    }

    /// A breadth walk must hold between pages while playback is active, and resume once it
    /// stops. Real time, not `start_paused`: live loopback HTTP races reqwest's own timers.
    #[tokio::test]
    async fn breadth_sync_yields_while_playback_is_active() {
        let server = MockServer::start().await;
        let client = JellyfinClient::from_token(&server.base_url, identity(), "tok");
        let view_id = "22222222-2222-2222-2222-222222222222";

        server.route(
            "/Items",
            &[("parentId", view_id), ("startIndex", "0")],
            json!({
                "Items": [movie_json("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", "B")],
                "TotalRecordCount": 1
            }),
        );

        let mirror = TestMirror::new(client);
        mirror.state.playback_active.store(true, Ordering::Release);

        let weak = mirror.weak();
        let view_id_owned = view_id.to_string();
        let handle =
            tokio::spawn(
                async move { sync_library_breadth(&weak, &view_id_owned, "movies").await },
            );

        // Several yield-poll cycles: with playback active it must issue no page request.
        tokio::time::sleep(PLAYBACK_YIELD_POLL * 4).await;
        assert_eq!(
            server.request_count("/Items"),
            0,
            "no pages may be fetched while playback is active"
        );

        // Playback stops; the held walk must resume and complete.
        mirror.state.playback_active.store(false, Ordering::Release);
        assert!(handle.await.expect("join"), "breadth sync must complete");
        assert!(
            server.request_count("/Items") >= 1,
            "the walk must resume where it left off"
        );
        mirror.state.writer.barrier().await;
        let conn = mirror.state.read_pool.acquire();
        let count: i64 = conn
            .query_row("SELECT COUNT(*) FROM items", [], |r| r.get(0))
            .expect("count");
        assert_eq!(count, 1, "the resumed walk must commit its page");
    }

    /// `sync_activity()` must transition Idle -> Syncing -> Idle around a breadth sync.
    #[tokio::test]
    async fn breadth_sync_reports_syncing_then_idle_activity() {
        use std::time::Duration;

        let server = MockServer::start().await;
        let client = JellyfinClient::from_token(&server.base_url, identity(), "tok");
        let view_id = "11111111-1111-1111-1111-111111111111";

        server.route_delayed(
            "/Items",
            &[("parentId", view_id), ("startIndex", "0")],
            json!({
                "Items": [movie_json("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "A")],
                "TotalRecordCount": 1
            }),
            Duration::from_millis(150),
        );

        let mirror = TestMirror::new(client);
        let mut activity_rx = mirror.state.sync_activity.subscribe();
        assert_eq!(*activity_rx.borrow(), SyncActivity::Idle);

        let weak = mirror.weak();
        let view_id_owned = view_id.to_string();
        let handle =
            tokio::spawn(
                async move { sync_library_breadth(&weak, &view_id_owned, "movies").await },
            );

        // The page fetch is slow; wait for the transition rather than racing a fixed sleep.
        activity_rx
            .changed()
            .await
            .expect("activity changed to Syncing");
        assert_eq!(
            *activity_rx.borrow(),
            SyncActivity::Syncing {
                library_name_or_id: view_id.to_string(),
                pages_done: 0,
                items_done: 0,
                // First page still in flight: the denominator arrives with the first response.
                total_items: None,
            }
        );

        assert!(
            handle.await.expect("breadth sync task"),
            "breadth sync must complete normally"
        );

        activity_rx
            .changed()
            .await
            .expect("activity changed back to Idle");
        assert_eq!(*activity_rx.borrow(), SyncActivity::Idle);
    }

    /// Reproduces the whole chain end-to-end: a breadth sync flattens a movie onto its view,
    /// then `refresh_resume` upserts it with the server's raw folder `ParentId`;
    /// `children(view_id)` must still find it (`UPSERT_ITEM_SQL`'s `CASE` guard).
    #[tokio::test]
    async fn resume_refresh_does_not_evict_a_movie_from_its_library_grid() {
        let server = MockServer::start().await;
        let client = JellyfinClient::from_token(&server.base_url, identity(), "tok");
        let view_id = "11111111-1111-1111-1111-111111111111";
        let folder_id = "44444444-4444-4444-4444-444444444444";
        let movie_id = "22222222-2222-2222-2222-222222222222";

        let mirror = TestMirror::new(client);
        mirror
            .state
            .writer
            .replace_views(vec![crate::ViewRow {
                id: view_id.to_string(),
                name: "Movies".to_string(),
                collection_type: "movies".to_string(),
                item_type: "CollectionFolder".to_string(),
            }])
            .await;

        server.route(
            "/Items",
            &[("parentId", view_id), ("startIndex", "0")],
            json!({
                "Items": [movie_json(movie_id, "A Movie")],
                "TotalRecordCount": 1
            }),
        );
        assert!(sync_library_breadth(&mirror.weak(), view_id, "movies").await);
        mirror.state.writer.barrier().await;

        let conn = mirror.state.read_pool.acquire();
        assert_eq!(
            crate::query::children(&conn, view_id, crate::Sort::NameAsc, 0, 50).len(),
            1,
            "breadth sync must flatten the movie onto its view"
        );
        drop(conn);

        // The server's raw Resume response: ParentId is the physical folder, not the view.
        server.route(
            "/UserItems/Resume",
            &[],
            json!({ "Items": [{ "Id": movie_id, "Name": "A Movie", "Type": "Movie", "ParentId": folder_id }] }),
        );
        sync_resume(&mirror.state).await;
        mirror.state.writer.barrier().await;

        let conn = mirror.state.read_pool.acquire();
        assert_eq!(
            crate::query::children(&conn, view_id, crate::Sort::NameAsc, 0, 50).len(),
            1,
            "refresh_resume's raw folder ParentId must not evict the movie from its library grid"
        );
    }

    // --- Incremental delta sync -------------------------------------------

    const DELTA_ITEM_A: &str = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    const DELTA_ITEM_B: &str = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

    /// The query window always reaches `DELTA_OVERLAP_SLACK` further back than the stored
    /// cursor; a value this module didn't write is rejected rather than guessed at.
    #[test]
    fn delta_query_since_applies_the_overlap_slack() {
        assert_eq!(
            delta_query_since("2026-08-01T00:00:00Z").as_deref(),
            Some("2026-07-31T23:55:00Z")
        );
        // Normalized to UTC, not left in the offset it arrived in.
        assert_eq!(
            delta_query_since("2026-08-01T02:00:00+02:00").as_deref(),
            Some("2026-07-31T23:55:00Z")
        );
        assert_eq!(delta_query_since("not a timestamp"), None);
        assert_eq!(delta_query_since(""), None);
    }

    // --- Next Up cutoff formatting (Settings > Next Up) --------------------

    /// `next_up_date_cutoff(days)` must land within a few seconds of exactly
    /// "now minus `days` days" (not asserting an exact string -- the fn
    /// reads the real clock -- but the arithmetic and RFC3339/UTC shape are
    /// pinned) and must produce a strictly older instant for a larger `days`.
    #[test]
    fn next_up_date_cutoff_subtracts_days_from_now() {
        let zero = next_up_date_cutoff(0);
        let now = chrono::Utc::now();
        let parsed = chrono::DateTime::parse_from_rfc3339(&zero)
            .expect("valid RFC3339")
            .with_timezone(&chrono::Utc);
        assert!(
            (now - parsed).num_seconds().abs() < 5,
            "0-day cutoff should be ~now, got {zero}"
        );
        assert!(zero.ends_with('Z'), "must be UTC ('Z' suffix): {zero}");

        let fourteen = next_up_date_cutoff(14);
        let parsed_14 = chrono::DateTime::parse_from_rfc3339(&fourteen)
            .expect("valid RFC3339")
            .with_timezone(&chrono::Utc);
        let delta_days = (parsed - parsed_14).num_seconds() as f64 / 86_400.0;
        assert!(
            (delta_days - 14.0).abs() < 0.01,
            "expected ~14 days between the 0-day and 14-day cutoffs, got {delta_days}"
        );
    }

    /// One small query picks up both a brand-new item and an in-place update neither
    /// reconcile probe can see, and the cursor moves forward.
    #[tokio::test]
    async fn delta_sync_upserts_new_and_updated_items_and_advances_the_cursor() {
        let server = MockServer::start().await;
        let client = JellyfinClient::from_token(&server.base_url, identity(), "tok");
        let mirror = TestMirror::new(client);

        // An already-mirrored item, carrying the stale name.
        let existing: BaseItemDto =
            serde_json::from_value(movie_json(DELTA_ITEM_A, "Existing Movie (720p)")).expect("dto");
        mirror.state.writer.upsert_items(vec![existing]).await;
        mirror
            .state
            .writer
            .set_meta(LAST_DELTA_SYNC_KEY, "2026-08-01T00:00:00Z".to_string())
            .await;

        server.route("/Shows/NextUp", &[], json!({ "Items": [] }));
        // Keyed on the expected window: cursor minus the 5-minute overlap slack.
        server.route(
            "/Items",
            &[("minDateLastSaved", "2026-07-31T23:55:00Z")],
            json!({
                "Items": [
                    movie_json(DELTA_ITEM_A, "Existing Movie (1080p)"),
                    movie_json(DELTA_ITEM_B, "Brand New Movie"),
                ],
                "TotalRecordCount": 2
            }),
        );

        delta_sync(&mirror.state).await;

        assert_eq!(mirror.item_count().await, 2);
        assert_eq!(
            mirror.item_name(DELTA_ITEM_A).await.as_deref(),
            Some("Existing Movie (1080p)"),
            "an in-place update must overwrite the mirrored metadata"
        );
        assert_eq!(
            mirror.item_name(DELTA_ITEM_B).await.as_deref(),
            Some("Brand New Movie")
        );

        let cursor = mirror
            .meta(LAST_DELTA_SYNC_KEY)
            .await
            .expect("cursor is set after a successful pass");
        assert!(
            cursor.as_str() > "2026-08-01T00:00:00Z",
            "cursor should have advanced past the seeded value, got {cursor}"
        );
    }

    /// A delta page spanning several libraries must go through per-item library resolution,
    /// not `sync_library_breadth`'s "stamp the whole page" shortcut.
    #[tokio::test]
    async fn delta_sync_stamps_each_item_with_its_own_resolved_library() {
        let server = MockServer::start().await;
        let client = JellyfinClient::from_token(&server.base_url, identity(), "tok");
        let mirror = TestMirror::new(client);

        let tv_view = "11111111-1111-1111-1111-111111111111";
        let series_id = "22222222-2222-2222-2222-222222222222";
        let episode_id = "33333333-3333-3333-3333-333333333333";

        // The Series is already mirrored and stamped with the TV library.
        let series: BaseItemDto =
            serde_json::from_value(json!({ "Id": series_id, "Name": "Show", "Type": "Series" }))
                .expect("dto");
        mirror
            .state
            .writer
            .upsert_items_scoped(vec![series], Some(tv_view.to_string()))
            .await;
        mirror
            .state
            .writer
            .set_meta(LAST_DELTA_SYNC_KEY, "2026-08-01T00:00:00Z".to_string())
            .await;

        server.route("/Shows/NextUp", &[], json!({ "Items": [] }));
        server.route(
            "/Items",
            &[("minDateLastSaved", "2026-07-31T23:55:00Z")],
            json!({
                "Items": [{
                    "Id": episode_id, "Name": "New Episode", "Type": "Episode",
                    "SeriesId": series_id
                }],
                "TotalRecordCount": 1
            }),
        );

        delta_sync(&mirror.state).await;

        mirror.state.writer.barrier().await;
        let conn = mirror.state.read_pool.acquire();
        let library_id: Option<String> = conn
            .query_row(
                "SELECT library_id FROM items WHERE id = ?1",
                [episode_id],
                |r| r.get(0),
            )
            .expect("episode row");
        assert_eq!(
            library_id.as_deref(),
            Some(tv_view),
            "the new episode must inherit its series' library, so Latest/Home \
             for that library picks it up"
        );
    }

    /// No cursor yet: the pass must not query (would pull the whole library) but must adopt
    /// the current instant, or delta sync would be a permanent no-op for an upgrading user.
    #[tokio::test]
    async fn delta_sync_with_no_cursor_does_not_query_but_bootstraps_one() {
        let server = MockServer::start().await;
        let client = JellyfinClient::from_token(&server.base_url, identity(), "tok");
        let mirror = TestMirror::new(client);

        assert_eq!(mirror.meta(LAST_DELTA_SYNC_KEY).await, None);

        delta_sync(&mirror.state).await;

        assert_eq!(
            server.request_count("/Items"),
            0,
            "a cursorless delta must not issue any /Items query"
        );
        assert!(
            mirror.meta(LAST_DELTA_SYNC_KEY).await.is_some(),
            "the pass must adopt a cursor so the next one can run"
        );
    }

    /// A pass whose second page fails has banked nothing, so the cursor must stay put and
    /// the next pass redoes the whole window.
    #[tokio::test]
    async fn delta_sync_leaves_the_cursor_alone_when_a_page_fails() {
        let server = MockServer::start().await;
        let client = JellyfinClient::from_token(&server.base_url, identity(), "tok");
        let mirror = TestMirror::new(client);

        mirror
            .state
            .writer
            .set_meta(LAST_DELTA_SYNC_KEY, "2026-08-01T00:00:00Z".to_string())
            .await;
        server.route("/Shows/NextUp", &[], json!({ "Items": [] }));
        // Page 1 lands and claims there are more; page 2 has no route, so the pass aborts.
        server.route(
            "/Items",
            &[
                ("minDateLastSaved", "2026-07-31T23:55:00Z"),
                ("startIndex", "0"),
            ],
            json!({
                "Items": [movie_json(DELTA_ITEM_A, "Page One")],
                "TotalRecordCount": 2
            }),
        );

        delta_sync(&mirror.state).await;

        // What did land stays landed, but the cursor itself must not have moved.
        assert_eq!(
            mirror.item_name(DELTA_ITEM_A).await.as_deref(),
            Some("Page One")
        );
        assert_eq!(
            mirror.meta(LAST_DELTA_SYNC_KEY).await.as_deref(),
            Some("2026-08-01T00:00:00Z"),
            "a partially-failed pass must not bank progress"
        );
    }

    /// The cursor is the instant the pass started, never "now once it finished", or a
    /// change saved mid-pass could land in an already-walked page and be lost forever.
    #[tokio::test]
    async fn delta_sync_cursor_is_the_pass_start_not_its_completion() {
        use std::time::Duration;

        let server = MockServer::start().await;
        let client = JellyfinClient::from_token(&server.base_url, identity(), "tok");
        let mirror = TestMirror::new(client);

        mirror
            .state
            .writer
            .set_meta(LAST_DELTA_SYNC_KEY, "2026-08-01T00:00:00Z".to_string())
            .await;
        server.route("/Shows/NextUp", &[], json!({ "Items": [] }));
        server.route_delayed(
            "/Items",
            &[("minDateLastSaved", "2026-07-31T23:55:00Z")],
            json!({
                "Items": [movie_json(DELTA_ITEM_A, "Slow Page")],
                "TotalRecordCount": 1
            }),
            Duration::from_millis(2100),
        );

        delta_sync(&mirror.state).await;
        let finished_at = now_rfc3339();

        let cursor = mirror
            .meta(LAST_DELTA_SYNC_KEY)
            .await
            .expect("cursor after a successful pass");
        assert!(
            cursor.as_str() < finished_at.as_str(),
            "cursor {cursor} must predate the pass's completion at {finished_at}"
        );
    }

    /// The overlap slack means consecutive passes deliberately re-fetch the same items;
    /// every write here is an idempotent upsert, so that must be a no-op.
    #[tokio::test]
    async fn delta_sync_is_idempotent_across_overlapping_windows() {
        let server = MockServer::start().await;
        let client = JellyfinClient::from_token(&server.base_url, identity(), "tok");
        let mirror = TestMirror::new(client);

        mirror
            .state
            .writer
            .set_meta(LAST_DELTA_SYNC_KEY, "2026-08-01T00:00:00Z".to_string())
            .await;
        server.route("/Shows/NextUp", &[], json!({ "Items": [] }));
        // Matches any window, so the second pass re-fetches the same payload like a real
        // overlap would.
        server.route(
            "/Items",
            &[("recursive", "true")],
            json!({
                "Items": [
                    movie_json(DELTA_ITEM_A, "Movie A"),
                    movie_json(DELTA_ITEM_B, "Movie B"),
                ],
                "TotalRecordCount": 2
            }),
        );

        delta_sync(&mirror.state).await;
        assert_eq!(mirror.item_count().await, 2);
        let after_first = mirror.meta(LAST_DELTA_SYNC_KEY).await;

        delta_sync(&mirror.state).await;

        assert!(
            server.request_count("/Items") >= 2,
            "sanity: the second pass must actually have re-queried"
        );
        assert_eq!(
            mirror.item_count().await,
            2,
            "re-applying an overlapping window must not duplicate rows"
        );
        assert_eq!(
            mirror.item_name(DELTA_ITEM_A).await.as_deref(),
            Some("Movie A")
        );
        assert!(mirror.meta(LAST_DELTA_SYNC_KEY).await >= after_first);
    }

    /// Initial sync owns first population and stamps the cursor itself, at the sync's start
    /// instant, not its completion.
    #[tokio::test]
    async fn initial_sync_stamps_the_delta_cursor_from_its_start_instant() {
        let server = MockServer::start().await;
        let client = JellyfinClient::from_token(&server.base_url, identity(), "tok");
        let view_id = "11111111-1111-1111-1111-111111111111";

        server.route(
            "/UserViews",
            &[],
            json!({ "Items": [{ "Id": view_id, "Name": "Movies", "CollectionType": "movies" }] }),
        );
        server.route("/UserItems/Resume", &[], json!({ "Items": [] }));
        server.route("/Shows/NextUp", &[], json!({ "Items": [] }));
        server.route(
            "/Items",
            &[("parentId", view_id)],
            json!({
                "Items": [movie_json(DELTA_ITEM_A, "Movie A")],
                "TotalRecordCount": 1
            }),
        );

        let mirror = TestMirror::new(client);
        let started_at = now_rfc3339();
        initial_sync(&mirror.weak()).await;
        let finished_at = now_rfc3339();

        let cursor = mirror
            .meta(LAST_DELTA_SYNC_KEY)
            .await
            .expect("initial sync must stamp the delta cursor");
        assert!(
            cursor.as_str() >= started_at.as_str() && cursor.as_str() <= finished_at.as_str(),
            "cursor {cursor} should sit in [{started_at}, {finished_at}]"
        );
    }

    /// A second call entered while a slow pass is running must defer (one rerun) rather
    /// than issue a second concurrent walk.
    #[tokio::test]
    async fn delta_sync_is_single_flight() {
        use std::time::Duration;

        let server = MockServer::start().await;
        let client = JellyfinClient::from_token(&server.base_url, identity(), "tok");
        let mirror = TestMirror::new(client);

        mirror
            .state
            .writer
            .set_meta(LAST_DELTA_SYNC_KEY, "2026-08-01T00:00:00Z".to_string())
            .await;
        server.route("/Shows/NextUp", &[], json!({ "Items": [] }));
        server.route_delayed(
            "/Items",
            &[("recursive", "true")],
            json!({
                "Items": [movie_json(DELTA_ITEM_A, "Movie A")],
                "TotalRecordCount": 1
            }),
            Duration::from_millis(300),
        );

        let state = mirror.state.clone();
        let first = tokio::spawn(async move { delta_sync(&state).await });
        tokio::time::sleep(Duration::from_millis(80)).await;
        assert!(
            mirror.state.delta_in_progress.load(Ordering::Acquire),
            "sanity: the first pass should still be in flight"
        );

        // Enters, finds the guard taken, defers.
        delta_sync(&mirror.state).await;
        assert!(
            mirror.state.delta_pending.load(Ordering::Acquire),
            "the deferred trigger must be recorded, not dropped"
        );

        first.await.expect("first delta pass");
        assert!(
            !mirror.state.delta_pending.load(Ordering::Acquire),
            "the running pass must consume the deferred trigger and rerun"
        );
        assert!(!mirror.state.delta_in_progress.load(Ordering::Acquire));
    }

    /// A timer tick landing mid-initial-sync must not fire a delta that competes with it,
    /// or bootstrap a cursor initial sync is about to stamp.
    #[tokio::test]
    async fn delta_sync_defers_to_an_in_progress_initial_sync() {
        let server = MockServer::start().await;
        let client = JellyfinClient::from_token(&server.base_url, identity(), "tok");
        let mirror = TestMirror::new(client);

        mirror
            .state
            .initial_sync_in_progress
            .store(true, Ordering::Release);

        delta_sync(&mirror.state).await;

        assert_eq!(server.request_count("/Items"), 0);
        assert_eq!(
            mirror.meta(LAST_DELTA_SYNC_KEY).await,
            None,
            "initial sync stamps the first cursor; delta must not race it"
        );
    }
}
