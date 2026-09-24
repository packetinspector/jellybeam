use super::*;
use crate::object::JellybeamCore;
use crate::session;
use std::sync::Arc;

fn core_in_tempdir() -> (tempfile::TempDir, Arc<JellybeamCore>) {
    let dir = tempfile::tempdir().expect("tempdir");
    let core = JellybeamCore::new(dir.path().to_string_lossy().to_string());
    (dir, core)
}

fn seed_session(dir: &std::path::Path, server_url: &str, user_id: &str) {
    let saved = session::SessionFile {
        server_url: server_url.to_string(),
        user_id: user_id.to_string(),
        user_name: "jellybeam-user".to_string(),
        token: "tok".to_string(),
        device_id: "dev".to_string(),
        mirror_dir: "mirror".to_string(),
        server_version: None,
        server_name: None,
    };
    session::add_or_update(dir, saved).expect("seed a session file");
}

fn sample_entry(server_url: &str, user_id: &str) -> SeerrConfigEntry {
    SeerrConfigEntry {
        server_url: server_url.to_string(),
        user_id: user_id.to_string(),
        seerr_url: "http://seerr.test/api/v1".to_string(),
        method: SeerrAuthMethod::Jellyfin,
        identity: "alice".to_string(),
        secret: "hunter2".to_string(),
    }
}

// --- Availability mapping ---------------------------------------------------

#[test]
fn availability_from_status_maps_every_known_value() {
    assert_eq!(
        availability_from_status(Some(2)),
        SeerrAvailability::Pending
    );
    assert_eq!(
        availability_from_status(Some(3)),
        SeerrAvailability::Processing
    );
    assert_eq!(
        availability_from_status(Some(4)),
        SeerrAvailability::PartiallyAvailable
    );
    assert_eq!(
        availability_from_status(Some(5)),
        SeerrAvailability::Available
    );
}

#[test]
fn availability_from_status_folds_unknown_and_absent_into_not_requested() {
    assert_eq!(
        availability_from_status(None),
        SeerrAvailability::NotRequested,
        "absent mediaInfo/status"
    );
    assert_eq!(
        availability_from_status(Some(1)),
        SeerrAvailability::NotRequested,
        "explicit UNKNOWN"
    );
    assert_eq!(
        availability_from_status(Some(6)),
        SeerrAvailability::NotRequested,
        "DELETED"
    );
    assert_eq!(
        availability_from_status(Some(99)),
        SeerrAvailability::NotRequested,
        "a future/unrecognized value"
    );
}

// --- Season requestability matrix -------------------------------------------

#[test]
fn season_requestable_matrix() {
    // Not yet available, no request at all -> requestable.
    assert!(season_requestable(SeerrAvailability::NotRequested, None));

    // Already at least pending-availability -> never requestable.
    for availability in [
        SeerrAvailability::Pending,
        SeerrAvailability::Processing,
        SeerrAvailability::PartiallyAvailable,
        SeerrAvailability::Available,
    ] {
        assert!(
            !season_requestable(availability, None),
            "{availability:?} must not be requestable"
        );
    }

    // Already covered by a pending/approved request -> not requestable.
    assert!(!season_requestable(
        SeerrAvailability::NotRequested,
        Some(SeerrRequestStatus::Pending)
    ));
    assert!(!season_requestable(
        SeerrAvailability::NotRequested,
        Some(SeerrRequestStatus::Approved)
    ));

    // A declined request does not block re-requesting.
    assert!(season_requestable(
        SeerrAvailability::NotRequested,
        Some(SeerrRequestStatus::Declined)
    ));
}

fn season(number: i32, status: Option<i64>) -> seerr_api::Season {
    seerr_api::Season {
        season_number: Some(number),
        name: None,
        episode_count: Some(10),
        status,
    }
}

fn media_request(is4k: bool, seasons: Vec<seerr_api::Season>) -> seerr_api::MediaRequest {
    seerr_api::MediaRequest {
        id: 1,
        status: 0,
        is4k,
        seasons,
        requested_by: None,
        media: None,
        kind: None,
    }
}

