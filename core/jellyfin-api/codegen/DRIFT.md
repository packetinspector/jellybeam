# Spec-version vs. server-version drift policy

`jellyfin-api`'s models are generated (see `regen.sh`) from a **checked-in,
hash-pinned** OpenAPI spec snapshot: `jellyfin-openapi-stable.json`, spec
version **12.0.0** as of the W1.1 review (2026-08).

The dev/test server this crate is validated against day-to-day
(`dev/docker-compose.yml`) runs Jellyfin **10.10.7** — an older server than
the spec the models were generated from. This is a **deliberate, known
mismatch**, not an oversight:

- There is no Jellyfin-published per-server-version OpenAPI spec URL to pin
  to instead. `api.jellyfin.org/openapi/` only serves `stable` / `unstable`
  (each with one `_previous` snapshot) — floating channel names, not
  version-addressed files like `jellyfin-openapi-10.10.7.json`. The
  `jellyfin/jellyfin` GitHub releases don't attach an `openapi.json` asset
  per tag either. So "pin the spec to exactly 10.10.7" isn't a URL you can
  fetch; the closest available approximation is "pin to a specific spec
  *snapshot*, chosen and hashed deliberately" — which is what `regen.sh`
  does now (see its header comment for the mechanics).
- A newer spec generating the client is the safe direction for drift: newer
  Jellyfin API versions overwhelmingly *add* optional fields and enum
  values rather than remove or change the meaning of existing ones. typify
  generates every field as `Option<T>` unless the schema marks it required,
  and `extract_subset.py` strips `additionalProperties: false` everywhere
  (see its docstring) so responses from an *older* server that's missing
  fields the 12.0.0 spec thinks exist just deserialize those fields as
  `None` — not an error.
- B1 (the `#[serde(other)]` fallback injected into every generated
  string enum by `postprocess_enums.py`) covers the complementary
  direction: an *older* server sending an enum value the pinned spec
  doesn't know about (rare, but possible if 12.0.0 renamed something)
  deserializes to `Unrecognized` instead of failing the whole page.

Together, those two properties are why "newer spec, older pinned test
server" is safe enough to commit to rather than chase: the client tolerates
both "server is missing a field/value the spec added" and "server sends a
value the spec renamed/dropped."

## What actually catches drift

This policy is a bet, not a proof — it needs a live alarm, not just
reasoning about schema shapes. That alarm is **DEVPLAN.md §2 (API contract
tests)**: `jellyfin-api` + `jellyfin-core`'s contract suite runs against the
live dockerized server today, and per DEVPLAN.md §2 is scheduled to also run
against `jellyfin:unstable` (the newest nightly server build) to get early
warning if a server ships something the pinned 12.0.0 spec truly can't
tolerate — a required field that goes missing, a wire shape change deeper
than "enum got a new value" or "object got a new optional field," etc. If
that scheduled run ever fails, that is the signal to re-pin (see
`regen.sh --update-pin`) and regenerate, not this document.

## Postprocess rules beyond B1

`extract_subset.py` and `postprocess_cleanup.py` carry a few more rules
worth calling out explicitly, since they change what typify would otherwise
emit from the pinned spec as-is:

- **Relaxed `required`.** `UserItemDataDto.Key`, `UserDataChangeInfo.UserDataList`,
  and `UserPolicy.AuthenticationProviderId`/`PasswordResetProviderId` are the
  spec's only `required` response fields that aren't already `Option`-shaped
  by their type; `extract_subset.py`'s `RELAX_REQUIRED` drops them from
  their schema's `required` list before typify runs, so a server omitting
  one of them deserializes to `None`/empty instead of failing the object
  (or, embedded in a page, the whole page). `QuickConnectDto.Secret` is
  deliberately left alone: it's a request body this client builds, not a
  server response, so a caller omitting it should fail to construct the
  request, not silently send nothing.
- **Shared `ImageBlurHashes`.** `BaseItemDto.ImageBlurHashes` and
  `BaseItemPerson.ImageBlurHashes` are inline, field-identical schemas;
  `extract_subset.py` extracts them into one named `ImageBlurHashes` schema
  and points both properties at it via `$ref`, so typify generates a single
  type instead of two identical ones.
- **Dropped root wrapper type.** `postprocess_cleanup.py` deletes the
  `JellyfinSchemas` struct typify generates for the subset document's own
  top-level schema (a passthrough `serde_json::Value` wrapper named after
  the document's `"title"`); nothing in the crate references it.
- **Collapsed `Default` impls.** `postprocess_cleanup.py` also replaces a
  hand-written `impl Default for T` with `#[derive(Default)]` on `T`
  whenever every field initializer is exactly `Default::default()`; a type
  with a spec-declared literal default (e.g. a bool defaulting to `true`)
  keeps its manual impl, since a derive can't express that.

## Re-pinning

Pinning is deliberate by design (see `regen.sh`'s header comment for the
full flow): `./regen.sh --update-pin` fetches the current `stable` spec and
reports its hash; a human then edits `PINNED_SPEC_SHA256` in `regen.sh` and
re-runs `./regen.sh` normally, reviews the `models.rs` diff, and commits
spec file + pin + regenerated models together. Plain `./regen.sh` never
fetches anything and fails loudly if the checked-in spec file's hash
doesn't match the pin.
