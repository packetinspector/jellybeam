//! The single writer: owns the read-write `Connection`, drains a command queue, and
//! broadcasts the change-feed in commit order (ordering rule -- every mutation
//! flows through here, so there's no interleaving hazard).

use std::collections::HashSet;

use rusqlite::{params, Connection, OptionalExtension};
use tokio::sync::{broadcast, mpsc, oneshot};

use jellyfin_api::models::{BaseItemDto, UserItemDataDto};

use crate::rows::{extract_columns, search_text, to_dto_bytes, SearchText};
use crate::{MirrorChange, ViewRow};

pub(crate) enum WriteCmd {
    #[cfg(test)]
    UpsertViews(Vec<ViewRow>),
    /// Replaces `/UserViews` as one authoritative snapshot and removes all item rows scoped
    /// to views no longer present in it.
    ReplaceViews(Vec<ViewRow>),
    UpsertItems {
        items: Vec<BaseItemDto>,
        /// The library every item in this batch belongs to, when the caller can attribute
        /// the whole batch up front (e.g. one view's breadth-sync page). `None`
        /// leaves each item's existing `library_id` untouched (`COALESCE`, see
        /// `apply_upsert_items_scoped`) -- used where a batch can't be cheaply attributed to
        /// one library (Resume/NextUp span every library; a WS `LibraryChanged` batch means
        /// "still unresolved" here, corrected by the next breadth sync or reconcile).
        library_id: Option<String>,
        /// `Some` for a caller that needs to know whether this batch actually changed
        /// anything (e.g. `delta_pass` deciding whether Next Up is worth re-fetching) rather
        /// than fire-and-forget; see [`WriterHandle::upsert_items_scoped_reporting`]. The vast
        /// majority of callers pass `None` and pay no round-trip.
        reply: Option<oneshot::Sender<Vec<String>>>,
    },
    RemoveItems(Vec<String>),
    /// Deletes every local row stamped with `library_id` whose id isn't in `keep_ids`, issued
    /// once after a library's full recursive breadth sync finishes upserting every page.
    /// `keep_ids` is that walk's complete current membership snapshot, so anything else
    /// under this library is provably gone from the server; see `apply_prune_library` for
    /// why no parent/child cascade is needed here, unlike `RemoveItems`.
    ///
    /// Relies on the writer's strict FIFO ordering: every `UpsertItems` this breadth sync
    /// sent lands before this command, so no not-yet-landed page ever looks pruned.
    ///
    /// `since_rowid`: a row whose `rowid` is past this mark was inserted no earlier than the
    /// enumeration that produced `keep_ids` began, so it may be a concurrent `LibraryChanged`
    /// insert the enumeration missed -- never pruned even when absent from `keep_ids` (docs/12;
    /// see `apply_prune_library`).
    PruneLibrary {
        library_id: String,
        keep_ids: Vec<String>,
        since_rowid: i64,
    },
    /// Re-applies `sync.rs::browse_root_item_type`'s parent flattening to rows already in the
    /// mirror: every `item_type` row stamped with `library_id` gets `parent_id = library_id`.
    /// Idempotent and cheap. Exists because delta sync historically skipped the flattening,
    /// leaving newly-added Series/Movies invisible to `children(view_id)`; run once at sync
    /// start so existing mirrors heal without a full resync.
    FlattenRootParents {
        library_id: String,
        item_type: String,
    },
    ApplyUserData(Vec<(String, UserItemDataDto)>),
    /// Optimistic local application of one item's watch state, used when this client just
    /// reported progress/stop/EOF but can't rely on a `UserDataChanged` WS event coming back
    /// (see `Mirror::apply_local_user_data`).
    ApplyLocalUserData {
        item_id: String,
        position_ticks: i64,
        played: Option<bool>,
    },
    /// Replaces the full membership list of one BoxSet in a single transaction
    /// (delete-then-reinsert), since the server is the sole source of truth for membership.
    SetCollectionMembers {
        collection_id: String,
        members: Vec<(String, i64)>,
    },
    SetMeta {
        key: &'static str,
        value: String,
    },
    /// Replaces `meta.next_up_ids` (see `query::next_up`), the ordered id list a Next Up
    /// shelf is entirely rendered from. Its own `WriteCmd` rather than a plain `SetMeta`
    /// because membership/order can move -- an episode ages out of the cutoff window, or a
    /// different show's episode outranks it -- with no individual item DTO changing, which
    /// would otherwise leave Home showing a stale shelf with no change event to react to.
    SetNextUpIds(Vec<String>),
    /// Makes `is_favorite` match the server's complete favorites id list: sets it on listed
    /// rows, clears it everywhere else. The only way a favorite changed on another client
    /// while this one was closed reaches the mirror (`delta_sync` ignores UserData saves).
    /// Only rows last written before `since` (the fetch's start, [`now_millis`]) change: any
    /// write that landed during the fetch -- a toggle, a server event, an item upsert -- is
    /// newer than the snapshot, which must not undo it.
    SetFavoriteIds {
        ids: Vec<String>,
        since: i64,
    },
    /// Builds [`crate::schema::FAVORITE_INDEX_SQL`] on a populated mirror; replies whether it
    /// succeeded.
    BuildFavoriteIndexes(oneshot::Sender<bool>),
    /// Fired after enqueueing a batch the caller wants to know completed; commands are
    /// processed strictly in order, so this just drains to that point.
    Barrier(oneshot::Sender<bool>),
}

#[derive(Clone)]
pub(crate) struct WriterHandle {
    tx: mpsc::Sender<WriteCmd>,
}

impl WriterHandle {
    pub(crate) fn new(tx: mpsc::Sender<WriteCmd>) -> Self {
        Self { tx }
    }

    #[cfg(test)]
    pub(crate) async fn upsert_views(&self, views: Vec<ViewRow>) {
        self.send(WriteCmd::UpsertViews(views)).await;
    }

    pub(crate) async fn replace_views(&self, views: Vec<ViewRow>) {
        self.send(WriteCmd::ReplaceViews(views)).await;
    }

    pub(crate) async fn upsert_items(&self, items: Vec<BaseItemDto>) {
        self.upsert_items_scoped(items, None).await;
    }

    /// See [`WriteCmd::UpsertItems`]'s `library_id` doc comment.
    pub(crate) async fn upsert_items_scoped(
        &self,
        items: Vec<BaseItemDto>,
        library_id: Option<String>,
    ) {
        if items.is_empty() {
            return;
        }
        self.send(WriteCmd::UpsertItems {
            items,
            library_id,
            reply: None,
        })
        .await;
    }

    /// Same write as [`Self::upsert_items_scoped`], but waits for it to land and returns the
    /// ids it actually changed (empty when every item in the batch was a no-op). For a caller
    /// deciding whether a *downstream* fetch is worth making at all -- e.g. `delta_sync`
    /// re-querying Next Up only when this batch really moved something, not merely because the
    /// server's `minDateLastSaved` query matched some rows (see `sync::delta_pass`).
    pub(crate) async fn upsert_items_scoped_reporting(
        &self,
        items: Vec<BaseItemDto>,
        library_id: Option<String>,
    ) -> Vec<String> {
        if items.is_empty() {
            return Vec::new();
        }
        let (tx, rx) = oneshot::channel();
        self.send(WriteCmd::UpsertItems {
            items,
            library_id,
            reply: Some(tx),
        })
        .await;
        rx.await.unwrap_or_default()
    }

    pub(crate) async fn remove_items(&self, ids: Vec<String>) {
        if ids.is_empty() {
            return;
        }
        self.send(WriteCmd::RemoveItems(ids)).await;
    }

    /// See [`WriteCmd::PruneLibrary`]. Not skipped when `keep_ids` is empty (unlike
    /// `remove_items`'s no-op): an empty `keep_ids` legitimately means the library is empty.
    pub(crate) async fn prune_library(
        &self,
        library_id: String,
        keep_ids: Vec<String>,
        since_rowid: i64,
    ) {
        self.send(WriteCmd::PruneLibrary {
            library_id,
            keep_ids,
            since_rowid,
        })
        .await;
    }

    /// See [`WriteCmd::FlattenRootParents`].
    pub(crate) async fn flatten_root_parents(&self, library_id: String, item_type: String) {
        self.send(WriteCmd::FlattenRootParents {
            library_id,
            item_type,
        })
        .await;
    }

    pub(crate) async fn apply_user_data(&self, updates: Vec<(String, UserItemDataDto)>) {
        if updates.is_empty() {
            return;
        }
        self.send(WriteCmd::ApplyUserData(updates)).await;
    }

    /// See `WriteCmd::ApplyLocalUserData`.
    pub(crate) async fn apply_local_user_data(
        &self,
        item_id: String,
        position_ticks: i64,
        played: Option<bool>,
    ) {
        self.send(WriteCmd::ApplyLocalUserData {
            item_id,
            position_ticks,
            played,
        })
        .await;
    }

    pub(crate) async fn set_collection_members(
        &self,
        collection_id: String,
        members: Vec<(String, i64)>,
    ) {
        self.send(WriteCmd::SetCollectionMembers {
            collection_id,
            members,
        })
        .await;
    }

    pub(crate) async fn set_meta(&self, key: &'static str, value: String) {
        self.send(WriteCmd::SetMeta { key, value }).await;
    }

    /// See [`WriteCmd::SetNextUpIds`].
    pub(crate) async fn set_next_up_ids(&self, ids: Vec<String>) {
        self.send(WriteCmd::SetNextUpIds(ids)).await;
    }

    /// See [`WriteCmd::BuildFavoriteIndexes`].
    pub(crate) async fn build_favorite_indexes(&self) -> bool {
        let (tx, rx) = oneshot::channel();
        self.send(WriteCmd::BuildFavoriteIndexes(tx)).await;
        rx.await.unwrap_or(false)
    }

    /// See [`WriteCmd::SetFavoriteIds`].
    pub(crate) async fn set_favorite_ids(&self, ids: Vec<String>, since: i64) {
        self.send(WriteCmd::SetFavoriteIds { ids, since }).await;
    }

    /// Waits until every command enqueued before this call has been applied. Returns false
    /// if this writer has observed any failed mutation -- sticky, since a later successful
    /// write can't prove a cursor still describes a complete local snapshot.
    pub(crate) async fn barrier(&self) -> bool {
        let (tx, rx) = oneshot::channel();
        self.send(WriteCmd::Barrier(tx)).await;
        rx.await.unwrap_or(false)
    }

    async fn send(&self, cmd: WriteCmd) {
        if self.tx.send(cmd).await.is_err() {
            tracing::warn!("mirror writer task is gone; dropping write command");
        }
    }
}

/// The `Ok(non-empty)` -> broadcast / `Ok(empty)` -> no-op / `Err` -> mark-unhealthy shape
/// every id-returning write command below shares; `log_error` supplies the call site's own
/// structured `tracing::error!` (kept per-site so `library_id`/`item_id` stay in the log).
fn changed_ids(
    writer_healthy: &mut bool,
    result: rusqlite::Result<Vec<String>>,
    log_error: impl FnOnce(&rusqlite::Error),
) -> Option<Vec<String>> {
    match result {
        Ok(ids) if !ids.is_empty() => Some(ids),
        Ok(_) => None,
        Err(e) => {
            *writer_healthy = false;
            log_error(&e);
            None
        }
    }
}

/// Runs on a `spawn_blocking` task for the lifetime of the `Mirror`. Blocking
/// `rusqlite` calls belong here and nowhere else.
pub(crate) fn run(
    mut conn: Connection,
    mut rx: mpsc::Receiver<WriteCmd>,
    changes: broadcast::Sender<MirrorChange>,
) {
    // Sticky: a failed SQLite mutation means later successful commands must not make an
    // acknowledgement green again.
    let mut writer_healthy = true;
    while let Some(cmd) = rx.blocking_recv() {
        match cmd {
            #[cfg(test)]
            WriteCmd::UpsertViews(views) => {
                if let Err(e) = apply_upsert_views(&mut conn, &views) {
                    writer_healthy = false;
                    tracing::error!(error = %e, "failed to upsert views");
                } else {
                    let _ = changes.send(MirrorChange::ViewsChanged);
                }
            }
            WriteCmd::ReplaceViews(views) => {
                match apply_replace_views(&mut conn, &views) {
                    Err(e) => {
                        writer_healthy = false;
                        tracing::error!(error = %e, "failed to replace views snapshot");
                    }
                    // A byte-for-byte identical `/UserViews` snapshot (the common warm-launch
                    // case) must not fire a change event -- see `apply_replace_views`.
                    Ok(true) => {
                        let _ = changes.send(MirrorChange::ViewsChanged);
                    }
                    Ok(false) => {}
                }
            }
            WriteCmd::UpsertItems {
                items,
                library_id,
                reply,
            } => {
                let ids = changed_ids(
                    &mut writer_healthy,
                    apply_upsert_items_scoped(&mut conn, &items, library_id.as_deref()),
                    |e| tracing::error!(error = %e, "failed to upsert items"),
                );
                if let Some(reply) = reply {
                    let _ = reply.send(ids.clone().unwrap_or_default());
                }
                if let Some(ids) = ids {
                    let _ = changes.send(MirrorChange::Upserted { ids, library_id });
                }
            }
            WriteCmd::RemoveItems(ids) => {
                if let Some(removed) = changed_ids(
                    &mut writer_healthy,
                    apply_remove_items(&mut conn, &ids),
                    |e| tracing::error!(error = %e, "failed to remove items"),
                ) {
                    let _ = changes.send(MirrorChange::Removed {
                        ids: removed,
                        library_id: None,
                    });
                }
            }
            WriteCmd::PruneLibrary {
                library_id,
                keep_ids,
                since_rowid,
            } => {
                if let Some(removed) = changed_ids(
                    &mut writer_healthy,
                    apply_prune_library(&mut conn, &library_id, &keep_ids, since_rowid),
                    |e| tracing::error!(error = %e, library_id = %library_id, "failed to prune library"),
                ) {
                    let _ = changes.send(MirrorChange::Removed {
                        ids: removed,
                        library_id: Some(library_id),
                    });
                }
            }
            WriteCmd::FlattenRootParents {
                library_id,
                item_type,
            } => {
                if let Some(ids) = changed_ids(
                    &mut writer_healthy,
                    apply_flatten_root_parents(&mut conn, &library_id, &item_type),
                    |e| tracing::error!(error = %e, library_id = %library_id, "failed to flatten root parents"),
                ) {
                    tracing::info!(
                        library_id,
                        item_type,
                        healed = ids.len(),
                        "re-parented root items onto their library view"
                    );
                    let _ = changes.send(MirrorChange::Upserted {
                        ids,
                        library_id: Some(library_id),
                    });
                }
            }
            WriteCmd::ApplyUserData(updates) => {
                if let Some(ids) = changed_ids(
                    &mut writer_healthy,
                    apply_user_data(&mut conn, &updates),
                    |e| tracing::error!(error = %e, "failed to apply user data changes"),
                ) {
                    let _ = changes.send(MirrorChange::Upserted {
                        ids,
                        library_id: None,
                    });
                }
            }
            WriteCmd::ApplyLocalUserData {
                item_id,
                position_ticks,
                played,
            } => {
                let result = apply_local_user_data(&mut conn, &item_id, position_ticks, played)
                    .map(|id| id.into_iter().collect::<Vec<_>>());
                if let Some(ids) = changed_ids(
                    &mut writer_healthy,
                    result,
                    |e| tracing::error!(error = %e, item_id = %item_id, "failed to apply local user data"),
                ) {
                    let _ = changes.send(MirrorChange::Upserted {
                        ids,
                        library_id: None,
                    });
                }
            }
            WriteCmd::SetCollectionMembers {
                collection_id,
                members,
            } => {
                match apply_set_collection_members(&mut conn, &collection_id, &members) {
                    Ok(()) => {
                        // The BoxSet's own row is unchanged, but its children are; tell the
                        // change feed so a UI browsing into it re-queries.
                        let _ = changes.send(MirrorChange::Upserted {
                            ids: vec![collection_id],
                            library_id: None,
                        });
                    }
                    Err(e) => {
                        writer_healthy = false;
                        tracing::error!(error = %e, collection_id, "failed to set collection members")
                    }
                }
            }
            WriteCmd::SetMeta { key, value } => {
                if let Err(e) = crate::schema::upsert_meta(&conn, key, &value) {
                    writer_healthy = false;
                    tracing::error!(error = %e, key, "failed to write meta");
                }
            }
            WriteCmd::SetNextUpIds(ids) => match apply_set_next_up_ids(&conn, &ids) {
                Ok(true) => {
                    let _ = changes.send(MirrorChange::Refresh);
                }
                Ok(false) => {}
                Err(e) => {
                    writer_healthy = false;
                    tracing::error!(error = %e, "failed to write next_up_ids");
                }
            },
            WriteCmd::SetFavoriteIds { ids, since } => {
                if let Some(ids) = changed_ids(
                    &mut writer_healthy,
                    apply_set_favorite_ids(&mut conn, &ids, since),
                    |e| tracing::error!(error = %e, "failed to write favorite ids"),
                ) {
                    let _ = changes.send(MirrorChange::Upserted {
                        ids,
                        library_id: None,
                    });
                }
            }
            WriteCmd::BuildFavoriteIndexes(reply) => {
                let result = conn.execute_batch(crate::schema::FAVORITE_INDEX_SQL);
                if let Err(e) = &result {
                    tracing::error!(error = %e, "failed to build favorite indexes");
                }
                let _ = reply.send(result.is_ok());
            }
            WriteCmd::Barrier(reply) => {
                let _ = reply.send(writer_healthy);
            }
        }
    }
    tracing::debug!("mirror writer task exiting (channel closed)");
}

