//! Credits-aware next-up's "what episode comes next" question: a pure picker
//! ([`pick_next_episode`]) over an already-ordered list, plus a thin
//! `media_cache::Mirror` query wrapper ([`next_episode_after`]) that builds
//! that list from the series -> seasons -> episodes shape and concatenates
//! it in season order. The picker knows nothing of the mirror/seasons/series
//! -- just "the next entry after this one, skipping virtual ones". The
//! series Play target ([`pick_series_play`], docs/11 item 11) rides the same walk.

use chrono::{DateTime, Utc};
use media_cache::{CardRow, Mirror, Sort};

use crate::types::{
    Card, EpisodeNeighbors, SeasonStats, SeriesPlayKind, SeriesPlayTarget, SeriesSummary,
};

/// No-pagination cap for a single parent's full listing (docs/07 §3).
const ALL: u32 = 10_000;

/// Plain descriptor for one item in a series' full episode ordering, so
/// [`pick_next_episode`] can run on real [`CardRow`]s or a test fixture.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct EpisodeSlot {
    pub id: String,
    pub item_type: String,
    pub is_virtual: bool,
}

/// Given `ordered` (already sorted season-then-episode ascending) and the
/// currently-playing episode's id, returns the next non-virtual Episode's
/// id. `None` if `current_id` isn't a found `Episode` entry, or it's the
/// last non-virtual episode in the series. A season boundary is not a
/// stopping condition -- `ordered` already spans every season back-to-back.
pub(crate) fn pick_next_episode(ordered: &[EpisodeSlot], current_id: &str) -> Option<String> {
    let idx = ordered
        .iter()
        .position(|e| e.id == current_id && e.item_type == "Episode")?;
    ordered[idx + 1..]
        .iter()
        .find(|e| e.item_type == "Episode" && !e.is_virtual)
        .map(|e| e.id.clone())
}

/// Shared traversal, factored out so [`crate::JellybeamCore::series_episodes_read`]
/// (docs/11 item 11) can reuse it: `series_id`'s seasons in `IndexNumber`
/// order, each season's episodes concatenated back-to-back. Specials sort
/// FIRST here (this owns raw mirror order, not the docs/11 button-label
/// Specials-last re-sort). `None` if any query fails, mirroring
/// [`Mirror::children_checked`]'s fail-closed contract; zero seasons is a
/// legitimate empty series, not an error.
pub(crate) fn all_episodes_of_series(mirror: &Mirror, series_id: &str) -> Option<Vec<CardRow>> {
    Some(
        episodes_by_season(mirror, series_id)?
            .into_iter()
            .flat_map(|(_, episodes)| episodes)
            .collect(),
    )
}

/// [`all_episodes_of_series`] keeping each episode's season row id, which the
/// resume season needs (docs/15 §5).
pub(crate) fn episodes_by_season(
    mirror: &Mirror,
    series_id: &str,
) -> Option<Vec<(String, Vec<CardRow>)>> {
    let seasons = mirror.children_checked(series_id, Sort::IndexNumber, 0, ALL)?;
    let mut out = Vec::with_capacity(seasons.len());
    for season in seasons {
        let of_season = mirror.children_checked(&season.id, Sort::IndexNumber, 0, ALL)?;
        out.push((season.id, of_season));
    }
    Some(out)
}

/// What the series summary needs of one episode, so its pure parts run on rows or a fixture.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct EpisodeFacts {
    pub is_virtual: bool,
    /// `parent_index_number == Some(0)`: docs/11 item 11 sorts Specials last.
    pub special: bool,
    pub position_ticks: i64,
    pub played: bool,
    /// docs/24: virtual and not airing later ([`is_missing`]).
    pub missing: bool,
}

impl EpisodeFacts {
    pub(crate) fn of(row: &CardRow, now: DateTime<Utc>) -> Self {
        EpisodeFacts {
            is_virtual: row.is_virtual,
            special: row.parent_index_number == Some(0),
            position_ticks: row.position_ticks,
            played: row.played,
            missing: row.is_virtual && is_missing(row.premiere_date.as_deref(), now),
        }
    }
}

