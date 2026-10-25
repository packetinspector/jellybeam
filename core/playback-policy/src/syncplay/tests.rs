use super::*;
use chrono::{DateTime, Utc};
use jellyfin_api::models::SyncPlayQueueItem;
use jellyfin_api::models::{GroupStateUpdate, PlaybackRequestType};
use PlayQueueUpdateReason as Why;

const fn id(n: u128) -> Uuid {
    Uuid::from_u128(0x0000_0000_0000_4000_8000_0000_0000_0000 | n)
}
const GROUP: Uuid = id(0x01);
const OTHER_GROUP: Uuid = id(0x09);
const ITEM_1: Uuid = id(0x11);
const ITEM_2: Uuid = id(0x12);
const PID_1: Uuid = id(0x21);
const PID_2: Uuid = id(0x22);
const A: &str = "member-a";
const B: &str = "member-b";
const C: &str = "member-c";
/// The guest rigs' own user name: listed after the host, as Jellyfin lists a joiner.
const ME: &str = B;
/// The test server clock runs this far ahead of the device's wall clock.
const OFFSET: i64 = 250;
const WALL_0: i64 = 1_800_000_000_000;
/// Jellyfin ticks in a millisecond.
const TICKS: i64 = 10_000;

/// `n` milliseconds as Jellyfin ticks.
const fn ms(n: i64) -> i64 {
    n * TICKS
}

fn dt(ms: i64) -> DateTime<Utc> {
    DateTime::from_timestamp_millis(ms).expect("test time")
}

fn now_at(mono: u64) -> Now {
    Now {
        mono_ms: mono,
        wall_ms: WALL_0 + i64::try_from(mono).expect("test clock"),
    }
}

fn server_at(now: Now) -> i64 {
    now.wall_ms + OFFSET
}

fn member_of(state: &State) -> Option<&Member> {
    match state {
        State::Member(m) => Some(m),
        _ => None,
    }
}

// ---- event builders ----

fn update(group: Uuid, update: GroupUpdate) -> Event {
    Event::Server(SyncPlayEvent::GroupUpdate {
        group_id: Some(group),
        update,
    })
}

/// The name our Create posts as the group name.
const ROOM: &str = "Sam's group";

fn joined(group: Uuid, names: &[&str], state: GroupStateType, last_ms: i64) -> Event {
    joined_named(group, ROOM, names, state, last_ms)
}

fn joined_named(
    group: Uuid,
    name: &str,
    names: &[&str],
    state: GroupStateType,
    last_ms: i64,
) -> Event {
    update(
        group,
        GroupUpdate::GroupJoined(GroupInfoDto {
            group_id: Some(group),
            group_name: Some(name.into()),
            last_updated_at: Some(dt(last_ms)),
            participants: names.iter().map(ToString::to_string).collect(),
            state: Some(state),
        }),
    )
}

fn user_joined(name: &str) -> Event {
    update(GROUP, GroupUpdate::UserJoined(name.into()))
}

fn state_update(state: GroupStateType, reason: PlaybackRequestType) -> Event {
    update(
        GROUP,
        GroupUpdate::StateUpdate(GroupStateUpdate {
            reason: Some(reason),
            state: Some(state),
        }),
    )
}

/// docs/28 §2: the server sends GroupDoesNotExist and NotInGroup with an empty group id.
fn error_reply(make: fn(String) -> GroupUpdate) -> Event {
    update(Uuid::nil(), make(Uuid::nil().to_string()))
}

fn group_signal(make: fn(String) -> GroupUpdate) -> Event {
    update(GROUP, make(GROUP.to_string()))
}

fn play_queue(
    reason: Why,
    last_ms: i64,
    items: &[(Uuid, Uuid)],
    index: i32,
    start: i64,
    playing: bool,
) -> Event {
    update(
        GROUP,
        GroupUpdate::PlayQueue(PlayQueueUpdate {
            is_playing: Some(playing),
            last_update: Some(dt(last_ms)),
            playing_item_index: Some(index),
            playlist: items
                .iter()
                .map(|(item, pid)| SyncPlayQueueItem {
                    item_id: Some(*item),
                    playlist_item_id: Some(*pid),
                })
                .collect(),
            reason: Some(reason),
            start_position_ticks: Some(start),
            ..Default::default()
        }),
    )
}

fn command(
    kind: SendCommandType,
    pid: Option<Uuid>,
    ticks: Option<i64>,
    when_ms: Option<i64>,
    emitted_ms: i64,
) -> Event {
    Event::Server(SyncPlayEvent::Command(SendCommand {
        command: Some(kind),
        emitted_at: Some(dt(emitted_ms)),
        group_id: Some(GROUP),
        playlist_item_id: pid,
        position_ticks: ticks,
        when: when_ms.map(dt),
    }))
}

fn command_fields(kind: SendCommandType, s: i64) -> SendCommand {
    match command(kind, Some(PID_1), Some(0), Some(s), s) {
        Event::Server(SyncPlayEvent::Command(c)) => c,
        _ => unreachable!(),
    }
}

/// A sample whose server stamps sit OFFSET ahead with a 40 ms round trip.
fn time_sample(membership: Tag, now: Now) -> Event {
    let server = now.wall_ms + OFFSET + 20;
    Event::TimeSample(TimeSample {
        membership,
        sent_wall_ms: now.wall_ms,
        received_wall_ms: now.wall_ms + 40,
        response: UtcTimeResponse {
            request_reception_time: Some(dt(server)),
            response_transmission_time: Some(dt(server)),
        },
    })
}

/// The host's Start or a rejoin snapshot: one item, from the top, paused.
fn new_playlist(last_ms: i64) -> Event {
    play_queue(Why::NewPlaylist, last_ms, &[(ITEM_1, PID_1)], 0, 0, false)
}

/// A paused group's queue moves to the second of two items.
fn next_item(last_ms: i64) -> Event {
    play_queue(
        Why::NextItem,
        last_ms,
        &[(ITEM_1, PID_1), (ITEM_2, PID_2)],
        1,
        0,
        false,
    )
}

fn loaded(pid: Uuid) -> Event {
    Event::ItemLoaded {
        pid,
        landed_ticks: 0,
    }
}

fn create() -> Event {
    create_with(Reporting::Idle, Bus::Up)
}

fn create_with(reporting: Reporting, bus: Bus) -> Event {
    Event::Create(NewGroup {
        name: RoomName(ROOM.into()),
        reporting,
        bus,
    })
}

fn pick_item() -> Event {
    Event::PickItem(NewQueue {
        items: vec![ITEM_1],
        index: 0,
        start_ticks: 0,
    })
}

// ---- effect readers ----

fn head(value: &impl std::fmt::Debug) -> String {
    let text = format!("{value:?}");
    text.split(|c: char| !c.is_alphanumeric())
        .next()
        .unwrap_or_default()
        .to_string()
}

fn label(effect: &Effect) -> String {
    match effect {
        Effect::Rest { call, .. } => format!("Rest:{}", head(call)),
        Effect::Dir { directive, .. } => format!("Dir:{}", head(directive)),
        Effect::Ui(notice) => format!("Ui:{notice:?}"),
        Effect::Job(Job::TimeSync { .. }) => "Job:TimeSync".into(),
        Effect::Job(Job::After { timer, .. }) => format!("Job:{}", head(timer)),
        Effect::CancelScope => "CancelScope".into(),
        Effect::Notify => "Notify".into(),
    }
}

/// The same effects in any order, except that requests and directives keep their relative
/// order, which the rules do fix (a Ready before the held command it releases).
fn same_effects(got: &[String], want: &[&str]) -> bool {
    let ordered = |l: &&str| l.starts_with("Rest:") || l.starts_with("Dir:");
    let got_ordered: Vec<&str> = got.iter().map(String::as_str).filter(ordered).collect();
    let want_ordered: Vec<&str> = want.iter().copied().filter(ordered).collect();
    let mut got_all: Vec<&str> = got.iter().map(String::as_str).collect();
    let mut want_all = want.to_vec();
    got_all.sort_unstable();
    want_all.sort_unstable();
    got_ordered == want_ordered && got_all == want_all
}

fn labels(fx: &[Effect]) -> Vec<String> {
    fx.iter().map(label).collect()
}

fn rests(fx: &[Effect]) -> Vec<&RestCall> {
    fx.iter()
        .filter_map(|e| match e {
            Effect::Rest { call, .. } => Some(call),
            _ => None,
        })
        .collect()
}

fn dirs(fx: &[Effect]) -> Vec<(Tag, Directive)> {
    fx.iter()
        .filter_map(|e| match e {
            Effect::Dir { seq, directive } => Some((*seq, *directive)),
            _ => None,
        })
        .collect()
}

fn timer_in(fx: &[Effect]) -> Timer {
    fx.iter()
        .find_map(|e| match e {
            Effect::Job(Job::After { timer, .. }) => Some(*timer),
            _ => None,
        })
        .expect("a timer job")
}

fn req_in(fx: &[Effect]) -> Tag {
    fx.iter()
        .find_map(|e| match e {
            Effect::Rest { req, .. } => Some(*req),
            _ => None,
        })
        .expect("a rest call")
}

fn count_unpause(fx: &[Effect]) -> usize {
    rests(fx)
        .iter()
        .filter(|c| ***c == RestCall::Unpause)
        .count()
}

fn shape(state: &State) -> String {
    match state {
        State::Member(m) => format!(
            "Member:{}:{}:{}",
            head(&m.link),
            m.local().map_or_else(|| "Empty".to_string(), head),
            head(&m.phase)
        ),
        State::Leaving(Leaving { reason, .. }) => format!("Leaving:{reason:?}"),
        other => head(other),
    }
}

// ---- the rig and seeds ----

#[derive(Clone)]
struct Rig {
    sp: SyncPlay,
    mono: u64,
}

impl Rig {
    fn new() -> Self {
        Rig {
            sp: SyncPlay::default(),
            mono: 100_000,
        }
    }
    fn now(&self) -> Now {
        now_at(self.mono)
    }
    fn server(&self) -> i64 {
        server_at(self.now())
    }
    /// Steps at the current time, then moves the clock 10 ms so the next message is newer.
    fn ev(&mut self, event: Event) -> Vec<Effect> {
        let fx = self.sp.step(event, self.now());
        self.mono += 10;
        fx
    }
    fn wait(&mut self, ms: u64) {
        self.mono += ms;
    }
    fn member(&self) -> &Member {
        member_of(self.sp.state()).expect("a member")
    }
    fn sync(&mut self) -> Vec<Effect> {
        let sample = time_sample(self.member().membership, self.now());
        self.ev(sample)
    }
    fn unpause_now(&mut self, ticks: i64) -> Vec<Effect> {
        let s = self.server();
        self.ev(command(
            SendCommandType::Unpause,
            Some(PID_1),
            Some(ticks),
            Some(s + 500),
            s,
        ))
    }
}

fn idle() -> Rig {
    Rig::new()
}

fn creating() -> Rig {
    let mut rig = Rig::new();
    rig.ev(create());
    rig
}

fn joining() -> Rig {
    let mut rig = Rig::new();
    rig.ev(join_group());
    rig
}

/// A guest just entered: Unsynced, nothing queued.
fn guest_fresh() -> Rig {
    let mut rig = joining();
    let s = rig.server();
    rig.ev(joined(GROUP, &[A, B], GroupStateType::Paused, s - 1_000));
    rig
}

fn guest_loading() -> Rig {
    let mut rig = guest_fresh();
    rig.sync();
    let s = rig.server();
    rig.ev(new_playlist(s));
    rig
}

fn guest_paused() -> Rig {
    let mut rig = guest_loading();
    rig.ev(loaded(PID_1));
    rig
}

/// Synced, playing past its lockout, with member-a and member-b.
fn guest_playing() -> Rig {
    let mut rig = guest_paused();
    rig.unpause_now(0);
    rig.ev(state_update(
        GroupStateType::Playing,
        PlaybackRequestType::Unpause,
    ));
    rig.wait(2_500);
    rig
}

fn guest_down() -> Rig {
    let mut rig = guest_playing();
    rig.ev(Event::BusDisconnected);
    rig
}

fn guest_await() -> Rig {
    let mut rig = guest_playing();
    rig.ev(Event::BusConnected);
    rig
}

fn guest_rejoining() -> Rig {
    let mut rig = guest_playing();
    rig.ev(group_signal(GroupUpdate::NotInGroup));
    rig
}

fn guest_seeking() -> Rig {
    let mut rig = guest_playing();
    let s = rig.server();
    rig.ev(command(
        SendCommandType::Seek,
        Some(PID_1),
        Some(ms(5)),
        Some(s),
        s,
    ));
    rig
}

fn guest_buffering() -> Rig {
    let mut rig = guest_playing();
    rig.ev(Event::BufferingStarted);
    rig
}

fn guest_buffer_sent() -> Rig {
    let mut rig = guest_buffering();
    let token = match rig.member().local() {
        Some(Local::Buffering {
            report: Report::Pending { token },
            ..
        }) => *token,
        other => panic!("not pending: {other:?}"),
    };
    rig.ev(Event::Timer(Timer::BufferingReport { token }));
    rig
}

/// Paused after a reported stall: the BufferingEnded still owes the group a Ready.
fn guest_ready_owed() -> Rig {
    let mut rig = guest_buffer_sent();
    rig.ev(Event::Pause);
    rig
}

fn guest_loading_down() -> Rig {
    let mut rig = guest_loading();
    rig.ev(Event::BusDisconnected);
    rig
}

fn guest_prompt_shown() -> Rig {
    let mut rig = guest_paused();
    rig.ev(stall_timer(&rig));
    rig
}

/// docs/28 §6: our Ready went out for the next item of a paused group, and the prompt is armed.
fn guest_paused_prompt_armed() -> Rig {
    let mut rig = guest_paused();
    let s = rig.server();
    rig.ev(next_item(s));
    rig.ev(loaded(PID_2));
    rig
}

/// docs/28 §7: a Jellyswarrm joiner after its empty new playlist: synced, nothing queued.
fn jellyswarrm_joiner_open() -> Rig {
    let mut rig = guest_fresh();
    rig.sync();
    let s = rig.server();
    rig.ev(play_queue(Why::NewPlaylist, s, &[], -1, 0, false));
    rig
}

fn guest_waiting() -> Rig {
    let mut rig = guest_playing();
    rig.ev(state_update(
        GroupStateType::Waiting,
        PlaybackRequestType::Seek,
    ));
    rig
}

fn leaving() -> Rig {
    let mut rig = guest_playing();
    rig.ev(Event::Leave);
    rig
}

/// The last item ended: back in the lobby, still a member.
fn guest_ended_in_lobby() -> Rig {
    let mut rig = guest_playing();
    rig.ev(Event::PlayerEnded);
    rig
}

fn guest_stopped() -> Rig {
    let mut rig = guest_playing();
    let s = rig.server();
    rig.ev(command(
        SendCommandType::Stop,
        Some(PID_1),
        None,
        Some(s),
        s,
    ));
    rig
}

fn state_without(state: Option<GroupStateType>) -> Event {
    update(
        GROUP,
        GroupUpdate::StateUpdate(GroupStateUpdate {
            reason: Some(PlaybackRequestType::Pause),
            state,
        }),
    )
}

/// Left while Creating, before the server named a group.
fn leaving_ungrouped() -> Rig {
    let mut rig = creating();
    rig.ev(Event::Leave);
    rig
}

/// Loading the second item with a Pause for it held until the landing.
fn guest_loading_held() -> Rig {
    let mut rig = guest_playing();
    let s = rig.server();
    rig.ev(next_item(s));
    rig.ev(command(
        SendCommandType::Pause,
        Some(PID_2),
        Some(ms(1)),
        Some(s),
        s,
    ));
    rig
}

/// The entry timer a Creating or Joining state holds.
fn entry_token(sp: &SyncPlay) -> Option<Tag> {
    match sp.state() {
        State::Creating { timeout, .. } | State::Joining { timeout, .. } => Some(*timeout),
        _ => None,
    }
}

fn entry_timer(rig: &Rig) -> Event {
    Event::Timer(Timer::EntryTimeout {
        token: entry_token(&rig.sp).expect("creating or joining"),
    })
}

/// A join gave up after 10 s and sent its Leave; the Join may still run.
fn joining_timed_out() -> Rig {
    let mut rig = joining();
    rig.wait(ENTRY_TIMEOUT_MS);
    rig.ev(entry_timer(&rig));
    rig
}

fn leave_req(rig: &Rig) -> Tag {
    match rig.sp.state() {
        State::Leaving(Leaving { req, .. }) => *req,
        other => panic!("not leaving: {other:?}"),
    }
}

/// The host in the lobby: synced, member-a only.
fn host_lobby() -> Rig {
    let mut rig = creating();
    let s = rig.server();
    rig.ev(joined(GROUP, &[A], GroupStateType::Idle, s - 1_000));
    rig.sync();
    rig
}

/// The host after Start with member-a and member-b, playing past its lockout.
fn host_playing() -> Rig {
    let mut rig = host_lobby();
    rig.ev(user_joined(B));
    rig.ev(pick_item());
    let s = rig.server();
    rig.ev(new_playlist(s));
    rig.ev(loaded(PID_1));
    rig.unpause_now(0);
    rig.ev(state_update(
        GroupStateType::Playing,
        PlaybackRequestType::Unpause,
    ));
    rig.wait(2_500);
    rig
}

fn snapshot_timer(rig: &Rig) -> Event {
    match rig.member().link {
        Link::AwaitSnapshot { token } => Event::Timer(Timer::SnapshotWindow { token }),
        other => panic!("not awaiting a snapshot: {other:?}"),
    }
}

fn expected_now(rig: &Rig) -> i64 {
    match rig.member().local() {
        Some(Local::Playing(Playing { anchor, .. })) => expected_ticks(*anchor, rig.server()),
        other => panic!("not playing: {other:?}"),
    }
}

// ---- every table row ----

type Row = (
    &'static str,
    fn() -> Rig,
    fn(&Rig) -> Event,
    &'static [&'static str],
    &'static str,
);

