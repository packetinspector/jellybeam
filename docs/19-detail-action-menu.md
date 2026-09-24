# 19 — Detail page action menu

Spec of record for the `···` menu on the Movie, Series (including the
season scope) and Episode detail pages. This file is the authoritative
record and the implementation contract: rules, Rust core surface, Kotlin
surface, focus behaviour, verification. The design went through an early
popover layout before settling on a **side panel**; frame numbers cited
below (15–19) refer to that design process and survive only as labels.

Decisions, where the design was left open:

| Question | Decision |
|---|---|
| Episode page "View Series" pill | **Removed.** The menu's Library › Go to series row is the one path. |
| Trailer pill (frames show it at position three) | **Not in this work.** Stays Planned in docs/13. The row is Play · `···`. |
| Advanced › Media information | **Dropped, group and all.** The spec capsule in the eyebrow already carries the file facts. The menu has three groups (frame 17 still draws an Advanced group; that decision stands). |
| Collections list source | **Session cache in the core.** One live BoxSet list per session, warmed on the first detail page open, re-read after ten minutes. |

---

## 1. Rules

1. **The door.** A round `···` pill at **position two**, immediately right of
   the primary Play/Resume pill, on every detail page type. Same 44dp height
   and Pistacchio fill as the primary; focus ring is `Sheen` on it (docs/12
   §0.4). It renders even when the page has no primary action (a series
   with no playable episode, a virtual movie): then it is the row's only
   pill and the page's focus fallback.
2. **Retired pills.** "Start from beginning" (Series), "Start over" (Movie,
   Episode) and "View Series" (Episode) leave the action rows. The menu owns
   all three.
3. **Never scrolls.** Three groups in fixed order — **This title · Playback
   · Library** — all open, each under its own heading band. A group with no
   rows renders nothing, never an empty heading. The worst case (series,
   admin, part-watched) is eight action rows under three headings and ends
   well above the footer; overflow would be a content decision, never a
   scroll.
4. **Absent, not disabled.** A row that cannot apply is not rendered. A
   toggle shows only its current inverse.
5. **Show state.** A row that has a state carries it as trailing Martian
   Mono uppercase subtext stating a fact (`3 EPISODES`, `E8`, `S2 E6`,
   `FROM 10`, `NEVER PLAYED`, `SERIES`, `ALREADY IN`). Never an instruction.
6. **Server names verbatim.** Series names and collection names are shown
   exactly as the server sends them.

### 1.1 Contents per page

| Group | Movie | Series | Season scope | Episode |
|---|---|---|---|---|
| This title | Mark as watched `NEVER PLAYED` *or* Mark as unwatched · Add to / Remove from favorites | Mark series as watched `N EPISODES` · Mark series as unwatched `N EPISODES` · favorite | Mark season as watched `N EPISODES` · Mark season as unwatched `N EPISODES` · favorite `SERIES` | Mark as watched `NEVER PLAYED` *or* unwatched · favorite |
| Playback | Play from the beginning | Play next unwatched `E8`/`S2 E6` · Play from the beginning · Play something random `FROM N` | Play next unwatched `E{n}` · Play something random `FROM N` | Play from the beginning |
| Library | Add to collection `▸` · Refresh metadata | Add to collection `▸` · Refresh metadata | Refresh metadata `SERIES` | Go to series · Add to collection `▸` · Refresh metadata |

`NEVER PLAYED` appears on the single-item Mark as watched row only when the
item has neither a position nor a played flag. In season scope the two rows
that still act on the whole series (favorite, refresh) say so with `SERIES`
in the state column.

Presence rules, all evaluated from data the page already holds (no new
fetch on open):

