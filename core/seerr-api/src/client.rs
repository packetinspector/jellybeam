//! `SeerrClient` -- hand-written REST client for the ~20 Seerr endpoints this app calls.
//! Auth (`docs/14-seerr-discover.md` "Auth"): JELLYFIN/LOCAL log in via `POST`, capturing
//! `connect.sid` from `Set-Cookie` manually (no reqwest cookie-store); API_KEY sends
//! `X-Api-Key` and never re-authenticates. A 401 on a cookie-authenticated request retries once
//! via re-login, else `SeerrError::Unauthorized`. `Inner`/`AuthState` skip `Debug` so secrets
//! never print.

use std::sync::{Arc, Mutex};
use std::time::Duration;

use serde::de::DeserializeOwned;
use serde::Serialize;

use crate::error::SeerrError;
use crate::models::*;
use crate::url::normalize_api_base;
use crate::util::describe_error_chain;

/// Cap on [`SeerrError::Status`] response bodies, in bytes; same as `jellyfin-api`'s cap.
const STATUS_ERROR_BODY_CAP: usize = 2048;

/// Ceiling on a JSON response body, in bytes; smaller than `jellyfin-api`'s (TMDB pages are small).
const JSON_BODY_CAP: usize = 2 * 1024 * 1024;

/// Which of Seerr's three login mechanisms a saved account uses (`docs/14-seerr-discover.md`'s
/// `SeerrAuthMethod`); kept free of uniffi/Android types so the decision logic stays platform-neutral.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SeerrAuthMethod {
    Jellyfin,
    Local,
    ApiKey,
}

/// Optional filters for [`SeerrClient::discover_movies`]/[`discover_tv`]
/// (`docs/14-seerr-discover.md`'s `SeerrBrowseFilters`); raw query-param passthrough, unvalidated.
#[derive(Debug, Clone, Default)]
pub struct BrowseFilters {
    pub sort_by: Option<String>,
    pub genre_id: Option<i64>,
    pub min_vote: Option<f64>,
    /// TV only -- ignored by `discover_movies`.
    pub network_id: Option<i64>,
    /// TV only -- ignored by `discover_movies`.
    pub status: Option<String>,
}

enum AuthState {
    ApiKey(String),
    Cookie {
        method: SeerrAuthMethod,
        identity: String,
        secret: String,
        cookie: Mutex<Option<String>>,
    },
}

struct Inner {
    /// Normalized (`normalize_api_base`), no trailing slash, ends in `/api/v1`.
    base_url: String,
    /// The logged-in Seerr user's id (from the login response or `/auth/me`);
    /// scopes [`SeerrClient::my_requests`].
    user_id: i64,
    http: reqwest::Client,
    auth: AuthState,
}

/// One authenticated Seerr connection; cheap to clone (`Arc`-backed), like
/// `jellyfin_api::JellyfinClient`.
#[derive(Clone)]
pub struct SeerrClient {
    inner: Arc<Inner>,
}

fn build_http_client(
    connect_timeout: Duration,
    request_timeout: Duration,
) -> Result<reqwest::Client, SeerrError> {
    reqwest::Client::builder()
        .connect_timeout(connect_timeout)
        .timeout(request_timeout)
        .build()
        .map_err(|e| SeerrError::Transport(describe_error_chain(&e)))
}

async fn read_capped_body(
    mut resp: reqwest::Response,
    cap: usize,
    what: &str,
) -> Result<Vec<u8>, SeerrError> {
    let mut body: Vec<u8> = Vec::new();
    loop {
        let chunk = match resp.chunk().await {
            Ok(Some(chunk)) => chunk,
            Ok(None) => return Ok(body),
            Err(e) => return Err(SeerrError::Transport(describe_error_chain(&e))),
        };
        if body.len() + chunk.len() > cap {
            tracing::warn!(path = %what, cap, "seerr response body exceeded the size cap; aborting read");
            return Err(SeerrError::Decode(format!(
                "response body exceeded {cap} bytes"
            )));
        }
        body.extend_from_slice(&chunk);
    }
}

async fn read_json_capped<T: DeserializeOwned>(
    resp: reqwest::Response,
    what: &str,
) -> Result<T, SeerrError> {
    let body = read_capped_body(resp, JSON_BODY_CAP, what).await?;
    serde_json::from_slice(&body).map_err(|e| SeerrError::Decode(e.to_string()))
}

