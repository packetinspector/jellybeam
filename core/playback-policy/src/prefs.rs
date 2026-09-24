//! Preference/settings types consumed by [`crate::tracks`], [`crate::segments`], and
//! [`crate::preload`].
//!
//! Persistence stays with each
//! platform app (DataStore on Android) -- these are plain-data structs only.

use serde::{Deserialize, Serialize};

use jellyfin_api::models::MediaSegmentType;

/// Per-series audio/subtitle track preference; persistence lives in the platform app, this crate
/// only needs the shape for [`crate::tracks::resolve_track_selection`].
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq, Eq)]
pub struct SeriesTrackPref {
    /// Preferred audio track language/title key ([`crate::tracks::track_pref_key`]): matches
    /// `Track::lang` first, falling back to `Track::title`, since track ids aren't stable across
    /// items.
    pub audio: Option<String>,
    pub subtitle: Option<String>,
}

/// Subtitle behavior when no per-series memory applies yet; see
/// [`crate::tracks::resolve_track_selection`] for the decision table each variant drives.
#[derive(Debug, Default, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum SubtitleMode {
    /// Leave whatever the stream/server marked as default; no preference applied.
    #[default]
    Default,
    /// Always enable a subtitle track: `LanguagePrefs::subtitle` match if set and present, else the
    /// stream's default-flagged track, else the first one.
    Always,
    /// Only enable a track flagged `forced` by the container and matching the currently-playing
    /// audio language; otherwise subtitles stay off.
    OnlyForced,
    /// Subtitles off, regardless of any stream/server default.
    None,
}

/// Global preferred audio/subtitle language, applied when no per-series memory exists
/// ([`crate::tracks::resolve_track_selection`] composes this with the per-series lookup, which
/// always wins when present). Language codes are ISO 639-2 off the container (`Track::lang`); both
/// "B" and "T" forms are accepted and matched tolerantly ([`crate::tracks::lang_matches`]).
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct LanguagePrefs {
    /// `None` = "Any": no audio-language preference applied, the stream/server default plays
    /// untouched.
    pub audio: Option<String>,
    /// The language `SubtitleMode::Always` prefers; irrelevant to other modes (`OnlyForced` matches
    /// audio language instead).
    pub subtitle: Option<String>,
    pub subtitle_mode: SubtitleMode,
}

/// Per-`MediaSegmentType` behavior: `Ask` shows the OSD skip pill, `AutoSkip` seeks past the
/// segment on entry with a 5s Undo toast, `Off` ignores it entirely.
#[derive(Debug, Default, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum SegmentAction {
    #[default]
    Ask,
    AutoSkip,
    Off,
}

/// Per-type skip behavior, one field per `MediaSegmentType` variant the model exposes
/// (`Unknown`/`Unrecognized` have no row, always `Off`, see `action_for`). Default: everything
/// `Ask` except `Commercial` (`AutoSkip`).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct SkipSegmentPrefs {
    pub intro: SegmentAction,
    pub outro: SegmentAction,
    pub recap: SegmentAction,
    pub preview: SegmentAction,
    pub commercial: SegmentAction,
}

impl Default for SkipSegmentPrefs {
    fn default() -> Self {
        SkipSegmentPrefs {
            intro: SegmentAction::Ask,
            outro: SegmentAction::Ask,
            recap: SegmentAction::Ask,
            preview: SegmentAction::Ask,
            commercial: SegmentAction::AutoSkip,
        }
    }
}

impl SkipSegmentPrefs {
    /// `Unknown`/`Unrecognized` (a segment type the pinned model doesn't recognize) has no settings
    /// row and is always `Off`.
    pub fn action_for(&self, ty: MediaSegmentType) -> SegmentAction {
        match ty {
            MediaSegmentType::Intro => self.intro,
            MediaSegmentType::Outro => self.outro,
            MediaSegmentType::Recap => self.recap,
            MediaSegmentType::Preview => self.preview,
            MediaSegmentType::Commercial => self.commercial,
            MediaSegmentType::Unknown | MediaSegmentType::Unrecognized => SegmentAction::Off,
        }
    }

