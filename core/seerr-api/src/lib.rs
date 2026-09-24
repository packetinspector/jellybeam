//! Hand-written REST client for a [Seerr](https://docs.seerr.dev/) server;
//! see `docs/14-seerr-discover.md` for the feature contract. No
//! Android/uniffi dependency (plain reqwest/serde/tokio) so it's reusable
//! across platforms; FFI-facing decision logic lives in
//! `core/ffi/src/seerr.rs`/`seerr_types.rs`, operating on the DTOs exported here.

mod client;
mod error;
mod models;
mod util;

pub mod url;

pub use client::{BrowseFilters, LoginArgs, SeerrAuthMethod, SeerrClient};
pub use error::SeerrError;
pub use models::{
    CreditCast, CreditCrew, MediaInfo, MediaRequest, MediaResult, MovieDetails, PublicSettings,
    RelatedVideo, RequestCreateBody, RequestUpdateBody, RequestUser, Season, ServarrInstance,
    ServiceDetail, TvDetails, User,
};
