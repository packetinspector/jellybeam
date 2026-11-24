//! Remote control (docs/31 §4): the `Play`, `Playstate` and `GeneralCommand` socket messages decoded
//! into one typed [`RemoteCommand`]. Stateless: nothing here runs until the socket delivers such a
//! frame. Hand-written, not generated: the request shapes are absent from the pinned subset of
//! models, and every `GeneralCommand` argument arrives as a string.

use std::collections::BTreeMap;
use std::str::FromStr;

use serde_json::Value;

/// A decoded remote-control frame. `Debug` names the variant only: ids, message text and typed
/// strings come from someone else's controller and must never reach logs.
#[derive(Clone, PartialEq, Eq)]
pub enum RemoteCommand {
    Play(PlayRequest),
    Playstate(PlaystateCommand),
    General(GeneralCommand),
    /// A `GeneralCommand` name this build doesn't handle, kept so the caller logs one line
    /// (docs/31 §4.6).
    Unsupported(String),
}

impl std::fmt::Debug for RemoteCommand {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(match self {
            Self::Play(_) => "Play",
            Self::Playstate(_) => "Playstate",
            Self::General(_) => "General",
            Self::Unsupported(_) => "Unsupported",
        })
    }
}

/// docs/31 §4.1. Also what `PlayMediaSource` decodes to (§4.5), with `PlayNow`.
#[derive(Clone, PartialEq, Eq, serde::Deserialize)]
#[serde(rename_all = "PascalCase")]
pub struct PlayRequest {
    /// Never empty: a request without items is malformed.
    pub item_ids: Vec<String>,
    pub start_index: Option<u32>,
    pub start_position_ticks: Option<i64>,
    pub play_command: PlayCommand,
    pub media_source_id: Option<String>,
    pub audio_stream_index: Option<i32>,
    pub subtitle_stream_index: Option<i32>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Deserialize)]
pub enum PlayCommand {
    PlayNow,
    PlayNext,
    PlayLast,
    PlayShuffle,
    PlayInstantMix,
}

/// docs/31 §4.2.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PlaystateCommand {
    Stop,
    Pause,
    Unpause,
    NextTrack,
    PreviousTrack,
    /// A `Seek` frame without ticks is malformed, so the ticks are not optional here.
    Seek {
        position_ticks: i64,
    },
    Rewind,
    FastForward,
    PlayPause,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RemoteRepeat {
    Off,
    All,
    One,
}

