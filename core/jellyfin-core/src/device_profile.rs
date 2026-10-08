//! Richly-typed model of Jellyfin's `DeviceProfile` JSON contract; field names match the pinned
//! OpenAPI spec (server v12.0.0).
//!
//! [`android_tv_profile`] builds a [`RawDeviceProfile`]; `ffi` round-trips it through
//! `serde_json::Value` into `jellyfin_api::models::DeviceProfile` (identical JSON keys). Tests
//! here assert against [`RawDeviceProfile`] directly.

use serde::{Deserialize, Serialize};

/// HLS/TS video codecs the transcoder falls back to (VideoToolbox hardware-encodes both).
/// docs/18 §2: transcode targets, kept only when this device decodes them (H.264 always).
const TRANSCODE_VIDEO_CODECS: &[VideoCodec] = &[VideoCodec::H264, VideoCodec::Hevc];
const TRANSCODE_AUDIO_CODECS: &str = "aac,ac3";
/// 5.1 baseline for the transcoded fallback stream; direct play keeps the source's real layout.
const TRANSCODE_MAX_AUDIO_CHANNELS: &str = "6";
const HLS_SEGMENT_LENGTH_SECS: i32 = 6;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum DlnaProfileType {
    Audio,
    Video,
    Photo,
    Subtitle,
    Lyric,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum MediaStreamProtocol {
    #[serde(rename = "http")]
    Http,
    #[serde(rename = "hls")]
    Hls,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum TranscodeSeekInfo {
    Auto,
    Bytes,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum EncodingContext {
    Streaming,
    Static,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum CodecType {
    Video,
    VideoAudio,
    Audio,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum SubtitleDeliveryMethod {
    Encode,
    Embed,
    External,
    Hls,
    Drop,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum ProfileConditionType {
    Equals,
    NotEquals,
    LessThanEqual,
    GreaterThanEqual,
    EqualsAny,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum ProfileConditionValue {
    AudioChannels,
    AudioBitrate,
    AudioProfile,
    Width,
    Height,
    Has64BitOffsets,
    PacketLength,
    VideoBitDepth,
    VideoBitrate,
    VideoFramerate,
    VideoLevel,
    VideoProfile,
    VideoTimestamp,
    IsAnamorphic,
    RefFrames,
    NumAudioStreams,
    NumVideoStreams,
    IsSecondaryAudio,
    VideoCodecTag,
    IsAvc,
    IsInterlaced,
    AudioSampleRate,
    AudioBitDepth,
    VideoRangeType,
    NumStreams,
    VideoRotation,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "PascalCase")]
pub struct ProfileCondition {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub condition: Option<ProfileConditionType>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub property: Option<ProfileConditionValue>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub value: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub is_required: Option<bool>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "PascalCase")]
pub struct DirectPlayProfile {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub container: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub audio_codec: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub video_codec: Option<String>,
    #[serde(rename = "Type", skip_serializing_if = "Option::is_none")]
    pub kind: Option<DlnaProfileType>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "PascalCase")]
pub struct TranscodingProfile {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub container: Option<String>,
    #[serde(rename = "Type", skip_serializing_if = "Option::is_none")]
    pub kind: Option<DlnaProfileType>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub video_codec: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub audio_codec: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub protocol: Option<MediaStreamProtocol>,
    pub estimate_content_length: bool,
    pub enable_mpegts_m2_ts_mode: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub transcode_seek_info: Option<TranscodeSeekInfo>,
    pub copy_timestamps: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub context: Option<EncodingContext>,
    pub enable_subtitles_in_manifest: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub max_audio_channels: Option<String>,
    pub min_segments: i32,
    pub segment_length: i32,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub break_on_non_key_frames: Option<bool>,
    #[serde(default)]
    pub conditions: Vec<ProfileCondition>,
    pub enable_audio_vbr_encoding: bool,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "PascalCase")]
pub struct ContainerProfile {
    #[serde(rename = "Type", skip_serializing_if = "Option::is_none")]
    pub kind: Option<DlnaProfileType>,
    #[serde(default)]
    pub conditions: Vec<ProfileCondition>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub container: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub sub_container: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "PascalCase")]
pub struct CodecProfile {
    #[serde(rename = "Type", skip_serializing_if = "Option::is_none")]
    pub kind: Option<CodecType>,
    #[serde(default)]
    pub conditions: Vec<ProfileCondition>,
    #[serde(default)]
    pub apply_conditions: Vec<ProfileCondition>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub codec: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub container: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub sub_container: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "PascalCase")]
pub struct SubtitleProfile {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub format: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub method: Option<SubtitleDeliveryMethod>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub didl_mode: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub language: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub container: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, Default)]
#[serde(rename_all = "PascalCase", default)]
pub struct RawDeviceProfile {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub max_streaming_bitrate: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub max_static_bitrate: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub music_streaming_transcoding_bitrate: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub max_static_music_bitrate: Option<i32>,
    pub direct_play_profiles: Vec<DirectPlayProfile>,
    pub transcoding_profiles: Vec<TranscodingProfile>,
    pub container_profiles: Vec<ContainerProfile>,
    pub codec_profiles: Vec<CodecProfile>,
    pub subtitle_profiles: Vec<SubtitleProfile>,
}

/// Jellyfin falls back to a server-side default cap (~8 Mbps) when `MaxStreamingBitrate` is
/// omitted; "no cap" must be an explicit huge number instead (1 Gbps, matching jellyfin-web).
pub(crate) const UNCAPPED_STREAMING_BITRATE: u32 = 1_000_000_000;

