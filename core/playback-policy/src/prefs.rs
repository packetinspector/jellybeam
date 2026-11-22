//! Preference/settings types consumed by [`crate::tracks`] and [`crate::segments`].
//!
//! Plain data only; the app crate owns persistence.

use serde::{Deserialize, Serialize};

use jellyfin_api::models::MediaSegmentType;

/// Per-series audio/subtitle track preference; the app crate persists it, this crate only needs
/// the shape for [`crate::tracks::resolve_track_selection`].
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq, Eq)]
pub struct SeriesTrackPref {
    /// Remembered audio track; track ids aren't stable across items, so it names the track by
    /// identity ([`crate::tracks::track_identity`]) rather than by id.
    #[serde(default)]
    pub audio: TrackMemory,
    #[serde(default)]
    pub subtitle: TrackMemory,
}

/// What distinguishes one remembered track from its siblings (docs/12 "Choices are remembered per
/// series"): language first (normalized), the kind flags and title/codec to tell "English" from
/// "English Forced" or "English SDH".
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct TrackIdentity {
    /// Normalized language code; `None` when the track carried none.
    pub lang: Option<String>,
    pub title: Option<String>,
    pub forced: bool,
    pub sdh: bool,
    pub codec: Option<String>,
}

/// One half of a series' remembered choice. On disk: `Unset` is `null`/absent, `Key` a bare string
/// (the legacy shape, still read), `Off` the tagged object `{"off":true}` and `Track` the tagged
/// object `{"track":{...}}`; the tags keep either from colliding with a key string or each other.
/// Audio has no Off: its memory is `Unset`, `Key` or `Track`.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub enum TrackMemory {
    #[default]
    Unset,
    Off,
    Key(String),
    Track(TrackIdentity),
}

impl TrackMemory {
    /// `Key` and `Track` name a track to find; `Unset` and `Off` do not.
    pub fn names_track(&self) -> bool {
        matches!(self, Self::Key(_) | Self::Track(_))
    }
}

/// The id a viewer's track memory is kept under (docs/12): the series for an episode, the item
/// itself otherwise (a movie keeps its own choice).
pub fn track_memory_subject(series_id: Option<&str>, item_id: &str) -> String {
    series_id.unwrap_or(item_id).to_string()
}

/// The on-disk shape of a non-null [`TrackMemory`]: a bare key string or one of the tagged objects;
/// `Other` swallows any shape this build does not know (including a malformed `track` body) so one
/// odd entry cannot fail the whole map.
#[derive(Deserialize)]
#[serde(untagged)]
enum TrackMemoryRepr {
    Key(String),
    Off { off: bool },
    Track { track: TrackIdentity },
    Other(serde::de::IgnoredAny),
}

/// The write side of [`TrackMemoryRepr::Off`].
#[derive(Serialize)]
struct OffTag {
    off: bool,
}

/// The write side of [`TrackMemoryRepr::Track`].
#[derive(Serialize)]
struct TrackTag<'a> {
    track: &'a TrackIdentity,
}

impl Serialize for TrackMemory {
    fn serialize<S: serde::Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        match self {
            TrackMemory::Unset => serializer.serialize_none(),
            TrackMemory::Off => OffTag { off: true }.serialize(serializer),
            TrackMemory::Key(key) => serializer.serialize_str(key),
            TrackMemory::Track(identity) => TrackTag { track: identity }.serialize(serializer),
        }
    }
}

