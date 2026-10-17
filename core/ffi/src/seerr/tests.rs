use super::*;
use crate::object::JellybeamCore;
use crate::session;
use std::sync::Arc;

/// A handle with every `/settings/public` toggle off, so no test request is spent on it.
fn settled_handle(client: seerr_api::SeerrClient, user_id: i64, base: &str) -> SeerrHandle {
    SeerrHandle::new(client, user_id, base, seerr_api::PublicSettings::default())
}

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

/// The installed account's cached Seerr handle; `None` when no account is installed.
fn cached_seerr(core: &JellybeamCore) -> Option<SeerrHandle> {
    let state = core.lock_state();
    state
        .account
        .installed()
        .and_then(|i| i.caches.seerr.clone())
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
    core.restore_session().expect("the seeded account restores");
    let status = core.seerr_status();
    assert!(!status.configured);
}

#[test]
fn seerr_status_reads_the_saved_config_for_the_active_account_without_a_network_call() {
    let (dir, core) = core_in_tempdir();
    seed_session(dir.path(), "http://jf.test", "user-1");
    core.restore_session().expect("the seeded account restores");
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
    core.restore_session().expect("the seeded account restores");
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
    core.restore_session().expect("account A restores");

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
    let handle = settled_handle(client, user.id, &base);

    // The connect began while user-b was active and lands after the switch to user-a.
    let stale = core.account_epoch().wrapping_add(1);
    assert!(!core.cache_seerr_handle_if_active(stale, handle.clone()));
    assert!(
        cached_seerr(&core).is_none(),
        "a stale completion must not populate the slot"
    );

    let current = core.account_epoch();
    assert!(core.cache_seerr_handle_if_active(current, handle));
    assert!(cached_seerr(&core).is_some());
}

#[test]
fn a_handle_built_for_account_a_is_not_cached_after_switch_session_to_b() {
    let (dir, core) = core_in_tempdir();
    seed_session(dir.path(), "http://jellyfin-b.test", "user-b");
    seed_session(dir.path(), "http://jellyfin-a.test", "user-a");
    core.restore_session().expect("account A restores");
    let captured = core.account_epoch();
    let handle = mock_handle(&core);

    core.switch_session(0).expect("switch to account B");

    assert!(!core.cache_seerr_handle_if_active(captured, handle));
    assert!(
        cached_seerr(&core).is_none(),
        "account A's handle must not be readable under account B"
    );
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
    settled_handle(client, user.id, &base)
}

#[test]
fn a_rejected_login_is_a_credentials_error_not_a_raw_status() {
    let (dir, core) = core_in_tempdir();
    seed_session(dir.path(), "http://jellyfin.test", "user-a");
    core.restore_session().expect("the seeded account restores");

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
    core.restore_session().expect("the seeded account restores");
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
    core.restore_session().expect("account A restores");
    let epoch = core.account_epoch();
    let mut existing = SeerrConfigFile::default();
    existing.upsert(sample_entry("http://jellyfin.test", "user-a"));
    save_config(dir.path(), &existing).expect("seed config");

    let intent = core.seerr_config().begin();
    core.seerr_disconnect();
    let result = core.commit_seerr_connect(
        intent,
        epoch,
        sample_entry("http://jellyfin.test", "user-a"),
        mock_handle(&core),
    );

    assert!(matches!(result, Err(CoreError::Api { .. })));
    assert!(load_config(dir.path()).entries.is_empty());
    assert!(cached_seerr(&core).is_none());
    assert!(!core.seerr_status().configured);
}

#[test]
fn the_later_of_two_overlapping_connects_wins() {
    let (dir, core) = core_in_tempdir();
    seed_session(dir.path(), "http://jellyfin.test", "user-a");
    core.restore_session().expect("account A restores");
    let epoch = core.account_epoch();
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
    core.commit_seerr_connect(second, epoch, second_entry.clone(), mock_handle(&core))
        .expect("the later connect commits");
    let stale = core.commit_seerr_connect(first, epoch, first_entry, mock_handle(&core));

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
    core.restore_session().expect("account A restores");
    let epoch = core.account_epoch();

    let intent = core.seerr_config().current();
    core.seerr_disconnect();
    let result = core.cache_rebuilt_seerr_handle(intent, epoch, mock_handle(&core));

    assert!(matches!(result, Err(CoreError::SeerrNotConfigured)));
    assert!(cached_seerr(&core).is_none());

    let intent = core.seerr_config().current();
    core.cache_rebuilt_seerr_handle(intent, epoch, mock_handle(&core))
        .expect("an undisturbed rebuild is cached");
    assert!(cached_seerr(&core).is_some());
}

#[test]
fn a_lazy_rebuild_that_lands_after_an_account_switch_is_not_cached() {
    let (dir, core) = core_in_tempdir();
    seed_session(dir.path(), "http://jellyfin.test", "user-a");
    core.restore_session().expect("account A restores");
    let captured = core.account_epoch();

    let intent = core.seerr_config().current();
    seed_session(dir.path(), "http://jellyfin.test", "user-b");
    core.switch_session(1).expect("account B is installed");

    let result = core.cache_rebuilt_seerr_handle(intent, captured, mock_handle(&core));

    assert!(
        matches!(result, Err(CoreError::SeerrNotConfigured)),
        "a rebuild for the account active when it began must not answer with the new account's handle"
    );
    assert!(cached_seerr(&core).is_none());
}

fn cached_home_sample() -> SeerrHome {
    SeerrHome {
        rows: vec![SeerrHomeRow {
            id: "trending".to_string(),
            title: "Trending".to_string(),
            cards: Vec::new(),
        }],
    }
}

#[test]
fn seerr_submit_request_detail_invalidates_the_home_cache_on_success() {
    let (_dir, core) = core_in_tempdir();
    let reads = Arc::new(std::sync::atomic::AtomicUsize::new(0));
    let (base, _log) = core
        .runtime()
        .block_on(routed_seerr_server(movie_detail_routes(reads, 1)));
    let handle = install_api_key_handle(&core, &base);
    handle.cache_home(handle.home_generation(), cached_home_sample());
    assert!(
        handle.cached_home().is_some(),
        "precondition: a home snapshot is cached before the submit"
    );

    core.seerr_submit_request_detail(movie_input(42))
        .expect("submit against the mock server should succeed");

    assert!(
        handle.cached_home().is_none(),
        "a successful submit must invalidate the now-stale cached home snapshot"
    );
}