fn clamp_to_i32(bitrate: u32) -> i32 {
    bitrate.min(i32::MAX as u32) as i32
}

/// The HLS transcode target. Its video codecs are what this device decodes: the server copies a
/// source whose codec is listed, so an undecodable one listed here comes back unchanged.
fn transcoding_profile(caps: &AndroidTvCaps) -> TranscodingProfile {
    let decodable = android_direct_play_video_codecs(caps);
    let video_codec = TRANSCODE_VIDEO_CODECS
        .iter()
        .filter(|codec| **codec == VideoCodec::H264 || decodable.contains(codec))
        .map(|codec| codec.as_str())
        .collect::<Vec<_>>()
        .join(",");
    TranscodingProfile {
        container: Some("ts".to_string()),
        kind: Some(DlnaProfileType::Video),
        video_codec: Some(video_codec),
        audio_codec: Some(TRANSCODE_AUDIO_CODECS.to_string()),
        protocol: Some(MediaStreamProtocol::Hls),
        estimate_content_length: false,
        enable_mpegts_m2_ts_mode: true,
        transcode_seek_info: Some(TranscodeSeekInfo::Auto),
        copy_timestamps: false,
        context: Some(EncodingContext::Streaming),
        enable_subtitles_in_manifest: true,
        max_audio_channels: Some(TRANSCODE_MAX_AUDIO_CHANNELS.to_string()),
        min_segments: 1,
        segment_length: HLS_SEGMENT_LENGTH_SECS,
        break_on_non_key_frames: Some(true),
        conditions: Vec::new(),
        enable_audio_vbr_encoding: true,
    }
}

fn subtitle_profile(format: &str, method: SubtitleDeliveryMethod) -> SubtitleProfile {
    SubtitleProfile {
        format: Some(format.to_string()),
        method: Some(method),
        didl_mode: None,
        language: None,
        container: None,
    }
}

// Android TV (Jellybeam TV) profile (docs/05 "Device profile"): video decode is real hardware
// `MediaCodec`, probed per device via [`AndroidTvCaps`]; containers/audio/subtitles are constant.

/// One video codec's `MediaCodec` decode capability as probed by Kotlin; fields default open.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct VideoCodecCaps {
    pub codec: VideoCodec,
    /// Decoder profile names, lower-cased to match `VideoProfile`; empty means unconstrained.
    pub profiles: Vec<String>,
    /// Highest decoder level (`VideoLevel` units); a server condition only when intolerant.
    pub max_level: Option<i32>,
    /// Profile-specific level ceilings; same intolerant-only rule as [`Self::max_level`].
    pub profile_levels: Vec<VideoProfileLevel>,
    pub max_width: Option<u32>,
    pub max_height: Option<u32>,
    /// `VideoRangeType` names (e.g. "DOVI", "HDR10Plus") excluded via a `NotEquals` condition.
    pub unsupported_video_ranges: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct VideoProfileLevel {
    pub profile: String,
    pub max_level: i32,
}

/// Video codecs Jellybeam TV can advertise; no software fallback, so unprobed codecs are excluded.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum VideoCodec {
    H264,
    Hevc,
    Av1,
    Vp8,
    Vp9,
    Mpeg2Video,
    Vc1,
}

impl VideoCodec {
    /// The ffmpeg/Jellyfin wire name, distinct from the enum's own FFI `Serialize` casing.
    fn as_str(self) -> &'static str {
        match self {
            VideoCodec::H264 => "h264",
            VideoCodec::Hevc => "hevc",
            VideoCodec::Av1 => "av1",
            VideoCodec::Vp8 => "vp8",
            VideoCodec::Vp9 => "vp9",
            VideoCodec::Mpeg2Video => "mpeg2video",
            VideoCodec::Vc1 => "vc1",
        }
    }

    /// The reverse of [`Self::as_str`], plus server aliases (`"avc"`/`"h265"`), used by Auto mode's
    /// local-codec-corroboration check (docs/18-playback-quality.md §2); `codec` must be
    /// lowercased. `None` for a codec with no [`VideoCodec`] variant: never locally supported.
    pub fn from_server_codec(codec: &str) -> Option<VideoCodec> {
        match codec {
            "h264" | "avc" => Some(VideoCodec::H264),
            "hevc" | "h265" => Some(VideoCodec::Hevc),
            "av1" => Some(VideoCodec::Av1),
            "vp8" => Some(VideoCodec::Vp8),
            "vp9" => Some(VideoCodec::Vp9),
            "mpeg2video" => Some(VideoCodec::Mpeg2Video),
            "vc1" => Some(VideoCodec::Vc1),
            _ => None,
        }
    }
}

/// Per-device Android TV capabilities, probed by Kotlin over the FFI (see
/// [`UNCAPPED_STREAMING_BITRATE`] for `max_streaming_bitrate`'s default).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, Default)]
pub struct AndroidTvCaps {
    pub max_streaming_bitrate: Option<u32>,
    pub video: Vec<VideoCodecCaps>,
}

/// Baseline order for advertised video codecs, filtered to what [`AndroidTvCaps::video`] reports
/// a decoder for; no software fallback, so an unprobed codec is never advertised.
const ANDROID_VIDEO_CODEC_BASELINE: &[VideoCodec] = &[
    VideoCodec::H264,
    VideoCodec::Hevc,
    VideoCodec::Av1,
    VideoCodec::Vp8,
    VideoCodec::Vp9,
    VideoCodec::Mpeg2Video,
    VideoCodec::Vc1,
];

