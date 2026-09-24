//! Pure playback decision logic.
//!
//! Pure state + arithmetic only, no I/O/UI/player backend/network, aside from `jellyfin-api`'s
//! plain-data `MediaSegmentDto`/`MediaSegmentType`; preference persistence and driving a real
//! player/UI toolkit are left to each platform.
//!
//! - [`tracks`][]: audio/subtitle track selection ([`tracks::resolve_track_selection`]).
//! - [`segments`][]: media-segment skip decisions and credits-aware next-episode timing.
//! - [`prefs`][]: preference/settings types consumed by the above.
//! - [`glide`][]: hold-to-seek state machine/ramp ([`glide::Glide`], [`glide::rate_at`],
//!   [`glide::travel_ms`]) -- `docs/feature-dev/PRD-hold-to-seek.md`.
//! - [`still_watching`][]: autoplay inactivity guard ([`still_watching::InactivityState`]),
//!   distinct from the next-up countdown.
//! - [`time`][]: tick/second conversion.
//! - [`trickplay`][]: scrub-preview tile geometry ([`trickplay::resolve_trickplay_meta`],
//!   [`trickplay::locate`]).

pub mod glide;
pub mod prefs;
pub mod segments;
pub mod still_watching;
pub mod time;
pub mod tracks;
pub mod trickplay;
