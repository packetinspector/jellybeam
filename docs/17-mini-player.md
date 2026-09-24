# 17 — Mini player (picture-in-picture)

Spec of record for the mini player. Setting: **off by default**.

## 1. Product behaviour

With the mini player on (Settings › Playback › "Mini player"):

- **Back** at the end of the player's Back chain (docs/12: picker → next-up →
  OSD hide → exit) shrinks the video into the system picture-in-picture window
  instead of stopping it. The app underneath (Home, a detail page, wherever
  playback was started from) is usable while the video keeps playing.
- **Home** during fullscreen playback does the same (`onUserLeaveHint`).
- **Playing something else** — any Play/Resume/"Start from beginning" in the
  app, an external `tv.jellybeam.action.PLAY` intent, or a `jellybeam://play/<id>`
  deep link — stops the mini-player item (final position report), swaps the
  new item into the same player and expands back to fullscreen.
- **Next Up** keeps working headlessly: the countdown runs in the ViewModel, so
  with autoplay on the next episode starts inside the mini player. With
  autoplay off the natural end closes the mini player.
- **"Still watching?"** in the mini player behaves as an inactivity guard: the
  prompt is not drawn, its timeout runs down, Stop reports the finished
  episode and closes the mini player (it does not open the next episode's
  detail page — the viewer is elsewhere).
- **Another app takes audio focus** (launcher video, another player):
  ExoPlayer is built with `handleAudioFocus = true`, so the mini player pauses
  and stays paused, visible, until the viewer expands or dismisses it.
