# 11 — Item Detail UX spec

Our current TV detail page is a bare v1 (backdrop band, title, meta, Play,
season strip, episode grid). This doc is the gap list and the spec for closing
it, ranked by contribution to "feels like Jellybeam".

## Tier 1 — brand-defining (without these it's a generic client)

1. **The spec strip** — the brand's signature component.
   - 999px pill, SURFACE_RAISED fill, 1px HAIRLINE border, px 12 / py 4,
     self-start (hugs content, never stretches into a bar).
   - Martian Mono 10px, values joined by `" │ "` (U+2502). Values only —
     never `RESOLUTION: 1080P`.
   - Field order: RESOLUTION │ VIDEO CODEC │ BIT DEPTH │ HDR │ AUDIO(+ch) │
     BITRATE │ CONTAINER │ SIZE.
   - Three DATA-DRIVEN emphasis tiers (classification is data-driven; callers
     cannot hard-code a weight): baseline GRIGIO (1080P, H264, AC3, MKV, size);
     notable PANNA (4K/8K, HDR/HLG, HEVC, AV1, DIRECT PLAY, lossless);
     best-in-class PISTACCHIO (DOLBY VISION, ATMOS, DTS:X, DTS-HD, TRUEHD,
     any ≥10-BIT).
   - Overflow: wrap into extra pill rows (6px gap), NEVER drop a value.
     Separators bind to the cell they introduce so wraps can't strand a ` │ `.
   - `INFO` control outside the pill: opens a MediaInfo-style breakdown
     (per-stream sections + FILE section w/ container/bitrate/size/path — path
     lives only here). On TV: a focusable button opening a side panel.
   - Data: MediaStreams/MediaSources via live enrichment fetch (mirror sync
     deliberately omits them); fields appear progressively, unknown omitted.
   - Series pages have NO strip (no MediaStreams on a Series).
   - Condensed 3-cell variant (RESOLUTION │ HDR │ AUDIO) shown as a
     focused-card overlay.

2. **Type system.** Archivo for everything; Martian Mono ONLY for technical
   data (spec strip 10px, details line 11px, episode breadcrumb 10px), always
   uppercase, always ` │ `-joined; Bagel Fat One only for the word "Jellybeam".
   Tabular numerals wherever digits could jitter (meta, spec, details).

3. **Palette discipline.** All text is PANNA at four alphas (FF/B3/73/40) —
   TEXT_PRIMARY/SECONDARY/TERTIARY/QUATERNARY — never independent grays.
   GRIGIO is the mono register. PISTACCHIO is the only accent, spent on
   actions/state only — never hover, never chrome. Warm neutrals everywhere;
   no decorative gradients, no glows, no colored shadows.

4. **The four-layer backdrop** — full-page, not a band:
   (a) sharp image, cover-fit, full bleed (fallback: flat SURFACE_RAISED);
   (b) blurred+darkened copy painted over the bottom 40% only (sharp top 60%);
   (c) vertical scrim: opaque NOTTE from bottom to 45% height, ramp to
   transparent at top; (d) horizontal scrim: NOTTE at x=0 → 70% alpha at 25%
   width → transparent by 55%. No tint, no color sampling — one hue, NOTTE at
   varying alpha. Plus a 120px seam fade whose bottom edge pins at 45%
   viewport height.

5. **Episode cells are 16:9 thumbs + a fixed-height three-zone text block**
   (160×90 art, 2px radius, then 68px text budget):
   - Title, exactly one line w/ real ellipsis: `E{n} · {name}` — 14px MEDIUM
     PANNA. Rule: one line for titles, two for descriptions, everywhere.
   - Runtime line 12px GRIGIO ("42m"/"1h 23m"); virtual → "Airs Mar 4, 2026"
     / "Missing".
   - Overview: 2-line measured clamp w/ ellipsis, 12px GRIGIO, 2×16px
     RESERVED even when empty so rows line up.
   - NO sibling row-dimming and NO focus-driven text color changes on episode
     cells (deliberate; dimming read as "row disabled"). No card dims
     (docs/07 §4).

## Tier 2 — the page feels empty without these

6. **Poster in the hero.** 220×330 fixed, 2px radius, NO shadow. Missing →
   flat SURFACE_PANEL rect (no icon/text).
