//! Credits-aware next-up's "what episode comes next" question: a pure picker
//! ([`pick_next_episode`]) over an already-ordered list, plus a thin
//! `media_cache::Mirror` query wrapper ([`next_episode_after`]) that builds
//! that list from the series -> seasons -> episodes shape and concatenates
//! it in season order. The picker knows nothing of the mirror/seasons/series
//! -- just "the next entry after this one, skipping virtual ones".

use media_cache::{CardRow, Mirror, Sort};

use crate::types::{Card, EpisodeNeighbors};

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

/// Mirror of [`pick_next_episode`] for the opposite direction: same
/// ordering contract, returns the previous non-virtual Episode's id, `None`
/// if `current_id` isn't found or is the first non-virtual episode.
pub(crate) fn pick_previous_episode(ordered: &[EpisodeSlot], current_id: &str) -> Option<String> {
    let idx = ordered
        .iter()
        .position(|e| e.id == current_id && e.item_type == "Episode")?;
    ordered[..idx]
        .iter()
        .rev()
        .find(|e| e.item_type == "Episode" && !e.is_virtual)
        .map(|e| e.id.clone())
}

/// Shared traversal, factored out so [`crate::JellybeamCore::series_episodes`]
/// (docs/11 item 11) can reuse it: `series_id`'s seasons in `IndexNumber`
/// order, each season's episodes concatenated back-to-back. Specials sort
/// FIRST here (this owns raw mirror order, not the docs/11 button-label
/// Specials-last re-sort). `None` if any query fails, mirroring
/// [`Mirror::children_checked`]'s fail-closed contract; zero seasons is a
/// legitimate empty series, not an error.
pub(crate) fn all_episodes_of_series(mirror: &Mirror, series_id: &str) -> Option<Vec<CardRow>> {
    let seasons = mirror.children_checked(series_id, Sort::IndexNumber, 0, ALL)?;
    let mut episodes: Vec<CardRow> = Vec::new();
    for season in seasons {
        let of_season = mirror.children_checked(&season.id, Sort::IndexNumber, 0, ALL)?;
        episodes.extend(of_season);
    }
    Some(episodes)
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

/// Mirror of [`next_episode_after`] walking backward via
/// [`pick_previous_episode`]. `None` under the same conditions, substituting
/// "first episode" for "last".
pub(crate) fn previous_episode_before(mirror: &Mirror, item_id: &str) -> Option<Card> {
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

    let previous_id = pick_previous_episode(&slots, item_id)?;
    episodes
        .into_iter()
        .find(|e| e.id == previous_id)
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

    #[test]
    fn normal_previous_within_the_same_season() {
        let ordered = vec![slot("s1e1"), slot("s1e2"), slot("s1e3")];
        assert_eq!(
            pick_previous_episode(&ordered, "s1e2"),
            Some("s1e1".to_string())
        );
    }

    #[test]
    fn skips_a_virtual_episode_to_find_the_previous_real_one() {
        let ordered = vec![slot("s1e1"), virtual_slot("s1e2"), slot("s1e3")];
        assert_eq!(
            pick_previous_episode(&ordered, "s1e3"),
            Some("s1e1".to_string())
        );
    }

    #[test]
    fn crosses_a_season_boundary_backward() {
        let ordered = vec![slot("s1e1"), slot("s1e2"), slot("s2e1"), slot("s2e2")];
        assert_eq!(
            pick_previous_episode(&ordered, "s2e1"),
            Some("s1e2".to_string())
        );
    }

    #[test]
    fn crosses_a_season_boundary_backward_and_skips_a_virtual_episode() {
        let ordered = vec![
            slot("s1e1"),
            virtual_slot("s1e2"),
            slot("s2e1"),
            slot("s2e2"),
        ];
        assert_eq!(
            pick_previous_episode(&ordered, "s2e1"),
            Some("s1e1".to_string())
        );
    }

    #[test]
    fn first_episode_of_the_series_has_no_previous() {
        let ordered = vec![slot("s1e1"), slot("s1e2")];
        assert_eq!(pick_previous_episode(&ordered, "s1e1"), None);
    }

    #[test]
    fn leading_virtual_episodes_before_the_first_real_one_are_not_a_previous() {
        let ordered = vec![virtual_slot("s1e1"), slot("s1e2"), slot("s1e3")];
        assert_eq!(pick_previous_episode(&ordered, "s1e2"), None);
    }

    #[test]
    fn non_episode_input_has_no_previous() {
        let ordered = vec![
            slot("s1e1"),
            EpisodeSlot {
                id: "movie-1".to_string(),
                item_type: "Movie".to_string(),
                is_virtual: false,
            },
        ];
        assert_eq!(pick_previous_episode(&ordered, "movie-1"), None);
    }

    #[test]
    fn unknown_current_id_has_no_previous() {
        let ordered = vec![slot("s1e1"), slot("s1e2")];
        assert_eq!(pick_previous_episode(&ordered, "does-not-exist"), None);
    }
}
