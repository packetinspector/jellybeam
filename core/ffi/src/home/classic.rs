//! docs/07 §1's Classic Home: a hero over rows of shelves.

use media_cache::Mirror;

use super::blocks;
use crate::settings::Settings;
use crate::types::Card;

/// docs/07 §1: what Classic draws, already decided. `shelves` is in display order with empty
/// shelves dropped; `hero` is Continue Watching's first card, present only when that shelf
/// leads.
#[derive(uniffi::Record, Debug, Clone, PartialEq, Default)]
pub struct ClassicHome {
    pub hero: Option<Card>,
    pub shelves: Vec<HomeShelf>,
}

#[derive(uniffi::Record, Debug, Clone, PartialEq)]
pub struct HomeShelf {
    pub source: ShelfSource,
    pub cards: Vec<Card>,
}

/// Where a shelf's cards came from; Kotlin maps it to a localised title and a card treatment.
#[derive(uniffi::Enum, Debug, Clone, PartialEq, Eq)]
pub enum ShelfSource {
    ContinueWatching,
    NextUp,
    Favorites,
    /// One "Latest in `{view_name}`" shelf; the name is the server's, verbatim.
    Latest {
        view_id: String,
        view_name: String,
    },
}

pub(super) fn build(mirror: &Mirror, settings: &Settings) -> ClassicHome {
    let size = settings.home_shelf_size;
    let resume = blocks::resume(mirror, size);
    let next_up = blocks::next_up(mirror, &resume, size);
    let favorites = if settings.home_show_favorites {
        mirror.favorites(size).into_iter().map(Card::from).collect()
    } else {
        Vec::new()
    };
    assemble(resume, next_up, favorites, latest_shelves(mirror, settings))
}

/// One shelf per library in server order, skipping hidden libraries and `Channel` views,
/// whose content is never mirrored so their query would always come back empty.
fn latest_shelves(mirror: &Mirror, settings: &Settings) -> Vec<HomeShelf> {
    mirror
        .views()
        .into_iter()
        .filter(|view| {
            view.item_type != "Channel" && !settings.hidden_library_ids.contains(&view.id)
        })
        .map(|view| {
            let cards = mirror
                .latest(
                    &view.id,
                    settings.home_shelf_size,
                    settings.hide_watched_in_latest,
                )
                .into_iter()
                .map(Card::from)
                .collect();
            HomeShelf {
                source: ShelfSource::Latest {
                    view_id: view.id,
                    view_name: view.name,
                },
                cards,
            }
        })
        .collect()
}

/// docs/07 §1's order: Continue Watching, Next Up, Favorites, then each Latest shelf; an empty
/// shelf is dropped, and the hero is shelf 0's first card when shelf 0 is Continue Watching.
fn assemble(
    resume: Vec<Card>,
    next_up: Vec<Card>,
    favorites: Vec<Card>,
    latest: Vec<HomeShelf>,
) -> ClassicHome {
    let hero = resume.first().cloned();
    let shelves = [
        HomeShelf {
            source: ShelfSource::ContinueWatching,
            cards: resume,
        },
        HomeShelf {
            source: ShelfSource::NextUp,
            cards: next_up,
        },
        HomeShelf {
            source: ShelfSource::Favorites,
            cards: favorites,
        },
    ]
    .into_iter()
    .chain(latest)
    .filter(|shelf| !shelf.cards.is_empty())
    .collect();
    ClassicHome { hero, shelves }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn card(id: &str) -> Card {
        Card::sample(id, "Movie")
    }

    fn latest(view_id: &str, view_name: &str, ids: &[&str]) -> HomeShelf {
        HomeShelf {
            source: ShelfSource::Latest {
                view_id: view_id.to_string(),
                view_name: view_name.to_string(),
            },
            cards: ids.iter().map(|id| card(id)).collect(),
        }
    }

    fn sources(home: &ClassicHome) -> Vec<ShelfSource> {
        home.shelves.iter().map(|s| s.source.clone()).collect()
    }

    #[test]
    fn all_shelves_are_hidden_when_everything_is_empty() {
        let home = assemble(Vec::new(), Vec::new(), Vec::new(), Vec::new());
        assert_eq!(home, ClassicHome::default());
    }

    #[test]
    fn shelf_order_is_continue_watching_next_up_favorites_then_latest_per_view() {
        let home = assemble(
            vec![card("r1")],
            vec![card("n1")],
            vec![card("f1")],
            vec![
                latest("v1", "Movies", &["m1"]),
                latest("v2", "TV Shows", &["t1"]),
            ],
        );
        assert_eq!(
            sources(&home),
            [
                ShelfSource::ContinueWatching,
                ShelfSource::NextUp,
                ShelfSource::Favorites,
                latest("v1", "Movies", &[]).source,
                latest("v2", "TV Shows", &[]).source,
            ]
        );
    }

    #[test]
    fn a_latest_shelf_with_no_cards_is_hidden_and_others_still_show() {
        let home = assemble(
            Vec::new(),
            Vec::new(),
            Vec::new(),
            vec![
                latest("v1", "Movies", &[]),
                latest("v2", "TV Shows", &["t1"]),
            ],
        );
        assert_eq!(sources(&home), [latest("v2", "TV Shows", &[]).source]);
    }

    #[test]
    fn continue_watching_and_next_up_are_hidden_individually_when_empty() {
        let home = assemble(Vec::new(), vec![card("n1")], Vec::new(), Vec::new());
        assert_eq!(sources(&home), [ShelfSource::NextUp]);
    }

    #[test]
    fn the_hero_is_continue_watchings_first_card() {
        let home = assemble(
            vec![card("r1"), card("r2")],
            Vec::new(),
            Vec::new(),
            Vec::new(),
        );
        assert_eq!(home.hero.map(|c| c.id).as_deref(), Some("r1"));
        assert_eq!(
            home.shelves[0].cards.len(),
            2,
            "the hero card stays on its shelf"
        );
    }

    #[test]
    fn there_is_no_hero_without_continue_watching() {
        let home = assemble(
            Vec::new(),
            vec![card("n1")],
            Vec::new(),
            vec![latest("v1", "Movies", &["m1"])],
        );
        assert_eq!(home.hero, None);
    }
}
