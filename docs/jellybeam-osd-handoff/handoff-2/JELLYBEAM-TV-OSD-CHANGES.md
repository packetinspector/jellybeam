# Jellybeam TV — OSD change list (v3 build → flat)

**This is a change doc, not a spec.** It assumes the OSD you already shipped (screenshot dated Sep 1) and
lists only what to alter. `JELLYBEAM-TV-OSD-SPEC.md` remains the reference for anything not mentioned here;
where the two disagree, **this file wins**.

Reference frames for the target state:

| File | What it shows |
|---|---|
| `handoff/08-osd-flat-v4.png` | nerdy OSD, focus on pause — **build to this** |
| `handoff/09-clean-flat-v4.png` | clean OSD |
| `handoff/10-sheet-flat-v5.png` | library sheet |

Units are **dp/sp** (960×540dp screen). The PNGs are 1920×1080, i.e. 2× these numbers.

---

## The one-line summary

The features are right; the styling is too loud. Remove containers and effects, keep every control. After
this pass, accent (`#A8CB6B`) appears on screen in exactly **four** places: the series kicker, the played portion of
the bar, the focused glyph, and the rating figure in the sheet.

---

## 1. Buttons — delete the containers

| | Now | Change to |
|---|---|---|
| Rest | glyph inside a circle | **glyph only** — no circle, no background, no border |
| Focused | accent-filled disc (or ring + wash) | **the glyph turns `accent`** — nothing else |
| Glyph size | 28dp | **22dp** |
| Stroke weight | 1.5dp | **0.8dp** — 2u in the 56-unit viewBox at a 22dp draw size. The stats pulse alone is 2.4u (0.95dp). |
| Solid marks (play bars, triangles) | 3.5dp wide bars | **2.5dp** wide bars |
| Gap between buttons | 7dp | **10dp** (the air replaces the container as the separator) |
| Cluster break | 8dp extra | **6dp extra** |
| Gap between OSD rows (title / time / buttons) | 10dp | **7dp** |

- The **44dp touch/focus target stays exactly as it is** — it is now invisible. Do not shrink the target
  along with the glyph.
- Delete the `accent-lit` outer ring everywhere. Delete the focus wash. Delete any elevation/shadow on
  buttons.
- Keep the small `accent` dot on audio & subtitles when a non-default track is active — drop it to **4dp**.
- **Delete the focus caption entirely** (`PAUSE`, `INFO`, `BACK 10S`…). The glyphs carry their own meaning;
  a word under one of ten icons was scaffolding. The caption sat *below* the block, so removing it does not
  shrink the block itself — it lets the whole OSD drop 17dp closer to the bottom edge, which is where the
  reclaimed picture comes from.
  Colour alone now signals focus — verify it reads at 3m on a bright scene before shipping.
- With the caption gone, the OSD block sits at **27dp** from the bottom edge, not 44dp.

## 2. Scrubber — thin it

| | Now | Change to |
|---|---|---|
| Track height | 5dp | **2dp** |
| Track colour | `cream` @16% | `cream` @14% |
| Buffered fill | `cream` @30% | **remove entirely** |
| Thumb | 13dp with a 1.5dp `surface` ring | **7dp, no ring** |
| Chapter ticks | 1.5dp, always on in nerdy | **1dp, and only when the file has markers** |

Focused scrubber: track 2dp → **4dp**, thumb 7dp → **10dp**, remaining track to `cream` @20%. No ring, no
outline, no `accent-lit`.

## 3. Title block — stop shouting the kicker

| | Now | Change to |
|---|---|---|
| Kicker colour | `accent` | **unchanged — stays `accent`.** It is the one place green earns its keep: it tells you what you are inside of at a glance. |
| Kicker size | 11sp mono | unchanged |
| Title | 22sp/700 | **20sp/500** |
| Gap kicker→title | 6dp | **3dp** |

The title is the only high-contrast element in the OSD. The kicker stays `accent`; everything else is
`cream-dim` in the OSD band and `muted` only inside the sheet, where the flat 92% tint guarantees contrast.

## 4. Direct Play strip — one cream-dim line

