//! Perf probe: benchmarks Jellybeam's API client against raw HTTP timing, using the app's stored
//! session token.
//! Usage: `cargo run -p jellyfin-api --release --example perf_probe [-- <server-host>]` (defaults
//! to the first non-localhost session).
//! Benchmarks (N iterations, min/p50/p95 ms): views (GET /UserViews), items-100 (GET /Items page),
//! item-by-id, playback-info (POST /Items/{id}/PlaybackInfo), stream-8MiB (ranged GET), views-cold
//! (fresh client each time, isolates connection setup cost).

use std::time::Instant;

use jellyfin_api::models::DeviceProfile;
use jellyfin_api::{ClientIdentity, ItemQuery, JellyfinClient};

fn identity() -> ClientIdentity {
    ClientIdentity {
        client: "Jellybeam Perf Probe".to_string(),
        device: "perf-probe".to_string(),
        device_id: "jellybeam-perf-probe".to_string(),
        version: "0.1.0".to_string(),
    }
}

struct Session {
    base_url: String,
    token: String,
    user_id: String,
}

/// Reads the app's session store; the JSON shape mirrors `app`'s `keychain::StoredSessionList`
/// (copied here since `app` is a binary crate and not importable).
fn load_session(filter: Option<&str>) -> Session {
    let path = std::env::var("JELLYBEAM_SESSION_STORE")
        .expect("JELLYBEAM_SESSION_STORE must point to an ignored local session store");
    let raw = std::fs::read_to_string(&path)
        .unwrap_or_else(|_| panic!("could not read configured session store; path redacted"));
    let parsed: serde_json::Value = serde_json::from_str(&raw).expect("parse session store");
    let sessions = parsed["sessions"].as_array().expect("sessions array");
    let chosen = sessions
        .iter()
        .find(|s| {
            let url = s["base_url"].as_str().unwrap_or("");
            match filter {
                Some(f) => url.contains(f),
                None => !url.contains("localhost") && !url.contains("127.0.0.1"),
            }
        })
        .unwrap_or_else(|| panic!("no stored session matched; filter redacted"));
    Session {
        base_url: chosen["base_url"].as_str().expect("base_url").to_string(),
        token: chosen["token"].as_str().expect("token").to_string(),
        user_id: chosen["user_id"].as_str().expect("user_id").to_string(),
    }
}

fn stats(mut ms: Vec<f64>) -> (f64, f64, f64) {
    ms.sort_by(f64::total_cmp);
    let min = ms[0];
    let p50 = ms[ms.len() / 2];
    let p95 = ms[((ms.len() as f64 * 0.95) as usize).min(ms.len() - 1)];
    (min, p50, p95)
}

/// Panics without printing the error -- status/transport bodies can carry live endpoints or
/// account/media identifiers.
fn expect_redacted<T, E>(result: Result<T, E>, operation: &str) -> T {
    match result {
        Ok(value) => value,
        Err(_) => panic!("{operation} failed; diagnostics redacted"),
    }
}

async fn bench<F, Fut>(label: &str, n: usize, mut f: F) -> (f64, f64, f64)
where
    F: FnMut() -> Fut,
    Fut: std::future::Future<Output = ()>,
{
    let mut samples = Vec::with_capacity(n);
    for _ in 0..n {
        let t = Instant::now();
        f().await;
        samples.push(t.elapsed().as_secs_f64() * 1000.0);
    }
    let (min, p50, p95) = stats(samples);
    println!("{label:<14} min {min:>8.1}ms   p50 {p50:>8.1}ms   p95 {p95:>8.1}ms");
    (min, p50, p95)
}

fn main() {
    let filter = std::env::args().nth(1);
    let session = load_session(filter.as_deref());
    println!("probing <REDACTED> (N=15 per benchmark)\n");

    let rt = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .expect("tokio runtime");
    rt.block_on(run(session));
}

async fn run(session: Session) {
    const N: usize = 15;
    let client = JellyfinClient::from_token(&session.base_url, identity(), &session.token)
        .with_user_id(&session.user_id);

    // Warm-up (connection + server caches) -- not measured.
    let views = client.get_user_views().await;
    let _ = &views;

    bench("views", N, || {
        let c = client.clone();
        async move {
            expect_redacted(c.get_user_views().await, "views");
        }
    })
    .await;

    let page = client
        .get_items(&ItemQuery {
            include_item_types: vec!["Movie".to_string(), "Episode".to_string()],
            recursive: true,
            limit: 100,
            ..ItemQuery::new()
        })
        .await;
    let page = expect_redacted(page, "items page");
    println!("  (items-100 fetched {} items)", page.items.len());
    let item_id = page
        .items
        .first()
        .and_then(|i| i.id)
        .expect("at least one item")
        .to_string();

    bench("items-100", N, || {
        let c = client.clone();
        async move {
            let result = c
                .get_items(&ItemQuery {
                    include_item_types: vec!["Movie".to_string(), "Episode".to_string()],
                    recursive: true,
                    limit: 100,
                    ..ItemQuery::new()
                })
                .await;
            expect_redacted(result, "items page");
        }
    })
    .await;

    bench("item-by-id", N, || {
        let c = client.clone();
        let id = item_id.clone();
        async move {
            let result = c
                .get_items(&ItemQuery {
                    ids: vec![id],
                    ..ItemQuery::new()
                })
                .await;
            expect_redacted(result, "item by id");
        }
    })
    .await;

    bench("playback-info", N, || {
        let c = client.clone();
        let id = item_id.clone();
        async move {
            let result = c
                .get_playback_info(
                    &id,
                    &DeviceProfile::default(),
                    None,
                    jellyfin_api::PlaybackInfoOptions::default(),
                )
                .await;
            expect_redacted(result, "playback info");
        }
    })
    .await;

    const STREAM_BYTES: u64 = 8 * 1024 * 1024;
    let (_, p50, _) = bench("stream-8MiB", 5, || {
        let c = client.clone();
        let id = item_id.clone();
        async move {
            let result = c.warm_stream_head(&id, STREAM_BYTES).await;
            expect_redacted(result, "stream head");
        }
    })
    .await;
    println!(
        "  (stream throughput at p50: {:.1} MB/s)",
        (STREAM_BYTES as f64 / 1_048_576.0) / (p50 / 1000.0)
    );

    bench("views-cold", N, || {
        let base = session.base_url.clone();
        let token = session.token.clone();
        let uid = session.user_id.clone();
        async move {
            let fresh = JellyfinClient::from_token(&base, identity(), &token).with_user_id(&uid);
            expect_redacted(fresh.get_user_views().await, "views cold");
        }
    })
    .await;
}
