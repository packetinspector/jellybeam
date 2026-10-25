//! SyncPlay: the socket messages decoded into typed events, and one REST method per
//! `/SyncPlay/*` endpoint plus `GET /GetUtcTime`. Stateless: nothing here runs until a caller
//! invokes a method or the socket delivers a `SyncPlay*` frame.

use uuid::Uuid;

use crate::models::{
    BufferRequestDto, GroupInfoDto, GroupRepeatMode, GroupShuffleMode, GroupStateUpdate,
    IgnoreWaitRequestDto, JoinGroupRequestDto, MovePlaylistItemRequestDto, NewGroupRequestDto,
    NextItemRequestDto, PingRequestDto, PlayQueueUpdate, PlayRequestDto, PreviousItemRequestDto,
    QueueRequestDto, ReadyRequestDto, RemoveFromPlaylistRequestDto, SeekRequestDto, SendCommand,
    SetPlaylistItemRequestDto, SetRepeatModeRequestDto, SetShuffleModeRequestDto, UtcTimeResponse,
};
use crate::util::describe_error_chain;
use crate::{read_json_capped, ApiError, JellyfinClient};

/// A decoded `SyncPlayCommand` or `SyncPlayGroupUpdate` socket message.
#[derive(Debug, Clone)]
pub enum SyncPlayEvent {
    Command(SendCommand),
    /// `group_id` is the envelope's `GroupId`; `None` when the frame omitted it.
    GroupUpdate {
        group_id: Option<Uuid>,
        update: GroupUpdate,
    },
}

/// The `Data` of a `SyncPlayGroupUpdate`, switched on its `Type`. The `String` variants carry the
/// wire text: a user name for `UserJoined`/`UserLeft`, a group id for the rest.
#[derive(Clone)]
pub enum GroupUpdate {
    GroupJoined(GroupInfoDto),
    StateUpdate(GroupStateUpdate),
    PlayQueue(PlayQueueUpdate),
    UserJoined(String),
    UserLeft(String),
    GroupLeft(String),
    NotInGroup(String),
    GroupDoesNotExist(String),
    LibraryAccessDenied(String),
    /// A `Type` this build doesn't know, kept by name so a newer server never fails the frame.
    Unknown(String),
}

/// Debug names the variant only, so user names and room names never reach logs.
impl std::fmt::Debug for GroupUpdate {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(match self {
            Self::GroupJoined(_) => "GroupJoined",
            Self::StateUpdate(_) => "StateUpdate",
            Self::PlayQueue(_) => "PlayQueue",
            Self::UserJoined(_) => "UserJoined",
            Self::UserLeft(_) => "UserLeft",
            Self::GroupLeft(_) => "GroupLeft",
            Self::NotInGroup(_) => "NotInGroup",
            Self::GroupDoesNotExist(_) => "GroupDoesNotExist",
            Self::LibraryAccessDenied(_) => "LibraryAccessDenied",
            Self::Unknown(_) => "Unknown",
        })
    }
}

#[derive(serde::Deserialize)]
struct GroupUpdateEnvelope {
    #[serde(rename = "GroupId", default)]
    group_id: Option<Uuid>,
    #[serde(rename = "Type")]
    kind: String,
    #[serde(rename = "Data", default)]
    data: serde_json::Value,
}

impl SyncPlayEvent {
    /// Decodes the `Data` of a `SyncPlayCommand` or `SyncPlayGroupUpdate` frame; `None` when it
    /// doesn't have the shape for a known `Type`, so the caller falls back to `Ignored`.
    pub(crate) fn decode(message_type: &str, data: serde_json::Value) -> Option<Self> {
        if message_type == "SyncPlayCommand" {
            return serde_json::from_value(data).ok().map(Self::Command);
        }
        let envelope: GroupUpdateEnvelope = serde_json::from_value(data).ok()?;
        let update = GroupUpdate::decode(&envelope.kind, envelope.data).ok()?;
        Some(Self::GroupUpdate {
            group_id: envelope.group_id,
            update,
        })
    }
}

impl GroupUpdate {
    fn decode(kind: &str, data: serde_json::Value) -> Result<Self, serde_json::Error> {
        use serde_json::from_value;
        Ok(match kind {
            "GroupJoined" => Self::GroupJoined(from_value(data)?),
            "StateUpdate" => Self::StateUpdate(from_value(data)?),
            "PlayQueue" => Self::PlayQueue(from_value(data)?),
            "UserJoined" => Self::UserJoined(from_value(data)?),
            "UserLeft" => Self::UserLeft(from_value(data)?),
            "GroupLeft" => Self::GroupLeft(from_value(data)?),
            "NotInGroup" => Self::NotInGroup(from_value(data)?),
            "GroupDoesNotExist" => Self::GroupDoesNotExist(from_value(data)?),
            "LibraryAccessDenied" => Self::LibraryAccessDenied(from_value(data)?),
            other => Self::Unknown(other.to_string()),
        })
    }
}

