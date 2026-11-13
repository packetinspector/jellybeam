//! docs/feature-dev/music-design-brief.md §1: music browse queries answered from the mirror
//! alone. Names are verbatim server strings (CLAUDE.md), never split or re-cased; every list
//! pages with `offset`/`limit`.

use std::borrow::Cow;
use std::collections::HashMap;
use std::sync::Arc;

use tokio::sync::broadcast::error::TryRecvError;

use rusqlite::{params, Connection, ToSql};

use crate::query::{
    grid_group_key_expr, grid_sort_order, row_to_card, run_length_groups, CARD_COLUMNS,
};
use crate::{CacheError, CardRow, GridGroup, GridSort, GridSortField, Mirror};

/// Which songs a song list shows (handoff §4 D); each source fixes its own order.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SongSource {
    /// The Songs tab: every song in the view, by name.
    All { view_id: String },
    /// Favourite songs in the view, most recently added first.
    Favorites { view_id: String },
    /// Played songs in the view, most recently played first (§1.2: the server keeps no
    /// audio resume, so this is a sort, not a row).
    RecentlyPlayed { view_id: String },
    /// Songs in the view tagged with exactly `genre` (§1.2: `A;B` is one genre).
    Genre { view_id: String, genre: String },
    /// Songs naming `artist_id` as album artist or performer, any library.
    Artist { artist_id: String, sort: GridSort },
}

/// The Artists tab toggle (handoff §4 A).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum ArtistScope {
    /// `AlbumArtists` of the view's albums (`/Artists/AlbumArtists`).
    AlbumArtists,
    /// Album artists plus every per-track performer (`/Artists`).
    All,
}

/// One artist: `name` verbatim; `sort_name`/`primary_tag` come from the mirrored
/// `MusicArtist` row, `None` for a per-track performer the server lists only on tracks.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MusicArtistRow {
    pub id: String,
    pub name: String,
    pub sort_name: Option<String>,
    pub primary_tag: Option<String>,
    /// Albums naming the artist as album artist.
    pub album_count: u64,
    /// Albums holding a track the artist performs, minus `album_count`'s albums.
    pub appears_on_count: u64,
    pub song_count: u64,
    /// Total `RunTimeTicks` over `song_count`'s songs.
    pub runtime_ticks: i64,
}

/// One genre of a music view (§1.2), counted over its albums and songs.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MusicGenreRow {
    pub name: String,
    pub album_count: u64,
    pub song_count: u64,
}

/// An album's header facts, counted from its mirrored tracks (§1.2: the sync carries no
/// `ChildCount`).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct AlbumSummary {
    pub track_count: u64,
    /// Distinct `ParentIndexNumber`s; disc headers show only above 1 (handoff §4 C).
    pub disc_count: u64,
    pub runtime_ticks: i64,
}

/// One song source's `FROM ... WHERE`, its binds, its ORDER BY and its group key.
struct SongQuery<'a> {
    scope: String,
    params: Vec<&'a dyn ToSql>,
    order: String,
    key: Cow<'static, str>,
}

const SONGS_IN_VIEW: &str = "FROM items WHERE item_type = 'Audio' AND library_id = ?1";

/// A song source scoped to view `view_id` (bound as `?1`) plus `filter`.
fn in_view<'a>(view_id: &'a dyn ToSql, filter: &str, sort: GridSort) -> SongQuery<'a> {
    SongQuery {
        scope: format!("{SONGS_IN_VIEW}{filter}"),
        params: vec![view_id],
        order: song_sort_order(sort),
        key: song_group_key(sort.field),
    }
}

/// docs/29 §9 A: songs order by `name`, never `sort_name`, which the server prefixes with "disc - track - ".
fn song_sort_order(sort: GridSort) -> String {
    grid_sort_order(sort).replace("sort_name", "name")
}

/// docs/29 §9 A: the rail key follows [`song_sort_order`], so a song files under its title's letter, not "#".
fn song_group_key(field: GridSortField) -> Cow<'static, str> {
    match field {
        GridSortField::Name => Cow::Owned(grid_group_key_expr(field).replace("sort_name", "name")),
        _ => Cow::Borrowed(grid_group_key_expr(field)),
    }
}

fn song_query(source: &SongSource) -> SongQuery<'_> {
    let by_name = GridSort {
        field: GridSortField::Name,
        descending: false,
    };
    match source {
        SongSource::All { view_id } => in_view(view_id, "", by_name),
        SongSource::Favorites { view_id } => in_view(
            view_id,
            " AND is_favorite = 1",
            GridSort {
                field: GridSortField::DateAdded,
                descending: true,
            },
        ),
        SongSource::RecentlyPlayed { view_id } => SongQuery {
            scope: format!("{SONGS_IN_VIEW} AND played = 1"),
            params: vec![view_id as &dyn ToSql],
            order: "last_played_date DESC NULLS LAST, id DESC".to_string(),
            key: Cow::Borrowed("COALESCE(substr(last_played_date, 1, 7), '')"),
        },
        SongSource::Genre { view_id, genre } => SongQuery {
            params: vec![view_id as &dyn ToSql, genre as &dyn ToSql],
            ..in_view(
                view_id,
                " AND id IN (SELECT item_id FROM item_genres WHERE genre = ?2)",
                by_name,
            )
        },
        SongSource::Artist { artist_id, sort } => SongQuery {
            scope: format!("FROM items WHERE {ARTIST_SONGS_WHERE}"),
            params: vec![artist_id as &dyn ToSql],
            order: song_sort_order(*sort),
            key: song_group_key(sort.field),
        },
    }
}

pub(crate) fn songs(
    conn: &Connection,
    source: &SongSource,
    offset: u32,
    limit: u32,
) -> rusqlite::Result<Vec<CardRow>> {
    let q = song_query(source);
    let n = q.params.len();
    let sql = format!(
        "SELECT {CARD_COLUMNS} {} ORDER BY {} LIMIT ?{} OFFSET ?{}",
        q.scope,
        q.order,
        n + 1,
        n + 2
    );
    let mut binds = q.params;
    binds.push(&limit);
    binds.push(&offset);
    let mut stmt = conn.prepare_cached(&sql)?;
    let rows = stmt.query_map(binds.as_slice(), row_to_card)?;
    rows.collect()
}

pub(crate) fn song_count(conn: &Connection, source: &SongSource) -> rusqlite::Result<u64> {
    let q = song_query(source);
    let count: i64 = conn.query_row(
        &format!("SELECT COUNT(*) {}", q.scope),
        q.params.as_slice(),
        |row| row.get(0),
    )?;
    Ok(count.max(0) as u64)
}

