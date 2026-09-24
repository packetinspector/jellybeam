# 15 — Focus and selection — the one rule

Single source of truth for where the D-pad cursor lands when a screen is
entered, re-entered, or returned to, and for how "the cursor is here" is
told apart from "this is the current value". Every screen and every OSD
surface follows this file. A screen that needs to deviate documents the
deviation *here*, in §7, not in its own code comments.

Vocabulary: **focus** = where the cursor is (Compose focus, or the OSD's
`focusedButton` identity). **Selection** = the current value of a setting,
tab, chip, or picker. **Current** = the item that is playing / active
right now, independent of both.

---

## 0. Diagnosis (read from source, every claim cited)

The four reported symptoms are one gap: no shared rule for initial focus,
and no shared place where "what was focused" is stored. Each screen wrote
its own becoming-top effect, and three of them restore a *fixed* control.

### 0.1 Where focus state lives today, and where it is lost

The navigation layer already does the hard part: every back-stack entry
stays composed as a `RetainedScreenLayer` (`MainActivity.kt` ~459), so
`remember` state, list scroll positions and the real Compose focus target
all *survive* a push. Nothing is lost by disposal. Focus is lost by being
**overwritten** the moment `isTop` flips back to true:

| Surface | Focus recorded where | Becoming-top effect requests | Point of loss |
|---|---|---|---|
| Series / movie / episode Detail (`ui/detail/DetailScreen.kt` 749-808, 324-339, 1212-1227) | Nowhere. Two fixed `FocusRequester`s per screen: primary pill and the *selected* season chip. | The seed target again: primary pill if one exists, else selected chip (`seedFocusRequester`, 793). | `LaunchedEffect(isTop)` 801-808 fires on every false→true and re-seeds unconditionally. The episode card, cast member or chip the viewer was on is never recorded, so it cannot be restored. Same shape ×3 item types. |
| Settings (`ui/settings/SettingsScreen.kt` 153-167) | Only `activeSection` (plain `remember`). One `FocusRequester` per **rail** row; none for any pane row. | `railFocusRequesters[activeSection]`, always a rail row. | 163-167: `LaunchedEffect(isTop, isLoading)` re-seeds the rail on every return, so a toggle/chip focused in the pane is lost. Section switch disposes the pane subtree outright (242-250). |
| Playback OSD (`player/PlaybackScreen.kt`) | `focusedButton: ControlButton?` (760), a manual identity, not Compose focus. Survives hide/show. | Reveal-from-hidden always sets `PLAY_PAUSE` (`revealOsdToPlayPause`, 838-843, spec §10). | Submenus: Speed/Chapters leave `focusedButton` alone, so they return correctly by omission. The track picker nulls it on open (1176, 1220) and reseeds `PLAY_PAUSE` on close (862-863 and the safety net at 947-951). |

Surfaces that already do it right (stable-ID memory + fallback): Home,
Library, Discover ×5. Surfaces that reset to a fixed control: Search,
Discover Search, Detail ×3, Settings. Surfaces outside the retained-layer
scheme (Sign In, Server Management) replace the whole tree and have no
return path, so no restore is expected there.

Storage class today: every memory object is plain `remember`, so none
survives a configuration change or process death. `NavBackStack.reset()`
has no callers; sign-out sets the stack to null, which disposes every
layer and every memory (`MainActivity.resetSessionState`, ~702).

### 0.2 Season drift — actual cause

Not index-vs-ID: every season match is by id (`DetailScreen.kt` 1054-1057,
`DetailViewModel.kt` 351-357). Not a mis-attached requester. Two verified
mechanisms, both real:

1. **Two-pass resume-season selection that can disagree with itself.**
   `DetailViewModel.init` launches `loadSeasons` and `loadAllEpisodes` in
   parallel (169-174). Both call `updateResumeSeasonIfNeeded` (229, 321).
   Before episodes land, `resolveResumeSeason` (`DetailFormatting.kt`
   333-346) falls back to *the first non-special season whose
   `unplayedCount != 0`*; once episodes land it re-resolves from
   per-episode `positionTicks`/`played`, where an in-progress episode
   wins over the first unplayed one. If the two signals disagree (an
   in-progress episode in S4 while S1 still has unplayed episodes; or a
   server that returns no season-level unplayed count, which makes the
   first pass pick S1), the filled chip visibly jumps to S4/S5 shortly
   after the page paints. The latch `userSelectedSeason` (136) only stops
   the second pass after an Enter on a chip; D-pad focus on a chip does
   **not** select it (`SeasonChip` selects on click only, 1099), so
   moving the cursor to Season 1 does not protect it.
