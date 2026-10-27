# 02 — How it fits

The map from a lifecycle signal to the one place it enters, the state each signal moves, and
the seam to use when adding something. Everything is keyed by symbol: Kotlin as `File.kt`
`Type.member`, Rust as `file.rs` `Type::fn`. Read `01-architecture.md` first.

## 1. Lifecycle signals

Each signal enters once and fans out inside its owner. Where the code has more than one entry,
the entry named here is the one to extend.

### Process start / warm-up

- **Enters:** `JellybeamApp.kt` `JellybeamApp.onCreate` → `AppGraph.init` (idempotent).
- **Fans out:** `JellybeamCore::new` on `AppGraph.processScope`; `RealCoreGateway` awaits that
  `Deferred` on every call. `LaunchWarmup` runs `restoreSession` → `openMirror` → the first
  `homeSnapshot` from process start. The device-caps probe waits on
  `AppGraph.notifyFirstFrame` (or a 3 s cap) and completes `deviceCapsReady`;
  `PlayerHolder.prewarm` runs after the core is ready.
- **Consumed once:** `JellybeamRoot` takes `LaunchWarmup.takeSession`, `HomeFeed` takes
  `LaunchWarmup.takeHome` (`peekHome` only seeds the first frame). Anything after the first
  session gets `null` and runs the ordinary path; `LaunchWarmup.discard` drops both on a reset.

### Foreground / background

- **Enters:** `MainActivity.kt` `onActivityStart`, called from the `LifecycleEventObserver` in
  `JellybeamRoot` on `ON_START` with at least one account.
- **Fans out:** `checkSession(force = false)` (at most once a minute) → `CoreGateway.validateSession`;
  `CoreGateway.onForeground` → `object.rs` `JellybeamCore::on_foreground` →
  `Mirror::request_user_state_refresh(UserStateReason::Foreground)`. The mirror's refresher
  coalesces it with `Startup`, `Reconnect` and `Timer` (all from `sync.rs`) and
  `user_state.rs` `user_state::due` decides whether it runs.
- **Resumed state** is written in `MainActivity.onResume` / `onPause`: `AppForeground.browsing`
  (the one resumed bit; `retainedLayerIsActive` reads it), `AppForeground.resumeCount`,
  `UpdateCoordinator.resume` / `pause`. Process-level
  `app.foreground` / `app.background` diag lines come from
  `AppGraph.registerActivityLifecycleCallbacks`.

### Sign-in, account switch, removal (account epoch)

- **Enters (core):** `object.rs` `JellybeamCore::reset_account_state_locked`, called by
  `restore_session`, `switch_session`, `remove_session` (`AccountChange::Leave`) and
  `install_authenticated_session` (sign-in, Quick Connect). It advances `AccountSlot.epoch` and
  replaces `AccountSlot.installed` (client, facts, caches, mirror and bus as one `Installed`) in
  one step, invalidates the preload, and parks or ends a live playback; the outgoing bus bundle and
  the incoming facts refresh travel out in `AccountReset` and run after the lock is released.
  Every async result carries the epoch it started under and writes only through
  `AccountSlot::installed_mut(epoch)`, so a mirror open, facts refresh, cache store or follow-up
  that lost to an account change writes nothing.
- **Enters (Kotlin):** `CoreGateway.kt` `RealCoreGateway.accountChange` wraps every account
  call, re-reads `JellybeamCore::account_epochs` once in its `finally` and publishes it on
  `CoreGateway.accountEpochs`; it also completes `awaitAccountRestored`.
- **Fans out:** `AccountTreeOwner.follow` (one collector in `JellybeamRoot`) runs
  `resetSessionState` once per epoch move and bumps `AccountTreeOwner.epoch`, which re-keys the
  restore/open-mirror `LaunchedEffect`, the drawer favorites/accounts effect and the Discover
  drawer effect; a restored account queues a forced `checkSession`. Removing the active account
  installs the next saved one inside `remove_session`, so the tree's relaunch restore never moves
  the epoch again. In the player, `PlaybackViewModel` collects `accountEpochs` and calls
  `closeForAccountChange` when `CoreGateway.playbackOwnershipOpen` is false for its request.