Replace the bordered accent chip + separate codec span with **a single `cream-dim` mono 11sp line**:
`DIRECT PLAY · 1080p · HEVC · AAC 5.1`. No border, no accent, no radius, `nowrap`.
When transcoding: same line, reading `TRANSCODE 1080p→720p ·` first, in amber `oklch(0.78 0.13 75)` —
that is the only case where this line is not `cream-dim`.

## 5. Time readouts — one row, cream-dim secondaries

- Nerdy: `0:52` `cream` + ` / 20:48` `cream-dim` on the left; `−19:56` `cream-dim` + ` · ENDS 10:14`
  `cream-dim` on the right. Secondary values are dimmer by *colour weight*, not by dropping to `muted` —
  muted text on a light scrim over a bright frame measures under 3:1. Both `nowrap`; fixed column widths (120dp left, 160dp right) so the bar never shifts.
- Clean: `0:52` and `−19:56` only, 65dp columns.
- **`cream-dim` `#C9C0B2` is the floor for everything in the OSD band** — secondary values are quieter by
  colour weight, not by going darker. `muted` `#8C8478` is for sheet interiors only, where the flat 92%
  tint guarantees the contrast. If a value looks too loud at `cream-dim`, remove the value rather than
  darken it. (Both `#6F675C` and `muted` fail 4.5:1 on this scrim the moment the frame behind is bright.)

## 6. Sheets — flat tint, no glass

| | Now | Change to |
|---|---|---|
| Backdrop blur | `blur(15dp)` | **remove** |
| Panel fill | 4-stop horizontal gradient (66/86/90%) | **flat `#070605` @92%**, with a soft left edge: transparent → 92% across the first 81dp (18% of the panel width) |
| Bottom falloff | mask fade 55dp | **40dp**, and keep the last line ≥15dp above where it starts |
| Divider | 1dp `cream` @12% | **1dp `cream` @6%**, and only one per sheet |
| Kicker (`IN YOUR LIBRARY`) | `accent` | **`muted`** |
| Rating figure | `accent` | unchanged — this is the sheet's single accent |
| Meta line | truncates with `…` | **wrap to a second line; never truncate genres** |
| Sheet footer pointer | present | **remove** — the stats button is right there |

Everything else about the sheets (450dp width, right anchor, padding, focus behaviour, content order)
is unchanged from the spec.

## 7. Scrim — shorter and softer

| Mode | Now | Change to |
|---|---|---|
| Nerdy | 230dp, `93/87@34/62@64/0` | **200dp, `90/83@36/56@66/0`** |
| Clean | 170dp, `90/80@38/50@68/0` | **160dp, `88/80@38/50@68/0`** |

Verify against a *bright* frame, not a dark one. Cream at 11sp must still clear 4.5:1 over the worst case.

## 8. Bug in the current build (independent of styling)

Subtitles render **under** the info sheet (visible in your screenshot: dialogue running behind the panel).
While a sheet is open, reflow subtitles into the left column, clear of the 450dp sheet, and lift them above
the OSD block. While the OSD is open with no sheet, lift them above the OSD block only.

---

## Do not change

- The button set, their order, or which mode shows which.
- The 44dp targets and the 48dp safe inset.
- Focus/D-pad rules, auto-hide rules, the density setting, the seek-interval setting.
- The seek glyph's numeral being data-driven, and its painted size (`font-size × draw-size ÷ viewBox` must
  land at ≥11sp — at a 22dp draw size in a 56-unit viewBox that means `font-size="28"`).

## Acceptance

- [ ] No circle, ring, wash, border, chip, blur or shadow anywhere in the OSD.
- [ ] Accent appears in exactly four places: series kicker, played bar, focused glyph, sheet rating.
- [ ] Nerdy block ≤ 110dp tall; clean ≤ 92dp (measured on the reference frames: 109.5 / 92).
- [ ] No helper text under any button.
- [ ] Every glyph stroke is 0.8dp (2u at the 22dp draw size); the stats pulse alone is 0.95dp; no solid
      mark is wider than 2.5dp.
- [ ] Nothing in the OSD band is darker than `cream-dim` `#C9C0B2`; `muted` is for sheet interiors only.
- [ ] Subtitles never sit under the sheet.
- [ ] Focus is unmistakable from 3m away with the TV at half brightness — check this on a bright scene, not
      a title card.
