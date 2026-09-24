# Jellybeam Android TV — Player OSD + info sheets

Implementation spec. Supersedes the OSD portion of `JELLYBEAM-TV-FIXES.md` and `JELLYBEAM-TV-FIXES-2.md`.

## 0. Read this first: the unit rule

Every number below is in **dp / sp** — use it directly in Compose. The reference design is drawn on a
1920×1080 canvas, which is **960×540dp**. Canvas px ÷ 2 = dp. If you measure something off a
screenshot, halve it. (The 84dp overlap in v2 was this conversion done once, in the wrong direction.)

Reference design: `Jellybeam TV Player OSD.dc.html` — frames `2a` (nerdy), `2b` (clean), `2c` (buttons),
`1b` (library info), `2d` (playback stats).

Screenshots (1920×1080, i.e. 2× the dp values):

| File | Frame |
|---|---|
| `handoff/01-osd-nerdy.png` | 2a — nerdy OSD, focus on pause |
| `handoff/02-osd-clean.png` | 2b — clean OSD |
| `handoff/03-osd-buttons.png` | 2c — every button rest + focused, speed menu, decisions |
| `handoff/04-sheet-library-info.png` | 1b — library info sheet |
| `handoff/05-sheet-playback-stats.png` | 2d — playback stats sheet |

## 1. Tokens

Colors:

| Token | Hex | Use |
|---|---|---|
| `bg` | `#070605` | app background |
| `surface` | `#0B0908` | scrim base, sheet base, menu base |
| `cream` | `#F7E9CE` | primary text, glyph strokes |
| `cream-dim` | `#C9C0B2` | secondary text, remaining time |
| `muted` | `#8C8478` | labels, captions, tertiary numbers |
| `accent` | `#A8CB6B` | played bar, focus fill, kickers, Direct Play |
| `accent-lit` | `#C6DE9B` | focus outline (at 55% alpha) |
| `on-accent` | `#14100D` | glyph/text on an accent fill |

Type: **Archivo** (400/600/700/800) for prose and titles; **Martian Mono** (400/700, tracking −2%)
for numbers, keys, kickers, captions. Never Martian Mono for sentences.

Motion: OSD in 160ms ease-out, out 220ms ease-in. Focus move 120ms. Sheets slide 8dp + fade,
200ms. No spring, no bounce.

Minimum type: **11sp**. Nothing in this spec is smaller; don't let a translation shrink it.

## 2. Geometry

- Safe inset: **48dp** left/right on everything. Nothing bleeds to the display edge.
- OSD block: `left 48dp, right 48dp, bottom 44dp`, vertical stack, `gap 10dp` (nerdy) / `8dp` (clean).
- Focus caption: mono 11sp `muted`, **centered on the focused button**, at `bottom 27dp`. One caption
  at a time, for the focused control only. (Never left-align it to the block — v3 labelled the wrong button.)
- Bottom scrim, full width, bottom-anchored, no blur:
  - nerdy: **230dp** tall, `linear-gradient(0deg, #070605 93%α, 87%α @34%, 62%α @64%, 0 @100%)`
  - clean: **170dp** tall, `90%α, 80%α @38%, 50%α @68%, 0 @100%`
  - These stops are tuned so cream text clears a *bright* frame (see 01/02 — the plaid shirt is the worst case). Don’t flatten the mid stop to reduce dimming; shorten the scrim instead.
- Block stack, top → bottom: title line(s) → time row → button row.
  Measured heights: nerdy ≈ **120dp**, clean ≈ **100dp** (incl. caption band). Do not exceed these —
  the OSD must not become a lower third.

## 3. Title line

