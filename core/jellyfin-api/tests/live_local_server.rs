//! Integration tests against a live Jellyfin server, `#[ignore]`d by default. Run with
//! `cargo test -p jellyfin-api --test live_local_server -- --ignored --test-threads=1` against
//! the local dev server; `--test-threads=1` since several tests share playback-report state.

use jellyfin_api::models::{DeviceProfile, DirectPlayProfile, DlnaProfileType};
use jellyfin_api::{
    ClientIdentity, ImageKind, ItemQuery, JellyfinClient, PlaybackReport, PlaybackReportKind,
};

const BASE_URL: &str = "http://localhost:8096";
const ADMIN_USER: &str = "jellybeam-admin";
const ADMIN_PASS: &str = "jellybeam-test";

/// Redacts the `ApiKey=...` query param before printing a URL, so the admin token doesn't end up
/// in CI logs; the legacy `api_key` param is matched too, as a fallback for other servers.
fn redact_api_key(url: &str) -> String {
    for needle in ["ApiKey=", "api_key="] {
        if let Some(idx) = url.find(needle) {
            let value_start = idx + needle.len();
            let value_end = url[value_start..]
                .find('&')
                .map(|i| value_start + i)
                .unwrap_or(url.len());
            return format!("{}<redacted>{}", &url[..value_start], &url[value_end..]);
        }
    }
    url.to_string()
}

fn identity() -> ClientIdentity {
    ClientIdentity {
        client: "Jellybeam".to_string(),
        device: "integration-test".to_string(),
        device_id: "jellybeam-integration-test".to_string(),
        version: env!("CARGO_PKG_VERSION").to_string(),
    }
}

fn permissive_device_profile() -> DeviceProfile {
    DeviceProfile {
        direct_play_profiles: vec![DirectPlayProfile {
            container: Some("mp4,mkv,webm,mov".to_string()),
            type_: Some(DlnaProfileType::Video),
            ..Default::default()
        }],
        ..Default::default()
    }
}

async fn authenticated_client() -> JellyfinClient {
    let (client, result) =
        JellyfinClient::authenticate_by_name(BASE_URL, identity(), ADMIN_USER, ADMIN_PASS)
            .await
            .expect("authenticate_by_name against local dev server");
    assert!(
        result.access_token.is_some(),
        "server did not return an access token"
    );
    client
}

#[tokio::test]
#[ignore]
async fn authenticate_by_name_succeeds() {
    let client = authenticated_client().await;
    assert!(
        client.user_id().is_some(),
        "authenticate_by_name should populate user_id"
    );
}

#[tokio::test]
#[ignore]
async fn wrong_password_is_unauthorized() {
    let result = JellyfinClient::authenticate_by_name(
        BASE_URL,
        identity(),
        ADMIN_USER,
        "definitely-not-the-password",
    )
    .await;
    let err = match result {
        Err(e) => e,
        Ok(_) => panic!("wrong password must not succeed"),
    };
    assert!(
        matches!(
            err,
            jellyfin_api::ApiError::Unauthorized { .. } | jellyfin_api::ApiError::Status { .. }
        ),
        "unexpected error variant: {err:?}"
    );
}

#[tokio::test]
#[ignore]
async fn get_user_views_returns_configured_libraries() {
    let client = authenticated_client().await;
    let views = client.get_user_views().await.expect("get_user_views");
    // Don't hard-code the seeded library's name/count; just confirm the call round-trips real rows.
    for view in &views {
        assert!(view.id.is_some(), "view missing Id: {view:?}");
    }
    println!(
        "views: {:?}",
        views
            .iter()
            .filter_map(|v| v.name.clone())
            .collect::<Vec<_>>()
    );
}