/// Never fails: an unreadable or over-cap body becomes a description string instead of an error,
/// since this only ever feeds [`SeerrError::Status`]'s diagnostic text.
async fn capped_body_text(resp: reqwest::Response, cap: usize) -> String {
    match read_capped_body(resp, cap, "error body").await {
        Ok(body) => String::from_utf8_lossy(&body).into_owned(),
        Err(err) => format!("<failed to read response body: {err}>"),
    }
}

async fn check_status(resp: reqwest::Response) -> Result<reqwest::Response, SeerrError> {
    if resp.status() == reqwest::StatusCode::UNAUTHORIZED {
        return Err(SeerrError::Unauthorized);
    }
    if !resp.status().is_success() {
        let code = resp.status().as_u16();
        let body = capped_body_text(resp, STATUS_ERROR_BODY_CAP).await;
        return Err(SeerrError::Status { code, body });
    }
    Ok(resp)
}

/// Extract the `connect.sid` value (up to the first `;`) from `Set-Cookie`; only `connect.sid` is
/// kept among possibly several headers.
fn extract_session_cookie(resp: &reqwest::Response) -> Option<String> {
    for value in resp.headers().get_all(reqwest::header::SET_COOKIE) {
        let Ok(text) = value.to_str() else { continue };
        let Some(rest) = text.strip_prefix("connect.sid=") else {
            continue;
        };
        let value = rest.split(';').next().unwrap_or(rest).trim();
        if !value.is_empty() {
            return Some(value.to_string());
        }
    }
    None
}

#[derive(Serialize)]
struct JellyfinAuthBody<'a> {
    username: &'a str,
    password: &'a str,
}

#[derive(Serialize)]
struct LocalAuthBody<'a> {
    email: &'a str,
    password: &'a str,
}

/// [`SeerrClient::login`]'s parameters: bundled because three of the six
/// (`base_url`/`identity`/`secret`) are `&str` -- a bare positional list that
/// size is a transposition hazard (e.g. passing `identity` where `secret` is
/// expected compiles silently). Field names match the old parameter names.
pub struct LoginArgs<'a> {
    pub base_url: &'a str,
    pub method: SeerrAuthMethod,
    pub identity: &'a str,
    pub secret: &'a str,
    pub connect_timeout: Duration,
    pub request_timeout: Duration,
}

impl SeerrClient {
    /// Authenticate against `args.base_url` with `args.method`, returning a ready client
    /// plus the logged-in [`User`]; `connect_timeout`/`request_timeout` let
    /// `seerr_connect`'s candidate probing and a lazy handle rebuild share this entry point.
    pub async fn login(args: LoginArgs<'_>) -> Result<(Self, User), SeerrError> {
        let LoginArgs {
            base_url,
            method,
            identity,
            secret,
            connect_timeout,
            request_timeout,
        } = args;
        let base_url = normalize_api_base(base_url);
        let http = build_http_client(connect_timeout, request_timeout)?;

        match method {
            SeerrAuthMethod::ApiKey => {
                let probe = SeerrClient {
                    inner: Arc::new(Inner {
                        base_url: base_url.clone(),
                        user_id: 0,
                        http: http.clone(),
                        auth: AuthState::ApiKey(secret.to_string()),
                    }),
                };
                let user = probe.fetch_me().await?;
                let client = SeerrClient {
                    inner: Arc::new(Inner {
                        base_url,
                        user_id: user.id,
                        http,
                        auth: AuthState::ApiKey(secret.to_string()),
                    }),
                };
                Ok((client, user))
            }
            SeerrAuthMethod::Jellyfin | SeerrAuthMethod::Local => {
                let (cookie, user) =
                    Self::login_cookie(&http, &base_url, method, identity, secret).await?;
                let client = SeerrClient {
                    inner: Arc::new(Inner {
                        base_url,
                        user_id: user.id,
                        http,
                        auth: AuthState::Cookie {
                            method,
                            identity: identity.to_string(),
                            secret: secret.to_string(),
                            cookie: Mutex::new(Some(cookie)),
                        },
                    }),
                };
                Ok((client, user))
            }
        }
    }

