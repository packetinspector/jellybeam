# 16 — Library sort and filter

Movies and TV Shows libraries get a sticky, per-library sort and filter
state, shown as a permanent summary line, edited in a strip that pushes the
grid down, and jumped through with an index rail on the right edge. This
file is the authoritative record and the implementation contract: data
model, mirror queries, FFI surface, UI behavior, focus rules, persistence,
verification.

Decisions, where the design was left open:

| Question | Decision |
|---|---|
| TV Shows extra chip | Has unwatched is a state of the Watched chip on TV; Continuing / Ended stays a separate chip. |
| Default sort | **Name A→Z** for both library types (unchanged from today). Reset returns here. |
| Genre in the mirror | Schema version bump; the mirror's drop-and-rebuild policy resyncs once per install. No in-place migration. |
| Scope | Collection types `movies` and `tvshows` only. Every other library keeps the plain grid. |

---

## 1. Data model (media-cache)

`SCHEMA_VERSION` 11 → 12, then 12 → 13 (server `SortName` requested, grid
indexes reshaped). Everything below is a rebuild, never a
migration.

### 1.1 `items` gains two columns

| Column | Source | Notes |
|---|---|---|
| `series_status TEXT` | `BaseItemDto.status` | `"Continuing"` / `"Ended"` on Series; NULL elsewhere. Already in the default DTO (verified live 10.11.10), no `Fields` request needed. |
| (none for genre) | | Genres are a many-to-many, see 1.2. |
| `sort_name TEXT` (existing) | `BaseItemDto.sort_name`, falling back to `name` | `"SortName"` is `Fields`-gated: previously the mirror never received it and stored the display name. Now requested in `sync::item_fields()`, so the column carries the server's sort key (lower-cased, leading articles removed, digit runs zero-padded, user overrides honoured). Never shown; display uses `name`. |

### 1.2 New table `item_genres`

```sql
CREATE TABLE IF NOT EXISTS item_genres (
    item_id TEXT NOT NULL,
    genre   TEXT NOT NULL,
    PRIMARY KEY (item_id, genre)
) WITHOUT ROWID;
CREATE INDEX IF NOT EXISTS idx_item_genres_genre ON item_genres(genre, item_id);
```

- Source: `BaseItemDto.genres` (`Vec<String>`), server strings verbatim
  (never prettified). `"Genres"` is added to `sync::item_fields()`
  so every persisting fetch carries them.
