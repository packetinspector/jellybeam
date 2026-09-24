//! Tracing forwarder to the Kotlin diagnostic ring (docs/21 §2.1 last
//! paragraph, §2.3): a hand-rolled `tracing::Subscriber` that forwards
//! WARN/ERROR (any target) and INFO from `media_cache::sync*` (sync-pass
//! completion lines) to a uniffi callback, redacting every field at the
//! write site so nothing identifying ever reaches the ring.

use std::fmt;
use std::sync::Arc;

use tracing::field::{Field, Visit};
use tracing::level_filters::LevelFilter;
use tracing::{span, Event, Level, Metadata, Subscriber};

/// Mirrors a `tracing::Level`, crossing the FFI boundary as a plain enum --
/// see [`install_diag_sink`].
#[derive(uniffi::Enum, Debug, Clone, Copy, PartialEq, Eq)]
pub enum DiagLevel {
    Info,
    Warn,
    Error,
}

impl From<Level> for DiagLevel {
    /// TRACE/DEBUG never reach here -- see [`DiagSubscriber::enabled`] --
    /// so they fall into the `Info` arm only as an unreachable-in-practice
    /// default.
    fn from(level: Level) -> Self {
        match level {
            Level::ERROR => DiagLevel::Error,
            Level::WARN => DiagLevel::Warn,
            _ => DiagLevel::Info,
        }
    }
}

/// Kotlin-implemented sink for forwarded `tracing` records (docs/21 §2.1).
/// One record per `event()` call, on the emitting thread -- see
/// [`install_diag_sink`] for the cost contract.
#[uniffi::export(with_foreign)]
pub trait DiagSink: Send + Sync {
    fn on_record(&self, level: DiagLevel, target: String, message: String, fields: String);
}

/// The only string/Debug/Error field names forwarded as scrubbed text
/// (docs/21 §2.3): fixed, closed-vocabulary fields a call site cannot use
/// to smuggle free text (a title, a path, a search query). `error`/`err`
/// are handled separately by [`classify_error`] since scrubbing alone
/// cannot bound server-controlled response bodies. Numeric and bool fields
/// carry no free text, so they forward under any name -- see
/// `EventVisitor::push`.
const ALLOW_TEXT_FIELDS: &[&str] = &["kind", "status", "code", "state", "phase", "op"];

/// True for the two field names that never forward their own text (docs/21
/// §2.3) -- an `ApiError::Status` body or any other server-controlled error
/// text is classified by [`classify_error`] instead.
fn is_error_field(name: &str) -> bool {
    name == "error" || name == "err"
}

/// Finds the first run of exactly three ASCII digits, starting with `4` or
/// `5`, bounded by non-digits -- the HTTP status code embedded in an error's
/// text (e.g. `ApiError::Status`'s `"http status 500: ..."` Display).
fn find_http_status(text: &str) -> Option<&str> {
    let bytes = text.as_bytes();
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i].is_ascii_digit() {
            let start = i;
            while i < bytes.len() && bytes[i].is_ascii_digit() {
                i += 1;
            }
            let run = &text[start..i];
            if run.len() == 3 && (run.starts_with('4') || run.starts_with('5')) {
                return Some(run);
            }
        } else {
            i += 1;
        }
    }
    None
}

/// Classifies an `error`/`err` field's text into a fixed, closed-vocabulary
/// label (docs/21 §2.3) -- the only thing ever forwarded for those fields,
/// since the text itself may carry a server response body, a title or a
/// path. First matching rule wins; an HTTP status code found in the text
/// keeps its real digits (`http_500`), everything else maps to a fixed
/// word. Returns `String` rather than `&'static str` because the status
/// branch has to embed the digits it found.
fn classify_error(text: &str) -> String {
    let lower = text.to_lowercase();
    if lower.contains("timed out") || lower.contains("timeout") {
        "timeout".to_string()
    } else if lower.contains("connection refused") {
        "connection_refused".to_string()
    } else if lower.contains("connection reset")
        || lower.contains("broken pipe")
        || lower.contains("eof")
    {
        "connection_reset".to_string()
    } else if lower.contains("dns")
        || lower.contains("failed to lookup")
        || lower.contains("resolve")
    {
        "dns".to_string()
    } else if lower.contains("certificate") || lower.contains("tls") || lower.contains("ssl") {
        "tls".to_string()
    } else if let Some(status) = find_http_status(&lower) {
        format!("http_{status}")
    } else if lower.contains("unauthorized") {
        "http_401".to_string()
    } else if lower.contains("not found") {
        "http_404".to_string()
    } else if lower.contains("json")
        || lower.contains("deserialize")
        || lower.contains("decode")
        || lower.contains("parse")
    {
        "decode".to_string()
    } else if lower.contains("sqlite")
        || lower.contains("database")
        || lower.contains("locked")
        || lower.contains("constraint")
    {
        "db".to_string()
    } else if lower.contains("no such file")
        || lower.contains("permission denied")
        || lower.contains("read-only")
        || lower.contains("disk")
        || lower.contains("i/o")
        || lower.contains("io error")
    {
        "io".to_string()
    } else if lower.contains("cancel") {
        "cancelled".to_string()
    } else {
        "other".to_string()
    }
}

