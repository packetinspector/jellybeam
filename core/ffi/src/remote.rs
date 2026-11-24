//! docs/31 remote control, the core half: the capabilities link each bus forwarder steps
//! ([`CapsLink`], docs/31 §3), the commands it hands to Kotlin's [`RemoteControlListener`]
//! (§9), and the Play enrichment that resolves a Play's items before delivery (§4.1).

use std::sync::{Arc, Weak};

use chrono::{DateTime, Utc};
use futures_util::future::BoxFuture;
use futures_util::FutureExt;
use jellyfin_api::models::BaseItemDto;
use jellyfin_api::{
    GeneralCommand, ItemQuery, PlayCommand, PlayRequest, PlaylistItemsShape, PlaystateCommand,
    RemoteCommand, RemoteRepeat, RemoteShuffle, TokenOwner,
};
use jellyfin_core::RemoteControl;
use media_cache::{CardRow, GridSort, GridSortField, Mirror, SongSource, Sort};

use crate::error::CallbackError;
use crate::music::{live_cards, MixSeedFfi};
use crate::next_episode::{is_missing, pick_series_play, series_summary, EpisodeFacts};
use crate::object::{canonicalize_item_id, live_children_fields, JellybeamCore, SharedState};
use crate::types::Card;

type BusWatch = tokio::sync::watch::Receiver<jellyfin_core::BusConnection>;

/// docs/31 §3, §4.4: what the capabilities post is built from, one field per writer
/// ([`JellybeamCore::set_settings`], [`JellybeamCore::set_device_caps`]);
/// `volume_adjustable` is `None` until the first device probe lands.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub(crate) struct CapsInput {
    pub(crate) enabled: bool,
    pub(crate) volume_adjustable: Option<bool>,
}

impl CapsInput {
    /// docs/31 §2: the mode to post, `None` while unprobed so a cold start posts once, after the probe.
    pub(crate) fn mode(self) -> Option<RemoteControl> {
        self.volume_adjustable
            .map(|adjustable| match (self.enabled, adjustable) {
                (false, _) => RemoteControl::Off,
                (true, false) => RemoteControl::FixedVolume,
                (true, true) => RemoteControl::AdjustableVolume,
            })
    }
}

