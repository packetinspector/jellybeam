//! docs/feature-dev/music-design-brief.md §1: music browse exports, answered from the mirror
//! with no request. Lists follow `library_grid`'s contract: `None` when the mirror isn't open
//! or the query failed, so Kotlin keeps its last good list; `Some(vec![])` is truly empty.

use jellyfin_api::models::{BaseItemDto, MediaStreamType};
use jellyfin_api::{LyricLine, MixSeed, PlaylistItemsShape};

use crate::error::CoreError;
use crate::object::{live_children_fields, JellybeamCore};
use crate::types::{
    AlbumSummary, ArtistScope, Card, GridGroup, MusicArtistRow, MusicGenreRow, SongSource,
};

/// Handoff §4 I: the most tracks one Instant Mix asks for.
const INSTANT_MIX_LIMIT: u32 = 200;
/// The most entries a membership scan reads; a longer playlist's tail shows no marker.
const PLAYLIST_SCAN_LIMIT: u32 = 5_000;

/// A failed music read, logged once here and folded into `None`.
fn logged<T>(what: &'static str, result: Result<T, media_cache::CacheError>) -> Option<T> {
    result
        .map_err(|e| tracing::error!(error = %e, query = what, "music query failed"))
        .ok()
}

fn cards(rows: Vec<media_cache::CardRow>) -> Vec<Card> {
    rows.into_iter().map(Card::from).collect()
}

#[uniffi::export]
impl JellybeamCore {
    /// Handoff §4 D: one page of a song list, in its source's order.
    pub fn music_songs(&self, source: SongSource, offset: u32, limit: u32) -> Option<Vec<Card>> {
        let mirror = self.require_mirror().ok()?;
        logged(
            "music_songs",
            mirror.music_songs(&source.into(), offset, limit),
        )
        .map(cards)
    }

    pub fn music_song_count(&self, source: SongSource) -> Option<u64> {
        let mirror = self.require_mirror().ok()?;
        logged("music_song_count", mirror.music_song_count(&source.into()))
    }

    /// The song list's A–Z rail runs, in the source's order.
    pub fn music_song_groups(&self, source: SongSource) -> Option<Vec<GridGroup>> {
        let mirror = self.require_mirror().ok()?;
        logged(
            "music_song_groups",
            mirror.music_song_groups(&source.into()),
        )
        .map(|groups| groups.into_iter().map(GridGroup::from).collect())
    }

    /// Handoff §4 C: disc, then track, unnumbered tracks last.
    pub fn album_tracks(&self, album_id: String, offset: u32, limit: u32) -> Option<Vec<Card>> {
        let mirror = self.require_mirror().ok()?;
        logged(
            "album_tracks",
            mirror.album_tracks(&album_id, offset, limit),
        )
        .map(cards)
    }

    pub fn album_summary(&self, album_id: String) -> Option<AlbumSummary> {
        let mirror = self.require_mirror().ok()?;
        logged("album_summary", mirror.album_summary(&album_id)).map(AlbumSummary::from)
    }

    /// Handoff §4 A: one page of the view's artists, by sort name.
    pub fn music_artists(
        &self,
        view_id: String,
        scope: ArtistScope,
        offset: u32,
        limit: u32,
    ) -> Option<Vec<MusicArtistRow>> {
        let mirror = self.require_mirror().ok()?;
        logged(
            "music_artists",
            mirror.music_artists(&view_id, scope.into(), offset, limit),
        )
        .map(|rows| rows.into_iter().map(MusicArtistRow::from).collect())
    }

    pub fn music_artist_count(&self, view_id: String, scope: ArtistScope) -> Option<u64> {
        let mirror = self.require_mirror().ok()?;
        logged(
            "music_artist_count",
            mirror.music_artist_count(&view_id, scope.into()),
        )
    }

    pub fn music_artist_groups(
        &self,
        view_id: String,
        scope: ArtistScope,
    ) -> Option<Vec<GridGroup>> {
        let mirror = self.require_mirror().ok()?;
        logged(
            "music_artist_groups",
            mirror.music_artist_groups(&view_id, scope.into()),
        )
        .map(|groups| groups.into_iter().map(GridGroup::from).collect())
    }

