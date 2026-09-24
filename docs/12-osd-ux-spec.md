# 12 — Player OSD UX spec (as implemented)

Single current spec for the player OSD, read from source. Every number below
is read from
`app/src/main/kotlin/tv/jellybeam/player/PlaybackScreen.kt` (geometry, colors,
composition, key handling), `PlaybackOsdController.kt` (visibility rules),
`TrickplaySeekPreviewController.kt`, `SkipSegment.kt`, `BufferingInfo.kt`,
`NextUpCountdown.kt`, `StillWatchingCountdown.kt`, `EndsClock.kt`,
`PlaybackTimeFormat.kt`, `StatsSheetFormat.kt`, `LibraryInfoFormat.kt`,
`Chapters.kt`, and `core/ffi/src/settings.rs`. Where a claim is a deliberate
deviation from the design history below it says so in §19. When the code and
this doc disagree, fix one of them in the same change.

Design history, kept for the reference frames and the reasoning, not as
spec: `docs/jellybeam-osd-handoff/handoff/JELLYBEAM-TV-OSD-SPEC.md` (v2 layout,
density modes, sheets), `docs/jellybeam-osd-handoff/handoff-2/
JELLYBEAM-TV-OSD-CHANGES.md` (flat restyle, wins over the v2 spec where they
disagree). The "Still watching?" card's product brief was pruned; this doc
and the code are its record. Focus rules come from
`docs/15-focus-and-selection.md`; this doc only restates the OSD-specific
consequences.

Units are dp/sp on a 960×540dp canvas (1080p at 2×). Anything measured off
a 1920×1080 screenshot is halved.

## 0. Tokens

Colors, by the `JellybeamTheme` name the code uses (handoff name in brackets):

| Name | Hex | OSD use |
|---|---|---|
| Panna [cream] | `#F7E9CE` | title, elapsed digits, glyphs at rest, sheet values, pill text |
| Panna2 [cream-dim] | `#C9C0B2` | every secondary readout in the OSD band: total, remaining, ENDS, Direct Play line, Minimal's inline `S4 E18`, sheet meta line |
| Grigio [muted] | `#8C8478` | sheet interiors only: kickers, `BACK TO CLOSE`, grid labels; card eyebrows and captions; picker meta |
| Pistacchio [accent] | `#A8CB6B` | series kicker, played bar, the focused glyph, non-default tracks dot, Speed label when rate ≠ 1×, menu check/current markers, sheet rating figure, focused menu-row fill, card countdown rule fill and action label |
| Notte | `#14100D` | center flash disc, trickplay chip, text on a focused menu row, card corner scrim |
| SurfaceRaised | `#1D1814` | skip pill, buffering and reconnecting pills (all at `@0xee`) |
| SurfacePanel | `#261F19` | track picker, undo toast |
| Hairline | `#322A22` | track picker border |
| OSD surface (local) | `#0B0908` | menu fill, chapter ticks |
| OSD scrim base (local) | `#070605` | bottom scrim, sheet tint |

The OSD band's floor is Panna2. Nothing in the band is drawn in Grigio;
Grigio is for sheet and card interiors, where the flat tint guarantees the
contrast.

Type: Archivo for prose and titles; Martian Mono for numbers, kickers,
captions, menu rows, the Direct Play line. Minimum 11sp, with three
knowing exceptions listed in §19.

Glyphs are Canvas-drawn, no icon pack: strokes 0.8dp, the stats pulse
0.95dp, solid transport marks slim (a pause bar is 11% of the 22dp box).

Motion: the center flash curve in §10 is the only OSD animation. Show/hide
and focus moves hard-cut.

## 1. Layers, bottom to top

1. `PlayerView` (pure black shutter and background; three-layer letterbox).
2. Center flash (§10), independent of OSD visibility.
3. OSD chrome, only while visible and the session is `READY`: bottom
   scrim → trickplay preview panel → OSD block (title, time row, button
   row) → speed/chapters menu → stats sheet → library sheet.
4. Up-next card or still-watching card (never both), independent of OSD
   visibility.
5. Track picker.
6. Skip pill (hidden while either card shows) and the undo toast.
7. Buffering pill, reconnecting pill.

## 2. Geometry

- Safe inset 48dp left and right for everything, including card content, pills,
  menus and the picker -- except the card corner scrim (§13), which is deliberately
  unindented, pinned to the frame's own corner.
