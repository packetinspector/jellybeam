# 18 — Playback quality (opt-in transcoding)

Spec of record for the Playback › "Quality" setting: Direct Play (default),
Auto, and fixed bitrate caps. **Auto**'s semantics are deliberately not a
link-speed measurement. The rule:
*Auto only transcodes when this TV's player
actually cannot play the file — never because the server or anything else
predicted it couldn't.*

## 1. Product behaviour

Settings › Playback › **Quality**, a chip row, first row of the section:

| Chip | Stored | Behaviour |
|---|---|---|
| Direct Play (default) | `DirectPlay` | Never transcodes. Exactly the pre-feature behaviour: the server's `SupportsDirectPlay` verdict is honoured, and a "would transcode" verdict is refused up front with a message that quotes the server's reasons and points at this setting. |
| Auto | `Auto` | Direct Play whenever this TV can decode the file; a transcode only when it cannot. "Cannot" is decided by local evidence, never by the server's opinion alone (§1.1). |
| 20 Mbps · 8 Mbps · 3 Mbps | `Cap { max_bps }` | **Every file transcodes**, from the first frame, at that bitrate (the server is asked with `MaxStreamingBitrate` and Direct Play/DirectStream disabled). |

### 1.1 Auto's evidence

Corrections after the first on-device pass: a bitrate preset means "always
transcode"; Direct Play must refuse as it always did; and Auto's first cut
("attempt everything, fall back on a player error") missed a file that
plays *badly* without ever erroring (a DivX/MPEG-4 ASP AVI decoded in
software) while it correctly played an HDR10 HEVC whose level the server
distrusts. The evidence Auto uses, in order:

1. **The server agrees Direct Play** → Direct Play.
2. **The server says transcode and "Tolerate mislabeled levels" is off**
   → the device profile carried honest per-profile level ceilings, so the
   verdict is the TV's own declared limits talking. Transcode up front.
3. **The server says transcode and the file's video codec is not one this
   TV's decoder probe advertises** (`android_direct_play_video_codecs`,
   the probed-and-baselined list the profile itself is built from) → the
   verdict is corroborated by local knowledge. Transcode up front, reason
   "no `<codec>` decoder on this TV".
4. **Otherwise attempt Direct Play** (the static stream; the verdict is
   kept as `server_verdict` for the error text only). Fall back to a
   transcode from the same position, once per item, on any of three local
   signals from Media3:
   - a `PlaybackException` that `ReconnectPolicy` does not treat as a
     network blip;
   - a `Tracks` snapshot where the video (or audio) type is present but
     every track of that type is `FORMAT_UNSUPPORTED_TYPE` /
     `FORMAT_UNSUPPORTED_SUBTYPE` / `FORMAT_UNSUPPORTED_DRM`
     (`FORMAT_EXCEEDS_CAPABILITIES` is not a trigger — that is the
     mislabeled-level case the tolerate setting handles);
   - the video decoder Media3 initialised is software-only
     (`MediaCodecInfo.isSoftwareOnly`, name heuristics below API 29):
     a TV SoC decoding in software is "cannot really play it".

A transcode that then fails is a plain fatal error. The setting is global,
not per server.

## 2. Rust core

`Settings.playback_quality: PlaybackQuality`:

```rust
#[derive(uniffi::Enum, serde::Serialize, serde::Deserialize, Default, ...)]
#[serde(tag = "mode", rename_all = "snake_case")]
pub enum PlaybackQuality {
    #[default]
    DirectPlay,
    Auto,
    Cap { max_bps: u32 },
}
```

`#[serde(default)]`; tolerant-load and round-trip tests like the other
fields. Presets are a Kotlin concern (the chip row), the core accepts any
`max_bps`.

`PlaybackPlan` gains:

- `play_method: PlayMethodFfi` — `DirectPlay` | `Transcode`.
- `transcode_reason: Option<String>` — for a Transcode plan: the local
  failure text (Auto fallback), "no `<codec>` decoder on this TV" or the
  server's reasons (Auto, up front), or "quality cap N Mbps" (Cap).
- `server_verdict: Option<String>` — for a DirectPlay plan the server
  wanted to transcode: its synthesized reasons (`synthesize_reasons`),
  joined with `"; "`. `None` when the server agreed.
- `transcode_fallback_allowed: bool` — `playback_quality != DirectPlay`,
  so Kotlin needs no second settings read to know whether the fallback
  path is open.

`jellyfin-core`:

- `PlaybackDecision::Transcode` additionally carries `direct_url` (the
  static-stream URL built the same way the codec-blind branch builds it),
  so the ffi layer can attempt Direct Play against a source the server
  routed to transcoding without rebuilding URLs itself.
- `JellyfinClient::get_playback_info` takes a `PlaybackInfoOptions`
  (`force_transcode: bool` → `EnableDirectPlay: false`,
  `EnableDirectStream: false` on the `PlaybackInfoDto`). Everything else
  unchanged.
- `ReportContext` / `PlaybackReport` carry `play_method`; the hand-written
  `StartProgressBody` sends `"PlayMethod": "DirectPlay" | "Transcode"`.
  The Stopped report already carries `PlaySessionId`, which is what makes
  the server kill the transcode job — no separate
  `DELETE /Videos/ActiveEncodings` is needed.
- `android_tv_profile` is fed `max_streaming_bitrate = Some(max_bps)` in
  Cap mode (the existing `AndroidTvCaps::max_streaming_bitrate` plumbing
  and its `VideoBitrate` codec condition), `None` otherwise.

`JellybeamCore`:

- `prepare_playback` resolves the server decision through one pure,
  unit-tested function `resolve_plan(...)` implementing §1/§1.1:
  - mode DirectPlay: `DirectPlay` decision → plan; `Transcode` decision →
    `Err(CoreError::WouldTranscode { reasons })` (pre-feature behaviour).
  - mode Cap: negotiation is made with `force_transcode: true` and the
    profile's `max_streaming_bitrate = Some(max_bps)`; the result is
    always a Transcode plan (`hls_url`, or a codec-blind source's own
    `TranscodingUrl`), reason "quality cap N Mbps".
  - mode Auto: `DirectPlay` decision → plan with `server_verdict: None`;
    `Transcode` decision → Transcode plan up front when
    `tolerate_mislabeled_levels` is off (reason: the server's reasons) or
    when the source's video codec is not in
    `android_direct_play_video_codecs(caps)` (reason "no `<codec>` decoder
    on this TV"); otherwise an attempt-anyway DirectPlay plan on
    `direct_url` with `server_verdict: Some(reasons)`.
- `prepare_transcode_fallback(item_id, position_ticks, reason) ->
  Result<PlaybackPlan, CoreError>`: refuses with
  `CoreError::WouldTranscode { reasons: reason }` when the mode is
  DirectPlay (the variant keeps its name and its Kotlin message mapping;
  its meaning is now "the setting forbids the transcode this would need").
  Otherwise: takes over the active `ReportingSession`, which keeps
  reporting (and its stream keeps playing) until the fallback's claim
  resolves (§2.1), negotiates again with
  `force_transcode: true` and `StartTimeTicks = position_ticks`, requires
  a `Transcode` decision (else `NoPlayableSource`), starts a fresh
  `ReportingSession` with `play_method: Transcode`, returns a Transcode
  plan whose `start_position_ticks == position_ticks`.
  **Staleness guard**: the call carries the plan's
  `play_session_id` and only takes the active session if it is that one,
  then claims ownership as in §2.1 with that id as the session it retires.
  **Never the same stream back**: the fallback names the current
  `MediaSourceId` and, from the Kotlin-classified `FailedTrackFfi` (renderer
  error's format type, the unplayable track type, or a software video
  decoder), sends `AllowVideoStreamCopy: false` (video or unattributed) or
  `AllowAudioStreamCopy: false` (audio), so the server re-encodes the type
  that failed. The transcoding profile's video targets are H.264 plus only
  the codecs this device decodes: a listed codec is copied, so an
  undecodable HEVC listed there came straight back (dev-server evidence:
  `-codec:v:0 copy` before, `libx264` after).
- `preload_playback` caches DirectPlay plans only (an attempt-anyway plan
  included — its URL is position-independent); a Transcode plan is never
  cached (its HLS URL embeds the start position), and preload is a no-op
  in Cap mode.
- `prepare_playback` joins a same-item in-flight preload instead of
  aborting it, reusing the negotiation already under way; a preload for
  any other item is aborted as before.

### 2.1 Playback ownership

Stop, replacement, fallback and account change are ordinary events that can
overlap a negotiation; these rules give every overlap one outcome.

- **Requests.** Kotlin mints a `PlaybackRequest { seq, account_epoch }` on
  the main thread before dispatch; `prepare_playback` and
  `prepare_transcode_fallback` take it. The epoch names the account the
  request plays on (see Account boundary). `seq` is process-wide and
  increasing, so native admission follows viewer order, not IO arrival.
- **Admission** (under the state lock, after local preconditions): a
  request from an epoch that is neither the current one nor the parked
  playback's is refused with `CoreError::AccountChanged`; a `seq` not above the highest admitted is
  refused with `StalePlaybackSession`. Otherwise it becomes the one claim.
  A fallback's claim also names the session it stopped (`retires`).
- **Install** happens only while the claim is still the owner, and the
  `ReportingSession` starts only then, so a refused negotiation never sends
  Start. The sidecars install with it. A refused install returns
  `StalePlaybackSession`. A failed negotiation gives up its claim if it
  still holds it and is undone (the playing account reverts), so it
  neither owns playback nor gets parked, and never revives older work.
- **Stop / abandon** take the `play_session_id` they are for. If it names the
  active session, that session (and its sidecars) is retired; a newer
  pending claim is untouched. If it names the session a pending fallback
  retired, that fallback's claim is revoked and the retired session ends:
  a stop at the stop's position (where the viewer actually left), an
  abandon at the fallback's position. If it names the session the installed
  fallback replaced (sent before the caller learned the new id), it acts on
  the installed session. Anything else is a debug-logged no-op. Position
  and pause reports name their session too, so one queued behind a stop, or
  sent by the outgoing session while a fallback negotiates, is ignored.
- **Retired session.** A fallback's taken-over session ends exactly once,
  with its final Stopped and mirror writeback. Its stream keeps playing and
  its own position reports keep arriving until then. It ends at the
  fallback's position when its own replacement installs (where that
  replacement takes over), and otherwise where the viewer is: its latest
  report when something newer installs, the negotiation fails, it is
  abandoned or the account changes, and the stop's position when a stop
  revokes the claim.
- **Account boundary.** Every sign-in, reauthentication, Quick Connect,
  restore, switch, sign-out and removal of the active account goes through
  one reset, and the epoch advances. A live playback (installed, retiring
  or negotiating) outlives it: its account (client and mirror) is parked
  with the epoch its requests carry, so the player keeps reporting, falls
  back, renegotiates, fetches sidecars, reads trickplay, segments and OSD
  detail and plays next-up on the server it started on, while browsing
  moves to the new account. A parked account resolves to the installed
  client whenever it is the same saved account again (a re-sign-in's fresh
  token, a switch back). The newest admitted request that has not failed
  names the playing account: a switch parks the account the newest playback is on, replacing
  an older parked one (a playback started on the browsed account after the
  kept one ended), so its negotiation survives a further switch. Only
  leaving the account that plays ends it:
  signing out of it, or removing it (active or not), clears the claim and
  stops the installed session at its last reported position (it keeps its
  resume point, at most a second old), with its sidecars cleared. The
  parked account is released when a request on the current account
  installs or its account is left; not when its playback stops, since
  next-up stops before it prepares. Leaving it ends playback only while
  its playback is the newest admitted; otherwise it is just dropped, so a
  newer playback negotiating elsewhere is untouched. Every 401 names the
  account whose token was rejected (the HTTP client attaches its server and
  user where it sees the status), and `reauthorization_account` re-authorizes
  that account only while the app uses it -- browsed, or a playback runs or
  negotiates on it; a playback that ended (stopped, abandoned, failed) keeps
  its account for next-up only. The player's own 401 still re-authorizes its
  account after its teardown ends the playback, while that account is saved.
  A 401 from any other account, one switched away from or removed, prompts
  nobody, never the browsed account in its place; switching back re-checks
  that token. "Still watching?" Stop
  on a parked playback closes instead of opening a detail page the browsed
  account would resolve. A parked account resolves to the installed one field by
  field, so a switch back still has its mirror until Kotlin reopens it.
  Sync yields to playback on any account: every mirror the core opens
  shares one yield flag, re-derived from the playback state (a session
  installed or retiring) whenever the state lock is released, so no path
  that ends a playback can leave any account's sync paused. Every
  session's final writes (Stopped, mirror
  position, mark-played) go to the account it belongs to, never to the one
  that replaced it. Removing an account nothing plays on is not an account
  change, and neither is restoring the account already installed (a
  recreated Activity, a failed switch).