    async fn login_cookie(
        http: &reqwest::Client,
        base_url: &str,
        method: SeerrAuthMethod,
        identity: &str,
        secret: &str,
    ) -> Result<(String, User), SeerrError> {
        let path = match method {
            SeerrAuthMethod::Jellyfin => "/auth/jellyfin",
            SeerrAuthMethod::Local => "/auth/local",
            SeerrAuthMethod::ApiKey => {
                return Err(SeerrError::Decode(
                    "login_cookie called with API_KEY method".to_string(),
                ));
            }
        };
        let url = format!("{base_url}{path}");
        let builder = match method {
            SeerrAuthMethod::Jellyfin => http.post(&url).json(&JellyfinAuthBody {
                username: identity,
                password: secret,
            }),
            SeerrAuthMethod::Local => http.post(&url).json(&LocalAuthBody {
                email: identity,
                password: secret,
            }),
            SeerrAuthMethod::ApiKey => unreachable!("handled above"),
        };
        let resp = builder
            .send()
            .await
            .map_err(|e| SeerrError::Transport(describe_error_chain(&e)))?;
        let cookie = extract_session_cookie(&resp);
        let resp = check_status(resp).await?;
        let user: User = read_json_capped(resp, path).await?;
        let cookie = cookie.ok_or_else(|| {
            SeerrError::Decode("login response carried no connect.sid cookie".to_string())
        })?;
        Ok((cookie, user))
    }

    async fn fetch_me(&self) -> Result<User, SeerrError> {
        self.request_json(reqwest::Method::GET, "/auth/me", &[], None)
            .await
    }

    fn attach_auth(&self, builder: reqwest::RequestBuilder) -> reqwest::RequestBuilder {
        match &self.inner.auth {
            AuthState::ApiKey(key) => builder.header("X-Api-Key", key),
            AuthState::Cookie { cookie, .. } => {
                let value = cookie.lock().unwrap_or_else(|e| e.into_inner()).clone();
                match value {
                    Some(c) => builder.header("Cookie", format!("connect.sid={c}")),
                    None => builder,
                }
            }
        }
    }

    async fn execute(
        &self,
        method: reqwest::Method,
        path: &str,
        query: &[(&str, String)],
        body: &Option<serde_json::Value>,
    ) -> Result<reqwest::Response, SeerrError> {
        let url = format!("{}{}", self.inner.base_url, path);
        let mut builder = self.inner.http.request(method, &url);
        if !query.is_empty() {
            builder = builder.query(query);
        }
        builder = self.attach_auth(builder);
        if let Some(body) = body {
            builder = builder.json(body);
        }
        builder
            .send()
            .await
            .map_err(|e| SeerrError::Transport(describe_error_chain(&e)))
    }

    /// Retries once on a 401 from a cookie-authenticated client (see the
    /// auth note above); an API-key client or a failed re-login returns the 401.
    async fn send_with_relogin(
        &self,
        method: reqwest::Method,
        path: &str,
        query: &[(&str, String)],
        body: Option<serde_json::Value>,
    ) -> Result<reqwest::Response, SeerrError> {
        let resp = self.execute(method.clone(), path, query, &body).await?;
        if resp.status() != reqwest::StatusCode::UNAUTHORIZED {
            return Ok(resp);
        }
        let AuthState::Cookie {
            method: auth_method,
            identity,
            secret,
            cookie,
        } = &self.inner.auth
        else {
            return Ok(resp);
        };
        let Ok((new_cookie, _user)) = Self::login_cookie(
            &self.inner.http,
            &self.inner.base_url,
            *auth_method,
            identity,
            secret,
        )
        .await
        else {
            return Ok(resp);
        };
        *cookie.lock().unwrap_or_else(|e| e.into_inner()) = Some(new_cookie);
        self.execute(method, path, query, &body).await
    }

    async fn request_json<T: DeserializeOwned>(
        &self,
        method: reqwest::Method,
        path: &str,
        query: &[(&str, String)],
        body: Option<serde_json::Value>,
    ) -> Result<T, SeerrError> {
        let resp = self.send_with_relogin(method, path, query, body).await?;
        let resp = check_status(resp).await?;
        read_json_capped(resp, path).await
    }

    /// Like [`Self::request_json`], but a 404 means "no data" (`Ok(None)`) -- used by the ratings
    /// endpoints.
    async fn request_optional_json<T: DeserializeOwned>(
        &self,
        path: &str,
    ) -> Result<Option<T>, SeerrError> {
        let resp = self
            .send_with_relogin(reqwest::Method::GET, path, &[], None)
            .await?;
        if resp.status() == reqwest::StatusCode::NOT_FOUND {
            return Ok(None);
        }
        let resp = check_status(resp).await?;
        Ok(Some(read_json_capped(resp, path).await?))
    }

    async fn request_no_content(
        &self,
        method: reqwest::Method,
        path: &str,
    ) -> Result<(), SeerrError> {
        let resp = self.send_with_relogin(method, path, &[], None).await?;
        check_status(resp).await?;
        Ok(())
    }

    pub async fn public_settings(&self) -> Result<PublicSettings, SeerrError> {
        self.request_json(reqwest::Method::GET, "/settings/public", &[], None)
            .await
    }

