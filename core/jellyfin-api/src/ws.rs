//! WebSocket client: connects to `/socket`, decodes the server events Jellybeam cares about into
//! [`crate::ServerEvent`], and responds to `ForceKeepAlive` so the server doesn't drop the
//! connection.
//! One connection per [`crate::JellyfinClient::connect_ws`] call, no auto-reconnect -- that's
//! jellyfin-core's job, observing the returned channel closing.
//! Auth: the token travels in the handshake's `Authorization: MediaBrowser ...` header (Jellyfin 12
//! disables the old `?api_key=` query param by default). `deviceId` stays a query param since
//! there's no header equivalent and the server needs it to route the connection.

use std::time::Duration;

use futures_util::{SinkExt, StreamExt};
use tokio::sync::mpsc;
use tokio_tungstenite::tungstenite::client::ClientRequestBuilder;
use tokio_tungstenite::tungstenite::http::Uri;
use tokio_tungstenite::tungstenite::Message;

use crate::models;
use crate::syncplay::SyncPlayEvent;
use crate::util::{describe_error_chain, percent_encode};
use crate::{auth_header, ApiError, ClientIdentity, ServerEvent};

const EVENT_CHANNEL_CAPACITY: usize = 256;

/// `ForceKeepAlive` interval assumed until the server advertises one (docs/07 Watch state).
const DEFAULT_KEEPALIVE_SECS: u64 = 60;

/// Upper bound on the read deadline so an absurd advertised value can't disable dead-socket detection.
const MAX_READ_DEADLINE_SECS: u64 = 600;

/// Silence budget: twice the advertised `ForceKeepAlive` interval plus 10 s, because a dead 12.0
/// session goes silent without a close (docs/07 Watch state).
pub(crate) fn read_deadline(advertised_secs: Option<u64>) -> Duration {
    let base = match advertised_secs {
        Some(n) if n > 0 => n,
        _ => DEFAULT_KEEPALIVE_SECS,
    };
    Duration::from_secs(
        base.saturating_mul(2)
            .saturating_add(10)
            .min(MAX_READ_DEADLINE_SECS),
    )
}

/// Timeout for establishing the WebSocket connection: without one, a stalled TCP/TLS/HTTP-upgrade
/// handshake against an unreachable server hangs `connect_ws` forever. Bounds only the handshake,
/// not the connection's lifetime.
const WS_CONNECT_TIMEOUT: Duration = Duration::from_secs(10);

/// Rewrite an `http(s)://` base URL into the matching `ws(s)://` form.
fn ws_base(base_url: &str) -> String {
    if let Some(rest) = base_url.strip_prefix("https://") {
        format!("wss://{rest}")
    } else if let Some(rest) = base_url.strip_prefix("http://") {
        format!("ws://{rest}")
    } else {
        // Already scheme-less or an unrecognized scheme; assume plaintext.
        format!("ws://{base_url}")
    }
}

