# 07 — Home & browse behavior spec

This is the behavioral contract for Home and library browsing on Compose for TV.
dp≈px below.

## 1. Home screen composition

The ViewModel owns the initial snapshot load. Composition and the first host-resume
event do not request duplicate loads; later host resumes, returns to Home, and
mirror changes still refresh, including changes received during the initial load.

Shelf order (`home.rs:118-159`); `size` is the Shelf size setting (10/20/30, default 20):
1. Hero banner — only when shelf 0 is Continue Watching (`hero_candidate`, home.rs:427-433); no hero otherwise, and the shelves then start below the floating masthead (`contentTopInset`).
2. "Continue Watching" — `mirror.resume(size)`, hidden if empty.
3. "Next Up" — `mirror.next_up(size + resume.len())`, de-duplicated against Continue Watching ids (`dedup_against`, home.rs:827-836); hidden if empty after dedup.
4. "Latest in {library name}" — one shelf per view in server order, `latest(id, size, hide_watched)`; hidden if empty; hidden libraries skipped before querying.

Shelves beyond the first two mount progressively, one per frame, once initial focus has landed, rather than composing every shelf's cards in the first content frame. Any D-pad move or focus-memory restore that reaches an unmounted shelf mounts the rest immediately before the move completes.

Hero: item = shelves[0].items[0]. Full-bleed backdrop (own tag, else ancestor), 42% viewport height, min 320px. Eyebrow = series name (episodes only); title = item name; metadata = `"S{s} E{e} · {runtime} · {year}"` (episode) / `"{year}"` (movie). Resume + "More Info" buttons.

Card sizes:
- Resume shelves (Continue Watching, Next Up): mixed-aspect single row, row height solved so 5.5 cards fit (`RESUME_CARDS_ACROSS = 5.5` — 5 visible + bleed). Episodes 16:9, movies 2:3 at the same height. Width clamp [180, 320]px.
- Latest shelves: uniform 2:3 poster, `CELL_WIDTH = 160`px.

Poster-slot image requests are bucketed to the nearest of a small fixed set of server widths (`CardFormatting.bucketedImageWidth`) rather than each surface's exact drawn size, so Home's POSTER shelf, the library grid, and Detail's hero-poster placeholder key all share one 240-wide rendition instead of fragmenting the server's resize cache.

Card text:
- Poster card: 2 fixed lines — title 14px/Medium 1-line ellipsis; metadata 12px (year). Title `TEXT_PRIMARY` focused / `TEXT_SECONDARY` unfocused; metadata `TEXT_SECONDARY`/`TEXT_TERTIARY`. Lines reserve height even when empty (cards bottom-align).
- Resume card: 3 fixed lines (52px total: 20+16+16) — title / series name (episodes, 60% opacity) / `"S{s} E{e} · {remaining} left"` (drop missing halves, never "S? E?").

Progress bar: 3px, inside the art's bottom edge. `fraction = position_ticks/runtime_ticks` clamped 0..1; shown only if runtime present AND position > 0 (`watch_progress`, cards.rs:484-488). Fill = accent; track = accent @30%.

Badges: unplayed-count only for Series/Season/BoxSet, accent pill, count > 0. Watched Movie/Episode (played, not mid-progress) gets a checkmark badge (~20px, top-right, black scrim + white check) — never both. No "unwatched" mark.

Every card-derived value above (art source, progress bar, badge, timing label, and text) is recomputed from the card's own current field values on each recomposition rather than cached from a card's first render — a lazy-list item key is the item id, which stays stable across a mirror refresh even though the `Card` instance behind it is replaced. A resume position, watched flip, or metadata/tag refresh landing after the first render is reflected immediately, not stuck on the value the card first rendered with.

## 2. Card details

- Episode title format: `"E{n} · {name}"` (cards.rs:1154 — NOT "{n}. {name}"). Bare name if no index_number.
- Series-name line only on resume-shelf episode cards.
- Watched state = checkmark badge only; focus = ring only; never combined, no watched-dimming.
- Virtual episodes (`is_virtual`): art at 40% opacity; status line `"Airs {abbrev date}"` if premiere_date parseable and future, else `"Missing"`; no runtime; no play affordance (navigation still allowed).
- Poster (2:3) art fallback (`poster_art_source`, cards.rs:211-224): own primary_tag ONLY if item_type != Episode → (series_id, series_primary_tag) → placeholder.
- 16:9 art fallback (`rail_art_source`, cards.rs:277-288): own primary_tag → (parent_backdrop_item_id, parent_backdrop_tag) → placeholder.
- Missing-artwork placeholder: static SURFACE_PANEL box with the item NAME centered in small tertiary text (never initials/blank). Pre-texture loading placeholder: flat SURFACE_RAISED + skeleton pulse (0.85↔1.0 opacity, 2s), no text. The pulse stops once failed image requests exhaust their retries and resumes if a later foreground return triggers another load.
- An empty Home keeps a focusable explanatory message so Left can open the drawer before the first titles arrive, after sync failure, or when every shelf is hidden. The normal focus-memory path restores this target on drawer close and transfers to content when it arrives.
- Blurhash: interim only — texture (150ms cross-fade) → blurhash → pulsing flat. The interim chain stays hidden for the first 250 ms of a load, so art that arrives from cache fades straight in over the background instead of flashing flat → blurhash → art.
- Failed load: Coil never retries a request that ended in error, so a card would keep its blurhash for as long as the screen lives; `CardArtImage` retries with backoff (2 s, 4 s, 8 s, three attempts) and once more, with a fresh attempt budget, when the app returns to the foreground. The blurhash stays up meanwhile; nothing is re-requested for a card that succeeded.

