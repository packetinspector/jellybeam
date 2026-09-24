//! Hold-to-seek FFI surface (`docs/feature-dev/PRD-hold-to-seek.md`): a
//! `uniffi::Object` wrapping one `playback_policy::glide::Glide` per
//! gesture (scoped to one key-down-to-persistence-expiry cycle, not the
//! player session). Every method is a lock, a delegate, and a type
//! conversion -- no I/O, safe to call every frame tick.

use std::sync::{Arc, Mutex, MutexGuard};

use playback_policy::glide::{Clamp, Direction, Glide, Outcome, Phase, Preview};

#[derive(uniffi::Enum, Debug, Clone, Copy, PartialEq, Eq)]
pub enum GlideDirection {
    Back,
    Forward,
}

impl From<GlideDirection> for Direction {
    fn from(direction: GlideDirection) -> Self {
        match direction {
            GlideDirection::Back => Direction::Back,
            GlideDirection::Forward => Direction::Forward,
        }
    }
}

impl From<Direction> for GlideDirection {
    fn from(direction: Direction) -> Self {
        match direction {
            Direction::Back => GlideDirection::Back,
            Direction::Forward => GlideDirection::Forward,
        }
    }
}

#[derive(uniffi::Enum, Debug, Clone, Copy, PartialEq, Eq)]
pub enum GlidePhase {
    Idle,
    Tapped,
    Gliding,
    Persisting,
}

impl From<Phase> for GlidePhase {
    fn from(phase: Phase) -> Self {
        match phase {
            Phase::Idle => GlidePhase::Idle,
            Phase::Tapped => GlidePhase::Tapped,
            Phase::Gliding => GlidePhase::Gliding,
            Phase::Persisting => GlidePhase::Persisting,
        }
    }
}

#[derive(uniffi::Enum, Debug, Clone, Copy, PartialEq, Eq)]
pub enum GlideClamp {
    None,
    Start,
    End,
}

impl From<Clamp> for GlideClamp {
    fn from(clamp: Clamp) -> Self {
        match clamp {
            Clamp::None => GlideClamp::None,
            Clamp::Start => GlideClamp::Start,
            Clamp::End => GlideClamp::End,
        }
    }
}

/// Mirrors `playback_policy::glide::Outcome` 1:1. No field named `message`
/// (uniffi/Kotlin `Throwable.message` collision, see `crate::error`).
#[derive(uniffi::Enum, Debug, Clone, Copy, PartialEq, Eq)]
pub enum GlideOutcome {
    None,
    Commit { target_ms: u64, end_clamped: bool },
    Cancelled,
}

impl From<Outcome> for GlideOutcome {
    fn from(outcome: Outcome) -> Self {
        match outcome {
            Outcome::None => GlideOutcome::None,
            Outcome::Commit {
                target_ms,
                end_clamped,
            } => GlideOutcome::Commit {
                target_ms,
                end_clamped,
            },
            Outcome::Cancelled => GlideOutcome::Cancelled,
        }
    }
}

#[derive(uniffi::Record, Debug, Clone, Copy, PartialEq, Eq)]
pub struct GlidePreview {
    pub phase: GlidePhase,
    pub direction: Option<GlideDirection>,
    pub target_ms: u64,
    pub delta_ms: i64,
    pub tier: u8,
    pub multiplier: u32,
    pub clamp: GlideClamp,
    pub chapter_index: Option<u32>,
}

impl From<Preview> for GlidePreview {
    fn from(preview: Preview) -> Self {
        GlidePreview {
            phase: preview.phase.into(),
            direction: preview.direction.map(GlideDirection::from),
            target_ms: preview.target_ms,
            delta_ms: preview.delta_ms,
            tier: preview.tier,
            multiplier: preview.multiplier,
            clamp: preview.clamp.into(),
            chapter_index: preview.chapter_index,
        }
    }
}

/// One hold-to-seek gesture. Kotlin constructs a fresh one on the first
/// hidden-OSD key-down and drops it when the persistence window expires
/// or the gesture cancels.
#[derive(uniffi::Object)]
pub struct GlideSeek(Mutex<Glide>);

impl GlideSeek {
    fn lock(&self) -> MutexGuard<'_, Glide> {
        self.0.lock().unwrap_or_else(|e| e.into_inner())
    }
}

#[uniffi::export]
impl GlideSeek {
    #[uniffi::constructor]
    pub fn new(duration_ms: u64, chapter_starts_ms: Vec<u64>) -> Arc<Self> {
        Arc::new(GlideSeek(Mutex::new(Glide::new(
            duration_ms,
            chapter_starts_ms,
        ))))
    }