/// The A–Z rail's runs over [`songs`]' order (docs/16 §2.4 keys).
pub(crate) fn song_groups(
    conn: &Connection,
    source: &SongSource,
) -> rusqlite::Result<Vec<GridGroup>> {
    let q = song_query(source);
    let sql = format!("SELECT {} {} ORDER BY {}", q.key, q.scope, q.order);
    let mut stmt = conn.prepare_cached(&sql)?;
    let rows = stmt.query_map(q.params.as_slice(), |row| row.get::<_, String>(0))?;
    run_length_groups(rows)
}

const ALBUM_TRACKS_FROM: &str = "FROM items WHERE album_id = ?1 AND item_type = 'Audio'";

/// Handoff §4 C: disc, then track, unnumbered last (shown, never dropped), then name.
pub(crate) fn album_tracks(
    conn: &Connection,
    album_id: &str,
    offset: u32,
    limit: u32,
) -> rusqlite::Result<Vec<CardRow>> {
    let sql = format!(
        "SELECT {CARD_COLUMNS} {ALBUM_TRACKS_FROM} \
         ORDER BY parent_index_number ASC NULLS LAST, index_number ASC NULLS LAST, \
         sort_name COLLATE NOCASE ASC, id ASC LIMIT ?2 OFFSET ?3"
    );
    let mut stmt = conn.prepare_cached(&sql)?;
    let rows = stmt.query_map(params![album_id, limit, offset], row_to_card)?;
    rows.collect()
}

pub(crate) fn album_summary(conn: &Connection, album_id: &str) -> rusqlite::Result<AlbumSummary> {
    conn.prepare_cached(&format!(
        "SELECT COUNT(*), COUNT(DISTINCT parent_index_number), COALESCE(SUM(runtime_ticks), 0) \
         {ALBUM_TRACKS_FROM}"
    ))?
    .query_row([album_id], |row| {
        Ok(AlbumSummary {
            track_count: row.get::<_, i64>(0)?.max(0) as u64,
            disc_count: row.get::<_, i64>(1)?.max(0) as u64,
            runtime_ticks: row.get(2)?,
        })
    })
}

// The artist filters below drive from `idx_item_artists_artist`: the unary `+` keeps SQLite off
// the `(library_id, item_type)` indexes, which would walk every album or song per artist.

/// `items` rows (unaliased) that are `?1`'s albums: album artist on a `MusicAlbum`.
const ARTIST_ALBUMS_WHERE: &str = "+item_type = 'MusicAlbum' AND id IN \
     (SELECT item_id FROM item_artists WHERE artist_id = ?1 AND role = 0)";
/// §1.2: albums holding a track `?1` performs, minus `?1`'s own albums.
const ARTIST_APPEARS_ON_WHERE: &str = "+item_type = 'MusicAlbum' AND id IN \
     (SELECT t.album_id FROM item_artists ia JOIN items t ON t.id = ia.item_id \
      WHERE ia.artist_id = ?1 AND ia.role = 1 AND t.item_type = 'Audio') \
     AND id NOT IN (SELECT item_id FROM item_artists WHERE artist_id = ?1 AND role = 0)";
/// Songs naming `?1` in either role (the server's `ArtistIds=`).
const ARTIST_SONGS_WHERE: &str = "+item_type = 'Audio' AND id IN \
     (SELECT item_id FROM item_artists WHERE artist_id = ?1)";
/// Album shelves: newest first, undated last.
const SHELF_ORDER: &str = "production_year DESC NULLS LAST, sort_name COLLATE NOCASE ASC, id ASC";

fn album_shelf(conn: &Connection, filter: &str, artist_id: &str) -> rusqlite::Result<Vec<CardRow>> {
    let sql = format!("SELECT {CARD_COLUMNS} FROM items WHERE {filter} ORDER BY {SHELF_ORDER}");
    let mut stmt = conn.prepare_cached(&sql)?;
    let rows = stmt.query_map([artist_id], row_to_card)?;
    rows.collect()
}

pub(crate) fn artist_albums(conn: &Connection, artist_id: &str) -> rusqlite::Result<Vec<CardRow>> {
    album_shelf(conn, ARTIST_ALBUMS_WHERE, artist_id)
}

pub(crate) fn artist_appears_on(
    conn: &Connection,
    artist_id: &str,
) -> rusqlite::Result<Vec<CardRow>> {
    album_shelf(conn, ARTIST_APPEARS_ON_WHERE, artist_id)
}

/// Handoff §4 B: the artist's played songs, most played first.
pub(crate) fn artist_most_played(
    conn: &Connection,
    artist_id: &str,
    limit: u32,
) -> rusqlite::Result<Vec<CardRow>> {
    let sql = format!(
        "SELECT {CARD_COLUMNS} FROM items WHERE {ARTIST_SONGS_WHERE} AND play_count > 0 \
         ORDER BY play_count DESC, last_played_date DESC NULLS LAST, \
         sort_name COLLATE NOCASE ASC, id ASC LIMIT ?2"
    );
    let mut stmt = conn.prepare_cached(&sql)?;
    let rows = stmt.query_map(params![artist_id, limit], row_to_card)?;
    rows.collect()
}

/// Where [`artist_counts`] counts: one view (the Artists tab) or the whole mirror (the
/// artist page, reachable from any library).
#[derive(Clone, Copy)]
enum CountScope<'a> {
    View(&'a str),
    Everywhere,
}

/// `(albums, appears on, songs, runtime)` for `artist_id` within `scope`.
fn artist_counts(
    conn: &Connection,
    artist_id: &str,
    scope: CountScope<'_>,
) -> rusqlite::Result<(u64, u64, u64, i64)> {
    let lib = match scope {
        CountScope::View(_) => " AND +library_id = ?2",
        CountScope::Everywhere => "",
    };
    let sql = format!(
        "SELECT (SELECT COUNT(*) FROM items WHERE {ARTIST_ALBUMS_WHERE}{lib}), \
                (SELECT COUNT(*) FROM items WHERE {ARTIST_APPEARS_ON_WHERE}{lib}), \
                (SELECT COUNT(*) FROM items WHERE {ARTIST_SONGS_WHERE}{lib}), \
                (SELECT COALESCE(SUM(runtime_ticks), 0) FROM items WHERE {ARTIST_SONGS_WHERE}{lib})"
    );
    let mut binds: Vec<&dyn ToSql> = vec![&artist_id];
    if let CountScope::View(view_id) = &scope {
        binds.push(view_id);
    }
    conn.prepare_cached(&sql)?
        .query_row(binds.as_slice(), |row| {
            Ok((
                row.get::<_, i64>(0)?.max(0) as u64,
                row.get::<_, i64>(1)?.max(0) as u64,
                row.get::<_, i64>(2)?.max(0) as u64,
                row.get(3)?,
            ))
        })
}

