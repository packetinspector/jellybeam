//! Media-segment (intro/outro/recap/preview/commercial) skip decisions, and
//! credits-aware next-episode timing.

use jellyfin_api::models::{MediaSegmentDto, MediaSegmentType};

use crate::prefs::{SegmentAction, SkipSegmentPrefs};
use crate::time::ticks_to_secs;

/// Safety-net EOF check: how close to the end (seconds) counts as "might as well be EOF", forcing
/// next-episode auto-advance regardless of the countdown.
pub const EOF_EPSILON_SECS: f64 = 0.35;

/// Config x segment-type -> what the OSD should do; a thin wrapper over [`SegmentAction`] so
/// callers read as "what to do" and a future action only needs one new match arm.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SegmentDecision {
    /// Ask (default): show the skip pill, wait for a click/confirm.
    Pill,
    /// Auto-skip: seek past the segment on entry, with a 5s Undo toast.
    AutoSkip,
    /// Off: ignore the segment entirely.
    Nothing,
}

pub fn segment_decision(action: SegmentAction) -> SegmentDecision {
    match action {
        SegmentAction::Ask => SegmentDecision::Pill,
        SegmentAction::AutoSkip => SegmentDecision::AutoSkip,
        SegmentAction::Off => SegmentDecision::Nothing,
    }
}

/// The Media Segment (if any) covering `position_secs`; the skip pill is active only while the play
/// head is inside it.
pub fn active_segment(
    segments: &[MediaSegmentDto],
    position_secs: f64,
) -> Option<&MediaSegmentDto> {
    let pos_ticks = (position_secs * 10_000_000.0) as i64;
    segments.iter().find(|s| {
        let start = s.start_ticks.unwrap_or(0);
        let end = s.end_ticks.unwrap_or(0);
        pos_ticks >= start && pos_ticks < end
    })
}

/// The segment covering `position_secs` (if any) paired with what `prefs` says to do about it.
/// `SegmentDecision::Nothing` is still returned (not filtered out) so callers can tell playback is
/// inside *any* segment regardless of configured action.
pub fn active_segment_decision<'a>(
    segments: &'a [MediaSegmentDto],
    position_secs: f64,
    prefs: &SkipSegmentPrefs,
) -> Option<(&'a MediaSegmentDto, SegmentDecision)> {
    let seg = active_segment(segments, position_secs)?;
    let ty = seg.type_.unwrap_or(MediaSegmentType::Unknown);
    let action = prefs.action_for(ty);
    Some((seg, segment_decision(action)))
}

/// The item's Outro/credits segment start (seconds), if the server returned one. Unlike
/// [`active_segment`], doesn't require the playhead to be inside it -- needed ahead of time by
/// [`next_episode_trigger_remaining_secs`].
pub fn outro_segment_start_secs(segments: &[MediaSegmentDto]) -> Option<f64> {
    segments
        .iter()
        .find(|s| s.type_ == Some(MediaSegmentType::Outro))
        .and_then(|s| s.start_ticks)
        .map(ticks_to_secs)
}

/// Whether Outro is configured `AutoSkip`; the signal [`next_episode_trigger_remaining_secs`] uses
/// to bring the card forward by the countdown.
pub fn outro_auto_skip(prefs: &SkipSegmentPrefs) -> bool {
    prefs.action_for(MediaSegmentType::Outro) == SegmentAction::AutoSkip
}

/// §2.4: how close to the end (seconds remaining) the next-episode card should appear; scales with
/// episode length (~15% of runtime), clamped to [3, 30]s so a short episode still gets a detectable
/// window and a long one isn't shown absurdly early.
pub fn next_episode_show_threshold(duration_secs: f64) -> f64 {
    (duration_secs * 0.15).clamp(3.0, 30.0)
}

/// Credits-aware "when should the next-episode card appear" threshold (remaining seconds); pure and
/// unit-testable independent of the tick-driven state machine that acts on it.
///
/// Two signals: the fixed default ([`next_episode_show_threshold`], ~15% of runtime), or the item's
/// own Outro/credits start (`outro_start_secs`) when known, which replaces the default outright
/// since it's a better signal than a duration-proportional guess.
///
/// When `outro_auto_skip` is true the credits will be skipped the moment they start, so the card
/// must come up `countdown_secs` (the autoplay delay) before the outro: the trigger is the credits
/// length plus the countdown, and the countdown runs out exactly where the skip lands. Without the
/// extension the card would never be seen on a show whose credits are auto-skipped.
///
/// A nonsensical `outro_start_secs` (negative, or at/after duration) falls back to the fixed
/// default rather than a non-positive threshold. "Show immediately once the trigger has passed"
/// needs no special case: it falls out of the normal `remaining <= trigger_remaining` comparison at
/// the call site.
pub fn next_episode_trigger_remaining_secs(
    duration_secs: f64,
    outro_start_secs: Option<f64>,
    outro_auto_skip: bool,
    countdown_secs: f64,
) -> f64 {
    match outro_start_secs {
        Some(start) if start >= 0.0 && start < duration_secs => {
            let credits = duration_secs - start;
            if outro_auto_skip {
                credits + countdown_secs.max(0.0)
            } else {
                credits
            }
        }
        _ => next_episode_show_threshold(duration_secs),
    }
}