impl RemoteRepeat {
    fn parse(wire: &str) -> Option<Self> {
        match wire {
            "RepeatNone" => Some(Self::Off),
            "RepeatAll" => Some(Self::All),
            "RepeatOne" => Some(Self::One),
            _ => None,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RemoteShuffle {
    Sorted,
    Shuffled,
}

impl RemoteShuffle {
    /// `SetShuffleQueue` sends `Sorted`/`Shuffle`, `SetPlaybackOrder` sends `Default`/`Shuffle`.
    fn parse(wire: &str) -> Option<Self> {
        match wire {
            "Sorted" | "Default" => Some(Self::Sorted),
            "Shuffle" => Some(Self::Shuffled),
            _ => None,
        }
    }
}

/// The `GeneralCommand` names docs/31 §4.3-§4.5 handle, arguments parsed.
#[derive(Clone, PartialEq, Eq)]
pub enum GeneralCommand {
    SetAudioStreamIndex(i32),
    /// `-1` means off.
    SetSubtitleStreamIndex(i32),
    DisplayContent {
        item_id: String,
        item_type: String,
    },
    DisplayMessage {
        /// `None` when the frame's header is absent or empty.
        header: Option<String>,
        text: String,
    },
    SendString(String),
    /// Clamped to 0..=100.
    SetVolume(u8),
    VolumeUp,
    VolumeDown,
    Mute,
    Unmute,
    ToggleMute,
    MoveUp,
    MoveDown,
    MoveLeft,
    MoveRight,
    Select,
    Back,
    PageUp,
    PageDown,
    PreviousLetter,
    NextLetter,
    GoHome,
    GoToSearch,
    GoToSettings,
    ToggleContextMenu,
    ToggleOsd,
    ToggleOsdMenu,
    ToggleFullscreen,
    ToggleStats,
    SetRepeatMode(RemoteRepeat),
    /// From `SetShuffleQueue` and `SetPlaybackOrder`, which mean the same.
    SetShuffle(RemoteShuffle),
}

#[derive(serde::Deserialize)]
#[serde(rename_all = "PascalCase")]
struct PlaystateWire {
    command: String,
    seek_position_ticks: Option<i64>,
}

#[derive(serde::Deserialize)]
#[serde(rename_all = "PascalCase")]
struct GeneralWire {
    name: String,
    arguments: Option<Args>,
}

type Args = BTreeMap<String, String>;

impl RemoteCommand {
    /// Decodes the `Data` of a `Play`, `Playstate` or `GeneralCommand` frame; `None` when it
    /// doesn't have the shape for a known command, so the caller falls back to `Ignored`.
    pub(crate) fn decode(message_type: &str, data: Value) -> Option<Self> {
        match message_type {
            "Play" => serde_json::from_value::<PlayRequest>(data)
                .ok()
                .filter(|request| !request.item_ids.is_empty())
                .map(Self::Play),
            "Playstate" => decode_playstate(data).map(Self::Playstate),
            "GeneralCommand" => decode_general(data),
            _ => None,
        }
    }
}

fn decode_playstate(data: Value) -> Option<PlaystateCommand> {
    let wire: PlaystateWire = serde_json::from_value(data).ok()?;
    Some(match wire.command.as_str() {
        "Stop" => PlaystateCommand::Stop,
        "Pause" => PlaystateCommand::Pause,
        "Unpause" => PlaystateCommand::Unpause,
        "NextTrack" => PlaystateCommand::NextTrack,
        "PreviousTrack" => PlaystateCommand::PreviousTrack,
        "Seek" => PlaystateCommand::Seek {
            position_ticks: wire.seek_position_ticks?,
        },
        "Rewind" => PlaystateCommand::Rewind,
        "FastForward" => PlaystateCommand::FastForward,
        "PlayPause" => PlaystateCommand::PlayPause,
        _ => return None,
    })
}

fn decode_general(data: Value) -> Option<RemoteCommand> {
    use GeneralCommand as G;
    let wire: GeneralWire = serde_json::from_value(data).ok()?;
    let args = wire.arguments.unwrap_or_default();
    let command = match wire.name.as_str() {
        "SetAudioStreamIndex" => G::SetAudioStreamIndex(number(&args, "Index")?),
        "SetSubtitleStreamIndex" => G::SetSubtitleStreamIndex(number(&args, "Index")?),
        "DisplayContent" => G::DisplayContent {
            item_id: text(&args, "ItemId")?.to_string(),
            item_type: text(&args, "ItemType")?.to_string(),
        },
        "DisplayMessage" => G::DisplayMessage {
            header: text(&args, "Header").map(str::to_string),
            text: text(&args, "Text")?.to_string(),
        },
        "SendString" => G::SendString(text(&args, "String")?.to_string()),
        "SetVolume" => {
            G::SetVolume(u8::try_from(number::<i64>(&args, "Volume")?.clamp(0, 100)).ok()?)
        }
        "VolumeUp" => G::VolumeUp,
        "VolumeDown" => G::VolumeDown,
        "Mute" => G::Mute,
        "Unmute" => G::Unmute,
        "ToggleMute" => G::ToggleMute,
        "MoveUp" => G::MoveUp,
        "MoveDown" => G::MoveDown,
        "MoveLeft" => G::MoveLeft,
        "MoveRight" => G::MoveRight,
        "Select" => G::Select,
        "Back" => G::Back,
        "PageUp" => G::PageUp,
        "PageDown" => G::PageDown,
        "PreviousLetter" => G::PreviousLetter,
        "NextLetter" => G::NextLetter,
        "GoHome" => G::GoHome,
        "GoToSearch" => G::GoToSearch,
        "GoToSettings" => G::GoToSettings,
        "ToggleContextMenu" => G::ToggleContextMenu,
        "ToggleOsd" => G::ToggleOsd,
        "ToggleOsdMenu" => G::ToggleOsdMenu,
        "ToggleFullscreen" => G::ToggleFullscreen,
        "ToggleStats" => G::ToggleStats,
        "SetRepeatMode" => G::SetRepeatMode(RemoteRepeat::parse(text(&args, "RepeatMode")?)?),
        "SetShuffleQueue" => G::SetShuffle(RemoteShuffle::parse(text(&args, "ShuffleMode")?)?),
        "SetPlaybackOrder" => G::SetShuffle(RemoteShuffle::parse(text(&args, "PlaybackOrder")?)?),
        "PlayMediaSource" => return play_media_source(&args).map(RemoteCommand::Play),
        other => return Some(RemoteCommand::Unsupported(other.to_string())),
    };
    Some(RemoteCommand::General(command))
}

/// docs/31 §4.5: `PlayMediaSource` is a `Play` of one item with its media source.
fn play_media_source(args: &Args) -> Option<PlayRequest> {
    Some(PlayRequest {
        item_ids: vec![text(args, "ItemId")?.to_string()],
        start_index: None,
        start_position_ticks: optional_number(args, "StartPositionTicks").ok()?,
        play_command: PlayCommand::PlayNow,
        media_source_id: text(args, "MediaSourceId").map(str::to_string),
        audio_stream_index: optional_number(args, "AudioStreamIndex").ok()?,
        subtitle_stream_index: optional_number(args, "SubtitleStreamIndex").ok()?,
    })
}

/// The argument's value; an empty value counts as absent.
fn text<'a>(args: &'a Args, key: &str) -> Option<&'a str> {
    args.get(key).map(String::as_str).filter(|v| !v.is_empty())
}

fn number<T: FromStr>(args: &Args, key: &str) -> Option<T> {
    text(args, key)?.parse().ok()
}

/// `Ok(None)` when absent; `Err` when present and not a number.
fn optional_number<T: FromStr>(args: &Args, key: &str) -> Result<Option<T>, T::Err> {
    text(args, key).map(|value| value.parse::<T>()).transpose()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::ServerEvent;

    const ITEM_A: &str = "item-a";
    const ITEM_B: &str = "item-b";
    const USER: &str = "user-1";

    /// One frame through the real socket decoder: `Ok` for a decoded command, `Err(message type)`
    /// for a frame that fell back to `Ignored`.
    fn decode(message_type: &str, data: &str) -> Result<RemoteCommand, String> {
        let frame = format!(r#"{{"MessageType":"{message_type}","Data":{data}}}"#);
        match crate::ws::decode_message(&frame) {
            Some(ServerEvent::Remote(command)) => Ok(command),
            Some(ServerEvent::Ignored(name)) => Err(name),
            other => panic!("{frame}: unexpected: {other:?}"),
        }
    }

    fn args(pairs: &[(&str, &str)]) -> String {
        let map: serde_json::Map<String, Value> = pairs
            .iter()
            .map(|(k, v)| (k.to_string(), Value::String(v.to_string())))
            .collect();
        Value::Object(map).to_string()
    }

    fn general(name: &str, arguments: &str) -> Result<RemoteCommand, String> {
        decode(
            "GeneralCommand",
            &format!(r#"{{"Name":"{name}","ControllingUserId":"{USER}","Arguments":{arguments}}}"#),
        )
    }

    fn ignored(message_type: &str) -> Result<RemoteCommand, String> {
        Err(message_type.to_string())
    }

    fn play_request(command: PlayCommand) -> PlayRequest {
        PlayRequest {
            item_ids: vec![ITEM_A.into(), ITEM_B.into()],
            start_index: Some(1),
            start_position_ticks: Some(600_000_000),
            play_command: command,
            media_source_id: Some("source-1".into()),
            audio_stream_index: Some(2),
            subtitle_stream_index: Some(-1),
        }
    }

    #[test]
    fn decodes_every_play_command_with_all_its_fields() {
        for (wire, want) in [
            ("PlayNow", PlayCommand::PlayNow),
            ("PlayNext", PlayCommand::PlayNext),
            ("PlayLast", PlayCommand::PlayLast),
            ("PlayShuffle", PlayCommand::PlayShuffle),
            ("PlayInstantMix", PlayCommand::PlayInstantMix),
        ] {
            let data = format!(
                r#"{{"ItemIds":["{ITEM_A}","{ITEM_B}"],"StartIndex":1,"StartPositionTicks":600000000,"PlayCommand":"{wire}","ControllingUserId":"{USER}","MediaSourceId":"source-1","AudioStreamIndex":2,"SubtitleStreamIndex":-1}}"#
            );
            assert!(
                decode("Play", &data) == Ok(RemoteCommand::Play(play_request(want))),
                "{wire}"
            );
        }
    }

    /// Only `ItemIds` and `PlayCommand` are required; an absent or null optional is `None`, and a
    /// zero position stays a real position.
    #[test]
    fn play_optionals_default_to_none_and_zero_ticks_stay_zero() {
        let bare = RemoteCommand::Play(PlayRequest {
            item_ids: vec![ITEM_A.into()],
            start_index: None,
            start_position_ticks: None,
            play_command: PlayCommand::PlayNow,
            media_source_id: None,
            audio_stream_index: None,
            subtitle_stream_index: None,
        });
        let missing = format!(r#"{{"ItemIds":["{ITEM_A}"],"PlayCommand":"PlayNow"}}"#);
        let nulls = format!(
            r#"{{"ItemIds":["{ITEM_A}"],"PlayCommand":"PlayNow","StartIndex":null,"StartPositionTicks":null,"MediaSourceId":null,"AudioStreamIndex":null,"SubtitleStreamIndex":null}}"#
        );
        assert!(decode("Play", &missing) == Ok(bare.clone()), "missing");
        assert!(decode("Play", &nulls) == Ok(bare), "nulls");

        let zero =
            format!(r#"{{"ItemIds":["{ITEM_A}"],"PlayCommand":"PlayNow","StartPositionTicks":0}}"#);
        match decode("Play", &zero) {
            Ok(RemoteCommand::Play(request)) => {
                assert_eq!(request.start_position_ticks, Some(0));
            }
            _ => panic!("a zero start position must decode"),
        }
    }

    #[test]
    fn malformed_play_frames_are_ignored_by_name() {
        let cases = [
            "null".to_string(),
            r#""unexpected-string""#.to_string(),
            "{}".to_string(),
            r#"{"PlayCommand":"PlayNow"}"#.to_string(),
            r#"{"ItemIds":null,"PlayCommand":"PlayNow"}"#.to_string(),
            r#"{"ItemIds":[],"PlayCommand":"PlayNow"}"#.to_string(),
            r#"{"ItemIds":[5],"PlayCommand":"PlayNow"}"#.to_string(),
            format!(r#"{{"ItemIds":["{ITEM_A}"]}}"#),
            format!(r#"{{"ItemIds":["{ITEM_A}"],"PlayCommand":"PlayFuture"}}"#),
            format!(r#"{{"ItemIds":["{ITEM_A}"],"PlayCommand":"PlayNow","StartIndex":-1}}"#),
            format!(r#"{{"ItemIds":["{ITEM_A}"],"PlayCommand":"PlayNow","StartIndex":"x"}}"#),
            format!(
                r#"{{"ItemIds":["{ITEM_A}"],"PlayCommand":"PlayNow","StartPositionTicks":"x"}}"#
            ),
            format!(r#"{{"ItemIds":["{ITEM_A}"],"PlayCommand":"PlayNow","AudioStreamIndex":"x"}}"#),
        ];
        for data in cases {
            assert!(decode("Play", &data) == ignored("Play"), "{data}");
        }
        match crate::ws::decode_message(r#"{"MessageType":"Play"}"#) {
            Some(ServerEvent::Ignored(name)) => assert_eq!(name, "Play"),
            other => panic!("a Play frame without Data: unexpected: {other:?}"),
        }
    }

    #[test]
    fn decodes_every_playstate_command() {
        for (wire, want) in [
            ("Stop", PlaystateCommand::Stop),
            ("Pause", PlaystateCommand::Pause),
            ("Unpause", PlaystateCommand::Unpause),
            ("NextTrack", PlaystateCommand::NextTrack),
            ("PreviousTrack", PlaystateCommand::PreviousTrack),
            ("Rewind", PlaystateCommand::Rewind),
            ("FastForward", PlaystateCommand::FastForward),
            ("PlayPause", PlaystateCommand::PlayPause),
        ] {
            let data = format!(r#"{{"Command":"{wire}","ControllingUserId":"{USER}"}}"#);
            assert!(
                decode("Playstate", &data) == Ok(RemoteCommand::Playstate(want)),
                "{wire}"
            );
        }
        for (ticks, want) in [("600000000", 600_000_000), ("0", 0)] {
            let data = format!(r#"{{"Command":"Seek","SeekPositionTicks":{ticks}}}"#);
            assert!(
                decode("Playstate", &data)
                    == Ok(RemoteCommand::Playstate(PlaystateCommand::Seek {
                        position_ticks: want
                    })),
                "Seek {ticks}"
            );
        }
    }

    #[test]
    fn malformed_playstate_frames_are_ignored_by_name() {
        for data in [
            "null",
            r#""unexpected-string""#,
            "{}",
            r#"{"Command":null}"#,
            r#"{"Command":"Fling"}"#,
            r#"{"Command":"Seek"}"#,
            r#"{"Command":"Seek","SeekPositionTicks":null}"#,
            r#"{"Command":"Seek","SeekPositionTicks":"x"}"#,
            r#"{"SeekPositionTicks":5}"#,
        ] {
            assert!(decode("Playstate", data) == ignored("Playstate"), "{data}");
        }
    }

    #[test]
    fn decodes_every_argument_free_general_command() {
        use GeneralCommand as G;
        for (name, want) in [
            ("VolumeUp", G::VolumeUp),
            ("VolumeDown", G::VolumeDown),
            ("Mute", G::Mute),
            ("Unmute", G::Unmute),
            ("ToggleMute", G::ToggleMute),
            ("MoveUp", G::MoveUp),
            ("MoveDown", G::MoveDown),
            ("MoveLeft", G::MoveLeft),
            ("MoveRight", G::MoveRight),
            ("Select", G::Select),
            ("Back", G::Back),
            ("PageUp", G::PageUp),
            ("PageDown", G::PageDown),
            ("PreviousLetter", G::PreviousLetter),
            ("NextLetter", G::NextLetter),
            ("GoHome", G::GoHome),
            ("GoToSearch", G::GoToSearch),
            ("GoToSettings", G::GoToSettings),
            ("ToggleContextMenu", G::ToggleContextMenu),
            ("ToggleOsd", G::ToggleOsd),
            ("ToggleOsdMenu", G::ToggleOsdMenu),
            ("ToggleFullscreen", G::ToggleFullscreen),
            ("ToggleStats", G::ToggleStats),
        ] {
            let want = Ok(RemoteCommand::General(want));
            assert!(general(name, "{}") == want, "{name} with empty Arguments");
            assert!(general(name, "null") == want, "{name} with null Arguments");
            let bare = decode("GeneralCommand", &format!(r#"{{"Name":"{name}"}}"#));
            assert!(bare == want, "{name} without Arguments");
        }
    }

    #[test]
    fn decodes_every_general_command_that_takes_arguments() {
        use GeneralCommand as G;
        let rows: Vec<(&str, String, GeneralCommand)> = vec![
            (
                "SetAudioStreamIndex",
                args(&[("Index", "3")]),
                G::SetAudioStreamIndex(3),
            ),
            (
                "SetSubtitleStreamIndex",
                args(&[("Index", "4")]),
                G::SetSubtitleStreamIndex(4),
            ),
            (
                "SetSubtitleStreamIndex",
                args(&[("Index", "-1")]),
                G::SetSubtitleStreamIndex(-1),
            ),
            (
                "DisplayContent",
                args(&[
                    ("ItemId", ITEM_A),
                    ("ItemType", "Movie"),
                    ("ItemName", "ignored"),
                ]),
                G::DisplayContent {
                    item_id: ITEM_A.into(),
                    item_type: "Movie".into(),
                },
            ),
            (
                "DisplayMessage",
                args(&[
                    ("Header", "Hello"),
                    ("Text", "World"),
                    ("TimeoutMs", "5000"),
                ]),
                G::DisplayMessage {
                    header: Some("Hello".into()),
                    text: "World".into(),
                },
            ),
            (
                "DisplayMessage",
                args(&[("Text", "World")]),
                G::DisplayMessage {
                    header: None,
                    text: "World".into(),
                },
            ),
            (
                "DisplayMessage",
                args(&[("Header", ""), ("Text", "World")]),
                G::DisplayMessage {
                    header: None,
                    text: "World".into(),
                },
            ),
            (
                "SendString",
                args(&[("String", "hello world")]),
                G::SendString("hello world".into()),
            ),
            ("SetVolume", args(&[("Volume", "0")]), G::SetVolume(0)),
            ("SetVolume", args(&[("Volume", "42")]), G::SetVolume(42)),
            ("SetVolume", args(&[("Volume", "100")]), G::SetVolume(100)),
            ("SetVolume", args(&[("Volume", "150")]), G::SetVolume(100)),
            ("SetVolume", args(&[("Volume", "-5")]), G::SetVolume(0)),
            (
                "SetRepeatMode",
                args(&[("RepeatMode", "RepeatAll")]),
                G::SetRepeatMode(RemoteRepeat::All),
            ),
            (
                "SetRepeatMode",
                args(&[("RepeatMode", "RepeatOne")]),
                G::SetRepeatMode(RemoteRepeat::One),
            ),
            (
                "SetRepeatMode",
                args(&[("RepeatMode", "RepeatNone")]),
                G::SetRepeatMode(RemoteRepeat::Off),
            ),
            (
                "SetShuffleQueue",
                args(&[("ShuffleMode", "Sorted")]),
                G::SetShuffle(RemoteShuffle::Sorted),
            ),
            (
                "SetShuffleQueue",
                args(&[("ShuffleMode", "Shuffle")]),
                G::SetShuffle(RemoteShuffle::Shuffled),
            ),
            (
                "SetPlaybackOrder",
                args(&[("PlaybackOrder", "Default")]),
                G::SetShuffle(RemoteShuffle::Sorted),
            ),
            (
                "SetPlaybackOrder",
                args(&[("PlaybackOrder", "Shuffle")]),
                G::SetShuffle(RemoteShuffle::Shuffled),
            ),
        ];
        for (name, arguments, want) in rows {
            assert!(
                general(name, &arguments) == Ok(RemoteCommand::General(want)),
                "{name} {arguments}"
            );
        }
    }

    /// docs/31 §4.5: `PlayMediaSource` is a `Play` of one item with its media source, played now.
    #[test]
    fn play_media_source_decodes_as_a_play_with_its_media_source() {
        let full = args(&[
            ("ItemId", ITEM_A),
            ("MediaSourceId", "source-1"),
            ("StartPositionTicks", "600000000"),
            ("AudioStreamIndex", "2"),
            ("SubtitleStreamIndex", "-1"),
        ]);
        let want = RemoteCommand::Play(PlayRequest {
            item_ids: vec![ITEM_A.into()],
            start_index: None,
            start_position_ticks: Some(600_000_000),
            play_command: PlayCommand::PlayNow,
            media_source_id: Some("source-1".into()),
            audio_stream_index: Some(2),
            subtitle_stream_index: Some(-1),
        });
        assert!(general("PlayMediaSource", &full) == Ok(want));

        let bare = RemoteCommand::Play(PlayRequest {
            item_ids: vec![ITEM_A.into()],
            start_index: None,
            start_position_ticks: None,
            play_command: PlayCommand::PlayNow,
            media_source_id: Some("source-1".into()),
            audio_stream_index: None,
            subtitle_stream_index: None,
        });
        let arguments = args(&[("ItemId", ITEM_A), ("MediaSourceId", "source-1")]);
        assert!(general("PlayMediaSource", &arguments) == Ok(bare));
    }

    /// Every command that reads an argument ignores the frame when it is missing or unparseable.
    #[test]
    fn general_commands_with_missing_or_bad_arguments_are_ignored_by_name() {
        let rows: Vec<(&str, String)> = vec![
            ("SetAudioStreamIndex", args(&[])),
            ("SetAudioStreamIndex", args(&[("Index", "x")])),
            ("SetAudioStreamIndex", args(&[("Index", "")])),
            ("SetAudioStreamIndex", args(&[("Index", "1.5")])),
            ("SetAudioStreamIndex", args(&[("Index", "99999999999")])),
            ("SetSubtitleStreamIndex", args(&[])),
            ("SetSubtitleStreamIndex", args(&[("Index", "off")])),
            ("DisplayContent", args(&[])),
            ("DisplayContent", args(&[("ItemId", ITEM_A)])),
            ("DisplayContent", args(&[("ItemType", "Movie")])),
            ("DisplayMessage", args(&[])),
            ("DisplayMessage", args(&[("Header", "Hello")])),
            ("DisplayMessage", args(&[("Text", "")])),
            ("SendString", args(&[])),
            ("SendString", args(&[("String", "")])),
            ("SetVolume", args(&[])),
            ("SetVolume", args(&[("Volume", "loud")])),
            ("SetRepeatMode", args(&[])),
            ("SetRepeatMode", args(&[("RepeatMode", "RepeatSometimes")])),
            ("SetShuffleQueue", args(&[])),
            ("SetShuffleQueue", args(&[("ShuffleMode", "Sideways")])),
            ("SetPlaybackOrder", args(&[])),
            ("SetPlaybackOrder", args(&[("PlaybackOrder", "Sideways")])),
            ("PlayMediaSource", args(&[])),
            ("PlayMediaSource", args(&[("MediaSourceId", "source-1")])),
            (
                "PlayMediaSource",
                args(&[("ItemId", ITEM_A), ("AudioStreamIndex", "x")]),
            ),
            (
                "PlayMediaSource",
                args(&[("ItemId", ITEM_A), ("StartPositionTicks", "x")]),
            ),
        ];
        for (name, arguments) in rows {
            assert!(
                general(name, &arguments) == ignored("GeneralCommand"),
                "{name} {arguments}"
            );
        }
    }

    #[test]
    fn malformed_general_command_envelopes_are_ignored_by_name() {
        for data in [
            "null",
            r#""unexpected-string""#,
            "{}",
            r#"{"Name":null}"#,
            r#"{"Arguments":{"Index":"3"}}"#,
            r#"{"Name":"SetAudioStreamIndex","Arguments":{"Index":3}}"#,
            r#"{"Name":"SetAudioStreamIndex","Arguments":"Index"}"#,
        ] {
            assert!(
                decode("GeneralCommand", data) == ignored("GeneralCommand"),
                "{data}"
            );
        }
    }

    /// docs/31 §4.6: a name this build doesn't handle is kept, so Kotlin logs it; the protocol's
    /// own names we leave out (`TakeScreenshot`, trailers, Live TV) are unsupported too.
    #[test]
    fn unknown_general_command_names_are_unsupported_by_name() {
        for name in [
            "TakeScreenshot",
            "SetMaxStreamingBitrate",
            "PlayTrailers",
            "Guide",
            "ChannelUp",
            "ChannelDown",
            "SendKey",
            "PlayState",
            "FutureCommand",
        ] {
            let want = Ok(RemoteCommand::Unsupported(name.to_string()));
            assert!(general(name, "{}") == want, "{name}");
            assert!(
                general(name, &args(&[("ItemId", ITEM_A)])) == want,
                "{name} with arguments"
            );
        }
    }

    /// Typed text and ids come from another person's controller and never reach a log line.
    #[test]
    fn debug_of_a_remote_command_never_prints_its_values() {
        let secret = "private-text";
        let commands = [
            general("SendString", &args(&[("String", secret)])),
            general("DisplayMessage", &args(&[("Text", secret)])),
            decode(
                "Play",
                &format!(r#"{{"ItemIds":["{secret}"],"PlayCommand":"PlayNow"}}"#),
            ),
        ];
        for command in commands {
            let shown = format!("{:?}", command.expect("decodes"));
            assert!(!shown.contains(secret), "{shown}");
        }
    }
}
