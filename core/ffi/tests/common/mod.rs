//! Shared setup for integration tests that drive a `JellybeamCore` against a
//! real Jellyfin dev server. `mod common;` in each test binary (Cargo's
//! convention for a `tests/` helper module that isn't its own test target).

use std::sync::Arc;
use std::time::{Duration, Instant};

use jellybeam_core::{AccountInfo, JellybeamCore};

/// Waits until sync has been idle continuously for a full second, not just
/// one poll -- clearing on `item_count() > 0` alone can race sync's own
/// debounced notifications. Panics if sync never settles within `timeout`.
fn wait_for_sync_to_settle(core: &JellybeamCore, timeout: Duration) {
    let deadline = Instant::now() + timeout;
    let required_idle = Duration::from_secs(1);
    let mut idle_since: Option<Instant> = None;
    loop {
        if core.is_syncing() {
            idle_since = None;
        } else {
            let since = *idle_since.get_or_insert_with(Instant::now);
            if since.elapsed() >= required_idle {
                return;
            }
        }
        assert!(
            Instant::now() < deadline,
            "sync never stayed idle for {required_idle:?} within {timeout:?} (is_syncing={})",
            core.is_syncing()
        );
        std::thread::sleep(Duration::from_millis(100));
    }
}

/// Signs in, opens the mirror, and waits for the initial sync to produce
/// items and settle -- the setup shared by every live integration test.
pub(crate) fn signed_in_core_with_settled_mirror(
    base_url: &str,
    username: &str,
    password: &str,
) -> (tempfile::TempDir, Arc<JellybeamCore>, AccountInfo) {
    let dir = tempfile::tempdir().expect("tempdir");
    let core = JellybeamCore::new(dir.path().to_string_lossy().to_string());

    let account = core
        .sign_in(
            base_url.to_string(),
            username.to_string(),
            password.to_string(),
        )
        .unwrap_or_else(|e| panic!("sign in against dev server {base_url}: {e}"));
    core.open_mirror()
        .unwrap_or_else(|e| panic!("open mirror: {e}"));

    let deadline = Instant::now() + Duration::from_mins(1);
    while core.is_syncing() && core.item_count() == 0 && Instant::now() < deadline {
        std::thread::sleep(Duration::from_millis(300));
    }
    assert!(
        core.item_count() > 0,
        "expected the initial sync to produce at least one item (is_syncing={})",
        core.is_syncing()
    );
    wait_for_sync_to_settle(&core, Duration::from_mins(1));

    (dir, core, account)
}
