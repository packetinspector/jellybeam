# Emulator stress tests

These tools select exactly one `emulator-*` target. They never select a physical
or network-connected TV. Keep raw captures and account backups in the ignored
`internal/stress/` directory. Never publish these files.

## Synthetic server

Run `python3 tools/stress/server.py` for 10,000 generated movies across eight
libraries. The default listener is localhost-only on port 18096. Options:
`--items` (up to 100,000), `--libraries` (up to 100), `--port`, and `--bind`.
For a Docker container, bind inside the container to `0.0.0.0` but publish the
host port only on `127.0.0.1`. No external dependencies are required.

This fixture implements a subset of Jellyfin 12 for metadata browsing and,
with `--media`, Direct Play of a generated one-minute H.264/AAC clip with
byte-range support. It does not prove Jellyfin API conformance or artwork throughput.
Names, IDs, and credentials are generated or explicitly synthetic. An account
can sign in with any credentials; never expose this server publicly.

`GET /__control` returns aggregate request counts. To inject latency or errors,
POST a JSON body such as `{"delay_ms":1500,"fail_every":3}`. Add
`"fail_path":"/items"` to restrict failures to item requests (useful for failing
the first library page after views are fetched successfully). `fail_every`
counts only the requests `fail_path` matches (every request when it is empty),
starting from the control change, so unrelated user, session or view traffic
never shifts which matching request fails. Reset with
`{"delay_ms":0,"fail_every":0}`. Faults apply to subsequent API requests;
the control endpoint stays available during an outage.
`"broken_art":true` supplies artwork tags whose image endpoints return 404.
Use a fresh mirror, wait at least 25 seconds for initial population and image
retries, then measure idle rendering separately. Clear with `"broken_art":false`.

Validate `/Items` fixture JSON using the Rust `synthetic_fixture_check` example
before timing a sync. A malformed fixture is a test setup failure, not evidence
of slow synchronization.

The optional Compose setup expects `internal/stress/media/synthetic.mp4`, a
generated 60-second 1280×720/30fps H.264/AAC clip. Start it using
`docker compose -p jellybeam-stress -f tools/stress/compose.yaml up -d`.
Use synthetic patterns and tones only, never real media. Stop it using the
same command with `down` instead of `up -d`.

## Emulator setup and measurements

Forward emulator TCP port 18096 to the host port with `adb reverse`, explicitly
selecting the emulator. Sign in manually through Add server, or use
`provision` on a rooted emulator that already has an app session. Provisioning
backs up the complete account list, adds an isolated synthetic account/mirror,
and preserves existing accounts. It refuses to stop foreground playback.

Run from the repository root:

```sh
python3 tools/stress/emulator.py provision
python3 tools/stress/emulator.py launch
python3 tools/stress/emulator.py snapshot
python3 tools/stress/emulator.py measure --seconds 15
python3 tools/stress/emulator.py measure --seconds 20 --keys RIGHT,RIGHT,DOWN,LEFT,LEFT,UP --interval 0.08
python3 tools/stress/emulator.py playback --rounds 5
python3 tools/stress/emulator.py focus-cycles --rounds 10
```

`snapshot` prints redacted semantics and stores the raw hierarchy locally.
`measure` resets frame counters before its window and prints frame percentiles,
memory totals, elapsed time, and the actual number of keys injected. No playback
or selection keys are accepted by `measure`. `keys` supports explicit selection
and Back for test setup; use it only on known synthetic screens.
`playback` verifies the active account is the isolated synthetic session before
force-stopping the app between cold launches. It records numeric startup-phase
durations and first-frame success; it does not report live account data.
`focus-cycles` opens synthetic item details using an explicit component, checks
that the focused node *is* the action-menu door (its label, with no focusable
beneath it) before selecting it, asserts the panel's kicker is gone and focus is
back on the door after closing it, and asserts a Home shelf marker plus a focused
node after the final Back. Start with Home visible. `test_emulator.py` pins the
predicates against XML fixtures (focused ancestor containing the label, focus
elsewhere, an unrelated focused screen).
`provision --fresh-mirror` selects a new synthetic mirror directory without
deleting existing data; use it for first-sync and failed-first-page scenarios.

Run the fixture's own pagination, fault-isolation, and media-range tests with
`python3 -m unittest discover -s tools/stress -p 'test_*.py'`.

Measure release builds with the same compilation mode and starting screen.
Wait for sync and loading animations to settle before measuring steady state.
Frame percentiles are undefined for zero rendered frames and are omitted.
Do not build, encode media, or run other heavy workloads during a comparison.
Time-based key injection includes adb overhead: compare key counts, and do not
present different workloads as an exact before/after navigation speedup.

Android emulators are useful for regressions in focus, lifecycle, cache behavior,
and request handling. They do not establish physical-TV decoder, audio, HDR,
refresh-rate, thermal, or absolute frame-time performance. Google TV integration
also requires an appropriate image or device.