### Screen becomes top / hidden

- **Enters:** `MainActivity.kt` `retainedLayerIsActive(index, topIndex, AppForeground.browsing)`, which
  becomes `isTop` for each `RetainedScreenLayer`. A layer is top only while the Activity is
  resumed and no crash prompt is up.
- **Fans out:** `ChangeRefreshScheduler.kt` `ActiveWhileTop(isTop, viewModel::setActive)` feeds the
  view model's `active` flow that gates `ChangeRefreshScheduler`;
  `FocusMemory.kt` `FocusRestorer(isTop)` restores focus on becoming top;
  `RefreshRestoreOwner.kt` `rememberRefreshRestoreOwner(isTop)` cancels a running
  refresh-restore on losing top; `RetainedScreenLayer` sets `RootFocus.seat` while top.

### Mirror change events

- **Enters (core):** `writer.rs` `writer::run` sends `MirrorChange` on the mirror's broadcast;
  `recv_changes` turns a lag into `MirrorChange::Refresh`. `object.rs`
  `JellybeamCore::spawn_listener_task` forwards each as a `ChangeEvent` to the one
  `ChangeListener`.
- **Enters (Kotlin):** `RealCoreGateway.changeListenerRegistration` (registered once, before the
  first `openMirror`) emits into `_changeEvents` and re-reads `viewsState` when
  `refreshesViews(event)`.
- **Fans out:** `CoreGateway.changeEvents()` — each screen's `ChangeRefreshScheduler` (`HomeFeed`,
  `LibraryViewModel`, `DetailViewModel`, `PersonViewModel`) with its own `affects` filter; the
  launch watcher in `LaunchWarmup`; the drawer favorites loop in `JellybeamRoot`.
- **Seerr** has its own counter, `CoreGateway.seerrRevision`, moved only by
  `RealCoreGateway.bumpSeerrRevision`.

### Watch Together group events

- **Enters (core):** `syncplay.rs` `sync_play_feed`, called by `object.rs`
  `JellybeamCore::spawn_bus_forwarder` for the installed bus (SyncPlay frames, `BusConnected`
  once the server-version refresh returns, `BusDisconnected`, `BusUnauthorized`, and a bus lag
  as a rejoin) and by the owner's own runner and jobs; the `sync_play_*` exports; `open_begin`
  re-entry (the taken bus steps `BusDisconnected` under its lock); `dispose_account_reset` (the
  outgoing group's `AccountReset`, on its own client); `Drop for JellybeamCore` (one bounded
  best-effort Leave).
- **Fans out:** `SyncPlayOwner::step_locked` → `playback_policy::syncplay::SyncPlay::step`; a
  Leave runs detached, other requests through the group's serial runner, and each step's update
  goes to the group's `SyncPlayListener`. Nothing reaches `ChangeListener`.
- **Enters (Kotlin):** `SyncPlayExecutor.listener.onStep` only queues; one Main consumer runs
  `reduce` for each event, also fed by the player through its `AttachedPort`
  (`PlaybackViewModel.mode = PlayerMode.Group`) and by two lifecycle hooks:
  `SyncPlayExecutor.leave` from `MainActivity.finish` (best-effort Leave) and
  `onEpochMoved` from `resetSessionState`. No root-level collector.
- **Fans out (Kotlin):** `reduce`'s effects, in order: core inputs through one serial queue,
  a group Load as a request to `playbackActivityTracker` (docs/17 §3), and `PortCall`s to the
  attached player.

### Playback session start / transition / stop

- **Start:** `PlaybackActivity.onCreate` builds `PlaybackViewModel`; its `init` mints the first
  request (`claimRequest(fresh = true)`), waits for `awaitAccountRestored`, then `start`.
  `start` adopts the plan through `adoptPlan` and sets `owner = SessionOwner.Playing(scope)`;
  every launch it makes is scoped to that `SessionScope.generation`.