- OSD block: `left 48, right 48, bottom 27`, a vertical stack of title
  line(s), time row, button row with a 7dp gap between the three, in both
  density modes.
- Bottom scrim: full width, bottom-anchored, no blur. Full: 200dp tall,
  alpha 90% at the bottom edge, 83% at 36% up, 56% at 66% up, 0 at the top.
  Minimal: 160dp, 88% / 80% at 38% / 50% at 68% / 0.
- Control-zone datum: 140dp (Full) or 120dp (Minimal) above the screen
  bottom. Every floating overlay positions relative to it: trickplay panel
  and skip pill at datum + 16dp, up-next and still-watching cards at datum
  + 96dp, menus with their bottom edge at datum + 8dp.

## 3. Density modes

Settings › OSD, "Minimal | Full", default Full. (The v2 handoff named
these Clean / Nerdy and defaulted to Clean; the product default is Full.)

| Element | Minimal | Full |
|---|---|---|
| Series kicker line | — | ✓ |
| Direct Play line | — | ✓ |
| Total duration, ENDS wall clock | — | ✓ |
| Chapter ticks on the scrubber | — | ✓ (only with markers) |
| Speed button | — | ✓ |
| Playback stats button | — | ✓ |
| Chapters button | only with markers | only with markers |
| Episode previous / next | only when that neighbour is known | same |
| Transport, tracks, library info | ✓ | ✓ |

## 4. Title block

Names are server-configured display data and render verbatim. No release
tag stripping, ever (`OsdTitleFormat.displayTitle` is the identity).

- Full: a kicker row then the title, 3dp apart. The kicker is Martian Mono
  11sp Pistacchio, `Series name · S4 E18`, max 530dp, one line, ellipsis;
  absent for movies. The Direct Play line (§5) sits flush right on the
  kicker row whether or not there is a kicker. The title is Archivo 20sp
  Medium Panna, max 530dp, one line, ellipsis.
- Minimal: one row, bottom-aligned: the title (same style) then, 10dp
  later, an inline Martian Mono 11sp Panna2 `S4 E18` for episodes. No
  series name (it is in the library sheet).
- Movies: the bare title. No year: neither the playback plan nor the
  narrow OSD detail fetch carries one, and the full item fetch is only
  made when the library sheet opens.

## 5. Direct Play line (Full only)

One Martian Mono 11sp Panna2 line, non-focusable, nowrap, clipped:
`DIRECT PLAY · 1080p · HEVC · AAC 5.1`. The resolution is the stored
height (with a coarse width ladder when height is missing), the codecs are
the server's identifiers uppercased verbatim (`H264`, not `H.264`), the
audio channel label is Mono / Stereo / 5.1 / 7.1 / `n ch`; each piece
drops independently when absent, leaving bare `DIRECT PLAY`. While a
server transcode is playing (docs/18: Auto fallback or a bitrate cap) the
leading word is `TRANSCODE` in Ambra, the rest of the line unchanged.

## 6. Time row

One `Row`: left column, 14dp gap, scrubber (weighted), 14dp gap, right
column. Martian Mono 13sp throughout.

| Mode | Left column | Right column, right-aligned |
|---|---|---|
| Minimal | fixed 65dp, `13:52` Panna | min 65dp, `-7:11` Panna2 |
| Full | fixed 120dp, `13:52` Panna + ` / 22:04` Panna2 | min 160dp, `-7:11 · ENDS 10:14` Panna2 |

- Time format: `m:ss`, or `h:mm:ss` once an hour is reached. Remaining is
  always negative-signed and clamps at `-0:00`.
- The right column is a minimum width, not fixed: a movie's hour-long
  remainder or a 19-character episode reading would wrap at 160dp, so the
  column grows and the scrubber gives up the difference. Within one title
  the string length is stable, so the bar does not breathe.
- ENDS: device wall clock at which playback finishes, `now + remaining /
  rate`, recomputed each tick from position, duration and playback rate,
  so both a seek and a speed change move it. 24-hour locales get `HH:mm`;
  12-hour locales get `h:mm` with no AM/PM, no leading zero.
- Unknown duration (live or not yet reported): the right column is
  omitted, the left shows elapsed only, the scrubber is an empty groove.

## 7. Scrubber

Display only. It is not focusable, has no seek mode, no focus lift, no
thumb, no buffered fill. Position is the leading edge of the played fill.