    /// Handoff §4 B header, counted across libraries. `Ok(None)`: the mirror knows no such
    /// artist; `Err(MirrorNotOpen)` before `open_mirror`, `Err(Cache)` on a failed query.
    pub fn artist_summary(&self, artist_id: String) -> Result<Option<MusicArtistRow>, CoreError> {
        let mirror = self.require_mirror()?;
        Ok(mirror.artist_summary(&artist_id)?.map(MusicArtistRow::from))
    }

    /// The artist page's "Albums" shelf, newest first.
    pub fn artist_albums(&self, artist_id: String) -> Option<Vec<Card>> {
        let mirror = self.require_mirror().ok()?;
        logged("artist_albums", mirror.artist_albums(&artist_id)).map(cards)
    }

    /// The artist page's "Appears on" shelf, never repeating an album under "Albums".
    pub fn artist_appears_on(&self, artist_id: String) -> Option<Vec<Card>> {
        let mirror = self.require_mirror().ok()?;
        logged("artist_appears_on", mirror.artist_appears_on(&artist_id)).map(cards)
    }

    /// Handoff §4 B "Most played": played songs only, most plays first.
    pub fn artist_most_played(&self, artist_id: String, limit: u32) -> Option<Vec<Card>> {
        let mirror = self.require_mirror().ok()?;
        logged(
            "artist_most_played",
            mirror.artist_most_played(&artist_id, limit),
        )
        .map(cards)
    }

    /// Handoff §4 A Genres tab: verbatim names with album and song counts.
    pub fn music_genres(&self, view_id: String) -> Option<Vec<MusicGenreRow>> {
        let mirror = self.require_mirror().ok()?;
        logged("music_genres", mirror.music_genres(&view_id))
            .map(|rows| rows.into_iter().map(MusicGenreRow::from).collect())
    }

    /// Handoff §9: the Normalisation switch shows once any mirrored song or album carries a
    /// served gain; `false` before the mirror opens or when the read fails.
    pub fn music_has_normalization_gain(&self) -> bool {
        self.require_mirror().ok().is_some_and(|mirror| {
            logged(
                "music_has_normalization_gain",
                mirror.music_has_normalization_gain(),
            )
            .unwrap_or(false)
        })
    }
}

/// §1.3 Genres tab, live: a genre's id (its `Primary` art key) and verbatim name; the
/// counts come from the mirror.
#[derive(uniffi::Record, Debug, Clone, PartialEq, Eq)]
pub struct LiveMusicGenre {
    pub id: String,
    pub name: String,
    pub primary_tag: Option<String>,
}

impl LiveMusicGenre {
    fn of(dto: &BaseItemDto) -> Option<Self> {
        Some(Self {
            id: dto.id?.to_string(),
            name: dto.name.clone().unwrap_or_default(),
            primary_tag: dto.image_tags.get("Primary").cloned(),
        })
    }
}

/// Handoff §4 E1: one playlist row; `entry_id` (`PlaylistItemId`) keys move and remove, since
/// one song may sit in a playlist twice.
#[derive(uniffi::Record, Debug, Clone, PartialEq)]
pub struct PlaylistEntry {
    pub entry_id: String,
    pub card: Card,
}

/// Handoff §4 I: an Instant Mix seed -- mirrors `jellyfin_api::MixSeed` 1:1.
#[derive(uniffi::Enum, Debug, Clone, PartialEq, Eq)]
pub enum MixSeedFfi {
    Song { id: String },
    Album { id: String },
    Artist { id: String },
    Playlist { id: String },
    Genre { name: String },
}

impl From<MixSeedFfi> for MixSeed {
    fn from(seed: MixSeedFfi) -> Self {
        match seed {
            MixSeedFfi::Song { id } => Self::Song(id),
            MixSeedFfi::Album { id } => Self::Album(id),
            MixSeedFfi::Artist { id } => Self::Artist(id),
            MixSeedFfi::Playlist { id } => Self::Playlist(id),
            MixSeedFfi::Genre { name } => Self::Genre(name),
        }
    }
}

#[derive(uniffi::Record, Debug, Clone, PartialEq, Eq)]
pub struct SyncedLyricLine {
    pub start_ms: u64,
    pub text: String,
}

/// Handoff §4 F2: a track's lyrics. `None` is a 404 or an empty list (the pill hides).
#[derive(uniffi::Enum, Debug, Clone, PartialEq, Eq)]
pub enum Lyrics {
    Synced { lines: Vec<SyncedLyricLine> },
    Unsynced { lines: Vec<String> },
    None,
}

