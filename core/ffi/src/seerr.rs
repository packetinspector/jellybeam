//! Seerr Discover integration (`docs/14-seerr-discover.md`): the on-disk per-account config
//! store, the live `SeerrHandle` cached in `JellybeamCore::State`, the plain decision functions
//! this doc calls out for Rust with unit tests, and the exported `impl JellybeamCore` block. The
//! decision functions never touch `uniffi` types, so reusing them elsewhere only re-skins the FFI
//! boundary.

use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use futures_util::StreamExt;

use crate::error::{CoreError, UnreachableReason};
use crate::object::JellybeamCore;
use crate::seerr_types::*;
use crate::session::same_server;

// --- Timeouts --------------------------------------------------------------

/// Tight timeouts for `seerr_connect`'s candidate-URL probing -- a bad
/// candidate must fail fast so trying 2-4 candidates still feels instant.
const PROBE_CONNECT_TIMEOUT: Duration = Duration::from_secs(2);
const PROBE_REQUEST_TIMEOUT: Duration = Duration::from_secs(6);

// Test seam: a test shortens the probe timeouts for the connects it runs on its own thread.
#[cfg(test)]
thread_local! {
    static PROBE_TIMEOUTS_OVERRIDE: std::cell::Cell<Option<(Duration, Duration)>> =
        const { std::cell::Cell::new(None) };
}

/// `(connect, request)` timeouts for `seerr_connect`'s candidate probing.
fn probe_timeouts() -> (Duration, Duration) {
    #[cfg(test)]
    if let Some(timeouts) = PROBE_TIMEOUTS_OVERRIDE.with(|cell| cell.get()) {
        return timeouts;
    }
    (PROBE_CONNECT_TIMEOUT, PROBE_REQUEST_TIMEOUT)
}

/// Normal timeouts for every other Seerr call, same magnitude as
/// `jellyfin-api`'s own connect/request timeouts.
const NORMAL_CONNECT_TIMEOUT: Duration = Duration::from_secs(10);
const NORMAL_REQUEST_TIMEOUT: Duration = Duration::from_secs(30);

/// How long a [`SeerrHandle`]'s home-snapshot cache stays fresh (docs/14
/// Performance: "60s in-core snapshot cache per account").
const HOME_CACHE_TTL: Duration = Duration::from_secs(60);

/// Cap on in-flight detail fetches for per-request/per-instance lookups --
/// bounds fan-out on a Seerr instance while keeping My Requests (up to 50) to a few round trips.
const MAX_CONCURRENT_DETAIL_FETCHES: usize = 10;

/// Longest a `/settings/public` read may hold a connect or handle build (docs/14 "Auth").
const PUBLIC_SETTINGS_TIMEOUT: Duration = Duration::from_secs(5);

// Test seam: a test shortens the settings read cap for the calls it runs on its own thread.
#[cfg(test)]
thread_local! {
    static PUBLIC_SETTINGS_TIMEOUT_OVERRIDE: std::cell::Cell<Option<Duration>> =
        const { std::cell::Cell::new(None) };
}

fn public_settings_timeout() -> Duration {
    #[cfg(test)]
    if let Some(timeout) = PUBLIC_SETTINGS_TIMEOUT_OVERRIDE.with(std::cell::Cell::get) {
        return timeout;
    }
    PUBLIC_SETTINGS_TIMEOUT
}

// --- On-disk per-account config store ---------------------------------------

/// One saved Seerr connection, keyed by the Jellyfin `(server_url,
/// user_id)` identity (docs/14). `secret` is a plaintext password/API key,
/// same storage contract as `session.rs`'s `SessionFile::token`.
#[derive(Debug, Clone, PartialEq, serde::Serialize, serde::Deserialize)]
struct SeerrConfigEntry {
    server_url: String,
    user_id: String,
    seerr_url: String,
    #[serde(default)]
    method: SeerrAuthMethod,
    #[serde(default)]
    identity: String,
    #[serde(default)]
    secret: String,
}

/// `<data_dir>/seerr.json`'s whole shape -- a flat list, no `active` index:
/// the active entry matches the Jellyfin session's own active identity.
#[derive(Debug, Clone, Default, PartialEq, serde::Serialize, serde::Deserialize)]
struct SeerrConfigFile {
    #[serde(default)]
    entries: Vec<SeerrConfigEntry>,
}

impl SeerrConfigFile {
    fn find(&self, server_url: &str, user_id: &str) -> Option<&SeerrConfigEntry> {
        self.entries
            .iter()
            .find(|e| same_server(&e.server_url, server_url) && e.user_id == user_id)
    }

    /// Inserts `entry`, or replaces an existing entry for the same
    /// `(server_url, user_id)` pair.
    fn upsert(&mut self, entry: SeerrConfigEntry) {
        if let Some(existing) = self
            .entries
            .iter_mut()
            .find(|e| same_server(&e.server_url, &entry.server_url) && e.user_id == entry.user_id)
        {
            *existing = entry;
        } else {
            self.entries.push(entry);
        }
    }

    fn remove(&mut self, server_url: &str, user_id: &str) {
        self.entries
            .retain(|e| !(same_server(&e.server_url, server_url) && e.user_id == user_id));
    }
}

fn config_path(data_dir: &Path) -> PathBuf {
    data_dir.join("seerr.json")
}

/// Tolerant load -- a missing or corrupt `seerr.json` reads as "nothing
/// configured", same contract as `settings::load`/`session::load_list`.
fn load_config(data_dir: &Path) -> SeerrConfigFile {
    std::fs::read(config_path(data_dir))
        .ok()
        .and_then(|bytes| serde_json::from_slice(&bytes).ok())
        .unwrap_or_default()
}

/// Atomic replacement; [`SeerrConfigStore`] orders the read-modify-write around it.
fn save_config(data_dir: &Path, config: &SeerrConfigFile) -> std::io::Result<()> {
    crate::persistence::save_json(&config_path(data_dir), config)
}

/// Serializes `seerr.json` read-modify-write and orders user intents: the latest
/// connect or disconnect wins, so an earlier connect that lands later is dropped.
/// Never held across network I/O; the state lock may be taken inside it, never
/// the reverse.
#[derive(Default)]
pub(crate) struct SeerrConfigStore(Mutex<u64>);

impl SeerrConfigStore {
    fn lock(&self) -> std::sync::MutexGuard<'_, u64> {
        self.0.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// Registers a new intent whose commit happens later, after network work.
    fn begin(&self) -> u64 {
        let mut intents = self.lock();
        *intents += 1;
        *intents
    }

    /// The current intent, for work that must not outlive a later intent.
    fn current(&self) -> u64 {
        *self.lock()
    }

    /// Runs `commit` under the lock unless a later intent has begun.
    fn commit<R>(&self, intent: u64, commit: impl FnOnce() -> R) -> Option<R> {
        let intents = self.lock();
        (*intents == intent).then(commit)
    }

    /// Registers and applies an intent in one step.
    fn apply<R>(&self, apply: impl FnOnce() -> R) -> R {
        let mut intents = self.lock();
        *intents += 1;
        apply()
    }
}

// --- Live handle -------------------------------------------------------------

#[derive(Default)]
struct GenreCache {
    movie: Option<Vec<SeerrGenre>>,
    tv: Option<Vec<SeerrGenre>>,
}

struct SeerrHandleInner {
    client: seerr_api::SeerrClient,
    user_id: i64,
    /// The plain Seerr server URL (no `/api/v1`), used as the base for
    /// `{seerr_url}/imageproxy/tmdb` -- see [`strip_api_v1`].
    public_url: String,
    movie4k_enabled: bool,
    series4k_enabled: bool,
    cache_images: bool,
    application_title: Option<String>,
    home_cache: Mutex<Option<(Instant, SeerrHome)>>,
    /// Bumped by every home invalidation; a home fetch stores only if it is unchanged.
    home_gen: AtomicU64,
    genre_cache: Mutex<GenreCache>,
}

