//! Hold-to-seek ("glide") state machine (`docs/feature-dev/PRD-hold-to-seek.md`, "the PRD"):
//! holding Left/Right while the OSD is hidden enters a continuous, accelerating seek. Pure
//! decision logic -- no I/O, no clock (callers pass monotonic ms); this module never seeks, it
//! only reports the commit target for Kotlin's `seekTo` (see `core/ffi/src/glide.rs`).
//!
//! State machine (PRD §7): `Idle -> Tapped -> Gliding -> Persisting -> Idle`, with `Gliding`
//! short-circuiting to `Cancelled` ([`Glide::back`]) or a fresh `Tapped` cycle (PRD §4.3).

/// Entry threshold (PRD §4.3): held this long before a tap grows into a glide.
pub const ENTRY_HOLD_MS: u64 = 500;
/// Persist-surface lifetime after release, and refresh window (PRD §4.5).
pub const PERSIST_MS: u64 = 3000;
/// Tier 1 ease-in duration so a slightly-long tap doesn't fling the target (PRD §4.4).
pub const EASE_IN_MS: u64 = 300;
/// Dwell-ms tier boundaries, measured from glide entry (PRD §4.4's rate table).
pub const TIER_BOUNDS_MS: [u64; 3] = [800, 2000, 3500];
/// Tiers 1-3 rates, media-seconds per real second; tier 4 is computed (PRD §4.4).
pub const TIER_RATES: [f64; 3] = [6.0, 30.0, 120.0];
/// Tier 4's rate: `duration_secs / TIER4_DURATION_DIVISOR`, floored at [`TIER4_FLOOR`] (PRD §4.4).
pub const TIER4_DURATION_DIVISOR: f64 = 8.0;
/// Tier 4 never drops below tier 3's rate (PRD §4.4).
pub const TIER4_FLOOR: f64 = 120.0;
/// Forward clamp margin before EOF so a commit lands inside playable media (PRD §6.2).
pub const END_CLAMP_MARGIN_MS: u64 = 1000;

/// Which key started the glide; the opposite key ends it and starts a new one (PRD §4.3).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Direction {
    Back,
    Forward,
}

/// The state machine's phase; see the module doc for the transition diagram.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Phase {
    Idle,
    Tapped,
    Gliding,
    Persisting,
}

/// Whether the target sits at a traversal boundary (PRD §6); `Start`/`End` are mutually exclusive.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Clamp {
    None,
    Start,
    End,
}

/// What a caller must do after a state-changing call; `None` means no caller action needed.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Outcome {
    None,
    /// Issue exactly one `seekTo(target_ms)` (PRD §4.5); `end_clamped` drives PRD §6.2's
    /// pause-not-end behavior on the Kotlin side.
    Commit {
        target_ms: u64,
        end_clamped: bool,
    },
    /// No seek: target discarded, playback continues undisturbed (PRD §4.6).
    Cancelled,
}

/// Everything the glide surface (PRD §5) needs to render one frame; `tier`/`multiplier` are `0`
/// outside [`Phase::Gliding`].
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Preview {
    pub phase: Phase,
    pub direction: Option<Direction>,
    pub target_ms: u64,
    /// Signed, measured from the pre-glide position, not glide entry (PRD §5.3).
    pub delta_ms: i64,
    /// `1..=4`, or `0` outside [`Phase::Gliding`].
    pub tier: u8,
    /// Rate rounded for the speed badge (PRD §5.3), or `0` outside [`Phase::Gliding`].
    pub multiplier: u32,
    pub clamp: Clamp,
    /// Index into `chapter_starts_ms`, resolved against the target, not true position
    /// (PRD §7/§8).
    pub chapter_index: Option<u32>,
}

/// One hold-to-seek gesture's state (one per key sequence, via `core/ffi/src/glide.rs`), never
/// persisted; `target_ms` stays `f64` internally to avoid losing fractional-ms precision.
pub struct Glide {
    duration_ms: u64,
    chapter_starts_ms: Vec<u64>,

