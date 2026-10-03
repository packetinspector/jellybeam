# 26 — App updates

Status: **Implemented locally; unpublished.** Stable, immutable GitHub
Releases supply discovery, assets, digests and the existing release notes.
Rust owns policy and transport; Android verifies APKs and requests installation.
No server configuration, GitHub login, automatic download or silent install.

## 1. Viewer experience

Settings → **Updates** is the only entry point. A quiet check can add a
Settings indicator; it never opens a dialog or moves focus. Automatic checks
are on by default and run after content is drawn and ten seconds of eligible
foreground browsing. Playback, including paused/buffering/PiP, defers them.

| State | Hero and action |
|---|---|
| Not checked | Check for updates; notes appear after a successful check. |
| Checking | Searching mascot, “Looking for something new…”, Cancel. |
| Available / verified | “A fresh Jellybeam is here.”, installed → target version, download size; **Install update** with “Android will confirm first”. **Later** hides the indicator until tomorrow and returns to Home settings. |
| Downloading | Fast mascot, “Grabbing {version}…”, byte progress and Cancel. |
| Verifying / staging | Short status explains verification or install preparation; Cancel remains available before commit. |
| Current | Happy mascot, “You're on the latest.”, Check again. |
| Failed | Sleeping mascot, a short reason and Try again. Browsing remains usable. |
| Android confirmation | Explicit continuation; no background UI launch. Cancel preserves the verified download. |

The hero sits above two columns: scrollable release notes, then installation
facts and the automatic-check switch. The primary control keeps its focus
identity across state changes. Notes are native text with headings, bullets,
bold emphasis and inline code; links are labels, never executable actions.
No WebView, HTML or remote images. D-pad Up/Down scrolls the notes; Left/Right
leaves the region. Focus rings use the existing green accent.

**Install update** downloads and verifies, then opens Android's confirmation.
If install permission is missing, **Open settings** opens the package's system
permission screen. Returning continues only the explicitly authorized update.
Leaving the app (pause, screensaver, Home) never cancels a download or
verification; it only withdraws install authorization and abandons staging that
has not been committed. Starting Play abandons uncommitted staging and plays.
If a session is committed but the viewer has left Android's confirmation, Play
abandons that session and starts playback; a Play is never dropped. Accounts and
settings stay put.

## 2. GitHub discovery and notes

The production repository and signer are compiled constants in
`core/app-updates/src/policy.rs`. Public requests use a dedicated client with
normal TLS and no token, cookies, Jellyfin headers or client identifiers:

```http
GET https://api.github.com/repos/{official-repository}/releases/latest
Accept: application/vnd.github+json
X-GitHub-Api-Version: 2026-03-10
If-None-Match: <cached etag>
```

Require `draft:false`, `prerelease:false`, `immutable:true`. Pin release id,
tag, asset ids, sizes and SHA-256 digests. Android ordering uses numeric
`version_code`, not tag text. Equal/older versions are current. Immediately
before staging, re-fetch `/releases/{id}` and require the same eligible release
and selected asset identities; deletion, withdrawal or changed assets blocks
installation. A newer latest release never silently replaces the selection.

Render GitHub `body`, stripping `## Install` and subsequent publisher material.
Limit viewer text to 16 KiB, preserving UTF-8 and labeling truncation. Local
release preparation rejects empty or oversized viewer notes. Cache notes/date
for the installed version, so current, checking and error states can still
show useful notes. Prefer concise `## New` / `## Fixed` sections and bullets
whose first phrase is bold; author notes once for both GitHub and TV.

