//! End-to-end test against a real (dockerized) Jellyfin dev server: not
//! part of the default `cargo test`, requires `http://localhost:8096`.
//! Exercises the v0.1 app-facing flow through `JellybeamCore` as Kotlin
//! would: sign in, open the mirror, poll until sync catches up, check the
//! coarse snapshot queries.
//!
//! Run explicitly:
//!   cargo test -p jellybeam-ffi -- --ignored live_sign_in

use std::time::{Duration, Instant};

use jellybeam_core::JellybeamCore;

mod common;

const BASE_URL: &str = "http://localhost:8096";
const USERNAME: &str = "jellybeam-user";
const PASSWORD: &str = "jellybeam-test";

#[test]
#[ignore = "requires a live Jellyfin server at localhost:8096 (see dev/README.md: make server-up)"]
fn live_sign_in_sync_and_home() {
    let (_dir, core, account) =
        common::signed_in_core_with_settled_mirror(BASE_URL, USERNAME, PASSWORD);
    assert!(
        !account.user_id.is_empty(),
        "sign-in should return a user id"
    );
    assert_eq!(account.server_url, BASE_URL);

    let views = core.views();
    assert!(
        !views.is_empty(),
        "expected at least one view after sync (item_count={}, is_syncing={})",
        core.item_count(),
        core.is_syncing()
    );

    let home = core.home_snapshot(8);
    assert!(
        home.latest.iter().any(|shelf| !shelf.cards.is_empty()),
        "expected at least one 'latest' shelf with cards; got {} shelves",
        home.latest.len()
    );
}

/// Exercises the playback slice end to end: pick a real Movie,
/// `prepare_playback` it, check the plan looks like Direct Play. Never
/// calls `set_device_caps`, proving the conservative floor is enough to
/// direct-play the dev server's h264 media. docs/18-playback-quality.md §2:
/// since `prepare_playback` always returns a plan rather than refusing,
/// `Ok` alone doesn't prove agreement -- checks `play_method`/`server_verdict`.
///
/// Run explicitly:
///   cargo test -p jellybeam-ffi -- --ignored live_prepare_playback
#[test]
#[ignore = "requires a live Jellyfin server at localhost:8096 (see dev/README.md: make server-up)"]
fn live_prepare_playback() {
    let (_dir, core, _account) =
        common::signed_in_core_with_settled_mirror(BASE_URL, USERNAME, PASSWORD);

    let movie_id = find_a_movie_id(&core)
        .unwrap_or_else(|| panic!("expected at least one Movie card somewhere in home/children"));

    let plan = core
        .prepare_playback(movie_id.clone(), false)
        .unwrap_or_else(|e| panic!("prepare_playback({movie_id}): {e}"));

    assert_eq!(plan.item_id, movie_id);
    assert!(!plan.url.is_empty(), "expected a non-empty playback url");
    assert!(
        !plan.media_source_id.is_empty(),
        "expected a non-empty media_source_id"
    );
    assert!(
        !plan.play_session_id.is_empty(),
        "expected a non-empty play_session_id"
    );
    // The dev server's h264 media is direct-playable even under the
    // conservative floor. Since `prepare_playback` always returns a
    // DirectPlay plan regardless of verdict, assert `server_verdict` is
    // `None` and check the url shape independently (direct stream, not HLS).
    assert_eq!(plan.play_method, jellybeam_core::PlayMethodFfi::DirectPlay);
    assert!(
        plan.server_verdict.is_none(),
        "expected the dev server to agree this file is directly playable, got verdict {:?}",
        plan.server_verdict
    );
    assert!(
        plan.url.contains("/stream"),
        "expected a Direct Play stream url, got {}",
        plan.url
    );

    core.stop_playback(plan.play_session_id.clone(), 0);
}

/// Exercises `next_episode_after` against the "Quantum Static" show: finds
/// S01E01 and asserts the next episode is S01E02.
///
/// Run explicitly:
///   cargo test -p jellybeam-ffi -- --ignored live_next_episode_after
#[test]
#[ignore = "requires a live Jellyfin server at localhost:8096 (see dev/README.md: make server-up)"]
fn live_next_episode_after() {
    let (_dir, core, _account) =
        common::signed_in_core_with_settled_mirror(BASE_URL, USERNAME, PASSWORD);

    let s1e1 = find_episode(&core, "Quantum Static", 1, 1).unwrap_or_else(|| {
        panic!("expected to find Quantum Static S01E01 somewhere in the synced mirror")
    });

    let next = core.next_episode_after(s1e1.id.clone()).unwrap_or_else(|| {
        panic!("expected a next episode after Quantum Static S01E01 ({s1e1:?})")
    });

    assert_eq!(next.series_name.as_deref(), Some("Quantum Static"));
    assert_eq!(
        next.parent_index_number,
        Some(1),
        "expected next episode still in season 1"
    );
    assert_eq!(
        next.index_number,
        Some(2),
        "expected S01E02 to follow S01E01"
    );
    assert_ne!(next.id, s1e1.id);

    core.stop_playback("no-active-session".to_string(), 0);
}

