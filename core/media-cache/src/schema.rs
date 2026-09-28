//! SQLite mirror schema: WAL mode, `schema_version` in `meta`
//! checked at open, drop-and-rebuild on mismatch (disposable cache, never a migration target).

use std::path::Path;

use rusqlite::Connection;

use crate::{CacheError, SCHEMA_VERSION};

/// DDL for the mirror; `CREATE ... IF NOT EXISTS` throughout so it can run unconditionally on
/// (re)open.
pub(crate) const SCHEMA_SQL: &str = r#"
CREATE TABLE IF NOT EXISTS meta (
    key TEXT PRIMARY KEY,
    value TEXT
);

CREATE TABLE IF NOT EXISTS views (
    id TEXT PRIMARY KEY,
    name TEXT,
    collection_type TEXT,
    sort_index INTEGER,
    -- Schema v11: the `/UserViews` entry's own `Type`, distinct from `collection_type`
    -- above; `sync::current_views` excludes `'Channel'` rows (browsed live instead).
    item_type TEXT
);

CREATE TABLE IF NOT EXISTS items (
    id TEXT PRIMARY KEY,
    parent_id TEXT,
    series_id TEXT,
    season_id TEXT,
    item_type TEXT NOT NULL,
    name TEXT,
    sort_name TEXT,
    index_number INTEGER,
    parent_index_number INTEGER,
    production_year INTEGER,
    premiere_date TEXT,
    runtime_ticks INTEGER,
    date_created TEXT,
    played INTEGER NOT NULL DEFAULT 0,
    playback_position_ticks INTEGER NOT NULL DEFAULT 0,
    play_count INTEGER NOT NULL DEFAULT 0,
    is_favorite INTEGER NOT NULL DEFAULT 0,
    unplayed_item_count INTEGER,
    primary_tag TEXT,
    backdrop_tag TEXT,
    thumb_tag TEXT,
    primary_blurhash TEXT,
    -- Ancestor-image fallback columns (docs/07 §2) so cards/detail can fall back to series
    -- poster/parent backdrop. See `rows::extract_columns`.
    series_primary_tag TEXT,
    parent_backdrop_item_id TEXT,
    parent_backdrop_tag TEXT,
    -- Owning library (`views.id`), so `latest()`/reconciliation scope by library, not just
    -- item_type. Stamped by `sync::sync_library_breadth`; WS-delta writes that can't
    -- attribute a batch bind NULL, and the upsert's `COALESCE(excluded, old)` keeps the old
    -- value (see `writer.rs`).
    library_id TEXT,
    -- Server-authoritative `UserData.LastPlayedDate` (RFC3339), distinct from `updated_at`
    -- (local write clock, debugging only, see below). Continue Watching sorts by this
    -- instead of `updated_at` so only a real watch-state change reorders it, matching the
    -- web client; `apply_local_user_data` stamps "now" on local reports for immediate sort.
    last_played_date TEXT,
    -- Episode grid's synopsis on the browse path without a live fetch; see
    -- `CardRow::overview` in lib.rs.
    overview TEXT,
    -- `BaseItemDto.LocationType == "Virtual"` (unaired/missing episode, no `MediaSources`);
    -- `premiere_date` distinguishes unaired vs. missing for the reason text, see
    -- `CardRow::is_virtual` in lib.rs.
    is_virtual INTEGER NOT NULL DEFAULT 0,
    -- Owning series' display name, for a Season/Episode row, needed on the browse path
    -- itself for Continue Watching/Next Up cards. See `CardRow::series_name` in lib.rs.
    series_name TEXT,
    -- docs/16-library-sort-filter.md §1.1: `BaseItemDto.status` ("Continuing"/"Ended" on a
    -- Series, NULL elsewhere), part of the default DTO. Backs the library grid's Status
    -- filter (`query::library_grid`). Schema v12.
    series_status TEXT,
    dto BLOB NOT NULL,
    -- Local write clock (unix millis), not server-meaningful; debugging only, do not sort
    -- user-facing lists by it (see `last_played_date` above).
    updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_items_browse ON items(parent_id, item_type, sort_name COLLATE NOCASE);
-- Replaced by `idx_items_latest_virtual` below (adds `is_virtual`); dropped so an upgrading
-- mirror sheds the redundant duplicate on its next open, no schema version bump needed.
DROP INDEX IF EXISTS idx_items_latest;
-- `library_id`, `item_type`, `date_created DESC` serve `latest()`'s ORDER BY; trailing
-- `is_virtual` also makes `sync::local_summary`'s reconcile-count probe index-only.
CREATE INDEX IF NOT EXISTS idx_items_latest_virtual ON items(library_id, item_type, date_created DESC, is_virtual);
-- `latest_grouped_series` groups every Episode of a library by `series_id`. Covering, so the
-- grouping never touches the DTO-sized table rows: one page per episode is seconds on a cold TV.
CREATE INDEX IF NOT EXISTS idx_items_latest_series ON items(library_id, series_id, date_created, is_virtual, played)
    WHERE item_type = 'Episode' AND series_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_items_series ON items(series_id, parent_index_number, index_number);
CREATE INDEX IF NOT EXISTS idx_items_resume ON items(playback_position_ticks) WHERE playback_position_ticks > 0;
-- `resume()`'s ORDER BY needs its own partial index led by `last_played_date`, with
-- `updated_at`, `id` as deterministic tiebreakers so ties don't fall back to physical row
-- order (which could show a stale item atop Continue Watching after a restart).
CREATE INDEX IF NOT EXISTS idx_items_resume_by_last_played ON items(last_played_date DESC, updated_at DESC, id ASC) WHERE playback_position_ticks > 0;
-- `children()` with `Sort::IndexNumber` needs its own parent_id-scoped ordering index
-- (`idx_items_browse` sorts by `sort_name`); covers both the filter and the ORDER BY.
CREATE INDEX IF NOT EXISTS idx_items_parent_order ON items(parent_id, parent_index_number, index_number);
-- docs/16-library-sort-filter.md §2.6: the library grid's four sorts each get a
-- `parent_id`-scoped index trailing `sort_name COLLATE NOCASE, id` so the full ORDER BY is
-- index-served in both directions. `idx_items_grid_runtime` keys on `NULLIF(runtime_ticks,
-- 0)` to match `query::grid_sort_order`'s Runtime expression. Filters (watched/genre/decade/
-- status) are deliberately unindexed, treated as post-index predicates. Schema v13.
CREATE INDEX IF NOT EXISTS idx_items_grid_name ON items(parent_id, sort_name COLLATE NOCASE, id);
CREATE INDEX IF NOT EXISTS idx_items_grid_added ON items(parent_id, date_created, sort_name COLLATE NOCASE, id);
CREATE INDEX IF NOT EXISTS idx_items_grid_year ON items(parent_id, production_year, sort_name COLLATE NOCASE, id);
CREATE INDEX IF NOT EXISTS idx_items_grid_runtime ON items(parent_id, NULLIF(runtime_ticks, 0), sort_name COLLATE NOCASE, id);