/// The scope's artists in view `?1`: `listed` holds each artist id once, `named` adds the
/// mirrored `MusicArtist` row's name, sort name and art.
fn artists_cte(scope: ArtistScope) -> String {
    let filter = match scope {
        ArtistScope::AlbumArtists => "i.item_type = 'MusicAlbum' AND ia.role = 0",
        ArtistScope::All => "i.item_type IN ('MusicAlbum', 'Audio')",
    };
    format!(
        "WITH listed AS (\
             SELECT ia.artist_id AS artist_id, MIN(ia.name) AS link_name \
             FROM items i JOIN item_artists ia ON ia.item_id = i.id \
             WHERE i.library_id = ?1 AND ia.artist_id IS NOT NULL AND {filter} \
             GROUP BY ia.artist_id), \
         named AS (\
             SELECT l.artist_id AS artist_id, COALESCE(a.name, l.link_name, '') AS name, \
                    a.sort_name AS sort_name, a.primary_tag AS primary_tag \
             FROM listed l LEFT JOIN items a ON a.id = l.artist_id AND a.item_type = 'MusicArtist') "
    )
}

/// §1.2: artists sort by their sort name (the server drops a leading "the"), else the name.
const ARTIST_ORDER: &str =
    "COALESCE(sort_name, name) COLLATE NOCASE ASC, name COLLATE NOCASE ASC, artist_id ASC";
const ARTIST_KEY: &str = "CASE WHEN upper(substr(COALESCE(sort_name, name), 1, 1)) \
     BETWEEN 'A' AND 'Z' THEN upper(substr(COALESCE(sort_name, name), 1, 1)) ELSE '#' END";

/// One view's artists in one scope, in tab order: `(id, name, sort_name, primary_tag)` and
/// the A–Z runs, so a page is a slice and the count and rail cost nothing.
pub(crate) struct ArtistList {
    rows: Vec<(String, String, Option<String>, Option<String>)>,
    groups: Vec<GridGroup>,
}

/// docs/29 §7: the Artists tab's lists, built on the first read of a (view, scope) and dropped
/// after any mirror write; the change feed is subscribed on that first read, so a mirror whose
/// music is never opened holds nothing.
#[derive(Default)]
pub(crate) struct ArtistLists {
    changes: Option<tokio::sync::broadcast::Receiver<crate::MirrorChange>>,
    built: HashMap<(String, ArtistScope), Arc<ArtistList>>,
}

/// Builds the list with one grouped pass over the view; [`ArtistLists`] keeps it.
pub(crate) fn build_artist_list(
    conn: &Connection,
    view_id: &str,
    scope: ArtistScope,
) -> rusqlite::Result<ArtistList> {
    let sql = format!(
        "{}SELECT artist_id, name, sort_name, primary_tag, {ARTIST_KEY} FROM named \
         ORDER BY {ARTIST_ORDER}",
        artists_cte(scope)
    );
    let mut stmt = conn.prepare_cached(&sql)?;
    let mut rows = Vec::new();
    let mut groups: Vec<GridGroup> = Vec::new();
    let mut q = stmt.query([view_id])?;
    while let Some(row) = q.next()? {
        rows.push((row.get(0)?, row.get(1)?, row.get(2)?, row.get(3)?));
        let key: String = row.get(4)?;
        match groups.last_mut() {
            Some(last) if last.key == key => last.count += 1,
            _ => groups.push(GridGroup { key, count: 1 }),
        }
    }
    Ok(ArtistList { rows, groups })
}

/// Rows `offset..offset + limit` of `list`, each with its counts in `view_id`.
fn artist_page(
    conn: &Connection,
    list: &ArtistList,
    view_id: &str,
    offset: u32,
    limit: u32,
) -> rusqlite::Result<Vec<MusicArtistRow>> {
    list.rows
        .iter()
        .skip(offset as usize)
        .take(limit as usize)
        .map(|(id, name, sort_name, primary_tag)| {
            let (album_count, appears_on_count, song_count, runtime_ticks) =
                artist_counts(conn, id, CountScope::View(view_id))?;
            Ok(MusicArtistRow {
                id: id.clone(),
                name: name.clone(),
                sort_name: sort_name.clone(),
                primary_tag: primary_tag.clone(),
                album_count,
                appears_on_count,
                song_count,
                runtime_ticks,
            })
        })
        .collect()
}

#[cfg(test)]
pub(crate) fn artists(
    conn: &Connection,
    view_id: &str,
    scope: ArtistScope,
    offset: u32,
    limit: u32,
) -> rusqlite::Result<Vec<MusicArtistRow>> {
    artist_page(
        conn,
        &build_artist_list(conn, view_id, scope)?,
        view_id,
        offset,
        limit,
    )
}

#[cfg(test)]
pub(crate) fn artist_count(
    conn: &Connection,
    view_id: &str,
    scope: ArtistScope,
) -> rusqlite::Result<u64> {
    Ok(build_artist_list(conn, view_id, scope)?.rows.len() as u64)
}

#[cfg(test)]
pub(crate) fn artist_groups(
    conn: &Connection,
    view_id: &str,
    scope: ArtistScope,
) -> rusqlite::Result<Vec<GridGroup>> {
    Ok(build_artist_list(conn, view_id, scope)?.groups)
}

/// The artist page header across every library; `None` when the mirror has neither a
/// `MusicArtist` row nor any track or album naming `artist_id`.
pub(crate) fn artist_summary(
    conn: &Connection,
    artist_id: &str,
) -> rusqlite::Result<Option<MusicArtistRow>> {
    let head: Option<(String, Option<String>, Option<String>)> = conn
        .prepare_cached(
            "SELECT COALESCE(a.name, (SELECT MIN(name) FROM item_artists WHERE artist_id = ?1), ''), \
                    a.sort_name, a.primary_tag \
             FROM (SELECT ?1 AS id) k \
             LEFT JOIN items a ON a.id = k.id AND a.item_type = 'MusicArtist' \
             WHERE a.id IS NOT NULL OR EXISTS (SELECT 1 FROM item_artists WHERE artist_id = ?1)",
        )?
        .query_row([artist_id], |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)))
        .map(Some)
        .or_else(|e| match e {
            rusqlite::Error::QueryReturnedNoRows => Ok(None),
            other => Err(other),
        })?;
    let Some((name, sort_name, primary_tag)) = head else {
        return Ok(None);
    };
    let (album_count, appears_on_count, song_count, runtime_ticks) =
        artist_counts(conn, artist_id, CountScope::Everywhere)?;
    Ok(Some(MusicArtistRow {
        id: artist_id.to_string(),
        name,
        sort_name,
        primary_tag,
        album_count,
        appears_on_count,
        song_count,
        runtime_ticks,
    }))
}

