//! One-off diagnostic: isolates which PlaybackInfo request shape 500s a server, using the app's own
//! session store.
//!
//!   cargo run -p jellyfin-core --example pbinfo_probe -- <server-host> "<search-term>"

use jellyfin_api::models::DeviceProfile;
use jellyfin_api::{ClientIdentity, JellyfinClient};

fn identity() -> ClientIdentity {
    ClientIdentity {
        client: "Jellybeam PBInfo Probe".to_string(),
        device: "pbinfo-probe".to_string(),
        device_id: "jellybeam-pbinfo-probe".to_string(),
        version: "0.1.0".to_string(),
    }
}

fn load_session(filter: &str) -> (String, String, String) {
    let home = std::env::var("HOME").expect("HOME");
    let path = format!("{home}/Library/Application Support/Jellybeam/dev-session-list.json");
    let raw = std::fs::read_to_string(&path).expect("read session store");
    let parsed: serde_json::Value = serde_json::from_str(&raw).expect("parse");
    let s = parsed["sessions"]
        .as_array()
        .expect("sessions")
        .iter()
        .find(|s| s["base_url"].as_str().unwrap_or("").contains(filter))
        .expect("no matching session");
    (
        s["base_url"].as_str().expect("base_url").to_string(),
        s["token"].as_str().expect("token").to_string(),
        s["user_id"].as_str().expect("user_id").to_string(),
    )
}

fn main() {
    let filter = std::env::args().nth(1).expect("server filter arg");
    let name = std::env::args().nth(2).expect("item name arg");
    let (base_url, token, user_id) = load_session(&filter);
    let rt = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .expect("rt");
    rt.block_on(async move {
        let client =
            JellyfinClient::from_token(&base_url, identity(), &token).with_user_id(&user_id);
        let id = name.clone();
        let resume_ticks: Option<i64> = None;

        let android_profile = |max_streaming_bitrate: Option<u32>| -> DeviceProfile {
            let raw = jellyfin_core::android_tv_profile(
                &jellyfin_core::AndroidTvCaps {
                    max_streaming_bitrate,
                    video: Vec::new(),
                },
                true,
            );
            serde_json::from_value(serde_json::to_value(raw).expect("raw profile serializes"))
                .expect("profile round-trips into the generated model")
        };

        let cases: Vec<(&str, DeviceProfile, Option<i64>)> = vec![
            ("empty profile, no ticks", DeviceProfile::default(), None),
            (
                "app profile (android_tv_profile, no caps), no ticks",
                android_profile(None),
                None,
            ),
            (
                "app profile, with resume ticks",
                android_profile(None),
                resume_ticks,
            ),
            (
                "app profile, bitrate cap 8Mbps, no ticks",
                android_profile(Some(8_000_000)),
                None,
            ),
        ];
        for (label, profile, ticks) in cases {
            match client
                .get_playback_info(
                    &id,
                    &profile,
                    ticks,
                    jellyfin_api::PlaybackInfoOptions::default(),
                )
                .await
            {
                Ok(resp) => println!("OK   {label}: {} media source(s)", resp.media_sources.len()),
                Err(e) => println!("FAIL {label}: {e}"),
            }
        }
    });
}
