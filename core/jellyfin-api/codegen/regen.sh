#!/usr/bin/env bash
# Regenerates ../src/models.rs from the Jellyfin OpenAPI spec.
#
# Usage:
#   ./regen.sh              # regenerate curated-subset models from the
#                            # already-checked-in, hash-verified spec file
#   ./regen.sh --full        # generate ALL schemas instead of the curated subset
#   ./regen.sh --update-pin  # deliberately refresh the pinned spec file from
#                            # PINNED_SPEC_URL; does NOT regenerate models.rs
#                            # by itself -- see the printed instructions
#
# Requires: python3, rustfmt, cargo-typify (cargo install cargo-typify --locked)
#
# Graduated from spikes/s3-openapi/regen.sh -- see spikes/s3-openapi/SPIKE.md
# for the full design rationale (why typify, why additionalProperties:false
# must be stripped, why client methods stay hand-written).
#
# --- Spec pinning policy (W1.1 review finding M2) -------------------------
#
# jellyfin-openapi-stable.json is a CHECKED-IN ARTIFACT, not something this
# script silently re-downloads on every run. Two things used to be true that
# this script no longer does:
#
#   1. Every run (no flags) re-fetched "stable" from api.jellyfin.org, which
#      is a *floating* alias -- whatever the Jellyfin project currently
#      calls stable, today possibly newer than the spec version this crate
#      was actually validated against. A plain `./regen.sh` could silently
#      regenerate models.rs against a different spec than last time, with
#      no record of what changed or why.
#   2. There is no per-server-version spec URL upstream (verified during
#      this review: api.jellyfin.org/openapi/ only publishes
#      jellyfin-openapi-{stable,unstable}[_previous].json -- no
#      jellyfin-openapi-10.10.7.json-style artifact, and the jellyfin/jellyfin
#      GitHub releases don't attach an openapi.json asset per tag either).
#      So "pin to the exact spec version matching the dev server" isn't
#      literally possible via URL; what IS possible, and what this script
#      now does, is pin to an exact CONTENT HASH of a spec snapshot chosen
#      deliberately, and never move off it silently.
#
# The current pin (12.0.0) predates the dev server's pinned Jellyfin image
# (10.10.7, see dev/docker-compose.yml) -- newer spec, older server. That is
# a deliberate, known choice, not drift: 12.0.0's schemas deserialize
# 10.10.7's actual responses fine in the live contract tests (extra spec
# fields are all optional; B1's #[serde(other)] fallback covers any enum
# value the older server sends that a newer spec renamed/added since). See
# ../codegen/DRIFT.md for the full policy and how drift gets caught.
#
# Deliberate re-pin flow:
#   1. Run `./regen.sh --update-pin`. It downloads PINNED_SPEC_URL, backs up
#      the old spec file, overwrites jellyfin-openapi-stable.json, and
#      prints the new file's sha256.
#   2. Edit PINNED_SPEC_SHA256 below to that printed value (and bump the
#      version note in the comment above it).
#   3. Run `./regen.sh` normally, review the models.rs diff, commit the new
#      spec file + PINNED_SPEC_SHA256 edit + regenerated models.rs together.
# A plain `./regen.sh` with a stale PINNED_SPEC_SHA256 (spec file edited or
# swapped without updating the pin) fails loudly instead of proceeding.
set -euo pipefail
cd "$(dirname "$0")"

SPEC_FILE="jellyfin-openapi-stable.json"
PINNED_SPEC_URL="https://api.jellyfin.org/openapi/jellyfin-openapi-stable.json"
# Spec version 12.0.0, pinned 2026-08 during the W1.1 review (see policy
# note above). Update only via the --update-pin flow, deliberately.
PINNED_SPEC_SHA256="5c93d9cc151c5a2bf09ed8c4b7d9edb8d6ebbf668b39372b7b6604fc88430fe9"

sha256_of() {
  if command -v shasum >/dev/null; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    sha256sum "$1" | awk '{print $1}'
  fi
}

EXTRA_ARGS=()
UPDATE_PIN=0
for arg in "$@"; do
  case "$arg" in
    --update-pin) UPDATE_PIN=1 ;;
    --full) EXTRA_ARGS+=(--full) ;;
    --no-fetch)
      echo "note: --no-fetch is the default now (this script never fetches" >&2
      echo "silently); ignoring the flag. Use --update-pin to deliberately" >&2
      echo "refresh the pinned spec." >&2
      ;;
    *) echo "unknown arg: $arg" >&2; exit 1 ;;
  esac
done

