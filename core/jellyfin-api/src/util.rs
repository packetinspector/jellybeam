//! Small shared helpers used by both the REST client (`lib.rs`) and the
//! WebSocket client (`ws.rs`).

/// Percent-encode a string for safe inclusion in a URL query value.
/// Hand-rolled instead of a `percent-encoding`/`url` query-pair builder so `image_url`/`stream_url`
/// stay infallible (`-> String`, no `Result`) even if a server returns a tag/id needing escaping.
pub(crate) fn percent_encode(input: &str) -> String {
    let mut out = String::with_capacity(input.len());
    for b in input.bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(b as char)
            }
            _ => out.push_str(&format!("%{b:02X}")),
        }
    }
    out
}

/// Walks `err`'s `source()` chain and joins every layer's `Display` output with `": "`.
/// `reqwest::Error`/`tungstenite::Error` print only the top frame, dropping the underlying cause
/// (DNS failure, TLS error, connection refusal) that's usually the actionable part for a failed
/// Connect screen.
pub(crate) fn describe_error_chain(err: &(dyn std::error::Error + 'static)) -> String {
    let mut out = err.to_string();
    let mut source = err.source();
    while let Some(next) = source {
        out.push_str(": ");
        out.push_str(&next.to_string());
        source = next.source();
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn leaves_safe_chars_alone() {
        assert_eq!(percent_encode("abc123-_.~"), "abc123-_.~");
    }

    #[test]
    fn encodes_unsafe_chars() {
        assert_eq!(percent_encode("a b/c"), "a%20b%2Fc");
    }

    #[derive(Debug)]
    struct RootCause;

    impl std::fmt::Display for RootCause {
        fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
            write!(f, "dns error: failed to lookup address information")
        }
    }

    impl std::error::Error for RootCause {}

    #[derive(Debug)]
    struct MiddleLayer(RootCause);

    impl std::fmt::Display for MiddleLayer {
        fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
            write!(f, "client error (Connect)")
        }
    }

    impl std::error::Error for MiddleLayer {
        fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
            Some(&self.0)
        }
    }

    #[derive(Debug)]
    struct TopLayer(MiddleLayer);

    impl std::fmt::Display for TopLayer {
        fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
            write!(f, "error sending request for url (http://example/)")
        }
    }

    impl std::error::Error for TopLayer {
        fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
            Some(&self.0)
        }
    }

    #[test]
    fn describe_error_chain_joins_every_source_layer() {
        let err = TopLayer(MiddleLayer(RootCause));
        assert_eq!(
            describe_error_chain(&err),
            "error sending request for url (http://example/): client error (Connect): dns error: failed to lookup address information"
        );
    }

    #[test]
    fn describe_error_chain_with_no_source_is_just_display() {
        let err = RootCause;
        assert_eq!(
            describe_error_chain(&err),
            "dns error: failed to lookup address information"
        );
    }
}