- Write rule (writer, inside the item's upsert transaction): delete this
  item's rows, then insert the DTO's set — an **empty** `genres` clears the
  set. This is safe because every persisting fetch (resume, next up,
  breadth, delta, by-ids refetch, BoxSet membership) requests `Genres`
  through `item_fields()`; the id-sweep's bare pages are never written and
  the Detail enrichment graft never touches this table. (The first cut
  preserved rows on an empty list, which kept a genre the server had
  removed forever.) Deleting an item deletes its genre rows.
- The Detail page's enrichment graft (`apply_enrichment`) does not touch
  this table.

### 1.3 `views` exposes `collection_type`

`ViewSummary` gains `collection_type: String` (already a column). FFI
`ViewSnapshot` gains `collection_type: Option<String>` with a uniffi default
of `None`, so every existing Kotlin constructor call keeps compiling.

---

## 2. Mirror queries (media-cache `query.rs`, public on `Mirror`)

The grid population is every row with `parent_id = <view id>` (the breadth
sync stamps top-level items with the view id). Nothing else changes about
which rows a library shows.

### 2.1 Types

```rust
pub enum GridSortField { Name, DateAdded, Year, Runtime }
pub struct GridSort { pub field: GridSortField, pub descending: bool }

pub enum WatchedFilter { Any, Unwatched, HasUnwatched, Watched }
pub enum StatusFilter  { Any, Continuing, Ended }
pub enum Decade { D2020s, D2010s, D2000s, D1990s, D1980s, Older }
pub struct GridFilters {
    pub watched: WatchedFilter,
    pub genre: Option<String>,
    pub decade: Option<Decade>,
    pub status: StatusFilter,
}
pub struct GridCounts { pub filtered: u64, pub total: u64 }
pub struct GridGroup  { pub key: String, pub count: u64 }
```

### 2.2 `library_grid(view_id, sort, filters, offset, limit) -> Vec<CardRow>`

ORDER BY is `{key} {dir} NULLS LAST, sort_name COLLATE NOCASE {dir}, id
{dir}` — every term follows the direction (Jellyfin web applies one
`SortOrder` to every sort field the same way), which is what lets a
descending sort be a backward scan of the same index (§2.6). No `CASE`
terms: the key expression is written exactly as the index expression.
Name drops the redundant `sort_name` repeat (`sort_name COLLATE NOCASE
{dir} NULLS LAST, id {dir}`): SQLite treats the duplicated term as a
"right part of ORDER BY" it cannot serve from the index.

| Field | Natural direction | Key | NULL handling |
|---|---|---|---|
| Name | ascending | `sort_name COLLATE NOCASE` | none in practice (`sort_name` falls back to `name`) |
| DateAdded | descending | `date_created` | NULL last in both directions |
| Year | descending | `production_year` | NULL last in both directions |
| Runtime | descending | `NULLIF(runtime_ticks, 0)` | NULL **or 0** last in both directions (a Series row often carries 0) |

WHERE clauses, ANDed:

| Filter | SQL |
|---|---|
| `watched = Unwatched` | The row is displayed never-started and not `played` (`not_started_sql!`), and no Episode of it (`e.series_id = items.id`) is started or played — docs/07 §1's start grace, so a position under 2 min counts as never started. Movies have no episode rows, so the subquery is vacuously true for them. |
| `watched = HasUnwatched` | `COALESCE(unplayed_item_count, 0) > 0` — "not finished"; distinct from `Unwatched`, which is "not started". |
| `watched = Watched` | `played = 1` or the row's position is inside docs/07 §1's end grace (`watched_sql!`) — what its card shows; a Series is `Played` only when every episode is. |
| `genre = Some(g)` | `EXISTS (SELECT 1 FROM item_genres g WHERE g.item_id = items.id AND g.genre = ?)` |
| `decade` | `production_year BETWEEN 2020 AND 2029` etc.; `Older` = `production_year < 1980`. A NULL year never matches a decade. |
| `status` | `series_status = 'Continuing'` / `'Ended'` |

`is_virtual` is not filtered here (library-level rows are Movies/Series).

### 2.3 `library_grid_counts(view_id, filters) -> GridCounts`

`filtered` = rows matching 2.2's WHERE; `total` = rows with no filter.

### 2.4 `library_grid_groups(view_id, sort, filters) -> Vec<GridGroup>`

An ordered scan of the per-field key expression over the same filtered
set, under exactly 2.2's ORDER BY, run-length encoded in Rust into
`(key, count)` per **contiguous run**, so Kotlin can accumulate offsets by
summing counts in order. (The first cut was a `GROUP BY`, which merged a
key's non-contiguous runs into one row and shifted every later offset by
the trailing run's size.) Keys:

| Field | Key expression | Examples |
|---|---|---|
| Name | `CASE WHEN upper(substr(sort_name,1,1)) BETWEEN 'A' AND 'Z' THEN upper(substr(sort_name,1,1)) ELSE '#' END` | `A` … `Z`, `#` |
| DateAdded | `substr(date_created, 1, 7)` | `2026-09`; NULL → `""` |
| Year | `CAST((production_year / 10) * 10 AS TEXT)` | `2010`; NULL → `""` |
| Runtime | minutes band start: `<30 → "0"`, `30–59 → "30"`, `60–89 → "60"`, `90–119 → "90"`, `120–149 → "120"`, `150–179 → "150"`, `≥180 → "180"` | NULL/0 → `""` |

Run order follows the sort; the empty key lands where its NULLs land
(last). A key appears more than once only for `#` under Name: digit- and
symbol-led sort names run before `A`, non-ASCII-led ones after `Z`
(`NOCASE` folds ASCII only). Kotlin's `IndexRailModel` takes the first
run's offset and sums the counts, so `A`'s offset is exact and a poster in
the trailing run lights the last letter.

### 2.5 `library_genres(view_id) -> Vec<String>`

Distinct `item_genres.genre` over the view's grid rows, ordered
`COLLATE NOCASE`. Empty when nothing is synced yet.

### 2.6 Indexes

One index per sort, each carrying the full ORDER BY so both directions
are a plain index walk (forward or backward) with `LIMIT`/`OFFSET` and no
sort step:

| Sort | Index |
|---|---|
| Name | `idx_items_grid_name (parent_id, sort_name COLLATE NOCASE, id)` |
| DateAdded | `idx_items_grid_added (parent_id, date_created, sort_name COLLATE NOCASE, id)` |
| Year | `idx_items_grid_year (parent_id, production_year, sort_name COLLATE NOCASE, id)` |
| Runtime | `idx_items_grid_runtime (parent_id, NULLIF(runtime_ticks, 0), sort_name COLLATE NOCASE, id)` — expression index, matched by text |

`idx_items_browse (parent_id, item_type, sort_name COLLATE NOCASE)` stays for
`children()`, whose Name order is case-insensitive like the grid's (schema v15). Filters are predicates evaluated during the index walk; no
filter gets its own index. Measured on a synthetic 10k-title library: the
first cut's `(parent_id, key)` indexes narrowed by `parent_id` and then
sorted the whole library per page (`USE TEMP B-TREE FOR ORDER BY`,
2–8 ms/page on the dev host, more on the TV); these serve a page in
0.1–0.3 ms. `EXPLAIN QUERY PLAN` tests assert, per sort, per direction,
and with each filter kind active, no `SCAN items` and no `TEMP B-TREE`;
the groups scan additionally uses the index as covering.

---

### 2.7 Favorites scope

The drawer's Favorites page reuses this grid. `FAVORITES_VIEW_ID` (`"favorites"`, never a
32-hex server id) passed as `view_id` swaps the `parent_id = ?` population for every favorite
of a shown type (Movie, Series, Season, Episode, BoxSet; people, music and playlists are left
out) across libraries, off the partial index `idx_items_favorite`, with no pinned sort index:
the set is small. The Home shelf's "last played" order reads a favorite show's or season's
episodes through `idx_items_series_played` / `idx_items_parent_played` (partial, played rows
only, covering), one seek per favorite rather than one row read per episode. The three favorites
indexes live in `FAVORITE_INDEX_SQL`, not the launch schema: a fresh mirror creates them at open
(free on an empty table), and a populated one builds them through the writer once the startup
pass has fetched what Home needs. Until then the Home shelf and the drawer probe answer empty
rather than scan the table; the build ends with a `Refresh` that brings both in. Counts, groups and genres follow the same scope. The core's
`favorites_view(name)` builds the page's `ViewSnapshot` with collection type `"favorites"`,
which is how Kotlin recognises it.

`GridFilters.item_type` is an exact item-type match. Only the Favorites strip sets it, through
a **Type** chip whose panel lists `favorite_item_types()` (the types present, fixed order) plus
Any; the summary line counts `FAVORITES` and adds the type's plural (`SHOWS`, `EPISODES`, ...).

Favorites are per user on the server. The mirror's `is_favorite` flag follows the app's own
toggle, live `UserDataChanged` events, and `sync_favorites`: an id-only `isFavorite=true` walk
that runs last in the warm startup pass, after each reconnect and on the reconcile timer, and
makes the flag match the server list (any failed or incomplete page changes nothing, and it
leaves alone any row written in or after the millisecond the fetch began, so a stale snapshot never undoes a newer
toggle, server event or item fetch). That guard reads each row's general write time, so any
write to an item during the sync -- a playback position, a metadata refresh -- also shields
that item's favorite flag from the snapshot. The window spans the whole paged fetch plus the
wait for the writer to apply it. A favorite changed elsewhere on such an item stays stale until
a later favorites sync completes successfully (next launch, reconnect, or reconcile tick). It never
runs ahead of anything Home's first frame needs.

## 3. FFI surface (`core/ffi`)

Records/enums mirror §2.1 one-to-one as `uniffi::Record`/`uniffi::Enum`
(`GridSortField`, `GridSort`, `WatchedFilter`, `StatusFilter`, `Decade`,
`GridFilters`, `GridCounts`, `GridGroup`, plus
`LibraryGridPrefs { sort: GridSort, filters: GridFilters }`).

`JellybeamCore` methods (all fail open like `children`: empty/zero when the
mirror is not open):

| Method | Backs |
|---|---|
| `library_grid(view_id, sort, filters, offset, limit) -> Vec<Card>` | §2.2 |
| `library_grid_counts(view_id, filters) -> GridCounts` | §2.3 |
| `library_grid_groups(view_id, sort, filters) -> Vec<GridGroup>` | §2.4 |
| `library_genres(view_id) -> Vec<String>` | §2.5 |
| `get_library_grid_prefs(view_id) -> LibraryGridPrefs` | §5 |
| `set_library_grid_prefs(view_id, prefs)` | §5 |

Defaults: `GridSort { Name, descending: false }`, `GridFilters { Any,
None, None, Any }`.

---

## 4. UI (`app/.../ui/library`)

Applies to `ViewKind.LIBRARY` views whose `collectionType` is `movies` or
`tvshows` (`ViewSnapshot.supportsSortFilter`). Every `LIBRARY` view now
loads through `libraryGrid` (defaults equal today's `children(NameAsc)`
result); only the two supported types show the summary line, strip, and
rail.

### 4.1 Summary line

Under the title, one Martian Mono 13sp line, always present:

```
342 MOVIES │ NAME ↑
23 OF 342 │ DATE ADDED ↓ │ UNWATCHED │ ACTION │ 2010s
```

- Count in `Panna2`; sort and filter segments in `Panna`; separators `│`
  in `Grigio` with 12dp padding.
- Unfiltered: `<total> MOVIES` / `<total> SHOWS`. Filtered: `<filtered> OF
  <total>`.
- Sort labels: `NAME`, `DATE ADDED`, `YEAR`, `RUNTIME`; arrow `↑`
  ascending, `↓` descending.
- Filter segments, in strip order, only when active: `UNWATCHED` /
  `HAS UNWATCHED` / `WATCHED` (`HAS UNWATCHED` comes from that Watched-chip
  state, TV Shows only); the genre upper-cased verbatim; `2010s` / `OLDER`;
  `CONTINUING` / `ENDED`.

Pure formatter: `GridSummaryFormat.line(...)` (unit-tested).

### 4.2 Strip

Up from the top row of posters opens it; it pushes the grid down (animated,
250ms). Band: `SurfacePanel` fill, 1dp `Hairline` top and bottom, two rows:

| Row | Leading label (Martian Mono 12sp `Grigio`, 132dp column) | Chips |
|---|---|---|
| SORT | `SORT` | Name · Date added · Year · Runtime |
| FILTER | `FILTER` | Movies: Watched · Genre · Years · Reset. TV: Watched · Genre · Years · Status · Reset |

Chip (`StripChip`): 36dp tall, fully rounded, Archivo SemiBold 16sp, 16dp
horizontal padding, 8dp gap. Active = `Pistacchio` fill, `Notte` text.
Inactive = no fill, no border, `Panna2` text. Focus ring: `focusRing`,
`Sheen` on an active chip, `Pistacchio` otherwise. Reset is never active.

Chip labels and Select behavior:

| Chip | Inactive label | Select |
|---|---|---|
| Sort field | field name | inactive → becomes active in its natural direction and shows `↑`/`↓`; active → flips direction |
| Watched (Movies) | `Watched: any` | cycles Any → `Unwatched` → `Watched` → Any |
| Watched (TV Shows) | `Watched: any` | cycles Any → `Unwatched` → `Has unwatched` → `Watched` → Any |
| Genre | `Genre` / active shows the genre name | opens the Genre panel |
| Years | `Years` / active shows `2010s` etc. | opens the Years panel |
| Status | `Status: any` | cycles Any → `Continuing` → `Ended` → Any |
| Reset | `Reset` | sort → Name ↑, every filter → default |

Results change on Select. Focus moving inside the strip never re-queries.

Keys inside the strip: Left/Right move within the row and **stop at both
ends** (consumed, so Left at the first chip cannot open the nav drawer).
Up from SORT is consumed (no-op). Down from SORT → FILTER; Down from
FILTER → back to the poster the strip was opened from. Back closes the
strip and restores that poster. Opening focuses the **active sort chip**.

### 4.3 Panels

Selecting Genre or Years opens a panel directly beneath the band (the
grid moves further down): a wrapping row of `StripChip`s, `Any` first, then
the library's genres (from `libraryGenres`, verbatim, server order
alphabetised NOCASE) or the decades `2020s · 2010s · 2000s · 90s · 80s ·
Older`. Focus lands on the current value. Select commits, closes the panel,
re-queries, and returns focus to the opener chip; Back closes unchanged.