#[tokio::test]
#[ignore]
async fn get_items_and_playback_info_round_trip() {
    let client = authenticated_client().await;
    let views = client.get_user_views().await.expect("get_user_views");

    let mut found_item = None;
    for view in &views {
        let Some(parent_id) = view.id.map(|id| id.to_string()) else {
            continue;
        };
        let result = client
            .get_items(&ItemQuery {
                parent_id: Some(parent_id),
                include_item_types: vec!["Movie".to_string(), "Episode".to_string()],
                recursive: true,
                sort_by: None,
                sort_order: None,
                fields: vec![],
                start_index: 0,
                limit: 5,
                ids: vec![],
                is_missing: None,
                min_date_last_saved: None,
                ..ItemQuery::new()
            })
            .await
            .expect("get_items");
        if let Some(item) = result.items.into_iter().next() {
            found_item = Some(item);
            break;
        }
    }

    let Some(item) = found_item else {
        eprintln!("no playable item found in any library; skipping PlaybackInfo assertion");
        return;
    };
    let item_id = item.id.expect("item has an Id").to_string();

    let profile = permissive_device_profile();
    let info = client
        .get_playback_info(
            &item_id,
            &profile,
            Some(0),
            jellyfin_api::PlaybackInfoOptions::default(),
        )
        .await
        .expect("get_playback_info");
    assert!(
        !info.media_sources.is_empty(),
        "expected at least one MediaSource"
    );

    let source = &info.media_sources[0];
    let stream_url = client.stream_url(&item_id, source);
    assert!(
        stream_url.starts_with(BASE_URL),
        "stream_url should be rooted at the server: {stream_url}"
    );
    println!("stream_url: {}", redact_api_key(&stream_url));

    if let Some(tag) = item.image_tags.get("Primary") {
        let image_url = client.image_url(&item_id, ImageKind::Primary, tag, 300);
        assert!(image_url.starts_with(BASE_URL));
        println!("image_url: {}", redact_api_key(&image_url));
    }
}

/// Regression test: a PlaybackReport with a non-integer VolumeLevel 400s server-side; drives
/// Start -> Progress -> Stopped to confirm the `u8`-typed field satisfies the int32 model binder.
#[tokio::test]
#[ignore]
async fn playback_report_lifecycle_does_not_400() {
    let client = authenticated_client().await;
    let views = client.get_user_views().await.expect("get_user_views");

    let mut found_item = None;
    for view in &views {
        let Some(parent_id) = view.id.map(|id| id.to_string()) else {
            continue;
        };
        let result = client
            .get_items(&ItemQuery {
                parent_id: Some(parent_id),
                include_item_types: vec!["Movie".to_string(), "Episode".to_string()],
                recursive: true,
                sort_by: None,
                sort_order: None,
                fields: vec![],
                start_index: 0,
                limit: 1,
                ids: vec![],
                is_missing: None,
                min_date_last_saved: None,
                ..ItemQuery::new()
            })
            .await
            .expect("get_items");
        if let Some(item) = result.items.into_iter().next() {
            found_item = Some(item);
            break;
        }
    }

    let Some(item) = found_item else {
        eprintln!("no playable item found in any library; skipping playback-report lifecycle");
        return;
    };
    let item_id = item.id.expect("item has an Id").to_string();

    let profile = permissive_device_profile();
    let info = client
        .get_playback_info(
            &item_id,
            &profile,
            Some(0),
            jellyfin_api::PlaybackInfoOptions::default(),
        )
        .await
        .expect("get_playback_info");
    let Some(source) = info.media_sources.into_iter().next() else {
        eprintln!("no MediaSource for item; skipping playback-report lifecycle");
        return;
    };
    let media_source_id = source.id.clone().unwrap_or_else(|| item_id.clone());
    let play_session_id = info
        .play_session_id
        .unwrap_or_else(|| "test-session".to_string());

    let base = PlaybackReport {
        kind: PlaybackReportKind::Start,
        item_id: item_id.clone(),
        media_source_id: media_source_id.clone(),
        position_ticks: 0,
        is_paused: false,
        volume_level: 100,
        audio_stream_index: None,
        subtitle_stream_index: None,
        play_session_id: play_session_id.clone(),
        play_method: jellyfin_api::ReportPlayMethod::DirectPlay,
    };
    client
        .report_playback(base.clone())
        .await
        .expect("Start report must not 400");

    client
        .report_playback(PlaybackReport {
            kind: PlaybackReportKind::Progress,
            position_ticks: 1_000_000,
            volume_level: 37, // deliberately not a "round" value like 0/50/100
            is_paused: true,
            ..base.clone()
        })
        .await
        .expect("Progress report must not 400");

    client
        .report_playback(PlaybackReport {
            kind: PlaybackReportKind::Stopped,
            position_ticks: 2_000_000,
            ..base
        })
        .await
        .expect("Stopped report must not 400");
}