Persist ETag/response and check times. `304` reuses the accepted response.
Successful automatic checks wait 24 hours; failures wait six hours. Manual
checks coalesce and wait at least one minute. Respect rate-limit deadlines,
capped at one hour: `Retry-After` is a delta in seconds, `x-ratelimit-reset` an
epoch. A stored check, snooze or Ready time later than now (the clock
moved backwards) is treated as stale for cadence, snooze and TTL, so a bad clock
can neither suppress checks nor extend a snooze. Unauthenticated requests share GitHub's source-IP limit.
[Releases API](https://docs.github.com/en/rest/releases/releases#get-the-latest-release),
[rate limits](https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api).

## 3. Android metadata and performance

Generate `jellybeam-tv-update.json` from the final signed APK and its AGP
`output-metadata.json`; upload it alongside the APK and SDK-specific `.dm` files.
Synthetic example:

```json
{
  "schema": 1,
  "package": "tv.jellybeam",
  "version_code": 20,
  "min_sdk": 23,
  "abis": ["arm64-v8a", "armeabi-v7a", "x86_64"],
  "apk_asset": "jellybeam-tv-1.2.3.apk",
  "profiles": [
    {"min_sdk": 28, "max_sdk": 30, "asset": "jellybeam-tv-1.2.3-api28-30.dm"},
    {"min_sdk": 31, "max_sdk": 2147483647, "asset": "jellybeam-tv-1.2.3-api31-up.dm"}
  ]
}
```

Only Android facts belong here. Notes, URLs and digests come from GitHub.
Verify bytes/hash before parsing; reject unknown schema/fields, duplicate
keys, invalid integers, ambiguous assets, overlapping profile ranges and
unsupported SDK/ABI. The downloaded APK's actual package, version, minimum
SDK and complete ABI set must agree with metadata.

API 28+ requires exactly one matching `.dm` bucket. Bound ZIP expansion,
allow only expected profile entries and verify each referenced DEX's ZIP
CRC against the APK. Validate supported `010`/`015` profile formats. Stage
`base.apk` and `base.dm` together so Android compiles during installation;
never silently fall back to APK-only installation on covered SDKs. Below
API 28 use the embedded/platform profile path.

## 4. Verification and installation

GitHub immutability is enabled for future releases; existing mutable releases
remain ineligible. Require it per response. APK signing independently establishes
publisher identity: a repository compromise must not authorize another key.
The compiled pin is the production release certificate. Debug/development installations are outside scope;
key rotation requires a separate signing-lineage design.

1. Require HTTPS, official repository asset paths and an exact GitHub/CDN
   redirect allowlist, at most five redirects. Reject URL credentials,
   downgrade and unrelated hosts.
2. Stream to generation-specific private `.part` files, enforce byte counts
   and SHA-256, fsync and rename. Reserve space for downloaded and staged
   copies plus 8 MiB. Rehash immediately before staging.
3. Android `apksig` verifies the APK cryptographically for the device SDK.
   Require the production signer, installed signer, non-debuggable package
   and matching actual Android facts. Package-manager metadata alone is
   insufficient. Reverify immediately before installer copying.
4. Use `PackageInstaller` full-install sessions, fsync APK/profile writes,
   and require user action on API 31+. Android verifies compatibility again.
   Declare `REQUEST_INSTALL_PACKAGES`; no storage permission or FileProvider.
   API 26+ uses per-package install permission; older SDKs use system security
   settings. Never uninstall to recover from an update failure.

An explicit non-exported receiver accepts only the persisted session/generation.
The commit callback is mutable on API 31+, as required by the installer API.
Hold pending confirmation until the update page is resumed and playback is
absent. Persist the committed session before committing; reconcile installed
code and session existence after restart. Never depend on the replaced process
receiving success. Abandon incomplete staging and orphan sessions; preserve
Android-owned committed sessions until reconciliation.

## 5. Ownership, state and bounds

`core/app-updates` has its own lazy runtime outside the account/session mutex.
`core/ffi/src/updates.rs` exposes `AppUpdater`: `snapshot`, `check`, `download`,
`cancel`, `verified`, `prepareInstall`, `installerResult`, `retry`, `snooze`.
Files stay private; their bytes never cross UniFFI. Snapshots contain generation,
revision, status, bounded notes, progress and accepted paths. Kotlin polls at
250 ms only while an operation is busy and the app is in the foreground, and
slowly otherwise; transfers publish at most four times per second.

```text
Idle → Checking → Current | Available | Error
Available → Downloading → Verifying → Ready | Error
Ready → Preparing → Staging → AwaitingConfirmation → Finished
```

A serialized operation owner rejects stale callbacks. Cancel aborts **and joins**
the Rust transport task before another operation can reuse files. The separate
atomic journal stores candidate/cache/cadence; Rust Settings stores
`automatic_update_checks` with a backward-compatible default of true. Kotlin
stores installer identity and foreground authorization. Account switches do
not reset the updater. Restart removes partial downloads and reverifies retained
files; a restart mid-download returns to Available and keeps the candidate; unused Ready files expire after seven days. Committed sessions retain
identity past that TTL because Android owns their copied bytes.

| Bound | Value |
|---|---|
| Release JSON / metadata / viewer notes | 256 KiB / 4 KiB / 16 KiB |
| APK / profile / expanded profile | 128 MiB / 16 MiB / 32 MiB |
| Connect / read stall / check / transfer | 10 s / 30 s / 15 s / 10 min |

## 6. Local release preparation

`./build.sh release` builds the signed, minified candidate locally.
`tools/releases/prepare.py` verifies the official certificate/package, increasing
code, all supported ABIs, matching profile buckets (at most 8) and viewer notes, then emits
APK/DM/JSON/notes plus local hashes. Every asset name must follow the updater's safe-name rule (ASCII
alphanumerics and `-_.`, at most 128, no leading dot; profiles end in `.dm`) with at
most 64 assets. It rejects debug signing and fixture transport. See [release tooling](../tools/releases/README.md).

