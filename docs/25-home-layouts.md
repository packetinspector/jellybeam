# 25 — Home layouts

Status: **P2 implemented** (Classic in the new shape, §10.1 steps 1–4). P3's TV measurement
is pending, and P4 onward is not started. "Classic" is today's Home, specified in docs/07 §1.

**Scope:** this doc is the architecture only. It covers how any Home layout
plugs in, stays self-contained, and costs nothing while inactive. Each
layout's product design (what it shows and how it behaves) is its own spec.
Classic's is docs/07 §1.

This spec plans how Jellybeam can ship more than one Home screen and let the
viewer choose between them. It does so without making any single layout
slower, heavier, or harder to maintain than it would be if it were the only
Home in the app. It covers the Rust core, the Kotlin UI, how the two stay
DRY across the UniFFI boundary, and how the tree is organised so each layout
is self-contained.

## 1. Goal and constraints

The viewer picks a Home layout under Settings › Home. Classic is the
default. Each layout is a **different screen**: its own composition, focus
behaviour, skeleton and data. It is not a different ordering of the same
shelves.

Hard constraints. Each one is a pass/fail check in §9:

- **C1 Startup.** The code of an inactive layout adds no measurable cold-start
  cost to the selected one, and the groundwork adds none to Classic. Both are
  judged by the protocol and threshold in §9.2.
- **C2 Memory.** After a cold start, and after any switch has settled, the
  heap holds no instance of a stateful class belonging to an inactive layout:
  its ViewModel, UiState, snapshot record, or remembered Compose state. Its
  stateless spec object may be loaded (§5.2). This is checked by the heap
  histogram in §9.3.
- **C3 Work.** A layout's builder runs only while that layout is **drawn**,
  meaning its feed exists (§5.4). This is a host guarantee, not a core
  check:
  - The core builds whatever it is asked for (§4.3).
  - Only two call sites exist: `HomeFeed` and the `LaunchWarmup` prefetch. The
    §5.6 test pins that.
  - Once a different layout is committed, the drawn feed stops issuing
    requests (§6.4).
  - A snapshot build already inside the core when its feed is retired is the
    one allowed layout-query waste. A native call can't be interrupted, so it
    runs to completion and its result is discarded (§5.4). The next layout
    doesn't wait for it: at most one old build overlaps the new layout's first
    build, once per switch (§6.4).

  No inactive layout's Kotlin code runs, apart from reading its Settings label
  (§5.2). A layout that has never been selected has no indexes, no background
  work and no network traffic (§4.8, §4.9).
- **C4 One owner per decision.** Every content decision is implemented once,
  in Rust (docs/01 "Division of responsibility"). Kotlin never re-decides it.
- **C5 One dispatch site per language.** Only `home/mod.rs` and
  `HomeLayouts.kt` name more than one layout. Adding a layout also touches the
  shared files listed in §7; the list is fixed, and anything outside it is a
  design change to this spec.

Non-goals:

- Per-server or per-user layouts. Settings are per install (`settings.json`),
  so the layout is too.
- A live preview of layouts inside Settings.
- Mixing parts of two layouts on one screen.
- Changing the drawer, Library, Detail or the player per layout. The drawer
  and its spine wrap every layout.

## 2. Terms

- **Layout.** One complete Home screen, identified by a `HomeLayout` enum
  value.
- **Builder.** A layout's Rust function that reads the mirror and returns that
  layout's snapshot record.
- **Spec object.** A layout's Kotlin singleton implementing `HomeLayoutSpec`.
  It is the layout's single entry point on the UI side.
- **Building block.** A content rule shared by at least two layouts (today:
  Continue Watching and Next Up). It is written once, in Rust.
- **Host.** Everything outside the layout: `JellybeamRoot`, the drawer, the
  back stack, and `HomeHost`, which picks the spec object.

## 3. Ownership across the boundary

The rule that keeps the two languages DRY: **a decision about what is shown
belongs to Rust, and a decision about how it is shown belongs to Kotlin.
Nothing is decided on both sides.**

| Rust owns (content) | Kotlin owns (presentation) |
|---|---|
| Which queries run, their order and caps | Composition, layout metrics, animation |
| Dedup between shelves | Focus, D-pad routing, focus restore (docs/15) |
| Which shelves exist, their order, and hiding empty ones | Skeleton, reveal, progressive mounting |
| Hero choice | Localised strings, including shelf titles |
| Hidden libraries and setting gates | ViewModel lifecycle and referential reuse of lists |
| The persisted layout preference: its default, and the fallback for an unknown value (§4.2) | The session's committed layout (`JellybeamRoot.homeLayout`, §6.1), and mapping enum values to string resources |

Today's Home breaks this rule in three places. Item 1 is a plain duplicate
and can be removed on its own at any time. Items 2 and 3 move in the
groundwork (§10 P2). None of the three is visible to the viewer:

1. **Next Up dedup is implemented twice.** Rust `next_up_beside_resume`
   (`core/ffi/src/object.rs`) filters Next Up against Continue Watching. Kotlin
   `HomeViewModel.dedupAgainst` filters it again, and its doc comment says the
   Rust side doesn't. The Kotlin copy and its tests are deleted.
2. **Shelf order and empty-shelf hiding** are decided in Kotlin
   (`ShelfAssembly.buildShelves`). They move into the Classic builder (§4.5).
3. **The hero choice** ("the first card, when shelf 0 is Continue Watching") is
   decided in Kotlin (`heroCard`). It moves into the Classic builder.

The layout choice has exactly two owners, with different jobs. Rust owns the
**persisted preference**: what is stored, and what an unknown or missing value
means. Kotlin owns the **session's committed choice**: which layout is on
screen now, and which one every request names. The core never keeps a copy
of the committed choice, and Kotlin never interprets the stored value beyond
reading it once to seed the session. Adding a third place that "knows the
active layout" is a design change to this spec.

These stay in Kotlin because they are presentation: `contentTopInset`,
`initialMountCount`/`mountedShelfCount`, shelf titles, and
`reuseIfUnchanged`.

## 4. Rust design

### 4.1 Tree

```
core/ffi/src/home/
  mod.rs        HomeLayout, HomeSnapshot, snapshot(): the only file that names layouts
  blocks.rs     content rules used by more than one layout (§4.5)
  classic.rs    ClassicHome, HomeShelf, ShelfSource, build(), tests
  intuitive.rs  IntuitiveHome record, build(), tests (§8.2)
```

Home code leaves `object.rs`. `JellybeamCore::home_snapshot` becomes a thin
wrapper that locks state, takes the mirror and settings, and calls
`home::snapshot`. `Card` stays in `types.rs` because every screen uses it.
Records used only by Home live in `home/`.

### 4.2 `HomeLayout`