- Kotlin reads `parked_account_epoch()` then `account_epoch()` after every
  account call (`RealCoreGateway`, in a `NonCancellable` `finally`, so a
  cancelled caller still refreshes and reopens); a request owns playback
  while its epoch is either. `AccountChanged` is logged, never swallowed
  as stale.

Session threading rules shared by every path above:

- `stop_session_and_sync_mirror` spawns the final Stopped report on the
  runtime rather than blocking on it: `ReportingSession::stop` is
  delivered-or-queued (100 ms ack, then the flush queue), so nothing is
  lost and the next negotiation is not serialized behind the old report.
  It is sent exactly once: a Stopped already on the wire is never re-queued,
  since Jellyfin can take over a second to answer one and would record a
  second stop.
  The mirror write stays synchronous: it is the barrier a same-item
  resume reads through (docs/17 §6).
- The synchronous `fetch_item_dto` reads the mirror on the caller's thread
  and only `block_on`s the network fallback (no runtime hop on a
  mirror-hit Play); the preload worker's async variant moves the mirror
  read to `spawn_blocking`.

## 3. Kotlin

- **Request owner (§2.1).** `PlaybackViewModel` mints a request on the main thread before every
  prepare, replacement and fallback (`init`, `replaceItem` and transitions mint before they launch,
  so an initial prepare still negotiating is obsolete at once) and keeps only the newest as
  `currentRequest`. `init`, `replaceItem` and the post-restore re-mint take the current account's
  epoch; transitions, fallbacks and renegotiations keep the player's own (`accountEpoch`), which
  every account-bound read (trickplay, segments, OSD and library detail, episode neighbours,
  next-up art, server name, preload) also names. Stop, abandon and leaving the playing account
  retire it, before the exactly-once session guard. A returned plan loads only while its request is the newest and ownership is open
  (`planOutcome`); otherwise it is abandoned by its own `play_session_id`, and the player closes if
  the account changed under it. A failure reaches the viewer only from its owner
  (`failureOutcome`), which also decides re-authorization: the fallback call never routes a 401 to
  recovery through the gateway. A plan or failure that lands while an
  account call is in flight waits for it (`CoreGateway.awaitAccountCalls`) and
  is then judged against ownership: still open, it publishes; closed, the
  player closes. A live owner whose epoch no longer owns playback closes; the watch starts once
  the first request is minted after the launch restore
  (`awaitAccountRestored`), so a cold start never closes itself. A transcode
  negotiation in flight owns the session it retires: stop and abandon name
  that session (which also ends a fallback the core installed over it, so
  the landing's own disposal is skipped rather than racing that stop; a
  transition captures it before anything else runs), the old stream's
  errors are ignored until the landing reloads
  the player, and a second renegotiation waits for the landing to re-check
  the choice.
