//! "Still watching?" inactivity guard
//! (`docs/feature-dev/spec-still-watching-and-lan-discovery.md`): autoplay can otherwise chain
//! episodes for hours after the viewer stops paying attention. Pure decision logic -- no I/O, no
//! clock (callers pass monotonic ms); see `core/ffi/src/still_watching.rs` for the FFI wrapper
//! owning one [`InactivityState`] per `JellybeamCore`.

use serde::{Deserialize, Serialize};

/// How the guard is triggered; `Off` disables it entirely, [`InactivityState::decide`] always
/// returns [`Decision::Countdown`].
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum StillWatchingMode {
    Off,
    AfterEpisodes,
    AfterHours,
}

/// User-configurable guard thresholds (`docs/feature-dev/spec-still-watching-and-lan-discovery.md`
/// Settings section). Struct-level `#[serde(default)]` means a missing field in saved JSON falls
/// back to [`StillWatchingPrefs::default`], not the type's zero value -- the same tolerant-load
/// contract every settings struct here follows.
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct StillWatchingPrefs {
    pub mode: StillWatchingMode,
    pub episodes: u32,
    pub hours: f32,
    pub timeout_secs: u32,
    /// Whether player input resets the guard's counters. `true` (default):
    /// [`InactivityState::on_user_input`] fires on every qualifying input. `false`: the FFI layer
    /// skips that call, so `AfterEpisodes`/`AfterHours` count/measure regardless of seeking or
    /// pausing; [`InactivityState`] itself stays unconditional, the gating happens at the FFI call
    /// site.
    pub reset_on_input: bool,
}

impl Default for StillWatchingPrefs {
    fn default() -> Self {
        StillWatchingPrefs {
            mode: StillWatchingMode::AfterEpisodes,
            episodes: 3,
            hours: 3.0,
            timeout_secs: 120,
            reset_on_input: true,
        }
    }
}

/// What [`InactivityState::decide`] returns at the point the next-episode card would normally
/// appear.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Decision {
    /// Show the ordinary countdown/next-up card -- today's behavior.
    Countdown,
    /// Show the "Still watching?" card instead: no countdown, no auto-continue.
    AskStillWatching,
}

/// Per-play-next-chain inactivity counters; Kotlin constructs one per playback session via the FFI
/// wrapper, never persisted to disk.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct InactivityState {
    pub episodes_since_input: u32,
    pub last_input_ms: u64,
}

impl Default for InactivityState {
    /// Equivalent to [`Self::new(0)`][Self::new], letting an embedding struct derive `Default`;
    /// `now_ms = 0` is harmless since a real timestamp always arrives before `decide` is ever
    /// consulted.
    fn default() -> Self {
        Self::new(0)
    }
}

impl InactivityState {
    /// A freshly started session: `now_ms` counts as the most recent input, so `decide` never asks
    /// immediately after construction.
    pub fn new(now_ms: u64) -> Self {
        InactivityState {
            episodes_since_input: 0,
            last_input_ms: now_ms,
        }
    }

    /// Any user-initiated action (key press, seek, pause, track change, OSD reveal) resets both
    /// counters.
    pub fn on_user_input(&mut self, now_ms: u64) {
        self.episodes_since_input = 0;
        self.last_input_ms = now_ms;
    }

    /// An autoplay transition with no intervening input; only `episodes_since_input` moves, since
    /// autoplay isn't interaction.
    pub fn on_episode_finished(&mut self) {
        self.episodes_since_input = self.episodes_since_input.saturating_add(1);
    }

