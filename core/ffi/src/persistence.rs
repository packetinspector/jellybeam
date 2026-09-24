//! Atomic JSON replacement; callers own ordering between successive snapshots.

use std::fs::{File, OpenOptions};
use std::io::{self, Write};
use std::path::Path;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Mutex;

static NEXT_TEMP: AtomicU64 = AtomicU64::new(0);

/// Per-owner snapshot ordering; never hold the owner's state lock while saving.
#[derive(Default)]
pub(crate) struct RevisionWriter(Mutex<u64>);

impl RevisionWriter {
    pub(crate) fn save(
        &self,
        revision: u64,
        write: impl FnOnce() -> io::Result<()>,
    ) -> io::Result<()> {
        let mut saved = self.0.lock().unwrap_or_else(|e| e.into_inner());
        if revision <= *saved {
            return Ok(());
        }
        write()?;
        *saved = revision;
        Ok(())
    }
}

/// Readers see the old or new complete file after a process crash, never a partial write.
/// No fsync: this does not promise durability across power loss.
pub(crate) fn save_json(path: &Path, value: &impl serde::Serialize) -> io::Result<()> {
    let bytes = serde_json::to_vec_pretty(value)
        .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))?;
    replace_with(path, |file| file.write_all(&bytes))
}

/// Same guarantee as [`save_json`] for a small opaque value.
pub(crate) fn save_bytes(path: &Path, bytes: &[u8]) -> io::Result<()> {
    replace_with(path, |file| file.write_all(bytes))
}

fn replace_with(path: &Path, write: impl FnOnce(&mut File) -> io::Result<()>) -> io::Result<()> {
    let parent = path.parent().unwrap_or_else(|| Path::new("."));
    let (temp, mut file) = loop {
        let counter = NEXT_TEMP.fetch_add(1, Ordering::Relaxed);
        let temp = parent.join(format!(".jellybeam.tmp-{}-{counter}", std::process::id()));
        match OpenOptions::new().write(true).create_new(true).open(&temp) {
            Ok(file) => break (temp, file),
            Err(e) if e.kind() == io::ErrorKind::AlreadyExists => continue,
            Err(e) => return Err(e),
        }
    };
    let result = write(&mut file);
    drop(file);
    result
        .and_then(|()| std::fs::rename(&temp, path))
        .inspect_err(|_| {
            let _ = std::fs::remove_file(&temp);
        })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn stale_revision_cannot_overwrite_newer_saved_snapshot() {
        let writer = RevisionWriter::default();
        let dir = tempfile::tempdir().expect("tempdir");
        let path = dir.path().join("prefs.json");
        writer
            .save(2, || save_json(&path, &"newer"))
            .expect("newer");
        writer
            .save(1, || panic!("stale snapshot must not be written"))
            .expect("skip");
        assert_eq!(std::fs::read_to_string(path).expect("read"), "\"newer\"");
    }

    #[test]
    fn failed_revision_does_not_prevent_retry() {
        let writer = RevisionWriter::default();
        assert!(writer
            .save(2, || Err(io::Error::other("synthetic failure")))
            .is_err());
        let mut retried = false;
        writer
            .save(2, || {
                retried = true;
                Ok(())
            })
            .expect("retry");
        assert!(retried);
    }

    #[test]
    fn serialization_failure_preserves_previous_file() {
        struct Invalid;
        impl serde::Serialize for Invalid {
            fn serialize<S: serde::Serializer>(&self, _: S) -> Result<S::Ok, S::Error> {
                Err(serde::ser::Error::custom("synthetic serialization failure"))
            }
        }
        let dir = tempfile::tempdir().expect("tempdir");
        let path = dir.path().join("prefs.json");
        save_json(&path, &vec![1, 2]).expect("seed");
        let before = std::fs::read(&path).expect("read");
        assert_eq!(
            save_json(&path, &Invalid).expect_err("must fail").kind(),
            io::ErrorKind::InvalidData
        );
        assert_eq!(std::fs::read(&path).expect("read"), before);
        assert_eq!(std::fs::read_dir(dir.path()).expect("entries").count(), 1);
    }

    #[test]
    fn partial_write_failure_preserves_previous_file_and_cleans_temp() {
        let dir = tempfile::tempdir().expect("tempdir");
        let path = dir.path().join("prefs.json");
        save_json(&path, &vec![1, 2]).expect("seed");
        let before = std::fs::read(&path).expect("read");
        let result = replace_with(&path, |file| {
            file.write_all(b"{\"incomplete\":")?;
            Err(io::Error::other("synthetic write failure"))
        });
        assert!(result.is_err());
        assert_eq!(std::fs::read(&path).expect("read"), before);
        assert_eq!(std::fs::read_dir(dir.path()).expect("entries").count(), 1);
    }

    #[test]
    fn rename_failure_cleans_temp_without_changing_destination() {
        let dir = tempfile::tempdir().expect("tempdir");
        let path = dir.path().join("prefs.json");
        std::fs::create_dir(&path).expect("conflicting directory");
        assert!(save_json(&path, &vec![1, 2]).is_err());
        assert!(path.is_dir());
        assert_eq!(std::fs::read_dir(dir.path()).expect("entries").count(), 1);
    }

    #[test]
    fn concurrent_replacements_leave_one_complete_snapshot() {
        let dir = tempfile::tempdir().expect("tempdir");
        let path = dir.path().join("prefs.json");
        std::thread::scope(|scope| {
            for value in 0u32..16 {
                let path = &path;
                scope.spawn(move || save_json(path, &vec![value; 1024]).expect("save"));
            }
        });
        let values: Vec<u32> =
            serde_json::from_slice(&std::fs::read(&path).expect("read")).expect("complete JSON");
        assert_eq!(values.len(), 1024);
        assert!(values.iter().all(|value| *value == values[0]));
        assert_eq!(std::fs::read_dir(dir.path()).expect("entries").count(), 1);
    }
}
