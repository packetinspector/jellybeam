//! UniFFI-exported value types: the snapshot/command shapes the app-facing
//! surface trades in (docs/01-architecture.md risk #2 -- coarse records,
//! never row-level DB access) plus small enums mirroring a variant set
//! `jellyfin-api`/`media-cache` already own 1:1.

use chrono::Datelike;

/// Channel-views decision: a view is either a normal mirrored `Library` or a
/// plugin-provided `Channel`, listed in the drawer but never synced --
/// browsed live via [`crate::object::JellybeamCore::live_children`] instead of
/// `Mirror::children`. `ChannelFolder` is a folder inside a channel view,
/// never produced by `From<ViewSummary>`; Kotlin constructs it itself when
/// a `ChannelFolderItem` card opens. Both are browsed live.
#[derive(uniffi::Enum, Debug, Clone, Copy, PartialEq, Eq)]
pub enum ViewKind {
    Library,
    Channel,
    ChannelFolder,
}

/// One server-configured library/view. `name` is shown **verbatim** --
/// never prettified/renamed (CLAUDE.md hard rule).
#[derive(uniffi::Record, Debug, Clone, PartialEq, Eq)]
pub struct ViewSnapshot {
    pub id: String,
    pub name: String,
    pub kind: ViewKind,
    /// docs/16-library-sort-filter.md §1.3: the view's `CollectionType`,
    /// `None` when the server sent an empty string. `#[uniffi(default =
    /// None)]` so existing call sites keep compiling.
    #[uniffi(default = None)]
    pub collection_type: Option<String>,
}

impl From<media_cache::ViewSummary> for ViewSnapshot {
    fn from(summary: media_cache::ViewSummary) -> Self {
        let kind = if summary.item_type == "Channel" {
            ViewKind::Channel
        } else {
            ViewKind::Library
        };
        let collection_type = if summary.collection_type.is_empty() {
            None
        } else {
            Some(summary.collection_type)
        };
        ViewSnapshot {
            id: summary.id,
            name: summary.name,
            kind,
            collection_type,
        }
    }
}

/// One browse/shelf tile. Maps `media_cache::CardRow`'s public fields 1:1
/// -- see that struct's own field docs in `media-cache` for what each means.
#[derive(uniffi::Record, Debug, Clone, PartialEq)]
pub struct Card {
    pub id: String,
    pub item_type: String,
    pub name: String,
    pub primary_tag: Option<String>,
    pub backdrop_tag: Option<String>,
    pub thumb_tag: Option<String>,
    pub blurhash: Option<String>,
    pub played: bool,
    pub position_ticks: i64,
    pub runtime_ticks: Option<i64>,
    pub unplayed_count: Option<i64>,
    pub production_year: Option<i32>,
    pub index_number: Option<i32>,
    pub premiere_date: Option<String>,
    pub parent_index_number: Option<i32>,
    pub series_id: Option<String>,
    pub series_primary_tag: Option<String>,
    pub parent_backdrop_item_id: Option<String>,
    pub parent_backdrop_tag: Option<String>,
    pub series_name: Option<String>,
    pub last_played_date: Option<String>,
    pub overview: Option<String>,
    pub is_virtual: bool,
    pub library_id: Option<String>,
    /// docs/19-detail-action-menu.md §2.3: `UserData.IsFavorite`. Last field
    /// so existing positional call sites keep compiling; `#[uniffi(default =
    /// false)]` gives them an implicit `false`.
    #[uniffi(default = false)]
    pub is_favorite: bool,
}

#[cfg(test)]
impl Card {
    /// A synthetic card for tests: `id` and `item_type` set, everything else empty.
    pub(crate) fn sample(id: &str, item_type: &str) -> Self {
        Self {
            id: id.to_string(),
            item_type: item_type.to_string(),
            name: format!("{id} name"),
            primary_tag: None,
            backdrop_tag: None,
            thumb_tag: None,
            blurhash: None,
            played: false,
            position_ticks: 0,
            runtime_ticks: None,
            unplayed_count: None,
            production_year: None,
            index_number: None,
            premiere_date: None,
            parent_index_number: None,
            series_id: None,
            series_primary_tag: None,
            parent_backdrop_item_id: None,
            parent_backdrop_tag: None,
            series_name: None,
            last_played_date: None,
            overview: None,
            is_virtual: false,
            library_id: None,
            is_favorite: false,
        }
    }
}

/// The two episodic transport targets surrounding the currently-playing
/// episode, kept in one record so the mirror/server walk is atomic --
/// Kotlin cannot observe Previous from one snapshot and Next from another.
#[derive(uniffi::Record, Debug, Clone, PartialEq)]
pub struct EpisodeNeighbors {
    pub previous: Option<Card>,
    pub next: Option<Card>,
}

impl From<media_cache::CardRow> for Card {
    fn from(row: media_cache::CardRow) -> Self {
        Card {
            id: row.id,
            item_type: row.item_type,
            name: row.name,
            primary_tag: row.primary_tag,
            backdrop_tag: row.backdrop_tag,
            thumb_tag: row.thumb_tag,
            blurhash: row.blurhash,
            played: row.played,
            position_ticks: row.position_ticks,
            runtime_ticks: row.runtime_ticks,
            unplayed_count: row.unplayed_count,
            production_year: row.production_year,
            index_number: row.index_number,
            premiere_date: row.premiere_date,
            parent_index_number: row.parent_index_number,
            series_id: row.series_id,
            series_primary_tag: row.series_primary_tag,
            parent_backdrop_item_id: row.parent_backdrop_item_id,
            parent_backdrop_tag: row.parent_backdrop_tag,
            series_name: row.series_name,
            last_played_date: row.last_played_date,
            overview: row.overview,
            is_virtual: row.is_virtual,
            library_id: row.library_id,
            is_favorite: row.is_favorite,
        }
    }
}

/// `GET /Items/{itemId}/Similar` returns raw `BaseItemDto`s off the wire,
/// never through `Mirror`; `library_id` is always `None`. Fallible (unlike
/// [`Card::from`]): a missing wire id means "skip this row".
impl TryFrom<&jellyfin_api::models::BaseItemDto> for Card {
    type Error = ();

    fn try_from(dto: &jellyfin_api::models::BaseItemDto) -> Result<Self, Self::Error> {
        let id = dto.id.ok_or(())?.to_string();

        let primary_tag = dto.image_tags.get("Primary").cloned();
        let thumb_tag = dto.image_tags.get("Thumb").cloned();
        let backdrop_tag = dto.backdrop_image_tags.first().cloned();
        let blurhash = primary_tag.as_ref().and_then(|tag| {
            dto.image_blur_hashes
                .as_ref()
                .and_then(|hashes| hashes.primary.get(tag).cloned())
        });
        let user_data = dto.user_data.as_ref();
        let item_type = dto
            .type_
            .map_or_else(|| "Unknown".to_string(), |t| t.to_string());
        // docs/07 §1: the same grace windows mirror-backed cards get.
        let (position_ticks, played) = media_cache::watch_grace::displayed_watch_state(
            &item_type,
            user_data
                .and_then(|u| u.playback_position_ticks)
                .unwrap_or(0),
            dto.run_time_ticks,
            user_data.and_then(|u| u.played).unwrap_or(false),
        );

        Ok(Card {
            id,
            item_type,
            name: dto.name.clone().unwrap_or_default(),
            primary_tag,
            backdrop_tag,
            thumb_tag,
            blurhash,
            played,
            position_ticks,
            runtime_ticks: dto.run_time_ticks,
            unplayed_count: user_data.and_then(|u| u.unplayed_item_count).map(i64::from),
            production_year: dto.production_year,
            index_number: dto.index_number,
            premiere_date: dto.premiere_date.map(|d| d.to_rfc3339()),
            parent_index_number: dto.parent_index_number,
            series_id: dto.series_id.map(|id| id.to_string()),
            series_primary_tag: dto.series_primary_image_tag.clone(),
            parent_backdrop_item_id: dto.parent_backdrop_item_id.map(|id| id.to_string()),
            parent_backdrop_tag: dto.parent_backdrop_image_tags.first().cloned(),
            series_name: dto.series_name.clone(),
            last_played_date: user_data
                .and_then(|u| u.last_played_date)
                .map(|d| d.to_rfc3339()),
            overview: dto.overview.clone(),
            is_virtual: dto.location_type == Some(jellyfin_api::models::LocationType::Virtual),
            library_id: None,
            is_favorite: user_data.and_then(|u| u.is_favorite).unwrap_or(false),
        })
    }
}

/// docs/19-detail-action-menu.md §2.1/§2.3: one entry in the session's
/// cached BoxSet list, for "Add to collection". `name` is shown verbatim
/// (CLAUDE.md hard rule) -- never re-cased or prettified.
#[derive(uniffi::Record, Debug, Clone, PartialEq, Eq)]
pub struct CollectionInfo {
    pub id: String,
    pub name: String,
}

/// Account identity returned by [`crate::JellybeamCore::sign_in`] and
/// [`crate::JellybeamCore::restore_session`]. Deliberately carries no token --
/// that stays inside the Rust core (see `session.rs`).
#[derive(uniffi::Record, Debug, Clone, PartialEq, Eq)]
pub struct AccountInfo {
    pub server_url: String,
    pub user_id: String,
    pub user_name: String,
}

/// One in-progress Jellyfin Quick Connect handshake. `code` is shown on the
/// TV; `secret` is an opaque capability kept in memory, never persisted.
#[derive(uniffi::Record, Debug, Clone, PartialEq, Eq)]
pub struct QuickConnectSession {
    pub code: String,
    pub secret: String,
}

/// docs/13 About > server info: [`crate::JellybeamCore::server_info_snapshot`]'s offline
/// snapshot -- no network call, so every field reads whatever is already in memory/on disk.
/// All `None`/`false`/empty when nobody is signed in.
#[derive(uniffi::Record, Debug, Clone, PartialEq, Eq)]
pub struct ServerInfoSnapshot {
    pub server_url: Option<String>,
    /// The server's own `ServerName`, shown verbatim (CLAUDE.md) -- never renamed/prettified.
    pub server_name: Option<String>,
    pub server_version: Option<String>,
    pub user_name: Option<String>,
    pub device_id: Option<String>,
    pub live_events_connected: bool,
    pub mirror: Option<MirrorStats>,
    pub libraries: Vec<MirrorLibrary>,
}

/// [`ServerInfoSnapshot::mirror`]: the local mirror's own stats, distinct from anything the
/// server reports.
#[derive(uniffi::Record, Debug, Clone, PartialEq, Eq)]
pub struct MirrorStats {
    pub item_count: i64,
    pub db_bytes: u64,
    /// Debugging-only Unix-millis timestamp of the last full/initial sync; `None` if one
    /// hasn't completed yet (see `media_cache::sync`'s `last_full_sync` meta key).
    pub last_full_sync_ms: Option<i64>,
    /// Raw RFC 3339 cursor of the last delta sync; `None` before the first one.
    pub last_delta_sync: Option<String>,
    pub counts: MirrorItemCounts,
}

/// [`ServerInfoSnapshot::libraries`]: one mirrored library. `name` is shown verbatim
/// (CLAUDE.md) -- never renamed/prettified.
#[derive(uniffi::Record, Debug, Clone, PartialEq, Eq)]
pub struct MirrorLibrary {
    pub name: String,
    pub collection_type: String,
}

/// docs/13 About > server info: [`crate::JellybeamCore::fetch_server_details`]'s on-demand
/// fetch result.
#[derive(uniffi::Record, Debug, Clone, PartialEq, Eq)]
pub struct ServerDetails {
    /// The server's own `ServerName`, shown verbatim (CLAUDE.md) -- never renamed/prettified.
    pub server_name: Option<String>,
    pub version: Option<String>,
    pub product_name: Option<String>,
    pub operating_system: Option<String>,
    pub architecture: Option<String>,
    pub has_pending_restart: Option<bool>,
    pub has_update_available: Option<bool>,
    /// `false` when the authenticated `GET /System/Info` was refused (401/403) and this
    /// fell back to the pre-auth `/System/Info/Public` -- `product_name`/`operating_system`/
    /// `architecture`/`has_pending_restart`/`has_update_available` are all `None` in that case.
    pub system_info_available: bool,
}