7. **Watch-state language on cells:** 3px PISTACCHIO progress bar flush inside
   the art's bottom edge (track = PISTACCHIO@30%); watched → 20px circular
   check badge top-right on ART_SCRIM (NOTTE@50%); series/season cells →
   unplayed-count pill (PISTACCHIO fill, NOTTE numeral, 11px SEMIBOLD).
   Progress and checkmark mutually exclusive by construction.
8. **Synopsis + details block.** Synopsis 15px TEXT_SECONDARY, ~68-char
   measure (max-w 510px), UNCLAMPED on detail pages; missing → block absent.
   Details: ONE wrapping Martian Mono line, 11px, all GRIGIO, uppercase,
   ` │ `-joined: `DIR. NAME │ WR. NAMES │ Studio │ Mmm D, YYYY │ ADDED …`.
   No section header. Absent rows dropped.
9. **Cast row.** Header "Cast" (20px SEMIBOLD). 72px circular portraits,
   name (12px, 1-line) + role (11px TERTIARY, 1-line), 84px columns.
   HARD RULE: skip anyone without a portrait — no gray placeholder circles.
   Row absent if none survive. Edge-faded horizontal strip.
10. **Series-specific meta line:** `YearRange · N seasons · OfficialRating`.
    Year range: `2007–2013`, `2007–` if Status=Continuing. NEVER runtime or
    genres on a Series. Season count from loaded seasons, not ChildCount.
    Movie/Episode meta: `Year · Runtime · OfficialRating · Genres`. Empty
    segments omitted, never placeholdered. OfficialRating only —
    CommunityRating is never displayed anywhere.
11. **Resume semantics.** Series Play target: walk seasons in watch order
    (Specials index 0 sort LAST), skip virtuals; first episode with
    position>0 wins (→ button `Resume S2 E6`), else first unplayed (→ plain
    `Play`). Missing S/E numbers degrade to plain `Resume`, never `S? E?`.
    ~~Secondary "Start from beginning" pill only when progress exists — same
    44px box as primary (transparent, 1px HAIRLINE border, PANNA-2 label),
    same row, wraps.~~ Superseded by docs/19: the `···` menu at
    position two owns "Play from the beginning"; no secondary pill on the
    row. Disabled: offline → "Server offline"; virtual → status
    label; SURFACE_OVERLAY fill + TERTIARY text + reason line beneath.
    Primary: PISTACCHIO pill, NOTTE label, Archivo 700, h44, min-w 140.
12. **Season picker = tab strip with a 3px PISTACCHIO underline** (no filled
    pill — explicitly killed). Selected label PRIMARY, unselected SECONDARY.
    The underline animates with a real sliding indicator, not a crossfade.
    Focus ring on tabs = the shared 2px ring. Left/Right changes season;
    Enter on a tab does nothing.
13. **Similar Titles row** — 2:3 posters at 160px, from a live GetSimilar
    (limit 16) alongside the instant mirror paint. Empty and not-yet-loaded
    both render NOTHING. Skipped on episode pages.

## Tier 3 — "correct" vs "premium"

14. **Unified focus treatment** (already largely ours): 2px PISTACCHIO ring at
    4px outset (radius = art radius + 4), art grows 1.02 INSIDE a fixed slot,
    +8% brightness wash, 180ms in / 240ms out, ease ≈ cubic-bezier(.2,0,0,1).
    One treatment for cards, buttons, tabs — no variants.
15. **Episode detail is its own page**: full-bleed hero
    band (height = content height, not fixed) using the SERIES backdrop
    (never the episode still; sharp only, no double-darken), hard horizontal
    scrim (opaque→42%, glow to 88%, opaque to right edge) + vertical scrim
    (0x40 top → opaque by 92%), text confined to the LEFT 46% (cap 700px):
    breadcrumb → title → meta (movie shape) → spec strip → actions.
    Breadcrumb: Martian Mono 10px TERTIARY — `S{n}` (link → series page with
    that season pre-selected) ` E{n}` ` · ` series-name link with an
    always-visible 12px chevron (affordance was real user feedback). Link
    hover = brighten + underline, never accent. Below the band on flat
    NOTTE: synopsis, details, then a same-season sibling rail (same episode
    cards; current episode carries the 6dp "current" dot, never the focus
    ring (docs/15-focus-and-selection.md §1.2);
    header `Season {n}  ·  All seasons ›`). Sibling open = in-place replace,
    no history push. Prefetch prev/next episodes' hero art. Deliberately no
    floating episode still and no prev/next chevrons.
