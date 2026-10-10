//! Browse queries: everything here must be servable from an index (query
//! budget, asserted via `EXPLAIN QUERY PLAN` in tests). `item()`/`items()` are the DTO-parse
//! exceptions (Detail-only / explicitly batched); neither belongs on the grid/scroll path.

use jellyfin_api::models::BaseItemDto;
use std::borrow::Cow;

use rusqlite::{params, Connection, Row};

use crate::{
    CardRow, Decade, GridCounts, GridFilters, GridGroup, GridSort, GridSortField, Sort,
    StatusFilter, WatchedFilter, FAVORITES_VIEW_ID,
};

/// The item types a favorite can be shown as (people, music and playlists are left out).
const FAVORITES_FROM_SQL: &str =
    "FROM items WHERE is_favorite = 1 AND item_type IN ('Movie', 'Series', 'Season', 'Episode', 'BoxSet')";

const CARD_COLUMNS: &str = "id, item_type, name, primary_tag, primary_blurhash, played, \
     playback_position_ticks, runtime_ticks, unplayed_item_count, production_year, \
     index_number, parent_index_number, series_id, series_primary_tag, \
     parent_backdrop_item_id, parent_backdrop_tag, last_played_date, overview, \
     premiere_date, is_virtual, series_name, library_id, backdrop_tag, thumb_tag, \
     is_favorite, parent_thumb_item_id, parent_thumb_tag";

/// Watch state leaves the mirror already graced (docs/07 §1), so every browse surface agrees.
fn row_to_card(row: &Row<'_>) -> rusqlite::Result<CardRow> {
    let item_type: String = row.get(1)?;
    let runtime_ticks = row.get(7)?;
    let (position_ticks, played) = crate::watch_grace::displayed_watch_state(
        &item_type,
        row.get(6)?,
        runtime_ticks,
        row.get(5)?,
    );
    Ok(CardRow {
        id: row.get(0)?,
        item_type,
        name: row.get::<_, Option<String>>(2)?.unwrap_or_default(),
        primary_tag: row.get(3)?,
        blurhash: row.get(4)?,
        played,
        position_ticks,
        runtime_ticks,
        unplayed_count: row.get(8)?,
        production_year: row.get(9)?,
        index_number: row.get(10)?,
        parent_index_number: row.get(11)?,
        series_id: row.get(12)?,
        series_primary_tag: row.get(13)?,
        parent_backdrop_item_id: row.get(14)?,
        parent_backdrop_tag: row.get(15)?,
        last_played_date: row.get(16)?,
        overview: row.get(17)?,
        premiere_date: row.get(18)?,
        is_virtual: row.get(19)?,
        series_name: row.get(20)?,
        library_id: row.get(21)?,
        backdrop_tag: row.get(22)?,
        thumb_tag: row.get(23)?,
        is_favorite: row.get(24)?,
        parent_thumb_item_id: row.get(25)?,
        parent_thumb_tag: row.get(26)?,
    })
}

/// Total row count for the sidebar's sync progress text. A bare `COUNT(*)` reads SQLite's
/// b-tree page count, not a per-row scan, and is only polled at UI cadence.
pub(crate) fn item_count(conn: &Connection) -> i64 {
    conn.query_row("SELECT COUNT(*) FROM items", [], |row| row.get(0))
        .unwrap_or_else(|e| {
            tracing::error!(error = %e, "item_count query failed");
            0
        })
}

/// Non-virtual items per `item_type` (docs/13 About > Library tiles).
pub(crate) fn item_type_counts(conn: &Connection) -> Vec<(String, i64)> {
    let result = (|| -> rusqlite::Result<Vec<(String, i64)>> {
        let mut stmt = conn.prepare_cached(
            "SELECT item_type, COUNT(*) FROM items WHERE is_virtual = 0 GROUP BY item_type",
        )?;
        let rows = stmt.query_map([], |row| Ok((row.get(0)?, row.get(1)?)))?;
        rows.collect()
    })();
    result.unwrap_or_else(|e| {
        tracing::error!(error = %e, "item_type_counts query failed");
        Vec::new()
    })
}

pub(crate) fn views(conn: &Connection) -> Vec<crate::ViewSummary> {
    let result = (|| -> rusqlite::Result<Vec<crate::ViewSummary>> {
        let mut stmt = conn.prepare_cached(
            "SELECT id, name, item_type, collection_type FROM views ORDER BY sort_index ASC",
        )?;
        let rows = stmt.query_map([], |row| {
            Ok(crate::ViewSummary {
                id: row.get::<_, String>(0)?,
                name: row.get::<_, Option<String>>(1)?.unwrap_or_default(),
                item_type: row.get::<_, Option<String>>(2)?.unwrap_or_default(),
                collection_type: row.get::<_, Option<String>>(3)?.unwrap_or_default(),
            })
        })?;
        rows.collect()
    })();
    result.unwrap_or_else(|e| {
        tracing::error!(error = %e, "views query failed");
        Vec::new()
    })
}

// ---- Library sort/filter (docs/16-library-sort-filter.md §2) ------------

/// §2.6: the per-field index each [`GridSortField`] is pinned to, a guarantee rather than
/// left to the planner's cost heuristics.
fn grid_sort_index(field: GridSortField) -> &'static str {
    match field {
        GridSortField::Name => "idx_items_grid_name",
        GridSortField::DateAdded => "idx_items_grid_added",
        GridSortField::Year => "idx_items_grid_year",
        GridSortField::Runtime => "idx_items_grid_runtime",
    }
}

/// §2.2's ORDER BY table (schema v13): `{key} {dir} NULLS LAST, sort_name COLLATE NOCASE
/// {dir}, id {dir}`, matching the grid index's own column expressions term-for-term so
/// SQLite can index-serve both directions; the tiebreak follows `dir` (needed for a backward
/// scan to serve DESC). Name is a special case: `key` there already IS `sort_name COLLATE
/// NOCASE`, and repeating that term breaks SQLite 3.43's index match (`USE TEMP B-TREE`), so
/// the redundant tiebreak term is dropped for Name (a no-op on row order).
fn grid_sort_order(sort: GridSort) -> String {
    let dir = if sort.descending { "DESC" } else { "ASC" };
    let key = match sort.field {
        GridSortField::Name => "sort_name COLLATE NOCASE".to_string(),
        GridSortField::DateAdded => "date_created".to_string(),
        GridSortField::Year => "production_year".to_string(),
        GridSortField::Runtime => "NULLIF(runtime_ticks, 0)".to_string(),
    };
    if sort.field == GridSortField::Name {
        return format!("{key} {dir} NULLS LAST, id {dir}");
    }
    format!("{key} {dir} NULLS LAST, sort_name COLLATE NOCASE {dir}, id {dir}")
}

/// §2.2's filter table. Appends `AND ...` clauses for every active filter; Genre and item
/// type add `?` placeholders, bound in that order by [`grid_filter_params`].
fn append_grid_filters(sql: &mut String, filters: &GridFilters) {
    match filters.watched {
        WatchedFilter::Any => {}
        // docs/07 §1: judged by the card's displayed state, so filter and card agree; a Movie
        // has no Episode rows, so the subquery is vacuously true for it.
        WatchedFilter::Unwatched => sql.push_str(concat!(
            " AND ",
            crate::watch_grace::not_started_sql!(""),
            " AND NOT EXISTS (\
             SELECT 1 FROM items e WHERE e.series_id = items.id AND e.item_type = 'Episode' \
             AND NOT ",
            crate::watch_grace::not_started_sql!("e."),
            ")"
        )),
        WatchedFilter::HasUnwatched => sql.push_str(" AND COALESCE(unplayed_item_count, 0) > 0"),
        // A Series is `played` only when every episode is (server-computed).
        WatchedFilter::Watched => {
            sql.push_str(concat!(" AND ", crate::watch_grace::watched_sql!("")))
        }
    }
    if filters.genre.is_some() {
        sql.push_str(
            " AND EXISTS (SELECT 1 FROM item_genres g WHERE g.item_id = items.id AND g.genre = ?)",
        );
    }
    if let Some(decade) = filters.decade {
        // A NULL `production_year` never satisfies a numeric comparison, so no extra
        // `IS NOT NULL` guard is needed.
        let clause = match decade {
            Decade::D2020s => "production_year BETWEEN 2020 AND 2029",
            Decade::D2010s => "production_year BETWEEN 2010 AND 2019",
            Decade::D2000s => "production_year BETWEEN 2000 AND 2009",
            Decade::D1990s => "production_year BETWEEN 1990 AND 1999",
            Decade::D1980s => "production_year BETWEEN 1980 AND 1989",
            Decade::Older => "production_year < 1980",
        };
        sql.push_str(" AND ");
        sql.push_str(clause);
    }
    match filters.status {
        StatusFilter::Any => {}
        StatusFilter::Continuing => sql.push_str(" AND series_status = 'Continuing'"),
        StatusFilter::Ended => sql.push_str(" AND series_status = 'Ended'"),
    }
    if filters.item_type.is_some() {
        sql.push_str(" AND item_type = ?");
    }
}

/// The bound parameters `append_grid_filters` needs, in the same order its `?` placeholders
/// appear, kept separate so callers can slot them into their own list.
fn grid_filter_params(filters: &GridFilters) -> Vec<&dyn rusqlite::ToSql> {
    let mut params: Vec<&dyn rusqlite::ToSql> = Vec::new();
    if let Some(genre) = &filters.genre {
        params.push(genre as &dyn rusqlite::ToSql);
    }
    if let Some(item_type) = &filters.item_type {
        params.push(item_type as &dyn rusqlite::ToSql);
    }
    params
}

pub(crate) fn library_grid(
    conn: &Connection,
    view_id: &str,
    sort: GridSort,
    filters: &GridFilters,
    offset: u32,
    limit: u32,
) -> Vec<CardRow> {
    library_grid_checked(conn, view_id, sort, filters, offset, limit).unwrap_or_else(|e| {
        tracing::error!(error = %e, "library_grid query failed");
        Vec::new()
    })
}

/// The `SELECT <select_expr> FROM items INDEXED BY <index> WHERE parent_id = ? [filters]
/// ORDER BY <order>` scaffold [`library_grid_checked`] and [`library_grid_groups_checked`]
/// both build, plus the filter half of the parameter vector; callers still prepend `view_id`
/// and append any paging params themselves, since those borrow the caller's own locals.
fn grid_query_sql<'a>(
    select_expr: &str,
    view_id: &str,
    sort: GridSort,
    filters: &'a GridFilters,
) -> (String, Vec<&'a dyn rusqlite::ToSql>) {
    let mut sql = format!(
        "SELECT {select_expr} {}",
        grid_scope(view_id, Some(sort.field))
    );
    append_grid_filters(&mut sql, filters);
    sql.push_str(&format!(" ORDER BY {}", grid_sort_order(sort)));
    (sql, grid_filter_params(filters))
}

/// docs/16 §2.7: the grid's `FROM ... WHERE` population. A library view is `parent_id = ?`
/// pinned to its sort's index; [`FAVORITES_VIEW_ID`] is every favorite across libraries (a
/// small set off `idx_items_favorite`, so no pinned index) and binds no parameter.
fn grid_scope(view_id: &str, sort_field: Option<GridSortField>) -> Cow<'static, str> {
    if view_id == FAVORITES_VIEW_ID {
        return Cow::Borrowed(FAVORITES_FROM_SQL);
    }
    sort_field.map_or(Cow::Borrowed("FROM items WHERE parent_id = ?"), |field| {
        Cow::Owned(format!(
            "FROM items INDEXED BY {} WHERE parent_id = ?",
            grid_sort_index(field)
        ))
    })
}

/// The leading `view_id` bind [`grid_scope`] needs, if any.
fn scope_params<'a>(view_id: &'a &str) -> Vec<&'a dyn rusqlite::ToSql> {
    if *view_id == FAVORITES_VIEW_ID {
        Vec::new()
    } else {
        vec![view_id as &dyn rusqlite::ToSql]
    }
}

/// [`library_grid`] with the error surfaced instead of an empty list (§4.6: keep the last
/// good grid, don't blank it).
pub(crate) fn library_grid_checked(
    conn: &Connection,
    view_id: &str,
    sort: GridSort,
    filters: &GridFilters,
    offset: u32,
    limit: u32,
) -> rusqlite::Result<Vec<CardRow>> {
    let (mut sql, filter_params) = grid_query_sql(CARD_COLUMNS, view_id, sort, filters);
    sql.push_str(" LIMIT ? OFFSET ?");

    let mut params = scope_params(&view_id);
    params.extend(filter_params);
    params.push(&limit);
    params.push(&offset);

    let mut stmt = conn.prepare(&sql)?;
    let rows = stmt.query_map(params.as_slice(), row_to_card)?;
    rows.collect()
}

/// §2.3: `filtered` matches [`append_grid_filters`]'s WHERE; `total` is every row under
/// `view_id`. Fails open to zeroed counts; use [`library_grid_counts_checked`] (§4.6) to
/// tell a failure apart from a genuinely empty view.
pub(crate) fn library_grid_counts(
    conn: &Connection,
    view_id: &str,
    filters: &GridFilters,
) -> GridCounts {
    library_grid_counts_checked(conn, view_id, filters).unwrap_or_else(|e| {
        tracing::error!(error = %e, "library_grid_counts query failed");
        GridCounts {
            filtered: 0,
            total: 0,
        }
    })
}

/// [`library_grid_counts`] with the error surfaced instead of zeroed counts (§4.6).
pub(crate) fn library_grid_counts_checked(
    conn: &Connection,
    view_id: &str,
    filters: &GridFilters,
) -> rusqlite::Result<GridCounts> {
    let scope = grid_scope(view_id, None);
    let mut filtered_sql = format!("SELECT COUNT(*) {scope}");
    append_grid_filters(&mut filtered_sql, filters);
    let mut filtered_params = scope_params(&view_id);
    filtered_params.extend(grid_filter_params(filters));
    let filtered: i64 =
        conn.query_row(&filtered_sql, filtered_params.as_slice(), |row| row.get(0))?;

    let total: i64 = conn.query_row(
        &format!("SELECT COUNT(*) {scope}"),
        scope_params(&view_id).as_slice(),
        |row| row.get(0),
    )?;

    Ok(GridCounts {
        filtered: filtered.max(0) as u64,
        total: total.max(0) as u64,
    })
}

/// §2.4's per-group key expression, run-length encoded by [`library_grid_groups`]. Each
/// field maps its NULL/absent case to the empty string, which lands last purely via
/// `grid_sort_order`'s `NULLS LAST` -- no separate handling needed here.
fn grid_group_key_expr(field: GridSortField) -> &'static str {
    match field {
        GridSortField::Name => {
            "CASE WHEN upper(substr(sort_name,1,1)) BETWEEN 'A' AND 'Z' \
             THEN upper(substr(sort_name,1,1)) ELSE '#' END"
        }
        GridSortField::DateAdded => "COALESCE(substr(date_created, 1, 7), '')",
        GridSortField::Year => {
            "CASE WHEN production_year IS NULL THEN '' \
             ELSE CAST((production_year / 10) * 10 AS TEXT) END"
        }
        GridSortField::Runtime => {
            "CASE \
                WHEN runtime_ticks IS NULL OR runtime_ticks = 0 THEN '' \
                WHEN runtime_ticks / 600000000 < 30 THEN '0' \
                WHEN runtime_ticks / 600000000 < 60 THEN '30' \
                WHEN runtime_ticks / 600000000 < 90 THEN '60' \
                WHEN runtime_ticks / 600000000 < 120 THEN '90' \
                WHEN runtime_ticks / 600000000 < 150 THEN '120' \
                WHEN runtime_ticks / 600000000 < 180 THEN '150' \
                ELSE '180' \
             END"
        }
    }
}

/// §2.4: a plain ordered key scan, run-length-encoded in Rust using the identical `ORDER
/// BY`/index [`library_grid`] uses, so a contiguous run of equal keys is genuinely one
/// visual block. Not a `GROUP BY` (schema v13): that collapses all rows sharing a key into
/// one, mis-grouping a key with two non-contiguous runs (e.g. `#` under Name: digit-led
/// titles before `A`, non-ASCII after `Z`) into one wrongly-positioned bucket.
pub(crate) fn library_grid_groups(
    conn: &Connection,
    view_id: &str,
    sort: GridSort,
    filters: &GridFilters,
) -> Vec<GridGroup> {
    library_grid_groups_checked(conn, view_id, sort, filters).unwrap_or_else(|e| {
        tracing::error!(error = %e, "library_grid_groups query failed");
        Vec::new()
    })
}

/// [`library_grid_groups`] with the error surfaced instead of an empty list (§4.6: keep the
/// last good rail).
pub(crate) fn library_grid_groups_checked(
    conn: &Connection,
    view_id: &str,
    sort: GridSort,
    filters: &GridFilters,
) -> rusqlite::Result<Vec<GridGroup>> {
    let key_expr = grid_group_key_expr(sort.field);
    let (sql, filter_params) = grid_query_sql(key_expr, view_id, sort, filters);

    let mut params = scope_params(&view_id);
    params.extend(filter_params);

    let mut stmt = conn.prepare(&sql)?;
    let rows = stmt.query_map(params.as_slice(), |row| row.get::<_, String>(0))?;
    let mut groups: Vec<GridGroup> = Vec::new();
    for key in rows {
        let key = key?;
        match groups.last_mut() {
            Some(last) if last.key == key => last.count += 1,
            _ => groups.push(GridGroup { key, count: 1 }),
        }
    }
    Ok(groups)
}

/// §2.5: distinct genres over the view's grid rows, `COLLATE NOCASE` order. Joins from
/// `items` (indexed `parent_id`) into `item_genres`, same direction as the Genre filter's
/// `EXISTS`.
pub(crate) fn library_genres(conn: &Connection, view_id: &str) -> Vec<String> {
    let sql = format!(
        "SELECT DISTINCT g.genre FROM item_genres g WHERE g.item_id IN (SELECT id {}) \
         ORDER BY g.genre COLLATE NOCASE ASC",
        grid_scope(view_id, None)
    );
    let result = (|| -> rusqlite::Result<Vec<String>> {
        let mut stmt = conn.prepare_cached(&sql)?;
        let rows = stmt.query_map(scope_params(&view_id).as_slice(), |row| {
            row.get::<_, String>(0)
        })?;
        rows.collect()
    })();
    result.unwrap_or_else(|e| {
        tracing::error!(error = %e, "library_genres query failed");
        Vec::new()
    })
}

fn sort_clause(sort: Sort) -> &'static str {
    match sort {
        // COLLATE NOCASE matches the grid's Name sort (`idx_items_grid_name`) and
        // `idx_items_browse`'s own collation; see `SCHEMA_VERSION`'s 14 -> 15 note.
        Sort::NameAsc => "sort_name COLLATE NOCASE ASC",
        Sort::DateCreatedDesc => "date_created DESC",
        Sort::PremiereDateDesc => "premiere_date DESC",
        // Column order matches `idx_items_parent_order` exactly so this stays index-served;
        // see `Sort::IndexNumber`'s doc comment.
        Sort::IndexNumber => "parent_index_number ASC, index_number ASC",
    }
}

/// Card columns qualified with `items.` for queries that join `items`
/// against another table (the column list is otherwise ambiguous once a
/// second table with e.g. its own `id` is in scope).
fn qualified_card_columns() -> String {
    CARD_COLUMNS
        .split(", ")
        .map(|c| format!("items.{c}"))
        .collect::<Vec<_>>()
        .join(", ")
}

/// A `BoxSet`'s children can't be expressed by `items.parent_id` (an item can belong to
/// many collections, and membership isn't a parent/child relationship), so browsing into
/// one goes through the `collection_members` join instead, in the server-provided curation
/// order (`sort_index`) rather than the caller's `Sort`.
fn children_via_collection_membership_checked(
    conn: &Connection,
    collection_id: &str,
    offset: u32,
    limit: u32,
) -> rusqlite::Result<Vec<CardRow>> {
    let sql = format!(
        "SELECT {cols} FROM collection_members cm \
         JOIN items ON items.id = cm.item_id \
         WHERE cm.collection_id = ?1 \
         ORDER BY cm.sort_index ASC LIMIT ?2 OFFSET ?3",
        cols = qualified_card_columns()
    );
    let mut stmt = conn.prepare_cached(&sql)?;
    let rows = stmt.query_map(params![collection_id, limit, offset], row_to_card)?;
    rows.collect()
}

