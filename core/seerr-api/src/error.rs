//! `SeerrError` mirrors `jellyfin_api::ApiError`'s four cases so `core/ffi`
//! maps both the same way; never carries a raw secret, only status/transport/decode text.

#[derive(Debug, thiserror::Error)]
pub enum SeerrError {
    #[error("http status {code}{}", if body.is_empty() { String::new() } else { format!(": {body}") })]
    Status { code: u16, body: String },
    #[error("transport: {0}")]
    Transport(String),
    #[error("decode: {0}")]
    Decode(String),
    #[error("unauthorized")]
    Unauthorized,
}