- **Dismissing the window from the system UI** (the PiP menu's close) stops
  playback, reports the final position exactly once, and removes the player
  task. Nothing keeps running headless.
- **Exiting Jellybeam** (double Back at the Home root) closes an active mini
  player first, so no orphan window survives the app.
- **Selecting the mini player** (system "expand") returns to fullscreen with
  the OSD hidden; the next key press reveals it as usual.

With the setting off, nothing changes: Back exits playback, Home stops it.

## 2. Architecture

The "singleInstance player" shape:

- `PlaybackActivity` is `launchMode="singleInstance"`, `supportsPictureInPicture`,
  `resizeableActivity`, and its `configChanges` include
  `screenSize|smallestScreenSize|screenLayout|orientation` so entering/leaving
  the PiP window never recreates it. It is always alone in its own task, so
  `finishAndRemoveTask()` is always safe and is the only way it closes from a
  PiP state (a plain `finish()` can leave a ghost task that absorbs the next
  launch intent).
- **Replacing the item never destroys the Activity.** A `startActivity` with
  `PlaybackActivity.intent(...)` while an instance is alive (fullscreen or
  PiP) is delivered to it via `onNewIntent`; the Activity calls
  `PlaybackViewModel.replaceItem(itemId, startFromBeginning)` (the same
  stop-report-then-start transition autoplay already uses) and Android expands
  the PiP window in place. No teardown choreography, no second player, no
  leaked decoder.
- The MediaSession's `sessionActivity` PendingIntent carries
  `EXTRA_RESUME_SESSION`; an `onNewIntent` with that extra only brings the
  player forward and never restarts the item.
- `PipController` (`player/PipController.kt`, process-local like
  `playbackActivityTracker`) holds: `isSupported(context)` (API 26+,
  `FEATURE_PICTURE_IN_PICTURE`, AppOps `PICTURE_IN_PICTURE` allowed),
  `isInPip: StateFlow<Boolean>`, the last video aspect (`PipAspect`, clamped to
  Android's 1:2.39 … 2.39:1), `enter(activity)` and a
  `finishPlayback` callback the live Activity registers
  (`finishAndRemoveTask`).
- `PipAspect` (`player/PipAspect.kt`) is the pure clamp, unit-tested; the
  Activity feeds it from `Player.Listener.onVideoSizeChanged` and refreshes
  `setPictureInPictureParams` while in PiP so an item swap resizes the window.
- Enabled = `Settings.mini_player_enabled` (snapshot taken by
  `PlaybackViewModel.start`, exposed as `PlaybackUiState.miniPlayerEnabled`)
  **and** `isSupported`.

## 3. Lifecycle matrix (`PlaybackActivity`)

| Event | Not in PiP | In PiP |
|---|---|---|
| Back → `BackAction.EXIT` | mini player on and first frame rendered → `enter()`; otherwise `finish()` | n/a (no key input) |
| Home → `onUserLeaveHint` | mini player on, READY, not finishing, not leaving for a detail page → `enter()` | no-op |
| `onStop` | as today: release MediaSession, `stopPlaybackOnce`, `finishAndRemoveTask` if not finishing | release MediaSession, cancel init jobs, **return** (dismissal is handled below; a transient stop such as screen-off keeps playing and `onStart` recreates the session once STARTED again) |
| `onPictureInPictureModeChanged(true)` | — | `isInPip = true`, Compose `inPictureInPicture = true` (only the video surface is drawn), Coil memory cache cleared |
| `onPictureInPictureModeChanged(false)` | — | `isInPip = false`; if `expandViaIntent` → clear the flag; else if lifecycle < STARTED → dismissed → `finishAndRemoveTask()` |
| `onNewIntent` | resume-session extra → no-op; else `replaceItem` | same, plus `expandViaIntent = true` before the swap |
| `PlaybackEvent.Finish` | `finish()` | `finishAndRemoveTask()` |
| `FinishWithMessage` | toast + `finish()` | toast + `finishAndRemoveTask()` |
| `FinishToDetail` | `leavingForDetail = true`, start MainActivity with `ACTION_OPEN_DETAIL` + `FLAG_ACTIVITY_NO_USER_ACTION`, `finish()` | `finishAndRemoveTask()` (no detail routing) |
| `ReauthorizationRequired` | request + `finish()` | request + `finishAndRemoveTask()` |
| `onDestroy` | `stopPlaybackOnce` fallback, MediaSession fallback release, remove the video-size listener, `pipController.onActivityDestroyed()`, tracker decrement | same |

`MainActivity`:

- `finish()` override: if `pipController.isInPip` → `pipController.stopPlayback()`
  first (app-exit cleanup).
- External PLAY / deep link: `playbackActivityTracker.awaitIdle()` only when
  no PiP is active (a PiP'd player never reaches idle; the intent must be
  delivered to it instead).
- In-app Play sites are unchanged: `startActivity(PlaybackActivity.intent(..))`
  routes to the live instance by itself.

`PlaybackScreen`:

- New parameters `inPictureInPicture: Boolean` (every overlay — OSD, scrims,
  next-up/still-watching cards, pills, toasts, glide surface — is gated on
  `!inPictureInPicture`; the `AndroidView` player surface always draws) and
  `onExitRequested` (Back chain's EXIT; `onFinish` stays the terminal path).

## 4. Setting

- Rust: `Settings.mini_player_enabled: bool`, `#[serde(default)]` (false is the
  zero value), tolerant-load + round-trip tests like `preload_on_focus`.
- Kotlin: `SettingsViewModel.toggleMiniPlayer`, Playback section row right
  after "Autoplay delay" (before the Still watching group), focus key
  `playback/mini_player`, label "Mini player", description "Back or Home
  during playback shrinks the video into a corner window that keeps playing
  while you browse."
- **Unsupported devices** (`PipController.isSupported` false: API < 26, no
  `FEATURE_PICTURE_IN_PICTURE`, or the AppOps grant revoked): the row renders
  dimmed (`SETTINGS_DISABLED_ALPHA`, the Still watching precedent), always
  reads Off whatever is stored, Select is a no-op, and its description says
  "Not available on this device. Picture-in-picture is missing here or turned
  off for Jellybeam in the system settings." The row stays focusable so the
  D-pad path through the section has no gap. The check runs once per visit
  to the Playback section, so a grant changed in system settings shows on the
  next visit.

## 5. On-device test cases

1. Setting off (default): Back exits, Home stops playback — unchanged.
2. Setting on: Back → PiP window bottom-right, app underneath focusable.
3. Home → PiP; relaunch Jellybeam from the launcher → Main task in front, PiP still playing.
4. In PiP, play another item from a detail page → old item's stop report lands, new item fullscreen, no double audio.
5. In PiP, `adb shell am start -a tv.jellybeam.action.PLAY --es tv.jellybeam.extra.ITEM_ID <id> tv.jellybeam/.MainActivity` → same as 4.
6. Next Up inside PiP with autoplay on → next episode plays in the window; aspect updates.
7. Dismiss from the PiP menu → playback stops, `dumpsys audio` shows no started MOVIE stream, no `tv.jellybeam` player task in `dumpsys activity activities`.
8. Double-Back exit at Home with PiP active → window closes with the app.
9. Another app's playback starts while in PiP → Jellybeam pauses; expand → still paused.
10. "Still watching?" trips inside PiP → window closes after the timeout, episode reported.
11. Expand from PiP → fullscreen, OSD hidden, D-pad works, MediaSession present.

## 6. Refresh after the window closes

Closing the window from the system UI ends playback underneath an already
resumed MainActivity, so none of the usual "came back from the player" edges
fire (`onResume`, a retained screen becoming top). Two things keep the UI
honest without them:

- **Detail renders a live card.** `Screen.Detail(card)` is only the seed;
  `DetailViewModel` re-reads the card from the mirror (`cardById`) on any
  change event naming it, so the header's Resume / remaining / watched state
  follows playback on every item type (before this, the episode and movie
  headers were frozen for the page's lifetime in every flow, PiP or not).
- **A stop-report edge.** `PlaybackReports.stopEpoch` is bumped once the
  core's final stop has returned, which is after the mirror write's commit
  barrier. Home and Detail refresh on that edge in addition to their own
  mirror change-event collector (whose buffer drops oldest under load), both
  now running through `tv.jellybeam.ui.common.ChangeRefreshScheduler` --
  docs/16-library-sort-filter.md §4.6 -- so the committed position is read
  exactly once it exists. `DetailScreen` feeds its `isTop` into
  `DetailViewModel.setActive` (the scheduler's visible/hidden sampling gate)
  exactly as Home and Library do; the gate defaults `true` so a ViewModel
  with no screen composed yet samples at the visible 500ms rate.
