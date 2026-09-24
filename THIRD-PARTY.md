# Third-party dependencies

Direct dependencies of the shipping app, and the licence each is
distributed under. Jellybeam TV's own code is GPL-3.0-or-later — see
`LICENSE`; its name and artwork are reserved, see `TRADEMARKS.md`.

## Android (Kotlin)

| Dependency | Version | Licence |
|---|---|---|
| androidx.activity:activity-compose | 1.12.2 | Apache-2.0 |
| androidx.compose.foundation:foundation | 1.10.0 | Apache-2.0 |
| androidx.compose.ui:ui | 1.10.0 | Apache-2.0 |
| androidx.compose.ui:ui-tooling-preview | 1.10.0 | Apache-2.0 |
| androidx.core:core-ktx | 1.17.0 | Apache-2.0 |
| androidx.core:core-splashscreen | 1.0.1 | Apache-2.0 |
| androidx.lifecycle:lifecycle-runtime-ktx | 2.8.7 | Apache-2.0 |
| androidx.lifecycle:lifecycle-viewmodel-ktx | 2.8.7 | Apache-2.0 |
| androidx.lifecycle:lifecycle-viewmodel-compose | 2.8.7 | Apache-2.0 |
| androidx.media3:media3-exoplayer | 1.9.0 | Apache-2.0 |
| androidx.media3:media3-ui | 1.9.0 | Apache-2.0 |
| androidx.media3:media3-exoplayer-hls | 1.9.0 | Apache-2.0 |
| androidx.media3:media3-datasource-okhttp | 1.9.0 | Apache-2.0 |
| androidx.media3:media3-session | 1.9.0 | Apache-2.0 |
| androidx.profileinstaller:profileinstaller | 1.4.1 | Apache-2.0 |
| org.jellyfin.media3:media3-ffmpeg-decoder | 1.8.0+1 | **GPL-3.0** |
| io.coil-kt:coil-compose | 2.7.0 | Apache-2.0 |
| org.jetbrains.kotlinx:kotlinx-coroutines-android | 1.9.0 | Apache-2.0 |
| com.android.tools:desugar_jdk_libs | 2.1.2 | Apache-2.0 |
| net.java.dev.jna:jna (`@aar`) | 5.14.0 | Apache-2.0 / LGPL-2.1 (dual-licensed) |
| com.google.zxing:core | 3.5.3 | Apache-2.0 |

`org.jellyfin.media3:media3-ffmpeg-decoder` is GPL-3.0, unlike the rest of
this table — it's what lets Jellybeam decode TrueHD/DTS/DTS-HD/etc. without
a server transcode.

Test-only dependencies (`junit:junit`, `org.jetbrains.kotlinx:kotlinx-coroutines-test`)
are not listed — they never ship in the APK.

## Fonts

| Font | Source | Licence |
|---|---|---|
| Archivo | Google Fonts | OFL-1.1 |
| Martian Mono | Google Fonts | OFL-1.1 |
| Bagel Fat One | Google Fonts | OFL-1.1 |

## Rust (`core/`)

Direct dependencies of the workspace's shipping crates (`ffi`, `jellyfin-api`,
`jellyfin-core`, `media-cache`, `playback-policy`, `seerr-api`); internal
path dependencies between these crates aren't listed. Versions are the ones
locked in `core/Cargo.lock`.

| Crate | Version | Licence |
|---|---|---|
| tokio | 1.53.1 | MIT |
| serde | 1.0.229 | MIT OR Apache-2.0 |
| serde_json | 1.0.151 | MIT OR Apache-2.0 |
| thiserror | 2.0.20 | MIT OR Apache-2.0 |
| tracing | 0.1.44 | MIT |
| uniffi | 0.32.0 | MPL-2.0 |
| chrono | 0.4.45 | MIT OR Apache-2.0 |
| url | 2.5.8 | MIT OR Apache-2.0 |
| uuid | 1.25.0 | Apache-2.0 OR MIT |
| futures-util | 0.3.34 | MIT OR Apache-2.0 |
| reqwest | 0.12.28 | MIT OR Apache-2.0 |
| tokio-tungstenite | 0.24.0 | MIT |
| rusqlite (bundled SQLite) | 0.32.1 | MIT (rusqlite); the bundled SQLite C library itself is public domain |

`reqwest` and `tokio-tungstenite` are built with `rustls` (via `ring`), not
OpenSSL — no system TLS dependency.

Test-only dependencies (`tempfile`) are not listed — they never ship in
the compiled library.

Licences above come from each crate's own `Cargo.toml` `license` field as
resolved in `core/Cargo.lock`, read from the local cargo registry cache.