/// docs/24: "missing" unless `premiere_date` is a parseable RFC3339 instant after `now`; the app's
/// air-date label reads the same table (`testdata/air-dates.tsv`).
pub(crate) fn is_missing(premiere_date: Option<&str>, now: DateTime<Utc>) -> bool {
    !premiere_date
        .and_then(|date| DateTime::parse_from_rfc3339(date).ok())
        .is_some_and(|date| date > now)
}

/// docs/11 §12 chip markers and docs/19 series-scope counts for one season's episodes:
/// `have`/`watched` count real episodes, `missing` counts [`EpisodeFacts::missing`], and
/// `any_progress` is any episode, virtual included, played or part-watched.
pub(crate) fn season_stats(season_id: String, episodes: &[EpisodeFacts]) -> SeasonStats {
    let count = |keep: fn(&EpisodeFacts) -> bool| {
        u32::try_from(episodes.iter().filter(|e| keep(e)).count()).unwrap_or(u32::MAX)
    };
    SeasonStats {
        season_id,
        have: count(|e| !e.is_virtual),
        watched: count(|e| !e.is_virtual && e.played),
        missing: count(|e| e.missing),
        any_progress: episodes.iter().any(|e| e.played || e.position_ticks > 0),
    }
}

/// docs/11 item 11 series Play target over `ordered` (traversal order): virtuals
/// skipped, Specials moved last (stable); then the first episode with a position
/// (Resume), else the first unplayed (NextUp), else -- or when `replay` -- the
/// first of that order (Replay). `None` when no episode is playable.
pub(crate) fn pick_series_play(
    ordered: &[EpisodeFacts],
    replay: bool,
) -> Option<(usize, SeriesPlayKind)> {
    let candidates: Vec<usize> = (0..ordered.len())
        .filter(|&i| !ordered[i].is_virtual && !ordered[i].special)
        .chain((0..ordered.len()).filter(|&i| !ordered[i].is_virtual && ordered[i].special))
        .collect();
    if !replay {
        if let Some(&i) = candidates.iter().find(|&&i| ordered[i].position_ticks > 0) {
            return Some((i, SeriesPlayKind::Resume));
        }
        if let Some(&i) = candidates.iter().find(|&&i| !ordered[i].played) {
            return Some((i, SeriesPlayKind::NextUp));
        }
    }
    candidates.first().map(|&i| (i, SeriesPlayKind::Replay))
}

/// One mirror walk for the series page (docs/11 item 11, §12): the Play pick plus each
/// season's [`season_stats`], so no episode list crosses FFI. `None` if a query failed.
pub(crate) fn series_summary(
    mirror: &Mirror,
    series_id: &str,
    replay: bool,
    now: DateTime<Utc>,
) -> Option<SeriesSummary> {
    let seasons = episodes_by_season(mirror, series_id)?;
    let mut rows: Vec<(&str, &CardRow)> = Vec::new();
    let mut facts: Vec<EpisodeFacts> = Vec::new();
    let mut stats = Vec::with_capacity(seasons.len());
    for (season_id, episodes) in &seasons {
        let start = facts.len();
        for episode in episodes {
            rows.push((season_id.as_str(), episode));
            facts.push(EpisodeFacts::of(episode, now));
        }
        stats.push(season_stats(season_id.clone(), &facts[start..]));
    }
    let target = pick_series_play(&facts, replay).map(|(i, kind)| SeriesPlayTarget {
        episode: Card::from(rows[i].1.clone()),
        season_id: rows[i].0.to_string(),
        kind,
    });
    Some(SeriesSummary {
        target,
        seasons: stats,
    })
}

