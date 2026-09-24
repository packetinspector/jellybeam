# 24 — Server data hazards: virtual placeholder items and Folder children of Series

Any client that mirrors raw Jellyfin item data inherits both hazards unless it
applies the same semantic filters Jellyfin Web applies silently. `media-cache`
applies them in the query layer; this document is the contract those queries cite.

## Hazard 1 — Virtual placeholder episodes pollute "Latest" and counts

**What the server does:** during metadata refreshes, Jellyfin creates
placeholder items for episodes that are announced-but-unaired or missing from
disk. They look like normal `Episode` (and `Season`) DTOs except
`LocationType == "Virtual"`, and their `DateCreated` is **the moment of the
metadata refresh** — not anything related to media files. A nightly refresh can
mint hundreds of them in one batch, whole shows' worth created within minutes of
each other.

**Symptom if unfiltered:** "Latest in <library>" fills with shows nobody added
(their newest *virtual* episode is minutes old); series with only virtual
episodes appear; unwatched badges balloon.

**Rule:** anything answering "what was recently added" or counting
watched/unwatched must require `LocationType != Virtual`. Jellyfin Web does
this on every such query, which is why the web UI looks fine against the same
server.

**Do NOT simply drop virtual items at sync time.** Virtual episodes render as
"unaired" rows (premiere date shown, no play affordance) inside a season's
episode list. Mirror them, flag them (`is_virtual` column), filter them per-surface.

## Hazard 2 — `Folder` items as children of a Series

**What the server does:** when episodes live in per-episode release
directories directly under the show directory (no `Season NN` folder), e.g.
`Show.S05E01.1080p.HEVC.x265-GROUP/`, the scanner creates a real `Folder` item
per directory with `ParentId` = the series. The episodes themselves are still
correctly matched into a proper `Season` object — playback is unaffected. The
junk Folder items just coexist as series children.

**Symptom if unfiltered:** a "seasons" strip built from *all* children of the
series shows release-dir filenames as season tabs, and a season count derived
from `children.len()` is wrong (six Seasons plus ten Folders reads as "16 seasons").

**Rule:** the seasons UI must select children by `Type == "Season"`, never
"all children of the series". (Jellyfin Web uses `/Shows/{id}/Seasons`, which
does this server-side.)

## Hazard 2b — Virtual SEASONS can contain real episodes

The same layout also makes the server mark the *Season* object
`LocationType: Virtual` (there's no physical season directory), even though it
holds real, playable episodes. So:

- Filter the season strip by **type**, not by virtual-ness.
- Never use a Season's `LocationType` to decide playability; check episodes.

## Implementation in `core/media-cache`

- `items.is_virtual` column stamped at ingest from `LocationType == Virtual`
  (`rows.rs`).
- `query.rs::latest` and `latest_grouped_series` add `is_virtual = 0` to their
  WHERE clauses (recency rank per series = newest **real** episode).
- `query.rs::children_checked` gained a Series branch returning only
  `item_type = 'Season'` children, matched semantically
  (`series_id = ? OR (series_id IS NULL AND parent_id = ?)`) — the same
  pattern as the duplicate-Season episode fix. Virtual seasons intentionally
  retained.
- Unwatched badges remain server-supplied (`UserData.UnplayedItemCount`) —
  the server computes them per-user and matches what Jellyfin Web shows.
- Unit tests pin: virtual items excluded from both Latest flavors; a series
  with only virtual episodes absent from Latest; Series children = Seasons
  only, virtual Season included; EXPLAIN QUERY PLAN guards against table
  scans.