/// The active account's live Seerr connection, cached in
/// `object.rs::State::seerr`. Cheap to clone (`Arc`-backed).
#[derive(Clone)]
pub(crate) struct SeerrHandle {
    inner: Arc<SeerrHandleInner>,
}

impl SeerrHandle {
    fn new(
        client: seerr_api::SeerrClient,
        user_id: i64,
        api_base_url: &str,
        public: seerr_api::PublicSettings,
    ) -> Self {
        Self {
            inner: Arc::new(SeerrHandleInner {
                client,
                user_id,
                public_url: strip_api_v1(api_base_url),
                movie4k_enabled: public.movie4k_enabled,
                series4k_enabled: public.series4k_enabled,
                cache_images: public.cache_images,
                application_title: public.application_title,
                home_cache: Mutex::new(None),
                home_gen: AtomicU64::new(0),
                genre_cache: Mutex::new(GenreCache::default()),
            }),
        }
    }

    fn client(&self) -> &seerr_api::SeerrClient {
        &self.inner.client
    }

    fn user_id(&self) -> i64 {
        self.inner.user_id
    }

    fn images(&self) -> ImageContext<'_> {
        ImageContext {
            seerr_url: &self.inner.public_url,
            cache_images: self.inner.cache_images,
        }
    }

    fn movie4k_enabled(&self) -> bool {
        self.inner.movie4k_enabled
    }

    fn series4k_enabled(&self) -> bool {
        self.inner.series4k_enabled
    }

    fn app_title(&self) -> Option<String> {
        self.inner.application_title.clone()
    }

    fn cached_home(&self) -> Option<SeerrHome> {
        let guard = self
            .inner
            .home_cache
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        guard.as_ref().and_then(|(at, home)| {
            if at.elapsed() < HOME_CACHE_TTL {
                Some(home.clone())
            } else {
                None
            }
        })
    }

    fn home_generation(&self) -> u64 {
        self.inner.home_gen.load(Ordering::SeqCst)
    }

    /// Stores `home` only if no invalidation ran since `generation` was read, so a fetch that
    /// straddles a submit/cancel never caches its pre-mutation snapshot (docs/14 Performance).
    fn cache_home(&self, generation: u64, home: SeerrHome) {
        let mut cache = self
            .inner
            .home_cache
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        if self.home_generation() == generation {
            *cache = Some((Instant::now(), home));
        }
    }

    /// Drops the cached home snapshot after a submit/cancel, so a stale
    /// availability badge doesn't sit for up to [`HOME_CACHE_TTL`].
    fn invalidate_home(&self) {
        let mut cache = self
            .inner
            .home_cache
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        self.inner.home_gen.fetch_add(1, Ordering::SeqCst);
        *cache = None;
    }

    fn cached_genres(&self, media_type: SeerrMediaType) -> Option<Vec<SeerrGenre>> {
        let guard = self
            .inner
            .genre_cache
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        match media_type {
            SeerrMediaType::Movie => guard.movie.clone(),
            SeerrMediaType::Tv => guard.tv.clone(),
        }
    }

    fn cache_genres(&self, media_type: SeerrMediaType, genres: Vec<SeerrGenre>) {
        let mut guard = self
            .inner
            .genre_cache
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        match media_type {
            SeerrMediaType::Movie => guard.movie = Some(genres),
            SeerrMediaType::Tv => guard.tv = Some(genres),
        }
    }
}

/// Strip a normalized API base's trailing `/api/v1` back off, for
/// `{seerr_url}/imageproxy/tmdb`. A no-op if the suffix is absent.
fn strip_api_v1(base: &str) -> String {
    base.strip_suffix("/api/v1").unwrap_or(base).to_string()
}

async fn build_handle(entry: &SeerrConfigEntry) -> Result<SeerrHandle, CoreError> {
    let (client, user) = seerr_api::SeerrClient::login(seerr_api::LoginArgs {
        base_url: &entry.seerr_url,
        method: entry.method.into(),
        identity: &entry.identity,
        secret: &entry.secret,
        connect_timeout: NORMAL_CONNECT_TIMEOUT,
        request_timeout: NORMAL_REQUEST_TIMEOUT,
    })
    .await?;
    let public = public_settings_or_default(&client).await;
    Ok(SeerrHandle::new(client, user.id, &entry.seerr_url, public))
}

/// `/settings/public` capped at [`PUBLIC_SETTINGS_TIMEOUT`]: the toggles only gate UI, so a
/// failed or slow read leaves them off for this handle rather than failing it (docs/14 "Auth").
async fn public_settings_or_default(client: &seerr_api::SeerrClient) -> seerr_api::PublicSettings {
    tokio::time::timeout(public_settings_timeout(), client.public_settings())
        .await
        .ok()
        .and_then(Result::ok)
        .unwrap_or_default()
}

// --- Pure decision functions (unit-tested below) ----------------------------

/// `MediaInfo.status` (1..=6) -> [`SeerrAvailability`]. An absent status,
/// `1 = UNKNOWN`, and any value with no bucket here fold into `NotRequested`.
fn availability_from_status(status: Option<i64>) -> SeerrAvailability {
    match status {
        Some(2) => SeerrAvailability::Pending,
        Some(3) => SeerrAvailability::Processing,
        Some(4) => SeerrAvailability::PartiallyAvailable,
        Some(5) => SeerrAvailability::Available,
        _ => SeerrAvailability::NotRequested,
    }
}

fn availability_rank(availability: SeerrAvailability) -> u8 {
    match availability {
        SeerrAvailability::NotRequested => 0,
        SeerrAvailability::Pending => 1,
        SeerrAvailability::Processing => 2,
        SeerrAvailability::PartiallyAvailable => 3,
        SeerrAvailability::Available => 4,
    }
}

/// `MediaRequest.status` (`1 = PENDING`, `2 = APPROVED`, `3 = DECLINED`)
/// -> [`SeerrRequestStatus`], strictly -- `None` for anything else. Contrast
/// [`request_status_for_display`], lossy on purpose for "My Requests".
fn request_status_from_int(status: i64) -> Option<SeerrRequestStatus> {
    match status {
        1 => Some(SeerrRequestStatus::Pending),
        2 => Some(SeerrRequestStatus::Approved),
        3 => Some(SeerrRequestStatus::Declined),
        _ => None,
    }
}

/// `seerr_my_requests`-only mapping: unfiltered `GET /request` can also
/// return `4 = FAILURE`/`5 = COMPLETED`. `COMPLETED` maps to `Approved`
/// (closest analog); `FAILURE`/unrecognized map to `Declined`.
fn request_status_for_display(status: i64) -> SeerrRequestStatus {
    match status {
        1 => SeerrRequestStatus::Pending,
        2 | 5 => SeerrRequestStatus::Approved,
        _ => SeerrRequestStatus::Declined,
    }
}

fn request_status_rank(status: SeerrRequestStatus) -> u8 {
    match status {
        SeerrRequestStatus::Declined => 0,
        SeerrRequestStatus::Pending => 1,
        SeerrRequestStatus::Approved => 2,
    }
}

/// `jellyfinMediaId4k` preferred over `jellyfinMediaId` (docs/14's
/// contract section).
fn jellyfin_item_id_from(info: Option<&seerr_api::MediaInfo>) -> Option<String> {
    let info = info?;
    info.jellyfin_media_id4k
        .clone()
        .or_else(|| info.jellyfin_media_id.clone())
}

/// A season is requestable unless already at least PENDING, or already
/// covered by an existing pending/approved request (docs/14's wording).
fn season_requestable(
    availability: SeerrAvailability,
    request_status: Option<SeerrRequestStatus>,
) -> bool {
    let already_available = !matches!(availability, SeerrAvailability::NotRequested);
    let already_requested = matches!(
        request_status,
        Some(SeerrRequestStatus::Pending) | Some(SeerrRequestStatus::Approved)
    );
    !already_available && !already_requested
}

