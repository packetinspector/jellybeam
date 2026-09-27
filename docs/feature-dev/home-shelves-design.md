# Jellybeam TV — Configurable Home shelves (design)

Two parts: **Part 1** is the product/system design (tier policy, intents,
Rust guardrails). **Part 2** is the visual design brief (screens, tokens,
don'ts). Part 2 implements Part 1.

---

# Part 1 — System design

The core move: **stop treating Home as a list the user edits, and treat it as
a policy the user feeds.** The user never sees an "add row" screen. They
express intent where the content lives, and Home's order is decided by a
fixed tier system in Rust. That removes the add/delete/move triad entirely
while keeping the system general.

## 1. Home order is a fixed tier policy, not a user-ordered list

```
Tier 0  Hero                 (unchanged: only when Continue has items)
Tier 1  Continue Watching    resume ∪ next_up, deduped, resume first
Tier 2  Favorites            hidden when empty
Tier 3  Pinned               collections / playlists (order = pin order)
Tier 4  Latest in {library}  server order, minus hidden libraries (today's behaviour)
```

Rules that make this feel seamless rather than rigid:

- Every tier hides itself when empty. No placeholder rows, ever. A new user's
  Home is byte-identical to today's.
- Users never reorder tiers. The one ordering question worth asking — "do my
  pinned rows go above or below Latest?" — is a single toggle in
  Settings › Home, not a move system.
- Within Pinned, order is pin order. To reorder, unpin and re-pin. People pin
  1–4 things; a move UI for four items is more friction than the constraint.
- Rust owns the tier evaluation (`home_snapshot` walks the tiers, applies
  caps, drops empties). Kotlin renders the same `Vec<Shelf>` it renders
  today, so the Home rendering path, eager Column, focus model, and measured
  perf profile don't change.

## 2. Intent is captured in place, never in an editor

| Intent | Where it happens | Mechanism |
|---|---|---|
| Pin a collection or playlist | Its detail page | One action button next to Play: **Pin to Home / Unpin** |
| Hide a Latest shelf | On Home, from the shelf itself | Long-press Select on any card in the row → shelf menu |
| Unpin from Home | On Home, from the shelf itself | Same menu |
| Split Continue Watching back into two rows | Same menu on the Continue row | "Show Next Up separately" |
| Bring a hidden shelf back | Settings › Home | The one place a list exists — see §4 |

**Why long-press on a card, not a focusable header.** A focusable header adds
a focus stop between every pair of rows, which taxes every Up/Down press for
every user forever, to serve a management action used a handful of times.
Long-press costs nothing on the navigation path and is the Android TV
convention for "options for this thing." The menu is an anchored panel per
docs/12 (no scrim, no modal), 2–4 rows, Back closes.

The shelf menu's contents are derived from the tier, so it stays tiny:

- Continue: *Show Next Up separately* / *Hide watched rewatches*
- Favorites: *Hide Favorites from Home*
- Pinned: *Unpin from Home*
- Latest: *Hide "{library name}" from Home*

No poster/thumb toggle. The mixed-aspect row already solves what that toggle
papers over.

## 3. Favorites

Tier 2, because it needs no configuration: if the user has favorites on the
server, the row exists. Two things to settle:

- **Mirror dependency.** Confirm `UserData.IsFavorite` is synced per item.
  If played/position state is in the mirror, the flag is likely in the same
  UserData payload — unverified.
- **Doc conflict.** docs/11 lists "Favorite toggle" under deliberate
  non-features. A Favorites shelf without an in-app way to
  favorite feels broken on a TV-only account. Recommendation: add a Favorite
  action on detail pages and update docs/11 to record the reversal and why.
  Alternative: ship the shelf anyway (works for users who favorite from
  Jellyfin Web) and say so in the feature list.

## 4. Settings › Home becomes an overview, not an editor

One screen, no ordering controls:

```
Home
  Continue Watching      [Combined ▾]   (Combined / Separate rows)
  Favorites              [On]
  Pinned rows            [Above Latest ▾]
     • Weekend Classics             Unpin
     • Summer Playlist              Unpin
  Latest
     ☑ Movies   ☑ TV   ☐ Kids   ☑ Anime
  Reset Home to default
```

It exists for recovery and discoverability ("where did my Kids row go?").
It's the read-out of the policy, not the way you drive it. Merge today's
per-library visibility toggles into it so there's one Home surface, not two.

## 5. What stays out, and what waits

- **Genres and studios as pins** — deferred. They need a genre grid page to
  pin *from*. Tier 3 is spec'd as
  `PinnedShelf { kind: Collection | Playlist | (later) Genre }` so adding it
  later touches no UI already built.
- **Custom shelf titles** — never. Names verbatim.
- **Manual row ordering, drag handles, up/down buttons** — never. If there's
  pressure to add them, the fix is another tier rule, not a list editor.

## 6. Performance guardrails (decided in Rust)

- Hard cap of 10 rendered shelves and 30 cards per shelf; tiers are evaluated
  in order and Rust stops at the cap. Latest is last, so pinning many things
  trades off against Latest rows — surface that as a one-line note in
  Settings › Home rather than a warning.
- Pinned rows are mirror queries (children of a BoxSet/Playlist id) — confirm
  both item types are synced; the feature list only names
  shows/seasons/episodes. If not, that sync work is the real cost and is a
  separate gated commit ahead of any UI.
- A pin whose item no longer exists in the mirror is silently dropped from
  Home (fail open) but kept in settings until the user unpins, so a
  revoked-then-restored share comes back on its own.
- Long-press detection is Kotlin-only key timing; the menu is composed only
  on open. Nothing new is resident on Home while browsing.
- Add a 10-shelf profile to the `ffi.homeSnapshot` perf check before merging;
  if median regresses noticeably over today's baseline, the tier cap drops,
  not the design.

## 7. Commit sequence

1. Rust tiers + combined Continue + tests; default output identical to today.
2. Favorites shelf + Favorite action + mirror flag verification.
3. Pinning — detail-page action, long-press shelf menu, Settings overview.

---

# Part 2 — Design brief

Extend the existing Jellybeam TV design (Player OSD file for palette, type, and
component language) with the Home-shelf customization UX below. Reuse every
token and pattern already established; introduce no new colors, fonts, or
control styles.

## Brand tokens (must match the OSD file)

- Background NOTTE. Text is PANNA at four alphas only (FF / B3 / 73 / 40).
  GRIGIO for mono/technical text. PISTACCHIO #A8CB6B is the sole accent —
  focus ring, progress fill, badges, primary button. Never for hover, chrome,
  or decoration.
- Archivo for UI text. Martian Mono, 10–11sp, uppercase, `│`-joined, for
  technical data only. Bagel Fat One only for the word "Jellybeam".
- Focus = 3dp PISTACCHIO ring at 2dp outset + art scale 1.02 inside a fixed
  slot + ~12% brightness lift. Never border-only, never shadow/glow.
- Panels: SURFACE_PANEL fill, 1dp HAIRLINE border, 8dp radius, no scrim, no
  blur. Loading motif = pulsing dots, never spinner rings.
- 1080p canvas, 32dp page margin, overscan-safe. D-pad only — no pointer, no
  hover states.

## Design principle

Home is a **policy the user feeds, not a list the user edits.** No
add/delete/move row editor anywhere. Users express intent where content
lives; Home orders itself by fixed tiers. Every empty tier disappears. A
fresh user's Home is identical to today's.

Tier order (fixed, not user-reorderable):

1. Hero (only when Continue Watching has items)
2. Continue Watching — resume and next-up merged into one row, resume first
3. Favorites — auto-shown when the user has any
4. Pinned — collections / playlists the user pinned, in pin order
5. Latest in {library} — one row per library in server order

## Screens

### A. Home with all tiers populated
Shelf headers 20sp semibold PANNA. Continue row is mixed-aspect (16:9
episodes, 2:3 movies, same height, 5.5 cards across). Favorites and Pinned
rows: 2:3 posters at 160dp cell width. Show one pinned collection row and one
pinned playlist row. Header text is the server's name verbatim — no icons, no
"Pinned:" prefix. Show the focused card with the standard ring and the rest
of its row dimmed to 50%.

### B. Shelf menu (long-press Select on any card in a row)
Anchored panel above the focused card's row, not a modal: min-w 200 /
max-w 360dp, p4, 8dp radius, SURFACE_PANEL, 1dp HAIRLINE. Rows px12 py8, 6dp
radius, focus = SURFACE_OVERLAY fill. Contents depend on the row:

- Continue Watching: "Show Next Up separately" · "Hide watched rewatches"
- Favorites: "Hide Favorites from Home"
- Pinned: "Unpin from Home"
- Latest: `Hide "Movies" from Home` (library name verbatim, in quotes)

Design all four variants. Back closes. No title bar, no icons, no accent.

### C. Collection detail page — Pin action
Existing detail layout (four-layer backdrop, poster 220×330, title, meta
line, actions row). Add **Pin to Home** as a secondary action in the same row
as Play: 44dp tall, transparent, 1dp HAIRLINE border, PANNA-2 label, same box
as "Start from beginning". Pinned state: label becomes **Unpin from Home**; no
icon change, no accent, no checkmark. Show both states.

### D. Detail page — Favorite action
Same secondary-button style, label **Favorite** / **Favorited**. Sits after
Pin to Home (collections) or after Play (movies/series/episodes). No heart
icon; the only iconography is the standard watched-check badge on cards
elsewhere. Show both states on a movie page.

### E. Settings › Home (overview, not an editor)
One D-pad list using the Settings row-card recipe. No ordering controls, no
drag handles.

```
Continue Watching        Combined ▾        (Combined / Separate rows)
Favorites                On
Pinned rows              Above Latest ▾    (Above Latest / Below Latest)
   Weekend Classics               Unpin
   Rainy Sunday                    Unpin
Latest
   ☑ Movies   ☑ TV Shows   ☐ Kids   ☑ Anime
Startup screen           Home ▾
Clock                    On
Missing episodes         Show ▾
Reset Home to default
```

Sub-rows under Pinned and Latest indented 24dp. Names verbatim. One-line
tertiary note under Pinned: "Home shows up to 10 rows; pinned rows come
first." Reset is a plain row, not a red/danger style (no status colors exist
in this system).

### F. Empty-state discipline
One frame of Home for a fresh account with no favorites and nothing pinned:
exactly today's Home (Hero, Continue Watching, Latest rows). No prompts, no
"pin something" callout, no empty rows.

## Don'ts

- No row reorder UI of any kind. No "Add row" button. No custom shelf titles.
- No poster/thumbnail toggle per shelf.
- No new colors, no gradients on panels, no icons in menus or buttons.
- No focusable shelf headers — management is only via long-press on a card.

## Deliverable

Frames A–F at 1920×1080, plus the four shelf-menu variants and the two-state
buttons as component variants. Annotate focus order on Settings › Home and on
the shelf menu.