- **Transition:** `PlaybackViewModel.transitionTo` (next-up, previous, next episode,
  still-watching continue) and `replaceItem` (`PlaybackActivity.onNewIntent`) both run
  `replaceWith`: the old session's stop lands (`stopPlaybackAndAwait`) before the next `start`.
- **Stop:** `PlaybackViewModel.endSessionForStop` (reported stop) or `abandonPlaybackOnce`
  (no report) move `owner` to `SessionOwner.Ended` exactly once; `launchFinalStop` runs on
  `AppGraph.processScope` so teardown cannot cancel it. Callers: `finishAfterStop` (Back, remote
  Stop), `finishAfterFatalPlaybackError`, `PlaybackActivity.onStop` / `onDestroy`,
  `PlaybackViewModel.onCleared`. Each final stop is counted on `playbackActivityTracker` from
  its launch until `stopPlayback` returns; `SyncPlayExecutor.create` waits for zero (docs/28 §3).
- **Core side:** `JellybeamCore::prepare_playback` admits the request against its epoch;
  `stop_playback` / `abandon_playback` end the reporting session.
- **Also notified:** `UpdateCoordinator.playbackChanged` from `PlaybackActivity.onCreate` /
  `onDestroy` and from a player start that doesn't run; `PlaybackActivityTracker` gets each instance's create and destroy, tagged with
  its generation (docs/17 §3).

### PiP enter / exit