/// The most-advanced status carried by any *SD* request's entry for
/// `season_number` -- `is4k` requests excluded, since `SeerrTvDetail::seasons`
/// only reflects SD availability.
fn season_request_status(
    media_info: Option<&seerr_api::MediaInfo>,
    season_number: i32,
) -> Option<SeerrRequestStatus> {
    let info = media_info?;
    let mut best: Option<SeerrRequestStatus> = None;
    for request in info.requests.iter().filter(|r| !r.is4k) {
        for season in &request.seasons {
            if season.season_number != Some(season_number) {
                continue;
            }
            let Some(status) = season.status.and_then(request_status_from_int) else {
                continue;
            };
            if best.is_none_or(|current| request_status_rank(status) > request_status_rank(current))
            {
                best = Some(status);
            }
        }
    }
    best
}

/// Builds the SD season-status list: merges the title's own status with
/// `mediaInfo`'s (higher-ranked wins), `requestable` folds in any existing
/// SD request.
fn build_season_statuses(
    seasons: &[seerr_api::Season],
    media_info: Option<&seerr_api::MediaInfo>,
) -> Vec<SeerrSeasonStatus> {
    seasons
        .iter()
        .filter_map(|season| {
            let season_number = season.season_number?;
            let mut availability = availability_from_status(season.status);
            if let Some(info) = media_info {
                if let Some(info_season) = info
                    .seasons
                    .iter()
                    .find(|s| s.season_number == Some(season_number))
                {
                    let info_availability = availability_from_status(info_season.status);
                    if availability_rank(info_availability) > availability_rank(availability) {
                        availability = info_availability;
                    }
                }
            }
            let request_status = season_request_status(media_info, season_number);
            Some(SeerrSeasonStatus {
                season_number,
                name: season.name.clone(),
                episode_count: season.episode_count.unwrap_or(0),
                availability,
                requestable: season_requestable(availability, request_status),
            })
        })
        .collect()
}

fn movie_can_request(availability: SeerrAvailability) -> bool {
    availability == SeerrAvailability::NotRequested
}

fn tv_can_request(seasons: &[SeerrSeasonStatus]) -> bool {
    seasons.iter().any(|s| s.requestable)
}

/// The caller's own currently-active (pending or approved) request, if any
/// -- Cancel-request only makes sense for one the caller can cancel.
fn active_request_for(
    media_info: Option<&seerr_api::MediaInfo>,
    current_user_id: i64,
) -> Option<SeerrActiveRequest> {
    let info = media_info?;
    info.requests
        .iter()
        .filter(|r| r.requested_by.as_ref().map(|u| u.id) == Some(current_user_id))
        .filter_map(|r| request_status_from_int(r.status).map(|status| (r, status)))
        .filter(|(_, status)| {
            matches!(
                status,
                SeerrRequestStatus::Pending | SeerrRequestStatus::Approved
            )
        })
        .max_by_key(|(_, status)| request_status_rank(*status))
        .map(|(r, status)| SeerrActiveRequest {
            request_id: r.id,
            status,
            is_4k: r.is4k,
            seasons: r.seasons.iter().filter_map(|s| s.season_number).collect(),
        })
}

enum RequestAction {
    Create,
    Update { request_id: i64 },
}

/// POST-vs-PUT: if the caller already has a PENDING request for this item
/// at the same `is_4k` flavor, update it (`PUT`) instead of duplicating.
fn decide_request_action(
    existing_requests: &[seerr_api::MediaRequest],
    is_4k: bool,
    current_user_id: i64,
) -> RequestAction {
    existing_requests
        .iter()
        .find(|r| {
            r.is4k == is_4k
                && r.status == 1
                && r.requested_by.as_ref().map(|u| u.id) == Some(current_user_id)
        })
        .map(|r| RequestAction::Update { request_id: r.id })
        .unwrap_or(RequestAction::Create)
}

fn year_from_date_str(date: &str) -> Option<i32> {
    date.get(0..4)?.parse().ok()
}

/// docs/14's "Image URLs": `poster` at `w500`, `backdrop` at
/// `w1920_and_h1080_multi_faces`, base swapped when `cache_images` is set.
#[derive(Debug, Clone, Copy)]
struct ImageContext<'a> {
    seerr_url: &'a str,
    cache_images: bool,
}

fn build_image_url(
    path: Option<&str>,
    size_segment: &str,
    images: ImageContext<'_>,
) -> Option<String> {
    let path = path.filter(|p| !p.is_empty())?;
    let base = if images.cache_images {
        format!("{}/imageproxy/tmdb", images.seerr_url.trim_end_matches('/'))
    } else {
        "https://image.tmdb.org".to_string()
    };
    Some(format!("{base}{size_segment}{path}"))
}

fn poster_url(path: Option<&str>, images: ImageContext<'_>) -> Option<String> {
    build_image_url(path, "/t/p/w500", images)
}

fn backdrop_url(path: Option<&str>, images: ImageContext<'_>) -> Option<String> {
    build_image_url(path, "/t/p/w1920_and_h1080_multi_faces", images)
}

/// Profile pictures ride the same `w500` prefix as posters -- TMDB/Seerr
/// don't expose a distinct profile-picture size in this app's contract.
fn profile_url(path: Option<&str>, images: ImageContext<'_>) -> Option<String> {
    build_image_url(path, "/t/p/w500", images)
}

fn media_type_from_str(value: Option<&str>) -> Option<SeerrMediaType> {
    match value {
        Some("movie") => Some(SeerrMediaType::Movie),
        Some("tv") => Some(SeerrMediaType::Tv),
        _ => None,
    }
}

/// Per-source fields feeding [`build_card`] -- the one piece each
/// `card_from_*` adapter below actually differs on.
struct CardSource<'a> {
    tmdb_id: i64,
    title: Option<&'a str>,
    fallback_title: Option<&'a str>,
    date: Option<&'a str>,
    fallback_date: Option<&'a str>,
    overview: Option<&'a str>,
    poster_path: Option<&'a str>,
    backdrop_path: Option<&'a str>,
    media_info: Option<&'a seerr_api::MediaInfo>,
}

/// Shared builder behind all five `card_from_*` adapters: title/year fall
/// back from a primary to a secondary field (movie vs. TV naming), then
/// poster/backdrop/availability/jellyfin-id are derived identically
/// regardless of which vendored DTO the fields came from.
fn build_card(
    media_type: SeerrMediaType,
    source: CardSource<'_>,
    images: ImageContext<'_>,
) -> SeerrCard {
    let title = source
        .title
        .or(source.fallback_title)
        .unwrap_or_default()
        .to_string();
    let year = source
        .date
        .or(source.fallback_date)
        .and_then(year_from_date_str);
    SeerrCard {
        media_type,
        tmdb_id: source.tmdb_id,
        title,
        year,
        overview: source.overview.map(str::to_string),
        poster_url: poster_url(source.poster_path, images),
        backdrop_url: backdrop_url(source.backdrop_path, images),
        availability: availability_from_status(source.media_info.and_then(|m| m.status)),
        jellyfin_item_id: jellyfin_item_id_from(source.media_info),
    }
}

fn card_from_result(
    item: &seerr_api::MediaResult,
    media_type: SeerrMediaType,
    images: ImageContext<'_>,
) -> SeerrCard {
    build_card(
        media_type,
        CardSource {
            tmdb_id: item.id,
            title: item.title.as_deref(),
            fallback_title: item.name.as_deref(),
            date: item.release_date.as_deref(),
            fallback_date: item.first_air_date.as_deref(),
            overview: item.overview.as_deref(),
            poster_path: item.poster_path.as_deref(),
            backdrop_path: item.backdrop_path.as_deref(),
            media_info: item.media_info.as_ref(),
        },
        images,
    )
}

/// A list endpoint scoped to one known kind -- `media_type` is supplied by
/// the caller since vendored schemas don't reliably carry their own.
fn cards_from_results(
    items: &[seerr_api::MediaResult],
    media_type: SeerrMediaType,
    images: ImageContext<'_>,
) -> Vec<SeerrCard> {
    items
        .iter()
        .map(|item| card_from_result(item, media_type, images))
        .collect()
}