/// [`MirrorStats::counts`]: mirrored, non-virtual items per type, `0` for a type the mirror
/// holds none of. Counted locally because a multi-server proxy answers `/Items/Counts` for one
/// backend, not the merged library this client shows.
#[derive(uniffi::Record, Debug, Clone, Default, PartialEq, Eq)]
pub struct MirrorItemCounts {
    pub movies: i64,
    pub series: i64,
    pub episodes: i64,
    pub box_sets: i64,
    pub albums: i64,
    pub songs: i64,
    pub artists: i64,
    pub music_videos: i64,
    pub books: i64,
    pub trailers: i64,
    pub programs: i64,
}

impl MirrorItemCounts {
    /// From [`media_cache::Mirror::item_type_counts`]; unlisted types (seasons, folders) drop.
    pub(crate) fn from_type_counts(type_counts: &[(String, i64)]) -> Self {
        let mut counts = Self::default();
        for (item_type, count) in type_counts {
            let slot = match item_type.as_str() {
                "Movie" => &mut counts.movies,
                "Series" => &mut counts.series,
                "Episode" => &mut counts.episodes,
                "BoxSet" => &mut counts.box_sets,
                "MusicAlbum" => &mut counts.albums,
                "Audio" => &mut counts.songs,
                "MusicArtist" => &mut counts.artists,
                "MusicVideo" => &mut counts.music_videos,
                "Book" | "AudioBook" => &mut counts.books,
                "Trailer" => &mut counts.trailers,
                "Program" | "LiveTvProgram" => &mut counts.programs,
                _ => continue,
            };
            *slot += count;
        }
        counts
    }
}

/// One cast/crew credit (docs/11-detail-ux-spec.md tier 1 items 8-9).
/// `person_type` is the generated `PersonKind` enum's `Display` text
/// rather than a dedicated FFI enum -- same bare-string convention as
/// [`crate::PlaybackPlan::item_type`], since nothing here exhaustively
/// matches the 25+-variant upstream enum.
#[derive(uniffi::Record, Debug, Clone, PartialEq, Eq)]
pub struct PersonInfo {
    pub id: Option<String>,
    pub name: Option<String>,
    pub role: Option<String>,
    pub person_type: Option<String>,
    pub primary_image_tag: Option<String>,
}

impl From<&jellyfin_api::models::BaseItemPerson> for PersonInfo {
    fn from(person: &jellyfin_api::models::BaseItemPerson) -> Self {
        PersonInfo {
            id: person.id.map(|id| id.to_string()),
            name: person.name.clone(),
            role: person.role.clone(),
            person_type: person.type_.map(|t| t.to_string()),
            primary_image_tag: person.primary_image_tag.clone(),
        }
    }
}

/// The three `MediaStreams` kinds the spec strip / OSD track pickers branch
/// on (docs/11 tier 1 item 1, docs/12); every other variant folds into `Other`.
#[derive(uniffi::Enum, Debug, Clone, Copy, PartialEq, Eq)]
pub enum MediaStreamKind {
    Video,
    Audio,
    Subtitle,
    Other,
}

impl From<jellyfin_api::models::MediaStreamType> for MediaStreamKind {
    fn from(kind: jellyfin_api::models::MediaStreamType) -> Self {
        use jellyfin_api::models::MediaStreamType;
        match kind {
            MediaStreamType::Video => MediaStreamKind::Video,
            MediaStreamType::Audio => MediaStreamKind::Audio,
            MediaStreamType::Subtitle => MediaStreamKind::Subtitle,
            MediaStreamType::EmbeddedImage
            | MediaStreamType::Data
            | MediaStreamType::Lyric
            | MediaStreamType::Unrecognized => MediaStreamKind::Other,
        }
    }
}

/// An audio stream's object-based format -- the spec strip's ATMOS / DTS:X suffix (docs/11
/// tier 1 item 1).
#[derive(uniffi::Enum, Debug, Clone, Copy, PartialEq, Eq)]
pub enum AudioSpatialKind {
    DolbyAtmos,
    DtsX,
}

impl AudioSpatialKind {
    /// The server's `AudioSpatialFormat` when it names one; else recovered from `Profile`
    /// (`TrueHD Atmos 7.1`, `DTS-HD MA + DTS:X`), the only place older servers carry it.
    fn of(stream: &jellyfin_api::models::MediaStream) -> Option<Self> {
        use jellyfin_api::models::AudioSpatialFormat;
        match stream.audio_spatial_format {
            Some(AudioSpatialFormat::DolbyAtmos) => return Some(Self::DolbyAtmos),
            Some(AudioSpatialFormat::Dtsx) => return Some(Self::DtsX),
            _ => {}
        }
        let profile = stream.profile.as_deref()?.to_uppercase();
        if profile.contains("ATMOS") {
            Some(Self::DolbyAtmos)
        } else if profile.contains("DTS:X") || profile.contains("DTS-X") {
            Some(Self::DtsX)
        } else {
            None
        }
    }
}

/// One `MediaStreams` entry -- the spec strip (docs/11 tier 1 item 1) and
/// OSD track pickers (docs/12). `video_range`/`video_range_type` are the
/// generated enums' `Display` text, both `None` when unclassified.
// `Eq` dropped: `f32` doesn't implement `Eq`, only `PartialEq`.
#[derive(uniffi::Record, Debug, Clone, PartialEq)]
pub struct MediaStreamInfo {
    pub index: Option<i32>,
    pub stream_type: MediaStreamKind,
    pub codec: Option<String>,
    pub language: Option<String>,
    pub display_title: Option<String>,
    pub width: Option<i32>,
    pub height: Option<i32>,
    pub channels: Option<i32>,
    pub bit_rate: Option<i32>,
    pub bit_depth: Option<i32>,
    pub is_default: bool,
    pub video_range: Option<String>,
    pub video_range_type: Option<String>,
    /// Codec profile (e.g. "High", "Main 10") -- the stats sheet's VIDEO
    /// row (docs/jellybeam-osd-handoff §8b), straight through.
    pub profile: Option<String>,
    /// Audio sample rate in Hz -- the stats sheet's AUDIO row (§8b),
    /// straight through.
    pub sample_rate: Option<i32>,
    /// Video frame rate for the stats sheet's VIDEO row (§8b). Prefers
    /// `average_frame_rate`, falling back to `real_frame_rate` since the
    /// server doesn't consistently send both.
    pub avg_frame_rate: Option<f32>,
    /// See [`AudioSpatialKind::of`]; `None` on non-audio streams.
    #[uniffi(default = None)]
    pub audio_spatial: Option<AudioSpatialKind>,
}

impl From<&jellyfin_api::models::MediaStream> for MediaStreamInfo {
    fn from(stream: &jellyfin_api::models::MediaStream) -> Self {
        MediaStreamInfo {
            index: stream.index,
            stream_type: stream
                .type_
                .map(MediaStreamKind::from)
                .unwrap_or(MediaStreamKind::Other),
            codec: stream.codec.clone(),
            language: stream.language.clone(),
            display_title: stream.display_title.clone(),
            width: stream.width,
            height: stream.height,
            channels: stream.channels,
            bit_rate: stream.bit_rate,
            bit_depth: stream.bit_depth,
            is_default: stream.is_default.unwrap_or(false),
            video_range: stream.video_range.map(|r| r.to_string()),
            video_range_type: stream.video_range_type.map(|r| r.to_string()),
            profile: stream.profile.clone(),
            sample_rate: stream.sample_rate,
            avg_frame_rate: stream.average_frame_rate.or(stream.real_frame_rate),
            audio_spatial: AudioSpatialKind::of(stream),
        }
    }
}

/// One `Chapters` entry. Named with the `Ffi` suffix to avoid colliding
/// with the generated `jellyfin_api::models::ChapterInfo` this mirrors.
#[derive(uniffi::Record, Debug, Clone, PartialEq, Eq)]
pub struct ChapterInfoFfi {
    pub name: Option<String>,
    pub start_position_ticks: i64,
    pub image_tag: Option<String>,
}

impl From<&jellyfin_api::models::ChapterInfo> for ChapterInfoFfi {
    fn from(chapter: &jellyfin_api::models::ChapterInfo) -> Self {
        ChapterInfoFfi {
            name: chapter.name.clone(),
            start_position_ticks: chapter.start_position_ticks.unwrap_or(0),
            image_tag: chapter.image_tag.clone(),
        }
    }
}

/// The narrow playback-start enrichment surface. Unlike [`ItemDetail`],
/// cannot accidentally retain plot/people/ratings/history fetched for a
/// library-info panel -- startup asks only for OSD chapter/stream data.
#[derive(uniffi::Record, Debug, Clone, PartialEq)]
pub struct PlaybackOsdDetail {
    pub container: Option<String>,
    pub media_streams: Vec<MediaStreamInfo>,
    pub chapters: Vec<ChapterInfoFfi>,
    /// The primary `MediaSource`'s file size in bytes -- the stats sheet's
    /// CONTAINER row (§8b). Requires the `MediaSources` field.
    pub size_bytes: Option<i64>,
    /// The primary `MediaSource`'s file path, verbatim -- the stats
    /// sheet's FILE row (§8b), the one place a release tag surfaces.
    /// `None` if the server sent no media sources.
    pub path: Option<String>,
}

impl From<&jellyfin_api::models::BaseItemDto> for PlaybackOsdDetail {
    fn from(dto: &jellyfin_api::models::BaseItemDto) -> Self {
        let primary_source = dto.media_sources.first();
        PlaybackOsdDetail {
            container: dto.container.clone(),
            media_streams: dto
                .media_streams
                .iter()
                .map(MediaStreamInfo::from)
                .collect(),
            chapters: dto.chapters.iter().map(ChapterInfoFfi::from).collect(),
            size_bytes: primary_source.and_then(|source| source.size),
            path: primary_source.and_then(|source| source.path.clone()),
        }
    }
}

/// One item's full detail record for the Detail/OSD tier-2 UI (docs/11,
/// docs/12) -- fetched live once per visit, never persisted to the mirror.
#[derive(uniffi::Record, Debug, Clone, PartialEq)]
pub struct ItemDetail {
    pub id: String,
    /// Display metadata for the playback library-info panel, kept off
    /// [`PlaybackPlan`] so opening this panel never adds work to the
    /// latency-critical stream preparation path.
    pub name: String,
    pub item_type: String,
    pub series_name: Option<String>,
    pub parent_index_number: Option<i32>,
    pub index_number: Option<i32>,
    pub premiere_date: Option<String>,
    pub play_count: i32,
    pub last_played_date: Option<String>,
    pub genres: Vec<String>,
    pub official_rating: Option<String>,
    pub community_rating: Option<f32>,
    pub critic_rating: Option<f32>,
    pub production_year: Option<i32>,
    /// The year `EndDate` falls in, for a finished Series -- `None` for a
    /// still-`Continuing` series or non-episodic item. docs/11 tier 1 item
    /// 10's year-range rule picks the display form from this and `status`.
    pub end_year: Option<i32>,
    /// `Status` ("Continuing"/"Ended") -- carried raw, not collapsed into
    /// [`Self::end_year`], so the UI can distinguish "still airing" from
    /// "ended, but no `EndDate`" for the year-range rule above.
    pub status: Option<String>,
    /// Studio names only -- nothing here needs a studio's id.
    pub studios: Vec<String>,
    pub overview: Option<String>,
    pub run_time_ticks: Option<i64>,
    pub container: Option<String>,
    /// Top ~12 cast/crew credits, server order (docs/11 tier 1 item 9);
    /// skip-no-portrait/absent-if-none is UI-side, this just carries what
    /// the server sent, capped.
    pub people: Vec<PersonInfo>,
    pub media_streams: Vec<MediaStreamInfo>,
    pub chapters: Vec<ChapterInfoFfi>,
    /// RFC3339 timestamp the server added this item to the library, same
    /// convention as [`Card::last_played_date`]. Requires `DateCreated`;
    /// `None` if omitted.
    pub date_created: Option<String>,
    /// The primary `MediaSource`'s file size in bytes, `None` if the
    /// server sent no media sources (requires the `MediaSources` field).
    pub size_bytes: Option<i64>,
    /// Total descendant item count (e.g. a Series' episode count across
    /// seasons), requires the `RecursiveItemCount` field.
    pub recursive_item_count: Option<i32>,
    /// Direct child count (e.g. a Series' season count), requires the
    /// `ChildCount` field.
    pub child_count: Option<i32>,
    /// Director name(s), extracted from the *full* people list before
    /// [`MAX_PEOPLE`] truncates it -- a large cast can push directors past
    /// the top-12 cap.
    pub directors: Vec<String>,
    /// Writer name(s) -- see [`Self::directors`], same before-the-cap reasoning.
    pub writers: Vec<String>,
}

