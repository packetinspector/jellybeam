#!/usr/bin/env bash
# Size and new-mechanism scorecard between two commits, from git archive snapshots (docs/06 "Workflow scripts").
set -eu

case "${1:-}" in
  -h|--help|'')
    cat <<'EOF'
Usage: tools/scorecard.sh BASE HEAD [--md]

Production and test lines (Kotlin, Rust; comments and blanks stripped, Rust
#[cfg(test)] mods counted as tests), coordination counts (state holders, launch
sites, generation/epoch/token mentions, owner-style types, FFI exports), every new
type, FFI export, settings field and file by name, and the files that grew most.
Reads git archive snapshots of app/ and core/ in a temp dir; never touches the
checkout, so it can run beside another session's work.
EOF
    [ -n "${1:-}" ] && exit 0 || exit 2 ;;
esac
base="$1" head="${2:?HEAD ref}" fmt="${3:-text}"
[ "$fmt" = --md ] && fmt=md

cd "$(dirname "$0")/.."
tmp=$(mktemp -d "${TMPDIR:-/tmp}/scorecard.XXXXXX")
trap 'rm -rf "$tmp"' EXIT
for side in base head; do
  ref="${!side}"
  mkdir "$tmp/$side"
  git archive "$ref" app core | tar -x -C "$tmp/$side"
done
python3 -I tools/scorecard.py "$tmp/base" "$tmp/head" "$base" "$head" "$fmt"