- `PlaybackViewModel.enrichmentGate` (`CompletableDeferred`, reset per session): the OSD detail,
  trickplay manifest/warm, and server-display-name fetches await it instead of firing right after
  `load()`, so they stop competing with the media loads for time-to-first-frame; it completes on
  `onRenderedFirstFrame` or a 1500ms fallback timer, whichever comes first. `getMediaSegments`
  (auto-skip can fire at 0s) and `episodeNeighbors` stay immediate.
- `PreloadOnDwell` (docs/13) now also fires from the Home hero region,
  library grid cells, and `PlaybackViewModel.showCountdownCard` (once per
  next-up item), alongside Home shelves and Detail. A fired item re-arms
  itself every `PRELOAD_REARM_MILLIS` (mirrors §2's Rust TTL) while it
  stays focused, cancelled on unfocus, so a long dwell never plays against
  a stale cache entry.
- Settings: `SettingsViewModel.selectPlaybackQuality(PlaybackQuality)`;
  `ChipFieldRow` "Quality" first in the Playback section, key
  `playback/quality`, chips Direct Play / Auto / 20 Mbps / 8 Mbps /
  3 Mbps (`PLAYBACK_QUALITY_PRESETS`), description: "Direct Play never
  transcodes and refuses files this TV can't play. Auto plays files
  directly and transcodes only the ones this TV's decoders can't handle.
  A bitrate preset transcodes everything at that rate."
- `PlayerHolder.load` sets `MimeTypes.APPLICATION_M3U8` on the
  `MediaItem` for a Transcode plan; `media3-exoplayer-hls` is added to the
  dependency catalog (same version ref as the other Media3 artifacts).
- `PlaybackViewModel`:
  - `PlaybackUiState.playMethod` / `transcodeReason` from the plan.
  - `LocalPlayability.unplayableReason(tracks: Tracks): String?` — pure,
    unit-tested: the §1 track rule.
  - `onTracksChanged` → if `unplayableReason != null` and the current plan
    is DirectPlay → `fallBackToTranscode(reason)`.
  - `PlayerHolder` forwards `AnalyticsListener.onVideoDecoderInitialized`'s
    decoder name; the cheap guards run first (DirectPlay plan, fallback
    allowed, none attempted yet) and only then
    `SoftwareDecoder.isSoftwareOnly(name)` → `fallBackToTranscode("Software
    video decoder …")`. That check never touches the platform: the set of
    software-only decoder names is collected by `DeviceCapsProbe` (which
    already enumerates `MediaCodecList` off the main thread at startup) and
    remembered process-wide; the name heuristic is the fallback.
  - The fallback mints its own request (§2.1) and runs the native call
    under `NonCancellable`; its result publishes only through the request
    owner (§3), and `StalePlaybackSession` for a live owner is ignored
    silently (a software-decoder fallback may still be playing).
  - `onPlayerError` non-recoverable branch → if the plan is DirectPlay
    and `transcodeFallbackAllowed` and no fallback happened yet →
    `fallBackToTranscode("Playback error: <errorCodeName>")`; else the
    existing fatal path, whose message for a DirectPlay plan with a
    `serverVerdict` becomes: `"This file can't be Direct Played on this
    TV (<errorCodeName>). Server: <verdict>. Set Quality to Auto in
    Settings › Playback to let the server transcode it."` The up-front
    Direct Play-mode refusal (`CoreException.WouldTranscode`) reads
    `"Direct Play isn't possible for this file on this TV: <reasons>. Set
    Quality to Auto in Settings › Playback to let the server transcode
    it."`
  - `fallBackToTranscode(reason)`: cancels any reconnect episode,
    snapshots the position, calls `gateway.prepareTranscodeFallback`,
    loads the new plan (same tolerate/decoder preferences), re-seeds
    `_positionTicks`, updates `playMethod`/`transcodeReason`, keeps every
    other session field (next-up, segments, trickplay). Failure → the
    fatal path with the core's message. `sessionEnded` stays false
    throughout (the core swapped reporting sessions itself).
  - `start()` runs the native prepare under `NonCancellable` (the JNI call
    cannot be cancelled anyway); its plan goes through the request owner
    (§3), so a plan whose owner was cleared meanwhile is abandoned by its own
    `playSessionId` instead of leaking. An owner with no session receiving
    `StalePlaybackSession` finishes quietly.
  - Every exit path snapshots `currentPlan.playSessionId` synchronously at
    the exit decision (`StopSnapshot`) before dispatching onto the
    process-lifetime report scope, because `currentPlan` may change
    underneath the dispatched work; the native stop is skipped when no plan
    ever landed.
- OSD (docs/12 §5 and §19): the Direct Play line gains its `TRANSCODE`
  variant — amber (`JellybeamTheme.Ambra`, new token `oklch(0.78 0.13 75)`
  ≈ `#E0B45C`) `TRANSCODE` in place of `DIRECT PLAY`, same codec summary.
  Stats sheet headline: `Transcoding · <transcode_reason>` instead of
  `Direct Play · no transcode`.

### 3.1 Track selection lifecycle

- **Automatic resolution is generation-guarded and cancellable.**
  `resolveTrackSelectionOnce`'s `gateway.resolveTracks` round trip is a real
  suspend call, so a session change or an explicit `chooseAudio`/
  `chooseSubtitle` pick can land while it's still pending. The launched job
  is held in `trackSelectionJob` and cancelled outright by `start`'s
  per-session reset, by `endSessionForStop`/`abandonPlaybackOnce` (same set
  of leftover-job cancels as the reconnect/buffering/library-info jobs), and
  by `chooseAudio`/`chooseSubtitle` before applying the manual decision.
  Belt and braces: the coroutine also re-checks a captured `sessionGeneration`
  snapshot and a `manualTrackChoiceMade` flag before applying, same
  "cancellation is cooperative, re-check on the other side too" shape
  `maybeFallBackToTranscode` already uses for the transcode-fallback result.
  A late automatic result can therefore never overwrite a newer session or a
  manual pick that beat it.
- **Every `load()` starts from a clean per-item track baseline.** `PlayerHolder`
  is a process-wide singleton with one `ExoPlayer`; without a reset, a
  previous item's applied decision (a per-series subtitle Off's
  `TRACK_TYPE_TEXT` disable, or a `TrackSelectionOverride` pointing at a
  group that no longer exists) would silently carry into an unrelated item.
  `load()` now resets `trackSelectionParameters` to a clean baseline —
  overrides cleared for both audio and text, text re-enabled — before this
  item's own decision (if any) is applied. The reset is the exact,
  independently-unit-tested `trackSelectionBaseline` function; nothing
  genuinely global (this app sets no preferred-language/forced-subtitle/
  viewport preference anywhere) is touched. This is what makes
  `SubtitleActionFfi.LEAVE` mean "this item's own default", never "whatever
  the previous item left selected".