/// One row per state and event cell that acts, plus the ignored cells worth pinning.
#[rustfmt::skip]
#[allow(clippy::too_many_lines)]
fn rows() -> Vec<Row> {
    use GroupStateType as G;
    vec![
        ("Create/Idle", idle, |_| create(), &["Rest:New", "Job:EntryTimeout", "Notify"], "Creating"),
        ("Create/Idle reporting Live", idle, |_| create_with(Reporting::Live, Bus::Up), &["Ui:CreateRefused", "Notify"], "Idle"),
        ("Create/Idle bus Down", idle, |_| create_with(Reporting::Idle, Bus::Down), &["Ui:CreateRefused", "Notify"], "Idle"),
        ("Create/Creating", creating, |_| create(), &[], "Creating"),
        ("Create/Member", guest_playing, |_| create(), &[], "Member"),
        ("Create/Leaving", leaving, |_| create(), &[], "Leaving:User"),
        ("Join/Idle", idle, |_| join_group(), &["Rest:Join", "Job:EntryTimeout", "Notify"], "Joining"),
        ("Join/Idle bus Down", idle, |_| join_with(Bus::Down, ME), &["Ui:JoinRefused", "Notify"], "Idle"),
        ("Join/Member", guest_playing, |_| join_group(), &[], "Member"),
        ("GroupJoined/Idle ghost", idle, |r| joined(GROUP, &[A], G::Idle, r.server()), &["Rest:Leave"], "Idle"),
        ("GroupJoined/Creating", creating, |r| joined(GROUP, &[A], G::Idle, r.server()), &["Job:TimeSync", "Ui:Joined", "Notify"], "Member:Connected:Empty:Idle"),
        ("GroupJoined/Joining", joining, |r| joined(GROUP, &[A], G::Waiting, r.server()), &["Job:TimeSync", "Ui:Joined", "Notify"], "Member:Connected:Empty:Waiting"),
        ("GroupJoined/Joining other group", joining, |r| joined(OTHER_GROUP, &[A], G::Idle, r.server()), &[], "Joining"),
        ("GroupJoined/Member re-entry", guest_down, |r| joined(GROUP, &[A, B], G::Playing, r.server()), &["Notify"], "Member:Connected:Playing:Playing"),
        ("GroupJoined/Member other group", guest_down, |r| joined(OTHER_GROUP, &[A], G::Idle, r.server()), &[], "Member:Down"),
        ("GroupJoined/Leaving", leaving, |r| joined(GROUP, &[A], G::Idle, r.server()), &[], "Leaving:User"),
        ("GroupJoined/Leaving ungrouped", leaving_ungrouped, |r| joined(GROUP, &[A], G::Idle, r.server()), &["Rest:Leave"], "Leaving:User"),
        ("GroupJoined/Member re-entry naming a later joiner", guest_down, |r| joined(GROUP, &[A, B, C], G::Playing, r.server()), &["Notify"], "Member:Connected:Playing:Playing"),
        ("PlayQueue/Idle", idle, |r| new_playlist(r.server()), &[], "Idle"),
        ("PlayQueue/Creating", creating, |r| new_playlist(r.server()), &[], "Creating"),
        ("PlayQueue/Member not newer", guest_playing, |r| play_queue(Why::NewPlaylist, r.server() - 60_000, &[(ITEM_2, PID_2)], 0, 0, false), &[], "Member:Connected:Playing"),
        ("PlayQueue/Member empty", guest_playing, |r| play_queue(Why::RemoveItems, r.server(), &[], -1, 0, false), &["Dir:ReturnToLobby", "Notify"], "Member:Connected:Empty"),
        ("PlayQueue/Member empty nothing loaded", host_lobby, |r| play_queue(Why::RemoveItems, r.server(), &[], -1, 0, false), &[], "Member:Connected:Empty"),
        ("PlayQueue/Member new pid", guest_playing, |r| next_item(r.server()), &["Dir:Load", "Notify"], "Member:Connected:Loading:Playing"),
        ("PlayQueue/Member same pid NewPlaylist", guest_playing, |r| new_playlist(r.server()), &["Dir:Seek", "Notify"], "Member:Connected:Seeking:Playing"),
        ("PlayQueue/Member same pid SetCurrentItem", guest_playing, |r| play_queue(Why::SetCurrentItem, r.server(), &[(ITEM_1, PID_1)], 0, 0, false), &["Dir:Seek", "Notify"], "Member:Connected:Seeking:Playing"),
        ("PlayQueue/Member same pid other reason", guest_playing, |r| play_queue(Why::Queue, r.server(), &[(ITEM_1, PID_1), (ITEM_2, PID_2)], 0, 0, false), &["Notify"], "Member:Connected:Playing"),
        ("PlayQueue/Member unsynced waits", guest_fresh, |r| new_playlist(r.server()), &[], "Member:Connected:Empty"),
        ("PlayQueue/Leaving", leaving, |r| new_playlist(r.server()), &[], "Leaving:User"),
        ("Command/Idle", idle, |r| command(SendCommandType::Pause, Some(PID_1), Some(0), Some(r.server()), r.server()), &[], "Idle"),
        ("Command/Creating", creating, |r| command(SendCommandType::Stop, None, None, Some(r.server()), r.server()), &[], "Creating"),
        ("Command/Member emitted before entry", guest_playing, |r| command(SendCommandType::Pause, Some(PID_1), Some(0), Some(r.server()), r.server() - 600_000), &[], "Member:Connected:Playing"),
        ("Command/Member unsynced queues", guest_fresh, |r| command(SendCommandType::Pause, Some(PID_1), Some(0), Some(r.server()), r.server()), &[], "Member:Connected:Empty"),
        ("Command/Member other pid", guest_playing, |r| command(SendCommandType::Pause, Some(PID_2), Some(0), Some(r.server()), r.server()), &[], "Member:Connected:Playing"),
        ("Command/Member Pause", guest_playing, |r| command(SendCommandType::Pause, Some(PID_1), Some(0), Some(r.server()), r.server()), &["Dir:Pause", "Notify"], "Member:Connected:Paused"),
        ("Command/Member Unpause", guest_paused, |r| command(SendCommandType::Unpause, Some(PID_1), Some(0), Some(r.server() + 500), r.server()), &["Dir:Unpause", "Notify"], "Member:Connected:Playing"),
        ("Command/Member Seek", guest_playing, |r| command(SendCommandType::Seek, Some(PID_1), Some(0), Some(r.server()), r.server()), &["Dir:Seek", "Notify"], "Member:Connected:Seeking"),
        ("Command/Member Stop nothing loaded", host_lobby, |r| command(SendCommandType::Stop, None, None, Some(r.server()), r.server()), &[], "Member:Connected:Empty"),
        ("Command/Member Stop", guest_playing, |r| command(SendCommandType::Stop, Some(PID_2), None, Some(r.server()), r.server()), &["Dir:ReturnToLobby", "Notify"], "Member:Connected:Stopped"),
        ("Command/Leaving", leaving, |r| command(SendCommandType::Stop, None, None, Some(r.server()), r.server()), &[], "Leaving:User"),
        ("UserJoined/Idle", idle, |_| user_joined(C), &[], "Idle"),
        ("UserJoined/Member lobby", host_lobby, |_| user_joined(B), &["Ui:UserJoined(Name(..))", "Notify"], "Member"),
        ("UserJoined/Member listed name reconnecting", guest_playing, |_| user_joined(B), &[], "Member:Connected:Playing:Playing"),
        ("UserJoined/Member after start", guest_playing, |_| user_joined(C), &["Ui:UserJoined(Name(..))", "Notify"], "Member:Connected:Playing:Playing"),
        ("UserJoined/Leaving", leaving, |_| user_joined(C), &[], "Leaving:User"),
        ("UserLeft/Member", guest_playing, |_| update(GROUP, GroupUpdate::UserLeft(B.into())), &["Ui:UserLeft(Name(..))", "Notify"], "Member"),
        ("UserLeft/Idle", idle, |_| update(GROUP, GroupUpdate::UserLeft(B.into())), &[], "Idle"),
        ("UserLeft/Member host in the lobby", jellyswarrm_joiner_open, |_| user_left(A), &["Ui:UserLeft(Name(..))", "Ui:HostLeft", "Rest:Leave", "CancelScope", "Notify"], "Leaving:HostLeft"),
        ("UserLeft/Member host after the queue ended", guest_ended_in_lobby, |_| user_left(A), &["Ui:UserLeft(Name(..))", "Ui:HostLeft", "Rest:Leave", "CancelScope", "Notify"], "Leaving:HostLeft"),
        ("UserLeft/Member host while playing", guest_playing, |_| user_left(A), &["Ui:UserLeft(Name(..))", "Notify"], "Member:Connected:Playing"),
        ("UserLeft/Member host before the clock syncs", guest_fresh, |_| user_left(A), &["Ui:UserLeft(Name(..))", "Notify"], "Member:Connected:Empty"),
        ("UserLeft/Host member-a", host_lobby, |_| user_left(A), &["Ui:UserLeft(Name(..))", "Notify"], "Member:Connected:Empty"),
        ("GroupLeft/Joining", joining, |_| group_signal(GroupUpdate::GroupLeft), &[], "Joining"),
        ("GroupLeft/Member", guest_playing, |_| group_signal(GroupUpdate::GroupLeft), &["CancelScope", "Dir:Detach", "Ui:GroupEnded(Removed)", "Notify"], "Idle"),
        ("GroupLeft/Member rejoining", guest_rejoining, |_| group_signal(GroupUpdate::GroupLeft), &[], "Member:Rejoining"),
        ("GroupLeft/Leaving", leaving, |_| group_signal(GroupUpdate::GroupLeft), &["Notify"], "Idle"),
        ("NotInGroup/Joining", joining, |_| group_signal(GroupUpdate::NotInGroup), &[], "Joining"),
        ("NotInGroup/Member connected", guest_playing, |_| group_signal(GroupUpdate::NotInGroup), &["Rest:Join", "Notify"], "Member:Rejoining"),
        ("NotInGroup/Member down", guest_down, |_| group_signal(GroupUpdate::NotInGroup), &[], "Member:Down"),
        ("NotInGroup/Leaving", leaving, |_| group_signal(GroupUpdate::NotInGroup), &["Notify"], "Idle"),
        ("GroupDoesNotExist/Joining", joining, |_| group_signal(GroupUpdate::GroupDoesNotExist), &["Ui:NoSuchGroup", "Notify"], "Idle"),
        ("GroupDoesNotExist/Creating", creating, |_| group_signal(GroupUpdate::GroupDoesNotExist), &[], "Creating"),
        ("GroupDoesNotExist/Member rejoining", guest_rejoining, |_| group_signal(GroupUpdate::GroupDoesNotExist), &["CancelScope", "Dir:Detach", "Ui:GroupEnded(Gone)", "Notify"], "Idle"),
        ("GroupDoesNotExist/Member connected", guest_playing, |_| group_signal(GroupUpdate::GroupDoesNotExist), &[], "Member:Connected"),
        ("GroupDoesNotExist/Leaving", leaving, |_| group_signal(GroupUpdate::GroupDoesNotExist), &[], "Leaving:User"),
        ("LibraryAccessDenied/Joining", joining, |_| group_signal(GroupUpdate::LibraryAccessDenied), &["Ui:NoAccess", "Notify"], "Idle"),
        ("LibraryAccessDenied/Member rejoining", guest_rejoining, |_| group_signal(GroupUpdate::LibraryAccessDenied), &["CancelScope", "Dir:Detach", "Ui:GroupEnded(Gone)", "Notify"], "Idle"),
        ("LibraryAccessDenied/Member connected", guest_playing, |_| group_signal(GroupUpdate::LibraryAccessDenied), &[], "Member:Connected"),
        ("GroupDoesNotExist nil id/Creating", creating, |_| error_reply(GroupUpdate::GroupDoesNotExist), &[], "Creating"),
        ("GroupDoesNotExist nil id/Joining", joining, |_| error_reply(GroupUpdate::GroupDoesNotExist), &["Ui:NoSuchGroup", "Notify"], "Idle"),
        ("GroupDoesNotExist nil id/Member rejoining", guest_rejoining, |_| error_reply(GroupUpdate::GroupDoesNotExist), &["CancelScope", "Dir:Detach", "Ui:GroupEnded(Gone)", "Notify"], "Idle"),
        ("GroupDoesNotExist nil id/Member connected", guest_playing, |_| error_reply(GroupUpdate::GroupDoesNotExist), &[], "Member:Connected"),
        ("GroupDoesNotExist nil id/Leaving", leaving, |_| error_reply(GroupUpdate::GroupDoesNotExist), &[], "Leaving:User"),
        ("LibraryAccessDenied nil id/Creating", creating, |_| error_reply(GroupUpdate::LibraryAccessDenied), &[], "Creating"),
        ("LibraryAccessDenied nil id/Joining", joining, |_| error_reply(GroupUpdate::LibraryAccessDenied), &["Ui:NoAccess", "Notify"], "Idle"),
        ("LibraryAccessDenied nil id/Member rejoining", guest_rejoining, |_| error_reply(GroupUpdate::LibraryAccessDenied), &["CancelScope", "Dir:Detach", "Ui:GroupEnded(Gone)", "Notify"], "Idle"),
        ("LibraryAccessDenied nil id/Member connected", guest_playing, |_| error_reply(GroupUpdate::LibraryAccessDenied), &[], "Member:Connected"),
        ("LibraryAccessDenied nil id/Leaving", leaving, |_| error_reply(GroupUpdate::LibraryAccessDenied), &[], "Leaving:User"),
        ("NotInGroup nil id/Creating", creating, |_| error_reply(GroupUpdate::NotInGroup), &[], "Creating"),
        ("NotInGroup nil id/Joining", joining, |_| error_reply(GroupUpdate::NotInGroup), &[], "Joining"),
        ("NotInGroup nil id/Member rejoining", guest_rejoining, |_| error_reply(GroupUpdate::NotInGroup), &[], "Member:Rejoining"),
        ("NotInGroup nil id/Member connected", guest_playing, |_| error_reply(GroupUpdate::NotInGroup), &["Rest:Join", "Notify"], "Member:Rejoining"),
        ("NotInGroup nil id/Leaving", leaving, |_| error_reply(GroupUpdate::NotInGroup), &["Notify"], "Idle"),
        ("StateUpdate/Idle", idle, |_| state_update(G::Paused, PlaybackRequestType::Pause), &[], "Idle"),
        ("StateUpdate/Member Paused", host_playing, |_| state_update(G::Paused, PlaybackRequestType::Pause), &["Notify"], "Member:Connected:Playing:Paused"),
        ("StateUpdate/Member enters Waiting", guest_playing, |_| state_update(G::Waiting, PlaybackRequestType::Buffer), &["Notify"], "Member:Connected:Playing:Waiting"),
        ("StateUpdate/Member stays Waiting", guest_waiting, |_| state_update(G::Waiting, PlaybackRequestType::Ready), &[], "Member:Connected:Playing:Waiting"),
        ("StateUpdate/Leaving", leaving, |_| state_update(G::Paused, PlaybackRequestType::Pause), &[], "Leaving:User"),
        ("BusDisconnected/Idle", idle, |_| Event::BusDisconnected, &[], "Idle"),
        ("BusDisconnected/Creating", creating, |_| Event::BusDisconnected, &["Ui:StartFailed", "Notify"], "Idle"),
        ("BusDisconnected/Joining", joining, |_| Event::BusDisconnected, &["Ui:StartFailed", "Notify"], "Idle"),
        ("BusDisconnected/Member", guest_playing, |_| Event::BusDisconnected, &["Notify"], "Member:Down:Playing"),
        ("BusDisconnected/Leaving", leaving, |_| Event::BusDisconnected, &[], "Leaving:User"),
        ("BusConnected/Idle", idle, |_| Event::BusConnected, &[], "Idle"),
        ("BusConnected/Member connected", guest_playing, |_| Event::BusConnected, &["Job:SnapshotWindow", "Notify"], "Member:AwaitSnapshot"),
        ("BusConnected/Member down", guest_down, |_| Event::BusConnected, &["Job:SnapshotWindow", "Notify"], "Member:AwaitSnapshot"),
        ("BusConnected/Member rejoining", guest_rejoining, |_| Event::BusConnected, &["Job:SnapshotWindow", "Notify"], "Member:AwaitSnapshot"),
        ("BusConnected/Leaving", leaving, |_| Event::BusConnected, &[], "Leaving:User"),
        ("BusUnauthorized/Idle", idle, |_| Event::BusUnauthorized, &[], "Idle"),
        ("BusUnauthorized/Joining", joining, |_| Event::BusUnauthorized, &["Notify"], "Idle"),
        ("BusUnauthorized/Member", guest_playing, |_| Event::BusUnauthorized, &["CancelScope", "Dir:Detach", "Ui:GroupEnded(Auth)", "Notify"], "Idle"),
        ("BusUnauthorized/Leaving", leaving, |_| Event::BusUnauthorized, &["Notify"], "Idle"),
        ("SnapshotWindow/Member current", guest_await, snapshot_timer, &["Rest:Join", "Notify"], "Member:Rejoining"),
        ("SnapshotWindow/Member stale token", guest_await, |_| Event::Timer(Timer::SnapshotWindow { token: Tag(0) }), &[], "Member:AwaitSnapshot"),
        ("CreateFailed/Idle", idle, |_| Event::EntryFailed { req: Tag(1) }, &[], "Idle"),
        ("CreateFailed/Creating", creating, |r| Event::EntryFailed { req: match r.sp.state() { State::Creating { req, .. } => *req, _ => unreachable!() } }, &["Ui:StartFailed", "Notify"], "Idle"),
        ("JoinFailed/Joining", joining, |r| Event::EntryFailed { req: match r.sp.state() { State::Joining { req, .. } => *req, _ => unreachable!() } }, &["Ui:StartFailed", "Notify"], "Idle"),
        ("JoinFailed/Joining stale", joining, |_| Event::EntryFailed { req: Tag(0) }, &[], "Joining"),
        ("JoinFailed/Member rejoining", guest_rejoining, |r| Event::EntryFailed { req: match r.member().link { Link::Rejoining { req } => req, _ => unreachable!() } }, &["CancelScope", "Dir:Detach", "Ui:GroupEnded(Lost)", "Notify"], "Idle"),
        ("LeaveDone/Leaving", leaving, |r| Event::RequestDone { req: match r.sp.state() { State::Leaving(Leaving { req, .. }) => *req, _ => unreachable!() } }, &["Notify"], "Idle"),
        ("LeaveDone/Leaving stale", leaving, |_| Event::RequestDone { req: Tag(0) }, &[], "Leaving:User"),
        ("TimeSample/Member unsynced", guest_fresh, |r| time_sample(r.member().membership, r.now()), &["Rest:Ping"], "Member:Connected:Empty"),
        ("TimeSample/Member synced", guest_playing, |r| time_sample(r.member().membership, r.now()), &["Rest:Ping"], "Member:Connected:Playing"),
        ("TimeSample/Member stale membership", guest_playing, |r| time_sample(Tag(0), r.now()), &[], "Member:Connected:Playing"),
        ("TimeSample/Idle", idle, |r| time_sample(Tag(1), r.now()), &[], "Idle"),
        ("ItemLoaded/Member loading", guest_loading, |_| loaded(PID_1), &["Rest:Ready", "Job:StallPrompt", "Notify"], "Member:Connected:Paused"),
        ("ItemLoaded/Member other pid", guest_loading, |_| loaded(PID_2), &[], "Member:Connected:Loading"),
        ("Command/Member loading holds", guest_loading, |r| command(SendCommandType::Unpause, Some(PID_1), Some(0), Some(r.server()), r.server()), &[], "Member:Connected:Loading"),
        ("ItemLoaded/Member loading with a held command", guest_loading_held, |_| loaded(PID_2), &["Rest:Ready", "Dir:Pause", "Notify"], "Member:Connected:Paused"),
        ("ItemLoaded/Member not loading", guest_playing, |_| loaded(PID_1), &[], "Member:Connected:Playing"),
        ("SeekLanded/Member seeking", guest_seeking, |r| Event::SeekLanded { seq: match r.member().local() { Some(Local::Seeking { seq, .. }) => *seq, _ => unreachable!() }, landed_ticks: ms(5) }, &["Rest:Ready", "Job:StallPrompt", "Notify"], "Member:Connected:Paused"),
        ("SeekLanded/Member stale seq", guest_seeking, |_| Event::SeekLanded { seq: Tag(0), landed_ticks: 0 }, &[], "Member:Connected:Seeking"),
        ("BufferingStarted/Member playing", guest_playing, |_| Event::BufferingStarted, &["Job:BufferingReport", "Notify"], "Member:Connected:Buffering"),
        ("BufferingStarted/Member paused", guest_paused, |_| Event::BufferingStarted, &[], "Member:Connected:Paused"),
        ("BufferingReportDue/Member pending", guest_buffering, |r| Event::Timer(match r.member().local() { Some(Local::Buffering { report: Report::Pending { token }, .. }) => Timer::BufferingReport { token: *token }, _ => unreachable!() }), &["Rest:Buffering"], "Member:Connected:Buffering"),
        ("BufferingReportDue/Member stale", guest_buffering, |_| Event::Timer(Timer::BufferingReport { token: Tag(0) }), &[], "Member:Connected:Buffering"),
        ("BufferingEnded/Member pending", guest_buffering, |_| Event::BufferingEnded { position_ticks: 0 }, &["Notify"], "Member:Connected:Playing"),
        ("BufferingEnded/Member ready owed", guest_ready_owed, |_| Event::BufferingEnded { position_ticks: 0 }, &["Rest:Ready", "Job:StallPrompt", "Notify"], "Member:Connected:Paused"),
        ("StallPrompt/Member armed", guest_paused, stall_timer, &["Notify"], "Member:Connected:Paused"),
        ("StallPrompt/Member stale token", guest_paused, |_| Event::Timer(Timer::StallPrompt { token: Tag(0) }), &[], "Member:Connected:Paused"),
        ("StallPrompt/Member playing", guest_playing, |_| Event::Timer(Timer::StallPrompt { token: Tag(0) }), &[], "Member:Connected:Playing"),
        ("ItemLoaded/Member loading link down", guest_loading_down, |_| loaded(PID_1), &["Notify"], "Member:Down:Paused"),
        ("Command/Member other pid clears a shown prompt", guest_prompt_shown, |r| command(SendCommandType::Pause, Some(PID_2), Some(0), Some(r.server()), r.server()), &["Notify"], "Member:Connected:Paused"),
        ("BufferingEnded/Member sent", guest_buffer_sent, |_| Event::BufferingEnded { position_ticks: 0 }, &["Rest:Ready", "Notify"], "Member:Connected:Playing"),
        ("PlaybackError/Member fatal", guest_playing, |_| Event::PlaybackError(Severity::Fatal), &["Rest:Leave", "CancelScope", "Notify"], "Leaving:PlayerError"),
        ("PlaybackError/Member recoverable", guest_playing, |_| Event::PlaybackError(Severity::Recoverable), &["Job:BufferingReport", "Notify"], "Member:Connected:Buffering"),
        ("PlayerEnded/Member last item", guest_playing, |_| Event::PlayerEnded, &["Dir:ReturnToLobby", "Notify"], "Member:Connected:Stopped"),
        ("PlayerEnded/Idle", idle, |_| Event::PlayerEnded, &[], "Idle"),
        ("PlayerClosed/Creating", creating, |_| Event::PlayerClosed, &["Rest:Leave", "Notify"], "Leaving:User"),
        ("PlayerClosed/Member", guest_playing, |_| Event::PlayerClosed, &["Rest:Leave", "CancelScope", "Notify"], "Leaving:User"),
        ("PlayerClosed/Leaving", leaving, |_| Event::PlayerClosed, &[], "Leaving:User"),
        ("PositionSample/Member behind", guest_playing, |r| Event::PositionSample { position_ticks: expected_now(r) - ms(600) }, &["Dir:Correct"], "Member:Connected:Playing"),
        ("PositionSample/Member ahead", guest_playing, |r| Event::PositionSample { position_ticks: expected_now(r) + ms(600) }, &["Dir:HoldFor"], "Member:Connected:Playing"),
        ("PositionSample/Member in sync", guest_playing, |r| Event::PositionSample { position_ticks: expected_now(r) + ms(300) }, &[], "Member:Connected:Playing"),
        ("PositionSample/Member buffering", guest_buffering, |_| Event::PositionSample { position_ticks: 0 }, &[], "Member:Connected:Buffering"),
        ("Pause/Member connected", guest_playing, |_| Event::Pause, &["Dir:Pause", "Rest:Pause", "Notify"], "Member:Connected:Paused"),
        ("Pause/Member down", guest_down, |_| Event::Pause, &["Dir:Pause", "Notify"], "Member:Down:Paused"),
        ("Pause/Leaving", leaving, |_| Event::Pause, &[], "Leaving:User"),
        ("Unpause/Member connected", guest_paused, |_| Event::Unpause, &["Rest:Unpause"], "Member:Connected:Paused"),
        ("Unpause/Member waiting", guest_waiting, |_| Event::Unpause, &["Rest:Unpause"], "Member:Connected:Playing:Waiting"),
        ("Unpause/Member down", guest_down, |_| Event::Unpause, &["Ui:Reconnecting", "Notify"], "Member:Down"),
        ("Seek/Member", guest_playing, |_| Event::Seek { ticks: ms(9) }, &["Rest:Seek"], "Member:Connected:Playing"),
        ("Seek/Leaving", leaving, |_| Event::Seek { ticks: ms(9) }, &[], "Leaving:User"),
        ("PickItem/Member guest", guest_playing, |_| pick_item(), &["Rest:SetNewQueue"], "Member"),
        ("PickItem/Member host lobby", host_lobby, |_| pick_item(), &["Rest:SetNewQueue"], "Member"),
        ("Queue/Member", guest_playing, |_| Event::Queue(AddToQueue { items: vec![ITEM_2], mode: GroupQueueMode::QueueNext }), &["Rest:Queue"], "Member"),
        ("Next/Member", guest_playing, |_| Event::Next, &["Rest:NextItem"], "Member"),
        ("Next/Member empty queue", host_lobby, |_| Event::Next, &[], "Member"),
        ("Prev/Member", guest_playing, |_| Event::Prev, &["Rest:PreviousItem"], "Member"),
        ("Leave/Joining", joining, |_| Event::Leave, &["Rest:Leave", "Notify"], "Leaving:User"),
        ("Leave/Member", guest_playing, |_| Event::Leave, &["Rest:Leave", "CancelScope", "Dir:Detach", "Notify"], "Leaving:User"),
        ("Leave/Leaving", leaving, |_| Event::Leave, &[], "Leaving:User"),
        ("AccountReset/Idle", idle, |_| Event::AccountReset, &[], "Idle"),
        ("AccountReset/Creating", creating, |_| Event::AccountReset, &["Rest:Leave", "Notify"], "Idle"),
        ("AccountReset/Member", guest_playing, |_| Event::AccountReset, &["Rest:Leave", "CancelScope", "Dir:Detach", "Notify"], "Idle"),
        ("AccountReset/Leaving", leaving, |_| Event::AccountReset, &["Notify"], "Idle"),
        ("NotInGroup/Member awaiting snapshot", guest_await, |_| group_signal(GroupUpdate::NotInGroup), &[], "Member:AwaitSnapshot"),
        ("NotInGroup/Member already rejoining", guest_rejoining, |_| group_signal(GroupUpdate::NotInGroup), &[], "Member:Rejoining"),
        ("Command/Member Pause when ended", guest_ended_in_lobby, |r| command(SendCommandType::Pause, Some(PID_1), Some(0), Some(r.server()), r.server()), &[], "Member:Connected:Stopped"),
        ("Command/Member Seek when ended", guest_ended_in_lobby, |r| command(SendCommandType::Seek, Some(PID_1), Some(0), Some(r.server()), r.server()), &[], "Member:Connected:Stopped"),
        ("PlayQueue/Member same pid when ended", guest_ended_in_lobby, |r| new_playlist(r.server()), &["Dir:Load", "Notify"], "Member:Connected:Loading"),
        ("PlayQueue/Member new pid when ended", guest_ended_in_lobby, |r| play_queue(Why::NewPlaylist, r.server(), &[(ITEM_2, PID_2)], 0, 0, false), &["Dir:Load", "Notify"], "Member:Connected:Loading"),
        ("PlayQueue/Member empty when ended", guest_ended_in_lobby, |r| play_queue(Why::RemoveItems, r.server(), &[], -1, 0, false), &["Notify"], "Member:Connected:Empty"),
        ("PlayerEnded/Member ended", guest_ended_in_lobby, |_| Event::PlayerEnded, &[], "Member:Connected:Stopped"),
        ("PlayerClosed/Member ended", guest_ended_in_lobby, |_| Event::PlayerClosed, &[], "Member:Connected:Stopped"),
        ("PlayerClosed/Member empty queue", host_lobby, |_| Event::PlayerClosed, &[], "Member:Connected:Empty"),
        ("Pause/Member ended", guest_ended_in_lobby, |_| Event::Pause, &[], "Member:Connected:Stopped"),
        ("Unpause/Member ended", guest_ended_in_lobby, |_| Event::Unpause, &[], "Member:Connected:Stopped"),
        ("Seek/Member empty queue", host_lobby, |_| Event::Seek { ticks: ms(9) }, &[], "Member:Connected:Empty"),
        ("Command/Member Stop when stopped", guest_stopped, |r| command(SendCommandType::Stop, Some(PID_1), None, Some(r.server()), r.server()), &[], "Member:Connected:Stopped"),
        ("PlayQueue/Member empty when stopped", guest_stopped, |r| play_queue(Why::RemoveItems, r.server(), &[], -1, 0, false), &["Notify"], "Member:Connected:Empty"),
        ("PlayQueue/Member index past the end", guest_playing, |r| play_queue(Why::RemoveItems, r.server(), &[(ITEM_1, PID_1)], 5, 0, false), &["Dir:ReturnToLobby", "Notify"], "Member:Connected:Empty"),
        ("StateUpdate/Member without a state", host_playing, |_| state_without(None), &[], "Member:Connected:Playing:Playing"),
        ("StateUpdate/Member unknown state", host_playing, |_| state_without(Some(GroupStateType::Unrecognized)), &[], "Member:Connected:Playing:Playing"),
        ("Command/Member Unpause other pid", guest_paused, |r| command(SendCommandType::Unpause, Some(PID_2), Some(0), Some(r.server()), r.server()), &[], "Member:Connected:Paused"),
        ("EntryTimeout/Creating", creating, entry_timer, &["Rest:Leave", "Ui:StartFailed", "Notify"], "Leaving:User"),
        ("EntryTimeout/Joining", joining, entry_timer, &["Rest:Leave", "Ui:StartFailed", "Notify"], "Leaving:User"),
        ("EntryTimeout/Joining stale token", joining, |_| Event::Timer(Timer::EntryTimeout { token: Tag(0) }), &[], "Joining"),
        ("EntryTimeout/Idle", idle, |_| Event::Timer(Timer::EntryTimeout { token: Tag(1) }), &[], "Idle"),
        ("GroupJoined/Leaving after a join timed out", joining_timed_out, |r| joined(GROUP, &[A], G::Idle, r.server()), &["Rest:Leave"], "Leaving:User"),
        ("GroupJoined/Leaving after a join timed out other group", joining_timed_out, |r| joined(OTHER_GROUP, &[A], G::Idle, r.server()), &[], "Leaving:User"),
        ("Command/Member Seek other pid", guest_playing, |r| command(SendCommandType::Seek, Some(PID_2), Some(0), Some(r.server()), r.server()), &[], "Member:Connected:Playing"),
    ]
}