2. **One `LazyListState` for every season's episode shelf**
   (`DetailScreen.kt` 1124, unkeyed `rememberLazyListState()`). Switching
   seasons keeps the previous season's scroll index, so the shelf can open
   deep into the wrong list. This does not move the chip; it moves what
   the viewer sees below it.

Telling the two apart from source alone isn't possible: if the *chip* reads
4/5, it is (1); if the chip reads 1 and the shelf is scrolled, it is (2).
Both are fixed in §5.

### 0.3 Settings highlight clipping — actual cause

`Modifier.focusRing` (`ui/cards/CardArt.kt` 254-266) draws the 3dp ring
**outside** its layout bounds at a 4.5dp outset (3dp/2 + 3dp gap, 76-77).
Settings rows sit in a plain `Column` with `Arrangement.spacedBy(ROW_GAP =
4.dp)` (`SettingsControls.kt` 47) and each `RowCard` paints an **opaque**
`SurfaceRaised` background (63-65). Siblings paint in order, so row N+1's
opaque fill overpaints the bottom 0.5dp-plus of row N's ring. The last row
has no successor, so only it shows a full surround. No `clip`, no
`graphicsLayer`, no scale is involved. The same overpaint happens anywhere
`focusRing` is used in a stack tighter than 4.5dp (drawer rows, chip
flows); Settings is just where it is visible against a filled row.

---

## 1. Visual states