/// Floor advertised when `AndroidTvCaps::video` comes back empty: H.264/VP8/VP9/MPEG2 are
/// CDD-mandated or AOSP-baseline; HEVC/AV1/VC1 are excluded as too inconsistent to claim unprobed.
const ANDROID_CONSERVATIVE_VIDEO_CODEC_FLOOR: &[VideoCodec] = &[
    VideoCodec::H264,
    VideoCodec::Vp8,
    VideoCodec::Vp9,
    VideoCodec::Mpeg2Video,
];

/// Containers Media3's `DefaultExtractorsFactory` demuxes direct play (docs/05 "Device profile").
const ANDROID_DIRECT_PLAY_CONTAINERS: &[&str] = &[
    "mkv", "mp4", "m4v", "mov", "avi", "ts", "m2ts", "webm", "flv", "ogv", "asf", "wmv", "vob",
    "ogm",
];

/// Audio codecs the ffmpeg Media3 extension decodes in software (docs/05 "Device profile").
const ANDROID_DIRECT_PLAY_AUDIO_CODECS: &[&str] = &[
    "aac",
    "ac3",
    "eac3",
    "dts",
    "dca",
    "truehd",
    "mlp",
    "flac",
    "opus",
    "vorbis",
    "mp3",
    "mp2",
    "alac",
    "pcm_s16le",
    "pcm_s24le",
    "pcm_alaw",
    "pcm_mulaw",
];

/// Subtitle formats Media3 embeds/accepts as sidecar, never inside an HLS manifest (unlike WebVTT).
const ANDROID_TEXT_SUBTITLE_FORMATS: &[&str] = &["srt", "subrip", "ttml"];

/// WebVTT under both format names the server may report; valid inside an HLS manifest too.
const ANDROID_WEBVTT_SUBTITLE_FORMATS: &[&str] = &["vtt", "webvtt"];

/// Bitmap subtitle formats: Media3 composites embedded, else needs server burn-in.
const ANDROID_BITMAP_SUBTITLE_FORMATS: &[&str] = &["pgssub", "dvdsub"];

/// ASS/SSA: Media3's SSA parser renders them embedded (simplified styling, no libass); Encode
/// covers transcodes. No `External`: docs/18 §3.2 never parses an ASS sidecar.
const ANDROID_ASS_SUBTITLE_FORMATS: &[&str] = &["ass", "ssa"];

/// Builds Jellybeam TV's device profile for the given probed capabilities (named "Jellybeam TV" so
/// the server can disambiguate it from this app's other clients). `tolerate_mislabeled_levels` defaults `true`.
pub fn android_tv_profile(
    caps: &AndroidTvCaps,
    tolerate_mislabeled_levels: bool,
) -> RawDeviceProfile {
    RawDeviceProfile {
        name: Some("Jellybeam TV".to_string()),
        // Must be explicit, never omitted (see
        // [`UNCAPPED_STREAMING_BITRATE`]).
        max_streaming_bitrate: Some(clamp_to_i32(
            caps.max_streaming_bitrate
                .unwrap_or(UNCAPPED_STREAMING_BITRATE),
        )),
        max_static_bitrate: None,
        music_streaming_transcoding_bitrate: None,
        max_static_music_bitrate: None,
        direct_play_profiles: vec![android_direct_play_profile(caps)],
        // HLS/ts fallback, reached only when direct play/stream both fail.
        transcoding_profiles: vec![transcoding_profile(caps)],
        container_profiles: Vec::new(),
        codec_profiles: android_codec_profiles(caps, tolerate_mislabeled_levels),
        subtitle_profiles: android_subtitle_profiles(),
    }
}

/// The advertised video codec set: the baseline filtered to what [`AndroidTvCaps::video`] reports
/// a decoder for, falling back to [`ANDROID_CONSERVATIVE_VIDEO_CODEC_FLOOR`] when empty. `pub`
/// (docs/18-playback-quality.md §2): also used by Auto mode's local-codec-corroboration check.
pub fn android_direct_play_video_codecs(caps: &AndroidTvCaps) -> Vec<VideoCodec> {
    if caps.video.is_empty() {
        return ANDROID_CONSERVATIVE_VIDEO_CODEC_FLOOR.to_vec();
    }
    ANDROID_VIDEO_CODEC_BASELINE
        .iter()
        .copied()
        .filter(|codec| caps.video.iter().any(|v| v.codec == *codec))
        .collect()
}

fn android_direct_play_profile(caps: &AndroidTvCaps) -> DirectPlayProfile {
    let video_codec = android_direct_play_video_codecs(caps)
        .into_iter()
        .map(VideoCodec::as_str)
        .collect::<Vec<_>>()
        .join(",");
    DirectPlayProfile {
        container: Some(ANDROID_DIRECT_PLAY_CONTAINERS.join(",")),
        audio_codec: Some(ANDROID_DIRECT_PLAY_AUDIO_CODECS.join(",")),
        video_codec: Some(video_codec),
        kind: Some(DlnaProfileType::Video),
    }
}

