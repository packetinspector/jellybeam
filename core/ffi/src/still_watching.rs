//! "Still watching?" FFI surface (docs/feature-dev/
//! spec-still-watching-and-lan-discovery.md, Feature A): a thin
//! `impl JellybeamCore` block wrapping one
//! `playback_policy::still_watching::InactivityState` per `JellybeamCore`.
//! Each method is bounded to a lock, a counter update, and (for
//! [`JellybeamCore::note_episode_finished`]) one pure `decide` call -- no
//! allocation, no I/O, safe from the playback fast path.

use playback_policy::still_watching::Decision;

use crate::object::JellybeamCore;

/// FFI mirror of `playback_policy::still_watching::Decision`.
#[derive(uniffi::Enum, Debug, Clone, Copy, PartialEq, Eq)]
pub enum StillWatchingDecision {
    Countdown,
    AskStillWatching,
}

impl From<Decision> for StillWatchingDecision {
    fn from(decision: Decision) -> Self {
        match decision {
            Decision::Countdown => StillWatchingDecision::Countdown,
            Decision::AskStillWatching => StillWatchingDecision::AskStillWatching,
        }
    }
}

#[uniffi::export]
impl JellybeamCore {
    /// Records a user-initiated action in the player (any key press; an
    /// autoplay transition itself is not interaction) -- a no-op when
    /// `Settings::still_watching::reset_on_input` is `false`. The two
    /// resets the spec always guarantees (fresh session, Continue) go
    /// through [`JellybeamCore::reset_still_watching`] instead.
    pub fn note_player_input(&self, now_ms: u64) {
        let mut state = self.lock_state();
        if state.settings.still_watching.reset_on_input {
            state.still_watching.on_user_input(now_ms);
        }
    }

    /// Unconditionally resets the "still watching?" guard's counters,
    /// never gated by `reset_on_input` -- for the two resets the spec
    /// always guarantees (fresh session, Continue).
    pub fn reset_still_watching(&self, now_ms: u64) {
        self.lock_state().still_watching.on_user_input(now_ms);
    }

    /// Records an autoplay transition with no intervening input, then
    /// decides whether the next-up card shows its countdown or asks "still
    /// watching?" (see `InactivityState::decide` for the rule per mode).
    pub fn note_episode_finished(&self, now_ms: u64) -> StillWatchingDecision {
        let mut state = self.lock_state();
        state.still_watching.on_episode_finished();
        let prefs = state.settings.still_watching.into();
        state.still_watching.decide(&prefs, now_ms).into()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::settings::{StillWatchingMode, StillWatchingSettings};

    fn core() -> std::sync::Arc<JellybeamCore> {
        let dir = tempfile::tempdir().expect("tempdir");
        JellybeamCore::new(dir.path().to_string_lossy().to_string())
    }

    fn set_still_watching(core: &JellybeamCore, settings: StillWatchingSettings) {
        let mut full = core.get_settings();
        full.still_watching = settings;
        core.set_settings(full);
    }

    /// Defaults `AfterEpisodes`/3: only the third finish asks.
    #[test]
    fn defaults_ask_on_the_third_finish_not_before() {
        let core = core();
        assert_eq!(
            core.note_episode_finished(0),
            StillWatchingDecision::Countdown
        );
        assert_eq!(
            core.note_episode_finished(0),
            StillWatchingDecision::Countdown
        );
        assert_eq!(
            core.note_episode_finished(0),
            StillWatchingDecision::AskStillWatching
        );
    }

    #[test]
    fn note_player_input_resets_the_episode_counter() {
        let core = core();
        core.note_episode_finished(0);
        core.note_episode_finished(0);
        core.note_player_input(100);
        assert_eq!(
            core.note_episode_finished(100),
            StillWatchingDecision::Countdown
        );
        assert_eq!(
            core.note_episode_finished(100),
            StillWatchingDecision::Countdown
        );
        assert_eq!(
            core.note_episode_finished(100),
            StillWatchingDecision::AskStillWatching
        );
    }

    #[test]
    fn mode_off_never_asks() {
        let core = core();
        set_still_watching(
            &core,
            StillWatchingSettings {
                mode: StillWatchingMode::Off,
                ..StillWatchingSettings::default()
            },
        );
        for _ in 0..10 {
            assert_eq!(
                core.note_episode_finished(0),
                StillWatchingDecision::Countdown
            );
        }
    }

    /// `reset_on_input: false`: input no longer resets the episode counter.
    #[test]
    fn note_player_input_is_a_no_op_when_reset_on_input_is_false() {
        let core = core();
        set_still_watching(
            &core,
            StillWatchingSettings {
                reset_on_input: false,
                ..StillWatchingSettings::default()
            },
        );
        assert_eq!(
            core.note_episode_finished(0),
            StillWatchingDecision::Countdown
        );
        assert_eq!(
            core.note_episode_finished(0),
            StillWatchingDecision::Countdown
        );
        core.note_player_input(100);
        assert_eq!(
            core.note_episode_finished(100),
            StillWatchingDecision::AskStillWatching
        );
    }

    /// `reset_still_watching` always resets, regardless of `reset_on_input`.
    #[test]
    fn reset_still_watching_always_resets_regardless_of_reset_on_input() {
        let core = core();
        set_still_watching(
            &core,
            StillWatchingSettings {
                reset_on_input: false,
                ..StillWatchingSettings::default()
            },
        );
        assert_eq!(
            core.note_episode_finished(0),
            StillWatchingDecision::Countdown
        );
        assert_eq!(
            core.note_episode_finished(0),
            StillWatchingDecision::Countdown
        );
        assert_eq!(
            core.note_episode_finished(0),
            StillWatchingDecision::AskStillWatching
        );

        core.reset_still_watching(500);
        assert_eq!(
            core.note_episode_finished(500),
            StillWatchingDecision::Countdown
        );
        assert_eq!(
            core.note_episode_finished(500),
            StillWatchingDecision::Countdown
        );
        assert_eq!(
            core.note_episode_finished(500),
            StillWatchingDecision::AskStillWatching
        );
    }

    #[test]
    fn mode_after_hours_asks_once_elapsed_meets_threshold() {
        let core = core();
        set_still_watching(
            &core,
            StillWatchingSettings {
                mode: StillWatchingMode::AfterHours,
                hours: 1.0,
                ..StillWatchingSettings::default()
            },
        );
        core.note_player_input(0);
        let one_hour_ms = 3_600_000;
        assert_eq!(
            core.note_episode_finished(one_hour_ms - 1),
            StillWatchingDecision::Countdown
        );
        assert_eq!(
            core.note_episode_finished(one_hour_ms),
            StillWatchingDecision::AskStillWatching
        );
    }
}