/// docs/31 §3: one bus's capabilities link, owned by its forwarder;
/// `holds` is what the server accepted on this connection, `None` until a post lands.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum CapsLink {
    Down,
    Up { holds: Option<RemoteControl> },
    Dead,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum CapsEvent {
    /// The installed bus connected; `want` is the input's mode at that moment, `None` unprobed.
    Connected {
        want: Option<RemoteControl>,
    },
    /// A bus that is no longer installed connected.
    ConnectedTaken,
    Disconnected,
    /// The socket rejected its token, naming the owner.
    Unauthorized(Option<TokenOwner>),
    /// The input changed; `None` while unprobed.
    Want(Option<RemoteControl>),
    Posted(RemoteControl),
    PostFailed,
    /// The post answered 401, naming the token's owner.
    PostRejected(Option<TokenOwner>),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum CapsEffect {
    Post(RemoteControl),
    AskReauth(Option<TokenOwner>),
    Diag,
}

impl CapsLink {
    /// The table's transition for `event`, with at most one effect.
    pub(crate) fn step(&mut self, event: CapsEvent) -> Option<CapsEffect> {
        use CapsEvent as E;
        let (next, effect) = match (*self, event) {
            (Self::Dead, _) => (Self::Dead, None),
            (_, E::Unauthorized(owner)) | (Self::Up { .. }, E::PostRejected(owner)) => {
                (Self::Dead, Some(CapsEffect::AskReauth(owner)))
            }
            (_, E::Connected { want }) => (Self::Up { holds: None }, want.map(CapsEffect::Post)),
            (_, E::ConnectedTaken | E::Disconnected) => (Self::Down, None),
            (link, E::Want(None)) => (link, None),
            (Self::Up { holds }, E::Want(Some(want))) => (
                Self::Up { holds },
                (holds != Some(want)).then_some(CapsEffect::Post(want)),
            ),
            (Self::Up { .. }, E::Posted(held)) => (Self::Up { holds: Some(held) }, None),
            (up @ Self::Up { .. }, E::PostFailed) => (up, Some(CapsEffect::Diag)),
            (Self::Down, E::Want(_) | E::Posted(_) | E::PostFailed | E::PostRejected(_)) => {
                (Self::Down, None)
            }
        };
        *self = next;
        effect
    }
}

/// docs/31 §3: steps `link` with `event` and runs each effect until none is left. A post goes out
/// on `client` only while `bus` is the installed one, and its answer steps back in; an ask goes
/// through [`JellybeamCore::bus_reauthorization_locked`]; a failed post logs one line.
pub(crate) async fn feed_caps(
    link: &mut CapsLink,
    event: CapsEvent,
    state: &Weak<SharedState>,
    bus: &BusWatch,
    client: &jellyfin_api::JellyfinClient,
) {
    let mut next = Some(event);
    let mut failure = None;
    while let Some(event) = next.take() {
        let Some(effect) = link.step(event) else {
            return;
        };
        let Some(shared) = state.upgrade() else {
            return;
        };
        match effect {
            CapsEffect::Post(mode) => {
                let installed = JellybeamCore::installed_bus_locked(&shared.lock(), bus);
                drop(shared);
                if !installed {
                    return;
                }
                let body = jellyfin_core::capabilities(mode);
                next = Some(match client.post_capabilities(&body).await {
                    Ok(()) => CapsEvent::Posted(mode),
                    Err(jellyfin_api::ApiError::Unauthorized { owner }) => {
                        CapsEvent::PostRejected(owner)
                    }
                    Err(error) => {
                        failure = Some(error);
                        CapsEvent::PostFailed
                    }
                });
            }
            CapsEffect::AskReauth(owner) => {
                let ask = {
                    let guard = shared.lock();
                    JellybeamCore::installed_bus_locked(&guard, bus)
                        .then(|| JellybeamCore::bus_reauthorization_locked(&guard, owner.as_ref()))
                        .flatten()
                };
                drop(shared);
                if let Some((listener, account)) = ask {
                    if let Err(error) = listener.on_unauthorized(account) {
                        tracing::warn!(error = %error, "change listener threw on a rejection");
                    }
                }
            }
            CapsEffect::Diag => {
                if let Some(error) = failure.take() {
                    tracing::warn!(error = %error, "capabilities post failed");
                }
            }
        }
    }
}

/// docs/31 §9: receives each remote command for the installed account, never through the change
/// stream.
#[uniffi::export(with_foreign)]
pub trait RemoteControlListener: Send + Sync {
    /// Called on a runtime thread in arrival order; `epoch` is the account the command came for.
    /// Kotlin only posts it to the main looper.
    fn on_command(&self, epoch: u64, command: RemoteCommandFfi) -> Result<(), CallbackError>;
    /// docs/31 §4.4: the system output volume, read when a report is built.
    fn volume(&self) -> Result<VolumeFfi, CallbackError>;
}

/// docs/31 §4.4: `level` is 0-100.
#[derive(uniffi::Record, Debug, Clone, Copy, PartialEq, Eq)]
pub struct VolumeFfi {
    pub level: u8,
    pub muted: bool,
}

/// docs/31 §4: a delivered command. No `Debug`: ids and texts come from someone else's
/// controller and never reach logs.
#[derive(uniffi::Enum, Clone, PartialEq)]
pub enum RemoteCommandFfi {
    Play { play: RemotePlay },
    Playstate { command: PlaystateCommandFfi },
    General { command: GeneralCommandFfi },
}

/// docs/31 §4.1: a Play's items, resolved for the player its start item goes to.
#[derive(uniffi::Enum, Clone, PartialEq)]
pub enum RemoteItems {
    /// Playable video ids in play order.
    Video { ids: Vec<String> },
    /// The music queue: at most [`QUEUE_FETCH_LIMIT`] songs around the start item's first.
    Songs { songs: Vec<Card> },
    /// An Instant Mix seeded by the start item; nothing is expanded.
    Mix { seed: MixSeedFfi },
}

/// docs/31 §4.1: a Play with its items resolved.
#[derive(uniffi::Record, Clone, PartialEq)]
pub struct RemotePlay {
    pub items: RemoteItems,
    /// Video: index into `ids`; Songs: the start item's first song in `songs`; Mix: 0.
    pub start_index: u32,
    pub start_position_ticks: Option<i64>,
    pub play_command: PlayCommandFfi,
    pub media_source_id: Option<String>,
    pub audio_stream_index: Option<i32>,
    /// `-1` is off.
    pub subtitle_stream_index: Option<i32>,
}

/// Mirrors `jellyfin_api::PlayCommand` 1:1.
#[derive(uniffi::Enum, Debug, Clone, Copy, PartialEq, Eq)]
pub enum PlayCommandFfi {
    PlayNow,
    PlayNext,
    PlayLast,
    PlayShuffle,
    PlayInstantMix,
}

impl From<PlayCommand> for PlayCommandFfi {
    fn from(command: PlayCommand) -> Self {
        match command {
            PlayCommand::PlayNow => Self::PlayNow,
            PlayCommand::PlayNext => Self::PlayNext,
            PlayCommand::PlayLast => Self::PlayLast,
            PlayCommand::PlayShuffle => Self::PlayShuffle,
            PlayCommand::PlayInstantMix => Self::PlayInstantMix,
        }
    }
}

/// Mirrors `jellyfin_api::PlaystateCommand` 1:1.
#[derive(uniffi::Enum, Debug, Clone, Copy, PartialEq, Eq)]
pub enum PlaystateCommandFfi {
    Stop,
    Pause,
    Unpause,
    NextTrack,
    PreviousTrack,
    Seek { position_ticks: i64 },
    Rewind,
    FastForward,
    PlayPause,
}

impl From<PlaystateCommand> for PlaystateCommandFfi {
    fn from(command: PlaystateCommand) -> Self {
        match command {
            PlaystateCommand::Stop => Self::Stop,
            PlaystateCommand::Pause => Self::Pause,
            PlaystateCommand::Unpause => Self::Unpause,
            PlaystateCommand::NextTrack => Self::NextTrack,
            PlaystateCommand::PreviousTrack => Self::PreviousTrack,
            PlaystateCommand::Seek { position_ticks } => Self::Seek { position_ticks },
            PlaystateCommand::Rewind => Self::Rewind,
            PlaystateCommand::FastForward => Self::FastForward,
            PlaystateCommand::PlayPause => Self::PlayPause,
        }
    }
}

/// Mirrors `jellyfin_api::RemoteRepeat` 1:1.
#[derive(uniffi::Enum, Debug, Clone, Copy, PartialEq, Eq)]
pub enum RemoteRepeatFfi {
    Off,
    All,
    One,
}

impl From<RemoteRepeatFfi> for RemoteRepeat {
    fn from(repeat: RemoteRepeatFfi) -> Self {
        match repeat {
            RemoteRepeatFfi::Off => Self::Off,
            RemoteRepeatFfi::All => Self::All,
            RemoteRepeatFfi::One => Self::One,
        }
    }
}

impl From<RemoteRepeat> for RemoteRepeatFfi {
    fn from(repeat: RemoteRepeat) -> Self {
        match repeat {
            RemoteRepeat::Off => Self::Off,
            RemoteRepeat::All => Self::All,
            RemoteRepeat::One => Self::One,
        }
    }
}

/// Mirrors `jellyfin_api::RemoteShuffle` 1:1.
#[derive(uniffi::Enum, Debug, Clone, Copy, PartialEq, Eq)]
pub enum RemoteShuffleFfi {
    Sorted,
    Shuffled,
}

impl From<RemoteShuffleFfi> for RemoteShuffle {
    fn from(shuffle: RemoteShuffleFfi) -> Self {
        match shuffle {
            RemoteShuffleFfi::Sorted => Self::Sorted,
            RemoteShuffleFfi::Shuffled => Self::Shuffled,
        }
    }
}

impl From<RemoteShuffle> for RemoteShuffleFfi {
    fn from(shuffle: RemoteShuffle) -> Self {
        match shuffle {
            RemoteShuffle::Sorted => Self::Sorted,
            RemoteShuffle::Shuffled => Self::Shuffled,
        }
    }
}

/// Mirrors `jellyfin_core::ReportFacts` (docs/31 §7): what a remote-driven Progress states
/// beside the position.
#[derive(uniffi::Enum, Clone, PartialEq, Eq)]
pub enum ReportFactsFfi {
    /// The chosen stream indices, `None` when unknown; `subtitle` -1 is off.
    Video {
        audio: Option<i32>,
        subtitle: Option<i32>,
        list: Option<ReportListFfi>,
    },
    Audio {
        repeat: RemoteRepeatFfi,
        shuffle: RemoteShuffleFfi,
    },
}

/// Mirrors `jellyfin_core::ReportList` (docs/31 §4.1.1): `at` indexes `ids`, which may repeat.
#[derive(uniffi::Record, Clone, PartialEq, Eq)]
pub struct ReportListFfi {
    pub ids: Vec<String>,
    pub at: u32,
}

impl From<ReportFactsFfi> for jellyfin_core::ReportFacts {
    fn from(facts: ReportFactsFfi) -> Self {
        match facts {
            ReportFactsFfi::Video {
                audio,
                subtitle,
                list,
            } => Self::Video {
                audio,
                subtitle,
                list: list.map(|list| jellyfin_core::ReportList {
                    ids: list.ids.into(),
                    at: list.at as usize,
                }),
            },
            ReportFactsFfi::Audio { repeat, shuffle } => Self::Audio {
                repeat: repeat.into(),
                shuffle: shuffle.into(),
            },
        }
    }
}

/// docs/31 §4.4: a reporting session's volume reader over `listener`; a throw reads as full and
/// unmuted, as a report without a listener does.
pub(crate) fn volume_source(
    listener: Arc<dyn RemoteControlListener>,
) -> jellyfin_core::VolumeSource {
    jellyfin_core::VolumeSource(Arc::new(move || match listener.volume() {
        Ok(volume) => jellyfin_core::Volume {
            level: volume.level.min(100),
            muted: volume.muted,
        },
        Err(_) => {
            tracing::debug!("remote control listener threw reading the volume");
            jellyfin_core::Volume {
                level: 100,
                muted: false,
            }
        }
    }))
}

/// Mirrors `jellyfin_api::GeneralCommand` 1:1 (docs/31 §4.3-§4.5).
#[derive(uniffi::Enum, Clone, PartialEq, Eq)]
pub enum GeneralCommandFfi {
    SetAudioStreamIndex {
        index: i32,
    },
    /// `-1` is off.
    SetSubtitleStreamIndex {
        index: i32,
    },
    DisplayContent {
        item_id: String,
        item_type: String,
    },
    DisplayMessage {
        header: Option<String>,
        text: String,
    },
    SendString {
        text: String,
    },
    /// 0-100.
    SetVolume {
        level: u8,
    },
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
    SetRepeatMode {
        mode: RemoteRepeatFfi,
    },
    SetShuffle {
        mode: RemoteShuffleFfi,
    },
}

impl From<GeneralCommand> for GeneralCommandFfi {
    fn from(command: GeneralCommand) -> Self {
        use GeneralCommand as G;
        match command {
            G::SetAudioStreamIndex(index) => Self::SetAudioStreamIndex { index },
            G::SetSubtitleStreamIndex(index) => Self::SetSubtitleStreamIndex { index },
            // docs/31 §4.3: the mirror keys views and cards by the dashed spelling.
            G::DisplayContent { item_id, item_type } => Self::DisplayContent {
                item_id: crate::object::canonicalize_item_id(&item_id),
                item_type,
            },
            G::DisplayMessage { header, text } => Self::DisplayMessage { header, text },
            G::SendString(text) => Self::SendString { text },
            G::SetVolume(level) => Self::SetVolume { level },
            G::VolumeUp => Self::VolumeUp,
            G::VolumeDown => Self::VolumeDown,
            G::Mute => Self::Mute,
            G::Unmute => Self::Unmute,
            G::ToggleMute => Self::ToggleMute,
            G::MoveUp => Self::MoveUp,
            G::MoveDown => Self::MoveDown,
            G::MoveLeft => Self::MoveLeft,
            G::MoveRight => Self::MoveRight,
            G::Select => Self::Select,
            G::Back => Self::Back,
            G::PageUp => Self::PageUp,
            G::PageDown => Self::PageDown,
            G::PreviousLetter => Self::PreviousLetter,
            G::NextLetter => Self::NextLetter,
            G::GoHome => Self::GoHome,
            G::GoToSearch => Self::GoToSearch,
            G::GoToSettings => Self::GoToSettings,
            G::ToggleContextMenu => Self::ToggleContextMenu,
            G::ToggleOsd => Self::ToggleOsd,
            G::ToggleOsdMenu => Self::ToggleOsdMenu,
            G::ToggleFullscreen => Self::ToggleFullscreen,
            G::ToggleStats => Self::ToggleStats,
            G::SetRepeatMode(mode) => Self::SetRepeatMode { mode: mode.into() },
            G::SetShuffle(mode) => Self::SetShuffle { mode: mode.into() },
        }
    }
}

/// A command on its way to the listener: ready at once, or once its Play is enriched.
pub(crate) type Delivery = BoxFuture<'static, Option<RemoteCommandFfi>>;

/// docs/31 §4.6, §8: what one Remote frame from the bus `bus` becomes: `None` (one diag line) while
/// `input`'s setting is off, for an unsupported command, or for a Play with no installed mirror;
/// otherwise its delivery, a Play's after [`enrich_play`].
pub(crate) fn admit_remote(
    command: RemoteCommand,
    input: CapsInput,
    state: &Weak<SharedState>,
    bus: &BusWatch,
    client: &jellyfin_api::JellyfinClient,
) -> Option<Delivery> {
    if !input.enabled {
        tracing::debug!("remote command dropped: remote control is off");
        return None;
    }
    let command = match command {
        RemoteCommand::Unsupported(name) => {
            tracing::warn!(kind = %name, "remote command unsupported; ignored");
            return None;
        }
        RemoteCommand::Play(request) => {
            let mirror = state
                .upgrade()
                .and_then(|shared| JellybeamCore::installed_bus_mirror_locked(&shared.lock(), bus));
            let Some(mirror) = mirror else {
                tracing::warn!("remote play dropped: the bus's mirror is not installed");
                return None;
            };
            return Some(enrich_play(client.clone(), mirror, request).boxed());
        }
        RemoteCommand::Playstate(command) => RemoteCommandFfi::Playstate {
            command: command.into(),
        },
        RemoteCommand::General(command) => RemoteCommandFfi::General {
            command: command.into(),
        },
    };
    Some(futures_util::future::ready(Some(command)).boxed())
}

/// docs/31 §6, §8: hands `command` to the registered listener tagged with `epoch`, only while the
/// setting is on and that account is still installed, both read under one lock at delivery so a
/// command admitted before the setting went off is dropped; a throw is logged and the next goes out.
pub(crate) fn deliver_remote(state: &Weak<SharedState>, epoch: u64, command: RemoteCommandFfi) {
    let Some(shared) = state.upgrade() else {
        return;
    };
    let (enabled, listener) = {
        let mut guard = shared.lock();
        let enabled = guard.remote_caps.borrow().enabled;
        let current = guard.account.installed_mut(epoch).is_some();
        (enabled, current.then(|| guard.remote_listener.clone()))
    };
    drop(shared);
    if !enabled {
        tracing::debug!("remote command dropped: remote control is off");
        return;
    }
    let listener = match listener {
        None => {
            tracing::warn!("remote command dropped: stale account");
            return;
        }
        Some(None) => {
            tracing::warn!("remote command dropped: no listener registered");
            return;
        }
        Some(Some(listener)) => listener,
    };
    if listener.on_command(epoch, command).is_err() {
        tracing::warn!("remote control listener threw; the next command still goes out");
    }
}

/// docs/31 §4.1: the most songs a remote queue holds and one expansion reads; Kotlin's
/// `QUEUE_FETCH_LIMIT` is pinned equal to this line by a Kotlin test.
pub(crate) const QUEUE_FETCH_LIMIT: u32 = 2_000;

/// docs/31 §4.1: an artist's songs as a queue, release year ascending; Kotlin's
/// `ARTIST_QUEUE_SORT` is pinned equal to this line by a Kotlin test.
pub(crate) const ARTIST_QUEUE_SORT: GridSort = GridSort {
    field: GridSortField::Year,
    descending: false,
};

/// The kinds a Play hands the video player, whether named or a collection's members.
const VIDEO_KINDS: [jellyfin_api::models::BaseItemKind; 4] = {
    use jellyfin_api::models::BaseItemKind as K;
    [K::Movie, K::Episode, K::Video, K::MusicVideo]
};

/// What one requested item is to a Play, read from its DTO.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum ItemClass {
    Video,
    Series,
    Season,
    BoxSet,
    Song,
    Album,
    Artist,
    Playlist,
    Unplayable,
}

