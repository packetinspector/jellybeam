//! On-disk session persistence.
//!
//! Multi-server/user support: every signed-in `(server, user)` pair is kept
//! in one list, `SessionList { sessions, active }`, persisted at
//! `<data_dir>/sessions.json`; `SessionFile` also carries `mirror_dir` --
//! see [`mirror_dir_name`] for why every session gets its own.
//!
//! **Migration**: a pre-list install (a lone `session.json`) is
//! transparently upgraded the first time [`load_list`] runs, pinned to the
//! legacy `"mirror"` directory via [`legacy_mirror_dir_name`] and written
//! out as `sessions.json`; the legacy file stays as a rollback fallback.
//!
//! Contains real access tokens: never logged, never included in errors.

#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub(crate) struct SessionFile {
    pub server_url: String,
    pub user_id: String,
    pub user_name: String,
    pub token: String,
    pub device_id: String,
    /// Subdirectory of `data_dir` holding this session's mirror SQLite
    /// file -- see [`mirror_dir_name`]. `#[serde(default = ..)]` so a
    /// legacy `session.json` still deserializes; [`load_list`]'s migration
    /// path relies on that default to pin the pre-existing `"mirror"`
    /// directory rather than a freshly hashed one.
    #[serde(default = "legacy_mirror_dir_name")]
    pub mirror_dir: String,
    /// The server's `/System/Info/Public` `Version` string (docs/13 Server
    /// compatibility), kept raw so an unparseable future format
    /// only fails to gate, never to load. `None` until the first refresh.
    #[serde(default)]
    pub server_version: Option<String>,
    /// The server's own `ServerName` (docs/13 About > server info), shown verbatim -- never
    /// renamed/prettified (CLAUDE.md). `None` until the first refresh; carried over on
    /// [`SessionList::add_or_update`] the same way as [`Self::server_version`].
    #[serde(default)]
    pub server_name: Option<String>,
}

/// Every signed-in `(server, user)` session plus which one is currently
/// active. `active` is a plain index, kept in range by
/// [`SessionList::add_or_update`]/[`remove_active`]/[`remove_at`] so
/// callers never re-clamp it themselves.
#[derive(Debug, Clone, Default, serde::Serialize, serde::Deserialize)]
pub(crate) struct SessionList {
    pub sessions: Vec<SessionFile>,
    pub active: usize,
}

impl SessionList {
    pub(crate) fn active_session(&self) -> Option<&SessionFile> {
        self.sessions.get(self.active)
    }

    /// Inserts `session`, or replaces an existing entry for the same
    /// `(server_url, user_id)` pair, and makes it active. When replacing,
    /// `mirror_dir` is overwritten with the *existing* entry's value, not
    /// the caller's guess, so a re-authentication never orphans an
    /// already-synced cache; `server_version`/`server_name` get the same
    /// carry-over, each only when the caller left it `None`.
    pub(crate) fn add_or_update(&mut self, mut session: SessionFile) {
        if let Some(ix) = self
            .sessions
            .iter()
            .position(|s| s.server_url == session.server_url && s.user_id == session.user_id)
        {
            session.mirror_dir = self.sessions[ix].mirror_dir.clone();
            if session.server_version.is_none() {
                session.server_version = self.sessions[ix].server_version.clone();
            }
            if session.server_name.is_none() {
                session.server_name = self.sessions[ix].server_name.clone();
            }
            self.sessions[ix] = session;
            self.active = ix;
        } else {
            self.sessions.push(session);
            self.active = self.sessions.len() - 1;
        }
    }

    /// Removes the *active* session (`sign_out`'s semantics): if any
    /// remain, the first becomes active -- always acts on whichever
    /// session is currently active, never a caller-chosen index. Returns
    /// the removed entry, or `None` if the list was already empty.
    pub(crate) fn remove_active(&mut self) -> Option<SessionFile> {
        if self.sessions.is_empty() {
            return None;
        }
        let active = self.active.min(self.sessions.len() - 1);
        let removed = self.sessions.remove(active);
        self.active = 0;
        Some(removed)
    }

