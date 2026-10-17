#!/usr/bin/env bash
# Build helpers for tools/perf-ab.sh (sourced): ref -> cached release APK + .dm, built in a throwaway
# worktree under the checkout (the build container mounts the checkout as /app). Needs ROOT OUT die.
SERVICE="${JELLYBEAM_BUILD_SERVICE:-android-build-arm64}"
WT="" LINK="" APK_CACHE="$ROOT/internal/perf-ab/apk"

cleanup_build() {
  [ -n "$LINK" ] && rm -f "$LINK"
  [ -n "$WT" ] && git worktree remove --force "$WT" >/dev/null 2>&1
  WT="" LINK=""
}

# One Gradle run at a time (same patterns as tools/gate.sh): a host client or an orphan in the container.
refuse_if_building() {
  local busy m
  busy=$(pgrep -fl 'docker.* exec .*(gradlew|cargo)|build\.sh (test|test-rust|lint-rust|check|check-fast|build|release|core|update-fixture)' || true)
  if [ -z "$busy" ] && docker compose ps -q "$SERVICE" 2>/dev/null | grep -q .; then
    busy=$(docker compose exec -T "$SERVICE" sh -c '
      for d in /proc/[0-9]*; do c=$(tr "\0" " " < "$d/cmdline" 2>/dev/null) || continue
        case "$c" in *[G]radleWrapperMain*|*[c]argo\ test*|*[c]argo\ clippy*|*[r]ustc\ *) echo "${d#/proc/} $c";; esac
      done' 2>/dev/null)
  fi
  m=internal/perf-ab/.measuring
  [ -z "$busy" ] && [ -f "$m" ] && kill -0 "$(cat "$m")" 2>/dev/null && busy="perf-ab measuring (pid $(cat "$m"))"
  [ -z "$busy" ] || { printf '%s\n' "$busy" >&2; die "another build is running; not starting"; }
}

resolve() { # $1 spec -> sets SHA ("" for an APK path) and APK (final path; may not exist yet)
  if [ -f "$1" ]; then SHA=""; APK=$(cd "$(dirname "$1")" && pwd)/$(basename "$1")
  else
    SHA=$(git rev-parse --verify --quiet "$1^{commit}") || die "not a ref or an APK file: $1"
    APK="$APK_CACHE/$SHA/app-release.apk"
  fi
}

build_sha() { # $1 full sha
  local sha=$1 wt sf rel
  wt="internal/worktrees/perf-ab-${sha:0:9}"
  test -f keystore.properties || die "no keystore.properties: a debug-signed APK cannot update the installed release app"
  git worktree remove --force "$wt" >/dev/null 2>&1
  git worktree add --detach "$wt" "$sha" >/dev/null 2>&1 || die "worktree add failed for ${sha:0:9}"
  WT=$wt
  # Same signing key as the installed app, so the install keeps app data (docs/06).
  cp keystore.properties "$wt/"
  sf=$(sed -n 's/^storeFile=//p' keystore.properties)
  case "$sf" in /*|"") ;; *) mkdir -p "$wt/$(dirname "$sf")"; cp "$sf" "$wt/$sf" || die "keystore $sf missing";; esac
  if [ -f core/.cargo/config.toml ]; then # local substation path patch + the symlink Gradle's ../substation input needs
    mkdir -p "$wt/core/.cargo"; cp core/.cargo/config.toml "$wt/core/.cargo/"
    if [ -d ../substation ] && [ ! -e internal/worktrees/substation ]; then
      LINK=internal/worktrees/substation; ln -s ../../../substation "$LINK"
    fi
  fi
  echo "building ${sha:0:9} (log: $OUT/build-${sha:0:9}.log)"
  docker compose up -d --no-recreate "$SERVICE" >/dev/null 2>&1
  # build.sh's run_cmd always runs in /app (the main tree), so exec into the worktree directly. A
  # per-worktree CARGO_TARGET_DIR keeps it from overwriting the main tree's artifacts.
  perl -e 'alarm shift; exec @ARGV' 3000 docker compose exec -T -w "/app/$wt" \
    -e "CARGO_TARGET_DIR=/app/$wt/core/target-docker${SERVICE#android-build}" "$SERVICE" \
    ./gradlew --console=plain assembleRelease > "$OUT/build-${sha:0:9}.log" 2>&1 || die "build failed or timed out for ${sha:0:9}"
  rel="$wt/app/build/outputs/apk/release"
  test -f "$rel/app-release.apk" || die "no APK produced for ${sha:0:9}"
  mkdir -p "$APK_CACHE/$sha"
  cp "$rel/app-release.apk" "$rel/output-metadata.json" "$APK_CACHE/$sha/"
  cp -R "$rel/baselineProfiles" "$APK_CACHE/$sha/" 2>/dev/null || echo "WARNING: no baselineProfiles dir in ${sha:0:9}'s output"
  cleanup_build
}