Later publication uses the existing GitHub release flow: create a draft, upload
all assets, validate their digests, then publish immutable/latest. Verify the
release and assets with GitHub's CLI attestation commands. A bad release is
superseded with a higher version code. No publication is authorized in this work.
The first updater build still requires one ordinary manual sideload.

## 7. Testing and shipment gates

Run `./build.sh test` and `./build.sh check`. Rust tests cover parsing, version/
SDK/ABI/signature policy, URL restrictions, UTF-8 notes, cadence/backoff, ETag,
stream bounds/truncation, stale generations, joined cancellation, persisted
snooze and restart/TTL behavior. Kotlin tests cover profile parsing, formatted
notes, foreground policy and session recovery. Release-tool tests (`tools/releases/test_prepare.py`) bind profiles
to DEX ZIP checksums, reject malformed formats/bounds, and pin the signer, version
code, ABI set, SDK-bucket gaps/overlap/count, asset-name and notes rules, with
`## Install` cut vectors shared with the Rust notes test.

The HTTPS fixture and runner reuse `tools/stress/emulator.py`'s single-emulator
selection, D-pad navigation and focus assertions. They refuse physical devices.
A/B are minified, same-package, same-signer test builds with increasing codes;
the fixture CA/endpoint are compile-time test inputs rejected by production
validation. Save a disposable synthetic-account snapshot; install A by targeted
ADB, then **install B only through the app and Android confirmation**. Raw
captures and logs stay in ignored `internal/`. Restore A between cases.
See [emulator instructions](../tools/updates/README.md).

Runner column: scenarios of `tools/updates/runner.py` (`--scenario`); everything
else is a manual gate.

| Acceptance case | Required proof | Runner | Manual |
|---|---|---|---|
| Normal upgrade | B installed; synthetic account/library and settings retained; next launch recognizes the installed code. | `install`: target code, retained library | Settings and account retention |
| Profile delivery | Android reports profile-based install compilation without a test-side compile command. Compare identical first-launch/browse workloads with the same APK/profile delivered by ADB before performance sign-off. | `install`: `speed-profile` / `install-dm` | ADB-delivered workload comparison |
| Notes and state designs | Visually inspect ready/checking/downloading/current/error at 720p/1080p, 1.3× font and long notes; final bullet and primary action remain remotely reachable. | `notes`: final bullet reachable, captures | All visual inspection |
| Permission / confirmation | Actual permission denial/grant and OS Cancel/retry; unrelated resume never installs. | `install`: grant and accept | Denial, OS Cancel/retry, unrelated resume |
| Cancel / navigation | Transfer closes; no remaining partial writes, background confirmation or focus trap. | `cancel`: transfer closes, action returns | Partial files, background confirmation, focus trap |
| Invalid bytes / release | Mutable, withdrawn, missing/changed digest, wrong signer/package/version/ABI, truncated bytes and corrupt APK with recomputed fixture digest cannot stage. | `corrupt-transfer`, `bad-hash`, `missing-digest`, `mutable`, `withdrawn`, `truncated` | Corrupt APK with recomputed digest (`corrupt-signature` fixture mode), wrong signer/package/version/ABI on a real build |
| Lifecycle / scheduling | Kill during transfer/staging/confirmation; reconcile without duplicate commit. Automatic-off sends no checks; playback/PiP blocks checks/install and Play abandons uncommitted staging. | none | All |
| Faults | Rate limits, stalled transport, exhausted storage and restart expiry preserve the installed app and allow an appropriate retry. | `rate-limit`, `stall` | Exhausted storage, restart expiry, retry |

API **23, 26, 28, 31 and 36** are separate compatibility gates; a pass on one
image does not clear another. Missing images, unreadable notes, or unverified
profile behavior remain open gates. A non-TV image may prove installer
compatibility, never TV focus.
