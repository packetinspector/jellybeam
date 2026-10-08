//! `CoreError` -- the single error type every fallible `JellybeamCore` method
//! surfaces across the UniFFI boundary. Wraps underlying error types via
//! `Display` only, never raw fields, so an upstream field added later can't
//! silently leak something sensitive into a message shown here.

/// FFI-facing error type: a few "core state" variants plus catch-alls that
/// carry the underlying error's `Display` text.
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum CoreError {
    /// A method requiring an authenticated client was called before
    /// [`crate::JellybeamCore::sign_in`]/[`crate::JellybeamCore::restore_session`].
    #[error("not signed in")]
    NotSignedIn,
    /// A method requiring an open mirror was called before
    /// [`crate::JellybeamCore::open_mirror`] succeeded.
    #[error("mirror not open")]
    MirrorNotOpen,
    /// The saved access token was rejected; kept distinct from a generic API
    /// failure so Android can route straight into reauthorization. `account`
    /// names the Jellyfin account it was issued to (docs/18 §2.1); `None` when no token named
    /// one: Seerr's, or a public call's.
    #[error("authorization expired")]
    Unauthorized {
        account: Option<crate::types::AccountIdentity>,
    },
    /// A `jellyfin-api` call failed; `detail` is its `Display` text only.
    #[error("api error: {detail}")]
    Api { detail: String },
    /// A `media-cache` call failed; `detail` is its `Display` text.
    #[error("cache error: {detail}")]
    Cache { detail: String },
    /// docs/18-playback-quality.md §2: the `PlaybackQuality::DirectPlay`
    /// setting forbids a transcode that would otherwise happen, raised by
    /// [`crate::JellybeamCore::prepare_playback`] (server negotiated
    /// `Transcode`) or [`crate::JellybeamCore::prepare_transcode_fallback`]
    /// (local player already failed). Per CLAUDE.md, Direct Play is the
    /// default and transcode is opt-in, so the UI must show why. Field is
    /// `reasons`, not `message`: a uniffi `Error` field named `message`
    /// collides with `kotlin.Throwable.message`.
    #[error("would transcode: {reasons}")]
    WouldTranscode { reasons: String },
    /// [`crate::JellybeamCore::switch_session`] got an `index` outside
    /// `0..list_accounts().len()`. Field is `index`, not `message` (same
    /// uniffi/Throwable collision as [`Self::WouldTranscode`]).
    #[error("no session at index {index}")]
    InvalidSessionIndex { index: u32 },
    /// A `seerr_*` method other than status/connect was called with no saved
    /// Seerr connection (docs/14-seerr-discover.md); Kotlin routes this to
    /// Discover settings instead of a raw network error.
    #[error("seerr not configured")]
    SeerrNotConfigured,
    /// docs/18-playback-quality.md §2 fallback race: the session
    /// [`crate::JellybeamCore::prepare_transcode_fallback`] was started for was
    /// replaced/stopped before or during negotiation. Kotlin ignores it --
    /// playback already moved on, which is what it wants.
    #[error("playback session is no longer current")]
    StalePlaybackSession,
    /// docs/18 §2.1: the playback request was minted under an account epoch that is no longer
    /// current; distinct from [`Self::StalePlaybackSession`] so a missed epoch refresh is visible.
    #[error("playback request belongs to a previous account")]
    AccountChanged,
    /// docs/13 "sign-in": the entered address itself is unusable (empty,
    /// unsupported scheme, no host) -- caught before any request is sent.
    #[error("invalid server address: {detail}")]
    InvalidServerAddress { detail: String },
    /// docs/13 "sign-in": the request never reached a server (refused, DNS
    /// failure, timeout) -- distinct from [`Self::NotJellyfinServer`], which
    /// did get a response.
    #[error("server unreachable: {host}: {reason:?} {detail}")]
    ServerUnreachable {
        host: String,
        reason: UnreachableReason,
        /// The transport's own text, for [`UnreachableReason::Other`] only (else empty).
        detail: String,
    },
    /// docs/13 "sign-in": the TLS handshake failed the way it does when
    /// `https://` is pointed at a plain-http Jellyfin server.
    #[error("https not offered by {host}")]
    HttpsNotOffered { host: String },
    /// docs/13 "sign-in": got an HTTP response, but not one a Jellyfin
    /// server would send (wrong port/app, or a non-2xx status).
    #[error("not a jellyfin server: {host} (http {status})")]
    NotJellyfinServer { host: String, status: u16 },
    /// docs/13 "sign-in": a first sign-in's username/password (or Quick
    /// Connect secret) was rejected -- distinct from [`Self::Unauthorized`],
    /// which is a saved token needing reauthorization, not a fresh attempt.
    #[error("invalid credentials")]
    InvalidCredentials,
    /// docs/14-seerr-discover.md: the Seerr server answered a connect with
    /// 401/403, so the address is right and `method`'s identity or secret is
    /// wrong (a Seerr local account signs in with its email address).
    #[error("seerr rejected the {method:?} credentials")]
    SeerrInvalidCredentials {
        method: crate::seerr_types::SeerrAuthMethod,
    },
    /// docs/14-seerr-discover.md "Auth": the Seerr server answered a connect
    /// with its own error body, so the address is right but Seerr cannot sign
    /// this account in for `reason`; `detail` is Seerr's message, capped.
    #[error("seerr refused the {method:?} sign-in: {reason:?} {detail}")]
    SeerrSignInRefused {
        method: crate::seerr_types::SeerrAuthMethod,
        reason: crate::seerr_types::SeerrRefusal,
        detail: String,
    },
    /// docs/14-seerr-discover.md: a Seerr request never got an answer; Kotlin
    /// words `reason` (docs/27 §5) rather than showing transport text.
    #[error("seerr unreachable: {reason:?}")]
    SeerrUnreachable { reason: UnreachableReason },
    /// docs/26: the updater's working directory can't be created or opened.
    #[error("update storage unavailable")]
    UpdateStorageUnavailable,
}