/// Cap on [`ItemDetail::people`].
const MAX_PEOPLE: usize = 12;

/// Names of every person in `people` whose `type_` is `kind`, server order
/// -- pulls [`ItemDetail::directors`]/[`ItemDetail::writers`] before
/// [`MAX_PEOPLE`] caps the list.
fn people_named_by_kind(
    people: &[jellyfin_api::models::BaseItemPerson],
    kind: jellyfin_api::models::PersonKind,
) -> Vec<String> {
    people
        .iter()
        .filter(|p| p.type_ == Some(kind))
        .filter_map(|p| p.name.clone())
        .collect()
}

impl From<&jellyfin_api::models::BaseItemDto> for ItemDetail {
    fn from(dto: &jellyfin_api::models::BaseItemDto) -> Self {
        use jellyfin_api::models::PersonKind;
        ItemDetail {
            id: dto.id.map(|id| id.to_string()).unwrap_or_default(),
            name: dto.name.clone().unwrap_or_default(),
            item_type: dto
                .type_
                .map(|kind| kind.to_string())
                .unwrap_or_else(|| "Unknown".to_string()),
            series_name: dto.series_name.clone(),
            parent_index_number: dto.parent_index_number,
            index_number: dto.index_number,
            premiere_date: dto.premiere_date.map(|date| date.to_rfc3339()),
            play_count: dto
                .user_data
                .as_ref()
                .and_then(|data| data.play_count)
                .unwrap_or(0),
            last_played_date: dto
                .user_data
                .as_ref()
                .and_then(|data| data.last_played_date)
                .map(|date| date.to_rfc3339()),
            genres: dto.genres.clone(),
            official_rating: dto.official_rating.clone(),
            community_rating: dto.community_rating,
            critic_rating: dto.critic_rating,
            production_year: dto.production_year,
            end_year: dto.end_date.map(|d| d.year()),
            status: dto.status.clone(),
            studios: dto.studios.iter().filter_map(|s| s.name.clone()).collect(),
            overview: dto.overview.clone(),
            run_time_ticks: dto.run_time_ticks,
            container: dto.container.clone(),
            people: dto
                .people
                .iter()
                .take(MAX_PEOPLE)
                .map(PersonInfo::from)
                .collect(),
            media_streams: dto
                .media_streams
                .iter()
                .map(MediaStreamInfo::from)
                .collect(),
            chapters: dto.chapters.iter().map(ChapterInfoFfi::from).collect(),
            date_created: dto.date_created.map(|d| d.to_rfc3339()),
            size_bytes: dto.media_sources.first().and_then(|source| source.size),
            recursive_item_count: dto.recursive_item_count,
            child_count: dto.child_count,
            directors: people_named_by_kind(&dto.people, PersonKind::Director),
            writers: people_named_by_kind(&dto.people, PersonKind::Writer),
        }
    }
}

/// Mirrors `jellyfin_api::models::MediaSegmentType` 1:1 except folding
/// `#[serde(other)]`'s `Unrecognized` into `Unknown`.
#[derive(uniffi::Enum, Debug, Clone, Copy, PartialEq, Eq)]
pub enum MediaSegmentKind {
    Unknown,
    Intro,
    Outro,
    Recap,
    Preview,
    Commercial,
}

impl From<jellyfin_api::models::MediaSegmentType> for MediaSegmentKind {
    fn from(kind: jellyfin_api::models::MediaSegmentType) -> Self {
        use jellyfin_api::models::MediaSegmentType;
        match kind {
            MediaSegmentType::Unknown | MediaSegmentType::Unrecognized => MediaSegmentKind::Unknown,
            MediaSegmentType::Intro => MediaSegmentKind::Intro,
            MediaSegmentType::Outro => MediaSegmentKind::Outro,
            MediaSegmentType::Recap => MediaSegmentKind::Recap,
            MediaSegmentType::Preview => MediaSegmentKind::Preview,
            MediaSegmentType::Commercial => MediaSegmentKind::Commercial,
        }
    }
}

/// One skip-intro/credits marker (docs/12-osd-ux-spec.md). `start_ticks`/
/// `end_ticks` default to `0` when the server omits either -- a missing
/// bound is more useful as "0" than forcing `Option` everywhere.
#[derive(uniffi::Record, Debug, Clone, Copy, PartialEq, Eq)]
pub struct MediaSegment {
    pub segment_type: MediaSegmentKind,
    pub start_ticks: i64,
    pub end_ticks: i64,
}

impl From<jellyfin_api::models::MediaSegmentDto> for MediaSegment {
    fn from(dto: jellyfin_api::models::MediaSegmentDto) -> Self {
        MediaSegment {
            segment_type: dto
                .type_
                .map(MediaSegmentKind::from)
                .unwrap_or(MediaSegmentKind::Unknown),
            start_ticks: dto.start_ticks.unwrap_or(0),
            end_ticks: dto.end_ticks.unwrap_or(0),
        }
    }
}

/// Mirrors `media_cache::Sort` 1:1.
#[derive(uniffi::Enum, Debug, Clone, Copy, PartialEq, Eq)]
pub enum SortOrder {
    NameAsc,
    DateCreatedDesc,
    PremiereDateDesc,
    IndexNumber,
}

impl From<SortOrder> for media_cache::Sort {
    fn from(sort: SortOrder) -> Self {
        match sort {
            SortOrder::NameAsc => media_cache::Sort::NameAsc,
            SortOrder::DateCreatedDesc => media_cache::Sort::DateCreatedDesc,
            SortOrder::PremiereDateDesc => media_cache::Sort::PremiereDateDesc,
            SortOrder::IndexNumber => media_cache::Sort::IndexNumber,
        }
    }
}

// ---- Library sort/filter (docs/16-library-sort-filter.md §1.3/§2.1/§3) --
//
// One-to-one uniffi mirrors of `media_cache`'s own (non-FFI) grid types,
// same convention as `settings.rs`'s `SubtitleModeSetting`/`LanguageSettings`.
// `serde`/`Default` derives are needed here because [`LibraryGridPrefs`] is
// persisted to `library_grid_prefs.json`.

/// The library grid's sortable fields -- mirrors `media_cache::GridSortField`
/// 1:1. `#[default]` on `Name` matches docs/16's default sort (Name A->Z).
#[derive(
    uniffi::Enum, Debug, Clone, Copy, Default, PartialEq, Eq, serde::Serialize, serde::Deserialize,
)]
pub enum GridSortField {
    #[default]
    Name,
    DateAdded,
    Year,
    Runtime,
}

impl From<GridSortField> for media_cache::GridSortField {
    fn from(field: GridSortField) -> Self {
        match field {
            GridSortField::Name => media_cache::GridSortField::Name,
            GridSortField::DateAdded => media_cache::GridSortField::DateAdded,
            GridSortField::Year => media_cache::GridSortField::Year,
            GridSortField::Runtime => media_cache::GridSortField::Runtime,
        }
    }
}

impl From<media_cache::GridSortField> for GridSortField {
    fn from(field: media_cache::GridSortField) -> Self {
        match field {
            media_cache::GridSortField::Name => GridSortField::Name,
            media_cache::GridSortField::DateAdded => GridSortField::DateAdded,
            media_cache::GridSortField::Year => GridSortField::Year,
            media_cache::GridSortField::Runtime => GridSortField::Runtime,
        }
    }
}

/// One field plus direction -- mirrors `media_cache::GridSort` 1:1.
/// Default (`Name`, `descending: false`) is docs/16's stated default.
#[derive(
    uniffi::Record, Debug, Clone, Copy, Default, PartialEq, Eq, serde::Serialize, serde::Deserialize,
)]
pub struct GridSort {
    #[serde(default)]
    pub field: GridSortField,
    #[serde(default)]
    pub descending: bool,
}

impl From<GridSort> for media_cache::GridSort {
    fn from(sort: GridSort) -> Self {
        media_cache::GridSort {
            field: sort.field.into(),
            descending: sort.descending,
        }
    }
}

impl From<media_cache::GridSort> for GridSort {
    fn from(sort: media_cache::GridSort) -> Self {
        GridSort {
            field: sort.field.into(),
            descending: sort.descending,
        }
    }
}

/// §2.2's Watched filter -- mirrors `media_cache::WatchedFilter` 1:1.
/// "Never started" (`Unwatched`) is distinct from `HasUnwatched` ("not finished").
#[derive(
    uniffi::Enum, Debug, Clone, Copy, Default, PartialEq, Eq, serde::Serialize, serde::Deserialize,
)]
pub enum WatchedFilter {
    #[default]
    Any,
    Unwatched,
    HasUnwatched,
    Watched,
}

impl From<WatchedFilter> for media_cache::WatchedFilter {
    fn from(filter: WatchedFilter) -> Self {
        match filter {
            WatchedFilter::Any => media_cache::WatchedFilter::Any,
            WatchedFilter::Unwatched => media_cache::WatchedFilter::Unwatched,
            WatchedFilter::HasUnwatched => media_cache::WatchedFilter::HasUnwatched,
            WatchedFilter::Watched => media_cache::WatchedFilter::Watched,
        }
    }
}

impl From<media_cache::WatchedFilter> for WatchedFilter {
    fn from(filter: media_cache::WatchedFilter) -> Self {
        match filter {
            media_cache::WatchedFilter::Any => WatchedFilter::Any,
            media_cache::WatchedFilter::Unwatched => WatchedFilter::Unwatched,
            media_cache::WatchedFilter::HasUnwatched => WatchedFilter::HasUnwatched,
            media_cache::WatchedFilter::Watched => WatchedFilter::Watched,
        }
    }
}

/// TV Shows' Continuing/Ended chip (§2.2) -- mirrors
/// `media_cache::StatusFilter` 1:1.
#[derive(
    uniffi::Enum, Debug, Clone, Copy, Default, PartialEq, Eq, serde::Serialize, serde::Deserialize,
)]
pub enum StatusFilter {
    #[default]
    Any,
    Continuing,
    Ended,
}

impl From<StatusFilter> for media_cache::StatusFilter {
    fn from(filter: StatusFilter) -> Self {
        match filter {
            StatusFilter::Any => media_cache::StatusFilter::Any,
            StatusFilter::Continuing => media_cache::StatusFilter::Continuing,
            StatusFilter::Ended => media_cache::StatusFilter::Ended,
        }
    }
}

impl From<media_cache::StatusFilter> for StatusFilter {
    fn from(filter: media_cache::StatusFilter) -> Self {
        match filter {
            media_cache::StatusFilter::Any => StatusFilter::Any,
            media_cache::StatusFilter::Continuing => StatusFilter::Continuing,
            media_cache::StatusFilter::Ended => StatusFilter::Ended,
        }
    }
}

/// The Years panel's decade buckets (§2.2/§4.3) -- mirrors
/// `media_cache::Decade` 1:1. `#[default]` on `D2020s` is arbitrary, reached
/// only through [`GridFilters::decade`]'s `Option` (default `None`).
#[derive(
    uniffi::Enum, Debug, Clone, Copy, Default, PartialEq, Eq, serde::Serialize, serde::Deserialize,
)]
pub enum Decade {
    #[default]
    D2020s,
    D2010s,
    D2000s,
    D1990s,
    D1980s,
    Older,
}