/// Exercises settings-driven home filtering (docs/09-settings-plan.md
/// slice one): hide the "Shows" view via `set_settings`, confirm its
/// "Latest" shelf disappears from `home_snapshot` while `views()` stays
/// unfiltered. Next-up/resume filtering by library is a later slice (docs/07).
///
/// Run explicitly:
///   cargo test -p jellybeam-ffi -- --ignored live_settings_home_filtering
#[test]
#[ignore = "requires a live Jellyfin server at localhost:8096 (see dev/README.md: make server-up)"]
fn live_settings_home_filtering() {
    let (_dir, core, _account) =
        common::signed_in_core_with_settled_mirror(BASE_URL, USERNAME, PASSWORD);

    let views = core.views();
    let shows_view = views
        .iter()
        .find(|v| v.name == "Shows")
        .unwrap_or_else(|| panic!("expected a 'Shows' view on the dev server; got {views:?}"));
    let shows_view_id = shows_view.id.clone();

    let home_before = core.home_snapshot(8);
    assert!(
        home_before
            .latest
            .iter()
            .any(|shelf| shelf.view_id == shows_view_id),
        "expected a 'Shows' Latest shelf before hiding it; got {:?}",
        home_before
            .latest
            .iter()
            .map(|s| &s.view_name)
            .collect::<Vec<_>>()
    );

    let mut settings = core.get_settings();
    settings.hidden_library_ids = vec![shows_view_id.clone()];
    core.set_settings(settings);

    let home_after = core.home_snapshot(8);
    assert!(
        home_after
            .latest
            .iter()
            .all(|shelf| shelf.view_id != shows_view_id),
        "expected no 'Shows' Latest shelf after hiding it; got {:?}",
        home_after
            .latest
            .iter()
            .map(|s| &s.view_name)
            .collect::<Vec<_>>()
    );

    // `views()` itself must stay unfiltered.
    let views_after = core.views();
    assert!(
        views_after.iter().any(|v| v.id == shows_view_id),
        "views() must remain unfiltered by hidden_library_ids"
    );
}

/// Exercises mirror-backed search against the real seeded corpus with two
/// searches: "hevc" (the codec test matrix -- asserts every result is a
/// `Movie` with "hevc" in its name, more than one), and "Quantum" (the
/// "Quantum Static" show -- asserts both the `Series` and an `Episode`
/// match via the `series_name` FTS column).
///
/// Run explicitly:
///   cargo test -p jellybeam-ffi -- --ignored live_search
#[test]
#[ignore = "requires a live Jellyfin server at localhost:8096 (see dev/README.md: make server-up)"]
fn live_search() {
    let (_dir, core, _account) =
        common::signed_in_core_with_settled_mirror(BASE_URL, USERNAME, PASSWORD);

    let hevc_results = core.search("hevc".to_string(), 50);
    assert!(
        hevc_results.len() > 1,
        "expected more than one hevc codec-matrix movie; got {hevc_results:?}"
    );
    for card in &hevc_results {
        assert_eq!(
            card.item_type, "Movie",
            "expected only Movies to match \"hevc\"; got {card:?}"
        );
        assert!(
            card.name.to_lowercase().contains("hevc"),
            "expected every result's name to contain \"hevc\"; got {card:?}"
        );
    }

    let quantum_results = core.search("Quantum".to_string(), 50);
    assert!(
        quantum_results
            .iter()
            .any(|c| c.item_type == "Series" && c.name == "Quantum Static"),
        "expected the \"Quantum Static\" Series itself among the results; got {quantum_results:?}"
    );
    assert!(
        quantum_results
            .iter()
            .any(|c| c.item_type == "Episode" && c.series_name.as_deref() == Some("Quantum Static")),
        "expected at least one \"Quantum Static\" Episode among the results; got {quantum_results:?}"
    );
}