The panel row wraps: a library with many genres lays its chips out over
several lines. Down from a chip that has a line below it moves to that
line (plain focus traversal); only Down from the panel's **last** line
closes the strip, the same close that Down from FILTER runs when no panel
is open. The strip decides "is there a line below" from each panel chip's
measured top within the wrapping row, not from chip counts, so it stays
right for any genre list and any label widths.

### 4.4 Index rail

A 64dp column at the right edge, top-aligned with the poster grid (padded
by the measured header height, so opening the strip never squeezes it),
never beside the title block. The grid's usable width shrinks by the rail width
(cell width recomputed; column count stays 8).

Labels (Martian Mono 12sp, single line), by active sort, in sort order (reversed when
the direction is flipped):

| Sort | Labels | Group key |
|---|---|---|
| Name | `#`, `A` … `Z` | §2.4 Name key |
| Date added | `NOW` (current month), then the earlier months of the current year `AUG` … `JAN`, then the previous five years `'25` … `'21`, then `OLD` | `YYYY-MM` keys folded into the label buckets |
| Year | `2020s` … `1980s`, `OLD` (< 1980), reduced to the decades between the newest and oldest present, plus `OLD` | decade key |
| Runtime | `<30m`, `30m`, `1h`, `1h30`, `2h`, `2h30`, `3h+` | band key |

