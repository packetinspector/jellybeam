//! LAN server autodetection (`docs/feature-dev/spec-still-watching-and-lan-discovery.md`):
//! Jellyfin's UDP discovery protocol.
//! Broadcasts `Who is JellyfinServer?` to UDP port 7359; servers reply with JSON
//! `{"Address":..,"Id":..,"Name":..}`. Not every deployment forwards 7359, so this is an
//! accelerator only -- socket/timeout failures resolve to an empty result, never an error.
//! [`discover_from`] is public so a caller or test can supply extra per-interface broadcast
//! targets; subnet-broadcast enumeration itself is out of scope (would need a new
//! interface-enumeration crate).

use std::collections::HashSet;
use std::net::{IpAddr, SocketAddr};
use std::time::Duration;

use tokio::net::UdpSocket;

/// UDP 7359 discovery payload; servers match it case-insensitively.
const DISCOVERY_MAGIC: &str = "Who is JellyfinServer?";

/// Jellyfin's discovery reply; missing/null fields deserialize to `None` rather than failing the
/// datagram.
/// Caller drops replies missing or empty `Address`/`Id`; a missing `Name` becomes an empty string,
/// not a placeholder.
#[derive(Debug, Clone, PartialEq, Eq, Default, serde::Deserialize)]
#[serde(default)]
struct DiscoveryReply {
    #[serde(rename = "Address")]
    address: Option<String>,
    #[serde(rename = "Id")]
    id: Option<String>,
    #[serde(rename = "Name")]
    name: Option<String>,
}

/// One server that answered within the window. `address` has its trailing slash trimmed (matches
/// this crate's base-URL convention); `name` is shown verbatim per CLAUDE.md. `responder_ip` is
/// the datagram's source, kept for [`fallback_candidates`] when the advertised address is dead.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DiscoveredServer {
    pub address: String,
    pub id: String,
    pub name: String,
    pub responder_ip: Option<IpAddr>,
}

/// Jellyfin's default HTTPS and HTTP ports.
const DEFAULT_HTTPS_PORT: u16 = 8920;
const DEFAULT_HTTP_PORT: u16 = 8096;

/// Addresses to try, in order, when the advertised one does not answer: a server whose published
/// URL omits its port advertises a dead address. Secure candidates first (`https://host`, then the
/// default HTTPS port), then the default HTTP port, then the same three on the responder's IP when
/// it differs from the advertised host. The advertised address itself is never repeated.
pub fn fallback_candidates(advertised: &str, responder_ip: Option<IpAddr>) -> Vec<String> {
    let host = advertised_host(advertised);
    let ip_host = responder_ip.map(|ip| match ip {
        IpAddr::V4(v4) => v4.to_string(),
        IpAddr::V6(v6) => format!("[{v6}]"),
    });
    let mut hosts: Vec<String> = host.into_iter().collect();
    if let Some(ip_host) = ip_host {
        if !hosts.contains(&ip_host) {
            hosts.push(ip_host);
        }
    }
    let advertised = advertised.trim_end_matches('/');
    hosts
        .iter()
        .flat_map(|h| {
            [
                format!("https://{h}"),
                format!("https://{h}:{DEFAULT_HTTPS_PORT}"),
                format!("http://{h}:{DEFAULT_HTTP_PORT}"),
            ]
        })
        .filter(|candidate| candidate != advertised)
        .collect()
}

/// `host` or `[v6]` from an advertised `scheme://host[:port][/path]`; `None` when it has no host.
fn advertised_host(advertised: &str) -> Option<String> {
    let rest = advertised
        .split_once("://")
        .map_or(advertised, |(_, rest)| rest);
    let authority = rest.split('/').next().unwrap_or("");
    let host = if let Some(end) = authority.strip_prefix('[').and_then(|a| a.find(']')) {
        &authority[..end + 2]
    } else {
        authority.split(':').next().unwrap_or("")
    };
    (!host.is_empty()).then(|| host.to_string())
}

/// Broadcasts to `255.255.255.255:7359` and collects replies for `window_ms`, capped at `cap`. Thin
/// wrapper over [`discover_from`] with the production target list.
pub async fn discover_local_servers(window_ms: u64, cap: usize) -> Vec<DiscoveredServer> {
    let broadcast: SocketAddr = SocketAddr::from(([255, 255, 255, 255], 7359));
    discover_from(&[broadcast], window_ms, cap).await
}