```rust
#[derive(uniffi::Enum, serde::Serialize, serde::Deserialize, Default, Debug, Clone, Copy, PartialEq, Eq)]
pub enum HomeLayout {
    #[default]
    Classic,
    Intuitive,
}
```

- It is stored as `Settings.home_layout`, with a serde default of `Classic`.
  An old `settings.json` without the field loads as Classic.
- **An unknown value loads as Classic.** A downgrade, or a layout removed in a
  later release, must never fail settings loading. `settings::load` already
  falls back to defaults on a parse error, but that would reset every other
  setting. So the field gets its own lenient deserializer, pinned by a test.
- **The on-disk name of a variant never changes once shipped.** Each variant
  gets an explicit `#[serde(rename = "...")]`, so a Rust-side rename can't
  orphan saved settings.
- The enum is the only definition of the layout list in the whole app. Kotlin
  uses the uniffi-generated `HomeLayout` and never declares its own.
- Following the `favorites` pattern in `HomeSnapshot`, the new `Settings` field
  is added last with a uniffi default, so positional Kotlin constructions keep
  compiling. If the pinned uniffi (0.32) can't express an enum default, the
  Kotlin constructions are updated instead.

### 4.3 `HomeSnapshot`

```rust
#[derive(uniffi::Enum, Debug, Clone, PartialEq)]
pub enum HomeSnapshot {
    Classic { home: classic::ClassicHome },
    Intuitive { home: intuitive::IntuitiveHome },
}
```

- There is one FFI entry point, `home_snapshot(layout: HomeLayout)`. It
  builds the layout it is **asked** for, not the one in settings. The caller
  always names the layout it will draw, so:
  - A request never races a settings write (§6.4).
  - A layout never receives another layout's snapshot, except a discarded
    prefetch (§6.3).
  - `CoreGateway`, the real gateway and `FakeCoreGateway` keep a single
    `homeSnapshot(layout)` method for every layout.
- The variants use named fields, not tuple fields, so the generated Kotlin
  sealed class has a stable property name (`home`).

### 4.4 Dispatch

```rust
pub(crate) fn snapshot(layout: HomeLayout, mirror: &Mirror, settings: &Settings) -> HomeSnapshot {
    match layout {
        HomeLayout::Classic => HomeSnapshot::Classic { home: classic::build(mirror, settings) },
        HomeLayout::Intuitive => HomeSnapshot::Intuitive { home: intuitive::build(mirror, settings) },
    }
}
```

This is the only `match` on `HomeLayout` in Rust. Only the requested builder
runs, which satisfies C3. `Settings.home_layout` is persistence only: the
core never reads it to decide what to build. With no mirror open, the
builder is never called, and the requested variant is returned with an empty
record
(`Default::default()`).

### 4.5 Shared building blocks (`blocks.rs`)

A rule lives in `blocks.rs` only when at least two layouts use it. Checked
against Classic and Intuitive (§8.4), that means:

- `resume(size)`: the Continue Watching query. Classic's first shelf and hero,
  and Intuitive's main panel.
- `next_up_beside_resume(resume, size)`: the existing function, which becomes
  the only dedup. Classic's Next Up shelf, and Intuitive's fallback when nothing is in progress.

Rules only Classic uses move into `classic.rs`:

- the Favorites shelf, gated on `home_show_favorites`;
- the Latest shelves, which skip Channel views and hidden libraries;
- shelf order and the hero choice.

Classic's records live in `classic.rs`:

```rust
pub struct ClassicHome { pub hero: Option<Card>, pub shelves: Vec<HomeShelf> }
pub struct HomeShelf { pub source: ShelfSource, pub cards: Vec<Card> }

pub enum ShelfSource {
    ContinueWatching,
    NextUp,
    Favorites,
    Latest { view_id: String, view_name: String },
}
```

`shelves` is already in docs/07 §1 order with empty shelves dropped. `hero`
is the first Continue Watching card, or `None`. Classic's Kotlin side draws
exactly what it receives, and maps `ShelfSource` to a localised title and a
card treatment. The name avoids `ShelfKind`, which already means the visual
treatment in Kotlin (`ui/home/ShelfAssembly.kt`).

Rule for blocks: if two layouts need slightly different versions of a block,
the block takes a parameter rather than being copied. Once a block needs more
than about three parameters to serve every layout, the variation belongs in
the layout's own module instead. A rule moves from a layout's module into
`blocks.rs` when a second layout first needs it, and not before.

### 4.6 What a layout module contains

- **Its snapshot record,** defined in the module rather than in `types.rs`.
- **`pub(super) fn build(mirror: &Mirror, settings: &Settings) -> <Layout>Home`,**
  which only combines building blocks and mirror queries.
- **Private helpers and its tests,** beside the code they test.
- **Imports** only `home::blocks`, `crate::types` and `media_cache`. It never
  imports another layout. The Rust compiler enforces this, because a sibling
  module's private items aren't visible.

### 4.7 Layout-specific settings

A **new** setting that only one layout uses lives in a struct defined in that
layout's module, e.g. `Settings.intuitive: IntuitiveSettings` with
`#[serde(default)]`.

Settings that already exist stay where they are in `settings.json`, flat, so
saved files keep loading. Ownership of their Settings **rows** follows their
meaning:
- `home_shelf_size` and `hidden_library_ids` apply to every layout. Their rows
  stay in the shared Home section.
- `home_show_favorites` ("Favorites row") and `hide_watched_in_latest` only
  mean something to Classic. Their rows move into `ClassicLayout.SettingsRows()`,
  so they appear only while Classic is active.

A shared setting that a layout ignores is simply unused by that layout; it is
never duplicated.

### 4.8 New queries and indexes

Mirror queries a layout needs go in `media-cache` like any other query, under
the normal rules there (docs/16 §2).

An index is not free when idle. Every item upsert during sync maintains it,
and it takes disk. So an index that only one layout needs is built only once
that layout has been selected:

- **Deferred index groups.** `media-cache` holds a table of named groups,
  each a list of `CREATE INDEX IF NOT EXISTS` statements. One writer command,
  `BuildIndexGroup(group)`, builds any group and records it as ready. The
  favorites indexes (docs/16 §2.7) become the first group. They are
  app-wide rather than tied to a layout, because the Favorites library page
  uses them too.
- **A layout group is built on first use.** A builder whose queries need its
  group checks that group's ready flag:
  - If the group is ready, the builder runs those queries.
  - If not, it enqueues `BuildIndexGroup` without waiting (the request is
    idempotent) and returns those parts of its record empty.
  - Once the build finishes, the writer emits `Refresh`, and the next snapshot
    fills the parts in.

  So a never-selected layout never builds its indexes (C3). Opening a
  populated mirror never pays for any group.
