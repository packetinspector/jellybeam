#!/usr/bin/env bash
# Read-only session state: git position, worktrees, and what each device has installed (docs/06 "Workflow scripts").
set -u
PKG=tv.jellybeam

case "${1:-}" in
  -h|--help)
    cat <<'EOF'
Usage: tools/status.sh [--no-devices]

Prints branch, HEAD, dirty files, ahead/behind against main, backup/main and
public/main (when those exist), worktrees, and for the TV and the emulator:
reachable, busy (device-busy.sh), installed versionName/versionCode, dexopt
status, and whether the app is in front. Sends no input to any device. Serials
come from internal/device.env (TV, EMU), else the TV / EMU env vars.
EOF
    exit 0 ;;
esac

devices=1; [ "${1:-}" = --no-devices ] && devices=0
cd "$(dirname "$0")/.." || exit 2

echo "== git"
echo "branch: $(git branch --show-current 2>/dev/null || echo '(detached)')  HEAD: $(git log -1 --format='%h %s')"
dirty=$(git status --short)
if [ -n "$dirty" ]; then echo "dirty:"; printf '%s\n' "$dirty" | sed 's/^/  /'; else echo "dirty: none"; fi
for ref in main backup/main public/main; do
  if git rev-parse -q --verify "$ref" >/dev/null; then
    read -r ahead behind < <(git rev-list --left-right --count "HEAD...$ref")
    echo "vs $ref: ahead $ahead, behind $behind"
  else
    echo "vs $ref: (no such ref)"
  fi
done
echo "== worktrees"
git worktree list | sed 's/^/  /'

[ $devices -eq 0 ] && exit 0

# Serials: internal/device.env when present, else the environment.
if [ -f internal/device.env ]; then
  TV="${TV:-$(sed -n 's/^TV=//p' internal/device.env)}"
  EMU="${EMU:-$(sed -n 's/^EMU=//p' internal/device.env)}"
fi
busy_script=.claude/skills/tv-install/scripts/device-busy.sh
q() { perl -e 'alarm shift; exec @ARGV' 5 adb -s "$S" shell "$@" 2>/dev/null | tr -d '\r'; }

for name in TV EMU; do
  S="${!name:-}"
  echo "== $name"
  if [ -z "$S" ]; then echo "  no serial (set $name or internal/device.env)"; continue; fi
  if ! perl -e 'alarm 3; exec @ARGV' adb -s "$S" get-state >/dev/null 2>&1; then
    echo "  unreachable"; continue
  fi
  if [ -f "$busy_script" ]; then
    busy=$(perl -e 'alarm 8; exec @ARGV' bash "$busy_script" "$S" 2>/dev/null | grep -E '^(BUSY|FREE)$')
    echo "  state: ${busy:-unknown}"
  else
    echo "  state: unknown ($busy_script not present)"
  fi
  pkg=$(q dumpsys package "$PKG")
  ver=$(printf '%s\n' "$pkg" | grep -m1 -o 'versionName=[^ ]*')
  code=$(printf '%s\n' "$pkg" | grep -m1 -o 'versionCode=[0-9]*')
  echo "  installed: ${ver:-not installed} ${code}"
  dex=$(q dumpsys package dexopt | grep -A3 "\[$PKG\]" | grep -m1 -o 'status=[^] ]*')
  echo "  dexopt: ${dex:-unknown}"
  if q dumpsys activity activities | grep -m1 -E '(topResumedActivity|mResumedActivity)' | grep -q "$PKG/"; then
    echo "  in front: yes"
  else
    echo "  in front: no"
  fi
done