CREATE VIRTUAL TABLE IF NOT EXISTS search USING fts5(
    name, original_title, series_name,
    content='', tokenize='unicode61 remove_diacritics 2'
);

-- `image_lru` backed the now-deleted `image_cache` module; dropped for mirrors that predate
-- this bump (a fresh file never has it, so this is a no-op there). See `SCHEMA_VERSION`.
DROP TABLE IF EXISTS image_lru;

-- A BoxSet's children can't be expressed by `items.parent_id` (many-to-many, not a
-- parent/child relationship); populated per-BoxSet from `/Items?ParentId=<boxset_id>`,
-- `sort_index` preserving server order.
CREATE TABLE IF NOT EXISTS collection_members (
    collection_id TEXT NOT NULL,
    item_id TEXT NOT NULL,
    sort_index INTEGER,
    PRIMARY KEY (collection_id, item_id)
);
-- Covers both the `children()` membership join's filter and its ORDER BY sort_index.
CREATE INDEX IF NOT EXISTS idx_collection_members_order ON collection_members(collection_id, sort_index);

-- docs/16-library-sort-filter.md §1.2: genres are many-to-many (server strings verbatim,
-- never prettified, per CLAUDE.md), so unlike `series_status` this can't be a plain `items`
-- column. `WITHOUT ROWID`: the composite PK is the only access path needed, so a separate
-- rowid would be wasted storage.
CREATE TABLE IF NOT EXISTS item_genres (
    item_id TEXT NOT NULL,
    genre   TEXT NOT NULL,
    PRIMARY KEY (item_id, genre)
) WITHOUT ROWID;
-- Serves the Genre filter's `EXISTS` lookup from the genre side, and `library_genres`'s
-- `DISTINCT genre` listing.
CREATE INDEX IF NOT EXISTS idx_item_genres_genre ON item_genres(genre, item_id);
"#;

