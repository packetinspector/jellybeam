#!/usr/bin/env bash
# A/B perf comparison of two builds on one device: cold start, time-to-first-frame, Home hero dwell.
set -u

usage() {
  cat <<'EOF2'
Usage: tools/perf-ab.sh A B [--rounds 8] [--device emu|tv] [--metrics cold,ttff,dwell]
                        [--allow-account-switch] [--out DIR] [--help]

A, B       a git ref (built in a throwaway worktree, cached by sha in internal/perf-ab/apk) or a release APK path
           (a sibling output-metadata.json + baselineProfiles/ supplies the .dm).
--rounds   build slots, a multiple of 4: first half A/B/A/B.., second half B/A/B/A.. (default 8).
           Per slot and build: 10 cold, 8 TTFF, 5 dwell + 5 quick runs (COLD_N TTFF_N DWELL_N);
           run 0 of each metric is a warm-up the stats drop.
--device   emu (default) or tv. TV: runs .claude/skills/tv-install/scripts/device-busy.sh first and
           exits when busy; defaults to cold,ttff; needs JB_ITEM_ID (a playable item for the app's
           signed-in account; ttff reports progress for it); dwell plays whatever the Home hero is.
--metrics  cold (process.start -> home.dataReady), ttff (PLAY intent -> firstFrame),
           dwell (also runs quick; Home hero Select after a 2 s hold / inside 400 ms).
--allow-account-switch  emu only: run tools/emu-signin.sh first. Otherwise the app is assumed to be
           signed in already and no account is ever touched.
--out      default internal/perf-ab/<timestamp>; results.csv, summary.md and logs/ land there.
Serials: TV / EMU from the environment, else internal/device.env. Item for ttff on emu: JB_ITEM_ID, else
the first unplayed 5+ minute movie on JB_SERVER (default http://10.0.2.2:8098, JB_USER sam, JB_PASS showcase;
tools/showcase-server). Emulator: mini player is forced off (adb root) and restored; on the TV it must be off.
Stop playback and screensavers first; no builds, adb installs or other input while this runs.
EOF2
}
die() { echo "perf-ab: $*" >&2; exit 2; }

ROUNDS=8 DEVICE=emu METRICS="" ACCEPT_SWITCH=0 OUT="" pos=""
while [ $# -gt 0 ]; do
  case "$1" in
    --rounds) ROUNDS="${2:?}"; shift ;;
    --device) DEVICE="${2:?}"; shift ;;
    --metrics) METRICS="${2:?}"; shift ;;
    --allow-account-switch) ACCEPT_SWITCH=1 ;;
    --out) OUT="${2:?}"; shift ;;
    -h|--help) usage; exit 0 ;;
    -*) usage >&2; die "unknown option: $1" ;;
    *) pos="$pos|$1" ;;
  esac
  shift
done
SPEC_A=$(cut -d'|' -f2 <<<"$pos"); SPEC_B=$(cut -d'|' -f3 <<<"$pos")
[ -n "$SPEC_A" ] && [ -n "$SPEC_B" ] || { usage >&2; exit 2; }
case "$DEVICE" in emu|tv) ;; *) die "--device must be emu or tv" ;; esac
[ -n "$METRICS" ] || { [ "$DEVICE" = tv ] && METRICS=cold,ttff || METRICS=cold,ttff,dwell; }
for m in $(tr , ' ' <<<"$METRICS"); do case "$m" in cold|ttff|dwell) ;; *) die "unknown metric: $m" ;; esac; done
case "$ROUNDS" in ''|*[!0-9]*) die "--rounds must be a number" ;; esac
[ "$ROUNDS" -ge 4 ] && [ $((ROUNDS % 4)) -eq 0 ] || die "--rounds must be a multiple of 4"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT" || exit 2
PKG=tv.jellybeam COLD_N=${COLD_N:-10} TTFF_N=${TTFF_N:-8} DWELL_N=${DWELL_N:-5}
[ -n "$OUT" ] || OUT="internal/perf-ab/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT/logs"; OUT="$(cd "$OUT" && pwd)"; CSV="$OUT/results.csv"
. tools/perf-ab-build.sh; . tools/perf-ab-lib.sh

tv_env=${TV:-} emu_env=${EMU:-}
[ -f internal/device.env ] && . internal/device.env
[ -n "$tv_env" ] && TV=$tv_env; [ -n "$emu_env" ] && EMU=$emu_env
if [ "$DEVICE" = tv ]; then SERIAL=${TV:-}; else SERIAL=${EMU:-}; fi
[ -n "$SERIAL" ] || die "no serial for $DEVICE: set \$$(echo "$DEVICE" | tr a-z A-Z) or internal/device.env"
export ANDROID_SERIAL="$SERIAL"
A get-state >/dev/null 2>&1 || die "$DEVICE is not reachable over adb"
SDK=$(A shell getprop ro.build.version.sdk | tr -d '\r')

check_tv_free() {
  [ "$DEVICE" = tv ] || return 0
  local bs=.claude/skills/tv-install/scripts/device-busy.sh o
  [ -f "$bs" ] || die "$bs is missing: refusing to touch the TV (fail closed)"
  o=$(bash "$bs" "$SERIAL") || { printf '%s\n' "$o" | sed "s/$SERIAL/<tv>/g" >&2; die "TV is busy or unreachable"; }
  case "$o" in *tv.jellybeam*) ;; *) echo "note: another app is in front on the TV; confirm nothing else is in use" ;; esac
}

