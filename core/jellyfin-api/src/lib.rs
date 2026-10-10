//! Jellyfin REST + WebSocket client. Models are generated (see `codegen/regen.sh`); the client
//! surface below is hand-written, plus two additions: [`JellyfinClient::get_playback_info`]'s
//! [`PlaybackInfoOptions`] parameter and [`ReportPlayMethod`] on
//! [`PlaybackReport`]/`StartProgressBody` (docs/18-playback-quality.md §2), and
//! [`ServerVersion`]/[`PublicServerInfo`]/[`JellyfinClient::public_system_info`]/[`JellyfinClient::refresh_public_system_info`]
//! for the pre-auth `GET /System/Info/Public` call (docs/13 Server compatibility).
//! TLS: `reqwest`/`tokio-tungstenite` both use rustls, not native-tls/OpenSSL -- one stack shared
//! by HTTP and WebSocket. See `Cargo.toml` for the feature selection.

/// LAN server autodetection (docs/feature-dev/spec-still-watching-and-lan-discovery.md).
pub mod discovery;
// Generated (see codegen/regen.sh) and not hand-tuned for clippy's stricter
// lints -- don't hand-edit to satisfy them, regen would reintroduce them.
#[allow(clippy::all)]
pub mod dns;
// Generated (see codegen/): the generator writes non-derived `Default`
// impls clippy flags. Allowed here so the waiver survives regeneration.
#[allow(clippy::derivable_impls)]
pub mod models;
mod util;
mod ws;

/// Fuzzing-only re-exports. `cargo fuzz` builds with `--cfg fuzzing`, so
/// normal builds never see this and the private decode surface stays
/// private elsewhere.
#[cfg(fuzzing)]
pub mod fuzzing {
    pub use crate::ws::decode_message;
}

use std::time::Duration;

use models::*;
use util::{describe_error_chain, percent_encode};

/// TCP connect timeout for the REST client -- bounds a hung connect to an
/// unreachable/firewalled host. 10s tolerates a VPN route still
/// establishing on cold start while still bounding a truly unreachable host.
const CONNECT_TIMEOUT: Duration = Duration::from_secs(10);
/// Whole-request timeout (connect+send+receive) for the REST client. Does
/// not apply to image/stream URLs -- those are plain strings fetched by
/// other crates with their own timeout/retry policy.
const REQUEST_TIMEOUT: Duration = Duration::from_secs(30);

/// Builds the shared `reqwest::Client`. Panics only if the TLS backend
/// fails to init, same as `reqwest::Client::new()` -- not a new panic surface.
fn build_http_client() -> reqwest::Client {
    reqwest::Client::builder()
        .connect_timeout(CONNECT_TIMEOUT)
        .timeout(REQUEST_TIMEOUT)
        .redirect(redirect_policy())
        // Stream and sidecar URLs carry `ApiKey` in the query; a redirect must not hand it to the
        // next host as a Referer.
        .referer(false)
        // The system resolver intermittently stalls ~5s; the shared cache
        // serves every post-first lookup instantly (see `dns`'s module docs).
        .dns_resolver(crate::dns::shared_dns_resolver())
        .build()
        .expect("reqwest client with connect/request timeouts should always build")
}

/// Pure acceptance of a fetched sidecar body (docs/18 §3.2): valid UTF-8 or nothing, since a
/// lossy decode would hand the player garbled cues.
fn text_from_delivery_body(body: Vec<u8>) -> Result<String, ApiError> {
    String::from_utf8(body).map_err(|_| ApiError::Decode("delivery body is not utf-8".to_string()))
}

/// Pure body of [`JellyfinClient::delivery_url`]: relative paths resolve against `base` and gain
/// `ApiKey` (unless one is present); an absolute URL is kept only when it is on `base`.
fn authed_delivery_url(base: &str, token: &str, delivery_url: &str) -> Option<String> {
    let absolute = delivery_url.starts_with("http://") || delivery_url.starts_with("https://");
    let full = if absolute {
        if !delivery_url.starts_with(&format!("{base}/")) {
            return None;
        }
        delivery_url.to_string()
    } else {
        format!("{base}/{}", delivery_url.trim_start_matches('/'))
    };
    let has_key = full.split_once('?').is_some_and(|(_, q)| {
        q.split('&').any(|kv| {
            let k = kv.split('=').next().unwrap_or("");
            k.eq_ignore_ascii_case("apikey") || k.eq_ignore_ascii_case("api_key")
        })
    });
    if has_key {
        return Some(full);
    }
    let sep = if full.contains('?') { '&' } else { '?' };
    Some(format!("{full}{sep}ApiKey={}", percent_encode(token)))
}

/// Redirect hop limit -- matches reqwest's own default, so a deployment
/// behind a redirecting reverse proxy still works.
const MAX_REDIRECT_HOPS: usize = 10;

/// reqwest's default policy strips `Authorization` only on a cross-host redirect, not a scheme
/// downgrade -- a same-host `https`->`http` bounce would leak the token. Only the downgrade is
/// refused; it fails loudly rather than following the 3xx.
fn redirect_policy() -> reqwest::redirect::Policy {
    reqwest::redirect::Policy::custom(|attempt| {
        if is_scheme_downgrade(attempt.previous(), attempt.url()) {
            tracing::warn!("refusing an https -> http redirect (would leak the access token)");
            return attempt.error("refusing to follow an https -> http redirect");
        }
        // `previous`'s first entry is the original URL, not a redirection --
        // same off-by-one reqwest's own `Policy::limited` accounts for.
        if attempt.previous().len() > MAX_REDIRECT_HOPS {
            return attempt.error("too many redirects");
        }
        attempt.follow()
    })
}

/// True when the hop just came from was `https` and the next URL isn't.
/// Split out of [`redirect_policy`] because `reqwest::redirect::Attempt`
/// can't be constructed outside reqwest, so this is the only testable part.
fn is_scheme_downgrade(previous: &[reqwest::Url], next: &reqwest::Url) -> bool {
    previous
        .last()
        .is_some_and(|prev| prev.scheme() == "https" && next.scheme() != "https")
}

/// Cap on [`ApiError::Status`] body bytes -- Jellyfin error responses are
/// usually a short JSON problem-details body worth surfacing, but must stay
/// bounded against a misbehaving proxy/server returning something huge.
const STATUS_ERROR_BODY_CAP: usize = 2048;

/// Ceiling on a successful (2xx) JSON body -- `Response::json` otherwise buffers the whole body
/// before serde sees it, letting a broken server force an unbounded allocation. 8 MiB is generous
/// for even a very large `/Items` page.
const JSON_BODY_CAP: usize = 8 * 1024 * 1024;

/// Reads a response body chunk by chunk, capped at `cap` bytes, replacing `Response::json()`'s
/// whole-body buffering. `cap` is a parameter so tests can drive the boundary without pushing 8
/// MiB.
async fn read_capped_body(
    mut resp: reqwest::Response,
    cap: usize,
    what: &str,
) -> Result<Vec<u8>, ApiError> {
    let mut body: Vec<u8> = Vec::new();
    loop {
        let chunk = match resp.chunk().await {
            Ok(Some(chunk)) => chunk,
            Ok(None) => return Ok(body),
            Err(e) => return Err(ApiError::Transport(describe_error_chain(&e))),
        };
        if body.len() + chunk.len() > cap {
            tracing::warn!(path = %what, cap, "response body exceeded the size cap; aborting read");
            return Err(ApiError::Decode(format!(
                "response body exceeded {cap} bytes"
            )));
        }
        body.extend_from_slice(&chunk);
    }
}

/// [`read_capped_body`] at [`JSON_BODY_CAP`], plus the deserialize step --
/// the drop-in for every `resp.json().await` in this crate.
async fn read_json_capped<T: serde::de::DeserializeOwned>(
    resp: reqwest::Response,
    what: &str,
) -> Result<T, ApiError> {
    let body = read_capped_body(resp, JSON_BODY_CAP, what).await?;
    serde_json::from_slice(&body).map_err(|e| ApiError::Decode(e.to_string()))
}

#[derive(Debug, thiserror::Error)]
pub enum ApiError {
    #[error("http status {code}{}", if body.is_empty() { String::new() } else { format!(": {body}") })]
    Status { code: u16, body: String },
    #[error("transport: {0}")]
    Transport(String),
    #[error("decode: {0}")]
    Decode(String),
    /// 401: the token was rejected. `owner` names whose token it was; `None` before sign-in.
    #[error("unauthorized")]
    Unauthorized { owner: Option<TokenOwner> },
}

/// The account a token was issued to: a 401 on a request sent with it re-authorizes this account.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TokenOwner {
    pub server_url: String,
    pub user_id: String,
}

/// Connection identity sent in the `Authorization: MediaBrowser ...` header.
#[derive(Debug, Clone)]
pub struct ClientIdentity {
    pub client: String,    // "Jellybeam"
    pub device: String,    // hostname
    pub device_id: String, // stable per install
    pub version: String,
}

/// A parsed Jellyfin server version (e.g. `"10.11.10"`) -- docs/13 Server
/// compatibility. Field order is `(major, minor, patch)` so derived
/// `Ord`/`PartialOrd` compare like a dotted version number.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct ServerVersion {
    pub major: u32,
    pub minor: u32,
    pub patch: u32,
}

impl ServerVersion {
    /// `true` when this version is `(major, minor)` or newer; `patch` never
    /// affects the comparison. Callers with only `Option<ServerVersion>`
    /// decide the fail-closed default themselves -- see the FFI
    /// `server_at_least` export.
    pub fn at_least(&self, major: u32, minor: u32) -> bool {
        (self.major, self.minor) >= (major, minor)
    }
}

impl std::fmt::Display for ServerVersion {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}.{}.{}", self.major, self.minor, self.patch)
    }
}

/// Parses `"major.minor[.patch][-suffix]"`. `patch` defaults to `0`; a
/// non-digit suffix (e.g. `-rc1`) is dropped after its leading digits.
/// `Err` if major or minor doesn't parse.
impl std::str::FromStr for ServerVersion {
    type Err = String;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        fn leading_digits(component: &str) -> Option<u32> {
            let digits: String = component
                .chars()
                .take_while(|c| c.is_ascii_digit())
                .collect();
            if digits.is_empty() {
                None
            } else {
                digits.parse().ok()
            }
        }

        let mut parts = s.split('.');
        let major = parts
            .next()
            .and_then(leading_digits)
            .ok_or_else(|| format!("no major version component in {s:?}"))?;
        let minor = parts
            .next()
            .and_then(leading_digits)
            .ok_or_else(|| format!("no minor version component in {s:?}"))?;
        let patch = parts.next().and_then(leading_digits).unwrap_or(0);
        Ok(ServerVersion {
            major,
            minor,
            patch,
        })
    }
}

/// `GET /System/Info/Public` response -- docs/13 Server compatibility
/// gating. Only the fields this crate uses are modeled; other fields are
/// ignored (no `deny_unknown_fields`).
#[derive(Debug, Clone, Default, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "PascalCase")]
pub struct PublicServerInfo {
    pub version: Option<String>,
    pub server_name: Option<String>,
    pub id: Option<String>,
    pub startup_wizard_completed: Option<bool>,
}

impl PublicServerInfo {
    /// [`Self::version`] parsed as a [`ServerVersion`]; `None` if absent or
    /// unparseable (see `FromStr` for what's tolerated).
    pub fn parsed_version(&self) -> Option<ServerVersion> {
        self.version.as_deref().and_then(|v| v.parse().ok())
    }
}

/// `GET /System/Info` response (authenticated, docs/13 About > server info). Only the fields the
/// About page needs are modeled; other fields are ignored (no `deny_unknown_fields`).
#[derive(Debug, Clone, Default, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "PascalCase")]
pub struct ServerSystemInfo {
    pub version: Option<String>,
    pub server_name: Option<String>,
    pub product_name: Option<String>,
    pub operating_system_display_name: Option<String>,
    pub system_architecture: Option<String>,
    pub has_pending_restart: Option<bool>,
    pub has_update_available: Option<bool>,
}

/// `GET /Items/Counts` response (docs/13 About > server info). Every field is per-library-type
/// and `None` when the server omits it; no `deny_unknown_fields`.
#[derive(Debug, Clone, Default, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "PascalCase")]
pub struct ItemCounts {
    pub movie_count: Option<i32>,
    pub series_count: Option<i32>,
    pub episode_count: Option<i32>,
    pub box_set_count: Option<i32>,
    pub album_count: Option<i32>,
    pub song_count: Option<i32>,
    pub artist_count: Option<i32>,
    pub music_video_count: Option<i32>,
    pub book_count: Option<i32>,
    pub trailer_count: Option<i32>,
    pub program_count: Option<i32>,
}

/// Escapes a value for an HTTP header's `quoted-string` (RFC 7230 §3.2.6): backslash-escapes `"`
/// and `\`, strips bare CR/LF -- `identity.device` can carry a user-editable name that would
/// otherwise close the value early or inject headers.
fn escape_header_value(value: &str) -> String {
    let mut out = String::with_capacity(value.len());
    for c in value.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\r' | '\n' => {}
            _ => out.push(c),
        }
    }
    out
}