impl ItemClass {
    fn of(dto: &BaseItemDto) -> Self {
        use jellyfin_api::models::{BaseItemKind as K, MediaType};
        match dto.type_ {
            Some(kind) if VIDEO_KINDS.contains(&kind) => Self::Video,
            Some(K::Series) => Self::Series,
            Some(K::Season) => Self::Season,
            Some(K::BoxSet) => Self::BoxSet,
            Some(K::Audio) => Self::Song,
            Some(K::MusicAlbum) => Self::Album,
            Some(K::MusicArtist) => Self::Artist,
            // Assumption: a playlist holds music unless it says it holds video.
            Some(K::Playlist) if dto.media_type != Some(MediaType::Video) => Self::Playlist,
            _ => Self::Unplayable,
        }
    }

    fn plays_music(self) -> bool {
        matches!(
            self,
            Self::Song | Self::Album | Self::Artist | Self::Playlist
        )
    }

    /// docs/31 §4.1: the Instant Mix seed typed by this kind; `None` for a kind with no mix.
    fn mix_seed(self, id: String) -> Option<MixSeedFfi> {
        match self {
            Self::Song => Some(MixSeedFfi::Song { id }),
            Self::Album => Some(MixSeedFfi::Album { id }),
            Self::Artist => Some(MixSeedFfi::Artist { id }),
            Self::Playlist => Some(MixSeedFfi::Playlist { id }),
            _ => None,
        }
    }
}

/// One requested id after resolution: what it plays (empty when nothing), or skipped as
/// unplayable or of the other family.
#[derive(Debug, Clone, PartialEq)]
enum Resolved {
    Video(Vec<String>),
    Songs(Vec<Card>),
    Mix(MixSeedFfi),
    Skipped,
}

impl Resolved {
    fn plays_something(&self) -> bool {
        match self {
            Self::Video(ids) => !ids.is_empty(),
            Self::Songs(songs) => !songs.is_empty(),
            Self::Mix(_) => true,
            Self::Skipped => false,
        }
    }
}

/// docs/31 §4.1: the Play's ids classified (installed mirror first, one targeted lookup per miss
/// carrying the live song fields), then each item of the start item's family resolved; an
/// Instant Mix resolves only its seed. `None`, with one diag line, when the start item plays nothing.
async fn enrich_play(
    client: jellyfin_api::JellyfinClient,
    mirror: Mirror,
    request: PlayRequest,
) -> Option<RemoteCommandFfi> {
    let fields = live_children_fields();
    let mut items = Vec::with_capacity(request.item_ids.len());
    for id in request.item_ids.iter().map(|id| canonicalize_item_id(id)) {
        let dto = JellybeamCore::fetch_item_dto_async(&client, &mirror, &id, &fields)
            .await
            .ok();
        items.push((id, dto));
    }
    let start = start_slot(&request, items.len());
    let music = items
        .get(start)
        .and_then(|(_, dto)| dto.as_ref())
        .is_some_and(|dto| ItemClass::of(dto).plays_music());
    let mix = music && request.play_command == PlayCommand::PlayInstantMix;
    let now = Utc::now();
    let mut resolved = vec![Resolved::Skipped; items.len()];
    // The start slot first: a Play whose start plays nothing expands none of the others.
    let order = std::iter::once(start).chain((0..items.len()).filter(|&index| index != start));
    for index in order.take(items.len()) {
        let (id, dto) = std::mem::take(&mut items[index]);
        let entry = match dto {
            Some(dto) if mix && index == start => ItemClass::of(&dto)
                .mix_seed(id)
                .map_or(Resolved::Skipped, Resolved::Mix),
            Some(dto) if !mix && ItemClass::of(&dto).plays_music() == music => {
                resolve(&client, &mirror, id, &dto, now).await
            }
            _ => Resolved::Skipped,
        };
        if index == start && !entry.plays_something() {
            break;
        }
        resolved[index] = entry;
    }
    let play = assemble(&request, resolved);
    if play.is_none() {
        tracing::warn!("remote play dropped: its start item plays nothing");
    }
    play.map(|play| RemoteCommandFfi::Play { play })
}