The app already has one focus vocabulary and it is used on eight screens:
a 3dp `Pistacchio` ring at a 3dp gap outside the control (`focusRing`),
plus, for art, a 1.04 scale and a brightness lift. docs/07 §4 and docs/11
§14 both make it the *only* focus treatment ("one treatment for cards,
buttons, tabs, no variants"). That decides the question below.

### 1.1 A ring for selection, considered and rejected

Evaluated against Android TV and two shipping clients:

- **Android TV / Compose TV Material.** Focus is a border plus scale;
  selection is a *container tone change* (filled or tonal chip, list item
  `selected`) with a trailing check glyph. The two never share a shape.
- **Wholphin.** Focus = scale + border. The chosen item in a picker is a
  filled/tonal row with a check; the two stack cleanly when both apply.
- **Infuse (tvOS).** Focus = lift + parallax on the item. The current
  value in any list is a check mark on the row; the currently playing
  item gets a small "now playing" glyph. Never a second outline.

A second ring would collide with the one focus signal the whole app is
built on. Told apart by colour or weight, it becomes a second outline
language the eye has to decode from three metres, and it inherits the
outset-clipping problem in §0.3 twice over. Selection must be a
**different shape class** from focus, not a variant of it.

### 1.2 The states, named

| State | Meaning | Treatment |
|---|---|---|
| **Focused** | the cursor is here, not necessarily chosen | `focusRing` (3dp `Pistacchio`, 3dp gap) on chrome; `ArtBox` ring + 1.04 scale + brightness lift on art. Unchanged. |
| **Selected** | this is the current value | *Controls* (chips, toggles, tabs): filled `Pistacchio` with `Notte` text, as today. *List rows* (pickers, menus, radio-style options): a `✓` in a fixed 20dp leading gutter, `Pistacchio`, text stays `Panna`. The gutter is always reserved so rows never shift when the check moves. This is the track picker's existing convention (`TrackChoiceRow`), made universal; the Speed menu's trailing "CURRENT" word migrates to it. |
| **Focused + selected** | cursor on the current value | both at once. On a `Pistacchio`-filled control the ring is `Sheen` (docs/12 §0.4 already says so; `SeasonChip` does not yet comply and is fixed under this rule). |
| **Current** | playing / active now, independent of focus and selection | a 6dp `Pistacchio` dot beside the label, the drawer's existing current-section marker (`DrawerRow`). Used for: the current chapter in the Chapters menu (today it has no affordance at all), and the current episode in the episode-page sibling rail (docs/11 §15 currently hands it the *focus ring*, which is exactly the conflation this file exists to remove; that sentence is superseded). |

No new colours, widths, or animations. Everything above is composed from
tokens and treatments already shipped.

---

## 2. Initial-focus precedence

Applied by one mechanism on **every** entry of every screen and nested
surface, in this order. The first rule whose target exists wins.

1. **Restored focus.** Returning to this surface, and the element that
   had focus when we left still exists (matched by its stable key). Never
   by list index.
2. **The selected / current item**, resolved by stable id. For a
   season row: the selected chip. For a picker: the checked row. For a
   settings section: the active rail row.
3. **The surface's declared fallback.** Exactly one per surface, stated in
   §7. It is always the *primary action* the screen exists for, because a
   fresh entry with no history is the one moment the viewer has no place
   of their own yet: Home → hero primary (else first shelf cell); Library
   grid → first cell; Detail → primary pill (Play/Resume); on a series page rule 2
   (the selected season chip) is reached first; Settings → the active rail row; Search → the
   field; OSD reveal → Play/Pause.

**Rule 2 also governs entering a group.** A D-pad move *into* a group of
options (the season chip row, a Settings chip row, a picker) lands on the
group's selected item, never on whichever member is spatially nearest
(on device: Down from Resume landed on season 4 with season 1
selected). Implemented as a focus group whose `enter` target is the
selected item; it yields while a programmatic restore is in flight, so a
remembered non-selected member (rule 1) still wins on a return.

Why a fixed primary rather than "first focusable": "first" depends on
layout order and on which async section painted first, which is how a
dead remote and the season race both happened. The fallback must be a
thing the screen *names*, not a thing the framework finds.

A restore never scrolls the viewer somewhere they were not: the target's
own `bringIntoView` is the only scroll a restore may cause. Corollary for
rule 3 on a *return* whose saved key no longer resolves (the item was
removed by a refresh): a scrolled list falls back to its first *visible*
cell, not its first cell, so a deep grid keeps its place. Only a fresh
entry lands on the named primary.

---

## 3. Lifecycle — when saved focus is kept or discarded

| Event | Saved focus |
|---|---|
| Push forward, then Back (pop) | **kept** and restored (rule 1) |
| Forward navigation *to* a screen already on the stack (e.g. a second push of the same Detail) | a new entry, no history: rule 2 then 3. The older entry keeps its own. |
| Returning from PlaybackActivity (Activity resume, stack unchanged) | **kept**; treated exactly as a pop (the existing `activityResumed` edge) |
| Content / library refresh while on or under the screen | **kept** if the key still resolves; otherwise rule 2, then 3. Never index. Implemented on Home, Library and Detail as a *refresh guard*: one frame after the screen's content values change, if the screen is top, resumed, seeded and holds no focus, an owned restore job runs the same `restoreNow` as a return (remembered key, then rule 2, then rule 3, then the screen's fallback). Ownership: one `RefreshRestoreOwner` per screen (Home, Library, Detail x3 bodies) cancels the job, and invalidates any trigger still waiting on its own one-frame settle, when the screen loses top, on pause, when the drawer opens (Home, Library, Detail) or the action panel opens (Detail); a freeze left by a cancelled *refresh* job does not block its replacement, while a navigation or drawer freeze still does (`refreshGuardEligible`), and `restoreNow`'s cancellation contract is unchanged. Library and Detail also read the drawer coordinator -- they gate eligibility on a plain `drawerOpen` flag rather than freezing their own memory, so `NavDrawerHost`'s generic close-time restore keeps placing focus for them as before. An in-place close (drawer closed without navigating on Library/Detail, action panel closed on Detail) releases a freeze that a refresh restore cancelled by the matching open left behind (`releaseCancelledFreezeInPlace`) -- otherwise nothing on that path would ever unfreeze the memory and later focus changes would go unrecorded. The release happens only while the screen still owns focus: a navigating-away drawer close, and a panel dropped for a navigation (Go to series, playback) or landing after the page lost top, keep the navigation freeze. |
| Configuration change | **kept** (memory is `rememberSaveable`) |
| Process death | **kept** for the top entry if the stack itself is restored; memory is saveable, so whatever survives with the stack restores with it. Nothing is persisted to disk for this. |
| Sign-out / switch server / `NavBackStack` cleared | **discarded** with the layers. Home re-enters cold (rule 3). |
| Section switch inside Settings | per-section memory: returning to a section lands on its last focused row (rule 1), first row of the section otherwise. |
| OSD hide → reveal | **deliberate exception**: rule 3 (Play/Pause). See §4. |

---

## 4. Nested surfaces