| Row | Present when |
|---|---|
| Mark as watched / unwatched (movie, episode) | always; one of the two by `card.played` |
| Mark series/season as watched `N` | `N` = non-virtual episodes in scope with `played == false`; present when `N > 0` |
| Mark series/season as unwatched `N` | `N` = non-virtual episodes in scope with `played == true`; present when `N > 0`. Both rows coexist on a part-watched scope. |
| Add to / Remove from favorites | always; by `isFavorite` of the movie/episode/series. In season scope the row reads and writes the **series** flag. |
| Play from the beginning | movie/episode: `positionTicks > 0`. Series: the primary action is a Resume (an in-progress episode exists); restarts that episode. Absent in season scope. |
| Play next unwatched | series: the primary action resolves to a Playable target (`DetailFormatting.resolvePrimaryAction`); the row plays that same target. Season scope: first in-progress else first unplayed non-virtual episode of the selected season. Subtext `E{n}` when the target is in the selected season, `S{s} E{n}` otherwise, nothing when either number is missing. |
| Play something random | scope has at least one non-virtual episode. `N` = that count (all episodes, watched or not). Picks uniformly, plays it with normal autoplay after. One episode, no queue. |
| Go to series | episode with a known `seriesId`; opens the series page (`DetailFormatting.seriesCardFrom`). Label `Go to series` with the series name as trailing subtext, verbatim, not re-cased. |
| Add to collection | movie, series, episode; the session collections list is non-empty. Absent in season scope and while the list is unknown or empty. |
| Refresh metadata | the signed-in user is an administrator (`isAdministrator()`, cached per session). |

Scope: on the Series page, "scope" is the whole series until the viewer
**selects** a season chip with Select; then it is that season. The chip
the page highlights on its own on a fresh entry (the resume season, docs/15
§2 rule 2) does not flip scope, or every series page would open in season
scope. Rows flip; group positions do not.

### 1.2 Where focus lands

Every group is open, so the only prediction is the **row** focus lands on:

| Page state | Focus opens on |
|---|---|
| Movie, never played | Mark as watched |
| Movie, has progress | Play from the beginning |
| Series / season scope, any episode played or in progress | Play next unwatched |
| Series, never played | Mark series as watched |
| Episode | Play from the beginning |

If the predicted row is absent on that page, focus lands on the first
action row.

### 1.3 Second levels

- **Add to collection** replaces the list in the same column, sliding
  across the panel width over 250ms. The kicker becomes `◂ ADD TO
  COLLECTION` (`◂` in HairlineStrong); the item name stays. The heading
  band reads `N COLLECTIONS`. One row per collection in the server's
  sort-name order, names verbatim. A collection the item is already in
  (mirror `collection_members`) shows `ALREADY IN` in the state column and
  is **not focusable**. When every collection reads `ALREADY IN` the level
  still opens (it is the answer to the question) and focus sits on an
  invisible anchor so the trap keeps a focused node: Up/Down do nothing,
  Back goes up, Left closes. The list **scrolls** inside the panel between
  the fixed header and footer, the focused row brought into view. Select
  adds, closes the panel, toasts `Added to {Name}`. **Back goes up one
  level, not out** — the only place Back does not close the panel; the
  footer reads `BACK FOR ACTIONS`.
- **Bulk marks confirm in place.** The selected row expands into a confirm
  block; every other row stays visible at 40% opacity. Block: inset 10dp,
  Notte fill, 1dp Pistacchio border, 7dp radius, 13dp padding. Question
  Archivo 700 13.5sp Panna `Mark N episodes as watched?` / `… as
  unwatched?`; detail Archivo 400 10sp Grigio `Every unwatched episode of
  {Name}.` (or the unwatched mirror); a pill pair at 11.5sp, `Mark
  watched` / `Mark unwatched` (Pistacchio fill, Notte ink) then `Cancel`
  (1dp HairlineStrong outline). `N` is the count the row showed. Back
  cancels; the footer reads `BACK TO CANCEL`. Single-item marks never
  confirm.

### 1.4 Feedback

Every action closes the panel and shows one in-app toast for 2.5s, bottom
left over the page: `✓` glyph, Archivo 14sp Panna, Surface fill, 1dp
Hairline border, 8dp radius. Texts: `Marked as watched` · `Marked as
unwatched` · `Added to favorites` · `Removed from favorites` · `Added to
{Name}` · `Refreshing metadata`. Go to series and the Playback rows
navigate instead and get no toast. A failed server call toasts
`Couldn't reach the server` and changes nothing locally.

Refresh metadata is fire-and-forget with fixed parameters; the mirror
reconciles when the server's `LibraryChanged` arrives.