    pub fn set_action_for(&mut self, ty: MediaSegmentType, action: SegmentAction) {
        match ty {
            MediaSegmentType::Intro => self.intro = action,
            MediaSegmentType::Outro => self.outro = action,
            MediaSegmentType::Recap => self.recap = action,
            MediaSegmentType::Preview => self.preview = action,
            MediaSegmentType::Commercial => self.commercial = action,
            MediaSegmentType::Unknown | MediaSegmentType::Unrecognized => {}
        }
    }
}

/// Autoplay next episode toggle + delay; the next-episode card always appears, `enabled` only gates
/// whether it auto-advances vs. requiring "Play Next". Default: `enabled = true`, `delay_secs = 10`
/// (Netflix's 2019 A/B test found 10s increased watch time over a shorter default).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct AutoplayPrefs {
    pub enabled: bool,
    pub delay_secs: u32,
}

impl Default for AutoplayPrefs {
    fn default() -> Self {
        AutoplayPrefs {
            enabled: true,
            delay_secs: 10,
        }
    }
}

/// Configurable ←/→ skip lengths, back and forward independently. Default: 10s/10s.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct SkipLengthPrefs {
    pub back_secs: u32,
    pub forward_secs: u32,
}

impl Default for SkipLengthPrefs {
    fn default() -> Self {
        SkipLengthPrefs {
            back_secs: 10,
            forward_secs: 10,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Pins `SkipSegmentPrefs`/`AutoplayPrefs` defaults: everything `Ask` except `Commercial`;
    /// autoplay on at 10s.
    #[test]
    fn skip_segment_defaults_match_mission_spec() {
        let prefs = SkipSegmentPrefs::default();
        assert_eq!(prefs.intro, SegmentAction::Ask);
        assert_eq!(prefs.outro, SegmentAction::Ask);
        assert_eq!(prefs.recap, SegmentAction::Ask);
        assert_eq!(prefs.preview, SegmentAction::Ask);
        assert_eq!(prefs.commercial, SegmentAction::AutoSkip);

        let autoplay = AutoplayPrefs::default();
        assert!(autoplay.enabled);
        assert_eq!(autoplay.delay_secs, 10);
    }

    #[test]
    fn skip_segment_action_for_covers_every_configurable_type() {
        let mut prefs = SkipSegmentPrefs::default();
        prefs.set_action_for(MediaSegmentType::Intro, SegmentAction::Off);
        prefs.set_action_for(MediaSegmentType::Outro, SegmentAction::AutoSkip);
        assert_eq!(
            prefs.action_for(MediaSegmentType::Intro),
            SegmentAction::Off
        );
        assert_eq!(
            prefs.action_for(MediaSegmentType::Outro),
            SegmentAction::AutoSkip
        );
        assert_eq!(
            prefs.action_for(MediaSegmentType::Recap),
            SegmentAction::Ask
        );
        // Unknown/Unrecognized: always Off, set_action_for on them is a no-op.
        assert_eq!(
            prefs.action_for(MediaSegmentType::Unknown),
            SegmentAction::Off
        );
        prefs.set_action_for(MediaSegmentType::Unrecognized, SegmentAction::Ask);
        assert_eq!(
            prefs.action_for(MediaSegmentType::Unrecognized),
            SegmentAction::Off
        );
    }

    /// Pins the 10s/10s default matching the prior hardcoded `±10.0` behavior.
    #[test]
    fn skip_length_defaults_match_pre_feature_hardcoded_behavior() {
        let prefs = SkipLengthPrefs::default();
        assert_eq!(prefs.back_secs, 10);
        assert_eq!(prefs.forward_secs, 10);
    }
}