/// §1.2: the view's genres verbatim, with how many of its albums and songs carry each.
pub(crate) fn genres(conn: &Connection, view_id: &str) -> rusqlite::Result<Vec<MusicGenreRow>> {
    let mut stmt = conn.prepare_cached(
        "SELECT g.genre, SUM(i.item_type = 'MusicAlbum'), SUM(i.item_type = 'Audio') \
         FROM items i JOIN item_genres g ON g.item_id = i.id \
         WHERE i.library_id = ?1 AND i.item_type IN ('MusicAlbum', 'Audio') \
         GROUP BY g.genre ORDER BY g.genre COLLATE NOCASE ASC, g.genre ASC",
    )?;
    let rows = stmt.query_map([view_id], |row| {
        Ok(MusicGenreRow {
            name: row.get(0)?,
            album_count: row.get::<_, i64>(1)?.max(0) as u64,
            song_count: row.get::<_, i64>(2)?.max(0) as u64,
        })
    })?;
    rows.collect()
}

/// One probe per gain column, each served by its partial index (`schema.rs`), so a mirror
/// without music pays a lookup, never a scan of `items`.
const HAS_NORMALIZATION_GAIN: &str =
    "SELECT EXISTS (SELECT 1 FROM items WHERE normalization_gain IS NOT NULL) \
     OR EXISTS (SELECT 1 FROM items WHERE album_normalization_gain IS NOT NULL)";

/// Handoff §9: whether any mirrored song or album carries a served normalisation gain.
pub(crate) fn has_normalization_gain(conn: &Connection) -> rusqlite::Result<bool> {
    conn.prepare_cached(HAS_NORMALIZATION_GAIN)?
        .query_row([], |row| row.get(0))
}

fn db_err(e: rusqlite::Error) -> CacheError {
    CacheError::Db(e.to_string())
}

/// docs/feature-dev/music-design-brief.md §1: music reads. Each returns `Err` when the query
/// failed, so a screen keeps its last good list instead of showing an empty one.
impl Mirror {
    pub fn music_songs(
        &self,
        source: &SongSource,
        offset: u32,
        limit: u32,
    ) -> Result<Vec<CardRow>, CacheError> {
        songs(&self.inner.read_pool.acquire(), source, offset, limit).map_err(db_err)
    }

    pub fn music_song_count(&self, source: &SongSource) -> Result<u64, CacheError> {
        song_count(&self.inner.read_pool.acquire(), source).map_err(db_err)
    }

    pub fn music_song_groups(&self, source: &SongSource) -> Result<Vec<GridGroup>, CacheError> {
        song_groups(&self.inner.read_pool.acquire(), source).map_err(db_err)
    }

    pub fn album_tracks(
        &self,
        album_id: &str,
        offset: u32,
        limit: u32,
    ) -> Result<Vec<CardRow>, CacheError> {
        album_tracks(&self.inner.read_pool.acquire(), album_id, offset, limit).map_err(db_err)
    }

    pub fn album_summary(&self, album_id: &str) -> Result<AlbumSummary, CacheError> {
        album_summary(&self.inner.read_pool.acquire(), album_id).map_err(db_err)
    }

    /// [`ArtistLists`]: the kept list for `(view_id, scope)`, rebuilt when the mirror changed.
    pub(crate) fn artist_list(
        &self,
        view_id: &str,
        scope: ArtistScope,
    ) -> Result<Arc<ArtistList>, CacheError> {
        let mut lists = self
            .inner
            .artist_lists
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        let changed = match lists.changes.as_mut() {
            None => true,
            Some(rx) => {
                let mut any = false;
                // Every pending change, or a lag past the feed's buffer, means rebuild.
                while let Ok(_) | Err(TryRecvError::Lagged(_)) = rx.try_recv() {
                    any = true;
                }
                any
            }
        };
        if changed {
            lists.changes = Some(self.inner.changes_tx.subscribe());
            lists.built.clear();
        }
        let key = (view_id.to_string(), scope);
        if let Some(list) = lists.built.get(&key) {
            return Ok(list.clone());
        }
        let list = Arc::new(
            build_artist_list(&self.inner.read_pool.acquire(), view_id, scope).map_err(db_err)?,
        );
        lists.built.insert(key, list.clone());
        Ok(list)
    }

    pub fn music_artists(
        &self,
        view_id: &str,
        scope: ArtistScope,
        offset: u32,
        limit: u32,
    ) -> Result<Vec<MusicArtistRow>, CacheError> {
        let list = self.artist_list(view_id, scope)?;
        artist_page(
            &self.inner.read_pool.acquire(),
            &list,
            view_id,
            offset,
            limit,
        )
        .map_err(db_err)
    }

    pub fn music_artist_count(&self, view_id: &str, scope: ArtistScope) -> Result<u64, CacheError> {
        Ok(self.artist_list(view_id, scope)?.rows.len() as u64)
    }

    pub fn music_artist_groups(
        &self,
        view_id: &str,
        scope: ArtistScope,
    ) -> Result<Vec<GridGroup>, CacheError> {
        Ok(self.artist_list(view_id, scope)?.groups.clone())
    }

    pub fn artist_summary(&self, artist_id: &str) -> Result<Option<MusicArtistRow>, CacheError> {
        artist_summary(&self.inner.read_pool.acquire(), artist_id).map_err(db_err)
    }

    pub fn artist_albums(&self, artist_id: &str) -> Result<Vec<CardRow>, CacheError> {
        artist_albums(&self.inner.read_pool.acquire(), artist_id).map_err(db_err)
    }

    pub fn artist_appears_on(&self, artist_id: &str) -> Result<Vec<CardRow>, CacheError> {
        artist_appears_on(&self.inner.read_pool.acquire(), artist_id).map_err(db_err)
    }

    pub fn artist_most_played(
        &self,
        artist_id: &str,
        limit: u32,
    ) -> Result<Vec<CardRow>, CacheError> {
        artist_most_played(&self.inner.read_pool.acquire(), artist_id, limit).map_err(db_err)
    }

    pub fn music_genres(&self, view_id: &str) -> Result<Vec<MusicGenreRow>, CacheError> {
        genres(&self.inner.read_pool.acquire(), view_id).map_err(db_err)
    }

