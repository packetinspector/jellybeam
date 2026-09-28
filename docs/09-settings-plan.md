# 09 — Settings

The settings surface honors the architecture line: **Rust owns settings persistence
semantics and every decision that reads a setting; Kotlin renders the settings screen
and calls coarse-grained FFI.**

## Storage (Rust, core/ffi)

One `settings.json` in the core data dir (next to session.json), owned by the
ffi crate. Whole-record FFI, no per-field chatter:

- `get_settings() -> Settings` (uniffi Record; defaults when file absent)
- `set_settings(Settings)` — persist, update memory, and apply side effects
  (e.g. re-push NextUpOptions to the mirror). A persistence failure is logged;
  the in-memory change still applies.

Only changes to cutoff or rewatching options trigger an immediate Next Up
refresh; unrelated settings do not fetch that shelf. Playback preload
invalidation remains independent of this refresh decision.

JSON stores replace the previous file by renaming a complete temporary sibling;
a failed or interrupted write does not truncate the saved preferences. This is
atomic replacement, not a power-loss durability guarantee or a transaction with
the in-memory state and mirror side effects.

`Settings` fields (types from playback-policy::prefs where they exist):
- `next_up_cutoff_days: Option<u32>` + `next_up_rewatching: bool` → mapped onto
  media-cache `NextUpOptions` via `set_next_up_options` on apply.
- `hidden_library_ids: Vec<String>` → home_snapshot skips these views.
- `hide_watched_in_latest: bool` → latest(..., hide_watched) wiring.
- `home_shelf_size: u32` (presets [10,20,30], default 20) → the limit for
  resume, next_up, favorites and every latest shelf in home_snapshot.
- `home_show_favorites: bool` (default true) → whether home_snapshot returns
  the Favorites shelf; the drawer's Favorites entry ignores it (docs/07 §5).
- `startup_screen: Option<String>` (view id; None = Home) — Kotlin resolves at
  launch, stale id falls back to Home (docs/07 §5).
- `skip_back_secs/skip_forward_secs: u32` (SkipLengthPrefs semantics; presets
  [5,10,15,30,60], default 10/10) — PlaybackScreen seek magnitudes.
- `language: LanguagePrefs` (audio/subtitle + SubtitleMode) — consumed by
  `resolve_track_selection` in the player's prepare/load path.
- `autoplay: AutoplayPrefs` (enabled, delay_secs; default true/10).
- `playback_quality: PlaybackQuality` (DirectPlay default / Auto / Cap {
  max_bps }) — docs/18; read by `prepare_playback` and
  `prepare_transcode_fallback`, never by Kotlin's playback path directly.
- Per-series track memory: separate `track-prefs.json` (SeriesTrackPref map),
  owned by the ffi crate the same way as `settings.json`.

## Kotlin

- Settings screen (Jellybeam style, D-pad list; reachable from the nav drawer) —
  reads/writes the whole record via CoreGateway.
- Row changes update the UI immediately and serialize their whole-record writes
  in input order, so a slow earlier save cannot overwrite a newer selection.
- Consumers re-read via a `settingsFlow` (ChangeEvent-style callback or simple
  StateFlow refreshed after set_settings).
- Track selection: `resolve_track_selection` runs in the prepare/load path, backed by
  per-series memory and subtitle mode, with the Media3 track mapping in `PlayerHolder`.
