//! Session lifecycle, DeviceProfile, playback decision + reporting state
//! machine, WebSocket supervision (reconnect/backoff + reconciliation
//! triggers).

mod backoff;
mod device_profile;
mod event_bus;
mod playback;
mod reporting;

pub use device_profile::{
    allow_ass_sidecars, android_tv_profile, profile_miss, AndroidTvCaps, MissKind, ProfileMiss,
    RawDeviceProfile, Verdict, VideoCodec, VideoCodecCaps, VideoProfileLevel,
};
pub use event_bus::{BusConnection, EventBus, EventBusHandle};
pub use reporting::{
    pending_local_item_ids, supersede, FinalDelivery, ReportFacts, ReportList, ReportingSession,
    Superseded,
};

use jellyfin_api::models::{
    ClientCapabilitiesDto, GeneralCommandType, MediaSourceInfo, MediaType, PlaybackInfoResponse,
};
#[cfg(test)]
use jellyfin_api::models::{MediaStream, MediaStreamType};
use jellyfin_api::{JellyfinClient, ServerEvent};
use tokio::sync::broadcast;

/// Outcome of PlaybackInfo negotiation, normalized for the player + UI (the UI
/// must always be able to show WHY a transcode happened — never silent).
#[derive(Debug, Clone)]
pub enum PlaybackDecision {
    DirectPlay {
        source: MediaSourceInfo,
        url: String,
    },
    Transcode {
        source: MediaSourceInfo,
        hls_url: String,
        /// The static-stream URL for this same `source`, built with
        /// `TranscodingUrl` cleared (see [`decide_playback`]).
        /// docs/18-playback-quality.md §2: a server "would transcode"
        /// verdict is never itself a reason to transcode, so `jellybeam-ffi`
        /// attempts Direct Play against this URL instead of `hls_url`.
        direct_url: String,
        reasons: Vec<String>,
    },
}

/// Picks the best `MediaSource` (see `playback::choose`: DirectPlay/
/// DirectStream beats a TranscodingUrl fallback, first-listed source wins
/// ties) and asks the client to build the playback URL for it.
///
/// `item_id` is threaded separately from each `MediaSourceInfo.id` because
/// plugin/channel sources use a distinct, non-item id (see
/// [`JellyfinClient::stream_url`]).
pub fn decide_playback(
    client: &JellyfinClient,
    item_id: &str,
    info: &PlaybackInfoResponse,
) -> Result<PlaybackDecision, CoreError> {
    let facts: Vec<playback::SourceFacts> = info
        .media_sources
        .iter()
        .map(playback::extract_facts)
        .collect();

    let choice = playback::choose(&facts)?;
    let source = info
        .media_sources
        .get(choice.index())
        .cloned()
        .ok_or(CoreError::NoPlayableSource)?;
    let url = match choice {
        // `stream_url` prefers `TranscodingUrl` when set, but a DirectPlay
        // choice must never resolve to it (a codec-blind source, see
        // `playback::is_codec_blind`, often has both set) -- clear it here
        // so `stream_url` falls through to the static stream form.
        playback::Choice::DirectPlay { .. } if source.transcoding_url.is_some() => {
            let direct_source = MediaSourceInfo {
                transcoding_url: None,
                ..source.clone()
            };
            client.stream_url(item_id, &direct_source)
        }
        _ => client.stream_url(item_id, &source),
    };
    Ok(match choice {
        playback::Choice::DirectPlay { .. } => PlaybackDecision::DirectPlay { source, url },
        playback::Choice::Transcode { reasons, .. } => {
            // Same strip-and-rebuild as the DirectPlay branch; `choose` never
            // returns Transcode for a source without a TranscodingUrl.
            let direct_source = MediaSourceInfo {
                transcoding_url: None,
                ..source.clone()
            };
            let direct_url = client.stream_url(item_id, &direct_source);
            PlaybackDecision::Transcode {
                source,
                hls_url: url,
                direct_url,
                reasons,
            }
        }
    })
}