/// Exercises trickplay end to end: find item "98" (one of the two long
/// items the dev server's "Generate Trickplay Images" task targets),
/// `prepare_playback` it once, then fetch trickplay separately via
/// [`JellybeamCore::get_trickplay`] -- what `PlaybackViewModel.start()` does
/// fire-and-forget after `load()`.
///
/// Generation is async server-side and may still be running, so `None`
/// isn't itself a failure: polls for ~3 minutes and SKIPS with a printed
/// note if the manifest never appears.
///
/// Run explicitly:
///   cargo test -p jellybeam-ffi -- --ignored live_trickplay
#[test]
#[ignore = "requires a live Jellyfin server at localhost:8096 (see dev/README.md: make server-up)"]
fn live_trickplay() {
    let (_dir, core, _account) =
        common::signed_in_core_with_settled_mirror(BASE_URL, USERNAME, PASSWORD);

    let item_id = find_item_named(&core, "98")
        .unwrap_or_else(|| panic!("expected to find an item named \"98\" in the synced mirror"));

    let plan = core
        .prepare_playback(item_id.clone(), false)
        .unwrap_or_else(|e| panic!("prepare_playback({item_id}): {e}"));

    let poll_deadline = Instant::now() + Duration::from_secs(180);
    let mut trickplay = core.get_trickplay(item_id.clone(), plan.media_source_id.clone());
    while trickplay.is_none() && Instant::now() < poll_deadline {
        std::thread::sleep(Duration::from_secs(5));
        trickplay = core.get_trickplay(item_id.clone(), plan.media_source_id.clone());
    }

    let Some(trickplay) = trickplay else {
        eprintln!(
            "live_trickplay: no trickplay manifest for item \"98\" after ~3 minutes of \
             polling -- the \"Generate Trickplay Images\" task likely hasn't reached this \
             item yet; skipping assertions rather than failing (generation timing isn't \
             this test's fault)"
        );
        core.stop_playback(plan.play_session_id.clone(), 0);
        return;
    };

    assert!(trickplay.width > 0, "expected a positive tile width");
    assert!(trickplay.height > 0, "expected a positive tile height");
    assert!(
        trickplay.tile_width > 0 && trickplay.tile_height > 0,
        "expected a non-degenerate tile grid, got {trickplay:?}"
    );
    assert!(
        trickplay.thumbnail_count > 0,
        "expected at least one advertised thumbnail"
    );

    let url = core
        .trickplay_tile_url(item_id.clone(), trickplay.width, 0)
        .unwrap_or_else(|| panic!("trickplay_tile_url should return Some while signed in"));
    assert!(
        url.contains(&format!(
            "/Videos/{item_id}/Trickplay/{}/0.jpg",
            trickplay.width
        )),
        "unexpected trickplay tile url shape: {url}"
    );
    assert!(
        url.contains("ApiKey="),
        "expected the trickplay tile url to carry an ApiKey: {url}"
    );

    // The very first thumbnail must always resolve to sheet 0, tile (0, 0).
    let tile = jellybeam_core::trickplay_locate(trickplay, 0)
        .unwrap_or_else(|| panic!("trickplay_locate(trickplay, 0) should resolve a real manifest"));
    assert_eq!(tile.image_index, 0);
    assert_eq!(tile.x, 0);
    assert_eq!(tile.y, 0);

    core.stop_playback(plan.play_session_id.clone(), 0);
}

/// Depth-first search over every view's library tree for the first card
/// whose `name` matches exactly -- good enough for a small dev library.
fn find_item_named(core: &JellybeamCore, name: &str) -> Option<String> {
    for view in core.views() {
        let children = core.children(view.id.clone(), jellybeam_core::SortOrder::NameAsc, 0, 500);
        for card in &children {
            if card.name == name {
                return Some(card.id.clone());
            }
        }
        for series in children {
            for season in core.children(series.id, jellybeam_core::SortOrder::IndexNumber, 0, 200) {
                for ep in core.children(season.id, jellybeam_core::SortOrder::IndexNumber, 0, 200) {
                    if ep.name == name {
                        return Some(ep.id);
                    }
                }
            }
        }
    }
    None
}

/// Depth-first search for the first Episode card matching
/// `series_name`/`season`/`episode` -- good enough for a small dev library.
fn find_episode(
    core: &JellybeamCore,
    series_name: &str,
    season: i32,
    episode: i32,
) -> Option<jellybeam_core::Card> {
    for view in core.views() {
        for series in core.children(view.id, jellybeam_core::SortOrder::NameAsc, 0, 200) {
            if series.item_type != "Series" || series.name != series_name {
                continue;
            }
            for season_card in
                core.children(series.id, jellybeam_core::SortOrder::IndexNumber, 0, 200)
            {
                if season_card.item_type != "Season" {
                    continue;
                }
                for ep in core.children(
                    season_card.id,
                    jellybeam_core::SortOrder::IndexNumber,
                    0,
                    200,
                ) {
                    if ep.item_type == "Episode"
                        && ep.parent_index_number == Some(season)
                        && ep.index_number == Some(episode)
                    {
                        return Some(ep);
                    }
                }
            }
        }
    }
    None
}

/// Depth-first search over Home's rails, then each view's children, for
/// the first "Movie" card -- good enough for a small dev library.
fn find_a_movie_id(core: &JellybeamCore) -> Option<String> {
    let home = core.home_snapshot(50);
    let home_cards = home
        .resume
        .into_iter()
        .chain(home.next_up)
        .chain(home.latest.into_iter().flat_map(|shelf| shelf.cards));
    for card in home_cards {
        if card.item_type == "Movie" {
            return Some(card.id);
        }
    }

    for view in core.views() {
        let children = core.children(view.id, jellybeam_core::SortOrder::NameAsc, 0, 100);
        for card in children {
            if card.item_type == "Movie" {
                return Some(card.id);
            }
        }
    }

    None
}