- **An empty mirror** (first sign-in) creates the app-wide groups at open, as
  favorites does today. Layout groups wait for first use even then, so a
  fresh install carries only the selected layout's indexes.
- **Built groups stay.** Switching away doesn't drop them: a later switch back
  would rebuild them, and dropping costs a write as well.
- **Every query on a builder's path has a query-plan test** that pins the
  index it uses and fails on a full scan of `items`, the way the favorites
  queries are pinned today.

### 4.9 Builder rules

These are the rules that keep any layout's snapshot as cheap as Classic's.
Each is checked by a test (§9.1) or a measurement (§9.2).

1. **Local only.** `build(mirror: &Mirror, settings: &Settings)` is a plain
   synchronous `fn`, as `home_snapshot` is today. `Mirror`'s network-backed
   methods (`refresh_items`, `refresh_next_up` and the rest) are all `async`,
   so a builder can't await them. It makes only the synchronous read calls. A
   builder never uses `block_on` or a runtime handle.
   - Remote data reaches a builder only through the mirror, or through a
     core-side cache that sync fills away from Home's path.
   - Detecting an optional server feature (§8.3.1) is done in the sync pass;
     the builder reads only the cached result.
   - The one write a builder may cause is the non-blocking index request in
     §4.8.
2. **The first screen only.** A builder returns what the layout's first
   screen draws, capped by Shelf size or a constant in the layout. Longer
   lists belong to destination screens, with paging (docs/16 §4.6). This
   bounds the FFI marshal, which is part of `ffi.homeSnapshot`.
3. **Refresh pays the same cost.** `HomeFeed` rebuilds the whole snapshot on
   every sampled mirror change (§5.4). So a builder's cost is paid during
   every sync burst, not just at startup, and the budget covers both. There is
   no partial refresh; a layout that needs one revises this spec.
4. **Default budget.** In the §9.2 G2 session, measured with that layout
   selected, a layout's median `ffi.homeSnapshot` and `home.focusReady` are
   each within the §9.2 threshold of Classic's. A layout's product spec may
   set a different budget only with a stated reason, and that budget is then
   gated the same way.

## 5. Kotlin design

### 5.1 Tree

```
ui/home/
  HomeLayouts.kt   HomeLayoutSpec + HomeLayout.spec: the only file that names layouts
  HomeHost.kt      settings.layout.spec.Screen(args); names no layout
  common/          HomeFeed, Shelf, EdgeFadedRow, skeleton blocks, wordmark, clock,
                   empty-library state, sync pill, focus-restore helpers, perf marks
  classic/         ClassicLayout (spec object), screen, ViewModel, UiState,
                   presentation decisions + tests
  intuitive/       same shape (§8.2)
```

`MainActivity` imports only `HomeHost`. Today it imports `HomeScreen`, and
that is the only import of `ui.home` outside the package.

### 5.2 `HomeLayoutSpec`

```kotlin
interface HomeLayoutSpec {
    @get:StringRes val label: Int
    @Composable fun Screen(args: HomeHostArgs)
    @Composable fun Skeleton()
    /** Settings › Home rows only this layout uses; shown only while it is active. */
    @Composable fun SettingsRows() {}
}

val HomeLayout.spec: HomeLayoutSpec get() = when (this) {
    HomeLayout.CLASSIC -> ClassicLayout
    HomeLayout.INTUITIVE -> IntuitiveLayout
}
```

- This is the only `when` on `HomeLayout` in Kotlin. It is exhaustive, so the
  compiler flags a new variant until it has an entry here.
- **Settings** lists `HomeLayout.entries.map { it.spec.label }` and shows the
  active layout's `SettingsRows()`. It has no `when` of its own.
- **Class loading is a hypothesis, not a guarantee.** The expectation is that
  ART loads a class on first use, so an arm that doesn't run never loads its
  spec object, and a spec object's `Screen` never loads its layout's other
  classes until it runs. Two things can break that expectation:
  - R8 may inline, merge or reorder classes, since several spec objects
    implement one interface.
  - The baseline profile (§11) may preload classes.

  The design doesn't depend on lazy loading for correctness, only for C1's
  cost. So C1's measurement (§9.2) and C2's heap check (§9.3) decide it. The
  §9.3 heap dump also lists which inactive-layout classes were loaded.
- **Settings reads labels.** Settings loads every spec object to read its
  label. Each spec object is stateless, which keeps this within C2.

### 5.3 The host contract (`HomeHostArgs`)

