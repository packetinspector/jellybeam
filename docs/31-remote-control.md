# 31 — Remote control from the server

Status: planned for the next release.

Jellybeam answers the Jellyfin server's remote-control protocol, so any
controller that drives a Jellyfin session can drive the TV. That includes the
Jellyfin web and phone apps ("Play on…"), Home Assistant's Jellyfin
integration, and scripts calling the Sessions API. The bar is parity with the
official Android TV client: every command it handles, we handle, through our
own owners.

## 1. Goals and non-goals

Goals:

- The server lists the TV as controllable, so controllers enable their buttons.
- Play, Playstate and the GeneralCommands in §4 work on video and music.
- They work in the mini player and while music plays in the background.
- Controllers show the true state: paused, position, audio and subtitle
  track, volume. They update right after each command, not at the next
  10-second tick.
- Remote Play starts as fast as an in-app Play. It goes through the same
  owner, so TTFF must not slip (docs/18).

Non-goals:

- No new socket and no new timer.
- No controller UI inside Jellybeam: we are the target, not the remote.
- No HA-specific code; that is a separate, later spec.

## 2. Cost when unused

Remote control adds no requests, timers, collectors or first-frame work
(docs/02 §4, docs/28 §8):

- The account socket already exists. The only new traffic is one
  `POST /Sessions/Capabilities` per socket connect (§3).
- Decoding a command is a new `ws.rs` arm. Nothing runs until a command
  frame arrives.
- The Kotlin dispatcher is a function, not a collector. It runs only when the
  core calls its listener.
- `AppGraph.music` is never built to answer a command. When no music
  session exists, music commands are dropped.

## 3. Capabilities

The core posts capabilities after every socket `Connected`, before
`NeedsReconcile` work. The server forgets capabilities when a session lapses,
which is why it posts on every connect. It also posts again when the setting
in §8 or the volume capability (§4.4) changes.

A pure function `capabilities(settings, device)` in jellyfin-core builds the
body:

| Field | Value |
|---|---|
| `PlayableMediaTypes` | `Video`, `Audio` |
| `SupportsMediaControl` | `true`; `false` when the setting is off |
| `SupportedCommands` | Parity: `DisplayContent`, `SetSubtitleStreamIndex`, `SetAudioStreamIndex`, `DisplayMessage`, `SendString`; plus `VolumeUp`, `VolumeDown`, `SetVolume`, `Mute`, `Unmute`, `ToggleMute` only when volume is controllable (§4.4). Beyond parity (§4.5): the navigation, OSD, stats, bitrate and music-mode commands, plus `PlayTrailers` / Live TV commands only once those features ship. |

The device facts ride the existing capability probe that Kotlin already
passes over the FFI (docs/18 §1).

A failed post is diag-logged and retried on the next connect. A 401 follows
the existing re-authorization path.

## 4. Commands

The core decodes each frame into one typed `RemoteCommand`. Data that is
missing or malformed becomes `Ignored`, as other frames do today (docs/07
"Watch state from other clients"). Only frames for the installed account
arrive (§6).

### 4.1 `Play`

Body fields: `ItemIds`, `StartIndex`, `StartPositionTicks`, `PlayCommand`,
`MediaSourceId`, `AudioStreamIndex`, `SubtitleStreamIndex`.

The command enters `PlaybackActivityTracker` as `Req.Kind.Play` with a new
`Req.Origin.Remote`. It follows the same rules as an external intent
(docs/05 "External playback contract", docs/17 §3).

Start position:

- `StartPositionTicks` present: `At(ms)`.
- Absent: `Resume` under the normal resume rule.

Item kinds:

| Items | Route |
|---|---|
| One video (Movie, Episode, Video, MusicVideo) | Video player |
| One Series, Season or BoxSet | Core Play pick (`series_summary_read`, docs/11 item 11), then the video player |
| Several videos | Video player with a remote list (§4.1.1) |
| Audio, MusicAlbum, MusicArtist, Playlist of audio | `MusicPlaybackOwner.play` with a new `MusicSource.Items(ids, startIndex)` (docs/29 §4) |

`PlayCommand`:

