//! docs/07 §1: Continue Watching's grace windows. A few minutes in isn't a start, and stopping
//! in the credits is a finished watch; movie credits run 5-10 min, everything else's 30-120 s.

pub const TICKS_PER_MINUTE: i64 = 600_000_000;

/// Saved positions under this are treated as never started.
const START_GRACE_TICKS: i64 = 2 * TICKS_PER_MINUTE;
/// A Movie with this little left is watched.
const MOVIE_END_GRACE_TICKS: i64 = 10 * TICKS_PER_MINUTE;
/// Every other video type (episodes, music videos, home videos) with this little left is watched.
const END_GRACE_TICKS: i64 = 2 * TICKS_PER_MINUTE;
/// Each window is capped at this share of the runtime, so a short item isn't finished on start.
const MAX_GRACE_PERCENT: i64 = 20;

/// [`watch_state`]'s `InProgress` as an `items` predicate, so Continue Watching's query and its
/// partial index select the rows directly; a macro so index DDL can `concat!` it. Literals are
/// the constants above (2 and 10 min in ticks, 20%); `in_progress_sql_matches_watch_state` pins them.
macro_rules! in_progress_sql {
    () => {
        "playback_position_ticks > 0 \
         AND playback_position_ticks >= CASE WHEN runtime_ticks > 0 \
             THEN MIN(1200000000, runtime_ticks / 100 * 20) ELSE 1200000000 END \
         AND (runtime_ticks IS NULL OR runtime_ticks <= 0 \
             OR runtime_ticks - playback_position_ticks > MIN(CASE WHEN item_type = 'Movie' \
                 THEN 6000000000 ELSE 1200000000 END, runtime_ticks / 100 * 20))"
    };
}
pub(crate) use in_progress_sql;

/// A card shows this row as never started: [`watch_state`]'s `NotStarted` and not `played`.
/// `$p` is the column prefix (`""` or `"e."`); `not_started_sql_matches_watch_state` pins it.
macro_rules! not_started_sql {
    ($p:literal) => {
        concat!(
            "(",
            $p,
            "played = 0 AND (",
            $p,
            "playback_position_ticks <= 0 \
             OR ",
            $p,
            "playback_position_ticks < CASE WHEN COALESCE(",
            $p,
            "runtime_ticks, 0) > 0 \
                 THEN MIN(1200000000, ",
            $p,
            "runtime_ticks / 100 * 20) ELSE 1200000000 END))"
        )
    };
}
pub(crate) use not_started_sql;

/// A card shows this row as watched: server `played`, or [`watch_state`]'s grace-`Watched`.
/// `$p` is the column prefix; `watched_sql_matches_watch_state` pins it.
macro_rules! watched_sql {
    ($p:literal) => {
        concat!(
            "(",
            $p,
            "played = 1 OR (COALESCE(",
            $p,
            "runtime_ticks, 0) > 0 \
             AND ",
            $p,
            "playback_position_ticks >= MIN(1200000000, ",
            $p,
            "runtime_ticks / 100 * 20) \
             AND ",
            $p,
            "playback_position_ticks > 0 \
             AND ",
            $p,
            "runtime_ticks - ",
            $p,
            "playback_position_ticks <= MIN(CASE WHEN ",
            $p,
            "item_type = 'Movie' \
                 THEN 6000000000 ELSE 1200000000 END, ",
            $p,
            "runtime_ticks / 100 * 20)))"
        )
    };
}
pub(crate) use watched_sql;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum WatchState {
    NotStarted,
    InProgress,
    Watched,
}

/// Where a saved position falls; an unknown runtime only ever applies the start window.
pub fn watch_state(item_type: &str, position_ticks: i64, runtime_ticks: Option<i64>) -> WatchState {
    let runtime = runtime_ticks.filter(|&rt| rt > 0);
    let capped = |grace: i64| runtime.map_or(grace, |rt| grace.min(rt / 100 * MAX_GRACE_PERCENT));
    if position_ticks <= 0 || position_ticks < capped(START_GRACE_TICKS) {
        return WatchState::NotStarted;
    }
    let end_grace = if item_type == "Movie" {
        MOVIE_END_GRACE_TICKS
    } else {
        END_GRACE_TICKS
    };
    match runtime {
        Some(rt) if rt - position_ticks <= capped(end_grace) => WatchState::Watched,
        _ => WatchState::InProgress,
    }
}