## 3. Library grid

- Default sort NameAsc (root.rs:2763); menu options: DateCreatedDesc ("Date Added"), PremiereDateDesc ("Premiere"). Season→episodes always IndexNumber sort.
  Movies and TV Shows libraries have a sticky per-library sort/filter state with a summary line, an Up-to-open strip, and a right-edge index rail — see `docs/16-library-sort-filter.md`. Other library types keep the plain NameAsc grid.
- 2:3 posters. Library flex sizing (`grid.rs:60-109`): target 7 columns at 1500px window (~175px pitch), cell clamp [120, 220]px, cells stretch to fill the row.
- NO query pagination: `children_checked(view_id, sort, 0, u32::MAX)` in one snapshot (OFFSET paging across readers can dup/omit rows during sync). Virtualize at render by row; cancel image fetches >2 rows behind the viewport top on scroll.
- Season→episode: a GRID of fixed 160px-wide 16:9 episode cards (`columns_for_width`), each: 1-line `"E{n} · {name}"`, 1-line runtime/virtual status, 2-line clamped overview (fixed 68px text block). Seasons = horizontal tab strip above (name only, accent underline on selection).

### 3.1 Plugin channel views: folder listing

A `ViewKind.CHANNEL` view (a Jellyfin plugin channel, e.g. a DVR
recordings plugin) and each `ChannelFolderItem` folder inside it
(`ViewKind.CHANNEL_FOLDER`) render as a full-width row list, not the
poster grid: the items have no art or runtime and mostly share a name,
so only a date column tells them apart. Rows are 64dp, 4dp apart, inside
the same page margin and under the same 28sp title as the grid:

- leading 132dp column, Martian Mono: folders show `FOLDER`; recordings
  show the local date (`Sat, Aug 30`, or `Aug 30, 2025` outside the
  current year) over the local time (`8:00 AM`, or `08:00` when the
  device uses 24-hour time);
- name in Archivo SemiBold 20sp `Panna`, one line; recordings add the
  server overview as a one-line 14sp `Grigio` second line when present;
- trailing 48dp: `✓` (`Pistacchio`) when played, `RESUME` when partly
  played, `›` on folders.

Order comes from the server: folders by name, recordings newest first.
Focus is the standard ring on the row (docs/15 §1.2, no scale, no sibling
dim) with a `Surface` fill behind the focused row; focus memory and the
becoming-top restore are the grid's (`card:<id>` keys). Page Up/Down and
the Channel rocker are not mapped here (library grids only). Opening a
folder pushes another list; opening a recording opens Detail, whose
Play/Resume plays it.

## 4. Focus & interaction (D-pad)

- Internal focus position exists (shelf 0, col 0) as soon as a screen mounts, and the
  ring shows from that moment, *before* the first D-pad press. A TV remote has no
  pointer/hover equivalent, so on a fresh screen the very first thing rendered is
  already "focused" somewhere with no visible indication of it -- unlike a
  trackpad-driven desktop, there is no "haven't touched anything yet" state where an
  invisible cursor reads as intentional rather than broken. There is no engagement
  latch gating this: `isFocused` is never gated on an engagement flag, and
  Home/Library/Search carry no `engaged` state.