/// A mixed-kind list endpoint: each item's own `mediaType` decides Movie
/// vs TV; a Person hit is dropped -- no Person variant to route it into.
fn cards_from_mixed_results(
    items: &[seerr_api::MediaResult],
    images: ImageContext<'_>,
) -> Vec<SeerrCard> {
    items
        .iter()
        .filter_map(|item| {
            let media_type = media_type_from_str(item.media_type.as_deref())?;
            Some(card_from_result(item, media_type, images))
        })
        .collect()
}

fn card_from_movie_details(
    details: &seerr_api::MovieDetails,
    images: ImageContext<'_>,
) -> SeerrCard {
    build_card(
        SeerrMediaType::Movie,
        CardSource {
            tmdb_id: details.id,
            title: details.title.as_deref(),
            fallback_title: None,
            date: details.release_date.as_deref(),
            fallback_date: None,
            overview: details.overview.as_deref(),
            poster_path: details.poster_path.as_deref(),
            backdrop_path: details.backdrop_path.as_deref(),
            media_info: details.media_info.as_ref(),
        },
        images,
    )
}

fn card_from_tv_details(details: &seerr_api::TvDetails, images: ImageContext<'_>) -> SeerrCard {
    build_card(
        SeerrMediaType::Tv,
        CardSource {
            tmdb_id: details.id,
            title: details.name.as_deref(),
            fallback_title: None,
            date: details.first_air_date.as_deref(),
            fallback_date: None,
            overview: details.overview.as_deref(),
            poster_path: details.poster_path.as_deref(),
            backdrop_path: details.backdrop_path.as_deref(),
            media_info: details.media_info.as_ref(),
        },
        images,
    )
}

/// Discover's per-kind cap on a person's credits (docs/14).
const PERSON_CREDITS_PER_KIND: usize = 25;

/// A person's credits as distinct cards, cast then crew, at most `per_kind` of each (docs/14).
fn credit_cards<C, R>(
    cast: &[C],
    crew: &[R],
    images: ImageContext<'_>,
    per_kind: usize,
) -> Vec<SeerrCard>
where
    C: std::ops::Deref<Target = seerr_api::MediaResult>,
    R: std::ops::Deref<Target = seerr_api::MediaResult>,
{
    let mut seen = std::collections::HashSet::new();
    let mut combined: Vec<SeerrCard> = Vec::new();
    let mut push_distinct = |card: SeerrCard, taken: &mut usize| {
        if seen.insert((
            matches!(card.media_type, SeerrMediaType::Movie),
            card.tmdb_id,
        )) {
            combined.push(card);
            *taken += 1;
        }
    };
    let mut taken = 0;
    for credit in cast {
        if taken == per_kind {
            break;
        }
        if let Some(media_type) = media_type_from_str(credit.media_type.as_deref()) {
            push_distinct(card_from_credit(credit, media_type, images), &mut taken);
        }
    }
    taken = 0;
    for credit in crew {
        if taken == per_kind {
            break;
        }
        if let Some(media_type) = media_type_from_str(credit.media_type.as_deref()) {
            push_distinct(card_from_credit(credit, media_type, images), &mut taken);
        }
    }
    combined
}

impl JellybeamCore {
    /// [`Self::seerr_person`]'s credits alone and uncapped: one request, no person-details
    /// dependency; the library person page caps after removing owned titles (docs/11 §Person page).
    pub(crate) fn seerr_person_credit_cards(
        &self,
        person_id: i64,
    ) -> Result<Vec<SeerrCard>, CoreError> {
        let handle = self.require_seerr_handle()?;
        let credits = self
            .runtime()
            .block_on(handle.client().person_combined_credits(person_id))?;
        Ok(credit_cards(
            &credits.cast,
            &credits.crew,
            handle.images(),
            usize::MAX,
        ))
    }
}

/// `CreditCast`/`CreditCrew` both `Deref` to the shared `MediaResult` fields
/// (seerr-api), so one card builder covers both.
fn card_from_credit(
    credit: &seerr_api::MediaResult,
    media_type: SeerrMediaType,
    images: ImageContext<'_>,
) -> SeerrCard {
    build_card(
        media_type,
        CardSource {
            tmdb_id: credit.id,
            title: credit.title.as_deref(),
            fallback_title: credit.name.as_deref(),
            date: credit.release_date.as_deref(),
            fallback_date: credit.first_air_date.as_deref(),
            overview: credit.overview.as_deref(),
            poster_path: credit.poster_path.as_deref(),
            backdrop_path: credit.backdrop_path.as_deref(),
            media_info: credit.media_info.as_ref(),
        },
        images,
    )
}

fn person_ref_from_cast(
    credit: &seerr_api::CreditCast,
    images: ImageContext<'_>,
) -> SeerrPersonRef {
    SeerrPersonRef {
        person_id: credit.id,
        name: credit.name.clone().unwrap_or_default(),
        role: credit.character.clone(),
        profile_url: profile_url(credit.profile_path.as_deref(), images),
    }
}

/// `seerr_movie`/`seerr_tv`'s shared genre mapping (`seerr_api::Genre` is not
/// re-exported, so this takes the fields rather than the type).
fn seerr_genre(id: i64, name: Option<String>) -> SeerrGenre {
    SeerrGenre {
        id,
        name: name.unwrap_or_default(),
    }
}

/// `seerr_movie`/`seerr_tv`'s shared cast list: `credits.cast`, or empty
/// when the detail fetch carried no `credits` block.
fn cast_from_credits(
    cast: &[seerr_api::CreditCast],
    images: ImageContext<'_>,
) -> Vec<SeerrPersonRef> {
    cast.iter()
        .map(|c| person_ref_from_cast(c, images))
        .collect()
}

/// `seerr_movie`/`seerr_tv`'s shared ratings mapping: a 404 (`None`) or
/// transport error both fold into "no score," never surfaced as an error
/// (`seerr_api::Ratings` is not re-exported, so this takes the fields
/// already read out of it rather than the type).
fn scores_from_ratings(raw: Option<(Option<i64>, Option<i64>)>) -> (Option<i32>, Option<i32>) {
    raw.map(|(critics, audience)| {
        (
            critics.and_then(|v| i32::try_from(v).ok()),
            audience.and_then(|v| i32::try_from(v).ok()),
        )
    })
    .unwrap_or((None, None))
}

fn trailer_url_from(videos: &[seerr_api::RelatedVideo]) -> Option<String> {
    videos
        .iter()
        .find(|v| v.kind.as_deref() == Some("Trailer"))
        .and_then(|v| v.url.clone())
}

/// The movie detail from one `GET /movie/{id}`; `similar`/`recommendations`/`scores` come from
/// the sibling fetches [`JellybeamCore::seerr_movie`] makes and are empty/`None` on the
/// single-GET mutation path ([`SeerrRequestDetail`]).
fn movie_detail_from(
    handle: &SeerrHandle,
    details: &seerr_api::MovieDetails,
    similar: Vec<SeerrCard>,
    recommendations: Vec<SeerrCard>,
    (critics_score, audience_score): (Option<i32>, Option<i32>),
) -> SeerrMovieDetail {
    let images = handle.images();
    let card = card_from_movie_details(details, images);
    let can_request = movie_can_request(card.availability);
    SeerrMovieDetail {
        runtime_minutes: details.runtime.map(|r| r.round() as i32),
        genres: details
            .genres
            .iter()
            .map(|g| seerr_genre(g.id, g.name.clone()))
            .collect(),
        cast: details
            .credits
            .as_ref()
            .map_or_else(Vec::new, |c| cast_from_credits(&c.cast, images)),
        similar,
        recommendations,
        trailer_url: trailer_url_from(&details.related_videos),
        critics_score,
        audience_score,
        active_request: active_request_for(details.media_info.as_ref(), handle.user_id()),
        can_request,
        can_request_4k: can_request && handle.movie4k_enabled(),
        card,
    }
}