#[test]
fn every_table_row_emits_its_effects_and_lands_in_its_state() {
    let mut failures = Vec::new();
    for (name, seed, event, want_fx, want_state) in rows() {
        let mut rig = seed();
        let event = event(&rig);
        let fx = rig.ev(event);
        let (got_fx, got_state) = (labels(&fx), shape(rig.sp.state()));
        // A leave reason is part of the state; elsewhere the row names the prefix it pins.
        let state_ok = if want_state.starts_with("Leaving") {
            got_state == want_state
        } else {
            got_state.starts_with(want_state)
        };
        if !same_effects(&got_fx, want_fx) || !state_ok {
            failures.push(format!(
                "{name}: got {got_fx:?} -> {got_state}, want {want_fx:?} -> {want_state}"
            ));
        }
    }
    assert!(failures.is_empty(), "\n{}", failures.join("\n"));
}

// ---- named sequences ----

/// docs/28 §4: a member whose socket came back is announced again without a leave; it stays listed once.
#[test]
fn a_listed_name_joining_again_stays_listed_once() {
    let mut rig = host_lobby();
    rig.ev(user_joined(B));
    let before = rig.member().participants.clone();
    assert!(
        rig.ev(user_joined(B)).is_empty(),
        "no notice for a reconnect"
    );
    assert_eq!(rig.member().participants, before);
    rig.ev(user_left(B));
    assert_eq!(
        rig.member().participants,
        vec![Name(A.into())],
        "one leave clears it"
    );
}

/// docs/28 §3: anyone with access may join a playing group; it is a notice, never a leave.
#[test]
fn a_name_joining_after_start_is_a_notice_not_a_leave() {
    let mut rig = guest_playing();
    let membership = rig.member().membership;
    let fx = rig.ev(user_joined(C));
    assert_eq!(labels(&fx), ["Ui:UserJoined(Name(..))", "Notify"]);
    assert_eq!(
        rig.member().membership,
        membership,
        "still the same membership"
    );
    assert_eq!(
        rig.member().participants,
        [Name(A.into()), Name(B.into()), Name(C.into())]
    );
    assert!(matches!(rig.member().local(), Some(Local::Playing(_))));
}

/// docs/28 §4: a rejoin snapshot naming someone who joined while our socket was down re-enters.
#[test]
fn a_rejoin_snapshot_with_a_later_joiner_re_enters_without_leaving() {
    let mut rig = guest_down();
    let membership = rig.member().membership;
    let s = rig.server();
    let fx = rig.ev(joined(GROUP, &[A, B, C], GroupStateType::Playing, s));
    assert_eq!(labels(&fx), ["Notify"]);
    assert_eq!(rig.member().membership, membership);
    assert_eq!(rig.member().link, Link::Connected);
    assert_eq!(rig.member().cursor, Cursor::Reset);
    assert_eq!(
        rig.member().participants,
        [Name(A.into()), Name(B.into()), Name(C.into())]
    );
}

/// 12.0 removes a dropped member and its rejoin snapshot repeats the old LastUpdate.
#[test]
fn a_12_0_rejoin_snapshot_with_the_old_last_update_is_applied() {
    let mut rig = guest_playing();
    let Cursor::Applied(old) = rig.member().cursor else {
        panic!("cursor applied")
    };
    rig.ev(Event::BusDisconnected);
    let fx = rig.ev(Event::BusConnected);
    let fx = rig.ev(Event::Timer(timer_in(&fx)));
    assert_eq!(rests(&fx), [&RestCall::Join { group: GROUP }]);
    let s = rig.server();
    rig.ev(joined(GROUP, &[A], GroupStateType::Waiting, s));
    let fx = rig.ev(play_queue(
        Why::NewPlaylist,
        old,
        &[(ITEM_1, PID_1)],
        0,
        ms(7),
        false,
    ));
    let (seq, seek) = dirs(&fx)[0];
    assert_eq!(
        seek,
        Directive::Seek {
            position_ticks: ms(7)
        }
    );
    let fx = rig.ev(Event::SeekLanded {
        seq,
        landed_ticks: ms(7),
    });
    assert!(
        matches!(rests(&fx)[..], [RestCall::Ready(PlayerReport { position_ticks, pid: PID_1, is_playing: false, .. })] if *position_ticks == ms(7)),
        "{fx:?}"
    );
}

#[test]
fn a_12_0_reconnect_as_the_last_member_ends_the_group() {
    let mut rig = guest_await();
    let fx = rig.ev(snapshot_timer(&rig));
    assert_eq!(labels(&fx), ["Rest:Join", "Notify"]);
    let fx = rig.ev(group_signal(GroupUpdate::GroupDoesNotExist));
    assert_eq!(
        labels(&fx),
        ["CancelScope", "Dir:Detach", "Ui:GroupEnded(Gone)", "Notify"]
    );
}

#[test]
fn a_jellyswarrm_reconnect_inside_the_grace_needs_no_join() {
    let mut rig = guest_down();
    let window = timer_in(&rig.ev(Event::BusConnected));
    let s = rig.server();
    rig.ev(joined(GROUP, &[A, B], GroupStateType::Playing, s));
    assert_eq!(rig.member().link, Link::Connected);
    assert_eq!(
        rig.ev(Event::Timer(window)),
        [],
        "the window is stale once the snapshot came"
    );
}

#[test]
fn a_late_jellyswarrm_snapshot_wins_over_the_join_it_raced() {
    let mut rig = guest_await();
    let Link::AwaitSnapshot { token } = rig.member().link else {
        panic!("awaiting")
    };
    let join = req_in(&rig.ev(Event::Timer(Timer::SnapshotWindow { token })));
    let s = rig.server();
    rig.ev(joined(GROUP, &[A, B], GroupStateType::Playing, s));
    assert_eq!(rig.ev(Event::EntryFailed { req: join }), []);
    assert_eq!(rig.member().link, Link::Connected);
}

#[test]
fn an_account_switch_mid_group_leaves_once_on_the_outgoing_client() {
    for seed in [creating as fn() -> Rig, joining, guest_playing, host_lobby] {
        let mut rig = seed();
        let fx = rig.ev(Event::AccountReset);
        assert_eq!(rests(&fx), [&RestCall::Leave]);
        assert_eq!(rig.sp.state(), &State::Idle);
    }
    let mut rig = leaving();
    assert!(rests(&rig.ev(Event::AccountReset)).is_empty());
}

#[test]
fn stale_commands_are_dropped() {
    let mut rig = guest_playing();
    let s = rig.server();
    let other_group = Event::Server(SyncPlayEvent::Command(SendCommand {
        group_id: Some(OTHER_GROUP),
        ..command_fields(SendCommandType::Pause, s)
    }));
    for event in [
        command(
            SendCommandType::Pause,
            Some(PID_1),
            Some(0),
            Some(s),
            s - 600_000,
        ),
        command(SendCommandType::Pause, Some(PID_2), Some(0), Some(s), s),
        other_group,
    ] {
        assert_eq!(rig.ev(event), []);
    }
}

/// docs/28 §4: the generated model is all-optional, so a command short of what it acts on is ignored.
#[test]
fn a_command_missing_its_kind_time_or_item_is_stale() {
    let rig = guest_playing();
    let s = rig.server();
    let full = command_fields(SendCommandType::Pause, s);
    for broken in [
        SendCommand {
            command: None,
            ..full.clone()
        },
        SendCommand {
            when: None,
            ..full.clone()
        },
        SendCommand {
            playlist_item_id: None,
            ..full.clone()
        },
        SendCommand {
            position_ticks: None,
            ..full
        },
        SendCommand {
            when: None,
            ..command_fields(SendCommandType::Stop, s)
        },
    ] {
        let mut rig = rig.clone();
        assert_eq!(rig.ev(Event::Server(SyncPlayEvent::Command(broken))), []);
    }
    let mut stop = rig;
    let without_item = SendCommand {
        playlist_item_id: None,
        ..command_fields(SendCommandType::Stop, s)
    };
    assert_eq!(
        labels(&stop.ev(Event::Server(SyncPlayEvent::Command(without_item)))),
        ["Dir:ReturnToLobby", "Notify"],
        "Stop needs no item"
    );
}

#[test]
fn an_inbound_seek_storm_reports_ready_once_for_the_latest_seek() {
    let mut rig = guest_playing();
    let s = rig.server();
    let seek = |ticks| command(SendCommandType::Seek, Some(PID_1), Some(ticks), Some(s), s);
    let a = dirs(&rig.ev(seek(ms(1))))[0].0;
    assert!(
        dirs(&rig.ev(seek(ms(2)))).is_empty(),
        "the second seek waits for the first landing"
    );
    let fx = rig.ev(Event::SeekLanded {
        seq: a,
        landed_ticks: ms(1),
    });
    assert!(
        rests(&fx).is_empty(),
        "superseded: no Ready for the first landing"
    );
    let (b, directive) = dirs(&fx)[0];
    assert_eq!(
        directive,
        Directive::Seek {
            position_ticks: ms(2)
        }
    );
    assert_eq!(
        rig.ev(Event::SeekLanded {
            seq: a,
            landed_ticks: ms(1)
        }),
        []
    );
    let fx = rig.ev(Event::SeekLanded {
        seq: b,
        landed_ticks: ms(2),
    });
    assert_eq!(labels(&fx), ["Rest:Ready", "Job:StallPrompt", "Notify"]);
    assert_eq!(
        rig.ev(Event::SeekLanded {
            seq: b,
            landed_ticks: ms(2)
        }),
        []
    );
}

#[test]
fn an_outbound_seek_storm_keeps_one_post_in_flight_and_sends_the_latest() {
    let mut rig = guest_playing();
    let first = req_in(&rig.ev(Event::Seek { ticks: ms(1) }));
    assert!(rests(&rig.ev(Event::Seek { ticks: ms(2) })).is_empty());
    assert!(rests(&rig.ev(Event::Seek { ticks: ms(3) })).is_empty());
    assert_eq!(rig.ev(Event::RequestDone { req: Tag(0) }), []);
    let fx = rig.ev(Event::RequestDone { req: first });
    assert_eq!(rests(&fx), [&RestCall::Seek { ticks: ms(3) }]);
    rig.ev(Event::RequestDone { req: req_in(&fx) });
    assert_eq!(rig.member().seek_req, SeekReq::None);
}

#[test]
fn commands_before_time_sync_wait_and_only_the_latest_runs() {
    let mut rig = guest_fresh();
    let s = rig.server();
    let queue = new_playlist(s);
    for event in [
        queue,
        command(
            SendCommandType::Unpause,
            Some(PID_1),
            Some(0),
            Some(s + 500),
            s,
        ),
        command(SendCommandType::Pause, Some(PID_1), Some(ms(3)), Some(s), s),
    ] {
        assert!(
            dirs(&rig.ev(event)).is_empty(),
            "no directive before the clock"
        );
    }
    let fx = rig.sync();
    let got: Vec<_> = dirs(&fx).into_iter().map(|(_, d)| head(&d)).collect();
    assert_eq!(
        got,
        ["Load"],
        "queue first; the command waits for the landing"
    );
    let fx = rig.ev(loaded(PID_1));
    assert_eq!(
        labels(&fx),
        ["Rest:Ready", "Dir:Pause", "Notify"],
        "then only the latest command"
    );
}

#[test]
fn create_while_playing_or_offline_is_refused_without_a_request() {
    for (reporting, bus) in [
        (Reporting::Live, Bus::Up),
        (Reporting::Idle, Bus::Down),
        (Reporting::Live, Bus::Down),
    ] {
        let mut rig = idle();
        assert_eq!(
            labels(&rig.ev(create_with(reporting, bus))),
            ["Ui:CreateRefused", "Notify"]
        );
        assert_eq!(rig.sp.state(), &State::Idle);
    }
}

/// Every create or join gets an immediate Stop for an empty queue's item id.
#[test]
fn the_create_race_stop_for_an_empty_queue_is_a_no_op() {
    let mut rig = idle();
    let create_req = req_in(&rig.ev(create()));
    let s = rig.server();
    rig.ev(joined(GROUP, &[A], GroupStateType::Idle, s));
    // The server names the empty queue's item with the nil id in this Stop.
    let stop = SendCommand {
        playlist_item_id: Some(Uuid::nil()),
        ..command_fields(SendCommandType::Stop, s)
    };
    assert_eq!(
        labels(&rig.ev(Event::Server(SyncPlayEvent::Command(stop)))),
        Vec::<String>::new(),
        "held for the clock"
    );
    assert!(
        dirs(&rig.sync()).is_empty(),
        "replayed onto an empty queue: nothing to stop"
    );
    assert_eq!(rig.ev(Event::EntryFailed { req: create_req }), []);
    assert!(member_of(rig.sp.state()).is_some());
}

#[test]
fn buffering_under_three_seconds_never_reaches_the_server() {
    let mut rig = guest_playing();
    let fx = rig.ev(Event::BufferingStarted);
    assert!(matches!(
        fx[0],
        Effect::Job(Job::After {
            delay_ms: BUFFERING_REPORT_MS,
            ..
        })
    ));
    assert_eq!(
        labels(&rig.ev(Event::BufferingEnded { position_ticks: 0 })),
        ["Notify"]
    );
    assert_eq!(
        rig.ev(Event::Timer(timer_in(&fx))),
        [],
        "the report timer is stale"
    );
}

#[test]
fn buffering_over_three_seconds_reports_then_readies_while_playing() {
    let mut rig = guest_playing();
    let timer = timer_in(&rig.ev(Event::BufferingStarted));
    rig.wait(BUFFERING_REPORT_MS);
    let fx = rig.ev(Event::Timer(timer));
    assert!(
        matches!(
            rests(&fx)[..],
            [RestCall::Buffering(PlayerReport {
                is_playing: true,
                ..
            })]
        ),
        "{fx:?}"
    );
    let fx = rig.ev(Event::BufferingEnded {
        position_ticks: ms(4),
    });
    assert!(
        matches!(rests(&fx)[..], [RestCall::Ready(PlayerReport { is_playing: true, position_ticks, .. })] if *position_ticks == ms(4)),
        "{fx:?}"
    );
}

