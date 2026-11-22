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
   TV's decoder probe advertises** (a `VideoCodec` miss from
   `profile_miss` against the profile itself, §6) → the
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
    when `profile_miss` names the source's video codec (§6; reason "no `<codec>` decoder
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
  Full signature: `prepare_transcode_fallback(item_id, position_ticks,
  reason, play_session_id, failed, subtitle_stream_index,
  audio_stream_index, request)`; both stream indexes are sent beside the
  `MediaSourceId` (`None` leaves the server's default, §3.1).
- `preload_playback` caches a DirectPlay plan whole (an attempt-anyway plan
  included — its URL is position-independent), and preload is a no-op in
  Cap mode. Of an Auto Transcode answer it caches only the chosen source,
  never its URL or session (the HLS URL embeds the start position): Play
  skips its first `PlaybackInfo` and asks only the second (§3.2), so a
  dwelled-on transcode costs the server one call fewer.
- `prepare_playback` joins a same-item in-flight preload instead of
  aborting it, reusing the negotiation already under way; a preload for
  any other item is aborted and Play negotiates at once, never waiting on it.
  A repeated dwell trigger renegotiates a cache at least half its 30 s TTL
  old (the old entry serves Play until the refresh publishes); Play still
  consumes a cache until the full TTL.

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
- **Renegotiation installs on commit.** A renegotiation (a track pick on a
  transcode, a chained change) prepares its new session beside the playing
  one and installs it only when Kotlin commits it (`commit_transcode`),
  which retires the old session where the new one takes over. A failed
  renegotiation leaves the playing session reporting and its stream
  playing; the player shows a short notice. Only a failed fallback (the old
  stream is already dead) or a player error during one ends playback. A
  prepared session dropped on any claim-clearing path (stop, replacement,
  account change) never starts.
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
  restore, switch and removal of the active account goes through
  one reset, and the epoch advances (removing the active account also
  installs the next saved one in the same call, so the app sees one change; any result that started
  under an older epoch, a mirror open included, writes nothing). A live playback (installed, retiring
  or negotiating) outlives it: its account (client and mirror) is parked
  with the epoch its requests carry, so the player keeps reporting, falls
  back, renegotiates, fetches sidecars, reads trickplay, segments and OSD
  detail and plays next-up on the server it started on, while browsing
  moves to the new account. A parked account resolves to the installed
  client whenever it is the same saved account again (a re-sign-in's fresh
  token, a switch back). The newest playback still negotiating or streaming
  names the playing account (a stopped one names none, so a switch after a
  stop parks nothing): a switch parks the account the newest playback is on, replacing
  an older parked one (a playback started on the browsed account after the
  kept one ended), so its negotiation survives a further switch. Only
  leaving the account that plays ends it:
  removing it (active or not) clears the claim and
  stops the installed session at its last reported position (it keeps its
  resume point, at most a second old), with its sidecars cleared. The
  parked account is released when a request on the current account
  installs or its account is left; not when its playback stops, since
  next-up stops before it prepares. Leaving it ends playback only while
  its playback is the newest one; otherwise it is just dropped, so a
  newer playback negotiating elsewhere is untouched. Every 401 names the
  account whose token was rejected (the HTTP client attaches its server and
  user where it sees the status), and `reauthorization_account` re-authorizes
  that account only while the app uses it -- browsed, or a playback runs or
  negotiates on it; a playback that ended (stopped, abandoned, failed) keeps
  its account for next-up only. The player's own 401 still re-authorizes its
  account after its teardown ends the playback, while that account is saved.
  A 401 from any other account, one switched away from or removed, prompts
  nobody, never the browsed account in its place; switching back re-checks
  that token. A rejected live-events socket asks the same way, once per
  bus and only while its bus is installed. "Still watching?" Stop
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
- Kotlin reads `account_epochs()` (both under one lock) after every
  account call (`RealCoreGateway`, in a `NonCancellable` `finally`, so a
  cancelled caller still publishes); a request owns playback while its
  epoch is either. `AccountChanged` is logged, never swallowed
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
  recovery through the gateway. A plan or failure is judged against the
  core's ownership as it is now (`CoreGateway.playbackOwnershipOpenNow`),
  even while an account call is in flight: still open, it publishes; closed,
  the player closes. A live owner whose epoch no longer owns playback closes; the watch starts once
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
  a stale cache entry; it stops after `PRELOAD_MAX_FIRES` (~2.5 min), so an
  idle screen stops asking the server.
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
    fatal path with the core's message. The owner stays
    `SessionOwner.Playing` throughout (the core swapped reporting sessions itself).
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

- **One reducer owns the track choice.** `TrackChoice` (`player/TrackChoice.kt`)
  holds the current audio and subtitle choice; `PlaybackViewModel.sendTrack`
  is its single writer, and effects (apply, negotiate, commit, notice) run
  from its result. A manual pick, a landing, a fallback reload and the
  start decision are all events to it; none writes the choice elsewhere.
- **A transcode's start choice is final.** The player never re-runs the
  policy on a transcode: the core decides at negotiation and the plan
  carries the outcome. Start rule: the core names the policy's subtitle
  unless the URL already names that same index; Off or a sidecar names
  `-1` only when the first URL burns a subtitle or names one; audio is
  named when it differs from the delivered stream; `MediaSourceId` is
  always sent (JF12 honours `SubtitleStreamIndex` and `AudioStreamIndex`
  only with it). `PlaybackPlan.burned_subtitle_index` is read from the
  transcode URL (`SubtitleMethod=Encode` plus an index);
  `delivered_audio_index` is the negotiated audio stream, else the
  source's default, `None` on Direct Play. A fallback before any choice
  asks the core for the policy. If a landing burns a stream the choice
  didn't ask for, it is logged once (`playback.burn_ignored`, docs/21) and
  never retried.
- **The plan carries the track rows.** `PlaybackPlan.track_rows` is the one
  canonical row list for a transcode (streams, titles verbatim, language,
  codec, default/forced/SDH, delivery: rendition, burned, needs
  negotiation, sidecar, delivered or source audio, selected); empty on
  Direct Play. The picker shows it, with Media3's rendition ids overlaid by
  `DisplayTitle` (the manifest's NAME), only where the label names exactly
  one rendition and one Media3 track, or, failing that, as its language's
  only rendition. Every announcement re-resolves a shown text through the
  same map (switching or turning it off, never negotiating). Once Media3 announces the loaded stream, a rendition it lacks
  or can't match needs a negotiation. A rendition picked before the
  announcement waits for it: shown if announced, else negotiated (refused
  in a group). A reloaded plan's text the announcement lacks stays off,
  the choice kept.
- **A pick negotiates only when it must.** Moving off a burned stream, or
  picking a stream the manifest doesn't carry (ASS and bitmaps reach a
  transcode only burned in; some text isn't in the manifest), needs a
  negotiation; every other pick is a Media3 selection. Picking the stream
  already burned in is a no-op. At most one negotiation per user action; a
  choice changed while one is in flight chains one more from the landed
  session. In a watch-together group none of these picks is allowed
  (docs/28 §1). Every renegotiation resends the session's copy policy (§2).
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
- **Text subtitles in a transcode are drawn by the player, never burned in.**
  The profile lists `Hls` ahead of `External` for SRT and WebVTT, since the
  server takes the first matching profile and reads `Hls` only in a
  transcode, so Direct Play's answers are unchanged. Naming such a stream
  (embedded or a sidecar file) makes the server convert it to a WebVTT
  rendition in the HLS manifest (`SubtitleMethod=Hls`); the manifest then
  carries every subtitle stream, ASS and bitmap copies included, or none
  when no text stream is named (JF 10.11 and 12 alike).
  `PlaybackPlan.embedded_subtitles` therefore holds the embedded streams
  plus the sidecars the server delivers by `Hls`, each with its
  `DeliveryMethod` and `DisplayTitle`. Each rendition is labelled with its
  stream's `DisplayTitle`, so the plan's track rows give a delivered
  rendition its stream's title and flags (identities then match Direct
  Play's for reapply, memory and negotiation) and drop renditions of
  streams the server burns in instead, so an ASS copy is never offered as
  plain text. The server pins every cue to the bottom line (Media3 reads
  it as line -1), which would bypass the viewer's vertical position and
  the lift over the OSD, so `unpinServerLine` leaves a bottom-line WebVTT
  cue unpositioned; a cue placed elsewhere keeps its line. Switching
  between delivered text is a Media3 selection; text the manifest lacks
  needs one renegotiation. The first frame never waits on the server
  extracting the stream (seconds on a large file): Media3 leaves text out
  of the buffered position beside audio and video, and its text renderer
  is always ready, so only the first cues arrive late. TTML keeps
  `External`; no server here has a TTML stream to verify a rendition.

### 3.2 External subtitles

The device profile advertises `External` delivery for SRT/WebVTT/TTML, and for
ASS/SSA too whenever the server may answer Direct Play, so the server hands
back sidecar files as `MediaStream`s with `DeliveryUrl` (in a transcode an
SRT or WebVTT sidecar is delivered by `Hls` instead, §3.1, so this fetch path
serves Direct Play and TTML only); without it an ASS
sidecar makes the server refuse Direct Play (Direct Play mode) or transcode to
burn it in (Auto), though the player draws it itself. Offered ASS sidecars, a
transcode would hand out even an embedded ASS track as a sidecar instead of
burning it in (§3.1), so a forced transcode (Cap, the fallback) never offers
them, and an Auto answer that transcodes anyway is asked for again without
them (one extra round trip, only when a transcode was unavoidable).
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
cached for the session. An ASS/SSA file is parsed a `Dialogue` line at a time
(SsaParser given the file's header and events `Format` line, as Matroska hands
it over), since its whole-file parse expands every overlap first.

With full styling on, an ASS/SSA sidecar goes to the styled overlay instead:
`load_ass_sidecar` fetches it (10 s, 16 MiB, the overlay's per-track input
cap) straight into the Rust engine, so the file never enters the JVM heap,
and returns once the render thread has parsed it whole with substation's
script reader under the same input limits as an embedded track. Nothing
prunes that track, since nothing would deliver its lines again; the item
holds one script at a time and frees it when it ends, and a script fetched
for an item that has since ended is dropped (`AssOverlay.item`). The player
polls the position every 40 ms and sends it as `AssTextRenderer` does for an
embedded track, and it counts as a styled track showing, so the MKV's attached fonts
load for it. Leaving a track deselects only that track's key, so a renderer
disabled late can't blank a sidecar chosen meanwhile. The server serves
sidecars as UTF-8 (it converts UTF-16; a UTF-8 byte-order mark is skipped). Our own `SubtitleView`, a sibling of PlayerView's with
the same styling, is fed from the live position, sleeping until the next cue
change scaled by the playback rate (at most 120 ms, so a seek lands promptly).
Every subtitle path (stock text, plain and styled ASS, both sidecar kinds) draws at `subtitleClockUs`,
the media position minus the viewer's subtitle delay (docs/12 §16). The video is never re-prepared, so a pick or a switch never rebuffers. The row
reads `Loading…` until the file is in; a fetch or parse failure (or a file with
no cues) marks it `Unavailable`, returns subtitles to Off and shows a short
notice, and the video plays on. Sidecar indices belong to one media source.
A committed renegotiation reloads the player, which clears the overlay, so a
showing sidecar is asked for again under the new session: cached cues show at
once, a styled script loads again. A plan on another source drops every cached
file and re-picks the same language and title there. While a renegotiation
swaps sessions, a sidecar is held, not shown, until the new plan commits: a
fetch the old session refuses (not the file failing), and a file loaded over a
burned subtitle, which is never drawn over the burn. If the renegotiation
fails, a held file shows only when the plan on screen burns nothing; a later
pick or Off in between wins.
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
  On a Transcode plan the timeout is 20 seconds (§7): a seek past the
  server's encode lands only after the server restarts ffmpeg there, so an
  early re-issue would restart it again. The timeout is an upper bound; a
  seek inside what the encode has produced still lands at once.
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

**Root cause, fixed at the source:** Media3 1.11.1 counted any decrease of
the AudioTrack's playback head as a 32-bit wrap. While the released track
and its replacement share one direct output during a flush, the head can
read lower, so the position jumped 2^32 frames ahead and Media3 then
reported the frames written: the 44,739 s reading below, and the stepped
position that dropped video frames on a later item. Upstream 4c95cd9
treats a decrease under 2^31 frames as a reset; the app ships it
backported (docs/06 "Patched Media3"). The guard below is now observe-only:
it returns the raw position and logs `clock would-clamp` where it would
have acted. On a test TV with E-AC3 passthrough the patched build showed
only brief `start` faults on a fresh track after a seek (over within a few
reads), never a latch, and no stall; the guard is deleted once real use
shows the same. The output provider also logs, only when they change,
`audio support mime=<encoded> level=<n> offload=<0|1>` (whether Media3 may
send that format as passthrough) and `audio out encoding=<n> offload=<0|1>
rate=<Hz>`, so a TV that stops offering passthrough mid-session is visible.

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
actually spent playing, unless its own clock runs a little fast.** A position
is a lie only when it leads play time by more than 100 ms **and** one rule
fires; each rule is a fact about what no real audio clock does:

| Rule | Fires when | Why |
|---|---|---|
| `start` | first sample, or inside the first 2 s of play | no clock gains 100 ms in 2 s |
| `jump` | one step gains more than 250 ms on play time | drift is smooth |
| `ceiling` | the lead grows faster than 1000 ppm (plus 5 ms of sampling jitter) | audio crystals stay within a few hundred ppm |

A lead of 100 ms or less, or a catch-up that stays behind play time, is never
touched. A smooth drift below the ceiling is a real clock running fast and is
**followed**: the first version bounded the position by wall time for the
whole track, so a healthy output 50-300 ppm fast was clamped after 566-3400 s
and the picture then followed wall time while the sound ran ahead, 0.3-2 s over
two hours (#32; JVM trace test `smooth drift`).

A lie is rebased: the guard remembers `raw - (play time - latency)` at the
fault and reports the raw position minus that offset, so the track keeps its
own rate, bounded below by `play time - latency` and above by
`play time + slack`; the offset ends when the raw position is truthful again.
`latency` is `play time - position` as measured on the last healthy output
between 1 s and 10 s of play (before its `stop()`, and before drift can have
built up; 0 until one has been measured), so drift never carries into the next
item. Nothing ever goes backwards. `player/AudioClockGuard.kt` applies it in a
`ForwardingAudioOutput` installed through `DefaultAudioSink.Builder.setAudioOutputProvider`,
the extension point Media3 documents for wrapping the AudioTrack output. The
renderer stays ready, the video clock never sees a 2.5 s jump (so no
drop-to-keyframe codec flush either), and the seek path is untouched.
Logging, tag `JellybeamSeek`, numbers only: `clock would-clamp rule=<start|jump|ceiling>
reported=<ms> bound=<ms>` on each fault of an output (first 20), `clock would-clamps=<n> maxExcess=<ms>
start=<n> jump=<n> ceiling=<n>` on release, and the always-on `clock drift out=<n> ppm=<n>
span=<s> lag=<ms> clamps=<n>` once a minute (the raw position's rate against
`nanoTime`; a final line with `final=1` at release). Tests are trace tests: the
seek-stall trace (a counter of the previous stream, 2.5 s and 12.4 h), jumps at
any time, drift of up to 1000 ppm either way over three hours, and a replay of
the previous guard asserting that the new one only clamps where the old one
clamped and gives the same output until then.

Verified on a test TV: a
long skip-burst session with no fault; one lying track caught (10 clamps
in the 53 ms before the next skip replaced it), playback continued; 35
landings, median 1.0 s, fastest 112 ms, the same as before the guard.

### 5.2 Resume seek lands on the previous keyframe

Rule: **a resume's saved position lands on the keyframe at or before it, never mid-GOP, positioned
once.** `setMediaItem(item, startPositionMs)` alone applies the position exactly, decoding and
discarding every frame from the last keyframe up to it; a second `seekTo` after preparation would
cancel the first request at the resume cluster and reopen the same bytes on a new connection
(+90-160 ms to first frame on a remote server). So `ItemBoundMediaSourceFactory` wraps a resume
load's source in `ResumeSnapMediaSource`, whose period's first `selectTracks` starts at
`getAdjustedSeekPositionUs(position, PREVIOUS_SYNC)`; Media3 adopts the position `selectTracks`
returns as the period's start, so the first request opens at the keyframe and no seek follows. An
unseekable file snaps to 0, as any seek into it would. One-shot per source: a retry's re-created
period keeps its exact position. The resume target takes `SeekSerializer`'s in-flight slot at the
media-item transition, before playback input can be accepted, and the first landing signal
(`onRenderedFirstFrame`/`STATE_READY`) frees it: a D-pad skip pressed while the player is still
preparing is held and replayed on top (§5). The reservation is keyed on `PlayerHolder`'s load
generation, so a transition left over from a replaced item reserves nothing
(`ResumeSeekOrderingTest`). A Transcode (HLS) plan, a Watch Together exact start and an external
start never snap (`loadOpening`'s `armResume`). `ResumeSnapMediaSourceTest`, `ResumeSeekGateTest`.

### 5.3 Container reads are buffered

Rule: **an extractor's small reads never reach OkHttp one by one.** Media3's extractors read
container fields a few bytes at a time (an MKV Cues index is mostly 1-4-byte EBML fields), and
`DefaultExtractorInput` passes each read straight to the `DataSource`; through OkHttp, Okio and
Media3's transfer reporting each call costs far more than its bytes. A 2 h movie's Cues index
(about 1.6 MB, read before tracks are known) took ~0.9 s of loader-thread CPU on the TV this way.
`CancelingDataSource` serves any read smaller than `READ_AHEAD_BYTES` (16 KiB) from a buffer it
fills with one upstream read; a larger read goes straight upstream with no extra copy. An upstream
read returns whatever bytes have arrived, so the buffer never waits for data the extractor didn't
ask for. `open` and `close` drop buffered bytes, and the abandoned-read tracker and the
transcode segment gate (§7) count bytes as they leave the network, unchanged.

### 5.4 Seek index cache

Rule: **a warm start of a Matroska file never requests its Cues.** When the Cues sit after the
first cluster, Media3 seeks to them before it can report tracks: one more range request for the
file's tail (~200-340 ms to a remote server) and, for a 2 h film's ~1.6 MB index, ~0.5 s of
parsing. So the first load of a file stores a compact seek index and later loads use it instead.

- **What is stored.** `AssMatroskaExtractor` collects the cues of Media3's seek track (the last
  default video track, else the first video, else the last default audio, else the first audio)
  as (time, segment start + CueClusterPosition), drops trailing cues at or past the duration as
  Media3's `ChunkIndex` does, and stores them under `cacheDir/seekidx` (`SeekIndexStore`) only
  when the Cues followed the first cluster, Media3 built a seekable map from them, there are at
  least 2 entries, every offset is inside the file and every entry answers Media3's own map with
  itself (checked off the loader thread before writing). The SSA tracks' Cues entries (docs/13's
  earlier-starting lines) and the time scale are stored with it, collected even with full styling
  off, so a later styled start needs no Cues pass either.
- **Bounds.** A cue within 500 ms of the last kept one is dropped and more than 65,536 kept cues
  abandon the index; out-of-order cues, which Media3 sorts, abandon it too, and so do more than
  16,384 SSA entries. At most 32 files and 2 MB, least recently used first; a hit marks its file
  used. Writes go to a temp file renamed into place (`LruFileDir`, shared with the trickplay sheet
  cache); a damaged or other-version file is deleted on read.
- **Only resumes keep one.** An item played to its end (the end reducer's played-through effect,
  solo or group) drops its index, keyed from the same stream URL, so the cache holds in-progress
  items only; a stop mid-file keeps it. A stop the server counts as watched without reaching the
  end has no single owner in the app (the server and the core's mirror decide it), so that index
  ages out by LRU.
- **Key and validation.** Only a static stream is cached, keyed by a hash of host, port, path and
  `mediaSourceId`: no token or per-play parameter takes part and the URL never reaches disk. A
  stored index is used only when the response's size (Content-Range total, else Content-Length)
  matches and its Last-Modified/ETag hash matches (both absent counts as a match).
- **Hit path.** The extractor is built with `FLAG_DISABLE_SEEK_FOR_CUES`, so Media3 reports tracks
  at the end of Tracks and an Unseekable map at the first cluster; `AttachmentSeekingExtractor`'s
  output swaps that for `CachedSeekMap`, which answers exactly as Media3's `ChunkIndex` over the
  same points (the point at or before, plus the next one, which §5.2's snapping needs), and with
  full styling on hands the stored SSA entries to the styled renderer there, as the Cues pass would.
  A hit whose segment start, first cluster or duration (beyond 1 ms) no longer match, or whose read
  then fails to parse, ends early or throws any non-network error (a file rewritten under the same
  size and Last-Modified), drops its index at once and fails as `SeekIndexMiss`, never playing on
  unseekable; `ReconnectPolicy.retriesCold` re-prepares that play session cold once, with no
  reconnect episode or message. Its second failure, a cold failure and a network failure take the
  usual path, and a network failure keeps the index. A seekable map from Media3 is never
  replaced, and Cues reached at the end of playback neither re-emit a map nor rewrite the index.
- **Landing.** With keyframes closer than 500 ms, a resume or seek can land up to that much before
  the keyframe Media3's full index would pick; the extra frames decode and are discarded.

`MatroskaSeekIndexTest`, `SeekIndexStoreTest`.

## 6. Detail playability

The Movie and Episode detail pages end the spec capsule with one cell that says what pressing
Play will do on this TV, so a file the TV can't play is visible before Play is pressed:

| Cell | When | Tier |
|---|---|---|
| `DIRECT PLAY` | Some version passes this TV's device profile, or Auto would attempt Direct Play despite a miss (§1.1 row 4: Tolerate on, not a missing decoder) | notable (Panna) |
| `WILL TRANSCODE (X)` | No version passes and Auto transcodes up front (no decoder for the codec, or any miss with Tolerate off); transcoding allowed or unknown | baseline (Grigio) |
| `WILL TRANSCODE` | Any bitrate cap (every file transcodes), transcoding allowed or unknown | baseline (Grigio) |
| `WON'T PLAY (X)` | No version passes and nothing will convert it: Direct Play mode, or Auto transcoding up front where the server forbids video transcoding for this user | warning (Ambra) |
| none | No stream facts (the mirror's first paint, a Series) | — |

**One rule set.** The verdict evaluates each `MediaSource` against the same `RawDeviceProfile`
`android_tv_profile` sends the server (`jellyfin_core::profile_miss`): the Direct Play
profile's codec lists, then every video `CodecProfile` that applies (its `ApplyConditions`
hold), condition by condition. A stream fact the server didn't send passes here, though the
server fails an `IsRequired` unknown: the cell never claims "won't play" without evidence. The
container is not judged (a remux still plays direct). The audio judged is the server's default
track: `DefaultAudioStreamIndex`, else the stream flagged default, else the first. Auto's
up-front transcode (§1.1 rows 2-3) and this cell ask one predicate
(`auto_transcodes_up_front`), so they can't disagree for any miss.

**Versions.** Playback takes the first version the server can play direct, so the cell says
`DIRECT PLAY` when any version passes; the reason otherwise comes from the first version. A
version with no video codec to judge may be the one playback picks, so with no pass and any
such version the cell is absent rather than `WON'T PLAY`; a bitrate cap ignores misses.

**`X`** names the failing property in the capsule's own vocabulary: the video or audio codec,
the video profile, the HDR label, the resolution or the bitrate; it is the judged version's own
value, carried in `Playability.value`, not read off the page's default streams. A level miss (only
with "Tolerate mislabeled levels" off) and a missing fact show the cell without brackets.

**Transcoding allowed.** `UserPolicy.EnableVideoPlaybackTranscoding`, cached per account from
the `/Users/Me` reply `validate_session` (launch, account switch, foreground) and
`is_administrator` already fetch. A detail load that finds it unknown fetches `/Users/Me`
itself, only when the verdict differs between allowed and forbidden (Cap, or Auto transcoding
up front), and the same reply caches the role, so `is_administrator`'s call from the page
costs nothing more. Unknown (a failed fetch too) is treated as allowed. A server
with transcoding off for everyone (no ffmpeg) is not visible to the client; the play-time
refusal still covers it.

**Not judged.** Subtitle-driven transcodes (burn-in) and Auto's play-time software-decoder
fallback (§1.1 row 4): `DIRECT PLAY` is the profile's prediction.

**Idle cost.** Computed once per live detail record from data that record already carries; no
timer or collector, and no request beyond the `/Users/Me` the page asks for anyway.

## 7. Server cost bounds

A Transcode plan costs the server an ffmpeg process per (re)start, and Jellyfin
starts a fresh one for every segment request that finds no live job or lands
past its encode. The player bounds what it asks for:

| Case | Rule | Launches |
|---|---|---|
| The server's ffmpeg fails (HTTP 500, or 502/503/504 as a proxy relays it, on a segment or playlist) | One retry after 2 s, then "The server couldn't convert this video for this TV." Counted on Media3's `errorCount`, which an HLS retry never resets; variant exclusion is off for it. Only a 503 with Retry-After keeps the 90 s window. Direct Play keeps it for every 5xx: there it costs no ffmpeg. | ≤ 2 |
| A file whose streams end before its listed length | Two segments in a row with no video frame (no video PES; the server's past-the-end stub carries only tables and an audio packet, while every real segment starts with a keyframe, however black, silent or static) mean the server has no media from the first of them on. Counted on the loader thread as each whole read ends; a resumed read or bytes that aren't plain 188-byte TS prove nothing and break the run; a request the server holds past the 20 s read timeout counts as video-less only right after one that was (a held first post-end request stays a network error, bounded by the 90 s retry window); from then on that load never requests a segment at or past that point (a held load re-checks locally every 5 s, prepared or not, and never surfaces as an error; a back-skip, seek or retry never re-opens it). The gate keeps counting after a trip: a lower run trips again lower (a seek into the void, then back and playing forward), a higher one never raises the end. Buffered real media plays out; once tripped, a playing stall decides, outside a seek in flight (a seek's own stall belongs to the seek serializer until it lands or times out, then the buffering tick decides; the loader may never ask past the end when its buffer is full), never while paused (it decides once play resumes), and it ends the session by where it lands: within two segments of a known real end with everything up to it buffered, a normal end (reported at the full runtime, so watched, then next-up or exit; in a group, the group's end like a natural one); past the end, a quiet close resuming two segments before the real end (the last real segment can hold a single frame), so a resume plays out to a normal end; with no real media read on this load, "The server's conversion stopped producing video." A stall anywhere else is an ordinary rebuffer. The real end is known only when the run directly follows a segment read with media on this load (a resumed read counts), and is then the run's start; until then only a stall at or past the run's start ends it. Every later read raises the real end. A stop never reports a position in the stubs. Segment times come from the URL's runtimeTicks/actualSegmentLengthTicks, else index × 6 s. Transcode loads only: Direct Play reads pass through. | ≤ 2 past the end, per load (per stretch of stubs a seek lands in; Jellyfin 10.11 can hold a third request open indefinitely, which a third count would wait on forever) |
| Playing and buffering with nothing loading or seeking, yet the position hasn't moved for 15 s, whatever is buffered (a copied last segment whose truncated final PES never delivers end of stream; a mid-file timestamp gap the renderers can't cross) | Safety net: first one re-seek to the position through the seek serializer, the viewer's manual fix (a `player.stallRecover` diag line), once per load; frozen 15 s again, or any player failure within a second of where that re-seek was spent (the held request's IO timeout), stop with "Playback stopped: the rest of this video couldn't be read." when everything up to the listed end is buffered, else "Playback stopped: this video couldn't continue past this point."; reported at the current position, never watched, with a `player.stalledForGood` diag line (position, buffered ahead, each audio/video renderer's state). Never before this load's first frame, counted again after a retry re-prepares (a cold software decoder can take tens of seconds over a full buffer), while paused (a pause restarts the 15 s), while a load or a seek is in flight, or while the position moves; timed on a monotonic clock. Group members too. | ≤ 2 |
| A burst of skips during a slow encode | §5's landing timeout is 20 s on a Transcode plan, so held presses coalesce into one final seek. | ≤ 2 per burst |
| The server sends video this TV can't decode (e.g. it remuxes AV1 because the account may not transcode video) | Every track of the type rejected and no fallback left: end with a message naming the track and its codec, never a black picture or silent playback. Not while a fallback negotiates. | 1 |
| A source larger than this TV's decoder | A software decoder reports the largest size it decodes at 24 fps, not its independent width/height ceilings, so the server's transcode scales down to it. A hardware decoder keeps its ceilings. | 1 |

One owner decides every end of a load (rows 2 and 3, and a natural end): `PlaybackViewModel.sendEnd` →
`reducePlaybackEnd` (`PlaybackEnd.kt`), fed by `PlayerHolder` and `OutOfMediaGate` events tagged with the load
generation, so a late event from an earlier load changes nothing. `LoadRetryPolicy`, `SeekSerializer` via `loadOpening`, `PlaybackViewModel.onTracksChanged`,
`DeviceCapsProbe`; unit-tested. `tools/ffmpeg-launches.sh` counts launches per scenario
from the server's log.