/// [`movie_detail_from`]'s TV counterpart; `seasons` reflects SD availability only -- see
/// [`build_season_statuses`].
fn tv_detail_from(
    handle: &SeerrHandle,
    details: &seerr_api::TvDetails,
    similar: Vec<SeerrCard>,
    recommendations: Vec<SeerrCard>,
    (critics_score, audience_score): (Option<i32>, Option<i32>),
) -> SeerrTvDetail {
    let images = handle.images();
    let card = card_from_tv_details(details, images);
    let seasons = build_season_statuses(&details.seasons, details.media_info.as_ref());
    let can_request = tv_can_request(&seasons);
    SeerrTvDetail {
        genres: details
            .genres
            .iter()
            .map(|g| seerr_genre(g.id, g.name.clone()))
            .collect(),
        cast: details
            .credits
            .as_ref()
            .map_or_else(Vec::new, |c| cast_from_credits(&c.cast, images)),
        similar,
        recommendations,
        trailer_url: trailer_url_from(&details.related_videos),
        critics_score,
        audience_score,
        active_request: active_request_for(details.media_info.as_ref(), handle.user_id()),
        can_request,
        // docs/14's FFI contract exposes exactly one (SD) season
        // list, so `can_request_4k` reuses the SD requestability
        // signal, gated by the server's own `series4kEnabled` toggle.
        can_request_4k: can_request && handle.series4k_enabled(),
        seasons,
        card,
    }
}

/// The season list a TV `PUT /request/{id}` must carry: Seerr replaces the request's seasons
/// with the body's, so the request's existing seasons ride along with the newly asked ones
/// (docs/14 Request semantics). Sorted and de-duplicated.
fn merged_seasons(existing: &[seerr_api::Season], requested: &[i32]) -> Vec<i32> {
    let mut seasons: Vec<i32> = existing
        .iter()
        .filter_map(|s| s.season_number)
        .chain(requested.iter().copied())
        .collect();
    seasons.sort_unstable();
    seasons.dedup();
    seasons
}

// --- JellybeamCore private helpers ---------------------------------------------

impl JellybeamCore {
    /// Caches `handle` as the live connection only while `epoch`, captured with the identity
    /// the network work began under, is still the installed account's (docs/18 §2.1); a late
    /// completion would otherwise refill the slot a swap cleared with the previous account's
    /// session. Returns whether the handle was cached.
    pub(crate) fn cache_seerr_handle_if_active(&self, epoch: u64, handle: SeerrHandle) -> bool {
        let cached = self.store_if_epoch(epoch, |caches| caches.seerr = Some(handle));
        if !cached {
            tracing::info!("seerr session completed after an account switch; dropped");
        }
        cached
    }

    /// Returns the cached handle, or builds one from stored config (a
    /// network round trip -- the exception to `seerr_status`'s zero-network
    /// contract). `Err(SeerrNotConfigured)` when there's no saved connection.
    pub(crate) fn require_seerr_handle(&self) -> Result<SeerrHandle, CoreError> {
        let cached = self
            .lock_state()
            .account
            .installed()
            .and_then(|i| i.caches.seerr.clone());
        if let Some(handle) = cached {
            return Ok(handle);
        }
        let identity = self
            .installed_identity()
            .ok_or(CoreError::SeerrNotConfigured)?;
        let entry = load_config(self.data_dir())
            .find(&identity.account.server_url, &identity.account.user_id)
            .cloned()
            .ok_or(CoreError::SeerrNotConfigured)?;
        let intent = self.seerr_config().current();
        let handle = self.runtime().block_on(build_handle(&entry))?;
        self.cache_rebuilt_seerr_handle(intent, identity.epoch, handle)
    }

    /// Caches a lazily rebuilt handle only if no connect or disconnect began
    /// meanwhile; otherwise the later intent's outcome stands.
    fn cache_rebuilt_seerr_handle(
        &self,
        intent: u64,
        epoch: u64,
        handle: SeerrHandle,
    ) -> Result<SeerrHandle, CoreError> {
        let cached = self
            .seerr_config()
            .commit(intent, || {
                self.cache_seerr_handle_if_active(epoch, handle.clone())
                    .then_some(handle)
            })
            .flatten();
        match cached {
            Some(handle) => Ok(handle),
            // docs/18 §2.1: only the cache of the account the rebuild began under may answer.
            None => self
                .read_if_epoch(epoch, |caches| caches.seerr.clone())
                .flatten()
                .ok_or(CoreError::SeerrNotConfigured),
        }
    }

    fn submit_request_in(
        &self,
        handle: &SeerrHandle,
        input: SeerrRequestInput,
    ) -> Result<(), CoreError> {
        let client = handle.client();
        let media_type_str: &'static str = match input.media_type {
            SeerrMediaType::Movie => "movie",
            SeerrMediaType::Tv => "tv",
        };
        let seasons = if input.seasons.is_empty() {
            None
        } else {
            Some(input.seasons.clone())
        };

        let existing_requests = match input.media_type {
            SeerrMediaType::Movie => self
                .runtime()
                .block_on(client.movie_details(input.tmdb_id))?
                .media_info
                .map(|m| m.requests)
                .unwrap_or_default(),
            SeerrMediaType::Tv => self
                .runtime()
                .block_on(client.tv_details(input.tmdb_id))?
                .media_info
                .map(|m| m.requests)
                .unwrap_or_default(),
        };
        let action = decide_request_action(&existing_requests, input.is_4k, handle.user_id());

        match action {
            RequestAction::Update { request_id } => {
                // PUT replaces the request's season list, so the seasons it already holds ride along.
                let seasons = seasons.map(|requested| {
                    let held = existing_requests
                        .iter()
                        .find(|r| r.id == request_id)
                        .map_or(&[][..], |r| r.seasons.as_slice());
                    merged_seasons(held, &requested)
                });
                self.runtime().block_on(client.request_update(
                    request_id,
                    seerr_api::RequestUpdateBody {
                        media_type: media_type_str,
                        seasons,
                        is4k: input.is_4k,
                        server_id: input.server_id,
                        profile_id: input.profile_id,
                        root_folder: input.root_folder,
                    },
                ))?;
            }
            RequestAction::Create => {
                self.runtime()
                    .block_on(client.request_create(seerr_api::RequestCreateBody {
                        media_type: media_type_str,
                        media_id: input.tmdb_id,
                        seasons,
                        is4k: input.is_4k,
                        server_id: input.server_id,
                        profile_id: input.profile_id,
                        root_folder: input.root_folder,
                    }))?;
            }
        }
        // The submitted item's availability badge just changed; a stale
        // 60s-cached home snapshot would keep showing its old state.
        handle.invalidate_home();
        Ok(())
    }

    fn cancel_request_in(&self, handle: &SeerrHandle, request_id: i64) -> Result<(), CoreError> {
        self.runtime()
            .block_on(handle.client().request_delete(request_id))?;
        handle.invalidate_home();
        Ok(())
    }

    /// The post-mutation detail: one GET, no sibling fetches (see [`SeerrRequestDetail`]).
    fn request_detail_in(
        &self,
        handle: &SeerrHandle,
        media_type: SeerrMediaType,
        tmdb_id: i64,
    ) -> Result<SeerrRequestDetail, CoreError> {
        let client = handle.client();
        Ok(match media_type {
            SeerrMediaType::Movie => {
                let details = self.runtime().block_on(client.movie_details(tmdb_id))?;
                SeerrRequestDetail::Movie {
                    detail: movie_detail_from(
                        handle,
                        &details,
                        Vec::new(),
                        Vec::new(),
                        (None, None),
                    ),
                }
            }
            SeerrMediaType::Tv => {
                let details = self.runtime().block_on(client.tv_details(tmdb_id))?;
                SeerrRequestDetail::Tv {
                    detail: tv_detail_from(handle, &details, Vec::new(), Vec::new(), (None, None)),
                }
            }
        })
    }

    /// [`Self::request_detail_in`] after a mutation that landed: an unreadable detail is
    /// [`CoreError::SeerrChangeUnconfirmed`], so the caller still sees that the server changed.
    fn changed_request_detail(
        &self,
        handle: &SeerrHandle,
        media_type: SeerrMediaType,
        tmdb_id: i64,
    ) -> Result<SeerrRequestDetail, CoreError> {
        self.request_detail_in(handle, media_type, tmdb_id)
            .map_err(|e| {
                tracing::warn!(error = %e, "a seerr change landed but its detail could not be read");
                CoreError::SeerrChangeUnconfirmed
            })
    }

    /// Drops the saved Seerr connection (secret included) of an account that left the saved list
    /// (docs/14 Per-account config). Called after the session list commit; the intent bump also
    /// refuses a connect for that account still in flight.
    pub(crate) fn prune_seerr_entry(&self, server_url: &str, user_id: &str) {
        self.seerr_config()
            .apply(|| self.remove_seerr_entry(server_url, user_id));
    }

    /// Drops the saved entry of `(server_url, user_id)`; the caller holds the config intent lock.
    fn remove_seerr_entry(&self, server_url: &str, user_id: &str) {
        let mut config = load_config(self.data_dir());
        config.remove(server_url, user_id);
        if let Err(e) = save_config(self.data_dir(), &config) {
            tracing::warn!(error = %e, "failed to persist seerr config after removing an entry");
        }
    }

    /// Persists `entry` and caches `handle` unless a later connect or
    /// disconnect began after `intent`; the newer intent wins.
    fn commit_seerr_connect(
        &self,
        intent: u64,
        epoch: u64,
        entry: SeerrConfigEntry,
        handle: SeerrHandle,
    ) -> Result<(), CoreError> {
        self.seerr_config()
            .commit(intent, || {
                let mut config = load_config(self.data_dir());
                config.upsert(entry);
                save_config(self.data_dir(), &config).map_err(|e| CoreError::Cache {
                    detail: format!("failed to persist seerr config: {e}"),
                })?;
                self.cache_seerr_handle_if_active(epoch, handle);
                Ok(())
            })
            .unwrap_or(Err(CoreError::Api {
                detail: "Seerr connection was replaced by a newer change".to_string(),
            }))
    }
}