Any submenu, picker, sheet or dialog opened from a control returns focus
to **that control** when it closes, whatever closed it (Back, Menu,
choosing a value, or the surface closing itself). The invoker is captured
at the moment of opening, by the code that opens it, as the stable key of
the focused element *before* any focus is cleared or moved. It is stored
in the same memory object as the screen's restore target, so it survives
everything §3 says survives. If the invoker no longer exists on close
(e.g. the button set changed with playback state), rule 2, then 3.

**OSD reveal from hidden stays on Play/Pause.** With the OSD hidden, a
Select press reveals it without acting; the second Select toggles pause.
Restoring, say, Tracks there would turn the pause gesture into "open the
track picker". Chrome that hides *itself* is not a surface the viewer
left, so rule 1 does not apply to it; docs/12 §10 already says so and this
file keeps it. Everything the OSD opens (Speed, Chapters, track picker,
Library info, Stats) is a nested surface and follows this section.

Dialogs that trap focus (`focusProperties { exit = Cancel }`) still seed
their own first control on open; only their *close* is governed here.

---

## 5. Implementation contract

One utility, `ui/focus/FocusMemory.kt`, used by every retained screen and
by the Discover screens' existing helpers folded into it. No per-screen
restore code remains outside it.

- `rememberFocusMemory(entryKey)`: a saveable holder of `lastKey:
  String?`, `invokerKey: String?`, and a `frozen` flag (writes are ignored
  while the screen is not top, so a pop transition's focus churn cannot
  clobber the memory). Backed by `rememberSaveable`.
- `Modifier.focusKey(memory, key)`: registers a `FocusRequester` for `key`
  in the memory's live registry for as long as the element is composed,
  and records `key` as `lastKey` on focus gained. A `LazyRow`/`LazyColumn`
  item that scrolls out unregisters; a restore to a missing key first asks
  the owning list to scroll to it (`memory.scrollTo: suspend (key) ->
  Boolean`, supplied by the screen for its lazy lists), then retries.
- `FocusRestorer(memory, isTop, focusGate, selectedKey: () -> String?,
  fallback: () -> FocusRequester?)`: the single becoming-top effect. Runs
  §2 in order with `requestFocusUntilSuccess(focusGate)`. Screens delete
  their own `LaunchedEffect(isTop)` blocks and call this instead.
- `memory.captureInvoker()` / `memory.restoreInvoker()` for §4.
- The OSD keeps its `focusedButton` identity model (it has one key
  handler by design) but applies the same contract with a `ControlButton`
  invoker field: capture before clearing on open, restore on close.

Season drift: the resume season is resolved **once**, when the per-episode
signal arrives (or fails), never provisionally from season counts and then
revised; until then the chip row shows no selection. The episode shelf's
`LazyListState` is keyed by season id. Shown selection is final unless the
viewer changes it.

Ring overpaint: `focusRing` raises the focused element's `zIndex` so it
paints after its siblings. Geometry is unchanged; every stacked use of the
ring is fixed at once.

---

## 6. Settings subtext

Descriptive text is shown for the **focused** row (the option under the
cursor), not the selected value: the viewer is browsing; the text explains
what the thing they are pointing at does. Each `ToggleRow` and
`ChipFieldRow` reserves a fixed second line (`Archivo`, `Grigio`, 12sp)
at all times. Row heights are fixed per row kind (`Modifier.height`, never
a minimum): `ToggleRow` goes from 52dp to 64dp (label and description
stacked beside the switch); `ChipFieldRow` is 76dp (its 32dp chip line
sits above a full-width description line, with 8dp between them so a
focused chip's outset ring clears the text -- at 64dp the ring would touch
the card edge). The text fades in over 120ms (alpha only). No layout
property changes with focus, so neighbouring rows never move and the ring
geometry never re-triggers §0.3.

---

## 7. Audit — per surface

"Rule" = §2 order applied by `FocusRestorer` (or, for
the OSD, by `restoreInvokerFocus`). "Code" = compiles and unit-gated;
"device" = checked on a TV after install (release build; "not exercised"
rows share the code path of an exercised one and were not driven on
hardware).

Two consequences of the rule worth knowing, both intended by §2 but
visible on hardware for the first time:
- A fresh series page lands on the selected season chip, not the Resume
  pill, because rule 2 outranks rule 3. One Up press reaches Resume.
- The resume season is the earliest in-progress episode in series order
  (`resolveResumeSeason`), not the most recently played one. A stale
  partial watch in an early season selects that season even while the
  viewer is mid-way through a later one. Stable now (no jump), but the
  choice itself is a separate decision, out of scope here.