#[test]
fn build_season_statuses_marks_a_pending_request_season_not_requestable() {
    let seasons = vec![season(1, None), season(2, None)];
    let media_info = seerr_api::MediaInfo {
        requests: vec![media_request(
            false,
            vec![seerr_api::Season {
                status: Some(1), // PENDING (request-level, per MediaRequest.status semantics)
                ..season(1, None)
            }],
        )],
        ..Default::default()
    };
    let result = build_season_statuses(&seasons, Some(&media_info));
    assert_eq!(result.len(), 2);
    assert!(!result[0].requestable, "season 1 has a pending request");
    assert!(result[1].requestable, "season 2 has no request at all");
}

#[test]
fn build_season_statuses_ignores_4k_requests_for_the_sd_list() {
    let seasons = vec![season(1, None)];
    let media_info = seerr_api::MediaInfo {
        requests: vec![media_request(
            true, // a 4K request
            vec![seerr_api::Season {
                status: Some(1),
                ..season(1, None)
            }],
        )],
        ..Default::default()
    };
    let result = build_season_statuses(&seasons, Some(&media_info));
    assert!(
        result[0].requestable,
        "a 4K request must not block SD requestability"
    );
}

#[test]
fn build_season_statuses_merges_the_higher_ranked_availability() {
    // mediaInfo.seasons AVAILABLE must win over the title's own NotRequested.
    let seasons = vec![season(1, None)];
    let media_info = seerr_api::MediaInfo {
        seasons: vec![season(1, Some(5))],
        ..Default::default()
    };
    let result = build_season_statuses(&seasons, Some(&media_info));
    assert_eq!(result[0].availability, SeerrAvailability::Available);
    assert!(!result[0].requestable);
}

// --- POST-vs-PUT decision ---------------------------------------------------

fn requested_by(id: i64) -> seerr_api::RequestUser {
    seerr_api::RequestUser { id, username: None }
}

#[test]
fn decide_request_action_creates_when_no_existing_request() {
    let action = decide_request_action(&[], false, 42);
    assert!(matches!(action, RequestAction::Create));
}

#[test]
fn decide_request_action_updates_the_callers_own_pending_request() {
    let existing = vec![seerr_api::MediaRequest {
        id: 7,
        status: 1, // pending
        is4k: false,
        seasons: Vec::new(),
        requested_by: Some(requested_by(42)),
        media: None,
        kind: None,
    }];
    let action = decide_request_action(&existing, false, 42);
    assert!(matches!(action, RequestAction::Update { request_id: 7 }));
}

#[test]
fn decide_request_action_ignores_another_users_pending_request() {
    let existing = vec![seerr_api::MediaRequest {
        id: 7,
        status: 1,
        is4k: false,
        seasons: Vec::new(),
        requested_by: Some(requested_by(999)),
        media: None,
        kind: None,
    }];
    let action = decide_request_action(&existing, false, 42);
    assert!(matches!(action, RequestAction::Create));
}

#[test]
fn decide_request_action_ignores_a_pending_request_at_the_other_4k_flavor() {
    let existing = vec![seerr_api::MediaRequest {
        id: 7,
        status: 1,
        is4k: true, // caller is submitting an SD (is4k: false) request
        seasons: Vec::new(),
        requested_by: Some(requested_by(42)),
        media: None,
        kind: None,
    }];
    let action = decide_request_action(&existing, false, 42);
    assert!(matches!(action, RequestAction::Create));
}

#[test]
fn decide_request_action_ignores_a_declined_request() {
    let existing = vec![seerr_api::MediaRequest {
        id: 7,
        status: 3, // declined
        is4k: false,
        seasons: Vec::new(),
        requested_by: Some(requested_by(42)),
        media: None,
        kind: None,
    }];
    let action = decide_request_action(&existing, false, 42);
    assert!(
        matches!(action, RequestAction::Create),
        "a declined request must not be updated -- a fresh request should be created"
    );
}

// --- Request status mapping --------------------------------------------------

#[test]
fn request_status_from_int_is_strict() {
    assert_eq!(
        request_status_from_int(1),
        Some(SeerrRequestStatus::Pending)
    );
    assert_eq!(
        request_status_from_int(2),
        Some(SeerrRequestStatus::Approved)
    );
    assert_eq!(
        request_status_from_int(3),
        Some(SeerrRequestStatus::Declined)
    );
    assert_eq!(request_status_from_int(4), None, "FAILURE has no slot");
    assert_eq!(request_status_from_int(5), None, "COMPLETED has no slot");
    assert_eq!(request_status_from_int(0), None);
}