/// Target prefix for the one INFO-level line class this forwarder keeps
/// (docs/21 §2.1: "sync-pass completion lines live there").
const SYNC_TARGET_PREFIX: &str = "media_cache::sync";

/// Replaces every `scheme://<non-whitespace, non-quote>*` run with `<url>`,
/// consuming the whole run (query string included) so a URL carrying
/// `api_key=...` never reaches the second redaction pass.
fn redact_urls(input: &str) -> String {
    fn is_scheme_byte(b: u8) -> bool {
        b.is_ascii_alphanumeric() || b == b'+' || b == b'.' || b == b'-'
    }
    fn is_url_stop_byte(b: u8) -> bool {
        b.is_ascii_whitespace() || b == b'"' || b == b'\''
    }

    let bytes = input.as_bytes();
    let mut out: Vec<u8> = Vec::with_capacity(bytes.len());
    let mut i = 0usize;
    while i < bytes.len() {
        if bytes[i..].starts_with(b"://") {
            let mut start = i;
            while start > 0 && is_scheme_byte(bytes[start - 1]) {
                start -= 1;
            }
            if start < i {
                out.truncate(out.len() - (i - start));
                let mut end = i + 3;
                while end < bytes.len() && !is_url_stop_byte(bytes[end]) {
                    end += 1;
                }
                out.extend_from_slice(b"<url>");
                i = end;
                continue;
            }
        }
        out.push(bytes[i]);
        i += 1;
    }
    // Every appended run is either verbatim bytes from `input` or the ASCII
    // literal `<url>`, so `out` stays valid UTF-8.
    String::from_utf8(out).unwrap_or_default()
}

/// Replaces the value after a case-insensitive `api_key=`/`apikey=`/
/// `token=`/`password=` key (docs/21 §2.3) up to whitespace, `&`, `"` or `'`
/// with `<redacted>`.
fn redact_keys(input: &str) -> String {
    const KEYS: &[&[u8]] = &[b"api_key", b"apikey", b"token", b"password"];

    fn is_value_stop_byte(b: u8) -> bool {
        b.is_ascii_whitespace() || b == b'&' || b == b'"' || b == b'\''
    }

    let bytes = input.as_bytes();
    let mut out: Vec<u8> = Vec::with_capacity(bytes.len());
    let mut i = 0usize;
    while i < bytes.len() {
        let matched_prefix_len = KEYS.iter().find_map(|key| {
            let prefix_len = key.len() + 1;
            let has_prefix = i + prefix_len <= bytes.len()
                && bytes[i..i + key.len()].eq_ignore_ascii_case(key)
                && bytes[i + key.len()] == b'=';
            has_prefix.then_some(prefix_len)
        });
        if let Some(prefix_len) = matched_prefix_len {
            out.extend_from_slice(&bytes[i..i + prefix_len]);
            let mut end = i + prefix_len;
            while end < bytes.len() && !is_value_stop_byte(bytes[end]) {
                end += 1;
            }
            out.extend_from_slice(b"<redacted>");
            i = end;
            continue;
        }
        out.push(bytes[i]);
        i += 1;
    }
    // Same UTF-8 argument as `redact_urls`.
    String::from_utf8(out).unwrap_or_default()
}

/// Truncates to `max_chars` `char`s, appending `…` when cut (docs/21 §2.3).
fn truncate_with_ellipsis(s: &str, max_chars: usize) -> String {
    if s.chars().count() <= max_chars {
        return s.to_string();
    }
    let mut out: String = s.chars().take(max_chars).collect();
    out.push('…');
    out
}

