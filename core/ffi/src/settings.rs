//! On-disk settings persistence: `<data_dir>/settings.json`, holding the
//! whole [`Settings`] record (`docs/09-settings-plan.md`). Plain whole-file
//! `serde_json` read/write, tolerant load (missing/unparseable falls back to
//! [`Settings::default`]), `#[serde(default)]` per field. Unlike
//! `session.rs`'s `SessionFile`, `Settings` itself crosses the FFI boundary.

use std::path::{Path, PathBuf};

use crate::home::HomeLayout;

use playback_policy::prefs::{AutoplayPrefs, LanguagePrefs, SkipLengthPrefs, SubtitleMode};
use playback_policy::still_watching::{
    StillWatchingMode as PolicyStillWatchingMode, StillWatchingPrefs,
};

/// Mirrors `playback_policy::prefs::SubtitleMode` 1:1, re-declared as its
/// own `uniffi::Enum` + `serde` type since that crate has no
/// FFI/persistence concerns of its own.
#[derive(
    uniffi::Enum, Debug, Clone, Copy, Default, PartialEq, Eq, serde::Serialize, serde::Deserialize,
)]
pub enum SubtitleModeSetting {
    #[default]
    Default,
    Always,
    OnlyForced,
    None,
}

impl From<SubtitleModeSetting> for SubtitleMode {
    fn from(mode: SubtitleModeSetting) -> Self {
        match mode {
            SubtitleModeSetting::Default => SubtitleMode::Default,
            SubtitleModeSetting::Always => SubtitleMode::Always,
            SubtitleModeSetting::OnlyForced => SubtitleMode::OnlyForced,
            SubtitleModeSetting::None => SubtitleMode::None,
        }
    }
}

impl From<SubtitleMode> for SubtitleModeSetting {
    fn from(mode: SubtitleMode) -> Self {
        match mode {
            SubtitleMode::Default => SubtitleModeSetting::Default,
            SubtitleMode::Always => SubtitleModeSetting::Always,
            SubtitleMode::OnlyForced => SubtitleModeSetting::OnlyForced,
            SubtitleMode::None => SubtitleModeSetting::None,
        }
    }
}

/// Global preferred audio/subtitle language, applied when no per-series
/// memory exists. Mirrors `playback_policy::prefs::LanguagePrefs` 1:1, for
/// the same reason as [`SubtitleModeSetting`].
#[derive(
    uniffi::Record, Debug, Clone, Default, PartialEq, Eq, serde::Serialize, serde::Deserialize,
)]
pub struct LanguageSettings {
    /// `None` = "Any".
    #[serde(default)]
    pub audio: Option<String>,
    /// The language `SubtitleModeSetting::Always` prefers.
    #[serde(default)]
    pub subtitle: Option<String>,
    #[serde(default)]
    pub subtitle_mode: SubtitleModeSetting,
}

impl From<LanguageSettings> for LanguagePrefs {
    fn from(settings: LanguageSettings) -> Self {
        LanguagePrefs {
            audio: settings.audio,
            subtitle: settings.subtitle,
            subtitle_mode: settings.subtitle_mode.into(),
        }
    }
}

impl From<LanguagePrefs> for LanguageSettings {
    fn from(prefs: LanguagePrefs) -> Self {
        LanguageSettings {
            audio: prefs.audio,
            subtitle: prefs.subtitle,
            subtitle_mode: prefs.subtitle_mode.into(),
        }
    }
}

/// Vertical position presets for subtitles: Android's
/// `SubtitleView` has no `sub-pos` concept, so `PlaybackScreen.kt` maps
/// each variant onto its own bottom-padding fraction. `#[default]` on
/// `Default` matches [`Settings::subtitle_position`]'s real default.
#[derive(
    uniffi::Enum, Debug, Clone, Copy, Default, PartialEq, Eq, serde::Serialize, serde::Deserialize,
)]
pub enum SubtitlePositionPreset {
    #[default]
    Default,
    Raised,
    Higher,
    Highest,
}

/// Subtitle text colour presets: a short curated list rather than a free
/// picker (docs/09). `SubtitleStyle.kt` owns the RGB.
#[derive(
    uniffi::Enum, Debug, Clone, Copy, Default, PartialEq, Eq, serde::Serialize, serde::Deserialize,
)]
pub enum SubtitleColorPreset {
    #[default]
    White,
    /// Dimmed white, so HDR/OLED panels don't render text at peak brightness.
    SoftWhite,
    Yellow,
    LightGreen,
}

/// [`Settings::subtitle_scale`]'s real default -- `1.0`, not `f32`'s zero
/// value, so an explicit `#[serde(default = "...")]` is required.
fn default_subtitle_scale() -> f32 {
    1.0
}

fn default_home_shelf_size() -> u32 {
    20
}

const fn default_home_show_favorites() -> bool {
    true
}

fn default_skip_back_secs() -> u32 {
    SkipLengthPrefs::default().back_secs
}

fn default_skip_forward_secs() -> u32 {
    SkipLengthPrefs::default().forward_secs
}

fn default_autoplay_enabled() -> bool {
    AutoplayPrefs::default().enabled
}

/// docs/26 §5: update checks are on unless the viewer turned them off.
fn default_automatic_update_checks() -> bool {
    true
}

fn default_autoplay_delay_secs() -> u32 {
    AutoplayPrefs::default().delay_secs
}

/// What the OSD does when
/// the playhead enters a `MediaSegmentKind` -- **Ask** shows the skip pill
/// (today's behavior); **AutoSkip** seeks past it with an Undo toast;
/// **Off** ignores it entirely.
#[derive(
    uniffi::Enum, Debug, Clone, Copy, Default, PartialEq, Eq, serde::Serialize, serde::Deserialize,
)]
pub enum SegmentAction {
    #[default]
    Ask,
    AutoSkip,
    Off,
}

/// [`Settings::skip_commercial`]'s real default -- `AutoSkip`, not
/// `SegmentAction`'s own `#[default]` `Ask` -- commercial breaks are the
/// one segment type nobody wants to be asked about every time. Every other
/// segment type keeps `Ask` via a bare `#[serde(default)]`.
fn default_skip_commercial() -> SegmentAction {
    SegmentAction::AutoSkip
}

/// [`Settings::show_clock`]'s real default -- `true`, not `bool`'s zero
/// value, so an explicit `#[serde(default = "...")]` is required.
fn default_show_clock() -> bool {
    true
}

/// [`Settings::crash_reports_enabled`]'s real default -- `true`, not
/// `bool`'s zero value: docs/21 §1.3/§6 "Crash reports" is opt-out, not
/// opt-in.
fn default_crash_reports_enabled() -> bool {
    true
}

/// [`Settings::preload_on_focus`]'s real default -- `true`, not `bool`'s
/// zero value: focus-dwell preload is a strict UX improvement with no
/// user-visible downside, so an old settings.json must load with that
/// default rather than `bool`'s zeroed "off".
fn default_preload_on_focus() -> bool {
    true
}

/// [`Settings::tolerate_mislabeled_levels`]'s real default -- `true`, not
/// `bool`'s zero value: `android_tv_profile` never emitted a `VideoLevel`
/// condition and `DecoderSelection.kt`'s retry always ran, so an existing
/// install must keep that behavior on first load.
fn default_tolerate_mislabeled_levels() -> bool {
    true
}

