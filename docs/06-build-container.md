# 06 — Build container

A Docker image with Android SDK + NDK + Rust + cargo-ndk, so nothing gets
installed on the host.

## Two images

`docker-compose.yml` defines two build services with the same SDK, NDK and
Rust toolchain; `build.sh` picks one through `JELLYBEAM_BUILD_SERVICE`:

- **`android-build-arm64`** (`Dockerfile.arm64`, the default on Apple
  Silicon): a native `linux/arm64` Debian image. Gradle, Kotlin, JVM unit
  tests, lint, R8, rustc and cargo run native. Google ships `aapt2` and the
  NDK toolchain for Linux as x86_64 only, so those two run under Rosetta:
  the image installs the x86_64 glibc multiarch libraries
  (`libc6:amd64` and friends) and OrbStack executes amd64 ELF binaries
  inside the arm64 container. Measured on the same forced gate (app Kotlin
  compile + unit tests + lint): about a third of the wall time and well
  under half the CPU seconds of the amd64 image.
- **`android-build`** (`Dockerfile`): the original `linux/amd64` image,
  fully emulated under Rosetta. Kept as the fallback; select it with
  `JELLYBEAM_BUILD_SERVICE=android-build ./build.sh <cmd>`.

Each service has its own Gradle home and cargo registry volumes and its
own `CARGO_TARGET_DIR` (`core/target-docker` vs `core/target-docker-arm64`),
since host-arch artifacts differ; they share the debug keystore volume and
the bind-mounted repo, including `app/build/` and `app/src/main/jniLibs/`
(Android-side outputs are host-independent).

## What's inside

- amd64 image: `eclipse-temurin:21-jdk`, `linux/amd64`; arm64 image:
  `debian:trixie-slim` with `openjdk-21-jdk-headless`, plus the x86_64
  runtime libraries for `aapt2` and NDK clang
- Android cmdline-tools, `platforms;android-36`, `build-tools;35.0.0`,
  `platform-tools`
- NDK `28.2.13676358` (newest stable side-by-side NDK at build time) at
  `ANDROID_NDK_HOME`
- Rust via rustup, pinned by `core/rust-toolchain.toml`; targets
  `aarch64-linux-android`, `x86_64-linux-android` and
  `armv7-linux-androideabi`; `cargo-ndk` at the version the Dockerfiles pin
- `GRADLE_USER_HOME=/app/.gradle-home`, `CARGO_HOME=/opt/cargo`

## Usage

```
./build.sh image  # build the Docker image (first time, or after Dockerfile changes)
./build.sh core   # cross-compile core/ (media-cache, jellyfin-api, jellyfin-core) for Android
./build.sh test   # cargo test --workspace inside the container (host arch), then Gradle unit tests
./build.sh check  # pre-commit gate: Kotlin unit tests (release variant) + lint (no APK)
./build.sh check-fast [package]  # iteration gate: unit tests only, offline; optional dotted package prefix
./build.sh build  # debug APK (Gradle drives cargo-ndk + uniffi-bindgen)
./build.sh release # R8-minified release APK + its Baseline Profile .dm
./build.sh install # host-side: sideload the release APK together with the .dm
./build.sh shell  # interactive bash in the container
```

Pick the amd64 service only when the arm64 image will not build or run on
your host (an Intel machine, or a Docker runtime without amd64 emulation
inside arm64 containers): `JELLYBEAM_BUILD_SERVICE=android-build`.

## Local substation checkout

`core/ass-render` pins substation (the styled-subtitle renderer) to a git
commit. To build against a local checkout instead, keep both gitignored
files below; never commit either, or a local path.

- `core/.cargo/config.toml` patches the git source with a path relative to
  `core/`, so the same file resolves on the host and in the container:

  ```toml
  [patch."https://github.com/packetinspector/substation"]
  substation = { path = "../../substation" }
  ```

