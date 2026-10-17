#!/usr/bin/env bash
# Device-side helpers for tools/perf-ab.sh (sourced). Needs SERIAL PKG CSV OUT ITEM DEVICE ORDER ROUND.
# Marks are `perf startup|playback phase=NAME atMs=N` lines on logcat tag JellybeamTV (docs/10).
A() { adb -s "$SERIAL" "$@"; }
log_dump() { A logcat -d -s JellybeamTV:V 2>/dev/null | tr -d '\r'; }
mark() { sed -n "s/.*phase=$1 atMs=\([0-9]*\).*/\1/p" | head -1; } # stdin = log
resumed() { A shell dumpsys activity activities 2>/dev/null | tr -d '\r' | grep -m1 'ResumedActivity'; }
wake() { A shell input keyevent KEYCODE_WAKEUP; } # a screensaver keeps window focus: no home.focusReady

row() { # build metric run value note -> CSV + stdout; blank value = lost sample
  printf '%s,%s,%s,%s,%s,%s,%s\n' "$ORDER" "$ROUND" "$1" "$2" "$3" "$4" "$5" >> "$CSV"
  echo "$ORDER s$ROUND $1 $2 #$3 ${4:-lost} $5"
}
keep_log() { printf '%s\n' "$2" > "$OUT/logs/$1.log"; }

install_build() { # $1 apk: install with the matching .dm (a plain install runs cold starts interpreted)
  local apk=$1 md dm out
  md="$(dirname "$apk")/output-metadata.json"; dm=""
  [ -f "$md" ] && dm=$(python3 -I - "$SDK" "$md" <<'PY'
import json, os, sys
sdk, md = int(sys.argv[1]), sys.argv[2]
for e in json.load(open(md)).get("baselineProfiles", []):
    if e["minApi"] <= sdk <= e["maxApi"]:
        print(os.path.join(os.path.dirname(md), e["baselineProfiles"][0]))
        break
PY
)
  if [ -n "$dm" ]; then out=$(A install-multiple -r -d "$apk" "$dm" 2>&1 | tail -1)
  else echo "no .dm bucket for API $SDK: installing the APK alone"; out=$(A install -r -d "$apk" 2>&1 | tail -1); fi
  case "$out" in *Success*) ;; *) die "install failed: $out (not uninstalling: that wipes app data)";; esac
  A shell setprop log.tag.JellybeamTV DEBUG
  dexopt_check
}

dexopt_line() { A shell dumpsys package dexopt | tr -d '\r' | grep -A3 "\[$PKG\]" | head -4; }
dexopt_check() { # status must be speed-profile before anything is timed (docs/06)
  case "$(dexopt_line)" in *status=speed-profile*) echo "dexopt: speed-profile"; return;; esac
  echo "dexopt: not speed-profile; forcing compile -m speed-profile -f"
  A shell cmd package compile -m speed-profile -f "$PKG" >/dev/null 2>&1
  case "$(dexopt_line)" in *status=speed-profile*) echo "dexopt: speed-profile (after forced compile)";; *)
    [ "$DEVICE" = tv ] && die "TV is not speed-profile after a forced compile"
    echo "WARNING: emulator still not speed-profile (API 28 mislabels this); continuing";; esac
}

home_ready() { local i; for i in $(seq 1 60); do case "$(log_dump)" in *phase=home.focusReady*) return 0;; esac; sleep 0.5; done; return 1; }
cold_launch() { wake; A shell am force-stop "$PKG"; A logcat -c; A shell am start -W -n "$PKG/.MainActivity" | tr -d '\r' | sed -n 's/.*TotalTime: *//p'; }