/// OSD density preference (JELLYBEAM-TV-OSD-SPEC.md §7/§11) -- named
/// `Minimal`/`Full` here rather than the spec's `Clean`/`Nerdy`. `#[default]`
/// on `Full` is a deliberate override of the spec's stated `Clean` default.
#[derive(
    uniffi::Enum, Debug, Clone, Copy, Default, PartialEq, Eq, serde::Serialize, serde::Deserialize,
)]
pub enum OsdDetailSetting {
    Minimal,
    #[default]
    Full,
}

/// Which art Continue Watching and Next Up draw (docs/07 §1). `Episode` is the episode's own
/// 16:9 still, `SeriesThumb` the series' 16:9 Thumb (spoiler-free), `Poster` the 2:3 poster
/// cell. `#[default]` on `Episode` keeps today's thumbnail rows.
#[derive(
    uniffi::Enum, Debug, Clone, Copy, Default, PartialEq, Eq, serde::Serialize, serde::Deserialize,
)]
pub enum ResumeArt {
    #[default]
    Episode,
    SeriesThumb,
    Poster,
}

/// Seek-preview (trickplay) panel size, docs/12 §11: Small is the server thumbnail
/// pixel-for-pixel on a 1080p panel, Medium is Netflix-like, Large is the old
/// native-dp size. Kotlin maps each to a dp width; `#[default]` on `Medium`.
#[derive(
    uniffi::Enum, Debug, Clone, Copy, Default, PartialEq, Eq, serde::Serialize, serde::Deserialize,
)]
pub enum SeekPreviewSize {
    Small,
    #[default]
    Medium,
    Large,
}

/// "Still watching?" inactivity-guard mode -- mirrors
/// `playback_policy::still_watching::StillWatchingMode` 1:1, re-declared
/// for the same reason as [`SubtitleModeSetting`]. Named identically to
/// the policy enum (disambiguated via the `PolicyStillWatchingMode` import
/// alias). `#[default]` on `AfterEpisodes` (not `Off`) matches
/// [`StillWatchingSettings::mode`]'s real product default.
#[derive(
    uniffi::Enum, Debug, Clone, Copy, Default, PartialEq, Eq, serde::Serialize, serde::Deserialize,
)]
pub enum StillWatchingMode {
    Off,
    #[default]
    AfterEpisodes,
    AfterHours,
}

impl From<StillWatchingMode> for PolicyStillWatchingMode {
    fn from(mode: StillWatchingMode) -> Self {
        match mode {
            StillWatchingMode::Off => PolicyStillWatchingMode::Off,
            StillWatchingMode::AfterEpisodes => PolicyStillWatchingMode::AfterEpisodes,
            StillWatchingMode::AfterHours => PolicyStillWatchingMode::AfterHours,
        }
    }
}

impl From<PolicyStillWatchingMode> for StillWatchingMode {
    fn from(mode: PolicyStillWatchingMode) -> Self {
        match mode {
            PolicyStillWatchingMode::Off => StillWatchingMode::Off,
            PolicyStillWatchingMode::AfterEpisodes => StillWatchingMode::AfterEpisodes,
            PolicyStillWatchingMode::AfterHours => StillWatchingMode::AfterHours,
        }
    }
}

/// "Still watching?" inactivity-guard settings -- mirrors
/// `playback_policy::still_watching::StillWatchingPrefs` 1:1.
/// `#[serde(default)]` on the struct matches that type's own
/// container-level default.
#[derive(uniffi::Record, Debug, Clone, Copy, PartialEq, serde::Serialize, serde::Deserialize)]
#[serde(default)]
pub struct StillWatchingSettings {
    pub mode: StillWatchingMode,
    pub episodes: u32,
    pub hours: f32,
    pub timeout_secs: u32,
    /// Mirrors [`StillWatchingPrefs::reset_on_input`] 1:1. Defaults to
    /// `true` (today's original behavior).
    pub reset_on_input: bool,
}

impl Default for StillWatchingSettings {
    fn default() -> Self {
        StillWatchingPrefs::default().into()
    }
}

impl From<StillWatchingSettings> for StillWatchingPrefs {
    fn from(settings: StillWatchingSettings) -> Self {
        StillWatchingPrefs {
            mode: settings.mode.into(),
            episodes: settings.episodes,
            hours: settings.hours,
            timeout_secs: settings.timeout_secs,
            reset_on_input: settings.reset_on_input,
        }
    }
}

impl From<StillWatchingPrefs> for StillWatchingSettings {
    fn from(prefs: StillWatchingPrefs) -> Self {
        StillWatchingSettings {
            mode: prefs.mode.into(),
            episodes: prefs.episodes,
            hours: prefs.hours,
            timeout_secs: prefs.timeout_secs,
            reset_on_input: prefs.reset_on_input,
        }
    }
}