    phase: Phase,
    direction: Option<Direction>,

    /// Pre-glide position that [`Preview::delta_ms`] is measured from; a reversal re-bases it
    /// (PRD §8).
    baseline_ms: u64,
    /// `Tapped`-phase key-down timestamp; [`Glide::tick`] checks it against [`ENTRY_HOLD_MS`].
    down_at_ms: u64,
    /// Whether the current `Tapped` cycle was entered from `Persisting` (PRD §4.5).
    resume_persisting: bool,

    /// Dwell start; tier-1 dwell begins at zero at glide entry, not at key-down.
    entered_at_ms: u64,
    /// Dwell as of the last tick, so each tick only integrates the newly-elapsed slice.
    last_dwell_ms: u64,
    current_tier: u8,
    current_rate: f64,

    /// Target position, absolute media ms; frozen from commit to the next cycle.
    target_ms: f64,

    persist_until_ms: u64,
}

/// The ramp (PRD §4.4): tier and rate for a given dwell; `duration_ms` only matters for tier 4.
pub fn rate_at(dwell_ms: u64, duration_ms: u64) -> (u8, f64) {
    if dwell_ms < TIER_BOUNDS_MS[0] {
        let eased_dwell = dwell_ms.min(EASE_IN_MS) as f64;
        (1, TIER_RATES[0] * eased_dwell / EASE_IN_MS as f64)
    } else if dwell_ms < TIER_BOUNDS_MS[1] {
        (2, TIER_RATES[1])
    } else if dwell_ms < TIER_BOUNDS_MS[2] {
        (3, TIER_RATES[2])
    } else {
        (4, tier4_rate(duration_ms))
    }
}

fn tier4_rate(duration_ms: u64) -> f64 {
    let duration_secs = duration_ms as f64 / 1000.0;
    (duration_secs / TIER4_DURATION_DIVISOR).max(TIER4_FLOOR)
}

/// Cumulative travel (media ms) from dwell `0` to `x`, the antiderivative of [`rate_at`]'s
/// piecewise rate; [`travel_ms`] subtracts two evaluations so it stays exactly additive (PRD §8).
fn cumulative_travel_ms(dwell_ms: u64, duration_ms: u64) -> f64 {
    let x = dwell_ms as f64;
    let ease_end = EASE_IN_MS as f64;
    let t1 = TIER_BOUNDS_MS[0] as f64;
    let t2 = TIER_BOUNDS_MS[1] as f64;
    let t3 = TIER_BOUNDS_MS[2] as f64;

    // Ease-in [0, ease_end]: integral of TIER_RATES[0]*(t/ease_end) dt.
    let ease_x = x.min(ease_end);
    let mut total = TIER_RATES[0] * ease_x * ease_x / (2.0 * ease_end);
    if x <= ease_end {
        return total;
    }

    // Tier 1's flat remainder [ease_end, t1).
    let seg1_x = x.min(t1);
    total += TIER_RATES[0] * (seg1_x - ease_end);
    if x <= t1 {
        return total;
    }

    // Tier 2 [t1, t2).
    let seg2_x = x.min(t2);
    total += TIER_RATES[1] * (seg2_x - t1);
    if x <= t2 {
        return total;
    }

    // Tier 3 [t2, t3).
    let seg3_x = x.min(t3);
    total += TIER_RATES[2] * (seg3_x - t2);
    if x <= t3 {
        return total;
    }

    // Tier 4 [t3, x): constant rate, plain rate * length.
    total += tier4_rate(duration_ms) * (x - t3);
    total
}

/// Forward traversal limit (PRD §6.2/§10), also Kotlin's tap-seek clamp (PRD §4.2).
pub fn end_clamp_ms(duration_ms: u64) -> u64 {
    duration_ms.saturating_sub(END_CLAMP_MARGIN_MS)
}