    pub fn music_has_normalization_gain(&self) -> Result<bool, CacheError> {
        has_normalization_gain(&self.inner.read_pool.acquire()).map_err(db_err)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::schema::open_test_db;
    use crate::writer::apply_upsert_items_scoped;
    use crate::{GridFilters, StatusFilter, WatchedFilter};
    use jellyfin_api::models::BaseItemDto;
    use serde_json::{json, Value};

    const VIEW: &str = "00000000-0000-4000-8000-000000000001";
    const TICKS: i64 = 1_800_000_000;

    fn id(n: u32) -> String {
        format!("00000000-0000-4000-8000-{n:012}")
    }

    fn pair(n: u32, name: &str) -> Value {
        json!({ "Name": name, "Id": id(n) })
    }

    const TEST_BAND: u32 = 301;
    const QUIET_ONES: u32 = 302;
    const VARIOUS: u32 = 303;
    const SOLO: u32 = 304;
    const ZED: u32 = 305;
    const PLAIN: u32 = 201;
    const TWO_DISC: u32 = 202;
    const COMP: u32 = 203;
    const QUIET_ALBUM: u32 = 204;

    fn artist_name(n: u32) -> &'static str {
        match n {
            TEST_BAND => "Test Band",
            QUIET_ONES => "The Quiet Ones",
            VARIOUS => "Various Artists",
            SOLO => "Solo Singer",
            _ => "Zed Performer",
        }
    }

    fn album(n: u32, name: &str, by: u32, year: i32, added: &str, genre: Option<&str>) -> Value {
        json!({
            "Id": id(n), "Name": name, "SortName": name, "Type": "MusicAlbum", "ParentId": VIEW,
            "AlbumArtist": artist_name(by), "AlbumArtists": [pair(by, artist_name(by))],
            "ProductionYear": year, "DateCreated": added, "Genres": genre.into_iter().collect::<Vec<_>>(),
            "ImageTags": { "Primary": format!("cover-{n}") },
        })
    }

    struct Track {
        n: u32,
        name: &'static str,
        album: u32,
        disc: Option<i32>,
        track: Option<i32>,
        performers: &'static [u32],
    }

    /// The server's own song SortName: "disc - track - name", each number when present.
    fn server_song_sort_name(t: &Track) -> String {
        let n = |v: Option<i32>| v.map(|v| format!("{v:04} - ")).unwrap_or_default();
        format!("{}{}{}", n(t.disc), n(t.track), t.name)
    }

    fn track(t: &Track, album_name: &str, by: u32, added: &str, genre: Option<&str>) -> Value {
        json!({
            "Id": id(t.n), "Name": t.name, "SortName": server_song_sort_name(t), "Type": "Audio",
            "ParentId": id(t.album), "AlbumId": id(t.album), "Album": album_name,
            "AlbumArtist": artist_name(by), "AlbumArtists": [pair(by, artist_name(by))],
            "ArtistItems": t.performers.iter().map(|p| pair(*p, artist_name(*p))).collect::<Vec<_>>(),
            "ParentIndexNumber": t.disc, "IndexNumber": t.track, "RunTimeTicks": TICKS,
            "DateCreated": added, "Genres": genre.into_iter().collect::<Vec<_>>(),
            "AlbumPrimaryImageTag": format!("cover-{}", t.album),
        })
    }

    fn user_data(v: &mut Value, plays: i32, last_played: Option<&str>, favorite: bool) {
        v["UserData"] = json!({
            "PlayCount": plays, "Played": plays > 0, "IsFavorite": favorite,
            "LastPlayedDate": last_played,
        });
    }

    /// Synthetic music view: two Test Band albums (one two-disc, genre `Genre;Two`), a
    /// Various Artists compilation with per-track performers, and one The Quiet Ones album.
    fn music_db() -> (tempfile::TempDir, Connection) {
        let (dir, mut conn) = open_test_db();
        conn.execute(
            "INSERT INTO views (id, name, collection_type, sort_index) VALUES (?1, 'Music', 'music', 0)",
            [VIEW],
        )
        .expect("view");
        let t = |n, name, album, disc, track, performers| Track {
            n,
            name,
            album,
            disc,
            track,
            performers,
        };
        let plain = "2024-01-01T00:00:00Z";
        let two = "2024-03-01T00:00:00Z";
        let comp = "2024-02-01T00:00:00Z";
        let quiet = "2024-04-01T00:00:00Z";
        let mut values = vec![
            json!({ "Id": id(TEST_BAND), "Name": "Test Band", "SortName": "Test Band", "Type": "MusicArtist" }),
            json!({ "Id": id(QUIET_ONES), "Name": "The Quiet Ones", "SortName": "Quiet Ones", "Type": "MusicArtist" }),
            json!({ "Id": id(VARIOUS), "Name": "Various Artists", "SortName": "Various Artists",
                    "Type": "MusicArtist", "ImageTags": { "Primary": "va-tag" } }),
            album(PLAIN, "Plain Album", TEST_BAND, 2019, plain, Some("Rock")),
            album(
                TWO_DISC,
                "Two Disc Album",
                TEST_BAND,
                2022,
                two,
                Some("Genre;Two"),
            ),
            album(COMP, "Comp Album", VARIOUS, 2021, comp, None),
            album(QUIET_ALBUM, "Quiet Album", QUIET_ONES, 2024, quiet, None),
        ];
        // §1.2: an album also lists its tracks' performers as `ArtistItems`.
        values[5]["ArtistItems"] = json!([pair(ZED, "Zed Performer"), pair(SOLO, "Solo Singer")]);
        let tracks = [
            (
                t(101, "Plain One", PLAIN, Some(1), Some(1), &[TEST_BAND]),
                "Plain Album",
                TEST_BAND,
                plain,
                Some("Rock"),
            ),
            (
                t(102, "Plain Two", PLAIN, Some(1), Some(2), &[TEST_BAND]),
                "Plain Album",
                TEST_BAND,
                plain,
                Some("Rock"),
            ),
            (
                t(103, "Plain Unnumbered", PLAIN, Some(1), None, &[TEST_BAND]),
                "Plain Album",
                TEST_BAND,
                plain,
                Some("Rock"),
            ),
            (
                t(
                    111,
                    "Disc Two Track One",
                    TWO_DISC,
                    Some(2),
                    Some(1),
                    &[TEST_BAND],
                ),
                "Two Disc Album",
                TEST_BAND,
                two,
                Some("Genre;Two"),
            ),
            (
                t(
                    112,
                    "Disc One Track Two",
                    TWO_DISC,
                    Some(1),
                    Some(2),
                    &[TEST_BAND],
                ),
                "Two Disc Album",
                TEST_BAND,
                two,
                Some("Genre;Two"),
            ),
            (
                t(
                    113,
                    "Disc One Track One",
                    TWO_DISC,
                    Some(1),
                    Some(1),
                    &[TEST_BAND],
                ),
                "Two Disc Album",
                TEST_BAND,
                two,
                Some("Genre;Two"),
            ),
            (
                t(
                    114,
                    "Disc Two Track Two",
                    TWO_DISC,
                    Some(2),
                    Some(2),
                    &[TEST_BAND],
                ),
                "Two Disc Album",
                TEST_BAND,
                two,
                Some("Genre;Two"),
            ),
            (
                t(121, "Comp First", COMP, None, Some(1), &[SOLO]),
                "Comp Album",
                VARIOUS,
                comp,
                None,
            ),
            (
                t(122, "Comp Second", COMP, None, Some(2), &[ZED]),
                "Comp Album",
                VARIOUS,
                comp,
                None,
            ),
            (
                t(123, "Comp Third", COMP, None, Some(3), &[TEST_BAND]),
                "Comp Album",
                VARIOUS,
                comp,
                None,
            ),
            (
                t(124, "Comp Duet", COMP, None, Some(4), &[ZED, SOLO]),
                "Comp Album",
                VARIOUS,
                comp,
                None,
            ),
            (
                t(
                    131,
                    "Quiet Song",
                    QUIET_ALBUM,
                    Some(1),
                    Some(1),
                    &[QUIET_ONES],
                ),
                "Quiet Album",
                QUIET_ONES,
                quiet,
                None,
            ),
        ];
        for (tr, album_name, by, added, genre) in &tracks {
            let mut v = track(tr, album_name, *by, added, *genre);
            match tr.n {
                101 => user_data(&mut v, 3, Some("2024-05-01T00:00:00Z"), false),
                102 => user_data(&mut v, 5, Some("2024-06-01T00:00:00Z"), true),
                113 => user_data(&mut v, 0, None, true),
                123 => user_data(&mut v, 1, Some("2024-04-01T00:00:00Z"), false),
                124 => v["HasLyrics"] = json!(true),
                _ => {}
            }
            values.push(v);
        }
        let dtos: Vec<BaseItemDto> = values
            .into_iter()
            .map(|v| serde_json::from_value(v).expect("dto"))
            .collect();
        apply_upsert_items_scoped(&mut conn, &dtos, Some(VIEW)).expect("seed");
        (dir, conn)
    }