/// §1.3 Lyrics: synced only when every line has a `Start`; a line without one can't be placed
/// on the clock, so the whole text scrolls by hand instead.
pub(crate) fn lyrics_of(lines: Option<Vec<LyricLine>>) -> Lyrics {
    let lines = lines.unwrap_or_default();
    if lines.is_empty() {
        return Lyrics::None;
    }
    let synced: Option<Vec<SyncedLyricLine>> = lines
        .iter()
        .map(|line| {
            Some(SyncedLyricLine {
                start_ms: ticks_to_ms(line.start_ticks?),
                text: line.text.clone(),
            })
        })
        .collect();
    match synced {
        Some(lines) => Lyrics::Synced { lines },
        None => Lyrics::Unsynced {
            lines: lines.into_iter().map(|line| line.text).collect(),
        },
    }
}

/// 1 tick = 100 ns; a negative start clamps to 0.
fn ticks_to_ms(ticks: i64) -> u64 {
    u64::try_from(ticks / 10_000).unwrap_or(0)
}

/// Handoff §6: the first audio stream's facts. `codec` is the stream `Codec` uppercased, never
/// the raw `Container`; `bit_depth` is `None` unless served (FLAC); `bitrate_kbps` is rounded.
#[derive(uniffi::Record, Debug, Clone, PartialEq, Eq, Default)]
pub struct AudioFacts {
    pub codec: Option<String>,
    pub sample_rate_hz: Option<u32>,
    pub bit_depth: Option<u32>,
    pub bitrate_kbps: Option<u32>,
    pub channels: Option<u32>,
}

pub(crate) fn audio_facts_of(dto: &BaseItemDto) -> AudioFacts {
    let Some(stream) = dto
        .media_streams
        .iter()
        .find(|s| s.type_ == Some(MediaStreamType::Audio))
    else {
        return AudioFacts::default();
    };
    let positive = |n: Option<i32>| n.and_then(|n| u32::try_from(n).ok()).filter(|n| *n > 0);
    AudioFacts {
        codec: stream.codec.as_deref().map(str::to_uppercase),
        sample_rate_hz: positive(stream.sample_rate),
        bit_depth: positive(stream.bit_depth),
        bitrate_kbps: positive(stream.bit_rate).map(|bps| (bps + 500) / 1000),
        channels: positive(stream.channels),
    }
}

/// Handoff §6: whether the spec line leads with how it plays (Now Playing) or not (the album
/// capsule). Transcode isn't here: its line needs the plan's target, a playback-slice input.
#[derive(uniffi::Enum, Debug, Clone, Copy, PartialEq, Eq)]
pub enum SpecLineContext {
    AlbumCapsule,
    DirectPlay,
}

/// Handoff §6: `{CODEC} │ {RATE} KHZ │ {DEPTH}-BIT`, or `… │ {KBPS} KBPS` when no bit depth is
/// served; missing parts are omitted, never guessed.
#[uniffi::export]
pub fn music_spec_line(facts: AudioFacts, context: SpecLineContext) -> String {
    let mut parts: Vec<String> = Vec::new();
    if context == SpecLineContext::DirectPlay {
        parts.push("DIRECT PLAY".to_string());
    }
    parts.extend(facts.codec);
    parts.extend(facts.sample_rate_hz.map(|hz| format!("{} KHZ", khz(hz))));
    match (facts.bit_depth, facts.bitrate_kbps) {
        (Some(depth), _) => parts.push(format!("{depth}-BIT")),
        (None, Some(kbps)) => parts.push(format!("{kbps} KBPS")),
        (None, None) => {}
    }
    parts.join(" │ ")
}

/// `44100` → `44.1`, `48000` → `48`, `22050` → `22.05`.
fn khz(hz: u32) -> String {
    let (whole, frac) = (hz / 1000, hz % 1000);
    if frac == 0 {
        return whole.to_string();
    }
    let frac = format!("{frac:03}");
    format!("{whole}.{}", frac.trim_end_matches('0'))
}

fn live_cards(items: &[BaseItemDto]) -> Vec<Card> {
    items
        .iter()
        .filter_map(|dto| Card::try_from(dto).ok())
        .collect()
}