    /// Removes an arbitrary saved account, shifting the active index to
    /// keep the same account active, or selecting the first remaining one
    /// if the active row itself was removed.
    pub(crate) fn remove_at(&mut self, index: usize) -> Option<SessionFile> {
        if index >= self.sessions.len() {
            return None;
        }
        let removed_active = index == self.active;
        let removed = self.sessions.remove(index);
        self.active = if self.sessions.is_empty() || removed_active {
            0
        } else if index < self.active {
            self.active - 1
        } else {
            self.active
        };
        Some(removed)
    }
}

/// Deterministic directory name for a `(server_url, user_id)` pair's
/// mirror, so switching sessions never mixes two servers'/users' cached
/// item ids in one `mirror.db`. Uses `DefaultHasher` rather than embedding
/// the raw `server_url` (CLAUDE.md: never log a real hostname) or a
/// random-per-process seed (must be stable across launches).
pub(crate) fn mirror_dir_name(server_url: &str, user_id: &str) -> String {
    use std::hash::{Hash, Hasher};
    let mut hasher = std::collections::hash_map::DefaultHasher::new();
    server_url.hash(&mut hasher);
    0u8.hash(&mut hasher); // separator: avoid ("ab","c") colliding with ("a","bc")
    user_id.hash(&mut hasher);
    format!("mirror-{:016x}", hasher.finish())
}

/// The pre-multi-session mirror directory name (`data_dir/mirror`) --
/// used by `SessionFile::mirror_dir`'s serde default and [`load_list`]'s
/// migration so an upgrade never orphans an already-synced mirror.
/// Distinguishable from a hashed name by construction:
/// [`mirror_dir_name`] always produces `"mirror-"` + 16 hex digits.
fn legacy_mirror_dir_name() -> String {
    "mirror".to_string()
}

fn legacy_path(data_dir: &std::path::Path) -> std::path::PathBuf {
    data_dir.join("session.json")
}

fn list_path(data_dir: &std::path::Path) -> std::path::PathBuf {
    data_dir.join("sessions.json")
}

/// Load the full multi-server/user session list. Reads `sessions.json`
/// first; falls back to migrating the legacy `session.json`, or an empty
/// list if neither exists -- never `None`.
pub(crate) fn load_list(data_dir: &std::path::Path) -> SessionList {
    if let Ok(bytes) = std::fs::read(list_path(data_dir)) {
        match serde_json::from_slice::<SessionList>(&bytes) {
            Ok(list) => return list,
            Err(e) => {
                tracing::warn!(
                    error = %e,
                    bytes_len = bytes.len(),
                    "sessions.json failed to parse; falling back to the legacy \
                     single-session blob (or an empty session list)"
                );
            }
        }
    }

    match load_legacy(data_dir) {
        Some(mut legacy) => {
            // The legacy blob predates `mirror_dir`; serde's default
            // already filled it, but pin it explicitly here too in case a
            // newer-format blob is ever found at the legacy path.
            legacy.mirror_dir = legacy_mirror_dir_name();
            let list = SessionList {
                sessions: vec![legacy],
                active: 0,
            };
            if let Err(e) = save_list(data_dir, &list) {
                tracing::warn!(error = %e, "failed to persist migrated sessions.json");
            }
            list
        }
        None => SessionList::default(),
    }
}

fn load_legacy(data_dir: &std::path::Path) -> Option<SessionFile> {
    let bytes = std::fs::read(legacy_path(data_dir)).ok()?;
    serde_json::from_slice(&bytes).ok()
}

/// Atomically replace the saved session list without exposing partial JSON.
pub(crate) fn save_list(data_dir: &std::path::Path, list: &SessionList) -> std::io::Result<()> {
    crate::persistence::save_json(&list_path(data_dir), list)
}

/// Load the *active* session, if any. Thin compat wrapper over
/// [`load_list`] kept so read-only call sites didn't need to change when
/// the on-disk shape grew from one session to a list.
pub(crate) fn load(data_dir: &std::path::Path) -> Option<SessionFile> {
    load_list(data_dir).active_session().cloned()
}

