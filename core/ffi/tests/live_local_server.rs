//! Live proof that `JellybeamCore`'s websocket `EventBus` delivers a change
//! made by a *different* session, end to end.
//!
//! `#[ignore]`d by default -- needs a real dev server. Reads
//! `JELLYBEAM_DEV_SERVER_URL` (default `http://localhost:8096`); run again on
//! 8097 against a second dev server, both must pass independently:
//!
//!   cd core && JELLYBEAM_DEV_SERVER_URL=http://localhost:8096 \
//!     cargo test -p jellybeam-ffi --test live_local_server -- --ignored --nocapture
//!   cd core && JELLYBEAM_DEV_SERVER_URL=http://localhost:8097 \
//!     cargo test -p jellybeam-ffi --test live_local_server -- --ignored --nocapture
//!
//! Seeded admin user jellybeam-admin / jellybeam-test (see dev/setup-server.sh).
//!
//! **Mutates one item's watch state on the target server** (marks played,
//! then reverts) -- picks an item already unplayed with zero position,
//! reverting on every exit path including panics. Disposable dev servers
//! only.
//!
//! A negative control (`websocket_disabled_bus_delivers_no_event`) disables
//! the bus via `JELLYBEAM_DISABLE_EVENT_BUS=1` and asserts no event arrives,
//! proving the positive signal genuinely depends on it. Requires
//! `--features live-test-knobs`; the two tests serialize on a static lock:
//!
//!   cd core && JELLYBEAM_DEV_SERVER_URL=http://localhost:8096 \
//!     cargo test -p jellybeam-ffi --features live-test-knobs \
//!     --test live_local_server -- --ignored --nocapture

use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

mod common;

/// How long both tests give the websocket path after the second session's
/// mutation -- one constant so the two windows can never drift apart.
const EVENT_WINDOW: Duration = Duration::from_secs(10);

/// Serializes the two tests: the negative control flips a process-wide env
/// var the positive test's mirror-open also reads. A poisoned lock is
/// still fine to hold.
static SERIAL: Mutex<()> = Mutex::new(());

use jellybeam_core::{ChangeEvent, ChangeListener, JellybeamCore};
use jellyfin_api::{ClientIdentity, ItemQuery, JellyfinClient};

const ADMIN_USER: &str = "jellybeam-admin";
const ADMIN_PASS: &str = "jellybeam-test";

fn server_url() -> String {
    std::env::var("JELLYBEAM_DEV_SERVER_URL")
        .unwrap_or_else(|_| "http://localhost:8096".to_string())
}

/// Collects every `ChangeEvent` `JellybeamCore` delivers, in arrival order,
/// behind a `Mutex` the test's main thread can poll.
struct RecordingListener {
    events: Arc<Mutex<Vec<ChangeEvent>>>,
}

impl ChangeListener for RecordingListener {
    fn on_change(&self, event: ChangeEvent) {
        self.events
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .push(event);
    }
}

/// A second client identity, distinct from `core`'s own -- Jellyfin does
/// not echo `UserDataChanged` back to the session that caused it.
fn other_session_identity() -> ClientIdentity {
    ClientIdentity {
        client: "Jellybeam".to_string(),
        device: "live-bus-test-second-session".to_string(),
        device_id: "jellybeam-live-bus-test-second-session".to_string(),
        version: env!("CARGO_PKG_VERSION").to_string(),
    }
}

/// Depth-first search over every view (and every Series' Seasons/Episodes)
/// for every leaf Movie/Episode id -- collects every candidate since
/// `find_item_safe_to_mutate` needs to filter by live watch state.
fn leaf_item_ids(core: &JellybeamCore) -> Vec<String> {
    let mut ids = Vec::new();
    for view in core.views() {
        let children = core.children(view.id, jellybeam_core::SortOrder::NameAsc, 0, 200);
        for card in &children {
            if card.item_type == "Movie" || card.item_type == "Episode" {
                ids.push(card.id.clone());
            }
        }
        for series in children.into_iter().filter(|c| c.item_type == "Series") {
            for season in core.children(series.id, jellybeam_core::SortOrder::IndexNumber, 0, 200) {
                for ep in core.children(season.id, jellybeam_core::SortOrder::IndexNumber, 0, 200) {
                    if ep.item_type == "Episode" {
                        ids.push(ep.id);
                    }
                }
            }
        }
    }
    ids
}

