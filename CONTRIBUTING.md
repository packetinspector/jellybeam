# Contributing to Jellybeam TV

## Build

Everything builds inside the Docker container described in
[`docs/06-build-container.md`](docs/06-build-container.md) — nothing needs
installing on the host beyond Docker itself.

```sh
./build.sh image  # first time, or after a Dockerfile change
./build.sh build  # debug APK
```

## Before a commit

Run the pre-commit gate and the Rust gates. All of them must pass:

```sh
./build.sh check                              # Kotlin unit tests (release variant) + lint
docker compose exec android-build-arm64 bash -c '
  cd /app/core &&
  cargo fmt --check &&
  cargo clippy --workspace -- -D warnings &&
  cargo test --workspace
'
```

(Swap `android-build-arm64` for `android-build` if you're on the amd64
image — see `docs/06-build-container.md` "Two images".)

## What the tests cover

| Layer | Where | Runs in | Covers |
|---|---|---|---|
| Kotlin JVM unit tests | `app/src/test/` | `./build.sh check` | Pure decision functions, formatters, view-model state, focus rules |
| Rust unit and fixture tests | `core/*/src`, `core/*/tests` | `cargo test --workspace` | Sync, queries, playback policy, API parsing, the FFI surface, against fixtures and a mock server |
| Rust live tests | `core/ffi/tests` | `cargo test -- --ignored`, by name | Sign-in, Seerr and sync against a real dev server (docs/06 "Testing") |
| Rendered UI, D-pad focus, playback | none | emulator or a TV, by hand | What the unit layers cannot see |

There is no instrumentation or Compose UI test suite. Anything that
changes how a screen renders, how focus moves, or how a file plays is
verified on an emulator or a real Android TV before it is committed, and
the commit message says what was checked (docs/06 "Running a debug build",
docs/05 "Test servers").

## Commits

One commit per coherent change. Write the summary in the voice the existing
history uses: `Area: what changed and why`, one paragraph — the area names
a screen, layer, or subsystem (`Sign In`, `Discovery`, `Playback`, `ffi`),
followed by what changed, why it was needed, and what was verified. No
AI-attribution trailers of any kind.

## Never commit

- Real server addresses, hostnames, IPs (other than the emulator's
  `10.0.2.2`), or device names.
- Access tokens, API keys, or credentials.
- Media titles, paths, or other identifying library content.
- Local filesystem paths, shell prompts, or unredacted diagnostic output.

Tests and examples use synthetic values only: `example.test`-style
hostnames, RFC-reserved addresses, and generated placeholder UUIDs — never
anything read from a real server.

## Product rules that hold across the whole app

- **Server-configured names are shown verbatim.** Whatever a server calls a
  library, item, or account is what renders. Heuristics may choose an icon;
  they never rewrite a name.
- **Direct Play is the default.** Transcoding or adaptive bitrate is
  strictly opt-in, never a silent fallback.
- **Keep `docs/13-feature-list.md` current.** Any user-visible feature
  addition, removal, or material change updates the shipped and/or
  marketing sections in the same change.

## Code comments

Comments are short contracts, not narration: a doc reference where one
applies, the rule, and the one reason it exists — one sentence by default,
three at most. They say what the code cannot say by itself; they don't
restate the code beneath them or recount how it used to work.

## Fixing a behavior bug

Extract the decision into a pure function and pin it with a test, rather
than patching the symptom in place. A pure function is easy to reason about
and to test exhaustively; the surrounding platform code just calls it.