impl From<Decade> for media_cache::Decade {
    fn from(decade: Decade) -> Self {
        match decade {
            Decade::D2020s => media_cache::Decade::D2020s,
            Decade::D2010s => media_cache::Decade::D2010s,
            Decade::D2000s => media_cache::Decade::D2000s,
            Decade::D1990s => media_cache::Decade::D1990s,
            Decade::D1980s => media_cache::Decade::D1980s,
            Decade::Older => media_cache::Decade::Older,
        }
    }
}

impl From<media_cache::Decade> for Decade {
    fn from(decade: media_cache::Decade) -> Self {
        match decade {
            media_cache::Decade::D2020s => Decade::D2020s,
            media_cache::Decade::D2010s => Decade::D2010s,
            media_cache::Decade::D2000s => Decade::D2000s,
            media_cache::Decade::D1990s => Decade::D1990s,
            media_cache::Decade::D1980s => Decade::D1980s,
            media_cache::Decade::Older => Decade::Older,
        }
    }
}

/// The strip's full filter state (§2.2), ANDed together -- mirrors
/// `media_cache::GridFilters` 1:1. A stale "has_unwatched" key from an older
/// `library_grid_prefs.json` is dropped by serde's unknown-field default.
#[derive(
    uniffi::Record, Debug, Clone, Default, PartialEq, Eq, serde::Serialize, serde::Deserialize,
)]
#[serde(default)]
pub struct GridFilters {
    pub watched: WatchedFilter,
    pub genre: Option<String>,
    pub decade: Option<Decade>,
    pub status: StatusFilter,
    /// Exact item type (`"Movie"`, `"Series"`, ...): the Favorites grid's type chips
    /// (docs/16 §2.7). `None` = every type.
    #[uniffi(default = None)]
    pub item_type: Option<String>,
}

impl From<GridFilters> for media_cache::GridFilters {
    fn from(filters: GridFilters) -> Self {
        media_cache::GridFilters {
            watched: filters.watched.into(),
            genre: filters.genre,
            decade: filters.decade.map(Into::into),
            status: filters.status.into(),
            item_type: filters.item_type,
        }
    }
}

impl From<&GridFilters> for media_cache::GridFilters {
    fn from(filters: &GridFilters) -> Self {
        media_cache::GridFilters::from(filters.clone())
    }
}

impl From<media_cache::GridFilters> for GridFilters {
    fn from(filters: media_cache::GridFilters) -> Self {
        GridFilters {
            watched: filters.watched.into(),
            genre: filters.genre,
            decade: filters.decade.map(Into::into),
            status: filters.status.into(),
            item_type: filters.item_type,
        }
    }
}

/// [`crate::JellybeamCore::library_grid_counts`]'s result (§2.3) -- mirrors
/// `media_cache::GridCounts` 1:1.
#[derive(
    uniffi::Record, Debug, Clone, Copy, Default, PartialEq, Eq, serde::Serialize, serde::Deserialize,
)]
pub struct GridCounts {
    pub filtered: u64,
    pub total: u64,
}

impl From<media_cache::GridCounts> for GridCounts {
    fn from(counts: media_cache::GridCounts) -> Self {
        GridCounts {
            filtered: counts.filtered,
            total: counts.total,
        }
    }
}

/// One bucket of [`crate::JellybeamCore::library_grid_groups`]'s result
/// (§2.4) -- mirrors `media_cache::GridGroup` 1:1.
#[derive(
    uniffi::Record, Debug, Clone, Default, PartialEq, Eq, serde::Serialize, serde::Deserialize,
)]
pub struct GridGroup {
    pub key: String,
    pub count: u64,
}

impl From<media_cache::GridGroup> for GridGroup {
    fn from(group: media_cache::GridGroup) -> Self {
        GridGroup {
            key: group.key,
            count: group.count,
        }
    }
}

/// §5: the whole per-library persisted grid state, keyed by view id in
/// `crate::library_prefs`'s on-disk map. This crate's own record, not a
/// mirror -- `media_cache` has no persistence concept of its own.
#[derive(
    uniffi::Record, Debug, Clone, Default, PartialEq, Eq, serde::Serialize, serde::Deserialize,
)]
#[serde(default)]
pub struct LibraryGridPrefs {
    pub sort: GridSort,
    pub filters: GridFilters,
}

/// Sort for [`crate::object::JellybeamCore::live_children`] -- no mirror-side
/// counterpart. `ServerOrder` sends no `sortBy`/`sortOrder` at all.
#[derive(uniffi::Enum, Debug, Clone, Copy, PartialEq, Eq)]
pub enum LiveSort {
    /// No `sortBy`/`sortOrder` sent -- whatever order the server/plugin
    /// returns.
    ServerOrder,
    /// `SortBy=SortName&SortOrder=Ascending`.
    NameAsc,
    /// `SortBy=PremiereDate&SortOrder=Descending`.
    NewestFirst,
}

/// Mirrors `jellyfin_api::ImageKind` 1:1 -- the three kinds
/// `JellyfinClient::image_url` accepts (not `media_cache::ImageKind`,
/// which also has `Trickplay` for its own on-disk cache).
#[derive(uniffi::Enum, Debug, Clone, Copy, PartialEq, Eq)]
pub enum ImageKind {
    Primary,
    Backdrop,
    Thumb,
}

impl From<ImageKind> for jellyfin_api::ImageKind {
    fn from(kind: ImageKind) -> Self {
        match kind {
            ImageKind::Primary => jellyfin_api::ImageKind::Primary,
            ImageKind::Backdrop => jellyfin_api::ImageKind::Backdrop,
            ImageKind::Thumb => jellyfin_api::ImageKind::Thumb,
        }
    }
}

/// Mirrors `jellyfin_core::VideoCodec` 1:1 -- codecs a probed `MediaCodec`
/// decoder can be advertised for (see [`VideoCaps`]).
#[derive(uniffi::Enum, Debug, Clone, Copy, PartialEq, Eq)]
pub enum VideoCodecId {
    H264,
    Hevc,
    Av1,
    Vp8,
    Vp9,
    Mpeg2Video,
    Vc1,
}

impl From<VideoCodecId> for jellyfin_core::VideoCodec {
    fn from(codec: VideoCodecId) -> Self {
        match codec {
            VideoCodecId::H264 => jellyfin_core::VideoCodec::H264,
            VideoCodecId::Hevc => jellyfin_core::VideoCodec::Hevc,
            VideoCodecId::Av1 => jellyfin_core::VideoCodec::Av1,
            VideoCodecId::Vp8 => jellyfin_core::VideoCodec::Vp8,
            VideoCodecId::Vp9 => jellyfin_core::VideoCodec::Vp9,
            VideoCodecId::Mpeg2Video => jellyfin_core::VideoCodec::Mpeg2Video,
            VideoCodecId::Vc1 => jellyfin_core::VideoCodec::Vc1,
        }
    }
}

/// Mirrors `jellyfin_core::VideoCodecCaps` 1:1 -- see that struct's own
/// field docs in `jellyfin-core`.
#[derive(uniffi::Record, Debug, Clone, PartialEq)]
pub struct ProfileLevelCaps {
    pub profile: String,
    pub max_level: i32,
}

#[derive(uniffi::Record, Debug, Clone, PartialEq)]
pub struct VideoCaps {
    pub codec: VideoCodecId,
    pub profiles: Vec<String>,
    pub max_level: Option<i32>,
    pub profile_levels: Vec<ProfileLevelCaps>,
    pub max_width: Option<u32>,
    pub max_height: Option<u32>,
    pub unsupported_video_ranges: Vec<String>,
}

impl From<VideoCaps> for jellyfin_core::VideoCodecCaps {
    fn from(caps: VideoCaps) -> Self {
        jellyfin_core::VideoCodecCaps {
            codec: caps.codec.into(),
            profiles: caps.profiles,
            max_level: caps.max_level,
            profile_levels: caps
                .profile_levels
                .into_iter()
                .map(|level| jellyfin_core::VideoProfileLevel {
                    profile: level.profile,
                    max_level: level.max_level,
                })
                .collect(),
            max_width: caps.max_width,
            max_height: caps.max_height,
            unsupported_video_ranges: caps.unsupported_video_ranges,
        }
    }
}

/// Mirrors `jellyfin_core::AndroidTvCaps` 1:1, passed to
/// [`crate::JellybeamCore::set_device_caps`]; never calling it gets
/// `jellyfin_core`'s conservative baseline floor.
#[derive(uniffi::Record, Debug, Clone, PartialEq, Default)]
pub struct DeviceCaps {
    pub max_streaming_bitrate: Option<u32>,
    pub video: Vec<VideoCaps>,
}

impl From<DeviceCaps> for jellyfin_core::AndroidTvCaps {
    fn from(caps: DeviceCaps) -> Self {
        jellyfin_core::AndroidTvCaps {
            max_streaming_bitrate: caps.max_streaming_bitrate,
            video: caps.video.into_iter().map(Into::into).collect(),
        }
    }
}

/// Mirrors `playback_policy::trickplay::TrickplayMeta` 1:1 -- the manifest
/// entry `prepare_playback` resolved. `None` on [`PlaybackPlan::trickplay`]
/// means no usable manifest yet, never fatal to playback.
#[derive(uniffi::Record, Debug, Clone, Copy, PartialEq, Eq)]
pub struct TrickplayMetaFfi {
    pub width: u32,
    pub height: u32,
    pub tile_width: u32,
    pub tile_height: u32,
    pub interval_ms: u32,
    pub thumbnail_count: u32,
}

impl From<playback_policy::trickplay::TrickplayMeta> for TrickplayMetaFfi {
    fn from(meta: playback_policy::trickplay::TrickplayMeta) -> Self {
        TrickplayMetaFfi {
            width: meta.width,
            height: meta.height,
            tile_width: meta.tile_width,
            tile_height: meta.tile_height,
            interval_ms: meta.interval_ms,
            thumbnail_count: meta.thumbnail_count,
        }
    }
}

impl From<TrickplayMetaFfi> for playback_policy::trickplay::TrickplayMeta {
    fn from(meta: TrickplayMetaFfi) -> Self {
        playback_policy::trickplay::TrickplayMeta {
            width: meta.width,
            height: meta.height,
            tile_width: meta.tile_width,
            tile_height: meta.tile_height,
            interval_ms: meta.interval_ms,
            thumbnail_count: meta.thumbnail_count,
        }
    }
}

/// Mirrors `playback_policy::trickplay::TrickplayTile` 1:1, returned by
/// [`crate::trickplay_locate`]. `x`/`y` are this thumbnail's top-left pixel
/// offset within sheet `image_index`.
#[derive(uniffi::Record, Debug, Clone, Copy, PartialEq, Eq)]
pub struct TrickplayTileFfi {
    pub image_index: u32,
    pub x: u32,
    pub y: u32,
}

impl From<playback_policy::trickplay::TrickplayTile> for TrickplayTileFfi {
    fn from(tile: playback_policy::trickplay::TrickplayTile) -> Self {
        TrickplayTileFfi {
            image_index: tile.image_index,
            x: tile.x,
            y: tile.y,
        }
    }
}

/// docs/18-playback-quality.md §2: which endpoint [`PlaybackPlan::url`]
/// actually is. `DirectPlay` covers both a genuine agreement and Auto's
/// attempt-anyway plan ([`PlaybackPlan::server_verdict`] tells them apart).
/// `Transcode` comes from Cap mode, Auto's codec corroboration, or
/// `prepare_transcode_fallback` -- never from `DirectPlay` mode, which
/// refuses outright instead.
#[derive(uniffi::Enum, Debug, Clone, Copy, PartialEq, Eq)]
pub enum PlayMethodFfi {
    DirectPlay,
    Transcode,
}

