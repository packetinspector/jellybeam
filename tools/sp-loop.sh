#!/usr/bin/env bash
# Watch Together three-member device loop on two emulators plus a REST bot (docs/28 §9; docs/06 "Workflow scripts").
set -u
PKG=tv.jellybeam

usage() {
  cat <<'EOF'
Usage: tools/sp-loop.sh [--server dev12|jswarrm] [--host SERIAL] [--guest SERIAL]
                        [--to N] [--skip N,M] [--no-install]

Runs the Watch Together loop by itself: the host emulator creates a group from a
title's Detail page, the guest emulator joins from the room list, and a bot (third
member, SP_EVE, tools/sp-loop/bot.mjs) joins by group id over the SyncPlay REST API
and follows every command like a real member. Prints PASS/FAIL per step and a
summary; exits 0 only if every step that ran passed.

Steps (run in this order; 14 runs before 13 because 13 ends the queue):
   1 snapshot load + heap check + APK check (installs the release APK if it differs)
   2 bot signs in, opens the socket, syncs its clock, finds the clip
   3 host: Search -> Detail -> ⋯ -> Watch together; the lobby shows the listed room's name
   4 guest: drawer -> Watch together -> the room's row; both lobbies list two members
   5 bot joins by group id; both lobbies list three
   6 host Start -> both players play
   7 paired frame-counter capture -> drift
   8 pause from the guest (D-pad) -> both frozen, frame difference
   9 unpause from the bot (REST) -> both play, drift
  10 seek +10 s from the host (D-pad, OSD hidden) -> both land, drift
  11 seek from the bot (REST) -> both land, drift
  12 22 presses of +10 s from the host, 0.3 s apart -> both land ~+220 s and play
  14 guest network off 10 s (svc wifi/data) -> group recovers, drift
  13 bot seeks near the end -> both return to their lobbies, still in the group
  15 guest Back, host Back, bot Leave -> no group left on the server

  --server   dev12 (default; JF 12 at :8097) or jswarrm (Jellyswarrm at :8100). Also the
             emulator snapshot name loaded in step 1 (SP_SNAPSHOT overrides).
  --host     host serial (default emulator-5558); --guest guest serial (default emulator-5556)
  --to N     stop after step N, leaving the group up for inspection
  --skip L   skip steps in the comma list (dependent steps still need their setup)
  --no-install  never install in step 1, only report the APK state

Player keys (PlaybackScreen.kt, PlaybackOsdController.kt): with the OSD hidden, Enter only
reveals it with focus on Play/Pause and Left/Right seek -/+10 s; with it shown, Enter
toggles play/pause and Left/Right move focus. The OSD hides 5 s after the last key while
playing and stays while paused. Back from the player leaves the group.

Env: SP_ACCOUNTS (default internal/syncplay/test-accounts.env: SP_ALICE, SP_BOB, SP_EVE,
SP_PASS), SP_CLIP (search term of the frame-counter clip, default 97, the 40-minute one),
SP_DRIFT_MS (pass threshold for drift and frozen-frame difference, default 84 = 2 frames
at 24 fps), SP_BASE / SP_EMU_BASE (server as seen from this host / from the emulator).
Emulator serials only. Run output (log, screenshots, UI dumps, bot log) goes to
internal/syncplay/runs/<timestamp>/; account names and the room name are redacted on
stdout and in the log.
EOF
}

profile=dev12 HOST=emulator-5558 GUEST=emulator-5556 TO=99 SKIP="" INSTALL=1
while [ $# -gt 0 ]; do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    --server) profile="${2:?}"; shift 2 ;;
    --host) HOST="${2:?}"; shift 2 ;;
    --guest) GUEST="${2:?}"; shift 2 ;;
    --to) TO="${2:?}"; shift 2 ;;
    --skip) SKIP=",${2:?},"; shift 2 ;;
    --no-install) INSTALL=0; shift ;;
    *) echo "unknown argument: $1 (see --help)" >&2; exit 2 ;;
  esac
done
case "$profile" in
  dev12) BASE="${SP_BASE:-http://localhost:8097}" ;;
  jswarrm) BASE="${SP_BASE:-http://localhost:8100}" ;;
  *) echo "unknown server: $profile (dev12|jswarrm)" >&2; exit 2 ;;