### 1.5 The panel (v2, frames 15–19; dp on a 960×540dp screen)

- **Placement.** 330dp wide, pinned to the right edge, full 540dp height,
  corner radius 0. Ground `#100D0B` fully opaque; 0.5dp left hairline
  `#322A22`; shadow `-30dp 0 70dp rgba(7,6,5,0.9)` cast leftward. Padding
  42dp top, 28dp bottom, 28dp sides.
- **Entry.** The panel slides in from the right over 250ms (ease out) while
  the page's **content column reflows to 630dp** over the same 250ms — the
  library sort strip's push, rotated. The page is not dimmed, scaled or
  covered; the backdrop stays full-bleed under the opaque panel. On close
  the same 250ms runs in reverse and focus returns to the door.
- **Reflow rules** (nothing is drawn under
  the panel at any point of the transition):
  - The header text column keeps the page's 40dp margin against the panel
    edge, so text re-wraps 40dp short of it, never flush against it. The
    synopsis keeps its usual four-line clamp and MORE stop; it may gain a
    line.
  - Meta lines (series and episode header line, the season summary) drop
    trailing items whole, at the separator, never mid-word; only when the
    first item alone does not fit is it ellipsised (`ItemBoundaryLine`).
  - Card rows (episode strip, cast, similar) render only whole cards while
    the panel is open: the list is sliced before render from the first
    fully visible card, to as many cards as fit the current width from the
    40dp start margin (3 episode cards at 630dp). The start card is latched
    when the panel opens, so a mark from the panel cannot re-slice the row.
    On close the full list returns with the same first card.
  - The season chip row never wraps: label, then chips at their own width
    (scrolling only when the chips alone overflow), then the summary in the
    remaining width, dropping `N WATCHED` first. The scrolling container
    leaves 6dp inside its clip on each side for the focus ring.
  - The footer is pinned to the panel's bottom edge; the ground between
    the last row and the footer is by design (§7 of the v2 PRD).
- **Header.** `ACTIONS` kicker: Martian Mono 9sp Pistacchio uppercase;
  8dp; the item name: Archivo 700 15sp Panna, two lines max then
  ellipsis; 15dp below the block, then the first heading rule. In season
  scope the kicker reads `ACTIONS │ SEASON N` (`│` HairlineStrong, scope
  Grigio).
- **Heading band**, one per non-empty group: a 0.5dp `#322A22` rule
  spanning the full panel width (not inset), 11dp above the label, 4dp
  below; label Martian Mono 8sp `#6B6157` uppercase; 10dp extra top margin
  on every heading except the first.
- **Action row.** 29dp tall (7dp padding, 13sp label); label Archivo 400
  13sp Panna2; state subtext Martian Mono 8sp `#6B6157` uppercase,
  right-aligned; 28dp side padding; the drill-in marker is `▸` in
  HairlineStrong in the state column.
- **Focused row** is a pill, not a slab: radius 999, inset 10dp from both
  panel edges, Pistacchio fill, label Archivo 600 13sp Notte, subtext
  Martian Mono 8sp `#2E3A18`, 7dp vertical / 18dp horizontal padding. The
  pill is the only accent on the panel besides the `ACTIONS` kicker: no
  ring, no left bar, no label brightening.
- **Footer**, pinned above the 28dp bottom padding: a 0.5dp `#1D1814`
  rule, 12dp, then Martian Mono 8sp HairlineStrong: `BACK TO CLOSE  │  N
  ACTIONS` (`│` in `#1D1814`; `N` = focusable rows currently in the
  panel). Second levels swap the text (§1.3).
- **Keys.** Up/Down walk action rows only, headings skipped, no wrap.
  Select performs the action. **Back and Left both close** the panel and
  return focus to the door (except Back on a second level, §1.3). Right
  does nothing. While a bulk-mark confirm is up (§1.3) Left and Right walk
  its pill pair instead and never leave the panel; only Back or Cancel do.
- **The first level never scrolls.** The list sits at the top; the space
  below is the panel's ground. Never centre it vertically. Only the
  collections level (§1.3) scrolls.
