//! Watch Together (SyncPlay) client: one pure [`SyncPlay::step`] from the group state and an
//! [`Event`] to the next state and the [`Effect`]s its owner runs. No I/O and no clock reads: the
//! owner passes [`Now`] and feeds every result back tagged with the request, directive, timer or
//! membership that started it, and `step` drops a stale one before anything else.
//!
//! Units: `*_ms` is server UTC, `*_wall_ms` this device's wall clock, `*_mono_ms` the owner's
//! monotonic clock; positions are Jellyfin ticks (100 ns).

use jellyfin_api::models::{
    GroupInfoDto, GroupQueueMode, GroupStateType, PlayQueueUpdate, PlayQueueUpdateReason,
    SendCommand, SendCommandType, UtcTimeResponse,
};
use jellyfin_api::{GroupUpdate, SyncPlayEvent};
use std::mem::{discriminant, Discriminant};
use uuid::Uuid;

const TICKS_PER_MS: i64 = 10_000;
/// Drift at or past this, either way, is corrected; below it the player is left alone.
pub(crate) const DRIFT_MS: i64 = 400;
/// docs/28 §5: the longest hold for running ahead; further ahead, seek back instead.
pub(crate) const MAX_HOLD_MS: u64 = 5_000;
/// No drift check this long after a landing, an Unpause or a hold: the post-flush audio clock lies.
pub(crate) const LOCKOUT_MS: u64 = 1_500;
/// After a bus reconnect, how long to wait for an unprompted GroupJoined before POSTing Join.
pub(crate) const SNAPSHOT_WINDOW_MS: u64 = 2_000;
/// docs/28 §6: how long after our Ready, with no command since, the Play prompt appears.
pub(crate) const STALL_PROMPT_MS: u64 = 10_000;
/// A stall shorter than this never reaches the server as Buffering.
pub(crate) const BUFFERING_REPORT_MS: u64 = 3_000;
/// docs/28 §4: how long a create or join waits for its GroupJoined before giving up and leaving.
pub(crate) const ENTRY_TIMEOUT_MS: u64 = 10_000;

/// One counter issues every request, directive, timer and membership tag, so each is unique.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct Tag(pub u64);

/// The owner's clocks, read once per step.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Now {
    pub mono_ms: u64,
    pub wall_ms: i64,
}

/// A user name; `Debug` hides it so names never reach logs.
#[derive(Clone, PartialEq, Eq)]
pub struct Name(pub String);

impl std::fmt::Debug for Name {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("Name(..)")
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Reporting {
    Idle,
    Live,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Bus {
    Up,
    Down,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Severity {
    Recoverable,
    Fatal,
}

/// Everything `step` reacts to: server messages, transport, REST results, timers, the player and
/// the local user. `EntryFailed` is a failed New or Join (a rejoin's too); `RequestDone` is any
/// answer to a Leave or Seek POST, success or error. Both are routed by the request's tag.
#[derive(Clone, Debug)]
pub enum Event {
    Server(SyncPlayEvent),
    BusConnected,
    BusDisconnected,
    BusUnauthorized,
    EntryFailed {
        req: Tag,
    },
    RequestDone {
        req: Tag,
    },
    TimeSample(TimeSample),
    Timer(Timer),
    ItemLoaded {
        pid: Uuid,
        landed_ticks: i64,
    },
    SeekLanded {
        seq: Tag,
        landed_ticks: i64,
    },
    BufferingStarted,
    BufferingEnded {
        position_ticks: i64,
    },
    PlaybackError(Severity),
    PlayerEnded,
    PlayerClosed,
    PositionSample {
        position_ticks: i64,
    },
    Create(NewGroup),
    /// `me` is the installed account's user name, which the first GroupJoined lists for us.
    Join {
        group: Uuid,
        bus: Bus,
        me: Name,
    },
    Leave,
    Pause,
    Unpause,
    Seek {
        ticks: i64,
    },
    PickItem(NewQueue),
    Queue(AddToQueue),
    Next,
    Prev,
    AccountReset,
}

/// docs/28 §3: a create request; `name` is the group name we post, and how we know our group.
#[derive(Clone, Debug)]
pub struct NewGroup {
    pub name: RoomName,
    pub reporting: Reporting,
    pub bus: Bus,
}

/// A room name; `Debug` hides it because it carries a user name (docs/28 §3.6).
#[derive(Clone, PartialEq, Eq)]
pub struct RoomName(pub String);

impl std::fmt::Debug for RoomName {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("RoomName(..)")
    }
}

/// One `/GetUtcTime` answer for `membership`, with our wall clock either side of it.
#[derive(Clone, Debug)]
pub struct TimeSample {
    pub membership: Tag,
    pub sent_wall_ms: i64,
    pub received_wall_ms: i64,
    pub response: UtcTimeResponse,
}

/// A whole new play queue: the host's Start, or anyone's pick.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct NewQueue {
    pub items: Vec<Uuid>,
    pub index: i32,
    pub start_ticks: i64,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct AddToQueue {
    pub items: Vec<Uuid>,
    pub mode: GroupQueueMode,
}

/// A timer the owner arms from [`Job::After`] and feeds back as [`Event::Timer`] when it fires.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Timer {
    SnapshotWindow { token: Tag },
    BufferingReport { token: Tag },
    StallPrompt { token: Tag },
    EntryTimeout { token: Tag },
}

/// `Rest`: a REST call; a result that matters comes back as an event carrying `req`. `Dir`: a
/// player directive; the executor skips a `seq` at or below the last one it applied. `Job`: work
/// in the membership's scope. `CancelScope` ends every job of the membership, once per exit from
/// Member. `Notify`: the snapshot changed (`notifies`), so its reader should re-read it.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Effect {
    Rest { req: Tag, call: RestCall },
    Dir { seq: Tag, directive: Directive },
    Ui(Notice),
    Job(Job),
    CancelScope,
    Notify,
}

/// `TimeSync` polls `/GetUtcTime` on [`time_sync_delay_ms`] and feeds each answer back as a
/// TimeSample; `After` arms a timer.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Job {
    TimeSync { membership: Tag },
    After { delay_ms: u64, timer: Timer },
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum RestCall {
    New { name: RoomName },
    Join { group: Uuid },
    Leave,
    Ping { ms: i64 },
    Ready(PlayerReport),
    Buffering(PlayerReport),
    Pause,
    Unpause,
    Seek { ticks: i64 },
    SetNewQueue(NewQueue),
    Queue(AddToQueue),
    NextItem { pid: Uuid },
    PreviousItem { pid: Uuid },
}

/// The body of a Ready or Buffering report; `when_ms` is our estimate of server now.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct PlayerReport {
    pub when_ms: i64,
    pub position_ticks: i64,
    pub is_playing: bool,
    pub pid: Uuid,
}

/// Every position here is an EXACT target: the server re-sends Seek to a member whose Ready is
/// more than 500 ms off, so a keyframe-snapped landing (docs/18 §5.2) would loop forever.
/// `Load` loads paused and reports the landing as ItemLoaded; `Seek` pauses and seeks, `Correct`
/// seeks while playing, both reporting the landing as SeekLanded; `HoldFor` pauses `ms` then
/// plays, the cure for running ahead; `Detach` returns to solo transport with play/pause
/// unchanged; `ReturnToLobby` follows the last item or a group Stop, still a member, and the
/// executor reports no PlayerClosed for it.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Directive {
    Load { entry: Entry, start_ticks: i64 },
    Pause(PauseAt),
    Unpause(PlayAt),
    Seek { position_ticks: i64 },
    Correct { position_ticks: i64 },
    HoldFor { ms: u64 },
    Detach,
    ReturnToLobby,
}