- `docker-compose.override.yml` mounts that checkout read-only at
  `/substation` for both services (`- <checkout>:/substation:ro`), then
  `docker compose up -d --force-recreate <service>`.

With the patch active, cargo rewrites the substation entries of
`core/Cargo.lock` without their git source; commit `Cargo.lock` only as
generated with the patch moved aside. Gradle reruns cargo when the
checkout's sources change.

## Running a debug build on an emulator or device

`install` is release-only. A debug APK goes on with plain `adb` from the
host, against a single attached device or with `ANDROID_SERIAL` set:

```sh
./build.sh build
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n tv.jellybeam/.MainActivity
```

A debug install carries no Baseline Profile, so its cold start is not the
number to judge; measure on `release` + `install` (docs/10). The emulator
reaches a server on the host at `10.0.2.2` (docs/05 "Test servers").

## Testing

Fastest to slowest, by scope:

- `./build.sh check-fast [package]` — offline Kotlin unit tests only, no lint; pass a
  dotted package prefix (e.g. `tv.jellybeam.player`) to narrow the run.
- `./build.sh check` — the pre-commit gate: `testReleaseUnitTest` + `lintDebug`, no APK.
- `./build.sh test` — `cargo test --workspace` inside the container (host arch; set
  `JELLYBEAM_TEST_JOBS` to change cargo's parallel jobs, default 2, which keeps the
  memory-limited container from being exhausted), then
  the full Gradle `testReleaseUnitTest` run.

One Kotlin test class: `./build.sh shell`, then inside the container
`./gradlew testReleaseUnitTest --tests 'tv.jellybeam.player.SomeTest'`.

Rust, run from `core/` inside the container (`./build.sh shell`) or on the host if you
have the toolchain installed:

```sh
cargo test --workspace
cargo nextest run --workspace   # if installed; faster, same coverage
cargo fmt --check
cargo clippy --workspace -- -D warnings
```

Live tests that need a running Jellyfin dev server (and, for Seerr, a Jellyseerr
instance — see docs/05 "Test servers") are `#[ignore]`d by default and run explicitly:

```sh
cargo test -p jellybeam-ffi -- --ignored live_sign_in
cargo test -p jellybeam-ffi -- --ignored live_seerr
cargo test -p jellybeam-ffi --test live_local_server -- --ignored --nocapture
```

The last one also has a negative-control variant gated behind
`--features live-test-knobs`; both read `JELLYBEAM_DEV_SERVER_URL`.

## Signing releases

`./build.sh release` signs with `keystore.properties` when present and otherwise
with the debug key; a debug-signed APK is for local installs only and
`tools/releases/prepare.py` rejects it as not publishable. To sign with a real key,
drop a gitignored `keystore.properties` at the repo root with four keys:

```
storeFile=path/to/your.keystore
storePassword=...
keyAlias=...
keyPassword=...
```

`storeFile` resolves relative to the repository root. `app/build.gradle.kts` picks
this up automatically when the file is present and falls back to the debug keystore
when it isn't (`build.sh release` prints a warning in that case).

## Releases

Releases are built locally with `./build.sh release`; there is no CI. The APK at
`app/build/outputs/apk/release/app-release.apk` is what gets attached to a GitHub
release. Archive the R8 mapping file alongside it — `build.sh` already copies it to
`internal/mappings/<pg_map_id>.txt` — because crash reports name the map id and only
that file can retrace them.

What the release APK is built with, so nothing ships half-optimized:

- Kotlin: R8 full mode with resource shrinking, plus the embedded Baseline Profile.
- Rust core: `core/Cargo.toml` `[profile.release]` sets fat LTO on one codegen unit. It must
  never set `strip`: uniffi-bindgen's library mode reads its metadata from the `.so`'s
  `.symtab`. AGP strips the packaged copy instead, which needs `ndkVersion` in
  `app/build.gradle.kts` to match the image's NDK; with no match AGP silently packages the
  unstripped library.