/// Exact media-ms travelled between two dwell points (PRD §4.4/§8), not sampled, so a tick
/// spanning a tier boundary doesn't over/under-shoot.
pub fn travel_ms(dwell_from: u64, dwell_to: u64, duration_ms: u64) -> f64 {
    cumulative_travel_ms(dwell_to, duration_ms) - cumulative_travel_ms(dwell_from, duration_ms)
}

impl Glide {
    /// A fresh, idle gesture for one item; `chapter_starts_ms` need not be pre-sorted, see
    /// [`Glide::chapter_index_for`].
    pub fn new(duration_ms: u64, chapter_starts_ms: Vec<u64>) -> Self {
        Glide {
            duration_ms,
            chapter_starts_ms,
            phase: Phase::Idle,
            direction: None,
            baseline_ms: 0,
            down_at_ms: 0,
            resume_persisting: false,
            entered_at_ms: 0,
            last_dwell_ms: 0,
            current_tier: 0,
            current_rate: 0.0,
            target_ms: 0.0,
            persist_until_ms: 0,
        }
    }

    /// Kotlin already issued the tap seek (PRD §4.2); this starts the timer deciding whether it
    /// grows into a glide.
    /// - `Idle`/`Persisting` -> `Tapped` (PRD §4.5, remembers a `Persisting` origin for `key_up`).
    /// - `Gliding`, opposite direction -> commits now (PRD §4.3), then a fresh `Tapped` cycle.
    /// - `Gliding`, same direction -> ignored.
    /// - `Tapped` -> ignored: a key already down cannot restart.
    pub fn key_down(
        &mut self,
        direction: Direction,
        position_before_ms: u64,
        tap_target_ms: u64,
        now_ms: u64,
    ) -> Outcome {
        match self.phase {
            Phase::Idle | Phase::Persisting => {
                let resume_persisting = self.phase == Phase::Persisting;
                self.start_tapped(
                    direction,
                    position_before_ms,
                    tap_target_ms,
                    now_ms,
                    resume_persisting,
                );
                Outcome::None
            }
            Phase::Gliding => {
                if self.direction == Some(direction) {
                    Outcome::None
                } else {
                    let outcome = self.commit_outcome();
                    self.start_tapped(direction, position_before_ms, tap_target_ms, now_ms, false);
                    outcome
                }
            }
            Phase::Tapped => Outcome::None,
        }
    }

    fn start_tapped(
        &mut self,
        direction: Direction,
        position_before_ms: u64,
        tap_target_ms: u64,
        now_ms: u64,
        resume_persisting: bool,
    ) {
        self.phase = Phase::Tapped;
        self.direction = Some(direction);
        self.baseline_ms = position_before_ms;
        self.down_at_ms = now_ms;
        self.target_ms = tap_target_ms as f64;
        self.resume_persisting = resume_persisting;
    }

    /// Advances to `now_ms`, returns the [`Preview`] (PRD §9: one FFI call per tick).
    pub fn tick(&mut self, now_ms: u64) -> Preview {
        match self.phase {
            Phase::Tapped => {
                if now_ms.saturating_sub(self.down_at_ms) >= ENTRY_HOLD_MS {
                    self.phase = Phase::Gliding;
                    self.entered_at_ms = self.down_at_ms + ENTRY_HOLD_MS;
                    self.last_dwell_ms = 0;
                    let (tier, rate) = rate_at(0, self.duration_ms);
                    self.current_tier = tier;
                    self.current_rate = rate;
                }
            }
            Phase::Gliding => {
                let dwell_now = now_ms.saturating_sub(self.entered_at_ms);
                let travelled = travel_ms(self.last_dwell_ms, dwell_now, self.duration_ms);
                let signed_travel = match self.direction {
                    Some(Direction::Forward) => travelled,
                    Some(Direction::Back) => -travelled,
                    None => 0.0,
                };
                self.target_ms = (self.target_ms + signed_travel).clamp(0.0, self.upper_clamp_ms());
                self.last_dwell_ms = dwell_now;
                let (tier, rate) = rate_at(dwell_now, self.duration_ms);
                self.current_tier = tier;
                self.current_rate = rate;
            }
            Phase::Persisting => {
                if now_ms >= self.persist_until_ms {
                    self.phase = Phase::Idle;
                    self.direction = None;
                }
            }
            Phase::Idle => {}
        }
        self.preview()
    }

