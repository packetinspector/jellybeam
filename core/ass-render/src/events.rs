//! Per-item dialogue budget. The track prunes events that ended [`PRUNE_DELAY_MS`] ago, which bounds
//! a well-formed track to a playback window; this caps what may be live in that window, so a
//! malformed or hostile track can't grow memory for the rest of the item.

use std::cmp::Reverse;
use std::collections::BinaryHeap;

use substation::InputLimits;

/// The track frees events this long after they end; seeking back further re-delivers them.
pub const PRUNE_DELAY_MS: i64 = 120_000;
/// Under input pressure a track frees events as soon as they end: a seek back re-delivers them, and a
/// dense track (thousands of karaoke events a second) needs the room for the lines about to show.
pub const PRESSURE_PRUNE_DELAY_MS: i64 = 0;
/// A track header (styles, plus any `[Fonts]` it embeds) larger than this is not loaded.
pub const MAX_HEADER_BYTES: usize = 32 << 20;
/// One event larger than this is skipped; real lines, drawings included, are far smaller.
pub const MAX_EVENT_BYTES: usize = 256 << 10;
/// Event bytes allowed live at once, across the item's tracks.
pub const MAX_LIVE_BYTES: usize = 32 << 20;

#[derive(Debug, Default)]
pub struct EventBudget {
    /// (end time, bytes) of every accepted event the track may still hold.
    live: BinaryHeap<Reverse<(i64, usize)>>,
    live_bytes: usize,
    skipped: u32,
}

impl EventBudget {
    /// Whether an event with `bytes` of payload may be loaded at media time `now_ms`; one that may
    /// not is counted as skipped.
    pub fn fits(&mut self, bytes: usize, now_ms: i64) -> bool {
        self.prune(now_ms - PRUNE_DELAY_MS);
        let fits = bytes <= MAX_EVENT_BYTES && self.live_bytes + bytes <= MAX_LIVE_BYTES;
        if !fits {
            self.skipped = self.skipped.saturating_add(1);
        }
        fits
    }

    /// Counts an event the track kept, until it ends at `end_ms`.
    pub fn record(&mut self, end_ms: i64, bytes: usize) {
        self.live.push(Reverse((end_ms, bytes)));
        self.live_bytes += bytes;
    }

    #[cfg(test)]
    fn admit(&mut self, end_ms: i64, bytes: usize, now_ms: i64) -> bool {
        let fits = self.fits(bytes, now_ms);
        if fits {
            self.record(end_ms, bytes);
        }
        fits
    }

    /// Stops counting events that ended before `deadline`, as the track frees them.
    pub fn prune(&mut self, deadline: i64) {
        while let Some(&Reverse((end, size))) = self.live.peek() {
            if end >= deadline {
                break;
            }
            self.live.pop();
            self.live_bytes -= size;
        }
    }

    pub const fn skipped(&self) -> u32 {
        self.skipped
    }
}

/// When a track holds three quarters of what its input limits allow, the deadline to free its ended
/// events by: keeping them for seeks back must never refuse the lines about to show.
pub fn pressure_prune_deadline(
    events: usize,
    bytes: usize,
    limits: &InputLimits,
    now_ms: i64,
) -> Option<i64> {
    (events.saturating_mul(4) >= limits.events.saturating_mul(3)
        || bytes.saturating_mul(4) >= limits.retained_bytes.saturating_mul(3))
    .then_some(now_ms - PRESSURE_PRUNE_DELAY_MS)
}

/// Whether a just-loaded event shows within `within_ms` of `now_ms` and so needs a redraw now; later
/// ones are drawn by the regular tick once they start, so a dense track can't force a redraw per line.
pub const fn shows_within(start_ms: i64, end_ms: i64, now_ms: i64, within_ms: i64) -> bool {
    start_ms <= now_ms + within_ms && end_ms > now_ms
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn oversized_events_are_skipped() {
        let mut b = EventBudget::default();
        assert!(b.admit(1_000, MAX_EVENT_BYTES, 0));
        assert!(!b.admit(1_000, MAX_EVENT_BYTES + 1, 0));
        assert_eq!(b.skipped(), 1);
    }

    #[test]
    fn live_bytes_cap_then_free_once_pruned() {
        let mut b = EventBudget::default();
        let chunk = MAX_EVENT_BYTES;
        let fit = MAX_LIVE_BYTES / chunk;
        for i in 0..fit {
            assert!(b.admit(10_000 + i as i64, chunk, 0), "event {i}");
        }
        assert!(!b.admit(20_000, chunk, 0), "window full");
        // Once playback is past the prune window, those events no longer count.
        assert!(b.admit(400_000, chunk, 10_000 + PRUNE_DELAY_MS + fit as i64));
        assert_eq!(b.skipped(), 1);
    }

    #[test]
    fn events_inside_the_prune_window_stay_counted() {
        let mut b = EventBudget::default();
        for _ in 0..MAX_LIVE_BYTES / MAX_EVENT_BYTES {
            assert!(b.admit(5_000, MAX_EVENT_BYTES, 0));
        }
        assert!(
            !b.admit(6_000, 1, 5_000 + PRUNE_DELAY_MS),
            "still inside the window"
        );
        assert!(b.admit(6_000, 1, 5_000 + PRUNE_DELAY_MS + 1));
    }

    fn limits() -> InputLimits {
        InputLimits {
            block_bytes: 1,
            line_bytes: 1,
            retained_bytes: 1000,
            events: 100,
            styles: 1,
        }
    }

    #[test]
    fn a_track_near_its_limits_frees_ended_events_early() {
        assert_eq!(pressure_prune_deadline(74, 0, &limits(), 60_000), None);
        assert_eq!(
            pressure_prune_deadline(75, 0, &limits(), 60_000),
            Some(60_000 - PRESSURE_PRUNE_DELAY_MS)
        );
        assert_eq!(
            pressure_prune_deadline(1, 750, &limits(), 60_000),
            Some(60_000 - PRESSURE_PRUNE_DELAY_MS)
        );
    }

    #[test]
    fn pruning_frees_budget_for_new_events() {
        let mut b = EventBudget::default();
        for _ in 0..MAX_LIVE_BYTES / MAX_EVENT_BYTES {
            assert!(b.admit(5_000, MAX_EVENT_BYTES, 0));
        }
        assert!(!b.admit(9_000, 1, 6_000));
        b.prune(5_001);
        assert!(b.admit(9_000, 1, 6_000));
    }

    #[test]
    fn only_lines_showing_now_or_next_force_a_redraw() {
        assert!(shows_within(1_000, 2_000, 1_500, 40), "showing");
        assert!(
            shows_within(1_530, 2_000, 1_500, 40),
            "starts before the next frame"
        );
        assert!(
            !shows_within(5_000, 6_000, 1_500, 40),
            "later: the tick draws it"
        );
        assert!(!shows_within(0, 1_000, 1_500, 40), "already over");
    }
}
