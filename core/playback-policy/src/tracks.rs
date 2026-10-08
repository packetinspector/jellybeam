//! Audio/subtitle track selection.
//!
//! Fully pure: tracks in, prefs in, chosen ids out,
//! unit-testable without a live player session.

use crate::prefs::{LanguagePrefs, SeriesTrackPref, SubtitleMode};

/// A single audio/video/subtitle track, as reported by the playback backend.
#[derive(Debug, Clone)]
pub struct Track {
    pub id: i64,
    pub kind: TrackKind,
    pub title: Option<String>,
    pub lang: Option<String>,
    pub codec: Option<String>,
    pub default: bool,
    pub selected: bool,
    /// The container's own "forced" flag (foreign-dialogue burned-in subs); `false` elsewhere.
    pub forced: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TrackKind {
    Video,
    Audio,
    Subtitle,
}

/// The persistence key for one [`Track`]: its normalized language code if present, else its title
/// -- backend track ids aren't stable across items/relaunches, but lang/title usually is within a
/// series.
pub fn track_pref_key(track: &Track) -> Option<String> {
    track
        .lang
        .as_deref()
        .map(normalize_lang)
        .or_else(|| track.title.clone())
}

/// Finds the first track of `kind` matching a persisted per-series `key`, to re-apply it once real
/// track ids are known. Languages compare normalized, so a key saved from a sidecar (`en`) matches
/// a burn-in stream (`eng`) and keys saved before normalization still match.
pub fn find_track_by_key(tracks: &[Track], kind: TrackKind, key: &str) -> Option<i64> {
    tracks
        .iter()
        .find(|t| {
            t.kind == kind
                && match &t.lang {
                    Some(lang) => lang_matches(lang, key),
                    None => t.title.as_deref() == Some(key),
                }
        })
        .map(|t| t.id)
}

/// Canonicalizes an ISO 639-2 code to its "B" (bibliographic) form so e.g. `"deu"`/`"ger"` compare
/// equal -- `Track::lang` can carry either the "B" or "T" form depending on the muxing tool. Covers
/// only the B/T pairs likely to appear in a Jellyfin library.
fn normalize_lang(code: &str) -> String {
    let lower = code.trim().to_ascii_lowercase();
    // ISO 639-1 two-letter forms fold to the same 639-2 "B" target as their 639-2 "T" alias
    // (Media3 uses 2-letter, prefs use 3-letter) -- one arm per canonical target.
    let canonical = match lower.as_str() {
        "deu" | "de" => "ger",
        "fra" | "fr" => "fre",
        "zho" | "zh" => "chi",
        "nld" | "nl" => "dut",
        "ces" | "cs" => "cze",
        "ell" | "el" => "gre",
        "eus" | "eu" => "baq",
        "fas" | "fa" => "per",
        "isl" | "is" => "ice",
        "kat" | "ka" => "geo",
        "mkd" | "mk" => "mac",
        "mri" | "mi" => "mao",
        "msa" | "ms" => "may",
        "mya" | "my" => "bur",
        "ron" | "ro" => "rum",
        "slk" | "sk" => "slo",
        "sqi" | "sq" => "alb",
        "hye" | "hy" => "arm",
        "bod" | "bo" => "tib",
        "cym" | "cy" => "wel",
        // 639-1 codes with no separate 639-2 B/T alias -- the 639-2 target is already unambiguous.
        "en" => "eng",
        "ja" => "jpn",
        "es" => "spa",
        "it" => "ita",
        "ko" => "kor",
        "pt" => "por",
        "ru" => "rus",
        "sv" => "swe",
        "no" => "nor",
        "da" => "dan",
        "fi" => "fin",
        "pl" => "pol",
        "ar" => "ara",
        "he" => "heb",
        "hi" => "hin",
        "tr" => "tur",
        other => other,
    };
    canonical.to_string()
}

/// Case-insensitive, B/T-tolerant language-code comparison; see [`normalize_lang`].
pub fn lang_matches(a: &str, b: &str) -> bool {
    normalize_lang(a) == normalize_lang(b)
}

/// What to do about subtitles once resolved; three outcomes rather than `Option<i64>` because
/// `SubtitleMode::None` must actively turn subtitles off, distinct from leaving them alone.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SubtitleDecision {
    /// Don't touch subtitle selection; the stream/server's own selection stays in effect.
    Leave,
    /// Explicitly disable subtitles.
    Off,
    /// Explicitly select this subtitle track.
    Track(i64),
}

