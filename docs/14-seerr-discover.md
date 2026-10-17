# 14 — Seerr Discover (request management)

Integration with a [Seerr](https://docs.seerr.dev/) (Jellyseerr/Overseerr) server: browse
what's popular/upcoming, see what's already in the library, and request what isn't —
from a **Discover** entry in the side panel. The UI follows Jellybeam's own brand and
patterns.

## Non-negotiable constraints

- **Zero startup cost.** No Seerr code runs at cold start. The drawer's "is Discover
  configured" check is a local JSON read (`seerr_status`), performed in the existing
  post-startup drawer-metadata effect. No network happens until the user opens the
  Discover screen or the Discover settings section, or types a query into Search.
- **No local mirror.** Seerr data is fetched on demand; caching is in-memory in the
  Rust core only (process-lifetime or shorter). Nothing Seerr touches the media-cache
  crate or mirror.db.
- **Fail open.** A dead/misconfigured Seerr server degrades to an inline message inside
  the Discover screen, or inside Search's Discover section. It must never affect Jellyfin
  browsing, playback, library search results, or startup.
- **Decisions in Rust** (docs/01): availability mapping, season requestability, POST-vs-PUT
  request logic, URL candidate probing, auth/re-auth — all in the core with unit tests.
  Kotlin renders snapshots and forwards commands.
- Direct Play rules, theming rules (no raw colors/fonts outside JellybeamTheme), and the
  redaction rules (docs/05) all apply. Test fixtures use `seerr.test` hosts.

## Architecture

```
core/seerr-api   new crate: hand-written reqwest client for the Seerr HTTP API
core/ffi         seerr.rs (config store + JellybeamCore methods) + seerr_types.rs (records)
app …/ui/discover  Compose screens (shelves, grids, detail, requests, search)
app …/ui/settings  new "Discover" section (connect/disconnect)
```

The client is hand-written against the endpoints we actually call (~20), not generated
from the 7.8k-line OpenAPI spec. DTOs are tolerant (`#[serde(default)]`, only consumed
fields declared).

### Auth (three methods)

`SeerrAuthMethod { JELLYFIN, LOCAL, API_KEY }`
- JELLYFIN: `POST /api/v1/auth/jellyfin {username, password}` → `connect.sid` cookie.
- LOCAL: `POST /api/v1/auth/local {email, password}` → cookie. Jellyseerr matches a local
  account by its email address only (verified against 2.7.3: a username in that field is a
  403), so the identity field is labelled "Email address" for this method.
- A 401, a 403, or any status whose body is Seerr's own error shape (`{"message"}` /
  `{"error"}`) is definitive: the server was found, so probing stops. 401, `INVALID_CREDENTIALS`
  and a bare 403 raise `SeerrInvalidCredentials { method }`; the other bodies raise
  `SeerrSignInRefused { method, reason, detail }` with `reason` one of `MediaServerSignIn`
  (`INVALID_URL`: Seerr could not sign in to its own media server, which is what Jellyseerr 2.x
  returns against Jellyfin 12 since only Seerr 3.0+ sends the modern `Authorization` header),
  `NewUsersBlocked` (403 `Access denied.` under JELLYFIN: user not imported, new sign-ins off),
  `MethodDisabled` (`... is disabled`), or `Other`. Each gets plain-words copy on the Kotlin
  side. A non-Seerr body (a proxy's HTML 404) is "not the server" and probing continues.
  Live coverage: `core/ffi/tests/live_seerr_local.rs` against `tools/dev-seerr/up.sh`, which can
  also start a second instance against a Jellyfin 12 dev server; `JELLYBEAM_SEERR_URL` /
  `JELLYBEAM_JELLYFIN_URL` point the test at it.
  Verified on both Jellyseerr 2.7.3 and Seerr 3.4.1: local sign-in is email-only on
  both, and only the 3.x line signs in against Jellyfin 12.
- API_KEY: `X-Api-Key` header on every request.

Cookie sessions: capture `connect.sid` from the login response manually (no reqwest
cookie-store feature); on a 401 mid-session, re-login **once** with the stored secret,
then surface the error. Each call that gets the 401 logs in on its own (concurrent stale calls
may each log in). A 401 that survives it is
`CoreError::SeerrSessionExpired`, never `Unauthorized` (Jellyfin reauthorization); a relogin that
got no answer is the transport error (`SeerrUnreachable`), not an expired session.
Secrets are stored like Jellyfin tokens (plaintext in the core
data dir, never logged, never committed).

### Per-account config

`<data_dir>/seerr.json`: a list of entries keyed by the Jellyfin `(server_url, user_id)`
identity, each holding `{seerr_url, method, identity, secret}`. `seerr_status`/all Seerr
calls resolve the entry for the *active* session. Tolerant load (missing/corrupt file →
not configured). The live client handle sits in `JellybeamCore`'s `State`, built lazily on
first Seerr call and cleared at every session-swap point (sign_in/restore/switch/
remove-active), same as the playback preload cache. Removing a saved account
prunes that account's entry, secret included, right after the session list commits.

### URL validation (`seerr_connect`)

The user may enter a bare host. Expand to candidates (as-given, http/https, `:5055`
variants — Seerr's default port), normalize to `…/api/v1`, and try a **real login** on
each with tight timeouts (2s connect / 6s read) until one succeeds; those timeouts end with the
probe (the saved connection is rebuilt on the normal 10s / 30s). Because each probe
sends the secret, a bare host ranks every https candidate before any http one
(`https://host`, `https://host:5055`, then the http forms; https never inherits http's
port 80) so an https-only server's credential never travels in cleartext first; the
cost is one failed TLS attempt on plain-http LAN installs, and an explicitly typed
scheme is honored as typed. Save only on success, and immediately fetch
`/settings/public` for `movie4kEnabled`/`series4kEnabled`/`cacheImages`/
`partialRequestsEnabled` (cached on the client handle; the same read runs when a handle is
rebuilt). The read is capped at 5s; a failed or slow read leaves the toggles off for that
handle (the next connect or rebuild reads again).

The connect and the lazy handle rebuild both capture the active `(server_url, user_id)`
before the network work and cache the resulting handle only if that identity is still
active when it completes; a completion landing after an account switch is dropped
rather than re-populating the slot the swap cleared (the persisted entry is keyed
correctly either way).

`seerr.json` is replaced atomically, and its read-modify-write is serialized behind an
intent counter: a connect registers its intent before probing and commits only if no
later connect or disconnect began meanwhile, so the user's latest action wins and a slow
connect can never resurrect an entry a disconnect removed. A lazy rebuild is cached
under the same rule.

## FFI surface (all blocking, coarse snapshots)

Records/enums (`seerr_types.rs`):
- `SeerrStatus { configured, seerr_url?, method?, identity?, app_title? }` — local read only.
- `SeerrCard { media_type: SeerrMediaType, tmdb_id: i64, title, year?, overview?,
  poster_url?, backdrop_url?, availability: SeerrAvailability, jellyfin_item_id? }`
- `SeerrAvailability { NOT_REQUESTED, PENDING, PROCESSING, PARTIALLY_AVAILABLE, AVAILABLE }`
  (Seerr MediaInfo.status 1–5; absent mediaInfo → NOT_REQUESTED).
- `SeerrHome { rows: Vec<SeerrHomeRow> }`, `SeerrHomeRow { id, title, cards }`
- `SeerrPage { cards, page, total_pages, total_results }`
- `SeerrBrowseKind { TRENDING, MOVIES, TV, UPCOMING_MOVIES, UPCOMING_TV }`
- `SeerrBrowseFilters { sort_by?, genre_id?, min_vote?, network_id?, status? }` (all optional)
- `SeerrGenre { id, name }`
- `SeerrMovieDetail` / `SeerrTvDetail`: card fields + `runtime_minutes?`, `genres`,
  `cast: Vec<SeerrPersonRef>`, `similar`, `recommendations`, `trailer_url?`,
  `critics_score?`, `audience_score?`, `active_request?: SeerrActiveRequest`,
  `can_request`, `can_request_4k`; TV adds `seasons: Vec<SeerrSeasonStatus>`.
- `SeerrSeasonStatus { season_number, name, episode_count, availability, requestable }`
- `SeerrPersonRef { person_id, name, role?, profile_url? }`
- `SeerrPersonCredits { name, profile_url?, credits: Vec<SeerrCard> }`
- `SeerrActiveRequest { request_id, status: SeerrRequestStatus, is_4k, seasons }`
- `SeerrRequestStatus { PENDING, APPROVED, DECLINED }`
- `SeerrRequestOptions { servers: Vec<SeerrServiceServer> }` (Radarr/Sonarr instances with
  profiles + root folders; empty ⇒ UI shows a plain Request button).
- `SeerrRequestInput { media_type, tmdb_id, is_4k, seasons, server_id?, profile_id?, root_folder? }`
- `SeerrMyRequest { request_id, card, status, is_4k, seasons, requested_by? }`

`JellybeamCore` methods: `seerr_status`, `seerr_connect(url, method, identity, secret)`,
`seerr_disconnect`, `seerr_home`, `seerr_browse(kind, page, filters)`,
`seerr_genres(media_type)`, `seerr_search(query, page)`, `seerr_movie(tmdb_id)`,
`seerr_tv(tmdb_id)`, `seerr_person(person_id)`, `seerr_request_options(media_type, is_4k)`,
`seerr_submit_request_detail(input) -> SeerrRequestDetail`,
`seerr_cancel_request_detail(media_type, tmdb_id, request_id) -> SeerrRequestDetail`,
`seerr_my_requests`. `SeerrRequestDetail` is `Movie{detail}` / `Tv{detail}` read with one GET after
the mutation (submit = pre-read + POST/PUT + GET, cancel = DELETE + GET): request, availability,
seasons and cast are current, `similar`/`recommendations`/scores are empty, so the caller merges
rather than replaces. The app merges the returned request fields (card, active request,
requestability, TV seasons) into the detail on screen and makes no second read.

Errors: new `CoreError::SeerrNotConfigured`; a request that never got an answer is
`CoreError::SeerrUnreachable{reason}` (an `UnreachableReason`, worded by the app as "Couldn't
reach Seerr (connection refused)…", never the URL or transport text); otherwise map to the
existing `Api{detail}` shape. A Seerr 401 that survives the one relogin is
`CoreError::SeerrSessionExpired` (Discover prompts for new Seerr credentials; it never opens
Jellyfin reauthorization). A submit or cancel that reached Seerr but whose detail GET failed is
`CoreError::SeerrChangeUnconfirmed`: the server changed, so the revision still moves; the title
page closes its dialogs, re-reads once, and on a second failure shows the error beside the card.
Any failed submit or cancel also shows its error beside the card and re-reads the title; Request
and Cancel stay usable (the server-side pre-read keeps a repeat from duplicating). Never leak secrets through
`Display`.

### Request semantics (in Rust, tested)

- Movie: single request. TV: per-season; seasons already pending/processing/available are
  **not requestable**; submitting while the caller already has a PENDING request for the
  item updates it (`PUT /request/{id}`) instead of creating a duplicate (`POST /request`).
  Seerr's `PUT` replaces the request's season list, so the body is the union of the seasons the
  request already holds and the newly asked ones. The existing-request pre-read must succeed:
  a failed read fails the submit rather than being taken for "no request".
- 4K request paths exist but are shown only when the server enables them
  (`movie4kEnabled`/`series4kEnabled` from public settings).
- Ratings endpoints 404 for many titles: treated as "no score", never an error.
- My Requests always sends `requestedBy=<Seerr user id>`: the server auto-scopes
  `GET /request` only for unprivileged accounts, and an `ADMIN`/`MANAGE_REQUESTS` user
  (the instance owner) would otherwise see everyone's requests. Under API_KEY the id is
  the key owner's, from `/auth/me`, so the page is the owner's own requests.

### Image URLs (built in Rust, consumed by Coil)

- Item already in the library (`jellyfinMediaId` present) → the card carries
  `jellyfin_item_id`; Kotlin uses the existing `imageUrl` gateway path.
- Else TMDB: poster `https://image.tmdb.org/t/p/w500{path}`, backdrop
  `w1920_and_h1080_multi_faces`; when the server has `cacheImages` on, swap the base for
  `{seerr_url}/imageproxy/tmdb`.

### Performance

- `seerr_home` fetches all rows concurrently (`tokio::join!`) and fails open per row
  (an errored row is omitted; every row failing is an error, not an empty home, and is never
  cached). Result cached in-core for 60s per account and dropped by a submit/cancel; a fetch
  that straddles one (generation counter bumped by the invalidation) is returned but not cached.
- `seerr_my_requests` resolves up to 50 titles with 10 detail fetches in flight, in server order.
- Browse pages: size 20 (server default), fetched live per call; not cached.
- Genre lists cached for the process lifetime.
- Images ride the existing app-wide Coil loader (RGB_565, 256MB disk cache).

## UI (Jellybeam brand, existing components)

- **Drawer**: "Discover" entry between the libraries and Search, present only when
  `seerr_status().configured`. Re-evaluated on session swap and after connect/disconnect, and
  hidden from an account change until the new account's read lands.
- **Revision**: the gateway's `seerrRevision` moves once per successful connect, disconnect,
  submit or cancel (a change that landed with its detail unreadable included), bumped inside the
  core call so a caller cancelled mid-call still announces it; the drawer entry follows it.
  Discover home and My Requests reload each time they return to the top (a change is always
  made on another screen), and rows already shown stay up during a reload.
- **Discover home**: eager Column of `LazyRow` shelves (Home recipe): Trending,
  Movies, TV, Upcoming Movies, Upcoming TV — plus a top action row (Search,
  My Requests, Movies, TV as chips). PosterCard geometry per docs/07; availability shown
  as a small corner badge (check = available, dot = pending/processing) using the
  WatchBadge pattern; no new colors.
- **Grids** (Movies/TV/view-more): `LazyVerticalGrid(8)` per Library recipe, paged
  (fetch next page on approach), sort + genre filter chips; further filters can follow.
- **Card lists are de-duplicated on the Kotlin side** (`distinctSeerrCards`, keyed
  `media_type-tmdb_id`, first occurrence wins) before they reach any grid or shelf:
  a popularity-sorted browse can repeat an item on the next page, a person's
  cast-plus-crew credits repeat a title, and Compose's lazy layouts throw on a repeated
  key. A browse page that adds no new card still advances the page cursor, and the
  grid's loader effect is keyed on that cursor as well as the card count and `hasMore`,
  so paging carries on by itself until a page grows the list or the server's
  `total_pages` is reached; `hasMore` is always the server's word, never inferred from
  repeats. The cursor is the client's own (a response's `page` never advances or
  rewinds it). Eight consecutive no-growth pages pause the automatic chain, which bounds
  a server echoing a stale page or an inflated `total_pages`; a trigger from a different
  last-visible index (a real user scroll) or a sort/genre change re-arms it. Accepted
  residual: a grid too short to scroll cannot produce that trigger, so a card such a
  server surfaces only after eight full pages of repeats waits for a sort/genre change
  or a reopen. A sort/genre change also clears `isLoadingMore`, since the superseded
  next-page request returns on its stale generation without touching state.
  My Requests keys its cells by `request_id` (rows de-duplicated by it), since one title can carry several
  requests (SD + 4K, season batches).
- **Search**: field-atop-grid per SearchScreen recipe, 300ms debounce,
  generation-guarded, results are Seerr movie/TV cards (Seerr only).
- **Unified search** (main Search screen): library results stay mirror-backed on their
  own 300ms debounce and never wait on Seerr. When `seerr_status().configured` and the
  trimmed query has two or more characters (`discoverQueryReady`), the same query goes to
  `seerr_search` (page 1) after a 700ms pause, with its own generation guard
  and a cancelled predecessor. Its cards follow the library cells in the same grid under
  a full-width "FROM DISCOVER" kicker and 1px divider, rendered as `SeerrPosterCard`
  (availability badge) and opening Discover detail. A Seerr card whose
  `jellyfin_item_id` matches a shown library result's id or series id, or whose kind,
  title and year match a shown Movie/Series (a Seerr linked to another server reports ids
  that never match), is dropped (`discoverCardsBesideLibrary`); an empty remainder hides the section. While pending the
  header shows "Searching Discover…"; a failure shows a one-line muted notice. "No
  matches" waits until both sides settle empty (`showNoMatches`). Down from the field
  lands on the first Discover card when the library side is empty. A new library answer
  resets the grid to the top (its rows are inserted ahead of the Discover header, which the
  grid would otherwise keep anchored). While Discover has results and its header is below the
  last visible cell, the field label's line shows a muted "↓ N FROM DISCOVER BELOW"
  (`discoverHintCount`), unfocusable.
- **Detail**: availability/request state line, Request / Request 4K / Cancel request
  actions, season picker for TV (non-requestable seasons shown checked and inert),
  profile/root-folder pickers only when `SeerrRequestOptions` has entries, cast row,
  Similar + Recommended rows, critic/audience scores when present. If
  `jellyfin_item_id` is set, the primary action is "Go to library" routing to the
  native detail page. Submit and cancel run through one funnel: a single busy slot
  (starting, submitting, cancelling) admits one at a time, and while it is held every action,
  both dialogs' Back and their buttons are inert. A failure shows its message (in the open
  request dialog, or on the page for a cancel) and re-reads the title; until that read
  succeeds, Request, Request 4K and Cancel request are disabled.
- **My Requests**: grid of the account's requests with status badges; Select opens detail.
- **Person**: header + credits grid.
- **Settings → Discover**: status row, URL / method chips / identity / secret fields
  (house `JellybeamTextField` recipe), Connect (spinner + inline error), Disconnect. Connect,
  Disconnect and its confirmation each replace the control that had focus, so the section
  hands focus to the new layout's first chip itself (the selected method chip, the Disconnect
  chip, or the confirm chip) through the retained layer's focus gate; a text field is never the
  landing spot, since landing in one opens editing and the D-pad stops moving.
  Every value writes through on Connect only.

## Testing

- Rust: hand-rolled tokio `TcpListener` mock servers (jellyfin-api convention) for auth
  (incl. 401 → one re-login), request POST/PUT/DELETE shapes, pagination; pure unit
  tests for URL candidates, availability mapping, season requestability, config-store
  roundtrip + tolerant load. Fixtures use `seerr.test`.
- Kotlin JVM: drawer gating, pure mapping/format helpers, FakeCoreGateway coverage.
- Emulator: `python3 tools/fake-seerr/fake_seerr.py [port]` serves the `/api/v1` subset the
  core uses with synthetic data (null poster paths, so no TMDB traffic); connect from the
  emulator as Seerr login at `http://10.0.2.2:5055` with any credentials. Every Discover
  screen is drivable on the emulator this way.
