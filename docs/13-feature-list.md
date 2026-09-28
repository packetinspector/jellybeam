# Jellybeam TV — feature list

## Shipped

**Brand & identity** — the Jellybeam identity throughout: adaptive launcher icon
(the mascot on its own dark ground, with a monochrome layer) and the Google TV
banner; the header lockup on every top-level screen (mascot beside the
two-tone wordmark, `Jelly` in Panna and `beam` in Pistacchio, kerned only
between the y and the b, in Bagel Fat One -- a face used for that word and
nothing else); the About header; and the UI-state mascot on screens with no
content only -- Home with a reachable server and empty libraries (one factual
title and body plus a `host:port │ N LIBRARIES │ 0 ITEMS` strip, no button),
Search with no matches, and the Quick Connect pairing code -- never over
posters, backdrops, video or a populated shelf; on launch, a system splash
(Android 12+ SplashScreen, Notte background and the launcher icon) hands off
immediately to a Compose launch screen -- mascot, rayed wordmark, and a status
line naming the saved server's host, or reporting it unreachable, while the
session resolves.
<!-- verified: app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml; app/src/main/res/drawable-xhdpi/banner.png; app/src/main/kotlin/tv/jellybeam/ui/theme/JellybeamWordmark.kt; app/src/main/kotlin/tv/jellybeam/ui/theme/JellybeamLockup.kt; app/src/main/kotlin/tv/jellybeam/ui/home/HomeScreen.kt EmptyLibraryState/HomeMasthead; app/src/main/kotlin/tv/jellybeam/ui/common/EmptyStateFormat.kt; app/src/main/kotlin/tv/jellybeam/ui/search/SearchScreen.kt SearchNoResults; app/src/main/kotlin/tv/jellybeam/ui/signin/SignInScreen.kt quick connect column; app/src/main/kotlin/tv/jellybeam/ui/settings/AboutSection.kt; app/src/main/kotlin/tv/jellybeam/ui/launch/LaunchScreen.kt; app/src/main/res/values/themes.xml Theme.Jellybeam.Starting; app/src/main/kotlin/tv/jellybeam/MainActivity.kt installSplashScreen -->

