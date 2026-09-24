//! Per-series audio/subtitle track memory: `<data_dir>/track-prefs.json`,
//! mapping a series id to a `playback_policy::prefs::SeriesTrackPref`.
//! Written via [`crate::JellybeamCore::remember_track_choice`], read via
//! [`crate::JellybeamCore::resolve_tracks`] (docs/09-settings-plan.md step 3).
//!
//! Follows `settings.rs`'s persistence style: atomic whole-file `serde_json`
//! replacement, tolerant load (missing/unparseable falls back to an empty
//! map). Unlike `settings.rs`, the on-disk shape here is never itself a
//! `uniffi::Record` -- `SeriesTrackPref` is `playback_policy`'s own
//! plain-data type, consumed directly without a separate FFI mirror, since
//! only individual key strings ever cross the FFI boundary.
//!
//! `SeriesTrackPref`'s `Option<String>` fields already deserialize fine
//! from a file missing either key, with no `#[serde(default)]` needed.

use std::collections::HashMap;
use std::path::{Path, PathBuf};

use playback_policy::prefs::SeriesTrackPref;

/// Series id -> that series' remembered audio/subtitle track preference.
pub(crate) type TrackPrefsFile = HashMap<String, SeriesTrackPref>;

fn path(data_dir: &Path) -> PathBuf {
    data_dir.join("track-prefs.json")
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
                audio: Some("eng".to_string()),
                subtitle: Some("spa".to_string()),
            },
        );
        map.insert(
            "series-2".to_string(),
            SeriesTrackPref {
                audio: None,
                subtitle: Some("Commentary".to_string()),
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
        assert_eq!(pref.audio.as_deref(), Some("eng"));
        assert_eq!(pref.subtitle, None);
    }
}