impl<'de> Deserialize<'de> for TrackMemory {
    fn deserialize<D: serde::Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        Ok(
            match Option::<TrackMemoryRepr>::deserialize(deserializer)? {
                Some(TrackMemoryRepr::Off { off: true }) => TrackMemory::Off,
                Some(TrackMemoryRepr::Key(key)) => TrackMemory::Key(key),
                Some(TrackMemoryRepr::Track { track })
                    if track.lang.is_some() || track.title.is_some() =>
                {
                    TrackMemory::Track(track)
                }
                // An identity with neither language nor title matches no track
                // ([`crate::tracks::track_identity`]'s rule) and would block the global fallback.
                _ => TrackMemory::Unset,
            },
        )
    }
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

    fn english_identity() -> TrackIdentity {
        TrackIdentity {
            lang: Some("eng".into()),
            title: Some("English".into()),
            forced: false,
            sdh: true,
            codec: Some("subrip".into()),
        }
    }

    #[test]
    fn only_key_and_track_memories_name_a_track() {
        assert!(!TrackMemory::Unset.names_track());
        assert!(!TrackMemory::Off.names_track());
        assert!(TrackMemory::Key("eng".into()).names_track());
        assert!(TrackMemory::Track(english_identity()).names_track());
    }

    /// docs/12: an episode is remembered under its series, anything else (a movie) under itself.
    #[test]
    fn the_memory_subject_is_the_series_for_an_episode_and_the_item_for_a_movie() {
        assert_eq!(
            track_memory_subject(Some("series-1"), "episode-1"),
            "series-1"
        );
        assert_eq!(track_memory_subject(None, "movie-1"), "movie-1");
    }

    #[test]
    fn a_track_memory_round_trips_as_a_tagged_object() {
        let memory = TrackMemory::Track(english_identity());
        let json = serde_json::to_string(&memory).expect("serialize");
        assert!(json.starts_with(r#"{"track":{"#), "{json}");
        assert_eq!(
            serde_json::from_str::<TrackMemory>(&json).expect("parse"),
            memory
        );
    }

    #[test]
    fn a_track_memory_with_missing_fields_defaults_them() {
        let memory: TrackMemory =
            serde_json::from_str(r#"{"track":{"lang":"eng"}}"#).expect("parse");
        assert_eq!(
            memory,
            TrackMemory::Track(TrackIdentity {
                lang: Some("eng".into()),
                ..TrackIdentity::default()
            })
        );
    }

    /// An identity with neither language nor title matches no track, so it must not block the
    /// global fallback.
    #[test]
    fn a_track_memory_with_neither_language_nor_title_loads_as_unset() {
        for json in [
            r#"{"track":{}}"#,
            r#"{"track":{"forced":true,"codec":"srt"}}"#,
        ] {
            let memory: TrackMemory = serde_json::from_str(json).expect("parse");
            assert_eq!(memory, TrackMemory::Unset, "{json}");
        }
    }

    /// docs/18 §3.1: the tagged shapes never collide with each other or with a key string.
    #[test]
    fn off_and_legacy_key_still_read_beside_the_track_shape() {
        let off: TrackMemory = serde_json::from_str(r#"{"off":true}"#).expect("off");
        let key: TrackMemory = serde_json::from_str(r#""eng""#).expect("key");
        assert_eq!(off, TrackMemory::Off);
        assert_eq!(key, TrackMemory::Key("eng".into()));
    }

    #[test]
    fn a_file_mixing_every_shape_loads_and_an_unknown_track_body_only_forgets_itself() {
        let json = format!(
            r#"{{
                "a": {{"audio": "eng", "subtitle": {{"off": true}}}},
                "b": {{"audio": null, "subtitle": {}}},
                "c": {{"audio": {{"track": 5}}, "subtitle": "spa"}},
                "d": {{"audio": {{"track": {{"forced": "yes"}}}}, "subtitle": {{"track": {{"lang": "fre"}}}}}}
            }}"#,
            serde_json::to_string(&TrackMemory::Track(english_identity())).expect("serialize"),
        );
        let loaded: std::collections::HashMap<String, SeriesTrackPref> =
            serde_json::from_str(&json).expect("one odd entry must not fail the map");
        assert_eq!(loaded["a"].audio, TrackMemory::Key("eng".into()));
        assert_eq!(loaded["a"].subtitle, TrackMemory::Off);
        assert_eq!(loaded["b"].audio, TrackMemory::Unset);
        assert_eq!(loaded["b"].subtitle, TrackMemory::Track(english_identity()));
        assert_eq!(loaded["c"].audio, TrackMemory::Unset);
        assert_eq!(loaded["c"].subtitle, TrackMemory::Key("spa".into()));
        assert_eq!(loaded["d"].audio, TrackMemory::Unset);
        assert_eq!(
            loaded["d"].subtitle,
            TrackMemory::Track(TrackIdentity {
                lang: Some("fre".into()),
                ..TrackIdentity::default()
            })
        );
    }
}