/// What this TV tells the server about remote control (docs/31 §3, §8).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RemoteControl {
    /// The setting is off: no commands are advertised.
    Off,
    /// On, but the TV's output volume is fixed (HDMI-CEC), so no volume commands (docs/31 §4.4).
    FixedVolume,
    AdjustableVolume,
}

/// Commands the official client handles (docs/31 §3).
const PARITY_COMMANDS: &[GeneralCommandType] = &[
    GeneralCommandType::DisplayContent,
    GeneralCommandType::SetSubtitleStreamIndex,
    GeneralCommandType::SetAudioStreamIndex,
    GeneralCommandType::DisplayMessage,
    GeneralCommandType::SendString,
];

/// Offered only while `RemoteControl::AdjustableVolume` (docs/31 §4.4).
const VOLUME_COMMANDS: &[GeneralCommandType] = &[
    GeneralCommandType::VolumeUp,
    GeneralCommandType::VolumeDown,
    GeneralCommandType::SetVolume,
    GeneralCommandType::Mute,
    GeneralCommandType::Unmute,
    GeneralCommandType::ToggleMute,
];

/// docs/31 §4.5. Trailers, Live TV, `SetMaxStreamingBitrate` and `TakeScreenshot` stay out until they ship.
const BEYOND_PARITY_COMMANDS: &[GeneralCommandType] = &[
    GeneralCommandType::MoveUp,
    GeneralCommandType::MoveDown,
    GeneralCommandType::MoveLeft,
    GeneralCommandType::MoveRight,
    GeneralCommandType::Select,
    GeneralCommandType::Back,
    GeneralCommandType::PageUp,
    GeneralCommandType::PageDown,
    GeneralCommandType::PreviousLetter,
    GeneralCommandType::NextLetter,
    GeneralCommandType::GoHome,
    GeneralCommandType::GoToSearch,
    GeneralCommandType::GoToSettings,
    GeneralCommandType::ToggleContextMenu,
    GeneralCommandType::ToggleOsd,
    GeneralCommandType::ToggleOsdMenu,
    GeneralCommandType::ToggleFullscreen,
    GeneralCommandType::ToggleStats,
    GeneralCommandType::SetRepeatMode,
    GeneralCommandType::SetShuffleQueue,
    GeneralCommandType::SetPlaybackOrder,
    GeneralCommandType::PlayMediaSource,
];

/// docs/31 §3: the body of `POST /Sessions/Capabilities/Full`.
pub fn capabilities(mode: RemoteControl) -> ClientCapabilitiesDto {
    let supported_commands = match mode {
        RemoteControl::Off => Vec::new(),
        RemoteControl::FixedVolume => [PARITY_COMMANDS, BEYOND_PARITY_COMMANDS].concat(),
        RemoteControl::AdjustableVolume => {
            [PARITY_COMMANDS, VOLUME_COMMANDS, BEYOND_PARITY_COMMANDS].concat()
        }
    };
    ClientCapabilitiesDto {
        playable_media_types: vec![MediaType::Video, MediaType::Audio],
        supported_commands,
        supports_media_control: Some(mode != RemoteControl::Off),
        ..Default::default()
    }
}

#[derive(Debug, Clone)]
pub struct ReportContext {
    pub item_id: String,
    pub media_source_id: String,
    pub play_session_id: String,
    /// docs/18-playback-quality.md §2: threaded onto every Start/Progress
    /// report; ignored for Stopped (no `PlayMethod` field). Set once at
    /// [`ReportingSession::start`] -- a play-method change mid-session
    /// starts a fresh `ReportingSession` instead.
    pub play_method: jellyfin_api::ReportPlayMethod,
    pub media: ReportMedia,
    /// docs/31 §4.4: reads the system volume as each Start/Progress is built; `None` reports full
    /// and unmuted.
    pub volume: Option<VolumeSource>,
}

/// docs/31 §4.4: the system stream's volume, `level` 0-100.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Volume {
    pub level: u8,
    pub muted: bool,
}