    /// Handoff §9: no served gain reads false; one track's or one album's gain reads true.
    #[test]
    fn normalization_gain_is_found_on_any_track_or_album() {
        let (_dir, mut conn) = music_db();
        assert!(!has_normalization_gain(&conn).expect("query"));
        let mut gained = track(
            &Track {
                n: 141,
                name: "Gained",
                album: PLAIN,
                disc: Some(1),
                track: Some(9),
                performers: &[TEST_BAND],
            },
            "Plain Album",
            TEST_BAND,
            "2024-01-01T00:00:00Z",
            None,
        );
        gained["AlbumNormalizationGain"] = json!(-7.5);
        let dto: BaseItemDto = serde_json::from_value(gained).expect("dto");
        apply_upsert_items_scoped(&mut conn, &[dto], Some(VIEW)).expect("upsert");
        assert!(has_normalization_gain(&conn).expect("query"));
    }

    fn names(rows: &[CardRow]) -> Vec<&str> {
        rows.iter().map(|r| r.name.as_str()).collect()
    }

    fn artist_ids(rows: &[MusicArtistRow]) -> Vec<String> {
        rows.iter().map(|r| r.id.clone()).collect()
    }

    #[test]
    fn album_tracks_order_by_disc_then_track_and_keep_unnumbered_tracks_last() {
        let (_dir, conn) = music_db();
        let two = album_tracks(&conn, &id(TWO_DISC), 0, 100).expect("tracks");
        assert_eq!(
            names(&two),
            vec![
                "Disc One Track One",
                "Disc One Track Two",
                "Disc Two Track One",
                "Disc Two Track Two"
            ]
        );
        let plain = album_tracks(&conn, &id(PLAIN), 0, 100).expect("tracks");
        assert_eq!(
            names(&plain),
            vec!["Plain One", "Plain Two", "Plain Unnumbered"]
        );
        let page = album_tracks(&conn, &id(TWO_DISC), 1, 2).expect("page");
        assert_eq!(
            names(&page),
            vec!["Disc One Track Two", "Disc Two Track One"]
        );
    }

    #[test]
    fn album_summary_counts_tracks_discs_and_runtime() {
        let (_dir, conn) = music_db();
        assert_eq!(
            album_summary(&conn, &id(TWO_DISC)).expect("summary"),
            AlbumSummary {
                track_count: 4,
                disc_count: 2,
                runtime_ticks: 4 * TICKS
            }
        );
        assert_eq!(
            album_summary(&conn, &id(COMP)).expect("summary").disc_count,
            0,
            "no ParentIndexNumber means no disc headers"
        );
    }

    /// §1.2: a compilation track carries its own performers in server order, verbatim, beside
    /// the album artist string.
    #[test]
    fn compilation_tracks_keep_their_own_performers_in_order() {
        let (_dir, conn) = music_db();
        let tracks = album_tracks(&conn, &id(COMP), 0, 100).expect("tracks");
        let duet = tracks.iter().find(|t| t.name == "Comp Duet").expect("duet");
        assert_eq!(duet.artists, vec!["Zed Performer", "Solo Singer"]);
        assert_eq!(duet.album_artist.as_deref(), Some("Various Artists"));
        assert_eq!(duet.album.as_deref(), Some("Comp Album"));
        assert_eq!(duet.album_id.as_deref(), Some(id(COMP).as_str()));
        assert_eq!(
            duet.album_primary_tag.as_deref(),
            Some(format!("cover-{COMP}").as_str())
        );
        assert!(duet.has_lyrics);
        assert!(!tracks[0].has_lyrics);
    }