    pub async fn search(
        &self,
        query: &str,
        page: i64,
    ) -> Result<ResultPage<MediaResult>, SeerrError> {
        self.request_json(
            reqwest::Method::GET,
            "/search",
            &[("query", query.to_string()), ("page", page.to_string())],
            None,
        )
        .await
    }

    pub async fn discover_trending(
        &self,
        page: i64,
    ) -> Result<ResultPage<MediaResult>, SeerrError> {
        self.request_json(
            reqwest::Method::GET,
            "/discover/trending",
            &[("page", page.to_string())],
            None,
        )
        .await
    }

    fn browse_query(page: i64, filters: &BrowseFilters, tv: bool) -> Vec<(&'static str, String)> {
        let mut query = vec![("page", page.to_string())];
        if let Some(sort_by) = &filters.sort_by {
            query.push(("sortBy", sort_by.clone()));
        }
        if let Some(genre_id) = filters.genre_id {
            query.push(("genre", genre_id.to_string()));
        }
        if let Some(min_vote) = filters.min_vote {
            query.push(("voteAverageGte", min_vote.to_string()));
        }
        if tv {
            if let Some(network_id) = filters.network_id {
                query.push(("network", network_id.to_string()));
            }
            if let Some(status) = &filters.status {
                query.push(("status", status.clone()));
            }
        }
        query
    }

    pub async fn discover_movies(
        &self,
        page: i64,
        filters: &BrowseFilters,
    ) -> Result<ResultPage<MediaResult>, SeerrError> {
        self.request_json(
            reqwest::Method::GET,
            "/discover/movies",
            &Self::browse_query(page, filters, false),
            None,
        )
        .await
    }

    pub async fn discover_tv(
        &self,
        page: i64,
        filters: &BrowseFilters,
    ) -> Result<ResultPage<MediaResult>, SeerrError> {
        self.request_json(
            reqwest::Method::GET,
            "/discover/tv",
            &Self::browse_query(page, filters, true),
            None,
        )
        .await
    }

    pub async fn discover_movies_upcoming(
        &self,
        page: i64,
    ) -> Result<ResultPage<MediaResult>, SeerrError> {
        self.request_json(
            reqwest::Method::GET,
            "/discover/movies/upcoming",
            &[("page", page.to_string())],
            None,
        )
        .await
    }

    pub async fn discover_tv_upcoming(
        &self,
        page: i64,
    ) -> Result<ResultPage<MediaResult>, SeerrError> {
        self.request_json(
            reqwest::Method::GET,
            "/discover/tv/upcoming",
            &[("page", page.to_string())],
            None,
        )
        .await
    }

    pub async fn genres_movie(&self) -> Result<Vec<Genre>, SeerrError> {
        self.request_json(reqwest::Method::GET, "/genres/movie", &[], None)
            .await
    }

    pub async fn genres_tv(&self) -> Result<Vec<Genre>, SeerrError> {
        self.request_json(reqwest::Method::GET, "/genres/tv", &[], None)
            .await
    }

    pub async fn movie_details(&self, tmdb_id: i64) -> Result<MovieDetails, SeerrError> {
        self.request_json(
            reqwest::Method::GET,
            &format!("/movie/{tmdb_id}"),
            &[],
            None,
        )
        .await
    }

    pub async fn movie_similar(
        &self,
        tmdb_id: i64,
        page: i64,
    ) -> Result<ResultPage<MediaResult>, SeerrError> {
        self.request_json(
            reqwest::Method::GET,
            &format!("/movie/{tmdb_id}/similar"),
            &[("page", page.to_string())],
            None,
        )
        .await
    }

    pub async fn movie_recommendations(
        &self,
        tmdb_id: i64,
        page: i64,
    ) -> Result<ResultPage<MediaResult>, SeerrError> {
        self.request_json(
            reqwest::Method::GET,
            &format!("/movie/{tmdb_id}/recommendations"),
            &[("page", page.to_string())],
            None,
        )
        .await
    }

    /// `GET /movie/{id}/ratings`; 404 (no score) maps to `Ok(None)`.
    pub async fn movie_ratings(&self, tmdb_id: i64) -> Result<Option<Ratings>, SeerrError> {
        self.request_optional_json(&format!("/movie/{tmdb_id}/ratings"))
            .await
    }

    pub async fn tv_details(&self, tmdb_id: i64) -> Result<TvDetails, SeerrError> {
        self.request_json(reqwest::Method::GET, &format!("/tv/{tmdb_id}"), &[], None)
            .await
    }