/// Every method is a request the server answers `204`, except where the return type says otherwise.
/// A `403` surfaces as [`ApiError::Status`], not `Unauthorized`: it means no SyncPlay access, and
/// the token is fine.
impl JellyfinClient {
    fn sync_play_post(&self, path: &str) -> reqwest::RequestBuilder {
        self.inner
            .http
            .post(format!("{}{}", self.inner.base_url, path))
            .header("Authorization", self.auth_header())
    }

    async fn sync_play_send(
        &self,
        request: reqwest::RequestBuilder,
    ) -> Result<reqwest::Response, ApiError> {
        let resp = request
            .send()
            .await
            .map_err(|e| ApiError::Transport(describe_error_chain(&e)))?;
        self.check(resp).await
    }

    /// `POST /SyncPlay/New`. Jellyfin 10.11+ and Jellyswarrm answer `200` with the group; a bodiless
    /// `204` is tolerated because the `GroupJoined` socket message carries the group too.
    pub async fn sync_play_new_group(
        &self,
        group_name: &str,
    ) -> Result<Option<GroupInfoDto>, ApiError> {
        let body = NewGroupRequestDto {
            group_name: Some(group_name.to_string()),
        };
        let resp = self
            .sync_play_send(self.sync_play_post("/SyncPlay/New").json(&body))
            .await?;
        if resp.status() == reqwest::StatusCode::NO_CONTENT {
            return Ok(None);
        }
        read_json_capped(resp, "/SyncPlay/New").await.map(Some)
    }

    pub async fn sync_play_join_group(&self, group_id: Uuid) -> Result<(), ApiError> {
        let body = JoinGroupRequestDto {
            group_id: Some(group_id),
        };
        self.sync_play_send(self.sync_play_post("/SyncPlay/Join").json(&body))
            .await
            .map(drop)
    }

    pub async fn sync_play_leave_group(&self) -> Result<(), ApiError> {
        self.sync_play_send(self.sync_play_post("/SyncPlay/Leave"))
            .await
            .map(drop)
    }

    pub async fn sync_play_list_groups(&self) -> Result<Vec<GroupInfoDto>, ApiError> {
        self.get("/SyncPlay/List", &[]).await
    }

    pub async fn sync_play_get_group(&self, group_id: Uuid) -> Result<GroupInfoDto, ApiError> {
        self.get(&format!("/SyncPlay/{group_id}"), &[]).await
    }

    pub async fn sync_play_set_new_queue(&self, request: &PlayRequestDto) -> Result<(), ApiError> {
        self.sync_play_send(self.sync_play_post("/SyncPlay/SetNewQueue").json(request))
            .await
            .map(drop)
    }

    pub async fn sync_play_set_playlist_item(
        &self,
        playlist_item_id: Uuid,
    ) -> Result<(), ApiError> {
        let body = SetPlaylistItemRequestDto {
            playlist_item_id: Some(playlist_item_id),
        };
        self.sync_play_send(self.sync_play_post("/SyncPlay/SetPlaylistItem").json(&body))
            .await
            .map(drop)
    }

    pub async fn sync_play_remove_from_playlist(
        &self,
        request: &RemoveFromPlaylistRequestDto,
    ) -> Result<(), ApiError> {
        self.sync_play_send(
            self.sync_play_post("/SyncPlay/RemoveFromPlaylist")
                .json(request),
        )
        .await
        .map(drop)
    }

    pub async fn sync_play_move_playlist_item(
        &self,
        playlist_item_id: Uuid,
        new_index: i32,
    ) -> Result<(), ApiError> {
        let body = MovePlaylistItemRequestDto {
            playlist_item_id: Some(playlist_item_id),
            new_index: Some(new_index),
        };
        self.sync_play_send(
            self.sync_play_post("/SyncPlay/MovePlaylistItem")
                .json(&body),
        )
        .await
        .map(drop)
    }

    pub async fn sync_play_queue(&self, request: &QueueRequestDto) -> Result<(), ApiError> {
        self.sync_play_send(self.sync_play_post("/SyncPlay/Queue").json(request))
            .await
            .map(drop)
    }