/// The whole settings record, per `docs/09-settings-plan.md`: one
/// coarse-grained value traded whole across the FFI boundary, never a
/// per-field setter. `#[serde(default = "...")]` on every field whose zero
/// value isn't its real default keeps an old settings.json loading with the
/// *feature's* default, not a zeroed one.
#[derive(uniffi::Record, Debug, Clone, PartialEq, serde::Serialize, serde::Deserialize)]
pub struct Settings {
    #[serde(default = "default_automatic_update_checks")]
    #[uniffi(default = true)]
    pub automatic_update_checks: bool,
    /// `Some(days)` -- see `NextUpOptions::cutoff_days`; `None` ("Off") is
    /// the default, today's unfiltered behavior.
    #[serde(default)]
    pub next_up_cutoff_days: Option<u32>,
    /// See `media_cache::NextUpOptions::rewatching`.
    #[serde(default)]
    pub next_up_rewatching: bool,
    /// View ids [`crate::JellybeamCore::home_snapshot`] skips for "Latest"
    /// shelves. [`crate::JellybeamCore::views`] stays unfiltered.
    #[serde(default)]
    pub hidden_library_ids: Vec<String>,
    /// Passed straight through to `Mirror::latest`'s `hide_watched` parameter.
    #[serde(default)]
    pub hide_watched_in_latest: bool,
    /// View id to land on at launch instead of Home; `None` = Home. Kotlin
    /// falls back to Home itself if the id no longer names a real view
    /// (docs/07 §5); nothing here validates it.
    #[serde(default)]
    pub startup_screen_view_id: Option<String>,
    /// Cards per Home shelf (Continue Watching, Next Up, each Latest). One of
    /// Kotlin's shelf-size presets by convention, not validated here.
    #[serde(default = "default_home_shelf_size")]
    pub home_shelf_size: u32,
    /// docs/07 §1: whether Home shows the Favorites shelf. The drawer's Favorites entry
    /// ignores this; it shows whenever favorites exist.
    #[serde(default = "default_home_show_favorites")]
    #[uniffi(default = true)]
    pub home_show_favorites: bool,
    /// docs/07 §1: the art Continue Watching and Next Up draw. Defaults to the episode still;
    /// a pre-enum `home_resume_posters: true` loads as `Poster` (see [`load`]).
    #[serde(default)]
    pub home_resume_art: ResumeArt,
    /// Seek-back magnitude, in seconds. One of Kotlin's skip-length presets
    /// by convention, not validated as such here.
    #[serde(default = "default_skip_back_secs")]
    pub skip_back_secs: u32,
    /// Seek-forward magnitude, in seconds -- see `skip_back_secs`.
    #[serde(default = "default_skip_forward_secs")]
    pub skip_forward_secs: u32,
    #[serde(default)]
    pub language: LanguageSettings,
    /// See `playback_policy::prefs::AutoplayPrefs::enabled`.
    #[serde(default = "default_autoplay_enabled")]
    pub autoplay_enabled: bool,
    /// See `playback_policy::prefs::AutoplayPrefs::delay_secs`.
    #[serde(default = "default_autoplay_delay_secs")]
    pub autoplay_delay_secs: u32,
    /// Subtitle rendering scale, applied by `PlaybackScreen.kt` as a
    /// multiplier on Media3 `SubtitleView`'s `DEFAULT_TEXT_SIZE_FRACTION`.
    /// One of `[0.75, 1.0, 1.25, 1.5]` by convention, not validated here.
    #[serde(default = "default_subtitle_scale")]
    pub subtitle_scale: f32,
    /// Subtitle vertical-position preset -- see [`SubtitlePositionPreset`].
    #[serde(default)]
    pub subtitle_position: SubtitlePositionPreset,
    /// Bold subtitle text.
    #[serde(default)]
    pub subtitle_bold: bool,
    /// Subtitle background opacity, `0.0..=1.0`. One of `[0.0, 0.25, 0.5,
    /// 0.75]` by convention, not validated here. `0.0`, `f32`'s natural
    /// zero value, is already the intended default.
    #[serde(default)]
    pub subtitle_background_opacity: f32,
    /// Subtitle text colour -- see [`SubtitleColorPreset`].
    #[serde(default)]
    pub subtitle_color: SubtitleColorPreset,
    /// Take colour, edge, background and typeface from Android's system
    /// caption settings instead of the presets; size and position still apply.
    #[serde(default)]
    pub subtitle_use_system_style: bool,
    /// Render ASS/SSA with full typesetting (fonts, signs, karaoke) through the substation
    /// overlay; off (the default while it is new) keeps Media3's plain-text rendering.
    #[serde(default)]
    pub subtitle_full_ass_styling: bool,
    /// Per-`MediaSegmentKind` skip behavior -- see [`SegmentAction`]. Bare
    /// `#[serde(default)]` is correct: `SegmentAction::default()` (`Ask`)
    /// already matches this field's intended fallback.
    #[serde(default)]
    pub skip_intro: SegmentAction,
    /// See [`Self::skip_intro`].
    #[serde(default)]
    pub skip_outro: SegmentAction,
    /// See [`Self::skip_intro`].
    #[serde(default)]
    pub skip_recap: SegmentAction,
    /// See [`Self::skip_intro`].
    #[serde(default)]
    pub skip_preview: SegmentAction,
    /// Defaults to `AutoSkip`, not `Ask` -- see [`default_skip_commercial`].
    #[serde(default = "default_skip_commercial")]
    pub skip_commercial: SegmentAction,
    /// Show a wall-clock readout in the OSD/UI chrome. Defaults to `true`.
    #[serde(default = "default_show_clock")]
    pub show_clock: bool,
    /// Advanced-settings toggle (`settings_tolerate_levels`): whether to
    /// tolerate a source's mislabeled/inflated codec level rather than
    /// trusting it at face value. `true` (default) is today's existing
    /// behavior; `false` restores a per-profile `VideoLevel <=` ceiling and
    /// no level-stripped decoder retry, for hardware that genuinely can't
    /// handle a level it accepts once the check is bypassed.
    #[serde(default = "default_tolerate_mislabeled_levels")]
    pub tolerate_mislabeled_levels: bool,
    /// Prefer Jellybeam's bundled FFmpeg audio renderer over Android's
    /// platform decoder for TrueHD -- a local decoder choice only, never
    /// changing the Jellyfin device profile or Direct Play negotiation.
    #[serde(default)]
    pub prefer_ffmpeg_true_hd: bool,
    /// Same local-only override for DTS and DTS Express.
    #[serde(default)]
    pub prefer_ffmpeg_dts: bool,
    /// Same local-only override for DTS-HD and DTS:X. Some containers
    /// expose DTS-HD to Media3 as generic DTS; this flag also matches that
    /// ambiguous MIME so the switch works.
    #[serde(default)]
    pub prefer_ffmpeg_dts_hd: bool,
    /// Include virtual (missing/unaired) placeholder rows in
    /// series/season episode listings. `false` (`bool`'s zero value)
    /// hides them -- see [`crate::JellybeamCore::children`]. `true` restores
    /// pre-toggle behavior.
    #[serde(default)]
    pub show_virtual_episodes: bool,
    /// Advanced-settings toggle (`settings_preload_on_focus`): whether
    /// dwelling focus on a playable card fires a background
    /// playback-handshake preload -- see
    /// [`crate::JellybeamCore::preload_playback`]. Defaults to `true`.
    #[serde(default = "default_preload_on_focus")]
    pub preload_on_focus: bool,
    /// Playback toggle "Mini player" (docs/17-mini-player.md): gates
    /// `PlaybackActivity`'s picture-in-picture entry on Back/Home. Defaults
    /// off, `bool`'s own zero value.
    #[serde(default)]
    pub mini_player_enabled: bool,
    /// OSD density -- see [`OsdDetailSetting`] for the `Minimal`/`Full`
    /// naming and why `Full` (not the spec's `Clean`) is the real default.
    #[serde(default)]
    pub osd_detail: OsdDetailSetting,
    /// Seek-preview panel size -- see [`SeekPreviewSize`].
    #[serde(default)]
    pub seek_preview_size: SeekPreviewSize,
    /// "Still watching?" inactivity guard -- see
    /// [`StillWatchingSettings`]. Only meaningful when
    /// [`Self::autoplay_enabled`] is true (not gated on the Rust side).
    #[serde(default)]
    pub still_watching: StillWatchingSettings,
    /// Playback › "Quality" setting (docs/18-playback-quality.md §1-3) --
    /// see [`PlaybackQuality`] for the three variants.
    /// `prepare_playback`/`prepare_transcode_fallback` are the only
    /// readers.
    #[serde(default)]
    pub playback_quality: PlaybackQuality,
    /// Troubleshooting › "Diagnostic logging" (docs/21 §1.1/§6): gates the
    /// Kotlin event-tier recorder. Off by default -- `bool`'s zero value.
    #[serde(default)]
    pub diagnostic_logging_enabled: bool,
    /// Troubleshooting › "Crash reports" (docs/21 §1.3/§6): gates the
    /// Kotlin crash-capture handler. Defaults to `true` -- see
    /// [`default_crash_reports_enabled`].
    #[serde(default = "default_crash_reports_enabled")]
    pub crash_reports_enabled: bool,
    /// docs/25 §6.1: the persisted Home layout, read once per session to seed the committed
    /// choice; an unknown stored value loads as the default.
    #[serde(default, deserialize_with = "crate::home::deserialize_layout")]
    pub home_layout: HomeLayout,
}