| Value | Video | Music |
|---|---|---|
| `PlayNow` | Replace what is playing | `play` |
| `PlayNext` | Insert after the current item in the remote list; if nothing is playing, `PlayNow` | `enqueue(Next)` |
| `PlayLast` | Append to the remote list; if nothing is playing, `PlayNow` | `enqueue(Last)` |
| `PlayShuffle` | Shuffle the list, then `PlayNow` | `play(shuffled = true)` |
| `PlayInstantMix` | Ignored | `MusicSource.Mix(seed)` |

Track hints: `MediaSourceId`, `AudioStreamIndex` and `SubtitleStreamIndex`
become optional initial picks.

- They extend `Req.Kind.Play`, the `PlaybackActivity` extras and
  `prepare_playback` with `TrackRequest?`.
- They feed TrackChoice's first pick as if the viewer had chosen them in the
  picker (docs/18 §3.1).
- A hint never forces a transcode the viewer's own pick would not
  (Direct Play is the default).

Watch Together: a remote Play of other content leaves the group, exactly as
`replaceItem` does (docs/28 §4).

#### 4.1.1 Remote list (video)

A Play with several video ids gives the live video session an ordered list.

- Next and previous walk the list before falling back to episode neighbours.
- At the end of the list, normal next-up applies.
- The list lives inside the existing playback session owner, not a new
  owner. It dies with the session.
- It is reported as `NowPlayingQueue` and `PlaylistItemId`, the same shape
  music uses (docs/29 §2).

### 4.2 `Playstate`

Fields: `Command` and `SeekPositionTicks`.

Target selection, a pure `route(command, live)`:

- A live video player (full screen or mini player) wins.
- Otherwise a live music session.
- Otherwise the command is dropped and diag-logged.

| Command | Video (`PlaybackViewModel`) | Music |
|---|---|---|
| `Pause` / `Unpause` | New idempotent `pause()` / `unpause()`, group-aware like `togglePlayPause` | `Transport(Pause)` / `UserPlay` |
| `PlayPause` | `togglePlayPause` | Toggle |
| `Seek` | `seekToPositionMs` (group-aware) | `Transport(SeekTo)` |
| `NextTrack` / `PreviousTrack` | Remote list, else `playNextEpisode` / `playPrevious` | `Transport(Next)` / `Transport(Previous)` |
| `Rewind` / `FastForward` | `seek(∓skip)` using the skip settings (docs/12) | Seek ∓10 s |
| `Stop` | `stopFromRemote` (leaves any group) | `MusicEvent.Stop` |

The live player is reached through a new transport port on the existing
`PlayerHandle`, so no second handle exists.

Every applied command asks the reporting session for an immediate Progress
report, through a `report_now` hook on the existing actor (docs/18 §2).

### 4.3 `GeneralCommand`: tracks, navigation, messages

`SetAudioStreamIndex` / `SetSubtitleStreamIndex` take `Index`; a subtitle
index of `-1` means off.

- A pure `rowForStreamIndex(planFacts, kind, index)` maps the server stream
  index to a picker row (`TrackMapping`), then calls `chooseAudio` /
  `chooseSubtitle`.
- Group gates apply: a pick that needs re-encoding is refused in a group
  (docs/28 §1).
- An index with no matching row is dropped.

`DisplayContent` takes `ItemId` and `ItemType`:

- It arrives as `Req.Kind.OpenDetail`, and `CollectionFolder` / `UserView`
  opens that library.
- It is ignored while a full-screen player is up, as in the official client.

`DisplayMessage` / `SendString` take `Header`, `Text` or `String`:

- Shown as a system Toast, `Header: Text`.
- Truncated to 200 characters and shown verbatim; we don't render
  formatting.
- Toast draws over the player and the mini player without a new overlay
  component.

### 4.4 Volume

The TV's audio output may be a bitstream (passthrough, docs/18 §5.1), so
player gain cannot be the volume. Remote volume uses the system
`AudioManager.STREAM_MUSIC`:

| Command | Effect |
|---|---|
| `SetVolume` (`Volume` 0–100) | Set the stream volume to the proportional index |
| `VolumeUp` / `VolumeDown` | `adjustStreamVolume` by one step |
| `Mute` / `Unmute` / `ToggleMute` | Stream mute |