/// The plan Kotlin's Media3 player executes, returned by
/// `prepare_playback`/`prepare_transcode_fallback`. docs/18-playback-quality.md
/// §2: the `play_method`/`transcode_reason`/`server_verdict` fields let
/// Kotlin tell a genuine Direct Play plan apart from an attempt-anyway one.
#[derive(uniffi::Record, Debug, Clone, PartialEq)]
pub struct PlaybackPlan {
    pub item_id: String,
    pub item_name: String,
    pub url: String,
    pub media_source_id: String,
    pub play_session_id: String,
    /// `DirectPlay` or `Transcode` -- see [`PlayMethodFfi`].
    pub play_method: PlayMethodFfi,
    /// Set only on a `Transcode` plan: a codec-decoder reason (Auto), the
    /// local failure text (fallback path), or "quality cap <N> Mbps" (Cap).
    pub transcode_reason: Option<String>,
    /// Set only on an attempt-anyway `DirectPlay` plan: the server's own
    /// reasons, joined with "; " -- OSD text only, never a reason to transcode.
    pub server_verdict: Option<String>,
    /// `Settings::playback_quality != DirectPlay` when built -- lets Kotlin
    /// know whether `prepare_transcode_fallback` is worth calling.
    pub transcode_fallback_allowed: bool,
    /// Resume position, in Jellyfin's 100ns "ticks" -- `0` if none saved.
    pub start_position_ticks: i64,
    pub runtime_ticks: Option<i64>,
    pub container: Option<String>,
    /// The item's own `BaseItemKind`, 1:1 with `Card::item_type`'s
    /// convention -- Kotlin's next-up driver needs this to know if the
    /// loaded item is an Episode before calling `next_episode_after`.
    pub item_type: String,
    /// Episode breadcrumb metadata (docs/12-osd-ux-spec.md top region) --
    /// all `None` for non-episodes; the formatter degrades per-segment.
    pub series_name: Option<String>,
    pub parent_index_number: Option<i32>,
    pub index_number: Option<i32>,
    /// The item's own `SeriesId`, if any (`None` for a Movie): docs/09 step
    /// 3 keys per-series track memory on this, not the individual episode.
    pub series_id: Option<String>,
    // `outro_start_secs`/`trickplay` don't live here: neither is needed to
    // start a frame, and Kotlin fetches both separately (fire-and-forget).
}

/// Mirrors `media_cache::MirrorChange` 1:1, forwarded to
/// [`crate::ChangeListener::on_change`].
#[derive(uniffi::Enum, Debug, Clone, PartialEq, Eq)]
pub enum ChangeEvent {
    Upserted {
        ids: Vec<String>,
        library_id: Option<String>,
    },
    Removed {
        ids: Vec<String>,
        library_id: Option<String>,
    },
    ViewsChanged,
    Refresh,
}

impl From<media_cache::MirrorChange> for ChangeEvent {
    fn from(change: media_cache::MirrorChange) -> Self {
        match change {
            media_cache::MirrorChange::Upserted { ids, library_id } => {
                ChangeEvent::Upserted { ids, library_id }
            }
            media_cache::MirrorChange::Removed { ids, library_id } => {
                ChangeEvent::Removed { ids, library_id }
            }
            media_cache::MirrorChange::ViewsChanged => ChangeEvent::ViewsChanged,
            media_cache::MirrorChange::Refresh => ChangeEvent::Refresh,
        }
    }
}

/// `media_cache::SyncActivity` for the UI, with the view id swapped for the
/// server's own library name -- a sync-status affordance, read via [`crate::JellybeamCore::sync_status`] as a plain snapshot poll.
/// No field named `message` (uniffi/Kotlin `Exception.message` collision).
#[derive(uniffi::Enum, Debug, Clone, PartialEq, Eq)]
pub enum SyncStatus {
    /// No bulk library pass currently running.
    Idle,
    /// A bulk library pass (initial sync or reconcile sweep --
    /// deliberately indistinguishable here) is in progress.
    Syncing {
        /// The library's server-configured name, verbatim; `None` when the
        /// mirror holds no view for the id yet. Never the raw id.
        library_name: Option<String>,
        /// Pages already fetched for this library in the current pass.
        pages_done: u32,
        /// Items already accounted for in the current pass.
        items_done: u32,
        /// The denominator for `items_done`; `None` while waiting on the
        /// first page's response.
        total_items: Option<u32>,
    },
}

impl SyncStatus {
    /// `resolve_name` maps the activity's view id to a library name.
    pub(crate) fn from_activity(
        activity: media_cache::SyncActivity,
        resolve_name: impl FnOnce(&str) -> Option<String>,
    ) -> Self {
        match activity {
            media_cache::SyncActivity::Idle => SyncStatus::Idle,
            media_cache::SyncActivity::Syncing {
                library_name_or_id,
                pages_done,
                items_done,
                total_items,
            } => SyncStatus::Syncing {
                library_name: resolve_name(&library_name_or_id),
                pages_done,
                items_done,
                total_items,
            },
        }
    }
}

/// Mirrors `playback_policy::tracks::TrackKind` 1:1. `Ffi` suffix keeps
/// this module's convention of never exporting a `playback_policy`/
/// `media_cache` type directly across the FFI boundary.
#[derive(uniffi::Enum, Debug, Clone, Copy, PartialEq, Eq)]
pub enum TrackKindFfi {
    Video,
    Audio,
    Subtitle,
}

impl From<TrackKindFfi> for playback_policy::tracks::TrackKind {
    fn from(kind: TrackKindFfi) -> Self {
        match kind {
            TrackKindFfi::Video => playback_policy::tracks::TrackKind::Video,
            TrackKindFfi::Audio => playback_policy::tracks::TrackKind::Audio,
            TrackKindFfi::Subtitle => playback_policy::tracks::TrackKind::Subtitle,
        }
    }
}

/// One audio/video/subtitle track, from Kotlin's Media3 track mapping.
/// Mirrors `playback_policy::tracks::Track` 1:1, `is_`-prefixed since bare
/// `default`/`selected` collide with Kotlin keywords in generated bindings.
#[derive(uniffi::Record, Debug, Clone, PartialEq)]
pub struct TrackInfo {
    /// `id` is only meaningful within one
    /// [`crate::JellybeamCore::resolve_tracks`] call -- see
    /// `TrackMapping.kt`'s `groupIndex * 1000 + trackIndex` scheme.
    pub id: i64,
    pub kind: TrackKindFfi,
    pub title: Option<String>,
    pub lang: Option<String>,
    pub codec: Option<String>,
    pub is_default: bool,
    pub is_selected: bool,
    pub is_forced: bool,
}

impl From<TrackInfo> for playback_policy::tracks::Track {
    fn from(info: TrackInfo) -> Self {
        playback_policy::tracks::Track {
            id: info.id,
            kind: info.kind.into(),
            title: info.title,
            lang: info.lang,
            codec: info.codec,
            default: info.is_default,
            selected: info.is_selected,
            forced: info.is_forced,
        }
    }
}

/// Mirrors the two no-track-id halves of `SubtitleDecision`; `Track(id)` is
/// flattened onto [`TrackDecisionFfi::subtitle_track_id`] instead of a
/// third variant (uniffi enum payloads bind poorly in Kotlin).
#[derive(uniffi::Enum, Debug, Clone, Copy, PartialEq, Eq)]
pub enum SubtitleActionFfi {
    /// Don't touch subtitle selection. Only meaningful when
    /// [`TrackDecisionFfi::subtitle_track_id`] is `None`.
    Leave,
    /// Explicitly disable subtitles. Only meaningful when
    /// [`TrackDecisionFfi::subtitle_track_id`] is `None`.
    Off,
}

/// The uniffi-flattened form of `playback_policy::tracks::TrackDecision`,
/// returned by [`crate::JellybeamCore::resolve_tracks`]. Check
/// [`Self::subtitle_track_id`] first: `Some(id)` selects that track
/// ([`Self::subtitle_action`] is then meaningless); `None` defers to
/// [`Self::subtitle_action`] -- avoids a third enum variant.
#[derive(uniffi::Record, Debug, Clone, Copy, PartialEq, Eq)]
pub struct TrackDecisionFfi {
    /// `Some(id)` to switch audio track; `None` to leave the
    /// stream/server default alone -- 1:1 with `TrackDecision::audio`.
    pub audio_track_id: Option<i64>,
    pub subtitle_action: SubtitleActionFfi,
    pub subtitle_track_id: Option<i64>,
}