- Canvas 13dp tall, track 2dp fully rounded in both modes, groove
  Panna@14%.
- Played fill Pistacchio, the accent's home in the player.
- Chapter ticks: Full only, only when the file has markers: 1dp wide,
  OSD-surface@75%, full track height, at each chapter's start fraction.
- Unknown duration: the groove alone, static (no shimmer; open item).

## 8. Buttons

One rule for every button: a 44dp invisible focus target with a 22dp glyph
centered in it. Rest = Panna glyph. Focused = the same glyph in
Pistacchio, nothing else: no disc, ring, wash, shadow or caption. Gap 10dp
within a cluster, plus 6dp on the inside of each episode button. The
button row is 44dp tall; the transport cluster is start-aligned, the
secondary cluster end-aligned.

Disabled does not exist. An unavailable action omits its button, so the
visible list changes with item type, neighbour availability, markers and
density.

| # | Button | Glyph | Shown when |
|---|---|---|---|
| 1 | Previous episode | bar + left triangle | episode with a known previous |
| 2 | Back *n*s | counter-clockwise arc + numeral | always |
| 3 | Play / Pause | triangle / two bars, follows `playWhenReady` | always; default focus |
| 4 | Forward *n*s | mirror of 2 | always |
| 5 | Next episode | right triangle + bar | episode with a known next |
| — | spacer | | |
| 6 | Speed | text `1×` Martian Mono 14sp Bold | Full |
| 7 | Audio & subtitles | rounded rect + two rows of lines | always |
| 8 | Chapters | three rounded segments | markers exist |
| 9 | Library info | circled *i* | always |
| 10 | Playback stats | pulse polyline | Full |

- The seek numeral is the configured interval, back and forward
  independently (Settings › Playback, presets 5 / 10 / 15 / 30 / 60s,
  default 10 / 10). It is set in code when the glyph is built; nothing
  hardcodes `10`.
- Speed label: `0.5× 0.75× 1× 1.25× 1.5× 2×`, trailing zeros trimmed. Any
  rate other than 1× tints the label Pistacchio even at rest, so a stray
  1.5× is visible without opening the menu.
- Tracks dot: a 4dp Pistacchio dot hugging the 22dp glyph's top-right
  corner (offset 7dp in from the target's corner) whenever a non-default
  audio or subtitle track is active.
- Episode neighbours resolve from the local mirror with one live fallback
  after load for items the mirror has not seen (deep links, new items).

## 9. Visibility, focus, keys

Owner: `PlaybackOsdController`, ticked every 250ms.

- Idle auto-hide after 5s of no input while playing. Pinned visible
  while: paused, track picker, either sheet, either menu, up-next card,
  still-watching card, trickplay preview showing, or a reconnect in
  progress. Unpinning and resuming each grant a fresh 5s window. Pinned
  overlays that render independently of the OSD (cards, picker) still pin
  the chrome under them.
- Reveal from hidden always lands focus on Play / Pause (or the first
  button if Play / Pause is somehow absent). A fresh session lands there
  too. This is the documented exception to focus restoration
  (`docs/15-focus-and-selection.md` §4).
- Select is two-stage: while hidden it only reveals; while visible it
  activates the focused button. Play / Pause activation is derived from
  `playWhenReady` at press time, so the flash and the glyph never
  disagree with the player.
- Hidden OSD, Left / Right: a silent seek by the configured interval, on
  the first press of a key only (key repeat is still ignored for this tap
  seek itself). No reveal, no flash, no trickplay lookup.
- **Hold-to-seek** (its product brief was pruned; this section
  and `player/GlideSeekController.kt` are the record): if the key is
  still held 500ms after the tap above, a continuous, accelerating "glide"
  begins from the post-tap position -- tiers of 6x/30x/120x/`duration/8`
  (floored at 120x), measured from glide entry. No `seekTo` is issued while
  gliding; release commits exactly one seek at the target. The glide
  surface (§5 of that PRD: a traversal bar, a trickplay tile sampled and
  prefetched per §11, and a chip with target time, signed delta, and
  speed) persists 3s after
  release, refreshed by any further tap or hold in that window, then
  hard-cuts away. Pressing the opposite key mid-glide commits the current
  glide and starts a fresh one the other way. This entire mechanism is
  hidden-OSD only (§9's Left/Right meaning while visible, above, is
  untouched) and never reveals or pins the OSD band itself.
- Hidden OSD, Back: not a wake key -- it reaches the Back chain and exits,
  **except while a glide is active** (see the Back chain below), where it
  cancels the glide instead.
- Hardware media play / pause keys are never consumed here, hidden or
  visible; they reach the MediaSession callback and do not reveal chrome.
- Page Up / Channel Up and Page Down / Channel Down (`PageKeys`, pure and
  tested; most Android TV remotes send the Channel rocker, not Page keys)
  are chapter transport, hidden or visible: Up jumps to the next chapter's
  start; Down to the current chapter's start, or the previous chapter's when
  within `Chapters`' 5s grace of the current start (the "previous track"
  rule). First press only. Hidden: never reveals or pins the OSD; visible:
  resets the idle clock and leaves focus alone. Either way a centre flash
  (`⏭` / `⏮` plus the chapter name when known) confirms the jump. Consumed
  as a no-op while any nested surface, the up-next or still-watching card,
  or a hold-to-seek glide is active, and when the item has no markers or
  nothing lies ahead.