/// docs/31 §4.1: what one item plays. A Series or Season plays its docs/11 item 11 Play pick, a
/// BoxSet its video members in collection order (nested series skipped); a song plays itself, an
/// album, artist or playlist its songs. A Season, BoxSet, album or artist the mirror lacks costs
/// one live request; a Series it lacks plays nothing, since its pick needs mirrored watch state.
async fn resolve(
    client: &jellyfin_api::JellyfinClient,
    mirror: &Mirror,
    id: String,
    dto: &BaseItemDto,
    now: DateTime<Utc>,
) -> Resolved {
    let audio = |query: ItemQuery| ItemQuery {
        include_item_types: vec!["Audio".to_string()],
        recursive: true,
        limit: QUEUE_FETCH_LIMIT,
        ..query
    };
    match ItemClass::of(dto) {
        ItemClass::Video => Resolved::Video(vec![id]),
        ItemClass::Unplayable => Resolved::Skipped,
        ItemClass::Song => Resolved::Songs(Card::try_from(dto).into_iter().collect()),
        ItemClass::Series => {
            let owned = mirror.clone();
            let summary =
                tokio::task::spawn_blocking(move || series_summary(&owned, &id, false, now))
                    .await
                    .ok()
                    .flatten()
                    .filter(|summary| !summary.seasons.is_empty());
            let Some(summary) = summary else {
                return Resolved::Skipped;
            };
            Resolved::Video(
                summary
                    .target
                    .map(|t| vec![t.episode.id])
                    .unwrap_or_default(),
            )
        }
        ItemClass::Season => {
            let episodes =
                video_children(client, mirror, id, Some("ParentIndexNumber,IndexNumber"));
            Resolved::Video(season_pick(&episodes.await, now))
        }
        ItemClass::BoxSet => Resolved::Video(
            video_children(client, mirror, id, None)
                .await
                .into_iter()
                .filter(|card| {
                    VIDEO_KINDS
                        .iter()
                        .any(|kind| kind.to_string() == card.item_type)
                })
                .map(|card| card.id)
                .collect(),
        ),
        ItemClass::Album => {
            let live = audio(ItemQuery {
                parent_id: Some(id.clone()),
                sort_by: Some("ParentIndexNumber,IndexNumber".to_string()),
                ..ItemQuery::new()
            });
            let read = move |m: &Mirror| m.album_tracks(&id, 0, QUEUE_FETCH_LIMIT).ok();
            Resolved::Songs(mirrored_or_live(client, mirror, read, live).await)
        }
        ItemClass::Artist => {
            let live = audio(ItemQuery {
                artist_ids: vec![id.clone()],
                sort_by: Some("ProductionYear,Name".to_string()),
                ..ItemQuery::new()
            });
            let source = SongSource::Artist {
                artist_id: id,
                sort: ARTIST_QUEUE_SORT,
            };
            let read = move |m: &Mirror| m.music_songs(&source, 0, QUEUE_FETCH_LIMIT).ok();
            Resolved::Songs(mirrored_or_live(client, mirror, read, live).await)
        }
        ItemClass::Playlist => {
            let fields = live_children_fields();
            let shape = PlaylistItemsShape::Full;
            let page = client.get_playlist_items(&id, 0, QUEUE_FETCH_LIMIT, &fields, shape);
            Resolved::Songs(live_songs(page.await))
        }
    }
}

/// A Season's or BoxSet's mirrored children by index, or, when the mirror has none, its video
/// children from one live request in `sort_by` order (`None` is the server's collection order).
async fn video_children(
    client: &jellyfin_api::JellyfinClient,
    mirror: &Mirror,
    parent: String,
    sort_by: Option<&str>,
) -> Vec<Card> {
    let live = ItemQuery {
        parent_id: Some(parent.clone()),
        include_item_types: VIDEO_KINDS.iter().map(ToString::to_string).collect(),
        sort_by: sort_by.map(str::to_string),
        // The server's `limit` is an `i32`; a collection's members are never cut like a queue.
        limit: i32::MAX as u32,
        ..ItemQuery::new()
    };
    let read = move |m: &Mirror| m.children_checked(&parent, Sort::IndexNumber, 0, u32::MAX);
    mirrored_or_live(client, mirror, read, live).await
}

/// `read`'s mirrored rows, or, when it finds none, `live`'s items from one request carrying the
/// live song fields; a failed request is one diag line and no items.
async fn mirrored_or_live(
    client: &jellyfin_api::JellyfinClient,
    mirror: &Mirror,
    read: impl FnOnce(&Mirror) -> Option<Vec<CardRow>> + Send + 'static,
    live: ItemQuery,
) -> Vec<Card> {
    let owned = mirror.clone();
    let rows = tokio::task::spawn_blocking(move || read(&owned))
        .await
        .ok()
        .flatten()
        .unwrap_or_default();
    if !rows.is_empty() {
        return rows.into_iter().map(Card::from).collect();
    }
    let query = ItemQuery {
        fields: live_children_fields(),
        ..live
    };
    live_songs(client.get_items(&query).await)
}

fn live_songs(
    page: Result<jellyfin_api::models::ItemsResult, jellyfin_api::ApiError>,
) -> Vec<Card> {
    page.map(|page| live_cards(&page.items))
        .unwrap_or_else(|error| {
            tracing::warn!(error = %error, "remote play: a live expansion failed");
            Vec::new()
        })
}

/// docs/11 item 11: a Season's Play pick over its episodes in index order; empty when none.
fn season_pick(episodes: &[Card], now: DateTime<Utc>) -> Vec<String> {
    let facts: Vec<EpisodeFacts> = episodes
        .iter()
        .map(|episode| EpisodeFacts {
            is_virtual: episode.is_virtual,
            special: episode.parent_index_number == Some(0),
            position_ticks: episode.position_ticks,
            played: episode.played,
            missing: episode.is_virtual && is_missing(episode.premiere_date.as_deref(), now),
        })
        .collect();
    pick_series_play(&facts, false)
        .and_then(|(i, _)| episodes.get(i))
        .map(|episode| vec![episode.id.clone()])
        .unwrap_or_default()
}

/// The request's start slot; an out-of-range start index starts at the first item.
fn start_slot(request: &PlayRequest, len: usize) -> usize {
    request
        .start_index
        .and_then(|index| usize::try_from(index).ok())
        .filter(|&index| index < len)
        .unwrap_or(0)
}

/// docs/31 §4.1: the start item's family keeps that family's entries in request order, the
/// others are dropped; the start index lands on the start item's first id or song. `None` when
/// the start item plays nothing.
fn assemble(request: &PlayRequest, resolved: Vec<Resolved>) -> Option<RemotePlay> {
    let start = start_slot(request, resolved.len());
    let first = resolved
        .get(start)
        .filter(|entry| entry.plays_something())?;
    let (items, start_index) = match first {
        Resolved::Mix(seed) => (RemoteItems::Mix { seed: seed.clone() }, 0),
        Resolved::Video(_) => {
            let groups: Vec<Vec<String>> = resolved
                .into_iter()
                .map(|entry| match entry {
                    Resolved::Video(ids) => ids,
                    _ => Vec::new(),
                })
                .collect();
            let at = groups[..start].iter().map(Vec::len).sum();
            let ids = groups.into_iter().flatten().collect();
            (RemoteItems::Video { ids }, at)
        }
        Resolved::Songs(_) => {
            let groups = resolved
                .into_iter()
                .map(|entry| match entry {
                    Resolved::Songs(songs) => songs,
                    _ => Vec::new(),
                })
                .collect();
            let (songs, at) = songs_window(groups, start);
            (RemoteItems::Songs { songs }, at)
        }
        _ => return None,
    };
    Some(RemotePlay {
        items,
        start_index: u32::try_from(start_index).unwrap_or(0),
        start_position_ticks: request.start_position_ticks,
        play_command: request.play_command.into(),
        media_source_id: request.media_source_id.clone(),
        audio_stream_index: request.audio_stream_index,
        subtitle_stream_index: request.subtitle_stream_index,
    })
}

/// docs/31 §4.1: `groups` flattened in order, cut to a [`QUEUE_FETCH_LIMIT`] window that holds
/// the first song of group `start`, starting there unless too near the end; returns the window
/// and that song's index in it.
fn songs_window(groups: Vec<Vec<Card>>, start: usize) -> (Vec<Card>, usize) {
    let at: usize = groups[..start].iter().map(Vec::len).sum();
    let all: Vec<Card> = groups.into_iter().flatten().collect();
    let limit = QUEUE_FETCH_LIMIT as usize;
    let from = at.min(all.len().saturating_sub(limit));
    (all.into_iter().skip(from).take(limit).collect(), at - from)
}

