#!/usr/bin/env bash
# Fresh sign-in on the emulator: clear app data, sign in with a password, wait for Home (docs/06 "Workflow scripts").
set -u
PKG=tv.jellybeam

case "${1:-showcase}" in
  -h|--help)
    cat <<'EOF'
Usage: tools/emu-signin.sh [showcase|dev12]

Runs pm clear tv.jellybeam on the emulator, launches the app and signs in with
D-pad keys and typed text, confirming each field with uiautomator. Exits 0 when
Home shows.
  showcase  JB_SERVER/JB_USER/JB_PASS, default http://10.0.2.2:8098 with the
            synthetic showcase account (tools/showcase-server/)
  dev12     JB_SERVER default http://10.0.2.2:8097; JB_USER and JB_PASS required
Serial: internal/device.env EMU, else the EMU env var. Emulator only: this
wipes the app's data.
EOF
    exit 0 ;;
  showcase) server="${JB_SERVER:-http://10.0.2.2:8098}" user="${JB_USER:-sam}" pass="${JB_PASS:-showcase}" ;;
  dev12) server="${JB_SERVER:-http://10.0.2.2:8097}" user="${JB_USER:?dev12 needs JB_USER}" pass="${JB_PASS:?dev12 needs JB_PASS}" ;;
  *) echo "unknown profile: $1 (showcase|dev12)" >&2; exit 2 ;;
esac

cd "$(dirname "$0")/.." || exit 2
[ -f internal/device.env ] && EMU="${EMU:-$(sed -n 's/^EMU=//p' internal/device.env)}"
S="${EMU:?no emulator serial: set EMU or internal/device.env}"
case "$S" in emulator-*) ;; *) echo "refusing: $S is not an emulator serial" >&2; exit 2 ;; esac
adb -s "$S" get-state >/dev/null 2>&1 || { echo "emulator $S unreachable" >&2; exit 1; }

key() { for k in "$@"; do adb -s "$S" shell input keyevent "KEYCODE_$k"; sleep 0.4; done; }
# Typed text needs spaces escaped for `input text`.
type_text() { adb -s "$S" shell input text "$(printf '%s' "$1" | sed 's/ /%s/g')"; }
fail() { echo "emu-signin: $1" >&2; adb -s "$S" exec-out screencap -p > "${TMPDIR:-/tmp}/emu-signin-fail.png"; echo "screenshot: ${TMPDIR:-/tmp}/emu-signin-fail.png" >&2; exit 1; }

# One line per text/edit/focused node: "<class> <text> <F?>". A blinking cursor keeps the UI
# from going idle, so dumps only happen once focus has left the text fields.
ui() {
  local i
  for i in 1 2 3 4 5; do
    adb -s "$S" shell rm -f /sdcard/ui.xml
    adb -s "$S" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    if adb -s "$S" exec-out cat /sdcard/ui.xml 2>/dev/null | grep -q '<hierarchy'; then
      adb -s "$S" exec-out cat /sdcard/ui.xml | python3 -I -c '
import re, sys
for n in re.findall(r"<node [^>]*>", sys.stdin.read()):
    g = lambda a: (re.search(a + r"=\"([^\"]*)\"", n) or [0, ""])[1]
    if g("text") or g("focused") == "true" or "EditText" in g("class"):
        print(g("class").split(".")[-1], g("text"), "F" if g("focused") == "true" else "")'
      return 0
    fi
    sleep 1
  done
  return 1
}
wait_for() {  # wait_for <seconds> <grep -E pattern>
  local end=$((SECONDS + $1))
  while [ $SECONDS -lt $end ]; do ui | grep -qE "$2" && return 0; sleep 1; done
  return 1
}

adb -s "$S" shell am force-stop "$PKG"
adb -s "$S" shell pm clear "$PKG" >/dev/null || fail "pm clear failed"
adb -s "$S" shell monkey -p "$PKG" -c android.intent.category.LEANBACK_LAUNCHER 1 >/dev/null 2>&1
wait_for 20 '^TextView Username' || fail "sign-in screen did not show"
echo "sign-in screen up"

# docs/15 §4: focus opens on the Quick Connect button; Up enters the server field in edit mode.
key DPAD_UP MOVE_END
adb -s "$S" shell input keyevent $(printf 'KEYCODE_DEL %.0s' $(seq 80))
type_text "$server"
key TAB
ui | grep -qxF "EditText $server " || fail "server field does not read $server"
echo "server set"

# Down from Quick Connect enters Username; TAB (ImeAction.Next) moves to Password, then Sign in.
key DPAD_DOWN
type_text "$user"
key TAB
type_text "$pass"
key TAB
ui | grep -qxF "EditText $user " || fail "username field does not read $user"
ui | grep -qE '^View +F$' || fail "focus did not reach the Sign in button"
key DPAD_CENTER
echo "signing in"

# Home: the sign-in fields are gone and a row header or the empty-library title shows.
end=$((SECONDS + 30))
while [ $SECONDS -lt $end ]; do
  screen=$(ui)
  if ! printf '%s\n' "$screen" | grep -q '^TextView Username' \
    && printf '%s\n' "$screen" | grep -qE '^TextView (Continue Watching|Next Up|Recently Added|No items in your libraries yet\.)'; then
    echo "HOME"
    exit 0
  fi
  sleep 1
done
fail "Home did not show within 30 s"
