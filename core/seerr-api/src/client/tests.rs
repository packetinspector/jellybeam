//! `SeerrClient` tests against hand-rolled `TcpListener` mock servers (the
//! `jellyfin-api` convention), extended with a scripted multi-response server for 401-then-retry.

use super::*;
use std::collections::HashMap;
use std::sync::Arc;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;

/// One request the mock server received: method, path+query, lower-cased header names, and raw body
/// bytes.
struct CapturedRequest {
    method: String,
    path: String,
    headers: HashMap<String, String>,
    body: String,
}

/// One canned response the mock server sends back, in order.
struct ScriptedResponse {
    status_line: &'static str,
    headers: Vec<(&'static str, String)>,
    body: String,
}

impl ScriptedResponse {
    fn ok_json(body: impl Into<String>) -> Self {
        ScriptedResponse {
            status_line: "200 OK",
            headers: vec![("Content-Type", "application/json".to_string())],
            body: body.into(),
        }
    }

    fn unauthorized() -> Self {
        ScriptedResponse {
            status_line: "401 Unauthorized",
            headers: Vec::new(),
            body: String::new(),
        }
    }

    fn not_found() -> Self {
        ScriptedResponse {
            status_line: "404 Not Found",
            headers: Vec::new(),
            body: String::new(),
        }
    }

