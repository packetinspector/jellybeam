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
use crate::library_prefs::LibraryGridPrefsFile;
use crate::settings::{PlaybackQuality, Settings};
use crate::track_prefs::TrackPrefsFile;
use crate::types::{
    AccountInfo, Card, ChangeEvent, CollectionInfo, DeviceCaps, EpisodeNeighbors, GridCounts,
    GridFilters, GridGroup, GridSort, HomeSnapshot, ImageKind, ItemDetail, LatestShelf,
    LibraryGridPrefs, LiveSort, MediaSegment, MirrorItemCounts, MirrorLibrary, MirrorStats,
    PlayMethodFfi, PlaybackOsdDetail, PlaybackPlan, QuickConnectSession, ServerDetails,
    ServerInfoSnapshot, SortOrder, SyncStatus, TrackDecisionFfi, TrackInfo, TrackKindFfi,
    TrickplayMetaFfi, ViewSnapshot,
};
use crate::{device_id, library_prefs, session, settings, signin, track_prefs};

/// Foreign-implemented sink for mirror change notifications. Kotlin hands an
/// instance to [`JellybeamCore::set_change_listener`]; `on_change` is called from
/// a background task on `JellybeamCore`'s own runtime, never the caller's thread.
#[uniffi::export(with_foreign)]
pub trait ChangeListener: Send + Sync {
    fn on_change(&self, event: ChangeEvent);
}

/// Local resume checkpoints are independent of Jellyfin's network progress
/// cadence: commit the first useful position immediately, then throttle to
/// the same ten-second scale as server reports. Android force-stop runs no
/// lifecycle callback, so these are the only reliable progress record.
const LOCAL_PROGRESS_CHECKPOINT_INTERVAL: Duration = Duration::from_secs(10);

/// docs/19-detail-action-menu.md §1/§2.3: [`JellybeamCore::list_collections`]'s
/// cache TTL -- one live BoxSet list per session, re-read after ten minutes.
const COLLECTIONS_CACHE_TTL: Duration = Duration::from_secs(10 * 60);

/// How often [`JellybeamCore::spawn_server_version_reconnect_watch`] polls
/// `/System/Info/Public` on its own, independent of the `bus_tx` `Connected`
/// event it also listens for (docs/13 Server compatibility).
/// Backstops the gaps with no bus running (before the first `open_mirror`,
/// or between sessions); once a bus is running, its `Connected` event fires
/// this same refresh immediately instead. Shortened under `cfg(test)`.
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

/// One-slot cache of a focus-dwell preload's negotiation result. Only ever
/// holds a [`ResolvedPlan::DirectPlay`] outcome (docs/18-playback-quality.md
/// §2: "preload_playback caches DirectPlay plans only") -- a `Transcode`
/// result's `url` embeds the negotiation's start position, so it isn't safe
/// to reuse the way a Direct Play url is.
struct PreloadCache {
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
/// newest request in [`State::preload_pending`] lets rapid focus changes
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
    fn is_fresh_for(&self, generation: u64, item_id: &str, now: Instant) -> bool {
        self.generation == generation
            && self.item_id == item_id
            && now.saturating_duration_since(self.created_at) < PRELOAD_CACHE_TTL
    }
}

/// One mirror's own event relay: everything a real `jellyfin_core::EventBus`
/// emits for that mirror's client is copied here, and `Mirror::open`
/// subscribes on `tx`, never on [`JellybeamCore::bus_tx`] directly, so two
/// accounts' bundles briefly alive at once (during
/// [`JellybeamCore::install_authenticated_session`]'s swap window) can never
/// cross-deliver a `UserDataChanged` between them.
struct BusBundle {
    /// Per-bundle relay every event the real bus emits is copied onto; the
    /// mirror's receiver is subscribed here. Kept alive here (not only in the
    /// forwarder) so the mirror's receiver never sees `Closed` while the
    /// bundle lives, including the disabled-bus test path. Also lets tests
    /// subscribe/send directly to prove the account-isolation this struct
    /// exists for.
    #[allow(dead_code)]
    tx: tokio::sync::broadcast::Sender<jellyfin_core::BusEvent>,
    handle: Option<jellyfin_core::EventBusHandle>,
    forwarder: Option<tokio::task::JoinHandle<()>>,
}

/// Mutable state behind a `Mutex` so `JellybeamCore` itself can be `Sync`
/// (required of a `#[derive(uniffi::Object)]` type). `Mirror`/`JellyfinClient`
/// are cheap-clone `Arc` handles, so methods clone out of the lock rather
/// than holding the guard across a `block_on`/foreign call.
#[derive(Default)]
pub(crate) struct State {
    client: Option<jellyfin_api::JellyfinClient>,
    mirror: Option<media_cache::Mirror>,
    /// The active session's `SessionFile::mirror_dir` (`session.rs`): which
    /// subdirectory of `data_dir` [`JellybeamCore::open_mirror`] opens `mirror.db`
    /// inside. Set alongside `client` by every install path, cleared by
    /// [`JellybeamCore::sign_out`]; kept in memory so it can never drift from
    /// whichever session `client` was built from.
    mirror_dir: Option<String>,
    /// The active session's last-known server version (docs/13 Server
    /// compatibility), read by [`JellybeamCore::server_version`]/
    /// [`JellybeamCore::server_at_least`]. Seeded synchronously offline from the
    /// saved session record by every install path, then kept fresh by
    /// [`JellybeamCore::refresh_server_version_once`]'s periodic poll and bus
    /// `Connected` triggers. `None` means "unknown", never "old server":
    /// `server_at_least` fails closed on either.
    server_version: Option<jellyfin_api::ServerVersion>,
    /// The active session's last-known server name (docs/13 About > server info), shown
    /// verbatim -- never renamed/prettified (CLAUDE.md). Seeded/cleared/refreshed alongside
    /// [`Self::server_version`], but a name-only change never fires [`ChangeEvent::Refresh`]
    /// (see [`JellybeamCore::apply_public_server_info`]).
    server_name: Option<String>,
    /// Live-events connection state for [`JellybeamCore::server_info_snapshot`]'s
    /// `live_events_connected` -- set by the reconnect watch's `Connected`/`Disconnected`
    /// bus events, cleared at every session-end point ([`JellybeamCore::stop_event_bus`]/
    /// `sign_out`/switch/remove).
    bus_connected: bool,
    /// Kept independently of `listener_task` so a listener registered before
    /// [`JellybeamCore::open_mirror`] is still bound once the mirror opens, and
    /// rebinds automatically across a sign-out + fresh sign-in.
    listener: Option<Arc<dyn ChangeListener>>,
    /// The forwarding task started by [`JellybeamCore::spawn_listener_task`],
    /// if a listener is currently bound to an open mirror.
    listener_task: Option<tokio::task::JoinHandle<()>>,
    /// The [`BusBundle`] bound to the currently open mirror's client, if
    /// any -- created by [`JellybeamCore::open_mirror_with_bus`] right before it
    /// opens the mirror, torn down at every session-end point via
    /// [`JellybeamCore::stop_event_bus`]. `None` before the first successful
    /// `open_mirror` and after every teardown. Kept here (not only in the
    /// forwarder closure) so teardown can actually stop the reconnect loop
    /// instead of leaking it across a session switch.
    event_bus: Option<BusBundle>,
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
    /// and kept in sync by [`JellybeamCore::remember_track_choice`]; read by
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
    /// [`JellybeamCore::preload_playback`]'s one-slot result cache, consumed
    /// (taken, never merely read) by [`JellybeamCore::prepare_playback`].
    /// Cleared on every session-swap point so a preload negotiated against
    /// one account's server can never be consumed by a different one.
    preload_cache: Option<PreloadCache>,
    /// Monotonic ownership token for speculative playback negotiation. Every
    /// replacement, real prepare, session swap, or profile change advances
    /// it; a background task may publish only while its captured generation
    /// is still current.
    preload_generation: u64,
    /// Monotonic ownership token for [`Self::reporting`], bumped whenever it
    /// changes hands. `prepare_playback`/`prepare_transcode_fallback`
    /// negotiate with the state lock released, so each records this
    /// generation before releasing the lock and re-checks it before
    /// installing its own session (docs/18-playback-quality.md §2's fallback
    /// race) -- a mismatch means a newer call already moved `reporting` on,
    /// so the stale one must not clobber it.
    playback_generation: u64,
    /// The sole speculative worker, bounding focus-driven negotiation to one
    /// active server request regardless of cancelled coroutines unwinding late.
    preload_task: Option<tokio::task::JoinHandle<()>>,
    /// Item owned by `preload_task`, coalescing duplicate high-signal
    /// triggers without restarting the same request.
    preload_target_item_id: Option<String>,
    /// Latest target requested while `preload_task` still negotiates an
    /// older item; replaced in place so intermediate cards never queue up.
    preload_pending: Option<PreloadRequest>,
    /// The active account's live Seerr Discover connection, if any -- built
    /// lazily on first Seerr call, never at startup (docs/14-seerr-discover.md's
    /// "zero startup cost" rule). Cleared at every session-swap point, same
    /// discipline as `preload_cache`. `pub(crate)` so `seerr.rs`'s sibling
    /// `impl JellybeamCore` block can reach it through `JellybeamCore::lock_state`.
    pub(crate) seerr: Option<crate::seerr::SeerrHandle>,
    /// docs/19-detail-action-menu.md §2.3: the session's cached BoxSet
    /// ("collection") list -- `(fetched_at, collections)`, re-fetched by
    /// [`JellybeamCore::list_collections`] once older than ten minutes. Cleared
    /// at every session-swap point, same discipline as `preload_cache`/`seerr`.
    collections: Option<(Instant, Vec<CollectionInfo>)>,
    /// docs/19-detail-action-menu.md §2.3: [`JellybeamCore::is_administrator`]'s
    /// per-session cache. Cleared at the same session-swap points as
    /// [`Self::collections`].
    is_admin: Option<bool>,
    /// "Still watching?" inactivity counters for the current play-next chain
    /// (docs/feature-dev/spec-still-watching-and-lan-discovery.md Feature A).
    /// Lives only as long as this `JellybeamCore` instance's current playback
    /// session; not reset on session-swap since
    /// `still_watching::JellybeamCore::note_player_input` resets it at every new
    /// player-session construction anyway. `pub(crate)` for the same
    /// cross-module reason as [`Self::seerr`].
    pub(crate) still_watching: playback_policy::still_watching::InactivityState,
}