`HomeHostArgs(onOpenDetail, onNavigate, isTop, focusGate)` is today's
`HomeScreen` parameter list plus `onNavigate: (Screen) -> Unit`: the root's
existing `navigateFromDrawer`, for layouts that link to screens themselves
(Intuitive's Search and Browse). There is one callback for every
destination, so a new link never widens the contract. Classic ignores it,
so `onNavigate` is added with the first layout that links anywhere (§10 P4).

`HomeHost` sits inside `NavDrawerHost`, exactly where `HomeScreen` sits
today. So the drawer, its spine, and the Left-at-the-edge rule are the
host's, and a layout can't opt out of them. Every layout does take part in
`DrawerFocusCoordinator`, so closing the drawer restores that layout's own
focus. Every layout's `Screen` must meet the same obligations
Classic meets today:

- **Focus.**
  - Honour `focusGate` and docs/15's rules.
  - Restore focus when becoming top, through the shared `FocusMemory`.
  - Provide a fallback when the drawer closes.
- **Refresh.** Call its ViewModel's `setActive(isTop)` and `onHostResume()`,
  so hidden-Home sampling and resume catch-up (docs/17 §6) behave the same.
- **Artwork.** Use the shared card image URLs and width bucketing
  (`CardFormatting.bucketedImageWidth`, docs/07 §1), so each layout's images
  share renditions and the image cache with every other screen, instead of
  requesting one-off sizes.
- **Perf marks.** Emit `home.dataReady`, `home.contentDrawn` and
  `home.focusReady` exactly once per cold launch, through helpers in
  `common/`, so the names in docs/10 have one source. A mark only one layout
  has, such as Classic's `home.shelvesMounted`, stays in that layout.

### 5.4 `HomeFeed<T>`

`HomeFeed` is the layout-agnostic part of today's `HomeViewModel`, about 70%
of it. Each layout's ViewModel owns one instance: composition, not
inheritance. It holds:

- The refresh-conflation guard (`refreshInFlight`/`refreshQueued`).
- `ChangeRefreshScheduler` and the `setActive` gating.
- The PiP stop-epoch hook and `onHostResume`.
- The `LaunchWarmup.takeHome()` hand-off and the stale-prefetch trailing
  refresh.
- `pollSyncing` and `pollSyncStatus`.
- `retire()` and `unretire()` (§6.4). While retired, no trigger issues a
  request.
- **A generation.** Retiring, and clearing the store, advance it. Every
  gateway call the feed starts captures it: the snapshot, `views()`,
  `getSettings()`, the sync-status polls and the lazy `serverHost` read. A
  result is applied only if its generation is still current.
  - Clearing the store cancels `viewModelScope`.
  - A call already blocked inside the core keeps running on its IO thread
    (`CoreGateway.ffi` wraps each call in `withContext(Dispatchers.IO)`, and
    a native call can't be interrupted). When it returns, the cancelled
    coroutine throws at its next suspension point, so the value is dropped
    without being applied.
  - Nothing waits for these calls, and nothing is needed to.
- **The shared chrome state**, `HomeChrome`, which every layout draws from and
  none owns. It is fetched alongside each snapshot, concurrently, exactly as
  `refreshSnapshotOnce` does today. Its fields:
  - `isLoading`, `isSyncing`, `syncProgressText` and `loadingStatusText`;
  - `views`, which drives the empty-library state;
  - `showClock`;
  - `serverHost`, loaded only when the empty state shows (`loadServerHost`).

The feed exposes two flows: `StateFlow<HomeChrome>` and `StateFlow<T?>`. The
first is `null` until the first snapshot lands. Today's `HomeUiState` splits
along that line: its chrome fields go to `HomeChrome`, and
`resume`/`nextUp`/`favorites`/`latest` go to Classic's own UiState.

The type parameter `T` is the layout's own record. Each layout passes one
function that picks its variant out of the snapshot:

```kotlin
HomeFeed(gateway, launchWarmup, HomeLayout.CLASSIC) { (it as? HomeSnapshot.Classic)?.home }
```

The feed always requests its own layout (§4.3), so the extractor only sees
another layout's variant in one case: a discarded prefetch (§6.3). The
layout's ViewModel maps `T` into its own UiState, applying
`reuseIfUnchanged` (from `common/`) per section. Tests that exist today for
the refresh and sync loops move to a `HomeFeed` test. Layout ViewModel tests
cover only the mapping.

### 5.5 Resources

A layout's strings go in its own file, `res/values/strings_home_<layout>.xml`.
Android merges every file in `values/`. Strings shared by every layout (shelf
titles, the sync pill, the top-bar labels) stay in `strings.xml`.

### 5.6 Boundary test

Kotlin can't enforce the boundary: the app is one Gradle module, so
`internal` is app-wide. A plain JVM unit test scans the `ui/home/` sources and
fails if either of these holds:

- a file in one layout package imports another layout package;
- any file other than `HomeLayouts.kt` imports a layout package;
- `homeSnapshot(` is called anywhere in `app/src/main` other than
  `HomeFeed` and `LaunchWarmup` (C3).

## 6. Selection, first frame, switching

### 6.1 Where the value lives

There are two values, each with one job:

- **`Settings.home_layout`** in the core is the **persisted** choice. It is
  read once per session to seed the root, and written when the viewer picks a
  layout.
- **`JellybeamRoot.homeLayout`** (Compose state) is the **committed** choice
  for the running session. `HomeHost` draws it, and every
  `home_snapshot(layout)` request names it. Nothing reads the persisted value
  back during a session, so the order in which the two are written can't
  matter.

The root is seeded like this:

- **Cold start.** `LaunchWarmup` reads `getSettings().homeLayout` in its
  `Session` step, right after `restoreSession`. This is an in-memory read with
  no network. It hands the value out with the session, and prefetches
  `home_snapshot(thatLayout)`.
- **Non-warm path** (account switch, added server, recreated Activity). The
  stack-ready effect reads it the same way.

### 6.2 First frame

The loading skeleton must match the layout, or the screen jumps when data
lands. `HomeHost` draws `homeLayout.spec.Skeleton()`. Because the layout comes
from the `Session`, which is already awaited before `root.stackReady`, no new
wait is added before the first Home frame.

### 6.3 A prefetched snapshot for another layout

The one way a feed can receive another layout's variant is a `LaunchWarmup`
prefetch taken by a different layout than the one it was built for. The
committed layout changes only through §6.4, and the first Home takes the
prefetch in its ViewModel's `init`, before Settings can be opened. So this
isn't expected to happen. If it does, the extractor returns `null`, the
prefetch is dropped, and the feed requests its own layout at once. A test
pins that path, and a test for each layout pins that its extractor accepts
its own variant.

### 6.4 Switching

1. The picker in Settings calls a root callback,
   `onHomeLayoutSelected(layout)`, that `JellybeamRoot` passes to the Settings
   screen:
   - The callback sets `homeLayout` synchronously on the main thread. This is
     the commit point.
   - Separately, and through the ordinary `updateSettings` path, the choice is
     persisted to `Settings.home_layout`.
   - If that write fails, the running session is unaffected. The next cold
     start comes up in the last layout that was successfully persisted.
2. While Settings remains on top, Home keeps its current composition and
   `drawnLayout`. Picker actions update `committedLayout`, but do not compose
   another layout, clear a store, or request a snapshot. Repeated picks
   coalesce to the last committed value.
3. When Home becomes top, `HomeHost` compares `committedLayout` with
   `drawnLayout`:
   - If they match, keep the current layout. If its feed was retired because
     the values briefly differed, unretire it and issue one catch-up refresh
     after Home is visible.
   - If they differ, retire the old feed before beginning the swap. Retirement
     stops change-event, PiP-stop, and host-resume triggers and clears any
     queued refresh. No new request may start from that feed.
4. For a real swap, `HomeHost` does these steps in order. The order follows
   the rule `pruneViewModelStores` already obeys in `MainActivity`: a store is
   cleared only after its screen has left composition, never while it is
   still mounted.
   1. Close `focusGate`. The old feed is already retired (step 3), so it
      starts no new calls.
   2. Set `drawnLayout` to the latest committed value. On that recomposition,
      the old spec leaves composition along with its remembered state. The new
      spec composes under its own store, keyed `home:<layout>` in the
      Activity-scoped holder (§10.1 step 5), and its feed is created.
      - The new layout's own skeleton is what the viewer sees until its first
        `home_snapshot(newLayout)` lands, exactly as on a cold start.
      - A switch never takes the `LaunchWarmup` prefetch; that one-shot
        belongs to the first Home of a cold launch.
   3. In a `SideEffect` after that composition has been applied, clear the old
      layout's `ViewModelStore` and drop its owner. That cancels its scope and
      advances its generation (§5.4). This is the same pruning point
      `pruneViewModelStores` uses.
   4. Place the new layout's initial focus target, then open `focusGate`.
      Focus memory is never carried across layouts.

   There is no transition screen and no waiting:
   - A call the old feed started that is still inside the core finishes on
     its IO thread, and its result is dropped (§5.4). It can overlap the new
     feed's first request. Both are read-only builders on the read pool, so
     the overlap costs at most one build's CPU, once. Waiting for it first
     would cost the same time, one after the other.
   - If the core is slow or stuck, the new layout sits on its skeleton, just
     as any Home does when the core stalls at cold start. Back behaves as it
     does on Home at any time (docs/07 §5). A layout switch introduces no
     new stall and no new state.

   If a session reset happens, `resetSessionState` clears every Home store as
   it does today. That cancels their scopes and advances their generations,
   so no late result from the replaced session is applied, and the next Home
   is mounted by the normal stack-ready path.
5. The layout picker is hidden while `HomeLayout.entries.size == 1`, so
   shipping the groundwork alone adds no visible Settings row.

**Retiring the drawn feed.** As soon as committed differs from drawn, the
feed is retired: its `ChangeRefreshScheduler`, stop-epoch, resume hooks,
sync-status polling and lazy host reads stop starting gateway calls, and its
queued refresh is cleared.
- If the viewer switches back before leaving Settings, the old composition
  and store are kept. The feed un-retires, and its single catch-up refresh is
  deferred until Home becomes top.
- A call already inside the core finishes, and its result is dropped by
  generation (§5.4).

These decisions are one pure function,
`homeLayoutTransition(committed, drawn, isTop)`. It returns `Keep`,
`Retire`, `Unretire` or `Swap(from, to)`, and is pinned by unit tests.
The function decides; `HomeHost` performs the effects.
- The swap is driven by `drawnLayout`, which is Compose state. So a
  recomposition that sees the same `Swap` finds `drawnLayout` already
  advanced, gets `Keep`, and does nothing.
- The old store is cleared by pruning: every Home store owner whose key isn't
  `home:<drawnLayout>` is cleared. Pruning is idempotent, so clearing twice is
  impossible.
- A session reset also advances `sessionEpoch`, and `resetSessionState` clears
  every owner.

## 7. Adding a layout

The two dispatch files are the only places that name more than one layout
(C5). This table is the complete list of files a new layout may touch:

| Where | Change | Kind |
|---|---|---|
| `core/ffi/src/home/mod.rs` | `HomeLayout` variant with its serde name; `HomeSnapshot` variant; dispatch arm | dispatch |
| `ui/home/HomeLayouts.kt` | one `when` arm | dispatch |
| `core/ffi/src/home/<layout>.rs` | new: record, `build`, tests, optional settings struct | owned |
| `ui/home/<layout>/` | new: spec object, screen, ViewModel, UiState, skeleton, tests | owned |
| `res/values/strings_home_<layout>.xml` | new | owned |
| `core/ffi/src/settings.rs` | one field holding the layout's settings struct, if it has settings (§4.7) | shared |
| `core/media-cache` | new queries in `query.rs`/`lib.rs`, each with a query-plan test; one entry in the deferred index table, if the layout needs indexes (§4.8); a sync-pass fetch in `sync.rs`, only for data the mirror doesn't hold yet (§8.3.1) | shared |
| `core/ffi/src/home/blocks.rs` | a rule moved in from a layout, or a new parameter on one, when a second layout needs it (§4.5) | shared |
| `app/src/main/baseline-prof.txt` | only if §9.2 shows the wildcard costs startup (§11) | shared |
| This doc | a §8 entry, written before code | docs |
| docs/13, docs/09, docs/07 or docs/16 | feature entry; settings rows; query contracts, as applicable | docs |

`CoreGateway` and `FakeCoreGateway` aren't touched. A change to any shared
file not listed here means this spec needs revising first.

## 8. Layout catalogue

### 8.1 Classic

docs/07 §1, unchanged. After the groundwork, its data is `ClassicHome`
(§4.5) and its UI is `ui/home/classic/`.

### 8.2 Intuitive

Intuitive's product design (what it shows, its focus order, its wording) is
not part of this doc. It gets its own spec before it is built (§10 P4). This
section records only what it asks of the architecture, taken from its first
mockups. It serves as the test case for §8.3.

Intuitive's parts:
- a top bar with Search and Browse buttons and the clock;
- a large "now" panel for one item, with context about that item: season
  progress, the next episode, and one alternative;
- a row of never-started items, newest first;
- a watch-time panel for the last 7 days.

It asks the architecture for:

1. **A record shape unlike Classic's.** It has no shelves, and its panels
   share nothing with Classic's except `Card`.
2. **Links to other screens** from inside the layout: Search, and a new Browse
   screen.
3. **Rules Classic already has:** Continue Watching and Next Up.
4. **New mirror queries** (season and series progress, never-started items),
   each needing an index.
5. **A cheaper form of an existing rule.** Its next-episode lookup must be fast
   enough for startup, and today's isn't.
6. **Data the mirror doesn't hold:** watch time per day.
7. **A fixed, small number of cards**, so no progressive mounting.

### 8.3 What a layout may need, and where it goes

This is the general rule for any future layout. A need not covered here
means this spec is revised before the layout is built.

| A layout needs… | It goes… | Why |
|---|---|---|
| Its own content shape | A record in its own Rust module (§4.6), a `HomeSnapshot` variant | Only `Card` and the blocks are shared, so no layout's shape constrains another's. |
| A rule another layout already has | `blocks.rs`, moved there when the second layout first needs it (§4.5) | One implementation per rule (C4). |
| A new query | `media-cache`, like every other query, with a query-plan test; any index goes in the layout's deferred group (§4.8) | The index is built only once the layout is selected, off the startup path. Until then, the parts that need it come back empty, and a `Refresh` fills them in. |
| A faster version of an existing rule | The existing rule gets faster, for every caller | Never a second implementation (C4). A layout's needs may change how a rule is computed, never what it decides; the rule's existing tests are the proof. |
| Data the mirror doesn't hold | A source in the core, and an `Option` field that is `None` when the source isn't available | See §8.3.1. |
| A link to another screen | `onNavigate(Screen)` (§5.3) | One callback for every destination, so a new link never widens the contract. |
| A new screen to link to | An ordinary `Screen` outside `ui/home/`, with its own spec | A destination is composed only when opened, so it costs nothing on Home. It isn't part of any layout, and any layout may link to it. |
| Its own settings | A struct in its Rust module (§4.7); rows via `spec.SettingsRows()` | Its settings sit with its code. |
| Its own strings | `strings_home_<layout>.xml` (§5.5) | Its text sits with its code. |
| Progressive mounting, extra perf marks | Its own package | Presentation that belongs to the layout alone. |
| The side menu hidden or changed | Not allowed | The drawer and its spine are owned by the host and wrap every layout (§5.3). |

#### 8.3.1 Data the mirror doesn't hold

- **The core is the only place data comes from.** A layout never reaches the
  server itself; the core fetches, stores and decides, like any other data
  (docs/01).
- **Prefer data that is already mirrored.** An estimate built from mirrored
  fields is acceptable when its known errors are stated in the layout's own
  spec.
- **An optional server feature, such as a plugin, is detected, never
  required.**
  - When present, it may replace an estimate.
  - When absent, the layout degrades: the panel uses the estimate, or is
    hidden.
  - A Home layout never refuses to work against a plain Jellyfin server.
- **A new local store** (for example, a history the core records itself) is a
  product decision for the layout's spec. It is never a side effect of the
  architecture.

### 8.4 Re-check of §§4–6 against both layouts

This is P1's outcome. Each piece is kept only if the two real layouts need it.

| Piece | Classic | Intuitive | Verdict |
|---|---|---|---|
| `HomeSnapshot` enum | hero + shelves | now panel, never-started row, week | **Keep.** The records share nothing but `Card`. |
| `HomeFeed<T>` | change-driven refresh, warmup, resume, PiP stop | the same triggers | **Keep.** |
| Home's own ViewModel store | needed to switch | needed to switch | **Keep.** |
| `blocks.rs` | resume, next up | resume, next up | **Keep, narrowed** to those two rules (§4.5). Favorites and latest move to Classic. |
| Boundary test | | | **Keep.** |
| Host contract | unchanged | links to Search and Browse | **Changed:** `onNavigate` (§5.3). The drawer still wraps both. |
| Masthead | floats over the hero | fixed top bar with buttons | **Layout-owned.** The wordmark and clock are shared in `common/`. |
| Progressive mounting | yes | no | **Classic-only.** |
| Next-episode rule | n/a | needs a cheaper form | **One implementation**, made faster for both callers (§8.3). |
| Browse | n/a | links to it | **An ordinary screen**, outside the layouts (§8.3). |
| Watch-time data | n/a | not in the mirror | **Core source, optional**, and the panel hides when there's none (§8.3.1). |

## 9. Verification

### 9.1 Tests

Rust:

- **Dispatch.** Each requested `HomeLayout` yields its own `HomeSnapshot`
  variant, whatever `Settings.home_layout` holds. With no mirror open, the
  requested variant comes back with an empty record.
- **Settings.**
  - An unknown `home_layout` string loads as Classic, and every other setting
    in the same file survives.
  - A missing field loads as Classic.
  - Every variant round-trips through serde.
- **Classic builder.** Carries over today's `next_up_beside_resume` tests.
  Adds tests for shelf order, empty-shelf dropping, the hero rule, and hidden
  libraries.
- **Deferred index groups** (§4.8).
  - A group is built only when first requested, and the request is
    idempotent.
  - A never-requested group doesn't exist in `sqlite_master`.
  - While a group isn't ready, the builder returns the dependent parts empty.
  - Finishing a build emits exactly one `Refresh`.
  - The favorites group behaves exactly as it does today.
- **Query plans.** Every query a builder runs has a test that pins its index
  and fails on a full scan of `items` (§4.8).
- **Caps.** Every list in a builder's record is capped (§4.9 rule 2). A test
  runs each builder over a mirror larger than every cap.

Kotlin:

- **`HomeFeed`.**
  - The refresh and sync tests moved from `HomeViewModelTest`.
  - It requests its own layout.
  - A prefetch of another variant is dropped and followed by its own request
    (§6.3).
- **Each layout's extractor** accepts its own variant and rejects the others.
- **`homeLayoutTransition`** (§6.4):
  - No swap while Home isn't top.
  - No swap when the committed layout equals the drawn one.
  - Exactly one swap when Home becomes top after a change.
  - `Retire` as soon as committed differs from drawn.
  - `Unretire` when they match again before a swap.
  - Recomposition during one swap doesn't clear a store twice, and doesn't
    mount an intermediate committed value.
- **Fakes behave like the FFI.** Every fake gateway call used by these tests
  blocks a thread inside `withContext(Dispatchers.IO)`, gated by a latch. A
  fake that merely suspends would cancel cleanly, which the real native call
  can't do, so it would prove nothing.
- **Retired feed and swap** (C3), with that blocking fake:
  - Retire the feed while each kind of call (snapshot, chrome read, status
    poll) is blocked. Then emit change events, a stop epoch and a host resume.
    No new call starts.
  - Swap while the old snapshot is blocked. The new feed's first request
    starts at once, without waiting. Then release the old call: its result is
    never applied, and no exception escapes.
  - A session reset while a call is blocked applies no late result.
  - Un-retiring issues no request while Home is hidden, then exactly one
    catch-up request when Home becomes top.
- **Labels:** every `HomeLayout` entry has a non-zero `spec.label`.
- **The boundary test** (§5.6).
- **Classic presentation decisions:** top inset and mount counts, as today.

### 9.2 Startup protocol (C1)

The scenario is matched, so snapshot timings aren't read out of context:

- **Build.** A release build installed through `./build.sh install`, with
  `speed-profile` confirmed (docs/10 "Profile status").
- **Device.** The TV is idle: no playback and no PiP window.
- **Account and data.** The same signed-in account, and the mirror fully
  synced before the first run.
- **Per run.**
  1. `am force-stop`, then wait 5 s.
  2. `am start -W`.
  3. Collect the `perf startup` and `ffi.homeSnapshot` lines (docs/10).
  4. Return to the launcher.
- **Metrics.** For each run, measured from `process.start`:
  - `home.dataReady`, `home.contentDrawn` and `home.focusReady`.
  - The duration of `ffi.homeSnapshot`.

Each comparison is run in three install blocks, **A, B, then A again**. Each
block is 12 cold starts, and the first 2 of each block are discarded. The
second A block measures drift within the session.

The comparison **passes** if, for every metric:

- the two A blocks' medians differ by at most the threshold (otherwise the
  session is too noisy, and it is rerun), and
- B's median differs from the pooled A median by at most the threshold.

The **threshold** is the larger of 10 ms and 2% of the A median.

A fail is confirmed by one full rerun before it blocks anything. Raw lines
and the computed medians go into the change's commit message or PR, not into
this doc.

Comparisons:

- **G1, groundwork neutrality.** A = the last release, B = the groundwork
  build. Classic is selected in both.
- **G2, cost of an inactive layout.** A = the build containing only Classic;
  B = the same source plus the new layout. Classic is selected in both. The
  only difference between the two builds is the inactive layout's presence:
  its code, its dispatch arms, and the classes the wildcard profile adds for
  it. That presence is exactly the cost C1 bounds. Pass means C1 holds.
- **G3, the new layout's own cost.** In the same session as G2, the B build
  also gets a block with the new layout selected, after its index group has
  been built. That block is compared with the B build's Classic block using
  §4.9 rule 4's budget. A separate run with the group not yet built is
  reported, not gated: those parts come back empty by design (§4.8).

### 9.3 Memory check (C2)

For **every** layout L, and with every other layout as O, two heap dumps are
taken (`am dumpheap`, with a forced GC first where the platform supports it),
each after Home has been top and idle for 5 s:

- (a) after a cold start into L;
- (b) after an L → O → L switch.

With two layouts, that is four dumps: Classic cold, Classic → Intuitive →
Classic, Intuitive cold, and Intuitive → Classic → Intuitive. Each is read
against the release build's R8 mapping.

**Pass** means every dump shows zero instances of any inactive layout's
stateful types: its ViewModel, UiState, snapshot record, and any other class
in its package except the spec object.

The dumps also list which of the inactive layout's classes are **loaded**.
That list is reported as evidence for or against the lazy-loading hypothesis
in §5.2; it isn't a pass/fail item.

**Limit of the check.** Generic Compose objects (`MutableState`,
`LazyListState`) can't be attributed to a layout by class. For those, the
guarantee comes from how the switch is built: the old spec's composition
leaves (§6.4), and its store is cleared. The heap check covers every type a
layout owns.

### 9.4 Device check

**The real FFI path.** The fakes in §9.1 imitate the threading, not the
native library. So a debug build adds a delay switch to
`CoreGateway.homeSnapshot`: a system property that blocks the IO thread for a
set time before the native call, like a slow core. Release builds contain no
such switch. On the emulator, then on the TV, with a 3 s delay:
- switch layouts while the old snapshot is blocked;
- the new layout's skeleton appears at once, and its content lands when its
  own call returns;
- the diagnostic log shows the old call finished and was discarded;
- the §9.3 heap check still passes after the old call has finished.

After install, open each layout on the TV and check:
- focus restore and the drawer closing;
- a return from Detail;
- a switch in Settings, including flipping the picker back and forth before
  leaving.

### 9.5 Layout-switch latency

There is no historical baseline for this. Past Home timings around a session
change include a session reset and a mirror reopen, which a layout switch
never does, so they aren't comparable.

- **The metric.** A new docs/10 mark, `home.layoutSwitch`, is a duration from
  step 4.2 of the §6.4 swap to the new layout's first frame showing real
  content.
- **The protocol.** The same device and data rules as §9.2. Ten switches in
  each direction, each starting with Home idle and synced.
- **The gate.** A warm process shouldn't be slower than a cold one. So each
  direction's median must not exceed the target layout's own cold-start
  interval from `root.stackReady` to `home.contentDrawn`, measured in the same
  session, plus the §9.2 threshold.
- **The baseline.** The first passing measurement in P4 is recorded in its
  commit, and later changes compare against it with the same threshold.

## 10. Phases

- **P0 Standalone cleanup, any time.** Delete Kotlin's duplicate Next Up dedup
  (§3 item 1). It is independent of layouts.
- **P1 Check the architecture against a second real layout.** Record what
  Intuitive asks of it (§8.2) and write the general rules (§8.3). Then
  re-check §§4–6 against both layouts, recording for each of the following
  whether Intuitive needs it:
  - the snapshot enum;
  - `HomeFeed<T>`;
  - Home's own ViewModel store;
  - the shared blocks;
  - the boundary test.

  Drop whatever neither layout needs, then review the revised spec.

  *Status:* done. §8.4 records the outcome. Intuitive's open product questions
  don't block P2, because they belong to its own spec.
- **P2 Groundwork, no visible change.** Only what survived P1:
  - **Rust:** the `home/` module, `HomeLayout` with only `Classic`,
    `home_snapshot(layout)`, `blocks.rs`, and Classic's rules (shelf order, the
    hero, favorites, latest) moved into `classic.rs`. In `media-cache`, the
    favorites index mechanism becomes the generic deferred index groups
    (§4.8).
  - **Kotlin:** `HomeFeed`, the split of `HomeScreen.kt`, `HomeHost` and
    `HomeLayouts.kt`, and the boundary test.