fn db_err(e: impl std::fmt::Display) -> CacheError {
    CacheError::Db(e.to_string())
}

fn read_schema_version(conn: &Connection) -> Option<u32> {
    conn.query_row(
        "SELECT value FROM meta WHERE key = 'schema_version'",
        [],
        |row| row.get::<_, String>(0),
    )
    .ok()
    .and_then(|v| v.parse::<u32>().ok())
}

/// Removes the db file and any WAL/SHM/journal siblings; a missing sibling is not an error.
fn remove_db_files(path: &Path) {
    let _ = std::fs::remove_file(path);
    for suffix in ["-wal", "-shm", "-journal"] {
        let mut sibling = path.as_os_str().to_owned();
        sibling.push(suffix);
        let _ = std::fs::remove_file(std::path::PathBuf::from(sibling));
    }
}

/// Forces owner-only (0600) perms on the mirror db and its WAL/SHM/journal sidecars,
/// best-effort (defense-in-depth for shared machines, matching the 0600 session store).
#[cfg(unix)]
fn harden_db_perms(path: &Path) {
    use std::os::unix::fs::PermissionsExt;
    let owner_only = std::fs::Permissions::from_mode(0o600);
    let _ = std::fs::set_permissions(path, owner_only.clone());
    for suffix in ["-wal", "-shm", "-journal"] {
        let mut sibling = path.as_os_str().to_owned();
        sibling.push(suffix);
        let sibling = std::path::PathBuf::from(sibling);
        if sibling.exists() {
            let _ = std::fs::set_permissions(&sibling, owner_only.clone());
        }
    }
}

#[cfg(not(unix))]
fn harden_db_perms(_path: &Path) {}

/// Opens the mirror at `path`, dropping and recreating it if `schema_version` doesn't match
/// (or can't be read — disposable cache). Returns the prepared connection and whether
/// `items` was empty (drives whether the sync engine needs a full initial sync).
pub(crate) fn open_and_prepare(path: &Path) -> Result<(Connection, bool), CacheError> {
    if path.exists() {
        let mismatched = match Connection::open(path) {
            Ok(probe) => read_schema_version(&probe) != Some(SCHEMA_VERSION),
            Err(_) => true,
        };
        if mismatched {
            tracing::info!(
                ?path,
                "schema_version mismatch (or unreadable); rebuilding mirror"
            );
            remove_db_files(path);
        }
    }

    let conn = Connection::open(path).map_err(db_err)?;
    conn.pragma_update(None, "journal_mode", "WAL")
        .map_err(db_err)?;
    conn.pragma_update(None, "synchronous", "NORMAL")
        .map_err(db_err)?;
    // Default file creation is world-readable (0644); harden the db and its WAL/SHM sidecars
    // to owner-only. Best-effort: a perms failure must not stop the mirror opening.
    harden_db_perms(path);
    conn.execute_batch(SCHEMA_SQL).map_err(db_err)?;
    conn.execute(
        "INSERT INTO meta (key, value) VALUES ('schema_version', ?1)
         ON CONFLICT(key) DO UPDATE SET value = excluded.value",
        [SCHEMA_VERSION.to_string()],
    )
    .map_err(db_err)?;

    // EXISTS stops at the first row; COUNT(*) walks the whole table on every launch.
    let has_items: bool = conn
        .query_row("SELECT EXISTS(SELECT 1 FROM items)", [], |row| row.get(0))
        .map_err(db_err)?;
    // Free on an empty table; a populated one builds them off the launch path instead.
    if !has_items {
        conn.execute_batch(FAVORITE_INDEX_SQL).map_err(db_err)?;
    }

    Ok((conn, !has_items))
}