/// Picks the first leaf item whose live `UserData` (via the *second*
/// session, never `core`'s possibly-stale mirror) is unplayed at zero
/// position -- the only shape `mark_unplayed` restores exactly.
fn find_item_safe_to_mutate(
    core: &JellybeamCore,
    net_rt: &tokio::runtime::Runtime,
    second_client: &JellyfinClient,
) -> String {
    for id in leaf_item_ids(core) {
        let query = ItemQuery {
            ids: vec![id.clone()],
            recursive: true,
            // The server takes `limit=0` literally as "zero items", not
            // "unlimited"; `Default::default()` alone would return nothing.
            limit: 1,
            ..Default::default()
        };
        let Ok(result) = net_rt.block_on(second_client.get_items(&query)) else {
            continue;
        };
        let Some(item) = result.items.into_iter().next() else {
            continue;
        };
        let played = item
            .user_data
            .as_ref()
            .and_then(|u| u.played)
            .unwrap_or(false);
        let position = item
            .user_data
            .as_ref()
            .and_then(|u| u.playback_position_ticks)
            .unwrap_or(0);
        if !played && position == 0 {
            return id;
        }
    }
    panic!(
        "expected at least one unplayed, zero-playback-position Movie/Episode in the synced \
         mirror -- this test needs one exact-restore candidate to mutate"
    );
}

/// Guarantees `mark_unplayed` runs on every exit path, constructed
/// *before* the mutation. Never panics itself; a failed revert is logged.
struct RevertToUnplayed<'a> {
    net_rt: &'a tokio::runtime::Runtime,
    client: &'a JellyfinClient,
    item_id: String,
}

impl Drop for RevertToUnplayed<'_> {
    fn drop(&mut self) {
        if let Err(e) = self
            .net_rt
            .block_on(self.client.mark_unplayed(&self.item_id))
        {
            eprintln!(
                "cleanup: failed to revert mark_played({}) back to unplayed: {e}",
                self.item_id
            );
        }
    }
}

/// End-to-end proof: a change from a *second* session reaches this
/// session's `ChangeListener` with no poll of its own. Asserts a
/// `ChangeEvent::Upserted` arrives within 10s AND the mirror row reports
/// played, reverting regardless of outcome.
#[test]
#[ignore = "requires a live Jellyfin server (see JELLYBEAM_DEV_SERVER_URL, default localhost:8096); \
            mutates one item's watch state on the target server, meant for disposable dev \
            servers only"]
fn websocket_delivers_a_change_made_by_another_session() {
    let _serial = SERIAL.lock().unwrap_or_else(|e| e.into_inner());
    let base_url = server_url();
    let (_dir, core, _account) =
        common::signed_in_core_with_settled_mirror(&base_url, ADMIN_USER, ADMIN_PASS);

    let events: Arc<Mutex<Vec<ChangeEvent>>> = Arc::new(Mutex::new(Vec::new()));
    core.set_change_listener(Arc::new(RecordingListener {
        events: events.clone(),
    }));

    // A second, independently authenticated client drives the mutation --
    // `core`'s own client must never be the one that causes it.
    let net_rt = tokio::runtime::Runtime::new().expect("tokio runtime for the second client");
    let (second_client, _auth) = net_rt
        .block_on(JellyfinClient::authenticate_by_name(
            &base_url,
            other_session_identity(),
            ADMIN_USER,
            ADMIN_PASS,
        ))
        .unwrap_or_else(|e| panic!("second-session sign in against {base_url}: {e}"));

    let item_id = find_item_safe_to_mutate(&core, &net_rt, &second_client);

    // Clear whatever the listener recorded from initial sync's debounced
    // notifications before triggering the signal this test cares about.
    events.lock().unwrap_or_else(|e| e.into_inner()).clear();

    // Installed before the mutation so it reverts on every exit path.
    let _revert = RevertToUnplayed {
        net_rt: &net_rt,
        client: &second_client,
        item_id: item_id.clone(),
    };

    let mutation_started = Instant::now();
    net_rt
        .block_on(second_client.mark_played(&item_id))
        .unwrap_or_else(|e| panic!("mark_played({item_id}) from the second session: {e}"));

    let wait_deadline = Instant::now() + EVENT_WINDOW;
    let mut latency = None;
    while Instant::now() < wait_deadline {
        let saw_it = events.lock().unwrap_or_else(|e| e.into_inner()).iter().any(
            |event| matches!(event, ChangeEvent::Upserted { ids, .. } if ids.contains(&item_id)),
        );
        if saw_it {
            latency = Some(mutation_started.elapsed());
            break;
        }
        std::thread::sleep(Duration::from_millis(50));
    }

    let latency = latency.unwrap_or_else(|| {
        panic!(
            "expected a ChangeEvent::Upserted naming {item_id} within {EVENT_WINDOW:?} of the \
             second session's mark_played over the websocket bus; recorded events: {:?}",
            events.lock().unwrap_or_else(|e| e.into_inner())
        )
    });

    // The event alone only proves a notification fired; assert the
    // mirror's own row actually reflects the mutation.
    let card = core
        .card_by_id(item_id.clone())
        .unwrap_or_else(|e| panic!("card_by_id({item_id}) after the Upserted event: {e}"))
        .unwrap_or_else(|| {
            panic!("expected {item_id} to still be in the mirror after the Upserted event")
        });
    assert!(
        card.played,
        "expected the mirror's own view of {item_id} to report played after the Upserted event"
    );

    // `_revert` drops here, reverting to unplayed on every exit path.
    eprintln!(
        "websocket_delivers_a_change_made_by_another_session ({base_url}): mutation-to-event \
         latency = {latency:?}"
    );
}