/// docs/31 §4.4: a reader of the current [`Volume`], called on the reporting actor's thread with
/// no lock held.
#[derive(Clone)]
pub struct VolumeSource(pub std::sync::Arc<dyn Fn() -> Volume + Send + Sync>);

impl std::fmt::Debug for VolumeSource {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("VolumeSource")
    }
}

/// docs/feature-dev/music-design-brief.md §1.3: what a session plays. Audio names its queue on
/// every Start/Progress and keeps no resume position (the server keeps none for songs).
#[derive(Debug, Clone)]
pub enum ReportMedia {
    Video,
    /// `queue`: the queue's item ids in play order, the playing track among them.
    Audio {
        queue: std::sync::Arc<[String]>,
    },
}

impl ReportMedia {
    /// Whether its stop leaves a resume position: video's does; a song's never does (handoff Q7).
    pub fn keeps_resume_position(&self) -> bool {
        matches!(self, Self::Video)
    }

    /// The `NowPlayingQueue` its reports carry; empty for video.
    pub(crate) fn now_playing_queue(&self) -> std::sync::Arc<[String]> {
        match self {
            Self::Video => std::sync::Arc::default(),
            Self::Audio { queue } => queue.clone(),
        }
    }
}

/// Forwarded server events plus the reconcile trigger emitted by [`EventBus::spawn`]; connection
/// state is its [`BusConnection`] watch. media-cache listens for `NeedsReconcile` to resync.
#[derive(Debug, Clone)]
pub enum BusEvent {
    Server(ServerEvent),
    /// Emitted after every (re)connect — reconciliation trigger.
    NeedsReconcile,
}

/// Receives the next [`BusEvent`], treating a lagged consumer as a
/// reconciliation trigger instead of silently going dead.
///
/// All `EventBus` consumers must use this instead of calling `.recv()`
/// directly: a bare `while let Ok(event) = rx.recv().await` treats
/// `Err(Lagged(n))` as a dead channel and silently exits. This maps
/// `Lagged` to [`BusEvent::NeedsReconcile`] instead, and only returns
/// `None` on a genuine `Closed`.
pub async fn recv_bus(rx: &mut broadcast::Receiver<BusEvent>) -> Option<BusEvent> {
    match rx.recv().await {
        Ok(event) => Some(event),
        Err(broadcast::error::RecvError::Lagged(skipped)) => {
            tracing::warn!(
                skipped,
                "event bus consumer lagged behind the broadcast channel; \
                 treating as NeedsReconcile instead of ending the stream"
            );
            Some(BusEvent::NeedsReconcile)
        }
        Err(broadcast::error::RecvError::Closed) => None,
    }
}

#[derive(Debug, thiserror::Error)]
pub enum CoreError {
    #[error(transparent)]
    Api(#[from] jellyfin_api::ApiError),
    #[error("no playable media source")]
    NoPlayableSource,
}

#[cfg(test)]
mod tests {
    use super::*;

    // The decision matrix itself is tested in `playback::tests`; these tests
    // prove the public function (including the real, local `stream_url` call)
    // is wired end to end.

    fn client() -> JellyfinClient {
        JellyfinClient::from_token(
            "http://localhost:8096",
            jellyfin_api::ClientIdentity {
                client: "Jellybeam".into(),
                device: "test".into(),
                device_id: "test-device".into(),
                version: "0.1.0".into(),
            },
            "test-token",
        )
    }

    #[test]
    fn decide_playback_end_to_end_direct_play() {
        let info = PlaybackInfoResponse {
            media_sources: vec![MediaSourceInfo {
                id: Some("src-1".into()),
                supports_direct_play: Some(true),
                ..Default::default()
            }],
            ..Default::default()
        };
        match decide_playback(&client(), "src-1", &info).expect("test assertion") {
            PlaybackDecision::DirectPlay { url, .. } => {
                assert!(url.contains("/Videos/src-1/stream"), "{url}");
            }
            other => panic!("expected DirectPlay, got {other:?}"),
        }
    }