| Surface | Fallback (rule 3, fresh entry) | Restore on return (rule 1, by stable key) | Nested return (§4) | Code | Device |
|---|---|---|---|---|---|
| Home | hero primary button, else shelf 0 cell 0 | `hero:<action>` / `shelf:<id>/card:<id>`; drawer close uses the same path | n/a | pass | pass: Back from a Detail lands on the card opened; no extra scroll (control shot: plain Right-key navigation aligns the same way). refresh guard implemented: focus lost to a refresh is re-placed per this rule |
| Library grid | first cell | `card:<id>`; stale key → first *visible* cell | sort strip / index rail (docs/16 §6): not restore targets; Back and Down return to the **origin poster** recorded when the surface was entered; a return from Detail always finds the strip closed | pass | pass: fresh entry lands on the first cell; return path not exercised separately. refresh guard implemented |
| Search, Discover search | the field | `result:<id>` | n/a | pass | not exercised |
| Detail: movie, episode | primary pill | `action:*`, `overview:more`, `person:<id>`, `similar:<id>`, `up-next` | player return = resume edge, same path; the full-synopsis panel (opened from `overview:more`) takes focus while open, swallows Left/Right/Select, scrolls on Up/Down, and Back returns focus to that MORE stop by key | pass | pass: fresh entry on the primary pill; return from the player on the primary pill; Down from Play lands on MORE, Select opens the panel, Back lands back on MORE. refresh guard implemented |
| Detail action panel (docs/19) | the predicted row (docs/19 §1.2), else the first action row | n/a (a side panel, never on the stack) | Back, Left, Select or any action returns focus to the `···` door (`action:menu`); the in-place bulk confirm and the collections level return to the row that opened them. The invoker captured on open is cleared by that close (§4: consumed), otherwise the restore order (invoker first) sent every later return to this page to the door instead of the last card | pass | pass: predicted row on open; Back, Left and an action land on the door; Cancel on the confirm lands on the mark row; Go to series lands the series page on its selected chip. Device check: after panel close, move to another episode card, leave via HOME and resume: focus returned to the door (stale invoker); fixed |
| Detail: series | rule 2 wins on a fresh entry: the **selected season chip** (`season:<id>`); primary pill only when no season is selected | + `season:<id>`, `episode:<id>`; season resolved once, shelf state per season | as above | pass | pass: Back from an episode page lands on that episode card (log: placed by key); selected chip identical at 0.4s and 3.4s after open, no jump; fresh entry lands on the selected chip; Down from Resume lands on the selected chip (was the spatially nearest). refresh guard implemented |
| Settings | active rail row (rule 2 `rail:<section>`) | `<section>/<row>[/<chip>]`; per-section pane entry | n/a (no nested surfaces) | pass | pass: full ring on a middle stacked row; description under the label; leaving a section and re-entering it lands on the same row; entering a chip row lands on its selected chip; chip-row description on its own full-width line |
| Discover home | Search chip | `chip:*`, `shelf:<row>/card:<key>` | n/a | pass | not exercised |
| Discover grid / person / requests | first cell | `card:<key>`; stale → first visible | n/a | pass | not exercised |
| Discover detail | first action | `action:*`, `person:*`, `shelf:similar|recommended/card:*` | request dialogs → invoking action | pass | not exercised |
| Player OSD | Play/Pause on reveal (§4 exception) | hide/reveal: exception, not a return | Speed, Chapters, track picker, Library info, Stats → invoker | pass | pass: Tracks → picker (check on current tracks) → Back lands on Tracks; reveal from hidden lands on Play/Pause |

Checked and deliberately left alone:

- **Sign In, Server Management**: replace the whole tree (no retained
  layer, no return path); one-shot seeds stay. Nothing to restore.
- **Nav drawer panel**: Compose's own `focusRestorer()` inside the panel
  is correct for a surface that is never disposed; only its content
  hand-off goes through the shared path (Home's coordinator).
- **Next Up card, Still watching card**: single-purpose overlays with one
  primary; no history to keep.
- **`ArtBox`'s animated ring** on cards: a different draw path from
  `focusRing` (fade + scale inside a fixed slot); no overpaint reported.
- **docs/11 §15 sibling rail**: not ported, so its "current" marker has no
  surface yet; the rule for it is in §1.2 for when it lands.
- **Season chip Left/Right = select** (docs/11 item 12): today Enter
  selects; unchanged, out of scope.