/// Builds the `MediaBrowser` auth header value:
/// `MediaBrowser Client="Jellybeam", Device="...", DeviceId="...",
/// Version="...", Token="..."` (the `Token` clause is omitted pre-auth).
fn auth_header(identity: &ClientIdentity, token: Option<&str>) -> String {
    let mut header = format!(
        r#"MediaBrowser Client="{}", Device="{}", DeviceId="{}", Version="{}""#,
        escape_header_value(&identity.client),
        escape_header_value(&identity.device),
        escape_header_value(&identity.device_id),
        escape_header_value(&identity.version)
    );
    if let Some(token) = token {
        header.push_str(&format!(r#", Token="{}""#, escape_header_value(token)));
    }
    header
}

struct Inner {
    base_url: String, // no trailing slash
    identity: ClientIdentity,
    token: String,
    http: reqwest::Client,
    /// The authenticated user's id, when known -- populated by
    /// [`JellyfinClient::authenticate_by_name`] from
    /// `AuthenticationResult.User.Id`. Sessions resumed via
    /// [`JellyfinClient::from_token`] have none until
    /// [`JellyfinClient::with_user_id`] sets it explicitly.
    user_id: Option<String>,
}

/// One authenticated server connection. Cheap to clone (inner Arc).
#[derive(Clone)]
pub struct JellyfinClient {
    inner: std::sync::Arc<Inner>,
}

#[derive(Default)]
pub struct ItemQuery {
    pub parent_id: Option<String>,
    pub include_item_types: Vec<String>,
    pub recursive: bool,
    pub sort_by: Option<String>,
    /// Sort direction for `sort_by` (`"Ascending"`/`"Descending"`), sent as
    /// `sortOrder`. `None` omits the param (server default).
    pub sort_order: Option<String>,
    pub fields: Vec<String>,
    pub start_index: u32,
    pub limit: u32,
    pub ids: Vec<String>,
    /// `isMissing` query param: filters missing/unaired virtual placeholder episodes. `None` omits
    /// it. A `Series`/`Season`-scoped recursive query still applies the server's own filter
    /// regardless, so every call site here leaves this `None` (see
    /// `media-cache::sync::reconcile_view`).
    pub is_missing: Option<bool>,
    /// `minDateLastSaved` (ISO 8601): filters to items with `DateLastSaved` at or after this
    /// instant; `media-cache::sync::delta_sync` is built on it. `None` omits it.
    /// Sent as both `minDateLastSaved` and `minDateLastSavedForUser` (same value): Jellyfin 10.10.7
    /// 500s a `recursive=true` query with `minDateLastSaved` alone; a no-op on servers where the
    /// bug is fixed.
    /// The response never echoes `DateLastSaved` back -- filter-only value; see `delta_sync`'s
    /// cursor-advance rule.
    pub min_date_last_saved: Option<String>,
    /// `enableImages` query param. `None` omits it (server default `true`); `Some(false)` strips
    /// image fields. Used by `media-cache::sync`'s id-only sweep with an empty `fields` list and
    /// [`Self::enable_user_data`] to shrink a page to ~100 bytes/item.
    pub enable_images: Option<bool>,
    /// `enableUserData` query param. `None` omits it (server default `true`); `Some(false)` strips
    /// `UserData`. Same motivation as [`Self::enable_images`], but missing `UserData` reads as
    /// "unplayed, position 0" to `rows::extract_columns`, so it's only safe for id-only
    /// enumeration; the sweep re-fetches full DTOs for stored ids.
    pub enable_user_data: Option<bool>,
    /// `isFavorite` query param: the requesting user's favorites only (favorites are per user).
    /// `None` omits it.
    pub is_favorite: Option<bool>,
    /// `personIds` query param: items crediting any of these person ids. Empty omits it.
    pub person_ids: Vec<String>,
}

impl ItemQuery {
    /// Equivalent to `ItemQuery::default()`, for `..ItemQuery::new()`
    /// struct-update syntax. Prefer this over an exhaustive struct literal,
    /// which breaks whenever a new pub field is added.
    pub fn new() -> Self {
        Self::default()
    }
}

/// `GET /Shows/NextUp` query options (cutoff + rewatching). Both knobs are
/// omitted from the request at their default, same convention as
/// [`ItemQuery::enable_images`].
#[derive(Debug, Clone, Default)]
pub struct NextUpOptions {
    /// `nextUpDateCutoff` query param (RFC3339 UTC instant): only series
    /// with unwatched content added on/after this date. `None` omits it.
    /// Caller formats the instant; this struct just carries the string.
    pub date_cutoff: Option<String>,
    /// `enableRewatching` query param. Server default `false` (a fully
    /// watched series never resurfaces); `true` includes a
    /// rewatched-from-the-start series' next episode.
    pub enable_rewatching: bool,
}

/// Status check for a request sent without a token (sign-in, public info); an authenticated
/// request goes through [`JellyfinClient::check`], so its 401 names the token's owner.
async fn check_public_status(resp: reqwest::Response) -> Result<reqwest::Response, ApiError> {
    if resp.status() == reqwest::StatusCode::UNAUTHORIZED {
        return Err(ApiError::Unauthorized { owner: None });
    }
    if !resp.status().is_success() {
        let code = resp.status().as_u16();
        let body = capped_body_text(resp, STATUS_ERROR_BODY_CAP).await;
        return Err(ApiError::Status { code, body });
    }
    Ok(resp)
}

/// Reads `resp`'s body as text, streamed chunk by chunk and stopped as soon as `cap` bytes are
/// collected rather than buffering the whole body first. Never fails -- an unreadable/non-UTF-8
/// body is folded into the returned string rather than losing the status code already captured.
async fn capped_body_text(mut resp: reqwest::Response, cap: usize) -> String {
    let mut body: Vec<u8> = Vec::new();
    while body.len() < cap {
        match resp.chunk().await {
            Ok(Some(chunk)) => body.extend_from_slice(&chunk),
            Ok(None) => break,
            Err(e) => return format!("<failed to read response body: {e}>"),
        }
    }
    // `>=`, not `>`: the loop above stops pulling chunks as soon as the cap is reached, so hitting
    // it exactly still means more of the body may be unread.
    let truncated = body.len() >= cap;
    body.truncate(cap.min(body.len()));
    // `from_utf8_lossy` tolerates a cut mid-UTF-8-sequence at the truncation point, unlike slicing
    // a `String` on a non-char boundary (which panics).
    let mut text = String::from_utf8_lossy(&body).into_owned();
    if truncated {
        text.push_str("... [truncated]");
    }
    text
}

impl JellyfinClient {
    pub async fn authenticate_by_name(
        base_url: &str,
        identity: ClientIdentity,
        username: &str,
        password: &str,
    ) -> Result<(Self, AuthenticationResult), ApiError> {
        let base_url = base_url.trim_end_matches('/').to_string();
        let http = build_http_client();
        let url = format!("{base_url}/Users/AuthenticateByName");

        let body = AuthenticateUserByName {
            username: Some(username.to_string()),
            pw: Some(password.to_string()),
        };

        let resp = http
            .post(&url)
            .header("Authorization", auth_header(&identity, None))
            .json(&body)
            .send()
            .await
            .map_err(|e| ApiError::Transport(describe_error_chain(&e)))?;
        let resp = check_public_status(resp).await?;
        let result: AuthenticationResult =
            read_json_capped(resp, "/Users/AuthenticateByName").await?;

        let token = result.access_token.clone().ok_or_else(|| {
            ApiError::Decode("AuthenticateByName response missing AccessToken".to_string())
        })?;

        let user_id = result
            .user
            .as_ref()
            .and_then(|u| u.id)
            .map(|id| id.to_string());

        let client = Self {
            inner: std::sync::Arc::new(Inner {
                base_url,
                identity,
                token,
                http,
                user_id,
            }),
        };
        Ok((client, result))
    }

    /// `POST /QuickConnect/Initiate` -- starts a Quick Connect handshake (Initiate -> poll Connect
    /// -> AuthenticateWithQuickConnect). No prior authentication required. Returns the user-facing
    /// `code` (show it) and the `secret` (kept client-side, never shown).
    pub async fn quick_connect_initiate(
        base_url: &str,
        identity: &ClientIdentity,
    ) -> Result<QuickConnectResult, ApiError> {
        let base_url = base_url.trim_end_matches('/');
        let http = build_http_client();
        let url = format!("{base_url}/QuickConnect/Initiate");
        let resp = http
            .post(&url)
            .header("Authorization", auth_header(identity, None))
            .send()
            .await
            .map_err(|e| ApiError::Transport(describe_error_chain(&e)))?;
        let resp = check_public_status(resp).await?;
        read_json_capped(resp, "/QuickConnect/Initiate").await
    }

    /// `GET /QuickConnect/Connect?secret=...` -- one poll of a Quick Connect request's state;
    /// callers drive their own poll loop. `result.authenticated == Some(true)` means
    /// [`Self::authenticate_with_quick_connect`] can be called with the same secret.
    /// A 404 (expired/invalid secret) surfaces as a normal `ApiError::Status` here, unlike
    /// [`Self::get_media_segments`]'s 404-as-empty.
    pub async fn quick_connect_poll(
        base_url: &str,
        identity: &ClientIdentity,
        secret: &str,
    ) -> Result<QuickConnectResult, ApiError> {
        let base_url = base_url.trim_end_matches('/');
        let http = build_http_client();
        let url = format!("{base_url}/QuickConnect/Connect");
        let resp = http
            .get(&url)
            .header("Authorization", auth_header(identity, None))
            .query(&[("secret", secret)])
            .send()
            .await
            .map_err(|e| ApiError::Transport(describe_error_chain(&e)))?;
        let resp = check_public_status(resp).await?;
        read_json_capped(resp, "/QuickConnect/Connect").await
    }

    /// `GET /QuickConnect/Enabled` -- whether the server has Quick Connect
    /// turned on. Worth checking before showing the "Use Quick Connect"
    /// toggle, since Initiate 401s outright when disabled.
    pub async fn quick_connect_enabled(
        base_url: &str,
        identity: &ClientIdentity,
    ) -> Result<bool, ApiError> {
        let base_url = base_url.trim_end_matches('/');
        let http = build_http_client();
        let url = format!("{base_url}/QuickConnect/Enabled");
        let resp = http
            .get(&url)
            .header("Authorization", auth_header(identity, None))
            .send()
            .await
            .map_err(|e| ApiError::Transport(describe_error_chain(&e)))?;
        let resp = check_public_status(resp).await?;
        read_json_capped(resp, "/QuickConnect/Enabled").await
    }

    /// `GET /System/Info/Public` -- unauthenticated server info (docs/13 Server compatibility
    /// gating). Sends the `MediaBrowser` header with no `Token=` clause, for first-contact points
    /// (Connect screen) before [`Self::refresh_public_system_info`] applies.
    pub async fn public_system_info(
        base_url: &str,
        identity: &ClientIdentity,
    ) -> Result<PublicServerInfo, ApiError> {
        let base_url = base_url.trim_end_matches('/');
        let http = build_http_client();
        let url = format!("{base_url}/System/Info/Public");
        let resp = http
            .get(&url)
            .header("Authorization", auth_header(identity, None))
            .send()
            .await
            .map_err(|e| ApiError::Transport(describe_error_chain(&e)))?;
        let resp = check_public_status(resp).await?;
        read_json_capped(resp, "/System/Info/Public").await
    }

    /// `POST /Users/AuthenticateWithQuickConnect` -- completes the handshake once
    /// [`Self::quick_connect_poll`] reports `authenticated: true`, exchanging the secret for a
    /// token. Mirrors [`Self::authenticate_by_name`]'s shape.
    pub async fn authenticate_with_quick_connect(
        base_url: &str,
        identity: ClientIdentity,
        secret: &str,
    ) -> Result<(Self, AuthenticationResult), ApiError> {
        let base_url = base_url.trim_end_matches('/').to_string();
        let http = build_http_client();
        let url = format!("{base_url}/Users/AuthenticateWithQuickConnect");

        let secret_value: QuickConnectDtoSecret = secret
            .to_string()
            .try_into()
            .map_err(|_| ApiError::Decode("empty Quick Connect secret".to_string()))?;
        let body = QuickConnectDto {
            secret: secret_value,
        };

        let resp = http
            .post(&url)
            .header("Authorization", auth_header(&identity, None))
            .json(&body)
            .send()
            .await
            .map_err(|e| ApiError::Transport(describe_error_chain(&e)))?;
        let resp = check_public_status(resp).await?;
        let result: AuthenticationResult =
            read_json_capped(resp, "/Users/AuthenticateWithQuickConnect").await?;

        let token = result.access_token.clone().ok_or_else(|| {
            ApiError::Decode(
                "AuthenticateWithQuickConnect response missing AccessToken".to_string(),
            )
        })?;
        let user_id = result
            .user
            .as_ref()
            .and_then(|u| u.id)
            .map(|id| id.to_string());

        let client = Self {
            inner: std::sync::Arc::new(Inner {
                base_url,
                identity,
                token,
                http,
                user_id,
            }),
        };
        Ok((client, result))
    }

    /// Resume a session from a stored token. Has no `AuthenticationResult` to read a user id from,
    /// so [`Self::user_id`] returns `None` until [`Self::with_user_id`] supplies one.
    pub fn from_token(base_url: &str, identity: ClientIdentity, token: &str) -> Self {
        let base_url = base_url.trim_end_matches('/').to_string();
        Self {
            inner: std::sync::Arc::new(Inner {
                base_url,
                identity,
                token: token.to_string(),
                http: build_http_client(),
                user_id: None,
            }),
        }
    }

    /// Attaches a known user id to a client (typically after
    /// [`Self::from_token`]). Returns a new client sharing the same
    /// connection; doesn't mutate `self` since `Inner` is behind an `Arc`.
    pub fn with_user_id(self, user_id: &str) -> Self {
        let inner = Inner {
            base_url: self.inner.base_url.clone(),
            identity: self.inner.identity.clone(),
            token: self.inner.token.clone(),
            http: self.inner.http.clone(),
            user_id: Some(user_id.to_string()),
        };
        Self {
            inner: std::sync::Arc::new(inner),
        }
    }

    /// The authenticated user's id, if known. Always `Some` after
    /// [`Self::authenticate_by_name`]; `None` after [`Self::from_token`]
    /// unless [`Self::with_user_id`] was also called.
    pub fn user_id(&self) -> Option<&str> {
        self.inner.user_id.as_deref()
    }

    fn auth_header(&self) -> String {
        auth_header(&self.inner.identity, Some(&self.inner.token))
    }

    /// [`check_public_status`] for a request sent with this client's token.
    async fn check(&self, resp: reqwest::Response) -> Result<reqwest::Response, ApiError> {
        check_public_status(resp)
            .await
            .map_err(|err| self.owned(err))
    }

    /// Names this client's token owner on a 401, so it re-authorizes the account that sent it.
    fn owned(&self, err: ApiError) -> ApiError {
        match err {
            ApiError::Unauthorized { owner: None } => ApiError::Unauthorized {
                owner: self.inner.user_id.as_ref().map(|user_id| TokenOwner {
                    server_url: self.inner.base_url.clone(),
                    user_id: user_id.clone(),
                }),
            },
            other => other,
        }
    }

    /// Appends `userId` to `query` when [`Self::user_id`] is known; a no-op for a token-resumed
    /// session that never got one. Shared by every call site that personalizes a request this way.
    fn push_user_id(&self, query: &mut Vec<(&str, String)>) {
        if let Some(user_id) = &self.inner.user_id {
            query.push(("userId", user_id.clone()));
        }
    }

    async fn get<T: serde::de::DeserializeOwned>(
        &self,
        path: &str,
        query: &[(&str, String)],
    ) -> Result<T, ApiError> {
        let url = format!("{}{}", self.inner.base_url, path);
        let resp = self
            .inner
            .http
            .get(&url)
            .header("Authorization", self.auth_header())
            .query(query)
            .send()
            .await
            .map_err(|e| ApiError::Transport(describe_error_chain(&e)))?;
        let resp = self.check(resp).await?;
        read_json_capped(resp, path).await
    }

    pub async fn get_user_views(&self) -> Result<Vec<BaseItemDto>, ApiError> {
        let result: ItemsResult = self.get("/UserViews", &[]).await?;
        Ok(result.items)
    }

    pub async fn get_items(&self, q: &ItemQuery) -> Result<ItemsResult, ApiError> {
        let mut query: Vec<(&str, String)> = Vec::new();
        if let Some(parent_id) = &q.parent_id {
            query.push(("parentId", parent_id.clone()));
        }
        if !q.include_item_types.is_empty() {
            query.push(("includeItemTypes", q.include_item_types.join(",")));
        }
        query.push(("recursive", q.recursive.to_string()));
        if let Some(sort_by) = &q.sort_by {
            query.push(("sortBy", sort_by.clone()));
        }
        if let Some(sort_order) = &q.sort_order {
            query.push(("sortOrder", sort_order.clone()));
        }
        if !q.fields.is_empty() {
            query.push(("fields", q.fields.join(",")));
        }
        query.push(("startIndex", q.start_index.to_string()));
        query.push(("limit", q.limit.to_string()));
        if !q.ids.is_empty() {
            query.push(("ids", q.ids.join(",")));
        }
        if let Some(is_missing) = q.is_missing {
            query.push(("isMissing", is_missing.to_string()));
        }
        if let Some(is_favorite) = q.is_favorite {
            query.push(("isFavorite", is_favorite.to_string()));
        }
        if !q.person_ids.is_empty() {
            query.push(("personIds", q.person_ids.join(",")));
        }
        if let Some(min_date_last_saved) = &q.min_date_last_saved {
            query.push(("minDateLastSaved", min_date_last_saved.clone()));
            // Not a second filter: see `ItemQuery::min_date_last_saved`'s doc comment (10.10.x 500s
            // without this companion param).
            query.push(("minDateLastSavedForUser", min_date_last_saved.clone()));
        }
        // Payload-shrinking knobs (see their doc comments): omitted entirely when `None`.
        if let Some(enable_images) = q.enable_images {
            query.push(("enableImages", enable_images.to_string()));
        }
        if let Some(enable_user_data) = q.enable_user_data {
            query.push(("enableUserData", enable_user_data.to_string()));
        }
        self.get("/Items", &query).await
    }

    /// One item by id, user-scoped (`/Users/{userId}/Items/{id}`) when the user id is known, else
    /// `/Items/{id}`. Unlike [`Self::get_items`] with `ids`, it resolves non-library kinds such as
    /// a `Person`.
    pub async fn get_item_by_id(
        &self,
        item_id: &str,
        fields: &[String],
    ) -> Result<BaseItemDto, ApiError> {
        let item = percent_encode(item_id);
        let path = self.user_id().map_or_else(
            || format!("/Items/{item}"),
            |user_id| format!("/Users/{}/Items/{item}", percent_encode(user_id)),
        );
        let mut query: Vec<(&str, String)> = Vec::new();
        if !fields.is_empty() {
            query.push(("fields", fields.join(",")));
        }
        self.get(&path, &query).await
    }

    /// `fields` matters as documented on [`Self::get_next_up`]: `media-cache::sync::sync_resume`
    /// upserts this whole over the mirror's rows, and a field-less response omits
    /// `Overview`/`SeriesName`/etc.
    pub async fn get_resume_items(&self, fields: &[String]) -> Result<ItemsResult, ApiError> {
        let mut query: Vec<(&str, String)> = Vec::new();
        if !fields.is_empty() {
            query.push(("fields", fields.join(",")));
        }
        self.get("/UserItems/Resume", &query).await
    }

    /// `fields` matters: `media-cache::refresh_next_up` upserts this over the mirror's rows, and a
    /// field-less response omits `Overview` and other opt-in `ItemFields`.
    pub async fn get_next_up(
        &self,
        fields: &[String],
        options: &NextUpOptions,
    ) -> Result<ItemsResult, ApiError> {
        let mut query: Vec<(&str, String)> = Vec::new();
        if !fields.is_empty() {
            query.push(("fields", fields.join(",")));
        }
        if let Some(cutoff) = &options.date_cutoff {
            query.push(("nextUpDateCutoff", cutoff.clone()));
        }
        if options.enable_rewatching {
            query.push(("enableRewatching", "true".to_string()));
        }
        self.get("/Shows/NextUp", &query).await
    }

    /// All real episodes in one series, in Jellyfin's aired episode order. Uses the dedicated Show
    /// endpoint rather than a recursive `/Items` parent query, since episodes are children of
    /// Seasons and server versions don't consistently expand that shape.
    pub async fn get_episodes(
        &self,
        series_id: &str,
        fields: &[String],
        limit: u32,
    ) -> Result<ItemsResult, ApiError> {
        let path = format!("/Shows/{}/Episodes", percent_encode(series_id));
        let mut query: Vec<(&str, String)> = vec![
            ("isMissing", "false".to_string()),
            ("sortBy", "AiredEpisodeOrder".to_string()),
            ("limit", limit.to_string()),
        ];
        if !fields.is_empty() {
            query.push(("fields", fields.join(",")));
        }
        self.push_user_id(&mut query);
        self.get(&path, &query).await
    }

    /// The current episode and its immediate siblings. `adjacentTo` returns only the at-most-three
    /// rows transport controls need, not a whole series; Jellyfin owns the cross-season ordering.
    pub async fn get_adjacent_episodes(
        &self,
        series_id: &str,
        item_id: &str,
        fields: &[String],
    ) -> Result<ItemsResult, ApiError> {
        let path = format!("/Shows/{}/Episodes", percent_encode(series_id));
        let mut query: Vec<(&str, String)> = vec![
            ("adjacentTo", item_id.to_string()),
            ("isMissing", "false".to_string()),
        ];
        if !fields.is_empty() {
            query.push(("fields", fields.join(",")));
        }
        self.push_user_id(&mut query);
        self.get(&path, &query).await
    }

    /// `GET /Items/{itemId}/Similar` (Detail page "Similar titles" row). `userId` is sent whenever
    /// known, personalizing results like the server's own web client; a token-resumed session
    /// without one simply omits it.
    pub async fn get_similar(
        &self,
        item_id: &str,
        limit: u32,
    ) -> Result<Vec<BaseItemDto>, ApiError> {
        let path = format!("/Items/{}/Similar", percent_encode(item_id));
        let mut query: Vec<(&str, String)> = vec![("limit", limit.to_string())];
        self.push_user_id(&mut query);
        let result: ItemsResult = self.get(&path, &query).await?;
        Ok(result.items)
    }

    pub async fn get_playback_info(
        &self,
        item_id: &str,
        profile: &DeviceProfile,
        start_ticks: Option<i64>,
        options: PlaybackInfoOptions,
    ) -> Result<PlaybackInfoResponse, ApiError> {
        let url = format!(
            "{}/Items/{}/PlaybackInfo",
            self.inner.base_url,
            percent_encode(item_id)
        );
        let body = PlaybackInfoDto {
            start_time_ticks: start_ticks,
            device_profile: Some(profile.clone()),
            // docs/18-playback-quality.md §2: `force_transcode` asks the server to negotiate a
            // transcode instead of re-deciding Direct Play.
            enable_direct_play: options.force_transcode.then_some(false),
            enable_direct_stream: options.force_transcode.then_some(false),
            media_source_id: options.media_source_id,
            allow_video_stream_copy: options.allow_video_stream_copy,
            allow_audio_stream_copy: options.allow_audio_stream_copy,
            subtitle_stream_index: options.subtitle_stream_index,
            ..Default::default()
        };
        let resp = self
            .inner
            .http
            .post(&url)
            .header("Authorization", self.auth_header())
            .json(&body)
            .send()
            .await
            .map_err(|e| ApiError::Transport(describe_error_chain(&e)))?;
        let resp = self.check(resp).await?;
        read_json_capped(resp, "/Items/{itemId}/PlaybackInfo").await
    }

    /// Fetches skip-intro/credits markers: `GET /MediaSegments/{itemId}[?includeSegmentTypes=...]`.
    /// `include_types` is comma-joined when non-empty; empty omits the param.
    /// MediaSegments isn't present on every server; a 404 on the whole endpoint is treated as "no
    /// segments" (`Ok(vec![])`) rather than an error. Any other non-success status still surfaces
    /// as a normal `ApiError`.
    pub async fn get_media_segments(
        &self,
        item_id: &str,
        include_types: &[&str],
    ) -> Result<Vec<MediaSegmentDto>, ApiError> {
        let url = format!(
            "{}/MediaSegments/{}",
            self.inner.base_url,
            percent_encode(item_id)
        );
        let mut query: Vec<(&str, String)> = Vec::new();
        if !include_types.is_empty() {
            query.push(("includeSegmentTypes", include_types.join(",")));
        }
        let resp = self
            .inner
            .http
            .get(&url)
            .header("Authorization", self.auth_header())
            .query(&query)
            .send()
            .await
            .map_err(|e| ApiError::Transport(describe_error_chain(&e)))?;
        if resp.status() == reqwest::StatusCode::NOT_FOUND {
            return Ok(Vec::new());
        }
        let resp = self.check(resp).await?;
        let result: MediaSegmentDtoQueryResult =
            read_json_capped(resp, "/MediaSegments/{itemId}").await?;
        Ok(result.items)
    }

    pub async fn report_playback(&self, report: PlaybackReport) -> Result<(), ApiError> {
        let path = match report.kind {
            PlaybackReportKind::Start => "/Sessions/Playing",
            PlaybackReportKind::Progress => "/Sessions/Playing/Progress",
            PlaybackReportKind::Stopped => "/Sessions/Playing/Stopped",
        };
        let url = format!("{}{}", self.inner.base_url, path);
        let request = self
            .inner
            .http
            .post(&url)
            .header("Authorization", self.auth_header());

        let resp = match report.kind {
            PlaybackReportKind::Stopped => request.json(&stop_body(&report)).send().await,
            PlaybackReportKind::Start | PlaybackReportKind::Progress => {
                request.json(&start_progress_body(&report)).send().await
            }
        }
        .map_err(|e| ApiError::Transport(describe_error_chain(&e)))?;

        self.check(resp).await?;
        Ok(())
    }

    /// Shared shape for the `/UserPlayedItems/{id}` and `/UserFavoriteItems/{id}` pairs: POST or
    /// DELETE with no body, `userId` sent whenever known, decoding the `UserItemDataDto` echoed
    /// back.
    async fn mutate_user_item_data(
        &self,
        method: reqwest::Method,
        path_prefix: &str,
        item_id: &str,
    ) -> Result<UserItemDataDto, ApiError> {
        let url = format!(
            "{}{}/{}",
            self.inner.base_url,
            path_prefix,
            percent_encode(item_id)
        );
        let mut query: Vec<(&str, String)> = Vec::new();
        self.push_user_id(&mut query);
        let resp = self
            .inner
            .http
            .request(method, &url)
            .header("Authorization", self.auth_header())
            .query(&query)
            .send()
            .await
            .map_err(|e| ApiError::Transport(describe_error_chain(&e)))?;
        let resp = self.check(resp).await?;
        read_json_capped(resp, path_prefix).await
    }

    /// `POST /UserPlayedItems/{itemId}` -- docs/19-detail-action-menu.md §2.1. Decodes the returned
    /// `UserItemDataDto` so the caller can apply it to the mirror directly, without waiting on a WS
    /// `UserDataChanged` round trip.
    pub async fn mark_played(&self, item_id: &str) -> Result<UserItemDataDto, ApiError> {
        self.mutate_user_item_data(reqwest::Method::POST, "/UserPlayedItems", item_id)
            .await
    }

    /// `DELETE /UserPlayedItems/{itemId}` -- docs/19-detail-action-menu.md
    /// §2.1.
    pub async fn mark_unplayed(&self, item_id: &str) -> Result<UserItemDataDto, ApiError> {
        self.mutate_user_item_data(reqwest::Method::DELETE, "/UserPlayedItems", item_id)
            .await
    }

    /// `POST`/`DELETE /UserFavoriteItems/{itemId}` -- docs/19-detail-action-menu.md
    /// §2.1. `favorite` selects the verb: `true` adds the favorite (POST),
    /// `false` removes it (DELETE).
    pub async fn set_favorite(
        &self,
        item_id: &str,
        favorite: bool,
    ) -> Result<UserItemDataDto, ApiError> {
        let method = if favorite {
            reqwest::Method::POST
        } else {
            reqwest::Method::DELETE
        };
        self.mutate_user_item_data(method, "/UserFavoriteItems", item_id)
            .await
    }

    /// The session's live BoxSet list -- docs/19-detail-action-menu.md §2.1. Capped at 200
    /// (session-cached per §2.3, not a paged browse -- the cap just bounds a pathological library).
    pub async fn list_collections(&self) -> Result<Vec<BaseItemDto>, ApiError> {
        let query = ItemQuery {
            include_item_types: vec!["BoxSet".to_string()],
            recursive: true,
            sort_by: Some("SortName".to_string()),
            sort_order: Some("Ascending".to_string()),
            limit: 200,
            ..ItemQuery::new()
        };
        let result = self.get_items(&query).await?;
        Ok(result.items)
    }

    /// `POST /Collections/{collectionId}/Items?ids={itemId}` --
    /// docs/19-detail-action-menu.md §2.1. `204 No Content`, no body;
    /// membership is server-owned, the mirror isn't touched locally.
    pub async fn add_to_collection(
        &self,
        collection_id: &str,
        item_id: &str,
    ) -> Result<(), ApiError> {
        let url = format!(
            "{}/Collections/{}/Items",
            self.inner.base_url,
            percent_encode(collection_id)
        );
        let resp = self
            .inner
            .http
            .post(&url)
            .header("Authorization", self.auth_header())
            .query(&[("ids", item_id.to_string())])
            .send()
            .await
            .map_err(|e| ApiError::Transport(describe_error_chain(&e)))?;
        self.check(resp).await?;
        Ok(())
    }

    /// `POST /Items/{itemId}/Refresh` with fixed parameters --
    /// docs/19-detail-action-menu.md §2.1/§1.4: full metadata+image refresh
    /// without replacing already-downloaded ones. `204 No Content`, no body.
    pub async fn refresh_item(&self, item_id: &str) -> Result<(), ApiError> {
        let url = format!(
            "{}/Items/{}/Refresh",
            self.inner.base_url,
            percent_encode(item_id)
        );
        let resp = self
            .inner
            .http
            .post(&url)
            .header("Authorization", self.auth_header())
            .query(&[
                ("metadataRefreshMode", "FullRefresh"),
                ("imageRefreshMode", "FullRefresh"),
                ("replaceAllMetadata", "false"),
                ("replaceAllImages", "false"),
            ])
            .send()
            .await
            .map_err(|e| ApiError::Transport(describe_error_chain(&e)))?;
        self.check(resp).await?;
        Ok(())
    }

    /// `GET /Users/Me` -- docs/19-detail-action-menu.md §2.1, the source of
    /// `is_administrator()`'s `UserPolicy.IsAdministrator` (§2.3).
    pub async fn current_user(&self) -> Result<UserDto, ApiError> {
        self.get("/Users/Me", &[]).await
    }

    /// Re-fetches `/System/Info/Public` against this client's own `base_url` (docs/13 Server
    /// compatibility) -- called at sign-in, session restore, account switch,
    /// reauthorization, and periodically thereafter, since the TV has no websocket to notice a
    /// server upgrade otherwise.
    pub async fn refresh_public_system_info(&self) -> Result<PublicServerInfo, ApiError> {
        self.get("/System/Info/Public", &[]).await
    }

    /// `GET /System/Info` (authenticated) -- docs/13 About > server info on-demand fetch. Requires
    /// an admin-capable token on some server configs; a 401/403 is the caller's cue to fall back
    /// to [`Self::refresh_public_system_info`].
    pub async fn system_info(&self) -> Result<ServerSystemInfo, ApiError> {
        self.get("/System/Info", &[]).await
    }

    /// `GET /Items/Counts` -- docs/13 About > server info. `userId` sent whenever known, same as
    /// the other user-scoped calls in this file.
    pub async fn item_counts(&self) -> Result<ItemCounts, ApiError> {
        let mut query: Vec<(&str, String)> = Vec::new();
        self.push_user_id(&mut query);
        self.get("/Items/Counts", &query).await
    }

    /// Builds a direct-fetch image URL: `/Items/{id}/Images/{kind}?tag=...&ApiKey=...`. Carries the
    /// raw access token in `ApiKey`, the only query-string auth form Jellyfin 12 still accepts by
    /// default (also accepted by 10.11/10.10). Treat the returned string as a credential:
    /// debug-level logging at most, no persistence beyond an in-memory cache key, never in crash
    /// reports/telemetry.
    pub fn image_url(&self, item_id: &str, kind: ImageKind, tag: &str, max_width: u32) -> String {
        let kind_str = match kind {
            ImageKind::Primary => "Primary",
            ImageKind::Backdrop => "Backdrop",
            ImageKind::Thumb => "Thumb",
        };
        // Without `quality`, Jellyfin re-encodes at near-lossless JPEG (5-8x larger than needed).
        // `format=Webp` + per-kind quality (90 sharp art, 80 dimmed/blurred backdrops) cuts that;
        // an older server ignoring these params just returns JPEG, which the decode path sniffs.
        let quality = match kind {
            ImageKind::Backdrop => 80,
            ImageKind::Primary | ImageKind::Thumb => 90,
        };
        format!(
            "{}/Items/{}/Images/{}?tag={}&maxWidth={}&quality={}&format=Webp&ApiKey={}",
            self.inner.base_url,
            percent_encode(item_id),
            kind_str,
            percent_encode(tag),
            max_width,
            quality,
            percent_encode(&self.inner.token),
        )
    }

    /// Builds a trickplay tile-sheet image URL (`mediaSourceId` only when `Some`). `width` must be
    /// one advertised in the item's `TrickplayInfoDto::width`; `tile_index` addresses a tile in
    /// that sheet. Carries the raw access token in `ApiKey`, same as [`Self::image_url`] -- treat
    /// it as a credential.
    pub fn trickplay_tile_url(
        &self,
        item_id: &str,
        width: u32,
        tile_index: u32,
        media_source_id: Option<&str>,
    ) -> String {
        let mut url = format!(
            "{}/Videos/{}/Trickplay/{}/{}.jpg?",
            self.inner.base_url,
            percent_encode(item_id),
            width,
            tile_index,
        );
        if let Some(media_source_id) = media_source_id {
            url.push_str("mediaSourceId=");
            url.push_str(&percent_encode(media_source_id));
            url.push('&');
        }
        url.push_str("ApiKey=");
        url.push_str(&percent_encode(&self.inner.token));
        url
    }

    /// Static direct-play stream: `/Videos/{itemId}/stream?static=true&mediaSourceId=...`.
    /// If the source carries a `TranscodingUrl` (HLS), that URL is used
    /// instead (resolved against `base_url` if relative) -- it's the
    /// server's own transcode-vs-direct-play decision and must pass through
    /// as-is.
    ///
    /// `item_id` and `source.id` are not interchangeable: a plugin channel
    /// recording (e.g. a TVHeadend tuner item) issues a short
    /// source-scoped `MediaSources[0].Id` that 400s if used as the path
    /// segment. The path segment must always be the real item id;
    /// `mediaSourceId` is the only place `source.id` belongs.
    ///
    /// Carries the raw access token in `ApiKey`, same as [`Self::image_url`]
    /// -- treat the returned string as a credential.
    pub fn stream_url(&self, item_id: &str, source: &MediaSourceInfo) -> String {
        if let Some(transcoding_url) = &source.transcoding_url {
            if transcoding_url.starts_with("http://") || transcoding_url.starts_with("https://") {
                return transcoding_url.clone();
            }
            let path = transcoding_url.strip_prefix('/').unwrap_or(transcoding_url);
            return format!("{}/{}", self.inner.base_url, path);
        }

        let media_source_id = match &source.id {
            Some(id) => id.clone(),
            None => {
                // A well-behaved server always sets MediaSourceInfo.id; silently building a URL
                // with an empty mediaSourceId would just 404/400 with no clue why.
                tracing::warn!(
                    "stream_url: MediaSourceInfo.id is missing; built URL will have an empty mediaSourceId"
                );
                String::new()
            }
        };
        format!(
            "{}/Videos/{}/stream?static=true&mediaSourceId={}&ApiKey={}",
            self.inner.base_url,
            percent_encode(item_id),
            percent_encode(&media_source_id),
            percent_encode(&self.inner.token),
        )
    }

    /// Absolute, authenticated URL for a `MediaStream` `DeliveryUrl` (docs/18 §3.2): carries
    /// `ApiKey` the way [`Self::stream_url`] does. `None` for an off-server absolute URL, so
    /// the token never reaches a third-party host. Never log the result.
    pub fn delivery_url(&self, delivery_url: &str) -> Option<String> {
        authed_delivery_url(&self.inner.base_url, &self.inner.token, delivery_url)
    }

    /// GETs a `MediaStream` `DeliveryUrl` sidecar as text (docs/18 §3.2): authenticated like
    /// [`Self::delivery_url`] (an off-server URL is refused before any request), at most
    /// `max_bytes`, 2xx and valid UTF-8 only. No overall deadline here: callers wrap it. Errors
    /// never carry the URL or token.
    pub async fn fetch_delivery_text(
        &self,
        delivery_url: &str,
        max_bytes: usize,
    ) -> Result<String, ApiError> {
        text_from_delivery_body(self.fetch_delivery_bytes(delivery_url, max_bytes).await?)
    }

    /// [`Self::fetch_delivery_text`] without the UTF-8 check, for a reader that decodes the bytes itself.
    pub async fn fetch_delivery_bytes(
        &self,
        delivery_url: &str,
        max_bytes: usize,
    ) -> Result<Vec<u8>, ApiError> {
        let url = self
            .delivery_url(delivery_url)
            .ok_or_else(|| ApiError::Decode("delivery url is off-server".to_string()))?;
        let resp = self
            .inner
            .http
            .get(&url)
            .send()
            .await
            // The transport message can embed the request URL; keep only that it failed.
            .map_err(|_| ApiError::Transport("delivery fetch failed".to_string()))?;
        let status = resp.status().as_u16();
        if !(200..300).contains(&status) {
            return Err(ApiError::Status {
                code: status,
                body: String::new(),
            });
        }
        read_capped_body(resp, max_bytes, "delivery text")
            .await
            .map_err(|e| match e {
                ApiError::Transport(_) => ApiError::Transport("delivery read failed".to_string()),
                other => other,
            })
    }

    /// This client's server base URL (scheme://host[:port], no trailing
    /// slash) -- e.g. as a cache key for per-server measured state.
    pub fn base_url(&self) -> &str {
        &self.inner.base_url
    }

    /// The raw access token `image_url` carries in `ApiKey`, for a caller that formats image URLs
    /// itself from [`Self::base_url`] (same credential rules as [`Self::image_url`]).
    pub fn image_url_token(&self) -> &str {
        &self.inner.token
    }

    /// Whether `other` is the same signed-in account and credential as this client, without
    /// exposing the token.
    pub fn same_account(&self, other: &Self) -> bool {
        self.inner.base_url == other.inner.base_url
            && self.inner.token == other.inner.token
            && self.user_id() == other.user_id()
    }

    /// Warms the play path for an item the user is looking at but hasn't played (ranged GET of the
    /// first `max_bytes`): primes the OS page cache and keeps the pooled HTTP connection hot.
    /// Fire-and-forget: callers drop the result.
    pub async fn warm_stream_head(&self, item_id: &str, max_bytes: u64) -> Result<(), ApiError> {
        let url = format!(
            "{}/Videos/{}/stream?static=true&ApiKey={}",
            self.inner.base_url,
            percent_encode(item_id),
            percent_encode(&self.inner.token),
        );
        let resp = self
            .inner
            .http
            .get(&url)
            .header("Range", format!("bytes=0-{}", max_bytes.saturating_sub(1)))
            .send()
            .await
            .map_err(|e| ApiError::Transport(describe_error_chain(&e)))?;
        // Drain up to the ranged size so the server reads the file head rather than aborting on
        // disconnect; capped since a misbehaving server could ignore Range and stream the whole
        // file.
        let cap = usize::try_from(max_bytes.saturating_add(64 * 1024)).unwrap_or(usize::MAX);
        let _ = read_capped_body(resp, cap, "stream-warm head").await?;
        Ok(())
    }

    /// Connect the WebSocket; events arrive on the returned channel until drop.
    /// Reconnection is the CALLER's job (jellyfin-core session) — this is one connection.
    pub async fn connect_ws(&self) -> Result<tokio::sync::mpsc::Receiver<ServerEvent>, ApiError> {
        ws::connect(
            &self.inner.base_url,
            &self.inner.identity,
            &self.inner.token,
        )
        .await
        .map_err(|err| self.owned(err))
    }
}

#[derive(Debug, Clone, Copy)]
pub enum ImageKind {
    Primary,
    Backdrop,
    Thumb,
}

/// [`JellyfinClient::get_playback_info`]'s options beyond item/profile/start-ticks
/// (docs/18-playback-quality.md's Auto/Cap transcode-fallback path). `Default` (`force_transcode:
/// false`) matches the pre-existing request body.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct PlaybackInfoOptions {
    /// When `true`, sets `EnableDirectPlay`/`EnableDirectStream` to
    /// `Some(false)` -- forces the server to negotiate a transcode rather
    /// than re-deciding Direct Play.
    pub force_transcode: bool,
    /// The source being renegotiated; the server applies the stream choices
    /// below only to the source named here.
    pub media_source_id: Option<String>,
    /// docs/18 §2: `Some(false)` forbids copying that stream type, so a type
    /// that just failed to decode is re-encoded rather than handed back.
    pub allow_video_stream_copy: Option<bool>,
    pub allow_audio_stream_copy: Option<bool>,
    /// docs/18 §3.1: the subtitle stream the viewer chose (`-1` for none), honoured only with
    /// `media_source_id`; `None` leaves the server's default, which may burn it in.
    pub subtitle_stream_index: Option<i32>,
}

/// Start/progress/stopped payloads unified, mapped to the three endpoints
/// below. NOTE: `volume_level` must serialize as an integer -- the server
/// 400s otherwise (see the regression test).
#[derive(Debug, Clone)]
pub struct PlaybackReport {
    pub kind: PlaybackReportKind,
    pub item_id: String,
    pub media_source_id: String,
    pub position_ticks: i64,
    pub is_paused: bool,
    pub volume_level: u8,
    pub audio_stream_index: Option<i32>,
    pub subtitle_stream_index: Option<i32>,
    pub play_session_id: String,
    /// docs/18-playback-quality.md §2: sent as `"PlayMethod"` on Start/Progress only -- Stopped
    /// kills a transcode job via `PlaySessionId` alone, so [`StopBody`] has no need for this.
    pub play_method: ReportPlayMethod,
}

#[derive(Debug, Clone, Copy)]
pub enum PlaybackReportKind {
    Start,
    Progress,
    Stopped,
}

/// The wire value of Start/Progress's `"PlayMethod"` field
/// (docs/18-playback-quality.md §2) -- `Display` gives the exact server
/// strings, asserted in [`playback_report_start_progress_body_exact_json`].
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ReportPlayMethod {
    DirectPlay,
    Transcode,
}

impl ReportPlayMethod {
    /// The exact `"PlayMethod"` wire string, used by [`start_progress_body`] directly and by this
    /// type's [`std::fmt::Display`] impl.
    fn as_wire_str(self) -> &'static str {
        match self {
            ReportPlayMethod::DirectPlay => "DirectPlay",
            ReportPlayMethod::Transcode => "Transcode",
        }
    }
}