    /// Pins: a plugin/channel source whose `MediaSourceInfo.id` differs from
    /// the item id still builds a stream URL rooted at the item id.
    #[test]
    fn decide_playback_end_to_end_uses_item_id_when_source_id_differs() {
        let info = PlaybackInfoResponse {
            media_sources: vec![MediaSourceInfo {
                id: Some("ab12cd34".into()),
                supports_direct_play: Some(true),
                ..Default::default()
            }],
            ..Default::default()
        };
        match decide_playback(&client(), "item-real-1", &info).expect("test assertion") {
            PlaybackDecision::DirectPlay { url, .. } => {
                assert!(
                    url.contains("/Videos/item-real-1/stream"),
                    "path segment should be the item id, not the source id: {url}"
                );
                assert!(
                    url.contains("mediaSourceId=ab12cd34"),
                    "mediaSourceId query param should still be the source id: {url}"
                );
            }
            other => panic!("expected DirectPlay, got {other:?}"),
        }
    }

    /// A source with real codec facts the server genuinely can't direct play.
    fn incompatible_codec_streams() -> Vec<MediaStream> {
        vec![
            MediaStream {
                type_: Some(MediaStreamType::Video),
                codec: Some("wmv3".into()),
                ..Default::default()
            },
            MediaStream {
                type_: Some(MediaStreamType::Audio),
                codec: Some("wmav2".into()),
                ..Default::default()
            },
        ]
    }

    #[test]
    fn decide_playback_end_to_end_transcode() {
        let info = PlaybackInfoResponse {
            media_sources: vec![MediaSourceInfo {
                id: Some("src-1".into()),
                container: Some("wmv".into()),
                transcoding_url: Some("/videos/src-1/master.m3u8".into()),
                media_streams: incompatible_codec_streams(),
                ..Default::default()
            }],
            ..Default::default()
        };
        match decide_playback(&client(), "src-1", &info).expect("test assertion") {
            PlaybackDecision::Transcode {
                hls_url,
                direct_url,
                reasons,
                ..
            } => {
                assert!(hls_url.ends_with("/videos/src-1/master.m3u8"), "{hls_url}");
                assert!(!reasons.is_empty());
                // docs/18-playback-quality.md §2: `direct_url` is the same source's static-stream
                // URL, never HLS.
                assert!(direct_url.contains("/Videos/src-1/stream"), "{direct_url}");
                assert!(!direct_url.contains("master.m3u8"), "{direct_url}");
            }
            other => panic!("expected Transcode, got {other:?}"),
        }
    }

    #[test]
    fn decide_playback_end_to_end_no_playable_source() {
        let info = PlaybackInfoResponse::default();
        assert!(matches!(
            decide_playback(&client(), "item-1", &info).expect_err("test assertion"),
            CoreError::NoPlayableSource
        ));
    }

    // --- capabilities ---------------------------------------------

    /// The wire body of `capabilities(mode)`, so the pinned names are the JSON the server reads.
    fn capabilities_body(mode: RemoteControl) -> serde_json::Value {
        serde_json::to_value(capabilities(mode)).expect("capabilities serialize")
    }

    fn sorted_commands(body: &serde_json::Value) -> Vec<String> {
        let mut names: Vec<String> = body["SupportedCommands"]
            .as_array()
            .map(|a| {
                a.iter()
                    .filter_map(|n| n.as_str().map(str::to_string))
                    .collect()
            })
            .unwrap_or_default();
        names.sort();
        names
    }

    fn expected_commands(names: &[&str]) -> Vec<String> {
        let mut names: Vec<String> = names.iter().map(|n| n.to_string()).collect();
        names.sort();
        names
    }

    const PARITY_NAMES: [&str; 5] = [
        "DisplayContent",
        "SetSubtitleStreamIndex",
        "SetAudioStreamIndex",
        "DisplayMessage",
        "SendString",
    ];
    const VOLUME_NAMES: [&str; 6] = [
        "VolumeUp",
        "VolumeDown",
        "SetVolume",
        "Mute",
        "Unmute",
        "ToggleMute",
    ];
    const BEYOND_PARITY_NAMES: [&str; 22] = [
        "MoveUp",
        "MoveDown",
        "MoveLeft",
        "MoveRight",
        "Select",
        "Back",
        "PageUp",
        "PageDown",
        "PreviousLetter",
        "NextLetter",
        "GoHome",
        "GoToSearch",
        "GoToSettings",
        "ToggleContextMenu",
        "ToggleOsd",
        "ToggleOsdMenu",
        "ToggleFullscreen",
        "ToggleStats",
        "SetRepeatMode",
        "SetShuffleQueue",
        "SetPlaybackOrder",
        "PlayMediaSource",
    ];

