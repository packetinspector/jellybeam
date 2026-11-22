//! Audio/subtitle track selection.
//!
//! Fully pure: tracks in, prefs in, chosen ids out,
//! unit-testable without a live player session.

use crate::prefs::{LanguagePrefs, SeriesTrackPref, SubtitleMode, TrackIdentity, TrackMemory};

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
    /// The server's hearing-impaired (SDH) flag; `false` where the source doesn't carry it.
    pub hearing_impaired: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TrackKind {
    Video,
    Audio,
    Subtitle,
}

/// The identity a [`Track`] is remembered under, or `None` for a track with neither language nor
/// title (nothing durable to match it by).
pub fn track_identity(track: &Track) -> Option<TrackIdentity> {
    if track.lang.is_none() && track.title.is_none() {
        return None;
    }
    Some(TrackIdentity {
        lang: track.lang.as_deref().map(normalize_lang),
        title: track.title.clone(),
        forced: track.forced,
        sdh: track.hearing_impaired,
        codec: track.codec.clone(),
    })
}

/// The first track of `kind` that passes `matches`: the one plain lookup (ranked picks go through
/// [`best_ranked`]).
fn first_where(
    tracks: &[Track],
    kind: TrackKind,
    matches: impl Fn(&Track) -> bool,
) -> Option<&Track> {
    tracks.iter().find(|t| t.kind == kind && matches(t))
}

/// The highest-`score` track of `kind` that passes `candidate`; the earliest wins a tie
/// (`max_by_key` keeps the last maximum, so the scan runs in reverse).
fn best_ranked<K: Ord>(
    tracks: &[Track],
    kind: TrackKind,
    candidate: impl Fn(&Track) -> bool,
    score: impl Fn(&Track) -> K,
) -> Option<&Track> {
    tracks
        .iter()
        .rev()
        .filter(|t| t.kind == kind && candidate(t))
        .max_by_key(|t| score(t))
}

/// The track carries a language that [`lang_matches`] `lang`.
fn in_language(track: &Track, lang: &str) -> bool {
    track.lang.as_deref().is_some_and(|l| lang_matches(l, lang))
}

/// Ranking for a pick that has no identity to follow: the plain track before Forced, then before
/// SDH.
fn plain_first(track: &Track) -> (bool, bool) {
    (!track.forced, !track.hearing_impaired)
}

/// Trimmed, case-insensitive equality of two optional labels (title, codec); two absent labels agree.
fn same_label(a: Option<&str>, b: Option<&str>) -> bool {
    match (a, b) {
        (Some(a), Some(b)) => a.trim().eq_ignore_ascii_case(b.trim()),
        (None, None) => true,
        _ => false,
    }
}

/// `(spelling, name)`: Media3's codec/mime forms and the server's names for one audio or subtitle
/// format, so a codec remembered from either delivery matches the other; an unlisted codec is its
/// own name.
const CODEC_NAMES: [(&str, &str); 28] = [
    ("application/x-subrip", "srt"),
    ("subrip", "srt"),
    ("text/x-ssa", "ass"),
    ("ssa", "ass"),
    ("text/vtt", "vtt"),
    ("webvtt", "vtt"),
    ("application/pgs", "pgs"),
    ("hdmv_pgs_subtitle", "pgs"),
    ("pgssub", "pgs"),
    ("application/vobsub", "vobsub"),
    ("dvd_subtitle", "vobsub"),
    ("dvdsub", "vobsub"),
    ("ac-3", "ac3"),
    ("audio/ac3", "ac3"),
    ("ec-3", "eac3"),
    ("audio/eac3", "eac3"),
    ("audio/eac3-joc", "eac3"),
    ("audio/true-hd", "truehd"),
    ("audio/vnd.dts", "dts"),
    ("audio/vnd.dts.hd", "dts"),
    ("dtsc", "dts"),
    ("dtsh", "dts"),
    ("dtsl", "dts"),
    ("audio/mp4a-latm", "aac"),
    ("audio/flac", "flac"),
    ("audio/opus", "opus"),
    ("audio/mpeg", "mp3"),
    ("audio/vorbis", "vorbis"),
];