/// docs/19-detail-action-menu.md §2.2: the menu's `ALREADY IN` state --
/// every collection (BoxSet id) `item_id` is currently a member of. Unlike
/// [`children_via_collection_membership_checked`] (one collection's members,
/// in curation order), this is one item's memberships, unordered -- the
/// caller only tests set membership, so `sort_index` doesn't matter here.
/// Failure is logged and coerced to empty, same as [`children`]: "we don't
/// know" and "member of nothing" both render the same absent `ALREADY IN`.
pub(crate) fn collection_ids_containing(conn: &Connection, item_id: &str) -> Vec<String> {
    let result: rusqlite::Result<Vec<String>> = (|| {
        let mut stmt =
            conn.prepare_cached("SELECT collection_id FROM collection_members WHERE item_id = ?1")?;
        let rows = stmt.query_map(params![item_id], |row| row.get(0))?;
        rows.collect()
    })();
    result.unwrap_or_else(|e| {
        tracing::error!(error = %e, "collection_ids_containing query failed");
        Vec::new()
    })
}

pub(crate) fn children(
    conn: &Connection,
    parent_id: &str,
    sort: Sort,
    offset: u32,
    limit: u32,
) -> Vec<CardRow> {
    children_checked(conn, parent_id, sort, offset, limit).unwrap_or_else(|e| {
        tracing::error!(error = %e, "children query failed");
        Vec::new()
    })
}

/// [`children`] with the error surfaced instead of an empty list: "the query failed" (keep
/// the last good grid) and "the parent is empty" (show an empty grid) are different outcomes.
pub(crate) fn children_checked(
    conn: &Connection,
    parent_id: &str,
    sort: Sort,
    offset: u32,
    limit: u32,
) -> rusqlite::Result<Vec<CardRow>> {
    // One cheap PK lookup to tell a BoxSet/Season parent apart from a regular one, and pull
    // `series_id`/`index_number` a Season parent needs to resolve episodes by season
    // semantics. `.ok()` treats "no such row" and a failed lookup alike; a real I/O error
    // surfaces from the main query below.
    let parent_row: Option<(String, Option<String>, Option<i32>)> = conn
        .query_row(
            "SELECT item_type, series_id, index_number FROM items WHERE id = ?1",
            [parent_id],
            |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
        )
        .ok();
    let parent_item_type = parent_row.as_ref().map(|(t, _, _)| t.as_str());
    if parent_item_type == Some("BoxSet") {
        return children_via_collection_membership_checked(conn, parent_id, offset, limit);
    }
    // Hazard 2 (docs/24-server-virtual-items.md): per-episode release
    // directories make the scanner create real `Folder` items alongside `Season` objects, so
    // "all children of the series" mixes junk Folders into a seasons listing.
    // `seasons_of_series_checked` selects by type instead, matching `/Shows/{id}/Seasons`.
    if parent_item_type == Some("Series") {
        return seasons_of_series_checked(conn, parent_id, sort, offset, limit);
    }
    // A server can hold duplicate Season objects for one series after a rescan; `/Shows/{id}/
    // Seasons` returns only one, but an episode's `ParentId` may point at the unsynced
    // duplicate. `episodes_of_season_checked` resolves by (series, season number) instead of
    // literal `parent_id`, matching how the server itself resolves it.
    if parent_item_type == Some("Season") {
        let (_, series_id, season_index) = parent_row.expect("Some, matched above");
        if let (Some(series_id), Some(season_index)) = (series_id, season_index) {
            return episodes_of_season_checked(
                conn,
                parent_id,
                &series_id,
                season_index,
                sort,
                offset,
                limit,
            );
        }
        // Missing series_id/index_number shouldn't happen for a real Season, but falls
        // through to the plain parent_id path rather than trusting the blob.
    }

    // `INDEXED BY` pins each `Sort` variant's index explicitly: with two indexes matching
    // the bare `parent_id = ?` filter, the planner can prefer the wrong one for a given sort
    // (temp b-tree sort, defeating `idx_items_browse`); caught by
    // `children_query_uses_index_not_scan`.
    let index = match sort {
        Sort::IndexNumber => "idx_items_parent_order",
        Sort::NameAsc | Sort::DateCreatedDesc | Sort::PremiereDateDesc => "idx_items_browse",
    };
    let sql = format!(
        "SELECT {CARD_COLUMNS} FROM items INDEXED BY {index} WHERE parent_id = ?1 ORDER BY {} LIMIT ?2 OFFSET ?3",
        sort_clause(sort)
    );
    let mut stmt = conn.prepare_cached(&sql)?;
    let rows = stmt.query_map(params![parent_id, limit, offset], row_to_card)?;
    rows.collect()
}

/// `children_checked`'s Series branch (Hazard 2/2b): only `Season` rows, matched by
/// `series_id = ?1 OR (series_id IS NULL AND parent_id = ?1)` rather than a bare `parent_id`
/// equality that would also pick up junk `Folder` items. Filters by `item_type` only, never
/// `is_virtual`: a Season can be virtual yet hold real, playable episodes.
fn seasons_of_series_checked(
    conn: &Connection,
    series_id: &str,
    sort: Sort,
    offset: u32,
    limit: u32,
) -> rusqlite::Result<Vec<CardRow>> {
    let sql = format!(
        "SELECT {CARD_COLUMNS} FROM items \
         WHERE item_type = 'Season' \
         AND (series_id = ?1 OR (series_id IS NULL AND parent_id = ?1)) \
         ORDER BY {} LIMIT ?2 OFFSET ?3",
        sort_clause(sort)
    );
    let mut stmt = conn.prepare_cached(&sql)?;
    let rows = stmt.query_map(params![series_id, limit, offset], row_to_card)?;
    rows.collect()
}

/// [`season_episode_counts`]' seasons of one series.
const SERIES_SEASONS_SQL: &str = "SELECT id, index_number FROM items WHERE item_type = 'Season' \
     AND (series_id = ?1 OR (series_id IS NULL AND parent_id = ?1))";

/// [`season_episode_counts`]' episode totals per season key: (parent_index_number, parent_id
/// only when the index is NULL) -> (total, non-virtual).
const SERIES_EPISODE_GROUPS_SQL: &str = "SELECT parent_index_number, \
     CASE WHEN parent_index_number IS NULL THEN parent_id END, \
     COUNT(*), COALESCE(SUM(is_virtual = 0), 0) \
     FROM items WHERE item_type = 'Episode' AND series_id = ?1 GROUP BY 1, 2";

/// Per-season episode counts under `series_id`, for `JellybeamCore::children`'s Series ->
/// Seasons virtual-season filtering: a fully-unaired season still needs to select an empty
/// grid, which needs per-season totals [`seasons_of_series_checked`] alone can't supply.
///
/// Returns `(season_id, total_episodes, non_virtual_episodes)`, reusing
/// [`episodes_of_season_checked`]'s exact per-season match so counts agree with that grid.
/// A season with no episodes synced yet comes back `(id, 0, 0)`; the caller treats
/// `total_episodes == 0` as "fail open, don't hide" (never "the server says this is empty").
pub(crate) fn season_episode_counts(
    conn: &Connection,
    series_id: &str,
) -> rusqlite::Result<Vec<(String, i64, i64)>> {
    // One grouped pass over the series' episodes, mapped onto the seasons in Rust, so the
    // episodes are read once rather than once per season. A season with no episodes still yields
    // (id, 0, 0); matching mirrors `episodes_of_season_checked`.
    let seasons: Vec<(String, Option<i32>)> = conn
        .prepare_cached(SERIES_SEASONS_SQL)?
        .query_map(params![series_id], |r| Ok((r.get(0)?, r.get(1)?)))?
        .collect::<Result<_, _>>()?;
    let groups: Vec<(Option<i32>, Option<String>, i64, i64)> = conn
        .prepare_cached(SERIES_EPISODE_GROUPS_SQL)?
        .query_map(params![series_id], |r| {
            Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?))
        })?
        .collect::<Result<_, _>>()?;
    Ok(seasons
        .into_iter()
        .map(|(id, index)| {
            let (mut total, mut real) = (0, 0);
            for (pin, pid, count, non_virtual) in &groups {
                let hit = match (pin, index) {
                    (Some(p), Some(i)) => *p == i,
                    (None, _) => pid.as_deref() == Some(id.as_str()),
                    _ => false,
                };
                if hit {
                    total += count;
                    real += non_virtual;
                }
            }
            (id, total, real)
        })
        .collect())
}

/// `children_checked`'s Season branch: resolves episodes by (series, season number) instead
/// of literal `parent_id` equality; see that call site for the duplicate-Season bug this
/// fixes. The `OR`'s second arm is a `parent_id` fallback for an episode with no
/// `ParentIndexNumber` at all, so it doesn't silently vanish from every season's list.
/// The statement comes from [`episodes_of_season_sql`]; `INDEXED BY` is deliberately not
/// pinned, and `episodes_of_season_query_does_not_scan_items` asserts it never degrades to a
/// full scan.
fn episodes_of_season_checked(
    conn: &Connection,
    season_id: &str,
    series_id: &str,
    season_index: i32,
    sort: Sort,
    offset: u32,
    limit: u32,
) -> rusqlite::Result<Vec<CardRow>> {
    let sql = episodes_of_season_sql(sort);
    let mut stmt = conn.prepare_cached(&sql)?;
    let rows = stmt.query_map(
        params![series_id, season_index, season_id, limit, offset],
        row_to_card,
    )?;
    rows.collect()
}

/// Index order gets a `UNION ALL` of the two seekable branches (`(series_id,
/// parent_index_number)` and the NULL-index `parent_id` fallback): the equivalent `OR` seeks on
/// `series_id` alone and filters. The ORDER BY of a compound select must name result columns,
/// which `parent_index_number, index_number` are. Other sorts keep the `OR` form.
fn episodes_of_season_sql(sort: Sort) -> String {
    if sort == Sort::IndexNumber {
        return format!(
            "SELECT {c} FROM items WHERE item_type = 'Episode' AND series_id = ?1 \
                AND parent_index_number = ?2 \
             UNION ALL \
             SELECT {c} FROM items WHERE item_type = 'Episode' AND series_id = ?1 \
                AND parent_index_number IS NULL AND parent_id = ?3 \
             ORDER BY {o} LIMIT ?4 OFFSET ?5",
            c = CARD_COLUMNS,
            o = sort_clause(sort)
        );
    }
    format!(
        "SELECT {CARD_COLUMNS} FROM items \
         WHERE item_type = 'Episode' AND series_id = ?1 \
         AND (parent_index_number = ?2 \
              OR (parent_index_number IS NULL AND parent_id = ?3)) \
         ORDER BY {} LIMIT ?4 OFFSET ?5",
        sort_clause(sort)
    )
}

/// [`favorites`]' statement. Only a Series or Season looks at its episodes, each through one
/// seek on a played-rows partial index, so a long-running show costs no more than a movie.
fn favorites_sql() -> String {
    format!(
        "SELECT {CARD_COLUMNS} {FAVORITES_FROM_SQL} \
         ORDER BY COALESCE(last_played_date, CASE item_type \
             WHEN 'Series' THEN (SELECT MAX(e.last_played_date) FROM items e \
                 WHERE e.series_id = items.id AND e.last_played_date IS NOT NULL) \
             WHEN 'Season' THEN (SELECT MAX(e.last_played_date) FROM items e \
                 WHERE e.parent_id = items.id AND e.last_played_date IS NOT NULL) END) \
             DESC NULLS LAST, sort_name COLLATE NOCASE, id \
         LIMIT ?1"
    )
}

/// docs/07 §1: the Home Favorites shelf, most recently played first (a Series or Season counts
/// its episodes' plays), then by name. Favorites carry no timestamp of their own.
pub(crate) fn favorites(conn: &Connection, limit: u32) -> Vec<CardRow> {
    let result = (|| -> rusqlite::Result<Vec<CardRow>> {
        let mut stmt = conn.prepare_cached(&favorites_sql())?;
        let rows = stmt.query_map([limit], row_to_card)?;
        rows.collect()
    })();
    result.unwrap_or_else(|e| {
        tracing::error!(error = %e, "favorites query failed");
        Vec::new()
    })
}

/// docs/07 §5: whether any favorite exists, for the drawer entry; one partial-index probe.
pub(crate) fn has_favorites(conn: &Connection) -> bool {
    conn.query_row(
        &format!("SELECT EXISTS(SELECT 1 {FAVORITES_FROM_SQL})"),
        [],
        |row| row.get(0),
    )
    .unwrap_or_else(|e| {
        tracing::error!(error = %e, "has_favorites query failed");
        false
    })
}

/// docs/16 §2.7: the item types present among favorites, for the Favorites grid's Type
/// panel, in the fixed Movie/Series/Season/Episode/BoxSet order.
pub(crate) fn favorite_item_types(conn: &Connection) -> Vec<String> {
    let sql = format!(
        "SELECT DISTINCT item_type {FAVORITES_FROM_SQL} \
         ORDER BY CASE item_type WHEN 'Movie' THEN 0 WHEN 'Series' THEN 1 \
         WHEN 'Season' THEN 2 WHEN 'Episode' THEN 3 ELSE 4 END"
    );
    let result = (|| -> rusqlite::Result<Vec<String>> {
        let mut stmt = conn.prepare_cached(&sql)?;
        let rows = stmt.query_map([], |row| row.get(0))?;
        rows.collect()
    })();
    result.unwrap_or_else(|e| {
        tracing::error!(error = %e, "favorite_item_types query failed");
        Vec::new()
    })
}

/// Sorts by server-authoritative `last_played_date`, not the local `updated_at` write clock
/// (see schema.rs); `updated_at DESC, id` are deterministic tiebreakers. Only rows inside the
/// grace windows (docs/07 §1) match, read straight off `idx_items_in_progress` once
/// [`IndexGroup::Resume`](crate::schema::IndexGroup::Resume) is built.
pub(crate) fn resume(conn: &Connection, limit: u32) -> Vec<CardRow> {
    let sql = format!(
        "SELECT {CARD_COLUMNS} FROM items WHERE {} \
         ORDER BY last_played_date DESC NULLS LAST, updated_at DESC, id ASC LIMIT ?1",
        crate::watch_grace::in_progress_sql!()
    );
    let result = (|| -> rusqlite::Result<Vec<CardRow>> {
        let mut stmt = conn.prepare_cached(&sql)?;
        let rows = stmt.query_map(params![limit], row_to_card)?;
        rows.collect()
    })();
    result.unwrap_or_else(|e| {
        tracing::error!(error = %e, "resume query failed");
        Vec::new()
    })
}

/// Whether any of `ids` names an Episode row (primary-key lookups; an error reads as no).
pub(crate) fn any_episode(conn: &Connection, ids: &[String]) -> bool {
    if ids.is_empty() {
        return false;
    }
    let placeholders = vec!["?"; ids.len()].join(",");
    let sql = format!(
        "SELECT EXISTS(SELECT 1 FROM items WHERE item_type = 'Episode' AND id IN ({placeholders}))"
    );
    conn.query_row(&sql, rusqlite::params_from_iter(ids), |row| row.get(0))
        .unwrap_or(false)
}

/// "Next Up" isn't derivable from indexed columns alone; the sync engine mirrors
/// `/Shows/NextUp`'s ordered id list into `meta.next_up_ids`, and this resolves those ids
/// back to rows (primary-key lookup, not a scan), preserving server order.
pub(crate) fn next_up(conn: &Connection, limit: u32) -> Vec<CardRow> {
    let ids = match crate::schema::read_meta(conn, "next_up_ids") {
        Some(json) => serde_json::from_str::<Vec<String>>(&json).unwrap_or_default(),
        None => return Vec::new(),
    };
    let ids: Vec<String> = ids.into_iter().take(limit as usize).collect();
    cards_by_ids(conn, &ids)
}

/// The cards for `ids` in one primary-key lookup, in the order asked (`IN (...)` gives no
/// ordering guarantee); an unknown id is simply absent.
pub(crate) fn cards_by_ids(conn: &Connection, ids: &[String]) -> Vec<CardRow> {
    if ids.is_empty() {
        return Vec::new();
    }
    let placeholders = std::iter::repeat_n("?", ids.len())
        .collect::<Vec<_>>()
        .join(",");
    let sql = format!("SELECT {CARD_COLUMNS} FROM items WHERE id IN ({placeholders})");
    let result = (|| -> rusqlite::Result<Vec<CardRow>> {
        let mut stmt = conn.prepare_cached(&sql)?;
        let rows = stmt.query_map(rusqlite::params_from_iter(ids.iter()), row_to_card)?;
        rows.collect::<rusqlite::Result<Vec<_>>>()
    })();
    let mut rows = result.unwrap_or_else(|e| {
        tracing::error!(error = %e, "cards_by_ids query failed");
        Vec::new()
    });
    let order: std::collections::HashMap<&str, usize> = ids
        .iter()
        .enumerate()
        .map(|(i, id)| (id.as_str(), i))
        .collect();
    rows.sort_by_key(|r| order.get(r.id.as_str()).copied().unwrap_or(usize::MAX));
    rows
}

/// Scoped by `library_id` (see schema.rs), so two libraries sharing a `collection_type`
/// don't show identical "Latest" rows (docs/07 §1). Implements Jellyfin's Latest-Media
/// grouping for a `tvshows` view (one `CardRow` per series; see `latest_grouped_series`);
/// every other collection type stays per-item. `hide_watched` excludes displayed-watched rows (docs/07 §1);
/// deliberately not threaded into `resume()`/`next_up()`, which are unwatched/in-progress by
/// construction.
///
/// `watched_index_ready`: whether [`IndexGroup::LatestWatched`](crate::schema::IndexGroup)
/// is built; a hide-watched grouped read uses it, else the older index (never empty).
pub(crate) fn latest(
    conn: &Connection,
    view_id: &str,
    limit: u32,
    hide_watched: bool,
    watched_index_ready: bool,
) -> Vec<CardRow> {
    let collection_type: Option<String> = conn
        .query_row(
            "SELECT collection_type FROM views WHERE id = ?1",
            [view_id],
            |row| row.get(0),
        )
        .ok();
    let Some(collection_type) = collection_type else {
        return Vec::new();
    };

    if collection_type == "tvshows" {
        return latest_grouped_series(conn, view_id, limit, hide_watched, watched_index_ready);
    }

    let item_types = crate::item_types_for_collection(&collection_type);
    if item_types.is_empty() {
        return Vec::new();
    }

    let placeholders = std::iter::repeat_n("?", item_types.len())
        .collect::<Vec<_>>()
        .join(",");
    let played_filter = if hide_watched {
        concat!(" AND NOT ", crate::watch_grace::watched_sql!(""))
    } else {
        ""
    };
    // `limit` is bound rather than interpolated, matching every other query in this module.
    // `is_virtual = 0`: a virtual placeholder's `date_created` is its metadata-refresh time,
    // not a real media file's; unfiltered, minting hundreds at once would flood Latest.
    // Jellyfin Web applies the same exclusion server-side.
    let sql = format!(
        "SELECT {CARD_COLUMNS} FROM items WHERE library_id = ? AND item_type IN ({placeholders}) AND is_virtual = 0{played_filter} ORDER BY date_created DESC LIMIT ?"
    );
    let result = (|| -> rusqlite::Result<Vec<CardRow>> {
        let mut stmt = conn.prepare_cached(&sql)?;
        let mut params: Vec<&dyn rusqlite::ToSql> = vec![&view_id as &dyn rusqlite::ToSql];
        params.extend(item_types.iter().map(|s| s as &dyn rusqlite::ToSql));
        params.push(&limit);
        let rows = stmt.query_map(params.as_slice(), row_to_card)?;
        rows.collect()
    })();
    result.unwrap_or_else(|e| {
        tracing::error!(error = %e, "latest query failed");
        Vec::new()
    })
}

/// `is_virtual = 0` (see `latest`) also means a series whose only recent episodes are
/// virtual placeholders drops out of the grouping entirely, matching Jellyfin Web.
fn grouped_series_sql(index: &str, played_filter: &str, cols: &str) -> String {
    format!(
        "WITH grouped AS (\
             SELECT series_id, MAX(date_created) AS newest_episode \
             FROM items INDEXED BY {index} \
             WHERE library_id = ?1 AND item_type = 'Episode' AND series_id IS NOT NULL \
             AND is_virtual = 0{played_filter} \
             GROUP BY series_id \
             ORDER BY newest_episode DESC \
             LIMIT ?2\
         ) \
         SELECT {cols} FROM grouped JOIN items ON items.id = grouped.series_id \
         ORDER BY grouped.newest_episode DESC"
    )
}