/// docs/feature-dev/music-design-brief.md §1.3: the live music routes; each needs a signed-in
/// client, never the mirror, and a failure crosses as [`CoreError::Api`].
#[uniffi::export]
impl JellybeamCore {
    /// Handoff §4 A Genres tab, live from `GET /MusicGenres?parentId=`.
    pub fn music_genres_live(
        &self,
        view_id: String,
        offset: u32,
        limit: u32,
    ) -> Result<Vec<LiveMusicGenre>, CoreError> {
        let client = self.require_client()?;
        let page = self
            .runtime()
            .block_on(client.get_music_genres(&view_id, offset, limit))?;
        Ok(page.items.iter().filter_map(LiveMusicGenre::of).collect())
    }

    /// Handoff §4 A Playlists tab: audio playlists, most recently added to first, then by name.
    pub fn playlists(&self, offset: u32, limit: u32) -> Result<Vec<Card>, CoreError> {
        let client = self.require_client()?;
        let page =
            self.runtime()
                .block_on(client.get_playlists(offset, limit, live_children_fields()))?;
        Ok(live_cards(&page.items))
    }

    /// Handoff §4 A: whether any playlist exists, from a count-only request.
    pub fn has_playlists(&self) -> Result<bool, CoreError> {
        let client = self.require_client()?;
        let page = self
            .runtime()
            .block_on(client.get_playlists(0, 0, Vec::new()))?;
        Ok(page.total_record_count.unwrap_or(0) > 0)
    }

    /// Handoff §4 E1: one page of a playlist in its order; an entry the server sends without a
    /// `PlaylistItemId` can't be moved or removed, so it is dropped.
    pub fn playlist_items(
        &self,
        playlist_id: String,
        offset: u32,
        limit: u32,
    ) -> Result<Vec<PlaylistEntry>, CoreError> {
        let client = self.require_client()?;
        let page = self.runtime().block_on(client.get_playlist_items(
            &playlist_id,
            offset,
            limit,
            &live_children_fields(),
            PlaylistItemsShape::Full,
        ))?;
        Ok(page
            .items
            .iter()
            .filter_map(|dto| {
                Some(PlaylistEntry {
                    entry_id: dto.playlist_item_id.clone()?,
                    card: Card::try_from(dto).ok()?,
                })
            })
            .collect())
    }

    /// Handoff §4 E2 `IN PLAYLIST`: the entries of `playlist_id` holding `item_id`, from one
    /// id-only scan; empty when it isn't there.
    pub fn playlist_entry_ids(
        &self,
        playlist_id: String,
        item_id: String,
    ) -> Result<Vec<String>, CoreError> {
        let client = self.require_client()?;
        let page = self.runtime().block_on(client.get_playlist_items(
            &playlist_id,
            0,
            PLAYLIST_SCAN_LIMIT,
            &[],
            PlaylistItemsShape::IdsOnly,
        ))?;
        Ok(page
            .items
            .into_iter()
            .filter(|dto| dto.id.is_some_and(|id| id.to_string() == item_id))
            .filter_map(|dto| dto.playlist_item_id)
            .collect())
    }

    /// Handoff §4 E3: creates an audio playlist holding `item_ids`; returns its id.
    pub fn create_playlist(
        &self,
        name: String,
        item_ids: Vec<String>,
    ) -> Result<String, CoreError> {
        let client = self.require_client()?;
        Ok(self
            .runtime()
            .block_on(client.create_playlist(&name, &item_ids))?)
    }

    /// Handoff §4 E2: appends `item_ids`, duplicates allowed.
    pub fn add_to_playlist(
        &self,
        playlist_id: String,
        item_ids: Vec<String>,
    ) -> Result<(), CoreError> {
        let client = self.require_client()?;
        Ok(self
            .runtime()
            .block_on(client.add_to_playlist(&playlist_id, &item_ids))?)
    }

    /// Handoff §4 E4: moves one entry (by `PlaylistItemId`) to `new_index`.
    pub fn move_playlist_item(
        &self,
        playlist_id: String,
        entry_id: String,
        new_index: u32,
    ) -> Result<(), CoreError> {
        let client = self.require_client()?;
        Ok(self.runtime().block_on(client.move_playlist_item(
            &playlist_id,
            &entry_id,
            new_index,
        ))?)
    }

    /// Handoff §4 E "Remove from playlist", by `PlaylistItemId`.
    pub fn remove_from_playlist(
        &self,
        playlist_id: String,
        entry_ids: Vec<String>,
    ) -> Result<(), CoreError> {
        let client = self.require_client()?;
        Ok(self
            .runtime()
            .block_on(client.remove_from_playlist(&playlist_id, &entry_ids))?)
    }

