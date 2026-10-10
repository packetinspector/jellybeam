//! Render-cost guard: per-window frame times step the overlay down a quality ladder when the renderer
//! can't keep up, and back up once it has headroom. Video never waits on subtitles.

/// Overlay quality, best first.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Default)]
pub enum Quality {
    #[default]
    Full,
    /// 75% resolution; the compositor scales the buffer up.
    Reduced,
    /// 50% resolution: a quarter of the pixels the renderer blurs and blends.
    Low,
    /// 50% resolution, at most 12 updates a second.
    Minimal,
}

impl Quality {
    pub fn scale(self) -> f32 {
        match self {
            Quality::Full => 1.0,
            Quality::Reduced => 0.75,
            Quality::Low | Quality::Minimal => 0.5,
        }
    }

    pub fn min_interval_ms(self) -> u64 {
        if self == Quality::Minimal {
            83
        } else {
            0
        }
    }

    fn down(self) -> Self {
        match self {
            Quality::Full => Quality::Reduced,
            Quality::Reduced => Quality::Low,
            Quality::Low | Quality::Minimal => Quality::Minimal,
        }
    }

    /// The share of the budget a window must stay under before stepping up from this level. Low
    /// draws the same pixels as Minimal, only more often, so Minimal climbs at near-budget cost;
    /// every other step up draws 1.8-2.25x the pixels.
    fn up_headroom(self) -> f32 {
        if self == Quality::Minimal {
            0.8
        } else {
            HEADROOM
        }
    }

    fn up(self) -> Self {
        match self {
            Quality::Minimal => Quality::Low,
            Quality::Low => Quality::Reduced,
            Quality::Reduced | Quality::Full => Quality::Full,
        }
    }
}

/// Frame times collected over one window.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct WindowStats {
    pub frames: u32,
    pub p90_ms: f32,
    /// Total render time in the window.
    pub busy_ms: f32,
}

/// Windows with fewer frames, and under this many budgets of render time, can't justify a step down,
/// but cheap ones count toward climbing back: ordinary dialogue redraws a few times a window, and one
/// heavy sign must not pin it low for good. The time test catches a renderer too slow to draw more.
const MIN_FRAMES: u32 = 5;
/// Two slow windows in a row step down; five fast ones step up.
const DOWN_AFTER: u32 = 2;
const UP_AFTER: u32 = 5;
/// "Fast" means using under this fraction of the budget, so a step up doesn't bounce straight back.
const HEADROOM: f32 = 0.35;

#[derive(Debug)]
pub struct Ladder {
    level: Quality,
    slow: u32,
    fast: u32,
}

impl Default for Ladder {
    fn default() -> Self {
        Self {
            level: Quality::Full,
            slow: 0,
            fast: 0,
        }
    }
}

impl Ladder {
    pub fn level(&self) -> Quality {
        self.level
    }

    /// Feeds one window; returns the new level when it changes.
    pub fn on_window(&mut self, w: WindowStats, budget_ms: f32) -> Option<Quality> {
        let sparse = w.frames < MIN_FRAMES && w.busy_ms < budget_ms * MIN_FRAMES as f32;
        if sparse && w.p90_ms > budget_ms {
            return None;
        }
        if !sparse && w.p90_ms > budget_ms {
            self.fast = 0;
            self.slow += 1;
            if self.slow >= DOWN_AFTER && self.level != Quality::Minimal {
                self.slow = 0;
                self.level = self.level.down();
                return Some(self.level);
            }
        } else if sparse || w.p90_ms < budget_ms * self.level.up_headroom() {
            self.slow = 0;
            self.fast += 1;
            if self.fast >= UP_AFTER && self.level != Quality::Full {
                self.fast = 0;
                self.level = self.level.up();
                return Some(self.level);
            }
        } else {
            self.slow = 0;
            self.fast = 0;
        }
        None
    }
}

/// Accumulates frame times and cuts them into windows.
#[derive(Debug, Default)]
pub struct FrameTimes {
    ms: Vec<f32>,
}

impl FrameTimes {
    pub fn push(&mut self, ms: f32) {
        self.ms.push(ms);
    }

