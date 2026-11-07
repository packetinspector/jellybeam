//! docs/feature-dev/music-design-brief.md §1.3 Stream, §2 Formats: a music queue's per-track
//! stream plans and its track-to-track reporting, under the same playback ownership as video
//! (docs/18 §2.1).

use futures_util::{StreamExt, TryStreamExt};
use jellyfin_api::models::{MediaSourceInfo, MediaStreamProtocol, MediaStreamType};
use jellyfin_api::{ApiError, JellyfinClient, ReportPlayMethod};
use jellyfin_core::{PlaybackDecision, ReportContext, ReportMedia, ReportingSession};

use crate::error::CoreError;
use crate::object::{JellybeamCore, Taken};
use crate::settings::PlaybackQuality;
use crate::types::PlaybackRequest;

/// The most tracks one [`JellybeamCore::prepare_audio_queue`] resolves; Kotlin asks for the rest.
const AUDIO_PLAN_PAGE: usize = 100;

/// `PlaybackInfo` calls in flight at once within a page; plans still return in queue order.
const AUDIO_PLAN_CONCURRENCY: usize = 4;

/// One queued song's stream, resolved by its own `PlaybackInfo`.
#[derive(uniffi::Record, Debug, Clone, PartialEq, Eq)]
pub struct AudioTrackPlan {
    pub item_id: String,
    pub media_source_id: String,
    /// The server's id for this play; [`JellybeamCore::start_audio_track`] reports under it.
    pub play_session_id: String,
    pub stream: AudioStream,
}

/// docs/18 §1: how a song plays. Direct Play by default; a transcode only when the Quality
/// setting allows one, and then it names its target so the UI never hides it.
#[derive(uniffi::Enum, Debug, Clone, PartialEq, Eq)]
pub enum AudioStream {
    /// `mime`: the container's MIME type, `None` when the server names none Media3 knows.
    DirectPlay { url: String, mime: Option<String> },
    Transcode {
        url: String,
        mime: Option<String>,
        target: AudioTranscode,
    },
    /// The Direct Play setting refuses the transcode this song needs.
    WouldTranscode { reasons: String },
    /// The server has no source to play; the queue skips it.
    Unplayable { detail: String },
}

/// Handoff §6 TRANSCODE line facts: the source codec, what the server sends instead, its rate.
#[derive(uniffi::Record, Debug, Clone, PartialEq, Eq)]
pub struct AudioTranscode {
    pub source_codec: Option<String>,
    pub target_codec: Option<String>,
    pub bitrate_kbps: Option<u32>,
    pub reason: String,
}

/// The track a [`JellybeamCore::start_audio_track`] ends, and where it ended.
#[derive(uniffi::Record, Debug, Clone, PartialEq, Eq)]
pub struct AudioEnd {
    pub play_session_id: String,
    pub ticks: i64,
}

/// docs/18 §1-2 for one song: the server's Direct Play plays the static file; its transcode
/// is taken unless the Quality setting is Direct Play, which refuses it instead. Songs have no
/// level labels to second-guess, so Auto trusts the profile's verdict. Pure.
fn resolve_audio_stream(
    client: &JellyfinClient,
    item_id: &str,
    quality: PlaybackQuality,
    decision: PlaybackDecision,
) -> (String, AudioStream) {
    let source_id =
        |source: &MediaSourceInfo| source.id.clone().unwrap_or_else(|| item_id.to_string());
    match decision {
        PlaybackDecision::DirectPlay { source, .. } => {
            let media_source_id = source_id(&source);
            let url = client.audio_stream_url(item_id, &media_source_id);
            let mime = audio_mime(source.container.as_deref());
            (media_source_id, AudioStream::DirectPlay { url, mime })
        }
        PlaybackDecision::Transcode {
            source,
            hls_url,
            reasons,
            ..
        } => {
            let reasons = reasons.join("; ");
            let stream = if quality == PlaybackQuality::DirectPlay {
                AudioStream::WouldTranscode { reasons }
            } else {
                let mime = match source.transcoding_sub_protocol {
                    Some(MediaStreamProtocol::Hls) => Some("application/x-mpegURL".to_string()),
                    _ => audio_mime(source.transcoding_container.as_deref()),
                };
                let target = AudioTranscode {
                    source_codec: source
                        .media_streams
                        .iter()
                        .find(|s| s.type_ == Some(MediaStreamType::Audio))
                        .and_then(|s| s.codec.as_deref())
                        .map(str::to_uppercase),
                    target_codec: query_value(&hls_url, "AudioCodec")
                        .and_then(|codecs| codecs.split(',').next().map(str::to_string))
                        .or_else(|| source.transcoding_container.clone())
                        .map(|codec| codec.to_uppercase()),
                    bitrate_kbps: query_value(&hls_url, "AudioBitrate")
                        .and_then(|bps| bps.parse::<u32>().ok())
                        .map(|bps| (bps + 500) / 1000),
                    reason: reasons,
                };
                AudioStream::Transcode {
                    url: hls_url,
                    mime,
                    target,
                }
            };
            (source_id(&source), stream)
        }
    }
}

