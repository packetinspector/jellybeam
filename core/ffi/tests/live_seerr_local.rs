//! End-to-end Seerr sign-in (all three methods) against a real (dockerized) Jellyseerr:
//! not part of the default `cargo test`. Bring the servers up with
//! `tools/dev-seerr/up.sh` (dev Jellyfin at localhost:8096, Jellyseerr at
//! localhost:5055 with the synthetic local account it creates).
//!
//! Run explicitly:
//!   cargo test -p jellybeam-ffi -- --ignored live_seerr
//! `JELLYBEAM_JELLYFIN_URL` / `JELLYBEAM_SEERR_URL` / `JELLYBEAM_SEERR_API_KEY` point the run at
//! another pair, e.g. the Seerr 3.x instance the script's header comment brings up.

use jellybeam_core::{CoreError, JellybeamCore, SeerrAuthMethod};

const DEFAULT_JELLYFIN_URL: &str = "http://localhost:8096";
const DEFAULT_SEERR_URL: &str = "http://localhost:5055";
const USERNAME: &str = "jellybeam-user";
const PASSWORD: &str = "jellybeam-test";
const SEERR_LOCAL_EMAIL: &str = "local@example.test";
const SEERR_LOCAL_USERNAME: &str = "local-user";
const SEERR_ADMIN_USERNAME: &str = "jellybeam-admin";

/// The dev instance's admin API key, written by `tools/dev-seerr/up.sh` into an ignored path.
fn dev_api_key() -> String {
    if let Ok(key) = std::env::var("JELLYBEAM_SEERR_API_KEY") {
        return key;
    }
    let path = concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/../../internal/dev-seerr-config/api-key"
    );
    std::fs::read_to_string(path)
        .unwrap_or_else(|e| panic!("dev Seerr API key: run tools/dev-seerr/up.sh ({e})"))
        .trim()
        .to_string()
}

fn jellyfin_url() -> String {
    std::env::var("JELLYBEAM_JELLYFIN_URL").unwrap_or_else(|_| DEFAULT_JELLYFIN_URL.to_string())
}

fn seerr_url() -> String {
    std::env::var("JELLYBEAM_SEERR_URL").unwrap_or_else(|_| DEFAULT_SEERR_URL.to_string())
}

fn signed_in_core() -> (tempfile::TempDir, std::sync::Arc<JellybeamCore>) {
    let dir = tempfile::tempdir().expect("tempdir");
    let core = JellybeamCore::new(dir.path().to_string_lossy().to_string());
    let jellyfin_url = jellyfin_url();
    core.sign_in(
        jellyfin_url.clone(),
        USERNAME.to_string(),
        PASSWORD.to_string(),
    )
    .unwrap_or_else(|e| panic!("sign in against dev server {jellyfin_url}: {e}"));
    (dir, core)
}

#[test]
#[ignore = "requires the dev Jellyfin at localhost:8096 and Jellyseerr at localhost:5055 (tools/dev-seerr/up.sh)"]
fn live_seerr_local_login_with_email_connects() {
    let (_dir, core) = signed_in_core();
    let status = core
        .seerr_connect(
            seerr_url(),
            SeerrAuthMethod::Local,
            SEERR_LOCAL_EMAIL.to_string(),
            PASSWORD.to_string(),
        )
        .unwrap_or_else(|e| panic!("seerr local login: {e}"));
    assert!(status.configured);
    assert_eq!(status.method, Some(SeerrAuthMethod::Local));
    assert_eq!(status.identity.as_deref(), Some(SEERR_LOCAL_EMAIL));
    assert!(core.seerr_status().configured);
    core.seerr_disconnect();
    assert!(!core.seerr_status().configured);
}

#[test]
#[ignore = "requires the dev Jellyfin at localhost:8096 and Jellyseerr at localhost:5055 (tools/dev-seerr/up.sh)"]
fn live_seerr_local_login_with_username_is_a_credentials_error() {
    let (_dir, core) = signed_in_core();
    let err = core
        .seerr_connect(
            seerr_url(),
            SeerrAuthMethod::Local,
            SEERR_LOCAL_USERNAME.to_string(),
            PASSWORD.to_string(),
        )
        .expect_err("a Seerr local account only signs in with its email address");
    assert!(
        matches!(
            err,
            CoreError::SeerrInvalidCredentials {
                method: SeerrAuthMethod::Local
            }
        ),
        "{err:?}"
    );
    assert!(!core.seerr_status().configured);
}

#[test]
#[ignore = "requires the dev Jellyfin at localhost:8096 and Jellyseerr at localhost:5055 (tools/dev-seerr/up.sh)"]
fn live_seerr_api_key_connects_and_a_bad_key_is_a_credentials_error() {
    let (_dir, core) = signed_in_core();
    let status = core
        .seerr_connect(
            seerr_url(),
            SeerrAuthMethod::ApiKey,
            "dev key".to_string(),
            dev_api_key(),
        )
        .unwrap_or_else(|e| panic!("seerr api-key login: {e}"));
    assert!(status.configured);
    assert_eq!(status.method, Some(SeerrAuthMethod::ApiKey));
    assert!(
        core.seerr_home().is_ok(),
        "an API-key handle must serve Discover"
    );
    core.seerr_disconnect();

    let err = core
        .seerr_connect(
            seerr_url(),
            SeerrAuthMethod::ApiKey,
            String::new(),
            "not-a-real-key".to_string(),
        )
        .expect_err("a rejected key must not connect");
    assert!(
        matches!(
            err,
            CoreError::SeerrInvalidCredentials {
                method: SeerrAuthMethod::ApiKey
            }
        ),
        "{err:?}"
    );
    assert!(!core.seerr_status().configured);
}

#[test]
#[ignore = "requires the dev Jellyfin at localhost:8096 and Jellyseerr at localhost:5055 (tools/dev-seerr/up.sh)"]
fn live_seerr_jellyfin_login_connects_and_a_wrong_password_is_a_credentials_error() {
    let (_dir, core) = signed_in_core();
    let status = core
        .seerr_connect(
            seerr_url(),
            SeerrAuthMethod::Jellyfin,
            SEERR_ADMIN_USERNAME.to_string(),
            PASSWORD.to_string(),
        )
        .unwrap_or_else(|e| panic!("seerr jellyfin login: {e}"));
    assert!(status.configured);
    assert_eq!(status.method, Some(SeerrAuthMethod::Jellyfin));
    core.seerr_disconnect();

    let err = core
        .seerr_connect(
            seerr_url(),
            SeerrAuthMethod::Jellyfin,
            SEERR_ADMIN_USERNAME.to_string(),
            "wrong-password".to_string(),
        )
        .expect_err("a wrong password must not connect");
    assert!(
        matches!(
            err,
            CoreError::SeerrInvalidCredentials {
                method: SeerrAuthMethod::Jellyfin
            }
        ),
        "{err:?}"
    );
}