impl std::fmt::Display for ReportPlayMethod {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(self.as_wire_str())
    }
}

/// Decoded WebSocket events Jellybeam cares about; everything else is Ignored(name).
#[derive(Debug, Clone)]
pub enum ServerEvent {
    LibraryChanged {
        added: Vec<String>,
        updated: Vec<String>,
        removed: Vec<String>,
    },
    UserDataChanged {
        item_userdata: Vec<(String, UserItemDataDto)>,
    },
    ForceKeepAlive,
    Ignored(String),
}

/// Request body for `POST /Sessions/Playing`/`Playing/Progress`. Hand-written so `VolumeLevel`
/// stays `u8`, always serializing as a JSON integer -- the server 400s a progress report if it
/// arrives as a float. See `playback_report_serialization` tests.
#[derive(Debug, serde::Serialize)]
struct StartProgressBody<'a> {
    #[serde(rename = "ItemId")]
    item_id: &'a str,
    #[serde(rename = "MediaSourceId")]
    media_source_id: &'a str,
    #[serde(rename = "PositionTicks")]
    position_ticks: i64,
    #[serde(rename = "IsPaused")]
    is_paused: bool,
    #[serde(rename = "IsMuted")]
    is_muted: bool,
    #[serde(rename = "VolumeLevel")]
    volume_level: u8,
    #[serde(rename = "AudioStreamIndex", skip_serializing_if = "Option::is_none")]
    audio_stream_index: Option<i32>,
    #[serde(
        rename = "SubtitleStreamIndex",
        skip_serializing_if = "Option::is_none"
    )]
    subtitle_stream_index: Option<i32>,
    #[serde(rename = "PlaySessionId")]
    play_session_id: &'a str,
    #[serde(rename = "CanSeek")]
    can_seek: bool,
    /// docs/18-playback-quality.md §2: Start/Progress only, never [`StopBody`].
    #[serde(rename = "PlayMethod")]
    play_method: &'static str,
}