    pub async fn tv_similar(
        &self,
        tmdb_id: i64,
        page: i64,
    ) -> Result<ResultPage<MediaResult>, SeerrError> {
        self.request_json(
            reqwest::Method::GET,
            &format!("/tv/{tmdb_id}/similar"),
            &[("page", page.to_string())],
            None,
        )
        .await
    }

    pub async fn tv_recommendations(
        &self,
        tmdb_id: i64,
        page: i64,
    ) -> Result<ResultPage<MediaResult>, SeerrError> {
        self.request_json(
            reqwest::Method::GET,
            &format!("/tv/{tmdb_id}/recommendations"),
            &[("page", page.to_string())],
            None,
        )
        .await
    }

    /// `GET /tv/{id}/ratings`; same 404-as-None contract as [`Self::movie_ratings`].
    pub async fn tv_ratings(&self, tmdb_id: i64) -> Result<Option<Ratings>, SeerrError> {
        self.request_optional_json(&format!("/tv/{tmdb_id}/ratings"))
            .await
    }

    pub async fn person_details(&self, person_id: i64) -> Result<PersonDetails, SeerrError> {
        self.request_json(
            reqwest::Method::GET,
            &format!("/person/{person_id}"),
            &[],
            None,
        )
        .await
    }

    pub async fn person_combined_credits(
        &self,
        person_id: i64,
    ) -> Result<CombinedCredits, SeerrError> {
        self.request_json(
            reqwest::Method::GET,
            &format!("/person/{person_id}/combined_credits"),
            &[],
            None,
        )
        .await
    }

    pub async fn service_radarr_list(&self) -> Result<Vec<ServarrInstance>, SeerrError> {
        self.request_json(reqwest::Method::GET, "/service/radarr", &[], None)
            .await
    }

    pub async fn service_radarr_detail(&self, id: i64) -> Result<ServiceDetail, SeerrError> {
        self.request_json(
            reqwest::Method::GET,
            &format!("/service/radarr/{id}"),
            &[],
            None,
        )
        .await
    }

    pub async fn service_sonarr_list(&self) -> Result<Vec<ServarrInstance>, SeerrError> {
        self.request_json(reqwest::Method::GET, "/service/sonarr", &[], None)
            .await
    }

    pub async fn service_sonarr_detail(&self, id: i64) -> Result<ServiceDetail, SeerrError> {
        self.request_json(
            reqwest::Method::GET,
            &format!("/service/sonarr/{id}"),
            &[],
            None,
        )
        .await
    }

    /// `POST /request`; always creates (POST-vs-PUT decided by the caller,
    /// `core/ffi/src/seerr.rs::decide_request_action`).
    pub async fn request_create(
        &self,
        body: RequestCreateBody,
    ) -> Result<MediaRequest, SeerrError> {
        let json = serde_json::to_value(&body)
            .map_err(|e| SeerrError::Decode(format!("failed to encode request body: {e}")))?;
        self.request_json(reqwest::Method::POST, "/request", &[], Some(json))
            .await
    }

    /// `PUT /request/{id}`; updates rather than duplicating an existing pending request.
    pub async fn request_update(
        &self,
        request_id: i64,
        body: RequestUpdateBody,
    ) -> Result<MediaRequest, SeerrError> {
        let json = serde_json::to_value(&body)
            .map_err(|e| SeerrError::Decode(format!("failed to encode request body: {e}")))?;
        self.request_json(
            reqwest::Method::PUT,
            &format!("/request/{request_id}"),
            &[],
            Some(json),
        )
        .await
    }

    /// `DELETE /request/{id}` -- cancels a request.
    pub async fn request_delete(&self, request_id: i64) -> Result<(), SeerrError> {
        self.request_no_content(reqwest::Method::DELETE, &format!("/request/{request_id}"))
            .await
    }

    /// `GET /request?take=&skip=&requestedBy=<user id>`. The server only
    /// auto-scopes for unprivileged accounts -- an `ADMIN`/`MANAGE_REQUESTS`
    /// user (the instance owner, this app's usual user) would otherwise see
    /// everyone's requests as "My Requests" -- so the filter is always sent.
    /// Under `API_KEY` the id is the key owner's, from `/auth/me`.
    pub async fn my_requests(&self, take: i64, skip: i64) -> Result<RequestsPage, SeerrError> {
        self.request_json(
            reqwest::Method::GET,
            "/request",
            &[
                ("take", take.to_string()),
                ("skip", skip.to_string()),
                ("requestedBy", self.inner.user_id.to_string()),
            ],
            None,
        )
        .await
    }
}

#[cfg(test)]
mod tests;