resolve_item() {
  case ",$METRICS," in *,ttff,*) ;; *) return 0 ;; esac
  ITEM="${JB_ITEM_ID:-}"; [ -n "$ITEM" ] && return 0
  [ "$DEVICE" = tv ] && die "ttff on the TV needs JB_ITEM_ID"
  ITEM=$(JB_USER="${JB_USER:-sam}" JB_PASS="${JB_PASS:-showcase}" python3 -I - "${JB_SERVER:-http://10.0.2.2:8098}" <<'PY'
import json, os, sys, urllib.request as u
base = sys.argv[1].replace("10.0.2.2", "localhost")  # the emulator's host alias, seen from the host
hdr = 'MediaBrowser Client="perf-ab", Device="host", DeviceId="perf-ab", Version="1"'
def call(path, body=None, tok=None):
    h = {"Content-Type": "application/json", "Authorization": hdr + (', Token="%s"' % tok if tok else "")}
    return json.load(u.urlopen(u.Request(base + path, json.dumps(body).encode() if body else None, h), timeout=15))
a = call("/Users/AuthenticateByName", {"Username": os.environ["JB_USER"], "Pw": os.environ["JB_PASS"]})
r = call("/Users/%s/Items?IncludeItemTypes=Movie&Recursive=true&Filters=IsUnplayed&Fields=RunTimeTicks&SortBy=SortName&Limit=50" % a["User"]["Id"], tok=a["AccessToken"])
print(next((i["Id"] for i in r["Items"] if i.get("RunTimeTicks", 0) >= 3e9), ""))
PY
  ) || ITEM=""
  [ -n "$ITEM" ] || die "no item found on the server; set JB_ITEM_ID"
}

# Emulator only (adb root): mini player off for the run, original settings.json restored on exit.
SETTINGS=/data/user/0/$PKG/files/jellybeam/settings.json
mini_player_off() {
  if [ "$DEVICE" = tv ]; then echo "mini player: cannot be checked without root; it must be off (default)"; return 0; fi
  A root >/dev/null 2>&1; sleep 2
  A shell am force-stop "$PKG"
  A pull "$SETTINGS" "$OUT/settings.orig.json" >/dev/null 2>&1 || { echo "mini player: no settings.json yet (default off)"; return 0; }
  if grep -Eq '"mini_player_enabled" *: *true' "$OUT/settings.orig.json"; then
    perl -pe 's/("mini_player_enabled"\s*:\s*)true/${1}false/' "$OUT/settings.orig.json" > "$OUT/settings.off.json"
    A push "$OUT/settings.off.json" "$SETTINGS" >/dev/null 2>&1; RESTORE_MINI=1; echo "mini player: forced off (restored on exit)"
  fi
}
RESTORE_MINI=0
finish() {
  cleanup_build
  [ "$(cat internal/perf-ab/.measuring 2>/dev/null)" = $$ ] && rm -f internal/perf-ab/.measuring
  if [ "$RESTORE_MINI" = 1 ]; then A shell am force-stop "$PKG"; A push "$OUT/settings.orig.json" "$SETTINGS" >/dev/null 2>&1; fi
  A shell setprop log.tag.JellybeamTV INFO >/dev/null 2>&1
}
trap finish EXIT; trap 'exit 130' INT TERM

check_tv_free
resolve "$SPEC_A"; SHA_A=$SHA; APK_A=$APK; resolve "$SPEC_B"; SHA_B=$SHA; APK_B=$APK
if { [ -n "$SHA_A" ] && [ ! -f "$APK_A" ]; } || { [ -n "$SHA_B" ] && [ ! -f "$APK_B" ]; }; then
  refuse_if_building
  [ -n "$SHA_A" ] && [ ! -f "$APK_A" ] && build_sha "$SHA_A"
  [ -n "$SHA_B" ] && [ ! -f "$APK_B" ] && build_sha "$SHA_B"
fi
[ -f "$APK_A" ] && [ -f "$APK_B" ] || die "APK missing"
check_tv_free   # builds take minutes
resolve_item
mini_player_off
if [ "$ACCEPT_SWITCH" = 1 ]; then
  [ "$DEVICE" = emu ] && [ -f tools/emu-signin.sh ] || die "--allow-account-switch needs --device emu and tools/emu-signin.sh"
  bash tools/emu-signin.sh || die "emu-signin failed"
else echo "assuming the app is already signed in on the $DEVICE (no account is touched)"; fi

echo $$ > internal/perf-ab/.measuring   # tools/gate.sh and refuse_if_building honour it
echo "order,round,build,metric,run,value_ms,note" > "$CSV"
echo "A = ${SHA_A:-$APK_A}"; echo "B = ${SHA_B:-$APK_B}"
half=$((ROUNDS / 2)) ROUND=1
while [ "$ROUND" -le "$ROUNDS" ]; do
  if [ "$ROUND" -le "$half" ]; then ORDER=AB; i=$ROUND; else ORDER=BA; i=$((ROUND - half)); fi
  if [ $((i % 2)) -eq 1 ]; then [ "$ORDER" = AB ] && b=A || b=B; else [ "$ORDER" = AB ] && b=B || b=A; fi
  if [ "$b" = A ]; then measure_slot A "$APK_A"; else measure_slot B "$APK_B"; fi
  ROUND=$((ROUND + 1))
done
python3 -I tools/perf_ab_stats.py "$CSV" | tee "$OUT/summary.md"
