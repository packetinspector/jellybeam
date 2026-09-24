//! docs/13 "sign-in": pure helpers for normalizing a user-entered server
//! address and mapping a failed sign-in/Quick Connect `ApiError` to a
//! specific `CoreError` variant instead of raw transport/decode text.

use crate::error::CoreError;

/// Normalizes a user-typed server address for `JellyfinClient`: trims outer
/// whitespace, defaults to `http://` when no scheme was given, requires an
/// `http`/`https` scheme and a host, and strips a trailing `/`. Rejects
/// embedded whitespace and empty input before ever building a request.
pub(crate) fn normalize_sign_in_url(input: &str) -> Result<String, CoreError> {
    let trimmed = input.trim();
    if trimmed.is_empty() {
        return Err(CoreError::InvalidServerAddress {
            detail: "empty".to_string(),
        });
    }
    if trimmed.chars().any(char::is_whitespace) {
        return Err(CoreError::InvalidServerAddress {
            detail: "contains spaces".to_string(),
        });
    }

    let candidate = if trimmed.contains("://") {
        trimmed.to_string()
    } else {
        format!("http://{trimmed}")
    };

    let url = url::Url::parse(&candidate).map_err(|_| CoreError::InvalidServerAddress {
        detail: "not a valid URL".to_string(),
    })?;

    if url.scheme() != "http" && url.scheme() != "https" {
        return Err(CoreError::InvalidServerAddress {
            detail: format!("unsupported scheme {}", url.scheme()),
        });
    }
    if url.host_str().is_none() {
        return Err(CoreError::InvalidServerAddress {
            detail: "no host".to_string(),
        });
    }

    Ok(url.as_str().trim_end_matches('/').to_string())
}

/// `host[:port]` for display in a [`CoreError`] variant, derived from an
/// already-[`normalize_sign_in_url`]-ed URL. Falls back to the input
/// verbatim if it somehow doesn't parse -- display text only, never reused
/// for a request.
pub(crate) fn host_for_display(normalized_url: &str) -> String {
    url::Url::parse(normalized_url)
        .ok()
        .and_then(|url| {
            let host = url.host_str()?.to_string();
            Some(match url.port() {
                Some(port) => format!("{host}:{port}"),
                None => host,
            })
        })
        .unwrap_or_else(|| normalized_url.to_string())
}

/// docs/13 "sign-in": maps a failed sign-in/Quick Connect `ApiError` to a
/// `CoreError` variant Kotlin can show a specific message for. `host` is
/// display text only ([`host_for_display`]); the response body is never
/// surfaced.
pub(crate) fn classify_sign_in_error(host: &str, err: jellyfin_api::ApiError) -> CoreError {
    match err {
        jellyfin_api::ApiError::Unauthorized => CoreError::InvalidCredentials,
        jellyfin_api::ApiError::Status { code, .. } => CoreError::NotJellyfinServer {
            host: host.to_string(),
            status: code,
        },
        jellyfin_api::ApiError::Decode(_) => CoreError::NotJellyfinServer {
            host: host.to_string(),
            status: 200,
        },
        jellyfin_api::ApiError::Transport(msg) => classify_transport_error(host, &msg),
    }
}

/// Split out of [`classify_sign_in_error`] for the `Transport` arm's own
/// substring heuristics (docs/13 "sign-in": no structured error info
/// crosses the reqwest boundary, only `Display` text).
fn classify_transport_error(host: &str, msg: &str) -> CoreError {
    let https_not_offered = [
        "InvalidContentType",
        "corrupt message",
        "handshake",
        "certificate",
    ]
    .iter()
    .any(|needle| msg.contains(needle));
    if https_not_offered {
        return CoreError::HttpsNotOffered {
            host: host.to_string(),
        };
    }
    if msg.contains("relative URL") || msg.contains("builder error") {
        return CoreError::InvalidServerAddress {
            detail: "not a valid URL".to_string(),
        };
    }

    let reason = if msg.contains("dns error") {
        "the name could not be resolved".to_string()
    } else if msg.contains("Connection refused") {
        "connection refused".to_string()
    } else if msg.contains("timed out") || msg.contains("timeout") {
        "timed out".to_string()
    } else if msg.contains("Network is unreachable") || msg.contains("unreachable") {
        "network unreachable".to_string()
    } else {
        truncate_chars(msg, 120)
    };

    CoreError::ServerUnreachable {
        host: host.to_string(),
        reason,
    }
}