/// Insert-or-replace `session` into the on-disk list and make it active,
/// returning the finalized entry actually persisted -- `add_or_update`
/// may have overwritten the caller's guessed `mirror_dir` with an
/// existing entry's real one.
#[cfg(test)]
pub(crate) fn add_or_update(
    data_dir: &std::path::Path,
    session: SessionFile,
) -> std::io::Result<SessionFile> {
    let mut list = load_list(data_dir);
    list.add_or_update(session);
    let saved = list
        .active_session()
        .cloned()
        .expect("add_or_update always leaves `active` pointing at the just-inserted entry");
    save_list(data_dir, &list)?;
    Ok(saved)
}

/// Remove the currently active session (`sign_out`'s semantics): if
/// others remain, the first becomes active; `sessions.json` is rewritten,
/// including as an explicit empty list (deleting it would resurrect a
/// retained legacy `session.json`). Best-effort: a filesystem error is
/// logged and swallowed.
pub(crate) fn remove_active(data_dir: &std::path::Path) {
    let mut list = load_list(data_dir);
    if list.remove_active().is_none() {
        return;
    }
    if let Err(e) = save_list(data_dir, &list) {
        tracing::warn!(error = %e, "failed to persist sessions.json after sign-out");
    }
}

/// Remove a caller-selected saved account and durably persist the result.
/// Unlike [remove_active], errors are returned: the management UI must
/// not claim success while credentials are still on disk.
pub(crate) fn remove_at(
    data_dir: &std::path::Path,
    index: usize,
) -> std::io::Result<Option<SessionFile>> {
    let mut list = load_list(data_dir);
    let Some(removed) = list.remove_at(index) else {
        return Ok(None);
    };
    save_list(data_dir, &list)?;
    Ok(Some(removed))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample() -> SessionFile {
        sample_for("http://example.invalid", "user-123")
    }

    fn sample_for(server_url: &str, user_id: &str) -> SessionFile {
        SessionFile {
            server_url: server_url.to_string(),
            user_id: user_id.to_string(),
            user_name: "jellybeam-user".to_string(),
            token: "tok".to_string(),
            device_id: "device-abc".to_string(),
            mirror_dir: mirror_dir_name(server_url, user_id),
            server_version: None,
            server_name: None,
        }
    }

    #[test]
    fn round_trips_through_disk_as_the_active_session() {
        let dir = tempfile::tempdir().expect("tempdir");
        let original = sample();
        add_or_update(dir.path(), original.clone()).expect("save session");
        let loaded = load(dir.path()).expect("session should load back");
        assert_eq!(loaded, original);
    }

    #[test]
    fn load_returns_none_when_no_session_exists() {
        let dir = tempfile::tempdir().expect("tempdir");
        assert!(load(dir.path()).is_none());
    }

    #[test]
    fn load_returns_none_for_malformed_sessions_json_and_no_legacy_file() {
        let dir = tempfile::tempdir().expect("tempdir");
        std::fs::write(list_path(dir.path()), b"not json").expect("write garbage");
        assert!(load(dir.path()).is_none());
    }

    #[test]
    fn remove_active_persists_an_empty_list_and_is_idempotent() {
        let dir = tempfile::tempdir().expect("tempdir");
        add_or_update(dir.path(), sample()).expect("save session");
        assert!(list_path(dir.path()).exists());

        remove_active(dir.path());
        assert!(list_path(dir.path()).exists());
        assert!(load(dir.path()).is_none());

        // Second removal with nothing there must not panic.
        remove_active(dir.path());
    }

    #[test]
    fn add_or_update_dedups_on_server_and_user_and_becomes_active() {
        let mut list = SessionList::default();
        list.add_or_update(sample_for("http://s1.test", "u1"));
        list.add_or_update(sample_for("http://s2.test", "u1"));
        assert_eq!(list.sessions.len(), 2);
        assert_eq!(list.active, 1);

        let mut updated = sample_for("http://s1.test", "u1");
        updated.token = "new-token".to_string();
        list.add_or_update(updated);
        assert_eq!(
            list.sessions.len(),
            2,
            "re-adding the same server+user must not duplicate"
        );
        assert_eq!(list.active, 0, "re-added session becomes active");
        assert_eq!(list.sessions[0].token, "new-token");
    }

    #[test]
    fn add_or_update_preserves_the_existing_mirror_dir_on_replace() {
        let mut list = SessionList::default();
        let mut first = sample_for("http://s1.test", "u1");
        first.mirror_dir = "mirror-original".to_string();
        list.add_or_update(first);

        let mut replacement = sample_for("http://s1.test", "u1");
        replacement.mirror_dir = "mirror-freshly-guessed".to_string();
        list.add_or_update(replacement);

        assert_eq!(list.sessions[0].mirror_dir, "mirror-original");
    }

    #[test]
    fn remove_active_falls_back_to_the_first_remaining_session() {
        let mut list = SessionList::default();
        list.add_or_update(sample_for("http://s1.test", "u1"));
        list.add_or_update(sample_for("http://s2.test", "u1"));
        list.add_or_update(sample_for("http://s3.test", "u1"));
        list.active = 2; // s3 active

        let removed = list.remove_active().expect("list was non-empty");
        assert_eq!(removed.server_url, "http://s3.test");
        assert_eq!(list.sessions.len(), 2);
        assert_eq!(list.active, 0, "first remaining session becomes active");
        assert_eq!(list.sessions[list.active].server_url, "http://s1.test");
    }

    #[test]
    fn remove_active_on_an_empty_list_returns_none() {
        let mut list = SessionList::default();
        assert!(list.remove_active().is_none());
    }

    #[test]
    fn remove_active_clamps_an_out_of_range_active_index() {
        let mut list = SessionList::default();
        list.add_or_update(sample_for("http://s1.test", "u1"));
        list.add_or_update(sample_for("http://s2.test", "u2"));
        list.active = 9; // e.g. a hand-edited or truncated sessions.json

        let removed = list.remove_active().expect("list was non-empty");
        assert_eq!(
            removed.server_url, "http://s2.test",
            "clamped to the last entry"
        );
        assert_eq!(list.sessions.len(), 1);
        assert_eq!(list.active, 0);
    }

    #[test]
    fn remove_at_keeps_the_same_active_account_when_an_earlier_row_is_removed() {
        let mut list = SessionList::default();
        list.add_or_update(sample_for("http://s1.test", "u1"));
        list.add_or_update(sample_for("http://s2.test", "u2"));
        list.add_or_update(sample_for("http://s3.test", "u3"));
        assert_eq!(list.active, 2);

        let removed = list.remove_at(0).expect("row exists");
        assert_eq!(removed.server_url, "http://s1.test");
        assert_eq!(list.active, 1);
        assert_eq!(
            list.active_session()
                .expect("active session exists")
                .server_url,
            "http://s3.test"
        );
    }

    #[test]
    fn remove_at_active_selects_first_remaining_and_rejects_stale_index() {
        let mut list = SessionList::default();
        list.add_or_update(sample_for("http://s1.test", "u1"));
        list.add_or_update(sample_for("http://s2.test", "u2"));
        assert!(list.remove_at(1).is_some());
        assert_eq!(list.active, 0);
        assert_eq!(
            list.active_session()
                .expect("active session exists")
                .server_url,
            "http://s1.test"
        );
        assert!(list.remove_at(9).is_none());
    }

    #[test]
    fn removing_last_migrated_account_does_not_resurrect_legacy_blob() {
        let dir = tempfile::tempdir().expect("tempdir");
        std::fs::write(
            legacy_path(dir.path()),
            serde_json::to_vec(&sample()).expect("serialize"),
        )
        .expect("write legacy");
        assert!(load(dir.path()).is_some());

        remove_active(dir.path());
        assert!(load(dir.path()).is_none());
    }

    #[test]
    fn session_file_with_server_version_round_trips() {
        let dir = tempfile::tempdir().expect("tempdir");
        let mut original = sample();
        original.server_version = Some("10.11.10".to_string());
        add_or_update(dir.path(), original.clone()).expect("save session");
        let loaded = load(dir.path()).expect("session should load back");
        assert_eq!(loaded.server_version.as_deref(), Some("10.11.10"));
        assert_eq!(loaded, original);
    }

    #[test]
    fn session_file_without_server_version_key_loads_as_none() {
        let dir = tempfile::tempdir().expect("tempdir");
        // A `sessions.json` written before `server_version` existed: no key
        // at all, not `"server_version": null`.
        let list_json = serde_json::json!({
            "sessions": [{
                "server_url": "http://server.test",
                "user_id": "u1",
                "user_name": "jellybeam-user",
                "token": "tok",
                "device_id": "dev",
                "mirror_dir": "mirror-0000000000000000",
            }],
            "active": 0,
        });
        std::fs::write(
            list_path(dir.path()),
            serde_json::to_vec(&list_json).expect("serialize fixture"),
        )
        .expect("write sessions.json");

        let loaded = load(dir.path()).expect("session should load");
        assert_eq!(loaded.server_version, None);
        assert_eq!(loaded.server_name, None);
    }

    #[test]
    fn session_file_with_server_name_round_trips() {
        let dir = tempfile::tempdir().expect("tempdir");
        let mut original = sample();
        original.server_name = Some("Living Room Server".to_string());
        add_or_update(dir.path(), original.clone()).expect("save session");
        let loaded = load(dir.path()).expect("session should load back");
        assert_eq!(loaded.server_name.as_deref(), Some("Living Room Server"));
        assert_eq!(loaded, original);
    }

    #[test]
    fn add_or_update_carries_over_the_existing_server_name_when_caller_has_none() {
        let mut list = SessionList::default();
        let mut first = sample_for("http://s1.test", "u1");
        first.server_name = Some("Living Room Server".to_string());
        list.add_or_update(first);

        // Re-authenticating builds a fresh `SessionFile` with no name known
        // yet (the refresh is fire-and-forget, started after save).
        let replacement = sample_for("http://s1.test", "u1");
        assert_eq!(replacement.server_name, None);
        list.add_or_update(replacement);

        assert_eq!(
            list.sessions[0].server_name.as_deref(),
            Some("Living Room Server"),
            "re-authenticating must not wipe the last-known server name"
        );
    }

    #[test]
    fn add_or_update_prefers_the_callers_server_name_when_it_has_one() {
        let mut list = SessionList::default();
        let mut first = sample_for("http://s1.test", "u1");
        first.server_name = Some("Living Room Server".to_string());
        list.add_or_update(first);

        let mut replacement = sample_for("http://s1.test", "u1");
        replacement.server_name = Some("Renamed Server".to_string());
        list.add_or_update(replacement);

        assert_eq!(
            list.sessions[0].server_name.as_deref(),
            Some("Renamed Server")
        );
    }

    #[test]
    fn add_or_update_carries_over_the_existing_server_version_when_caller_has_none() {
        let mut list = SessionList::default();
        let mut first = sample_for("http://s1.test", "u1");
        first.server_version = Some("10.11.10".to_string());
        list.add_or_update(first);

        // Re-authenticating builds a fresh `SessionFile` with no version
        // known yet (the refresh is fire-and-forget, started after save).
        let replacement = sample_for("http://s1.test", "u1");
        assert_eq!(replacement.server_version, None);
        list.add_or_update(replacement);

        assert_eq!(
            list.sessions[0].server_version.as_deref(),
            Some("10.11.10"),
            "re-authenticating must not wipe the last-known server version"
        );
    }

    #[test]
    fn add_or_update_prefers_the_callers_server_version_when_it_has_one() {
        let mut list = SessionList::default();
        let mut first = sample_for("http://s1.test", "u1");
        first.server_version = Some("10.11.10".to_string());
        list.add_or_update(first);

        let mut replacement = sample_for("http://s1.test", "u1");
        replacement.server_version = Some("12.0.0".to_string());
        list.add_or_update(replacement);

        assert_eq!(list.sessions[0].server_version.as_deref(), Some("12.0.0"));
    }

    #[test]
    fn mirror_dir_name_is_stable_and_distinguishes_server_and_user() {
        let a = mirror_dir_name("http://server.test", "user-1");
        let b = mirror_dir_name("http://server.test", "user-1");
        assert_eq!(a, b, "must be deterministic for the same inputs");
        assert!(a.starts_with("mirror-"));
        assert_ne!(a, legacy_mirror_dir_name());

        let different_user = mirror_dir_name("http://server.test", "user-2");
        let different_server = mirror_dir_name("http://other.test", "user-1");
        assert_ne!(a, different_user);
        assert_ne!(a, different_server);
    }

    #[test]
    fn mirror_dir_name_never_embeds_the_raw_hostname() {
        let dir = mirror_dir_name("http://server.test", "user-1");
        assert!(!dir.contains("server.test"));
    }

    #[test]
    fn load_list_migrates_a_legacy_single_session_blob_keeping_the_legacy_mirror_dir() {
        let dir = tempfile::tempdir().expect("tempdir");
        // Write the pre-multi-session shape directly: a plain `SessionFile`
        // JSON object with no `mirror_dir` key at all, at the legacy path.
        let legacy_json = serde_json::json!({
            "server_url": "http://server.test",
            "user_id": "u1",
            "user_name": "jellybeam-user",
            "token": "tok",
            "device_id": "dev",
        });
        std::fs::write(
            legacy_path(dir.path()),
            serde_json::to_vec(&legacy_json).expect("serialize legacy fixture"),
        )
        .expect("write legacy blob");

        let list = load_list(dir.path());
        assert_eq!(list.sessions.len(), 1);
        assert_eq!(list.active, 0);
        assert_eq!(list.sessions[0].server_url, "http://server.test");
        assert_eq!(
            list.sessions[0].mirror_dir,
            legacy_mirror_dir_name(),
            "migrated entry must keep using the pre-existing mirror directory"
        );

        // sessions.json now exists and is what a subsequent load reads --
        // the legacy file is left in place (rollback fallback) but never
        // consulted again once the list blob exists.
        assert!(list_path(dir.path()).exists());
        assert!(legacy_path(dir.path()).exists());

        let reloaded = load_list(dir.path());
        assert_eq!(reloaded.sessions.len(), 1);
    }

    #[test]
    fn load_list_returns_empty_when_nothing_is_stored() {
        let dir = tempfile::tempdir().expect("tempdir");
        let list = load_list(dir.path());
        assert!(list.sessions.is_empty());
    }

    /// Pins that concurrent `save_list` calls from real threads, with no
    /// external synchronization, always leave one complete, parseable file.
    #[test]
    fn concurrent_save_list_calls_never_corrupt_the_file() {
        let dir = tempfile::tempdir().expect("tempdir");
        let path = dir.path().to_path_buf();
        let handles: Vec<_> = (0..16)
            .map(|i| {
                let path = path.clone();
                std::thread::spawn(move || {
                    let list = SessionList {
                        sessions: vec![sample_for(&format!("http://s{i}.test"), "u1")],
                        active: 0,
                    };
                    save_list(&path, &list).expect("save_list from a concurrent thread");
                })
            })
            .collect();
        for handle in handles {
            handle.join().expect("writer thread panicked");
        }

        let bytes = std::fs::read(list_path(&path)).expect("sessions.json should exist");
        let parsed: SessionList =
            serde_json::from_slice(&bytes).expect("final file must be one complete, valid write");
        assert_eq!(
            parsed.sessions.len(),
            1,
            "each write is a self-consistent single-entry list"
        );
    }

    #[test]
    fn write_atomic_leaves_no_temp_file_behind() {
        let dir = tempfile::tempdir().expect("tempdir");
        add_or_update(dir.path(), sample()).expect("save session");
        for entry in std::fs::read_dir(dir.path()).expect("read dir") {
            let name = entry
                .expect("entry")
                .file_name()
                .to_string_lossy()
                .into_owned();
            assert!(!name.contains(".tmp-"), "temp file left behind: {name}");
        }
    }
}