pub(crate) async fn connect(
    base_url: &str,
    identity: &ClientIdentity,
    token: &str,
) -> Result<mpsc::Receiver<ServerEvent>, ApiError> {
    let url = format!(
        "{}/socket?deviceId={}",
        ws_base(base_url),
        percent_encode(&identity.device_id),
    );
    let uri: Uri = url
        .parse()
        .map_err(|e| ApiError::Transport(format!("invalid websocket URL: {e}")))?;
    let request = ClientRequestBuilder::new(uri)
        .with_header("Authorization", auth_header(identity, Some(token)));

    let connect_result = tokio::time::timeout(
        WS_CONNECT_TIMEOUT,
        tokio_tungstenite::connect_async(request),
    )
    .await
    .map_err(|_elapsed| {
        ApiError::Transport(format!(
            "websocket connect timed out after {WS_CONNECT_TIMEOUT:?}"
        ))
    })?;

    let (stream, _response) = connect_result.map_err(map_connect_error)?;

    let (mut write, mut read) = stream.split();
    let (tx, rx) = mpsc::channel(EVENT_CHANNEL_CAPACITY);

    tokio::spawn(async move {
        let mut advertised: Option<u64> = None;
        let mut budget = read_deadline(advertised);
        let deadline = tokio::time::sleep(budget);
        tokio::pin!(deadline);
        loop {
            // Race the next server frame against the receiver being dropped, rather than only
            // checking after `tx.send()`: otherwise a dropped receiver on an idle connection isn't
            // noticed until the next server message, leaking the task and socket.
            tokio::select! {
                biased;
                _ = tx.closed() => {
                    tracing::debug!("jellyfin websocket receiver dropped; closing");
                    let _ = write.send(Message::Close(None)).await;
                    break;
                }
                _ = &mut deadline => {
                    tracing::info!(
                        deadline_secs = budget.as_secs(),
                        "jellyfin websocket silent past its read deadline; closing"
                    );
                    break;
                }
                next = read.next() => {
                    let Some(next) = next else { break };
                    let msg = match next {
                        Ok(m) => m,
                        Err(e) => {
                            tracing::debug!(error = %e, "jellyfin websocket read error; closing");
                            break;
                        }
                    };
                    let text = match msg {
                        Message::Text(t) => t,
                        Message::Close(_) => break,
                        Message::Ping(_) | Message::Pong(_) | Message::Binary(_) | Message::Frame(_) => {
                            continue
                        }
                    };

                    let Some((event, keep_alive)) = decode_frame(&text) else {
                        // Undecodable text is still liveness.
                        budget = read_deadline(advertised);
                        deadline.as_mut().reset(tokio::time::Instant::now() + budget);
                        continue;
                    };
                    if keep_alive.is_some() {
                        advertised = keep_alive;
                    }

                    // ForceKeepAlive means "reply or I'll drop you"; ack immediately in addition to
                    // surfacing the event.
                    if matches!(event, ServerEvent::ForceKeepAlive) {
                        let ack = Message::Text(r#"{"MessageType":"KeepAlive"}"#.to_string());
                        if let Err(e) = write.send(ack).await {
                            tracing::debug!(error = %e, "failed to send KeepAlive; closing");
                            break;
                        }
                    }

                    if tx.send(event).await.is_err() {
                        // Receiver dropped between the read completing and here; still close
                        // cleanly.
                        let _ = write.send(Message::Close(None)).await;
                        break;
                    }

                    // Reset only after the awaits above: a blocked consumer must not eat the budget
                    // of a healthy socket. Only text frames count; ping/pong can outlive a dead session.
                    budget = read_deadline(advertised);
                    deadline.as_mut().reset(tokio::time::Instant::now() + budget);
                }
            }
        }
    });

    Ok(rx)
}

/// docs/18 §2.1: a rejected socket token (401, or Jellyfin 12's 403 -- no role-based 403 exists on
/// the upgrade) is [`ApiError::Unauthorized`], so the bus stops instead of reconnecting.
fn map_connect_error(e: tokio_tungstenite::tungstenite::Error) -> ApiError {
    if let tokio_tungstenite::tungstenite::Error::Http(response) = &e {
        if matches!(response.status().as_u16(), 401 | 403) {
            return ApiError::Unauthorized { owner: None };
        }
    }
    ApiError::Transport(describe_error_chain(&e))
}

/// Decode one raw WebSocket text frame into a [`ServerEvent`].
/// Returns `None` only when the frame isn't valid JSON. A recognized `MessageType` whose `Data`
/// fails to decode still yields `Some` (falls back to `Ignored`) rather than dropping the frame.
#[cfg(any(test, fuzzing))]
pub(crate) fn decode_message(text: &str) -> Option<ServerEvent> {
    decode_frame(text).map(|(event, _)| event)
}

/// [`decode_message`] plus the `ForceKeepAlive` `Data` seconds, parsed in the same pass.
fn decode_frame(text: &str) -> Option<(ServerEvent, Option<u64>)> {
    let value: serde_json::Value = serde_json::from_str(text).ok()?;
    let message_type = value.get("MessageType").and_then(|v| v.as_str())?;

    let mut keep_alive = None;
    let event = match message_type {
        "LibraryChanged" => {
            let data = value
                .get("Data")
                .cloned()
                .unwrap_or(serde_json::Value::Null);
            match serde_json::from_value::<models::LibraryUpdateInfo>(data) {
                Ok(info) => ServerEvent::LibraryChanged {
                    added: info.items_added,
                    updated: info.items_updated,
                    removed: info.items_removed,
                },
                Err(e) => {
                    tracing::debug!(error = %e, "failed to decode LibraryUpdateInfo");
                    ServerEvent::Ignored(message_type.to_string())
                }
            }
        }
        "UserDataChanged" => {
            let data = value
                .get("Data")
                .cloned()
                .unwrap_or(serde_json::Value::Null);
            match serde_json::from_value::<models::UserDataChangeInfo>(data) {
                Ok(info) => {
                    let item_userdata = info
                        .user_data_list
                        .into_iter()
                        .filter_map(|dto| dto.item_id.map(|id| (id.to_string(), dto)))
                        .collect();
                    ServerEvent::UserDataChanged { item_userdata }
                }
                Err(e) => {
                    tracing::debug!(error = %e, "failed to decode UserDataChangeInfo");
                    ServerEvent::Ignored(message_type.to_string())
                }
            }
        }
        "ForceKeepAlive" => {
            keep_alive = value.get("Data").and_then(|d| d.as_u64());
            ServerEvent::ForceKeepAlive
        }
        "SyncPlayCommand" | "SyncPlayGroupUpdate" => {
            let data = value
                .get("Data")
                .cloned()
                .unwrap_or(serde_json::Value::Null);
            match SyncPlayEvent::decode(message_type, data) {
                Some(event) => ServerEvent::SyncPlay(event),
                None => {
                    // No error text: serde quotes the offending value, which may be a user name.
                    tracing::debug!(message_type, "failed to decode SyncPlay frame");
                    ServerEvent::Ignored(message_type.to_string())
                }
            }
        }
        other => ServerEvent::Ignored(other.to_string()),
    };

    Some((event, keep_alive))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::syncplay::GroupUpdate;

    /// A 401 during the WS upgrade maps to `ApiError::Unauthorized`, not a generic
    /// `Transport` error.
    #[test]
    fn map_connect_error_translates_401_to_unauthorized() {
        let response = tokio_tungstenite::tungstenite::http::Response::builder()
            .status(401)
            .body(None)
            .expect("builds a response with no body");
        let err = tokio_tungstenite::tungstenite::Error::Http(response);
        assert!(matches!(
            map_connect_error(err),
            ApiError::Unauthorized { owner: None }
        ));
    }

    /// Jellyfin 12 answers the upgrade with 403 for a revoked token.
    #[test]
    fn map_connect_error_translates_403_to_unauthorized() {
        let response = tokio_tungstenite::tungstenite::http::Response::builder()
            .status(403)
            .body(None)
            .expect("builds a response with no body");
        let err = tokio_tungstenite::tungstenite::Error::Http(response);
        assert!(matches!(
            map_connect_error(err),
            ApiError::Unauthorized { owner: None }
        ));
    }

    #[test]
    fn map_connect_error_translates_other_http_status_to_transport() {
        let response = tokio_tungstenite::tungstenite::http::Response::builder()
            .status(500)
            .body(None)
            .expect("builds a response with no body");
        let err = tokio_tungstenite::tungstenite::Error::Http(response);
        assert!(matches!(map_connect_error(err), ApiError::Transport(_)));
    }

    #[test]
    fn map_connect_error_translates_non_http_error_to_transport() {
        let err = tokio_tungstenite::tungstenite::Error::AlreadyClosed;
        assert!(matches!(map_connect_error(err), ApiError::Transport(_)));
    }

    #[test]
    fn decodes_library_changed() {
        let raw = r#"{"MessageType":"LibraryChanged","Data":{"ItemsAdded":["a","b"],"ItemsUpdated":["c"],"ItemsRemoved":[]}}"#;
        match decode_message(raw) {
            Some(ServerEvent::LibraryChanged {
                added,
                updated,
                removed,
            }) => {
                assert_eq!(added, vec!["a".to_string(), "b".to_string()]);
                assert_eq!(updated, vec!["c".to_string()]);
                assert!(removed.is_empty());
            }
            other => panic!("unexpected: {other:?}"),
        }
    }

    #[test]
    fn decodes_user_data_changed() {
        let raw = r#"{"MessageType":"UserDataChanged","Data":{"UserDataList":[{"Key":"k","ItemId":"e2f5a5f1-1a0b-4b3a-9c2e-000000000001","Played":true,"PlaybackPositionTicks":123}]}}"#;
        match decode_message(raw) {
            Some(ServerEvent::UserDataChanged { item_userdata }) => {
                assert_eq!(item_userdata.len(), 1);
                let (id, dto) = &item_userdata[0];
                assert_eq!(id, "e2f5a5f1-1a0b-4b3a-9c2e-000000000001");
                assert_eq!(dto.played, Some(true));
                assert_eq!(dto.playback_position_ticks, Some(123));
            }
            other => panic!("unexpected: {other:?}"),
        }
    }

    #[test]
    fn decodes_force_keep_alive() {
        let raw = r#"{"MessageType":"ForceKeepAlive","Data":30}"#;
        assert!(matches!(
            decode_message(raw),
            Some(ServerEvent::ForceKeepAlive)
        ));
    }

    #[test]
    fn read_deadline_is_twice_the_interval_plus_ten() {
        assert_eq!(read_deadline(Some(60)), Duration::from_secs(130));
        assert_eq!(read_deadline(Some(1)), Duration::from_secs(12));
        assert_eq!(read_deadline(None), Duration::from_secs(130));
    }

    #[test]
    fn read_deadline_clamps_absurd_values() {
        assert_eq!(read_deadline(Some(0)), Duration::from_secs(130));
        assert_eq!(read_deadline(Some(100_000)), Duration::from_secs(600));
        assert_eq!(read_deadline(Some(u64::MAX)), Duration::from_secs(600));
    }

    #[test]
    fn decode_frame_carries_keep_alive_data_only_for_force_keep_alive() {
        let secs = |t: &str| decode_frame(t).and_then(|(_, k)| k);
        assert_eq!(
            secs(r#"{"MessageType":"ForceKeepAlive","Data":60}"#),
            Some(60)
        );
        assert_eq!(secs(r#"{"MessageType":"ForceKeepAlive"}"#), None);
        assert_eq!(secs(r#"{"MessageType":"ForceKeepAlive","Data":"x"}"#), None);
        assert_eq!(secs(r#"{"MessageType":"LibraryChanged","Data":5}"#), None);
        assert_eq!(secs("not json"), None);
    }

    #[test]
    fn unknown_message_types_are_ignored_by_name() {
        let raw = r#"{"MessageType":"SomeFutureMessageType","Data":{}}"#;
        match decode_message(raw) {
            Some(ServerEvent::Ignored(name)) => assert_eq!(name, "SomeFutureMessageType"),
            other => panic!("unexpected: {other:?}"),
        }
    }

    #[test]
    fn malformed_json_does_not_panic() {
        assert!(decode_message("not json").is_none());
        assert!(decode_message("").is_none());
        assert!(decode_message("{}").is_none());
    }

    #[test]
    fn library_changed_with_wrong_shaped_data_falls_back_to_ignored() {
        // Data present but not an object; must not panic and must still surface something.
        let raw = r#"{"MessageType":"LibraryChanged","Data":"unexpected-string"}"#;
        match decode_message(raw) {
            Some(ServerEvent::Ignored(name)) => assert_eq!(name, "LibraryChanged"),
            other => panic!("unexpected: {other:?}"),
        }
    }

    const GROUP: &str = "00000000-0000-4000-8000-000000000001";
    const ITEM: &str = "00000000-0000-4000-8000-000000000002";
    const PLAYLIST_ITEM: &str = "00000000-0000-4000-8000-000000000003";

    fn group_update_frame(kind: &str, data: &str) -> String {
        format!(
            r#"{{"MessageType":"SyncPlayGroupUpdate","Data":{{"GroupId":"{GROUP}","Data":{data},"Type":"{kind}"}}}}"#
        )
    }

    fn command_frame(command: &str, position_ticks: &str) -> String {
        format!(
            r#"{{"MessageType":"SyncPlayCommand","Data":{{"GroupId":"{GROUP}","PlaylistItemId":"{PLAYLIST_ITEM}","When":"2026-01-01T00:00:01.5Z","PositionTicks":{position_ticks},"Command":"{command}","EmittedAt":"2026-01-01T00:00:01Z"}}}}"#
        )
    }

    fn group_update(raw: &str) -> (Option<uuid::Uuid>, GroupUpdate) {
        match decode_message(raw) {
            Some(ServerEvent::SyncPlay(SyncPlayEvent::GroupUpdate { group_id, update })) => {
                (group_id, update)
            }
            other => panic!("unexpected: {other:?}"),
        }
    }

    /// The first `Type` of `SyncPlayGroupUpdate`: a join carries the group, and the envelope's
    /// `GroupId` is kept.
    #[test]
    fn decodes_sync_play_group_joined() {
        let data = r#"{"GroupId":"00000000-0000-4000-8000-000000000001","GroupName":"ABC234","State":"Idle","Participants":["member-a"],"LastUpdatedAt":"2026-01-01T00:00:00Z"}"#;
        let (group_id, update) = group_update(&group_update_frame("GroupJoined", data));
        assert_eq!(group_id.map(|g| g.to_string()).as_deref(), Some(GROUP));
        let GroupUpdate::GroupJoined(info) = update else {
            panic!("expected GroupJoined, got {update:?}");
        };
        assert_eq!(info.group_name.as_deref(), Some("ABC234"));
        assert_eq!(info.state, Some(models::GroupStateType::Idle));
        assert_eq!(info.participants, vec!["member-a".to_string()]);
    }

    #[test]
    fn decodes_every_sync_play_group_update_type() {
        let queue = format!(
            r#"{{"Reason":"NewPlaylist","LastUpdate":"2026-01-01T00:00:00Z","Playlist":[{{"ItemId":"{ITEM}","PlaylistItemId":"{PLAYLIST_ITEM}"}}],"PlayingItemIndex":0,"StartPositionTicks":0,"IsPlaying":false,"ShuffleMode":"Sorted","RepeatMode":"RepeatNone"}}"#
        );
        let cases: [(&str, &str); 9] = [
            ("StateUpdate", r#"{"State":"Playing","Reason":"Unpause"}"#),
            ("PlayQueue", &queue),
            ("UserJoined", r#""member-b""#),
            ("UserLeft", r#""member-b""#),
            ("GroupLeft", r#""00000000-0000-4000-8000-000000000001""#),
            ("NotInGroup", r#""00000000-0000-4000-8000-000000000001""#),
            (
                "GroupDoesNotExist",
                r#""00000000-0000-4000-8000-000000000001""#,
            ),
            (
                "LibraryAccessDenied",
                r#""00000000-0000-4000-8000-000000000001""#,
            ),
            ("GroupJoined", r#"{"GroupName":"ABC234"}"#),
        ];
        for (kind, data) in cases {
            let (_, update) = group_update(&group_update_frame(kind, data));
            assert_eq!(format!("{update:?}"), kind, "Type {kind} decoded wrongly");
        }

        let (_, update) = group_update(&group_update_frame("PlayQueue", &queue));
        let GroupUpdate::PlayQueue(q) = update else {
            panic!("expected PlayQueue")
        };
        assert_eq!(q.reason, Some(models::PlayQueueUpdateReason::NewPlaylist));
        assert_eq!(q.playlist.len(), 1);
        assert_eq!(
            q.playlist[0]
                .playlist_item_id
                .map(|i| i.to_string())
                .as_deref(),
            Some(PLAYLIST_ITEM)
        );
        assert_eq!(q.playing_item_index, Some(0));
        assert_eq!(q.is_playing, Some(false));

        let (_, update) = group_update(&group_update_frame("UserJoined", r#""member-b""#));
        assert!(matches!(update, GroupUpdate::UserJoined(name) if name == "member-b"));
        let (_, update) = group_update(&group_update_frame(
            "StateUpdate",
            r#"{"State":"Waiting","Reason":"Seek"}"#,
        ));
        let GroupUpdate::StateUpdate(state) = update else {
            panic!("expected StateUpdate")
        };
        assert_eq!(state.state, Some(models::GroupStateType::Waiting));
        assert_eq!(state.reason, Some(models::PlaybackRequestType::Seek));
    }

    #[test]
    fn decodes_each_sync_play_command_kind() {
        for (wire, want) in [
            ("Unpause", models::SendCommandType::Unpause),
            ("Pause", models::SendCommandType::Pause),
            ("Stop", models::SendCommandType::Stop),
            ("Seek", models::SendCommandType::Seek),
        ] {
            match decode_message(&command_frame(wire, "123")) {
                Some(ServerEvent::SyncPlay(SyncPlayEvent::Command(cmd))) => {
                    assert_eq!(cmd.command, Some(want), "{wire}");
                    assert_eq!(cmd.position_ticks, Some(123));
                    assert_eq!(cmd.group_id.map(|g| g.to_string()).as_deref(), Some(GROUP));
                    assert_eq!(
                        cmd.playlist_item_id.map(|i| i.to_string()).as_deref(),
                        Some(PLAYLIST_ITEM)
                    );
                    assert!(cmd.when.is_some() && cmd.emitted_at.is_some());
                }
                other => panic!("{wire}: unexpected: {other:?}"),
            }
        }
    }

    /// Jellyfin's web client reads 0 as "no position"; here 0 stays a real position and an absent
    /// one stays `None`.
    #[test]
    fn sync_play_command_position_zero_is_some_zero() {
        let position = |raw: &str| match decode_message(raw) {
            Some(ServerEvent::SyncPlay(SyncPlayEvent::Command(cmd))) => cmd.position_ticks,
            other => panic!("unexpected: {other:?}"),
        };
        assert_eq!(position(&command_frame("Stop", "0")), Some(0));
        assert_eq!(position(&command_frame("Stop", "null")), None);
    }

    #[test]
    fn unknown_sync_play_values_are_tolerated() {
        let (_, update) = group_update(&group_update_frame("SomeFutureType", r#"{"x":1}"#));
        assert!(matches!(update, GroupUpdate::Unknown(kind) if kind == "SomeFutureType"));

        let (_, update) = group_update(&group_update_frame(
            "StateUpdate",
            r#"{"State":"FutureState","Reason":"FutureReason"}"#,
        ));
        let GroupUpdate::StateUpdate(state) = update else {
            panic!("expected StateUpdate")
        };
        assert_eq!(state.state, Some(models::GroupStateType::Unrecognized));
        assert_eq!(
            state.reason,
            Some(models::PlaybackRequestType::Unrecognized)
        );

        match decode_message(&command_frame("FutureCommand", "1")) {
            Some(ServerEvent::SyncPlay(SyncPlayEvent::Command(cmd))) => {
                assert_eq!(cmd.command, Some(models::SendCommandType::Unrecognized));
            }
            other => panic!("unexpected: {other:?}"),
        }
    }

    /// Same convention as `LibraryChanged`: a known message type whose `Data` has the wrong shape
    /// still surfaces, as `Ignored` by name.
    #[test]
    fn sync_play_frames_with_missing_or_malformed_data_fall_back_to_ignored() {
        let cases = [
            (r#"{"MessageType":"SyncPlayCommand"}"#, "SyncPlayCommand"),
            (
                r#"{"MessageType":"SyncPlayCommand","Data":"unexpected-string"}"#,
                "SyncPlayCommand",
            ),
            (
                r#"{"MessageType":"SyncPlayGroupUpdate"}"#,
                "SyncPlayGroupUpdate",
            ),
            (
                r#"{"MessageType":"SyncPlayGroupUpdate","Data":"unexpected-string"}"#,
                "SyncPlayGroupUpdate",
            ),
            (
                r#"{"MessageType":"SyncPlayGroupUpdate","Data":{"GroupId":"00000000-0000-4000-8000-000000000001"}}"#,
                "SyncPlayGroupUpdate",
            ),
        ];
        for (raw, name) in cases {
            match decode_message(raw) {
                Some(ServerEvent::Ignored(got)) => assert_eq!(got, name, "{raw}"),
                other => panic!("{raw}: unexpected: {other:?}"),
            }
        }
        for (kind, data) in [
            ("GroupJoined", "\"not-a-group\""),
            ("StateUpdate", "5"),
            ("PlayQueue", "null"),
            ("UserJoined", "{}"),
            ("GroupLeft", "null"),
        ] {
            match decode_message(&group_update_frame(kind, data)) {
                Some(ServerEvent::Ignored(got)) => assert_eq!(got, "SyncPlayGroupUpdate", "{kind}"),
                other => panic!("{kind}: unexpected: {other:?}"),
            }
        }
    }

    #[test]
    fn ws_base_rewrites_scheme() {
        assert_eq!(ws_base("http://localhost:8096"), "ws://localhost:8096");
        assert_eq!(
            ws_base("https://demo.jellyfin.org/stable"),
            "wss://demo.jellyfin.org/stable"
        );
    }

    /// Against a real WS server that sends nothing, dropping the receiver is noticed promptly
    /// and a `Close` frame is sent without needing a server message first.
    #[tokio::test]
    async fn drops_receiver_promptly_without_waiting_for_a_server_message() {
        use tokio::net::TcpListener;

        let listener = TcpListener::bind("127.0.0.1:0")
            .await
            .expect("bind local listener");
        let addr = listener.local_addr().expect("local_addr");

        let server = tokio::spawn(async move {
            let (stream, _) = listener.accept().await.expect("accept tcp connection");
            let ws_stream = tokio_tungstenite::accept_async(stream)
                .await
                .expect("complete ws handshake");
            let (_write, mut read) = ws_stream.split();
            // Deliberately idle; wait for the client to send Close (or drop) after its receiver is
            // dropped.
            while let Some(Ok(msg)) = read.next().await {
                if matches!(msg, Message::Close(_)) {
                    return true; // client noticed and closed cleanly
                }
            }
            false // socket closed some other way (still fine, not hung)
        });

        let identity = ClientIdentity {
            client: "Test".to_string(),
            device: "test".to_string(),
            device_id: "test-device".to_string(),
            version: "0.0.0".to_string(),
        };
        let base_url = format!("http://{addr}");
        let rx = connect(&base_url, &identity, "test-token")
            .await
            .expect("connect to mock server");

        drop(rx);

        // Bounded wait: times out if the receiver-drop isn't noticed until a server message (never
        // sent here).
        let saw_close = tokio::time::timeout(Duration::from_secs(2), server)
            .await
            .expect("server task must observe the client closing promptly, not hang")
            .expect("server task must not panic");
        assert!(
            saw_close,
            "client should send a Close frame once its receiver is dropped"
        );
    }

    /// A server that sends one `ForceKeepAlive` (Data=1) then goes silent ends the read loop at 12 s.
    #[tokio::test(flavor = "current_thread")]
    async fn silent_server_ends_the_read_loop_at_the_deadline() {
        use tokio::net::TcpListener;

        let listener = TcpListener::bind("127.0.0.1:0")
            .await
            .expect("bind local listener");
        let addr = listener.local_addr().expect("local_addr");
        let server = tokio::spawn(async move {
            let (stream, _) = listener.accept().await.expect("accept tcp connection");
            let mut ws = tokio_tungstenite::accept_async(stream)
                .await
                .expect("complete ws handshake");
            ws.send(Message::Text(
                r#"{"MessageType":"ForceKeepAlive","Data":1}"#.to_string(),
            ))
            .await
            .expect("send keepalive");
            // Silent but open; pongs would not count either.
            futures_util::future::pending::<()>().await;
        });

        let identity = ClientIdentity {
            client: "Test".to_string(),
            device: "test".to_string(),
            device_id: "test-device".to_string(),
            version: "0.0.0".to_string(),
        };
        let mut rx = connect(&format!("http://{addr}"), &identity, "test-token")
            .await
            .expect("connect to mock server");

        // Receive the advertised interval in real time; pause only for the silent wait.
        assert!(matches!(rx.recv().await, Some(ServerEvent::ForceKeepAlive)));
        tokio::time::pause();
        let started = tokio::time::Instant::now();
        let end = tokio::time::timeout(Duration::from_secs(300), rx.recv())
            .await
            .expect("loop must end by its own deadline");
        assert!(end.is_none());
        let elapsed = started.elapsed();
        assert!(
            elapsed >= Duration::from_secs(12) && elapsed < Duration::from_secs(20),
            "{elapsed:?}"
        );
        server.abort();
    }

    /// A consumer that stalls past the deadline must not get a healthy socket closed on unblock.
    #[tokio::test(flavor = "current_thread")]
    async fn stalled_consumer_does_not_trip_the_deadline() {
        use tokio::net::TcpListener;

        const EXTRA: usize = EVENT_CHANNEL_CAPACITY + 50;
        let listener = TcpListener::bind("127.0.0.1:0")
            .await
            .expect("bind local listener");
        let addr = listener.local_addr().expect("local_addr");
        let server = tokio::spawn(async move {
            let (stream, _) = listener.accept().await.expect("accept tcp connection");
            let mut ws = tokio_tungstenite::accept_async(stream)
                .await
                .expect("complete ws handshake");
            ws.send(Message::Text(
                r#"{"MessageType":"ForceKeepAlive","Data":1}"#.to_string(),
            ))
            .await
            .expect("send keepalive");
            for _ in 0..EXTRA {
                ws.send(Message::Text(r#"{"MessageType":"Other"}"#.to_string()))
                    .await
                    .expect("send frame");
            }
            futures_util::future::pending::<()>().await;
        });

        let identity = ClientIdentity {
            client: "Test".to_string(),
            device: "test".to_string(),
            device_id: "test-device".to_string(),
            version: "0.0.0".to_string(),
        };
        let mut rx = connect(&format!("http://{addr}"), &identity, "test-token")
            .await
            .expect("connect to mock server");

        assert!(matches!(rx.recv().await, Some(ServerEvent::ForceKeepAlive)));
        // Real time until the task is blocked in `send` on a full channel; only then jump the
        // clock well past the 12 s deadline, so paused time never races socket reads.
        while rx.len() < EVENT_CHANNEL_CAPACITY {
            tokio::time::sleep(Duration::from_millis(5)).await;
        }
        tokio::time::pause();
        tokio::time::advance(Duration::from_secs(60)).await;
        tokio::time::resume();
        for i in 0..EXTRA {
            let ev = tokio::time::timeout(Duration::from_secs(5), rx.recv()).await;
            assert!(matches!(ev, Ok(Some(_))), "loop ended after {i} of {EXTRA}");
        }
        server.abort();
    }
}