#[test]
fn request_status_for_display_folds_completed_and_failure() {
    assert_eq!(
        request_status_for_display(5),
        SeerrRequestStatus::Approved,
        "COMPLETED displays as Approved"
    );
    assert_eq!(
        request_status_for_display(4),
        SeerrRequestStatus::Declined,
        "FAILURE displays as Declined"
    );
    assert_eq!(request_status_for_display(1), SeerrRequestStatus::Pending);
    assert_eq!(request_status_for_display(2), SeerrRequestStatus::Approved);
    assert_eq!(request_status_for_display(3), SeerrRequestStatus::Declined);
}

// --- jellyfin_item_id_from / image URLs -------------------------------------

#[test]
fn jellyfin_item_id_from_prefers_the_4k_id() {
    let info = seerr_api::MediaInfo {
        jellyfin_media_id: Some("sd-id".to_string()),
        jellyfin_media_id4k: Some("4k-id".to_string()),
        ..Default::default()
    };
    assert_eq!(
        jellyfin_item_id_from(Some(&info)),
        Some("4k-id".to_string())
    );
}

#[test]
fn jellyfin_item_id_from_falls_back_to_sd_id() {
    let info = seerr_api::MediaInfo {
        jellyfin_media_id: Some("sd-id".to_string()),
        ..Default::default()
    };
    assert_eq!(
        jellyfin_item_id_from(Some(&info)),
        Some("sd-id".to_string())
    );
}

#[test]
fn jellyfin_item_id_from_is_none_without_mediainfo() {
    assert_eq!(jellyfin_item_id_from(None), None);
}

#[test]
fn build_image_url_uses_tmdb_by_default() {
    let images = ImageContext {
        seerr_url: "https://seerr.test",
        cache_images: false,
    };
    assert_eq!(
        poster_url(Some("/abc.jpg"), images),
        Some("https://image.tmdb.org/t/p/w500/abc.jpg".to_string())
    );
    assert_eq!(
        backdrop_url(Some("/abc.jpg"), images),
        Some("https://image.tmdb.org/t/p/w1920_and_h1080_multi_faces/abc.jpg".to_string())
    );
}

#[test]
fn build_image_url_swaps_base_when_cache_images_is_set() {
    let images = ImageContext {
        seerr_url: "https://seerr.test",
        cache_images: true,
    };
    assert_eq!(
        poster_url(Some("/abc.jpg"), images),
        Some("https://seerr.test/imageproxy/tmdb/t/p/w500/abc.jpg".to_string())
    );
}

#[test]
fn build_image_url_is_none_without_a_path() {
    let images = ImageContext {
        seerr_url: "https://seerr.test",
        cache_images: false,
    };
    assert_eq!(poster_url(None, images), None);
    assert_eq!(poster_url(Some(""), images), None);
}

// --- Config store: roundtrip, tolerant load, per-account keying ------------

#[test]
fn seerr_config_roundtrips_through_disk() {
    let dir = tempfile::tempdir().expect("tempdir");
    let mut config = SeerrConfigFile::default();
    config.upsert(sample_entry("http://jf.test", "user-1"));
    save_config(dir.path(), &config).expect("save");

    let loaded = load_config(dir.path());
    assert_eq!(loaded, config);
}

#[test]
fn seerr_config_load_returns_empty_when_no_file_exists() {
    let dir = tempfile::tempdir().expect("tempdir");
    let loaded = load_config(dir.path());
    assert!(loaded.entries.is_empty());
}

#[test]
fn seerr_config_load_tolerates_corrupt_json() {
    let dir = tempfile::tempdir().expect("tempdir");
    std::fs::write(config_path(dir.path()), b"not json").expect("write garbage");
    let loaded = load_config(dir.path());
    assert!(loaded.entries.is_empty());
}

