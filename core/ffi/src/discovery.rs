//! LAN server autodetection FFI surface (Feature B,
//! `docs/feature-dev/spec-still-watching-and-lan-discovery.md`): a thin
//! `#[uniffi::export] impl JellybeamCore` block over `jellyfin_api::discovery`,
//! plus marking each hit `already_saved` against `crate::session`.
//!
//! Only called from Sign In / Add Server, never at cold start; blocking and
//! coarse (~1.5s worst case: discovery's window plus one resend).

use crate::object::JellybeamCore;

/// One LAN-discovered server, plus whether it matches an already-saved
/// account. Thin `uniffi::Record` skin over `jellyfin_api::discovery::DiscoveredServer`.
#[derive(uniffi::Record, Debug, Clone, PartialEq, Eq)]
pub struct DiscoveredServer {
    pub address: String,
    pub id: String,
    pub name: String,
    pub already_saved: bool,
}

/// Normalizes a server URL for `already_saved` comparison: trims whitespace
/// and a trailing slash, lowercases scheme and host (path case is preserved
/// -- server-configured paths are case sensitive), and drops an explicit
/// default port (`:80`/`:443`). `sessions.json` stores no server id, so
/// matching is address-only; an accepted trade-off since this only gates a
/// "Saved" label, never routing or auth.
pub(crate) fn normalize_server_url(url: &str) -> String {
    let trimmed = url.trim().trim_end_matches('/');
    let (scheme, after_scheme) = match trimmed.split_once("://") {
        Some((scheme, rest)) => (scheme.to_ascii_lowercase(), rest),
        None => (String::new(), trimmed),
    };
    let (authority, path) = match after_scheme.split_once('/') {
        Some((authority, path)) => (authority, Some(path)),
        None => (after_scheme, None),
    };
    let mut authority = authority.to_ascii_lowercase();
    let default_port_suffix = match scheme.as_str() {
        "http" => Some(":80"),
        "https" => Some(":443"),
        _ => None,
    };
    if let Some(suffix) = default_port_suffix {
        if let Some(stripped) = authority.strip_suffix(suffix) {
            authority = stripped.to_string();
        }
    }

    let mut normalized = if scheme.is_empty() {
        authority
    } else {
        format!("{scheme}://{authority}")
    };
    if let Some(path) = path {
        normalized.push('/');
        normalized.push_str(path);
    }
    normalized
}

/// A server whose published URL omits its port advertises a dead address (a real deployment hit
/// this). Each hit's advertised address is probed with `GET /System/Info/Public`; on failure the
/// [`jellyfin_api::discovery::fallback_candidates`] are probed concurrently and the first reachable
/// one, in candidate order, replaces it. Fail-open: nothing reachable leaves the address as
/// advertised.
async fn resolve_reachable_addresses(
    found: Vec<jellyfin_api::discovery::DiscoveredServer>,
) -> Vec<jellyfin_api::discovery::DiscoveredServer> {
    let mut resolved = Vec::with_capacity(found.len());
    for mut server in found {
        if probe(&server.address).await {
            resolved.push(server);
            continue;
        }
        let candidates =
            jellyfin_api::discovery::fallback_candidates(&server.address, server.responder_ip);
        let results = futures_util::future::join_all(candidates.iter().map(|c| probe(c))).await;
        if let Some(address) = first_reachable(&candidates, &results) {
            server.address = address.to_string();
        }
        resolved.push(server);
    }
    resolved
}

/// The first candidate, in order, whose probe succeeded.
fn first_reachable<'a>(candidates: &'a [String], reachable: &[bool]) -> Option<&'a str> {
    candidates
        .iter()
        .zip(reachable)
        .find_map(|(candidate, ok)| ok.then_some(candidate.as_str()))
}

/// Bound on one probe; the discovery window itself is 1.5 s, so a dead address must not stall the
/// list for reqwest's full request timeout.
const PROBE_TIMEOUT: std::time::Duration = std::time::Duration::from_millis(2500);

async fn probe(base_url: &str) -> bool {
    let identity = JellybeamCore::identity_with(String::new());
    matches!(
        tokio::time::timeout(
            PROBE_TIMEOUT,
            jellyfin_api::JellyfinClient::public_system_info(base_url, &identity)
        )
        .await,
        Ok(Ok(_))
    )
}

/// Stamps `already_saved` on each discovered server by comparing its
/// normalized address against `saved_urls` (normalizes both sides).
pub(crate) fn mark_already_saved(
    found: Vec<jellyfin_api::discovery::DiscoveredServer>,
    saved_urls: &[String],
) -> Vec<DiscoveredServer> {
    let normalized_saved: Vec<String> =
        saved_urls.iter().map(|u| normalize_server_url(u)).collect();
    found
        .into_iter()
        .map(|server| {
            let already_saved = normalized_saved.contains(&normalize_server_url(&server.address));
            DiscoveredServer {
                address: server.address,
                id: server.id,
                name: server.name,
                already_saved,
            }
        })
        .collect()
}