/// The one redaction seam every field value passes through (docs/21 §2.3):
/// URL runs first (so an embedded `api_key=` inside a URL is consumed
/// whole), then bare key=value secrets, then a 120-char cap.
fn scrub(input: &str) -> String {
    truncate_with_ellipsis(&redact_keys(&redact_urls(input)), 120)
}

/// Collects one event's fields into `message` (the implicit `message`
/// field) and `fields` (every other field, `name=value` space-joined, in
/// visit order) -- docs/21 §2.1/§2.3.
#[derive(Default)]
struct EventVisitor {
    message: Option<String>,
    fields: String,
}

impl EventVisitor {
    /// Routes one field's formatted value to `message` or `fields`. `message`
    /// is always kept, scrubbed like any other string field. An `error`/`err`
    /// text field forwards only [`classify_error`]'s label, never its own
    /// text; any other text field (`scrub_value` true) is dropped unless its
    /// name is in [`ALLOW_TEXT_FIELDS`]; numeric/bool fields (`scrub_value`
    /// false) are always kept, unscrubbed -- docs/21 §2.3.
    fn push(&mut self, name: &'static str, raw: String, scrub_value: bool) {
        if name == "message" {
            self.message = Some(if scrub_value { scrub(&raw) } else { raw });
            return;
        }
        let error_field = scrub_value && is_error_field(name);
        let value = if error_field {
            classify_error(&raw)
        } else if scrub_value {
            scrub(&raw)
        } else {
            raw
        };
        if scrub_value && !error_field && !ALLOW_TEXT_FIELDS.contains(&name) {
            return;
        }
        if !self.fields.is_empty() {
            self.fields.push(' ');
        }
        self.fields.push_str(name);
        self.fields.push('=');
        self.fields.push_str(&value);
    }
}

impl Visit for EventVisitor {
    fn record_debug(&mut self, field: &Field, value: &dyn fmt::Debug) {
        self.push(field.name(), format!("{value:?}"), true);
    }

    fn record_str(&mut self, field: &Field, value: &str) {
        self.push(field.name(), value.to_string(), true);
    }

    fn record_error(&mut self, field: &Field, value: &(dyn std::error::Error + 'static)) {
        self.push(field.name(), value.to_string(), true);
    }

    fn record_i64(&mut self, field: &Field, value: i64) {
        self.push(field.name(), value.to_string(), false);
    }

    fn record_u64(&mut self, field: &Field, value: u64) {
        self.push(field.name(), value.to_string(), false);
    }

    fn record_i128(&mut self, field: &Field, value: i128) {
        self.push(field.name(), value.to_string(), false);
    }

    fn record_u128(&mut self, field: &Field, value: u128) {
        self.push(field.name(), value.to_string(), false);
    }

    fn record_f64(&mut self, field: &Field, value: f64) {
        self.push(field.name(), value.to_string(), false);
    }

    fn record_bool(&mut self, field: &Field, value: bool) {
        self.push(field.name(), value.to_string(), false);
    }
}

/// `tracing::Subscriber` forwarding to one [`DiagSink`] -- see
/// [`install_diag_sink`]. Hand-written rather than pulling in
/// `tracing-subscriber` (not a dependency of this crate).
struct DiagSubscriber {
    sink: Arc<dyn DiagSink>,
}

impl Subscriber for DiagSubscriber {
    /// WARN/ERROR on any target; INFO only on `media_cache::sync*`; nothing
    /// else -- docs/21 §2.1. `Level` isn't a real Rust enum (it wraps a
    /// private one), so this is `if`/`else`, not a `match`.
    fn enabled(&self, metadata: &Metadata<'_>) -> bool {
        let level = *metadata.level();
        if level == Level::ERROR || level == Level::WARN {
            return true;
        }
        level == Level::INFO && metadata.target().starts_with(SYNC_TARGET_PREFIX)
    }

    /// Keeps DEBUG/TRACE call sites cheap through `tracing`'s global max
    /// level -- docs/21 §2.2.
    fn max_level_hint(&self) -> Option<LevelFilter> {
        Some(LevelFilter::INFO)
    }

    /// We forward events only; a single fixed id is enough since `record`,
    /// `enter` and `exit` are no-ops below.
    fn new_span(&self, _span: &span::Attributes<'_>) -> span::Id {
        span::Id::from_u64(1)
    }