#[test]
fn seerr_config_load_tolerates_unknown_fields_and_missing_optional_ones() {
    let dir = tempfile::tempdir().expect("tempdir");
    let raw = serde_json::json!({
        "entries": [
            {
                "server_url": "http://jf.test",
                "user_id": "user-1",
                "seerr_url": "http://seerr.test/api/v1",
                "bogus_future_field": {"anything": "goes here"},
            }
        ]
    });
    std::fs::write(
        config_path(dir.path()),
        serde_json::to_vec(&raw).expect("serialize"),
    )
    .expect("write seerr.json");

    let loaded = load_config(dir.path());
    assert_eq!(loaded.entries.len(), 1);
    assert_eq!(loaded.entries[0].server_url, "http://jf.test");
    assert_eq!(loaded.entries[0].seerr_url, "http://seerr.test/api/v1");
    // method/identity/secret were all omitted -- tolerant defaults.
    assert_eq!(loaded.entries[0].method, SeerrAuthMethod::Jellyfin);
    assert_eq!(loaded.entries[0].identity, "");
    assert_eq!(loaded.entries[0].secret, "");
}

#[test]
fn seerr_config_keys_two_accounts_independently() {
    let mut config = SeerrConfigFile::default();
    let mut entry_a = sample_entry("http://jf-a.test", "user-a");
    entry_a.seerr_url = "http://seerr-a.test/api/v1".to_string();
    let mut entry_b = sample_entry("http://jf-b.test", "user-b");
    entry_b.seerr_url = "http://seerr-b.test/api/v1".to_string();
    config.upsert(entry_a.clone());
    config.upsert(entry_b.clone());

    assert_eq!(config.entries.len(), 2);
    assert_eq!(config.find("http://jf-a.test", "user-a"), Some(&entry_a));
    assert_eq!(config.find("http://jf-b.test", "user-b"), Some(&entry_b));
    assert!(config.find("http://jf-a.test", "user-b").is_none());
}

#[test]
fn seerr_config_upsert_replaces_only_the_matching_account() {
    let mut config = SeerrConfigFile::default();
    config.upsert(sample_entry("http://jf-a.test", "user-a"));
    config.upsert(sample_entry("http://jf-b.test", "user-b"));

    let mut updated_a = sample_entry("http://jf-a.test", "user-a");
    updated_a.secret = "new-secret".to_string();
    config.upsert(updated_a);

    assert_eq!(config.entries.len(), 2, "must not duplicate");
    assert_eq!(
        config
            .find("http://jf-a.test", "user-a")
            .expect("entry a exists")
            .secret,
        "new-secret"
    );
    assert_eq!(
        config
            .find("http://jf-b.test", "user-b")
            .expect("entry b exists")
            .secret,
        "hunter2"
    );
}

#[test]
fn seerr_config_remove_drops_only_the_named_account() {
    let mut config = SeerrConfigFile::default();
    config.upsert(sample_entry("http://jf-a.test", "user-a"));
    config.upsert(sample_entry("http://jf-b.test", "user-b"));

    config.remove("http://jf-a.test", "user-a");

    assert_eq!(config.entries.len(), 1);
    assert!(config.find("http://jf-a.test", "user-a").is_none());
    assert!(config.find("http://jf-b.test", "user-b").is_some());
}

// --- seerr_status: local-file-only, zero network ----------------------------

#[test]
fn seerr_status_with_nobody_signed_in_is_not_configured() {
    let (_dir, core) = core_in_tempdir();
    let status = core.seerr_status();
    assert!(!status.configured);
    assert!(status.seerr_url.is_none());
}

#[test]
fn seerr_status_signed_in_but_no_seerr_config_is_not_configured() {
    let (dir, core) = core_in_tempdir();
    seed_session(dir.path(), "http://jf.test", "user-1");
    let status = core.seerr_status();
    assert!(!status.configured);
}

#[test]
fn seerr_status_reads_the_saved_config_for_the_active_account_without_a_network_call() {
    let (dir, core) = core_in_tempdir();
    seed_session(dir.path(), "http://jf.test", "user-1");
    let mut config = SeerrConfigFile::default();
    config.upsert(sample_entry("http://jf.test", "user-1"));
    save_config(dir.path(), &config).expect("save seerr config");

    // Returning instantly with the saved values proves no network I/O ran.
    let status = core.seerr_status();
    assert!(status.configured);
    assert_eq!(
        status.seerr_url.as_deref(),
        Some("http://seerr.test/api/v1")
    );
    assert_eq!(status.method, Some(SeerrAuthMethod::Jellyfin));
    assert_eq!(status.identity.as_deref(), Some("alice"));
    assert!(
        status.app_title.is_none(),
        "no live handle has been built yet, so app_title has nothing cached"
    );
}