    /// Handoff §4 I: up to 200 shuffled tracks around `seed`; empty is "no mix yet".
    pub fn instant_mix(&self, seed: MixSeedFfi) -> Result<Vec<Card>, CoreError> {
        let client = self.require_client()?;
        let items = self.runtime().block_on(client.get_instant_mix(
            &seed.into(),
            INSTANT_MIX_LIMIT,
            &live_children_fields(),
        ))?;
        Ok(live_cards(&items))
    }

    /// Handoff §4 F2. `account_epoch` names the player's account (docs/18 §2.1); `None` is the
    /// current one.
    pub fn lyrics(&self, item_id: String, account_epoch: Option<u64>) -> Result<Lyrics, CoreError> {
        let client = self
            .read_account(account_epoch)?
            .client
            .ok_or(CoreError::NotSignedIn)?;
        let lines = self.runtime().block_on(client.get_lyrics(&item_id))?;
        Ok(lyrics_of(lines))
    }

    /// Handoff §6 spec-line inputs from the item's `MediaStreams`; all `None` when it has no
    /// audio stream. `account_epoch` names the player's account (docs/18 §2.1).
    pub fn audio_facts(
        &self,
        item_id: String,
        account_epoch: Option<u64>,
    ) -> Result<AudioFacts, CoreError> {
        let client = self
            .read_account(account_epoch)?
            .client
            .ok_or(CoreError::NotSignedIn)?;
        let dto = self
            .runtime()
            .block_on(client.get_item_by_id(&item_id, &["MediaStreams".to_string()]))?;
        Ok(audio_facts_of(&dto))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::object::tests::core_signed_in_against_routes;
    use serde_json::json;

    /// §1.2: a live music DTO maps to the same music fields a mirrored card carries.
    #[test]
    fn a_live_compilation_track_maps_its_music_fields_verbatim() {
        let dto: jellyfin_api::models::BaseItemDto = serde_json::from_value(serde_json::json!({
            "Id": "00000000-0000-4000-8000-000000000124", "Name": "Comp Duet", "Type": "Audio",
            "Album": "Comp Album", "AlbumId": "00000000-0000-4000-8000-000000000203",
            "AlbumArtist": "Various Artists", "HasLyrics": true, "AlbumPrimaryImageTag": "cover",
            "ArtistItems": [
                { "Name": "Zed Performer", "Id": "00000000-0000-4000-8000-000000000305" },
                { "Name": "Solo Singer", "Id": "00000000-0000-4000-8000-000000000304" }
            ]
        }))
        .expect("dto");
        let card = Card::try_from(&dto).expect("card");
        assert_eq!(card.album.as_deref(), Some("Comp Album"));
        assert_eq!(
            card.album_id.as_deref(),
            Some("00000000-0000-4000-8000-000000000203")
        );
        assert_eq!(card.album_artist.as_deref(), Some("Various Artists"));
        assert_eq!(card.artists, vec!["Zed Performer", "Solo Singer"]);
        assert!(card.has_lyrics);
        assert_eq!(card.album_primary_tag.as_deref(), Some("cover"));
    }

    fn dto(value: serde_json::Value) -> BaseItemDto {
        serde_json::from_value(value).expect("dto")
    }

    fn line(text: &str, start_ticks: Option<i64>) -> LyricLine {
        LyricLine {
            text: text.to_string(),
            start_ticks,
        }
    }

    /// Handoff §4 F2: `Start` ticks become milliseconds; any line without one makes the text
    /// unsynced; a 404 or an empty list is no lyrics.
    #[test]
    fn lyrics_table() {
        let synced = |pairs: &[(u64, &str)]| Lyrics::Synced {
            lines: pairs
                .iter()
                .map(|(start_ms, text)| SyncedLyricLine {
                    start_ms: *start_ms,
                    text: text.to_string(),
                })
                .collect(),
        };
        let rows: Vec<(&str, Option<Vec<LyricLine>>, Lyrics)> = vec![
            ("404", None, Lyrics::None),
            ("empty list", Some(vec![]), Lyrics::None),
            (
                "synced",
                Some(vec![
                    line("one", Some(10_000_000)),
                    line("two", Some(25_005_000)),
                ]),
                synced(&[(1_000, "one"), (2_500, "two")]),
            ),
            (
                "negative start clamps",
                Some(vec![line("zero", Some(-5))]),
                synced(&[(0, "zero")]),
            ),
            (
                "unsynced",
                Some(vec![line("a", None), line("b", None)]),
                Lyrics::Unsynced {
                    lines: vec!["a".into(), "b".into()],
                },
            ),
            (
                "one untimed line unsyncs all",
                Some(vec![line("a", Some(0)), line("b", None)]),
                Lyrics::Unsynced {
                    lines: vec!["a".into(), "b".into()],
                },
            ),
        ];
        for (name, input, expected) in rows {
            assert_eq!(lyrics_of(input), expected, "{name}");
        }
    }

    fn facts(codec: &str, rate: u32, depth: Option<u32>, kbps: Option<u32>) -> AudioFacts {
        AudioFacts {
            codec: Some(codec.to_string()),
            sample_rate_hz: Some(rate),
            bit_depth: depth,
            bitrate_kbps: kbps,
            channels: Some(2),
        }
    }

    /// Handoff §6 spec line.
    #[test]
    fn spec_line_table() {
        use SpecLineContext::{AlbumCapsule, DirectPlay};
        let rows = [
            (
                facts("FLAC", 44_100, Some(16), Some(900)),
                AlbumCapsule,
                "FLAC │ 44.1 KHZ │ 16-BIT",
            ),
            (
                facts("MP3", 44_100, None, Some(320)),
                AlbumCapsule,
                "MP3 │ 44.1 KHZ │ 320 KBPS",
            ),
            (
                facts("AAC", 48_000, None, Some(256)),
                DirectPlay,
                "DIRECT PLAY │ AAC │ 48 KHZ │ 256 KBPS",
            ),
            (
                facts("FLAC", 96_000, Some(24), None),
                DirectPlay,
                "DIRECT PLAY │ FLAC │ 96 KHZ │ 24-BIT",
            ),
            (
                facts("OPUS", 22_050, None, None),
                AlbumCapsule,
                "OPUS │ 22.05 KHZ",
            ),
            (AudioFacts::default(), AlbumCapsule, ""),
            (AudioFacts::default(), DirectPlay, "DIRECT PLAY"),
        ];
        for (input, context, expected) in rows {
            assert_eq!(
                music_spec_line(input.clone(), context),
                expected,
                "{input:?}"
            );
        }
    }

    /// Handoff §6: the codec comes from the audio stream, never the raw container string.
    #[test]
    fn audio_facts_read_the_audio_stream_not_the_container() {
        let item = dto(json!({
            "Id": "00000000-0000-4000-8000-000000000124",
            "Container": "mov,mp4,m4a,3gp,3g2,mj2",
            "MediaStreams": [
                { "Type": "EmbeddedImage", "Codec": "mjpeg" },
                { "Type": "Audio", "Codec": "aac", "SampleRate": 44100, "BitRate": 255_600,
                  "Channels": 2 }
            ]
        }));
        assert_eq!(
            audio_facts_of(&item),
            AudioFacts {
                codec: Some("AAC".into()),
                sample_rate_hz: Some(44_100),
                bit_depth: None,
                bitrate_kbps: Some(256),
                channels: Some(2),
            }
        );
        let no_codec = dto(json!({
            "Id": "00000000-0000-4000-8000-000000000125",
            "Container": "mov,mp4,m4a,3gp,3g2,mj2",
            "MediaStreams": [{ "Type": "Audio" }]
        }));
        assert_eq!(audio_facts_of(&no_codec).codec, None);
    }

    const SONG: &str = "00000000-0000-4000-8000-000000000124";
    const OTHER: &str = "00000000-0000-4000-8000-000000000125";

    #[test]
    fn lyrics_export_maps_synced_unsynced_and_404() {
        let (_dir, core, mock) = core_signed_in_against_routes();
        mock.route(
            "GET",
            &format!("/Audio/{SONG}/Lyrics"),
            200,
            json!({ "Lyrics": [{ "Text": "First synthetic line", "Start": 10_000_000, "Cues": [] }] }),
        );
        mock.route(
            "GET",
            &format!("/Audio/{OTHER}/Lyrics"),
            200,
            json!({ "Lyrics": [{ "Text": "Plain line" }] }),
        );
        mock.route("GET", "/Audio/no-lyrics/Lyrics", 404, json!({}));
        assert_eq!(
            core.lyrics(SONG.into(), None).expect("synced"),
            Lyrics::Synced {
                lines: vec![SyncedLyricLine {
                    start_ms: 1_000,
                    text: "First synthetic line".into()
                }]
            }
        );
        assert_eq!(
            core.lyrics(OTHER.into(), None).expect("unsynced"),
            Lyrics::Unsynced {
                lines: vec!["Plain line".into()]
            }
        );
        assert_eq!(
            core.lyrics("no-lyrics".into(), None).expect("404"),
            Lyrics::None
        );
        assert!(matches!(
            core.lyrics(SONG.into(), Some(u64::MAX)),
            Err(CoreError::AccountChanged)
        ));
    }

    /// Handoff §4 I: an empty mix is an empty list, and a genre seed's name is one encoded
    /// path segment.
    #[test]
    fn an_empty_genre_mix_is_an_empty_list_at_the_encoded_route() {
        let (_dir, core, mock) = core_signed_in_against_routes();
        let cards = core
            .instant_mix(MixSeedFfi::Genre {
                name: "Synthwave;Ambient Dream".into(),
            })
            .expect("mix");
        assert!(cards.is_empty());
        assert_eq!(
            mock.hit_count_containing("GET", "/MusicGenres/Synthwave%3BAmbient%20Dream/InstantMix"),
            1,
            "{:?}",
            mock.log_snapshot()
        );
    }

    #[test]
    fn playlist_entries_carry_their_entry_ids_and_membership_reads_them() {
        let (_dir, core, mock) = core_signed_in_against_routes();
        let page = json!({ "Items": [
            { "Id": SONG, "Name": "Song A", "Type": "Audio", "PlaylistItemId": "entry-1" },
            { "Id": OTHER, "Name": "Song B", "Type": "Audio", "PlaylistItemId": "entry-2" },
            { "Id": SONG, "Name": "Song A", "Type": "Audio", "PlaylistItemId": "entry-3" },
            { "Id": OTHER, "Name": "No entry id", "Type": "Audio" }
        ], "TotalRecordCount": 4 });
        mock.route("GET", "/Playlists/pl-1/Items", 200, page);
        let entries = core.playlist_items("pl-1".into(), 0, 50).expect("items");
        let ids: Vec<(&str, &str)> = entries
            .iter()
            .map(|e| (e.entry_id.as_str(), e.card.id.as_str()))
            .collect();
        assert_eq!(
            ids,
            vec![("entry-1", SONG), ("entry-2", OTHER), ("entry-3", SONG)]
        );
        assert_eq!(
            core.playlist_entry_ids("pl-1".into(), SONG.into())
                .expect("membership"),
            vec!["entry-1", "entry-3"]
        );
        assert!(core
            .playlist_entry_ids("pl-1".into(), "absent".into())
            .expect("membership")
            .is_empty());
    }

    #[test]
    fn has_playlists_reads_the_count_only_total() {
        let (_dir, core, mock) = core_signed_in_against_routes();
        assert!(!core.has_playlists().expect("none"));
        mock.route_with_query(
            "GET",
            "/Items",
            "includeItemTypes=Playlist",
            200,
            &json!({ "Items": [], "TotalRecordCount": 2 }),
        );
        assert!(core.has_playlists().expect("some"));
    }

    #[test]
    fn live_genres_keep_names_verbatim_with_their_art() {
        let (_dir, core, mock) = core_signed_in_against_routes();
        mock.route(
            "GET",
            "/MusicGenres",
            200,
            json!({ "Items": [
                { "Id": SONG, "Name": "Synthwave;Ambient", "ImageTags": { "Primary": "g1" } },
                { "Id": OTHER, "Name": "drone  folk" }
            ], "TotalRecordCount": 2 }),
        );
        let genres = core
            .music_genres_live("view-1".into(), 0, 50)
            .expect("genres");
        assert_eq!(
            genres,
            vec![
                LiveMusicGenre {
                    id: SONG.into(),
                    name: "Synthwave;Ambient".into(),
                    primary_tag: Some("g1".into()),
                },
                LiveMusicGenre {
                    id: OTHER.into(),
                    name: "drone  folk".into(),
                    primary_tag: None,
                },
            ]
        );
    }

    /// Handoff §9: reads `false` before the mirror opens; the mirror read itself is pinned in
    /// media-cache.
    #[test]
    fn normalization_gain_reads_false_without_a_mirror() {
        let (_dir, core, _mock) = core_signed_in_against_routes();
        assert!(!core.music_has_normalization_gain());
    }
}