    /// Force back to `Idle` from any phase, no [`Outcome`] (PRD §4.1/§5); `target_ms` untouched.
    pub fn reset(&mut self) {
        self.phase = Phase::Idle;
        self.direction = None;
    }

    /// Chapters can arrive after the gesture starts; swaps them in without disturbing phase/target.
    pub fn set_chapter_starts(&mut self, chapter_starts_ms: Vec<u64>) {
        self.chapter_starts_ms = chapter_starts_ms;
    }

    /// Release (PRD §4.5): `Tapped` returns to `Persisting` if entered from there, else `Idle`
    /// (no seek, already fired at key-down); `Gliding` commits and enters `Persisting`.
    pub fn key_up(&mut self, now_ms: u64) -> Outcome {
        match self.phase {
            Phase::Tapped => {
                if self.resume_persisting {
                    self.phase = Phase::Persisting;
                    self.persist_until_ms = now_ms + PERSIST_MS;
                } else {
                    self.phase = Phase::Idle;
                    self.direction = None;
                }
                Outcome::None
            }
            Phase::Gliding => {
                let outcome = self.commit_outcome();
                self.phase = Phase::Persisting;
                self.persist_until_ms = now_ms + PERSIST_MS;
                outcome
            }
            Phase::Idle | Phase::Persisting => Outcome::None,
        }
    }

    fn commit_outcome(&self) -> Outcome {
        Outcome::Commit {
            target_ms: self.rounded_target_ms(),
            end_clamped: self.clamp_state() == Clamp::End,
        }
    }

    /// Cancel (PRD §4.6): only `Gliding` cancels; `Back` during `Persisting` doesn't undo the
    /// seek.
    pub fn back(&mut self, now_ms: u64) -> Outcome {
        let _ = now_ms;
        if self.phase == Phase::Gliding {
            self.phase = Phase::Idle;
            self.direction = None;
            Outcome::Cancelled
        } else {
            Outcome::None
        }
    }

    /// Tap/hold during `Persisting` (PRD §4.5) moves the target and resets the 3s timer.
    pub fn tap(&mut self, tap_target_ms: u64, now_ms: u64) {
        if self.phase == Phase::Persisting {
            self.target_ms = tap_target_ms as f64;
            self.persist_until_ms = now_ms + PERSIST_MS;
        }
    }

    /// Whether the glide surface (PRD §5) should be on screen at all.
    pub fn surface_visible(&self) -> bool {
        matches!(self.phase, Phase::Gliding | Phase::Persisting)
    }

    /// Everything the glide surface needs for one frame; see [`Preview`]'s field docs.
    pub fn preview(&self) -> Preview {
        let target_ms = self.rounded_target_ms();
        let delta_ms = target_ms as i64 - self.baseline_ms as i64;
        let (tier, multiplier) = if self.phase == Phase::Gliding {
            (self.current_tier, self.current_rate.round() as u32)
        } else {
            (0, 0)
        };
        Preview {
            phase: self.phase,
            direction: self.direction,
            target_ms,
            delta_ms,
            tier,
            multiplier,
            clamp: self.clamp_state(),
            chapter_index: self.chapter_index_for(target_ms),
        }
    }

    fn rounded_target_ms(&self) -> u64 {
        self.target_ms.round().max(0.0) as u64
    }

    /// [`end_clamp_ms`] for this duration; a shorter-than-margin item reads as `Start`, not `End`.
    fn upper_clamp_ms(&self) -> f64 {
        end_clamp_ms(self.duration_ms) as f64
    }

