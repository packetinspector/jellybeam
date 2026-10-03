# Local update acceptance

Use a disposable project TV emulator with the synthetic account/server from
`tools/stress/README.md`. Exactly one emulator must be connected; the runner
never targets physical devices. Save a snapshot before replacing the app.
All certificates, APKs and diagnostic captures belong in ignored `internal/`.

1. Run `python3 tools/updates/setup_tls.py` once to create a local CA and leaf
   (CA 10 years, leaf 825 days). Fixture APKs embed the CA: after
   `setup_tls.py --rotate`, rebuild every fixture APK.
2. Build fixture B and prepare it **before** building A, because every
   `update-fixture` build overwrites `app/build/outputs/apk/release/app-release.apk`:
   ```sh
   ./build.sh update-fixture 700002 0.1.7
   python3 tools/releases/prepare.py --fixture --previous-version-code 700001 \
     --notes internal/updates/notes.md --output-dir internal/updates/b
   ```
   `--output-dir` must be absent or empty; write any synthetic notes file first.
3. Build A: `./build.sh update-fixture 700001 0.1.6`. Install **A only** with targeted
   ADB on the emulator, sign into the synthetic server and save an A snapshot.
4. Serve prepared B with `python3 tools/updates/fixture.py --directory
   internal/updates/b --cert internal/updates/server.pem --key
   internal/updates/server-key.pem`. Reverse emulator port 18443 to host 18443.
   Synchronize the emulator clock before TLS, particularly after snapshot restore.
5. Run `python3 tools/stress/emulator.py updates --update-scenario install`.
   Other scenarios: `notes`, `cancel`, `corrupt-transfer`, `bad-hash`,
   `missing-digest`, `mutable`, `withdrawn`, `rate-limit`, `stall`, `truncated`
   (`tools/updates/runner.py --scenario <name>` runs one directly). Blocked cases assert the app
   reaches Try again, never Android confirmation, and A stays installed.
   Restore A between cases.
   Never install B through ADB: its upgrade must use the app and Android UI.

The loopback HTTPS control endpoint supports normal/current/checking/error,
slow/stalled/truncated transport, missing/bad digests, mutable/withdrawn release,
rate limits and corrupt signed bytes with a recomputed distribution digest.
The install case checks target code, retained synthetic library and Android's
install-profile compilation. Synthetic settings/account assertions and the full
SDK matrix require the additional gates in docs/26 §7.

The runner requires synthetic fixture A's version code. Restore the original
emulator snapshot and remove task-owned reverse ports afterwards.
Fixture endpoint/CA support is compiled in only for fixture builds; production
preparation rejects those APKs. It is never a user setting.
