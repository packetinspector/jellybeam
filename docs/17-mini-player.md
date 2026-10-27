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
  new item into the same player and expands back to fullscreen. Play/Resume
  (in-app or external) for the item the mini player already shows, started on
  the active account with no switch since, with no start option, only expands
  it (docs/05). An external one sent while that item plays fullscreen shrinks it
  like Home and leaves it in the mini player.
- **Library sync waits** while the mini player plays (bulk sync yields to
  playback), so Home shows no "Syncing…" pill until playback ends and the sync
  resumes.
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
- Media keys have one owner, the MediaSession: its player is a
  `ForwardingPlayer` (`RoutedPlayer`) whose Stop ends the session like Back
  (`PlaybackViewModel.stopFromRemote`: final position reported, Activity
  closes, also during an autoplay or Play-intent transition, whose pending
  title is then never loaded), whose FF / REW seek by the viewer's
  skip-forward / skip-back settings (also its reported seek increments), whose
  absolute seeks use the guarded seek path, and whose Play / Pause go through
  the view model. It offers only play / pause, stop, in-item seeks and the
  reads the session publishes (an allowlist granted in
  `onConnect` to every controller, trusted or not, since a remote remapper or
  the cast shell is an unverifiable app, and reported as the player's commands), so Media3 refuses any
  other call before it reaches the ExoPlayer around the view model.
  `PlaybackScreen` leaves every transport key unconsumed while a session
  exists (`MediaKeys.route`), hidden OSD or not; `PlaybackActivity` swallows
  them, key down and key up, while none does (before the first frame, after a
  non-PiP `onStop` released it; a PiP `onStop` keeps the session, so PiP behind Home still answers the remote), so they never reach another app's session. The
  session is created at the first rendered frame and again from `onStart`.
- Every final stop (Back, natural end, "still watching?" stop, a fatal error
  after playback began, a media-key Stop) is launched by `launchFinalStop`,
  which registers the landing a replacement (`replaceItem`) awaits, so a
  Play-from-Home into PiP never installs the new item before the old
  session's Stopped has landed.
- `PipController` (`player/PipController.kt`) is the device side only:
  `isSupported(context)` (API 26+, `FEATURE_PICTURE_IN_PICTURE`, AppOps
  `PICTURE_IN_PICTURE` allowed), the last video aspect (`PipAspect`, clamped to
  Android's 1:2.39 … 2.39:1), `params()` and `enter(activity)`.
- `PlaybackActivityTracker` (`player/PlaybackActivityTracker.kt`) owns player
  presence and play requests, process-wide because the `singleInstance` player
  and MainActivity never hold each other. One pure `reduce`, fed by a Main-only
  `send`, holds four things:
  - **The player slot:** none, launching, fullscreen, shrinking, PiP,
    expanding or closing. Each `PlaybackActivity` instance is a generation
    minted at its create and carried by every callback it sends, so an older
    instance's late callbacks (its destroy after a newer one's create) only
    drain it and never touch the newer one.
  - **One request slot:** the waiting Play or detail request, from an
    external intent, an in-app Play (`PlaybackActivity.launch`) or a Watch
    Together load. The newest replaces it.
  - **MainActivity Shown / Hidden,** set by its `onResume` / `onStop`, not by
    its lifecycle state: API 28 restarts a stopped Activity before
    `onNewIntent`, so the state can't tell "covered" from "paused under PiP".
  - **The root gate:** the account tree's stack is pending, signed out or on
    Home.

  Its effects are data, run after the new state is published: close
  (`finishAndRemoveTask` on that generation, at most once), launch, deliver
  (the same `startActivity`, which `singleInstance` routes to `onNewIntent`),
  the sign-in toast and leaving the group.
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
| `onStop` | as today: release MediaSession, `stopPlaybackOnce`, `finishAndRemoveTask` if not finishing | keep the MediaSession (`sessionShouldSurviveStop`), cancel init jobs, **return** (dismissal is handled below; a transient stop such as screen-off keeps playing; `onDestroy` always releases the session) |
| `onPictureInPictureModeChanged(true)` | — | `PipEntered` to the tracker, Compose `inPictureInPicture = true` (only the video surface is drawn), Coil memory cache cleared |
| `onPictureInPictureModeChanged(false)` | — | `PipExited` to the tracker: lifecycle at STARTED is an expand (fullscreen again); below it a dismissal, which the tracker closes once with `finishAndRemoveTask()`; an expand a delivered request started is already expanding there |
| `onNewIntent` | resume-session extra → no-op; same item, no start option → no-op (docs/05 "Same item"); a group launch attaches to the group; else `replaceItem` | same; Android expands the window in place |
| `PlaybackEvent.Finish` | `finish()` | `finishAndRemoveTask()` |
| `FinishWithMessage` | toast + `finish()` | toast + `finishAndRemoveTask()` |
| `FinishToDetail` | `leavingForDetail = true`, start MainActivity with `ACTION_OPEN_DETAIL` + `FLAG_ACTIVITY_NO_USER_ACTION`, `finish()` | `finishAndRemoveTask()` (no detail routing) |
| `ReauthorizationRequired` | request + `finish()` | request + `finishAndRemoveTask()` |
| `onDestroy` | `stopPlaybackOnce` fallback, MediaSession fallback release, remove the video-size listener, then the tracker's `destroyed` (detaches only this instance's handle) | same |

