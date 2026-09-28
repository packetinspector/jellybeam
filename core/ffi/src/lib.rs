//! `jellybeam-ffi` -- the UniFFI bindings crate the Android app (Kotlin) calls
//! into: a version string, plus a smoke test proving `jellyfin-api`,
//! `jellyfin-core` and `media-cache` actually link through this boundary.
//!
//! Proc-macro style (no UDL file): `#[uniffi::export]` per item,
//! `setup_scaffolding!()` wires up the rest.

uniffi::setup_scaffolding!();

mod device_id;
mod diag;
mod discovery;
mod error;
pub mod glide;
mod library_prefs;
mod next_episode;
mod object;
mod persistence;
mod seerr;
mod seerr_types;
mod session;
mod settings;
mod signin;
mod still_watching;
mod track_prefs;
mod types;

pub use diag::{install_diag_sink, DiagLevel, DiagSink};
pub use discovery::DiscoveredServer;
pub use error::CoreError;
pub use glide::GlideDirection;
pub use object::{ChangeListener, JellybeamCore};
pub use seerr_types::{
    SeerrActiveRequest, SeerrAuthMethod, SeerrAvailability, SeerrBrowseFilters, SeerrBrowseKind,
    SeerrCard, SeerrGenre, SeerrHome, SeerrHomeRow, SeerrMediaType, SeerrMovieDetail,
    SeerrMyRequest, SeerrPage, SeerrPersonCredits, SeerrPersonRef, SeerrProfile, SeerrRequestInput,
    SeerrRequestOptions, SeerrRequestStatus, SeerrRootFolder, SeerrSeasonStatus,
    SeerrServiceServer, SeerrStatus, SeerrTvDetail,
};
pub use settings::{
    LanguageSettings, PlaybackQuality, SeekPreviewSize, SegmentAction, Settings, StillWatchingMode,
    StillWatchingSettings, SubtitleModeSetting, SubtitlePositionPreset,
};
pub use still_watching::StillWatchingDecision;
pub use types::{
    AccountInfo, AudioSpatialKind, Card, ChangeEvent, ChapterInfoFfi, Decade, DeviceCaps,
    EpisodeNeighbors, GridCounts, GridFilters, GridGroup, GridSort, GridSortField, HomeSnapshot,
    ImageKind, ItemDetail, LatestShelf, LibraryGridPrefs, LiveSort, MediaSegment, MediaSegmentKind,
    MediaStreamInfo, MediaStreamKind, MirrorItemCounts, MirrorLibrary, MirrorStats, PersonInfo,
    PlayMethodFfi, PlaybackOsdDetail, PlaybackPlan, QuickConnectSession, ServerDetails,
    ServerInfoSnapshot, SortOrder, StatusFilter, SubtitleActionFfi, TrackDecisionFfi, TrackInfo,
    TrackKindFfi, TrickplayMetaFfi, TrickplayTileFfi, VideoCaps, VideoCodecId, ViewSnapshot,
    WatchedFilter,
};

/// docs/16 §2.7: the drawer's Favorites page as a library view; Kotlin recognises it by
/// collection type `"favorites"`. `name` is the app's localized label.
#[uniffi::export]
pub fn favorites_view(name: String) -> ViewSnapshot {
    ViewSnapshot {
        id: media_cache::FAVORITES_VIEW_ID.to_string(),
        name,
        kind: types::ViewKind::Library,
        collection_type: Some("favorites".to_string()),
    }
}

/// Human-readable version string, e.g. "jellybeam-core 0.1.0".
#[uniffi::export]
pub fn core_version() -> String {
    format!("jellybeam-core {}", env!("CARGO_PKG_VERSION"))
}

/// Credits-aware next-up timing: delegate to
/// `playback_policy::segments::next_episode_trigger_remaining_secs`
/// (percentage-of-runtime default vs. Outro start, plus the countdown when the outro is
/// auto-skipped). Plain function, not a `JellybeamCore` method: pure arithmetic, no core state.
#[uniffi::export]
pub fn next_episode_trigger_remaining_secs(
    duration_secs: f64,
    outro_start_secs: Option<f64>,
    outro_auto_skip: bool,
    countdown_secs: f64,
) -> f64 {
    playback_policy::segments::next_episode_trigger_remaining_secs(
        duration_secs,
        outro_start_secs,
        outro_auto_skip,
        countdown_secs,
    )
}