    /// docs/31 §3 table: video and audio, media control on, parity plus beyond-parity commands,
    /// and no volume commands while the TV's volume is fixed.
    #[test]
    fn fixed_volume_advertises_everything_but_the_volume_commands() {
        let body = capabilities_body(RemoteControl::FixedVolume);
        assert_eq!(
            body["PlayableMediaTypes"],
            serde_json::json!(["Video", "Audio"])
        );
        assert_eq!(body["SupportsMediaControl"], serde_json::json!(true));
        let want: Vec<&str> = PARITY_NAMES
            .iter()
            .chain(&BEYOND_PARITY_NAMES)
            .copied()
            .collect();
        assert_eq!(sorted_commands(&body), expected_commands(&want));
    }

    #[test]
    fn adjustable_volume_adds_the_six_volume_commands() {
        let body = capabilities_body(RemoteControl::AdjustableVolume);
        assert_eq!(
            body["PlayableMediaTypes"],
            serde_json::json!(["Video", "Audio"])
        );
        assert_eq!(body["SupportsMediaControl"], serde_json::json!(true));
        let want: Vec<&str> = PARITY_NAMES
            .iter()
            .chain(&VOLUME_NAMES)
            .chain(&BEYOND_PARITY_NAMES)
            .copied()
            .collect();
        assert_eq!(sorted_commands(&body), expected_commands(&want));
    }

    /// docs/31 §8: off posts media control false and no commands.
    #[test]
    fn off_advertises_no_media_control_and_no_commands() {
        let body = capabilities_body(RemoteControl::Off);
        assert_eq!(body["SupportsMediaControl"], serde_json::json!(false));
        assert!(sorted_commands(&body).is_empty(), "{body}");
        assert_eq!(
            body["PlayableMediaTypes"],
            serde_json::json!(["Video", "Audio"])
        );
    }

    // --- recv_bus -------------------------------------------------

    #[tokio::test]
    async fn recv_bus_passes_through_ordinary_events() {
        let (tx, mut rx) = broadcast::channel(8);
        tx.send(BusEvent::Server(ServerEvent::ForceKeepAlive))
            .expect("send");
        match recv_bus(&mut rx).await {
            Some(BusEvent::Server(ServerEvent::ForceKeepAlive)) => {}
            other => panic!("expected the server event, got {other:?}"),
        }
    }

    #[tokio::test]
    async fn recv_bus_maps_lagged_to_needs_reconcile_instead_of_ending_the_stream() {
        // Capacity 2, send 5 -- the receiver (created before any sends,
        // subscribed to the same channel) is guaranteed to lag.
        let (tx, mut rx) = broadcast::channel(2);
        for _ in 0..5 {
            let _ = tx.send(BusEvent::Server(ServerEvent::ForceKeepAlive));
        }
        match recv_bus(&mut rx).await {
            Some(BusEvent::NeedsReconcile) => {}
            other => panic!("expected NeedsReconcile on lag, got {other:?}"),
        }
        // The stream is not over; a bare `while let Ok(e) = rx.recv().await` would have silently
        // ended here.
        match recv_bus(&mut rx).await {
            Some(BusEvent::Server(ServerEvent::ForceKeepAlive)) => {}
            other => panic!("expected the stream to continue after lag, got {other:?}"),
        }
    }

    #[tokio::test]
    async fn recv_bus_returns_none_on_closed() {
        let (tx, mut rx) = broadcast::channel::<BusEvent>(8);
        drop(tx);
        assert!(recv_bus(&mut rx).await.is_none());
    }
}