#[test]
fn drift_waits_out_the_lockout_then_corrects_600ms_once() {
    let mut rig = guest_paused();
    rig.unpause_now(0);
    rig.wait(500 + 1_000);
    let behind = |rig: &Rig| Event::PositionSample {
        position_ticks: expected_now(rig) - ms(600),
    };
    assert_eq!(rig.ev(behind(&rig)), [], "inside the lockout");
    rig.wait(1_000);
    let position_ticks = expected_now(&rig);
    let fx = rig.ev(behind(&rig));
    let (seq, correct) = dirs(&fx)[0];
    assert_eq!(correct, Directive::Correct { position_ticks });
    assert_eq!(rig.ev(behind(&rig)), [], "while correcting");
    rig.ev(Event::SeekLanded {
        seq,
        landed_ticks: expected_now(&rig),
    });
    assert_eq!(rig.ev(behind(&rig)), [], "inside the post-landing lockout");
    rig.wait(LOCKOUT_MS);
    assert_eq!(labels(&rig.ev(behind(&rig))), ["Dir:Correct"]);
}

#[test]
fn drift_ahead_holds_and_behind_seeks() {
    assert_eq!(
        decide_drift(ms(10_000), ms(9_400)),
        Drift::Correct {
            target_ticks: ms(10_000)
        }
    );
    assert_eq!(
        decide_drift(ms(10_000), ms(10_600)),
        Drift::HoldFor { ms: 600 }
    );
    assert_eq!(
        decide_drift(ms(10_000), ms(9_600)),
        Drift::Correct {
            target_ticks: ms(10_000)
        }
    );
    assert_eq!(
        decide_drift(ms(10_000), ms(10_400)),
        Drift::HoldFor { ms: 400 }
    );
    assert_eq!(decide_drift(ms(10_000), ms(9_601)), Drift::InSync);
    assert_eq!(decide_drift(ms(10_000), ms(10_399)), Drift::InSync);

    let mut rig = guest_playing();
    let ahead = Event::PositionSample {
        position_ticks: expected_now(&rig) + ms(600),
    };
    assert_eq!(dirs(&rig.ev(ahead))[0].1, Directive::HoldFor { ms: 600 });
    rig.wait(600 + LOCKOUT_MS - 100);
    let again = Event::PositionSample {
        position_ticks: expected_now(&rig) + ms(600),
    };
    assert_eq!(rig.ev(again), [], "the hold plus its lockout");
}

/// docs/28 §1: the end of the last item returns to the lobby instead of finishing.
#[test]
fn the_last_item_ending_returns_to_the_lobby_still_a_member() {
    let mut rig = guest_playing();
    let fx = rig.ev(Event::PlayerEnded);
    assert_eq!(dirs(&fx)[0].1, Directive::ReturnToLobby);
    assert_eq!(rig.member().local(), Some(&Local::Stopped));

    let mut rig = guest_playing();
    let s = rig.server();
    rig.ev(play_queue(
        Why::Queue,
        s,
        &[(ITEM_1, PID_1), (ITEM_2, PID_2)],
        0,
        0,
        true,
    ));
    assert_eq!(
        rests(&rig.ev(Event::PlayerEnded)),
        [&RestCall::NextItem { pid: PID_1 }]
    );
}

fn prompt_of(rig: &Rig) -> Option<Prompt> {
    match rig.member().local() {
        Some(Local::Paused(prompt)) => Some(*prompt),
        _ => None,
    }
}

fn stall_timer(rig: &Rig) -> Event {
    match prompt_of(rig) {
        Some(Prompt::Armed { token, .. }) => Event::Timer(Timer::StallPrompt { token }),
        other => panic!("no prompt armed: {other:?}"),
    }
}

/// docs/28 §6, §7: a paused Next stays paused on Jellyswarrm, so the client only offers Play.
#[test]
fn a_paused_next_item_only_prompts_and_never_unpauses() {
    let mut rig = guest_paused();
    let s = rig.server();
    let mut all = rig.ev(next_item(s));
    let fx = rig.ev(loaded(PID_2));
    assert!(
        same_effects(&labels(&fx), &["Rest:Ready", "Job:StallPrompt", "Notify"]),
        "{fx:?}"
    );
    assert!(matches!(
        fx[..],
        [
            Effect::Rest { .. },
            Effect::Job(Job::After {
                delay_ms: STALL_PROMPT_MS,
                ..
            }),
            ..
        ]
    ));
    all.extend(fx);
    rig.wait(STALL_PROMPT_MS);
    let fx = rig.ev(stall_timer(&rig));
    assert_eq!(labels(&fx), ["Notify"]);
    assert_eq!(prompt_of(&rig), Some(Prompt::Shown));
    all.extend(fx);
    assert_eq!(count_unpause(&all), 0, "{:?}", labels(&all));
    let s = rig.server();
    rig.ev(command(
        SendCommandType::Pause,
        Some(PID_2),
        Some(0),
        Some(s),
        s,
    ));
    assert_eq!(prompt_of(&rig), Some(Prompt::Quiet));
    assert_eq!(count_unpause(&rig.ev(Event::Unpause)), 1);
}

/// The sp-force2 seek target: 2 s into the item.
const SEEK_TARGET: i64 = ms(2_000);

/// sp-force2-12.0.log: a group Seek while a member is silent; the group waits and our sync seek
/// is in flight, with `held` (if any) arriving before it lands.
fn guest_seeking_while_waiting(held: Option<Event>) -> Rig {
    let mut rig = guest_playing();
    let s = rig.server();
    rig.ev(command(
        SendCommandType::Seek,
        Some(PID_1),
        Some(SEEK_TARGET),
        Some(s),
        s,
    ));
    rig.ev(state_update(
        GroupStateType::Waiting,
        PlaybackRequestType::Seek,
    ));
    if let Some(event) = held {
        rig.ev(event);
    }
    rig
}

fn land_seek(rig: &mut Rig) -> Vec<Effect> {
    let seq = match rig.member().local() {
        Some(Local::Seeking { seq, .. }) => *seq,
        other => panic!("not seeking: {other:?}"),
    };
    rig.ev(Event::SeekLanded {
        seq,
        landed_ticks: SEEK_TARGET,
    })
}

/// The sp-force2 state after our Ready: landed on the target, prompt armed, group Waiting.
fn guest_landed_while_waiting() -> Rig {
    let mut rig = guest_seeking_while_waiting(None);
    land_seek(&mut rig);
    rig
}

fn pause_at(rig: &Rig, pid: Uuid, ticks: i64) -> Event {
    let s = rig.server();
    command(SendCommandType::Pause, Some(pid), Some(ticks), Some(s), s)
}

fn prompt_shown(rig: &Rig) -> bool {
    matches!(rig.sp.snapshot(), Snapshot::Member(m) if m.prompt_shown)
}

/// docs/28 §6, sp-force2-12.0.log: while the group waits, the server answers our Ready with a
/// Pause at our landing; that is no command from anyone, so the prompt still shows after 10 s.
#[test]
fn the_servers_pause_answering_our_ready_keeps_the_prompt() {
    let mut rig = guest_seeking_while_waiting(None);
    let fx = land_seek(&mut rig);
    assert!(
        same_effects(&labels(&fx), &["Rest:Ready", "Job:StallPrompt", "Notify"]),
        "{fx:?}"
    );
    let armed = prompt_of(&rig);
    let echo = pause_at(&rig, PID_1, SEEK_TARGET);
    assert_eq!(labels(&rig.ev(echo)), ["Dir:Pause"]);
    assert_eq!(prompt_of(&rig), armed, "the same prompt, on the same timer");
    rig.wait(STALL_PROMPT_MS);
    assert_eq!(labels(&rig.ev(stall_timer(&rig))), ["Notify"]);
    assert!(prompt_shown(&rig));
}

/// docs/28 §6: anything but that echo still clears the prompt and makes its timer stale.
#[test]
fn any_other_command_while_waiting_clears_the_prompt() {
    let cases: [(&str, fn(&Rig) -> Event); 5] = [
        ("Pause elsewhere", |r| {
            pause_at(r, PID_1, SEEK_TARGET + ms(1_000))
        }),
        ("Pause for another item", |r| {
            pause_at(r, PID_2, SEEK_TARGET)
        }),
        ("Unpause", |r| {
            let s = r.server();
            command(
                SendCommandType::Unpause,
                Some(PID_1),
                Some(SEEK_TARGET),
                Some(s + 500),
                s,
            )
        }),
        ("Seek", |r| {
            let s = r.server();
            command(
                SendCommandType::Seek,
                Some(PID_1),
                Some(SEEK_TARGET),
                Some(s),
                s,
            )
        }),
        ("Stop", |r| {
            let s = r.server();
            command(SendCommandType::Stop, None, None, Some(s), s)
        }),
    ];
    for (name, make) in cases {
        let mut rig = guest_landed_while_waiting();
        let timer = stall_timer(&rig);
        let event = make(&rig);
        rig.ev(event);
        assert!(
            !matches!(prompt_of(&rig), Some(Prompt::Armed { .. } | Prompt::Shown)),
            "{name}: {:?}",
            rig.member().local()
        );
        assert_eq!(
            rig.ev(timer),
            [],
            "{name}: the cleared prompt's timer is stale"
        );
    }
}

/// docs/28 §6: the echo only counts while the group waits; once it says Paused, a Pause is a person's.
#[test]
fn the_echo_pause_outside_waiting_clears_the_prompt() {
    let mut rig = guest_landed_while_waiting();
    let timer = stall_timer(&rig);
    rig.ev(state_update(
        GroupStateType::Paused,
        PlaybackRequestType::Pause,
    ));
    let echo = pause_at(&rig, PID_1, SEEK_TARGET);
    rig.ev(echo);
    assert_eq!(prompt_of(&rig), Some(Prompt::Quiet));
    assert_eq!(rig.ev(timer), []);
}

/// docs/28 §6: the same echo arriving before our seek lands is held, and the landing still arms.
#[test]
fn an_echo_pause_held_during_the_seek_still_arms_the_prompt() {
    let echo = |ticks: i64| {
        let mut probe = guest_playing();
        probe.wait(20);
        pause_at(&probe, PID_1, ticks)
    };
    let mut rig = guest_seeking_while_waiting(Some(echo(SEEK_TARGET)));
    let fx = land_seek(&mut rig);
    assert!(
        same_effects(
            &labels(&fx),
            &["Rest:Ready", "Dir:Pause", "Job:StallPrompt", "Notify"]
        ),
        "{fx:?}"
    );
    rig.wait(STALL_PROMPT_MS);
    rig.ev(stall_timer(&rig));
    assert!(prompt_shown(&rig));

    let mut rig = guest_seeking_while_waiting(Some(echo(SEEK_TARGET + ms(1_000))));
    let fx = land_seek(&mut rig);
    assert!(
        same_effects(&labels(&fx), &["Rest:Ready", "Dir:Pause", "Notify"]),
        "a Pause elsewhere is the group moving on: {fx:?}"
    );
    assert_eq!(prompt_of(&rig), Some(Prompt::Quiet));
}

/// docs/28 §4, §6: a command released by the landing is the group moving on, so no prompt arms.
#[test]
fn a_held_command_after_a_landing_arms_no_prompt() {
    let mut rig = guest_loading_held();
    let fx = rig.ev(loaded(PID_2));
    assert_eq!(labels(&fx), ["Rest:Ready", "Dir:Pause", "Notify"]);
    assert_eq!(prompt_of(&rig), Some(Prompt::Quiet));
}

#[test]
fn a_prompt_armed_before_a_new_item_is_stale_while_loading() {
    let mut rig = guest_paused();
    let timer = stall_timer(&rig);
    let s = rig.server();
    rig.ev(next_item(s));
    let loading = rig.sp.state().clone();
    assert_eq!(rig.member().local(), Some(&Local::Loading { held: None }));
    assert_eq!(rig.ev(timer), []);
    assert_eq!(rig.sp.state(), &loading);
}

#[test]
fn a_superseded_stall_prompt_is_dropped() {
    let mut rig = guest_paused();
    let first = stall_timer(&rig);
    let s = rig.server();
    let seq = dirs(&rig.ev(command(
        SendCommandType::Seek,
        Some(PID_1),
        Some(ms(4)),
        Some(s),
        s,
    )))[0]
        .0;
    rig.ev(Event::SeekLanded {
        seq,
        landed_ticks: ms(4),
    });
    let second = stall_timer(&rig);
    assert_eq!(rig.ev(first), []);
    assert_eq!(labels(&rig.ev(second)), ["Notify"]);
    assert_eq!(prompt_of(&rig), Some(Prompt::Shown));
}

/// docs/28 §6: the prompt clears on the next command, whichever item it names.
#[test]
fn any_command_clears_the_prompt_even_for_another_item() {
    let mut rig = guest_paused();
    let timer = stall_timer(&rig);
    let s = rig.server();
    let other = command(SendCommandType::Pause, Some(PID_2), Some(0), Some(s), s);
    let mut armed = rig.clone();
    assert_eq!(armed.ev(other.clone()), []);
    assert_eq!(prompt_of(&armed), Some(Prompt::Quiet));
    assert_eq!(
        armed.ev(timer.clone()),
        [],
        "the cleared prompt's timer is stale"
    );
    rig.ev(timer);
    assert_eq!(prompt_of(&rig), Some(Prompt::Shown));
    assert_eq!(labels(&rig.ev(other)), ["Notify"]);
    assert_eq!(prompt_of(&rig), Some(Prompt::Quiet));
}

/// docs/28 §4: after the queue ends, group commands and snapshots are ignored until a new title loads.
#[test]
fn after_the_queue_ends_snapshots_and_commands_change_nothing() {
    let mut rig = guest_playing();
    let Cursor::Applied(old) = rig.member().cursor else {
        panic!("cursor applied")
    };
    assert_eq!(
        labels(&rig.ev(Event::PlayerEnded)),
        ["Dir:ReturnToLobby", "Notify"]
    );
    assert_eq!(rig.member().local(), Some(&Local::Stopped));
    rig.ev(Event::BusDisconnected);
    let window = timer_in(&rig.ev(Event::BusConnected));
    assert_eq!(
        rests(&rig.ev(Event::Timer(window))),
        [&RestCall::Join { group: GROUP }]
    );
    let s = rig.server();
    rig.ev(joined(GROUP, &[A, B], GroupStateType::Idle, s));
    let fx = rig.ev(new_playlist(old));
    assert!(dirs(&fx).is_empty(), "{fx:?}");
    assert_eq!(rig.member().local(), Some(&Local::Stopped));
    for kind in [
        SendCommandType::Pause,
        SendCommandType::Unpause,
        SendCommandType::Seek,
    ] {
        // Emitted after the rejoin, so only the lobby rule can drop it.
        let s = rig.server() + 1;
        assert_eq!(
            rig.ev(command(kind, Some(PID_1), Some(ms(2)), Some(s), s)),
            [],
            "{kind:?}"
        );
        assert_eq!(rig.member().local(), Some(&Local::Stopped), "{kind:?}");
    }
    let s = rig.server();
    let fx = rig.ev(play_queue(
        Why::NewPlaylist,
        s,
        &[(ITEM_2, PID_2)],
        0,
        0,
        false,
    ));
    assert_eq!(labels(&fx), ["Dir:Load", "Notify"]);
}

/// docs/28 §4: a real queue for the ended title (not the rejoin snapshot) is a new title loading.
#[test]
fn a_restart_of_the_same_title_after_the_queue_ends_loads_it() {
    let mut picked = guest_ended_in_lobby();
    let s = picked.server();
    let pick = play_queue(Why::SetCurrentItem, s, &[(ITEM_1, PID_1)], 0, 0, false);
    assert_eq!(labels(&picked.ev(pick)), ["Dir:Load", "Notify"]);
    let mut rig = guest_ended_in_lobby();
    let edit = play_queue(
        Why::Queue,
        s,
        &[(ITEM_1, PID_1), (ITEM_2, PID_2)],
        0,
        0,
        false,
    );
    assert!(labels(&rig.ev(edit)).iter().all(|l| !l.starts_with("Dir:")));
    assert_eq!(rig.member().local(), Some(&Local::Stopped));
    let fx = rig.ev(new_playlist(s + 1));
    assert_eq!(labels(&fx), ["Dir:Load", "Notify"]);
    assert!(matches!(rig.member().local(), Some(Local::Loading { .. })));
}

/// docs/28 §1: a group Stop returns to the lobby, and the player closing behind it is no leave.
#[test]
fn a_group_stop_then_the_players_close_stays_in_the_group() {
    let mut rig = guest_playing();
    let s = rig.server();
    let fx = rig.ev(command(SendCommandType::Stop, None, None, Some(s), s));
    assert_eq!(labels(&fx), ["Dir:ReturnToLobby", "Notify"]);
    assert_eq!(rig.ev(Event::PlayerClosed), []);
    assert_eq!(rig.member().local(), Some(&Local::Stopped));
}

/// docs/28 §4: with no player running, transport presses and player events do nothing.
#[test]
fn pause_unpause_seek_and_end_are_inert_in_the_lobby() {
    for seed in [guest_ended_in_lobby as fn() -> Rig, host_lobby] {
        let mut rig = seed();
        for event in [
            Event::Pause,
            Event::Unpause,
            Event::Seek { ticks: ms(9) },
            Event::PlayerEnded,
            Event::PlayerClosed,
            Event::PlaybackError(Severity::Fatal),
        ] {
            let label = head(&event);
            assert_eq!(rig.ev(event), [], "{label}");
        }
        assert!(member_of(rig.sp.state()).is_some());
    }
}

/// docs/28 §5: before the first clock sample the newest queue is kept, whatever order it arrived in.
#[test]
fn a_late_older_queue_before_the_clock_syncs_does_not_replace_a_newer_one() {
    let mut rig = guest_fresh();
    let s = rig.server();
    rig.ev(play_queue(
        Why::NewPlaylist,
        s,
        &[(ITEM_2, PID_2)],
        0,
        0,
        false,
    ));
    rig.ev(new_playlist(s - 1_000));
    let loads: Vec<Uuid> = dirs(&rig.sync())
        .into_iter()
        .filter_map(|(_, d)| match d {
            Directive::Load { entry, .. } => Some(entry.pid),
            _ => None,
        })
        .collect();
    assert_eq!(loads, [PID_2]);
}

/// docs/28 §4: a seek held behind one in flight is not posted once the queue has ended.
#[test]
fn a_held_seek_dies_when_the_queue_ends() {
    let mut rig = guest_playing();
    let first = req_in(&rig.ev(Event::Seek { ticks: ms(1) }));
    rig.ev(Event::Seek { ticks: ms(2) });
    rig.ev(Event::PlayerEnded);
    assert_eq!(rig.ev(Event::RequestDone { req: first }), []);
    assert_eq!(rig.member().seek_req, SeekReq::None);
}

/// A joiner's snapshot says not playing with a frozen position.
#[test]
fn play_queue_start_adds_elapsed_time_only_while_playing() {
    for (playing, want) in [(false, ms(10)), (true, ms(10) + ms(3_000))] {
        let mut rig = guest_fresh();
        rig.sync();
        let last = rig.server() - 3_000;
        let fx = rig.ev(play_queue(
            Why::NewPlaylist,
            last,
            &[(ITEM_1, PID_1)],
            0,
            ms(10),
            playing,
        ));
        assert_eq!(
            dirs(&fx)[0].1,
            Directive::Load {
                entry: Entry {
                    item_id: ITEM_1,
                    pid: PID_1
                },
                start_ticks: want
            },
            "playing={playing}"
        );
    }
}

#[test]
fn debug_never_prints_user_names() {
    let mut rig = host_lobby();
    let fx = rig.ev(user_joined(B));
    let s = rig.server();
    rig.ev(pick_item());
    rig.ev(new_playlist(s));
    let text = format!("{:?} {fx:?} {:?}", rig.sp, user_joined(B));
    assert!(!text.contains("member-"), "{text}");
    let among = guest_joined(&[A, C, B], ME);
    let text = format!(
        "{:?} {:?} {:?} {:?}",
        among.sp,
        guest_playing().sp,
        join_group(),
        Notice::HostLeft
    );
    assert!(!text.contains("member-"), "{text}");
}

// ---- host seat and host-left ----

fn user_left(name: &str) -> Event {
    update(GROUP, GroupUpdate::UserLeft(name.into()))
}

/// A guest named `me` whose first GroupJoined listed `names`; unsynced, nothing queued.
fn guest_joined(names: &[&str], me: &str) -> Rig {
    let mut rig = Rig::new();
    rig.ev(join_with(Bus::Up, me));
    let s = rig.server();
    rig.ev(joined(GROUP, names, GroupStateType::Paused, s - 1_000));
    rig
}

fn names(list: &[&str]) -> Vec<Name> {
    list.iter().map(|n| Name((*n).into())).collect()
}

fn snapshot_host(rig: &Rig) -> HostSeat {
    match rig.sp.snapshot() {
        Snapshot::Member(m) => m.host,
        other => panic!("not a member: {other:?}"),
    }
}

/// The effects of a host-left exit, alongside whatever the step itself did.
const HOST_LEFT: [&str; 3] = ["Ui:HostLeft", "Rest:Leave", "CancelScope"];

fn left_for_host(rig: &Rig) -> bool {
    matches!(rig.sp.state(), State::Leaving(l) if l.reason == LeaveReason::HostLeft)
}