/// Delegate to `playback_policy::segments::next_episode_countdown_total`:
/// the next-up card's countdown-fill total, `min(remaining, delay)`.
#[uniffi::export]
pub fn next_episode_countdown_total(remaining_secs: f64, delay_secs: f64) -> f64 {
    playback_policy::segments::next_episode_countdown_total(remaining_secs, delay_secs)
}

/// Returns the Outro segment's start (seconds) from an already-fetched
/// [`MediaSegment`] list (Kotlin's `PlaybackViewModel.fetchMediaSegments`,
/// backed by [`crate::JellybeamCore::get_media_segments`]), operating on the
/// FFI shape directly rather than a second fetch. `None` if no Outro entry.
#[uniffi::export]
pub fn outro_start_secs_from_segments(segments: Vec<MediaSegment>) -> Option<f64> {
    segments
        .into_iter()
        .find(|s| s.segment_type == MediaSegmentKind::Outro)
        .map(|s| playback_policy::time::ticks_to_secs(s.start_ticks))
}

/// Delegate to `playback_policy::tracks::track_pref_key`: the persistence
/// key a [`TrackInfo`] is remembered under (language if present, else
/// title). Feeds [`JellybeamCore::remember_track_choice`]'s `track_key`
/// (docs/09 step 3b).
#[uniffi::export]
pub fn track_pref_key_of(track: TrackInfo) -> Option<String> {
    playback_policy::tracks::track_pref_key(&track.into())
}

/// Delegate to `playback_policy::trickplay::locate`: which sprite-sheet
/// tile (if any) covers `position_ms` for the given [`TrickplayMetaFfi`].
///
/// `position_ms` is `u64` (Kotlin has no unsigned types) but the grid math
/// is `u32`-based; values beyond `u32::MAX` ms (~49 days) saturate rather
/// than panic on the cast, clamping to the last advertised thumbnail.
#[uniffi::export]
pub fn trickplay_locate(meta: TrickplayMetaFfi, position_ms: u64) -> Option<TrickplayTileFfi> {
    let position_ms = u32::try_from(position_ms).unwrap_or(u32::MAX);
    playback_policy::trickplay::locate(&meta.into(), position_ms).map(TrickplayTileFfi::from)
}

/// Delegate to `playback_policy::trickplay::glide_sample` (docs/12 §11): the dwell-gated tile
/// for a glide target, `None` while the dwell since `last_sample_ms` is still running. Same
/// `u64` -> `u32` saturation as [`trickplay_locate`].
#[uniffi::export]
pub fn trickplay_glide_sample(
    meta: TrickplayMetaFfi,
    target_ms: u64,
    now_ms: u64,
    last_sample_ms: Option<u64>,
    direction: GlideDirection,
) -> Option<TrickplayTileFfi> {
    let target_ms = u32::try_from(target_ms).unwrap_or(u32::MAX);
    let forward = matches!(direction, GlideDirection::Forward);
    playback_policy::trickplay::glide_sample(
        &meta.into(),
        target_ms,
        now_ms,
        last_sample_ms,
        forward,
    )
    .map(TrickplayTileFfi::from)
}

/// Delegate to `playback_policy::trickplay::locate_biased` (docs/12 §11): the tile for a seek
/// target, biased so the previewed frame is never behind the landing point in the direction of
/// travel. Same `u64` -> `u32` saturation as [`trickplay_locate`].
#[uniffi::export]
pub fn trickplay_locate_biased(
    meta: TrickplayMetaFfi,
    position_ms: u64,
    direction: GlideDirection,
) -> Option<TrickplayTileFfi> {
    let position_ms = u32::try_from(position_ms).unwrap_or(u32::MAX);
    let forward = matches!(direction, GlideDirection::Forward);
    playback_policy::trickplay::locate_biased(
        &meta.into(),
        position_ms,
        playback_policy::trickplay::bias_for(forward),
    )
    .map(TrickplayTileFfi::from)
}

