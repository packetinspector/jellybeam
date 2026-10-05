# 05 — Conventions, servers, and secrets

## Brand

The app is **Jellybeam** on TV. Theme tokens, fonts, and naming live in the Kotlin
theme layer. Licence: GPL-3.0-or-later (`LICENSE`); the name, wordmark, and mascot are
reserved (`TRADEMARKS.md`).

## Product rules (repeated because they get violated by default behaviors)

- **Names verbatim**: whatever the server calls a library/item is what we render.
  Icon choice may be heuristic; text never is.
- **Direct Play default**: never silently transcode. Transcoding and bitrate caps are
  an opt-in Quality setting (docs/18), off by default. Build the Android TV device profile
  (jellyfin-core `device_profile.rs` variant) to honestly advertise what the device
  + ffmpeg decoder extension can play, so the server doesn't "helpfully" transcode.
- **Fail open**: missing/unknown metadata shows content rather than hiding it.

## Test servers

The app and its live (non-default) test suites point at a Jellyfin dev server and,
optionally, a Jellyseerr instance through `JELLYBEAM_*` environment variables rather
than a hardcoded address, so any locally-run Jellyfin/Jellyseerr pair works:

1. **Jellyfin dev server**, default `http://localhost:8096`. `core/ffi/tests/live_local_server.rs`
   reads `JELLYBEAM_DEV_SERVER_URL`; the Seerr live test reads `JELLYBEAM_JELLYFIN_URL`
   (same default). Seeded synthetic users: `jellybeam-admin` / `jellybeam-user`, password
   `jellybeam-test`. From the Android **emulator**, localhost is `http://10.0.2.2:8096`. From a
   real TV device on the LAN, use the dev machine's LAN address.
2. **Jellyseerr (Discover verification)**, default `http://localhost:5055`, overridable with
   `JELLYBEAM_SEERR_URL`. `tools/dev-seerr/up.sh` brings one up beside a Jellyfin dev server,
   initializes it against the seeded admin account, and creates a synthetic local account
   (`local@example.test` / `jellybeam-test`) for exercising the Seerr-local login method. Its
   admin API key is written to an ignored local path and can also be supplied directly via
   `JELLYBEAM_SEERR_API_KEY`. From the emulator the instance is `http://10.0.2.2:5055`.
   `cargo test -p jellybeam-ffi -- --ignored live_seerr` covers all three Seerr sign-in methods
   against it.
3. **Showcase server (screenshots)**, `http://localhost:8098`. `tools/showcase-server/up.sh`
   builds a public-domain and CC-BY library from `catalog.tsv` (three Blender films downloaded
   for playback, runtime-accurate stubs for the rest), matches it against TMDB, and seeds
   Continue Watching and Next Up. User `sam` / `showcase`; from the emulator
   `http://10.0.2.2:8098`. Marketing and README screenshots come from this server only.
4. **Live servers (verification only, never hardcoded):** credentials and endpoints
   live only in ignored local storage. Read locally when needed; **never** commit,
   echo into shared output, or bake them into the Android app. Refer to any such
   endpoint only as "the live server" or `<REDACTED>`. Do not record its network,
   bandwidth, DNS behavior, account identity, or other environment fingerprints.

## Secrets & redaction

- Never commit real server addresses, tokens, or media titles. Grep every diff for
  local paths, names, endpoints, IDs, `MediaBrowser Token=`, `ApiKey=` (the Jellyfin 12
  form), and the legacy `api_key=`. Tests and examples use clearly synthetic values.
- App logs must redact server addresses and media titles/paths before they are shared.
- Android log tag: `JellybeamTV`, kept greppable and consistent across diagnostics.

## External playback contract

Jellybeam can start a specific item from the active saved server/account. Playback
uses normal resume semantics and the same Direct Play policy as an in-app Play
action. A mirror hit keeps the ordinary fast path unchanged; if reconciliation
has not cached the ID yet, Jellybeam performs one targeted lookup against the
active server before preparing playback. That fallback is not inserted into an
invented library scope—the next real reconciliation remains authoritative.

Explicit PLAY action with an item-id extra:

```sh
adb shell am start -a tv.jellybeam.action.PLAY \
  -p tv.jellybeam --es tv.jellybeam.extra.ITEM_ID '<item-id>'
```

Deep link:

```sh
adb shell am start -a android.intent.action.VIEW \
  -p tv.jellybeam -d 'jellybeam://play/<item-id>'
```

The exported entry point accepts only path-safe Jellyfin item identifiers; a
UUID in any spelling (undashed, upper case) is canonicalized to the dashed
lowercase form, so it plays exactly as the same item started from the UI. It
does not accept a server URL, token, account selector, transcode option, or media
URL. Jellybeam restores the active session and opens its mirror before a cold-start
request is dispatched; when signed out, it stays on Sign In and reports that an
account is required.

## Code comments

Comments are short contracts, not essays. A comment says only what the code cannot:

- **Keep**: the numbered-doc citation (`docs/16 §4.6`), the rule and the one
  reason it exists, thread/lock ownership, cancellation and generation rules,
  ordering and barrier reasons, units and null semantics, and in tests the one
  line saying what the test pins.
- **Cut**: narration of previous approaches or of how the code came to be, prose
  that restates the code beneath it, running per-line commentary, a rule
  re-explained where it is already stated (cite it instead), "see X's own doc
  comment" chains, rhetorical emphasis, and review/audit/finding labels.
- **Size**: one sentence is the default, three the ceiling; a list only when the
  contract is a list. A file whose comment lines exceed a quarter of its code
  lines is over budget.

## Git

- One commit per coherent change.
- No AI/tool attribution trailers on commits.
- `internal/` is ignored local storage and stays gitignored.

## Jellyfin 12 notes

Jellyfin 12 disables the legacy `api_key` URL query parameter and the legacy auth
headers by default; only the `Authorization: MediaBrowser ..., Token="..."` header and
the `ApiKey` query parameter (spelled exactly so) are accepted. Every REST call already
uses the header; the URL builders that hand a URL to something that fetches it itself
(image, trickplay-tile, and direct-stream URLs) use `ApiKey`, and the websocket upgrade
carries the token in the `Authorization` header rather than its query string. `ApiKey`
is also accepted by older supported server versions, so this is not a version-gated
change.

## Device profile

The `android_tv` device profile the core builds tells the server what the device and
the bundled ffmpeg decoder extension can actually play, so it doesn't "helpfully"
transcode. It declares direct-play containers, video codecs, and a maximalist static
audio codec list (the ffmpeg extension is always present, so audio direct-play is not
gated by per-device probing); video codec support is the one part that's genuinely
per-device, driven by an on-device `MediaCodecList` probe (per-codec presence, profile/
level ceilings, max resolution, HDR support) passed in from Kotlin. `MaxStreamingBitrate`
is always sent explicitly — omitting it makes the server apply a low default cap and
silently deny Direct Play. The profile deliberately omits fake interlace/level
constraints and never advertises transcoding as anything but a fallback.