    fn record(&self, _span: &span::Id, _values: &span::Record<'_>) {}

    fn record_follows_from(&self, _span: &span::Id, _follows: &span::Id) {}

    fn event(&self, event: &Event<'_>) {
        let metadata = event.metadata();
        let mut visitor = EventVisitor::default();
        event.record(&mut visitor);
        self.sink.on_record(
            DiagLevel::from(*metadata.level()),
            metadata.target().to_string(),
            visitor.message.unwrap_or_default(),
            visitor.fields,
        );
    }

    fn enter(&self, _span: &span::Id) {}

    fn exit(&self, _span: &span::Id) {}
}

/// Installs `sink` as the process's global `tracing` subscriber (docs/21
/// §2.1). Returns `false` (never panics) if a global default is already
/// set -- `tracing::subscriber::set_global_default`'s one error case.
#[uniffi::export]
pub fn install_diag_sink(sink: Arc<dyn DiagSink>) -> bool {
    tracing::subscriber::set_global_default(DiagSubscriber { sink }).is_ok()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;

    #[derive(Default)]
    struct TestSink {
        records: Mutex<Vec<(DiagLevel, String, String, String)>>,
    }

    impl DiagSink for TestSink {
        fn on_record(&self, level: DiagLevel, target: String, message: String, fields: String) {
            self.records
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .push((level, target, message, fields));
        }
    }

    fn run_with_sink(f: impl FnOnce()) -> Vec<(DiagLevel, String, String, String)> {
        let sink = Arc::new(TestSink::default());
        let subscriber = DiagSubscriber { sink: sink.clone() };
        tracing::subscriber::with_default(subscriber, f);
        let records = sink.records.lock().unwrap_or_else(|e| e.into_inner());
        records.clone()
    }

    #[test]
    fn warn_event_is_forwarded_with_message_and_ordered_fields() {
        let records = run_with_sink(|| {
            tracing::warn!(b = 2, a = 1, "diag test: warn with fields");
        });
        assert_eq!(records.len(), 1);
        let (level, target, message, fields) = &records[0];
        assert_eq!(*level, DiagLevel::Warn);
        assert!(target.contains("diag"));
        assert_eq!(message, "diag test: warn with fields");
        assert_eq!(fields, "b=2 a=1");
    }

    #[test]
    fn info_is_forwarded_only_from_the_sync_target_prefix() {
        let records = run_with_sink(|| {
            tracing::info!(target: "media_cache::sync::delta", "sync pass done");
            tracing::info!(target: "media_cache::mirror", "not a sync line");
            tracing::info!(target: "jellybeam_ffi", "not a sync line either");
            tracing::debug!("never forwarded");
        });
        assert_eq!(records.len(), 1);
        assert_eq!(records[0].1, "media_cache::sync::delta");
        assert_eq!(records[0].2, "sync pass done");
    }

    #[test]
    fn scrub_replaces_a_url_with_an_embedded_api_key_as_one_run() {
        let scrubbed = scrub("see https://example.test:8096/Items?api_key=abc for detail");
        assert_eq!(scrubbed, "see <url> for detail");
    }

    #[test]
    fn scrub_redacts_bare_keys_in_plain_text() {
        let scrubbed = scrub("query had api_key=abc123 and Token=xyz in it");
        assert_eq!(
            scrubbed,
            "query had api_key=<redacted> and Token=<redacted> in it"
        );
    }

    #[test]
    fn text_fields_outside_the_allowlist_are_dropped() {
        let records = run_with_sink(|| {
            tracing::warn!(
                query = "Private Title Person",
                view_id = "view-1",
                title = "some title",
                error = "boom",
                count = 3,
                ok = true,
                "diag test: allowlist"
            );
        });
        let fields = &records[0].3;
        assert!(!fields.contains("Private Title Person"));
        assert!(!fields.contains("view_id"));
        assert!(!fields.contains("title"));
        assert_eq!(fields, "error=other count=3 ok=true");
    }

    /// Pins media-cache/src/query.rs's `search` error path (`error = %e,
    /// query, "search query failed"`): the user's own search text must
    /// never reach the sink, whatever the caller's error message says.
    #[test]
    fn planted_search_query_never_reaches_the_sink() {
        let planted = "Private Title Person";
        let records = run_with_sink(|| {
            let err = std::io::Error::other("boom");
            tracing::error!(error = %err, query = planted, "search query failed");
        });
        let (_, _, message, fields) = &records[0];
        assert!(!message.contains(planted));
        assert!(!fields.contains(planted));
        assert!(!fields.contains("query"));
    }

    #[test]
    fn a_200_char_value_is_cut_to_120_chars_plus_ellipsis() {
        let long = "a".repeat(200);
        let scrubbed = scrub(&long);
        assert_eq!(scrubbed.chars().count(), 121);
        assert!(scrubbed.ends_with('…'));
        assert_eq!(scrubbed.chars().filter(|&c| c == 'a').count(), 120);
    }

    #[test]
    fn record_error_values_are_classified_not_scrubbed() {
        let records = run_with_sink(|| {
            let io_err = std::io::Error::other("fetch failed: https://example.test/api?token=shhh");
            tracing::warn!(
                error = &io_err as &dyn std::error::Error,
                "diag test: io error"
            );
        });
        assert_eq!(records[0].3, "error=other");
    }

    /// Pins docs/21 §2.3: an `ApiError::Status` body can carry a title, a
    /// username, a path and a hostname verbatim, so `error`/`err` must
    /// forward only [`classify_error`]'s label, never the body text.
    #[test]
    fn error_fields_are_classified_never_forwarded() {
        let body = "Private Title / plancklike-user /media/family/x.mkv storeserver.example.test"
            .to_string();
        let err = jellyfin_api::ApiError::Status { code: 500, body };
        let records = run_with_sink(|| {
            tracing::warn!(error = %err, "sync failed");
            tracing::warn!(err = ?err, "sync failed");
        });
        assert_eq!(records.len(), 2);
        for (_, _, _, fields) in &records {
            assert!(!fields.contains("Private Title"));
            assert!(!fields.contains("plancklike-user"));
            assert!(!fields.contains("/media/family/x.mkv"));
            assert!(!fields.contains("storeserver.example.test"));
        }
        assert_eq!(records[0].3, "error=http_500");
        assert_eq!(records[1].3, "err=http_500");
    }

    #[test]
    fn classify_error_covers_the_table() {
        assert_eq!(classify_error("request timed out"), "timeout");
        assert_eq!(classify_error("Timeout while connecting"), "timeout");
        assert_eq!(classify_error("connection refused"), "connection_refused");
        assert_eq!(
            classify_error("connection reset by peer"),
            "connection_reset"
        );
        assert_eq!(classify_error("broken pipe"), "connection_reset");
        assert_eq!(classify_error("unexpected eof"), "connection_reset");
        assert_eq!(classify_error("dns lookup failed"), "dns");
        assert_eq!(classify_error("failed to lookup address"), "dns");
        assert_eq!(classify_error("could not resolve host"), "dns");
        assert_eq!(classify_error("certificate verify failed"), "tls");
        assert_eq!(classify_error("tls handshake error"), "tls");
        assert_eq!(classify_error("ssl error"), "tls");
        assert_eq!(classify_error("server returned 503"), "http_503");
        assert_eq!(classify_error("http status 404: not there"), "http_404");
        assert_eq!(classify_error("request was unauthorized"), "http_401");
        assert_eq!(classify_error("resource not found"), "http_404");
        assert_eq!(classify_error("failed to deserialize json"), "decode");
        assert_eq!(classify_error("decode error"), "decode");
        assert_eq!(classify_error("parse error"), "decode");
        assert_eq!(classify_error("sqlite error: database is locked"), "db");
        assert_eq!(classify_error("constraint violation"), "db");
        assert_eq!(classify_error("no such file or directory"), "io");
        assert_eq!(classify_error("permission denied"), "io");
        assert_eq!(classify_error("read-only file system"), "io");
        assert_eq!(classify_error("disk full"), "io");
        assert_eq!(classify_error("i/o error"), "io");
        assert_eq!(classify_error("operation cancelled"), "cancelled");
        assert_eq!(classify_error("totally unrecognized message"), "other");
    }

    /// Either outcome is fine -- a global default may already be set by
    /// another test in this binary -- the only thing pinned is "no panic".
    #[test]
    fn install_diag_sink_does_not_panic() {
        struct NoopSink;
        impl DiagSink for NoopSink {
            fn on_record(
                &self,
                _level: DiagLevel,
                _target: String,
                _message: String,
                _fields: String,
            ) {
            }
        }
        let installed = install_diag_sink(Arc::new(NoopSink));
        assert!(installed || !installed);
    }
}
