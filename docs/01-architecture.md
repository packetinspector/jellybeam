# 01 — Architecture

## The shape

Rust core, Kotlin shell. One shared brain, thin platform UI — the Signal/Firefox
pattern.

```
┌──────────────────────────────────────────────────────┐
│  Kotlin / Jetpack Compose for TV (UI shell)          │
│  - Home shelves, library grid, detail, player OSD    │
│  - D-pad focus, leanback nav, overscan handling      │
│  - Media3 ExoPlayer + jellyfin ffmpeg-decoder        │
└───────────────▲──────────────────────────────────────┘
                │ UniFFI bindings (generated Kotlin)
┌───────────────┴──────────────────────────────────────┐
│  Rust core (.so via cargo-ndk, aarch64-linux-android)│
│  - media-cache: SQLite mirror, sync engine, queries  │
│  - jellyfin-api: HTTP client                         │
│  - jellyfin-core: device profile, reporting, backoff │
│  - playback policy: direct-play decisions, track     │
│    selection, next-up timing, skip prefs             │
└──────────────────────────────────────────────────────┘
```

## Division of responsibility

**Rust owns:** server communication, the SQLite mirror and all queries, sync
(initial breadth + delta/WebSocket + heal passes), playback *policy* (what URL to play,
which audio/subtitle track, when the next-up card fires, resume positions), progress
reporting, settings persistence semantics.

**Kotlin owns:** rendering, focus/input, Activity/Service lifecycle, and the decoder —
Media3 ExoPlayer executes what the Rust core decides. Kotlin never talks to the
Jellyfin server directly except where Media3 must stream media bytes (the stream URL is
produced by Rust).

The line to hold: **any function that decides something is Rust with unit tests; Kotlin
only executes decisions.** This pure-function discipline is what keeps fixes pinnable:
a decision bug reproduces and resolves as a unit test, not a UI repro.

## Rust core: crate layout

- `media-cache` — SQLite mirror, sync engine, queries (rusqlite compiles fine for Android).
- `jellyfin-api` — HTTP client (rustls for the TLS stack).
- `jellyfin-core` — device_profile, reporting, backoff, event_bus, playback types.
- `playback-policy` — pure decision logic: track selection / `resolve_track_selection`,
  `normalize_lang`, `next_episode_trigger_remaining_secs`, skip-length prefs, trickplay
  tile selection.
- `seerr-api` — Jellyseerr/Overseerr client for Discover (docs/14).
- `ffi` — the UniFFI crate exporting the app-facing surface.
  Two small policies stay in Kotlin by decision: `SkipSegment` (a duplicate of
  `playback-policy::segments` with no divergence) and `LoadRetryPolicy` (no Rust
  counterpart). Neither is on the Play-to-first-frame path, and porting either means a
  new per-tick FFI call; revisit if either policy changes.

Bindings: **UniFFI** (Mozilla). Keep the exported surface small and coarse-grained —
view-model-shaped snapshots and commands, not row-level DB access. Callbacks/streams
for MirrorChange events → Kotlin flows.

Build: `cargo-ndk` producing `libjellybeam_core.so` for `arm64-v8a`, `armeabi-v7a` and
`x86_64` (the emulator). A Gradle task wires the cargo build and `uniffi-bindgen` into
the APK build (docs/06).

## UI toolkit

Plain Jetpack Compose with the app's own TV focus handling (docs/15); no
`androidx.tv` artifacts, no Leanback, no XML views.

The theme tokens, layout proportions, shelf/grid structure, and focus-ring behavior
that define the visual style map naturally onto TV focus states.

## Player backend

Media3 ExoPlayer **1.11.1** + `org.jellyfin.media3:media3-ffmpeg-decoder`. libmpv is
explicitly rejected for TV: Media3 is the certified path for MediaCodec hardware
decode, HDR/passthrough per chipset, and the ffmpeg extension covers software audio
formats (DTS, TrueHD…) without server transcoding — which keeps the Direct-Play-first
rule intact.

## Repo layout

```
jellybeam/
  docs/                  # specs of record (docs/00 is the map)
  core/                  # Rust workspace
    media-cache/
    jellyfin-api/
    jellyfin-core/
    playback-policy/     # pure decision logic, see above
    seerr-api/
    ffi/                 # UniFFI crate exporting the app-facing surface
  app/                   # Android app (Kotlin, Compose)
  tools/                 # contributor tools: dev Seerr, fake Seerr, emulator stress
  build.sh  Dockerfile  Dockerfile.arm64  docker-compose.yml
  gradle/  build.gradle.kts  settings.gradle.kts
```

## Platform decisions that hold

1. **rusqlite on Android.** The bundled SQLite compiles under cargo-ndk for all three
   ABIs; the mirror opens, syncs and queries on-device with no platform SQLite.
2. **Coarse FFI.** The exported surface is snapshots and commands, never per-frame or
   row-level calls; anything that would be called per tick stays on one side of the
   boundary.
3. **Mirror location.** The database and session state live under
   `context.filesDir/jellybeam`; images are cached by Coil on the Kotlin side under the tagged image URLs
   the mirror produces, so a changed image tag is a cache miss and a stale one is never shown.
4. **Background lifecycle.** Android stops backgrounded apps; the websocket and sync
   resume on foreground, and the mirror's delta plus heal passes make a missed window
   harmless.