    pub async fn sync_play_unpause(&self) -> Result<(), ApiError> {
        self.sync_play_send(self.sync_play_post("/SyncPlay/Unpause"))
            .await
            .map(drop)
    }

    pub async fn sync_play_pause(&self) -> Result<(), ApiError> {
        self.sync_play_send(self.sync_play_post("/SyncPlay/Pause"))
            .await
            .map(drop)
    }

    pub async fn sync_play_stop(&self) -> Result<(), ApiError> {
        self.sync_play_send(self.sync_play_post("/SyncPlay/Stop"))
            .await
            .map(drop)
    }

    pub async fn sync_play_seek(&self, position_ticks: i64) -> Result<(), ApiError> {
        let body = SeekRequestDto {
            position_ticks: Some(position_ticks),
        };
        self.sync_play_send(self.sync_play_post("/SyncPlay/Seek").json(&body))
            .await
            .map(drop)
    }

    pub async fn sync_play_buffering(&self, request: &BufferRequestDto) -> Result<(), ApiError> {
        self.sync_play_send(self.sync_play_post("/SyncPlay/Buffering").json(request))
            .await
            .map(drop)
    }

    pub async fn sync_play_ready(&self, request: &ReadyRequestDto) -> Result<(), ApiError> {
        self.sync_play_send(self.sync_play_post("/SyncPlay/Ready").json(request))
            .await
            .map(drop)
    }

    pub async fn sync_play_set_ignore_wait(
        &self,
        request: &IgnoreWaitRequestDto,
    ) -> Result<(), ApiError> {
        self.sync_play_send(self.sync_play_post("/SyncPlay/SetIgnoreWait").json(request))
            .await
            .map(drop)
    }

    pub async fn sync_play_next_item(&self, playlist_item_id: Uuid) -> Result<(), ApiError> {
        let body = NextItemRequestDto {
            playlist_item_id: Some(playlist_item_id),
        };
        self.sync_play_send(self.sync_play_post("/SyncPlay/NextItem").json(&body))
            .await
            .map(drop)
    }

    pub async fn sync_play_previous_item(&self, playlist_item_id: Uuid) -> Result<(), ApiError> {
        let body = PreviousItemRequestDto {
            playlist_item_id: Some(playlist_item_id),
        };
        self.sync_play_send(self.sync_play_post("/SyncPlay/PreviousItem").json(&body))
            .await
            .map(drop)
    }

    pub async fn sync_play_set_repeat_mode(&self, mode: GroupRepeatMode) -> Result<(), ApiError> {
        let body = SetRepeatModeRequestDto { mode: Some(mode) };
        self.sync_play_send(self.sync_play_post("/SyncPlay/SetRepeatMode").json(&body))
            .await
            .map(drop)
    }

    pub async fn sync_play_set_shuffle_mode(&self, mode: GroupShuffleMode) -> Result<(), ApiError> {
        let body = SetShuffleModeRequestDto { mode: Some(mode) };
        self.sync_play_send(self.sync_play_post("/SyncPlay/SetShuffleMode").json(&body))
            .await
            .map(drop)
    }

    /// `POST /SyncPlay/Ping`: the half round-trip time in milliseconds the server uses to schedule
    /// commands for this member.
    pub async fn sync_play_ping(&self, ping_ms: i64) -> Result<(), ApiError> {
        let body = PingRequestDto {
            ping: Some(ping_ms),
        };
        self.sync_play_send(self.sync_play_post("/SyncPlay/Ping").json(&body))
            .await
            .map(drop)
    }

