//! On-disk session persistence.
//!
//! Multi-server/user support: every signed-in `(server, user)` pair is kept
//! in one list, `SessionList { sessions, active }`, persisted at
//! `<data_dir>/sessions.json`; `SessionFile` also carries `mirror_dir` --
//! see [`mirror_dir_name`] for why every session gets its own.
//!
//! **Migration**: a pre-list install (a lone `session.json`) reads as a one-account list,
//! pinned to the legacy `"mirror"` directory via [`legacy_mirror_dir_name`]; the first
//! writer ([`mutate`]) saves it as `sessions.json` and the legacy file stays as a rollback
//! fallback. Readers ([`load_list`], [`load`]) never write (docs/18 §2.1).
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

impl SessionFile {
    /// docs/18 §2.1: whether this row is the account `(server_url, user_id)` names; a trailing
    /// slash on either address is not a different server.
    pub(crate) fn is_account(&self, server_url: &str, user_id: &str) -> bool {
        same_server(&self.server_url, server_url) && self.user_id == user_id
    }
}

/// Whether two saved or live server addresses name one server (they differ only by trailing
/// slashes); the one compare every account lookup uses.
pub(crate) fn same_server(a: &str, b: &str) -> bool {
    a.trim_end_matches('/') == b.trim_end_matches('/')
}