- **P3 Measure.** §9.2 G1. P2 ships only if it passes.
- **P4 A new layout.** Its product spec is written first. It is then built by
  §7's table. The first new layout also brings the switching machinery (§6.4,
  §5.4's retirement and generation, and the Settings picker), Home's store
  holder (§10.1 step 5), and the Settings rows (§10.1 step 6). It is then
  checked with §9.2 G2 and G3, §9.3, §9.4 and §9.5.
  A screen it links to that doesn't exist yet (Intuitive's Browse) is its own
  feature, with its own spec.

### 10.1 Migrating Classic (P2 in detail)

P2 must leave Classic identical: the same pixels, the same focus behaviour,
the same timings. It lands as a sequence of commits, each of which builds and
passes `./build.sh test` and `./build.sh check` on its own:

1. **Deferred index groups** (`media-cache`, §4.8). The favorites mechanism
   is generalised, with no other change. The existing favorites tests pass
   unmodified.
2. **The Rust `home/` module.**
   - Add `blocks.rs`, `classic.rs`, `HomeLayout` (Classic only), the
     `HomeSnapshot` enum, and `home_snapshot(layout)`.
   - Shelf order, empty-shelf dropping and the hero move into `classic.rs`.
   - Kotlin changes only enough to compile: the gateway, `LaunchWarmup`, the
     fakes, and a temporary mapping from `ClassicHome` back into today's
     `HomeUiState`. `HomeScreen` is unchanged.