/// Negative control: disables the bus via `JELLYBEAM_DISABLE_EVENT_BUS=1` and
/// asserts NO event arrives within `EVENT_WINDOW`, proving the positive
/// test's signal genuinely depends on it. Reverts unconditionally.
///
///   cargo test -p jellybeam-ffi --features live-test-knobs \
///     --test live_local_server -- --ignored --nocapture
#[cfg(feature = "live-test-knobs")]
#[test]
#[ignore = "requires a live Jellyfin server and --features live-test-knobs; mutates one item's \
            watch state on the target server, meant for disposable dev servers only"]
fn websocket_disabled_bus_delivers_no_event() {
    // `SERIAL` keeps the positive test from opening its mirror while this
    // env var is set.
    let _serial = SERIAL.lock().unwrap_or_else(|e| e.into_inner());
    std::env::set_var("JELLYBEAM_DISABLE_EVENT_BUS", "1");

    let base_url = server_url();
    let (_dir, core, _account) =
        common::signed_in_core_with_settled_mirror(&base_url, ADMIN_USER, ADMIN_PASS);

    let events: Arc<Mutex<Vec<ChangeEvent>>> = Arc::new(Mutex::new(Vec::new()));
    core.set_change_listener(Arc::new(RecordingListener {
        events: events.clone(),
    }));

    let net_rt = tokio::runtime::Runtime::new().expect("tokio runtime for the second client");
    let (second_client, _auth) = net_rt
        .block_on(JellyfinClient::authenticate_by_name(
            &base_url,
            other_session_identity(),
            ADMIN_USER,
            ADMIN_PASS,
        ))
        .unwrap_or_else(|e| panic!("second-session sign in against {base_url}: {e}"));

    let item_id = find_item_safe_to_mutate(&core, &net_rt, &second_client);
    events.lock().unwrap_or_else(|e| e.into_inner()).clear();

    let _revert = RevertToUnplayed {
        net_rt: &net_rt,
        client: &second_client,
        item_id: item_id.clone(),
    };

    net_rt
        .block_on(second_client.mark_played(&item_id))
        .unwrap_or_else(|e| panic!("mark_played({item_id}) from the second session: {e}"));

    std::thread::sleep(EVENT_WINDOW);
    let saw_it =
        events.lock().unwrap_or_else(|e| e.into_inner()).iter().any(
            |event| matches!(event, ChangeEvent::Upserted { ids, .. } if ids.contains(&item_id)),
        );
    assert!(
        !saw_it,
        "expected no event with JELLYBEAM_DISABLE_EVENT_BUS=1 set, but one arrived naming {item_id}"
    );

    std::env::remove_var("JELLYBEAM_DISABLE_EVENT_BUS");
}