/// The MIME type of the first container in a server's comma list Media3 names.
fn audio_mime(container: Option<&str>) -> Option<String> {
    container?.split(',').find_map(|c| {
        let mime = match c.trim().to_ascii_lowercase().as_str() {
            "flac" => "audio/flac",
            "mp3" => "audio/mpeg",
            "m4a" | "mp4" | "m4b" | "mov" => "audio/mp4",
            "aac" => "audio/aac",
            "ogg" | "oga" | "opus" => "audio/ogg",
            "webm" => "audio/webm",
            "wav" => "audio/wav",
            "mka" | "matroska" => "audio/x-matroska",
            _ => return None,
        };
        Some(mime.to_string())
    })
}

/// `name`'s percent-decoded value in `url`'s query, matched without case (the server's spelling
/// varies).
fn query_value(url: &str, name: &str) -> Option<String> {
    let (_, query) = url.split_once('?')?;
    url::form_urlencoded::parse(query.as_bytes())
        .find_map(|(key, value)| key.eq_ignore_ascii_case(name).then(|| value.into_owned()))
}

/// Handoff §6: `TRANSCODE │ {SRC} → {DST} │ {KBPS} KBPS`, in the caution colour beside the
/// Direct Play line; a part the server didn't name is omitted, never guessed.
#[uniffi::export]
pub fn music_transcode_line(target: AudioTranscode) -> String {
    let mut parts = vec!["TRANSCODE".to_string()];
    match (target.source_codec, target.target_codec) {
        (Some(source), Some(target)) => parts.push(format!("{source} → {target}")),
        (Some(codec), None) | (None, Some(codec)) => parts.push(codec),
        (None, None) => {}
    }
    parts.extend(target.bitrate_kbps.map(|kbps| format!("{kbps} KBPS")));
    parts.join(" │ ")
}

/// One song's plan; a 4xx or a source-less answer is that song's [`AudioStream::Unplayable`],
/// anything else (network, 401, 5xx) fails the page so Kotlin retries it.
async fn plan_audio_track(
    client: &JellyfinClient,
    profile: &jellyfin_api::models::DeviceProfile,
    quality: PlaybackQuality,
    item_id: String,
) -> Result<AudioTrackPlan, CoreError> {
    let unplayable = |detail: String| AudioTrackPlan {
        item_id: item_id.clone(),
        media_source_id: item_id.clone(),
        play_session_id: String::new(),
        stream: AudioStream::Unplayable { detail },
    };
    let info = match client
        .get_playback_info(&item_id, profile, None, Default::default())
        .await
    {
        Ok(info) => info,
        Err(err @ ApiError::Status { code, .. }) if (400..500).contains(&code) => {
            return Ok(unplayable(err.to_string()));
        }
        Err(err) => return Err(err.into()),
    };
    let decision = match jellyfin_core::decide_playback(client, &item_id, &info) {
        Ok(decision) => decision,
        Err(err) => return Ok(unplayable(err.to_string())),
    };
    let (media_source_id, stream) = resolve_audio_stream(client, &item_id, quality, decision);
    Ok(AudioTrackPlan {
        play_session_id: info.play_session_id.unwrap_or_default(),
        item_id,
        media_source_id,
        stream,
    })
}