`MainActivity` and the tracker:

- `finish()` sends the app's exit: a player in, or headed for, PiP is closed
  so no orphan window survives the app, and the waiting request and any start
  not yet run are dropped.
- External PLAY / OPEN_DETAIL / deep link (`onNewIntent`, and `onCreate` only
  without saved state, so a recreate never replays its last intent) and every
  in-app Play reach the tracker as a request; nothing starts a player itself.
- After every event the waiting request settles:
  - root pending → it waits; signed out → it is dropped, an external PLAY with
    sign-in feedback;
  - no player and none still tearing down → launched once MainActivity is
    shown;
  - launching, shrinking, expanding, closing, or an older instance still
    tearing down → it waits;
  - fullscreen → delivered to it;
  - PiP → delivered while MainActivity is shown (the window expands in place),
    waiting while it is hidden.
- **Starts run later.** The update owner may run a Launch or Deliver after the
  step that issued it (docs/26 §1), so it runs only while it is still the last
  start the tracker issued. An account change, leaving the app, or MainActivity
  hiding while a launch has no player yet withdraws it; a newer start replaces
  an older one. A withdrawn launch frees the slot, so a player never starts
  from the background. A launch in flight counts as playback for the update
  owner until it is withdrawn or fails.
- A Deliver whose start fails leaves the mini player in PiP and drops its
  request; a failed launch frees the slot.
- **MainActivity hiding** drops a waiting request, so a later reopen never
  plays something unasked; a Watch Together load keeps waiting.
- An account change returns the root to pending, as does MainActivity's
  destroy without a finish, so a request waits for the next stack.
- **Fresh vs Any.** An external PLAY arriving while a player covers a hidden
  MainActivity is Fresh: the intent shrinks that player into PiP, and a Fresh
  request never joins it. It leaves the player in PiP for its own item with no
  start option; for any other item or a start time the player is closed and
  the request launches after its destroy, because expanding straight out of
  that PiP entry can leave MainActivity on top and stop the player. A mini
  player floating over another app counts as covering. Every other request,
  and a Fresh one once a newer player instance exists, is Any.
- An expand the platform interrupts before it lands relaunches its request
  once, unless a newer request waits. An account change drops the waiting
  request and never closes a player (docs/18 §2.1).
- **Watch Together** (docs/28 §4): a group load skips the root gate; it waits
  while MainActivity is hidden, so a load while Jellybeam is in the background
  waits until it is opened; an interrupted group expand is dropped, since the
  group's next Load launches again; any other request replacing a waiting
  group load leaves the group. A create refused over a live player stops it
  (any player but a closing one, closed once) and waits for that instance's
  destroy, by which its final stop is counted (docs/28 §1).

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
- **The stop's own mirror write.** The core's final stop commits the
  position to the mirror before it returns, and that write is an `Upserted`
  change event naming the stopped item. Home refreshes on any item change (the
  item may be about to enter Continue Watching); a Detail page refreshes only
  for its own item or one it lists. Both run through
  `tv.jellybeam.ui.common.ChangeRefreshScheduler` (docs/16 §4.6): a screen
  that is not top only marks itself dirty and refreshes once when it is top
  again. An item the mirror doesn't hold (an external play of an item not
  yet synced) writes nothing, so its stop refreshes no screen until the
  server's own change event arrives.