/// Sends the discovery payload to every address in `targets` (public so tests and the FFI layer can
/// target a loopback responder instead of the real LAN broadcast) and collects replies for
/// `window_ms`, de-duplicated by `Id` (first reply wins), capped at `cap`.
/// Re-sends once at the 500ms mark (skipped when `window_ms` <= 500). Malformed datagrams and
/// socket errors are swallowed rather than propagated -- discovery is fail-open by design,
/// returning whatever was already collected.
/// Cancellation-safe: all I/O is on a socket owned by this future, and it spawns no tasks that
/// could outlive it.
pub async fn discover_from(
    targets: &[SocketAddr],
    window_ms: u64,
    cap: usize,
) -> Vec<DiscoveredServer> {
    let socket = match UdpSocket::bind("0.0.0.0:0").await {
        Ok(socket) => socket,
        Err(e) => {
            tracing::warn!(error = %e, "discovery: failed to bind UDP socket; no servers found");
            return Vec::new();
        }
    };
    if let Err(e) = socket.set_broadcast(true) {
        tracing::warn!(error = %e, "discovery: failed to enable SO_BROADCAST; no servers found");
        return Vec::new();
    }

    for target in targets {
        let _ = socket.send_to(DISCOVERY_MAGIC.as_bytes(), target).await;
    }

    let window = Duration::from_millis(window_ms);
    let resend_at = Duration::from_millis(500);
    // Skip the resend if the window already ends at or before the resend mark.
    let mut resent = window <= resend_at;

    let start = std::time::Instant::now();
    let mut found: Vec<DiscoveredServer> = Vec::new();
    let mut seen_ids: HashSet<String> = HashSet::new();
    let mut buf = [0u8; 1024];

    loop {
        if found.len() >= cap {
            break;
        }
        let elapsed = start.elapsed();
        if elapsed >= window {
            break;
        }

        if !resent && elapsed >= resend_at {
            for target in targets {
                let _ = socket.send_to(DISCOVERY_MAGIC.as_bytes(), target).await;
            }
            resent = true;
        }

        // Wake at the resend mark instead of sleeping straight to the deadline, so the resend goes
        // out mid-window.
        let mut wait = window - elapsed;
        if !resent {
            // resent is false here, so elapsed < resend_at -- this subtraction can't underflow.
            wait = wait.min(resend_at - elapsed);
        }

        match tokio::time::timeout(wait, socket.recv_from(&mut buf)).await {
            Ok(Ok((n, src))) => {
                let Ok(reply) = serde_json::from_slice::<DiscoveryReply>(&buf[..n]) else {
                    // Malformed datagram -- ignore and keep listening.
                    continue;
                };
                let (Some(address), Some(id)) = (reply.address, reply.id) else {
                    continue;
                };
                if address.is_empty() || id.is_empty() || seen_ids.contains(&id) {
                    continue;
                }
                seen_ids.insert(id.clone());
                found.push(DiscoveredServer {
                    address: address.trim_end_matches('/').to_string(),
                    id,
                    name: reply.name.unwrap_or_default(),
                    responder_ip: Some(src.ip()),
                });
            }
            Ok(Err(e)) => {
                tracing::warn!(
                    error = %e,
                    "discovery: socket error while receiving; returning what was collected so far"
                );
                break;
            }
            Err(_elapsed) => {
                // Wait slice ran out (resend mark or deadline) -- loop back and re-check.
            }
        }
    }

    found
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::net::UdpSocket as TokioUdpSocket;

    fn reply_json(address: &str, id: &str, name: &str) -> Vec<u8> {
        serde_json::json!({
            "Address": address,
            "Id": id,
            "Name": name,
            "EndpointAddress": null,
        })
        .to_string()
        .into_bytes()
    }

    /// Loopback UDP responder that replies to each discovery-magic datagram with `replies` in
    /// order, then keeps listening for a resend.
    /// Not explicitly joined; `#[tokio::test]` drops it with the runtime at test end.
    async fn spawn_responder(replies: Vec<Vec<u8>>) -> SocketAddr {
        let socket = TokioUdpSocket::bind("127.0.0.1:0")
            .await
            .expect("bind mock responder");
        let addr = socket.local_addr().expect("mock responder local_addr");
        tokio::spawn(async move {
            let mut buf = [0u8; 1024];
            loop {
                let (n, src) = match socket.recv_from(&mut buf).await {
                    Ok(v) => v,
                    Err(_) => return,
                };
                let text = String::from_utf8_lossy(&buf[..n]);
                if !text.eq_ignore_ascii_case(DISCOVERY_MAGIC) {
                    continue;
                }
                for reply in &replies {
                    let _ = socket.send_to(reply, src).await;
                }
            }
        });
        addr
    }

    #[test]
    fn fallback_candidates_try_secure_first_then_the_responder_ip() {
        let ip = Some(IpAddr::from([192, 0, 2, 10]));
        assert_eq!(
            fallback_candidates("http://jellyfin.example.test", ip),
            vec![
                "https://jellyfin.example.test",
                "https://jellyfin.example.test:8920",
                "http://jellyfin.example.test:8096",
                "https://192.0.2.10",
                "https://192.0.2.10:8920",
                "http://192.0.2.10:8096",
            ]
        );
    }

    #[test]
    fn fallback_candidates_skip_the_advertised_address_and_a_matching_ip() {
        let ip = Some(IpAddr::from([192, 0, 2, 10]));
        assert_eq!(
            fallback_candidates("http://192.0.2.10:8096/", ip),
            vec!["https://192.0.2.10", "https://192.0.2.10:8920"]
        );
        assert_eq!(
            fallback_candidates(
                "https://[2001:db8::1]:8920",
                Some(IpAddr::from([0x2001, 0xdb8, 0, 0, 0, 0, 0, 1]))
            ),
            vec!["https://[2001:db8::1]", "http://[2001:db8::1]:8096"]
        );
        assert!(fallback_candidates("", None).is_empty());
    }

    #[tokio::test]
    async fn parses_a_well_formed_reply_verbatim() {
        let addr = spawn_responder(vec![reply_json(
            "http://192.0.2.10:8096",
            "server-1",
            "Living Room \u{1F3E0} \u{5BA2}\u{5385}",
        )])
        .await;

        let found = discover_from(&[addr], 300, 15).await;

        assert_eq!(found.len(), 1);
        assert_eq!(found[0].address, "http://192.0.2.10:8096");
        assert_eq!(found[0].id, "server-1");
        assert_eq!(found[0].name, "Living Room \u{1F3E0} \u{5BA2}\u{5385}");
        assert_eq!(found[0].responder_ip, Some(addr.ip()));
    }

    #[tokio::test]
    async fn dedupes_by_id_first_reply_wins() {
        let addr = spawn_responder(vec![
            reply_json("http://192.0.2.10:8096", "server-1", "First"),
            reply_json("http://192.0.2.11:8096", "server-1", "Second"),
        ])
        .await;

        let found = discover_from(&[addr], 300, 15).await;

        assert_eq!(found.len(), 1);
        assert_eq!(found[0].address, "http://192.0.2.10:8096");
        assert_eq!(found[0].name, "First");
    }

    #[tokio::test]
    async fn trims_a_trailing_slash_off_the_address() {
        let addr = spawn_responder(vec![reply_json(
            "http://jellyfin.test:8096/",
            "server-2",
            "Slashy",
        )])
        .await;

        let found = discover_from(&[addr], 300, 15).await;

        assert_eq!(found.len(), 1);
        assert_eq!(found[0].address, "http://jellyfin.test:8096");
    }

    #[tokio::test]
    async fn skips_a_malformed_datagram_but_still_parses_the_good_one() {
        let addr = spawn_responder(vec![
            b"not json at all".to_vec(),
            reply_json("http://192.0.2.12:8096", "server-3", "Good"),
        ])
        .await;

        let found = discover_from(&[addr], 300, 15).await;

        assert_eq!(found.len(), 1);
        assert_eq!(found[0].id, "server-3");
    }

    #[tokio::test]
    async fn drops_a_reply_missing_address() {
        let json = serde_json::json!({ "Id": "server-4", "Name": "NoAddress" })
            .to_string()
            .into_bytes();
        let addr = spawn_responder(vec![json]).await;

        let found = discover_from(&[addr], 300, 15).await;

        assert!(found.is_empty());
    }

    #[tokio::test]
    async fn no_responder_returns_empty_within_the_window() {
        // Pins the pure-timeout path: nothing is bound, so recv_from never fires.
        let target: SocketAddr = "127.0.0.1:1".parse().expect("valid loopback addr");
        let window_ms = 300;

        let start = std::time::Instant::now();
        let found = discover_from(&[target], window_ms, 15).await;
        let elapsed = start.elapsed();

        assert!(found.is_empty());
        assert!(
            elapsed.as_millis() >= u128::from(window_ms),
            "returned before the window elapsed: {elapsed:?}"
        );
        assert!(
            elapsed.as_millis() <= u128::from(window_ms) + 500,
            "took far longer than the window: {elapsed:?}"
        );
    }

    #[tokio::test]
    async fn cap_limits_the_number_of_results() {
        let addr = spawn_responder(vec![
            reply_json("http://192.0.2.20:8096", "server-a", "A"),
            reply_json("http://192.0.2.21:8096", "server-b", "B"),
            reply_json("http://192.0.2.22:8096", "server-c", "C"),
        ])
        .await;

        let found = discover_from(&[addr], 300, 2).await;

        assert_eq!(found.len(), 2);
    }

    #[test]
    fn discovery_reply_defaults_every_field_when_absent() {
        let reply: DiscoveryReply = serde_json::from_str("{}").expect("empty object parses");
        assert_eq!(reply.address, None);
        assert_eq!(reply.id, None);
        assert_eq!(reply.name, None);
    }

    #[test]
    fn discovery_reply_treats_explicit_nulls_as_absent() {
        let reply: DiscoveryReply =
            serde_json::from_str(r#"{"Address":null,"Id":null,"Name":null}"#)
                .expect("null fields parse");
        assert_eq!(reply.address, None);
        assert_eq!(reply.id, None);
        assert_eq!(reply.name, None);
    }

    #[test]
    fn discovery_reply_parses_a_full_object() {
        let reply: DiscoveryReply = serde_json::from_str(
            r#"{"Address":"http://192.0.2.5:8096","Id":"abc","Name":"Den","EndpointAddress":null}"#,
        )
        .expect("full object parses");
        assert_eq!(reply.address.as_deref(), Some("http://192.0.2.5:8096"));
        assert_eq!(reply.id.as_deref(), Some("abc"));
        assert_eq!(reply.name.as_deref(), Some("Den"));
    }
}