/// Request body for `POST /Sessions/Playing/Stopped`.
#[derive(Debug, serde::Serialize)]
struct StopBody<'a> {
    #[serde(rename = "ItemId")]
    item_id: &'a str,
    #[serde(rename = "MediaSourceId")]
    media_source_id: &'a str,
    #[serde(rename = "PositionTicks")]
    position_ticks: i64,
    #[serde(rename = "PlaySessionId")]
    play_session_id: &'a str,
}

fn start_progress_body(report: &PlaybackReport) -> StartProgressBody<'_> {
    StartProgressBody {
        item_id: &report.item_id,
        media_source_id: &report.media_source_id,
        position_ticks: report.position_ticks,
        is_paused: report.is_paused,
        is_muted: false,
        volume_level: report.volume_level,
        audio_stream_index: report.audio_stream_index,
        subtitle_stream_index: report.subtitle_stream_index,
        play_session_id: &report.play_session_id,
        can_seek: true,
        play_method: report.play_method.as_wire_str(),
    }
}

fn stop_body(report: &PlaybackReport) -> StopBody<'_> {
    StopBody {
        item_id: &report.item_id,
        media_source_id: &report.media_source_id,
        position_ticks: report.position_ticks,
        play_session_id: &report.play_session_id,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample_identity() -> ClientIdentity {
        ClientIdentity {
            client: "Jellybeam".to_string(),
            device: "Test Mac".to_string(),
            device_id: "device-123".to_string(),
            version: "0.1.0".to_string(),
        }
    }

    #[test]
    fn auth_header_without_token() {
        let h = auth_header(&sample_identity(), None);
        assert_eq!(
            h,
            r#"MediaBrowser Client="Jellybeam", Device="Test Mac", DeviceId="device-123", Version="0.1.0""#
        );
    }

    #[test]
    fn auth_header_with_token() {
        let h = auth_header(&sample_identity(), Some("tok"));
        assert_eq!(
            h,
            r#"MediaBrowser Client="Jellybeam", Device="Test Mac", DeviceId="device-123", Version="0.1.0", Token="tok""#
        );
    }

    /// Pins: a device name containing a quote and backslash must not prematurely close the quoted
    /// `Device="..."` value or corrupt the header.
    #[test]
    fn auth_header_escapes_embedded_quotes_and_backslashes() {
        let identity = ClientIdentity {
            client: "Jellybeam".to_string(),
            device: r#"Device "A"\Test"#.to_string(),
            device_id: "device-123".to_string(),
            version: "0.1.0".to_string(),
        };
        let h = auth_header(&identity, Some(r#"tok"en"#));
        assert_eq!(
            h,
            r#"MediaBrowser Client="Jellybeam", Device="Device \"A\"\\Test", DeviceId="device-123", Version="0.1.0", Token="tok\"en""#
        );
    }

    /// Pins: bare CR/LF in a value is stripped, not passed through where it could inject header
    /// lines.
    #[test]
    fn auth_header_strips_embedded_crlf() {
        let identity = ClientIdentity {
            client: "Jellybeam".to_string(),
            device: "evil\r\nX-Injected: true".to_string(),
            device_id: "device-123".to_string(),
            version: "0.1.0".to_string(),
        };
        let h = auth_header(&identity, None);
        assert!(!h.contains('\r'));
        assert!(!h.contains('\n'));
        assert_eq!(
            h,
            r#"MediaBrowser Client="Jellybeam", Device="evilX-Injected: true", DeviceId="device-123", Version="0.1.0""#
        );
    }

    fn sample_report(kind: PlaybackReportKind) -> PlaybackReport {
        PlaybackReport {
            kind,
            item_id: "item-1".to_string(),
            media_source_id: "source-1".to_string(),
            position_ticks: 123_456_789,
            is_paused: true,
            volume_level: 42,
            audio_stream_index: Some(1),
            subtitle_stream_index: None,
            play_session_id: "session-1".to_string(),
            play_method: ReportPlayMethod::DirectPlay,
        }
    }

    /// Pins: `volume_level=42` serializes `VolumeLevel` as the JSON integer `42`, never `42.0` --
    /// the server's int32 model binder 400s on a float.
    #[test]
    fn playback_report_serializes_volume_level_as_integer() {
        let report = sample_report(PlaybackReportKind::Progress);
        let value = serde_json::to_value(start_progress_body(&report)).expect("serializes");
        assert_eq!(value["VolumeLevel"], serde_json::json!(42));
        assert!(
            value["VolumeLevel"].is_u64(),
            "VolumeLevel must be a JSON integer, got {value}"
        );

        let raw = serde_json::to_string(&value).expect("serializes");
        assert!(raw.contains(r#""VolumeLevel":42"#), "raw JSON: {raw}");
        assert!(!raw.contains("42.0"), "raw JSON: {raw}");
    }

    #[test]
    fn playback_report_start_progress_body_exact_json() {
        let report = sample_report(PlaybackReportKind::Start);
        let value = serde_json::to_value(start_progress_body(&report)).expect("serializes");
        assert_eq!(
            value,
            serde_json::json!({
                "ItemId": "item-1",
                "MediaSourceId": "source-1",
                "PositionTicks": 123_456_789,
                "IsPaused": true,
                "IsMuted": false,
                "VolumeLevel": 42,
                "AudioStreamIndex": 1,
                "PlaySessionId": "session-1",
                "CanSeek": true,
                "PlayMethod": "DirectPlay",
            })
        );
    }

    /// docs/18-playback-quality.md §2: a Transcode report sends the exact wire string
    /// `"Transcode"`, not a case-shifted or Rust-debug spelling.
    #[test]
    fn playback_report_start_progress_body_sends_transcode_play_method() {
        let report = PlaybackReport {
            play_method: ReportPlayMethod::Transcode,
            ..sample_report(PlaybackReportKind::Progress)
        };
        let value = serde_json::to_value(start_progress_body(&report)).expect("serializes");
        assert_eq!(value["PlayMethod"], "Transcode");
    }

    #[test]
    fn playback_report_stop_body_exact_json() {
        let report = sample_report(PlaybackReportKind::Stopped);
        let value = serde_json::to_value(stop_body(&report)).expect("serializes");
        assert_eq!(
            value,
            serde_json::json!({
                "ItemId": "item-1",
                "MediaSourceId": "source-1",
                "PositionTicks": 123_456_789,
                "PlaySessionId": "session-1",
            })
        );
    }

    #[test]
    fn image_url_builds_expected_query() {
        let client = JellyfinClient::from_token(
            "http://localhost:8096",
            sample_identity(),
            "tok en/with?special",
        );
        let url = client.image_url("item 1", ImageKind::Primary, "tag&1", 300);
        assert_eq!(
            url,
            "http://localhost:8096/Items/item%201/Images/Primary?tag=tag%261&maxWidth=300&quality=90&format=Webp&ApiKey=tok%20en%2Fwith%3Fspecial"
        );
    }

    #[test]
    fn image_url_kinds() {
        let client = JellyfinClient::from_token("http://localhost:8096", sample_identity(), "t");
        assert!(client
            .image_url("i", ImageKind::Backdrop, "tag", 100)
            .contains("/Images/Backdrop?"));
        assert!(client
            .image_url("i", ImageKind::Thumb, "tag", 100)
            .contains("/Images/Thumb?"));
    }

    /// Backdrops render dimmed/blurred, so they ship at a lower re-encode
    /// quality than sharp grid art.
    #[test]
    fn image_url_quality_is_lower_for_backdrops_than_sharp_art() {
        let client = JellyfinClient::from_token("http://localhost:8096", sample_identity(), "t");
        assert!(client
            .image_url("i", ImageKind::Backdrop, "tag", 1280)
            .contains("quality=80"));
        assert!(client
            .image_url("i", ImageKind::Primary, "tag", 320)
            .contains("quality=90"));
        assert!(client
            .image_url("i", ImageKind::Thumb, "tag", 400)
            .contains("quality=90"));
    }

    // --- trickplay_tile_url ---------------------------------------

    #[test]
    fn trickplay_tile_url_without_media_source_id() {
        let client = JellyfinClient::from_token(
            "http://localhost:8096",
            sample_identity(),
            "tok en/with?special",
        );
        let url = client.trickplay_tile_url("item 1", 320, 7, None);
        assert_eq!(
            url,
            "http://localhost:8096/Videos/item%201/Trickplay/320/7.jpg?ApiKey=tok%20en%2Fwith%3Fspecial"
        );
    }

    #[test]
    fn trickplay_tile_url_with_media_source_id() {
        let client = JellyfinClient::from_token("http://localhost:8096", sample_identity(), "tok");
        let url = client.trickplay_tile_url("item-1", 320, 7, Some("source 1&x"));
        assert_eq!(
            url,
            "http://localhost:8096/Videos/item-1/Trickplay/320/7.jpg?mediaSourceId=source%201%26x&ApiKey=tok"
        );
    }

    #[test]
    fn stream_url_direct_play_uses_static_videos_endpoint() {
        let client = JellyfinClient::from_token("http://localhost:8096", sample_identity(), "tok");
        let source = MediaSourceInfo {
            id: Some("src-1".to_string()),
            ..Default::default()
        };
        let url = client.stream_url("src-1", &source);
        assert_eq!(
            url,
            "http://localhost:8096/Videos/src-1/stream?static=true&mediaSourceId=src-1&ApiKey=tok"
        );
    }

    /// A plugin channel recording (e.g. a TVHeadend tuner item) has
    /// `MediaSources[0].Id` set to a short id that is NOT the item id. The
    /// stream path segment must be the item id (server 400s otherwise);
    /// only `mediaSourceId` uses `source.id`.
    #[test]
    fn stream_url_uses_item_id_for_path_and_source_id_for_query_when_they_differ() {
        let client = JellyfinClient::from_token("http://localhost:8096", sample_identity(), "tok");
        let source = MediaSourceInfo {
            id: Some("ab12cd34".to_string()),
            ..Default::default()
        };
        let url = client.stream_url("item-1", &source);
        assert_eq!(
            url,
            "http://localhost:8096/Videos/item-1/stream?static=true&mediaSourceId=ab12cd34&ApiKey=tok"
        );
    }

    /// A missing `MediaSourceInfo.id` must not panic `stream_url`
    /// (infallible by design) -- it builds a URL with an empty
    /// `mediaSourceId` plus a `tracing::warn!`. Only the non-panicking
    /// build is asserted; the warn isn't (would need a test-only tracing
    /// subscriber).
    #[test]
    fn stream_url_with_missing_media_source_id_still_builds_a_url() {
        let client = JellyfinClient::from_token("http://localhost:8096", sample_identity(), "tok");
        let source = MediaSourceInfo {
            id: None,
            ..Default::default()
        };
        let url = client.stream_url("item-1", &source);
        assert_eq!(
            url,
            "http://localhost:8096/Videos/item-1/stream?static=true&mediaSourceId=&ApiKey=tok"
        );
    }

    // --- ApiError::Status body capture -----------------------------------

    #[test]
    fn api_error_status_display_includes_body_when_present() {
        let err = ApiError::Status {
            code: 404,
            body: "item not found".to_string(),
        };
        assert_eq!(err.to_string(), "http status 404: item not found");
    }

    #[test]
    fn api_error_status_display_omits_colon_when_body_empty() {
        let err = ApiError::Status {
            code: 500,
            body: String::new(),
        };
        assert_eq!(err.to_string(), "http status 500");
    }

    #[tokio::test]
    async fn capped_body_text_truncates_at_the_byte_cap() {
        // Body well over the cap; captured text must stay bounded and
        // marked truncated.
        let big_body = "x".repeat(STATUS_ERROR_BODY_CAP * 2);
        let server = httpbin_style_server(big_body.clone()).await;
        let resp = reqwest::get(&server)
            .await
            .expect("request the mock server");
        let text = capped_body_text(resp, STATUS_ERROR_BODY_CAP).await;
        assert!(
            text.len() <= STATUS_ERROR_BODY_CAP + "... [truncated]".len(),
            "captured body should stay near the {STATUS_ERROR_BODY_CAP}-byte cap, got {} bytes",
            text.len()
        );
        assert!(
            text.ends_with("... [truncated]"),
            "truncated body should be marked as such: {text}"
        );
    }

    #[tokio::test]
    async fn capped_body_text_returns_short_body_unmodified() {
        let server = httpbin_style_server("short error body".to_string()).await;
        let resp = reqwest::get(&server)
            .await
            .expect("request the mock server");
        let text = capped_body_text(resp, STATUS_ERROR_BODY_CAP).await;
        assert_eq!(text, "short error body");
    }

    /// Pins: the server promises far more body than it ever sends and never closes the
    /// connection, so a whole-body read would hang. `capped_body_text` must stop pulling chunks
    /// once `cap` bytes are collected instead of waiting for the promised remainder.
    #[tokio::test]
    async fn capped_body_text_stops_reading_once_the_cap_is_collected() {
        let cap = 64;
        let server = hanging_oversized_body_server(cap).await;
        let resp = reqwest::get(&server)
            .await
            .expect("request the mock server");
        let text = tokio::time::timeout(Duration::from_secs(5), capped_body_text(resp, cap))
            .await
            .expect("must not wait for bytes beyond the cap");
        assert_eq!(text, format!("{}... [truncated]", "x".repeat(cap)));
    }

    /// One-shot HTTP server that sends exactly `cap` bytes of body, claims a much larger
    /// `Content-Length`, then goes silent without closing the connection.
    async fn hanging_oversized_body_server(cap: usize) -> String {
        use tokio::io::AsyncWriteExt;
        use tokio::net::TcpListener;

        let listener = TcpListener::bind("127.0.0.1:0")
            .await
            .expect("bind local listener");
        let addr = listener.local_addr().expect("local_addr");
        tokio::spawn(async move {
            if let Ok((mut stream, _)) = listener.accept().await {
                let mut discard = [0u8; 1024];
                let _ = tokio::io::AsyncReadExt::read(&mut stream, &mut discard).await;
                let head = format!(
                    "HTTP/1.1 500 Internal Server Error\r\nContent-Length: {}\r\n\r\n",
                    cap * 100
                );
                let _ = stream.write_all(head.as_bytes()).await;
                let _ = stream.write_all(&vec![b'x'; cap]).await;
                // Never sends the rest, never closes -- a whole-body read would hang here.
                tokio::time::sleep(Duration::from_mins(1)).await;
            }
        });
        format!("http://{addr}/")
    }

    /// One-shot local HTTP server answering its first request with a bare 401.
    async fn unauthorized_server() -> String {
        use tokio::io::AsyncWriteExt;
        use tokio::net::TcpListener;

        let listener = TcpListener::bind("127.0.0.1:0")
            .await
            .expect("bind local listener");
        let addr = listener.local_addr().expect("local_addr");
        tokio::spawn(async move {
            if let Ok((mut stream, _)) = listener.accept().await {
                let mut discard = [0u8; 4096];
                let _ = tokio::io::AsyncReadExt::read(&mut stream, &mut discard).await;
                let response =
                    "HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
                let _ = stream.write_all(response.as_bytes()).await;
                let _ = stream.shutdown().await;
            }
        });
        format!("http://{addr}")
    }

    /// Every request sent with a client's token names that token's owner on a 401, so the app
    /// re-authorizes the account that sent it; one sent before sign-in names nobody.
    #[tokio::test]
    async fn a_401_names_the_account_whose_token_was_rejected() {
        type Call =
            fn(JellyfinClient) -> std::pin::Pin<Box<dyn std::future::Future<Output = ApiError>>>;
        let calls: [(&str, Call); 8] = [
            ("get", |c| {
                Box::pin(async move { c.get_user_views().await.expect_err("401") })
            }),
            ("playback info", |c| {
                Box::pin(async move {
                    c.get_playback_info(
                        "item",
                        &DeviceProfile::default(),
                        None,
                        PlaybackInfoOptions::default(),
                    )
                    .await
                    .expect_err("401")
                })
            }),
            ("media segments", |c| {
                Box::pin(async move { c.get_media_segments("item", &[]).await.expect_err("401") })
            }),
            ("report", |c| {
                Box::pin(async move {
                    c.report_playback(sample_report(PlaybackReportKind::Progress))
                        .await
                        .expect_err("401")
                })
            }),
            ("user item data", |c| {
                Box::pin(async move { c.mark_played("item").await.expect_err("401") })
            }),
            ("add to collection", |c| {
                Box::pin(async move { c.add_to_collection("box", "item").await.expect_err("401") })
            }),
            ("refresh", |c| {
                Box::pin(async move { c.refresh_item("item").await.expect_err("401") })
            }),
            ("websocket", |c| {
                Box::pin(async move { c.connect_ws().await.expect_err("401") })
            }),
        ];
        for (name, call) in calls {
            let base_url = unauthorized_server().await;
            let client = JellyfinClient::from_token(&base_url, sample_identity(), "tok")
                .with_user_id("user-1");
            let err = call(client).await;
            let ApiError::Unauthorized { owner } = err else {
                panic!("{name}: expected Unauthorized, got {err:?}");
            };
            assert_eq!(
                owner,
                Some(TokenOwner {
                    server_url: base_url,
                    user_id: "user-1".to_string()
                }),
                "{name}"
            );
        }

        let base_url = unauthorized_server().await;
        let err = JellyfinClient::public_system_info(&base_url, &sample_identity())
            .await
            .expect_err("401");
        assert!(
            matches!(err, ApiError::Unauthorized { owner: None }),
            "{err:?}"
        );
    }

    /// Minimal one-shot HTTP server (raw TCP) returning `body` for the
    /// first request, then stopping. Local-only (127.0.0.1, ephemeral port).
    async fn httpbin_style_server(body: String) -> String {
        use tokio::io::AsyncWriteExt;
        use tokio::net::TcpListener;

        let listener = TcpListener::bind("127.0.0.1:0")
            .await
            .expect("bind local listener");
        let addr = listener.local_addr().expect("local_addr");
        tokio::spawn(async move {
            if let Ok((mut stream, _)) = listener.accept().await {
                // Drain the request; no need to parse it.
                let mut discard = [0u8; 1024];
                let _ = tokio::io::AsyncReadExt::read(&mut stream, &mut discard).await;
                let response = format!(
                    "HTTP/1.1 500 Internal Server Error\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                    body.len(),
                    body
                );
                let _ = stream.write_all(response.as_bytes()).await;
                let _ = stream.shutdown().await;
            }
        });
        format!("http://{addr}/")
    }

    #[test]
    fn stream_url_transcode_passes_through_absolute_url() {
        let client = JellyfinClient::from_token("http://localhost:8096", sample_identity(), "tok");
        let source = MediaSourceInfo {
            transcoding_url: Some("https://other-host/hls/master.m3u8?a=1".to_string()),
            ..Default::default()
        };
        assert_eq!(
            client.stream_url("item-1", &source),
            "https://other-host/hls/master.m3u8?a=1"
        );
    }

    #[test]
    fn stream_url_transcode_resolves_relative_url_against_base() {
        let client = JellyfinClient::from_token("http://localhost:8096", sample_identity(), "tok");
        let source = MediaSourceInfo {
            transcoding_url: Some("/videos/1/master.m3u8?DeviceId=x".to_string()),
            ..Default::default()
        };
        assert_eq!(
            client.stream_url("item-1", &source),
            "http://localhost:8096/videos/1/master.m3u8?DeviceId=x"
        );
    }

    #[test]
    fn delivery_body_accepts_utf8_and_refuses_anything_else() {
        assert_eq!(
            text_from_delivery_body("1\n00:00:01,000 --> 00:00:02,000\nh\u{e9}llo\n".into())
                .as_deref()
                .ok(),
            Some("1\n00:00:01,000 --> 00:00:02,000\nh\u{e9}llo\n")
        );
        assert!(matches!(
            text_from_delivery_body(vec![0x68, 0xe9, 0x6c]),
            Err(ApiError::Decode(_))
        ));
    }

    #[tokio::test]
    async fn fetch_delivery_text_returns_the_body_and_refuses_off_server_urls() {
        let (base_url, _rx) = capturing_json_server("1\nhello".to_string()).await;
        let client = JellyfinClient::from_token(&base_url, sample_identity(), "tok");
        let text = client
            .fetch_delivery_text("/Videos/x/s/0.srt", 1024)
            .await
            .expect("sidecar fetch");
        assert_eq!(text, "1\nhello");
        assert!(matches!(
            client
                .fetch_delivery_text("https://other.example.test/s.srt", 1024)
                .await,
            Err(ApiError::Decode(_))
        ));
    }

    #[test]
    fn delivery_url_appends_api_key_to_a_relative_path() {
        assert_eq!(
            authed_delivery_url(
                "http://example.test:8096",
                "tok",
                "/Videos/i/s/Subtitles/3/0/Stream.srt"
            ),
            Some(
                "http://example.test:8096/Videos/i/s/Subtitles/3/0/Stream.srt?ApiKey=tok"
                    .to_string()
            )
        );
    }

    #[test]
    fn delivery_url_extends_an_existing_query_and_keeps_an_existing_key() {
        assert_eq!(
            authed_delivery_url("http://example.test", "tok", "/s.vtt?x=1"),
            Some("http://example.test/s.vtt?x=1&ApiKey=tok".to_string())
        );
        assert_eq!(
            authed_delivery_url("http://example.test", "tok", "/s.vtt?api_key=other"),
            Some("http://example.test/s.vtt?api_key=other".to_string())
        );
    }

    #[test]
    fn delivery_url_refuses_to_send_the_token_off_server() {
        assert_eq!(
            authed_delivery_url(
                "http://example.test",
                "tok",
                "https://cdn.example.org/s.srt"
            ),
            None
        );
        assert_eq!(
            authed_delivery_url("http://example.test", "tok", "http://example.test/s.srt"),
            Some("http://example.test/s.srt?ApiKey=tok".to_string())
        );
    }

    #[test]
    fn from_token_trims_trailing_slash() {
        let client = JellyfinClient::from_token("http://localhost:8096/", sample_identity(), "tok");
        assert_eq!(client.inner.base_url, "http://localhost:8096");
    }

    // --- Model deserialization from recorded fixtures -----------------

    #[test]
    fn deserializes_base_item_dto_fixture() {
        let raw = include_str!("../tests/fixtures/base_item_dto.json");
        let item: BaseItemDto = serde_json::from_str(raw).expect("deserializes");
        assert_eq!(
            item.id,
            Some(uuid::Uuid::parse_str("e2f5a5f1-1a0b-4b3a-9c2e-000000000001").expect("uuid"))
        );
        assert_eq!(item.name.as_deref(), Some("Sample Movie"));
    }

    #[test]
    fn deserializes_base_item_dto_with_unknown_fields() {
        // Unknown fields from server drift must never fail deserialization.
        let raw = include_str!("../tests/fixtures/base_item_dto_with_unknown_fields.json");
        let item: BaseItemDto =
            serde_json::from_str(raw).expect("deserializes despite unknown fields");
        assert_eq!(item.name.as_deref(), Some("Sample Movie"));
    }

    /// An unrecognized `Type` value (server added after this crate's spec
    /// was pinned) must fall back to `BaseItemKind::Unrecognized` via
    /// `#[serde(other)]`, not fail deserialization.
    #[test]
    fn base_item_dto_with_unknown_type_deserializes_to_unrecognized() {
        let raw = include_str!("../tests/fixtures/base_item_dto_unknown_type.json");
        let item: BaseItemDto =
            serde_json::from_str(raw).expect("deserializes despite unknown Type");
        assert_eq!(item.name.as_deref(), Some("Item From The Future"));
        assert_eq!(item.type_, Some(BaseItemKind::Unrecognized));
    }

    /// A full `ItemsResult` page must deserialize completely even when one
    /// item has drifted (unrecognized `BaseItemKind` and nested
    /// `MediaStreamType`), not fail the whole page over one bad item.
    #[test]
    fn items_result_with_one_drifted_item_deserializes_fully() {
        let raw = include_str!("../tests/fixtures/items_result_with_drifted_item.json");
        let result: ItemsResult = serde_json::from_str(raw).expect("deserializes despite drift");
        assert_eq!(result.total_record_count, Some(3));
        assert_eq!(
            result.items.len(),
            3,
            "all three items must be present, drifted one included"
        );

        assert_eq!(result.items[0].type_, Some(BaseItemKind::Movie));
        assert_eq!(result.items[2].type_, Some(BaseItemKind::Episode));

        let drifted = &result.items[1];
        assert_eq!(
            drifted.name.as_deref(),
            Some("Drifted Item From A Newer Server")
        );
        assert_eq!(drifted.type_, Some(BaseItemKind::Unrecognized));
        let stream = drifted
            .media_streams
            .first()
            .expect("drifted item keeps its MediaStreams");
        assert_eq!(stream.type_, Some(models::MediaStreamType::Unrecognized));
    }

    #[test]
    fn deserializes_playback_info_response_fixture() {
        let raw = include_str!("../tests/fixtures/playback_info_response.json");
        let resp: PlaybackInfoResponse = serde_json::from_str(raw).expect("deserializes");
        assert_eq!(resp.media_sources.len(), 1);
        assert_eq!(resp.media_sources[0].media_streams.len(), 2);
        assert!(resp.play_session_id.is_some());
    }

    #[test]
    fn deserializes_authentication_result_fixture() {
        let raw = include_str!("../tests/fixtures/authentication_result.json");
        let result: AuthenticationResult = serde_json::from_str(raw).expect("deserializes");
        assert!(result.access_token.is_some());
        assert!(result.user.is_some());
    }

    // --- mock-server helpers ------------------------------------------

    /// One-shot local HTTP server that captures the raw request and
    /// responds `200 OK` with `body` as JSON. Returns the base URL and a
    /// receiver yielding the captured request text. Local-only (127.0.0.1,
    /// ephemeral port).
    async fn capturing_json_server(
        body: String,
    ) -> (String, tokio::sync::oneshot::Receiver<String>) {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};
        use tokio::net::TcpListener;

        let listener = TcpListener::bind("127.0.0.1:0")
            .await
            .expect("bind local listener");
        let addr = listener.local_addr().expect("local_addr");
        let (tx, rx) = tokio::sync::oneshot::channel();
        tokio::spawn(async move {
            if let Ok((mut stream, _)) = listener.accept().await {
                let mut buf = Vec::new();
                let mut chunk = [0u8; 4096];
                loop {
                    let n = stream.read(&mut chunk).await.unwrap_or(0);
                    if n == 0 {
                        break;
                    }
                    buf.extend_from_slice(&chunk[..n]);
                    if buf.windows(4).any(|w| w == b"\r\n\r\n") {
                        break;
                    }
                }
                let request_text = String::from_utf8_lossy(&buf).to_string();
                let _ = tx.send(request_text);
                let response = format!(
                    "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                    body.len(),
                    body
                );
                let _ = stream.write_all(response.as_bytes()).await;
                let _ = stream.shutdown().await;
            }
        });
        (format!("http://{addr}"), rx)
    }

    /// Like `capturing_json_server` but with an arbitrary `status_line`
    /// (e.g. `"404 Not Found"`), for tests driving a specific non-2xx status
    /// through the real HTTP path.
    async fn capturing_status_server(
        status_line: &str,
        body: String,
    ) -> (String, tokio::sync::oneshot::Receiver<String>) {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};
        use tokio::net::TcpListener;

        let listener = TcpListener::bind("127.0.0.1:0")
            .await
            .expect("bind local listener");
        let addr = listener.local_addr().expect("local_addr");
        let (tx, rx) = tokio::sync::oneshot::channel();
        let status_line = status_line.to_string();
        tokio::spawn(async move {
            if let Ok((mut stream, _)) = listener.accept().await {
                let mut buf = Vec::new();
                let mut chunk = [0u8; 4096];
                loop {
                    let n = stream.read(&mut chunk).await.unwrap_or(0);
                    if n == 0 {
                        break;
                    }
                    buf.extend_from_slice(&chunk[..n]);
                    if buf.windows(4).any(|w| w == b"\r\n\r\n") {
                        break;
                    }
                }
                let request_text = String::from_utf8_lossy(&buf).to_string();
                let _ = tx.send(request_text);
                let response = format!(
                    "HTTP/1.1 {}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                    status_line,
                    body.len(),
                    body
                );
                let _ = stream.write_all(response.as_bytes()).await;
                let _ = stream.shutdown().await;
            }
        });
        (format!("http://{addr}"), rx)
    }

    // --- MediaSegments (skip-intro/credits) -----------------------------

    /// A recorded `/MediaSegments/{itemId}` fixture with an Intro and Outro
    /// segment must deserialize fully, including `Type` resolving to real
    /// `MediaSegmentType` variants (not `Unrecognized`).
    #[test]
    fn deserializes_media_segments_result_fixture() {
        let raw = include_str!("../tests/fixtures/media_segments_result.json");
        let result: MediaSegmentDtoQueryResult = serde_json::from_str(raw).expect("deserializes");
        assert_eq!(result.total_record_count, Some(2));
        assert_eq!(result.items.len(), 2);

        let intro = &result.items[0];
        assert_eq!(intro.type_, Some(models::MediaSegmentType::Intro));
        assert_eq!(intro.start_ticks, Some(0));
        assert_eq!(intro.end_ticks, Some(900_000_000));

        let outro = &result.items[1];
        assert_eq!(outro.type_, Some(models::MediaSegmentType::Outro));
        assert_eq!(outro.start_ticks, Some(68_400_000_000));
        assert_eq!(outro.end_ticks, Some(72_000_000_000));
    }

    #[tokio::test]
    async fn get_media_segments_builds_expected_path_and_query() {
        let raw = include_str!("../tests/fixtures/media_segments_result.json");
        let (base_url, rx) = capturing_json_server(raw.to_string()).await;
        let client = JellyfinClient::from_token(&base_url, sample_identity(), "tok");

        let segments = client
            .get_media_segments("item 1", &["Intro", "Outro"])
            .await
            .expect("get_media_segments against mock server");
        assert_eq!(segments.len(), 2);

        let request = rx.await.expect("mock server captured a request");
        let request_line = request.lines().next().unwrap_or_default();
        assert!(
            request_line.starts_with("GET /MediaSegments/item%201?"),
            "request line missing percent-encoded item id path: {request_line}"
        );
        assert!(
            request_line.contains("includeSegmentTypes=Intro%2COutro"),
            "request line missing includeSegmentTypes: {request_line}"
        );
    }

    #[tokio::test]
    async fn get_media_segments_omits_query_when_include_types_empty() {
        let raw = include_str!("../tests/fixtures/media_segments_result.json");
        let (base_url, rx) = capturing_json_server(raw.to_string()).await;
        let client = JellyfinClient::from_token(&base_url, sample_identity(), "tok");

        client
            .get_media_segments("item-1", &[])
            .await
            .expect("get_media_segments against mock server");

        let request = rx.await.expect("mock server captured a request");
        let request_line = request.lines().next().unwrap_or_default();
        assert!(
            request_line.starts_with("GET /MediaSegments/item-1"),
            "unexpected request line: {request_line}"
        );
        assert!(
            !request_line.contains("includeSegmentTypes"),
            "request line should omit includeSegmentTypes when include_types is empty: {request_line}"
        );
    }

    /// A server without MediaSegments 404s this endpoint entirely;
    /// `get_media_segments` must treat that as `Ok(vec![])`, not an error,
    /// so skip-intro UI treats both cases the same.
    #[tokio::test]
    async fn get_media_segments_returns_empty_on_404() {
        let (base_url, _rx) =
            capturing_status_server("404 Not Found", r#"{"title":"Not Found"}"#.to_string()).await;
        let client = JellyfinClient::from_token(&base_url, sample_identity(), "tok");

        let segments = client
            .get_media_segments("item-1", &[])
            .await
            .expect("404 must be translated to Ok(vec![]), not an error");
        assert!(segments.is_empty());
    }

    /// A non-404 failure (e.g. 500, or an auth failure) must still surface
    /// as a real error -- only 404 gets the "feature not supported"
    /// treatment.
    #[tokio::test]
    async fn get_media_segments_surfaces_non_404_errors() {
        let (base_url, _rx) =
            capturing_status_server("500 Internal Server Error", "boom".to_string()).await;
        let client = JellyfinClient::from_token(&base_url, sample_identity(), "tok");

        let err = client
            .get_media_segments("item-1", &[])
            .await
            .expect_err("500 must surface as an error, not Ok(vec![])");
        assert!(
            matches!(err, ApiError::Status { code: 500, .. }),
            "unexpected error variant: {err:?}"
        );
    }

    // --- ItemQuery.sort_order -> `sortOrder` query param ----------------

    #[tokio::test]
    async fn get_items_sends_sort_order_when_set() {
        let (base_url, rx) = capturing_json_server("{}".to_string()).await;
        let client = JellyfinClient::from_token(&base_url, sample_identity(), "tok");
        let query = ItemQuery {
            sort_by: Some("SortName".to_string()),
            sort_order: Some("Descending".to_string()),
            ..ItemQuery::new()
        };
        client
            .get_items(&query)
            .await
            .expect("get_items against mock server");
        let request = rx.await.expect("mock server captured a request");
        let request_line = request.lines().next().unwrap_or_default();
        assert!(
            request_line.contains("sortBy=SortName"),
            "request line missing sortBy: {request_line}"
        );
        assert!(
            request_line.contains("sortOrder=Descending"),
            "request line missing sortOrder: {request_line}"
        );
    }

    #[tokio::test]
    async fn get_items_sends_person_ids_only_when_set() {
        let (base_url, rx) = capturing_json_server("{}".to_string()).await;
        let client = JellyfinClient::from_token(&base_url, sample_identity(), "tok");
        let query = ItemQuery {
            person_ids: vec!["person-1".to_string()],
            ..ItemQuery::new()
        };
        client
            .get_items(&query)
            .await
            .expect("get_items against mock server");
        let request = rx.await.expect("mock server captured a request");
        let request_line = request.lines().next().unwrap_or_default();
        assert!(
            request_line.contains("personIds=person-1"),
            "request line missing personIds: {request_line}"
        );

        let (base_url, rx) = capturing_json_server("{}".to_string()).await;
        let client = JellyfinClient::from_token(&base_url, sample_identity(), "tok");
        client
            .get_items(&ItemQuery::new())
            .await
            .expect("get_items against mock server");
        let request = rx.await.expect("mock server captured a request");
        let request_line = request.lines().next().unwrap_or_default();
        assert!(!request_line.contains("personIds"), "{request_line}");
    }

    #[tokio::test]
    async fn get_item_by_id_is_user_scoped_when_the_user_id_is_known() {
        let (base_url, rx) = capturing_json_server("{}".to_string()).await;
        let client =
            JellyfinClient::from_token(&base_url, sample_identity(), "tok").with_user_id("user-1");
        client
            .get_item_by_id("person-1", &["Overview".to_string()])
            .await
            .expect("get_item_by_id against mock server");
        let request = rx.await.expect("mock server captured a request");
        let request_line = request.lines().next().unwrap_or_default();
        assert!(
            request_line.contains("/Users/user-1/Items/person-1"),
            "{request_line}"
        );
        assert!(request_line.contains("fields=Overview"), "{request_line}");
    }

    #[tokio::test]
    async fn get_item_by_id_without_a_user_id_uses_the_plain_route() {
        let (base_url, rx) = capturing_json_server("{}".to_string()).await;
        let client = JellyfinClient::from_token(&base_url, sample_identity(), "tok");
        client
            .get_item_by_id("person-1", &[])
            .await
            .expect("get_item_by_id against mock server");
        let request = rx.await.expect("mock server captured a request");
        let request_line = request.lines().next().unwrap_or_default();
        assert!(
            request_line.starts_with("GET /Items/person-1"),
            "{request_line}"
        );
    }

    #[tokio::test]
    async fn get_items_omits_sort_order_when_unset() {
        let (base_url, rx) = capturing_json_server("{}".to_string()).await;
        let client = JellyfinClient::from_token(&base_url, sample_identity(), "tok");
        let query = ItemQuery {
            sort_by: Some("SortName".to_string()),
            ..ItemQuery::new()
        };
        client
            .get_items(&query)
            .await
            .expect("get_items against mock server");
        let request = rx.await.expect("mock server captured a request");
        let request_line = request.lines().next().unwrap_or_default();
        assert!(
            !request_line.contains("sortOrder"),
            "request line should omit sortOrder when None: {request_line}"
        );
    }

    // --- get_resume_items(fields) -> `fields` query param

    #[tokio::test]
    async fn get_resume_items_sends_fields_when_set() {
        let (base_url, rx) = capturing_json_server("{}".to_string()).await;
        let client = JellyfinClient::from_token(&base_url, sample_identity(), "tok");
        client
            .get_resume_items(&["Overview".to_string(), "SeriesName".to_string()])
            .await
            .expect("get_resume_items against mock server");
        let request = rx.await.expect("mock server captured a request");
        let request_line = request.lines().next().unwrap_or_default();
        assert!(
            request_line.starts_with("GET /UserItems/Resume?"),
            "unexpected request line: {request_line}"
        );
        assert!(
            request_line.contains("fields=Overview%2CSeriesName"),
            "request line missing fields: {request_line}"
        );
    }

    #[tokio::test]
    async fn get_resume_items_omits_fields_when_empty() {
        let (base_url, rx) = capturing_json_server("{}".to_string()).await;
        let client = JellyfinClient::from_token(&base_url, sample_identity(), "tok");
        client
            .get_resume_items(&[])
            .await
            .expect("get_resume_items against mock server");
        let request = rx.await.expect("mock server captured a request");
        let request_line = request.lines().next().unwrap_or_default();
        assert!(
            !request_line.contains("fields"),
            "request line should omit fields when empty: {request_line}"
        );
    }

    #[tokio::test]
    async fn get_items_sends_is_favorite_when_set() {
        let (base_url, rx) = capturing_json_server("{}".to_string()).await;
        let client = JellyfinClient::from_token(&base_url, sample_identity(), "tok");
        let query = ItemQuery {
            is_favorite: Some(true),
            ..ItemQuery::new()
        };
        client
            .get_items(&query)
            .await
            .expect("get_items against mock server");
        let request = rx.await.expect("mock server captured a request");
        let request_line = request.lines().next().unwrap_or_default();
        assert!(
            request_line.contains("isFavorite=true"),
            "request line missing isFavorite: {request_line}"
        );
    }

    // --- ItemQuery.is_missing -> `isMissing` query param

    #[tokio::test]
    async fn get_items_sends_is_missing_when_set() {
        let (base_url, rx) = capturing_json_server("{}".to_string()).await;
        let client = JellyfinClient::from_token(&base_url, sample_identity(), "tok");
        let query = ItemQuery {
            is_missing: Some(true),
            ..ItemQuery::new()
        };
        client
            .get_items(&query)
            .await
            .expect("get_items against mock server");
        let request = rx.await.expect("mock server captured a request");
        let request_line = request.lines().next().unwrap_or_default();
        assert!(
            request_line.contains("isMissing=true"),
            "request line missing isMissing: {request_line}"
        );
    }

    #[tokio::test]
    async fn get_items_omits_is_missing_when_unset() {
        let (base_url, rx) = capturing_json_server("{}".to_string()).await;
        let client = JellyfinClient::from_token(&base_url, sample_identity(), "tok");
        let query = ItemQuery::new();
        client
            .get_items(&query)
            .await
            .expect("get_items against mock server");
        let request = rx.await.expect("mock server captured a request");
        let request_line = request.lines().next().unwrap_or_default();
        assert!(
            !request_line.contains("isMissing"),
            "request line should omit isMissing when None: {request_line}"
        );
    }

    // --- Reconciliation ID sweep: ItemQuery.enable_images / enable_user_data

    /// The sweep's enumeration pages are only cheap if these actually reach
    /// the wire -- a silently-dropped `enableImages=false` would put every
    /// item's image tags and blurhashes back into a 1,000-item page.
    #[tokio::test]
    async fn get_items_sends_enable_images_and_user_data_when_set() {
        let (base_url, rx) = capturing_json_server("{}".to_string()).await;
        let client = JellyfinClient::from_token(&base_url, sample_identity(), "tok");
        let query = ItemQuery {
            enable_images: Some(false),
            enable_user_data: Some(false),
            ..ItemQuery::new()
        };
        client
            .get_items(&query)
            .await
            .expect("get_items against mock server");
        let request = rx.await.expect("mock server captured a request");
        let request_line = request.lines().next().unwrap_or_default();
        assert!(
            request_line.contains("enableImages=false"),
            "request line missing enableImages: {request_line}"
        );
        assert!(
            request_line.contains("enableUserData=false"),
            "request line missing enableUserData: {request_line}"
        );
    }

    /// Every other query in the app leaves these `None`, and must keep
    /// sending exactly the bytes it always did.
    #[tokio::test]
    async fn get_items_omits_enable_images_and_user_data_when_unset() {
        let (base_url, rx) = capturing_json_server("{}".to_string()).await;
        let client = JellyfinClient::from_token(&base_url, sample_identity(), "tok");
        client
            .get_items(&ItemQuery::new())
            .await
            .expect("get_items against mock server");
        let request = rx.await.expect("mock server captured a request");
        let request_line = request.lines().next().unwrap_or_default();
        assert!(
            !request_line.contains("enableImages"),
            "request line should omit enableImages when None: {request_line}"
        );
        assert!(
            !request_line.contains("enableUserData"),
            "request line should omit enableUserData when None: {request_line}"
        );
    }

    // --- Incremental delta sync: ItemQuery.min_date_last_saved ------------

    /// The param must go out under both names (see
    /// `ItemQuery::min_date_last_saved` for the 10.10.x HTTP 500 this works
    /// around) -- catches a future refactor dropping the companion param
    /// before it 500s in the field.
    #[tokio::test]
    async fn get_items_sends_both_min_date_last_saved_params_when_set() {
        let (base_url, rx) = capturing_json_server("{}".to_string()).await;
        let client = JellyfinClient::from_token(&base_url, sample_identity(), "tok");
        let query = ItemQuery {
            min_date_last_saved: Some("2026-08-17T07:45:12Z".to_string()),
            ..ItemQuery::new()
        };
        client
            .get_items(&query)
            .await
            .expect("get_items against mock server");
        let request = rx.await.expect("mock server captured a request");
        let request_line = request.lines().next().unwrap_or_default();
        assert!(
            request_line.contains("minDateLastSaved=2026-08-17T07%3A45%3A12Z"),
            "request line missing minDateLastSaved: {request_line}"
        );
        assert!(
            request_line.contains("minDateLastSavedForUser=2026-08-17T07%3A45%3A12Z"),
            "request line missing the minDateLastSavedForUser companion \
             (10.10.x returns HTTP 500 without it): {request_line}"
        );
    }

    #[tokio::test]
    async fn get_items_omits_min_date_last_saved_when_unset() {
        let (base_url, rx) = capturing_json_server("{}".to_string()).await;
        let client = JellyfinClient::from_token(&base_url, sample_identity(), "tok");
        let query = ItemQuery::new();
        client
            .get_items(&query)
            .await
            .expect("get_items against mock server");
        let request = rx.await.expect("mock server captured a request");
        let request_line = request.lines().next().unwrap_or_default();
        assert!(
            !request_line.contains("minDateLastSaved"),
            "request line should omit minDateLastSaved when None: {request_line}"
        );
    }

    // --- get_similar -------------------------------------------------------

    #[tokio::test]
    async fn get_similar_deserializes_items_from_the_fixture() {
        let raw = include_str!("../tests/fixtures/similar_items_result.json");
        let (base_url, rx) = capturing_json_server(raw.to_string()).await;
        let client = JellyfinClient::from_token(&base_url, sample_identity(), "tok");

        let items = client
            .get_similar("item-1", 12)
            .await
            .expect("get_similar against mock server");
        assert_eq!(items.len(), 2);
        assert_eq!(items[0].name.as_deref(), Some("Similar Movie One"));
        assert_eq!(items[1].name.as_deref(), Some("Similar Movie Two"));

        let _ = rx.await;
    }

    #[tokio::test]
    async fn get_episodes_uses_the_dedicated_series_endpoint_and_bounded_query() {
        let (base_url, rx) = capturing_json_server("{}".to_string()).await;
        let client =
            JellyfinClient::from_token(&base_url, sample_identity(), "tok").with_user_id("user-9");

        client
            .get_episodes("series 1", &["Overview".to_string()], 10_000)
            .await
            .expect("get_episodes against mock server");

        let request = rx.await.expect("mock server captured a request");
        let request_line = request.lines().next().unwrap_or_default();
        assert!(request_line.starts_with("GET /Shows/series%201/Episodes?"));
        assert!(request_line.contains("isMissing=false"));
        assert!(request_line.contains("sortBy=AiredEpisodeOrder"));
        assert!(request_line.contains("limit=10000"));
        assert!(request_line.contains("fields=Overview"));
        assert!(request_line.contains("userId=user-9"));
    }

    #[tokio::test]
    async fn get_adjacent_episodes_sends_the_current_item_without_a_series_sized_limit() {
        let (base_url, rx) = capturing_json_server("{}".to_string()).await;
        let client =
            JellyfinClient::from_token(&base_url, sample_identity(), "tok").with_user_id("user-9");

        client
            .get_adjacent_episodes("series-1", "episode-20", &["Overview".to_string()])
            .await
            .expect("get_adjacent_episodes against mock server");

        let request = rx.await.expect("mock server captured a request");
        let request_line = request.lines().next().unwrap_or_default();
        assert!(request_line.starts_with("GET /Shows/series-1/Episodes?"));
        assert!(request_line.contains("adjacentTo=episode-20"));
        assert!(request_line.contains("isMissing=false"));
        assert!(request_line.contains("fields=Overview"));
        assert!(request_line.contains("userId=user-9"));
        assert!(!request_line.contains("limit="));
    }

    #[tokio::test]
    async fn get_similar_builds_expected_path_and_limit_query() {
        let (base_url, rx) = capturing_json_server("{}".to_string()).await;
        let client = JellyfinClient::from_token(&base_url, sample_identity(), "tok");

        client
            .get_similar("item 1", 8)
            .await
            .expect("get_similar against mock server");

        let request = rx.await.expect("mock server captured a request");
        let request_line = request.lines().next().unwrap_or_default();
        assert!(
            request_line.starts_with("GET /Items/item%201/Similar?"),
            "request line missing percent-encoded item id path: {request_line}"
        );
        assert!(
            request_line.contains("limit=8"),
            "request line missing limit: {request_line}"
        );
        assert!(
            !request_line.contains("userId"),
            "request line should omit userId when the client doesn't know one: {request_line}"
        );
    }

    #[tokio::test]
    async fn get_similar_sends_user_id_when_known() {
        let (base_url, rx) = capturing_json_server("{}".to_string()).await;
        let client =
            JellyfinClient::from_token(&base_url, sample_identity(), "tok").with_user_id("user-9");

        client
            .get_similar("item-1", 8)
            .await
            .expect("get_similar against mock server");

        let request = rx.await.expect("mock server captured a request");
        let request_line = request.lines().next().unwrap_or_default();
        assert!(
            request_line.contains("userId=user-9"),
            "request line missing userId: {request_line}"
        );
    }

    #[tokio::test]
    async fn get_similar_surfaces_non_2xx_errors() {
        let (base_url, _rx) =
            capturing_status_server("500 Internal Server Error", "boom".to_string()).await;
        let client = JellyfinClient::from_token(&base_url, sample_identity(), "tok");

        let err = client
            .get_similar("item-1", 8)
            .await
            .expect_err("500 must surface as an error");
        assert!(
            matches!(err, ApiError::Status { code: 500, .. }),
            "unexpected error variant: {err:?}"
        );
    }

    #[test]
    fn item_query_new_matches_default() {
        let a = ItemQuery::new();
        let b = ItemQuery::default();
        assert_eq!(a.parent_id, b.parent_id);
        assert_eq!(a.recursive, b.recursive);
        assert_eq!(a.sort_order, None);
        assert_eq!(a.start_index, 0);
        assert_eq!(a.limit, 0);
    }

    // --- JellyfinClient::user_id -----------------------------------------

    #[tokio::test]
    async fn authenticate_by_name_captures_user_id_from_fixture() {
        // Drives the recorded fixture through the real HTTP path rather than only unit-testing
        // extraction.
        let raw = include_str!("../tests/fixtures/authentication_result.json");
        let (base_url, _rx) = capturing_json_server(raw.to_string()).await;
        let (client, result) = JellyfinClient::authenticate_by_name(
            &base_url,
            sample_identity(),
            "jellybeam-admin",
            "jellybeam-test",
        )
        .await
        .expect("authenticate_by_name against mock server");

        let expected_user_id = result
            .user
            .as_ref()
            .and_then(|u| u.id)
            .map(|id| id.to_string())
            .expect("fixture has a User.Id");
        assert_eq!(client.user_id(), Some(expected_user_id.as_str()));
        assert_eq!(
            client.user_id(),
            Some("e2f5a5f1-1a0b-4b3a-9c2e-0000000000aa")
        );
    }

    #[test]
    fn from_token_has_no_user_id_until_with_user_id_is_called() {
        let client = JellyfinClient::from_token("http://localhost:8096", sample_identity(), "tok");
        assert_eq!(client.user_id(), None);

        let client = client.with_user_id("user-123");
        assert_eq!(client.user_id(), Some("user-123"));
    }

    // --- Quick Connect -----------------------------------------------------

    #[tokio::test]
    async fn quick_connect_initiate_returns_code_and_secret_from_fixture() {
        let raw = include_str!("../tests/fixtures/quick_connect_result.json");
        let (base_url, rx) = capturing_json_server(raw.to_string()).await;
        let result = JellyfinClient::quick_connect_initiate(&base_url, &sample_identity())
            .await
            .expect("quick_connect_initiate against mock server");
        assert_eq!(result.code.as_deref(), Some("123456"));
        assert_eq!(result.secret.as_deref(), Some("the-quick-connect-secret"));
        assert_eq!(result.authenticated, Some(false));

        let request = rx.await.expect("captured request");
        assert!(
            request.starts_with("POST /QuickConnect/Initiate"),
            "{request}"
        );
        assert!(
            request
                .to_lowercase()
                .contains("authorization: mediabrowser"),
            "{request}"
        );
        assert!(
            !request.contains("Token="),
            "pre-auth request must not carry a Token clause: {request}"
        );
    }

    #[tokio::test]
    async fn quick_connect_poll_sends_secret_as_query_param() {
        let mut authenticated_raw: serde_json::Value =
            serde_json::from_str(include_str!("../tests/fixtures/quick_connect_result.json"))
                .expect("valid json");
        authenticated_raw["Authenticated"] = serde_json::Value::Bool(true);
        let (base_url, rx) = capturing_json_server(authenticated_raw.to_string()).await;

        let result = JellyfinClient::quick_connect_poll(
            &base_url,
            &sample_identity(),
            "the-quick-connect-secret",
        )
        .await
        .expect("quick_connect_poll against mock server");
        assert_eq!(result.authenticated, Some(true));

        let request = rx.await.expect("captured request");
        assert!(
            request.starts_with("GET /QuickConnect/Connect"),
            "{request}"
        );
        assert!(
            request.contains("secret=the-quick-connect-secret"),
            "{request}"
        );
    }

    #[tokio::test]
    async fn quick_connect_enabled_deserializes_bare_bool() {
        let (base_url, _rx) = capturing_json_server("true".to_string()).await;
        let enabled = JellyfinClient::quick_connect_enabled(&base_url, &sample_identity())
            .await
            .expect("quick_connect_enabled against mock server");
        assert!(enabled);
    }

    #[tokio::test]
    async fn quick_connect_poll_surfaces_404_as_unknown_secret_error() {
        // Unlike get_media_segments's 404-as-empty, an expired secret is a
        // real error the poll loop should stop on.
        let (base_url, _rx) =
            capturing_status_server("404 Not Found", r#"{"title":"unknown secret"}"#.to_string())
                .await;
        let result =
            JellyfinClient::quick_connect_poll(&base_url, &sample_identity(), "expired-secret")
                .await;
        assert!(
            matches!(result, Err(ApiError::Status { code: 404, .. })),
            "{result:?}"
        );
    }

    #[tokio::test]
    async fn authenticate_with_quick_connect_posts_secret_and_captures_token() {
        let raw = include_str!("../tests/fixtures/authentication_result.json");
        let (base_url, rx) = capturing_json_server(raw.to_string()).await;
        let (client, result) = JellyfinClient::authenticate_with_quick_connect(
            &base_url,
            sample_identity(),
            "the-quick-connect-secret",
        )
        .await
        .expect("authenticate_with_quick_connect against mock server");

        assert!(result.access_token.is_some());
        assert_eq!(
            client.user_id(),
            Some("e2f5a5f1-1a0b-4b3a-9c2e-0000000000aa")
        );

        let request = rx.await.expect("captured request");
        assert!(
            request.starts_with("POST /Users/AuthenticateWithQuickConnect"),
            "{request}"
        );
    }

    // --- docs/13 Server compatibility -------------------------

    #[test]
    fn server_version_parses_tolerant_formats() {
        let cases = [
            (
                "12.0.0",
                ServerVersion {
                    major: 12,
                    minor: 0,
                    patch: 0,
                },
            ),
            (
                "10.11.10",
                ServerVersion {
                    major: 10,
                    minor: 11,
                    patch: 10,
                },
            ),
            (
                "12.0",
                ServerVersion {
                    major: 12,
                    minor: 0,
                    patch: 0,
                },
            ),
            (
                "10.11.0-rc1",
                ServerVersion {
                    major: 10,
                    minor: 11,
                    patch: 0,
                },
            ),
        ];
        for (input, expected) in cases {
            assert_eq!(
                input.parse::<ServerVersion>().expect(input),
                expected,
                "parsing {input:?}"
            );
        }
    }

    #[test]
    fn server_version_rejects_strings_missing_major_or_minor() {
        for bad in ["12", "", "abc", "abc.5", ".", "v"] {
            assert!(
                bad.parse::<ServerVersion>().is_err(),
                "{bad:?} should not parse (needs at least major.minor)"
            );
        }
    }

    #[test]
    fn server_version_display_is_major_minor_patch() {
        let v = ServerVersion {
            major: 12,
            minor: 0,
            patch: 0,
        };
        assert_eq!(v.to_string(), "12.0.0");
    }

    #[test]
    fn server_version_at_least_is_lexicographic_on_major_then_minor() {
        let v12_0_0 = ServerVersion {
            major: 12,
            minor: 0,
            patch: 0,
        };
        assert!(v12_0_0.at_least(12, 0), "12.0.0 >= 12.0");

        let v10_11_7 = ServerVersion {
            major: 10,
            minor: 11,
            patch: 7,
        };
        assert!(!v10_11_7.at_least(12, 0), "10.11.7 is not >= 12.0");

        let v12_1_0 = ServerVersion {
            major: 12,
            minor: 1,
            patch: 0,
        };
        assert!(v12_1_0.at_least(12, 0), "12.1.0 >= 12.0");
    }

    #[tokio::test]
    async fn public_system_info_deserializes_and_ignores_unknown_fields() {
        let body = serde_json::json!({
            "Version": "10.11.10",
            "ServerName": "test-server",
            "Id": "server-id-1",
            "StartupWizardCompleted": true,
            "SomeFutureField": {"nested": true},
        })
        .to_string();
        let (base_url, rx) = capturing_json_server(body).await;

        let info = JellyfinClient::public_system_info(&base_url, &sample_identity())
            .await
            .expect("public_system_info against mock server");
        assert_eq!(info.version.as_deref(), Some("10.11.10"));
        assert_eq!(
            info.parsed_version(),
            Some(ServerVersion {
                major: 10,
                minor: 11,
                patch: 10
            })
        );
        assert_eq!(info.server_name.as_deref(), Some("test-server"));
        assert_eq!(info.id.as_deref(), Some("server-id-1"));
        assert_eq!(info.startup_wizard_completed, Some(true));

        let request = rx.await.expect("captured request");
        assert!(request.starts_with("GET /System/Info/Public"), "{request}");
        assert!(
            !request.contains("Token="),
            "pre-auth request must not carry a Token clause: {request}"
        );
    }

    #[tokio::test]
    async fn refresh_public_system_info_uses_the_client_own_authenticated_header() {
        let body = serde_json::json!({"Version": "12.0.0"}).to_string();
        let (base_url, rx) = capturing_json_server(body).await;
        let client = JellyfinClient::from_token(&base_url, sample_identity(), "tok");

        let info = client
            .refresh_public_system_info()
            .await
            .expect("refresh_public_system_info against mock server");
        assert_eq!(info.version.as_deref(), Some("12.0.0"));

        let request = rx.await.expect("captured request");
        assert!(request.starts_with("GET /System/Info/Public"), "{request}");
        assert!(request.contains(r#"Token="tok""#), "{request}");
    }

    #[tokio::test]
    async fn system_info_deserializes_and_ignores_unknown_fields() {
        let body = serde_json::json!({
            "Version": "10.11.10",
            "ServerName": "test-server",
            "ProductName": "Jellyfin Server",
            "OperatingSystemDisplayName": "Linux",
            "SystemArchitecture": "X64",
            "HasPendingRestart": false,
            "HasUpdateAvailable": true,
            "SomeFutureField": {"nested": true},
        })
        .to_string();
        let (base_url, rx) = capturing_json_server(body).await;
        let client = JellyfinClient::from_token(&base_url, sample_identity(), "tok");

        let info = client
            .system_info()
            .await
            .expect("system_info against mock server");
        assert_eq!(info.version.as_deref(), Some("10.11.10"));
        assert_eq!(info.server_name.as_deref(), Some("test-server"));
        assert_eq!(info.product_name.as_deref(), Some("Jellyfin Server"));
        assert_eq!(info.operating_system_display_name.as_deref(), Some("Linux"));
        assert_eq!(info.system_architecture.as_deref(), Some("X64"));
        assert_eq!(info.has_pending_restart, Some(false));
        assert_eq!(info.has_update_available, Some(true));

        let request = rx.await.expect("captured request");
        assert!(request.starts_with("GET /System/Info "), "{request}");
        assert!(request.contains(r#"Token="tok""#), "{request}");
    }

    #[tokio::test]
    async fn item_counts_sends_user_id_when_known_and_decodes_fields() {
        let body = serde_json::json!({
            "MovieCount": 100,
            "SeriesCount": 20,
            "EpisodeCount": 300,
            "BoxSetCount": 1,
            "AlbumCount": 5,
            "SongCount": 50,
            "ArtistCount": 10,
            "MusicVideoCount": 2,
            "BookCount": 3,
            "TrailerCount": 4,
            "ProgramCount": 6,
            "ItemCount": 501,
            "SomeFutureField": {"nested": true},
        })
        .to_string();
        let (base_url, rx) = capturing_json_server(body).await;
        let client =
            JellyfinClient::from_token(&base_url, sample_identity(), "tok").with_user_id("user-9");

        let counts = client
            .item_counts()
            .await
            .expect("item_counts against mock server");
        assert_eq!(counts.movie_count, Some(100));
        assert_eq!(counts.series_count, Some(20));
        assert_eq!(counts.episode_count, Some(300));
        assert_eq!(counts.box_set_count, Some(1));
        assert_eq!(counts.album_count, Some(5));
        assert_eq!(counts.song_count, Some(50));
        assert_eq!(counts.artist_count, Some(10));
        assert_eq!(counts.music_video_count, Some(2));
        assert_eq!(counts.book_count, Some(3));
        assert_eq!(counts.trailer_count, Some(4));
        assert_eq!(counts.program_count, Some(6));

        let request = rx.await.expect("captured request");
        assert!(request.starts_with("GET /Items/Counts"), "{request}");
        assert!(
            request.contains("userId=user-9"),
            "request line missing userId: {request}"
        );
    }

    #[tokio::test]
    async fn item_counts_omits_user_id_when_unknown() {
        let (base_url, rx) = capturing_json_server("{}".to_string()).await;
        let client = JellyfinClient::from_token(&base_url, sample_identity(), "tok");

        client
            .item_counts()
            .await
            .expect("item_counts against mock server");

        let request = rx.await.expect("captured request");
        assert!(
            !request.contains("userId"),
            "request line should omit userId when the client doesn't know one: {request}"
        );
    }

    fn url(s: &str) -> reqwest::Url {
        reqwest::Url::parse(s).expect("test url")
    }

    /// Pins: reqwest's own policy strips `Authorization` only on a cross-host redirect, so a
    /// same-host `https`->`http` bounce is caught as a downgrade too.
    #[test]
    fn same_host_https_to_http_redirect_is_a_scheme_downgrade() {
        assert!(is_scheme_downgrade(
            &[url("https://media.example.com/Items")],
            &url("http://media.example.com/Items")
        ));
    }

    /// Only the scheme is judged -- a cross-host downgrade is refused for the same reason.
    #[test]
    fn cross_host_https_to_http_redirect_is_a_scheme_downgrade() {
        assert!(is_scheme_downgrade(
            &[url("https://media.example.com/Items")],
            &url("http://192.0.2.50:8096/Items")
        ));
    }

    /// Legitimate cases still pass: upgrades, same-scheme hops, and a plain-`http://` LAN server
    /// redirecting within `http://`.
    #[test]
    fn non_downgrading_redirects_are_allowed() {
        assert!(!is_scheme_downgrade(
            &[url("http://media.example.com/Items")],
            &url("https://media.example.com/Items")
        ));
        assert!(!is_scheme_downgrade(
            &[url("https://media.example.com/Items")],
            &url("https://media.example.com/Items2")
        ));
        assert!(!is_scheme_downgrade(
            &[url("http://192.0.2.50:8096/Items")],
            &url("http://192.0.2.50:8096/Items2")
        ));
        // First hop: nothing has been visited yet.
        assert!(!is_scheme_downgrade(&[], &url("http://example.com/")));
    }

    /// One-shot local HTTP server answering with a redirect `Location` header, for asserting the
    /// real client's redirect behavior end to end.
    async fn redirecting_server(location: String) -> String {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};
        use tokio::net::TcpListener;

        let listener = TcpListener::bind("127.0.0.1:0")
            .await
            .expect("bind local listener");
        let addr = listener.local_addr().expect("local_addr");
        tokio::spawn(async move {
            if let Ok((mut stream, _)) = listener.accept().await {
                let mut discard = [0u8; 1024];
                let _ = stream.read(&mut discard).await;
                let response = format!(
                    "HTTP/1.1 302 Found\r\nLocation: {location}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                );
                let _ = stream.write_all(response.as_bytes()).await;
                let _ = stream.shutdown().await;
            }
        });
        format!("http://{addr}")
    }

    /// Pins: ordinary same-scheme redirects still work; the downgrade check must not become
    /// "redirects are off".
    #[tokio::test]
    async fn an_ordinary_same_scheme_redirect_is_still_followed() {
        let raw = include_str!("../tests/fixtures/similar_items_result.json");
        let (target, _rx) = capturing_json_server(raw.to_string()).await;
        let base_url = redirecting_server(format!("{target}/UserViews")).await;
        let client = JellyfinClient::from_token(&base_url, sample_identity(), "tok");
        let views = client
            .get_user_views()
            .await
            .expect("the redirect to the same scheme is followed");
        assert!(!views.is_empty());
    }

    /// A body under the cap is read and decoded exactly as before.
    #[tokio::test]
    async fn read_capped_body_returns_a_body_that_fits() {
        let (base_url, _rx) = capturing_json_server("[1,2,3]".to_string()).await;
        let resp = reqwest::get(&base_url).await.expect("request");
        let body = read_capped_body(resp, 1024, "/test")
            .await
            .expect("under the cap");
        assert_eq!(body, b"[1,2,3]");
    }

    /// Pins: a hostile/broken server's arbitrarily large body must not force an unbounded
    /// allocation -- the read stops at the ceiling with a clean `ApiError::Decode`.
    #[tokio::test]
    async fn read_capped_body_refuses_a_body_over_the_cap() {
        let (base_url, _rx) = capturing_json_server("x".repeat(64 * 1024)).await;
        let resp = reqwest::get(&base_url).await.expect("request");
        let err = read_capped_body(resp, 1024, "/test")
            .await
            .expect_err("over the cap");
        assert!(
            matches!(&err, ApiError::Decode(msg) if msg.contains("exceeded")),
            "{err:?}"
        );
    }

    /// Pins the inclusive edge: the cap check is `> cap`, so a body of exactly `cap` bytes is
    /// accepted.
    #[tokio::test]
    async fn read_capped_body_accepts_a_body_exactly_at_the_cap() {
        const CAP: usize = 4096;
        let (base_url, _rx) = capturing_json_server("x".repeat(CAP)).await;
        let resp = reqwest::get(&base_url).await.expect("request");
        let body = read_capped_body(resp, CAP, "/test")
            .await
            .expect("a body exactly at the cap is accepted");
        assert_eq!(body.len(), CAP);
    }

    /// Complements the exactly-at-cap case: one byte over is refused.
    #[tokio::test]
    async fn read_capped_body_refuses_a_body_one_byte_over_the_cap() {
        const CAP: usize = 4096;
        let (base_url, _rx) = capturing_json_server("x".repeat(CAP + 1)).await;
        let resp = reqwest::get(&base_url).await.expect("request");
        let err = read_capped_body(resp, CAP, "/test")
            .await
            .expect_err("one byte over the cap is refused");
        assert!(
            matches!(&err, ApiError::Decode(msg) if msg.contains("exceeded")),
            "{err:?}"
        );
    }

    // --- docs/19-detail-action-menu.md §2.1: detail action menu client methods

    fn sample_user_item_data_json() -> serde_json::Value {
        serde_json::json!({
            "Key": "item-1",
            "Played": true,
            "IsFavorite": false,
            "PlaybackPositionTicks": 0
        })
    }

    #[tokio::test]
    async fn mark_played_posts_to_user_played_items_and_decodes_response() {
        let (base_url, rx) = capturing_json_server(sample_user_item_data_json().to_string()).await;
        let client = JellyfinClient::from_token(&base_url, sample_identity(), "tok");

        let data = client
            .mark_played("item 1")
            .await
            .expect("mark_played against mock server");
        assert_eq!(data.played, Some(true));

        let request = rx.await.expect("mock server captured a request");
        let request_line = request.lines().next().unwrap_or_default();
        assert!(
            request_line.starts_with("POST /UserPlayedItems/item%201"),
            "unexpected request line: {request_line}"
        );
        assert!(
            !request_line.contains("userId"),
            "request line should omit userId when the client doesn't know one: {request_line}"
        );
    }

    #[tokio::test]
    async fn mark_played_sends_user_id_when_known() {
        let (base_url, rx) = capturing_json_server(sample_user_item_data_json().to_string()).await;
        let client =
            JellyfinClient::from_token(&base_url, sample_identity(), "tok").with_user_id("user-9");

        client
            .mark_played("item-1")
            .await
            .expect("mark_played against mock server");

        let request = rx.await.expect("mock server captured a request");
        let request_line = request.lines().next().unwrap_or_default();
        assert!(
            request_line.contains("userId=user-9"),
            "request line missing userId: {request_line}"
        );
    }

    #[tokio::test]
    async fn mark_unplayed_sends_delete_to_user_played_items() {
        let (base_url, rx) = capturing_json_server(sample_user_item_data_json().to_string()).await;
        let client = JellyfinClient::from_token(&base_url, sample_identity(), "tok");

        client
            .mark_unplayed("item-1")
            .await
            .expect("mark_unplayed against mock server");

        let request = rx.await.expect("mock server captured a request");
        let request_line = request.lines().next().unwrap_or_default();
        assert!(
            request_line.starts_with("DELETE /UserPlayedItems/item-1"),
            "unexpected request line: {request_line}"
        );
    }

    #[tokio::test]
    async fn set_favorite_true_posts_to_user_favorite_items() {
        let (base_url, rx) = capturing_json_server(sample_user_item_data_json().to_string()).await;
        let client = JellyfinClient::from_token(&base_url, sample_identity(), "tok");

        let data = client
            .set_favorite("item-1", true)
            .await
            .expect("set_favorite against mock server");
        assert_eq!(data.played, Some(true));

        let request = rx.await.expect("mock server captured a request");
        let request_line = request.lines().next().unwrap_or_default();
        assert!(
            request_line.starts_with("POST /UserFavoriteItems/item-1"),
            "unexpected request line: {request_line}"
        );
    }

    #[tokio::test]
    async fn set_favorite_false_deletes_user_favorite_items() {
        let (base_url, rx) = capturing_json_server(sample_user_item_data_json().to_string()).await;
        let client = JellyfinClient::from_token(&base_url, sample_identity(), "tok");

        client
            .set_favorite("item-1", false)
            .await
            .expect("set_favorite against mock server");

        let request = rx.await.expect("mock server captured a request");
        let request_line = request.lines().next().unwrap_or_default();
        assert!(
            request_line.starts_with("DELETE /UserFavoriteItems/item-1"),
            "unexpected request line: {request_line}"
        );
    }

    #[tokio::test]
    async fn list_collections_sends_expected_boxset_query() {
        let raw = include_str!("../tests/fixtures/similar_items_result.json");
        let (base_url, rx) = capturing_json_server(raw.to_string()).await;
        let client = JellyfinClient::from_token(&base_url, sample_identity(), "tok");

        let items = client
            .list_collections()
            .await
            .expect("list_collections against mock server");
        assert_eq!(items.len(), 2, "reuses the same ItemsResult shape");

        let request = rx.await.expect("mock server captured a request");
        let request_line = request.lines().next().unwrap_or_default();
        assert!(
            request_line.starts_with("GET /Items?"),
            "unexpected request line: {request_line}"
        );
        assert!(request_line.contains("includeItemTypes=BoxSet"));
        assert!(request_line.contains("recursive=true"));
        assert!(request_line.contains("sortBy=SortName"));
        assert!(request_line.contains("sortOrder=Ascending"));
        assert!(request_line.contains("limit=200"));
    }

    #[tokio::test]
    async fn add_to_collection_posts_ids_as_a_query_param() {
        let (base_url, rx) = capturing_json_server(String::new()).await;
        let client = JellyfinClient::from_token(&base_url, sample_identity(), "tok");

        client
            .add_to_collection("collection 1", "item 1")
            .await
            .expect("add_to_collection against mock server");

        let request = rx.await.expect("mock server captured a request");
        let request_line = request.lines().next().unwrap_or_default();
        assert!(
            request_line.starts_with("POST /Collections/collection%201/Items?"),
            "unexpected request line: {request_line}"
        );
        assert!(
            // reqwest's `.query()` form-encodes (space -> `+`), unlike the
            // manual `percent_encode` path segments use -- both decode the
            // same server-side.
            request_line.contains("ids=item+1"),
            "request line missing ids: {request_line}"
        );
    }

    #[tokio::test]
    async fn refresh_item_posts_with_fixed_refresh_parameters() {
        let (base_url, rx) = capturing_json_server(String::new()).await;
        let client = JellyfinClient::from_token(&base_url, sample_identity(), "tok");

        client
            .refresh_item("item 1")
            .await
            .expect("refresh_item against mock server");

        let request = rx.await.expect("mock server captured a request");
        let request_line = request.lines().next().unwrap_or_default();
        assert!(
            request_line.starts_with("POST /Items/item%201/Refresh?"),
            "unexpected request line: {request_line}"
        );
        assert!(request_line.contains("metadataRefreshMode=FullRefresh"));
        assert!(request_line.contains("imageRefreshMode=FullRefresh"));
        assert!(request_line.contains("replaceAllMetadata=false"));
        assert!(request_line.contains("replaceAllImages=false"));
    }

    #[tokio::test]
    async fn current_user_gets_users_me() {
        let raw = include_str!("../tests/fixtures/authentication_result.json");
        let user_json = serde_json::from_str::<serde_json::Value>(raw)
            .expect("valid json")
            .get("User")
            .cloned()
            .expect("fixture has a User");
        let (base_url, rx) = capturing_json_server(user_json.to_string()).await;
        let client = JellyfinClient::from_token(&base_url, sample_identity(), "tok");

        let user = client
            .current_user()
            .await
            .expect("current_user against mock server");
        assert!(user.id.is_some());

        let request = rx.await.expect("mock server captured a request");
        let request_line = request.lines().next().unwrap_or_default();
        assert!(
            request_line.starts_with("GET /Users/Me"),
            "unexpected request line: {request_line}"
        );
    }
}