    /// A song and an album card name the first album artist's id (a compilation track's, not
    /// its performers'), carry the play count, and a non-music row names no artist.
    #[test]
    fn music_cards_carry_play_count_and_the_first_album_artist_id() {
        let (_dir, mut conn) = music_db();
        let tracks = album_tracks(&conn, &id(COMP), 0, 100).expect("tracks");
        let duet = tracks.iter().find(|t| t.name == "Comp Duet").expect("duet");
        assert_eq!(duet.album_artist_id.as_deref(), Some(id(VARIOUS).as_str()));
        let plain = album_tracks(&conn, &id(PLAIN), 0, 100).expect("tracks");
        let counts: Vec<(&str, u32)> = plain
            .iter()
            .map(|t| (t.name.as_str(), t.play_count))
            .collect();
        assert_eq!(
            counts,
            vec![("Plain One", 3), ("Plain Two", 5), ("Plain Unnumbered", 0)]
        );

        let albums = artist_albums(&conn, &id(VARIOUS)).expect("albums");
        assert_eq!(
            albums
                .iter()
                .map(|a| a.album_artist_id.clone())
                .collect::<Vec<_>>(),
            vec![Some(id(VARIOUS))]
        );

        let found = crate::query::search(&conn, "Comp Album", 20);
        let comp = found
            .iter()
            .find(|r| r.item_type == "MusicAlbum")
            .expect("album");
        assert_eq!(comp.album_artist_id.as_deref(), Some(id(VARIOUS).as_str()));

        let mut movie = json!({ "Id": id(401), "Name": "Film", "Type": "Movie", "ParentId": VIEW });
        movie["AlbumArtists"] = json!([pair(TEST_BAND, "Test Band")]);
        let dto: BaseItemDto = serde_json::from_value(movie).expect("dto");
        apply_upsert_items_scoped(&mut conn, &[dto], Some(VIEW)).expect("upsert");
        let film = crate::query::card_by_id(&conn, &id(401)).expect("film");
        assert_eq!((film.album_artist_id, film.play_count), (None, 0));
    }

    /// §1.2: `Genre;Two` stays one genre, matched only by its exact string.
    #[test]
    fn a_semicolon_genre_stays_one_genre() {
        let (_dir, conn) = music_db();
        assert_eq!(
            genres(&conn, VIEW).expect("genres"),
            vec![
                MusicGenreRow {
                    name: "Genre;Two".to_string(),
                    album_count: 1,
                    song_count: 4
                },
                MusicGenreRow {
                    name: "Rock".to_string(),
                    album_count: 1,
                    song_count: 3
                },
            ]
        );
        let tagged = SongSource::Genre {
            view_id: VIEW.to_string(),
            genre: "Genre;Two".to_string(),
        };
        assert_eq!(song_count(&conn, &tagged).expect("count"), 4);
        assert_eq!(
            names(&songs(&conn, &tagged, 0, 100).expect("songs")),
            vec![
                "Disc One Track One",
                "Disc One Track Two",
                "Disc Two Track One",
                "Disc Two Track Two"
            ]
        );
        let prefix = SongSource::Genre {
            view_id: VIEW.to_string(),
            genre: "Genre".to_string(),
        };
        assert_eq!(song_count(&conn, &prefix).expect("count"), 0);
    }

    /// Handoff §4 A: album artists list only `AlbumArtists`; All adds per-track performers,
    /// both sorted by sort name (The Quiet Ones under Q) with per-view counts.
    #[test]
    fn album_artists_scope_omits_performers_and_all_adds_them() {
        let (_dir, conn) = music_db();
        let album_artists =
            artists(&conn, VIEW, ArtistScope::AlbumArtists, 0, 100).expect("artists");
        assert_eq!(
            artist_ids(&album_artists),
            vec![id(QUIET_ONES), id(TEST_BAND), id(VARIOUS)]
        );
        assert_eq!(album_artists[0].name, "The Quiet Ones");
        assert_eq!(album_artists[0].sort_name.as_deref(), Some("Quiet Ones"));
        assert_eq!(album_artists[2].primary_tag.as_deref(), Some("va-tag"));
        assert_eq!(
            artist_count(&conn, VIEW, ArtistScope::AlbumArtists).expect("count"),
            3
        );

        let all = artists(&conn, VIEW, ArtistScope::All, 0, 100).expect("artists");
        assert_eq!(
            artist_ids(&all),
            vec![
                id(QUIET_ONES),
                id(SOLO),
                id(TEST_BAND),
                id(VARIOUS),
                id(ZED)
            ]
        );
        assert_eq!(
            artist_count(&conn, VIEW, ArtistScope::All).expect("count"),
            5
        );
        let solo = &all[1];
        assert_eq!(
            (
                solo.name.as_str(),
                solo.sort_name.as_deref(),
                solo.primary_tag.as_deref()
            ),
            ("Solo Singer", None, None)
        );
        assert_eq!(
            (
                solo.album_count,
                solo.appears_on_count,
                solo.song_count,
                solo.runtime_ticks
            ),
            (0, 1, 2, 2 * TICKS)
        );
        let band = &all[2];
        assert_eq!(
            (band.album_count, band.appears_on_count, band.song_count),
            (2, 1, 8)
        );
        let various = &all[3];
        assert_eq!(
            (
                various.album_count,
                various.appears_on_count,
                various.song_count
            ),
            (1, 0, 4)
        );
        let page = artists(&conn, VIEW, ArtistScope::All, 1, 2).expect("page");
        assert_eq!(artist_ids(&page), vec![id(SOLO), id(TEST_BAND)]);
        assert_eq!(
            artist_groups(&conn, VIEW, ArtistScope::All).expect("groups"),
            ["Q", "S", "T", "V", "Z"]
                .iter()
                .map(|k| GridGroup {
                    key: k.to_string(),
                    count: 1
                })
                .collect::<Vec<_>>()
        );
    }

    /// Handoff §4 B: an artist's own albums never repeat under Appears on, even when the
    /// artist also performs their tracks.
    #[test]
    fn appears_on_excludes_the_artists_own_albums() {
        let (_dir, conn) = music_db();
        assert_eq!(
            names(&artist_albums(&conn, &id(TEST_BAND)).expect("albums")),
            vec!["Two Disc Album", "Plain Album"]
        );
        assert_eq!(
            names(&artist_appears_on(&conn, &id(TEST_BAND)).expect("appears")),
            vec!["Comp Album"]
        );
        assert!(artist_albums(&conn, &id(SOLO)).expect("albums").is_empty());
        assert_eq!(
            names(&artist_appears_on(&conn, &id(SOLO)).expect("appears")),
            vec!["Comp Album"]
        );
        assert!(artist_appears_on(&conn, &id(VARIOUS))
            .expect("appears")
            .is_empty());
    }

    #[test]
    fn artist_summary_counts_across_libraries_and_knows_unmirrored_performers() {
        let (_dir, conn) = music_db();
        let band = artist_summary(&conn, &id(TEST_BAND))
            .expect("query")
            .expect("known");
        assert_eq!(
            (
                band.name.as_str(),
                band.album_count,
                band.appears_on_count,
                band.song_count,
                band.runtime_ticks
            ),
            ("Test Band", 2, 1, 8, 8 * TICKS)
        );
        let zed = artist_summary(&conn, &id(ZED))
            .expect("query")
            .expect("known");
        assert_eq!((zed.name.as_str(), zed.song_count), ("Zed Performer", 2));
        assert_eq!(artist_summary(&conn, &id(999)).expect("query"), None);
    }