#[uniffi::export]
impl JellybeamCore {
    /// Broadcasts Jellyfin's UDP discovery (1.5s window, one resend at
    /// 500ms, capped at 15 -- per `docs/feature-dev/spec-still-watching-and-lan-discovery.md`),
    /// marking each hit `already_saved`. Fail-open: an unreachable network
    /// yields an empty list, never an error.
    pub fn discover_servers(&self) -> Vec<DiscoveredServer> {
        let found = self.runtime().block_on(async {
            let found = jellyfin_api::discovery::discover_local_servers(1500, 15).await;
            resolve_reachable_addresses(found).await
        });
        let saved_urls: Vec<String> = crate::session::load_list(self.data_dir())
            .sessions
            .into_iter()
            .map(|session| session.server_url)
            .collect();
        mark_already_saved(found, &saved_urls)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn server(address: &str, id: &str, name: &str) -> jellyfin_api::discovery::DiscoveredServer {
        jellyfin_api::discovery::DiscoveredServer {
            responder_ip: None,
            address: address.to_string(),
            id: id.to_string(),
            name: name.to_string(),
        }
    }

    #[test]
    fn normalize_trims_trailing_slash() {
        assert_eq!(
            normalize_server_url("http://jellyfin.test:8096/"),
            "http://jellyfin.test:8096"
        );
    }

    #[test]
    fn normalize_lowercases_scheme_and_host_but_not_path() {
        assert_eq!(
            normalize_server_url("HTTP://JELLYFIN.test:8096/Some/Path"),
            "http://jellyfin.test:8096/Some/Path"
        );
    }

    #[test]
    fn normalize_drops_explicit_default_port() {
        assert_eq!(
            normalize_server_url("http://jellyfin.test:80"),
            "http://jellyfin.test"
        );
        assert_eq!(
            normalize_server_url("https://jellyfin.test:443/"),
            "https://jellyfin.test"
        );
    }

    #[test]
    fn normalize_keeps_a_non_default_port() {
        assert_eq!(
            normalize_server_url("http://jellyfin.test:8096"),
            "http://jellyfin.test:8096"
        );
    }

    #[test]
    fn normalize_trims_surrounding_whitespace() {
        assert_eq!(
            normalize_server_url("  http://jellyfin.test:8096  "),
            "http://jellyfin.test:8096"
        );
    }

    #[test]
    fn first_reachable_follows_candidate_order_not_probe_completion() {
        let candidates: Vec<String> = ["https://a", "https://a:8920", "http://a:8096"]
            .iter()
            .map(|s| s.to_string())
            .collect();
        assert_eq!(
            first_reachable(&candidates, &[false, true, true]),
            Some("https://a:8920")
        );
        assert_eq!(first_reachable(&candidates, &[false, false, false]), None);
    }

    #[test]
    fn mark_already_saved_flags_a_matching_address() {
        let found = vec![server("http://192.0.2.10:8096", "server-1", "Living Room")];
        let saved = vec!["http://192.0.2.10:8096/".to_string()];

        let marked = mark_already_saved(found, &saved);

        assert_eq!(marked.len(), 1);
        assert!(marked[0].already_saved);
    }

    #[test]
    fn mark_already_saved_leaves_an_unmatched_address_unmarked() {
        let found = vec![server("http://192.0.2.11:8096", "server-2", "Den")];
        let saved = vec!["http://192.0.2.10:8096".to_string()];

        let marked = mark_already_saved(found, &saved);

        assert_eq!(marked.len(), 1);
        assert!(!marked[0].already_saved);
    }

    #[test]
    fn mark_already_saved_matches_case_insensitively_on_host() {
        let found = vec![server("HTTP://Jellyfin.Test:8096", "server-3", "Case Test")];
        let saved = vec!["http://jellyfin.test:8096".to_string()];

        let marked = mark_already_saved(found, &saved);

        assert_eq!(marked.len(), 1);
        assert!(marked[0].already_saved);
    }

    /// Pins that `already_saved` marking reads real on-disk session state
    /// (`session::load_list`) via a temp `data_dir`; no sockets involved.
    #[test]
    fn already_saved_marking_reads_a_real_saved_session_file() {
        let dir = tempfile::tempdir().expect("tempdir");
        let saved = crate::session::SessionFile {
            server_url: "http://jellyfin.test:8096/".to_string(),
            user_id: "u1".to_string(),
            user_name: "jellybeam-user".to_string(),
            token: "tok".to_string(),
            device_id: "dev".to_string(),
            mirror_dir: "mirror".to_string(),
            server_version: None,
            server_name: None,
        };
        crate::session::add_or_update(dir.path(), saved).expect("seed a session file");

        let saved_urls: Vec<String> = crate::session::load_list(dir.path())
            .sessions
            .into_iter()
            .map(|session| session.server_url)
            .collect();
        let found = vec![server(
            "http://jellyfin.test:8096",
            "server-1",
            "Living Room",
        )];

        let marked = mark_already_saved(found, &saved_urls);

        assert_eq!(marked.len(), 1);
        assert!(marked[0].already_saved);
    }
}