**Browsing & library** — mirror-backed instant browse everywhere (local SQLite
mirror, no server round-trip for navigation); Home shelves (resume, next-up,
latest, per-library visibility; a user-set shelf size caps every row, and Next
Up never repeats a Continue Watching title); library grid (Page Up / Page Down, or the
remote's Channel Up / Down rocker, move focus by one screenful of rows in the
same column -- library grids only, never Home, detail pages, or a plugin
channel's live folder-listing); Movies and TV Shows libraries sort and
filter in place (docs/16): a permanent summary line under the title names
the count, the active sort with its direction, and every active filter;
Up from the top row opens a strip that pushes the grid down (sort by Name
using the server's sort name, so leading articles are ignored and numbered
titles run in natural order, Date added, Year, Runtime, Select flips direction; filter by a Watched chip
that cycles Any / Unwatched / Watched, plus Has unwatched on TV Shows, by
Genre, by Years by decade, and on TV Shows by Continuing / Ended; Reset;
the strip closes as soon as focus leaves it); a permanent index rail on the right edge jumps
the grid live by letter, month/year, decade, or duration band; everything
is answered from the local mirror (genres and series status are now
mirrored) and sticks per library across restarts; series/season/episode pages
(duplicate-Season resilient, virtual/unaired episodes shown as non-playable
rows with airing status, junk Folder-as-season-child filtered); movie,
series and episode detail pages laid out as one flow (poster, two-line
title, metadata, actions, a four-line synopsis with a focusable MORE ↓ stop
that opens the full text as a panel, a one-line directed-by / written-by /
studio credit, the outlined spec capsule -- its audio cell carries an ATMOS or
DTS:X suffix in the accent tier when the default track is object-based, read
from the server's spatial-format field or, on servers that only say so in the
stream profile, from there -- and a fixed-width cast row --
nothing overlaps however long the title, synopsis, or a cast name runs);
a `···` action panel at position two of every detail page's button row
(a 330dp side panel that pushes the page column over rather than covering
it; This title / Playback / Library groups under heading bands, never
scrolls, focus lands on the row the page state predicts; mark watched/unwatched for a movie, episode, series or season
with a one-time confirmation on the bulk marks; add/remove favorite; play
next unwatched, play from the beginning, play one random episode; go to
series from an episode; add to an existing collection; refresh metadata
for administrators -- rows that cannot apply are absent, counts and
episode numbers are shown as trailing subtext, and every action reports
with one toast; docs/19-detail-action-menu.md);
search (mirror-backed, live results as you type; with Discover connected, Seerr
results follow in their own labelled "From Discover" section after a longer pause
(two characters or more; a hint above the field counts them while they are below the fold),
never delaying library results, minus titles the library results already show;
docs/14 "Unified search"); hidden libraries (per-library Home
visibility toggle); hide-watched-in-Latest; startup screen (Home or a chosen
library, stale id falls back to Home); password or Jellyfin Quick Connect sign-in
(an address typed without a scheme gets http://, and a failed attempt says what
went wrong in plain words -- not a valid address, server unreachable with the
reason, HTTPS not offered so try http://, answered but not a Jellyfin server
with the HTTP status, or wrong username or password -- in a card kept on
screen above the Sign in button on a scrollable form; a saved token that the
server rejects is still the separate "authorization expired" path);
LAN server autodetection on the Sign In / Add Server screens (Jellyfin UDP
discovery, never a gate: manual URL stays primary; saved servers shown inert;
a server whose published URL omits its port advertises a dead address, so each
hit is probed and, on failure, replaced by the first reachable of `https://host`,
`https://host:8920`, `http://host:8096` and the same on the responder's IP);
multi-server/multi-account session persistence, instant account switching,
add-server, in-place password/Quick Connect reauthorization of expired saved
accounts (preserving their mirror), automatic reauthorization routing when
playback discovers an expired token, and confirmation-gated server removal
(credentials and isolated local cache), with server/account names shown verbatim;
Jellyfin server compatibility 10.11 through 12.x: every request authenticates
with the standard `Authorization: MediaBrowser` header, and the URLs handed to
the image loader and player carry the token as `ApiKey`, the only query form
Jellyfin 12 still accepts (12.0 removed the legacy `api_key`/`X-Emby-*`
methods; no `/emby` or `/mediabrowser` route prefixes are used); exit confirmation (double-press
Back on Home before quitting);
series pages open on the resume/next-up season (viewer's own season choice is
never overridden). A plugin channel library (`ViewKind.CHANNEL`, e.g. a
TVHeadend recordings library) shows in the drawer like any other library but
is browsed live, straight from the server rather than the offline mirror --
its content is never synced, so it is always current, including on return to
an already-open folder. Because a channel's folders/recordings have no art,
no runtime, and mostly identical names (a daily programme), the channel and
its folders (`ViewKind.CHANNEL`/`ViewKind.CHANNEL_FOLDER`) render as a
folder-listing instead of the poster grid: one full-width row per item,
alphabetical for a channel's own folders, newest-first for a folder's
recordings, each recording row showing its own date/time and (when the
server sent one) its per-episode overview as a second line, plus a played
checkmark, a RESUME tag, or a disclosure glyph. Its recordings play as Direct
Play through the server's static stream even when the server, having no
codec facts from the plugin, reports Direct Play as unsupported and offers
only a transcode; every single-file video kind (`Video`, `MusicVideo`,
`Recording`) gets the same Play/Resume action as a movie.
<!-- verified: core/jellyfin-core/src/playback.rs is_codec_blind()/choose(); core/jellyfin-api/src/lib.rs stream_url() item-id path; core/ffi/src/types.rs ViewKind::ChannelFolder, LiveSort; core/ffi/src/object.rs live_children() sort mapping; app/src/main/kotlin/tv/jellybeam/ui/detail/DetailFormatting.kt OTHER_SINGLE_VIDEO_ITEM_TYPES; app/src/main/kotlin/tv/jellybeam/data/CoreGateway.kt liveChildren() doc comment; app/src/main/kotlin/tv/jellybeam/nav/Screen.kt screenForLibraryCard(); app/src/main/kotlin/tv/jellybeam/ui/library/LibraryViewModel.kt ViewKind.isLive branches/liveSortFor() in refresh()/loadNextPage()/onBecameTop(); app/src/main/kotlin/tv/jellybeam/ui/library/LibraryScreen.kt ChannelFolderList diversion; app/src/main/kotlin/tv/jellybeam/ui/library/ChannelFolderList.kt; app/src/main/kotlin/tv/jellybeam/ui/library/RecordingFormatting.kt --> Empty Home retains D-pad access to libraries and server management, including before the first titles arrive.


**Navigation** — a left-edge nav drawer (Home, every library, Discover when
connected, Search, Settings, plus a Servers section) opens with D-pad Left
from the first column of any screen and closes with Right or Back. Its
closed edge is always visible as a menu spine: a thin non-focusable strip
with a centred chevron, so the gesture is discoverable without a focus
stop in the gutter, a coach mark, or any copy, and the open drawer widens
out of that same strip rather than sliding in over it (docs/07 §5). Never on Search or
during playback.
<!-- verified: app/src/main/kotlin/tv/jellybeam/ui/nav/NavDrawerHost.kt; app/src/main/kotlin/tv/jellybeam/ui/nav/MenuSpine.kt -->

**Discover & requests** — optional Jellyseerr/Overseerr integration: a
"Discover" side-drawer entry (shown only once connected) with Trending/
Movies/TV/Upcoming Movies/Upcoming TV shelves, paged Movies/TV grids with
sort (popularity/release date/rating/title) and genre filtering, live
Discover search (its own screen, and a section under the main Search results), and a title detail page (backdrop, genres, cast, critic/
audience scores, availability, Similar/Recommended shelves) with Request
and Request 4K (per-server quality-profile/root-folder picker when the
server exposes more than one option, TV per-season picker with already-
covered seasons shown inert), Cancel request, a "Go to library" shortcut
for titles already in the Jellyfin library, and a My Requests page; connect
via Jellyfin login, Seerr local login, or an API key from Settings >
Discover. Zero impact on cold start (a local-only "is Discover configured"
check gates the drawer entry; no Seerr network call happens until the
Discover screen or its Settings section is opened) and fails open to an
inline message if the Seerr server is unreachable, never affecting Jellyfin
browsing or playback. Every Discover card list is de-duplicated before it
reaches a grid or shelf (a popularity-sorted browse can repeat a title on the
next page; a person's cast-plus-crew credits can repeat one), paging carries
on by itself past a page that added nothing, and My Requests keys its cells by
request id so one title with several requests shows each of them.

**Playback** — Direct Play by default, with an opt-in Quality setting
(docs/18): Direct Play never transcodes and refuses a file the server would
transcode; Auto Direct Plays whatever this TV can decode and transcodes only
on local evidence (no declared decoder for the codec, the tolerate-levels
setting off, a software-only decoder, a decode error, or no supported track);
20/8/3 Mbps presets transcode everything at that rate. The OSD's Direct Play line and
stats headline read TRANSCODE with the reason when one is active; Media3
ExoPlayer + Jellyfin's FFmpeg
audio decoder extension; HDR10/Dolby Vision passthrough as-is; Dolby Atmos
(EAC3-JOC) delivered intact — the spatial bitstream reaches the receiver
unmodified, so Atmos engages where other clients' paths lose it;
tolerate-mislabeled-codec-levels toggle (device-profile + player
decoder selection, default on); FFmpeg TrueHD/DTS/DTS-HD decoder preference
toggles (local renderer choice only, never affects Direct Play negotiation);
pure-black letterbox background; resume + progress reporting, crash-safe local
resume checkpoints; trickplay seek preview (the server's own thumbnail
tiles, region-decoded per tile so no sprite sheet is ever held as a bitmap;
Small / Medium / Large panel via Settings > OSD, default Medium);
chapter markers + chapter-jump transport (also on the remote: Page Up or
Channel Up jumps to the next chapter's start, Page Down or Channel Down to the
current chapter's start, or the previous chapter's when within 5s of it --
OSD hidden or visible, never revealing it; a centre flash names the chapter);
skip intro/credits/recap/preview/
commercial with per-type Ask/Auto-skip/Off (commercial defaults to Auto-skip),
Auto-skip shows an Undo toast; autoplay next episode with a countdown card whose
depleting rule and "IN {n}" numeral count down per frame to the exact hand-over
instant (dismissible, independent of OSD idle-fade); a "Still watching?" inactivity
guard (configurable: off, after N episodes, or after N hours with no input) that
replaces the countdown card with an explicit "Keep Watching" prompt once tripped
(Back stops), the same depleting rule and numeral counting down the answer timeout,
reset by any key press — Stop reports the finished episode normally, never starts or
marks the next one, and returns to its detail page;
track selection
(audio/subtitle) with per-series memory; subtitle mode Default/Always/
OnlyForced/None; subtitle styling (size, vertical position, bold, background
opacity); skip back/forward length presets (5/10/15/30/60s, default 10/10);
on-demand playback-stats sheet with live pipeline stats (video/audio/subtitle
stream breakdown, buffered-ahead time, allocated buffer size, network estimate,
playback state, dropped frames, plus the source file's name/size); a separate, on-demand library-info panel for the current
title (episode/season identity, plot, air/premiere and library-added dates,
watch count and last-watch date, content/community/critic ratings when
available, genres, director/writer, studio, and runtime); one unified track-selector control for the shared
audio/subtitle picker;
external playback orchestration through an exported PLAY intent or
`jellybeam://play/&lt;item-id&gt;` deep link (active server/account, resume semantics,
with an on-demand server lookup when the mirror has not cached the item yet);
focus-dwell preload: sustained focus on a playable Home/hero/library-grid/episode
card—or reaching its high-intent Play/Resume action—prefetches the playback
handshake, trimming press-Play latency, and re-arms itself while focus stays put
past the cache's lifetime; also fires once for the next episode when the
autoplay countdown card appears, and a same-item Play press joins an in-flight
prefetch instead of restarting it; metadata only, toggleable, and bounded to one
cancellable request so browsing cannot build a speculative network backlog;
redesigned player OSD (spec of record: docs/12-osd-ux-spec.md, read from
source; docs/jellybeam-osd-handoff/ holds the design history) — glyph-only
control buttons with no containers, rings, or per-button captions (focus
reads as a color change alone), a thinned position scrubber (played fill +
chapter ticks, no buffered fill), and flat-tint info/stats sheets with no
blur; two density modes via Settings > OSD's "Minimal | Full" (default
Full) — Full adds a codec strip, an ENDS wall-clock readout, and speed +
stats controls; restructured control row (episode previous/next for
episodic content, resolved from the local mirror with one post-load server
fallback for newly/deep-linked items, a combined audio-and-subtitles button with a
non-default-track dot, a scrolling chapters menu when markers exist (numbered rows, real chapter titles wrapped underneath), and separate
library-info and playback-stats sheets); playback speed control, 0.5–2x
(Full mode, per-session only — not persisted across playback sessions),
ENDS-aware; server-configured item and series names shown verbatim; exact
stored video dimensions in playback stats plus crop-aware resolution tiers
in the codec strip; silent one-step hidden-OSD Left/Right seeking using the
configured interval, with no chrome reveal or unused thumbnail work on a
first press; holding Left/Right past 500ms enters hold-to-seek, a
continuous accelerating glide (6x/30x/120x tiers, then a duration-scaled
tier that crosses the whole file in ~8s) over a minimal traversal
bar/tile/chip surface -- Glide Seek with trickplay: the thumbnail stays on
screen through the whole hold, re-sampled at most ~7 times a second at any
speed and never blanking mid-gesture, with sheets prefetched one ahead in
the glide direction under a one-in-flight/one-queued budget so a VPN or a
weak TV sees the same pacing as a LAN -- committing exactly one seek on release without
touching autoplay, up-next, or watched state even at the end clamp;
routine sub-750ms decoder repositioning does not
flash a misleading network-buffering prompt; center transient flash on on-screen
transport actions; idle auto-hide (now 5s) with paused/panel-open exceptions and
a deliberate Back chain (close panel, hide OSD, then exit playback);
MediaSession publication while playing (TV system UI, global play/pause,
Assistant see and control the player); optional mini player (Settings >
Playback, default off -- spec: docs/17-mini-player.md): Back or Home during
playback shrinks the video into the system picture-in-picture window and keeps
it playing while you browse; playing anything else (in-app, PLAY intent, deep
link) swaps the new item into the same player and expands it; Next Up
autoplays inside the window; dismissing the window from the system UI or
exiting Jellybeam stops playback with a final position report, never a headless
player.

**Resilience** — an audio clock guard keeps a skip from parking playback in an endless buffering state on boxes whose passthrough audio track reports a position ahead of what it has played (docs/18 §5.1); network-aware load retry policy riding out Wi-Fi radio stalls
(60s / 64MB buffer, up to 90s of retries before surfacing an error);
reconnect with backoff on transient network errors, frozen resume position
preserved across the interlock; transactional sync/sign-in (add-server and
view-snapshot writes are all-or-nothing, revoked-library cleanup on
snapshot refresh, and a reconciliation sweep never prunes an item that arrived while it was running); background library sync yields while a stream is playing so the network and SQLite stay with playback; folder and collection browse sort names case-insensitively like the library grid; serialized play-next transitions; per-profile codec
ceilings from a device-capability probe. The websocket connection that
feeds live mirror updates supervises itself with capped exponential
backoff and jitter, so a dropped socket (screen off, brief Wi-Fi loss, a
server restart) reconnects on its own with no app-level retry logic and no
Android lifecycle hook needed for correctness — every successful reconnect
triggers a resume+delta+reconcile pass that heals whatever changed while
disconnected. Signing out, switching accounts, or removing the active
account stops that socket and its reconnect loop outright rather than
leaving it running in the background. Card art (Home, Library, Search, and
Discover posters) that fails to load retries on its own instead of showing
a blank/blurhash tile for the rest of that screen's life: bounded backoff
(up to three attempts, 2s/4s/8s) restarts automatically on a failed load,
plus one immediate retry whenever the app returns to the foreground. Art that arrives within 250 ms (cache hits) fades straight in with no placeholder flash; slower loads show the blurhash or pulse after that grace. Static named fallbacks for missing artwork; loading pulses stop when image retries are exhausted, avoiding perpetual animation on incomplete libraries. The Home sync pill names the library being synced by its server-configured name with an item count ("Syncing Movies — 40 of 120…"), during the cold-start skeleton and for background syncs after it, and never shows a library id. Failed or cancelled initial library syncs release their syncing state and buffered-update waiters without recording a successful sync cursor.
<!-- verified: core/jellyfin-core/src/event_bus.rs EventBus::spawn/supervise (Backoff, USEFUL_CONNECTION_DURATION), EventBusHandle::shutdown; core/ffi/src/object.rs open_mirror (spawn ordering doc comment), stop_event_bus and its call sites in sign_out/switch_session/remove_session/open_mirror re-entry/Drop -->
<!-- verified: app/src/main/kotlin/tv/jellybeam/ui/cards/CardArt.kt IMAGE_LOAD_MAX_RETRIES = 3, IMAGE_LOAD_RETRY_BASE_MS = 2000L, imageRetryDelayMs, CardArtImage's retryAttempt/retryToken LaunchedEffects; app/src/main/kotlin/tv/jellybeam/AppForeground.kt resumeCount, bumped from MainActivity.onResume; app/src/main/kotlin/tv/jellybeam/ui/discover/DiscoverPosterCard.kt SeerrPosterArt (same recipe) -->


**Accounts** — each account signs in with its own device identity, derived
from the install and the server address, so a direct account and a
multi-server proxy account over the same Jellyfin server never revoke each
other's token (Jellyfin logs out a user's session when the same device id
signs in again, and a proxy forwards the client's id to its backends); a
saved token keeps the identity it was issued to until its next sign-in. An
expired or revoked token opens re-authorization from whichever screen hit it
-- Detail, Home, Library, Search, not only Play -- and, because browsing reads
the local mirror and would never notice, the saved token is also checked with
one authenticated request at launch, on account switch and on return to the
foreground (at most once a minute); the screen underneath
is left alone while the prompt is up; Seerr's own sign-in failures never
trigger it.
<!-- verified: core/ffi/src/device_id.rs for_server; core/ffi/src/object.rs client_identity/saved_identity, install_authenticated_session; app/src/main/kotlin/tv/jellybeam/data/CoreGateway.kt ffi()/noteCoreFailure; data/CoreExceptions.kt routesToReauthorization; player/PlaybackActivity.kt AuthorizationRecoveryCoordinator; MainActivity.kt authorizationRecoveryCoordinator collector, checkSession; core/ffi/src/object.rs validate_session -->

**Server compatibility** — support floor 10.11; the server's version is
captured from `/System/Info/Public` at sign-in and persisted on the
per-server session record (known offline at startup, survives restarts),
refreshed on session restore, account switch, token reauthorization, every
websocket reconnect (a server upgrade restarts the server and drops the
socket) and a five-minute backstop poll, with a changed value emitted as a
refresh event so gated screens re-evaluate; `server_at_least(major, minor)`
fails closed (unknown version means "not at least") so 12.0-only calls stay
on the 10.11 path until the version is known. The server's own name rides
the same refresh and persist path for the About section.
<!-- verified: core/jellyfin-api/src/lib.rs ServerVersion/PublicServerInfo/refresh_public_system_info; core/ffi/src/session.rs SessionFile.server_version/server_name; core/ffi/src/object.rs State.server_version, server_at_least, refresh_server_version_once, spawn_server_version_reconnect_watch, SERVER_VERSION_REFRESH_INTERVAL; app/src/main/kotlin/tv/jellybeam/data/CoreGateway.kt serverAtLeast -->

**Performance** — embedded Baseline Profile (`app/src/main/baseline-prof.txt`,
merged with the profiles Compose/Coil/lifecycle ship): the release APK
carries an ART profile covering the app's own code, the uniffi/JNA bridge,
Media3, OkHttp, and the Kotlin stdlib, so sideloaded installs get
profile-guided AOT without any manual `cmd package compile` step. AGP also
emits the matching `.dm` next to the APK; `./build.sh install` sideloads
both so the device compiles at install time. Debug-gated perf logging
(`docs/10-perf-logging.md`) reports the profile's compilation status once
per process. The Rust core ships link-time optimized and stripped, and the
APK packages only the ABIs the core is built for. Cold start gets a head
start: session restore, the mirror open and Home's first snapshot begin at
process start and overlap the first frame instead of waiting for it. Home, Library, and Detail stay live during a sync burst
instead of freezing on stale data until it quiets down: a shared
leading-refresh + bounded-periodic-sampling scheduler (500ms while the
screen is on top, 3s while a retained screen is hidden, with an immediate
catch-up refresh when it becomes top again) replaces a resetting debounce
that could never fire while the mirror kept delivering changes every
~250ms. The nav drawer's library list
uses a lighter conflate-and-delay loop with the same leading-refresh/
bounded-rate/guaranteed-trailing-refresh shape, sized for its much smaller
payload.
<!-- verified: core/Cargo.toml [profile.release]; app/build.gradle.kts ndkVersion, abiFilters; app/src/main/kotlin/tv/jellybeam/data/LaunchWarmup.kt; app/src/main/kotlin/tv/jellybeam/JellybeamApp.kt AppGraph.launchWarmup; app/src/main/kotlin/tv/jellybeam/ui/common/ChangeRefreshScheduler.kt; app/src/main/kotlin/tv/jellybeam/ui/home/HomeViewModel.kt changeRefreshScheduler; app/src/main/kotlin/tv/jellybeam/ui/library/LibraryViewModel.kt changeRefreshScheduler; app/src/main/kotlin/tv/jellybeam/ui/detail/DetailViewModel.kt changeRefreshScheduler; app/src/main/kotlin/tv/jellybeam/MainActivity.kt JellybeamRoot drawer changeEvents conflate; core/media-cache/src/lib.rs recv_changes 250ms window -->

**Settings** — whole-record Rust-owned settings store (`get_settings`/
`set_settings`, tolerant load/migration, atomic file replacement and ordered
Settings-screen saves); Home section (Next Up cutoff
presets + rewatching toggle, per-library Home visibility, shelf size (10/20/30,
default 20, every Home row), hide-watched-in-Latest,
startup screen picker, clock toggle, show/hide missing episodes); Playback
section (skip back/forward, Quality chips (Direct Play / Auto / 20 / 8 / 3
Mbps, default Direct Play), autoplay enabled + delay, mini player toggle
(default off; greyed with a note on devices without picture-in-picture), Still
watching? group (mode: off/after episodes/after hours, episode/hour
thresholds, answer timeout, button-presses-reset-the-count toggle — greyed
with a note when autoplay is off), per-segment skip behaviour, Advanced group:
tolerate-mislabeled-levels + preload-on-focus + FFmpeg audio decoder
preferences); OSD section (player OSD density, Minimal/Full, default Full; seek preview size, Small/Medium/Large, default Medium);
Subtitles section (size, position, bold, background presets); Discover section
(Jellyseerr/Overseerr connect/disconnect: server address, auth method,
credentials -- the identity field reads "Email address" for a Seerr account, a
rejected sign-in says so in plain words instead of an HTTP status, a refusal on Seerr's
own side names the fix -- Seerr can't sign in to its Jellyfin (Jellyseerr 2.x against
Jellyfin 12), the sign-in method is switched off, or the Jellyfin user isn't in Seerr yet
with new sign-ins off -- and focus follows
Connect, Disconnect and its confirmation instead of being lost); About section (a centred brand header -- mark, wordmark, descriptor,
version and build, a Kotlin / Rust / Media3 / Direct Play pill -- rising beside
the Settings title so the page fits one screen, then the connected server as
stat cards, never option rows: a Server card with the server's own name and
version, product / operating system / architecture when the account may read
`/System/Info`, address and account, its running / restart-pending /
update-available state in the card header, and the mirrored library's item
counts as big-number Library tiles, one per non-empty type -- counted from the
local mirror, so a multi-server proxy's merged library reads correctly, with
one number size per page that shrinks to fit libraries in the millions; a Local Mirror card --
items mirrored, database size, last full sync, last change check, sync state,
with the live-events connection in its header; and a This Device card with the
Android and Media3 versions and the device id; hairline-ruled label/value
rows (Archivo labels, monospace values); each card is a quiet focusable
block so the D-pad walks and scrolls them; there is no Refresh control --
every entry into the section re-fetches the details and re-persists the
server's name and version for the version gate); every option row carries a
one-line description that fades in for the focused row (fixed row heights --
64dp toggle rows, 76dp chip rows -- no reflow), and the pane remembers the
last focused row per section; Troubleshooting section (Diagnostic logging
toggle with status, Crash reports toggle, Report a problem, Clear log).

**User reporting** — docs/21: a redacted diagnostic log (off by default;
Settings > Troubleshooting) recorded as a 2000-line / 256 KB ring off the
hot path with per-process aliases in place of item and server ids, Rust
`tracing` WARN/ERROR forwarded into the same ring, perf-tier lines mirrored
when the docs/10 gate is on; Report a problem serves a frozen snapshot
(summary, full log, JSON) on the local network behind a QR-coded random
path for ten minutes, shows "Opened on a phone" / "Log downloaded", and the
phone page opens a prefilled GitHub issue form; crash capture (on by
default, own toggle) with a next-launch Report now / Later / Discard
dialog. Nothing leaves the TV until the user submits on GitHub.
<!-- verified: app/src/main/kotlin/tv/jellybeam/diag/DiagLog.kt, ReportServer.kt, CrashCapture.kt; ui/report/ReportScreen.kt; core/ffi/src/diag.rs; .github/ISSUE_TEMPLATE/tv-bug.yml -->

**Focus and selection** — one precedence rule for every screen and OSD
surface (docs/15-focus-and-selection.md): on entry, restored focus (the
element the viewer left, matched by stable id, never by index) > the
selected/current item > the surface's one declared fallback; a submenu,
picker, sheet or dialog returns focus to the control that opened it;
memory survives push/pop, returning from playback, and configuration
changes, and is discarded on sign-out. One shared mechanism (`FocusMemory`
+ `FocusRestorer`, `ui/focus/`) replaces per-screen restore code. Focus is
the 3dp Pistacchio ring only; selection is a fill (controls) or a leading
check (list rows); "current" is a 6dp dot (drawer section, playing
chapter). Stacked rows never overpaint a neighbour's ring.
<!-- verified: app/src/main/kotlin/tv/jellybeam/ui/focus/FocusMemory.kt; ui/cards/CardArt.kt focusRing zIndex; ui/detail/DetailScreen.kt, ui/settings/SettingsScreen.kt, ui/search/SearchScreen.kt FocusRestorer call sites; player/PlaybackScreen.kt restoreInvokerFocus/OsdRowLeading -->

## Why Jellybeam — differentiators

*(For marketing-site use. Every bullet below is verified against the current
source tree; file paths are cited in HTML comments and are not meant for
public display.)*

**Plays everything, honestly**

- Direct Play is the default and refuses rather than quietly downgrading:
  when the server decides a file would need transcoding, Jellybeam tells you
  why instead of handing you a transcode. Transcoding is strictly opt-in:
  Auto keeps Direct Playing everything your TV's decoders can actually
  handle and transcodes only on local evidence, and a bitrate preset
  transcodes everything at the rate you chose.
  <!-- verified: core/ffi/src/object.rs resolve_plan() (Direct Play mode: Transcode decision -> CoreError::WouldTranscode; Auto: up-front transcode only when tolerate_mislabeled_levels is off or the codec is outside android_direct_play_video_codecs, else attempt-anyway Direct Play; Cap: force_transcode negotiation); app/src/main/kotlin/tv/jellybeam/player/LocalPlayability.kt, SoftwareDecoder.kt + PlaybackViewModel.maybeFallBackToTranscode() are the runtime fallback triggers -->

- Works with Jellyfin 10.11 through 12.x, including 12.0's default of
  refusing legacy authorization: the app never depended on the removed
  `api_key` query token, `X-Emby-*` headers or `/emby` route prefixes
  that break older third-party clients on upgrade.
  <!-- verified: core/jellyfin-api/src/lib.rs auth_header() (MediaBrowser scheme with Token), image_url/trickplay_tile_url/stream_url builders (ApiKey query; on 12.0 trickplay tiles were the TV's only endpoint that rejected the old form -- images and static streams are anonymous), core/jellyfin-api/src/ws.rs connect() (Authorization header on the socket upgrade; crate-level, the TV does not open the socket today); live suite run against jellyfin/jellyfin:12.0 -->

- The device profile Jellybeam sends the server is built from a real,
  on-device probe of your TV's actual decoder hardware (codecs, profiles,
  levels, resolutions) at every startup, not a canned capabilities list —
  and playback waits for that probe before it ever asks the server what it
  can play.
  <!-- verified: app/src/main/kotlin/tv/jellybeam/player/DeviceCapsProbe.kt probeUnsafe() (MediaCodecList enumeration); app/src/main/kotlin/tv/jellybeam/JellybeamApp.kt deviceCapsReady CompletableDeferred; app/src/main/kotlin/tv/jellybeam/data/CoreGateway.kt line ~519 deviceCapsReady?.await() -->
- An advanced "tolerate mislabeled codec levels" toggle (on by default) lets
  a file whose container over-states its own HEVC level — common with
  sloppy encoders — still Direct Play at full HDR quality instead of being
  needlessly refused and transcoded; the hardware decoder is asked directly
  whether it can actually handle the stream.
  <!-- verified: core/jellyfin-core/src/device_profile.rs android_video_codec_profiles() doc comment and tolerate_mislabeled_levels gating; app/src/main/kotlin/tv/jellybeam/player/DecoderSelection.kt MediaCodecSelector wrapping -->
- Jellyfin's own FFmpeg audio decoder extension is bundled, so TrueHD,
  DTS, DTS-HD, and EAC3 decode on-device instead of forcing a server-side
  transcode; TrueHD/DTS/DTS-HD each have their own on/off preference in
  Settings.
  <!-- verified: gradle/libs.versions.toml jellyfin-media3-ffmpeg-decoder; core/jellyfin-core/src/device_profile.rs ANDROID_DIRECT_PLAY_AUDIO_CODECS; app/src/main/kotlin/tv/jellybeam/ui/settings/SettingsSections.kt preferFfmpegTrueHd/preferFfmpegDts/preferFfmpegDtsHd -->
- Embedded subtitles just work, because Direct Play delivers the whole
  file: SRT, TTML, WebVTT, ASS/SSA, and bitmap PGS/VobSub tracks all
  render straight from the container — including ASS, which several
  Android clients can't show without a transcode. (ASS styling is
  simplified relative to a full libass renderer: text and basic styling
  render; elaborate typesetting is approximated. External sidecar files
  are not loaded yet — see Planned.)
  <!-- verified live on-device (embedded ASS rendering during Direct Play); Media3 DefaultSubtitleParserFactory includes SsaParser for demuxed application/x-ssa tracks; no SubtitleConfiguration wiring exists in app/ so external sidecars are never attached; device_profile.rs subtitle profiles govern only server-side delivery, not Direct Play demux -->
- Pure-black letterbox bars — no washed-out gray edges on OLED panels
  during scope/widescreen content.
  <!-- verified: app/src/main/kotlin/tv/jellybeam/player/PlaybackScreen.kt Color.Black background + setBackgroundColor/setShutterBackgroundColor BLACK, three-layer letterbox -->
- Browse and request new movies & shows (Jellyseerr/Overseerr) right from
  the couch — zero impact on startup or browsing speed.
  <!-- verified: core/ffi/src/seerr.rs seerr_status() doc comment "Local-file-only read (zero network): whether Discover is configured... Safe to call near startup"; app/src/main/kotlin/tv/jellybeam/MainActivity.kt discoverConfigured LaunchedEffect keyed off gateway.seerrStatus() only, separate from the startup restoreSession/openMirror effect; app/src/main/kotlin/tv/jellybeam/ui/discover/DiscoverViewModel.kt fetches seerr_home() only on its own init, never from app startup -->

**Instant everywhere**

- The entire server library — every show, season, episode, and image
  reference — mirrors into a local SQLite database, so browsing never
  waits on a network round trip. A websocket connection pushes server-side
  changes to the mirror live — a library scan adding an episode, or the same
  account marking something watched from another device, shows up within a
  second with no refresh — with a delta sync on every start plus a
  five-minute reconcile pass as the backstop for whatever the socket misses
  while disconnected (screen off, brief network loss, a server restart);
  every screen refreshes live as the mirror changes.
  <!-- verified: core/media-cache/src/schema.rs; core/media-cache/src/sync.rs RECONCILE_INTERVAL + delta_sync + apply_bus_event NeedsReconcile handling; core/ffi/src/object.rs open_mirror (jellyfin_core::EventBus::spawn + spawn_bus_forwarder), spawn_listener_task (mirror change stream); core/jellyfin-core/src/event_bus.rs supervise() reconnect/backoff; core/ffi/tests/live_local_server.rs websocket_delivers_a_change_made_by_another_session measured ~0.8 s against 10.10.7 and 12.0 dev servers; on the TV against the live server the same day, a played-flag change from another device produced exactly one Home refresh ~2 s later (0 refreshes in the idle 15 s before), observed through PerfLog's ffi.homeSnapshot lines -->
- Sorting and filtering a library never leaves the grid and never asks
  the server: sort field, direction, watched state, genre, decade, and
  series status are all mirror queries answered in milliseconds, with a
  right-edge index rail that jumps by letter, month, decade, or duration.
  <!-- verified: core/media-cache/src/query.rs library_grid_checked()/library_grid_groups()/library_genres(); app/src/main/kotlin/tv/jellybeam/ui/library/LibrarySortStrip.kt; app/src/main/kotlin/tv/jellybeam/ui/library/IndexRail.kt; docs/16-library-sort-filter.md -->
- Search is instant, powered by a real SQLite FTS5 full-text index —
  results appear as you type, entirely against the local mirror, matching
  word prefixes in titles, original titles and series names (not synopses).
  <!-- verified: core/media-cache/src/schema.rs "CREATE VIRTUAL TABLE IF NOT EXISTS search USING fts5(" -->
- All local writes go through a single dedicated writer task with strict
  command ordering, so the mirror can never be corrupted by concurrent
  writes racing each other.
  <!-- verified: core/media-cache/src/writer.rs top-of-file doc comment "The single writer: owns the read-write Connection, drains a command..." -->
- Multi-server, multi-account sign-in with instant switching — changing
  the active account swaps local state and installs a new client
  synchronously, with zero network round trip. A D-pad-native server manager
  can refresh an expired credential in place by password or Quick Connect
  without losing the account's mirror, or remove any saved account and its
  isolated local mirror; removing the active account safely falls back to
  another saved account or Sign In. Reauthorization is identity-bound, so
  approving Quick Connect as a different user cannot replace the selected
  account. If playback is the first live request to discover that a saved
  token expired, Jellybeam exits the failed player and opens that account's
  reauthorization screen automatically instead of leaving an unauthorized
  toast as a dead end.
  <!-- verified: core/ffi/src/error.rs typed Unauthorized mapping; core/ffi/src/object.rs switch_session()/reauthorize_session()/complete_quick_connect_reauthorization()/remove_session(); app/src/main/kotlin/tv/jellybeam/player/PlaybackViewModel.kt + PlaybackActivity.kt; app/src/main/kotlin/tv/jellybeam/MainActivity.kt authorizationRecoveryCoordinator collector; object.rs reauthorization_* and remove_session_* tests -->
- Jellyfin Quick Connect lets you authorize the TV from an already signed-in
  Jellyfin client instead of typing a username and password with a remote. The
  short code is displayed on TV; its secret stays in memory, polling is
  lifecycle-cancellable, and approval commits through the same transactional
  mirror-before-account-switch path as password sign-in.
  <!-- verified: core/jellyfin-api/src/lib.rs quick_connect_* / authenticate_with_quick_connect; core/ffi/src/object.rs initiate_quick_connect()/poll_quick_connect()/complete_quick_connect(); app/src/main/kotlin/tv/jellybeam/ui/signin/SignInViewModel.kt -->
- Finds your Jellyfin server on the LAN at sign-in — no typing URLs with a
  remote. A broadcast discovery request answers with whatever's on the
  network within about 1.5 seconds; it's strictly an accelerator, never a
  gate, so a firewalled network or a Docker/Kubernetes deployment that
  doesn't forward the discovery port simply falls back to the always-visible
  manual URL field, and a server you've already saved shows up inert rather
  than inviting a duplicate sign-in.
  <!-- verified: core/jellyfin-api/src/discovery.rs discover_local_servers(); core/ffi/src/discovery.rs JellybeamCore::discover_servers()/mark_already_saved(); app/src/main/kotlin/tv/jellybeam/ui/signin/SignInViewModel.kt startDiscovery() -->
- Real, measured Compose performance work, not guesswork: Home's shelf
  list is deliberately an eager-composed column rather than a `LazyColumn`
  because the lazy version measured worse on real TV hardware (up-navigation
  p90 regressed from 65ms to 129ms on the reference target device); frame-metrics
  logging is built in to catch regressions.
  <!-- verified: app/src/main/kotlin/tv/jellybeam/ui/home/HomeScreen.kt comment above the root Column ("Deliberately an EAGER scrolling Column..."); app/src/main/kotlin/tv/jellybeam/perf/PerfLog.kt usage in MainActivity.kt startFrameMetrics -->
- Playback start has been through a dedicated latency audit: pooled
  keep-alive HTTP connections for the player's data source, untouched
  default buffering tuned for the common case, focus-dwell handshake
  preloading, and a measured fast-start path that invokes ExoPlayer before
  composing noncritical player chrome (53ms median from SELECT to ExoPlayer
  on the reference target device, down from 118–122ms). Instrumented phase and first-frame
  markers keep it honest going forward.
  <!-- verified on-device; docs/10-perf-logging.md Playback-start timeline; app/src/main/kotlin/tv/jellybeam/player/PlaybackActivity.kt installPlaybackUiAfterPlayerLoad(); PlaybackViewModel.kt concurrent settings/prepare startup; PlayerHolder.kt playback phase marks -->
- Ahead-of-time compiled out of the box: the APK embeds a Baseline
  Profile, so a sideloaded install runs ART-compiled code after its first
  background optimization pass (or immediately, when installed with the
  bundled `.dm`) instead of spending its first days interpreting and
  JIT-compiling the UI. Browse frame times on the reference target device
  measured roughly half of the JIT-cold numbers once compiled.
  <!-- verified: app/src/main/baseline-prof.txt; app/build.gradle.kts profileinstaller dependency; build.sh install; docs/10-perf-logging.md "Profile status" -->

**Respects your library**

- Server-configured names — libraries, servers, accounts — are always
  shown exactly as configured. Jellybeam never prettifies or renames what the
  server sent.
  <!-- verified: app/src/main/kotlin/tv/jellybeam/data/CoreGateway.kt "server-configured libraries, names shown verbatim (CLAUDE.md hard rule)"; app/src/main/kotlin/tv/jellybeam/ui/home/HomeViewModel.kt; app/src/main/kotlin/tv/jellybeam/ui/nav/NavDrawerHost.kt -->
- No analytics, no crash-reporting SDK, no telemetry of any kind — the
  app only ever talks to the internet for the Jellyfin server itself
  (`INTERNET`/`ACCESS_NETWORK_STATE` are its only network permissions).
  <!-- verified: gradle/libs.versions.toml and app/build.gradle.kts contain no analytics/crash-reporting dependency; app/src/main/AndroidManifest.xml declares only android.permission.INTERNET and ACCESS_NETWORK_STATE -->
- Audio and subtitle track choices are remembered per series, not per
  episode, so a show's dub/subtitle preference sticks across every episode
  without re-selecting it.
  <!-- verified: core/playback-policy/src/prefs.rs per-series lookup doc comments; core/ffi/src/track_prefs.rs "Load previously saved per-series track prefs" -->
- Trickplay scrubbing shows the server's own thumbnail tiles while you
  seek, and keeps showing them through an accelerating hold-to-seek: the
  only Jellyfin client we know of that does. The thumbnail is re-sampled
  at a readable pace whatever the speed, never blanks mid-hold, and sheets
  are fetched one ahead under a fixed budget, so it behaves the same over
  a VPN as on a LAN and stays cheap on a weak TV.
  <!-- verified: app/src/main/kotlin/tv/jellybeam/player/TrickplaySeekPreviewController.kt; core/playback-policy/src/trickplay.rs (glide_tunables, glide_sample, glide_want_list, should_abandon_sheet); app/src/main/kotlin/tv/jellybeam/player/TrickplayPreviewer.kt (one in flight, one queued, hold-last, region decode); app/src/main/kotlin/tv/jellybeam/player/GlideSeekController.kt refreshSurface; emulator against a Jellyfin 12 dev server -->
- The OSD keeps playback diagnostics and library knowledge distinct: live
  buffer/network/stream statistics remain in technical info, while a separate
  catalog panel fetches fresh plot, episode number, air date, watch history,
  available content/community/critic ratings, director/writer, and studio only when opened. It
  adds no work to the time-to-play path.
  <!-- verified: app/src/main/kotlin/tv/jellybeam/player/PlaybackScreen.kt LibraryInfoOverlayPanel; PlaybackViewModel.kt openLibraryInfoOverlay(); LibraryInfoFormat.kt -->
- Five segment types — intro, recap, preview, commercial, and
  outro/credits — each get their own independent Ask / Auto-skip / Off
  setting, not one blanket "skip intro" toggle.
  <!-- verified: app/src/main/kotlin/tv/jellybeam/ui/settings/SettingsViewModel.kt skipIntro/skipRecap/skipPreview/skipCommercial/skipOutro; app/src/main/kotlin/tv/jellybeam/ui/settings/SettingsSections.kt -->
- Autoplay's next-episode countdown is timed off the show's own credits
  segment, not a fixed offset from the end of the file. With credits set to
  Auto-skip the card comes forward by the autoplay delay, so it is seen in
  full and its countdown ends exactly where the credits start; the next
  episode begins there.
  <!-- verified: core/playback-policy/src/segments.rs next_episode_trigger_remaining_secs; app/src/main/kotlin/tv/jellybeam/player/PlaybackViewModel.kt evaluateNextUp() playableSecs -->
- Falling asleep mid-binge doesn't mark the rest of the season watched: after
  a configurable run of untouched autoplay (N episodes or N hours since the
  last button press) the countdown card gives way to a "Still watching?"
  "Keep Watching" prompt (Back stops) whose depleting countdown rule and
  "IN {n}" numeral count down the answer timeout — a key press resets both
  back to the full timeout. Stop reports the finished episode, never
  starts or marks the next one, and drops you on that next episode's detail
  page so the true resume point survives. Remote input resets the count by
  default, optionally ignored so the guard only counts autoplay
  transitions/elapsed time straight through seeks and pauses. The
  ask/countdown decision is a pure Rust state machine driven by two counter
  updates, nothing per tick; the on-screen countdown itself ticks per frame
  in Compose off a wall-clock deadline, not the Rust decision.
  <!-- verified: core/playback-policy/src/still_watching.rs InactivityState::decide; core/ffi/src/still_watching.rs note_player_input()/note_episode_finished(); app/src/main/kotlin/tv/jellybeam/player/PlaybackViewModel.kt evaluateNextUp()/stillWatchingStop(); app/src/main/kotlin/tv/jellybeam/player/StillWatchingCountdown.kt -->
- Outro/credits Auto-skip never races the "Still watching?"/next-up decision:
  it waits for that decision before seeking past the segment, a countdown
  that ran out at the credits hands over before the skip can seek, a seek
  that lands inside the credits advances with no card flash, and reaching
  the file's true end while the decision is still pending waits for it
  instead of exiting the player early.
  <!-- verified: app/src/main/kotlin/tv/jellybeam/player/PlaybackViewModel.kt evaluateAutoSkip()/handlePlaybackEnded()/evaluateNextUp(); app/src/main/kotlin/tv/jellybeam/player/NextUpCountdown.kt countdownOutcome() -->

**Built to survive real networks and real libraries**

- A multi-second Wi-Fi radio stall doesn't interrupt playback: a 60-second
  / 64MB rebuffer window plus up to 90 seconds of quiet network-aware
  retries absorbs the stall before any error ever reaches the screen.
  <!-- verified: app/src/main/kotlin/tv/jellybeam/player/LoadRetryPolicy.kt GIVE_UP_BUDGET_MS = 90_000L, RECOVERY_RESET_AFTER_MS = 60_000L; app/src/main/kotlin/tv/jellybeam/player/PlayerHolder.kt maxBufferMs = 60_000 -->
- Watch progress is checkpointed to local storage roughly every 10 seconds
  during playback, so a force-killed app (low memory, a crash, a yanked
  power cord) never loses more than a few seconds of resume position.
  <!-- verified: core/ffi/src/object.rs LOCAL_PROGRESS_CHECKPOINT_INTERVAL = Duration::from_secs(10); "The mirror checkpoint is what survives an Android force-stop" -->
- Library-list replacement atomically cleans up revoked access; item
  enumeration removes absent membership only after a complete response.
  Failed, repeated, or inconsistent pages preserve prior membership and
  do not advance the delta cursor. Sync also correctly
  resolves servers with duplicate Season rows, and converges cleanly when
  the server swaps a virtual (unaired) episode's id for the real one once
  it airs.
  <!-- verified: core/media-cache/src/writer.rs replace_views_removes_absent_view_and_its_scoped_items_atomically test; core/media-cache/src/query.rs duplicate-Season resilience comments; core/media-cache/src/sync.rs reconcile_converges_a_virtual_to_real_episode_id_swap -->
- MediaSession is published while playing, so the TV's system UI, the
  global play/pause remote button, and Assistant can all see and control
  what's on screen.
  <!-- verified: app/src/main/kotlin/tv/jellybeam/player/MediaSessionHolder.kt; gradle/libs.versions.toml androidx-media3-session comment -->

## In progress / known gaps

- None currently tracked — see Planned.

## Planned

- External subtitle sidecar loading (.srt/.ass files next to the media —
  today only embedded tracks play; needs SubtitleConfiguration wiring in
  the player load path plus the server's delivery URL).
- Trailer button on detail pages (needs a RemoteTrailers/LocalTrailers
  FFI path; the action row has its slot at position three).
- Byte-level preload (buffering actual media bytes ahead of Play, not just
  the playback handshake -- see Shipped/Playback's focus-dwell preload for
  the metadata-only tier already in place).
- "Included in" row on Detail from the 12.0-only `GET /Items/{id}/Collections`
  endpoint, behind `server_at_least(12, 0)` (the route is a 404 on 10.11;
  the Add-to-collection panel keeps deriving membership from the mirror's
  per-collection member lists on older servers and as the fallback).
- `VideoRotation` device-profile condition behind the same gate (an unknown
  condition value fails `PlaybackInfo` on 10.11), only after a platform
  check with a rotated phone recording: Media3 applies rotation metadata on
  hardware decoders, so the TV may need no condition at all. Features whose
  new fields are simply absent on older servers (original-language audio
  preference, episode version picker) need no gate.
- Header-borne token for the image, trickplay and stream fetchers
  (deferred): today the token rides those URLs as `ApiKey`; moving it to an
  `Authorization` header keeps it out of logs and caches but needs the token
  in Kotlin, a host-scoped interceptor shared by Coil and the player, and a
  device pass. Held until the debug log exists and performance work is
  quieter.

## Explicitly not planned

Transcoding-by-default, Sparkle-style updates (sideload
distribution only), About-window physics.