/// A codec's [`CODEC_NAMES`] name, trimmed; Media3's `mp4a.40.*` codec strings are all AAC.
fn codec_name(codec: &str) -> &str {
    let codec = codec.trim();
    if codec
        .get(..5)
        .is_some_and(|p| p.eq_ignore_ascii_case("mp4a."))
    {
        return "aac";
    }
    CODEC_NAMES
        .iter()
        .find(|(spelling, _)| spelling.eq_ignore_ascii_case(codec))
        .map_or(codec, |&(_, name)| name)
}

/// Two optional codecs agree once each is folded to its [`codec_name`].
fn same_codec(a: Option<&str>, b: Option<&str>) -> bool {
    same_label(a.map(codec_name), b.map(codec_name))
}

/// Re-finds a remembered choice among the real tracks of `kind` (docs/12 "Choices are remembered
/// per series"); `Unset`/`Off` name no track.
/// - `Track(identity)`: same language (or, with none, same title on a track with no language); a
///   remembered Forced admits only forced tracks, then the closest on forced, SDH, title, codec,
///   in that order.
/// - `Key(lang_or_title)` (legacy, language only): same language -- or same title on a track with
///   no language -- preferring the plain track so a series stuck on Forced recovers.
///
/// Languages compare normalized, so a memory saved from a sidecar (`en`) matches a burn-in stream
/// (`eng`).
pub fn find_remembered(tracks: &[Track], kind: TrackKind, memory: &TrackMemory) -> Option<i64> {
    let found = match memory {
        TrackMemory::Unset | TrackMemory::Off => None,
        TrackMemory::Key(key) => best_ranked(
            tracks,
            kind,
            |t| in_language(t, key) || (t.lang.is_none() && t.title.as_deref() == Some(key)),
            plain_first,
        ),
        TrackMemory::Track(id) => best_ranked(
            tracks,
            kind,
            |t| {
                (t.forced || !id.forced)
                    && match &id.lang {
                        Some(lang) => in_language(t, lang),
                        None => {
                            id.title.is_some()
                                && t.lang.is_none()
                                && same_label(t.title.as_deref(), id.title.as_deref())
                        }
                    }
            },
            |t| {
                (
                    t.forced == id.forced,
                    t.hearing_impaired == id.sdh,
                    same_label(t.title.as_deref(), id.title.as_deref()),
                    same_codec(t.codec.as_deref(), id.codec.as_deref()),
                )
            },
        ),
    };
    found.map(|t| t.id)
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
    first_where(tracks, kind, |t| t.selected).or_else(|| default_or_first(tracks, kind))
}