/// docs/28 §1: the seat is everyone listed but one entry of our own name, whatever the order.
#[test]
fn host_seat_is_the_members_listed_besides_us() {
    let alias = "member-b-alias";
    let cases: [(&[&str], HostSeat); 7] = [
        (&[A, B], HostSeat::Known(Name(A.into()))),
        (&[B, A], HostSeat::Known(Name(A.into()))),
        (&[B], HostSeat::Unknown),
        (&[A, C, B], HostSeat::Among(names(&[A, C]))),
        (&[B, C, A], HostSeat::Among(names(&[C, A]))),
        (&[A, alias], HostSeat::Among(names(&[A, alias]))),
        (&[], HostSeat::Among(Vec::new())),
    ];
    for (listed, want) in cases {
        let listed: Vec<String> = listed.iter().map(ToString::to_string).collect();
        assert_eq!(host_seat(&Name(B.into()), &listed), want, "{listed:?}");
    }
}

/// Device finding: Jellyswarrm may list the joiner first; the other member is still the host.
#[test]
fn a_guest_listed_first_names_the_other_member_host() {
    let rig = guest_joined(&[B, A], B);
    assert_eq!(snapshot_host(&rig), HostSeat::Known(Name(A.into())));
    let Snapshot::Member(m) = rig.sp.snapshot() else {
        panic!("a member")
    };
    assert_eq!(m.role, Role::Guest);
    assert_eq!(m.participants, names(&[B, A]), "server order, verbatim");
}

/// docs/28 §1: with several members before us the host is one of them, unnamed.
#[test]
fn three_members_at_entry_is_among_and_names_no_host() {
    let rig = guest_joined(&[A, C, B], B);
    assert_eq!(snapshot_host(&rig), HostSeat::Among(names(&[A, C])));
    assert_eq!(rig.member().role(), Role::Guest);
}

#[test]
fn among_fires_host_left_only_when_every_earlier_member_left() {
    for (first, last) in [(A, C), (C, A)] {
        let mut rig = guest_joined(&[A, C, B], B);
        rig.sync();
        let fx = rig.ev(user_left(first));
        assert_eq!(labels(&fx), ["Ui:UserLeft(Name(..))", "Notify"], "{first}");
        assert!(member_of(rig.sp.state()).is_some());
        let fx = rig.ev(user_left(last));
        let want = [
            "Ui:UserLeft(Name(..))",
            HOST_LEFT[0],
            HOST_LEFT[1],
            HOST_LEFT[2],
            "Notify",
        ];
        assert!(
            same_effects(&labels(&fx), &want),
            "{first} then {last}: {fx:?}"
        );
        assert!(left_for_host(&rig), "{:?}", rig.sp.state());
        assert_eq!(rig.ev(user_left(B)), [], "once: Leaving ignores the rest");
    }
}

/// docs/28 §1: someone who joined after us is never a host candidate.
#[test]
fn a_later_joiner_leaving_never_ends_the_lobby() {
    let mut rig = guest_joined(&[A, B], B);
    rig.sync();
    rig.ev(user_joined(C));
    assert_eq!(
        labels(&rig.ev(user_left(C))),
        ["Ui:UserLeft(Name(..))", "Notify"]
    );
    assert_eq!(rig.member().host, HostSeat::Known(Name(A.into())));
    let fx = rig.ev(user_joined(C));
    assert_eq!(labels(&fx), ["Ui:UserJoined(Name(..))", "Notify"]);
    let fx = rig.ev(user_left(A));
    assert!(fx.contains(&Effect::Ui(Notice::HostLeft)), "{fx:?}");
    assert!(
        left_for_host(&rig),
        "the later joiner still listed is no host"
    );
}

/// docs/28 §1: our own entry under another name keeps every candidate set listed, so it fails safe.
#[test]
fn a_missing_own_name_never_fires_host_left() {
    let alias = "member-b-alias";
    let mut rig = guest_joined(&[A, alias], B);
    rig.sync();
    assert_eq!(rig.member().host, HostSeat::Among(names(&[A, alias])));
    assert_eq!(
        labels(&rig.ev(user_left(A))),
        ["Ui:UserLeft(Name(..))", "Notify"]
    );
    assert!(member_of(rig.sp.state()).is_some());
}

/// Before the first clock sample a join is in transit, so host-left waits for the sample.
#[test]
fn no_host_left_before_the_clock_syncs() {
    let mut rig = guest_joined(&[A, B], B);
    assert_eq!(
        labels(&rig.ev(user_left(A))),
        ["Ui:UserLeft(Name(..))", "Notify"]
    );
    assert!(member_of(rig.sp.state()).is_some());
    let fx = rig.sync();
    let want = [
        "Rest:Ping",
        HOST_LEFT[0],
        HOST_LEFT[1],
        HOST_LEFT[2],
        "Notify",
    ];
    assert!(same_effects(&labels(&fx), &want), "{fx:?}");
    assert!(left_for_host(&rig));
}

/// docs/28 §1: guests watching keep watching until the queue ends.
#[test]
fn host_left_never_fires_mid_playback() {
    let mut rig = guest_playing();
    assert_eq!(
        labels(&rig.ev(user_left(A))),
        ["Ui:UserLeft(Name(..))", "Notify"]
    );
    for event in [
        Event::Pause,
        Event::BufferingStarted,
        Event::Seek { ticks: ms(4) },
    ] {
        let label = head(&event);
        let fx = rig.ev(event);
        assert!(
            !fx.contains(&Effect::Ui(Notice::HostLeft)),
            "{label}: {fx:?}"
        );
    }
    assert!(member_of(rig.sp.state()).is_some());
    let fx = rig.ev(Event::PlayerEnded);
    let want = [
        "Dir:ReturnToLobby",
        HOST_LEFT[0],
        HOST_LEFT[1],
        HOST_LEFT[2],
        "Notify",
    ];
    assert!(same_effects(&labels(&fx), &want), "{fx:?}");
    assert!(left_for_host(&rig));
}

/// docs/28 §1: the end of the last item is Finished; a new title plays; an empty queue is the lobby.
#[test]
fn finished_after_the_last_item() {
    let stage = |rig: &Rig| match rig.sp.snapshot() {
        Snapshot::Member(m) => m.stage,
        other => panic!("not a member: {other:?}"),
    };
    let mut rig = guest_playing();
    let s = rig.server();
    rig.ev(play_queue(
        Why::Queue,
        s,
        &[(ITEM_1, PID_1), (ITEM_2, PID_2)],
        0,
        0,
        true,
    ));
    rig.ev(Event::PlayerEnded);
    assert_eq!(
        stage(&rig),
        Stage::Playing,
        "the next item is still to play"
    );
    rig.ev(next_item(s + 1));
    rig.ev(loaded(PID_2));
    rig.ev(Event::PlayerEnded);
    assert_eq!(stage(&rig), Stage::Finished);
    let s = rig.server();
    rig.ev(play_queue(
        Why::NewPlaylist,
        s,
        &[(ITEM_1, PID_1)],
        0,
        0,
        false,
    ));
    assert_eq!(stage(&rig), Stage::Playing, "a new title loads");
    rig.ev(play_queue(Why::RemoveItems, s + 1, &[], -1, 0, false));
    assert_eq!(stage(&rig), Stage::Lobby);
}

/// docs/28 §5: a late joiner's queue waits for the first sample and loads on it, so no host-left
/// fires even with the host already gone.
#[test]
fn a_join_snapshot_with_a_queue_loads_after_the_first_sample() {
    let mut rig = guest_joined(&[A, B], B);
    let last = rig.server() - 3_000;
    let queue = play_queue(Why::NewPlaylist, last, &[(ITEM_1, PID_1)], 0, ms(10), true);
    assert_eq!(rig.ev(queue), [], "unsynced: the queue waits");
    rig.ev(user_left(A));
    let fx = rig.sync();
    assert!(
        matches!(dirs(&fx)[..], [(_, Directive::Load { start_ticks, .. })] if start_ticks > ms(10) + ms(3_000)),
        "{fx:?}"
    );
    assert!(!fx.contains(&Effect::Ui(Notice::HostLeft)), "{fx:?}");
    assert!(matches!(rig.member().local(), Some(Local::Loading { .. })));
}

/// docs/28 §1: a rejoin keeps the seat latched at entry, whatever the new list's order or members.
#[test]
fn a_rejoin_keeps_the_seat_and_ends_the_lobby_once_the_host_is_gone() {
    let mut rig = guest_playing();
    rig.ev(Event::BusDisconnected);
    let s = rig.server();
    rig.ev(joined(GROUP, &[C, B], GroupStateType::Playing, s));
    assert_eq!(rig.member().host, HostSeat::Known(Name(A.into())));
    assert!(member_of(rig.sp.state()).is_some(), "still watching");

    let mut rig = jellyswarrm_joiner_open();
    rig.ev(Event::BusDisconnected);
    let s = rig.server();
    rig.ev(joined(GROUP, &[B, A, C], GroupStateType::Idle, s));
    assert_eq!(rig.member().host, HostSeat::Known(Name(A.into())));
    let fx = rig.ev(Event::BusDisconnected);
    assert!(!fx.contains(&Effect::Ui(Notice::HostLeft)));
    let s = rig.server();
    let fx = rig.ev(joined(GROUP, &[C, B], GroupStateType::Idle, s));
    let want = [HOST_LEFT[0], HOST_LEFT[1], HOST_LEFT[2], "Notify"];
    assert!(same_effects(&labels(&fx), &want), "{fx:?}");
    assert!(left_for_host(&rig));
}

/// docs/28 §4: a command for the item we are still loading waits for the landing, so our Ready is never lost.
#[test]
fn a_command_while_loading_waits_for_the_landing_and_never_goes_silent() {
    let mut rig = guest_playing();
    let s = rig.server();
    rig.ev(next_item(s));
    for event in [
        command(SendCommandType::Pause, Some(PID_2), Some(ms(1)), Some(s), s),
        command(
            SendCommandType::Unpause,
            Some(PID_2),
            Some(ms(1)),
            Some(s + 500),
            s,
        ),
    ] {
        assert!(dirs(&rig.ev(event)).is_empty(), "held while loading");
    }
    let fx = rig.ev(loaded(PID_2));
    assert_eq!(
        labels(&fx),
        ["Rest:Ready", "Dir:Unpause", "Notify"],
        "Ready, then the latest command"
    );
    assert!(matches!(rig.member().local(), Some(Local::Playing { .. })));
}

/// A New that the server ran after our Leave still makes us a member; leave that group too.
#[test]
fn a_group_joined_after_leaving_from_creating_leaves_again() {
    let mut rig = leaving_ungrouped();
    let first = leave_req(&rig);
    let s = rig.server();
    let fx = rig.ev(joined(GROUP, &[A], GroupStateType::Idle, s));
    assert_eq!(rests(&fx), [&RestCall::Leave]);
    let second = leave_req(&rig);
    assert_ne!(first, second);
    assert_eq!(
        rig.ev(joined(OTHER_GROUP, &[A], GroupStateType::Idle, s)),
        [],
        "now bound to that group"
    );
    assert_eq!(
        rig.ev(Event::RequestDone { req: first }),
        [],
        "the earlier Leave ran before the group existed"
    );
    rig.ev(Event::RequestDone { req: second });
    assert_eq!(rig.sp.state(), &State::Idle);
}

/// docs/28 §4: a Ready at the landed spot would draw a corrective Seek; the Seek's landing reports.
#[test]
fn a_seek_held_while_loading_seeks_before_reporting_ready() {
    let mut rig = guest_playing();
    let s = rig.server();
    rig.ev(next_item(s));
    rig.ev(command(
        SendCommandType::Seek,
        Some(PID_2),
        Some(ms(5)),
        Some(s),
        s,
    ));
    let fx = rig.ev(loaded(PID_2));
    assert_eq!(labels(&fx), ["Dir:Seek", "Notify"]);
    let (seq, seek) = dirs(&fx)[0];
    assert_eq!(
        seek,
        Directive::Seek {
            position_ticks: ms(5)
        }
    );
    let fx = rig.ev(Event::SeekLanded {
        seq,
        landed_ticks: ms(5),
    });
    assert!(
        matches!(rests(&fx)[..], [RestCall::Ready(PlayerReport { position_ticks, pid: PID_2, .. })] if *position_ticks == ms(5)),
        "{fx:?}"
    );
}

/// A rejoin snapshot's list is who is in the group now.
#[test]
fn a_rejoin_snapshot_replaces_the_participants() {
    let mut rig = guest_down();
    rig.ev(update(GROUP, GroupUpdate::UserLeft(B.into())));
    let s = rig.server();
    rig.ev(joined(GROUP, &[B, A, B], GroupStateType::Playing, s));
    assert_eq!(
        rig.member().participants,
        [Name(B.into()), Name(A.into()), Name(B.into())]
    );
}

// ---- review fixes, in the server's own frame order ----

#[test]
fn a_rejoin_answered_by_an_empty_id_group_does_not_exist_ends_the_group() {
    let mut rig = guest_rejoining();
    let fx = rig.ev(error_reply(GroupUpdate::GroupDoesNotExist));
    assert_eq!(
        labels(&fx),
        ["CancelScope", "Dir:Detach", "Ui:GroupEnded(Gone)", "Notify"]
    );
    assert_eq!(rig.sp.state(), &State::Idle);
}

/// docs/28 §2: Jellyswarrm sends all three error replies with a nil id, so none is filtered by group.
#[test]
fn a_nil_id_library_access_denied_ends_a_rejoin_and_refuses_a_join() {
    let mut rig = guest_rejoining();
    let fx = rig.ev(error_reply(GroupUpdate::LibraryAccessDenied));
    assert_eq!(
        labels(&fx),
        ["CancelScope", "Dir:Detach", "Ui:GroupEnded(Gone)", "Notify"]
    );
    assert_eq!(rig.sp.state(), &State::Idle);
    let mut rig = joining();
    let fx = rig.ev(error_reply(GroupUpdate::LibraryAccessDenied));
    assert_eq!(labels(&fx), ["Ui:NoAccess", "Notify"]);
    assert_eq!(rig.sp.state(), &State::Idle);
    let mut rig = joining();
    let fx = rig.ev(error_reply(GroupUpdate::GroupDoesNotExist));
    assert_eq!(labels(&fx), ["Ui:NoSuchGroup", "Notify"]);
    for make in [
        GroupUpdate::LibraryAccessDenied as fn(String) -> GroupUpdate,
        GroupUpdate::GroupDoesNotExist,
        GroupUpdate::NotInGroup,
    ] {
        let mut rig = creating();
        let before = rig.sp.state().clone();
        assert_eq!(rig.ev(error_reply(make)), []);
        assert_eq!(rig.sp.state(), &before);
    }
}

#[test]
fn not_in_group_with_an_empty_id_starts_a_rejoin() {
    let mut rig = guest_playing();
    let fx = rig.ev(error_reply(GroupUpdate::NotInGroup));
    assert_eq!(labels(&fx), ["Rest:Join", "Notify"]);
    assert!(matches!(rig.member().link, Link::Rejoining { .. }));
}

/// docs/28 §6: a new queue makes the server resume by itself once all are Ready.
#[test]
fn the_host_start_sends_no_unpause_because_the_group_starts_itself() {
    let mut rig = host_lobby();
    let mut fx = rig.ev(user_joined(B));
    fx.extend(rig.ev(pick_item()));
    let s = rig.server();
    fx.extend(rig.ev(new_playlist(s)));
    fx.extend(rig.ev(loaded(PID_1)));
    fx.extend(rig.unpause_now(0));
    fx.extend(rig.ev(state_update(
        GroupStateType::Playing,
        PlaybackRequestType::Unpause,
    )));
    fx.extend(rig.ev(Event::Pause));
    let s = rig.server();
    fx.extend(rig.ev(command(
        SendCommandType::Pause,
        Some(PID_1),
        Some(0),
        Some(s),
        s,
    )));
    fx.extend(rig.ev(state_update(
        GroupStateType::Paused,
        PlaybackRequestType::Pause,
    )));
    assert_eq!(count_unpause(&fx), 0, "{:?}", labels(&fx));
}

/// docs/28 §5: a paused player is never drift-corrected, whether or not the server heard the pause.
#[test]
fn a_local_pause_stops_drift_correction_even_offline() {
    let mut rig = guest_down();
    let position_ticks = expected_now(&rig);
    rig.ev(Event::Pause);
    rig.wait(2_000);
    let fx = rig.ev(Event::PositionSample { position_ticks });
    assert!(dirs(&fx).is_empty(), "{fx:?}");
}

/// A command queued before a re-entry predates it, and the replay skips the age check.
#[test]
fn re_entry_drops_a_command_queued_before_it() {
    let mut rig = guest_fresh();
    let s = rig.server();
    rig.ev(command(
        SendCommandType::Pause,
        Some(PID_1),
        Some(ms(1)),
        Some(s),
        s,
    ));
    rig.ev(joined(GROUP, &[A, B], GroupStateType::Paused, s + 500));
    let s = rig.server();
    rig.ev(new_playlist(s));
    rig.sync();
    let fx = rig.ev(loaded(PID_1));
    assert_eq!(labels(&fx), ["Rest:Ready", "Job:StallPrompt", "Notify"]);
}

/// docs/28 §5: a Ready owed after a reported stall is sent even if the group paused meanwhile.
#[test]
fn a_ready_owed_after_a_reported_stall_survives_a_pause() {
    let mut rig = guest_buffer_sent();
    let s = rig.server();
    let fx = rig.ev(command(
        SendCommandType::Pause,
        Some(PID_1),
        Some(ms(1)),
        Some(s),
        s,
    ));
    assert_eq!(head(&dirs(&fx)[0].1), "Pause");
    let fx = rig.ev(Event::BufferingEnded {
        position_ticks: ms(4),
    });
    assert!(
        matches!(rests(&fx)[..], [RestCall::Ready(PlayerReport { is_playing: false, position_ticks, .. })] if *position_ticks == ms(4)),
        "{fx:?}"
    );
    assert!(matches!(
        rig.member().local(),
        Some(Local::Paused(Prompt::Armed { .. }))
    ));
}

/// docs/28 §5: a hold has a ceiling; further ahead than that, seek back instead.
#[test]
fn drift_ahead_holds_up_to_five_seconds_then_seeks_back() {
    let expected = ms(60_000);
    assert_eq!(
        decide_drift(expected, expected + ms(4_999)),
        Drift::HoldFor { ms: 4_999 }
    );
    assert_eq!(
        decide_drift(expected, expected + ms(5_000)),
        Drift::HoldFor { ms: 5_000 }
    );
    assert_eq!(
        decide_drift(expected, expected + ms(5_001)),
        Drift::Correct {
            target_ticks: expected
        }
    );
}

/// docs/28 §5: commands run at their When in local time; a When already past runs at once, from
/// where the group is by then.
#[test]
fn command_payloads_follow_the_server_clock() {
    let mut rig = guest_paused();
    let (s, wall) = (rig.server(), rig.now().wall_ms);
    let fx = rig.ev(command(
        SendCommandType::Pause,
        Some(PID_1),
        Some(ms(7)),
        Some(s + 200),
        s,
    ));
    let pause = PauseAt {
        freeze_ticks: Some(ms(7)),
        due_wall_ms: wall + 200,
    };
    assert_eq!(dirs(&fx)[0].1, Directive::Pause(pause));
    let (s, wall) = (rig.server(), rig.now().wall_ms);
    let fx = rig.ev(command(
        SendCommandType::Unpause,
        Some(PID_1),
        Some(ms(7)),
        Some(s + 500),
        s,
    ));
    let future = PlayAt {
        position_ticks: ms(7),
        due_wall_ms: wall + 500,
    };
    assert_eq!(dirs(&fx)[0].1, Directive::Unpause(future));
    let (s, wall) = (rig.server(), rig.now().wall_ms);
    let fx = rig.ev(command(
        SendCommandType::Unpause,
        Some(PID_1),
        Some(ms(7)),
        Some(s - 300),
        s,
    ));
    let past = PlayAt {
        position_ticks: ms(7) + ms(300),
        due_wall_ms: wall,
    };
    assert_eq!(dirs(&fx)[0].1, Directive::Unpause(past));
}

#[test]
fn a_40_ms_round_trip_pings_20_and_the_timers_use_their_delays() {
    let mut rig = guest_playing();
    assert_eq!(rests(&rig.sync()), [&RestCall::Ping { ms: 20 }]);
    let delay = |fx: &[Effect]| {
        fx.iter().find_map(|e| match e {
            Effect::Job(Job::After { delay_ms, .. }) => Some(*delay_ms),
            _ => None,
        })
    };
    assert_eq!(delay(&rig.clone().ev(Event::BusConnected)), Some(2_000));
    assert_eq!(delay(&rig.clone().ev(Event::BufferingStarted)), Some(3_000));
    assert_eq!(delay(&guest_loading().ev(loaded(PID_1))), Some(10_000));
}

#[test]
fn a_superseded_buffering_report_is_dropped() {
    let mut rig = guest_playing();
    let first = timer_in(&rig.ev(Event::BufferingStarted));
    rig.ev(Event::BufferingEnded { position_ticks: 0 });
    let second = timer_in(&rig.ev(Event::BufferingStarted));
    assert_eq!(rig.ev(Event::Timer(first)), []);
    assert_eq!(labels(&rig.ev(Event::Timer(second))), ["Rest:Buffering"]);
}