/// Pause at `due_wall_ms`; `None` freezes where the player is, else it seeks there if off.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct PauseAt {
    pub freeze_ticks: Option<i64>,
    pub due_wall_ms: i64,
}

/// At `due_wall_ms`, seek to `position_ticks` if off, then play.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct PlayAt {
    pub position_ticks: i64,
    pub due_wall_ms: i64,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Notice {
    CreateRefused,
    JoinRefused,
    StartFailed,
    NoSuchGroup,
    NoAccess,
    Joined,
    UserJoined(Name),
    UserLeft(Name),
    GroupEnded(EndCause),
    Reconnecting,
    HostLeft,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum EndCause {
    Removed,
    Gone,
    Auth,
    Lost,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum LeaveReason {
    User,
    PlayerError,
    HostLeft,
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub(crate) enum State {
    #[default]
    Idle,
    /// `timeout` is the entry timer's token, as in `Joining`.
    Creating {
        req: Tag,
        name: RoomName,
        timeout: Tag,
    },
    Joining {
        req: Tag,
        group: Uuid,
        timeout: Tag,
        me: Name,
    },
    Member(Box<Member>),
    Leaving(Leaving),
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct Leaving {
    pub req: Tag,
    /// `None` when we left before the server named one.
    pub group: Option<Uuid>,
    pub reason: LeaveReason,
    pub late_join: LateJoin,
}

/// docs/28 §4: `Possible` after a join timed out, when the Join may still run after our Leave; its
/// GroupJoined is then left once more.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum LateJoin {
    Settled,
    Possible,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct Member {
    pub(crate) group: Uuid,
    pub(crate) membership: Tag,
    pub(crate) host: HostSeat,
    pub(crate) participants: Vec<Name>,
    /// The (re)entry GroupJoined's `LastUpdatedAt`; a command emitted earlier is stale.
    pub(crate) enabled_at_ms: Option<i64>,
    pub(crate) link: Link,
    pub(crate) clock: Clock,
    pub(crate) cursor: Cursor,
    pub(crate) queue: Queue,
    pub(crate) phase: GroupPhase,
    pub(crate) seek_req: SeekReq,
}

/// docs/28 §1: the member that entered by creating; it only decides who sees the lobby's Start.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Role {
    Host,
    Guest,
}

/// docs/28 §1: who hosts, latched once at entry because a server's participant order is not
/// stable: `Me` after a create; for a guest, the members listed before it (`Known` one, `Among`
/// several, `Unknown` none). `Debug` hides names.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum HostSeat {
    Me,
    Known(Name),
    Among(Vec<Name>),
    Unknown,
}

/// docs/28 §1: `Lobby` before a title, `Playing` while one is loaded, `Finished` after the queue
/// ended.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Stage {
    Lobby,
    Playing,
    Finished,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Link {
    Connected,
    Down,
    AwaitSnapshot { token: Tag },
    Rejoining { req: Tag },
}

/// Before the first time sample, server-timed messages wait in `Unsynced`, the latest of each kind.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) enum Clock {
    Unsynced {
        queue: Option<QueueSnapshot>,
        command: Option<Command>,
    },
    Synced(Ring8),
}

/// The `LastUpdate` of the last applied PlayQueue; `Reset` on every (re)entry, because a 12.0
/// rejoin snapshot repeats the old `LastUpdate` and dropping it would make us the silent member.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Cursor {
    Reset,
    Applied(i64),
}

/// In `Set`, `index` is in bounds and `local` is our player's state for `playlist[index]`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) enum Queue {
    Empty,
    Set {
        playlist: Vec<Entry>,
        index: usize,
        local: Local,
    },
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Entry {
    pub item_id: Uuid,
    pub pid: Uuid,
}

/// In `Loading` and `Seeking`, `held` is the latest command for this item, applied once it lands.
/// `PausedReadyOwed` is paused after a reported stall: its BufferingEnded still sends the Ready.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Local {
    Stopped,
    Loading { held: Option<Command> },
    Seeking { seq: Tag, held: Option<Command> },
    Paused(Prompt),
    PausedReadyOwed,
    Playing(Playing),
    Buffering { anchor: Anchor, report: Report },
}

/// docs/28 §6: `Armed` waits for its StallPrompt timer after our Ready, sent at `landed_ticks`;
/// `Shown` offers Play to start without the stragglers. Any command clears it, except the
/// server's Pause answering that Ready ([`Member::echoes_ready`]).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Prompt {
    Quiet,
    Armed { token: Tag, landed_ticks: i64 },
    Shown,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct Playing {
    pub anchor: Anchor,
    pub lockout_until_mono_ms: u64,
    pub correcting: Option<Tag>,
}

/// The group timeline: at server time `when_ms` the item was at `ticks`, playing since.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct Anchor {
    pub ticks: i64,
    pub when_ms: i64,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Report {
    Pending { token: Tag },
    Sent,
}

/// docs/28 §4: the server's group state as it last said, for the waiting pill; no rule reads it.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum GroupPhase {
    Idle,
    Waiting,
    Paused,
    Playing,
}

/// docs/28 §1: a guest's host seat from its first GroupJoined's `participants`: everyone but one
/// entry named `me`. Without such an entry, ours is listed under another name, so every entry is
/// a candidate and host-left can't fire while we are listed.
pub(crate) fn host_seat(me: &Name, participants: &[String]) -> HostSeat {
    let mut others: Vec<Name> = participants.iter().cloned().map(Name).collect();
    let Some(own) = others.iter().position(|name| name == me) else {
        return HostSeat::Among(others);
    };
    others.remove(own);
    match others.len() {
        0 => HostSeat::Unknown,
        1 => others.pop().map_or(HostSeat::Unknown, HostSeat::Known),
        _ => HostSeat::Among(others),
    }
}

/// docs/28 §4: the server's group state as a phase; `None` when absent or unrecognized, never
/// guessed.
pub fn group_phase(state: Option<GroupStateType>) -> Option<GroupPhase> {
    match state? {
        GroupStateType::Idle => Some(GroupPhase::Idle),
        GroupStateType::Waiting => Some(GroupPhase::Waiting),
        GroupStateType::Paused => Some(GroupPhase::Paused),
        GroupStateType::Playing => Some(GroupPhase::Playing),
        GroupStateType::Unrecognized => None,
    }
}

/// Our outbound Seek: at most one POST in flight, later targets collapse into `Held`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum SeekReq {
    None,
    InFlight { req: Tag },
    Held { req: Tag, ticks: i64 },
}

/// A PlayQueue update with every id present.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct QueueSnapshot {
    pub reason: Option<PlayQueueUpdateReason>,
    pub last_update_ms: i64,
    pub playlist: Vec<Entry>,
    pub index: Option<usize>,
    pub start_ticks: i64,
    pub is_playing: bool,
}