    #[test]
    fn most_played_ranks_only_played_songs_by_play_count() {
        let (_dir, conn) = music_db();
        assert_eq!(
            names(&artist_most_played(&conn, &id(TEST_BAND), 5).expect("most played")),
            vec!["Plain Two", "Plain One", "Comp Third"]
        );
        assert_eq!(
            names(&artist_most_played(&conn, &id(TEST_BAND), 2).expect("most played")),
            vec!["Plain Two", "Plain One"]
        );
        assert!(artist_most_played(&conn, &id(ZED), 5)
            .expect("most played")
            .is_empty());
    }

    #[test]
    fn song_sources_filter_and_order_as_specified() {
        let (_dir, conn) = music_db();
        let view = || VIEW.to_string();
        let all = SongSource::All { view_id: view() };
        assert_eq!(song_count(&conn, &all).expect("count"), 12);
        assert_eq!(
            names(&songs(&conn, &all, 0, 3).expect("songs")),
            vec!["Comp Duet", "Comp First", "Comp Second"]
        );
        let groups = song_groups(&conn, &all).expect("groups");
        assert_eq!(groups.iter().map(|g| g.count).sum::<u64>(), 12);
        assert_eq!(
            groups[0],
            GridGroup {
                key: "C".to_string(),
                count: 4
            }
        );

        let favorites = SongSource::Favorites { view_id: view() };
        assert_eq!(
            names(&songs(&conn, &favorites, 0, 100).expect("songs")),
            vec!["Disc One Track One", "Plain Two"]
        );
        let recent = SongSource::RecentlyPlayed { view_id: view() };
        assert_eq!(
            names(&songs(&conn, &recent, 0, 100).expect("songs")),
            vec!["Plain Two", "Plain One", "Comp Third"]
        );
        let by_artist = SongSource::Artist {
            artist_id: id(SOLO),
            sort: GridSort {
                field: GridSortField::Name,
                descending: false,
            },
        };
        assert_eq!(
            names(&songs(&conn, &by_artist, 0, 100).expect("songs")),
            vec!["Comp Duet", "Comp First"]
        );
    }

    /// Handoff §4 J (brief §1.3): a music library's Latest is its albums, newest first.
    #[test]
    fn latest_for_a_music_view_returns_albums() {
        let (_dir, conn) = music_db();
        let latest = crate::query::latest(&conn, VIEW, 10, false, false);
        assert_eq!(
            names(&latest),
            vec!["Quiet Album", "Two Disc Album", "Comp Album", "Plain Album"]
        );
        assert!(latest.iter().all(|r| r.item_type == "MusicAlbum"));
    }

    /// Handoff §4 A: the Albums tab is the library grid filtered to `MusicAlbum`.
    #[test]
    fn library_grid_filtered_to_albums_lists_the_views_albums() {
        let (_dir, conn) = music_db();
        let filters = GridFilters {
            watched: WatchedFilter::Any,
            genre: None,
            decade: None,
            status: StatusFilter::Any,
            item_type: Some("MusicAlbum".to_string()),
        };
        let by_year = GridSort {
            field: GridSortField::Year,
            descending: true,
        };
        let rows = crate::query::library_grid(&conn, VIEW, by_year, &filters, 0, 100);
        assert_eq!(
            names(&rows),
            vec!["Quiet Album", "Two Disc Album", "Comp Album", "Plain Album"]
        );
        assert_eq!(
            crate::query::library_grid_counts(&conn, VIEW, &filters).filtered,
            4
        );
    }

    /// Handoff §4 K: search's title index already covers every music type.
    #[test]
    fn search_finds_artists_albums_and_songs() {
        let (_dir, conn) = music_db();
        let types = |q: &str| -> Vec<String> {
            let mut t: Vec<String> = crate::query::search(&conn, q, 20)
                .into_iter()
                .map(|r| r.item_type)
                .collect();
            t.sort();
            t.dedup();
            t
        };
        assert_eq!(types("Comp"), vec!["Audio", "MusicAlbum"]);
        assert_eq!(types("Test Band"), vec!["MusicArtist"]);
    }

    /// The music reads seek their indexes; none walks the whole `items` table.
    #[test]
    fn music_queries_never_scan_items() {
        let (_dir, conn) = music_db();
        let plan = |sql: &str| -> Vec<String> {
            let mut stmt = conn
                .prepare(&format!("EXPLAIN QUERY PLAN {sql}"))
                .expect("prepare");
            let unbound = std::iter::repeat_n(rusqlite::types::Null, stmt.parameter_count());
            let lines: Vec<String> = stmt
                .query_map(rusqlite::params_from_iter(unbound), |row| {
                    row.get::<_, String>(3)
                })
                .expect("plan")
                .collect::<Result<_, _>>()
                .expect("rows");
            lines
        };
        let cases = [
            format!("SELECT id {ALBUM_TRACKS_FROM} ORDER BY parent_index_number"),
            format!("SELECT id {SONGS_IN_VIEW} ORDER BY sort_name COLLATE NOCASE ASC NULLS LAST, id ASC"),
            format!("SELECT id FROM items WHERE {ARTIST_APPEARS_ON_WHERE}"),
            format!("SELECT id FROM items WHERE {ARTIST_SONGS_WHERE}"),
            format!("{}SELECT artist_id FROM named", artists_cte(ArtistScope::All)),
            format!("SELECT {CARD_COLUMNS} FROM items WHERE id = ?1"),
            HAS_NORMALIZATION_GAIN.to_string(),
        ];
        // Per-artist counts run once per listed artist: items are found by id, never by view.
        for filter in [
            ARTIST_ALBUMS_WHERE,
            ARTIST_APPEARS_ON_WHERE,
            ARTIST_SONGS_WHERE,
        ] {
            let lines = plan(&format!(
                "SELECT COUNT(*) FROM items WHERE {filter} AND +library_id = ?2"
            ));
            assert!(
                lines.iter().all(|l| !l.contains("idx_items_")),
                "{filter}: {lines:?}"
            );
        }
        for sql in &cases {
            let lines = plan(sql);
            assert!(
                !lines.iter().any(|l| l.starts_with("SCAN items")
                    || l.starts_with("SCAN i ")
                    || l == "SCAN i"
                    || l.starts_with("SCAN t")
                    || l.starts_with("SCAN ia")
                    || l.starts_with("SCAN item_artists")),
                "{sql}: {lines:?}"
            );
        }
    }
}