- Nerdy: kicker mono 11sp `accent` (`<server series name> · S4 E18`) + title 22sp/700 `cream`, `gap 3dp`, max width 530dp.
- Clean: **one line** — title 20sp/700 `cream` + inline mono 11sp `muted` (`S4 E18`), baseline-aligned,
  `gap 10dp`. No series name (it's in the info sheet).
- Project override: titles are server-configured display strings and remain **verbatim**, including bracketed
  text, casing, and resolution-looking tokens — never rewritten or prettified. The stats
  sheet FILE row independently shows the source basename verbatim when present.
- Overflow: single line, ellipsis. Never wrap to two.

## 4. Time readouts

One row: `elapsed [bar] remaining`, `gap 14dp`, side columns fixed so the bar never jumps as digits change.
Column widths differ by mode: clean **65dp** each side; nerdy **130dp** left / **165dp** right.

| Mode | Left column | Right column (right-aligned) |
|---|---|---|
| Clean (65 / 65dp) | `13:52` mono 13sp `cream` | `-7:11` mono 13sp `cream-dim` |
| Nerdy (130 / 165dp) | `13:52` + ` / 22:04` in `muted` | `-7:11` + ` · ENDS 10:14` in `muted` |

- `ENDS` = wall clock at which playback finishes, from device clock, recomputed on seek and on speed
  change (a 1.5× rate moves it). Respect the device 12/24h setting; the design shows 12h without AM/PM
  to keep the row short — include AM/PM only if the locale is 12h and the string fits the right column.
- Remaining is always negative-signed. Elapsed/total never shows hours unless the media has them.

## 5. Scrubber

| Part | Nerdy | Clean |
|---|---|---|
| Track height | 5dp, fully rounded, `cream` @16% | 4dp |
| Buffered fill | `cream` @30% | not shown |
| Played fill | `accent` | `accent` |
| Position | `accent` played fill; no thumb | `accent` played fill; no thumb |
| Chapter ticks | 1.5dp wide, `surface` @75%, full track height | not shown |

- Chapter ticks only when the file has markers.
- The scrubber is display-only. A Left/Right press while the OSD is hidden silently seeks exactly one
  configured interval without revealing OSD chrome. Key-repeat events do not multiply or accelerate the seek.

## 6. Buttons

One spec for every OSD button:

- Target **44dp** circle (D-pad focus target and click area), glyph **28dp**, `gap 7dp` within a cluster,
  an extra **8dp** break where the spec says so.
- Rest: transparent background, `cream` glyph.
- Focused: `accent` fill, `on-accent` glyph, **2dp** `accent-lit` @55% outline at **3dp** offset, plus the caption.
- Active-but-not-default: `cream` glyph + a **6dp** `accent` dot at the top-right of the circle
  (used by audio & subtitles when a non-default track is selected).
- Disabled state does not exist. If an action isn't available, **omit the button** (see §7).

Order — transport cluster (left), then a flexible spacer, then the secondary cluster (right):

| # | Button | Caption | Glyph | Notes |
|---|---|---|---|---|
| 1 | Previous episode | `PREVIOUS` | bar + left triangle | episodic only; 8dp break after |
| 2 | Back *n*s | `BACK 10S` | CCW arc + numeral | numeral = configured interval |
| 3 | Play / pause | `PLAY` / `PAUSE` | two bars / right triangle | default focus |
| 4 | Forward *n*s | `FORWARD 10S` | mirror of #2 | |
| 5 | Next episode | `NEXT` | right triangle + bar | episodic only; 8dp break before; neighbors resolve after player load from one mirror lookup, with one live fallback only when the current episode is absent locally |
| — | *spacer* | | | |
| 6 | Speed | `SPEED 1×` | text label `1×` mono 14sp/700 | nerdy only |
| 7 | Audio & subtitles | `AUDIO & SUBTITLES` | rounded rect + 2 rows of lines | one button, one menu, two tabs |
| 8 | Chapters | `CHAPTERS` | 3 rounded segments | only when markers exist |
| 9 | Library info | `INFO` | circled *i* | opens §8a |
| 10 | Playback stats | `STATS` | pulse polyline | nerdy only; opens §8b |

Seek glyph geometry (56-unit viewBox, drawn at 28dp) — reuse exactly, it has a measured clearance budget:

Do **not** build this glyph by interpolating a value into the SVG text node — set the numeral from the
setting in code when the glyph is constructed, and let the caption carry the interval in words.

```
arc:       M28 5 A23 23 0 1 1 5 28     stroke 1.5dp, round cap
arrowhead: polygon 28,0 28,11 37,5.5
numeral:   Martian Mono 700, 23u, centered x=28 y=37
back = the whole group mirrored: translate(56,0) scale(-1,1), numeral NOT mirrored
```

Inner edge of the ring sits at 21.5u; two digits need 16.1u of half-width at cap height. `5`, `10`, `15`,
`30` all fit. Don't scale the numeral independently of the ring.

## 7. Density modes

User setting, **OSD detail: Clean / Nerdy** (default Clean). Not a debug flag — it's a first-class preference.

| Element | Clean | Nerdy |
|---|---|---|
| Series kicker | — | ✓ |
| Direct Play chip + codec strip | — | ✓ |
| Total duration, ENDS time | — | ✓ |
| Buffered fill, chapter ticks | — | ✓ |
| Speed button | — | ✓ |
| Playback stats button | — | ✓ |
| Chapters button | only if markers | only if markers |
| Episode skip buttons | only if episodic | only if episodic |
| Transport, audio & subtitles, info | ✓ | ✓ |

Direct Play chip (nerdy): mono 10sp `accent`, 1dp `accent` @45% border, 2dp radius, 3.5dp × 7dp padding,
**non-focusable**, followed by mono 10sp `muted` codec summary (`1080p · H.264 · EAC3 5.1`). Both `nowrap`.
When the server is transcoding, the chip reads `TRANSCODE` in amber (`oklch(0.78 0.13 75)`) and the codec
string shows `→ 720p H.264`; the reason goes in the stats sheet, not the OSD.

## 8. Info sheets

Both sheets share **one container**. This is the "library card convention" — the stats dialog is the
same object with different content.

- Right-anchored, `top 0`, width **450dp**, height = content (no fixed height, no min-height).
- Padding: top **32dp**, right **48dp**, bottom **26dp**, left **52dp**. The bottom padding preserves the
  mask fade without manufacturing a scroll range for content that already fits.
- Background: `linear-gradient(90deg, transparent 0%, surface@66% 20%, surface@86% 44%, surface@90% 100%)`
  over `blur(15dp) saturate(115%)`. No border, no radius, no shadow — it is a scrim, not a card.
- Bottom falloff: alpha mask `linear-gradient(0deg, transparent 0, opaque 55dp)`.
- **Content must end at least 26dp above the OSD block's top edge.** Content taller than that scrolls
  inside the sheet; the sheet region itself never grows past `y = 350dp`.
- Header row: kicker mono 11sp `accent`; right side mono 10sp `muted` `BACK TO CLOSE`.
- Divider: 1dp `cream` @12%.
- Opening moves focus **into** the sheet (it is the scroll container). Back returns focus to the button
  that opened it, which stays in its focused state throughout. The OSD stays visible and live.

### 8a. Library info (`i`) — Archivo values

| Row | Spec |
|---|---|
| Kicker | `IN YOUR LIBRARY` |
| Meta line | 15sp `cream-dim`: `S4 E18 · Mar 10, 2011 · 22m · TV-PG · **7.3**/10 · Comedy, Romance` (rating figure in `accent` 700) |
| Synopsis | 15sp/1.45 `cream`, max 4 lines, `text-wrap: pretty` |
| Fields | 2-col grid, label col 95dp: label 13sp `muted`, value 14sp `cream`, row gap 7dp |
| Fields shown | Director · Writers (`first two + N`) · Watched (`3 times · last Aug 31, 2026`) · Added |

No file/codec data here. Server-provided display names remain verbatim. Missing fields drop out of the grid entirely — never
"Unknown", never an empty value.

### 8b. Playback stats (pulse) — Martian Mono values

| Row | Spec |
|---|---|
| Kicker | `PLAYBACK STATS` |
| Headline | 15sp `cream-dim`: `Direct Play · **no transcode**` (accent 700) — or the transcode reason, verbatim from the server |
| Fields | 2-col grid, label col 75dp, col gap 11dp: label mono 11sp `muted`, value mono 12sp `cream`, row gap 6dp |
| Fields | VIDEO (codec, profile, exact dimensions, fps, bitrate) · AUDIO (codec, channels, bitrate, sample rate, language) · SUBTITLES (`Off · 3 available`) · CONTAINER (`MKV · 1.4 GB`) · SOURCE (`example-server`) · BUFFER (`42.0s ahead · 32.0 MiB`) · NETWORK (`16.00 Mbit/s`) · HEALTH (`Playing · 0 dropped`) |
| Footer | `FILE` label + filename mono 10.5sp `#A69C8E`, `word-break: break-all` |

Values must fit one line at 75dp label + 11dp gap — keep exact stored dimensions (`1920×960`) near the
front of VIDEO and abbreviate later fields (`EN`, `0 dropped`) rather than wrap.

BUFFER, NETWORK, and HEALTH are live — poll at 1Hz while the sheet is open, nothing while it's closed.
The filename is never rewritten; source/release text in it remains verbatim.

## 9. Speed menu

- Opens upward from the speed pill: **170dp** wide, `surface` @92%, 1dp `cream` @12% border, 5dp radius,
  10dp vertical padding.
- Rows: 8dp × 13dp padding, label mono 14sp. Options `0.5× 0.75× 1× 1.25× 1.5× 2×`.
- Current rate marked `CURRENT` in mono 10sp `accent` on the right. Focused row: `accent` fill,
  `on-accent` label.
- The pill's own label is always the live rate. Any rate ≠ 1× tints the pill `accent` so a stray 1.5× is
  visible without opening the menu.
- Changing rate recomputes the `ENDS` time.

## 10. Focus & D-pad

- OSD appears with focus on **play/pause**, every time. Never restore focus to a secondary button.
- Left/Right traverses the visible button row across both clusters in visual order — no wrap at either
  end. From a hidden OSD, Left/Right instead silently seeks one configured interval immediately.
- Auto-hide after **5s** of no input while playing; never auto-hide while paused or while a sheet or menu is
  open. Other keys wake it without also actuating.
- Back: closes menu → closes sheet → hides OSD → exit-playback confirm. One level per press.
- Play/pause on the remote works whether or not the OSD is visible, and does not force it visible.

## 11. Settings that feed this screen

| Setting | Values | Default | Feeds |
|---|---|---|---|
| OSD detail | Clean / Nerdy | Clean | §7 |
| Seek interval | 5 / 10 / 15 / 30 s | 10 | glyph numeral, captions, scrubber D-pad step |
| Show clock in browse chrome | on / off | off | (browse only) |

The seek glyph and its caption both read the interval — no hardcoded `10` anywhere.

## 12. Edge cases

- **Movie, not episode:** transport cluster is 3 buttons; no kicker; title line is the movie title + year.
- **No chapter markers:** no chapters button, no ticks.
- **Live/unknown duration:** hide total, ENDS, and remaining; show elapsed only; scrubber becomes an
  indeterminate track.
- **Missing metadata:** the row disappears. No placeholder strings.
- **Very long title:** ellipsis at max width; the info sheet carries the full string.
- **Transcoding:** §7 chip variant; stats headline explains why.
- **No backdrop/dark frame:** the scrim still renders — cream text must never rely on the video being dark.

## 13. Acceptance checklist

- [ ] Nothing within 48dp of the left/right display edge; OSD bottom edge ≥ 44dp.
- [ ] Nerdy OSD block ≤ 120dp tall, clean ≤ 100dp.
- [ ] No text smaller than 11sp anywhere, including glyph numerals and captions.
- [ ] Focus caption is centered on the focused button, in both clusters, in both modes.
- [ ] Exactly one focused control at all times; opening the OSD focuses play/pause.
- [ ] Seek glyph numeral matches the setting for 5 / 10 / 15 / 30 with no clipping or ring collision.
- [ ] Both sheets use the same container code path; neither renders a border, radius, or shadow.
- [ ] Sheet content never overlaps the OSD block and never sits on unscrimmed video.
- [ ] Release tags appear nowhere except the stats FILE row.
- [ ] Direct Play chip is not focusable and is absent in clean mode.
- [ ] Speed pill label tracks the live rate; ENDS time updates on seek and on rate change.
- [ ] Auto-hide respects every exclusion in §10.

## 14. Out of scope / open

- **Chapters button visibility** — spec'd as hidden when there are no markers. Confirm the server exposes
  marker presence cheaply; if not, we show it always and accept an empty list.
- **Episode skip on the last episode** — omit or keep? Spec says omit; needs a "next available" signal.
- Audio & subtitles menu internals (two tabs) not designed yet.
- Next-up / post-play behaviour at end of episode not designed yet.
