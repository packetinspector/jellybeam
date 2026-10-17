//! Per-series audio/subtitle track memory: `<data_dir>/track-prefs.json`,
//! mapping a series id to a `playback_policy::prefs::SeriesTrackPref`.
//! Written via [`crate::JellybeamCore::remember_track_memory`], read via
//! [`crate::JellybeamCore::resolve_tracks`] (docs/12 "Choices are remembered per series").
//!
//! Atomic whole-file `serde_json` replacement; a missing or unparseable file loads as an empty
//! map. `SeriesTrackPref` is `playback_policy`'s own type, with no FFI mirror: only
//! `TrackMemoryFfi` crosses the boundary. A missing half loads `Unset`, an unknown-shaped half
//! also loads `Unset` without failing the map, and a subtitle `Off` is stored as `{"off":true}`.

use std::collections::HashMap;
use std::path::{Path, PathBuf};

use playback_policy::prefs::{SeriesTrackPref, TrackMemory};

use crate::types::TrackKindFfi;

/// Series id -> that series' remembered audio/subtitle track preference.
pub(crate) type TrackPrefsFile = HashMap<String, SeriesTrackPref>;

fn path(data_dir: &Path) -> PathBuf {
    data_dir.join("track-prefs.json")
}

/// Sets one half of `series_id`'s memory. A pairing with no memory (video, or audio `Off`, since
/// audio has none) is refused with a warning and writes nothing; returns whether `prefs` changed.
pub(crate) fn remember(
    prefs: &mut TrackPrefsFile,
    series_id: String,
    kind: TrackKindFfi,
    memory: TrackMemory,
) -> bool {
    match (kind, memory) {
        (TrackKindFfi::Video, _) | (TrackKindFfi::Audio, TrackMemory::Off) => {
            tracing::warn!(
                ?kind,
                "remember_track_memory: no per-series memory for this kind; no-op"
            );
            false
        }
        (TrackKindFfi::Audio, memory) => {
            prefs.entry(series_id).or_default().audio = memory;
            true
        }
        (TrackKindFfi::Subtitle, memory) => {
            prefs.entry(series_id).or_default().subtitle = memory;
            true
        }
    }
}

/// Atomically replace `prefs`; the caller serializes snapshot revisions.
pub(crate) fn save(data_dir: &Path, prefs: &TrackPrefsFile) -> std::io::Result<()> {
    crate::persistence::save_json(&path(data_dir), prefs)
}