#[test]
fn seerr_status_keys_on_the_active_jellyfin_account_not_just_any_saved_seerr_entry() {
    let (dir, core) = core_in_tempdir();
    seed_session(dir.path(), "http://jf-a.test", "user-a");
    let mut config = SeerrConfigFile::default();
    // Only user-b has a saved Seerr connection; the active account is user-a.
    config.upsert(sample_entry("http://jf-b.test", "user-b"));
    save_config(dir.path(), &config).expect("save seerr config");

    assert!(!core.seerr_status().configured);
}

// --- Home-cache invalidation on submit/cancel -------------------------------

/// Minimal one-shot-per-response mock server: answers with the next queued `(status, body)` pair.
async fn scripted_json_server(responses: Vec<(u16, String)>) -> String {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::net::TcpListener;

    let listener = TcpListener::bind("127.0.0.1:0")
        .await
        .expect("bind local listener");
    let addr = listener.local_addr().expect("local_addr");

    tokio::spawn(async move {
        for (status, body) in responses {
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
                if let Some(pos) = buf.windows(4).position(|w| w == b"\r\n\r\n") {
                    break Some(pos + 4);
                }
            };
            let Some(header_end) = header_end else {
                continue;
            };

            let head = String::from_utf8_lossy(&buf[..header_end]).to_string();
            let mut content_length = 0usize;
            for line in head.split("\r\n").skip(1) {
                if let Some((name, value)) = line.split_once(':') {
                    if name.trim().eq_ignore_ascii_case("content-length") {
                        content_length = value.trim().parse().unwrap_or(0);
                    }
                }
            }
            let mut body_read = buf.len() - header_end;
            while body_read < content_length {
                let n = stream.read(&mut chunk).await.unwrap_or(0);
                if n == 0 {
                    break;
                }
                body_read += n;
            }

            let status_line = match status {
                200 => "200 OK",
                201 => "201 Created",
                401 => "401 Unauthorized",
                403 => "403 Forbidden",
                404 => "404 Not Found",
                _ => "500 Internal Server Error",
            };
            let response = format!(
                "HTTP/1.1 {status_line}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                body.len(),
                body
            );
            let _ = stream.write_all(response.as_bytes()).await;
            let _ = stream.shutdown().await;
        }
    });

    format!("http://{addr}")
}

#[test]
fn a_seerr_handle_completed_after_an_account_switch_is_not_cached() {
    let (dir, core) = core_in_tempdir();
    seed_session(dir.path(), "http://jellyfin.test", "user-a");

    // API_KEY auth: a single `GET /auth/me` builds a client for the test.
    let base = core.runtime().block_on(scripted_json_server(vec![(
        200,
        r#"{"id":1,"username":"jellybeam-test-user"}"#.to_string(),
    )]));
    let (client, user) = core
        .runtime()
        .block_on(seerr_api::SeerrClient::login(seerr_api::LoginArgs {
            base_url: &base,
            method: seerr_api::SeerrAuthMethod::ApiKey,
            identity: "",
            secret: "test-key",
            connect_timeout: Duration::from_secs(2),
            request_timeout: Duration::from_secs(6),
        }))
        .expect("login against the mock server should succeed");
    let handle = SeerrHandle::new(client, user.id, &base, seerr_api::PublicSettings::default());

    // The connect began while user-b was active and lands after the switch to user-a.
    let stale = ("http://jellyfin.test".to_string(), "user-b".to_string());
    assert!(!core.cache_seerr_handle_if_active(&stale, handle.clone()));
    assert!(
        core.lock_state().seerr.is_none(),
        "a stale completion must not populate the slot"
    );

    let current = ("http://jellyfin.test".to_string(), "user-a".to_string());
    assert!(core.cache_seerr_handle_if_active(&current, handle));
    assert!(core.lock_state().seerr.is_some());
}