/// Thin wrapper for [`crate::JellybeamCore::next_episode_after`]: resolves
/// `item_id`'s series -> seasons -> episodes via [`all_episodes_of_series`],
/// hands the flat ordering to [`pick_next_episode`], looks up the result.
/// `None` if `item_id` isn't a real `Episode` in the mirror, has no known
/// series, a query failed, or it's the series' last episode.
pub(crate) fn next_episode_after(mirror: &Mirror, item_id: &str) -> Option<Card> {
    let dto = mirror.item(item_id)?;
    if dto.type_ != Some(jellyfin_api::models::BaseItemKind::Episode) {
        return None;
    }
    let series_id = dto.series_id?.to_string();

    let episodes = all_episodes_of_series(mirror, &series_id)?;

    let slots: Vec<EpisodeSlot> = episodes
        .iter()
        .map(|e| EpisodeSlot {
            id: e.id.clone(),
            item_type: e.item_type.clone(),
            is_virtual: e.is_virtual,
        })
        .collect();

    let next_id = pick_next_episode(&slots, item_id)?;
    episodes
        .into_iter()
        .find(|e| e.id == next_id)
        .map(Card::from)
}

/// Resolves both OSD episode-edge controls from one coherent mirror walk.
/// `None` means the mirror could not establish the current episode's place
/// in the series; `Some` with either empty side is a legitimate series edge.
/// That distinction lets the FFI layer use the server only for an unresolved
/// mirror, without issuing a network request at every first/last episode.
pub(crate) fn episode_neighbors(mirror: &Mirror, item_id: &str) -> Option<EpisodeNeighbors> {
    let dto = mirror.item(item_id)?;
    if dto.type_ != Some(jellyfin_api::models::BaseItemKind::Episode) {
        return None;
    }
    let series_id = dto.series_id?.to_string();
    let episodes = all_episodes_of_series(mirror, &series_id)?;
    episode_neighbors_from_rows(episodes, item_id)
}