/// CodecProfile conditions for one probed codec; only real constraints get one. `VideoLevel` is
/// emitted only when `tolerate_mislabeled_levels` is `false` (a hard Direct Play gate server-side).
fn android_video_codec_profiles(
    video: &VideoCodecCaps,
    tolerate_mislabeled_levels: bool,
) -> Vec<CodecProfile> {
    let codec = Some(video.codec.as_str().to_string());
    let video_condition = |condition, property, value: String| CodecProfile {
        kind: Some(CodecType::Video),
        codec: codec.clone(),
        container: None,
        sub_container: None,
        apply_conditions: Vec::new(),
        conditions: vec![ProfileCondition {
            condition: Some(condition),
            property: Some(property),
            value: Some(value),
            is_required: Some(true),
        }],
    };

    let mut profiles = Vec::new();

    if !video.profiles.is_empty() {
        profiles.push(video_condition(
            ProfileConditionType::EqualsAny,
            ProfileConditionValue::VideoProfile,
            video.profiles.join("|"),
        ));
    }

    if !tolerate_mislabeled_levels {
        if let Some(level) = video.max_level {
            profiles.push(video_condition(
                ProfileConditionType::LessThanEqual,
                ProfileConditionValue::VideoLevel,
                level.to_string(),
            ));
        }

        for ceiling in &video.profile_levels {
            profiles.push(CodecProfile {
                kind: Some(CodecType::Video),
                codec: codec.clone(),
                container: None,
                sub_container: None,
                apply_conditions: vec![ProfileCondition {
                    condition: Some(ProfileConditionType::Equals),
                    property: Some(ProfileConditionValue::VideoProfile),
                    value: Some(ceiling.profile.clone()),
                    is_required: Some(true),
                }],
                conditions: vec![ProfileCondition {
                    condition: Some(ProfileConditionType::LessThanEqual),
                    property: Some(ProfileConditionValue::VideoLevel),
                    value: Some(ceiling.max_level.to_string()),
                    is_required: Some(true),
                }],
            });
        }
    }

    let mut resolution_conditions = Vec::new();
    if let Some(width) = video.max_width {
        resolution_conditions.push(ProfileCondition {
            condition: Some(ProfileConditionType::LessThanEqual),
            property: Some(ProfileConditionValue::Width),
            value: Some(width.to_string()),
            is_required: Some(true),
        });
    }
    if let Some(height) = video.max_height {
        resolution_conditions.push(ProfileCondition {
            condition: Some(ProfileConditionType::LessThanEqual),
            property: Some(ProfileConditionValue::Height),
            value: Some(height.to_string()),
            is_required: Some(true),
        });
    }
    if !resolution_conditions.is_empty() {
        profiles.push(CodecProfile {
            kind: Some(CodecType::Video),
            codec: codec.clone(),
            container: None,
            sub_container: None,
            apply_conditions: Vec::new(),
            conditions: resolution_conditions,
        });
    }

    if !video.unsupported_video_ranges.is_empty() {
        // A bare `NotEquals VideoRangeType` always fails server-side; gated behind ApplyConditions.
        let joined = video.unsupported_video_ranges.join("|");
        profiles.push(CodecProfile {
            kind: Some(CodecType::Video),
            codec: codec.clone(),
            container: None,
            sub_container: None,
            apply_conditions: vec![ProfileCondition {
                condition: Some(ProfileConditionType::EqualsAny),
                property: Some(ProfileConditionValue::VideoRangeType),
                value: Some(joined.clone()),
                is_required: Some(true),
            }],
            conditions: vec![ProfileCondition {
                condition: Some(ProfileConditionType::NotEquals),
                property: Some(ProfileConditionValue::VideoRangeType),
                value: Some(joined),
                is_required: Some(true),
            }],
        });
    }

    profiles
}

/// Per-codec conditions for every probed codec, plus the bitrate ceiling.
fn android_codec_profiles(
    caps: &AndroidTvCaps,
    tolerate_mislabeled_levels: bool,
) -> Vec<CodecProfile> {
    let mut profiles: Vec<CodecProfile> = caps
        .video
        .iter()
        .flat_map(|video| android_video_codec_profiles(video, tolerate_mislabeled_levels))
        .collect();
    if let Some(bitrate) = caps.max_streaming_bitrate {
        profiles.push(CodecProfile {
            kind: Some(CodecType::Video),
            codec: None,
            container: None,
            sub_container: None,
            apply_conditions: Vec::new(),
            conditions: vec![ProfileCondition {
                condition: Some(ProfileConditionType::LessThanEqual),
                property: Some(ProfileConditionValue::VideoBitrate),
                value: Some(bitrate.to_string()),
                is_required: Some(true),
            }],
        });
    }
    profiles
}

/// Android TV's subtitle matrix.
fn android_subtitle_profiles() -> Vec<SubtitleProfile> {
    let mut profiles = Vec::new();
    for format in ANDROID_TEXT_SUBTITLE_FORMATS {
        profiles.push(subtitle_profile(format, SubtitleDeliveryMethod::Embed));
        profiles.push(subtitle_profile(format, SubtitleDeliveryMethod::External));
    }
    for format in ANDROID_WEBVTT_SUBTITLE_FORMATS {
        profiles.push(subtitle_profile(format, SubtitleDeliveryMethod::Embed));
        profiles.push(subtitle_profile(format, SubtitleDeliveryMethod::External));
        profiles.push(subtitle_profile(format, SubtitleDeliveryMethod::Hls));
    }
    for format in ANDROID_BITMAP_SUBTITLE_FORMATS
        .iter()
        .chain(ANDROID_ASS_SUBTITLE_FORMATS)
    {
        profiles.push(subtitle_profile(format, SubtitleDeliveryMethod::Embed));
        profiles.push(subtitle_profile(format, SubtitleDeliveryMethod::Encode));
    }
    profiles
}