esac
SNAPSHOT="${SP_SNAPSHOT:-$profile}"
for s in "$HOST" "$GUEST"; do
  case "$s" in emulator-*) ;; *) echo "refusing: $s is not an emulator serial" >&2; exit 2 ;; esac
done
[ "$HOST" != "$GUEST" ] || { echo "host and guest must differ" >&2; exit 2; }

cd "$(dirname "$0")/.." || exit 2
ROOT=$PWD
TOOLS=$ROOT/tools/sp-loop
ACCOUNTS="${SP_ACCOUNTS:-internal/syncplay/test-accounts.env}"
[ -f "$ACCOUNTS" ] || { echo "missing $ACCOUNTS (SP_ALICE, SP_BOB, SP_EVE, SP_PASS)" >&2; exit 2; }
NAMES=$(sed -nE 's/^SP_(ALICE|BOB|EVE)=//p' "$ACCOUNTS")
CLIP="${SP_CLIP:-97}"
THRESH="${SP_DRIFT_MS:-84}"
for t in adb node python3 curl; do command -v "$t" >/dev/null || { echo "needs $t" >&2; exit 2; }; done

RUN="$ROOT/internal/syncplay/runs/$(date +%Y%m%d-%H%M%S)-$profile"
mkdir -p "$RUN"
LOG="$RUN/log.txt"
ROOM=""
BOT_PID=""
BOT_PORT=""
RUNTIME_TICKS=0