/// A SendCommand with every field its kind needs.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Command {
    Stop,
    Pause(Cue),
    Unpause(Cue),
    Seek(Cue),
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct Cue {
    pub pid: Uuid,
    pub ticks: i64,
    pub when_ms: i64,
}

/// One `/GetUtcTime` round: server minus local clock, and the network round trip.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(crate) struct ClockSample {
    pub offset_ms: i64,
    pub rtt_ms: i64,
}

/// The last eight clock samples; the estimate is the one with the lowest round trip.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub(crate) struct Ring8 {
    slots: [Option<ClockSample>; 8],
    next: usize,
}

/// `HoldFor`: we are ahead of the group by `ms`. `Correct`: we are behind, seek to where it is.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Drift {
    InSync,
    HoldFor { ms: u64 },
    Correct { target_ticks: i64 },
}

/// The group state plus the tag counter; `Default` is Idle, which holds no heap and no scope.
#[derive(Clone, Debug, Default)]
pub struct SyncPlay {
    state: State,
    last_tag: u64,
}

impl SyncPlay {
    #[cfg(test)]
    pub(crate) fn state(&self) -> &State {
        &self.state
    }

    /// Whether the group is Idle, without building a snapshot.
    pub fn is_idle(&self) -> bool {
        self.state == State::Idle
    }

    /// Whether the group is Leaving, without building a snapshot.
    pub fn is_leaving(&self) -> bool {
        matches!(self.state, State::Leaving(_))
    }

    pub fn snapshot(&self) -> Snapshot {
        match &self.state {
            State::Idle => Snapshot::Idle,
            State::Creating { name, .. } => Snapshot::Starting {
                name: Some(name.clone()),
            },
            State::Joining { .. } => Snapshot::Starting { name: None },
            State::Member(m) => Snapshot::Member(MemberSnapshot {
                role: m.role(),
                stage: m.stage(),
                host: m.host.clone(),
                participants: m.participants.clone(),
                phase: m.phase,
                connected: m.link == Link::Connected,
                prompt_shown: m.local() == Some(&Local::Paused(Prompt::Shown)),
            }),
            State::Leaving(leaving) => Snapshot::Leaving {
                reason: leaving.reason,
            },
        }
    }

    /// The one transition function; every state change goes through here.
    pub fn step(&mut self, event: Event, now: Now) -> Vec<Effect> {
        if !is_current(&self.state, &event) {
            return Vec::new();
        }
        let before = self.state.clone();
        let mut out = Out {
            fx: Vec::new(),
            last_tag: self.last_tag,
        };
        let next = match &mut self.state {
            State::Member(m) => m
                .step(event, now, &mut out)
                .or_else(|| m.host_left(&mut out)),
            State::Leaving(leaving) => leaving_step(*leaving, &event, &mut out),
            outside => entry_step(outside, &event, &mut out),
        };
        if let Some(next) = next {
            self.state = next;
        }
        self.last_tag = out.last_tag;
        if notifies(&before, &self.state, &out.fx) {
            out.fx.push(Effect::Notify);
        }
        out.fx
    }
}

/// docs/28 §1: what the screens show of the group; no tags, clocks or ids. `Debug` names the
/// variant only, so names never reach logs.
#[derive(Clone, PartialEq, Eq)]
pub enum Snapshot {
    Idle,
    /// The name we posted while creating; `None` while joining.
    Starting {
        name: Option<RoomName>,
    },
    Member(MemberSnapshot),
    Leaving {
        reason: LeaveReason,
    },
}

/// `participants` in server order. `connected`: the link is up, not down, awaiting a snapshot or
/// rejoining. `prompt_shown`: Play is offered to start without the stragglers (§6).
#[derive(Clone, PartialEq, Eq)]
pub struct MemberSnapshot {
    pub role: Role,
    pub stage: Stage,
    pub host: HostSeat,
    pub participants: Vec<Name>,
    pub phase: GroupPhase,
    pub connected: bool,
    pub prompt_shown: bool,
}

impl std::fmt::Debug for Snapshot {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(match self {
            Snapshot::Idle => "Idle",
            Snapshot::Starting { .. } => "Starting",
            Snapshot::Member(_) => "Member",
            Snapshot::Leaving { .. } => "Leaving",
        })
    }
}

impl std::fmt::Debug for MemberSnapshot {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("MemberSnapshot")
    }
}