- It is advertised only when `!isVolumeFixed`. Many TVs fix volume and use
  HDMI-CEC, and then these commands are not offered.
- Music loudness normalization stays a player-gain multiplier (docs/29 §1)
  and doesn't conflict.
- Reports carry the real `VolumeLevel` and `IsMuted`, read when the report is
  built.

### 4.5 Beyond parity

These are commands the protocol defines and the official client ignores.
Each one goes to an existing owner, and none adds cost when unused (§2).

Typing and navigation (MainActivity on top; in the player, the player owns
the keys):

| Command | Effect |
|---|---|
| `SendString` (`String`) | A focused text field (Search, sign-in server URL) gets the text inserted at the cursor, as if typed; no field focused: shown as a message (§4.3). This replaces the on-screen keyboard from a phone. |
| `MoveUp` / `MoveDown` / `MoveLeft` / `MoveRight`, `Select`, `Back`, `PageUp` / `PageDown` | Dispatched as the matching D-pad key to our own window's `dispatchKeyEvent`, so focus rules (docs/15) apply unchanged. Never injected into other apps. |
| `PreviousLetter` / `NextLetter` | The library index rail steps one letter (docs/16) |
| `GoHome` / `GoToSearch` / `GoToSettings` | Navigate to that destination; ignored while a full-screen player is up |
| `ToggleContextMenu` | Opens the `···` menu on Detail (docs/19), else the focused card's menu |
| `ToggleOsd` / `ToggleOsdMenu` | In the player: show or hide the OSD / the speed and chapters menu (docs/12) |
| `ToggleFullscreen` | Mini player ↔ full screen (docs/17) |

Playback extras:

| Command | Effect |
|---|---|
| `ToggleStats` | Opens or closes the player's stats sheet (docs/12 row 10): HDR format, codecs, Direct Play or transcode reason, dropped frames |
| `SetMaxStreamingBitrate` (`MaxBitrate`) | Maps to the nearest Quality step (docs/18) for the current playback only, renegotiating as an OSD Quality change does. It is not persisted, and Direct Play stays the default for the next item. |
| `SetRepeatMode` (`RepeatMode`) | Music `Transport(Repeat)`; ignored for video |
| `SetShuffleQueue` (`ShuffleMode`), `SetPlaybackOrder` | Music `Transport(Shuffle)`; ignored for video |
| `PlayMediaSource` | Same as `Play` with `MediaSourceId` (§4.1) |
| `PlayTrailers` (`ItemId`) | Plays the item's trailers. Advertised only once trailer playback ships. |
| `Guide`, `ChannelUp` / `ChannelDown` | Advertised only if Live TV playback ships; not part of this release |

Reports gain `RepeatMode` and `ShuffleMode` for music, so controllers show
the real mode.

Launch when closed: the server cannot reach an app that isn't running. A
controller that can run ADB (Home Assistant's Android TV integration, for
example) can start playback with the existing external contract, such as
`am start -d jellybeam://play/<id>` (docs/05). That needs no new code; the
user docs say so.

`TakeScreenshot` stays unsupported: video surfaces can't be captured.

### 4.6 Anything else

Unsupported commands are ignored with one diag line naming the command.

## 5. Foreground and background

Android does not let an app start an activity from the background, and our
rule is that a player never starts from the background (docs/17 §3). So:

| App state | `Play` / `DisplayContent` | `Playstate`, tracks, volume, message |
|---|---|---|
| Jellybeam on top | Applied | Applied |
| Mini player visible | Applied (the app has a visible window) | Applied |
| Music playing, app hidden | Music `Play` applied; video `Play` and `DisplayContent` dropped | Applied to music |
| Hidden, nothing playing | Dropped, with a diag line | Dropped |

Dropped requests are not queued for later. A remote press that shows nothing
for minutes and then plays is worse than nothing.

## 6. Accounts and servers

- Commands arrive only on the installed account's socket (docs/02 §1).
- A command acts on a live session only when that session belongs to the
  installed account (epoch match). A playback parked on another account
  after a switch (docs/18 §2.1) is not commandable from either server, and
  its own server shows it as not controllable.