/// docs/27 §5: why [`CoreError::ServerUnreachable`]'s request never got an answer; Kotlin words it.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum UnreachableReason {
    NameNotResolved,
    ConnectionRefused,
    TimedOut,
    NetworkUnreachable,
    Other,
}

impl From<jellyfin_api::ApiError> for CoreError {
    fn from(err: jellyfin_api::ApiError) -> Self {
        match err {
            jellyfin_api::ApiError::Unauthorized { owner } => CoreError::Unauthorized {
                account: owner.map(Into::into),
            },
            other => CoreError::Api {
                detail: other.to_string(),
            },
        }
    }
}

impl From<media_cache::CacheError> for CoreError {
    fn from(err: media_cache::CacheError) -> Self {
        CoreError::Cache {
            detail: err.to_string(),
        }
    }
}

/// Mapped through `Display` only, same as the `ApiError` impl above, except a
/// transport failure, which becomes a typed [`CoreError::SeerrUnreachable`].
impl From<seerr_api::SeerrError> for CoreError {
    fn from(err: seerr_api::SeerrError) -> Self {
        match err {
            seerr_api::SeerrError::Unauthorized => CoreError::Unauthorized { account: None },
            seerr_api::SeerrError::Transport(msg) => CoreError::SeerrUnreachable {
                reason: crate::signin::unreachable_reason(&msg),
            },
            other => CoreError::Api {
                detail: other.to_string(),
            },
        }
    }
}