/// Every signed-in `(server, user)` session plus which one is currently
/// active. `active` is a plain index, kept in range by
/// [`SessionList::add_or_update`]/[`SessionList::remove_at`] so callers never re-clamp it themselves.
#[derive(Debug, Clone, Default, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
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
    /// carry-over, each only when the caller left it `None`. The address is stored without a
    /// trailing `/`, so the one writer saves one spelling (docs/18 §2.1).
    pub(crate) fn add_or_update(&mut self, mut session: SessionFile) {
        session.server_url = session.server_url.trim_end_matches('/').to_string();
        if let Some(ix) = self
            .sessions
            .iter()
            .position(|s| s.is_account(&session.server_url, &session.user_id))
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

/// What `sessions.json` holds right now; the one classification every reader
/// and writer goes through (docs/18 §2.1: a saved account is never silently lost).
enum StoredList {
    Missing,
    Unreadable(std::io::Error),
    /// Unparseable; carries the byte length for the quarantine log.
    Corrupt(usize),
    Ok(SessionList),
}

fn read_stored(data_dir: &std::path::Path) -> StoredList {
    match std::fs::read(list_path(data_dir)) {
        Ok(bytes) => match serde_json::from_slice::<SessionList>(&bytes) {
            Ok(list) => StoredList::Ok(list),
            Err(_) => StoredList::Corrupt(bytes.len()),
        },
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => StoredList::Missing,
        Err(e) => StoredList::Unreadable(e),
    }
}

/// Renames an unparseable `sessions.json` to `sessions.json.corrupt-<unix_ms>`
/// so no write can replace its bytes (docs/18 §2.1).
fn quarantine_corrupt(data_dir: &std::path::Path, bytes_len: usize) -> std::io::Result<()> {
    let ms = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_or(0, |d| d.as_millis());
    let aside = data_dir.join(format!("sessions.json.corrupt-{ms}"));
    std::fs::rename(list_path(data_dir), aside)?;
    tracing::warn!(
        bytes_len,
        "sessions.json failed to parse; kept aside as sessions.json.corrupt-*"
    );
    Ok(())
}

/// The lone legacy `session.json` as a one-account list, if any; reads only.
fn legacy_list(data_dir: &std::path::Path) -> Option<SessionList> {
    let mut legacy = load_legacy(data_dir)?;
    // The legacy blob predates `mirror_dir`; serde's default already filled
    // it, but pin it explicitly in case a newer-format blob sits at the
    // legacy path.
    legacy.mirror_dir = legacy_mirror_dir_name();
    Some(SessionList {
        sessions: vec![legacy],
        active: 0,
    })
}

/// docs/18 §2.1: saved addresses carry no trailing slash and `active` is in range; the flag says
/// the stored bytes differ, so a writer saves it.
fn canonical(mut list: SessionList) -> (SessionList, bool) {
    let before = list.clone();
    list.active = list.active.min(list.sessions.len().saturating_sub(1));
    for row in &mut list.sessions {
        row.server_url = row.server_url.trim_end_matches('/').to_string();
    }
    let changed = list != before;
    (list, changed)
}

/// The session list as a reader sees it: `sessions.json`, else the legacy `session.json` as a
/// one-account list, else empty. Never writes -- an unparseable `sessions.json` is left for the next
/// writer to set aside -- and an unreadable one reads as empty; [`mutate`] refuses it (docs/18 §2.1).
pub(crate) fn load_list(data_dir: &std::path::Path) -> SessionList {
    match read_stored(data_dir) {
        StoredList::Ok(list) => canonical(list).0,
        StoredList::Unreadable(e) => {
            tracing::warn!(error = %e, "sessions.json could not be read; treating as empty");
            SessionList::default()
        }
        StoredList::Corrupt(_) | StoredList::Missing => {
            legacy_list(data_dir).map_or_else(SessionList::default, |list| canonical(list).0)
        }
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

/// Why [`mutate`] did not commit.
#[derive(Debug)]
pub(crate) enum MutateError<E> {
    /// The list could not be read or the result could not be written.
    Storage(std::io::Error),
    /// The caller's closure refused; nothing was written.
    Refused(E),
}

impl From<MutateError<crate::error::CoreError>> for crate::error::CoreError {
    fn from(err: MutateError<crate::error::CoreError>) -> Self {
        match err {
            MutateError::Storage(e) => Self::Cache {
                detail: format!("failed to update the session list: {e}"),
            },
            MutateError::Refused(e) => e,
        }
    }
}

/// The one writer of `sessions.json` (docs/18 §2.1): under `lock` it sets an unparseable list
/// aside, migrates a lone legacy `session.json`, applies `change`, and saves only a changed list;
/// an unreadable list or a refusal from `change` saves none.
pub(crate) fn mutate<T, E>(
    data_dir: &std::path::Path,
    lock: &std::sync::Mutex<()>,
    change: impl FnOnce(&mut SessionList) -> Result<T, E>,
) -> Result<T, MutateError<E>> {
    let _guard = lock
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let (mut list, migrated) = open_for_write(data_dir).map_err(MutateError::Storage)?;
    let before = list.clone();
    let value = change(&mut list).map_err(MutateError::Refused)?;
    if migrated || list != before {
        save_list(data_dir, &list).map_err(MutateError::Storage)?;
    }
    Ok(value)
}

/// The list a writer starts from, and whether it came from the legacy file (so must be saved).
fn open_for_write(data_dir: &std::path::Path) -> std::io::Result<(SessionList, bool)> {
    match read_stored(data_dir) {
        StoredList::Ok(list) => return Ok(canonical(list)),
        StoredList::Unreadable(e) => return Err(e),
        StoredList::Corrupt(len) => quarantine_corrupt(data_dir, len)?,
        StoredList::Missing => {}
    }
    Ok(legacy_list(data_dir).map_or_else(
        || (SessionList::default(), false),
        |list| (canonical(list).0, true),
    ))
}

/// Insert-or-replace `session` into the on-disk list and make it active,
/// returning the finalized entry actually persisted -- `add_or_update`
/// may have overwritten the caller's guessed `mirror_dir` with an
/// existing entry's real one. Refuses (`Err`, nothing written) when
/// `sessions.json` is unreadable.
#[cfg(test)]
pub(crate) fn add_or_update(
    data_dir: &std::path::Path,
    session: SessionFile,
) -> std::io::Result<SessionFile> {
    mutate(data_dir, &std::sync::Mutex::new(()), |list| {
        list.add_or_update(session);
        Ok::<_, std::convert::Infallible>(
            list.active_session()
                .cloned()
                .expect("add_or_update always leaves `active` pointing at the just-inserted entry"),
        )
    })
    .map_err(|e| match e {
        MutateError::Storage(e) => e,
        MutateError::Refused(never) => match never {},
    })
}

/// Test-only: removes the active session, writing even an empty list (deleting the file would
/// resurrect a legacy `session.json`); `None` on an empty or unreadable list.
#[cfg(test)]
pub(crate) fn remove_active(data_dir: &std::path::Path) -> Option<SessionFile> {
    mutate(data_dir, &std::sync::Mutex::new(()), |list| {
        let active = list.active;
        list.remove_at(active).ok_or(())
    })
    .ok()
}

/// Test-only: leaves `session` as the lone legacy `session.json`.
#[cfg(test)]
pub(crate) fn write_legacy(data_dir: &std::path::Path, session: &SessionFile) {
    std::fs::write(
        legacy_path(data_dir),
        serde_json::to_vec(session).expect("serialize"),
    )
    .expect("write legacy");
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
    fn a_trailing_slash_is_the_same_account() {
        let mut list = SessionList::default();
        list.add_or_update(sample_for("http://s1.test/jf/", "u1"));
        list.add_or_update(sample_for("http://s1.test/jf", "u1"));
        assert_eq!(list.sessions.len(), 1);
    }

    fn stored(dir: &std::path::Path, sessions: Vec<SessionFile>, active: usize) {
        save_list(dir, &SessionList { sessions, active }).expect("save list");
    }

    #[test]
    fn readers_and_the_writer_see_identical_rows_from_every_source() {
        for url in ["http://h/jf/", "http://h/jf"] {
            for source in ["legacy", "legacy-behind-corrupt-list", "list"] {
                let dir = tempfile::tempdir().expect("tempdir");
                match source {
                    "legacy" => write_legacy(dir.path(), &sample_for(url, "u1")),
                    "legacy-behind-corrupt-list" => {
                        write_legacy(dir.path(), &sample_for(url, "u1"));
                        std::fs::write(list_path(dir.path()), b"not json").expect("corrupt");
                    }
                    _ => stored(dir.path(), vec![sample_for(url, "u1")], 0),
                }
                let read = load_list(dir.path());
                let (write, _) = open_for_write(dir.path()).expect("open");
                assert_eq!(read, write, "{source} {url}");
                assert_eq!(read.sessions[0].server_url, "http://h/jf", "{source} {url}");
            }
        }
    }

    #[test]
    fn add_or_update_stores_the_trimmed_address() {
        let dir = tempfile::tempdir().expect("tempdir");
        let saved = add_or_update(dir.path(), sample_for("http://h/jf/", "u1")).expect("add");
        assert_eq!(saved.server_url, "http://h/jf");
        let raw: SessionList =
            serde_json::from_slice(&std::fs::read(list_path(dir.path())).expect("bytes"))
                .expect("parse");
        assert_eq!(raw.sessions[0].server_url, "http://h/jf");
    }

    #[test]
    fn a_slashed_row_reads_trimmed_and_the_read_writes_nothing() {
        let dir = tempfile::tempdir().expect("tempdir");
        stored(dir.path(), vec![sample_for("http://h/jf/", "u1")], 0);
        let before = std::fs::read(list_path(dir.path())).expect("bytes");

        let list = load_list(dir.path());
        assert_eq!(list.sessions[0].server_url, "http://h/jf");
        assert_eq!(std::fs::read(list_path(dir.path())).expect("bytes"), before);
    }

    #[test]
    fn the_next_write_persists_the_canonical_list() {
        let dir = tempfile::tempdir().expect("tempdir");
        stored(dir.path(), vec![sample_for("http://h/jf/", "u1")], 0);

        mutate(dir.path(), &std::sync::Mutex::new(()), |_| Ok::<_, ()>(())).expect("no-op write");
        let raw: SessionList =
            serde_json::from_slice(&std::fs::read(list_path(dir.path())).expect("bytes"))
                .expect("parse");
        assert_eq!(raw.sessions.len(), 1);
        assert_eq!(raw.sessions[0].server_url, "http://h/jf");
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

        // A reader only views the migration: it is saved by the first writer, and the
        // legacy file stays in place (rollback fallback).
        assert!(!list_path(dir.path()).exists());
        assert!(legacy_path(dir.path()).exists());
        assert_eq!(load_list(dir.path()).sessions.len(), 1);

        add_or_update(dir.path(), sample_for("http://new.test", "u9")).expect("first writer");
        assert!(list_path(dir.path()).exists());
        assert_eq!(load_list(dir.path()).sessions.len(), 2);
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

    // --- saved accounts are never silently lost (docs/18 §2.1) ---

    const GARBAGE: &[u8] = b"\x00\xff\xfenot json {{";
    const WRONG_SHAPE: &[u8] = br#"{"accounts":[{"server":"x"}]}"#;

    #[derive(Debug, Clone, Copy)]
    enum Op {
        Load,
        AddOrUpdate,
        RemoveActive,
    }

    /// Runs `op`; a refused write surfaces as `Err`, an accepted add as the saved entry.
    fn run(op: Op, dir: &std::path::Path) -> std::io::Result<Option<SessionFile>> {
        match op {
            Op::Load => {
                let _ = load_list(dir);
                Ok(None)
            }
            Op::AddOrUpdate => add_or_update(dir, sample_for("http://new.test", "u9")).map(Some),
            Op::RemoveActive => {
                remove_active(dir);
                Ok(None)
            }
        }
    }

    fn write_legacy_sample(dir: &std::path::Path) {
        write_legacy(dir, &sample_for("http://legacy.test", "u1"));
    }

    /// True when some regular file in `dir` still holds exactly `original`.
    fn original_bytes_survive(dir: &std::path::Path, original: &[u8]) -> bool {
        std::fs::read_dir(dir)
            .expect("read dir")
            .flatten()
            .any(|entry| std::fs::read(entry.path()).is_ok_and(|bytes| bytes == original))
    }

    /// Runs `op` over every corrupt `sessions.json` shape and reports each case that lost the bytes.
    fn corrupt_list_failures(op: Op, with_legacy: bool) -> Vec<String> {
        let mut failures = Vec::new();
        for (label, original) in [("garbage", GARBAGE), ("wrong shape", WRONG_SHAPE)] {
            let dir = tempfile::tempdir().expect("tempdir");
            std::fs::write(list_path(dir.path()), original).expect("write sessions.json");
            if with_legacy {
                write_legacy_sample(dir.path());
            }
            let outcome = run(op, dir.path());
            if !original_bytes_survive(dir.path(), original) {
                failures.push(format!("{label}: original bytes gone after {op:?}"));
                continue;
            }
            if outcome.is_err()
                && std::fs::read(list_path(dir.path())).ok().as_deref() != Some(original)
            {
                failures.push(format!("{label}: {op:?} refused but changed sessions.json"));
            }
            if let Ok(Some(saved)) = outcome {
                if load(dir.path()) != Some(saved) {
                    failures.push(format!(
                        "{label}: {op:?} reported success but did not persist"
                    ));
                }
            }
        }
        failures
    }

    fn assert_corrupt_list_survives(op: Op, with_legacy: bool) {
        let failures = corrupt_list_failures(op, with_legacy);
        assert!(failures.is_empty(), "{}", failures.join("\n"));
    }

    #[test]
    fn add_or_update_keeps_the_bytes_of_an_unparseable_sessions_json() {
        assert_corrupt_list_survives(Op::AddOrUpdate, false);
    }

    #[test]
    fn remove_active_keeps_the_bytes_of_an_unparseable_sessions_json() {
        assert_corrupt_list_survives(Op::RemoveActive, false);
    }

    #[test]
    fn load_keeps_the_bytes_of_an_unparseable_sessions_json() {
        assert_corrupt_list_survives(Op::Load, false);
    }

    #[test]
    fn add_or_update_keeps_an_unparseable_sessions_json_even_with_a_legacy_file() {
        assert_corrupt_list_survives(Op::AddOrUpdate, true);
    }

    #[test]
    fn remove_active_keeps_an_unparseable_sessions_json_even_with_a_legacy_file() {
        assert_corrupt_list_survives(Op::RemoveActive, true);
    }

    #[test]
    fn load_keeps_an_unparseable_sessions_json_even_with_a_legacy_file() {
        assert_corrupt_list_survives(Op::Load, true);
    }

    #[cfg(unix)]
    fn assert_unreadable_list_survives(op: Op) {
        let dir = tempfile::tempdir().expect("tempdir");
        std::fs::write(list_path(dir.path()), GARBAGE).expect("write sessions.json");
        let Some(unreadable) =
            crate::persistence::ChmodGuard::unreadable_file(&list_path(dir.path()))
        else {
            return;
        };
        let _ = run(op, dir.path());
        drop(unreadable);
        assert!(
            original_bytes_survive(dir.path(), GARBAGE),
            "{op:?} replaced an unreadable sessions.json"
        );
    }

    #[cfg(unix)]
    #[test]
    fn add_or_update_never_replaces_an_unreadable_sessions_json() {
        assert_unreadable_list_survives(Op::AddOrUpdate);
    }

    #[cfg(unix)]
    #[test]
    fn remove_active_never_replaces_an_unreadable_sessions_json() {
        assert_unreadable_list_survives(Op::RemoveActive);
    }

    #[cfg(unix)]
    #[test]
    fn load_never_replaces_an_unreadable_sessions_json() {
        assert_unreadable_list_survives(Op::Load);
    }

    #[test]
    fn add_or_update_fails_and_leaves_a_directory_named_sessions_json_in_place() {
        let dir = tempfile::tempdir().expect("tempdir");
        std::fs::create_dir(list_path(dir.path())).expect("mkdir sessions.json");
        let outcome = add_or_update(dir.path(), sample());
        assert!(outcome.is_err(), "writer must refuse, got {outcome:?}");
        assert!(list_path(dir.path()).is_dir());
    }

    #[test]
    fn remove_active_and_load_leave_a_directory_named_sessions_json_in_place() {
        for op in [Op::RemoveActive, Op::Load] {
            let dir = tempfile::tempdir().expect("tempdir");
            std::fs::create_dir(list_path(dir.path())).expect("mkdir sessions.json");
            let _ = run(op, dir.path());
            assert!(
                list_path(dir.path()).is_dir(),
                "{op:?} replaced the directory"
            );
        }
    }

    fn write_two_accounts_with_active(dir: &std::path::Path, active: usize) {
        let list = SessionList {
            sessions: vec![
                sample_for("http://s1.test", "u1"),
                sample_for("http://s2.test", "u2"),
            ],
            active,
        };
        std::fs::write(
            list_path(dir),
            serde_json::to_vec(&list).expect("serialize"),
        )
        .expect("write sessions.json");
    }

    #[test]
    fn load_clamps_an_out_of_range_active_index_without_losing_an_account() {
        let dir = tempfile::tempdir().expect("tempdir");
        write_two_accounts_with_active(dir.path(), 9);
        let list = load_list(dir.path());
        assert_eq!(list.sessions.len(), 2);
        assert!(
            list.active < list.sessions.len(),
            "active left out of range"
        );
        assert!(
            load(dir.path()).is_some(),
            "an account exists but none is active"
        );
    }

    #[test]
    fn add_or_update_with_an_out_of_range_active_index_keeps_every_saved_account() {
        let dir = tempfile::tempdir().expect("tempdir");
        write_two_accounts_with_active(dir.path(), 9);
        add_or_update(dir.path(), sample_for("http://s3.test", "u3")).expect("save");
        let list = load_list(dir.path());
        assert_eq!(list.sessions.len(), 3);
        assert_eq!(
            list.active_session().expect("active").server_url,
            "http://s3.test"
        );
    }

    #[test]
    fn remove_active_with_an_out_of_range_active_index_keeps_the_other_account() {
        let dir = tempfile::tempdir().expect("tempdir");
        write_two_accounts_with_active(dir.path(), 9);
        remove_active(dir.path());
        assert_eq!(load_list(dir.path()).sessions.len(), 1);
    }

    #[test]
    fn add_or_update_creates_sessions_json_when_none_exists() {
        let dir = tempfile::tempdir().expect("tempdir");
        add_or_update(dir.path(), sample()).expect("save");
        assert!(list_path(dir.path()).exists());
    }

    #[test]
    fn remove_active_writes_nothing_when_no_sessions_json_exists() {
        let dir = tempfile::tempdir().expect("tempdir");
        remove_active(dir.path());
        assert!(!list_path(dir.path()).exists());
    }

    #[test]
    fn add_or_update_on_a_legacy_only_install_keeps_the_migrated_account() {
        let dir = tempfile::tempdir().expect("tempdir");
        write_legacy_sample(dir.path());
        add_or_update(dir.path(), sample_for("http://new.test", "u9")).expect("save");
        let list = load_list(dir.path());
        assert_eq!(list.sessions.len(), 2);
        assert_eq!(list.sessions[0].server_url, "http://legacy.test");
    }
}