/// Longest Seerr error message carried into [`CoreError::SeerrSignInRefused`].
const SEERR_REFUSAL_DETAIL_CAP: usize = 200;

/// docs/14-seerr-discover.md "Auth": a login answered with Seerr's own JSON
/// error shape (`message`/`error`) proves the candidate is the server, so the
/// body picks the copy; a bare 401/403 (a proxy, an older build) still reads
/// as wrong credentials, and any other body is not Seerr, so probing goes on.
fn classify_seerr_refusal(method: SeerrAuthMethod, code: u16, body: &str) -> Option<CoreError> {
    let text: Option<String> = serde_json::from_str::<serde_json::Value>(body)
        .ok()
        .and_then(|v| {
            v.get("message")
                .or_else(|| v.get("error"))
                .and_then(|m| m.as_str())
                .map(|m| m.chars().take(SEERR_REFUSAL_DETAIL_CAP).collect())
        });
    let reason = match (code, text.as_deref()) {
        (401, _) | (_, Some("INVALID_CREDENTIALS")) => {
            return Some(CoreError::SeerrInvalidCredentials { method });
        }
        (_, Some("INVALID_URL")) => SeerrRefusal::MediaServerSignIn,
        (403, Some("Access denied.")) if method == SeerrAuthMethod::Jellyfin => {
            SeerrRefusal::NewUsersBlocked
        }
        (403, _) => return Some(CoreError::SeerrInvalidCredentials { method }),
        (_, Some(t)) if t.contains("disabled") => SeerrRefusal::MethodDisabled,
        (_, Some(_)) => SeerrRefusal::Other,
        (_, None) => return None,
    };
    Some(CoreError::SeerrSignInRefused {
        method,
        reason,
        detail: text.unwrap_or_default(),
    })
}

// --- FFI surface -------------------------------------------------------------

#[uniffi::export]
impl JellybeamCore {
    /// Local-file-only read (zero network): whether Discover is
    /// configured (docs/14's "zero startup cost" rule).
    pub fn seerr_status(&self) -> SeerrStatus {
        let not_configured = SeerrStatus {
            configured: false,
            seerr_url: None,
            method: None,
            identity: None,
            app_title: None,
        };
        // One lock, so the identity and the cached handle belong to the same account.
        let (identity, app_title) = {
            let state = self.lock_state();
            let Some(identity) = Self::installed_identity_locked(&state) else {
                return not_configured;
            };
            let app_title = state
                .account
                .installed()
                .and_then(|i| i.caches.seerr.as_ref())
                .and_then(SeerrHandle::app_title);
            (identity, app_title)
        };
        let config = load_config(self.data_dir());
        let Some(entry) = config.find(&identity.account.server_url, &identity.account.user_id)
        else {
            return not_configured;
        };
        SeerrStatus {
            configured: true,
            seerr_url: Some(entry.seerr_url.clone()),
            method: Some(entry.method),
            identity: Some(entry.identity.clone()),
            app_title,
        }
    }

    /// Expands `url` into candidates, tries a real login on each (tight
    /// timeouts) until one succeeds, then saves the connection and caches
    /// `/settings/public` flags. Nothing is saved on failure.
    pub fn seerr_connect(
        &self,
        url: String,
        method: SeerrAuthMethod,
        identity: String,
        secret: String,
    ) -> Result<SeerrStatus, CoreError> {
        let installed = self.installed_identity().ok_or(CoreError::NotSignedIn)?;
        let intent = self.seerr_config().begin();

        let candidates = seerr_api::url::candidate_urls(&url);
        if candidates.is_empty() {
            return Err(CoreError::Api {
                detail: "enter a Seerr server address".to_string(),
            });
        }

        let seerr_method: seerr_api::SeerrAuthMethod = method.into();
        let (probe_connect_timeout, probe_request_timeout) = probe_timeouts();
        let mut last_error: Option<CoreError> = None;
        let mut connected: Option<(seerr_api::SeerrClient, seerr_api::User, String)> = None;
        for candidate in &candidates {
            let attempt =
                self.runtime()
                    .block_on(seerr_api::SeerrClient::login(seerr_api::LoginArgs {
                        base_url: candidate,
                        method: seerr_method,
                        identity: &identity,
                        secret: &secret,
                        connect_timeout: probe_connect_timeout,
                        request_timeout: probe_request_timeout,
                    }));
            match attempt {
                Ok((client, user)) => {
                    connected = Some((client, user, seerr_api::url::normalize_api_base(candidate)));
                    break;
                }
                // A refusal means this candidate is the server: stop probing, so a
                // later candidate's transport failure cannot hide the real answer.
                Err(seerr_api::SeerrError::Unauthorized) => {
                    return Err(CoreError::SeerrInvalidCredentials { method });
                }
                Err(seerr_api::SeerrError::Status { code, body }) => {
                    if let Some(refusal) = classify_seerr_refusal(method, code, &body) {
                        return Err(refusal);
                    }
                    last_error = Some(seerr_api::SeerrError::Status { code, body }.into());
                }
                Err(e) => last_error = Some(e.into()),
            }
        }
        let (client, user, seerr_url) = connected.ok_or_else(|| {
            last_error.unwrap_or(CoreError::SeerrUnreachable {
                reason: UnreachableReason::Other,
            })
        })?;

        // docs/14 "Auth": the tight probe timeouts end with the probe; the saved connection
        // runs on the normal ones, and its settings read is capped on top of them.
        let client = client.with_timeouts(NORMAL_CONNECT_TIMEOUT, NORMAL_REQUEST_TIMEOUT)?;
        let public = self.runtime().block_on(public_settings_or_default(&client));
        let handle = SeerrHandle::new(client, user.id, &seerr_url, public);
        let app_title = handle.app_title();

        let entry = SeerrConfigEntry {
            server_url: installed.account.server_url,
            user_id: installed.account.user_id,
            seerr_url: seerr_url.clone(),
            method,
            identity,
            secret,
        };
        self.commit_seerr_connect(intent, installed.epoch, entry.clone(), handle)?;

        Ok(SeerrStatus {
            configured: true,
            seerr_url: Some(entry.seerr_url),
            method: Some(method),
            identity: Some(entry.identity),
            app_title,
        })
    }