/// Both `jellyfin_core::CoreError` cases fold into [`CoreError::Api`]; no
/// state here is worth a distinct variant.
impl From<jellyfin_core::CoreError> for CoreError {
    fn from(err: jellyfin_core::CoreError) -> Self {
        match err {
            jellyfin_core::CoreError::Api(api) => api.into(),
            other => CoreError::Api {
                detail: other.to_string(),
            },
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn api_error_maps_through_display_text() {
        let api_err = jellyfin_api::ApiError::Status {
            code: 404,
            body: "not found".to_string(),
        };
        let core_err: CoreError = api_err.into();
        match core_err {
            CoreError::Api { detail } => {
                assert!(detail.contains("404"), "detail was {detail:?}");
                assert!(detail.contains("not found"), "detail was {detail:?}");
            }
            other => panic!("expected CoreError::Api, got {other:?}"),
        }
    }

    #[test]
    fn api_unauthorized_maps_to_typed_authorization_error_naming_its_account() {
        let owner = jellyfin_api::TokenOwner {
            server_url: "http://a.test".to_string(),
            user_id: "u-a".to_string(),
        };
        let core_err: CoreError =
            jellyfin_api::ApiError::Unauthorized { owner: Some(owner) }.into();
        let CoreError::Unauthorized { account } = core_err else {
            panic!("expected Unauthorized, got {core_err:?}");
        };
        assert_eq!(
            account,
            Some(crate::types::AccountIdentity {
                server_url: "http://a.test".to_string(),
                user_id: "u-a".to_string(),
            })
        );
    }

    #[test]
    fn cache_error_maps_through_display_text() {
        let cache_err = media_cache::CacheError::Db("disk full".to_string());
        let core_err: CoreError = cache_err.into();
        match core_err {
            CoreError::Cache { detail } => {
                assert!(detail.contains("disk full"), "detail was {detail:?}");
            }
            other => panic!("expected CoreError::Cache, got {other:?}"),
        }
    }

    #[test]
    fn seerr_transport_error_maps_to_typed_unreachable_without_its_text() {
        let core_err: CoreError = seerr_api::SeerrError::Transport(
            "error sending request for url (http://seerr.test:5056/api/v1/auth/local): \
             client error (Connect): tcp connect error: Connection refused (os error 111)"
                .to_string(),
        )
        .into();
        assert!(matches!(
            core_err,
            CoreError::SeerrUnreachable {
                reason: UnreachableReason::ConnectionRefused
            }
        ));
    }

    #[test]
    fn not_signed_in_and_mirror_not_open_display_distinct_messages() {
        assert_eq!(CoreError::NotSignedIn.to_string(), "not signed in");
        assert_eq!(CoreError::MirrorNotOpen.to_string(), "mirror not open");
    }

    #[test]
    fn jellyfin_core_preserves_typed_unauthorized_error() {
        let core_err: CoreError =
            jellyfin_core::CoreError::Api(jellyfin_api::ApiError::Unauthorized { owner: None })
                .into();
        assert!(matches!(
            core_err,
            CoreError::Unauthorized { account: None }
        ));
    }

    #[test]
    fn would_transcode_display_includes_the_reasons() {
        let err = CoreError::WouldTranscode {
            reasons: "decoder error: DECODER_ERROR_UNSUPPORTED_TYPE".to_string(),
        };
        let text = err.to_string();
        assert!(text.contains("transcode"), "text was {text:?}");
        assert!(
            text.contains("decoder error: DECODER_ERROR_UNSUPPORTED_TYPE"),
            "text was {text:?}"
        );
    }

    #[test]
    fn seerr_error_maps_through_display_text() {
        let seerr_err = seerr_api::SeerrError::Status {
            code: 404,
            body: "not found".to_string(),
        };
        let core_err: CoreError = seerr_err.into();
        match core_err {
            CoreError::Api { detail } => {
                assert!(detail.contains("404"), "detail was {detail:?}");
            }
            other => panic!("expected CoreError::Api, got {other:?}"),
        }
    }

    #[test]
    fn seerr_unauthorized_maps_to_typed_authorization_error() {
        let core_err: CoreError = seerr_api::SeerrError::Unauthorized.into();
        assert!(matches!(
            core_err,
            CoreError::Unauthorized { account: None }
        ));
    }

    #[test]
    fn seerr_not_configured_has_a_stable_message() {
        assert_eq!(
            CoreError::SeerrNotConfigured.to_string(),
            "seerr not configured"
        );
    }

    #[test]
    fn jellyfin_core_no_playable_source_maps_through_display_text() {
        let core_err: CoreError = jellyfin_core::CoreError::NoPlayableSource.into();
        match core_err {
            CoreError::Api { detail } => {
                assert!(detail.contains("no playable"), "detail was {detail:?}");
            }
            other => panic!("expected CoreError::Api, got {other:?}"),
        }
    }
}