/// Pins that `get_media_segments` doesn't error against the real dev server, whose corpus likely
/// has no MediaSegments data; empty result and 404 both fold to `Ok(vec![])`.
#[tokio::test]
#[ignore]
async fn get_media_segments_does_not_error_against_live_server() {
    let client = authenticated_client().await;
    let views = client.get_user_views().await.expect("get_user_views");

    let mut found_item = None;
    for view in &views {
        let Some(parent_id) = view.id.map(|id| id.to_string()) else {
            continue;
        };
        let result = client
            .get_items(&ItemQuery {
                parent_id: Some(parent_id),
                include_item_types: vec!["Movie".to_string(), "Episode".to_string()],
                recursive: true,
                sort_by: None,
                sort_order: None,
                fields: vec![],
                start_index: 0,
                limit: 1,
                ids: vec![],
                is_missing: None,
                min_date_last_saved: None,
                ..ItemQuery::new()
            })
            .await
            .expect("get_items");
        if let Some(item) = result.items.into_iter().next() {
            found_item = Some(item);
            break;
        }
    }

    let Some(item) = found_item else {
        eprintln!("no playable item found in any library; skipping get_media_segments check");
        return;
    };
    let item_id = item.id.expect("item has an Id").to_string();

    let segments = client
        .get_media_segments(&item_id, &[])
        .await
        .expect("get_media_segments must not error, even with no data / no server support");
    println!(
        "segments for {item_id}: {} (empty is expected on this corpus)",
        segments.len()
    );

    let filtered = client
        .get_media_segments(&item_id, &["Intro", "Outro"])
        .await
        .expect("get_media_segments with includeSegmentTypes must not error");
    println!(
        "filtered (Intro/Outro) segments for {item_id}: {}",
        filtered.len()
    );
}

/// Pins that `minDateLastSaved` without its `ForUser` companion doesn't 500 server-side, and that
/// the filter is genuinely applied: far-future returns nothing, far-past returns items.
#[tokio::test]
#[ignore]
async fn min_date_last_saved_filters_without_erroring_against_live_server() {
    let client = authenticated_client().await;

    let past = client
        .get_items(&ItemQuery {
            recursive: true,
            limit: 5,
            min_date_last_saved: Some("2000-01-01T00:00:00Z".to_string()),
            ..ItemQuery::new()
        })
        .await
        .expect("minDateLastSaved query must not 500 (needs the ForUser companion param)");
    assert!(
        !past.items.is_empty(),
        "a far-past minDateLastSaved should return the whole corpus, got none"
    );

    let future = client
        .get_items(&ItemQuery {
            recursive: true,
            limit: 5,
            min_date_last_saved: Some("2099-01-01T00:00:00Z".to_string()),
            ..ItemQuery::new()
        })
        .await
        .expect("minDateLastSaved query must not 500");
    assert!(
        future.items.is_empty(),
        "a far-future minDateLastSaved should return nothing, got {} items -- \
         the filter is being ignored server-side",
        future.items.len()
    );
}

#[tokio::test]
#[ignore]
async fn websocket_connects_and_can_receive_or_time_out() {
    let client = authenticated_client().await;
    let mut rx = client.connect_ws().await.expect("connect_ws");

    // Asserts the connection stays live, not that a specific event arrives.
    let outcome = tokio::time::timeout(std::time::Duration::from_secs(3), rx.recv()).await;
    match outcome {
        Ok(Some(event)) => println!("received event: {event:?}"),
        Ok(None) => panic!("websocket channel closed immediately"),
        Err(_timeout) => {
            println!("no event within 3s (expected if server is idle) — connection stayed open")
        }
    }
}

// Full Initiate -> Connect -> AuthenticateWithQuickConnect is exercised by the app crate's
// JELLYBEAM_E2E walk; here we only assert Initiate returns a real, non-empty code.

#[tokio::test]
#[ignore]
async fn quick_connect_initiate_returns_a_code_against_live_dev_server() {
    let result = JellyfinClient::quick_connect_initiate(BASE_URL, &identity())
        .await
        .expect("Initiate against the live dev server (is Quick Connect enabled? see dev/setup-server.sh)");
    let code = result
        .code
        .expect("live Initiate response should include a Code");
    assert!(
        !code.is_empty(),
        "live Quick Connect code should be non-empty"
    );
    println!("live Quick Connect code: {code}");
}