if [ "$UPDATE_PIN" -eq 1 ]; then
  echo "==> Deliberately refreshing pinned spec from $PINNED_SPEC_URL"
  TMP_SPEC="$(mktemp)"
  curl -sSL -o "$TMP_SPEC" "$PINNED_SPEC_URL"
  NEW_HASH="$(sha256_of "$TMP_SPEC")"
  if [ "$NEW_HASH" = "$PINNED_SPEC_SHA256" ]; then
    echo "==> Downloaded spec matches the current pin already; nothing to do."
    rm -f "$TMP_SPEC"
    exit 0
  fi
  cp "$SPEC_FILE" "${SPEC_FILE}.previous" 2>/dev/null || true
  mv "$TMP_SPEC" "$SPEC_FILE"
  echo "==> Wrote new $SPEC_FILE (previous copy saved as ${SPEC_FILE}.previous)"
  echo "==> New sha256: $NEW_HASH"
  echo "==> ACTION REQUIRED: edit PINNED_SPEC_SHA256 in this script to the"
  echo "    hash above, review what changed (diff against ${SPEC_FILE}.previous,"
  echo "    check the spec's top-level \"version\"), then run ./regen.sh"
  echo "    normally and commit spec + pin + regenerated models.rs together."
  exit 0
fi

echo "==> Verifying $SPEC_FILE against the pinned sha256"
if [ ! -f "$SPEC_FILE" ]; then
  echo "ERROR: $SPEC_FILE is missing. Run ./regen.sh --update-pin to fetch it," >&2
  echo "or restore the checked-in copy." >&2
  exit 1
fi
ACTUAL_HASH="$(sha256_of "$SPEC_FILE")"
if [ "$ACTUAL_HASH" != "$PINNED_SPEC_SHA256" ]; then
  echo "ERROR: $SPEC_FILE does not match PINNED_SPEC_SHA256." >&2
  echo "  expected: $PINNED_SPEC_SHA256" >&2
  echo "  actual:   $ACTUAL_HASH" >&2
  echo "This means the spec file was edited or swapped without a deliberate" >&2
  echo "re-pin. If that was intentional, run ./regen.sh --update-pin and" >&2
  echo "update PINNED_SPEC_SHA256; otherwise restore the checked-in spec file." >&2
  exit 1
fi
echo "==> OK ($ACTUAL_HASH)"

echo "==> Extracting schemas (rewriting refs, dropping additionalProperties:false)"
python3 extract_subset.py ${EXTRA_ARGS[@]+"${EXTRA_ARGS[@]}"} -o jellyfin-schemas-subset.json

echo "==> Running cargo-typify"
command -v cargo-typify >/dev/null || {
  echo "cargo-typify not found; install with: cargo install cargo-typify --locked" >&2
  exit 1
}
cargo typify jellyfin-schemas-subset.json -o ../src/models.rs -B

echo "==> Post-processing: alias BaseItemDtoQueryResult -> ItemsResult"
# lib.rs's frozen interface names the /Items, /UserViews, /UserItems/Resume,
# /Shows/NextUp response type `ItemsResult`; the OpenAPI schema is named
# `BaseItemDtoQueryResult`. Keep the generated struct name faithful to the
# spec and bridge with a type alias rather than renaming the schema (which
# would require rewriting every $ref to it).
if ! grep -q "pub type ItemsResult = BaseItemDtoQueryResult;" ../src/models.rs; then
  {
    echo ""
    echo "/// Alias for the frozen jellyfin-api client interface (lib.rs uses"
    echo "/// \`ItemsResult\`; the OpenAPI schema calls this \`BaseItemDtoQueryResult\`)."
    echo "pub type ItemsResult = BaseItemDtoQueryResult;"
  } >> ../src/models.rs
fi

echo "==> Post-processing: injecting #[serde(other)] fallback into string enums (B1)"
python3 postprocess_enums.py ../src/models.rs

echo "==> Post-processing: dropping dead root type, collapsing trivial Default impls"
python3 postprocess_cleanup.py ../src/models.rs

echo "==> Formatting generated models.rs"
command -v rustfmt >/dev/null && rustfmt --edition 2021 ../src/models.rs

echo "==> Sanity check: no deny_unknown_fields should be present"
if grep -q "deny_unknown_fields" ../src/models.rs; then
  echo "ERROR: deny_unknown_fields leaked into generated models; check extract_subset.py" >&2
  exit 1
fi

echo "==> Sanity check: every unit-variant enum has a #[serde(other)] fallback (B1)"
python3 postprocess_enums.py --check ../src/models.rs

echo "==> Building jellyfin-api"
( cd .. && cargo check )

echo "==> Done. Review ../src/models.rs and the diff before committing."
