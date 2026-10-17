//! `JellybeamCore` -- the v0.1 app-facing UniFFI object: sign in, persist/restore
//! a session, open the mirror (starts sync automatically), and serve
//! coarse-grained snapshot queries (docs/01-architecture.md risk #2: no
//! fine-grained per-field FFI calls).
//!
//! Exported methods are blocking unless documented otherwise -- either no I/O
//! (reads off `media_cache::Mirror`'s synced state) or `block_on` to
//! completion; Kotlin calls them from `Dispatchers.IO`. `preload_playback` is
//! the exception: it only replaces a bounded background task and returns.

use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use crate::error::CoreError;
use crate::home::{HomeLayout, HomeSnapshot};
use crate::library_prefs::LibraryGridPrefsFile;
use crate::settings::{PlaybackQuality, Settings};
use crate::track_prefs::TrackPrefsFile;
use crate::types::{
    AccountIdentity, AccountInfo, BrowseRead, Card, ChangeEvent, CollectionInfo, DeviceCaps,
    EpisodeNeighbors, FailedTrackFfi, GridCounts, GridFilters, GridGroup, GridSort, ImageKind,
    ImageUrlPrefix, ItemDetail, LibraryGridPrefs, LiveSort, MediaSegment, MirrorItemCounts,
    MirrorLibrary, MirrorStats, PlayMethodFfi, PlaybackOsdDetail, PlaybackPlan, PlaybackRequest,
    QuickConnectSession, ServerDetails, ServerInfoSnapshot, SortOrder, SyncStatus,
    TrackDecisionFfi, TrackInfo, TrackKindFfi, TrackMemoryFfi, ViewSnapshot,
};
use crate::{device_id, library_prefs, session, settings, signin, track_prefs};

/// Foreign-implemented sink for mirror change notifications. Kotlin hands an
/// instance to [`JellybeamCore::set_change_listener`]; `on_change` is called from
/// a background task on `JellybeamCore`'s own runtime, never the caller's thread.
#[uniffi::export(foreign)]
pub trait ChangeListener: Send + Sync {
    fn on_change(&self, event: ChangeEvent) -> Result<(), crate::error::CallbackError>;
    /// docs/18 §2.1: the installed bus's socket rejected `account`'s token, at most once per bus;
    /// called on a runtime thread that a core teardown may be joining, so it must not call the core.
    fn on_unauthorized(&self, account: AccountIdentity) -> Result<(), crate::error::CallbackError>;
}

/// Local resume checkpoints are independent of Jellyfin's network progress
/// cadence: commit the first useful position immediately, then throttle to
/// the same ten-second scale as server reports. Android force-stop runs no
/// lifecycle callback, so these are the only reliable progress record.
const LOCAL_PROGRESS_CHECKPOINT_INTERVAL: Duration = Duration::from_secs(10);

/// docs/19-detail-action-menu.md §1/§2.3: [`JellybeamCore::list_collections`]'s
/// cache TTL -- one live BoxSet list per session, re-read after ten minutes.
const COLLECTIONS_CACHE_TTL: Duration = Duration::from_secs(10 * 60);

/// docs/13 Server compatibility: how often [`JellybeamCore::spawn_server_version_poll`] refreshes
/// `/System/Info/Public`, the backstop beside each bus `Connected` refresh; shortened under `cfg(test)`.
#[cfg(not(test))]
const SERVER_VERSION_REFRESH_INTERVAL: Duration = Duration::from_secs(5 * 60);
#[cfg(test)]
const SERVER_VERSION_REFRESH_INTERVAL: Duration = Duration::from_millis(200);

#[derive(Default)]
struct LocalProgressCheckpoint {
    last_commit_at: Option<Instant>,
}

impl LocalProgressCheckpoint {
    fn should_commit(&mut self, position_ticks: i64, now: Instant) -> bool {
        if position_ticks <= 0 {
            return false;
        }
        let due = self.last_commit_at.is_none_or(|last| {
            now.saturating_duration_since(last) >= LOCAL_PROGRESS_CHECKPOINT_INTERVAL
        });
        if due {
            self.last_commit_at = Some(now);
        }
        due
    }

    fn reset(&mut self) {
        self.last_commit_at = None;
    }
}

/// How long a [`PreloadCache`] entry stays usable by
/// [`JellybeamCore::prepare_playback`] after [`JellybeamCore::preload_playback`]
/// populates it: long enough to cover a dwell-then-press, short enough that
/// stale resume/server state is unlikely -- not a long-lived cache.
const PRELOAD_CACHE_TTL: Duration = Duration::from_secs(30);

/// docs/18 §2: a repeated dwell trigger renegotiates a cache this old, so a long dwell re-armed
/// every TTL never sits on a dead entry; Play still consumes one until [`PRELOAD_CACHE_TTL`].
const PRELOAD_REFRESH_AFTER: Duration = Duration::from_secs(PRELOAD_CACHE_TTL.as_secs() / 2);

/// docs/18 §2.1: playback ownership. A request is admitted under the lock, and only the admitted
/// claim may install; a failed request's claim is inert, since the next admission replaces it.
#[derive(Default)]
struct PlaybackOwner {
    /// Highest admitted [`PlaybackRequest::seq`]; an equal or lower one is refused.
    highest_seq: u64,
    /// The admitted request still allowed to install, cleared by install, revocation and reset.
    claim: Option<PlaybackClaim>,
    /// The session a pending fallback took over. It keeps reporting (and its stream keeps
    /// playing) until the claim resolves, so it is stopped where the viewer actually left it.
    retiring: Option<Retiring>,
    /// The session the installed one replaced: a stop sent before the caller learned the new id
    /// still names it, and ends the installed session where the viewer left.
    installed_replaces: Option<String>,
    /// The account a live playback kept across an account change, with the epoch its own requests
    /// still carry (next-up included, which stops before it prepares); cleared when a playback on
    /// the current account installs or its own account is left.
    parked: Option<ParkedAccount>,
}

/// docs/18 §2.1: how long the logout waits for the account's final reports; a Stopped still
/// retrying after this would be refused once the token is revoked anyway.
#[cfg(not(test))]
const FINAL_REPORT_SETTLE_BUDGET: Duration = Duration::from_secs(5);
#[cfg(test)]
const FINAL_REPORT_SETTLE_BUDGET: Duration = Duration::from_secs(3);

/// Resolves when one session's final Stopped report has landed, given up or been cancelled.
type FinalSettled = tokio::sync::oneshot::Receiver<()>;

/// [`PlaybackOwner::parked`].
struct ParkedAccount {
    epoch: u64,
    account: SessionAccount,
}

impl ParkedAccount {
    /// Whether it is the saved account `mirror_dir` names.
    fn is_account(&self, mirror_dir: Option<&str>) -> bool {
        self.account.mirror_dir.as_deref() == mirror_dir
    }

    /// Its account, as installed now when that is the same saved account again (a re-sign-in's
    /// fresh token, a switch back).
    /// A field the installed one lacks (a mirror still opening after a switch back) stays its own.
    fn resolve(&self, state: &State) -> SessionAccount {
        let installed_dir = state.account.installed().map(|i| i.mirror_dir.as_str());
        if self.account.mirror_dir.is_some() && self.is_account(installed_dir) {
            self.account.refreshed_by(SessionAccount::of(state))
        } else {
            self.account.clone()
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
struct PlaybackClaim {
    seq: u64,
    /// The account epoch it was minted under, which names the account it installs on.
    epoch: u64,
    /// The session a fallback took over on admission, so stopping or abandoning it revokes.
    retires: Option<String>,
}

/// [`PlaybackOwner::retiring`]: the claim it belongs to and the fallback's position, where the
/// replacement takes over. Its stream keeps playing until then, and its own reports keep arriving.
struct Retiring {
    seq: u64,
    session: jellyfin_core::ReportingSession,
    ticks: i64,
    /// The latest position reported for it since the fallback took it.
    reported: Option<i64>,
}

impl Retiring {
    /// Where the viewer is: its final position when it ends without a replacement taking over.
    fn left_at(&self) -> i64 {
        self.reported.unwrap_or(self.ticks)
    }
}

/// The account a taken session belongs to, read under the lock that took it, so its final writes
/// (mirror position) never reach an account that replaced it.
#[derive(Clone)]
struct SessionAccount {
    mirror: Option<media_cache::Mirror>,
    client: Option<jellyfin_api::JellyfinClient>,
    /// Which saved account this is, so removing it can end its playback.
    mirror_dir: Option<String>,
}

impl SessionAccount {
    /// `newer` where it has a field, else this one's: an account mid-switch has a client before
    /// its mirror opens.
    fn refreshed_by(&self, newer: Self) -> Self {
        Self {
            mirror: newer.mirror.or_else(|| self.mirror.clone()),
            client: newer.client.or_else(|| self.client.clone()),
            mirror_dir: newer.mirror_dir.or_else(|| self.mirror_dir.clone()),
        }
    }

    /// Its client and open mirror, read together, or why it has none.
    fn require_open(
        self,
    ) -> Result<(jellyfin_api::JellyfinClient, media_cache::Mirror), CoreError> {
        let client = self.client.ok_or(CoreError::NotSignedIn)?;
        Ok((client, self.mirror.ok_or(CoreError::MirrorNotOpen)?))
    }

    fn of(state: &State) -> Self {
        let installed = state.account.installed();
        Self {
            mirror: state.account.bundle().map(|g| g.mirror.clone()),
            client: installed.map(|i| i.client.clone()),
            mirror_dir: installed.map(|i| i.mirror_dir.clone()),
        }
    }
}

/// docs/18 §2.1 / docs/19 §2.3: per-account caches, replaced wholesale at every account change;
/// a fetch stores into them only through [`JellybeamCore::store_if_epoch`].
#[derive(Default)]
pub(crate) struct AccountCaches {
    /// The live Seerr Discover connection, built lazily on first use (docs/14 "zero startup
    /// cost").
    pub(crate) seerr: Option<crate::seerr::SeerrHandle>,
    /// `(fetched_at, collections)`, re-fetched by [`JellybeamCore::list_collections`] after ten
    /// minutes.
    collections: Option<(Instant, Vec<CollectionInfo>)>,
    /// [`JellybeamCore::is_administrator`]'s answer, cached only when the server stated it.
    is_admin: Option<bool>,
}

/// docs/18 §2.1: the installed account and its epoch; [`JellybeamCore::reset_account_state_locked`]
/// is the only writer of either, and the epoch outlives the account so a removal still moves it.
#[derive(Default)]
pub(crate) struct AccountSlot {
    epoch: u64,
    installed: Option<Installed>,
}

impl AccountSlot {
    pub(crate) fn installed(&self) -> Option<&Installed> {
        self.installed.as_ref()
    }

    /// The installed account's client with the epoch a result fetched with it is tagged by.
    fn current_client(&self) -> Option<(u64, jellyfin_api::JellyfinClient)> {
        Some((self.epoch, self.installed.as_ref()?.client.clone()))
    }

    /// The installed account's open bundle; `None` while signed out or Closed.
    fn bundle(&self) -> Option<&BusBundle> {
        self.installed.as_ref()?.bundle.as_ref()
    }

    /// docs/18 §2.1: the installed account only while `epoch` is still current -- the one gate a
    /// result tagged with the epoch it was fetched under passes.
    fn installed_mut(&mut self, epoch: u64) -> Option<&mut Installed> {
        self.installed.as_mut().filter(|_| self.epoch == epoch)
    }
}

/// docs/18 §2.1: one saved account as installed -- its client and everything known about it.
pub(crate) struct Installed {
    account: AccountInfo,
    device_id: String,
    mirror_dir: String,
    client: jellyfin_api::JellyfinClient,
    /// docs/13 Server compatibility: last-known version and name, seeded offline from the saved
    /// record; `None` is "unknown", never "old server".
    server_version: Option<jellyfin_api::ServerVersion>,
    server_name: Option<String>,
    pub(crate) caches: AccountCaches,
    /// docs/18 §2.1: its open mirror and bus; `None` is Closed. Taken whole, never dropped under
    /// the state lock: [`JellybeamCore::stop_event_bus`] is its one teardown.
    bundle: Option<BusBundle>,
}

impl Installed {
    /// `saved` installed with `client`, its caches empty and its facts as saved.
    fn of(saved: &session::SessionFile, client: jellyfin_api::JellyfinClient) -> Self {
        Self {
            account: AccountInfo::of(saved),
            device_id: saved.device_id.clone(),
            mirror_dir: saved.mirror_dir.clone(),
            client,
            server_version: saved.server_version.as_deref().and_then(|v| v.parse().ok()),
            server_name: saved.server_name.clone(),
            caches: AccountCaches::default(),
            bundle: None,
        }
    }

    fn identity(&self) -> AccountIdentity {
        AccountIdentity {
            server_url: self.account.server_url.clone(),
            user_id: self.account.user_id.clone(),
        }
    }
}

/// [`JellybeamCore::installed_identity`]'s result: the installed account, stamped with its epoch.
pub(crate) struct InstalledIdentity {
    pub(crate) epoch: u64,
    pub(crate) account: AccountIdentity,
}

/// docs/18 §2.1: the epochs Kotlin re-reads after every account call, read under one lock so
/// they always describe the same instant.
#[derive(uniffi::Record, Debug, Clone, PartialEq, Eq)]
pub struct AccountEpochs {
    /// The current account's epoch, the one a [`PlaybackRequest`] must carry.
    pub epoch: u64,
    /// The epoch a live playback kept across an account change still owns playback under.
    pub parked: Option<u64>,
}

/// The core's [`State`], reachable only through [`StateGuard`] so no lock can skip its release.
pub(crate) struct SharedState(Mutex<State>);

impl SharedState {
    fn lock(&self) -> StateGuard<'_> {
        StateGuard(self.0.lock().unwrap_or_else(|e| e.into_inner()))
    }
}

/// What [`JellybeamCore::prepare_playback`] read under the lock that admitted its claim.
struct AdmittedPlay {
    client: jellyfin_api::JellyfinClient,
    mirror: media_cache::Mirror,
    caps: jellyfin_core::AndroidTvCaps,
    tolerate_mislabeled_levels: bool,
    quality: PlaybackQuality,
    preload: PreloadForPlay,
}

/// [`SharedState::lock`]'s guard. Releasing it re-derives the shared sync-yield flag from the
/// playback state it leaves (docs/18 §2.1), so no path can end a playback with sync paused.
pub(crate) struct StateGuard<'a>(std::sync::MutexGuard<'a, State>);

impl std::ops::Deref for StateGuard<'_> {
    type Target = State;

    fn deref(&self) -> &State {
        &self.0
    }
}

impl std::ops::DerefMut for StateGuard<'_> {
    fn deref_mut(&mut self) -> &mut State {
        &mut self.0
    }
}

impl Drop for StateGuard<'_> {
    fn drop(&mut self) {
        let live = JellybeamCore::playback_live_locked(&self.0);
        self.0
            .playback_active
            .store(live, std::sync::atomic::Ordering::Release);
    }
}

/// Which account boundary [`JellybeamCore::reset_account_state_locked`] is crossing.
#[derive(Clone, Copy, PartialEq, Eq)]
enum AccountChange {
    /// Sign-in, switch, restore: a live playback keeps its own account (docs/18 §2.1).
    Replace,
    /// Sign-out or removal of the active account: ends playback that belongs to it.
    Leave,
}

/// The session a pending fallback took over, if `play_session_id` names it.
fn retiring_session_locked<'a>(
    state: &'a mut State,
    play_session_id: &str,
) -> Option<&'a mut Retiring> {
    state
        .playback_owner
        .retiring
        .as_mut()
        .filter(|retiring| retiring.session.context().play_session_id == play_session_id)
}

/// The installed session if `play_session_id` names it; anything else is a lost race, ignored.
fn installed_session_locked<'a>(
    state: &'a mut State,
    play_session_id: &str,
) -> Option<&'a mut jellyfin_core::ReportingSession> {
    let session = state
        .reporting
        .as_mut()
        .filter(|session| session.context().play_session_id == play_session_id);
    if session.is_none() {
        tracing::debug!(
            play_session_id,
            "progress report for a session that is not installed; ignoring"
        );
    }
    session
}

/// What [`JellybeamCore::take_session_or_revoke_locked`] took for a stop/abandon.
enum Taken {
    /// The installed session.
    Active(jellyfin_core::ReportingSession),
    /// A pending fallback's retired session, with the fallback's position.
    Retired(jellyfin_core::ReportingSession, i64),
}

/// What [`JellybeamCore::reset_account_state_locked`] hands out to dispose of outside the lock.
struct AccountReset {
    preload_task: Option<tokio::task::JoinHandle<()>>,
    reporting: Option<jellyfin_core::ReportingSession>,
    retiring: Option<Retiring>,
    /// The outgoing account, so its sessions' final writes stay with it.
    account: SessionAccount,
    /// The outgoing account's bundle, torn down after the final reports start.
    bundle: Option<BusBundle>,
    /// docs/13 Server compatibility: the incoming account's epoch and client, whose facts are
    /// refreshed last.
    refresh: Option<(u64, jellyfin_api::JellyfinClient)>,
}

/// One-slot cache of a focus-dwell preload's negotiation result. Only ever
/// holds a [`ResolvedPlan::DirectPlay`] outcome (docs/18-playback-quality.md
/// §2: "preload_playback caches DirectPlay plans only") -- a `Transcode`
/// result's `url` embeds the negotiation's start position, so it isn't safe
/// to reuse the way a Direct Play url is.
#[derive(Clone)]
struct PreloadCache {
    /// The request that produced it: [`State::preload_publish`] gates on it, and
    /// [`State::preload_take_joined`] clears the slot copy of the generation a joiner consumed.
    generation: u64,
    item_id: String,
    source: jellyfin_api::models::MediaSourceInfo,
    url: String,
    /// `PlaybackInfoResponse::play_session_id`, already unwrapped to the
    /// same empty-string convention the uncached path uses.
    play_session_id: String,
    /// Carried from the [`ResolvedPlan::DirectPlay`] this entry was built
    /// from, so a cache-hit [`PlaybackPlan`] shows the same `server_verdict`
    /// a fresh negotiation would have.
    server_verdict: Option<String>,
    created_at: Instant,
}

/// Complete immutable input for one speculative negotiation. Keeping the
/// newest request in [`PreloadTask::pending`] lets rapid focus changes
/// coalesce without starting concurrent server work.
struct PreloadRequest {
    generation: u64,
    item_id: String,
    client: jellyfin_api::JellyfinClient,
    mirror: media_cache::Mirror,
    caps: jellyfin_core::AndroidTvCaps,
    tolerate_mislabeled_levels: bool,
    /// `Settings::playback_quality` at the moment this negotiation started;
    /// see [`Self::build_preload_cache`]'s use of `resolve_plan`.
    quality: PlaybackQuality,
}

impl PreloadCache {
    fn age(&self, now: Instant) -> Duration {
        now.saturating_duration_since(self.created_at)
    }

    /// Whether Play may consume it for `item_id`.
    fn serves(&self, item_id: &str, now: Instant) -> bool {
        self.item_id == item_id && self.age(now) < PRELOAD_CACHE_TTL
    }
}

/// docs/18 §2: the one speculative worker, bounding focus-driven negotiation to one active
/// server request however many cancelled coroutines unwind late. It lives in
/// [`State::preload_task`] exactly while a negotiation is outstanding.
struct PreloadTask {
    handle: tokio::task::JoinHandle<()>,
    /// The item the running negotiation is for, which is all a Play may join.
    running_item: String,
    /// The running request's; a result is published only while it still names the task.
    generation: u64,
    /// The newest target asked for meanwhile, replaced in place so intermediate cards never queue.
    pending: Option<PreloadRequest>,
    /// The running negotiation's result for a joining Play: set by [`State::preload_publish`],
    /// and dropped (waking every joiner) when the negotiation ends however it ends.
    settled: tokio::sync::watch::Sender<Option<PreloadCache>>,
    /// Set by the Play that joined: the result is single-use, so no second Play may share it.
    joined: bool,
    /// The item the newest trigger or Play named, which [`State::preload_publish`] weighs against
    /// the slot to decide whether this negotiation's result is still wanted there.
    newest_target: String,
}

/// A Play's hold on the negotiation it joined: the result of that generation alone.
struct PreloadJoin {
    settled: tokio::sync::watch::Receiver<Option<PreloadCache>>,
    /// [`State::preload_epoch`] at the join; a later invalidation voids the result.
    epoch: u64,
}

/// What [`State::preload_take_for_play`] hands [`JellybeamCore::prepare_playback`].
#[derive(Default)]
struct PreloadForPlay {
    /// A cache that serves this item, single-use.
    cached: Option<PreloadCache>,
    /// The running negotiation for this very item, awaited instead of renegotiating.
    join: Option<PreloadJoin>,
    /// A speculative worker for anything else, aborted outside the lock.
    abort: Option<tokio::task::JoinHandle<()>>,
}

/// What [`State::preload_publish`] tells the worker to do next.
enum PreloadNext {
    /// The slot moved on without this request; stop without touching it.
    Stale,
    /// Nothing more was asked for.
    Idle,
    /// The newest target asked for meanwhile.
    Run(PreloadRequest),
}

/// Clears the slot if the worker dies without publishing (a panic), so a dead task never
/// coalesces later triggers away.
struct PreloadExit {
    shared: Arc<SharedState>,
    generation: u64,
}

impl Drop for PreloadExit {
    fn drop(&mut self) {
        let mut state = self.shared.lock();
        if state
            .preload_task
            .as_ref()
            .is_some_and(|task| task.generation == self.generation)
        {
            state.preload_task = None;
        }
    }
}

/// One open mirror with its own event relay: everything a real `jellyfin_core::EventBus`
/// emits for that mirror's client is copied here and nowhere else, and `Mirror::open`
/// subscribes on `tx`, so two accounts' bundles briefly alive at once can never
/// cross-deliver a `UserDataChanged` between them.
struct BusBundle {
    mirror: media_cache::Mirror,
    /// The listener's forwarding task over `mirror`, if a listener was bound at commit or since.
    listener_task: Option<tokio::task::JoinHandle<()>>,
    /// Per-bundle relay every event the real bus emits is copied onto; the
    /// mirror's receiver is subscribed here. Kept alive here (not only in the
    /// forwarder) so the mirror's receiver never sees `Closed` while the
    /// bundle lives, including the disabled-bus test path. Also lets tests
    /// subscribe/send directly to prove the account-isolation this struct
    /// exists for.
    #[allow(dead_code)]
    tx: tokio::sync::broadcast::Sender<jellyfin_core::BusEvent>,
    /// docs/13 About > server info: the supervisor's connection watch, which `live_events_connected`
    /// reads via `borrow()` so a lagging broadcast consumer can never lose it.
    connection: tokio::sync::watch::Receiver<jellyfin_core::BusConnection>,
    handle: Option<jellyfin_core::EventBusHandle>,
    forwarder: Option<tokio::task::JoinHandle<()>>,
}

/// Mutable state behind a `Mutex` so `JellybeamCore` itself can be `Sync`
/// (required of a `#[derive(uniffi::Object)]` type). `Mirror`/`JellyfinClient`
/// are cheap-clone `Arc` handles, so methods clone out of the lock rather
/// than holding the guard across a `block_on`/foreign call.
#[derive(Default)]
pub(crate) struct State {
    /// docs/18 §2.1: the installed account; every "is this the installed account" decision keys
    /// on it, never the saved list's `active`. `pub(crate)` so `seerr.rs` can reach its caches.
    pub(crate) account: AccountSlot,
    /// Kept apart from any bundle so a listener registered before
    /// [`JellybeamCore::open_mirror`] is still bound once the mirror opens, and
    /// rebinds automatically across a removal + fresh sign-in.
    listener: Option<Arc<dyn ChangeListener>>,
    /// Set by [`JellybeamCore::set_device_caps`]; read by
    /// [`JellybeamCore::prepare_playback`]. Never called means
    /// `jellyfin_core::AndroidTvCaps::default()`'s conservative floor.
    caps: jellyfin_core::AndroidTvCaps,
    /// The reporting session for the item [`JellybeamCore::prepare_playback`]
    /// most recently returned a Direct Play plan for, if any. Replaced
    /// (previous session `abandon`-ed) by the next successful call, cleared
    /// by stop/abandon.
    reporting: Option<jellyfin_core::ReportingSession>,
    /// Throttles durable mirror progress writes while [Self::reporting] is
    /// active. Reset for every new/ended reporting session.
    local_progress_checkpoint: LocalProgressCheckpoint,
    /// Loaded once at construction (tolerant load, defaults if nothing
    /// saved) and kept in sync by [`JellybeamCore::set_settings`]. Every setting
    /// side effect is applied off this copy, not re-read from disk.
    /// `pub(crate)` so `still_watching.rs`'s sibling `impl JellybeamCore` block
    /// can read `Settings::still_watching` through [`JellybeamCore::lock_state`].
    pub(crate) settings: Settings,
    /// Per-series audio/subtitle track memory, loaded once (tolerant load)
    /// and kept in sync by [`JellybeamCore::remember_track_memory`]; read by
    /// [`JellybeamCore::resolve_tracks`].
    track_prefs: TrackPrefsFile,
    track_prefs_revision: u64,
    /// docs/16-library-sort-filter.md §5: per-view library grid sort/filter
    /// state, loaded once (tolerant load; a missing id reads as
    /// [`LibraryGridPrefs::default`]) and kept in sync by
    /// [`JellybeamCore::set_library_grid_prefs`].
    library_grid_prefs: LibraryGridPrefsFile,
    /// Bumped by every [`JellybeamCore::set_library_grid_prefs`] call under this
    /// lock, handed to [`JellybeamCore::persist_library_grid_prefs`] as the
    /// snapshot's identity -- ordering the disk write by this counter (not by
    /// holding the lock across the write) keeps a slow writer from clobbering
    /// a newer one.
    library_grid_prefs_revision: u64,
    /// Bumped under the state lock by each [`JellybeamCore::set_settings`]; orders the disk write.
    settings_revision: u64,
    /// docs/18 §2: [`JellybeamCore::preload_playback`]'s one-slot result cache, consumed
    /// (taken, never merely read) by [`JellybeamCore::prepare_playback`]. It outlives a refresh
    /// until the refreshed entry publishes, and is cleared at every session-swap point so one
    /// account's preload is never consumed by another.
    preload_cache: Option<PreloadCache>,
    /// docs/18 §2.1: which playback request may install the next reporting session.
    playback_owner: PlaybackOwner,
    /// docs/18 §2.1: the sync-yield flag every mirror this core opens shares, kept equal to
    /// [`JellybeamCore::playback_live_locked`] by [`StateGuard`].
    playback_active: Arc<std::sync::atomic::AtomicBool>,
    /// docs/18 §3.2: the installed session's sidecar URLs for
    /// [`JellybeamCore::fetch_external_subtitle`]; cleared with the account.
    sidecars: Option<crate::types::SessionSidecars>,
    /// The negotiation outstanding, if any; a speculative one is never awaited by anything but
    /// a Play for its own item.
    preload_task: Option<PreloadTask>,
    /// Bumped by every [`Self::preload_invalidate`], so a joined result outlives no invalidation.
    preload_epoch: u64,
    /// The last [`PreloadRequest::generation`] handed out; unique per request, so a task that
    /// outlived its slot can never publish into the next one, and never reset.
    preload_generation: u64,
    /// "Still watching?" inactivity counters for the current play-next chain
    /// (docs/feature-dev/spec-still-watching-and-lan-discovery.md Feature A).
    /// Lives only as long as this `JellybeamCore` instance's current playback
    /// session; not reset on session-swap since
    /// `still_watching::JellybeamCore::note_player_input` resets it at every new
    /// player-session construction anyway. `pub(crate)` for the same
    /// cross-module reason as [`Self::seerr`].
    pub(crate) still_watching: playback_policy::still_watching::InactivityState,
}

impl State {
    /// docs/18 §2: a dwell trigger for `request.item_id`. Returns without work while a cache
    /// younger than [`PRELOAD_REFRESH_AFTER`] serves the item or its negotiation already runs;
    /// otherwise starts `spawn` when idle, or replaces the pending target of the running one.
    /// A cache for the item is kept until its refresh publishes; one for another item is dropped.
    fn preload_request(
        &mut self,
        request: PreloadRequest,
        now: Instant,
        spawn: impl FnOnce(PreloadRequest) -> tokio::task::JoinHandle<()>,
    ) {
        match self.preload_cache.as_ref() {
            Some(cache) if cache.item_id == request.item_id => {
                if cache.age(now) < PRELOAD_REFRESH_AFTER {
                    // Back on a cached item: whatever else runs is no longer wanted, and
                    // [`Self::preload_publish`] won't let it overwrite this item's cache.
                    if let Some(task) = self.preload_task.as_mut() {
                        task.pending = None;
                        task.newest_target.clone_from(&request.item_id);
                    }
                    return;
                }
            }
            _ => self.preload_cache = None,
        }
        if let Some(task) = self.preload_task.as_mut() {
            // Back on the running item: a newer target for another card is no longer wanted.
            task.newest_target.clone_from(&request.item_id);
            task.pending = (task.running_item != request.item_id).then_some(request);
            return;
        }
        let running_item = request.item_id.clone();
        let generation = request.generation;
        let (settled, _) = tokio::sync::watch::channel(None);
        self.preload_task = Some(PreloadTask {
            handle: spawn(request),
            newest_target: running_item.clone(),
            running_item,
            generation,
            pending: None,
            settled,
            joined: false,
        });
    }

    /// docs/18 §2: what Play for `item_id` takes from the slot. A serving cache is consumed; the
    /// running negotiation for this very item is joined; anything else speculative is aborted, so
    /// Play never waits on another item's reply.
    fn preload_take_for_play(
        &mut self,
        item_id: &str,
        on_current: bool,
        now: Instant,
    ) -> PreloadForPlay {
        let cached = self
            .preload_cache
            .take()
            .filter(|cache| on_current && cache.serves(item_id, now));
        let joinable = self
            .preload_task
            .as_mut()
            .filter(|task| on_current && cached.is_none() && task.running_item == item_id);
        if let Some(task) = joinable {
            task.pending = None;
            task.newest_target.clone_from(&task.running_item);
            // The result is single-use: a second Play negotiates on its own, and must not abort
            // the task the first is awaiting.
            let join = (!std::mem::replace(&mut task.joined, true)).then(|| PreloadJoin {
                settled: task.settled.subscribe(),
                epoch: self.preload_epoch,
            });
            return PreloadForPlay {
                cached: None,
                join,
                abort: None,
            };
        }
        PreloadForPlay {
            cached,
            join: None,
            abort: self.preload_task.take().map(|task| task.handle),
        }
    }

    /// What a Play that joined a negotiation reads once it ends: that generation's own result if
    /// it serves `item_id` and no invalidation came between, never whatever the slot holds now.
    /// The slot's copy of the same generation goes with it, so the result stays single-use.
    fn preload_take_joined(
        &mut self,
        join: &PreloadJoin,
        item_id: &str,
        now: Instant,
    ) -> Option<PreloadCache> {
        if join.epoch != self.preload_epoch {
            return None;
        }
        let joined = join.settled.borrow().clone()?;
        if self
            .preload_cache
            .as_ref()
            .is_some_and(|cache| cache.generation == joined.generation)
        {
            self.preload_cache = None;
        }
        Some(joined).filter(|cache| cache.serves(item_id, now))
    }

    /// docs/18 §2: the worker for `generation` ends its negotiation with `cache`; published only while the slot
    /// still names that request and never over a cache serving the newest target. The newest pending target runs
    /// next, else the slot goes idle.
    fn preload_publish(
        &mut self,
        generation: u64,
        cache: Option<PreloadCache>,
        now: Instant,
    ) -> PreloadNext {
        let Some(task) = self
            .preload_task
            .as_mut()
            .filter(|task| task.generation == generation)
        else {
            return PreloadNext::Stale;
        };
        if let Some(cache) = cache {
            debug_assert_eq!(cache.generation, generation);
            let unwanted = task.newest_target != task.running_item
                && self
                    .preload_cache
                    .as_ref()
                    .is_some_and(|slot| slot.serves(&task.newest_target, now));
            // Only a joined Play reads `settled`; without one, the slot gets the only copy.
            if task.settled.receiver_count() > 0 {
                task.settled.send_replace(Some(cache.clone()));
            }
            if !unwanted {
                self.preload_cache = Some(cache);
            }
        }
        if let Some(next) = task.pending.take() {
            // `pending` is always the newest target, so `newest_target` already names it.
            task.running_item.clone_from(&next.item_id);
            task.generation = next.generation;
            task.settled = tokio::sync::watch::channel(None).0;
            task.joined = false;
            PreloadNext::Run(next)
        } else {
            self.preload_task = None;
            PreloadNext::Idle
        }
    }

    /// Drops the cache and the negotiation; the caller aborts the returned handle outside the lock.
    fn preload_invalidate(&mut self) -> Option<tokio::task::JoinHandle<()>> {
        self.preload_cache = None;
        self.preload_epoch = self.preload_epoch.wrapping_add(1);
        self.preload_task.take().map(|task| task.handle)
    }
}

/// Extra (non-default) `Fields` [`JellybeamCore::live_children`] requests --
/// kept byte-for-byte the same as `media-cache::sync::item_fields()` (private
/// to a non-`pub` module, so not reusable from here). Keep in sync by hand.
pub(crate) fn live_children_fields() -> Vec<String> {
    [
        "Overview",
        "OriginalTitle",
        "SeriesName",
        "DateCreated",
        "PremiereDate",
        "ImageBlurHashes",
        "ParentId",
        "SeriesPrimaryImageTag",
    ]
    .into_iter()
    .map(String::from)
    .collect()
}

/// The UniFFI object the Android app holds: one instance per app process
/// (per `data_dir`), constructed once at startup.
#[derive(uniffi::Object)]
pub struct JellybeamCore {
    data_dir: PathBuf,
    device_id: String,
    state: Arc<SharedState>,
    /// docs/18 §2.1: serializes every read-modify-write of the session list so a stale snapshot
    /// is never written back; held for one load-modify-save, never across network I/O and never
    /// nested with the state lock. An `Arc` so a background task can hold its own clone.
    session_lock: Arc<Mutex<()>>,
    /// Serializes a throttled progress checkpoint with the final stop write.
    /// A report that won the state lock before `stop_playback` also takes
    /// this lock before releasing state, so the final write can never be
    /// overwritten by that older in-flight checkpoint.
    playback_write: Mutex<()>,
    /// Last revision of `State::library_grid_prefs` actually written to disk
    /// by [`Self::persist_library_grid_prefs`]. A dedicated lock, never held
    /// with `state`, so a save in flight never blocks `image_url`/
    /// `get_library_grid_prefs` on disk (docs/16-library-sort-filter.md §5).
    library_grid_prefs_save: crate::persistence::RevisionWriter,
    settings_save: crate::persistence::RevisionWriter,
    track_prefs_save: crate::persistence::RevisionWriter,
    seerr_config: crate::seerr::SeerrConfigStore,
    updater: Mutex<Option<Arc<crate::updates::AppUpdater>>>,
    /// Calls into `open_mirror`, so tests can pin how often a flow opens a mirror.
    #[cfg(test)]
    mirror_opens: std::sync::atomic::AtomicUsize,
    /// docs/13 "sign-in": one client for every pre-auth request (sign-in, Quick Connect, discovery
    /// probes) so the poll loop reuses its connection; per-core, never process-global, because
    /// its pool is tied to this core's runtime.
    pre_auth_http: std::sync::OnceLock<jellyfin_api::HttpClient>,
    runtime: tokio::runtime::Runtime,
}

#[uniffi::export]
impl JellybeamCore {
    #[uniffi::constructor]
    pub fn new(data_dir: String) -> Arc<Self> {
        let data_dir = PathBuf::from(data_dir);
        if let Err(e) = std::fs::create_dir_all(&data_dir) {
            // Every later disk operation (device id, session file, the
            // mirror itself) fails loudly on its own if this directory
            // genuinely can't exist; log here rather than panic to keep
            // `new`'s infallible signature honest.
            tracing::error!(
                error = %e,
                dir = %data_dir.display(),
                "failed to create data directory"
            );
        }

        let device_id = device_id::load_or_create(&data_dir);
        let loaded_settings = settings::load(&data_dir);
        let loaded_track_prefs = track_prefs::load(&data_dir);
        let loaded_library_grid_prefs = library_prefs::load(&data_dir);

        // Two worker threads: enough to overlap a `block_on`'d network/DB
        // call with the change-listener forwarding task; this object's
        // methods are meant to be called from Kotlin's IO dispatcher, not
        // hammered concurrently.
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .build()
            .expect("jellybeam-core tokio runtime should always build");

        let state = Arc::new(SharedState(Mutex::new(State {
            settings: loaded_settings,
            track_prefs: loaded_track_prefs,
            library_grid_prefs: loaded_library_grid_prefs,
            ..State::default()
        })));
        let session_lock = Arc::new(Mutex::new(()));

        // docs/13 Server compatibility: one poll for the process, following whichever client is installed.
        Self::spawn_server_version_poll(
            Arc::downgrade(&state),
            data_dir.clone(),
            session_lock.clone(),
            &runtime,
        );

        Arc::new(Self {
            data_dir,
            device_id,
            state,
            session_lock,
            playback_write: Mutex::new(()),
            library_grid_prefs_save: crate::persistence::RevisionWriter::default(),
            settings_save: crate::persistence::RevisionWriter::default(),
            track_prefs_save: crate::persistence::RevisionWriter::default(),
            seerr_config: crate::seerr::SeerrConfigStore::default(),
            updater: Mutex::new(None),
            #[cfg(test)]
            mirror_opens: std::sync::atomic::AtomicUsize::new(0),
            pre_auth_http: std::sync::OnceLock::new(),
            runtime,
        })
    }

    pub fn updater(
        &self,
        facts: crate::updates::InstalledUpdateFacts,
    ) -> Result<Arc<crate::updates::AppUpdater>, CoreError> {
        let mut slot = self
            .updater
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        if let Some(updater) = slot.as_ref() {
            return Ok(updater.clone());
        }
        let updater = crate::updates::AppUpdater::create(self.data_dir.join("updates"), facts)?;
        *slot = Some(updater.clone());
        Ok(updater)
    }

    pub fn sign_in(
        &self,
        server_url: String,
        username: String,
        password: String,
    ) -> Result<AccountInfo, CoreError> {
        let base = signin::ServerBase::parse(&server_url)?;
        let identity = self.client_identity(base.url());
        let (client, auth) = self
            .runtime
            .block_on(jellyfin_api::JellyfinClient::authenticate_by_name(
                self.pre_auth_http(),
                base.url(),
                identity,
                &username,
                &password,
            ))
            .map_err(|e| signin::classify_sign_in_error(base.host(), e))?;

        self.install_authenticated_session(base.into_url(), client, auth)
    }

    /// Replaces the credential for one already-saved account without
    /// discarding its mirror. The server address comes from the saved
    /// session, never editable UI; the authenticated user id must still
    /// match the selected saved account before anything changes.
    pub fn reauthorize_session(
        &self,
        index: u32,
        username: String,
        password: String,
    ) -> Result<AccountInfo, CoreError> {
        let target = self.saved_session_at(index)?;
        let host = signin::host_for_display(&target.server_url);
        let identity = self.client_identity(&target.server_url);
        let (client, auth) = self
            .runtime
            .block_on(jellyfin_api::JellyfinClient::authenticate_by_name(
                self.pre_auth_http(),
                &target.server_url,
                identity,
                &username,
                &password,
            ))
            .map_err(|e| signin::classify_sign_in_error(&host, e))?;

        self.install_reauthenticated_session(index, target, client, auth)
    }

    /// Starts a Quick Connect request. The secret is returned only for
    /// [Self::quick_connect_poll]/[Self::complete_quick_connect]; never persisted.
    pub fn initiate_quick_connect(
        &self,
        server_url: String,
    ) -> Result<QuickConnectSession, CoreError> {
        let base = signin::ServerBase::parse(&server_url)?;
        let identity = self.client_identity(base.url());
        let result = self
            .runtime
            .block_on(jellyfin_api::JellyfinClient::quick_connect_initiate(
                self.pre_auth_http(),
                base.url(),
                &identity,
            ))
            .map_err(|e| signin::classify_quick_connect_initiate_error(base.host(), e))?;
        let code = result
            .code
            .filter(|value| !value.is_empty())
            .ok_or_else(|| CoreError::Api {
                detail: "Quick Connect response did not include a code".to_string(),
            })?;
        let secret = result
            .secret
            .filter(|value| !value.is_empty())
            .ok_or_else(|| CoreError::Api {
                detail: "Quick Connect response did not include a secret".to_string(),
            })?;
        Ok(QuickConnectSession { code, secret })
    }

    /// One Quick Connect status request. `true` means approved and
    /// completion may proceed; `false` means keep polling.
    pub fn poll_quick_connect(
        &self,
        server_url: String,
        secret: String,
    ) -> Result<bool, CoreError> {
        let base = signin::ServerBase::parse(&server_url)?;
        let identity = self.client_identity(base.url());
        let result = self
            .runtime
            .block_on(jellyfin_api::JellyfinClient::quick_connect_poll(
                self.pre_auth_http(),
                base.url(),
                &identity,
                &secret,
            ))?;
        Ok(result.authenticated.unwrap_or(false))
    }

    /// Exchanges an approved Quick Connect secret for a normal account,
    /// through the same open-mirror-before-switch path as password sign-in.
    pub fn complete_quick_connect(
        &self,
        server_url: String,
        secret: String,
    ) -> Result<AccountInfo, CoreError> {
        let base = signin::ServerBase::parse(&server_url)?;
        let identity = self.client_identity(base.url());
        let (client, auth) = self
            .runtime
            .block_on(
                jellyfin_api::JellyfinClient::authenticate_with_quick_connect(
                    self.pre_auth_http(),
                    base.url(),
                    identity,
                    &secret,
                ),
            )
            .map_err(|e| signin::classify_sign_in_error(base.host(), e))?;
        self.install_authenticated_session(base.into_url(), client, auth)
    }

    /// Completes Quick Connect for a selected saved account. Unlike normal
    /// completion, refuses an approval made as a different Jellyfin user
    /// rather than adding that user and leaving the expired credential alone.
    pub fn complete_quick_connect_reauthorization(
        &self,
        index: u32,
        secret: String,
    ) -> Result<AccountInfo, CoreError> {
        let target = self.saved_session_at(index)?;
        let host = signin::host_for_display(&target.server_url);
        let identity = self.client_identity(&target.server_url);
        let (client, auth) = self
            .runtime
            .block_on(
                jellyfin_api::JellyfinClient::authenticate_with_quick_connect(
                    self.pre_auth_http(),
                    &target.server_url,
                    identity,
                    &secret,
                ),
            )
            .map_err(|e| signin::classify_sign_in_error(&host, e))?;
        self.install_reauthenticated_session(index, target, client, auth)
    }

    /// Restores a client from `session.json`, no network validation
    /// (offline-first): a stale/revoked token is only found once a real
    /// request fails.
    pub fn restore_session(&self) -> Option<AccountInfo> {
        // docs/18 §2.1: the tag is read before the saved list, so a switch landing between wins.
        let tag = self.account_epoch();
        self.restore_finish(tag, session::load(&self.data_dir)?)
    }

    /// Read-only "who is signed in" check: unlike
    /// [`Self::restore_session`], never constructs a client, never touches
    /// `self.state` or the mirror -- just whatever `session.json` currently
    /// says (tolerant load). Never returns the token: [`AccountInfo`] has no
    /// field for it.
    pub fn current_account(&self) -> Option<AccountInfo> {
        let saved = session::load(&self.data_dir)?;
        Some(AccountInfo::of(&saved))
    }

    /// The installed account's last-known server version string, or `None` if
    /// nobody is signed in or no refresh has completed (docs/13 Server
    /// compatibility); never a network call.
    pub fn server_version(&self) -> Option<String> {
        let state = self.lock_state();
        let version = state.account.installed()?.server_version;
        version.map(|v| v.to_string())
    }

    /// Whether the installed account's server is known to be at least
    /// `major.minor` (docs/13 Server compatibility). **Fails
    /// closed**: `false` whenever the version isn't known yet -- never
    /// assumes a floor for "unknown".
    pub fn server_at_least(&self, major: u32, minor: u32) -> bool {
        let state = self.lock_state();
        state
            .account
            .installed()
            .and_then(|i| i.server_version)
            .is_some_and(|v| v.at_least(major, minor))
    }

    /// The playback stats sheet's SOURCE row
    /// (`docs/jellybeam-osd-handoff/handoff/JELLYBEAM-TV-OSD-SPEC.md` §8b) needs a
    /// server name; this derives a display name from the account's server address
    /// instead of an extra round trip -- see [`host_from_server_url`]. `None` if that account is
    /// not signed in or the URL has no parseable host.
    /// `account_epoch` names the player's account (docs/18 §2.1); `None` is the current one.
    pub fn server_display_name(&self, account_epoch: Option<u64>) -> Option<String> {
        let client = self.read_account(account_epoch).ok()?.client?;
        host_from_server_url(client.base_url())
    }

    /// docs/13 About > server info: the offline snapshot the About page renders
    /// immediately, no network call -- everything already in memory/on disk.
    /// All fields are `None`/`false`/empty when nobody is signed in.
    pub fn server_info_snapshot(&self) -> ServerInfoSnapshot {
        // Clone out of the lock before the disk and SQLite reads below (the `state` field's
        // rule: never hold the guard across I/O).
        let (installed, mirror_handle, live_events_connected) = {
            let state = self.lock_state();
            (
                state.account.installed().map(|i| {
                    let facts = (i.server_version, i.server_name.clone());
                    (i.account.clone(), i.device_id.clone(), facts)
                }),
                state.account.bundle().map(|g| g.mirror.clone()),
                // Derived from the installed bus only, so a taken bundle never reaches the snapshot.
                state.account.bundle().is_some_and(|bus| {
                    *bus.connection.borrow() == jellyfin_core::BusConnection::Connected
                }),
            )
        };
        let Some((account, device_id, (server_version, server_name))) = installed else {
            return ServerInfoSnapshot {
                server_url: None,
                server_name: None,
                server_version: None,
                user_name: None,
                device_id: None,
                live_events_connected: false,
                mirror: None,
                libraries: Vec::new(),
            };
        };

        let mirror = mirror_handle.as_ref().map(|mirror| MirrorStats {
            item_count: mirror.item_count(),
            db_bytes: mirror.db_size_bytes(),
            last_full_sync_ms: mirror
                .meta_value("last_full_sync")
                .and_then(|raw| raw.parse::<i64>().ok()),
            last_delta_sync: mirror.meta_value("last_delta_sync"),
            counts: MirrorItemCounts::from_type_counts(&mirror.item_type_counts()),
        });
        // Names shown verbatim (CLAUDE.md): never re-cased or prettified.
        let libraries = mirror_handle
            .as_ref()
            .map(|mirror| {
                mirror
                    .views()
                    .into_iter()
                    .map(|v| MirrorLibrary {
                        name: v.name,
                        collection_type: v.collection_type,
                    })
                    .collect()
            })
            .unwrap_or_default();

        ServerInfoSnapshot {
            server_url: Some(account.server_url),
            server_name,
            server_version: server_version.map(|v| v.to_string()),
            user_name: Some(account.user_name),
            device_id: Some(device_id),
            live_events_connected,
            mirror,
            libraries,
        }
    }

    /// docs/13 About > server info: the on-demand refresh behind the About page's refresh
    /// action. Tries the authenticated `GET /System/Info` first (richer than the pre-auth
    /// `/System/Info/Public`); a server that refuses it to a non-admin user (403, or the cached
    /// non-admin role) falls back to `/System/Info/Public` with `system_info_available: false`
    /// and the extra fields left `None`. Whichever version/name came back is handed to
    /// [`Self::apply_public_server_info`], so this refresh also refreshes the version gate and
    /// persists the server name -- the same reason `server_version`/`server_name` are never
    /// stale for long even without this being called.
    pub fn fetch_server_details(&self) -> Result<ServerDetails, CoreError> {
        // docs/18 §2.1: a known non-admin never asks the admin endpoint; a 403 is the role, a 401 a
        // revoked token that must reach re-authorization rather than the public fallback.
        let (epoch, client, known_non_admin) = {
            let state = self.lock_state();
            let installed = state.account.installed().ok_or(CoreError::NotSignedIn)?;
            (
                state.account.epoch,
                installed.client.clone(),
                installed.caches.is_admin == Some(false),
            )
        };
        let admin_info = if known_non_admin {
            None
        } else {
            match self.runtime.block_on(client.system_info()) {
                Ok(info) => Some(info),
                Err(jellyfin_api::ApiError::Status { code: 403, .. }) => None,
                Err(e) => return Err(e.into()),
            }
        };
        let (
            system_info_available,
            name,
            version,
            product_name,
            operating_system,
            architecture,
            has_pending_restart,
            has_update_available,
        ) = match admin_info {
            Some(info) => (
                true,
                info.server_name,
                info.version,
                info.product_name,
                info.operating_system_display_name,
                info.system_architecture,
                info.has_pending_restart,
                info.has_update_available,
            ),
            None => {
                let info = self.runtime.block_on(client.refresh_public_system_info())?;
                (
                    false,
                    info.server_name,
                    info.version,
                    None,
                    None,
                    None,
                    None,
                    None,
                )
            }
        };

        self.runtime.block_on(Self::apply_public_server_info(
            epoch,
            &client,
            version.clone(),
            name.clone(),
            self.data_dir.clone(),
            self.state.clone(),
            self.session_lock.clone(),
        ));

        Ok(ServerDetails {
            server_name: name,
            version,
            product_name,
            operating_system,
            architecture,
            has_pending_restart,
            has_update_available,
            system_info_available,
        })
    }

    /// Every signed-in account, in on-disk order (index `i` is what
    /// [`Self::switch_session`] expects). Never carries a token.
    pub fn list_accounts(&self) -> Vec<AccountInfo> {
        session::load_list(&self.data_dir)
            .sessions
            .iter()
            .map(AccountInfo::of)
            .collect()
    }

    /// The index into [`Self::list_accounts`]'s result that is currently
    /// active, or `None` if nobody is signed in.
    pub fn active_account_index(&self) -> Option<u32> {
        let list = session::load_list(&self.data_dir);
        if list.sessions.is_empty() {
            None
        } else {
            Some(list.active as u32)
        }
    }

    /// Switches the active account to `index` and installs its client with **no network validation** (like
    /// [`Self::restore_session`]): persists the new `active` index first, then resets the account and tears down the
    /// outgoing mirror/listener. Does **not** call [`Self::open_mirror`].
    pub fn switch_session(&self, index: u32) -> Result<AccountInfo, CoreError> {
        let ix = index as usize;
        // docs/18 §2.1: the active index is saved before anything is installed, and a failed
        // save refuses the switch -- nothing may run that the saved list does not name.
        let target = session::mutate(&self.data_dir, &self.session_lock, |list| {
            if ix >= list.sessions.len() {
                return Err(CoreError::InvalidSessionIndex { index });
            }
            list.active = ix;
            Ok(list.sessions[ix].clone())
        })?;

        self.install_account(Self::installed_from_saved(&target));
        Ok(AccountInfo::of(&target))
    }

    /// Permanently removes one saved account and its isolated local mirror.
    /// The session list is committed before any live state is torn down, so
    /// a disk failure leaves the current account fully usable.
    pub fn remove_session(&self, index: u32) -> Result<bool, CoreError> {
        let ix = index as usize;
        // The bounds check and the removal are one read-modify-write of the session list, so a
        // concurrent version-refresh persist can never re-add this row in between.
        let (removed, was_listed_active, next_active) =
            session::mutate(&self.data_dir, &self.session_lock, |list| {
                let was_listed_active = ix == list.active;
                let removed = list
                    .remove_at(ix)
                    .ok_or(CoreError::InvalidSessionIndex { index })?;
                Ok((removed, was_listed_active, list.active_session().cloned()))
            })?;

        self.prune_seerr_entry(&removed.server_url, &removed.user_id);

        // docs/18 §2.1: the removed row is the installed account by identity, decided under the
        // lock that tears it down; the saved list's active row stands in only while no account
        // is installed. The list's next active row is installed in the same change, so the app
        // sees one account change and its relaunch restore finds the account installed.
        let next = next_active.as_ref().map(Self::installed_from_saved);
        let torn_down = {
            let mut state = self.lock_state();
            let is_installed = match state.account.installed() {
                Some(installed) => installed.identity().names(&removed),
                None => was_listed_active,
            };
            is_installed
                .then(|| Self::reset_account_state_locked(&mut state, next, AccountChange::Leave))
        };
        let removed_installed = torn_down.is_some();

        let final_reports = if let Some(reset) = torn_down {
            self.dispose_account_reset(reset)
        } else {
            // docs/18 §2.1: removing the account a parked playback plays on ends that playback.
            let ended = {
                let mut state = self.lock_state();
                let parked_on_removed = state
                    .playback_owner
                    .parked
                    .as_ref()
                    .is_some_and(|parked| parked.is_account(Some(&removed.mirror_dir)));
                if !parked_on_removed {
                    None
                } else if Self::parked_plays_locked(&state) {
                    Some(Self::end_playback_locked(&mut state))
                } else {
                    state.playback_owner.parked = None;
                    None
                }
            };
            ended.map_or_else(Vec::new, |ended| self.dispose_account_reset(ended))
        };

        self.spawn_logout(&removed, final_reports);
        self.remove_mirror_dir_if_safe(&removed.mirror_dir);
        Ok(removed_installed)
    }

    /// Opens the mirror (starting sync automatically) against the installed account, replacing
    /// any mirror already open, with the real websocket `EventBus` and a forwarder relaying its
    /// events onto a fresh [`BusBundle`]; the one open per account install. docs/18 §2.1: an open
    /// that spans an account change installs nothing and fails [`CoreError::AccountChanged`].
    ///
    /// The bus's capped-backoff supervisor reconnects a closed socket, and every reconnect
    /// emits `NeedsReconcile`, which `media_cache::sync` turns into a full heal.
    pub fn open_mirror(&self) -> Result<(), CoreError> {
        let (epoch, client, dir) = self.open_begin()?;
        let built = self.build_bundle(&client, dir)?;
        self.open_finish(epoch, &client, built)
    }

    pub fn views(&self) -> Vec<ViewSnapshot> {
        self.require_mirror()
            .map(|m| m.views().into_iter().map(ViewSnapshot::from).collect())
            .unwrap_or_default()
    }

    /// docs/25 §4.3: the Home snapshot for `layout`, the layout the caller will draw; never
    /// the persisted `Settings::home_layout`, so a request can't race a settings write.
    pub fn home_snapshot(&self, layout: HomeLayout) -> HomeSnapshot {
        let mirror = self.require_mirror().ok();
        let settings = self.lock_state().settings.clone();
        crate::home::snapshot(layout, mirror.as_ref(), &settings)
    }

    /// docs/16 §2.7: the item types present among favorites (`"Movie"`, `"Series"`, ...), for
    /// the Favorites grid's Type panel. Empty before the mirror opens.
    pub fn favorite_item_types(&self) -> Vec<String> {
        self.require_mirror()
            .map(|mirror| mirror.favorite_item_types())
            .unwrap_or_default()
    }

    /// docs/07 §5: whether the drawer shows its Favorites entry. One indexed probe; `false`
    /// before the mirror opens.
    pub fn has_favorites(&self) -> bool {
        self.require_mirror()
            .is_ok_and(|mirror| mirror.has_favorites())
    }

    /// `Settings::show_virtual_episodes` (default `false`) drops virtual
    /// (missing/unaired) `Episode` rows from the result when off -- applied
    /// here post-query since only a Season's Episode rows carry a meaningful
    /// `is_virtual` in practice, avoiding query-layer signature churn. The
    /// decision itself is [`filter_virtual_episodes`], split out so it's
    /// unit-testable without a live `Mirror`. Every caller today asks for the
    /// whole list in one page, so filtering after LIMIT/OFFSET never
    /// truncates a page short.
    ///
    /// docs/07 §5: "mirror not open yet", "query failed" and "genuinely empty" stay apart, so a
    /// screen never settles on a failure as an empty folder.
    pub fn children_read(
        &self,
        parent_id: String,
        sort: SortOrder,
        offset: u32,
        limit: u32,
    ) -> BrowseRead {
        let Ok(mirror) = self.require_mirror() else {
            return BrowseRead::NotReady;
        };
        let show_virtual_episodes = self.lock_state().settings.show_virtual_episodes;
        let Some(rows) = mirror.children_checked(&parent_id, sort.into(), offset, limit) else {
            tracing::warn!("children query failed");
            return BrowseRead::Failed;
        };
        let cards: Vec<Card> = rows.into_iter().map(Card::from).collect();
        // This is a Series -> Seasons listing only when at least one
        // returned card is a Season -- every other listing shape skips the
        // extra query entirely.
        let cards = if cards.iter().any(|c| c.item_type == "Season") {
            // Without the counts an all-virtual season would be listed as real, so a failure here
            // fails the read.
            let Some(counts) = mirror.season_episode_counts(&parent_id) else {
                tracing::warn!("season episode counts query failed");
                return BrowseRead::Failed;
            };
            filter_all_virtual_seasons(cards, &counts, show_virtual_episodes)
        } else {
            cards
        };
        BrowseRead::Items {
            cards: filter_virtual_episodes(cards, show_virtual_episodes),
        }
    }

    /// docs/16-library-sort-filter.md §3/§2.2/§4.6: the sorted/filtered
    /// library grid for `view_id` -- library-level rows only, no
    /// virtual-episode filtering (none exist at this level). `None` when the
    /// mirror isn't open OR the query failed -- deliberately NOT coerced to
    /// an empty page: §4.6 needs a transient failure to keep Kotlin's last
    /// good grid, distinct from a genuinely empty `Some(vec![])`.
    pub fn library_grid(
        &self,
        view_id: String,
        sort: GridSort,
        filters: GridFilters,
        offset: u32,
        limit: u32,
    ) -> Option<Vec<Card>> {
        let mirror = self.require_mirror().ok()?;
        let cards =
            mirror.library_grid_checked(&view_id, sort.into(), &filters.into(), offset, limit)?;
        Some(cards.into_iter().map(Card::from).collect())
    }

    /// docs/16-library-sort-filter.md §3/§2.3/§4.6: filtered/total row
    /// counts for the summary line. `None` when the mirror isn't open OR the
    /// query failed, same "keep the last good value" contract as
    /// [`Self::library_grid`].
    pub fn library_grid_counts(&self, view_id: String, filters: GridFilters) -> Option<GridCounts> {
        let mirror = self.require_mirror().ok()?;
        mirror
            .library_grid_counts_checked(&view_id, &filters.into())
            .map(GridCounts::from)
    }

    /// docs/16-library-sort-filter.md §3/§2.4/§4.6: the index rail's group
    /// buckets. `None` when the mirror isn't open OR the query failed, same
    /// contract as [`Self::library_grid`].
    pub fn library_grid_groups(
        &self,
        view_id: String,
        sort: GridSort,
        filters: GridFilters,
    ) -> Option<Vec<GridGroup>> {
        let mirror = self.require_mirror().ok()?;
        let groups = mirror.library_grid_groups_checked(&view_id, sort.into(), &filters.into())?;
        Some(groups.into_iter().map(GridGroup::from).collect())
    }

    /// docs/16-library-sort-filter.md §3/§2.5: the view's distinct genres,
    /// verbatim server strings (CLAUDE.md: never prettified). Empty when the
    /// mirror isn't open.
    pub fn library_genres(&self, view_id: String) -> Vec<String> {
        let Ok(mirror) = self.require_mirror() else {
            return Vec::new();
        };
        mirror.library_genres(&view_id)
    }

    /// Mirror-backed free-text search (title/original-title/series-name prefix
    /// match, ranked -- see `media_cache::query::search`). Fails open: an
    /// empty vec, never an error, when the mirror isn't open yet.
    pub fn search(&self, query: String, limit: u32) -> Vec<Card> {
        let Ok(mirror) = self.require_mirror() else {
            return Vec::new();
        };
        mirror
            .search(&query, limit)
            .into_iter()
            .map(Card::from)
            .collect()
    }

    /// The full existing `Card` for one item id, straight off the mirror --
    /// no network fallback. Gets the same real art every browse row carries,
    /// instead of a degraded `Card` reconstructed from `get_item_detail`'s DTO.
    ///
    /// Unlike `children_read`/`search`, returns a `Result`:
    /// `Err(CoreError::MirrorNotOpen)` before `open_mirror` succeeds, while
    /// `Ok(None)` covers an id the mirror simply doesn't know.
    pub fn card_by_id(&self, item_id: String) -> Result<Option<Card>, CoreError> {
        let mirror = self.require_mirror()?;
        let canonical_item_id = canonicalize_item_id(&item_id);
        Ok(mirror.card_by_id(&canonical_item_id).map(Card::from))
    }

    /// [`Self::card_by_id`] for many ids in one call: the known cards in the order asked, unknown
    /// ids dropped. Backs a screen re-reading the cards it already shows (docs/11 §Person page).
    pub fn cards_by_ids(&self, item_ids: Vec<String>) -> Result<Vec<Card>, CoreError> {
        let mirror = self.require_mirror()?;
        let ids: Vec<String> = item_ids.iter().map(|id| canonicalize_item_id(id)).collect();
        Ok(mirror
            .cards_by_ids(&ids)
            .into_iter()
            .map(Card::from)
            .collect())
    }

    pub fn item_count(&self) -> i64 {
        self.require_mirror().map(|m| m.item_count()).unwrap_or(0)
    }

    pub fn is_syncing(&self) -> bool {
        self.require_mirror()
            .map(|m| shown_as_syncing(m.is_syncing(), m.playback_active()))
            .unwrap_or(false)
    }

    /// Snapshot of [`media_cache::Mirror::sync_activity`]'s current value --
    /// see [`SyncStatus`] for the UI affordance this backs. A plain poll of a
    /// fresh `watch::Receiver`, same fail-open-when-no-mirror contract as
    /// [`Self::is_syncing`] (here, `SyncStatus::Idle`).
    pub fn sync_status(&self) -> SyncStatus {
        self.require_mirror()
            .map(|m| {
                let activity = m.sync_activity().borrow().clone();
                SyncStatus::from_activity(activity, |view_id| {
                    m.views()
                        .into_iter()
                        .find(|view| view.id == view_id)
                        .map(|view| view.name)
                })
            })
            .unwrap_or(SyncStatus::Idle)
    }

    /// `account_epoch` names the player's account (docs/18 §2.1); `None` is the current one.
    pub fn image_url(
        &self,
        item_id: String,
        kind: ImageKind,
        tag: String,
        max_width: u32,
        account_epoch: Option<u64>,
    ) -> Result<String, CoreError> {
        let client = self
            .read_account(account_epoch)?
            .client
            .ok_or(CoreError::NotSignedIn)?;
        Ok(client.image_url(&item_id, kind.into(), &tag, max_width))
    }

    /// The current account's pair Kotlin formats image URLs from, read under one lock with the
    /// epoch it belongs to; `None` when signed out.
    pub fn image_url_prefix(&self) -> Option<ImageUrlPrefix> {
        let (epoch, client) = self.lock_state().account.current_client()?;
        Some(ImageUrlPrefix {
            epoch,
            base_url: client.base_url().to_string(),
            token: client.image_url_token().to_string(),
        })
    }

    /// Builds a trickplay tile-sheet image URL for Coil, or `None` if no
    /// signed-in client. Unlike [`Self::image_url`] returns `Option` not
    /// `Result`: a caller only ever calls this after a plan with
    /// `trickplay: Some(..)`, so "not signed in" here means state got out of
    /// sync, not an actionable outcome.
    ///
    /// `width`/`image_index` come from [`PlaybackPlan::trickplay`] and
    /// [`crate::trickplay_locate`]'s result. `media_source_id` is not
    /// threaded through: the server resolves the tile sheet from `item_id` +
    /// `width` alone for the common single-source case.
    /// `account_epoch` names the player's account (docs/18 §2.1); `None` is the current one.
    pub fn trickplay_tile_url(
        &self,
        item_id: String,
        width: u32,
        image_index: u32,
        account_epoch: Option<u64>,
    ) -> Option<String> {
        let client = self.read_account(account_epoch).ok()?.client?;
        Some(client.trickplay_tile_url(&item_id, width, image_index, None))
    }

    /// Playback's one enrichment fetch (docs/18 §3): the chapter/stream metadata the OSD uses, the
    /// playing media source's size/path for the stats sheet's CONTAINER/FILE rows
    /// (`docs/jellybeam-osd-handoff/handoff/JELLYBEAM-TV-OSD-SPEC.md` §8b), and the trickplay
    /// manifest of `media_source_id`. `MediaSources` and `Trickplay` are requested explicitly --
    /// without them the server omits both and the fields come back empty regardless.
    /// Deliberately narrow: full detail belongs to [`Self::get_item_detail`].
    /// `account_epoch` names the player's account (docs/18 §2.1); `None` is the current one.
    pub fn get_playback_osd_detail(
        &self,
        item_id: String,
        media_source_id: String,
        account_epoch: Option<u64>,
    ) -> Result<PlaybackOsdDetail, CoreError> {
        let client = self
            .read_account(account_epoch)?
            .client
            .ok_or(CoreError::NotSignedIn)?;

        let dto = self.runtime.block_on(async {
            let result = client
                .get_items(&jellyfin_api::ItemQuery {
                    ids: vec![item_id.clone()],
                    fields: vec![
                        "MediaStreams".to_string(),
                        "Chapters".to_string(),
                        "MediaSources".to_string(),
                        "Trickplay".to_string(),
                    ],
                    limit: 1,
                    ..jellyfin_api::ItemQuery::default()
                })
                .await?;
            result
                .items
                .into_iter()
                .next()
                .ok_or_else(|| CoreError::Api {
                    detail: format!("item {item_id} not found"),
                })
        })?;

        Ok(PlaybackOsdDetail::of(&dto, &media_source_id))
    }

    /// An item's full detail record (docs/11, docs/12): a live per-visit fetch, never persisted, needing a signed-in
    /// client and no open mirror; errors ([`CoreError::Api`]) rather than degrading. `account_epoch` names the
    /// player's account (docs/18 §2.1); `None` is the current one.
    pub fn get_item_detail(
        &self,
        item_id: String,
        account_epoch: Option<u64>,
    ) -> Result<ItemDetail, CoreError> {
        let client = self
            .read_account(account_epoch)?
            .client
            .ok_or(CoreError::NotSignedIn)?;

        let dto = self.runtime.block_on(async {
            let result = client
                .get_items(&jellyfin_api::ItemQuery {
                    ids: vec![item_id.clone()],
                    fields: vec![
                        "MediaStreams".to_string(),
                        "People".to_string(),
                        "Chapters".to_string(),
                        "Genres".to_string(),
                        "CriticRating".to_string(),
                        "Studios".to_string(),
                        "Overview".to_string(),
                        "DateCreated".to_string(),
                        "MediaSources".to_string(),
                        "RecursiveItemCount".to_string(),
                        "ChildCount".to_string(),
                        // Not reliably present without an explicit request
                        // (a View-Series synthesized Card carries no year).
                        "ProductionYear".to_string(),
                        "EndDate".to_string(),
                        "Status".to_string(),
                    ],
                    limit: 1,
                    ..jellyfin_api::ItemQuery::default()
                })
                .await?;
            result
                .items
                .into_iter()
                .next()
                .ok_or_else(|| CoreError::Api {
                    detail: format!("item {item_id} not found"),
                })
        })?;

        Ok(ItemDetail::from(&dto))
    }

    /// docs/11 item 1: the Detail page's first paint from the mirror's stored DTO, before
    /// [`Self::get_item_detail`] answers; carries only what the sync requested, and the network
    /// record that follows is authoritative. `None` when the mirror doesn't know the item.
    pub fn item_detail_local(&self, item_id: String) -> Result<Option<ItemDetail>, CoreError> {
        let mirror = self.require_mirror()?;
        Ok(mirror
            .item(&canonicalize_item_id(&item_id))
            .map(|dto| ItemDetail::from(&dto)))
    }

    /// docs/11-detail-ux-spec.md tier 2 item 13, "Similar Titles":
    /// `GET /Items/{itemId}/Similar` mapped onto the same [`Card`] shape
    /// every browse surface uses. Requires a signed-in client; no mirror
    /// needed -- a live personalized set, not a library listing. A result
    /// item missing its own `Id` is silently dropped rather than failing the
    /// whole call.
    pub fn get_similar(&self, item_id: String, limit: u32) -> Result<Vec<Card>, CoreError> {
        let client = self.require_client()?;
        let items = self.runtime.block_on(client.get_similar(&item_id, limit))?;
        Ok(items
            .iter()
            .filter_map(|dto| Card::try_from(dto).ok())
            .collect())
    }

    /// A `Channel` view's content is browsed LIVE, never mirrored, for three
    /// reasons: its Episode children carry no usable parent linkage
    /// (`ParentId`/`SeriesId`/`SeasonId` all `null`); neither the WS delta
    /// feed nor `LibraryChanged` ever surfaces a channel item, so a mirrored
    /// copy could never heal; and DVR content churns independent of any
    /// library scan, so "whatever the plugin returns now" is the only
    /// correct order.
    ///
    /// Non-recursive; `sort` picks the `sortBy`/`sortOrder` params (or omits
    /// both for [`LiveSort::ServerOrder`]). Same client-acquire/`block_on`
    /// shape as [`Self::get_similar`].
    pub fn live_children(
        &self,
        parent_id: String,
        start_index: u32,
        limit: u32,
        sort: LiveSort,
    ) -> Result<Vec<Card>, CoreError> {
        let client = self.require_client()?;
        let (sort_by, sort_order) = match sort {
            LiveSort::ServerOrder => (None, None),
            LiveSort::NameAsc => (Some("SortName".to_string()), Some("Ascending".to_string())),
            LiveSort::NewestFirst => (
                Some("PremiereDate".to_string()),
                Some("Descending".to_string()),
            ),
        };
        let query = jellyfin_api::ItemQuery {
            parent_id: Some(parent_id),
            recursive: false,
            start_index,
            limit,
            fields: live_children_fields(),
            sort_by,
            sort_order,
            ..jellyfin_api::ItemQuery::new()
        };
        let result = self.runtime.block_on(client.get_items(&query))?;
        Ok(result
            .items
            .iter()
            .filter_map(|dto| Card::try_from(dto).ok())
            .collect())
    }

    /// Skip-intro/credits markers (docs/12-osd-ux-spec.md): `GET
    /// /MediaSegments/{itemId}`, requesting every segment type. Fails open,
    /// unlike [`Self::get_item_detail`]: not signed in, a 404 (older server),
    /// or any other error all degrade to an empty list -- a markers fetch
    /// failing must never block or error out playback.
    /// `account_epoch` names the player's account (docs/18 §2.1); `None` is the current one.
    pub fn get_media_segments(
        &self,
        item_id: String,
        account_epoch: Option<u64>,
    ) -> Vec<MediaSegment> {
        let Some(client) = self.read_account(account_epoch).ok().and_then(|a| a.client) else {
            return Vec::new();
        };
        self.runtime
            .block_on(client.get_media_segments(&item_id, &[]))
            .unwrap_or_else(|e| {
                tracing::warn!(
                    error = %e,
                    "get_media_segments failed; degrading to an empty list"
                );
                Vec::new()
            })
            .into_iter()
            .map(MediaSegment::from)
            .collect()
    }

    /// docs/18 §3.2: sidecar `index`'s text for `play_session_id`; `None` on a stale session, an
    /// unknown index or any failure, logged by class only (the URL carries the token).
    pub fn fetch_external_subtitle(&self, play_session_id: String, index: i32) -> Option<String> {
        let (client, url) = self.sidecar_source(&play_session_id, index)?;
        self.sidecar_fetch(
            index,
            client.fetch_delivery_text(&url, crate::types::SUBTITLE_MAX_BYTES),
        )
    }

    /// docs/18 §3.2: ASS sidecar `index` fetched straight into `overlay` as track `key` of overlay
    /// item `item`, so the file never enters the JVM heap; blocks until parsed. False on a stale
    /// session, any fetch failure (logged as above), or a script the overlay refuses.
    pub fn load_ass_sidecar(
        &self,
        play_session_id: String,
        index: i32,
        overlay: Arc<crate::ass::AssOverlay>,
        key: String,
        item: u64,
    ) -> bool {
        let Some((client, url)) = self.sidecar_source(&play_session_id, index) else {
            return false;
        };
        self.sidecar_fetch(
            index,
            client.fetch_delivery_bytes(&url, ass_render::MAX_SCRIPT_BYTES),
        )
        .is_some_and(|data| overlay.add_script(key, data, item))
    }

    /// Registers (or replaces) the change listener. If a mirror is open, its forwarding task is
    /// swapped for one to `listener` under one lock and the old one joined outside it; otherwise
    /// remembered and bound by the next successful [`Self::open_mirror`].
    pub fn set_change_listener(&self, listener: Arc<dyn ChangeListener>) {
        let old_task = {
            let mut state = self.lock_state();
            state.listener = Some(listener.clone());
            let bundle = state
                .account
                .installed
                .as_mut()
                .and_then(|i| i.bundle.as_mut());
            bundle.and_then(|g| {
                let task = self.spawn_listener_task(g.mirror.clone(), listener);
                g.listener_task.replace(task)
            })
        };
        self.stop_task(old_task);
    }

    /// Records this device's playback capabilities, used by the next
    /// [`Self::prepare_playback`] call to build the device profile. Callable
    /// before or after sign-in. If never called, uses
    /// `jellyfin_core::AndroidTvCaps::default()`'s conservative floor
    /// (H.264/VP8/VP9/MPEG2 only).
    pub fn set_device_caps(&self, caps: DeviceCaps) {
        let preload_task = {
            let mut state = self.lock_state();
            state.caps = caps.into();
            state.preload_invalidate()
        };
        Self::abort_task(preload_task);
    }

    /// Returns the current settings record -- loaded at construction (or
    /// [`Settings::default`]) and kept current by [`Self::set_settings`].
    /// Whole-record per `docs/09-settings-plan.md`; no per-field getter.
    pub fn get_settings(&self) -> Settings {
        self.lock_state().settings.clone()
    }

    /// docs/09: the record, its revision and the mirror's Next Up options change under the state
    /// lock, then a revision-ordered write persists it (a failure is logged, never raised); a
    /// changed Next Up option asks the mirror's refresher to re-query without blocking.
    pub fn set_settings(&self, new_settings: Settings) {
        let (revision, mirror, preload_task, next_up_changed) = {
            let mut state = self.lock_state();
            let next_up_changed = changed_next_up_options(&state.settings, &new_settings).is_some();
            state.settings = new_settings.clone();
            state.settings_revision += 1;
            Self::push_next_up_options_locked(&state);
            let preload_task = state.preload_invalidate();
            (
                state.settings_revision,
                state.account.bundle().map(|g| g.mirror.clone()),
                preload_task,
                next_up_changed,
            )
        };
        Self::abort_task(preload_task);

        if let Err(e) = self
            .settings_save
            .save(revision, || settings::save(&self.data_dir, &new_settings))
        {
            tracing::warn!(error = %e, "failed to persist settings to disk");
        }

        if let Some(mirror) = mirror.filter(|_| next_up_changed) {
            mirror.request_next_up_refresh();
        }
    }

    /// docs/16-library-sort-filter.md §5: `view_id`'s persisted sort/filter
    /// state, or [`LibraryGridPrefs::default`] if never saved -- covers both
    /// "never opened" and an unknown view id indistinguishably. Loaded once
    /// at construction and kept current by [`Self::set_library_grid_prefs`].
    pub fn get_library_grid_prefs(&self, view_id: String) -> LibraryGridPrefs {
        self.lock_state()
            .library_grid_prefs
            .get(&view_id)
            .cloned()
            .unwrap_or_default()
    }

    /// Stores `prefs` for `view_id` as current in-memory state and persists
    /// the whole map, swallowing a write failure (logged).
    ///
    /// The insert and revision bump happen under `lock_state()` -- cheap,
    /// in-memory -- and the disk write never runs while the lock is held,
    /// since [`Self::image_url`] takes the same lock on Compose's main thread.
    /// Disk ordering comes from the revision counter instead (see
    /// [`Self::persist_library_grid_prefs`], docs/16-library-sort-filter.md
    /// §5), not from serializing callers on the state lock. Reads always
    /// come from `State`, never disk, so they see the newest value
    /// immediately regardless of where its save stands.
    pub fn set_library_grid_prefs(&self, view_id: String, prefs: LibraryGridPrefs) {
        let (revision, snapshot) = {
            let mut state = self.lock_state();
            state.library_grid_prefs.insert(view_id, prefs);
            state.library_grid_prefs_revision += 1;
            (
                state.library_grid_prefs_revision,
                state.library_grid_prefs.clone(),
            )
        };
        self.persist_library_grid_prefs(revision, &snapshot);
    }

    /// Persist only newer successful revisions, without holding the state lock.
    fn persist_library_grid_prefs(&self, revision: u64, snapshot: &LibraryGridPrefsFile) {
        if let Err(e) = self
            .library_grid_prefs_save
            .save(revision, || library_prefs::save(&self.data_dir, snapshot))
        {
            tracing::warn!(error = %e, "failed to persist library grid prefs to disk");
        }
    }

    /// Negotiates playback for `item_id`, returning the plan Kotlin's Media3
    /// player executes. Requires a signed-in client and an open mirror
    /// ([`CoreError::NotSignedIn`]/[`CoreError::MirrorNotOpen`]); refuses a
    /// virtual item with [`CoreError::Cache`].
    ///
    /// docs/18-playback-quality.md §2: on a cache miss, resolution depends on
    /// `Settings::playback_quality` -- `Cap` always forces a real `Transcode`
    /// plan via [`transcode_url_from_decision`]; `DirectPlay`/`Auto` resolve
    /// through [`resolve_plan`] (`DirectPlay` refuses with
    /// [`CoreError::WouldTranscode`], `Auto` attempts Direct Play or
    /// transcodes on local codec corroboration).
    /// [`Self::prepare_transcode_fallback`] is unrelated: Kotlin's own
    /// local-player-failure fallback.
    ///
    /// On success, starts a fresh `ReportingSession`, `abandon`-ing any
    /// previous one -- only ever one active session per `JellybeamCore`.
    /// `start_from_beginning` (docs/11 item 11) forces tick 0 over the
    /// server's saved resume position; recomputed every call so a preload
    /// cache hit never mis-resumes. A fresh
    /// [`Self::preload_playback`] `DirectPlay` result for this `item_id` is
    /// consumed here (single-use) in place of a fresh negotiation round
    /// trip; a preload still negotiating this same item is joined instead of
    /// aborted (§2), so Play reuses it rather than starting over. A preload
    /// for any other item is stale the moment a real Play arrives: it is
    /// aborted and Play negotiates at once, never waiting on it.
    pub fn prepare_playback(
        &self,
        item_id: String,
        start_from_beginning: bool,
        request: PlaybackRequest,
    ) -> Result<PlaybackPlan, CoreError> {
        let (admitted, claim) = {
            let mut state = self.lock_state();
            // docs/18 §2.1: a playback kept across an account change goes on with its own account.
            let account = Self::account_for_epoch_locked(&state, request.account_epoch)?;
            let on_current = request.account_epoch == state.account.epoch;
            let (client, mirror) = account.require_open()?;
            let caps = state.caps.clone();
            let tolerate_mislabeled_levels = state.settings.tolerate_mislabeled_levels;
            let quality = state.settings.playback_quality;
            let claim = Self::admit_playback_request(&mut state, request, None)?;

            // Preloads negotiate on the current account only.
            let preload = state.preload_take_for_play(&item_id, on_current, Instant::now());
            let admitted = AdmittedPlay {
                client,
                mirror,
                caps,
                tolerate_mislabeled_levels,
                quality,
                preload,
            };
            (admitted, claim)
        };
        // A failed negotiation undoes its admission (docs/18 §2.1).
        let result = self.negotiate_admitted(item_id, start_from_beginning, admitted, &claim);
        if result.is_err() {
            self.release_claim(&claim);
        }
        result
    }

    /// Focus-dwell preload (`Settings::preload_on_focus`, default on):
    /// best-effort playback-handshake prefetch for a long-focused card.
    /// Metadata/negotiation only, never media bytes: runs the same
    /// negotiation [`Self::prepare_playback`] does but never starts a
    /// `ReportingSession` or surfaces an error. A `DirectPlay` result is
    /// stashed in the one-slot [`PreloadCache`]; anything else caches nothing.
    ///
    /// No-ops before touching the network when the setting is off, nobody is
    /// signed in, the mirror isn't open, quality is `Cap { .. }` (its plan's
    /// `url` is position-dependent, never reusable), a cache entry for this
    /// `item_id` is younger than [`PRELOAD_REFRESH_AFTER`], or its negotiation
    /// already runs.
    ///
    /// Only replaces a background task and returns; never waits on the
    /// server. Exactly one speculative worker is retained; newer focus
    /// targets replace the pending slot rather than racing requests. A real
    /// `prepare_playback` for another item aborts any unfinished speculation,
    /// and each request's generation is checked before publishing, so a
    /// cancellation, account swap, or profile change can never be undone by a
    /// late response.
    /// `account_epoch` names the player's account; a preload only ever runs on the current one.
    pub fn preload_playback(&self, item_id: String, account_epoch: Option<u64>) {
        let mut state = self.lock_state();
        if !state.settings.preload_on_focus
            || account_epoch.is_some_and(|epoch| epoch != state.account.epoch)
        {
            return;
        }
        let Some(client) = state.account.installed().map(|i| i.client.clone()) else {
            return;
        };
        let Some(mirror) = state.account.bundle().map(|g| g.mirror.clone()) else {
            return;
        };
        if matches!(state.settings.playback_quality, PlaybackQuality::Cap { .. }) {
            return;
        }
        state.preload_generation += 1;
        let request = PreloadRequest {
            generation: state.preload_generation,
            item_id,
            client,
            mirror,
            caps: state.caps.clone(),
            tolerate_mislabeled_levels: state.settings.tolerate_mislabeled_levels,
            quality: state.settings.playback_quality,
        };
        let shared_state = Arc::clone(&self.state);
        state.preload_request(request, Instant::now(), |request| {
            self.runtime
                .spawn(Self::run_preload_worker(shared_state, request))
        });
    }

    /// Forwards a position update to the active reporting session and
    /// periodically commits the same position to the local mirror -- what
    /// survives an Android force-stop, throttled by
    /// [`LOCAL_PROGRESS_CHECKPOINT_INTERVAL`] (first positive position
    /// commits immediately). When a checkpoint is due, waits for the mirror
    /// writer's FIFO barrier rather than merely queueing, so a returned
    /// checkpoint is genuinely durable if killed right after; called on
    /// Dispatchers.IO, never Media3's playback thread.
    ///
    /// Only for the session `play_session_id` names: the installed one, or one a pending fallback
    /// took over (whose stream still plays); a report queued before a stop never reaches another.
    pub fn report_position(&self, play_session_id: String, ticks: i64) {
        let checkpoint = {
            let mut state = self.lock_state();
            if let Some(retiring) = retiring_session_locked(&mut state, &play_session_id) {
                retiring.session.on_position(ticks);
                retiring.reported = Some(ticks);
                return;
            }
            let Some(session) = installed_session_locked(&mut state, &play_session_id) else {
                return;
            };
            session.on_position(ticks);
            let item_id = session.context().item_id.clone();

            if state
                .local_progress_checkpoint
                .should_commit(ticks, Instant::now())
            {
                Self::playback_account_locked(&state).mirror.map(|mirror| {
                    // Acquired while `state` is still held: see the field's
                    // ordering comment for the stop/checkpoint race this
                    // closes; no path acquires these in the opposite order.
                    let write_guard = self
                        .playback_write
                        .lock()
                        .unwrap_or_else(|e| e.into_inner());
                    (write_guard, mirror, item_id)
                })
            } else {
                None
            }
        };

        if let Some((_write_guard, mirror, item_id)) = checkpoint {
            let committed = self
                .runtime
                .block_on(mirror.apply_local_user_data_and_wait(&item_id, ticks, None));
            if !committed {
                tracing::error!(
                    item_id,
                    "playback progress checkpoint did not commit to the mirror"
                );
            }
        }
    }

    /// Forwards a pause/resume edge to the installed session `play_session_id` names, as
    /// [`Self::report_position`].
    pub fn report_paused(&self, play_session_id: String, paused: bool) {
        let mut state = self.lock_state();
        if let Some(retiring) = retiring_session_locked(&mut state, &play_session_id) {
            retiring.session.on_pause(paused);
            return;
        }
        if let Some(session) = installed_session_locked(&mut state, &play_session_id) {
            session.on_pause(paused);
        }
    }

    /// Ends the active reporting session, *if it is still the one named by
    /// `play_session_id`*: sends the final Stopped report, then applies the
    /// same watch-state update locally to the mirror so Home shelves update
    /// instantly rather than waiting on an unreliable `UserDataChanged` event.
    /// See [`Self::report_position`] for the no-active-session contract.
    ///
    /// docs/18-playback-quality.md §2: Kotlin fires this from a scope that
    /// can outlive the ViewModel, so a delayed call can land after a
    /// *different* session became active; a `play_session_id` mismatch is a
    /// lost race, logged at `debug`, returning without touching state.
    pub fn stop_playback(&self, play_session_id: String, position_ticks: i64) {
        let (session, account) = {
            let mut state = self.lock_state();
            // A retired session is stopped here too: this is where the viewer actually left it.
            let session = match Self::take_session_or_revoke_locked(&mut state, &play_session_id) {
                Some(Taken::Active(session) | Taken::Retired(session, _)) => session,
                None => return,
            };
            (session, Self::playback_account_locked(&state))
        };

        Self::stop_session_and_sync_mirror(
            &self.runtime,
            &self.playback_write,
            session,
            position_ticks,
            account,
        );
    }

    /// Auto/Cap-mode local-failure transcode fallback
    /// (docs/18-playback-quality.md §2): called after Media3 proves the
    /// current `DirectPlay` plan genuinely can't play. `reason` is that local
    /// failure's text, carried onto `transcode_reason`. `DirectPlay` mode
    /// refuses outright with [`CoreError::WouldTranscode`], touching neither
    /// network nor session.
    ///
    /// Otherwise: parks the session in `retiring` (ended once: at install,
    /// stop, abandon, a failed negotiation or an account reset), re-negotiates with
    /// `force_transcode: true` and `StartTimeTicks = position_ticks`,
    /// accepted via [`transcode_url_from_decision`] (`None` ->
    /// `NoPlayableSource`). One fallback per item; a transcode that then
    /// fails is a plain fatal error.
    ///
    /// docs/18 §2.1: `play_session_id` must still name the active session (else
    /// [`CoreError::StalePlaybackSession`], untouched), and `request` claims ownership while the
    /// lock is released; a newer request, a stop/abandon of `play_session_id`, or an account reset
    /// revokes it, and the negotiated session is then never started.
    // Exported flat: uniffi records would add a type per call for no reader's benefit.
    #[allow(clippy::too_many_lines, clippy::too_many_arguments)]
    pub fn prepare_transcode_fallback(
        &self,
        item_id: String,
        position_ticks: i64,
        reason: String,
        play_session_id: String,
        failed: Option<FailedTrackFfi>,
        subtitle_stream_index: Option<i32>,
        request: PlaybackRequest,
    ) -> Result<PlaybackPlan, CoreError> {
        let (client, mirror, caps, tolerate_mislabeled_levels, quality, media_source_id, claim) = {
            let mut state = self.lock_state();
            if state.settings.playback_quality == PlaybackQuality::DirectPlay {
                return Err(CoreError::WouldTranscode { reasons: reason });
            }
            let account = Self::account_for_epoch_locked(&state, request.account_epoch)?;
            let (client, mirror) = account.require_open()?;
            let caps = state.caps.clone();
            let tolerate_mislabeled_levels = state.settings.tolerate_mislabeled_levels;
            let quality = state.settings.playback_quality;
            let is_still_current = state
                .reporting
                .as_ref()
                .is_some_and(|active| active.context().play_session_id == play_session_id);
            if !is_still_current {
                return Err(CoreError::StalePlaybackSession);
            }
            let claim = Self::admit_playback_request(&mut state, request, Some(play_session_id))?;
            state.local_progress_checkpoint.reset();
            let session = state
                .reporting
                .take()
                .expect("is_still_current just proved this is Some");
            // Its sidecars go now: the replacement brings its own, and a session that is only
            // retiring must not be fetched from.
            state.sidecars = None;
            let media_source_id = session.context().media_source_id.clone();
            // docs/18 §2.1: it keeps reporting until the claim resolves, so it ends where the
            // viewer left it -- at install, at a stop that revokes the claim, or when superseded.
            state.playback_owner.retiring = Some(Retiring {
                seq: claim.seq,
                session,
                ticks: position_ticks,
                reported: None,
            });
            (
                client,
                mirror,
                caps,
                tolerate_mislabeled_levels,
                quality,
                media_source_id,
                claim,
            )
        };
        let result = (|| -> Result<PlaybackPlan, CoreError> {
            let dto = self.fetch_item_dto(&client, &mirror, &item_id)?;
            let item_name = dto.name.clone().unwrap_or_default();
            let item_type = dto
                .type_
                .map(|t| t.to_string())
                .unwrap_or_else(|| "Unknown".to_string());
            let series_id = dto.series_id.map(|id| id.to_string());
            let series_name = dto.series_name.clone();
            let parent_index_number = dto.parent_index_number;
            let index_number = dto.index_number;

            let profile = build_android_profile_for_quality(
                &caps,
                tolerate_mislabeled_levels,
                &quality,
                false,
            );

            let (play_session_id, decision) = self.negotiate_playback_info(
                &client,
                &item_id,
                &profile,
                Some(position_ticks),
                fallback_options(media_source_id, failed, subtitle_stream_index),
            )?;
            // See [`transcode_url_from_decision`]'s own doc comment for exactly
            // which two decision shapes this accepts (a codec-blind source's
            // `DirectPlay` decision included).
            let (source, url) = transcode_url_from_decision(&client, &item_id, decision)
                .ok_or(jellyfin_core::CoreError::NoPlayableSource)?;

            let media_source_id = source.id.clone().unwrap_or_default();
            let ctx = jellyfin_core::ReportContext {
                item_id: item_id.clone(),
                media_source_id: media_source_id.clone(),
                play_session_id: play_session_id.clone(),
                play_method: jellyfin_api::ReportPlayMethod::Transcode,
            };
            let (sidecars, sidecar_tracks) =
                Self::session_sidecars(&client, &source, &play_session_id);
            self.install_claimed_session(&claim, &client, ctx, sidecars)?;

            let burned_subtitle_index = crate::types::burned_subtitle_index(&url);
            let plan = PlaybackPlan {
                item_id: item_id.clone(),
                item_name,
                url,
                media_source_id,
                play_session_id,
                play_method: PlayMethodFfi::Transcode,
                transcode_reason: Some(reason),
                server_verdict: None,
                // Always `true` here -- the `DirectPlay`-mode refusal above
                // already returned for the one value that would make this `false`.
                transcode_fallback_allowed: quality != PlaybackQuality::DirectPlay,
                start_position_ticks: position_ticks,
                runtime_ticks: source.run_time_ticks,
                container: source.container.clone(),
                item_type,
                series_name,
                parent_index_number,
                index_number,
                series_id,
                external_subtitles: sidecar_tracks,
                embedded_subtitles: crate::types::embedded_subtitles_of(&source),
                burned_subtitle_index,
            };

            Ok(plan)
        })();
        if result.is_err() {
            self.release_claim(&claim);
            // A fallback that never installs releases what it took, if it still holds it.
            let released = {
                let mut state = self.lock_state();
                let account = Self::playback_account_locked(&state);
                let owner = &mut state.playback_owner;
                if owner.retiring.as_ref().is_some_and(|r| r.seq == claim.seq) {
                    owner.retiring.take().map(|retiring| (retiring, account))
                } else {
                    None
                }
            };
            if let Some((retiring, account)) = released {
                let ticks = retiring.left_at();
                Self::stop_session_and_sync_mirror(
                    &self.runtime,
                    &self.playback_write,
                    retiring.session,
                    ticks,
                    account,
                );
            }
        }
        result
    }

    /// Discards the active reporting session, *if it is still the one named
    /// by `play_session_id`* (see [`Self::stop_playback`]), without sending
    /// a final Stopped report or touching the mirror -- for error paths
    /// where there is no real position worth persisting. See
    /// [`jellyfin_core::ReportingSession::abandon`] for what that trades
    /// away. A mismatch is a lost race, logged at `debug`, and returns
    /// without touching state.
    pub fn abandon_playback(&self, play_session_id: String) {
        let (taken, account) = {
            let mut state = self.lock_state();
            let taken = Self::take_session_or_revoke_locked(&mut state, &play_session_id);
            (taken, Self::playback_account_locked(&state))
        };
        match taken {
            Some(Taken::Active(session)) => session.abandon(),
            // It played, so it keeps the position the fallback recorded for it.
            Some(Taken::Retired(session, ticks)) => {
                Self::stop_session_and_sync_mirror(
                    &self.runtime,
                    &self.playback_write,
                    session,
                    ticks,
                    account,
                );
            }
            None => {}
        }
    }

    /// docs/07 Watch state: the app returned to the foreground; asks the current account's mirror
    /// for a played-state refresh. Parked mirrors are not notified; never blocks or touches the
    /// network inline (docs/01 §4).
    pub fn on_foreground(&self) {
        if let Ok(mirror) = self.require_mirror() {
            mirror.request_user_state_refresh(media_cache::UserStateReason::Foreground);
        }
    }

    /// docs/18 §2.1: both epochs read under one lock, so Kotlin never pairs the current epoch
    /// with a parked one from another instant.
    pub fn account_epochs(&self) -> AccountEpochs {
        let state = self.lock_state();
        AccountEpochs {
            epoch: state.account.epoch,
            parked: state
                .playback_owner
                .parked
                .as_ref()
                .map(|parked| parked.epoch),
        }
    }

    /// docs/18 §2.1: the saved account to re-authorize for a token rejected for `rejected` -- while
    /// the app uses it (browsed, or a playback runs or negotiates on it), or, for the player's own
    /// `failed_playback` (its teardown already ended the playback), while it is saved. A playback
    /// that ended keeps its account for next-up only, and an account switched away from is checked
    /// again on the way back to it, so `None` otherwise.
    pub fn reauthorization_account(
        &self,
        rejected: AccountIdentity,
        failed_playback: bool,
    ) -> Option<AccountInfo> {
        let in_use: Vec<String> = {
            let state = self.lock_state();
            let playing = state
                .playback_owner
                .parked
                .as_ref()
                .filter(|_| Self::parked_plays_locked(&state))
                .and_then(|parked| parked.account.mirror_dir.clone());
            let installed = state.account.installed().map(|i| i.mirror_dir.clone());
            installed.into_iter().chain(playing).collect()
        };
        session::load_list(&self.data_dir)
            .sessions
            .into_iter()
            .find(|saved| rejected.names(saved))
            .filter(|saved| failed_playback || in_use.contains(&saved.mirror_dir))
            .map(|saved| AccountInfo::of(&saved))
    }

    /// Credits-aware next-up: for an `Episode` in the mirror, the next
    /// episode within its series ordered by (season, episode number),
    /// skipping virtual entries -- crossing into the next season is in
    /// scope. `None` if the mirror isn't open, `item_id` isn't a real
    /// `Episode`, or it's the series' last episode. See
    /// [`crate::next_episode::next_episode_after`] for the implementation.
    pub fn next_episode_after(&self, item_id: String) -> Option<Card> {
        let mirror = self.require_mirror().ok()?;
        crate::next_episode::next_episode_after(&mirror, &item_id)
    }

    /// Resolves both episode-edge transport targets in one call. The local
    /// mirror is always tried first; a live series query is used only when
    /// the mirror cannot place the current item at all, restoring controls
    /// for externally launched/fresh items without routine network load.
    /// `account_epoch` names the player's account (docs/18 §2.1); `None` is the current one.
    pub fn episode_neighbors(
        &self,
        item_id: String,
        series_id: String,
        account_epoch: Option<u64>,
    ) -> Option<EpisodeNeighbors> {
        let account = self.read_account(account_epoch).ok()?;
        // Intent/deep-link ids commonly arrive as 32 compact hex digits,
        // while Jellyfin DTO UUIDs stringify with hyphens. Use one canonical
        // spelling for mirror keys/server params/response match, or a
        // successful adjacent-episode response is discarded.
        let canonical_item_id = canonicalize_item_id(&item_id);
        if let Some(mirror) = &account.mirror {
            if let Some(neighbors) =
                crate::next_episode::episode_neighbors(mirror, &canonical_item_id)
            {
                return Some(neighbors);
            }
        }

        let client = account.client?;
        let mut episodes = self
            .runtime
            .block_on(client.get_adjacent_episodes(
                &series_id,
                &canonical_item_id,
                &[
                    "Overview".to_string(),
                    "PremiereDate".to_string(),
                    "ImageBlurHashes".to_string(),
                    "ParentId".to_string(),
                    "SeriesPrimaryImageTag".to_string(),
                ],
            ))
            .ok()?
            .items;

        // Do not rely exclusively on a server version's multi-key sort.
        // Missing indices sort last, preserving the useful ordered prefix.
        episodes.sort_by_key(|episode| {
            (
                episode.parent_index_number.unwrap_or(i32::MAX),
                episode.index_number.unwrap_or(i32::MAX),
            )
        });
        let current = episodes.iter().position(|episode| {
            episode.id.map(|id| id.to_string()).as_deref() == Some(&canonical_item_id)
        })?;
        let playable = |episode: &&jellyfin_api::models::BaseItemDto| {
            episode.type_ == Some(jellyfin_api::models::BaseItemKind::Episode)
                && episode.location_type != Some(jellyfin_api::models::LocationType::Virtual)
        };
        let previous = episodes[..current]
            .iter()
            .rev()
            .find(playable)
            .and_then(|episode| Card::try_from(episode).ok());
        let next = episodes[current + 1..]
            .iter()
            .find(playable)
            .and_then(|episode| Card::try_from(episode).ok());
        Some(EpisodeNeighbors { previous, next })
    }

    /// docs/11 item 11, Series Play/Resume: ALL of `series_id`'s episodes
    /// across EVERY season (Specials first), not just the Detail page's
    /// selected season tab. Backs Kotlin's
    /// `DetailFormatting.resolvePrimaryAction`/`resolveSeriesAction` so the
    /// button finds an in-progress/next-unplayed episode in any season.
    ///
    /// One local-mirror-only round trip per Detail page open (never per
    /// season-tab switch). docs/07 §5: "mirror not open yet", "query failed" and "series has no
    /// episodes" stay apart.
    pub fn series_episodes_read(&self, series_id: String) -> BrowseRead {
        let Ok(mirror) = self.require_mirror() else {
            return BrowseRead::NotReady;
        };
        match crate::next_episode::all_episodes_of_series(&mirror, &series_id) {
            Some(rows) => BrowseRead::Items {
                cards: rows.into_iter().map(Card::from).collect(),
            },
            None => {
                tracing::warn!("series episodes query failed");
                BrowseRead::Failed
            }
        }
    }

    /// Automatic track selection (docs/09 step 3): converts `tracks` to
    /// `playback_policy`'s own shape, looks up `series_id`'s remembered pref
    /// (if any), and delegates to
    /// [`playback_policy::tracks::resolve_track_selection`] against
    /// `Settings::language`. Called once per loaded item, right after
    /// Media3 announces its tracks.
    pub fn resolve_tracks(
        &self,
        series_id: Option<String>,
        tracks: Vec<TrackInfo>,
    ) -> TrackDecisionFfi {
        let policy_tracks: Vec<playback_policy::tracks::Track> =
            tracks.into_iter().map(Into::into).collect();

        let state = self.lock_state();
        let global = playback_policy::prefs::LanguagePrefs::from(state.settings.language.clone());
        let series_pref = series_id.and_then(|id| state.track_prefs.get(&id).cloned());
        drop(state);

        playback_policy::tracks::resolve_track_selection(
            &policy_tracks,
            series_pref.as_ref(),
            &global,
        )
        .into()
    }

    /// Persists one half (audio or subtitle; `Video` is a no-op)
    /// of `series_id`'s remembered track choice, immediately updating the in-memory copy
    /// [`Self::resolve_tracks`] reads. `Unset` clears that half, `Off` remembers "no subtitles"
    /// (docs/18 §3.1; audio has no Off, so an audio `Off` is refused, as is `Video`). A `Key` must already be the result of
    /// [`crate::track_pref_key_of`]. A disk write failure is logged and swallowed, same contract
    /// as [`Self::set_settings`].
    pub fn remember_track_memory(
        &self,
        series_id: String,
        kind: TrackKindFfi,
        memory: TrackMemoryFfi,
    ) {
        let (revision, snapshot) = {
            let mut state = self.lock_state();
            if !track_prefs::remember(&mut state.track_prefs, series_id, kind, memory.into()) {
                return;
            }
            state.track_prefs_revision += 1;
            (state.track_prefs_revision, state.track_prefs.clone())
        };
        if let Err(e) = self
            .track_prefs_save
            .save(revision, || track_prefs::save(&self.data_dir, &snapshot))
        {
            tracing::warn!(error = %e, "failed to persist track-prefs.json to disk");
        }
    }

    /// Marks a single Movie/Episode as watched or unwatched, applying the
    /// server's returned `UserItemDataDto` straight into the mirror row --
    /// instant and authoritative, no round trip through a WS event. If
    /// `item_id` is an Episode, spawns a background
    /// [`media_cache::Mirror::refresh_items`] for its season/series without
    /// blocking this call.
    pub fn set_played(&self, item_id: String, played: bool) -> Result<(), CoreError> {
        let (client, mirror) = self.read_account(None)?.require_open()?;

        // docs/07 Watch state, Ordering: this mark is the newest action on the item; a failed mark
        // drops the guard, so the paused Stopped resumes.
        let superseded = self.runtime.block_on(jellyfin_core::supersede(&[&item_id]));
        let data = self.runtime.block_on(async {
            if played {
                client.mark_played(&item_id).await
            } else {
                client.mark_unplayed(&item_id).await
            }
        })?;
        superseded.commit();
        self.runtime
            .block_on(mirror.apply_user_data(vec![(item_id.clone(), data)]));

        let refresh_ids = episode_scope_ids(&mirror, &item_id);
        if !refresh_ids.is_empty() {
            mirror.request_next_up_refresh();
            self.runtime.spawn(async move {
                mirror.refresh_items(refresh_ids).await;
            });
        }

        Ok(())
    }

    /// Bulk mark for a Series or Season scope (docs/19 §1.1): marks it on the server, applies it locally (optimistic)
    /// to every non-virtual episode, and returns once those applies are enqueued; a background task then re-fetches
    /// the scope for authoritative counts.
    pub fn set_played_recursive(&self, scope_id: String, played: bool) -> Result<(), CoreError> {
        let (client, mirror) = self.read_account(None)?.require_open()?;

        let scope_item = mirror.item(&scope_id);
        let is_series = scope_item
            .as_ref()
            .and_then(|dto| dto.type_)
            .map(|t| t.to_string())
            .as_deref()
            == Some("Series");

        let episode_ids = scope_episode_ids(if is_series {
            crate::next_episode::all_episodes_of_series(&mirror, &scope_id)
        } else {
            mirror.children_checked(&scope_id, media_cache::Sort::IndexNumber, 0, u32::MAX)
        })?;

        // docs/07 Watch state, Ordering: this mark is the newest action on exactly the items it
        // covers, so only their settling Stopped reports are superseded, and only a successful
        // mark cancels them.
        let covered: Vec<&str> = std::iter::once(scope_id.as_str())
            .chain(episode_ids.iter().map(String::as_str))
            .collect();
        let superseded = self.runtime.block_on(jellyfin_core::supersede(&covered));
        self.runtime.block_on(async {
            if played {
                client.mark_played(&scope_id).await
            } else {
                client.mark_unplayed(&scope_id).await
            }
        })?;
        superseded.commit();

        self.runtime.block_on(async {
            for id in &episode_ids {
                mirror.apply_local_user_data(id, 0, Some(played)).await;
            }
        });

        // Season/series ids to refresh alongside the scope itself, for
        // authoritative unplayed counts once the local applies above commit.
        let mut refresh_ids: Vec<String> = vec![scope_id.clone()];
        if is_series {
            if let Some(seasons) =
                mirror.children_checked(&scope_id, media_cache::Sort::IndexNumber, 0, u32::MAX)
            {
                refresh_ids.extend(seasons.into_iter().map(|s| s.id));
            }
        } else if let Some(series_id) = scope_item.and_then(|dto| dto.series_id) {
            refresh_ids.push(series_id.to_string());
        }

        let last_id = episode_ids.last().cloned();
        let mirror = mirror.clone();
        // A season or series mark moves Next Up (docs/07 §1).
        mirror.request_next_up_refresh();
        self.runtime.spawn(async move {
            if let Some(last_id) = last_id {
                mirror
                    .apply_local_user_data_and_wait(&last_id, 0, Some(played))
                    .await;
            }
            mirror.refresh_items(refresh_ids).await;
        });

        Ok(())
    }

    /// Adds/removes a Movie/Series/Episode favorite, applying the server's
    /// returned DTO the same way [`Self::set_played`] does. In season scope
    /// the menu passes the *series'* id here (docs/19 §1.1); this method
    /// itself has no scope concept.
    pub fn set_favorite(&self, item_id: String, favorite: bool) -> Result<(), CoreError> {
        let (client, mirror) = self.read_account(None)?.require_open()?;

        let data = self
            .runtime
            .block_on(client.set_favorite(&item_id, favorite))?;
        self.runtime
            .block_on(mirror.apply_user_data(vec![(item_id, data)]));
        Ok(())
    }

    /// The session's live BoxSet list for "Add to collection" (docs/19
    /// §1.3/§2.1) -- cached ten minutes per session. Names are shown
    /// verbatim (CLAUDE.md's "server-configured names verbatim" rule).
    pub fn list_collections(&self) -> Result<Vec<CollectionInfo>, CoreError> {
        let (epoch, client) = {
            let state = self.lock_state();
            let installed = state.account.installed().ok_or(CoreError::NotSignedIn)?;
            if let Some((fetched_at, cached)) = &installed.caches.collections {
                if fetched_at.elapsed() < COLLECTIONS_CACHE_TTL {
                    return Ok(cached.clone());
                }
            }
            (state.account.epoch, installed.client.clone())
        };
        let items = self.runtime.block_on(client.list_collections())?;
        let collections: Vec<CollectionInfo> = items
            .into_iter()
            .filter_map(|dto| {
                Some(CollectionInfo {
                    id: dto.id?.to_string(),
                    name: dto.name.unwrap_or_default(),
                })
            })
            .collect();

        self.store_if_epoch(epoch, |caches| {
            caches.collections = Some((Instant::now(), collections.clone()));
        });
        Ok(collections)
    }

    /// Adds `item_id` to `collection_id` (docs/19 §1.3). Membership is
    /// server-owned, but doesn't wait for the next `LibraryChanged`: once
    /// the server call lands, spawns a background
    /// [`media_cache::Mirror::refresh_collection_membership`] so `ALREADY
    /// IN` reads true on the panel's next open. Skipped when no mirror is open.
    pub fn add_to_collection(
        &self,
        collection_id: String,
        item_id: String,
    ) -> Result<(), CoreError> {
        let current = self.lock_state().account.current_client();
        let (epoch, client) = current.ok_or(CoreError::NotSignedIn)?;
        self.runtime
            .block_on(client.add_to_collection(&collection_id, &item_id))?;

        // docs/18 §2.1: the follow-up refreshes the mirror of the account that posted, only while
        // it is still installed.
        let mirror = {
            let mut state = self.lock_state();
            let installed = state.account.installed_mut(epoch);
            installed.and_then(|i| Some(i.bundle.as_ref()?.mirror.clone()))
        };
        if let Some(mirror) = mirror {
            self.runtime.spawn(async move {
                mirror.refresh_collection_membership(&collection_id).await;
            });
        }
        Ok(())
    }

    /// docs/19 §1.3/§2.3: which collections `item_id` is currently a member
    /// of, per the local mirror's `collection_members` table. Empty when the
    /// mirror isn't open, same convention as [`Self::series_episodes_read`].
    pub fn collection_ids_containing(&self, item_id: String) -> Vec<String> {
        let Ok(mirror) = self.require_mirror() else {
            return Vec::new();
        };
        mirror.collection_ids_containing(&item_id)
    }

    /// Fire-and-forget metadata refresh (docs/19 §1.4); the mirror
    /// reconciles when `LibraryChanged` arrives.
    pub fn refresh_metadata(&self, item_id: String) -> Result<(), CoreError> {
        let client = self.require_client()?;
        self.runtime.block_on(client.refresh_item(&item_id))?;
        Ok(())
    }

    /// One authenticated round trip (`GET /Users/Me`). Browse reads the mirror, so without this
    /// a revoked token goes unnoticed until the first Play; the app calls it at launch, account
    /// switch and return to foreground, and routes `Unauthorized` to re-authorization.
    pub fn validate_session(&self) -> Result<(), CoreError> {
        let client = self.require_client()?;
        self.runtime.block_on(client.current_user())?;
        Ok(())
    }

    /// Whether the signed-in user is a server administrator (docs/19 §1.1)
    /// -- cached per session, never re-fetched once known (unlike
    /// [`Self::list_collections`]'s ten-minute TTL).
    pub fn is_administrator(&self) -> Result<bool, CoreError> {
        let (epoch, client) = {
            let state = self.lock_state();
            let installed = state.account.installed().ok_or(CoreError::NotSignedIn)?;
            if let Some(cached) = installed.caches.is_admin {
                return Ok(cached);
            }
            (state.account.epoch, installed.client.clone())
        };
        let user = self.runtime.block_on(client.current_user())?;
        // docs/19 §2.3: a reply without a policy proves nothing about the role, so it is not cached.
        let Some(is_admin) = user.policy.and_then(|policy| policy.is_administrator) else {
            return Ok(false);
        };
        self.store_if_epoch(epoch, |caches| caches.is_admin = Some(is_admin));
        Ok(is_admin)
    }
}

/// [`JellybeamCore::negotiate_playback_source`]'s return shape: source, url,
/// `play_session_id`, play method, transcode reason, server verdict.
type NegotiatedSource = (
    jellyfin_api::models::MediaSourceInfo,
    String,
    String,
    PlayMethodFfi,
    Option<String>,
    Option<String>,
);

impl JellybeamCore {
    /// docs/18 §2.1: the current account's epoch; Kotlin reads it through [`Self::account_epochs`].
    pub(crate) fn account_epoch(&self) -> u64 {
        self.lock_state().account.epoch
    }

    /// docs/18 §2.1: the parked playback's epoch, as [`Self::account_epochs`] reports it.
    #[cfg(test)]
    pub(crate) fn parked_account_epoch(&self) -> Option<u64> {
        self.account_epochs().parked
    }

    /// docs/09: gives the mirror the Next Up options of `state.settings`, called under the lock that
    /// writes them or installs the mirror so they match the latest settings whatever order
    /// concurrent saves finish in.
    fn push_next_up_options_locked(state: &State) {
        if let Some(bundle) = state.account.bundle() {
            bundle
                .mirror
                .set_next_up_options(next_up_options_from(&state.settings));
        }
    }

    /// The playback client and authed URL of sidecar `index` in session `play_session_id`.
    fn sidecar_source(
        &self,
        play_session_id: &str,
        index: i32,
    ) -> Option<(jellyfin_api::JellyfinClient, String)> {
        let state = self.lock_state();
        let url = state
            .sidecars
            .as_ref()?
            .url_for(play_session_id, index)?
            .to_string();
        Some((Self::playback_account_locked(&state).client?, url))
    }

    /// Runs one sidecar fetch under its deadline; a failure is logged by class only.
    fn sidecar_fetch<T>(
        &self,
        index: i32,
        fetch: impl std::future::Future<Output = Result<T, jellyfin_api::ApiError>>,
    ) -> Option<T> {
        // The timer is built inside the runtime: tokio's timer panics when created outside one.
        let fetched = self.runtime.block_on(async {
            tokio::time::timeout(crate::types::SUBTITLE_FETCH_TIMEOUT, fetch).await
        });
        let kind = match fetched {
            Ok(Ok(body)) => return Some(body),
            Ok(Err(err)) => crate::types::subtitle_failure_kind(&err),
            Err(_) => "timeout",
        };
        tracing::warn!(index, kind, "sidecar subtitle fetch failed");
        None
    }

    /// docs/18 §3.2: `source`'s sidecars for [`Self::fetch_external_subtitle`], installed with
    /// the session they belong to ([`Self::install_reporting_session`]), and their descriptors
    /// for the plan.
    fn session_sidecars(
        client: &jellyfin_api::JellyfinClient,
        source: &jellyfin_api::models::MediaSourceInfo,
        play_session_id: &str,
    ) -> (
        Option<crate::types::SessionSidecars>,
        Vec<crate::types::ExternalSubtitleFfi>,
    ) {
        let sources = crate::types::external_subtitles_of(source, |u| client.delivery_url(u));
        let tracks = sources.iter().map(|s| s.track.clone()).collect();
        let sidecars = (!sources.is_empty()).then(|| crate::types::SessionSidecars {
            play_session_id: play_session_id.to_string(),
            sources,
        });
        (sidecars, tracks)
    }

    /// `pub(crate)` so `seerr.rs`'s sibling `impl JellybeamCore` block can
    /// read/write `State::seerr` the same way every method in this file does.
    /// The signed-in client, cloned out of the state lock; `NotSignedIn` before sign-in.
    pub(crate) fn require_client(&self) -> Result<jellyfin_api::JellyfinClient, CoreError> {
        self.lock_state()
            .account
            .installed()
            .map(|i| i.client.clone())
            .ok_or(CoreError::NotSignedIn)
    }

    pub(crate) fn lock_state(&self) -> StateGuard<'_> {
        self.state.lock()
    }

    /// docs/18 §2.1: applies `store` to the account caches only while `epoch`, captured with the
    /// client the fetch used, is still the installed account's; returns whether it applied.
    pub(crate) fn store_if_epoch(
        &self,
        epoch: u64,
        store: impl FnOnce(&mut AccountCaches),
    ) -> bool {
        let mut state = self.lock_state();
        let Some(installed) = state.account.installed_mut(epoch) else {
            return false;
        };
        store(&mut installed.caches);
        true
    }

    /// docs/18 §2.1: reads the account caches only while `epoch` is still the installed account's;
    /// `None` once it is not, so a late fetch never answers with the next account's cache.
    pub(crate) fn read_if_epoch<T>(
        &self,
        epoch: u64,
        read: impl FnOnce(&AccountCaches) -> T,
    ) -> Option<T> {
        let mut state = self.lock_state();
        state
            .account
            .installed_mut(epoch)
            .map(|installed| read(&installed.caches))
    }

    /// docs/18 §2.1: the installed account's epoch, server and user, read under one lock so the
    /// three always name the same account; the url is as saved, so it keys the Seerr config
    /// exactly as written. `None` when no account is installed.
    pub(crate) fn installed_identity(&self) -> Option<InstalledIdentity> {
        Self::installed_identity_locked(&self.lock_state())
    }

    /// [`Self::installed_identity`] for a caller already holding the state lock.
    pub(crate) fn installed_identity_locked(state: &State) -> Option<InstalledIdentity> {
        Some(InstalledIdentity {
            epoch: state.account.epoch,
            account: state.account.installed()?.identity(),
        })
    }

    /// `pub(crate)` accessor for `seerr.rs`'s config-store path; see
    /// [`Self::lock_state`].
    pub(crate) fn data_dir(&self) -> &std::path::Path {
        &self.data_dir
    }

    pub(crate) fn pre_auth_http(&self) -> &jellyfin_api::HttpClient {
        self.pre_auth_http
            .get_or_init(jellyfin_api::build_http_client)
    }

    /// `pub(crate)` accessor for `seerr.rs`'s `block_on` calls.
    pub(crate) fn runtime(&self) -> &tokio::runtime::Runtime {
        &self.runtime
    }

    pub(crate) fn seerr_config(&self) -> &crate::seerr::SeerrConfigStore {
        &self.seerr_config
    }

    /// docs/18 §2.1: the one account change: installs `next` (`None`: signed out) under the next
    /// epoch and invalidates the preload; a live playback is parked on its own account unless
    /// `change` leaves it, which ends it; the outgoing bundle goes out in the reset for teardown.
    fn reset_account_state_locked(
        state: &mut State,
        next: Option<Installed>,
        change: AccountChange,
    ) -> AccountReset {
        let outgoing = SessionAccount::of(state);
        let epoch = state.account.epoch;
        // The newest playback is on the outgoing account, or on a parked one that came back.
        let playing_here = Self::playing_epoch_locked(state) == Some(epoch);
        let parked_here = state
            .playback_owner
            .parked
            .as_ref()
            .is_some_and(|parked| parked.is_account(outgoing.mirror_dir.as_deref()));
        let parked_plays = Self::parked_plays_locked(state);
        let preload_task = state.preload_invalidate();
        let leaving = change == AccountChange::Leave;
        let mut reset = if leaving && (playing_here || parked_here && parked_plays) {
            Self::end_playback_locked(state)
        } else {
            let owner = &mut state.playback_owner;
            if playing_here {
                // A newer playback than any parked one: it alone keeps its account.
                owner.parked = Some(ParkedAccount {
                    epoch,
                    account: outgoing,
                });
            } else if leaving && parked_here {
                owner.parked = None;
            } else if let Some(parked) = owner.parked.as_mut().filter(|_| parked_here) {
                // The parked account is leaving again: keep its freshest client (a re-sign-in's).
                parked.account = parked.account.refreshed_by(outgoing);
            }
            AccountReset {
                preload_task: None,
                reporting: None,
                retiring: None,
                account: Self::playback_account_locked(state),
                bundle: None,
                refresh: None,
            }
        };
        reset.preload_task = preload_task;
        reset.bundle = state.account.installed.take().and_then(|old| old.bundle);
        state.account.epoch = epoch.wrapping_add(1);
        reset.refresh = next
            .as_ref()
            .map(|i| (state.account.epoch, i.client.clone()));
        state.account.installed = next;
        reset
    }

    /// docs/18 §2.1: installs `next`, read under epoch `tag`, unless the epoch has moved since or
    /// `next` is already installed (a recreated Activity, a failed switch) -- neither is an account
    /// change, so live playback and its caches stay. `None` when nothing changed.
    fn restore_account_locked(
        state: &mut State,
        tag: u64,
        next: Installed,
    ) -> Option<AccountReset> {
        let installed = state.account.installed().is_some_and(|i| {
            i.mirror_dir == next.mirror_dir
                && session::same_server(&i.account.server_url, &next.account.server_url)
                && i.account.user_id == next.account.user_id
        });
        (tag == state.account.epoch && !installed)
            .then(|| Self::reset_account_state_locked(state, Some(next), AccountChange::Replace))
    }

    /// docs/18 §2.1: ends the live playback -- its claim, sessions and sidecars -- for disposal
    /// outside the lock with the account they belong to.
    fn end_playback_locked(state: &mut State) -> AccountReset {
        let account = Self::playback_account_locked(state);
        let owner = &mut state.playback_owner;
        owner.claim = None;
        owner.installed_replaces = None;
        owner.parked = None;
        let retiring = owner.retiring.take();
        state.sidecars = None;
        state.local_progress_checkpoint.reset();
        AccountReset {
            preload_task: None,
            reporting: state.reporting.take(),
            retiring,
            account,
            bundle: None,
            refresh: None,
        }
    }

    /// docs/18 §2.1: whether a stream plays -- an installed session, or the one a fallback took
    /// over -- which every account's sync yields to. A negotiation alone streams nothing yet.
    fn playback_live_locked(state: &State) -> bool {
        state.reporting.is_some() || state.playback_owner.retiring.is_some()
    }

    /// docs/18 §2.1: the epoch the newest playback plays on -- a negotiating claim's, else a live
    /// stream's (the parked account's while one is parked) -- so a stopped playback owns nothing.
    fn playing_epoch_locked(state: &State) -> Option<u64> {
        let owner = &state.playback_owner;
        owner.claim.as_ref().map(|claim| claim.epoch).or_else(|| {
            Self::playback_live_locked(state).then(|| {
                owner
                    .parked
                    .as_ref()
                    .map_or(state.account.epoch, |parked| parked.epoch)
            })
        })
    }

    /// docs/18 §2.1: whether the newest playback is the parked one, so leaving its account ends
    /// it; a parked account whose playback ended or was replaced is only dropped.
    fn parked_plays_locked(state: &State) -> bool {
        let parked = state
            .playback_owner
            .parked
            .as_ref()
            .map(|parked| parked.epoch);
        parked.is_some() && Self::playing_epoch_locked(state) == parked
    }

    /// docs/18 §2.1: a negotiation that fails undoes its admission if it still holds its claim,
    /// so a playback that never started neither owns playback nor gets parked by a switch.
    fn release_claim(&self, claim: &PlaybackClaim) {
        let mut state = self.lock_state();
        let owner = &mut state.playback_owner;
        if owner.claim.as_ref() == Some(claim) {
            owner.claim = None;
        }
    }

    /// [`Self::prepare_playback`] once `claim` is admitted: negotiates and installs its session.
    fn negotiate_admitted(
        &self,
        item_id: String,
        start_from_beginning: bool,
        admitted: AdmittedPlay,
        claim: &PlaybackClaim,
    ) -> Result<PlaybackPlan, CoreError> {
        let AdmittedPlay {
            client,
            mirror,
            caps,
            tolerate_mislabeled_levels,
            quality,
            preload,
        } = admitted;
        let PreloadForPlay {
            cached,
            join,
            abort,
        } = preload;
        // Abort is deliberately not awaited: Play must enter its own
        // critical path immediately. The generation gate already prevents
        // the cancelled task from publishing even mid-poll.
        Self::abort_task(abort);

        // Same-item join: wait for the negotiation already talking to the
        // server, then consume whatever it published -- the same single-use
        // read a fresh cache hit would do, just delayed instead of skipped.
        // A miss (the slot was invalidated underneath it, or it resolved to a
        // Transcode plan, which is never cached) falls through to a normal
        // negotiation below exactly like an expired cache entry would.
        let cached = if let Some(mut join) = join {
            self.runtime.block_on(async {
                let _ = join.settled.changed().await;
            });
            self.lock_state()
                .preload_take_joined(&join, &item_id, Instant::now())
        } else {
            cached
        };

        let dto = self.fetch_item_dto(&client, &mirror, &item_id)?;
        refuse_if_virtual(&item_id, &dto)?;

        let item_name = dto.name.clone().unwrap_or_default();
        let item_type = dto
            .type_
            .map(|t| t.to_string())
            .unwrap_or_else(|| "Unknown".to_string());
        let series_id = dto.series_id.map(|id| id.to_string());
        let series_name = dto.series_name.clone();
        let parent_index_number = dto.parent_index_number;
        let index_number = dto.index_number;
        let start_ticks = resolved_start_ticks(start_from_beginning, &dto);

        let profile =
            build_android_profile_for_quality(&caps, tolerate_mislabeled_levels, &quality, true);

        // Outro timing and trickplay never touch this critical path: Kotlin
        // derives Outro start from its own fire-and-forgotten
        // `get_media_segments` call, and gets trickplay from
        // [`Self::get_playback_osd_detail`]. `get_playback_info` + `decide_playback`
        // (skipped on a preload cache hit) are the only network-needing
        // steps left here.
        let (source, url, play_session_id, play_method, transcode_reason, server_verdict) = self
            .negotiate_playback_source(
                &client,
                &item_id,
                &caps,
                tolerate_mislabeled_levels,
                quality,
                &profile,
                start_ticks,
                cached,
            )?;

        let media_source_id = source.id.clone().unwrap_or_default();
        let report_play_method = match play_method {
            PlayMethodFfi::DirectPlay => jellyfin_api::ReportPlayMethod::DirectPlay,
            PlayMethodFfi::Transcode => jellyfin_api::ReportPlayMethod::Transcode,
        };
        let ctx = jellyfin_core::ReportContext {
            item_id: item_id.clone(),
            media_source_id: media_source_id.clone(),
            play_session_id: play_session_id.clone(),
            play_method: report_play_method,
        };
        // Started fresh every call, cache hit or not -- a preload never starts a reporting session.
        let (sidecars, sidecar_tracks) = Self::session_sidecars(&client, &source, &play_session_id);
        self.install_claimed_session(claim, &client, ctx, sidecars)?;
        let burned_subtitle_index = crate::types::burned_subtitle_index(&url);
        let plan = PlaybackPlan {
            item_id: item_id.clone(),
            item_name,
            url,
            media_source_id,
            play_session_id,
            play_method,
            transcode_reason,
            server_verdict,
            transcode_fallback_allowed: quality != PlaybackQuality::DirectPlay,
            start_position_ticks: start_ticks.unwrap_or(0),
            runtime_ticks: source.run_time_ticks,
            container: source.container.clone(),
            item_type,
            series_name,
            parent_index_number,
            index_number,
            series_id,
            external_subtitles: sidecar_tracks,
            embedded_subtitles: crate::types::embedded_subtitles_of(&source),
            burned_subtitle_index,
        };

        Ok(plan)
    }

    /// The account the live playback's sessions belong to: the parked one, else the current.
    fn playback_account_locked(state: &State) -> SessionAccount {
        state
            .playback_owner
            .parked
            .as_ref()
            .map_or_else(|| SessionAccount::of(state), |parked| parked.resolve(state))
    }

    /// docs/18 §2.1: the account a request minted under `epoch` plays on -- the current one, or
    /// the parked one its playback kept; any other epoch is [`CoreError::AccountChanged`].
    fn account_for_epoch_locked(state: &State, epoch: u64) -> Result<SessionAccount, CoreError> {
        if epoch == state.account.epoch {
            return Ok(SessionAccount::of(state));
        }
        state
            .playback_owner
            .parked
            .as_ref()
            .filter(|parked| parked.epoch == epoch)
            .map(|parked| parked.resolve(state))
            .ok_or(CoreError::AccountChanged)
    }

    /// [`Self::account_for_epoch_locked`] for a player read; `None` is the current account.
    fn read_account(&self, epoch: Option<u64>) -> Result<SessionAccount, CoreError> {
        let state = self.lock_state();
        match epoch {
            None => Ok(SessionAccount::of(&state)),
            Some(epoch) => Self::account_for_epoch_locked(&state, epoch),
        }
    }

    /// Outside the lock: aborts the preload and ends the outgoing sessions with the same final
    /// writes as a stop (report, mirror), to the outgoing account. The installed one ends at its
    /// last reported position (at most a second old); with none reported yet it is abandoned
    /// instead, since a Stopped at 0 would overwrite the resume point. The returned receivers
    /// settle when each final report has landed (docs/18 §2.1).
    fn dispose_account_reset(&self, reset: AccountReset) -> Vec<FinalSettled> {
        Self::abort_task(reset.preload_task);
        let end = |session, ticks| {
            Self::stop_session_and_sync_mirror(
                &self.runtime,
                &self.playback_write,
                session,
                ticks,
                reset.account.clone(),
            )
        };
        let mut pending = Vec::new();
        if let Some(retiring) = reset.retiring {
            let ticks = retiring.left_at();
            pending.push(end(retiring.session, ticks));
        }
        match reset.reporting {
            Some(session) if session.last_position() > 0 => {
                let ticks = session.last_position();
                pending.push(end(session, ticks));
            }
            Some(session) => session.abandon(),
            None => {}
        }
        self.stop_event_bus(reset.bundle);
        if let Some((epoch, client)) = reset.refresh {
            self.runtime.spawn(Self::refresh_server_version_once(
                epoch,
                client,
                self.data_dir.clone(),
                self.state.clone(),
                self.session_lock.clone(),
            ));
        }
        pending
    }

    fn abort_task(task: Option<tokio::task::JoinHandle<()>>) {
        if let Some(task) = task {
            task.abort();
        }
    }

    /// Drains one active request followed by at most the latest replacement
    /// target; a focus change can replace the pending target again but never
    /// creates a second network worker.
    async fn run_preload_worker(shared_state: Arc<SharedState>, mut request: PreloadRequest) {
        let mut exit = PreloadExit {
            shared: Arc::clone(&shared_state),
            generation: request.generation,
        };
        loop {
            let cache = Self::build_preload_cache(&request).await;
            let next =
                shared_state
                    .lock()
                    .preload_publish(request.generation, cache, Instant::now());
            match next {
                PreloadNext::Run(next) => {
                    exit.generation = next.generation;
                    request = next;
                }
                PreloadNext::Stale | PreloadNext::Idle => return,
            }
        }
    }

    /// Runs one speculative negotiation, every await cancellable by the
    /// worker's `JoinHandle::abort`. `request.quality` is always
    /// `DirectPlay`/`Auto` here -- `preload_playback` never spawns a request
    /// in `Cap` mode. Resolves through the same [`resolve_plan`] matrix
    /// [`JellybeamCore::prepare_playback`] uses, but only ever caches an
    /// `Ok(DirectPlay)` result; anything else discards.
    async fn build_preload_cache(request: &PreloadRequest) -> Option<PreloadCache> {
        let dto = Self::fetch_item_dto_async(&request.client, &request.mirror, &request.item_id)
            .await
            .ok()?;
        refuse_if_virtual(&request.item_id, &dto).ok()?;

        let start_ticks = resolved_start_ticks(false, &dto);
        let profile = build_android_profile_for_quality(
            &request.caps,
            request.tolerate_mislabeled_levels,
            &request.quality,
            true,
        );
        let (play_session_id, decision) = Self::negotiate_playback_info_async(
            &request.client,
            &request.item_id,
            &profile,
            start_ticks,
            jellyfin_api::PlaybackInfoOptions::default(),
        )
        .await
        .ok()?;
        let video_codec = video_stream_codec(decision_source(&decision));
        let supported_codecs = jellyfin_core::android_direct_play_video_codecs(&request.caps);
        let ResolvedPlan::DirectPlay {
            source,
            url,
            server_verdict,
        } = resolve_plan(
            &request.quality,
            decision,
            video_codec.as_deref(),
            &supported_codecs,
            request.tolerate_mislabeled_levels,
        )
        .ok()?
        else {
            return None;
        };

        Some(PreloadCache {
            generation: request.generation,
            item_id: request.item_id.clone(),
            source,
            url,
            play_session_id,
            server_verdict,
            created_at: Instant::now(),
        })
    }

    /// Identity for a first contact or a fresh sign-in at `server_url`; see
    /// [`device_id::for_server`].
    fn client_identity(&self, server_url: &str) -> jellyfin_api::ClientIdentity {
        Self::identity_with(device_id::for_server(&self.device_id, server_url))
    }

    /// docs/18 §2.1: asks the removed account's server to end its session with that account's own
    /// token once `final_reports` have settled or [`FINAL_REPORT_SETTLE_BUDGET`] ends, fire-and-forget
    /// (bounded by the client's request timeout), so a slow or dead server never delays removal.
    fn spawn_logout(&self, session: &session::SessionFile, final_reports: Vec<FinalSettled>) {
        let client = jellyfin_api::JellyfinClient::from_token(
            &session.server_url,
            Self::saved_identity(session),
            &session.token,
        )
        .with_user_id(&session.user_id);
        self.runtime.spawn(async move {
            // The token is revoked only after the account's last Stopped has used it, or the
            // settle budget has run out.
            let finals = futures_util::future::join_all(final_reports);
            if tokio::time::timeout(FINAL_REPORT_SETTLE_BUDGET, finals)
                .await
                .is_err()
            {
                tracing::debug!("final reports still pending; logging out anyway");
            }
            match client.logout().await {
                Ok(()) => tracing::debug!("server session ended"),
                Err(error) => tracing::debug!(%error, "server logout failed"),
            }
        });
    }

    /// Identity for a saved token: the `DeviceId` it was issued to, which for sessions saved
    /// before per-server ids is the install id itself.
    fn saved_identity(saved: &session::SessionFile) -> jellyfin_api::ClientIdentity {
        Self::identity_with(saved.device_id.clone())
    }

    pub(crate) fn identity_with(device_id: String) -> jellyfin_api::ClientIdentity {
        jellyfin_api::ClientIdentity {
            client: "Jellybeam TV".to_string(),
            device: "Android TV".to_string(),
            device_id,
            version: env!("CARGO_PKG_VERSION").to_string(),
        }
    }

    /// docs/18 §2.1: admits `request` as the playback owner. A request from another account epoch
    /// is refused as [`CoreError::AccountChanged`]; one not newer than the last admitted as stale.
    /// `retires` names the session a fallback stopped, so a late stop/abandon of it can revoke.
    fn admit_playback_request(
        state: &mut State,
        request: PlaybackRequest,
        retires: Option<String>,
    ) -> Result<PlaybackClaim, CoreError> {
        let current_epoch = state.account.epoch;
        let owner = &mut state.playback_owner;
        let parked_epoch = owner.parked.as_ref().map(|parked| parked.epoch);
        if request.account_epoch != current_epoch && Some(request.account_epoch) != parked_epoch {
            tracing::warn!(
                request_epoch = request.account_epoch,
                current_epoch,
                "playback request from a previous account epoch refused"
            );
            return Err(CoreError::AccountChanged);
        }
        if request.seq <= owner.highest_seq {
            return Err(CoreError::StalePlaybackSession);
        }
        owner.highest_seq = request.seq;
        let claim = PlaybackClaim {
            seq: request.seq,
            epoch: request.account_epoch,
            retires,
        };
        owner.claim = Some(claim.clone());
        Ok(claim)
    }

    /// docs/18 §2.1: installs a reporting session for `claim` if it is still the owner, starting
    /// it only then (a refused negotiation never sends Start). The sidecars go in with it, and any
    /// session still installed is abandoned outside the lock.
    fn install_claimed_session(
        &self,
        claim: &PlaybackClaim,
        client: &jellyfin_api::JellyfinClient,
        ctx: jellyfin_core::ReportContext,
        sidecars: Option<crate::types::SessionSidecars>,
    ) -> Result<(), CoreError> {
        let (previous, retired, account) = {
            let mut state = self.lock_state();
            if state.playback_owner.claim.as_ref() != Some(claim) {
                return Err(CoreError::StalePlaybackSession);
            }
            state.playback_owner.claim = None;
            state.playback_owner.installed_replaces = claim.retires.clone();
            let retired = state.playback_owner.retiring.take();
            // `ReportingSession::start` spawns its actor and needs a runtime context, which a
            // preload cache hit never entered.
            let session = {
                let _runtime_guard = self.runtime.enter();
                jellyfin_core::ReportingSession::start(client.clone(), ctx)
            };
            state.local_progress_checkpoint.reset();
            // The sidecars live and die with the session (docs/18 §3.2): one install, one clear.
            state.sidecars = sidecars;
            // What it replaces belongs to the playback before it; a claim on the current account
            // starts a playback of its own, so nothing is parked any more.
            let account = Self::playback_account_locked(&state);
            if claim.epoch == state.account.epoch {
                state.playback_owner.parked = None;
            }
            (state.reporting.replace(session), retired, account)
        };
        if let Some(previous) = previous {
            previous.abandon();
        }
        // The replacement is reporting now, so a session a fallback took over ends -- this claim's
        // at its position, a superseded claim's as soon as something newer plays.
        if let Some(retired) = retired {
            // Its own replacement takes over at the fallback's position; anything newer replaces it
            // wherever the viewer left it.
            let ticks = if retired.seq == claim.seq {
                retired.ticks
            } else {
                retired.left_at()
            };
            Self::stop_session_and_sync_mirror(
                &self.runtime,
                &self.playback_write,
                retired.session,
                ticks,
                account,
            );
        }
        Ok(())
    }

    /// docs/18 §2.1: takes the reporting session if `play_session_id` names it or the session it
    /// replaced; otherwise revokes a pending fallback that retired that session. Never touches a
    /// newer request's claim.
    fn take_session_or_revoke_locked(state: &mut State, play_session_id: &str) -> Option<Taken> {
        let is_current = state.reporting.as_ref().is_some_and(|active| {
            active.context().play_session_id == play_session_id
                || state.playback_owner.installed_replaces.as_deref() == Some(play_session_id)
        });
        if !is_current {
            let owner = &mut state.playback_owner;
            if owner
                .claim
                .as_ref()
                .is_some_and(|claim| claim.retires.as_deref() == Some(play_session_id))
            {
                owner.claim = None;
                return owner.retiring.take().map(|retiring| {
                    let ticks = retiring.left_at();
                    Taken::Retired(retiring.session, ticks)
                });
            } else {
                tracing::debug!(
                    play_session_id = %play_session_id,
                    "play_session_id names neither the active session nor a pending fallback -- \
                     a lost race is expected here, not a caller bug; ignoring"
                );
            }
            return None;
        }
        state.local_progress_checkpoint.reset();
        state.sidecars = None;
        state.reporting.take().map(Taken::Active)
    }

    /// docs/18 §2.1: the one stop path of `stop_playback`, `abandon_playback`, a transcode
    /// fallback's retired session, a replaced claim and an account reset. Fires the session's
    /// final Stopped (not awaited) and commits the position to `account`'s mirror before
    /// returning; the [`FinalSettled`] resolves once that report has landed or been given up.
    fn stop_session_and_sync_mirror(
        runtime: &tokio::runtime::Runtime,
        playback_write: &Mutex<()>,
        session: jellyfin_core::ReportingSession,
        position_ticks: i64,
        account: SessionAccount,
    ) -> FinalSettled {
        let SessionAccount { mirror, .. } = account;
        let item_id = session.context().item_id.clone();
        // The server recounts an episode's season/series only once the
        // Stopped report lands, and its `UserDataChanged` event omits the
        // series, so the scope is re-fetched after that report, never before.
        let scope_ids = mirror
            .as_ref()
            .map(|mirror| episode_scope_ids(mirror, &item_id))
            .unwrap_or_default();
        // docs/07 §1: stopping in the credits is a finished watch; reporting the runtime as the
        // Stopped position makes the server mark it played, so no separate mark is sent.
        let (report_ticks, finished) = final_report(
            mirror
                .as_ref()
                .and_then(|mirror| mirror.card_by_id(&item_id))
                .as_ref(),
            position_ticks,
        );
        let stop = session.stop(report_ticks);
        let scope_mirror = mirror.clone();
        let (settled_tx, settled_rx) = tokio::sync::oneshot::channel();
        runtime.spawn(async move {
            // Waits for the Stopped to land, retries included: a recount before it reads the old
            // counts. A delivery cancelled by a newer action is recounted by that action instead.
            let cancelled = stop.await.settled().await;
            let _ = settled_tx.send(());
            if cancelled {
                return;
            }
            // An episode's scope is non-empty; its stop moves Next Up (docs/07 §1).
            if let Some(mirror) = scope_mirror.filter(|_| !scope_ids.is_empty()) {
                mirror.request_next_up_refresh();
                mirror.refresh_items(scope_ids).await;
            }
        });

        if let Some(mirror) = mirror {
            let _write_guard = playback_write.lock().unwrap_or_else(|e| e.into_inner());
            let committed = runtime.block_on(mirror.apply_local_user_data_and_wait(
                &item_id,
                position_ticks,
                finished.then_some(true),
            ));
            if !committed {
                tracing::error!(item_id, "final playback state did not commit to the mirror");
            }
        }
        settled_rx
    }

    /// Shared by [`Self::prepare_playback`] and [`Self::preload_playback`]:
    /// the normal, zero-network mirror lookup; only a cache miss pays one
    /// targeted `/Items?ids=...` request. External PLAY intents can
    /// legitimately name a server item before the background mirror has
    /// reconciled it. Does not persist the fetched DTO: the next reconcile
    /// remains sole authority for mirror membership.
    ///
    /// docs/18-playback-quality.md §2: this synchronous half runs on
    /// whichever thread the caller is already blocking on (never a Tokio
    /// worker directly). Only a cache miss pays a `block_on`, and only for
    /// the network fallback.
    fn fetch_item_dto(
        &self,
        client: &jellyfin_api::JellyfinClient,
        mirror: &media_cache::Mirror,
        item_id: &str,
    ) -> Result<jellyfin_api::models::BaseItemDto, CoreError> {
        if let Some(dto) = mirror.item(item_id) {
            return Ok(dto);
        }
        self.runtime
            .block_on(Self::fetch_from_network(client, item_id))
    }

    /// The async half of that same rule, used only by
    /// [`Self::build_preload_cache`]. Unlike [`Self::fetch_item_dto`], runs
    /// *on* a Tokio worker, so the mirror's blocking SQLite read moves to
    /// `spawn_blocking` -- with only two workers total, blocking one
    /// directly could starve the other preload/negotiation task.
    /// `run_preload_worker`'s own generation re-check still discards a
    /// result that arrives after its caller stopped caring.
    async fn fetch_item_dto_async(
        client: &jellyfin_api::JellyfinClient,
        mirror: &media_cache::Mirror,
        item_id: &str,
    ) -> Result<jellyfin_api::models::BaseItemDto, CoreError> {
        let owned_mirror = mirror.clone();
        let owned_item_id = item_id.to_string();
        let mirror_hit = tokio::task::spawn_blocking(move || owned_mirror.item(&owned_item_id))
            .await
            .unwrap_or(None);
        if let Some(dto) = mirror_hit {
            return Ok(dto);
        }
        Self::fetch_from_network(client, item_id).await
    }

    /// Network fallback shared by [`Self::fetch_item_dto`] and
    /// [`Self::fetch_item_dto_async`]: the one targeted `/Items?ids=...`
    /// request either pays on a mirror miss.
    async fn fetch_from_network(
        client: &jellyfin_api::JellyfinClient,
        item_id: &str,
    ) -> Result<jellyfin_api::models::BaseItemDto, CoreError> {
        let result = client
            .get_items(&jellyfin_api::ItemQuery {
                ids: vec![item_id.to_string()],
                limit: 1,
                enable_user_data: Some(true),
                ..jellyfin_api::ItemQuery::default()
            })
            .await?;
        result
            .items
            .into_iter()
            .next()
            .ok_or_else(|| CoreError::Api {
                detail: format!("item {item_id} not found on the active server"),
            })
    }

    /// [`Self::prepare_playback`]'s quality negotiation, split out so that
    /// caller only orchestrates: a preload cache hit is `DirectPlay` as-is;
    /// otherwise Cap mode always forces a transcode negotiation, and
    /// Auto/`DirectPlay` negotiate once and hand the decision to
    /// [`resolve_plan`] (Auto twice when it transcodes, docs/18 §3.2).
    #[allow(clippy::too_many_arguments)]
    fn negotiate_playback_source(
        &self,
        client: &jellyfin_api::JellyfinClient,
        item_id: &str,
        caps: &jellyfin_core::AndroidTvCaps,
        tolerate_mislabeled_levels: bool,
        quality: PlaybackQuality,
        profile: &jellyfin_api::models::DeviceProfile,
        start_ticks: Option<i64>,
        cached: Option<PreloadCache>,
    ) -> Result<NegotiatedSource, CoreError> {
        if let Some(cache) = cached {
            return Ok((
                cache.source,
                cache.url,
                cache.play_session_id,
                PlayMethodFfi::DirectPlay,
                None,
                cache.server_verdict,
            ));
        }
        // docs/18-playback-quality.md §2: Cap mode is not a row of
        // `resolve_plan`'s matrix -- it always forces the negotiation
        // itself, nothing for `resolve_plan` to resolve.
        match quality {
            PlaybackQuality::Cap { max_bps } => {
                let (play_session_id, decision) = self.negotiate_playback_info(
                    client,
                    item_id,
                    profile,
                    start_ticks,
                    jellyfin_api::PlaybackInfoOptions {
                        force_transcode: true,
                        ..Default::default()
                    },
                )?;
                let (source, url) = transcode_url_from_decision(client, item_id, decision)
                    .ok_or(jellyfin_core::CoreError::NoPlayableSource)?;
                Ok((
                    source,
                    url,
                    play_session_id,
                    PlayMethodFfi::Transcode,
                    Some(cap_transcode_reason(max_bps)),
                    None,
                ))
            }
            PlaybackQuality::DirectPlay | PlaybackQuality::Auto => {
                let supported_codecs = jellyfin_core::android_direct_play_video_codecs(caps);
                let negotiate = |profile| -> Result<_, CoreError> {
                    let (play_session_id, decision) = self.negotiate_playback_info(
                        client,
                        item_id,
                        profile,
                        start_ticks,
                        jellyfin_api::PlaybackInfoOptions::default(),
                    )?;
                    let video_codec = video_stream_codec(decision_source(&decision));
                    let plan = resolve_plan(
                        &quality,
                        decision,
                        video_codec.as_deref(),
                        &supported_codecs,
                        tolerate_mislabeled_levels,
                    );
                    Ok((play_session_id, plan))
                };
                let (mut play_session_id, mut plan) = negotiate(profile)?;
                // docs/18 §3.2: offered ASS sidecars, the transcode Auto takes anyway would hand
                // out embedded ASS instead of burning it in, so it is asked for without them.
                if matches!(plan, Ok(ResolvedPlan::Transcode { .. })) {
                    (play_session_id, plan) = negotiate(&build_android_profile_for_quality(
                        caps,
                        tolerate_mislabeled_levels,
                        &quality,
                        false,
                    ))?;
                }
                match plan {
                    Ok(ResolvedPlan::DirectPlay {
                        source,
                        url,
                        server_verdict,
                    }) => Ok((
                        source,
                        url,
                        play_session_id,
                        PlayMethodFfi::DirectPlay,
                        None,
                        server_verdict,
                    )),
                    Ok(ResolvedPlan::Transcode {
                        source,
                        url,
                        reason,
                    }) => Ok((
                        source,
                        url,
                        play_session_id,
                        PlayMethodFfi::Transcode,
                        Some(reason),
                        None,
                    )),
                    Err(reasons) => Err(CoreError::WouldTranscode { reasons }),
                }
            }
        }
    }

    /// Shared by [`Self::prepare_playback`] (preload-cache miss),
    /// [`Self::preload_playback`] (always), and
    /// [`Self::prepare_transcode_fallback`] (`force_transcode: true`): the
    /// one load-bearing network round trip --
    /// `get_playback_info` + `jellyfin_core::decide_playback`. Extracted so
    /// callers can't drift on what "negotiate playback" means; `options` is
    /// the one axis that legitimately differs per caller.
    fn negotiate_playback_info(
        &self,
        client: &jellyfin_api::JellyfinClient,
        item_id: &str,
        profile: &jellyfin_api::models::DeviceProfile,
        start_ticks: Option<i64>,
        options: jellyfin_api::PlaybackInfoOptions,
    ) -> Result<(String, jellyfin_core::PlaybackDecision), CoreError> {
        self.runtime.block_on(Self::negotiate_playback_info_async(
            client,
            item_id,
            profile,
            start_ticks,
            options,
        ))
    }

    async fn negotiate_playback_info_async(
        client: &jellyfin_api::JellyfinClient,
        item_id: &str,
        profile: &jellyfin_api::models::DeviceProfile,
        start_ticks: Option<i64>,
        options: jellyfin_api::PlaybackInfoOptions,
    ) -> Result<(String, jellyfin_core::PlaybackDecision), CoreError> {
        let info = client
            .get_playback_info(item_id, profile, start_ticks, options)
            .await?;
        let play_session_id = info.play_session_id.clone().unwrap_or_default();
        let decision = jellyfin_core::decide_playback(client, item_id, &info)?;
        Ok((play_session_id, decision))
    }

    fn saved_session_at(&self, index: u32) -> Result<session::SessionFile, CoreError> {
        session::load_list(&self.data_dir)
            .sessions
            .get(index as usize)
            .cloned()
            .ok_or(CoreError::InvalidSessionIndex { index })
    }

    fn install_reauthenticated_session(
        &self,
        original_index: u32,
        target: session::SessionFile,
        client: jellyfin_api::JellyfinClient,
        auth: jellyfin_api::models::AuthenticationResult,
    ) -> Result<AccountInfo, CoreError> {
        if client.user_id() != Some(target.user_id.as_str()) {
            return Err(CoreError::Api {
                detail: "The authorized Jellyfin user does not match the saved account. Approve Quick Connect as the selected user, or enter that user's password.".to_string(),
            });
        }

        // docs/18 §2.1: "browsed" means the installed account; the list's active row only stands in
        // when nothing is installed.
        let target_identity = AccountIdentity::of(&target);
        let target_installed = self
            .installed_identity()
            .map(|installed| installed.account.names(&target));
        // Authentication is a network round trip. Re-read after it finishes
        // and refuse to resurrect an account removed while in flight;
        // locate by stable identity, not the original index.
        let replaced_parked = session::mutate(&self.data_dir, &self.session_lock, |list| {
            let Some(ix) = list
                .sessions
                .iter()
                .position(|saved| target_identity.names(saved))
            else {
                return Err(CoreError::InvalidSessionIndex {
                    index: original_index,
                });
            };
            Ok(if target_installed.unwrap_or(ix == list.active) {
                None
            } else {
                // docs/18 §2.1: only the token (and name) of a parked account changes; the account
                // being browsed stays active, no account change happens, and nothing is installed.
                let token = access_token_of(&auth)?;
                let row = &mut list.sessions[ix];
                row.token = token;
                if let Some(name) = auth.user.as_ref().and_then(|user| user.name.clone()) {
                    row.user_name = name;
                }
                row.device_id = device_id::for_server(&self.device_id, &target.server_url);
                Some(row.clone())
            })
        })?;
        if let Some(row) = replaced_parked {
            let mirror_dir = row.mirror_dir.clone();
            // A live playback parked on this account keeps going, now with the fresh token.
            {
                let mut state = self.lock_state();
                if let Some(parked) = state
                    .playback_owner
                    .parked
                    .as_mut()
                    .filter(|parked| parked.is_account(Some(&mirror_dir)))
                {
                    parked.account = parked.account.refreshed_by(SessionAccount {
                        mirror: None,
                        client: Some(client),
                        mirror_dir: None,
                    });
                }
            }
            return Ok(AccountInfo::of(&row));
        }

        self.install_authenticated_session(target.server_url, client, auth)
    }

    /// Shared commit path for password and Quick Connect authentication. Like
    /// [`Self::switch_session`] it opens no mirror: the account change's
    /// [`Self::open_mirror`] is the one open per install.
    fn install_authenticated_session(
        &self,
        server_url: String,
        client: jellyfin_api::JellyfinClient,
        auth: jellyfin_api::models::AuthenticationResult,
    ) -> Result<AccountInfo, CoreError> {
        let user_id = client
            .user_id()
            .map(str::to_string)
            .ok_or_else(|| CoreError::Api {
                detail: "sign-in response did not include a user id".to_string(),
            })?;
        let token = access_token_of(&auth)?;
        let user_name = auth
            .user
            .as_ref()
            .and_then(|user| user.name.clone())
            .unwrap_or_default();

        let to_persist = session::SessionFile {
            server_url: server_url.clone(),
            user_id: user_id.clone(),
            user_name: user_name.clone(),
            token,
            device_id: device_id::for_server(&self.device_id, &server_url),
            mirror_dir: session::mirror_dir_name(&server_url, &user_id),
            // Not known yet -- the install's `AccountReset::refresh` fetches and persists it.
            // `SessionList::add_or_update` carries over whatever version was
            // already on record so a re-authentication never wipes a known
            // value back to `None`. Same reasoning for `server_name`.
            server_version: None,
            server_name: None,
        };
        let persisted = session::mutate(&self.data_dir, &self.session_lock, |sessions| {
            sessions.add_or_update(to_persist);
            Ok::<_, CoreError>(
                sessions
                    .active_session()
                    .cloned()
                    .expect("add_or_update installs the new active session"),
            )
        })?;

        self.install_account(Installed::of(&persisted, client));
        Ok(AccountInfo::of(&persisted))
    }

    /// docs/18 §2.1: installs `next` in the lock that advances the epoch, so no fetch pairs the
    /// outgoing client with the new epoch; the outgoing mirror, listener and bus stop here.
    fn install_account(&self, next: Installed) {
        let reset = Self::reset_account_state_locked(
            &mut self.lock_state(),
            Some(next),
            AccountChange::Replace,
        );
        self.dispose_account_reset(reset);
    }

    /// Deletes only mirror directory names generated by this crate (plus
    /// the one legacy name). A tampered sessions.json can remove its
    /// credential row but never turn this into an arbitrary recursive delete.
    fn remove_mirror_dir_if_safe(&self, mirror_dir: &str) {
        let generated = mirror_dir.strip_prefix("mirror-").is_some_and(|suffix| {
            suffix.len() == 16 && suffix.chars().all(|c| c.is_ascii_hexdigit())
        });
        if mirror_dir != "mirror" && !generated {
            tracing::warn!("refusing to delete an unrecognized mirror directory name");
            return;
        }
        let path = self.data_dir.join(mirror_dir);
        if let Err(error) = std::fs::remove_dir_all(&path) {
            if error.kind() != std::io::ErrorKind::NotFound {
                // Credentials are already gone at this point. Cache cleanup
                // is best-effort and must not falsely report the server as
                // still saved.
                tracing::warn!(error = %error, "failed to delete removed account's mirror directory");
            }
        }
    }

    /// The one place that turns "no mirror open" into [`CoreError`]. Plain
    /// snapshot getters have no `Result` in their signature and fall back to
    /// an empty/default value instead of propagating it.
    fn require_mirror(&self) -> Result<media_cache::Mirror, CoreError> {
        self.lock_state()
            .account
            .bundle()
            .map(|g| g.mirror.clone())
            .ok_or(CoreError::MirrorNotOpen)
    }

    /// Installs `saved` unless an account change landed since `tag` was read; answers with the
    /// account installed afterwards either way.
    fn restore_finish(&self, tag: u64, saved: session::SessionFile) -> Option<AccountInfo> {
        let next = Self::installed_from_saved(&saved);
        let (reset, now) = {
            let mut state = self.lock_state();
            let reset = Self::restore_account_locked(&mut state, tag, next);
            (reset, state.account.installed().map(|i| i.account.clone()))
        };
        if let Some(reset) = reset {
            self.dispose_account_reset(reset);
        }
        now
    }

    /// `saved` as installed with a client built from its token, no network validation.
    fn installed_from_saved(saved: &session::SessionFile) -> Installed {
        let identity = Self::saved_identity(saved);
        let client =
            jellyfin_api::JellyfinClient::from_token(&saved.server_url, identity, &saved.token)
                .with_user_id(&saved.user_id);
        Installed::of(saved, client)
    }

    /// [`Self::open_mirror`]'s first step: the epoch, client and directory it opens for, read
    /// under one lock, with an already open bundle (re-entry) taken and torn down here.
    fn open_begin(&self) -> Result<(u64, jellyfin_api::JellyfinClient, PathBuf), CoreError> {
        let (opened, taken) = {
            let mut state = self.lock_state();
            let epoch = state.account.epoch;
            let installed = state
                .account
                .installed
                .as_mut()
                .ok_or(CoreError::NotSignedIn)?;
            let dir = self.data_dir.join(&installed.mirror_dir);
            (
                (epoch, installed.client.clone(), dir),
                installed.bundle.take(),
            )
        };
        self.stop_event_bus(taken);
        Ok(opened)
    }

    /// Opens the mirror in `dir` and spawns its real bus, unlocked; the bus's receiver comes back
    /// beside the bundle for the forwarder [`Self::open_finish`] spawns on commit.
    fn build_bundle(
        &self,
        client: &jellyfin_api::JellyfinClient,
        dir: PathBuf,
    ) -> Result<
        (
            BusBundle,
            Option<tokio::sync::broadcast::Receiver<jellyfin_core::BusEvent>>,
        ),
        CoreError,
    > {
        #[cfg(test)]
        self.mirror_opens
            .fetch_add(1, std::sync::atomic::Ordering::SeqCst);
        // Subscribed before `EventBus::spawn`, so the first `NeedsReconcile` is never missed.
        let (bundle_tx, bundle_rx) = tokio::sync::broadcast::channel(16);

        #[cfg(feature = "live-test-knobs")]
        let bus_disabled = std::env::var("JELLYBEAM_DISABLE_EVENT_BUS").as_deref() == Ok("1");
        #[cfg(not(feature = "live-test-knobs"))]
        let bus_disabled = false;

        let (real_bus, connection) = if bus_disabled {
            // Rests at Disconnected: a disabled bus never reads connected.
            let (_tx, connection) =
                tokio::sync::watch::channel(jellyfin_core::BusConnection::Disconnected);
            (None, connection)
        } else {
            // `EventBus::spawn` calls `tokio::spawn` for its supervisor, needing a runtime context.
            let _runtime_guard = self.runtime.enter();
            let (real_bus_rx, connection, bus_handle) =
                jellyfin_core::EventBus::spawn(client.clone());
            (Some((bus_handle, real_bus_rx)), connection)
        };

        // Read first: a guard in the `match` scrutinee would hold the state lock across the open.
        let playback_active = self.lock_state().playback_active.clone();
        let opened = self.runtime.block_on(media_cache::Mirror::open(
            dir,
            client.clone(),
            bundle_rx,
            playback_active,
            // docs/07 Watch state: read from the session registry, never the state lock.
            Arc::new(jellyfin_core::pending_local_item_ids),
        ));
        let (handle, real_bus_rx) = real_bus.unzip();
        let mirror = match opened {
            Ok(mirror) => mirror,
            Err(e) => {
                // The bus just spawned stops with the failed open.
                if let Some(handle) = handle {
                    self.runtime.block_on(handle.shutdown());
                }
                return Err(e.into());
            }
        };
        // Seeds the first sync's Next Up fetch; the push at commit is authoritative, since a save
        // landing in between cannot reach this mirror.
        mirror.set_next_up_options(next_up_options_from(&self.lock_state().settings));
        let bundle = BusBundle {
            mirror,
            listener_task: None,
            tx: bundle_tx,
            connection,
            handle,
            forwarder: None,
        };
        Ok((bundle, real_bus_rx))
    }

    /// docs/18 §2.1: commits a built bundle under the lock that checks `epoch`, spawning its
    /// listener task and forwarder there (so every change the forwarder reads is of an installed
    /// bus); a stale or redundant bundle is torn down outside the lock.
    fn open_finish(
        &self,
        epoch: u64,
        client: &jellyfin_api::JellyfinClient,
        (bundle, real_bus_rx): (
            BusBundle,
            Option<tokio::sync::broadcast::Receiver<jellyfin_core::BusEvent>>,
        ),
    ) -> Result<(), CoreError> {
        let refused = {
            let mut state = self.lock_state();
            let listener = state.listener.clone();
            match Self::commit_bundle_locked(&mut state, epoch, bundle) {
                Ok(installed) => {
                    installed.listener_task =
                        listener.map(|l| self.spawn_listener_task(installed.mirror.clone(), l));
                    installed.forwarder = real_bus_rx.map(|rx| {
                        let (tx, connection) = (installed.tx.clone(), installed.connection.clone());
                        self.spawn_bus_forwarder(rx, tx, connection, client.clone(), epoch)
                    });
                    Self::push_next_up_options_locked(&state);
                    None
                }
                Err(refused) => Some(refused),
            }
        };
        let Some((bundle, result)) = refused else {
            return Ok(());
        };
        self.stop_event_bus(Some(bundle));
        result
    }

    /// docs/18 §2.1: installs `bundle` on the account `epoch` names, if that account is still
    /// installed and Closed; otherwise hands it back for teardown with the open's result:
    /// [`CoreError::AccountChanged`] when stale, `Ok` when a concurrent open of it committed first.
    fn commit_bundle_locked(
        state: &mut State,
        epoch: u64,
        bundle: BusBundle,
    ) -> Result<&mut BusBundle, (BusBundle, Result<(), CoreError>)> {
        let Some(installed) = state.account.installed_mut(epoch) else {
            return Err((bundle, Err(CoreError::AccountChanged)));
        };
        if installed.bundle.is_some() {
            return Err((bundle, Ok(())));
        }
        Ok(installed.bundle.insert(bundle))
    }

    fn spawn_listener_task(
        &self,
        mirror: media_cache::Mirror,
        listener: Arc<dyn ChangeListener>,
    ) -> tokio::task::JoinHandle<()> {
        self.runtime.spawn(async move {
            let mut rx = mirror.changes();
            while let Some(change) = media_cache::recv_changes(&mut rx).await {
                if let Err(error) = listener.on_change(ChangeEvent::from(change)) {
                    tracing::warn!(error = %error, "change listener threw; next change still delivered");
                }
            }
        })
    }

    /// docs/13 Server compatibility, docs/18 §2.1: relays every event of one real bus onto its
    /// bundle (a lag becomes `NeedsReconcile`) until the bus closes; while its bundle is installed,
    /// each `Connected` refreshes the server version with that bus's `client`, tagged with the
    /// `epoch` the bundle was committed under, and the first
    /// `Unauthorized` asks the listener to reauthorize. Holds the state `Weak`; aborting it
    /// (`stop_event_bus`) cancels an in-flight refresh, and joining it waits out a running ask.
    fn spawn_bus_forwarder(
        &self,
        mut real_bus_rx: tokio::sync::broadcast::Receiver<jellyfin_core::BusEvent>,
        bundle_tx: tokio::sync::broadcast::Sender<jellyfin_core::BusEvent>,
        mut connection: tokio::sync::watch::Receiver<jellyfin_core::BusConnection>,
        client: jellyfin_api::JellyfinClient,
        epoch: u64,
    ) -> tokio::task::JoinHandle<()> {
        let state = Arc::downgrade(&self.state);
        let data_dir = self.data_dir.clone();
        let session_lock = self.session_lock.clone();
        self.runtime.spawn(async move {
            // Off once the supervisor ends or rejects; the relay runs on until the real bus closes.
            let mut watching = true;
            loop {
                tokio::select! {
                    // A connection change is handled before any bus event sent after it.
                    biased;
                    changed = connection.changed(), if watching => {
                        if changed.is_err() {
                            watching = false;
                            continue;
                        }
                        let now = connection.borrow_and_update().clone();
                        match now {
                            jellyfin_core::BusConnection::Connected => {
                                let Some(state) = state.upgrade() else { break };
                                let installed =
                                    Self::installed_bus_locked(&state.lock(), &connection);
                                if installed {
                                    Self::refresh_server_version_once(
                                        epoch,
                                        client.clone(),
                                        data_dir.clone(),
                                        state,
                                        session_lock.clone(),
                                    )
                                    .await;
                                }
                            }
                            jellyfin_core::BusConnection::Disconnected => {}
                            jellyfin_core::BusConnection::Unauthorized { .. } => {
                                // Terminal: the supervisor stops, so this bus asks at most once.
                                watching = false;
                                let Some(state) = state.upgrade() else { break };
                                let ask =
                                    Self::bus_reauthorization_locked(&state.lock(), &connection, &now);
                                if let Some((listener, account)) = ask {
                                    if let Err(error) = listener.on_unauthorized(account) {
                                        tracing::warn!(
                                            error = %error,
                                            "change listener threw on a socket rejection"
                                        );
                                    }
                                }
                            }
                        }
                    }
                    event = jellyfin_core::recv_bus(&mut real_bus_rx) => {
                        let Some(event) = event else { break };
                        let _ = bundle_tx.send(event);
                    }
                }
            }
        })
    }

    /// Whether `bus` is the installed bundle's connection watch, the one bus whose changes count.
    fn installed_bus_locked(
        state: &State,
        bus: &tokio::sync::watch::Receiver<jellyfin_core::BusConnection>,
    ) -> bool {
        state
            .account
            .bundle()
            .is_some_and(|installed| installed.connection.same_channel(bus))
    }

    /// docs/18 §2.1: whom a socket rejection asks to reauthorize: the bound listener, naming the
    /// rejected token's owner, only while `bus` is installed; an unnamed owner asks nobody.
    fn bus_reauthorization_locked(
        state: &State,
        bus: &tokio::sync::watch::Receiver<jellyfin_core::BusConnection>,
        now: &jellyfin_core::BusConnection,
    ) -> Option<(Arc<dyn ChangeListener>, AccountIdentity)> {
        let jellyfin_core::BusConnection::Unauthorized { owner: Some(owner) } = now else {
            return None;
        };
        if !Self::installed_bus_locked(state, bus) {
            return None;
        }
        Some((state.listener.clone()?, owner.clone().into()))
    }

    /// docs/18 §2.1: the one [`BusBundle`] teardown, never under the state lock: joins the
    /// listener task and the forwarder, then awaits the real bus's shutdown (ending its reconnect
    /// loop) and drops the mirror, whose writer ends once its last clone drops (R2).
    fn stop_event_bus(&self, bundle: Option<BusBundle>) {
        let Some(bundle) = bundle else { return };
        self.stop_task(bundle.listener_task);
        self.stop_task(bundle.forwarder);
        if let Some(handle) = bundle.handle {
            self.runtime.block_on(handle.shutdown());
        }
    }

    /// docs/13 Server compatibility: one task from [`Self::new`] refreshing the installed client's
    /// server version every `SERVER_VERSION_REFRESH_INTERVAL` (the first, immediate tick skipped
    /// since every install path refreshes); holds the state `Weak` and ends once the core drops.
    fn spawn_server_version_poll(
        state: std::sync::Weak<SharedState>,
        data_dir: PathBuf,
        session_lock: Arc<Mutex<()>>,
        runtime: &tokio::runtime::Runtime,
    ) {
        runtime.spawn(async move {
            let mut interval = tokio::time::interval(SERVER_VERSION_REFRESH_INTERVAL);
            interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
            interval.tick().await;
            loop {
                interval.tick().await;
                let Some(state) = state.upgrade() else { break };
                let current = state.lock().account.current_client();
                if let Some((epoch, client)) = current {
                    Self::refresh_server_version_once(
                        epoch,
                        client,
                        data_dir.clone(),
                        state,
                        session_lock.clone(),
                    )
                    .await;
                }
            }
        });
    }

    /// Fetches `/System/Info/Public` with `client`, read under `epoch`, and hands the result to
    /// [`Self::apply_public_server_info`] -- shared by every install ([`AccountReset::refresh`]),
    /// [`Self::spawn_server_version_poll`] and [`Self::spawn_bus_forwarder`]. Always inside a
    /// spawned task since it does network I/O. A fetch failure is debug-logged and leaves the
    /// stored value untouched -- a transient failure must not blank out what `server_at_least`
    /// fails closed on.
    async fn refresh_server_version_once(
        epoch: u64,
        client: jellyfin_api::JellyfinClient,
        data_dir: PathBuf,
        state: Arc<SharedState>,
        session_lock: Arc<Mutex<()>>,
    ) {
        // Every install site sets a user id; a client with none has no
        // session record to identify, let alone persist onto.
        if client.user_id().is_none() {
            return;
        }

        let info = match client.refresh_public_system_info().await {
            Ok(info) => info,
            Err(e) => {
                tracing::debug!(
                    error = %e,
                    "server version refresh failed; keeping the stored value"
                );
                return;
            }
        };
        Self::apply_public_server_info(
            epoch,
            &client,
            info.version,
            info.server_name,
            data_dir,
            state,
            session_lock,
        )
        .await;
    }

    /// docs/13 About > server info: applies a `version`/`server_name` pair fetched from either
    /// `/System/Info/Public` ([`Self::refresh_server_version_once`]) or the authenticated
    /// `/System/Info` ([`Self::fetch_server_details`]) -- the shared compare-persist-notify
    /// steps, so an About-page refresh also refreshes the version gate and persists the name.
    ///
    /// The fetched values are persisted onto whichever `(server_url, user_id)` record `client`
    /// actually authenticated as (identified from `client` itself, never the installed client by
    /// completion time) even when stale, while [`Installed`]/the change-listener notification
    /// only update while `epoch`, read with `client`, is still current. The on-disk persist runs
    /// on `spawn_blocking`, re-reading the session list inside `session_lock`, so a concurrent
    /// switch/remove is never clobbered. `version` that fails to parse is treated as absent
    /// throughout (never persisted or applied) so an unparseable future format only fails to gate,
    /// never to load.
    /// A version change fires [`ChangeEvent::Refresh`] (it gates playback decisions); a
    /// name-only change updates the installed name with no event, since nothing but the About
    /// page reads it.
    async fn apply_public_server_info(
        epoch: u64,
        client: &jellyfin_api::JellyfinClient,
        version: Option<String>,
        server_name: Option<String>,
        data_dir: PathBuf,
        state: Arc<SharedState>,
        session_lock: Arc<Mutex<()>>,
    ) {
        let Some(user_id) = client.user_id().map(str::to_string) else {
            return;
        };
        let server_url = client.base_url().to_string();

        let parsed_version = match version.as_deref() {
            Some(raw) => match raw.parse::<jellyfin_api::ServerVersion>() {
                Ok(parsed) => Some(parsed),
                Err(_) => {
                    tracing::debug!(
                        version = %raw,
                        "server version did not parse; keeping the stored value"
                    );
                    None
                }
            },
            None => None,
        };
        // Never persist/apply a version string that didn't parse -- an unparseable value must
        // never land in `SessionFile::server_version` or the installed facts.
        let version_to_persist = if parsed_version.is_some() {
            version
        } else {
            None
        };

        if version_to_persist.is_none() && server_name.is_none() {
            return;
        }

        let persist_server_url = server_url.clone();
        let persist_user_id = user_id.clone();
        let persist_version = version_to_persist.clone();
        let persist_name = server_name.clone();
        let join_result = tokio::task::spawn_blocking(move || {
            Self::persist_server_identity_locked(
                &session_lock,
                &data_dir,
                &persist_server_url,
                &persist_user_id,
                persist_version.as_deref(),
                persist_name.as_deref(),
            );
        })
        .await;
        if let Err(e) = join_result {
            tracing::warn!(error = %e, "server identity persist task panicked or was cancelled");
        }

        let (version_changed, listener) = {
            let mut state = state.lock();
            let changed =
                Self::apply_server_facts_locked(&mut state, epoch, parsed_version, server_name);
            (changed, state.listener.clone().filter(|_| changed))
        };
        if version_changed {
            // A version number is not identifying (CLAUDE.md's redaction
            // rule targets addresses/tokens/titles/paths, not this).
            if let Some(parsed) = parsed_version {
                tracing::info!(version = %parsed, "server version changed");
            }
            if let Some(listener) = listener {
                if let Err(error) = listener.on_change(ChangeEvent::Refresh) {
                    tracing::warn!(error = %error, "change listener threw on a version change");
                }
            }
        }
    }

    /// docs/13 Server compatibility: records fetched facts on the installed account while `epoch`
    /// is still current; returns whether the version changed, the only fact that fires
    /// [`ChangeEvent::Refresh`] since it gates playback decisions.
    fn apply_server_facts_locked(
        state: &mut State,
        epoch: u64,
        version: Option<jellyfin_api::ServerVersion>,
        name: Option<String>,
    ) -> bool {
        let Some(installed) = state.account.installed_mut(epoch) else {
            return false;
        };
        if name.is_some() {
            installed.server_name = name;
        }
        let changed = version.is_some_and(|v| installed.server_version != Some(v));
        if changed {
            installed.server_version = version;
        }
        changed
    }

    /// [`Self::apply_public_server_info`]'s on-disk persist step, shared with the
    /// [`Self::persist_server_version`] test hook below. Takes `session_lock`/`data_dir` as
    /// plain values, not off `self`, so the caller can run this on `spawn_blocking`/a plain
    /// thread with no `JellybeamCore` reference.
    ///
    /// Re-reads the session list *inside* `session_lock` and writes only the record matching
    /// `(server_url, user_id)`, and only the fields that actually changed. If a concurrent
    /// switch/remove already removed that record, writes nothing.
    fn persist_server_identity_locked(
        session_lock: &Mutex<()>,
        data_dir: &Path,
        server_url: &str,
        user_id: &str,
        version: Option<&str>,
        name: Option<&str>,
    ) {
        let persisted = session::mutate(data_dir, session_lock, |sessions| {
            let record = sessions
                .sessions
                .iter_mut()
                .find(|s| s.is_account(server_url, user_id))
                .ok_or(())?;
            if let Some(version) = version.filter(|v| record.server_version.as_deref() != Some(v)) {
                record.server_version = Some(version.to_string());
            }
            if let Some(name) = name.filter(|n| record.server_name.as_deref() != Some(n)) {
                record.server_name = Some(name.to_string());
            }
            Ok::<(), ()>(())
        });
        if let Err(session::MutateError::Storage(e)) = persisted {
            tracing::warn!(error = %e, "failed to persist refreshed server identity");
        }
    }

    /// Test hook exposing [`Self::persist_server_identity_locked`] directly on a live
    /// `JellybeamCore`: lets a test drive "a refresh persist runs after some other state-mutating
    /// call already completed" without racing a real background task. Production code never
    /// calls this directly.
    #[cfg(test)]
    pub(crate) fn persist_server_version(&self, server_url: &str, user_id: &str, version: &str) {
        Self::persist_server_identity_locked(
            &self.session_lock,
            &self.data_dir,
            server_url,
            user_id,
            Some(version),
            None,
        );
    }

    /// Abort `task` (if any) and block until it has finished, so nothing it holds (a `Mirror`
    /// clone) outlives the caller's teardown.
    fn stop_task(&self, task: Option<tokio::task::JoinHandle<()>>) {
        if let Some(handle) = task {
            handle.abort();
            let _ = self.runtime.block_on(handle);
        }
    }
}

impl Drop for JellybeamCore {
    fn drop(&mut self) {
        let (installed, preload_task, reporting, parked) = {
            let mut state = self.lock_state();
            (
                state.account.installed.take(),
                state.preload_task.take().map(|task| task.handle),
                state.reporting.take(),
                state.playback_owner.parked.take(),
            )
        };
        drop(parked);
        // A dropped `JellybeamCore` (app process teardown) is not the place to
        // block on a final network round trip -- `abandon` (sync, no I/O)
        // just stops the reporting task's background work, safe regardless
        // of field-drop ordering.
        if let Some(reporting) = reporting {
            reporting.abandon();
        }
        Self::abort_task(preload_task);
        // Blocks on the runtime, which is still alive here (fields drop after this body).
        self.stop_event_bus(installed.and_then(|i| i.bundle));
    }
}

/// The access token a sign-in response must carry.
fn access_token_of(auth: &jellyfin_api::models::AuthenticationResult) -> Result<String, CoreError> {
    auth.access_token.clone().ok_or_else(|| CoreError::Api {
        detail: "sign-in response did not include an access token".to_string(),
    })
}

/// The non-virtual episode ids of a bulk-mark scope read; an unreadable scope (`None`) fails
/// closed, so a mark never covers a partial episode set (docs/07 Watch state, Ordering).
fn scope_episode_ids(rows: Option<Vec<media_cache::CardRow>>) -> Result<Vec<String>, CoreError> {
    let rows = rows.ok_or_else(|| CoreError::Cache {
        detail: "the scope's episodes could not be read from the mirror".to_string(),
    })?;
    Ok(rows
        .into_iter()
        .filter(|row| !row.is_virtual)
        .map(|row| row.id)
        .collect())
}

/// The season and series ids of `item_id` when the mirror knows it as an
/// Episode, else empty: the scope whose unplayed counts change with it.
fn episode_scope_ids(mirror: &media_cache::Mirror, item_id: &str) -> Vec<String> {
    let Some(dto) = mirror.item(item_id) else {
        return Vec::new();
    };
    if dto.type_ != Some(jellyfin_api::models::BaseItemKind::Episode) {
        return Vec::new();
    }
    [dto.season_id, dto.series_id]
        .into_iter()
        .flatten()
        .map(|id| id.to_string())
        .collect()
}

/// Maps the two `Settings` fields that control `/Shows/NextUp` filtering
/// onto `media_cache`'s options type -- shared by
/// [`JellybeamCore::open_mirror`] and [`JellybeamCore::set_settings`].
fn next_up_options_from(settings: &Settings) -> media_cache::NextUpOptions {
    media_cache::NextUpOptions {
        cutoff_days: settings.next_up_cutoff_days,
        rewatching: settings.next_up_rewatching,
    }
}

/// docs/17 §1: a sync parked behind playback isn't shown, since it makes no progress until playback ends.
fn shown_as_syncing(syncing: bool, playback_active: bool) -> bool {
    syncing && !playback_active
}

fn changed_next_up_options(
    previous: &Settings,
    current: &Settings,
) -> Option<media_cache::NextUpOptions> {
    let options = next_up_options_from(current);
    (options != next_up_options_from(previous)).then_some(options)
}

/// Normalize UUID spellings; leave non-UUID identifiers unchanged.
fn canonicalize_item_id(item_id: &str) -> String {
    uuid::Uuid::parse_str(item_id)
        .map(|id| id.to_string())
        .unwrap_or_else(|_| item_id.to_string())
}

fn host_from_server_url(url: &str) -> Option<String> {
    let without_scheme = url.split_once("://").map(|(_, rest)| rest).unwrap_or(url);
    let authority_end = without_scheme
        .find(['/', '?', '#'])
        .unwrap_or(without_scheme.len());
    let authority = &without_scheme[..authority_end];
    // Drop `user[:pass]@` userinfo, if present -- a server_url never
    // carries one in practice, but a stray one shouldn't leak into the name.
    let authority = authority
        .rsplit_once('@')
        .map_or(authority, |(_, host)| host);
    let host = authority.split(':').next().unwrap_or(authority);
    if host.is_empty() {
        None
    } else {
        Some(host.to_string())
    }
}

/// Refuses a virtual item (no real media file backing it) up front, before
/// asking the server to negotiate playback. Pure and independently
/// unit-testable: mirrors `media_cache::CardRow::is_virtual`'s own
/// `LocationType == Virtual` check, applied to the full `BaseItemDto`.
fn refuse_if_virtual(
    item_id: &str,
    item: &jellyfin_api::models::BaseItemDto,
) -> Result<(), CoreError> {
    if item.location_type == Some(jellyfin_api::models::LocationType::Virtual) {
        return Err(CoreError::Cache {
            detail: format!("item {item_id} is virtual -- it has no playable media"),
        });
    }
    Ok(())
}

/// The pure half of [`JellybeamCore::children_read`]'s virtual-episode filtering
/// (`Settings::show_virtual_episodes`): drops any `cards` row that is both
/// `item_type == "Episode"` and `is_virtual` when the setting is `false`,
/// leaves everything else untouched. Scoped to `Episode` rows only -- a
/// virtual `Season` (Hazard 2b) must never be dropped by this, since the
/// season grid decides play/no-play per *episode*, not per season.
fn filter_virtual_episodes(cards: Vec<Card>, show_virtual_episodes: bool) -> Vec<Card> {
    if show_virtual_episodes {
        return cards;
    }
    cards
        .into_iter()
        .filter(|card| !(card.item_type == "Episode" && card.is_virtual))
        .collect()
}

/// The pure half of [`JellybeamCore::children_read`]'s Series -> Seasons follow-on
/// to [`filter_virtual_episodes`]: hides a Season chip whose every episode
/// is virtual, so a fully-unaired season never opens onto an empty grid.
/// `counts` is [`media_cache::Mirror::season_episode_counts`]'s
/// `(season_id, total_episodes, non_virtual_episodes)`; a `Season` card is
/// dropped only when its row is present with `total_episodes > 0` and
/// `non_virtual_episodes == 0`.
///
/// FAIL OPEN by construction: zero episode rows synced yet, or a season
/// absent from `counts` entirely, are both kept. A virtual `Season`
/// (Hazard 2b) holding at least one real episode is never touched.
///
/// Scoped to `Season` rows only; every other card passes through untouched.
fn filter_all_virtual_seasons(
    cards: Vec<Card>,
    counts: &[(String, i64, i64)],
    show_virtual_episodes: bool,
) -> Vec<Card> {
    if show_virtual_episodes {
        return cards;
    }
    cards
        .into_iter()
        .filter(|card| {
            if card.item_type != "Season" {
                return true;
            }
            match counts.iter().find(|(id, _, _)| id == &card.id) {
                Some((_, total_episodes, non_virtual_episodes)) => {
                    *total_episodes == 0 || *non_virtual_episodes > 0
                }
                // Fail open: no counts row for this season -- never hide on
                // absence of evidence.
                None => true,
            }
        })
        .collect()
}

/// docs/07 §1: the position to put in the final Stopped report and whether the stop finished the
/// item; in the end grace the runtime is reported so the server marks it played itself, and an
/// item the mirror doesn't know is never finished early.
fn final_report(row: Option<&media_cache::CardRow>, position_ticks: i64) -> (i64, bool) {
    let Some(row) = row else {
        return (position_ticks, false);
    };
    let in_grace =
        media_cache::watch_grace::watch_state(&row.item_type, position_ticks, row.runtime_ticks)
            == media_cache::watch_grace::WatchState::Watched;
    // `Watched` implies a known positive runtime.
    match row.runtime_ticks {
        Some(runtime) if in_grace => (runtime, true),
        _ => (position_ticks, false),
    }
}

/// The pure half of [`JellybeamCore::prepare_playback`]'s start-ticks decision
/// (docs/11 item 11, "Start from beginning"): `true` always starts at tick 0;
/// `false` resumes only an in-progress position, so playback agrees with the
/// Play/Resume label (docs/07 §1 grace windows).
fn resolved_start_ticks(
    start_from_beginning: bool,
    dto: &jellyfin_api::models::BaseItemDto,
) -> Option<i64> {
    if start_from_beginning {
        return None;
    }
    let saved = dto.user_data.as_ref()?.playback_position_ticks?;
    let item_type = dto.type_.map(|t| t.to_string()).unwrap_or_default();
    let (position, _) = media_cache::watch_grace::displayed_watch_state(
        &item_type,
        saved,
        dto.run_time_ticks,
        false,
    );
    (position > 0).then_some(position)
}

/// Builds Jellybeam TV's device profile from stored
/// [`jellyfin_core::AndroidTvCaps`], round-tripping
/// `jellyfin_core::android_tv_profile`'s `RawDeviceProfile` through
/// `serde_json::Value` into the real generated
/// `jellyfin_api::models::DeviceProfile` -- same pattern as
/// `jellyfin_core::build_device_profile`, duplicated here because that
/// function is hardcoded to the mpv builder.
///
/// `tolerate_mislabeled_levels` is `Settings::tolerate_mislabeled_levels`
/// (default `true`), passed straight through to `android_tv_profile`;
/// `ass_sidecars` adds `jellyfin_core::allow_ass_sidecars`.
fn build_android_profile(
    caps: &jellyfin_core::AndroidTvCaps,
    tolerate_mislabeled_levels: bool,
    ass_sidecars: bool,
) -> jellyfin_api::models::DeviceProfile {
    let mut raw = jellyfin_core::android_tv_profile(caps, tolerate_mislabeled_levels);
    if ass_sidecars {
        jellyfin_core::allow_ass_sidecars(&mut raw);
    }
    match serde_json::to_value(raw) {
        Ok(value) => match serde_json::from_value(value) {
            Ok(profile) => profile,
            Err(err) => {
                tracing::error!(
                    ?err,
                    "AndroidTvCaps RawDeviceProfile -> DeviceProfile round trip failed \
                     after serializing successfully; falling back to an empty default \
                     DeviceProfile (server will transcode nearly everything)"
                );
                jellyfin_api::models::DeviceProfile::default()
            }
        },
        Err(err) => {
            tracing::error!(
                ?err,
                "failed to serialize android_tv_profile to JSON; falling back to an \
                 empty default DeviceProfile (server will transcode nearly everything)"
            );
            jellyfin_api::models::DeviceProfile::default()
        }
    }
}

/// [`Settings::playback_quality`]'s effect on the negotiated
/// `DeviceProfile` (docs/18-playback-quality.md §2): in `Cap` mode,
/// `max_bps` is threaded onto `AndroidTvCaps::max_streaming_bitrate` before
/// [`build_android_profile`]. `DirectPlay`/`Auto` pass `caps` through
/// unmodified. ASS sidecars are offered only when `may_direct_play` (§3.2): a
/// transcode offered them hands out even embedded ASS instead of burning it in
/// (§3.1). Shared by [`JellybeamCore::prepare_playback`], the fallback and
/// [`JellybeamCore::build_preload_cache`].
fn build_android_profile_for_quality(
    caps: &jellyfin_core::AndroidTvCaps,
    tolerate_mislabeled_levels: bool,
    quality: &PlaybackQuality,
    may_direct_play: bool,
) -> jellyfin_api::models::DeviceProfile {
    match quality {
        PlaybackQuality::Cap { max_bps } => {
            let capped = jellyfin_core::AndroidTvCaps {
                max_streaming_bitrate: Some(*max_bps),
                ..caps.clone()
            };
            build_android_profile(&capped, tolerate_mislabeled_levels, false)
        }
        PlaybackQuality::DirectPlay | PlaybackQuality::Auto => {
            build_android_profile(caps, tolerate_mislabeled_levels, may_direct_play)
        }
    }
}

/// [`resolve_plan`]'s resolved outcome for `DirectPlay`/`Auto` modes. `Cap`
/// mode never produces one: it negotiates forced-transcode from the start
/// and resolves through [`transcode_url_from_decision`] instead.
#[derive(Debug)]
enum ResolvedPlan {
    DirectPlay {
        source: jellyfin_api::models::MediaSourceInfo,
        url: String,
        server_verdict: Option<String>,
    },
    Transcode {
        source: jellyfin_api::models::MediaSourceInfo,
        url: String,
        reason: String,
    },
}

/// `&MediaSourceInfo` for either arm of a
/// [`jellyfin_core::PlaybackDecision`] -- lets a caller inspect the chosen
/// source before handing the decision to [`resolve_plan`].
fn decision_source(
    decision: &jellyfin_core::PlaybackDecision,
) -> &jellyfin_api::models::MediaSourceInfo {
    match decision {
        jellyfin_core::PlaybackDecision::DirectPlay { source, .. } => source,
        jellyfin_core::PlaybackDecision::Transcode { source, .. } => source,
    }
}

/// The chosen source's own video stream codec, lowercased -- Auto mode's
/// local-codec-corroboration input (docs/18-playback-quality.md §2):
/// [`resolve_plan`] compares this against
/// `jellyfin_core::android_direct_play_video_codecs(caps)`. `None` when the
/// source has no `Video` stream or no `Codec` fact -- a codec-blind source
/// must never be treated as "known unsupported".
fn video_stream_codec(source: &jellyfin_api::models::MediaSourceInfo) -> Option<String> {
    source
        .media_streams
        .iter()
        .find(|stream| stream.type_ == Some(jellyfin_api::models::MediaStreamType::Video))
        .and_then(|stream| stream.codec.clone())
        .filter(|codec| !codec.is_empty())
        .map(|codec| codec.to_lowercase())
}

/// [`JellybeamCore::prepare_playback`]'s per-mode resolution of one negotiated
/// [`jellyfin_core::PlaybackDecision`] for `DirectPlay`/`Auto` modes (`Cap`
/// never calls this). docs/18-playback-quality.md §2: Auto only transcodes
/// on evidence this TV's own device profile can judge, never the server's
/// opinion alone.
///
/// - `DirectPlay` decision -> `Ok(DirectPlay)`, `server_verdict: None`, in
///   every mode.
/// - `Transcode` decision, mode `DirectPlay` -> `Err(reasons.join("; "))`,
///   mapped by the caller onto `CoreError::WouldTranscode`.
/// - `Transcode` decision, mode `Auto`, `tolerate_mislabeled_levels` is
///   `false` -> `Ok(Transcode)` on `hls_url` -- with the setting off, level
///   verdicts are the TV's own declared limits talking (docs/18 §1.1 row 2).
/// - `Transcode` decision, mode `Auto`, `video_codec` is `Some` and not in
///   `supported_codecs` -> `Ok(Transcode)` on the decision's own `hls_url`,
///   `reason: "no <codec> decoder on this TV"`.
/// - `Transcode` decision, mode `Auto`, `video_codec` is `None` or in
///   `supported_codecs` -> `Ok(DirectPlay)` on `direct_url`,
///   `server_verdict: Some(...)` -- a server "would transcode" verdict
///   alone is never a reason to transcode (docs/18 §1).
///
/// `quality: &PlaybackQuality` keeps this total; an unexpected `Cap` value
/// resolves like `Auto` rather than panicking. Pure, unit-tested per row.
fn resolve_plan(
    quality: &PlaybackQuality,
    decision: jellyfin_core::PlaybackDecision,
    video_codec: Option<&str>,
    supported_codecs: &[jellyfin_core::VideoCodec],
    tolerate_mislabeled_levels: bool,
) -> Result<ResolvedPlan, String> {
    match decision {
        jellyfin_core::PlaybackDecision::DirectPlay { source, url } => {
            Ok(ResolvedPlan::DirectPlay {
                source,
                url,
                server_verdict: None,
            })
        }
        jellyfin_core::PlaybackDecision::Transcode {
            source,
            hls_url,
            direct_url,
            reasons,
        } => {
            if matches!(quality, PlaybackQuality::DirectPlay) {
                return Err(reasons.join("; "));
            }
            if !tolerate_mislabeled_levels {
                // The profile carried honest per-profile level ceilings
                // (`tolerate_mislabeled_levels` off), so this verdict is
                // the TV's own declared limits -- trust it up front.
                return Ok(ResolvedPlan::Transcode {
                    source,
                    url: hls_url,
                    reason: reasons.join("; "),
                });
            }
            let locally_unsupported = match video_codec {
                Some(codec) => match jellyfin_core::VideoCodec::from_server_codec(codec) {
                    Some(vc) => !supported_codecs.contains(&vc),
                    // Not even a codec this app can name -- can't possibly
                    // be one of `supported_codecs` either.
                    None => true,
                },
                None => false,
            };
            if locally_unsupported {
                let codec = video_codec.expect("Some in every locally_unsupported=true case above");
                Ok(ResolvedPlan::Transcode {
                    source,
                    url: hls_url,
                    reason: format!("no {codec} decoder on this TV"),
                })
            } else {
                Ok(ResolvedPlan::DirectPlay {
                    source,
                    url: direct_url,
                    server_verdict: Some(reasons.join("; ")),
                })
            }
        }
    }
}

/// docs/18 §2: the fallback's negotiation -- a forced transcode of the same source that never copies
/// the stream type that `failed` (an unattributed failure is treated as video, the usual culprit;
/// `None` is a renegotiation with no failure) and carries the viewer's subtitle stream (§3.1).
fn fallback_options(
    media_source_id: String,
    failed: Option<FailedTrackFfi>,
    subtitle_stream_index: Option<i32>,
) -> jellyfin_api::PlaybackInfoOptions {
    let (video_copy, audio_copy) = match failed {
        Some(FailedTrackFfi::Video | FailedTrackFfi::Unknown) => (Some(false), None),
        Some(FailedTrackFfi::Audio) => (None, Some(false)),
        None => (None, None),
    };
    jellyfin_api::PlaybackInfoOptions {
        force_transcode: true,
        media_source_id: Some(media_source_id).filter(|id| !id.is_empty()),
        allow_video_stream_copy: video_copy,
        allow_audio_stream_copy: audio_copy,
        subtitle_stream_index,
    }
}

/// Accepts either shape of a forced-transcode `PlaybackInfo` negotiation's
/// [`jellyfin_core::PlaybackDecision`] (docs/18-playback-quality.md §2): a
/// genuine `Transcode` decision, or a codec-blind `DirectPlay` decision
/// whose source still carries a `TranscodingUrl` --
/// `jellyfin_core::playback::choose`'s codec-blind rule keeps choosing
/// `DirectPlay` even under `force_transcode`. Using
/// [`jellyfin_api::JellyfinClient::stream_url`] on that source yields the
/// same server-side transcode stream a `Transcode` decision would have.
/// `None` only for a source with no `TranscodingUrl` at all -- callers map
/// that to `NoPlayableSource`.
///
/// Shared by [`JellybeamCore::prepare_playback`]'s Cap-mode branch and
/// [`JellybeamCore::prepare_transcode_fallback`] so the two acceptance rules
/// can't drift.
fn transcode_url_from_decision(
    client: &jellyfin_api::JellyfinClient,
    item_id: &str,
    decision: jellyfin_core::PlaybackDecision,
) -> Option<(jellyfin_api::models::MediaSourceInfo, String)> {
    match decision {
        jellyfin_core::PlaybackDecision::Transcode {
            source, hls_url, ..
        } => Some((source, hls_url)),
        jellyfin_core::PlaybackDecision::DirectPlay { source, .. }
            if source.transcoding_url.is_some() =>
        {
            let url = client.stream_url(item_id, &source);
            Some((source, url))
        }
        _ => None,
    }
}

/// Cap mode's fixed `PlaybackPlan::transcode_reason` text
/// (docs/18-playback-quality.md §2): `max_bps` in whole Mbps when it
/// divides evenly (true of every preset), else one decimal place.
fn cap_transcode_reason(max_bps: u32) -> String {
    if max_bps.is_multiple_of(1_000_000) {
        format!("quality cap {} Mbps", max_bps / 1_000_000)
    } else {
        format!("quality cap {:.1} Mbps", f64::from(max_bps) / 1_000_000.0)
    }
}

#[cfg(test)]
mod tests {
    /// (highest admitted seq, current claim, account epoch).
    fn owner_view(core: &JellybeamCore) -> (u64, Option<PlaybackClaim>, u64) {
        let state = core.lock_state();
        let owner = &state.playback_owner;
        (owner.highest_seq, owner.claim.clone(), state.account.epoch)
    }

    /// Admits the next request directly, as a prepare would before negotiating.
    fn admit(core: &JellybeamCore) -> PlaybackClaim {
        let request = core.next_request();
        JellybeamCore::admit_playback_request(&mut core.lock_state(), request, None)
            .expect("the next request is admitted")
    }

    impl JellybeamCore {
        /// How many times this core has entered `open_mirror`.
        fn mirror_open_count(&self) -> usize {
            self.mirror_opens.load(std::sync::atomic::Ordering::SeqCst)
        }

        /// Kotlin's ownership check: the current epoch, or the parked one.
        fn owns_playback(&self, epoch: u64) -> bool {
            epoch == self.account_epoch() || self.parked_account_epoch() == Some(epoch)
        }

        /// The request a Kotlin caller would mint next: one past the highest admitted seq, under
        /// the current account epoch.
        fn next_request(&self) -> PlaybackRequest {
            let state = self.lock_state();
            PlaybackRequest {
                seq: state.playback_owner.highest_seq + 1,
                account_epoch: state.account.epoch,
            }
        }

        fn prepare_playback_now(
            &self,
            item_id: String,
            start_from_beginning: bool,
        ) -> Result<PlaybackPlan, CoreError> {
            let request = self.next_request();
            self.prepare_playback(item_id, start_from_beginning, request)
        }

        fn prepare_transcode_fallback_now(
            &self,
            item_id: String,
            position_ticks: i64,
            reason: String,
            play_session_id: String,
        ) -> Result<PlaybackPlan, CoreError> {
            let request = self.next_request();
            self.prepare_transcode_fallback(
                item_id,
                position_ticks,
                reason,
                play_session_id,
                Some(FailedTrackFfi::Video),
                None,
                request,
            )
        }
    }

    use super::*;

    /// Removes the active account, as the server manager does (the only way the UI leaves one).
    fn remove_active_account(core: &JellybeamCore) {
        let index = core.active_account_index().expect("an account is active");
        core.remove_session(index)
            .expect("the active account is removed");
    }

    fn core_in_tempdir() -> (tempfile::TempDir, Arc<JellybeamCore>) {
        let dir = tempfile::tempdir().expect("tempdir");
        let core = JellybeamCore::new(dir.path().to_string_lossy().to_string());
        (dir, core)
    }

    #[test]
    fn next_up_refresh_only_follows_changes_to_its_options() {
        let before = Settings::default();
        assert_eq!(changed_next_up_options(&before, &before), None);
        let unrelated = Settings {
            skip_back_secs: before.skip_back_secs + 5,
            hidden_library_ids: vec!["synthetic-library".into()],
            hide_watched_in_latest: !before.hide_watched_in_latest,
            ..before.clone()
        };
        assert_eq!(changed_next_up_options(&before, &unrelated), None);
        let cutoff = Settings {
            next_up_cutoff_days: Some(14),
            ..before.clone()
        };
        assert_eq!(
            changed_next_up_options(&before, &cutoff),
            Some(next_up_options_from(&cutoff))
        );
        assert_eq!(
            changed_next_up_options(&cutoff, &before),
            Some(next_up_options_from(&before))
        );
        let rewatching = Settings {
            next_up_rewatching: !before.next_up_rewatching,
            ..before.clone()
        };
        assert_eq!(
            changed_next_up_options(&before, &rewatching),
            Some(next_up_options_from(&rewatching))
        );
    }

    #[test]
    fn a_sync_parked_behind_playback_is_not_shown() {
        for (syncing, playback_active, shown) in [
            (false, false, false),
            (true, false, true),
            (true, true, false),
            (false, true, false),
        ] {
            assert_eq!(
                shown_as_syncing(syncing, playback_active),
                shown,
                "syncing={syncing} playback_active={playback_active}"
            );
        }
    }

    #[test]
    fn snapshot_queries_are_safe_defaults_before_mirror_is_open() {
        let (_dir, core) = core_in_tempdir();
        assert!(core.views().is_empty());
        assert_eq!(core.item_count(), 0);
        assert!(!core.is_syncing());
        assert_eq!(core.sync_status(), SyncStatus::Idle);
        assert!(matches!(
            core.children_read("any-parent".to_string(), SortOrder::NameAsc, 0, 10),
            BrowseRead::NotReady
        ));
        assert!(core.search("anything".to_string(), 10).is_empty());
        let HomeSnapshot::Classic { home } = core.home_snapshot(HomeLayout::Classic);
        assert_eq!(home, crate::home::ClassicHome::default());
    }

    /// `card_by_id` has a `Result` (unlike the fail-open `Vec` queries
    /// above): "no mirror at all" is a real error, unlike "unknown id".
    #[test]
    fn card_by_id_without_open_mirror_is_mirror_not_open() {
        let (_dir, core) = core_in_tempdir();
        let err = core
            .card_by_id("item-1".to_string())
            .expect_err("mirror never opened");
        assert!(matches!(err, CoreError::MirrorNotOpen));
    }

    #[test]
    fn open_mirror_before_sign_in_is_not_signed_in() {
        let (_dir, core) = core_in_tempdir();
        let err = core.open_mirror().expect_err("no client yet");
        assert!(matches!(err, CoreError::NotSignedIn));
    }

    #[test]
    fn image_url_before_sign_in_is_not_signed_in() {
        let (_dir, core) = core_in_tempdir();
        let err = core
            .image_url(
                "item-1".to_string(),
                ImageKind::Primary,
                "tag".to_string(),
                200,
                None,
            )
            .expect_err("no client yet");
        assert!(matches!(err, CoreError::NotSignedIn));
    }

    #[test]
    fn image_url_prefix_names_the_current_epoch_and_none_before_sign_in() {
        let (_dir, core) = core_in_tempdir();
        assert!(core.image_url_prefix().is_none());
        install_without_refresh(&core, "http://localhost:8096");
        let prefix = core.image_url_prefix().expect("signed in");
        assert_eq!(prefix.epoch, core.account_epoch());
        assert_eq!(prefix.base_url, "http://localhost:8096");
        assert_eq!(prefix.token, "tok");
    }

    #[test]
    fn restore_session_with_nothing_saved_returns_none() {
        let (_dir, core) = core_in_tempdir();
        assert!(core.restore_session().is_none());
    }

    /// A signed-in core with its mirror open (nothing synced: the server is unreachable).
    fn core_with_open_mirror() -> (tempfile::TempDir, Arc<JellybeamCore>) {
        let (dir, core) = core_in_tempdir();
        seed_session(dir.path(), "http://server-a.test", "u1");
        assert!(core.restore_session().is_some());
        core.open_mirror().expect("opens local SQLite only");
        (dir, core)
    }

    /// Installs an account on `server_url` (token "tok") through the one transition, without the
    /// server-version refresh an install spawns, so a mock's last request stays the test's own.
    fn install_without_refresh(core: &JellybeamCore, server_url: &str) {
        let saved = slot_saved(server_url, "u1", "10.11.10", "Synthetic Server");
        let mut reset = JellybeamCore::reset_account_state_locked(
            &mut core.lock_state(),
            Some(slot_installed(&saved)),
            AccountChange::Replace,
        );
        reset.refresh = None;
        core.dispose_account_reset(reset);
    }

    fn installed_mirror_dir(core: &JellybeamCore) -> String {
        let state = core.lock_state();
        let installed = state.account.installed().expect("an account is installed");
        installed.mirror_dir.clone()
    }

    /// Makes every mirror read fail the way a damaged database does: the items table is gone.
    fn break_mirror_reads(dir: &std::path::Path, core: &JellybeamCore) {
        let mirror_dir = installed_mirror_dir(core);
        let conn = rusqlite::Connection::open(dir.join(mirror_dir).join("mirror.db"))
            .expect("opens the mirror file");
        conn.busy_timeout(Duration::from_secs(5)).expect("timeout");
        conn.execute_batch("DROP TABLE items;")
            .expect("drops the items table");
    }

    const BROWSE_PARENT: &str = "00000000-0000-0000-0000-0000000000a1";

    fn browse_children(core: &JellybeamCore) -> BrowseRead {
        core.children_read(BROWSE_PARENT.to_string(), SortOrder::NameAsc, 0, 50)
    }

    /// Models a Kotlin listener whose first `onChange` throws.
    struct ThrowsOnceListener {
        calls: Arc<std::sync::atomic::AtomicUsize>,
        delivered: std::sync::mpsc::Sender<ChangeEvent>,
    }

    impl ChangeListener for ThrowsOnceListener {
        fn on_change(&self, event: ChangeEvent) -> Result<(), crate::error::CallbackError> {
            if self.calls.fetch_add(1, std::sync::atomic::Ordering::SeqCst) == 0 {
                return Err(crate::error::CallbackError::Failed);
            }
            let _ = self.delivered.send(event);
            Ok(())
        }

        fn on_unauthorized(
            &self,
            _account: AccountIdentity,
        ) -> Result<(), crate::error::CallbackError> {
            Ok(())
        }
    }

    /// A listener that throws once keeps receiving later changes.
    #[test]
    fn a_change_listener_that_throws_once_still_gets_the_next_change() {
        let (dir, core) = core_with_open_mirror();
        let mirror_dir = installed_mirror_dir(&core);
        rusqlite::Connection::open(dir.path().join(mirror_dir).join("mirror.db"))
            .expect("opens the mirror file")
            .execute_batch(
                "INSERT INTO items (id, item_type, dto, updated_at) VALUES \
                 ('item-a', 'Movie', '{\"Id\":\"item-a\"}', 0);",
            )
            .expect("seeds an item");
        let calls = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let (tx, rx) = std::sync::mpsc::channel();
        core.set_change_listener(Arc::new(ThrowsOnceListener {
            calls: calls.clone(),
            delivered: tx,
        }));
        let mirror = core.require_mirror().expect("mirror open");
        let write = |ticks: i64| {
            core.runtime
                .block_on(mirror.apply_local_user_data_and_wait("item-a", ticks, None));
        };
        // The listener task subscribes asynchronously: write until the throwing call lands.
        let deadline = std::time::Instant::now() + Duration::from_secs(10);
        let mut ticks = 1;
        while calls.load(std::sync::atomic::Ordering::SeqCst) == 0 {
            assert!(
                std::time::Instant::now() < deadline,
                "listener never called"
            );
            write(ticks);
            ticks += 1;
            std::thread::sleep(Duration::from_millis(100));
        }
        write(ticks);
        rx.recv_timeout(Duration::from_secs(10))
            .expect("the change after the throw is delivered");
    }

    /// docs/07 §5: a read before the mirror opens is "not ready", not an empty folder.
    #[test]
    fn children_before_the_mirror_opens_is_not_ready() {
        let (_dir, core) = core_in_tempdir();
        assert!(matches!(browse_children(&core), BrowseRead::NotReady));
    }

    #[test]
    fn children_of_an_empty_parent_is_genuinely_empty() {
        let (_dir, core) = core_with_open_mirror();
        assert!(matches!(browse_children(&core), BrowseRead::Items { cards } if cards.is_empty()));
    }

    /// docs/07 §5: a failed query must not look like an empty folder, or a screen settles on it.
    #[test]
    fn children_when_the_query_fails_is_failed() {
        let (dir, core) = core_with_open_mirror();
        break_mirror_reads(dir.path(), &core);
        assert!(matches!(browse_children(&core), BrowseRead::Failed));
    }

    #[test]
    fn series_episodes_before_the_mirror_opens_is_not_ready() {
        let (_dir, core) = core_in_tempdir();
        assert!(matches!(
            core.series_episodes_read(BROWSE_PARENT.to_string()),
            BrowseRead::NotReady
        ));
    }

    #[test]
    fn series_episodes_of_a_series_without_episodes_is_genuinely_empty() {
        let (_dir, core) = core_with_open_mirror();
        assert!(matches!(
            core.series_episodes_read(BROWSE_PARENT.to_string()),
            BrowseRead::Items { cards } if cards.is_empty()
        ));
    }

    #[test]
    fn series_episodes_when_the_query_fails_is_failed() {
        let (dir, core) = core_with_open_mirror();
        break_mirror_reads(dir.path(), &core);
        assert!(matches!(
            core.series_episodes_read(BROWSE_PARENT.to_string()),
            BrowseRead::Failed
        ));
    }

    #[test]
    fn current_account_with_nothing_saved_returns_none() {
        let (_dir, core) = core_in_tempdir();
        assert!(core.current_account().is_none());
    }

    #[test]
    fn current_account_reads_a_saved_session_without_touching_state() {
        let (dir, core) = core_in_tempdir();
        let saved = session::SessionFile {
            server_url: "http://server.test".to_string(),
            user_id: "u1".to_string(),
            user_name: "jellybeam-user".to_string(),
            token: "tok".to_string(),
            device_id: "dev".to_string(),
            mirror_dir: "mirror".to_string(),
            server_version: None,
            server_name: None,
        };
        session::add_or_update(dir.path(), saved).expect("seed a session file");

        let account = core
            .current_account()
            .expect("session file was just written");
        assert_eq!(account.server_url, "http://server.test");
        assert_eq!(account.user_id, "u1");
        assert_eq!(account.user_name, "jellybeam-user");

        // Pure read: unlike `restore_session`, must not have installed a
        // client -- every mirror/client-dependent call should still see
        // "not signed in".
        let err = core
            .open_mirror()
            .expect_err("current_account must not sign in");
        assert!(matches!(err, CoreError::NotSignedIn));
    }

    #[test]
    fn server_display_name_with_nothing_saved_returns_none() {
        let (_dir, core) = core_in_tempdir();
        assert!(core.server_display_name(None).is_none());
    }

    #[test]
    fn server_display_name_derives_the_host_from_the_active_session_url() {
        let (dir, core) = core_in_tempdir();
        let saved = session::SessionFile {
            server_url: "https://shelf.test:8920/jellyfin".to_string(),
            user_id: "u1".to_string(),
            user_name: "jellybeam-user".to_string(),
            token: "tok".to_string(),
            device_id: "dev".to_string(),
            mirror_dir: "mirror".to_string(),
            server_version: None,
            server_name: None,
        };
        session::add_or_update(dir.path(), saved).expect("seed a session file");
        assert!(core.restore_session().is_some());

        assert_eq!(
            core.server_display_name(None),
            Some("shelf.test".to_string())
        );
    }

    #[test]
    fn sign_in_against_an_unreachable_url_fails_fast_without_panicking() {
        let (_dir, core) = core_in_tempdir();
        // Port 1 on loopback: refused immediately, no connect-timeout wait.
        let started = std::time::Instant::now();
        let err = core
            .sign_in(
                "http://127.0.0.1:1".to_string(),
                "someone".to_string(),
                "wrong".to_string(),
            )
            .expect_err("nothing is listening on 127.0.0.1:1");
        assert!(
            matches!(err, CoreError::ServerUnreachable { .. }),
            "got {err:?}"
        );
        assert!(
            started.elapsed() < std::time::Duration::from_secs(5),
            "sign_in against a refused connection should fail fast, took {:?}",
            started.elapsed()
        );
    }

    #[test]
    fn prepare_playback_before_sign_in_is_not_signed_in() {
        let (_dir, core) = core_in_tempdir();
        let err = core
            .prepare_playback_now("item-1".to_string(), false)
            .expect_err("no client yet");
        assert!(matches!(err, CoreError::NotSignedIn));
    }

    #[test]
    fn prepare_playback_with_start_from_beginning_before_sign_in_is_still_not_signed_in() {
        // Same precondition gate regardless of the new flag's value.
        let (_dir, core) = core_in_tempdir();
        let err = core
            .prepare_playback_now("item-1".to_string(), true)
            .expect_err("no client yet");
        assert!(matches!(err, CoreError::NotSignedIn));
    }

    #[test]
    fn get_item_detail_before_sign_in_is_not_signed_in() {
        let (_dir, core) = core_in_tempdir();
        let err = core
            .get_item_detail("item-1".to_string(), None)
            .expect_err("no client yet");
        assert!(matches!(err, CoreError::NotSignedIn));
    }

    #[test]
    fn get_playback_osd_detail_before_sign_in_is_not_signed_in() {
        let (_dir, core) = core_in_tempdir();
        let err = core
            .get_playback_osd_detail("item-1".to_string(), "ms-1".to_string(), None)
            .expect_err("no client yet");
        assert!(matches!(err, CoreError::NotSignedIn));
    }

    #[test]
    fn get_similar_before_sign_in_is_not_signed_in() {
        let (_dir, core) = core_in_tempdir();
        let err = core
            .get_similar("item-1".to_string(), 16)
            .expect_err("no client yet");
        assert!(matches!(err, CoreError::NotSignedIn));
    }

    #[test]
    fn live_children_before_sign_in_is_not_signed_in() {
        let (_dir, core) = core_in_tempdir();
        let err = core
            .live_children("channel-1".to_string(), 0, 50, LiveSort::ServerOrder)
            .expect_err("no client yet");
        assert!(matches!(err, CoreError::NotSignedIn));
    }

    /// `live_children` must query non-recursively, server order, mapping
    /// the response onto `Card` preserving server order.
    #[test]
    fn live_children_sends_non_recursive_query_and_maps_cards_in_server_order() {
        let (_dir, core) = core_in_tempdir();

        let items_json = serde_json::json!({
            "Items": [
                {
                    "Id": "00000000-0000-0000-0000-000000000001",
                    "Name": "Recording A",
                    "Type": "Video"
                },
                {
                    "Id": "00000000-0000-0000-0000-000000000002",
                    "Name": "Recording B",
                    "Type": "Episode"
                }
            ],
            "TotalRecordCount": 2,
            "StartIndex": 0
        });
        let server = core.runtime.block_on(RouteMockServer::start());
        server.route("GET", "/Items", 200, items_json);

        install_without_refresh(&core, &server.base_url);

        let cards = core
            .live_children("channel-1".to_string(), 0, 50, LiveSort::ServerOrder)
            .expect("mock server responds 200");

        assert_eq!(cards.len(), 2, "both items must map to cards");
        assert_eq!(cards[0].id, "00000000-0000-0000-0000-000000000001");
        assert_eq!(cards[0].name, "Recording A");
        assert_eq!(cards[1].id, "00000000-0000-0000-0000-000000000002");
        assert_eq!(
            cards[1].name, "Recording B",
            "server order must be preserved, not re-sorted"
        );

        let request_line = server.last_request();
        assert!(
            request_line.contains("parentId=channel-1"),
            "must query the given parent: {request_line}"
        );
        assert!(
            request_line.contains("recursive=false"),
            "channel folders are listed non-recursively: {request_line}"
        );
        assert!(
            request_line.contains("startIndex=0"),
            "must forward start_index: {request_line}"
        );
        assert!(
            request_line.contains("limit=50"),
            "must forward limit: {request_line}"
        );
        assert!(
            !request_line.contains("sortBy"),
            "no sortBy: server/plugin order is the browse order for a channel: {request_line}"
        );
    }

    /// `NameAsc` must send `SortBy=SortName&SortOrder=Ascending`.
    #[test]
    fn live_children_name_asc_sends_sort_name_ascending() {
        let (_dir, core) = core_in_tempdir();

        let items_json = serde_json::json!({
            "Items": [],
            "TotalRecordCount": 0,
            "StartIndex": 0
        });
        let server = core.runtime.block_on(RouteMockServer::start());
        server.route("GET", "/Items", 200, items_json);

        install_without_refresh(&core, &server.base_url);

        core.live_children("channel-1".to_string(), 0, 50, LiveSort::NameAsc)
            .expect("mock server responds 200");

        let request_line = server.last_request();
        assert!(
            request_line.contains("sortBy=SortName"),
            "NameAsc must sort by SortName: {request_line}"
        );
        assert!(
            request_line.contains("sortOrder=Ascending"),
            "NameAsc must sort ascending: {request_line}"
        );
    }

    /// `NewestFirst` must send `SortBy=PremiereDate&SortOrder=Descending`.
    #[test]
    fn live_children_newest_first_sends_sort_premiere_date_descending() {
        let (_dir, core) = core_in_tempdir();

        let items_json = serde_json::json!({
            "Items": [],
            "TotalRecordCount": 0,
            "StartIndex": 0
        });
        let server = core.runtime.block_on(RouteMockServer::start());
        server.route("GET", "/Items", 200, items_json);

        install_without_refresh(&core, &server.base_url);

        core.live_children("folder-1".to_string(), 0, 50, LiveSort::NewestFirst)
            .expect("mock server responds 200");

        let request_line = server.last_request();
        assert!(
            request_line.contains("sortBy=PremiereDate"),
            "NewestFirst must sort by PremiereDate: {request_line}"
        );
        assert!(
            request_line.contains("sortOrder=Descending"),
            "NewestFirst must sort descending: {request_line}"
        );
    }

    #[test]
    fn get_media_segments_before_sign_in_degrades_to_empty() {
        let (_dir, core) = core_in_tempdir();
        // No client at all -- must fail open (empty list), never panic.
        assert!(core
            .get_media_segments("item-1".to_string(), None)
            .is_empty());
    }

    #[test]
    fn prepare_playback_without_open_mirror_is_mirror_not_open() {
        let (dir, core) = core_in_tempdir();
        // Seed a session for a client with no live server, skip open_mirror.
        let saved = session::SessionFile {
            server_url: "http://example.invalid".to_string(),
            user_id: "u1".to_string(),
            user_name: "jellybeam-user".to_string(),
            token: "tok".to_string(),
            device_id: "dev".to_string(),
            mirror_dir: "mirror".to_string(),
            server_version: None,
            server_name: None,
        };
        session::add_or_update(dir.path(), saved).expect("seed a session file");
        assert!(core.restore_session().is_some());

        let err = core
            .prepare_playback_now("item-1".to_string(), false)
            .expect_err("mirror never opened");
        assert!(matches!(err, CoreError::MirrorNotOpen));
    }

    use media_cache::watch_grace::TICKS_PER_MINUTE as MIN;

    fn movie_at(position_ticks: Option<i64>) -> jellyfin_api::models::BaseItemDto {
        jellyfin_api::models::BaseItemDto {
            type_: Some(jellyfin_api::models::BaseItemKind::Movie),
            run_time_ticks: Some(120 * MIN),
            user_data: Some(jellyfin_api::models::UserItemDataDto {
                playback_position_ticks: position_ticks,
                ..Default::default()
            }),
            ..Default::default()
        }
    }

    #[test]
    fn resolved_start_ticks_true_always_ignores_the_saved_position() {
        assert_eq!(resolved_start_ticks(true, &movie_at(Some(60 * MIN))), None);
        assert_eq!(resolved_start_ticks(true, &movie_at(None)), None);
    }

    #[test]
    fn resolved_start_ticks_false_resumes_only_an_in_progress_position() {
        assert_eq!(
            resolved_start_ticks(false, &movie_at(Some(60 * MIN))),
            Some(60 * MIN)
        );
        assert_eq!(resolved_start_ticks(false, &movie_at(None)), None);
        // docs/07 §1: inside either grace window, playback starts over like the Play label says.
        assert_eq!(resolved_start_ticks(false, &movie_at(Some(MIN))), None);
        assert_eq!(
            resolved_start_ticks(false, &movie_at(Some(115 * MIN))),
            None
        );
    }

    #[test]
    fn report_position_and_paused_with_no_active_session_do_not_panic() {
        let (_dir, core) = core_in_tempdir();
        // No prepare_playback ever succeeded: stop/abandon must be silent
        // no-ops (docs/18-playback-quality.md §2), any string exercises the
        // same mismatch path.
        core.report_position("none".to_string(), 1234);
        core.report_paused("none".to_string(), true);
        core.abandon_playback("no-active-session".to_string());
        // stop_playback with no mirror open must still return, not panic.
        core.stop_playback("no-active-session".to_string(), 0);
    }

    /// docs/18-playback-quality.md §2: a stop/abandon whose
    /// `play_session_id` names an already-replaced session must not touch
    /// the replacement.
    #[test]
    fn stop_playback_with_a_stale_play_session_id_does_not_touch_the_active_session() {
        let (item_json, direct_play_json) = direct_play_fixture();
        let (_dir, core, mock) = core_signed_in_against_mock(item_json, direct_play_json);

        let plan = core
            .prepare_playback_now("item-1".to_string(), false)
            .expect("initial Direct Play negotiation");
        let owner_before = owner_view(&core);

        core.stop_playback("not-the-active-session".to_string(), 999);

        assert_eq!(
            mock.playback_info_hit_count(),
            1,
            "a rejected stop must never touch the network"
        );
        let state = core.lock_state();
        let session = state
            .reporting
            .as_ref()
            .expect("the real active session must be untouched by the rejected stop");
        assert_eq!(session.context().play_session_id, plan.play_session_id);
        drop(state);
        assert_eq!(
            owner_view(&core),
            owner_before,
            "a rejected stop leaves ownership alone"
        );
    }

    /// Mirrors the stop test above for `abandon_playback`.
    #[test]
    fn abandon_playback_with_a_stale_play_session_id_does_not_touch_the_active_session() {
        let (item_json, direct_play_json) = direct_play_fixture();
        let (_dir, core, _mock) = core_signed_in_against_mock(item_json, direct_play_json);

        let plan = core
            .prepare_playback_now("item-1".to_string(), false)
            .expect("initial Direct Play negotiation");
        let owner_before = owner_view(&core);

        core.abandon_playback("not-the-active-session".to_string());

        let state = core.lock_state();
        let session = state
            .reporting
            .as_ref()
            .expect("the real active session must be untouched by the rejected abandon");
        assert_eq!(session.context().play_session_id, plan.play_session_id);
        drop(state);
        assert_eq!(
            owner_view(&core),
            owner_before,
            "a rejected abandon leaves ownership alone"
        );
    }

    #[test]
    fn local_progress_checkpoint_commits_first_positive_tick_then_throttles() {
        let mut checkpoint = LocalProgressCheckpoint::default();
        let start = Instant::now();

        assert!(!checkpoint.should_commit(0, start));
        assert!(checkpoint.should_commit(10_000_000, start));
        assert!(!checkpoint.should_commit(
            20_000_000,
            start + LOCAL_PROGRESS_CHECKPOINT_INTERVAL - Duration::from_millis(1),
        ));
        assert!(checkpoint.should_commit(30_000_000, start + LOCAL_PROGRESS_CHECKPOINT_INTERVAL,));

        checkpoint.reset();
        assert!(checkpoint.should_commit(
            40_000_000,
            start + LOCAL_PROGRESS_CHECKPOINT_INTERVAL + Duration::from_millis(1),
        ));
    }

    fn sample_card(id: &str, item_type: &str, is_virtual: bool) -> Card {
        Card {
            is_virtual,
            ..Card::sample(id, item_type)
        }
    }

    #[test]
    fn filter_virtual_episodes_hides_virtual_episodes_when_setting_is_off() {
        let cards = vec![
            sample_card("e1", "Episode", false),
            sample_card("e2", "Episode", true),
            sample_card("e3", "Episode", false),
        ];
        let filtered = filter_virtual_episodes(cards, false);
        assert_eq!(
            filtered.into_iter().map(|c| c.id).collect::<Vec<_>>(),
            vec!["e1".to_string(), "e3".to_string()]
        );
    }

    #[test]
    fn filter_virtual_episodes_keeps_virtual_episodes_when_setting_is_on() {
        let cards = vec![
            sample_card("e1", "Episode", false),
            sample_card("e2", "Episode", true),
        ];
        let filtered = filter_virtual_episodes(cards, true);
        assert_eq!(
            filtered.into_iter().map(|c| c.id).collect::<Vec<_>>(),
            vec!["e1".to_string(), "e2".to_string()]
        );
    }

    /// A virtual `Season` (Hazard 2b) must never be dropped by this filter.
    #[test]
    fn filter_virtual_episodes_never_drops_a_virtual_season() {
        let cards = vec![
            sample_card("season-1", "Season", true),
            sample_card("e1", "Episode", true),
        ];
        let filtered = filter_virtual_episodes(cards, false);
        assert_eq!(
            filtered.into_iter().map(|c| c.id).collect::<Vec<_>>(),
            vec!["season-1".to_string()]
        );
    }

    /// A season whose counts row says every episode is virtual must be hidden.
    #[test]
    fn filter_all_virtual_seasons_hides_a_fully_unaired_season() {
        let cards = vec![
            sample_card("season-1", "Season", false),
            sample_card("season-5", "Season", false),
        ];
        let counts = vec![
            ("season-1".to_string(), 10, 10),
            ("season-5".to_string(), 3, 0),
        ];
        let filtered = filter_all_virtual_seasons(cards, &counts, false);
        assert_eq!(
            filtered.into_iter().map(|c| c.id).collect::<Vec<_>>(),
            vec!["season-1".to_string()]
        );
    }

    /// Hazard 2b: a season with at least one real episode keeps showing
    /// even if the Season row itself is `is_virtual`.
    #[test]
    fn filter_all_virtual_seasons_keeps_a_mixed_season() {
        let cards = vec![sample_card("season-1", "Season", true)];
        let counts = vec![("season-1".to_string(), 10, 1)];
        let filtered = filter_all_virtual_seasons(cards, &counts, false);
        assert_eq!(
            filtered.into_iter().map(|c| c.id).collect::<Vec<_>>(),
            vec!["season-1".to_string()]
        );
    }

    /// FAIL OPEN: a season with zero episode rows synced yet must never be hidden.
    #[test]
    fn filter_all_virtual_seasons_keeps_a_season_with_no_episode_rows() {
        let cards = vec![sample_card("season-1", "Season", false)];
        let counts = vec![("season-1".to_string(), 0, 0)];
        let filtered = filter_all_virtual_seasons(cards, &counts, false);
        assert_eq!(
            filtered.into_iter().map(|c| c.id).collect::<Vec<_>>(),
            vec!["season-1".to_string()]
        );
    }

    /// A season entirely absent from `counts` must also fail open.
    #[test]
    fn filter_all_virtual_seasons_keeps_a_season_missing_from_counts() {
        let cards = vec![sample_card("season-1", "Season", false)];
        let filtered = filter_all_virtual_seasons(cards, &[], false);
        assert_eq!(
            filtered.into_iter().map(|c| c.id).collect::<Vec<_>>(),
            vec!["season-1".to_string()]
        );
    }

    /// When the setting is on, every season keeps showing regardless of counts.
    #[test]
    fn filter_all_virtual_seasons_keeps_everything_when_setting_is_on() {
        let cards = vec![
            sample_card("season-1", "Season", false),
            sample_card("season-5", "Season", false),
        ];
        let counts = vec![
            ("season-1".to_string(), 10, 10),
            ("season-5".to_string(), 3, 0),
        ];
        let filtered = filter_all_virtual_seasons(cards, &counts, true);
        assert_eq!(
            filtered.into_iter().map(|c| c.id).collect::<Vec<_>>(),
            vec!["season-1".to_string(), "season-5".to_string()]
        );
    }

    /// Non-`Season` cards pass through untouched.
    #[test]
    fn filter_all_virtual_seasons_ignores_non_season_cards() {
        let cards = vec![sample_card("movie-1", "Movie", false)];
        let counts = vec![("movie-1".to_string(), 3, 0)];
        let filtered = filter_all_virtual_seasons(cards, &counts, false);
        assert_eq!(
            filtered.into_iter().map(|c| c.id).collect::<Vec<_>>(),
            vec!["movie-1".to_string()]
        );
    }

    #[test]
    fn refuse_if_virtual_rejects_a_virtual_item() {
        let virtual_item = jellyfin_api::models::BaseItemDto {
            location_type: Some(jellyfin_api::models::LocationType::Virtual),
            ..Default::default()
        };
        let err = refuse_if_virtual("item-1", &virtual_item).expect_err("virtual item");
        assert!(matches!(err, CoreError::Cache { .. }));
        assert!(err.to_string().contains("virtual"), "got {err}");
    }

    #[test]
    fn refuse_if_virtual_allows_a_real_item() {
        let real_item = jellyfin_api::models::BaseItemDto {
            location_type: Some(jellyfin_api::models::LocationType::FileSystem),
            ..Default::default()
        };
        assert!(refuse_if_virtual("item-1", &real_item).is_ok());

        // No `LocationType` at all must not be treated as virtual either.
        let unspecified = jellyfin_api::models::BaseItemDto::default();
        assert!(refuse_if_virtual("item-2", &unspecified).is_ok());
    }

    #[test]
    fn host_from_server_url_strips_scheme_port_and_path() {
        assert_eq!(
            host_from_server_url("https://shelf.test:8920/jellyfin"),
            Some("shelf.test".to_string())
        );
        assert_eq!(
            host_from_server_url("http://shelf.test"),
            Some("shelf.test".to_string())
        );
        assert_eq!(
            host_from_server_url("http://shelf.test/"),
            Some("shelf.test".to_string())
        );
        assert_eq!(
            host_from_server_url("https://shelf.test:443/path?query=1#frag"),
            Some("shelf.test".to_string())
        );
    }

    #[test]
    fn canonicalize_item_id_unifies_compact_and_hyphenated_uuid_spellings() {
        let compact = "00112233445566778899aabbccddeeff";
        let hyphenated = "00112233-4455-6677-8899-aabbccddeeff";
        assert_eq!(canonicalize_item_id(compact), hyphenated);
        assert_eq!(canonicalize_item_id(hyphenated), hyphenated);
        assert_eq!(canonicalize_item_id("not-a-uuid"), "not-a-uuid");
    }

    #[test]
    fn host_from_server_url_drops_userinfo_if_present() {
        assert_eq!(
            host_from_server_url("http://user:pass@shelf.test:8096/"),
            Some("shelf.test".to_string())
        );
    }

    #[test]
    fn host_from_server_url_tolerates_a_schemeless_or_bare_host() {
        assert_eq!(
            host_from_server_url("shelf.test:8096"),
            Some("shelf.test".to_string())
        );
        assert_eq!(
            host_from_server_url("shelf.test"),
            Some("shelf.test".to_string())
        );
    }

    #[test]
    fn host_from_server_url_returns_none_for_a_host_less_string() {
        assert_eq!(host_from_server_url(""), None);
        assert_eq!(host_from_server_url("http://"), None);
    }

    #[test]
    fn build_android_profile_round_trips_the_conservative_floor() {
        // Same shape assertion as jellyfin-core's own device-profile test,
        // against `android_tv_profile`'s output.
        let profile = build_android_profile(&jellyfin_core::AndroidTvCaps::default(), true, false);
        assert_eq!(profile.name.as_deref(), Some("Jellybeam TV"));
        assert!(!profile.direct_play_profiles.is_empty());
        assert!(!profile.transcoding_profiles.is_empty());
        assert!(!profile.subtitle_profiles.is_empty());
    }

    #[test]
    fn build_android_profile_for_quality_caps_streaming_bitrate_only_in_cap_mode() {
        let caps = jellyfin_core::AndroidTvCaps::default();

        let direct_play =
            build_android_profile_for_quality(&caps, true, &PlaybackQuality::DirectPlay, true);
        let auto = build_android_profile_for_quality(&caps, true, &PlaybackQuality::Auto, true);
        let cap = build_android_profile_for_quality(
            &caps,
            true,
            &PlaybackQuality::Cap { max_bps: 8_000_000 },
            false,
        );

        assert_eq!(
            direct_play.max_streaming_bitrate,
            auto.max_streaming_bitrate
        );
        assert_ne!(
            cap.max_streaming_bitrate, direct_play.max_streaming_bitrate,
            "Cap mode must thread its own max_bps onto the profile"
        );
        assert!(
            !cap.codec_profiles.is_empty(),
            "a bitrate cap should add the VideoBitrate CodecProfile condition"
        );
    }

    /// docs/18 §3.2: ASS sidecars are offered only to a negotiation that may answer Direct Play;
    /// in a transcode the server would hand out embedded ASS as a sidecar instead of burning it in.
    #[test]
    fn ass_sidecars_are_offered_only_where_direct_play_may_answer() {
        let caps = jellyfin_core::AndroidTvCaps::default();
        let external_ass = |quality: PlaybackQuality, may_direct_play| {
            build_android_profile_for_quality(&caps, true, &quality, may_direct_play)
                .subtitle_profiles
                .iter()
                .any(|p| {
                    p.format.as_deref() == Some("ass")
                        && p.method == Some(jellyfin_api::models::SubtitleDeliveryMethod::External)
                })
        };
        assert!(external_ass(PlaybackQuality::DirectPlay, true));
        assert!(external_ass(PlaybackQuality::Auto, true));
        assert!(!external_ass(PlaybackQuality::Auto, false));
        assert!(!external_ass(
            PlaybackQuality::Cap { max_bps: 8_000_000 },
            true
        ));
    }

    /// Whether a recorded `PlaybackInfo` body offered ASS sidecars (docs/18 §3.2).
    fn offers_ass_sidecars(body: &serde_json::Value) -> bool {
        body["DeviceProfile"]["SubtitleProfiles"]
            .as_array()
            .expect("subtitle profiles")
            .iter()
            .any(|p| p["Format"] == "ass" && p["Method"] == "External")
    }

    /// docs/18 §3.2: Auto offers ASS sidecars so an .ass file never forces a transcode; when the
    /// answer is a transcode anyway, it asks again without them so embedded ASS is still burned in.
    #[test]
    fn auto_mode_asks_a_transcode_again_without_ass_sidecars() {
        let (_dir, core, mock) = auto_core(vec![transcode_fixture().1]);
        let plan = core
            .prepare_playback_now("item-1".to_string(), false)
            .expect("Auto transcodes a source the TV can't decode");
        assert_eq!(plan.play_method, PlayMethodFfi::Transcode);
        let bodies = mock.playback_info_bodies();
        assert_eq!(bodies.len(), 2);
        assert!(offers_ass_sidecars(&bodies[0]));
        assert!(!offers_ass_sidecars(&bodies[1]));
        assert!(
            bodies[1].get("EnableDirectPlay").is_none(),
            "still the server's own choice"
        );
    }

    #[test]
    fn auto_mode_direct_play_asks_once_with_ass_sidecars() {
        let (_dir, core, mock) = auto_core(vec![direct_play_fixture().1]);
        let plan = core
            .prepare_playback_now("item-1".to_string(), false)
            .expect("direct play");
        assert_eq!(plan.play_method, PlayMethodFfi::DirectPlay);
        let bodies = mock.playback_info_bodies();
        assert_eq!(bodies.len(), 1);
        assert!(offers_ass_sidecars(&bodies[0]));
    }

    // --- resolve_plan (docs/18-playback-quality.md §2 matrix); `Cap` mode
    // has no rows here -- see the prepare_playback/preload_playback tests. ---

    fn media_source(
        id: &str,
        transcoding_url: Option<&str>,
        video_codec: Option<&str>,
    ) -> jellyfin_api::models::MediaSourceInfo {
        jellyfin_api::models::MediaSourceInfo {
            id: Some(id.to_string()),
            transcoding_url: transcoding_url.map(|s| s.to_string()),
            media_streams: video_codec
                .map(|codec| {
                    vec![jellyfin_api::models::MediaStream {
                        type_: Some(jellyfin_api::models::MediaStreamType::Video),
                        codec: Some(codec.to_string()),
                        ..Default::default()
                    }]
                })
                .unwrap_or_default(),
            ..Default::default()
        }
    }

    fn direct_play_decision() -> jellyfin_core::PlaybackDecision {
        jellyfin_core::PlaybackDecision::DirectPlay {
            source: media_source("ms-1", None, Some("h264")),
            url: "http://example.test/Videos/item-1/stream".to_string(),
        }
    }

    /// `video_codec` is the chosen source's own codec fact; `None` mirrors
    /// a codec-blind source.
    fn transcode_decision(video_codec: Option<&str>) -> jellyfin_core::PlaybackDecision {
        jellyfin_core::PlaybackDecision::Transcode {
            source: media_source("ms-1", Some("/videos/ms-1/master.m3u8"), video_codec),
            hls_url: "http://example.test/videos/ms-1/master.m3u8".to_string(),
            direct_url: "http://example.test/Videos/item-1/stream".to_string(),
            reasons: vec!["container 'wmv' requires transcoding".to_string()],
        }
    }

    /// Row 1: a `DirectPlay` decision is a `DirectPlay` plan with no
    /// verdict, regardless of mode.
    #[test]
    fn resolve_plan_direct_play_decision_is_always_direct_play_with_no_verdict() {
        for quality in [PlaybackQuality::DirectPlay, PlaybackQuality::Auto] {
            match resolve_plan(&quality, direct_play_decision(), Some("h264"), &[], true) {
                Ok(ResolvedPlan::DirectPlay {
                    url,
                    server_verdict,
                    ..
                }) => {
                    assert_eq!(url, "http://example.test/Videos/item-1/stream");
                    assert!(server_verdict.is_none());
                }
                other => panic!("expected Ok(DirectPlay) for {quality:?}, got {other:?}"),
            }
        }
    }

    /// docs/18 §1's pre-feature refusal: `DirectPlay` mode never turns a
    /// `Transcode` decision into a plan of either kind.
    #[test]
    fn resolve_plan_transcode_decision_under_direct_play_mode_errors() {
        let err = resolve_plan(
            &PlaybackQuality::DirectPlay,
            transcode_decision(Some("wmv3")),
            Some("wmv3"),
            &[],
            true,
        )
        .expect_err("Direct Play mode must refuse a Transcode decision outright");
        assert_eq!(err, "container 'wmv' requires transcoding");
    }

    /// Auto + `Transcode` with an unsupported video codec -> real Transcode
    /// plan on the decision's own `hls_url`, "no <codec> decoder" reason.
    #[test]
    fn resolve_plan_auto_mode_trusts_a_transcode_verdict_when_tolerate_mislabeled_levels_is_off() {
        // With the tolerate setting off, even a locally-supported codec
        // (hevc) transcodes up front on the server's own reasons.
        let supported = [jellyfin_core::VideoCodec::Hevc];
        match resolve_plan(
            &PlaybackQuality::Auto,
            transcode_decision(Some("hevc")),
            Some("hevc"),
            &supported,
            false,
        ) {
            Ok(ResolvedPlan::Transcode { url, reason, .. }) => {
                assert_eq!(url, "http://example.test/videos/ms-1/master.m3u8");
                assert_eq!(reason, "container 'wmv' requires transcoding");
            }
            other => panic!("expected Ok(Transcode), got {other:?}"),
        }
    }

    #[test]
    fn resolve_plan_auto_mode_transcode_decision_with_an_unsupported_codec_transcodes() {
        let supported = [
            jellyfin_core::VideoCodec::H264,
            jellyfin_core::VideoCodec::Hevc,
        ];
        match resolve_plan(
            &PlaybackQuality::Auto,
            transcode_decision(Some("mpeg4")),
            Some("mpeg4"),
            &supported,
            true,
        ) {
            Ok(ResolvedPlan::Transcode { url, reason, .. }) => {
                assert_eq!(url, "http://example.test/videos/ms-1/master.m3u8");
                assert_eq!(reason, "no mpeg4 decoder on this TV");
            }
            other => panic!("expected Ok(Transcode), got {other:?}"),
        }
    }

    /// Auto + `Transcode` with a supported video codec -> attempt-anyway
    /// DirectPlay plan (docs/18 §1's "never a reason to transcode").
    #[test]
    fn resolve_plan_auto_mode_transcode_decision_with_a_supported_codec_attempts_direct_play() {
        let supported = [jellyfin_core::VideoCodec::Hevc];
        match resolve_plan(
            &PlaybackQuality::Auto,
            transcode_decision(Some("hevc")),
            Some("hevc"),
            &supported,
            true,
        ) {
            Ok(ResolvedPlan::DirectPlay {
                url,
                server_verdict,
                ..
            }) => {
                assert_eq!(url, "http://example.test/Videos/item-1/stream");
                assert_eq!(
                    server_verdict.as_deref(),
                    Some("container 'wmv' requires transcoding")
                );
            }
            other => panic!("expected an attempt-anyway Ok(DirectPlay), got {other:?}"),
        }
    }

    /// Auto + a codec-blind `Transcode` decision -> same attempt-anyway
    /// plan as a known-supported codec.
    #[test]
    fn resolve_plan_auto_mode_codec_blind_transcode_decision_attempts_direct_play() {
        match resolve_plan(
            &PlaybackQuality::Auto,
            transcode_decision(None),
            None,
            &[],
            true,
        ) {
            Ok(ResolvedPlan::DirectPlay { server_verdict, .. }) => {
                assert!(server_verdict.is_some());
            }
            other => panic!("expected an attempt-anyway Ok(DirectPlay), got {other:?}"),
        }
    }

    #[test]
    fn removing_the_active_account_deletes_its_saved_session() {
        let (dir, core) = core_in_tempdir();
        let saved = session::SessionFile {
            server_url: "http://example.invalid".to_string(),
            user_id: "u1".to_string(),
            user_name: "jellybeam-user".to_string(),
            token: "tok".to_string(),
            device_id: "dev".to_string(),
            mirror_dir: "mirror".to_string(),
            server_version: None,
            server_name: None,
        };
        session::add_or_update(dir.path(), saved).expect("seed a session file");
        assert!(core.restore_session().is_some());

        remove_active_account(&core);

        assert!(session::load(dir.path()).is_none());
        // A fresh restore attempt now finds nothing (state cleared too).
        assert!(core.restore_session().is_none());
    }

    /// Reproduces a version refresh's persist step running after a
    /// removal already removed the account: the removed account must stay
    /// removed, since `persist_server_version` re-reads under `session_lock`.
    #[test]
    fn persist_server_version_after_removing_the_active_account_writes_nothing() {
        let (dir, core) = core_in_tempdir();
        let saved = session::SessionFile {
            server_url: "http://example.invalid".to_string(),
            user_id: "u1".to_string(),
            user_name: "jellybeam-user".to_string(),
            token: "tok".to_string(),
            device_id: "dev".to_string(),
            mirror_dir: "mirror".to_string(),
            server_version: Some("10.11.10".to_string()),
            server_name: None,
        };
        session::add_or_update(dir.path(), saved).expect("seed a session file");
        assert!(core.restore_session().is_some());

        // The removal completes first; only
        // afterward does the stale refresh's persist step run.
        remove_active_account(&core);
        core.persist_server_version("http://example.invalid", "u1", "12.0.0");

        assert!(
            session::load(dir.path()).is_none(),
            "persist_server_version must not resurrect a removed account"
        );
    }

    /// Same interleaving as the active-account removal test, against `remove_session` of
    /// a non-active account instead.
    #[test]
    fn persist_server_version_after_remove_session_writes_nothing_for_the_removed_account() {
        let (dir, core) = core_in_tempdir();
        seed_session(dir.path(), "http://server-a.test", "u1");
        seed_session(dir.path(), "http://server-b.test", "u2");
        assert_eq!(core.active_account_index(), Some(1));

        assert!(!core.remove_session(0).expect("index 0 exists"));
        core.persist_server_version("http://server-a.test", "u1", "12.0.0");

        assert!(
            session::load_list(dir.path())
                .sessions
                .iter()
                .all(|s| s.server_url != "http://server-a.test"),
            "persist_server_version must not resurrect a row remove_session already deleted"
        );
    }

    /// docs/18 §2.1: a trailing slash on the saved address never splits an account from the
    /// trimmed address its client and its rejected tokens carry.
    #[test]
    fn a_saved_trailing_slash_still_names_its_account() {
        let (dir, core) = core_in_tempdir();
        seed_session(dir.path(), "http://server-a.test/jf/", "u1");
        let trimmed = AccountIdentity {
            server_url: "http://server-a.test/jf".to_string(),
            user_id: "u1".to_string(),
        };

        let account = core.reauthorization_account(trimmed, true);
        assert_eq!(
            account.map(|account| account.user_id),
            Some("u1".to_string()),
            "a 401 naming the trimmed owner resolves to the saved row"
        );

        core.persist_server_version("http://server-a.test/jf", "u1", "12.0.0");
        assert_eq!(
            session::load_list(dir.path()).sessions[0]
                .server_version
                .as_deref(),
            Some("12.0.0"),
            "the version lands on the saved row"
        );
    }

    /// Removing the active account drops its server version with it; both getters fail closed
    /// once no account is installed.
    #[test]
    fn removing_the_active_account_clears_the_cached_server_version() {
        let (dir, core) = core_in_tempdir();
        let saved = session::SessionFile {
            server_url: "http://server-a.test".to_string(),
            user_id: "u1".to_string(),
            user_name: "jellybeam-user".to_string(),
            token: "tok".to_string(),
            device_id: "dev".to_string(),
            mirror_dir: "mirror".to_string(),
            server_version: Some("12.0.0".to_string()),
            server_name: None,
        };
        session::add_or_update(dir.path(), saved).expect("seed a session file");
        assert!(core.restore_session().is_some());
        assert!(core.server_at_least(12, 0));
        assert_eq!(core.server_version(), Some("12.0.0".to_string()));

        remove_active_account(&core);

        assert_eq!(core.server_version(), None);
        assert!(!core.server_at_least(12, 0));
        assert!(!core.server_at_least(0, 0));

        // Nothing left to restore -- still fails closed afterward.
        assert!(core.restore_session().is_none());
        assert!(!core.server_at_least(12, 0));
    }

    /// Same belt-and-suspenders check for `remove_session`'s active-account branch.
    #[test]
    fn remove_session_of_active_account_clears_the_cached_server_version() {
        let (dir, core) = core_in_tempdir();
        let mut saved = seed_session(dir.path(), "http://server-a.test", "u1");
        saved.server_version = Some("12.0.0".to_string());
        session::add_or_update(dir.path(), saved).expect("update with a known server version");
        assert!(core.restore_session().is_some());
        assert!(core.server_at_least(12, 0));

        let removed_active = core.remove_session(0).expect("index 0 exists");

        assert!(removed_active);
        assert_eq!(core.server_version(), None);
        assert!(!core.server_at_least(12, 0));
    }

    /// Test-only helper: seed an on-disk session with a real hashed
    /// `mirror_dir`, the shape a real `sign_in` would leave behind.
    fn seed_session(
        dir: &std::path::Path,
        server_url: &str,
        user_id: &str,
    ) -> session::SessionFile {
        let session = session::SessionFile {
            server_url: server_url.to_string(),
            user_id: user_id.to_string(),
            user_name: format!("{user_id}-name"),
            token: "tok".to_string(),
            device_id: "dev".to_string(),
            mirror_dir: session::mirror_dir_name(server_url, user_id),
            server_version: None,
            server_name: None,
        };
        session::add_or_update(dir, session).expect("seed a session file")
    }

    #[test]
    fn list_accounts_and_active_index_reflect_every_signed_in_account() {
        let (dir, core) = core_in_tempdir();
        assert!(core.list_accounts().is_empty());
        assert!(core.active_account_index().is_none());

        seed_session(dir.path(), "http://server-a.test", "u1");
        seed_session(dir.path(), "http://server-b.test", "u2");

        let accounts = core.list_accounts();
        assert_eq!(accounts.len(), 2);
        assert_eq!(accounts[0].server_url, "http://server-a.test");
        assert_eq!(accounts[1].server_url, "http://server-b.test");
        // No token field exists on `AccountInfo` at all -- structurally
        // impossible to leak.

        // The most recently added session is active, same as add_or_update.
        assert_eq!(core.active_account_index(), Some(1));
    }

    #[test]
    fn list_accounts_carries_each_saved_server_name_verbatim() {
        let (dir, core) = core_in_tempdir();
        seed_session(dir.path(), "http://server-a.test", "u1");
        seed_session(dir.path(), "http://server-b.test", "u2");
        session::mutate(dir.path(), &Mutex::new(()), |sessions| {
            sessions.sessions[1].server_name = Some(" Ünïcode Server ".to_string());
            Ok::<_, CoreError>(())
        })
        .expect("name the second server");

        let accounts = core.list_accounts();
        assert_eq!(accounts[0].server_name, None);
        assert_eq!(accounts[1].server_name.as_deref(), Some(" Ünïcode Server "));
    }

    #[test]
    fn switch_session_persists_active_and_swaps_client_state_without_network() {
        let (dir, core) = core_in_tempdir();
        seed_session(dir.path(), "http://server-a.test", "u1");
        seed_session(dir.path(), "http://server-b.test", "u2");
        assert_eq!(core.active_account_index(), Some(1));

        let account = core.switch_session(0).expect("index 0 exists");
        assert_eq!(account.server_url, "http://server-a.test");
        assert_eq!(account.user_id, "u1");

        // Persisted immediately -- a fresh disk read sees the new active index.
        assert_eq!(session::load_list(dir.path()).active, 0);
        assert_eq!(core.active_account_index(), Some(0));

        // A client was installed with no network round trip: `open_mirror`
        // no longer sees `NotSignedIn`, and opens account 0's mirror directory.
        core.open_mirror()
            .expect("client installed synchronously by switch_session");
        let expected_mirror_db = dir
            .path()
            .join(session::mirror_dir_name("http://server-a.test", "u1"))
            .join("mirror.db");
        assert!(
            expected_mirror_db.exists(),
            "open_mirror should have used account 0's mirror directory, not account 1's"
        );
    }

    #[test]
    fn switch_session_out_of_range_index_is_rejected() {
        let (dir, core) = core_in_tempdir();
        seed_session(dir.path(), "http://server-a.test", "u1");

        let err = core.switch_session(5).expect_err("only index 0 exists");
        assert!(matches!(err, CoreError::InvalidSessionIndex { index: 5 }));

        // Rejecting an out-of-range switch must not disturb the active index.
        assert_eq!(core.active_account_index(), Some(0));
    }

    // --- websocket EventBus wiring. Mocks have
    // no websocket support, so `connect_ws()` always fails/backs off
    // harmlessly; assertions below are about `BusBundle` bookkeeping, never
    // a real connection; tests assert the bundle's own `tx.receiver_count()`. ---

    #[test]
    fn open_mirror_spawns_a_bus_and_removing_the_account_stops_it() {
        let (dir, core) = core_in_tempdir();
        seed_session(dir.path(), "http://server-a.test", "u1");
        assert!(core.restore_session().is_some());

        core.open_mirror()
            .expect("Mirror::open only does local SQLite setup synchronously");

        assert_eq!(
            core.lock_state()
                .account
                .bundle()
                .expect("open_mirror should have spawned a real EventBus handle")
                .tx
                .receiver_count(),
            1,
            "the mirror's own subscription should be the bundle's only receiver"
        );
        let forwarder_handle = core
            .lock_state()
            .account
            .bundle()
            .expect("open_mirror should have spawned a real EventBus handle")
            .forwarder
            .as_ref()
            .expect("open_mirror should have spawned a bus forwarder")
            .abort_handle();

        remove_active_account(&core);

        assert!(core.lock_state().account.bundle().is_none());
        assert!(
            forwarder_handle.is_finished(),
            "removal must actually stop the bus forwarder task, not just clear the handle \
             -- otherwise its unbounded reconnect loop keeps running in the background"
        );
        // Not asserting the bundle's `tx.receiver_count()` back to 0: the
        // mirror's internal `bus_listener` (media-cache) only re-checks its
        // `Weak` when the next event arrives, and none does here -- a
        // pre-existing media-cache property, out of scope (CLAUDE.md).
    }

    #[test]
    fn open_mirror_reentry_stops_the_previous_bus_without_stacking() {
        let (dir, core) = core_in_tempdir();
        seed_session(dir.path(), "http://server-a.test", "u1");
        assert!(core.restore_session().is_some());

        core.open_mirror().expect("first open_mirror");
        let first_forwarder = core
            .lock_state()
            .account
            .bundle()
            .expect("first open_mirror should have spawned a bundle")
            .forwarder
            .as_ref()
            .expect("first open_mirror should have spawned a forwarder")
            .abort_handle();

        // Re-entry: a second `open_mirror` call against the same client.
        core.open_mirror().expect("second open_mirror (re-entry)");

        assert!(
            first_forwarder.is_finished(),
            "re-entry must stop the previous bus's forwarder rather than leaking a second, \
             unbounded reconnect loop alongside the new one"
        );
        let state = core.lock_state();
        let fresh_bundle = state
            .account
            .bundle()
            .expect("re-entry should install a fresh bus");
        assert!(
            fresh_bundle.forwarder.is_some(),
            "re-entry should install a fresh forwarder"
        );
        assert_eq!(
            fresh_bundle.tx.receiver_count(),
            1,
            "the fresh bundle should have exactly one receiver -- its own mirror, not a \
             leftover from the previous bundle (which has its own, separate tx)"
        );
    }

    /// Whether `connection`'s bus has already ended, with no wait: its supervisor, the watch's
    /// only sender, is gone (docs/18 §2.1: stopped before the call that took it returns).
    fn bus_has_stopped(
        connection: &tokio::sync::watch::Receiver<jellyfin_core::BusConnection>,
    ) -> bool {
        connection.has_changed().is_err()
    }

    /// docs/18 §2.1 (R1): an open that spans a switch installs nothing for the new account and
    /// stops its own bus; the new account's open then opens its own directory.
    #[test]
    fn an_open_mirror_that_spans_a_switch_installs_nothing_for_the_new_account() {
        let (dir, core) = core_in_tempdir();
        seed_session(dir.path(), "http://server-a.test", "u1");
        let b = seed_session(dir.path(), "http://server-b.test", "u2");
        core.switch_session(0).expect("A is installed");
        let (epoch, client, mirror_dir) = core.open_begin().expect("A's open begins");
        let built = core
            .build_bundle(&client, mirror_dir)
            .expect("A's mirror builds");
        let spanned = built.0.connection.clone();
        core.switch_session(1).expect("B is installed meanwhile");

        let result = core.open_finish(epoch, &client, built);

        assert!(
            matches!(result, Err(CoreError::AccountChanged)),
            "{result:?}"
        );
        assert!(core.views().is_empty(), "B shows no views");
        assert!(core.require_mirror().is_err(), "B has no mirror");
        assert!(core.server_info_snapshot().mirror.is_none());
        assert!(
            bus_has_stopped(&spanned),
            "the spanning open's bus is stopped"
        );
        core.open_mirror().expect("B opens");
        assert_eq!(installed_mirror_dir(&core), b.mirror_dir);
        assert!(dir.path().join(&b.mirror_dir).join("mirror.db").exists());
    }

    /// docs/18 §2.1 (R2): of two interleaved opens of one account the first commit stays
    /// installed, and the second succeeds with its bus stopped.
    #[test]
    fn two_open_mirrors_for_one_account_leave_exactly_one_bus() {
        let (dir, core) = core_in_tempdir();
        seed_session(dir.path(), "http://server-a.test", "u1");
        assert!(core.restore_session().is_some());
        let (first_epoch, first_client, first_dir) = core.open_begin().expect("first begins");
        let first = core
            .build_bundle(&first_client, first_dir)
            .expect("first builds");
        let (second_epoch, second_client, second_dir) = core.open_begin().expect("second begins");
        let second = core
            .build_bundle(&second_client, second_dir)
            .expect("second builds");
        let first_connection = first.0.connection.clone();
        let second_connection = second.0.connection.clone();

        core.open_finish(first_epoch, &first_client, first)
            .expect("the first commits");
        core.open_finish(second_epoch, &second_client, second)
            .expect("the open that lost still succeeds");

        assert!(
            installed_bus_connection(&core).same_channel(&first_connection),
            "the first commit stays installed"
        );
        assert!(
            bus_has_stopped(&second_connection),
            "the losing open's bus is stopped"
        );
        assert!(
            !bus_has_stopped(&first_connection),
            "the installed bus still runs"
        );
    }

    #[test]
    fn switch_session_stops_the_outgoing_accounts_bus() {
        let (dir, core) = core_in_tempdir();
        seed_session(dir.path(), "http://server-a.test", "u1");
        seed_session(dir.path(), "http://server-b.test", "u2");
        assert_eq!(core.active_account_index(), Some(1));

        core.switch_session(0).expect("index 0 exists");
        core.open_mirror().expect("open account 0's mirror");
        assert!(core.lock_state().account.bundle().is_some());
        let forwarder = core
            .lock_state()
            .account
            .bundle()
            .expect("open_mirror should have spawned a bundle")
            .forwarder
            .as_ref()
            .expect("open_mirror should have spawned a forwarder")
            .abort_handle();

        core.switch_session(1).expect("index 1 exists");

        assert!(
            forwarder.is_finished(),
            "switch_session must stop the outgoing account's bus forwarder"
        );
        assert!(
            core.lock_state().account.bundle().is_none(),
            "switch_session doesn't itself open a new mirror/bus -- the caller re-runs \
             open_mirror, same as it does for the mirror/listener task"
        );
    }

    #[test]
    fn remove_session_of_active_account_stops_its_bus() {
        let (dir, core) = core_in_tempdir();
        seed_session(dir.path(), "http://server-a.test", "u1");
        let active = seed_session(dir.path(), "http://server-b.test", "u2");
        let active_dir = dir.path().join(&active.mirror_dir);
        std::fs::create_dir_all(&active_dir).expect("active mirror dir");
        assert!(core.restore_session().is_some());
        core.open_mirror()
            .expect("open the active account's mirror");
        assert!(core.lock_state().account.bundle().is_some());
        let forwarder = core
            .lock_state()
            .account
            .bundle()
            .expect("open_mirror should have spawned a bundle")
            .forwarder
            .as_ref()
            .expect("open_mirror should have spawned a forwarder")
            .abort_handle();

        let removed_active = core.remove_session(1).expect("active index exists");

        assert!(removed_active);
        assert!(
            forwarder.is_finished(),
            "remove_session of the active account must stop its bus forwarder"
        );
        assert!(core.lock_state().account.bundle().is_none());
    }

    /// docs/13 Server compatibility: the forwarder relays every bus event, `Server` payloads
    /// included, onto its own bundle in order, and keeps relaying after the supervisor's watch ends.
    #[test]
    fn bus_forwarder_relays_every_event_to_its_bundle_until_the_bus_closes() {
        use jellyfin_core::BusEvent;
        let (_dir, core) = core_in_tempdir();
        let (real_tx, real_rx) = tokio::sync::broadcast::channel(16);
        let (bundle_tx, mut bundle_rx) = tokio::sync::broadcast::channel(16);
        let (connection_tx, connection) =
            tokio::sync::watch::channel(jellyfin_core::BusConnection::Disconnected);
        let client = jellyfin_api::JellyfinClient::from_token(
            "http://server-a.test",
            JellybeamCore::identity_with("dev".to_string()),
            "tok",
        );
        let epoch = core.account_epoch();
        let forwarder = core.spawn_bus_forwarder(real_rx, bundle_tx, connection, client, epoch);

        let user_data_event = BusEvent::Server(jellyfin_api::ServerEvent::UserDataChanged {
            item_userdata: vec![],
        });
        let library_event = BusEvent::Server(jellyfin_api::ServerEvent::LibraryChanged {
            added: vec![],
            updated: vec![],
            removed: vec![],
        });
        for event in [user_data_event, library_event, BusEvent::NeedsReconcile] {
            real_tx.send(event).expect("send to a fresh channel");
        }
        let mut next = || {
            core.runtime.block_on(async {
                tokio::time::timeout(Duration::from_secs(2), bundle_rx.recv())
                    .await
                    .expect("the forwarder relays the event")
                    .expect("bundle open")
            })
        };
        assert!(matches!(
            next(),
            BusEvent::Server(jellyfin_api::ServerEvent::UserDataChanged { .. })
        ));
        assert!(matches!(
            next(),
            BusEvent::Server(jellyfin_api::ServerEvent::LibraryChanged { .. })
        ));
        assert!(matches!(next(), BusEvent::NeedsReconcile));

        drop(connection_tx);
        real_tx.send(BusEvent::NeedsReconcile).expect("send");
        assert!(
            matches!(next(), BusEvent::NeedsReconcile),
            "a closed connection watch leaves the relay running"
        );

        drop(real_tx);
        core.runtime
            .block_on(forwarder)
            .expect("the forwarder exits once its real bus closes");
    }

    fn authentication_result(
        token: &str,
        user_name: &str,
    ) -> jellyfin_api::models::AuthenticationResult {
        jellyfin_api::models::AuthenticationResult {
            access_token: Some(token.to_string()),
            user: Some(jellyfin_api::models::UserDto {
                name: Some(user_name.to_string()),
                ..Default::default()
            }),
            ..Default::default()
        }
    }

    #[test]
    fn reauthorization_replaces_token_and_preserves_existing_mirror() {
        let (dir, core) = core_in_tempdir();
        let target = seed_session(dir.path(), "http://server-a.test", "u1");
        let original_mirror_dir = target.mirror_dir.clone();
        let client = jellyfin_api::JellyfinClient::from_token(
            &target.server_url,
            core.client_identity("http://example.test"),
            "new-token",
        )
        .with_user_id("u1");

        let account = core
            .install_reauthenticated_session(
                0,
                target,
                client,
                authentication_result("new-token", "renamed-user"),
            )
            .expect("same Jellyfin user may refresh its credential");

        let saved = session::load_list(dir.path());
        assert_eq!(saved.sessions.len(), 1);
        assert_eq!(saved.sessions[0].token, "new-token");
        assert_eq!(saved.sessions[0].mirror_dir, original_mirror_dir);
        assert_eq!(saved.sessions[0].user_name, "renamed-user");
        assert_eq!(account.user_id, "u1");
    }

    #[test]
    fn reauthorization_rejects_a_different_user_without_mutating_session() {
        let (dir, core) = core_in_tempdir();
        let target = seed_session(dir.path(), "http://server-a.test", "u1");
        let before = session::load_list(dir.path()).sessions[0].clone();
        let client = jellyfin_api::JellyfinClient::from_token(
            &target.server_url,
            core.client_identity("http://example.test"),
            "wrong-user-token",
        )
        .with_user_id("u2");

        let error = core
            .install_reauthenticated_session(
                0,
                target,
                client,
                authentication_result("wrong-user-token", "other-user"),
            )
            .expect_err("reauthorization must remain bound to the selected account");

        assert!(matches!(error, CoreError::Api { .. }));
        assert_eq!(session::load_list(dir.path()).sessions[0], before);
    }

    #[test]
    fn remove_session_deletes_a_non_active_account_and_its_mirror_only() {
        let (dir, core) = core_in_tempdir();
        let removed = seed_session(dir.path(), "http://server-a.test", "u1");
        let kept = seed_session(dir.path(), "http://server-b.test", "u2");
        let removed_dir = dir.path().join(&removed.mirror_dir);
        let kept_dir = dir.path().join(&kept.mirror_dir);
        std::fs::create_dir_all(&removed_dir).expect("removed mirror dir");
        std::fs::create_dir_all(&kept_dir).expect("kept mirror dir");

        let removed_active = core.remove_session(0).expect("index 0 exists");

        assert!(!removed_active);
        assert!(!removed_dir.exists());
        assert!(kept_dir.exists());
        assert_eq!(core.list_accounts().len(), 1);
        assert_eq!(core.list_accounts()[0].user_id, "u2");
        assert_eq!(core.active_account_index(), Some(0));
    }

    #[test]
    fn remove_session_of_active_account_clears_live_state_and_selects_a_fallback() {
        let (dir, core) = core_in_tempdir();
        seed_session(dir.path(), "http://server-a.test", "u1");
        let active = seed_session(dir.path(), "http://server-b.test", "u2");
        let active_dir = dir.path().join(&active.mirror_dir);
        std::fs::create_dir_all(&active_dir).expect("active mirror dir");
        assert_eq!(core.restore_session().expect("just restored").user_id, "u2");

        let removed_active = core.remove_session(1).expect("active index exists");

        assert!(removed_active);
        assert!(!active_dir.exists());
        assert_eq!(
            core.current_account()
                .expect("fallback account exists")
                .user_id,
            "u1"
        );
        assert_eq!(
            installed_user(&core).as_deref(),
            Some("u1"),
            "the fallback is installed"
        );
    }

    #[test]
    fn remove_session_rejects_a_stale_index_without_changing_disk() {
        let (dir, core) = core_in_tempdir();
        seed_session(dir.path(), "http://server-a.test", "u1");

        let error = core.remove_session(4).expect_err("only index 0 exists");

        assert!(matches!(error, CoreError::InvalidSessionIndex { index: 4 }));
        assert_eq!(core.list_accounts().len(), 1);
        assert_eq!(core.active_account_index(), Some(0));
    }

    /// docs/18 §2.1: removing the active account installs the next saved one in the same change, so
    /// the app's relaunch restore finds it installed and the epoch moves once, not twice.
    #[test]
    fn removing_the_active_account_installs_the_next_one_in_the_same_change() {
        let (dir, core) = core_in_tempdir();
        seed_session(dir.path(), "http://server-a.test", "u1");
        seed_session(dir.path(), "http://server-b.test", "u2");
        assert!(
            core.restore_session().is_some(),
            "u2 (active) restores fine"
        );

        remove_active_account(&core);

        // u2 (previously active) is gone; u1 remains and is now active.
        let accounts = core.list_accounts();
        assert_eq!(accounts.len(), 1);
        assert_eq!(accounts[0].user_id, "u1");
        assert_eq!(core.active_account_index(), Some(0));

        // current_account (a pure disk read) reports the new active account...
        let current = core.current_account().expect("u1 is still on disk");
        assert_eq!(current.user_id, "u1");

        // ...and it is already installed: the relaunch restore is not a second account change.
        let epoch = core.account_epoch();
        assert_eq!(core.restore_session().expect("u1").user_id, "u1");
        assert_eq!(core.account_epoch(), epoch);
    }

    #[test]
    fn removing_the_only_account_empties_the_list() {
        let (dir, core) = core_in_tempdir();
        seed_session(dir.path(), "http://server-a.test", "u1");
        remove_active_account(&core);
        assert!(core.list_accounts().is_empty());
        assert!(core.active_account_index().is_none());
        assert!(!session::load_list(dir.path())
            .sessions
            .iter()
            .any(|s| s.user_id == "u1"));
    }

    #[test]
    fn each_account_gets_its_own_mirror_directory() {
        let (dir, _core) = core_in_tempdir();
        let a = seed_session(dir.path(), "http://server-a.test", "u1");
        let b = seed_session(dir.path(), "http://server-b.test", "u2");
        assert_ne!(
            a.mirror_dir, b.mirror_dir,
            "two different (server, user) sessions must never share a mirror directory"
        );
    }

    #[test]
    fn get_settings_before_anything_is_saved_returns_defaults() {
        let (_dir, core) = core_in_tempdir();
        assert_eq!(core.get_settings(), Settings::default());
    }

    #[test]
    fn set_settings_persists_to_disk_and_updates_in_memory_state() {
        let (dir, core) = core_in_tempdir();
        let new_settings = Settings {
            hidden_library_ids: vec!["lib-1".to_string()],
            hide_watched_in_latest: true,
            next_up_cutoff_days: Some(14),
            ..Settings::default()
        };

        core.set_settings(new_settings.clone());

        assert_eq!(core.get_settings(), new_settings);
        assert_eq!(settings::load(dir.path()), new_settings);
    }

    #[test]
    fn a_fresh_core_loads_settings_previously_saved_to_the_same_data_dir() {
        let dir = tempfile::tempdir().expect("tempdir");
        let saved = Settings {
            autoplay_enabled: false,
            skip_back_secs: 30,
            ..Settings::default()
        };
        settings::save(dir.path(), &saved).expect("seed a settings file");

        let core = JellybeamCore::new(dir.path().to_string_lossy().to_string());
        assert_eq!(core.get_settings(), saved);
    }

    #[test]
    fn set_settings_without_an_open_mirror_does_not_panic() {
        let (_dir, core) = core_in_tempdir();
        // No `open_mirror` call was made -- pushing Next Up options to a
        // nonexistent mirror must be a silent no-op.
        let new_settings = Settings {
            next_up_rewatching: true,
            ..Settings::default()
        };
        core.set_settings(new_settings.clone());
        assert_eq!(core.get_settings(), new_settings);
    }

    /// docs/09: concurrent `set_settings` calls must leave the file equal to memory, whichever lands last.
    #[test]
    fn concurrent_set_settings_leave_disk_equal_to_memory() {
        const ROUNDS: u32 = 400;
        let (dir, core) = core_in_tempdir();
        for round in 0..ROUNDS {
            let barrier = Arc::new(std::sync::Barrier::new(2));
            let writers: Vec<_> = (0..2u32)
                .map(|writer| {
                    let core = core.clone();
                    let barrier = barrier.clone();
                    std::thread::spawn(move || {
                        let record = Settings {
                            skip_back_secs: 1_000 + round * 2 + writer,
                            ..Settings::default()
                        };
                        barrier.wait();
                        core.set_settings(record);
                    })
                })
                .collect();
            for writer in writers {
                writer.join().expect("set_settings thread");
            }
            assert_eq!(
                settings::load(dir.path()),
                core.get_settings(),
                "round {round}: the saved file and the in-memory record diverged"
            );
        }
    }

    /// docs/09: a Next Up option change returns to the caller without waiting on the Next Up
    /// re-query, and the re-query still lands once the server answers.
    #[test]
    fn set_settings_with_changed_next_up_options_does_not_wait_on_the_next_up_reply() {
        const VIEW_ID: &str = "00000000-0000-0000-0000-000000000010";

        let (_dir, core, mock) = core_signed_in_against_routes();
        mock.route(
            "GET",
            "/UserViews",
            200,
            user_views_json(VIEW_ID, "tvshows"),
        );
        core.open_mirror().expect("open_mirror");
        wait_until(
            || mock.hit_count("GET", "/Shows/NextUp") >= 1,
            "the open's own Next Up fetch",
        );
        std::thread::sleep(Duration::from_millis(300));
        let before = mock.hit_count("GET", "/Shows/NextUp");
        let held = mock.gate("GET", "/Shows/NextUp", before);

        let changing = {
            let core = core.clone();
            std::thread::spawn(move || {
                core.set_settings(Settings {
                    next_up_cutoff_days: Some(14),
                    ..Settings::default()
                });
            })
        };
        wait_until(
            || mock.hit_count("GET", "/Shows/NextUp") > before,
            "the Next Up re-query to arrive and be held",
        );
        std::thread::sleep(Duration::from_millis(500));
        let returned_while_held = changing.is_finished();

        held.open();
        changing.join().expect("set_settings thread");
        assert!(
            returned_while_held,
            "set_settings must return while the Next Up reply is still held"
        );
        assert_eq!(core.get_settings().next_up_cutoff_days, Some(14));
    }

    #[test]
    fn get_library_grid_prefs_for_unknown_view_id_returns_defaults() {
        let (_dir, core) = core_in_tempdir();
        assert_eq!(
            core.get_library_grid_prefs("view-never-seen".to_string()),
            LibraryGridPrefs::default()
        );
    }

    #[test]
    fn set_library_grid_prefs_persists_to_disk_and_updates_in_memory_state() {
        let (dir, core) = core_in_tempdir();
        let prefs = LibraryGridPrefs {
            sort: GridSort {
                field: crate::types::GridSortField::Year,
                descending: true,
            },
            filters: GridFilters {
                watched: crate::types::WatchedFilter::Unwatched,
                genre: Some("Comedy".to_string()),
                decade: Some(crate::types::Decade::D2010s),
                status: crate::types::StatusFilter::Any,
                item_type: None,
            },
        };

        core.set_library_grid_prefs("view-movies".to_string(), prefs.clone());

        assert_eq!(
            core.get_library_grid_prefs("view-movies".to_string()),
            prefs
        );
        let on_disk = library_prefs::load(dir.path());
        assert_eq!(on_disk.get("view-movies"), Some(&prefs));
        // A different view id is untouched -- a per-view map.
        assert_eq!(
            core.get_library_grid_prefs("view-tvshows".to_string()),
            LibraryGridPrefs::default()
        );
    }

    #[test]
    fn a_fresh_core_loads_library_grid_prefs_previously_saved_to_the_same_data_dir() {
        let dir = tempfile::tempdir().expect("tempdir");
        let mut saved = LibraryGridPrefsFile::new();
        saved.insert(
            "view-movies".to_string(),
            LibraryGridPrefs {
                sort: GridSort {
                    field: crate::types::GridSortField::Runtime,
                    descending: false,
                },
                filters: GridFilters::default(),
            },
        );
        library_prefs::save(dir.path(), &saved).expect("seed a library grid prefs file");

        let core = JellybeamCore::new(dir.path().to_string_lossy().to_string());
        assert_eq!(
            core.get_library_grid_prefs("view-movies".to_string()),
            saved["view-movies"].clone()
        );
    }

    #[test]
    fn set_library_grid_prefs_without_an_open_mirror_does_not_panic() {
        let (_dir, core) = core_in_tempdir();
        let prefs = LibraryGridPrefs {
            sort: GridSort::default(),
            filters: GridFilters::default(),
        };
        core.set_library_grid_prefs("view-1".to_string(), prefs.clone());
        assert_eq!(core.get_library_grid_prefs("view-1".to_string()), prefs);
    }

    /// docs/16-library-sort-filter.md §5: two back-to-back
    /// `set_library_grid_prefs` calls for different views must both land on disk.
    #[test]
    fn back_to_back_prefs_writes_for_different_views_both_reach_disk() {
        let (dir, core) = core_in_tempdir();
        let movies_prefs = LibraryGridPrefs {
            sort: GridSort {
                field: crate::types::GridSortField::Year,
                descending: true,
            },
            filters: GridFilters::default(),
        };
        let tvshows_prefs = LibraryGridPrefs {
            sort: GridSort {
                field: crate::types::GridSortField::Runtime,
                descending: false,
            },
            filters: GridFilters::default(),
        };

        core.set_library_grid_prefs("view-movies".to_string(), movies_prefs.clone());
        core.set_library_grid_prefs("view-tvshows".to_string(), tvshows_prefs.clone());

        let on_disk = library_prefs::load(dir.path());
        assert_eq!(on_disk.get("view-movies"), Some(&movies_prefs));
        assert_eq!(on_disk.get("view-tvshows"), Some(&tvshows_prefs));
    }

    /// docs/16-library-sort-filter.md §5: a stale-revision snapshot must
    /// never overwrite one saved under a newer revision.
    #[test]
    fn persist_library_grid_prefs_skips_a_stale_revision() {
        let (dir, core) = core_in_tempdir();
        let mut newer = LibraryGridPrefsFile::new();
        newer.insert(
            "view-movies".to_string(),
            LibraryGridPrefs {
                sort: GridSort {
                    field: crate::types::GridSortField::Year,
                    descending: true,
                },
                filters: GridFilters::default(),
            },
        );
        let mut older = LibraryGridPrefsFile::new();
        older.insert(
            "view-movies".to_string(),
            LibraryGridPrefs {
                sort: GridSort {
                    field: crate::types::GridSortField::Name,
                    descending: false,
                },
                filters: GridFilters::default(),
            },
        );

        core.persist_library_grid_prefs(2, &newer);
        core.persist_library_grid_prefs(1, &older);

        let on_disk = library_prefs::load(dir.path());
        assert_eq!(on_disk, newer);
    }

    /// §4.6: `library_grid`/`_counts`/`_groups` return `None` (never a
    /// panic/error/defaulted `Some`) when no mirror is open. `library_genres`
    /// keeps its older empty-list-always contract.
    #[test]
    fn library_grid_queries_are_none_before_mirror_is_open() {
        let (_dir, core) = core_in_tempdir();
        let sort = GridSort::default();
        let filters = GridFilters::default();

        assert!(core
            .library_grid("view-1".to_string(), sort, filters.clone(), 0, 20)
            .is_none());
        assert!(core
            .library_grid_counts("view-1".to_string(), filters.clone())
            .is_none());
        assert!(core
            .library_grid_groups("view-1".to_string(), sort, filters)
            .is_none());
        assert!(core.library_genres("view-1".to_string()).is_empty());
    }

    #[test]
    fn home_snapshot_skips_hidden_library_ids_in_latest_shelves() {
        let (_dir, core) = core_in_tempdir();
        // No mirror open, so home_snapshot already returns empty; proves
        // set_settings + home_snapshot compose without panicking.
        let new_settings = Settings {
            hidden_library_ids: vec!["some-view-id".to_string()],
            ..Settings::default()
        };
        core.set_settings(new_settings);

        let HomeSnapshot::Classic { home } = core.home_snapshot(HomeLayout::Classic);
        assert!(home.shelves.is_empty());
    }

    fn track(id: i64, kind: TrackKindFfi, lang: Option<&str>) -> TrackInfo {
        TrackInfo {
            id,
            kind,
            title: None,
            lang: lang.map(str::to_string),
            codec: None,
            is_default: false,
            is_selected: false,
            is_forced: false,
        }
    }

    #[test]
    fn resolve_tracks_before_any_settings_saved_leaves_everything_alone() {
        let (_dir, core) = core_in_tempdir();
        let tracks = vec![
            track(1, TrackKindFfi::Audio, Some("eng")),
            track(2, TrackKindFfi::Subtitle, Some("eng")),
        ];
        let decision = core.resolve_tracks(None, tracks);
        assert_eq!(decision.audio_track_id, None);
        assert_eq!(
            decision.subtitle_action,
            crate::types::SubtitleActionFfi::Leave
        );
        assert_eq!(decision.subtitle_track_id, None);
    }

    #[test]
    fn resolve_tracks_matches_the_global_audio_language_preference() {
        let (_dir, core) = core_in_tempdir();
        let settings = Settings {
            language: crate::settings::LanguageSettings {
                audio: Some("jpn".to_string()),
                ..Default::default()
            },
            ..Settings::default()
        };
        core.set_settings(settings);

        let tracks = vec![
            track(1, TrackKindFfi::Audio, Some("eng")),
            track(2, TrackKindFfi::Audio, Some("jpn")),
        ];
        let decision = core.resolve_tracks(None, tracks);
        assert_eq!(decision.audio_track_id, Some(2));
    }

    #[test]
    fn resolve_tracks_subtitle_mode_none_always_turns_subtitles_off() {
        let (_dir, core) = core_in_tempdir();
        let settings = Settings {
            language: crate::settings::LanguageSettings {
                subtitle_mode: crate::settings::SubtitleModeSetting::None,
                ..Default::default()
            },
            ..Settings::default()
        };
        core.set_settings(settings);

        let tracks = vec![track(1, TrackKindFfi::Subtitle, Some("eng"))];
        let decision = core.resolve_tracks(None, tracks);
        assert_eq!(
            decision.subtitle_action,
            crate::types::SubtitleActionFfi::Off
        );
        assert_eq!(decision.subtitle_track_id, None);
    }

    #[test]
    fn remember_track_memory_makes_a_per_series_preference_win_over_the_global_one() {
        let (_dir, core) = core_in_tempdir();
        // Global prefers "jpn"; the remembered per-series choice must
        // override it outright.
        let settings = Settings {
            language: crate::settings::LanguageSettings {
                audio: Some("jpn".to_string()),
                ..Default::default()
            },
            ..Settings::default()
        };
        core.set_settings(settings);

        core.remember_track_memory(
            "series-1".to_string(),
            TrackKindFfi::Audio,
            TrackMemoryFfi::Key {
                key: "eng".to_string(),
            },
        );

        let tracks = vec![
            track(1, TrackKindFfi::Audio, Some("eng")),
            track(2, TrackKindFfi::Audio, Some("jpn")),
        ];
        let decision = core.resolve_tracks(Some("series-1".to_string()), tracks);
        assert_eq!(decision.audio_track_id, Some(1));
    }

    #[test]
    fn remember_track_memory_only_applies_to_the_series_it_was_saved_under() {
        let (_dir, core) = core_in_tempdir();
        core.remember_track_memory(
            "series-1".to_string(),
            TrackKindFfi::Audio,
            TrackMemoryFfi::Key {
                key: "eng".to_string(),
            },
        );

        let tracks = vec![
            track(1, TrackKindFfi::Audio, Some("eng")),
            track(2, TrackKindFfi::Audio, Some("jpn")),
        ];
        // A different (or absent) series id must not see series-1's memory.
        let decision = core.resolve_tracks(Some("series-2".to_string()), tracks.clone());
        assert_eq!(decision.audio_track_id, None);
        let decision = core.resolve_tracks(None, tracks);
        assert_eq!(decision.audio_track_id, None);
    }

    #[test]
    fn remember_track_memory_persists_across_a_fresh_core_against_the_same_data_dir() {
        let dir = tempfile::tempdir().expect("tempdir");
        let core = JellybeamCore::new(dir.path().to_string_lossy().to_string());
        core.remember_track_memory(
            "series-1".to_string(),
            TrackKindFfi::Subtitle,
            TrackMemoryFfi::Key {
                key: "spa".to_string(),
            },
        );

        let reopened = JellybeamCore::new(dir.path().to_string_lossy().to_string());
        let tracks = vec![
            track(1, TrackKindFfi::Subtitle, Some("eng")),
            track(2, TrackKindFfi::Subtitle, Some("spa")),
        ];
        let decision = reopened.resolve_tracks(Some("series-1".to_string()), tracks);
        assert_eq!(decision.subtitle_track_id, Some(2));
    }

    fn remember(core: &JellybeamCore, series_id: &str, kind: TrackKindFfi, memory: TrackMemoryFfi) {
        core.remember_track_memory(series_id.to_string(), kind, memory);
    }

    fn core_with_global_subtitles_always() -> (tempfile::TempDir, Arc<JellybeamCore>) {
        let (dir, core) = core_in_tempdir();
        core.set_settings(Settings {
            language: crate::settings::LanguageSettings {
                subtitle_mode: crate::settings::SubtitleModeSetting::Always,
                ..Default::default()
            },
            ..Settings::default()
        });
        (dir, core)
    }

    /// An audio track plus an English subtitle the stream flags default, the case where global
    /// Always would turn subtitles back on.
    fn episode_tracks_with_default_subtitle() -> Vec<TrackInfo> {
        let mut subtitle = track(2, TrackKindFfi::Subtitle, Some("eng"));
        subtitle.is_default = true;
        vec![track(1, TrackKindFfi::Audio, Some("eng")), subtitle]
    }

    /// docs/18 §3.1: subtitle Off is a remembered per-series choice, so the next episode of that
    /// series stays subtitle-free even under global Always.
    #[test]
    fn remembering_subtitles_off_keeps_them_off_for_the_next_episode_under_global_always() {
        let (_dir, core) = core_with_global_subtitles_always();
        remember(
            &core,
            "series-1",
            TrackKindFfi::Subtitle,
            TrackMemoryFfi::Off,
        );

        let decision = core.resolve_tracks(
            Some("series-1".to_string()),
            episode_tracks_with_default_subtitle(),
        );
        assert_eq!(
            decision.subtitle_action,
            crate::types::SubtitleActionFfi::Off,
            "series-1 remembered Off but resolve_tracks returned {decision:?}"
        );
        assert_eq!(decision.subtitle_track_id, None);

        // Another series has no memory and still follows the global mode.
        let other = core.resolve_tracks(
            Some("series-2".to_string()),
            episode_tracks_with_default_subtitle(),
        );
        assert_eq!(other.subtitle_track_id, Some(2));
    }

    /// docs/18 §3.1: a remembered Off is written to track-prefs.json, so it survives a relaunch.
    #[test]
    fn a_remembered_subtitle_off_persists_across_a_fresh_core() {
        let dir = tempfile::tempdir().expect("tempdir");
        let core = JellybeamCore::new(dir.path().to_string_lossy().to_string());
        remember(
            &core,
            "series-1",
            TrackKindFfi::Subtitle,
            TrackMemoryFfi::Off,
        );

        let reopened = JellybeamCore::new(dir.path().to_string_lossy().to_string());
        reopened.set_settings(Settings {
            language: crate::settings::LanguageSettings {
                subtitle_mode: crate::settings::SubtitleModeSetting::Always,
                ..Default::default()
            },
            ..Settings::default()
        });
        let decision = reopened.resolve_tracks(
            Some("series-1".to_string()),
            episode_tracks_with_default_subtitle(),
        );
        assert_eq!(
            decision.subtitle_action,
            crate::types::SubtitleActionFfi::Off,
            "reopened core lost the remembered Off: {decision:?}"
        );
    }

    /// docs/12 "Choices are remembered per series": Off is remembered, and only an explicit clear
    /// (Unset) forgets it, returning the series to the global mode.
    #[test]
    fn remember_track_memory_off_is_remembered_and_an_explicit_clear_forgets() {
        let (_dir, core) = core_with_global_subtitles_always();
        remember(
            &core,
            "series-1",
            TrackKindFfi::Audio,
            TrackMemoryFfi::Key {
                key: "jpn".to_string(),
            },
        );
        remember(
            &core,
            "series-1",
            TrackKindFfi::Subtitle,
            TrackMemoryFfi::Off,
        );

        let mut tracks = episode_tracks_with_default_subtitle();
        tracks.push(track(3, TrackKindFfi::Audio, Some("jpn")));
        let decision = core.resolve_tracks(Some("series-1".to_string()), tracks.clone());
        assert_eq!(decision.audio_track_id, Some(3));
        assert_eq!(
            decision.subtitle_action,
            crate::types::SubtitleActionFfi::Off,
            "Off was not remembered: {decision:?}"
        );

        remember(
            &core,
            "series-1",
            TrackKindFfi::Audio,
            TrackMemoryFfi::Unset,
        );
        remember(
            &core,
            "series-1",
            TrackKindFfi::Subtitle,
            TrackMemoryFfi::Unset,
        );
        let decision = core.resolve_tracks(Some("series-1".to_string()), tracks);
        assert_eq!(
            decision.audio_track_id, None,
            "cleared audio memory must fall back to no audio decision (no global set either)"
        );
        assert_eq!(
            decision.subtitle_track_id,
            Some(2),
            "cleared subtitle memory must fall back to the global Always mode"
        );
    }

    #[test]
    fn remember_track_memory_with_video_kind_is_a_no_op() {
        let (_dir, core) = core_in_tempdir();
        // Must not panic, and must not create a usable preference either.
        core.remember_track_memory(
            "series-1".to_string(),
            TrackKindFfi::Video,
            TrackMemoryFfi::Key {
                key: "eng".to_string(),
            },
        );

        let tracks = vec![track(1, TrackKindFfi::Audio, Some("eng"))];
        let decision = core.resolve_tracks(Some("series-1".to_string()), tracks);
        assert_eq!(decision.audio_track_id, None);
    }

    /// Audio has no Off (docs/18 §3.1): an audio Off is refused like a Video choice, so it never
    /// erases the remembered language.
    #[test]
    fn remember_track_memory_with_audio_off_is_a_no_op() {
        let (_dir, core) = core_in_tempdir();
        let tracks = vec![
            track(1, TrackKindFfi::Audio, Some("eng")),
            track(2, TrackKindFfi::Audio, Some("jpn")),
        ];
        core.remember_track_memory(
            "series-1".to_string(),
            TrackKindFfi::Audio,
            TrackMemoryFfi::Key {
                key: "jpn".to_string(),
            },
        );
        core.remember_track_memory(
            "series-1".to_string(),
            TrackKindFfi::Audio,
            TrackMemoryFfi::Off,
        );
        let decision = core.resolve_tracks(Some("series-1".to_string()), tracks);
        assert_eq!(decision.audio_track_id, Some(2));
    }

    /// Thin [`RouteMockServer`] config scoped to exactly the two request
    /// shapes prepare_playback/preload_playback issue against a reachable
    /// server: a static `/Items` fixture and a scripted `/PlaybackInfo`.
    struct PlaybackMockServer {
        base_url: String,
        routes: RouteMockServer,
    }

    impl PlaybackMockServer {
        /// `playback_info_responses` scripts a distinct response per call
        /// (last entry repeats past the list's length).
        /// `playback_info_delays` is the same per-call-index shape for reply delay.
        async fn start_with_responses(
            item_json: serde_json::Value,
            playback_info_responses: Vec<serde_json::Value>,
            playback_info_delays: Vec<Duration>,
        ) -> Self {
            let routes = RouteMockServer::start().await;
            routes.route("GET", "/Items", 200, item_json);
            routes.route_script(
                "POST",
                "/PlaybackInfo",
                playback_info_responses,
                playback_info_delays,
            );
            Self {
                base_url: routes.base_url.clone(),
                routes,
            }
        }

        fn playback_info_hit_count(&self) -> usize {
            self.routes.hit_count_containing("POST", "/PlaybackInfo")
        }

        fn playback_info_bodies(&self) -> Vec<serde_json::Value> {
            self.routes
                .request_bodies_containing("POST", "/PlaybackInfo")
        }

        /// Holds the `hit_index`-th (zero-based) `PlaybackInfo` reply until the gate opens.
        fn hold_playback_info(&self, hit_index: usize) -> Gate {
            self.routes.gate("POST", "/PlaybackInfo", hit_index)
        }

        /// Start reports received, one per reporting session the core started.
        fn start_reports(&self) -> usize {
            self.routes.hit_count("POST", "/Sessions/Playing")
        }
    }

    /// Builds an `/Items` + `/PlaybackInfo` fixture pair for one `MediaSource`.
    /// `codecs` is `(video, audio)`; `None` omits `MediaStreams` entirely (the
    /// Direct Play cases, which never inspect codec facts), while
    /// `Some((None, None))` yields a codec-blind stream pair. `run_time_ticks`
    /// is only ever set when `supports_direct_play`, matching every caller.
    fn playback_fixture(
        item_name: &str,
        media_source_id: &str,
        container: Option<&str>,
        supports_direct_play: bool,
        transcoding_url: Option<&str>,
        codecs: Option<(Option<&str>, Option<&str>)>,
        play_session_id: &str,
    ) -> (serde_json::Value, serde_json::Value) {
        let item = jellyfin_api::models::BaseItemDto {
            name: Some(item_name.to_string()),
            type_: Some(jellyfin_api::models::BaseItemKind::Movie),
            ..Default::default()
        };
        let items_result = jellyfin_api::models::BaseItemDtoQueryResult {
            items: vec![item],
            ..Default::default()
        };
        let item_json = serde_json::to_value(&items_result).expect("serialize item");

        let media_streams = codecs
            .map(|(video, audio)| {
                vec![
                    jellyfin_api::models::MediaStream {
                        type_: Some(jellyfin_api::models::MediaStreamType::Video),
                        codec: video.map(str::to_string),
                        ..Default::default()
                    },
                    jellyfin_api::models::MediaStream {
                        type_: Some(jellyfin_api::models::MediaStreamType::Audio),
                        codec: audio.map(str::to_string),
                        ..Default::default()
                    },
                ]
            })
            .unwrap_or_default();
        let media_source = jellyfin_api::models::MediaSourceInfo {
            id: Some(media_source_id.to_string()),
            container: container.map(str::to_string),
            supports_direct_play: Some(supports_direct_play),
            transcoding_url: transcoding_url.map(str::to_string),
            media_streams,
            run_time_ticks: supports_direct_play.then_some(600_000_000),
            ..Default::default()
        };
        let info = jellyfin_api::models::PlaybackInfoResponse {
            media_sources: vec![media_source],
            play_session_id: Some(play_session_id.to_string()),
            ..Default::default()
        };
        let playback_info_json = serde_json::to_value(&info).expect("serialize playback info");

        (item_json, playback_info_json)
    }

    /// A `/Items` + `/PlaybackInfo` fixture where the source is
    /// Direct-Play-capable -- shared by every cache-hit/TTL test below.
    fn direct_play_fixture() -> (serde_json::Value, serde_json::Value) {
        playback_fixture(
            "Test Movie",
            "ms-1",
            Some("mkv"),
            true,
            None,
            None,
            "play-session-1",
        )
    }

    /// Signs `core` into a freshly started [`PlaybackMockServer`] and
    /// returns both, for asserting on `playback_info_hit_count`.
    fn core_signed_in_against_mock(
        item_json: serde_json::Value,
        playback_info_json: serde_json::Value,
    ) -> (tempfile::TempDir, Arc<JellybeamCore>, PlaybackMockServer) {
        core_signed_in_against_mock_with_delay(item_json, playback_info_json, Duration::ZERO)
    }

    fn core_signed_in_against_mock_with_delay(
        item_json: serde_json::Value,
        playback_info_json: serde_json::Value,
        playback_info_delay: Duration,
    ) -> (tempfile::TempDir, Arc<JellybeamCore>, PlaybackMockServer) {
        core_signed_in_against_mock_with_responses(
            item_json,
            vec![playback_info_json],
            playback_info_delay,
        )
    }

    /// Like [`core_signed_in_against_mock_with_delay`], but scripts a
    /// distinct response per call.
    fn core_signed_in_against_mock_with_responses(
        item_json: serde_json::Value,
        playback_info_responses: Vec<serde_json::Value>,
        playback_info_delay: Duration,
    ) -> (tempfile::TempDir, Arc<JellybeamCore>, PlaybackMockServer) {
        core_signed_in_against_mock_with_responses_and_delays(
            item_json,
            playback_info_responses,
            vec![playback_info_delay],
        )
    }

    /// Like [`core_signed_in_against_mock_with_responses`], but scripts a
    /// distinct per-call delay too.
    fn core_signed_in_against_mock_with_responses_and_delays(
        item_json: serde_json::Value,
        playback_info_responses: Vec<serde_json::Value>,
        playback_info_delays: Vec<Duration>,
    ) -> (tempfile::TempDir, Arc<JellybeamCore>, PlaybackMockServer) {
        let (dir, core) = core_in_tempdir();
        let mock = core
            .runtime
            .block_on(PlaybackMockServer::start_with_responses(
                item_json,
                playback_info_responses,
                playback_info_delays,
            ));
        let saved = session::SessionFile {
            server_url: mock.base_url.clone(),
            user_id: "u1".to_string(),
            user_name: "jellybeam-user".to_string(),
            token: "tok".to_string(),
            device_id: "dev".to_string(),
            mirror_dir: "mirror".to_string(),
            server_version: None,
            server_name: None,
        };
        session::add_or_update(dir.path(), saved).expect("seed a session file");
        assert!(core.restore_session().is_some());
        core.open_mirror()
            .expect("open_mirror should succeed synchronously against a real loopback server");
        (dir, core, mock)
    }

    fn wait_for_preload_idle(core: &JellybeamCore) {
        wait_until(
            || core.lock_state().preload_task.is_none(),
            "preload task to finish",
        );
    }

    fn wait_for_playback_info_hits(mock: &PlaybackMockServer, expected: usize) {
        wait_until(
            || mock.playback_info_hit_count() >= expected,
            "PlaybackInfo request to arrive",
        );
    }

    fn fake_preload_cache(item_id: &str) -> PreloadCache {
        PreloadCache {
            generation: 0,
            item_id: item_id.to_string(),
            source: jellyfin_api::models::MediaSourceInfo::default(),
            url: "http://example.invalid/stream".to_string(),
            play_session_id: "play-session".to_string(),
            server_verdict: None,
            created_at: Instant::now(),
        }
    }

    #[test]
    fn preload_playback_populates_the_cache_and_prepare_playback_consumes_it_without_a_second_network_call(
    ) {
        let (item_json, playback_json) = direct_play_fixture();
        let (_dir, core, mock) = core_signed_in_against_mock(item_json, playback_json);

        core.preload_playback("item-1".to_string(), None);
        wait_for_preload_idle(&core);
        assert_eq!(
            mock.playback_info_hit_count(),
            1,
            "preload should negotiate exactly once"
        );
        assert!(
            core.lock_state().preload_cache.is_some(),
            "a DirectPlay decision should be cached"
        );

        let plan = core
            .prepare_playback_now("item-1".to_string(), false)
            .expect("a fresh cache entry should be consumed, not refetched");
        assert_eq!(plan.item_id, "item-1");
        assert_eq!(
            mock.playback_info_hit_count(),
            1,
            "the cache hit must skip a second PlaybackInfo round trip"
        );
        assert!(
            core.lock_state().preload_cache.is_none(),
            "the cache is single-use"
        );
        assert!(
            core.lock_state().reporting.is_some(),
            "prepare_playback must still start a reporting session on a cache hit"
        );
    }

    #[test]
    fn preload_cache_expires_after_the_ttl_and_prepare_playback_refetches() {
        let (item_json, playback_json) = direct_play_fixture();
        let (_dir, core, mock) = core_signed_in_against_mock(item_json, playback_json);

        core.preload_playback("item-1".to_string(), None);
        wait_for_preload_idle(&core);
        assert_eq!(mock.playback_info_hit_count(), 1);

        // Age the cache entry past PRELOAD_CACHE_TTL without real time passing.
        {
            let mut state = core.lock_state();
            if let Some(cache) = state.preload_cache.as_mut() {
                cache.created_at = Instant::now() - PRELOAD_CACHE_TTL - Duration::from_secs(1);
            }
        }

        let plan = core
            .prepare_playback_now("item-1".to_string(), false)
            .expect("an expired cache entry should still fall through to a fresh fetch");
        assert_eq!(plan.item_id, "item-1");
        assert_eq!(
            mock.playback_info_hit_count(),
            2,
            "an expired cache entry must not be reused"
        );
    }

    #[test]
    fn duplicate_preload_for_the_same_in_flight_item_is_coalesced() {
        let (item_json, playback_json) = direct_play_fixture();
        let (_dir, core, mock) = core_signed_in_against_mock_with_delay(
            item_json,
            playback_json,
            Duration::from_millis(200),
        );

        core.preload_playback("item-1".to_string(), None);
        wait_for_playback_info_hits(&mock, 1);
        core.preload_playback("item-1".to_string(), None);
        std::thread::sleep(Duration::from_millis(30));

        assert_eq!(
            mock.playback_info_hit_count(),
            1,
            "a Detail Play-button trigger must not restart its card's in-flight preload"
        );
        wait_for_preload_idle(&core);
        assert_eq!(
            core.lock_state()
                .preload_cache
                .as_ref()
                .map(|c| c.item_id.as_str()),
            Some("item-1")
        );
    }

    #[test]
    fn rapid_retargets_keep_only_the_latest_pending_item_and_never_build_a_request_backlog() {
        let (item_json, playback_json) = direct_play_fixture();
        let (_dir, core, mock) = core_signed_in_against_mock_with_delay(
            item_json,
            playback_json,
            Duration::from_millis(150),
        );

        core.preload_playback("item-1".to_string(), None);
        wait_for_playback_info_hits(&mock, 1);
        core.preload_playback("item-2".to_string(), None);
        core.preload_playback("item-3".to_string(), None);
        wait_for_playback_info_hits(&mock, 2);
        wait_for_preload_idle(&core);
        std::thread::sleep(Duration::from_millis(175));

        let state = core.lock_state();
        assert_eq!(
            state.preload_cache.as_ref().map(|c| c.item_id.as_str()),
            Some("item-3"),
            "only the newest pending focus target may claim the one-slot cache"
        );
        assert_eq!(
            mock.playback_info_hit_count(),
            2,
            "the intermediate item must be coalesced instead of becoming a third request"
        );
        assert!(state.preload_task.is_none());
    }

    /// docs/18 §2: Play on the newest dwell target must not wait out an older target's reply.
    #[test]
    fn prepare_for_the_newest_dwell_target_does_not_wait_for_an_older_targets_negotiation() {
        let (item_json, playback_json) = direct_play_fixture();
        let (_dir, core, mock) = core_signed_in_against_mock(item_json, playback_json);
        let held_a = mock.routes.gate("POST", "/Items/item-a/PlaybackInfo", 0);
        let hits_for = |item: &str| {
            mock.routes
                .hit_count_containing("POST", &format!("/Items/{item}/PlaybackInfo"))
        };

        core.preload_playback("item-a".to_string(), None);
        wait_until(|| hits_for("item-a") >= 1, "A's PlaybackInfo to arrive");
        core.preload_playback("item-b".to_string(), None);
        let play_b = spawn_prepare(&core, "item-b");

        let mut finished_while_a_held = false;
        for _ in 0..200 {
            if play_b.is_finished() {
                finished_while_a_held = true;
                break;
            }
            std::thread::sleep(Duration::from_millis(5));
        }
        // Release A before asserting so a failing run cannot leave the thread blocked.
        held_a.open();
        let plan = join(play_b);
        assert!(
            finished_while_a_held,
            "Play on B waited for A's held PlaybackInfo reply"
        );
        assert_eq!(plan.expect("Play on B").item_id, "item-b");

        std::thread::sleep(Duration::from_millis(150));
        assert_eq!(
            hits_for("item-b"),
            1,
            "B's PlaybackInfo must be requested exactly once, never joined and then renegotiated"
        );
        assert_eq!(hits_for("item-a"), 1);
    }

    /// Re-preloads item-1 after backdating its cache entry to `age_fraction` of the TTL
    /// (the existing tests' `created_at` seam); returns the PlaybackInfo total.
    fn playback_info_hits_after_repreload_at_age(age_fraction: f64) -> usize {
        let (item_json, playback_json) = direct_play_fixture();
        let (_dir, core, mock) = core_signed_in_against_mock(item_json, playback_json);
        core.preload_playback("item-1".to_string(), None);
        wait_for_preload_idle(&core);
        assert_eq!(mock.playback_info_hit_count(), 1);

        {
            let mut state = core.lock_state();
            let cache = state.preload_cache.as_mut().expect("a cached DirectPlay");
            cache.created_at = Instant::now() - PRELOAD_CACHE_TTL.mul_f64(age_fraction);
        }
        core.preload_playback("item-1".to_string(), None);
        wait_for_preload_idle(&core);
        mock.playback_info_hit_count()
    }

    /// docs/18 §2: a cache past half its TTL is renegotiated by the next dwell trigger, so a long
    /// dwell re-armed every TTL never sits on a dead cache.
    #[test]
    fn a_repeated_preload_renegotiates_a_cache_at_half_its_ttl_or_older_and_not_a_younger_one() {
        // (cache age as a fraction of the TTL, expected PlaybackInfo requests in total)
        let mismatches: Vec<String> = [(0.0, 1), (0.5, 2), (0.99, 2), (1.01, 2)]
            .into_iter()
            .filter_map(|(age, expected)| {
                let hits = playback_info_hits_after_repreload_at_age(age);
                (hits != expected)
                    .then(|| format!("age {age} x TTL: {hits} requests, expected {expected}"))
            })
            .collect();
        assert!(mismatches.is_empty(), "{mismatches:?}");
    }

    /// docs/18 §2: refreshing early must not shorten how long a cache serves Play.
    #[test]
    fn prepare_still_consumes_a_cache_older_than_half_its_ttl() {
        let (item_json, playback_json) = direct_play_fixture();
        let (_dir, core, mock) = core_signed_in_against_mock(item_json, playback_json);
        core.preload_playback("item-1".to_string(), None);
        wait_for_preload_idle(&core);
        core.lock_state()
            .preload_cache
            .as_mut()
            .expect("a cached DirectPlay")
            .created_at = Instant::now() - PRELOAD_CACHE_TTL.mul_f64(0.75);

        core.prepare_playback_now("item-1".to_string(), false)
            .expect("a cache inside its TTL serves Play");
        assert_eq!(
            mock.playback_info_hit_count(),
            1,
            "Play inside the TTL must not renegotiate"
        );
    }

    #[test]
    fn real_prepare_for_the_same_item_joins_the_in_flight_preload_instead_of_renegotiating() {
        let (item_json, playback_json) = direct_play_fixture();
        let (_dir, core, mock) = core_signed_in_against_mock_with_delay(
            item_json,
            playback_json,
            Duration::from_millis(150),
        );

        core.preload_playback("item-1".to_string(), None);
        wait_for_playback_info_hits(&mock, 1);
        let plan = core
            .prepare_playback_now("item-1".to_string(), false)
            .expect("prepare_playback should join the in-flight preload for the same item");
        assert_eq!(plan.item_id, "item-1");
        assert_eq!(
            mock.playback_info_hit_count(),
            1,
            "joining the same item's in-flight preload must not start a second PlaybackInfo round trip"
        );

        let state = core.lock_state();
        assert!(
            state.preload_task.is_none(),
            "the joined worker should have finished"
        );
        assert!(
            state.preload_cache.is_none(),
            "a joined preload result is consumed by prepare_playback, not left cached"
        );
        assert!(
            state.reporting.is_some(),
            "prepare_playback must still start a reporting session on a join"
        );
    }

    #[test]
    fn real_prepare_for_a_different_item_aborts_the_unrelated_in_flight_preload() {
        let (item_json, playback_json) = direct_play_fixture();
        let (_dir, core, mock) = core_signed_in_against_mock_with_delay(
            item_json,
            playback_json,
            Duration::from_millis(150),
        );

        core.preload_playback("item-1".to_string(), None);
        wait_for_playback_info_hits(&mock, 1);
        let plan = core
            .prepare_playback_now("item-2".to_string(), false)
            .expect("the real prepare owns a fresh successful negotiation");
        assert_eq!(plan.item_id, "item-2");
        assert_eq!(
            mock.playback_info_hit_count(),
            2,
            "a different item must negotiate its own fresh PlaybackInfo call, not join an unrelated preload"
        );
        std::thread::sleep(Duration::from_millis(175));

        let state = core.lock_state();
        assert!(state.preload_task.is_none());
        assert!(
            state.preload_cache.is_none(),
            "the cancelled item-1 speculative result must not publish after a different item's playback started"
        );
    }

    #[test]
    fn an_account_change_aborts_in_flight_preload_and_late_completion_cannot_repopulate_cache() {
        let (item_json, playback_json) = direct_play_fixture();
        let (_dir, core, mock) = core_signed_in_against_mock_with_delay(
            item_json,
            playback_json,
            Duration::from_millis(150),
        );

        core.preload_playback("item-1".to_string(), None);
        wait_for_playback_info_hits(&mock, 1);
        change_account(&core);
        std::thread::sleep(Duration::from_millis(175));

        let state = core.lock_state();
        assert!(state.preload_task.is_none());
        assert!(state.preload_cache.is_none());
    }

    /// A request for `item` that no network serves: the stand-in worker below never ends by itself.
    fn quiet_preload_request(state: &State, item: &str, generation: u64) -> PreloadRequest {
        PreloadRequest {
            generation,
            item_id: item.to_string(),
            client: state
                .account
                .installed()
                .map(|i| i.client.clone())
                .expect("a signed-in core"),
            mirror: state
                .account
                .bundle()
                .map(|g| g.mirror.clone())
                .expect("an open mirror"),
            caps: state.caps.clone(),
            tolerate_mislabeled_levels: state.settings.tolerate_mislabeled_levels,
            quality: state.settings.playback_quality,
        }
    }

    /// Starts the stand-in worker for `item`.
    fn start_quiet_preload(core: &JellybeamCore, item: &str, generation: u64) {
        let mut state = core.lock_state();
        let request = quiet_preload_request(&state, item, generation);
        state.preload_request(request, Instant::now(), |_| {
            core.runtime.spawn(std::future::pending::<()>())
        });
    }

    /// The worker for `generation` ending with a DirectPlay cache for `item`.
    fn publish_quiet_preload(core: &JellybeamCore, item: &str, generation: u64) {
        let cache = PreloadCache {
            generation,
            ..fake_preload_cache(item)
        };
        let _ = core
            .lock_state()
            .preload_publish(generation, Some(cache), Instant::now());
    }

    /// One thing that happens to the slot between a Play joining item-x's negotiation and its read.
    #[derive(Clone, Copy, Debug)]
    enum AfterJoin {
        Publish(&'static str, u64),
        AccountReset,
        SetSettings,
        SetDeviceCaps,
        Request(&'static str, u64),
    }

    fn apply_after_join(core: &JellybeamCore, event: AfterJoin) {
        match event {
            AfterJoin::Publish(item, generation) => publish_quiet_preload(core, item, generation),
            AfterJoin::AccountReset => change_account(core),
            AfterJoin::SetSettings => {
                let mut settings = core.get_settings();
                settings.tolerate_mislabeled_levels = !settings.tolerate_mislabeled_levels;
                core.set_settings(settings);
            }
            AfterJoin::SetDeviceCaps => core.set_device_caps(DeviceCaps::default()),
            AfterJoin::Request(item, generation) => start_quiet_preload(core, item, generation),
        }
    }

    /// docs/18 §2: a Play that joined item-x's negotiation (generation 1) reads that generation's
    /// result alone -- never another generation's or account's, and not lost to another item's trigger.
    #[test]
    fn a_joined_play_reads_only_the_generation_it_joined() {
        use AfterJoin::*;
        // (events after the join, the generation the joined read must yield)
        let table: Vec<(Vec<AfterJoin>, Option<u64>)> = vec![
            (vec![Publish("item-x", 1)], Some(1)),
            (vec![], None),
            (vec![Publish("item-x", 1), AccountReset], None),
            (vec![Publish("item-x", 1), SetSettings], None),
            (vec![Publish("item-x", 1), SetDeviceCaps], None),
            (vec![AccountReset, Publish("item-x", 1)], None),
            (vec![Publish("item-x", 1), Request("item-y", 2)], Some(1)),
            (
                vec![
                    Request("item-y", 2),
                    Publish("item-x", 1),
                    Publish("item-y", 2),
                ],
                Some(1),
            ),
            (
                vec![
                    Publish("item-x", 1),
                    AccountReset,
                    Request("item-x", 2),
                    Publish("item-x", 2),
                ],
                None,
            ),
            (
                vec![
                    Publish("item-x", 1),
                    SetSettings,
                    Request("item-x", 2),
                    Publish("item-x", 2),
                ],
                None,
            ),
            (
                vec![
                    Publish("item-x", 1),
                    SetDeviceCaps,
                    Request("item-x", 2),
                    Publish("item-x", 2),
                ],
                None,
            ),
        ];
        let mismatches: Vec<String> = table
            .into_iter()
            .filter_map(|(events, expected)| {
                let (item_json, playback_json) = direct_play_fixture();
                let (_dir, core, _mock) = core_signed_in_against_mock(item_json, playback_json);
                start_quiet_preload(&core, "item-x", 1);
                let join = core
                    .lock_state()
                    .preload_take_for_play("item-x", true, Instant::now())
                    .join
                    .expect("Play for the running item joins it");
                for event in &events {
                    apply_after_join(&core, *event);
                }
                let mut state = core.lock_state();
                let read = state.preload_take_joined(&join, "item-x", Instant::now());
                let read = read.map(|cache| cache.generation);
                let slot_left = state.preload_cache.as_ref().map(|cache| cache.generation);
                let consumed = read.is_none() || slot_left != read;
                (read != expected || !consumed).then(|| {
                    format!("{events:?}: read {read:?} (slot still {slot_left:?}), expected {expected:?}")
                })
            })
            .collect();
        assert!(mismatches.is_empty(), "{mismatches:#?}");
    }

    /// docs/18 §2: a negotiation's single-use result goes to one Play; a second Play for the same
    /// running item negotiates on its own and leaves the task the first is awaiting alone.
    #[test]
    fn only_the_first_play_joins_a_running_negotiation() {
        let (item_json, playback_json) = direct_play_fixture();
        let (_dir, core, _mock) = core_signed_in_against_mock(item_json, playback_json);
        start_quiet_preload(&core, "item-x", 1);
        let mut state = core.lock_state();
        let first = state.preload_take_for_play("item-x", true, Instant::now());
        let second = state.preload_take_for_play("item-x", true, Instant::now());
        assert!(first.join.is_some(), "the first Play joins");
        assert!(
            second.join.is_none(),
            "the second Play joined the same result"
        );
        assert!(
            second.abort.is_none(),
            "the second Play aborted the joined task"
        );
        assert!(state.preload_task.is_some());
    }

    /// One step of a dwell sequence against the preload slot.
    #[derive(Clone, Copy, Debug)]
    enum Dwell {
        Request(&'static str),
        /// The running negotiation ends with a `DirectPlay` cache for its own item.
        Publish,
        Play(&'static str),
        /// The slot's cache was made this much earlier.
        Age(Duration),
    }

    /// Runs `steps` against a fresh preload slot and returns the item its cache serves.
    fn slot_after(steps: &[Dwell]) -> Option<String> {
        let (item_json, playback_json) = direct_play_fixture();
        let (_dir, core, _mock) = core_signed_in_against_mock(item_json, playback_json);
        let mut generation = 0;
        for step in steps {
            match *step {
                Dwell::Request(item) => {
                    generation += 1;
                    start_quiet_preload(&core, item, generation);
                }
                Dwell::Publish => {
                    let (running, generation) = {
                        let state = core.lock_state();
                        let task = state.preload_task.as_ref().expect("a running task");
                        (task.running_item.clone(), task.generation)
                    };
                    publish_quiet_preload(&core, &running, generation);
                }
                Dwell::Play(item) => {
                    let _ = core
                        .lock_state()
                        .preload_take_for_play(item, true, Instant::now());
                }
                Dwell::Age(by) => {
                    if let Some(cache) = core.lock_state().preload_cache.as_mut() {
                        cache.created_at -= by;
                    }
                }
            }
        }
        let slot = core
            .lock_state()
            .preload_cache
            .as_ref()
            .map(|cache| cache.item_id.clone());
        slot
    }

    /// docs/18 §2: whichever negotiation publishes, the slot ends up serving the newest target
    /// while its cache still serves, and anything else otherwise.
    #[test]
    fn a_publish_never_replaces_a_cache_that_still_serves_the_newest_target() {
        use Dwell::{Age, Play, Publish, Request};
        let past_refresh = PRELOAD_REFRESH_AFTER + Duration::from_secs(1);
        let past_ttl = PRELOAD_CACHE_TTL + Duration::from_secs(1);
        // (sequence, the slot's item after its last step)
        let table: Vec<(Vec<Dwell>, Option<&str>)> = vec![
            // X cached, then Y asked: Y drops X's cache, so back on X changes nothing for Y's publish.
            (
                vec![Request("x"), Publish, Request("y"), Request("x"), Publish],
                Some("y"),
            ),
            (
                vec![Request("x"), Request("y"), Publish, Request("x"), Publish],
                Some("x"),
            ),
            (
                vec![
                    Request("x"),
                    Request("y"),
                    Publish,
                    Request("x"),
                    Request("y"),
                    Publish,
                ],
                Some("y"),
            ),
            (
                vec![
                    Request("x"),
                    Request("y"),
                    Publish,
                    Request("x"),
                    Request("z"),
                    Publish,
                ],
                Some("y"),
            ),
            (
                vec![
                    Request("x"),
                    Request("y"),
                    Publish,
                    Request("x"),
                    Play("y"),
                    Publish,
                ],
                Some("y"),
            ),
            (
                vec![
                    Request("x"),
                    Request("y"),
                    Publish,
                    Age(past_refresh),
                    Request("x"),
                    Publish,
                ],
                Some("x"),
            ),
            (
                vec![
                    Request("x"),
                    Request("y"),
                    Publish,
                    Age(past_ttl),
                    Request("x"),
                    Publish,
                ],
                Some("y"),
            ),
        ];
        let mismatches: Vec<String> = table
            .into_iter()
            .filter_map(|(steps, expected)| {
                let slot = slot_after(&steps);
                (slot.as_deref() != expected)
                    .then(|| format!("{steps:?}: slot {slot:?}, expected {expected:?}"))
            })
            .collect();
        assert!(mismatches.is_empty(), "{mismatches:#?}");
    }

    /// docs/18 §2: back on a cached item, a different item's running negotiation must not
    /// replace that item's cache when it ends.
    #[test]
    fn a_different_items_negotiation_cannot_overwrite_the_newest_items_young_cache() {
        let (item_json, playback_json) = direct_play_fixture();
        let (_dir, core, mock) = core_signed_in_against_mock(item_json, playback_json);
        let held_x = mock.routes.gate("POST", "/Items/item-x/PlaybackInfo", 0);
        let held_y = mock.routes.gate("POST", "/Items/item-y/PlaybackInfo", 0);
        let hits_for = |item: &str| {
            mock.routes
                .hit_count_containing("POST", &format!("/Items/{item}/PlaybackInfo"))
        };

        core.preload_playback("item-x".to_string(), None);
        wait_until(|| hits_for("item-x") >= 1, "X's PlaybackInfo to arrive");
        core.preload_playback("item-y".to_string(), None);
        held_x.open();
        wait_until(|| hits_for("item-y") >= 1, "Y's PlaybackInfo to arrive");
        assert_eq!(
            core.lock_state()
                .preload_cache
                .as_ref()
                .map(|c| c.item_id.clone()),
            Some("item-x".to_string()),
            "X's cache is young while Y runs"
        );

        core.preload_playback("item-x".to_string(), None);
        held_y.open();
        wait_for_preload_idle(&core);

        assert_eq!(
            core.lock_state()
                .preload_cache
                .as_ref()
                .map(|c| c.item_id.clone()),
            Some("item-x".to_string()),
            "Y finishing must not replace the item the viewer is back on"
        );
    }

    /// docs/18 §3: playback start enriches with one `/Items?ids` GET, whose record carries both the
    /// OSD detail and the playing source's trickplay manifest.
    #[test]
    fn playback_enrichment_is_one_items_fetch_carrying_the_osd_detail_and_the_trickplay_manifest() {
        let manifest = jellyfin_api::models::TrickplayInfoDto {
            width: Some(320),
            height: Some(180),
            tile_width: Some(10),
            tile_height: Some(10),
            interval: Some(10_000),
            thumbnail_count: Some(100),
            ..Default::default()
        };
        let item = jellyfin_api::models::BaseItemDto {
            name: Some("Test Movie".to_string()),
            trickplay: std::collections::HashMap::from([(
                "ms-1".to_string(),
                std::collections::HashMap::from([("320".to_string(), manifest)]),
            )]),
            ..Default::default()
        };
        let item_json = serde_json::to_value(jellyfin_api::models::BaseItemDtoQueryResult {
            items: vec![item],
            ..Default::default()
        })
        .expect("serialize items");
        let (_, playback_json) = direct_play_fixture();
        let (_dir, core, mock) = core_signed_in_against_mock(item_json, playback_json);
        let fetches = || mock.routes.hit_count_containing("GET", "ids=item-1");
        let before = fetches();

        let detail = core
            .get_playback_osd_detail("item-1".to_string(), "ms-1".to_string(), None)
            .expect("the enrichment fetch");

        assert_eq!(fetches() - before, 1, "one fetch, not one per consumer");
        let request = mock
            .routes
            .log_snapshot()
            .into_iter()
            .rfind(|entry| entry.contains("ids=item-1"))
            .expect("the fetch is logged");
        for field in ["MediaStreams", "Chapters", "MediaSources", "Trickplay"] {
            assert!(request.contains(field), "{field} missing from {request}");
        }
        assert_eq!(
            detail.trickplay,
            Some(crate::types::TrickplayMetaFfi {
                width: 320,
                height: 180,
                tile_width: 10,
                tile_height: 10,
                interval_ms: 10_000,
                thumbnail_count: 100,
            })
        );
    }

    #[test]
    fn profile_setting_change_discards_a_completed_preload() {
        let (item_json, playback_json) = direct_play_fixture();
        let (_dir, core, _mock) = core_signed_in_against_mock(item_json, playback_json);
        core.preload_playback("item-1".to_string(), None);
        wait_for_preload_idle(&core);
        assert!(core.lock_state().preload_cache.is_some());

        let mut settings = core.get_settings();
        settings.tolerate_mislabeled_levels = !settings.tolerate_mislabeled_levels;
        core.set_settings(settings);

        assert!(core.lock_state().preload_cache.is_none());
    }

    #[test]
    fn preload_playback_is_a_no_op_when_the_setting_is_off() {
        let (item_json, playback_json) = direct_play_fixture();
        let (_dir, core, mock) = core_signed_in_against_mock(item_json, playback_json);
        let mut settings = core.get_settings();
        settings.preload_on_focus = false;
        core.set_settings(settings);

        core.preload_playback("item-1".to_string(), None);

        assert_eq!(
            mock.playback_info_hit_count(),
            0,
            "preload must not even negotiate when the setting is off"
        );
        assert!(core.lock_state().preload_cache.is_none());
    }

    #[test]
    fn preload_playback_before_sign_in_is_a_silent_no_op() {
        let (_dir, core) = core_in_tempdir();
        // Must not panic; there is no `Result` to surface an error through.
        core.preload_playback("item-1".to_string(), None);
        assert!(core.lock_state().preload_cache.is_none());
    }

    #[test]
    fn preload_playback_without_open_mirror_is_a_silent_no_op() {
        let (dir, core) = core_in_tempdir();
        let saved = session::SessionFile {
            server_url: "http://example.invalid".to_string(),
            user_id: "u1".to_string(),
            user_name: "jellybeam-user".to_string(),
            token: "tok".to_string(),
            device_id: "dev".to_string(),
            mirror_dir: "mirror".to_string(),
            server_version: None,
            server_name: None,
        };
        session::add_or_update(dir.path(), saved).expect("seed a session file");
        assert!(core.restore_session().is_some());

        core.preload_playback("item-1".to_string(), None);
        assert!(core.lock_state().preload_cache.is_none());
    }

    /// A fixture whose `MediaSource` genuinely can't direct play (real
    /// incompatible codec facts, not an empty stream list, else
    /// `is_codec_blind` would treat it as blind).
    fn transcode_fixture() -> (serde_json::Value, serde_json::Value) {
        playback_fixture(
            "Test Movie",
            "ms-2",
            Some("wmv"),
            false,
            Some("/videos/ms-2/master.m3u8"),
            Some((Some("wmv3"), Some("wmav2"))),
            "play-session-2",
        )
    }

    /// A fixture with codecs this TV's default profile *does* advertise
    /// (h264/aac) but `SupportsDirectPlay: false` -- exercises the
    /// attempt-anyway row under Auto, and "Cap transcodes anyway" under Cap.
    fn compatible_codec_transcode_fixture() -> (serde_json::Value, serde_json::Value) {
        playback_fixture(
            "Test Movie",
            "ms-4",
            Some("mkv"),
            false,
            Some("/videos/ms-4/master.m3u8"),
            Some((Some("h264"), Some("aac"))),
            "play-session-4",
        )
    }

    /// A codec-blind plugin/live-TV source (`Codec: null` everywhere) that
    /// the server still marked non-direct-playable with a `TranscodingUrl`.
    fn codec_blind_fallback_fixture() -> (serde_json::Value, serde_json::Value) {
        playback_fixture(
            "Test Movie",
            "ms-3",
            None,
            false,
            Some("/videos/ms-3/master.m3u8"),
            Some((None, None)),
            "play-session-3",
        )
    }

    /// Same shape as [`direct_play_fixture`] but a distinct item/
    /// `play_session_id`, used to represent Kotlin switching titles mid-fallback.
    fn second_item_direct_play_fixture() -> (serde_json::Value, serde_json::Value) {
        playback_fixture(
            "Test Movie 2",
            "ms-5",
            Some("mkv"),
            true,
            None,
            None,
            "play-session-5",
        )
    }

    /// docs/18-playback-quality.md §2: Direct Play mode (default) refuses a
    /// server `Transcode` decision outright, never cached or turned into a plan.
    #[test]
    fn preload_playback_does_not_cache_a_would_transcode_decision() {
        let (item_json, playback_info_json) = transcode_fixture();
        let (_dir, core, mock) = core_signed_in_against_mock(item_json, playback_info_json);

        core.preload_playback("item-1".to_string(), None);
        wait_for_preload_idle(&core);

        assert_eq!(
            mock.playback_info_hit_count(),
            1,
            "preload should still negotiate once"
        );
        assert!(
            core.lock_state().preload_cache.is_none(),
            "a Transcode decision must never be cached in Direct Play mode"
        );

        // prepare_playback runs its own uncached negotiation as if preload
        // had never run.
        let err = core
            .prepare_playback_now("item-1".to_string(), false)
            .expect_err("Direct Play mode refuses a Transcode decision outright");
        assert!(matches!(err, CoreError::WouldTranscode { .. }));
        assert_eq!(mock.playback_info_hit_count(), 2);
    }

    /// Cap mode always forces a transcode negotiation, so a speculative
    /// preload is a complete no-op, never hitting the network.
    #[test]
    fn preload_playback_is_a_no_op_in_cap_mode() {
        let (item_json, playback_info_json) = direct_play_fixture();
        let (_dir, core, mock) = core_signed_in_against_mock(item_json, playback_info_json);
        let mut settings = core.get_settings();
        settings.playback_quality = PlaybackQuality::Cap { max_bps: 8_000_000 };
        core.set_settings(settings);

        core.preload_playback("item-1".to_string(), None);

        assert_eq!(
            mock.playback_info_hit_count(),
            0,
            "Cap mode must not even negotiate on a speculative preload"
        );
        assert!(core.lock_state().preload_cache.is_none());
    }

    /// Cap mode always transcodes, even a source this TV could direct play
    /// -- pins the forced negotiation and the fixed cap reason.
    #[test]
    fn prepare_playback_cap_mode_always_transcodes_even_a_compatible_source() {
        let (item_json, playback_info_json) = compatible_codec_transcode_fixture();
        let (_dir, core, mock) = core_signed_in_against_mock(item_json, playback_info_json);
        let mut settings = core.get_settings();
        settings.playback_quality = PlaybackQuality::Cap { max_bps: 8_000_000 };
        core.set_settings(settings);

        let plan = core
            .prepare_playback_now("item-1".to_string(), false)
            .expect("Cap mode always produces a plan, never WouldTranscode");

        assert_eq!(mock.playback_info_hit_count(), 1);
        let bodies = mock.playback_info_bodies();
        assert_eq!(
            bodies[0]["EnableDirectPlay"], false,
            "Cap mode must force the negotiation"
        );
        assert_eq!(bodies[0]["EnableDirectStream"], false);
        assert_eq!(plan.play_method, PlayMethodFfi::Transcode);
        assert_eq!(plan.transcode_reason.as_deref(), Some("quality cap 8 Mbps"));
        assert!(plan.server_verdict.is_none());
        assert!(
            plan.url.contains("master.m3u8"),
            "expected the server's HLS url: {}",
            plan.url
        );
    }

    /// `cap_transcode_reason`'s non-whole-Mbps formatting, exercised end to end.
    #[test]
    fn prepare_playback_cap_mode_reason_uses_one_decimal_for_a_non_whole_mbps_cap() {
        let (item_json, playback_info_json) = compatible_codec_transcode_fixture();
        let (_dir, core, _mock) = core_signed_in_against_mock(item_json, playback_info_json);
        let mut settings = core.get_settings();
        settings.playback_quality = PlaybackQuality::Cap { max_bps: 1_500_000 };
        core.set_settings(settings);

        let plan = core
            .prepare_playback_now("item-1".to_string(), false)
            .expect("Cap mode always produces a plan");
        assert_eq!(
            plan.transcode_reason.as_deref(),
            Some("quality cap 1.5 Mbps")
        );
    }

    /// docs/18-playback-quality.md §2: `set_settings` always invalidates
    /// any preload result, so a plan negotiated under the old Quality
    /// setting can never reach `prepare_playback`.
    #[test]
    fn set_settings_invalidates_a_preload_cached_under_the_old_playback_quality() {
        let (item_json, playback_info_json) = compatible_codec_transcode_fixture();
        let (_dir, core, mock) = core_signed_in_against_mock(item_json, playback_info_json);

        let mut settings = core.get_settings();
        settings.playback_quality = PlaybackQuality::Auto;
        core.set_settings(settings);

        core.preload_playback("item-1".to_string(), None);
        wait_for_preload_idle(&core);
        assert_eq!(
            mock.playback_info_hit_count(),
            1,
            "preload should negotiate once"
        );
        assert!(
            core.lock_state().preload_cache.is_some(),
            "an Auto-mode attempt-anyway DirectPlay plan should be cached"
        );

        let mut settings = core.get_settings();
        settings.playback_quality = PlaybackQuality::Cap { max_bps: 3_000_000 };
        core.set_settings(settings);
        assert!(
            core.lock_state().preload_cache.is_none(),
            "set_settings must invalidate any preload cached under the old quality"
        );

        let plan = core
            .prepare_playback_now("item-1".to_string(), false)
            .expect("Cap mode always produces a plan, not an error");
        assert_eq!(
            mock.playback_info_hit_count(),
            2,
            "the cache miss must re-negotiate under the new Cap setting, never reuse the plan \
             cached under Auto"
        );
        assert_eq!(
            plan.play_method,
            PlayMethodFfi::Transcode,
            "a plan built from the stale Auto-mode cache would have wrongly Direct Played \
             this file"
        );
        let bodies = mock.playback_info_bodies();
        assert_eq!(
            bodies[1]["EnableDirectPlay"], false,
            "the re-negotiation under Cap mode must be forced"
        );
    }

    /// Direct Play mode always refuses; nothing touches the network, so no
    /// mock server is needed.
    #[test]
    fn prepare_transcode_fallback_refuses_in_direct_play_mode() {
        let (_dir, core) = core_in_tempdir();
        // No active session exists, so `play_session_id` is unreachable --
        // the DirectPlay-mode refusal returns first.
        let err = core
            .prepare_transcode_fallback_now(
                "item-1".to_string(),
                50_000_000,
                "decoder error".to_string(),
                "irrelevant-session".to_string(),
            )
            .expect_err("Direct Play mode must refuse the fallback outright");
        match err {
            CoreError::WouldTranscode { reasons } => assert_eq!(reasons, "decoder error"),
            other => panic!("expected WouldTranscode, got {other:?}"),
        }
    }

    /// Auto-mode happy path end to end: an initial prepare_playback
    /// establishes a session, then prepare_transcode_fallback ends it and
    /// re-negotiates with EnableDirectPlay/Stream false and the resume position.
    #[test]
    fn prepare_transcode_fallback_negotiates_force_transcode_and_starts_at_the_given_position() {
        let (item_json, direct_play_json) = direct_play_fixture();
        let (_unused_item_json, transcode_json) = transcode_fixture();
        let (_dir, core, mock) = core_signed_in_against_mock_with_responses(
            item_json,
            vec![direct_play_json, transcode_json],
            Duration::ZERO,
        );

        let mut settings = core.get_settings();
        settings.playback_quality = PlaybackQuality::Auto;
        core.set_settings(settings);

        let first_plan = core
            .prepare_playback_now("item-1".to_string(), false)
            .expect("initial Direct Play negotiation");
        assert_eq!(first_plan.play_method, PlayMethodFfi::DirectPlay);
        assert_eq!(mock.playback_info_hit_count(), 1);

        let fallback_plan = core
            .prepare_transcode_fallback_now(
                "item-1".to_string(),
                12_345_678,
                "decoder error".to_string(),
                first_plan.play_session_id.clone(),
            )
            .expect("Auto mode should negotiate a real transcode");

        assert_eq!(mock.playback_info_hit_count(), 2);
        assert_eq!(
            core.lock_state().playback_owner.claim,
            None,
            "an install consumes its claim"
        );
        assert_eq!(fallback_plan.play_method, PlayMethodFfi::Transcode);
        assert_eq!(
            fallback_plan.transcode_reason.as_deref(),
            Some("decoder error")
        );
        assert_eq!(fallback_plan.start_position_ticks, 12_345_678);
        assert!(
            fallback_plan.url.contains("master.m3u8"),
            "expected the server's HLS url: {}",
            fallback_plan.url
        );

        let bodies = mock.playback_info_bodies();
        assert_eq!(
            bodies.len(),
            2,
            "expected exactly two PlaybackInfo requests"
        );
        let forced_body = &bodies[1];
        assert_eq!(forced_body["EnableDirectPlay"], false);
        assert!(
            !offers_ass_sidecars(forced_body),
            "a forced transcode must keep burning embedded ASS in (docs/18 §3.1)"
        );
        assert_eq!(forced_body["EnableDirectStream"], false);
        assert_eq!(forced_body["StartTimeTicks"], 12_345_678);
        // The first, unforced negotiation must NOT have asked for a forced transcode.
        assert!(bodies[0].get("EnableDirectPlay").is_none());

        let state = core.lock_state();
        let session = state
            .reporting
            .as_ref()
            .expect("prepare_transcode_fallback must leave a fresh reporting session active");
        assert_eq!(
            session.context().play_method,
            jellyfin_api::ReportPlayMethod::Transcode
        );
    }

    /// Defensive path: if a forced negotiation somehow still comes back
    /// `DirectPlay`, prepare_transcode_fallback refuses rather than
    /// returning a contradictory plan.
    #[test]
    fn prepare_transcode_fallback_errors_when_the_forced_negotiation_is_not_a_transcode() {
        let (item_json, direct_play_json) = direct_play_fixture();
        let (_dir, core, mock) = core_signed_in_against_mock_with_responses(
            item_json,
            vec![direct_play_json.clone(), direct_play_json],
            Duration::ZERO,
        );

        let mut settings = core.get_settings();
        settings.playback_quality = PlaybackQuality::Auto;
        core.set_settings(settings);

        let first_plan = core
            .prepare_playback_now("item-1".to_string(), false)
            .expect("initial Direct Play negotiation");

        let err = core
            .prepare_transcode_fallback_now(
                "item-1".to_string(),
                1_000_000,
                "decoder error".to_string(),
                first_plan.play_session_id.clone(),
            )
            .expect_err("a DirectPlay decision from a forced negotiation must error");
        assert!(matches!(err, CoreError::Api { .. }));
        assert_eq!(mock.playback_info_hit_count(), 2);
    }

    /// docs/18 §3.2: a fallback stops the old session on its way in, so a fallback that then
    /// fails leaves no session and must leave no sidecars either; the old id can't fetch.
    #[test]
    fn a_failed_transcode_fallback_leaves_no_sidecars_behind_for_the_stopped_session() {
        let (item_json, direct_play_json) = direct_play_fixture();
        let (_dir, core, _mock) = core_signed_in_against_mock_with_responses(
            item_json,
            vec![direct_play_json.clone(), direct_play_json],
            Duration::ZERO,
        );
        let mut settings = core.get_settings();
        settings.playback_quality = PlaybackQuality::Auto;
        core.set_settings(settings);
        let first_plan = core
            .prepare_playback_now("item-1".to_string(), false)
            .expect("initial Direct Play negotiation");
        core.lock_state().sidecars = Some(test_sidecars(&first_plan.play_session_id));

        core.prepare_transcode_fallback_now(
            "item-1".to_string(),
            1_000_000,
            "decoder error".to_string(),
            first_plan.play_session_id.clone(),
        )
        .expect_err("a DirectPlay decision from a forced negotiation must error");

        let state = core.lock_state();
        assert!(state.reporting.is_none(), "the old session was stopped");
        assert!(state.sidecars.is_none(), "and its sidecars went with it");
        drop(state);
        assert!(
            core.fetch_external_subtitle(first_plan.play_session_id, 3)
                .is_none(),
            "the stopped session's id fetches nothing"
        );
    }

    /// A codec-blind source with a `TranscodingUrl` still produces a
    /// working Transcode plan.
    #[test]
    fn prepare_transcode_fallback_succeeds_for_a_codec_blind_source() {
        let (item_json, direct_play_json) = direct_play_fixture();
        let (_unused_item_json, codec_blind_json) = codec_blind_fallback_fixture();
        let (_dir, core, mock) = core_signed_in_against_mock_with_responses(
            item_json,
            vec![direct_play_json, codec_blind_json],
            Duration::ZERO,
        );

        let mut settings = core.get_settings();
        settings.playback_quality = PlaybackQuality::Auto;
        core.set_settings(settings);

        let first_plan = core
            .prepare_playback_now("item-1".to_string(), false)
            .expect("initial Direct Play negotiation");
        assert_eq!(mock.playback_info_hit_count(), 1);

        let fallback_plan = core
            .prepare_transcode_fallback_now(
                "item-1".to_string(),
                5_000_000,
                "decoder error".to_string(),
                first_plan.play_session_id.clone(),
            )
            .expect("a codec-blind source's DirectPlay decision must still satisfy the fallback");

        assert_eq!(mock.playback_info_hit_count(), 2);
        assert_eq!(fallback_plan.play_method, PlayMethodFfi::Transcode);
        assert_eq!(
            fallback_plan.transcode_reason.as_deref(),
            Some("decoder error")
        );
        assert!(
            fallback_plan.url.ends_with("/videos/ms-3/master.m3u8"),
            "expected the server's HLS url: {}",
            fallback_plan.url
        );
    }

    /// A `play_session_id` not naming the active session is rejected before
    /// touching the network or session at all.
    #[test]
    fn prepare_transcode_fallback_with_a_stale_play_session_id_is_rejected_without_touching_the_active_session(
    ) {
        let (item_json, direct_play_json) = direct_play_fixture();
        let (_dir, core, mock) = core_signed_in_against_mock(item_json, direct_play_json);

        let mut settings = core.get_settings();
        settings.playback_quality = PlaybackQuality::Auto;
        core.set_settings(settings);

        let first_plan = core
            .prepare_playback_now("item-1".to_string(), false)
            .expect("initial Direct Play negotiation");
        assert_eq!(mock.playback_info_hit_count(), 1);
        assert_ne!(
            first_plan.play_session_id, "not-the-active-session",
            "fixture sanity: the stale id below must genuinely differ from the real one"
        );

        let err = core
            .prepare_transcode_fallback_now(
                "item-1".to_string(),
                10_000_000,
                "decoder error".to_string(),
                "not-the-active-session".to_string(),
            )
            .expect_err("a play_session_id that isn't the active session must be rejected");
        assert!(matches!(err, CoreError::StalePlaybackSession));
        assert_eq!(
            mock.playback_info_hit_count(),
            1,
            "a rejected fallback must never negotiate a forced transcode"
        );

        let state = core.lock_state();
        let session = state
            .reporting
            .as_ref()
            .expect("the real active session must be untouched by the rejected fallback");
        assert_eq!(
            session.context().play_session_id,
            first_plan.play_session_id
        );
        assert_eq!(
            session.context().play_method,
            jellyfin_api::ReportPlayMethod::DirectPlay
        );
    }

    /// The fallback race itself: while A's forced negotiation is held up,
    /// B's prepare_playback installs its own session; A's delayed result
    /// must detect the generation moved on and report
    /// `StalePlaybackSession` rather than clobbering B.
    #[test]
    fn prepare_transcode_fallback_loses_the_race_to_a_newer_prepare_playback_and_is_reported_stale()
    {
        let (item_json, direct_play_json_a) = direct_play_fixture();
        let (_unused_item_json, transcode_json) = transcode_fixture();
        let (_unused_item_json2, direct_play_json_b) = second_item_direct_play_fixture();
        let (_dir, core, mock) = core_signed_in_against_mock_with_responses_and_delays(
            item_json,
            vec![direct_play_json_a, transcode_json, direct_play_json_b],
            vec![Duration::ZERO, Duration::from_millis(200), Duration::ZERO],
        );

        let mut settings = core.get_settings();
        settings.playback_quality = PlaybackQuality::Auto;
        core.set_settings(settings);

        let first_plan = core
            .prepare_playback_now("item-1".to_string(), false)
            .expect("initial Direct Play negotiation for item A");
        assert_eq!(mock.playback_info_hit_count(), 1);

        let core_for_fallback = Arc::clone(&core);
        let fallback_session_id = first_plan.play_session_id.clone();
        let fallback_handle = std::thread::spawn(move || {
            core_for_fallback.prepare_transcode_fallback_now(
                "item-1".to_string(),
                20_000_000,
                "decoder error".to_string(),
                fallback_session_id,
            )
        });

        // Blocks until A's request has reached the mock, so the next call
        // is guaranteed the third arrival.
        wait_for_playback_info_hits(&mock, 2);

        let second_plan = core
            .prepare_playback_now("item-2".to_string(), false)
            .expect(
                "a different item's prepare_playback must succeed while A's fallback negotiates",
            );
        assert_eq!(second_plan.play_method, PlayMethodFfi::DirectPlay);
        assert_eq!(mock.playback_info_hit_count(), 3);

        let fallback_result = fallback_handle
            .join()
            .expect("the fallback thread must not panic");
        assert!(
            matches!(fallback_result, Err(CoreError::StalePlaybackSession)),
            "a fallback superseded mid-negotiation by a different item's prepare_playback must \
             be reported stale, got {fallback_result:?}"
        );

        let state = core.lock_state();
        let session = state
            .reporting
            .as_ref()
            .expect("item B's session must still be active");
        assert_eq!(
            session.context().play_session_id,
            second_plan.play_session_id,
            "the stale fallback must not have clobbered item B's session"
        );
        assert_eq!(session.context().item_id, "item-2");
        assert_eq!(
            session.context().play_method,
            jellyfin_api::ReportPlayMethod::DirectPlay
        );
    }

    /// Same race as above, for prepare_playback itself: A's delayed install
    /// must notice the generation moved on and abandon rather than clobber B.
    #[test]
    fn prepare_playback_loses_the_race_to_a_newer_prepare_playback_and_is_reported_stale() {
        let (item_json, direct_play_json_a) = direct_play_fixture();
        let (_unused_item_json, direct_play_json_b) = second_item_direct_play_fixture();
        let (_dir, core, mock) = core_signed_in_against_mock_with_responses_and_delays(
            item_json,
            vec![direct_play_json_a, direct_play_json_b],
            vec![Duration::from_millis(200), Duration::ZERO],
        );

        let core_for_a = Arc::clone(&core);
        let a_handle = std::thread::spawn(move || {
            core_for_a.prepare_playback_now("item-1".to_string(), false)
        });

        // Blocks until A's request has reached the mock, so B is guaranteed
        // the second arrival.
        wait_for_playback_info_hits(&mock, 1);

        let b_plan = core
            .prepare_playback_now("item-2".to_string(), false)
            .expect("a different item's prepare_playback must succeed while A's negotiates");
        assert_eq!(b_plan.play_method, PlayMethodFfi::DirectPlay);
        assert_eq!(mock.playback_info_hit_count(), 2);
        let owner_after_b = owner_view(&core);

        let a_result = a_handle.join().expect("thread A must not panic");
        assert!(
            matches!(a_result, Err(CoreError::StalePlaybackSession)),
            "A's late-completing prepare_playback must be reported stale, got {a_result:?}"
        );

        let state = core.lock_state();
        let session = state
            .reporting
            .as_ref()
            .expect("item B's session must still be active");
        assert_eq!(
            session.context().play_session_id,
            b_plan.play_session_id,
            "the stale A completion must not have clobbered item B's session"
        );
        assert_eq!(session.context().item_id, "item-2");
        drop(state);
        assert_eq!(
            owner_view(&core),
            owner_after_b,
            "a refused install leaves ownership alone"
        );
    }

    /// `install_prepared_session` unit-tested directly: a stale `attempt`
    /// must reject and abandon the session, never install it.
    #[test]
    fn install_claimed_session_refuses_a_claim_that_is_no_longer_the_owner() {
        let (dir, core) = core_in_tempdir();
        let client = jellyfin_api::JellyfinClient::from_token(
            "http://example.invalid",
            core.client_identity("http://example.test"),
            "tok",
        );
        let ctx = jellyfin_core::ReportContext {
            item_id: "item-1".to_string(),
            media_source_id: "ms-1".to_string(),
            play_session_id: "attempt-a".to_string(),
            play_method: jellyfin_api::ReportPlayMethod::DirectPlay,
        };
        let older = admit(&core);
        let _newer = admit(&core);

        let result =
            core.install_claimed_session(&older, &client, ctx, Some(test_sidecars("attempt-a")));
        assert!(
            matches!(result, Err(CoreError::StalePlaybackSession)),
            "a superseded claim must be refused, got {result:?}"
        );
        let state = core.lock_state();
        assert!(
            state.reporting.is_none(),
            "a rejected session must never be installed"
        );
        assert!(state.sidecars.is_none(), "nor its sidecars");
        drop(state);

        let _dir = dir; // keep the tempdir alive for the duration of this test
    }

    /// docs/18 §3.2: the sidecars are installed with their session and leave with it, so a fetch
    /// for a stopped session finds nothing to fetch.
    #[test]
    fn sidecars_are_installed_with_the_session_and_cleared_when_it_is_abandoned() {
        let (dir, core) = core_in_tempdir();
        let client = jellyfin_api::JellyfinClient::from_token(
            "http://example.invalid",
            core.client_identity("http://example.test"),
            "tok",
        );
        let ctx = jellyfin_core::ReportContext {
            item_id: "item-1".to_string(),
            media_source_id: "ms-1".to_string(),
            play_session_id: "attempt-a".to_string(),
            play_method: jellyfin_api::ReportPlayMethod::DirectPlay,
        };
        let claim = admit(&core);
        core.install_claimed_session(&claim, &client, ctx, Some(test_sidecars("attempt-a")))
            .expect("the current claim installs");
        assert!(
            core.lock_state()
                .sidecars
                .as_ref()
                .is_some_and(|s| s.url_for("attempt-a", 3).is_some()),
            "installed with the session"
        );

        core.abandon_playback("attempt-a".to_string());
        assert!(core.lock_state().reporting.is_none());
        assert!(
            core.lock_state().sidecars.is_none(),
            "cleared with the session"
        );

        let _dir = dir;
    }

    fn test_sidecars(play_session_id: &str) -> crate::types::SessionSidecars {
        crate::types::SessionSidecars {
            play_session_id: play_session_id.to_string(),
            sources: vec![crate::types::ExternalSubtitleSource {
                url:
                    "http://example.invalid/Videos/item-1/ms-1/Subtitles/3/0/Stream.srt?ApiKey=tok"
                        .to_string(),
                track: crate::types::ExternalSubtitleFfi {
                    index: 3,
                    codec: "srt".to_string(),
                    language: None,
                    display_title: None,
                    is_default: false,
                    is_forced: false,
                },
            }],
        }
    }

    #[test]
    fn removing_the_active_account_clears_the_preload_cache() {
        let (dir, core) = core_in_tempdir();
        let saved = session::SessionFile {
            server_url: "http://example.invalid".to_string(),
            user_id: "u1".to_string(),
            user_name: "jellybeam-user".to_string(),
            token: "tok".to_string(),
            device_id: "dev".to_string(),
            mirror_dir: "mirror".to_string(),
            server_version: None,
            server_name: None,
        };
        session::add_or_update(dir.path(), saved).expect("seed a session file");
        assert!(core.restore_session().is_some());
        core.lock_state().preload_cache = Some(fake_preload_cache("item-1"));

        remove_active_account(&core);

        assert!(core.lock_state().preload_cache.is_none());
    }

    #[test]
    fn switch_session_clears_the_preload_cache() {
        let (dir, core) = core_in_tempdir();
        seed_session(dir.path(), "http://server-a.test", "u1");
        seed_session(dir.path(), "http://server-b.test", "u2");
        core.lock_state().preload_cache = Some(fake_preload_cache("item-1"));

        core.switch_session(0).expect("index 0 exists");

        assert!(core.lock_state().preload_cache.is_none());
    }

    #[test]
    fn remove_session_clears_the_preload_cache_when_removing_the_active_account() {
        let (dir, core) = core_in_tempdir();
        seed_session(dir.path(), "http://server-a.test", "u1");
        core.lock_state().preload_cache = Some(fake_preload_cache("item-1"));

        core.remove_session(0).expect("index 0 exists");

        assert!(core.lock_state().preload_cache.is_none());
    }

    #[test]
    fn install_authenticated_session_clears_the_preload_cache() {
        let (dir, core) = core_in_tempdir();
        let target = seed_session(dir.path(), "http://server-a.test", "u1");
        core.lock_state().preload_cache = Some(fake_preload_cache("item-1"));

        let client = jellyfin_api::JellyfinClient::from_token(
            &target.server_url,
            core.client_identity("http://example.test"),
            "new-token",
        )
        .with_user_id("u1");
        core.install_reauthenticated_session(
            0,
            target,
            client,
            authentication_result("new-token", "renamed-user"),
        )
        .expect("same Jellyfin user may refresh its credential");

        assert!(core.lock_state().preload_cache.is_none());
    }

    /// docs/18 §2: the fallback renegotiates the same source and never asks for a copy of the
    /// stream type that failed, so the server re-encodes it.
    #[test]
    fn a_fallback_names_its_source_and_forbids_copying_the_failed_stream() {
        let cases = [
            (Some(FailedTrackFfi::Video), Some(false), None, Some(-1)),
            (Some(FailedTrackFfi::Unknown), Some(false), None, Some(2)),
            (Some(FailedTrackFfi::Audio), None, Some(false), None),
            (None, None, None, Some(-1)),
        ];
        for (failed, video_copy, audio_copy, subtitle) in cases {
            let (_dir, core, mock) =
                auto_core(vec![direct_play_fixture().1, transcode_fixture().1]);
            let a = core
                .prepare_playback_now("item-1".to_string(), false)
                .expect("A plays");
            let request = core.next_request();
            core.prepare_transcode_fallback(
                "item-1".to_string(),
                0,
                "decoder error".to_string(),
                a.play_session_id,
                failed,
                subtitle,
                request,
            )
            .expect("the fallback negotiates");

            let body = &mock.playback_info_bodies()[1];
            let flag = |value: Option<bool>| {
                value.map_or(serde_json::Value::Null, serde_json::Value::Bool)
            };
            assert_eq!(body["MediaSourceId"], a.media_source_id, "{failed:?}");
            assert_eq!(body["AllowVideoStreamCopy"], flag(video_copy), "{failed:?}");
            assert_eq!(body["AllowAudioStreamCopy"], flag(audio_copy), "{failed:?}");
            let index = subtitle.map_or(serde_json::Value::Null, serde_json::Value::from);
            assert_eq!(body["SubtitleStreamIndex"], index, "{failed:?}");
        }
    }

    // ---- docs/18 §2.1 playback ownership: every ordering below is driven by gates ----

    /// A real account change: the saved account re-authenticates with a new credential, which goes
    /// through the same reset as any sign-in.
    fn change_account(core: &JellybeamCore) {
        let target = session::load(core.data_dir()).expect("a saved account");
        let client = jellyfin_api::JellyfinClient::from_token(
            &target.server_url,
            core.client_identity("http://example.test"),
            "new-token",
        )
        .with_user_id(&target.user_id);
        core.install_reauthenticated_session(
            0,
            target,
            client,
            authentication_result("new-token", "renamed-user"),
        )
        .expect("same user refreshes its credential");
        core.open_mirror()
            .expect("the account change opens the mirror");
    }

    /// Switches to a second saved account on another server; returns its list index.
    fn switch_to_other_account(core: &JellybeamCore) -> u32 {
        let other = seed_session(core.data_dir(), "http://other-server.invalid", "u-other");
        let index = index_of(core, &other);
        core.switch_session(index).expect("switches");
        index
    }

    /// Signed in against the mock in Auto mode, so a fallback may negotiate.
    fn auto_core(
        playback_info_responses: Vec<serde_json::Value>,
    ) -> (tempfile::TempDir, Arc<JellybeamCore>, PlaybackMockServer) {
        let (item_json, _) = direct_play_fixture();
        let (dir, core, mock) = core_signed_in_against_mock_with_responses_and_delays(
            item_json,
            playback_info_responses,
            Vec::new(),
        );
        let mut settings = core.get_settings();
        settings.playback_quality = PlaybackQuality::Auto;
        core.set_settings(settings);
        (dir, core, mock)
    }

    fn spawn_prepare(
        core: &Arc<JellybeamCore>,
        item_id: &str,
    ) -> std::thread::JoinHandle<Result<PlaybackPlan, CoreError>> {
        let core = Arc::clone(core);
        let item_id = item_id.to_string();
        std::thread::spawn(move || core.prepare_playback_now(item_id, false))
    }

    fn spawn_fallback(
        core: &Arc<JellybeamCore>,
        play_session_id: &str,
    ) -> std::thread::JoinHandle<Result<PlaybackPlan, CoreError>> {
        let core = Arc::clone(core);
        let play_session_id = play_session_id.to_string();
        std::thread::spawn(move || {
            core.prepare_transcode_fallback_now(
                "item-1".to_string(),
                20_000_000,
                "decoder error".to_string(),
                play_session_id,
            )
        })
    }

    fn join(
        handle: std::thread::JoinHandle<Result<PlaybackPlan, CoreError>>,
    ) -> Result<PlaybackPlan, CoreError> {
        handle.join().expect("the playback thread must not panic")
    }

    fn active_play_session_id(core: &JellybeamCore) -> Option<String> {
        core.lock_state()
            .reporting
            .as_ref()
            .map(|session| session.context().play_session_id.clone())
    }

    /// Exactly `expected` sessions ever sent Start. The short settle is the only way to observe
    /// an absent request; a started actor sends Start at once on loopback.
    fn assert_start_reports(mock: &PlaybackMockServer, expected: usize) {
        wait_until(
            || mock.start_reports() >= expected,
            "Start reports to arrive",
        );
        std::thread::sleep(Duration::from_millis(150));
        assert_eq!(
            mock.start_reports(),
            expected,
            "only sessions that were installed may ever send Start"
        );
    }

    #[test]
    fn stopping_or_abandoning_the_session_a_fallback_retired_revokes_the_fallback() {
        for abandon in [false, true] {
            let (_dir, core, mock) =
                auto_core(vec![direct_play_fixture().1, transcode_fixture().1]);
            let a = core
                .prepare_playback_now("item-1".to_string(), false)
                .expect("A plays");
            let held = mock.hold_playback_info(1);
            let fallback = spawn_fallback(&core, &a.play_session_id);
            wait_for_playback_info_hits(&mock, 2);

            if abandon {
                core.abandon_playback(a.play_session_id.clone());
            } else {
                core.stop_playback(a.play_session_id.clone(), 0);
            }
            held.open();

            let result = join(fallback);
            assert!(
                matches!(result, Err(CoreError::StalePlaybackSession)),
                "abandon={abandon}: a revoked fallback is stale, got {result:?}"
            );
            assert_eq!(active_play_session_id(&core), None, "abandon={abandon}");
            assert!(core.lock_state().sidecars.is_none(), "abandon={abandon}");
            assert_start_reports(&mock, 1);
        }
    }

    /// Final positions reported for `play_session_id`, in arrival order.
    fn stopped_positions(mock: &PlaybackMockServer, play_session_id: &str) -> Vec<i64> {
        mock.routes
            .request_bodies_containing("POST", "/Sessions/Playing/Stopped")
            .iter()
            .filter(|body| body["PlaySessionId"] == play_session_id)
            .filter_map(|body| body["PositionTicks"].as_i64())
            .collect()
    }

    /// docs/18 §2.1: the session a fallback took over keeps reporting until the claim resolves, and
    /// each way the claim resolves ends it at the right position, exactly once.
    #[test]
    fn a_retired_session_ends_once_where_the_viewer_left_it() {
        // (how the fallback resolves, expected final position of the retired session)
        enum Resolve {
            Installs,
            ViewerStops,
            Abandoned,
            NegotiationFails,
            NewerRequest,
            AccountChanges,
        }
        let fallback_ticks = 20_000_000;
        let reported_ticks = 23_000_000; // its stream plays on while the fallback negotiates
        let exit_ticks = 27_000_000;
        for (resolve, expected) in [
            (Resolve::Installs, fallback_ticks),
            (Resolve::ViewerStops, exit_ticks),
            (Resolve::Abandoned, reported_ticks),
            (Resolve::NegotiationFails, reported_ticks),
            (Resolve::NewerRequest, reported_ticks),
            (Resolve::AccountChanges, reported_ticks),
        ] {
            let negotiated = match resolve {
                Resolve::NegotiationFails => {
                    serde_json::json!({ "MediaSources": [], "PlaySessionId": "x" })
                }
                _ => transcode_fixture().1,
            };
            let (_dir, core, mock) = auto_core(vec![
                direct_play_fixture().1,
                negotiated,
                second_item_direct_play_fixture().1,
            ]);
            let a = core
                .prepare_playback_now("item-1".to_string(), false)
                .expect("A plays");
            let held = mock.hold_playback_info(1);
            let fallback = spawn_fallback(&core, &a.play_session_id);
            wait_for_playback_info_hits(&mock, 2);
            std::thread::sleep(Duration::from_millis(100));
            assert!(
                stopped_positions(&mock, &a.play_session_id).is_empty(),
                "A keeps reporting while the fallback negotiates"
            );
            core.report_position(a.play_session_id.clone(), reported_ticks);

            match resolve {
                Resolve::ViewerStops => core.stop_playback(a.play_session_id.clone(), exit_ticks),
                Resolve::Abandoned => core.abandon_playback(a.play_session_id.clone()),
                Resolve::NewerRequest => {
                    join(spawn_prepare(&core, "item-2")).expect("C installs");
                }
                Resolve::AccountChanges => remove_active_account(&core),
                Resolve::Installs | Resolve::NegotiationFails => {}
            }
            if matches!(resolve, Resolve::NewerRequest | Resolve::AccountChanges) {
                // Ended at once, not whenever the superseded fallback's negotiation returns.
                wait_until(
                    || !stopped_positions(&mock, &a.play_session_id).is_empty(),
                    "the retired session to end while its fallback is still negotiating",
                );
            }
            held.open();
            let _ = join(fallback);

            wait_until(
                || !stopped_positions(&mock, &a.play_session_id).is_empty(),
                "the retired session's final report",
            );
            std::thread::sleep(Duration::from_millis(150));
            assert_eq!(
                stopped_positions(&mock, &a.play_session_id),
                vec![expected],
                "{} final position",
                match resolve {
                    Resolve::Installs => "install",
                    Resolve::ViewerStops => "viewer stop",
                    Resolve::Abandoned => "abandon",
                    Resolve::NegotiationFails => "failed negotiation",
                    Resolve::NewerRequest => "newer request",
                    Resolve::AccountChanges => "account change",
                }
            );
        }
    }

    /// docs/18 §2.1: a stop the caller sent before it learned the fallback's id still names the
    /// session the fallback replaced, and ends the installed fallback where the viewer left it.
    #[test]
    fn a_stop_naming_the_replaced_session_ends_the_installed_fallback() {
        let (_dir, core, mock) = auto_core(vec![direct_play_fixture().1, transcode_fixture().1]);
        let a = core
            .prepare_playback_now("item-1".to_string(), false)
            .expect("A plays");
        let b = join(spawn_fallback(&core, &a.play_session_id)).expect("the fallback installs");
        assert_eq!(
            active_play_session_id(&core),
            Some(b.play_session_id.clone())
        );

        let exit_ticks = 27_000_000;
        core.stop_playback(a.play_session_id.clone(), exit_ticks);
        core.abandon_playback(b.play_session_id.clone()); // the caller disposing what it never adopted

        assert_eq!(active_play_session_id(&core), None);
        wait_until(
            || !stopped_positions(&mock, &b.play_session_id).is_empty(),
            "the installed fallback's final report",
        );
        std::thread::sleep(Duration::from_millis(150));
        assert_eq!(
            stopped_positions(&mock, &b.play_session_id),
            vec![exit_ticks]
        );
        assert_eq!(
            stopped_positions(&mock, &a.play_session_id).len(),
            1,
            "A ended once, at install"
        );
    }

    #[test]
    fn a_newer_prepare_outranks_a_pending_fallback_in_either_completion_order() {
        for fallback_first in [true, false] {
            let (_dir, core, mock) = auto_core(vec![
                direct_play_fixture().1,
                transcode_fixture().1,
                second_item_direct_play_fixture().1,
            ]);
            let a = core
                .prepare_playback_now("item-1".to_string(), false)
                .expect("A plays");
            let held_fallback = mock.hold_playback_info(1);
            let held_c = mock.hold_playback_info(2);
            let fallback = spawn_fallback(&core, &a.play_session_id);
            wait_for_playback_info_hits(&mock, 2);
            let c = spawn_prepare(&core, "item-2");
            wait_for_playback_info_hits(&mock, 3);

            let (fallback_result, c_result) = if fallback_first {
                held_fallback.open();
                let f = join(fallback);
                held_c.open();
                (f, join(c))
            } else {
                held_c.open();
                let c = join(c);
                held_fallback.open();
                (join(fallback), c)
            };

            assert!(
                matches!(fallback_result, Err(CoreError::StalePlaybackSession)),
                "fallback_first={fallback_first}: got {fallback_result:?}"
            );
            let c_plan = c_result.expect("the newest request installs");
            assert_eq!(active_play_session_id(&core), Some(c_plan.play_session_id));
            assert_start_reports(&mock, 2);
        }
    }

    /// A delayed stop of the session a fallback retired, landing after a newer request claimed,
    /// must not revoke that newer request.
    #[test]
    fn a_late_stop_of_the_retired_session_does_not_revoke_a_newer_prepare() {
        let (_dir, core, mock) = auto_core(vec![
            direct_play_fixture().1,
            transcode_fixture().1,
            second_item_direct_play_fixture().1,
        ]);
        let a = core
            .prepare_playback_now("item-1".to_string(), false)
            .expect("A plays");
        let held_fallback = mock.hold_playback_info(1);
        let held_c = mock.hold_playback_info(2);
        let fallback = spawn_fallback(&core, &a.play_session_id);
        wait_for_playback_info_hits(&mock, 2);
        let c = spawn_prepare(&core, "item-2");
        wait_for_playback_info_hits(&mock, 3);

        core.stop_playback(a.play_session_id, 0);
        held_fallback.open();
        held_c.open();

        assert!(matches!(
            join(fallback),
            Err(CoreError::StalePlaybackSession)
        ));
        let c_plan = join(c).expect("C still installs");
        assert_eq!(active_play_session_id(&core), Some(c_plan.play_session_id));
    }

    /// Stopping an installed session retires it without revoking a newer pending prepare.
    #[test]
    fn stopping_the_installed_session_keeps_a_pending_prepare() {
        let (_dir, core, mock) = auto_core(vec![
            direct_play_fixture().1,
            second_item_direct_play_fixture().1,
        ]);
        let a = core
            .prepare_playback_now("item-1".to_string(), false)
            .expect("A plays");
        let held_c = mock.hold_playback_info(1);
        let c = spawn_prepare(&core, "item-2");
        wait_for_playback_info_hits(&mock, 2);

        core.stop_playback(a.play_session_id, 0);
        assert_eq!(active_play_session_id(&core), None, "A is retired at once");
        held_c.open();

        let c_plan = join(c).expect("C installs");
        assert_eq!(active_play_session_id(&core), Some(c_plan.play_session_id));
    }

    /// A stop naming a session from two sessions ago is not the fallback's retired session.
    #[test]
    fn a_stop_for_an_older_session_does_not_revoke_a_pending_fallback() {
        let (_dir, core, mock) = auto_core(vec![
            direct_play_fixture().1,
            second_item_direct_play_fixture().1,
            transcode_fixture().1,
        ]);
        let older = core
            .prepare_playback_now("item-1".to_string(), false)
            .expect("the older session plays");
        let a = core
            .prepare_playback_now("item-1".to_string(), false)
            .expect("A replaces it");
        assert_ne!(older.play_session_id, a.play_session_id);
        let held = mock.hold_playback_info(2);
        let fallback = spawn_fallback(&core, &a.play_session_id);
        wait_for_playback_info_hits(&mock, 3);

        core.stop_playback(older.play_session_id, 0);
        held.open();

        let plan = join(fallback).expect("the fallback still installs");
        assert_eq!(active_play_session_id(&core), Some(plan.play_session_id));
    }

    #[test]
    fn the_newest_of_two_prepares_wins_in_either_completion_order() {
        for older_first in [true, false] {
            let (_dir, core, mock) = auto_core(vec![
                direct_play_fixture().1,
                second_item_direct_play_fixture().1,
            ]);
            let held_older = mock.hold_playback_info(0);
            let held_newer = mock.hold_playback_info(1);
            let older = spawn_prepare(&core, "item-1");
            wait_for_playback_info_hits(&mock, 1);
            let newer = spawn_prepare(&core, "item-2");
            wait_for_playback_info_hits(&mock, 2);

            let (older_result, newer_result) = if older_first {
                held_older.open();
                let o = join(older);
                held_newer.open();
                (o, join(newer))
            } else {
                held_newer.open();
                let n = join(newer);
                held_older.open();
                (join(older), n)
            };

            assert!(
                matches!(older_result, Err(CoreError::StalePlaybackSession)),
                "older_first={older_first}: got {older_result:?}"
            );
            let newer_plan = newer_result.expect("the newest request installs");
            assert_eq!(
                active_play_session_id(&core),
                Some(newer_plan.play_session_id)
            );
            assert_start_reports(&mock, 1);
        }
    }

    #[test]
    fn a_newer_prepare_that_fails_does_not_revive_the_older_one() {
        let no_sources = serde_json::json!({ "MediaSources": [], "PlaySessionId": "unused" });
        let (_dir, core, mock) = auto_core(vec![direct_play_fixture().1, no_sources]);
        let held_older = mock.hold_playback_info(0);
        let held_newer = mock.hold_playback_info(1);
        let older = spawn_prepare(&core, "item-1");
        wait_for_playback_info_hits(&mock, 1);
        let newer = spawn_prepare(&core, "item-2");
        wait_for_playback_info_hits(&mock, 2);

        held_newer.open();
        let newer_result = join(newer);
        assert!(
            newer_result.is_err() && !matches!(newer_result, Err(CoreError::StalePlaybackSession)),
            "the newer request fails on its own, got {newer_result:?}"
        );
        held_older.open();

        assert!(matches!(join(older), Err(CoreError::StalePlaybackSession)));
        assert_eq!(active_play_session_id(&core), None);
        assert_start_reports(&mock, 0);
    }

    #[test]
    fn a_request_that_is_not_newer_than_the_last_admitted_is_refused_before_the_network() {
        let (_dir, core, mock) = auto_core(vec![direct_play_fixture().1]);
        let request = core.next_request();
        core.prepare_playback("item-1".to_string(), false, request)
            .expect("first use plays");

        let again = core.prepare_playback("item-1".to_string(), false, request);

        assert!(matches!(again, Err(CoreError::StalePlaybackSession)));
        assert_eq!(mock.playback_info_hit_count(), 1);
    }

    /// docs/18 §2.1: viewer order, not native arrival order, decides -- an older request that
    /// reaches Rust after a newer one is refused before it negotiates.
    #[test]
    fn an_older_request_arriving_after_a_newer_one_is_refused() {
        let (_dir, core, mock) = auto_core(vec![second_item_direct_play_fixture().1]);
        let older = core.next_request();
        let newer = PlaybackRequest {
            seq: older.seq + 1,
            ..older
        };
        core.prepare_playback("item-2".to_string(), false, newer)
            .expect("the newer request plays");

        let result = core.prepare_playback("item-1".to_string(), false, older);

        assert!(
            matches!(result, Err(CoreError::StalePlaybackSession)),
            "got {result:?}"
        );
        assert_eq!(
            mock.playback_info_hit_count(),
            1,
            "the older never negotiated"
        );
    }

    /// Minted before an account change, admitted after it: refused, even when the account
    /// changes back (A -> B -> A).
    #[test]
    fn a_request_minted_under_a_previous_account_is_refused_at_admission() {
        let (_dir, core, mock) = auto_core(vec![direct_play_fixture().1]);
        let request = core.next_request();
        change_account(&core);
        change_account(&core);

        let result = core.prepare_playback("item-1".to_string(), false, request);

        assert!(
            matches!(result, Err(CoreError::AccountChanged)),
            "got {result:?}"
        );
        assert_eq!(mock.playback_info_hit_count(), 0, "never negotiated");
    }

    /// docs/18 §2.1: switching accounts while a playback negotiates lets it install and play on
    /// the account it started on.
    #[test]
    fn a_switch_during_negotiation_installs_on_the_playing_account() {
        let (_dir, core, mock) = auto_core(vec![direct_play_fixture().1]);
        let held = mock.hold_playback_info(0);
        let prepare = spawn_prepare(&core, "item-1");
        wait_for_playback_info_hits(&mock, 1);

        switch_to_other_account(&core);
        held.open();

        let plan = join(prepare).expect("installs on its own account");
        assert_eq!(active_play_session_id(&core), Some(plan.play_session_id));
        assert_start_reports(&mock, 1);
    }

    /// docs/18 §2.1: a playback outlives a switch to another account; its reports, its own
    /// requests and its final writes stay with the account it plays on.
    #[test]
    fn a_switch_keeps_playback_on_its_own_account() {
        let (_dir, core, mock) = auto_core(vec![
            direct_play_fixture().1,
            second_item_direct_play_fixture().1,
        ]);
        let a = core
            .prepare_playback_now("item-1".to_string(), false)
            .expect("A plays");
        let playing_epoch = core.account_epoch();

        let other = switch_to_other_account(&core);

        assert_ne!(core.account_epoch(), playing_epoch);
        assert!(core.owns_playback(playing_epoch));
        assert_eq!(
            active_play_session_id(&core),
            Some(a.play_session_id.clone())
        );
        // Next-up stops the episode before it prepares the next one, under the playing epoch, which
        // negotiates with the mock's account.
        core.stop_playback(a.play_session_id.clone(), 30_000_000);
        let next = core
            .prepare_playback(
                "item-2".to_string(),
                false,
                PlaybackRequest {
                    seq: core.next_request().seq,
                    account_epoch: playing_epoch,
                },
            )
            .expect("next item plays on the same account");
        assert_eq!(mock.playback_info_hit_count(), 2);
        core.stop_playback(next.play_session_id.clone(), 33_000_000);
        wait_until(
            || stopped_positions(&mock, &next.play_session_id) == vec![33_000_000],
            "Stopped reaches the playing account's server",
        );
        assert!(
            core.owns_playback(playing_epoch),
            "kept until its account is left"
        );

        core.remove_session(1 - other)
            .expect("removes the playing account");
        assert!(!core.owns_playback(playing_epoch));
    }

    /// docs/18 §2.1: a playback started on the browsed account after the parked one ended takes
    /// over the parking, so switching again while it negotiates keeps it rather than the old one.
    #[test]
    fn a_newer_playback_takes_over_the_parking_on_the_next_switch() {
        let (_dir, core, mock) = auto_core(vec![
            direct_play_fixture().1,
            second_item_direct_play_fixture().1,
        ]);
        let first = session::load_list(core.data_dir()).sessions[0].clone();
        let other = seed_session(core.data_dir(), &first.server_url, "u-b");
        let a = core
            .prepare_playback_now("item-1".to_string(), false)
            .expect("A plays");
        core.switch_session(index_of(&core, &other))
            .expect("browses B");
        core.open_mirror().expect("B's mirror, as Kotlin opens it");
        assert_eq!(
            core.reauthorization_account(identity_of(&first), false)
                .map(|account| account.user_id),
            Some(first.user_id.clone()),
            "a dead token on A re-authorizes A"
        );
        assert_eq!(
            core.reauthorization_account(identity_of(&other), false)
                .map(|account| account.user_id),
            Some(other.user_id.clone()),
            "the browsed account"
        );
        core.stop_playback(a.play_session_id, 30_000_000);

        let held = mock.hold_playback_info(1);
        let b_epoch = core.account_epoch();
        let b = spawn_prepare(&core, "item-2");
        wait_for_playback_info_hits(&mock, 2);
        core.switch_session(index_of(&core, &first))
            .expect("back to A");
        assert!(
            core.owns_playback(b_epoch),
            "B's negotiation still owns playback"
        );
        held.open();

        let plan = join(b).expect("B installs");
        assert_eq!(active_play_session_id(&core), Some(plan.play_session_id));
        assert!(core.owns_playback(b_epoch));
    }

    /// docs/18 §2.1: switching back to the playing account resolves its parked mirror until Kotlin
    /// reopens the installed one, so next-up in that window still negotiates.
    #[test]
    fn a_switch_back_keeps_the_playing_mirror_until_it_reopens() {
        let (_dir, core, _mock) = auto_core(vec![
            direct_play_fixture().1,
            second_item_direct_play_fixture().1,
        ]);
        let a = core
            .prepare_playback_now("item-1".to_string(), false)
            .expect("A plays");
        let a_epoch = core.account_epoch();
        let other = switch_to_other_account(&core);
        core.switch_session(1 - other)
            .expect("back to A, mirror not reopened yet");
        assert!(core.lock_state().account.bundle().is_none());

        core.stop_playback(a.play_session_id, 30_000_000);
        core.prepare_playback(
            "item-2".to_string(),
            false,
            PlaybackRequest {
                seq: core.next_request().seq,
                account_epoch: a_epoch,
            },
        )
        .expect("next-up negotiates with the parked mirror");
    }

    /// docs/18 §2.1: an account whose playback stopped isn't parked by the switch, so removing it
    /// ends nothing -- a newer playback negotiating on the browsed account installs.
    #[test]
    fn removing_an_account_whose_playback_ended_leaves_the_newer_playback_alone() {
        let (_dir, core, mock) = auto_core(vec![
            direct_play_fixture().1,
            second_item_direct_play_fixture().1,
        ]);
        let first = session::load_list(core.data_dir()).sessions[0].clone();
        let a = core
            .prepare_playback_now("item-1".to_string(), false)
            .expect("A plays");
        core.stop_playback(a.play_session_id, 30_000_000);
        seed_session(core.data_dir(), &first.server_url, "u-b");
        core.switch_session(1).expect("browses B");
        reopen_mirror(&core);
        assert_eq!(
            core.parked_account_epoch(),
            None,
            "A's playback had stopped"
        );

        let held = mock.hold_playback_info(1);
        let b = spawn_prepare(&core, "item-2");
        wait_for_playback_info_hits(&mock, 2);
        core.remove_session(0).expect("removes A");
        held.open();

        let plan = join(b).expect("B's playback installs");
        assert_eq!(active_play_session_id(&core), Some(plan.play_session_id));
        assert_eq!(core.parked_account_epoch(), None);
    }

    /// docs/18 §2.1: a negotiation in flight across a switch parks its account; once it fails,
    /// removing that account drops the parking instead of keeping it for a signed-out one.
    #[test]
    fn signing_out_of_an_account_parked_by_a_failed_negotiation_drops_it() {
        let (item_json, playback_info_json) = transcode_fixture();
        let (_dir, core, mock) = core_signed_in_against_mock(item_json, playback_info_json);
        let held = mock.hold_playback_info(0);
        let a = spawn_prepare(&core, "item-1");
        wait_for_playback_info_hits(&mock, 1);
        let other = switch_to_other_account(&core);
        assert!(
            core.parked_account_epoch().is_some(),
            "its negotiation was in flight"
        );
        held.open();
        join(a).expect_err("Direct Play mode refuses a Transcode decision outright");

        core.switch_session(1 - other).expect("back to A");
        remove_active_account(&core);

        assert_eq!(core.parked_account_epoch(), None);
    }

    /// Opens the browsed account's mirror as Kotlin does after a switch, returning a handle to it.
    fn reopen_mirror(core: &JellybeamCore) -> media_cache::Mirror {
        core.open_mirror()
            .expect("opens, as Kotlin does after a switch");
        installed_mirror(core)
    }

    /// docs/18 §2.1: every mirror -- the playing account's, the browsed one, any opened since --
    /// yields its sync exactly while a stream plays, whichever path ends it. Each row runs on a
    /// core where A plays; `other` is the index B gets once browsed.
    #[test]
    fn sync_yields_exactly_while_a_stream_plays() {
        type Path = fn(&JellybeamCore, String) -> Vec<media_cache::Mirror>;
        let rows: [(&str, Path, bool); 13] = [
            ("plays on A", |_, _| Vec::new(), true),
            (
                "stop",
                |core, id| {
                    core.stop_playback(id, 30_000_000);
                    Vec::new()
                },
                false,
            ),
            (
                "abandon",
                |core, id| {
                    core.abandon_playback(id);
                    Vec::new()
                },
                false,
            ),
            (
                "remove A while browsing it",
                |core, _| {
                    remove_active_account(core);
                    Vec::new()
                },
                false,
            ),
            (
                "browse B",
                |core, _| {
                    switch_to_other_account(core);
                    vec![reopen_mirror(core)]
                },
                true,
            ),
            (
                "browse B, stop",
                |core, id| {
                    switch_to_other_account(core);
                    let browsed = reopen_mirror(core);
                    core.stop_playback(id, 30_000_000);
                    vec![browsed]
                },
                false,
            ),
            (
                "browse B, abandon",
                |core, id| {
                    switch_to_other_account(core);
                    let browsed = reopen_mirror(core);
                    core.abandon_playback(id);
                    vec![browsed]
                },
                false,
            ),
            (
                "browse B, remove A",
                |core, _| {
                    let other = switch_to_other_account(core);
                    let browsed = reopen_mirror(core);
                    core.remove_session(1 - other).expect("removes A");
                    vec![browsed]
                },
                false,
            ),
            (
                "browse B, remove B",
                |core, _| {
                    let other = switch_to_other_account(core);
                    let browsed = reopen_mirror(core);
                    core.remove_session(other).expect("removes B");
                    vec![browsed]
                },
                true,
            ),
            (
                "browse B, then C",
                |core, _| {
                    switch_to_other_account(core);
                    let browsed = reopen_mirror(core);
                    seed_session(core.data_dir(), "http://third-server.invalid", "u-third");
                    core.switch_session(2).expect("browses C");
                    vec![browsed, reopen_mirror(core)]
                },
                true,
            ),
            (
                "browse B, back to A",
                |core, _| {
                    let other = switch_to_other_account(core);
                    let browsed = reopen_mirror(core);
                    core.switch_session(1 - other).expect("back to A");
                    vec![browsed, reopen_mirror(core)]
                },
                true,
            ),
            (
                "browse B, back to A, stop",
                |core, id| {
                    let other = switch_to_other_account(core);
                    let browsed = reopen_mirror(core);
                    core.switch_session(1 - other).expect("back to A");
                    let reopened = reopen_mirror(core);
                    core.stop_playback(id, 30_000_000);
                    vec![browsed, reopened]
                },
                false,
            ),
            (
                "browse B, back to A, remove A",
                |core, _| {
                    let other = switch_to_other_account(core);
                    let browsed = reopen_mirror(core);
                    core.switch_session(1 - other).expect("back to A");
                    let reopened = reopen_mirror(core);
                    remove_active_account(core);
                    vec![browsed, reopened]
                },
                false,
            ),
        ];
        let wrong: Vec<&str> = rows
            .into_iter()
            .filter(|(_, path, plays)| {
                let (_dir, core, _mock) = auto_core(vec![direct_play_fixture().1]);
                let plan = core
                    .prepare_playback_now("item-1".to_string(), false)
                    .expect("A plays");
                let playing = installed_mirror(&core);
                let mut held = path(&core, plan.play_session_id);
                held.push(playing);
                held.iter().any(|mirror| mirror.playback_active() != *plays)
            })
            .map(|(name, _, _)| name)
            .collect();
        assert!(
            wrong.is_empty(),
            "rows with a mirror yielding wrongly: {wrong:?}"
        );
    }

    /// docs/18 §2.1: a negotiation that fails streams nothing, so no mirror yields to it, and it
    /// is undone: a switch after it parks nothing.
    #[test]
    fn a_failed_negotiation_neither_pauses_sync_nor_gets_parked() {
        let (item_json, playback_info_json) = transcode_fixture();
        let (_dir, core, _mock) = core_signed_in_against_mock(item_json, playback_info_json);
        core.prepare_playback_now("item-1".to_string(), false)
            .expect_err("Direct Play mode refuses a Transcode decision outright");
        let opened = installed_mirror(&core);
        assert!(core.lock_state().playback_owner.claim.is_none());

        switch_to_other_account(&core);
        let browsed = reopen_mirror(&core);

        assert_eq!(core.parked_account_epoch(), None);
        assert!(!opened.playback_active());
        assert!(!browsed.playback_active());
    }

    /// docs/18 §2.1: a playback stopped before the switch plays nothing, so its account isn't
    /// parked: no second mirror or client stays loaded, and its epoch no longer owns playback.
    #[test]
    fn a_stopped_playback_does_not_park_its_account_on_the_next_switch() {
        let (item_json, playback_info_json) = direct_play_fixture();
        let (_dir, core, _mock) = core_signed_in_against_mock(item_json, playback_info_json);
        let played_epoch = core.account_epoch();
        let plan = core
            .prepare_playback_now("item-1".to_string(), false)
            .expect("plays");
        core.stop_playback(plan.play_session_id, 30_000_000);

        switch_to_other_account(&core);

        assert_eq!(core.parked_account_epoch(), None);
        assert!(!core.owns_playback(played_epoch));
    }

    /// docs/18 §2.1: a negotiation that fails on the browsed account leaves the playback still
    /// running on its own account parked there across the next switch.
    #[test]
    fn a_failed_negotiation_on_the_browsed_account_keeps_the_playing_one_parked() {
        let (_dir, core, _mock) = auto_core(vec![direct_play_fixture().1]);
        let plan = core
            .prepare_playback_now("item-1".to_string(), false)
            .expect("A plays");
        let a_epoch = core.account_epoch();
        switch_to_other_account(&core);
        reopen_mirror(&core);
        core.prepare_playback_now("item-2".to_string(), false)
            .expect_err("B's server is unreachable");

        seed_session(core.data_dir(), "http://third-server.invalid", "u-third");
        core.switch_session(2).expect("browses C");

        assert_eq!(core.parked_account_epoch(), Some(a_epoch));
        assert!(core.owns_playback(a_epoch));
        core.stop_playback(plan.play_session_id, 30_000_000);
        assert_eq!(
            core.require_client()
                .ok()
                .map(|client| client.base_url().to_string()),
            Some("http://third-server.invalid".to_string()),
            "C stays browsed"
        );
    }

    /// The identity a 401 from `saved`'s client carries.
    fn identity_of(saved: &session::SessionFile) -> AccountIdentity {
        AccountIdentity {
            server_url: saved.server_url.clone(),
            user_id: saved.user_id.clone(),
        }
    }

    /// `saved`'s index in the on-disk list now.
    fn index_of(core: &JellybeamCore, saved: &session::SessionFile) -> u32 {
        let list = session::load_list(core.data_dir());
        let index = list
            .sessions
            .iter()
            .position(|other| other.mirror_dir == saved.mirror_dir)
            .expect("saved");
        u32::try_from(index).expect("small")
    }

    /// A plays item-1, then B is browsed; returns A's plan and the epoch A's requests carry.
    fn a_plays_then_browses_b(
        core: &JellybeamCore,
        b: &session::SessionFile,
    ) -> (PlaybackPlan, u64) {
        let plan = core
            .prepare_playback_now("item-1".to_string(), false)
            .expect("A plays");
        let a_epoch = core.account_epoch();
        core.switch_session(index_of(core, b)).expect("browses B");
        (plan, a_epoch)
    }

    /// A request on A's kept epoch, as next-up mints it.
    fn a_request(core: &JellybeamCore, a_epoch: u64) -> PlaybackRequest {
        PlaybackRequest {
            seq: core.next_request().seq,
            account_epoch: a_epoch,
        }
    }

    /// docs/18 §2.1: a call's rejected token re-authorizes its account only while the app uses it
    /// -- browsed, or a playback runs or negotiates on it. A playback that ended (stopped,
    /// abandoned, failed) keeps its account for next-up only, so a late 401 from it prompts nobody;
    /// the player's own 401 still re-authorizes its account after its teardown, while it is saved.
    /// Each row starts with A signed in and B and C saved; the flags are whether a call's 401 from
    /// A and from B prompts, and whether A's failed playback does. C (saved, unused) and a
    /// never-saved identity never prompt from a call; a never-saved one never from a playback.
    #[test]
    fn a_401_reauthorizes_its_account_only_while_it_is_in_use() {
        type Setup = fn(&JellybeamCore, &session::SessionFile, &session::SessionFile);
        let rows: [(&str, Setup, bool, bool, bool); 13] = [
            ("browsing A", |_, _, _| {}, true, false, true),
            (
                "playing on A",
                |core, _, _| {
                    core.prepare_playback_now("item-1".to_string(), false)
                        .expect("A plays");
                },
                true,
                false,
                true,
            ),
            (
                "A plays on, browsing B",
                |core, _, b| {
                    a_plays_then_browses_b(core, b);
                },
                true,
                true,
                true,
            ),
            (
                "A plays on, back on A",
                |core, a, b| {
                    a_plays_then_browses_b(core, b);
                    core.switch_session(index_of(core, a)).expect("back on A");
                },
                true,
                false,
                true,
            ),
            (
                "A stopped, browsing B",
                |core, _, b| {
                    let (plan, _) = a_plays_then_browses_b(core, b);
                    core.stop_playback(plan.play_session_id, 30_000_000);
                },
                false,
                true,
                true,
            ),
            (
                "A abandoned, browsing B",
                |core, _, b| {
                    let (plan, _) = a_plays_then_browses_b(core, b);
                    core.abandon_playback(plan.play_session_id);
                },
                false,
                true,
                true,
            ),
            (
                "A stopped, back on A",
                |core, a, b| {
                    let (plan, _) = a_plays_then_browses_b(core, b);
                    core.stop_playback(plan.play_session_id, 30_000_000);
                    core.switch_session(index_of(core, a)).expect("back on A");
                },
                true,
                false,
                true,
            ),
            (
                "A's next-up negotiating after its stop, browsing B",
                |core, _, b| {
                    let (plan, a_epoch) = a_plays_then_browses_b(core, b);
                    core.stop_playback(plan.play_session_id, 30_000_000);
                    let request = a_request(core, a_epoch);
                    JellybeamCore::admit_playback_request(&mut core.lock_state(), request, None)
                        .expect("next-up is admitted on A");
                },
                true,
                true,
                true,
            ),
            (
                "A's next-up failed to negotiate, browsing B",
                |core, _, b| {
                    let (plan, a_epoch) = a_plays_then_browses_b(core, b);
                    core.stop_playback(plan.play_session_id, 30_000_000);
                    let request = a_request(core, a_epoch);
                    let claim = JellybeamCore::admit_playback_request(
                        &mut core.lock_state(),
                        request,
                        None,
                    )
                    .expect("next-up is admitted on A");
                    core.release_claim(&claim);
                },
                false,
                true,
                true,
            ),
            (
                "A's fallback failed, browsing B",
                |core, _, b| {
                    let (plan, a_epoch) = a_plays_then_browses_b(core, b);
                    core.prepare_transcode_fallback(
                        "item-1".to_string(),
                        1_000_000,
                        "decoder error".to_string(),
                        plan.play_session_id,
                        None,
                        None,
                        a_request(core, a_epoch),
                    )
                    .expect_err("a forced negotiation answered with Direct Play fails");
                },
                false,
                true,
                true,
            ),
            (
                "a newer playback on B replaces A's",
                |core, _, b| {
                    a_plays_then_browses_b(core, b);
                    admit(core);
                },
                false,
                true,
                true,
            ),
            (
                "A removed while it plays on",
                |core, a, b| {
                    a_plays_then_browses_b(core, b);
                    core.remove_session(index_of(core, a)).expect("removes A");
                },
                false,
                true,
                false,
            ),
            (
                "signed out of B while A plays on",
                |core, _, b| {
                    a_plays_then_browses_b(core, b);
                    remove_active_account(core);
                },
                true,
                false,
                true,
            ),
        ];
        let wrong: Vec<String> = rows
            .into_iter()
            .filter_map(|(name, setup, a_call, b_call, a_failed)| {
                let (_dir, core, _mock) =
                    auto_core(vec![direct_play_fixture().1, direct_play_fixture().1]);
                let a = session::load_list(core.data_dir()).sessions[0].clone();
                let b = seed_session(core.data_dir(), "http://other-server.invalid", "u-other");
                let c = seed_session(core.data_dir(), "http://third-server.invalid", "u-third");
                setup(&core, &a, &b);
                let prompts = |rejected: AccountIdentity, failed_playback: bool| {
                    core.reauthorization_account(rejected, failed_playback)
                        .map(|account| account.user_id)
                };
                let never_saved = AccountIdentity {
                    server_url: a.server_url.clone(),
                    user_id: "u-never".to_string(),
                };
                let got = (
                    prompts(identity_of(&a), false),
                    prompts(identity_of(&b), false),
                    prompts(identity_of(&c), false),
                    prompts(never_saved.clone(), false),
                    prompts(identity_of(&a), true),
                    prompts(never_saved, true),
                );
                let want = (
                    a_call.then(|| a.user_id.clone()),
                    b_call.then(|| b.user_id.clone()),
                    None,
                    None,
                    a_failed.then(|| a.user_id.clone()),
                    None,
                );
                (got != want).then(|| format!("{name}: got {got:?}, want {want:?}"))
            })
            .collect();
        assert!(wrong.is_empty(), "{wrong:#?}");
    }

    /// docs/18 §2.1: a parked account that comes back and re-signs in leaves again with its fresh
    /// token, so the playback's next calls authenticate.
    #[test]
    fn a_parked_account_leaves_again_with_its_fresh_token() {
        let (_dir, core, _mock) = auto_core(vec![direct_play_fixture().1]);
        core.prepare_playback_now("item-1".to_string(), false)
            .expect("A plays");
        let playing_epoch = core.account_epoch();
        let other = switch_to_other_account(&core);
        core.switch_session(1 - other).expect("switches back");
        change_account(&core);

        core.switch_session(other).expect("switches away again");

        let url = core
            .image_url(
                "item-1".to_string(),
                ImageKind::Primary,
                "tag".to_string(),
                100,
                Some(playing_epoch),
            )
            .expect("the parked account builds it");
        assert!(url.contains("ApiKey=new-token"), "{url}");
    }

    /// Signing out of the account that plays stops the installed session at its last position, so
    /// it keeps its resume point, and clears the sidecars with it.
    #[test]
    fn signing_out_stops_the_installed_session_at_its_last_position() {
        let (_dir, core, mock) = auto_core(vec![direct_play_fixture().1]);
        let a = core
            .prepare_playback_now("item-1".to_string(), false)
            .expect("A plays");
        core.report_position(a.play_session_id, 42_000_000);

        remove_active_account(&core);

        assert_eq!(active_play_session_id(&core), None);
        assert!(core.lock_state().sidecars.is_none());
        wait_until(
            || {
                mock.routes
                    .request_bodies_containing("POST", "/Sessions/Playing/Stopped")
                    .iter()
                    .any(|body| body["PositionTicks"] == 42_000_000)
            },
            "Stopped at the last reported position",
        );
    }

    /// A progress report names its session: one for another session (queued before a stop, or
    /// from an outgoing session) never moves the installed one.
    #[test]
    fn a_progress_report_for_another_session_is_ignored() {
        let (_dir, core, mock) = auto_core(vec![direct_play_fixture().1]);
        let a = core
            .prepare_playback_now("item-1".to_string(), false)
            .expect("A plays");
        core.report_position("an-earlier-session".to_string(), 42_000_000);
        core.report_position(a.play_session_id.clone(), 10_000_000);
        core.report_position("an-earlier-session".to_string(), 99_000_000);

        remove_active_account(&core);

        wait_until(
            || !stopped_positions(&mock, &a.play_session_id).is_empty(),
            "the installed session's final report",
        );
        assert_eq!(
            stopped_positions(&mock, &a.play_session_id),
            vec![10_000_000]
        );
    }

    #[test]
    fn signing_out_before_any_position_never_reports_a_stop_at_zero() {
        let (_dir, core, mock) = auto_core(vec![direct_play_fixture().1]);
        core.prepare_playback_now("item-1".to_string(), false)
            .expect("A plays");
        assert_start_reports(&mock, 1);

        remove_active_account(&core);

        assert_eq!(active_play_session_id(&core), None);
        std::thread::sleep(Duration::from_millis(150));
        assert_eq!(
            mock.routes
                .request_bodies_containing("POST", "/Sessions/Playing/Stopped")
                .len(),
            0,
            "a Stopped at 0 would overwrite the item's resume point"
        );
    }

    /// docs/18 §2.1: restoring the account that is already installed is not an account change.
    #[test]
    fn restoring_the_installed_account_leaves_playback_alone() {
        let (_dir, core, _mock) = auto_core(vec![direct_play_fixture().1]);
        let a = core
            .prepare_playback_now("item-1".to_string(), false)
            .expect("A plays");
        let owner_before = owner_view(&core);

        assert!(core.restore_session().is_some());

        assert_eq!(owner_view(&core), owner_before, "no epoch change");
        assert_eq!(active_play_session_id(&core), Some(a.play_session_id));
    }

    /// Every account path advances the epoch; only leaving the account that plays (removing it,
    /// active or parked) ends the playback with its claim and sidecars. Removing
    /// an account that isn't active is not an account change.
    #[test]
    fn only_leaving_the_playing_account_ends_playback() {
        #[derive(PartialEq)]
        enum Expect {
            Ends,
            Keeps,
            Untouched,
        }
        type AccountPath = fn(&JellybeamCore, session::SessionFile);
        let paths: [(&str, AccountPath, Expect); 5] = [
            (
                "switch",
                |core, _| {
                    core.switch_session(0).expect("switches");
                },
                Expect::Keeps,
            ),
            (
                "remove active",
                |core, _| {
                    core.remove_session(1).expect("removes");
                },
                Expect::Ends,
            ),
            (
                "remove other",
                |core, _| {
                    core.remove_session(0).expect("removes");
                },
                Expect::Untouched,
            ),
            (
                "switch, then remove the playing account",
                |core, _| {
                    core.switch_session(0).expect("switches");
                    core.remove_session(1).expect("removes");
                },
                Expect::Ends,
            ),
            (
                "reauthenticate",
                |core, target| {
                    let client = jellyfin_api::JellyfinClient::from_token(
                        &target.server_url,
                        core.client_identity("http://example.test"),
                        "new-token",
                    )
                    .with_user_id(&target.user_id);
                    core.install_reauthenticated_session(
                        1,
                        target,
                        client,
                        authentication_result("new-token", "renamed-user"),
                    )
                    .expect("same user refreshes its credential");
                },
                Expect::Keeps,
            ),
        ];
        for (name, path, expect) in paths {
            let (dir, core) = core_in_tempdir();
            seed_session(dir.path(), "http://server-a.test", "u1");
            let active = seed_session(dir.path(), "http://server-b.test", "u2");
            core.restore_session().expect("account u2 is active");
            let client = jellyfin_api::JellyfinClient::from_token(
                "http://example.invalid",
                core.client_identity("http://example.test"),
                "tok",
            );
            let ctx = jellyfin_core::ReportContext {
                item_id: "item-1".to_string(),
                media_source_id: "ms-1".to_string(),
                play_session_id: "attempt-a".to_string(),
                play_method: jellyfin_api::ReportPlayMethod::DirectPlay,
            };
            let claim = admit(&core);
            core.install_claimed_session(&claim, &client, ctx, Some(test_sidecars("attempt-a")))
                .expect("installs");
            let _pending = admit(&core);
            let (_, _, epoch_before) = owner_view(&core);

            path(&core, active);

            let (_, claim_after, epoch_after) = owner_view(&core);
            let (has_session, has_sidecars) = {
                let state = core.lock_state();
                (state.reporting.is_some(), state.sidecars.is_some())
            };
            match expect {
                Expect::Ends => {
                    assert_ne!(epoch_after, epoch_before, "{name}: epoch");
                    assert_eq!(claim_after, None, "{name}: pending claim revoked");
                    assert!(!has_session, "{name}: session ended");
                    assert!(!has_sidecars, "{name}: sidecars cleared");
                    assert!(!core.owns_playback(epoch_before), "{name}: closed");
                }
                Expect::Keeps => {
                    assert_ne!(epoch_after, epoch_before, "{name}: epoch");
                    assert!(claim_after.is_some(), "{name}: pending claim kept");
                    assert!(has_session && has_sidecars, "{name}: session kept");
                    assert!(core.owns_playback(epoch_before), "{name}: still owns");
                }
                Expect::Untouched => {
                    assert_eq!(epoch_after, epoch_before, "{name}: epoch");
                    assert!(claim_after.is_some(), "{name}: pending claim kept");
                    assert!(has_session, "{name}: session kept");
                }
            }
        }
    }

    /// (method, path, required query substring, status, body): see
    /// [`RouteMockServer::route_with_query`].
    type QueryRoute = (String, String, String, u16, String);
    /// (method, required path substring, responses, delays): see
    /// [`RouteMockServer::route_script`].
    type ScriptedRoute = (String, String, Vec<serde_json::Value>, Vec<Duration>);
    /// (method, path substring, query substring, hit index, gate): see
    /// [`RouteMockServer::gate_with_query`]; an empty query substring matches every query.
    type GatedReply = (String, String, String, usize, Arc<tokio::sync::Semaphore>);

    /// The state a [`RouteMockServer`]'s connection handler shares with the
    /// struct's own accessors, held as one `Arc` so `serve` only ever clones
    /// one handle per accepted connection.
    #[derive(Default)]
    struct MockShared {
        routes: Mutex<std::collections::HashMap<String, (u16, String)>>,
        /// Checked before `routes` so a query-scoped response can override
        /// the path-only default for the same method+path.
        query_routes: Mutex<Vec<QueryRoute>>,
        /// A response per hit index (clamped to the list's length) plus a
        /// matching per-index reply delay; takes precedence over `routes`.
        /// Matched by substring, not exact path, so it still finds an
        /// endpoint whose path embeds a varying id (e.g.
        /// `/Items/{id}/PlaybackInfo`); the hit index counts every prior
        /// request matching the same method+substring regardless of the id
        /// in between.
        scripted: Mutex<Vec<ScriptedRoute>>,
        /// A scripted arrival replies only once its gate is opened, so a test orders completions
        /// without timing.
        gates: Mutex<Vec<GatedReply>>,
        /// (method, path substring, arrivals left to answer 500), see [`RouteMockServer::fail_first`].
        failures: Mutex<Vec<(String, String, usize)>>,
        hits: Mutex<std::collections::HashMap<String, usize>>,
        /// Every request's ("METHOD path", parsed JSON body) in arrival
        /// order, for callers that need either an exact-path or a
        /// substring-matched body history.
        bodies: Mutex<Vec<(String, serde_json::Value)>>,
        /// "METHOD full-target" (query string included) in arrival order,
        /// so a test can tell apart requests that collapse to the same key.
        log: Mutex<Vec<String>>,
        /// ("METHOD path", `Authorization` header value) in arrival order, see
        /// [`RouteMockServer::authorizations`].
        auth_log: Mutex<Vec<(String, String)>>,
    }

    /// A held mock reply; see [`RouteMockServer::gate`].
    struct Gate(Arc<tokio::sync::Semaphore>);

    impl Gate {
        fn open(&self) {
            self.0.add_permits(1);
        }
    }

    /// Small loopback HTTP mock serving both plain signed-in-but-mirror-less
    /// tests and a fully synced mirror. Routes matched on "METHOD PATH"; an
    /// unregistered path gets a harmless default so sync's incidental calls
    /// never hang or fail unrelated tests.
    struct RouteMockServer {
        base_url: String,
        shared: Arc<MockShared>,
        _handle: tokio::task::JoinHandle<()>,
    }

    impl RouteMockServer {
        async fn start() -> Self {
            let listener = tokio::net::TcpListener::bind("127.0.0.1:0")
                .await
                .expect("bind loopback");
            let addr = listener.local_addr().expect("local_addr");
            let shared = Arc::new(MockShared::default());
            let shared_for_task = shared.clone();

            let handle = tokio::spawn(async move {
                loop {
                    let Ok((stream, _)) = listener.accept().await else {
                        break;
                    };
                    tokio::spawn(serve(shared_for_task.clone(), stream));
                }
            });

            Self {
                base_url: format!("http://{addr}"),
                shared,
                _handle: handle,
            }
        }

        fn route(&self, method: &str, path: &str, status: u16, body: serde_json::Value) {
            unlock(&self.shared.routes)
                .insert(format!("{method} {path}"), (status, body.to_string()));
        }

        /// Registers a response that only applies when the request's query
        /// string contains `query_substring`; takes precedence over a plain
        /// [`Self::route`] for the same method+path so a caller can tell
        /// apart requests that share a path but differ in intent (e.g. a
        /// full-collection fetch vs. an id-scoped refresh).
        fn route_with_query(
            &self,
            method: &str,
            path: &str,
            query_substring: &str,
            status: u16,
            body: &serde_json::Value,
        ) {
            unlock(&self.shared.query_routes).push((
                method.to_string(),
                path.to_string(),
                query_substring.to_string(),
                status,
                body.to_string(),
            ));
        }

        /// Scripts a distinct 200 response per call to a `method` request
        /// whose path contains `path_substring` (the last entry repeats past
        /// the list's length), each preceded by the matching per-index reply
        /// delay (same clamping). Substring, not exact, so one script still
        /// covers an endpoint whose path embeds a varying id.
        fn route_script(
            &self,
            method: &str,
            path_substring: &str,
            responses: Vec<serde_json::Value>,
            delays: Vec<Duration>,
        ) {
            unlock(&self.shared.scripted).push((
                method.to_string(),
                path_substring.to_string(),
                responses,
                if delays.is_empty() {
                    vec![Duration::ZERO]
                } else {
                    delays
                },
            ));
        }

        /// Holds the `hit_index`-th (zero-based) scripted `method` arrival whose path contains
        /// `path_substring` until the returned gate is opened.
        fn gate(&self, method: &str, path_substring: &str, hit_index: usize) -> Gate {
            self.gate_with_query(method, path_substring, "", hit_index)
        }

        /// [`Self::gate`] restricted to arrivals whose query string contains `query_substring`;
        /// only those arrivals count toward `hit_index`, so a same-path request with another
        /// query neither trips nor shifts the hold.
        fn gate_with_query(
            &self,
            method: &str,
            path_substring: &str,
            query_substring: &str,
            hit_index: usize,
        ) -> Gate {
            let gate = Arc::new(tokio::sync::Semaphore::new(0));
            unlock(&self.shared.gates).push((
                method.to_string(),
                path_substring.to_string(),
                query_substring.to_string(),
                hit_index,
                gate.clone(),
            ));
            Gate(gate)
        }

        /// Answers the first `count` `method` arrivals whose path contains `path_substring` with
        /// 500, whatever else is routed for them.
        fn fail_first(&self, method: &str, path_substring: &str, count: usize) {
            unlock(&self.shared.failures).push((
                method.to_string(),
                path_substring.to_string(),
                count,
            ));
        }

        /// Every logged "METHOD target", in arrival order.
        fn log_snapshot(&self) -> Vec<String> {
            unlock(&self.shared.log).clone()
        }

        /// Index of the first logged request whose target contains
        /// `needle`. `None` if it never arrived.
        fn first_request_containing(&self, needle: &str) -> Option<usize> {
            unlock(&self.shared.log)
                .iter()
                .position(|entry| entry.contains(needle))
        }

        /// The most recently logged "METHOD full-target" entry.
        fn last_request(&self) -> String {
            unlock(&self.shared.log)
                .last()
                .cloned()
                .expect("no request captured")
        }

        /// The `Authorization` header of every "METHOD path" arrival, in arrival order.
        fn authorizations(&self, method: &str, path: &str) -> Vec<String> {
            let key = format!("{method} {path}");
            unlock(&self.shared.auth_log)
                .iter()
                .filter(|(entry, _)| *entry == key)
                .map(|(_, header)| header.clone())
                .collect()
        }

        fn hit_count(&self, method: &str, path: &str) -> usize {
            *unlock(&self.shared.hits)
                .get(&format!("{method} {path}"))
                .unwrap_or(&0)
        }

        /// Number of arrived `method` requests whose path contains `needle`;
        /// the [`Self::route_script`] counterpart to [`Self::hit_count`] for
        /// an endpoint whose path embeds a varying id.
        fn hit_count_containing(&self, method: &str, needle: &str) -> usize {
            unlock(&self.shared.log)
                .iter()
                .filter(|entry| entry.starts_with(&format!("{method} ")) && entry.contains(needle))
                .count()
        }

        /// Every request body whose "METHOD path" contains `needle`, parsed
        /// as JSON, in arrival order; the [`Self::route_script`] counterpart
        /// to a plain [`Self::route`], for an endpoint whose path embeds a
        /// varying id.
        fn request_bodies_containing(&self, method: &str, needle: &str) -> Vec<serde_json::Value> {
            unlock(&self.shared.bodies)
                .iter()
                .filter(|(key, _)| key.starts_with(&format!("{method} ")) && key.contains(needle))
                .map(|(_, body)| body.clone())
                .collect()
        }
    }

    /// Whether a logged "METHOD target" entry's query string contains `needle`.
    fn entry_query_contains(entry: &str, needle: &str) -> bool {
        entry
            .split_once('?')
            .map_or(needle.is_empty(), |(_, query)| query.contains(needle))
    }

    /// One accepted connection's worth of [`RouteMockServer`] logic: parses
    /// the request line, resolves it against `shared` (a query-scoped route,
    /// then a scripted one, then a plain one, then a harmless default), and
    /// writes back a synthetic HTTP/1.1 response.
    async fn serve(shared: Arc<MockShared>, mut stream: tokio::net::TcpStream) {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};

        let mut buf = vec![0u8; 65536];
        let n = match stream.read(&mut buf).await {
            Ok(n) if n > 0 => n,
            _ => return,
        };
        let request = String::from_utf8_lossy(&buf[..n]);
        let mut first_line = request.lines().next().unwrap_or("").split_whitespace();
        let method = first_line.next().unwrap_or("").to_string();
        let target = first_line.next().unwrap_or("/");
        let mut target_parts = target.splitn(2, '?');
        let path = target_parts.next().unwrap_or(target).to_string();
        let query = target_parts.next().unwrap_or("");
        let key = format!("{method} {path}");

        // Found before this arrival is logged, so it excludes
        // itself: a zero-based index into the matched
        // script's responses/delays.
        let scripted_match = unlock(&shared.scripted)
            .iter()
            .find(|(m, needle, _, _)| *m == method && path.contains(needle.as_str()))
            .cloned();
        let scripted_hit_index = scripted_match.as_ref().map(|(_, needle, ..)| {
            unlock(&shared.log)
                .iter()
                .filter(|entry| {
                    entry.starts_with(&format!("{method} ")) && entry.contains(needle.as_str())
                })
                .count()
        });

        // Also before this arrival is logged: the gate whose hit index (counted over its own
        // method + substring) this arrival is.
        let gate = unlock(&shared.gates)
            .iter()
            .find(|(m, needle, query_needle, index, _)| {
                *m == method
                    && path.contains(needle.as_str())
                    && query.contains(query_needle.as_str())
                    && unlock(&shared.log)
                        .iter()
                        .filter(|entry| {
                            entry.starts_with(&format!("{method} "))
                                && entry.contains(needle.as_str())
                                && entry_query_contains(entry, query_needle)
                        })
                        .count()
                        == *index
            })
            .map(|(.., gate)| gate.clone());

        *unlock(&shared.hits).entry(key.clone()).or_insert(0) += 1;
        unlock(&shared.log).push(format!("{method} {target}"));
        let authorization = request
            .lines()
            .find(|line| line.to_ascii_lowercase().starts_with("authorization:"))
            .map(|line| line["authorization:".len()..].trim().to_string())
            .unwrap_or_default();
        unlock(&shared.auth_log).push((key.clone(), authorization));
        if let Some(raw_body) = request.split("\r\n\r\n").nth(1) {
            if let Ok(parsed) = serde_json::from_str(raw_body) {
                unlock(&shared.bodies).push((key.clone(), parsed));
            }
        }

        let query_configured = unlock(&shared.query_routes)
            .iter()
            .find(|(m, p, needle, _, _)| {
                *m == method && *p == path && query.contains(needle.as_str())
            })
            .map(|(_, _, _, status, body)| (*status, body.clone()));
        let scripted_configured =
            scripted_match
                .zip(scripted_hit_index)
                .map(|((_, _, responses, delays), hit_index)| {
                    let response_index = hit_index.min(responses.len().saturating_sub(1));
                    let delay_index = hit_index.min(delays.len().saturating_sub(1));
                    (
                        delays[delay_index],
                        (200, responses[response_index].to_string()),
                    )
                });
        if let Some((delay, _)) = &scripted_configured {
            if !delay.is_zero() {
                tokio::time::sleep(*delay).await;
            }
        }
        if let Some(gate) = gate {
            let _ = gate.acquire().await;
        }
        let failing = unlock(&shared.failures)
            .iter_mut()
            .find(|(m, needle, left)| *m == method && path.contains(needle.as_str()) && *left > 0)
            .map(|entry| entry.2 -= 1)
            .is_some();

        let configured = failing
            .then(|| (500, "{}".to_string()))
            .or(query_configured)
            .or_else(|| scripted_configured.map(|(_, response)| response))
            .or_else(|| unlock(&shared.routes).get(&key).cloned());
        let (status, body) = configured.unwrap_or_else(|| {
            if method == "GET" {
                (200, r#"{"Items":[],"TotalRecordCount":0}"#.to_string())
            } else {
                (204, String::new())
            }
        });
        // Every HTTP/1.1 status line requires a reason phrase; only the
        // numeric `status` is ever asserted on, never this text.
        let reason = match status {
            200 => "OK",
            204 => "No Content",
            400 => "Bad Request",
            401 => "Unauthorized",
            404 => "Not Found",
            500 => "Internal Server Error",
            _ => "Status",
        };
        let response = format!(
            "HTTP/1.1 {status} {reason}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
            body.len()
        );
        let _ = stream.write_all(response.as_bytes()).await;
        let _ = stream.shutdown().await;
    }

    /// Recovers a mock server's lock instead of propagating poison -- one
    /// assertion panicking mid-request must not cascade into unrelated
    /// "lock poisoned" failures elsewhere in the same test.
    fn unlock<T>(mutex: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
        mutex
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
    }

    /// Polls `f` until `true` or ~2s elapse; panics with `what` on timeout.
    fn wait_until(f: impl FnMut() -> bool, what: &str) {
        wait_until_for(Duration::from_secs(2), f, what);
    }

    /// [`wait_until`] with a caller-chosen timeout, for waits on a real retry backoff.
    fn wait_until_for(timeout: Duration, mut f: impl FnMut() -> bool, what: &str) {
        let deadline = Instant::now() + timeout;
        while Instant::now() < deadline {
            if f() {
                return;
            }
            std::thread::sleep(Duration::from_millis(5));
        }
        panic!("timed out waiting for: {what}");
    }

    /// A minimal `UserItemDataDto` response body with only `Key` set.
    fn user_item_data_json(played: Option<bool>, favorite: Option<bool>) -> serde_json::Value {
        let mut body = serde_json::json!({ "Key": "k" });
        if let Some(played) = played {
            body["Played"] = serde_json::json!(played);
        }
        if let Some(favorite) = favorite {
            body["IsFavorite"] = serde_json::json!(favorite);
        }
        body
    }

    /// Signs `core` into a freshly started [`RouteMockServer`], no network
    /// round trip. Callers register routes before calling `open_mirror`
    /// themselves.
    fn core_signed_in_against_routes() -> (tempfile::TempDir, Arc<JellybeamCore>, RouteMockServer) {
        let (dir, core) = core_in_tempdir();
        let mock = core.runtime.block_on(RouteMockServer::start());
        let saved = session::SessionFile {
            server_url: mock.base_url.clone(),
            user_id: "u1".to_string(),
            user_name: "jellybeam-user".to_string(),
            token: "tok".to_string(),
            device_id: "dev".to_string(),
            mirror_dir: "mirror".to_string(),
            server_version: None,
            server_name: None,
        };
        session::add_or_update(dir.path(), saved).expect("seed a session file");
        assert!(core.restore_session().is_some());
        (dir, core, mock)
    }

    fn user_views_json(view_id: &str, collection_type: &str) -> serde_json::Value {
        serde_json::json!({
            "Items": [{
                "Id": view_id,
                "Name": "Library",
                "CollectionType": collection_type,
                "Type": "CollectionFolder"
            }],
            "TotalRecordCount": 1
        })
    }

    #[test]
    fn set_played_before_sign_in_is_not_signed_in() {
        let (_dir, core) = core_in_tempdir();
        let err = core
            .set_played("item-1".to_string(), true)
            .expect_err("no client yet");
        assert!(matches!(err, CoreError::NotSignedIn));
    }

    /// docs/19 §2.3: `set_played` applies the response DTO to the mirror
    /// row; `card_by_id` sees the flip without an extra fetch.
    #[test]
    fn set_played_applies_the_response_to_the_mirror_row() {
        const VIEW_ID: &str = "00000000-0000-0000-0000-000000000010";
        const MOVIE_ID: &str = "00000000-0000-0000-0000-000000000011";

        let (_dir, core, mock) = core_signed_in_against_routes();
        mock.route("GET", "/UserViews", 200, user_views_json(VIEW_ID, "movies"));
        mock.route(
            "GET",
            "/Items",
            200,
            serde_json::json!({
                "Items": [{
                    "Id": MOVIE_ID,
                    "Name": "Sample Movie",
                    "Type": "Movie",
                    "UserData": {"Key": "k", "Played": false, "IsFavorite": false}
                }],
                "TotalRecordCount": 1
            }),
        );
        mock.route(
            "POST",
            &format!("/UserPlayedItems/{MOVIE_ID}"),
            200,
            user_item_data_json(Some(true), None),
        );

        core.open_mirror()
            .expect("open_mirror should succeed synchronously against a real loopback server");

        wait_until(
            || {
                core.card_by_id(MOVIE_ID.to_string())
                    .ok()
                    .flatten()
                    .is_some_and(|c| !c.played)
            },
            "initial sync to seed the unplayed movie row",
        );

        core.set_played(MOVIE_ID.to_string(), true)
            .expect("set_played against mock server");

        wait_until(
            || {
                core.card_by_id(MOVIE_ID.to_string())
                    .ok()
                    .flatten()
                    .is_some_and(|c| c.played)
            },
            "set_played's mirror write to commit",
        );
    }

    /// docs/19 §2.3: `set_favorite` applies the response DTO; `card_by_id`'s
    /// `is_favorite` flips.
    #[test]
    fn set_favorite_reaches_the_card_the_home_shelf_and_the_drawer() {
        const VIEW_ID: &str = "00000000-0000-0000-0000-000000000010";
        const MOVIE_ID: &str = "00000000-0000-0000-0000-000000000012";

        let (_dir, core, mock) = core_signed_in_against_routes();
        mock.route("GET", "/UserViews", 200, user_views_json(VIEW_ID, "movies"));
        mock.route(
            "GET",
            "/Items",
            200,
            serde_json::json!({
                "Items": [{
                    "Id": MOVIE_ID,
                    "Name": "Sample Movie",
                    "Type": "Movie",
                    "UserData": {"Key": "k", "Played": false, "IsFavorite": false}
                }],
                "TotalRecordCount": 1
            }),
        );
        mock.route(
            "POST",
            &format!("/UserFavoriteItems/{MOVIE_ID}"),
            200,
            user_item_data_json(None, Some(true)),
        );

        core.open_mirror()
            .expect("open_mirror should succeed synchronously against a real loopback server");

        wait_until(
            || {
                core.card_by_id(MOVIE_ID.to_string())
                    .ok()
                    .flatten()
                    .is_some_and(|c| !c.is_favorite)
            },
            "initial sync to seed the non-favorite movie row",
        );

        core.set_favorite(MOVIE_ID.to_string(), true)
            .expect("set_favorite against mock server");

        wait_until(
            || {
                core.card_by_id(MOVIE_ID.to_string())
                    .ok()
                    .flatten()
                    .is_some_and(|c| c.is_favorite)
            },
            "set_favorite's mirror write to commit",
        );

        // docs/07 §1/§5: the shelf and the drawer entry follow the flag; the setting hides
        // only the shelf.
        assert!(core.has_favorites());
        let favorites_shelf = |core: &JellybeamCore| -> Vec<String> {
            let HomeSnapshot::Classic { home } = core.home_snapshot(HomeLayout::Classic);
            home.shelves
                .into_iter()
                .filter(|s| s.source == crate::home::ShelfSource::Favorites)
                .flat_map(|s| s.cards)
                .map(|c| c.id)
                .collect()
        };
        assert_eq!(favorites_shelf(&core), vec![MOVIE_ID.to_string()]);
        let mut settings = core.get_settings();
        settings.home_show_favorites = false;
        core.set_settings(settings);
        assert!(favorites_shelf(&core).is_empty());
        assert!(core.has_favorites());
    }

    /// docs/19 §2.3: `list_collections` caches for its TTL -- a second call
    /// within it makes no second request.
    #[test]
    fn list_collections_caches_and_makes_no_second_request() {
        let (_dir, core, mock) = core_signed_in_against_routes();
        mock.route(
            "GET",
            "/Items",
            200,
            serde_json::json!({
                "Items": [{"Id": "00000000-0000-0000-0000-000000000030", "Name": "My Collection"}],
                "TotalRecordCount": 1
            }),
        );

        let first = core
            .list_collections()
            .expect("list_collections against mock server");
        assert_eq!(first.len(), 1);
        assert_eq!(first[0].name, "My Collection");
        assert_eq!(mock.hit_count("GET", "/Items"), 1);

        let second = core.list_collections().expect("cached list_collections");
        assert_eq!(second, first);
        assert_eq!(
            mock.hit_count("GET", "/Items"),
            1,
            "a cache hit within the TTL must not make a second request"
        );
    }

    /// Websocket connect attempts the mock has seen; it serves no upgrade, so each arrives as a
    /// plain `GET /socket`.
    fn ws_connect_count(mock: &RouteMockServer) -> usize {
        mock.hit_count_containing("GET", "/socket")
    }

    /// Harness self-test: every `open_mirror` enters the single open funnel exactly once.
    #[test]
    fn mirror_open_count_rises_by_one_per_open_mirror() {
        let (_dir, core, _mock) = core_signed_in_against_routes();
        let baseline = core.mirror_open_count();
        assert_eq!(baseline, 0);

        core.open_mirror().expect("first open_mirror");
        assert_eq!(core.mirror_open_count(), baseline + 1);

        core.open_mirror().expect("second open_mirror");
        assert_eq!(core.mirror_open_count(), baseline + 2);
    }

    /// Harness self-test: one open starts one websocket attempt, and the next arrives only after
    /// the reconnect backoff (base 1 s, at least 0.75 s with jitter), so a short window is stable.
    #[test]
    fn ws_connect_count_sees_one_attempt_per_open_within_the_backoff() {
        let (_dir, core, mock) = core_signed_in_against_routes();
        assert_eq!(ws_connect_count(&mock), 0);

        core.open_mirror().expect("open_mirror");
        wait_until_for(
            Duration::from_secs(3),
            || ws_connect_count(&mock) >= 1,
            "the websocket connect attempt to reach the mock",
        );
        let first = ws_connect_count(&mock);

        std::thread::sleep(Duration::from_millis(300));
        assert_eq!(
            ws_connect_count(&mock),
            first,
            "no second attempt inside the reconnect backoff"
        );
    }

    /// Harness self-test: a query-scoped gate holds only the BoxSet `GET /Items`, not the
    /// mirror's own `GET /Items` pages.
    #[test]
    fn gate_with_query_holds_list_collections_but_not_sync_pages() {
        const VIEW_ID: &str = "00000000-0000-0000-0000-000000000010";
        const MOVIE_ID: &str = "00000000-0000-0000-0000-000000000034";
        const COLLECTION_ID: &str = "00000000-0000-0000-0000-000000000035";

        let (_dir, core, mock) = core_signed_in_against_routes();
        mock.route("GET", "/UserViews", 200, user_views_json(VIEW_ID, "movies"));
        mock.route(
            "GET",
            "/Items",
            200,
            serde_json::json!({
                "Items": [{
                    "Id": MOVIE_ID,
                    "Name": "Sample Movie",
                    "Type": "Movie",
                    "UserData": {"Key": "k", "Played": false, "IsFavorite": false}
                }],
                "TotalRecordCount": 1
            }),
        );
        mock.route_with_query(
            "GET",
            "/Items",
            "includeItemTypes=BoxSet",
            200,
            &serde_json::json!({
                "Items": [{"Id": COLLECTION_ID, "Name": "Sample Collection"}],
                "TotalRecordCount": 1
            }),
        );
        let held = mock.gate_with_query("GET", "/Items", "includeItemTypes=BoxSet", 0);

        core.open_mirror().expect("open_mirror");
        wait_until(
            || {
                core.card_by_id(MOVIE_ID.to_string())
                    .ok()
                    .flatten()
                    .is_some()
            },
            "the sync page to land in the mirror before the BoxSet request exists",
        );

        let listing = std::thread::spawn(move || core.list_collections());
        wait_until(
            || mock.hit_count_containing("GET", "includeItemTypes=BoxSet") == 1,
            "the BoxSet request to arrive",
        );
        std::thread::sleep(Duration::from_millis(300));
        assert!(
            !listing.is_finished(),
            "list_collections waits for its held reply"
        );

        held.open();
        let collections = listing
            .join()
            .expect("list_collections thread")
            .expect("list_collections once released");
        assert_eq!(collections.len(), 1);
        assert_eq!(collections[0].id, COLLECTION_ID);
        assert_eq!(collections[0].name, "Sample Collection");
    }

    /// docs/19 §2.2/§2.3: `add_to_collection` posts the add, then spawns a
    /// background membership refresh so `collection_ids_containing` sees it
    /// without waiting on `LibraryChanged`.
    #[test]
    fn add_to_collection_refreshes_membership_after_the_post() {
        const VIEW_ID: &str = "00000000-0000-0000-0000-000000000010";
        const MOVIE_ID: &str = "00000000-0000-0000-0000-000000000013";
        const COLLECTION_ID: &str = "00000000-0000-0000-0000-000000000031";

        let (_dir, core, mock) = core_signed_in_against_routes();
        mock.route("GET", "/UserViews", 200, user_views_json(VIEW_ID, "movies"));
        // Same fixture answers both the breadth sync's plain GET /Items and
        // the later membership fetch.
        mock.route(
            "GET",
            "/Items",
            200,
            serde_json::json!({
                "Items": [{
                    "Id": MOVIE_ID,
                    "Name": "Sample Movie",
                    "Type": "Movie",
                    "UserData": {"Key": "k", "Played": false, "IsFavorite": false}
                }],
                "TotalRecordCount": 1
            }),
        );

        core.open_mirror()
            .expect("open_mirror should succeed synchronously against a real loopback server");
        wait_until(
            || {
                core.card_by_id(MOVIE_ID.to_string())
                    .ok()
                    .flatten()
                    .is_some()
            },
            "initial sync to seed the movie row",
        );
        assert!(
            core.collection_ids_containing(MOVIE_ID.to_string())
                .is_empty(),
            "must start out of every collection"
        );

        core.add_to_collection(COLLECTION_ID.to_string(), MOVIE_ID.to_string())
            .expect("add_to_collection against mock server");

        wait_until(
            || {
                core.collection_ids_containing(MOVIE_ID.to_string())
                    == vec![COLLECTION_ID.to_string()]
            },
            "background refresh_collection_membership to commit",
        );

        let post_index = mock
            .first_request_containing(&format!("/Collections/{COLLECTION_ID}/Items"))
            .expect("the membership POST must have gone out");
        let membership_get_index = mock
            .first_request_containing(&format!("parentId={COLLECTION_ID}"))
            .expect("the non-recursive membership GET must have gone out");
        assert!(
            membership_get_index > post_index,
            "membership fetch (index {membership_get_index}) must follow the \
             add POST (index {post_index}), not race or precede it"
        );
    }

    /// docs/19 §2.3: `is_administrator` caches for the whole session.
    #[test]
    fn validate_session_passes_on_200_and_is_unauthorized_on_401() {
        let (_dir, core, mock) = core_signed_in_against_routes();
        mock.route(
            "GET",
            "/Users/Me",
            200,
            serde_json::json!({
                "Id": "00000000-0000-0000-0000-000000000040",
                "Name": "jellybeam-user"
            }),
        );
        core.validate_session().expect("a live token validates");

        mock.route("GET", "/Users/Me", 401, serde_json::json!({}));
        let err = core
            .validate_session()
            .expect_err("a revoked token must not validate");
        let CoreError::Unauthorized {
            account: Some(rejected),
        } = err
        else {
            panic!("a revoked token names its account, got {err:?}");
        };
        let signed_in = session::load_list(core.data_dir()).sessions[0].clone();
        assert_eq!(
            core.reauthorization_account(rejected, false)
                .map(|account| account.user_id),
            Some(signed_in.user_id),
            "the 401's account is the signed-in one, as saved"
        );
        assert_eq!(
            mock.hit_count("GET", "/Users/Me"),
            2,
            "validation is never cached"
        );
    }

    #[test]
    fn is_administrator_caches_and_makes_no_second_request() {
        let (_dir, core, mock) = core_signed_in_against_routes();
        mock.route(
            "GET",
            "/Users/Me",
            200,
            serde_json::json!({
                "Id": "00000000-0000-0000-0000-000000000040",
                "Name": "jellybeam-admin",
                "Policy": {
                    "IsAdministrator": true,
                    "AuthenticationProviderId": "synthetic-provider",
                    "PasswordResetProviderId": "synthetic-provider"
                }
            }),
        );

        let first = core
            .is_administrator()
            .expect("is_administrator against mock server");
        assert!(first);
        assert_eq!(mock.hit_count("GET", "/Users/Me"), 1);

        let second = core.is_administrator().expect("cached is_administrator");
        assert!(second);
        assert_eq!(
            mock.hit_count("GET", "/Users/Me"),
            1,
            "a cached admin bit must not make a second request"
        );
    }

    const SWAP_USER_A: &str = "00000000-0000-0000-0000-000000000001";
    const SWAP_USER_B: &str = "00000000-0000-0000-0000-000000000002";

    /// How the active account stops being account A while A's fetch is in flight.
    #[derive(Clone, Copy, Debug)]
    enum AccountSwap {
        Switch,
        SignIn,
        RemoveActive,
    }

    /// Account A (active, on `mock_a`) and account B (on `mock_b`) with distinct answers for the
    /// two per-account caches; B is saved at index 0 and A at index 1, except that `SignIn`
    /// leaves B unsaved for the swap itself to install.
    struct TwoAccounts {
        _dir: tempfile::TempDir,
        core: Arc<JellybeamCore>,
        mock_a: RouteMockServer,
        mock_b: RouteMockServer,
    }

    fn core_with_two_accounts(swap: AccountSwap) -> TwoAccounts {
        let (dir, core) = core_in_tempdir();
        let mock_a = core.runtime.block_on(RouteMockServer::start());
        let mock_b = core.runtime.block_on(RouteMockServer::start());
        let collection = |name: &str| {
            serde_json::json!({
                "Items": [{"Id": "00000000-0000-0000-0000-000000000030", "Name": name}],
                "TotalRecordCount": 1
            })
        };
        let user = |id: &str, admin: bool| {
            serde_json::json!({
                "Id": id,
                "Name": "Sample User",
                "Policy": {
                    "IsAdministrator": admin,
                    "AuthenticationProviderId": "synthetic-provider",
                    "PasswordResetProviderId": "synthetic-provider"
                }
            })
        };
        mock_a.route_with_query(
            "GET",
            "/Items",
            "includeItemTypes=BoxSet",
            200,
            &collection("Sample Collection A"),
        );
        mock_b.route_with_query(
            "GET",
            "/Items",
            "includeItemTypes=BoxSet",
            200,
            &collection("Sample Collection B"),
        );
        mock_a.route("GET", "/Users/Me", 200, user(SWAP_USER_A, true));
        mock_b.route("GET", "/Users/Me", 200, user(SWAP_USER_B, false));
        mock_b.route(
            "POST",
            "/Users/AuthenticateByName",
            200,
            serde_json::json!({
                "User": {"Id": SWAP_USER_B, "Name": "Sample User"},
                "AccessToken": "tok-b",
            }),
        );

        if !matches!(swap, AccountSwap::SignIn) {
            seed_session(dir.path(), &mock_b.base_url, SWAP_USER_B);
        }
        seed_session(dir.path(), &mock_a.base_url, SWAP_USER_A);
        assert_eq!(
            core.restore_session().expect("account A restores").user_id,
            SWAP_USER_A
        );
        TwoAccounts {
            _dir: dir,
            core,
            mock_a,
            mock_b,
        }
    }

    fn apply_account_swap(accounts: &TwoAccounts, swap: AccountSwap) {
        let core = &accounts.core;
        match swap {
            AccountSwap::Switch => {
                core.switch_session(0).expect("switch to account B");
            }
            AccountSwap::SignIn => {
                core.sign_in(
                    accounts.mock_b.base_url.clone(),
                    "user".to_string(),
                    "pass".to_string(),
                )
                .expect("sign in as account B");
            }
            AccountSwap::RemoveActive => {
                assert!(core.remove_session(1).expect("remove account A"));
            }
        }
    }

    /// docs/19 §2.3: a per-account cache fetch that spans an account change may hand its caller
    /// the old account's answer, but must not store it for the new account.
    fn assert_fetch_spanning_a_swap_is_not_cached<T>(
        swap: AccountSwap,
        fetch: fn(&JellybeamCore) -> Result<T, CoreError>,
        cache_is_empty: fn(&JellybeamCore) -> bool,
        (gate_path, gate_query, request_needle): (&str, &str, &str),
        expected_b: T,
    ) where
        T: Send + PartialEq + std::fmt::Debug + 'static,
    {
        let accounts = core_with_two_accounts(swap);
        let held = accounts
            .mock_a
            .gate_with_query("GET", gate_path, gate_query, 0);
        let in_flight = {
            let core = accounts.core.clone();
            std::thread::spawn(move || fetch(&core))
        };
        wait_until(
            || accounts.mock_a.hit_count_containing("GET", request_needle) >= 1,
            "account A's request to reach its server",
        );
        apply_account_swap(&accounts, swap);
        held.open();
        let _ = in_flight.join().expect("fetch thread");

        assert!(
            cache_is_empty(&accounts.core),
            "account A's answer must not be cached after {swap:?}"
        );
        let before = accounts.mock_b.hit_count_containing("GET", request_needle);
        assert_eq!(
            fetch(&accounts.core).expect("account B's fetch"),
            expected_b
        );
        assert_eq!(
            accounts.mock_b.hit_count_containing("GET", request_needle),
            before + 1,
            "account B reads its own server, not a cache"
        );
    }

    fn assert_collections_fetch_spanning_a_swap_is_not_cached(swap: AccountSwap) {
        assert_fetch_spanning_a_swap_is_not_cached(
            swap,
            JellybeamCore::list_collections,
            |core| {
                let state = core.lock_state();
                state
                    .account
                    .installed()
                    .is_none_or(|i| i.caches.collections.is_none())
            },
            (
                "/Items",
                "includeItemTypes=BoxSet",
                "includeItemTypes=BoxSet",
            ),
            vec![CollectionInfo {
                id: "00000000-0000-0000-0000-000000000030".to_string(),
                name: "Sample Collection B".to_string(),
            }],
        );
    }

    fn assert_admin_fetch_spanning_a_swap_is_not_cached(swap: AccountSwap) {
        assert_fetch_spanning_a_swap_is_not_cached(
            swap,
            JellybeamCore::is_administrator,
            |core| {
                let state = core.lock_state();
                state
                    .account
                    .installed()
                    .is_none_or(|i| i.caches.is_admin.is_none())
            },
            ("/Users/Me", "", "/Users/Me"),
            false,
        );
    }

    #[test]
    fn list_collections_fetch_spanning_a_switch_is_not_cached() {
        assert_collections_fetch_spanning_a_swap_is_not_cached(AccountSwap::Switch);
    }

    #[test]
    fn list_collections_fetch_spanning_a_sign_in_is_not_cached() {
        assert_collections_fetch_spanning_a_swap_is_not_cached(AccountSwap::SignIn);
    }

    #[test]
    fn list_collections_fetch_spanning_a_remove_of_the_active_account_is_not_cached() {
        assert_collections_fetch_spanning_a_swap_is_not_cached(AccountSwap::RemoveActive);
    }

    #[test]
    fn is_administrator_fetch_spanning_a_switch_is_not_cached() {
        assert_admin_fetch_spanning_a_swap_is_not_cached(AccountSwap::Switch);
    }

    #[test]
    fn is_administrator_fetch_spanning_a_sign_in_is_not_cached() {
        assert_admin_fetch_spanning_a_swap_is_not_cached(AccountSwap::SignIn);
    }

    #[test]
    fn is_administrator_fetch_spanning_a_remove_of_the_active_account_is_not_cached() {
        assert_admin_fetch_spanning_a_swap_is_not_cached(AccountSwap::RemoveActive);
    }

    /// docs/19 §2.3: a `/Users/Me` reply without a `Policy` proves nothing about the role, so
    /// the guess is not stored and the next call asks the server again.
    #[test]
    fn is_administrator_does_not_cache_a_reply_without_a_policy() {
        let (_dir, core, mock) = core_signed_in_against_routes();
        mock.route(
            "GET",
            "/Users/Me",
            200,
            serde_json::json!({
                "Id": "00000000-0000-0000-0000-000000000040",
                "Name": "Sample User"
            }),
        );

        assert!(!core.is_administrator().expect("first call"));
        assert!(
            core.lock_state()
                .account
                .installed()
                .is_some_and(|i| i.caches.is_admin.is_none()),
            "an unknown role must not be cached as false"
        );
        let _ = core.is_administrator();
        assert_eq!(
            mock.hit_count("GET", "/Users/Me"),
            2,
            "the second call asks the server again"
        );
    }

    /// Full-sync `/Items` payload for one series/season/three-episode tree,
    /// the third episode virtual -- shared setup for the bulk-mark test
    /// below.
    fn series_with_three_episodes_json(
        series_id: &str,
        season_id: &str,
        ep1_id: &str,
        ep2_id: &str,
        ep3_virtual_id: &str,
    ) -> serde_json::Value {
        serde_json::json!({
            "Items": [
                {"Id": series_id, "Name": "Sample Series", "Type": "Series"},
                {
                    "Id": season_id, "Name": "Season 1", "Type": "Season",
                    "SeriesId": series_id, "IndexNumber": 1
                },
                {
                    "Id": ep1_id, "Name": "Episode 1", "Type": "Episode",
                    "SeriesId": series_id, "SeasonId": season_id,
                    "ParentIndexNumber": 1, "IndexNumber": 1,
                    "UserData": {"Key": "k", "Played": false}
                },
                {
                    "Id": ep2_id, "Name": "Episode 2", "Type": "Episode",
                    "SeriesId": series_id, "SeasonId": season_id,
                    "ParentIndexNumber": 1, "IndexNumber": 2,
                    "UserData": {"Key": "k", "Played": false}
                },
                {
                    "Id": ep3_virtual_id, "Name": "Episode 3", "Type": "Episode",
                    "SeriesId": series_id, "SeasonId": season_id,
                    "ParentIndexNumber": 1, "IndexNumber": 3,
                    "LocationType": "Virtual",
                    "UserData": {"Key": "k", "Played": false}
                }
            ],
            "TotalRecordCount": 5
        })
    }

    /// A stop re-fetches the episode's season and series once the Stopped
    /// report has landed, so their unplayed counts follow the server without
    /// waiting on a `UserDataChanged` event that never names the series.
    #[test]
    fn stopping_an_episode_refetches_its_season_and_series_after_the_stopped_report() {
        const VIEW_ID: &str = "00000000-0000-0000-0000-000000000010";
        const SERIES_ID: &str = "00000000-0000-0000-0000-000000000020";
        const SEASON_ID: &str = "00000000-0000-0000-0000-000000000021";
        const EP1_ID: &str = "00000000-0000-0000-0000-000000000022";
        const EP2_ID: &str = "00000000-0000-0000-0000-000000000023";
        const EP3_VIRTUAL_ID: &str = "00000000-0000-0000-0000-000000000024";

        let (_dir, core, mock) = core_signed_in_against_routes();
        mock.route(
            "GET",
            "/UserViews",
            200,
            user_views_json(VIEW_ID, "tvshows"),
        );
        mock.route(
            "GET",
            "/Items",
            200,
            series_with_three_episodes_json(SERIES_ID, SEASON_ID, EP1_ID, EP2_ID, EP3_VIRTUAL_ID),
        );
        let (_, playback_info_json) = direct_play_fixture();
        mock.route_script(
            "POST",
            "/PlaybackInfo",
            vec![playback_info_json],
            vec![Duration::ZERO],
        );

        core.open_mirror()
            .expect("open_mirror against a loopback server");
        wait_until(
            || core.card_by_id(EP1_ID.to_string()).ok().flatten().is_some(),
            "initial sync to seed the episode",
        );
        let plan = core
            .prepare_playback_now(EP1_ID.to_string(), false)
            .expect("Direct Play negotiation");
        let scope_fetches_before = mock.hit_count_containing("GET", SERIES_ID);
        let next_up_before = mock.hit_count("GET", "/Shows/NextUp");

        core.stop_playback(plan.play_session_id, 5_000_000);

        // docs/07 §1: the stop also re-fetches Next Up, so Home shows the following episode.
        wait_until(
            || mock.hit_count("GET", "/Shows/NextUp") > next_up_before,
            "the Next Up re-fetch after an episode stop",
        );

        wait_until(
            || mock.hit_count_containing("GET", SERIES_ID) > scope_fetches_before,
            "the season/series re-fetch after the stop",
        );
        assert_eq!(
            mock.hit_count("POST", "/Sessions/Playing/Stopped"),
            1,
            "the re-fetch follows the Stopped report"
        );
        assert!(
            unlock(&mock.shared.log).iter().any(|entry| {
                entry.starts_with("GET ")
                    && entry.contains("ids=")
                    && entry.contains(SEASON_ID)
                    && entry.contains(SERIES_ID)
            }),
            "one by-id fetch covers both the season and the series"
        );
    }

    /// A signed-in core with one unplayed 120-minute movie seeded into the mirror.
    fn end_grace_movie_fixture(
        view_id: &str,
        movie_id: &str,
    ) -> (tempfile::TempDir, Arc<JellybeamCore>, RouteMockServer) {
        movie_fixture(view_id, movie_id, true)
    }

    /// [`end_grace_movie_fixture`], with the server answering every Stopped report 500 when
    /// `stopped_ok` is false.
    fn movie_fixture(
        view_id: &str,
        movie_id: &str,
        stopped_ok: bool,
    ) -> (tempfile::TempDir, Arc<JellybeamCore>, RouteMockServer) {
        let (dir, core, mock) = core_signed_in_against_routes();
        mock.route("GET", "/UserViews", 200, user_views_json(view_id, "movies"));
        mock.route(
            "GET",
            "/Items",
            200,
            serde_json::json!({
                "Items": [{
                    "Id": movie_id, "Name": "Sample Movie", "Type": "Movie",
                    "RunTimeTicks": 120 * MIN,
                    "UserData": {"Key": "k", "Played": false}
                }],
                "TotalRecordCount": 1
            }),
        );
        // The final-Stopped flush queue dedups by session id process-wide, so each test needs its own.
        let (_, playback_info_json) = playback_fixture(
            "Test Movie",
            "ms-1",
            Some("mkv"),
            true,
            None,
            None,
            &format!("play-session-{movie_id}"),
        );
        mock.route_script("POST", "/PlaybackInfo", vec![playback_info_json], vec![]);
        if stopped_ok {
            mock.route_script(
                "POST",
                "/Sessions/Playing/Stopped",
                vec![serde_json::json!({})],
                vec![],
            );
        } else {
            mock.route(
                "POST",
                "/Sessions/Playing/Stopped",
                500,
                serde_json::json!({}),
            );
        }
        core.open_mirror()
            .expect("open_mirror against a loopback server");
        let id = movie_id.to_string();
        wait_until(
            || core.card_by_id(id.clone()).ok().flatten().is_some(),
            "initial sync to seed the movie",
        );
        (dir, core, mock)
    }

    /// The sweep's pending set holds canonical (undashed lowercase) ids.
    fn item_is_pending(item_id: &str) -> bool {
        jellyfin_core::pending_local_item_ids().contains(&item_id.replace('-', "").to_lowercase())
    }

    /// Covers the first retry backoff (up to 1.25 s) with room for a loaded test host.
    const RETRY_WAIT: Duration = Duration::from_secs(10);

    /// Stopped reports the server has received for `item_id`.
    fn stopped_reports_for(mock: &RouteMockServer, item_id: &str) -> usize {
        mock.request_bodies_containing("POST", "/Sessions/Playing/Stopped")
            .iter()
            .filter(|body| body["ItemId"].as_str() == Some(item_id))
            .count()
    }

    fn routed_stopped_positions(mock: &RouteMockServer) -> Vec<i64> {
        mock.request_bodies_containing("POST", "/Sessions/Playing/Stopped")
            .iter()
            .filter_map(|body| body["PositionTicks"].as_i64())
            .collect()
    }

    /// docs/07 §1: a stop in the end grace reports the runtime as the Stopped position, which the
    /// server turns into a finished watch, so no separate mark played is sent.
    #[test]
    fn end_grace_stop_reports_the_runtime_and_sends_no_mark_played() {
        const VIEW_ID: &str = "00000000-0000-0000-0000-000000000010";
        const MOVIE_ID: &str = "00000000-0000-0000-0000-000000000030";
        let (_dir, core, mock) = end_grace_movie_fixture(VIEW_ID, MOVIE_ID);

        let plan = core
            .prepare_playback_now(MOVIE_ID.to_string(), false)
            .expect("Direct Play negotiation");
        core.stop_playback(plan.play_session_id, 115 * MIN);
        wait_until(
            || !item_is_pending(MOVIE_ID),
            "the Stopped report to settle",
        );

        assert_eq!(routed_stopped_positions(&mock), vec![120 * MIN]);
        assert_eq!(
            mock.hit_count_containing("POST", "/UserPlayedItems"),
            0,
            "the Stopped carries the finish; no separate mark"
        );
        let card = core
            .card_by_id(MOVIE_ID.to_string())
            .expect("card_by_id")
            .expect("movie row");
        assert!(card.played);
        assert_eq!(card.position_ticks, 0);
    }

    /// docs/07 §1: a stop before the end grace reports the real position and leaves the item
    /// unfinished.
    #[test]
    fn mid_film_stop_reports_the_real_position() {
        const VIEW_ID: &str = "00000000-0000-0000-0000-000000000010";
        const MOVIE_ID: &str = "00000000-0000-0000-0000-000000000031";
        let (_dir, core, mock) = end_grace_movie_fixture(VIEW_ID, MOVIE_ID);

        let plan = core
            .prepare_playback_now(MOVIE_ID.to_string(), false)
            .expect("Direct Play negotiation");
        core.stop_playback(plan.play_session_id, 60 * MIN);
        wait_until(
            || !item_is_pending(MOVIE_ID),
            "the Stopped report to settle",
        );

        assert_eq!(routed_stopped_positions(&mock), vec![60 * MIN]);
        assert_eq!(mock.hit_count_containing("POST", "/UserPlayedItems"), 0);
        let card = core
            .card_by_id(MOVIE_ID.to_string())
            .expect("card_by_id")
            .expect("movie row");
        assert!(!card.played);
        assert_eq!(card.position_ticks, 60 * MIN);
    }

    /// docs/07 Watch state: a Mark Unplayed issued while the end-grace Stopped is still in flight
    /// is sent only after the Stopped settles, so the server applies them in order.
    #[test]
    fn mark_unplayed_waits_for_the_end_grace_stopped() {
        const VIEW_ID: &str = "00000000-0000-0000-0000-000000000010";
        const MOVIE_ID: &str = "00000000-0000-0000-0000-000000000032";
        let (_dir, core, mock) = end_grace_movie_fixture(VIEW_ID, MOVIE_ID);
        let played_path = format!("/UserPlayedItems/{MOVIE_ID}");
        mock.route(
            "DELETE",
            &played_path,
            200,
            user_item_data_json(Some(false), None),
        );
        let stopped = mock.gate("POST", "/Sessions/Playing/Stopped", 0);

        let plan = core
            .prepare_playback_now(MOVIE_ID.to_string(), false)
            .expect("Direct Play negotiation");
        core.stop_playback(plan.play_session_id, 115 * MIN);
        wait_until(
            || mock.hit_count_containing("POST", "/Sessions/Playing/Stopped") == 1,
            "the Stopped report to arrive",
        );

        let unmark = {
            let core = core.clone();
            std::thread::spawn(move || core.set_played(MOVIE_ID.to_string(), false))
        };
        std::thread::sleep(Duration::from_millis(500));
        assert_eq!(
            mock.hit_count_containing("DELETE", &played_path),
            0,
            "the mark waits while the Stopped is in flight"
        );

        stopped.open();
        unmark.join().expect("mark thread").expect("mark unplayed");
        assert_eq!(mock.hit_count_containing("DELETE", &played_path), 1);
        wait_until(
            || {
                core.card_by_id(MOVIE_ID.to_string())
                    .ok()
                    .flatten()
                    .is_some_and(|card| !card.played)
            },
            "the Mark Unplayed to reach the mirror",
        );
    }

    /// docs/07 Watch state, Ordering: a Mark Unplayed supersedes a Stopped that is failing and in
    /// backoff: the DELETE goes out at once and that Stopped is never sent again.
    #[test]
    fn mark_unplayed_cancels_a_stopped_in_backoff() {
        const VIEW_ID: &str = "00000000-0000-0000-0000-000000000010";
        const MOVIE_ID: &str = "00000000-0000-0000-0000-000000000033";
        let (_dir, core, mock) = movie_fixture(VIEW_ID, MOVIE_ID, false);
        let played_path = format!("/UserPlayedItems/{MOVIE_ID}");
        mock.route(
            "DELETE",
            &played_path,
            200,
            user_item_data_json(Some(false), None),
        );

        let plan = core
            .prepare_playback_now(MOVIE_ID.to_string(), false)
            .expect("Direct Play negotiation");
        core.stop_playback(plan.play_session_id, 115 * MIN);
        wait_until_for(
            RETRY_WAIT,
            || stopped_reports_for(&mock, MOVIE_ID) >= 2,
            "the Stopped to fail once and retry",
        );

        let started = Instant::now();
        core.set_played(MOVIE_ID.to_string(), false)
            .expect("mark unplayed");
        assert!(
            started.elapsed() < Duration::from_secs(1),
            "the mark does not wait on a Stopped that is only in backoff"
        );
        assert_eq!(mock.hit_count_containing("DELETE", &played_path), 1);

        let sent = stopped_reports_for(&mock, MOVIE_ID);
        std::thread::sleep(Duration::from_millis(3500));
        assert_eq!(
            stopped_reports_for(&mock, MOVIE_ID),
            sent,
            "no Stopped attempt after the mark"
        );
        wait_until(
            || !item_is_pending(MOVIE_ID),
            "the cancelled hold to release",
        );
    }

    /// docs/07 Watch state, Ordering: playing the item again cancels the older Stopped's retries.
    #[test]
    fn replaying_an_item_cancels_the_older_stopped_retries() {
        const VIEW_ID: &str = "00000000-0000-0000-0000-000000000010";
        const MOVIE_ID: &str = "00000000-0000-0000-0000-000000000034";
        let (_dir, core, mock) = movie_fixture(VIEW_ID, MOVIE_ID, false);

        let plan = core
            .prepare_playback_now(MOVIE_ID.to_string(), false)
            .expect("Direct Play negotiation");
        core.stop_playback(plan.play_session_id, 60 * MIN);
        wait_until_for(
            RETRY_WAIT,
            || stopped_reports_for(&mock, MOVIE_ID) >= 2,
            "the Stopped to fail once and retry",
        );

        core.prepare_playback_now(MOVIE_ID.to_string(), false)
            .expect("the replay's negotiation");
        let sent = stopped_reports_for(&mock, MOVIE_ID);
        std::thread::sleep(Duration::from_millis(3500));
        assert_eq!(
            stopped_reports_for(&mock, MOVIE_ID),
            sent,
            "the replay cancelled the older Stopped"
        );
    }

    /// docs/07 Watch state, Ordering: a season mark supersedes only the Stopped reports of the
    /// episodes it covers and never waits on an unrelated item's.
    #[test]
    fn season_mark_supersedes_only_the_stopped_reports_in_its_scope() {
        const VIEW_ID: &str = "00000000-0000-0000-0000-000000000010";
        const SERIES_ID: &str = "00000000-0000-0000-0000-000000000050";
        const SEASON_ID: &str = "00000000-0000-0000-0000-000000000051";
        const EP1_ID: &str = "00000000-0000-0000-0000-000000000052";
        const EP2_ID: &str = "00000000-0000-0000-0000-000000000053";
        const EP3_VIRTUAL_ID: &str = "00000000-0000-0000-0000-000000000054";
        const OTHER_SEASON_ID: &str = "00000000-0000-0000-0000-000000000056";
        const OTHER_EP_ID: &str = "00000000-0000-0000-0000-000000000057";

        let (_dir, core, mock) = core_signed_in_against_routes();
        mock.route(
            "GET",
            "/UserViews",
            200,
            user_views_json(VIEW_ID, "tvshows"),
        );
        let mut items =
            series_with_three_episodes_json(SERIES_ID, SEASON_ID, EP1_ID, EP2_ID, EP3_VIRTUAL_ID);
        items["Items"].as_array_mut().expect("items").extend([
            serde_json::json!({
                "Id": OTHER_SEASON_ID, "Name": "Season 2", "Type": "Season",
                "SeriesId": SERIES_ID, "IndexNumber": 2
            }),
            serde_json::json!({
                "Id": OTHER_EP_ID, "Name": "Episode 4", "Type": "Episode",
                "SeriesId": SERIES_ID, "SeasonId": OTHER_SEASON_ID,
                "ParentIndexNumber": 2, "IndexNumber": 1,
                "UserData": {"Key": "k", "Played": false}
            }),
        ]);
        items["TotalRecordCount"] = serde_json::json!(7);
        mock.route("GET", "/Items", 200, items);
        mock.route(
            "POST",
            &format!("/UserPlayedItems/{SEASON_ID}"),
            200,
            user_item_data_json(Some(true), None),
        );
        // One session id per stop: the final-Stopped flush queue dedups by it process-wide.
        let session_ids = ["season-mark-session-a", "season-mark-session-b"];
        let playback_infos = session_ids
            .iter()
            .map(|id| playback_fixture("Test Movie", "ms-1", Some("mkv"), true, None, None, id).1)
            .collect();
        mock.route_script("POST", "/PlaybackInfo", playback_infos, vec![]);
        mock.route(
            "POST",
            "/Sessions/Playing/Stopped",
            500,
            serde_json::json!({}),
        );
        core.open_mirror()
            .expect("open_mirror against a loopback server");
        wait_until(
            || {
                core.card_by_id(EP2_ID.to_string()).ok().flatten().is_some()
                    && core
                        .card_by_id(OTHER_EP_ID.to_string())
                        .ok()
                        .flatten()
                        .is_some()
            },
            "initial sync to seed the episodes",
        );

        for (item_id, ticks) in [(EP1_ID, 5_000_000), (OTHER_EP_ID, 5_000_000)] {
            let plan = core
                .prepare_playback_now(item_id.to_string(), false)
                .expect("Direct Play negotiation");
            core.stop_playback(plan.play_session_id, ticks);
        }
        wait_until_for(
            RETRY_WAIT,
            || {
                stopped_reports_for(&mock, EP1_ID) >= 2
                    && stopped_reports_for(&mock, OTHER_EP_ID) >= 2
            },
            "both Stopped reports to fail once and retry",
        );

        let started = Instant::now();
        core.set_played_recursive(SEASON_ID.to_string(), true)
            .expect("season mark");
        assert!(
            started.elapsed() < Duration::from_secs(1),
            "the season mark does not wait on any Stopped"
        );

        let (episode_sent, other_sent) = (
            stopped_reports_for(&mock, EP1_ID),
            stopped_reports_for(&mock, OTHER_EP_ID),
        );
        std::thread::sleep(Duration::from_millis(3500));
        assert_eq!(
            stopped_reports_for(&mock, EP1_ID),
            episode_sent,
            "the in-scope episode's Stopped is cancelled"
        );
        assert!(
            stopped_reports_for(&mock, OTHER_EP_ID) > other_sent,
            "an episode outside the season's Stopped keeps retrying"
        );
        wait_until(|| !item_is_pending(EP1_ID), "the cancelled hold to release");
        assert!(item_is_pending(OTHER_EP_ID));
    }

    #[derive(Clone, Copy, Debug)]
    enum MarkEntry {
        Played,
        Unplayed,
        SeasonRecursive,
    }

    #[derive(Clone, Copy, Debug)]
    enum StoppedState {
        InBackoff,
        OnTheWire,
        NotYetAttempted,
    }

    /// One cell of the Ordering table (docs/07 Watch state): a failing-then-landing end-grace
    /// Stopped in `state`, then `entry` with the mark answering 200 or 500.
    fn run_ordering_cell(cell: usize, entry: MarkEntry, state: StoppedState, mark_ok: bool) {
        let id = |k: usize| format!("00000000-0000-0000-0000-{:012x}", 0xa000 + cell * 0x10 + k);
        let (view, movie, series, season, episode) = (id(0), id(1), id(2), id(3), id(4));
        let (stopped_item, runtime, stop_at, collection, mark_method, mark_path, played) =
            match entry {
                MarkEntry::Played | MarkEntry::Unplayed => (
                    movie.clone(),
                    120 * MIN,
                    115 * MIN,
                    "movies",
                    if matches!(entry, MarkEntry::Played) {
                        "POST"
                    } else {
                        "DELETE"
                    },
                    format!("/UserPlayedItems/{movie}"),
                    matches!(entry, MarkEntry::Played),
                ),
                MarkEntry::SeasonRecursive => (
                    episode.clone(),
                    45 * MIN,
                    44 * MIN,
                    "tvshows",
                    "POST",
                    format!("/UserPlayedItems/{season}"),
                    true,
                ),
            };
        let items = match entry {
            MarkEntry::Played | MarkEntry::Unplayed => serde_json::json!({
                "Items": [{
                    "Id": movie, "Name": "Sample Movie", "Type": "Movie",
                    "RunTimeTicks": runtime, "UserData": {"Key": "k", "Played": !played}
                }],
                "TotalRecordCount": 1
            }),
            MarkEntry::SeasonRecursive => serde_json::json!({
                "Items": [
                    {"Id": series, "Name": "Sample Series", "Type": "Series"},
                    {"Id": season, "Name": "Season 1", "Type": "Season",
                     "SeriesId": series, "IndexNumber": 1},
                    {"Id": episode, "Name": "Episode 1", "Type": "Episode",
                     "SeriesId": series, "SeasonId": season, "ParentIndexNumber": 1,
                     "IndexNumber": 1, "RunTimeTicks": runtime,
                     "UserData": {"Key": "k", "Played": false}}
                ],
                "TotalRecordCount": 3
            }),
        };

        let (_dir, core, mock) = core_signed_in_against_routes();
        mock.route("GET", "/UserViews", 200, user_views_json(&view, collection));
        mock.route("GET", "/Items", 200, items);
        mock.route(
            mark_method,
            &mark_path,
            if mark_ok { 200 } else { 500 },
            user_item_data_json(Some(played), None),
        );
        let (_, playback_info) = playback_fixture(
            "Test Movie",
            "ms-1",
            Some("mkv"),
            true,
            None,
            None,
            &format!("ordering-session-{cell}"),
        );
        mock.route_script("POST", "/PlaybackInfo", vec![playback_info], vec![]);
        // The actor's inline attempt and the flush's first attempt both fail before a backoff.
        let failures = if matches!(state, StoppedState::InBackoff) {
            2
        } else {
            1
        };
        mock.fail_first("POST", "/Sessions/Playing/Stopped", failures);
        let mark_gate = mock.gate(mark_method, &mark_path, 0);
        let stopped_gate = matches!(state, StoppedState::OnTheWire)
            .then(|| mock.gate("POST", "/Sessions/Playing/Stopped", 0));
        let start_gates = matches!(state, StoppedState::NotYetAttempted).then(|| {
            // The actor is stuck in the first Start; the Start-repair is the final delivery's own.
            (
                mock.gate("POST", "/Sessions/Playing", 0),
                mock.gate("POST", "/Sessions/Playing", 1),
            )
        });
        core.open_mirror().expect("open_mirror");
        wait_until(
            || {
                core.card_by_id(stopped_item.clone())
                    .ok()
                    .flatten()
                    .is_some()
            },
            "initial sync to seed the item",
        );

        let plan = core
            .prepare_playback_now(stopped_item.clone(), false)
            .expect("Direct Play negotiation");
        wait_until(
            || mock.hit_count_containing("POST", "/Sessions/Playing") >= 1,
            "the session's Start",
        );
        core.stop_playback(plan.play_session_id, stop_at);
        match state {
            StoppedState::InBackoff => wait_until_for(
                RETRY_WAIT,
                || stopped_reports_for(&mock, &stopped_item) >= 2,
                "the Stopped to fail into its backoff",
            ),
            StoppedState::OnTheWire => wait_until(
                || stopped_reports_for(&mock, &stopped_item) >= 1,
                "the Stopped to reach the wire",
            ),
            StoppedState::NotYetAttempted => wait_until(
                || mock.hit_count_containing("POST", "/Sessions/Playing") >= 2,
                "the Start-repair to reach the wire",
            ),
        }

        let mark = {
            let core = core.clone();
            let (movie, season) = (movie.clone(), season.clone());
            std::thread::spawn(move || match entry {
                MarkEntry::Played => core.set_played(movie, true),
                MarkEntry::Unplayed => core.set_played(movie, false),
                MarkEntry::SeasonRecursive => core.set_played_recursive(season, true),
            })
        };
        if !matches!(state, StoppedState::InBackoff) {
            std::thread::sleep(Duration::from_millis(300));
            assert_eq!(
                mock.hit_count_containing(mark_method, &mark_path),
                0,
                "the mark waits for the request on the wire"
            );
            if let Some(gate) = &stopped_gate {
                gate.open();
            }
            if let Some((_, repair)) = &start_gates {
                repair.open();
            }
        }
        wait_until(
            || mock.hit_count_containing(mark_method, &mark_path) == 1,
            "the mark to reach the server",
        );
        // The mark is in flight: the Stopped is held, not sent, and not released, even across its
        // backoff.
        let sent = mock.hit_count_containing("POST", "/Sessions/Playing");
        std::thread::sleep(Duration::from_secs(3));
        assert_eq!(
            mock.hit_count_containing("POST", "/Sessions/Playing"),
            sent,
            "nothing is sent while the mark is in flight"
        );
        assert!(item_is_pending(&stopped_item));
        mark_gate.open();
        let result = mark.join().expect("mark thread");
        assert_eq!(result.is_ok(), mark_ok, "mark result {result:?}");
        let mark_at = mock
            .first_request_containing(&mark_path)
            .expect("the mark reached the server");

        if mark_ok {
            std::thread::sleep(Duration::from_millis(3500));
            let after_mark = mock.log_snapshot().split_off(mark_at + 1);
            assert!(
                after_mark
                    .iter()
                    .all(|entry| !entry.starts_with("POST /Sessions/Playing")),
                "no Start or Stopped after the mark: {after_mark:?}"
            );
            wait_until(
                || !item_is_pending(&stopped_item),
                "the cancelled hold to release",
            );
            if matches!(entry, MarkEntry::SeasonRecursive) {
                // The mark refreshes its own scope; the cancelled Stopped adds no second recount.
                let scope_fetches = after_mark
                    .iter()
                    .filter(|entry| entry.starts_with("GET ") && entry.contains(&season))
                    .count();
                assert_eq!(scope_fetches, 1, "{after_mark:?}");
            }
        } else {
            wait_until_for(
                RETRY_WAIT,
                || !item_is_pending(&stopped_item),
                "the resumed Stopped to land",
            );
            let positions: Vec<i64> = mock
                .request_bodies_containing("POST", "/Sessions/Playing/Stopped")
                .iter()
                .filter(|body| body["ItemId"].as_str() == Some(stopped_item.as_str()))
                .filter_map(|body| body["PositionTicks"].as_i64())
                .collect();
            assert!(positions.len() >= 2, "a failure, then the landing");
            assert!(positions.iter().all(|ticks| *ticks == runtime));
        }
    }

    /// docs/07 Watch state, Ordering: a mark pauses the item's Stopped (in backoff, on the wire or
    /// not yet attempted) and cancels it only once the mark succeeds; a failed mark lets it land.
    #[test]
    fn a_mark_cancels_the_stopped_only_when_it_succeeds() {
        let mut cells = Vec::new();
        for entry in [
            MarkEntry::Played,
            MarkEntry::Unplayed,
            MarkEntry::SeasonRecursive,
        ] {
            for mark_ok in [true, false] {
                for state in [
                    StoppedState::InBackoff,
                    StoppedState::OnTheWire,
                    StoppedState::NotYetAttempted,
                ] {
                    cells.push((entry, mark_ok, state));
                }
            }
        }
        let handles: Vec<_> = cells
            .into_iter()
            .enumerate()
            .map(|(cell, (entry, mark_ok, state))| {
                let label = format!("{entry:?} mark_ok={mark_ok} {state:?}");
                let handle = std::thread::spawn(move || {
                    run_ordering_cell(cell, entry, state, mark_ok);
                });
                (label, handle)
            })
            .collect();
        let failed: Vec<String> = handles
            .into_iter()
            .filter_map(|(label, handle)| handle.join().err().map(|_| label))
            .collect();
        assert!(failed.is_empty(), "failing cells: {failed:#?}");
    }

    /// A mirror row for [`final_report`]: an item of `item_type` with the given runtime.
    fn grace_row(item_type: &str, runtime_ticks: Option<i64>) -> media_cache::CardRow {
        media_cache::CardRow {
            id: "00000000-0000-0000-0000-0000000000f1".to_string(),
            item_type: item_type.to_string(),
            name: "Sample".to_string(),
            primary_tag: None,
            backdrop_tag: None,
            thumb_tag: None,
            blurhash: None,
            played: false,
            position_ticks: 0,
            runtime_ticks,
            unplayed_count: None,
            production_year: None,
            index_number: None,
            premiere_date: None,
            parent_index_number: None,
            series_id: None,
            series_primary_tag: None,
            parent_backdrop_item_id: None,
            parent_backdrop_tag: None,
            parent_thumb_item_id: None,
            parent_thumb_tag: None,
            series_name: None,
            last_played_date: None,
            overview: None,
            is_virtual: false,
            library_id: None,
            is_favorite: false,
            date_created: None,
        }
    }

    #[test]
    fn scope_episode_ids_fails_closed_on_an_unreadable_scope_and_skips_virtual() {
        let real = grace_row("Episode", Some(45 * MIN));
        let virtual_row = media_cache::CardRow {
            id: "synthetic-virtual".to_string(),
            is_virtual: true,
            ..grace_row("Episode", None)
        };
        assert!(matches!(
            scope_episode_ids(None),
            Err(CoreError::Cache { .. })
        ));
        assert_eq!(
            scope_episode_ids(Some(vec![real.clone(), virtual_row])).expect("readable"),
            vec![real.id]
        );
        assert_eq!(
            scope_episode_ids(Some(Vec::new())).expect("empty"),
            Vec::<String>::new()
        );
    }

    #[test]
    fn final_report_substitutes_the_runtime_for_a_movie_in_the_end_grace() {
        let row = grace_row("Movie", Some(120 * MIN));
        assert_eq!(final_report(Some(&row), 115 * MIN), (120 * MIN, true));
    }

    #[test]
    fn final_report_substitutes_the_runtime_for_an_episode_in_the_end_grace() {
        let row = grace_row("Episode", Some(45 * MIN));
        assert_eq!(final_report(Some(&row), 44 * MIN), (45 * MIN, true));
    }

    #[test]
    fn final_report_keeps_the_position_mid_film() {
        let row = grace_row("Movie", Some(120 * MIN));
        assert_eq!(final_report(Some(&row), 60 * MIN), (60 * MIN, false));
    }

    #[test]
    fn final_report_keeps_the_position_when_the_runtime_is_unknown() {
        let row = grace_row("Movie", None);
        assert_eq!(final_report(Some(&row), 115 * MIN), (115 * MIN, false));
    }

    #[test]
    fn final_report_keeps_the_position_for_an_item_the_mirror_does_not_know() {
        assert_eq!(final_report(None, i64::MAX), (i64::MAX, false));
    }

    /// docs/19 §2.3: `set_played_recursive` marks every non-virtual episode
    /// locally, never a virtual one.
    #[test]
    fn set_played_recursive_marks_every_non_virtual_episode_and_skips_virtual() {
        const VIEW_ID: &str = "00000000-0000-0000-0000-000000000010";
        const SERIES_ID: &str = "00000000-0000-0000-0000-000000000060";
        const SEASON_ID: &str = "00000000-0000-0000-0000-000000000061";
        const EP1_ID: &str = "00000000-0000-0000-0000-000000000062";
        const EP2_ID: &str = "00000000-0000-0000-0000-000000000063";
        const EP3_VIRTUAL_ID: &str = "00000000-0000-0000-0000-000000000064";

        let (_dir, core, mock) = core_signed_in_against_routes();
        mock.route(
            "GET",
            "/UserViews",
            200,
            user_views_json(VIEW_ID, "tvshows"),
        );
        mock.route(
            "GET",
            "/Items",
            200,
            series_with_three_episodes_json(SERIES_ID, SEASON_ID, EP1_ID, EP2_ID, EP3_VIRTUAL_ID),
        );
        mock.route(
            "POST",
            &format!("/UserPlayedItems/{SERIES_ID}"),
            200,
            user_item_data_json(Some(true), None),
        );
        // The background `refresh_items([SERIES_ID, SEASON_ID])` re-fetches
        // by id; scope it to just those two rows so it cannot clobber the
        // episodes' locally-applied marks (docs/19 §2.3).
        mock.route_with_query(
            "GET",
            "/Items",
            "ids=",
            200,
            &serde_json::json!({
                "Items": [
                    {
                        "Id": SERIES_ID, "Name": "Sample Series", "Type": "Series",
                        "UserData": {"Key": "k", "Played": true}
                    },
                    {
                        "Id": SEASON_ID, "Name": "Season 1", "Type": "Season",
                        "SeriesId": SERIES_ID, "IndexNumber": 1,
                        "UserData": {"Key": "k", "Played": true}
                    }
                ],
                "TotalRecordCount": 2
            }),
        );

        core.open_mirror()
            .expect("open_mirror should succeed synchronously against a real loopback server");

        wait_until(
            || core.card_by_id(EP2_ID.to_string()).ok().flatten().is_some(),
            "initial sync to seed the series/season/episodes",
        );

        core.set_played_recursive(SERIES_ID.to_string(), true)
            .expect("set_played_recursive against mock server");

        wait_until(
            || {
                core.card_by_id(EP1_ID.to_string())
                    .ok()
                    .flatten()
                    .is_some_and(|c| c.played)
                    && core
                        .card_by_id(EP2_ID.to_string())
                        .ok()
                        .flatten()
                        .is_some_and(|c| c.played)
            },
            "local marks to commit for both real episodes",
        );

        let virtual_card = core
            .card_by_id(EP3_VIRTUAL_ID.to_string())
            .expect("mirror open")
            .expect("virtual episode row exists");
        assert!(
            !virtual_card.played,
            "a virtual episode must never be marked played by the bulk action"
        );
    }

    /// Sign-in fetches `/System/Info/Public` in the background; once it
    /// settles both the live gate and the on-disk record reflect it.
    #[test]
    fn sign_in_refreshes_server_version_and_persists_it() {
        // A real AuthenticationResult response's User.Id must parse as a
        // UUID (unlike the plain "u1" seeded directly elsewhere).
        const REAL_USER_ID: &str = "00000000-0000-0000-0000-000000000001";

        let (dir, core) = core_in_tempdir();
        let mock = core.runtime.block_on(RouteMockServer::start());
        mock.route(
            "POST",
            "/Users/AuthenticateByName",
            200,
            serde_json::json!({
                "User": {"Id": REAL_USER_ID, "Name": "jellybeam-user"},
                "AccessToken": "tok",
            }),
        );
        mock.route(
            "GET",
            "/System/Info/Public",
            200,
            serde_json::json!({"Version": "12.0.0"}),
        );

        let account = core
            .sign_in(
                mock.base_url.clone(),
                "user".to_string(),
                "pass".to_string(),
            )
            .expect("sign_in against mock server");
        assert_eq!(account.user_id, REAL_USER_ID);

        wait_until(
            || core.server_at_least(12, 0),
            "server_at_least(12, 0) once the background version refresh settles",
        );
        assert_eq!(core.server_version(), Some("12.0.0".to_string()));

        let sessions = session::load_list(dir.path());
        let record = sessions
            .sessions
            .iter()
            .find(|s| s.user_id == REAL_USER_ID)
            .expect("session was persisted");
        assert_eq!(record.server_version.as_deref(), Some("12.0.0"));
    }

    /// Two accounts on one install must never present the same `DeviceId`: Jellyfin revokes a
    /// user's token when that id signs in again, and a proxy forwards it to its backends.
    #[test]
    fn sign_in_persists_a_device_id_derived_for_that_server() {
        const REAL_USER_ID: &str = "00000000-0000-0000-0000-000000000001";
        let (dir, core) = core_in_tempdir();
        let mock = core.runtime.block_on(RouteMockServer::start());
        mock.route(
            "POST",
            "/Users/AuthenticateByName",
            200,
            serde_json::json!({
                "User": {"Id": REAL_USER_ID, "Name": "jellybeam-user"},
                "AccessToken": "tok",
            }),
        );

        core.sign_in(
            mock.base_url.clone(),
            "user".to_string(),
            "pass".to_string(),
        )
        .expect("sign_in against mock server");

        let record = session::load_list(dir.path())
            .sessions
            .into_iter()
            .find(|s| s.user_id == REAL_USER_ID)
            .expect("session was persisted");
        assert_eq!(
            record.device_id,
            device_id::for_server(&core.device_id, &mock.base_url)
        );
        assert_ne!(record.device_id, core.device_id);
        assert_ne!(
            record.device_id,
            device_id::for_server(&core.device_id, "http://other.example.test")
        );
    }

    /// A sign-in plus the account change's `open_mirror` opens one mirror and one bus, and
    /// removing the account stops them.
    #[test]
    fn sign_in_then_open_mirror_opens_once_and_removal_stops_the_bus() {
        let (_dir, core) = core_in_tempdir();
        let mock = core.runtime.block_on(RouteMockServer::start());
        mock.route(
            "POST",
            "/Users/AuthenticateByName",
            200,
            serde_json::json!({
                "User": {"Id": "00000000-0000-0000-0000-000000000003", "Name": "jellybeam-user"},
                "AccessToken": "tok",
            }),
        );

        core.sign_in(
            mock.base_url.clone(),
            "user".to_string(),
            "pass".to_string(),
        )
        .expect("sign_in against mock server");
        core.open_mirror()
            .expect("the account change opens the mirror");

        assert_eq!(core.mirror_open_count(), 1, "one sign-in, one mirror open");
        let forwarder_handle = core
            .lock_state()
            .account
            .bundle()
            .expect("open_mirror spawned a bundle")
            .forwarder
            .as_ref()
            .expect("open_mirror spawned a bus forwarder")
            .abort_handle();
        assert!(!forwarder_handle.is_finished());

        remove_active_account(&core);

        assert!(core.lock_state().account.bundle().is_none());
        assert!(
            forwarder_handle.is_finished(),
            "removal must actually stop the bus forwarder sign_in spawned"
        );
    }

    /// docs/13 "sign-in": a rejected password must not be shown as "session
    /// expired" -- that's for a saved token's reauthorization prompt, not a
    /// fresh sign-in attempt.
    #[test]
    fn sign_in_with_a_401_route_is_invalid_credentials() {
        let (_dir, core) = core_in_tempdir();
        let mock = core.runtime.block_on(RouteMockServer::start());
        mock.route(
            "POST",
            "/Users/AuthenticateByName",
            401,
            serde_json::json!({}),
        );

        let err = core
            .sign_in(
                mock.base_url.clone(),
                "user".to_string(),
                "wrong".to_string(),
            )
            .expect_err("401 must not sign in");
        assert!(matches!(err, CoreError::InvalidCredentials), "got {err:?}");
    }

    /// docs/13 "sign-in": a non-Jellyfin HTTP server (wrong port, reverse
    /// proxy default page, ...) must not leak its response body.
    #[test]
    fn sign_in_with_a_404_route_is_not_jellyfin_server() {
        let (_dir, core) = core_in_tempdir();
        let mock = core.runtime.block_on(RouteMockServer::start());
        mock.route(
            "POST",
            "/Users/AuthenticateByName",
            404,
            serde_json::json!({}),
        );

        let err = core
            .sign_in(
                mock.base_url.clone(),
                "user".to_string(),
                "pass".to_string(),
            )
            .expect_err("404 must not sign in");
        assert!(
            matches!(err, CoreError::NotJellyfinServer { status: 404, .. }),
            "got {err:?}"
        );
    }

    /// docs/13 "sign-in": an unusable address is caught before any network
    /// call -- no request should ever reach a listener.
    #[test]
    fn sign_in_against_an_invalid_address_makes_no_request() {
        let (_dir, core) = core_in_tempdir();
        let mock = core.runtime.block_on(RouteMockServer::start());

        let err = core
            .sign_in(
                "not a server".to_string(),
                "user".to_string(),
                "pass".to_string(),
            )
            .expect_err("not a URL");
        assert!(
            matches!(err, CoreError::InvalidServerAddress { .. }),
            "got {err:?}"
        );
        assert_eq!(
            mock.hit_count("POST", "/Users/AuthenticateByName"),
            0,
            "an invalid address must never reach any server"
        );
    }

    /// docs/13 "sign-in": a refused connection (nothing listening) is
    /// distinct from a reachable-but-wrong server, with a short reason
    /// instead of the raw transport error text.
    #[test]
    fn sign_in_against_a_refused_port_is_server_unreachable() {
        let (_dir, core) = core_in_tempdir();
        // Bind then drop: the OS keeps the port refusing connections rather
        // than reusing it immediately, same trick as the existing
        // 127.0.0.1:1 test but with a guaranteed-free ephemeral port.
        let listener = std::net::TcpListener::bind("127.0.0.1:0").expect("bind loopback");
        let addr = listener.local_addr().expect("local_addr");
        drop(listener);

        let err = core
            .sign_in(
                format!("http://{addr}"),
                "user".to_string(),
                "pass".to_string(),
            )
            .expect_err("nothing is listening on a just-dropped port");
        match err {
            CoreError::ServerUnreachable { reason, .. } => {
                assert_eq!(reason, crate::error::UnreachableReason::ConnectionRefused);
            }
            other => panic!("expected ServerUnreachable, got {other:?}"),
        }
    }

    // --- docs/18 §2.1 account boundary: server info, logout, reauthorization -------------------

    const ACCOUNT_A_ID: &str = "00000000-0000-0000-0000-0000000000a1";
    const ACCOUNT_B_ID: &str = "00000000-0000-0000-0000-0000000000b2";

    /// Saves an account whose server is `mock`, with `token` and a UUID user id; later saves are active.
    fn seed_account_on(
        dir: &std::path::Path,
        mock: &RouteMockServer,
        user_id: &str,
        token: &str,
    ) -> session::SessionFile {
        session::add_or_update(
            dir,
            session::SessionFile {
                server_url: mock.base_url.clone(),
                user_id: user_id.to_string(),
                user_name: format!("{user_id}-name"),
                token: token.to_string(),
                device_id: "dev".to_string(),
                mirror_dir: session::mirror_dir_name(&mock.base_url, user_id),
                server_version: None,
                server_name: None,
            },
        )
        .expect("seed a session file")
    }

    /// Account B (index 0, token `tok-b`) then account A (index 1, token `tok-a`, active and
    /// restored), each on its own mock server.
    fn two_accounts_on_mocks() -> (
        tempfile::TempDir,
        Arc<JellybeamCore>,
        RouteMockServer,
        RouteMockServer,
    ) {
        let (dir, core) = core_in_tempdir();
        let mock_a = core.runtime.block_on(RouteMockServer::start());
        let mock_b = core.runtime.block_on(RouteMockServer::start());
        seed_account_on(dir.path(), &mock_b, ACCOUNT_B_ID, "tok-b");
        seed_account_on(dir.path(), &mock_a, ACCOUNT_A_ID, "tok-a");
        assert_eq!(
            core.restore_session().expect("account A restores").user_id,
            ACCOUNT_A_ID
        );
        (dir, core, mock_a, mock_b)
    }

    fn logouts(mock: &RouteMockServer) -> usize {
        mock.hit_count("POST", "/Sessions/Logout")
    }

    /// Lets a fire-and-forget request that must NOT exist show up if it is going to.
    fn settle() {
        std::thread::sleep(Duration::from_millis(300));
    }

    #[test]
    fn server_details_split_admin_non_admin_and_revoked_token_rows() {
        enum Expect {
            Details { available: bool },
            Reauthorize,
        }
        // (name, cached admin flag, /System/Info status, expected /System/Info requests, expectation)
        let rows = [
            (
                "admin",
                Some(true),
                200,
                Some(1),
                Expect::Details { available: true },
            ),
            (
                "cached non-admin skips the admin endpoint",
                Some(false),
                403,
                Some(0),
                Expect::Details { available: false },
            ),
            (
                "role not yet known falls back on 403",
                None,
                403,
                None,
                Expect::Details { available: false },
            ),
            (
                "revoked token of a cached admin",
                Some(true),
                401,
                Some(1),
                Expect::Reauthorize,
            ),
            (
                "revoked token of an unknown role",
                None,
                401,
                None,
                Expect::Reauthorize,
            ),
        ];
        let mut failures = Vec::new();
        for (name, cached_admin, info_status, info_requests, expect) in rows {
            let (_dir, core, mock) = core_signed_in_against_routes();
            mock.route(
                "GET",
                "/System/Info",
                info_status,
                serde_json::json!({
                    "ServerName": "Synthetic Admin Name",
                    "Version": "12.0.0",
                    "ProductName": "Synthetic Server"
                }),
            );
            mock.route(
                "GET",
                "/System/Info/Public",
                200,
                serde_json::json!({"ServerName": "Synthetic Public Name", "Version": "12.0.0"}),
            );
            let epoch = core.account_epoch();
            assert!(core.store_if_epoch(epoch, |caches| caches.is_admin = cached_admin));

            let result = core.fetch_server_details();

            let requests = mock.hit_count("GET", "/System/Info");
            if info_requests.is_some_and(|expected| expected != requests) {
                failures.push(format!(
                    "{name}: {requests} admin-endpoint requests, expected {info_requests:?}"
                ));
            }
            let fine = match (&expect, &result) {
                (Expect::Details { available }, Ok(details)) => {
                    details.system_info_available == *available
                        && (*available
                            || details.server_name.as_deref() == Some("Synthetic Public Name"))
                }
                (Expect::Reauthorize, Err(CoreError::Unauthorized { account: Some(_) })) => true,
                _ => false,
            };
            if !fine {
                failures.push(format!("{name}: unexpected result {result:?}"));
            }
        }
        assert!(failures.is_empty(), "{failures:#?}");
    }

    /// docs/18 §2.1: removing the active account asks its server to end that account's session.
    #[test]
    fn remove_session_of_the_active_account_logs_it_out_with_its_own_token() {
        let (_dir, core, mock_a, mock_b) = two_accounts_on_mocks();

        assert!(core.remove_session(1).expect("active index exists"));

        wait_until(
            || logouts(&mock_a) >= 1,
            "a Logout at the removed account's server",
        );
        settle();
        assert_eq!(logouts(&mock_a), 1);
        let auth = mock_a.authorizations("POST", "/Sessions/Logout");
        assert!(auth[0].contains(r#"Token="tok-a""#), "{auth:?}");
        assert_eq!(logouts(&mock_b), 0, "the other account stays signed in");
    }

    #[test]
    fn remove_session_of_a_parked_account_logs_it_out_with_its_own_token() {
        let (_dir, core, mock_a, mock_b) = two_accounts_on_mocks();

        assert!(!core.remove_session(0).expect("parked index exists"));

        wait_until(
            || logouts(&mock_b) >= 1,
            "a Logout at the removed account's server",
        );
        settle();
        assert_eq!(logouts(&mock_b), 1);
        let auth = mock_b.authorizations("POST", "/Sessions/Logout");
        assert!(auth[0].contains(r#"Token="tok-b""#), "{auth:?}");
        assert_eq!(logouts(&mock_a), 0, "the active account stays signed in");
    }

    /// docs/18 §2.1: ending an account never revokes the token its final Stopped report still needs.
    #[test]
    fn logout_waits_for_the_final_stopped_of_the_account_it_ends() {
        {
            let (_dir, core, mock_a, _mock_b) = two_accounts_on_mocks();
            let (item_json, playback_info_json) = playback_fixture(
                "Test Movie",
                "ms-1",
                Some("mkv"),
                true,
                None,
                None,
                "play-session-logout-order",
            );
            mock_a.route("GET", "/Items", 200, item_json);
            mock_a.route_script("POST", "/PlaybackInfo", vec![playback_info_json], vec![]);
            let stopped = mock_a.gate("POST", "/Sessions/Playing/Stopped", 0);
            core.open_mirror().expect("account A's mirror opens");
            let plan = core
                .prepare_playback_now("item-1".to_string(), false)
                .expect("A plays");
            core.report_position(plan.play_session_id, 30_000_000);

            core.remove_session(1).expect("removes A");
            wait_until(
                || mock_a.hit_count("POST", "/Sessions/Playing/Stopped") >= 1,
                "the Stopped report to reach its server",
            );
            settle();
            assert_eq!(logouts(&mock_a), 0, "no Logout while the Stopped is held");

            stopped.open();
            wait_until(|| logouts(&mock_a) >= 1, "the Logout after the Stopped");
            let stopped_at = mock_a.first_request_containing("POST /Sessions/Playing/Stopped");
            let logout_at = mock_a.first_request_containing("POST /Sessions/Logout");
            assert!(
                stopped_at < logout_at,
                "Stopped at {stopped_at:?}, Logout at {logout_at:?}"
            );
        }
    }

    /// docs/18 §2.1: a Stopped still pending after the settle budget no longer holds the Logout.
    #[test]
    fn logout_goes_out_once_the_settle_budget_expires_with_the_stopped_still_held() {
        let (_dir, core, mock_a, _mock_b) = two_accounts_on_mocks();
        let (item_json, playback_info_json) = playback_fixture(
            "Test Movie",
            "ms-1",
            Some("mkv"),
            true,
            None,
            None,
            "play-session-logout-budget",
        );
        mock_a.route("GET", "/Items", 200, item_json);
        mock_a.route_script("POST", "/PlaybackInfo", vec![playback_info_json], vec![]);
        let stopped = mock_a.gate("POST", "/Sessions/Playing/Stopped", 0);
        core.open_mirror().expect("account A's mirror opens");
        let plan = core
            .prepare_playback_now("item-1".to_string(), false)
            .expect("A plays");
        core.report_position(plan.play_session_id, 30_000_000);

        let started = Instant::now();
        remove_active_account(&core);
        wait_until_for(
            FINAL_REPORT_SETTLE_BUDGET + Duration::from_secs(3),
            || logouts(&mock_a) >= 1,
            "the Logout after the settle budget",
        );
        assert!(
            started.elapsed() >= FINAL_REPORT_SETTLE_BUDGET,
            "Logout went out after {:?}, before the budget",
            started.elapsed()
        );
        settle();
        assert_eq!(logouts(&mock_a), 1);
        stopped.open();
    }

    /// docs/18 §2.1: a Stopped that lands inside the budget releases the Logout right away.
    #[test]
    fn logout_follows_a_stopped_that_lands_inside_the_settle_budget() {
        let (_dir, core, mock_a, _mock_b) = two_accounts_on_mocks();
        let (item_json, playback_info_json) = playback_fixture(
            "Test Movie",
            "ms-1",
            Some("mkv"),
            true,
            None,
            None,
            "play-session-logout-early",
        );
        mock_a.route("GET", "/Items", 200, item_json);
        mock_a.route_script("POST", "/PlaybackInfo", vec![playback_info_json], vec![]);
        let stopped = mock_a.gate("POST", "/Sessions/Playing/Stopped", 0);
        core.open_mirror().expect("account A's mirror opens");
        let plan = core
            .prepare_playback_now("item-1".to_string(), false)
            .expect("A plays");
        core.report_position(plan.play_session_id, 30_000_000);

        let started = Instant::now();
        remove_active_account(&core);
        wait_until(
            || mock_a.hit_count("POST", "/Sessions/Playing/Stopped") >= 1,
            "the Stopped report to reach its server",
        );
        stopped.open();
        wait_until(|| logouts(&mock_a) >= 1, "the Logout after the Stopped");
        assert!(
            started.elapsed() < FINAL_REPORT_SETTLE_BUDGET,
            "Logout waited the full budget: {:?}",
            started.elapsed()
        );
        settle();
        assert_eq!(logouts(&mock_a), 1);
    }

    /// docs/18 §2.1: the server's Logout is best effort; a slow or failing one never holds up or fails the removal.
    #[test]
    fn a_held_logout_never_delays_removal() {
        {
            let (_dir, core, mock_a, _mock_b) = two_accounts_on_mocks();
            let _held = mock_a.gate("POST", "/Sessions/Logout", 0);

            let started = Instant::now();
            core.remove_session(1).expect("removal succeeds");
            let elapsed = started.elapsed();

            assert!(
                elapsed < Duration::from_secs(1),
                "returned after {elapsed:?} with the Logout held"
            );
            assert!(
                core.list_accounts()
                    .iter()
                    .all(|a| a.user_id != ACCOUNT_A_ID),
                "the account is gone"
            );
            wait_until(
                || logouts(&mock_a) >= 1,
                "the held Logout to have been attempted",
            );
        }
    }

    #[test]
    fn a_failing_logout_does_not_fail_the_removal() {
        let (_dir, core, mock_a, mock_b) = two_accounts_on_mocks();
        mock_a.route("POST", "/Sessions/Logout", 500, serde_json::json!({}));
        mock_b.route("POST", "/Sessions/Logout", 500, serde_json::json!({}));

        assert!(!core
            .remove_session(0)
            .expect("a 500 Logout still removes the parked account"));
        wait_until(
            || logouts(&mock_b) >= 1,
            "the parked account's failing Logout",
        );
        assert!(core
            .remove_session(0)
            .expect("a 500 Logout still removes the active account"));

        wait_until(
            || logouts(&mock_a) >= 1,
            "the active account's failing Logout",
        );
        assert!(core.list_accounts().is_empty());
    }

    #[test]
    fn switch_session_sends_no_logout() {
        let (_dir, core, mock_a, mock_b) = two_accounts_on_mocks();

        core.switch_session(0).expect("account B exists");
        core.switch_session(1).expect("account A exists");

        settle();
        assert_eq!(logouts(&mock_a), 0);
        assert_eq!(logouts(&mock_b), 0);
    }

    fn route_authentication(mock: &RouteMockServer, user_id: &str, new_token: &str) {
        mock.route(
            "POST",
            "/Users/AuthenticateByName",
            200,
            serde_json::json!({
                "User": {"Id": user_id, "Name": "jellybeam-user"},
                "AccessToken": new_token,
            }),
        );
    }

    /// docs/18 §2.1: reauthorizing a parked account refreshes its token in place; the account being browsed stays.
    #[test]
    fn reauthorizing_a_parked_account_keeps_the_browsed_account_active() {
        let (_dir, core, mock_a, mock_b) = two_accounts_on_mocks();
        route_authentication(&mock_b, ACCOUNT_B_ID, "new-b");
        let epoch_before = core.account_epoch();

        let reauthorized = core
            .reauthorize_session(0, "user".to_string(), "pass".to_string())
            .expect("the parked account reauthorizes");

        assert_eq!(reauthorized.user_id, ACCOUNT_B_ID);
        assert_eq!(
            core.active_account_index(),
            Some(1),
            "the browsed account stays active"
        );
        assert_eq!(core.account_epoch(), epoch_before, "no account change");
        let saved = session::load_list(core.data_dir());
        assert_eq!(saved.sessions[0].token, "new-b", "B's token is replaced");
        assert_eq!(saved.sessions[1].token, "tok-a", "A's token is untouched");
        let live_user = core
            .require_client()
            .ok()
            .and_then(|c| c.user_id().map(str::to_string));
        assert_eq!(
            live_user.as_deref(),
            Some(ACCOUNT_A_ID),
            "A's client stays installed"
        );
        assert_eq!(mock_a.hit_count("POST", "/Users/AuthenticateByName"), 0);
    }

    #[test]
    fn reauthorizing_the_active_account_keeps_it_active_with_the_new_token() {
        let (_dir, core, mock_a, _mock_b) = two_accounts_on_mocks();
        route_authentication(&mock_a, ACCOUNT_A_ID, "new-a");

        core.reauthorize_session(1, "user".to_string(), "pass".to_string())
            .expect("the active account reauthorizes");

        assert_eq!(core.active_account_index(), Some(1));
        let saved = session::load_list(core.data_dir());
        assert_eq!(saved.sessions[1].token, "new-a");
        assert_eq!(saved.sessions[0].token, "tok-b");
    }

    const QUICK_CONNECT_USER_ID: &str = "00000000-0000-0000-0000-000000000001";

    /// Registers the three Quick Connect routes under `prefix` (a reverse-proxy path prefix, or "").
    fn route_quick_connect(mock: &RouteMockServer, prefix: &str) {
        mock.route(
            "POST",
            &format!("{prefix}/QuickConnect/Initiate"),
            200,
            serde_json::json!({"Code": "123456", "Secret": "s3cr3t", "Authenticated": false}),
        );
        mock.route(
            "GET",
            &format!("{prefix}/QuickConnect/Connect"),
            200,
            serde_json::json!({"Code": "123456", "Secret": "s3cr3t", "Authenticated": true}),
        );
        mock.route(
            "POST",
            &format!("{prefix}/Users/AuthenticateWithQuickConnect"),
            200,
            serde_json::json!({
                "User": {"Id": QUICK_CONNECT_USER_ID, "Name": "Sample User"},
                "AccessToken": "tok",
            }),
        );
    }

    /// docs/13 "sign-in" (Quick Connect): every step sends the typed address to the same normalized
    /// base, so a scheme-less, slashed, upper-case or proxied address works for the whole handshake.
    fn assert_quick_connect_reaches_normalized_base(
        address: impl Fn(&RouteMockServer) -> String,
        prefix: &str,
    ) {
        let (_dir, core) = core_in_tempdir();
        let mock = core.runtime.block_on(RouteMockServer::start());
        route_quick_connect(&mock, prefix);
        let address = address(&mock);

        let session = core.initiate_quick_connect(address.clone());
        let polled = core.poll_quick_connect(address.clone(), "s3cr3t".to_string());
        let completed = core.complete_quick_connect(address.clone(), "s3cr3t".to_string());

        let log = mock.log_snapshot();
        let reached = |entry: String| log.contains(&entry);
        assert!(
            reached(format!("POST {prefix}/QuickConnect/Initiate")),
            "initiate must reach {prefix}/QuickConnect/Initiate for {address:?}; got {log:?}"
        );
        assert!(
            reached(format!("GET {prefix}/QuickConnect/Connect?secret=s3cr3t")),
            "poll must reach {prefix}/QuickConnect/Connect for {address:?}; got {log:?}"
        );
        assert!(
            reached(format!("POST {prefix}/Users/AuthenticateWithQuickConnect")),
            "complete must reach {prefix}/Users/AuthenticateWithQuickConnect for {address:?}; got {log:?}"
        );
        assert_eq!(session.expect("initiate").secret, "s3cr3t");
        assert!(
            polled.expect("poll must succeed for the same address"),
            "the secret is authenticated"
        );
        assert_eq!(completed.expect("complete").user_id, QUICK_CONNECT_USER_ID);
    }

    #[test]
    fn quick_connect_reaches_the_server_for_a_bare_host_and_port() {
        assert_quick_connect_reaches_normalized_base(
            |mock| mock.base_url.trim_start_matches("http://").to_string(),
            "",
        );
    }

    #[test]
    fn quick_connect_reaches_the_server_for_an_address_with_a_trailing_slash() {
        assert_quick_connect_reaches_normalized_base(|mock| format!("{}/", mock.base_url), "");
    }

    #[test]
    fn quick_connect_reaches_the_server_for_an_uppercase_scheme() {
        assert_quick_connect_reaches_normalized_base(
            |mock| format!("HTTP://{}", mock.base_url.trim_start_matches("http://")),
            "",
        );
    }

    #[test]
    fn quick_connect_reaches_the_server_for_an_address_with_a_path_prefix() {
        assert_quick_connect_reaches_normalized_base(|mock| format!("{}/jf", mock.base_url), "/jf");
    }

    /// docs/13 "sign-in" (Quick Connect): Initiate answered 401 means the server has the feature off.
    #[test]
    fn initiating_quick_connect_on_a_server_that_disabled_it_reports_disabled() {
        let (_dir, core) = core_in_tempdir();
        let mock = core.runtime.block_on(RouteMockServer::start());
        mock.route(
            "POST",
            "/QuickConnect/Initiate",
            401,
            serde_json::json!("Quick connect is not active on this server."),
        );

        let err = core
            .initiate_quick_connect(mock.base_url.clone())
            .expect_err("a 401 from Initiate is not a session");

        assert!(
            matches!(err, CoreError::QuickConnectDisabled { .. }),
            "{err:?}"
        );
    }

    /// docs/13 "sign-in" (Quick Connect): reauthorizing a saved account whose stored address has a
    /// trailing slash still lands on the normalized base.
    #[test]
    fn quick_connect_reauthorization_reaches_the_saved_accounts_normalized_base() {
        let (dir, core) = core_in_tempdir();
        let mock = core.runtime.block_on(RouteMockServer::start());
        route_quick_connect(&mock, "/jf");
        session::add_or_update(
            dir.path(),
            session::SessionFile {
                server_url: format!("{}/jf/", mock.base_url),
                user_id: QUICK_CONNECT_USER_ID.to_string(),
                user_name: "Sample User".to_string(),
                token: "stale".to_string(),
                device_id: "dev".to_string(),
                mirror_dir: "mirror".to_string(),
                server_version: None,
                server_name: None,
            },
        )
        .expect("seed a saved account");

        let account = core
            .complete_quick_connect_reauthorization(0, "s3cr3t".to_string())
            .expect("reauthorization against the saved address");

        assert_eq!(account.user_id, QUICK_CONNECT_USER_ID);
        let log = mock.log_snapshot();
        assert!(
            log.contains(&"POST /jf/Users/AuthenticateWithQuickConnect".to_string()),
            "reauthorization must reach /jf/Users/AuthenticateWithQuickConnect; got {log:?}"
        );
    }

    /// The mock has no websocket support, so the periodic
    /// `SERVER_VERSION_REFRESH_INTERVAL` tick (200ms under cfg(test)) is what
    /// notices a server-version change after sign-in already settled on the
    /// old value.
    #[test]
    fn periodic_watch_notices_a_server_version_change_with_no_bus_event() {
        let (_dir, core) = core_in_tempdir();
        let mock = core.runtime.block_on(RouteMockServer::start());
        mock.route(
            "POST",
            "/Users/AuthenticateByName",
            200,
            serde_json::json!({
                "User": {"Id": "00000000-0000-0000-0000-000000000002", "Name": "jellybeam-user"},
                "AccessToken": "tok",
            }),
        );
        mock.route(
            "GET",
            "/System/Info/Public",
            200,
            serde_json::json!({"Version": "12.0.0"}),
        );

        core.sign_in(
            mock.base_url.clone(),
            "user".to_string(),
            "pass".to_string(),
        )
        .expect("sign_in against mock server");
        wait_until(
            || core.server_version() == Some("12.0.0".to_string()),
            "sign-in's own immediate refresh to settle on the initial version",
        );

        // No sign-in/restore/switch happens after this -- only the
        // poll's own tick can notice the route change.
        mock.route(
            "GET",
            "/System/Info/Public",
            200,
            serde_json::json!({"Version": "12.1.0"}),
        );

        wait_until(
            || core.server_version() == Some("12.1.0".to_string()),
            "a later periodic tick to notice the changed server version",
        );
        assert!(core.server_at_least(12, 1));
    }

    /// restore_session seeds the version synchronously from disk; a
    /// subsequent failed background refresh must leave that value alone.
    #[test]
    fn restore_session_seeds_server_version_from_disk_before_any_network() {
        let (dir, core) = core_in_tempdir();
        let mock = core.runtime.block_on(RouteMockServer::start());
        mock.route(
            "GET",
            "/System/Info/Public",
            500,
            serde_json::json!({"error": "boom"}),
        );
        let saved = session::SessionFile {
            server_url: mock.base_url.clone(),
            user_id: "u1".to_string(),
            user_name: "jellybeam-user".to_string(),
            token: "tok".to_string(),
            device_id: "dev".to_string(),
            mirror_dir: "mirror".to_string(),
            server_version: Some("10.11.10".to_string()),
            server_name: None,
        };
        session::add_or_update(dir.path(), saved).expect("seed a session file");

        let account = core.restore_session().expect("a saved session exists");
        assert_eq!(account.user_id, "u1");
        // Seeded synchronously and offline: correct before the background
        // refresh has any chance to reach the (failing) mock.
        assert_eq!(core.server_version(), Some("10.11.10".to_string()));
        assert!(core.server_at_least(10, 11));
        assert!(!core.server_at_least(12, 0));

        wait_until(
            || mock.hit_count("GET", "/System/Info/Public") >= 1,
            "the background version refresh to reach the mock server",
        );
        // The mock 500s every refresh attempt; the stored value must be
        // left exactly as it was.
        assert_eq!(core.server_version(), Some("10.11.10".to_string()));
        assert!(core.server_at_least(10, 11));
    }

    #[test]
    fn server_at_least_is_false_with_no_session() {
        let (_dir, core) = core_in_tempdir();
        assert_eq!(core.server_version(), None);
        assert!(!core.server_at_least(0, 0));
        assert!(!core.server_at_least(12, 0));
    }

    // --- docs/13 About > server info ---------------------------------

    #[test]
    fn server_info_snapshot_with_nothing_signed_in_is_all_empty() {
        let (_dir, core) = core_in_tempdir();
        let snapshot = core.server_info_snapshot();
        assert_eq!(snapshot.server_url, None);
        assert_eq!(snapshot.server_name, None);
        assert_eq!(snapshot.server_version, None);
        assert_eq!(snapshot.user_name, None);
        assert_eq!(snapshot.device_id, None);
        assert!(!snapshot.live_events_connected);
        assert!(snapshot.mirror.is_none());
        assert!(snapshot.libraries.is_empty());
    }

    #[test]
    fn server_info_snapshot_when_signed_in_with_an_open_mirror_carries_full_state() {
        const VIEW_ID: &str = "00000000-0000-0000-0000-000000000020";

        let (_dir, core, mock) = core_signed_in_against_routes();
        mock.route(
            "GET",
            "/System/Info/Public",
            200,
            serde_json::json!({"Version": "12.0.0", "ServerName": "Living Room Server"}),
        );
        mock.route("GET", "/UserViews", 200, user_views_json(VIEW_ID, "movies"));

        core.open_mirror()
            .expect("open_mirror should succeed synchronously against a real loopback server");

        wait_until(
            || core.server_version() == Some("12.0.0".to_string()),
            "the background version refresh to settle",
        );
        wait_until(
            || !core.server_info_snapshot().libraries.is_empty(),
            "initial sync to seed the mirrored library",
        );

        let snapshot = core.server_info_snapshot();
        assert_eq!(snapshot.server_url.as_deref(), Some(mock.base_url.as_str()));
        assert_eq!(snapshot.server_name.as_deref(), Some("Living Room Server"));
        assert_eq!(snapshot.server_version.as_deref(), Some("12.0.0"));
        assert_eq!(snapshot.user_name.as_deref(), Some("jellybeam-user"));
        assert_eq!(snapshot.device_id.as_deref(), Some("dev"));
        assert_eq!(
            snapshot.libraries,
            vec![MirrorLibrary {
                name: "Library".to_string(),
                collection_type: "movies".to_string(),
            }]
        );
        let mirror = snapshot.mirror.expect("a mirror is open");
        assert!(
            mirror.db_bytes > 0,
            "mirror.db already has the schema written to it"
        );
    }

    #[test]
    fn fetch_server_details_happy_path_decodes_every_field_and_persists() {
        let (dir, core, mock) = core_signed_in_against_routes();
        mock.route(
            "GET",
            "/System/Info",
            200,
            serde_json::json!({
                "Version": "12.0.0",
                "ServerName": "Living Room Server",
                "ProductName": "Jellyfin Server",
                "OperatingSystemDisplayName": "Linux",
                "SystemArchitecture": "X64",
                "HasPendingRestart": false,
                "HasUpdateAvailable": true,
            }),
        );
        let details = core
            .fetch_server_details()
            .expect("fetch_server_details against mock server");

        assert_eq!(details.server_name.as_deref(), Some("Living Room Server"));
        assert_eq!(details.version.as_deref(), Some("12.0.0"));
        assert_eq!(details.product_name.as_deref(), Some("Jellyfin Server"));
        assert_eq!(details.operating_system.as_deref(), Some("Linux"));
        assert_eq!(details.architecture.as_deref(), Some("X64"));
        assert_eq!(details.has_pending_restart, Some(false));
        assert_eq!(details.has_update_available, Some(true));
        assert!(details.system_info_available);

        let record = session::load_list(dir.path())
            .sessions
            .into_iter()
            .find(|s| s.user_id == "u1")
            .expect("session record exists");
        assert_eq!(record.server_name.as_deref(), Some("Living Room Server"));
        assert_eq!(record.server_version.as_deref(), Some("12.0.0"));
    }

    /// A server that refuses the authenticated `/System/Info` call (e.g. a non-admin user)
    /// must still surface the name/version from the pre-auth `/System/Info/Public` fallback,
    /// just with `system_info_available: false` and the extra fields left `None`.
    #[test]
    fn fetch_server_details_falls_back_to_public_info_on_403() {
        let (_dir, core, mock) = core_signed_in_against_routes();
        mock.route(
            "GET",
            "/System/Info",
            403,
            serde_json::json!({"error": "forbidden"}),
        );
        mock.route(
            "GET",
            "/System/Info/Public",
            200,
            serde_json::json!({"Version": "12.0.0", "ServerName": "Living Room Server"}),
        );

        let details = core
            .fetch_server_details()
            .expect("a 403 on /System/Info must fall back, not error");

        assert!(!details.system_info_available);
        assert_eq!(details.server_name.as_deref(), Some("Living Room Server"));
        assert_eq!(details.version.as_deref(), Some("12.0.0"));
        assert_eq!(details.product_name, None);
        assert_eq!(details.operating_system, None);
        assert_eq!(details.architecture, None);
        assert_eq!(details.has_pending_restart, None);
        assert_eq!(details.has_update_available, None);
    }

    /// Installs a hand-built bus whose connection the returned sender drives, since the mock has
    /// no websocket: an account (on an unreachable server unless one is installed) opens its
    /// mirror, and the bundle keeps that mirror while the bus it opened with is torn down.
    fn install_test_bus(
        core: &JellybeamCore,
        bundle_tx: tokio::sync::broadcast::Sender<jellyfin_core::BusEvent>,
        forwarder: Option<tokio::task::JoinHandle<()>>,
    ) -> tokio::sync::watch::Sender<jellyfin_core::BusConnection> {
        if core.installed_identity().is_none() {
            install_without_refresh(core, "http://server-a.test");
        }
        if core.require_mirror().is_err() {
            core.open_mirror().expect("opens local SQLite only");
        }
        let (connection_tx, connection) =
            tokio::sync::watch::channel(jellyfin_core::BusConnection::Disconnected);
        let opened = take_bundle(core).expect("the mirror just opened");
        let old_bus = BusBundle {
            mirror: opened.mirror.clone(),
            listener_task: None,
            tx: opened.tx,
            connection: opened.connection,
            handle: opened.handle,
            forwarder: opened.forwarder,
        };
        core.stop_event_bus(Some(old_bus));
        let bundle = BusBundle {
            mirror: opened.mirror,
            listener_task: opened.listener_task,
            tx: bundle_tx,
            connection,
            handle: None,
            forwarder,
        };
        let epoch = core.account_epoch();
        assert!(JellybeamCore::commit_bundle_locked(&mut core.lock_state(), epoch, bundle).is_ok());
        connection_tx
    }

    /// Takes the installed account's bundle, as a teardown would, for the caller to stop.
    fn take_bundle(core: &JellybeamCore) -> Option<BusBundle> {
        let mut state = core.lock_state();
        state.account.installed.as_mut()?.bundle.take()
    }

    /// The installed account's open mirror.
    fn installed_mirror(core: &JellybeamCore) -> media_cache::Mirror {
        let state = core.lock_state();
        let bundle = state.account.bundle().expect("a mirror is open");
        bundle.mirror.clone()
    }

    /// A clone of the installed bundle's connection receiver: it keeps the bundle's seen version,
    /// so a value sent before the clone still reads as changed.
    fn installed_bus_connection(
        core: &JellybeamCore,
    ) -> tokio::sync::watch::Receiver<jellyfin_core::BusConnection> {
        core.lock_state()
            .account
            .bundle()
            .expect("a test bus is installed")
            .connection
            .clone()
    }

    /// One mirror on an unreachable server, cloned into every hand-built bundle of a pure test.
    fn test_mirror(
        runtime: &tokio::runtime::Runtime,
        dir: &std::path::Path,
    ) -> media_cache::Mirror {
        let (_tx, bus) = tokio::sync::broadcast::channel(1);
        runtime
            .block_on(media_cache::Mirror::open(
                dir.join("mirror"),
                test_bus_client(),
                bus,
                Arc::new(std::sync::atomic::AtomicBool::new(false)),
                Arc::new(jellyfin_core::pending_local_item_ids),
            ))
            .expect("opens local SQLite only")
    }

    /// A client whose token the test buses reject, owned by [`BUS_USER`].
    fn test_bus_client() -> jellyfin_api::JellyfinClient {
        jellyfin_api::JellyfinClient::from_token(
            "http://server-a.test",
            JellybeamCore::identity_with("dev".to_string()),
            "tok",
        )
        .with_user_id(BUS_USER)
    }

    /// A forwarder over an installed hand-driven bus: `connection` drives its watch.
    struct TestBus {
        connection: tokio::sync::watch::Sender<jellyfin_core::BusConnection>,
        real_tx: tokio::sync::broadcast::Sender<jellyfin_core::BusEvent>,
        bundle_rx: tokio::sync::broadcast::Receiver<jellyfin_core::BusEvent>,
        /// The forwarder, unless handed to the bundle (then taking the bundle stops it).
        forwarder: Option<tokio::task::JoinHandle<()>>,
    }

    fn spawn_test_bus(core: &JellybeamCore, client: jellyfin_api::JellyfinClient) -> TestBus {
        let (real_tx, real_rx) = tokio::sync::broadcast::channel(16);
        let (bundle_tx, bundle_rx) = tokio::sync::broadcast::channel(16);
        let connection = install_test_bus(core, bundle_tx.clone(), None);
        let epoch = core.account_epoch();
        let forwarder = core.spawn_bus_forwarder(
            real_rx,
            bundle_tx,
            installed_bus_connection(core),
            client,
            epoch,
        );
        TestBus {
            connection,
            real_tx,
            bundle_rx,
            forwarder: Some(forwarder),
        }
    }

    impl TestBus {
        /// Two relayed markers: the forwarder's poll after the first sees every earlier
        /// connection change first (biased select), so both arriving means it handled them.
        fn caught_up(&mut self, core: &JellybeamCore) {
            for _ in 0..2 {
                self.real_tx
                    .send(jellyfin_core::BusEvent::NeedsReconcile)
                    .expect("send");
                core.runtime.block_on(async {
                    tokio::time::timeout(Duration::from_secs(2), self.bundle_rx.recv())
                        .await
                        .expect("the forwarder relays the marker")
                        .expect("bundle open")
                });
            }
        }

        fn hand_forwarder_to_bundle(&mut self, core: &JellybeamCore) {
            let mut state = core.lock_state();
            let installed = state.account.installed.as_mut();
            installed
                .and_then(|i| i.bundle.as_mut())
                .expect("a test bus is installed")
                .forwarder = self.forwarder.take();
        }

        /// Closes the real bus and joins a forwarder still held here.
        fn finish(self, core: &JellybeamCore) {
            drop(self.real_tx);
            if let Some(forwarder) = self.forwarder {
                core.runtime
                    .block_on(forwarder)
                    .expect("the forwarder exits once its real bus closes");
            }
        }
    }

    /// Records every reauthorization ask, in order.
    #[derive(Default)]
    struct AskRecorder {
        asks: Mutex<Vec<AccountIdentity>>,
    }

    impl AskRecorder {
        fn asks(&self) -> Vec<AccountIdentity> {
            self.asks.lock().unwrap_or_else(|e| e.into_inner()).clone()
        }
    }

    impl ChangeListener for AskRecorder {
        fn on_change(&self, _event: ChangeEvent) -> Result<(), crate::error::CallbackError> {
            Ok(())
        }

        fn on_unauthorized(
            &self,
            account: AccountIdentity,
        ) -> Result<(), crate::error::CallbackError> {
            self.asks
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .push(account);
            Ok(())
        }
    }

    /// An ask that signals `entered`, then holds until released and takes `done_at`'s next value.
    struct HeldAskListener {
        entered: std::sync::mpsc::Sender<()>,
        release: Mutex<std::sync::mpsc::Receiver<()>>,
        done_at: Arc<std::sync::atomic::AtomicUsize>,
    }

    impl ChangeListener for HeldAskListener {
        fn on_change(&self, _event: ChangeEvent) -> Result<(), crate::error::CallbackError> {
            Ok(())
        }

        fn on_unauthorized(
            &self,
            _account: AccountIdentity,
        ) -> Result<(), crate::error::CallbackError> {
            let _ = self.entered.send(());
            let _ = self
                .release
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .recv_timeout(Duration::from_secs(5));
            self.done_at
                .fetch_add(1, std::sync::atomic::Ordering::SeqCst);
            Ok(())
        }
    }

    /// docs/13 Server compatibility: each Connected of the installed bus refreshes the server
    /// version once with that bus's own client; a down, rejected or taken bus refreshes nothing.
    #[test]
    fn a_connected_bus_refreshes_the_server_version_once_with_its_own_client() {
        use jellyfin_core::{BusConnection, BusEvent};
        // The test bus's account has an unresolvable server: every mock hit is the forwarder's.
        let (_dir, core) = core_in_tempdir();
        let mock = core.runtime.block_on(RouteMockServer::start());
        mock.route(
            "GET",
            "/System/Info/Public",
            200,
            serde_json::json!({"Version": "12.0.0"}),
        );
        let bus_client = jellyfin_api::JellyfinClient::from_token(
            &mock.base_url,
            JellybeamCore::identity_with("dev".to_string()),
            "tok",
        )
        .with_user_id("00000000-0000-4000-8000-000000000001");
        let hits = || mock.hit_count("GET", "/System/Info/Public");

        // A Connected behind a flood that laps the forwarder's ring still refreshes once.
        let (flood_tx, flood_rx) = tokio::sync::broadcast::channel(256);
        for _ in 0..300 {
            flood_tx
                .send(BusEvent::Server(
                    jellyfin_api::ServerEvent::UserDataChanged {
                        item_userdata: vec![],
                    },
                ))
                .expect("send");
        }
        let (flood_bundle_tx, _flood_bundle_rx) = tokio::sync::broadcast::channel(16);
        let flood_connection = install_test_bus(&core, flood_bundle_tx.clone(), None);
        flood_connection.send_replace(BusConnection::Connected);
        let flooded = core.spawn_bus_forwarder(
            flood_rx,
            flood_bundle_tx,
            installed_bus_connection(&core),
            bus_client.clone(),
            core.account_epoch(),
        );
        drop(flood_tx);
        core.runtime
            .block_on(flooded)
            .expect("the forwarder drains the flood and exits");
        assert_eq!(hits(), 1, "flood then Connected: one refresh");

        let mut bus = spawn_test_bus(&core, bus_client);
        let rows = [
            (BusConnection::Disconnected, 1, "socket down: no refresh"),
            (BusConnection::Connected, 2, "socket up: one refresh"),
            (BusConnection::Disconnected, 2, "socket closed: no refresh"),
            (BusConnection::Connected, 3, "socket back up: one refresh"),
        ];
        for (state, expected, row) in rows {
            bus.connection.send_replace(state);
            bus.caught_up(&core);
            assert_eq!(hits(), expected, "{row}");
        }

        // Still watching here (no rejection yet), so this row proves the installed check.
        let taken = take_bundle(&core);
        bus.connection.send_replace(BusConnection::Connected);
        bus.caught_up(&core);
        assert_eq!(hits(), 3, "a taken bus's Connected refreshes nothing");

        core.stop_event_bus(taken);
        bus.finish(&core);
    }

    /// The account a synthetic rejected token was issued to.
    fn rejected_owner(user_id: &str) -> jellyfin_api::TokenOwner {
        jellyfin_api::TokenOwner {
            server_url: "http://server-a.test".to_string(),
            user_id: user_id.to_string(),
        }
    }

    fn rejected(user_id: &str) -> jellyfin_core::BusConnection {
        jellyfin_core::BusConnection::Unauthorized {
            owner: Some(rejected_owner(user_id)),
        }
    }

    const BUS_USER: &str = "00000000-0000-4000-8000-000000000001";

    /// docs/18 §2.1: a rejection of the installed bus asks the listener once, naming its account.
    #[test]
    fn a_rejected_live_events_socket_asks_to_reauthorize_its_account_once() {
        let (_dir, core) = core_in_tempdir();
        let recorder = Arc::new(AskRecorder::default());
        core.set_change_listener(recorder.clone());
        let mut bus = spawn_test_bus(&core, test_bus_client());

        bus.connection.send_replace(rejected(BUS_USER));
        bus.caught_up(&core);
        assert_eq!(
            recorder.asks(),
            vec![AccountIdentity::from(rejected_owner(BUS_USER))]
        );
        bus.finish(&core);
    }

    /// docs/18 §2.1: a rejection is terminal for its bus, so a repeat asks nobody.
    #[test]
    fn a_second_unauthorized_on_one_bus_asks_nobody_again() {
        let (_dir, core) = core_in_tempdir();
        let recorder = Arc::new(AskRecorder::default());
        core.set_change_listener(recorder.clone());
        let mut bus = spawn_test_bus(&core, test_bus_client());

        for _ in 0..2 {
            bus.connection.send_replace(rejected(BUS_USER));
            bus.caught_up(&core);
        }
        assert_eq!(recorder.asks().len(), 1, "one ask per bus");
        bus.finish(&core);
    }

    /// docs/18 §2.1: a rejection that names no account has nobody to reauthorize.
    #[test]
    fn a_live_events_rejection_naming_no_account_asks_nobody() {
        let (_dir, core) = core_in_tempdir();
        let recorder = Arc::new(AskRecorder::default());
        core.set_change_listener(recorder.clone());
        let mut bus = spawn_test_bus(&core, test_bus_client());

        bus.connection
            .send_replace(jellyfin_core::BusConnection::Unauthorized { owner: None });
        bus.caught_up(&core);
        assert!(recorder.asks().is_empty());
        bus.finish(&core);
    }

    /// docs/18 §2.1: a switch takes the old bus, so its late rejection asks nobody; the forwarder
    /// is kept out of the bundle to model the window before the switch stops it.
    #[test]
    fn a_taken_bus_rejected_after_the_switch_asks_nobody() {
        let (dir, core) = core_in_tempdir();
        seed_session(dir.path(), "http://server-a.test", "u1");
        seed_session(dir.path(), "http://server-b.test", "u2");
        assert!(core.restore_session().is_some());
        let recorder = Arc::new(AskRecorder::default());
        core.set_change_listener(recorder.clone());
        let mut bus = spawn_test_bus(&core, test_bus_client());

        let other = 1 - core.active_account_index().expect("an account is active");
        core.switch_session(other)
            .expect("switch to the other account");
        bus.connection.send_replace(rejected(BUS_USER));
        bus.caught_up(&core);
        assert!(
            recorder.asks().is_empty(),
            "the taken bus asked {:?}",
            recorder.asks()
        );
        bus.finish(&core);
    }

    /// docs/18 §2.1: an ask already running when a reauthorization takes its bus finishes before
    /// that reauthorization returns, so no ask ever follows it.
    #[test]
    fn a_rejection_racing_a_reauthorization_lands_before_it_returns() {
        use std::sync::atomic::{AtomicUsize, Ordering};
        let (dir, core) = core_in_tempdir();
        let target = seed_session(dir.path(), "http://server-a.test", "u1");
        assert!(core.restore_session().is_some());
        let order = Arc::new(AtomicUsize::new(0));
        let (entered_tx, entered) = std::sync::mpsc::channel();
        let (release, release_rx) = std::sync::mpsc::channel();
        core.set_change_listener(Arc::new(HeldAskListener {
            entered: entered_tx,
            release: Mutex::new(release_rx),
            done_at: order.clone(),
        }));
        let mut bus = spawn_test_bus(&core, test_bus_client());
        bus.hand_forwarder_to_bundle(&core);

        bus.connection.send_replace(rejected(BUS_USER));
        entered
            .recv_timeout(Duration::from_secs(2))
            .expect("the forwarder asks");
        let reauthorizing = {
            let core = core.clone();
            let order = order.clone();
            std::thread::spawn(move || {
                let client = jellyfin_api::JellyfinClient::from_token(
                    &target.server_url,
                    core.client_identity("http://example.test"),
                    "new-token",
                )
                .with_user_id("u1");
                core.install_reauthenticated_session(
                    0,
                    target,
                    client,
                    authentication_result("new-token", "u1-name"),
                )
                .expect("reauthorizes the installed account");
                order.fetch_add(1, Ordering::SeqCst)
            })
        };
        std::thread::sleep(Duration::from_millis(200));
        assert!(
            !reauthorizing.is_finished(),
            "the reauthorization waits for the running ask"
        );
        release.send(()).expect("release the ask");
        let returned_at = reauthorizing.join().expect("reauthorization thread");
        assert_eq!(
            order.load(Ordering::SeqCst),
            2,
            "one ask and one return recorded"
        );
        assert_eq!(
            returned_at, 1,
            "the ask finished before the reauthorization returned"
        );
        bus.finish(&core);
    }

    /// docs/18 §2.1: a socket rejected before `open_mirror` installs its bundle still asks once,
    /// since the forwarder starts only once the bundle is installed.
    #[test]
    fn a_bus_rejected_while_its_mirror_opens_still_asks() {
        let (_dir, core, mock) = core_signed_in_against_routes();
        mock.route("GET", "/socket", 401, serde_json::json!({}));
        let recorder = Arc::new(AskRecorder::default());
        core.set_change_listener(recorder.clone());

        core.open_mirror()
            .expect("open_mirror should succeed against a real loopback server");
        wait_until(
            || !recorder.asks().is_empty(),
            "the rejected socket to ask for reauthorization",
        );
        assert_eq!(
            recorder.asks(),
            vec![AccountIdentity {
                server_url: mock.base_url.clone(),
                user_id: "u1".to_string(),
            }]
        );
    }

    /// docs/18 §2.1, R1-R4 over every sequence of up to four bus events: an ask names the asking
    /// bus's account, comes only from the installed bus with a listener bound, and at most once
    /// per bus; exactly the first named rejection of the installed bus asks.
    #[test]
    fn bus_sequences_up_to_four_keep_the_reauthorization_invariants() {
        use jellyfin_core::BusConnection;
        #[derive(Clone, Copy, Debug)]
        enum Ev {
            Open,
            UpCur,
            DownCur,
            RejectedCur,
            UpStale,
            Stop,
            RejectedStale,
            RejectedUnnamedCur,
        }
        const ALPHABET: [Ev; 8] = [
            Ev::Open,
            Ev::UpCur,
            Ev::DownCur,
            Ev::RejectedCur,
            Ev::UpStale,
            Ev::Stop,
            Ev::RejectedStale,
            Ev::RejectedUnnamedCur,
        ];
        struct Bus {
            id: String,
            tx: tokio::sync::watch::Sender<BusConnection>,
            rx: tokio::sync::watch::Receiver<BusConnection>,
            watching: bool,
        }
        // The forwarder's watch arm: the latest value, and a rejection ends its watching.
        fn observe(state: &State, bus: &mut Bus) -> Option<AccountIdentity> {
            if !bus.watching || !bus.rx.has_changed().unwrap_or(false) {
                return None;
            }
            let now = bus.rx.borrow_and_update().clone();
            if !matches!(now, BusConnection::Unauthorized { .. }) {
                return None;
            }
            bus.watching = false;
            JellybeamCore::bus_reauthorization_locked(state, &bus.rx, &now)
                .map(|(_, account)| account)
        }
        fn install(state: &mut State, mirror: &media_cache::Mirror, id: usize) -> Bus {
            let (tx, rx) = tokio::sync::watch::channel(BusConnection::Disconnected);
            let installed = state.account.installed.as_mut().expect("an account");
            installed.bundle = Some(BusBundle {
                mirror: mirror.clone(),
                listener_task: None,
                tx: tokio::sync::broadcast::channel(1).0,
                connection: rx.clone(),
                handle: None,
                forwarder: None,
            });
            Bus {
                id: format!("00000000-0000-4000-8000-{id:012}"),
                tx,
                rx,
                watching: true,
            }
        }

        let runtime = tokio::runtime::Runtime::new().expect("runtime");
        let dir = tempfile::tempdir().expect("tempdir");
        let mirror = test_mirror(&runtime, dir.path());
        let saved = slot_saved("http://server-a.test", "u1", "10.11.10", "Server A");
        let mut failures = Vec::new();
        let mut sequences = 0;
        for listener_bound in [true, false] {
            for len in 1..=4u32 {
                for code in 0..8usize.pow(len) {
                    sequences += 1;
                    let seq: Vec<Ev> = (0..len)
                        .map(|i| ALPHABET[code / 8usize.pow(i) % 8])
                        .collect();
                    let mut state = State::default();
                    drop(JellybeamCore::reset_account_state_locked(
                        &mut state,
                        Some(slot_installed(&saved)),
                        AccountChange::Replace,
                    ));
                    if listener_bound {
                        state.listener = Some(Arc::new(AskRecorder::default()));
                    }
                    let mut opened = 0;
                    let mut cur: Option<Bus> = None;
                    let mut stale: Option<Bus> = None;
                    let mut asked: Vec<String> = Vec::new();
                    for (step, ev) in seq.iter().enumerate() {
                        let expect_ask = matches!(ev, Ev::RejectedCur)
                            && listener_bound
                            && cur.as_ref().is_some_and(|bus| bus.watching);
                        let send = |bus: &Option<Bus>, value: BusConnection| {
                            if let Some(bus) = bus {
                                bus.tx.send_replace(value);
                            }
                        };
                        match ev {
                            Ev::Open | Ev::Stop => {
                                if let Some(taken) = cur.take() {
                                    stale = Some(taken);
                                }
                                let installed = state.account.installed.as_mut();
                                installed.expect("an account").bundle = None;
                                if matches!(ev, Ev::Open) {
                                    opened += 1;
                                    cur = Some(install(&mut state, &mirror, opened));
                                }
                            }
                            Ev::UpCur => send(&cur, BusConnection::Connected),
                            Ev::DownCur => send(&cur, BusConnection::Disconnected),
                            Ev::RejectedCur => {
                                if let Some(bus) = &cur {
                                    bus.tx.send_replace(rejected(&bus.id));
                                }
                            }
                            Ev::RejectedUnnamedCur => {
                                send(&cur, BusConnection::Unauthorized { owner: None })
                            }
                            Ev::UpStale => send(&stale, BusConnection::Connected),
                            Ev::RejectedStale => {
                                if let Some(bus) = &stale {
                                    bus.tx.send_replace(rejected(&bus.id));
                                }
                            }
                        }
                        let mut asked_now = false;
                        for (bus, installed) in [(&mut cur, true), (&mut stale, false)] {
                            let Some(bus) = bus.as_mut() else { continue };
                            let Some(account) = observe(&state, bus) else {
                                continue;
                            };
                            asked_now = true;
                            let at = format!("{seq:?} step {step} (listener {listener_bound})");
                            if !installed {
                                failures.push(format!("{at}: a taken bus asked"));
                            }
                            if asked.contains(&bus.id) {
                                failures.push(format!("{at}: a bus asked twice"));
                            }
                            if account != AccountIdentity::from(rejected_owner(&bus.id)) {
                                failures.push(format!("{at}: the ask named another account"));
                            }
                            asked.push(bus.id.clone());
                        }
                        if asked_now != expect_ask {
                            failures.push(format!(
                                "{seq:?} step {step} (listener {listener_bound}): asked {asked_now}, expected {expect_ask}"
                            ));
                        }
                    }
                }
            }
        }
        assert_eq!(sequences, 2 * (8 + 64 + 512 + 4096));
        assert!(
            failures.is_empty(),
            "{} failures, first: {:?}",
            failures.len(),
            failures.first()
        );
    }

    /// docs/13 About > server info: a Server flood that laps the forwarder must not lose Connected.
    #[test]
    fn a_server_flood_lagging_the_bus_forwarder_still_reports_connected() {
        let (_dir, core, _mock) = core_signed_in_against_routes();
        let (real_tx, real_rx) = tokio::sync::broadcast::channel(256);
        let (bundle_tx, _bundle_rx) = tokio::sync::broadcast::channel(16);
        // Queued before the forwarder runs, as when it is starved: the flood laps it while the
        // watch carries Connected.
        real_tx
            .send(jellyfin_core::BusEvent::NeedsReconcile)
            .expect("send");
        for _ in 0..300 {
            real_tx
                .send(jellyfin_core::BusEvent::Server(
                    jellyfin_api::ServerEvent::UserDataChanged {
                        item_userdata: vec![],
                    },
                ))
                .expect("send");
        }
        let connection = install_test_bus(&core, bundle_tx.clone(), None);
        assert!(
            !core.server_info_snapshot().live_events_connected,
            "a bus that has not connected yet reads disconnected"
        );
        connection.send_replace(jellyfin_core::BusConnection::Connected);
        let forwarder = core.spawn_bus_forwarder(
            real_rx,
            bundle_tx,
            installed_bus_connection(&core),
            core.require_client().expect("signed in"),
            core.account_epoch(),
        );
        // Closing the real bus ends the forwarder only after it relayed the whole lagged flood.
        drop(real_tx);
        core.runtime
            .block_on(forwarder)
            .expect("the forwarder drains the flood and exits");
        assert!(
            core.server_info_snapshot().live_events_connected,
            "the socket is up, so live events read connected"
        );
    }

    /// docs/13 About > server info: the flag follows only the installed bus's connection watch.
    #[test]
    fn live_events_connected_follows_only_the_installed_bus_connection() {
        use jellyfin_core::BusConnection;
        let (_dir, core, _mock) = core_signed_in_against_routes();
        core.open_mirror()
            .expect("open_mirror should succeed against a real loopback server");
        assert!(
            !core.server_info_snapshot().live_events_connected,
            "a freshly opened bus has not connected yet"
        );
        let (bundle_tx, _bundle_rx) = tokio::sync::broadcast::channel(16);
        let installed = install_test_bus(&core, bundle_tx, None);
        let rows = [
            (
                BusConnection::Disconnected,
                false,
                "before the socket connects",
            ),
            (BusConnection::Connected, true, "socket up"),
            (BusConnection::Disconnected, false, "socket closed"),
            (BusConnection::Connected, true, "socket back up"),
            (
                BusConnection::Unauthorized { owner: None },
                false,
                "socket rejected",
            ),
        ];
        for (connection, expected, row) in rows {
            installed.send_replace(connection);
            assert_eq!(
                core.server_info_snapshot().live_events_connected,
                expected,
                "{row}"
            );
        }
        installed.send_replace(BusConnection::Connected);
        assert!(core.server_info_snapshot().live_events_connected);

        core.open_mirror()
            .expect("open_mirror should succeed against a real loopback server");
        assert!(
            !core.server_info_snapshot().live_events_connected,
            "re-entry takes the old bus; the new one has not connected"
        );
        installed.send_replace(BusConnection::Connected);
        assert!(
            !core.server_info_snapshot().live_events_connected,
            "a taken bus's Connected never reaches the snapshot"
        );

        let stopped = take_bundle(&core);
        core.stop_event_bus(stopped);
        assert!(
            !core.server_info_snapshot().live_events_connected,
            "no installed bus reads disconnected while signed in"
        );
    }

    // --- docs/07 Next Up follows the latest settings; docs/18 §2.1 the account list. ---

    /// A Next Up reply naming one episode, so a test can tell which request's answer the mirror holds.
    fn next_up_reply(episode_id: &str) -> serde_json::Value {
        serde_json::json!({
            "Items": [{"Id": episode_id, "Name": "Sample Episode", "Type": "Episode"}],
            "TotalRecordCount": 1
        })
    }

    /// The query fragment a `/Shows/NextUp` request carries for a cutoff of `days` days.
    fn next_up_cutoff_query(days: i64) -> String {
        // The code under test reads the clock itself, so wait out a UTC date rollover that
        // could fall between the two reads.
        let into_day = chrono::Utc::now().timestamp().rem_euclid(86_400);
        if into_day > 86_400 - 30 {
            std::thread::sleep(Duration::from_secs(
                u64::try_from(86_400 - into_day).expect("positive") + 1,
            ));
        }
        let day = chrono::Utc::now() - chrono::Duration::days(days);
        format!("nextUpDateCutoff={}", day.format("%Y-%m-%d"))
    }

    fn mirror_next_up_ids(core: &JellybeamCore) -> Vec<String> {
        core.require_mirror()
            .map(|mirror| mirror.next_up(10).into_iter().map(|row| row.id).collect())
            .unwrap_or_default()
    }

    fn next_up_requests_containing(mock: &RouteMockServer, needle: &str) -> usize {
        mock.log_snapshot()
            .iter()
            .filter(|entry| entry.starts_with("GET /Shows/NextUp") && entry.contains(needle))
            .count()
    }

    /// Polls up to `within` for a `/Shows/NextUp` request whose query contains `needle`.
    fn next_up_request_arrives(mock: &RouteMockServer, needle: &str, within: Duration) -> bool {
        let deadline = Instant::now() + within;
        while Instant::now() < deadline {
            if next_up_requests_containing(mock, needle) > 0 {
                return true;
            }
            std::thread::sleep(Duration::from_millis(5));
        }
        false
    }

    /// Polls until `count` has not changed for `quiet`.
    fn wait_until_quiet(count: impl Fn() -> usize, quiet: Duration) {
        let deadline = Instant::now() + Duration::from_secs(15);
        let mut last = count();
        let mut since = Instant::now();
        while since.elapsed() < quiet {
            assert!(Instant::now() < deadline, "the request count never settled");
            std::thread::sleep(Duration::from_millis(5));
            let now = count();
            if now != last {
                last = now;
                since = Instant::now();
            }
        }
    }

    /// A signed-in core with an open tvshows mirror whose own Next Up fetches have settled.
    fn core_with_settled_next_up_mirror() -> (tempfile::TempDir, Arc<JellybeamCore>, RouteMockServer)
    {
        const VIEW_ID: &str = "00000000-0000-0000-0000-000000000010";
        let (dir, core, mock) = core_signed_in_against_routes();
        mock.route(
            "GET",
            "/UserViews",
            200,
            user_views_json(VIEW_ID, "tvshows"),
        );
        core.open_mirror().expect("open_mirror");
        wait_until(
            || mock.hit_count("GET", "/Shows/NextUp") >= 1,
            "the open's own Next Up fetch",
        );
        wait_until_quiet(
            || mock.hit_count("GET", "/Shows/NextUp"),
            Duration::from_secs(2),
        );
        (dir, core, mock)
    }

    /// docs/07: Next Up shows the latest saved settings even when an earlier save's reply lands last.
    #[test]
    fn next_up_follows_the_latest_settings_when_the_older_reply_lands_last() {
        const EPISODE_A: &str = "00000000-0000-0000-0000-0000000000a1";
        const EPISODE_B: &str = "00000000-0000-0000-0000-0000000000b1";
        let (_dir, core, mock) = core_with_settled_next_up_mirror();
        let (query_a, query_b) = (next_up_cutoff_query(7), next_up_cutoff_query(30));
        mock.route_with_query(
            "GET",
            "/Shows/NextUp",
            &query_a,
            200,
            &next_up_reply(EPISODE_A),
        );
        mock.route_with_query(
            "GET",
            "/Shows/NextUp",
            &query_b,
            200,
            &next_up_reply(EPISODE_B),
        );
        let held_a = mock.gate_with_query("GET", "/Shows/NextUp", &query_a, 0);

        core.set_settings(Settings {
            next_up_cutoff_days: Some(7),
            ..Settings::default()
        });
        let _ = next_up_request_arrives(&mock, &query_a, Duration::from_secs(1));
        core.set_settings(Settings {
            next_up_cutoff_days: Some(30),
            ..Settings::default()
        });
        // Whenever B's request is sent while A's reply is held, B's reply lands first.
        if next_up_request_arrives(&mock, &query_b, Duration::from_secs(1)) {
            wait_until_for(
                Duration::from_secs(3),
                || mirror_next_up_ids(&core) == [EPISODE_B],
                "B's reply to land",
            );
        }
        held_a.open();
        wait_until_for(
            Duration::from_secs(6),
            || mirror_next_up_ids(&core) == [EPISODE_B],
            "the mirror to hold B's Next Up",
        );
        std::thread::sleep(Duration::from_millis(500));

        assert_eq!(
            mirror_next_up_ids(&core),
            [EPISODE_B],
            "Next Up must follow the latest settings once both replies have landed"
        );
    }

    /// Spinning threads, twice the CPU count, that burn only while asked to; joined on drop.
    struct CpuBurners {
        burn: Arc<std::sync::atomic::AtomicBool>,
        stop: Arc<std::sync::atomic::AtomicBool>,
        threads: Vec<std::thread::JoinHandle<()>>,
    }

    impl CpuBurners {
        fn start() -> Self {
            use std::sync::atomic::{AtomicBool, Ordering::Relaxed};
            let burn = Arc::new(AtomicBool::new(false));
            let stop = Arc::new(AtomicBool::new(false));
            let count = std::thread::available_parallelism().map_or(8, |n| n.get() * 2);
            let threads = (0..count)
                .map(|_| {
                    let (burn, stop) = (burn.clone(), stop.clone());
                    std::thread::spawn(move || {
                        while !stop.load(Relaxed) {
                            if burn.load(Relaxed) {
                                std::hint::spin_loop();
                            } else {
                                std::thread::sleep(Duration::from_micros(20));
                            }
                        }
                    })
                })
                .collect();
            Self {
                burn,
                stop,
                threads,
            }
        }

        fn burn(&self, on: bool) {
            self.burn.store(on, std::sync::atomic::Ordering::Relaxed);
        }
    }

    impl Drop for CpuBurners {
        fn drop(&mut self) {
            self.stop.store(true, std::sync::atomic::Ordering::Relaxed);
            for thread in self.threads.drain(..) {
                let _ = thread.join();
            }
        }
    }

    /// docs/09: after overlapping saves the mirror's Next Up options are those of the settings that won.
    #[test]
    fn mirror_next_up_options_equal_the_settings_after_overlapping_saves() {
        const ROUNDS: i64 = 200;
        const WRITERS: i64 = 8;
        let (_dir, core, _mock) = core_with_settled_next_up_mirror();
        let mirror = core.require_mirror().expect("mirror is open");
        // Busy threads, while the writers run, make one lose its slot between saving and pushing.
        let burners = CpuBurners::start();
        for round in 0..ROUNDS {
            let barrier = Arc::new(std::sync::Barrier::new(WRITERS as usize));
            burners.burn(true);
            let writers: Vec<_> = (0..WRITERS)
                .map(|writer| {
                    let core = core.clone();
                    let barrier = barrier.clone();
                    std::thread::spawn(move || {
                        let days = u32::try_from(1_000 + round * WRITERS + writer).expect("small");
                        barrier.wait();
                        core.set_settings(Settings {
                            next_up_cutoff_days: Some(days),
                            ..Settings::default()
                        });
                    })
                })
                .collect();
            for writer in writers {
                writer.join().expect("set_settings thread");
            }
            burners.burn(false);

            assert_eq!(
                mirror.next_up_options(),
                next_up_options_from(&core.get_settings()),
                "round {round}: the mirror holds options other than the saved settings"
            );
        }
    }

    /// Core with B installed while the saved list still names A active, the state a lost
    /// active-index save leaves; A is index 1, B index 0.
    fn installed_b_while_the_list_names_a() -> TwoAccounts {
        let accounts = core_with_two_accounts(AccountSwap::Switch);
        accounts
            .core
            .switch_session(0)
            .expect("switch to account B");
        let mut list = session::load_list(accounts.core.data_dir());
        list.active = 1;
        session::save_list(accounts.core.data_dir(), &list).expect("name A active again");
        assert_eq!(accounts.core.active_account_index(), Some(1));
        assert_eq!(installed_user(&accounts.core).as_deref(), Some(SWAP_USER_B));
        accounts
    }

    fn installed_user(core: &JellybeamCore) -> Option<String> {
        core.installed_identity()
            .map(|identity| identity.account.user_id)
    }

    fn saved_users(core: &JellybeamCore) -> Vec<String> {
        core.list_accounts()
            .into_iter()
            .map(|account| account.user_id)
            .collect()
    }

    /// docs/18 §2.1: removing a saved account other than the installed one leaves the installed one alone.
    #[test]
    fn removing_a_saved_account_keeps_the_installed_account_when_the_list_names_it() {
        let accounts = installed_b_while_the_list_names_a();
        let epoch = accounts.core.account_epoch();

        let removed_installed = accounts.core.remove_session(1).expect("remove account A");

        assert!(!removed_installed, "A was not the installed account");
        assert_eq!(
            installed_user(&accounts.core).as_deref(),
            Some(SWAP_USER_B),
            "removing A must keep B installed"
        );
        assert_eq!(accounts.core.account_epoch(), epoch);
        assert_eq!(saved_users(&accounts.core), [SWAP_USER_B]);
    }

    /// docs/18 §2.1: removing the installed account tears it down, whatever the saved list names active.
    #[test]
    fn removing_the_installed_account_tears_it_down_when_the_list_names_another() {
        let accounts = installed_b_while_the_list_names_a();

        let removed_installed = accounts.core.remove_session(0).expect("remove account B");

        assert!(removed_installed, "B was the installed account");
        assert_eq!(
            installed_user(&accounts.core).as_deref(),
            Some(SWAP_USER_A),
            "removing B tears B down and installs the account the list names"
        );
        assert_eq!(saved_users(&accounts.core), [SWAP_USER_A]);
        wait_until(
            || accounts.mock_b.hit_count("POST", "/Sessions/Logout") == 1,
            "B's server to be logged out",
        );
    }

    /// docs/18 §2.1: a switch whose active-index save fails is refused, so nothing is installed
    /// that the saved list does not name.
    #[cfg(unix)]
    #[test]
    fn switch_session_fails_and_installs_nothing_when_the_active_index_cannot_be_saved() {
        let (dir, core) = core_in_tempdir();
        seed_session(dir.path(), "http://server-b.invalid", "u2");
        seed_session(dir.path(), "http://server-a.invalid", "u1");
        assert_eq!(core.restore_session().expect("A restores").user_id, "u1");
        let epoch = core.account_epoch();
        let Some(read_only) = crate::persistence::ChmodGuard::read_only_dir(dir.path()) else {
            return;
        };

        let result = core.switch_session(0);
        drop(read_only);

        assert!(
            result.is_err(),
            "switch_session reported success although the active index was not saved"
        );
        assert_eq!(core.account_epoch(), epoch, "nothing may be installed");
        assert_eq!(installed_user(&core).as_deref(), Some("u1"));
        assert_eq!(core.active_account_index(), Some(1));
    }

    /// Every file and directory name in `dir` with the bytes of each file.
    fn directory_contents(
        dir: &std::path::Path,
    ) -> std::collections::BTreeMap<String, Option<Vec<u8>>> {
        std::fs::read_dir(dir)
            .expect("read dir")
            .flatten()
            .map(|entry| {
                let bytes = entry
                    .path()
                    .is_file()
                    .then(|| std::fs::read(entry.path()).unwrap_or_default());
                (entry.file_name().to_string_lossy().into_owned(), bytes)
            })
            .collect()
    }

    type AccountReader = (&'static str, fn(&JellybeamCore));

    /// Every call that reads the saved account list and must never write it.
    fn account_list_readers() -> Vec<AccountReader> {
        vec![
            ("list_accounts", |core| {
                core.list_accounts();
            }),
            ("active_account_index", |core| {
                core.active_account_index();
            }),
            ("current_account", |core| {
                core.current_account();
            }),
            ("server_display_name", |core| {
                core.server_display_name(None);
            }),
            ("server_info_snapshot", |core| {
                core.server_info_snapshot();
            }),
            ("reauthorization_account", |core| {
                core.reauthorization_account(
                    AccountIdentity {
                        server_url: "http://reader.invalid".to_string(),
                        user_id: "u1".to_string(),
                    },
                    true,
                );
            }),
        ]
    }

    fn assert_readers_leave_the_directory_unchanged(dir: &std::path::Path, core: &JellybeamCore) {
        let before = directory_contents(dir);
        for (name, read) in account_list_readers() {
            read(core);
            let after = directory_contents(dir);
            let changed: Vec<&String> = after
                .keys()
                .chain(before.keys())
                .filter(|file| after.get(*file) != before.get(*file))
                .collect();
            assert!(
                changed.is_empty(),
                "{name} wrote to the data directory: {changed:?}"
            );
        }
    }

    const GARBAGE_LIST: &[u8] = b"{ not a session list";

    fn write_legacy_session(dir: &std::path::Path) {
        let legacy = session::SessionFile {
            server_url: "http://reader.invalid".to_string(),
            user_id: "u1".to_string(),
            user_name: "u1-name".to_string(),
            token: "tok".to_string(),
            device_id: "dev".to_string(),
            mirror_dir: "mirror".to_string(),
            server_version: None,
            server_name: None,
        };
        session::write_legacy(dir, &legacy);
    }

    /// docs/18 §2.1: reading the account list never quarantines or rewrites an unparseable one.
    #[test]
    fn readers_leave_an_unparseable_account_list_untouched() {
        let (dir, core) = core_in_tempdir();
        seed_session(dir.path(), "http://reader.invalid", "u1");
        core.restore_session().expect("the seeded account restores");
        std::fs::write(dir.path().join("sessions.json"), GARBAGE_LIST).expect("corrupt the list");

        assert_readers_leave_the_directory_unchanged(dir.path(), &core);
    }

    /// docs/18 §2.1: reading never migrates a lone legacy `session.json` into `sessions.json`.
    #[test]
    fn readers_leave_a_legacy_only_session_untouched() {
        let (dir, core) = core_in_tempdir();
        seed_session(dir.path(), "http://reader.invalid", "u1");
        core.restore_session().expect("the seeded account restores");
        std::fs::remove_file(dir.path().join("sessions.json")).expect("remove the list");
        write_legacy_session(dir.path());

        assert_readers_leave_the_directory_unchanged(dir.path(), &core);
        assert!(!dir.path().join("sessions.json").exists());
    }

    /// docs/18 §2.1: the first locked writer sets a corrupt list aside once, migrates the legacy
    /// account and keeps it.
    #[test]
    fn the_first_writer_quarantines_and_migrates_once_and_keeps_every_account() {
        let (dir, core) = core_in_tempdir();
        std::fs::write(dir.path().join("sessions.json"), GARBAGE_LIST).expect("corrupt the list");
        write_legacy_session(dir.path());
        assert_readers_leave_the_directory_unchanged(dir.path(), &core);

        core.switch_session(0)
            .expect("the legacy account switches in");
        core.switch_session(0).expect("and again");

        let quarantined: Vec<_> = directory_contents(dir.path())
            .into_iter()
            .filter(|(name, _)| name.starts_with("sessions.json.corrupt-"))
            .collect();
        assert_eq!(quarantined.len(), 1, "exactly one quarantine file");
        assert_eq!(quarantined[0].1.as_deref(), Some(GARBAGE_LIST));
        assert_eq!(saved_users(&core), ["u1"]);
    }

    /// docs/18 §2.1: an account list that cannot be read is a storage error, not an empty list.
    #[cfg(unix)]
    #[test]
    fn remove_session_with_an_unreadable_list_is_a_storage_error() {
        let (dir, core) = core_in_tempdir();
        seed_session(dir.path(), "http://server-a.invalid", "u1");
        let Some(_unreadable) =
            crate::persistence::ChmodGuard::unreadable_file(&dir.path().join("sessions.json"))
        else {
            return;
        };

        let error = core.remove_session(0).expect_err("the list cannot be read");

        assert!(matches!(error, CoreError::Cache { .. }), "{error:?}");
    }

    /// docs/18 §2.1: a sign-in that cannot read the account list creates no mirror to strand.
    #[cfg(unix)]
    #[test]
    fn sign_in_with_an_unreadable_list_creates_no_mirror_directory() {
        let (dir, core) = core_in_tempdir();
        seed_session(dir.path(), "http://server-a.invalid", "u1");
        let mock = core.runtime.block_on(RouteMockServer::start());
        mock.route(
            "POST",
            "/Users/AuthenticateByName",
            200,
            serde_json::json!({
                "User": {"Id": SWAP_USER_B, "Name": "Sample User"},
                "AccessToken": "tok-b",
            }),
        );
        let Some(_unreadable) =
            crate::persistence::ChmodGuard::unreadable_file(&dir.path().join("sessions.json"))
        else {
            return;
        };

        let result = core.sign_in(
            mock.base_url.clone(),
            "user".to_string(),
            "pass".to_string(),
        );

        assert!(result.is_err(), "the list cannot be read");
        let mirrors: Vec<String> = directory_contents(dir.path())
            .into_keys()
            .filter(|name| name.starts_with("mirror"))
            .collect();
        assert!(
            mirrors.is_empty(),
            "sign-in left mirror directories behind: {mirrors:?}"
        );
    }

    /// docs/18 §2.1: leaving the sign-in surface while its authentication is in flight does not
    /// stop it: the account is saved, installed and the epoch moves, so the app follows it.
    #[test]
    fn a_sign_in_still_in_flight_after_back_is_saved_and_active() {
        let (_dir, core, _mock_a, _mock_b) = two_accounts_on_mocks();
        let mock_new = core.runtime.block_on(RouteMockServer::start());
        route_authentication(&mock_new, SWAP_USER_B, "tok-new");
        let gate = mock_new.gate("POST", "/Users/AuthenticateByName", 0);
        let epoch_before = core.account_epoch();

        let worker = {
            let core = Arc::clone(&core);
            let url = mock_new.base_url.clone();
            std::thread::spawn(move || core.sign_in(url, "user".to_string(), "pass".to_string()))
        };
        wait_until(
            || mock_new.hit_count("POST", "/Users/AuthenticateByName") >= 1,
            "the authentication request to arrive",
        );
        gate.open();

        let account = worker
            .join()
            .expect("the sign-in thread must not panic")
            .expect("the sign-in completes");
        assert_eq!(account.user_id, SWAP_USER_B);
        assert!(
            saved_users(&core).contains(&SWAP_USER_B.to_string()),
            "saved"
        );
        assert_eq!(
            core.installed_identity().map(|id| id.account.user_id),
            Some(SWAP_USER_B.to_string()),
            "installed"
        );
        assert!(core.account_epoch() > epoch_before, "the epoch moved");
    }

    /// docs/18 §2.1: `account_epochs` reports exactly what the two single-value reads do.
    #[test]
    fn account_epochs_match_the_single_epoch_reads_across_a_switch() {
        let (dir, core) = core_in_tempdir();
        seed_session(dir.path(), "http://server-b.invalid", "u2");
        seed_session(dir.path(), "http://server-a.invalid", "u1");
        core.restore_session().expect("A restores");
        let before = core.account_epochs();
        assert_eq!(before.epoch, core.account_epoch());
        assert_eq!(before.parked, core.parked_account_epoch());

        core.switch_session(0).expect("switch to B");

        let after = core.account_epochs();
        assert!(after.epoch > before.epoch);
        assert_eq!(after.epoch, core.account_epoch());
        assert_eq!(after.parked, core.parked_account_epoch());
    }

    /// The account slot as the sequence test compares it.
    #[derive(Clone, Debug, PartialEq)]
    struct SlotView {
        epoch: u64,
        installed: Option<(AccountIdentity, String)>,
        server: (Option<jellyfin_api::ServerVersion>, Option<String>),
        is_admin: Option<bool>,
        collections: bool,
        /// A bundle is installed (Open).
        open: bool,
    }

    impl SlotView {
        fn of(state: &State) -> Self {
            let installed = state.account.installed();
            Self {
                epoch: state.account.epoch,
                installed: installed.map(|i| (i.identity(), i.mirror_dir.clone())),
                server: installed
                    .map_or((None, None), |i| (i.server_version, i.server_name.clone())),
                is_admin: installed.and_then(|i| i.caches.is_admin),
                collections: installed.is_some_and(|i| i.caches.collections.is_some()),
                open: state.account.bundle().is_some(),
            }
        }

        /// Right after an account change to `saved` (`None`: signed out) under `epoch`.
        fn fresh(epoch: u64, saved: Option<&session::SessionFile>) -> Self {
            Self {
                epoch,
                installed: saved.map(|s| (AccountIdentity::of(s), s.mirror_dir.clone())),
                server: saved.map_or((None, None), |s| {
                    (
                        s.server_version.as_deref().and_then(|v| v.parse().ok()),
                        s.server_name.clone(),
                    )
                }),
                is_admin: None,
                collections: false,
                open: false,
            }
        }
    }

    /// Every bundle a slot sequence built, with the epoch and account it was opened for, and
    /// every bundle the slot handed back for teardown.
    #[derive(Default)]
    struct BundleLedger {
        built: Vec<(
            u64,
            Option<AccountIdentity>,
            tokio::sync::watch::Receiver<jellyfin_core::BusConnection>,
        )>,
        handed: Vec<BusBundle>,
    }

    impl BundleLedger {
        fn build(
            &mut self,
            mirror: &media_cache::Mirror,
            tag: u64,
            account: Option<AccountIdentity>,
        ) -> BusBundle {
            let (_tx, connection) =
                tokio::sync::watch::channel(jellyfin_core::BusConnection::Disconnected);
            self.built.push((tag, account, connection.clone()));
            BusBundle {
                mirror: mirror.clone(),
                listener_task: None,
                tx: tokio::sync::broadcast::channel(1).0,
                connection,
                handle: None,
                forwarder: None,
            }
        }

        fn hand(&mut self, reset: Option<AccountReset>) {
            self.handed.extend(reset.and_then(|reset| reset.bundle));
        }

        /// R1 invariants 4 (each built bundle is installed or handed back, exactly once) and 7
        /// (the installed bundle was opened under the slot's epoch for its account).
        fn violations(&self, state: &State) -> Vec<String> {
            let installed = state.account.bundle();
            let account = state.account.installed().map(Installed::identity);
            let mut found = Vec::new();
            for (i, (tag, opened_for, rx)) in self.built.iter().enumerate() {
                let here = usize::from(installed.is_some_and(|g| g.connection.same_channel(rx)));
                let handed = self
                    .handed
                    .iter()
                    .filter(|g| g.connection.same_channel(rx))
                    .count();
                if here + handed != 1 {
                    found.push(format!(
                        "bundle {i}: installed {here}, handed back {handed}"
                    ));
                }
                if here == 1 && (*tag != state.account.epoch || *opened_for != account) {
                    found.push(format!("bundle {i} opened under {tag} is installed"));
                }
            }
            found
        }
    }

    #[derive(Clone, Copy, Debug)]
    enum SlotStep {
        InstallA,
        InstallB,
        /// Restores the installed account (A when none is) under the current epoch.
        RestoreInstalled,
        /// Restores B under the previous epoch.
        RestoreBStale,
        /// Restores the account not installed (B when none is) under the current epoch.
        RestoreOtherCurrent,
        RemoveInstalled,
        /// Removes the installed account with the other one (B when none is) next in the list.
        RemoveInstalledToOther,
        FactsCurrent,
        FactsStale,
        CacheStoreStale,
        CacheStoreCurrent,
        /// Commits a bundle built for the installed account under the current epoch.
        OpenCommitCurrent,
        /// Commits a bundle built under the previous epoch.
        OpenCommitStale,
    }

    const SLOT_STEPS: [SlotStep; 13] = [
        SlotStep::InstallA,
        SlotStep::InstallB,
        SlotStep::RestoreInstalled,
        SlotStep::RestoreBStale,
        SlotStep::RestoreOtherCurrent,
        SlotStep::RemoveInstalled,
        SlotStep::RemoveInstalledToOther,
        SlotStep::FactsCurrent,
        SlotStep::FactsStale,
        SlotStep::CacheStoreStale,
        SlotStep::CacheStoreCurrent,
        SlotStep::OpenCommitCurrent,
        SlotStep::OpenCommitStale,
    ];

    fn slot_saved(
        server_url: &str,
        user_id: &str,
        version: &str,
        name: &str,
    ) -> session::SessionFile {
        session::SessionFile {
            server_url: server_url.to_string(),
            user_id: user_id.to_string(),
            user_name: format!("{user_id}-name"),
            token: "tok".to_string(),
            device_id: "dev".to_string(),
            mirror_dir: session::mirror_dir_name(server_url, user_id),
            server_version: Some(version.to_string()),
            server_name: Some(name.to_string()),
        }
    }

    fn slot_installed(saved: &session::SessionFile) -> Installed {
        let client = jellyfin_api::JellyfinClient::from_token(
            &saved.server_url,
            JellybeamCore::saved_identity(saved),
            &saved.token,
        )
        .with_user_id(&saved.user_id);
        Installed::of(saved, client)
    }

    /// Runs `step` on `state` and returns the slot the R1 table says it must leave.
    fn apply_slot_step(
        state: &mut State,
        step: SlotStep,
        a: &session::SessionFile,
        b: &session::SessionFile,
        mirror: &media_cache::Mirror,
        ledger: &mut BundleLedger,
    ) -> SlotView {
        let before = SlotView::of(state);
        let current = before.epoch;
        let previous = current.wrapping_sub(1);
        let installed = before.installed.is_some();
        let fresh_version: jellyfin_api::ServerVersion = "12.0.0".parse().expect("parses");
        let store = |state: &mut State, epoch: u64| {
            if let Some(installed) = state.account.installed_mut(epoch) {
                installed.caches.is_admin = Some(true);
            }
        };
        match step {
            SlotStep::InstallA | SlotStep::InstallB => {
                let saved = if matches!(step, SlotStep::InstallA) {
                    a
                } else {
                    b
                };
                ledger.hand(Some(JellybeamCore::reset_account_state_locked(
                    state,
                    Some(slot_installed(saved)),
                    AccountChange::Replace,
                )));
                SlotView::fresh(current + 1, Some(saved))
            }
            SlotStep::RestoreInstalled => {
                let on_b = state
                    .account
                    .installed()
                    .is_some_and(|i| i.identity() == AccountIdentity::of(b));
                let saved = if on_b { b } else { a };
                ledger.hand(JellybeamCore::restore_account_locked(
                    state,
                    current,
                    slot_installed(saved),
                ));
                if installed {
                    before
                } else {
                    SlotView::fresh(current + 1, Some(saved))
                }
            }
            SlotStep::RestoreOtherCurrent => {
                let on_b = state
                    .account
                    .installed()
                    .is_some_and(|i| i.identity() == AccountIdentity::of(b));
                let other = if on_b { a } else { b };
                ledger.hand(JellybeamCore::restore_account_locked(
                    state,
                    current,
                    slot_installed(other),
                ));
                SlotView::fresh(current + 1, Some(other))
            }
            SlotStep::RestoreBStale => {
                ledger.hand(JellybeamCore::restore_account_locked(
                    state,
                    previous,
                    slot_installed(b),
                ));
                before
            }
            SlotStep::RemoveInstalled => {
                ledger.hand(Some(JellybeamCore::reset_account_state_locked(
                    state,
                    None,
                    AccountChange::Leave,
                )));
                SlotView::fresh(current + 1, None)
            }
            SlotStep::RemoveInstalledToOther => {
                let on_b = state
                    .account
                    .installed()
                    .is_some_and(|i| i.identity() == AccountIdentity::of(b));
                let other = if on_b { a } else { b };
                ledger.hand(Some(JellybeamCore::reset_account_state_locked(
                    state,
                    Some(slot_installed(other)),
                    AccountChange::Leave,
                )));
                SlotView::fresh(current + 1, Some(other))
            }
            SlotStep::FactsCurrent | SlotStep::FactsStale => {
                let tag = if matches!(step, SlotStep::FactsCurrent) {
                    current
                } else {
                    previous
                };
                JellybeamCore::apply_server_facts_locked(
                    state,
                    tag,
                    Some(fresh_version),
                    Some("Renamed Server".to_string()),
                );
                if installed && tag == current {
                    SlotView {
                        server: (Some(fresh_version), Some("Renamed Server".to_string())),
                        ..before
                    }
                } else {
                    before
                }
            }
            SlotStep::CacheStoreCurrent | SlotStep::CacheStoreStale => {
                let tag = if matches!(step, SlotStep::CacheStoreCurrent) {
                    current
                } else {
                    previous
                };
                store(state, tag);
                if installed && tag == current {
                    SlotView {
                        is_admin: Some(true),
                        ..before
                    }
                } else {
                    before
                }
            }
            SlotStep::OpenCommitCurrent | SlotStep::OpenCommitStale => {
                let tag = if matches!(step, SlotStep::OpenCommitCurrent) {
                    current
                } else {
                    previous
                };
                let account = before
                    .installed
                    .as_ref()
                    .map(|(identity, _)| identity.clone());
                let bundle = ledger.build(mirror, tag, account);
                if let Err((bundle, _)) = JellybeamCore::commit_bundle_locked(state, tag, bundle) {
                    ledger.handed.push(bundle);
                }
                if installed && tag == current {
                    SlotView {
                        open: true,
                        ..before
                    }
                } else {
                    before
                }
            }
        }
    }

    /// docs/18 §2.1, R1 invariants 2, 3, 4, 7, 8, 9: over every sequence of up to four steps, each
    /// account change moves the epoch by exactly one and installs a fresh, Closed account, restoring
    /// the installed account changes nothing, a result tagged with an older epoch leaves the slot
    /// untouched, and every bundle is installed for its own epoch or handed back exactly once.
    #[test]
    fn every_account_slot_sequence_moves_the_epoch_once_per_change_and_drops_stale_results() {
        let a = slot_saved("http://server-a.test", "u-a", "10.11.10", "Server A");
        let b = slot_saved("http://server-b.test", "u-b", "10.10.7", "Server B");
        let mut sequences: Vec<Vec<SlotStep>> = Vec::new();
        let mut frontier = vec![Vec::new()];
        for _ in 0..4 {
            frontier = frontier
                .iter()
                .flat_map(|seq| {
                    SLOT_STEPS
                        .iter()
                        .map(move |step| [seq.as_slice(), &[*step]].concat())
                })
                .collect();
            sequences.extend(frontier.iter().cloned());
        }
        let runtime = tokio::runtime::Runtime::new().expect("runtime");
        let dir = tempfile::tempdir().expect("tempdir");
        let mirror = test_mirror(&runtime, dir.path());
        let mut failures = Vec::new();
        for sequence in &sequences {
            let mut state = State::default();
            let mut ledger = BundleLedger::default();
            for (i, step) in sequence.iter().enumerate() {
                let before_epoch = state.account.epoch;
                let expected = apply_slot_step(&mut state, *step, &a, &b, &mirror, &mut ledger);
                let after = SlotView::of(&state);
                let broken = ledger.violations(&state);
                if after != expected || after.epoch < before_epoch || !broken.is_empty() {
                    failures.push(format!(
                        "{sequence:?} step {i} {step:?}: left {after:?}, expected {expected:?}, \
                         bundles {broken:?}"
                    ));
                    break;
                }
            }
        }
        assert_eq!(
            sequences.len(),
            13 + 169 + 2197 + 28561,
            "every sequence up to length 4"
        );
        assert!(
            failures.is_empty(),
            "{} failing sequences, first: {:?}",
            failures.len(),
            failures.first()
        );
    }

    /// Counts the `Refresh` changes a core sends its listener.
    #[derive(Default)]
    struct RefreshCounter(std::sync::atomic::AtomicUsize);

    impl ChangeListener for RefreshCounter {
        fn on_change(&self, event: ChangeEvent) -> Result<(), crate::error::CallbackError> {
            if matches!(event, ChangeEvent::Refresh) {
                self.0.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
            }
            Ok(())
        }

        fn on_unauthorized(
            &self,
            _account: AccountIdentity,
        ) -> Result<(), crate::error::CallbackError> {
            Ok(())
        }
    }

    /// docs/18 §2.1 (R3): a version fetched for A's install that lands after a switch away and
    /// back reaches only A's saved record, never the account installed since: its facts stay the
    /// saved ones and no `Refresh` fires.
    #[test]
    fn a_version_refresh_spanning_a_switch_never_reaches_the_new_account() {
        let (dir, core) = core_in_tempdir();
        let mock_a = core.runtime.block_on(RouteMockServer::start());
        let mock_b = core.runtime.block_on(RouteMockServer::start());
        mock_a.route_script(
            "GET",
            "/System/Info/Public",
            vec![
                serde_json::json!({"Version": "12.0.0"}),
                serde_json::json!({"Version": "10.11.10"}),
            ],
            vec![],
        );
        // A's first fetch replies late; its second never does, so only the late one lands.
        let late = mock_a.gate("GET", "/System/Info/Public", 0);
        let _never = mock_a.gate("GET", "/System/Info/Public", 1);
        mock_b.route(
            "GET",
            "/System/Info/Public",
            200,
            serde_json::json!({"Version": "10.10.7"}),
        );
        let a = slot_saved(&mock_a.base_url, "u-a", "10.11.10", "Server A");
        session::add_or_update(dir.path(), a.clone()).expect("seed A");
        let b = slot_saved(&mock_b.base_url, "u-b", "10.10.7", "Server B");
        session::add_or_update(dir.path(), b).expect("seed B");
        let refreshes = Arc::new(RefreshCounter::default());
        core.set_change_listener(refreshes.clone());

        core.switch_session(0).expect("A is installed");
        wait_until(
            || mock_a.hit_count("GET", "/System/Info/Public") >= 1,
            "A's version fetch to reach its server",
        );
        core.switch_session(1).expect("B is installed");
        assert_eq!(core.server_version().as_deref(), Some("10.10.7"));
        core.switch_session(0).expect("A is installed again");
        wait_until(
            || mock_a.hit_count("GET", "/System/Info/Public") >= 2,
            "the new install's own fetch to reach A's server",
        );
        late.open();
        wait_until(
            || {
                session::load_list(dir.path())
                    .sessions
                    .iter()
                    .any(|s| s.user_id == "u-a" && s.server_version.as_deref() == Some("12.0.0"))
            },
            "the late reply to persist onto A's saved record",
        );
        settle();

        assert_eq!(
            core.server_version().as_deref(),
            Some("10.11.10"),
            "the new install keeps its saved facts"
        );
        assert_eq!(
            refreshes.0.load(std::sync::atomic::Ordering::SeqCst),
            0,
            "a late reply fires no Refresh"
        );
    }

    /// docs/13 Server compatibility: an install's own refresh is tagged with the incoming
    /// account's epoch, so its reply lands even though no later fetch reports a version.
    #[test]
    fn an_install_refresh_lands_under_the_incoming_accounts_epoch() {
        let (dir, core) = core_in_tempdir();
        let mock = core.runtime.block_on(RouteMockServer::start());
        mock.route_script(
            "GET",
            "/System/Info/Public",
            vec![
                serde_json::json!({"Version": "12.0.0"}),
                serde_json::json!({}),
            ],
            vec![],
        );
        let saved = slot_saved(&mock.base_url, "u-a", "10.11.10", "Server A");
        session::add_or_update(dir.path(), saved).expect("seed A");

        assert!(core.restore_session().is_some());

        wait_until(
            || core.server_version().as_deref() == Some("12.0.0"),
            "the install's refresh to update the installed version",
        );
    }

    /// docs/18 §2.1 (R4): a restore that read the epoch before a switch landed installs nothing
    /// over the switched-to account and answers with it.
    #[test]
    fn a_restore_that_lost_to_a_switch_installs_nothing() {
        let (dir, core) = core_in_tempdir();
        seed_session(dir.path(), "http://server-b.test", "u2");
        seed_session(dir.path(), "http://server-a.test", "u1");
        let tag = core.account_epoch();
        let saved = session::load(dir.path()).expect("A is saved active");
        core.switch_session(0).expect("B is installed meanwhile");
        let after_switch = core.account_epochs();

        let restored = core.restore_finish(tag, saved);

        assert_eq!(
            core.account_epochs(),
            after_switch,
            "the restore changed nothing"
        );
        assert_eq!(
            installed_user(&core).as_deref(),
            Some("u2"),
            "B stays installed"
        );
        assert_eq!(
            restored.map(|account| account.user_id).as_deref(),
            Some("u2"),
            "the answer is the account installed now"
        );
    }

    /// docs/18 §2.1 (R5): removing the installed account installs the next one in one account
    /// change, so the app sees the epoch move once.
    #[test]
    fn removing_the_active_account_moves_the_epoch_once() {
        let (dir, core) = core_in_tempdir();
        seed_session(dir.path(), "http://server-b.test", "u2");
        seed_session(dir.path(), "http://server-a.test", "u1");
        assert!(core.restore_session().is_some());
        let before = core.account_epochs().epoch;

        assert!(core.remove_session(1).expect("A is removed"));

        assert_eq!(
            core.account_epochs().epoch,
            before + 1,
            "one account change"
        );
        assert_eq!(
            installed_user(&core).as_deref(),
            Some("u2"),
            "B is installed"
        );
    }

    /// docs/13 About > server info (R6): the snapshot names the installed account, even while the
    /// saved list's active row names another.
    #[test]
    fn about_shows_the_installed_accounts_address_when_the_list_names_another() {
        let accounts = installed_b_while_the_list_names_a();

        let snapshot = accounts.core.server_info_snapshot();

        assert_eq!(
            snapshot.server_url.as_deref(),
            Some(accounts.mock_b.base_url.as_str())
        );
        assert_eq!(snapshot.user_name, Some(format!("{SWAP_USER_B}-name")));
    }

    /// docs/18 §2.1 (R7): an add to a collection that spans a switch refreshes no other account's
    /// mirror once its post lands.
    #[test]
    fn add_to_collection_spanning_a_switch_refreshes_no_other_accounts_mirror() {
        const COLLECTION_ID: &str = "00000000-0000-0000-0000-000000000031";
        let accounts = core_with_two_accounts(AccountSwap::Switch);
        accounts.core.open_mirror().expect("A opens");
        let held = accounts.mock_a.gate("POST", "/Collections/", 0);
        let adding = {
            let core = accounts.core.clone();
            std::thread::spawn(move || {
                core.add_to_collection(COLLECTION_ID.to_string(), "item-1".to_string())
            })
        };
        wait_until(
            || {
                accounts
                    .mock_a
                    .hit_count_containing("POST", "/Collections/")
                    >= 1
            },
            "A's add to reach its server",
        );
        apply_account_swap(&accounts, AccountSwap::Switch);
        accounts.core.open_mirror().expect("B opens");

        held.open();
        adding
            .join()
            .expect("add thread")
            .expect("the add itself succeeded on A");
        settle();

        let refreshes = accounts
            .mock_b
            .log_snapshot()
            .iter()
            .filter(|entry| entry.contains(&format!("parentId={COLLECTION_ID}")))
            .count();
        assert_eq!(refreshes, 0, "B's mirror must not refresh A's collection");
    }
}