/// Jellyfin's Latest-Media semantics for a TV library: one tile per series, ordered by its
/// most-recently added episode, not one tile per episode (otherwise a binge-worthy season
/// floods the shelf with the same poster). Mirrors `/Users/{id}/Items/Latest?GroupItems=true`.
///
/// A CTE groups Episodes by `series_id`, keeping each series' newest `date_created` and the
/// top `limit` series, then joins back to `items` for each series' own row.
/// `hide_watched` (see `latest`) is applied on the Episode rows inside the CTE, not the
/// returned Series row, so a series whose only recent activity is watched drops out entirely.
fn latest_grouped_series(
    conn: &Connection,
    view_id: &str,
    limit: u32,
    hide_watched: bool,
    watched_index_ready: bool,
) -> Vec<CardRow> {
    // `INDEXED BY` a missing index is an error, so the widened one is gated on its group.
    let index = if hide_watched && watched_index_ready {
        crate::schema::LATEST_WATCHED_INDEX
    } else {
        "idx_items_latest_series"
    };
    let played_filter = if hide_watched {
        concat!(" AND NOT ", crate::watch_grace::watched_sql!(""))
    } else {
        ""
    };
    let sql = grouped_series_sql(index, played_filter, &qualified_card_columns());
    let result = (|| -> rusqlite::Result<Vec<CardRow>> {
        let mut stmt = conn.prepare_cached(&sql)?;
        let rows = stmt.query_map(params![view_id, limit], row_to_card)?;
        rows.collect()
    })();
    result.unwrap_or_else(|e| {
        tracing::error!(error = %e, "latest_grouped_series query failed");
        Vec::new()
    })
}

/// Builds a safe FTS5 MATCH expression: split on whitespace, quote each token (so stray
/// FTS5 syntax chars can't leak in), append `*` for prefix matching, AND them together.
fn fts_query(input: &str) -> Option<String> {
    let terms: Vec<String> = input
        .split_whitespace()
        .map(|term| format!("\"{}\"*", term.replace('"', "\"\"")))
        .collect();
    if terms.is_empty() {
        None
    } else {
        Some(terms.join(" "))
    }
}

pub(crate) fn search(conn: &Connection, query: &str, limit: u32) -> Vec<CardRow> {
    let Some(match_expr) = fts_query(query) else {
        return Vec::new();
    };
    let sql = format!(
        "SELECT {cols} FROM search JOIN items ON items.rowid = search.rowid \
         WHERE search MATCH ?1 ORDER BY rank LIMIT ?2",
        cols = qualified_card_columns()
    );
    let result = (|| -> rusqlite::Result<Vec<CardRow>> {
        let mut stmt = conn.prepare_cached(&sql)?;
        let rows = stmt.query_map(params![match_expr, limit], row_to_card)?;
        rows.collect()
    })();
    result.unwrap_or_else(|e| {
        tracing::error!(error = %e, query, "search query failed");
        Vec::new()
    })
}

/// `writer::apply_local_user_data`/`writer::apply_user_data` both patch the dedicated
/// `played`/`playback_position_ticks`/etc. columns and deliberately never rewrite the `dto`
/// blob. Every other browse query reads those columns directly; `item()` used to be the one
/// exception, handing back the blob's possibly-stale `user_data`, which fed a stale resume
/// position into mpv on a quick re-play. Overlaying the columns here (the one place still
/// allowed to touch the blob) makes them the source of truth for `user_data` everywhere.
///
/// `item()`'s raw row shape, named so the query doesn't trip clippy's `type_complexity` lint.
type ItemUserDataRow = (Vec<u8>, bool, i64, i32, bool, Option<i32>, Option<String>);

/// One `CardRow` by primary key, the same shape every browse query produces, for an id from
/// outside any listing (e.g. Discover's "Go to library" routing). `None` covers both "unknown
/// id" and a query error, same fold as [`item`].
pub(crate) fn card_by_id(conn: &Connection, id: &str) -> Option<CardRow> {
    conn.prepare_cached(&format!("SELECT {CARD_COLUMNS} FROM items WHERE id = ?1"))
        .and_then(|mut stmt| stmt.query_row([id], row_to_card))
        .ok()
}

pub(crate) fn item(conn: &Connection, id: &str) -> Option<BaseItemDto> {
    let row: Option<ItemUserDataRow> = conn
        .prepare_cached(
            "SELECT dto, played, playback_position_ticks, play_count, is_favorite, \
             unplayed_item_count, last_played_date FROM items WHERE id = ?1",
        )
        .and_then(|mut stmt| {
            stmt.query_row([id], |row| {
                Ok((
                    row.get(0)?,
                    row.get(1)?,
                    row.get(2)?,
                    row.get(3)?,
                    row.get(4)?,
                    row.get(5)?,
                    row.get(6)?,
                ))
            })
        })
        .ok();
    let (blob, played, position_ticks, play_count, is_favorite, unplayed_count, last_played) = row?;
    let mut dto: BaseItemDto = serde_json::from_slice(&blob).ok()?;
    overlay_local_user_data(
        &mut dto,
        played,
        position_ticks,
        play_count,
        is_favorite,
        unplayed_count,
        last_played,
    );
    Some(dto)
}

/// Batch form of [`item`]. SQLite has a default 999-parameter limit, so this splits any
/// number of ids into bounded queries.
pub(crate) fn items(
    conn: &Connection,
    ids: &[String],
) -> std::collections::HashMap<String, BaseItemDto> {
    const SQLITE_PARAMETER_BATCH: usize = 900;
    let mut result = std::collections::HashMap::with_capacity(ids.len());
    for ids in ids.chunks(SQLITE_PARAMETER_BATCH) {
        let placeholders = std::iter::repeat_n("?", ids.len())
            .collect::<Vec<_>>()
            .join(",");
        let sql = format!(
            "SELECT id, dto, played, playback_position_ticks, play_count, is_favorite, \
             unplayed_item_count, last_played_date FROM items WHERE id IN ({placeholders})"
        );
        let rows = (|| -> rusqlite::Result<Vec<(String, ItemUserDataRow)>> {
            let mut stmt = conn.prepare(&sql)?;
            let rows = stmt.query_map(rusqlite::params_from_iter(ids), |row| {
                Ok((
                    row.get(0)?,
                    (
                        row.get(1)?,
                        row.get(2)?,
                        row.get(3)?,
                        row.get(4)?,
                        row.get(5)?,
                        row.get(6)?,
                        row.get(7)?,
                    ),
                ))
            })?;
            rows.collect()
        })();
        match rows {
            Ok(rows) => {
                for (
                    id,
                    (
                        blob,
                        played,
                        position_ticks,
                        play_count,
                        is_favorite,
                        unplayed_count,
                        last_played,
                    ),
                ) in rows
                {
                    if let Ok(mut dto) = serde_json::from_slice::<BaseItemDto>(&blob) {
                        overlay_local_user_data(
                            &mut dto,
                            played,
                            position_ticks,
                            play_count,
                            is_favorite,
                            unplayed_count,
                            last_played,
                        );
                        result.insert(id, dto);
                    }
                }
            }
            Err(error) => tracing::error!(error = %error, "batched item query failed"),
        }
    }
    result
}