#[cfg(test)]
mod tests {

    #[test]
    fn transcode_targets_are_only_codecs_this_device_decodes() {
        let target = |caps: &AndroidTvCaps| {
            android_json(caps, true)["TranscodingProfiles"][0]["VideoCodec"]
                .as_str()
                .expect("test assertion")
                .to_string()
        };
        let floor = AndroidTvCaps {
            max_streaming_bitrate: None,
            video: vec![],
        };
        assert_eq!(
            target(&floor),
            "h264",
            "an undecodable HEVC must be encoded, never copied"
        );
        let hevc = AndroidTvCaps {
            max_streaming_bitrate: None,
            video: vec![hevc_caps(&["main"], Some(153))],
        };
        assert_eq!(target(&hevc), "h264,hevc");
    }

    use super::*;

    // -- Android TV (Jellybeam TV) profile --

    /// Test helper; most call sites pass `true` (tolerant), `false` for the intolerant path.
    fn android_json(caps: &AndroidTvCaps, tolerate_mislabeled_levels: bool) -> serde_json::Value {
        serde_json::to_value(android_tv_profile(caps, tolerate_mislabeled_levels))
            .expect("profile always serializes")
    }

    fn hevc_caps(profiles: &[&str], max_level: Option<i32>) -> VideoCodecCaps {
        VideoCodecCaps {
            codec: VideoCodec::Hevc,
            profiles: profiles.iter().map(|p| p.to_string()).collect(),
            max_level,
            profile_levels: Vec::new(),
            max_width: None,
            max_height: None,
            unsupported_video_ranges: Vec::new(),
        }
    }

    #[test]
    fn android_names_the_profile_jellybeam_tv() {
        let v = android_json(&AndroidTvCaps::default(), true);
        assert_eq!(v["Name"], "Jellybeam TV");
    }

    #[test]
    fn android_full_audio_list_present() {
        let v = android_json(&AndroidTvCaps::default(), true);
        let dpp = &v["DirectPlayProfiles"][0];
        for c in ANDROID_DIRECT_PLAY_AUDIO_CODECS {
            assert!(
                dpp["AudioCodec"]
                    .as_str()
                    .expect("test assertion")
                    .split(',')
                    .any(|x| x == *c),
                "missing audio codec {c}"
            );
        }
        for required in ["dts", "dca", "truehd"] {
            assert!(ANDROID_DIRECT_PLAY_AUDIO_CODECS.contains(&required));
        }
    }

    /// Embed keeps a file whose default track is ASS on Direct Play; Encode serves transcodes.
    #[test]
    fn android_ass_ssa_embed_or_encode_never_external() {
        let v = android_json(&AndroidTvCaps::default(), true);
        let profiles = v["SubtitleProfiles"].as_array().expect("test assertion");
        for f in ANDROID_ASS_SUBTITLE_FORMATS {
            let methods: Vec<&str> = profiles
                .iter()
                .filter(|p| p["Format"] == *f)
                .map(|p| p["Method"].as_str().expect("test assertion"))
                .collect();
            assert_eq!(methods, ["Embed", "Encode"], "{f}");
        }
    }

    #[test]
    fn android_bitmap_subs_never_external() {
        let v = android_json(&AndroidTvCaps::default(), true);
        let profiles = v["SubtitleProfiles"].as_array().expect("test assertion");
        let external: Vec<&str> = profiles
            .iter()
            .filter(|p| p["Method"] == "External")
            .map(|p| p["Format"].as_str().expect("test assertion"))
            .collect();
        assert!(!external.contains(&"pgssub"));
        assert!(!external.contains(&"dvdsub"));
        // But they are still declared, via Embed+Encode.
        for f in ANDROID_BITMAP_SUBTITLE_FORMATS {
            assert!(profiles
                .iter()
                .any(|p| p["Format"] == *f && p["Method"] == "Embed"));
            assert!(profiles
                .iter()
                .any(|p| p["Format"] == *f && p["Method"] == "Encode"));
        }
    }

    #[test]
    fn android_webvtt_gets_hls_delivery_but_srt_does_not() {
        let v = android_json(&AndroidTvCaps::default(), true);
        let profiles = v["SubtitleProfiles"].as_array().expect("test assertion");
        for f in ANDROID_WEBVTT_SUBTITLE_FORMATS {
            assert!(profiles
                .iter()
                .any(|p| p["Format"] == *f && p["Method"] == "Hls"));
        }
        assert!(!profiles
            .iter()
            .any(|p| p["Format"] == "srt" && p["Method"] == "Hls"));
    }

    #[test]
    fn android_honest_video_gating_excludes_absent_codecs() {
        let caps = AndroidTvCaps {
            max_streaming_bitrate: None,
            video: vec![VideoCodecCaps {
                codec: VideoCodec::H264,
                profiles: Vec::new(),
                max_level: None,
                profile_levels: Vec::new(),
                max_width: None,
                max_height: None,
                unsupported_video_ranges: Vec::new(),
            }],
        };
        let v = android_json(&caps, true);
        let advertised = v["DirectPlayProfiles"][0]["VideoCodec"]
            .as_str()
            .expect("test assertion");
        assert!(advertised.split(',').any(|c| c == "h264"));
        assert!(
            !advertised.split(',').any(|c| c == "hevc"),
            "a caps set without hevc must not advertise hevc, got {advertised}"
        );
    }

