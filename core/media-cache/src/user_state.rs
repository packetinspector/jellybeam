//! Pure decisions for the watch-state sweep (docs/07 Watch state): which mirror rows to
//! re-fetch, how provider ids are normalised, and when a trigger may run.

use std::collections::{HashMap, HashSet};
use std::time::{Duration, Instant};

use rusqlite::Connection;

use crate::UserStateReason;

/// A foreground/timer trigger within this long of a finished run is skipped (docs/07 Watch state).
pub(crate) const MIN_INTERVAL: Duration = Duration::from_secs(30);

/// A foreground/timer trigger within this long of a failed run is skipped, so a broken server
/// isn't hammered; startup and reconnect still run.
pub(crate) const FAILED_RETRY_FLOOR: Duration = Duration::from_secs(5);

/// A provider's bare or dashed id in any case -> dashed lowercase; any other shape is `None`.
pub(crate) fn normalize_id(raw: &str) -> Option<String> {
    if raw.len() != 32 && raw.len() != 36 {
        return None;
    }
    uuid::Uuid::parse_str(raw).ok().map(|u| u.to_string())
}

/// The sweep's inputs; every id is dashed lowercase (see [`normalize_id`]).
#[derive(Debug, Default)]
pub(crate) struct UserStateInput {
    /// Leaf ids the server reports played.
    pub server_played: HashSet<String>,
    /// Leaf ids in `/UserItems/Resume`.
    pub server_resume: HashSet<String>,
    pub local: LocalLeaves,
    /// Local writes the server hasn't seen yet; never overwritten.
    pub pending: HashSet<String>,
    /// The resume list is absent or truncated, so "not in it" proves nothing (docs/07 Watch state).
    pub resume_unknown: bool,
}

/// Known leaves (unsorted, may repeat) played elsewhere, unplayed elsewhere or with a stale resume
/// position, minus pending; ids the mirror lacks belong to breadth/reconcile.
pub(crate) fn changed_leaves(input: &UserStateInput) -> Vec<String> {
    let local = &input.local;
    let played_elsewhere = input.server_played.difference(&local.played);
    let unplayed_elsewhere = local.played.difference(&input.server_played);
    // Locally in progress but absent from the server's resume list: finished, cleared or
    // marked elsewhere.
    let stale_resume = local
        .in_progress
        .difference(&input.server_resume)
        .filter(|_| !input.resume_unknown);

    played_elsewhere
        .chain(unplayed_elsewhere)
        .chain(stale_resume)
        .filter(|id| local.known.contains(*id) && !input.pending.contains(*id))
        .cloned()
        .collect()
}

/// `leaves` plus their series/season ids, sorted and deduped once: the upsert doesn't recompute a
/// parent's `unplayed_item_count`, so a changed episode's parents are re-fetched too.
pub(crate) fn with_parents(
    mut leaves: Vec<String>,
    parents: &HashMap<String, Vec<String>>,
) -> Vec<String> {
    let extra: Vec<String> = leaves
        .iter()
        .filter_map(|leaf| parents.get(leaf))
        .flatten()
        .cloned()
        .collect();
    leaves.extend(extra);
    leaves.sort();
    leaves.dedup();
    leaves
}

/// Reconnect always runs; Startup only before any run on this refresher, since a run already
/// covers it (docs/07 Watch state); Foreground/Timer wait [`MIN_INTERVAL`] after a finished run
/// and [`FAILED_RETRY_FLOOR`] after a failed one.
pub(crate) fn due(
    last_finished: Option<Instant>,
    last_failed: Option<Instant>,
    now: Instant,
    reason: UserStateReason,
) -> bool {
    let waited = |since: Option<Instant>, floor: Duration| {
        since.is_none_or(|t| now.saturating_duration_since(t) >= floor)
    };
    match reason {
        UserStateReason::Startup => last_finished.is_none() && last_failed.is_none(),
        UserStateReason::Reconnect => true,
        UserStateReason::Timer | UserStateReason::Foreground => {
            waited(last_finished, MIN_INTERVAL) && waited(last_failed, FAILED_RETRY_FLOOR)
        }
    }
}

/// Item types whose watch state the sweep tracks; the leaves of the library tree.
pub(crate) const LEAF_TYPES: [&str; 4] = ["Movie", "Episode", "Video", "MusicVideo"];

/// The mirror's side of the sweep; row ids are stored dashed lowercase.
#[derive(Debug, Default)]
pub(crate) struct LocalLeaves {
    pub played: HashSet<String>,
    pub in_progress: HashSet<String>,
    pub known: HashSet<String>,
}

/// One pass over the leaf rows (docs/07 Watch state); in-progress is the Continue Watching predicate.
pub(crate) fn read_local_leaves(conn: &Connection) -> rusqlite::Result<LocalLeaves> {
    let sql = format!(
        "SELECT id, played, CASE WHEN {} THEN 1 ELSE 0 END FROM items WHERE item_type IN ({})",
        crate::watch_grace::in_progress_sql!(),
        LEAF_TYPES.map(|t| format!("'{t}'")).join(",")
    );
    let mut stmt = conn.prepare(&sql)?;
    let mut out = LocalLeaves::default();
    let mut rows = stmt.query([])?;
    while let Some(row) = rows.next()? {
        let id: String = row.get(0)?;
        if row.get::<_, bool>(1)? {
            out.played.insert(id.clone());
        }
        if row.get::<_, bool>(2)? {
            out.in_progress.insert(id.clone());
        }
        out.known.insert(id);
    }
    Ok(out)
}

