//! FFI-facing Seerr Discover records/enums (`docs/14-seerr-discover.md`'s
//! "FFI surface" section, type list verbatim). Thin `uniffi` skins: the
//! mapping/decision logic lives in `seerr.rs` as ordinary, uniffi-free
//! functions.

/// Which of Seerr's three login mechanisms a saved account uses. Mirrors
/// `seerr_api::SeerrAuthMethod` 1:1, kept separate since that crate stays
/// free of uniffi; also `serde`-derived for direct on-disk persistence.
#[derive(
    uniffi::Enum, Debug, Clone, Copy, Default, PartialEq, Eq, serde::Serialize, serde::Deserialize,
)]
pub enum SeerrAuthMethod {
    #[default]
    Jellyfin,
    Local,
    ApiKey,
}

/// docs/14-seerr-discover.md "Auth": why Seerr itself refused a connect after
/// the address proved right, so Settings can say what to fix instead of an
/// HTTP status.
#[derive(uniffi::Enum, Debug, Clone, Copy, PartialEq, Eq)]
pub enum SeerrRefusal {
    /// Seerr could not sign in to its own media server (`INVALID_URL`):
    /// Jellyseerr 2.x against Jellyfin 12, or a wrong hostname in Seerr.
    MediaServerSignIn,
    /// The Jellyfin user is not in Seerr yet and Seerr's new-user sign-in is off.
    NewUsersBlocked,
    /// Seerr has this sign-in method switched off.
    MethodDisabled,
    /// Any other Seerr error message; `detail` carries it.
    Other,
}

impl From<SeerrAuthMethod> for seerr_api::SeerrAuthMethod {
    fn from(method: SeerrAuthMethod) -> Self {
        match method {
            SeerrAuthMethod::Jellyfin => seerr_api::SeerrAuthMethod::Jellyfin,
            SeerrAuthMethod::Local => seerr_api::SeerrAuthMethod::Local,
            SeerrAuthMethod::ApiKey => seerr_api::SeerrAuthMethod::ApiKey,
        }
    }
}

impl From<seerr_api::SeerrAuthMethod> for SeerrAuthMethod {
    fn from(method: seerr_api::SeerrAuthMethod) -> Self {
        match method {
            seerr_api::SeerrAuthMethod::Jellyfin => SeerrAuthMethod::Jellyfin,
            seerr_api::SeerrAuthMethod::Local => SeerrAuthMethod::Local,
            seerr_api::SeerrAuthMethod::ApiKey => SeerrAuthMethod::ApiKey,
        }
    }
}

/// Local-file-only read: whether Discover is configured. `app_title` fills
/// in only after `seerr_connect` cached it.
#[derive(uniffi::Record, Debug, Clone, PartialEq, Eq)]
pub struct SeerrStatus {
    pub configured: bool,
    pub seerr_url: Option<String>,
    pub method: Option<SeerrAuthMethod>,
    pub identity: Option<String>,
    pub app_title: Option<String>,
}

/// A Seerr title's kind -- movies and TV use different endpoints/season semantics.
#[derive(uniffi::Enum, Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum SeerrMediaType {
    Movie,
    Tv,
}

/// `MediaInfo.status` (1..=5); absent/unrecognized/`DELETED` folds into `NotRequested`.
#[derive(uniffi::Enum, Debug, Clone, Copy, PartialEq, Eq)]
pub enum SeerrAvailability {
    NotRequested,
    Pending,
    Processing,
    PartiallyAvailable,
    Available,
}

/// One browse/shelf tile.
#[derive(uniffi::Record, Debug, Clone, PartialEq, Eq)]
pub struct SeerrCard {
    pub media_type: SeerrMediaType,
    pub tmdb_id: i64,
    pub title: String,
    pub year: Option<i32>,
    pub overview: Option<String>,
    pub poster_url: Option<String>,
    pub backdrop_url: Option<String>,
    pub availability: SeerrAvailability,
    /// Set when already in the Jellyfin library; the detail screen's
    /// primary action becomes "Go to library" instead of a request action.
    pub jellyfin_item_id: Option<String>,
}

#[derive(uniffi::Record, Debug, Clone, PartialEq, Eq)]
pub struct SeerrHomeRow {
    pub id: String,
    pub title: String,
    pub cards: Vec<SeerrCard>,
}

#[derive(uniffi::Record, Debug, Clone, PartialEq, Eq)]
pub struct SeerrHome {
    pub rows: Vec<SeerrHomeRow>,
}

#[derive(uniffi::Record, Debug, Clone, PartialEq, Eq)]
pub struct SeerrPage {
    pub cards: Vec<SeerrCard>,
    pub page: i64,
    pub total_pages: i64,
    pub total_results: i64,
}

#[derive(uniffi::Enum, Debug, Clone, Copy, PartialEq, Eq)]
pub enum SeerrBrowseKind {
    Trending,
    Movies,
    Tv,
    UpcomingMovies,
    UpcomingTv,
}

/// Raw query-param passthrough (`seerr_api::BrowseFilters`'s FFI skin); every field is optional,
/// unvalidated.
#[derive(uniffi::Record, Debug, Clone, Default, PartialEq)]
pub struct SeerrBrowseFilters {
    pub sort_by: Option<String>,
    pub genre_id: Option<i64>,
    pub min_vote: Option<f64>,
    pub network_id: Option<i64>,
    pub status: Option<String>,
}

