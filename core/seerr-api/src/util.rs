//! Mirrors `jellyfin-api/src/util.rs`'s `describe_error_chain`: reqwest's
//! `Error::to_string()` is often generic, so walking `.source()` surfaces the real DNS/TLS/IO
//! cause.

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