/// Delegate to `playback_policy::trickplay::glide_want_list`: the sheets (at most two, fetch
/// order) a glide at `rate` media-seconds per real second should have ready.
#[uniffi::export]
pub fn trickplay_glide_sheets(
    meta: TrickplayMetaFfi,
    target_ms: u64,
    rate: u32,
    direction: GlideDirection,
) -> Vec<u32> {
    let target_ms = u32::try_from(target_ms).unwrap_or(u32::MAX);
    let forward = matches!(direction, GlideDirection::Forward);
    playback_policy::trickplay::glide_want_list(&meta.into(), target_ms, rate, forward)
}

/// Delegate to `playback_policy::trickplay::should_abandon_sheet`: whether an in-flight fetch
/// of `sheet` is no longer worth finishing for a glide now at `target_ms`.
#[uniffi::export]
pub fn trickplay_should_abandon_sheet(
    meta: TrickplayMetaFfi,
    sheet: u32,
    target_ms: u64,
    direction: GlideDirection,
) -> bool {
    let target_ms = u32::try_from(target_ms).unwrap_or(u32::MAX);
    let forward = matches!(direction, GlideDirection::Forward);
    playback_policy::trickplay::should_abandon_sheet(&meta.into(), sheet, target_ms, forward)
}

/// A temp path unique enough that concurrent [`mirror_smoke_test`] calls
/// never collide.
fn temp_mirror_dir() -> std::path::PathBuf {
    let nanos = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_nanos())
        .unwrap_or_default();
    std::env::temp_dir().join(format!(
        "jellybeam-ffi-mirror-smoke-{nanos}-{}",
        std::process::id()
    ))
}