/// Upserts one `views` row -- the identical `INSERT ... ON CONFLICT` both [`apply_upsert_views`]
/// and [`apply_replace_views`] issue per row, differing only in which transaction runs it.
fn upsert_view(
    tx: &rusqlite::Transaction<'_>,
    view: &ViewRow,
    sort_index: i64,
) -> rusqlite::Result<()> {
    tx.execute(
        "INSERT INTO views (id, name, collection_type, sort_index, item_type) VALUES (?1, ?2, ?3, ?4, ?5)
         ON CONFLICT(id) DO UPDATE SET name = excluded.name, collection_type = excluded.collection_type,
            sort_index = excluded.sort_index, item_type = excluded.item_type",
        params![view.id, view.name, view.collection_type, sort_index, view.item_type],
    )?;
    Ok(())
}

#[cfg(test)]
fn apply_upsert_views(conn: &mut Connection, views: &[ViewRow]) -> rusqlite::Result<()> {
    let tx = conn.transaction()?;
    for (idx, view) in views.iter().enumerate() {
        upsert_view(&tx, view, idx as i64)?;
    }
    tx.commit()
}

/// Returns whether the stored `views` snapshot actually moved (id, name, collection_type,
/// item_type or sort order), so a byte-identical `/UserViews` response -- the common
/// warm-launch case -- doesn't fire a spurious `MirrorChange::ViewsChanged` (see the caller).
fn apply_replace_views(conn: &mut Connection, views: &[ViewRow]) -> rusqlite::Result<bool> {
    let tx = conn.transaction()?;
    let existing: Vec<(String, String, String, String)> = {
        let mut stmt = tx.prepare(
            "SELECT id, name, collection_type, item_type FROM views ORDER BY sort_index",
        )?;
        let rows = stmt.query_map([], |row| {
            Ok((row.get(0)?, row.get(1)?, row.get(2)?, row.get(3)?))
        })?;
        rows.collect::<rusqlite::Result<Vec<_>>>()?
    };
    let incoming: Vec<(String, String, String, String)> = views
        .iter()
        .map(|v| {
            (
                v.id.clone(),
                v.name.clone(),
                v.collection_type.clone(),
                v.item_type.clone(),
            )
        })
        .collect();
    let changed = existing != incoming;

    tx.execute(
        "CREATE TEMP TABLE IF NOT EXISTS incoming_views (id TEXT PRIMARY KEY)",
        [],
    )?;
    tx.execute("DELETE FROM incoming_views", [])?;
    for (idx, view) in views.iter().enumerate() {
        tx.execute("INSERT INTO incoming_views (id) VALUES (?1)", [&view.id])?;
        upsert_view(&tx, view, idx as i64)?;
    }
    // Items are cache data scoped to the server's current user-view set; see
    // `delete_item_row` for the cleanup shape.
    let revoked_items: Vec<(String, i64, Vec<u8>)> = {
        let mut stmt = tx.prepare(
            "SELECT id, rowid, dto FROM items WHERE library_id IS NOT NULL
             AND library_id NOT IN (SELECT id FROM incoming_views)",
        )?;
        let rows = stmt
            .query_map([], |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)))?
            .collect::<rusqlite::Result<Vec<_>>>()?;
        rows
    };
    for (id, rowid, blob) in revoked_items {
        delete_item_row(&tx, &id, rowid, &blob)?;
    }
    tx.execute(
        "DELETE FROM views WHERE id NOT IN (SELECT id FROM incoming_views)",
        [],
    )?;
    tx.execute("DELETE FROM incoming_views", [])?;
    tx.commit()?;
    Ok(changed)
}

/// Returns whether `meta.next_up_ids` actually moved (membership or order), so a byte-identical
/// `/Shows/NextUp` snapshot -- the common warm-launch case -- fires no change event; see
/// [`WriteCmd::SetNextUpIds`].
fn apply_set_next_up_ids(conn: &Connection, ids: &[String]) -> Result<bool, crate::CacheError> {
    let previous: Vec<String> = crate::schema::read_meta(conn, "next_up_ids")
        .and_then(|json| serde_json::from_str(&json).ok())
        .unwrap_or_default();
    let changed = previous != ids;
    let json = serde_json::to_string(ids).unwrap_or_else(|_| "[]".to_string());
    crate::schema::upsert_meta(conn, "next_up_ids", &json)?;
    Ok(changed)
}

/// Authoritative-when-present rule: a DTO that carries a field always wins (even writing
/// back the same value), but a DTO that omits it (a field-less/`UserData`-less response, e.g.
/// the old bare `/UserItems/Resume` or a `reconcile_sweep` ids-only page) must leave the
/// existing row alone. Applied to `library_id`, `played`, `playback_position_ticks`, and
/// `last_played_date` below. See `rows::extract_columns` for how "omitted" survives as `None`.
const UPSERT_ITEM_SQL: &str = "
INSERT INTO items (
    id, parent_id, series_id, season_id, item_type, name, sort_name,
    index_number, parent_index_number, production_year, premiere_date,
    runtime_ticks, date_created,
    played, playback_position_ticks, play_count, is_favorite,
    unplayed_item_count, primary_tag, backdrop_tag, thumb_tag, primary_blurhash,
    series_primary_tag, parent_backdrop_item_id, parent_backdrop_tag,
    library_id, last_played_date, overview, is_virtual, series_name, series_status,
    dto, updated_at
) VALUES (
    ?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13,
    ?14, ?15, ?16, ?17, ?18, ?19, ?20, ?21, ?22, ?23, ?24, ?25, ?26, ?27, ?28, ?29, ?30, ?31, ?32, ?33
)
ON CONFLICT(id) DO UPDATE SET
    -- A view-stamped `parent_id` survives an upsert carrying a non-view parent (e.g.
    -- `refresh_resume`/`refresh_next_up` handing back the raw un-flattened `ParentId`),
    -- unless the incoming `parent_id` is itself a view (a genuine library move), which wins.
    parent_id = CASE
        WHEN EXISTS (SELECT 1 FROM views v WHERE v.id = excluded.parent_id) THEN excluded.parent_id
        WHEN EXISTS (SELECT 1 FROM views v WHERE v.id = items.parent_id) THEN items.parent_id
        ELSE excluded.parent_id END,
    series_id = excluded.series_id, season_id = excluded.season_id,
    item_type = excluded.item_type, name = excluded.name, sort_name = excluded.sort_name,
    index_number = excluded.index_number, parent_index_number = excluded.parent_index_number,
    production_year = excluded.production_year, premiere_date = excluded.premiere_date,
    runtime_ticks = excluded.runtime_ticks, date_created = excluded.date_created,
    -- `played`/`playback_position_ticks` are NOT NULL, so `?14`/`?15` (the INSERT slot) stay
    -- concrete defaults; `?34`/`?35` are separate placeholders bound with the raw `Option`
    -- for this COALESCE (SQLite checks NOT NULL before ON CONFLICT, so `?14`/`?15` can't be
    -- `NULL` even on a genuine conflict).
    played = COALESCE(?34, items.played),
    playback_position_ticks = COALESCE(?35, items.playback_position_ticks),
    play_count = excluded.play_count, is_favorite = excluded.is_favorite,
    unplayed_item_count = excluded.unplayed_item_count, primary_tag = excluded.primary_tag,
    backdrop_tag = excluded.backdrop_tag, thumb_tag = excluded.thumb_tag,
    primary_blurhash = excluded.primary_blurhash,
    series_primary_tag = excluded.series_primary_tag,
    parent_backdrop_item_id = excluded.parent_backdrop_item_id,
    parent_backdrop_tag = excluded.parent_backdrop_tag,
    -- A NULL `excluded.library_id` (unattributed batch) must never clobber a known value; a
    -- non-NULL one always wins, including correcting a stale value on a library move.
    library_id = COALESCE(excluded.library_id, items.library_id),
    -- `excluded.last_played_date` is already the DTO's raw `Option` (nullable column, no
    -- second placeholder needed); COALESCE keeps a UserData-less DTO from blanking an
    -- already-known date, same guard as `library_id` above.
    last_played_date = COALESCE(excluded.last_played_date, items.last_played_date),
    overview = excluded.overview,
    is_virtual = excluded.is_virtual,
    series_name = excluded.series_name,
    -- docs/16-library-sort-filter.md §1.1: `Status` is unconditional on the default DTO, so
    -- unlike `played`/`last_played_date` it needs no authoritative-when-present guard.
    series_status = excluded.series_status,
    dto = excluded.dto, updated_at = excluded.updated_at
RETURNING rowid
";

/// Unix-millis write clock; also `sync.rs`'s mark for "a row written no earlier than now"
/// (see `WriteCmd::PruneLibrary`).
pub(crate) fn now_millis() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

/// Convenience wrapper equivalent to `apply_upsert_items_scoped(conn, items, None)`, which
/// leaves each item's existing `library_id` untouched.
#[cfg(test)]
pub(crate) fn apply_upsert_items(
    conn: &mut Connection,
    items: &[BaseItemDto],
) -> rusqlite::Result<Vec<String>> {
    apply_upsert_items_scoped(conn, items, None)
}

/// `(rowid, dto, library_id, parent_id)` of a row already in `items`, read before an upsert
/// both to pair the FTS delete with the new insert and (see [`apply_upsert_items_scoped`]) to
/// detect a no-op write.
type OldItemRow = (i64, Vec<u8>, Option<String>, Option<String>);

/// The currently-synced view (collection root) ids -- the same set `UPSERT_ITEM_SQL`'s
/// `parent_id` CASE tests, read once per batch instead of once per item.
fn view_ids(tx: &rusqlite::Transaction<'_>) -> rusqlite::Result<HashSet<String>> {
    tx.prepare("SELECT id FROM views")?
        .query_map([], |row| row.get::<_, String>(0))?
        .collect()
}

/// Mirrors `UPSERT_ITEM_SQL`'s `parent_id` CASE outside SQL: an incoming view id always wins
/// (a genuine library move); otherwise an already-flattened view id already stored survives a
/// raw, un-flattened incoming `ParentId` (e.g. Resume/NextUp handing back the physical folder
/// id for an item the breadth walk already stamped onto its view, see `sync::sync_resume`'s
/// doc comment). Used to resolve what the persisted row's `parent_id` will actually become, so
/// the no-op comparison below -- and the stored DTO blob itself -- agree with the column
/// instead of a browse-flatten looking like a real change forever after.
fn resolve_parent_id(
    views: &HashSet<String>,
    incoming: Option<&str>,
    existing: Option<&str>,
) -> Option<String> {
    incoming
        .filter(|pid| views.contains(*pid))
        .or_else(|| existing.filter(|pid| views.contains(*pid)))
        .or(incoming)
        .map(str::to_string)
}

