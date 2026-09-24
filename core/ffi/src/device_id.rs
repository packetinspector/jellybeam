//! Stable per-install device id, persisted as a UUID v4 at `<data_dir>/device-id`
//! so the server sees a consistent `DeviceId` across restarts. Accounts never present it
//! directly: each signs in with [`for_server`]'s per-address derivation. Persist failures are
//! logged and swallowed, not propagated: [`crate::JellybeamCore::new`] is infallible,
//! and a lost id just regenerates next launch (cosmetic, not a correctness issue).

const FILE_NAME: &str = "device-id";

/// Load the persisted device id from `data_dir`, or generate and persist a
/// fresh one if none exists yet. Only a well-formed UUID is accepted, so a
/// truncated file never becomes a second identity; the write is atomic.
pub(crate) fn load_or_create(data_dir: &std::path::Path) -> String {
    let path = data_dir.join(FILE_NAME);
    if let Ok(existing) = std::fs::read_to_string(&path) {
        if let Ok(id) = uuid::Uuid::parse_str(existing.trim()) {
            return id.to_string();
        }
    }

    let id = uuid::Uuid::new_v4().to_string();
    if let Err(e) = crate::persistence::save_bytes(&path, id.as_bytes()) {
        tracing::warn!(
            error = %e,
            path = %path.display(),
            "failed to persist device id; using an in-memory id for this run"
        );
    }
    id
}

/// The `DeviceId` an account at `server_url` signs in with. Jellyfin logs out a user's
/// existing session when the same `DeviceId` authenticates again, and a multi-server proxy
/// forwards the client's id to its backends -- so one id shared by a direct account and a proxy
/// account over the same server makes each sign-in revoke the other's token. Stable only within
/// a Rust release (`DefaultHasher`); the result is persisted on the session, never re-derived
/// for a saved token.
pub(crate) fn for_server(install_id: &str, server_url: &str) -> String {
    use std::hash::{Hash, Hasher};
    let address = crate::signin::normalize_sign_in_url(server_url)
        .unwrap_or_else(|_| server_url.trim().to_string());
    let address = address.trim_end_matches('/').to_ascii_lowercase();
    let half = |salt: u8| {
        let mut hasher = std::collections::hash_map::DefaultHasher::new();
        salt.hash(&mut hasher);
        install_id.hash(&mut hasher);
        0u8.hash(&mut hasher);
        address.hash(&mut hasher);
        hasher.finish()
    };
    uuid::Uuid::from_u64_pair(half(1), half(2)).to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn for_server_differs_per_address_and_install_but_not_per_spelling() {
        let install = "e2f5a5f1-1a0b-4b3a-9c2e-000000000001";
        let direct = for_server(install, "http://media.example.test:8096");
        let proxy = for_server(install, "http://media.example.test:3000");

        assert_ne!(direct, proxy);
        assert_ne!(direct, install);
        assert!(uuid::Uuid::parse_str(&direct).is_ok());
        assert_eq!(
            direct,
            for_server(install, " http://MEDIA.example.test:8096/ ")
        );
        assert_eq!(direct, for_server(install, "media.example.test:8096"));
        assert_ne!(
            direct,
            for_server(
                "e2f5a5f1-1a0b-4b3a-9c2e-000000000002",
                "http://media.example.test:8096"
            )
        );
    }

    #[test]
    fn creates_and_persists_a_new_id_when_none_exists() {
        let dir = tempfile::tempdir().expect("tempdir");
        let id = load_or_create(dir.path());
        assert!(!id.is_empty());
        assert!(
            uuid::Uuid::parse_str(&id).is_ok(),
            "device id should be a valid UUID: {id:?}"
        );

        let on_disk =
            std::fs::read_to_string(dir.path().join(FILE_NAME)).expect("device-id file exists");
        assert_eq!(on_disk.trim(), id);
    }

    #[test]
    fn round_trips_the_same_id_across_calls() {
        let dir = tempfile::tempdir().expect("tempdir");
        let first = load_or_create(dir.path());
        let second = load_or_create(dir.path());
        assert_eq!(first, second);
    }

    #[test]
    fn tolerates_a_pre_existing_id_file() {
        let dir = tempfile::tempdir().expect("tempdir");
        let known = "e2f5a5f1-1a0b-4b3a-9c2e-000000000001";
        std::fs::write(dir.path().join(FILE_NAME), known).expect("write seed id");
        assert_eq!(load_or_create(dir.path()), known);
    }

    #[test]
    fn a_truncated_id_file_is_replaced_rather_than_adopted() {
        let dir = tempfile::tempdir().expect("tempdir");
        let path = dir.path().join(FILE_NAME);
        std::fs::write(&path, "e2f5a5f1-1a0b-4b3a").expect("write truncated id");
        let id = load_or_create(dir.path());
        assert!(uuid::Uuid::parse_str(&id).is_ok(), "regenerated id: {id:?}");
        assert_eq!(std::fs::read_to_string(&path).expect("read"), id);
        assert_eq!(load_or_create(dir.path()), id);
    }

    #[test]
    fn creating_an_id_leaves_only_the_id_file_behind() {
        let dir = tempfile::tempdir().expect("tempdir");
        load_or_create(dir.path());
        assert_eq!(std::fs::read_dir(dir.path()).expect("entries").count(), 1);
    }
}
