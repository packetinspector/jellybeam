# Prepare an unpublished release

Build locally with `./build.sh release`, then run:

```sh
python3 tools/releases/prepare.py \
  --notes internal/releases/notes.md \
  --previous-version-code <latest published code> \
  --output-dir internal/releases/candidate
```

The tool inspects the final signed APK through the project container's Android
SDK, rejects debug/fixture signing (a build made without `keystore.properties` is
debug-signed and not publishable, docs/06), validates package/version/ABIs and matching
Baseline Profiles, and emits APK, SDK-specific DM files, Android JSON metadata,
notes and local checksums. It never uploads or publishes. Pass the latest
published version code; the build's default version is the next unreleased one
(`app/build.gradle.kts`). `--output-dir` must be absent or empty; everything is
validated first and written to a temp dir that is then moved into place, so a
failed run leaves nothing stale. Profile buckets are capped at 8 and every
asset name follows the updater's safe-name rules (docs/26 §3).

Write short `## New` / `## Fixed` bullets, with an optional `## Install` section
for publisher material. The app hides that section and everything after it.
Viewer notes must be nonempty and at most 16 KiB. Use `--fixture` solely for
isolated emulator preparation; production rejects the fixture endpoint.

Upload every prepared asset to a GitHub draft before immutable publication.
Verify uploaded digests and release attestations as described in docs/26 §6.