- Any other key while hidden reveals to Play / Pause and is otherwise
  swallowed (the waking key does not also actuate).
- Visible, no nested surface: Left / Right move focus one button across
  the flat list, transport then secondary, no wrap. Up is a no-op that
  resets the idle clock. Down re-lands focus on Play / Pause if nothing is
  focused. The Menu key opens the track picker from any focused button.
- Every first-press key, including hidden seeks, media keys and Back,
  counts as viewer input for the still-watching guard.

Back chain, one level per press, resolved by `resolveBackAction`:
menu → track picker → sheet → still-watching card (Back means Stop, §13)
→ dismiss up-next card → cancel glide (hold-to-seek,
hold-to-seek's cancel rule -- no seek is issued, the
target is discarded) → hide OSD → exit playback. Escape and Menu close
the topmost nested surface the same way but do not exit.

Nested-surface focus return: the button that opened a speed menu, chapters
menu, track picker, library sheet or stats sheet is captured on open and
focus returns to it on close by any path (Back, Escape / Menu, or the
surface closing itself after a choice). If that button is no longer
visible, Play / Pause, then the first button.

Window focus loss and resume re-request the root focus node, so a system
dialog or a resumed activity never leaves the D-pad dead.

## 10. Center flash

112dp circle, Notte@0xaa, screen-centered, independent of OSD visibility.
Content: the play or pause glyph at 48dp Panna, or a `◀◀` / `▶▶` text
glyph at Archivo 48sp with the interval (`10s`) below in Martian Mono
22sp. Alpha curve: in over 80ms, hold to 400ms, out by 720ms; a new flash
restarts it. Fires on on-screen Play / Pause and Back / Forward
activation only, never on hidden-OSD seeks or hardware media keys.

Pause has no treatment beyond the pinned OSD and this one flash.

## 11. Seek preview (trickplay)

One panel serves both the on-screen Back / Forward activation (OSD visible)
and the hold-to-seek glide (§9, OSD hidden). Which tile covers a target is
always a Rust decision (`playback_policy::trickplay`); Kotlin fetches,
decodes and draws.

- Panel bottom = datum + 16dp; horizontally centered on the target's
  fraction of the width and clamped to the screen. Tile width from the
  Seek preview setting (§18): Small 160dp (the server's 320px thumbnail
  pixel-for-pixel at 1080p), Medium 240dp (default), Large 320dp; height
  follows the manifest's aspect. 2dp Panna border, 6dp radius.
- 6dp below it a chip: Notte@0xcc, 4dp radius, padding 8×4, the target
  time in Martian Mono 11sp Panna and, when a chapter covers the target,
  its name in Archivo 11sp Grigio.
- OSD-visible seek: visible 1.5s, restarted by each further seek; a seek
  with no tile clears the panel immediately (no chip-only fallback). The
  panel pins the OSD.

**Glide pacing** (`playback_policy::trickplay::glide_tunables`, one place
to tune):

