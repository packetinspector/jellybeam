//! Per-library sort/filter persistence: `<data_dir>/library_grid_prefs.json`,
//! mapping a view id to its [`LibraryGridPrefs`] (docs/16-library-sort-filter.md
//! §5 -- "per library, forever"). Whole-file `serde_json` read/write, tolerant
//! load (missing file = empty map), `#[serde(default)]` throughout so an
//! older-build file with missing fields still loads with defaults filled in.
//! Unlike `track_prefs.rs`, this map's value type is itself a `uniffi::Record`
//! traded whole across the boundary by `object.rs`'s get/set methods.
//!
//! An unreadable (not just missing) file is logged via `tracing::warn`; either
//! way [`load`] falls back to an empty map -- never blocks library browsing.

use std::collections::HashMap;
use std::path::{Path, PathBuf};

use crate::types::LibraryGridPrefs;

/// View id -> that view's remembered sort/filter state. An id with no entry
/// reads as [`LibraryGridPrefs::default`] (see
/// [`crate::JellybeamCore::get_library_grid_prefs`]).
pub(crate) type LibraryGridPrefsFile = HashMap<String, LibraryGridPrefs>;

fn path(data_dir: &Path) -> PathBuf {
    data_dir.join("library_grid_prefs.json")
}

/// Atomically replace `prefs`; the caller serializes snapshot revisions.
pub(crate) fn save(data_dir: &Path, prefs: &LibraryGridPrefsFile) -> std::io::Result<()> {
    crate::persistence::save_json(&path(data_dir), prefs)
}

/// Load previously saved per-view grid prefs, or an empty map. A missing
/// file is silently treated as empty; a file that exists but fails to
/// read/parse is logged via `tracing::warn` and also falls back to empty.
pub(crate) fn load(data_dir: &Path) -> LibraryGridPrefsFile {
    let file_path = path(data_dir);
    match std::fs::read(&file_path) {
        Ok(bytes) => serde_json::from_slice(&bytes).unwrap_or_else(|e| {
            tracing::warn!(
                error = %e,
                "failed to parse library_grid_prefs.json; starting from an empty map"
            );
            HashMap::new()
        }),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => HashMap::new(),
        Err(e) => {
            tracing::warn!(
                error = %e,
                "failed to read library_grid_prefs.json; starting from an empty map"
            );
            HashMap::new()
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::types::{Decade, GridFilters, GridSort, GridSortField, StatusFilter, WatchedFilter};

    fn sample() -> LibraryGridPrefsFile {
        let mut map = HashMap::new();
        map.insert(
            "view-movies".to_string(),
            LibraryGridPrefs {
                sort: GridSort {
                    field: GridSortField::DateAdded,
                    descending: true,
                },
                filters: GridFilters {
                    watched: WatchedFilter::Unwatched,
                    genre: Some("Comedy".to_string()),
                    decade: Some(Decade::D2010s),
                    status: StatusFilter::Any,
                },
            },
        );
        map.insert(
            "view-tvshows".to_string(),
            LibraryGridPrefs {
                sort: GridSort {
                    field: GridSortField::Runtime,
                    descending: false,
                },
                filters: GridFilters {
                    watched: WatchedFilter::HasUnwatched,
                    genre: None,
                    decade: None,
                    status: StatusFilter::Continuing,
                },
            },
        );
        map
    }

    #[test]
    fn round_trips_through_disk() {
        let dir = tempfile::tempdir().expect("tempdir");
        let original = sample();
        save(dir.path(), &original).expect("save library grid prefs");
        let loaded = load(dir.path());
        assert_eq!(loaded, original);
    }

    #[test]
    fn save_leaves_no_tmp_file_behind() {
        let dir = tempfile::tempdir().expect("tempdir");
        save(dir.path(), &sample()).expect("save library grid prefs");
        assert_eq!(std::fs::read_dir(dir.path()).expect("entries").count(), 1);
    }

    #[test]
    fn a_legacy_tmp_file_does_not_affect_load_or_save() {
        let dir = tempfile::tempdir().expect("tempdir");
        let tmp_path = dir.path().join("library_grid_prefs.json.tmp");
        std::fs::write(&tmp_path, b"garbage from a crashed write").expect("write stale tmp");

        assert!(
            load(dir.path()).is_empty(),
            "a stale .tmp with no real file yet must load as empty, not error"
        );

        let original = sample();
        save(dir.path(), &original).expect("save library grid prefs");
        assert_eq!(load(dir.path()), original);
        assert_eq!(
            std::fs::read(tmp_path).expect("legacy temp"),
            b"garbage from a crashed write"
        );
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

    /// An entry with only `sort` set still loads: `#[serde(default)]` fills
    /// in `filters` rather than failing the whole map's deserialize.
    #[test]
    fn load_tolerates_an_entry_with_only_sort_set() {
        let dir = tempfile::tempdir().expect("tempdir");
        let raw = serde_json::json!({
            "view-1": {
                "sort": { "field": "Year", "descending": true }
            }
        });
        std::fs::write(
            path(dir.path()),
            serde_json::to_vec(&raw).expect("serialize"),
        )
        .expect("write library_grid_prefs.json");

        let loaded = load(dir.path());
        let prefs = loaded.get("view-1").expect("view-1 entry present");
        assert_eq!(prefs.sort.field, GridSortField::Year);
        assert!(prefs.sort.descending);
        assert_eq!(prefs.filters, GridFilters::default());
    }

    /// A stale `"has_unwatched"` key (pre-`WatchedFilter::HasUnwatched`) is
    /// ignored by serde's default unknown-field handling; the rest loads.
    #[test]
    fn load_tolerates_an_entry_with_a_stale_has_unwatched_key() {
        let dir = tempfile::tempdir().expect("tempdir");
        let raw = serde_json::json!({
            "view-1": {
                "sort": { "field": "Name", "descending": false },
                "filters": {
                    "watched": "Unwatched",
                    "has_unwatched": true,
                    "genre": null,
                    "decade": null,
                    "status": "Any"
                }
            }
        });
        std::fs::write(
            path(dir.path()),
            serde_json::to_vec(&raw).expect("serialize"),
        )
        .expect("write library_grid_prefs.json");

        let loaded = load(dir.path());
        let prefs = loaded.get("view-1").expect("view-1 entry present");
        assert_eq!(prefs.filters.watched, WatchedFilter::Unwatched);
        assert!(prefs.filters.genre.is_none());
        assert_eq!(prefs.filters.status, StatusFilter::Any);
    }

    /// Pins that an unknown view id has no entry in the loaded map;
    /// defaulting is [`crate::JellybeamCore::get_library_grid_prefs`]'s job.
    #[test]
    fn loaded_map_has_no_entry_for_an_unknown_view_id() {
        let dir = tempfile::tempdir().expect("tempdir");
        let original = sample();
        save(dir.path(), &original).expect("save library grid prefs");

        let loaded = load(dir.path());
        assert!(!loaded.contains_key("view-unknown"));
    }
}