#[uniffi::export]
impl JellybeamCore {
    /// docs/feature-dev/music-design-brief.md §1.3: stream plans for the next songs of a queue,
    /// in order, at most [`AUDIO_PLAN_PAGE`] per call, on the account `account_epoch` names
    /// (docs/18 §2.1: the current one or the parked one still playing). The call returns when the
    /// whole page is resolved, so a caller wanting the first sound fast asks for 1-3 ids first.
    pub fn prepare_audio_queue(
        &self,
        item_ids: Vec<String>,
        account_epoch: u64,
    ) -> Result<Vec<AudioTrackPlan>, CoreError> {
        let (client, profile, quality) = self.negotiation_inputs(account_epoch)?;
        self.runtime().block_on(
            futures_util::stream::iter(item_ids.into_iter().take(AUDIO_PLAN_PAGE))
                .map(|item_id| plan_audio_track(&client, &profile, quality, item_id))
                .buffered(AUDIO_PLAN_CONCURRENCY)
                .try_collect(),
        )
    }

    /// docs/18 §2.1: ends the track `ends` names and starts `track`'s
    /// report under one admission of `request`, so an account change never lands between them.
    /// The new Start follows the old Stopped (Jellyfin's Stopped clears whatever plays) and names
    /// `queue_ids` as `NowPlayingQueue`. Position, pause and stop go through `report_position`,
    /// `report_paused` and `stop_playback`. The mirror records the play as the server does
    /// (played, one more play, last played), never a resume position (music brief §1.2).
    pub fn start_audio_track(
        &self,
        track: AudioTrackPlan,
        queue_ids: Vec<String>,
        request: PlaybackRequest,
        ends: Option<AudioEnd>,
    ) -> Result<String, CoreError> {
        let play_method = match track.stream {
            AudioStream::DirectPlay { .. } => ReportPlayMethod::DirectPlay,
            AudioStream::Transcode { .. } => ReportPlayMethod::Transcode,
            AudioStream::WouldTranscode { reasons } => {
                return Err(CoreError::WouldTranscode { reasons })
            }
            AudioStream::Unplayable { detail } => return Err(CoreError::Api { detail }),
        };
        let ctx = ReportContext {
            item_id: track.item_id.clone(),
            media_source_id: track.media_source_id,
            play_session_id: track.play_session_id.clone(),
            play_method,
            media: ReportMedia::Audio {
                queue: queue_ids.into(),
            },
        };
        let (successor_tx, successor_rx) = tokio::sync::oneshot::channel();
        let (account, ended, installed) = {
            let mut state = self.lock_state();
            let account = Self::account_for_epoch_locked(&state, request.account_epoch)?;
            let client = account.client.clone().ok_or(CoreError::NotSignedIn)?;
            let claim = Self::admit_playback_request(&mut state, request, None)?;
            let ended = ends.and_then(|end| {
                let (Taken::Active(session) | Taken::Retired(session, _)) =
                    Self::take_session_or_revoke_locked(&mut state, &end.play_session_id)?;
                Some((
                    session,
                    end.ticks,
                    Self::track_final_stop_locked(&mut state),
                ))
            });
            let installed = Self::install_claimed_session_locked(
                &mut state,
                &claim,
                || {
                    let _runtime_guard = self.runtime().enter();
                    ReportingSession::start_after(client, ctx, successor_rx)
                },
                None,
            )
            .map(|replaced| (replaced, claim));
            (account, ended, installed)
        };
        // Ended even if the install was refused: the caller asked for it to end.
        if let Some((session, ticks, settled_tx)) = ended {
            Self::stop_session_and_sync_mirror(
                self.runtime(),
                &self.playback_write,
                session,
                ticks,
                account.clone(),
                vec![settled_tx, successor_tx],
            );
        }
        let (replaced, claim) = installed?;
        self.end_replaced(replaced, &claim);
        if let Some(mirror) = account.mirror {
            self.runtime()
                .block_on(mirror.record_local_audio_play(&track.item_id));
        }
        Ok(track.play_session_id)
    }
}