- **Retained layers.** The panel's Back handler and focus seeding are
  gated on the screen being top: a detail page pushed from outside (the
  still-watching Stop route) leaves the page below composed with its panel
  open, and that panel must neither eat Back nor hold focus until the page
  is top again, when it re-seeds its row.

---

## 2. Rust core

### 2.1 `jellyfin-api` (`core/jellyfin-api/src/lib.rs`)

New client methods, all using the existing `auth_header`/`check_status`
shape of `report_playback`. `userId` is sent as a query parameter when the
client has one (same rule as `get_similar`).

| Method | Call | Returns |
|---|---|---|
| `mark_played(item_id)` | `POST /UserPlayedItems/{id}` | `UserItemDataDto` (the response body) |
| `mark_unplayed(item_id)` | `DELETE /UserPlayedItems/{id}` | `UserItemDataDto` |
| `set_favorite(item_id, favorite)` | `POST` / `DELETE /UserFavoriteItems/{id}` | `UserItemDataDto` |
| `list_collections()` | `GET /Items?IncludeItemTypes=BoxSet&Recursive=true&SortBy=SortName&SortOrder=Ascending&Fields=` (via `ItemQuery`, `limit` 200) | `Vec<BaseItemDto>` |
| `add_to_collection(collection_id, item_id)` | `POST /Collections/{id}/Items?ids={itemId}` | `()` (204) |
| `refresh_item(item_id)` | `POST /Items/{id}/Refresh?metadataRefreshMode=FullRefresh&imageRefreshMode=FullRefresh&replaceAllMetadata=false&replaceAllImages=false` | `()` (204) |
| `current_user()` | `GET /Users/Me` | `UserDto` |

Item ids are percent-encoded in paths like every other method. Mock-server
tests cover method, path, query and body for each.

### 2.2 `media-cache`

- `Mirror::apply_user_data(updates: Vec<(String, UserItemDataDto)>)` —
  public wrapper over the writer's existing command, which already patches
  `played`, `playback_position_ticks`, `is_favorite`, `unplayed_item_count`
  and emits `MirrorChange::Upserted`.
- `Mirror::refresh_items(ids: Vec<String>)` — public async: runs the
  existing `sync::fetch_and_upsert_ids` in batches of 100. Used for the
  authoritative re-read of parent rows after a mark.
- `Mirror::refresh_collection_membership(collection_id)` — public async
  wrapper over `sync::sync_one_boxset_membership`, which pages the
  collection's members at 500 a page until the server's total is met and
  replaces the membership table only once the last page landed (a failed
  page, or hitting the 100-page cap, leaves the old, complete table in
  place rather than a truncated one).
- `Mirror::collection_ids_containing(item_id) -> Vec<String>` — read-pool
  query on `collection_members`.

### 2.3 FFI (`core/ffi`)

`Card` gains `#[uniffi(default = false)] pub is_favorite: bool` as its
**last** field, from `CardRow::is_favorite`. New record
`CollectionInfo { id: String, name: String }`.

`State` gains `collections: Option<(Instant, Vec<CollectionInfo>)>` and
`is_admin: Option<bool>`, both cleared wherever `client` is replaced or
taken (`sign_out`, `switch_session`, `sign_in`, `restore_session`).

| Method | Behaviour |
|---|---|
| `set_played(item_id, played) -> Result<(), CoreError>` | Server call; apply the response DTO through `Mirror::apply_user_data` (instant and authoritative for that row). If the item is an Episode, spawn a background `refresh_items([season_id, series_id])` so the parents' unplayed counts follow. Returns after the server call. |
| `set_played_recursive(scope_id, played) -> Result<(), CoreError>` | Server call on the Series or Season id. Then, for every non-virtual episode in scope (`all_episodes_of_series` for a Series, `children_checked(scope_id)` for a Season), `apply_local_user_data(id, 0, Some(played))` — the writer resolves `Some(false)` to (0, unplayed). Then spawn a background barrier + `refresh_items(scope + its seasons + series)` for authoritative counts. Returns after the local applies are enqueued. |
| `set_favorite(item_id, favorite) -> Result<(), CoreError>` | Server call; apply the response DTO through `apply_user_data`. |
| `list_collections() -> Result<Vec<CollectionInfo>, CoreError>` | Returns the cache when younger than 10 minutes; otherwise fetches, stores, returns. Names verbatim. |
| `add_to_collection(collection_id, item_id) -> Result<(), CoreError>` | Server call, then a background `Mirror::refresh_collection_membership(collection_id)` (the existing non-recursive `/Items?ParentId=` fetch + `set_collection_members`) so `ALREADY IN` is true on the next open without waiting for `LibraryChanged`. |
| `collection_ids_containing(item_id) -> Vec<String>` | Mirror read: `SELECT collection_id FROM collection_members WHERE item_id = ?`. Empty when the mirror has no membership rows (no Collections library view synced). |
| `refresh_metadata(item_id) -> Result<(), CoreError>` | Server call, nothing else. |
| `is_administrator() -> Result<bool, CoreError>` | `current_user().policy.is_administrator`, cached per session. |