/// Overlays the mirror's authoritative watch-state columns onto the parsed blob's
/// `user_data`, creating one if the blob had none. `key` has no local equivalent and is left
/// empty; nothing reads it.
fn overlay_local_user_data(
    dto: &mut BaseItemDto,
    played: bool,
    position_ticks: i64,
    play_count: i32,
    is_favorite: bool,
    unplayed_count: Option<i32>,
    last_played_date: Option<String>,
) {
    let last_played_date = last_played_date.and_then(|s| {
        chrono::DateTime::parse_from_rfc3339(&s)
            .ok()
            .map(|d| d.with_timezone(&chrono::Utc))
    });
    let user_data = dto.user_data.get_or_insert_default();
    user_data.played = Some(played);
    user_data.playback_position_ticks = Some(position_ticks);
    user_data.play_count = Some(play_count);
    user_data.is_favorite = Some(is_favorite);
    user_data.unplayed_item_count = unplayed_count;
    if last_played_date.is_some() {
        user_data.last_played_date = last_played_date;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::schema::open_test_db;
    use crate::writer::{apply_local_user_data, apply_upsert_items, apply_upsert_items_scoped};
    use jellyfin_api::models::{BaseItemKind, UserItemDataDto};

    fn item_dto(id: &str, name: &str, parent: Option<&str>, kind: BaseItemKind) -> BaseItemDto {
        BaseItemDto {
            id: Some(uuid::Uuid::parse_str(id).expect("uuid")),
            name: Some(name.to_string()),
            sort_name: Some(name.to_string()),
            type_: Some(kind),
            parent_id: parent.map(|p| uuid::Uuid::parse_str(p).expect("uuid")),
            ..Default::default()
        }
    }

    fn uuid_n(n: u8) -> String {
        format!("e2f5a5f1-1a0b-4b3a-9c2e-{n:012}")
    }

    fn explain(conn: &Connection, sql: &str, params: &[&dyn rusqlite::ToSql]) -> Vec<String> {
        let plan_sql = format!("EXPLAIN QUERY PLAN {sql}");
        let mut stmt = conn.prepare(&plan_sql).expect("prepare explain");
        let rows: Vec<String> = stmt
            .query_map(params, |row| row.get::<_, String>(3))
            .expect("query")
            .collect::<Result<_, _>>()
            .expect("rows");
        rows
    }

    fn assert_no_items_scan(plan: &[String]) {
        for line in plan {
            assert!(
                !line.contains("SCAN items"),
                "expected no full scan of items, got plan line: {line:?} (full plan: {plan:?})"
            );
        }
    }

    #[test]
    fn item_type_counts_group_by_type_and_skip_virtual_items() {
        let (_dir, conn) = open_test_db();
        for (id, item_type, is_virtual) in [
            ("a", "Movie", 0),
            ("b", "Movie", 0),
            ("c", "Episode", 0),
            ("d", "Episode", 1),
        ] {
            conn.execute(
                "INSERT INTO items (id, item_type, is_virtual, dto, updated_at) VALUES (?1, ?2, ?3, '{}', 0)",
                params![id, item_type, is_virtual],
            )
            .expect("insert");
        }

        let mut counts = item_type_counts(&conn);
        counts.sort();

        assert_eq!(
            counts,
            vec![("Episode".to_string(), 1), ("Movie".to_string(), 2)]
        );
    }

    #[test]
    fn children_query_uses_index_not_scan() {
        let (_dir, conn) = open_test_db();
        // Pins that `children()`'s `Sort::NameAsc` SQL (see its doc comment for why
        // `INDEXED BY` is pinned explicitly) stays index-served; a temp b-tree to finish the
        // `sort_name` order is expected, so only the *scan* is asserted against.
        let plan = explain(
            &conn,
            "SELECT id FROM items INDEXED BY idx_items_browse \
             WHERE parent_id = ?1 ORDER BY sort_name COLLATE NOCASE ASC LIMIT ?2 OFFSET ?3",
            params![String::from("p"), 10u32, 0u32],
        );
        assert_no_items_scan(&plan);
        assert!(
            plan.iter().any(|l| l.contains("idx_items_browse")),
            "plan: {plan:?}"
        );
    }

    /// Pins that Detail's Series -> Seasons / Season -> Episodes browse stays index-served
    /// under `Sort::IndexNumber` too (the whole point of `idx_items_parent_order`), not a
    /// temp b-tree sort.
    #[test]
    fn children_query_with_index_number_sort_uses_index_not_scan() {
        let (_dir, conn) = open_test_db();
        let plan = explain(
            &conn,
            "SELECT id FROM items WHERE parent_id = ?1 \
             ORDER BY parent_index_number ASC, index_number ASC LIMIT ?2 OFFSET ?3",
            params![String::from("p"), 10u32, 0u32],
        );
        assert_no_items_scan(&plan);
        assert!(
            !plan.iter().any(|l| l.contains("USE TEMP B-TREE")),
            "expected the index to satisfy ORDER BY directly, plan: {plan:?}"
        );
        assert!(
            plan.iter().any(|l| l.contains("idx_items_parent_order")),
            "plan: {plan:?}"
        );
    }

    #[test]
    fn resume_query_uses_partial_index_not_scan() {
        let (_dir, conn) = open_test_db();
        // docs/07 §1: once its index group is built, `resume()` reads only Continue Watching's
        // rows, already in order; before that it walks `idx_items_resume_by_last_played` and
        // filters. Neither needs a sort step or an `items` scan.
        let sql = format!(
            "SELECT id FROM items WHERE {} \
             ORDER BY last_played_date DESC NULLS LAST, updated_at DESC, id ASC LIMIT ?1",
            crate::watch_grace::in_progress_sql!()
        );
        for index in ["idx_items_in_progress", "idx_items_resume_by_last_played"] {
            let plan = explain(&conn, &sql, params![10u32]);
            assert!(
                plan.iter().any(|l| l.contains(index)),
                "{index}: plan: {plan:?}"
            );
            assert!(
                !plan.iter().any(|l| l.contains("TEMP B-TREE")),
                "{index}: plan: {plan:?}"
            );
            conn.execute_batch("DROP INDEX IF EXISTS idx_items_in_progress")
                .expect("drop");
        }
    }

    #[test]
    fn latest_query_uses_index_not_scan() {
        let (_dir, conn) = open_test_db();
        // Matches `latest()`'s SQL for a non-tvshows view.
        let plan = explain(
            &conn,
            "SELECT id FROM items WHERE library_id = ?1 AND item_type IN ('Movie') AND is_virtual = 0 ORDER BY date_created DESC LIMIT ?2",
            params![String::from("lib1"), 10u32],
        );
        assert_no_items_scan(&plan);
        assert!(
            plan.iter().any(|l| l.contains("idx_items_latest_virtual")),
            "plan: {plan:?}"
        );
    }

    /// The `latest_grouped_series` CTE walks every Episode of a library, so it must group in
    /// `idx_items_latest_series` order (no temp b-tree) with and without the watched filter.
    #[test]
    fn latest_grouped_series_query_groups_in_index_order() {
        let (_dir, conn) = open_test_db();
        for played_filter in [
            "",
            concat!(" AND NOT ", crate::watch_grace::watched_sql!("")),
        ] {
            let sql = format!(
                "WITH grouped AS (\
                     SELECT series_id, MAX(date_created) AS newest_episode \
                     FROM items INDEXED BY idx_items_latest_series \
                     WHERE library_id = ?1 AND item_type = 'Episode' AND series_id IS NOT NULL \
                     AND is_virtual = 0{played_filter} \
                     GROUP BY series_id \
                     ORDER BY newest_episode DESC \
                     LIMIT ?2\
                 ) \
                 SELECT items.id FROM grouped JOIN items ON items.id = grouped.series_id \
                 ORDER BY grouped.newest_episode DESC"
            );
            let plan = explain(&conn, &sql, params![String::from("lib1"), 10u32]);
            assert_no_items_scan(&plan);
            assert!(
                plan.iter().any(|l| l.contains("idx_items_latest_series")),
                "plan: {plan:?}"
            );
            assert!(
                !plan.iter().any(|l| l.contains("TEMP B-TREE FOR GROUP BY")),
                "plan: {plan:?}"
            );
        }
    }

    /// Hide-watched grouping reads the widened `idx_items_latest_series_watched` entirely from
    /// the index (COVERING, no temp b-tree); the pre-widening index only reaches INDEX.
    #[test]
    fn latest_grouped_series_hide_watched_is_covering_on_the_widened_index() {
        let (_dir, conn) = open_test_db();
        let watched = concat!(" AND NOT ", crate::watch_grace::watched_sql!(""));
        let plan_for = |index: &str| {
            explain(
                &conn,
                &grouped_series_sql(index, watched, "items.id"),
                params![String::from("lib1"), 10u32],
            )
        };
        let plan = plan_for(crate::schema::LATEST_WATCHED_INDEX);
        assert!(
            plan.iter()
                .any(|l| l.contains("COVERING INDEX idx_items_latest_series_watched")),
            "plan: {plan:?}"
        );
        assert!(
            !plan.iter().any(|l| l.contains("TEMP B-TREE FOR GROUP BY")),
            "plan: {plan:?}"
        );
    }

    /// The widened-index route and the fallback (group not built yet) return the same cards.
    #[test]
    fn latest_grouped_series_same_rows_with_or_without_the_widened_index() {
        let (_dir, mut conn) = open_test_db();
        conn.execute(
            "INSERT INTO views (id, name, collection_type, sort_index) VALUES ('shows', 'Shows', 'tvshows', 0)",
            [],
        )
        .expect("insert view");
        let series = uuid_n(1);
        apply_upsert_items_scoped(
            &mut conn,
            &[item_dto(&series, "Series", None, BaseItemKind::Series)],
            Some("shows"),
        )
        .expect("insert series");
        let mut ep = item_dto(&uuid_n(2), "S1E1", Some(&series), BaseItemKind::Episode);
        ep.series_id = Some(uuid::Uuid::parse_str(&series).expect("uuid"));
        ep.date_created = Some("2024-06-01T00:00:00Z".parse().expect("date"));
        apply_upsert_items_scoped(&mut conn, &[ep], Some("shows")).expect("insert episode");

        let fallback = latest(&conn, "shows", 10, true, false);
        let widened = latest(&conn, "shows", 10, true, true);
        assert_eq!(fallback.len(), 1);
        assert_eq!(fallback, widened);
    }

    #[test]
    fn collection_membership_children_query_uses_index_not_scan() {
        let (_dir, conn) = open_test_db();
        let plan = explain(
            &conn,
            "SELECT items.id FROM collection_members cm JOIN items ON items.id = cm.item_id \
             WHERE cm.collection_id = ?1 ORDER BY cm.sort_index ASC LIMIT ?2 OFFSET ?3",
            params![String::from("boxset-1"), 10u32, 0u32],
        );
        assert_no_items_scan(&plan);
        assert!(
            !plan.iter().any(|l| l.contains("SCAN cm")),
            "expected no full scan of collection_members, plan: {plan:?}"
        );
        assert!(
            plan.iter()
                .any(|l| l.contains("idx_collection_members_order")),
            "plan: {plan:?}"
        );
    }

    #[test]
    fn next_up_lookup_is_pk_search_not_scan() {
        let (_dir, conn) = open_test_db();
        let plan = explain(
            &conn,
            "SELECT id FROM items WHERE id IN (?1, ?2)",
            params!["a", "b"],
        );
        assert_no_items_scan(&plan);
    }

    #[test]
    fn search_join_does_not_scan_items() {
        let (_dir, conn) = open_test_db();
        let plan = explain(
            &conn,
            "SELECT items.id FROM search JOIN items ON items.rowid = search.rowid WHERE search MATCH ?1 ORDER BY rank LIMIT ?2",
            params!["hello", 10u32],
        );
        assert_no_items_scan(&plan);
    }

    #[test]
    fn children_returns_rows_sorted_by_name() {
        let (_dir, mut conn) = open_test_db();
        let parent = uuid_n(1);
        apply_upsert_items(
            &mut conn,
            &[
                item_dto(&uuid_n(2), "Bravo", Some(&parent), BaseItemKind::Movie),
                item_dto(&uuid_n(3), "Alpha", Some(&parent), BaseItemKind::Movie),
            ],
        )
        .expect("insert");

        let rows = children(&conn, &parent, Sort::NameAsc, 0, 10);
        assert_eq!(
            rows.iter().map(|r| r.name.as_str()).collect::<Vec<_>>(),
            vec!["Alpha", "Bravo"]
        );
    }

    /// `sort_clause`'s `Sort::NameAsc` must match the grid's `COLLATE NOCASE`, not a binary
    /// sort that would put every lowercase-led title after every uppercase-led one.
    #[test]
    fn children_name_sort_is_case_insensitive() {
        let (_dir, mut conn) = open_test_db();
        let parent = uuid_n(1);
        apply_upsert_items(
            &mut conn,
            &[
                item_dto(&uuid_n(2), "banana", Some(&parent), BaseItemKind::Movie),
                item_dto(&uuid_n(3), "Apple", Some(&parent), BaseItemKind::Movie),
                item_dto(&uuid_n(4), "cherry", Some(&parent), BaseItemKind::Movie),
            ],
        )
        .expect("insert");

        let rows = children(&conn, &parent, Sort::NameAsc, 0, 10);
        assert_eq!(
            rows.iter().map(|r| r.name.as_str()).collect::<Vec<_>>(),
            vec!["Apple", "banana", "cherry"]
        );
    }

    /// `card_by_id` must return exactly the same `CardRow` a listing query returns for that id.
    #[test]
    fn card_by_id_matches_the_row_a_listing_query_returns_for_the_same_id() {
        let (_dir, mut conn) = open_test_db();
        let parent = uuid_n(1);
        let child = uuid_n(2);
        apply_upsert_items(
            &mut conn,
            &[item_dto(
                &child,
                "Alpha",
                Some(&parent),
                BaseItemKind::Movie,
            )],
        )
        .expect("insert");

        let from_listing = children(&conn, &parent, Sort::NameAsc, 0, 10)
            .into_iter()
            .find(|row| row.id == child)
            .expect("the listing query should include the item just inserted");

        let from_card_by_id = card_by_id(&conn, &child).expect("card_by_id should find it");

        assert_eq!(from_card_by_id, from_listing);
    }

    #[test]
    fn cards_by_ids_keeps_the_order_asked_and_drops_unknown_ids() {
        let (_dir, mut conn) = open_test_db();
        let parent = uuid_n(1);
        let (a, b, missing) = (uuid_n(2), uuid_n(3), uuid_n(4));
        apply_upsert_items(
            &mut conn,
            &[
                item_dto(&a, "Alpha", Some(&parent), BaseItemKind::Movie),
                item_dto(&b, "Beta", Some(&parent), BaseItemKind::Movie),
            ],
        )
        .expect("insert");

        let ids = vec![b.clone(), missing, a.clone()];
        let got: Vec<String> = cards_by_ids(&conn, &ids)
            .into_iter()
            .map(|r| r.id)
            .collect();
        assert_eq!(got, vec![b, a]);
        assert!(cards_by_ids(&conn, &[]).is_empty());
    }

    #[test]
    fn card_by_id_returns_none_for_an_unknown_id() {
        let (_dir, conn) = open_test_db();
        assert!(card_by_id(&conn, &uuid_n(99)).is_none());
    }

    /// Episode order must come from `index_number`, not name (alphabetical would put
    /// "Episode 10" before "Episode 2").
    #[test]
    fn children_with_index_number_sort_orders_by_episode_number_not_name() {
        let (_dir, mut conn) = open_test_db();
        let season = uuid_n(1);
        let mut ep2 = item_dto(
            &uuid_n(2),
            "Zeta Episode",
            Some(&season),
            BaseItemKind::Episode,
        );
        ep2.index_number = Some(2);
        let mut ep10 = item_dto(
            &uuid_n(3),
            "Alpha Episode",
            Some(&season),
            BaseItemKind::Episode,
        );
        ep10.index_number = Some(10);
        let mut ep1 = item_dto(
            &uuid_n(4),
            "Mid Episode",
            Some(&season),
            BaseItemKind::Episode,
        );
        ep1.index_number = Some(1);
        apply_upsert_items(&mut conn, &[ep2, ep10, ep1]).expect("insert");

        let rows = children(&conn, &season, Sort::IndexNumber, 0, 10);
        assert_eq!(
            rows.iter().map(|r| r.index_number).collect::<Vec<_>>(),
            vec![Some(1), Some(2), Some(10)],
            "must sort by index_number, not name -- got: {:?}",
            rows.iter().map(|r| &r.name).collect::<Vec<_>>()
        );
    }

    /// Artwork fallback-chain fields (docs/07 §2) must round-trip through a real upsert +
    /// `children()` read, not just `extract_columns` in isolation.
    #[test]
    fn children_carries_artwork_fallback_fields() {
        let (_dir, mut conn) = open_test_db();
        let series = uuid_n(1);
        let mut ep = item_dto(&uuid_n(2), "Ep", Some(&series), BaseItemKind::Episode);
        ep.series_id = Some(uuid::Uuid::parse_str(&series).expect("uuid"));
        ep.series_primary_image_tag = Some("series-poster".to_string());
        ep.parent_backdrop_item_id = Some(uuid::Uuid::parse_str(&uuid_n(9)).expect("uuid"));
        ep.parent_backdrop_image_tags = vec!["parent-backdrop".to_string()];
        ep.parent_thumb_item_id = Some(uuid::Uuid::parse_str(&uuid_n(8)).expect("uuid"));
        ep.parent_thumb_image_tag = Some("parent-thumb".to_string());
        apply_upsert_items(&mut conn, &[ep]).expect("insert");

        let rows = children(&conn, &series, Sort::IndexNumber, 0, 10);
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0].series_id.as_deref(), Some(series.as_str()));
        assert_eq!(rows[0].series_primary_tag.as_deref(), Some("series-poster"));
        assert_eq!(
            rows[0].parent_backdrop_item_id.as_deref(),
            Some(uuid_n(9).as_str())
        );
        assert_eq!(
            rows[0].parent_backdrop_tag.as_deref(),
            Some("parent-backdrop")
        );
        assert_eq!(
            rows[0].parent_thumb_item_id.as_deref(),
            Some(uuid_n(8).as_str())
        );
        assert_eq!(rows[0].parent_thumb_tag.as_deref(), Some("parent-thumb"));
    }

    /// `CardRow::is_virtual`/`premiere_date` must round-trip: `LocationType == "Virtual"`
    /// becomes `is_virtual: true`, with `premiere_date` as an RFC3339 string.
    #[test]
    fn children_carries_virtual_and_premiere_date_fields() {
        let (_dir, mut conn) = open_test_db();
        let series = uuid_n(1);
        let mut ep = item_dto(
            &uuid_n(2),
            "Unaired Ep",
            Some(&series),
            BaseItemKind::Episode,
        );
        ep.series_id = Some(uuid::Uuid::parse_str(&series).expect("uuid"));
        ep.location_type = Some(jellyfin_api::models::LocationType::Virtual);
        ep.premiere_date = Some("2099-01-15T00:00:00Z".parse().expect("datetime"));
        apply_upsert_items(&mut conn, &[ep]).expect("insert");

        let rows = children(&conn, &series, Sort::IndexNumber, 0, 10);
        assert_eq!(rows.len(), 1);
        assert!(rows[0].is_virtual);
        assert_eq!(
            rows[0].premiere_date.as_deref(),
            Some("2099-01-15T00:00:00+00:00")
        );
    }

    #[test]
    fn children_of_a_non_virtual_item_is_not_virtual() {
        let (_dir, mut conn) = open_test_db();
        let series = uuid_n(1);
        let mut ep = item_dto(&uuid_n(2), "Aired Ep", Some(&series), BaseItemKind::Episode);
        ep.series_id = Some(uuid::Uuid::parse_str(&series).expect("uuid"));
        ep.location_type = Some(jellyfin_api::models::LocationType::FileSystem);
        apply_upsert_items(&mut conn, &[ep]).expect("insert");

        let rows = children(&conn, &series, Sort::IndexNumber, 0, 10);
        assert_eq!(rows.len(), 1);
        assert!(!rows[0].is_virtual);
    }

    #[test]
    fn children_of_a_boxset_resolves_through_collection_membership() {
        let (_dir, mut conn) = open_test_db();
        let boxset_id = uuid_n(1);
        let movie_a = uuid_n(2);
        let movie_b = uuid_n(3);
        let unrelated = uuid_n(4);

        apply_upsert_items(
            &mut conn,
            &[
                item_dto(&boxset_id, "A Collection", None, BaseItemKind::BoxSet),
                // Movies live under their library's parent_id, not the boxset's.
                item_dto(&movie_a, "Zeta", None, BaseItemKind::Movie),
                item_dto(&movie_b, "Alpha", None, BaseItemKind::Movie),
                item_dto(&unrelated, "Not In The Set", None, BaseItemKind::Movie),
            ],
        )
        .expect("insert");
        crate::writer::apply_set_collection_members(
            &mut conn,
            &boxset_id,
            // Server curation order deliberately not alphabetical.
            &[(movie_a.clone(), 0), (movie_b.clone(), 1)],
        )
        .expect("set members");

        // parent_id equality would find nothing (children's parent_id is
        // None here); the membership join must be what resolves this.
        let rows = children(&conn, &boxset_id, Sort::NameAsc, 0, 10);
        assert_eq!(
            rows.iter().map(|r| r.id.as_str()).collect::<Vec<_>>(),
            vec![movie_a.as_str(), movie_b.as_str()],
            "must follow collection_members' sort_index, not alphabetical Sort"
        );
    }

    /// A `Folder` parent (not `BoxSet`/`Season`/`Series`, each special-cased) pins the plain
    /// `parent_id` equality fallback path.
    #[test]
    fn children_of_a_non_boxset_parent_ignores_collection_membership() {
        let (_dir, mut conn) = open_test_db();
        let parent = uuid_n(1);
        let child = uuid_n(2);
        apply_upsert_items(
            &mut conn,
            &[
                item_dto(&parent, "A Folder", None, BaseItemKind::Folder),
                item_dto(&child, "Episode", Some(&parent), BaseItemKind::Episode),
            ],
        )
        .expect("insert");

        let rows = children(&conn, &parent, Sort::NameAsc, 0, 10);
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0].id, child);
    }

    /// Hazard 2 (docs/24-server-virtual-items.md): a "seasons" listing
    /// must not surface junk release-directory `Folder` rows as season tabs;
    /// `children(series_id, ...)` must return only `Season` rows.
    #[test]
    fn series_children_are_seasons_only_folder_children_ignored() {
        let (_dir, mut conn) = open_test_db();
        let series = uuid_n(1);
        let season1 = uuid_n(2);
        let junk_folder = uuid_n(3);
        apply_upsert_items(
            &mut conn,
            &[
                item_dto(&series, "A Series", None, BaseItemKind::Series),
                {
                    let mut s = item_dto(&season1, "Season 1", Some(&series), BaseItemKind::Season);
                    s.series_id = Some(uuid::Uuid::parse_str(&series).expect("uuid"));
                    s.index_number = Some(1);
                    s
                },
                item_dto(
                    &junk_folder,
                    "Show.S05E01.1080p.HEVC.x265-GROUP",
                    Some(&series),
                    BaseItemKind::Folder,
                ),
            ],
        )
        .expect("insert");

        let rows = children(&conn, &series, Sort::IndexNumber, 0, 10);
        assert_eq!(
            rows.iter().map(|r| r.id.as_str()).collect::<Vec<_>>(),
            vec![season1.as_str()],
            "the junk release-directory Folder must never appear as a season tab -- got {rows:?}"
        );
    }

    /// Hazard 2b: a virtual Season (no physical directory, but real playable episodes) must
    /// still appear in the seasons listing.
    #[test]
    fn series_children_retain_virtual_seasons() {
        let (_dir, mut conn) = open_test_db();
        let series = uuid_n(1);
        let virtual_season = uuid_n(2);
        apply_upsert_items(
            &mut conn,
            &[item_dto(&series, "A Series", None, BaseItemKind::Series), {
                let mut s = item_dto(
                    &virtual_season,
                    "Season 5",
                    Some(&series),
                    BaseItemKind::Season,
                );
                s.series_id = Some(uuid::Uuid::parse_str(&series).expect("uuid"));
                s.index_number = Some(5);
                s.location_type = Some(jellyfin_api::models::LocationType::Virtual);
                s
            }],
        )
        .expect("insert");

        let rows = children(&conn, &series, Sort::IndexNumber, 0, 10);
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0].id, virtual_season);
        assert!(
            rows[0].is_virtual,
            "a virtual Season must be retained in the seasons listing, not dropped"
        );
    }

    /// The Series -> Seasons listing must not degrade to a full `items` scan.
    #[test]
    fn seasons_of_series_query_does_not_scan_items() {
        let (_dir, conn) = open_test_db();
        let plan = explain(
            &conn,
            "SELECT id FROM items \
             WHERE item_type = 'Season' \
             AND (series_id = ?1 OR (series_id IS NULL AND parent_id = ?1)) \
             ORDER BY parent_index_number ASC, index_number ASC LIMIT ?2 OFFSET ?3",
            params![String::from("series-1"), 10u32, 0u32],
        );
        assert_no_items_scan(&plan);
    }

    fn episode_dto(
        id: &str,
        name: &str,
        series: &str,
        season: &str,
        season_index: i32,
        is_virtual: bool,
    ) -> BaseItemDto {
        let mut e = item_dto(id, name, Some(season), BaseItemKind::Episode);
        e.series_id = Some(uuid::Uuid::parse_str(series).expect("uuid"));
        e.season_id = Some(uuid::Uuid::parse_str(season).expect("uuid"));
        e.parent_index_number = Some(season_index);
        if is_virtual {
            e.location_type = Some(jellyfin_api::models::LocationType::Virtual);
        }
        e
    }

    fn find_counts<'a>(
        counts: &'a [(String, i64, i64)],
        season_id: &str,
    ) -> &'a (String, i64, i64) {
        counts
            .iter()
            .find(|(id, _, _)| id == season_id)
            .unwrap_or_else(|| panic!("no counts row for season {season_id}, got {counts:?}"))
    }

    /// A season with a real (non-virtual) episode must report it in `non_virtual_episodes`.
    #[test]
    fn season_episode_counts_reports_real_episodes() {
        let (_dir, mut conn) = open_test_db();
        let series = uuid_n(1);
        let season = uuid_n(2);
        let mut season_dto = item_dto(&season, "Season 1", Some(&series), BaseItemKind::Season);
        season_dto.series_id = Some(uuid::Uuid::parse_str(&series).expect("uuid"));
        season_dto.index_number = Some(1);
        apply_upsert_items(&mut conn, &[season_dto]).expect("insert season");
        apply_upsert_items(
            &mut conn,
            &[episode_dto(&uuid_n(3), "S1E1", &series, &season, 1, false)],
        )
        .expect("insert episode");

        let counts = season_episode_counts(&conn, &series).expect("query");
        assert_eq!(find_counts(&counts, &season), &(season.clone(), 1, 1));
    }

    /// A fully-unaired season (every episode virtual) must report `non_virtual_episodes ==
    /// 0` even though `total_episodes > 0`, the signal `filter_all_virtual_seasons` hides on.
    #[test]
    fn season_episode_counts_reports_zero_non_virtual_for_all_virtual_season() {
        let (_dir, mut conn) = open_test_db();
        let series = uuid_n(1);
        let season = uuid_n(2);
        let mut season_dto = item_dto(&season, "Season 5", Some(&series), BaseItemKind::Season);
        season_dto.series_id = Some(uuid::Uuid::parse_str(&series).expect("uuid"));
        season_dto.index_number = Some(5);
        apply_upsert_items(&mut conn, &[season_dto]).expect("insert season");
        apply_upsert_items(
            &mut conn,
            &[
                episode_dto(&uuid_n(3), "S5E1", &series, &season, 5, true),
                episode_dto(&uuid_n(4), "S5E2", &series, &season, 5, true),
            ],
        )
        .expect("insert episodes");

        let counts = season_episode_counts(&conn, &series).expect("query");
        assert_eq!(find_counts(&counts, &season), &(season.clone(), 2, 0));
    }

    /// A season with no episode rows synced yet must report `(0, 0)`, the caller's fail-open
    /// case, never treated the same as "all virtual".
    #[test]
    fn season_episode_counts_reports_zero_total_for_a_season_with_no_episode_rows() {
        let (_dir, mut conn) = open_test_db();
        let series = uuid_n(1);
        let season = uuid_n(2);
        let mut season_dto = item_dto(&season, "Season 9", Some(&series), BaseItemKind::Season);
        season_dto.series_id = Some(uuid::Uuid::parse_str(&series).expect("uuid"));
        season_dto.index_number = Some(9);
        apply_upsert_items(&mut conn, &[season_dto]).expect("insert season");

        let counts = season_episode_counts(&conn, &series).expect("query");
        assert_eq!(find_counts(&counts, &season), &(season.clone(), 0, 0));
    }

    /// Two seasons of the same series must have fully disjoint counts.
    #[test]
    fn season_episode_counts_does_not_bleed_across_seasons() {
        let (_dir, mut conn) = open_test_db();
        let series = uuid_n(1);
        let season1 = uuid_n(2);
        let season2 = uuid_n(3);
        let mut season1_dto = item_dto(&season1, "Season 1", Some(&series), BaseItemKind::Season);
        season1_dto.series_id = Some(uuid::Uuid::parse_str(&series).expect("uuid"));
        season1_dto.index_number = Some(1);
        let mut season2_dto = item_dto(&season2, "Season 2", Some(&series), BaseItemKind::Season);
        season2_dto.series_id = Some(uuid::Uuid::parse_str(&series).expect("uuid"));
        season2_dto.index_number = Some(2);
        apply_upsert_items(&mut conn, &[season1_dto, season2_dto]).expect("insert seasons");
        apply_upsert_items(
            &mut conn,
            &[
                episode_dto(&uuid_n(4), "S1E1", &series, &season1, 1, false),
                episode_dto(&uuid_n(5), "S2E1", &series, &season2, 2, true),
            ],
        )
        .expect("insert episodes");

        let counts = season_episode_counts(&conn, &series).expect("query");
        assert_eq!(find_counts(&counts, &season1), &(season1.clone(), 1, 1));
        assert_eq!(find_counts(&counts, &season2), &(season2.clone(), 1, 0));
    }

    /// Neither of the season-count statements degrades to a full `items` scan.
    #[test]
    fn season_episode_counts_query_does_not_scan_items() {
        let (_dir, conn) = open_test_db();
        for sql in [SERIES_SEASONS_SQL, SERIES_EPISODE_GROUPS_SQL] {
            assert_no_items_scan(&explain(&conn, sql, params![String::from("series-1")]));
        }
    }

    #[test]
    fn any_episode_is_true_only_when_an_id_names_an_episode() {
        let (_dir, mut conn) = open_test_db();
        let movie = item_dto(&uuid_n(1), "Movie", None, BaseItemKind::Movie);
        let episode = item_dto(&uuid_n(2), "Episode", None, BaseItemKind::Episode);
        apply_upsert_items(&mut conn, &[movie, episode]).expect("insert");
        assert!(!any_episode(&conn, &[]));
        assert!(!any_episode(&conn, &[uuid_n(1), uuid_n(9)]));
        assert!(any_episode(&conn, &[uuid_n(1), uuid_n(2)]));
    }

    #[test]
    fn resume_only_returns_items_with_progress() {
        let (_dir, mut conn) = open_test_db();
        let mut watched = item_dto(&uuid_n(1), "Watched", None, BaseItemKind::Movie);
        watched.user_data = Some(jellyfin_api::models::UserItemDataDto {
            played: Some(false),
            playback_position_ticks: Some(10 * MIN),
            play_count: Some(0),
            is_favorite: None,
            unplayed_item_count: None,
            key: Some("k".to_string()),
            item_id: None,
            last_played_date: None,
            likes: None,
            played_percentage: None,
            rating: None,
        });
        let unwatched = item_dto(&uuid_n(2), "Fresh", None, BaseItemKind::Movie);
        apply_upsert_items(&mut conn, &[watched, unwatched]).expect("insert");

        let rows = resume(&conn, 10);
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0].name, "Watched");
    }

    /// docs/07 §1: rows inside either grace window never reach the shelf, and skipping them
    /// doesn't shorten it.
    #[test]
    fn resume_skips_rows_inside_the_grace_windows_and_still_fills_the_limit() {
        let (_dir, mut conn) = open_test_db();
        let dated = |day: u32| Some(format!("2024-01-{day:02}T00:00:00Z").parse().expect("date"));
        let movie = |n: u8, name: &str, position: i64, day: u32| {
            let mut dto = item_dto(&uuid_n(n), name, None, BaseItemKind::Movie);
            dto.run_time_ticks = Some(120 * MIN);
            dto.user_data = Some(user_data_with_progress(position, dated(day)));
            dto
        };
        apply_upsert_items(
            &mut conn,
            &[
                movie(1, "Barely started", MIN, 9),
                movie(2, "In the credits", 115 * MIN, 8),
                movie(3, "Halfway", 60 * MIN, 7),
                movie(4, "Early", 5 * MIN, 6),
            ],
        )
        .expect("insert");

        let names: Vec<_> = resume(&conn, 2).into_iter().map(|r| r.name).collect();
        assert_eq!(names, ["Halfway", "Early"]);
        // Same answer from the fallback walk a not-yet-indexed mirror takes.
        conn.execute_batch("DROP INDEX idx_items_in_progress")
            .expect("drop");
        let names: Vec<_> = resume(&conn, 2).into_iter().map(|r| r.name).collect();
        assert_eq!(names, ["Halfway", "Early"]);
    }

    // Resume fixtures sit past the 2-minute start grace (docs/07 §1).
    use crate::watch_grace::TICKS_PER_MINUTE as MIN;

    fn user_data_with_progress(
        position_ticks: i64,
        last_played_date: Option<::chrono::DateTime<::chrono::Utc>>,
    ) -> jellyfin_api::models::UserItemDataDto {
        jellyfin_api::models::UserItemDataDto {
            played: Some(false),
            playback_position_ticks: Some(position_ticks),
            play_count: Some(0),
            is_favorite: None,
            unplayed_item_count: None,
            key: Some("k".to_string()),
            item_id: None,
            last_played_date,
            likes: None,
            played_percentage: None,
            rating: None,
        }
    }

    /// `resume()` must order by server-authoritative `LastPlayedDate`, not the local write
    /// clock (see schema.rs's `last_played_date`).
    #[test]
    fn resume_orders_by_last_played_date_not_local_write_clock() {
        let (_dir, mut conn) = open_test_db();
        let older = "2024-01-01T00:00:00Z".parse().expect("date");
        let newer = "2024-06-01T00:00:00Z".parse().expect("date");

        let mut a = item_dto(&uuid_n(1), "A", None, BaseItemKind::Movie);
        a.user_data = Some(user_data_with_progress(10 * MIN, Some(older)));
        let mut b = item_dto(&uuid_n(2), "B", None, BaseItemKind::Movie);
        b.user_data = Some(user_data_with_progress(20 * MIN, Some(newer)));
        apply_upsert_items(&mut conn, &[a.clone(), b.clone()]).expect("insert");

        let rows = resume(&conn, 10);
        assert_eq!(
            rows.iter().map(|r| r.id.clone()).collect::<Vec<_>>(),
            vec![b.id.expect("id").to_string(), a.id.expect("id").to_string()],
            "newer LastPlayedDate must sort first"
        );
    }

    /// Re-upserting identical user-state (a reconcile pass touching an unchanged row) must
    /// not reorder `resume()`, even though it still bumps `updated_at`.
    #[test]
    fn resume_order_is_stable_across_reupserts_of_identical_user_state() {
        let (_dir, mut conn) = open_test_db();
        let older = "2024-01-01T00:00:00Z".parse().expect("date");
        let newer = "2024-06-01T00:00:00Z".parse().expect("date");

        let mut a = item_dto(&uuid_n(1), "A", None, BaseItemKind::Movie);
        a.user_data = Some(user_data_with_progress(10 * MIN, Some(older)));
        let mut b = item_dto(&uuid_n(2), "B", None, BaseItemKind::Movie);
        b.user_data = Some(user_data_with_progress(20 * MIN, Some(newer)));
        apply_upsert_items(&mut conn, &[a.clone(), b]).expect("insert");

        let before = resume(&conn, 10)
            .iter()
            .map(|r| r.id.clone())
            .collect::<Vec<_>>();

        // Simulate a reconciliation pass re-upserting "A" with the exact
        // same UserData (same position, same LastPlayedDate) -- this still
        // bumps `updated_at` (every upsert does), but must NOT move "A"
        // ahead of "B" in `resume()`'s ordering.
        std::thread::sleep(std::time::Duration::from_millis(2));
        apply_upsert_items(&mut conn, &[a]).expect("re-upsert identical state");

        let after = resume(&conn, 10)
            .iter()
            .map(|r| r.id.clone())
            .collect::<Vec<_>>();

        assert_eq!(
            before, after,
            "re-upserting identical user-state must not reorder resume()"
        );
    }

    /// Two rows with the same `last_played_date` must break the tie deterministically by
    /// `updated_at DESC`, not fall back to physical row order.
    #[test]
    fn resume_breaks_last_played_date_ties_deterministically() {
        let (_dir, mut conn) = open_test_db();
        let same = "2024-01-01T00:00:00Z".parse().expect("date");

        let mut a = item_dto(&uuid_n(1), "A", None, BaseItemKind::Movie);
        a.user_data = Some(user_data_with_progress(10 * MIN, Some(same)));
        apply_upsert_items(&mut conn, &[a.clone()]).expect("insert a");

        // A short sleep guarantees b's updated_at is strictly later than a's.
        std::thread::sleep(std::time::Duration::from_millis(2));
        let mut b = item_dto(&uuid_n(2), "B", None, BaseItemKind::Movie);
        b.user_data = Some(user_data_with_progress(20 * MIN, Some(same)));
        apply_upsert_items(&mut conn, &[b.clone()]).expect("insert b");

        let rows = resume(&conn, 10);
        assert_eq!(
            rows.iter().map(|r| r.id.clone()).collect::<Vec<_>>(),
            vec![b.id.expect("id").to_string(), a.id.expect("id").to_string()],
            "equal last_played_date must break the tie by updated_at DESC -- the more \
             recently locally-written row sorts first"
        );
    }

    /// NULL `last_played_date` rows must sort after every dated row, and break ties among
    /// themselves deterministically too.
    #[test]
    fn resume_sorts_null_last_played_date_rows_last_and_deterministically() {
        let (_dir, mut conn) = open_test_db();
        let dated = "2024-01-01T00:00:00Z".parse().expect("date");

        let mut has_date = item_dto(&uuid_n(1), "Dated", None, BaseItemKind::Movie);
        has_date.user_data = Some(user_data_with_progress(10 * MIN, Some(dated)));
        let mut null_a = item_dto(&uuid_n(2), "NullA", None, BaseItemKind::Movie);
        null_a.user_data = Some(user_data_with_progress(5 * MIN, None));
        apply_upsert_items(&mut conn, &[has_date.clone(), null_a.clone()])
            .expect("insert first two");

        std::thread::sleep(std::time::Duration::from_millis(2));
        let mut null_b = item_dto(&uuid_n(3), "NullB", None, BaseItemKind::Movie);
        null_b.user_data = Some(user_data_with_progress(7 * MIN, None));
        apply_upsert_items(&mut conn, &[null_b.clone()]).expect("insert null_b later");

        let rows = resume(&conn, 10);
        assert_eq!(
            rows.iter().map(|r| r.id.clone()).collect::<Vec<_>>(),
            vec![
                has_date.id.expect("id").to_string(),
                null_b.id.expect("id").to_string(),
                null_a.id.expect("id").to_string(),
            ],
            "NULL last_played_date rows must sort after every dated row, and break their \
             own tie by updated_at DESC"
        );
    }

    #[test]
    fn latest_maps_view_collection_type_to_item_type() {
        let (_dir, mut conn) = open_test_db();
        conn.execute(
            "INSERT INTO views (id, name, collection_type, sort_index) VALUES ('lib1', 'Movies', 'movies', 0)",
            [],
        )
        .expect("insert view");
        apply_upsert_items_scoped(
            &mut conn,
            &[item_dto(&uuid_n(1), "A Movie", None, BaseItemKind::Movie)],
            Some("lib1"),
        )
        .expect("insert");
        apply_upsert_items_scoped(
            &mut conn,
            &[item_dto(&uuid_n(2), "A Series", None, BaseItemKind::Series)],
            Some("lib1"),
        )
        .expect("insert");

        let rows = latest(&conn, "lib1", 10, false, false);
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0].name, "A Movie");
    }

    /// Two libraries sharing a `collection_type` must have fully disjoint `latest()`
    /// results, scoped by library, not just `item_type`.
    #[test]
    fn latest_scopes_by_library_not_just_item_type() {
        let (_dir, mut conn) = open_test_db();
        conn.execute(
            "INSERT INTO views (id, name, collection_type, sort_index) VALUES ('library-a', 'Library A', 'movies', 0)",
            [],
        )
        .expect("insert view");
        conn.execute(
            "INSERT INTO views (id, name, collection_type, sort_index) VALUES ('library-b', 'Library B', 'movies', 1)",
            [],
        )
        .expect("insert view");
        apply_upsert_items_scoped(
            &mut conn,
            &[item_dto(&uuid_n(1), "Movie A", None, BaseItemKind::Movie)],
            Some("library-a"),
        )
        .expect("insert");
        apply_upsert_items_scoped(
            &mut conn,
            &[item_dto(&uuid_n(2), "Movie B", None, BaseItemKind::Movie)],
            Some("library-b"),
        )
        .expect("insert");

        let library_a_rows = latest(&conn, "library-a", 10, false, false);
        let library_b_rows = latest(&conn, "library-b", 10, false, false);
        assert_eq!(
            library_a_rows
                .iter()
                .map(|r| r.name.as_str())
                .collect::<Vec<_>>(),
            vec!["Movie A"]
        );
        assert_eq!(
            library_b_rows
                .iter()
                .map(|r| r.name.as_str())
                .collect::<Vec<_>>(),
            vec!["Movie B"]
        );
    }

    /// An item with no `library_id` must not show up in any library's Latest shelf, rather
    /// than leaking into all of them.
    #[test]
    fn latest_excludes_items_with_no_library_id() {
        let (_dir, mut conn) = open_test_db();
        conn.execute(
            "INSERT INTO views (id, name, collection_type, sort_index) VALUES ('lib1', 'Movies', 'movies', 0)",
            [],
        )
        .expect("insert view");
        apply_upsert_items(
            &mut conn,
            &[item_dto(
                &uuid_n(1),
                "Unscoped Movie",
                None,
                BaseItemKind::Movie,
            )],
        )
        .expect("insert");

        assert!(latest(&conn, "lib1", 10, false, false).is_empty());
    }

    /// `hide_watched = true` excludes an already-played item; `false` keeps it.
    #[test]
    fn latest_hides_watched_items_only_when_asked() {
        let (_dir, mut conn) = open_test_db();
        conn.execute(
            "INSERT INTO views (id, name, collection_type, sort_index) VALUES ('lib1', 'Movies', 'movies', 0)",
            [],
        )
        .expect("insert view");
        let mut watched = item_dto(&uuid_n(1), "Watched Movie", None, BaseItemKind::Movie);
        watched.user_data = Some(jellyfin_api::models::UserItemDataDto {
            played: Some(true),
            playback_position_ticks: Some(0),
            play_count: Some(1),
            is_favorite: None,
            unplayed_item_count: None,
            key: Some("k".to_string()),
            item_id: None,
            last_played_date: None,
            likes: None,
            played_percentage: None,
            rating: None,
        });
        let unwatched = item_dto(&uuid_n(2), "Unwatched Movie", None, BaseItemKind::Movie);
        apply_upsert_items_scoped(&mut conn, &[watched, unwatched], Some("lib1")).expect("insert");

        let with_watched = latest(&conn, "lib1", 10, false, false);
        assert_eq!(
            with_watched
                .iter()
                .map(|r| r.name.as_str())
                .collect::<std::collections::HashSet<_>>(),
            std::collections::HashSet::from(["Watched Movie", "Unwatched Movie"]),
            "hide_watched=false must keep today's behavior -- both items show"
        );

        let hidden = latest(&conn, "lib1", 10, true, false);
        assert_eq!(
            hidden.iter().map(|r| r.name.as_str()).collect::<Vec<_>>(),
            vec!["Unwatched Movie"],
            "hide_watched=true must exclude the already-played item"
        );
    }

    /// Hazard 1 (docs/24-server-virtual-items.md): a virtual
    /// placeholder's `date_created` (metadata-refresh time) must never flood Latest.
    #[test]
    fn latest_excludes_virtual_items() {
        let (_dir, mut conn) = open_test_db();
        conn.execute(
            "INSERT INTO views (id, name, collection_type, sort_index) VALUES ('lib1', 'Movies', 'movies', 0)",
            [],
        )
        .expect("insert view");
        let mut virtual_movie = item_dto(&uuid_n(1), "Announced Movie", None, BaseItemKind::Movie);
        virtual_movie.location_type = Some(jellyfin_api::models::LocationType::Virtual);
        let real_movie = item_dto(&uuid_n(2), "Real Movie", None, BaseItemKind::Movie);
        apply_upsert_items_scoped(&mut conn, &[virtual_movie, real_movie], Some("lib1"))
            .expect("insert");

        let rows = latest(&conn, "lib1", 10, false, false);
        assert_eq!(
            rows.iter().map(|r| r.name.as_str()).collect::<Vec<_>>(),
            vec!["Real Movie"],
            "a virtual placeholder item must never appear in Latest"
        );
    }

    /// Two episodes for the same series must surface exactly one row, ordered by each
    /// series' newest episode regardless of episode count.
    #[test]
    fn latest_groups_tvshows_by_series_ordered_by_newest_episode() {
        let (_dir, mut conn) = open_test_db();
        conn.execute(
            "INSERT INTO views (id, name, collection_type, sort_index) VALUES ('shows', 'Shows', 'tvshows', 0)",
            [],
        )
        .expect("insert view");

        let series_a = uuid_n(1);
        let series_b = uuid_n(2);
        apply_upsert_items_scoped(
            &mut conn,
            &[
                item_dto(&series_a, "Series A", None, BaseItemKind::Series),
                item_dto(&series_b, "Series B", None, BaseItemKind::Series),
            ],
            Some("shows"),
        )
        .expect("insert series");

        let mut ep_a1 = item_dto(&uuid_n(3), "A S1E1", Some(&series_a), BaseItemKind::Episode);
        ep_a1.series_id = Some(uuid::Uuid::parse_str(&series_a).expect("uuid"));
        ep_a1.date_created = Some("2024-01-01T00:00:00Z".parse().expect("date"));
        let mut ep_a2 = item_dto(&uuid_n(4), "A S1E2", Some(&series_a), BaseItemKind::Episode);
        ep_a2.series_id = Some(uuid::Uuid::parse_str(&series_a).expect("uuid"));
        ep_a2.date_created = Some("2024-06-01T00:00:00Z".parse().expect("date"));
        let mut ep_b1 = item_dto(&uuid_n(5), "B S1E1", Some(&series_b), BaseItemKind::Episode);
        ep_b1.series_id = Some(uuid::Uuid::parse_str(&series_b).expect("uuid"));
        ep_b1.date_created = Some("2024-03-01T00:00:00Z".parse().expect("date"));

        apply_upsert_items_scoped(&mut conn, &[ep_a1, ep_a2, ep_b1], Some("shows"))
            .expect("insert episodes");

        let rows = latest(&conn, "shows", 10, false, false);
        assert_eq!(
            rows.iter().map(|r| r.name.as_str()).collect::<Vec<_>>(),
            vec!["Series A", "Series B"],
            "Series A's newest episode (2024-06-01) beats Series B's (2024-03-01); \
             each series must appear exactly once regardless of episode count -- got {rows:?}"
        );
    }

    /// Hazard 1: the grouped branch must also exclude virtual episodes from the grouping; a
    /// series whose only episode is virtual must drop out entirely.
    #[test]
    fn latest_grouped_series_excludes_virtual_episodes() {
        let (_dir, mut conn) = open_test_db();
        conn.execute(
            "INSERT INTO views (id, name, collection_type, sort_index) VALUES ('shows', 'Shows', 'tvshows', 0)",
            [],
        )
        .expect("insert view");

        let series_a = uuid_n(1); // only virtual episodes, must vanish
        let series_b = uuid_n(2); // older real episode, but must still win
        apply_upsert_items_scoped(
            &mut conn,
            &[
                item_dto(&series_a, "Only Virtual Series", None, BaseItemKind::Series),
                item_dto(&series_b, "Real Series", None, BaseItemKind::Series),
            ],
            Some("shows"),
        )
        .expect("insert series");

        let mut ep_a_virtual =
            item_dto(&uuid_n(3), "A S1E1", Some(&series_a), BaseItemKind::Episode);
        ep_a_virtual.series_id = Some(uuid::Uuid::parse_str(&series_a).expect("uuid"));
        ep_a_virtual.date_created = Some("2024-09-01T00:00:00Z".parse().expect("date"));
        ep_a_virtual.location_type = Some(jellyfin_api::models::LocationType::Virtual);

        let mut ep_b_real = item_dto(&uuid_n(4), "B S1E1", Some(&series_b), BaseItemKind::Episode);
        ep_b_real.series_id = Some(uuid::Uuid::parse_str(&series_b).expect("uuid"));
        ep_b_real.date_created = Some("2024-01-01T00:00:00Z".parse().expect("date"));

        apply_upsert_items_scoped(&mut conn, &[ep_a_virtual, ep_b_real], Some("shows"))
            .expect("insert episodes");

        let rows = latest(&conn, "shows", 10, false, false);
        assert_eq!(
            rows.iter().map(|r| r.name.as_str()).collect::<Vec<_>>(),
            vec!["Real Series"],
            "a series whose only episode is virtual must be absent from Latest, \
             even though its virtual episode's date is newer than the real \
             series' real episode -- got {rows:?}"
        );
    }

    /// The grouped branch's `hide_watched`: a series whose only recent episode is watched
    /// drops out entirely; a mix surfaces via its newest unwatched episode's date.
    #[test]
    fn latest_grouped_series_hides_watched_episodes_only_when_asked() {
        let (_dir, mut conn) = open_test_db();
        conn.execute(
            "INSERT INTO views (id, name, collection_type, sort_index) VALUES ('shows', 'Shows', 'tvshows', 0)",
            [],
        )
        .expect("insert view");

        let series_a = uuid_n(1); // fully watched -- must vanish when hidden
        let series_b = uuid_n(2); // mixed -- newest UNWATCHED episode wins
        apply_upsert_items_scoped(
            &mut conn,
            &[
                item_dto(&series_a, "Series A", None, BaseItemKind::Series),
                item_dto(&series_b, "Series B", None, BaseItemKind::Series),
            ],
            Some("shows"),
        )
        .expect("insert series");

        let watched_data = |ticks: i64| {
            Some(jellyfin_api::models::UserItemDataDto {
                played: Some(true),
                playback_position_ticks: Some(0),
                play_count: Some(1),
                is_favorite: None,
                unplayed_item_count: None,
                key: Some(format!("k{ticks}")),
                item_id: None,
                last_played_date: None,
                likes: None,
                played_percentage: None,
                rating: None,
            })
        };

        let mut ep_a1 = item_dto(&uuid_n(3), "A S1E1", Some(&series_a), BaseItemKind::Episode);
        ep_a1.series_id = Some(uuid::Uuid::parse_str(&series_a).expect("uuid"));
        ep_a1.date_created = Some("2024-06-01T00:00:00Z".parse().expect("date"));
        ep_a1.user_data = watched_data(1);

        let mut ep_b1 = item_dto(&uuid_n(4), "B S1E1", Some(&series_b), BaseItemKind::Episode);
        ep_b1.series_id = Some(uuid::Uuid::parse_str(&series_b).expect("uuid"));
        ep_b1.date_created = Some("2024-05-01T00:00:00Z".parse().expect("date"));
        ep_b1.user_data = watched_data(2);
        let mut ep_b2 = item_dto(&uuid_n(5), "B S1E2", Some(&series_b), BaseItemKind::Episode);
        ep_b2.series_id = Some(uuid::Uuid::parse_str(&series_b).expect("uuid"));
        ep_b2.date_created = Some("2024-03-01T00:00:00Z".parse().expect("date"));
        // ep_b2 left unwatched.

        apply_upsert_items_scoped(&mut conn, &[ep_a1, ep_b1, ep_b2], Some("shows"))
            .expect("insert episodes");

        let with_watched = latest(&conn, "shows", 10, false, false);
        assert_eq!(
            with_watched
                .iter()
                .map(|r| r.name.as_str())
                .collect::<Vec<_>>(),
            vec!["Series A", "Series B"],
            "hide_watched=false must keep today's behavior -- both series show"
        );

        let hidden = latest(&conn, "shows", 10, true, false);
        assert_eq!(
            hidden.iter().map(|r| r.name.as_str()).collect::<Vec<_>>(),
            vec!["Series B"],
            "Series A had only a watched episode and must vanish entirely; \
             Series B must still surface via its unwatched episode"
        );
    }

    #[test]
    fn search_finds_by_name_prefix() {
        let (_dir, mut conn) = open_test_db();
        apply_upsert_items(
            &mut conn,
            &[item_dto(
                &uuid_n(1),
                "Quantum Static",
                None,
                BaseItemKind::Series,
            )],
        )
        .expect("insert");

        let rows = search(&conn, "Quant", 10);
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0].name, "Quantum Static");
    }

    #[test]
    fn search_matches_titles_but_not_overviews() {
        let (_dir, mut conn) = open_test_db();
        let mut overview_only = item_dto(&uuid_n(1), "Synthetic Short", None, BaseItemKind::Movie);
        overview_only.overview = Some("An animated tale and another".to_string());
        let mut original_title = item_dto(&uuid_n(2), "Localised", None, BaseItemKind::Movie);
        original_title.original_title = Some("Anglerfish Nights".to_string());
        let mut episode = item_dto(&uuid_n(3), "Pilot", None, BaseItemKind::Episode);
        episode.series_name = Some("Antenna Hour".to_string());
        apply_upsert_items(&mut conn, &[overview_only, original_title, episode]).expect("insert");

        let mut names: Vec<String> = search(&conn, "an", 10)
            .into_iter()
            .map(|r| r.name)
            .collect();
        names.sort();
        assert_eq!(names, vec!["Localised", "Pilot"]);
    }

    #[test]
    fn search_ands_every_term_within_titles() {
        let (_dir, mut conn) = open_test_db();
        apply_upsert_items(
            &mut conn,
            &[
                item_dto(&uuid_n(1), "Quantum Static", None, BaseItemKind::Series),
                item_dto(&uuid_n(2), "Quantum Drift", None, BaseItemKind::Series),
            ],
        )
        .expect("insert");

        let rows = search(&conn, "quant stat", 10);
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0].name, "Quantum Static");
    }

    #[test]
    fn search_with_empty_query_returns_nothing() {
        let (_dir, conn) = open_test_db();
        assert!(search(&conn, "", 10).is_empty());
        assert!(search(&conn, "   ", 10).is_empty());
    }

    #[test]
    fn search_query_with_quotes_does_not_error() {
        let (_dir, conn) = open_test_db();
        // Must not panic or error even with FTS5-syntax-looking input.
        let rows = search(&conn, "\"weird\" OR *query", 10);
        assert!(rows.is_empty());
    }

    #[test]
    fn item_returns_full_dto() {
        let (_dir, mut conn) = open_test_db();
        let id = uuid_n(1);
        apply_upsert_items(
            &mut conn,
            &[item_dto(&id, "Detail Me", None, BaseItemKind::Movie)],
        )
        .expect("insert");
        let dto = item(&conn, &id).expect("found");
        assert_eq!(dto.name.as_deref(), Some("Detail Me"));
    }

    /// `item()`'s blob is never patched by `apply_local_user_data`'s column-only write, so
    /// `item()` must overlay the columns to reflect a fresh local report.
    #[test]
    fn item_reflects_apply_local_user_data_even_though_the_blob_itself_is_never_rewritten() {
        let (_dir, mut conn) = open_test_db();
        let id = uuid_n(1);
        apply_upsert_items(
            &mut conn,
            &[item_dto(&id, "Resume Me", None, BaseItemKind::Movie)],
        )
        .expect("insert");
        assert!(
            item(&conn, &id)
                .expect("found")
                .user_data
                .and_then(|u| u.playback_position_ticks)
                .unwrap_or(0)
                == 0,
            "sanity check: a freshly-synced item starts with no resume position"
        );

        apply_local_user_data(&mut conn, &id, 12_345, None).expect("apply");

        let dto = item(&conn, &id).expect("found");
        assert_eq!(
            dto.user_data.and_then(|u| u.playback_position_ticks),
            Some(12_345),
            "item() must reflect apply_local_user_data's position even though \
             it never rewrites the dto blob"
        );
    }

    #[test]
    fn item_missing_returns_none() {
        let (_dir, conn) = open_test_db();
        assert!(item(&conn, "nope").is_none());
    }

    /// `uuid_n` caps at 255 (n as u8), so batched tests use their own wider ids.
    fn uuid_wide(n: u32) -> String {
        format!("e2f5a5f1-1a0b-4b3a-9c2e-{n:012}")
    }

    /// `items()` splits ids into 900-sized chunks internally; only inserting more than one
    /// chunk's worth exercises that boundary.
    #[test]
    fn items_returns_all_rows_across_the_parameter_chunk_boundary() {
        let (_dir, mut conn) = open_test_db();
        const N: u32 = 950;
        let dtos: Vec<BaseItemDto> = (0..N)
            .map(|n| item_dto(&uuid_wide(n), "Item", None, BaseItemKind::Movie))
            .collect();
        apply_upsert_items(&mut conn, &dtos).expect("insert");

        let ids: Vec<String> = (0..N).map(uuid_wide).collect();
        let result = items(&conn, &ids);

        assert_eq!(
            result.len(),
            N as usize,
            "must return every row, including those past the 900-parameter \
             chunk boundary"
        );
        for id in &ids {
            assert!(result.contains_key(id), "missing id {id} in result");
        }
    }

    /// `items()` must resolve what it can, silently drop unknown/duplicate ids, and never
    /// panic or error.
    #[test]
    fn items_skips_missing_and_duplicate_ids_cleanly() {
        let (_dir, mut conn) = open_test_db();
        let present_a = uuid_n(1);
        let present_b = uuid_n(2);
        let absent = uuid_n(3);
        apply_upsert_items(
            &mut conn,
            &[
                item_dto(&present_a, "A", None, BaseItemKind::Movie),
                item_dto(&present_b, "B", None, BaseItemKind::Movie),
            ],
        )
        .expect("insert");

        let ids = vec![
            present_a.clone(),
            absent.clone(),
            present_b.clone(),
            present_a.clone(),
        ];
        let result = items(&conn, &ids);

        assert_eq!(
            result.len(),
            2,
            "duplicate/absent ids must not inflate or error"
        );
        assert_eq!(
            result.get(&present_a).and_then(|d| d.name.as_deref()),
            Some("A")
        );
        assert_eq!(
            result.get(&present_b).and_then(|d| d.name.as_deref()),
            Some("B")
        );
        assert!(
            !result.contains_key(&absent),
            "an id with no matching row must simply be absent from the map"
        );
    }

    /// `items()`'s row mapper hand-maintains column indices separate from `item()`'s (the
    /// batched SELECT prepends `id`, shifting every index by one); pins that both decode the
    /// same row into the same fields, so a future column reorder fails loudly.
    #[test]
    fn items_field_alignment_matches_item() {
        let (_dir, mut conn) = open_test_db();
        let id = uuid_n(1);
        let mut dto = item_dto(&id, "Distinctive Name", None, BaseItemKind::Movie);
        dto.genres = vec!["Noir".to_string(), "Sci-Fi".to_string()];
        apply_upsert_items(&mut conn, &[dto]).expect("insert");
        apply_local_user_data(&mut conn, &id, 54_321, Some(true)).expect("apply user data");

        let via_item = item(&conn, &id).expect("item() found row");
        let via_items = items(&conn, std::slice::from_ref(&id))
            .remove(&id)
            .expect("items() found row");

        assert_eq!(via_items.name, via_item.name);
        assert_eq!(via_items.type_, via_item.type_);
        assert_eq!(via_items.genres, via_item.genres);
        assert_eq!(
            via_items.genres,
            vec!["Noir".to_string(), "Sci-Fi".to_string()]
        );

        let item_ud = via_item.user_data.expect("item() user_data");
        let items_ud = via_items.user_data.expect("items() user_data");
        assert_eq!(items_ud.played, item_ud.played);
        assert_eq!(
            items_ud.playback_position_ticks,
            item_ud.playback_position_ticks
        );
        assert_eq!(items_ud.play_count, item_ud.play_count);
        assert_eq!(items_ud.is_favorite, item_ud.is_favorite);
        assert_eq!(items_ud.unplayed_item_count, item_ud.unplayed_item_count);
        assert_eq!(items_ud.last_played_date, item_ud.last_played_date);
        // Pinned to the actual value written, so a bug shifting both functions' indices
        // identically still fails.
        assert_eq!(items_ud.played, Some(true));
        assert_eq!(items_ud.playback_position_ticks, Some(0));
    }

    // ---- `Sort::IndexNumber` ordering characterization -------------------
    // `app`'s episode navigation treats a `children(.., Sort::IndexNumber, ..)` list as
    // "playback order" and indexes into it with +1/-1; these tests pin down that ordering.

    /// SQLite orders `NULL` FIRST under a plain `ASC`, so an unnumbered episode sorts ahead
    /// of episode 1.
    #[test]
    fn children_with_index_number_sort_places_null_index_number_first() {
        let (_dir, mut conn) = open_test_db();
        let season = uuid_n(1);
        let mut ep1 = item_dto(&uuid_n(2), "Ep One", Some(&season), BaseItemKind::Episode);
        ep1.index_number = Some(1);
        let mut ep2 = item_dto(&uuid_n(3), "Ep Two", Some(&season), BaseItemKind::Episode);
        ep2.index_number = Some(2);
        // No index_number at all (a badly-tagged file the server couldn't number).
        let unnumbered = item_dto(
            &uuid_n(4),
            "Unnumbered",
            Some(&season),
            BaseItemKind::Episode,
        );
        apply_upsert_items(&mut conn, &[ep1, ep2, unnumbered]).expect("insert");

        let rows = children(&conn, &season, Sort::IndexNumber, 0, 10);
        assert_eq!(
            rows.iter().map(|r| r.index_number).collect::<Vec<_>>(),
            vec![None, Some(1), Some(2)],
            "an un-numbered episode sorts FIRST under Sort::IndexNumber"
        );
    }

    /// A "Specials" season (`IndexNumber == 0`) sorts ahead of season 1.
    #[test]
    fn children_with_index_number_sort_places_season_zero_specials_first() {
        let (_dir, mut conn) = open_test_db();
        let series = uuid_n(1);
        let mut specials = item_dto(&uuid_n(2), "Specials", Some(&series), BaseItemKind::Season);
        specials.index_number = Some(0);
        let mut s1 = item_dto(&uuid_n(3), "Season 1", Some(&series), BaseItemKind::Season);
        s1.index_number = Some(1);
        let mut s2 = item_dto(&uuid_n(4), "Season 2", Some(&series), BaseItemKind::Season);
        s2.index_number = Some(2);
        apply_upsert_items(&mut conn, &[s1, s2, specials]).expect("insert");

        let rows = children(&conn, &series, Sort::IndexNumber, 0, 10);
        assert_eq!(
            rows.iter().map(|r| r.index_number).collect::<Vec<_>>(),
            vec![Some(0), Some(1), Some(2)],
            "Specials (season 0) sorts ahead of season 1"
        );
    }

    /// Virtual episodes are not filtered out of a `children()` list; every consumer must
    /// check `CardRow::is_virtual` itself.
    #[test]
    fn children_with_index_number_sort_keeps_virtual_episodes_in_line() {
        let (_dir, mut conn) = open_test_db();
        let season = uuid_n(1);
        let mut aired = item_dto(&uuid_n(2), "Aired", Some(&season), BaseItemKind::Episode);
        aired.index_number = Some(1);
        let mut unaired = item_dto(&uuid_n(3), "Unaired", Some(&season), BaseItemKind::Episode);
        unaired.index_number = Some(2);
        unaired.location_type = Some(jellyfin_api::models::LocationType::Virtual);
        apply_upsert_items(&mut conn, &[aired, unaired]).expect("insert");

        let rows = children(&conn, &season, Sort::IndexNumber, 0, 10);
        assert_eq!(
            rows.iter()
                .map(|r| (r.index_number, r.is_virtual))
                .collect::<Vec<_>>(),
            vec![(Some(1), false), (Some(2), true)],
            "a virtual episode stays in the ordered children list"
        );
    }

    // ---- Data-resilience fix: duplicate-Season episode resolution --------
    // A known Jellyfin quirk after rescans: duplicate Season items, where every episode's
    // `ParentId` points at the unsynced duplicate. See `episodes_of_season_checked` for the
    // fix (resolve by `series_id` + `parent_index_number` instead of `parent_id`).

    /// `season_a` never appears as any episode's `parent_id` (every episode points at the
    /// unsynced `season_b`); the listing must still return all 22 in index order, with
    /// adjacency (`position()` + `±1`) working across the whole run.
    #[test]
    fn episodes_of_season_resolve_by_series_and_season_number_not_literal_parent_id() {
        let (_dir, mut conn) = open_test_db();
        let series = uuid_n(1);
        let season_a = uuid_n(2); // the only Season row the mirror stores
        let season_b = uuid_n(3); // every episode's ParentId -- never synced

        let mut season_a_dto = item_dto(&season_a, "Season 1", Some(&series), BaseItemKind::Season);
        season_a_dto.series_id = Some(uuid::Uuid::parse_str(&series).expect("uuid"));
        season_a_dto.index_number = Some(1);
        apply_upsert_items(&mut conn, &[season_a_dto]).expect("insert season");

        let episodes: Vec<BaseItemDto> = (1..=22u8)
            .map(|n| {
                let mut ep = item_dto(
                    &uuid_n(100 + n),
                    &format!("S1E{n}"),
                    Some(&season_b),
                    BaseItemKind::Episode,
                );
                ep.series_id = Some(uuid::Uuid::parse_str(&series).expect("uuid"));
                // `SeasonId` (and thus `parent_id`) names the unsynced duplicate, not season_a.
                ep.season_id = Some(uuid::Uuid::parse_str(&season_b).expect("uuid"));
                ep.parent_index_number = Some(1);
                ep.index_number = Some(i32::from(n));
                ep
            })
            .collect();
        apply_upsert_items(&mut conn, &episodes).expect("insert episodes");

        let rows = children(&conn, &season_a, Sort::IndexNumber, 0, 50);
        assert_eq!(
            rows.iter().map(|r| r.index_number).collect::<Vec<_>>(),
            (1..=22).map(Some).collect::<Vec<_>>(),
            "must return all 22 episodes in index order, resolved via \
             series_id + parent_index_number rather than the literal \
             (unsynced) parent_id -- got {} rows: {:?}",
            rows.len(),
            rows.iter().map(|r| &r.name).collect::<Vec<_>>()
        );

        // Adjacency: position() the current episode, then step by one.
        let ix = rows
            .iter()
            .position(|r| r.id == uuid_n(100 + 10))
            .expect("episode 10 present");
        assert_eq!(rows[ix].index_number, Some(10));
        assert_eq!(
            rows[ix + 1].index_number,
            Some(11),
            "next-episode navigation must land on episode 11"
        );
        assert_eq!(
            rows[ix - 1].index_number,
            Some(9),
            "prev-episode navigation must land on episode 9"
        );
    }

    /// An episode with no `ParentIndexNumber` must still resolve via `parent_id` equality,
    /// so it doesn't silently vanish from every season's list.
    #[test]
    fn episodes_of_season_falls_back_to_parent_id_when_parent_index_number_is_null() {
        let (_dir, mut conn) = open_test_db();
        let series = uuid_n(1);
        let season = uuid_n(2);

        let mut season_dto = item_dto(&season, "Season 1", Some(&series), BaseItemKind::Season);
        season_dto.series_id = Some(uuid::Uuid::parse_str(&series).expect("uuid"));
        season_dto.index_number = Some(1);
        apply_upsert_items(&mut conn, &[season_dto]).expect("insert season");

        let mut unnumbered = item_dto(
            &uuid_n(3),
            "Mystery Episode",
            Some(&season),
            BaseItemKind::Episode,
        );
        unnumbered.series_id = Some(uuid::Uuid::parse_str(&series).expect("uuid"));
        unnumbered.season_id = Some(uuid::Uuid::parse_str(&season).expect("uuid"));
        // Deliberately no `parent_index_number` -- the fallback case.
        apply_upsert_items(&mut conn, &[unnumbered]).expect("insert episode");

        let rows = children(&conn, &season, Sort::IndexNumber, 0, 50);
        assert_eq!(
            rows.iter().map(|r| r.name.as_str()).collect::<Vec<_>>(),
            vec!["Mystery Episode"],
            "a NULL parent_index_number episode must still resolve via its \
             parent_id"
        );
    }

    /// Two seasons of the same series must have fully disjoint episode lists.
    #[test]
    fn episodes_of_season_does_not_bleed_across_seasons() {
        let (_dir, mut conn) = open_test_db();
        let series = uuid_n(1);
        let season1 = uuid_n(2);
        let season2 = uuid_n(3);

        let mut season1_dto = item_dto(&season1, "Season 1", Some(&series), BaseItemKind::Season);
        season1_dto.series_id = Some(uuid::Uuid::parse_str(&series).expect("uuid"));
        season1_dto.index_number = Some(1);
        let mut season2_dto = item_dto(&season2, "Season 2", Some(&series), BaseItemKind::Season);
        season2_dto.series_id = Some(uuid::Uuid::parse_str(&series).expect("uuid"));
        season2_dto.index_number = Some(2);
        apply_upsert_items(&mut conn, &[season1_dto, season2_dto]).expect("insert seasons");

        let mut ep_s1 = item_dto(&uuid_n(4), "S1E1", Some(&season1), BaseItemKind::Episode);
        ep_s1.series_id = Some(uuid::Uuid::parse_str(&series).expect("uuid"));
        ep_s1.season_id = Some(uuid::Uuid::parse_str(&season1).expect("uuid"));
        ep_s1.parent_index_number = Some(1);
        ep_s1.index_number = Some(1);
        let mut ep_s2 = item_dto(&uuid_n(5), "S2E1", Some(&season2), BaseItemKind::Episode);
        ep_s2.series_id = Some(uuid::Uuid::parse_str(&series).expect("uuid"));
        ep_s2.season_id = Some(uuid::Uuid::parse_str(&season2).expect("uuid"));
        ep_s2.parent_index_number = Some(2);
        ep_s2.index_number = Some(1);
        apply_upsert_items(&mut conn, &[ep_s1, ep_s2]).expect("insert episodes");

        let season1_rows = children(&conn, &season1, Sort::IndexNumber, 0, 50);
        let season2_rows = children(&conn, &season2, Sort::IndexNumber, 0, 50);
        assert_eq!(
            season1_rows
                .iter()
                .map(|r| r.name.as_str())
                .collect::<Vec<_>>(),
            vec!["S1E1"]
        );
        assert_eq!(
            season2_rows
                .iter()
                .map(|r| r.name.as_str())
                .collect::<Vec<_>>(),
            vec!["S2E1"]
        );
    }

    /// Resolving a season's episodes by `series_id` + `parent_index_number` must not
    /// degrade to a full `items` scan.
    #[test]
    fn episodes_of_season_query_does_not_scan_items() {
        let (_dir, conn) = open_test_db();
        let plan = explain(
            &conn,
            &episodes_of_season_sql(Sort::IndexNumber),
            params![
                String::from("series-1"),
                1i32,
                String::from("season-a"),
                10u32,
                0u32
            ],
        );
        assert_no_items_scan(&plan);
        let text = plan.join("\n");
        assert!(
            text.contains("parent_index_number"),
            "seek on (series_id, parent_index_number): {text}"
        );
        assert!(
            !text.contains("TEMP B-TREE"),
            "both branches arrive in index order: {text}"
        );
    }

    /// The UNION ALL form returns exactly what the `OR` form does, in the same order,
    /// including NULL-index episodes that fall back to `parent_id`.
    #[test]
    fn episodes_of_season_union_matches_or_form() {
        let (_dir, mut conn) = open_test_db();
        let series = uuid_n(1);
        let season = uuid_n(2);
        let other = uuid_n(3);
        let series_uuid = uuid::Uuid::parse_str(&series).expect("uuid");
        let mut season_dto = item_dto(&season, "Season 1", Some(&series), BaseItemKind::Season);
        season_dto.series_id = Some(series_uuid);
        season_dto.index_number = Some(1);
        let mut dtos = vec![season_dto];
        let mut n = 10u8;
        for (pin, parent, idx) in [
            (Some(1), &season, 2),
            (Some(1), &season, 1),
            (None, &season, 3),
            (None, &other, 4),
            (Some(2), &other, 1),
        ] {
            let mut ep = item_dto(
                &uuid_n(n),
                &format!("E{n}"),
                Some(parent),
                BaseItemKind::Episode,
            );
            ep.series_id = Some(series_uuid);
            ep.season_id = Some(uuid::Uuid::parse_str(parent).expect("uuid"));
            ep.parent_index_number = pin;
            ep.index_number = Some(idx);
            dtos.push(ep);
            n += 1;
        }
        apply_upsert_items(&mut conn, &dtos).expect("insert");
        let or_sql = format!(
            "SELECT {CARD_COLUMNS} FROM items WHERE item_type = 'Episode' AND series_id = ?1 \
             AND (parent_index_number = ?2 OR (parent_index_number IS NULL AND parent_id = ?3)) \
             ORDER BY {} LIMIT ?4 OFFSET ?5",
            sort_clause(Sort::IndexNumber)
        );
        let mut stmt = conn.prepare(&or_sql).expect("prepare");
        let expected: Vec<CardRow> = stmt
            .query_map(params![series, 1i32, season, 100u32, 0u32], row_to_card)
            .expect("query")
            .collect::<Result<_, _>>()
            .expect("rows");
        let got = episodes_of_season_checked(&conn, &season, &series, 1, Sort::IndexNumber, 0, 100)
            .expect("union");
        assert_eq!(expected.len(), 3);
        assert_eq!(expected, got);
    }

    #[test]
    fn views_returns_sorted_by_sort_index() {
        let (_dir, conn) = open_test_db();
        conn.execute("INSERT INTO views (id, name, collection_type, sort_index, item_type) VALUES ('b', 'B', 'movies', 1, 'CollectionFolder')", [])
            .expect("insert");
        conn.execute("INSERT INTO views (id, name, collection_type, sort_index, item_type) VALUES ('a', 'A', 'movies', 0, 'CollectionFolder')", [])
            .expect("insert");
        assert_eq!(
            views(&conn),
            vec![
                crate::ViewSummary {
                    id: "a".to_string(),
                    name: "A".to_string(),
                    item_type: "CollectionFolder".to_string(),
                    collection_type: "movies".to_string(),
                },
                crate::ViewSummary {
                    id: "b".to_string(),
                    name: "B".to_string(),
                    item_type: "CollectionFolder".to_string(),
                    collection_type: "movies".to_string(),
                },
            ]
        );
    }

    // ---- Library sort/filter (docs/16-library-sort-filter.md §2) --------

    fn no_filters() -> GridFilters {
        GridFilters {
            watched: WatchedFilter::Any,
            genre: None,
            decade: None,
            status: StatusFilter::Any,
            item_type: None,
        }
    }

    fn name_sort() -> GridSort {
        GridSort {
            field: GridSortField::Name,
            descending: false,
        }
    }

    fn played_user_data(played: bool) -> UserItemDataDto {
        UserItemDataDto {
            played: Some(played),
            playback_position_ticks: Some(0),
            play_count: None,
            is_favorite: None,
            unplayed_item_count: None,
            key: Some("k".to_string()),
            item_id: None,
            last_played_date: None,
            likes: None,
            played_percentage: None,
            rating: None,
        }
    }

    fn unplayed_count_user_data(count: Option<i32>) -> UserItemDataDto {
        UserItemDataDto {
            played: Some(false),
            playback_position_ticks: Some(0),
            play_count: None,
            is_favorite: None,
            unplayed_item_count: count,
            key: Some("k".to_string()),
            item_id: None,
            last_played_date: None,
            likes: None,
            played_percentage: None,
            rating: None,
        }
    }

    // ---- Favorites (docs/07 §1, docs/16 §2.7) --------------------------

    /// `(id, item_type, sort_name, is_favorite, last_played_date, series_id, parent_id)`.
    type FavoriteSeed<'a> = (
        &'a str,
        &'a str,
        &'a str,
        bool,
        Option<&'a str>,
        Option<&'a str>,
        &'a str,
    );

    fn seed_favorites(conn: &Connection, rows: &[FavoriteSeed<'_>]) {
        for (id, item_type, name, is_favorite, last_played, series_id, parent_id) in rows {
            conn.execute(
                "INSERT INTO items (id, item_type, name, sort_name, is_favorite, last_played_date, \
                 series_id, parent_id, dto, updated_at) VALUES (?1, ?2, ?3, ?3, ?4, ?5, ?6, ?7, '{}', 0)",
                params![id, item_type, name, is_favorite, last_played, series_id, parent_id],
            )
            .expect("insert");
        }
    }

    fn favorites_fixture(conn: &Connection) {
        seed_favorites(
            conn,
            &[
                (
                    "movie",
                    "Movie",
                    "Alpha",
                    true,
                    Some("2026-01-01T00:00:00Z"),
                    None,
                    "lib-a",
                ),
                ("series", "Series", "Delta", true, None, None, "lib-b"),
                (
                    "played-ep",
                    "Episode",
                    "Pilot",
                    false,
                    Some("2026-03-01T00:00:00Z"),
                    Some("series"),
                    "season",
                ),
                (
                    "episode",
                    "Episode",
                    "Beta",
                    true,
                    None,
                    Some("other"),
                    "season-2",
                ),
                ("boxset", "BoxSet", "Gamma", true, None, None, "lib-c"),
                ("song", "Audio", "Aria", true, None, None, "lib-d"),
                ("plain", "Movie", "Zeta", false, None, None, "lib-a"),
            ],
        );
    }

    #[test]
    fn favorites_shelf_puts_recent_plays_first_and_skips_undisplayable_types() {
        let (_dir, conn) = open_test_db();
        favorites_fixture(&conn);

        let rows = favorites(&conn, 10);

        // The Series ranks by its episode's play; unplayed favorites follow by name.
        assert_eq!(ids(&rows), vec!["series", "movie", "episode", "boxset"]);
        assert!(rows.iter().all(|r| r.is_favorite));
        assert_eq!(ids(&favorites(&conn, 2)), vec!["series", "movie"]);
        assert!(has_favorites(&conn));
        conn.execute("UPDATE items SET is_favorite = 0", [])
            .expect("clear");
        assert!(!has_favorites(&conn));
    }

    #[test]
    fn favorites_grid_spans_libraries_and_filters_by_type() {
        let (_dir, conn) = open_test_db();
        favorites_fixture(&conn);

        let all = library_grid_checked(&conn, FAVORITES_VIEW_ID, name_sort(), &no_filters(), 0, 10)
            .expect("grid");
        assert_eq!(names(&all), vec!["Alpha", "Beta", "Delta", "Gamma"]);

        let movies = GridFilters {
            item_type: Some("Movie".to_string()),
            ..no_filters()
        };
        let only_movies =
            library_grid_checked(&conn, FAVORITES_VIEW_ID, name_sort(), &movies, 0, 10)
                .expect("grid");
        assert_eq!(names(&only_movies), vec!["Alpha"]);
        assert_eq!(
            library_grid_counts_checked(&conn, FAVORITES_VIEW_ID, &movies).expect("counts"),
            GridCounts {
                filtered: 1,
                total: 4
            }
        );
        assert_eq!(
            favorite_item_types(&conn),
            vec!["Movie", "Series", "Episode", "BoxSet"]
        );
        let groups =
            library_grid_groups_checked(&conn, FAVORITES_VIEW_ID, name_sort(), &no_filters())
                .expect("groups");
        assert_eq!(groups.iter().map(|g| g.count).sum::<u64>(), 4);
    }

    #[test]
    fn favorites_shelf_reads_episode_plays_through_the_played_indexes() {
        let (_dir, conn) = open_test_db();
        let limit: u32 = 20;
        let plan = explain(&conn, &favorites_sql(), &[&limit]);
        assert_no_items_scan(&plan);
        for index in [
            "idx_items_favorite",
            "idx_items_series_played",
            "idx_items_parent_played",
        ] {
            assert!(
                plan.iter().any(|l| l.contains(index)),
                "{index} missing from plan: {plan:?}"
            );
        }
    }

    #[test]
    fn favorites_scope_is_served_by_the_partial_index() {
        let (_dir, conn) = open_test_db();
        let plan = explain(
            &conn,
            &format!("SELECT id {}", grid_scope(FAVORITES_VIEW_ID, None)),
            &[],
        );
        assert_no_items_scan(&plan);
        assert!(
            plan.iter().any(|l| l.contains("idx_items_favorite")),
            "plan: {plan:?}"
        );
    }

    fn names(rows: &[CardRow]) -> Vec<&str> {
        rows.iter().map(|r| r.name.as_str()).collect()
    }

    fn ids(rows: &[CardRow]) -> Vec<String> {
        rows.iter().map(|r| r.id.clone()).collect()
    }

    fn minutes_ticks(minutes: i64) -> i64 {
        minutes * 600_000_000
    }

    /// Exercises the exact `INDEXED BY {index} ... ORDER BY {grid_sort_order}` shape
    /// [`library_grid_checked`] issues: no full scan and no separate sort step, and (§2.6) a
    /// filter is a predicate over the index scan, never a reason to fall back to a sort.
    fn assert_grid_sort_index_not_scan_filtered(
        field: GridSortField,
        descending: bool,
        filters: &GridFilters,
    ) {
        let (_dir, conn) = open_test_db();
        let index = grid_sort_index(field);
        let sort = GridSort { field, descending };
        let mut sql = format!("SELECT id FROM items INDEXED BY {index} WHERE parent_id = ?");
        append_grid_filters(&mut sql, filters);
        sql.push_str(&format!(
            " ORDER BY {} LIMIT ? OFFSET ?",
            grid_sort_order(sort)
        ));

        let parent = String::from("p");
        let limit: u32 = 10;
        let offset: u32 = 0;
        let mut params: Vec<&dyn rusqlite::ToSql> = vec![&parent as &dyn rusqlite::ToSql];
        params.extend(grid_filter_params(filters));
        params.push(&limit);
        params.push(&offset);

        let plan = explain(&conn, &sql, params.as_slice());
        assert_no_items_scan(&plan);
        assert!(
            !plan.iter().any(|l| l.contains("TEMP B-TREE")),
            "field {field:?}, descending {descending}, filters {filters:?}, plan: {plan:?}"
        );
        assert!(
            plan.iter().any(|l| l.contains(index)),
            "field {field:?}, plan: {plan:?}"
        );
    }

    fn assert_grid_sort_index_not_scan(field: GridSortField, descending: bool) {
        assert_grid_sort_index_not_scan_filtered(field, descending, &no_filters());
    }

    #[test]
    fn library_grid_name_sort_query_uses_index_not_scan() {
        assert_grid_sort_index_not_scan(GridSortField::Name, false);
        assert_grid_sort_index_not_scan(GridSortField::Name, true);
    }

    #[test]
    fn library_grid_date_added_sort_query_uses_index_not_scan() {
        assert_grid_sort_index_not_scan(GridSortField::DateAdded, false);
        assert_grid_sort_index_not_scan(GridSortField::DateAdded, true);
    }

    #[test]
    fn library_grid_year_sort_query_uses_index_not_scan() {
        assert_grid_sort_index_not_scan(GridSortField::Year, false);
        assert_grid_sort_index_not_scan(GridSortField::Year, true);
    }

    #[test]
    fn library_grid_runtime_sort_query_uses_index_not_scan() {
        assert_grid_sort_index_not_scan(GridSortField::Runtime, false);
        assert_grid_sort_index_not_scan(GridSortField::Runtime, true);
    }

    /// §2.6: each filter kind, active alongside every sort field/direction, must still leave
    /// the grid query index-served with no re-introduced sort step.
    #[test]
    fn library_grid_sort_index_holds_with_each_filter_kind_active() {
        let mut watched = no_filters();
        watched.watched = WatchedFilter::Watched;
        let mut genre = no_filters();
        genre.genre = Some("Drama".to_string());
        let mut decade = no_filters();
        decade.decade = Some(Decade::D2010s);
        let mut status = no_filters();
        status.status = StatusFilter::Continuing;

        for field in [
            GridSortField::Name,
            GridSortField::DateAdded,
            GridSortField::Year,
            GridSortField::Runtime,
        ] {
            for filters in [&watched, &genre, &decade, &status] {
                assert_grid_sort_index_not_scan_filtered(field, false, filters);
                assert_grid_sort_index_not_scan_filtered(field, true, filters);
            }
        }
    }

    #[test]
    fn library_grid_sorts_by_name_ascending_and_descending() {
        let (_dir, mut conn) = open_test_db();
        let parent = uuid_n(1);
        apply_upsert_items(
            &mut conn,
            &[
                item_dto(&uuid_n(2), "Bravo", Some(&parent), BaseItemKind::Movie),
                item_dto(&uuid_n(3), "Alpha", Some(&parent), BaseItemKind::Movie),
            ],
        )
        .expect("insert");

        let asc = library_grid(&conn, &parent, name_sort(), &no_filters(), 0, 10);
        assert_eq!(names(&asc), vec!["Alpha", "Bravo"]);

        let desc = library_grid(
            &conn,
            &parent,
            GridSort {
                field: GridSortField::Name,
                descending: true,
            },
            &no_filters(),
            0,
            10,
        );
        assert_eq!(names(&desc), vec!["Bravo", "Alpha"]);
    }

    /// §2.2: `date_created IS NULL` sorts last in BOTH directions.
    #[test]
    fn library_grid_sorts_by_date_added_and_places_null_last_in_both_directions() {
        let (_dir, mut conn) = open_test_db();
        let parent = uuid_n(1);
        let a = uuid_n(2);
        let b = uuid_n(3);
        let c = uuid_n(4);
        let mut item_a = item_dto(&a, "A", Some(&parent), BaseItemKind::Movie);
        item_a.date_created = Some("2024-01-01T00:00:00Z".parse().expect("date"));
        let mut item_b = item_dto(&b, "B", Some(&parent), BaseItemKind::Movie);
        item_b.date_created = Some("2024-06-01T00:00:00Z".parse().expect("date"));
        let item_c = item_dto(&c, "C", Some(&parent), BaseItemKind::Movie); // no date_created
        apply_upsert_items(&mut conn, &[item_a, item_b, item_c]).expect("insert");

        let desc = library_grid(
            &conn,
            &parent,
            GridSort {
                field: GridSortField::DateAdded,
                descending: true,
            },
            &no_filters(),
            0,
            10,
        );
        assert_eq!(ids(&desc), vec![b.clone(), a.clone(), c.clone()]);

        let asc = library_grid(
            &conn,
            &parent,
            GridSort {
                field: GridSortField::DateAdded,
                descending: false,
            },
            &no_filters(),
            0,
            10,
        );
        assert_eq!(ids(&asc), vec![a, b, c]);
    }

    /// §2.2: `production_year IS NULL` sorts last in BOTH directions.
    #[test]
    fn library_grid_sorts_by_year_and_places_null_last_in_both_directions() {
        let (_dir, mut conn) = open_test_db();
        let parent = uuid_n(1);
        let a = uuid_n(2);
        let b = uuid_n(3);
        let c = uuid_n(4);
        let mut item_a = item_dto(&a, "A", Some(&parent), BaseItemKind::Movie);
        item_a.production_year = Some(2010);
        let mut item_b = item_dto(&b, "B", Some(&parent), BaseItemKind::Movie);
        item_b.production_year = Some(2020);
        let item_c = item_dto(&c, "C", Some(&parent), BaseItemKind::Movie); // no year
        apply_upsert_items(&mut conn, &[item_a, item_b, item_c]).expect("insert");

        let desc = library_grid(
            &conn,
            &parent,
            GridSort {
                field: GridSortField::Year,
                descending: true,
            },
            &no_filters(),
            0,
            10,
        );
        assert_eq!(ids(&desc), vec![b.clone(), a.clone(), c.clone()]);

        let asc = library_grid(
            &conn,
            &parent,
            GridSort {
                field: GridSortField::Year,
                descending: false,
            },
            &no_filters(),
            0,
            10,
        );
        assert_eq!(ids(&asc), vec![a, b, c]);
    }

    /// §2.2: `runtime_ticks` NULL **or 0** sorts last in BOTH directions (a
    /// Series row often carries 0).
    #[test]
    fn library_grid_sorts_by_runtime_and_places_null_or_zero_last_in_both_directions() {
        let (_dir, mut conn) = open_test_db();
        let parent = uuid_n(1);
        let long = uuid_n(2);
        let short = uuid_n(3);
        let zero = uuid_n(4);
        let null = uuid_n(5);
        let mut item_long = item_dto(&long, "Long", Some(&parent), BaseItemKind::Movie);
        item_long.run_time_ticks = Some(minutes_ticks(60));
        let mut item_short = item_dto(&short, "Short", Some(&parent), BaseItemKind::Movie);
        item_short.run_time_ticks = Some(minutes_ticks(30));
        let mut item_zero = item_dto(&zero, "Zero", Some(&parent), BaseItemKind::Series);
        item_zero.run_time_ticks = Some(0);
        let item_null = item_dto(&null, "Null", Some(&parent), BaseItemKind::Movie); // no runtime
        apply_upsert_items(&mut conn, &[item_long, item_short, item_zero, item_null])
            .expect("insert");

        let desc = library_grid(
            &conn,
            &parent,
            GridSort {
                field: GridSortField::Runtime,
                descending: true,
            },
            &no_filters(),
            0,
            10,
        );
        // `NULLIF(runtime_ticks, 0)` maps the zero-runtime and NULL rows to the same SQL
        // NULL; within that tied group the tiebreak (sort_name COLLATE NOCASE {dir}) decides
        // "Zero" vs "Null" order, asserted here as the real deterministic behavior.
        assert_eq!(
            ids(&desc),
            vec![long.clone(), short.clone(), zero.clone(), null.clone()]
        );

        let asc = library_grid(
            &conn,
            &parent,
            GridSort {
                field: GridSortField::Runtime,
                descending: false,
            },
            &no_filters(),
            0,
            10,
        );
        assert_eq!(ids(&asc), vec![short, long, null, zero]);
    }

    /// §2.2: `Unwatched` ("never started") disqualifies a Series with a played/in-progress
    /// episode even though the Series row's own `played` is 0.
    #[test]
    fn library_grid_unwatched_filter_excludes_a_series_with_a_played_episode() {
        let (_dir, mut conn) = open_test_db();
        let parent = uuid_n(1);
        let movie_never_started = uuid_n(2);
        let movie_watched = uuid_n(3);
        let series_with_played_ep = uuid_n(4);
        let episode_played = uuid_n(5);
        let series_all_unwatched = uuid_n(6);
        let episode_unplayed = uuid_n(7);

        let m1 = item_dto(
            &movie_never_started,
            "Movie Unwatched",
            Some(&parent),
            BaseItemKind::Movie,
        );
        let mut m2 = item_dto(
            &movie_watched,
            "Movie Watched",
            Some(&parent),
            BaseItemKind::Movie,
        );
        m2.user_data = Some(played_user_data(true));

        let s1 = item_dto(
            &series_with_played_ep,
            "Series With Played Episode",
            Some(&parent),
            BaseItemKind::Series,
        );
        let mut e1 = item_dto(&episode_played, "Ep1", None, BaseItemKind::Episode);
        e1.series_id = Some(uuid::Uuid::parse_str(&series_with_played_ep).expect("uuid"));
        e1.user_data = Some(played_user_data(true));

        let s2 = item_dto(
            &series_all_unwatched,
            "Series All Unwatched",
            Some(&parent),
            BaseItemKind::Series,
        );
        let mut e2 = item_dto(&episode_unplayed, "Ep2", None, BaseItemKind::Episode);
        e2.series_id = Some(uuid::Uuid::parse_str(&series_all_unwatched).expect("uuid"));

        apply_upsert_items(&mut conn, &[m1, m2, s1, e1, s2, e2]).expect("insert");

        let mut filters = no_filters();
        filters.watched = WatchedFilter::Unwatched;
        let rows = library_grid(&conn, &parent, name_sort(), &filters, 0, 10);
        assert_eq!(
            names(&rows),
            vec!["Movie Unwatched", "Series All Unwatched"],
            "a series with any played/in-progress episode is not \
             'never started', even though its own row is played=0"
        );
    }

    /// §2.2: `Watched` is server `played = 1` or the card's grace-watched state (the server
    /// already computes a Series' `played` from its episodes).
    #[test]
    fn library_grid_watched_filter_returns_only_fully_played_rows() {
        let (_dir, mut conn) = open_test_db();
        let parent = uuid_n(1);
        let mut watched = item_dto(&uuid_n(2), "Watched", Some(&parent), BaseItemKind::Movie);
        watched.user_data = Some(played_user_data(true));
        let unwatched = item_dto(&uuid_n(3), "Unwatched", Some(&parent), BaseItemKind::Movie);
        apply_upsert_items(&mut conn, &[watched, unwatched]).expect("insert");

        let mut filters = no_filters();
        filters.watched = WatchedFilter::Watched;
        let rows = library_grid(&conn, &parent, name_sort(), &filters, 0, 10);
        assert_eq!(names(&rows), vec!["Watched"]);
    }

    fn progress_dto(
        n: u8,
        name: &str,
        kind: BaseItemKind,
        position: i64,
        runtime: i64,
    ) -> BaseItemDto {
        let mut dto = item_dto(&uuid_n(n), name, None, kind);
        dto.run_time_ticks = Some(runtime);
        dto.user_data = Some(user_data_with_progress(position, None));
        dto
    }

    /// docs/07 §1: filters judge the displayed state, not the raw columns.
    #[test]
    fn library_grid_watched_filters_follow_the_displayed_state() {
        let (_dir, mut conn) = open_test_db();
        let parent = uuid_n(1);
        let mut started = progress_dto(
            2,
            "Stopped Early",
            BaseItemKind::Movie,
            30 * 10_000_000,
            120 * MIN,
        );
        let mut credits = progress_dto(3, "In Credits", BaseItemKind::Movie, 115 * MIN, 120 * MIN);
        for dto in [&mut started, &mut credits] {
            dto.parent_id = Some(uuid::Uuid::parse_str(&parent).expect("uuid"));
        }
        // An episode stopped 30 s in does not make its series "started".
        let mut series = item_dto(
            &uuid_n(4),
            "Quiet Series",
            Some(&parent),
            BaseItemKind::Series,
        );
        series.run_time_ticks = None;
        let mut ep = progress_dto(5, "Ep", BaseItemKind::Episode, 30 * 10_000_000, 44 * MIN);
        ep.series_id = Some(uuid::Uuid::parse_str(&uuid_n(4)).expect("uuid"));
        let mut resumed = item_dto(
            &uuid_n(6),
            "Resumed Series",
            Some(&parent),
            BaseItemKind::Series,
        );
        resumed.run_time_ticks = None;
        let mut ep2 = progress_dto(7, "Ep2", BaseItemKind::Episode, 20 * MIN, 44 * MIN);
        ep2.series_id = Some(uuid::Uuid::parse_str(&uuid_n(6)).expect("uuid"));
        apply_upsert_items(&mut conn, &[started, credits, series, ep, resumed, ep2])
            .expect("insert");

        let mut filters = no_filters();
        filters.watched = WatchedFilter::Unwatched;
        let rows = library_grid(&conn, &parent, name_sort(), &filters, 0, 10);
        assert_eq!(names(&rows), vec!["Quiet Series", "Stopped Early"]);

        filters.watched = WatchedFilter::Watched;
        let rows = library_grid(&conn, &parent, name_sort(), &filters, 0, 10);
        assert_eq!(names(&rows), vec!["In Credits"]);
    }

    /// docs/07 §1: `hide_watched` drops an item the card shows watched, in both Latest branches.
    #[test]
    fn latest_hide_watched_drops_items_inside_the_end_grace() {
        let (_dir, mut conn) = open_test_db();
        conn.execute_batch(
            "INSERT INTO views (id, name, collection_type, sort_index) VALUES ('mov', 'Movies', 'movies', 0); \
             INSERT INTO views (id, name, collection_type, sort_index) VALUES ('shows', 'Shows', 'tvshows', 1);",
        )
        .expect("views");
        let credits = progress_dto(1, "In Credits", BaseItemKind::Movie, 115 * MIN, 120 * MIN);
        let early = progress_dto(
            2,
            "Stopped Early",
            BaseItemKind::Movie,
            30 * 10_000_000,
            120 * MIN,
        );
        apply_upsert_items_scoped(&mut conn, &[credits, early], Some("mov")).expect("movies");

        let series_done = uuid_n(3);
        let series_open = uuid_n(4);
        let mut ep_done = progress_dto(5, "Done Ep", BaseItemKind::Episode, 43 * MIN, 44 * MIN);
        ep_done.series_id = Some(uuid::Uuid::parse_str(&series_done).expect("uuid"));
        let mut ep_open = progress_dto(
            6,
            "Open Ep",
            BaseItemKind::Episode,
            30 * 10_000_000,
            44 * MIN,
        );
        ep_open.series_id = Some(uuid::Uuid::parse_str(&series_open).expect("uuid"));
        apply_upsert_items_scoped(
            &mut conn,
            &[
                item_dto(&series_done, "Done Series", None, BaseItemKind::Series),
                item_dto(&series_open, "Open Series", None, BaseItemKind::Series),
                ep_done,
                ep_open,
            ],
            Some("shows"),
        )
        .expect("shows");

        let shown = |view: &str| {
            let mut v: Vec<_> = latest(&conn, view, 10, true, false)
                .into_iter()
                .map(|r| r.name)
                .collect();
            v.sort();
            v
        };
        assert_eq!(shown("mov"), ["Stopped Early"]);
        assert_eq!(shown("shows"), ["Open Series"]);
    }

    /// §2.2: `WatchedFilter::HasUnwatched` ("not finished") is
    /// `unplayed_item_count > 0`, distinct from `WatchedFilter::Unwatched`
    /// ("not started").
    #[test]
    fn library_grid_has_unwatched_filter_uses_unplayed_item_count() {
        let (_dir, mut conn) = open_test_db();
        let parent = uuid_n(1);
        let mut has_unwatched = item_dto(
            &uuid_n(2),
            "Show With Unwatched",
            Some(&parent),
            BaseItemKind::Series,
        );
        has_unwatched.user_data = Some(unplayed_count_user_data(Some(2)));
        let mut finished = item_dto(
            &uuid_n(3),
            "Finished Show",
            Some(&parent),
            BaseItemKind::Series,
        );
        finished.user_data = Some(unplayed_count_user_data(Some(0)));
        let unknown = item_dto(
            &uuid_n(4),
            "No UserData Show",
            Some(&parent),
            BaseItemKind::Series,
        );
        apply_upsert_items(&mut conn, &[has_unwatched, finished, unknown]).expect("insert");

        let mut filters = no_filters();
        filters.watched = WatchedFilter::HasUnwatched;
        let rows = library_grid(&conn, &parent, name_sort(), &filters, 0, 10);
        assert_eq!(names(&rows), vec!["Show With Unwatched"]);
    }

    /// §2.2: the Genre filter is an exact-string `EXISTS` match against
    /// `item_genres`.
    #[test]
    fn library_grid_genre_filter_matches_exact_genre_string() {
        let (_dir, mut conn) = open_test_db();
        let parent = uuid_n(1);
        let mut drama = item_dto(
            &uuid_n(2),
            "Drama Movie",
            Some(&parent),
            BaseItemKind::Movie,
        );
        drama.genres = vec!["Drama".to_string()];
        let mut comedy = item_dto(
            &uuid_n(3),
            "Comedy Movie",
            Some(&parent),
            BaseItemKind::Movie,
        );
        comedy.genres = vec!["Comedy".to_string()];
        apply_upsert_items(&mut conn, &[drama, comedy]).expect("insert");

        let mut filters = no_filters();
        filters.genre = Some("Drama".to_string());
        let rows = library_grid(&conn, &parent, name_sort(), &filters, 0, 10);
        assert_eq!(names(&rows), vec!["Drama Movie"]);
    }

    /// §2.2: decade filters match the right `production_year` range, and a
    /// NULL year never matches any decade, `Older` included.
    #[test]
    fn library_grid_decade_filter_matches_range_and_excludes_null_year() {
        let (_dir, mut conn) = open_test_db();
        let parent = uuid_n(1);
        let mut y2015 = item_dto(&uuid_n(2), "2015 Movie", Some(&parent), BaseItemKind::Movie);
        y2015.production_year = Some(2015);
        let mut y1975 = item_dto(&uuid_n(3), "1975 Movie", Some(&parent), BaseItemKind::Movie);
        y1975.production_year = Some(1975);
        let null_year = item_dto(
            &uuid_n(4),
            "No Year Movie",
            Some(&parent),
            BaseItemKind::Movie,
        );
        apply_upsert_items(&mut conn, &[y2015, y1975, null_year]).expect("insert");

        let mut filters = no_filters();
        filters.decade = Some(Decade::D2010s);
        let rows = library_grid(&conn, &parent, name_sort(), &filters, 0, 10);
        assert_eq!(names(&rows), vec!["2015 Movie"]);

        filters.decade = Some(Decade::Older);
        let rows = library_grid(&conn, &parent, name_sort(), &filters, 0, 10);
        assert_eq!(
            names(&rows),
            vec!["1975 Movie"],
            "Older must exclude both the 2010s item and the NULL-year item"
        );
    }

    /// §2.2: the Status filter is a plain `series_status` equality check.
    #[test]
    fn library_grid_status_filter_matches_continuing_and_ended() {
        let (_dir, mut conn) = open_test_db();
        let parent = uuid_n(1);
        let mut continuing = item_dto(
            &uuid_n(2),
            "Continuing Show",
            Some(&parent),
            BaseItemKind::Series,
        );
        continuing.status = Some("Continuing".to_string());
        let mut ended = item_dto(
            &uuid_n(3),
            "Ended Show",
            Some(&parent),
            BaseItemKind::Series,
        );
        ended.status = Some("Ended".to_string());
        let movie = item_dto(&uuid_n(4), "A Movie", Some(&parent), BaseItemKind::Movie);
        apply_upsert_items(&mut conn, &[continuing, ended, movie]).expect("insert");

        let mut filters = no_filters();
        filters.status = StatusFilter::Continuing;
        let rows = library_grid(&conn, &parent, name_sort(), &filters, 0, 10);
        assert_eq!(names(&rows), vec!["Continuing Show"]);

        filters.status = StatusFilter::Ended;
        let rows = library_grid(&conn, &parent, name_sort(), &filters, 0, 10);
        assert_eq!(names(&rows), vec!["Ended Show"]);
    }

    /// §2.3: `filtered` counts rows matching the current filters; `total`
    /// ignores them.
    #[test]
    fn library_grid_counts_reports_filtered_and_total() {
        let (_dir, mut conn) = open_test_db();
        let parent = uuid_n(1);
        let mut watched = item_dto(&uuid_n(2), "Watched", Some(&parent), BaseItemKind::Movie);
        watched.user_data = Some(played_user_data(true));
        let unwatched = item_dto(&uuid_n(3), "Unwatched", Some(&parent), BaseItemKind::Movie);
        apply_upsert_items(&mut conn, &[watched, unwatched]).expect("insert");

        let mut filters = no_filters();
        filters.watched = WatchedFilter::Watched;
        let counts = library_grid_counts(&conn, &parent, &filters);
        assert_eq!(
            counts,
            GridCounts {
                filtered: 1,
                total: 2
            }
        );
    }

    /// §4.6: a genuinely empty view is `Ok`-shaped, never the error path; covers all three
    /// `_checked` grid queries.
    #[test]
    fn checked_grid_queries_return_ok_empty_for_a_view_with_no_rows() {
        let (_dir, conn) = open_test_db();
        let no_such_view = uuid_n(9);

        assert_eq!(
            library_grid_checked(&conn, &no_such_view, name_sort(), &no_filters(), 0, 20),
            Ok(Vec::new())
        );
        assert_eq!(
            library_grid_counts_checked(&conn, &no_such_view, &no_filters()),
            Ok(GridCounts {
                filtered: 0,
                total: 0
            })
        );
        assert_eq!(
            library_grid_groups_checked(&conn, &no_such_view, name_sort(), &no_filters()),
            Ok(Vec::new())
        );
    }

    /// §2.4: Name groups key by initial letter, `#` for anything outside `A`-`Z`, reversed
    /// when the sort direction flips.
    #[test]
    fn library_grid_groups_for_name_orders_hash_by_direction() {
        let (_dir, mut conn) = open_test_db();
        let parent = uuid_n(1);
        apply_upsert_items(
            &mut conn,
            &[
                item_dto(&uuid_n(2), "Alpha", Some(&parent), BaseItemKind::Movie),
                item_dto(&uuid_n(3), "Bravo", Some(&parent), BaseItemKind::Movie),
                item_dto(&uuid_n(4), "Zoo", Some(&parent), BaseItemKind::Movie),
                item_dto(
                    &uuid_n(5),
                    "3 Test Movie",
                    Some(&parent),
                    BaseItemKind::Movie,
                ),
            ],
        )
        .expect("insert");

        let asc = library_grid_groups(&conn, &parent, name_sort(), &no_filters());
        assert_eq!(
            asc.iter().map(|g| g.key.as_str()).collect::<Vec<_>>(),
            vec!["#", "A", "B", "Z"]
        );
        assert_eq!(
            asc.iter().map(|g| g.count).collect::<Vec<_>>(),
            vec![1, 1, 1, 1]
        );

        let desc = library_grid_groups(
            &conn,
            &parent,
            GridSort {
                field: GridSortField::Name,
                descending: true,
            },
            &no_filters(),
        );
        assert_eq!(
            desc.iter().map(|g| g.key.as_str()).collect::<Vec<_>>(),
            vec!["Z", "B", "A", "#"],
            "non-ASCII/digit-led titles under Name sort after Z when the \
             sort direction is flipped"
        );
    }

    /// `#` under Name legitimately has two non-contiguous runs (digit/symbol-led titles
    /// before `A`, non-ASCII-led after `Z`); run-length encoding must emit two separate `#`
    /// groups, not one summed row (the old `GROUP BY` shape's bug).
    #[test]
    fn library_grid_groups_for_name_emits_non_contiguous_hash_runs_separately() {
        let (_dir, mut conn) = open_test_db();
        let parent = uuid_n(1);
        apply_upsert_items(
            &mut conn,
            &[
                item_dto(&uuid_n(2), "!x", Some(&parent), BaseItemKind::Movie),
                item_dto(&uuid_n(3), "1y", Some(&parent), BaseItemKind::Movie),
                item_dto(&uuid_n(4), "alpha", Some(&parent), BaseItemKind::Movie),
                item_dto(&uuid_n(5), "beta", Some(&parent), BaseItemKind::Movie),
                // A non-ASCII-led name sorts after every ASCII letter, landing in the trailing `#`
                // run.
                item_dto(&uuid_n(6), "Éclair", Some(&parent), BaseItemKind::Movie),
            ],
        )
        .expect("insert");

        let asc = library_grid_groups(&conn, &parent, name_sort(), &no_filters());
        assert_eq!(
            asc.iter()
                .map(|g| (g.key.as_str(), g.count))
                .collect::<Vec<_>>(),
            vec![("#", 2), ("A", 1), ("B", 1), ("#", 1)],
            "the leading #-run (!x, 1y) and the trailing #-run (Éclair) \
             must stay separate, not summed into one row"
        );

        let desc = library_grid_groups(
            &conn,
            &parent,
            GridSort {
                field: GridSortField::Name,
                descending: true,
            },
            &no_filters(),
        );
        assert_eq!(
            desc.iter()
                .map(|g| (g.key.as_str(), g.count))
                .collect::<Vec<_>>(),
            vec![("#", 1), ("B", 1), ("A", 1), ("#", 2)]
        );
    }

    /// §2.6: the run-length group scan must be as index-served as the grid itself, for
    /// every sort field and direction.
    #[test]
    fn library_grid_groups_query_uses_index_not_scan() {
        for field in [
            GridSortField::Name,
            GridSortField::DateAdded,
            GridSortField::Year,
            GridSortField::Runtime,
        ] {
            for descending in [false, true] {
                let (_dir, conn) = open_test_db();
                let index = grid_sort_index(field);
                let key_expr = grid_group_key_expr(field);
                let sort = GridSort { field, descending };
                let sql = format!(
                    "SELECT {key_expr} FROM items INDEXED BY {index} WHERE parent_id = ? ORDER BY {}",
                    grid_sort_order(sort)
                );
                let parent = String::from("p");
                let plan = explain(&conn, &sql, &[&parent as &dyn rusqlite::ToSql]);
                assert_no_items_scan(&plan);
                assert!(
                    !plan.iter().any(|l| l.contains("TEMP B-TREE")),
                    "field {field:?}, descending {descending}, plan: {plan:?}"
                );
                assert!(
                    plan.iter().any(|l| l.contains(index)),
                    "field {field:?}, plan: {plan:?}"
                );
            }
        }
    }

    /// §2.4: Date added groups key by `YYYY-MM`; the empty (NULL) key sorts
    /// last regardless of direction.
    #[test]
    fn library_grid_groups_for_date_added_key_by_month_with_empty_last() {
        let (_dir, mut conn) = open_test_db();
        let parent = uuid_n(1);
        let mut a = item_dto(&uuid_n(2), "A", Some(&parent), BaseItemKind::Movie);
        a.date_created = Some("2026-09-01T00:00:00Z".parse().expect("date"));
        let mut b = item_dto(&uuid_n(3), "B", Some(&parent), BaseItemKind::Movie);
        b.date_created = Some("2026-09-15T00:00:00Z".parse().expect("date"));
        let mut c = item_dto(&uuid_n(4), "C", Some(&parent), BaseItemKind::Movie);
        c.date_created = Some("2026-08-01T00:00:00Z".parse().expect("date"));
        let d = item_dto(&uuid_n(5), "D", Some(&parent), BaseItemKind::Movie); // no date
        apply_upsert_items(&mut conn, &[a, b, c, d]).expect("insert");

        let groups = library_grid_groups(
            &conn,
            &parent,
            GridSort {
                field: GridSortField::DateAdded,
                descending: true,
            },
            &no_filters(),
        );
        assert_eq!(
            groups
                .iter()
                .map(|g| (g.key.as_str(), g.count))
                .collect::<Vec<_>>(),
            vec![("2026-09", 2), ("2026-08", 1), ("", 1)]
        );
    }

    /// §2.4: Year groups key by decade; the empty (NULL) key sorts last.
    #[test]
    fn library_grid_groups_for_year_key_by_decade_with_empty_last() {
        let (_dir, mut conn) = open_test_db();
        let parent = uuid_n(1);
        let mut a = item_dto(&uuid_n(2), "A", Some(&parent), BaseItemKind::Movie);
        a.production_year = Some(2015);
        let mut b = item_dto(&uuid_n(3), "B", Some(&parent), BaseItemKind::Movie);
        b.production_year = Some(2013);
        let mut c = item_dto(&uuid_n(4), "C", Some(&parent), BaseItemKind::Movie);
        c.production_year = Some(1975);
        let d = item_dto(&uuid_n(5), "D", Some(&parent), BaseItemKind::Movie); // no year
        apply_upsert_items(&mut conn, &[a, b, c, d]).expect("insert");

        let groups = library_grid_groups(
            &conn,
            &parent,
            GridSort {
                field: GridSortField::Year,
                descending: true,
            },
            &no_filters(),
        );
        assert_eq!(
            groups
                .iter()
                .map(|g| (g.key.as_str(), g.count))
                .collect::<Vec<_>>(),
            vec![("2010", 2), ("1970", 1), ("", 1)]
        );
    }

    /// §2.4: Runtime groups key by minute band; NULL/0 fold into one empty
    /// bucket that sorts last.
    #[test]
    fn library_grid_groups_for_runtime_key_by_band_with_empty_last() {
        let (_dir, mut conn) = open_test_db();
        let parent = uuid_n(1);
        let mut a = item_dto(&uuid_n(2), "A", Some(&parent), BaseItemKind::Movie);
        a.run_time_ticks = Some(minutes_ticks(45));
        let mut b = item_dto(&uuid_n(3), "B", Some(&parent), BaseItemKind::Movie);
        b.run_time_ticks = Some(minutes_ticks(10));
        let mut zero = item_dto(&uuid_n(4), "Zero", Some(&parent), BaseItemKind::Series);
        zero.run_time_ticks = Some(0);
        let null = item_dto(&uuid_n(5), "Null", Some(&parent), BaseItemKind::Movie);
        apply_upsert_items(&mut conn, &[a, b, zero, null]).expect("insert");

        let groups = library_grid_groups(
            &conn,
            &parent,
            GridSort {
                field: GridSortField::Runtime,
                descending: true,
            },
            &no_filters(),
        );
        assert_eq!(
            groups
                .iter()
                .map(|g| (g.key.as_str(), g.count))
                .collect::<Vec<_>>(),
            vec![("30", 1), ("0", 1), ("", 2)],
            "NULL and exactly-0 runtime must fold into the same empty bucket"
        );
    }

    /// §2.5: distinct genres, `COLLATE NOCASE` order. Distinct is exact-string (verbatim,
    /// CLAUDE.md); only the ordering folds case.
    #[test]
    fn library_genres_returns_distinct_values_in_nocase_order() {
        let (_dir, mut conn) = open_test_db();
        let parent = uuid_n(1);
        let mut a = item_dto(&uuid_n(2), "A", Some(&parent), BaseItemKind::Movie);
        a.genres = vec!["Documentary".to_string(), "action".to_string()];
        let mut b = item_dto(&uuid_n(3), "B", Some(&parent), BaseItemKind::Movie);
        b.genres = vec!["Drama".to_string()];
        let mut c = item_dto(&uuid_n(4), "C", Some(&parent), BaseItemKind::Movie);
        c.genres = vec!["Drama".to_string()]; // same genre string, another item
        apply_upsert_items(&mut conn, &[a, b, c]).expect("insert");

        let genres = library_genres(&conn, &parent);
        assert_eq!(
            genres,
            vec![
                "action".to_string(),
                "Documentary".to_string(),
                "Drama".to_string()
            ]
        );
    }

    #[test]
    fn library_genres_is_empty_for_a_library_with_no_genres_synced() {
        let (_dir, conn) = open_test_db();
        assert!(library_genres(&conn, &uuid_n(1)).is_empty());
    }
}