16. **48px section rhythm** on the episode page (buttons→synopsis→details→
    season header), each gap owned by exactly one place.
17. **Fallback chains:** backdrop → ParentBackdropTags[0] resolved against
    ParentBackdropItemId → flat surface. Episode-in-poster-slot → ALWAYS the
    series primary, never a cropped still. Missing art → flat tile, never an
    icon.
18. **Edge fades** on every horizontally-overflowing strip, only on sides
    with actual overflow.
19. **Layout stability:** reserve ≥ one viewport height under the season
    tabs + grid so a 1-row season can't clamp scroll to 0 and jump the page
    on season switch.
20. **Empty/error copy:** Archivo, factual, one line ("This item is no longer
    on the server."), no illustrations.

## Structural notes for the TV port

- Keep the scroll container's children FLAT (hero, tabs, one child per
  episode row) so keyboard focus can scroll-to-item a specific row.
- Focus model: Play → Seasons → Episodes top-to-bottom; Up from episode row 0
  → Seasons (or Play); grid focus remembers its column. Every focus move
  scrolls its target into view.
- Enter on an episode card OPENS the episode page; it does not play. There is
  no mouse-only hover play glyph on TV: use a deliberate substitute (Play/Pause
  media key on the focused card is the natural one; document whichever we
  pick).
- Skip A/B hero reflow (synopsis beside vs below poster) — it's a window-resize
  feature with no place in a fixed 1080p layout; pick one layout.
- Keep fixed per-zone text budgets (~68-char measures) so rows always align.

## Collection (BoxSet) page

A Jellyfin collection opens its own page (`CollectionDetailScreen`), not the
movie layout. Members come from the mirror in the server's display order
(`children(boxSetId, ...)`); every rule below is a pure function in
`CollectionFormatting`.

