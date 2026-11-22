#!/usr/bin/env bash
# Server ffmpeg launches per playback scenario (docs/18 §7): plays each named item on the emulator
# for a fixed time, presses Back, and counts the server's ffmpeg launches for that file.
set -u
PKG=tv.jellybeam

usage() {
  cat <<'EOF'
Usage: tools/ffmpeg-launches.sh [options] NAME...

Plays each library item whose name matches NAME (exact, case-insensitive) on the
emulator via the PLAY intent, waits --seconds, presses Back, waits --after, and
prints one row per item:
  name  launches_while_playing  launches_after_back  outcome
Launches are TranscodeManager ffmpeg command lines naming the item's file in the
server container's log (docker logs), so other clients' files don't count.
outcome is "black" (no first frame) or "playing", plus the longest texts on screen
(an error message shows here).

Options:
  --seconds N     play time per item (default 60)
  --after N       wait after Back before counting stragglers (default 10)
  --runs N        repeat each item N times (default 1)
  --seek-presses N   after the first frame, press D-pad Right N times 0.4 s apart
                  (a skip burst during the transcode); --seconds counts from the burst
  --server URL    server as seen from this Mac (default http://localhost:8098)
  --container C   server container name (default jellybeam-showcase)
  --user U --pass P   account used to look items up (default: showcase account)
Serial: internal/device.env EMU, else the EMU env var. Set the app's quality mode
first (settings.json playback_quality); this script never changes app data.
EOF
}

seconds=60 after=10 runs=1 presses=0 server=http://localhost:8098 container=jellybeam-showcase
user=sam pass=showcase names=()
while [ $# -gt 0 ]; do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    --seconds) seconds=$2; shift 2 ;;
    --after) after=$2; shift 2 ;;
    --runs) runs=$2; shift 2 ;;
    --seek-presses) presses=$2; shift 2 ;;
    --server) server=$2; shift 2 ;;
    --container) container=$2; shift 2 ;;
    --user) user=$2; shift 2 ;;
    --pass) pass=$2; shift 2 ;;
    -*) echo "unknown option $1" >&2; exit 2 ;;
    *) names+=("$1"); shift ;;
  esac
done
[ ${#names[@]} -gt 0 ] || { usage >&2; exit 2; }

cd "$(dirname "$0")/.." || exit 2
[ -f internal/device.env ] && EMU="${EMU:-$(sed -n 's/^EMU=//p' internal/device.env)}"
S="${EMU:?no emulator serial: set EMU or internal/device.env}"
case "$S" in emulator-*) ;; *) echo "refusing: $S is not an emulator serial" >&2; exit 2 ;; esac
A() { adb -s "$S" "$@"; }

auth='MediaBrowser Client="ffmpeg-launches", Device="script", DeviceId="ffmpeg-launches", Version="1"'
token=$(curl -sf "$server/Users/AuthenticateByName" -H "Authorization: $auth" -H 'Content-Type: application/json' \
  -d "{\"Username\":\"$user\",\"Pw\":\"$pass\"}" | python3 -Ic 'import json,sys; print(json.load(sys.stdin)["AccessToken"])') \
  || { echo "sign-in to $server failed" >&2; exit 1; }
hdr="Authorization: $auth, Token=\"$token\""

lookup() { # name -> "id<TAB>path" of the first exact match
  curl -sf -G "$server/Items" -H "$hdr" --data-urlencode "searchTerm=$1" \
    --data-urlencode 'Recursive=true' --data-urlencode 'Fields=Path' \
    --data-urlencode 'IncludeItemTypes=Movie,Episode,Video' |
    python3 -Ic 'import json,sys
want=sys.argv[1].lower()
for i in json.load(sys.stdin)["Items"]:
    if i["Name"].lower()==want: print(i["Id"]+"\t"+i.get("Path","")); break' "$1"
}

launches() { # since until path -> count of ffmpeg command lines naming path
  docker logs --since "$1" --until "$2" "$container" 2>&1 |
    grep 'TranscodeManager: .*ffmpeg.* -i ' | grep -cF "$3"
}

outcome() {
  local log ui
  log=$(A logcat -d -s JellybeamTV:V 2>/dev/null | tr -d '\r')
  ui=$(A exec-out uiautomator dump /dev/tty 2>/dev/null | grep -o 'text="[^"]\{12,\}"' | head -3 | tr '\n' ' ')
  if grep -q 'phase=firstFrame' <<<"$log"; then echo "playing ${ui}"; else echo "black ${ui}"; fi
}

printf '%-28s %8s %6s  %s\n' name playing after outcome
for name in "${names[@]}"; do
  row=$(lookup "$name"); [ -n "$row" ] || { echo "no item named '$name'" >&2; continue; }
  id=${row%%$'\t'*} path=${row#*$'\t'}
  for _ in $(seq 1 "$runs"); do
    A shell am force-stop "$PKG"; A shell setprop log.tag.JellybeamTV DEBUG; A logcat -c
    t0=$(date -u +%Y-%m-%dT%H:%M:%SZ)
    A shell am start -a tv.jellybeam.action.PLAY -p "$PKG" --es tv.jellybeam.extra.ITEM_ID "$id" >/dev/null 2>&1
    if [ "$presses" -gt 0 ]; then
      for _ in $(seq 1 120); do A logcat -d -s JellybeamTV:V | grep -q 'phase=firstFrame' && break; sleep 1; done
      for _ in $(seq 1 "$presses"); do A shell input keyevent KEYCODE_DPAD_RIGHT; sleep 0.4; done
    fi
    sleep "$seconds"
    out=$(outcome)
    t1=$(date -u +%Y-%m-%dT%H:%M:%SZ)
    A shell input keyevent KEYCODE_BACK; sleep 1; A shell input keyevent KEYCODE_BACK
    sleep "$after"
    t2=$(date -u +%Y-%m-%dT%H:%M:%SZ)
    printf '%-28s %8s %6s  %s\n' "$name" "$(launches "$t0" "$t1" "$path")" "$(launches "$t1" "$t2" "$path")" "$out"
  done
done
