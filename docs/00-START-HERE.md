# 00 — Start here

## What Jellybeam TV is

A native Android TV Jellyfin client: a Kotlin/Compose UI over a Rust core
that mirrors the server's library into local SQLite, so browsing is
instant and Direct Play stays the default. `README.md` has the fuller
pitch and the build/install steps.

This folder is the project's living spec. Numbered docs are product specs
and conventions, not a history log — they describe what the app does and
why, not how a change came to be.

## Docs map

- `01-architecture.md` — the Rust/Kotlin boundary and the shape of the app
- `05-conventions-and-servers.md` — brand and product rules, test servers,
  secrets and redaction, code-comment style
- `06-build-container.md` — the Docker build container and `build.sh`
- `07-home-browse-behavior.md` — Home and library browsing behavior
- `09-settings-plan.md` — the Settings screen, section by section
- `10-perf-logging.md` — debug-gated performance logging
- `11-detail-ux-spec.md` — the movie/series/episode detail page layout
- `12-osd-ux-spec.md` — the player OSD
- `13-feature-list.md` — the living feature inventory: shipped, planned,
  and marketing-ready copy. Update this with every user-visible change.
- `14-seerr-discover.md` — the optional Jellyseerr/Overseerr Discover
  integration
- `15-focus-and-selection.md` — the one focus/selection rule every screen
  and the OSD follow
- `16-library-sort-filter.md` — library sort, filter, and the index rail
- `17-mini-player.md` — the picture-in-picture mini player
- `18-playback-quality.md` — the Direct Play / Auto / bitrate-cap Quality
  setting
- `19-detail-action-menu.md` — the detail page's `···` action menu
- `21-user-reporting.md` — the diagnostic log and the LAN report page
- `23-detail-layout-rules.md` — detail page layout rules
- `24-server-virtual-items.md` — how virtual items and
  folder children from the server are handled
- `jellybeam-osd-handoff/` — the OSD specification files that `12-osd-ux-spec.md`
  summarizes; code cites them by section, so they stay as they are
- `brand.md` — name, colour tokens, wordmark, mascot usage, and the acceptance bar

## Reading order for a new contributor

1. `README.md`
2. `00-START-HERE.md` (this file)
3. `01-architecture.md`
4. `05-conventions-and-servers.md`
5. `06-build-container.md`
6. Whichever numbered spec covers the area you're about to touch

## Where to change things

| Work area | Directory | Spec |
|---|---|---|
| Home shelves, continue watching | `ui/home/` | 07 |
| Library grid, sort, filter, index rail | `ui/library/` | 07, 16 |
| Detail pages and the `···` menu | `ui/detail/` | 11, 19, 23 |
| Cards, posters, focus ring | `ui/cards/`, `ui/focus/` | 07, 15 |
| Navigation spine and drawer | `ui/nav/`, `nav/` | 07 |
| Settings | `ui/settings/` | 09 |
| Seerr Discover | `ui/discover/`, `core/seerr-api/` | 14 |
| Player, OSD, seek, skips, PiP | `player/` | 12, 17, 18, `jellybeam-osd-handoff/` |
| Playback decisions (pure) | `core/playback-policy/` | 12, 18 |
| Device profile, play session, reporting | `core/jellyfin-core/` | 05, 18 |
| Jellyfin HTTP, WebSocket, discovery | `core/jellyfin-api/` | 01, 05 |
| Library mirror, sync, queries | `core/media-cache/` | 01, 07 |
| The Rust/Kotlin boundary | `core/ffi/`, `data/` | 01 |
| Diagnostics and bug reports | `diag/`, `ui/report/` | 21 |
| Performance logging | `perf/` | 10 |

Kotlin paths are relative to `app/src/main/kotlin/tv/jellybeam/`; a bare
number is a doc in this folder.

## A convention worth knowing up front

Code comments cite specs as `docs/NN §x`. Section numbers inside a doc are
stable once a comment cites them — don't renumber a doc's existing
sections, even when editing it.