#[cfg(test)]
mod tests {
    use std::time::Duration;

    use super::*;
    use crate::object::tests::{
        core_signed_in_against_routes, end_grace_movie_fixture, switch_to_other_account,
        wait_until, RouteMockServer,
    };
    use serde_json::{json, Value};

    const SONG_1: &str = "00000000-0000-4000-8000-000000000101";
    const SONG_2: &str = "00000000-0000-4000-8000-000000000102";
    const DSD: &str = "00000000-0000-4000-8000-000000000103";
    const GONE: &str = "00000000-0000-4000-8000-000000000104";
    const SONG_3: &str = "00000000-0000-4000-8000-000000000105";
    /// One minute in ticks.
    const MINUTE: i64 = 600_000_000;

    fn playback_info(source: Value, play_session_id: &str) -> Value {
        json!({ "MediaSources": [source], "PlaySessionId": play_session_id })
    }

    fn flac_source(id: &str) -> Value {
        json!({ "Id": id, "Container": "flac", "SupportsDirectPlay": true,
                "MediaStreams": [{ "Type": "Audio", "Codec": "flac" }] })
    }

    fn route_song(mock: &RouteMockServer, id: &str, source: Value, session: &str) {
        mock.route(
            "POST",
            &format!("/Items/{id}/PlaybackInfo"),
            200,
            playback_info(source, session),
        );
    }

    /// A Direct Play plan as `prepare_audio_queue` returns one, without its request.
    fn plan(item_id: &str, session: &str) -> AudioTrackPlan {
        AudioTrackPlan {
            item_id: item_id.into(),
            media_source_id: item_id.into(),
            play_session_id: session.into(),
            stream: AudioStream::DirectPlay {
                url: format!("http://example.test/Audio/{item_id}/stream"),
                mime: Some("audio/flac".into()),
            },
        }
    }

    fn request(core: &JellybeamCore, account_epoch: u64) -> PlaybackRequest {
        PlaybackRequest {
            seq: core.next_request().seq,
            account_epoch,
        }
    }

    /// Start bodies (the only reports here carrying `PlayMethod` before a 10 s tick).
    fn starts(mock: &RouteMockServer) -> Vec<Value> {
        mock.request_bodies_containing("POST", "/Sessions/Playing")
            .into_iter()
            .filter(|body| body.get("PlayMethod").is_some())
            .collect()
    }

    fn stopped_items(mock: &RouteMockServer) -> Vec<String> {
        mock.request_bodies_containing("POST", "/Sessions/Playing/Stopped")
            .iter()
            .filter_map(|body| body["ItemId"].as_str().map(str::to_string))
            .collect()
    }

    fn started_items(mock: &RouteMockServer) -> Vec<String> {
        starts(mock)
            .iter()
            .filter_map(|body| body["ItemId"].as_str().map(str::to_string))
            .collect()
    }

    /// Lets a request that must NOT exist show up if it is going to.
    fn settle() {
        std::thread::sleep(Duration::from_millis(300));
    }