Every method: `NotSignedIn` without a client, `MirrorNotOpen` where the
mirror is needed, `Unauthorized`/`Api` from the client as today.

---

## 3. Kotlin

### 3.1 Gateway

`CoreGateway` gains `setPlayed`, `setPlayedRecursive`, `setFavorite`,
`listCollections`, `addToCollection`, `refreshMetadata`, `isAdministrator`,
each `withContext(Dispatchers.IO)` + `PerfLog.timed` like `getSimilar`.
`FakeCoreGateway` records every call and exposes configurable results,
including a `Throwable` to simulate a failed server call.

### 3.2 Model (`ui/detail/DetailMenuModel.kt`, pure, unit-tested)

```kotlin
enum class MenuGroup { THIS_TITLE, PLAYBACK, LIBRARY }
sealed interface MenuAction { MarkWatched, MarkUnwatched, MarkScopeWatched(count, isSeason), MarkScopeUnwatched(count, isSeason),
    AddFavorite, RemoveFavorite, PlayNextUnwatched(targetId, subtext), PlayFromBeginning(targetId),
    PlayRandom(count), GoToSeries(series: Card), AddToCollection, RefreshMetadata }
data class MenuRow(val action: MenuAction, val label: String, val subtext: String?)
data class MenuGroupModel(val group: MenuGroup, val rows: List<MenuRow>)
data class MenuModel(val groups: List<MenuGroupModel>, val focusOn: MenuAction?)
data class MenuInput(card, itemType, scopeSeason: Card?, scopeEpisodes: List<Card>, allEpisodes: List<Card>,
    primaryAction: PrimaryAction, isFavorite: Boolean, hasCollections: Boolean, isAdministrator: Boolean,
    seriesCard: Card?)
fun buildMenu(input: MenuInput): MenuModel
fun pickRandom(episodes: List<Card>, random: Random): Card?
```

Row labels and toast texts are literal text in the model and ViewModel
(the `PrimaryAction.label` precedent in this package); group headers, the
collections header and the confirm dialog use string resources.

### 3.3 ViewModel (`DetailViewModel`)

State additions: `isAdministrator: Boolean = false`, `collections:
List<CollectionInfo> = emptyList()`, `memberOfCollections: Set<String>`
(collection ids the item is already in, from
`collectionIdsContaining`), `menu: MenuUiState? = null` (`model`,
`level: FIRST | COLLECTIONS`), `confirm: BulkMarkConfirm?` (`played`,
`count`, `scopeId`, `scopeName`, `action`), `toast: String? = null`.

`init` adds three background loads: `isAdministrator()`,
`listCollections()` (both cached in the core after the first page) and
`collectionIdsContaining(itemId)` (a mirror read).

Actions: `openMenu()`, `closeMenu()`, `enterCollections()`,
`backFromCollections()`, `selectCollection(collection)`,
`runAction(action)`, `confirmBulkMark()`, `dismissConfirm()`,
`dismissToast()`. Marks and
favorites: call the gateway, then set the toast; the header follows the
mirror change event as it does after playback. Failures toast the error
text and leave state untouched. Playback actions start
`PlaybackActivity` and close the menu.

### 3.4 Screen (`DetailScreen.kt`, `ui/detail/DetailActionPanel.kt`)

- `rememberFocusMemory(card.id)` is hoisted to `DetailScreen` and passed
  to the three per-type screens so the panel and the door share one
  memory.