impl From<SeerrBrowseFilters> for seerr_api::BrowseFilters {
    fn from(filters: SeerrBrowseFilters) -> Self {
        seerr_api::BrowseFilters {
            sort_by: filters.sort_by,
            genre_id: filters.genre_id,
            min_vote: filters.min_vote,
            network_id: filters.network_id,
            status: filters.status,
        }
    }
}

#[derive(uniffi::Record, Debug, Clone, PartialEq, Eq)]
pub struct SeerrGenre {
    pub id: i64,
    pub name: String,
}

#[derive(uniffi::Record, Debug, Clone, PartialEq, Eq)]
pub struct SeerrPersonRef {
    pub person_id: i64,
    pub name: String,
    pub role: Option<String>,
    pub profile_url: Option<String>,
}

#[derive(uniffi::Record, Debug, Clone, PartialEq, Eq)]
pub struct SeerrPersonCredits {
    pub name: String,
    pub profile_url: Option<String>,
    pub credits: Vec<SeerrCard>,
}

/// `MediaRequest.status` (1=pending, 2=approved, 3=declined), a direct 1:1.
#[derive(uniffi::Enum, Debug, Clone, Copy, PartialEq, Eq)]
pub enum SeerrRequestStatus {
    Pending,
    Approved,
    Declined,
}

#[derive(uniffi::Record, Debug, Clone, PartialEq, Eq)]
pub struct SeerrActiveRequest {
    pub request_id: i64,
    pub status: SeerrRequestStatus,
    pub is_4k: bool,
    /// TV only -- always empty for a movie request.
    pub seasons: Vec<i32>,
}

#[derive(uniffi::Record, Debug, Clone, PartialEq, Eq)]
pub struct SeerrSeasonStatus {
    pub season_number: i32,
    /// Seerr's own name; `None` lets Kotlin word "Season N" (docs/27 §5).
    pub name: Option<String>,
    pub episode_count: i32,
    pub availability: SeerrAvailability,
    pub requestable: bool,
}

#[derive(uniffi::Record, Debug, Clone, PartialEq)]
pub struct SeerrMovieDetail {
    pub card: SeerrCard,
    pub runtime_minutes: Option<i32>,
    pub genres: Vec<SeerrGenre>,
    pub cast: Vec<SeerrPersonRef>,
    pub similar: Vec<SeerrCard>,
    pub recommendations: Vec<SeerrCard>,
    pub trailer_url: Option<String>,
    pub critics_score: Option<i32>,
    pub audience_score: Option<i32>,
    pub active_request: Option<SeerrActiveRequest>,
    pub can_request: bool,
    pub can_request_4k: bool,
}

#[derive(uniffi::Record, Debug, Clone, PartialEq)]
pub struct SeerrTvDetail {
    pub card: SeerrCard,
    pub genres: Vec<SeerrGenre>,
    pub cast: Vec<SeerrPersonRef>,
    pub similar: Vec<SeerrCard>,
    pub recommendations: Vec<SeerrCard>,
    pub trailer_url: Option<String>,
    pub critics_score: Option<i32>,
    pub audience_score: Option<i32>,
    pub active_request: Option<SeerrActiveRequest>,
    pub can_request: bool,
    pub can_request_4k: bool,
    pub seasons: Vec<SeerrSeasonStatus>,
}

#[derive(uniffi::Record, Debug, Clone, PartialEq, Eq)]
pub struct SeerrProfile {
    pub id: i64,
    pub name: String,
    pub is_default: bool,
}

#[derive(uniffi::Record, Debug, Clone, PartialEq, Eq)]
pub struct SeerrRootFolder {
    pub id: i64,
    pub path: String,
    pub is_default: bool,
}

#[derive(uniffi::Record, Debug, Clone, PartialEq, Eq)]
pub struct SeerrServiceServer {
    pub server_id: i64,
    pub name: String,
    pub is_4k: bool,
    pub is_default: bool,
    pub profiles: Vec<SeerrProfile>,
    pub root_folders: Vec<SeerrRootFolder>,
}

/// Empty `servers` means a plain Request button (no profile/root-folder pickers).
#[derive(uniffi::Record, Debug, Clone, PartialEq, Eq)]
pub struct SeerrRequestOptions {
    pub servers: Vec<SeerrServiceServer>,
}

#[derive(uniffi::Record, Debug, Clone, PartialEq, Eq)]
pub struct SeerrRequestInput {
    pub media_type: SeerrMediaType,
    pub tmdb_id: i64,
    pub is_4k: bool,
    /// TV only -- ignored (must be empty) for a movie request.
    pub seasons: Vec<i32>,
    pub server_id: Option<i64>,
    pub profile_id: Option<i64>,
    pub root_folder: Option<String>,
}

#[derive(uniffi::Record, Debug, Clone, PartialEq, Eq)]
pub struct SeerrMyRequest {
    /// Seerr's own request id; the one key that stays unique when an account
    /// holds several requests for the same title (SD and 4K, season batches).
    pub request_id: i64,
    pub card: SeerrCard,
    pub status: SeerrRequestStatus,
    pub is_4k: bool,
    pub seasons: Vec<i32>,
    pub requested_by: Option<String>,
}