- Direction bias: a thumbnail is the frame captured at `index * interval`,
  so taking the one at or before the target always shows a frame *behind*
  it, by up to a whole interval (10s on a default server). Travelling
  backwards that frame lies beyond the landing point in the direction of
  travel, so the viewer never lands past what they saw and it reads as
  exact; travelling forwards the same lag reads as having undershot.
  Taking the one at or after instead (`TileBias::Later`) simply moved the
  error to the other side: on device it then read as running ahead. So
  forward takes the **nearest** thumbnail (`TileBias::Nearest`, half up),
  halving both the worst case and the mean rather than choosing a side,
  while backward and every other case keep the one at or before
  (`TileBias::Earlier`), which device use calls exact. A target sitting
  exactly on a thumbnail takes that one under every bias.

  The residual error is the grid itself and is felt relative to speed, not
  in absolute terms: at 6x one 150ms sample advances the target under a
  second, so a 10s step dwarfs the movement and the image looks stuck
  against a crawling time readout; at 120x the target advances 18s per
  sample, more than a whole step, and the same quantisation vanishes into
  the motion. Only a finer server interval narrows it, which is not the
  client's to choose. The bias applies to
  the glide sample, the release tile, the key-down prime and the
  OSD-visible seek (by the sign of its interval), and the sheet want-list
  follows the biased tile so a boundary cannot leave the transport
  fetching the neighbouring sheet. A reversal commits the dying glide
  under *its* own direction.

- Sampled, not chased: while gliding the tile is re-sampled at most every
  `TILE_DWELL_MS` (150ms, ~7 changes/s, the most a viewer can read), so
  the work per second is the same at 6x and at 900x.
- Hold last: once a tile has shown in a gesture the panel never blanks; a
  newer tile replaces it when decoded, otherwise the last one stays and
  the chip carries the time. A gesture (from idle or by reversal) starts
  blank (a frame from the previous gesture would mislead) and primes the
  tap target's tile on key-down, so glide entry 500ms later already has
  it. Persist keeps the release tile. The OSD-visible seek never blanks:
  the previous tile holds until the new one decodes.
- Warm start: the manifest fetch and its sheet-under-the-play-position
  warm wait for the first rendered frame (or a 1500ms fallback) and then
  run once (fetch only, nothing shown), so neither competes with
  time-to-first-frame and the session's first glide still has no cold fetch.
- Sheet is the fetch unit, tile the decode unit: sheets are fetched as
  bytes (~0.3-0.7MB each) and each tile is region-decoded out of them, so
  a 3200x1800 sheet never exists as a bitmap and the tile fits any GPU
  texture cap.
- Bounded prefetch: on each sample Rust lists the sheet under the target
  and, when the glide reaches past it within `PREFETCH_LOOKAHEAD_MS`
  (1.5s) at the current rate, the adjacent sheet in the glide direction
  (the one crossed next, never the lookahead's endpoint). The transport
  keeps one fetch in flight and one queued; a new list replaces the queue;
  the sheet under the target always wins the slot (a farther prefetch is
  cancelled for it); an in-flight fetch that is merely off the list is
  abandoned only once the target is more than `ABANDON_SLACK_SHEETS` (1)
  sheets past it. Cancelling a fetch cancels the HTTP call, so an
  abandoned sheet stops consuming the link. At the fastest tier this is
  about one sheet per second.
- Release wins: on key-up the committed target's sheet jumps the queue and
  prefetches are dropped; that tile is the one the viewer reads.
- Budgets (`TrickplayTransport`): 4 sheets as bytes in memory, 8MB of
  decoded tiles (by bytes, not count), tiles subsampled on decode to at
  most 640px wide, 32MB of sheets on disk keyed by a hash of the URL (the
  token never lands on disk in the clear), 8MB per-sheet cap, 15s
  whole-call fetch timeout.
- Measured (docs/10 perf lines, `trickplay.firstTile` from key-down to
  the first tile shown, `trickplay.sheetFetch`, `trickplay.tileDecode`).
- Cadence at the start is the server's: with a 10s interval, tier 1 (6x
  for 800ms) covers under 5s of media, so after the first tile the next
  cannot change for up to 1.6s, then every 0.33s at tier 2, then every
  dwell from tier 3. A server interval of 5s halves those.

## 12. Speed and chapters menus

Shared container: 170dp wide, OSD-surface@92%, 1dp Panna@12% border, 5dp
radius, 10dp vertical padding, clipped to its corners. Centered above its
own button (measured from the rendered row), clamped to the safe inset,
bottom edge at datum + 8dp.

Rows: Martian Mono 14sp, padding 13×8, a reserved 20dp leading gutter,
optional trailing Martian Mono 10sp. Focused row = Pistacchio fill with
Notte text and markers. Up / Down clamp, no wrap.