fn mock_handle(core: &JellybeamCore) -> SeerrHandle {
    // API_KEY auth: a single `GET /auth/me` builds a client for the test.
    let base = core.runtime().block_on(scripted_json_server(vec![(
        200,
        r#"{"id":1,"username":"jellybeam-test-user"}"#.to_string(),
    )]));
    let (client, user) = core
        .runtime()
        .block_on(seerr_api::SeerrClient::login(seerr_api::LoginArgs {
            base_url: &base,
            method: seerr_api::SeerrAuthMethod::ApiKey,
            identity: "",
            secret: "test-key",
            connect_timeout: Duration::from_secs(2),
            request_timeout: Duration::from_secs(6),
        }))
        .expect("login against the mock server should succeed");
    SeerrHandle::new(client, user.id, &base, seerr_api::PublicSettings::default())
}

#[test]
fn a_rejected_login_is_a_credentials_error_not_a_raw_status() {
    let (dir, core) = core_in_tempdir();
    seed_session(dir.path(), "http://jellyfin.test", "user-a");

    let base = core.runtime().block_on(scripted_json_server(vec![(
        403,
        r#"{"message":"Access denied."}"#.to_string(),
    )]));
    let err = core
        .seerr_connect(
            base.clone(),
            SeerrAuthMethod::Local,
            "local-user".to_string(),
            "hunter2".to_string(),
        )
        .expect_err("403 must not connect");
    assert!(
        matches!(
            err,
            CoreError::SeerrInvalidCredentials {
                method: SeerrAuthMethod::Local
            }
        ),
        "{err:?}"
    );
    assert!(!core.seerr_status().configured);

    let base = core.runtime().block_on(scripted_json_server(vec![(
        401,
        r#"{"message":"Unauthorized"}"#.to_string(),
    )]));
    let err = core
        .seerr_connect(
            base,
            SeerrAuthMethod::ApiKey,
            String::new(),
            "bad-key".to_string(),
        )
        .expect_err("401 must not connect");
    assert!(
        matches!(
            err,
            CoreError::SeerrInvalidCredentials {
                method: SeerrAuthMethod::ApiKey
            }
        ),
        "{err:?}"
    );
}

fn connect_error(core: &JellybeamCore, base: String, method: SeerrAuthMethod) -> CoreError {
    let err = core
        .seerr_connect(
            base,
            method,
            "someone@example.test".to_string(),
            "hunter2".to_string(),
        )
        .expect_err("a refused login must not connect");
    assert!(!core.seerr_status().configured);
    err
}