/// The default-flagged track of `kind`, else the first.
fn default_or_first(tracks: &[Track], kind: TrackKind) -> Option<&Track> {
    first_where(tracks, kind, |t| t.default).or_else(|| first_where(tracks, kind, |_| true))
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
/// - `Always`: `global.subtitle` match if present (the plain track before Forced, then SDH), else
///   the default-flagged track, else the first, else `Leave`.
/// - `OnlyForced`: a `forced` track matching the *actually-playing* audio language (ignores
///   `global.subtitle`); otherwise `Off`.
/// - `None`: always `Off`.
pub fn resolve_track_selection(
    tracks: &[Track],
    series_pref: Option<&SeriesTrackPref>,
    global: &LanguagePrefs,
) -> TrackDecision {
    let audio = if let Some(memory) = series_pref.map(|p| &p.audio).filter(|m| m.names_track()) {
        find_remembered(tracks, TrackKind::Audio, memory)
    } else {
        global.audio.as_deref().and_then(|lang| {
            first_where(tracks, TrackKind::Audio, |t| in_language(t, lang)).map(|t| t.id)
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

    let remembered_subtitle = series_pref.map(|p| &p.subtitle);
    let subtitle = if let Some(TrackMemory::Off) = remembered_subtitle {
        // docs/18 §3.1: a remembered "subtitles off" decides before the global mode.
        SubtitleDecision::Off
    } else if let Some(memory) = remembered_subtitle.filter(|m| m.names_track()) {
        find_remembered(tracks, TrackKind::Subtitle, memory)
            .map(SubtitleDecision::Track)
            .unwrap_or(SubtitleDecision::Leave)
    } else {
        match global.subtitle_mode {
            SubtitleMode::Default => SubtitleDecision::Leave,
            SubtitleMode::None => SubtitleDecision::Off,
            SubtitleMode::Always => {
                let chosen = global
                    .subtitle
                    .as_deref()
                    .and_then(|lang| {
                        best_ranked(
                            tracks,
                            TrackKind::Subtitle,
                            |t| in_language(t, lang),
                            plain_first,
                        )
                    })
                    .or_else(|| default_or_first(tracks, TrackKind::Subtitle));
                chosen
                    .map(|t| SubtitleDecision::Track(t.id))
                    .unwrap_or(SubtitleDecision::Leave)
            }
            SubtitleMode::OnlyForced => {
                // A transcode's audio track carries no language; the forced track already showing
                // (a server burn-in) is then the best evidence, never a guess at another.
                let forced = first_where(tracks, TrackKind::Subtitle, |t| {
                    t.forced
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
    use crate::prefs::TrackMemory;

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
            hearing_impaired: false,
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

    fn sdh_flag(mut t: Track) -> Track {
        t.hearing_impaired = true;
        t
    }

    fn titled(mut t: Track, title: &str) -> Track {
        t.title = Some(title.to_string());
        t
    }

    fn coded(mut t: Track, codec: &str) -> Track {
        t.codec = Some(codec.to_string());
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
            audio: TrackMemory::Key("eng".to_string()),
            subtitle: TrackMemory::Key("spa".to_string()),
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
            let saved = track_identity(&track(TrackKind::Subtitle, 9, Some(saved_from)));
            let series_pref = SeriesTrackPref {
                audio: TrackMemory::Unset,
                subtitle: saved.map_or(TrackMemory::Unset, TrackMemory::Track),
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
                audio: TrackMemory::Unset,
                subtitle: TrackMemory::Key(saved_from.to_string()),
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
        let key = TrackMemory::Key("Signs".to_string());
        assert_eq!(
            find_remembered(&[english.clone(), titled], TrackKind::Subtitle, &key),
            Some(2)
        );
        assert_eq!(find_remembered(&[english], TrackKind::Subtitle, &key), None);
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

    /// One audio track and three subtitles: a plain English one, a Spanish one the stream flags
    /// default, and a forced English one that `OnlyForced` would pick.
    fn remembered_choice_tracks() -> Vec<Track> {
        vec![
            track(TrackKind::Audio, 1, Some("eng")),
            track(TrackKind::Audio, 2, Some("jpn")),
            track(TrackKind::Subtitle, 10, Some("eng")),
            default_flag(track(TrackKind::Subtitle, 11, Some("spa"))),
            forced_flag(track(TrackKind::Subtitle, 12, Some("eng"))),
        ]
    }

    fn global_mode(mode: SubtitleMode) -> LanguagePrefs {
        LanguagePrefs {
            subtitle_mode: mode,
            ..no_global_prefs()
        }
    }

    fn subtitle_memory(memory: TrackMemory) -> SeriesTrackPref {
        SeriesTrackPref {
            audio: TrackMemory::Unset,
            subtitle: memory,
        }
    }

    /// docs/12 (track selection, "Choices are remembered per series") and docs/18 §3.1: each
    /// remembered subtitle state against each global mode, with a default-flagged and a forced
    /// track present; every mismatching row is reported together.
    #[test]
    fn a_remembered_subtitle_choice_decides_before_the_global_mode() {
        use SubtitleDecision::{Leave, Off, Track as Pick};
        let tracks = remembered_choice_tracks();
        let rows: Vec<(&str, TrackMemory, SubtitleMode, SubtitleDecision)> = vec![
            ("Unset", TrackMemory::Unset, SubtitleMode::Default, Leave),
            ("Unset", TrackMemory::Unset, SubtitleMode::Always, Pick(11)),
            (
                "Unset",
                TrackMemory::Unset,
                SubtitleMode::OnlyForced,
                Pick(12),
            ),
            ("Unset", TrackMemory::Unset, SubtitleMode::None, Off),
            ("Off", TrackMemory::Off, SubtitleMode::Default, Off),
            ("Off", TrackMemory::Off, SubtitleMode::Always, Off),
            ("Off", TrackMemory::Off, SubtitleMode::OnlyForced, Off),
            ("Off", TrackMemory::Off, SubtitleMode::None, Off),
            (
                "Key(eng)",
                TrackMemory::Key("eng".into()),
                SubtitleMode::Default,
                Pick(10),
            ),
            (
                "Key(eng)",
                TrackMemory::Key("eng".into()),
                SubtitleMode::Always,
                Pick(10),
            ),
            (
                "Key(eng)",
                TrackMemory::Key("eng".into()),
                SubtitleMode::OnlyForced,
                Pick(10),
            ),
            (
                "Key(eng)",
                TrackMemory::Key("eng".into()),
                SubtitleMode::None,
                Pick(10),
            ),
            // A remembered key with no matching track is unspecified; it keeps leaving the stream's
            // own selection alone in every mode.
            (
                "Key(fre)",
                TrackMemory::Key("fre".into()),
                SubtitleMode::Default,
                Leave,
            ),
            (
                "Key(fre)",
                TrackMemory::Key("fre".into()),
                SubtitleMode::Always,
                Leave,
            ),
            (
                "Key(fre)",
                TrackMemory::Key("fre".into()),
                SubtitleMode::OnlyForced,
                Leave,
            ),
            (
                "Key(fre)",
                TrackMemory::Key("fre".into()),
                SubtitleMode::None,
                Leave,
            ),
        ];
        let mismatches: Vec<String> = rows
            .into_iter()
            .filter_map(|(label, memory, mode, expected)| {
                let pref = subtitle_memory(memory);
                let got =
                    resolve_track_selection(&tracks, Some(&pref), &global_mode(mode)).subtitle;
                (got != expected).then(|| {
                    format!("memory {label} x {mode:?}: expected {expected:?}, got {got:?}")
                })
            })
            .collect();
        assert!(mismatches.is_empty(), "{}", mismatches.join("\n"));
    }

    /// docs/12 "Choices are remembered per series": the audio and subtitle halves are independent.
    #[test]
    fn remembering_subtitles_off_leaves_the_remembered_audio_choice_in_force() {
        let tracks = remembered_choice_tracks();
        let pref = SeriesTrackPref {
            audio: TrackMemory::Key("jpn".to_string()),
            subtitle: TrackMemory::Off,
        };
        let decision =
            resolve_track_selection(&tracks, Some(&pref), &global_mode(SubtitleMode::Always));
        assert_eq!(decision.audio, Some(2));
        assert_eq!(decision.subtitle, SubtitleDecision::Off);
    }

    fn remembered(track: &Track) -> TrackMemory {
        TrackMemory::Track(track_identity(track).expect("identity"))
    }

    fn pick(tracks: &[Track], memory: &TrackMemory) -> Option<i64> {
        find_remembered(tracks, TrackKind::Subtitle, memory)
    }

    /// Runs `(label, tracks, expected)` rows through `got` and reports every mismatch together.
    fn assert_rows<T: PartialEq + std::fmt::Debug>(
        rows: Vec<(&str, Vec<Track>, T)>,
        got: impl Fn(&[Track]) -> T,
    ) {
        let misses: Vec<String> = rows
            .iter()
            .filter_map(|(label, tracks, want)| {
                let actual = got(tracks);
                (actual != *want).then(|| format!("{label}: expected {want:?}, got {actual:?}"))
            })
            .collect();
        assert!(misses.is_empty(), "{}", misses.join("\n"));
    }

    /// The reporter's case: the series remembered plain English, and the next episode lists Forced
    /// and SDH English first.
    #[test]
    fn a_remembered_full_english_beats_forced_and_sdh_listed_before_it() {
        let eng = |id| coded(track(TrackKind::Subtitle, id, Some("eng")), "subrip");
        let memory = remembered(&titled(eng(1), "English"));
        let next = [
            forced_flag(titled(eng(10), "English Forced")),
            sdh_flag(titled(eng(11), "English SDH")),
            titled(eng(12), "English"),
        ];
        assert_eq!(pick(&next, &memory), Some(12));
    }

    #[test]
    fn a_remembered_sdh_or_forced_track_is_picked_over_the_full_one() {
        let eng = |id| coded(track(TrackKind::Subtitle, id, Some("eng")), "subrip");
        let next = [
            titled(eng(10), "English"),
            sdh_flag(titled(eng(11), "English SDH")),
            forced_flag(titled(eng(12), "English Forced")),
        ];
        assert_eq!(pick(&next, &remembered(&next[1])), Some(11));
        assert_eq!(pick(&next, &remembered(&next[2])), Some(12));
    }

    /// Forced outranks SDH, which outranks title, which outranks codec; the earliest track wins a
    /// full tie.
    #[test]
    fn remembered_identity_ranks_forced_then_sdh_then_title_then_codec() {
        let memory = TrackMemory::Track(TrackIdentity {
            lang: Some("eng".into()),
            title: Some("Director".into()),
            forced: true,
            sdh: false,
            codec: Some("ass".into()),
        });
        let eng = |id, title, codec| {
            coded(
                titled(track(TrackKind::Subtitle, id, Some("eng")), title),
                codec,
            )
        };
        assert_rows(
            vec![
                (
                    "forced beats sdh",
                    vec![
                        eng(1, "Director", "ass"),
                        sdh_flag(forced_flag(eng(2, "x", "srt"))),
                    ],
                    Some(2),
                ),
                (
                    "sdh beats title",
                    vec![
                        sdh_flag(forced_flag(eng(1, "Director", "ass"))),
                        forced_flag(eng(2, "x", "srt")),
                    ],
                    Some(2),
                ),
                (
                    "title beats codec, trimmed and case-insensitive",
                    vec![
                        forced_flag(eng(1, "Other", "ass")),
                        forced_flag(eng(2, "  director ", "srt")),
                    ],
                    Some(2),
                ),
                (
                    "codec breaks a title tie, case-insensitive",
                    vec![
                        forced_flag(eng(1, "Director", "srt")),
                        forced_flag(eng(2, "Director", "ASS")),
                    ],
                    Some(2),
                ),
                (
                    "earliest wins a full tie",
                    vec![
                        forced_flag(eng(1, "Director", "ass")),
                        forced_flag(eng(2, "Director", "ass")),
                    ],
                    Some(1),
                ),
            ],
            |tracks| pick(tracks, &memory),
        );
    }

    #[test]
    fn a_remembered_identity_needs_a_same_language_track_of_the_same_kind() {
        let memory = remembered(&titled(
            track(TrackKind::Subtitle, 1, Some("eng")),
            "English",
        ));
        let spanish = [track(TrackKind::Subtitle, 10, Some("spa"))];
        assert_eq!(pick(&spanish, &memory), None);
        let audio_only = [track(TrackKind::Audio, 2, Some("eng"))];
        assert_eq!(pick(&audio_only, &memory), None);
        // Language compares through the normalized forms.
        let two_letter = [track(TrackKind::Subtitle, 11, Some("en"))];
        assert_eq!(pick(&two_letter, &memory), Some(11));
    }

    #[test]
    fn a_title_only_memory_matches_the_track_with_that_title_and_no_language() {
        let signs = titled(track(TrackKind::Subtitle, 3, None), "Signs");
        let memory = remembered(&signs);
        let tracks = [
            titled(track(TrackKind::Subtitle, 1, Some("eng")), "Other"),
            titled(track(TrackKind::Subtitle, 2, None), "Other"),
            signs,
        ];
        assert_eq!(pick(&tracks, &memory), Some(3));
    }

    /// A title-only memory is a track with no language: a same-titled track that carries one is a
    /// different track, and titles compare trimmed and case-insensitive like the ranking's.
    #[test]
    fn a_title_only_memory_ignores_a_same_titled_track_that_has_a_language() {
        let memory = remembered(&titled(track(TrackKind::Subtitle, 1, None), "Signs"));
        assert_rows(
            vec![
                (
                    "only a language-bearing namesake",
                    vec![titled(track(TrackKind::Subtitle, 2, Some("eng")), "Signs")],
                    None,
                ),
                (
                    "the language-less one wins, trimmed and case-insensitive",
                    vec![
                        titled(track(TrackKind::Subtitle, 2, Some("eng")), "Signs"),
                        titled(track(TrackKind::Subtitle, 3, None), "  signs "),
                    ],
                    Some(3),
                ),
            ],
            |tracks| pick(tracks, &memory),
        );
    }

    /// A remembered Forced is a filter: a forced-only viewer never gets full dialogue; plain and
    /// SDH memories only rank Forced.
    #[test]
    fn a_remembered_forced_track_is_a_filter_and_plain_or_sdh_memories_only_rank_it() {
        let eng = |id| titled(track(TrackKind::Subtitle, id, Some("eng")), "English");
        let forced_memory = remembered(&forced_flag(eng(1)));
        let plain_memory = remembered(&eng(1));
        let sdh_memory = remembered(&sdh_flag(eng(1)));
        assert_rows(
            vec![
                (
                    "forced memory, no forced track",
                    vec![eng(10), sdh_flag(eng(11))],
                    None,
                ),
                (
                    "forced memory takes the forced track",
                    vec![eng(10), forced_flag(eng(11))],
                    Some(11),
                ),
            ],
            |tracks| pick(tracks, &forced_memory),
        );
        assert_rows(
            vec![
                (
                    "plain memory, only forced offered",
                    vec![forced_flag(eng(10))],
                    Some(10),
                ),
                (
                    "plain memory prefers plain",
                    vec![forced_flag(eng(10)), eng(11)],
                    Some(11),
                ),
            ],
            |tracks| pick(tracks, &plain_memory),
        );
        assert_eq!(pick(&[forced_flag(eng(10))], &sdh_memory), Some(10));
        let pref = SeriesTrackPref {
            audio: TrackMemory::Unset,
            subtitle: forced_memory,
        };
        let tracks = [track(TrackKind::Audio, 1, Some("eng")), eng(10)];
        assert_eq!(
            resolve_track_selection(&tracks, Some(&pref), &no_global_prefs()).subtitle,
            SubtitleDecision::Leave
        );
    }

    /// Media3's mime forms and the server's names are one codec: the codec rank prefers the
    /// same-format track whichever spelling each side carries.
    #[test]
    fn remembered_audio_and_subtitle_codecs_match_across_media3_and_server_spellings() {
        let formats: [(&[&str], &str); 10] = [
            (&["application/x-subrip", "subrip", "srt", "SubRip"], "srt"),
            (&["ac-3", "audio/ac3", "ac3"], "ac3"),
            (&["ec-3", "audio/eac3", "audio/eac3-joc", "eac3"], "eac3"),
            (&["audio/true-hd", "truehd"], "truehd"),
            (
                &["audio/vnd.dts", "audio/vnd.dts.hd", "dtsc", "dtsh", "dts"],
                "dts",
            ),
            (&["mp4a.40.2", "mp4a.40.5", "audio/mp4a-latm", "aac"], "aac"),
            (&["text/x-ssa", "ssa", "ass"], "ass"),
            (&["text/vtt", "webvtt", "vtt"], "vtt"),
            (&["application/pgs", "hdmv_pgs_subtitle", "pgssub"], "pgs"),
            (&["application/vobsub", "dvd_subtitle", "dvdsub"], "vobsub"),
        ];
        let eng = |id, codec| coded(track(TrackKind::Subtitle, id, Some("eng")), codec);
        let mut misses = Vec::new();
        for (spellings, canonical) in formats {
            for remembered_as in spellings {
                for offered_as in spellings {
                    let memory = remembered(&eng(1, remembered_as));
                    // The decoy is listed first, so only the codec rank can pick the second.
                    let tracks = [eng(10, "other"), eng(11, offered_as)];
                    if pick(&tracks, &memory) != Some(11) {
                        misses.push(format!("{canonical}: {remembered_as} vs {offered_as}"));
                    }
                }
            }
        }
        // Different formats stay different.
        let memory = remembered(&eng(1, "application/x-subrip"));
        if pick(&[eng(10, "ass"), eng(11, "pgssub")], &memory) != Some(10) {
            misses.push("unrelated formats ranked apart".to_string());
        }
        assert!(misses.is_empty(), "{}", misses.join("\n"));
    }

    #[test]
    fn track_identity_normalizes_the_language_and_is_none_without_lang_or_title() {
        let mut t = coded(
            sdh_flag(forced_flag(titled(
                track(TrackKind::Subtitle, 1, Some("EN")),
                "English",
            ))),
            "subrip",
        );
        assert_eq!(
            track_identity(&t),
            Some(TrackIdentity {
                lang: Some("eng".into()),
                title: Some("English".into()),
                forced: true,
                sdh: true,
                codec: Some("subrip".into()),
            })
        );
        t.lang = None;
        t.title = None;
        assert_eq!(track_identity(&t), None);
    }

    /// A legacy bare-language key (saved before identity) repairs a series stuck on Forced: it
    /// prefers non-forced, then non-SDH, and the earliest track when nothing else separates them.
    #[test]
    fn a_legacy_language_key_prefers_the_plain_track_over_forced_and_sdh() {
        let key = TrackMemory::Key("eng".into());
        let eng = |id| track(TrackKind::Subtitle, id, Some("eng"));
        assert_rows(
            vec![
                ("forced first", vec![forced_flag(eng(1)), eng(2)], Some(2)),
                ("sdh first", vec![sdh_flag(eng(1)), eng(2)], Some(2)),
                (
                    "all forced -> earliest",
                    vec![forced_flag(eng(1)), forced_flag(eng(2))],
                    Some(1),
                ),
            ],
            |tracks| pick(tracks, &key),
        );
    }

    /// docs/12: global Always + language no longer lets a Forced or SDH track listed first win.
    #[test]
    fn always_with_a_language_prefers_the_plain_track_over_forced_and_sdh() {
        use SubtitleDecision::Track as Pick;
        let global = LanguagePrefs {
            subtitle: Some("eng".into()),
            subtitle_mode: SubtitleMode::Always,
            ..no_global_prefs()
        };
        let eng = |id| track(TrackKind::Subtitle, id, Some("eng"));
        assert_rows(
            vec![
                ("forced first", vec![forced_flag(eng(1)), eng(2)], Pick(2)),
                ("sdh before plain", vec![sdh_flag(eng(1)), eng(2)], Pick(2)),
                (
                    "forced, sdh, plain",
                    vec![forced_flag(eng(1)), sdh_flag(eng(2)), eng(3)],
                    Pick(3),
                ),
                (
                    "only forced and sdh: sdh beats forced",
                    vec![forced_flag(eng(1)), sdh_flag(eng(2))],
                    Pick(2),
                ),
            ],
            |tracks| resolve_track_selection(tracks, None, &global).subtitle,
        );
    }

    #[test]
    fn a_remembered_audio_identity_picks_the_matching_audio_track() {
        let main = titled(track(TrackKind::Audio, 3, Some("eng")), "Surround");
        let pref = SeriesTrackPref {
            audio: remembered(&main),
            subtitle: TrackMemory::Unset,
        };
        let tracks = vec![
            track(TrackKind::Audio, 1, Some("jpn")),
            titled(track(TrackKind::Audio, 2, Some("eng")), "Commentary"),
            main,
        ];
        let decision = resolve_track_selection(&tracks, Some(&pref), &no_global_prefs());
        assert_eq!(decision.audio, Some(3));
    }
}