Pure model: `IndexRailModel.build(sort, groups, today)` → list of
`RailEntry(label, offset, count)` (unit-tested). Entries with `count == 0`
render disabled (`HairlineStrong`) and are skipped by Up/Down.

Unfocused: the entry containing the focused poster (first visible item
when nothing in the grid has focus) is `Panna`; others `Grigio`. Focused:
the focused entry is `Pistacchio` inside a fixed 48×22dp pill border drawn
within the entry's own bounds (an outset ring would overlap the
neighbours above and below). Holding Up/Down retargets the jump on every
repeat event: one load runs at a time, it re-reads for the newest entry when
it finishes, and only the newest entry scrolls the grid.

Keys: Right from the last column of any grid row focuses the rail at the
entry containing the focused poster. Up/Down move to the next non-empty
entry and the grid **jumps live** to that entry's first item (loading the
prefix if needed, §4.6). Left or Select moves focus into the grid on the
current entry's first item. Back returns focus to the poster the rail was
entered from (scrolling back to it).

### 4.5 Empty result

The strip stays open; the grid area shows one line, Archivo 16sp `Grigio`:
`No titles match`. Chips are never pre-dimmed.

### 4.6 Paging

`LibraryViewModel` keeps its growing-prefix model (200 per page). A rail
jump to offset `n` first ensures the loaded rows cover `n + PAGE_SIZE`,
reading only the missing tail and appending it by id, then scrolls to
`n`'s row start. A mirror change between those reads can duplicate (dropped)
or skip a row; the change event's refresh re-reads the whole prefix and
heals it. Counts and groups are re-queried with every
sort/filter change and on mirror change events, alongside the items.