impl From<playback_policy::tracks::TrackDecision> for TrackDecisionFfi {
    fn from(decision: playback_policy::tracks::TrackDecision) -> Self {
        use playback_policy::tracks::SubtitleDecision;
        let (subtitle_action, subtitle_track_id) = match decision.subtitle {
            SubtitleDecision::Leave => (SubtitleActionFfi::Leave, None),
            SubtitleDecision::Off => (SubtitleActionFfi::Off, None),
            // `subtitle_action` is unused whenever `subtitle_track_id` is
            // `Some`; `Leave` here is an arbitrary placeholder.
            SubtitleDecision::Track(id) => (SubtitleActionFfi::Leave, Some(id)),
        };
        TrackDecisionFfi {
            audio_track_id: decision.audio,
            subtitle_action,
            subtitle_track_id,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn mirror_item_counts_bucket_known_types_and_drop_the_rest() {
        let counts = MirrorItemCounts::from_type_counts(&[
            ("Movie".to_string(), 12_345),
            ("Episode".to_string(), 7),
            ("Season".to_string(), 3),
            ("Book".to_string(), 2),
            ("AudioBook".to_string(), 1),
        ]);
        assert_eq!(
            counts,
            MirrorItemCounts {
                movies: 12_345,
                episodes: 7,
                books: 3,
                ..Default::default()
            }
        );
    }

    #[test]
    fn audio_spatial_prefers_the_server_field_then_recovers_from_profile() {
        use jellyfin_api::models::{AudioSpatialFormat, MediaStream};
        let of = |format: Option<AudioSpatialFormat>, profile: Option<&str>| {
            MediaStreamInfo::from(&MediaStream {
                audio_spatial_format: format,
                profile: profile.map(str::to_string),
                ..Default::default()
            })
            .audio_spatial
        };

        assert_eq!(
            of(Some(AudioSpatialFormat::DolbyAtmos), None),
            Some(AudioSpatialKind::DolbyAtmos)
        );
        assert_eq!(
            of(Some(AudioSpatialFormat::Dtsx), None),
            Some(AudioSpatialKind::DtsX)
        );
        assert_eq!(
            of(Some(AudioSpatialFormat::None), Some("TrueHD Atmos 7.1")),
            Some(AudioSpatialKind::DolbyAtmos)
        );
        assert_eq!(
            of(None, Some("DTS-HD MA + DTS:X")),
            Some(AudioSpatialKind::DtsX)
        );
        assert_eq!(of(None, Some("DTS-HD MA")), None);
        assert_eq!(of(None, None), None);
    }

    #[test]
    fn sync_status_maps_idle() {
        let status = SyncStatus::from_activity(media_cache::SyncActivity::Idle, |_| None);
        assert_eq!(status, SyncStatus::Idle);
    }

    #[test]
    fn sync_status_swaps_the_view_id_for_its_name() {
        let activity = media_cache::SyncActivity::Syncing {
            library_name_or_id: "lib-1".to_string(),
            pages_done: 2,
            items_done: 40,
            total_items: Some(200),
        };
        let named = SyncStatus::from_activity(activity.clone(), |id| {
            (id == "lib-1").then(|| "Movies (4K)".to_string())
        });
        assert_eq!(
            named,
            SyncStatus::Syncing {
                library_name: Some("Movies (4K)".to_string()),
                pages_done: 2,
                items_done: 40,
                total_items: Some(200),
            }
        );

        let unresolved = SyncStatus::from_activity(activity, |_| None);
        assert!(matches!(
            unresolved,
            SyncStatus::Syncing {
                library_name: None,
                ..
            }
        ));
    }

    #[test]
    fn trickplay_meta_ffi_round_trips_the_policy_struct() {
        let policy = playback_policy::trickplay::TrickplayMeta {
            width: 320,
            height: 180,
            tile_width: 10,
            tile_height: 10,
            interval_ms: 10_000,
            thumbnail_count: 1_000,
        };
        let ffi = TrickplayMetaFfi::from(policy);
        assert_eq!(ffi.width, 320);
        assert_eq!(ffi.height, 180);
        assert_eq!(ffi.tile_width, 10);
        assert_eq!(ffi.tile_height, 10);
        assert_eq!(ffi.interval_ms, 10_000);
        assert_eq!(ffi.thumbnail_count, 1_000);

        let back: playback_policy::trickplay::TrickplayMeta = ffi.into();
        assert_eq!(back, policy);
    }

    #[test]
    fn trickplay_tile_ffi_maps_1_to_1() {
        let tile = playback_policy::trickplay::TrickplayTile {
            image_index: 2,
            x: 640,
            y: 180,
        };
        let ffi = TrickplayTileFfi::from(tile);
        assert_eq!(ffi.image_index, 2);
        assert_eq!(ffi.x, 640);
        assert_eq!(ffi.y, 180);
    }

    #[test]
    fn sort_order_maps_1_to_1() {
        assert_eq!(
            media_cache::Sort::from(SortOrder::NameAsc),
            media_cache::Sort::NameAsc
        );
        assert_eq!(
            media_cache::Sort::from(SortOrder::DateCreatedDesc),
            media_cache::Sort::DateCreatedDesc
        );
        assert_eq!(
            media_cache::Sort::from(SortOrder::PremiereDateDesc),
            media_cache::Sort::PremiereDateDesc
        );
        assert_eq!(
            media_cache::Sort::from(SortOrder::IndexNumber),
            media_cache::Sort::IndexNumber
        );
    }

    #[test]
    fn grid_sort_field_maps_1_to_1_both_directions() {
        for (ffi, cache) in [
            (GridSortField::Name, media_cache::GridSortField::Name),
            (
                GridSortField::DateAdded,
                media_cache::GridSortField::DateAdded,
            ),
            (GridSortField::Year, media_cache::GridSortField::Year),
            (GridSortField::Runtime, media_cache::GridSortField::Runtime),
        ] {
            assert_eq!(media_cache::GridSortField::from(ffi), cache);
            assert_eq!(GridSortField::from(cache), ffi);
        }
        assert_eq!(GridSortField::default(), GridSortField::Name);
    }

    #[test]
    fn grid_sort_maps_1_to_1_both_directions() {
        let sort = GridSort {
            field: GridSortField::Year,
            descending: true,
        };
        let cache: media_cache::GridSort = sort.into();
        assert_eq!(cache.field, media_cache::GridSortField::Year);
        assert!(cache.descending);
        assert_eq!(GridSort::from(cache), sort);
        assert_eq!(GridSort::default(), GridSort::default());
    }

    #[test]
    fn watched_filter_maps_1_to_1_both_directions() {
        for (ffi, cache) in [
            (WatchedFilter::Any, media_cache::WatchedFilter::Any),
            (
                WatchedFilter::Unwatched,
                media_cache::WatchedFilter::Unwatched,
            ),
            (
                WatchedFilter::HasUnwatched,
                media_cache::WatchedFilter::HasUnwatched,
            ),
            (WatchedFilter::Watched, media_cache::WatchedFilter::Watched),
        ] {
            assert_eq!(media_cache::WatchedFilter::from(ffi), cache);
            assert_eq!(WatchedFilter::from(cache), ffi);
        }
    }

    #[test]
    fn status_filter_maps_1_to_1_both_directions() {
        for (ffi, cache) in [
            (StatusFilter::Any, media_cache::StatusFilter::Any),
            (
                StatusFilter::Continuing,
                media_cache::StatusFilter::Continuing,
            ),
            (StatusFilter::Ended, media_cache::StatusFilter::Ended),
        ] {
            assert_eq!(media_cache::StatusFilter::from(ffi), cache);
            assert_eq!(StatusFilter::from(cache), ffi);
        }
    }

    #[test]
    fn decade_maps_1_to_1_both_directions() {
        for (ffi, cache) in [
            (Decade::D2020s, media_cache::Decade::D2020s),
            (Decade::D2010s, media_cache::Decade::D2010s),
            (Decade::D2000s, media_cache::Decade::D2000s),
            (Decade::D1990s, media_cache::Decade::D1990s),
            (Decade::D1980s, media_cache::Decade::D1980s),
            (Decade::Older, media_cache::Decade::Older),
        ] {
            assert_eq!(media_cache::Decade::from(ffi), cache);
            assert_eq!(Decade::from(cache), ffi);
        }
    }

    #[test]
    fn grid_filters_maps_1_to_1_both_directions() {
        let filters = GridFilters {
            watched: WatchedFilter::Unwatched,
            genre: Some("Comedy".to_string()),
            decade: Some(Decade::D1990s),
            status: StatusFilter::Continuing,
            item_type: None,
        };
        let cache: media_cache::GridFilters = filters.clone().into();
        assert_eq!(cache.watched, media_cache::WatchedFilter::Unwatched);
        assert_eq!(cache.genre.as_deref(), Some("Comedy"));
        assert_eq!(cache.decade, Some(media_cache::Decade::D1990s));
        assert_eq!(cache.status, media_cache::StatusFilter::Continuing);
        assert_eq!(GridFilters::from(cache.clone()), filters);
        // `From<&GridFilters>` (used by `object.rs` for a by-reference
        // call) must agree with the owned `From<GridFilters>` conversion.
        let cache_from_ref: media_cache::GridFilters = (&filters).into();
        assert_eq!(cache_from_ref, cache);
    }

    #[test]
    fn grid_filters_default_matches_docs_16_defaults() {
        let defaults = GridFilters::default();
        assert_eq!(defaults.watched, WatchedFilter::Any);
        assert!(defaults.genre.is_none());
        assert!(defaults.decade.is_none());
        assert_eq!(defaults.status, StatusFilter::Any);
    }

    #[test]
    fn grid_counts_maps_1_to_1() {
        let counts = GridCounts::from(media_cache::GridCounts {
            filtered: 23,
            total: 342,
        });
        assert_eq!(
            counts,
            GridCounts {
                filtered: 23,
                total: 342,
            }
        );
    }

    #[test]
    fn grid_group_maps_1_to_1() {
        let group = GridGroup::from(media_cache::GridGroup {
            key: "A".to_string(),
            count: 12,
        });
        assert_eq!(
            group,
            GridGroup {
                key: "A".to_string(),
                count: 12,
            }
        );
    }

    #[test]
    fn library_grid_prefs_default_matches_docs_16_defaults() {
        let prefs = LibraryGridPrefs::default();
        assert_eq!(prefs.sort, GridSort::default());
        assert_eq!(prefs.sort.field, GridSortField::Name);
        assert!(!prefs.sort.descending);
        assert_eq!(prefs.filters, GridFilters::default());
    }

    #[test]
    fn library_grid_prefs_round_trips_through_json() {
        let original = LibraryGridPrefs {
            sort: GridSort {
                field: GridSortField::Runtime,
                descending: true,
            },
            filters: GridFilters {
                watched: WatchedFilter::Watched,
                genre: Some("Drama".to_string()),
                decade: Some(Decade::Older),
                status: StatusFilter::Ended,
                item_type: None,
            },
        };
        let json = serde_json::to_vec(&original).expect("serialize");
        let loaded: LibraryGridPrefs = serde_json::from_slice(&json).expect("deserialize");
        assert_eq!(loaded, original);
    }

    #[test]
    fn view_snapshot_with_empty_collection_type_maps_to_none() {
        let snapshot: ViewSnapshot = media_cache::ViewSummary {
            id: "id-3".to_string(),
            name: "Recordings".to_string(),
            item_type: "CollectionFolder".to_string(),
            collection_type: String::new(),
        }
        .into();
        assert!(snapshot.collection_type.is_none());
    }

    #[test]
    fn image_kind_maps_1_to_1() {
        assert!(matches!(
            jellyfin_api::ImageKind::from(ImageKind::Primary),
            jellyfin_api::ImageKind::Primary
        ));
        assert!(matches!(
            jellyfin_api::ImageKind::from(ImageKind::Backdrop),
            jellyfin_api::ImageKind::Backdrop
        ));
        assert!(matches!(
            jellyfin_api::ImageKind::from(ImageKind::Thumb),
            jellyfin_api::ImageKind::Thumb
        ));
    }

    #[test]
    fn change_event_maps_1_to_1() {
        assert_eq!(
            ChangeEvent::from(media_cache::MirrorChange::Upserted {
                ids: vec!["a".to_string()],
                library_id: Some("view-a".to_string())
            }),
            ChangeEvent::Upserted {
                ids: vec!["a".to_string()],
                library_id: Some("view-a".to_string())
            }
        );
        assert_eq!(
            ChangeEvent::from(media_cache::MirrorChange::Removed {
                ids: vec!["b".to_string()],
                library_id: None
            }),
            ChangeEvent::Removed {
                ids: vec!["b".to_string()],
                library_id: None
            }
        );
        assert_eq!(
            ChangeEvent::from(media_cache::MirrorChange::ViewsChanged),
            ChangeEvent::ViewsChanged
        );
        assert_eq!(
            ChangeEvent::from(media_cache::MirrorChange::Refresh),
            ChangeEvent::Refresh
        );
    }

    #[test]
    fn video_codec_id_maps_1_to_1() {
        assert_eq!(
            jellyfin_core::VideoCodec::from(VideoCodecId::H264),
            jellyfin_core::VideoCodec::H264
        );
        assert_eq!(
            jellyfin_core::VideoCodec::from(VideoCodecId::Hevc),
            jellyfin_core::VideoCodec::Hevc
        );
        assert_eq!(
            jellyfin_core::VideoCodec::from(VideoCodecId::Av1),
            jellyfin_core::VideoCodec::Av1
        );
        assert_eq!(
            jellyfin_core::VideoCodec::from(VideoCodecId::Vp8),
            jellyfin_core::VideoCodec::Vp8
        );
        assert_eq!(
            jellyfin_core::VideoCodec::from(VideoCodecId::Vp9),
            jellyfin_core::VideoCodec::Vp9
        );
        assert_eq!(
            jellyfin_core::VideoCodec::from(VideoCodecId::Mpeg2Video),
            jellyfin_core::VideoCodec::Mpeg2Video
        );
        assert_eq!(
            jellyfin_core::VideoCodec::from(VideoCodecId::Vc1),
            jellyfin_core::VideoCodec::Vc1
        );
    }

    #[test]
    fn device_caps_maps_to_android_tv_caps_1_to_1() {
        let caps = DeviceCaps {
            max_streaming_bitrate: Some(6_000_000),
            video: vec![VideoCaps {
                codec: VideoCodecId::Hevc,
                profiles: vec!["main".to_string(), "main 10".to_string()],
                max_level: Some(153),
                profile_levels: vec![ProfileLevelCaps {
                    profile: "main 10".to_string(),
                    max_level: 120,
                }],
                max_width: Some(3840),
                max_height: Some(2160),
                unsupported_video_ranges: vec!["DOVI".to_string()],
            }],
        };
        let android: jellyfin_core::AndroidTvCaps = caps.into();
        assert_eq!(android.max_streaming_bitrate, Some(6_000_000));
        assert_eq!(android.video.len(), 1);
        let video = &android.video[0];
        assert_eq!(video.codec, jellyfin_core::VideoCodec::Hevc);
        assert_eq!(
            video.profiles,
            vec!["main".to_string(), "main 10".to_string()]
        );
        assert_eq!(video.max_level, Some(153));
        assert_eq!(video.profile_levels[0].profile, "main 10");
        assert_eq!(video.profile_levels[0].max_level, 120);
        assert_eq!(video.max_width, Some(3840));
        assert_eq!(video.max_height, Some(2160));
        assert_eq!(video.unsupported_video_ranges, vec!["DOVI".to_string()]);
    }

    #[test]
    fn device_caps_default_maps_to_android_tv_caps_default() {
        let android: jellyfin_core::AndroidTvCaps = DeviceCaps::default().into();
        assert_eq!(android, jellyfin_core::AndroidTvCaps::default());
    }

    #[test]
    fn view_snapshot_from_library_summary() {
        let snapshot: ViewSnapshot = media_cache::ViewSummary {
            id: "id-1".to_string(),
            name: "Movies".to_string(),
            item_type: "CollectionFolder".to_string(),
            collection_type: "movies".to_string(),
        }
        .into();
        assert_eq!(
            snapshot,
            ViewSnapshot {
                id: "id-1".to_string(),
                name: "Movies".to_string(),
                kind: ViewKind::Library,
                collection_type: Some("movies".to_string()),
            }
        );
    }

    #[test]
    fn view_snapshot_from_channel_summary_maps_to_channel_kind() {
        let snapshot: ViewSnapshot = media_cache::ViewSummary {
            id: "id-2".to_string(),
            name: "Recordings".to_string(),
            item_type: "Channel".to_string(),
            collection_type: String::new(),
        }
        .into();
        assert_eq!(
            snapshot,
            ViewSnapshot {
                id: "id-2".to_string(),
                name: "Recordings".to_string(),
                kind: ViewKind::Channel,
                collection_type: None,
            }
        );
    }

    fn sample_track_info(id: i64, kind: TrackKindFfi) -> TrackInfo {
        TrackInfo {
            id,
            kind,
            title: Some("Track title".to_string()),
            lang: Some("eng".to_string()),
            codec: Some("aac".to_string()),
            is_default: true,
            is_selected: false,
            is_forced: true,
        }
    }

    #[test]
    fn track_kind_ffi_maps_1_to_1() {
        assert_eq!(
            playback_policy::tracks::TrackKind::from(TrackKindFfi::Video),
            playback_policy::tracks::TrackKind::Video
        );
        assert_eq!(
            playback_policy::tracks::TrackKind::from(TrackKindFfi::Audio),
            playback_policy::tracks::TrackKind::Audio
        );
        assert_eq!(
            playback_policy::tracks::TrackKind::from(TrackKindFfi::Subtitle),
            playback_policy::tracks::TrackKind::Subtitle
        );
    }

    #[test]
    fn track_info_maps_every_field_onto_the_playback_policy_track() {
        let info = sample_track_info(42, TrackKindFfi::Audio);
        let track: playback_policy::tracks::Track = info.clone().into();
        assert_eq!(track.id, info.id);
        assert_eq!(track.kind, playback_policy::tracks::TrackKind::Audio);
        assert_eq!(track.title, info.title);
        assert_eq!(track.lang, info.lang);
        assert_eq!(track.codec, info.codec);
        assert_eq!(track.default, info.is_default);
        assert_eq!(track.selected, info.is_selected);
        assert_eq!(track.forced, info.is_forced);
    }

    #[test]
    fn track_decision_ffi_flattens_leave() {
        let decision = playback_policy::tracks::TrackDecision {
            audio: None,
            subtitle: playback_policy::tracks::SubtitleDecision::Leave,
        };
        let ffi: TrackDecisionFfi = decision.into();
        assert_eq!(ffi.audio_track_id, None);
        assert_eq!(ffi.subtitle_action, SubtitleActionFfi::Leave);
        assert_eq!(ffi.subtitle_track_id, None);
    }

    #[test]
    fn track_decision_ffi_flattens_off() {
        let decision = playback_policy::tracks::TrackDecision {
            audio: Some(7),
            subtitle: playback_policy::tracks::SubtitleDecision::Off,
        };
        let ffi: TrackDecisionFfi = decision.into();
        assert_eq!(ffi.audio_track_id, Some(7));
        assert_eq!(ffi.subtitle_action, SubtitleActionFfi::Off);
        assert_eq!(ffi.subtitle_track_id, None);
    }

    #[test]
    fn track_decision_ffi_flattens_a_specific_track_choice() {
        let decision = playback_policy::tracks::TrackDecision {
            audio: None,
            subtitle: playback_policy::tracks::SubtitleDecision::Track(3),
        };
        let ffi: TrackDecisionFfi = decision.into();
        assert_eq!(ffi.subtitle_track_id, Some(3));
        // subtitle_action is a documented don't-care placeholder here, but
        // pin its concrete value anyway so an accidental change is visible.
        assert_eq!(ffi.subtitle_action, SubtitleActionFfi::Leave);
    }

    // -- ItemDetail / Similar / MediaSegments (Detail+OSD tier-2) --

    #[test]
    fn person_info_from_base_item_person_maps_every_field() {
        let id = uuid::Uuid::parse_str("e2f5a5f1-1a0b-4b3a-9c2e-000000000001").expect("uuid");
        let person = jellyfin_api::models::BaseItemPerson {
            id: Some(id),
            name: Some("Some Actor".to_string()),
            role: Some("Some Role".to_string()),
            type_: Some(jellyfin_api::models::PersonKind::Actor),
            primary_image_tag: Some("tag".to_string()),
            ..Default::default()
        };
        let info = PersonInfo::from(&person);
        assert_eq!(info.id.as_deref(), Some(id.to_string()).as_deref());
        assert_eq!(info.name.as_deref(), Some("Some Actor"));
        assert_eq!(info.role.as_deref(), Some("Some Role"));
        assert_eq!(info.person_type.as_deref(), Some("Actor"));
        assert_eq!(info.primary_image_tag.as_deref(), Some("tag"));
    }

    #[test]
    fn media_stream_kind_maps_video_audio_subtitle_and_folds_the_rest_into_other() {
        use jellyfin_api::models::MediaStreamType;
        assert_eq!(
            MediaStreamKind::from(MediaStreamType::Video),
            MediaStreamKind::Video
        );
        assert_eq!(
            MediaStreamKind::from(MediaStreamType::Audio),
            MediaStreamKind::Audio
        );
        assert_eq!(
            MediaStreamKind::from(MediaStreamType::Subtitle),
            MediaStreamKind::Subtitle
        );
        for other in [
            MediaStreamType::EmbeddedImage,
            MediaStreamType::Data,
            MediaStreamType::Lyric,
            MediaStreamType::Unrecognized,
        ] {
            assert_eq!(MediaStreamKind::from(other), MediaStreamKind::Other);
        }
    }

    #[test]
    fn media_stream_info_from_media_stream_maps_every_field() {
        let stream = jellyfin_api::models::MediaStream {
            index: Some(1),
            type_: Some(jellyfin_api::models::MediaStreamType::Video),
            codec: Some("hevc".to_string()),
            language: Some("eng".to_string()),
            display_title: Some("2160p HEVC".to_string()),
            width: Some(3840),
            height: Some(2160),
            channels: Some(6),
            bit_rate: Some(15_000_000),
            bit_depth: Some(10),
            is_default: Some(true),
            video_range: Some(jellyfin_api::models::VideoRange::Hdr),
            video_range_type: Some(jellyfin_api::models::VideoRangeType::Dovi),
            profile: Some("Main 10".to_string()),
            sample_rate: Some(48_000),
            average_frame_rate: Some(23.976),
            real_frame_rate: Some(24.0),
            ..Default::default()
        };
        let info = MediaStreamInfo::from(&stream);
        assert_eq!(info.index, Some(1));
        assert_eq!(info.stream_type, MediaStreamKind::Video);
        assert_eq!(info.codec.as_deref(), Some("hevc"));
        assert_eq!(info.language.as_deref(), Some("eng"));
        assert_eq!(info.display_title.as_deref(), Some("2160p HEVC"));
        assert_eq!(info.width, Some(3840));
        assert_eq!(info.height, Some(2160));
        assert_eq!(info.channels, Some(6));
        assert_eq!(info.bit_rate, Some(15_000_000));
        assert_eq!(info.bit_depth, Some(10));
        assert!(info.is_default);
        assert_eq!(info.video_range.as_deref(), Some("HDR"));
        assert_eq!(info.video_range_type.as_deref(), Some("DOVI"));
        assert_eq!(info.profile.as_deref(), Some("Main 10"));
        assert_eq!(info.sample_rate, Some(48_000));
        // average_frame_rate is present, so it wins over real_frame_rate.
        assert_eq!(info.avg_frame_rate, Some(23.976));
    }

    #[test]
    fn media_stream_info_defaults_missing_is_default_and_stream_type_to_safe_values() {
        let stream = jellyfin_api::models::MediaStream::default();
        let info = MediaStreamInfo::from(&stream);
        assert!(!info.is_default);
        assert_eq!(info.stream_type, MediaStreamKind::Other);
        assert_eq!(info.video_range, None);
        assert_eq!(info.profile, None);
        assert_eq!(info.sample_rate, None);
        assert_eq!(info.avg_frame_rate, None);
    }

    /// When only `RealFrameRate` is populated, `avg_frame_rate` falls
    /// back to it rather than staying `None`.
    #[test]
    fn media_stream_info_avg_frame_rate_falls_back_to_real_frame_rate() {
        let stream = jellyfin_api::models::MediaStream {
            average_frame_rate: None,
            real_frame_rate: Some(29.97),
            ..Default::default()
        };
        let info = MediaStreamInfo::from(&stream);
        assert_eq!(info.avg_frame_rate, Some(29.97));
    }

    #[test]
    fn chapter_info_ffi_from_chapter_info_maps_every_field() {
        let chapter = jellyfin_api::models::ChapterInfo {
            name: Some("Chapter 1".to_string()),
            start_position_ticks: Some(12_345),
            image_tag: Some("chap-tag".to_string()),
            ..Default::default()
        };
        let ffi = ChapterInfoFfi::from(&chapter);
        assert_eq!(ffi.name.as_deref(), Some("Chapter 1"));
        assert_eq!(ffi.start_position_ticks, 12_345);
        assert_eq!(ffi.image_tag.as_deref(), Some("chap-tag"));
    }

    #[test]
    fn chapter_info_ffi_defaults_missing_start_position_to_zero() {
        let chapter = jellyfin_api::models::ChapterInfo::default();
        let ffi = ChapterInfoFfi::from(&chapter);
        assert_eq!(ffi.start_position_ticks, 0);
    }

    #[test]
    fn media_segment_kind_folds_unknown_and_unrecognized_together() {
        use jellyfin_api::models::MediaSegmentType;
        assert_eq!(
            MediaSegmentKind::from(MediaSegmentType::Unknown),
            MediaSegmentKind::Unknown
        );
        assert_eq!(
            MediaSegmentKind::from(MediaSegmentType::Unrecognized),
            MediaSegmentKind::Unknown
        );
        assert_eq!(
            MediaSegmentKind::from(MediaSegmentType::Intro),
            MediaSegmentKind::Intro
        );
        assert_eq!(
            MediaSegmentKind::from(MediaSegmentType::Outro),
            MediaSegmentKind::Outro
        );
        assert_eq!(
            MediaSegmentKind::from(MediaSegmentType::Recap),
            MediaSegmentKind::Recap
        );
        assert_eq!(
            MediaSegmentKind::from(MediaSegmentType::Preview),
            MediaSegmentKind::Preview
        );
        assert_eq!(
            MediaSegmentKind::from(MediaSegmentType::Commercial),
            MediaSegmentKind::Commercial
        );
    }

    #[test]
    fn media_segment_from_dto_maps_ticks_and_defaults_missing_ones_to_zero() {
        let dto = jellyfin_api::models::MediaSegmentDto {
            type_: Some(jellyfin_api::models::MediaSegmentType::Outro),
            start_ticks: Some(100),
            end_ticks: None,
            ..Default::default()
        };
        let segment = MediaSegment::from(dto);
        assert_eq!(segment.segment_type, MediaSegmentKind::Outro);
        assert_eq!(segment.start_ticks, 100);
        assert_eq!(segment.end_ticks, 0);
    }

    fn sample_series_dto() -> jellyfin_api::models::BaseItemDto {
        jellyfin_api::models::BaseItemDto {
            id: Some(uuid::Uuid::parse_str("e2f5a5f1-1a0b-4b3a-9c2e-000000000002").expect("uuid")),
            name: Some("Sample Series".to_string()),
            type_: Some(jellyfin_api::models::BaseItemKind::Series),
            series_name: Some("Parent Series".to_string()),
            parent_index_number: Some(2),
            index_number: Some(7),
            premiere_date: Some(
                chrono::DateTime::parse_from_rfc3339("2007-06-03T00:00:00Z")
                    .expect("valid rfc3339")
                    .with_timezone(&chrono::Utc),
            ),
            user_data: Some(jellyfin_api::models::UserItemDataDto {
                is_favorite: None,
                item_id: None,
                key: Some("sample-key".to_string()),
                play_count: Some(3),
                last_played_date: Some(
                    chrono::DateTime::parse_from_rfc3339("2025-04-07T00:00:00Z")
                        .expect("valid rfc3339")
                        .with_timezone(&chrono::Utc),
                ),
                likes: None,
                playback_position_ticks: None,
                played: None,
                played_percentage: None,
                rating: None,
                unplayed_item_count: None,
            }),
            genres: vec!["Drama".to_string(), "Crime".to_string()],
            official_rating: Some("TV-MA".to_string()),
            community_rating: Some(8.5),
            critic_rating: Some(92.0),
            production_year: Some(2007),
            status: Some("Continuing".to_string()),
            studios: vec![jellyfin_api::models::NameGuidPair {
                id: None,
                name: Some("Example Network".to_string()),
            }],
            overview: Some("A synopsis.".to_string()),
            run_time_ticks: Some(30_000_000_000),
            container: Some("mkv".to_string()),
            people: vec![
                jellyfin_api::models::BaseItemPerson {
                    name: Some("Some Actor".to_string()),
                    type_: Some(jellyfin_api::models::PersonKind::Actor),
                    ..Default::default()
                },
                jellyfin_api::models::BaseItemPerson {
                    name: Some("Some Director".to_string()),
                    type_: Some(jellyfin_api::models::PersonKind::Director),
                    ..Default::default()
                },
                jellyfin_api::models::BaseItemPerson {
                    name: Some("Some Writer".to_string()),
                    type_: Some(jellyfin_api::models::PersonKind::Writer),
                    ..Default::default()
                },
            ],
            media_streams: vec![jellyfin_api::models::MediaStream {
                type_: Some(jellyfin_api::models::MediaStreamType::Video),
                codec: Some("h264".to_string()),
                ..Default::default()
            }],
            chapters: vec![jellyfin_api::models::ChapterInfo {
                name: Some("Chapter 1".to_string()),
                start_position_ticks: Some(0),
                ..Default::default()
            }],
            date_created: Some(
                chrono::DateTime::parse_from_rfc3339("2020-01-02T03:04:05Z")
                    .expect("valid rfc3339")
                    .with_timezone(&chrono::Utc),
            ),
            media_sources: vec![jellyfin_api::models::MediaSourceInfo {
                size: Some(123_456_789),
                path: Some("/data/Shows/Sample Series/S04/S04E18.mkv".to_string()),
                ..Default::default()
            }],
            recursive_item_count: Some(42),
            child_count: Some(3),
            ..Default::default()
        }
    }

    #[test]
    fn item_detail_from_base_item_dto_maps_every_field() {
        let dto = sample_series_dto();
        let detail = ItemDetail::from(&dto);
        assert_eq!(detail.id, dto.id.expect("id").to_string());
        assert_eq!(detail.name, "Sample Series");
        assert_eq!(detail.item_type, "Series");
        assert_eq!(detail.series_name.as_deref(), Some("Parent Series"));
        assert_eq!(detail.parent_index_number, Some(2));
        assert_eq!(detail.index_number, Some(7));
        assert_eq!(
            detail.premiere_date.as_deref(),
            Some("2007-06-03T00:00:00+00:00")
        );
        assert_eq!(detail.play_count, 3);
        assert_eq!(
            detail.last_played_date.as_deref(),
            Some("2025-04-07T00:00:00+00:00")
        );
        assert_eq!(
            detail.genres,
            vec!["Drama".to_string(), "Crime".to_string()]
        );
        assert_eq!(detail.official_rating.as_deref(), Some("TV-MA"));
        assert_eq!(detail.community_rating, Some(8.5));
        assert_eq!(detail.critic_rating, Some(92.0));
        assert_eq!(detail.production_year, Some(2007));
        // No `EndDate` on this fixture -- a still-Continuing series has none.
        assert_eq!(detail.end_year, None);
        assert_eq!(detail.status.as_deref(), Some("Continuing"));
        assert_eq!(detail.studios, vec!["Example Network".to_string()]);
        assert_eq!(detail.overview.as_deref(), Some("A synopsis."));
        assert_eq!(detail.run_time_ticks, Some(30_000_000_000));
        assert_eq!(detail.container.as_deref(), Some("mkv"));
        assert_eq!(detail.people.len(), 3);
        assert_eq!(detail.people[0].name.as_deref(), Some("Some Actor"));
        assert_eq!(detail.media_streams.len(), 1);
        assert_eq!(detail.media_streams[0].codec.as_deref(), Some("h264"));
        assert_eq!(detail.chapters.len(), 1);
        assert_eq!(detail.chapters[0].name.as_deref(), Some("Chapter 1"));
        assert_eq!(
            detail.date_created.as_deref(),
            Some("2020-01-02T03:04:05+00:00")
        );
        assert_eq!(detail.size_bytes, Some(123_456_789));
        assert_eq!(detail.recursive_item_count, Some(42));
        assert_eq!(detail.child_count, Some(3));
        assert_eq!(detail.directors, vec!["Some Director".to_string()]);
        assert_eq!(detail.writers, vec!["Some Writer".to_string()]);
    }

    #[test]
    fn playback_osd_detail_maps_only_container_streams_and_chapters() {
        let dto = sample_series_dto();
        let detail = PlaybackOsdDetail::from(&dto);
        assert_eq!(detail.container.as_deref(), Some("mkv"));
        assert_eq!(detail.media_streams.len(), 1);
        assert_eq!(detail.media_streams[0].codec.as_deref(), Some("h264"));
        assert_eq!(detail.chapters.len(), 1);
        assert_eq!(detail.chapters[0].name.as_deref(), Some("Chapter 1"));
        assert_eq!(detail.size_bytes, Some(123_456_789));
        assert_eq!(
            detail.path.as_deref(),
            Some("/data/Shows/Sample Series/S04/S04E18.mkv")
        );
    }

    #[test]
    fn playback_osd_detail_size_bytes_and_path_are_none_when_the_server_sent_no_media_sources() {
        let dto = jellyfin_api::models::BaseItemDto::default();
        let detail = PlaybackOsdDetail::from(&dto);
        assert_eq!(detail.size_bytes, None);
        assert_eq!(detail.path, None);
    }

    #[test]
    fn item_detail_date_created_size_bytes_and_counts_are_none_when_the_server_omits_them() {
        let dto = jellyfin_api::models::BaseItemDto::default();
        let detail = ItemDetail::from(&dto);
        assert_eq!(detail.date_created, None);
        assert_eq!(detail.size_bytes, None);
        assert_eq!(detail.recursive_item_count, None);
        assert_eq!(detail.child_count, None);
        assert!(detail.directors.is_empty());
        assert!(detail.writers.is_empty());
    }

    #[test]
    fn item_detail_size_bytes_comes_from_the_first_media_source_only() {
        let mut dto = sample_series_dto();
        dto.media_sources = vec![
            jellyfin_api::models::MediaSourceInfo {
                size: Some(111),
                ..Default::default()
            },
            jellyfin_api::models::MediaSourceInfo {
                size: Some(222),
                ..Default::default()
            },
        ];
        let detail = ItemDetail::from(&dto);
        assert_eq!(detail.size_bytes, Some(111));
    }

    #[test]
    fn item_detail_end_year_comes_from_end_date_when_present() {
        let mut dto = sample_series_dto();
        dto.status = Some("Ended".to_string());
        dto.end_date = Some(
            chrono::DateTime::parse_from_rfc3339("2013-06-01T00:00:00Z")
                .expect("valid rfc3339")
                .with_timezone(&chrono::Utc),
        );
        let detail = ItemDetail::from(&dto);
        assert_eq!(detail.end_year, Some(2013));
        assert_eq!(detail.status.as_deref(), Some("Ended"));
    }

    #[test]
    fn item_detail_people_is_capped_at_twelve() {
        let mut dto = sample_series_dto();
        dto.people = (0..20)
            .map(|i| jellyfin_api::models::BaseItemPerson {
                name: Some(format!("Person {i}")),
                ..Default::default()
            })
            .collect();
        let detail = ItemDetail::from(&dto);
        assert_eq!(detail.people.len(), 12);
        assert_eq!(detail.people[0].name.as_deref(), Some("Person 0"));
    }

    /// A director/writer past position 12 in a large cast must still show
    /// up on `directors`/`writers`, extracted from the full list.
    #[test]
    fn item_detail_directors_and_writers_survive_the_people_cap() {
        let mut dto = sample_series_dto();
        let mut people: Vec<jellyfin_api::models::BaseItemPerson> = (0..20)
            .map(|i| jellyfin_api::models::BaseItemPerson {
                name: Some(format!("Actor {i}")),
                type_: Some(jellyfin_api::models::PersonKind::Actor),
                ..Default::default()
            })
            .collect();
        people.push(jellyfin_api::models::BaseItemPerson {
            name: Some("Late Director".to_string()),
            type_: Some(jellyfin_api::models::PersonKind::Director),
            ..Default::default()
        });
        people.push(jellyfin_api::models::BaseItemPerson {
            name: Some("Late Writer".to_string()),
            type_: Some(jellyfin_api::models::PersonKind::Writer),
            ..Default::default()
        });
        dto.people = people;

        let detail = ItemDetail::from(&dto);
        assert_eq!(detail.people.len(), 12);
        assert!(detail
            .people
            .iter()
            .all(|p| p.name.as_deref() != Some("Late Director")));
        assert_eq!(detail.directors, vec!["Late Director".to_string()]);
        assert_eq!(detail.writers, vec!["Late Writer".to_string()]);
    }

    #[test]
    fn card_try_from_base_item_dto_maps_every_field() {
        let id = uuid::Uuid::parse_str("e2f5a5f1-1a0b-4b3a-9c2e-000000000003").expect("uuid");
        let mut image_tags = std::collections::HashMap::new();
        image_tags.insert("Primary".to_string(), "primary-tag".to_string());
        let dto = jellyfin_api::models::BaseItemDto {
            id: Some(id),
            name: Some("Sample Movie".to_string()),
            type_: Some(jellyfin_api::models::BaseItemKind::Movie),
            image_tags,
            backdrop_image_tags: vec!["backdrop-tag".to_string()],
            run_time_ticks: Some(72_000_000_000),
            production_year: Some(2020),
            user_data: Some(jellyfin_api::models::UserItemDataDto {
                played: Some(true),
                playback_position_ticks: Some(36_000_000_000),
                key: Some("k".to_string()),
                item_id: None,
                last_played_date: None,
                likes: None,
                play_count: None,
                played_percentage: None,
                rating: None,
                is_favorite: None,
                unplayed_item_count: None,
            }),
            ..Default::default()
        };
        let card = Card::try_from(&dto).expect("has id");
        assert_eq!(card.id, id.to_string());
        assert_eq!(card.item_type, "Movie");
        assert_eq!(card.name, "Sample Movie");
        assert_eq!(card.primary_tag.as_deref(), Some("primary-tag"));
        assert_eq!(card.backdrop_tag.as_deref(), Some("backdrop-tag"));
        assert_eq!(card.runtime_ticks, Some(72_000_000_000));
        assert_eq!(card.production_year, Some(2020));
        assert!(card.played);
        assert_eq!(card.position_ticks, 36_000_000_000);
        assert_eq!(card.library_id, None);
    }

    #[test]
    fn card_try_from_base_item_dto_without_id_fails() {
        let dto = jellyfin_api::models::BaseItemDto::default();
        assert!(Card::try_from(&dto).is_err());
    }
}