- Speed: options `0.5× 0.75× 1× 1.25× 1.5× 2×`; opens with focus on the
  current rate, which carries a leading Pistacchio check. Select sets the
  rate and leaves the menu open. Rate is per session, not persisted.
  ENDS recomputes.
- Chapters: one row per marker, name or `Chapter n` when unnamed, start
  time trailing in Grigio; the playing chapter carries a 6dp leading
  "current" dot, derived per chapter change, not per tick. Opens at the
  first row. Select jumps to the chapter's start and closes the menu;
  focus returns to the Chapters button.

## 13. Cards (bottom-right)

Design 1c, no container: neither card has a background, border, radius, or shadow --
just a scrim behind the content. Both cards share one geometry: end 48dp, bottom 96dp
above the frame's edge, riding up by the control-zone height (hard-cut, like the OSD)
while the OSD shows, a 360dp-wide column sized to its content. Rust decides which of the two
appears at an episode end; never both. While a card shows, the button row has no
focused button and the skip pill is hidden; when it goes, focus returns to Play /
Pause. Nothing on either card is focusable -- Select and Back are the only inputs,
handled by the OSD's key handler, not by a focus ring.

Scrim: a radial gradient pinned to the frame's own bottom-right corner (no 48dp
inset, unlike the content drawn over it), 550×310dp, appearing and disappearing with
whichever card shows and riding up with it, never animated on its own --
`radial-gradient(ellipse at 88% 88%, Notte 94% at 0, Notte 82% at 38%, Notte 42% at
66%, Notte 0% at 100%)`. The ellipse still carries alpha where the box's top and left
edges cut it, so those two edges are multiplied down to nothing over 64dp; no seam.

Content, top to bottom, in the 360dp column:

- A 128×72 thumbnail (2dp radius, blurhash placeholder), beside a text column:
  eyebrow (Martian Mono 12sp, -0.02em, Grigio), a title that wraps freely with no
  line cap (Archivo 15sp Medium, 17.4sp line height, Panna), and an optional third
  line (Martian Mono 11sp, -0.02em, Grigio).
- An 8dp gap, then a 2dp-tall countdown rule spanning the column: a Panna@16% track,
  Pistacchio fill left-anchored at the remaining fraction (full at the start,
  depleting right to left to empty at zero). No fill at all -- track only -- when the
  card isn't counting down.
- An 8dp gap, then the action row (space-between, baseline-aligned): `BACK TO
  DISMISS` / `BACK TO STOP` (Martian Mono 11sp Grigio) on the left; on the right,
  `SELECT` (Martian Mono 12sp Grigio), the action label (Archivo 14sp Bold,
  Pistacchio), and, only while counting down, the numeral as `IN {n}` (Martian Mono
  14sp Bold, Panna) -- absent entirely, not a placeholder, when there's nothing to
  count down.

Numeral format: under a minute as `8S`; a minute or more as `m:ss` (`1:30`).

Both cards' countdown is driven per frame, not once a second, and both stop exactly
at zero without wrapping or restarting. Up-next reads the live player position every
frame (`PlaybackViewModel.livePositionTicks()`, since `positionTicks`'s StateFlow
only updates once a second) against `countdownStartPositionTicks +
countdownTotalSecs`, and the frame that reaches zero hands over itself
(`nextUpCountdownElapsed()`) rather than waiting for the ticker's next pass, so the
empty rule, `0S` and the next episode's start are one instant. Still-watching reads the wall clock every frame against
`StillWatchingState.deadlineMs`. Either loop touches only the draw phase for the
rule and recomposes the numeral text on a whole-second change, not every frame.

Up-next: eyebrow "UP NEXT", third line is the runtime only ("22 MIN"; omitted when
the item has no known runtime -- no codec/resolution/synopsis), action label "Play
Next". With autoplay on, the rule depletes and the numeral counts down as described
above; with autoplay off, the rule shows its bare track and the numeral is absent --
Select still plays now. The card appears at the credits' start (the outro segment)
or, with no segment, ~15% of runtime from the end, clamped 3–30s. When credits are
set to Auto-skip and the item has an outro, the card comes forward by the autoplay
delay so it is seen in full: it appears delay + credits from the end, its countdown
runs out where the credits start, and the next episode starts there instead of the
skip. A seek that lands inside auto-skipped credits before the card showed advances
straight to the next episode with no card flash, and a file that ends while the
decision is in flight waits for it instead of exiting.