/// The result of [`resolve_track_selection`]: which audio track (if any) to switch to, and what to
/// do about subtitles.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TrackDecision {
    /// `Some(id)` to switch to that audio track; `None` leaves the stream/server default alone.
    pub audio: Option<i64>,
    pub subtitle: SubtitleDecision,
}

/// The track that's actually playing (or would, absent any preference): the stream's `selected`
/// flag if set, else `default`, else the first track of that kind. Shared by the audio branch and
/// (via `effective_audio_lang`) `OnlyForced` subtitle matching.
fn current_or_default_track(tracks: &[Track], kind: TrackKind) -> Option<&Track> {
    tracks
        .iter()
        .find(|t| t.kind == kind && t.selected)
        .or_else(|| tracks.iter().find(|t| t.kind == kind && t.default))
        .or_else(|| tracks.iter().find(|t| t.kind == kind))
}

/// Core decision table: pure (tracks in, prefs in, chosen ids out), called once per playback
/// session right after tracks are announced.
///
/// Precedence: `series_pref` wins over `global` per-field when present; `global` only fills
/// whichever half the series memory doesn't cover.
///
/// Audio: `global.audio` (if set) picks the first track whose `lang` matches ([`lang_matches`]); no
/// match or no preference leaves the default alone.
///
/// Subtitles, by `global.subtitle_mode`:
/// - `Default`: leave alone.
/// - `Always`: `global.subtitle` match if present, else the default-flagged track, else the first,
///   else `Leave`.
/// - `OnlyForced`: a `forced` track matching the *actually-playing* audio language (ignores
///   `global.subtitle`); otherwise `Off`.
/// - `None`: always `Off`.
pub fn resolve_track_selection(
    tracks: &[Track],
    series_pref: Option<&SeriesTrackPref>,
    global: &LanguagePrefs,
) -> TrackDecision {
    let audio = if let Some(key) = series_pref.and_then(|p| p.audio.as_ref()) {
        find_track_by_key(tracks, TrackKind::Audio, key)
    } else {
        global.audio.as_deref().and_then(|lang| {
            tracks
                .iter()
                .find(|t| {
                    t.kind == TrackKind::Audio
                        && t.lang.as_deref().is_some_and(|l| lang_matches(l, lang))
                })
                .map(|t| t.id)
        })
    };

    // Audio language actually playing, for `OnlyForced` matching: the just-resolved switch's
    // language, else the current selection/default.
    let effective_audio_lang: Option<String> = audio
        .and_then(|id| tracks.iter().find(|t| t.id == id))
        .and_then(|t| t.lang.clone())
        .or_else(|| {
            current_or_default_track(tracks, TrackKind::Audio).and_then(|t| t.lang.clone())
        });

    let subtitle = if let Some(key) = series_pref.and_then(|p| p.subtitle.as_ref()) {
        find_track_by_key(tracks, TrackKind::Subtitle, key)
            .map(SubtitleDecision::Track)
            .unwrap_or(SubtitleDecision::Leave)
    } else {
        match global.subtitle_mode {
            SubtitleMode::Default => SubtitleDecision::Leave,
            SubtitleMode::None => SubtitleDecision::Off,
            SubtitleMode::Always => {
                let matched = global.subtitle.as_deref().and_then(|lang| {
                    tracks.iter().find(|t| {
                        t.kind == TrackKind::Subtitle
                            && t.lang.as_deref().is_some_and(|l| lang_matches(l, lang))
                    })
                });
                let chosen = matched
                    .or_else(|| {
                        tracks
                            .iter()
                            .find(|t| t.kind == TrackKind::Subtitle && t.default)
                    })
                    .or_else(|| tracks.iter().find(|t| t.kind == TrackKind::Subtitle));
                chosen
                    .map(|t| SubtitleDecision::Track(t.id))
                    .unwrap_or(SubtitleDecision::Leave)
            }
            SubtitleMode::OnlyForced => {
                // A transcode's audio track carries no language; the forced track already showing
                // (a server burn-in) is then the best evidence, never a guess at another.
                let forced = tracks.iter().find(|t| {
                    t.kind == TrackKind::Subtitle
                        && t.forced
                        && match effective_audio_lang.as_deref() {
                            Some(al) => t.lang.as_deref().is_some_and(|sl| lang_matches(sl, al)),
                            None => t.selected,
                        }
                });
                forced
                    .map(|t| SubtitleDecision::Track(t.id))
                    .unwrap_or(SubtitleDecision::Off)
            }
        }
    };

    TrackDecision { audio, subtitle }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn lang_matches_is_case_insensitive() {
        assert!(lang_matches("ENG", "eng"));
        assert!(lang_matches("Eng", "eNG"));
    }

    #[test]
    fn lang_matches_treats_bibliographic_and_terminological_forms_as_equal() {
        // Three canonical B/T pairs.
        assert!(lang_matches("deu", "ger"));
        assert!(lang_matches("ger", "deu"));
        assert!(lang_matches("fra", "fre"));
        assert!(lang_matches("fre", "fra"));
        assert!(lang_matches("zho", "chi"));
        assert!(lang_matches("chi", "zho"));
    }

    #[test]
    fn lang_matches_rejects_genuinely_different_languages() {
        assert!(!lang_matches("eng", "spa"));
        assert!(!lang_matches("jpn", "kor"));
    }

    #[test]
    fn lang_matches_folds_iso_639_1_two_letter_forms() {
        // Media3 normalizes to 2-letter forms; both directions must match, including through a B/T
        // pair.
        assert!(lang_matches("en", "eng"));
        assert!(lang_matches("eng", "en"));
        assert!(lang_matches("ja", "jpn"));
        assert!(lang_matches("de", "deu"));
        assert!(lang_matches("de", "ger"));
        assert!(lang_matches("zh", "zho"));
        assert!(lang_matches("cs", "ces"));
        // Unrelated languages still differ, in both widths.
        assert!(!lang_matches("en", "spa"));
        assert!(!lang_matches("no", "nld"));
    }

    fn track(kind: TrackKind, id: i64, lang: Option<&str>) -> Track {
        Track {
            id,
            kind,
            title: None,
            lang: lang.map(str::to_string),
            codec: None,
            default: false,
            selected: false,
            forced: false,
        }
    }

    fn default_flag(mut t: Track) -> Track {
        t.default = true;
        t
    }

    fn selected_flag(mut t: Track) -> Track {
        t.selected = true;
        t
    }

    fn forced_flag(mut t: Track) -> Track {
        t.forced = true;
        t
    }

    fn no_global_prefs() -> LanguagePrefs {
        LanguagePrefs::default()
    }

    #[test]
    fn resolve_track_selection_with_no_prefs_at_all_leaves_everything_alone() {
        let tracks = vec![
            track(TrackKind::Audio, 1, Some("eng")),
            track(TrackKind::Subtitle, 2, Some("eng")),
        ];
        let decision = resolve_track_selection(&tracks, None, &no_global_prefs());
        assert_eq!(decision.audio, None);
        assert_eq!(decision.subtitle, SubtitleDecision::Leave);
    }

    #[test]
    fn resolve_track_selection_matches_preferred_audio_language() {
        let tracks = vec![
            track(TrackKind::Audio, 1, Some("eng")),
            track(TrackKind::Audio, 2, Some("jpn")),
        ];
        let global = LanguagePrefs {
            audio: Some("jpn".to_string()),
            ..no_global_prefs()
        };
        let decision = resolve_track_selection(&tracks, None, &global);
        assert_eq!(decision.audio, Some(2));
    }

    #[test]
    fn resolve_track_selection_leaves_audio_alone_when_preference_has_no_match() {
        let tracks = vec![track(TrackKind::Audio, 1, Some("eng"))];
        let global = LanguagePrefs {
            audio: Some("kor".to_string()),
            ..no_global_prefs()
        };
        let decision = resolve_track_selection(&tracks, None, &global);
        assert_eq!(
            decision.audio, None,
            "no match must leave the default alone"
        );
    }

    #[test]
    fn resolve_track_selection_default_mode_never_touches_subtitles() {
        let tracks = vec![default_flag(track(TrackKind::Subtitle, 1, Some("eng")))];
        let global = LanguagePrefs {
            subtitle: Some("eng".to_string()),
            subtitle_mode: SubtitleMode::Default,
            ..no_global_prefs()
        };
        let decision = resolve_track_selection(&tracks, None, &global);
        assert_eq!(decision.subtitle, SubtitleDecision::Leave);
    }

    #[test]
    fn resolve_track_selection_always_mode_matches_preferred_subtitle_language() {
        let tracks = vec![
            track(TrackKind::Subtitle, 1, Some("eng")),
            track(TrackKind::Subtitle, 2, Some("spa")),
        ];
        let global = LanguagePrefs {
            subtitle: Some("spa".to_string()),
            subtitle_mode: SubtitleMode::Always,
            ..no_global_prefs()
        };
        let decision = resolve_track_selection(&tracks, None, &global);
        assert_eq!(decision.subtitle, SubtitleDecision::Track(2));
    }

    #[test]
    fn resolve_track_selection_always_mode_falls_back_to_default_flagged_track() {
        let tracks = vec![
            track(TrackKind::Subtitle, 1, Some("eng")),
            default_flag(track(TrackKind::Subtitle, 2, Some("spa"))),
        ];
        // No language set at all -- falls to the default-flagged track.
        let global = LanguagePrefs {
            subtitle_mode: SubtitleMode::Always,
            ..no_global_prefs()
        };
        let decision = resolve_track_selection(&tracks, None, &global);
        assert_eq!(decision.subtitle, SubtitleDecision::Track(2));
    }

    #[test]
    fn resolve_track_selection_always_mode_falls_back_to_first_track_absent_default() {
        let tracks = vec![
            track(TrackKind::Subtitle, 5, Some("eng")),
            track(TrackKind::Subtitle, 6, Some("spa")),
        ];
        // Preference set but unmatched, no default flagged either -- falls to the first track.
        let global = LanguagePrefs {
            subtitle: Some("kor".to_string()),
            subtitle_mode: SubtitleMode::Always,
            ..no_global_prefs()
        };
        let decision = resolve_track_selection(&tracks, None, &global);
        assert_eq!(decision.subtitle, SubtitleDecision::Track(5));
    }

    #[test]
    fn resolve_track_selection_only_forced_matches_the_playing_audio_language() {
        let tracks = vec![
            selected_flag(track(TrackKind::Audio, 1, Some("jpn"))),
            forced_flag(track(TrackKind::Subtitle, 2, Some("jpn"))),
            track(TrackKind::Subtitle, 3, Some("eng")),
        ];
        let global = LanguagePrefs {
            subtitle_mode: SubtitleMode::OnlyForced,
            ..no_global_prefs()
        };
        let decision = resolve_track_selection(&tracks, None, &global);
        assert_eq!(decision.subtitle, SubtitleDecision::Track(2));
    }

    #[test]
    fn resolve_track_selection_only_forced_matches_the_newly_selected_audio_preference() {
        // Forced sub must match the newly-switched audio language, not the prior selection.
        let tracks = vec![
            selected_flag(track(TrackKind::Audio, 1, Some("eng"))),
            track(TrackKind::Audio, 4, Some("jpn")),
            forced_flag(track(TrackKind::Subtitle, 2, Some("jpn"))),
        ];
        let global = LanguagePrefs {
            audio: Some("jpn".to_string()),
            subtitle_mode: SubtitleMode::OnlyForced,
            ..no_global_prefs()
        };
        let decision = resolve_track_selection(&tracks, None, &global);
        assert_eq!(decision.audio, Some(4));
        assert_eq!(decision.subtitle, SubtitleDecision::Track(2));
    }

    #[test]
    fn resolve_track_selection_only_forced_turns_off_without_a_matching_forced_track() {
        let tracks = vec![
            selected_flag(track(TrackKind::Audio, 1, Some("eng"))),
            // Forced, but wrong language.
            forced_flag(track(TrackKind::Subtitle, 2, Some("jpn"))),
        ];
        let global = LanguagePrefs {
            subtitle_mode: SubtitleMode::OnlyForced,
            ..no_global_prefs()
        };
        let decision = resolve_track_selection(&tracks, None, &global);
        assert_eq!(decision.subtitle, SubtitleDecision::Off);
    }

    #[test]
    fn resolve_track_selection_none_mode_always_turns_subtitles_off() {
        let tracks = vec![default_flag(track(TrackKind::Subtitle, 1, Some("eng")))];
        let global = LanguagePrefs {
            subtitle_mode: SubtitleMode::None,
            ..no_global_prefs()
        };
        let decision = resolve_track_selection(&tracks, None, &global);
        assert_eq!(decision.subtitle, SubtitleDecision::Off);
    }

    #[test]
    fn resolve_track_selection_per_series_pref_overrides_globals() {
        let tracks = vec![
            track(TrackKind::Audio, 1, Some("eng")),
            track(TrackKind::Audio, 2, Some("jpn")),
            track(TrackKind::Subtitle, 3, Some("eng")),
            track(TrackKind::Subtitle, 4, Some("spa")),
        ];
        // Series memory wins outright over conflicting global prefs, not blended.
        let series_pref = SeriesTrackPref {
            audio: Some("eng".to_string()),
            subtitle: Some("spa".to_string()),
        };
        let global = LanguagePrefs {
            audio: Some("jpn".to_string()),
            subtitle: Some("eng".to_string()),
            subtitle_mode: SubtitleMode::Always,
        };
        let decision = resolve_track_selection(&tracks, Some(&series_pref), &global);
        assert_eq!(decision.audio, Some(1));
        assert_eq!(decision.subtitle, SubtitleDecision::Track(4));
    }

    /// A series pref saved from one delivery's language form resolves against another's: sidecar
    /// and Media3 rows carry 2-letter codes, burn-in rows the server's 3-letter ones.
    #[test]
    fn a_series_subtitle_pref_matches_across_language_code_forms() {
        let global = LanguagePrefs::default();
        for (saved_from, offered) in [("en", "eng"), ("eng", "en"), ("de", "ger"), ("deu", "ger")] {
            let saved = track_pref_key(&track(TrackKind::Subtitle, 9, Some(saved_from)));
            let series_pref = SeriesTrackPref {
                audio: None,
                subtitle: saved.clone(),
            };
            let tracks = vec![
                track(TrackKind::Audio, 1, Some("jpn")),
                track(TrackKind::Subtitle, -1003, Some("spa")),
                track(TrackKind::Subtitle, -1002, Some(offered)),
            ];
            let decision = resolve_track_selection(&tracks, Some(&series_pref), &global);
            assert_eq!(
                decision.subtitle,
                SubtitleDecision::Track(-1002),
                "{saved_from} -> {offered}"
            );
            // A key saved before keys were normalized still matches.
            let legacy = SeriesTrackPref {
                audio: None,
                subtitle: Some(saved_from.to_string()),
            };
            let decision = resolve_track_selection(&tracks, Some(&legacy), &global);
            assert_eq!(decision.subtitle, SubtitleDecision::Track(-1002));
        }
    }

    #[test]
    fn a_title_key_matches_only_a_track_without_a_language() {
        let mut titled = track(TrackKind::Subtitle, 2, None);
        titled.title = Some("Signs".to_string());
        let mut english = track(TrackKind::Subtitle, 3, Some("eng"));
        english.title = Some("Signs".to_string());
        assert_eq!(
            find_track_by_key(&[english.clone(), titled], TrackKind::Subtitle, "Signs"),
            Some(2)
        );
        assert_eq!(
            find_track_by_key(&[english], TrackKind::Subtitle, "Signs"),
            None
        );
    }

    #[test]
    fn only_forced_keeps_the_forced_track_showing_when_the_audio_language_is_unknown() {
        let only_forced = LanguagePrefs {
            subtitle_mode: SubtitleMode::OnlyForced,
            ..LanguagePrefs::default()
        };
        let mut burned = track(TrackKind::Subtitle, -1002, Some("eng"));
        burned.forced = true;
        let mut other = track(TrackKind::Subtitle, -1003, Some("ger"));
        other.forced = true;
        let audio = track(TrackKind::Audio, 1, None);
        for (selected, expected) in [
            (true, SubtitleDecision::Track(-1002)),
            (false, SubtitleDecision::Off),
        ] {
            burned.selected = selected;
            let tracks = vec![audio.clone(), other.clone(), burned.clone()];
            let decision = resolve_track_selection(&tracks, None, &only_forced);
            assert_eq!(decision.subtitle, expected, "selected={selected}");
        }
    }
}
