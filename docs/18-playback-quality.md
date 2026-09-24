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
  Otherwise: takes the active `ReportingSession` and `stop`s it at
  `position_ticks` (final Stopped for the direct-play play session, plus
  the mirror writeback — the position is real), negotiates again with
  `force_transcode: true` and `StartTimeTicks = position_ticks`, requires
  a `Transcode` decision (else `NoPlayableSource`), starts a fresh
  `ReportingSession` with `play_method: Transcode`, returns a Transcode
  plan whose `start_position_ticks == position_ticks`.
  **Staleness guard**: the call carries the plan's
  `play_session_id` and only takes the active session if it is that one;
  `State.playback_generation` is bumped whenever the active session
  changes hands (prepare, stop, abandon, fallback install), captured before
  the negotiation and re-checked before installing — a mismatch abandons
  the freshly started session and returns `CoreError::StalePlaybackSession`,
  so a fallback started for one title can never take over the reporting of
  the title that replaced it.
- `preload_playback` caches DirectPlay plans only (an attempt-anyway plan
  included — its URL is position-independent); a Transcode plan is never
  cached (its HLS URL embeds the start position), and preload is a no-op
  in Cap mode.
- `prepare_playback` joins a same-item in-flight preload instead of
  aborting it, reusing the negotiation already under way; a preload for
  any other item is aborted as before.

Session identity and threading rules shared by every path above:

- `prepare_playback` negotiates over the network with the state lock
  released; it captures `playback_generation` before releasing the lock
  and re-checks it at `install_prepared_session`. A mismatch abandons the
  freshly negotiated session and returns `StalePlaybackSession`, the same
  guard as `prepare_transcode_fallback`.
- `stop_playback` / `abandon_playback` take the `play_session_id` they are
  for and act only if it still names the active session; a mismatch,
  including "no session", is a debug-logged no-op that touches neither the
  session nor the generation.
- `stop_session_and_sync_mirror` spawns the final Stopped report on the
  runtime rather than blocking on it: `ReportingSession::stop` is
  delivered-or-queued (100 ms ack, then the flush queue), so nothing is
  lost and the next negotiation is not serialized behind the old report.
  The mirror write stays synchronous: it is the barrier a same-item
  resume reads through (docs/17 §6).
- The synchronous `fetch_item_dto` reads the mirror on the caller's thread
  and only `block_on`s the network fallback (no runtime hop on a
  mirror-hit Play); the preload worker's async variant moves the mirror
  read to `spawn_blocking`.

## 3. Kotlin

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
  - The fallback coroutine snapshots a per-session generation and the
    plan's `playSessionId`; a result (success or failure) arriving after a
    newer session started is ignored, and `StalePlaybackSession` is
    ignored silently.
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
    cannot be cancelled anyway); if its own job was cancelled meanwhile it
    abandons the session Rust installed, by `playSessionId`, instead of
    leaking it. A live owner receiving `StalePlaybackSession` finishes
    quietly.
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