Still watching: eyebrow "STILL WATCHING", title "Are you still there?", third line
is the next episode's `S1 E2 · Name` label, action label "Keep Watching". The rule
always depletes over the answer timeout and the numeral always counts down; any
first-press key resets both to the full timeout (a fresh `deadlineMs`). Select keeps
watching, playing the next episode immediately; Back stops. Stop reports the
finished episode normally, never starts or marks the next one, and routes to that
next episode's detail page. Left/Right are swallowed while the card shows (nothing
to move focus to) rather than reaching the OSD underneath. The guard and its
thresholds live in Settings › Playback › Still watching?.

## 14. Skip intro / credits

Per segment type (intro, credits, recap, preview, commercial) the setting
is Ask / Auto-skip / Off; commercial defaults to Auto-skip, the rest to
Ask; unknown types are Off.

- Ask: a pill at end 48dp, bottom = datum + 16dp: SurfaceRaised@0xee, 6dp
  radius, padding 12×8, Archivo 14sp Panna, `Skip Intro` / `Skip Credits`
  / `Skip Recap` / `Skip Preview` / `Skip Commercial`. While it shows,
  Select skips instead of activating the focused button. Hidden while
  either card shows.
- Auto-skip: seek past the segment on entry and reveal the OSD.
- After either skip, a toast top-center at 24dp: SurfacePanel, 8dp
  radius, padding 16×8, Archivo 13sp Panna, `Skipped intro · Select to
  undo`. Live 5s; Select seeks back to the pre-skip position. The toast
  does not pin the OSD; it and the idle window share the same 5s.
- Outro Auto-skip defers to the up-next / still-watching decision (§13).

## 15. Buffering and reconnecting

One pill shape, top-right at the 48dp inset: SurfaceRaised@0xee, 6dp
radius, padding 12×8, three pulsing 6dp Panna dots (1200ms cycle, the
house loading motif; no spinner rings anywhere) and Archivo 13sp Panna
text. No scrim, no layout shift; the video stays visible.

- Buffering: `Buffering · 42% · 12.4 MB/s`; throughput is omitted when
  the estimate is zero. The percent is progress toward resuming (banked
  media against the 1s cold-start or 2s rebuffer threshold), not the whole
  item. Shown at once for the initial load and spontaneous stalls; an
  explicit seek gets a 750ms grace so Media3's routine keyframe
  repositioning is not labelled network slowness.
- Reconnecting: `Reconnecting (attempt n)` while the reconnect interlock
  runs; pins the OSD.

## 16. Track picker and subtitles

Picker: anchored center-right at the 48dp inset, 320dp wide, max 480dp
with internal scroll, SurfacePanel, 1dp Hairline border, 8dp radius,
padding 16. Two sections in one list, "Audio" then "Subtitles" (Archivo
13sp SemiBold Grigio, 1sp tracking). Rows: 6dp radius, padding 12×8, a
20dp leading gutter with a Panna `✓` on the selected track, label Archivo
16sp Panna, meta Archivo 11sp Grigio; focused row = Panna@0x22 fill. Opens
with focus on the selected track; Up / Down clamp; Select chooses; Back,
Escape or Menu close. Choices are remembered per series.

Subtitles: bottom padding fraction 0.08 by default, 0.24 while the OSD is
visible, never below the viewer's position preset (0.08 / 0.16 / 0.24 /
0.32). While a sheet is open the subtitle view's right margin becomes the
sheet width, so cues reflow into the left column and never run under the
sheet. Size, bold and background opacity come from Settings › Subtitles.

## 17. Sheets

One container for both (`OsdSheetContainer`): right-anchored, top 0,
450dp wide, height = content up to 350dp with internal scroll (Up / Down
step 120dp), padding top 32 / end 48 / bottom 26 / start 52. Flat
scrim-base@92% tint with the left 81dp fading in from transparent; a 40dp
alpha fade at the bottom; no blur, border, radius or shadow. Header: the
kicker in Martian Mono 11sp Grigio and `BACK TO CLOSE` in Martian Mono
10sp Grigio, 12dp, a 1dp Panna@6% divider (the only one), 16dp, content.
Opening captures the invoking button; the OSD stays visible and live
underneath.