/// Upserts a batch of items in one transaction, maintaining `search` (contentless FTS5) in
/// lockstep: each write reads the row's old blob first so it can issue a matching FTS
/// `delete` before the new `insert`, since contentless tables can't reconstruct postings
/// from a `DELETE FROM items` alone. `library_id`, when `Some`, is stamped onto every item
/// (see `WriteCmd::UpsertItems` for when callers pass `None`).
pub(crate) fn apply_upsert_items_scoped(
    conn: &mut Connection,
    items: &[BaseItemDto],
    library_id: Option<&str>,
) -> rusqlite::Result<Vec<String>> {
    let tx = conn.transaction()?;
    let views = view_ids(&tx)?;
    let mut upserted = Vec::with_capacity(items.len());
    for item in items {
        let Some(cols) = extract_columns(item) else {
            tracing::warn!("skipping item with no id");
            continue;
        };

        let old: Option<OldItemRow> = tx
            .query_row(
                "SELECT rowid, dto, library_id, parent_id FROM items WHERE id = ?1",
                [&cols.id],
                |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?, row.get(3)?)),
            )
            .optional()?;

        let old_parent_id = old.as_ref().and_then(|(_, _, _, p)| p.as_deref());
        let resolved_parent_id =
            resolve_parent_id(&views, cols.parent_id.as_deref(), old_parent_id);

        // The blob stores `resolved_parent_id`, not the raw incoming one: a narrower fetch
        // (Resume/NextUp) must not downgrade an already-flattened DTO, or the blob drifts from
        // the `parent_id` column and every later re-fetch of the same unchanged item looks like
        // a change again (see `resolve_parent_id`).
        let parent_id_needs_rewrite = resolved_parent_id.as_deref() != cols.parent_id.as_deref();
        // `UserData.Key` is unread anywhere in this workspace (grepped) and its shape isn't
        // stable across endpoints -- a UUID-shaped key from one fetch, a short numeric one from
        // another, same item otherwise -- so it's normalised out the same way `parent_id` is:
        // dropped before both storage and the no-op comparison, or an endpoint switch alone
        // would register as a change forever after.
        let key_needs_stripping = item.user_data.as_ref().is_some_and(|u| u.key.is_some());
        let mut owned_item;
        let item_for_storage: &BaseItemDto = if !parent_id_needs_rewrite && !key_needs_stripping {
            item
        } else {
            owned_item = item.clone();
            if parent_id_needs_rewrite {
                owned_item.parent_id = resolved_parent_id
                    .as_deref()
                    .and_then(|id| uuid::Uuid::parse_str(id).ok());
            }
            if let Some(user_data) = owned_item.user_data.as_mut() {
                user_data.key = None;
            }
            &owned_item
        };
        let dto_bytes = to_dto_bytes(item_for_storage);

        // No-op skip: an unchanged DTO whose `library_id`/`parent_id` also wouldn't move
        // writes nothing and is left out of `upserted`, so it fires no change event.
        if let Some((_, old_blob, old_library_id, _)) = &old {
            let library_id_unchanged =
                library_id.is_none() || library_id == old_library_id.as_deref();
            let parent_id_unchanged = resolved_parent_id.as_deref() == old_parent_id;
            if dto_bytes == *old_blob && library_id_unchanged && parent_id_unchanged {
                continue;
            }
        }

        let now = now_millis();

        let rowid: i64 = tx.query_row(
            UPSERT_ITEM_SQL,
            params![
                cols.id,
                cols.parent_id,
                cols.series_id,
                cols.season_id,
                cols.item_type,
                cols.name,
                cols.sort_name,
                cols.index_number,
                cols.parent_index_number,
                cols.production_year,
                cols.premiere_date,
                cols.runtime_ticks,
                cols.date_created,
                // Concrete defaults for the plain INSERT slot; see `UPSERT_ITEM_SQL`.
                cols.played.unwrap_or(false),
                cols.playback_position_ticks.unwrap_or(0),
                cols.play_count,
                cols.is_favorite,
                cols.unplayed_item_count,
                cols.primary_tag,
                cols.backdrop_tag,
                cols.thumb_tag,
                cols.primary_blurhash,
                cols.series_primary_tag,
                cols.parent_backdrop_item_id,
                cols.parent_backdrop_tag,
                library_id,
                cols.last_played_date,
                cols.overview,
                cols.is_virtual,
                cols.series_name,
                cols.series_status,
                dto_bytes,
                now,
                // Raw `Option`s, bound only for the ON CONFLICT SET clause's COALESCE guard.
                cols.played,
                cols.playback_position_ticks,
            ],
            |row| row.get(0),
        )?;

        if let Some((old_rowid, old_blob, _, _)) = old {
            debug_assert_eq!(
                old_rowid, rowid,
                "upsert must preserve rowid for stable FTS mapping"
            );
            if let Ok(old_item) = serde_json::from_slice::<BaseItemDto>(&old_blob) {
                fts_delete(&tx, rowid, &search_text(&old_item))?;
            }
        }
        fts_insert(&tx, rowid, &search_text(item))?;

        // docs/16-library-sort-filter.md §1.2: `Genres` always replaces this item's genre set
        // outright, including when empty -- safe because every persisting fetch through this
        // function requests `Genres` (`sync::item_fields()`), unlike `played`/`library_id`
        // which need a COALESCE guard.
        tx.execute("DELETE FROM item_genres WHERE item_id = ?1", [&cols.id])?;
        for genre in &cols.genres {
            // A DTO carrying the same genre string twice must not fail the whole transaction.
            tx.execute(
                "INSERT INTO item_genres (item_id, genre) VALUES (?1, ?2) \
                 ON CONFLICT(item_id, genre) DO NOTHING",
                params![cols.id, genre],
            )?;
        }

        upserted.push(cols.id);
    }
    tx.commit()?;
    Ok(upserted)
}

fn fts_insert(
    tx: &rusqlite::Transaction<'_>,
    rowid: i64,
    text: &SearchText,
) -> rusqlite::Result<()> {
    tx.execute(
        "INSERT INTO search (rowid, name, original_title, series_name) VALUES (?1, ?2, ?3, ?4)",
        params![rowid, text.name, text.original_title, text.series_name],
    )?;
    Ok(())
}

fn fts_delete(
    tx: &rusqlite::Transaction<'_>,
    rowid: i64,
    text: &SearchText,
) -> rusqlite::Result<()> {
    tx.execute(
        "INSERT INTO search (search, rowid, name, original_title, series_name) VALUES ('delete', ?1, ?2, ?3, ?4)",
        params![rowid, text.name, text.original_title, text.series_name],
    )?;
    Ok(())
}

/// Deletes one already-selected `items` row, inside the caller's open transaction, along
/// with its FTS postings (contentless, so the old `dto` text is needed to remove them; a
/// deserialize failure just skips that step), `collection_members` rows keyed by either
/// column (no FK; `id` may be a BoxSet or a member), and `item_genres` rows
/// (docs/16-library-sort-filter.md §1.2). Returns whether the `items` row itself was actually
/// deleted (the `DELETE`'s own affected-row count, not the caller's earlier `SELECT`): the
/// caller's `removed` list -- and so the `MirrorChange::Removed` broadcast -- must reflect
/// only ids a row genuinely vanished for, never an id merely named by a stale or foreign
/// caller-side listing.
fn delete_item_row(
    tx: &rusqlite::Transaction<'_>,
    id: &str,
    rowid: i64,
    dto: &[u8],
) -> rusqlite::Result<bool> {
    if let Ok(old_item) = serde_json::from_slice::<BaseItemDto>(dto) {
        fts_delete(tx, rowid, &search_text(&old_item))?;
    }
    let deleted = tx.execute("DELETE FROM items WHERE id = ?1", [id])? > 0;
    tx.execute(
        "DELETE FROM collection_members WHERE collection_id = ?1 OR item_id = ?1",
        [id],
    )?;
    tx.execute("DELETE FROM item_genres WHERE item_id = ?1", [id])?;
    Ok(deleted)
}

/// Bounds each `IN (...)` clause `apply_remove_items`'s cascade lookup builds, so a large
/// `LibraryChanged` removal batch never binds one parameter per id in a single statement.
const SQL_IN_CHUNK: usize = 500;

/// Deletes items by id, cascading to rows referencing a removed id via
/// `parent_id`/`series_id`/`season_id`. Returns every id actually removed.
pub(crate) fn apply_remove_items(
    conn: &mut Connection,
    ids: &[String],
) -> rusqlite::Result<Vec<String>> {
    let tx = conn.transaction()?;
    let mut to_delete: std::collections::HashSet<String> = ids.iter().cloned().collect();

    // Fixpoint over cascaded children; the graph is at most a few levels deep in practice.
    loop {
        let frontier: Vec<String> = to_delete.iter().cloned().collect();
        let mut found = Vec::new();
        for column in ["parent_id", "series_id", "season_id"] {
            for chunk in frontier.chunks(SQL_IN_CHUNK) {
                let sql = format!(
                    "SELECT id FROM items WHERE {column} IN ({})",
                    std::iter::repeat_n("?", chunk.len())
                        .collect::<Vec<_>>()
                        .join(",")
                );
                let mut stmt = tx.prepare(&sql)?;
                let rows = stmt.query_map(rusqlite::params_from_iter(chunk.iter()), |row| {
                    row.get::<_, String>(0)
                })?;
                for r in rows {
                    found.push(r?);
                }
            }
        }
        let mut grew = false;
        for id in found {
            if to_delete.insert(id) {
                grew = true;
            }
        }
        if !grew {
            break;
        }
    }

    let mut removed = Vec::with_capacity(to_delete.len());
    for id in &to_delete {
        let old: Option<(i64, Vec<u8>)> = tx
            .query_row("SELECT rowid, dto FROM items WHERE id = ?1", [id], |row| {
                Ok((row.get(0)?, row.get(1)?))
            })
            .optional()?;
        let Some((rowid, blob)) = old else { continue };
        // See `delete_item_row` for why each step is needed. Its own affected-row count, not
        // this `SELECT`, decides `removed`: an id named by `ids` (or found via cascade) that
        // the mirror never actually stored a row for must never reach the broadcast.
        if delete_item_row(&tx, id, rowid, &blob)? {
            removed.push(id.clone());
        }
    }
    tx.commit()?;
    Ok(removed)
}

/// See [`WriteCmd::FlattenRootParents`]; returns the ids whose `parent_id` actually changed.
pub(crate) fn apply_flatten_root_parents(
    conn: &mut Connection,
    library_id: &str,
    item_type: &str,
) -> rusqlite::Result<Vec<String>> {
    let tx = conn.transaction()?;
    let ids: Vec<String> = {
        let mut stmt = tx.prepare(
            "SELECT id FROM items WHERE library_id = ?1 AND item_type = ?2 \
             AND (parent_id IS NULL OR parent_id != ?1)",
        )?;
        let rows = stmt.query_map([library_id, item_type], |row| row.get(0))?;
        rows.collect::<rusqlite::Result<Vec<_>>>()?
    };
    if !ids.is_empty() {
        tx.execute(
            "UPDATE items SET parent_id = ?1 WHERE library_id = ?1 AND item_type = ?2 \
             AND (parent_id IS NULL OR parent_id != ?1)",
            [library_id, item_type],
        )?;
    }
    tx.commit()?;
    Ok(ids)
}

/// Prunes stale rows belonging to one library (see [`WriteCmd::PruneLibrary`]). Deliberately
/// not `apply_remove_items`'s cascade-by-parent/series/season logic: `keep_ids` is already a
/// complete current membership snapshot, so a child whose parent is gone is itself already
/// missing from it, no graph walk needed. Scoped to `library_id` throughout, so another
/// library's rows are never touched.
///
/// `since_rowid` guards against pruning a row a concurrent `LibraryChanged` insert wrote
/// after `keep_ids`'s enumeration began (docs/12): such a row gets a `rowid` past that mark
/// (SQLite's own monotonic counter, immune to wall-clock ties), so it survives even when
/// `keep_ids` doesn't list it yet.
pub(crate) fn apply_prune_library(
    conn: &mut Connection,
    library_id: &str,
    keep_ids: &[String],
    since_rowid: i64,
) -> rusqlite::Result<Vec<String>> {
    let tx = conn.transaction()?;

    // `idx_items_latest_virtual` leads on `library_id`, so this is an index range scan, not a
    // full table scan; the rare per-library resync pass the pruning contract allows.
    let local: Vec<(i64, String, Vec<u8>)> = {
        let mut stmt = tx.prepare("SELECT rowid, id, dto FROM items WHERE library_id = ?1")?;
        let rows = stmt.query_map([library_id], |row| {
            Ok((row.get(0)?, row.get(1)?, row.get(2)?))
        })?;
        rows.collect::<rusqlite::Result<Vec<_>>>()?
    };

    let keep: std::collections::HashSet<&str> = keep_ids.iter().map(String::as_str).collect();
    let mut removed = Vec::new();
    for (rowid, id, blob) in &local {
        if keep.contains(id.as_str()) || *rowid > since_rowid {
            continue;
        }
        // See `delete_item_row` for why each step is needed; its affected-row count (not
        // membership in `local`, which was read before this transaction's own writes) decides
        // `removed`.
        if delete_item_row(&tx, id, *rowid, blob)? {
            removed.push(id.clone());
        }
    }

    tx.commit()?;
    Ok(removed)
}

/// Replaces the full membership list of one BoxSet: delete-then-reinsert in one transaction,
/// since `/Items?ParentId=<boxset_id>` is always the complete member list, not a delta.
/// `pub(crate)` so `query.rs`'s tests can seed membership without a writer harness.
pub(crate) fn apply_set_collection_members(
    conn: &mut Connection,
    collection_id: &str,
    members: &[(String, i64)],
) -> rusqlite::Result<()> {
    let tx = conn.transaction()?;
    tx.execute(
        "DELETE FROM collection_members WHERE collection_id = ?1",
        [collection_id],
    )?;
    for (item_id, sort_index) in members {
        tx.execute(
            "INSERT INTO collection_members (collection_id, item_id, sort_index) VALUES (?1, ?2, ?3)
             ON CONFLICT(collection_id, item_id) DO UPDATE SET sort_index = excluded.sort_index",
            params![collection_id, item_id, sort_index],
        )?;
    }
    tx.commit()
}

/// [`WriteCmd::SetFavoriteIds`]: returns only the ids whose flag actually flipped, so an
/// unchanged favorites list emits no change event. Rows written at or after `since` are
/// newer than the snapshot and keep their flag.
pub(crate) fn apply_set_favorite_ids(
    conn: &mut Connection,
    favorite_ids: &[String],
    since: i64,
) -> rusqlite::Result<Vec<String>> {
    let wanted: HashSet<&str> = favorite_ids.iter().map(String::as_str).collect();
    let tx = conn.transaction()?;
    let current: Vec<String> = tx
        .prepare("SELECT id FROM items WHERE is_favorite = 1 AND updated_at < ?1")?
        .query_map([since], |row| row.get(0))?
        .collect::<rusqlite::Result<_>>()?;
    let now = now_millis();
    let mut flipped = Vec::new();
    for id in current.iter().filter(|id| !wanted.contains(id.as_str())) {
        tx.execute(
            "UPDATE items SET is_favorite = 0, updated_at = ?1 WHERE id = ?2",
            params![now, id],
        )?;
        flipped.push(id.clone());
    }
    for id in &wanted {
        if tx.execute(
            "UPDATE items SET is_favorite = 1, updated_at = ?1 \
             WHERE id = ?2 AND is_favorite = 0 AND updated_at < ?3",
            params![now, id, since],
        )? > 0
        {
            flipped.push((*id).to_string());
        }
    }
    tx.commit()?;
    Ok(flipped)
}

