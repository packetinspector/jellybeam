#!/usr/bin/env bash
# Run the pre-commit gates under timeouts and print one summary line (docs/06 "Gates").
set -u

usage() {
  cat <<'EOF'
Usage: tools/gate.sh [--rust-only | --kotlin-only | --fast <target> [test]] [--keep-lock]
                     [--rust-timeout S] [--check-timeout S]

Default: Rust tests, fmt + clippy, then ./build.sh check (Kotlin unit tests + lint).
  --rust-only       Rust lanes only
  --kotlin-only     ./build.sh check only
  --fast <target>   iteration gate: a dotted Kotlin package (tv.jellybeam.x) runs
                    ./build.sh check-fast <pkg>; anything else is a Rust crate run
                    as cargo test -p <crate> [test] on the host from core/
  --keep-lock       leave core/Cargo.lock as the run left it
  --rust-timeout S  per Rust lane (default 1500)
  --check-timeout S per Gradle lane (default 900)

core/Cargo.lock is copied aside before the run and copied back after, without git,
so implementer agents (who never run git) can use this too. Logs go to a temp dir
whose path is printed. Exit 0 only when every lane passed. Refuses to start while
another Gradle or container build is running.
EOF
}

mode=all fast="" filter="" keep_lock=0 rust_to=1500 check_to=900
while [ $# -gt 0 ]; do
  case "$1" in
    --rust-only) mode=rust ;;
    --kotlin-only) mode=kotlin ;;
    --fast) mode=fast; fast="${2:?--fast needs a target}"; shift
            case "${2:-}" in ''|--*) ;; *) filter="$2"; shift ;; esac ;;
    --keep-lock) keep_lock=1 ;;
    --rust-timeout) rust_to="${2:?}"; shift ;;
    --check-timeout) check_to="${2:?}"; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done

cd "$(dirname "$0")/.." || exit 2
SERVICE="${JELLYBEAM_BUILD_SERVICE:-android-build-arm64}"

# The container has no procps: list (or kill, with $1=kill) build processes via /proc.
# The bracketed patterns keep the scanning shell from matching itself.
cexec_procs() {
  docker compose exec -T "$SERVICE" sh -c '
    for d in /proc/[0-9]*; do
      c=$(tr "\0" " " < "$d/cmdline" 2>/dev/null) || continue
      case "$c" in *[G]radleWrapperMain*|*[c]argo\ test*|*[c]argo\ clippy*|*[r]ustc\ *)
        if [ "$1" = kill ]; then kill "${d#/proc/}" 2>/dev/null; else echo "${d#/proc/} $c"; fi ;;
      esac
    done' _ "${1:-list}" 2>/dev/null
}

# One Gradle run at a time: a host client (any checkout) or an orphan left inside the container.
busy=$(pgrep -fl 'docker.* exec .*(gradlew|cargo)|build\.sh (test|test-rust|lint-rust|check|check-fast|build|release|core|update-fixture)' || true)
if [ -z "$busy" ] && docker compose ps -q "$SERVICE" 2>/dev/null | grep -q .; then
  busy=$(cexec_procs)
fi
# tools/perf-ab.sh holds this marker while it measures: no builds during a measurement.
m=internal/perf-ab/.measuring
if [ -z "$busy" ] && [ -f "$m" ] && kill -0 "$(cat "$m")" 2>/dev/null; then
  busy="perf-ab measuring (pid $(cat "$m"))"
fi
if [ -n "$busy" ]; then
  echo "gate: another build is running; not starting:" >&2
  printf '  %s\n' "$busy" >&2
  exit 3
fi

logs=$(mktemp -d "${TMPDIR:-/tmp}/gate.XXXXXX")
lock=core/Cargo.lock
cp "$lock" "$logs/Cargo.lock.before"
summary=() failed=0

# run <name> <timeout> <cmd...>: alarm kills only the docker client, so a timeout also
# kills the build left running inside the container.
run() {
  local name="$1" to="$2"; shift 2
  perl -e 'alarm shift; exec @ARGV' "$to" "$@" >"$logs/$name.log" 2>&1
  local rc=$?
  if [ $rc -eq 142 ]; then
    cexec_procs kill
    echo timeout
  elif [ $rc -eq 0 ]; then echo ok
  else echo "fail($rc)"
  fi
}

# Sums every `test result:` line (one per test binary, doctests included).
rust_counts() {
  awk '/^test result:/ { for (i = 1; i <= NF; i++) { if ($i ~ /^passed/) p += $(i-1); if ($i ~ /^failed/) f += $(i-1); if ($i ~ /^ignored/) g += $(i-1) } }
       END { printf "%d pass/%d fail/%d ignored", p, f, g }' "$1"
}

lane() {
  local name="$1" res; shift
  res=$(run "$name" "$@")
  [ "$res" = ok ] || failed=1
  case "$name" in
    rust|rust-fast) summary+=("$name $(rust_counts "$logs/$name.log") ($res)") ;;
    rust-lint) summary+=("$(sed -n 's/^fmt=\([0-9]*\) clippy=\([0-9]*\)$/fmt \1 · clippy \2/p' "$logs/$name.log" | sed 's/ 0/ ok/g; s/ [1-9][0-9]*/ fail/g') ($res)") ;;
    *) summary+=("$name $res") ;;
  esac
}

if [ "$mode" = all ] || [ "$mode" = rust ]; then
  lane rust "$rust_to" ./build.sh test-rust
  lane rust-lint "$rust_to" ./build.sh lint-rust
fi
if [ "$mode" = all ] || [ "$mode" = kotlin ]; then
  lane check "$check_to" ./build.sh check
fi
if [ "$mode" = fast ]; then
  case "$fast" in
    *.*) lane check-fast "$check_to" ./build.sh check-fast "$fast" ;;
    *) lane rust-fast "$rust_to" bash -c 'cd core && exec cargo test -p "$1" ${2:+"$2"}' _ "$fast" "$filter" ;;
  esac
fi

if [ $keep_lock -eq 1 ]; then
  summary+=("lock kept")
elif cmp -s "$lock" "$logs/Cargo.lock.before"; then
  summary+=("lock unchanged")
else
  cp "$logs/Cargo.lock.before" "$lock"
  summary+=("lock reset")
fi

line="${summary[0]}"
for s in "${summary[@]:1}"; do line="$line · $s"; done
echo "$line"
echo "logs: $logs"
exit $failed