Mirror change events reach this re-query through
`tv.jellybeam.ui.common.ChangeRefreshScheduler` (shared with Home and Detail;
see also docs/17 §6): a leading refresh on
the first relevant event, then bounded periodic sampling — 500ms while this
screen is top of the back stack and the Activity is resumed, 3s while it is
retained-but-hidden — with a guaranteed trailing refresh and an immediate
catch-up the moment the screen becomes top again with a pending change.
This replaced a resetting `debounce(500)` that never fired while the mirror
kept delivering changes roughly every 250ms during a sync burst (Rust's own
`recv_changes` window), leaving the grid frozen on stale data for the whole
burst.

Three rules keep the loaders honest:

- **Generation.** Every sort/filter intent bumps a generation counter
  together with its state update. Each loader (initial/mirror refresh,
  page append, rail prefix) captures the generation before its first
  suspension and discards its result if the generation moved while it
  was in the gateway — a refresh started under Name/Any can never land
  on top of Year/Watched, and a page from the old ordering is never
  appended to the new grid.
- **One load at a time.** Page appends and rail prefix loads share one
  mutex — the mirror-refresh loader takes the same mutex for its items
  read and publish (counts, groups and genres follow outside it, so they
  never hold up a page or a jump), so an overlapping refresh and page load, or two
  overlapping refreshes, always serialize instead of racing to publish;
  the later one, reading newer data, lands last. A rail jump that arrives
  while a page is loading waits for it, re-checks coverage, loads if
  still needed and reports whether the target offset is now loaded; the
  rail only moves focus into the grid on success (falling back to the
  last loaded card), so Left/Select can never consume the key and leave
  focus stranded on the rail.