    pub fn take_window(&mut self) -> WindowStats {
        let mut v = std::mem::take(&mut self.ms);
        let frames = v.len() as u32;
        let busy_ms = v.iter().sum();
        if v.is_empty() {
            return WindowStats {
                frames,
                p90_ms: 0.0,
                busy_ms,
            };
        }
        v.sort_by(f32::total_cmp);
        let idx = ((v.len() as f32 * 0.9).ceil() as usize).clamp(1, v.len()) - 1;
        WindowStats {
            frames,
            p90_ms: v[idx],
            busy_ms,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn w(p90: f32) -> WindowStats {
        WindowStats {
            frames: 30,
            p90_ms: p90,
            busy_ms: 30.0 * p90,
        }
    }

    #[test]
    fn two_slow_windows_step_down_once() {
        let mut l = Ladder::default();
        assert_eq!(l.on_window(w(40.0), 20.0), None);
        assert_eq!(l.on_window(w(40.0), 20.0), Some(Quality::Reduced));
        assert_eq!(l.on_window(w(40.0), 20.0), None);
        assert_eq!(l.on_window(w(40.0), 20.0), Some(Quality::Low));
    }

    #[test]
    fn bottoms_out_at_minimal() {
        let mut l = Ladder::default();
        for _ in 0..20 {
            l.on_window(w(100.0), 20.0);
        }
        assert_eq!(l.level(), Quality::Minimal);
        assert_eq!(Quality::Minimal.min_interval_ms(), 83);
    }

    #[test]
    fn steps_up_only_with_sustained_headroom() {
        let mut l = Ladder::default();
        l.on_window(w(40.0), 20.0);
        l.on_window(w(40.0), 20.0);
        assert_eq!(l.level(), Quality::Reduced);
        for _ in 0..4 {
            assert_eq!(l.on_window(w(5.0), 20.0), None);
        }
        assert_eq!(l.on_window(w(5.0), 20.0), Some(Quality::Full));
    }

    #[test]
    fn middling_windows_reset_both_streaks() {
        let mut l = Ladder::default();
        l.on_window(w(40.0), 20.0);
        l.on_window(w(15.0), 20.0);
        assert_eq!(l.on_window(w(40.0), 20.0), None);
    }

    #[test]
    fn one_slow_frame_a_window_never_steps_down() {
        let mut l = Ladder::default();
        let sparse = WindowStats {
            frames: 1,
            p90_ms: 60.0,
            busy_ms: 60.0,
        };
        for _ in 0..10 {
            assert_eq!(l.on_window(sparse, 20.0), None);
        }
    }

    #[test]
    fn a_renderer_too_slow_to_draw_many_frames_still_steps_down() {
        let mut l = Ladder::default();
        let saturated = WindowStats {
            frames: 3,
            p90_ms: 400.0,
            busy_ms: 900.0,
        };
        assert_eq!(l.on_window(saturated, 20.0), None);
        assert_eq!(l.on_window(saturated, 20.0), Some(Quality::Reduced));
    }

    #[test]
    fn quiet_dialogue_climbs_back_after_a_heavy_scene() {
        let mut l = Ladder::default();
        for _ in 0..20 {
            l.on_window(w(100.0), 20.0);
        }
        assert_eq!(l.level(), Quality::Minimal);
        let quiet = WindowStats {
            frames: 2,
            p90_ms: 12.0,
            busy_ms: 24.0,
        };
        for _ in 0..15 {
            l.on_window(quiet, 20.0);
        }
        assert_eq!(l.level(), Quality::Full);
    }

    #[test]
    fn minimal_climbs_to_low_at_near_budget_cost() {
        let mut l = Ladder::default();
        for _ in 0..20 {
            l.on_window(w(100.0), 20.0);
        }
        // 15 ms is too slow to leave Low or Reduced, but Low costs the same per frame as Minimal.
        for _ in 0..4 {
            assert_eq!(l.on_window(w(15.0), 20.0), None);
        }
        assert_eq!(l.on_window(w(15.0), 20.0), Some(Quality::Low));
        for _ in 0..10 {
            assert_eq!(l.on_window(w(15.0), 20.0), None);
        }
        assert_eq!(l.level(), Quality::Low);
    }

    #[test]
    fn p90_picks_the_slow_tail() {
        let mut t = FrameTimes::default();
        for i in 1..=10 {
            t.push(i as f32);
        }
        assert_eq!(
            t.take_window(),
            WindowStats {
                frames: 10,
                p90_ms: 9.0,
                busy_ms: 55.0,
            }
        );
        assert_eq!(t.take_window().frames, 0);
    }
}