cold_run() { # build run: process.start -> home.dataReady (note: focusReady, am TotalTime, homeSnapshot count)
  local tot log="" res=timeout i ps dr fr v=""
  tot=$(cold_launch)
  for i in $(seq 1 60); do
    log=$(log_dump)
    if grep -q 'phase=home.focusReady' <<<"$log" && grep -q 'phase=home.dataReady' <<<"$log"; then res=ok; break; fi
    sleep 0.5
  done
  ps=$(mark process.start <<<"$log"); dr=$(mark home.dataReady <<<"$log"); fr=$(mark home.focusReady <<<"$log")
  if [ "$res" = ok ]; then
    if [ -n "$ps" ] && [ -n "$dr" ]; then v=$((dr - ps)); else res=missing-mark; fi
  fi
  keep_log "cold-$ROUND-$2" "$log"
  row "$1" cold "$2" "$v" "$res;focus=$((${fr:-0} - ${ps:-0}));total=${tot:-};snaps=$(grep -c 'section=ffi.homeSnapshot' <<<"$log")"
  sleep 2
}

await_frame() { # sets LOG RES V: firstFrame - activity.create (the 400 ms preload/dwell window is inside it)
  local i c f; RES=timeout; LOG=""; V=""
  for i in $(seq 1 80); do
    sleep 0.25; LOG=$(log_dump)
    case "$LOG" in *"phase=firstFrame atMs"*) RES=ok; break;; esac
  done
  c=$(mark activity.create <<<"$LOG"); f=$(mark firstFrame <<<"$LOG")
  if [ "$RES" = ok ]; then
    if [ -n "$c" ] && [ -n "$f" ]; then V=$((f - c)); else RES=missing-mark; fi
  fi
}

settle_home() { # Back out of the player, make sure Home is resumed; sets RES (+notHome). Mini player must be off.
  local k; sleep 5; A shell input keyevent KEYCODE_BACK; sleep 3
  for k in 1 2 3; do
    case "$(resumed)" in *PlaybackActivity*) A shell input keyevent KEYCODE_BACK; sleep 1.5;; *) break;; esac
  done
  case "$(resumed)" in *MainActivity*) ;; *) RES="$RES+notHome";; esac
  sleep 4
}

ttff_run() { # build run: PLAY intent -> first frame, then Back and require Home's onForeground mark
  wake; A logcat -c
  A shell am start -a tv.jellybeam.action.PLAY -p "$PKG" --es tv.jellybeam.extra.ITEM_ID "$ITEM" >/dev/null 2>&1
  await_frame; settle_home
  log_dump | awk '/phase=firstFrame atMs/{f=1} f&&/section=ffi.onForeground/{ok=1} END{exit !ok}' || RES="$RES+noForegroundMark"
  keep_log "ttff-$ROUND-$2" "$LOG"
  row "$1" ttff "$2" "$V" "$RES"
}

dwell_run() { # build cond run: from Home, hero button focused. dwell = focus held 2 s then Select; quick = press inside 400 ms
  A logcat -c
  A shell input keyevent KEYCODE_DPAD_RIGHT; sleep 1
  if [ "$2" = dwell ]; then A shell input keyevent KEYCODE_DPAD_LEFT; sleep 2; A shell input keyevent KEYCODE_DPAD_CENTER
  else A shell input keyevent KEYCODE_DPAD_LEFT KEYCODE_DPAD_CENTER; fi
  await_frame; settle_home
  keep_log "$2-$ROUND-$3" "$LOG"
  row "$1" "$2" "$3" "$V" "$RES"
}

measure_slot() { # build apk: install, then warm-up (run 0, dropped by the stats) + counted runs per metric
  local b=$1 i c
  install_build "$2"
  case ",$METRICS," in *,cold,*)
    cold_run "$b" 0; for i in $(seq 1 "$COLD_N"); do cold_run "$b" "$i"; done;; esac
  cold_launch >/dev/null; home_ready || die "Home never reached focusReady"
  sleep 8
  case ",$METRICS," in *,ttff,*)
    ttff_run "$b" 0; for i in $(seq 1 "$TTFF_N"); do ttff_run "$b" "$i"; done;; esac
  case ",$METRICS," in *,dwell,*)
    for c in dwell quick; do dwell_run "$b" "$c" 0; done
    for i in $(seq 1 "$DWELL_N"); do for c in dwell quick; do dwell_run "$b" "$c" "$i"; done; done;; esac
}