- **Not fixed by the mutex above:** items/counts/groups/genres are still
  separate, non-transactional mirror reads within one refresh call, so a
  commit landing between them can still skew that single snapshot (a
  count that doesn't quite match the row count, say). Deferred — it
  self-heals on the next change event, and fixing it for real needs either
  one coarse transactional Rust snapshot or revision-aware keyset paging,
  neither implemented here.

**A query failure keeps the last good grid; it never blanks it.**
`JellybeamCore::
library_grid`/`library_grid_counts`/`library_grid_groups` return `None`
for exactly two cases the FFI layer can't and doesn't try to tell apart —
the mirror isn't open, or the underlying query failed — and `Some(vec![])`
/`Some(GridCounts{0,0})`/`Some(vec![])` for a *genuine* empty result;
`media-cache`'s own `library_grid_checked`/`library_grid_counts_checked`/
`library_grid_groups_checked` make the same split one layer down (`Ok`
carries the real result, including an empty one; `Err` is a query
failure). `CoreGateway.libraryGrid`/`libraryGridCounts`/`libraryGridGroups`
surface this as Kotlin `null` — never a defaulted/zeroed value. In
`LibraryViewModel`:

- `refresh()`/`requery()`: a `null` items/counts/groups result keeps
  whatever was already in `LibraryUiState` for that field (including
  `hasMore`, derived from `items`) and only clears the loading flags —
  coalesced independently per field, since a partial failure (items ok,
  counts failed) must not blank the ones that DID come back. A non-`null`
  items result, empty or not, always replaces the prior page.
- `loadNextPage()`/`ensureLoadedThrough()`: a `null` page leaves `items`
  and `hasMore` untouched — deliberately NOT `hasMore = false` — so a
  later scroll/rail jump retries instead of being permanently stranded
  short of the end of the grid. This is distinct from a page that
  genuinely ran out (`page.size < PAGE_SIZE`), which still sets
  `hasMore = false` exactly as before.

---

## 5. Persistence

Per library, forever: `<data_dir>/library_grid_prefs.json`, a map of view
id → `LibraryGridPrefs`, written on every change through
`set_library_grid_prefs`, as a temp file renamed into place (a process
death mid-write cannot leave malformed JSON that would reset every
library); serde defaults on every field so a file from an older build
still loads. Loaded once in `LibraryViewModel` init before the first
query. Unknown view ids read as defaults. Strip open/closed and panel
state are screen state, not persisted.

Ordering is by revision, not by lock: `set_library_grid_prefs` only holds
the core state lock long enough to insert into the map, bump a
`library_grid_prefs_revision` counter, and clone the map out -- it does
*not* hold that lock across the file write, because that lock also guards
every other read of core state, including `image_url`'s lookup during
Compose composition on the main thread. Holding it across a
write-and-rename would let a newly composed card block behind an
unrelated library screen's chip-select save. Instead, the cloned
snapshot and its revision go to `persist_library_grid_prefs`, which keeps
its own last-saved-revision counter (behind a separate lock, never held
together with the state lock) and only writes a snapshot whose revision
is strictly newer than the last one it actually saved. Two library
screens writing from their own IO threads can therefore still never drop
each other's entry -- whichever save reaches disk with the higher
revision always wins, regardless of which caller's write happens to
finish first -- while `image_url`/`get_library_grid_prefs` never wait on
the disk write at all, since they only ever read the in-memory map.

---

## 6. Focus (docs/15 §7 deviation entry)

- Grid cells keep `card:<id>` keys and the existing `FocusRestorer`.
- Strip chips and the rail are **not** registered in the focus memory
  (no `focusKey`): they are reached and left by explicit `FocusRequester`s,
  so `lastKey` only ever names a poster. Neither is a becoming-top restore
  target: returning to the library always restores a poster, with the
  strip closed.
- The strip and rail hold an **origin poster** (its `card:<id>` key) while
  open; Back returns there.

---

## 7. Verification

- Rust: `cargo test -p media-cache -p jellybeam-ffi` — sort order per field and
  direction, NULL/0 placement, every filter clause, group keys and order,
  counts, genres, `EXPLAIN QUERY PLAN` per sort and direction (no scan,
  no temp b-tree), contiguous-run groups including the two-run `#` case,
  genre clear-on-empty, `SortName` and `Genres` in `item_fields()`, schema
  version 13, prefs round trip, old-file tolerance and atomic save.
- Kotlin JVM: `GridSummaryFormatTest`, `IndexRailModelTest`,
  `LibraryViewModelTest` (prefs load before first query, Select re-queries,
  focus moves do not, Reset, rail jump loads the prefix, counts/groups
  refresh on change events, stale refresh and stale page discarded after an
  intent, rail prefix load waits for an in-flight page), and
  `ChangeRefreshSchedulerTest` (leading refresh on the first event, bounded
  periodic sampling during a sustained burst rather than one refresh at the
  end, hidden work bounded to about one refresh per hidden period, and an
  immediate catch-up refresh on becoming active with a pending change).
- On device (Movies and TV Shows): summary at rest, Up opens the strip on
  the active sort chip, each sort and direction, each chip, Genre and
  Years panels, Reset, empty result, rail jump under each sort, Back paths,
  strip closed on return from Detail, prefs survive a process restart, and
  a fresh install's one-time mirror rebuild.