    /// Music brief §1.3: a playable song's plan is its static `/Audio` file carrying the token
    /// as `ApiKey`, like video's stream; the Direct Play setting refuses a DSD transcode, Auto
    /// takes it and names its target; a missing song is unplayable, not a failed page.
    #[test]
    fn audio_plans_direct_play_by_default_and_transcode_only_when_quality_allows() {
        let (_dir, core, mock) = core_signed_in_against_routes();
        route_song(&mock, SONG_1, flac_source("ms-1"), "ps-1");
        route_song(
            &mock,
            DSD,
            json!({ "Id": "ms-3", "Container": "dsf", "SupportsDirectPlay": false,
                    "SupportsDirectStream": false, "TranscodingContainer": "mp3",
                    "TranscodingSubProtocol": "http",
                    "TranscodingUrl": format!("/Audio/{DSD}/stream.mp3?AudioCodec=mp3&AudioBitrate=320000&ApiKey=tok"),
                    "MediaStreams": [{ "Type": "Audio", "Codec": "dsd_lsbf_planar" }] }),
            "ps-3",
        );
        mock.route(
            "POST",
            &format!("/Items/{GONE}/PlaybackInfo"),
            404,
            json!({}),
        );
        let epoch = core.account_epoch();
        let ids = vec![SONG_1.to_string(), DSD.to_string(), GONE.to_string()];

        let plans = core.prepare_audio_queue(ids.clone(), epoch).expect("plans");
        assert_eq!(plans.len(), 3);
        assert_eq!(plans[0].play_session_id, "ps-1");
        assert_eq!(plans[0].media_source_id, "ms-1");
        let AudioStream::DirectPlay { url, mime } = &plans[0].stream else {
            panic!("{:?}", plans[0].stream);
        };
        assert!(
            url.ends_with(&format!(
                "/Audio/{SONG_1}/stream?static=true&mediaSourceId=ms-1&ApiKey=tok"
            )),
            "{url}"
        );
        assert!(!url.contains("api_key"), "{url}");
        assert_eq!(mime.as_deref(), Some("audio/flac"));
        assert!(
            matches!(&plans[1].stream, AudioStream::WouldTranscode { reasons } if !reasons.is_empty()),
            "{:?}",
            plans[1].stream
        );
        assert!(
            matches!(plans[2].stream, AudioStream::Unplayable { .. }),
            "{:?}",
            plans[2].stream
        );

        let mut settings = core.get_settings();
        settings.playback_quality = PlaybackQuality::Auto;
        core.set_settings(settings);
        let plans = core.prepare_audio_queue(ids, epoch).expect("plans");
        let AudioStream::Transcode { url, mime, target } = &plans[1].stream else {
            panic!("{:?}", plans[1].stream);
        };
        assert!(url.contains(&format!("/Audio/{DSD}/stream.mp3?")), "{url}");
        assert_eq!(mime.as_deref(), Some("audio/mpeg"));
        assert_eq!(target.source_codec.as_deref(), Some("DSD_LSBF_PLANAR"));
        assert_eq!(target.target_codec.as_deref(), Some("MP3"));
        assert_eq!(target.bitrate_kbps, Some(320));
        assert!(matches!(plans[0].stream, AudioStream::DirectPlay { .. }));
        assert!(matches!(
            core.prepare_audio_queue(vec![SONG_1.into()], u64::MAX),
            Err(CoreError::AccountChanged)
        ));
    }

    /// A transcode URL may carry its codec list percent-encoded (`mp3%2Caac`); the target is the
    /// first codec, decoded.
    #[test]
    fn a_percent_encoded_codec_list_names_its_first_codec() {
        let (_dir, core, mock) = core_signed_in_against_routes();
        route_song(
            &mock,
            DSD,
            json!({ "Id": "ms-3", "Container": "dsf", "SupportsDirectPlay": false,
                    "SupportsDirectStream": false, "TranscodingContainer": "mp3",
                    "TranscodingSubProtocol": "http",
                    "TranscodingUrl": format!("/Audio/{DSD}/stream.mp3?AudioCodec=mp3%2Caac&AudioBitrate=320000&ApiKey=tok"),
                    "MediaStreams": [{ "Type": "Audio", "Codec": "dsd_lsbf_planar" }] }),
            "ps-3",
        );
        let mut settings = core.get_settings();
        settings.playback_quality = PlaybackQuality::Auto;
        core.set_settings(settings);
        let plans = core
            .prepare_audio_queue(vec![DSD.into()], core.account_epoch())
            .expect("plans");
        let AudioStream::Transcode { target, .. } = &plans[0].stream else {
            panic!("{:?}", plans[0].stream);
        };
        assert_eq!(target.target_codec.as_deref(), Some("MP3"));
    }