3. **`HomeFeed` and `HomeChrome`.** Extracted from `HomeViewModel`, whose
   remaining code becomes `ClassicHomeViewModel`. The temporary mapping is
   removed. The Kotlin dedup copy goes here, if P0 hasn't already removed it.
4. **The UI split.** `HomeScreen.kt` becomes `common/` and `classic/`, behind
   `HomeHost` and `HomeLayouts.kt`, along with the boundary test.
5. **Home's own ViewModel store** is deferred to P4, along with
   `homeLayoutTransition`, retirement, `onHomeLayoutSelected` and the swap.
   - Today Home's ViewModel lives in the Activity's store, which survives the
     Activity being recreated. A TV can do that when the display mode switches
     around playback.
   - The per-entry owners in `screenStoreOwners` are `remember`ed, so they
     don't survive it. Moving Home onto one of them now would make Home reload
     after every such recreation, and with one layout there's nothing to swap.
   - P4 therefore keeps the per-layout Home stores in an Activity-scoped holder
     (a ViewModel holding one `ViewModelStore` per layout key, clearing them in
     `onCleared`), so they survive recreation the way today's store does.
     `resetSessionState` already clears the Activity store, and that clears
     the holder with it.
6. **Settings rows** move in P4, with the picker.
   - Moving the Classic-only rows into `ClassicLayout.SettingsRows()` changes
     their order in the Home section, which is a visible change.
   - With one layout, "shown only while Classic is active" means always shown.
   - So the move, and the `label` and `SettingsRows()` members of
     `HomeLayoutSpec`, land together with the picker. The Home section's row
     order is then a product decision for that change.