/// `UserDataChanged` application: patches only the columns present in the event (
/// §2), leaving everything else, including the blob, as is.
pub(crate) fn apply_user_data(
    conn: &mut Connection,
    updates: &[(String, UserItemDataDto)],
) -> rusqlite::Result<Vec<String>> {
    let tx = conn.transaction()?;
    let mut touched = Vec::with_capacity(updates.len());
    for (item_id, data) in updates {
        let changed = tx.execute(
            "UPDATE items SET
                played = COALESCE(?1, played),
                playback_position_ticks = COALESCE(?2, playback_position_ticks),
                play_count = COALESCE(?3, play_count),
                is_favorite = COALESCE(?4, is_favorite),
                unplayed_item_count = COALESCE(?5, unplayed_item_count),
                last_played_date = COALESCE(?6, last_played_date),
                updated_at = ?7
             WHERE id = ?8",
            params![
                data.played,
                data.playback_position_ticks,
                data.play_count,
                data.is_favorite,
                data.unplayed_item_count,
                data.last_played_date.map(|d| d.to_rfc3339()),
                now_millis(),
                item_id,
            ],
        )?;
        if changed > 0 {
            touched.push(item_id.clone());
        } else {
            // Not an error (a delta can race ahead of breadth sync), but worth a trace.
            tracing::debug!(
                item_id,
                "UserDataChanged for an item not present in the mirror; ignoring"
            );
        }
    }
    tx.commit()?;
    Ok(touched)
}

/// Jellyfin's server-side "mark played" threshold: a report at or past this fraction of
/// runtime is a completed watch (position resets to 0, `played` flips true), mirroring the
/// server's own `UserData` behavior.
const PLAYED_THRESHOLD: f64 = 0.9;

/// Given a reported position and an optional explicit `played` override (`Some(true)` at
/// real EOF, `None` for an ordinary tick/stop that only flips `played` past the threshold),
/// decides the position/played pair to write, mirroring the server's threshold locally so
/// the mirror needn't wait on a `UserDataChanged` push.
///
/// `runtime_ticks` of `None`/`<= 0` can't be compared against, so only the override applies.
fn resolve_played_position(
    position_ticks: i64,
    played_override: Option<bool>,
    runtime_ticks: Option<i64>,
    current_played: bool,
) -> (i64, bool) {
    let crossed_threshold = match runtime_ticks {
        Some(rt) if rt > 0 => (position_ticks as f64 / rt as f64) >= PLAYED_THRESHOLD,
        _ => false,
    };
    if played_override == Some(true) || crossed_threshold {
        (0, true)
    } else {
        (
            position_ticks.max(0),
            played_override.unwrap_or(current_played),
        )
    }
}

/// Clock-skew clamp for [`apply_local_user_data`]'s `last_played_date` stamp: a local clock
/// running behind the server's must never regress an already-known, newer date, since
/// `resume()` sorts by this column. `None`/unparseable `existing` always defers to `now`.
/// Pure and takes `now` explicitly so it's unit-testable without a live clock.
fn clamp_local_last_played_date(
    existing: Option<&str>,
    now: chrono::DateTime<chrono::Utc>,
) -> String {
    let existing_parsed = existing.and_then(|s| chrono::DateTime::parse_from_rfc3339(s).ok());
    match existing_parsed {
        Some(existing) if existing > now => existing.to_rfc3339(),
        _ => now.to_rfc3339(),
    }
}