    /// PRD §6: `Start` iff target is `0`; `End` iff at the upper clamp and item exceeds the
    /// margin.
    fn clamp_state(&self) -> Clamp {
        let upper = self.upper_clamp_ms();
        if self.target_ms <= 0.0 {
            Clamp::Start
        } else if self.duration_ms > END_CLAMP_MARGIN_MS && self.target_ms >= upper {
            Clamp::End
        } else {
            Clamp::None
        }
    }

    /// Index of the entry with the greatest start `<= target_ms` (not true position, PRD §7/§8);
    /// not assumed sorted.
    fn chapter_index_for(&self, target_ms: u64) -> Option<u32> {
        self.chapter_starts_ms
            .iter()
            .enumerate()
            .filter(|&(_, &start)| start <= target_ms)
            .fold(None, |best: Option<(u32, u64)>, (i, &start)| match best {
                Some((_, best_start)) if start <= best_start => best,
                _ => Some((i as u32, start)),
            })
            .map(|(i, _)| i)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const HOUR_MS: u64 = 3_600_000;
    const TWO_HOUR_MS: u64 = 2 * HOUR_MS;

    fn glide() -> Glide {
        Glide::new(TWO_HOUR_MS, Vec::new())
    }

    fn glide_with_chapters(duration_ms: u64, chapters: &[u64]) -> Glide {
        Glide::new(duration_ms, chapters.to_vec())
    }

    // -- rate_at tiers -----------------------------------------------------

    #[test]
    fn rate_at_tier_boundaries() {
        assert_eq!(rate_at(799, TWO_HOUR_MS).0, 1);
        assert_eq!(rate_at(801, TWO_HOUR_MS), (2, 30.0));
        assert_eq!(rate_at(1999, TWO_HOUR_MS), (2, 30.0));
        assert_eq!(rate_at(2001, TWO_HOUR_MS), (3, 120.0));
        assert_eq!(rate_at(3499, TWO_HOUR_MS), (3, 120.0));
        assert_eq!(rate_at(3501, TWO_HOUR_MS).0, 4);
    }

    #[test]
    fn rate_at_tier1_eases_in_linearly() {
        assert_eq!(rate_at(0, TWO_HOUR_MS), (1, 0.0));
        assert_eq!(rate_at(150, TWO_HOUR_MS), (1, 3.0));
        assert_eq!(rate_at(300, TWO_HOUR_MS), (1, 6.0));
    }

    #[test]
    fn rate_at_tier4_floors_at_120_for_short_runtimes() {
        // 10-min episode: 600s/8=75, floored to 120; 20-min's 150 exceeds the floor.
        let ten_min_ms = 10 * 60 * 1000;
        assert_eq!(rate_at(4_000, ten_min_ms), (4, 120.0));

        let twenty_min_ms = 20 * 60 * 1000;
        assert_eq!(rate_at(4_000, twenty_min_ms), (4, 150.0));

        let three_hour_ms = 3 * HOUR_MS;
        assert_eq!(rate_at(4_000, three_hour_ms), (4, 1350.0));
    }

    #[test]
    fn tier4_crosses_a_full_runtime_in_about_8_seconds() {
        for duration_ms in [22 * 60 * 1000, 3 * 60 * 60 * 1000] {
            let travelled = travel_ms(3_500, 3_500 + 8_000, duration_ms);
            let duration = duration_ms as f64;
            let tolerance = duration * 0.01;
            assert!(
                (travelled - duration).abs() <= tolerance,
                "duration_ms={duration_ms}: travelled={travelled}, want ~{duration}"
            );
        }
    }

    #[test]
    fn travel_ms_is_additive() {
        let a = travel_ms(0, 5_000, TWO_HOUR_MS);
        let b = travel_ms(0, 1_234, TWO_HOUR_MS) + travel_ms(1_234, 5_000, TWO_HOUR_MS);
        assert!((a - b).abs() < 1e-6, "a={a}, b={b}");
    }

    // -- entry timing --------------------------------------------------

    #[test]
    fn tapped_to_gliding_enters_exactly_at_500ms() {
        let mut g = glide();
        g.key_down(Direction::Forward, 100_000, 110_000, 0);
        g.tick(499);
        assert_eq!(g.preview().phase, Phase::Tapped);
        g.tick(500);
        assert_eq!(g.preview().phase, Phase::Gliding);
    }

    #[test]
    fn tap_then_release_under_500ms_never_shows_the_surface() {
        let mut g = glide();
        assert_eq!(
            g.key_down(Direction::Forward, 100_000, 110_000, 0),
            Outcome::None
        );
        g.tick(200);
        assert!(!g.surface_visible());
        assert_eq!(g.key_up(200), Outcome::None);
        assert_eq!(g.preview().phase, Phase::Idle);
        assert!(!g.surface_visible());
    }

    // -- delta -----------------------------------------------------------

    #[test]
    fn delta_is_measured_from_the_pre_glide_position() {
        let mut g = glide();
        g.key_down(Direction::Forward, 100_000, 110_000, 0);
        assert_eq!(g.preview().delta_ms, 10_000);
        g.tick(500);
        g.tick(1_000);
        assert!(g.preview().delta_ms > 10_000);
    }

    #[test]
    fn delta_is_signed_for_both_directions() {
        let mut forward = glide();
        forward.key_down(Direction::Forward, 100_000, 110_000, 0);
        forward.tick(500);
        forward.tick(1_000);
        assert!(forward.preview().delta_ms > 0);

        let mut back = glide();
        back.key_down(Direction::Back, 100_000, 90_000, 0);
        back.tick(500);
        back.tick(1_000);
        assert!(back.preview().delta_ms < 0);
    }

    // -- clamping ----------------------------------------------------------

    #[test]
    fn start_clamp_holds_at_zero() {
        let mut g = glide();
        g.key_down(Direction::Back, 3_000, 0, 0);
        g.tick(500);
        // Hold long enough to run well past zero.
        g.tick(20_000);
        let preview = g.preview();
        assert_eq!(preview.target_ms, 0);
        assert_eq!(preview.clamp, Clamp::Start);

        // Further ticks stay pinned at zero.
        g.tick(40_000);
        let preview = g.preview();
        assert_eq!(preview.target_ms, 0);
        assert_eq!(preview.clamp, Clamp::Start);
    }

    #[test]
    fn end_clamp_reports_end_clamped_on_commit() {
        let mut g = glide();
        let near_end = TWO_HOUR_MS - 2_000;
        g.key_down(Direction::Forward, near_end, near_end, 0);
        g.tick(500);
        g.tick(60_000);
        let preview = g.preview();
        assert_eq!(preview.clamp, Clamp::End);
        assert_eq!(preview.target_ms, TWO_HOUR_MS - END_CLAMP_MARGIN_MS);

        match g.key_up(60_000) {
            Outcome::Commit {
                target_ms,
                end_clamped,
            } => {
                assert_eq!(target_ms, TWO_HOUR_MS - END_CLAMP_MARGIN_MS);
                assert!(end_clamped);
            }
            other => panic!("expected Commit, got {other:?}"),
        }
    }

    // -- cancel / reversal ---------------------------------------------

    #[test]
    fn back_during_glide_cancels_with_no_outcome_on_release() {
        let mut g = glide();
        g.key_down(Direction::Forward, 100_000, 110_000, 0);
        g.tick(500);
        g.tick(1_000);
        assert_eq!(g.back(1_000), Outcome::Cancelled);
        assert_eq!(g.preview().phase, Phase::Idle);
        assert_eq!(g.key_up(1_000), Outcome::None);
    }

    #[test]
    fn opposite_direction_key_down_commits_and_starts_a_new_tapped_cycle() {
        let mut g = glide();
        g.key_down(Direction::Forward, 100_000, 110_000, 0);
        g.tick(500);
        g.tick(1_000);

        let outcome = g.key_down(Direction::Back, 200_000, 190_000, 1_000);
        match outcome {
            Outcome::Commit { .. } => {}
            other => panic!("expected Commit, got {other:?}"),
        }
        assert_eq!(g.preview().phase, Phase::Tapped);
        assert_eq!(g.preview().direction, Some(Direction::Back));
        // The new cycle's delta is re-based on its own position_before_ms.
        assert_eq!(g.preview().delta_ms, -10_000);
    }

    #[test]
    fn same_direction_key_down_while_gliding_is_ignored() {
        let mut g = glide();
        g.key_down(Direction::Forward, 100_000, 110_000, 0);
        g.tick(500);
        g.tick(1_000);
        let preview_before = g.preview();
        assert_eq!(
            g.key_down(Direction::Forward, 100_000, 110_000, 1_000),
            Outcome::None
        );
        assert_eq!(g.preview().phase, Phase::Gliding);
        assert_eq!(g.preview().target_ms, preview_before.target_ms);
    }

    // -- chapters ------------------------------------------------------

    #[test]
    fn chapter_index_resolves_against_the_target() {
        let mut g = glide_with_chapters(TWO_HOUR_MS, &[0, 600_000, 1_200_000, 1_800_000]);
        g.key_down(Direction::Forward, 500_000, 610_000, 0);
        // Tapped, not yet gliding: target already past the second chapter's start.
        assert_eq!(g.preview().chapter_index, Some(1));
    }

    #[test]
    fn chapter_index_for_picks_greatest_qualifying_start_not_greatest_index() {
        // Deliberately unsorted: index order must not matter.
        let g = glide_with_chapters(TWO_HOUR_MS, &[0, 600_000, 300_000]);
        assert_eq!(g.chapter_index_for(700_000), Some(1));
        assert_eq!(g.chapter_index_for(350_000), Some(2));
        assert_eq!(g.chapter_index_for(100), Some(0));
        assert_eq!(g.chapter_index_for(0), Some(0));
        let none_qualify = glide_with_chapters(TWO_HOUR_MS, &[500, 900]);
        assert_eq!(none_qualify.chapter_index_for(100), None);
    }

    // -- persistence -----------------------------------------------------

    #[test]
    fn persisting_expires_at_exactly_3000ms_and_tap_resets_it() {
        let mut g = glide();
        g.key_down(Direction::Forward, 100_000, 110_000, 0);
        g.tick(500);
        g.tick(1_000);
        assert_eq!(
            g.key_up(1_000),
            Outcome::Commit {
                target_ms: g.preview().target_ms,
                end_clamped: false,
            }
        );
        assert_eq!(g.preview().phase, Phase::Persisting);

        g.tick(1_000 + PERSIST_MS - 1);
        assert!(g.surface_visible());
        assert_eq!(g.preview().phase, Phase::Persisting);

        g.tick(1_000 + PERSIST_MS);
        assert!(!g.surface_visible());
        assert_eq!(g.preview().phase, Phase::Idle);

        // tap() during Persisting resets the window.
        let mut g2 = glide();
        g2.key_down(Direction::Forward, 100_000, 110_000, 0);
        g2.tick(500);
        g2.tick(1_000);
        g2.key_up(1_000);
        g2.tap(120_000, 3_000);
        assert_eq!(g2.preview().target_ms, 120_000);
        g2.tick(3_000 + PERSIST_MS - 1);
        assert!(g2.surface_visible());
        g2.tick(3_000 + PERSIST_MS);
        assert!(!g2.surface_visible());
    }

    #[test]
    fn key_down_during_persisting_then_quick_release_returns_to_persisting() {
        let mut g = glide();
        g.key_down(Direction::Forward, 100_000, 110_000, 0);
        g.tick(500);
        g.tick(1_000);
        g.key_up(1_000);
        assert_eq!(g.preview().phase, Phase::Persisting);

        // Fresh tap during the persistence window, released before ENTRY_HOLD_MS.
        assert_eq!(
            g.key_down(Direction::Forward, 110_000, 120_000, 1_100),
            Outcome::None
        );
        assert_eq!(g.preview().phase, Phase::Tapped);
        assert_eq!(g.key_up(1_200), Outcome::None);
        // Fresh 3s window from this release, not the original one.
        assert_eq!(g.preview().phase, Phase::Persisting);
        g.tick(1_200 + PERSIST_MS - 1);
        assert!(g.surface_visible());
        g.tick(1_200 + PERSIST_MS);
        assert!(!g.surface_visible());
    }

    // -- end_clamp_ms ----------------------------------------------------

    #[test]
    fn end_clamp_ms_is_zero_at_or_below_the_margin() {
        assert_eq!(end_clamp_ms(0), 0);
        assert_eq!(end_clamp_ms(END_CLAMP_MARGIN_MS), 0);
        assert_eq!(end_clamp_ms(END_CLAMP_MARGIN_MS + 1), 1);
        assert_eq!(end_clamp_ms(TWO_HOUR_MS), TWO_HOUR_MS - END_CLAMP_MARGIN_MS);
    }

    // -- reset -------------------------------------------------------------

    #[test]
    fn reset_returns_to_idle_from_any_phase_with_no_outcome() {
        // From Tapped.
        let mut g = glide();
        g.key_down(Direction::Forward, 100_000, 110_000, 0);
        g.reset();
        assert_eq!(g.preview().phase, Phase::Idle);
        assert!(!g.surface_visible());
        assert_eq!(g.key_up(0), Outcome::None);
        assert_eq!(g.tick(1), g.preview());
        assert_eq!(g.preview().phase, Phase::Idle);

        // From Gliding.
        let mut g = glide();
        g.key_down(Direction::Forward, 100_000, 110_000, 0);
        g.tick(500);
        g.tick(1_000);
        assert_eq!(g.preview().phase, Phase::Gliding);
        g.reset();
        assert_eq!(g.preview().phase, Phase::Idle);
        assert!(!g.surface_visible());
        assert_eq!(g.key_up(1_000), Outcome::None);
        assert_eq!(g.tick(2_000), g.preview());
        assert_eq!(g.preview().phase, Phase::Idle);

        // From Persisting.
        let mut g = glide();
        g.key_down(Direction::Forward, 100_000, 110_000, 0);
        g.tick(500);
        g.tick(1_000);
        g.key_up(1_000);
        assert_eq!(g.preview().phase, Phase::Persisting);
        g.reset();
        assert_eq!(g.preview().phase, Phase::Idle);
        assert!(!g.surface_visible());
        assert_eq!(g.key_up(1_000), Outcome::None);
        assert_eq!(g.tick(4_000), g.preview());
        assert_eq!(g.preview().phase, Phase::Idle);
    }

    // -- set_chapter_starts ------------------------------------------------

    #[test]
    fn set_chapter_starts_takes_effect_mid_gesture() {
        let mut g = glide_with_chapters(TWO_HOUR_MS, &[0]);
        g.key_down(Direction::Forward, 500_000, 610_000, 0);
        g.tick(500);
        g.tick(1_000);
        assert_eq!(g.preview().phase, Phase::Gliding);
        assert_eq!(g.preview().chapter_index, Some(0));

        g.set_chapter_starts(vec![0, 600_000]);
        assert_eq!(g.preview().chapter_index, Some(1));
    }

    // -- tick's return value -------------------------------------------

    #[test]
    fn tick_returns_the_post_tick_preview() {
        let mut g = glide();
        g.key_down(Direction::Forward, 100_000, 110_000, 0);
        assert_eq!(g.tick(500), g.preview());
        assert_eq!(g.tick(1_000), g.preview());
    }
}