- Full-bleed backdrop under the standard detail scrim: the collection's own,
  else the first member that has a backdrop (a Series member's own backdrop).
- Mono-caps eyebrow `COLLECTION`; title is the server name, verbatim; meta line
  `N ITEMS | N WATCHED | min–max year` over the members (one year when equal,
  segment omitted when no member has one; the collection's own year is ignored).
- Primary pill `Play` with a subtext line, then the `···` door. Target, in the
  series page's order: the first member part-way through, else the first not
  fully watched (Movie/Episode: not played or resuming; Series: unplayed
  episodes left). Only real (non-virtual) movies, episodes, videos, music videos
  and series qualify; a nested collection, album or book never does. A Series
  with no episode yields to the next candidate, and one whose episodes fail to
  load keeps the pill's previous target only if that was the same series. A Series target plays
  the series page's own next episode (first with progress, else first unplayed)
  and reads `<series> · S2 E6`; a movie reads `<name> · <runtime>`. A target with a
  saved position reads `Resume` (a movie's line shows `<name> · 53m left`). Everything
  watched: the pill reads `Play again` and starts member 0 from the beginning.
  An empty collection has no pill.
- One horizontal row of member posters in server order: watched check on
  watched members, an `UP NEXT` tag on the Play target, caption
  `Series · N seasons` (Specials not counted) or `YEAR · 1h 42m`. Select opens
  the member's own page.
- Focus: lands on Play (the door when there is no pill); Up from the row
  returns to Play, never the nearer door; Down reaches the row,
  which remembers its last card like the other detail rows; Back restores it
  (docs/15).
- The `···` menu holds only the favorite toggle and, for administrators,
  Refresh metadata (docs/19 §1.1).

## Deliberate NON-features (do not "fix" these)

~~Favorite toggle, Played toggle~~ (both are features on TV now: the
Favorites shelf plus the detail action menu, docs/19),
CommunityRating/stars, taglines, chapters,
logo art, a separate "Up Next" card (its info lives on the Play button
label), any second accent color, colored/status hues (SUCCESS=accent,
WARNING=GRIGIO, DANGER=PANNA). Each was deliberately scoped out; do not
add it back without a documented reason.

## Addendum — overflow fix (supersedes parts of §6, §8, §9, §15)

Spec: `docs/23-detail-layout-rules.md` (screenshot mockup kept out of
the repo). Applies to Movie, Series and Episode alike.

- **One flow column.** Only the backdrop image and its scrims are
  positioned absolutely. Every other region is a child of one Column, so a
  long title or synopsis displaces what follows instead of drawing over it.
  The old fixed y-offsets (details panel 310dp, spec strip 323dp, cast
  374dp; Series chips 318dp / shelf 380dp) are gone.
- **Movie frame.** Content column is at least one viewport tall; the spec
  capsule takes the flexible gap (`margin-top: auto`, with a fixed 8dp
  minimum so credits never sit flush on the pill) and the cast band sits a
  fixed 22dp under it, so on a short page both stay pinned to the bottom
  and on a tall page the column grows and the page scrolls. Similar Titles
  follows below the frame. The Movie scroll container replaces the
  platform's TV pivot (which dragged the title off-screen the moment MORE
  took focus) with a frame rule: focus landing on anything inside the frame
  scrolls the page back to the top, and a target below the frame (Similar
  Titles) scrolls only as far as needed to show it. Entering the cast row
  from the text column lands on its first visible portrait (docs/15 §2),
  never the one that happens to sit under the MORE stop; the whole cast
  card is the focus target so the shelf pin measures the card edge, not
  the portrait centred inside it. The Series page uses the
  same frame rule: the resume season's chip taking focus on a fresh entry
  no longer pivots the hero off the top of the screen.
- **Height budget.** A two-line title, four-line synopsis, MORE, credits,
  capsule and cast fit one 540dp viewport with a few dp to spare. To get
  there the Movie/Series action rows lost their 6dp vertical margins (the
  11dp column gap already clears the ring), the MORE stop sits 4dp under the
  synopsis rather than a full row gap, and the Episode page dropped the same
  6dp margins plus 4dp above its cast row so the cast names clear the
  bottom edge on first paint.
- **Clamps.** Title: 2 lines, 32sp/34sp, ellipsis (all three pages, replaces
  41/37/36sp). Overview: 4 lines, 12.5sp/19sp, ellipsis, with a focusable
  `MORE ↓` stop (Martian Mono 9sp) directly under it, shown only when the
  text actually overflowed (`onTextLayout.hasVisualOverflow`). §8's
  "UNCLAMPED on detail pages" no longer holds.
- **Full-synopsis panel.** Select on `MORE ↓` opens a full-screen scrim with
  the title and the whole overview in a scrolling column (Up/Down scroll,
  Left/Right/Select swallowed); Back closes it and returns focus to the MORE
  stop (`overview:more`, docs/15 §5 keys).
- **Credits as prose.** §6's details panel is replaced by one non-wrapping
  line under the overview: `DIRECTED BY <names> │ WRITTEN BY <names> │
  STUDIO` (labels Martian Mono 8.5sp GRIGIO, names Archivo 11sp PANNA, studio
  Martian Mono uppercase). Each segment shrinks and ellipsizes; a four-name
  writer list never reflows its neighbours. `ADDED` lives in the eyebrow.
- **Spec capsule.** Outlined only (1px HAIRLINE, full radius, 12dp/5dp
  padding, Martian Mono 9sp); the §1 emphasis tiers still colour the
  values. It is the visual break between the reading half and the browsing
  half of the page.
- **Cast.** One treatment everywhere: 84dp cards, 64dp round portraits, 22dp
  gap, name and role one line each with ellipsis, so a long name never
  widens its card. Header `CAST` in Martian Mono 10sp GRIGIO on Movie and
  Series; none on Episode. The Movie page no longer caps the row at six.
  The cast and Similar Titles rows scroll horizontally under the shared
  shelf rule (focused card pins at the page margin), the same one the
  episode shelf and Home's rows use.
- **Poster.** 148×222dp, 4dp radius, 26dp from the text column (was
  150×227, 3dp, 24dp).
