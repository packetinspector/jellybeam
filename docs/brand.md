# Brand — Jellybeam TV

The durable brand spec: manifest identity, launcher icon and banner rules,
colour tokens, the wordmark, mascot usage, per-screen lockup rules, and the
acceptance bar for anything that touches brand. Section numbers are stable
— code comments cite them (e.g. `docs/brand.md §2`, `§6.3`) — so don't
renumber when editing.

The name, wordmark, mascot, and launcher assets described here are reserved
and excluded from the code licence; a distributed fork replaces them
(`TRADEMARKS.md`).

## 1. Name and manifest

- `strings.xml` `app_name` is `Jellybeam`.
- `AndroidManifest.xml`'s `<application>` element carries:
  ```xml
  android:icon="@mipmap/ic_launcher"
  android:roundIcon="@mipmap/ic_launcher_round"
  android:banner="@drawable/banner"
  android:label="@string/app_name"
  ```
  Any activity that sets its own `android:banner` or `android:icon` points
  at the same resources.
- No other brand name appears anywhere user-visible — About, Settings
  headers, notifications, the user agent, the Jellyfin client name sent at
  sign-in, or any device name the app sends.

## 2. Launcher icon and banner

The launcher icon is the mascot on its own ground. Adaptive icon
background: `@color/ic_launcher_background` = `#1A1D15`. The foreground
stays inside the 66dp safe zone; the monochrome layer reuses the
foreground so themed launcher icons still read as the mascot.

The Google TV banner is `drawable-xhdpi/banner.png` (640×360); Android
scales the xhdpi asset for other densities, so one file is enough. Play
Store listing art (icon, feature graphic, TV banner) follows the same
mark and colour rules.

## 3. Colour

| Token | Hex |
|---|---|
| NOTTE | `#14100D` |
| SURFACE | `#1D1814` |
| HAIRLINE | `#322A22` |
| PANNA | `#F7E9CE` |
| PANNA-2 | `#C9C0B2` |
| GRIGIO | `#8C8478` |
| PISTACCHIO | `#A8CB6B` — the only accent |
| SHEEN | `#C6DE9B` |

One accent, warm neutrals, no gradients, glows, or translucency **in the
UI**. The single exception is inside the mascot art itself (§5).

## 4. Wordmark

- Font: **Bagel Fat One** (Google Fonts, SIL OFL), used only for the word
  "Jellybeam".
- Two colours: `Jelly` in PANNA, `beam` in PISTACCHIO.
- **One kerning adjustment:** −0.03em between the `y` and the `b`. Nothing
  else is tracked or kerned; one baseline.
- In Compose, it's one `Text` built from an `AnnotatedString` with two
  `SpanStyle`s; the kern is `letterSpacing = (-0.03).em` on the `y`
  character only.
- **Rays** (the three bars over the `m`) appear only on the launch screen
  and in marketing, never in the app header. Geometry, in em of the
  wordmark size:
  - Bar width 0.107em, fully rounded ends, PISTACCHIO.
  - Group box 0.6 × 0.467em. Its right edge sits 0.04em past the end of
    `beam`, and its top 1.16em above the baseline.
  - A: x 0.067, y 0.093, length 0.293, −24°. B: x 0.267, y 0, length
    0.333, +8°. C: x 0.453, y 0.173, length 0.253, +46°. Rotate each bar
    about its centre.
- Where the font can't be used, a transparent wordmark PNG (with or
  without rays) stands in for it.

## 5. The mascot

Three tiers. They don't mix.

1. **Icon mascot:** launcher icon only.
2. **UI-state mascot:** flat art used only on screens with **no content**.
3. **Scene art:** painterly, with a lit background. Still-watching and
   marketing only, never a functional state.

Rules for tier 2:
- **Never over video, posters, backdrops, or a library row.** A populated
  Home has no mascot except the header lockup.
- Always on NOTTE or SURFACE. If it ever has to sit on artwork, add a
  backing halo: two stacked drop shadows in NOTTE, 3px blur at 90% opacity
  and 22px blur at 75%.
- Display at 96dp or larger for states. Keep the aspect ratio
  (`ContentScale.Fit`). Don't tint, recolour, or crop.
- Pose → state:

| Pose | Where |
|---|---|
| Base | Header lockup |
| Happy | Signed in / connected confirmation |
| Excited | New episodes notice |
| Watching | Quick Connect / pairing screen |
| Curious | Empty library (§6.2) |
| Searching | Search, no results |
| Fast | Launch screen (§6.3) |
| Sleepy | Screensaver |

Empty-state copy stays factual: one line, no jokes, no exclamation marks.
The mascot carries the warmth; the words don't need to.

## 6. Screens

### 6.1 Header lockup (every top-level screen)
- The mascot's base pose at **35dp** tall, pulled left by 3dp and
  overhanging the row by 7dp top / 6dp bottom, so the row height doesn't
  change.
- 6dp gap, then the wordmark at **20sp**, no rays.
- The clock, on the right, is unaffected.

### 6.2 Home, library empty
Shown when the server is reachable and every library has 0 items. Centred
column on NOTTE; header and spine as normal.
- Curious pose, 180dp wide.
- 15dp gap, then the title "No items in your libraries yet.", Archivo 700,
  22sp, −0.02em, PANNA.
- Body: "Add media to a library on your Jellyfin server and it will appear
  here.", Archivo 400, 14sp, PANNA-2, max width 450dp, centred.
- Spec strip: `HOST:PORT │ N LIBRARIES │ 0 ITEMS`, Martian Mono 11sp,
  GRIGIO. Real values, never placeholders.
- No button — there's nothing on the TV side to act on.

### 6.3 Launch screen
After the system splash (Android 12+ `SplashScreen`: background
`#14100D`, icon = the launcher foreground), a full-screen NOTTE composable
holds until the first server response.
- Fast pose, 280dp wide.
- 22dp gap, then the wordmark at 66sp **with rays** (§4).
- 22dp gap, then a status row: 6dp PISTACCHIO dot, 6dp gap, `CONNECTING ·
  <HOST>` in Martian Mono 11sp, GRIGIO.
- If the connection fails, the screen stays up, the status swaps to
  `SERVER UNREACHABLE · <HOST>`, and the existing error flow proceeds.

### 6.4 Everything else
No layout changes anywhere else — the OSD, up-next card, detail pages,
spine, and drawer carry only the name and colour rules already in force
throughout this doc.

## 7. Known gaps

- **Scene art:** the still-watching scene painting isn't ready — its bowl
  needs repainting before it ships. Until then, the still-watching card
  stays text-only.
- **Resolution:** the mascot masters come from a small source, about
  360px. They read fine on a TV up to about 240dp but go soft beyond
  that; a larger master is needed for store and print use.
- **Small-size mark:** use the mascot, not a flattened mark, at every
  size. At 24dp or below the face doesn't read — avoid needing the mark
  that small.

## 8. Acceptance

Anything that touches brand should hold to:

1. The launcher shows the mascot banner; the device launcher shows the
   unchanged mascot icon, both square and round.
2. No other brand name appears anywhere user-visible, including the About
   screen and the Jellyfin server's own device list.
3. The header lockup, empty-library state, and launch screen match §6.1,
   §6.2, and §6.3.
4. The mascot never renders over a poster, backdrop, or video frame.
5. Bagel Fat One appears nowhere except the word "Jellybeam".
6. Only the y→b pair is kerned.