- **Enters:** `PlaybackActivity.onPictureInPictureModeChanged` (entry is requested by
  `tryEnterPip` from `onUserLeaveHint` and the OSD's exit request).
- **Fans out:** `inPictureInPicture` (gates every overlay in `PlaybackScreen`),
  `PipEntered` / `PipExited` to `playbackActivityTracker` (its `reduce` closes a dismissal once
  and settles a waiting request), Coil memory-cache clear on enter.
  `sessionShouldSurviveStop` keeps the `MediaSessionHolder` and the session across a PiP stop.

### Re-authorization (401)

- **Enters:** `PlaybackActivity.kt` `AuthorizationRecoveryCoordinator`, one pending request at a
  time. Three posters: `RealCoreGateway.noteCoreFailure` → `request` (any `ffi(...)` call whose
  `CoreException` passes `routesToReauthorization`), the change listener's `onUnauthorized` →
  `request` (the installed bus's socket rejected its token, once per bus), and the player's
  `PlaybackEvent.ReauthorizationRequired` → `requestForFailedPlayback` (playback calls opt out of
  the gateway seam with `reauthorize = false`; the view model decides in `publishFailure`).
- **Fans out:** one collector in `JellybeamRoot`: `reauthorizationDecision` →
  `resolveReauthorization` → `showReauthorization`. A landed re-sign-in calls
  `RealCoreGateway.noteReauthorized` (quiet window) and moves the core epoch, which resets the tree.

## 2. State owners

| State | Owner (one writer) | Readers | Cleared on cancel / session end / account reset |
|---|---|---|---|
| Account epoch pair | `object.rs` `AccountSlot.epoch` / `PlaybackOwner::parked`; Kotlin copy `RealCoreGateway.epochs`, written only in `accountChange` | `AccountTreeOwner`, `PlaybackViewModel`, `RealCoreGateway.currentUrlBase` | never cleared; advanced by `reset_account_state_locked` |
| Installed account (client, server facts, mirror, bus, listener task) | `object.rs` `AccountSlot.installed`, replaced only by `reset_account_state_locked`; the bus bundle is opened by `open_mirror` through `installed_mut(epoch)` and stopped only by `stop_event_bus` | every core export via `AccountSlot::installed`; `server_info_snapshot` | — / stale results dropped by epoch / replaced in the same step |
| Watch Together group | `syncplay.rs` `Installed.syncplay` (`SyncPlayOwner`), changed only by `SyncPlayOwner::step_locked` under the state lock | `sync_play_snapshot`, the group's `SyncPlayListener` | results for a replaced epoch dropped / Idle releases jobs and listener / the outgoing group leaves in `dispose_account_reset` |
| Watch Together membership (Kotlin) | `SyncPlayExecutor.current`, written only by `create`/`join` and the executor's End; its state only by `reduce` on the executor's Main consumer; the player's side is `PlaybackViewModel.mode` (`PlayerMode`) | `MainActivity` hooks, `PlaybackViewModel` through `AttachedPort` | a cancelled entry ends and leaves / Idle or Detach ends it, the player back to solo / `onEpochMoved` or `AccountChanged` ends it |
| Per-account caches (Seerr handle, collections, admin) | `object.rs` `Installed.caches` via `store_if_epoch` | `read_if_epoch`, `require_seerr_handle` | late stores dropped / — / replaced wholesale |
| Library list | `RealCoreGateway._viewsState` (`refreshViews`; emptied when `openMirror` fails) | `JellybeamRoot`, `HomeFeed`, `LaunchWarmup` | stale read dropped by epoch / — / re-read by the next `openMirror` |
| Change events | `RealCoreGateway._changeEvents` (`DROP_OLDEST`, no replay) | every `ChangeRefreshScheduler` | — / — / the core rebinds the listener to the new mirror |
| Seerr revision | `RealCoreGateway.bumpSeerrRevision` | Discover view models, drawer | never cleared; drawer answer reset in `resetSessionState` |
| Settings record | `SettingsStore.update` (persists through `JellybeamCore::set_settings`) | `SettingsViewModel`, `PlaybackViewModel` | — / — / not account-scoped |
| Pending 401 | `AuthorizationRecoveryCoordinator.pending` | `JellybeamRoot` | `consume` / — / `noteReauthorized` |
| Playback request | `PlaybackViewModel.claimRequest` → `currentRequest`, `accountEpoch` | `adoptPlan`, `publishFailure` | `retireRequests` / `retireRequests` / `closeForAccountChange` |
| Reporting session | `PlaybackViewModel.owner` (`SessionOwner`) | every `SessionScope.ifCurrent` | `endSessionForStop`, `abandonPlaybackOnce` / same / `closeForAccountChange` |
| Core reporting session | `object.rs` `State.reporting`, `PlaybackOwner::claim` | `report_position`, `stop_playback` | `release_claim` / `stop_playback`, `abandon_playback` / `end_playback_locked` (leave) or parked |
| Warm-up results | `LaunchWarmup` slots | `JellybeamRoot`, `HomeFeed` | — / taken once / `LaunchWarmup.discard` |
| Back stack, per-screen view models | `JellybeamRoot.navigate`; the epoch-keyed restore effect sets the first stack; `resetSessionState` blanks it | `RetainedScreenLayer` | — / — / `resetSessionState` clears stores and stack |
| Player presence and play requests (slot, generations, waiting request) | `PlaybackActivityTracker`, written only by `send` → `reduce` on Main | `MainActivity`, `JellybeamRoot`, `UpdateCoordinator`, `WatchTogetherViewModel` | a newer request replaces it / the player's own `Destroyed` / `AccountReset` drops the request, never closes a player |
| Focus record | `FocusMemory` (`lastKey`, `invokerKey`; `frozen` derived) | `FocusRestorer`, `reenterNow` | `withPlacement` token released / — / stores cleared |
| Refresh-restore job | `RefreshRestoreOwner` | screen restore effects | `ownershipLost` / — / composition left |
| Root re-entry seat | `RetainedScreenLayer` → `RootFocus.seat` | `MainActivity.dispatchKeyEvent` | disposed with the layer |

## 3. How to extend

**Add a setting.** Field plus `default_*` fn on `settings.rs` `Settings`; the UI row in
`SettingsSections.kt` calls a `SettingsViewModel` method that goes through `updateSettings` →
`SettingsStore.update`. A reader that must see later changes collects `SettingsStore.settings`;
a one-shot reader calls `SettingsStore.load`. A core-side consequence (Next Up options, preload)
goes in `JellybeamCore::set_settings`, never in Kotlin.

**Add a screen that refreshes on change.** Give the view model an `active` `MutableStateFlow`
and one `ChangeRefreshScheduler(events = gateway.changeEvents(), affects = ..., active = ...,
refresh = ...)`; the screen calls `ActiveWhileTop(isTop, viewModel::setActive)`,
`FocusRestorer(memory, isTop, ...)`, and, if a refresh can drop the focused item,
`rememberRefreshRestoreOwner(isTop)` with `refreshGuardEligible`. A change the event stream does
not carry calls `ChangeRefreshScheduler.markDirty`. Add the `Screen` case to `Screen.kt` and its
branch in `JellybeamRoot`.

**Add an account-scoped cache.** Core: a field on `AccountCaches`, written only through
`JellybeamCore::store_if_epoch` with the epoch captured alongside the client the fetch used,
read through `read_if_epoch`. `reset_account_state_locked` already replaces it. Kotlin: key a
cache by `CoreGateway.accountEpochs` epoch (as `RealCoreGateway.currentUrlBase` does), or keep it
in a view model and let `resetSessionState` clear the store.

**Add an FFI call (Rust decides, Kotlin executes).** A `pub fn` in the `#[uniffi::export] impl
JellybeamCore` block of `object.rs` (or a feature file such as `seerr.rs`), with its decision in
a pure function and a Rust test. Then the `CoreGateway` interface method, the
`RealCoreGateway` override through `ffi("ffi.<name>")` (timing, `ffi.error` diag and 401
routing come with it), and the `FakeCoreGateway` stub in `app/src/test`. A call that changes
the account goes through `accountChange`.

**Add a playback event.** A case on `PlaybackEvent`, emitted with `_events.tryEmit` from
`PlaybackViewModel`. An event that closes the player is handled in
`PlaybackActivity.installPlaybackUiAfterPlayerLoad`'s collector, which runs for the Activity's
whole life; anything else in `PlaybackScreen`'s `viewModel.events` collector. Both `when`s are
exhaustive, so a new case forces the choice.

**Add an OSD action / overlay.** A case on `OpenSurface`, opened with `PlaybackViewModel.open`,
updated with `replaceSurface`, closed with `closeSurface`; `cancelSessionJobsAndCloseMenus`
closes it at session end. Visibility and auto-hide stay in `PlaybackOsdController`. A transport
key goes through `MediaKeys.route` on screen and `MediaKeyRouter` for the `MediaSessionHolder`.

**Add a focus target.** `Modifier.focusKey(memory, key)` with a stable key on the target;
`FocusRestorer`'s `selectedKey` / `fallback` decide where focus lands on becoming top. A modal
inside a page passes `modal`; a surface that owns D-pad Left sets
`LocalDrawerLeftEdgeSuppressed`; a screen under `NavDrawerHost` wires
`LocalDrawerFocusCoordinator` to `RefreshRestoreOwner.ownershipLost`. A full-screen surface that
replaces the stack is a branch in `JellybeamRoot`'s `when`, not a new layer type.

## 4. Rules of thumb

- **A signal enters once and fans out in its owner.** A second observer of the same lifecycle
  event is a bug waiting for an ordering difference; extend the owner's fan-out instead.
- **Derive state instead of copying it.** Read `isTop`, `accountEpochs`, `viewsState` or
  `SettingsStore.settings` where needed; a copy needs a writer per change and drifts.
- **Delete a duplicate path rather than guarding it.** An exactly-once guard behind three
  callers is a backstop, not a design; keep one caller per real case and say which case.
- **A new owner, gate or token type needs a reason.** Before adding one, show that no existing
  owner (`AccountTreeOwner`, `ChangeRefreshScheduler`, `RefreshRestoreOwner`, `SessionOwner`,
  `AuthorizationRecoveryCoordinator`, `store_if_epoch`) can carry it.