    /// Whether to show the countdown card or ask "still watching?". `Off` never asks;
    /// `AfterEpisodes` asks once `episodes_since_input` reaches `prefs.episodes` (inclusive);
    /// `AfterHours` asks once `prefs.hours` have elapsed since `last_input_ms` (inclusive,
    /// `saturating_sub` guards a non-advancing clock).
    pub fn decide(&self, prefs: &StillWatchingPrefs, now_ms: u64) -> Decision {
        let ask = match prefs.mode {
            StillWatchingMode::Off => false,
            StillWatchingMode::AfterEpisodes => self.episodes_since_input >= prefs.episodes,
            StillWatchingMode::AfterHours => {
                let elapsed_ms = now_ms.saturating_sub(self.last_input_ms);
                let threshold_ms = (f64::from(prefs.hours) * 3_600_000.0) as u64;
                elapsed_ms >= threshold_ms
            }
        };
        if ask {
            Decision::AskStillWatching
        } else {
            Decision::Countdown
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn prefs(mode: StillWatchingMode) -> StillWatchingPrefs {
        StillWatchingPrefs {
            mode,
            ..StillWatchingPrefs::default()
        }
    }

    #[test]
    fn new_never_asks_immediately() {
        let state = InactivityState::new(1_000);
        assert_eq!(
            state.decide(&prefs(StillWatchingMode::AfterEpisodes), 1_000),
            Decision::Countdown
        );
        assert_eq!(
            state.decide(&prefs(StillWatchingMode::AfterHours), 1_000),
            Decision::Countdown
        );
    }

    #[test]
    fn off_never_asks_regardless_of_counters() {
        let mut state = InactivityState::new(0);
        for _ in 0..10 {
            state.on_episode_finished();
        }
        assert_eq!(
            state.decide(&prefs(StillWatchingMode::Off), 999_999_999),
            Decision::Countdown
        );
    }

    #[test]
    fn after_episodes_asks_on_the_nth_finish_not_n_minus_one() {
        let mut state = InactivityState::new(0);
        let prefs = StillWatchingPrefs {
            mode: StillWatchingMode::AfterEpisodes,
            episodes: 3,
            ..StillWatchingPrefs::default()
        };

        state.on_episode_finished();
        assert_eq!(state.decide(&prefs, 0), Decision::Countdown);
        state.on_episode_finished();
        assert_eq!(state.decide(&prefs, 0), Decision::Countdown);
        state.on_episode_finished();
        assert_eq!(state.decide(&prefs, 0), Decision::AskStillWatching);
    }

    #[test]
    fn after_hours_asks_only_once_elapsed_meets_the_threshold() {
        let state = InactivityState::new(0);
        let prefs = StillWatchingPrefs {
            mode: StillWatchingMode::AfterHours,
            hours: 1.0,
            ..StillWatchingPrefs::default()
        };
        let one_hour_ms = 3_600_000;

        assert_eq!(state.decide(&prefs, one_hour_ms - 1), Decision::Countdown);
        assert_eq!(
            state.decide(&prefs, one_hour_ms),
            Decision::AskStillWatching
        );
        assert_eq!(
            state.decide(&prefs, one_hour_ms + 1),
            Decision::AskStillWatching
        );
    }

    #[test]
    fn user_input_resets_both_counters() {
        let mut state = InactivityState::new(0);
        state.on_episode_finished();
        state.on_episode_finished();
        state.on_episode_finished();
        let episodes_prefs = StillWatchingPrefs {
            mode: StillWatchingMode::AfterEpisodes,
            episodes: 3,
            ..StillWatchingPrefs::default()
        };
        assert_eq!(state.decide(&episodes_prefs, 0), Decision::AskStillWatching);

        state.on_user_input(500);
        assert_eq!(state.episodes_since_input, 0);
        assert_eq!(state.last_input_ms, 500);
        assert_eq!(state.decide(&episodes_prefs, 500), Decision::Countdown);

        let hours_prefs = StillWatchingPrefs {
            mode: StillWatchingMode::AfterHours,
            hours: 1.0,
            ..StillWatchingPrefs::default()
        };
        assert_eq!(
            state.decide(&hours_prefs, 500 + 3_600_000),
            Decision::AskStillWatching
        );
        state.on_user_input(500 + 3_600_000);
        assert_eq!(
            state.decide(&hours_prefs, 500 + 3_600_000),
            Decision::Countdown
        );
    }

    #[test]
    fn prefs_default_matches_spec() {
        let prefs = StillWatchingPrefs::default();
        assert_eq!(prefs.mode, StillWatchingMode::AfterEpisodes);
        assert_eq!(prefs.episodes, 3);
        assert_eq!(prefs.hours, 3.0);
        assert_eq!(prefs.timeout_secs, 120);
        assert!(prefs.reset_on_input);
    }

    #[test]
    fn prefs_round_trip_through_json() {
        let original = StillWatchingPrefs {
            mode: StillWatchingMode::AfterHours,
            episodes: 5,
            hours: 2.0,
            timeout_secs: 60,
            reset_on_input: false,
        };
        let json = serde_json::to_vec(&original).expect("serialize");
        let loaded: StillWatchingPrefs = serde_json::from_slice(&json).expect("deserialize");
        assert_eq!(loaded, original);
    }

    #[test]
    fn prefs_missing_fields_fall_back_to_defaults() {
        let raw = serde_json::json!({ "mode": "AfterHours" });
        let loaded: StillWatchingPrefs =
            serde_json::from_value(raw).expect("deserialize partial prefs");
        assert_eq!(loaded.mode, StillWatchingMode::AfterHours);
        assert_eq!(loaded.episodes, StillWatchingPrefs::default().episodes);
        assert_eq!(loaded.hours, StillWatchingPrefs::default().hours);
        assert_eq!(
            loaded.timeout_secs,
            StillWatchingPrefs::default().timeout_secs
        );
        // Pre-existing settings.json (field absent) falls back to `true`, not `bool`'s zeroed
        // default.
        assert!(loaded.reset_on_input);
    }

    #[test]
    fn prefs_empty_json_object_parses_to_defaults() {
        let loaded: StillWatchingPrefs =
            serde_json::from_value(serde_json::json!({})).expect("deserialize empty object");
        assert_eq!(loaded, StillWatchingPrefs::default());
    }
}