    /// `GET /GetUtcTime`: the server's receive and transmit stamps for the clock-offset estimate.
    pub async fn get_utc_time(&self) -> Result<UtcTimeResponse, ApiError> {
        self.get("/GetUtcTime", &[]).await
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::models::{GroupQueueMode, GroupStateType};
    use crate::test_server::capturing_server;
    use crate::ClientIdentity;

    const GROUP: &str = "00000000-0000-4000-8000-000000000001";
    const ITEM: &str = "00000000-0000-4000-8000-000000000002";
    const PLAYLIST_ITEM: &str = "00000000-0000-4000-8000-000000000003";

    fn id(s: &str) -> Uuid {
        s.parse().expect("test uuid")
    }

    fn client(base_url: &str) -> JellyfinClient {
        let identity = ClientIdentity {
            client: "Jellybeam".to_string(),
            device: "Test Device".to_string(),
            device_id: "device-123".to_string(),
            version: "0.1.0".to_string(),
        };
        JellyfinClient::from_token(base_url, identity, "tok")
    }

    /// Splits a captured request into its request line and JSON body, asserting the token header.
    fn parse(request: &str) -> (String, Option<serde_json::Value>) {
        let (head, body) = request.split_once("\r\n\r\n").expect("head and body");
        assert!(head.contains(r#"Token="tok""#), "no token: {head}");
        let line = head.lines().next().expect("request line");
        let line = line.trim_end_matches(" HTTP/1.1").to_string();
        (
            line,
            (!body.is_empty()).then(|| serde_json::from_str(body).expect("json body")),
        )
    }

    #[tokio::test]
    async fn new_group_posts_the_name_and_decodes_the_group() {
        let (base, rx) = capturing_server(
            "200 OK",
            r#"{"GroupId":"00000000-0000-4000-8000-000000000001","GroupName":"ABC234","State":"Idle","Participants":["member-a"]}"#,
        )
        .await;
        let group = client(&base)
            .sync_play_new_group("ABC234")
            .await
            .expect("new group")
            .expect("200 carries the group");
        assert_eq!(group.state, Some(GroupStateType::Idle));
        assert_eq!(group.group_id, Some(id(GROUP)));
        let (line, body) = parse(&rx.await.expect("request"));
        assert_eq!(line, "POST /SyncPlay/New");
        assert_eq!(body, Some(serde_json::json!({"GroupName": "ABC234"})));
    }

    #[tokio::test]
    async fn new_group_accepts_a_204_without_a_group() {
        let (base, _rx) = capturing_server("204 No Content", "").await;
        let group = client(&base).sync_play_new_group("ABC234").await;
        assert!(matches!(group, Ok(None)), "{group:?}");
    }

    #[tokio::test]
    async fn join_posts_the_group_id() {
        let (base, rx) = capturing_server("204 No Content", "").await;
        client(&base)
            .sync_play_join_group(id(GROUP))
            .await
            .expect("join");
        let (line, body) = parse(&rx.await.expect("request"));
        assert_eq!(line, "POST /SyncPlay/Join");
        assert_eq!(body, Some(serde_json::json!({"GroupId": GROUP})));
    }

    #[tokio::test]
    async fn set_new_queue_posts_the_queue_and_start_position() {
        let (base, rx) = capturing_server("204 No Content", "").await;
        let request = PlayRequestDto {
            playing_queue: vec![id(ITEM)],
            playing_item_position: Some(0),
            start_position_ticks: Some(0),
        };
        client(&base)
            .sync_play_set_new_queue(&request)
            .await
            .expect("set new queue");
        let (line, body) = parse(&rx.await.expect("request"));
        assert_eq!(line, "POST /SyncPlay/SetNewQueue");
        assert_eq!(
            body,
            Some(serde_json::json!({
                "PlayingQueue": [ITEM], "PlayingItemPosition": 0, "StartPositionTicks": 0
            }))
        );
    }

    #[tokio::test]
    async fn queue_posts_the_items_and_mode() {
        let (base, rx) = capturing_server("204 No Content", "").await;
        let request = QueueRequestDto {
            item_ids: vec![id(ITEM)],
            mode: Some(GroupQueueMode::QueueNext),
        };
        client(&base)
            .sync_play_queue(&request)
            .await
            .expect("queue");
        let (line, body) = parse(&rx.await.expect("request"));
        assert_eq!(line, "POST /SyncPlay/Queue");
        assert_eq!(
            body,
            Some(serde_json::json!({"ItemIds": [ITEM], "Mode": "QueueNext"}))
        );
    }

    /// Position 0 is a real position: it must reach the wire, not be dropped as empty.
    #[tokio::test]
    async fn seek_posts_the_position_including_zero() {
        let (base, rx) = capturing_server("204 No Content", "").await;
        client(&base).sync_play_seek(0).await.expect("seek");
        let (line, body) = parse(&rx.await.expect("request"));
        assert_eq!(line, "POST /SyncPlay/Seek");
        assert_eq!(body, Some(serde_json::json!({"PositionTicks": 0})));
    }

    #[tokio::test]
    async fn ready_posts_when_position_and_playlist_item() {
        let (base, rx) = capturing_server("204 No Content", "").await;
        let request = ReadyRequestDto {
            when: Some("2026-01-01T00:00:01Z".parse().expect("time")),
            position_ticks: Some(50),
            is_playing: Some(false),
            playlist_item_id: Some(id(PLAYLIST_ITEM)),
        };
        client(&base)
            .sync_play_ready(&request)
            .await
            .expect("ready");
        let (line, body) = parse(&rx.await.expect("request"));
        assert_eq!(line, "POST /SyncPlay/Ready");
        let body = body.expect("body");
        assert_eq!(body["PositionTicks"], 50);
        assert_eq!(body["IsPlaying"], false);
        assert_eq!(body["PlaylistItemId"], PLAYLIST_ITEM);
        assert!(
            body["When"]
                .as_str()
                .is_some_and(|w| w.starts_with("2026-01-01T00:00:01")),
            "{body}"
        );
    }

    #[tokio::test]
    async fn ping_posts_the_milliseconds() {
        let (base, rx) = capturing_server("204 No Content", "").await;
        client(&base).sync_play_ping(42).await.expect("ping");
        let (line, body) = parse(&rx.await.expect("request"));
        assert_eq!(line, "POST /SyncPlay/Ping");
        assert_eq!(body, Some(serde_json::json!({"Ping": 42})));
    }

    #[tokio::test]
    async fn bodyless_commands_post_to_their_paths() {
        type Call = for<'a> fn(
            &'a JellyfinClient,
        ) -> std::pin::Pin<
            Box<dyn std::future::Future<Output = Result<(), ApiError>> + 'a>,
        >;
        let calls: [(&str, Call); 4] = [
            ("/SyncPlay/Leave", |c| Box::pin(c.sync_play_leave_group())),
            ("/SyncPlay/Unpause", |c| Box::pin(c.sync_play_unpause())),
            ("/SyncPlay/Pause", |c| Box::pin(c.sync_play_pause())),
            ("/SyncPlay/Stop", |c| Box::pin(c.sync_play_stop())),
        ];
        for (path, call) in calls {
            let (base, rx) = capturing_server("204 No Content", "").await;
            call(&client(&base)).await.expect(path);
            let (line, body) = parse(&rx.await.expect("request"));
            assert_eq!(line, format!("POST {path}"));
            assert_eq!(body, None, "{path}");
        }
    }

    #[tokio::test]
    async fn list_and_get_group_decode_the_group_info() {
        let (base, rx) = capturing_server(
            "200 OK",
            r#"[{"GroupId":"00000000-0000-4000-8000-000000000001","GroupName":"ABC234","State":"Waiting","Participants":["member-a","member-b"],"LastUpdatedAt":"2026-01-01T00:00:00Z"}]"#,
        )
        .await;
        let groups = client(&base).sync_play_list_groups().await.expect("list");
        assert_eq!(groups.len(), 1);
        assert_eq!(groups[0].state, Some(GroupStateType::Waiting));
        assert_eq!(groups[0].participants.len(), 2);
        assert_eq!(parse(&rx.await.expect("request")).0, "GET /SyncPlay/List");

        let (base, rx) = capturing_server(
            "200 OK",
            r#"{"GroupId":"00000000-0000-4000-8000-000000000001","GroupName":"ABC234"}"#,
        )
        .await;
        let group = client(&base)
            .sync_play_get_group(id(GROUP))
            .await
            .expect("get");
        assert_eq!(group.group_name.as_deref(), Some("ABC234"));
        assert_eq!(
            parse(&rx.await.expect("request")).0,
            format!("GET /SyncPlay/{GROUP}")
        );
    }

    #[tokio::test]
    async fn get_utc_time_parses_both_stamps() {
        let (base, rx) = capturing_server(
            "200 OK",
            r#"{"RequestReceptionTime":"2026-01-01T00:00:01.2500000Z","ResponseTransmissionTime":"2026-01-01T00:00:01.2600000Z"}"#,
        )
        .await;
        let time = client(&base).get_utc_time().await.expect("utc time");
        let (received, sent) = (
            time.request_reception_time.expect("received"),
            time.response_transmission_time.expect("sent"),
        );
        assert_eq!((sent - received).num_milliseconds(), 10);
        assert_eq!(parse(&rx.await.expect("request")).0, "GET /GetUtcTime");
    }

    /// A 403 is "no SyncPlay access", not a rejected token: it must not read as `Unauthorized`,
    /// which would sign the user out.
    #[tokio::test]
    async fn a_403_is_a_status_error_not_unauthorized() {
        let (base, _rx) = capturing_server("403 Forbidden", "").await;
        let err = client(&base)
            .sync_play_join_group(id(GROUP))
            .await
            .expect_err("403");
        assert!(matches!(err, ApiError::Status { code: 403, .. }), "{err:?}");
    }
}