/// docs/28 §4: one account's second device is listed once (a rejoin can't be told apart), so its leave clears the name.
#[test]
fn user_left_removes_the_name() {
    let mut rig = guest_playing();
    rig.ev(user_joined(B));
    rig.ev(update(GROUP, GroupUpdate::UserLeft(B.into())));
    assert_eq!(rig.member().participants, [Name(A.into())]);
}

/// docs/28 §4: commands emitted before the latest (re)entry are ignored.
#[test]
fn re_entry_moves_the_command_age_cutoff() {
    let mut rig = guest_down();
    let s = rig.server();
    rig.ev(joined(GROUP, &[A, B], GroupStateType::Playing, s + 1_000));
    let s2 = rig.server();
    let older = command(
        SendCommandType::Pause,
        Some(PID_1),
        Some(0),
        Some(s2),
        s + 500,
    );
    assert_eq!(rig.ev(older), []);
}

/// A command that arrives mid-seek waits for the landing, so the landing's Ready is never lost.
#[test]
fn a_command_during_a_sync_seek_waits_for_the_landing() {
    let mut rig = guest_seeking();
    let seq = match rig.member().local() {
        Some(Local::Seeking { seq, .. }) => *seq,
        other => panic!("not seeking: {other:?}"),
    };
    let s = rig.server();
    let fx = rig.ev(command(
        SendCommandType::Pause,
        Some(PID_1),
        Some(ms(5)),
        Some(s),
        s,
    ));
    assert!(dirs(&fx).is_empty(), "held while seeking: {fx:?}");
    let fx = rig.ev(Event::SeekLanded {
        seq,
        landed_ticks: ms(5),
    });
    assert_eq!(labels(&fx), ["Rest:Ready", "Dir:Pause", "Notify"]);
}

/// A Seek that arrives mid-seek supersedes it: re-seek on the landing, and only that one reports.
#[test]
fn a_seek_during_a_sync_seek_re_seeks_on_the_landing_without_a_ready() {
    let mut rig = guest_seeking();
    let seq = match rig.member().local() {
        Some(Local::Seeking { seq, .. }) => *seq,
        other => panic!("not seeking: {other:?}"),
    };
    let s = rig.server();
    assert!(dirs(&rig.ev(command(
        SendCommandType::Seek,
        Some(PID_1),
        Some(ms(9)),
        Some(s),
        s
    )))
    .is_empty());
    let fx = rig.ev(Event::SeekLanded {
        seq,
        landed_ticks: ms(5),
    });
    assert!(rests(&fx).is_empty(), "{fx:?}");
    let (next, seek) = dirs(&fx)[0];
    assert_eq!(
        seek,
        Directive::Seek {
            position_ticks: ms(9)
        }
    );
    let fx = rig.ev(Event::SeekLanded {
        seq: next,
        landed_ticks: ms(9),
    });
    assert_eq!(labels(&fx), ["Rest:Ready", "Job:StallPrompt", "Notify"]);
}

/// docs/28 §4: the phase is the server's word; a rejoin snapshot or a live queue change leaves it.
#[test]
fn a_rejoin_snapshot_takes_its_phase_from_group_joined_and_arms_nothing() {
    let mut rig = guest_down();
    let Cursor::Applied(old) = rig.member().cursor else {
        panic!("cursor applied")
    };
    let s = rig.server();
    rig.ev(joined(GROUP, &[A, B], GroupStateType::Waiting, s));
    let fx = rig.ev(new_playlist(old));
    assert!(!fx.iter().any(|e| matches!(e, Effect::Job(_))), "{fx:?}");
    assert_eq!(rig.member().phase, GroupPhase::Waiting);
    let s = rig.server();
    let fx = rig.ev(next_item(s));
    assert!(!fx.iter().any(|e| matches!(e, Effect::Job(_))), "{fx:?}");
    assert_eq!(rig.member().phase, GroupPhase::Waiting);
}

/// A GroupJoined under another name while we create is some other group replaying itself (a
/// disconnect grace): leave it and keep waiting for ours.
#[test]
fn a_group_joined_under_another_name_while_creating_is_left() {
    let mut rig = creating();
    let s = rig.server();
    let fx = rig.ev(joined_named(
        OTHER_GROUP,
        "XYZ789",
        &[A],
        GroupStateType::Playing,
        s,
    ));
    assert_eq!(labels(&fx), ["Rest:Leave"]);
    assert!(matches!(rig.sp.state(), State::Creating { .. }));
    rig.ev(joined(GROUP, &[A], GroupStateType::Idle, s));
    assert_eq!(rig.member().group, GROUP);
}

// ---- pure functions ----

#[test]
fn clock_sample_splits_the_round_trip_symmetrically() {
    // Sent at 1000 local; server got it at 1270 and answered at 1280; back at 1060 local.
    assert_eq!(
        clock_sample(1_000, 1_270, 1_280, 1_060),
        ClockSample {
            offset_ms: 245,
            rtt_ms: 50
        }
    );
    assert_eq!(clock_sample(1_000, 900, 900, 1_000).rtt_ms, 0);
    assert_eq!(
        clock_sample(1_000, 900, 2_000, 1_000).rtt_ms,
        0,
        "noise never goes negative"
    );
}

#[test]
fn ring8_uses_the_lowest_rtt_of_the_last_eight() {
    let mut ring = Ring8::default();
    assert_eq!(ring.best(), None);
    let sample = |offset_ms, rtt_ms| ClockSample { offset_ms, rtt_ms };
    ring.push(sample(100, 10));
    for rtt in 20..27 {
        ring.push(sample(999, rtt));
    }
    assert_eq!(ring.best(), Some(sample(100, 10)));
    ring.push(sample(500, 30));
    assert_eq!(
        ring.best(),
        Some(sample(999, 20)),
        "the ninth push evicts the first"
    );
}

#[test]
fn server_now_and_expected_position_follow_the_offset_and_anchor() {
    assert_eq!(server_now_ms(1_000, 250), 1_250);
    assert_eq!(server_now_ms(1_000, -250), 750);
    let anchor = Anchor {
        ticks: ms(5),
        when_ms: 2_000,
    };
    assert_eq!(expected_ticks(anchor, 2_000), ms(5));
    assert_eq!(expected_ticks(anchor, 3_500), ms(1_505));
    assert_eq!(
        expected_ticks(anchor, 1_000),
        ms(5),
        "before the anchor nothing elapsed"
    );
    assert_eq!(
        expected_ticks(
            Anchor {
                ticks: i64::MAX,
                when_ms: 0
            },
            10
        ),
        i64::MAX
    );
}

#[test]
fn time_sync_polls_at_once_then_three_at_a_second_then_every_minute() {
    let delays: Vec<u64> = (0..6).map(time_sync_delay_ms).collect();
    assert_eq!(delays, [0, 1_000, 1_000, 1_000, 60_000, 60_000]);
}

// ---- entry timeout ----

fn entry_timeout_in(fx: &[Effect]) -> Event {
    Event::Timer(
        fx.iter()
            .find_map(|e| match e {
                Effect::Job(Job::After {
                    delay_ms: ENTRY_TIMEOUT_MS,
                    timer: timer @ Timer::EntryTimeout { .. },
                }) => Some(*timer),
                _ => None,
            })
            .expect("a 10 s entry timeout"),
    )
}

fn join_group() -> Event {
    join_with(Bus::Up, ME)
}

fn join_with(bus: Bus, me: &str) -> Event {
    Event::Join {
        group: GROUP,
        bus,
        me: Name(me.into()),
    }
}

/// docs/28 §4: a create or join with no GroupJoined in 10 s leaves, says the start failed, and is
/// Idle once the Leave is answered; a create never named a group, a join names the one it asked for.
#[test]
fn an_entry_timeout_leaves_once_and_fails_the_start() {
    for (start, group) in [(create(), None), (join_group(), Some(GROUP))] {
        let mut rig = Rig::new();
        let timer = entry_timeout_in(&rig.ev(start));
        rig.wait(ENTRY_TIMEOUT_MS);
        let fx = rig.ev(timer);
        assert!(
            same_effects(&labels(&fx), &["Rest:Leave", "Ui:StartFailed", "Notify"]),
            "{fx:?}"
        );
        assert!(
            matches!(rig.sp.state(), State::Leaving(l) if l.group == group && l.reason == LeaveReason::User),
            "{:?}",
            rig.sp.state()
        );
        let done = rig.ev(Event::RequestDone {
            req: leave_req(&rig),
        });
        assert_eq!(labels(&done), ["Notify"]);
        assert_eq!(rig.sp.state(), &State::Idle);
    }
}

#[test]
fn a_group_joined_before_the_entry_timeout_makes_it_stale() {
    for (start, role) in [(create(), Role::Host), (join_group(), Role::Guest)] {
        let mut rig = Rig::new();
        let timer = entry_timeout_in(&rig.ev(start));
        let s = rig.server();
        rig.ev(joined(GROUP, &[A], GroupStateType::Idle, s));
        let entered = rig.sp.state().clone();
        rig.wait(ENTRY_TIMEOUT_MS);
        assert_eq!(rig.ev(timer), [], "the member's timer is stale");
        assert_eq!(rig.sp.state(), &entered);
        assert_eq!(rig.member().role(), role);
    }
}

/// An earlier attempt's timer firing during a newer create or join changes nothing.
#[test]
fn an_entry_timer_from_an_earlier_attempt_is_stale() {
    for start in [create, join_group] {
        let mut rig = Rig::new();
        let old = entry_timeout_in(&rig.ev(start()));
        rig.ev(Event::Leave);
        rig.ev(Event::RequestDone {
            req: leave_req(&rig),
        });
        rig.ev(start());
        let again = rig.sp.state().clone();
        rig.wait(ENTRY_TIMEOUT_MS);
        assert_eq!(rig.ev(old), [], "the earlier timer is stale");
        assert_eq!(rig.sp.state(), &again);
    }
}

/// docs/28 §4: the abandoned New or Join may still run after our Leave, so its GroupJoined is left
/// once more, and only once.
#[test]
fn a_late_group_joined_after_an_entry_timeout_sends_one_more_leave() {
    for start in [create(), join_group()] {
        let mut rig = Rig::new();
        let timer = entry_timeout_in(&rig.ev(start));
        rig.wait(ENTRY_TIMEOUT_MS);
        rig.ev(timer);
        let first = leave_req(&rig);
        let s = rig.server();
        let fx = rig.ev(joined(GROUP, &[A, B], GroupStateType::Paused, s));
        assert_eq!(rests(&fx), [&RestCall::Leave], "{fx:?}");
        let second = leave_req(&rig);
        assert_ne!(first, second);
        let again = rig.ev(joined(GROUP, &[A, B], GroupStateType::Paused, s + 20));
        assert_eq!(again, [], "only one more Leave");
        rig.ev(Event::RequestDone { req: second });
        assert_eq!(rig.sp.state(), &State::Idle);
    }
}

/// docs/28 §4: leaving while our Join or rejoin is still in flight leaves once more if it lands.
#[test]
fn a_leave_during_a_join_or_rejoin_leaves_again_if_the_join_lands() {
    let joining_then_leave = || {
        let mut rig = Rig::new();
        rig.ev(join_group());
        rig
    };
    for seed in [joining_then_leave as fn() -> Rig, guest_rejoining] {
        let mut rig = seed();
        rig.ev(Event::Leave);
        let first = leave_req(&rig);
        let s = rig.server();
        let fx = rig.ev(joined(GROUP, &[A, B], GroupStateType::Paused, s));
        assert_eq!(rests(&fx), [&RestCall::Leave], "{fx:?}");
        let second = leave_req(&rig);
        assert_ne!(first, second);
        assert_eq!(
            rig.ev(joined(GROUP, &[A, B], GroupStateType::Paused, s + 20)),
            []
        );
    }
}

// ---- snapshot and helpers ----