    /// A page resolves its songs concurrently, so the first sound doesn't wait on every
    /// `PlaybackInfo` in turn; the plans still come back in queue order.
    #[test]
    fn a_page_resolves_songs_concurrently_and_keeps_queue_order() {
        let (_dir, core, mock) = core_signed_in_against_routes();
        for (id, source, session) in [
            (SONG_1, "ms-1", "ps-1"),
            (SONG_2, "ms-2", "ps-2"),
            (SONG_3, "ms-3", "ps-3"),
        ] {
            route_song(&mock, id, flac_source(source), session);
        }
        let first_held = mock.gate("POST", &format!("/Items/{SONG_1}/PlaybackInfo"), 0);
        let epoch = core.account_epoch();
        let worker = {
            let core = core.clone();
            let ids = vec![SONG_1.to_string(), SONG_2.to_string(), SONG_3.to_string()];
            std::thread::spawn(move || core.prepare_audio_queue(ids, epoch))
        };
        wait_until(
            || mock.log_snapshot().iter().any(|l| l.contains(SONG_3)),
            "later songs asked for while the first is still held",
        );
        first_held.open();
        let plans = worker.join().expect("worker").expect("plans");
        let sessions: Vec<_> = plans.iter().map(|p| p.play_session_id.as_str()).collect();
        assert_eq!(sessions, ["ps-1", "ps-2", "ps-3"]);
    }

    /// Handoff §6 TRANSCODE line.
    #[test]
    fn transcode_line_table() {
        let target = |source: Option<&str>, target: Option<&str>, kbps| AudioTranscode {
            source_codec: source.map(str::to_string),
            target_codec: target.map(str::to_string),
            bitrate_kbps: kbps,
            reason: "container 'dsf' requires transcoding".into(),
        };
        let rows = [
            (
                target(Some("DSD"), Some("MP3"), Some(320)),
                "TRANSCODE │ DSD → MP3 │ 320 KBPS",
            ),
            (target(None, Some("MP3"), None), "TRANSCODE │ MP3"),
            (
                target(Some("ALAC"), None, Some(256)),
                "TRANSCODE │ ALAC │ 256 KBPS",
            ),
            (target(None, None, None), "TRANSCODE"),
        ];
        for (input, expected) in rows {
            assert_eq!(music_transcode_line(input.clone()), expected, "{input:?}");
        }
    }

    /// docs/18 §2.1: the next track's Start waits for the previous track's Stopped, names the
    /// queue, and a stale request neither ends nor starts anything.
    #[test]
    fn a_track_change_stops_the_old_track_before_starting_the_next() {
        let (_dir, core, mock) = core_signed_in_against_routes();
        let epoch = core.account_epoch();
        let queue = vec![SONG_1.to_string(), SONG_2.to_string(), SONG_1.to_string()];
        let first = core
            .start_audio_track(
                plan(SONG_1, "ps-a1"),
                queue.clone(),
                request(&core, epoch),
                None,
            )
            .expect("first track");
        assert_eq!(first, "ps-a1");
        wait_until(|| starts(&mock).len() == 1, "the first Start");
        assert_eq!(
            starts(&mock)[0]["NowPlayingQueue"],
            json!([
                { "Id": SONG_1, "PlaylistItemId": "playlistItem0" },
                { "Id": SONG_2, "PlaylistItemId": "playlistItem1" },
                { "Id": SONG_1, "PlaylistItemId": "playlistItem2" },
            ])
        );
        core.report_position(first.clone(), 2 * MINUTE);

        let stale = PlaybackRequest {
            seq: 1,
            account_epoch: epoch,
        };
        let refused = core.start_audio_track(
            plan(SONG_2, "ps-stale"),
            queue.clone(),
            stale,
            Some(AudioEnd {
                play_session_id: first.clone(),
                ticks: 3 * MINUTE,
            }),
        );
        assert!(matches!(refused, Err(CoreError::StalePlaybackSession)));

        let held = mock.gate("POST", "/Sessions/Playing/Stopped", 0);
        let second = core
            .start_audio_track(
                plan(SONG_2, "ps-a2"),
                queue,
                request(&core, epoch),
                Some(AudioEnd {
                    play_session_id: first,
                    ticks: 3 * MINUTE,
                }),
            )
            .expect("second track");
        wait_until(
            || mock.hit_count("POST", "/Sessions/Playing/Stopped") == 1,
            "the first track's Stopped",
        );
        settle();
        assert_eq!(
            started_items(&mock),
            vec![SONG_1],
            "Start waits for Stopped"
        );
        held.open();
        wait_until(|| starts(&mock).len() == 2, "the second Start");
        assert_eq!(started_items(&mock), vec![SONG_1, SONG_2]);
        let stops = mock.request_bodies_containing("POST", "/Sessions/Playing/Stopped");
        assert_eq!(stops.len(), 1, "the stale request ended nothing");
        assert_eq!(stops[0]["PlaySessionId"], "ps-a1");
        assert_eq!(stops[0]["PositionTicks"], 3 * MINUTE);
        assert_eq!(starts(&mock)[1]["PlaySessionId"], second);
    }