/// Load previously saved per-series track prefs, or an empty map --
/// missing/unreadable/malformed all read as "nothing remembered yet".
pub(crate) fn load(data_dir: &Path) -> TrackPrefsFile {
    std::fs::read(path(data_dir))
        .ok()
        .and_then(|bytes| serde_json::from_slice(&bytes).ok())
        .unwrap_or_default()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample() -> TrackPrefsFile {
        let mut map = HashMap::new();
        map.insert(
            "series-1".to_string(),
            SeriesTrackPref {
                audio: TrackMemory::Key("eng".to_string()),
                subtitle: TrackMemory::Key("spa".to_string()),
            },
        );
        map.insert(
            "series-2".to_string(),
            SeriesTrackPref {
                audio: TrackMemory::Unset,
                subtitle: TrackMemory::Key("Commentary".to_string()),
            },
        );
        map
    }

    #[test]
    fn round_trips_through_disk() {
        let dir = tempfile::tempdir().expect("tempdir");
        let original = sample();
        save(dir.path(), &original).expect("save track prefs");
        let loaded = load(dir.path());
        assert_eq!(loaded, original);
    }

    #[test]
    fn load_returns_empty_map_when_no_file_exists() {
        let dir = tempfile::tempdir().expect("tempdir");
        assert!(load(dir.path()).is_empty());
    }

    #[test]
    fn load_returns_empty_map_for_malformed_json() {
        let dir = tempfile::tempdir().expect("tempdir");
        std::fs::write(path(dir.path()), b"not json").expect("write garbage");
        assert!(load(dir.path()).is_empty());
    }

    /// A missing `Option<T>` field deserializes as `None` with no
    /// `#[serde(default)]` needed, so a file missing one key still loads.
    #[test]
    fn load_tolerates_an_entry_missing_one_optional_field() {
        let dir = tempfile::tempdir().expect("tempdir");
        let raw = serde_json::json!({
            "series-1": { "audio": "eng" },
        });
        std::fs::write(
            path(dir.path()),
            serde_json::to_vec(&raw).expect("serialize"),
        )
        .expect("write track-prefs.json");

        let loaded = load(dir.path());
        let pref = loaded.get("series-1").expect("series-1 entry present");
        assert_eq!(pref.audio, TrackMemory::Key("eng".to_string()));
        assert_eq!(pref.subtitle, TrackMemory::Unset);
    }

    /// docs/12 "Choices are remembered per series": files written before Off existed (`null`,
    /// absent, or a key string) must keep loading with the same meaning.
    #[test]
    fn load_reads_the_legacy_subtitle_shapes_as_unset_or_key() {
        let dir = tempfile::tempdir().expect("tempdir");
        let raw = serde_json::json!({
            "null-subtitle": { "audio": null, "subtitle": null },
            "missing-subtitle": { "audio": "eng" },
            "keyed-subtitle": { "audio": null, "subtitle": "k" },
        });
        std::fs::write(
            path(dir.path()),
            serde_json::to_vec(&raw).expect("serialize"),
        )
        .expect("write track-prefs.json");

        let loaded = load(dir.path());
        assert_eq!(loaded["null-subtitle"].subtitle, TrackMemory::Unset);
        assert_eq!(loaded["missing-subtitle"].subtitle, TrackMemory::Unset);
        assert_eq!(
            loaded["keyed-subtitle"].subtitle,
            TrackMemory::Key("k".to_string())
        );
    }

    /// An entry whose half has a shape this build does not know (newer file, hand edit) reads as
    /// Unset instead of failing the whole map, which the next save would turn into a wipe.
    #[test]
    fn an_unknown_shaped_memory_does_not_drop_the_other_series() {
        let dir = tempfile::tempdir().expect("tempdir");
        let raw = serde_json::json!({
            "good": { "audio": "eng", "subtitle": { "off": true } },
            "empty-object": { "audio": {} },
            "on-tag": { "subtitle": { "on": true } },
            "off-string": { "subtitle": { "off": "yes" } },
            "number": { "audio": 42 },
            "array": { "subtitle": ["a"] },
        });
        std::fs::write(
            path(dir.path()),
            serde_json::to_vec(&raw).expect("serialize"),
        )
        .expect("write track-prefs.json");

        let loaded = load(dir.path());
        assert_eq!(loaded.len(), 6, "{loaded:?}");
        assert_eq!(loaded["good"].audio, TrackMemory::Key("eng".to_string()));
        assert_eq!(loaded["good"].subtitle, TrackMemory::Off);
        assert_eq!(loaded["empty-object"].audio, TrackMemory::Unset);
        assert_eq!(loaded["on-tag"].subtitle, TrackMemory::Unset);
        assert_eq!(loaded["off-string"].subtitle, TrackMemory::Unset);
        assert_eq!(loaded["number"].audio, TrackMemory::Unset);
        assert_eq!(loaded["array"].subtitle, TrackMemory::Unset);
    }

    #[test]
    fn remember_refuses_video_and_audio_off_and_writes_nothing() {
        let mut prefs = TrackPrefsFile::new();
        for (kind, memory) in [
            (TrackKindFfi::Video, TrackMemory::Key("eng".to_string())),
            (TrackKindFfi::Audio, TrackMemory::Off),
        ] {
            assert!(!remember(&mut prefs, "s".to_string(), kind, memory));
        }
        assert!(prefs.is_empty(), "{prefs:?}");
        assert!(remember(
            &mut prefs,
            "s".to_string(),
            TrackKindFfi::Subtitle,
            TrackMemory::Off
        ));
        assert_eq!(prefs["s"].subtitle, TrackMemory::Off);
        assert_eq!(prefs["s"].audio, TrackMemory::Unset);
    }

    /// docs/18 §3.1: a subtitle Off survives a save/load and reads back as Off, not Unset.
    #[test]
    fn a_saved_subtitle_off_loads_back_as_off() {
        let dir = tempfile::tempdir().expect("tempdir");
        let mut map = TrackPrefsFile::new();
        map.insert(
            "series-1".to_string(),
            SeriesTrackPref {
                audio: TrackMemory::Key("eng".to_string()),
                subtitle: TrackMemory::Off,
            },
        );
        save(dir.path(), &map).expect("save track prefs");
        assert_eq!(load(dir.path()), map);
    }

    /// docs/18 §3.1: Off and Unset must not share an on-disk representation, or a reload would
    /// forget the choice.
    #[test]
    fn a_saved_subtitle_off_is_distinguishable_from_unset_on_disk() {
        let dir = tempfile::tempdir().expect("tempdir");
        let mut map = TrackPrefsFile::new();
        map.insert(
            "off".to_string(),
            SeriesTrackPref {
                audio: TrackMemory::Unset,
                subtitle: TrackMemory::Off,
            },
        );
        map.insert("unset".to_string(), SeriesTrackPref::default());
        save(dir.path(), &map).expect("save track prefs");

        let on_disk: serde_json::Value =
            serde_json::from_slice(&std::fs::read(path(dir.path())).expect("read")).expect("json");
        assert_ne!(
            on_disk["off"]["subtitle"], on_disk["unset"]["subtitle"],
            "Off and Unset serialized identically: {on_disk}"
        );
    }
}