- Multi-server proxies (docs/28 §7): whether the proxy forwards session
  commands is verified on the dev proxy before release. If it doesn't, we
  document it and ship nothing proxy-specific.

## 7. Reporting fixes this needs

These close gaps in `ReportingSession` (docs/18 §2):

- `AudioStreamIndex` / `SubtitleStreamIndex`: set from TrackChoice on start
  and on every change, sent on Start and Progress.
- `VolumeLevel` / `IsMuted`: real values (§4.4).
- `CanSeek`: false for live TV; otherwise true.
- `NowPlayingQueue` / `PlaylistItemId` for a video remote list (§4.1.1).
- `report_now` after any remote command, coalesced with the 500 ms seek
  debounce.

## 8. Setting

Settings → Playback → **Allow remote control**, default on to match the
official client.

- Off posts `SupportsMediaControl = false` with no commands, and the
  dispatcher drops everything.
- The server only routes commands from users allowed to control this session
  (the same user, or an administrator), so no in-app pairing is needed.

## 9. Ownership map

| Concern | Owner |
|---|---|
| Decode | `jellyfin-api` `ws.rs` → `ServerEvent::Remote(RemoteCommand)` |
| Capabilities body | `jellyfin-core` pure `capabilities()`; posted by the bus forwarder on `Connected` |
| Delivery to Kotlin | New foreign `RemoteControlListener`, next to `SyncPlayListener`, never on the change stream (docs/28 §8) |
| Routing | Pure Kotlin `route(command, live) -> RemoteAction`, tested as a matrix |
| Play / open | `PlaybackActivityTracker` (`Req.Origin.Remote`) |
| Transport, tracks | `PlaybackViewModel` through `PlayerHandle`; `MusicPlaybackOwner.send` |
| Volume | One `SystemVolume` helper wrapping `AudioManager` |
| Reports | `ReportingSession` |

No other new owners. The remote list is state inside the video session
owner.

## 10. Tests

- **Rust decode table:** every `PlayCommand`, `PlaystateCommand` and
  supported `GeneralCommand` frame, plus a malformed variant of each, using
  synthetic ids.
- **Capabilities:** fixed versus adjustable volume, setting on versus off.
- **Kotlin `route` matrix:** each command against nothing live, video full
  screen, mini player, music only, group, parked other account, and app
  hidden.
- **Track mapping:** direct-play embedded streams, transcode stream rows,
  external subtitles, `-1`.
- **Remote list:** next/previous at the edges, PlayNext/PlayLast insert,
  hand-off to next-up at the end.
- **Reporting:** stream indices and volume appear in Start and Progress;
  `report_now` coalesces with the seek debounce.
- **Emulator end-to-end against the dev server**, with curl to
  `/Sessions/{id}/Playing`, `/Playing/{cmd}`, `/Command` and `/Message`:
  - Play from a cold Home, Play over a playing item, pause/seek/stop in the
    mini player, music transport with the app hidden, track switches, Toast.
  - Drive the same session from the Jellyfin web client.
- **Beyond parity:**
  - `SendString` into a focused Search field versus no field (message).
  - D-pad commands move focus exactly like key presses, per the docs/15
    fixtures.
  - `SetMaxStreamingBitrate` maps to Quality steps, and the next item is
    back to Direct Play.
  - Music repeat and shuffle round-trip into reports.
- **TTFF:** a remote Play of the perf-ab item is within the spread of an
  in-app Play.
- **Idle:** with the app open and idle for 10 minutes, the request log shows
  only the capabilities post on connect.

## 11. Acceptance

- Jellyfin web "Play on Jellybeam" starts a movie, an episode, a series
  (next up) and an album.
- Its remote panel pauses, seeks, skips and switches tracks, and shows the
  state within a second.
- Home Assistant's Jellyfin media player for the TV has working
  play/pause/seek/next/stop, and "play media" from its browser starts
  playback.
- The Jellyfin web remote panel's navigation buttons and its text box drive
  the TV: type a search from the phone, move and select, Back, Home, show
  stats.
- Nothing changes for a viewer who never uses a remote: same cold start,
  same TTFF, same idle request log.
- docs/13 lists remote control; the setting string is translated per
  docs/27.