# Account names and the room name never reach stdout or the log verbatim.
red() {
  local line
  while IFS= read -r line; do
    local n i=0
    [ -n "$ROOM" ] && line=${line//"$ROOM"/<room>}
    for n in $NAMES; do i=$((i + 1)); line=${line//"$n"/<user$i>}; done
    printf '%s\n' "$line"
  done
}
say() { printf '%s %s\n' "$(date +%H:%M:%S)" "$*" | red | tee -a "$LOG"; }

declare -a RESULTS=()
FAILED=0
result() {  # result <step> <PASS|FAIL> <evidence>
  local line
  line=$(printf 'STEP %-2s %s  %s' "$1" "$2" "$3")
  RESULTS+=("$(printf '%s\n' "$line" | red)")
  say "$line"
  [ "$2" = PASS ] || FAILED=1
  shot "$1"
}
shot() {  # full-res screenshot of both emulators for step $1
  adb -s "$HOST" exec-out screencap -p > "$RUN/$(printf '%02d' "$1")-host.png" 2>/dev/null
  adb -s "$GUEST" exec-out screencap -p > "$RUN/$(printf '%02d' "$1")-guest.png" 2>/dev/null
}

key() {  # key <serial> <KEYCODE suffix>... ; one keyevent per command (API 28), 0.7 s apart
  local s=$1; shift
  for k in "$@"; do adb -s "$s" shell input keyevent "KEYCODE_$k"; sleep 0.7; done
}
# Dumps the UI of <serial> to $RUN/ui-<serial>.xml; a busy UI may need a few tries.
ui() {
  local s=$1 i
  for i in 1 2 3 4; do
    adb -s "$s" shell rm -f /sdcard/ui.xml
    adb -s "$s" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    if adb -s "$s" exec-out cat /sdcard/ui.xml > "$RUN/ui-$s.xml" 2>/dev/null && grep -q '<hierarchy' "$RUN/ui-$s.xml"; then
      return 0
    fi
    sleep 0.5
  done
  : > "$RUN/ui-$s.xml"
  return 1
}
uiq() { python3 -I "$TOOLS/ui.py" "$1" "$RUN/ui-$2.xml"; }   # uiq <query> <serial>, on the last dump
has() { ui "$1"; uiq texts "$1" | grep -qE "$2"; }          # has <serial> <regex>
wait_for() {  # wait_for <serial> <seconds> <regex>
  local end=$((SECONDS + $2))
  while [ $SECONDS -lt $end ]; do has "$1" "$3" && return 0; sleep 0.5; done
  return 1
}
focused() { ui "$1"; uiq focused "$1"; }
focus_to() {  # focus_to <serial> <KEY> <regex> <max presses>: press KEY until the focused label matches
  local s=$1 k=$2 re=$3 n=$4 i
  for ((i = 0; i <= n; i++)); do
    focused "$s" | grep -qE "$re" && return 0
    [ $i -lt "$n" ] && key "$s" "$k"
  done
  return 1
}
in_player() { adb -s "$1" shell dumpsys window 2>/dev/null | grep -m1 mCurrentFocus | grep -q PlaybackActivity; }
osd_shown() { has "$1" '^WATCH TOGETHER$'; }   # in the player, the OSD's group chip
wait_osd_hidden() {  # the OSD hides 5 s after the last key while playing
  local end=$((SECONDS + ${2:-12}))
  while [ $SECONDS -lt $end ]; do osd_shown "$1" || return 0; sleep 1; done
  return 1
}

bot() { curl -s --max-time 10 "http://127.0.0.1:$BOT_PORT/$1"; }
botj() {  # botj <python expression over d = /status>
  bot status | python3 -I -c "import json,sys; d=json.load(sys.stdin); print($1)" 2>/dev/null
}
wait_bot() {  # wait_bot <seconds> <python bool expression over d>
  local end=$((SECONDS + $1))
  while [ $SECONDS -lt $end ]; do [ "$(botj "$2")" = True ] && return 0; sleep 0.5; done
  return 1
}

# Paired capture: "drift_ms=.. skew_ms=.. n=.. a_ms=.. b_ms=.." (a = host, b = guest), or empty.
PAIR=""
pair() {  # pair <tag> [n]
  python3 -I "$TOOLS/frames.py" pair "$HOST" "$GUEST" "$RUN" "$1" "${2:-10}" > "$RUN/frames-$1.txt" 2>&1
  PAIR=$(sed -n 's/^RESULT //p' "$RUN/frames-$1.txt")
}
STILL=""
still() { python3 -I "$TOOLS/frames.py" still "$HOST" "$GUEST" "$RUN" "$1" > "$RUN/frames-$1.txt" 2>&1; STILL=$(sed -n 's/^RESULT //p' "$RUN/frames-$1.txt"); }
field() { printf '%s\n' "$1" | tr ' ' '\n' | sed -n "s/^$2=//p"; }
abs() { local v=${1%.*}; echo "${v#-}"; }
# docs/28 §9's drift gate, printed beside the step threshold.
gate() { local d s; d=$(abs "$(field "$PAIR" drift_ms)"); s=$(field "$PAIR" skew_ms); [ "$d" -le $((120 + 3 * s)) ] && echo "docs28-gate(120+3x${s})=ok" || echo "docs28-gate(120+3x${s})=over"; }
drift_ok() { local d; d=$(field "$PAIR" drift_ms); [ -n "$d" ] && [ "$(abs "$d")" -le "$THRESH" ]; }
# Host position vs the bot's model of the group clock, in ms.
vs_bot() { local a p; a=$(field "$PAIR" a_ms); p=$(botj 'd["posMs"]'); [ -n "$a" ] && [ -n "$p" ] && echo $((a - p)); }

cleanup() {
  adb -s "$GUEST" shell svc wifi enable >/dev/null 2>&1
  adb -s "$GUEST" shell svc data enable >/dev/null 2>&1
  if [ -n "$BOT_PORT" ]; then bot quit >/dev/null 2>&1; fi
  [ -n "$BOT_PID" ] && kill "$BOT_PID" 2>/dev/null
}
trap cleanup EXIT

summary() {
  say "---- summary ($profile, run $RUN)"
  local r
  for r in "${RESULTS[@]}"; do printf '%s\n' "$r" | tee -a "$LOG"; done
  local pass fail
  pass=$(printf '%s\n' "${RESULTS[@]}" | grep -c ' PASS ')
  fail=$(printf '%s\n' "${RESULTS[@]}" | grep -c ' FAIL ')
  say "PASS $pass  FAIL $fail"
  exit "$FAILED"
}
# A failed setup step leaves nothing to test; later steps still record what they saw.
abort_if_failed() { [ "$FAILED" = 0 ] || { say "setup step failed; stopping"; summary; }; }
want() { [ "$1" -le "$TO" ] && [[ "$SKIP" != *",$1,"* ]]; }
stop_after() { [ "$1" -ge "$TO" ] && { trap - EXIT; [ -n "$BOT_PID" ] && say "bot left running (pid $BOT_PID, port $BOT_PORT)"; summary; }; }

home_up() {  # launches the app fresh and waits for Home
  local s=$1
  adb -s "$s" shell am force-stop "$PKG"
  adb -s "$s" shell monkey -p "$PKG" -c android.intent.category.LEANBACK_LAUNCHER 1 >/dev/null 2>&1
  wait_for "$s" 30 '^(Continue Watching|Next Up|Recently Added|Latest in .*)$'
}
open_drawer_row() {  # open_drawer_row <serial> <regex>: Home's first column -> drawer -> row
  local s=$1
  key "$s" DPAD_LEFT
  wait_for "$s" 5 '^Search$' || return 1
  focus_to "$s" DPAD_DOWN "$2" 8
}

say "sp-loop $profile: host $HOST, guest $GUEST, server $BASE, clip term set, threshold ${THRESH} ms, run $RUN"

# ---- 1: snapshot load, heap, APK
if want 1; then
  ev="" ok=1
  apk=app/build/outputs/apk/release/app-release.apk
  local_sha=$( [ -f "$apk" ] && shasum -a 256 "$apk" | cut -d' ' -f1 )
  for s in "$HOST" "$GUEST"; do
    adb -s "$s" get-state >/dev/null 2>&1 || { ev+="$s unreachable; "; ok=0; continue; }
    out=$(adb -s "$s" emu avd snapshot load "$SNAPSHOT" 2>&1 | tr -d '\r' | head -1)
    [ "$out" = OK ] || { ev+="$s snapshot $SNAPSHOT: $out; "; ok=0; continue; }
    sleep 3
    adb -s "$s" wait-for-device
    heap=$(adb -s "$s" shell getprop dalvik.vm.heapgrowthlimit | tr -d '\r')
    [ "$heap" = 256m ] || { ev+="$s heap $heap; "; ok=0; }
    dev_sha=$(adb -s "$s" shell 'sha256sum $(pm path '"$PKG"' | sed -n "s/^package://p" | head -1)' 2>/dev/null | cut -d' ' -f1)
    if [ -n "$local_sha" ] && [ "$dev_sha" != "$local_sha" ]; then
      if [ "$INSTALL" = 1 ]; then
        say "$s: installed APK differs from the release build; installing"
        ANDROID_SERIAL=$s ./build.sh install >> "$LOG" 2>&1 || { ev+="$s install failed; "; ok=0; }
        apk_state="installed"
      else
        apk_state="differs"; ok=0
      fi
    else
      apk_state=$([ -n "$local_sha" ] && echo current || echo "no local build")
    fi
    home_up "$s" || { ev+="$s Home did not show; "; ok=0; }
    ev+="$s snapshot=$SNAPSHOT heap=$heap apk=$apk_state; "
  done
  result 1 "$([ $ok = 1 ] && echo PASS || echo FAIL)" "$ev"
  abort_if_failed
fi
stop_after 1

# ---- 2: bot
if want 2; then
  node "$TOOLS/bot.mjs" "$BASE" "$ACCOUNTS" "$RUN" > "$RUN/bot.out" 2>&1 &
  BOT_PID=$!
  for _ in $(seq 40); do [ -s "$RUN/bot.port" ] && break; sleep 0.25; done
  BOT_PORT=$(cat "$RUN/bot.port" 2>/dev/null)
  lookup=$( [ -n "$BOT_PORT" ] && curl -s --max-time 10 "http://127.0.0.1:$BOT_PORT/lookup?term=$CLIP")
  RUNTIME_TICKS=$(printf '%s' "$lookup" | python3 -I -c 'import json,sys; print(json.load(sys.stdin).get("runtimeTicks") or 0)' 2>/dev/null || echo 0)
  if [ -n "$BOT_PORT" ] && [ "$(botj 'd["authed"] and d["ws"]')" = True ] && [ "${RUNTIME_TICKS:-0}" -gt 0 ]; then
    result 2 PASS "signed in, socket open, clock offset $(botj 'd["offsetMs"]') ms, clip runtime $((RUNTIME_TICKS / 10000000)) s"
  else
    result 2 FAIL "bot port=${BOT_PORT:-none} status=$(bot status | head -c 200) clip lookup=$lookup"
  fi
  abort_if_failed
fi
stop_after 2

# ---- 3: host creates
if want 3; then
  ok=0 ev=""
  if open_drawer_row "$HOST" '^Search$'; then
    key "$HOST" DPAD_CENTER
    sleep 1.5
    adb -s "$HOST" shell input text "$CLIP"
    sleep 1
    key "$HOST" TAB
    sleep 1.5
    # The first result takes focus; its label is the title, which starts with the term.
    if focus_to "$HOST" DPAD_RIGHT "^$CLIP" 0; then
      key "$HOST" DPAD_CENTER
      if wait_for "$HOST" 10 '^···$' && focus_to "$HOST" DPAD_RIGHT '^···$' 2; then
        key "$HOST" DPAD_CENTER
        if wait_for "$HOST" 5 '^ACTIONS$' && focus_to "$HOST" DPAD_DOWN '^Watch together$' 10; then
          key "$HOST" DPAD_CENTER
          if wait_for "$HOST" 15 '^IN THE GROUP · 1$'; then
            # Our room is the listed name the host's lobby shows (the server names it, docs/28 §1).
            for _ in 1 2 3 4 5; do
              ui "$HOST"
              ROOM=$(bot list | python3 -I -c "import json,sys; ts=set(open(sys.argv[1]).read().split('\n')); print(next((g['GroupName'] for g in json.load(sys.stdin) if g.get('GroupName') in ts), ''))" <(uiq texts "$HOST") 2>/dev/null)
              [ -n "$ROOM" ] && break
              sleep 1
            done
            [ -n "$ROOM" ] && ok=1
            ev="lobby shows $( [ -n "$ROOM" ] && echo 'the listed room name' || echo 'no listed room name') members=1"
          else ev="no lobby after Watch together"; fi
        else ev="actions panel or Watch together row not reached (focus: $(focused "$HOST"))"; fi
      else ev="Detail not reached or ⋯ not focusable"; fi
    else ev="no search result starting with the term (focus: $(focused "$HOST"))"; fi
  else ev="drawer Search row not reached"; fi
  result 3 "$([ $ok = 1 ] && echo PASS || echo FAIL)" "$ev"
  abort_if_failed
fi
stop_after 3

# ---- 4: guest joins from the room list
if want 4; then
  ok=0 ev=""
  if [ -z "$ROOM" ]; then ev="no room name from step 3"
  elif open_drawer_row "$GUEST" '^(Join watch together|Watch together)$'; then
    key "$GUEST" DPAD_CENTER
    room_re="^$(printf '%s' "$ROOM" | sed 's/[]\/$*.^|[(){}?+]/\\&/g')$"
    if wait_for "$GUEST" 10 "$room_re" && focus_to "$GUEST" DPAD_DOWN "$room_re" 8; then
      key "$GUEST" DPAD_CENTER; ev="room list -> room row; "
    else
      ev="room row not reached (focus: $(focused "$GUEST")); "
    fi
    if wait_for "$GUEST" 15 '^IN THE GROUP · 2$' && wait_for "$HOST" 10 '^IN THE GROUP · 2$'; then
      ok=1; ev+="guest lobby 2 members, host lobby 2 members"
    else
      ev+="guest members=$(ui "$GUEST"; uiq members "$GUEST") host members=$(ui "$HOST"; uiq members "$HOST")"
    fi
  else ev="drawer join row not reached"; fi
  result 4 "$([ $ok = 1 ] && echo PASS || echo FAIL)" "$ev"
  abort_if_failed
fi
stop_after 4

# ---- 5: bot joins by group id
if want 5; then
  [ -n "$ROOM" ] || { result 5 FAIL "no room name from step 3"; abort_if_failed; }
  j=$(bot "join?name=$(python3 -I -c 'import sys,urllib.parse; print(urllib.parse.quote(sys.argv[1]))' "$ROOM")")
  if wait_bot 10 'len(d["participants"]) == 3' && wait_for "$HOST" 10 '^IN THE GROUP · 3$' && wait_for "$GUEST" 10 '^IN THE GROUP · 3$'; then
    result 5 PASS "bot join $(printf '%s' "$j" | tr -d '{}"'); bot sees 3, host lobby 3, guest lobby 3"
  else
    result 5 FAIL "bot join $j; bot sees $(botj 'len(d["participants"])'); host $(ui "$HOST"; uiq members "$HOST"); guest $(ui "$GUEST"; uiq members "$GUEST")"
  fi
  abort_if_failed
fi
stop_after 5

# ---- 6: host Start
if want 6; then
  ok=0 ev=""
  f=$(focused "$HOST")
  if [[ "$f" == Resume\ from* ]]; then focus_to "$HOST" DPAD_RIGHT '^From the beginning$' 2; f=$(focused "$HOST"); fi
  if [[ "$f" =~ ^(Start|From\ the\ beginning)$ ]]; then
    key "$HOST" DPAD_CENTER
    end=$((SECONDS + 25))
    while [ $SECONDS -lt $end ]; do in_player "$HOST" && in_player "$GUEST" && break; sleep 1; done
    if in_player "$HOST" && in_player "$GUEST" && wait_bot 20 'd["groupState"] == "Playing"'; then
      sleep 3
      still s6
      if [ "$(field "$STILL" a_moved)" = True ] && [ "$(field "$STILL" b_moved)" = True ]; then
        ok=1; ev="pressed '$f'; both players up, group Playing, both clocks moving ($STILL)"
      else ev="players up but not both moving: ${STILL:-unreadable}"; fi
    else ev="host player=$(in_player "$HOST" && echo up || echo no) guest player=$(in_player "$GUEST" && echo up || echo no) group=$(botj 'd["groupState"]')"; fi
  else ev="lobby focus is '$f', not Start"; fi
  result 6 "$([ $ok = 1 ] && echo PASS || echo FAIL)" "$ev"
  abort_if_failed
fi
stop_after 6

# ---- 7: drift while playing
if want 7; then
  sleep 4
  pair s7 10
  if drift_ok; then result 7 PASS "$PAIR $(gate)"; else result 7 FAIL "${PAIR:-unreadable} (threshold ${THRESH} ms) $(gate)"; fi
fi
stop_after 7

# ---- 8: pause from the guest
if want 8; then
  ev=""
  wait_osd_hidden "$GUEST" 12 || ev+="guest OSD did not hide; "
  # Hidden OSD: the first Enter reveals it on Play/Pause, the second toggles.
  key "$GUEST" DPAD_CENTER DPAD_CENTER
  if wait_bot 6 'd["groupState"] == "Paused"'; then
    sleep 1.5
    still s8
    d=$(field "$STILL" diff_ms)
    if [ "$(field "$STILL" a_moved)" = False ] && [ "$(field "$STILL" b_moved)" = False ] && [ -n "$d" ] && [ "$(abs "$d")" -le "$THRESH" ]; then
      result 8 PASS "${ev}group Paused; both frozen, frame difference ${d} ms ($STILL)"
    else
      result 8 FAIL "${ev}group Paused; ${STILL:-unreadable} (frozen difference threshold ${THRESH} ms)"
    fi
  else
    result 8 FAIL "${ev}group state $(botj 'd["groupState"]') after the guest's pause"
  fi
fi
stop_after 8

# ---- 9: unpause from the bot
if want 9; then
  j=$(bot unpause)
  if wait_bot 6 'd["groupState"] == "Playing"'; then
    sleep 4
    still s9
    pair s9p 10
    if [ "$(field "$STILL" a_moved)" = True ] && [ "$(field "$STILL" b_moved)" = True ] && drift_ok; then
      result 9 PASS "bot Unpause; both moving; $PAIR $(gate)"
    else
      result 9 FAIL "bot Unpause; moving: ${STILL:-unreadable}; ${PAIR:-unreadable} (threshold ${THRESH} ms) $(gate)"
    fi
  else
    result 9 FAIL "bot unpause $j; group state $(botj 'd["groupState"]')"
  fi
fi
stop_after 9

# Waits for a group seek to settle: the bot saw at least <n> new Seek commands, the group plays again
# and no command arrived for 3 s.
settle_seek() {  # settle_seek <seeks before> <min new> <seconds>
  local end=$((SECONDS + $3))
  wait_bot "$3" "d['seeks'] >= $1 + $2" || return 1
  while [ $SECONDS -lt $end ]; do
    [ "$(botj 'd["groupState"] == "Playing" and d["lastCommand"] == "Unpause"')" = True ] \
      && [ "$(botj 'int((__import__("time").time()*1000 - d["lastCommandAtMs"]) > 3000)')" = 1 ] && return 0
    sleep 0.5
  done
  return 1
}

# ---- 10: seek +10 s from the host
if want 10; then
  ev=""
  wait_osd_hidden "$HOST" 12 || ev+="host OSD did not hide; "
  s0=$(botj 'd["seeks"]'); p0=$(botj 'd["posMs"]')
  key "$HOST" DPAD_RIGHT
  if settle_seek "$s0" 1 20; then
    target=$(( $(botj 'd["lastSeekTicks"]') / 10000 ))
    jump=$((target - p0))
    sleep 1
    pair s10 10
    off=$(vs_bot)
    if [ "$jump" -ge 8000 ] && [ "$jump" -le 13000 ] && drift_ok && [ -n "$off" ] && [ "$(abs "$off")" -le 1500 ]; then
      result 10 PASS "${ev}group seek +${jump} ms; landed and playing; host vs group clock ${off} ms; $PAIR $(gate)"
    else
      result 10 FAIL "${ev}group seek +${jump} ms; host vs group clock ${off:-?} ms; ${PAIR:-unreadable} (threshold ${THRESH} ms) $(gate)"
    fi
  else
    result 10 FAIL "${ev}no settled group seek: seeks $s0 -> $(botj 'd["seeks"]'), state $(botj 'd["groupState"]'), last $(botj 'd["lastCommand"]')"
  fi
fi
stop_after 10

# ---- 11: seek from the bot
if want 11; then
  s0=$(botj 'd["seeks"]'); p0=$(botj 'd["posMs"]')
  target=$((p0 + 30000))
  j=$(bot "seek?ticks=$((target * 10000))")
  if settle_seek "$s0" 1 20; then
    sleep 1
    pair s11 10
    off=$(vs_bot)
    if drift_ok && [ -n "$off" ] && [ "$(abs "$off")" -le 1500 ]; then
      result 11 PASS "bot seek to +30 s; landed and playing; host vs group clock ${off} ms; $PAIR $(gate)"
    else
      result 11 FAIL "bot seek to +30 s; host vs group clock ${off:-?} ms; ${PAIR:-unreadable} (threshold ${THRESH} ms) $(gate)"
    fi
  else
    result 11 FAIL "bot seek $j; seeks $s0 -> $(botj 'd["seeks"]'), state $(botj 'd["groupState"]')"
  fi
fi
stop_after 11

# ---- 12: burst of 22 x +10 s from the host
if want 12; then
  ev=""
  wait_osd_hidden "$HOST" 12 || ev+="host OSD did not hide; "
  s0=$(botj 'd["seeks"]'); p0=$(botj 'd["posMs"]')
  # One device-side loop keeps the 0.3 s spacing; one keyevent per `input` call (API 28).
  adb -s "$HOST" shell 'for i in $(seq 22); do input keyevent KEYCODE_DPAD_RIGHT; sleep 0.3; done'
  if settle_seek "$s0" 1 60; then
    sleep 2
    p1=$(botj 'd["posMs"]')
    jump=$((p1 - p0))
    still s12
    pair s12p 10
    off=$(vs_bot)
    if [ "$jump" -ge 205000 ] && [ "$jump" -le 250000 ] && [ "$(field "$STILL" a_moved)" = True ] && [ "$(field "$STILL" b_moved)" = True ] \
      && drift_ok && [ -n "$off" ] && [ "$(abs "$off")" -le 1500 ]; then
      result 12 PASS "${ev}group moved +$((jump / 1000)) s over $(( $(botj 'd["seeks"]') - s0 )) seeks; both playing; host vs group clock ${off} ms; $PAIR $(gate)"
    else
      result 12 FAIL "${ev}group moved +$((jump / 1000)) s (expected ~220 incl. settle); moving: ${STILL:-?}; host vs group clock ${off:-?} ms; ${PAIR:-unreadable} $(gate)"
    fi
  else
    result 12 FAIL "${ev}burst did not settle in 60 s: seeks $s0 -> $(botj 'd["seeks"]'), state $(botj 'd["groupState"]'), last $(botj 'd["lastCommand"]')"
  fi
fi
stop_after 12

# ---- 14: network drop on the guest (runs before 13, which ends the queue)
if want 14; then
  l0=$(botj 'd["userLeft"]'); j0=$(botj 'd["userJoined"]')
  adb -s "$GUEST" shell svc wifi disable
  adb -s "$GUEST" shell svc data disable
  sleep 10
  dropped=$(botj "d['userLeft'] > $l0")
  adb -s "$GUEST" shell svc wifi enable
  adb -s "$GUEST" shell svc data enable
  # Jellyfin drops a member when its socket closes, and the guest rejoins; Jellyswarrm keeps it 30 s.
  recovered=0
  end=$((SECONDS + 60))
  while [ $SECONDS -lt $end ]; do
    if [ "$(botj 'len(d["participants"]) == 3 and d["groupState"] == "Playing"')" = True ] && in_player "$GUEST"; then recovered=1; break; fi
    sleep 1
  done
  ev="member dropped during the cut: $dropped; UserLeft +$(( $(botj 'd["userLeft"]') - l0 )), UserJoined +$(( $(botj 'd["userJoined"]') - j0 )); "
  if [ $recovered = 1 ]; then
    sleep 5
    still s14
    pair s14p 10
    if [ "$(field "$STILL" a_moved)" = True ] && [ "$(field "$STILL" b_moved)" = True ] && drift_ok; then
      result 14 PASS "${ev}group back to 3 and Playing, both moving; $PAIR $(gate)"
    else
      result 14 FAIL "${ev}group back to 3; moving: ${STILL:-?}; ${PAIR:-unreadable} (threshold ${THRESH} ms) $(gate)"
    fi
  else
    result 14 FAIL "${ev}not recovered in 60 s: bot sees $(botj 'len(d["participants"])') members, state $(botj 'd["groupState"]'), guest player $(in_player "$GUEST" && echo up || echo gone)"
  fi
fi
stop_after 14

# ---- 13: end of queue
if want 13; then
  s0=$(botj 'd["seeks"]')
  target=$((RUNTIME_TICKS - 120000000))
  j=$(bot "seek?ticks=$target")
  end=$((SECONDS + 60)) back=0
  while [ $SECONDS -lt $end ]; do
    if ! in_player "$HOST" && ! in_player "$GUEST"; then back=1; break; fi
    sleep 1
  done
  if [ $back = 1 ] && wait_for "$HOST" 10 '^IN THE GROUP · 3$' && wait_for "$GUEST" 10 '^IN THE GROUP · 3$' \
    && [ "$(botj 'd["groupId"] is not None and len(d["participants"]) == 3')" = True ]; then
    title=$(uiq texts "$GUEST" | grep -m1 -E '^Finished' || echo "no Finished title")
    result 13 PASS "seek to end-12 s; both players closed to their lobbies (3 members each); bot still in the group; guest: $title"
  else
    result 13 FAIL "seek $j; host player $(in_player "$HOST" && echo up || echo closed), guest player $(in_player "$GUEST" && echo up || echo closed); host members $(ui "$HOST"; uiq members "$HOST"), guest members $(ui "$GUEST"; uiq members "$GUEST"); bot members $(botj 'len(d["participants"])')"
  fi
fi
stop_after 13

# ---- 15: leave
if want 15; then
  ev="" ok=1
  [ -n "$ROOM" ] || { ev+="no room name from step 3; "; ok=0; }
  # Back in the lobby leaves (from the player it would leave too).
  key "$GUEST" BACK
  if wait_bot 10 'len(d["participants"]) == 2' && wait_for "$HOST" 10 '^IN THE GROUP · 2$'; then ev+="guest left: host and bot see 2; "; else ev+="after guest Back: bot sees $(botj 'len(d["participants"])'), host $(ui "$HOST"; uiq members "$HOST"); "; ok=0; fi
  in_player "$HOST" && key "$HOST" BACK
  key "$HOST" BACK
  if wait_bot 10 'len(d["participants"]) == 1'; then ev+="host left: bot alone; "; else ev+="after host Back: bot sees $(botj 'len(d["participants"])'); "; ok=0; fi
  bot leave >/dev/null
  wait_bot 5 'd["groupId"] is None' || { ev+="bot still in a group; "; ok=0; }
  # Jellyswarrm may keep an emptied group through its 30 s grace.
  gone=0 end=$((SECONDS + 40))
  while [ $SECONDS -lt $end ]; do
    if bot list | python3 -I -c "import json,sys; sys.exit(0 if all((g.get('GroupName') or '').strip().lower() != sys.argv[1].strip().lower() for g in json.load(sys.stdin)) else 1)" "$ROOM" 2>/dev/null; then gone=1; break; fi
    sleep 2
  done
  [ $gone = 1 ] && ev+="no group of ours listed; " || { ev+="our group still listed after 40 s; "; ok=0; }
  has "$HOST" '^IN THE GROUP' && { ev+="host still shows a lobby; "; ok=0; }
  has "$GUEST" '^IN THE GROUP' && { ev+="guest still shows a lobby; "; ok=0; }
  result 15 "$([ $ok = 1 ] && echo PASS || echo FAIL)" "$ev"
fi

summary
