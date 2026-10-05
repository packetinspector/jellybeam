<!-- One coherent change per PR. CONTRIBUTING.md has the full rules. -->

## What and why

<!-- Area: what changed, why it was needed. Link the issue if there is one. -->

## How it was verified

<!-- Tests added or run; for anything on screen or in playback, the emulator or TV you checked it on. -->

## Checklist

- [ ] `./build.sh check` passes (Kotlin unit tests + lint)
- [ ] Rust gates pass if `core/` changed (`cargo fmt --check`, `clippy -D warnings`, `cargo test --workspace`)
- [ ] Rendered UI, focus, or playback changes were checked on an emulator or a TV
- [ ] Behaviour fixes extract the decision into a pure function with a test
- [ ] `docs/13-feature-list.md` updated for any user-visible change
- [ ] No real hostnames, IPs, tokens, media titles, or local paths anywhere in the diff; tests use synthetic values
- [ ] Direct Play stays the default; server names still render verbatim
- [ ] New UI text is a string resource, not a literal (docs/27 §3)
