//! Playback decision: pick the best `MediaSource` from a `PlaybackInfoResponse`
//! honoring the server's SupportsDirectPlay/SupportsDirectStream/
//! TranscodingUrl verdict, and synthesize human-readable transcode reasons.
//! [`extract_facts`] round-trips each source through `serde_json` into
//! [`SourceFacts`]; the decision matrix ([`choose`]) is fixture-tested
//! directly against [`SourceFacts`], independent of extraction.

use jellyfin_api::models::MediaSourceInfo;
#[cfg(test)]
use jellyfin_api::models::{MediaStream, MediaStreamType};
use serde::Deserialize;

use crate::CoreError;

#[derive(Debug, Clone, Deserialize, Default, PartialEq)]
#[serde(rename_all = "PascalCase", default)]
pub(crate) struct SourceFacts {
    pub id: Option<String>,
    pub container: Option<String>,
    pub path: Option<String>,
    pub protocol: Option<String>,
    pub supports_direct_play: bool,
    pub supports_direct_stream: bool,
    pub supports_transcoding: bool,
    pub transcoding_url: Option<String>,
    pub media_streams: Vec<StreamFacts>,
}

/// Just enough of a `MediaStream` to judge direct-playability with (see [`is_codec_blind`]).
#[derive(Debug, Clone, Deserialize, Default, PartialEq)]
#[serde(rename_all = "PascalCase", default)]
pub(crate) struct StreamFacts {
    pub codec: Option<String>,
    #[serde(rename = "Type")]
    pub stream_type: Option<String>,
}

/// Best-effort extraction -- never panics even on missing optional fields.
pub(crate) fn extract_facts(source: &MediaSourceInfo) -> SourceFacts {
    serde_json::to_value(source)
        .ok()
        .and_then(|v| serde_json::from_value(v).ok())
        .unwrap_or_default()
}

/// True when `facts` carries no Video or Audio stream with a non-empty
/// `Codec` (zero streams counts as blind too); subtitle/other types never
/// count. Comparison is case-insensitive.
pub(crate) fn is_codec_blind(facts: &SourceFacts) -> bool {
    !facts.media_streams.iter().any(|stream| {
        let is_video_or_audio = stream
            .stream_type
            .as_deref()
            .is_some_and(|t| t.eq_ignore_ascii_case("Video") || t.eq_ignore_ascii_case("Audio"));
        let has_codec = stream.codec.as_deref().is_some_and(|c| !c.is_empty());
        is_video_or_audio && has_codec
    })
}

#[derive(Debug, Clone, PartialEq)]
pub(crate) enum Choice {
    DirectPlay { index: usize },
    Transcode { index: usize, reasons: Vec<String> },
}

impl Choice {
    pub(crate) fn index(&self) -> usize {
        match self {
            Choice::DirectPlay { index } | Choice::Transcode { index, .. } => *index,
        }
    }
}

/// Pure decision matrix, independently testable with fixtures:
/// - SupportsDirectPlay/SupportsDirectStream wins, source-list order.
/// - Else the first [`is_codec_blind`] source with a `TranscodingUrl` plays
///   directly: a "no direct play" verdict only means something when the
///   server had codec facts to judge with (some plugin channels report
///   every `Codec` null and get denied purely because our profile declares
///   codecs at all) -- Direct Play stays the default. The rule keys off
///   server facts, never plugin identity (a channel is not reliably
///   identifiable on a MediaSource), and under transcoding-strictly-opt-in a
///   codec-blind source without it is unplayable, not transcoded. Accepted
///   residual risk: with transcoding opted in, a codec-blind source the
///   decoder genuinely cannot play still direct-plays and fails there.
/// - Else the first source with a `TranscodingUrl` is used.
/// - Else `NoPlayableSource`.
pub(crate) fn choose(sources: &[SourceFacts]) -> Result<Choice, CoreError> {
    if sources.is_empty() {
        return Err(CoreError::NoPlayableSource);
    }

    if let Some((index, _)) = sources
        .iter()
        .enumerate()
        .find(|(_, f)| f.supports_direct_play || f.supports_direct_stream)
    {
        return Ok(Choice::DirectPlay { index });
    }

    if let Some((index, facts)) = sources
        .iter()
        .enumerate()
        .find(|(_, f)| f.transcoding_url.is_some() && is_codec_blind(f))
    {
        tracing::info!(
            media_source_id = facts.id.as_deref().unwrap_or("<unknown>"),
            "server judged direct play unsupported with no codec facts to judge by; \
             playing directly via the static stream endpoint instead of transcoding"
        );
        return Ok(Choice::DirectPlay { index });
    }

    if let Some((index, facts)) = sources
        .iter()
        .enumerate()
        .find(|(_, f)| f.transcoding_url.is_some())
    {
        return Ok(Choice::Transcode {
            index,
            reasons: synthesize_reasons(facts),
        });
    }

    Err(CoreError::NoPlayableSource)
}