fn episode_neighbors_from_rows(episodes: Vec<CardRow>, item_id: &str) -> Option<EpisodeNeighbors> {
    let current = episodes.iter().position(|episode| episode.id == item_id)?;
    let previous = episodes[..current]
        .iter()
        .rev()
        .find(|episode| episode.item_type == "Episode" && !episode.is_virtual)
        .cloned()
        .map(Card::from);
    let next = episodes[current + 1..]
        .iter()
        .find(|episode| episode.item_type == "Episode" && !episode.is_virtual)
        .cloned()
        .map(Card::from);
    Some(EpisodeNeighbors { previous, next })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn slot(id: &str) -> EpisodeSlot {
        EpisodeSlot {
            id: id.to_string(),
            item_type: "Episode".to_string(),
            is_virtual: false,
        }
    }

    fn virtual_slot(id: &str) -> EpisodeSlot {
        EpisodeSlot {
            is_virtual: true,
            ..slot(id)
        }
    }

    #[test]
    fn normal_next_within_the_same_season() {
        let ordered = vec![slot("s1e1"), slot("s1e2"), slot("s1e3")];
        assert_eq!(
            pick_next_episode(&ordered, "s1e1"),
            Some("s1e2".to_string())
        );
    }

    #[test]
    fn skips_a_virtual_episode_to_find_the_next_real_one() {
        let ordered = vec![slot("s1e1"), virtual_slot("s1e2"), slot("s1e3")];
        assert_eq!(
            pick_next_episode(&ordered, "s1e1"),
            Some("s1e3".to_string())
        );
    }

    #[test]
    fn crosses_a_season_boundary() {
        let ordered = vec![slot("s1e1"), slot("s1e2"), slot("s2e1"), slot("s2e2")];
        assert_eq!(
            pick_next_episode(&ordered, "s1e2"),
            Some("s2e1".to_string())
        );
    }

    #[test]
    fn crosses_a_season_boundary_and_skips_a_virtual_episode() {
        let ordered = vec![
            slot("s1e1"),
            slot("s1e2"),
            virtual_slot("s2e1"),
            slot("s2e2"),
        ];
        assert_eq!(
            pick_next_episode(&ordered, "s1e2"),
            Some("s2e2".to_string())
        );
    }

    #[test]
    fn last_episode_of_the_series_has_no_next() {
        let ordered = vec![slot("s1e1"), slot("s1e2")];
        assert_eq!(pick_next_episode(&ordered, "s1e2"), None);
    }

    #[test]
    fn trailing_virtual_episodes_after_the_last_real_one_are_not_a_next() {
        let ordered = vec![slot("s1e1"), slot("s1e2"), virtual_slot("s1e3")];
        assert_eq!(pick_next_episode(&ordered, "s1e2"), None);
    }

    #[test]
    fn non_episode_input_has_no_next() {
        let ordered = vec![
            EpisodeSlot {
                id: "movie-1".to_string(),
                item_type: "Movie".to_string(),
                is_virtual: false,
            },
            slot("s1e1"),
        ];
        assert_eq!(pick_next_episode(&ordered, "movie-1"), None);
    }

    #[test]
    fn unknown_current_id_has_no_next() {
        let ordered = vec![slot("s1e1"), slot("s1e2")];
        assert_eq!(pick_next_episode(&ordered, "does-not-exist"), None);
    }

    // -- pick_series_play: docs/11 item 11 (ported from the Kotlin DetailFormatting/CollectionFormatting tests) --

    fn ep() -> EpisodeFacts {
        EpisodeFacts {
            is_virtual: false,
            special: false,
            position_ticks: 0,
            played: false,
            missing: false,
        }
    }

    fn watched() -> EpisodeFacts {
        EpisodeFacts {
            played: true,
            ..ep()
        }
    }

    fn resuming() -> EpisodeFacts {
        EpisodeFacts {
            position_ticks: 100,
            ..ep()
        }
    }

    fn special() -> EpisodeFacts {
        EpisodeFacts {
            special: true,
            ..ep()
        }
    }

    #[test]
    fn play_resumes_the_first_in_progress_episode() {
        let ordered = [watched(), resuming()];
        assert_eq!(
            pick_series_play(&ordered, false),
            Some((1, SeriesPlayKind::Resume))
        );
    }

    #[test]
    fn play_with_nothing_in_progress_is_the_first_unplayed_episode() {
        let ordered = [watched(), ep()];
        assert_eq!(
            pick_series_play(&ordered, false),
            Some((1, SeriesPlayKind::NextUp))
        );
    }

    #[test]
    fn play_prefers_a_later_regular_episode_over_an_earlier_unplayed_special() {
        let ordered = [special(), ep()];
        assert_eq!(
            pick_series_play(&ordered, false),
            Some((1, SeriesPlayKind::NextUp))
        );
    }

    #[test]
    fn play_falls_to_an_unplayed_special_once_every_regular_episode_is_watched() {
        let ordered = [special(), watched()];
        assert_eq!(
            pick_series_play(&ordered, false),
            Some((0, SeriesPlayKind::NextUp))
        );
    }

    #[test]
    fn play_skips_a_virtual_episode_even_with_progress() {
        let virtual_resuming = EpisodeFacts {
            is_virtual: true,
            ..resuming()
        };
        let ordered = [virtual_resuming, ep()];
        assert_eq!(
            pick_series_play(&ordered, false),
            Some((1, SeriesPlayKind::NextUp))
        );
    }

    #[test]
    fn play_resume_in_a_later_season_wins_over_earlier_unplayed_episodes() {
        let ordered = [ep(), watched(), resuming()];
        assert_eq!(
            pick_series_play(&ordered, false),
            Some((2, SeriesPlayKind::Resume))
        );
    }

    #[test]
    fn a_watched_episode_with_a_position_still_resumes() {
        let ordered = [
            ep(),
            EpisodeFacts {
                played: true,
                ..resuming()
            },
        ];
        assert_eq!(
            pick_series_play(&ordered, false),
            Some((1, SeriesPlayKind::Resume))
        );
    }

    #[test]
    fn a_fully_watched_series_replays_its_first_regular_episode() {
        let ordered = [
            EpisodeFacts {
                played: true,
                ..special()
            },
            watched(),
            watched(),
        ];
        assert_eq!(
            pick_series_play(&ordered, false),
            Some((1, SeriesPlayKind::Replay))
        );
    }

    #[test]
    fn replay_ignores_progress_and_starts_the_first_regular_episode() {
        let ordered = [special(), ep(), resuming()];
        assert_eq!(
            pick_series_play(&ordered, true),
            Some((1, SeriesPlayKind::Replay))
        );
    }

    #[test]
    fn replay_of_a_specials_only_series_starts_the_first_special() {
        let ordered = [EpisodeFacts {
            played: true,
            ..special()
        }];
        assert_eq!(
            pick_series_play(&ordered, true),
            Some((0, SeriesPlayKind::Replay))
        );
    }

    #[test]
    fn a_series_with_no_playable_episode_has_no_target() {
        let virtual_only = EpisodeFacts {
            is_virtual: true,
            ..ep()
        };
        assert_eq!(pick_series_play(&[], false), None);
        assert_eq!(pick_series_play(&[virtual_only], false), None);
        assert_eq!(pick_series_play(&[virtual_only], true), None);
    }

    // -- is_missing / season_stats: docs/24 + docs/11 §12 (ported from the Kotlin
    // CardFormatting.isMissing and DetailFormatting.seasonAvailability tests) --

    fn now() -> DateTime<Utc> {
        DateTime::parse_from_rfc3339("2026-01-15T12:00:00Z")
            .expect("fixture date")
            .with_timezone(&Utc)
    }

    /// The table the app's `CardFormatting` test reads too, so both sides agree on every format.
    #[test]
    fn is_missing_follows_the_shared_air_date_table() {
        let table = include_str!("../testdata/air-dates.tsv");
        let mut cases = 0;
        for line in table
            .lines()
            .filter(|l| !l.starts_with('#') && !l.is_empty())
        {
            let (input, expected) = line.split_once('\t').expect("input<TAB>missing");
            let date = (input != "<none>").then_some(input);
            assert_eq!(
                is_missing(date, now()),
                expected == "true",
                "air date {input:?}"
            );
            cases += 1;
        }
        assert!(cases >= 10, "the table loaded");
    }

    #[test]
    fn season_stats_count_real_episodes_and_missing_virtuals_only() {
        let missing_virtual = EpisodeFacts {
            is_virtual: true,
            missing: true,
            ..ep()
        };
        let upcoming_virtual = EpisodeFacts {
            is_virtual: true,
            ..ep()
        };
        let stats = season_stats(
            "season-1".to_string(),
            &[
                watched(),
                ep(),
                resuming(),
                missing_virtual,
                upcoming_virtual,
            ],
        );
        assert_eq!(
            stats,
            SeasonStats {
                season_id: "season-1".to_string(),
                have: 3,
                watched: 1,
                missing: 1,
                any_progress: true,
            }
        );
    }

    #[test]
    fn season_stats_progress_counts_a_played_or_started_virtual_too() {
        let played_virtual = EpisodeFacts {
            is_virtual: true,
            played: true,
            ..ep()
        };
        let stats = season_stats("s".to_string(), &[ep(), played_virtual]);
        assert_eq!((stats.have, stats.watched), (1, 0));
        assert!(stats.any_progress);
    }

    #[test]
    fn season_stats_of_a_fresh_or_empty_season_has_no_progress() {
        let fresh = season_stats("s".to_string(), &[ep(), ep()]);
        assert_eq!((fresh.have, fresh.watched, fresh.missing), (2, 0, 0));
        assert!(!fresh.any_progress);
        let empty = season_stats("e".to_string(), &[]);
        assert_eq!((empty.have, empty.watched, empty.missing), (0, 0, 0));
        assert!(!empty.any_progress);
    }
}