type Seen = (Role, Stage, HostSeat, Vec<String>, GroupPhase, bool, bool);
type SeenRow = (&'static str, fn() -> Rig, Seen);

fn seen(rig: &Rig) -> Seen {
    let Snapshot::Member(m) = rig.sp.snapshot() else {
        panic!("not a member: {:?}", rig.sp.snapshot());
    };
    let names = m.participants.iter().map(|n| n.0.clone()).collect();
    let MemberSnapshot {
        role,
        stage,
        host,
        phase,
        connected,
        prompt_shown,
        ..
    } = m;
    (role, stage, host, names, phase, connected, prompt_shown)
}

#[test]
#[allow(clippy::too_many_lines)]
fn the_snapshot_shows_the_group_without_tags_or_clocks() {
    assert_eq!(idle().sp.snapshot(), Snapshot::Idle);
    assert_eq!(
        creating().sp.snapshot(),
        Snapshot::Starting {
            name: Some(RoomName(ROOM.into()))
        }
    );
    assert_eq!(joining().sp.snapshot(), Snapshot::Starting { name: None });
    assert_eq!(
        leaving().sp.snapshot(),
        Snapshot::Leaving {
            reason: LeaveReason::User
        }
    );
    use GroupPhase as P;
    use Stage as S;
    let ab = || vec![A.to_string(), B.to_string()];
    let a = || HostSeat::Known(Name(A.into()));
    let rows: [SeenRow; 12] = [
        (
            "host lobby",
            host_lobby,
            (
                Role::Host,
                S::Lobby,
                HostSeat::Me,
                vec![A.into()],
                P::Idle,
                true,
                false,
            ),
        ),
        (
            "host playing",
            host_playing,
            (
                Role::Host,
                S::Playing,
                HostSeat::Me,
                ab(),
                P::Playing,
                true,
                false,
            ),
        ),
        (
            "guest unsynced, nothing loaded",
            guest_fresh,
            (Role::Guest, S::Lobby, a(), ab(), P::Paused, true, false),
        ),
        (
            "Jellyswarrm joiner, empty queue",
            jellyswarrm_joiner_open,
            (Role::Guest, S::Lobby, a(), ab(), P::Paused, true, false),
        ),
        (
            "guest loading",
            guest_loading,
            (Role::Guest, S::Playing, a(), ab(), P::Paused, true, false),
        ),
        (
            "guest playing",
            guest_playing,
            (Role::Guest, S::Playing, a(), ab(), P::Playing, true, false),
        ),
        (
            "link down",
            guest_down,
            (Role::Guest, S::Playing, a(), ab(), P::Playing, false, false),
        ),
        (
            "awaiting snapshot",
            guest_await,
            (Role::Guest, S::Playing, a(), ab(), P::Playing, false, false),
        ),
        (
            "rejoining",
            guest_rejoining,
            (Role::Guest, S::Playing, a(), ab(), P::Playing, false, false),
        ),
        (
            "prompt shown",
            guest_prompt_shown,
            (Role::Guest, S::Playing, a(), ab(), P::Paused, true, true),
        ),
        (
            "queue ended",
            guest_ended_in_lobby,
            (Role::Guest, S::Finished, a(), ab(), P::Playing, true, false),
        ),
        (
            "group stop",
            guest_stopped,
            (Role::Guest, S::Finished, a(), ab(), P::Playing, true, false),
        ),
    ];
    let failures: Vec<String> = rows
        .into_iter()
        .filter_map(|(name, rig, want)| {
            let got = seen(&rig());
            (got != want).then(|| format!("{name}: got {got:?}, want {want:?}"))
        })
        .collect();
    assert!(failures.is_empty(), "\n{}", failures.join("\n"));
}

#[test]
fn snapshot_debug_names_the_variant_only() {
    let text = format!(
        "{:?} {:?} {:?}",
        creating().sp.snapshot(),
        host_lobby().sp.snapshot(),
        guest_playing().sp.snapshot()
    );
    assert_eq!(text, "Starting Member Member");
}

fn listed(id: Option<Uuid>, name: Option<&str>) -> GroupInfoDto {
    GroupInfoDto {
        group_id: id,
        group_name: name.map(Into::into),
        ..GroupInfoDto::default()
    }
}

/// docs/28 §1: the base name when free, else the first free numeric suffix, comparing trimmed and
/// ignoring case; unnamed groups never collide.
#[test]
fn room_name_takes_the_first_free_suffix() {
    const BASE: &str = "Sam's group";
    let named = |n: &str| listed(Some(GROUP), Some(n));
    let cases: [(&str, Vec<GroupInfoDto>, &str); 7] = [
        ("free", vec![named("Pat's group")], BASE),
        ("no groups", vec![], BASE),
        ("taken", vec![named(BASE)], "Sam's group 2"),
        (
            "two taken",
            vec![named(BASE), named("Sam's group 2")],
            "Sam's group 3",
        ),
        (
            "gap is reused",
            vec![named(BASE), named("Sam's group 3")],
            "Sam's group 2",
        ),
        (
            "case and spaces",
            vec![named("  SAM'S GROUP "), named("sam's group 2")],
            "Sam's group 3",
        ),
        (
            "unnamed and blank ignored",
            vec![listed(Some(GROUP), None), named("  ")],
            BASE,
        ),
    ];
    for (label, groups, want) in cases {
        assert_eq!(room_name(BASE, &groups), RoomName(want.into()), "{label}");
    }
}

/// docs/28 §3.6: a room name carries a user name, so `Debug` hides it.
#[test]
fn a_room_name_hides_itself_in_debug() {
    assert_eq!(format!("{:?}", RoomName(ROOM.into())), "RoomName(..)");
}

/// docs/28 §5: a failed sample retries on the schedule's next delay, but never at once.
#[test]
fn a_failed_time_sample_retries_after_a_second_then_a_minute() {
    let delays: Vec<u64> = (0..6).map(time_sync_retry_ms).collect();
    assert_eq!(delays, [1_000, 1_000, 1_000, 1_000, 60_000, 60_000]);
    assert_eq!(time_sync_retry_ms(u32::MAX), 60_000);
}

// ---- exhaustive sequences ----

struct Letter {
    name: &'static str,
    make: fn(&SyncPlay, Now) -> Event,
    /// True when the letter's tag or group is not current in this state, so it must change nothing.
    stale: fn(&SyncPlay) -> bool,
}

const fn letter(name: &'static str, make: fn(&SyncPlay, Now) -> Event) -> Letter {
    Letter {
        name,
        make,
        stale: |_| false,
    }
}

const fn stale_letter(name: &'static str, make: fn(&SyncPlay, Now) -> Event) -> Letter {
    Letter {
        name,
        make,
        stale: |_| true,
    }
}

fn cur_pid(sp: &SyncPlay) -> Uuid {
    member_of(sp.state())
        .and_then(Member::current_pid)
        .unwrap_or(PID_1)
}

fn other_entry(sp: &SyncPlay) -> (Uuid, Uuid) {
    if cur_pid(sp) == PID_1 {
        (ITEM_2, PID_2)
    } else {
        (ITEM_1, PID_1)
    }
}

fn cur_entry(sp: &SyncPlay) -> (Uuid, Uuid) {
    if cur_pid(sp) == PID_1 {
        (ITEM_1, PID_1)
    } else {
        (ITEM_2, PID_2)
    }
}

fn local_of(sp: &SyncPlay) -> Option<Local> {
    member_of(sp.state()).and_then(Member::local).copied()
}

fn sweep_expected(sp: &SyncPlay, now: Now) -> i64 {
    match (member_of(sp.state()), local_of(sp)) {
        (Some(m), Some(Local::Playing(Playing { anchor, .. }))) => {
            expected_ticks(anchor, server_now_ms(now.wall_ms, m.clock.offset_ms()))
        }
        _ => 0,
    }
}

#[allow(clippy::too_many_lines)]
fn core_letters() -> Vec<Letter> {
    vec![
        letter("Create", |_, _| create()),
        letter("Join", |_, _| join_group()),
        letter("GroupJoined", |_, n| {
            joined(GROUP, &[A, B], GroupStateType::Playing, server_at(n) - 10)
        }),
        Letter {
            name: "GroupJoined(other group)",
            make: |_, n| joined(OTHER_GROUP, &[A], GroupStateType::Idle, server_at(n)),
            stale: |sp| sp.state().group().is_some_and(|g| g != OTHER_GROUP),
        },
        letter("UserJoined(member-b)", |_, _| user_joined(B)),
        letter("GroupLeft", |_, _| group_signal(GroupUpdate::GroupLeft)),
        letter("GroupDoesNotExist", |_, _| {
            group_signal(GroupUpdate::GroupDoesNotExist)
        }),
        letter("LibraryAccessDenied(nil id)", |_, _| {
            error_reply(GroupUpdate::LibraryAccessDenied)
        }),
        letter("StateUpdate(Waiting, Unpause)", |_, _| {
            state_update(GroupStateType::Waiting, PlaybackRequestType::Unpause)
        }),
        letter("StateUpdate(Paused)", |_, _| {
            state_update(GroupStateType::Paused, PlaybackRequestType::Ready)
        }),
        letter("PlayQueue(NewPlaylist, current pid)", |sp, n| {
            play_queue(
                Why::NewPlaylist,
                server_at(n),
                &[cur_entry(sp)],
                0,
                0,
                false,
            )
        }),
        letter("PlayQueue(NextItem, other pid)", |sp, n| {
            play_queue(
                Why::NextItem,
                server_at(n),
                &[cur_entry(sp), other_entry(sp)],
                1,
                0,
                true,
            )
        }),
        Letter {
            name: "PlayQueue(not newer)",
            make: |sp, n| {
                let last = match member_of(sp.state()).map(|m| m.cursor) {
                    Some(Cursor::Applied(ms)) => ms,
                    _ => server_at(n) - 100_000,
                };
                play_queue(Why::NewPlaylist, last, &[other_entry(sp)], 0, 0, false)
            },
            stale: |sp| {
                member_of(sp.state()).is_some_and(|m| {
                    matches!(m.cursor, Cursor::Applied(_)) && matches!(m.clock, Clock::Synced(_))
                })
            },
        },
        letter("Command(Unpause)", |sp, n| {
            command(
                SendCommandType::Unpause,
                Some(cur_pid(sp)),
                Some(0),
                Some(server_at(n) + 500),
                server_at(n),
            )
        }),
        letter("Command(Seek)", |sp, n| {
            command(
                SendCommandType::Seek,
                Some(cur_pid(sp)),
                Some(ms(3)),
                Some(server_at(n)),
                server_at(n),
            )
        }),
        letter("Command(Stop)", |_, n| {
            command(
                SendCommandType::Stop,
                None,
                None,
                Some(server_at(n)),
                server_at(n),
            )
        }),
        letter("BusDisconnected", |_, _| Event::BusDisconnected),
        letter("BusConnected", |_, _| Event::BusConnected),
        letter("SnapshotWindow", |sp, _| {
            Event::Timer(match member_of(sp.state()).map(|m| m.link) {
                Some(Link::AwaitSnapshot { token }) => Timer::SnapshotWindow { token },
                _ => Timer::SnapshotWindow { token: Tag(0) },
            })
        }),
        letter("ItemLoaded", |sp, _| Event::ItemLoaded {
            pid: cur_pid(sp),
            landed_ticks: 0,
        }),
        letter("SeekLanded", |sp, _| Event::SeekLanded {
            seq: match local_of(sp) {
                Some(
                    Local::Seeking { seq, .. }
                    | Local::Playing(Playing {
                        correcting: Some(seq),
                        ..
                    }),
                ) => seq,
                _ => Tag(0),
            },
            landed_ticks: ms(3),
        }),
        stale_letter("SeekLanded(stale)", |_, _| Event::SeekLanded {
            seq: Tag(0),
            landed_ticks: 0,
        }),
        letter("BufferingStarted", |_, _| Event::BufferingStarted),
        letter("BufferingReportDue", |sp, _| {
            Event::Timer(Timer::BufferingReport {
                token: match local_of(sp) {
                    Some(Local::Buffering {
                        report: Report::Pending { token },
                        ..
                    }) => token,
                    _ => Tag(0),
                },
            })
        }),
        letter("PositionSample(600 ms behind)", |sp, n| {
            Event::PositionSample {
                position_ticks: sweep_expected(sp, n) - ms(600),
            }
        }),
        letter("PositionSample(600 ms ahead)", |sp, n| {
            Event::PositionSample {
                position_ticks: sweep_expected(sp, n) + ms(600),
            }
        }),
        letter("Pause", |_, _| Event::Pause),
        letter("Seek", |_, _| Event::Seek { ticks: ms(8) }),
        letter("StallPrompt", |sp, _| {
            Event::Timer(Timer::StallPrompt {
                token: match local_of(sp) {
                    Some(Local::Paused(Prompt::Armed { token, .. })) => token,
                    _ => Tag(0),
                },
            })
        }),
        letter("PlayQueue(empty NewPlaylist)", |_, n| {
            play_queue(Why::NewPlaylist, server_at(n), &[], -1, 0, false)
        }),
        letter("Unpause", |_, _| Event::Unpause),
        letter("PickItem", |_, _| pick_item()),
        letter("AccountReset", |_, _| Event::AccountReset),
        letter("CreateFailed", |sp, _| Event::EntryFailed {
            req: match sp.state() {
                State::Creating { req, .. } => *req,
                _ => Tag(0),
            },
        }),
        letter("LeaveDone", |sp, _| Event::RequestDone {
            req: match sp.state() {
                State::Leaving(Leaving { req, .. }) => *req,
                _ => Tag(0),
            },
        }),
        letter("TimeSample", |sp, n| {
            time_sample(member_of(sp.state()).map_or(Tag(0), |m| m.membership), n)
        }),
        letter("PlayerEnded", |_, _| Event::PlayerEnded),
        letter("Leave", |_, _| Event::Leave),
        Letter {
            name: "EntryTimeout(current)",
            make: |sp, _| {
                Event::Timer(Timer::EntryTimeout {
                    token: entry_token(sp).unwrap_or(Tag(0)),
                })
            },
            stale: |sp| entry_token(sp).is_none(),
        },
    ]
}

#[allow(clippy::too_many_lines)]
fn extra_letters() -> Vec<Letter> {
    vec![
        letter("Create(reporting Live)", |_, _| {
            create_with(Reporting::Live, Bus::Up)
        }),
        letter("Join(bus Down)", |_, _| join_with(Bus::Down, ME)),
        letter("UserLeft(member-b)", |_, _| user_left(B)),
        letter("UserLeft(member-a)", |_, _| user_left(A)),
        letter("UserLeft(member-c)", |_, _| user_left(C)),
        letter("UserJoined(member-c)", |_, _| user_joined(C)),
        letter("GroupJoined(member-a, member-c)", |_, n| {
            joined(GROUP, &[A, C], GroupStateType::Idle, server_at(n) - 10)
        }),
        letter("NotInGroup", |_, _| group_signal(GroupUpdate::NotInGroup)),
        letter("LibraryAccessDenied(own id)", |_, _| {
            group_signal(GroupUpdate::LibraryAccessDenied)
        }),
        letter("StateUpdate(Playing)", |_, _| {
            state_update(GroupStateType::Playing, PlaybackRequestType::Unpause)
        }),
        letter("StateUpdate(Waiting, Seek)", |_, _| {
            state_update(GroupStateType::Waiting, PlaybackRequestType::Seek)
        }),
        letter("PlayQueue(empty)", |_, n| {
            play_queue(Why::RemoveItems, server_at(n), &[], -1, 0, false)
        }),
        letter("Command(Pause)", |sp, n| {
            command(
                SendCommandType::Pause,
                Some(cur_pid(sp)),
                Some(ms(1)),
                Some(server_at(n)),
                server_at(n),
            )
        }),
        letter("Command(Pause, other pid)", |sp, n| {
            command(
                SendCommandType::Pause,
                Some(other_entry(sp).1),
                Some(0),
                Some(server_at(n)),
                server_at(n),
            )
        }),
        stale_letter("Command(Unpause, no When)", |sp, n| {
            command(
                SendCommandType::Unpause,
                Some(cur_pid(sp)),
                Some(0),
                None,
                server_at(n),
            )
        }),
        stale_letter("Command(Seek, no item)", |_, n| {
            command(
                SendCommandType::Seek,
                None,
                Some(0),
                Some(server_at(n)),
                server_at(n),
            )
        }),
        letter("BusUnauthorized", |_, _| Event::BusUnauthorized),
        stale_letter("SnapshotWindow(stale)", |_, _| {
            Event::Timer(Timer::SnapshotWindow { token: Tag(0) })
        }),
        stale_letter("StallPrompt(stale)", |_, _| {
            Event::Timer(Timer::StallPrompt { token: Tag(0) })
        }),
        letter("PlayQueue(NewPlaylist, new pid)", |sp, n| {
            play_queue(
                Why::NewPlaylist,
                server_at(n),
                &[other_entry(sp)],
                0,
                0,
                false,
            )
        }),
        stale_letter("TimeSample(stale)", |_, n| time_sample(Tag(0), n)),
        stale_letter("LeaveDone(stale)", |_, _| Event::RequestDone {
            req: Tag(0),
        }),
        letter("JoinFailed", |sp, _| Event::EntryFailed {
            req: match (sp.state(), member_of(sp.state()).map(|m| m.link)) {
                (State::Joining { req, .. }, _) => *req,
                (_, Some(Link::Rejoining { req })) => req,
                _ => Tag(0),
            },
        }),
        letter("SeekReqDone", |sp, _| Event::RequestDone {
            req: match member_of(sp.state()).map(|m| m.seek_req) {
                Some(SeekReq::InFlight { req } | SeekReq::Held { req, .. }) => req,
                _ => Tag(0),
            },
        }),
        letter("BufferingEnded", |_, _| Event::BufferingEnded {
            position_ticks: 0,
        }),
        letter("PlaybackError(fatal)", |_, _| {
            Event::PlaybackError(Severity::Fatal)
        }),
        letter("PlaybackError(recoverable)", |_, _| {
            Event::PlaybackError(Severity::Recoverable)
        }),
        letter("PlayerClosed", |_, _| Event::PlayerClosed),
        Letter {
            name: "GroupJoined(other name)",
            make: |_, n| {
                joined_named(
                    OTHER_GROUP,
                    "XYZ789",
                    &[A],
                    GroupStateType::Playing,
                    server_at(n),
                )
            },
            stale: |sp| sp.state().group().is_some_and(|g| g != OTHER_GROUP),
        },
        letter("GroupDoesNotExist(empty id)", |_, _| {
            error_reply(GroupUpdate::GroupDoesNotExist)
        }),
        letter("NotInGroup(empty id)", |_, _| {
            error_reply(GroupUpdate::NotInGroup)
        }),
        letter("StateUpdate(Waiting, Pause)", |_, _| {
            state_update(GroupStateType::Waiting, PlaybackRequestType::Pause)
        }),
        letter("PositionSample(in sync)", |sp, n| Event::PositionSample {
            position_ticks: sweep_expected(sp, n),
        }),
        letter("Queue", |_, _| {
            Event::Queue(AddToQueue {
                items: vec![ITEM_2],
                mode: GroupQueueMode::Queue,
            })
        }),
        letter("Next", |_, _| Event::Next),
        letter("Prev", |_, _| Event::Prev),
        stale_letter("EntryTimeout(stale)", |_, _| {
            Event::Timer(Timer::EntryTimeout { token: Tag(0) })
        }),
    ]
}

fn sweep_seeds() -> Vec<(&'static str, Rig)> {
    vec![
        ("Idle", idle()),
        ("guest synced and playing", guest_playing()),
        ("guest with the link down", guest_down()),
        ("host in the lobby", host_lobby()),
        ("host playing", host_playing()),
    ]
}

/// Swept at length 3 only: at length 4 they would double the sweep's runtime.
fn late_seeds() -> Vec<(&'static str, Rig)> {
    vec![
        (
            "guest in the lobby after the queue ended",
            guest_ended_in_lobby(),
        ),
        ("Jellyswarrm joiner, lobby open", jellyswarrm_joiner_open()),
        (
            "guest paused on the next item, prompt armed",
            guest_paused_prompt_armed(),
        ),
        ("guest before the first clock sample", guest_fresh()),
        ("guest synced in the lobby, two earlier members", {
            let mut rig = guest_joined(&[A, C, B], ME);
            rig.sync();
            rig
        }),
    ]
}

macro_rules! ensure {
    ($cond:expr, $($msg:tt)*) => {
        if !$cond {
            return Err(format!($($msg)*));
        }
    };
}

fn is_group_joined(event: &Event) -> bool {
    matches!(update_of(event), Some(GroupUpdate::GroupJoined(_)))
}

fn update_of(event: &Event) -> Option<&GroupUpdate> {
    match event {
        Event::Server(SyncPlayEvent::GroupUpdate { update, .. }) => Some(update),
        _ => None,
    }
}

/// What a viewer could see, spelled out here rather than taken from the production rule, for the
/// Notify oracle: state kind and leave reason; for a member its role, participants, link, phase,
/// and the queue's items, index and player state, including a shown Play prompt.
type MemberSeen<'a> = (
    Role,
    Vec<&'a str>,
    &'static str,
    &'static str,
    Option<(Vec<Uuid>, usize, &'static str)>,
);

fn test_view(state: &State) -> (&'static str, Option<LeaveReason>, Option<MemberSeen<'_>>) {
    let m = match state {
        State::Idle => return ("idle", None, None),
        State::Creating { .. } => return ("creating", None, None),
        State::Joining { .. } => return ("joining", None, None),
        State::Leaving(l) => return ("leaving", Some(l.reason), None),
        State::Member(m) => m,
    };
    let link = match m.link {
        Link::Connected => "connected",
        Link::Down => "down",
        Link::AwaitSnapshot { .. } => "awaiting",
        Link::Rejoining { .. } => "rejoining",
    };
    let phase = match m.phase {
        GroupPhase::Idle => "idle",
        GroupPhase::Waiting => "waiting",
        GroupPhase::Paused => "paused",
        GroupPhase::Playing => "playing",
    };
    let queue = match &m.queue {
        Queue::Set {
            playlist,
            index,
            local,
        } => {
            let player = match local {
                Local::Stopped => "stopped",
                Local::Loading { .. } => "loading",
                Local::Seeking { .. } => "seeking",
                Local::Paused(Prompt::Shown) => "paused, offering Play",
                Local::Paused(_) => "paused",
                Local::PausedReadyOwed => "paused, ready owed",
                Local::Playing(_) => "playing",
                Local::Buffering { .. } => "buffering",
            };
            Some((playlist.iter().map(|e| e.pid).collect(), *index, player))
        }
        Queue::Empty => None,
    };
    let names = m.participants.iter().map(|n| n.0.as_str()).collect();
    ("member", None, Some((m.role(), names, link, phase, queue)))
}

fn timer_tag(timer: &Timer) -> Tag {
    match *timer {
        Timer::SnapshotWindow { token, .. }
        | Timer::BufferingReport { token }
        | Timer::StallPrompt { token }
        | Timer::EntryTimeout { token } => token,
    }
}

/// One step of the sweep, with the readers every rule shares.
struct Step<'a> {
    pre: &'a SyncPlay,
    post: &'a SyncPlay,
    event: &'a Event,
    fx: &'a [Effect],
    stale: bool,
    now: Now,
}

impl Step<'_> {
    fn before(&self) -> &State {
        self.pre.state()
    }
    fn after(&self) -> &State {
        self.post.state()
    }
    fn pm(&self) -> Option<&Member> {
        member_of(self.before())
    }
    fn qm(&self) -> Option<&Member> {
        member_of(self.after())
    }
    fn calls(&self) -> Vec<&RestCall> {
        rests(self.fx)
    }
    fn directives(&self) -> Vec<Directive> {
        dirs(self.fx).into_iter().map(|(_, d)| d).collect()
    }
    fn count(&self, want: &RestCall) -> usize {
        self.calls().iter().filter(|c| **c == want).count()
    }
}

/// Every rule below holds after every step of every swept sequence.
fn check(
    pre: &SyncPlay,
    post: &SyncPlay,
    event: &Event,
    fx: &[Effect],
    stale: bool,
    now: Now,
) -> Result<(), String> {
    let step = Step {
        pre,
        post,
        event,
        fx,
        stale,
        now,
    };
    check_effects(&step)?;
    check_error_reply_scope(&step)?;
    check_lobby(&step)?;
    check_lifecycle(&step)?;
    check_own_requests(&step)?;
    check_stall_and_pause(&step)?;
    check_host(&step)
}

/// Notify, tags, staleness, directives and reports.
#[allow(clippy::too_many_lines)]
fn check_effects(st: &Step) -> Result<(), String> {
    let (before, after, fx) = (st.before(), st.after(), st.fx);
    let notifies = fx.iter().filter(|e| **e == Effect::Notify).count();
    let noticed = fx.iter().any(|e| matches!(e, Effect::Ui(_)));
    ensure!(notifies <= 1, "at most one Notify per step");
    ensure!(
        (notifies == 1) == (test_view(before) != test_view(after) || noticed),
        "Notify fires exactly when something a viewer sees changed or a notice went out"
    );
    let tags: Vec<Tag> = fx
        .iter()
        .filter_map(|e| match e {
            Effect::Rest { req, .. } => Some(*req),
            Effect::Dir { seq, .. } => Some(*seq),
            Effect::Job(Job::TimeSync { membership }) => Some(*membership),
            Effect::Job(Job::After { timer, .. }) => Some(timer_tag(timer)),
            _ => None,
        })
        .collect();
    let mut last = Tag(st.pre.last_tag);
    for tag in tags {
        ensure!(tag > last, "every new tag is above every earlier one");
        last = tag;
    }
    if st.stale {
        ensure!(
            before == after && fx.is_empty(),
            "a stale message changes nothing: {fx:?}"
        );
    }
    let (pm, qm) = (st.pm(), st.qm());
    let directives = st.directives();
    if let (Some(a), Some(b)) = (pm, qm) {
        let unsynced = |m: &Member| matches!(m.clock, Clock::Unsynced { .. });
        if unsynced(a) && unsynced(b) && matches!(st.event, Event::Server(_)) {
            ensure!(
                directives.is_empty(),
                "docs/28 §5: no server-timed directive before the first clock sample"
            );
        }
    }
    for d in &directives {
        if let Directive::Load { entry, .. } = d {
            let current = qm.and_then(|m| match &m.queue {
                Queue::Set {
                    playlist, index, ..
                } => playlist.get(*index).copied(),
                Queue::Empty => None,
            });
            ensure!(
                current == Some(*entry),
                "a Load names the current queue item"
            );
        }
    }
    for (seq, d) in dirs(fx) {
        if let Directive::Seek { .. } = d {
            ensure!(
                matches!(local_of(st.post), Some(Local::Seeking { seq: s, .. }) if s == seq),
                "a sync seek is the one in flight"
            );
        }
    }
    let readies: Vec<&PlayerReport> = st
        .calls()
        .into_iter()
        .filter_map(|c| match c {
            RestCall::Ready(r) => Some(r),
            _ => None,
        })
        .collect();
    ensure!(readies.len() <= 1, "at most one Ready per step");
    if let Some(ready) = readies.first() {
        let (from, to) = (local_of(st.pre), local_of(st.post));
        let landing = matches!(
            from,
            Some(
                Local::Loading { .. }
                    | Local::Seeking { .. }
                    | Local::PausedReadyOwed
                    | Local::Buffering {
                        report: Report::Sent,
                        ..
                    }
            )
        );
        ensure!(
            landing,
            "a Ready only follows a load, a sync seek or a reported stall: {from:?}"
        );
        ensure!(
            matches!(to, Some(Local::Paused(_) | Local::Playing { .. })),
            "a Ready leaves us paused or playing: {to:?}"
        );
        ensure!(
            Some(ready.pid) == qm.and_then(Member::current_pid),
            "a Ready names the current item"
        );
    }
    let landing = match (st.event, pm.and_then(Member::local)) {
        (Event::ItemLoaded { pid, .. }, Some(Local::Loading { held })) => pm
            .and_then(Member::current_pid)
            .filter(|p| p == pid)
            .map(|_| held),
        (Event::SeekLanded { seq, .. }, Some(Local::Seeking { seq: s, held })) if s == seq => {
            Some(held)
        }
        _ => None,
    };
    if let (Some(held), Some(m)) = (landing, pm) {
        {
            let seek = matches!(held, Some(Command::Seek(_)));
            if !seek && m.link == Link::Connected {
                ensure!(
                    readies.len() == 1,
                    "docs/28 §4: a landing always sends its Ready"
                );
            }
            if seek {
                let seeks = matches!(directives[..], [Directive::Seek { .. }]);
                ensure!(
                    readies.is_empty() && seeks,
                    "docs/28 §4: a held Seek seeks before any Ready"
                );
            }
        }
    }
    if let (
        Event::Server(SyncPlayEvent::Command(c)),
        Some(Local::Loading { .. } | Local::Seeking { .. }),
    ) = (st.event, local_of(st.pre))
    {
        let held = matches!(
            local_of(st.post),
            Some(Local::Loading { held: Some(_) } | Local::Seeking { held: Some(_), .. })
        );
        let stopped = c.command == Some(SendCommandType::Stop);
        ensure!(
            held || stopped || before == after,
            "docs/28 §4: a command never takes us off a load or sync seek"
        );
    }
    if directives
        .iter()
        .any(|d| matches!(d, Directive::Correct { .. } | Directive::HoldFor { .. }))
    {
        let Event::PositionSample { position_ticks } = st.event else {
            return Err("only a position sample corrects drift".into());
        };
        let Some((
            m,
            Local::Playing(Playing {
                anchor,
                lockout_until_mono_ms,
                correcting,
            }),
        )) = pm.zip(local_of(st.pre))
        else {
            return Err("docs/28 §5: drift is corrected only while playing".into());
        };
        ensure!(
            matches!(m.clock, Clock::Synced(_))
                && correcting.is_none()
                && st.now.mono_ms >= lockout_until_mono_ms,
            "docs/28 §5: no correction while correcting or inside a lockout"
        );
        let expected = expected_ticks(anchor, server_now_ms(st.now.wall_ms, m.clock.offset_ms()));
        ensure!(
            decide_drift(expected, *position_ticks) != Drift::InSync,
            "docs/28 §5: no correction under 400 ms"
        );
    }
    let seeks: Vec<(Tag, i64)> = fx
        .iter()
        .filter_map(|e| match e {
            Effect::Rest {
                req,
                call: RestCall::Seek { ticks },
            } => Some((*req, *ticks)),
            _ => None,
        })
        .collect();
    ensure!(seeks.len() <= 1, "one Seek POST at a time");
    if let Some((req, ticks)) = seeks.first() {
        ensure!(
            qm.is_some_and(|m| m.seek_req == SeekReq::InFlight { req: *req }),
            "a Seek POST is tracked in flight"
        );
        let pre_req = pm.map(|m| m.seek_req);
        let fresh = matches!(st.event, Event::Seek { .. }) && pre_req == Some(SeekReq::None);
        let released = matches!(st.event, Event::RequestDone { .. })
            && matches!(pre_req, Some(SeekReq::Held { ticks: held, .. }) if held == *ticks);
        ensure!(
            fresh || released,
            "a second Seek POST waits for the first, then carries the latest target"
        );
    }
    let link_down = |m: Option<&Member>| m.is_some_and(|m| m.link != Link::Connected);
    if link_down(pm) || link_down(qm) {
        let reported = st.calls().iter().any(|c| {
            matches!(
                c,
                RestCall::Ready(_) | RestCall::Buffering(_) | RestCall::Pause | RestCall::Unpause
            )
        });
        ensure!(
            !reported,
            "no report or transport request over a link that is not Connected"
        );
    }
    Ok(())
}