/// Opens one additional read-only connection against an already-prepared mirror file.
pub(crate) fn open_reader(path: &Path) -> Result<Connection, CacheError> {
    let conn = Connection::open_with_flags(
        path,
        rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY | rusqlite::OpenFlags::SQLITE_OPEN_URI,
    )
    .map_err(db_err)?;
    // busy_timeout smooths over the rare moment a reader opens mid-checkpoint.
    conn.busy_timeout(std::time::Duration::from_millis(2000))
        .map_err(db_err)?;
    Ok(conn)
}

pub(crate) fn upsert_meta(conn: &Connection, key: &str, value: &str) -> Result<(), CacheError> {
    conn.execute(
        "INSERT INTO meta (key, value) VALUES (?1, ?2)
         ON CONFLICT(key) DO UPDATE SET value = excluded.value",
        rusqlite::params![key, value],
    )
    .map_err(db_err)?;
    Ok(())
}

pub(crate) fn read_meta(conn: &Connection, key: &str) -> Option<String> {
    conn.query_row("SELECT value FROM meta WHERE key = ?1", [key], |row| {
        row.get(0)
    })
    .ok()
}

/// Fresh temp-dir mirror for a test -- shared by `query`'s and `writer`'s test modules so
/// both open a db the same way.
/// docs/16 §2.7's favorites indexes, kept out of [`SCHEMA_SQL`]: on a populated mirror their
/// one-time build (hundreds of ms on a TV) would land inside launch, so the sync startup pass
/// runs it through the writer instead (`sync::ensure_favorite_indexes`), and the Favorites
/// shelf and drawer probe stay empty until [`favorite_indexes_ready`].
pub(crate) const FAVORITE_INDEX_SQL: &str = "
-- Favorites shelf/page: a partial index, so only the few favorite rows are indexed.
CREATE INDEX IF NOT EXISTS idx_items_favorite ON items(item_type) WHERE is_favorite = 1;
-- The shelf's \"last played\" for a favorite Series/Season: one seek each, over played rows only.
CREATE INDEX IF NOT EXISTS idx_items_series_played ON items(series_id, last_played_date) WHERE last_played_date IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_items_parent_played ON items(parent_id, last_played_date) WHERE last_played_date IS NOT NULL;
";

/// Whether every [`FAVORITE_INDEX_SQL`] index exists.
pub(crate) fn favorite_indexes_ready(conn: &Connection) -> bool {
    conn.query_row(
        "SELECT COUNT(*) = 3 FROM sqlite_master WHERE type = 'index' AND name IN \
         ('idx_items_favorite', 'idx_items_series_played', 'idx_items_parent_played')",
        [],
        |row| row.get(0),
    )
    .unwrap_or(false)
}