/// What a snapshot reader sees: state kind, leave reason, a member's role, participants, phase
/// and link kind, and the queue's playlist, index, local kind and a shown Play prompt; clocks,
/// tags, timers and lockouts are not.
#[derive(PartialEq)]
struct View<'a> {
    kind: Discriminant<State>,
    reason: Option<LeaveReason>,
    /// Role, participants.
    member: Option<(Role, &'a [Name])>,
    /// Phase, link kind.
    status: Option<(GroupPhase, Discriminant<Link>)>,
    /// Playlist, index, local kind, prompt shown.
    queue: Option<(&'a [Entry], usize, Discriminant<Local>, bool)>,
}

impl<'a> View<'a> {
    fn of(state: &'a State) -> Self {
        let mut view = View {
            kind: discriminant(state),
            reason: None,
            member: None,
            status: None,
            queue: None,
        };
        match state {
            State::Leaving(leaving) => view.reason = Some(leaving.reason),
            State::Member(m) => {
                view.member = Some((m.role(), &m.participants[..]));
                view.status = Some((m.phase, discriminant(&m.link)));
                if let Queue::Set {
                    playlist,
                    index,
                    local,
                } = &m.queue
                {
                    let shown = *local == Local::Paused(Prompt::Shown);
                    view.queue = Some((&playlist[..], *index, discriminant(local), shown));
                }
            }
            State::Idle | State::Creating { .. } | State::Joining { .. } => {}
        }
        view
    }
}

/// Notify rule: the snapshot-visible state changed, or a notice went out.
pub(crate) fn notifies(before: &State, after: &State, fx: &[Effect]) -> bool {
    View::of(before) != View::of(after) || fx.iter().any(|e| matches!(e, Effect::Ui(_)))
}

/// docs/28 §4: the one place stale results die. A tag that isn't current, another group's message,
/// a command emitted before our (re)entry or a queue update not newer than the last one applied
/// is dropped before any row sees it.
fn is_current(state: &State, event: &Event) -> bool {
    let member = state.member();
    let local = member.and_then(Member::local);
    let ours = |group: Option<Uuid>| state.group().is_none_or(|g| group == Some(g));
    match event {
        // docs/28 §2: error replies carry an empty group id, so the state rows decide them.
        Event::Server(SyncPlayEvent::GroupUpdate {
            update:
                GroupUpdate::GroupDoesNotExist(_)
                | GroupUpdate::NotInGroup(_)
                | GroupUpdate::LibraryAccessDenied(_),
            ..
        }) => true,
        Event::Server(SyncPlayEvent::GroupUpdate { group_id, update }) => {
            let newer = match (update, member.map(|m| m.cursor)) {
                (GroupUpdate::PlayQueue(u), Some(Cursor::Applied(applied))) => {
                    u.last_update.is_none_or(|t| t.timestamp_millis() > applied)
                }
                _ => true,
            };
            ours(envelope_group(*group_id, update)) && newer
        }
        Event::Server(SyncPlayEvent::Command(c)) => {
            let enabled = member.and_then(|m| m.enabled_at_ms);
            let fresh = match (c.emitted_at, enabled) {
                (Some(emitted), Some(enabled)) => emitted.timestamp_millis() >= enabled,
                _ => true,
            };
            ours(c.group_id) && fresh
        }
        Event::EntryFailed { req } => {
            matches!(state, State::Creating { req: r, .. } | State::Joining { req: r, .. } if r == req)
                || member.is_some_and(|m| m.link == Link::Rejoining { req: *req })
        }
        Event::RequestDone { req } => {
            matches!(state, State::Leaving(l) if l.req == *req)
                || member.is_some_and(|m| {
                    matches!(m.seek_req, SeekReq::InFlight { req: r } | SeekReq::Held { req: r, .. } if r == *req)
                })
        }
        Event::TimeSample(sample) => member.is_some_and(|m| m.membership == sample.membership),
        Event::ItemLoaded { pid, .. } => member.and_then(Member::current_pid) == Some(*pid),
        Event::SeekLanded { seq, .. } => matches!(local,
            Some(Local::Seeking { seq: s, .. } | Local::Playing(Playing { correcting: Some(s), .. })) if s == seq),
        Event::Timer(Timer::SnapshotWindow { token }) => member.is_some_and(|m| {
            matches!(m.link, Link::AwaitSnapshot { token: g } if g == *token)
        }),
        Event::Timer(Timer::StallPrompt { token }) => matches!(local,
            Some(Local::Paused(Prompt::Armed { token: t, .. })) if t == token),
        Event::Timer(Timer::BufferingReport { token }) => matches!(local,
            Some(Local::Buffering { report: Report::Pending { token: g }, .. }) if g == token),
        Event::Timer(Timer::EntryTimeout { token }) => matches!(state,
            State::Creating { timeout: t, .. } | State::Joining { timeout: t, .. } if t == token),
        _ => true,
    }
}

/// The group a message is about: the envelope's id, else a GroupJoined's own.
fn envelope_group(group_id: Option<Uuid>, update: &GroupUpdate) -> Option<Uuid> {
    group_id.or(match update {
        GroupUpdate::GroupJoined(info) => info.group_id,
        _ => None,
    })
}

/// Idle, Creating and Joining: everything before a GroupJoined makes us a member.
fn entry_step(state: &State, event: &Event, out: &mut Out) -> Option<State> {
    let idle = *state == State::Idle;
    let joining = state.group();
    match event {
        Event::Create(new) if idle => {
            // docs/28 §3: a group created while reporting would be seeded with our now-playing item.
            if new.reporting == Reporting::Live || new.bus == Bus::Down {
                out.ui(Notice::CreateRefused);
                return None;
            }
            let name = new.name.clone();
            let req = out.rest(RestCall::New { name: name.clone() });
            let timeout = out.after(ENTRY_TIMEOUT_MS, |token| Timer::EntryTimeout { token });
            Some(State::Creating { req, name, timeout })
        }
        Event::Join { group, bus, me } if idle => {
            if *bus == Bus::Down {
                out.ui(Notice::JoinRefused);
                return None;
            }
            let req = out.rest(RestCall::Join { group: *group });
            let timeout = out.after(ENTRY_TIMEOUT_MS, |token| Timer::EntryTimeout { token });
            Some(State::Joining {
                req,
                group: *group,
                timeout,
                me: me.clone(),
            })
        }
        Event::Server(SyncPlayEvent::GroupUpdate {
            group_id,
            update: update @ GroupUpdate::GroupJoined(info),
        }) => {
            // docs/28 §4: a disconnect grace replays a group we left, or another while we create.
            let ours = match state {
                State::Creating { name, .. } => info.group_name.as_deref() == Some(name.0.as_str()),
                _ => !idle,
            };
            if !ours {
                out.rest(RestCall::Leave);
                return None;
            }
            let host = match state {
                State::Joining { me, .. } => host_seat(me, &info.participants),
                _ => HostSeat::Me,
            };
            let group = envelope_group(*group_id, update)?;
            Some(State::Member(Box::new(Member::enter(
                group, info, host, out,
            ))))
        }
        _ if idle => None,
        Event::Server(SyncPlayEvent::GroupUpdate { update, .. }) if joining.is_some() => {
            let notice = match update {
                GroupUpdate::GroupDoesNotExist(_) => Notice::NoSuchGroup,
                GroupUpdate::LibraryAccessDenied(_) => Notice::NoAccess,
                _ => return None,
            };
            out.ui(notice);
            Some(State::Idle)
        }
        Event::BusDisconnected | Event::EntryFailed { .. } => {
            out.ui(Notice::StartFailed);
            Some(State::Idle)
        }
        Event::BusUnauthorized => Some(State::Idle),
        Event::PlayerClosed | Event::Leave => Some(State::Leaving(Leaving {
            req: out.rest(RestCall::Leave),
            group: joining,
            reason: LeaveReason::User,
            late_join: joining.map_or(LateJoin::Settled, |_| LateJoin::Possible),
        })),
        Event::Timer(Timer::EntryTimeout { .. }) => {
            out.ui(Notice::StartFailed);
            Some(State::Leaving(Leaving {
                req: out.rest(RestCall::Leave),
                group: joining,
                reason: LeaveReason::User,
                late_join: joining.map_or(LateJoin::Settled, |_| LateJoin::Possible),
            }))
        }
        Event::AccountReset => {
            out.rest(RestCall::Leave);
            Some(State::Idle)
        }
        _ => None,
    }
}

fn leaving_step(leaving: Leaving, event: &Event, out: &mut Out) -> Option<State> {
    let reason = leaving.reason;
    let Event::Server(SyncPlayEvent::GroupUpdate { group_id, update }) = event else {
        return matches!(
            event,
            Event::RequestDone { .. } | Event::BusUnauthorized | Event::AccountReset
        )
        .then_some(State::Idle);
    };
    match update {
        // A New or Join the server ran after our Leave still made us a member: leave that group too.
        GroupUpdate::GroupJoined(_)
            if leaving.group.is_none() || leaving.late_join == LateJoin::Possible =>
        {
            Some(State::Leaving(Leaving {
                req: out.rest(RestCall::Leave),
                group: envelope_group(*group_id, update),
                reason,
                late_join: LateJoin::Settled,
            }))
        }
        // docs/28 §4: after an unanswered create or join only our RequestDone ends Leaving, so no new
        // group starts while a second Leave may still follow.
        GroupUpdate::GroupLeft(_) | GroupUpdate::NotInGroup(_)
            if leaving.group.is_some() && leaving.late_join == LateJoin::Settled =>
        {
            Some(State::Idle)
        }
        _ => None,
    }
}

impl State {
    fn member(&self) -> Option<&Member> {
        match self {
            State::Member(m) => Some(m),
            _ => None,
        }
    }

    fn group(&self) -> Option<Uuid> {
        match self {
            State::Joining { group, .. } => Some(*group),
            State::Member(m) => Some(m.group),
            State::Leaving(leaving) => leaving.group,
            State::Idle | State::Creating { .. } => None,
        }
    }
}

impl Member {
    fn enter(group: Uuid, info: &GroupInfoDto, host: HostSeat, out: &mut Out) -> Member {
        let membership = out.tag();
        out.fx.push(Effect::Job(Job::TimeSync { membership }));
        out.ui(Notice::Joined);
        let mut member = Member {
            group,
            membership,
            host,
            participants: Vec::new(),
            enabled_at_ms: None,
            link: Link::Connected,
            clock: Clock::Unsynced {
                queue: None,
                command: None,
            },
            cursor: Cursor::Reset,
            queue: Queue::Empty,
            phase: GroupPhase::Idle,
            seek_req: SeekReq::None,
        };
        member.reenter(info);
        member
    }

    fn reenter(&mut self, info: &GroupInfoDto) {
        self.link = Link::Connected;
        self.participants = info.participants.iter().cloned().map(Name).collect();
        self.enabled_at_ms = info.last_updated_at.map(|t| t.timestamp_millis());
        self.cursor = Cursor::Reset;
        // A command queued before this entry predates it, and the replay skips the age check.
        if let Clock::Unsynced { command, .. } = &mut self.clock {
            *command = None;
        }
        self.set_phase(info.state);
    }

    fn local(&self) -> Option<&Local> {
        match &self.queue {
            Queue::Set { local, .. } => Some(local),
            Queue::Empty => None,
        }
    }

    fn role(&self) -> Role {
        if self.host == HostSeat::Me {
            Role::Host
        } else {
            Role::Guest
        }
    }

    fn stage(&self) -> Stage {
        match self.local() {
            None => Stage::Lobby,
            Some(Local::Stopped) => Stage::Finished,
            Some(_) => Stage::Playing,
        }
    }

    /// A player runs for the current item: the queue is set and has not ended.
    fn live(&self) -> bool {
        self.stage() == Stage::Playing
    }

    /// docs/28 §1: a member the seat names is still listed; a host always is.
    fn host_present(&self) -> bool {
        let listed = |name: &Name| self.participants.contains(name);
        match &self.host {
            HostSeat::Me => true,
            HostSeat::Known(host) => listed(host),
            HostSeat::Among(candidates) => candidates.iter().any(listed),
            HostSeat::Unknown => false,
        }
    }

    /// docs/28 §1: a synced guest with no player running leaves once its host is gone; a player
    /// keeps watching until the queue ends, and before the first clock sample a join is in transit.
    fn host_left_due(&self) -> bool {
        matches!(self.clock, Clock::Synced(_)) && !self.live() && !self.host_present()
    }

    /// Runs after every step that keeps the membership, so host-left is never owed at rest.
    fn host_left(&self, out: &mut Out) -> Option<State> {
        if !self.host_left_due() {
            return None;
        }
        out.ui(Notice::HostLeft);
        Some(self.leave(LeaveReason::HostLeft, out))
    }

    fn local_mut(&mut self) -> Option<&mut Local> {
        match &mut self.queue {
            Queue::Set { local, .. } => Some(local),
            Queue::Empty => None,
        }
    }

    fn current_pid(&self) -> Option<Uuid> {
        match &self.queue {
            Queue::Set {
                playlist, index, ..
            } => playlist.get(*index).map(|e| e.pid),
            Queue::Empty => None,
        }
    }

    fn server_now_ms(&self, now: Now) -> i64 {
        server_now_ms(now.wall_ms, self.clock.offset_ms())
    }

    /// Returns the next state when this event ends the membership.
    fn step(&mut self, event: Event, now: Now, out: &mut Out) -> Option<State> {
        // docs/28 §4: with no player running (the lobby), presses and player events do nothing.
        let player = matches!(
            event,
            Event::PlayerEnded
                | Event::PlayerClosed
                | Event::PlaybackError(_)
                | Event::Pause
                | Event::Unpause
                | Event::Seek { .. }
        );
        if player && !self.live() {
            return None;
        }
        match event {
            Event::Server(SyncPlayEvent::GroupUpdate { update, .. }) => {
                return self.on_update(update, now, out)
            }
            Event::Server(SyncPlayEvent::Command(c)) => self.on_command(&c, now, out),
            Event::BusConnected => {
                let token = out.after(SNAPSHOT_WINDOW_MS, |token| Timer::SnapshotWindow { token });
                self.link = Link::AwaitSnapshot { token };
            }
            Event::BusDisconnected => self.link = Link::Down,
            Event::BusUnauthorized => return Some(end(EndCause::Auth, out)),
            Event::EntryFailed { .. } => return Some(end(EndCause::Lost, out)),
            Event::Timer(Timer::SnapshotWindow { .. }) => self.rejoin(out),
            Event::Timer(Timer::StallPrompt { .. }) => self.set_local(Local::Paused(Prompt::Shown)),
            Event::Timer(Timer::BufferingReport { .. }) => {
                let server_now = self.server_now_ms(now);
                if let Some(Local::Buffering { anchor, report }) = self.local_mut() {
                    *report = Report::Sent;
                    let position = expected_ticks(*anchor, server_now);
                    self.report(RestCall::Buffering, position, true, now, out);
                }
            }
            Event::TimeSample(sample) => self.on_time_sample(&sample, now, out),
            Event::ItemLoaded { landed_ticks, .. } => {
                if let Some(Local::Loading { held }) = self.local().copied() {
                    self.land(held, landed_ticks, now, out);
                }
            }
            Event::SeekLanded { landed_ticks, .. } => match self.local_mut() {
                Some(Local::Seeking { held, .. }) => {
                    let held = *held;
                    self.land(held, landed_ticks, now, out);
                }
                Some(Local::Playing(playing)) => {
                    playing.correcting = None;
                    playing.lockout_until_mono_ms = now.mono_ms.saturating_add(LOCKOUT_MS);
                }
                _ => {}
            },
            Event::BufferingStarted | Event::PlaybackError(Severity::Recoverable) => {
                if let Some(Local::Playing(Playing { anchor, .. })) = self.local().copied() {
                    let token = out.after(BUFFERING_REPORT_MS, |token| Timer::BufferingReport {
                        token,
                    });
                    let report = Report::Pending { token };
                    self.set_local(Local::Buffering { anchor, report });
                }
            }
            Event::BufferingEnded { position_ticks } => {
                if self.local() == Some(&Local::PausedReadyOwed) {
                    self.land(None, position_ticks, now, out);
                } else if let Some(local) = self.local_mut() {
                    if let Local::Buffering { anchor, report } = *local {
                        *local = Local::Playing(Playing {
                            anchor,
                            lockout_until_mono_ms: now.mono_ms.saturating_add(LOCKOUT_MS),
                            correcting: None,
                        });
                        if report == Report::Sent {
                            self.report(RestCall::Ready, position_ticks, true, now, out);
                        }
                    }
                }
            }
            Event::PlaybackError(Severity::Fatal) => {
                return Some(self.leave(LeaveReason::PlayerError, out))
            }
            Event::PlayerEnded => {
                if let Queue::Set {
                    playlist,
                    index,
                    local,
                } = &mut self.queue
                {
                    if let Some(current) =
                        playlist.get(*index).filter(|_| *index + 1 < playlist.len())
                    {
                        out.rest(RestCall::NextItem { pid: current.pid });
                    } else {
                        *local = Local::Stopped;
                        out.dir(Directive::ReturnToLobby);
                    }
                }
            }
            Event::PlayerClosed => return Some(self.leave(LeaveReason::User, out)),
            Event::PositionSample { position_ticks } => self.on_position(position_ticks, now, out),
            Event::Pause => {
                if let Some(local @ (Local::Playing(_) | Local::Buffering { .. })) =
                    self.local_mut()
                {
                    *local = paused(*local);
                }
                out.dir(pause_here(now));
                if self.link == Link::Connected {
                    out.rest(RestCall::Pause);
                }
            }
            // docs/28 §6: the only Unpause we send; while the group waits it is the server's force.
            Event::Unpause => {
                if self.link == Link::Connected {
                    out.rest(RestCall::Unpause);
                } else {
                    out.ui(Notice::Reconnecting);
                }
            }
            Event::Seek { ticks } => {
                self.seek_req = match self.seek_req {
                    SeekReq::None => SeekReq::InFlight {
                        req: out.rest(RestCall::Seek { ticks }),
                    },
                    SeekReq::InFlight { req } | SeekReq::Held { req, .. } => {
                        SeekReq::Held { req, ticks }
                    }
                }
            }
            // docs/28 §4: a seek held behind one in flight dies with the player.
            Event::RequestDone { .. } => {
                self.seek_req = match self.seek_req {
                    SeekReq::Held { ticks, .. } if self.live() => SeekReq::InFlight {
                        req: out.rest(RestCall::Seek { ticks }),
                    },
                    _ => SeekReq::None,
                }
            }
            Event::PickItem(queue) => {
                out.rest(RestCall::SetNewQueue(queue));
            }
            Event::Queue(add) => {
                out.rest(RestCall::Queue(add));
            }
            Event::Next => {
                if let Some(pid) = self.current_pid() {
                    out.rest(RestCall::NextItem { pid });
                }
            }
            Event::Prev => {
                if let Some(pid) = self.current_pid() {
                    out.rest(RestCall::PreviousItem { pid });
                }
            }
            Event::Leave => {
                let next = self.leave(LeaveReason::User, out);
                out.dir(Directive::Detach);
                return Some(next);
            }
            Event::AccountReset => {
                out.rest(RestCall::Leave);
                detach(out);
                return Some(State::Idle);
            }
            Event::Create { .. }
            | Event::Join { .. }
            | Event::Timer(Timer::EntryTimeout { .. }) => {}
        }
        None
    }

    fn on_update(&mut self, update: GroupUpdate, now: Now, out: &mut Out) -> Option<State> {
        let rejoining = matches!(self.link, Link::Rejoining { .. });
        match update {
            GroupUpdate::GroupJoined(info) => self.reenter(&info),
            // docs/28 §4: a listed name joining again is a reconnect the server never dropped.
            GroupUpdate::UserJoined(name) if !self.participants.iter().any(|n| n.0 == name) => {
                self.participants.push(Name(name.clone()));
                out.ui(Notice::UserJoined(Name(name)));
            }
            GroupUpdate::UserJoined(_) => {}
            GroupUpdate::UserLeft(name) => {
                let name = Name(name);
                if let Some(i) = self.participants.iter().position(|n| *n == name) {
                    self.participants.remove(i);
                }
                out.ui(Notice::UserLeft(name));
            }
            GroupUpdate::GroupLeft(_) if !rejoining => return Some(end(EndCause::Removed, out)),
            GroupUpdate::NotInGroup(_) if self.link == Link::Connected => self.rejoin(out),
            GroupUpdate::GroupDoesNotExist(_) | GroupUpdate::LibraryAccessDenied(_)
                if rejoining =>
            {
                return Some(end(EndCause::Gone, out))
            }
            GroupUpdate::StateUpdate(update) => self.set_phase(update.state),
            GroupUpdate::PlayQueue(update) => {
                let snapshot = QueueSnapshot::parse(&update)?;
                match &mut self.clock {
                    Clock::Unsynced { queue, .. } => {
                        if queue
                            .as_ref()
                            .is_none_or(|q| q.last_update_ms < snapshot.last_update_ms)
                        {
                            *queue = Some(snapshot);
                        }
                    }
                    Clock::Synced(_) => self.apply_queue(snapshot, now, out),
                }
            }
            _ => {}
        }
        None
    }

    /// docs/28 §4: the phase is what the server said, never inferred; an unknown state keeps it.
    fn set_phase(&mut self, state: Option<GroupStateType>) {
        if let Some(phase) = group_phase(state) {
            self.phase = phase;
        }
    }

    fn on_command(&mut self, command: &SendCommand, now: Now, out: &mut Out) {
        let Some(parsed) = Command::parse(command) else {
            return;
        };
        match &mut self.clock {
            Clock::Unsynced { command, .. } => *command = Some(parsed),
            Clock::Synced(_) => self.apply_command(parsed, now, out),
        }
    }

    fn apply_command(&mut self, command: Command, now: Now, out: &mut Out) {
        let offset = self.clock.offset_ms();
        let server_now = server_now_ms(now.wall_ms, offset);
        let current = self.current_pid();
        // docs/28 §4: after the queue ends, commands are ignored until a new title loads.
        if !self.live() {
            return;
        }
        let echo = match (command, self.local()) {
            (Command::Pause(cue), Some(Local::Paused(Prompt::Armed { landed_ticks, .. }))) => {
                self.echoes_ready(cue, *landed_ticks)
            }
            _ => false,
        };
        let Some(local) = self.local_mut() else {
            return;
        };
        match command {
            Command::Stop => {
                *local = Local::Stopped;
                out.dir(Directive::ReturnToLobby);
            }
            // docs/28 §6: any command clears the prompt, even one for another item.
            Command::Pause(cue) | Command::Unpause(cue) | Command::Seek(cue)
                if current != Some(cue.pid) =>
            {
                if let Local::Paused(prompt) = local {
                    *prompt = Prompt::Quiet;
                }
            }
            _ if matches!(local, Local::Loading { .. } | Local::Seeking { .. }) => {
                if let Local::Loading { held } | Local::Seeking { held, .. } = local {
                    *held = Some(command);
                }
            }
            Command::Pause(cue) => {
                if !echo {
                    *local = paused(*local);
                }
                out.dir(Directive::Pause(PauseAt {
                    freeze_ticks: Some(cue.ticks),
                    due_wall_ms: cue.when_ms.saturating_sub(offset),
                }));
            }
            Command::Unpause(cue) => {
                let anchor = Anchor {
                    ticks: cue.ticks,
                    when_ms: cue.when_ms,
                };
                let wait_ms = cue.when_ms.saturating_sub(server_now).max(0);
                *local = Local::Playing(Playing {
                    anchor,
                    lockout_until_mono_ms: now
                        .mono_ms
                        .saturating_add(wait_ms.unsigned_abs())
                        .saturating_add(LOCKOUT_MS),
                    correcting: None,
                });
                out.dir(Directive::Unpause(PlayAt {
                    position_ticks: expected_ticks(anchor, server_now),
                    due_wall_ms: now.wall_ms.saturating_add(wait_ms),
                }));
            }
            Command::Seek(cue) => {
                let seq = out.dir(Directive::Seek {
                    position_ticks: cue.ticks,
                });
                *local = Local::Seeking { seq, held: None };
            }
        }
    }

    /// Callers drop a snapshot not newer than the cursor; while Unsynced the cursor is Reset.
    fn apply_queue(&mut self, snapshot: QueueSnapshot, now: Now, out: &mut Out) {
        let reason = snapshot.reason;
        let reset = self.cursor == Cursor::Reset;
        self.cursor = Cursor::Applied(snapshot.last_update_ms);
        let current = snapshot
            .index
            .and_then(|i| Some((i, *snapshot.playlist.get(i)?)));
        let Some((index, entry)) = current else {
            if self.live() {
                out.dir(Directive::ReturnToLobby);
            }
            self.queue = Queue::Empty;
            return;
        };
        // docs/28 §5: a playing snapshot advances by the elapsed time; a joiner's frozen one doesn't.
        let start_ticks = if snapshot.is_playing {
            let anchor = Anchor {
                ticks: snapshot.start_ticks,
                when_ms: snapshot.last_update_ms,
            };
            expected_ticks(anchor, self.server_now_ms(now))
        } else {
            snapshot.start_ticks
        };
        let same_item = self.current_pid() == Some(entry.pid);
        let restart = matches!(
            reason,
            Some(PlayQueueUpdateReason::NewPlaylist | PlayQueueUpdateReason::SetCurrentItem)
        );
        let local = match self.local().copied() {
            // docs/28 §4: only a fresh start reloads the ended title; a rejoin snapshot or a queue edit doesn't.
            Some(Local::Stopped) if same_item && (reset || !restart) => Local::Stopped,
            Some(local) if same_item && local != Local::Stopped => {
                if restart {
                    Local::Seeking {
                        seq: out.dir(Directive::Seek {
                            position_ticks: start_ticks,
                        }),
                        held: None,
                    }
                } else {
                    local
                }
            }
            _ => {
                out.dir(Directive::Load { entry, start_ticks });
                Local::Loading { held: None }
            }
        };
        self.queue = Queue::Set {
            playlist: snapshot.playlist,
            index,
            local,
        };
    }

    fn on_time_sample(&mut self, time: &TimeSample, now: Now, out: &mut Out) {
        let (Some(received), Some(sent)) = (
            time.response.request_reception_time,
            time.response.response_transmission_time,
        ) else {
            return;
        };
        let (received, sent) = (received.timestamp_millis(), sent.timestamp_millis());
        let sample = clock_sample(time.sent_wall_ms, received, sent, time.received_wall_ms);
        let (mut ring, queued) =
            match std::mem::replace(&mut self.clock, Clock::Synced(Ring8::default())) {
                Clock::Synced(ring) => (ring, (None, None)),
                Clock::Unsynced { queue, command } => (Ring8::default(), (queue, command)),
            };
        ring.push(sample);
        let best = ring.best().unwrap_or(sample);
        self.clock = Clock::Synced(ring);
        out.rest(RestCall::Ping {
            ms: best.rtt_ms.saturating_add(1) / 2,
        });
        if let Some(snapshot) = queued.0 {
            self.apply_queue(snapshot, now, out);
        }
        if let Some(command) = queued.1 {
            self.apply_command(command, now, out);
        }
    }

    fn on_position(&mut self, position_ticks: i64, now: Now, out: &mut Out) {
        let synced = matches!(self.clock, Clock::Synced(_));
        let server_now = self.server_now_ms(now);
        let Some(Local::Playing(Playing {
            anchor,
            lockout_until_mono_ms,
            correcting,
        })) = self.local_mut()
        else {
            return;
        };
        if !synced || correcting.is_some() || now.mono_ms < *lockout_until_mono_ms {
            return;
        }
        match decide_drift(expected_ticks(*anchor, server_now), position_ticks) {
            Drift::InSync => {}
            Drift::Correct { target_ticks } => {
                *correcting = Some(out.dir(Directive::Correct {
                    position_ticks: target_ticks,
                }));
            }
            // A hold has no landing event, so the lockout covers the hold itself as well.
            Drift::HoldFor { ms } => {
                out.dir(Directive::HoldFor { ms });
                *lockout_until_mono_ms = now.mono_ms.saturating_add(ms).saturating_add(LOCKOUT_MS);
            }
        }
    }

    /// docs/28 §4, §6: a load or sync seek landed, paused. With nothing held, or a held echo of
    /// our Ready, our Ready arms the Play prompt; another held command runs after the Ready and
    /// arms none; a held Seek skips the Ready, which would draw a corrective Seek, and its own
    /// landing reports.
    fn land(&mut self, held: Option<Command>, landed_ticks: i64, now: Now, out: &mut Out) {
        if !matches!(held, Some(Command::Seek(_))) {
            self.report(RestCall::Ready, landed_ticks, false, now, out);
        }
        let arms = match held {
            None => true,
            Some(Command::Pause(cue)) => self.echoes_ready(cue, landed_ticks),
            Some(_) => false,
        };
        let prompt = if arms && self.link == Link::Connected {
            Prompt::Armed {
                token: out.after(STALL_PROMPT_MS, |token| Timer::StallPrompt { token }),
                landed_ticks,
            }
        } else {
            Prompt::Quiet
        };
        self.set_local(Local::Paused(prompt));
        if let Some(command) = held {
            self.apply_command(command, now, out);
        }
    }

    /// docs/28 §6: while the group waits, a Pause within [`DRIFT_MS`] of the landing our Ready
    /// reported is the server holding us for the stragglers, not anyone's command. Callers pass
    /// only a cue for the current item: another item's never reaches a held slot or this arm.
    fn echoes_ready(&self, cue: Cue, landed_ticks: i64) -> bool {
        self.phase == GroupPhase::Waiting
            && cue.ticks.abs_diff(landed_ticks) < (DRIFT_MS * TICKS_PER_MS).unsigned_abs()
    }

    fn set_local(&mut self, next: Local) {
        if let Some(local) = self.local_mut() {
            *local = next;
        }
    }

    /// Ready or Buffering for the current item, sent only over a Connected link.
    fn report(
        &self,
        call: fn(PlayerReport) -> RestCall,
        position_ticks: i64,
        is_playing: bool,
        now: Now,
        out: &mut Out,
    ) {
        if let (Some(pid), Link::Connected) = (self.current_pid(), self.link) {
            let when_ms = self.server_now_ms(now);
            out.rest(call(PlayerReport {
                when_ms,
                position_ticks,
                is_playing,
                pid,
            }));
        }
    }

    fn rejoin(&mut self, out: &mut Out) {
        self.link = Link::Rejoining {
            req: out.rest(RestCall::Join { group: self.group }),
        };
    }

    fn leave(&self, reason: LeaveReason, out: &mut Out) -> State {
        let req = out.rest(RestCall::Leave);
        out.cancel();
        State::Leaving(Leaving {
            req,
            group: Some(self.group),
            reason,
            // docs/28 §4: a rejoin still in flight can make us a member after our Leave.
            late_join: if matches!(self.link, Link::Rejoining { .. }) {
                LateJoin::Possible
            } else {
                LateJoin::Settled
            },
        })
    }
}

/// docs/28 §5: a Ready owed after a reported stall survives the pause.
fn paused(local: Local) -> Local {
    match local {
        Local::Buffering {
            report: Report::Sent,
            ..
        }
        | Local::PausedReadyOwed => Local::PausedReadyOwed,
        _ => Local::Paused(Prompt::Quiet),
    }
}

fn pause_here(now: Now) -> Directive {
    Directive::Pause(PauseAt {
        freeze_ticks: None,
        due_wall_ms: now.wall_ms,
    })
}

/// The group is over for us: jobs stop, the player goes solo.
fn end(cause: EndCause, out: &mut Out) -> State {
    detach(out);
    out.ui(Notice::GroupEnded(cause));
    State::Idle
}

/// Jobs stop and the player returns to solo controls (docs/28 §4).
fn detach(out: &mut Out) {
    out.cancel();
    out.dir(Directive::Detach);
}

impl Clock {
    /// Server minus local clock; 0 before the first sample.
    pub(crate) fn offset_ms(&self) -> i64 {
        match self {
            Clock::Synced(ring) => ring.best().map_or(0, |s| s.offset_ms),
            Clock::Unsynced { .. } => 0,
        }
    }
}

impl QueueSnapshot {
    /// `None` when `LastUpdate` or any playlist id is missing; `apply_queue` treats an index
    /// outside the list as nothing current.
    fn parse(update: &PlayQueueUpdate) -> Option<Self> {
        let playlist = update
            .playlist
            .iter()
            .map(|e| {
                Some(Entry {
                    item_id: e.item_id?,
                    pid: e.playlist_item_id?,
                })
            })
            .collect::<Option<Vec<_>>>()?;
        let index = update
            .playing_item_index
            .and_then(|i| usize::try_from(i).ok());
        Some(Self {
            reason: update.reason,
            last_update_ms: update.last_update?.timestamp_millis(),
            playlist,
            index,
            start_ticks: update.start_position_ticks.unwrap_or(0),
            is_playing: update.is_playing.unwrap_or(false),
        })
    }
}

impl Command {
    /// `None` (stale) without `Command` or `When`, or without the item and position any kind but
    /// Stop acts on.
    fn parse(command: &SendCommand) -> Option<Self> {
        let when_ms = command.when?.timestamp_millis();
        let cue = || {
            Some(Cue {
                pid: command.playlist_item_id?,
                ticks: command.position_ticks?,
                when_ms,
            })
        };
        match command.command? {
            SendCommandType::Stop => Some(Command::Stop),
            SendCommandType::Pause => cue().map(Command::Pause),
            SendCommandType::Unpause => cue().map(Command::Unpause),
            SendCommandType::Seek => cue().map(Command::Seek),
            SendCommandType::Unrecognized => None,
        }
    }
}

impl Ring8 {
    pub(crate) fn push(&mut self, sample: ClockSample) {
        if let Some(slot) = self.slots.get_mut(self.next) {
            *slot = Some(sample);
        }
        self.next = (self.next + 1) % self.slots.len();
    }

    /// The lowest-RTT sample, whose offset has the least network asymmetry in it.
    pub(crate) fn best(&self) -> Option<ClockSample> {
        self.slots
            .iter()
            .flatten()
            .min_by_key(|s| s.rtt_ms)
            .copied()
    }
}

/// NTP-style offset and round trip from send (`t0`), server receive (`t1`), server send (`t2`) and
/// receive (`t3`); a negative round trip from clock noise counts as 0.
pub(crate) fn clock_sample(
    t0_wall_ms: i64,
    t1_ms: i64,
    t2_ms: i64,
    t3_wall_ms: i64,
) -> ClockSample {
    let outbound = t1_ms.saturating_sub(t0_wall_ms);
    let inbound = t2_ms.saturating_sub(t3_wall_ms);
    let rtt = t3_wall_ms
        .saturating_sub(t0_wall_ms)
        .saturating_sub(t2_ms.saturating_sub(t1_ms));
    ClockSample {
        offset_ms: outbound.saturating_add(inbound) / 2,
        rtt_ms: rtt.max(0),
    }
}

pub(crate) fn server_now_ms(wall_ms: i64, offset_ms: i64) -> i64 {
    wall_ms.saturating_add(offset_ms)
}

/// Where the group is at `server_now_ms` playing from `anchor`; callers use it only while the
/// group plays, and time before the anchor counts as none.
pub(crate) fn expected_ticks(anchor: Anchor, server_now_ms: i64) -> i64 {
    let elapsed_ms = server_now_ms.saturating_sub(anchor.when_ms).max(0);
    anchor
        .ticks
        .saturating_add(elapsed_ms.saturating_mul(TICKS_PER_MS))
}

/// Pause-or-skip, never rate: behind by [`DRIFT_MS`] or more seeks forward, ahead holds still.
pub(crate) fn decide_drift(expected_ticks: i64, position_ticks: i64) -> Drift {
    let drift = expected_ticks.saturating_sub(position_ticks);
    let threshold = DRIFT_MS * TICKS_PER_MS;
    if drift >= threshold {
        Drift::Correct {
            target_ticks: expected_ticks,
        }
    } else if drift <= -threshold {
        let ms = (drift / TICKS_PER_MS).unsigned_abs();
        if ms <= MAX_HOLD_MS {
            Drift::HoldFor { ms }
        } else {
            Drift::Correct {
                target_ticks: expected_ticks,
            }
        }
    } else {
        Drift::InSync
    }
}

/// Delay before time sample `samples_taken + 1`: one at once, three a second apart, then a minute.
pub fn time_sync_delay_ms(samples_taken: u32) -> u64 {
    match samples_taken {
        0 => 0,
        1..=3 => 1_000,
        _ => 60_000,
    }
}

/// docs/28 §5: delay before retrying a failed sample after `successes` good ones: the schedule's
/// next delay, but at least a second, so a failing server is never polled in a loop.
pub fn time_sync_retry_ms(successes: u32) -> u64 {
    time_sync_delay_ms(successes).max(1_000)
}

/// docs/28 §1: `base` when no listed group carries that name, else the first free of `base 2`,
/// `base 3`, ...; names compare trimmed and ignoring case. Never logs a name.
pub fn room_name(base: &str, groups: &[GroupInfoDto]) -> RoomName {
    let taken = |candidate: &str| {
        groups.iter().any(|g| {
            g.group_name
                .as_deref()
                .is_some_and(|name| name.trim().eq_ignore_ascii_case(candidate.trim()))
        })
    };
    let mut name = base.to_string();
    let mut n = 1u32;
    while taken(&name) {
        n += 1;
        name = format!("{base} {n}");
    }
    RoomName(name)
}

/// Effects collected by one step, and the tag counter they draw from.
struct Out {
    fx: Vec<Effect>,
    last_tag: u64,
}

impl Out {
    fn tag(&mut self) -> Tag {
        self.last_tag = self.last_tag.wrapping_add(1);
        Tag(self.last_tag)
    }

    fn rest(&mut self, call: RestCall) -> Tag {
        let req = self.tag();
        self.fx.push(Effect::Rest { req, call });
        req
    }

    fn dir(&mut self, directive: Directive) -> Tag {
        let seq = self.tag();
        self.fx.push(Effect::Dir { seq, directive });
        seq
    }

    fn ui(&mut self, notice: Notice) {
        self.fx.push(Effect::Ui(notice));
    }

    fn after(&mut self, delay_ms: u64, timer: impl FnOnce(Tag) -> Timer) -> Tag {
        let token = self.tag();
        self.fx.push(Effect::Job(Job::After {
            delay_ms,
            timer: timer(token),
        }));
        token
    }

    fn cancel(&mut self) {
        self.fx.push(Effect::CancelScope);
    }
}

#[cfg(test)]
mod tests;