**Behaviour is pinned by ported tests, not new ones.** Moving a decision
across the boundary moves its tests with it:
- `ShelfAssemblyTest`'s order and empty-shelf cases, and `heroCard`'s cases,
  are ported case for case into Rust tests of `classic::build`.
- `HomeViewModelTest`'s refresh, prefetch, sync and resume cases become
  `HomeFeed` tests.
- No case is dropped without a note in the commit saying which case now
  covers it.

**Shelf identity doesn't change.** Classic's focus memory keys
(`shelf:<id>/card:<id>`), its per-shelf `LazyListState`s and its per-shelf
list reuse are all keyed by today's shelf ids: `resume`, `next-up`,
`favorites` and `latest:<viewId>`. A pure `ShelfSource.key()` in
`classic/` returns exactly those strings, pinned by a test. So a focus
restore and a scroll position survive both the migration and every refresh,
and `mergeLatestShelves` generalises to reuse keyed by that same
`ShelfSource.key()`.

**Comparable measurements.** The FFI timing label stays `ffi.homeSnapshot`,
so §9.2 G1 compares like with like against the last release.

**Every call site of the changed API is updated in the same commit.** From
the tree today:
- **Rust:** `core/ffi/src/object.rs` (`home_snapshot` and its tests) and
  `core/ffi/tests/live_sign_in.rs`.
