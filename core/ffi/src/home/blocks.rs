//! docs/25 §4.5: content rules at least two Home layouts use, written once.

use media_cache::Mirror;

use crate::types::Card;

/// Continue Watching, most recently played first, capped at `size`.
pub(super) fn resume(mirror: &Mirror, size: u32) -> Vec<Card> {
    mirror.resume(size).into_iter().map(Card::from).collect()
}

/// Next Up without anything already in `resume` (docs/07 §1), in server order, capped at
/// `size`; over-fetches by `resume.len()` so the drops still leave a full shelf.
pub(super) fn next_up(mirror: &Mirror, resume: &[Card], size: u32) -> Vec<Card> {
    let fetch = size.saturating_add(u32::try_from(resume.len()).unwrap_or(u32::MAX));
    next_up_beside_resume(
        mirror.next_up(fetch).into_iter().map(Card::from).collect(),
        resume,
        size as usize,
    )
}

/// The same episode never sits on two rails: Next Up minus Continue Watching ids.
fn next_up_beside_resume(next_up: Vec<Card>, resume: &[Card], limit: usize) -> Vec<Card> {
    next_up
        .into_iter()
        .filter(|card| resume.iter().all(|r| r.id != card.id))
        .take(limit)
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn episode(id: &str) -> Card {
        Card::sample(id, "Episode")
    }

    fn ids(cards: Vec<Card>) -> Vec<String> {
        cards.into_iter().map(|c| c.id).collect()
    }

    #[test]
    fn next_up_beside_resume_drops_resume_ids_and_keeps_server_order() {
        let next_up = vec![episode("e3"), episode("e1"), episode("e2")];
        let resume = vec![episode("e1")];
        assert_eq!(
            ids(next_up_beside_resume(next_up, &resume, 10)),
            ["e3", "e2"]
        );
    }

    #[test]
    fn next_up_beside_resume_caps_at_limit() {
        let next_up = (0..5).map(|i| episode(&format!("e{i}"))).collect();
        assert_eq!(
            ids(next_up_beside_resume(next_up, &[], 3)),
            ["e0", "e1", "e2"]
        );
    }

    #[test]
    fn next_up_beside_resume_still_fills_to_limit_after_drops() {
        let next_up = (0..5).map(|i| episode(&format!("e{i}"))).collect();
        let resume = vec![episode("e0"), episode("e1")];
        assert_eq!(
            ids(next_up_beside_resume(next_up, &resume, 3)),
            ["e2", "e3", "e4"]
        );
    }
}