    /// Removes the active account's saved Seerr connection and drops the
    /// live handle. Best-effort, like `remove_session`.
    pub fn seerr_disconnect(&self) {
        let Some(identity) = self.installed_identity() else {
            return;
        };
        self.seerr_config().apply(|| {
            self.remove_seerr_entry(&identity.account.server_url, &identity.account.user_id);
            self.store_if_epoch(identity.epoch, |caches| caches.seerr = None);
        });
    }

    /// Trending/Movies/TV/Upcoming rows, fetched concurrently; a failed row
    /// is dropped, all rows failing is the first row's error. Cached 60s.
    pub fn seerr_home(&self) -> Result<SeerrHome, CoreError> {
        let handle = self.require_seerr_handle()?;
        if let Some(cached) = handle.cached_home() {
            return Ok(cached);
        }
        let generation = handle.home_generation();
        let client = handle.client();
        let no_filters = seerr_api::BrowseFilters::default();
        let (trending, movies, tv, upcoming_movies, upcoming_tv) = self.runtime().block_on(async {
            tokio::join!(
                client.discover_trending(1),
                client.discover_movies(1, &no_filters),
                client.discover_tv(1, &no_filters),
                client.discover_movies_upcoming(1),
                client.discover_tv_upcoming(1),
            )
        });
        let images = handle.images();
        let outcomes = [
            (
                "trending",
                "Trending",
                trending.map(|page| cards_from_mixed_results(&page.results, images)),
            ),
            (
                "movies",
                "Movies",
                movies.map(|page| cards_from_results(&page.results, SeerrMediaType::Movie, images)),
            ),
            (
                "tv",
                "TV",
                tv.map(|page| cards_from_results(&page.results, SeerrMediaType::Tv, images)),
            ),
            (
                "upcoming_movies",
                "Upcoming Movies",
                upcoming_movies
                    .map(|page| cards_from_results(&page.results, SeerrMediaType::Movie, images)),
            ),
            (
                "upcoming_tv",
                "Upcoming TV",
                upcoming_tv
                    .map(|page| cards_from_results(&page.results, SeerrMediaType::Tv, images)),
            ),
        ];
        let mut rows = Vec::new();
        let mut first_error = None;
        for (id, title, outcome) in outcomes {
            match outcome {
                Ok(cards) => rows.push(SeerrHomeRow {
                    id: id.to_string(),
                    title: title.to_string(),
                    cards,
                }),
                Err(error) => {
                    first_error.get_or_insert(error);
                }
            }
        }
        // docs/14 Performance: a home with no row at all is an error the UI can retry, and is
        // never cached; any loaded row is shown.
        if rows.is_empty() {
            if let Some(error) = first_error {
                return Err(error.into());
            }
        }
        let home = SeerrHome { rows };
        handle.cache_home(generation, home.clone());
        Ok(home)
    }

    pub fn seerr_browse(
        &self,
        kind: SeerrBrowseKind,
        page: i32,
        filters: SeerrBrowseFilters,
    ) -> Result<SeerrPage, CoreError> {
        let handle = self.require_seerr_handle()?;
        let client = handle.client();
        let images = handle.images();
        let page_i64 = i64::from(page);
        let seerr_filters: seerr_api::BrowseFilters = filters.into();

        let (cards, page_no, total_pages, total_results) = match kind {
            SeerrBrowseKind::Trending => {
                let result = self
                    .runtime()
                    .block_on(client.discover_trending(page_i64))?;
                (
                    cards_from_mixed_results(&result.results, images),
                    result.page,
                    result.total_pages,
                    result.total_results,
                )
            }
            SeerrBrowseKind::Movies => {
                let result = self
                    .runtime()
                    .block_on(client.discover_movies(page_i64, &seerr_filters))?;
                (
                    cards_from_results(&result.results, SeerrMediaType::Movie, images),
                    result.page,
                    result.total_pages,
                    result.total_results,
                )
            }
            SeerrBrowseKind::Tv => {
                let result = self
                    .runtime()
                    .block_on(client.discover_tv(page_i64, &seerr_filters))?;
                (
                    cards_from_results(&result.results, SeerrMediaType::Tv, images),
                    result.page,
                    result.total_pages,
                    result.total_results,
                )
            }
            SeerrBrowseKind::UpcomingMovies => {
                let result = self
                    .runtime()
                    .block_on(client.discover_movies_upcoming(page_i64))?;
                (
                    cards_from_results(&result.results, SeerrMediaType::Movie, images),
                    result.page,
                    result.total_pages,
                    result.total_results,
                )
            }
            SeerrBrowseKind::UpcomingTv => {
                let result = self
                    .runtime()
                    .block_on(client.discover_tv_upcoming(page_i64))?;
                (
                    cards_from_results(&result.results, SeerrMediaType::Tv, images),
                    result.page,
                    result.total_pages,
                    result.total_results,
                )
            }
        };

        Ok(SeerrPage {
            cards,
            page: page_no,
            total_pages,
            total_results,
        })
    }

    /// Process-lifetime cache -- genre lists never change within one run.
    pub fn seerr_genres(&self, media_type: SeerrMediaType) -> Result<Vec<SeerrGenre>, CoreError> {
        let handle = self.require_seerr_handle()?;
        if let Some(cached) = handle.cached_genres(media_type) {
            return Ok(cached);
        }
        let client = handle.client();
        let raw = if matches!(media_type, SeerrMediaType::Movie) {
            self.runtime().block_on(client.genres_movie())
        } else {
            self.runtime().block_on(client.genres_tv())
        }?;
        let genres: Vec<SeerrGenre> = raw
            .into_iter()
            .map(|g| SeerrGenre {
                id: g.id,
                name: g.name.unwrap_or_default(),
            })
            .collect();
        handle.cache_genres(media_type, genres.clone());
        Ok(genres)
    }

    pub fn seerr_search(&self, query: String, page: i32) -> Result<SeerrPage, CoreError> {
        let handle = self.require_seerr_handle()?;
        let result = self
            .runtime()
            .block_on(handle.client().search(&query, i64::from(page)))?;
        let images = handle.images();
        Ok(SeerrPage {
            cards: cards_from_mixed_results(&result.results, images),
            page: result.page,
            total_pages: result.total_pages,
            total_results: result.total_results,
        })
    }

    /// Detail + similar + recommendations + ratings fetched concurrently;
    /// only the primary fetch is load-bearing, the rest fail open. Ratings
    /// 404s map to `None`, never an error.
    pub fn seerr_movie(&self, tmdb_id: i64) -> Result<SeerrMovieDetail, CoreError> {
        let handle = self.require_seerr_handle()?;
        let client = handle.client();
        let (details, similar, recommendations, ratings) = self.runtime().block_on(async {
            tokio::join!(
                client.movie_details(tmdb_id),
                client.movie_similar(tmdb_id, 1),
                client.movie_recommendations(tmdb_id, 1),
                client.movie_ratings(tmdb_id),
            )
        });
        let details = details?;
        let images = handle.images();
        let scores = scores_from_ratings(
            ratings
                .ok()
                .flatten()
                .map(|r| (r.critics_score, r.audience_score)),
        );
        Ok(movie_detail_from(
            &handle,
            &details,
            similar
                .map(|p| cards_from_results(&p.results, SeerrMediaType::Movie, images))
                .unwrap_or_default(),
            recommendations
                .map(|p| cards_from_results(&p.results, SeerrMediaType::Movie, images))
                .unwrap_or_default(),
            scores,
        ))
    }