Library info, kicker `IN YOUR LIBRARY`, Archivo values:
- Meta line, 15sp Panna2, up to two lines, never truncating genres:
  `S4 E18 · Mar 10, 2011 · 22m · TV-PG · 7.3/10 · Comedy, Romance`, the
  rating figure Pistacchio bold (the sheet's one accent).
- Synopsis 15sp / 21.75 line height Panna, max 4 lines.
- Fields, label column 95dp (13sp Grigio) and value (14sp Panna), 7dp
  row gap: Director, Writers (first two + N), Watched (`3 times · last
  Aug 31, 2026`, or `Never`), Added. Missing fields drop out; no
  placeholders, no footer pointer.
- Fetched on open only (`Loading…`, then content or `Library info
  unavailable`), never on the time-to-play path.

Playback stats, kicker `PLAYBACK STATS`, Martian Mono values:
- Headline Archivo 15sp Panna2 `Direct Play · no transcode`, the second
  span Panna bold (not accent); while transcoding, `Transcoding · <reason>`
  with the reason (the local failure text, or "bitrate above cap") as the
  second span.
- Grid, label 75dp (11sp Grigio), 11dp gap, value 12sp Panna one line
  with ellipsis, 6dp row gap: VIDEO (codec, exact stored dimensions,
  frame rate), AUDIO, SUBTITLES (`Off · 3 available`), CONTAINER, SOURCE
  (the server's configured name), then the live rows BUFFER, NETWORK,
  HEALTH refreshed at 1Hz while the sheet is open and never while closed.
- `FILE` plus the file name verbatim, Martian Mono 10.5sp `#A69C8E`. The
  only place a release tag can appear.

## 18. Settings that feed this screen

| Setting | Values | Default | Feeds |
|---|---|---|---|
| OSD density | Minimal / Full | Full | §3 |
| Seek preview | Small / Medium / Large | Medium | §11 panel width |
| Skip back / forward | 5 / 10 / 15 / 30 / 60s each | 10 / 10 | seek glyph numerals, D-pad seeks, flash numeral |
| Autoplay + delay | on / off, seconds | on, playback-policy default | up-next card, countdown rule |
| Still watching? | off / after N episodes / after N hours, answer timeout, presses-reset toggle | per playback-policy | still-watching card |
| Skip segments | Ask / Auto-skip / Off per type | Ask (commercial Auto-skip) | §14 |
| Subtitle size / position / bold / background | presets | Media3 defaults | §16 |

## 19. Known deviations and open items

Deviations from the design history above, all deliberate:
- No scrubber thumb and no scrubber focus (deliberate, from the flat
  restyle pass). Up / Down therefore never leave the button row.
- Titles never strip release tags (naming rule).
- Right time column is a minimum width, not fixed (§6).
- Sheet height is capped at 350dp with a 26dp bottom pad rather than
  "content ≥ 26dp above the block"; the fade is 40dp inside that.
- The track picker is one scrolled list with two headers, not two tabs.
- No chip-only trickplay fallback; no tile means no panel.
- Unknown duration draws a static groove, not an indeterminate animation.
- Movies show no year on the title line (no data at render time).
- Under the 11sp floor: `BACK TO CLOSE` (10sp), the stats `FILE` row
  (10.5sp), menu trailing times (10sp).
- **Hold-to-seek transient target tick**: the glide traversal bar's target tick is the
  one seek indicator that exists only during a glide (and its 3s
  persistence window) -- it is not a scrubber thumb and never appears at
  rest; §7's "no thumb" rule is otherwise unchanged.
- **Chip-only glide feedback without a tile** (§5.4, scoped override of
  this section's own "no tile means no panel" rule above): during a glide
  and its persistence window, the traversal bar and chip always render even
  when this item has no trickplay manifest (or no tile covers the current
  target) -- only the tile itself is conditional. Outside a glide, "no tile
  means no panel" is unchanged.
- **Hold-to-seek's end clamp**
  sits one second short of the true end of file, so a committed end-clamp
  seek always lands inside playable media; landing there pauses (reveals
  the OSD, as any pause does) without firing up-next, autoplay, or a
  watched-state write -- the hold is only released once the viewer resumes
  on their own (a seek while still paused, including a glide back from the
  clamp, keeps it).
- **A glide surface hard-cuts away** the instant anything else reveals the
  OSD (a menu, a sheet, the next-up/still-watching cards, pause, or any
  other wake key) -- a glide never itself reveals or pins the OSD band.

Branding: the player carries no brand mark. The brand in playback is the
palette and the Martian Mono readouts.