    #[test]
    fn android_hevc_main10_emits_profile_condition_but_no_level_condition_when_tolerant() {
        // Pins: VideoProfile stays but VideoLevel never appears at the tolerant default.
        let caps = AndroidTvCaps {
            max_streaming_bitrate: None,
            video: vec![hevc_caps(&["main", "main 10"], Some(153))],
        };
        let v = android_json(&caps, true);

        let advertised = v["DirectPlayProfiles"][0]["VideoCodec"]
            .as_str()
            .expect("test assertion");
        assert!(advertised.split(',').any(|c| c == "hevc"));

        let codec_profiles = v["CodecProfiles"].as_array().expect("test assertion");
        let hevc_profiles: Vec<&serde_json::Value> = codec_profiles
            .iter()
            .filter(|p| p["Codec"] == "hevc")
            .collect();

        let profile_condition = hevc_profiles
            .iter()
            .flat_map(|p| p["Conditions"].as_array().expect("test assertion"))
            .find(|c| c["Property"] == "VideoProfile")
            .expect("expected a VideoProfile condition for hevc");
        assert_eq!(profile_condition["Condition"], "EqualsAny");
        assert_eq!(profile_condition["Value"], "main|main 10");
        assert_eq!(profile_condition["IsRequired"], true);

        let has_level_condition = hevc_profiles
            .iter()
            .flat_map(|p| p["Conditions"].as_array().expect("test assertion"))
            .any(|c| c["Property"] == "VideoLevel");
        assert!(
            !has_level_condition,
            "VideoLevel must never be emitted as a CodecProfile condition -- \
             it's a hard, IsRequired-blind Direct Play gate on the real \
             server (see android_video_codec_profiles' doc comment), and a \
             lying encoder's inflated declared level would wrongly force a \
             transcode for a file the hardware decodes fine"
        );
    }

    #[test]
    fn android_profile_specific_levels_never_become_conditions_when_tolerant() {
        // Pins: `profile_levels` is accepted but never surfaces as a VideoLevel condition while
        // tolerant.
        let mut hevc = hevc_caps(&["main", "main 10"], None);
        hevc.profile_levels = vec![
            VideoProfileLevel {
                profile: "main".into(),
                max_level: 120,
            },
            VideoProfileLevel {
                profile: "main 10".into(),
                max_level: 153,
            },
        ];
        let v = android_json(
            &AndroidTvCaps {
                max_streaming_bitrate: None,
                video: vec![hevc],
            },
            true,
        );
        let profiles = v["CodecProfiles"].as_array().expect("codec profiles");

        let any_level_condition = profiles.iter().any(|entry| {
            entry["Codec"] == "hevc"
                && entry["Conditions"]
                    .as_array()
                    .expect("conditions")
                    .iter()
                    .any(|condition| condition["Property"] == "VideoLevel")
        });
        assert!(
            !any_level_condition,
            "profile_levels must never become a VideoLevel condition while tolerant"
        );
    }

    // -- tolerate_mislabeled_levels = false: stricter, server-enforced VideoLevel ceiling. --

    #[test]
    fn android_hevc_main10_emits_profile_and_level_conditions_when_intolerant() {
        let caps = AndroidTvCaps {
            max_streaming_bitrate: None,
            video: vec![hevc_caps(&["main", "main 10"], Some(153))],
        };
        let v = android_json(&caps, false);

        let advertised = v["DirectPlayProfiles"][0]["VideoCodec"]
            .as_str()
            .expect("test assertion");
        assert!(advertised.split(',').any(|c| c == "hevc"));

        let codec_profiles = v["CodecProfiles"].as_array().expect("test assertion");
        let hevc_profiles: Vec<&serde_json::Value> = codec_profiles
            .iter()
            .filter(|p| p["Codec"] == "hevc")
            .collect();

        let profile_condition = hevc_profiles
            .iter()
            .flat_map(|p| p["Conditions"].as_array().expect("test assertion"))
            .find(|c| c["Property"] == "VideoProfile")
            .expect("expected a VideoProfile condition for hevc");
        assert_eq!(profile_condition["Condition"], "EqualsAny");
        assert_eq!(profile_condition["Value"], "main|main 10");
        assert_eq!(profile_condition["IsRequired"], true);

        let level_condition = hevc_profiles
            .iter()
            .flat_map(|p| p["Conditions"].as_array().expect("test assertion"))
            .find(|c| c["Property"] == "VideoLevel")
            .expect("expected a VideoLevel condition for hevc when intolerant");
        assert_eq!(level_condition["Condition"], "LessThanEqual");
        assert_eq!(level_condition["Value"], "153");
    }

    #[test]
    fn android_profile_specific_levels_are_gated_by_video_profile_when_intolerant() {
        let mut hevc = hevc_caps(&["main", "main 10"], None);
        hevc.profile_levels = vec![
            VideoProfileLevel {
                profile: "main".into(),
                max_level: 120,
            },
            VideoProfileLevel {
                profile: "main 10".into(),
                max_level: 153,
            },
        ];
        let v = android_json(
            &AndroidTvCaps {
                max_streaming_bitrate: None,
                video: vec![hevc],
            },
            false,
        );
        let profiles = v["CodecProfiles"].as_array().expect("codec profiles");

        for (profile, level) in [("main", "120"), ("main 10", "153")] {
            let entry = profiles
                .iter()
                .find(|entry| {
                    entry["Codec"] == "hevc"
                        && entry["ApplyConditions"]
                            .as_array()
                            .is_some_and(|conditions| {
                                conditions.iter().any(|condition| {
                                    condition["Property"] == "VideoProfile"
                                        && condition["Value"] == profile
                                })
                            })
                })
                .expect("profile-gated level entry");
            assert!(entry["Conditions"]
                .as_array()
                .expect("conditions")
                .iter()
                .any(|condition| {
                    condition["Property"] == "VideoLevel" && condition["Value"] == level
                }));
        }
    }