impl Default for Settings {
    /// Matches `playback-policy`'s own defaults exactly, plus
    /// empty/false/`None` for every field with no counterpart -- so a
    /// fresh install behaves identically to today's hardcoded pre-settings
    /// behavior.
    fn default() -> Self {
        Settings {
            automatic_update_checks: true,
            next_up_cutoff_days: None,
            next_up_rewatching: false,
            hidden_library_ids: Vec::new(),
            hide_watched_in_latest: false,
            startup_screen_view_id: None,
            home_shelf_size: default_home_shelf_size(),
            home_show_favorites: default_home_show_favorites(),
            home_resume_art: ResumeArt::default(),
            skip_back_secs: default_skip_back_secs(),
            skip_forward_secs: default_skip_forward_secs(),
            language: LanguageSettings::default(),
            autoplay_enabled: default_autoplay_enabled(),
            autoplay_delay_secs: default_autoplay_delay_secs(),
            subtitle_scale: default_subtitle_scale(),
            subtitle_position: SubtitlePositionPreset::default(),
            subtitle_bold: false,
            subtitle_background_opacity: 0.0,
            subtitle_color: SubtitleColorPreset::default(),
            subtitle_use_system_style: false,
            subtitle_full_ass_styling: false,
            skip_intro: SegmentAction::Ask,
            skip_outro: SegmentAction::Ask,
            skip_recap: SegmentAction::Ask,
            skip_preview: SegmentAction::Ask,
            skip_commercial: default_skip_commercial(),
            show_clock: default_show_clock(),
            tolerate_mislabeled_levels: default_tolerate_mislabeled_levels(),
            prefer_ffmpeg_true_hd: false,
            prefer_ffmpeg_dts: false,
            prefer_ffmpeg_dts_hd: false,
            show_virtual_episodes: false,
            preload_on_focus: default_preload_on_focus(),
            mini_player_enabled: false,
            osd_detail: OsdDetailSetting::default(),
            seek_preview_size: SeekPreviewSize::default(),
            still_watching: StillWatchingSettings::default(),
            playback_quality: PlaybackQuality::default(),
            diagnostic_logging_enabled: false,
            crash_reports_enabled: default_crash_reports_enabled(),
            home_layout: HomeLayout::default(),
        }
    }
}

/// Playback › "Quality" setting (docs/18-playback-quality.md §1-2):
/// governs whether/when a source is routed through the server's
/// transcoder. **Auto** only transcodes on evidence this TV's own device profile can
/// judge, never on the server's link-measurement prediction.
///
/// - `DirectPlay` (`#[default]`): never transcodes -- a server `Transcode`
///   decision is refused outright with `CoreError::WouldTranscode`.
/// - `Auto`: a server `DirectPlay` decision plays directly; a `Transcode`
///   decision is checked against this TV's own probed decoder list -- an
///   unsupported codec corroborates and transcodes up front, else it
///   attempts Direct Play anyway, keeping the server's reasons as
///   `PlaybackPlan::server_verdict` for OSD text only.
/// - `Cap { max_bps }`: always transcodes, every file, at that ceiling.
///   Presets (20/8/3 Mbps) are a Kotlin concern; this type accepts any
///   `max_bps`.
///
/// Internally tagged (`mode` field) rather than externally tagged --
/// simpler for docs/18 §3's Kotlin chip row to pattern-match.
#[derive(
    uniffi::Enum, Debug, Clone, Copy, Default, PartialEq, Eq, serde::Serialize, serde::Deserialize,
)]
#[serde(tag = "mode", rename_all = "snake_case")]
pub enum PlaybackQuality {
    #[default]
    DirectPlay,
    Auto,
    Cap {
        max_bps: u32,
    },
}

fn path(data_dir: &Path) -> PathBuf {
    data_dir.join("settings.json")
}

/// Atomically replace the saved settings; callers own update ordering.
pub(crate) fn save(data_dir: &Path, settings: &Settings) -> std::io::Result<()> {
    crate::persistence::save_json(&path(data_dir), settings)
}

/// Load previously saved settings, or [`Settings::default`] if there isn't
/// one -- missing/unreadable/malformed all treated the same as "nothing
/// saved yet", same offline-first shape as `session::load`. A file
/// missing some fields or carrying unrecognized ones still loads.
pub(crate) fn load(data_dir: &Path) -> Settings {
    std::fs::read(path(data_dir))
        .ok()
        .and_then(|bytes| serde_json::from_slice::<serde_json::Value>(&bytes).ok())
        .map(migrate_legacy_keys)
        .and_then(|raw| serde_json::from_value(raw).ok())
        .unwrap_or_default()
}