#[cfg(test)]
pub(crate) fn open_test_db() -> (tempfile::TempDir, Connection) {
    let dir = tempfile::tempdir().expect("tempdir");
    let path = dir.path().join("mirror.db");
    let (conn, _) = open_and_prepare(&path).expect("open");
    (dir, conn)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn favorite_indexes_come_free_on_a_fresh_mirror_and_wait_on_a_populated_one() {
        let dir = tempfile::tempdir().expect("tempdir");
        let path = dir.path().join("mirror.db");
        let (conn, _) = open_and_prepare(&path).expect("open");
        assert!(
            favorite_indexes_ready(&conn),
            "a fresh mirror gets them at open"
        );

        conn.execute(
            "INSERT INTO items (id, item_type, dto, updated_at) VALUES ('a', 'Movie', '{}', 0)",
            [],
        )
        .expect("insert");
        conn.execute_batch(
            "DROP INDEX idx_items_favorite; DROP INDEX idx_items_series_played; \
             DROP INDEX idx_items_parent_played;",
        )
        .expect("drop");
        drop(conn);
        let (conn, _) = open_and_prepare(&path).expect("reopen");
        assert!(
            !favorite_indexes_ready(&conn),
            "a populated mirror defers the build"
        );

        conn.execute_batch(FAVORITE_INDEX_SQL).expect("build");
        assert!(favorite_indexes_ready(&conn));
    }

    #[test]
    fn fresh_open_creates_schema_and_reports_empty() {
        let dir = tempfile::tempdir().expect("tempdir");
        let path = dir.path().join("mirror.db");
        let (conn, empty) = open_and_prepare(&path).expect("open");
        assert!(empty);
        assert_eq!(read_schema_version(&conn), Some(SCHEMA_VERSION));
    }

    /// Pins that the mirror db and WAL/SHM sidecars are 0600, not the default 0644.
    #[cfg(unix)]
    #[test]
    fn mirror_db_and_sidecars_are_owner_only() {
        use std::os::unix::fs::PermissionsExt;
        let dir = tempfile::tempdir().expect("tempdir");
        let path = dir.path().join("mirror.db");
        let (_conn, _) = open_and_prepare(&path).expect("open");
        let mode = |p: &Path| std::fs::metadata(p).expect("stat").permissions().mode() & 0o777;
        assert_eq!(mode(&path), 0o600, "mirror.db must be owner-only");
        // WAL mode created these sidecars; whichever exist must be 0600 too.
        for suffix in ["-wal", "-shm"] {
            let mut sib = path.as_os_str().to_owned();
            sib.push(suffix);
            let sib = std::path::PathBuf::from(sib);
            if sib.exists() {
                assert_eq!(mode(&sib), 0o600, "{suffix} sidecar must be owner-only");
            }
        }
    }

    #[test]
    fn reopen_with_same_version_preserves_data() {
        let dir = tempfile::tempdir().expect("tempdir");
        let path = dir.path().join("mirror.db");
        {
            let (conn, _) = open_and_prepare(&path).expect("open");
            conn.execute(
                "INSERT INTO items (id, item_type, dto, updated_at) VALUES ('x', 'Movie', '{}', 0)",
                [],
            )
            .expect("insert");
        }
        let (_conn, empty) = open_and_prepare(&path).expect("reopen");
        assert!(
            !empty,
            "existing row should survive a reopen at the same schema version"
        );
    }

    #[test]
    fn version_mismatch_drops_and_rebuilds() {
        let dir = tempfile::tempdir().expect("tempdir");
        let path = dir.path().join("mirror.db");
        {
            let (conn, _) = open_and_prepare(&path).expect("open");
            conn.execute(
                "INSERT INTO items (id, item_type, dto, updated_at) VALUES ('x', 'Movie', '{}', 0)",
                [],
            )
            .expect("insert");
            upsert_meta(&conn, "schema_version", "999").expect("bump version");
        }
        let (_conn, empty) = open_and_prepare(&path).expect("reopen after mismatch");
        assert!(
            empty,
            "version mismatch must drop and rebuild, losing prior rows"
        );
    }

    #[test]
    fn corrupt_or_missing_meta_table_is_treated_as_mismatch() {
        let dir = tempfile::tempdir().expect("tempdir");
        let path = dir.path().join("mirror.db");
        // A file exists but has no schema at all.
        {
            let conn = Connection::open(&path).expect("open raw");
            conn.execute("CREATE TABLE unrelated (x INTEGER)", [])
                .expect("create");
        }
        let (_conn, empty) = open_and_prepare(&path).expect("open despite garbage file");
        assert!(empty);
    }
}