- Ring width 3dp, focused-art brightness lift 12% (`CardArt.kt`'s `RING_WIDTH`/
  brightness-lift constants). Every other border-based focus style in the app (tab
  chips, hero buttons, Settings' row cards) uses that same 3dp/2dp-outset ring via a
  shared `Modifier.focusRing` instead of each hand-rolling its own border. The
  constants in `CardArt.kt` are authoritative where §6 and this list disagree.
- Sibling-dim (dimming other cells in the focused row) is not shipped: the ring alone
  carries focus, with no dimming of unfocused siblings. No `rowDim` computation exists
  in the source.
- Edge fades: 48px gradient at strip edges (`STRIP_EDGE_FADE`); left fade only once scrolled >0.5px, right fade only while more content remains. Strips get −12px clip slack (`FOCUS_RING_CLEARANCE`) so rings aren't clipped.
- Focus recipe (fires as one unit): art scales 1.04× about center (art box only, absolutely positioned so siblings never reflow); 12% brightness lift; 3dp PISTACCHIO ring 3dp clear of the unscaled art edge (total outset 6dp), radius matching art (2dp). No shadow/glow on art, ever. Border-alone focus is banned. List rows: no scale — SURFACE_RAISED fill + the same ring.

## 5. Navigation model

- Startup: persisted startup_screen matched against current session's libraries; Home default; stale id → Home (root.rs:2588-2601).
- Enter/click on ANY card (Home or Library) opens Detail — never direct play (root.rs:4040-4097). Playback starts only from Detail's Play/Resume, or the episode-thumbnail one-click-play glyph (bottom-left of 16:9 thumb).
- On an Episode detail page, activating a sibling episode swaps in place (`nav.replace`) — Back skips visited siblings; from Series/Season detail it pushes (`nav.go`).
- History: browser-style back/forward stacks over Home / Library{id} / Detail{item_id}.
- **Nav drawer and menu spine.** The drawer opens on D-pad Left
  once `moveFocus(Left)` fails inside the screen (a true left edge, never a guess) and closes
  on Right or Back. Its closed edge is always on screen as the *menu spine*: a non-focusable
  19dp strip at x = 0, full viewport height, in the drawer's own `SurfacePanel` at 40%
  opacity while closed and solid once open (a solid strip dominated the TV) with a 1px
  `Hairline` right border and a 13dp `Grigio` left chevron centred in the viewport. It lives
  inside the page gutter (content still starts at 32/40dp) and no layout value changes in any
  state. It never reacts to focus: a first-column "reached" state (wider strip, brighter
  chevron, lighter fill, accent wash) was built and removed the same day, since any change as
  focus moved between the hero buttons read as flicker on the TV. Opening widens the same box to the
  272dp panel over 220ms ease-out (chevron out over the first 80ms, rows in over the last
  120ms); closing is the exact reverse over 160ms. The chevron is never accent-coloured, the
  strip is never a focus target, and the feature carries no copy, coach mark, or setting.
  Not on Search or Discover Search (no drawer there) and not during playback.

## 6. Metrics (theme.rs)

| Token | Value | Role |
|---|---|---|
| TEXT_DISPLAY | 34px bold / 44 lh | hero title |
| TEXT_TITLE | 28px bold | header without hero |
| Section header | 20px semibold | shelf titles |
| Card title | 14px medium | |
| TEXT_METADATA | 13px | |
| TEXT_CAPTION | 11px semibold | badge digits |
| Spacing scale | 4/8/12/16/20/24/32/40 | shelf gap 20, page margin 32, hero inset 40 |
| CELL_GAP | 16px | card gutter |
| CELL_WIDTH | 160px | fixed grids/shelves |
| Resume card | 5.5 across, clamp [180,320], 16:9 | |
| List row | 60px + 4 gap; thumb 32×48 | |
| RADIUS_ART / CARD / PILL | 2 / 8 / 9999 | |
| Ring width/gap/outset | 3 / 3 / 6dp | `CardArt.kt` `RING_WIDTH`/`RING_GAP` |
| FOCUS_RING_CLEARANCE | 12px | strip clip slack |
| FOCUS_SCALE | 1.02 | |
| FOCUS_SIBLING_DIM | 0.5 | not shipped, ring alone carries focus (§4) |
| PROGRESS_HEIGHT | 3px | |
| Focus anim | 180ms in / 240ms out, bezier(0.2,0,0,1) | |
| Image fade | 150ms | |
| Skeleton pulse | 2s, 0.85↔1.0 | |
| Fetch widths | poster 320 / backdrop 1280 / thumb 400 | |
| Accent | PISTACCHIO #A8CB6B only | ring, progress, badge, primary button |

Structural rule for Compose: art boxes are fixed slots; focus growth applies to an inner
layer so siblings never reflow. Text blocks reserve fixed height regardless of content.

## Sync snapshot safety

Breadth, reconcile, delta, and collection walks share a completeness rule: a
consistent reported total must be reached; without a total, an empty page ends
the walk (a short page alone is insufficient). Repeated or missing item IDs,
inconsistent totals, failed fetches, and page-limit exhaustion abort completion.
Previously fetched metadata may update, but incomplete walks do not prune old
membership or advance the delta cursor. Breadth/delta/reconcile are bounded at
2,000 pages; collection walks retain their 100-page bound.