/// Extra (non-default) `Fields` [`JellybeamCore::live_children`] requests --
/// kept byte-for-byte the same as `media-cache::sync::item_fields()` (private
/// to a non-`pub` module, so not reusable from here). Keep in sync by hand.
fn live_children_fields() -> Vec<String> {
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
    /// This `JellybeamCore`'s own long-lived *status-only* relay: connection
    /// state, never a per-account [`jellyfin_core::BusEvent::Server`]
    /// payload. [`Self::new`] subscribes
    /// [`Self::spawn_server_version_reconnect_watch`] to it once, for the
    /// process lifetime. Each [`JellybeamCore::open_mirror_with_bus`] call
    /// creates its own [`BusBundle`] instead of subscribing here, whose
    /// forwarder relays only `Connected`/`Disconnected`/`NeedsReconcile`
    /// onward onto this sender -- which therefore carries no data that could
    /// cross an account boundary between two briefly-overlapping mirrors.
    bus_tx: tokio::sync::broadcast::Sender<jellyfin_core::BusEvent>,
    // NOTE ordering: `state` (which can transitively hold a `Mirror`, and so
    // a live handle into the writer's mpsc channel) MUST be declared --
    // and therefore dropped -- before `runtime`. `Mirror::open` parks a
    // writer thread on `runtime`'s blocking pool that only returns once every
    // `Sender` clone is dropped; `Runtime::drop` joins that pool and blocks
    // forever if a live `Sender` outlives it. Rust drops fields in
    // declaration order, so this alone drops `state` first; the explicit
    // `Drop` impl below additionally handles the listener task's own
    // `Mirror` clone (not a struct field, so declaration order misses it).
    state: Arc<Mutex<State>>,
    /// Serializes every read-modify-write of the on-disk session list
    /// across every mutating site -- without this, a version refresh that
    /// loaded the list before a concurrent sign-out/switch/remove could write
    /// its own stale snapshot back, resurrecting a removed account or an old
    /// token. Held only for one load-modify-save sequence, never across
    /// network I/O or [`Self::state`]'s own lock (the reverse nesting --
    /// taking `state` briefly inside a held `session_lock` -- is fine as long
    /// as nothing takes them in the opposite order). An `Arc` so a
    /// background refresh task can hold its own clone without keeping a
    /// whole `JellybeamCore` alive.
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
    track_prefs_save: crate::persistence::RevisionWriter,
    seerr_config: crate::seerr::SeerrConfigStore,
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

        let (bus_tx, _bus_rx) = tokio::sync::broadcast::channel::<jellyfin_core::BusEvent>(16);

        let state = Arc::new(Mutex::new(State {
            settings: loaded_settings,
            track_prefs: loaded_track_prefs,
            library_grid_prefs: loaded_library_grid_prefs,
            ..State::default()
        }));
        let session_lock = Arc::new(Mutex::new(()));

        // docs/13 Server compatibility -- periodic poll (backstop
        // for gaps with no bus running) plus the bus `Connected` event once
        // `open_mirror` has spawned a real websocket `EventBus`. Spawned
        // exactly once here (not per-session like `listener_task`): `bus_tx`
        // outlives every sign-in/switch/sign-out, so one long-lived task
        // covers every account. Holds no `Mirror`/writer-channel handle, so
        // unlike `listener_task` it needs no `JoinHandle` kept for
        // abort/join on drop -- it exits on its own once `bus_tx` drops.
        Self::spawn_server_version_reconnect_watch(
            bus_tx.subscribe(),
            state.clone(),
            data_dir.clone(),
            session_lock.clone(),
            &runtime,
        );

        Arc::new(Self {
            data_dir,
            device_id,
            bus_tx,
            state,
            session_lock,
            playback_write: Mutex::new(()),
            library_grid_prefs_save: crate::persistence::RevisionWriter::default(),
            track_prefs_save: crate::persistence::RevisionWriter::default(),
            seerr_config: crate::seerr::SeerrConfigStore::default(),
            runtime,
        })
    }

    pub fn sign_in(
        &self,
        server_url: String,
        username: String,
        password: String,
    ) -> Result<AccountInfo, CoreError> {
        let server_url = signin::normalize_sign_in_url(&server_url)?;
        let host = signin::host_for_display(&server_url);
        let identity = self.client_identity(&server_url);
        let (client, auth) = self
            .runtime
            .block_on(jellyfin_api::JellyfinClient::authenticate_by_name(
                &server_url,
                identity,
                &username,
                &password,
            ))
            .map_err(|e| signin::classify_sign_in_error(&host, e))?;

        self.install_authenticated_session(server_url, client, auth)
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
                &target.server_url,
                identity,
                &username,
                &password,
            ))
            .map_err(|e| signin::classify_sign_in_error(&host, e))?;

        self.install_reauthenticated_session(index, target, client, auth)
    }

    /// Whether this server currently permits Quick Connect: one bounded
    /// request. Android owns the cancellable poll loop.
    pub fn quick_connect_enabled(&self, server_url: String) -> Result<bool, CoreError> {
        let server_url = signin::normalize_sign_in_url(&server_url)?;
        let host = signin::host_for_display(&server_url);
        let identity = self.client_identity(&server_url);
        self.runtime
            .block_on(jellyfin_api::JellyfinClient::quick_connect_enabled(
                &server_url,
                &identity,
            ))
            .map_err(|e| signin::classify_sign_in_error(&host, e))
    }

    /// Starts a Quick Connect request. The secret is returned only for
    /// [Self::quick_connect_poll]/[Self::complete_quick_connect]; never persisted.
    pub fn initiate_quick_connect(
        &self,
        server_url: String,
    ) -> Result<QuickConnectSession, CoreError> {
        let server_url = signin::normalize_sign_in_url(&server_url)?;
        let host = signin::host_for_display(&server_url);
        let identity = self.client_identity(&server_url);
        let result = self
            .runtime
            .block_on(jellyfin_api::JellyfinClient::quick_connect_initiate(
                &server_url,
                &identity,
            ))
            .map_err(|e| signin::classify_sign_in_error(&host, e))?;
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
        let identity = self.client_identity(&server_url);
        let result = self
            .runtime
            .block_on(jellyfin_api::JellyfinClient::quick_connect_poll(
                &server_url,
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
        let server_url = signin::normalize_sign_in_url(&server_url)?;
        let host = signin::host_for_display(&server_url);
        let identity = self.client_identity(&server_url);
        let (client, auth) = self
            .runtime
            .block_on(
                jellyfin_api::JellyfinClient::authenticate_with_quick_connect(
                    &server_url,
                    identity,
                    &secret,
                ),
            )
            .map_err(|e| signin::classify_sign_in_error(&host, e))?;
        self.install_authenticated_session(server_url, client, auth)
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
        let saved = session::load(&self.data_dir)?;
        let identity = Self::saved_identity(&saved);
        let client =
            jellyfin_api::JellyfinClient::from_token(&saved.server_url, identity, &saved.token)
                .with_user_id(&saved.user_id);

        let preload_task = {
            let mut state = self.lock_state();
            state.client = Some(client);
            state.mirror_dir = Some(saved.mirror_dir.clone());
            // docs/13 Server compatibility -- seeded offline from
            // whatever was last persisted, so `server_at_least` has an
            // answer before the refresh below reaches the network.
            state.server_version = saved.server_version.as_deref().and_then(|v| v.parse().ok());
            state.server_name = saved.server_name.clone();
            Self::reset_account_state_locked(&mut state)
        };
        Self::abort_task(preload_task);
        self.spawn_server_version_refresh();

        Some(AccountInfo {
            server_url: saved.server_url,
            user_id: saved.user_id,
            user_name: saved.user_name,
        })
    }

    /// Read-only "who is signed in" check: unlike
    /// [`Self::restore_session`], never constructs a client, never touches
    /// `self.state` or the mirror -- just whatever `session.json` currently
    /// says (tolerant load). Never returns the token: [`AccountInfo`] has no
    /// field for it.
    pub fn current_account(&self) -> Option<AccountInfo> {
        let saved = session::load(&self.data_dir)?;
        Some(AccountInfo {
            server_url: saved.server_url,
            user_id: saved.user_id,
            user_name: saved.user_name,
        })
    }

    /// The active session's last-known server version string, or `None` if
    /// nobody is signed in or no refresh has completed (docs/13 Server
    /// compatibility). Reads in-memory `State::server_version`, never
    /// a fresh network call. Also gated on `state.client.is_some()`, not just
    /// `server_version`, as a backstop should a future teardown clear one
    /// without the other.
    pub fn server_version(&self) -> Option<String> {
        let state = self.lock_state();
        state.client.as_ref()?;
        state.server_version.map(|v| v.to_string())
    }

    /// Whether the active session's server is known to be at least
    /// `major.minor` (docs/13 Server compatibility). **Fails
    /// closed**: `false` whenever the version isn't known yet -- never
    /// assumes a floor for "unknown". Also checks `state.client.is_some()`,
    /// same reasoning as [`Self::server_version`].
    pub fn server_at_least(&self, major: u32, minor: u32) -> bool {
        let state = self.lock_state();
        state.client.is_some()
            && state
                .server_version
                .is_some_and(|v| v.at_least(major, minor))
    }

    /// The playback stats sheet's SOURCE row
    /// (`docs/jellybeam-osd-handoff/handoff/JELLYBEAM-TV-OSD-SPEC.md` §8b) needs a
    /// server name; nothing persists Jellyfin's own `ServerName`, so this
    /// derives a display name from the session's `server_url` instead of an
    /// extra round trip -- see [`host_from_server_url`]. Same read-only,
    /// tolerant-load contract as [`Self::current_account`]; `None` if nobody
    /// is signed in or the URL has no parseable host.
    pub fn server_display_name(&self) -> Option<String> {
        let saved = session::load(&self.data_dir)?;
        host_from_server_url(&saved.server_url)
    }

    /// docs/13 About > server info: the offline snapshot the About page renders
    /// immediately, no network call -- everything already in memory/on disk.
    /// All fields are `None`/`false`/empty when nobody is signed in.
    pub fn server_info_snapshot(&self) -> ServerInfoSnapshot {
        // Clone out of the lock before the disk and SQLite reads below (the `state` field's
        // rule: never hold the guard across I/O).
        let (signed_in, mirror_handle, server_name, server_version, bus_connected) = {
            let state = self.lock_state();
            (
                state.client.is_some(),
                state.mirror.clone(),
                state.server_name.clone(),
                state.server_version,
                state.bus_connected,
            )
        };
        if !signed_in {
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
        }

        let saved = session::load(&self.data_dir);
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
            server_url: saved.as_ref().map(|s| s.server_url.clone()),
            server_name,
            server_version: server_version.map(|v| v.to_string()),
            user_name: saved.as_ref().map(|s| s.user_name.clone()),
            device_id: saved.as_ref().map(|s| s.device_id.clone()),
            live_events_connected: bus_connected,
            mirror,
            libraries,
        }
    }

    /// docs/13 About > server info: the on-demand refresh behind the About page's refresh
    /// action. Tries the authenticated `GET /System/Info` first (richer than the pre-auth
    /// `/System/Info/Public`); a server that refuses it to a non-admin user
    /// (401/403) falls back to `/System/Info/Public` with `system_info_available: false` and
    /// the extra fields left `None`. Whichever version/name came back is handed to
    /// [`Self::apply_public_server_info`], so this refresh also refreshes the version gate and
    /// persists the server name -- the same reason `server_version`/`server_name` are never
    /// stale for long even without this being called.
    pub fn fetch_server_details(&self) -> Result<ServerDetails, CoreError> {
        let client = self
            .lock_state()
            .client
            .clone()
            .ok_or(CoreError::NotSignedIn)?;

        let (
            system_info_available,
            name,
            version,
            product_name,
            operating_system,
            architecture,
            has_pending_restart,
            has_update_available,
        ) = match self.runtime.block_on(client.system_info()) {
            Ok(info) => (
                true,
                info.server_name,
                info.version,
                info.product_name,
                info.operating_system_display_name,
                info.system_architecture,
                info.has_pending_restart,
                info.has_update_available,
            ),
            Err(jellyfin_api::ApiError::Unauthorized)
            | Err(jellyfin_api::ApiError::Status { code: 403, .. }) => {
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
            Err(e) => return Err(e.into()),
        };

        self.runtime.block_on(Self::apply_public_server_info(
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

    /// Signs out the *active* account only (multiple accounts can be signed
    /// in at once). Removes it from the on-disk list; if others remain, the
    /// first becomes active on disk but its client is deliberately **not**
    /// installed -- Kotlin decides what to show next.
    pub fn sign_out(&self) {
        let (client, mirror, task, bundle, preload_task) = {
            let mut state = self.lock_state();
            state.mirror_dir = None;
            // Cleared alongside `client`, same as every other per-session
            // cache -- otherwise `server_version`/`server_at_least` would
            // keep answering from a dead session until the next sign-in.
            state.server_version = None;
            state.server_name = None;
            let preload_task = Self::reset_account_state_locked(&mut state);
            (
                state.client.take(),
                state.mirror.take(),
                state.listener_task.take(),
                state.event_bus.take(),
                preload_task,
            )
        };
        Self::abort_task(preload_task);
        self.stop_task(task);
        self.stop_event_bus(bundle);
        if let Some(mirror) = &mirror {
            mirror.set_playback_active(false);
        }
        drop(mirror);
        drop(client);
        // Routed through `session_lock` so a `refresh_server_version_once`
        // persist that loaded the list before this sign-out can never write
        // a stale snapshot back and resurrect the account just removed.
        let _guard = self.session_lock.lock().unwrap_or_else(|e| e.into_inner());
        session::remove_active(&self.data_dir);
    }

    /// Every signed-in account, in on-disk order (index `i` is what
    /// [`Self::switch_session`] expects). Never carries a token.
    pub fn list_accounts(&self) -> Vec<AccountInfo> {
        session::load_list(&self.data_dir)
            .sessions
            .into_iter()
            .map(|s| AccountInfo {
                server_url: s.server_url,
                user_id: s.user_id,
                user_name: s.user_name,
            })
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

    /// Switches the active account to `index` and installs its client, but
    /// like [`Self::restore_session`] with **no network validation**.
    /// Persists the new `active` index first, then tears down whatever
    /// client/mirror/listener is bound before installing the target's
    /// client. Does **not** call [`Self::open_mirror`] itself.
    pub fn switch_session(&self, index: u32) -> Result<AccountInfo, CoreError> {
        let ix = index as usize;
        // The load-mutate-save of the active index is one read-modify-write
        // of the session list, routed through `session_lock` like every other.
        let target = {
            let _guard = self.session_lock.lock().unwrap_or_else(|e| e.into_inner());
            let mut list = session::load_list(&self.data_dir);
            if ix >= list.sessions.len() {
                return Err(CoreError::InvalidSessionIndex { index });
            }
            list.active = ix;
            if let Err(e) = session::save_list(&self.data_dir, &list) {
                tracing::warn!(error = %e, "failed to persist active session index");
            }
            list.sessions[ix].clone()
        };

        let (old_mirror, old_task, old_bundle, preload_task) = {
            let mut state = self.lock_state();
            let preload_task = Self::reset_account_state_locked(&mut state);
            (
                state.mirror.take(),
                state.listener_task.take(),
                state.event_bus.take(),
                preload_task,
            )
        };
        Self::abort_task(preload_task);
        self.stop_task(old_task);
        self.stop_event_bus(old_bundle);
        if let Some(old_mirror) = &old_mirror {
            old_mirror.set_playback_active(false);
        }
        drop(old_mirror);

        let identity = Self::saved_identity(&target);
        let client =
            jellyfin_api::JellyfinClient::from_token(&target.server_url, identity, &target.token)
                .with_user_id(&target.user_id);

        {
            let mut state = self.lock_state();
            state.client = Some(client);
            state.mirror_dir = Some(target.mirror_dir);
            // docs/13 Server compatibility -- offline seed from
            // this account's own last-persisted value, not the previously
            // active account's.
            state.server_version = target
                .server_version
                .as_deref()
                .and_then(|v| v.parse().ok());
            state.server_name = target.server_name.clone();
        }
        self.spawn_server_version_refresh();

        Ok(AccountInfo {
            server_url: target.server_url,
            user_id: target.user_id,
            user_name: target.user_name,
        })
    }

    /// Permanently removes one saved account and its isolated local mirror.
    /// The session list is committed before any live state is torn down, so
    /// a disk failure leaves the current account fully usable.
    pub fn remove_session(&self, index: u32) -> Result<bool, CoreError> {
        let ix = index as usize;
        // The bounds/active check and the removal are one read-modify-write
        // of the session list, routed through `session_lock` so a concurrent
        // version-refresh persist can never re-add this row in between.
        let (removed_active, removed) = {
            let _guard = self.session_lock.lock().unwrap_or_else(|e| e.into_inner());
            let list_before = session::load_list(&self.data_dir);
            if ix >= list_before.sessions.len() {
                return Err(CoreError::InvalidSessionIndex { index });
            }
            let removed_active = ix == list_before.active;
            let removed = session::remove_at(&self.data_dir, ix)
                .map_err(|e| CoreError::Cache {
                    detail: format!("failed to remove saved session: {e}"),
                })?
                .ok_or(CoreError::InvalidSessionIndex { index })?;
            (removed_active, removed)
        };

        if removed_active {
            let (client, mirror, task, bundle, reporting, preload_task) = {
                let mut state = self.lock_state();
                state.mirror_dir = None;
                // Cleared alongside `client` -- same reasoning as
                // `sign_out`'s own comment on this field.
                state.server_version = None;
                state.server_name = None;
                state.local_progress_checkpoint.reset();
                let preload_task = Self::reset_account_state_locked(&mut state);
                (
                    state.client.take(),
                    state.mirror.take(),
                    state.listener_task.take(),
                    state.event_bus.take(),
                    state.reporting.take(),
                    preload_task,
                )
            };
            Self::abort_task(preload_task);
            if let Some(reporting) = reporting {
                reporting.abandon();
            }
            self.stop_task(task);
            self.stop_event_bus(bundle);
            if let Some(mirror) = &mirror {
                mirror.set_playback_active(false);
            }
            drop(mirror);
            drop(client);
        }

        self.remove_mirror_dir_if_safe(&removed.mirror_dir);
        Ok(removed_active)
    }

    /// Opens the mirror (starting sync automatically) against the currently
    /// signed-in client, replacing any mirror already open. Also spawns the
    /// real websocket `EventBus` and a forwarder relaying its events onto a
    /// fresh per-mirror [`BusBundle`] via [`Self::open_mirror_with_bus`] --
    /// both this and `install_authenticated_session` share the one bundle,
    /// so a first sign-in never runs with no live updates. The old bus, if
    /// any, is torn down via [`Self::stop_event_bus`] up front.
    ///
    /// No Android lifecycle hook is needed: the bus's capped-backoff
    /// supervisor reconnects on its own, and every reconnect emits
    /// `NeedsReconcile`, which `media_cache::sync` turns into a full heal.
    pub fn open_mirror(&self) -> Result<(), CoreError> {
        let client = self
            .lock_state()
            .client
            .clone()
            .ok_or(CoreError::NotSignedIn)?;

        let (old_mirror, old_task, old_bundle, listener) = {
            let mut state = self.lock_state();
            (
                state.mirror.take(),
                state.listener_task.take(),
                state.event_bus.take(),
                state.listener.clone(),
            )
        };
        self.stop_task(old_task);
        self.stop_event_bus(old_bundle);
        drop(old_mirror);

        // `state.mirror_dir` is set alongside `state.client` by every
        // install path; the `"mirror"` fallback is defensive only and
        // shouldn't be reachable in practice.
        let mirror_dir = self
            .lock_state()
            .mirror_dir
            .clone()
            .unwrap_or_else(|| "mirror".to_string());
        let dir = self.data_dir.join(mirror_dir);
        let (mirror, bundle) = self.open_mirror_with_bus(client, dir)?;

        // Settings survive sign-in/mirror cycles: push the current Next Up
        // filtering onto the freshly opened mirror rather than leaving it at
        // `media_cache`'s unfiltered default.
        let next_up_settings = self.lock_state().settings.clone();
        mirror.set_next_up_options(next_up_options_from(&next_up_settings));

        let new_task = listener.map(|l| self.spawn_listener_task(mirror.clone(), l));

        let mut state = self.lock_state();
        state.mirror = Some(mirror);
        state.listener_task = new_task;
        state.event_bus = Some(bundle);
        Ok(())
    }

    pub fn views(&self) -> Vec<ViewSnapshot> {
        self.require_mirror()
            .map(|m| m.views().into_iter().map(ViewSnapshot::from).collect())
            .unwrap_or_default()
    }

    /// The whole Home screen in one call: resume + next-up rails and one
    /// "Latest" shelf per view with at least one card, each holding up to
    /// `Settings::home_shelf_size` items (next-up never repeats a resume card),
    /// skipping `Settings::hidden_library_ids` and honoring
    /// `Settings::hide_watched_in_latest`. [`Self::views`] itself stays
    /// unfiltered; only this method's "Latest" shelves are filtered.
    /// Resume/next-up are not filtered by hidden library in this slice.
    pub fn home_snapshot(&self) -> HomeSnapshot {
        let Ok(mirror) = self.require_mirror() else {
            return HomeSnapshot {
                resume: Vec::new(),
                next_up: Vec::new(),
                latest: Vec::new(),
                favorites: Vec::new(),
            };
        };
        let settings = self.lock_state().settings.clone();

        let size = settings.home_shelf_size;
        let resume: Vec<Card> = mirror.resume(size).into_iter().map(Card::from).collect();
        // Over-fetch by the resume count so dropping repeats still fills the shelf.
        let next_up = next_up_beside_resume(
            mirror
                .next_up(size.saturating_add(u32::try_from(resume.len()).unwrap_or(u32::MAX)))
                .into_iter()
                .map(Card::from)
                .collect(),
            &resume,
            size as usize,
        );
        let favorites = if settings.home_show_favorites {
            mirror.favorites(size).into_iter().map(Card::from).collect()
        } else {
            Vec::new()
        };

        let mut latest = Vec::new();
        for view in mirror.views() {
            // A `Channel` view's content is never mirrored, so
            // `mirror.latest` would always come back empty -- skip the
            // wasted query rather than relying on `cards.is_empty()`.
            if view.item_type == "Channel" {
                continue;
            }
            let view_id = view.id;
            let view_name = view.name;
            if settings.hidden_library_ids.contains(&view_id) {
                continue;
            }
            let cards: Vec<Card> = mirror
                .latest(&view_id, size, settings.hide_watched_in_latest)
                .into_iter()
                .map(Card::from)
                .collect();
            if !cards.is_empty() {
                latest.push(LatestShelf {
                    view_id,
                    view_name,
                    cards,
                });
            }
        }

        HomeSnapshot {
            resume,
            next_up,
            latest,
            favorites,
        }
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
    pub fn children(
        &self,
        parent_id: String,
        sort: SortOrder,
        offset: u32,
        limit: u32,
    ) -> Vec<Card> {
        let Ok(mirror) = self.require_mirror() else {
            return Vec::new();
        };
        let show_virtual_episodes = self.lock_state().settings.show_virtual_episodes;
        let cards: Vec<Card> = mirror
            .children_checked(&parent_id, sort.into(), offset, limit)
            .unwrap_or_default()
            .into_iter()
            .map(Card::from)
            .collect();
        // This is a Series -> Seasons listing only when at least one
        // returned card is a Season -- every other listing shape skips the
        // extra query entirely.
        let cards = if cards.iter().any(|c| c.item_type == "Season") {
            let counts = mirror.season_episode_counts(&parent_id).unwrap_or_default();
            filter_all_virtual_seasons(cards, &counts, show_virtual_episodes)
        } else {
            cards
        };
        filter_virtual_episodes(cards, show_virtual_episodes)
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
    /// Unlike `children`/`search`, returns a `Result`:
    /// `Err(CoreError::MirrorNotOpen)` before `open_mirror` succeeds, while
    /// `Ok(None)` covers an id the mirror simply doesn't know.
    pub fn card_by_id(&self, item_id: String) -> Result<Option<Card>, CoreError> {
        let mirror = self.require_mirror()?;
        let canonical_item_id = canonicalize_item_id(&item_id);
        Ok(mirror.card_by_id(&canonical_item_id).map(Card::from))
    }

    pub fn item_count(&self) -> i64 {
        self.require_mirror().map(|m| m.item_count()).unwrap_or(0)
    }

    pub fn is_syncing(&self) -> bool {
        self.require_mirror()
            .map(|m| m.is_syncing())
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

    pub fn image_url(
        &self,
        item_id: String,
        kind: ImageKind,
        tag: String,
        max_width: u32,
    ) -> Result<String, CoreError> {
        let client = self
            .lock_state()
            .client
            .clone()
            .ok_or(CoreError::NotSignedIn)?;
        Ok(client.image_url(&item_id, kind.into(), &tag, max_width))
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
    pub fn trickplay_tile_url(
        &self,
        item_id: String,
        width: u32,
        image_index: u32,
    ) -> Option<String> {
        let client = self.lock_state().client.clone()?;
        Some(client.trickplay_tile_url(&item_id, width, image_index, None))
    }

    /// Fetches only the chapter/stream metadata used by the playback OSD,
    /// plus the primary media source's size/path for the stats sheet's
    /// CONTAINER/FILE rows (`docs/jellybeam-osd-handoff/handoff/JELLYBEAM-TV-OSD-SPEC.md`
    /// §8b). `MediaSources` is requested explicitly -- without it the server
    /// omits media sources and both fields come back `None` regardless.
    /// Deliberately narrow: full detail belongs to [`Self::get_item_detail`].
    pub fn get_playback_osd_detail(&self, item_id: String) -> Result<PlaybackOsdDetail, CoreError> {
        let client = self
            .lock_state()
            .client
            .clone()
            .ok_or(CoreError::NotSignedIn)?;

        let dto = self.runtime.block_on(async {
            let result = client
                .get_items(&jellyfin_api::ItemQuery {
                    ids: vec![item_id.clone()],
                    fields: vec![
                        "MediaStreams".to_string(),
                        "Chapters".to_string(),
                        "MediaSources".to_string(),
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

        Ok(PlaybackOsdDetail::from(&dto))
    }

    /// Fetches a single item's full detail record for the Detail page and
    /// playback library-info panel (docs/11-detail-ux-spec.md,
    /// docs/12-osd-ux-spec.md) -- fields the bulk mirror sync deliberately
    /// never asks for. A live per-visit fetch, never persisted to the mirror;
    /// `DateCreated`/`MediaSources`/`RecursiveItemCount`/`ChildCount` are
    /// requested explicitly or those [`ItemDetail`] fields come back `None`.
    ///
    /// Requires a signed-in client ([`CoreError::NotSignedIn`]), no open
    /// mirror. Errors ([`CoreError::Api`]) rather than degrading to a
    /// default, unlike [`Self::get_media_segments`]: worth surfacing.
    pub fn get_item_detail(&self, item_id: String) -> Result<ItemDetail, CoreError> {
        let client = self
            .lock_state()
            .client
            .clone()
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

    /// docs/11-detail-ux-spec.md tier 2 item 13, "Similar Titles":
    /// `GET /Items/{itemId}/Similar` mapped onto the same [`Card`] shape
    /// every browse surface uses. Requires a signed-in client; no mirror
    /// needed -- a live personalized set, not a library listing. A result
    /// item missing its own `Id` is silently dropped rather than failing the
    /// whole call.
    pub fn get_similar(&self, item_id: String, limit: u32) -> Result<Vec<Card>, CoreError> {
        let client = self
            .lock_state()
            .client
            .clone()
            .ok_or(CoreError::NotSignedIn)?;
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
        let client = self
            .lock_state()
            .client
            .clone()
            .ok_or(CoreError::NotSignedIn)?;
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
    pub fn get_media_segments(&self, item_id: String) -> Vec<MediaSegment> {
        let Some(client) = self.lock_state().client.clone() else {
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

    /// Trickplay scrub-preview manifest, fetched separately from
    /// [`Self::prepare_playback`] since it's only consumed on a seek.
    /// Kotlin's `PlaybackViewModel.start()` calls this fire-and-forget right
    /// after `load()`.
    ///
    /// Fails open to `None`, same non-fatal contract as
    /// [`Self::get_media_segments`]: not signed in, an older server, a
    /// transport hiccup, or genuinely no manifest all look identical here.
    pub fn get_trickplay(
        &self,
        item_id: String,
        media_source_id: String,
    ) -> Option<TrickplayMetaFfi> {
        let client = self.lock_state().client.clone()?;
        let trickplay_by_source = self
            .runtime
            .block_on(client.get_items(&jellyfin_api::ItemQuery {
                ids: vec![item_id],
                fields: vec!["Trickplay".to_string()],
                limit: 1,
                ..jellyfin_api::ItemQuery::default()
            }))
            .ok()?
            .items
            .into_iter()
            .next()?
            .trickplay;
        playback_policy::trickplay::resolve_trickplay_meta(&trickplay_by_source, &media_source_id)
            .map(TrickplayMetaFfi::from)
    }

    /// Registers (or replaces) the change listener. If a mirror is already
    /// open, starts forwarding immediately; otherwise remembered and bound
    /// on the next successful [`Self::open_mirror`]. Replacing aborts the
    /// prior forwarding task first.
    pub fn set_change_listener(&self, listener: Arc<dyn ChangeListener>) {
        let mirror = self.require_mirror().ok();
        let old_task = {
            let mut state = self.lock_state();
            state.listener = Some(listener.clone());
            state.listener_task.take()
        };
        self.stop_task(old_task);

        let new_task = mirror.map(|m| self.spawn_listener_task(m, listener));
        self.lock_state().listener_task = new_task;
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
            Self::invalidate_preload_locked(&mut state)
        };
        Self::abort_task(preload_task);
    }

    /// Returns the current settings record -- loaded at construction (or
    /// [`Settings::default`]) and kept current by [`Self::set_settings`].
    /// Whole-record per `docs/09-settings-plan.md`; no per-field getter.
    pub fn get_settings(&self) -> Settings {
        self.lock_state().settings.clone()
    }

    /// Persists `settings`, stores it as current in-memory state, and
    /// applies the side effects entirely `JellybeamCore`'s own to apply:
    /// `next_up_cutoff_days`/`next_up_rewatching` map onto
    /// `media_cache::NextUpOptions` and push to the open mirror if any
    /// (else deferred to the next `open_mirror`).
    /// `hidden_library_ids`/`hide_watched_in_latest`/`home_shelf_size` need no push --
    /// [`Self::home_snapshot`] reads settings directly. Every other field is
    /// Kotlin-consumed state with no Rust-side effect yet (docs/09 steps 2-3).
    ///
    /// Whole-record, no per-field setter. A disk write failure is logged and
    /// swallowed: in-memory state and the mirror side effect still apply
    /// even if persistence failed.
    pub fn set_settings(&self, new_settings: Settings) {
        if let Err(e) = settings::save(&self.data_dir, &new_settings) {
            tracing::warn!(error = %e, "failed to persist settings to disk");
        }

        let (mirror, preload_task, next_up_options) = {
            let mut state = self.lock_state();
            let next_up_options = changed_next_up_options(&state.settings, &new_settings);
            state.settings = new_settings.clone();
            let preload_task = Self::invalidate_preload_locked(&mut state);
            (state.mirror.clone(), preload_task, next_up_options)
        };
        Self::abort_task(preload_task);

        if let (Some(mirror), Some(options)) = (mirror, next_up_options) {
            mirror.set_next_up_options(options);
            // Re-query Next Up immediately so a cutoff/rewatching change is
            // visible on the next home_snapshot, firing a MirrorChange the
            // UI's change listener already reacts to.
            self.runtime.block_on(mirror.refresh_next_up());
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
    /// trip; a same-item preload still negotiating is joined instead of
    /// aborted (§2), so Play reuses it rather than starting over. A preload
    /// for any other item is stale the moment a real Play arrives, and is
    /// aborted as before.
    pub fn prepare_playback(
        &self,
        item_id: String,
        start_from_beginning: bool,
    ) -> Result<PlaybackPlan, CoreError> {
        let (
            client,
            mirror,
            caps,
            tolerate_mislabeled_levels,
            quality,
            cached,
            joinable_task,
            stale_task,
            attempt,
        ) = {
            let mut state = self.lock_state();
            let client = state.client.clone().ok_or(CoreError::NotSignedIn)?;
            let mirror = state.mirror.clone().ok_or(CoreError::MirrorNotOpen)?;
            let caps = state.caps.clone();
            let tolerate_mislabeled_levels = state.settings.tolerate_mislabeled_levels;
            let quality = state.settings.playback_quality;
            // docs/18-playback-quality.md §2: `playback_generation` is
            // captured before the network negotiation (state lock released)
            // so the install step can detect a newer session having already
            // taken over `state.reporting` meanwhile.
            let attempt = state.playback_generation;

            // Single-use regardless of match: a slot for a different item is
            // stale the moment a real call arrives for THIS item -- no
            // reason to keep it for a hypothetical future call.
            let cached = state
                .preload_cache
                .take()
                .filter(|c| c.is_fresh_for(state.preload_generation, &item_id, Instant::now()));

            // A preload already negotiating THIS item is joined below rather
            // than aborted, so Play reuses its in-flight handshake instead
            // of renegotiating from zero. Anything else (a different item,
            // or nothing in flight) is stale the instant a real call
            // arrives, exactly as before.
            let same_item = cached.is_none()
                && state.preload_target_item_id.as_deref() == Some(item_id.as_str());
            let joinable_task = if same_item {
                state.preload_task.take()
            } else {
                None
            };
            let stale_task = if same_item {
                None
            } else {
                let task = state.preload_task.take();
                if task.is_some() {
                    state.preload_generation = state.preload_generation.wrapping_add(1);
                }
                task
            };
            state.preload_target_item_id = None;
            state.preload_pending = None;
            (
                client,
                mirror,
                caps,
                tolerate_mislabeled_levels,
                quality,
                cached,
                joinable_task,
                stale_task,
                attempt,
            )
        };
        // Abort is deliberately not awaited: Play must enter its own
        // critical path immediately. The generation gate already prevents
        // the cancelled task from publishing even mid-poll.
        Self::abort_task(stale_task);

        // Same-item join: block on the negotiation already talking to the
        // server, then consume whatever it published -- the same single-use
        // read a fresh cache hit would do, just delayed instead of skipped.
        // A miss (the target changed underneath it, or it resolved to a
        // Transcode plan, which is never cached) falls through to a normal
        // negotiation below exactly like an expired cache entry would.
        let cached = if let Some(task) = joinable_task {
            let _ = self.runtime.block_on(task);
            let mut state = self.lock_state();
            state
                .preload_cache
                .take()
                .filter(|c| c.is_fresh_for(state.preload_generation, &item_id, Instant::now()))
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
        let start_ticks = resolved_start_ticks(
            start_from_beginning,
            dto.user_data
                .as_ref()
                .and_then(|u| u.playback_position_ticks),
        );

        let profile =
            build_android_profile_for_quality(&caps, tolerate_mislabeled_levels, &quality);

        // Outro timing and trickplay never touch this critical path: Kotlin
        // derives Outro start from its own fire-and-forgotten
        // `get_media_segments` call, and fetches trickplay separately via
        // [`Self::get_trickplay`]. `get_playback_info` + `decide_playback`
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
        // Started fresh every call, cache hit or not -- a preload never
        // starts a reporting session. `ReportingSession::start` spawns its
        // background actor via `tokio::spawn` internally, needing a runtime
        // context; a cache hit reaches here without entering an async block,
        // so `self.runtime.enter()` provides that context explicitly.
        let _runtime_guard = self.runtime.enter();
        let session = jellyfin_core::ReportingSession::start(client.clone(), ctx);
        drop(_runtime_guard);
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
        };

        self.install_prepared_session(attempt, session)?;
        mirror.set_playback_active(true); // see `Mirror::set_playback_active`'s own doc comment

        Ok(plan)
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
    /// `url` is position-dependent, never reusable), or a fresh cache entry
    /// for this `item_id` already exists.
    ///
    /// Only replaces a background task and returns; never waits on the
    /// server. Exactly one speculative worker is retained; newer focus
    /// targets replace the pending slot rather than racing requests. A real
    /// `prepare_playback` aborts any unfinished speculation first, and each
    /// request's generation is checked before publishing, so a cancellation,
    /// account swap, or profile change can never be undone by a late response.
    pub fn preload_playback(&self, item_id: String) {
        let mut state = self.lock_state();
        if !state.settings.preload_on_focus {
            return;
        }
        let Some(client) = state.client.clone() else {
            return;
        };
        let Some(mirror) = state.mirror.clone() else {
            return;
        };
        if matches!(state.settings.playback_quality, PlaybackQuality::Cap { .. }) {
            return;
        }
        if state.preload_cache.as_ref().is_some_and(|cache| {
            cache.is_fresh_for(state.preload_generation, &item_id, Instant::now())
        }) {
            return;
        }
        if state
            .preload_task
            .as_ref()
            .is_some_and(|task| !task.is_finished())
            && state.preload_target_item_id.as_deref() == Some(item_id.as_str())
        {
            return;
        }

        state.preload_generation = state.preload_generation.wrapping_add(1);
        let generation = state.preload_generation;
        state.preload_cache = None;
        state.preload_target_item_id = Some(item_id.clone());
        let request = PreloadRequest {
            generation,
            item_id,
            client,
            mirror,
            caps: state.caps.clone(),
            tolerate_mislabeled_levels: state.settings.tolerate_mislabeled_levels,
            quality: state.settings.playback_quality,
        };

        if state
            .preload_task
            .as_ref()
            .is_some_and(|task| !task.is_finished())
        {
            state.preload_pending = Some(request);
            return;
        }

        // A retained finished handle means the worker panicked before
        // cleanup; drop it and recover with a fresh worker.
        Self::abort_task(state.preload_task.take());
        state.preload_pending = None;
        let shared_state = Arc::clone(&self.state);
        let task = self
            .runtime
            .spawn(Self::run_preload_worker(shared_state, request));
        state.preload_task = Some(task);
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
    /// A no-op (`tracing::warn!`) when no `prepare_playback` call has
    /// succeeded since the last stop/abandon -- never an error.
    pub fn report_position(&self, ticks: i64) {
        let checkpoint = {
            let mut state = self.lock_state();
            let item_id = match state.reporting.as_mut() {
                Some(session) => {
                    session.on_position(ticks);
                    session.context().item_id.clone()
                }
                None => {
                    tracing::warn!("report_position called with no active reporting session");
                    return;
                }
            };

            if state
                .local_progress_checkpoint
                .should_commit(ticks, Instant::now())
            {
                state.mirror.clone().map(|mirror| {
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

    /// Forwards a pause/resume edge to the active reporting session, if any.
    /// See [`Self::report_position`] for the no-active-session contract.
    pub fn report_paused(&self, paused: bool) {
        match self.lock_state().reporting.as_mut() {
            Some(session) => session.on_pause(paused),
            None => tracing::warn!("report_paused called with no active reporting session"),
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
        let (session, mirror) = {
            let mut state = self.lock_state();
            let is_current = state
                .reporting
                .as_ref()
                .is_some_and(|active| active.context().play_session_id == play_session_id);
            if !is_current {
                tracing::debug!(
                    play_session_id = %play_session_id,
                    "stop_playback: play_session_id no longer names the active reporting \
                     session -- a lost race is expected here, not a caller bug; ignoring"
                );
                return;
            }
            state.local_progress_checkpoint.reset();
            let session = state
                .reporting
                .take()
                .expect("is_current just proved this is Some");
            // See `playback_generation`'s own doc comment: taking the
            // active session here counts as it "changing hands" (to
            // nothing), same as a fresh install.
            state.playback_generation = state.playback_generation.wrapping_add(1);
            (session, state.mirror.clone())
        };

        if let Some(mirror) = &mirror {
            mirror.set_playback_active(false);
        }
        self.stop_session_and_sync_mirror(session, position_ticks, mirror);
    }

    /// Auto/Cap-mode local-failure transcode fallback
    /// (docs/18-playback-quality.md §2): called after Media3 proves the
    /// current `DirectPlay` plan genuinely can't play. `reason` is that local
    /// failure's text, carried onto `transcode_reason`. `DirectPlay` mode
    /// refuses outright with [`CoreError::WouldTranscode`], touching neither
    /// network nor session.
    ///
    /// Otherwise: ends the session (via
    /// [`Self::stop_session_and_sync_mirror`]), re-negotiates with
    /// `force_transcode: true` and `StartTimeTicks = position_ticks`,
    /// accepted via [`transcode_url_from_decision`] (`None` ->
    /// `NoPlayableSource`). One fallback per item; a transcode that then
    /// fails is a plain fatal error.
    ///
    /// `play_session_id` fixes a race: this negotiates with the state lock
    /// released, so a concurrent [`Self::prepare_playback`] for a different
    /// item could install its own session first. Guarded twice -- up front (a
    /// mismatch returns [`CoreError::StalePlaybackSession`] untouched) and
    /// again via [`State::playback_generation`] after negotiation (a
    /// mismatch abandons the freshly negotiated session). Kotlin ignores
    /// that error: playback already moved on.
    #[allow(clippy::too_many_lines)]
    pub fn prepare_transcode_fallback(
        &self,
        item_id: String,
        position_ticks: i64,
        reason: String,
        play_session_id: String,
    ) -> Result<PlaybackPlan, CoreError> {
        let (client, mirror, caps, tolerate_mislabeled_levels, quality, session, generation) = {
            let mut state = self.lock_state();
            if state.settings.playback_quality == PlaybackQuality::DirectPlay {
                return Err(CoreError::WouldTranscode { reasons: reason });
            }
            let client = state.client.clone().ok_or(CoreError::NotSignedIn)?;
            let mirror = state.mirror.clone().ok_or(CoreError::MirrorNotOpen)?;
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
            state.local_progress_checkpoint.reset();
            let session = state
                .reporting
                .take()
                .expect("is_still_current just proved this is Some");
            let generation = state.playback_generation;
            (
                client,
                mirror,
                caps,
                tolerate_mislabeled_levels,
                quality,
                session,
                generation,
            )
        };

        mirror.set_playback_active(false); // resumed once the new session installs
        self.stop_session_and_sync_mirror(session, position_ticks, Some(mirror.clone()));

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

        let profile =
            build_android_profile_for_quality(&caps, tolerate_mislabeled_levels, &quality);

        let (play_session_id, decision) = self.negotiate_playback_info(
            &client,
            &item_id,
            &profile,
            Some(position_ticks),
            jellyfin_api::PlaybackInfoOptions {
                force_transcode: true,
            },
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
        let _runtime_guard = self.runtime.enter();
        let new_session = jellyfin_core::ReportingSession::start(client.clone(), ctx);
        drop(_runtime_guard);

        // Re-check ownership after the network round trip: a mismatch means
        // a newer prepare/stop/abandon call already moved `state.reporting`
        // on, so the just-negotiated session is discarded.
        let previous = {
            let mut state = self.lock_state();
            if state.playback_generation != generation {
                new_session.abandon();
                return Err(CoreError::StalePlaybackSession);
            }
            let previous = Self::install_reporting_session(&mut state, new_session);
            state.local_progress_checkpoint.reset();
            previous
        };
        if let Some(previous) = previous {
            previous.abandon();
        }
        mirror.set_playback_active(true);

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
        };

        Ok(plan)
    }

    /// Discards the active reporting session, *if it is still the one named
    /// by `play_session_id`* (see [`Self::stop_playback`]), without sending
    /// a final Stopped report or touching the mirror -- for error paths
    /// where there is no real position worth persisting. See
    /// [`jellyfin_core::ReportingSession::abandon`] for what that trades
    /// away. A mismatch is a lost race, logged at `debug`, and returns
    /// without touching state.
    pub fn abandon_playback(&self, play_session_id: String) {
        let session = {
            let mut state = self.lock_state();
            let is_current = state
                .reporting
                .as_ref()
                .is_some_and(|active| active.context().play_session_id == play_session_id);
            if !is_current {
                tracing::debug!(
                    play_session_id = %play_session_id,
                    "abandon_playback: play_session_id no longer names the active reporting \
                     session -- a lost race is expected here, not a caller bug; ignoring"
                );
                return;
            }
            state.local_progress_checkpoint.reset();
            let session = state
                .reporting
                .take()
                .expect("is_current just proved this is Some");
            // See `playback_generation`'s own doc comment.
            state.playback_generation = state.playback_generation.wrapping_add(1);
            session
        };
        session.abandon();
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

    /// OSD v2's `PREVIOUS` transport button
    /// (`docs/jellybeam-osd-handoff/handoff/JELLYBEAM-TV-OSD-SPEC.md` §6): the
    /// previous episode within its series, skipping virtual entries,
    /// crossing backward into the previous season. `None` under the mirror
    /// [`Self::next_episode_after`] contract, substituting "first" for
    /// "last". See [`crate::next_episode::previous_episode_before`].
    pub fn previous_episode_before(&self, item_id: String) -> Option<Card> {
        let mirror = self.require_mirror().ok()?;
        crate::next_episode::previous_episode_before(&mirror, &item_id)
    }

    /// Resolves both episode-edge transport targets in one call. The local
    /// mirror is always tried first; a live series query is used only when
    /// the mirror cannot place the current item at all, restoring controls
    /// for externally launched/fresh items without routine network load.
    pub fn episode_neighbors(
        &self,
        item_id: String,
        series_id: String,
    ) -> Option<EpisodeNeighbors> {
        // Intent/deep-link ids commonly arrive as 32 compact hex digits,
        // while Jellyfin DTO UUIDs stringify with hyphens. Use one canonical
        // spelling for mirror keys/server params/response match, or a
        // successful adjacent-episode response is discarded.
        let canonical_item_id = canonicalize_item_id(&item_id);
        if let Ok(mirror) = self.require_mirror() {
            if let Some(neighbors) =
                crate::next_episode::episode_neighbors(&mirror, &canonical_item_id)
            {
                return Some(neighbors);
            }
        }

        let client = self.lock_state().client.clone()?;
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
    /// season-tab switch); empty if the mirror isn't open, the query fails,
    /// or the series genuinely has no episodes.
    pub fn series_episodes(&self, series_id: String) -> Vec<Card> {
        let Ok(mirror) = self.require_mirror() else {
            return Vec::new();
        };
        crate::next_episode::all_episodes_of_series(&mirror, &series_id)
            .unwrap_or_default()
            .into_iter()
            .map(Card::from)
            .collect()
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

    /// Persists one half (audio or subtitle -- `kind: TrackKindFfi::Video`
    /// is a documented no-op) of `series_id`'s remembered track choice,
    /// immediately updating the in-memory copy [`Self::resolve_tracks`]
    /// reads. `track_key: None` clears that half rather than leaving a stale
    /// key. `track_key` must already be the result of
    /// [`crate::track_pref_key_of`]. A disk write failure is logged and
    /// swallowed, same contract as [`Self::set_settings`].
    pub fn remember_track_choice(
        &self,
        series_id: String,
        kind: TrackKindFfi,
        track_key: Option<String>,
    ) {
        let (revision, snapshot) = {
            let mut state = self.lock_state();
            let entry = state.track_prefs.entry(series_id).or_default();
            match kind {
                TrackKindFfi::Audio => entry.audio = track_key,
                TrackKindFfi::Subtitle => entry.subtitle = track_key,
                TrackKindFfi::Video => {
                    tracing::warn!(
                        "remember_track_choice called with TrackKindFfi::Video; no-op -- \
                         there is no per-series video-track preference"
                    );
                }
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
        let client = self
            .lock_state()
            .client
            .clone()
            .ok_or(CoreError::NotSignedIn)?;
        let mirror = self.require_mirror()?;

        let data = self.runtime.block_on(async {
            if played {
                client.mark_played(&item_id).await
            } else {
                client.mark_unplayed(&item_id).await
            }
        })?;
        self.runtime
            .block_on(mirror.apply_user_data(vec![(item_id.clone(), data)]));

        let refresh_ids = episode_scope_ids(&mirror, &item_id);
        if !refresh_ids.is_empty() {
            let mirror = mirror.clone();
            self.runtime.spawn(async move {
                mirror.refresh_items(refresh_ids).await;
            });
        }

        Ok(())
    }

    /// Bulk mark for a Series or Season scope (docs/19 §1.1). Marks the
    /// scope on the server first, then applies the mark *locally*
    /// (optimistic) to every non-virtual episode in scope. Returns once
    /// those local applies are enqueued; a background task then waits for
    /// them to commit and re-fetches the scope plus its season(s)/series for
    /// authoritative counts.
    pub fn set_played_recursive(&self, scope_id: String, played: bool) -> Result<(), CoreError> {
        let client = self
            .lock_state()
            .client
            .clone()
            .ok_or(CoreError::NotSignedIn)?;
        let mirror = self.require_mirror()?;

        self.runtime.block_on(async {
            if played {
                client.mark_played(&scope_id).await
            } else {
                client.mark_unplayed(&scope_id).await
            }
        })?;

        let scope_item = mirror.item(&scope_id);
        let is_series = scope_item
            .as_ref()
            .and_then(|dto| dto.type_)
            .map(|t| t.to_string())
            .as_deref()
            == Some("Series");

        let episodes: Vec<media_cache::CardRow> = if is_series {
            crate::next_episode::all_episodes_of_series(&mirror, &scope_id).unwrap_or_default()
        } else {
            mirror
                .children_checked(&scope_id, media_cache::Sort::IndexNumber, 0, u32::MAX)
                .unwrap_or_default()
        };
        let episode_ids: Vec<String> = episodes
            .into_iter()
            .filter(|row| !row.is_virtual)
            .map(|row| row.id)
            .collect();

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
        let client = self
            .lock_state()
            .client
            .clone()
            .ok_or(CoreError::NotSignedIn)?;
        let mirror = self.require_mirror()?;

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
        if let Some((fetched_at, cached)) = self.lock_state().collections.clone() {
            if fetched_at.elapsed() < COLLECTIONS_CACHE_TTL {
                return Ok(cached);
            }
        }

        let client = self
            .lock_state()
            .client
            .clone()
            .ok_or(CoreError::NotSignedIn)?;
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

        self.lock_state().collections = Some((Instant::now(), collections.clone()));
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
        let client = self
            .lock_state()
            .client
            .clone()
            .ok_or(CoreError::NotSignedIn)?;
        self.runtime
            .block_on(client.add_to_collection(&collection_id, &item_id))?;

        if let Some(mirror) = self.lock_state().mirror.clone() {
            self.runtime.spawn(async move {
                mirror.refresh_collection_membership(&collection_id).await;
            });
        }
        Ok(())
    }

    /// docs/19 §1.3/§2.3: which collections `item_id` is currently a member
    /// of, per the local mirror's `collection_members` table. Empty when the
    /// mirror isn't open, same convention as [`Self::series_episodes`].
    pub fn collection_ids_containing(&self, item_id: String) -> Vec<String> {
        let Ok(mirror) = self.require_mirror() else {
            return Vec::new();
        };
        mirror.collection_ids_containing(&item_id)
    }

    /// Fire-and-forget metadata refresh (docs/19 §1.4); the mirror
    /// reconciles when `LibraryChanged` arrives.
    pub fn refresh_metadata(&self, item_id: String) -> Result<(), CoreError> {
        let client = self
            .lock_state()
            .client
            .clone()
            .ok_or(CoreError::NotSignedIn)?;
        self.runtime.block_on(client.refresh_item(&item_id))?;
        Ok(())
    }

    /// One authenticated round trip (`GET /Users/Me`). Browse reads the mirror, so without this
    /// a revoked token goes unnoticed until the first Play; the app calls it at launch, account
    /// switch and return to foreground, and routes `Unauthorized` to re-authorization.
    pub fn validate_session(&self) -> Result<(), CoreError> {
        let client = self
            .lock_state()
            .client
            .clone()
            .ok_or(CoreError::NotSignedIn)?;
        self.runtime.block_on(client.current_user())?;
        Ok(())
    }

    /// Whether the signed-in user is a server administrator (docs/19 §1.1)
    /// -- cached per session, never re-fetched once known (unlike
    /// [`Self::list_collections`]'s ten-minute TTL).
    pub fn is_administrator(&self) -> Result<bool, CoreError> {
        if let Some(cached) = self.lock_state().is_admin {
            return Ok(cached);
        }
        let client = self
            .lock_state()
            .client
            .clone()
            .ok_or(CoreError::NotSignedIn)?;
        let user = self.runtime.block_on(client.current_user())?;
        let is_admin = user
            .policy
            .and_then(|policy| policy.is_administrator)
            .unwrap_or(false);
        self.lock_state().is_admin = Some(is_admin);
        Ok(is_admin)
    }
}

/// [`JellybeamCore::open_mirror_with_bus`]'s return shape.
type MirrorWithBus = (media_cache::Mirror, BusBundle);

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
    /// `pub(crate)` so `seerr.rs`'s sibling `impl JellybeamCore` block can
    /// read/write `State::seerr` the same way every method in this file does.
    pub(crate) fn lock_state(&self) -> std::sync::MutexGuard<'_, State> {
        self.state.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// `pub(crate)` accessor for `seerr.rs`'s config-store path; see
    /// [`Self::lock_state`].
    pub(crate) fn data_dir(&self) -> &std::path::Path {
        &self.data_dir
    }

    /// `pub(crate)` accessor for `seerr.rs`'s `block_on` calls.
    pub(crate) fn runtime(&self) -> &tokio::runtime::Runtime {
        &self.runtime
    }

    pub(crate) fn seerr_config(&self) -> &crate::seerr::SeerrConfigStore {
        &self.seerr_config
    }

    fn invalidate_preload_locked(state: &mut State) -> Option<tokio::task::JoinHandle<()>> {
        state.preload_generation = state.preload_generation.wrapping_add(1);
        state.preload_cache = None;
        state.preload_target_item_id = None;
        state.preload_pending = None;
        state.preload_task.take()
    }

    /// Clears every per-account cache (`seerr`/`collections`/`is_admin`) and
    /// invalidates any in-flight preload -- shared by every path that signs
    /// in, switches, or removes an account, so none of them can leave a
    /// previous account's cache readable under the new one.
    fn reset_account_state_locked(state: &mut State) -> Option<tokio::task::JoinHandle<()>> {
        state.seerr = None;
        state.collections = None;
        state.is_admin = None;
        Self::invalidate_preload_locked(state)
    }

    fn abort_task(task: Option<tokio::task::JoinHandle<()>>) {
        if let Some(task) = task {
            task.abort();
        }
    }

    /// Drains one active request followed by at most the latest replacement
    /// target; a focus change can replace `preload_pending` again but never
    /// creates a second network worker.
    async fn run_preload_worker(shared_state: Arc<Mutex<State>>, mut request: PreloadRequest) {
        loop {
            let generation = request.generation;
            let cache = Self::build_preload_cache(&request).await;
            let mut state = shared_state.lock().unwrap_or_else(|e| e.into_inner());
            if state.preload_generation == generation {
                state.preload_task = None;
                state.preload_target_item_id = None;
                state.preload_pending = None;
                state.preload_cache = cache;
                return;
            }

            // A bumped generation with nothing pending means a newer caller already owns
            // preload_task and its target (a worker spawned after a same-item join); leave them.
            let Some(next) = state.preload_pending.take() else {
                return;
            };
            request = next;
            drop(state);
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

        let start_ticks = resolved_start_ticks(
            false,
            dto.user_data
                .as_ref()
                .and_then(|u| u.playback_position_ticks),
        );
        let profile = build_android_profile_for_quality(
            &request.caps,
            request.tolerate_mislabeled_levels,
            &request.quality,
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

    /// Installs `session` as the active reporting session and bumps
    /// `playback_generation` in the same locked step, so the two can never
    /// drift. Returns whatever session was previously active; the caller
    /// abandons it.
    fn install_reporting_session(
        state: &mut State,
        session: jellyfin_core::ReportingSession,
    ) -> Option<jellyfin_core::ReportingSession> {
        state.playback_generation = state.playback_generation.wrapping_add(1);
        state.reporting.replace(session)
    }

    /// docs/18-playback-quality.md §2: guards
    /// [`Self::prepare_playback`]'s install step against a session that
    /// finished negotiation late, after a newer call already took over
    /// `State::reporting`. `attempt` is the generation captured before
    /// releasing the lock; if it's no longer current, `session` is
    /// `abandon()`-ed and this returns
    /// [`CoreError::StalePlaybackSession`] without touching state. On a
    /// match, installs via [`Self::install_reporting_session`] and resets
    /// `local_progress_checkpoint`.
    ///
    /// Factored out so it can be unit-tested directly with a generation
    /// bumped in between, without reproducing real network interleaving.
    fn install_prepared_session(
        &self,
        attempt: u64,
        session: jellyfin_core::ReportingSession,
    ) -> Result<(), CoreError> {
        let previous = {
            let mut state = self.lock_state();
            if state.playback_generation != attempt {
                drop(state);
                session.abandon();
                return Err(CoreError::StalePlaybackSession);
            }
            state.local_progress_checkpoint.reset();
            Self::install_reporting_session(&mut state, session)
        };
        if let Some(previous) = previous {
            previous.abandon();
        }
        Ok(())
    }

    /// Shared by [`Self::stop_playback`] and
    /// [`Self::prepare_transcode_fallback`] (docs/18-playback-quality.md
    /// §2): sends the session's final Stopped report, then applies the same
    /// watch-state update locally to `mirror` so Home shelves update
    /// instantly rather than waiting on an unreliable `UserDataChanged` event.
    ///
    /// `session.stop(..)`'s final report is fired via `spawn`, not
    /// `block_on`'d: Kotlin no longer waits for that HTTP round trip before
    /// starting the next item's negotiation. The mirror write below is still
    /// `block_on`'d: a same-item resume must read back the position this
    /// call is committing.
    fn stop_session_and_sync_mirror(
        &self,
        session: jellyfin_core::ReportingSession,
        position_ticks: i64,
        mirror: Option<media_cache::Mirror>,
    ) {
        let item_id = session.context().item_id.clone();
        // The server recounts an episode's season/series only once the
        // Stopped report lands, and its `UserDataChanged` event omits the
        // series, so the scope is re-fetched after that report, never before.
        let scope_ids = mirror
            .as_ref()
            .map(|mirror| episode_scope_ids(mirror, &item_id))
            .unwrap_or_default();
        let stop = session.stop(position_ticks);
        let scope_mirror = mirror.clone();
        self.runtime.spawn(async move {
            stop.await;
            if let Some(mirror) = scope_mirror.filter(|_| !scope_ids.is_empty()) {
                mirror.refresh_items(scope_ids).await;
            }
        });

        if let Some(mirror) = mirror {
            let _write_guard = self
                .playback_write
                .lock()
                .unwrap_or_else(|e| e.into_inner());
            let committed = self.runtime.block_on(mirror.apply_local_user_data_and_wait(
                &item_id,
                position_ticks,
                None,
            ));
            if !committed {
                tracing::error!(item_id, "final playback state did not commit to the mirror");
            }
        }
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
    /// [`resolve_plan`].
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
                let (play_session_id, decision) = self.negotiate_playback_info(
                    client,
                    item_id,
                    profile,
                    start_ticks,
                    jellyfin_api::PlaybackInfoOptions::default(),
                )?;
                let video_codec = video_stream_codec(decision_source(&decision));
                let supported_codecs = jellyfin_core::android_direct_play_video_codecs(caps);
                match resolve_plan(
                    &quality,
                    decision,
                    video_codec.as_deref(),
                    &supported_codecs,
                    tolerate_mislabeled_levels,
                ) {
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

        // Authentication is a network round trip. Re-read after it finishes
        // and refuse to resurrect an account removed while in flight;
        // locate by stable identity, not the original index.
        let still_saved = session::load_list(&self.data_dir)
            .sessions
            .iter()
            .any(|saved| saved.server_url == target.server_url && saved.user_id == target.user_id);
        if !still_saved {
            return Err(CoreError::InvalidSessionIndex {
                index: original_index,
            });
        }

        self.install_authenticated_session(target.server_url, client, auth)
    }

    /// Shared commit path for password and Quick Connect authentication. A
    /// candidate mirror is opened first; only after that succeeds is the
    /// session list written and the live account swapped, so a mirror-open
    /// failure can't strand a previously working account.
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
        let token = auth.access_token.clone().ok_or_else(|| CoreError::Api {
            detail: "sign-in response did not include an access token".to_string(),
        })?;
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
            // Not known yet -- `spawn_server_version_refresh` (called after
            // this session is installed) fetches and persists it.
            // `SessionList::add_or_update` carries over whatever version was
            // already on record so a re-authentication never wipes a known
            // value back to `None`. Same reasoning for `server_name`.
            server_version: None,
            server_name: None,
        };
        // The directory `add_or_update` will settle on (carried over on a
        // re-authentication, `to_persist`'s hash on a first sign-in), read
        // under `session_lock` but opened outside it: the open is SQLite I/O
        // plus migration, and the lock is for list read-modify-write only.
        // The write below re-checks the directory it lands on.
        let mirror_dir = {
            let _guard = self.session_lock.lock().unwrap_or_else(|e| e.into_inner());
            session::load_list(&self.data_dir)
                .sessions
                .iter()
                .find(|s| s.server_url == to_persist.server_url && s.user_id == to_persist.user_id)
                .map_or_else(|| to_persist.mirror_dir.clone(), |s| s.mirror_dir.clone())
        };

        // Goes through the same mirror+bus bundle `open_mirror` uses rather
        // than opening a bare `media_cache::Mirror` with no bus -- otherwise
        // a first sign-in that never separately calls `open_mirror` would
        // run with no live updates. `candidate_bundle` and whatever bundle
        // account A still has installed are both alive at once below, until
        // the old one is retired -- harmless since each bundle carries its
        // own channel. Opened before the session list is written, so a
        // mirror-open failure can't strand a previously working account.
        let (candidate_mirror, candidate_bundle) =
            self.open_mirror_with_bus(client.clone(), self.data_dir.join(&mirror_dir))?;
        let next_up_settings = self.lock_state().settings.clone();
        candidate_mirror.set_next_up_options(next_up_options_from(&next_up_settings));

        let persisted = {
            let _guard = self.session_lock.lock().unwrap_or_else(|e| e.into_inner());
            let mut sessions = session::load_list(&self.data_dir);
            sessions.add_or_update(to_persist);
            let persisted = sessions
                .active_session()
                .cloned()
                .expect("add_or_update installs the new active session");
            // A concurrent remove/re-add of this account between the peek and
            // here would leave the opened mirror on a directory the list no
            // longer names; refuse rather than install a mismatched pair.
            if persisted.mirror_dir != mirror_dir {
                return Err(CoreError::Cache {
                    detail: "session list changed during sign-in; retry".to_string(),
                });
            }
            session::save_list(&self.data_dir, &sessions).map_err(|e| CoreError::Cache {
                detail: format!("failed to persist session: {e}"),
            })?;
            persisted
        };

        let (old_mirror, old_task, old_bundle, old_reporting, listener, preload_task) = {
            let mut state = self.lock_state();
            state.client = Some(client);
            state.mirror_dir = Some(persisted.mirror_dir.clone());
            // docs/13 Server compatibility -- seeded from
            // whatever `persisted` ended up with;
            // `spawn_server_version_refresh` below fetches the current
            // value in the background.
            state.server_version = persisted
                .server_version
                .as_deref()
                .and_then(|v| v.parse().ok());
            state.server_name = persisted.server_name.clone();
            state.local_progress_checkpoint.reset();
            let preload_task = Self::reset_account_state_locked(&mut state);
            let old_mirror = state.mirror.replace(candidate_mirror.clone());
            let old_task = state.listener_task.take();
            let old_bundle = state.event_bus.replace(candidate_bundle);
            let old_reporting = state.reporting.take();
            (
                old_mirror,
                old_task,
                old_bundle,
                old_reporting,
                state.listener.clone(),
                preload_task,
            )
        };
        Self::abort_task(preload_task);
        if let Some(reporting) = old_reporting {
            reporting.abandon();
        }
        self.stop_task(old_task);
        // Retires whatever bus/forwarder a previous session on this same
        // `JellybeamCore` had running, so a re-entry never leaves a prior
        // reconnect loop running alongside the new one.
        self.stop_event_bus(old_bundle);
        if let Some(old_mirror) = &old_mirror {
            old_mirror.set_playback_active(false);
        }
        drop(old_mirror);
        if let Some(listener) = listener {
            let task = self.spawn_listener_task(candidate_mirror, listener);
            self.lock_state().listener_task = Some(task);
        }
        self.spawn_server_version_refresh();

        Ok(AccountInfo {
            server_url,
            user_id,
            user_name,
        })
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
            .mirror
            .clone()
            .ok_or(CoreError::MirrorNotOpen)
    }

    /// Shared mirror-open-plus-bus bundle behind both [`Self::open_mirror`]
    /// and [`Self::install_authenticated_session`]: creates a fresh
    /// per-mirror [`BusBundle`], spawns the real websocket `EventBus` and
    /// this crate's forwarder onto it, then opens the mirror against a
    /// subscription on that bundle -- never [`Self::bus_tx`] directly. The
    /// bundle's receiver is subscribed *before* `EventBus::spawn` so the
    /// first `Connected`/`NeedsReconcile` pair is never missed.
    ///
    /// If `Mirror::open` fails, the freshly spawned bus/forwarder are torn
    /// down before the error returns; the caller owns retiring any bundle
    /// previously installed. `handle`/`forwarder` are `None` instead of
    /// spawned under the `live-test-knobs` + `JELLYBEAM_DISABLE_EVENT_BUS=1`
    /// test-only escape hatch (`live_local_server.rs`'s negative control).
    fn open_mirror_with_bus(
        &self,
        client: jellyfin_api::JellyfinClient,
        dir: PathBuf,
    ) -> Result<MirrorWithBus, CoreError> {
        let (bundle_tx, bundle_rx) = tokio::sync::broadcast::channel(16);

        #[cfg(feature = "live-test-knobs")]
        let bus_disabled = std::env::var("JELLYBEAM_DISABLE_EVENT_BUS").as_deref() == Ok("1");
        #[cfg(not(feature = "live-test-knobs"))]
        let bus_disabled = false;

        let real_bus = if bus_disabled {
            None
        } else {
            // `EventBus::spawn` isn't itself `async`, but calls
            // `tokio::spawn` internally (its supervisor task), needing a
            // runtime context on the calling thread.
            let _runtime_guard = self.runtime.enter();
            let (real_bus_rx, bus_handle) = jellyfin_core::EventBus::spawn(client.clone());
            let bus_forwarder =
                self.spawn_bus_forwarder(real_bus_rx, bundle_tx.clone(), self.bus_tx.clone());
            drop(_runtime_guard);
            Some((bus_handle, bus_forwarder))
        };

        match self
            .runtime
            .block_on(media_cache::Mirror::open(dir, client, bundle_rx))
        {
            Ok(mirror) => {
                let (handle, forwarder) = real_bus.unzip();
                Ok((
                    mirror,
                    BusBundle {
                        tx: bundle_tx,
                        handle,
                        forwarder,
                    },
                ))
            }
            Err(e) => {
                // Don't leak the bus/forwarder just spawned above if the
                // mirror itself fails to open.
                if let Some((handle, forwarder)) = real_bus {
                    self.stop_event_bus(Some(BusBundle {
                        tx: bundle_tx,
                        handle: Some(handle),
                        forwarder: Some(forwarder),
                    }));
                }
                Err(e.into())
            }
        }
    }

    fn spawn_listener_task(
        &self,
        mirror: media_cache::Mirror,
        listener: Arc<dyn ChangeListener>,
    ) -> tokio::task::JoinHandle<()> {
        self.runtime.spawn(async move {
            let mut rx = mirror.changes();
            while let Some(change) = media_cache::recv_changes(&mut rx).await {
                listener.on_change(ChangeEvent::from(change));
            }
        })
    }

    /// Relays every event off a real `EventBus`'s receiver onto
    /// `bundle_tx` (that mirror's own [`BusBundle::tx`] -- everything,
    /// including `Server(_)` payloads), and onward onto `status_tx`
    /// (always this `JellybeamCore`'s [`Self::bus_tx`]) but **only**
    /// `Connected`/`Disconnected`/`NeedsReconcile` -- never `Server(_)`,
    /// since `status_tx` is process-wide and outlives any one mirror.
    ///
    /// Uses [`jellyfin_core::recv_bus`] so a consumer falling behind the
    /// real bus's buffer (`Err(Lagged)`) is treated as a `NeedsReconcile`
    /// forward instead of ending the relay; only `Err(Closed)` ends the
    /// loop. `send` errors on either channel are ignored the same way every
    /// other broadcast `send` in this crate is.
    fn spawn_bus_forwarder(
        &self,
        mut real_bus_rx: tokio::sync::broadcast::Receiver<jellyfin_core::BusEvent>,
        bundle_tx: tokio::sync::broadcast::Sender<jellyfin_core::BusEvent>,
        status_tx: tokio::sync::broadcast::Sender<jellyfin_core::BusEvent>,
    ) -> tokio::task::JoinHandle<()> {
        self.runtime.spawn(async move {
            while let Some(event) = jellyfin_core::recv_bus(&mut real_bus_rx).await {
                let is_status = matches!(
                    event,
                    jellyfin_core::BusEvent::Connected
                        | jellyfin_core::BusEvent::Disconnected
                        | jellyfin_core::BusEvent::NeedsReconcile
                );
                if is_status {
                    let _ = status_tx.send(event.clone());
                }
                let _ = bundle_tx.send(event);
            }
        })
    }

    /// Tears down one [`BusBundle`] -- the shared body every session-end/
    /// re-entry site runs alongside its `stop_task(listener_task)` call. The
    /// forwarder is stopped first (abort + join); then
    /// `handle.shutdown()` aborts the real bus's supervisor and awaits it,
    /// which actually stops the capped-backoff reconnect loop -- without
    /// this, every sign-out/session-switch/re-`open_mirror` would leak one
    /// more unbounded reconnect loop. Dropping the bundle's `tx` last is
    /// what finally lets the mirror's subscription see `Closed`.
    fn stop_event_bus(&self, bundle: Option<BusBundle>) {
        let Some(bundle) = bundle else { return };
        self.lock_state().bus_connected = false;
        self.stop_task(bundle.forwarder);
        if let Some(handle) = bundle.handle {
            self.runtime.block_on(handle.shutdown());
        }
    }

    /// docs/13 Server compatibility -- one long-lived task from
    /// [`Self::new`], `select!`ing over two triggers for
    /// [`Self::refresh_server_version_once`]: a `SERVER_VERSION_REFRESH_INTERVAL`
    /// tick (backstop for gaps with no bus running; first tick discarded
    /// since every install path already refreshes), and `bus_tx`'s
    /// `Connected` event once `open_mirror` has a real bus running.
    /// `RecvError::Closed` ends the whole task.
    fn spawn_server_version_reconnect_watch(
        mut bus_rx: tokio::sync::broadcast::Receiver<jellyfin_core::BusEvent>,
        state: Arc<Mutex<State>>,
        data_dir: PathBuf,
        session_lock: Arc<Mutex<()>>,
        runtime: &tokio::runtime::Runtime,
    ) {
        runtime.spawn(async move {
            let mut interval = tokio::time::interval(SERVER_VERSION_REFRESH_INTERVAL);
            interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
            interval.tick().await; // first tick is immediate; every install path already refreshes on its own

            loop {
                tokio::select! {
                    _ = interval.tick() => {
                        Self::refresh_active_client_server_version(&state, &data_dir, &session_lock).await;
                    }
                    event = bus_rx.recv() => {
                        match event {
                            Ok(jellyfin_core::BusEvent::Connected) => {
                                state.lock().unwrap_or_else(|e| e.into_inner()).bus_connected = true;
                                Self::refresh_active_client_server_version(&state, &data_dir, &session_lock).await;
                            }
                            Ok(jellyfin_core::BusEvent::Disconnected) => {
                                state.lock().unwrap_or_else(|e| e.into_inner()).bus_connected = false;
                            }
                            Ok(_) => {}
                            Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => continue,
                            Err(tokio::sync::broadcast::error::RecvError::Closed) => break,
                        }
                    }
                }
            }
        });
    }

    /// Shared body of both `select!` arms in
    /// [`Self::spawn_server_version_reconnect_watch`]: refresh only if a
    /// client is currently installed, against whichever client is active now.
    async fn refresh_active_client_server_version(
        state: &Arc<Mutex<State>>,
        data_dir: &Path,
        session_lock: &Arc<Mutex<()>>,
    ) {
        let client = state
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .client
            .clone();
        if let Some(client) = client {
            Self::refresh_server_version_once(
                client,
                data_dir.to_path_buf(),
                state.clone(),
                session_lock.clone(),
            )
            .await;
        }
    }

    /// Kicks off [`Self::refresh_server_version_once`] for whichever client
    /// is currently installed, without blocking the caller -- the shared
    /// trigger every install path calls right after seeding
    /// [`State::server_version`] from disk. A no-op if no client is installed.
    fn spawn_server_version_refresh(&self) {
        let Some(client) = self.lock_state().client.clone() else {
            return;
        };
        let data_dir = self.data_dir.clone();
        let state = self.state.clone();
        let session_lock = self.session_lock.clone();
        self.runtime.spawn(Self::refresh_server_version_once(
            client,
            data_dir,
            state,
            session_lock,
        ));
    }

    /// Fetches `/System/Info/Public` and hands the result to
    /// [`Self::apply_public_server_info`] -- the shared fetch-compare-persist-notify steps used
    /// by [`Self::spawn_server_version_refresh`] and
    /// [`Self::spawn_server_version_reconnect_watch`]. Always inside a spawned task since it
    /// does network I/O. A fetch failure is debug-logged and leaves the stored value untouched
    /// -- a transient failure must not blank out what `server_at_least` fails closed on.
    async fn refresh_server_version_once(
        client: jellyfin_api::JellyfinClient,
        data_dir: PathBuf,
        state: Arc<Mutex<State>>,
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
    /// actually authenticated as (identified from `client` itself, never `state.client` by
    /// completion time), and `State`/the change-listener notification only update while
    /// `client` is *still* active. The on-disk persist runs on `spawn_blocking`, re-reading the
    /// session list inside `session_lock`, so a concurrent sign-out/switch/remove is never
    /// clobbered. `version` that fails to parse is treated as absent throughout (never
    /// persisted or applied) so an unparseable future format only fails to gate, never to load.
    /// A version change fires [`ChangeEvent::Refresh`] (it gates playback decisions); a
    /// name-only change updates `State::server_name` with no event, since nothing but the About
    /// page reads it.
    async fn apply_public_server_info(
        client: &jellyfin_api::JellyfinClient,
        version: Option<String>,
        server_name: Option<String>,
        data_dir: PathBuf,
        state: Arc<Mutex<State>>,
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
        // never land in `SessionFile::server_version`/`State::server_version`.
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
            let mut state = state.lock().unwrap_or_else(|e| e.into_inner());
            let still_active = state.client.as_ref().is_some_and(|active| {
                active.base_url() == server_url && active.user_id() == Some(user_id.as_str())
            });
            if !still_active {
                (false, None)
            } else {
                if server_name.is_some() {
                    state.server_name = server_name;
                }
                match parsed_version {
                    Some(parsed) if state.server_version != Some(parsed) => {
                        state.server_version = Some(parsed);
                        (true, state.listener.clone())
                    }
                    _ => (false, None),
                }
            }
        };
        if version_changed {
            // A version number is not identifying (CLAUDE.md's redaction
            // rule targets addresses/tokens/titles/paths, not this).
            if let Some(parsed) = parsed_version {
                tracing::info!(version = %parsed, "server version changed");
            }
            if let Some(listener) = listener {
                listener.on_change(ChangeEvent::Refresh);
            }
        }
    }

    /// [`Self::apply_public_server_info`]'s on-disk persist step, shared with the
    /// [`Self::persist_server_version`] test hook below. Takes `session_lock`/`data_dir` as
    /// plain values, not off `self`, so the caller can run this on `spawn_blocking`/a plain
    /// thread with no `JellybeamCore` reference.
    ///
    /// Re-reads the session list *inside* `session_lock` and writes only the record matching
    /// `(server_url, user_id)`, and only the fields that actually changed. If a concurrent
    /// sign-out/switch/remove already removed that record, writes nothing.
    fn persist_server_identity_locked(
        session_lock: &Mutex<()>,
        data_dir: &Path,
        server_url: &str,
        user_id: &str,
        version: Option<&str>,
        name: Option<&str>,
    ) {
        let _guard = session_lock.lock().unwrap_or_else(|e| e.into_inner());
        let mut sessions = session::load_list(data_dir);
        if let Some(record) = sessions
            .sessions
            .iter_mut()
            .find(|s| s.server_url == server_url && s.user_id == user_id)
        {
            let mut changed = false;
            if let Some(version) = version {
                if record.server_version.as_deref() != Some(version) {
                    record.server_version = Some(version.to_string());
                    changed = true;
                }
            }
            if let Some(name) = name {
                if record.server_name.as_deref() != Some(name) {
                    record.server_name = Some(name.to_string());
                    changed = true;
                }
            }
            if changed {
                if let Err(e) = session::save_list(data_dir, &sessions) {
                    tracing::warn!(error = %e, "failed to persist refreshed server identity");
                }
            }
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

    /// Abort `task` (if any) and block until it has actually finished, not
    /// just scheduled -- the task holds its own `Mirror` clone, so without
    /// waiting here that clone (and the writer-channel `Sender` it keeps
    /// alive) could still be live when `runtime` drops (same deadlock class
    /// documented on the `state` field above).
    fn stop_task(&self, task: Option<tokio::task::JoinHandle<()>>) {
        if let Some(handle) = task {
            handle.abort();
            let _ = self.runtime.block_on(handle);
        }
    }
}

impl Drop for JellybeamCore {
    fn drop(&mut self) {
        let (mirror, client, task, bundle, preload_task, reporting) = {
            let mut state = self.lock_state();
            (
                state.mirror.take(),
                state.client.take(),
                state.listener_task.take(),
                state.event_bus.take(),
                state.preload_task.take(),
                state.reporting.take(),
            )
        };
        // A dropped `JellybeamCore` (app process teardown) is not the place to
        // block on a final network round trip -- `abandon` (sync, no I/O)
        // just stops the reporting task's background work, safe regardless
        // of field-drop ordering.
        if let Some(reporting) = reporting {
            reporting.abandon();
        }
        // Must happen before `runtime` drops -- see the field-order comment
        // on `state` and `stop_task`'s own doc comment. `stop_event_bus`
        // holds no `Mirror`/writer-channel handle so isn't itself subject to
        // that deadlock, but still `block_on`s the bus's `shutdown()`.
        self.stop_task(preload_task);
        self.stop_task(task);
        self.stop_event_bus(bundle);
        drop(mirror);
        drop(client);
    }
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

/// Next Up minus anything already on Continue Watching (docs/07 §1), in
/// server order, capped at `limit`; the same episode never sits on two rails.
fn next_up_beside_resume(next_up: Vec<Card>, resume: &[Card], limit: usize) -> Vec<Card> {
    next_up
        .into_iter()
        .filter(|card| resume.iter().all(|r| r.id != card.id))
        .take(limit)
        .collect()
}

/// The pure half of [`JellybeamCore::children`]'s virtual-episode filtering
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

/// The pure half of [`JellybeamCore::children`]'s Series -> Seasons follow-on
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

/// The pure half of [`JellybeamCore::prepare_playback`]'s start-ticks decision
/// (docs/11 item 11, "Start from beginning"): `true` always discards
/// `saved_position_ticks` in favor of tick 0; `false` passes it through
/// unchanged. Split out so this one-line decision is unit-testable without
/// the live server `prepare_playback` needs for everything else.
fn resolved_start_ticks(
    start_from_beginning: bool,
    saved_position_ticks: Option<i64>,
) -> Option<i64> {
    if start_from_beginning {
        None
    } else {
        saved_position_ticks
    }
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
/// (default `true`), passed straight through to `android_tv_profile`.
fn build_android_profile(
    caps: &jellyfin_core::AndroidTvCaps,
    tolerate_mislabeled_levels: bool,
) -> jellyfin_api::models::DeviceProfile {
    let raw = jellyfin_core::android_tv_profile(caps, tolerate_mislabeled_levels);
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

/// [`Settings::playback_quality`]'s only effect on the negotiated
/// `DeviceProfile` (docs/18-playback-quality.md §2): in `Cap` mode,
/// `max_bps` is threaded onto `AndroidTvCaps::max_streaming_bitrate` before
/// [`build_android_profile`]. `DirectPlay`/`Auto` pass `caps` through
/// unmodified. Shared by [`JellybeamCore::prepare_playback`] and
/// [`JellybeamCore::build_preload_cache`].
fn build_android_profile_for_quality(
    caps: &jellyfin_core::AndroidTvCaps,
    tolerate_mislabeled_levels: bool,
    quality: &PlaybackQuality,
) -> jellyfin_api::models::DeviceProfile {
    match quality {
        PlaybackQuality::Cap { max_bps } => {
            let capped = jellyfin_core::AndroidTvCaps {
                max_streaming_bitrate: Some(*max_bps),
                ..caps.clone()
            };
            build_android_profile(&capped, tolerate_mislabeled_levels)
        }
        PlaybackQuality::DirectPlay | PlaybackQuality::Auto => {
            build_android_profile(caps, tolerate_mislabeled_levels)
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
    use super::*;

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
    fn snapshot_queries_are_safe_defaults_before_mirror_is_open() {
        let (_dir, core) = core_in_tempdir();
        assert!(core.views().is_empty());
        assert_eq!(core.item_count(), 0);
        assert!(!core.is_syncing());
        assert_eq!(core.sync_status(), SyncStatus::Idle);
        assert!(core
            .children("any-parent".to_string(), SortOrder::NameAsc, 0, 10)
            .is_empty());
        assert!(core.search("anything".to_string(), 10).is_empty());
        let home = core.home_snapshot();
        assert!(home.resume.is_empty());
        assert!(home.next_up.is_empty());
        assert!(home.latest.is_empty());
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
            )
            .expect_err("no client yet");
        assert!(matches!(err, CoreError::NotSignedIn));
    }

    #[test]
    fn restore_session_with_nothing_saved_returns_none() {
        let (_dir, core) = core_in_tempdir();
        assert!(core.restore_session().is_none());
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
        assert!(core.server_display_name().is_none());
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

        assert_eq!(core.server_display_name(), Some("shelf.test".to_string()));
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
            .prepare_playback("item-1".to_string(), false)
            .expect_err("no client yet");
        assert!(matches!(err, CoreError::NotSignedIn));
    }

    #[test]
    fn prepare_playback_with_start_from_beginning_before_sign_in_is_still_not_signed_in() {
        // Same precondition gate regardless of the new flag's value.
        let (_dir, core) = core_in_tempdir();
        let err = core
            .prepare_playback("item-1".to_string(), true)
            .expect_err("no client yet");
        assert!(matches!(err, CoreError::NotSignedIn));
    }

    #[test]
    fn get_item_detail_before_sign_in_is_not_signed_in() {
        let (_dir, core) = core_in_tempdir();
        let err = core
            .get_item_detail("item-1".to_string())
            .expect_err("no client yet");
        assert!(matches!(err, CoreError::NotSignedIn));
    }

    #[test]
    fn get_playback_osd_detail_before_sign_in_is_not_signed_in() {
        let (_dir, core) = core_in_tempdir();
        let err = core
            .get_playback_osd_detail("item-1".to_string())
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

        let client = jellyfin_api::JellyfinClient::from_token(
            &server.base_url,
            core.client_identity("http://example.test"),
            "tok",
        );
        core.lock_state().client = Some(client);

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

        let client = jellyfin_api::JellyfinClient::from_token(
            &server.base_url,
            core.client_identity("http://example.test"),
            "tok",
        );
        core.lock_state().client = Some(client);

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

        let client = jellyfin_api::JellyfinClient::from_token(
            &server.base_url,
            core.client_identity("http://example.test"),
            "tok",
        );
        core.lock_state().client = Some(client);

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
        assert!(core.get_media_segments("item-1".to_string()).is_empty());
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
            .prepare_playback("item-1".to_string(), false)
            .expect_err("mirror never opened");
        assert!(matches!(err, CoreError::MirrorNotOpen));
    }

    #[test]
    fn resolved_start_ticks_true_always_ignores_the_saved_position() {
        assert_eq!(resolved_start_ticks(true, Some(500)), None);
        assert_eq!(resolved_start_ticks(true, None), None);
    }

    #[test]
    fn resolved_start_ticks_false_passes_the_saved_position_through_unchanged() {
        assert_eq!(resolved_start_ticks(false, Some(500)), Some(500));
        assert_eq!(resolved_start_ticks(false, None), None);
    }

    #[test]
    fn report_position_and_paused_with_no_active_session_do_not_panic() {
        let (_dir, core) = core_in_tempdir();
        // No prepare_playback ever succeeded: stop/abandon must be silent
        // no-ops (docs/18-playback-quality.md §2), any string exercises the
        // same mismatch path.
        core.report_position(1234);
        core.report_paused(true);
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
            .prepare_playback("item-1".to_string(), false)
            .expect("initial Direct Play negotiation");
        let generation_before = core.lock_state().playback_generation;

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
        assert_eq!(
            state.playback_generation, generation_before,
            "a rejected stop must not bump the generation"
        );
    }

    /// Mirrors the stop test above for `abandon_playback`.
    #[test]
    fn abandon_playback_with_a_stale_play_session_id_does_not_touch_the_active_session() {
        let (item_json, direct_play_json) = direct_play_fixture();
        let (_dir, core, _mock) = core_signed_in_against_mock(item_json, direct_play_json);

        let plan = core
            .prepare_playback("item-1".to_string(), false)
            .expect("initial Direct Play negotiation");
        let generation_before = core.lock_state().playback_generation;

        core.abandon_playback("not-the-active-session".to_string());

        let state = core.lock_state();
        let session = state
            .reporting
            .as_ref()
            .expect("the real active session must be untouched by the rejected abandon");
        assert_eq!(session.context().play_session_id, plan.play_session_id);
        assert_eq!(
            state.playback_generation, generation_before,
            "a rejected abandon must not bump the generation"
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
            id: id.to_string(),
            item_type: item_type.to_string(),
            name: format!("{id} name"),
            primary_tag: None,
            backdrop_tag: None,
            thumb_tag: None,
            blurhash: None,
            played: false,
            position_ticks: 0,
            runtime_ticks: None,
            unplayed_count: None,
            production_year: None,
            index_number: None,
            premiere_date: None,
            parent_index_number: None,
            series_id: None,
            series_primary_tag: None,
            parent_backdrop_item_id: None,
            parent_backdrop_tag: None,
            series_name: None,
            last_played_date: None,
            overview: None,
            is_virtual,
            library_id: None,
            is_favorite: false,
        }
    }

    fn ids(cards: Vec<Card>) -> Vec<String> {
        cards.into_iter().map(|c| c.id).collect()
    }

    #[test]
    fn next_up_beside_resume_drops_resume_ids_and_keeps_server_order() {
        let next_up = vec![
            sample_card("e3", "Episode", false),
            sample_card("e1", "Episode", false),
            sample_card("e2", "Episode", false),
        ];
        let resume = vec![sample_card("e1", "Episode", false)];
        assert_eq!(
            ids(next_up_beside_resume(next_up, &resume, 10)),
            ["e3", "e2"]
        );
    }

    #[test]
    fn next_up_beside_resume_caps_at_limit() {
        let next_up = (0..5)
            .map(|i| sample_card(&format!("e{i}"), "Episode", false))
            .collect();
        assert_eq!(
            ids(next_up_beside_resume(next_up, &[], 3)),
            ["e0", "e1", "e2"]
        );
    }

    #[test]
    fn next_up_beside_resume_still_fills_to_limit_after_drops() {
        let next_up = (0..5)
            .map(|i| sample_card(&format!("e{i}"), "Episode", false))
            .collect();
        let resume = vec![
            sample_card("e0", "Episode", false),
            sample_card("e1", "Episode", false),
        ];
        assert_eq!(
            ids(next_up_beside_resume(next_up, &resume, 3)),
            ["e2", "e3", "e4"]
        );
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
        let profile = build_android_profile(&jellyfin_core::AndroidTvCaps::default(), true);
        assert_eq!(profile.name.as_deref(), Some("Jellybeam TV"));
        assert!(!profile.direct_play_profiles.is_empty());
        assert!(!profile.transcoding_profiles.is_empty());
        assert!(!profile.subtitle_profiles.is_empty());
    }

    #[test]
    fn build_android_profile_for_quality_caps_streaming_bitrate_only_in_cap_mode() {
        let caps = jellyfin_core::AndroidTvCaps::default();

        let direct_play =
            build_android_profile_for_quality(&caps, true, &PlaybackQuality::DirectPlay);
        let auto = build_android_profile_for_quality(&caps, true, &PlaybackQuality::Auto);
        let cap = build_android_profile_for_quality(
            &caps,
            true,
            &PlaybackQuality::Cap { max_bps: 8_000_000 },
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
    fn sign_out_deletes_a_saved_session() {
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

        core.sign_out();

        assert!(session::load(dir.path()).is_none());
        // A fresh restore attempt now finds nothing (state cleared too).
        assert!(core.restore_session().is_none());
    }

    /// Reproduces a version refresh's persist step running after a
    /// sign-out already removed the account: the removed account must stay
    /// removed, since `persist_server_version` re-reads under `session_lock`.
    #[test]
    fn persist_server_version_after_sign_out_writes_nothing() {
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

        // Sign-out (which removes the account) completes first; only
        // afterward does the stale refresh's persist step run.
        core.sign_out();
        core.persist_server_version("http://example.invalid", "u1", "12.0.0");

        assert!(
            session::load(dir.path()).is_none(),
            "persist_server_version must not resurrect an account removed by sign_out"
        );
    }

    /// Same interleaving as the sign-out test, against `remove_session` of
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

    /// `sign_out` clears `state.server_version` alongside `state.client`;
    /// both getters fail closed once the client is gone.
    #[test]
    fn sign_out_clears_the_cached_server_version() {
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

        core.sign_out();

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
    // a real connection. Since the account-isolation fix, `open_mirror` adds
    // no subscriber to `core.bus_tx` -- tests assert the bundle's own
    // `tx.receiver_count()` instead. ---

    #[test]
    fn open_mirror_spawns_a_bus_and_sign_out_stops_it() {
        let (dir, core) = core_in_tempdir();
        seed_session(dir.path(), "http://server-a.test", "u1");
        assert!(core.restore_session().is_some());

        core.open_mirror()
            .expect("Mirror::open only does local SQLite setup synchronously");

        assert_eq!(
            core.lock_state()
                .event_bus
                .as_ref()
                .expect("open_mirror should have spawned a real EventBus handle")
                .tx
                .receiver_count(),
            1,
            "the mirror's own subscription should be the bundle's only receiver"
        );
        let forwarder_handle = core
            .lock_state()
            .event_bus
            .as_ref()
            .expect("open_mirror should have spawned a real EventBus handle")
            .forwarder
            .as_ref()
            .expect("open_mirror should have spawned a bus forwarder")
            .abort_handle();

        core.sign_out();

        assert!(core.lock_state().event_bus.is_none());
        assert!(
            forwarder_handle.is_finished(),
            "sign_out must actually stop the bus forwarder task, not just clear the handle \
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
            .event_bus
            .as_ref()
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
            .event_bus
            .as_ref()
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

    #[test]
    fn switch_session_stops_the_outgoing_accounts_bus() {
        let (dir, core) = core_in_tempdir();
        seed_session(dir.path(), "http://server-a.test", "u1");
        seed_session(dir.path(), "http://server-b.test", "u2");
        assert_eq!(core.active_account_index(), Some(1));

        core.switch_session(0).expect("index 0 exists");
        core.open_mirror().expect("open account 0's mirror");
        assert!(core.lock_state().event_bus.is_some());
        let forwarder = core
            .lock_state()
            .event_bus
            .as_ref()
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
            core.lock_state().event_bus.is_none(),
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
        assert!(core.lock_state().event_bus.is_some());
        let forwarder = core
            .lock_state()
            .event_bus
            .as_ref()
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
        assert!(core.lock_state().event_bus.is_none());
    }

    /// `spawn_bus_forwarder` must relay every event onto the per-mirror
    /// bundle, but only status events onto the process-wide `bus_tx` --
    /// never a `Server(_)` payload.
    #[test]
    fn bus_forwarder_relays_everything_to_the_bundle_and_only_status_to_bus_tx() {
        let (_dir, core) = core_in_tempdir();
        let (real_tx, real_rx) = tokio::sync::broadcast::channel(16);
        let (bundle_tx, mut bundle_rx) = tokio::sync::broadcast::channel(16);
        let mut status_rx = core.bus_tx.subscribe();

        let forwarder = core.spawn_bus_forwarder(real_rx, bundle_tx.clone(), core.bus_tx.clone());

        let user_data_event =
            jellyfin_core::BusEvent::Server(jellyfin_api::ServerEvent::UserDataChanged {
                item_userdata: vec![],
            });
        let library_event =
            jellyfin_core::BusEvent::Server(jellyfin_api::ServerEvent::LibraryChanged {
                added: vec![],
                updated: vec![],
                removed: vec![],
            });
        real_tx
            .send(user_data_event)
            .expect("send to a fresh channel");
        real_tx
            .send(jellyfin_core::BusEvent::Connected)
            .expect("send to a fresh channel");
        real_tx
            .send(library_event)
            .expect("send to a fresh channel");
        real_tx
            .send(jellyfin_core::BusEvent::NeedsReconcile)
            .expect("send to a fresh channel");

        core.runtime.block_on(async {
            assert!(matches!(
                bundle_rx.recv().await.expect("event 1 on bundle"),
                jellyfin_core::BusEvent::Server(jellyfin_api::ServerEvent::UserDataChanged { .. })
            ));
            assert!(matches!(
                bundle_rx.recv().await.expect("event 2 on bundle"),
                jellyfin_core::BusEvent::Connected
            ));
            assert!(matches!(
                bundle_rx.recv().await.expect("event 3 on bundle"),
                jellyfin_core::BusEvent::Server(jellyfin_api::ServerEvent::LibraryChanged { .. })
            ));
            assert!(matches!(
                bundle_rx.recv().await.expect("event 4 on bundle"),
                jellyfin_core::BusEvent::NeedsReconcile
            ));

            assert!(matches!(
                status_rx.recv().await.expect("first status event"),
                jellyfin_core::BusEvent::Connected
            ));
            assert!(matches!(
                status_rx.recv().await.expect("second status event"),
                jellyfin_core::BusEvent::NeedsReconcile
            ));
            let timed_out = tokio::time::timeout(Duration::from_millis(200), status_rx.recv())
                .await
                .is_err();
            assert!(
                timed_out,
                "no Server(_) event should ever reach the process-wide status bus_tx"
            );
        });

        drop(real_tx);
        core.stop_task(Some(forwarder));
    }

    /// Two mirrors' bundles alive at once must never let one account's
    /// `UserDataChanged` reach the other's mirror.
    #[test]
    fn two_live_bundles_never_share_user_data_events() {
        const MOVIE_ID: &str = "00000000-0000-0000-0000-000000000099";

        let (dir, core) = core_in_tempdir();
        let mock = core.runtime.block_on(RouteMockServer::start());
        mock.route(
            "GET",
            "/UserViews",
            200,
            user_views_json("00000000-0000-0000-0000-000000000098", "movies"),
        );
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

        let identity = core.client_identity("http://example.test");
        let client_a =
            jellyfin_api::JellyfinClient::from_token(&mock.base_url, identity.clone(), "tok-a")
                .with_user_id("user-a");
        let client_b = jellyfin_api::JellyfinClient::from_token(&mock.base_url, identity, "tok-b")
            .with_user_id("user-b");

        let (mirror_a, bundle_a) = core
            .open_mirror_with_bus(client_a, dir.path().join("mirror-a"))
            .expect("bundle A opens");
        let (mirror_b, bundle_b) = core
            .open_mirror_with_bus(client_b, dir.path().join("mirror-b"))
            .expect("bundle B opens");

        // Both bundles alive at once, neither installed into `state`.
        wait_until(
            || mirror_a.card_by_id(MOVIE_ID).is_some_and(|c| !c.played),
            "mirror A's initial sync to seed the unplayed movie row",
        );
        wait_until(
            || mirror_b.card_by_id(MOVIE_ID).is_some_and(|c| !c.played),
            "mirror B's initial sync to seed the unplayed movie row",
        );

        bundle_a
            .tx
            .send(jellyfin_core::BusEvent::Server(
                jellyfin_api::ServerEvent::UserDataChanged {
                    item_userdata: vec![(
                        MOVIE_ID.to_string(),
                        jellyfin_api::models::UserItemDataDto {
                            is_favorite: None,
                            item_id: None,
                            key: Some("k".to_string()),
                            last_played_date: None,
                            likes: None,
                            play_count: None,
                            playback_position_ticks: None,
                            played: Some(true),
                            played_percentage: None,
                            rating: None,
                            unplayed_item_count: None,
                        },
                    )],
                },
            ))
            .expect("bundle A has at least its own mirror subscribed");

        wait_until(
            || mirror_a.card_by_id(MOVIE_ID).is_some_and(|c| c.played),
            "mirror A to apply the UserDataChanged event sent on its own bundle",
        );
        // Bundle B, a different session's bus, must never see bundle A's event.
        std::thread::sleep(Duration::from_millis(200));
        assert!(
            mirror_b.card_by_id(MOVIE_ID).is_some_and(|c| !c.played),
            "bundle B must not have received bundle A's UserDataChanged event"
        );

        core.stop_event_bus(Some(bundle_a));
        drop(mirror_a);

        // B keeps working independently once A is gone.
        assert_eq!(
            bundle_b.tx.receiver_count(),
            1,
            "bundle B's tx should still have exactly its own mirror subscribed"
        );
        bundle_b
            .tx
            .send(jellyfin_core::BusEvent::Connected)
            .expect("bundle B's mirror is still subscribed");

        core.stop_event_bus(Some(bundle_b));
        drop(mirror_b);
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
        assert!(matches!(core.open_mirror(), Err(CoreError::NotSignedIn)));
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

    #[test]
    fn sign_out_removes_only_the_active_account_and_does_not_auto_install_the_next_one() {
        let (dir, core) = core_in_tempdir();
        seed_session(dir.path(), "http://server-a.test", "u1");
        seed_session(dir.path(), "http://server-b.test", "u2");
        assert!(
            core.restore_session().is_some(),
            "u2 (active) restores fine"
        );

        core.sign_out();

        // u2 (previously active) is gone; u1 remains and is now active.
        let accounts = core.list_accounts();
        assert_eq!(accounts.len(), 1);
        assert_eq!(accounts[0].user_id, "u1");
        assert_eq!(core.active_account_index(), Some(0));

        // current_account (a pure disk read) reports the new active account...
        let current = core.current_account().expect("u1 is still on disk");
        assert_eq!(current.user_id, "u1");

        // ...but no client was installed for it: sign_out leaves that
        // decision to Kotlin.
        let err = core.open_mirror().expect_err("no client installed yet");
        assert!(matches!(err, CoreError::NotSignedIn));
    }

    #[test]
    fn sign_out_of_the_only_account_empties_the_list() {
        let (dir, core) = core_in_tempdir();
        seed_session(dir.path(), "http://server-a.test", "u1");
        core.sign_out();
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

        let home = core.home_snapshot();
        assert!(home.latest.is_empty());
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
    fn remember_track_choice_makes_a_per_series_preference_win_over_the_global_one() {
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

        core.remember_track_choice(
            "series-1".to_string(),
            TrackKindFfi::Audio,
            Some("eng".to_string()),
        );

        let tracks = vec![
            track(1, TrackKindFfi::Audio, Some("eng")),
            track(2, TrackKindFfi::Audio, Some("jpn")),
        ];
        let decision = core.resolve_tracks(Some("series-1".to_string()), tracks);
        assert_eq!(decision.audio_track_id, Some(1));
    }

    #[test]
    fn remember_track_choice_only_applies_to_the_series_it_was_saved_under() {
        let (_dir, core) = core_in_tempdir();
        core.remember_track_choice(
            "series-1".to_string(),
            TrackKindFfi::Audio,
            Some("eng".to_string()),
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
    fn remember_track_choice_persists_across_a_fresh_core_against_the_same_data_dir() {
        let dir = tempfile::tempdir().expect("tempdir");
        let core = JellybeamCore::new(dir.path().to_string_lossy().to_string());
        core.remember_track_choice(
            "series-1".to_string(),
            TrackKindFfi::Subtitle,
            Some("spa".to_string()),
        );

        let reopened = JellybeamCore::new(dir.path().to_string_lossy().to_string());
        let tracks = vec![
            track(1, TrackKindFfi::Subtitle, Some("eng")),
            track(2, TrackKindFfi::Subtitle, Some("spa")),
        ];
        let decision = reopened.resolve_tracks(Some("series-1".to_string()), tracks);
        assert_eq!(decision.subtitle_track_id, Some(2));
    }

    #[test]
    fn remember_track_choice_with_none_clears_a_previously_remembered_key() {
        let (_dir, core) = core_in_tempdir();
        core.remember_track_choice(
            "series-1".to_string(),
            TrackKindFfi::Audio,
            Some("eng".to_string()),
        );
        core.remember_track_choice("series-1".to_string(), TrackKindFfi::Audio, None);

        let tracks = vec![
            track(1, TrackKindFfi::Audio, Some("eng")),
            track(2, TrackKindFfi::Audio, Some("jpn")),
        ];
        let decision = core.resolve_tracks(Some("series-1".to_string()), tracks);
        assert_eq!(
            decision.audio_track_id, None,
            "cleared per-series preference must fall back to no audio decision (no global set either)"
        );
    }

    #[test]
    fn remember_track_choice_with_video_kind_is_a_no_op() {
        let (_dir, core) = core_in_tempdir();
        // Must not panic, and must not create a usable preference either.
        core.remember_track_choice(
            "series-1".to_string(),
            TrackKindFfi::Video,
            Some("eng".to_string()),
        );

        let tracks = vec![track(1, TrackKindFfi::Audio, Some("eng"))];
        let decision = core.resolve_tracks(Some("series-1".to_string()), tracks);
        assert_eq!(decision.audio_track_id, None);
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

        core.preload_playback("item-1".to_string());
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
            .prepare_playback("item-1".to_string(), false)
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

        core.preload_playback("item-1".to_string());
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
            .prepare_playback("item-1".to_string(), false)
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

        core.preload_playback("item-1".to_string());
        wait_for_playback_info_hits(&mock, 1);
        core.preload_playback("item-1".to_string());
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

        core.preload_playback("item-1".to_string());
        wait_for_playback_info_hits(&mock, 1);
        core.preload_playback("item-2".to_string());
        core.preload_playback("item-3".to_string());
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

    #[test]
    fn real_prepare_for_the_same_item_joins_the_in_flight_preload_instead_of_renegotiating() {
        let (item_json, playback_json) = direct_play_fixture();
        let (_dir, core, mock) = core_signed_in_against_mock_with_delay(
            item_json,
            playback_json,
            Duration::from_millis(150),
        );

        core.preload_playback("item-1".to_string());
        wait_for_playback_info_hits(&mock, 1);
        let plan = core
            .prepare_playback("item-1".to_string(), false)
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

        core.preload_playback("item-1".to_string());
        wait_for_playback_info_hits(&mock, 1);
        let plan = core
            .prepare_playback("item-2".to_string(), false)
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
    fn session_restore_aborts_in_flight_preload_and_late_completion_cannot_repopulate_cache() {
        let (item_json, playback_json) = direct_play_fixture();
        let (_dir, core, mock) = core_signed_in_against_mock_with_delay(
            item_json,
            playback_json,
            Duration::from_millis(150),
        );

        core.preload_playback("item-1".to_string());
        wait_for_playback_info_hits(&mock, 1);
        assert!(core.restore_session().is_some());
        std::thread::sleep(Duration::from_millis(175));

        let state = core.lock_state();
        assert!(state.preload_task.is_none());
        assert!(state.preload_cache.is_none());
    }

    #[test]
    fn profile_setting_change_discards_a_completed_preload() {
        let (item_json, playback_json) = direct_play_fixture();
        let (_dir, core, _mock) = core_signed_in_against_mock(item_json, playback_json);
        core.preload_playback("item-1".to_string());
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

        core.preload_playback("item-1".to_string());

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
        core.preload_playback("item-1".to_string());
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

        core.preload_playback("item-1".to_string());
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

        core.preload_playback("item-1".to_string());
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
            .prepare_playback("item-1".to_string(), false)
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

        core.preload_playback("item-1".to_string());

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
            .prepare_playback("item-1".to_string(), false)
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
            .prepare_playback("item-1".to_string(), false)
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

        core.preload_playback("item-1".to_string());
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
            .prepare_playback("item-1".to_string(), false)
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
            .prepare_transcode_fallback(
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
            .prepare_playback("item-1".to_string(), false)
            .expect("initial Direct Play negotiation");
        assert_eq!(first_plan.play_method, PlayMethodFfi::DirectPlay);
        assert_eq!(mock.playback_info_hit_count(), 1);
        let generation_after_prepare_playback = core.lock_state().playback_generation;

        let fallback_plan = core
            .prepare_transcode_fallback(
                "item-1".to_string(),
                12_345_678,
                "decoder error".to_string(),
                first_plan.play_session_id.clone(),
            )
            .expect("Auto mode should negotiate a real transcode");

        assert_eq!(mock.playback_info_hit_count(), 2);
        // A successful install always bumps `playback_generation`, exactly
        // like every other session handoff.
        assert_eq!(
            core.lock_state().playback_generation,
            generation_after_prepare_playback.wrapping_add(1),
            "installing the fallback's fresh session must bump the generation"
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
            .prepare_playback("item-1".to_string(), false)
            .expect("initial Direct Play negotiation");

        let err = core
            .prepare_transcode_fallback(
                "item-1".to_string(),
                1_000_000,
                "decoder error".to_string(),
                first_plan.play_session_id.clone(),
            )
            .expect_err("a DirectPlay decision from a forced negotiation must error");
        assert!(matches!(err, CoreError::Api { .. }));
        assert_eq!(mock.playback_info_hit_count(), 2);
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
            .prepare_playback("item-1".to_string(), false)
            .expect("initial Direct Play negotiation");
        assert_eq!(mock.playback_info_hit_count(), 1);

        let fallback_plan = core
            .prepare_transcode_fallback(
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
            .prepare_playback("item-1".to_string(), false)
            .expect("initial Direct Play negotiation");
        assert_eq!(mock.playback_info_hit_count(), 1);
        assert_ne!(
            first_plan.play_session_id, "not-the-active-session",
            "fixture sanity: the stale id below must genuinely differ from the real one"
        );

        let err = core
            .prepare_transcode_fallback(
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
            .prepare_playback("item-1".to_string(), false)
            .expect("initial Direct Play negotiation for item A");
        assert_eq!(mock.playback_info_hit_count(), 1);

        let core_for_fallback = Arc::clone(&core);
        let fallback_session_id = first_plan.play_session_id.clone();
        let fallback_handle = std::thread::spawn(move || {
            core_for_fallback.prepare_transcode_fallback(
                "item-1".to_string(),
                20_000_000,
                "decoder error".to_string(),
                fallback_session_id,
            )
        });

        // Blocks until A's request has reached the mock, so the next call
        // is guaranteed the third arrival.
        wait_for_playback_info_hits(&mock, 2);

        let second_plan = core.prepare_playback("item-2".to_string(), false).expect(
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
        let a_handle =
            std::thread::spawn(move || core_for_a.prepare_playback("item-1".to_string(), false));

        // Blocks until A's request has reached the mock, so B is guaranteed
        // the second arrival.
        wait_for_playback_info_hits(&mock, 1);

        let b_plan = core
            .prepare_playback("item-2".to_string(), false)
            .expect("a different item's prepare_playback must succeed while A's negotiates");
        assert_eq!(b_plan.play_method, PlayMethodFfi::DirectPlay);
        assert_eq!(mock.playback_info_hit_count(), 2);
        let generation_after_b = core.lock_state().playback_generation;

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
        assert_eq!(
            state.playback_generation, generation_after_b,
            "a rejected install must not bump the generation again"
        );
    }

    /// `install_prepared_session` unit-tested directly: a stale `attempt`
    /// must reject and abandon the session, never install it.
    #[test]
    fn install_prepared_session_rejects_a_stale_attempt_and_abandons_the_session() {
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
        let attempt = core.lock_state().playback_generation;
        let session = {
            let _guard = core.runtime.enter();
            jellyfin_core::ReportingSession::start(client, ctx)
        };

        // Bumping the generation alone reproduces the guard without a
        // second real session (two `lock_state()` calls to avoid a
        // same-statement double-lock deadlock).
        {
            let mut state = core.lock_state();
            state.playback_generation = state.playback_generation.wrapping_add(1);
        }

        let result = core.install_prepared_session(attempt, session);
        assert!(
            matches!(result, Err(CoreError::StalePlaybackSession)),
            "a stale attempt must be rejected, got {result:?}"
        );
        assert!(
            core.lock_state().reporting.is_none(),
            "a rejected session must never be installed"
        );

        let _dir = dir; // keep the tempdir alive for the duration of this test
    }

    #[test]
    fn sign_out_clears_the_preload_cache() {
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

        core.sign_out();

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

    /// (method, path, required query substring, status, body): see
    /// [`RouteMockServer::route_with_query`].
    type QueryRoute = (String, String, String, u16, String);
    /// (method, required path substring, responses, delays): see
    /// [`RouteMockServer::route_script`].
    type ScriptedRoute = (String, String, Vec<serde_json::Value>, Vec<Duration>);

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
        hits: Mutex<std::collections::HashMap<String, usize>>,
        /// Every request's ("METHOD path", parsed JSON body) in arrival
        /// order, for callers that need either an exact-path or a
        /// substring-matched body history.
        bodies: Mutex<Vec<(String, serde_json::Value)>>,
        /// "METHOD full-target" (query string included) in arrival order,
        /// so a test can tell apart requests that collapse to the same key.
        log: Mutex<Vec<String>>,
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

        *unlock(&shared.hits).entry(key.clone()).or_insert(0) += 1;
        unlock(&shared.log).push(format!("{method} {target}"));
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

        let configured = query_configured
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
    fn wait_until(mut f: impl FnMut() -> bool, what: &str) {
        for _ in 0..400 {
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
        let shelf: Vec<String> = core
            .home_snapshot()
            .favorites
            .into_iter()
            .map(|c| c.id)
            .collect();
        assert_eq!(shelf, vec![MOVIE_ID.to_string()]);
        let mut settings = core.get_settings();
        settings.home_show_favorites = false;
        core.set_settings(settings);
        assert!(core.home_snapshot().favorites.is_empty());
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
        assert!(matches!(err, CoreError::Unauthorized));
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
            .prepare_playback(EP1_ID.to_string(), false)
            .expect("Direct Play negotiation");
        let scope_fetches_before = mock.hit_count_containing("GET", SERIES_ID);

        core.stop_playback(plan.play_session_id, 5_000_000);

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

    /// docs/19 §2.3: `set_played_recursive` marks every non-virtual episode
    /// locally, never a virtual one.
    #[test]
    fn set_played_recursive_marks_every_non_virtual_episode_and_skips_virtual() {
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

    /// A first sign-in that never separately calls `open_mirror` still gets
    /// a bus/forwarder installed immediately, and `sign_out` still stops them.
    #[test]
    fn sign_in_alone_spawns_a_bus_and_sign_out_stops_it() {
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

        assert!(
            core.lock_state().event_bus.is_some(),
            "sign_in alone (no open_mirror call) should already have spawned a real EventBus"
        );
        let forwarder_handle = core
            .lock_state()
            .event_bus
            .as_ref()
            .expect("sign_in alone should already have spawned a bundle")
            .forwarder
            .as_ref()
            .expect("sign_in alone should already have spawned a bus forwarder")
            .abort_handle();
        assert!(
            !forwarder_handle.is_finished(),
            "the forwarder spawned by sign_in should still be running"
        );

        core.sign_out();

        assert!(core.lock_state().event_bus.is_none());
        assert!(
            forwarder_handle.is_finished(),
            "sign_out must actually stop the bus forwarder sign_in spawned"
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
                assert_eq!(reason, "connection refused");
            }
            other => panic!("expected ServerUnreachable, got {other:?}"),
        }
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
        // reconnect watch's own tick can notice the route change.
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

    #[test]
    fn bus_connected_and_disconnected_events_flip_live_events_connected() {
        let (_dir, core, _mock) = core_signed_in_against_routes();
        assert!(!core.server_info_snapshot().live_events_connected);

        core.bus_tx.send(jellyfin_core::BusEvent::Connected).expect(
            "send to a live receiver -- the reconnect watch subscribes at JellybeamCore::new",
        );
        wait_until(
            || core.server_info_snapshot().live_events_connected,
            "the reconnect watch to observe Connected",
        );

        core.bus_tx
            .send(jellyfin_core::BusEvent::Disconnected)
            .expect("send to a live receiver");
        wait_until(
            || !core.server_info_snapshot().live_events_connected,
            "the reconnect watch to observe Disconnected",
        );
    }
}