    #[test]
    fn android_hevc_main10_level_186_would_transcode_when_intolerant() {
        // Pins: intolerant mode restores the VideoLevel <= 153 condition, rejecting level 186.
        let mut target_like_hevc = hevc_caps(&["main", "main 10"], Some(153));
        target_like_hevc.profile_levels = vec![
            VideoProfileLevel {
                profile: "main".into(),
                max_level: 120,
            },
            VideoProfileLevel {
                profile: "main 10".into(),
                max_level: 153,
            },
        ];
        target_like_hevc.max_width = Some(1920);
        target_like_hevc.max_height = Some(1080);
        let caps = AndroidTvCaps {
            max_streaming_bitrate: None,
            video: vec![target_like_hevc],
        };
        let v = android_json(&caps, false);

        let codec_profiles = v["CodecProfiles"].as_array().expect("codec profiles");
        let hevc_profiles: Vec<&serde_json::Value> = codec_profiles
            .iter()
            .filter(|p| p["Codec"] == "hevc")
            .collect();

        let has_level_153_condition = hevc_profiles.iter().any(|p| {
            p["Conditions"]
                .as_array()
                .expect("conditions")
                .iter()
                .any(|c| c["Property"] == "VideoLevel" && c["Value"] == "153")
        });
        assert!(
            has_level_153_condition,
            "intolerant mode must restore the per-profile VideoLevel ceiling \
             the lying-encoder fix removed, so a level-186-declaring stream \
             is rejected as WouldTranscode again"
        );
    }

    #[test]
    fn android_hevc_main10_level_186_direct_plays_under_target_like_caps() {
        // Pins: a HEVC Main10 file declaring an inflated level (186/6.2) despite being trivially
        // 1080p24 direct-plays under a target-like probe: no VideoLevel condition for hevc.
        let mut target_like_hevc = hevc_caps(&["main", "main 10"], Some(153));
        target_like_hevc.profile_levels = vec![
            VideoProfileLevel {
                profile: "main".into(),
                max_level: 120,
            },
            VideoProfileLevel {
                profile: "main 10".into(),
                max_level: 153,
            },
        ];
        target_like_hevc.max_width = Some(1920);
        target_like_hevc.max_height = Some(1080);
        let caps = AndroidTvCaps {
            max_streaming_bitrate: None,
            video: vec![target_like_hevc],
        };
        let v = android_json(&caps, true);

        let codec_profiles = v["CodecProfiles"].as_array().expect("codec profiles");
        let hevc_profiles: Vec<&serde_json::Value> = codec_profiles
            .iter()
            .filter(|p| p["Codec"] == "hevc")
            .collect();
        assert!(
            !hevc_profiles.is_empty(),
            "expected hevc codec profiles to exist"
        );

        let has_level_condition = hevc_profiles
            .iter()
            .flat_map(|p| p["Conditions"].as_array().expect("conditions"))
            .any(|c| c["Property"] == "VideoLevel");
        assert!(
            !has_level_condition,
            "a level-186 1080p Main10 file must not be gated by VideoLevel -- \
             width/height/profile/bitrate already reflect the device's real \
             capability, so nothing here can turn it into WouldTranscode"
        );

        // Real constraints (VideoProfile, Width, Height) are still present.
        let all_conditions: Vec<&serde_json::Value> = hevc_profiles
            .iter()
            .flat_map(|p| p["Conditions"].as_array().expect("conditions"))
            .collect();
        assert!(all_conditions
            .iter()
            .any(|c| c["Property"] == "VideoProfile"));
        assert!(all_conditions
            .iter()
            .any(|c| c["Property"] == "Width" && c["Value"] == "1920"));
        assert!(all_conditions
            .iter()
            .any(|c| c["Property"] == "Height" && c["Value"] == "1080"));
    }

    #[test]
    fn android_video_range_excludes_are_gated_behind_apply_conditions() {
        // Pins: a bare NotEquals VideoRangeType always fails server-side, gated behind
        // ApplyConditions.
        let mut hevc = hevc_caps(&["main"], None);
        hevc.unsupported_video_ranges = vec!["DOVI".to_string(), "HDR10Plus".to_string()];
        let caps = AndroidTvCaps {
            max_streaming_bitrate: None,
            video: vec![hevc],
        };
        let v = android_json(&caps, true);

        let codec_profiles = v["CodecProfiles"].as_array().expect("test assertion");
        for p in codec_profiles {
            let bare_not_equals = p["Conditions"]
                .as_array()
                .expect("test assertion")
                .iter()
                .any(|c| c["Condition"] == "NotEquals")
                && p["ApplyConditions"]
                    .as_array()
                    .map(|a| a.is_empty())
                    .unwrap_or(true);
            assert!(
                !bare_not_equals,
                "NotEquals without ApplyConditions poisons the codec: {p}"
            );
        }

        let range_profile = codec_profiles
            .iter()
            .find(|p| {
                p["Conditions"]
                    .as_array()
                    .expect("test assertion")
                    .iter()
                    .any(|c| c["Property"] == "VideoRangeType")
            })
            .expect("expected a VideoRangeType codec profile");
        let apply = &range_profile["ApplyConditions"][0];
        assert_eq!(apply["Condition"], "EqualsAny");
        assert_eq!(apply["Property"], "VideoRangeType");
        assert_eq!(apply["Value"], "DOVI|HDR10Plus");
        let cond = &range_profile["Conditions"][0];
        assert_eq!(cond["Condition"], "NotEquals");
        assert_eq!(cond["Value"], "DOVI|HDR10Plus");
    }

