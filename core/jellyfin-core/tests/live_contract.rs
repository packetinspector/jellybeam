//! Per-corpus-file contract tests against a REAL, running Jellyfin server
//! (not a fixture/mock), validating [`jellyfin_core::decide_playback`]'s
//! transcode-reasoning against actual server decisions.
//!
//! Every test is `#[ignore]`-gated (requires a live server); run with:
//!
//! ```text
//! cargo test -p jellyfin-core --test live_contract -- --ignored --nocapture
//! ```
//!
//! Requires a Jellyfin server at `http://localhost:8096` with the
//! `jellybeam-admin`/`jellybeam-test` account and the `dev/media` corpus
//! ingested as a library (the `Movies/*` file named below).
//!
//! Each test authenticates fresh (dev-server tokens expire quickly),
//! resolves its target file's item id by real filename (see
//! [`find_item_id_by_filename`]), POSTs `PlaybackInfo`, and asserts the
//! server's actual decision.

use jellyfin_api::models::{
    DeviceProfile, DirectPlayProfile, DlnaProfileType, EncodingContext, MediaStreamProtocol,
    TranscodeSeekInfo, TranscodingProfile,
};
use jellyfin_api::{ClientIdentity, ItemQuery, JellyfinClient};
use jellyfin_core::PlaybackDecision;

const BASE_URL: &str = "http://localhost:8096";
const USERNAME: &str = "jellybeam-admin";
const PASSWORD: &str = "jellybeam-test";

fn identity() -> ClientIdentity {
    ClientIdentity {
        client: "Jellybeam-W2.1-LiveContractTest".to_string(),
        device: "live-contract-test".to_string(),
        device_id: "jellybeam-w21-live-contract-test-device".to_string(),
        version: "0.1.0".to_string(),
    }
}

/// Authenticates fresh against the live dev server; panics with a message
/// pointing at the likely cause instead of an opaque `ApiError`.
async fn authenticated_client() -> JellyfinClient {
    let (client, _auth) =
        JellyfinClient::authenticate_by_name(BASE_URL, identity(), USERNAME, PASSWORD)
            .await
            .unwrap_or_else(|err| {
                panic!(
                    "failed to authenticate against {BASE_URL} as '{USERNAME}': {err:?}. \
                     Is the dev Jellyfin server running with the jellybeam-admin/jellybeam-test \
                     account seeded?"
                )
            });
    client
}

/// Resolves a corpus file's item id by matching the basename of its `Path`.
/// `Name` alone isn't reliable: Jellyfin's metadata parsing shortens some
/// codec-tag filenames (e.g. `07-hevc8-ac3.mkv` -> `Name: "07-hevc8"`).
async fn find_item_id_by_filename(client: &JellyfinClient, filename: &str) -> String {
    let result = client
        .get_items(&ItemQuery {
            parent_id: None,
            include_item_types: vec!["Movie".to_string()],
            recursive: true,
            sort_by: None,
            sort_order: None,
            fields: vec!["Path".to_string()],
            start_index: 0,
            limit: 500,
            ids: Vec::new(),
            is_missing: None,
            min_date_last_saved: None,
            ..ItemQuery::new()
        })
        .await
        .expect("list items from the live server (GET /Items)");

    let found = result.items.into_iter().find(|item| {
        item.path
            .as_deref()
            .and_then(|p| p.rsplit('/').next())
            .is_some_and(|basename| basename == filename)
    });

    let item = found.unwrap_or_else(|| {
        panic!(
            "corpus file '{filename}' not found in the live server's library -- \
             is dev/media ingested as a library on this server?"
        )
    });

    item.id
        .unwrap_or_else(|| panic!("item for '{filename}' has no Id"))
        .to_string()
}

/// A negative-control [`DeviceProfile`] accepting only `wmv`/`wmv3`/`wmav2`
/// (nothing in the corpus), so the server must refuse direct play and fall
/// back to transcoding.
fn wmv_only_negative_control_profile() -> DeviceProfile {
    DeviceProfile {
        name: Some("jellybeam-w2.1-negative-control-wmv-only".to_string()),
        direct_play_profiles: vec![DirectPlayProfile {
            container: Some("wmv".to_string()),
            audio_codec: Some("wmav2".to_string()),
            video_codec: Some("wmv3".to_string()),
            type_: Some(DlnaProfileType::Video),
        }],
        transcoding_profiles: vec![TranscodingProfile {
            container: Some("ts".to_string()),
            type_: Some(DlnaProfileType::Video),
            video_codec: Some("h264,hevc".to_string()),
            audio_codec: Some("aac,ac3".to_string()),
            protocol: Some(MediaStreamProtocol::Hls),
            estimate_content_length: false,
            enable_mpegts_m2_ts_mode: true,
            transcode_seek_info: Some(TranscodeSeekInfo::Auto),
            copy_timestamps: false,
            context: Some(EncodingContext::Streaming),
            enable_subtitles_in_manifest: true,
            max_audio_channels: Some("6".to_string()),
            min_segments: 1,
            segment_length: 6,
            break_on_non_key_frames: true,
            conditions: Vec::new(),
            enable_audio_vbr_encoding: true,
        }],
        ..Default::default()
    }
}

/// Pins: the wmv-only negative-control profile forces transcoding with
/// non-empty reasons, proving the corpus tests above actually discriminate.
#[ignore = "requires a live Jellyfin server at localhost:8096 with the dev corpus"]
#[tokio::test]
async fn wmv_only_negative_control_forces_transcode_with_reasons() {
    let client = authenticated_client().await;
    // Any corpus file works here since none of them are wmv/wmv3/wmav2.
    let item_id = find_item_id_by_filename(&client, "03-h264-eac3.ts").await;
    let profile = wmv_only_negative_control_profile();

    let info = client
        .get_playback_info(
            &item_id,
            &profile,
            None,
            jellyfin_api::PlaybackInfoOptions::default(),
        )
        .await
        .expect("PlaybackInfo request to the live server");

    assert!(
        info.media_sources
            .iter()
            .all(|s| s.supports_direct_play != Some(true)),
        "expected SupportsDirectPlay == false under the wmv-only negative-control \
         profile, got: {:#?}",
        info.media_sources
    );

    match jellyfin_core::decide_playback(&client, &item_id, &info) {
        Ok(PlaybackDecision::Transcode { reasons, .. }) => {
            assert!(
                !reasons.is_empty(),
                "expected non-empty TranscodeReasons under the negative-control profile"
            );
        }
        other => panic!(
            "expected Transcode (with reasons) under the wmv-only negative-control \
             profile, got {other:?}"
        ),
    }
}