    /// Same concurrency/fail-open shape as [`Self::seerr_movie`]. `seasons`
    /// reflects SD availability only -- see [`build_season_statuses`] and
    /// `can_request_4k`'s doc comment for the 4K gate.
    pub fn seerr_tv(&self, tmdb_id: i64) -> Result<SeerrTvDetail, CoreError> {
        let handle = self.require_seerr_handle()?;
        let client = handle.client();
        let (details, similar, recommendations, ratings) = self.runtime().block_on(async {
            tokio::join!(
                client.tv_details(tmdb_id),
                client.tv_similar(tmdb_id, 1),
                client.tv_recommendations(tmdb_id, 1),
                client.tv_ratings(tmdb_id),
            )
        });
        let details = details?;
        let images = handle.images();
        let scores = scores_from_ratings(
            ratings
                .ok()
                .flatten()
                .map(|r| (r.critics_score, r.audience_score)),
        );
        Ok(tv_detail_from(
            &handle,
            &details,
            similar
                .map(|p| cards_from_results(&p.results, SeerrMediaType::Tv, images))
                .unwrap_or_default(),
            recommendations
                .map(|p| cards_from_results(&p.results, SeerrMediaType::Tv, images))
                .unwrap_or_default(),
            scores,
        ))
    }

    /// Cast + crew, each capped at 25 distinct titles after de-duplication across the two
    /// (docs/14's contract section): a title credited in both lists takes one slot, not two.
    pub fn seerr_person(&self, person_id: i64) -> Result<SeerrPersonCredits, CoreError> {
        let handle = self.require_seerr_handle()?;
        let client = handle.client();
        let (details, credits) = self.runtime().block_on(async {
            tokio::join!(
                client.person_details(person_id),
                client.person_combined_credits(person_id),
            )
        });
        let details = details?;
        let credits = credits.unwrap_or_default();
        let images = handle.images();
        let combined = credit_cards(
            &credits.cast,
            &credits.crew,
            images,
            PERSON_CREDITS_PER_KIND,
        );

        Ok(SeerrPersonCredits {
            name: details.name.unwrap_or_default(),
            profile_url: profile_url(details.profile_path.as_deref(), images),
            credits: combined,
        })
    }

    /// Radarr/Sonarr instances at the requested 4K flavor; empty `servers` means a plain Request
    /// button.
    pub fn seerr_request_options(
        &self,
        media_type: SeerrMediaType,
        is_4k: bool,
    ) -> Result<SeerrRequestOptions, CoreError> {
        let handle = self.require_seerr_handle()?;
        let client = handle.client().clone();
        let instances: Vec<seerr_api::ServarrInstance> =
            if matches!(media_type, SeerrMediaType::Movie) {
                self.runtime().block_on(client.service_radarr_list())
            } else {
                self.runtime().block_on(client.service_sonarr_list())
            }?
            .into_iter()
            .filter(|i| i.is4k == is_4k)
            .collect();

        // Fetch every instance's profiles/root folders concurrently in
        // one `block_on`; `join_all` preserves `instances`' order so the
        // `zip` below pairs each instance with its own detail fetch.
        let details: Vec<seerr_api::ServiceDetail> = self.runtime().block_on(async {
            futures_util::future::join_all(instances.iter().map(|instance| {
                let client = client.clone();
                let id = instance.id;
                async move {
                    if matches!(media_type, SeerrMediaType::Movie) {
                        client.service_radarr_detail(id).await
                    } else {
                        client.service_sonarr_detail(id).await
                    }
                    .unwrap_or_default()
                }
            }))
            .await
        });

        let mut servers = Vec::new();
        for (instance, detail) in instances.into_iter().zip(details) {
            let active_profile_id = detail.server.as_ref().and_then(|s| s.active_profile_id);
            let active_directory = detail
                .server
                .as_ref()
                .and_then(|s| s.active_directory.clone());

            servers.push(SeerrServiceServer {
                server_id: instance.id,
                name: instance.name.unwrap_or_default(),
                is_4k: instance.is4k,
                is_default: instance.is_default,
                profiles: detail
                    .profiles
                    .into_iter()
                    .map(|p| SeerrProfile {
                        is_default: active_profile_id == Some(p.id),
                        id: p.id,
                        name: p.name.unwrap_or_default(),
                    })
                    .collect(),
                root_folders: detail
                    .root_folders
                    .into_iter()
                    .map(|f| {
                        let path = f.path.unwrap_or_default();
                        SeerrRootFolder {
                            is_default: active_directory.as_deref() == Some(path.as_str()),
                            id: f.id,
                            path,
                        }
                    })
                    .collect(),
            });
        }
        Ok(SeerrRequestOptions { servers })
    }

    /// Movie: single request. TV: per-season, only seasons the caller marked `requestable`.
    /// Updates (`PUT`) the caller's PENDING request instead of duplicating, keeping the seasons
    /// it already holds. Pre-read GET, the mutation, then one detail GET (docs/14 Request
    /// semantics); an unreadable pre-read fails the call instead of guessing "no request", and a
    /// detail that cannot be read after the change is `SeerrChangeUnconfirmed`.
    pub fn seerr_submit_request_detail(
        &self,
        input: SeerrRequestInput,
    ) -> Result<SeerrRequestDetail, CoreError> {
        let handle = self.require_seerr_handle()?;
        let (media_type, tmdb_id) = (input.media_type, input.tmdb_id);
        self.submit_request_in(&handle, input)?;
        self.changed_request_detail(&handle, media_type, tmdb_id)
    }

    /// Cancels `request_id`, then reads the title's detail once: DELETE + one GET; an unreadable
    /// detail is `SeerrChangeUnconfirmed`.
    pub fn seerr_cancel_request_detail(
        &self,
        media_type: SeerrMediaType,
        tmdb_id: i64,
        request_id: i64,
    ) -> Result<SeerrRequestDetail, CoreError> {
        let handle = self.require_seerr_handle()?;
        self.cancel_request_in(&handle, request_id)?;
        self.changed_request_detail(&handle, media_type, tmdb_id)
    }

    /// The account's own requests (server auto-scoped); one this app can't
    /// resolve a title for is omitted, same fail-open as `seerr_home`.
    pub fn seerr_my_requests(&self) -> Result<Vec<SeerrMyRequest>, CoreError> {
        let handle = self.require_seerr_handle()?;
        let client = handle.client().clone();
        let page = self.runtime().block_on(client.my_requests(50, 0))?;
        let images = handle.images();

        // Each request's title/poster needs its own detail fetch, up to 50,
        // bounded via `StreamExt::buffered` (preserves server order).
        let resolved: Vec<Option<SeerrMyRequest>> = self.runtime().block_on(async {
            futures_util::stream::iter(page.results.into_iter().map(|request| {
                let client = client.clone();
                async move {
                    let tmdb_id = request.media.as_ref().and_then(|m| m.tmdb_id);
                    let card = match (tmdb_id, request.kind.as_deref()) {
                        (Some(id), Some("movie")) => client
                            .movie_details(id)
                            .await
                            .ok()
                            .map(|d| card_from_movie_details(&d, images)),
                        (Some(id), Some("tv")) => client
                            .tv_details(id)
                            .await
                            .ok()
                            .map(|d| card_from_tv_details(&d, images)),
                        _ => None,
                    };
                    card.map(|card| SeerrMyRequest {
                        request_id: request.id,
                        card,
                        status: request_status_for_display(request.status),
                        is_4k: request.is4k,
                        seasons: request
                            .seasons
                            .iter()
                            .filter_map(|s| s.season_number)
                            .collect(),
                        requested_by: request.requested_by.and_then(|u| u.username),
                    })
                }
            }))
            .buffered(MAX_CONCURRENT_DETAIL_FETCHES)
            .collect()
            .await
        });

        // Fail-open per item: a request this app couldn't resolve a
        // title for is dropped rather than failing the whole call.
        Ok(resolved.into_iter().flatten().collect())
    }
}

#[cfg(test)]
mod tests;