    #[test]
    fn android_uncapped_bitrate_always_explicit() {
        let v = android_json(&AndroidTvCaps::default(), true);
        assert_eq!(
            v["MaxStreamingBitrate"]
                .as_i64()
                .expect("must be present, never omitted"),
            i64::from(UNCAPPED_STREAMING_BITRATE as i32),
        );
    }

    #[test]
    fn android_bitrate_cap_only_when_constrained() {
        let unconstrained = android_json(&AndroidTvCaps::default(), true);
        assert!(!unconstrained["CodecProfiles"]
            .as_array()
            .expect("test assertion")
            .iter()
            .any(|p| p["Conditions"]
                .as_array()
                .expect("test assertion")
                .iter()
                .any(|c| c["Property"] == "VideoBitrate")));

        let constrained = android_json(
            &AndroidTvCaps {
                max_streaming_bitrate: Some(6_000_000),
                video: Vec::new(),
            },
            true,
        );
        assert_eq!(constrained["MaxStreamingBitrate"], 6_000_000);
        let bitrate_condition = constrained["CodecProfiles"]
            .as_array()
            .expect("test assertion")
            .iter()
            .flat_map(|p| p["Conditions"].as_array().expect("test assertion"))
            .find(|c| c["Property"] == "VideoBitrate")
            .expect("expected a VideoBitrate condition when constrained");
        assert_eq!(bitrate_condition["Condition"], "LessThanEqual");
        assert_eq!(bitrate_condition["Value"], "6000000");
        assert_eq!(bitrate_condition["IsRequired"], true);
    }

    #[test]
    fn android_empty_caps_falls_back_to_conservative_floor() {
        let v = android_json(&AndroidTvCaps::default(), true);
        let advertised = v["DirectPlayProfiles"][0]["VideoCodec"]
            .as_str()
            .expect("test assertion");
        let advertised: Vec<&str> = advertised.split(',').collect();
        for expected in ["h264", "vp8", "vp9", "mpeg2video"] {
            assert!(
                advertised.contains(&expected),
                "conservative floor missing {expected}"
            );
        }
        for excluded in ["hevc", "av1", "vc1"] {
            assert!(
                !advertised.contains(&excluded),
                "conservative floor must not claim unconfirmed codec {excluded}"
            );
        }
        // No video caps at all means no per-codec CodecProfiles either.
        assert!(v["CodecProfiles"]
            .as_array()
            .expect("test assertion")
            .is_empty());
    }

    #[test]
    fn android_no_interlaced_style_fake_constraints_leak_in() {
        let caps = AndroidTvCaps {
            max_streaming_bitrate: Some(5_000_000),
            video: vec![hevc_caps(&["main", "main 10"], Some(153))],
        };
        let dump = android_json(&caps, true).to_string();
        assert!(!dump.contains("IsInterlaced"));
    }

    // --- VideoCodec::from_server_codec / android_direct_play_video_codecs
    // (docs/18-playback-quality.md §2) ---

    #[test]
    fn from_server_codec_recognizes_the_canonical_names_and_the_servers_aliases() {
        assert_eq!(
            VideoCodec::from_server_codec("h264"),
            Some(VideoCodec::H264)
        );
        assert_eq!(VideoCodec::from_server_codec("avc"), Some(VideoCodec::H264));
        assert_eq!(
            VideoCodec::from_server_codec("hevc"),
            Some(VideoCodec::Hevc)
        );
        assert_eq!(
            VideoCodec::from_server_codec("h265"),
            Some(VideoCodec::Hevc)
        );
        assert_eq!(VideoCodec::from_server_codec("av1"), Some(VideoCodec::Av1));
        assert_eq!(VideoCodec::from_server_codec("vp8"), Some(VideoCodec::Vp8));
        assert_eq!(VideoCodec::from_server_codec("vp9"), Some(VideoCodec::Vp9));
        assert_eq!(
            VideoCodec::from_server_codec("mpeg2video"),
            Some(VideoCodec::Mpeg2Video)
        );
        assert_eq!(VideoCodec::from_server_codec("vc1"), Some(VideoCodec::Vc1));
    }

    /// Pins: MPEG-4 Part 2/ASP has no `VideoCodec` variant, so it's never locally supported.
    #[test]
    fn from_server_codec_has_no_variant_for_mpeg4_or_any_other_unknown_codec() {
        assert_eq!(VideoCodec::from_server_codec("mpeg4"), None);
        assert_eq!(VideoCodec::from_server_codec("made-up-codec"), None);
    }

    #[test]
    fn android_direct_play_video_codecs_is_reachable_as_a_public_fn() {
        let caps = AndroidTvCaps {
            max_streaming_bitrate: None,
            video: vec![hevc_caps(&["main"], None)],
        };
        assert_eq!(
            android_direct_play_video_codecs(&caps),
            vec![VideoCodec::Hevc]
        );
    }
}