/// docs/09: `home_resume_posters: true` (the pre-`home_resume_art` bool) becomes
/// `home_resume_art: "Poster"`, so a saved choice survives; a present `home_resume_art` wins.
fn migrate_legacy_keys(mut raw: serde_json::Value) -> serde_json::Value {
    if let Some(map) = raw.as_object_mut() {
        let legacy = map.remove("home_resume_posters");
        if !map.contains_key("home_resume_art") && legacy == Some(serde_json::Value::Bool(true)) {
            map.insert("home_resume_art".to_string(), "Poster".into());
        }
    }
    raw
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    /// Earliest legacy settings.json shape -- the ten fields that existed
    /// before `show_clock`. Each migration test below extends this with
    /// whichever fields were added since, so the historical shape stays
    /// visible at each test's call site.
    fn legacy_base() -> serde_json::Value {
        json!({
            "next_up_cutoff_days": null,
            "next_up_rewatching": false,
            "hidden_library_ids": [],
            "hide_watched_in_latest": false,
            "startup_screen_view_id": null,
            "skip_back_secs": 10,
            "skip_forward_secs": 10,
            "language": {"audio": null, "subtitle": null, "subtitle_mode": "Default"},
            "autoplay_enabled": true,
            "autoplay_delay_secs": 10,
        })
    }

    /// Writes `raw` to a fresh tempdir's settings.json and loads it back
    /// -- the on-disk boilerplate shared by every migration test.
    fn load_from_json(raw: serde_json::Value) -> Settings {
        let dir = tempfile::tempdir().expect("tempdir");
        std::fs::write(
            path(dir.path()),
            serde_json::to_vec(&raw).expect("serialize raw json"),
        )
        .expect("write settings.json");
        load(dir.path())
    }

    fn sample() -> Settings {
        Settings {
            automatic_update_checks: true,
            next_up_cutoff_days: Some(30),
            next_up_rewatching: true,
            hidden_library_ids: vec!["lib-1".to_string()],
            hide_watched_in_latest: true,
            startup_screen_view_id: Some("view-1".to_string()),
            home_shelf_size: 30,
            home_show_favorites: false,
            home_resume_art: ResumeArt::SeriesThumb,
            skip_back_secs: 15,
            skip_forward_secs: 30,
            language: LanguageSettings {
                audio: Some("eng".to_string()),
                subtitle: Some("spa".to_string()),
                subtitle_mode: SubtitleModeSetting::Always,
            },
            autoplay_enabled: false,
            autoplay_delay_secs: 5,
            subtitle_scale: 1.25,
            subtitle_position: SubtitlePositionPreset::Higher,
            subtitle_bold: true,
            subtitle_background_opacity: 0.5,
            subtitle_color: SubtitleColorPreset::Yellow,
            subtitle_use_system_style: true,
            subtitle_full_ass_styling: false,
            skip_intro: SegmentAction::Off,
            skip_outro: SegmentAction::AutoSkip,
            skip_recap: SegmentAction::Off,
            skip_preview: SegmentAction::AutoSkip,
            skip_commercial: SegmentAction::Ask,
            show_clock: false,
            tolerate_mislabeled_levels: false,
            prefer_ffmpeg_true_hd: true,
            prefer_ffmpeg_dts: true,
            prefer_ffmpeg_dts_hd: true,
            show_virtual_episodes: true,
            preload_on_focus: false,
            mini_player_enabled: true,
            osd_detail: OsdDetailSetting::Minimal,
            seek_preview_size: SeekPreviewSize::Small,
            still_watching: StillWatchingSettings {
                mode: StillWatchingMode::AfterHours,
                episodes: 5,
                hours: 2.0,
                timeout_secs: 60,
                reset_on_input: false,
            },
            playback_quality: PlaybackQuality::Cap { max_bps: 8_000_000 },
            diagnostic_logging_enabled: true,
            crash_reports_enabled: false,
            home_layout: HomeLayout::Classic,
        }
    }

    /// docs/25 §4.2: a stored layout this build doesn't know loads as the default, and the
    /// rest of the file still loads.
    #[test]
    fn an_unknown_home_layout_falls_back_without_losing_other_settings() {
        let mut raw = serde_json::to_value(sample()).expect("to value");
        raw["home_layout"] = serde_json::json!("layout-from-a-newer-build");
        let dir = tempfile::tempdir().expect("tempdir");
        std::fs::write(path(dir.path()), serde_json::to_vec(&raw).expect("json")).expect("write");
        let loaded = load(dir.path());
        assert_eq!(loaded.home_layout, HomeLayout::Classic);
        assert_eq!(loaded.home_shelf_size, 30);
        assert_eq!(loaded.crash_reports_enabled, sample().crash_reports_enabled);
    }

    #[test]
    fn default_matches_playback_policy_defaults() {
        let settings = Settings::default();
        assert_eq!(
            settings.skip_back_secs,
            SkipLengthPrefs::default().back_secs
        );
        assert_eq!(
            settings.skip_forward_secs,
            SkipLengthPrefs::default().forward_secs
        );
        assert_eq!(settings.autoplay_enabled, AutoplayPrefs::default().enabled);
        assert_eq!(
            settings.autoplay_delay_secs,
            AutoplayPrefs::default().delay_secs
        );
        assert_eq!(
            settings.language.subtitle_mode,
            SubtitleModeSetting::Default
        );
        assert!(settings.next_up_cutoff_days.is_none());
        assert!(!settings.next_up_rewatching);
        assert!(settings.hidden_library_ids.is_empty());
        assert!(!settings.hide_watched_in_latest);
        assert!(settings.startup_screen_view_id.is_none());
        assert_eq!(settings.home_shelf_size, 20);
        assert!(settings.home_show_favorites);
        assert_eq!(settings.home_resume_art, ResumeArt::Episode);
        assert!(settings.language.audio.is_none());
        assert!(settings.language.subtitle.is_none());
        assert_eq!(settings.subtitle_scale, 1.0);
        assert_eq!(settings.subtitle_position, SubtitlePositionPreset::Default);
        assert!(!settings.subtitle_bold);
        assert_eq!(settings.subtitle_background_opacity, 0.0);
        assert_eq!(settings.subtitle_color, SubtitleColorPreset::White);
        assert!(!settings.subtitle_use_system_style);
        assert!(!settings.subtitle_full_ass_styling);
        assert_eq!(settings.skip_intro, SegmentAction::Ask);
        assert_eq!(settings.skip_outro, SegmentAction::Ask);
        assert_eq!(settings.skip_recap, SegmentAction::Ask);
        assert_eq!(settings.skip_preview, SegmentAction::Ask);
        assert_eq!(settings.skip_commercial, SegmentAction::AutoSkip);
        assert!(settings.show_clock);
        assert!(settings.tolerate_mislabeled_levels);
        assert!(!settings.prefer_ffmpeg_true_hd);
        assert!(!settings.prefer_ffmpeg_dts);
        assert!(!settings.prefer_ffmpeg_dts_hd);
        assert!(!settings.show_virtual_episodes);
        assert!(settings.preload_on_focus);
        assert!(!settings.mini_player_enabled);
        assert_eq!(settings.osd_detail, OsdDetailSetting::Full);
        assert_eq!(settings.seek_preview_size, SeekPreviewSize::Medium);
        assert_eq!(
            settings.still_watching.mode,
            StillWatchingMode::from(StillWatchingPrefs::default().mode)
        );
        assert_eq!(
            settings.still_watching.episodes,
            StillWatchingPrefs::default().episodes
        );
        assert_eq!(
            settings.still_watching.hours,
            StillWatchingPrefs::default().hours
        );
        assert_eq!(
            settings.still_watching.timeout_secs,
            StillWatchingPrefs::default().timeout_secs
        );
        assert_eq!(
            settings.still_watching.reset_on_input,
            StillWatchingPrefs::default().reset_on_input
        );
        assert!(!settings.diagnostic_logging_enabled);
        assert!(settings.crash_reports_enabled);
    }

    #[test]
    fn defaults_round_trip_through_json() {
        let original = Settings::default();
        let json = serde_json::to_vec(&original).expect("serialize defaults");
        let loaded: Settings = serde_json::from_slice(&json).expect("deserialize defaults");
        assert_eq!(loaded, original);
    }

    #[test]
    fn non_default_settings_round_trip_through_json() {
        let original = sample();
        let json = serde_json::to_vec(&original).expect("serialize");
        let loaded: Settings = serde_json::from_slice(&json).expect("deserialize");
        assert_eq!(loaded, original);
    }

    #[test]
    fn round_trips_through_disk() {
        let dir = tempfile::tempdir().expect("tempdir");
        let original = sample();
        save(dir.path(), &original).expect("save settings");
        let loaded = load(dir.path());
        assert_eq!(loaded, original);
    }

    /// docs/09: the legacy bool maps onto the enum; an explicit new key beats it.
    #[test]
    fn load_maps_legacy_home_resume_posters_onto_home_resume_art() {
        let mut raw = legacy_base();
        raw["home_resume_posters"] = json!(true);
        assert_eq!(load_from_json(raw).home_resume_art, ResumeArt::Poster);

        let mut raw = legacy_base();
        raw["home_resume_posters"] = json!(false);
        assert_eq!(load_from_json(raw).home_resume_art, ResumeArt::Episode);

        assert_eq!(
            load_from_json(legacy_base()).home_resume_art,
            ResumeArt::Episode
        );

        let mut raw = legacy_base();
        raw["home_resume_posters"] = json!(true);
        raw["home_resume_art"] = json!("SeriesThumb");
        assert_eq!(load_from_json(raw).home_resume_art, ResumeArt::SeriesThumb);
    }

    #[test]
    fn home_resume_art_round_trips_and_drops_the_legacy_key() {
        let dir = tempfile::tempdir().expect("tempdir");
        let settings = Settings {
            home_resume_art: ResumeArt::SeriesThumb,
            ..Settings::default()
        };
        save(dir.path(), &settings).expect("save");
        let on_disk: serde_json::Value =
            serde_json::from_slice(&std::fs::read(path(dir.path())).expect("read")).expect("json");
        assert_eq!(on_disk["home_resume_art"], json!("SeriesThumb"));
        assert!(on_disk.get("home_resume_posters").is_none());
        assert_eq!(load(dir.path()).home_resume_art, ResumeArt::SeriesThumb);
    }

    #[test]
    fn load_returns_defaults_when_no_settings_file_exists() {
        let dir = tempfile::tempdir().expect("tempdir");
        assert_eq!(load(dir.path()), Settings::default());
    }

    #[test]
    fn load_returns_defaults_for_malformed_json() {
        let dir = tempfile::tempdir().expect("tempdir");
        std::fs::write(path(dir.path()), b"not json").expect("write garbage");
        assert_eq!(load(dir.path()), Settings::default());
    }

    /// Simulates both migration directions at once: `bogus_future_field`
    /// is unknown to this build, and every field added since
    /// `autoplay_delay_secs` is omitted entirely. Loading must succeed
    /// either way -- old fields fall back to their real defaults, new
    /// unknown fields are silently ignored.
    #[test]
    fn load_tolerates_unknown_fields_and_fills_in_missing_ones_with_defaults() {
        let raw = json!({
            "next_up_cutoff_days": 14,
            "next_up_rewatching": true,
            "hidden_library_ids": ["lib-xyz"],
            "hide_watched_in_latest": true,
            "startup_screen_view_id": null,
            "skip_back_secs": 20,
            "skip_forward_secs": 20,
            "language": {"audio": null, "subtitle": null, "subtitle_mode": "Default"},
            "autoplay_enabled": true,
            "bogus_future_field": {"anything": "goes here"},
        });
        let loaded = load_from_json(raw);
        assert_eq!(loaded.next_up_cutoff_days, Some(14));
        assert!(loaded.next_up_rewatching);
        assert_eq!(loaded.hidden_library_ids, vec!["lib-xyz".to_string()]);
        assert!(loaded.hide_watched_in_latest);
        assert!(loaded.startup_screen_view_id.is_none());
        assert_eq!(loaded.skip_back_secs, 20);
        assert_eq!(loaded.skip_forward_secs, 20);
        // Absent from the old file: falls back to 20, not 0.
        assert_eq!(loaded.home_shelf_size, 20);
        // Absent from the old file: the shelf shows, not hides.
        assert!(loaded.home_show_favorites);
        // Absent from the old file: resume shelves keep thumbnails.
        assert_eq!(loaded.home_resume_art, ResumeArt::Episode);
        assert!(loaded.autoplay_enabled);
        // Missing field falls back to the playback-policy default, not 0.
        assert_eq!(
            loaded.autoplay_delay_secs,
            AutoplayPrefs::default().delay_secs
        );
        // Missing subtitle_* fields fall back to their own real defaults.
        assert_eq!(loaded.subtitle_scale, 1.0);
        assert_eq!(loaded.subtitle_position, SubtitlePositionPreset::Default);
        assert!(!loaded.subtitle_bold);
        assert_eq!(loaded.subtitle_background_opacity, 0.0);
        assert_eq!(loaded.subtitle_color, SubtitleColorPreset::White);
        assert!(!loaded.subtitle_use_system_style);
        // Missing subtitle_full_ass_styling falls back to off.
        assert!(!loaded.subtitle_full_ass_styling);
        // Missing skip_* fields fall back to their own real defaults --
        // Ask for everything except commercial.
        assert_eq!(loaded.skip_intro, SegmentAction::Ask);
        assert_eq!(loaded.skip_outro, SegmentAction::Ask);
        assert_eq!(loaded.skip_recap, SegmentAction::Ask);
        assert_eq!(loaded.skip_preview, SegmentAction::Ask);
        assert_eq!(loaded.skip_commercial, SegmentAction::AutoSkip);
        // Missing show_clock falls back to its own real default (true).
        assert!(loaded.show_clock);
        // Missing tolerate_mislabeled_levels falls back to true.
        assert!(loaded.tolerate_mislabeled_levels);
        assert!(!loaded.prefer_ffmpeg_true_hd);
        assert!(!loaded.prefer_ffmpeg_dts);
        assert!(!loaded.prefer_ffmpeg_dts_hd);
        // Missing show_virtual_episodes falls back to false (also bool's zero).
        assert!(!loaded.show_virtual_episodes);
    }

    #[test]
    fn ffmpeg_audio_preferences_round_trip_and_old_files_default_off() {
        let original = Settings {
            prefer_ffmpeg_true_hd: true,
            prefer_ffmpeg_dts: true,
            prefer_ffmpeg_dts_hd: true,
            ..Settings::default()
        };
        let json = serde_json::to_value(&original).expect("serialize");
        assert_eq!(json["prefer_ffmpeg_true_hd"], true);
        assert_eq!(json["prefer_ffmpeg_dts"], true);
        assert_eq!(json["prefer_ffmpeg_dts_hd"], true);

        let loaded: Settings = serde_json::from_value(json).expect("deserialize");
        assert_eq!(loaded, original);

        let old: Settings = serde_json::from_value(json!({})).expect("old settings");
        assert!(!old.prefer_ffmpeg_true_hd);
        assert!(!old.prefer_ffmpeg_dts);
        assert!(!old.prefer_ffmpeg_dts_hd);
    }

    /// Dedicated round-trip pinning [`Settings::show_clock`]'s exact JSON
    /// shape at a non-default value, so a future `serde` refactor fails
    /// loudly here instead of only on-device.
    #[test]
    fn show_clock_round_trips_through_json() {
        let original = Settings {
            show_clock: false,
            ..Settings::default()
        };
        let json = serde_json::to_value(&original).expect("serialize");
        assert_eq!(json["show_clock"], false);

        let loaded: Settings = serde_json::from_value(json).expect("deserialize");
        assert_eq!(loaded, original);
    }

    /// `show_clock` absent from settings.json falls back to `true`, not
    /// `bool`'s zero value.
    #[test]
    fn load_tolerates_a_settings_json_written_before_show_clock_existed() {
        let loaded = load_from_json(legacy_base());
        assert!(loaded.show_clock);
    }

    /// Pins [`Settings::tolerate_mislabeled_levels`]'s exact JSON shape at
    /// a non-default value; see `show_clock_round_trips_through_json`.
    #[test]
    fn tolerate_mislabeled_levels_round_trips_through_json() {
        let original = Settings {
            tolerate_mislabeled_levels: false,
            ..Settings::default()
        };
        let json = serde_json::to_value(&original).expect("serialize");
        assert_eq!(json["tolerate_mislabeled_levels"], false);

        let loaded: Settings = serde_json::from_value(json).expect("deserialize");
        assert_eq!(loaded, original);
    }

    /// `tolerate_mislabeled_levels` absent falls back to `true`.
    #[test]
    fn load_tolerates_a_settings_json_written_before_tolerate_mislabeled_levels_existed() {
        let mut raw = legacy_base();
        raw["show_clock"] = json!(true);
        let loaded = load_from_json(raw);
        assert!(loaded.tolerate_mislabeled_levels);
    }

    /// Pins [`Settings::show_virtual_episodes`]'s exact JSON shape at a
    /// non-default value; see `show_clock_round_trips_through_json`.
    #[test]
    fn show_virtual_episodes_round_trips_through_json() {
        let original = Settings {
            show_virtual_episodes: true,
            ..Settings::default()
        };
        let json = serde_json::to_value(&original).expect("serialize");
        assert_eq!(json["show_virtual_episodes"], true);

        let loaded: Settings = serde_json::from_value(json).expect("deserialize");
        assert_eq!(loaded, original);
    }

    /// `show_virtual_episodes` absent falls back to `false` (hiding virtuals).
    #[test]
    fn load_tolerates_a_settings_json_written_before_show_virtual_episodes_existed() {
        let mut raw = legacy_base();
        raw["show_clock"] = json!(true);
        raw["tolerate_mislabeled_levels"] = json!(true);
        let loaded = load_from_json(raw);
        assert!(!loaded.show_virtual_episodes);
    }

    /// Pins [`Settings::preload_on_focus`]'s exact JSON shape at a
    /// non-default value; see `show_clock_round_trips_through_json`.
    #[test]
    fn preload_on_focus_round_trips_through_json() {
        let original = Settings {
            preload_on_focus: false,
            ..Settings::default()
        };
        let json = serde_json::to_value(&original).expect("serialize");
        assert_eq!(json["preload_on_focus"], false);

        let loaded: Settings = serde_json::from_value(json).expect("deserialize");
        assert_eq!(loaded, original);
    }

    /// `preload_on_focus` absent falls back to `true`, not `bool`'s zeroed "off".
    #[test]
    fn load_tolerates_a_settings_json_written_before_preload_on_focus_existed() {
        let mut raw = legacy_base();
        raw["show_clock"] = json!(true);
        raw["tolerate_mislabeled_levels"] = json!(true);
        raw["show_virtual_episodes"] = json!(false);
        let loaded = load_from_json(raw);
        assert!(loaded.preload_on_focus);
    }

    /// Pins [`Settings::mini_player_enabled`]'s exact JSON shape at a
    /// non-default value; see `show_clock_round_trips_through_json`.
    #[test]
    fn mini_player_enabled_round_trips_through_json() {
        let original = Settings {
            mini_player_enabled: true,
            ..Settings::default()
        };
        let json = serde_json::to_value(&original).expect("serialize");
        assert_eq!(json["mini_player_enabled"], true);

        let loaded: Settings = serde_json::from_value(json).expect("deserialize");
        assert_eq!(loaded, original);
    }

    /// `mini_player_enabled` absent falls back to `false`
    /// (docs/17-mini-player.md's "off by default").
    #[test]
    fn load_tolerates_a_settings_json_written_before_mini_player_enabled_existed() {
        let mut raw = legacy_base();
        raw["show_clock"] = json!(true);
        raw["tolerate_mislabeled_levels"] = json!(true);
        raw["show_virtual_episodes"] = json!(false);
        raw["preload_on_focus"] = json!(true);
        let loaded = load_from_json(raw);
        assert!(!loaded.mini_player_enabled);
    }

    /// Pins [`Settings::osd_detail`]'s exact JSON shape at a non-default
    /// value; see `show_clock_round_trips_through_json`.
    #[test]
    fn osd_detail_round_trips_through_json() {
        let original = Settings {
            osd_detail: OsdDetailSetting::Minimal,
            ..Settings::default()
        };
        let json = serde_json::to_value(&original).expect("serialize");
        assert_eq!(json["osd_detail"], "Minimal");

        let loaded: Settings = serde_json::from_value(json).expect("deserialize");
        assert_eq!(loaded, original);
    }

    /// `osd_detail` absent falls back to `Full`, not `Minimal`.
    #[test]
    fn load_tolerates_a_settings_json_written_before_osd_detail_existed() {
        let mut raw = legacy_base();
        raw["show_clock"] = json!(true);
        raw["tolerate_mislabeled_levels"] = json!(true);
        raw["show_virtual_episodes"] = json!(false);
        raw["preload_on_focus"] = json!(true);
        let loaded = load_from_json(raw);
        assert_eq!(loaded.osd_detail, OsdDetailSetting::Full);
    }

    /// Pins [`Settings::seek_preview_size`]'s exact JSON shape at a non-default value.
    #[test]
    fn seek_preview_size_round_trips_through_json() {
        let original = Settings {
            seek_preview_size: SeekPreviewSize::Large,
            ..Settings::default()
        };
        let json = serde_json::to_value(&original).expect("serialize");
        assert_eq!(json["seek_preview_size"], "Large");

        let loaded: Settings = serde_json::from_value(json).expect("deserialize");
        assert_eq!(loaded, original);
    }

    /// `seek_preview_size` absent falls back to `Medium`.
    #[test]
    fn load_tolerates_a_settings_json_written_before_seek_preview_size_existed() {
        let mut raw = legacy_base();
        raw["osd_detail"] = json!("Full");
        let loaded = load_from_json(raw);
        assert_eq!(loaded.seek_preview_size, SeekPreviewSize::Medium);
    }

    /// Pins [`Settings::still_watching`]'s exact JSON shape at a
    /// non-default value; see `osd_detail_round_trips_through_json`.
    #[test]
    fn still_watching_round_trips_through_json() {
        let original = Settings {
            still_watching: StillWatchingSettings {
                mode: StillWatchingMode::AfterHours,
                episodes: 8,
                hours: 4.0,
                timeout_secs: 300,
                reset_on_input: false,
            },
            ..Settings::default()
        };
        let json = serde_json::to_value(&original).expect("serialize");
        assert_eq!(json["still_watching"]["mode"], "AfterHours");
        assert_eq!(json["still_watching"]["episodes"], 8);
        assert_eq!(json["still_watching"]["hours"], 4.0);
        assert_eq!(json["still_watching"]["timeout_secs"], 300);
        assert_eq!(json["still_watching"]["reset_on_input"], false);

        let loaded: Settings = serde_json::from_value(json).expect("deserialize");
        assert_eq!(loaded, original);
    }

    /// A `still_watching` object missing `reset_on_input` must still load,
    /// falling back to `true` (today's original behavior), not `bool`'s
    /// zeroed "off".
    #[test]
    fn still_watching_reset_on_input_defaults_true_when_missing_from_json() {
        let raw = serde_json::json!({
            "mode": "AfterHours",
            "episodes": 5,
            "hours": 2.0,
            "timeout_secs": 60,
        });
        let loaded: StillWatchingSettings =
            serde_json::from_value(raw).expect("deserialize partial still_watching");
        assert!(loaded.reset_on_input);
    }

    /// `still_watching` absent falls back to
    /// [`StillWatchingSettings::default`] (`AfterEpisodes`/3/3.0/120/true).
    #[test]
    fn load_tolerates_a_settings_json_written_before_still_watching_existed() {
        let mut raw = legacy_base();
        raw["show_clock"] = json!(true);
        raw["tolerate_mislabeled_levels"] = json!(true);
        raw["show_virtual_episodes"] = json!(false);
        raw["preload_on_focus"] = json!(true);
        raw["osd_detail"] = json!("Full");
        let loaded = load_from_json(raw);
        assert_eq!(loaded.still_watching, StillWatchingSettings::default());
    }

    #[test]
    fn still_watching_mode_maps_1_to_1_with_playback_policy() {
        assert_eq!(
            PolicyStillWatchingMode::from(StillWatchingMode::Off),
            PolicyStillWatchingMode::Off
        );
        assert_eq!(
            PolicyStillWatchingMode::from(StillWatchingMode::AfterEpisodes),
            PolicyStillWatchingMode::AfterEpisodes
        );
        assert_eq!(
            PolicyStillWatchingMode::from(StillWatchingMode::AfterHours),
            PolicyStillWatchingMode::AfterHours
        );

        assert_eq!(
            StillWatchingMode::from(PolicyStillWatchingMode::AfterHours),
            StillWatchingMode::AfterHours
        );
    }

    #[test]
    fn still_watching_settings_maps_to_still_watching_prefs() {
        let settings = StillWatchingSettings {
            mode: StillWatchingMode::AfterHours,
            episodes: 5,
            hours: 2.0,
            timeout_secs: 60,
            reset_on_input: false,
        };
        let prefs: StillWatchingPrefs = settings.into();
        assert_eq!(prefs.mode, PolicyStillWatchingMode::AfterHours);
        assert_eq!(prefs.episodes, settings.episodes);
        assert_eq!(prefs.hours, settings.hours);
        assert_eq!(prefs.timeout_secs, settings.timeout_secs);
        assert_eq!(prefs.reset_on_input, settings.reset_on_input);

        let round_tripped: StillWatchingSettings = prefs.into();
        assert_eq!(round_tripped, settings);
    }

    /// Pins the skip-segment fields' exact JSON shape at non-default
    /// preset values; see `subtitle_style_fields_round_trip_through_json`.
    #[test]
    fn skip_segment_fields_round_trip_through_json() {
        let original = Settings {
            skip_intro: SegmentAction::Off,
            skip_outro: SegmentAction::AutoSkip,
            skip_recap: SegmentAction::Off,
            skip_preview: SegmentAction::Ask,
            skip_commercial: SegmentAction::Off,
            ..Settings::default()
        };
        let json = serde_json::to_value(&original).expect("serialize");
        assert_eq!(json["skip_intro"], "Off");
        assert_eq!(json["skip_outro"], "AutoSkip");
        assert_eq!(json["skip_recap"], "Off");
        assert_eq!(json["skip_preview"], "Ask");
        assert_eq!(json["skip_commercial"], "Off");

        let loaded: Settings = serde_json::from_value(json).expect("deserialize");
        assert_eq!(loaded, original);
    }

    /// Pins the subtitle style fields' exact JSON shape at non-default
    /// preset values (`subtitle_position` serializes as its variant name),
    /// distinct from `non_default_settings_round_trip_through_json`'s
    /// blanket coverage.
    #[test]
    fn subtitle_style_fields_round_trip_through_json() {
        let original = Settings {
            subtitle_scale: 1.5,
            subtitle_position: SubtitlePositionPreset::Highest,
            subtitle_bold: true,
            subtitle_background_opacity: 0.75,
            subtitle_color: SubtitleColorPreset::LightGreen,
            subtitle_use_system_style: true,
            ..Settings::default()
        };
        let json = serde_json::to_value(&original).expect("serialize");
        assert_eq!(json["subtitle_scale"], 1.5);
        assert_eq!(json["subtitle_position"], "Highest");
        assert_eq!(json["subtitle_bold"], true);
        assert_eq!(json["subtitle_background_opacity"], 0.75);
        assert_eq!(json["subtitle_color"], "LightGreen");
        assert_eq!(json["subtitle_use_system_style"], true);

        let loaded: Settings = serde_json::from_value(json).expect("deserialize");
        assert_eq!(loaded, original);
    }

    #[test]
    fn subtitle_mode_setting_maps_1_to_1_with_playback_policy() {
        assert_eq!(
            SubtitleMode::from(SubtitleModeSetting::Default),
            SubtitleMode::Default
        );
        assert_eq!(
            SubtitleMode::from(SubtitleModeSetting::Always),
            SubtitleMode::Always
        );
        assert_eq!(
            SubtitleMode::from(SubtitleModeSetting::OnlyForced),
            SubtitleMode::OnlyForced
        );
        assert_eq!(
            SubtitleMode::from(SubtitleModeSetting::None),
            SubtitleMode::None
        );

        assert_eq!(
            SubtitleModeSetting::from(SubtitleMode::Always),
            SubtitleModeSetting::Always
        );
    }

    #[test]
    fn language_settings_maps_to_language_prefs() {
        let settings = LanguageSettings {
            audio: Some("eng".to_string()),
            subtitle: Some("fre".to_string()),
            subtitle_mode: SubtitleModeSetting::OnlyForced,
        };
        let prefs: LanguagePrefs = settings.clone().into();
        assert_eq!(prefs.audio, settings.audio);
        assert_eq!(prefs.subtitle, settings.subtitle);
        assert_eq!(prefs.subtitle_mode, SubtitleMode::OnlyForced);

        let round_tripped: LanguageSettings = prefs.into();
        assert_eq!(round_tripped, settings);
    }

    /// Pins [`PlaybackQuality`]'s internally tagged JSON shape for all
    /// three variants, so a future `#[serde(tag = "mode", ...)]` refactor
    /// fails loudly here instead of as a Kotlin-side parse failure.
    #[test]
    fn playback_quality_round_trips_through_json_for_every_variant() {
        for original in [
            PlaybackQuality::DirectPlay,
            PlaybackQuality::Auto,
            PlaybackQuality::Cap { max_bps: 3_000_000 },
        ] {
            let settings = Settings {
                playback_quality: original,
                ..Settings::default()
            };
            let json = serde_json::to_value(&settings).expect("serialize");
            let loaded: Settings = serde_json::from_value(json).expect("deserialize");
            assert_eq!(loaded.playback_quality, original);
        }

        let direct_play_json =
            serde_json::to_value(PlaybackQuality::DirectPlay).expect("serialize");
        assert_eq!(direct_play_json, serde_json::json!({"mode": "direct_play"}));

        let auto_json = serde_json::to_value(PlaybackQuality::Auto).expect("serialize");
        assert_eq!(auto_json, serde_json::json!({"mode": "auto"}));

        let cap_json =
            serde_json::to_value(PlaybackQuality::Cap { max_bps: 8_000_000 }).expect("serialize");
        assert_eq!(
            cap_json,
            serde_json::json!({"mode": "cap", "max_bps": 8_000_000})
        );
    }

    /// `playback_quality` absent falls back to [`PlaybackQuality::default`]
    /// (`DirectPlay` -- CLAUDE.md: Direct Play is the default).
    #[test]
    fn load_tolerates_a_settings_json_written_before_playback_quality_existed() {
        let mut raw = legacy_base();
        raw["show_clock"] = json!(true);
        raw["tolerate_mislabeled_levels"] = json!(true);
        raw["show_virtual_episodes"] = json!(false);
        raw["preload_on_focus"] = json!(true);
        raw["osd_detail"] = json!("Full");
        let loaded = load_from_json(raw);
        assert_eq!(loaded.playback_quality, PlaybackQuality::DirectPlay);
    }

    #[test]
    fn playback_quality_defaults_to_direct_play() {
        assert_eq!(
            Settings::default().playback_quality,
            PlaybackQuality::DirectPlay
        );
    }

    /// Pins [`Settings::diagnostic_logging_enabled`]'s exact JSON shape at a
    /// non-default value; see `show_clock_round_trips_through_json`.
    #[test]
    fn diagnostic_logging_enabled_round_trips_through_json() {
        let original = Settings {
            diagnostic_logging_enabled: true,
            ..Settings::default()
        };
        let json = serde_json::to_value(&original).expect("serialize");
        assert_eq!(json["diagnostic_logging_enabled"], true);

        let loaded: Settings = serde_json::from_value(json).expect("deserialize");
        assert_eq!(loaded, original);
    }

    /// `diagnostic_logging_enabled` absent falls back to `false` (docs/21
    /// §1.1 "default off").
    #[test]
    fn load_tolerates_a_settings_json_written_before_diagnostic_logging_enabled_existed() {
        let mut raw = legacy_base();
        raw["show_clock"] = json!(true);
        raw["tolerate_mislabeled_levels"] = json!(true);
        raw["show_virtual_episodes"] = json!(false);
        raw["preload_on_focus"] = json!(true);
        raw["osd_detail"] = json!("Full");
        raw["playback_quality"] = json!({"mode": "direct_play"});
        let loaded = load_from_json(raw);
        assert!(!loaded.diagnostic_logging_enabled);
    }

    /// Pins [`Settings::crash_reports_enabled`]'s exact JSON shape at a
    /// non-default value; see `show_clock_round_trips_through_json`.
    #[test]
    fn crash_reports_enabled_round_trips_through_json() {
        let original = Settings {
            crash_reports_enabled: false,
            ..Settings::default()
        };
        let json = serde_json::to_value(&original).expect("serialize");
        assert_eq!(json["crash_reports_enabled"], false);

        let loaded: Settings = serde_json::from_value(json).expect("deserialize");
        assert_eq!(loaded, original);
    }

    /// `crash_reports_enabled` absent falls back to `true` (docs/21 §6
    /// "Crash reports" default on), not `bool`'s zero value.
    #[test]
    fn load_tolerates_a_settings_json_written_before_crash_reports_enabled_existed() {
        let mut raw = legacy_base();
        raw["show_clock"] = json!(true);
        raw["tolerate_mislabeled_levels"] = json!(true);
        raw["show_virtual_episodes"] = json!(false);
        raw["preload_on_focus"] = json!(true);
        raw["osd_detail"] = json!("Full");
        raw["playback_quality"] = json!({"mode": "direct_play"});
        raw["diagnostic_logging_enabled"] = json!(false);
        let loaded = load_from_json(raw);
        assert!(loaded.crash_reports_enabled);
    }
}