/// Truncates `s` to at most `max_chars` chars on a char boundary (never
/// mid-UTF-8), for [`classify_transport_error`]'s raw-message fallback.
fn truncate_chars(s: &str, max_chars: usize) -> String {
    if s.chars().count() <= max_chars {
        return s.to_string();
    }
    s.chars().take(max_chars).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn normalize_defaults_to_http_and_keeps_port() {
        assert_eq!(
            normalize_sign_in_url("  192.0.2.10:8096 ").expect("valid"),
            "http://192.0.2.10:8096"
        );
    }

    #[test]
    fn normalize_strips_trailing_slash_but_keeps_path() {
        assert_eq!(
            normalize_sign_in_url("https://media.example.test/jf/").expect("valid"),
            "https://media.example.test/jf"
        );
    }

    #[test]
    fn normalize_rejects_prose_as_invalid_address() {
        let err = normalize_sign_in_url("not a server").expect_err("not a URL");
        assert!(matches!(err, CoreError::InvalidServerAddress { .. }));
    }

    #[test]
    fn normalize_rejects_empty_input() {
        let err = normalize_sign_in_url("").expect_err("empty");
        assert!(matches!(err, CoreError::InvalidServerAddress { .. }));
    }

    #[test]
    fn normalize_rejects_unsupported_scheme() {
        let err = normalize_sign_in_url("ftp://x.example.test").expect_err("ftp unsupported");
        assert!(matches!(err, CoreError::InvalidServerAddress { .. }));
    }

    #[test]
    fn normalize_rejects_a_bare_scheme_with_no_host() {
        let err = normalize_sign_in_url("http://").expect_err("no host");
        assert!(matches!(err, CoreError::InvalidServerAddress { .. }));
    }

    #[test]
    fn host_for_display_includes_a_non_default_port() {
        assert_eq!(
            host_for_display("http://192.0.2.10:8096"),
            "192.0.2.10:8096"
        );
    }

    #[test]
    fn host_for_display_omits_the_implied_port() {
        assert_eq!(
            host_for_display("https://media.example.test/jf"),
            "media.example.test"
        );
    }

    #[test]
    fn classify_unauthorized_is_invalid_credentials_not_expired_authorization() {
        let err =
            classify_sign_in_error("media.example.test", jellyfin_api::ApiError::Unauthorized);
        assert!(matches!(err, CoreError::InvalidCredentials));
    }

    #[test]
    fn classify_status_is_not_jellyfin_server_with_the_response_code() {
        let err = classify_sign_in_error(
            "media.example.test",
            jellyfin_api::ApiError::Status {
                code: 404,
                body: "<html>whole page</html>".to_string(),
            },
        );
        match err {
            CoreError::NotJellyfinServer { host, status } => {
                assert_eq!(host, "media.example.test");
                assert_eq!(status, 404);
            }
            other => panic!("expected NotJellyfinServer, got {other:?}"),
        }
    }

    #[test]
    fn classify_decode_is_not_jellyfin_server_with_status_200() {
        let err = classify_sign_in_error(
            "media.example.test",
            jellyfin_api::ApiError::Decode("missing AccessToken".to_string()),
        );
        assert!(matches!(
            err,
            CoreError::NotJellyfinServer { status: 200, .. }
        ));
    }

    #[test]
    fn classify_transport_https_not_offered_on_corrupt_message() {
        let err = classify_sign_in_error(
            "media.example.test:8096",
            jellyfin_api::ApiError::Transport(
                "client error (Connect): received corrupt message of type InvalidContentType"
                    .to_string(),
            ),
        );
        assert!(matches!(err, CoreError::HttpsNotOffered { .. }));
    }

    #[test]
    fn classify_transport_https_not_offered_on_handshake_failure() {
        let err = classify_sign_in_error(
            "media.example.test",
            jellyfin_api::ApiError::Transport("tls handshake eof".to_string()),
        );
        assert!(matches!(err, CoreError::HttpsNotOffered { .. }));
    }

    #[test]
    fn classify_transport_builder_error_is_invalid_server_address() {
        let err = classify_sign_in_error(
            "",
            jellyfin_api::ApiError::Transport(
                "builder error: relative URL without a base".to_string(),
            ),
        );
        assert!(matches!(err, CoreError::InvalidServerAddress { .. }));
    }

    #[test]
    fn classify_transport_dns_error_is_server_unreachable() {
        let err = classify_sign_in_error(
            "nowhere.example.test",
            jellyfin_api::ApiError::Transport(
                "dns error: failed to lookup address information: nodename nor servname provided"
                    .to_string(),
            ),
        );
        match err {
            CoreError::ServerUnreachable { host, reason } => {
                assert_eq!(host, "nowhere.example.test");
                assert_eq!(reason, "the name could not be resolved");
            }
            other => panic!("expected ServerUnreachable, got {other:?}"),
        }
    }

    #[test]
    fn classify_transport_connection_refused_is_server_unreachable() {
        let err = classify_sign_in_error(
            "192.0.2.10:9",
            jellyfin_api::ApiError::Transport(
                "client error (Connect): tcp connect error: Connection refused (os error 61)"
                    .to_string(),
            ),
        );
        match err {
            CoreError::ServerUnreachable { reason, .. } => {
                assert_eq!(reason, "connection refused");
            }
            other => panic!("expected ServerUnreachable, got {other:?}"),
        }
    }

    #[test]
    fn classify_transport_timeout_is_server_unreachable() {
        let err = classify_sign_in_error(
            "192.0.2.10",
            jellyfin_api::ApiError::Transport("operation timed out".to_string()),
        );
        match err {
            CoreError::ServerUnreachable { reason, .. } => assert_eq!(reason, "timed out"),
            other => panic!("expected ServerUnreachable, got {other:?}"),
        }
    }

    #[test]
    fn classify_transport_network_unreachable_is_server_unreachable() {
        let err = classify_sign_in_error(
            "192.0.2.10",
            jellyfin_api::ApiError::Transport(
                "client error (Connect): Network is unreachable (os error 51)".to_string(),
            ),
        );
        match err {
            CoreError::ServerUnreachable { reason, .. } => {
                assert_eq!(reason, "network unreachable");
            }
            other => panic!("expected ServerUnreachable, got {other:?}"),
        }
    }

    #[test]
    fn classify_transport_unrecognized_message_falls_back_to_raw_text() {
        let err = classify_sign_in_error(
            "192.0.2.10",
            jellyfin_api::ApiError::Transport("something unexpected happened".to_string()),
        );
        match err {
            CoreError::ServerUnreachable { reason, .. } => {
                assert_eq!(reason, "something unexpected happened");
            }
            other => panic!("expected ServerUnreachable, got {other:?}"),
        }
    }

    #[test]
    fn classify_transport_unrecognized_message_is_truncated_to_120_chars() {
        let long_msg = "x".repeat(500);
        let err = classify_sign_in_error("192.0.2.10", jellyfin_api::ApiError::Transport(long_msg));
        match err {
            CoreError::ServerUnreachable { reason, .. } => assert_eq!(reason.chars().count(), 120),
            other => panic!("expected ServerUnreachable, got {other:?}"),
        }
    }
}