/// Opens a throwaway `media-cache` mirror (real SQLite creation + schema
/// migration) and reports schema version and item count -- the "real
/// linkage" proof that `jellybeam-ffi` is actually wired to `media-cache`
/// (transitively `jellyfin-core`/`jellyfin-api`), not just their types.
///
/// Uses an undialable loopback URL and drops the throwaway runtime right
/// after `open()` resolves, which cancels the background sync task it
/// started -- so no real network I/O is attempted, only local file I/O.
#[uniffi::export]
pub fn mirror_smoke_test() -> String {
    let dir = temp_mirror_dir();

    let identity = jellyfin_api::ClientIdentity {
        client: "jellybeam-ffi-smoke-test".to_string(),
        device: "jellybeam-ffi".to_string(),
        device_id: "jellybeam-ffi-smoke-test".to_string(),
        version: env!("CARGO_PKG_VERSION").to_string(),
    };
    let client = jellyfin_api::JellyfinClient::from_token(
        "http://127.0.0.1:0",
        identity,
        "smoke-test-token",
    );
    let (_bus_tx, bus_rx) = tokio::sync::broadcast::channel::<jellyfin_core::BusEvent>(1);

    let rt = match tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
    {
        Ok(rt) => rt,
        Err(e) => return format!("mirror smoke test failed: could not start runtime: {e}"),
    };

    let opened = rt.block_on(media_cache::Mirror::open(dir.clone(), client, bus_rx));

    // `mirror` must drop (closing the writer's mpsc channel) before `rt`:
    // the writer thread parked on rt's blocking pool only exits once every
    // `Sender` clone is dropped, else rt's shutdown deadlocks waiting on it.
    let status = match opened {
        Ok(mirror) => {
            let status = format!(
                "mirror ok: schema v{}, {} items in fresh mirror",
                media_cache::SCHEMA_VERSION,
                mirror.item_count()
            );
            drop(mirror);
            status
        }
        Err(e) => format!("mirror open failed: {e}"),
    };

    // Safe to tear down now: no live Sender remains, so shutdown won't block.
    drop(rt);

    // Best-effort cleanup; a leftover temp dir is harmless clutter.
    let _ = std::fs::remove_dir_all(&dir);

    status
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn core_version_reports_the_crate_version() {
        let version = core_version();
        assert!(
            version.starts_with("jellybeam-core "),
            "unexpected core_version() output: {version:?}"
        );
        assert!(
            version.contains(env!("CARGO_PKG_VERSION")),
            "core_version() should embed CARGO_PKG_VERSION: {version:?}"
        );
    }

    fn sample_track(lang: Option<&str>, title: Option<&str>) -> TrackInfo {
        TrackInfo {
            id: 1,
            kind: TrackKindFfi::Audio,
            title: title.map(str::to_string),
            lang: lang.map(str::to_string),
            codec: Some("aac".to_string()),
            is_default: false,
            is_selected: false,
            is_forced: false,
        }
    }

    #[test]
    fn track_pref_key_of_prefers_lang_over_title() {
        let track = sample_track(Some("eng"), Some("Commentary"));
        assert_eq!(track_pref_key_of(track), Some("eng".to_string()));
    }

    #[test]
    fn track_pref_key_of_falls_back_to_title_without_a_lang() {
        let track = sample_track(None, Some("Commentary"));
        assert_eq!(track_pref_key_of(track), Some("Commentary".to_string()));
    }

    #[test]
    fn track_pref_key_of_is_none_without_lang_or_title() {
        let track = sample_track(None, None);
        assert_eq!(track_pref_key_of(track), None);
    }

    #[test]
    fn next_episode_trigger_remaining_secs_delegates_to_playback_policy() {
        assert_eq!(
            next_episode_trigger_remaining_secs(120.0, None, false, 10.0),
            playback_policy::segments::next_episode_show_threshold(120.0)
        );
        assert_eq!(
            next_episode_trigger_remaining_secs(2700.0, Some(2520.0), false, 10.0),
            180.0
        );
        assert_eq!(
            next_episode_trigger_remaining_secs(2700.0, Some(2520.0), true, 10.0),
            190.0
        );
    }

    fn segment(kind: MediaSegmentKind, start_ticks: i64) -> MediaSegment {
        MediaSegment {
            segment_type: kind,
            start_ticks,
            end_ticks: start_ticks + 10_000_000,
        }
    }

    #[test]
    fn outro_start_secs_from_segments_finds_the_outro_regardless_of_position() {
        let segments = vec![
            segment(MediaSegmentKind::Intro, 0),
            segment(MediaSegmentKind::Outro, 25_200_000_000),
        ];
        assert_eq!(outro_start_secs_from_segments(segments), Some(2520.0));
    }

    #[test]
    fn outro_start_secs_from_segments_is_none_without_an_outro_segment() {
        let segments = vec![segment(MediaSegmentKind::Intro, 0)];
        assert_eq!(outro_start_secs_from_segments(segments), None);
    }

    #[test]
    fn outro_start_secs_from_segments_is_none_for_an_empty_list() {
        assert_eq!(outro_start_secs_from_segments(Vec::new()), None);
    }

    #[test]
    fn next_episode_countdown_total_delegates_to_playback_policy() {
        assert_eq!(next_episode_countdown_total(120.0, 10.0), 10.0);
        assert_eq!(next_episode_countdown_total(4.0, 10.0), 4.0);
    }

    fn sample_trickplay_meta() -> TrickplayMetaFfi {
        TrickplayMetaFfi {
            width: 320,
            height: 180,
            tile_width: 10,
            tile_height: 10,
            interval_ms: 10_000,
            thumbnail_count: 1_000,
        }
    }

    #[test]
    fn trickplay_locate_delegates_to_playback_policy() {
        let meta = sample_trickplay_meta();
        assert_eq!(
            trickplay_locate(meta, 0),
            Some(TrickplayTileFfi {
                image_index: 0,
                x: 0,
                y: 0
            })
        );
        assert_eq!(
            trickplay_locate(meta, 10_000),
            Some(TrickplayTileFfi {
                image_index: 0,
                x: 320,
                y: 0
            })
        );
    }

    #[test]
    fn trickplay_locate_is_none_on_degenerate_geometry() {
        let mut meta = sample_trickplay_meta();
        meta.interval_ms = 0;
        assert_eq!(trickplay_locate(meta, 0), None);
    }

    /// Pins that a u64 position far beyond u32 range saturates rather than panics.
    #[test]
    fn trickplay_locate_saturates_a_u64_position_beyond_u32_range() {
        let meta = sample_trickplay_meta();
        let huge = u64::from(u32::MAX) + 1_000_000;
        assert_eq!(
            trickplay_locate(meta, huge),
            trickplay_locate(meta, u64::from(u32::MAX))
        );
    }

    #[test]
    fn mirror_smoke_test_opens_a_real_mirror() {
        let status = mirror_smoke_test();
        assert!(
            status.starts_with("mirror ok: schema v"),
            "mirror smoke test did not report success: {status:?}"
        );
        assert!(
            status.contains(&media_cache::SCHEMA_VERSION.to_string()),
            "mirror smoke test should report the real schema version: {status:?}"
        );
    }
}