    /// docs/18 §2.1: a queue playing across an account switch goes on on its parked account,
    /// track to track, and leaving that account stops the track then playing.
    #[test]
    fn a_parked_queue_changes_track_and_leaving_its_account_stops_it() {
        let (_dir, core, mock) = core_signed_in_against_routes();
        let epoch = core.account_epoch();
        let queue = vec![SONG_1.to_string(), SONG_2.to_string()];
        let first = core
            .start_audio_track(
                plan(SONG_1, "ps-p1"),
                queue.clone(),
                request(&core, epoch),
                None,
            )
            .expect("first track");
        let other = switch_to_other_account(&core);
        assert_eq!(core.parked_account_epoch(), Some(epoch));

        let second = core
            .start_audio_track(
                plan(SONG_2, "ps-p2"),
                queue.clone(),
                request(&core, epoch),
                Some(AudioEnd {
                    play_session_id: first,
                    ticks: MINUTE,
                }),
            )
            .expect("the parked account plays on");
        assert_eq!(core.parked_account_epoch(), Some(epoch));
        wait_until(|| starts(&mock).len() == 2, "the second Start");
        assert_eq!(stopped_items(&mock), vec![SONG_1]);

        assert!(matches!(
            core.start_audio_track(
                plan(SONG_1, "ps-p3"),
                queue,
                request(&core, epoch + 100),
                Some(AudioEnd {
                    play_session_id: second.clone(),
                    ticks: MINUTE,
                }),
            ),
            Err(CoreError::AccountChanged)
        ));
        settle();
        assert_eq!(
            stopped_items(&mock),
            vec![SONG_1],
            "a refused start ends nothing"
        );

        core.remove_session(1 - other)
            .expect("leaves the parked account");
        wait_until(
            || stopped_items(&mock).len() == 2,
            "the playing track's Stopped",
        );
        assert_eq!(stopped_items(&mock), vec![SONG_1, SONG_2]);
        assert_eq!(core.parked_account_epoch(), None);
    }

    /// docs/18 §2.1: music stopped before a video leaves no live session behind: the video
    /// prepares, and a late stop of the song ends neither the song again nor the video.
    #[test]
    fn a_video_after_stopped_music_finds_no_music_session() {
        const VIEW_ID: &str = "00000000-0000-0000-0000-000000000010";
        const MOVIE_ID: &str = "00000000-0000-0000-0000-000000000033";
        let (_dir, core, mock) = end_grace_movie_fixture(VIEW_ID, MOVIE_ID);
        let epoch = core.account_epoch();
        let song = core
            .start_audio_track(
                plan(SONG_1, "ps-v1"),
                vec![SONG_1.into()],
                request(&core, epoch),
                None,
            )
            .expect("music plays");
        core.stop_playback(song.clone(), MINUTE);
        let video = core
            .prepare_playback_now(MOVIE_ID.to_string(), false)
            .expect("the video prepares");
        core.stop_playback(song.clone(), 2 * MINUTE);
        core.report_position(song, 3 * MINUTE);
        wait_until(|| stopped_items(&mock).len() == 1, "the song's Stopped");
        settle();
        assert_eq!(stopped_items(&mock), vec![SONG_1]);
        core.stop_playback(video.play_session_id, MINUTE);
        wait_until(|| stopped_items(&mock).len() == 2, "the video's Stopped");
        assert_eq!(stopped_items(&mock), vec![SONG_1, MOVIE_ID]);
    }