/// Leaf -> its series/season ids for `leaves` present in the mirror (see [`with_parents`]).
pub(crate) fn read_parents(
    conn: &Connection,
    leaves: &[String],
) -> rusqlite::Result<HashMap<String, Vec<String>>> {
    let mut out = HashMap::new();
    for chunk in leaves.chunks(500) {
        let placeholders = vec!["?"; chunk.len()].join(",");
        let mut stmt = conn.prepare(&format!(
            "SELECT id, series_id, season_id FROM items WHERE id IN ({placeholders})"
        ))?;
        let mut rows = stmt.query(rusqlite::params_from_iter(chunk))?;
        while let Some(row) = rows.next()? {
            let id: String = row.get(0)?;
            let parents: Vec<String> = [row.get::<_, Option<String>>(1)?, row.get(2)?]
                .into_iter()
                .flatten()
                .collect();
            if !parents.is_empty() {
                out.insert(id, parents);
            }
        }
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sorted_leaves(i: &UserStateInput) -> Vec<String> {
        let mut leaves = changed_leaves(i);
        leaves.sort();
        leaves.dedup();
        leaves
    }

    fn id(n: u32) -> String {
        format!("00000000-0000-4000-8000-{n:012x}")
    }
    fn set(ns: &[u32]) -> HashSet<String> {
        ns.iter().map(|n| id(*n)).collect()
    }
    fn ids(ns: &[u32]) -> Vec<String> {
        ns.iter().map(|n| id(*n)).collect()
    }
    fn input(
        server_played: &[u32],
        server_resume: &[u32],
        local_played: &[u32],
        local_in_progress: &[u32],
        known: &[u32],
    ) -> UserStateInput {
        UserStateInput {
            server_played: set(server_played),
            server_resume: set(server_resume),
            local: LocalLeaves {
                played: set(local_played),
                in_progress: set(local_in_progress),
                known: set(known),
            },
            ..Default::default()
        }
    }

    #[test]
    fn identical_state_refetches_nothing() {
        let i = input(&[1, 2], &[3], &[1, 2], &[3], &[1, 2, 3, 4]);
        assert!(changed_leaves(&i).is_empty());
    }

    #[test]
    fn played_elsewhere_is_refetched() {
        let i = input(&[1, 2], &[], &[1], &[], &[1, 2]);
        assert_eq!(sorted_leaves(&i), ids(&[2]));
    }

    #[test]
    fn unplayed_elsewhere_is_refetched() {
        let i = input(&[1], &[], &[1, 2], &[], &[1, 2]);
        assert_eq!(sorted_leaves(&i), ids(&[2]));
    }

    #[test]
    fn stale_resume_is_refetched_but_live_resume_is_not() {
        let i = input(&[], &[3], &[], &[3, 4], &[3, 4]);
        assert_eq!(sorted_leaves(&i), ids(&[4]));
    }

    #[test]
    fn in_progress_and_played_elsewhere_dedupes() {
        let i = input(&[5], &[], &[], &[5], &[5]);
        assert_eq!(sorted_leaves(&i), ids(&[5]));
    }

    #[test]
    fn unknown_ids_are_never_refetched() {
        // 9 is played on the server but not in the mirror: breadth/reconcile owns new items.
        let i = input(&[1, 9], &[], &[], &[], &[1]);
        assert_eq!(sorted_leaves(&i), ids(&[1]));
        let i = input(&[], &[], &[8], &[7], &[]);
        assert!(changed_leaves(&i).is_empty());
    }

    #[test]
    fn pending_local_writes_are_excluded_in_every_category() {
        let mut i = input(&[1], &[], &[2], &[3], &[1, 2, 3, 4]);
        i.pending = set(&[1, 2, 3]);
        assert!(changed_leaves(&i).is_empty());
        i.pending = set(&[2]);
        assert_eq!(sorted_leaves(&i), ids(&[1, 3]));
    }

    #[test]
    fn unknown_resume_skips_only_the_stale_resume_category() {
        let mut i = input(&[1], &[], &[2], &[3], &[1, 2, 3]);
        assert_eq!(sorted_leaves(&i), ids(&[1, 2, 3]));
        i.resume_unknown = true;
        assert_eq!(sorted_leaves(&i), ids(&[1, 2]));
    }

    #[test]
    fn output_is_sorted_and_deduped() {
        let i = input(&[30, 10, 20], &[], &[20], &[10], &[10, 20, 30]);
        assert_eq!(sorted_leaves(&i), ids(&[10, 30]));
    }

    #[test]
    fn parents_of_changed_leaves_ride_along_deduped() {
        let i = input(&[1, 2], &[], &[], &[], &[1, 2, 3]);
        let parents = HashMap::from([
            (id(1), vec![id(100), id(101)]),
            (id(2), vec![id(100), id(101)]),
            (id(3), vec![id(102)]),
        ]);
        assert_eq!(
            with_parents(changed_leaves(&i), &parents),
            ids(&[1, 2, 100, 101])
        );
    }

    #[test]
    fn parents_of_pending_or_unchanged_leaves_are_not_fetched() {
        let mut i = input(&[1], &[], &[], &[], &[1, 2]);
        i.pending = set(&[1]);
        let parents = HashMap::from([(id(1), vec![id(100)]), (id(2), vec![id(101)])]);
        assert!(with_parents(changed_leaves(&i), &parents).is_empty());
    }

    #[test]
    fn normalize_accepts_bare_and_dashed_in_any_case() {
        let dashed = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d";
        assert_eq!(normalize_id(dashed).as_deref(), Some(dashed));
        assert_eq!(
            normalize_id("0A1B2C3D4E5F4A6B8C7D9E0F1A2B3C4D").as_deref(),
            Some(dashed)
        );
        assert_eq!(
            normalize_id(&dashed.to_uppercase()).as_deref(),
            Some(dashed)
        );
    }

    #[test]
    fn normalize_rejects_other_shapes() {
        for bad in [
            "",
            "abc",
            "{0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d}",
            "zz1b2c3d4e5f4a6b8c7d9e0f1a2b3c4d",
        ] {
            assert_eq!(normalize_id(bad), None, "{bad}");
        }
    }

    #[test]
    fn bare_provider_ids_against_dashed_local_ids_cause_no_spurious_refetch() {
        let bare = [
            "0a1b2c3d4e5f4a6b8c7d9e0f1a2b3c4d",
            "11111111222233334444555555555555",
        ];
        let server: HashSet<String> = bare.iter().filter_map(|b| normalize_id(b)).collect();
        let local: HashSet<String> = [
            "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d",
            "11111111-2222-3333-4444-555555555555",
        ]
        .map(String::from)
        .into();
        let i = UserStateInput {
            server_played: server.clone(),
            server_resume: server,
            local: LocalLeaves {
                played: local.clone(),
                in_progress: local.clone(),
                known: local,
            },
            ..Default::default()
        };
        assert!(changed_leaves(&i).is_empty());
    }

    #[test]
    fn due_table() {
        use UserStateReason::*;
        let t0 = Instant::now();
        let at = |s: u64| t0 + Duration::from_secs(s);
        // (last_finished, last_failed, now, reason, expected)
        let cases = [
            (None, None, at(0), Foreground, true),
            (None, None, at(0), Timer, true),
            (None, None, at(0), Startup, true),
            (Some(t0), None, at(0), Startup, false),
            (Some(t0), None, at(0), Reconnect, true),
            (Some(t0), None, at(29), Foreground, false),
            (Some(t0), None, at(29), Timer, false),
            (Some(t0), None, at(30), Foreground, true),
            (Some(t0), None, at(30), Timer, true),
            (Some(at(10)), None, at(0), Timer, false), // clock reads before last_finished
            (None, Some(t0), at(0), Foreground, false),
            (None, Some(t0), at(4), Timer, false),
            (None, Some(t0), at(5), Foreground, true),
            (None, Some(t0), at(5), Timer, true),
            (None, Some(t0), at(0), Startup, false),
            (None, Some(t0), at(0), Reconnect, true),
            (Some(at(10)), Some(at(20)), at(26), Foreground, false), // finished floor still holds
            (Some(t0), Some(at(40)), at(44), Foreground, false),
            (Some(t0), Some(at(40)), at(45), Foreground, true),
        ];
        for (finished, failed, now, reason, want) in cases {
            assert_eq!(
                due(finished, failed, now, reason),
                want,
                "{reason:?} at {now:?}"
            );
        }
    }

    /// A coalesced request decides by its strongest reason (`min`), Startup being the weakest.
    #[test]
    fn coalesced_reasons_decide_by_the_strongest() {
        use UserStateReason::*;
        let t0 = Instant::now();
        let at = |s: u64| t0 + Duration::from_secs(s);
        let decide = |reasons: &[UserStateReason]| *reasons.iter().min().expect("reasons");
        // (last_finished, last_failed, now, reasons, expected)
        let cases: [(_, _, _, &[UserStateReason], bool); 6] = [
            (Some(t0), None, at(0), &[Startup, Reconnect], true),
            (Some(t0), None, at(0), &[Startup], false),
            (None, None, at(0), &[Startup], true),
            (None, None, at(0), &[Startup, Reconnect], true),
            (Some(t0), None, at(0), &[Startup, Foreground], false),
            (Some(t0), None, at(30), &[Startup, Timer], true),
        ];
        for (finished, failed, now, reasons, want) in cases {
            assert_eq!(
                due(finished, failed, now, decide(reasons)),
                want,
                "{reasons:?} at {now:?}"
            );
        }
    }
}