- **A same-item fallback reload restores the choice.** The view model
  records the subtitle and audio choice as it applies one, manual or
  automatic (Off, the player's default, a sidecar, or an embedded track's
  language, title and forced flag); Media3's announced selection lags a pick,
  so it is never read back for this. Before the fallback's `load()` resets
  the baseline, that record is captured: an automatic decision still in
  flight counts as undecided, and the new load resolves afresh through Rust.
  Otherwise text is turned off at once (no track id needed) and an embedded
  choice is matched again on the first announcement carrying that kind (an
  empty or audio-only announcement keeps it pending): a unique language and
  forced match wins, an exact title breaks ties, and ambiguity is no match,
  never the first candidate, since tracks carry no role. No match leaves
  text off silently; audio keeps the player's default. A manual pick clears
  whatever is still pending. The test player applies decisions through the
  same `trackSelectionBaseline` and `withTrackDecision` as `PlayerHolder`.
- **The negotiation carries the subtitle choice, so nothing the viewer turned
  off is burned in.** No Media3 selection can remove pixels, so every transcode
  negotiation names the stream the recorded choice wants
  (`subtitleStreamIndexFor`): Off or a sidecar names none (`-1`), an embedded
  choice names its matched stream from `PlaybackPlan.embedded_subtitles`
  (same matching policy; ambiguous or missing names none), and an undecided
  or default choice leaves the server's default, which is what the Direct
  Play player showed. JF12 honours `SubtitleStreamIndex` only with
  `MediaSourceId` (dev-server evidence: an ASS-default file burned in for
  omitted, `-1` and `2` alike without it; with it, `-1` drops the stream and
  `2` burns it in). `PlaybackPlan.burned_subtitle_index` is read from the
  transcode URL and needs both `SubtitleMethod=Encode` and an index (a `-1`
  negotiation still says Encode, with no index). While transcoding, the
  picker also lists the plan's embedded streams no Media3 track carries
  (ASS and bitmaps reach a transcode only burned in), the burned one
  selected: picking another of them renegotiates with it burned in, and
  moving off a burned one (Off, a sidecar, a delivered track) renegotiates
  without it; picking the one already burned in is a no-op. A choice changed while a negotiation is
  in flight is renegotiated from the landed session before anything loads,
  as often as the choice keeps changing (a landing that contradicts it never
  loads: burned-in pixels can't be turned off), and every renegotiation
  resends the session's copy policy
  (§2), so a subtitle change never lets a failed stream be copied back.

### 3.2 External subtitles

The device profile advertises `External` delivery for SRT/WebVTT/TTML, so the
server hands back sidecar files as `MediaStream`s with `DeliveryUrl`. ASS/SSA
sidecars are never side-loaded: Media3's SSA parser expands every overlap
before it yields a cue, so no budget can bound it.
`PlaybackPlan::external_subtitles` lists the chosen source's text sidecars,
only streams with `IsExternal` (bitmap formats and off-server URLs are
dropped). An embedded track the server also offers as External is left in the
container: side-loading it makes the server extract it from the whole file,
which stalls the first frame and leaves the item unseekable until it lands.

Nothing is fetched at prepare time, so a sidecar can never delay the first
frame. `ExternalSubtitleFfi` is a descriptor (index, codec, language, title,
default, forced); the core keeps each authed `DeliveryUrl` for the installed
play session only (`SessionSidecars`, installed and cleared with the
`ReportingSession` itself), so no token reaches Kotlin, a stale session can't
fetch another's file, and a stopped one has nothing left to fetch.

Sidecars never enter the player. Each one is a picker row under a synthetic
`TrackInfo` id above every `TrackMapping` id (`ExternalSubtitles.idFor`), so it
joins `resolve_tracks` auto-selection and per-series memory like an embedded
track; `ExternalSubtitles.route` hands the player only the embedded half (text
off when a sidecar won). With subtitles left to the file's defaults and no
embedded track showing, Media3's own rule applies: a default-flagged sidecar,
else a forced one in the playing audio language (`eng` and `en` match).

Choosing a sidecar turns embedded text off and asks the core for the file
(`fetch_external_subtitle`: 10 s, 2 MiB, HTTP 2xx, UTF-8, refused off-server;
failures are logged by index and failure class, never the URL). Kotlin parses
it off the main thread with Media3's own parsers, outside the player, into
`SidecarCues` segments (at most 8 cues each, so heavy overlap stays linear),
cached for the session. Our own `SubtitleView`, a sibling of PlayerView's with
the same styling, is fed from the live position, sleeping until the next cue
change scaled by the playback rate (at most 120 ms, so a seek lands promptly).
The video is never re-prepared, so a pick or a switch never rebuffers. The row
reads `Loading…` until the file is in; a fetch or parse failure (or a file with
no cues) marks it `Unavailable`, returns subtitles to Off and shows a short
notice, and the video plays on. Sidecar indices belong to one media source: a
transcode fallback on the same source keeps the showing sidecar, one that moves
to another source drops every cached file and re-picks the same language and
title there. A fetch the old session refuses mid-swap is not the file failing:
the pick stays chosen and Loading, and is asked again under the new session
once it exists; a later pick or Off in between wins.
It never changes the play method, so Direct Play stays Direct Play.

## 4. On-device test cases

1. Default Direct Play: unchanged for every file the TV plays today; a
   file the server would transcode is refused with the reasons message.
2. Auto, an MPEG-4 ASP AVI: transcodes from the first frame, OSD line
   `TRANSCODE`, stats headline "no mpeg4 decoder on this TV".
3. Auto, an HDR10 HEVC whose level the server distrusts, tolerate on:
   Direct Play, no transcode.
4. Auto, same file, tolerate off: transcode from the first frame.
5. Auto, a file the TV decodes in software only: brief attempt, then the
   transcode from the same position.
6. Any bitrate preset: everything transcodes at that rate, including files
   well under it; the server dashboard shows the transcode; stop ends the
   ffmpeg job.
7. Next Up after a transcode negotiates fresh.
8. Resume position after a transcode session is the HLS position.

## 5. Seek serialization

Skips are cheap on their own: a target inside the buffered range is a
decoder flush, a target outside it is a reload, and on the LAN both land
within a few hundred milliseconds. What is not cheap is two of them
overlapping. On a test TV two D-pad skips 275ms apart produced
two pipeline resets and two decoder flushes back to back, and the player
then sat in BUFFERING for 90 seconds with 33 seconds of samples queued
until the next skip flushed it again.

Rule: **a seek never fires while the previous seek is still in flight.**

- A seek is in flight from `seekTo` until it lands: the first of
  `onRenderedFirstFrame` or the player reaching READY.
- A press that arrives while one is in flight is held. Only the latest
  held target is issued when the in-flight seek lands, and the next
  delta is computed from the held target so the middle press of a burst
  is never lost. The OSD position reads the held target at once.
- A landing timeout of 2.5 seconds fires the held target anyway; that
  re-seek is exactly the manual recovery the viewer performed. With
  nothing held the timeout clears the in-flight state and logs a stall.
- Stop, retry after an error, and a media item change reset the
  serializer. The retry path's own seek bypasses it.

No buffer sizes change and no seek is dropped: a six-press burst still
reaches the sum of the presses, through two seeks instead of six.

Implementation: `player/SeekSerializer.kt` (pure, unit-tested) driven by
`PlayerHolder.seekBy`, which is the one place every skip, glide commit and
absolute seek reaches the player.

Validation logging, tag `JellybeamSeek`, numbers only, always on:

```
issue target=<ms> from=<ms> edge=<ms> ahead=<ms> held=<bool>
hold  target=<ms> inflight=<ms> age=<ms>
land  via=firstFrame|ready after=<ms> pos=<ms> edge=<ms> pending=<ms|none>
stall inflight=<ms> after=<ms> pending=<ms|none>      (warning)
reset reason=stop|retry|transition
```

Read it with `adb logcat -s JellybeamSeek`. A healthy burst reads as one
`issue`, several `hold`s, one `land`, one `issue held=true`, one `land`.
Any `stall` line is the bug still present and names the seek that hung.

Validated on a test TV, Direct Play
mkv over the LAN, about 80 seconds of deliberate skipping in both
directions:

| | |
|---|---|
| presses | 48 (25 issued, 23 held) |
| held targets issued on landing | 13 |
| back-skips | 7 |
| out-of-buffer seeks (reload) | 8 |
| landings | 25, all via first frame |
| landing time, in-buffer | 408–581 ms typical, two at ~1.05 s |
| landing time, reload | 220–1209 ms |
| longest hold | 1.1 s |
| stalls | 0 |
| decoder output gaps over 1 s | 0 |

Before the change the same pattern of presses froze playback for 90 s
once in an evening; with it, no seek ever overlapped another and no
freeze occurred.

### 5.1 Audio clock guard

Serialized seeks still stalled: two skips 1.35 s apart on an HEVC/E-AC3
episode, both landed via first frame, and 49 ms after the second landing
the player sat in BUFFERING at 28.1 s with 47 s buffered until the next
skip. Read from logcat, AudioFlinger and the Media3 1.9.0 source:

- Every flush releases the passthrough AudioTrack and creates a new one
  (`DefaultAudioSink.flush`, b/7941810). On a test TV the new
  track can report the MS12 "continuous" HAL path's absolute stream
  counter as its own position within its first 50 ms: the first clamp
  seen on device read 44,739 s (12.4 hours) 39 ms after a landing. Media3
  caps the position at the frames written, which is why it looked like a
  2.5 s jump (25.6 -> 26.9 -> 28.1 s in 50 ms) in the original trace.
- `MediaCodecAudioRenderer.isReady()` is exactly `written > position`. The
  E-AC3 client buffer is 192000 bytes, 2.4 s at 640 kb/s; with 2.53 s
  written and a position at or past it, the renderer reports not-ready,
  ExoPlayer stops the renderers (the track is paused) and, with the buffer
  full, nothing can ever change. Pause/play toggles the same dead track;
  only another flush (a skip) creates a new one.

Rule: **an output's reported position can never lead the time it has
actually spent playing.** A lead of more than 100 ms is a lie, and a lie is
replaced by `play time - latency`, where latency is `play time - position`
as measured on the last healthy output after its first second (0 until one
has been measured); nothing ever goes backwards. `player/AudioClockGuard.kt`
applies it in a `ForwardingAudioOutput` installed through
`DefaultAudioSink.Builder.setAudioOutputProvider`, the extension point
Media3 documents for wrapping the AudioTrack output. Healthy tracks always
lag play time by their latency, so the rule never touches them; a lying
track is reported where the audio really is, the renderer stays ready, the
video clock never sees a 2.5 s jump (so no drop-to-keyframe codec flush
either), and the seek path is untouched. If the box's offset never
corrects, the residual error is the difference between that track's
latency and the last measured one, not a constant lead. Logging, tag
`JellybeamSeek`, numbers only: `clock clamp reported=<ms> bound=<ms>` on the
first clamp of an output and `clock clamps=<n> maxExcess=<ms>` on release.

Verified on a test TV: a
long skip-burst session with no fault; one lying track caught (10 clamps
in the 53 ms before the next skip replaced it), playback continued; 35
landings, median 1.0 s, fastest 112 ms, the same as before the guard.

### 5.2 Resume seek lands on the previous keyframe

Rule: **a resume's saved position lands on the keyframe at or before it, never mid-GOP.**
`setMediaItem(item, startPositionMs)` applies the initial position exactly, so the video renderer
decodes and discards every frame from the last keyframe up to it -- up to half a GOP of wasted
decode before the first visible frame. `PlayerHolder.load` sets `SeekParameters.PREVIOUS_SYNC`
before `prepare()`, then issues one `seekTo(startPositionMs)` on the first `onTracksChanged` with
a non-empty `Tracks` (the period is prepared; Media3's `SeekMap` biases the landing to the
preceding sync sample, an in-buffer seek). Normal `SeekParameters` are restored on the first
landing signal (`onRenderedFirstFrame`/`STATE_READY`, whichever first) so a later user skip keeps
its usual exact-seek semantics. The resume target takes `SeekSerializer`'s in-flight slot at
the media-item transition, before playback input can be accepted, and the player seek itself is
issued once tracks are known: a D-pad skip pressed while the player is still preparing is held
behind it and replayed on top after it lands (§5), never overwritten by a resume seek that
arrives later. Both steps are guarded by `PlayerHolder`'s own load generation, so a listener
callback left over from a replaced item can never fire them (`ResumeSeekOrderingTest`). A Transcode (HLS) plan is untouched -- its
segment boundaries already govern this. `player/PlayerHolder.kt`'s `ResumeSeekGate`, unit-tested.