/// `MediaSourceInfo` (PlaybackInfo, server v12.0.0) has no structured
/// `TranscodeReasons` field (that's only on `/Sessions`'s `TranscodingInfo`),
/// so this synthesizes a readable reason from the server's support flags.
fn synthesize_reasons(facts: &SourceFacts) -> Vec<String> {
    let mut reasons = Vec::new();
    if !facts.supports_direct_play {
        reasons.push("server: direct play not supported for this source".to_string());
    }
    if !facts.supports_direct_stream {
        reasons.push("server: direct stream not supported for this source".to_string());
    }
    if let Some(container) = &facts.container {
        reasons.push(format!("container '{container}' requires transcoding"));
    }
    if reasons.is_empty() {
        // Flags looked fine but the server still sent a TranscodingUrl; say so rather than going
        // silent.
        reasons.push("server selected transcoding for this source".to_string());
    }
    reasons
}

#[cfg(test)]
mod tests {
    use super::*;

    fn direct_play(id: &str) -> SourceFacts {
        SourceFacts {
            id: Some(id.to_string()),
            supports_direct_play: true,
            ..Default::default()
        }
    }

    fn direct_stream(id: &str) -> SourceFacts {
        SourceFacts {
            id: Some(id.to_string()),
            supports_direct_stream: true,
            ..Default::default()
        }
    }

    /// A source the server genuinely judged incompatible (real codec facts, still no direct play).
    fn transcode_only(id: &str, container: &str) -> SourceFacts {
        SourceFacts {
            id: Some(id.to_string()),
            container: Some(container.to_string()),
            transcoding_url: Some(format!("/videos/{id}/master.m3u8")),
            supports_transcoding: true,
            media_streams: vec![
                stream("Video", Some("wmv3")),
                stream("Audio", Some("wmav2")),
            ],
            ..Default::default()
        }
    }

    fn unplayable(id: &str) -> SourceFacts {
        SourceFacts {
            id: Some(id.to_string()),
            ..Default::default()
        }
    }

    fn stream(stream_type: &str, codec: Option<&str>) -> StreamFacts {
        StreamFacts {
            stream_type: Some(stream_type.to_string()),
            codec: codec.map(str::to_string),
        }
    }

    /// A plugin-channel source with no codec facts (`Codec: null` on every
    /// stream) that the server still marked not directly playable.
    fn codec_blind(id: &str) -> SourceFacts {
        SourceFacts {
            id: Some(id.to_string()),
            transcoding_url: Some(format!("/videos/{id}/master.m3u8")),
            supports_transcoding: true,
            media_streams: vec![stream("Video", None), stream("Audio", None)],
            ..Default::default()
        }
    }

    #[test]
    fn picks_direct_play_source() {
        let sources = vec![direct_play("a")];
        assert_eq!(
            choose(&sources).expect("test assertion"),
            Choice::DirectPlay { index: 0 }
        );
    }

    #[test]
    fn picks_direct_stream_source() {
        let sources = vec![direct_stream("a")];
        assert_eq!(
            choose(&sources).expect("test assertion"),
            Choice::DirectPlay { index: 0 }
        );
    }

    #[test]
    fn direct_play_preferred_over_transcode_when_both_present() {
        let sources = vec![transcode_only("a", "wmv"), direct_play("b")];
        assert_eq!(
            choose(&sources).expect("test assertion"),
            Choice::DirectPlay { index: 1 }
        );
    }

    #[test]
    fn falls_back_to_transcode_with_reasons() {
        let sources = vec![transcode_only("a", "wmv")];
        match choose(&sources).expect("test assertion") {
            Choice::Transcode { index, reasons } => {
                assert_eq!(index, 0);
                assert!(reasons.iter().any(|r| r.contains("direct play")));
                assert!(reasons.iter().any(|r| r.contains("wmv")));
            }
            other => panic!("expected Transcode, got {other:?}"),
        }
    }

    #[test]
    fn no_playable_source_when_list_empty() {
        assert!(matches!(
            choose(&[]).expect_err("test assertion"),
            CoreError::NoPlayableSource
        ));
    }

    #[test]
    fn no_playable_source_when_nothing_supports_anything() {
        let sources = vec![unplayable("a"), unplayable("b")];
        assert!(matches!(
            choose(&sources).expect_err("test assertion"),
            CoreError::NoPlayableSource
        ));
    }

    // --- codec-blind rule -------------------------------------------------

    #[test]
    fn picks_codec_blind_source_over_transcoding_when_server_had_no_codec_facts() {
        let sources = vec![codec_blind("a")];
        assert_eq!(
            choose(&sources).expect("test assertion"),
            Choice::DirectPlay { index: 0 }
        );
    }

    #[test]
    fn does_not_widen_rule_to_a_source_with_real_codec_facts() {
        // The server had real codec facts to judge with, so this must still transcode.
        let sources = vec![transcode_only("a", "wmv")];
        match choose(&sources).expect("test assertion") {
            Choice::Transcode { index, .. } => assert_eq!(index, 0),
            other => panic!("expected Transcode, got {other:?}"),
        }
    }