#[test]
fn seerr_cancel_request_detail_invalidates_the_home_cache_on_success() {
    let (_dir, core) = core_in_tempdir();
    let reads = Arc::new(std::sync::atomic::AtomicUsize::new(0));
    let (base, _log) = core
        .runtime()
        .block_on(routed_seerr_server(movie_detail_routes(reads, usize::MAX)));
    let handle = install_api_key_handle(&core, &base);
    handle.cache_home(handle.home_generation(), cached_home_sample());

    core.seerr_cancel_request_detail(SeerrMediaType::Movie, 42, 7)
        .expect("cancel against the mock server should succeed");

    assert!(
        handle.cached_home().is_none(),
        "a successful cancel must invalidate the now-stale cached home snapshot"
    );
}

// --- Seerr connection follows the installed account, not the file ------------

/// Mock Seerr that answers every request with user `user_id` and counts them; a request waits on
/// `gate` (when given) until the test adds permits, so a rebuild can be held mid-flight.
async fn counting_seerr_server(
    user_id: i64,
    gate: Option<Arc<tokio::sync::Semaphore>>,
) -> (String, Arc<std::sync::atomic::AtomicUsize>) {
    use std::sync::atomic::Ordering;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    let listener = tokio::net::TcpListener::bind("127.0.0.1:0")
        .await
        .expect("bind local listener");
    let addr = listener.local_addr().expect("local_addr");
    let hits = Arc::new(std::sync::atomic::AtomicUsize::new(0));
    let counter = hits.clone();

    tokio::spawn(async move {
        while let Ok((mut stream, _)) = listener.accept().await {
            let gate = gate.clone();
            let counter = counter.clone();
            tokio::spawn(async move {
                let mut buf = Vec::new();
                let mut chunk = [0u8; 4096];
                while !buf.windows(4).any(|w| w == b"\r\n\r\n") {
                    let n = stream.read(&mut chunk).await.unwrap_or(0);
                    if n == 0 {
                        return;
                    }
                    buf.extend_from_slice(&chunk[..n]);
                }
                counter.fetch_add(1, Ordering::SeqCst);
                if let Some(gate) = gate {
                    let _ = gate.acquire().await;
                }
                let body = format!(r#"{{"id":{user_id},"username":"jellybeam-test-user"}}"#);
                let response = format!(
                    "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                    body.len()
                );
                let _ = stream.write_all(response.as_bytes()).await;
                let _ = stream.shutdown().await;
            });
        }
    });

    (format!("http://{addr}"), hits)
}

fn api_key_entry(server_url: &str, user_id: &str, seerr_url: &str) -> SeerrConfigEntry {
    SeerrConfigEntry {
        server_url: server_url.to_string(),
        user_id: user_id.to_string(),
        seerr_url: seerr_url.to_string(),
        method: SeerrAuthMethod::ApiKey,
        identity: String::new(),
        secret: "test-key".to_string(),
    }
}

fn wait_for_hits(hits: &std::sync::atomic::AtomicUsize, at_least: usize) {
    let deadline = Instant::now() + Duration::from_secs(10);
    while hits.load(std::sync::atomic::Ordering::SeqCst) < at_least {
        assert!(
            Instant::now() < deadline,
            "the mock Seerr never saw the login"
        );
        std::thread::sleep(Duration::from_millis(5));
    }
}

#[test]
fn a_lazy_rebuild_uses_the_installed_accounts_seerr_when_the_file_names_another() {
    let (dir, core) = core_in_tempdir();
    seed_session(dir.path(), "http://jellyfin-a.test", "user-a");
    seed_session(dir.path(), "http://jellyfin-b.test", "user-b");
    let (seerr_a, hits_a) = core.runtime().block_on(counting_seerr_server(11, None));
    let (seerr_b, hits_b) = core.runtime().block_on(counting_seerr_server(22, None));
    let mut config = SeerrConfigFile::default();
    config.upsert(api_key_entry("http://jellyfin-a.test", "user-a", &seerr_a));
    config.upsert(api_key_entry("http://jellyfin-b.test", "user-b", &seerr_b));
    save_config(dir.path(), &config).expect("seed config");

    core.switch_session(1).expect("account B is installed");
    // The persisted active index is the one that can be wrong (a failed write is only logged).
    let mut list = session::load_list(dir.path());
    list.active = 0;
    session::save_list(dir.path(), &list).expect("rewrite the active index back to A");
    assert_eq!(
        session::load(dir.path()).map(|s| s.user_id).as_deref(),
        Some("user-a"),
        "precondition: the file names A while B is installed"
    );

    let handle = core
        .require_seerr_handle()
        .expect("B's saved Seerr connection rebuilds");

    // docs/14 and docs/18 §2.1: the Seerr connection belongs to the installed account, never to the file.
    assert_eq!(
        hits_a.load(std::sync::atomic::Ordering::SeqCst),
        0,
        "A's Seerr must not be contacted"
    );
    assert_eq!(
        hits_b.load(std::sync::atomic::Ordering::SeqCst),
        2,
        "B's Seerr serves the rebuild's login and settings reads"
    );
    assert_eq!(handle.user_id(), 22, "the handle must be B's");
    assert_eq!(
        cached_seerr(&core).as_ref().map(SeerrHandle::user_id),
        Some(22),
        "the cached handle must be B's"
    );
}

#[test]
fn a_rebuild_spanning_a_removal_is_not_cached_even_when_the_file_still_names_the_account() {
    let (dir, core) = core_in_tempdir();
    seed_session(dir.path(), "http://jellyfin-a.test", "user-a");
    let gate = Arc::new(tokio::sync::Semaphore::new(0));
    let (seerr_a, hits_a) = core
        .runtime()
        .block_on(counting_seerr_server(11, Some(gate.clone())));
    let mut config = SeerrConfigFile::default();
    config.upsert(api_key_entry("http://jellyfin-a.test", "user-a", &seerr_a));
    save_config(dir.path(), &config).expect("seed config");
    core.restore_session().expect("account A restores");

    let rebuilding = {
        let core = core.clone();
        std::thread::spawn(move || core.require_seerr_handle())
    };
    wait_for_hits(&hits_a, 1);

    // The file names A again once the removal has finished, as a concurrent re-add would leave it.
    core.remove_session(0).expect("account A is removed");
    seed_session(dir.path(), "http://jellyfin-a.test", "user-a");
    assert_eq!(
        session::load(dir.path()).map(|s| s.user_id).as_deref(),
        Some("user-a"),
        "precondition: the file names A after the removal"
    );
    gate.add_permits(16);
    let _ = rebuilding.join().expect("the rebuild thread finishes");

    // docs/14 and docs/18 §2.1: a handle built for an account the core no longer has installed is dropped.
    assert!(
        cached_seerr(&core).is_none(),
        "a rebuild that spans a removal must not repopulate the cleared slot"
    );
}

#[test]
fn a_rebuild_spanning_switch_session_is_not_cached_under_the_new_account() {
    let (dir, core) = core_in_tempdir();
    seed_session(dir.path(), "http://jellyfin-a.test", "user-a");
    seed_session(dir.path(), "http://jellyfin-b.test", "user-b");
    let gate = Arc::new(tokio::sync::Semaphore::new(0));
    let (seerr_a, hits_a) = core
        .runtime()
        .block_on(counting_seerr_server(11, Some(gate.clone())));
    let mut config = SeerrConfigFile::default();
    config.upsert(api_key_entry("http://jellyfin-a.test", "user-a", &seerr_a));
    save_config(dir.path(), &config).expect("seed config");
    core.switch_session(0).expect("account A is installed");

    let rebuilding = {
        let core = core.clone();
        std::thread::spawn(move || core.require_seerr_handle())
    };
    wait_for_hits(&hits_a, 1);
    core.switch_session(1)
        .expect("account B is installed meanwhile");
    gate.add_permits(16);
    let _ = rebuilding.join().expect("the rebuild thread finishes");

    // docs/14 and docs/18 §2.1: A's handle must not be readable under B.
    assert!(
        cached_seerr(&core).is_none(),
        "a rebuild that spans switch_session must not cache A's handle under B"
    );
}

#[test]
fn a_rebuild_that_loses_its_epoch_never_answers_with_the_next_accounts_handle() {
    let (dir, core) = core_in_tempdir();
    seed_session(dir.path(), "http://jellyfin-a.test", "user-a");
    seed_session(dir.path(), "http://jellyfin-b.test", "user-b");
    let gate = Arc::new(tokio::sync::Semaphore::new(0));
    let (seerr_a, hits_a) = core
        .runtime()
        .block_on(counting_seerr_server(11, Some(gate.clone())));
    let (seerr_b, _) = core.runtime().block_on(counting_seerr_server(22, None));
    let mut config = SeerrConfigFile::default();
    config.upsert(api_key_entry("http://jellyfin-a.test", "user-a", &seerr_a));
    config.upsert(api_key_entry("http://jellyfin-b.test", "user-b", &seerr_b));
    save_config(dir.path(), &config).expect("seed config");
    core.switch_session(0).expect("account A is installed");

    let rebuilding = {
        let core = core.clone();
        std::thread::spawn(move || core.require_seerr_handle())
    };
    wait_for_hits(&hits_a, 1);
    core.switch_session(1)
        .expect("account B is installed meanwhile");
    let handle_b = core
        .require_seerr_handle()
        .expect("B's own rebuild is cached");
    assert_eq!(handle_b.user_id(), 22, "precondition: B's handle is cached");
    gate.add_permits(16);

    // docs/14 and docs/18 §2.1: a rebuild for A that lost its epoch is an error, never B's handle.
    match rebuilding.join().expect("the rebuild thread finishes") {
        Ok(handle) => panic!(
            "A's rebuild answered with the handle for Seerr user {}",
            handle.user_id()
        ),
        Err(error) => assert!(
            matches!(
                error,
                CoreError::NotSignedIn | CoreError::SeerrNotConfigured
            ),
            "{error:?}"
        ),
    }
}

/// docs/14: the saved Jellyfin url is the config key as written, a trailing slash included.
#[test]
fn seerr_status_finds_the_entry_saved_under_a_url_with_a_trailing_slash() {
    let (dir, core) = core_in_tempdir();
    seed_session(dir.path(), "http://jellyfin.test/jf/", "user-1");
    core.restore_session().expect("the seeded account restores");
    let mut config = SeerrConfigFile::default();
    config.upsert(sample_entry("http://jellyfin.test/jf/", "user-1"));
    save_config(dir.path(), &config).expect("seed config");

    assert!(
        core.seerr_status().configured,
        "the entry saved under the account's exact url must be found"
    );
}

#[test]
fn seerr_disconnect_removes_the_entry_saved_under_a_url_with_a_trailing_slash() {
    let (dir, core) = core_in_tempdir();
    seed_session(dir.path(), "http://jellyfin.test/jf/", "user-1");
    core.restore_session().expect("the seeded account restores");
    let mut config = SeerrConfigFile::default();
    config.upsert(sample_entry("http://jellyfin.test/jf/", "user-1"));
    save_config(dir.path(), &config).expect("seed config");

    core.seerr_disconnect();

    assert!(
        load_config(dir.path()).entries.is_empty(),
        "disconnect must remove the entry saved under the account's exact url"
    );
}

// --- Routed mock Seerr: connect, home, mutations, relogin --------------------

/// One request the routed mock saw.
#[derive(Clone, Debug)]
struct MockReq {
    method: String,
    /// Path and query as sent, `/api/v1` prefix included.
    path: String,
    /// The request body as sent (empty for a GET).
    body: String,
}

impl MockReq {
    /// The path without its query string.
    fn route(&self) -> &str {
        self.path.split('?').next().unwrap_or(&self.path)
    }
}

struct MockReply {
    status: u16,
    body: String,
    delay: Duration,
    set_cookie: Option<String>,
    /// The reply waits for one permit (consumed) before it is written.
    held: Option<Arc<tokio::sync::Semaphore>>,
}

impl MockReply {
    fn json(status: u16, body: &str) -> Self {
        Self {
            status,
            body: body.to_string(),
            delay: Duration::ZERO,
            set_cookie: None,
            held: None,
        }
    }

    fn held_by(mut self, gate: &Arc<tokio::sync::Semaphore>) -> Self {
        self.held = Some(gate.clone());
        self
    }

    fn delayed(mut self, delay: Duration) -> Self {
        self.delay = delay;
        self
    }

    fn cookie(mut self, value: &str) -> Self {
        self.set_cookie = Some(format!("connect.sid={value}; Path=/; HttpOnly"));
        self
    }
}

type MockLog = Arc<Mutex<Vec<MockReq>>>;

/// Mock Seerr that answers each request from `handler` (after its `delay`) and logs it; every
/// connection is served on its own task, so delayed replies overlap.
async fn routed_seerr_server(
    handler: impl Fn(&MockReq) -> MockReply + Send + Sync + 'static,
) -> (String, MockLog) {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    let listener = tokio::net::TcpListener::bind("127.0.0.1:0")
        .await
        .expect("bind local listener");
    let addr = listener.local_addr().expect("local_addr");
    let log: MockLog = Arc::default();
    let handler = Arc::new(handler);
    let served = log.clone();

    tokio::spawn(async move {
        while let Ok((mut stream, _)) = listener.accept().await {
            let handler = handler.clone();
            let served = served.clone();
            tokio::spawn(async move {
                let mut buf = Vec::new();
                let mut chunk = [0u8; 4096];
                let header_end = loop {
                    let n = stream.read(&mut chunk).await.unwrap_or(0);
                    if n == 0 {
                        return;
                    }
                    buf.extend_from_slice(&chunk[..n]);
                    if let Some(pos) = buf.windows(4).position(|w| w == b"\r\n\r\n") {
                        break pos + 4;
                    }
                };
                let head = String::from_utf8_lossy(&buf[..header_end]).to_string();
                let mut lines = head.split("\r\n");
                let mut request_line = lines.next().unwrap_or_default().split(' ');
                let method = request_line.next().unwrap_or_default().to_string();
                let path = request_line.next().unwrap_or_default().to_string();
                let mut content_length = 0usize;
                for line in lines {
                    if let Some((name, value)) = line.split_once(':') {
                        if name.trim().eq_ignore_ascii_case("content-length") {
                            content_length = value.trim().parse().unwrap_or(0);
                        }
                    }
                }
                while buf.len() - header_end < content_length {
                    let n = stream.read(&mut chunk).await.unwrap_or(0);
                    if n == 0 {
                        break;
                    }
                    buf.extend_from_slice(&chunk[..n]);
                }

                let request = MockReq {
                    method,
                    path,
                    body: String::from_utf8_lossy(&buf[header_end..]).to_string(),
                };
                served
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .push(request.clone());
                let reply = handler(&request);
                if !reply.delay.is_zero() {
                    tokio::time::sleep(reply.delay).await;
                }
                if let Some(gate) = &reply.held {
                    if let Ok(permit) = gate.acquire().await {
                        permit.forget();
                    }
                }
                let status_line = match reply.status {
                    200 => "200 OK",
                    201 => "201 Created",
                    401 => "401 Unauthorized",
                    403 => "403 Forbidden",
                    404 => "404 Not Found",
                    _ => "500 Internal Server Error",
                };
                let cookie_header = reply
                    .set_cookie
                    .map(|c| format!("Set-Cookie: {c}\r\n"))
                    .unwrap_or_default();
                let response = format!(
                    "HTTP/1.1 {status_line}\r\nContent-Type: application/json\r\n{cookie_header}Content-Length: {}\r\nConnection: close\r\n\r\n{}",
                    reply.body.len(),
                    reply.body
                );
                let _ = stream.write_all(response.as_bytes()).await;
                let _ = stream.shutdown().await;
            });
        }
    });

    (format!("http://{addr}"), log)
}

fn mock_count(log: &MockLog, method: &str, route_prefix: &str) -> usize {
    log.lock()
        .unwrap_or_else(|e| e.into_inner())
        .iter()
        .filter(|r| r.method == method && r.route().starts_with(route_prefix))
        .count()
}

fn mock_total(log: &MockLog) -> usize {
    log.lock().unwrap_or_else(|e| e.into_inner()).len()
}

const EMPTY_PAGE: &str = r#"{"page":1,"totalPages":1,"totalResults":0,"results":[]}"#;
const ONE_CARD_PAGE: &str = r#"{"page":1,"totalPages":1,"totalResults":1,"results":[{"id":5,"title":"Synthetic Title","mediaType":"movie"}]}"#;
const ME: &str = r#"{"id":1,"username":"jellybeam-test-user"}"#;

/// Builds an API-key handle against `base` and installs it as the live one.
fn install_api_key_handle(core: &JellybeamCore, base: &str) -> SeerrHandle {
    let (client, user) = core
        .runtime()
        .block_on(seerr_api::SeerrClient::login(seerr_api::LoginArgs {
            base_url: base,
            method: seerr_api::SeerrAuthMethod::ApiKey,
            identity: "",
            secret: "test-key",
            connect_timeout: Duration::from_secs(2),
            request_timeout: Duration::from_secs(30),
        }))
        .expect("login against the routed mock should succeed");
    let handle = settled_handle(client, user.id, base);
    if core.installed_identity().is_none() {
        seed_session(core.data_dir(), "http://jellyfin.test", "user-a");
        core.restore_session()
            .expect("an account to hold the handle");
    }
    let epoch = core.account_epoch();
    assert!(core.store_if_epoch(epoch, |caches| caches.seerr = Some(handle.clone())));
    handle
}

fn movie_input(tmdb_id: i64) -> SeerrRequestInput {
    SeerrRequestInput {
        media_type: SeerrMediaType::Movie,
        tmdb_id,
        is_4k: false,
        seasons: Vec::new(),
        server_id: None,
        profile_id: None,
        root_folder: None,
    }
}

// --- 1. Connect probe timeouts do not outlive the connect ---------------------

/// docs/14 "Auth": the short probe timeouts apply to candidate probing only, never to the live handle.
#[test]
fn a_call_slower_than_the_probe_timeout_succeeds_after_connect() {
    let (dir, core) = core_in_tempdir();
    seed_session(dir.path(), "http://jellyfin.test", "user-a");
    core.restore_session().expect("account A restores");
    let (base, _log) = core
        .runtime()
        .block_on(routed_seerr_server(|req| match req.route() {
            "/api/v1/auth/me" => MockReply::json(200, ME),
            "/api/v1/settings/public" => MockReply::json(200, "{}"),
            "/api/v1/search" => {
                MockReply::json(200, EMPTY_PAGE).delayed(Duration::from_millis(900))
            }
            _ => MockReply::json(404, "{}"),
        }));

    // The probe request timeout is 300 ms for this thread's connects; the live client's is 30 s.
    PROBE_TIMEOUTS_OVERRIDE.with(|cell| {
        cell.set(Some((Duration::from_secs(2), Duration::from_millis(300))));
    });
    let connected = core.seerr_connect(
        base,
        SeerrAuthMethod::ApiKey,
        String::new(),
        "test-key".to_string(),
    );
    PROBE_TIMEOUTS_OVERRIDE.with(|cell| cell.set(None));
    connected.expect("the fast probe calls connect");

    let page = core.seerr_search("synthetic".to_string(), 1);
    assert!(
        page.is_ok(),
        "a 900 ms answer must not hit the 300 ms probe timeout after connect: {page:?}"
    );
}

// --- 2. Discover home: failure-aware, not cached when empty -------------------

fn home_routes(failing: &'static [&'static str]) -> impl Fn(&MockReq) -> MockReply {
    move |req| {
        let route = req.route();
        if route == "/api/v1/auth/me" {
            return MockReply::json(200, ME);
        }
        if failing
            .iter()
            .any(|f| route == format!("/api/v1/discover/{f}"))
        {
            return MockReply::json(500, r#"{"message":"boom"}"#);
        }
        if route.starts_with("/api/v1/discover/") {
            return MockReply::json(200, ONE_CARD_PAGE);
        }
        MockReply::json(404, "{}")
    }
}

/// docs/14 Performance: a home with every row failed is an error the UI can retry, not an empty home.
#[test]
fn a_home_with_every_row_failing_is_an_error_and_is_not_cached() {
    let (_dir, core) = core_in_tempdir();
    let (base, log) = core.runtime().block_on(routed_seerr_server(home_routes(&[
        "trending",
        "movies",
        "tv",
        "movies/upcoming",
        "tv/upcoming",
    ])));
    let handle = install_api_key_handle(&core, &base);

    let first = core.seerr_home();
    assert!(first.is_err(), "every row failed, got {first:?}");
    assert!(
        handle.cached_home().is_none(),
        "a failed home must not be cached"
    );
    let after_first = mock_count(&log, "GET", "/api/v1/discover/");
    assert_eq!(after_first, 5, "one request per row");

    let second = core.seerr_home();
    assert!(
        second.is_err(),
        "the retry hits the server again: {second:?}"
    );
    assert_eq!(
        mock_count(&log, "GET", "/api/v1/discover/"),
        10,
        "the second call must reach the server, not an empty cached snapshot"
    );
}

#[test]
fn a_home_with_some_rows_failing_returns_the_rows_that_loaded() {
    let (_dir, core) = core_in_tempdir();
    let (base, _log) = core.runtime().block_on(routed_seerr_server(home_routes(&[
        "trending",
        "tv/upcoming",
    ])));
    install_api_key_handle(&core, &base);

    let home = core.seerr_home().expect("three rows loaded");
    let ids: Vec<&str> = home.rows.iter().map(|r| r.id.as_str()).collect();
    assert_eq!(ids, ["movies", "tv", "upcoming_movies"]);
}

// --- 4. Account removal prunes the Seerr entry --------------------------------

fn account_index(core: &JellybeamCore, user_id: &str) -> u32 {
    core.list_accounts()
        .iter()
        .position(|a| a.user_id == user_id)
        .expect("the account is listed") as u32
}

fn seed_two_accounts_with_seerr(dir: &std::path::Path, core: &JellybeamCore) {
    seed_session(dir, "http://jellyfin-a.test", "user-a");
    seed_session(dir, "http://jellyfin-b.test", "user-b");
    let mut a = sample_entry("http://jellyfin-a.test", "user-a");
    a.secret = "secret-of-account-a".to_string();
    let mut b = sample_entry("http://jellyfin-b.test", "user-b");
    b.secret = "secret-of-account-b".to_string();
    let mut config = SeerrConfigFile::default();
    config.upsert(a);
    config.upsert(b);
    save_config(dir, &config).expect("seed config");
    core.switch_session(account_index(core, "user-a"))
        .expect("account A is installed");
}

fn seerr_file_text(dir: &std::path::Path) -> String {
    std::fs::read_to_string(config_path(dir)).unwrap_or_default()
}

/// docs/14: a plaintext Seerr secret must not outlive the Jellyfin account it belongs to.
#[test]
fn removing_a_saved_account_prunes_its_seerr_entry_and_secret() {
    let (dir, core) = core_in_tempdir();
    seed_two_accounts_with_seerr(dir.path(), &core);

    core.remove_session(account_index(&core, "user-b"))
        .expect("account B is removed");

    let config = load_config(dir.path());
    assert!(
        config.find("http://jellyfin-b.test", "user-b").is_none(),
        "B's Seerr entry must be gone"
    );
    assert!(config.find("http://jellyfin-a.test", "user-a").is_some());
    assert!(
        !seerr_file_text(dir.path()).contains("secret-of-account-b"),
        "B's secret must not survive on disk"
    );
}

#[test]
fn removing_the_installed_account_prunes_its_seerr_entry_and_secret() {
    let (dir, core) = core_in_tempdir();
    seed_two_accounts_with_seerr(dir.path(), &core);

    core.remove_session(account_index(&core, "user-a"))
        .expect("account A is removed");

    assert!(
        load_config(dir.path())
            .find("http://jellyfin-a.test", "user-a")
            .is_none(),
        "A's Seerr entry must be gone"
    );
    assert!(!seerr_file_text(dir.path()).contains("secret-of-account-a"));
    assert!(
        seerr_file_text(dir.path()).contains("secret-of-account-b"),
        "B's entry is untouched"
    );
}

// --- 5. A Seerr 401 is its own error -------------------------------------------

/// docs/14 Errors + docs/18 §2.1: a Seerr 401 is not the Jellyfin token's, so it never routes to Jellyfin reauthorization.
#[test]
fn a_seerr_401_does_not_map_to_the_jellyfin_unauthorized_error() {
    let err: CoreError = seerr_api::SeerrError::Unauthorized.into();
    assert!(
        !matches!(err, CoreError::Unauthorized { .. }),
        "Seerr's 401 must be its own variant, got {err:?}"
    );
}

#[test]
fn a_seerr_call_answered_401_fails_with_a_non_jellyfin_error() {
    let (_dir, core) = core_in_tempdir();
    let (base, _log) = core
        .runtime()
        .block_on(routed_seerr_server(|req| match req.route() {
            "/api/v1/auth/me" => MockReply::json(200, ME),
            _ => MockReply::json(401, "{}"),
        }));
    install_api_key_handle(&core, &base);

    let err = core
        .seerr_search("synthetic".to_string(), 1)
        .expect_err("an API key the server refuses");
    assert!(
        !matches!(err, CoreError::Unauthorized { .. }),
        "got {err:?}"
    );
}

// --- 6. A failed pre-read never becomes a create -------------------------------

/// docs/14 Request semantics: when the existing requests cannot be read, the submit fails rather than guessing "none".
#[test]
fn a_failed_pre_read_of_the_existing_request_returns_an_error_and_creates_nothing() {
    let (_dir, core) = core_in_tempdir();
    let (base, log) = core.runtime().block_on(routed_seerr_server(|req| {
        match (req.method.as_str(), req.route()) {
            (_, "/api/v1/auth/me") => MockReply::json(200, ME),
            ("POST", "/api/v1/request") => MockReply::json(201, r#"{"id":7,"status":1}"#),
            (_, "/api/v1/movie/42" | "/api/v1/tv/9") => {
                MockReply::json(500, r#"{"message":"boom"}"#)
            }
            _ => MockReply::json(404, "{}"),
        }
    }));
    install_api_key_handle(&core, &base);

    let movie = core.seerr_submit_request_detail(movie_input(42));
    let tv = core.seerr_submit_request_detail(SeerrRequestInput {
        media_type: SeerrMediaType::Tv,
        tmdb_id: 9,
        seasons: vec![1],
        ..movie_input(9)
    });

    assert!(
        movie.is_err(),
        "movie: a failed pre-read must fail the submit"
    );
    assert!(tv.is_err(), "tv: a failed pre-read must fail the submit");
    assert_eq!(
        mock_count(&log, "POST", "/api/v1/request"),
        0,
        "no request may be created on a guess"
    );
}

// --- 7. Submit / cancel return the post-mutation detail in one call -----------

const PENDING_REQUEST: &str = r#"{"id":7,"status":1,"is4k":false,"requestedBy":{"id":1}}"#;

fn movie_detail_routes(
    detail_reads: Arc<std::sync::atomic::AtomicUsize>,
    requested_after: usize,
) -> impl Fn(&MockReq) -> MockReply {
    move |req| match (req.method.as_str(), req.route()) {
        (_, "/api/v1/auth/me") => MockReply::json(200, ME),
        ("GET", "/api/v1/movie/42") => {
            let n = detail_reads.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
            if n >= requested_after {
                MockReply::json(
                    200,
                    &format!(
                        r#"{{"id":42,"title":"Sample Movie","mediaInfo":{{"status":2,"requests":[{PENDING_REQUEST}]}}}}"#
                    ),
                )
            } else {
                MockReply::json(200, r#"{"id":42,"title":"Sample Movie"}"#)
            }
        }
        ("POST", "/api/v1/request") => MockReply::json(201, PENDING_REQUEST),
        ("DELETE", "/api/v1/request/7") => MockReply::json(200, "{}"),
        (_, "/api/v1/movie/42/similar" | "/api/v1/movie/42/recommendations") => {
            MockReply::json(200, EMPTY_PAGE)
        }
        _ => MockReply::json(404, "{}"),
    }
}

/// A movie submit costs pre-read GET, POST and one detail GET: no sibling fetches.
#[test]
fn submitting_a_movie_request_returns_the_new_detail_in_pre_read_post_and_one_get() {
    let (_dir, core) = core_in_tempdir();
    let reads = Arc::new(std::sync::atomic::AtomicUsize::new(0));
    // The first detail read is the pre-read (nothing requested yet); later ones show the request.
    let (base, log) = core
        .runtime()
        .block_on(routed_seerr_server(movie_detail_routes(reads, 1)));
    install_api_key_handle(&core, &base);
    let before = mock_total(&log);

    let detail = core
        .seerr_submit_request_detail(movie_input(42))
        .expect("submit returns the detail");

    let active = detail
        .active_request()
        .expect("the new request is on the detail");
    assert_eq!(active.request_id, 7);
    assert_eq!(active.status, SeerrRequestStatus::Pending);
    assert_eq!(
        mock_total(&log) - before,
        3,
        "pre-read GET + POST + one detail GET, no four-call refetch"
    );
}

/// A cancel costs DELETE and one detail GET: no sibling fetches.
#[test]
fn cancelling_a_request_returns_the_new_detail_in_delete_and_one_get() {
    let (_dir, core) = core_in_tempdir();
    let reads = Arc::new(std::sync::atomic::AtomicUsize::new(0));
    // Every detail read after the DELETE shows nothing requested.
    let (base, log) = core
        .runtime()
        .block_on(routed_seerr_server(movie_detail_routes(reads, usize::MAX)));
    install_api_key_handle(&core, &base);
    let before = mock_total(&log);

    let detail = core
        .seerr_cancel_request_detail(SeerrMediaType::Movie, 42, 7)
        .expect("cancel returns the detail");

    assert!(detail.active_request().is_none());
    assert!(detail.can_request(), "the title is requestable again");
    assert_eq!(
        mock_total(&log) - before,
        2,
        "DELETE + one detail GET, no four-call refetch"
    );
}

/// docs/14 Performance: a home fetched across a submit/cancel is a pre-mutation snapshot and must
/// not enter the cache.
#[test]
fn a_home_fetched_across_a_mutation_is_not_cached() {
    for cancel in [false, true] {
        let (_dir, core) = core_in_tempdir();
        let gate = Arc::new(tokio::sync::Semaphore::new(0));
        let reads = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let detail = movie_detail_routes(reads, if cancel { usize::MAX } else { 1 });
        let held = gate.clone();
        let (base, log) = core.runtime().block_on(routed_seerr_server(move |req| {
            if req.route().starts_with("/api/v1/discover/") {
                MockReply::json(200, ONE_CARD_PAGE).held_by(&held)
            } else {
                detail(req)
            }
        }));
        let handle = install_api_key_handle(&core, &base);

        let home = {
            let core = core.clone();
            std::thread::spawn(move || core.seerr_home())
        };
        let started = Instant::now();
        while mock_count(&log, "GET", "/api/v1/discover/") < 5 {
            assert!(
                started.elapsed() < Duration::from_secs(5),
                "the home fetch never reached the server"
            );
            std::thread::sleep(Duration::from_millis(10));
        }
        if cancel {
            core.seerr_cancel_request_detail(SeerrMediaType::Movie, 42, 7)
                .expect("cancel while the home is in flight");
        } else {
            core.seerr_submit_request_detail(movie_input(42))
                .expect("submit while the home is in flight");
        }
        gate.add_permits(100);
        home.join()
            .expect("home thread")
            .expect("the in-flight home still answers its caller");

        assert!(
            handle.cached_home().is_none(),
            "cancel={cancel}: a home fetched across a mutation must not be cached"
        );
        let before = mock_count(&log, "GET", "/api/v1/discover/");
        core.seerr_home().expect("the next home");
        assert_eq!(
            mock_count(&log, "GET", "/api/v1/discover/") - before,
            5,
            "cancel={cancel}: the next home hits the server"
        );
        assert!(
            handle.cached_home().is_some(),
            "cancel={cancel}: a home fetched with no mutation in between is cached"
        );
    }
}

// --- 8. My Requests resolves its titles concurrently ---------------------------

/// The FFI returns one `Vec`, so progressive publishing has no Rust seam to assert on; this pins
/// the lower bound instead: 50 detail fetches of 150 ms each (2.55 s at the current 3 in flight)
/// must finish well inside 1.5 s (about 8 or more in flight).
#[test]
fn my_requests_resolves_fifty_titles_concurrently() {
    let (_dir, core) = core_in_tempdir();
    let requests: Vec<String> = (0..50)
        .map(|i| {
            format!(
                r#"{{"id":{id},"status":2,"is4k":false,"type":"movie","media":{{"tmdbId":{tmdb}}},"requestedBy":{{"id":1,"username":"synthetic"}}}}"#,
                id = 100 + i,
                tmdb = 1000 + i
            )
        })
        .collect();
    let list = format!(r#"{{"results":[{}]}}"#, requests.join(","));
    let (base, _log) = core.runtime().block_on(routed_seerr_server(move |req| {
        let route = req.route();
        if route == "/api/v1/auth/me" {
            MockReply::json(200, ME)
        } else if route == "/api/v1/request" {
            MockReply::json(200, &list)
        } else if let Some(id) = route.strip_prefix("/api/v1/movie/") {
            MockReply::json(
                200,
                &format!(r#"{{"id":{id},"title":"Synthetic Movie {id}"}}"#),
            )
            .delayed(Duration::from_millis(150))
        } else {
            MockReply::json(404, "{}")
        }
    }));
    install_api_key_handle(&core, &base);

    let started = Instant::now();
    let mine = core.seerr_my_requests().expect("the list resolves");
    let elapsed = started.elapsed();

    assert_eq!(mine.len(), 50);
    assert!(
        elapsed < Duration::from_millis(1500),
        "50 x 150 ms detail fetches took {elapsed:?}"
    );
}

// --- 9. A TV update keeps the seasons the request already holds ----------------

#[test]
fn merged_seasons_unions_sorts_and_dedups() {
    let held = [season(2, None), season(1, None)];
    assert_eq!(merged_seasons(&held, &[3, 2]), vec![1, 2, 3]);
    assert_eq!(merged_seasons(&[], &[2]), vec![2]);
}

/// docs/14 Request semantics: Seerr's `PUT /request/:id` replaces the season list, so submitting
/// season 2 while season 1 is pending must send both.
#[test]
fn submitting_a_season_while_another_is_pending_puts_the_union() {
    let (_dir, core) = core_in_tempdir();
    let (base, log) = core.runtime().block_on(routed_seerr_server(|req| {
        match (req.method.as_str(), req.route()) {
            (_, "/api/v1/auth/me") => MockReply::json(200, ME),
            ("GET", "/api/v1/tv/9") => MockReply::json(
                200,
                r#"{"id":9,"name":"Sample Show","seasons":[{"seasonNumber":1},{"seasonNumber":2}],"mediaInfo":{"status":2,"requests":[{"id":7,"status":1,"is4k":false,"requestedBy":{"id":1},"seasons":[{"seasonNumber":1,"status":1}]}]}}"#,
            ),
            ("PUT", "/api/v1/request/7") => MockReply::json(200, PENDING_REQUEST),
            _ => MockReply::json(404, "{}"),
        }
    }));
    install_api_key_handle(&core, &base);

    core.seerr_submit_request_detail(SeerrRequestInput {
        media_type: SeerrMediaType::Tv,
        tmdb_id: 9,
        seasons: vec![2],
        ..movie_input(9)
    })
    .expect("the update goes through");

    let bodies: Vec<String> = log
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .iter()
        .filter(|r| r.method == "PUT" || r.method == "POST")
        .map(|r| format!("{} {}", r.method, r.body))
        .collect();
    assert_eq!(bodies.len(), 1, "one mutation: {bodies:?}");
    let body: serde_json::Value =
        serde_json::from_str(bodies[0].split_once(' ').map_or("", |(_, b)| b)).expect("json body");
    assert!(bodies[0].starts_with("PUT "), "{bodies:?}");
    assert_eq!(
        body["seasons"],
        serde_json::json!([1, 2]),
        "the update must keep season 1: {bodies:?}"
    );
}

// --- 10. A change that landed says so even when its detail cannot be read ------

/// docs/14 Errors: the title read after a submit/cancel failing must not look like a refused
/// change, so the caller can tell the server moved.
#[test]
fn a_change_that_landed_but_whose_detail_read_failed_is_reported_as_changed() {
    for cancel in [false, true] {
        let (_dir, core) = core_in_tempdir();
        let reads = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let (base, log) = core.runtime().block_on(routed_seerr_server(move |req| {
            match (req.method.as_str(), req.route()) {
                (_, "/api/v1/auth/me") => MockReply::json(200, ME),
                // The submit's pre-read answers; every read after the change fails.
                ("GET", "/api/v1/movie/42") => {
                    if !cancel && reads.fetch_add(1, std::sync::atomic::Ordering::SeqCst) == 0 {
                        MockReply::json(200, r#"{"id":42,"title":"Sample Movie"}"#)
                    } else {
                        MockReply::json(500, r#"{"message":"boom"}"#)
                    }
                }
                ("POST", "/api/v1/request") => MockReply::json(201, PENDING_REQUEST),
                ("DELETE", "/api/v1/request/7") => MockReply::json(200, "{}"),
                _ => MockReply::json(404, "{}"),
            }
        }));
        let handle = install_api_key_handle(&core, &base);

        let outcome = if cancel {
            core.seerr_cancel_request_detail(SeerrMediaType::Movie, 42, 7)
        } else {
            core.seerr_submit_request_detail(movie_input(42))
        };

        assert!(
            matches!(outcome, Err(CoreError::SeerrChangeUnconfirmed)),
            "cancel={cancel}: got {outcome:?}"
        );
        let (method, route) = if cancel {
            ("DELETE", "/api/v1/request/7")
        } else {
            ("POST", "/api/v1/request")
        };
        assert_eq!(mock_count(&log, method, route), 1, "cancel={cancel}");
        assert_eq!(
            handle.home_generation(),
            1,
            "cancel={cancel}: the cached home is dropped although the detail read failed"
        );
    }
}

// --- 11. A relogin that gets no answer is a transport error -------------------

/// A routed Seerr whose first login hands out a cookie the server refuses and whose later logins
/// answer `relogin_reply`; returns the counter of login POSTs.
fn stale_cookie_seerr(
    core: &JellybeamCore,
    relogin_reply: impl Fn() -> MockReply + Send + Sync + 'static,
) -> (String, Arc<std::sync::atomic::AtomicUsize>) {
    let logins = Arc::new(std::sync::atomic::AtomicUsize::new(0));
    let login_count = logins.clone();
    let (base, _log) = core.runtime().block_on(routed_seerr_server(move |req| {
        match (req.method.as_str(), req.route()) {
            ("POST", "/api/v1/auth/local") => {
                if login_count.fetch_add(1, std::sync::atomic::Ordering::SeqCst) == 0 {
                    MockReply::json(200, ME).cookie("stale")
                } else {
                    relogin_reply()
                }
            }
            (_, "/api/v1/search") => MockReply::json(401, "{}").delayed(Duration::from_millis(50)),
            _ => MockReply::json(404, "{}"),
        }
    }));
    (base, logins)
}

/// docs/14 Errors: a relogin that never got an answer is a transport failure (unreachable), not
/// an expired session.
#[test]
fn a_relogin_that_fails_on_transport_reports_the_transport_error() {
    let (_dir, core) = core_in_tempdir();
    let (base, logins) = stale_cookie_seerr(&core, || {
        MockReply::json(200, ME).delayed(Duration::from_secs(3))
    });

    let result = core.runtime().block_on(async {
        let (client, _user) = seerr_api::SeerrClient::login(seerr_api::LoginArgs {
            base_url: &base,
            method: seerr_api::SeerrAuthMethod::Local,
            identity: "someone@example.test",
            secret: "hunter2",
            connect_timeout: Duration::from_secs(2),
            request_timeout: Duration::from_millis(400),
        })
        .await
        .expect("the first login succeeds");
        client.search("synthetic", 1).await.map(|_| ())
    });

    assert!(
        matches!(result, Err(seerr_api::SeerrError::Transport(_))),
        "a login that timed out is a transport error: {result:?}"
    );
    assert_eq!(logins.load(std::sync::atomic::Ordering::SeqCst), 2);
    let mapped: CoreError = result.expect_err("an error").into();
    assert!(
        matches!(mapped, CoreError::SeerrUnreachable { .. }),
        "got {mapped:?}"
    );
}

// --- 12. /settings/public is bounded at connect ---------------------------------

/// docs/14 "Auth": a slow `/settings/public` must not hold a connect past the settings cap.
#[test]
fn a_connect_does_not_wait_out_a_slow_public_settings_read() {
    let (dir, core) = core_in_tempdir();
    seed_session(dir.path(), "http://jellyfin.test", "user-a");
    core.restore_session().expect("account A restores");
    let (base, _log) = core
        .runtime()
        .block_on(routed_seerr_server(|req| match req.route() {
            "/api/v1/auth/me" => MockReply::json(200, ME),
            "/api/v1/settings/public" => {
                MockReply::json(200, r#"{"applicationTitle":"Synthetic Seerr"}"#)
                    .delayed(Duration::from_secs(2))
            }
            _ => MockReply::json(404, "{}"),
        }));

    PUBLIC_SETTINGS_TIMEOUT_OVERRIDE.with(|cell| cell.set(Some(Duration::from_millis(200))));
    let started = Instant::now();
    let status = core.seerr_connect(
        base,
        SeerrAuthMethod::ApiKey,
        String::new(),
        "test-key".to_string(),
    );
    let elapsed = started.elapsed();
    PUBLIC_SETTINGS_TIMEOUT_OVERRIDE.with(|cell| cell.set(None));

    let status = status.expect("the connect succeeds without the settings");
    assert!(status.configured);
    assert!(
        status.app_title.is_none(),
        "the slow settings read was cut off, so no title yet"
    );
    assert!(
        elapsed < Duration::from_millis(1500),
        "the connect waited {elapsed:?} for /settings/public"
    );
}