/// §2.4: the Play-Next button's countdown-fill total, `min(remaining, configured delay)`. Captured
/// once when the card appears rather than recomputed every tick, since `total -
/// elapsed_since_shown` is exactly the live countdown (see [`next_episode_countdown_remaining`]).
///
/// Reproduces "auto-advance only at real EOF" as one edge of the `min`: when the delay exceeds
/// what's left to play, `remaining` wins and the countdown ends exactly at EOF.
pub fn next_episode_countdown_total(remaining_secs: f64, delay_secs: f64) -> f64 {
    remaining_secs.max(0.0).min(delay_secs.max(0.0))
}

/// Countdown remaining seconds, clamped to `[0, total]`; feeds the auto-advance decision (`<= 0.0`)
/// and the fill fraction.
pub fn next_episode_countdown_remaining(total_secs: f64, elapsed_secs: f64) -> f64 {
    (total_secs - elapsed_secs).clamp(0.0, total_secs.max(0.0))
}

/// Fill fraction (`0.0..=1.0`): `elapsed / total`, saturating at `1.0`; `total_secs <= 0.0` reports
/// a full bar immediately rather than dividing by zero.
pub fn next_episode_countdown_fraction(total_secs: f64, elapsed_secs: f64) -> f32 {
    if total_secs <= 0.0 {
        return 1.0;
    }
    (elapsed_secs / total_secs).clamp(0.0, 1.0) as f32
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn segment_decision_maps_every_action() {
        assert_eq!(segment_decision(SegmentAction::Ask), SegmentDecision::Pill);
        assert_eq!(
            segment_decision(SegmentAction::AutoSkip),
            SegmentDecision::AutoSkip
        );
        assert_eq!(
            segment_decision(SegmentAction::Off),
            SegmentDecision::Nothing
        );
    }

    fn case(ty: MediaSegmentType) -> MediaSegmentDto {
        MediaSegmentDto {
            start_ticks: Some(0),
            end_ticks: Some(50_000_000),
            type_: Some(ty),
            ..Default::default()
        }
    }

    /// Pins the config x segment-type -> decision matrix through `active_segment_decision`,
    /// exercising the per-type lookup and position-window match together.
    #[test]
    fn active_segment_decision_matrix() {
        let prefs = SkipSegmentPrefs {
            intro: SegmentAction::Ask,
            outro: SegmentAction::AutoSkip,
            recap: SegmentAction::Off,
            preview: SegmentAction::Ask,
            commercial: SegmentAction::AutoSkip,
        };
        let position_secs = 2.0;

        let segments = vec![case(MediaSegmentType::Intro)];
        assert_eq!(
            active_segment_decision(&segments, position_secs, &prefs).map(|(_, d)| d),
            Some(SegmentDecision::Pill)
        );

        let segments = vec![case(MediaSegmentType::Outro)];
        assert_eq!(
            active_segment_decision(&segments, position_secs, &prefs).map(|(_, d)| d),
            Some(SegmentDecision::AutoSkip)
        );

        let segments = vec![case(MediaSegmentType::Recap)];
        assert_eq!(
            active_segment_decision(&segments, position_secs, &prefs).map(|(_, d)| d),
            Some(SegmentDecision::Nothing)
        );

        let segments = vec![case(MediaSegmentType::Commercial)];
        assert_eq!(
            active_segment_decision(&segments, position_secs, &prefs).map(|(_, d)| d),
            Some(SegmentDecision::AutoSkip)
        );

        // No segment covering the current position at all.
        let segments = vec![case(MediaSegmentType::Intro)];
        assert!(active_segment_decision(&segments, 100.0, &prefs).is_none());
    }

    #[test]
    fn next_episode_countdown_total_is_the_min_of_remaining_and_delay() {
        assert_eq!(next_episode_countdown_total(120.0, 10.0), 10.0);
        assert_eq!(next_episode_countdown_total(4.0, 10.0), 4.0);
        // Negative inputs (shouldn't happen, but clamp defensively).
        assert_eq!(next_episode_countdown_total(-1.0, 10.0), 0.0);
    }

    #[test]
    fn next_episode_countdown_remaining_counts_down_to_zero() {
        assert_eq!(next_episode_countdown_remaining(10.0, 0.0), 10.0);
        assert_eq!(next_episode_countdown_remaining(10.0, 6.0), 4.0);
        assert_eq!(next_episode_countdown_remaining(10.0, 15.0), 0.0);
    }

    #[test]
    fn next_episode_countdown_fraction_saturates() {
        assert_eq!(next_episode_countdown_fraction(10.0, 0.0), 0.0);
        assert_eq!(next_episode_countdown_fraction(10.0, 5.0), 0.5);
        assert_eq!(next_episode_countdown_fraction(10.0, 10.0), 1.0);
        assert_eq!(next_episode_countdown_fraction(10.0, 20.0), 1.0);
        // Zero/negative total: full bar, not a division by zero.
        assert_eq!(next_episode_countdown_fraction(0.0, 0.0), 1.0);
    }

    /// Pins the show-threshold clamp to [3, 30]s, scaling with episode length in between.
    #[test]
    fn next_episode_show_threshold_clamps_to_expected_range() {
        // A ~10s corpus test episode: 15% would be 1.5s, floored to 3.0s.
        assert_eq!(next_episode_show_threshold(10.0), 3.0);
        // A 45-minute (2700s) episode: 15% = 405s, ceilinged to 30.0s.
        assert_eq!(next_episode_show_threshold(2700.0), 30.0);
        assert_eq!(next_episode_show_threshold(120.0), 18.0);
    }

    /// Pins fallback to the fixed default when no outro segment is known.
    #[test]
    fn trigger_remaining_falls_back_to_fixed_default_with_no_outro_segment() {
        assert_eq!(
            next_episode_trigger_remaining_secs(120.0, None, false, 10.0),
            next_episode_show_threshold(120.0)
        );
        // Auto-skip flag is irrelevant when there's no segment.
        assert_eq!(
            next_episode_trigger_remaining_secs(120.0, None, true, 10.0),
            next_episode_show_threshold(120.0)
        );
    }

    /// Pins that a known outro start drives the trigger directly as `duration - outro_start`.
    #[test]
    fn trigger_remaining_uses_outro_start_when_known() {
        // 45-min episode, credits at 42:00 -- 180s, nowhere near the fixed
        // 30s-ceiling default.
        assert_eq!(
            next_episode_trigger_remaining_secs(2700.0, Some(2520.0), false, 10.0),
            180.0
        );
    }

    /// Pins that once the outro's start has passed, `remaining <= trigger_remaining` at the call
    /// site shows the card immediately with no special case needed.
    #[test]
    fn trigger_remaining_shows_immediately_once_outro_start_has_passed() {
        let duration = 1200.0;
        let outro_start = 1100.0;
        let trigger = next_episode_trigger_remaining_secs(duration, Some(outro_start), false, 10.0);
        assert_eq!(trigger, 100.0);
        // Position already 30s past the outro's start.
        let position = outro_start + 30.0;
        let remaining = duration - position;
        assert!(remaining <= trigger, "expected an immediate show");
    }

    /// Pins that AutoSkip brings the card forward by the countdown: credits length plus the delay,
    /// so the countdown runs out where the skip lands, and a non-positive delay adds nothing.
    #[test]
    fn trigger_remaining_adds_the_countdown_when_outro_is_auto_skip() {
        assert_eq!(
            next_episode_trigger_remaining_secs(2700.0, Some(2520.0), true, 10.0),
            190.0
        );
        assert_eq!(
            next_episode_trigger_remaining_secs(2700.0, Some(2520.0), true, -3.0),
            180.0
        );
    }

    /// Pins that nonsensical outro data (negative, or at/after duration) falls back like "no outro
    /// segment".
    #[test]
    fn trigger_remaining_falls_back_on_nonsensical_outro_start() {
        assert_eq!(
            next_episode_trigger_remaining_secs(120.0, Some(-5.0), false, 10.0),
            next_episode_show_threshold(120.0)
        );
        assert_eq!(
            next_episode_trigger_remaining_secs(120.0, Some(120.0), true, 10.0),
            next_episode_show_threshold(120.0)
        );
        assert_eq!(
            next_episode_trigger_remaining_secs(120.0, Some(500.0), false, 10.0),
            next_episode_show_threshold(120.0)
        );
    }

    /// Pins that this finds the Outro-typed segment regardless of playhead position and converts
    /// ticks to seconds.
    #[test]
    fn outro_segment_start_secs_finds_the_outro_regardless_of_position() {
        let segments = vec![
            MediaSegmentDto {
                type_: Some(MediaSegmentType::Intro),
                start_ticks: Some(0),
                end_ticks: Some(300_000_000),
                ..Default::default()
            },
            MediaSegmentDto {
                type_: Some(MediaSegmentType::Outro),
                start_ticks: Some(25_200_000_000),
                end_ticks: Some(27_000_000_000),
                ..Default::default()
            },
        ];
        assert_eq!(outro_segment_start_secs(&segments), Some(2520.0));
    }

    #[test]
    fn outro_segment_start_secs_is_none_without_an_outro_segment() {
        let segments = vec![MediaSegmentDto {
            type_: Some(MediaSegmentType::Intro),
            start_ticks: Some(0),
            end_ticks: Some(300_000_000),
            ..Default::default()
        }];
        assert_eq!(outro_segment_start_secs(&segments), None);
    }

    /// Pins that this reads through `SkipSegmentPrefs::action_for(Outro)`.
    #[test]
    fn outro_auto_skip_reflects_configured_prefs() {
        let prefs = SkipSegmentPrefs {
            outro: SegmentAction::AutoSkip,
            ..SkipSegmentPrefs::default()
        };
        assert!(outro_auto_skip(&prefs));

        let prefs = SkipSegmentPrefs {
            outro: SegmentAction::Ask,
            ..SkipSegmentPrefs::default()
        };
        assert!(!outro_auto_skip(&prefs));
    }
}