#[uniffi::export]
impl JellybeamCore {
    /// docs/31 §9: registers (or replaces) the one listener remote commands go to; until one is
    /// registered, commands are dropped with a diag line.
    pub fn set_remote_control_listener(&self, listener: Arc<dyn RemoteControlListener>) {
        self.lock_state().remote_listener = Some(listener);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::object::tests::{remote_enrich_fixture, RouteMockServer};

    const FIXED: RemoteControl = RemoteControl::FixedVolume;

    /// docs/31 §4.3: the server sends a compact id, the mirror's views and cards are keyed dashed.
    #[test]
    fn display_content_names_its_item_in_the_mirrors_spelling() {
        let command = GeneralCommand::DisplayContent {
            item_id: "00112233-4455-6677-8899-aabbccddeeff".replace('-', ""),
            item_type: "CollectionFolder".into(),
        };
        let GeneralCommandFfi::DisplayContent { item_id, .. } = GeneralCommandFfi::from(command)
        else {
            panic!("a DisplayContent stays one");
        };
        assert_eq!(item_id, "00112233-4455-6677-8899-aabbccddeeff");
    }

    const ADJUSTABLE: RemoteControl = RemoteControl::AdjustableVolume;

    fn owner() -> Option<TokenOwner> {
        Some(TokenOwner {
            server_url: "http://server-a.test".to_string(),
            user_id: "00000000-0000-4000-8000-000000000001".to_string(),
        })
    }

    fn alphabet() -> Vec<CapsEvent> {
        vec![
            CapsEvent::Connected { want: Some(FIXED) },
            CapsEvent::Connected {
                want: Some(RemoteControl::Off),
            },
            CapsEvent::Connected { want: None },
            CapsEvent::ConnectedTaken,
            CapsEvent::Disconnected,
            CapsEvent::Unauthorized(owner()),
            CapsEvent::Want(None),
            CapsEvent::Want(Some(FIXED)),
            CapsEvent::Want(Some(ADJUSTABLE)),
            CapsEvent::Posted(FIXED),
            CapsEvent::PostFailed,
            CapsEvent::PostRejected(owner()),
        ]
    }

    /// docs/31 §2, §3 over every sequence of up to four events: a post only from Up, every
    /// installed connect posts its known want and an unprobed one posts nothing, a known want on
    /// an unheld link posts, at most one ask, Dead is terminal and silent, an equal or unprobed
    /// want acts not, a failed post only logs.
    #[test]
    fn caps_link_sequences_up_to_four_keep_its_invariants() {
        let alphabet = alphabet();
        let n = alphabet.len();
        let mut failures = Vec::new();
        let mut sequences = 0;
        for len in 1..=4u32 {
            for code in 0..n.pow(len) {
                sequences += 1;
                let seq: Vec<CapsEvent> = (0..len)
                    .map(|i| alphabet[code / n.pow(i) % n].clone())
                    .collect();
                let mut link = CapsLink::Down;
                let mut asks = 0;
                for (step, event) in seq.iter().enumerate() {
                    let before = link;
                    let effect = link.step(event.clone());
                    let at = format!("{seq:?} step {step}");
                    if matches!(effect, Some(CapsEffect::Post(_)))
                        && !matches!(link, CapsLink::Up { .. })
                    {
                        failures.push(format!("{at}: posted while {link:?}"));
                    }
                    if let (CapsLink::Down | CapsLink::Up { .. }, CapsEvent::Connected { want }) =
                        (before, event)
                    {
                        if effect != want.map(CapsEffect::Post)
                            || link != (CapsLink::Up { holds: None })
                        {
                            failures.push(format!("{at}: connect gave {link:?} {effect:?}"));
                        }
                    }
                    if matches!(effect, Some(CapsEffect::AskReauth(_))) {
                        asks += 1;
                    }
                    if asks > 1 {
                        failures.push(format!("{at}: asked twice"));
                    }
                    if before == CapsLink::Dead && (link != CapsLink::Dead || effect.is_some()) {
                        failures.push(format!("{at}: Dead left or acted: {link:?} {effect:?}"));
                    }
                    if let (CapsLink::Up { holds: Some(held) }, CapsEvent::Want(Some(want))) =
                        (before, event)
                    {
                        if held == *want && (effect.is_some() || link != before) {
                            failures.push(format!("{at}: an equal want acted"));
                        }
                    }
                    if *event == CapsEvent::Want(None) && (effect.is_some() || link != before) {
                        failures.push(format!("{at}: an unprobed want acted"));
                    }
                    if let (CapsLink::Up { holds: None }, CapsEvent::Want(Some(want))) =
                        (before, event)
                    {
                        if effect != Some(CapsEffect::Post(*want)) {
                            failures
                                .push(format!("{at}: a want on an unheld link gave {effect:?}"));
                        }
                    }
                    if *event == CapsEvent::PostFailed
                        && (link != before || !matches!(effect, None | Some(CapsEffect::Diag)))
                    {
                        failures.push(format!("{at}: a failed post did more than log"));
                    }
                }
            }
        }
        assert_eq!(sequences, n + n.pow(2) + n.pow(3) + n.pow(4));
        assert!(
            failures.is_empty(),
            "{} failures, first: {:?}",
            failures.len(),
            failures.first()
        );
    }

    /// docs/31 §3: the table's rows, state x event -> next state, effect.
    #[test]
    fn caps_link_follows_its_table() {
        use CapsEffect as F;
        use CapsEvent as E;
        use CapsLink::{Dead, Down};
        let up = |holds| CapsLink::Up { holds };
        let rows: Vec<(CapsLink, E, CapsLink, Option<F>)> = vec![
            (
                Down,
                E::Connected { want: Some(FIXED) },
                up(None),
                Some(F::Post(FIXED)),
            ),
            (Down, E::Connected { want: None }, up(None), None),
            (up(Some(FIXED)), E::Connected { want: None }, up(None), None),
            (
                up(None),
                E::Connected {
                    want: Some(RemoteControl::Off),
                },
                up(None),
                Some(F::Post(RemoteControl::Off)),
            ),
            (Down, E::ConnectedTaken, Down, None),
            (Down, E::Disconnected, Down, None),
            (
                Down,
                E::Unauthorized(owner()),
                Dead,
                Some(F::AskReauth(owner())),
            ),
            (Down, E::Want(Some(FIXED)), Down, None),
            (Down, E::Posted(FIXED), Down, None),
            (Down, E::PostFailed, Down, None),
            (Down, E::PostRejected(owner()), Down, None),
            (
                up(Some(FIXED)),
                E::Connected {
                    want: Some(ADJUSTABLE),
                },
                up(None),
                Some(F::Post(ADJUSTABLE)),
            ),
            (up(Some(FIXED)), E::ConnectedTaken, Down, None),
            (up(Some(FIXED)), E::Disconnected, Down, None),
            (
                up(None),
                E::Unauthorized(owner()),
                Dead,
                Some(F::AskReauth(owner())),
            ),
            (up(Some(FIXED)), E::Want(Some(FIXED)), up(Some(FIXED)), None),
            (
                up(Some(FIXED)),
                E::Want(Some(ADJUSTABLE)),
                up(Some(FIXED)),
                Some(F::Post(ADJUSTABLE)),
            ),
            (
                up(None),
                E::Want(Some(FIXED)),
                up(None),
                Some(F::Post(FIXED)),
            ),
            (Down, E::Want(None), Down, None),
            (up(None), E::Want(None), up(None), None),
            (up(Some(FIXED)), E::Want(None), up(Some(FIXED)), None),
            (
                up(None),
                E::Want(Some(RemoteControl::Off)),
                up(None),
                Some(F::Post(RemoteControl::Off)),
            ),
            (up(None), E::Posted(ADJUSTABLE), up(Some(ADJUSTABLE)), None),
            (
                up(Some(FIXED)),
                E::PostFailed,
                up(Some(FIXED)),
                Some(F::Diag),
            ),
            (
                up(None),
                E::PostRejected(owner()),
                Dead,
                Some(F::AskReauth(owner())),
            ),
            (Dead, E::Connected { want: Some(FIXED) }, Dead, None),
            (Dead, E::Unauthorized(owner()), Dead, None),
            (Dead, E::PostRejected(owner()), Dead, None),
            (Dead, E::Want(Some(ADJUSTABLE)), Dead, None),
        ];
        for (from, event, to, effect) in rows {
            let mut link = from;
            let got = link.step(event.clone());
            assert_eq!((link, got), (to, effect), "{from:?} + {event:?}");
        }
    }

    fn request(ids: &[&str], start_index: Option<u32>) -> PlayRequest {
        PlayRequest {
            item_ids: ids.iter().map(|id| id.to_string()).collect(),
            start_index,
            start_position_ticks: Some(600_000_000),
            play_command: PlayCommand::PlayNow,
            media_source_id: None,
            audio_stream_index: Some(2),
            subtitle_stream_index: Some(-1),
        }
    }

    fn video(ids: &[&str]) -> Resolved {
        Resolved::Video(ids.iter().map(|id| id.to_string()).collect())
    }

    fn card(id: &str) -> Card {
        let dto: BaseItemDto = serde_json::from_value(serde_json::json!({
            "Id": "00000000-0000-4000-8000-000000000001", "Type": "Audio"
        }))
        .expect("dto");
        Card {
            id: id.to_string(),
            ..Card::try_from(&dto).expect("card")
        }
    }

    fn songs(ids: &[&str]) -> Resolved {
        Resolved::Songs(ids.iter().map(|id| card(id)).collect())
    }

    /// What a test reads of a Play's items: the family, its ids or the seed, and the start index.
    fn shape(play: &RemotePlay) -> (&'static str, Vec<String>, u32) {
        let (family, ids) = match &play.items {
            RemoteItems::Video { ids } => ("video", ids.clone()),
            RemoteItems::Songs { songs } => ("songs", songs.iter().map(|s| s.id.clone()).collect()),
            RemoteItems::Mix { seed } => ("mix", vec![format!("{seed:?}")]),
        };
        (family, ids, play.start_index)
    }

    /// docs/31 §4.1: the start item's family decides, its entries keep request order, the start
    /// index lands on the start item's first id or song.
    #[test]
    fn a_play_resolves_to_its_start_items_family() {
        type Row = (
            &'static str,
            Vec<Resolved>,
            Option<u32>,
            Option<(&'static str, Vec<&'static str>, u32)>,
        );
        let mix = MixSeedFfi::Album { id: "album".into() };
        let rows: Vec<Row> = vec![
            (
                "one movie",
                vec![video(&["m"])],
                None,
                Some(("video", vec!["m"], 0)),
            ),
            (
                "an episodes list started at the second",
                vec![video(&["e1"]), video(&["e2"]), video(&["e3"])],
                Some(1),
                Some(("video", vec!["e1", "e2", "e3"], 1)),
            ),
            (
                "a series with nothing to play",
                vec![video(&[])],
                None,
                None,
            ),
            (
                "an album's songs",
                vec![songs(&["t1", "t2"])],
                None,
                Some(("songs", vec!["t1", "t2"], 0)),
            ),
            (
                "a box set expands in order before the start",
                vec![video(&["b1", "b2"]), video(&["m"])],
                Some(1),
                Some(("video", vec!["b1", "b2", "m"], 2)),
            ),
            (
                "mixed, video start: songs dropped",
                vec![
                    songs(&["s"]),
                    video(&["m"]),
                    Resolved::Skipped,
                    video(&["e"]),
                ],
                Some(1),
                Some(("video", vec!["m", "e"], 0)),
            ),
            (
                "mixed, music start: video dropped, start on its first song",
                vec![video(&["m"]), songs(&["a1", "a2"]), songs(&["p1"])],
                Some(2),
                Some(("songs", vec!["a1", "a2", "p1"], 2)),
            ),
            (
                "an empty album start",
                vec![songs(&[]), songs(&["s"])],
                None,
                None,
            ),
            (
                "a mix seed plays alone",
                vec![Resolved::Mix(mix.clone()), Resolved::Skipped],
                None,
                Some(("mix", vec![format!("{mix:?}").leak()], 0)),
            ),
            (
                "an unplayable start",
                vec![Resolved::Skipped, video(&["m"])],
                None,
                None,
            ),
            (
                "an out-of-range start begins at the first",
                vec![video(&["m1"]), video(&["m2"])],
                Some(9),
                Some(("video", vec!["m1", "m2"], 0)),
            ),
        ];
        for (row, resolved, start, expected) in rows {
            let ids: Vec<&str> = (0..resolved.len()).map(|_| "x").collect();
            let request = request(&ids, start);
            let got = assemble(&request, resolved).map(|play| {
                assert_eq!(play.start_position_ticks, Some(600_000_000), "{row}");
                assert_eq!(play.audio_stream_index, Some(2), "{row}");
                assert_eq!(play.subtitle_stream_index, Some(-1), "{row}");
                assert_eq!(play.play_command, PlayCommandFfi::PlayNow, "{row}");
                shape(&play)
            });
            let expected = expected.map(|(family, ids, start)| {
                (
                    family,
                    ids.into_iter().map(String::from).collect::<Vec<_>>(),
                    start,
                )
            });
            assert_eq!(got, expected, "{row}");
        }
    }

    /// docs/31 §4.1: a queue past the limit keeps the start item's first song in its window,
    /// starting there; a short one is whole.
    #[test]
    fn a_remote_queue_past_the_limit_keeps_its_start_song_in_the_window() {
        let limit = QUEUE_FETCH_LIMIT as usize;
        let before: Vec<Card> = (0..limit).map(|i| card(&format!("a{i}"))).collect();
        let (window, at) = songs_window(vec![before, vec![card("s"), card("t")]], 1);
        assert_eq!(window.len(), limit);
        assert_eq!(window.get(at).map(|c| c.id.as_str()), Some("s"));
        assert_eq!(window.last().map(|c| c.id.as_str()), Some("t"));
        let after: Vec<Card> = (0..limit + 5).map(|i| card(&format!("z{i}"))).collect();
        let (window, at) = songs_window(vec![vec![card("a")], vec![card("s")], after], 1);
        assert_eq!(
            (window.len(), at),
            (limit, 0),
            "the window starts at the start song"
        );
        assert_eq!(window[0].id, "s");
        let (short, at) = songs_window(vec![vec![card("a")], vec![card("s")]], 1);
        let ids: Vec<&str> = short.iter().map(|c| c.id.as_str()).collect();
        assert_eq!((ids, at), (vec!["a", "s"], 1));
    }

    const ALBUM: &str = "00000000-0000-4000-8000-00000000a1b0";
    const ARTIST: &str = "00000000-0000-4000-8000-00000000a2b0";
    const SONG: &str = "00000000-0000-4000-8000-00000000a3b0";
    const PLAYLIST: &str = "00000000-0000-4000-8000-00000000a4b0";
    const BOXSET: &str = "00000000-0000-4000-8000-00000000a5b0";
    const SEASON: &str = "00000000-0000-4000-8000-00000000a6b0";
    const SERIES: &str = "00000000-0000-4000-8000-00000000a7b0";

    fn track(n: u32, disc: i32, index: i32) -> serde_json::Value {
        serde_json::json!({
            "Id": format!("00000000-0000-4000-8000-0000000{n:05}"), "Name": format!("Track {n}"),
            "Type": "Audio", "Album": "Synthetic Album", "AlbumId": ALBUM,
            "AlbumArtist": "Synthetic Artist", "ParentIndexNumber": disc, "IndexNumber": index,
            "ArtistItems": [{ "Name": "Synthetic Artist", "Id": ARTIST }]
        })
    }

    fn page(items: Vec<serde_json::Value>) -> serde_json::Value {
        let total = items.len();
        serde_json::json!({ "Items": items, "TotalRecordCount": total })
    }

    /// Serves `id`'s DTO to the classification lookup (`ids=`), which misses the empty mirror.
    fn serve_item(mock: &RouteMockServer, id: &str, dto: serde_json::Value) {
        mock.route_with_query("GET", "/Items", &format!("ids={id}"), 200, &page(vec![dto]));
    }

    /// Runs one Play's enrichment against `mock` over an empty mirror.
    fn enrich(
        fixture: &(
            tempfile::TempDir,
            std::sync::Arc<JellybeamCore>,
            RouteMockServer,
            jellyfin_api::JellyfinClient,
            Mirror,
        ),
        ids: &[&str],
        start: Option<u32>,
        command: PlayCommand,
    ) -> Option<RemotePlay> {
        let (_, core, _, client, mirror) = fixture;
        let play = PlayRequest {
            play_command: command,
            ..request(ids, start)
        };
        match core
            .runtime()
            .block_on(enrich_play(client.clone(), mirror.clone(), play))
        {
            Some(RemoteCommandFfi::Play { play }) => Some(play),
            _ => None,
        }
    }

    fn requests_containing(mock: &RouteMockServer, needle: &str) -> Vec<String> {
        mock.log_snapshot()
            .into_iter()
            .filter(|entry| entry.contains(needle))
            .collect()
    }

    /// docs/31 §4.1, F16: an album missing from the mirror costs exactly one ParentId request,
    /// audio only, recursive, disc then track, with the live song fields; its songs keep that order.
    #[test]
    fn an_album_miss_expands_from_one_parent_request_in_track_order() {
        let fixture = remote_enrich_fixture();
        let mock = &fixture.2;
        serve_item(
            mock,
            ALBUM,
            serde_json::json!({ "Id": ALBUM, "Type": "MusicAlbum" }),
        );
        let tracks = vec![track(1, 1, 1), track(2, 1, 2), track(3, 2, 1)];
        mock.route_with_query(
            "GET",
            "/Items",
            &format!("parentId={ALBUM}"),
            200,
            &page(tracks),
        );

        let play = enrich(&fixture, &[ALBUM], None, PlayCommand::PlayNow).expect("plays");

        let ids: Vec<String> = (1..=3)
            .map(|n| format!("00000000-0000-4000-8000-0000000{n:05}"))
            .collect();
        assert_eq!(shape(&play), ("songs", ids, 0));
        let live = requests_containing(mock, &format!("parentId={ALBUM}"));
        assert_eq!(live.len(), 1, "{:?}", mock.log_snapshot());
        for needle in [
            "includeItemTypes=Audio",
            "recursive=true",
            "sortBy=ParentIndexNumber%2CIndexNumber",
            "fields=Overview",
            "limit=2000",
        ] {
            assert!(live[0].contains(needle), "{needle} in {}", live[0]);
        }
    }

    /// docs/31 §4.1, Q4: an artist missing from the mirror costs exactly one ArtistIds request,
    /// audio only, recursive, release year first; its songs keep the server's order.
    #[test]
    fn an_artist_miss_expands_from_one_artist_ids_request() {
        let fixture = remote_enrich_fixture();
        let mock = &fixture.2;
        serve_item(
            mock,
            ARTIST,
            serde_json::json!({ "Id": ARTIST, "Type": "MusicArtist" }),
        );
        let tracks = vec![track(7, 1, 3), track(5, 1, 1)];
        mock.route_with_query(
            "GET",
            "/Items",
            &format!("artistIds={ARTIST}"),
            200,
            &page(tracks),
        );

        let play = enrich(&fixture, &[ARTIST], None, PlayCommand::PlayNow).expect("plays");

        let ids = vec![
            "00000000-0000-4000-8000-000000000007".to_string(),
            "00000000-0000-4000-8000-000000000005".to_string(),
        ];
        assert_eq!(shape(&play), ("songs", ids, 0));
        let live = requests_containing(mock, &format!("artistIds={ARTIST}"));
        assert_eq!(live.len(), 1, "{:?}", mock.log_snapshot());
        for needle in [
            "includeItemTypes=Audio",
            "recursive=true",
            "sortBy=ProductionYear%2CName",
        ] {
            assert!(live[0].contains(needle), "{needle} in {}", live[0]);
        }
    }

    /// docs/31 §4.1: a song missing from the mirror is one lookup carrying the live song fields,
    /// and its card keeps album, artists, disc and track.
    #[test]
    fn a_song_miss_is_one_lookup_whose_card_keeps_its_music_fields() {
        let fixture = remote_enrich_fixture();
        let mock = &fixture.2;
        let mut song = track(9, 2, 4);
        song["Id"] = SONG.into();
        serve_item(mock, SONG, song);

        let play = enrich(&fixture, &[SONG], None, PlayCommand::PlayNow).expect("plays");

        let RemoteItems::Songs { songs } = &play.items else {
            panic!("a song plays as songs");
        };
        let [card] = songs.as_slice() else {
            panic!("one song: {}", songs.len());
        };
        assert_eq!(card.id, SONG);
        assert_eq!(card.album.as_deref(), Some("Synthetic Album"));
        assert_eq!(card.album_artist.as_deref(), Some("Synthetic Artist"));
        assert_eq!(card.artists, vec!["Synthetic Artist"]);
        assert_eq!(
            (card.parent_index_number, card.index_number),
            (Some(2), Some(4))
        );
        let lookups = requests_containing(mock, &format!("ids={SONG}"));
        assert_eq!(lookups.len(), 1, "{:?}", mock.log_snapshot());
        assert!(lookups[0].contains("fields=Overview"), "{}", lookups[0]);
    }

    /// docs/31 §4.1: an Instant Mix is seeded by the start item, typed by its kind, and expands
    /// nothing.
    #[test]
    fn an_instant_mix_is_seeded_by_its_start_items_kind_and_expands_nothing() {
        let rows = [
            ("Audio", SONG, MixSeedFfi::Song { id: SONG.into() }),
            ("MusicAlbum", ALBUM, MixSeedFfi::Album { id: ALBUM.into() }),
            (
                "MusicArtist",
                ARTIST,
                MixSeedFfi::Artist { id: ARTIST.into() },
            ),
            (
                "Playlist",
                PLAYLIST,
                MixSeedFfi::Playlist {
                    id: PLAYLIST.into(),
                },
            ),
        ];
        for (kind, id, seed) in rows {
            let fixture = remote_enrich_fixture();
            let mock = &fixture.2;
            serve_item(mock, id, serde_json::json!({ "Id": id, "Type": kind }));
            serve_item(mock, SONG, track(1, 1, 1));

            let play =
                enrich(&fixture, &[SONG, id], Some(1), PlayCommand::PlayInstantMix).expect("plays");

            assert!(
                play.items == RemoteItems::Mix { seed: seed.clone() },
                "{kind}: {seed:?}"
            );
            assert_eq!(play.start_index, 0, "{kind}");
            let log = mock.log_snapshot();
            assert_eq!(log.len(), 2, "{kind}: only the two lookups: {log:?}");
        }
    }

    /// docs/31 §4.1: an album with no songs anywhere plays nothing.
    #[test]
    fn an_empty_album_plays_nothing() {
        let fixture = remote_enrich_fixture();
        let mock = &fixture.2;
        serve_item(
            mock,
            ALBUM,
            serde_json::json!({ "Id": ALBUM, "Type": "MusicAlbum" }),
        );

        assert!(enrich(&fixture, &[ALBUM], None, PlayCommand::PlayNow).is_none());
        assert_eq!(
            requests_containing(mock, &format!("parentId={ALBUM}")).len(),
            1
        );
    }

    /// docs/31 §4.1: a playlist plays its live entries in playlist order, after a song before it.
    #[test]
    fn a_playlist_plays_its_live_entries_after_the_song_before_it() {
        let fixture = remote_enrich_fixture();
        let mock = &fixture.2;
        let mut song = track(9, 1, 1);
        song["Id"] = SONG.into();
        serve_item(mock, SONG, song);
        serve_item(
            mock,
            PLAYLIST,
            serde_json::json!({ "Id": PLAYLIST, "Type": "Playlist" }),
        );
        let entries = vec![track(4, 1, 1), track(2, 1, 1)];
        mock.route(
            "GET",
            &format!("/Playlists/{PLAYLIST}/Items"),
            200,
            page(entries),
        );

        let play =
            enrich(&fixture, &[SONG, PLAYLIST], Some(1), PlayCommand::PlayNow).expect("plays");

        let ids = vec![
            SONG.to_string(),
            "00000000-0000-4000-8000-000000000004".to_string(),
            "00000000-0000-4000-8000-000000000002".to_string(),
        ];
        assert_eq!(shape(&play), ("songs", ids, 1));
    }

    /// docs/31 §4.1, Q8: a box set missing from the mirror costs one ParentId request for its
    /// video members in the server's collection order; other members are skipped, and an album
    /// sent beside it is never expanded.
    #[test]
    fn a_box_set_miss_plays_its_video_members_from_one_request() {
        let fixture = remote_enrich_fixture();
        let mock = &fixture.2;
        serve_item(
            mock,
            ALBUM,
            serde_json::json!({ "Id": ALBUM, "Type": "MusicAlbum" }),
        );
        serve_item(
            mock,
            BOXSET,
            serde_json::json!({ "Id": BOXSET, "Type": "BoxSet" }),
        );
        let members = vec![
            serde_json::json!({ "Id": "00000000-0000-4000-8000-000000000b02", "Type": "Movie" }),
            serde_json::json!({ "Id": "00000000-0000-4000-8000-000000000b03", "Type": "Series" }),
            serde_json::json!({ "Id": "00000000-0000-4000-8000-000000000b01", "Type": "Movie" }),
        ];
        mock.route_with_query(
            "GET",
            "/Items",
            &format!("parentId={BOXSET}"),
            200,
            &page(members),
        );

        let play =
            enrich(&fixture, &[ALBUM, BOXSET], Some(1), PlayCommand::PlayNow).expect("plays");

        let ids = vec![
            "00000000-0000-4000-8000-000000000b02".to_string(),
            "00000000-0000-4000-8000-000000000b01".to_string(),
        ];
        assert_eq!(shape(&play), ("video", ids, 0));
        let live = requests_containing(mock, &format!("parentId={BOXSET}"));
        assert_eq!(live.len(), 1, "{:?}", mock.log_snapshot());
        assert!(
            live[0].contains("includeItemTypes=Movie%2CEpisode%2CVideo%2CMusicVideo"),
            "{}",
            live[0]
        );
        assert!(!live[0].contains("sortBy"), "collection order: {}", live[0]);
        assert!(requests_containing(mock, &format!("parentId={ALBUM}")).is_empty());
    }

    /// docs/31 §4.1, Q8: a season missing from the mirror plays the docs/11 item 11 pick over its
    /// live episodes, from one request in index order.
    #[test]
    fn a_season_miss_plays_its_pick_from_one_live_request() {
        let fixture = remote_enrich_fixture();
        let mock = &fixture.2;
        serve_item(
            mock,
            SEASON,
            serde_json::json!({ "Id": SEASON, "Type": "Season" }),
        );
        let episode = |n: u32, played: bool| {
            serde_json::json!({
                "Id": format!("00000000-0000-4000-8000-000000000e0{n}"), "Type": "Episode",
                "ParentIndexNumber": 1, "IndexNumber": n, "RunTimeTicks": 600_000_000,
                "UserData": { "Played": played, "PlaybackPositionTicks": 0 }
            })
        };
        let episodes = vec![episode(1, true), episode(2, false), episode(3, false)];
        mock.route_with_query(
            "GET",
            "/Items",
            &format!("parentId={SEASON}"),
            200,
            &page(episodes),
        );

        let play = enrich(&fixture, &[SEASON], None, PlayCommand::PlayNow).expect("plays");

        assert_eq!(
            shape(&play),
            (
                "video",
                vec!["00000000-0000-4000-8000-000000000e02".to_string()],
                0
            )
        );
        let live = requests_containing(mock, &format!("parentId={SEASON}"));
        assert_eq!(live.len(), 1, "{:?}", mock.log_snapshot());
        assert!(
            live[0].contains("sortBy=ParentIndexNumber%2CIndexNumber"),
            "{}",
            live[0]
        );
    }

    /// docs/31 §4.1, Q8: a series missing from the mirror plays nothing and asks for no children.
    #[test]
    fn a_series_miss_plays_nothing() {
        let fixture = remote_enrich_fixture();
        let mock = &fixture.2;
        serve_item(
            mock,
            SERIES,
            serde_json::json!({ "Id": SERIES, "Type": "Series" }),
        );

        assert!(enrich(&fixture, &[SERIES], None, PlayCommand::PlayNow).is_none());
        assert_eq!(
            mock.log_snapshot().len(),
            1,
            "only the lookup: {:?}",
            mock.log_snapshot()
        );
    }

    /// docs/31 §4.1: a collection's video members are not truncated like a music queue.
    #[test]
    fn a_live_video_expansion_carries_no_music_queue_limit() {
        for (id, kind) in [(BOXSET, "BoxSet"), (SEASON, "Season")] {
            let fixture = remote_enrich_fixture();
            let mock = &fixture.2;
            serve_item(mock, id, serde_json::json!({ "Id": id, "Type": kind }));
            mock.route_with_query(
                "GET",
                "/Items",
                &format!("parentId={id}"),
                200,
                &page(vec![serde_json::json!({
                    "Id": "00000000-0000-4000-8000-000000000b01", "Type": "Episode"
                })]),
            );

            assert!(enrich(&fixture, &[id], None, PlayCommand::PlayNow).is_some());

            let live = requests_containing(mock, &format!("parentId={id}"));
            assert_eq!(live.len(), 1, "{kind}: {:?}", mock.log_snapshot());
            assert!(!live[0].contains("limit=2000"), "{kind}: {}", live[0]);
        }
    }

    /// Counts the WARN events this module logs on the thread that installs it.
    struct WarnCounter(std::sync::Arc<std::sync::atomic::AtomicUsize>);

    impl tracing::Subscriber for WarnCounter {
        fn enabled(&self, _: &tracing::Metadata<'_>) -> bool {
            true
        }
        fn new_span(&self, _: &tracing::span::Attributes<'_>) -> tracing::span::Id {
            tracing::span::Id::from_u64(1)
        }
        fn record(&self, _: &tracing::span::Id, _: &tracing::span::Record<'_>) {}
        fn record_follows_from(&self, _: &tracing::span::Id, _: &tracing::span::Id) {}
        fn event(&self, event: &tracing::Event<'_>) {
            let meta = event.metadata();
            if *meta.level() == tracing::Level::WARN && meta.target().ends_with("::remote") {
                self.0.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
            }
        }
        fn enter(&self, _: &tracing::span::Id) {}
        fn exit(&self, _: &tracing::span::Id) {}
    }

    /// docs/31 §4.1: a start item that plays nothing ends the Play with one diag line and no
    /// live expansion of the other ids.
    #[test]
    fn a_start_item_that_plays_nothing_expands_no_other_id_and_logs_once() {
        let fixture = remote_enrich_fixture();
        let mock = &fixture.2;
        for (id, kind) in [(SERIES, "Series"), (SEASON, "Season"), (BOXSET, "BoxSet")] {
            serve_item(mock, id, serde_json::json!({ "Id": id, "Type": kind }));
        }
        let warns = std::sync::Arc::new(std::sync::atomic::AtomicUsize::new(0));

        let play = tracing::subscriber::with_default(WarnCounter(warns.clone()), || {
            tracing::callsite::rebuild_interest_cache();
            enrich(
                &fixture,
                &[SERIES, SEASON, BOXSET],
                None,
                PlayCommand::PlayNow,
            )
        });

        assert!(play.is_none());
        let log = mock.log_snapshot();
        assert_eq!(log.len(), 3, "only the three lookups: {log:?}");
        assert!(requests_containing(mock, "parentId=").is_empty(), "{log:?}");
        assert_eq!(warns.load(std::sync::atomic::Ordering::SeqCst), 1);
    }

    /// docs/31 §4.1: `MusicPlaybackOwnerTest` pins the same two values on the Kotlin side.
    #[test]
    fn the_music_queue_rules_match_the_kotlin_pin() {
        assert_eq!(QUEUE_FETCH_LIMIT, 2_000);
        assert_eq!(
            ARTIST_QUEUE_SORT,
            GridSort {
                field: GridSortField::Year,
                descending: false
            }
        );
    }

    /// docs/31 §4.1: which items a Play plays as video, expands, passes to music, or drops.
    #[test]
    fn item_kinds_classify_for_a_play() {
        use jellyfin_api::models::{BaseItemDto, BaseItemKind as K, MediaType};
        let dto = |kind: K, media: Option<MediaType>| BaseItemDto {
            type_: Some(kind),
            media_type: media,
            ..BaseItemDto::default()
        };
        let rows = [
            (dto(K::Movie, None), ItemClass::Video),
            (dto(K::Episode, None), ItemClass::Video),
            (dto(K::Video, None), ItemClass::Video),
            (dto(K::MusicVideo, None), ItemClass::Video),
            (dto(K::Series, None), ItemClass::Series),
            (dto(K::Season, None), ItemClass::Season),
            (dto(K::BoxSet, None), ItemClass::BoxSet),
            (dto(K::Audio, None), ItemClass::Song),
            (dto(K::MusicAlbum, None), ItemClass::Album),
            (dto(K::MusicArtist, None), ItemClass::Artist),
            (
                dto(K::Playlist, Some(MediaType::Audio)),
                ItemClass::Playlist,
            ),
            (
                dto(K::Playlist, Some(MediaType::Video)),
                ItemClass::Unplayable,
            ),
            (dto(K::Folder, None), ItemClass::Unplayable),
            (BaseItemDto::default(), ItemClass::Unplayable),
        ];
        for (dto, class) in rows {
            assert_eq!(ItemClass::of(&dto), class, "{:?}", dto.type_);
        }
    }
}