/// The `(position_ticks, played)` pair every surface shows: only an in-progress item keeps its
/// position, and a watched one reads as played.
pub fn displayed_watch_state(
    item_type: &str,
    position_ticks: i64,
    runtime_ticks: Option<i64>,
    played: bool,
) -> (i64, bool) {
    match watch_state(item_type, position_ticks, runtime_ticks) {
        WatchState::NotStarted => (0, played),
        WatchState::InProgress => (position_ticks, played),
        WatchState::Watched => (0, true),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    use TICKS_PER_MINUTE as MIN;
    const MOVIE: Option<i64> = Some(120 * MIN);
    const EPISODE: Option<i64> = Some(44 * MIN);

    #[test]
    fn under_two_minutes_is_not_started() {
        assert_eq!(watch_state("Movie", 0, MOVIE), WatchState::NotStarted);
        assert_eq!(
            watch_state("Movie", 2 * MIN - 1, MOVIE),
            WatchState::NotStarted
        );
        assert_eq!(
            watch_state("Episode", 90 * 10_000_000, EPISODE),
            WatchState::NotStarted
        );
        assert_eq!(watch_state("Movie", 2 * MIN, MOVIE), WatchState::InProgress);
    }

    #[test]
    fn a_movie_with_ten_minutes_left_is_watched() {
        assert_eq!(watch_state("Movie", 110 * MIN, MOVIE), WatchState::Watched);
        assert_eq!(
            watch_state("Movie", 110 * MIN - 1, MOVIE),
            WatchState::InProgress
        );
    }

    #[test]
    fn an_episode_with_two_minutes_left_is_watched() {
        assert_eq!(
            watch_state("Episode", 42 * MIN, EPISODE),
            WatchState::Watched
        );
        assert_eq!(
            watch_state("Episode", 42 * MIN - 1, EPISODE),
            WatchState::InProgress
        );
        assert_eq!(
            watch_state("Episode", 35 * MIN, EPISODE),
            WatchState::InProgress
        );
    }

    #[test]
    fn other_video_types_follow_the_episode_window() {
        let runtime = Some(60 * MIN);
        assert_eq!(
            watch_state("MusicVideo", 55 * MIN, runtime),
            WatchState::InProgress
        );
        assert_eq!(watch_state("Video", 58 * MIN, runtime), WatchState::Watched);
    }

    #[test]
    fn short_items_cap_each_window_at_a_fifth_of_the_runtime() {
        // 9-min short film: start window 1.8 min, end window 1.8 min instead of 10.
        let runtime = Some(9 * MIN);
        assert_eq!(
            watch_state("Movie", 2 * MIN, runtime),
            WatchState::InProgress
        );
        assert_eq!(
            watch_state("Movie", 7 * MIN, runtime),
            WatchState::InProgress
        );
        assert_eq!(watch_state("Movie", 8 * MIN, runtime), WatchState::Watched);
    }

    #[test]
    fn an_unknown_runtime_only_applies_the_start_window() {
        assert_eq!(watch_state("Movie", MIN, None), WatchState::NotStarted);
        assert_eq!(
            watch_state("Movie", 500 * MIN, None),
            WatchState::InProgress
        );
        assert_eq!(
            watch_state("Movie", 500 * MIN, Some(0)),
            WatchState::InProgress
        );
    }

    #[test]
    fn in_progress_sql_matches_watch_state() {
        let conn = rusqlite::Connection::open_in_memory().expect("db");
        let sql = concat!(
            "SELECT ",
            in_progress_sql!(),
            " FROM (SELECT ?1 AS item_type, ?2 AS playback_position_ticks, ?3 AS runtime_ticks)"
        );
        let runtimes = [
            None,
            Some(0),
            Some(MIN),
            Some(9 * MIN),
            Some(12 * MIN),
            Some(44 * MIN),
            Some(120 * MIN),
        ];
        let positions = [
            0,
            1,
            MIN,
            2 * MIN - 1,
            2 * MIN,
            7 * MIN,
            8 * MIN,
            42 * MIN,
            110 * MIN - 1,
            110 * MIN,
            500 * MIN,
        ];
        for item_type in ["Movie", "Episode", "Video"] {
            for runtime in runtimes {
                // Each runtime's own window edges, one tick either side, so the capped
                // (short-item) boundaries are pinned too.
                let edges = runtime.filter(|&rt| rt > 0).map_or(vec![], |rt| {
                    let cap = |grace: i64| grace.min(rt / 100 * MAX_GRACE_PERCENT);
                    let end = if item_type == "Movie" {
                        MOVIE_END_GRACE_TICKS
                    } else {
                        END_GRACE_TICKS
                    };
                    [cap(START_GRACE_TICKS), rt - cap(end)]
                        .into_iter()
                        .flat_map(|edge| [edge - 1, edge, edge + 1])
                        .collect()
                });
                for position in positions.into_iter().chain(edges) {
                    let sql_says: bool = conn
                        .query_row(
                            sql,
                            rusqlite::params![item_type, position, runtime],
                            |row| row.get(0),
                        )
                        .expect("predicate");
                    let rust_says =
                        watch_state(item_type, position, runtime) == WatchState::InProgress;
                    assert_eq!(
                        sql_says, rust_says,
                        "{item_type} at {position} of {runtime:?}"
                    );
                }
            }
        }
    }

    /// Every (type, runtime, position, played) the SQL macros are pinned over.
    fn grid_cases() -> Vec<(&'static str, Option<i64>, i64)> {
        let runtimes = [
            None,
            Some(0),
            Some(MIN),
            Some(9 * MIN),
            Some(12 * MIN),
            Some(44 * MIN),
            Some(120 * MIN),
        ];
        let positions = [
            -1,
            0,
            1,
            MIN,
            2 * MIN - 1,
            2 * MIN,
            7 * MIN,
            8 * MIN,
            42 * MIN,
            110 * MIN - 1,
            110 * MIN,
            500 * MIN,
        ];
        let mut cases = Vec::new();
        for item_type in ["Movie", "Episode", "Video"] {
            for runtime in runtimes {
                let edges = runtime.filter(|&rt| rt > 0).map_or(vec![], |rt| {
                    let cap = |grace: i64| grace.min(rt / 100 * MAX_GRACE_PERCENT);
                    let end = if item_type == "Movie" {
                        MOVIE_END_GRACE_TICKS
                    } else {
                        END_GRACE_TICKS
                    };
                    [cap(START_GRACE_TICKS), rt - cap(end)]
                        .into_iter()
                        .flat_map(|edge| [edge - 1, edge, edge + 1])
                        .collect()
                });
                for position in positions.into_iter().chain(edges) {
                    cases.push((item_type, runtime, position));
                }
            }
        }
        cases
    }

    /// Evaluates a prefix-free macro body over the `grid_cases` and both `played` values.
    fn check_macro(sql_predicate: &str, expect: impl Fn(&str, i64, Option<i64>, bool) -> bool) {
        let conn = rusqlite::Connection::open_in_memory().expect("db");
        let sql = format!(
            "SELECT {sql_predicate} FROM (SELECT ?1 AS item_type, ?2 AS playback_position_ticks, \
             ?3 AS runtime_ticks, ?4 AS played)"
        );
        for (item_type, runtime, position) in grid_cases() {
            for played in [false, true] {
                let sql_says: bool = conn
                    .query_row(
                        &sql,
                        rusqlite::params![item_type, position, runtime, played],
                        |row| row.get(0),
                    )
                    .expect("predicate");
                assert_eq!(
                    sql_says,
                    expect(item_type, position, runtime, played),
                    "{item_type} at {position} of {runtime:?}, played={played}"
                );
            }
        }
    }

    #[test]
    fn not_started_sql_matches_watch_state() {
        check_macro(not_started_sql!(""), |t, pos, rt, played| {
            !played && watch_state(t, pos, rt) == WatchState::NotStarted
        });
    }

    #[test]
    fn watched_sql_matches_watch_state() {
        check_macro(watched_sql!(""), |t, pos, rt, played| {
            played || watch_state(t, pos, rt) == WatchState::Watched
        });
    }

    #[test]
    fn prefixed_macros_bind_to_the_alias() {
        let conn = rusqlite::Connection::open_in_memory().expect("db");
        let sql = concat!(
            "SELECT ",
            not_started_sql!("e."),
            ", ",
            watched_sql!("e."),
            " FROM (SELECT 'Movie' AS item_type, 0 AS playback_position_ticks, 0 AS played, \
             NULL AS runtime_ticks) e"
        );
        let (not_started, watched): (bool, bool) = conn
            .query_row(sql, [], |row| Ok((row.get(0)?, row.get(1)?)))
            .expect("predicate");
        assert!(not_started && !watched);
    }

    #[test]
    fn displayed_state_zeroes_position_outside_progress_and_marks_watched_played() {
        assert_eq!(
            displayed_watch_state("Movie", MIN, MOVIE, false),
            (0, false)
        );
        assert_eq!(displayed_watch_state("Movie", MIN, MOVIE, true), (0, true));
        assert_eq!(
            displayed_watch_state("Movie", 60 * MIN, MOVIE, false),
            (60 * MIN, false)
        );
        assert_eq!(
            displayed_watch_state("Movie", 115 * MIN, MOVIE, false),
            (0, true)
        );
    }
}