/// docs/14 "Auth": Seerr's own error bodies decide the copy; only a non-Seerr body keeps probing.
#[test]
fn seerr_error_bodies_classify_the_refusal_and_stop_probing() {
    let (dir, core) = core_in_tempdir();
    seed_session(dir.path(), "http://jellyfin.test", "user-a");
    let serve = |status: u16, body: &str| {
        core.runtime()
            .block_on(scripted_json_server(vec![(status, body.to_string())]))
    };

    // Jellyseerr 2.x against Jellyfin 12: Seerr cannot sign in to its own media server.
    let base = serve(404, r#"{"message":"INVALID_URL"}"#);
    assert!(matches!(
        connect_error(&core, base, SeerrAuthMethod::Jellyfin),
        CoreError::SeerrSignInRefused {
            reason: SeerrRefusal::MediaServerSignIn,
            ..
        }
    ));

    // A Jellyfin user Seerr has not imported while new sign-ins are off.
    let base = serve(403, r#"{"message":"Access denied."}"#);
    assert!(matches!(
        connect_error(&core, base, SeerrAuthMethod::Jellyfin),
        CoreError::SeerrSignInRefused {
            reason: SeerrRefusal::NewUsersBlocked,
            ..
        }
    ));

    // The same body under LOCAL is a wrong email/password.
    let base = serve(403, r#"{"message":"Access denied."}"#);
    assert!(matches!(
        connect_error(&core, base, SeerrAuthMethod::Local),
        CoreError::SeerrInvalidCredentials {
            method: SeerrAuthMethod::Local
        }
    ));

    // Seerr with password sign-in switched off answers 500 with an `error` field.
    let base = serve(500, r#"{"error":"Password sign-in is disabled."}"#);
    assert!(matches!(
        connect_error(&core, base, SeerrAuthMethod::Local),
        CoreError::SeerrSignInRefused {
            reason: SeerrRefusal::MethodDisabled,
            ..
        }
    ));

    // Seerr's INVALID_CREDENTIALS code is wrong credentials whatever the status.
    let base = serve(500, r#"{"message":"INVALID_CREDENTIALS"}"#);
    assert!(matches!(
        connect_error(&core, base, SeerrAuthMethod::Jellyfin),
        CoreError::SeerrInvalidCredentials {
            method: SeerrAuthMethod::Jellyfin
        }
    ));

    // A 404 that is not Seerr's shape is "not the server": a plain API error, probing continues.
    let base = serve(404, "<html>not found</html>");
    assert!(matches!(
        connect_error(&core, base, SeerrAuthMethod::Local),
        CoreError::Api { .. }
    ));
}

#[test]
fn seerr_config_save_replaces_the_file_atomically() {
    let dir = tempfile::tempdir().expect("tempdir");
    let mut config = SeerrConfigFile::default();
    config.upsert(sample_entry("http://jf.test", "user-1"));
    save_config(dir.path(), &config).expect("save");
    save_config(dir.path(), &config).expect("save again");
    assert_eq!(std::fs::read_dir(dir.path()).expect("entries").count(), 1);
    assert_eq!(load_config(dir.path()), config);
}

#[test]
fn a_connect_that_lands_after_a_disconnect_is_dropped() {
    let (dir, core) = core_in_tempdir();
    seed_session(dir.path(), "http://jellyfin.test", "user-a");
    let identity = ("http://jellyfin.test".to_string(), "user-a".to_string());
    let mut existing = SeerrConfigFile::default();
    existing.upsert(sample_entry("http://jellyfin.test", "user-a"));
    save_config(dir.path(), &existing).expect("seed config");

    let intent = core.seerr_config().begin();
    core.seerr_disconnect();
    let result = core.commit_seerr_connect(
        intent,
        &identity,
        sample_entry("http://jellyfin.test", "user-a"),
        mock_handle(&core),
    );

    assert!(matches!(result, Err(CoreError::Api { .. })));
    assert!(load_config(dir.path()).entries.is_empty());
    assert!(core.lock_state().seerr.is_none());
    assert!(!core.seerr_status().configured);
}

#[test]
fn the_later_of_two_overlapping_connects_wins() {
    let (dir, core) = core_in_tempdir();
    seed_session(dir.path(), "http://jellyfin.test", "user-a");
    let identity = ("http://jellyfin.test".to_string(), "user-a".to_string());
    let first_entry = SeerrConfigEntry {
        identity: "first".to_string(),
        ..sample_entry("http://jellyfin.test", "user-a")
    };
    let second_entry = SeerrConfigEntry {
        identity: "second".to_string(),
        ..sample_entry("http://jellyfin.test", "user-a")
    };

    let first = core.seerr_config().begin();
    let second = core.seerr_config().begin();
    core.commit_seerr_connect(second, &identity, second_entry.clone(), mock_handle(&core))
        .expect("the later connect commits");
    let stale = core.commit_seerr_connect(first, &identity, first_entry, mock_handle(&core));

    assert!(matches!(stale, Err(CoreError::Api { .. })));
    assert_eq!(
        load_config(dir.path()).find("http://jellyfin.test", "user-a"),
        Some(&second_entry)
    );
    assert_eq!(core.seerr_status().identity.as_deref(), Some("second"));
}

#[test]
fn a_lazy_rebuild_that_lands_after_a_disconnect_is_not_cached() {
    let (dir, core) = core_in_tempdir();
    seed_session(dir.path(), "http://jellyfin.test", "user-a");
    let identity = ("http://jellyfin.test".to_string(), "user-a".to_string());

    let intent = core.seerr_config().current();
    core.seerr_disconnect();
    let result = core.cache_rebuilt_seerr_handle(intent, &identity, mock_handle(&core));

    assert!(matches!(result, Err(CoreError::SeerrNotConfigured)));
    assert!(core.lock_state().seerr.is_none());

    let intent = core.seerr_config().current();
    core.cache_rebuilt_seerr_handle(intent, &identity, mock_handle(&core))
        .expect("an undisturbed rebuild is cached");
    assert!(core.lock_state().seerr.is_some());
}

#[test]
fn a_lazy_rebuild_that_lands_after_an_account_switch_is_not_cached() {
    let (dir, core) = core_in_tempdir();
    seed_session(dir.path(), "http://jellyfin.test", "user-a");
    let captured = ("http://jellyfin.test".to_string(), "user-a".to_string());

    let intent = core.seerr_config().current();
    seed_session(dir.path(), "http://jellyfin.test", "user-b"); // becomes active

    let result = core.cache_rebuilt_seerr_handle(intent, &captured, mock_handle(&core));

    assert!(
        matches!(result, Err(CoreError::SeerrNotConfigured)),
        "a rebuild for the account active when it began must not answer with the new account's handle"
    );
    assert!(core.lock_state().seerr.is_none());
}

#[test]
fn seerr_submit_request_invalidates_the_home_cache_on_success() {
    let (_dir, core) = core_in_tempdir();

    // API_KEY auth: `GET /auth/me`, `GET /movie/{id}` (no mediaInfo), `POST /request`.
    let base = core.runtime().block_on(scripted_json_server(vec![
        (
            200,
            r#"{"id":1,"username":"jellybeam-test-user"}"#.to_string(),
        ),
        (200, r#"{"id":42,"title":"Sample Movie"}"#.to_string()),
        (201, r#"{"id":7,"status":1,"is4k":false}"#.to_string()),
    ]));

    let (client, user) = core
        .runtime()
        .block_on(seerr_api::SeerrClient::login(seerr_api::LoginArgs {
            base_url: &base,
            method: seerr_api::SeerrAuthMethod::ApiKey,
            identity: "",
            secret: "test-key",
            connect_timeout: Duration::from_secs(2),
            request_timeout: Duration::from_secs(6),
        }))
        .expect("login against the mock server should succeed");
    assert_eq!(user.id, 1);

    let handle = SeerrHandle::new(client, user.id, &base, seerr_api::PublicSettings::default());
    handle.cache_home(SeerrHome {
        rows: vec![SeerrHomeRow {
            id: "trending".to_string(),
            title: "Trending".to_string(),
            cards: Vec::new(),
        }],
    });
    assert!(
        handle.cached_home().is_some(),
        "precondition: a home snapshot is cached before the submit"
    );

    core.lock_state().seerr = Some(handle.clone());

    core.seerr_submit_request(SeerrRequestInput {
        media_type: SeerrMediaType::Movie,
        tmdb_id: 42,
        is_4k: false,
        seasons: Vec::new(),
        server_id: None,
        profile_id: None,
        root_folder: None,
    })
    .expect("submit against the mock server should succeed");

    assert!(
        handle.cached_home().is_none(),
        "a successful submit must invalidate the now-stale cached home snapshot"
    );
}

#[test]
fn seerr_cancel_request_invalidates_the_home_cache_on_success() {
    let (_dir, core) = core_in_tempdir();

    let base = core.runtime().block_on(scripted_json_server(vec![
        (
            200,
            r#"{"id":1,"username":"jellybeam-test-user"}"#.to_string(),
        ),
        (200, r#"{"id":7,"status":1,"is4k":false}"#.to_string()), // DELETE /request/7
    ]));

    let (client, user) = core
        .runtime()
        .block_on(seerr_api::SeerrClient::login(seerr_api::LoginArgs {
            base_url: &base,
            method: seerr_api::SeerrAuthMethod::ApiKey,
            identity: "",
            secret: "test-key",
            connect_timeout: Duration::from_secs(2),
            request_timeout: Duration::from_secs(6),
        }))
        .expect("login against the mock server should succeed");

    let handle = SeerrHandle::new(client, user.id, &base, seerr_api::PublicSettings::default());
    handle.cache_home(SeerrHome { rows: Vec::new() });
    core.lock_state().seerr = Some(handle.clone());

    core.seerr_cancel_request(7)
        .expect("cancel against the mock server should succeed");

    assert!(
        handle.cached_home().is_none(),
        "a successful cancel must invalidate the now-stale cached home snapshot"
    );
}