    fn with_cookie(mut self, cookie: &str) -> Self {
        self.headers.push((
            "Set-Cookie",
            format!("connect.sid={cookie}; Path=/; HttpOnly"),
        ));
        self
    }
}

/// Starts a local ephemeral-port server serving each queued
/// [`ScriptedResponse`] in order; returns the base URL and the capture list.
async fn scripted_server(
    responses: Vec<ScriptedResponse>,
) -> (String, Arc<Mutex<Vec<CapturedRequest>>>) {
    let listener = TcpListener::bind("127.0.0.1:0")
        .await
        .expect("bind local listener");
    let addr = listener.local_addr().expect("local_addr");
    let captured = Arc::new(Mutex::new(Vec::new()));
    let captured_task = captured.clone();

    tokio::spawn(async move {
        for response in responses {
            let Ok((mut stream, _)) = listener.accept().await else {
                break;
            };

            let mut buf = Vec::new();
            let mut chunk = [0u8; 4096];
            let header_end = loop {
                let n = stream.read(&mut chunk).await.unwrap_or(0);
                if n == 0 {
                    break None;
                }
                buf.extend_from_slice(&chunk[..n]);
                if let Some(pos) = find_header_end(&buf) {
                    break Some(pos);
                }
            };
            let Some(header_end) = header_end else {
                continue;
            };

            let head = String::from_utf8_lossy(&buf[..header_end]).to_string();
            let mut lines = head.split("\r\n");
            let request_line = lines.next().unwrap_or_default();
            let mut parts = request_line.split_whitespace();
            let method = parts.next().unwrap_or_default().to_string();
            let path = parts.next().unwrap_or_default().to_string();

            let mut headers = HashMap::new();
            let mut content_length = 0usize;
            for line in lines {
                if let Some((name, value)) = line.split_once(':') {
                    let name = name.trim().to_ascii_lowercase();
                    let value = value.trim().to_string();
                    if name == "content-length" {
                        content_length = value.parse().unwrap_or(0);
                    }
                    headers.insert(name, value);
                }
            }

            let mut body_bytes = buf[header_end..].to_vec();
            while body_bytes.len() < content_length {
                let n = stream.read(&mut chunk).await.unwrap_or(0);
                if n == 0 {
                    break;
                }
                body_bytes.extend_from_slice(&chunk[..n]);
            }
            body_bytes.truncate(content_length.max(body_bytes.len().min(content_length)));
            let body = String::from_utf8_lossy(&body_bytes).to_string();

            captured_task
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .push(CapturedRequest {
                    method,
                    path,
                    headers,
                    body,
                });

            let mut header_text = String::new();
            for (name, value) in &response.headers {
                header_text.push_str(&format!("{name}: {value}\r\n"));
            }
            let out = format!(
                "HTTP/1.1 {}\r\nContent-Length: {}\r\nConnection: close\r\n{}\r\n{}",
                response.status_line,
                response.body.len(),
                header_text,
                response.body
            );
            let _ = stream.write_all(out.as_bytes()).await;
            let _ = stream.shutdown().await;
        }
    });

    (format!("http://{addr}"), captured)
}

fn find_header_end(buf: &[u8]) -> Option<usize> {
    buf.windows(4).position(|w| w == b"\r\n\r\n").map(|p| p + 4)
}

fn probe_timeouts() -> (Duration, Duration) {
    (Duration::from_secs(2), Duration::from_secs(6))
}

fn sample_user_json(id: i64) -> String {
    format!(r#"{{"id":{id},"username":"jellybeam-test-user"}}"#)
}

// --- Auth flows ----------------------------------------------------------

#[tokio::test]
async fn jellyfin_login_captures_the_connect_sid_cookie() {
    let (base, captured) = scripted_server(vec![
        ScriptedResponse::ok_json(sample_user_json(7)).with_cookie("abc123")
    ])
    .await;
    let (connect_timeout, request_timeout) = probe_timeouts();

    let (_client, user) = SeerrClient::login(LoginArgs {
        base_url: &base,
        method: SeerrAuthMethod::Jellyfin,
        identity: "alice",
        secret: "hunter2",
        connect_timeout,
        request_timeout,
    })
    .await
    .expect("login should succeed");

    assert_eq!(user.id, 7);
    let requests = captured.lock().unwrap_or_else(|e| e.into_inner());
    assert_eq!(requests.len(), 1);
    assert_eq!(requests[0].method, "POST");
    assert_eq!(requests[0].path, "/api/v1/auth/jellyfin");
    assert!(requests[0].body.contains("\"username\":\"alice\""));
    assert!(requests[0].body.contains("\"password\":\"hunter2\""));
}

#[tokio::test]
async fn local_login_hits_auth_local_with_email_and_password() {
    let (base, captured) = scripted_server(vec![
        ScriptedResponse::ok_json(sample_user_json(3)).with_cookie("xyz789")
    ])
    .await;
    let (connect_timeout, request_timeout) = probe_timeouts();

    SeerrClient::login(LoginArgs {
        base_url: &base,
        method: SeerrAuthMethod::Local,
        identity: "person@seerr.test",
        secret: "correct-horse",
        connect_timeout,
        request_timeout,
    })
    .await
    .expect("login should succeed");

    let requests = captured.lock().unwrap_or_else(|e| e.into_inner());
    assert_eq!(requests[0].path, "/api/v1/auth/local");
    assert!(requests[0].body.contains("\"email\":\"person@seerr.test\""));
}

#[tokio::test]
async fn api_key_client_sends_the_header_on_every_request_and_never_relogs_in() {
    let (base, captured) = scripted_server(vec![
        ScriptedResponse::ok_json(sample_user_json(1)), // /auth/me probe on login
        ScriptedResponse::ok_json(r#"[]"#),             // genres/movie
    ])
    .await;
    let (connect_timeout, request_timeout) = probe_timeouts();

    let (client, user) = SeerrClient::login(LoginArgs {
        base_url: &base,
        method: SeerrAuthMethod::ApiKey,
        identity: "",
        secret: "super-secret-key",
        connect_timeout,
        request_timeout,
    })
    .await
    .expect("api key login should succeed");
    assert_eq!(user.id, 1);

    client
        .genres_movie()
        .await
        .expect("genres call should succeed");

    let requests = captured.lock().unwrap_or_else(|e| e.into_inner());
    assert_eq!(requests.len(), 2);
    for req in requests.iter() {
        assert_eq!(
            req.headers.get("x-api-key").map(String::as_str),
            Some("super-secret-key"),
            "every request from an API-key client must carry X-Api-Key"
        );
    }
    assert!(
        !requests
            .iter()
            .any(|r| r.path.contains("/auth/jellyfin") || r.path.contains("/auth/local")),
        "an API-key client must never attempt a cookie re-login"
    );
}

#[tokio::test]
async fn a_401_triggers_exactly_one_relogin_then_replays_the_original_request() {
    let (base, captured) = scripted_server(vec![
        ScriptedResponse::ok_json(sample_user_json(9)).with_cookie("first-cookie"),
        ScriptedResponse::unauthorized(), // the real call, first attempt: session expired
        ScriptedResponse::ok_json(sample_user_json(9)).with_cookie("second-cookie"), // relogin
        ScriptedResponse::ok_json(r#"[{"id":1,"name":"Action"}]"#), // replay succeeds
    ])
    .await;
    let (connect_timeout, request_timeout) = probe_timeouts();

    let (client, _user) = SeerrClient::login(LoginArgs {
        base_url: &base,
        method: SeerrAuthMethod::Jellyfin,
        identity: "alice",
        secret: "hunter2",
        connect_timeout,
        request_timeout,
    })
    .await
    .expect("initial login should succeed");

    let genres = client
        .genres_movie()
        .await
        .expect("call should succeed after the transparent relogin");
    assert_eq!(genres.len(), 1);
    assert_eq!(genres[0].name.as_deref(), Some("Action"));

    let requests = captured.lock().unwrap_or_else(|e| e.into_inner());
    // login, genres(401), relogin, genres(replay) -- exactly one relogin.
    assert_eq!(requests.len(), 4);
    assert_eq!(requests[0].path, "/api/v1/auth/jellyfin");
    assert_eq!(requests[1].path, "/api/v1/genres/movie");
    assert_eq!(
        requests[1].headers.get("cookie").map(String::as_str),
        Some("connect.sid=first-cookie")
    );
    assert_eq!(requests[2].path, "/api/v1/auth/jellyfin");
    assert_eq!(requests[3].path, "/api/v1/genres/movie");
    assert_eq!(
        requests[3].headers.get("cookie").map(String::as_str),
        Some("connect.sid=second-cookie")
    );
}

#[tokio::test]
async fn a_401_that_survives_relogin_surfaces_as_unauthorized_without_looping() {
    let (base, captured) = scripted_server(vec![
        ScriptedResponse::ok_json(sample_user_json(9)).with_cookie("first-cookie"),
        ScriptedResponse::unauthorized(), // real call
        ScriptedResponse::ok_json(sample_user_json(9)).with_cookie("second-cookie"), // relogin succeeds
        ScriptedResponse::unauthorized(), // replay still 401s
    ])
    .await;
    let (connect_timeout, request_timeout) = probe_timeouts();

    let (client, _user) = SeerrClient::login(LoginArgs {
        base_url: &base,
        method: SeerrAuthMethod::Jellyfin,
        identity: "alice",
        secret: "hunter2",
        connect_timeout,
        request_timeout,
    })
    .await
    .expect("initial login should succeed");

    let err = client
        .genres_movie()
        .await
        .expect_err("a still-401 replay must surface as Unauthorized");
    assert!(matches!(err, SeerrError::Unauthorized));

    // Exactly 4 requests: no second relogin after the replay also failed.
    assert_eq!(captured.lock().unwrap_or_else(|e| e.into_inner()).len(), 4);
}

// --- Capped error-body reading ---------------------------------------------

/// Pins: a non-2xx body larger than [`STATUS_ERROR_BODY_CAP`] never reaches
/// `SeerrError::Status` in full -- `capped_body_text` must stream and stop at the cap rather
/// than buffering the whole response.
#[tokio::test]
async fn error_status_body_over_the_cap_is_a_bounded_message_not_the_full_body() {
    let huge_body = "x".repeat(STATUS_ERROR_BODY_CAP * 2);
    let (base, _captured) = scripted_server(vec![ScriptedResponse {
        status_line: "500 Internal Server Error",
        headers: Vec::new(),
        body: huge_body.clone(),
    }])
    .await;
    let (connect_timeout, request_timeout) = probe_timeouts();

    let Err(err) = SeerrClient::login(LoginArgs {
        base_url: &base,
        method: SeerrAuthMethod::Jellyfin,
        identity: "alice",
        secret: "hunter2",
        connect_timeout,
        request_timeout,
    })
    .await
    else {
        panic!("a 500 response must surface as an error");
    };

    match err {
        SeerrError::Status { code, body } => {
            assert_eq!(code, 500);
            assert!(
                body.len() < huge_body.len(),
                "the error body must not carry the full oversized response, got {} bytes",
                body.len()
            );
            assert!(
                body.contains("exceeded"),
                "expected a bounded-read description, got: {body}"
            );
        }
        other => panic!("expected SeerrError::Status, got {other:?}"),
    }
}

// --- Ratings 404-as-None ---------------------------------------------------

#[tokio::test]
async fn movie_ratings_404_maps_to_ok_none() {
    let (base, _captured) = scripted_server(vec![
        ScriptedResponse::ok_json(sample_user_json(1)).with_cookie("c"),
        ScriptedResponse::not_found(),
    ])
    .await;
    let (connect_timeout, request_timeout) = probe_timeouts();
    let (client, _user) = SeerrClient::login(LoginArgs {
        base_url: &base,
        method: SeerrAuthMethod::Jellyfin,
        identity: "alice",
        secret: "hunter2",
        connect_timeout,
        request_timeout,
    })
    .await
    .expect("login should succeed");

    let ratings = client
        .movie_ratings(42)
        .await
        .expect("a 404 must not be surfaced as an error");
    assert!(ratings.is_none());
}

#[tokio::test]
async fn tv_ratings_404_maps_to_ok_none() {
    let (base, _captured) = scripted_server(vec![
        ScriptedResponse::ok_json(sample_user_json(1)).with_cookie("c"),
        ScriptedResponse::not_found(),
    ])
    .await;
    let (connect_timeout, request_timeout) = probe_timeouts();
    let (client, _user) = SeerrClient::login(LoginArgs {
        base_url: &base,
        method: SeerrAuthMethod::Jellyfin,
        identity: "alice",
        secret: "hunter2",
        connect_timeout,
        request_timeout,
    })
    .await
    .expect("login should succeed");

    let ratings = client
        .tv_ratings(42)
        .await
        .expect("a 404 must not be surfaced as an error");
    assert!(ratings.is_none());
}

#[tokio::test]
async fn movie_ratings_success_decodes_scores() {
    let (base, _captured) = scripted_server(vec![
        ScriptedResponse::ok_json(sample_user_json(1)).with_cookie("c"),
        ScriptedResponse::ok_json(r#"{"criticsScore":85,"audienceScore":65}"#),
    ])
    .await;
    let (connect_timeout, request_timeout) = probe_timeouts();
    let (client, _user) = SeerrClient::login(LoginArgs {
        base_url: &base,
        method: SeerrAuthMethod::Jellyfin,
        identity: "alice",
        secret: "hunter2",
        connect_timeout,
        request_timeout,
    })
    .await
    .expect("login should succeed");

    let ratings = client
        .movie_ratings(42)
        .await
        .expect("call should succeed")
        .expect("ratings should be present");
    assert_eq!(ratings.critics_score, Some(85));
    assert_eq!(ratings.audience_score, Some(65));
}

// --- Pagination param passthrough -----------------------------------------

#[tokio::test]
async fn discover_movies_sends_the_page_param() {
    let (base, captured) = scripted_server(vec![
        ScriptedResponse::ok_json(sample_user_json(1)).with_cookie("c"),
        ScriptedResponse::ok_json(r#"{"page":3,"totalPages":10,"totalResults":200,"results":[]}"#),
    ])
    .await;
    let (connect_timeout, request_timeout) = probe_timeouts();
    let (client, _user) = SeerrClient::login(LoginArgs {
        base_url: &base,
        method: SeerrAuthMethod::Jellyfin,
        identity: "alice",
        secret: "hunter2",
        connect_timeout,
        request_timeout,
    })
    .await
    .expect("login should succeed");

    let page = client
        .discover_movies(3, &BrowseFilters::default())
        .await
        .expect("discover call should succeed");
    assert_eq!(page.page, 3);

    let requests = captured.lock().unwrap_or_else(|e| e.into_inner());
    assert!(
        requests[1].path.starts_with("/api/v1/discover/movies?")
            && requests[1].path.contains("page=3"),
        "expected page=3 in the request path, got {:?}",
        requests[1].path
    );
}

#[tokio::test]
async fn discover_tv_passes_through_genre_sort_and_status_filters() {
    let (base, captured) = scripted_server(vec![
        ScriptedResponse::ok_json(sample_user_json(1)).with_cookie("c"),
        ScriptedResponse::ok_json(r#"{"page":1,"totalPages":1,"totalResults":0,"results":[]}"#),
    ])
    .await;
    let (connect_timeout, request_timeout) = probe_timeouts();
    let (client, _user) = SeerrClient::login(LoginArgs {
        base_url: &base,
        method: SeerrAuthMethod::Jellyfin,
        identity: "alice",
        secret: "hunter2",
        connect_timeout,
        request_timeout,
    })
    .await
    .expect("login should succeed");

    let filters = BrowseFilters {
        sort_by: Some("popularity.desc".to_string()),
        genre_id: Some(18),
        min_vote: Some(7.0),
        network_id: Some(213),
        status: Some("3|4".to_string()),
    };
    client
        .discover_tv(2, &filters)
        .await
        .expect("discover call should succeed");

    let requests = captured.lock().unwrap_or_else(|e| e.into_inner());
    let path = &requests[1].path;
    assert!(path.contains("page=2"), "{path}");
    assert!(path.contains("sortBy=popularity.desc"), "{path}");
    assert!(path.contains("genre=18"), "{path}");
    assert!(path.contains("voteAverageGte=7"), "{path}");
    assert!(path.contains("network=213"), "{path}");
    assert!(
        path.contains("status=3%7C4") || path.contains("status=3|4"),
        "{path}"
    );
}

#[tokio::test]
async fn my_requests_sends_take_skip_and_the_requested_by_scope() {
    let (base, captured) = scripted_server(vec![
        ScriptedResponse::ok_json(sample_user_json(1)).with_cookie("c"),
        ScriptedResponse::ok_json(r#"{"pageInfo":{"page":1,"pages":1,"results":0},"results":[]}"#),
    ])
    .await;
    let (connect_timeout, request_timeout) = probe_timeouts();
    let (client, _user) = SeerrClient::login(LoginArgs {
        base_url: &base,
        method: SeerrAuthMethod::Jellyfin,
        identity: "alice",
        secret: "hunter2",
        connect_timeout,
        request_timeout,
    })
    .await
    .expect("login should succeed");

    client
        .my_requests(20, 40)
        .await
        .expect("my_requests call should succeed");

    let requests = captured.lock().unwrap_or_else(|e| e.into_inner());
    let path = &requests[1].path;
    assert!(path.contains("take=20"), "{path}");
    assert!(path.contains("skip=40"), "{path}");
    // Privileged accounts get every user's requests back without this filter.
    assert!(path.contains("requestedBy=1"), "{path}");
}

#[tokio::test]
async fn my_requests_under_api_key_scopes_to_the_key_owner_from_auth_me() {
    let (base, captured) = scripted_server(vec![
        ScriptedResponse::ok_json(sample_user_json(7)), // /auth/me probe on login
        ScriptedResponse::ok_json(r#"{"pageInfo":{"page":1,"pages":1,"results":0},"results":[]}"#),
    ])
    .await;
    let (connect_timeout, request_timeout) = probe_timeouts();
    let (client, user) = SeerrClient::login(LoginArgs {
        base_url: &base,
        method: SeerrAuthMethod::ApiKey,
        identity: "",
        secret: "super-secret-key",
        connect_timeout,
        request_timeout,
    })
    .await
    .expect("login should succeed");
    assert_eq!(user.id, 7);

    client
        .my_requests(20, 0)
        .await
        .expect("my_requests call should succeed");

    let requests = captured.lock().unwrap_or_else(|e| e.into_inner());
    assert!(
        requests[1].path.contains("requestedBy=7"),
        "{}",
        requests[1].path
    );
}

// --- seerr_status / constructibility without a server ---------------------

#[test]
fn browse_filters_default_sends_only_the_page_param() {
    let query = SeerrClient::browse_query(1, &BrowseFilters::default(), true);
    assert_eq!(query, vec![("page", "1".to_string())]);
}

#[test]
fn query_values_are_percent_encoded_never_plus() {
    let encoded = encode_query(&[
        ("query", "harbor lights ".to_string()),
        ("page", "1".to_string()),
    ]);
    assert_eq!(encoded, "query=harbor%20lights%20&page=1");
}

#[test]
fn query_encoding_escapes_reserved_and_non_ascii() {
    let encoded = encode_query(&[("query", "a+b&c=d/é:~".to_string())]);
    assert_eq!(encoded, "query=a%2Bb%26c%3Dd%2F%C3%A9%3A~");
}

#[tokio::test]
async fn search_sends_a_space_as_percent_twenty() {
    let (base, captured) = scripted_server(vec![
        ScriptedResponse::ok_json(sample_user_json(1)),
        ScriptedResponse::ok_json(r#"{"page":1,"totalPages":1,"totalResults":0,"results":[]}"#),
    ])
    .await;
    let (connect_timeout, request_timeout) = probe_timeouts();
    let (client, _user) = SeerrClient::login(LoginArgs {
        base_url: &base,
        method: SeerrAuthMethod::ApiKey,
        identity: "",
        secret: "synthetic-key",
        connect_timeout,
        request_timeout,
    })
    .await
    .expect("api key login should succeed");

    client
        .search("harbor lights", 1)
        .await
        .expect("search should succeed");

    let requests = captured.lock().unwrap_or_else(|e| e.into_inner());
    assert_eq!(
        requests[1].path,
        "/api/v1/search?query=harbor%20lights&page=1"
    );
}
