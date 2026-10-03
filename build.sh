#!/bin/bash
# Helper script for building Jellybeam TV in Docker (Android SDK/NDK + Rust +
# cargo-ndk container).

set -e

cd "$(dirname "$0")"

# Build the Docker image if needed
build_image() {
    echo "Building Docker image for $SERVICE..."
    docker compose build "$SERVICE"
}

# Run a command in the long-lived build container, starting it if needed.
# `exec` (vs `run --rm`) is what keeps the Gradle daemon warm between
# invocations -- a fresh container per command meant a cold daemon (JVM +
# Kotlin compiler warmup, minutes under Rosetta) every time.
# JELLYBEAM_BUILD_SERVICE picks the compose service: android-build-arm64 (native on
# Apple Silicon, the default; Dockerfile.arm64) or android-build (amd64 under
# Rosetta; Dockerfile). See docs/06-build-container.md "Two images".
SERVICE="${JELLYBEAM_BUILD_SERVICE:-android-build-arm64}"
run_cmd() {
    docker compose up -d --no-recreate "$SERVICE" >/dev/null 2>&1
    docker compose exec -T "$SERVICE" "$@"
}

case "${1:-shell}" in
    image)
        build_image
        ;;
    core)
        echo "Cross-compiling Rust workspace crates for Android (arm64-v8a, x86_64, armeabi-v7a)..."
        run_cmd bash -c '
            set -e
            cd /app/core
            cargo ndk -t arm64-v8a -t x86_64 -t armeabi-v7a build --release \
                -p media-cache -p jellyfin-api -p jellyfin-core -p jellybeam-ffi
        '
        echo ""
        echo "Built libs (per-ABI release/ dirs under the service's CARGO_TARGET_DIR):"
        run_cmd bash -c 'ls -d "$CARGO_TARGET_DIR"/*-linux-android*/release | sed "s|^/app/|  |"' 
        ;;
    build)
        echo "Building Jellybeam TV debug APK (Gradle drives cargo-ndk + uniffi-bindgen)..."
        run_cmd ./gradlew assembleDebug
        echo ""
        echo "APK: app/build/outputs/apk/debug/app-debug.apk"
        ;;
    release)
        echo "Building Jellybeam TV release APK (R8 minified; signed with keystore.properties, else the debug key -- not publishable, docs/06)..."
        test -f keystore.properties || echo "WARNING: no keystore.properties -- this APK is debug-signed and tools/releases/prepare.py will reject it."
        run_cmd ./gradlew assembleRelease
        # Keep every release's R8 mapping under its pg_map_id (gitignored internal/):
        # a crash report names the map id of the build it came from, and the next
        # assembleRelease overwrites app/build/outputs/mapping/release/mapping.txt.
        mapping="app/build/outputs/mapping/release/mapping.txt"
        if [ -f "$mapping" ]; then
            map_id=$(sed -n 's/^# pg_map_id: //p' "$mapping" | head -1)
            if [ -n "$map_id" ]; then
                mkdir -p internal/mappings
                cp "$mapping" "internal/mappings/$map_id.txt"
                echo "Mapping archived: internal/mappings/$map_id.txt"
            fi
        fi
        echo ""
        echo "APK: app/build/outputs/apk/release/app-release.apk"
        echo "DM:  app/build/outputs/apk/release/baselineProfiles/<n>/app-release.dm (per-API buckets, see output-metadata.json)"
        echo ""
        echo "Install with: ./build.sh install"
        ;;
    update-fixture)
        fixture_code="${2:?Pass a synthetic fixture version code}"
        case "$fixture_code" in *[!0-9]*|'') echo "Fixture version code must be numeric."; exit 1;; esac
        test -f internal/updates/ca.pem || { echo "Generate the local fixture CA first (tools/updates/README.md)."; exit 1; }
        # Overwrites app/build/outputs/apk/release/app-release.apk: prepare/copy each fixture
        # build before building the next (tools/updates/README.md).
        run_cmd ./gradlew assembleRelease -Pjellybeam.updateFixture=true "-Pjellybeam.updateVersionCode=$fixture_code" "-Pjellybeam.updateVersionName=${3:-0.1.6}"
        ;;
    test)
        echo "Running Rust workspace tests (host arch, proves the container toolchain)..."
        # JELLYBEAM_TEST_JOBS caps cargo's parallel compiles (default 2) so the
        # memory-limited build container is not exhausted (docs/06).
        run_cmd env "CARGO_BUILD_JOBS=${JELLYBEAM_TEST_JOBS:-2}" bash -c 'cd /app/core && cargo test --workspace'
        echo ""
        echo "Running Gradle JVM unit tests..."
        run_cmd ./gradlew testReleaseUnitTest
        ;;
    check)
        # One unit-test variant: debug and release run the same sources, so the second run
        # only doubled the gate's cost.
        echo "Pre-commit gate: Kotlin unit tests (release variant) + lint (no APK -- use 'release' for an installable build)..."
        run_cmd ./gradlew testReleaseUnitTest lintDebug
        ;;
    check-fast)
        # Iteration gate: unit tests only, offline, optionally one package (dotted prefix).
        # Lint waits for 'check' before the commit.
        filter=()
        if [ -n "${2:-}" ]; then
            filter=(--tests "${2}.*")
            echo "Iteration gate: unit tests under ${2} (offline, no lint)..."
        else
            echo "Iteration gate: Kotlin unit tests (offline, no lint)..."
        fi
        run_cmd ./gradlew testReleaseUnitTest --offline "${filter[@]}"
        ;;
    install)
        # Host-side (real device/emulator over USB/network adb) -- deliberately
        # NOT run via run_cmd/docker, since the build container has no adb
        # connection to the device this installs onto.
        echo "Installing Jellybeam TV release build via host adb..."

        if ! command -v adb >/dev/null 2>&1; then
            echo "adb not found on PATH -- install Android platform-tools and try again."
            exit 1
        fi

        # ANDROID_SERIAL (adb's own target variable) picks the device when more than one
        # is attached, e.g. the TV over network adb beside a running emulator.
        if [ -n "${ANDROID_SERIAL:-}" ]; then
            if ! adb devices | grep -qxF "${ANDROID_SERIAL}"$'\tdevice'; then
                echo "ANDROID_SERIAL is set but that device is not attached (adb devices)."
                exit 1
            fi
        else
            device_count=$(adb devices | grep -c $'\tdevice$' || true)
            if [ "$device_count" -ne 1 ]; then
                echo "Expected exactly one connected device/emulator (adb devices), found $device_count -- or set ANDROID_SERIAL."
                exit 1
            fi
        fi

        playback_active=false
        if adb shell dumpsys activity activities 2>/dev/null | grep -q 'topResumedActivity=.*tv.jellybeam/.player.PlaybackActivity'; then
            playback_active=true
        fi
        if [ "$playback_active" = true ] && [ "${2:-}" != "--force" ]; then
            echo "playback is active on the device; refusing to install"
            exit 1
        fi

        apk="app/build/outputs/apk/release/app-release.apk"
        metadata="app/build/outputs/apk/release/output-metadata.json"
        if [ ! -f "$apk" ] || [ ! -f "$metadata" ]; then
            echo "$apk (or its output-metadata.json) not found -- run './build.sh release' first."
            exit 1
        fi

        sdk=$(adb shell getprop ro.build.version.sdk | tr -d '\r')

        # output-metadata.json's "baselineProfiles" array maps each
        # [minApi, maxApi] bucket to its own app-release.dm -- read it fresh
        # every install rather than hardcoding which index (0 or 1 today)
        # covers this device's API level, since AGP owns that mapping.
        dm_rel=$(python3 - "$sdk" "$metadata" <<'PY'
import json
import sys

sdk = int(sys.argv[1])
with open(sys.argv[2]) as f:
    data = json.load(f)

for entry in data.get("baselineProfiles", []):
    if entry["minApi"] <= sdk <= entry["maxApi"]:
        print(entry["baselineProfiles"][0])
        break
PY
        )

        if [ -n "$dm_rel" ]; then
            dm="app/build/outputs/apk/release/$dm_rel"
            echo "API $sdk -> $dm"
            adb install-multiple -r "$apk" "$dm"
        else
            echo "No Baseline Profile bucket covers API $sdk -- installing the APK alone."
            adb install -r "$apk"
            echo "The profile will install on first launch and compile at the next background dexopt."
        fi

        echo ""
        echo "dexopt status:"
        if ! adb shell dumpsys package dexopt | grep -A3 'tv.jellybeam'; then
            echo "(no dexopt entry found for tv.jellybeam yet)"
        fi
        # API 31+ reports `verify` when the install carried no usable profile; older releases
        # print `speed-profile` either way, so this can only warn, never prove.
        if adb shell dumpsys package dexopt | grep -A3 'tv.jellybeam' | grep -q 'status=verify'; then
            echo ""
            echo "WARNING: not profile-compiled (status=verify); cold start will run interpreted."
            echo "Fix: adb shell cmd package compile -m speed-profile -f tv.jellybeam"
        fi
        ;;
    stop)
        echo "Stopping the build container (and its warm Gradle daemon)..."
        docker compose down
        ;;
    shell)
        echo "Opening shell in container..."
        run_cmd bash
        ;;
    *)
        echo "Usage: $0 {image|core|build|release|install|test|check|check-fast [package]|update-fixture <code>|stop|shell}"
        echo ""
        echo "Commands:"
        echo "  image    - Build the Docker image (Android SDK + NDK + Rust + cargo-ndk)"
        echo "  core     - Cross-compile the Rust workspace (core/) for Android via cargo-ndk"
        echo "  build    - Build the debug APK"
        echo "  release  - Build the release APK (R8 minified, includes the embedded Baseline Profile)"
        echo "  install  - Install the release APK + matching Baseline Profile (.dm) via host adb"
        echo "  test     - Run cargo test --workspace inside the container, then the Gradle unit tests"
        echo "  check    - Pre-commit gate: Gradle unit tests (release variant) + lint"
        echo "  update-fixture <code> - Local HTTPS emulator fixture; rejected by production release validation"
        echo "  check-fast [package] - Iteration gate: unit tests only, offline; optional dotted package prefix, e.g. tv.jellybeam.ui.settings"
        echo "  shell    - Open an interactive bash shell in the container"
        exit 1
        ;;
esac