- The door is `MenuDoorPill` with focus key `action:menu`. On Select the
  screen calls `memory.captureInvoker()` then `viewModel.openMenu()`.
- `DetailScreen`'s root Box hosts the page, the panel and the toast. The
  page receives `contentEndInset: Dp` (0dp → 330dp, `tween(250,
  easing = FastOutSlowInEasing)`) and applies it as end padding on its
  **content column only**, never on the backdrop. The panel is a Box
  aligned end, `offset(x = 330dp → 0dp)` over the same tween, opaque, with
  its left hairline and leftward shadow.
- `DetailActionPanel` is a focus trap (`focusProperties { exit = Cancel }`
  + `focusGroup()`), keeps one `FocusRequester` per focusable row of the
  current level, seeds `MenuModel.focusOn` (else row 0) on open, row 0 of
  the collections level on entry, and remembers the last focused index so
  Back from the collections level and Cancel on the in-place confirm return
  to the row that opened them. `onPreviewKeyEvent`: Left closes, Right is
  consumed. `BackHandler`: confirm → cancel; collections → up one level;
  else close.
- On close (any cause) the screen restores focus by key `action:menu`
  through `memory.placeByKeys`, docs/15 §4.
- Strings: `detail_menu_*` in `strings.xml`.

---

## 4. Focus (docs/15 §7 row)

| Surface | Fallback | Restore on return | Nested return |
|---|---|---|---|
| Detail action panel | the predicted row (§1.2), else the first action row | n/a (never on the stack) | Back, Left, Select or an action returns focus to `action:menu`; the in-place confirm and the collections level return to the row that opened them |

---

## 5. On-device test cases

Run on a test TV (release build), driven over
adb with screenshots: 1 (as an episode: predicted row, reflow, footer
count), 3 (favorite and watched round trips on an episode, server state
read back unchanged afterwards), 5 (`ACTIONS │ SEASON 1`, season counts,
`SERIES` subtexts), 6 (`E6` with the season highlighted), 8 (Go to
series, Back to the door), 10 (Refresh metadata toast as admin), 11
(Back, Left, no wrap, Cancel returns to the mark row). Not run: 2 and 7
(they start playback), 4 (bulk mark on a real library), 9 and 12 (the
server has no collections; no unreachable-server setup). Three fixes came
out of the pass: a row that leaves the page drops the panel at once
(dead remote after Go to series), a fresh series page waits for its
seasons before seeding focus, and Left no longer opens the nav drawer
from inside the panel.

1. Movie, never played: `···` slides the panel in, the page column
   reflows to 630dp with no dim; focus lands on Mark as watched
   (`NEVER PLAYED`); no Playback heading; Library shows Add to collection
   (if any exist) and Refresh metadata (admin only); footer reads
   `5 ACTIONS`.
2. Movie mid-progress: focus lands on Play from the beginning; Select
   restarts at 0.
3. Mark as watched on a movie: toast, header shows the watched check,
   Continue Watching on Home no longer lists it.
4. Series part-watched: This title shows both series mark rows with counts
   that match the season strip's totals; confirm dialog restates the count;
   after Mark watched every episode card carries the check and the primary
   pill changes.
5. Season scope: rows read "season"; counts are the selected season's.
6. Play next unwatched subtext reads `E{n}` in the selected season and
   `S{s} E{n}` otherwise; Select plays that episode.
7. Play something random: an episode plays; the OSD's next-up works after.
8. Episode page: Go to series opens the series page; Back returns to the
   episode page with focus on the door.
9. Add to collection: the list shows server names verbatim under
   `N COLLECTIONS`; a collection the item is in reads `ALREADY IN` and is
   skipped by Up/Down; Back returns to the Add to collection row; Select
   toasts `Added to {Name}` and the row reads `ALREADY IN` next time.
10. Refresh metadata: row absent for a non-admin user; for an admin the
    toast shows and the server's activity log records the refresh.
11. Back and Left close the panel to the door; inside the collections
    level Back goes up one level; Right does nothing; Up at the first row
    and Down at the last row do nothing (no wrap).
12. Server unreachable: the action toasts the failure and nothing changes.