- 32-bit ARM: the Rust code is built with `+neon` (`cargoNdkBuild` in `app/build.gradle.kts`),
  as the NDK already builds the bundled C (SQLite, ring) and FFmpeg. Nothing newer: no VFPv4,
  no `target-cpu`. `llvm-readelf -A` on the armeabi-v7a `libjellybeam_core.so` must read
  ARM v7 / VFPv3 / NEONv1.
- ABIs: `abiFilters` keeps only the three slices the core is built for. JNA's AAR brings
  x86, mips and armeabi copies of `libjnidispatch.so`; packaged, they let such a device
  install an APK that has no core to load.

After `install`, the dexopt line must read `status=speed-profile`; the script says so when it
does not. `status=verify` means the install carried no profile (a plain `adb install`), and
every cold start runs interpreted until the next background dexopt.

`release` also copies the R8 mapping to `internal/mappings/<pg_map_id>.txt`
(gitignored). A crash report's frames name the `pg_map_id` of the build they
came from; retrace them with that file, not with whatever the last build left in
`app/build/outputs/mapping/release/`:

```bash
retrace internal/mappings/<pg_map_id>.txt crash.txt
```

`install` is the one command that runs on the host, not in the container:
it needs the host `adb` and a single connected device, or `ANDROID_SERIAL`
set to the target when more than one is attached (the TV beside a running
emulator). It picks the `.dm`
whose API bucket matches the device (from
`app/build/outputs/apk/release/output-metadata.json`) and runs
`adb install-multiple` so ART compiles the app against the embedded
Baseline Profile at install time -- no manual `cmd package compile` ritual
afterwards. It refuses to install while the app's playback Activity is
resumed on the device (`--force` overrides). Devices below API 28, or an
APK installed by other means, still get the profile: ProfileInstaller
writes it on first launch and the next background dexopt compiles it (see
docs/10-perf-logging.md, "Profile status", to verify).

`core` runs `cargo ndk -t arm64-v8a -t x86_64 -t armeabi-v7a build --release
-p media-cache -p jellyfin-api -p jellyfin-core -p jellybeam-ffi` (explicit
`-p` flags, not `--workspace`), proving rusqlite's bundled SQLite C code and
rustls/ring both cross-compile with the NDK toolchain. Output lands under the
service's `CARGO_TARGET_DIR`, `core/target-docker-arm64/` for the default
service and `core/target-docker/` for the amd64 one, in
`{aarch64,x86_64,armv7}-linux-android*/release/`, including
`libjellybeam_core.so` — the cdylib the Android app loads. `build` and
`release` do not need `core` first: Gradle drives cargo-ndk itself and copies
the libraries into `app/src/main/jniLibs/`.

Each service sets its own `CARGO_TARGET_DIR` (docker-compose) so container
builds never share a target dir with host `cargo` — different toolchains
would clobber each other's caches.

## Cache volumes

The whole repo root is mounted at `/app`. Named volumes persist across runs:

- `gradle-cache` / `gradle-cache-arm64` → `/app/.gradle-home`
- `cargo-registry` / `cargo-registry-arm64` → `/opt/cargo/registry` (crates.io index + sources)
- `android-keystore` → `/root/.android` (shared by both images)

`core/target/` itself is *not* a named volume — it's part of the bind-mounted
repo root, so build output is visible on the host (and already gitignored).

### Unpublished update assets

`./build.sh release` builds the signed production candidate. Prepare its
APK, SDK-matched Baseline Profiles and GitHub metadata with
[tools/releases/prepare.py](../tools/releases/README.md); preparation never
publishes. `./build.sh update-fixture <code>` builds an isolated, minified
HTTPS emulator fixture after generating its local test CA. Production release
validation rejects fixture transport and debug signing. See
[the emulator flow](../tools/updates/README.md) and [docs/26](26-app-updates.md).