    /// A signed-in core whose mirror holds two songs, one carrying an album gain.
    fn music_mirror_core() -> (
        tempfile::TempDir,
        std::sync::Arc<JellybeamCore>,
        RouteMockServer,
    ) {
        let (dir, core, mock) = core_signed_in_against_routes();
        mock.route(
            "GET",
            "/UserViews",
            200,
            json!({ "Items": [{ "Id": "00000000-0000-4000-8000-000000000001", "Name": "Music",
                                "CollectionType": "music", "Type": "CollectionFolder" }],
                    "TotalRecordCount": 1 }),
        );
        mock.route(
            "GET",
            "/Items",
            200,
            json!({ "Items": [
                { "Id": SONG_1, "Name": "Song One", "Type": "Audio", "RunTimeTicks": 4 * MINUTE,
                  "UserData": { "Key": "k1", "Played": false, "PlayCount": 0 } },
                { "Id": SONG_2, "Name": "Song Two", "Type": "Audio", "RunTimeTicks": 4 * MINUTE,
                  "AlbumNormalizationGain": -7.5,
                  "UserData": { "Key": "k2", "Played": false, "PlayCount": 0 } }
            ], "TotalRecordCount": 2 }),
        );
        core.open_mirror().expect("mirror opens");
        wait_until(
            || core.card_by_id(SONG_2.to_string()).ok().flatten().is_some(),
            "the songs to sync",
        );
        (dir, core, mock)
    }

    /// Music brief §1.2: a song's start records the play as the server does, and neither its
    /// position reports nor its stop write a resume position.
    #[test]
    fn a_song_records_its_play_but_never_a_resume_position() {
        let (_dir, core, mock) = music_mirror_core();
        let epoch = core.account_epoch();
        let song = core
            .start_audio_track(
                plan(SONG_1, "ps-m1"),
                vec![SONG_1.into()],
                request(&core, epoch),
                None,
            )
            .expect("plays");
        let card = || {
            core.card_by_id(SONG_1.to_string())
                .expect("read")
                .expect("row")
        };
        wait_until(|| card().played, "the play to be recorded");
        assert!(card().last_played_date.is_some());
        core.report_position(song.clone(), MINUTE);
        core.stop_playback(song, 2 * MINUTE);
        wait_until(|| stopped_items(&mock).len() == 1, "the Stopped");
        assert_eq!(card().position_ticks, 0);
        assert!(card().played);
    }

    /// Handoff §9: the switch shows once a mirrored song carries a gain; none before the mirror
    /// opens.
    #[test]
    fn normalization_gain_is_read_from_the_mirror() {
        let (_dir, core, _mock) = core_signed_in_against_routes();
        assert!(!core.music_has_normalization_gain());
        let (_dir, core, _mock) = music_mirror_core();
        assert!(core.music_has_normalization_gain());
    }

    /// Handoff §9: a mirrored song's card carries the gains it was served, so the player can apply them.
    #[test]
    fn a_mirrored_song_card_carries_its_served_gains() {
        let (_dir, core, _mock) = music_mirror_core();
        let card = |id: &str| core.card_by_id(id.to_string()).expect("read").expect("row");
        let gained = card(SONG_2);
        assert_eq!(
            (gained.normalization_gain, gained.album_normalization_gain),
            (None, Some(-7.5))
        );
        let plain = card(SONG_1);
        assert_eq!(
            (plain.normalization_gain, plain.album_normalization_gain),
            (None, None)
        );
    }
}
