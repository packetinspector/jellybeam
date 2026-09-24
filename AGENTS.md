# Agent instructions

Jellybeam TV is a native Android TV Jellyfin client: Kotlin/Compose app over
a Rust core (`core/`) via UniFFI, Media3 ExoPlayer for playback.

## Read order

1. `README.md`
2. `docs/00-START-HERE.md` — docs map and reading order
3. `docs/01-architecture.md` — the Rust/Kotlin boundary
4. `docs/05-conventions-and-servers.md` — product rules, conventions
5. `docs/06-build-container.md` — build/test commands
6. Whichever numbered spec covers the area you're touching (see the map in
   `docs/00-START-HERE.md`)

## Build and test

Run through `./build.sh` (see `docs/06-build-container.md` for the full
list):

```sh
./build.sh build  # debug APK
./build.sh test   # Rust workspace tests, then Gradle unit tests
./build.sh check  # pre-commit gate: Kotlin unit tests + lint
```

## Hard rules

- **Subagents never run git.** The primary session reviews diffs and
  commits.
- **Never add AI-attribution trailers** to commits, PR descriptions, or
  release notes.
- **Never push** without an explicit ask in the current conversation.
- **Never commit identifying or environment-specific info**: real names or
  usernames, account/item ids, server/device/host names, IP/MAC addresses,
  local filesystem paths, shell prompts or history, media titles or paths,
  credentials or tokens, or unredacted diagnostic output. Tests use
  synthetic values only.
- **Server-configured names are shown verbatim** — never prettified or
  renamed.
- **Direct Play is the default** — transcoding is strictly opt-in.
- **Keep `docs/13-feature-list.md` current** with every user-visible
  feature change.
- **Comments are short contracts**: cite `docs/NN §x` where one applies,
  state the rule and its one reason, one sentence by default.
- **Extract behavior decisions into pure functions** and pin them with
  tests, rather than patching a symptom in place.

## Local-only state

Keep anything that shouldn't ship in `internal/` (gitignored) or
`CLAUDE.local.md` (gitignored).