/// Optimistic local application of a watch-state report; see `resolve_played_position`.
/// Returns `None` if the mirror doesn't have this item.
pub(crate) fn apply_local_user_data(
    conn: &mut Connection,
    item_id: &str,
    position_ticks: i64,
    played_override: Option<bool>,
) -> rusqlite::Result<Option<String>> {
    let tx = conn.transaction()?;
    let existing: Option<(Option<i64>, bool, Option<String>)> = tx
        .query_row(
            "SELECT runtime_ticks, played, last_played_date FROM items WHERE id = ?1",
            [item_id],
            |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
        )
        .optional()?;
    let Some((runtime_ticks, current_played, existing_last_played_date)) = existing else {
        tracing::debug!(
            item_id,
            "apply_local_user_data for an item not present in the mirror; ignoring"
        );
        return Ok(None);
    };

    let (position_ticks, played) = resolve_played_position(
        position_ticks,
        played_override,
        runtime_ticks,
        current_played,
    );

    // Stamp `last_played_date` to "now" so `resume()` sorts a fresh stop/EOF ahead
    // immediately, clamped so a slow local clock can't regress a newer known date.
    let last_played_date =
        clamp_local_last_played_date(existing_last_played_date.as_deref(), chrono::Utc::now());
    tx.execute(
        "UPDATE items SET playback_position_ticks = ?1, played = ?2, last_played_date = ?3, updated_at = ?4 WHERE id = ?5",
        params![position_ticks, played, last_played_date, now_millis(), item_id],
    )?;
    tx.commit()?;
    Ok(Some(item_id.to_string()))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::schema::{open_and_prepare, open_test_db};
    use jellyfin_api::models::BaseItemKind;

    /// Pins that `barrier()` (unlike a plain `send`, which resolves once enqueued) only
    /// returns once every earlier command is actually committed (docs/17 §6). Asserted
    /// through a second connection, since the writer's own connection would prove nothing.
    #[tokio::test(flavor = "multi_thread")]
    async fn barrier_returns_only_after_earlier_commands_have_committed() {
        let dir = tempfile::tempdir().expect("tempdir");
        let path = dir.path().join("mirror.db");
        let (conn, _) = open_and_prepare(&path).expect("open");

        let (tx, rx) = mpsc::channel(64);
        let (changes, _changes_rx) = broadcast::channel(64);
        let writer = tokio::task::spawn_blocking(move || run(conn, rx, changes));
        let handle = WriterHandle::new(tx);

        let id = "e2f5a5f1-1a0b-4b3a-9c2e-000000000001";
        // A batch ahead of the position write, so the barrier has something to wait behind.
        for n in 0..16 {
            handle
                .upsert_items(vec![item(id, &format!("A Movie {n}"))])
                .await;
        }
        handle
            .apply_local_user_data(id.to_string(), 4_200, None)
            .await;
        handle.barrier().await;

        let reader = Connection::open(&path).expect("second connection");
        let position: i64 = reader
            .query_row(
                "SELECT playback_position_ticks FROM items WHERE id = ?1",
                [id],
                |r| r.get(0),
            )
            .expect("row exists and the position write has committed");
        assert_eq!(position, 4_200);

        drop(handle);
        let _ = writer.await;
    }

    /// A favorites snapshot fetched before a newer favorite write -- a toggle or an item upsert
    /// carrying `IsFavorite` -- must not undo it; a snapshot fetched after both applies.
    #[tokio::test(flavor = "multi_thread")]
    async fn a_stale_favorites_snapshot_never_undoes_a_newer_write() {
        let dir = tempfile::tempdir().expect("tempdir");
        let path = dir.path().join("mirror.db");
        let (conn, _) = open_and_prepare(&path).expect("open");
        let (tx, rx) = mpsc::channel(64);
        let (changes, _changes_rx) = broadcast::channel(64);
        let writer = tokio::task::spawn_blocking(move || run(conn, rx, changes));
        let handle = WriterHandle::new(tx);
        let toggled = "e2f5a5f1-1a0b-4b3a-9c2e-000000000001";
        let upserted = "e2f5a5f1-1a0b-4b3a-9c2e-000000000002";
        handle
            .upsert_items(vec![item(toggled, "A Movie"), item(upserted, "B Movie")])
            .await;
        handle.barrier().await;
        let tick = || tokio::time::sleep(std::time::Duration::from_millis(5));
        let reader = Connection::open(&path).expect("second connection");
        let is_favorite = |id: &str| -> bool {
            reader
                .query_row("SELECT is_favorite FROM items WHERE id = ?1", [id], |r| {
                    r.get(0)
                })
                .expect("row")
        };

        tick().await;
        let stale = now_millis();
        tick().await;
        let toggle = UserItemDataDto {
            is_favorite: Some(true),
            ..Default::default()
        };
        handle
            .apply_user_data(vec![(toggled.to_string(), toggle.clone())])
            .await;
        let mut fetched = item(upserted, "B Movie");
        fetched.user_data = Some(toggle);
        handle.upsert_items(vec![fetched]).await;
        handle.set_favorite_ids(Vec::new(), stale).await;
        handle.barrier().await;
        assert!(
            is_favorite(toggled),
            "the stale snapshot must not undo the toggle"
        );
        assert!(
            is_favorite(upserted),
            "the stale snapshot must not undo the upsert"
        );

        tick().await;
        handle.set_favorite_ids(Vec::new(), now_millis()).await;
        handle.barrier().await;
        assert!(
            !is_favorite(toggled) && !is_favorite(upserted),
            "a fresh snapshot applies"
        );

        drop(handle);
        let _ = writer.await;
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn barrier_reports_a_failed_sqlite_transaction() {
        let dir = tempfile::tempdir().expect("tempdir");
        let path = dir.path().join("mirror.db");
        let (conn, _) = open_and_prepare(&path).expect("open");
        conn.execute("DROP TABLE meta", [])
            .expect("break meta writes");
        let (tx, rx) = mpsc::channel(8);
        let (changes, _changes_rx) = broadcast::channel(8);
        let writer = tokio::task::spawn_blocking(move || run(conn, rx, changes));
        let handle = WriterHandle::new(tx);

        handle.set_meta("cursor", "advanced".into()).await;
        assert!(
            !handle.barrier().await,
            "queue drain must not masquerade as transaction success"
        );

        drop(handle);
        let _ = writer.await;
    }

    fn item(id: &str, name: &str) -> BaseItemDto {
        BaseItemDto {
            id: Some(uuid::Uuid::parse_str(id).expect("uuid")),
            name: Some(name.to_string()),
            type_: Some(BaseItemKind::Movie),
            ..Default::default()
        }
    }

    /// A byte-identical `/UserViews` snapshot -- same ids, names, types and order -- must
    /// report no change, so a warm-launch `sync_views` against an unchanged server stops
    /// firing `MirrorChange::ViewsChanged` on every launch (see the `WriteCmd::ReplaceViews`
    /// handler in `run`).
    #[test]
    fn replace_views_with_an_identical_snapshot_reports_no_change() {
        let (_dir, mut conn) = open_test_db();
        let views = vec![
            ViewRow {
                id: "movies".into(),
                name: "Movies".into(),
                collection_type: "movies".into(),
                item_type: "CollectionFolder".into(),
            },
            ViewRow {
                id: "shows".into(),
                name: "Shows".into(),
                collection_type: "tvshows".into(),
                item_type: "CollectionFolder".into(),
            },
        ];
        assert!(
            apply_replace_views(&mut conn, &views).expect("first replace"),
            "the first snapshot against an empty mirror is a real change"
        );
        assert!(
            !apply_replace_views(&mut conn, &views).expect("identical replace"),
            "a byte-identical views snapshot must report no change"
        );

        // A real move (reordered, renamed, or a membership change) must still report changed.
        let mut reordered = views.clone();
        reordered.reverse();
        assert!(
            apply_replace_views(&mut conn, &reordered).expect("reordered replace"),
            "a reordered snapshot is a real change"
        );
    }

    /// `meta.next_up_ids` mirrors [`replace_views_with_an_identical_snapshot_reports_no_change`]:
    /// an identical ordered id list is a no-op, but a membership or order move must still be
    /// reported so `query::next_up` (entirely driven by this meta key) doesn't go stale with
    /// no change event to react to.
    #[test]
    fn set_next_up_ids_reports_change_only_on_an_actual_move() {
        let (_dir, conn) = open_test_db();
        let ids = vec!["a".to_string(), "b".to_string()];

        assert!(
            apply_set_next_up_ids(&conn, &ids).expect("first write"),
            "the first next_up_ids write against an empty mirror is a real change"
        );
        assert!(
            !apply_set_next_up_ids(&conn, &ids).expect("identical write"),
            "an identical ordered id list must report no change"
        );

        let reordered = vec!["b".to_string(), "a".to_string()];
        assert!(
            apply_set_next_up_ids(&conn, &reordered).expect("reordered write"),
            "a reorder is a real change even with the same membership"
        );

        let shrunk = vec!["b".to_string()];
        assert!(
            apply_set_next_up_ids(&conn, &shrunk).expect("shrunk write"),
            "a dropped id is a real change"
        );
    }

    #[test]
    fn replace_views_removes_absent_view_and_its_scoped_items_atomically() {
        let (_dir, mut conn) = open_test_db();
        let movie_id = "e2f5a5f1-1a0b-4b3a-9c2e-000000000011";
        let show_id = "e2f5a5f1-1a0b-4b3a-9c2e-000000000012";
        apply_replace_views(
            &mut conn,
            &[
                ViewRow {
                    id: "movies".into(),
                    name: "Movies".into(),
                    collection_type: "movies".into(),
                    item_type: "CollectionFolder".into(),
                },
                ViewRow {
                    id: "shows".into(),
                    name: "Shows".into(),
                    collection_type: "tvshows".into(),
                    item_type: "CollectionFolder".into(),
                },
            ],
        )
        .expect("seed views");
        apply_upsert_items_scoped(&mut conn, &[item(movie_id, "Movie")], Some("movies"))
            .expect("seed movie");
        apply_upsert_items_scoped(&mut conn, &[item(show_id, "Show")], Some("shows"))
            .expect("seed show");
        assert_eq!(
            conn.query_row(
                "SELECT COUNT(*) FROM search WHERE search MATCH 'movie*'",
                [],
                |r| r.get::<_, i64>(0)
            )
            .expect("fts count"),
            1
        );

        apply_replace_views(
            &mut conn,
            &[ViewRow {
                id: "shows".into(),
                name: "Shows".into(),
                collection_type: "tvshows".into(),
                item_type: "CollectionFolder".into(),
            }],
        )
        .expect("replace snapshot");

        assert_eq!(
            conn.query_row("SELECT COUNT(*) FROM views", [], |r| r.get::<_, i64>(0))
                .expect("views count"),
            1
        );
        assert_eq!(
            conn.query_row(
                "SELECT COUNT(*) FROM items WHERE id = ?1",
                [movie_id],
                |r| r.get::<_, i64>(0)
            )
            .expect("movie count"),
            0
        );
        assert_eq!(
            conn.query_row("SELECT COUNT(*) FROM items WHERE id = ?1", [show_id], |r| r
                .get::<_, i64>(0))
                .expect("show count"),
            1
        );
        assert_eq!(
            conn.query_row(
                "SELECT COUNT(*) FROM search WHERE search MATCH 'movie*'",
                [],
                |r| r.get::<_, i64>(0)
            )
            .expect("fts count"),
            0
        );
    }

    /// Pins that `FlattenRootParents` re-parents exactly the wrong rows of the named type
    /// onto the library id, reports them, and leaves correct rows (and other types) alone.
    #[test]
    fn flatten_root_parents_reparents_only_the_misfiled_rows_of_that_type() {
        let (_dir, mut conn) = open_test_db();
        let view = "e2f5a5f1-1a0b-4b3a-9c2e-0000000000d0";
        let folder = "e2f5a5f1-1a0b-4b3a-9c2e-0000000000d1";
        let view_uuid = uuid::Uuid::parse_str(view).expect("uuid");
        let folder_uuid = uuid::Uuid::parse_str(folder).expect("uuid");
        let mut ok = item("e2f5a5f1-1a0b-4b3a-9c2e-000000000010", "Series Alpha");
        ok.type_ = Some(BaseItemKind::Series);
        ok.parent_id = Some(view_uuid);
        let mut misfiled = item("e2f5a5f1-1a0b-4b3a-9c2e-000000000011", "Series Beta");
        misfiled.type_ = Some(BaseItemKind::Series);
        misfiled.parent_id = Some(folder_uuid);
        // An Episode must never be touched by a Series heal.
        let mut episode = item("e2f5a5f1-1a0b-4b3a-9c2e-000000000012", "Episode Gamma");
        episode.type_ = Some(BaseItemKind::Episode);
        episode.parent_id = Some(folder_uuid);
        apply_upsert_items_scoped(&mut conn, &[ok, misfiled, episode], Some(view)).expect("upsert");

        let healed = apply_flatten_root_parents(&mut conn, view, "Series").expect("heal");
        assert_eq!(
            healed,
            vec!["e2f5a5f1-1a0b-4b3a-9c2e-000000000011".to_string()]
        );

        let parent_of = |id: &str| -> String {
            conn.query_row("SELECT parent_id FROM items WHERE id = ?1", [id], |row| {
                row.get(0)
            })
            .expect("row")
        };
        assert_eq!(parent_of("e2f5a5f1-1a0b-4b3a-9c2e-000000000011"), view);
        assert_eq!(parent_of("e2f5a5f1-1a0b-4b3a-9c2e-000000000010"), view);
        assert_eq!(parent_of("e2f5a5f1-1a0b-4b3a-9c2e-000000000012"), folder);

        // Idempotent: a second pass changes nothing and reports nothing.
        let again = apply_flatten_root_parents(&mut conn, view, "Series").expect("heal");
        assert!(again.is_empty());
    }

    // ---- Root-parent survival across a raw-ParentId upsert -------------
    // These four tests pin the `CASE` guard in `UPSERT_ITEM_SQL` that keeps a view-stamped
    // `parent_id` from being undone by a Resume/NextUp/BoxSet upsert carrying the server's
    // raw, un-flattened `ParentId`.

    fn parent_of(conn: &Connection, id: &str) -> String {
        conn.query_row("SELECT parent_id FROM items WHERE id = ?1", [id], |row| {
            row.get(0)
        })
        .expect("row exists")
    }

    fn seed_view(conn: &mut Connection, id: &str) {
        apply_upsert_views(
            conn,
            &[ViewRow {
                id: id.to_string(),
                name: "A Library".into(),
                collection_type: "movies".into(),
                item_type: "CollectionFolder".into(),
            }],
        )
        .expect("seed view");
    }

    /// A Resume/NextUp/BoxSet upsert carrying the physical folder's id as `ParentId` must
    /// not re-parent a movie off its library view.
    #[test]
    fn raw_folder_parent_upsert_does_not_undo_root_flattening() {
        let (_dir, mut conn) = open_test_db();
        let view = "e2f5a5f1-1a0b-4b3a-9c2e-0000000000d0";
        let folder = "e2f5a5f1-1a0b-4b3a-9c2e-0000000000d1";
        let id = "e2f5a5f1-1a0b-4b3a-9c2e-000000000020";
        seed_view(&mut conn, view);

        let mut movie = item(id, "A Movie");
        movie.parent_id = Some(uuid::Uuid::parse_str(view).expect("uuid"));
        apply_upsert_items_scoped(&mut conn, &[movie], Some(view)).expect("breadth-sync seed");
        assert_eq!(parent_of(&conn, id), view);

        // Simulates refresh_resume/refresh_next_up/BoxSet: plain upsert with the raw folder id.
        let mut resumed = item(id, "A Movie");
        resumed.parent_id = Some(uuid::Uuid::parse_str(folder).expect("uuid"));
        apply_upsert_items(&mut conn, &[resumed]).expect("resume-shaped upsert");

        assert_eq!(
            parent_of(&conn, id),
            view,
            "a raw folder ParentId must not undo the view-flattening a breadth sync already did"
        );
    }

    /// The `parent_id` column survives a raw-`ParentId` re-upsert (previous test), but that
    /// alone isn't enough: the stored DTO blob must resolve the same way, or a byte-identical
    /// resume/next-up response looks like a change every time it's re-fetched
    /// (`resolve_parent_id`'s reason for existing). A resume-shaped upsert of the exact same
    /// item the breadth sync already flattened must report no change from the start -- the
    /// blob is normalized before the comparison runs, not just before some later pass -- and
    /// the blob itself must carry the view id, not the server's raw folder id.
    #[test]
    fn raw_folder_parent_reupsert_is_a_no_op_and_normalizes_the_blob() {
        let (_dir, mut conn) = open_test_db();
        let view = "e2f5a5f1-1a0b-4b3a-9c2e-0000000000d0";
        let folder = "e2f5a5f1-1a0b-4b3a-9c2e-0000000000d1";
        let id = "e2f5a5f1-1a0b-4b3a-9c2e-000000000024";
        seed_view(&mut conn, view);

        // Simulates `sync_library_breadth`: the raw item already flattened onto its view
        // before being handed to the writer (see `flatten_root_parents_in_place`).
        let mut movie = item(id, "A Movie");
        movie.parent_id = Some(uuid::Uuid::parse_str(view).expect("uuid"));
        apply_upsert_items_scoped(&mut conn, &[movie], Some(view)).expect("breadth-sync seed");

        // Simulates `sync_resume`/`refresh_next_up`: same item, server's raw un-flattened
        // folder id, no `library_id` attribution.
        let resume_shaped = || {
            let mut resumed = item(id, "A Movie");
            resumed.parent_id = Some(uuid::Uuid::parse_str(folder).expect("uuid"));
            resumed
        };
        let first =
            apply_upsert_items(&mut conn, &[resume_shaped()]).expect("first resume-shaped upsert");
        assert!(
            first.is_empty(),
            "a resume-shaped upsert of an already-flattened item must be a no-op immediately, \
             not only after one wasted write"
        );

        let second =
            apply_upsert_items(&mut conn, &[resume_shaped()]).expect("second resume-shaped upsert");
        assert!(
            second.is_empty(),
            "a repeated resume-shaped upsert must stay a no-op"
        );

        let blob: Vec<u8> = conn
            .query_row("SELECT dto FROM items WHERE id = ?1", [id], |r| r.get(0))
            .expect("dto");
        let stored: BaseItemDto = serde_json::from_slice(&blob).expect("stored dto");
        assert_eq!(
            stored.parent_id,
            Some(uuid::Uuid::parse_str(view).expect("uuid")),
            "the stored DTO blob must carry the resolved (view) parent id, not the server's \
             raw folder id, so it stays consistent with the `parent_id` column"
        );
    }

    /// `UserData.Key` is read nowhere in this workspace and its shape isn't stable across
    /// endpoints (a UUID-shaped key from one, a short numeric key from another, same item
    /// otherwise) -- exactly what rewrote every Resume/NextUp row on a real server despite
    /// nothing the user would call "changed". It's normalised out before both storage and the
    /// no-op comparison, the same way `resolve_parent_id` normalises a raw folder id.
    #[test]
    fn user_data_key_shape_change_alone_is_a_no_op() {
        let (_dir, mut conn) = open_test_db();
        let id = "e2f5a5f1-1a0b-4b3a-9c2e-000000000025";

        let with_key = |key: &str| {
            let mut dto = item(id, "A Movie");
            dto.user_data = Some(UserItemDataDto {
                played: Some(true),
                playback_position_ticks: Some(0),
                key: Some(key.to_string()),
                ..Default::default()
            });
            dto
        };

        let first = apply_upsert_items(
            &mut conn,
            &[with_key("22222222-2222-2222-2222-222222222222")],
        )
        .expect("first upsert");
        assert_eq!(
            first,
            vec![id.to_string()],
            "the first write is a real change"
        );

        // Same item, everything else identical, but `Key` now comes back in the other
        // endpoint's shape -- must be a no-op.
        let second = apply_upsert_items(&mut conn, &[with_key("100")]).expect("second upsert");
        assert!(
            second.is_empty(),
            "an endpoint's UserData.Key shape alone must never register as a change"
        );

        let blob: Vec<u8> = conn
            .query_row("SELECT dto FROM items WHERE id = ?1", [id], |r| r.get(0))
            .expect("dto");
        let stored: BaseItemDto = serde_json::from_slice(&blob).expect("stored dto");
        assert_eq!(
            stored.user_data.and_then(|u| u.key),
            None,
            "Key is dropped from the stored blob outright, not just from the comparison"
        );
    }

    /// `apply_remove_items`'s `removed` (and so the `MirrorChange::Removed` broadcast) must
    /// list only ids a row genuinely vanished for -- never an id a caller merely named (e.g. a
    /// listing referencing an item this mirror never stored: virtual, filtered by item_type,
    /// or in an unsynced library). Run through the real `WriterHandle`/`run()` loop, not just
    /// `apply_remove_items` directly, so the broadcast path is covered end to end.
    #[tokio::test(flavor = "multi_thread")]
    async fn remove_items_broadcasts_only_ids_whose_row_was_actually_deleted() {
        let dir = tempfile::tempdir().expect("tempdir");
        let (conn, _) = open_and_prepare(&dir.path().join("mirror.db")).expect("open");
        let (tx, rx) = mpsc::channel(8);
        let (changes, mut changes_rx) = broadcast::channel(8);
        let writer = tokio::task::spawn_blocking(move || run(conn, rx, changes));
        let handle = WriterHandle::new(tx);

        let phantom_id = "e2f5a5f1-1a0b-4b3a-9c2e-000000000030".to_string();
        handle.remove_items(vec![phantom_id]).await;
        assert!(
            handle.barrier().await,
            "a no-op remove must not mark the writer unhealthy"
        );
        assert!(
            changes_rx.try_recv().is_err(),
            "an id whose DELETE affected no row must never reach the broadcast"
        );

        // A real row alongside a phantom one in the same batch: only the real id is reported.
        let real_id = "e2f5a5f1-1a0b-4b3a-9c2e-000000000031".to_string();
        let other_phantom_id = "e2f5a5f1-1a0b-4b3a-9c2e-000000000032".to_string();
        handle.upsert_items(vec![item(&real_id, "A Movie")]).await;
        handle.barrier().await;
        while changes_rx.try_recv().is_ok() {}

        handle
            .remove_items(vec![real_id.clone(), other_phantom_id])
            .await;
        handle.barrier().await;
        match changes_rx.try_recv() {
            Ok(MirrorChange::Removed { ids, .. }) => {
                assert_eq!(
                    ids,
                    vec![real_id],
                    "the broadcast must list the genuinely deleted id and nothing else"
                );
            }
            other => panic!("expected a Removed change for the real id, got {other:?}"),
        }

        drop(handle);
        let _ = writer.await;
    }

    /// The guard's other arm: a genuine library move (a fresh breadth sync stamping the
    /// item's new view id) must still win.
    #[test]
    fn upsert_with_a_different_view_parent_moves_the_item() {
        let (_dir, mut conn) = open_test_db();
        let old_view = "e2f5a5f1-1a0b-4b3a-9c2e-0000000000d0";
        let new_view = "c1c1c1c1-1111-2222-3333-444444444444";
        let id = "e2f5a5f1-1a0b-4b3a-9c2e-000000000021";
        seed_view(&mut conn, old_view);
        seed_view(&mut conn, new_view);

        let mut movie = item(id, "A Movie");
        movie.parent_id = Some(uuid::Uuid::parse_str(old_view).expect("uuid"));
        apply_upsert_items_scoped(&mut conn, &[movie], Some(old_view)).expect("seed");
        assert_eq!(parent_of(&conn, id), old_view);

        let mut moved = item(id, "A Movie");
        moved.parent_id = Some(uuid::Uuid::parse_str(new_view).expect("uuid"));
        apply_upsert_items_scoped(&mut conn, &[moved], Some(new_view)).expect("re-breadth-sync");

        assert_eq!(
            parent_of(&conn, id),
            new_view,
            "a fresh breadth sync stamping a different view id is a real move"
        );
    }

    /// A Season/Episode's `parent_id` is never a view, so the guard must not pin it the way
    /// it pins a root-level item's view stamp.
    #[test]
    fn non_root_parent_still_takes_the_new_value_from_excluded() {
        let (_dir, mut conn) = open_test_db();
        let view = "e2f5a5f1-1a0b-4b3a-9c2e-0000000000d0";
        let season_a = "d1d1d1d1-1111-2222-3333-444444444444";
        let season_b = "d2d2d2d2-1111-2222-3333-444444444444";
        let id = "e2f5a5f1-1a0b-4b3a-9c2e-000000000022";
        seed_view(&mut conn, view);

        let mut episode = item(id, "Episode 1");
        episode.type_ = Some(BaseItemKind::Episode);
        episode.parent_id = Some(uuid::Uuid::parse_str(season_a).expect("uuid"));
        apply_upsert_items_scoped(&mut conn, &[episode], Some(view)).expect("seed");
        assert_eq!(parent_of(&conn, id), season_a);

        let mut re_seasoned = item(id, "Episode 1");
        re_seasoned.type_ = Some(BaseItemKind::Episode);
        re_seasoned.parent_id = Some(uuid::Uuid::parse_str(season_b).expect("uuid"));
        apply_upsert_items(&mut conn, &[re_seasoned]).expect("plain upsert");

        assert_eq!(
            parent_of(&conn, id),
            season_b,
            "neither side of a Season/Episode parent is ever a view id, so excluded always wins"
        );
    }

    /// A brand-new row (no conflict) must take the raw incoming `parent_id` unconditionally;
    /// the `CASE` guard only applies on `ON CONFLICT`.
    #[test]
    fn brand_new_row_takes_the_raw_parent_id() {
        let (_dir, mut conn) = open_test_db();
        let folder = "e2f5a5f1-1a0b-4b3a-9c2e-0000000000d1";
        let id = "e2f5a5f1-1a0b-4b3a-9c2e-000000000023";

        let mut movie = item(id, "A Movie");
        movie.parent_id = Some(uuid::Uuid::parse_str(folder).expect("uuid"));
        apply_upsert_items(&mut conn, &[movie]).expect("first insert");

        assert_eq!(parent_of(&conn, id), folder);
    }

    #[test]
    fn upsert_then_query_roundtrips() {
        let (_dir, mut conn) = open_test_db();
        let ids = apply_upsert_items(
            &mut conn,
            &[item("e2f5a5f1-1a0b-4b3a-9c2e-000000000001", "A Movie")],
        )
        .expect("upsert");
        assert_eq!(
            ids,
            vec!["e2f5a5f1-1a0b-4b3a-9c2e-000000000001".to_string()]
        );

        let name: String = conn
            .query_row(
                "SELECT name FROM items WHERE id = ?1",
                ["e2f5a5f1-1a0b-4b3a-9c2e-000000000001"],
                |r| r.get(0),
            )
            .expect("row exists");
        assert_eq!(name, "A Movie");
    }

    #[test]
    fn upsert_preserves_rowid_across_updates() {
        let (_dir, mut conn) = open_test_db();
        let id = "e2f5a5f1-1a0b-4b3a-9c2e-000000000001";
        apply_upsert_items(&mut conn, &[item(id, "First")]).expect("insert");
        let rowid1: i64 = conn
            .query_row("SELECT rowid FROM items WHERE id = ?1", [id], |r| r.get(0))
            .expect("rowid");

        apply_upsert_items(&mut conn, &[item(id, "Renamed")]).expect("update");
        let rowid2: i64 = conn
            .query_row("SELECT rowid FROM items WHERE id = ?1", [id], |r| r.get(0))
            .expect("rowid");

        assert_eq!(
            rowid1, rowid2,
            "ON CONFLICT DO UPDATE must not reassign rowid (FTS depends on stable rowid)"
        );
    }

    /// (b): re-upserting a byte-identical DTO must skip the write outright -- no reported
    /// change, `updated_at` untouched.
    #[test]
    fn identical_reupsert_reports_no_change_and_leaves_updated_at() {
        let (_dir, mut conn) = open_test_db();
        let id = "e2f5a5f1-1a0b-4b3a-9c2e-000000000001";
        apply_upsert_items(&mut conn, &[item(id, "A Movie")]).expect("insert");
        let updated_at1: i64 = conn
            .query_row("SELECT updated_at FROM items WHERE id = ?1", [id], |r| {
                r.get(0)
            })
            .expect("updated_at");

        std::thread::sleep(std::time::Duration::from_millis(2));
        let ids =
            apply_upsert_items(&mut conn, &[item(id, "A Movie")]).expect("identical re-upsert");
        let updated_at2: i64 = conn
            .query_row("SELECT updated_at FROM items WHERE id = ?1", [id], |r| {
                r.get(0)
            })
            .expect("updated_at");

        assert!(
            ids.is_empty(),
            "an unchanged row must not be reported as upserted"
        );
        assert_eq!(
            updated_at1, updated_at2,
            "a no-op upsert must not move updated_at"
        );
    }

    /// (b): the no-op skip must not swallow a genuine change -- a re-upsert with a different
    /// DTO still writes and reports the id.
    #[test]
    fn changed_reupsert_still_reports_and_advances_updated_at() {
        let (_dir, mut conn) = open_test_db();
        let id = "e2f5a5f1-1a0b-4b3a-9c2e-000000000001";
        apply_upsert_items(&mut conn, &[item(id, "First")]).expect("insert");
        let updated_at1: i64 = conn
            .query_row("SELECT updated_at FROM items WHERE id = ?1", [id], |r| {
                r.get(0)
            })
            .expect("updated_at");

        std::thread::sleep(std::time::Duration::from_millis(2));
        let ids = apply_upsert_items(&mut conn, &[item(id, "Renamed")]).expect("changed re-upsert");
        let updated_at2: i64 = conn
            .query_row("SELECT updated_at FROM items WHERE id = ?1", [id], |r| {
                r.get(0)
            })
            .expect("updated_at");

        assert_eq!(
            ids,
            vec![id.to_string()],
            "a changed row must still be reported"
        );
        assert!(
            updated_at2 > updated_at1,
            "a genuine change must still advance updated_at"
        );
    }

    /// (b): an unchanged DTO whose `library_id` actually moves must still write, even though
    /// the DTO bytes alone look identical.
    #[test]
    fn identical_dto_with_a_new_library_id_still_reports() {
        let (_dir, mut conn) = open_test_db();
        let id = "e2f5a5f1-1a0b-4b3a-9c2e-000000000001";
        apply_upsert_items_scoped(&mut conn, &[item(id, "A Movie")], Some("library-a"))
            .expect("insert");

        let ids = apply_upsert_items_scoped(&mut conn, &[item(id, "A Movie")], Some("library-b"))
            .expect("re-upsert into a different library");

        assert_eq!(
            ids,
            vec![id.to_string()],
            "a real library_id move must not be skipped as a no-op"
        );
        let library_id: String = conn
            .query_row("SELECT library_id FROM items WHERE id = ?1", [id], |r| {
                r.get(0)
            })
            .expect("library_id");
        assert_eq!(library_id, "library-b");
    }

    #[test]
    fn remove_items_deletes_row() {
        let (_dir, mut conn) = open_test_db();
        let id = "e2f5a5f1-1a0b-4b3a-9c2e-000000000001".to_string();
        apply_upsert_items(&mut conn, &[item(&id, "Doomed")]).expect("insert");
        let removed = apply_remove_items(&mut conn, std::slice::from_ref(&id)).expect("remove");
        assert_eq!(removed, vec![id.clone()]);
        let count: i64 = conn
            .query_row("SELECT COUNT(*) FROM items WHERE id = ?1", [&id], |r| {
                r.get(0)
            })
            .expect("count");
        assert_eq!(count, 0);
    }

    #[test]
    fn remove_cascades_to_children_by_series_and_parent() {
        let (_dir, mut conn) = open_test_db();
        let series_id = "e2f5a5f1-1a0b-4b3a-9c2e-000000000010";
        let season_id = "e2f5a5f1-1a0b-4b3a-9c2e-000000000011";
        let episode_id = "e2f5a5f1-1a0b-4b3a-9c2e-000000000012";

        let series = item(series_id, "The Show");
        let mut season = item(season_id, "Season 1");
        season.series_id = Some(uuid::Uuid::parse_str(series_id).expect("uuid"));
        let mut episode = item(episode_id, "Episode 1");
        episode.series_id = Some(uuid::Uuid::parse_str(series_id).expect("uuid"));
        episode.season_id = Some(uuid::Uuid::parse_str(season_id).expect("uuid"));

        apply_upsert_items(&mut conn, &[series, season, episode]).expect("insert all");

        let removed =
            apply_remove_items(&mut conn, &[series_id.to_string()]).expect("remove series");
        let mut removed_sorted = removed;
        removed_sorted.sort();
        let mut expected = vec![
            series_id.to_string(),
            season_id.to_string(),
            episode_id.to_string(),
        ];
        expected.sort();
        assert_eq!(removed_sorted, expected);

        let count: i64 = conn
            .query_row("SELECT COUNT(*) FROM items", [], |r| r.get(0))
            .expect("count");
        assert_eq!(count, 0);
    }

    /// The cascade lookup's `IN (...)` must chunk a batch past `SQLite`'s bound-parameter
    /// limit instead of building one clause per id.
    #[test]
    fn remove_items_handles_a_batch_over_one_thousand_ids() {
        let (_dir, mut conn) = open_test_db();
        let ids: Vec<String> = (0..1200)
            .map(|i| format!("11111111-1111-1111-1111-{i:012}"))
            .collect();
        let items: Vec<BaseItemDto> = ids.iter().map(|id| item(id, "Bulk")).collect();
        apply_upsert_items(&mut conn, &items).expect("seed 1200 items");

        let removed = apply_remove_items(&mut conn, &ids).expect("remove");
        assert_eq!(removed.len(), 1200);

        let count: i64 = conn
            .query_row("SELECT COUNT(*) FROM items", [], |r| r.get(0))
            .expect("count");
        assert_eq!(count, 0);
    }

    fn item_in_library(id: &str, name: &str) -> BaseItemDto {
        item(id, name)
    }

    #[test]
    fn prune_library_deletes_rows_the_server_no_longer_returns() {
        let (_dir, mut conn) = open_test_db();
        let library_id = "e2f5a5f1-1a0b-4b3a-9c2e-0000000000a0";
        let keep_id = "e2f5a5f1-1a0b-4b3a-9c2e-0000000000a1".to_string();
        let stale_id = "e2f5a5f1-1a0b-4b3a-9c2e-0000000000a2".to_string();

        apply_upsert_items_scoped(
            &mut conn,
            &[
                item_in_library(&keep_id, "Kept"),
                item_in_library(&stale_id, "Orphaned virtual placeholder"),
            ],
            Some(library_id),
        )
        .expect("seed items");

        let removed = apply_prune_library(
            &mut conn,
            library_id,
            std::slice::from_ref(&keep_id),
            i64::MAX,
        )
        .expect("prune");
        assert_eq!(removed, vec![stale_id.clone()]);

        let ids: Vec<String> = {
            let mut stmt = conn
                .prepare("SELECT id FROM items ORDER BY id")
                .expect("prep");
            stmt.query_map([], |r| r.get(0))
                .expect("query")
                .collect::<rusqlite::Result<_>>()
                .expect("rows")
        };
        assert_eq!(ids, vec![keep_id]);
    }

    /// docs/12: a `LibraryChanged` insert that lands after the sweep's id enumeration began
    /// but before this prune call must survive, even though the (already-complete)
    /// enumeration snapshot in `keep_ids` never saw it.
    #[test]
    fn prune_library_keeps_a_row_inserted_after_the_sweep_began() {
        let (_dir, mut conn) = open_test_db();
        let library_id = "e2f5a5f1-1a0b-4b3a-9c2e-0000000000f0";
        let stale_id = "e2f5a5f1-1a0b-4b3a-9c2e-0000000000f1".to_string();
        let new_id = "e2f5a5f1-1a0b-4b3a-9c2e-0000000000f2".to_string();

        apply_upsert_items_scoped(
            &mut conn,
            &[item_in_library(&stale_id, "Stale")],
            Some(library_id),
        )
        .expect("seed stale");

        // Snapshot the mark a real sweep takes (`max_item_rowid`) before it starts enumerating.
        let since_rowid: i64 = conn
            .query_row("SELECT COALESCE(MAX(rowid), 0) FROM items", [], |r| {
                r.get(0)
            })
            .expect("max rowid");

        // A `LibraryChanged` insert landing mid-sweep gets a rowid past the mark.
        apply_upsert_items_scoped(
            &mut conn,
            &[item_in_library(&new_id, "New")],
            Some(library_id),
        )
        .expect("seed a mid-sweep insert");

        // The (already-complete) enumeration snapshot lists neither id: the server no longer
        // has the stale one, and the new one arrived too late to be seen; the new row must
        // still survive despite being absent too.
        let removed = apply_prune_library(&mut conn, library_id, &[], since_rowid).expect("prune");
        assert_eq!(removed, vec![stale_id]);

        let remaining: Vec<String> = {
            let mut stmt = conn
                .prepare("SELECT id FROM items ORDER BY id")
                .expect("prep");
            stmt.query_map([], |r| r.get(0))
                .expect("query")
                .collect::<rusqlite::Result<_>>()
                .expect("rows")
        };
        assert_eq!(remaining, vec![new_id]);
    }

    /// A virtual placeholder deleted server-side and replaced with a new id (real file
    /// scanned in): both rows coexist locally until a resync's `keep_ids` prunes the dead one.
    #[test]
    fn prune_library_converges_a_virtual_to_real_id_swap() {
        let (_dir, mut conn) = open_test_db();
        let library_id = "e2f5a5f1-1a0b-4b3a-9c2e-0000000000b0";
        let old_virtual_id = "e2f5a5f1-1a0b-4b3a-9c2e-0000000000b1".to_string();
        let new_real_id = "e2f5a5f1-1a0b-4b3a-9c2e-0000000000b2".to_string();

        let mut virtual_ep = item(&old_virtual_id, "S01E01");
        virtual_ep.location_type = Some(jellyfin_api::models::LocationType::Virtual);
        apply_upsert_items_scoped(&mut conn, &[virtual_ep], Some(library_id))
            .expect("seed virtual placeholder");

        // The breadth fetch lands the new real item (different id, doesn't overwrite the old
        // row), then prunes with its complete id set, which excludes the dead virtual id.
        apply_upsert_items_scoped(&mut conn, &[item(&new_real_id, "S01E01")], Some(library_id))
            .expect("upsert real item");
        let removed = apply_prune_library(
            &mut conn,
            library_id,
            std::slice::from_ref(&new_real_id),
            i64::MAX,
        )
        .expect("prune");
        assert_eq!(removed, vec![old_virtual_id.clone()]);

        let (count, only_id, is_virtual): (i64, String, bool) = conn
            .query_row(
                "SELECT COUNT(*), MIN(id), MIN(is_virtual) FROM items",
                [],
                |r| Ok((r.get(0)?, r.get(1)?, r.get::<_, i64>(2)? != 0)),
            )
            .expect("row");
        assert_eq!(
            count, 1,
            "old virtual row must be gone, only the real one remains"
        );
        assert_eq!(only_id, new_real_id);
        assert!(
            !is_virtual,
            "surviving row must be the real (non-virtual) item"
        );
    }

    #[test]
    fn prune_library_is_scoped_to_one_library_id() {
        let (_dir, mut conn) = open_test_db();
        let library_a = "e2f5a5f1-1a0b-4b3a-9c2e-0000000000c0";
        let library_b = "e2f5a5f1-1a0b-4b3a-9c2e-0000000000c1";
        let item_a = "e2f5a5f1-1a0b-4b3a-9c2e-0000000000c2".to_string();
        let item_b = "e2f5a5f1-1a0b-4b3a-9c2e-0000000000c3".to_string();

        apply_upsert_items_scoped(&mut conn, &[item_in_library(&item_a, "A")], Some(library_a))
            .expect("seed a");
        apply_upsert_items_scoped(&mut conn, &[item_in_library(&item_b, "B")], Some(library_b))
            .expect("seed b");

        // Pruning library A must delete item_a but never touch library B's row.
        let removed = apply_prune_library(&mut conn, library_a, &[], i64::MAX).expect("prune a");
        assert_eq!(removed, vec![item_a]);

        let remaining: Vec<String> = {
            let mut stmt = conn.prepare("SELECT id FROM items").expect("prep");
            stmt.query_map([], |r| r.get(0))
                .expect("query")
                .collect::<rusqlite::Result<_>>()
                .expect("rows")
        };
        assert_eq!(
            remaining,
            vec![item_b],
            "library B's row must survive untouched"
        );
    }

    #[test]
    fn prune_library_cleans_up_fts_and_collection_membership() {
        let (_dir, mut conn) = open_test_db();
        let library_id = "e2f5a5f1-1a0b-4b3a-9c2e-0000000000d0";
        let stale_id = "e2f5a5f1-1a0b-4b3a-9c2e-0000000000d1".to_string();

        apply_upsert_items_scoped(
            &mut conn,
            &[item_in_library(&stale_id, "Quantum Static")],
            Some(library_id),
        )
        .expect("seed");
        apply_set_collection_members(&mut conn, "some-boxset", &[(stale_id.clone(), 0)])
            .expect("membership");

        apply_prune_library(&mut conn, library_id, &[], i64::MAX).expect("prune");

        let hits: i64 = conn
            .query_row(
                "SELECT COUNT(*) FROM search WHERE search MATCH 'Quantum'",
                [],
                |r| r.get(0),
            )
            .expect("fts query");
        assert_eq!(hits, 0, "pruned row's FTS postings must be removed");

        let members = member_ids(&conn, "some-boxset");
        assert!(
            members.is_empty(),
            "pruned item's collection membership rows must be removed too"
        );
    }

    #[test]
    fn prune_library_with_no_stale_rows_is_a_noop() {
        let (_dir, mut conn) = open_test_db();
        let library_id = "e2f5a5f1-1a0b-4b3a-9c2e-0000000000e0";
        let id = "e2f5a5f1-1a0b-4b3a-9c2e-0000000000e1".to_string();
        apply_upsert_items_scoped(
            &mut conn,
            &[item_in_library(&id, "Still There")],
            Some(library_id),
        )
        .expect("seed");

        let removed =
            apply_prune_library(&mut conn, library_id, std::slice::from_ref(&id), i64::MAX)
                .expect("prune");
        assert!(removed.is_empty());

        let count: i64 = conn
            .query_row("SELECT COUNT(*) FROM items", [], |r| r.get(0))
            .expect("count");
        assert_eq!(count, 1);
    }

    #[test]
    fn set_favorite_ids_flips_only_the_differences() {
        let (_dir, mut conn) = open_test_db();
        let ids: Vec<String> = (1..=3)
            .map(|n| format!("e2f5a5f1-1a0b-4b3a-9c2e-00000000000{n}"))
            .collect();
        let items: Vec<_> = ids.iter().map(|id| item(id, "Movie")).collect();
        apply_upsert_items(&mut conn, &items).expect("insert");
        apply_set_favorite_ids(&mut conn, &ids[..2], i64::MAX).expect("seed favorites");

        let mut flipped =
            apply_set_favorite_ids(&mut conn, &ids[1..], i64::MAX).expect("apply favorites");
        flipped.sort();
        assert_eq!(flipped, vec![ids[0].clone(), ids[2].clone()]);
        let favorites: Vec<String> = conn
            .prepare("SELECT id FROM items WHERE is_favorite = 1 ORDER BY id")
            .expect("prepare")
            .query_map([], |r| r.get(0))
            .expect("query")
            .collect::<rusqlite::Result<_>>()
            .expect("rows");
        assert_eq!(favorites, ids[1..].to_vec());
        assert!(
            apply_set_favorite_ids(&mut conn, &ids[1..], i64::MAX)
                .expect("repeat")
                .is_empty(),
            "an unchanged list must flip nothing"
        );
    }

    #[test]
    fn apply_user_data_patches_only_present_fields() {
        let (_dir, mut conn) = open_test_db();
        let id = "e2f5a5f1-1a0b-4b3a-9c2e-000000000001".to_string();
        apply_upsert_items(&mut conn, &[item(&id, "Movie")]).expect("insert");

        let update = UserItemDataDto {
            played: Some(true),
            playback_position_ticks: None, // must NOT clobber existing value
            play_count: Some(1),
            is_favorite: None,
            unplayed_item_count: None,
            key: Some("k".to_string()),
            item_id: None,
            last_played_date: None,
            likes: None,
            played_percentage: None,
            rating: None,
        };
        // Seed a nonzero position first via a direct upsert-with-userdata.
        let mut with_pos = item(&id, "Movie");
        with_pos.user_data = Some(UserItemDataDto {
            played: Some(false),
            playback_position_ticks: Some(555),
            play_count: Some(0),
            is_favorite: Some(false),
            unplayed_item_count: None,
            key: Some("k".to_string()),
            item_id: None,
            last_played_date: None,
            likes: None,
            played_percentage: None,
            rating: None,
        });
        apply_upsert_items(&mut conn, &[with_pos]).expect("seed position");

        let touched = apply_user_data(&mut conn, &[(id.clone(), update)]).expect("apply");
        assert_eq!(touched, vec![id.clone()]);

        let (played, pos, play_count): (bool, i64, i64) = conn
            .query_row(
                "SELECT played, playback_position_ticks, play_count FROM items WHERE id = ?1",
                [&id],
                |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
            )
            .expect("row");
        assert!(played);
        assert_eq!(pos, 555, "None field must leave existing value untouched");
        assert_eq!(play_count, 1);
    }

    #[test]
    fn apply_user_data_on_unknown_item_is_a_noop() {
        let (_dir, mut conn) = open_test_db();
        let update = UserItemDataDto {
            played: Some(true),
            playback_position_ticks: None,
            play_count: None,
            is_favorite: None,
            unplayed_item_count: None,
            key: Some("k".to_string()),
            item_id: None,
            last_played_date: None,
            likes: None,
            played_percentage: None,
            rating: None,
        };
        let touched =
            apply_user_data(&mut conn, &[("nonexistent".to_string(), update)]).expect("apply");
        assert!(touched.is_empty());
    }

    #[test]
    fn fts_search_finds_and_forgets_renamed_items() {
        let (_dir, mut conn) = open_test_db();
        let id = "e2f5a5f1-1a0b-4b3a-9c2e-000000000001".to_string();
        apply_upsert_items(&mut conn, &[item(&id, "Quantum Static")]).expect("insert");

        let hits: i64 = conn
            .query_row(
                "SELECT COUNT(*) FROM search WHERE search MATCH 'Quantum'",
                [],
                |r| r.get(0),
            )
            .expect("match old name");
        assert_eq!(hits, 1);

        apply_upsert_items(&mut conn, &[item(&id, "Glass Horizon")]).expect("rename");

        let old_hits: i64 = conn
            .query_row(
                "SELECT COUNT(*) FROM search WHERE search MATCH 'Quantum'",
                [],
                |r| r.get(0),
            )
            .expect("old name gone");
        assert_eq!(
            old_hits, 0,
            "renamed item must no longer match its old text"
        );

        let new_hits: i64 = conn
            .query_row(
                "SELECT COUNT(*) FROM search WHERE search MATCH 'Glass'",
                [],
                |r| r.get(0),
            )
            .expect("match new name");
        assert_eq!(new_hits, 1);
    }

    #[test]
    fn fts_removed_on_item_removal() {
        let (_dir, mut conn) = open_test_db();
        let id = "e2f5a5f1-1a0b-4b3a-9c2e-000000000001".to_string();
        apply_upsert_items(&mut conn, &[item(&id, "Quantum Static")]).expect("insert");
        apply_remove_items(&mut conn, &[id]).expect("remove");

        let hits: i64 = conn
            .query_row(
                "SELECT COUNT(*) FROM search WHERE search MATCH 'Quantum'",
                [],
                |r| r.get(0),
            )
            .expect("query");
        assert_eq!(hits, 0);
    }

    fn member_ids(conn: &Connection, collection_id: &str) -> Vec<String> {
        let mut stmt = conn
            .prepare("SELECT item_id FROM collection_members WHERE collection_id = ?1 ORDER BY sort_index")
            .expect("prepare");
        stmt.query_map([collection_id], |r| r.get(0))
            .expect("query")
            .collect::<rusqlite::Result<Vec<_>>>()
            .expect("rows")
    }

    #[test]
    fn set_collection_members_replaces_prior_membership() {
        let (_dir, mut conn) = open_test_db();
        let boxset = "e2f5a5f1-1a0b-4b3a-9c2e-0000000000b0";
        apply_set_collection_members(
            &mut conn,
            boxset,
            &[("a".to_string(), 0), ("b".to_string(), 1)],
        )
        .expect("first set");
        assert_eq!(member_ids(&conn, boxset), vec!["a", "b"]);

        // The second call is the full authoritative list, not a delta.
        apply_set_collection_members(
            &mut conn,
            boxset,
            &[("c".to_string(), 0), ("b".to_string(), 1)],
        )
        .expect("second set");
        assert_eq!(member_ids(&conn, boxset), vec!["c", "b"]);
    }

    #[test]
    fn removing_a_boxset_drops_its_membership_rows() {
        let (_dir, mut conn) = open_test_db();
        let boxset_id = "e2f5a5f1-1a0b-4b3a-9c2e-0000000000b1".to_string();
        let mut boxset = item(&boxset_id, "A Collection");
        boxset.type_ = Some(BaseItemKind::BoxSet);
        apply_upsert_items(&mut conn, &[boxset]).expect("insert boxset");
        apply_set_collection_members(&mut conn, &boxset_id, &[("member-1".to_string(), 0)])
            .expect("set members");

        apply_remove_items(&mut conn, std::slice::from_ref(&boxset_id)).expect("remove boxset");

        assert!(member_ids(&conn, &boxset_id).is_empty());
    }

    #[test]
    fn removing_a_member_item_drops_its_membership_row() {
        let (_dir, mut conn) = open_test_db();
        let boxset_id = "e2f5a5f1-1a0b-4b3a-9c2e-0000000000b2".to_string();
        let member_id = "e2f5a5f1-1a0b-4b3a-9c2e-0000000000b3".to_string();
        apply_upsert_items(&mut conn, &[item(&member_id, "A Movie")]).expect("insert member");
        apply_set_collection_members(&mut conn, &boxset_id, &[(member_id.clone(), 0)])
            .expect("set members");

        apply_remove_items(&mut conn, &[member_id]).expect("remove member");

        assert!(member_ids(&conn, &boxset_id).is_empty());
    }

    // ---- Resume-refresh data-loss fix: `UPSERT_ITEM_SQL`'s COALESCE guard on
    // `played`/`playback_position_ticks`/`last_played_date` -------------------

    fn item_with_user_data(
        id: &str,
        name: &str,
        played: bool,
        position_ticks: i64,
        last_played_date: Option<&str>,
    ) -> BaseItemDto {
        let mut dto = item(id, name);
        dto.user_data = Some(jellyfin_api::models::UserItemDataDto {
            played: Some(played),
            playback_position_ticks: Some(position_ticks),
            play_count: None,
            is_favorite: None,
            unplayed_item_count: None,
            key: Some("k".to_string()),
            item_id: None,
            last_played_date: last_played_date.map(|d| d.parse().expect("rfc3339 timestamp")),
            likes: None,
            played_percentage: None,
            rating: None,
        });
        dto
    }

    fn stored_watch_state(conn: &Connection, id: &str) -> (bool, i64, Option<String>) {
        conn.query_row(
            "SELECT played, playback_position_ticks, last_played_date FROM items WHERE id = ?1",
            [id],
            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
        )
        .expect("row exists")
    }

    /// Round-trips an RFC3339 instant through `chrono` so tests assert against the real
    /// serialized form instead of guessing `to_rfc3339()`'s exact output.
    fn rfc3339_roundtrip(s: &str) -> String {
        s.parse::<chrono::DateTime<chrono::Utc>>()
            .expect("rfc3339 timestamp")
            .to_rfc3339()
    }

    /// A re-upsert from a DTO with no `UserData` at all (a field-less endpoint response)
    /// must not blank an already-known watch state.
    #[test]
    fn reupsert_without_user_data_preserves_watch_state() {
        let (_dir, mut conn) = open_test_db();
        let id = "e2f5a5f1-1a0b-4b3a-9c2e-0000000000c0";
        let seeded_date = rfc3339_roundtrip("2026-01-01T00:00:00Z");
        apply_upsert_items(
            &mut conn,
            &[item_with_user_data(
                id,
                "A Movie",
                true,
                123_456,
                Some("2026-01-01T00:00:00Z"),
            )],
        )
        .expect("seed with user data");
        assert_eq!(
            stored_watch_state(&conn, id),
            (true, 123_456, Some(seeded_date.clone()))
        );

        apply_upsert_items(&mut conn, &[item(id, "A Movie")]).expect("re-upsert without user data");

        assert_eq!(
            stored_watch_state(&conn, id),
            (true, 123_456, Some(seeded_date)),
            "a UserData-less re-upsert must preserve the existing watch state, \
             not blank it back to unplayed/position 0/no last-played date"
        );
    }

    /// The opposite direction: a re-upsert whose DTO DOES carry `UserData` is still fully
    /// authoritative and must overwrite, including flipping a played item back to unplayed.
    #[test]
    fn reupsert_with_user_data_overwrites_watch_state() {
        let (_dir, mut conn) = open_test_db();
        let id = "e2f5a5f1-1a0b-4b3a-9c2e-0000000000c1";
        apply_upsert_items(
            &mut conn,
            &[item_with_user_data(
                id,
                "A Movie",
                true,
                123_456,
                Some("2026-01-01T00:00:00Z"),
            )],
        )
        .expect("seed with user data");

        let updated_date = rfc3339_roundtrip("2026-02-02T00:00:00Z");
        apply_upsert_items(
            &mut conn,
            &[item_with_user_data(
                id,
                "A Movie",
                false,
                0,
                Some("2026-02-02T00:00:00Z"),
            )],
        )
        .expect("re-upsert with fresh user data");

        assert_eq!(
            stored_watch_state(&conn, id),
            (false, 0, Some(updated_date)),
            "a re-upsert whose DTO carries UserData must still win outright"
        );
    }

    /// A brand-new row with no `UserData` must still insert cleanly, falling back to the
    /// `NOT NULL DEFAULT 0` columns' concrete defaults.
    #[test]
    fn fresh_insert_without_user_data_defaults_cleanly() {
        let (_dir, mut conn) = open_test_db();
        let id = "e2f5a5f1-1a0b-4b3a-9c2e-0000000000c2";
        apply_upsert_items(&mut conn, &[item(id, "A Movie")]).expect("insert without user data");
        assert_eq!(stored_watch_state(&conn, id), (false, 0, None));
    }

    // ---- `apply_local_user_data` / `resolve_played_position` ----

    fn item_with_runtime(id: &str, name: &str, runtime_ticks: i64) -> BaseItemDto {
        let mut it = item(id, name);
        it.run_time_ticks = Some(runtime_ticks);
        it
    }

    #[test]
    fn resolve_played_position_below_threshold_keeps_position_and_played_flag() {
        // Below threshold, no override: an ordinary progress tick must not un-mark a
        // rewatch (current played = true) as unplayed.
        let (pos, played) = resolve_played_position(50, None, Some(100), true);
        assert_eq!(pos, 50);
        assert!(
            played,
            "None override must preserve the existing played flag"
        );
    }

    #[test]
    fn resolve_played_position_crossing_ninety_percent_marks_played_and_zeroes_position() {
        let (pos, played) = resolve_played_position(91, None, Some(100), false);
        assert_eq!(pos, 0);
        assert!(played);
    }

    #[test]
    fn resolve_played_position_just_under_threshold_stays_unplayed() {
        let (pos, played) = resolve_played_position(89, None, Some(100), false);
        assert_eq!(pos, 89);
        assert!(!played);
    }

    #[test]
    fn resolve_played_position_explicit_true_override_forces_played_even_early() {
        // EOF report: caller knows playback ended regardless of the threshold math.
        let (pos, played) = resolve_played_position(10, Some(true), Some(100), false);
        assert_eq!(pos, 0);
        assert!(played);
    }

    #[test]
    fn resolve_played_position_unknown_runtime_only_honors_explicit_override() {
        let (pos, played) = resolve_played_position(999_999, None, None, false);
        assert_eq!(
            pos, 999_999,
            "no runtime to compare against -- pass position through"
        );
        assert!(!played);
    }

    #[test]
    fn apply_local_user_data_updates_existing_item() {
        let (_dir, mut conn) = open_test_db();
        let id = "e2f5a5f1-1a0b-4b3a-9c2e-000000000001".to_string();
        apply_upsert_items(&mut conn, &[item_with_runtime(&id, "Movie", 1_000)]).expect("insert");

        let touched = apply_local_user_data(&mut conn, &id, 500, None).expect("apply");
        assert_eq!(touched, Some(id.clone()));

        let (pos, played): (i64, bool) = conn
            .query_row(
                "SELECT playback_position_ticks, played FROM items WHERE id = ?1",
                [&id],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )
            .expect("row");
        assert_eq!(pos, 500);
        assert!(!played);
    }

    #[test]
    fn apply_local_user_data_crossing_threshold_resets_position_in_db() {
        let (_dir, mut conn) = open_test_db();
        let id = "e2f5a5f1-1a0b-4b3a-9c2e-000000000002".to_string();
        apply_upsert_items(&mut conn, &[item_with_runtime(&id, "Movie", 1_000)]).expect("insert");

        apply_local_user_data(&mut conn, &id, 950, None).expect("apply");

        let (pos, played): (i64, bool) = conn
            .query_row(
                "SELECT playback_position_ticks, played FROM items WHERE id = ?1",
                [&id],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )
            .expect("row");
        assert_eq!(pos, 0);
        assert!(played);
    }

    #[test]
    fn apply_local_user_data_at_eof_forces_played() {
        let (_dir, mut conn) = open_test_db();
        let id = "e2f5a5f1-1a0b-4b3a-9c2e-000000000003".to_string();
        apply_upsert_items(&mut conn, &[item_with_runtime(&id, "Movie", 1_000)]).expect("insert");

        apply_local_user_data(&mut conn, &id, 1_000, Some(true)).expect("apply");

        let (pos, played): (i64, bool) = conn
            .query_row(
                "SELECT playback_position_ticks, played FROM items WHERE id = ?1",
                [&id],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )
            .expect("row");
        assert_eq!(pos, 0);
        assert!(played);
    }

    /// `apply_local_user_data` has no ceiling on `position_ticks` relative to the item's own
    /// runtime, so a stale position belonging to a different, longer item silently marks
    /// this one fully played.
    #[test]
    fn apply_local_user_data_with_a_position_past_runtime_marks_the_item_played() {
        let (_dir, mut conn) = open_test_db();
        let id = "e2f5a5f1-1a0b-4b3a-9c2e-000000000701".to_string();
        // 24 minutes.
        let dto = item_with_runtime(&id, "Short Episode", 24 * 60 * 10_000_000);
        apply_upsert_items(&mut conn, &[dto]).expect("insert");

        // A position belonging to a 25-minute watch of some other, longer item.
        apply_local_user_data(&mut conn, &id, 25 * 60 * 10_000_000, None).expect("apply");

        let (pos, played): (i64, bool) = conn
            .query_row(
                "SELECT playback_position_ticks, played FROM items WHERE id = ?1",
                [&id],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )
            .expect("row");
        assert_eq!(
            pos, 0,
            "position collapses to 0 once the threshold is crossed"
        );
        assert!(
            played,
            "an out-of-range position silently marks a never-watched item played"
        );
    }

    /// A zeroed position with no override leaves a never-watched item unplayed at 0.
    #[test]
    fn apply_local_user_data_with_a_zeroed_counter_does_not_mark_the_item_played() {
        let (_dir, mut conn) = open_test_db();
        let id = "e2f5a5f1-1a0b-4b3a-9c2e-000000000702".to_string();
        let dto = item_with_runtime(&id, "Unwatched Episode", 24 * 60 * 10_000_000);
        apply_upsert_items(&mut conn, &[dto]).expect("insert");
        apply_local_user_data(&mut conn, &id, 0, None).expect("apply");

        let (pos, played): (i64, bool) = conn
            .query_row(
                "SELECT playback_position_ticks, played FROM items WHERE id = ?1",
                [&id],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )
            .expect("row");
        assert_eq!(pos, 0, "a zeroed counter writes position 0");
        assert!(
            !played,
            "a zeroed position must NOT mark a never-watched item played"
        );
    }

    #[test]
    fn apply_local_user_data_on_unknown_item_is_a_noop() {
        let (_dir, mut conn) = open_test_db();
        let touched = apply_local_user_data(&mut conn, "nonexistent", 100, None).expect("apply");
        assert_eq!(touched, None);
    }

    // ---- Clock-skew clamp: `clamp_local_last_played_date` / `apply_local_user_data` ----

    fn dt(s: &str) -> chrono::DateTime<chrono::Utc> {
        s.parse().expect("rfc3339 timestamp")
    }

    #[test]
    fn clamp_defers_to_now_when_no_existing_date() {
        let now = dt("2026-06-01T00:00:00Z");
        assert_eq!(clamp_local_last_played_date(None, now), now.to_rfc3339());
    }

    #[test]
    fn clamp_defers_to_now_when_existing_is_older() {
        let now = dt("2026-06-01T00:00:00Z");
        let existing = dt("2026-01-01T00:00:00Z").to_rfc3339();
        assert_eq!(
            clamp_local_last_played_date(Some(&existing), now),
            now.to_rfc3339()
        );
    }

    /// A local clock running behind the server's must not regress an already-known, newer
    /// `last_played_date`.
    #[test]
    fn clamp_preserves_existing_when_it_is_newer_than_now() {
        let now = dt("2026-01-01T00:00:00Z");
        let existing = dt("2026-06-01T00:00:00Z").to_rfc3339();
        assert_eq!(
            clamp_local_last_played_date(Some(&existing), now),
            existing,
            "a newer existing last_played_date must not be regressed by an older local now"
        );
    }

    #[test]
    fn clamp_defers_to_now_when_existing_is_unparseable() {
        let now = dt("2026-06-01T00:00:00Z");
        assert_eq!(
            clamp_local_last_played_date(Some("not a date"), now),
            now.to_rfc3339()
        );
    }

    /// Integration version of `clamp_preserves_existing_when_it_is_newer_than_now` through
    /// the real `apply_local_user_data` path.
    #[test]
    fn apply_local_user_data_does_not_regress_a_newer_existing_last_played_date() {
        let (_dir, mut conn) = open_test_db();
        let id = "e2f5a5f1-1a0b-4b3a-9c2e-0000000000c3";
        apply_upsert_items(
            &mut conn,
            &[item_with_user_data(
                id,
                "A Movie",
                false,
                0,
                // Far enough in the future to be newer than the real `Utc::now()`.
                Some("2999-01-01T00:00:00Z"),
            )],
        )
        .expect("seed with a future last_played_date");

        apply_local_user_data(&mut conn, id, 500, None).expect("apply local report");

        let (_, _, stored_date) = stored_watch_state(&conn, id);
        assert_eq!(
            stored_date,
            Some(rfc3339_roundtrip("2999-01-01T00:00:00Z")),
            "a local playback report must not regress a pre-existing, newer last_played_date"
        );
    }

    // ---- docs/16-library-sort-filter.md §1: item_genres / series_status ----

    fn item_with_genres(id: &str, name: &str, genres: &[&str]) -> BaseItemDto {
        let mut dto = item(id, name);
        dto.genres = genres.iter().map(|g| g.to_string()).collect();
        dto
    }

    fn item_with_status(id: &str, name: &str, status: &str) -> BaseItemDto {
        let mut dto = item(id, name);
        dto.status = Some(status.to_string());
        dto
    }

    fn genres_of(conn: &Connection, item_id: &str) -> Vec<String> {
        let mut stmt = conn
            .prepare("SELECT genre FROM item_genres WHERE item_id = ?1 ORDER BY genre")
            .expect("prepare");
        stmt.query_map([item_id], |r| r.get(0))
            .expect("query")
            .collect::<rusqlite::Result<Vec<_>>>()
            .expect("rows")
    }

    /// §1.2: a DTO with non-empty `Genres` replaces the item's genre set outright, not merges.
    #[test]
    fn genre_upsert_replaces_the_prior_set_on_non_empty_genres() {
        let (_dir, mut conn) = open_test_db();
        let id = "e2f5a5f1-1a0b-4b3a-9c2e-0000000000f0";
        apply_upsert_items(&mut conn, &[item_with_genres(id, "Movie", &["Drama"])]).expect("seed");
        assert_eq!(genres_of(&conn, id), vec!["Drama".to_string()]);

        apply_upsert_items(
            &mut conn,
            &[item_with_genres(id, "Movie", &["Comedy", "Action"])],
        )
        .expect("re-upsert with a different, non-empty genre set");

        assert_eq!(
            genres_of(&conn, id),
            vec!["Action".to_string(), "Comedy".to_string()],
            "a non-empty Genres must replace the prior set, not merge with it"
        );
    }

    /// §1.2's write rule, empty case: unlike `played`/`library_id`, `Genres`
    /// needs no authoritative-when-present guard, because every persisting
    /// fetch requests it (`sync::item_fields()`) -- so an empty `genres`
    /// means the server genuinely has none now, and a genre removed
    /// server-side must not survive as a stale row forever.
    #[test]
    fn genre_upsert_clears_the_prior_set_on_empty_genres() {
        let (_dir, mut conn) = open_test_db();
        let id = "e2f5a5f1-1a0b-4b3a-9c2e-0000000000f1";
        apply_upsert_items(&mut conn, &[item_with_genres(id, "Movie", &["Drama"])]).expect("seed");

        apply_upsert_items(&mut conn, &[item(id, "Movie")])
            .expect("re-upsert with no genres at all");

        assert!(
            genres_of(&conn, id).is_empty(),
            "an empty Genres must clear the existing genre set, since every \
             persisting fetch requests Genres"
        );
    }

    /// A DTO repeating the same genre string twice must not fail the upsert
    /// transaction (`ON CONFLICT DO NOTHING` on the insert loop).
    #[test]
    fn genre_upsert_tolerates_a_duplicate_genre_string() {
        let (_dir, mut conn) = open_test_db();
        let id = "e2f5a5f1-1a0b-4b3a-9c2e-0000000000f2";
        apply_upsert_items(
            &mut conn,
            &[item_with_genres(id, "Movie", &["Drama", "Drama"])],
        )
        .expect("seed with a duplicated genre string");
        assert_eq!(genres_of(&conn, id), vec!["Drama".to_string()]);
    }

    /// §1.2: deleting an item deletes its genre rows -- `apply_remove_items`.
    #[test]
    fn genre_rows_are_deleted_when_remove_items_deletes_the_item() {
        let (_dir, mut conn) = open_test_db();
        let id = "e2f5a5f1-1a0b-4b3a-9c2e-0000000000f3".to_string();
        apply_upsert_items(&mut conn, &[item_with_genres(&id, "Movie", &["Drama"])]).expect("seed");
        apply_remove_items(&mut conn, std::slice::from_ref(&id)).expect("remove");
        assert!(genres_of(&conn, &id).is_empty());
    }

    /// §1.2: same cascade, via `apply_prune_library`'s delete path.
    #[test]
    fn genre_rows_are_deleted_when_prune_library_deletes_the_item() {
        let (_dir, mut conn) = open_test_db();
        let library_id = "e2f5a5f1-1a0b-4b3a-9c2e-0000000000f4";
        let id = "e2f5a5f1-1a0b-4b3a-9c2e-0000000000f5".to_string();
        apply_upsert_items_scoped(
            &mut conn,
            &[item_with_genres(&id, "Movie", &["Drama"])],
            Some(library_id),
        )
        .expect("seed");
        apply_prune_library(&mut conn, library_id, &[], i64::MAX).expect("prune");
        assert!(genres_of(&conn, &id).is_empty());
    }

    /// §1.2: same cascade, via `apply_replace_views`'s revoked-library delete path.
    #[test]
    fn genre_rows_are_deleted_when_replace_views_revokes_the_library() {
        let (_dir, mut conn) = open_test_db();
        apply_replace_views(
            &mut conn,
            &[ViewRow {
                id: "movies".into(),
                name: "Movies".into(),
                collection_type: "movies".into(),
                item_type: "CollectionFolder".into(),
            }],
        )
        .expect("seed views");
        let id = "e2f5a5f1-1a0b-4b3a-9c2e-0000000000f6".to_string();
        apply_upsert_items_scoped(
            &mut conn,
            &[item_with_genres(&id, "Movie", &["Drama"])],
            Some("movies"),
        )
        .expect("seed item");

        // Revoke "movies" from the incoming snapshot.
        apply_replace_views(&mut conn, &[]).expect("replace with an empty snapshot");

        assert!(genres_of(&conn, &id).is_empty());
    }

    /// §1: `series_status` needs no authoritative-when-present guard; a fresh value always
    /// overwrites, same as `overview`/`is_virtual`.
    #[test]
    fn series_status_is_stored_and_overwritten_on_reupsert() {
        let (_dir, mut conn) = open_test_db();
        let id = "e2f5a5f1-1a0b-4b3a-9c2e-0000000000f7";
        apply_upsert_items(&mut conn, &[item_with_status(id, "A Show", "Continuing")])
            .expect("seed");
        let stored: Option<String> = conn
            .query_row("SELECT series_status FROM items WHERE id = ?1", [id], |r| {
                r.get(0)
            })
            .expect("row");
        assert_eq!(stored.as_deref(), Some("Continuing"));

        apply_upsert_items(&mut conn, &[item_with_status(id, "A Show", "Ended")])
            .expect("re-upsert with a fresh status");
        let stored: Option<String> = conn
            .query_row("SELECT series_status FROM items WHERE id = ?1", [id], |r| {
                r.get(0)
            })
            .expect("row");
        assert_eq!(stored.as_deref(), Some("Ended"));
    }
}