- **Kotlin:** `data/CoreGateway.kt`, `data/LaunchWarmup.kt`, and
  `ui/home/HomeViewModel.kt`.
- **Tests:** `FakeCoreGateway`, `FakeHomeGateway`, `LaunchWarmupTest`,
  `HomeViewModelTest` and `ShelfAssemblyTest`.

The remaining mentions of `homeSnapshot` in `MainActivity` and
`PlaybackReports` are comments only; they're updated when their wording goes
stale.

**Docs.**
- docs/07 §1 stops citing old `home.rs` line numbers and names
  `home/classic.rs` as where the order is decided.
- docs/10's `LaunchWarmup` paragraph gains the layout read (§6.1).
- docs/13 is unchanged, because nothing a viewer sees changes.

**Done** when G1 passes, the §9.4 device check on the TV matches 0.1.4, and
the reviewers have run on each commit.

## 11. Known costs and risks

- **Baseline profile.** `app/src/main/baseline-prof.txt` wildcards
  `tv/jellybeam/**` with `HSPL`. Every layout's classes are therefore
  compiled ahead of time and flagged as startup classes, whether or not they
  are active. This costs APK and odex size. It shouldn't cost startup time,
  since pages that are never executed are never touched, but that is
  unproven. §9.2 G2 is what verifies it. If it fails, the
  fallback is per-layout profile rules: only Classic's package in the startup
  set, the others hot only.
- **APK size.** Each layout adds its code and strings to every install.
- **The settings read on the warm-up path.** `LaunchWarmup` now calls
  `getSettings()` before it prefetches the snapshot (§6.1). The read is
  in-memory, but it is a whole-record marshal on the startup-critical path.
  §9.2 G1 measures it. If it shows, the fallback is a one-field
  `home_layout()` getter.
- **Index write cost.** Each built group slows sync writes a little, for as
  long as it exists. §4.8 limits this to layouts that have actually been
  selected. A viewer who tries every layout carries every group.
- **A switch can overlap one old build.** An old call still inside the core
  runs alongside the new layout's first request (§6.4). This costs at most one
  build's CPU, once per switch. §9.5 measures it, and §9.4 exercises the worst
  case with a forced delay.
- **Persisted and committed values can differ.** `Settings.home_layout` and
  `JellybeamRoot.homeLayout` differ from the moment a layout is picked until
  its write lands, and forever if that write fails. That is harmless, because
  nothing reads the persisted value back during a session (§6.1). The cost is
  that a failed write silently reverts the choice at the next cold start. The
  existing `settings.change` diagnostic records the write, but not its failure.
- **FFI shape change in P2.** `HomeSnapshot` changes from a record to an enum
  and `ClassicHome` carries `HomeShelf` lists. Kotlin code that constructs the
  old record positionally (fake gateways, tests) is rewritten in the same
  change. This is an internal break; there are no external consumers.
- **Home's ViewModel store.** Moving it off the Activity store changes when
  `onCleared` runs, from "session reset" to "session reset or layout switch".
  The existing clear points still cover the reset path. The per-layout stores
  sit in an Activity-scoped holder, not in `screenStoreOwners`, so Home still
  survives the Activity being recreated (§10.1 step 5).
- **Drift in shared blocks.** The parameter-or-move rule in §4.5 limits it.
  Code review enforces it; no tool does.
- **P2 generalises the favorites index mechanism.** `FAVORITE_INDEX_SQL`,
  `BuildFavoriteIndexes` and `favorite_indexes_ready` become the first
  deferred group (§4.8), with no change in behaviour. The existing favorites
  tests pin that.

## 12. Rejected alternatives

- **Compose every layout and hide the inactive ones.** It breaks C2 and C3.
- **A runtime registry** (ServiceLoader, DI multibinding, or a list of spec
  objects). Building the list loads every layout, and the exhaustive `when`
  already gives compile-time completeness.
- **An FFI function per layout** (`classic_home()`, `intuitive_home()`). Each
  one adds `CoreGateway`, real-gateway and fake-gateway methods, and
  `LaunchWarmup` would need its own Kotlin `when` to choose which to prefetch.
  That is more dispatch sites, not fewer.
- **A macro generating `HomeLayout`, `HomeSnapshot` and the dispatch.** It
  saves about two lines per layout, but hides the uniffi types from grep and
  the IDE.
- **A Gradle module per layout.** It gives real `internal` enforcement and
  isolated incremental builds, but costs Gradle configuration time and forces
  shared UI into its own module too. Revisit if a layout grows past a few
  thousand lines.
- **A Kotlin `HomeLayout` enum mirroring Rust's.** It would duplicate the
  generated type.
- **One configurable shelf engine.** Layouts are different screens, not
  different orderings of the same shelves (§1).
- **The core choosing the layout from settings.** The core would build
  whatever `Settings.home_layout` held when the call arrived. The Settings
  write is fire-and-forget, so a snapshot requested right after a pick could
  still be built for the old layout, and the new layout's Home would wait on a
  change event that may never come. Passing the layout explicitly (§4.3)
  removes the race.
- **Re-reading settings when Settings leaves the stack.** It has the same
  write race, from the other side. The root callback in §6.4 commits the
  choice synchronously instead.