/// docs/28 §4: after the queue ends nothing reaches the player or the group, and only a new queue
/// or an exit leaves the lobby.
fn check_lobby(st: &Step) -> Result<(), String> {
    if local_of(st.pre) != Some(Local::Stopped) || st.qm().is_none() {
        return Ok(());
    }
    let directives = st.directives();
    let post = local_of(st.post);
    if post == Some(Local::Stopped) {
        let player_call = st.calls().iter().any(|c| {
            matches!(
                c,
                RestCall::Ready(_)
                    | RestCall::Buffering(_)
                    | RestCall::Pause
                    | RestCall::Unpause
                    | RestCall::Seek { .. }
            )
        });
        ensure!(
            directives.is_empty() && !player_call,
            "docs/28 §4: the lobby is inert: {:?}",
            st.fx
        );
        return Ok(());
    }
    let queue = match update_of(st.event) {
        Some(GroupUpdate::PlayQueue(u)) => Some(u),
        _ => None,
    };
    let loads = matches!(directives[..], [Directive::Load { .. }])
        && post == Some(Local::Loading { held: None });
    ensure!(
        queue.is_some() && (loads || (post.is_none() && directives.is_empty())),
        "docs/28 §4: only a new queue leaves the lobby: {:?}",
        st.fx
    );
    if let (Some(u), Some(m)) = (queue, st.pm()) {
        let pid = usize::try_from(u.playing_item_index.unwrap_or(-1))
            .ok()
            .and_then(|i| u.playlist.get(i))
            .and_then(|e| e.playlist_item_id);
        let restart = m.cursor != Cursor::Reset
            && matches!(u.reason, Some(Why::NewPlaylist | Why::SetCurrentItem));
        ensure!(
            !loads || pid != m.current_pid() || restart,
            "docs/28 §4: only a fresh start reloads the ended title: {:?}",
            st.fx
        );
    }
    Ok(())
}

/// docs/28 §2: an error reply acts the same with a nil id as with our group's id, in every state.
fn check_error_reply_scope(st: &Step) -> Result<(), String> {
    let Event::Server(SyncPlayEvent::GroupUpdate { group_id, update }) = st.event else {
        return Ok(());
    };
    if !matches!(
        update,
        GroupUpdate::GroupDoesNotExist(_)
            | GroupUpdate::NotInGroup(_)
            | GroupUpdate::LibraryAccessDenied(_)
    ) {
        return Ok(());
    }
    let ours = st.before().group().unwrap_or(GROUP);
    for twin_id in [Uuid::nil(), ours, OTHER_GROUP] {
        if *group_id == Some(twin_id) {
            continue;
        }
        let mut twin = st.pre.clone();
        let twin_fx = twin.step(
            Event::Server(SyncPlayEvent::GroupUpdate {
                group_id: Some(twin_id),
                update: update.clone(),
            }),
            st.now,
        );
        ensure!(
            twin_fx == st.fx && twin.state() == st.after(),
            "docs/28 §2: an error reply acts the same whatever its group id: {twin_fx:?} vs {:?}",
            st.fx
        );
    }
    Ok(())
}

/// Entry, exits, scopes and what Idle and Leaving may do.
#[allow(clippy::too_many_lines)]
fn check_lifecycle(st: &Step) -> Result<(), String> {
    let (before, after, fx) = (st.before(), st.after(), st.fx);
    let (pm, qm) = (st.pm(), st.qm());
    let directives = st.directives();
    let calls = st.calls();
    if is_group_joined(st.event) && !st.stale {
        if let Some(m) = qm {
            ensure!(
                m.cursor == Cursor::Reset,
                "docs/28 §4: every (re)entry resets the queue cursor"
            );
        }
    }
    let same_member = pm
        .zip(qm)
        .is_some_and(|(a, b)| a.membership == b.membership);
    let cancels = fx.iter().filter(|e| **e == Effect::CancelScope).count();
    let exits = pm.is_some() && !same_member;
    ensure!(
        cancels == usize::from(exits),
        "every exit from a membership cancels its jobs once ({cancels})"
    );
    if fx.iter().any(|e| matches!(e, Effect::Job(_))) {
        ensure!(
            matches!(
                after,
                State::Creating { .. } | State::Joining { .. } | State::Member(_)
            ),
            "no job outside an entry or a membership"
        );
    }
    if let (Event::Timer(Timer::EntryTimeout { .. }), false) = (st.event, st.stale) {
        let group = before.group();
        ensure!(
            matches!(after, State::Leaving(l) if l.reason == LeaveReason::User && l.group == group)
                && st.count(&RestCall::Leave) == 1
                && fx.contains(&Effect::Ui(Notice::StartFailed)),
            "docs/28 §4: a timed-out entry leaves once and says the start failed"
        );
    }
    if matches!(st.event, Event::AccountReset) {
        let owed = matches!(
            before,
            State::Creating { .. } | State::Joining { .. } | State::Member(_)
        );
        ensure!(
            *after == State::Idle && st.count(&RestCall::Leave) == usize::from(owed),
            "an account reset ends Idle with one Leave for the outgoing account"
        );
    }
    if let (State::Creating { name, .. }, Some(GroupUpdate::GroupJoined(info))) =
        (before, update_of(st.event))
    {
        if info.group_name.as_deref() != Some(name.0.as_str()) {
            ensure!(
                matches!(after, State::Creating { .. }) && st.count(&RestCall::Leave) == 1,
                "a group joined under another name while creating is left, and we keep waiting"
            );
        }
    }
    if let Event::Create(NewGroup { reporting, bus, .. }) = st.event {
        if *reporting == Reporting::Live || *bus == Bus::Down {
            ensure!(
                !st.calls().iter().any(|c| matches!(c, RestCall::New { .. })),
                "docs/28 §3: no group is created while reporting or offline"
            );
        }
    }
    if matches!(after, State::Idle | State::Leaving { .. }) {
        // docs/28 §1: host-left follows the step that ended the queue, whose ReturnToLobby stands.
        let host_left = matches!(after, State::Leaving(l) if l.reason == LeaveReason::HostLeft);
        ensure!(
            directives
                .iter()
                .all(|d| *d == Directive::Detach || (host_left && *d == Directive::ReturnToLobby)),
            "on the way out only Detach reaches the player: {directives:?}"
        );
        // A host-left after the first clock sample keeps that sample's Ping.
        ensure!(
            calls.iter().all(
                |c| **c == RestCall::Leave || (host_left && matches!(c, RestCall::Ping { .. }))
            ),
            "on the way out the only request is Leave: {calls:?}"
        );
    }
    if let State::Leaving(Leaving {
        group, late_join, ..
    }) = before
    {
        let unbound = group.is_none() || *late_join == LateJoin::Possible;
        let ghost = unbound && is_group_joined(st.event) && !st.stale;
        let allowed = |e: &Effect| match e {
            Effect::Notify => true,
            Effect::Rest {
                call: RestCall::Leave,
                ..
            } => ghost,
            _ => false,
        };
        ensure!(
            fx.iter().all(allowed),
            "Leaving emits no directive and acts only to leave a ghost group: {fx:?}"
        );
        ensure!(
            !ghost || st.count(&RestCall::Leave) == 1,
            "a group joined after we left is left again"
        );
        let settled = |s: &State| matches!(s, State::Leaving(l) if l.group.is_some() && l.late_join == LateJoin::Settled);
        ensure!(
            !ghost || settled(after),
            "a group joined after we left is left only once"
        );
    }
    if *before == State::Idle && *after == State::Idle {
        ensure!(
            !fx.iter()
                .any(|e| matches!(e, Effect::Job(_) | Effect::CancelScope)),
            "docs/28 §8: Idle starts no work"
        );
        let ghost = is_group_joined(st.event);
        ensure!(
            calls.iter().all(|c| **c == RestCall::Leave && ghost),
            "docs/28 §8: Idle's only request leaves a ghost group"
        );
    }
    Ok(())
}

/// docs/28 §3 and §6: a joining name is a notice, never an exit, and group requests answer only
/// the user's own press.
fn check_own_requests(st: &Step) -> Result<(), String> {
    let joins = matches!(
        update_of(st.event),
        Some(GroupUpdate::UserJoined(_) | GroupUpdate::GroupJoined(_))
    );
    if let (true, false, Some(a)) = (joins, st.stale, st.pm()) {
        let same = st.qm().is_some_and(|b| b.membership == a.membership);
        // docs/28 §1: a re-entry listing no host candidate is the host gone, not a name joining.
        let host_gone = is_group_joined(st.event)
            && matches!(st.after(), State::Leaving(l) if l.reason == LeaveReason::HostLeft);
        ensure!(
            (same && st.count(&RestCall::Leave) == 0) || host_gone,
            "docs/28 §3: a joining name never makes a member leave"
        );
    }
    if st.count(&RestCall::Unpause) > 0 {
        ensure!(
            matches!(st.event, Event::Unpause),
            "docs/28 §6: never an automatic start; only the user's Play sends Unpause"
        );
    }
    for call in st.calls() {
        let own = match call {
            RestCall::Pause => matches!(st.event, Event::Pause),
            RestCall::SetNewQueue(_) => matches!(st.event, Event::PickItem(_)),
            RestCall::Queue(_) => matches!(st.event, Event::Queue(_)),
            RestCall::NextItem { .. } => matches!(st.event, Event::Next | Event::PlayerEnded),
            RestCall::PreviousItem { .. } => matches!(st.event, Event::Prev),
            RestCall::Seek { .. } => {
                matches!(st.event, Event::Seek { .. } | Event::RequestDone { .. })
            }
            _ => true,
        };
        ensure!(own, "a member request answers its own press: {call:?}");
    }
    Ok(())
}

/// docs/28 §5 and §6: the Play prompt, holds, pauses that stop correction, a queued command
/// dying with its entry, and Readies owed after a stall.
fn check_stall_and_pause(st: &Step) -> Result<(), String> {
    let (pm, qm) = (st.pm(), st.qm());
    let prompt = |sp: &SyncPlay| match local_of(sp) {
        Some(Local::Paused(prompt)) => Some(prompt),
        _ => None,
    };
    let (was, is) = (prompt(st.pre), prompt(st.post));
    let armed: Vec<Tag> = st
        .fx
        .iter()
        .filter_map(|e| match e {
            Effect::Job(Job::After {
                timer: Timer::StallPrompt { token },
                ..
            }) => Some(*token),
            _ => None,
        })
        .collect();
    for token in &armed {
        ensure!(
            matches!(is, Some(Prompt::Armed { token: t, .. }) if t == *token),
            "docs/28 §6: an armed prompt waits on the timer just started"
        );
    }
    if let Some(Prompt::Armed { token, .. }) = is {
        ensure!(
            was == is || armed == [token],
            "docs/28 §6: a prompt arms only with its own timer"
        );
    }
    ensure!(
        armed.is_empty() || qm.is_some_and(|m| m.link == Link::Connected),
        "docs/28 §6: the prompt arms only after a Ready sent over a Connected link"
    );
    let readies = st
        .calls()
        .iter()
        .filter(|c| matches!(c, RestCall::Ready(_)))
        .count();
    ensure!(
        armed.is_empty() || (armed.len() == 1 && readies == 1),
        "docs/28 §6: the prompt arms only with our Ready, ending paused"
    );
    if is == Some(Prompt::Shown) && was != Some(Prompt::Shown) {
        let own_timer = matches!(st.event, Event::Timer(Timer::StallPrompt { token })
            if matches!(was, Some(Prompt::Armed { token: t, .. }) if t == *token));
        ensure!(
            own_timer,
            "docs/28 §6: the prompt shows only when its own timer fires"
        );
    }
    if let Event::Server(SyncPlayEvent::Command(c)) = st.event {
        let synced = pm.is_some_and(|m| matches!(m.clock, Clock::Synced(_)));
        if !st.stale && synced && Command::parse(c).is_some() {
            // The server's Pause answering our Ready: current item, within 400 ms of the landing
            // the Ready reported, while the group waits.
            let echo = match (pm, was) {
                (Some(m), Some(Prompt::Armed { landed_ticks, .. })) => {
                    c.command == Some(SendCommandType::Pause)
                        && m.phase == GroupPhase::Waiting
                        && c.playlist_item_id == m.current_pid()
                        && c.position_ticks
                            .is_some_and(|t| (t - landed_ticks).abs() < ms(400))
                }
                _ => false,
            };
            if echo {
                ensure!(
                    is == was,
                    "docs/28 §6: the server's Pause answering our Ready keeps the prompt armed"
                );
            } else {
                ensure!(
                    matches!(is, None | Some(Prompt::Quiet)),
                    "docs/28 §6: any other command clears the prompt"
                );
            }
        }
    }
    for (_, d) in dirs(st.fx) {
        if let Directive::HoldFor { ms } = d {
            ensure!(ms <= 5_000, "docs/28 §5: no hold beyond the 5 s ceiling");
        }
    }
    if matches!(st.event, Event::Pause) {
        let running = matches!(
            local_of(st.post),
            Some(Local::Playing(_) | Local::Buffering { .. })
        );
        ensure!(
            !running,
            "docs/28 §5: a local pause stops drift correction at once"
        );
    }
    if !st.stale && is_group_joined(st.event) {
        if let Some(Clock::Unsynced { command, .. }) = qm.map(|m| &m.clock) {
            ensure!(
                command.is_none(),
                "a command queued before a (re)entry dies with it"
            );
        }
    }
    let owed = |l: Option<Local>| {
        matches!(
            l,
            Some(
                Local::PausedReadyOwed
                    | Local::Buffering {
                        report: Report::Sent,
                        ..
                    }
            )
        )
    };
    let connected = qm.is_some_and(|m| m.link == Link::Connected);
    if owed(local_of(st.pre)) && matches!(local_of(st.post), Some(Local::Paused(_))) && connected {
        let ready = rests(st.fx).iter().any(|c| matches!(c, RestCall::Ready(_)));
        ensure!(
            ready,
            "docs/28 §5: a Ready owed after a reported stall is never dropped"
        );
    }
    Ok(())
}

fn seat_names(seat: &HostSeat) -> Vec<&str> {
    match seat {
        HostSeat::Known(name) => vec![name.0.as_str()],
        HostSeat::Among(names) => names.iter().map(|n| n.0.as_str()).collect(),
        HostSeat::Me | HostSeat::Unknown => Vec::new(),
    }
}

/// Spelled out apart from the production rule: a synced guest with no player running, none of
/// whose seat is listed.
fn owes_host_left(m: &Member) -> bool {
    let listed = |name: &&str| m.participants.iter().any(|p| p.0 == *name);
    let present = match &m.host {
        HostSeat::Me => true,
        HostSeat::Unknown => false,
        seat => seat_names(seat).iter().any(listed),
    };
    let player = matches!(&m.queue, Queue::Set { local, .. } if *local != Local::Stopped);
    matches!(m.clock, Clock::Synced(_)) && !player && !present
}

/// docs/28 §1: the seat is latched at entry from that GroupJoined (never us, never a later
/// joiner); host-left fires at most once, only for a synced guest with no player running and no
/// candidate listed, and is never owed by a member at rest.
fn check_host(st: &Step) -> Result<(), String> {
    let (pm, qm) = (st.pm(), st.qm());
    if let Some(b) = qm {
        ensure!(
            !seat_names(&b.host).contains(&ME) || b.host == HostSeat::Me,
            "a guest never names itself host"
        );
        ensure!(!owes_host_left(b), "host-left is never owed at rest");
        match pm {
            Some(a) if a.membership == b.membership => ensure!(
                a.host == b.host,
                "the seat is latched once and survives rejoins"
            ),
            _ => {
                let Some(GroupUpdate::GroupJoined(info)) = update_of(st.event) else {
                    return Err("a membership starts only from a GroupJoined".into());
                };
                let mut listed: Vec<&str> = info.participants.iter().map(String::as_str).collect();
                let ours = listed.iter().position(|n| *n == ME);
                let want = match st.before() {
                    State::Creating { .. } => HostSeat::Me,
                    _ => {
                        if let Some(i) = ours {
                            listed.remove(i);
                        }
                        let candidates = listed.iter().map(|n| Name((*n).into())).collect();
                        match (ours, listed.len()) {
                            (Some(_), 0) => HostSeat::Unknown,
                            (Some(_), 1) => HostSeat::Known(Name(listed[0].into())),
                            _ => HostSeat::Among(candidates),
                        }
                    }
                };
                ensure!(b.host == want, "the seat comes from the entry's own list");
            }
        }
    }
    let notices = st
        .fx
        .iter()
        .filter(|e| **e == Effect::Ui(Notice::HostLeft))
        .count();
    let host_left = matches!(st.after(), State::Leaving(l) if l.reason == LeaveReason::HostLeft);
    let from_member = pm.is_some() && !matches!(st.before(), State::Leaving(_));
    ensure!(
        notices <= 1 && (notices == 1) == (host_left && from_member),
        "host-left says so once, exactly when a member leaves for it"
    );
    if let (true, Some(a)) = (notices == 1, pm) {
        ensure!(
            a.host != HostSeat::Me && st.count(&RestCall::Leave) == 1,
            "only a guest leaves for its host, with one Leave"
        );
        // The member as the event left it, before the host-left check turned it into Leaving.
        let mut m = a.clone();
        let mut out = Out {
            fx: Vec::new(),
            last_tag: st.pre.last_tag,
        };
        ensure!(
            m.step(st.event.clone(), st.now, &mut out).is_none() && owes_host_left(&m),
            "host-left only for a synced guest with no player and no candidate listed"
        );
    }
    Ok(())
}

fn sweep(
    sp: &SyncPlay,
    mono: u64,
    letters: &[Letter],
    depth: usize,
    path: &mut Vec<&'static str>,
    steps: &mut u64,
) {
    if depth == 0 {
        return;
    }
    let now = now_at(mono);
    for l in letters {
        let mut next = sp.clone();
        let event = (l.make)(sp, now);
        let fx = next.step(event.clone(), now);
        *steps += 1;
        if let Err(why) = check(sp, &next, &event, &fx, (l.stale)(sp), now) {
            panic!(
                "{why}\n  after {path:?}\n  then {}\n  effects {fx:?}",
                l.name
            );
        }
        path.push(l.name);
        sweep(&next, mono + 1_000, letters, depth - 1, path, steps);
        path.pop();
    }
}

fn run_sweep(seeds: Vec<(&'static str, Rig)>, letters: &[Letter], depth: usize) -> u64 {
    let mut steps = 0;
    for (name, rig) in seeds {
        let mut path = vec![name];
        sweep(
            &rig.sp,
            rig.mono + 1_000,
            letters,
            depth,
            &mut path,
            &mut steps,
        );
    }
    steps
}

#[test]
fn every_length_4_sequence_over_the_core_alphabet_keeps_the_invariants() {
    let letters = core_letters();
    let seeds = sweep_seeds();
    let n = seeds.len() as u64;
    let steps = run_sweep(seeds, &letters, 4);
    let per_seed: u64 = (1..=4).map(|d| (letters.len() as u64).pow(d)).sum();
    assert_eq!(steps, n * per_seed);
}

#[test]
fn every_length_3_sequence_over_the_full_alphabet_keeps_the_invariants() {
    let mut letters = core_letters();
    letters.extend(extra_letters());
    let mut seeds = sweep_seeds();
    seeds.extend(late_seeds());
    let n = seeds.len() as u64;
    let steps = run_sweep(seeds, &letters, 3);
    let per_seed: u64 = (1..=3).map(|d| (letters.len() as u64).pow(d)).sum();
    assert_eq!(steps, n * per_seed);
}

/// docs/28 §4: after leaving an unanswered create or join, the Leave's own answer, not the
/// server's NotInGroup or GroupLeft, ends Leaving, so no new group starts before a second Leave.
#[test]
fn leaving_an_unanswered_entry_ends_only_on_its_request_done() {
    for start in [create(), join_group()] {
        for reply in [
            group_signal(GroupUpdate::NotInGroup),
            group_signal(GroupUpdate::GroupLeft),
        ] {
            let mut rig = Rig::new();
            rig.ev(start.clone());
            rig.ev(Event::Leave);
            let req = leave_req(&rig);
            assert_eq!(rig.ev(reply), []);
            assert!(matches!(rig.sp.state(), State::Leaving(_)));
            rig.ev(Event::RequestDone { req });
            assert_eq!(rig.sp.state(), &State::Idle);
        }
    }
}