    #[test]
    fn a_real_direct_play_source_wins_over_a_later_codec_blind_one() {
        let sources = vec![codec_blind("a"), direct_play("b")];
        assert_eq!(
            choose(&sources).expect("test assertion"),
            Choice::DirectPlay { index: 1 }
        );
    }

    #[test]
    fn zero_media_streams_counts_as_codec_blind() {
        let facts = SourceFacts {
            id: Some("a".to_string()),
            transcoding_url: Some("/videos/a/master.m3u8".to_string()),
            supports_transcoding: true,
            media_streams: Vec::new(),
            ..Default::default()
        };
        assert!(is_codec_blind(&facts));
        assert_eq!(
            choose(&[facts]).expect("test assertion"),
            Choice::DirectPlay { index: 0 }
        );
    }

    #[test]
    fn subtitle_only_codec_info_still_counts_as_blind() {
        let facts = SourceFacts {
            id: Some("a".to_string()),
            transcoding_url: Some("/videos/a/master.m3u8".to_string()),
            supports_transcoding: true,
            media_streams: vec![
                stream("Video", None),
                stream("Audio", None),
                stream("Subtitle", Some("subrip")),
            ],
            ..Default::default()
        };
        assert!(is_codec_blind(&facts));
        assert_eq!(
            choose(&[facts]).expect("test assertion"),
            Choice::DirectPlay { index: 0 }
        );
    }

    #[test]
    fn a_video_stream_with_a_real_codec_is_not_blind() {
        let facts = SourceFacts {
            id: Some("a".to_string()),
            media_streams: vec![stream("Video", Some("h264")), stream("Audio", None)],
            ..Default::default()
        };
        assert!(!is_codec_blind(&facts));
    }

    #[test]
    fn extract_facts_round_trip_carries_media_streams_and_codecs() {
        let source = MediaSourceInfo {
            id: Some("abc".into()),
            supports_direct_play: Some(false),
            supports_transcoding: Some(true),
            transcoding_url: Some("/videos/abc/master.m3u8".into()),
            media_streams: vec![
                MediaStream {
                    type_: Some(MediaStreamType::Video),
                    codec: Some("h264".into()),
                    ..Default::default()
                },
                MediaStream {
                    type_: Some(MediaStreamType::Audio),
                    codec: Some("aac".into()),
                    ..Default::default()
                },
            ],
            ..Default::default()
        };
        let facts = extract_facts(&source);
        assert_eq!(facts.media_streams.len(), 2);
        assert_eq!(facts.media_streams[0].stream_type.as_deref(), Some("Video"));
        assert_eq!(facts.media_streams[0].codec.as_deref(), Some("h264"));
        assert_eq!(facts.media_streams[1].stream_type.as_deref(), Some("Audio"));
        assert_eq!(facts.media_streams[1].codec.as_deref(), Some("aac"));
        assert!(!is_codec_blind(&facts));
        // Real codec facts, so the codec-blind rule must not fire despite SupportsDirectPlay being
        // false.
        match choose(&[facts]).expect("test assertion") {
            Choice::Transcode { .. } => {}
            other => panic!("expected Transcode, got {other:?}"),
        }
    }

    #[test]
    fn extract_facts_defaults_missing_fields_safely() {
        let source = MediaSourceInfo {
            id: Some("abc".into()),
            ..Default::default()
        };
        let facts = extract_facts(&source);
        assert_eq!(facts.id.as_deref(), Some("abc"));
        assert!(!facts.supports_direct_play);
        assert!(!facts.supports_direct_stream);
        assert!(facts.transcoding_url.is_none());
    }

    #[test]
    fn extract_facts_reads_real_capability_fields_from_media_source_info() {
        let source = MediaSourceInfo {
            id: Some("abc".into()),
            container: Some("mkv".into()),
            supports_direct_play: Some(true),
            supports_direct_stream: Some(false),
            supports_transcoding: Some(true),
            ..Default::default()
        };
        let facts = extract_facts(&source);
        assert!(facts.supports_direct_play);
        assert!(!facts.supports_direct_stream);
        assert_eq!(facts.container.as_deref(), Some("mkv"));
        assert_eq!(
            choose(&[facts]).expect("test assertion"),
            Choice::DirectPlay { index: 0 }
        );
    }

    #[test]
    fn extract_facts_from_raw_json_matches_spec_field_names() {
        // Simulates the exact wire JSON shape via direct SourceFacts deserialization.
        let json = serde_json::json!({
            "Id": "abc",
            "Container": "mkv",
            "SupportsDirectPlay": true,
            "SupportsDirectStream": false,
            "SupportsTranscoding": true,
        });
        let facts: SourceFacts = serde_json::from_value(json).expect("test assertion");
        assert!(facts.supports_direct_play);
        assert_eq!(
            choose(&[facts]).expect("test assertion"),
            Choice::DirectPlay { index: 0 }
        );
    }
}