    pub fn key_down(
        &self,
        direction: GlideDirection,
        position_before_ms: u64,
        tap_target_ms: u64,
        now_ms: u64,
    ) -> GlideOutcome {
        self.lock()
            .key_down(direction.into(), position_before_ms, tap_target_ms, now_ms)
            .into()
    }

    /// One FFI call per tick (PRD §9): returns the post-tick preview.
    pub fn tick(&self, now_ms: u64) -> GlidePreview {
        self.lock().tick(now_ms).into()
    }

    pub fn key_up(&self, now_ms: u64) -> GlideOutcome {
        self.lock().key_up(now_ms).into()
    }

    pub fn back(&self, now_ms: u64) -> GlideOutcome {
        self.lock().back(now_ms).into()
    }

    pub fn tap(&self, tap_target_ms: u64, now_ms: u64) {
        self.lock().tap(tap_target_ms, now_ms);
    }

    /// Force `Idle` from any phase (PRD §4.1/§5); called whenever anything
    /// else reveals the OSD.
    pub fn reset(&self) {
        self.lock().reset();
    }

    /// Swap in chapter starts that arrived after this gesture began.
    pub fn set_chapter_starts(&self, chapter_starts_ms: Vec<u64>) {
        self.lock().set_chapter_starts(chapter_starts_ms);
    }

    pub fn surface_visible(&self) -> bool {
        self.lock().surface_visible()
    }

    pub fn preview(&self) -> GlidePreview {
        self.lock().preview().into()
    }
}

/// Kotlin's tap-seek clamp (PRD §4.2) shares the glide's own end-clamp
/// arithmetic (PRD §6.2).
#[uniffi::export]
pub fn glide_end_clamp_ms(duration_ms: u64) -> u64 {
    playback_policy::glide::end_clamp_ms(duration_ms)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn constructor_starts_idle_and_invisible() {
        let seek = GlideSeek::new(2 * 3_600_000, vec![0, 600_000]);
        assert!(!seek.surface_visible());
        assert_eq!(seek.preview().phase, GlidePhase::Idle);
    }

    /// Pins the key_down/tick/key_up round trip through the FFI conversions.
    #[test]
    fn full_round_trip_through_ffi_types() {
        let seek = GlideSeek::new(2 * 3_600_000, Vec::new());

        assert_eq!(
            seek.key_down(GlideDirection::Forward, 100_000, 110_000, 0),
            GlideOutcome::None
        );
        seek.tick(500);
        seek.tick(1_000);
        assert!(seek.surface_visible());

        let preview = seek.preview();
        assert_eq!(preview.phase, GlidePhase::Gliding);
        assert_eq!(preview.direction, Some(GlideDirection::Forward));
        assert!(preview.target_ms > 110_000);
        assert!(preview.delta_ms > 10_000);
        assert!(preview.tier >= 1);

        match seek.key_up(1_000) {
            GlideOutcome::Commit {
                target_ms,
                end_clamped,
            } => {
                assert_eq!(target_ms, preview.target_ms);
                assert!(!end_clamped);
            }
            other => panic!("expected Commit, got {other:?}"),
        }
        assert_eq!(seek.preview().phase, GlidePhase::Persisting);
        assert!(seek.surface_visible());
    }

    #[test]
    fn back_cancels_a_glide() {
        let seek = GlideSeek::new(2 * 3_600_000, Vec::new());
        seek.key_down(GlideDirection::Forward, 100_000, 110_000, 0);
        seek.tick(500);
        seek.tick(1_000);
        assert_eq!(seek.back(1_000), GlideOutcome::Cancelled);
        assert_eq!(seek.preview().phase, GlidePhase::Idle);
        assert!(!seek.surface_visible());
    }

    #[test]
    fn reset_forces_idle_with_no_outcome() {
        let seek = GlideSeek::new(2 * 3_600_000, Vec::new());
        seek.key_down(GlideDirection::Forward, 100_000, 110_000, 0);
        seek.tick(500);
        seek.tick(1_000);
        assert_eq!(seek.preview().phase, GlidePhase::Gliding);

        seek.reset();
        assert_eq!(seek.preview().phase, GlidePhase::Idle);
        assert!(!seek.surface_visible());
        assert_eq!(seek.key_up(1_000), GlideOutcome::None);
    }

    #[test]
    fn glide_end_clamp_ms_matches_the_policy_function() {
        assert_eq!(
            glide_end_clamp_ms(2 * 3_600_000),
            playback_policy::glide::end_clamp_ms(2 * 3_600_000)
        );
        assert_eq!(glide_end_clamp_ms(500), 0);
    }
}
